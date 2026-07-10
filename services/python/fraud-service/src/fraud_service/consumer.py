"""
Kafka consumer + transactional outbox for fraud-service.

Consumes PaymentCreated events from `payment-events`, scores them, and writes the
result to `fraud_scores` AND a `fraud_outbox` row in a SINGLE database transaction
(atomic idempotency + no dual-write). A poller drains `fraud_outbox` to the
`fraud-events` topic (at-least-once; consumers dedup via processed_events).
"""

import asyncio
import json
import uuid
import logging
from datetime import datetime, timezone

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from fraud_service.events import EventValidationError, validate_payment_event
from fraud_service.scorer import FraudScorer

logger = logging.getLogger(__name__)

SOURCE_TOPIC = "payment-events"
OUTBOX_TOPIC = "fraud-events"
DLQ_TOPIC = "payment-events-dlq"
CONSUMER_GROUP = "fraud-service"
POLL_INTERVAL_SECONDS = 1.0
POLL_BATCH_SIZE = 100


class FraudConsumer:
    """Scores a payment and persists score + outbox row in one transaction."""

    def __init__(self, db_pool):
        self.db = db_pool
        self.scorer = FraudScorer()

    async def handle(self, event: dict) -> None:
        """Process one PaymentCreated event. Validation errors propagate to the caller."""
        validate_payment_event(event)

        event_id = event["eventId"]
        payment_id = event["paymentId"]

        async with self.db.acquire() as conn:
            async with conn.transaction():
                # Atomic idempotency — dedup mark shares the transaction with the writes.
                marked = await conn.fetchval(
                    """INSERT INTO processed_events (event_id, consumer_group, processed_at)
                       VALUES ($1, $2, now())
                       ON CONFLICT (event_id, consumer_group) DO NOTHING
                       RETURNING event_id""",
                    event_id, CONSUMER_GROUP,
                )
                if marked is None:
                    logger.info("Duplicate event %s — skipping", event_id)
                    return

                result = self.scorer.score(event)
                logger.info(
                    "Fraud check: payment=%s score=%s decision=%s",
                    payment_id, result.score, result.decision,
                )

                await conn.execute(
                    """INSERT INTO fraud_scores (id, payment_id, score, decision, reason)
                       VALUES ($1, $2, $3, $4, $5)""",
                    str(uuid.uuid4()), payment_id, result.score, result.decision, result.reason,
                )

                event_type = "PaymentApproved" if result.decision == "APPROVED" else "PaymentRejected"
                out_event_id = str(uuid.uuid4())
                payload = {
                    "v": 1,
                    "eventId": out_event_id,
                    "type": event_type,
                    "paymentId": payment_id,
                    "amount": str(event["amount"]),
                    "currency": event["currency"],
                    "customerId": event["customerId"],
                    "merchantId": event["merchantId"],
                    "score": result.score,
                    "decision": result.decision,
                    "reason": result.reason,
                    "timestamp": datetime.now(timezone.utc).isoformat(),
                }
                await conn.execute(
                    """INSERT INTO fraud_outbox (event_id, aggregate_id, event_type, payload)
                       VALUES ($1, $2, $3, $4::jsonb)""",
                    out_event_id, payment_id, event_type, json.dumps(payload),
                )


class FraudOutboxPoller:
    """Drains unpublished fraud_outbox rows to Kafka and stamps published_at."""

    def __init__(self, db_pool, producer: AIOKafkaProducer):
        self.db = db_pool
        self.producer = producer
        self._running = False

    async def run(self) -> None:
        self._running = True
        while self._running:
            try:
                await self._publish_batch()
            except Exception:  # pragma: no cover - defensive loop guard
                logger.exception("fraud outbox poll failed")
            await asyncio.sleep(POLL_INTERVAL_SECONDS)

    def stop(self) -> None:
        self._running = False

    async def backlog(self) -> int:
        async with self.db.acquire() as conn:
            return await conn.fetchval(
                "SELECT count(*) FROM fraud_outbox WHERE published_at IS NULL"
            )

    async def _publish_batch(self) -> None:
        async with self.db.acquire() as conn:
            rows = await conn.fetch(
                """SELECT id, aggregate_id, payload
                   FROM fraud_outbox
                   WHERE published_at IS NULL
                   ORDER BY created_at, id
                   FOR UPDATE SKIP LOCKED
                   LIMIT $1""",
                POLL_BATCH_SIZE,
            )
            for row in rows:
                await self.producer.send_and_wait(
                    OUTBOX_TOPIC,
                    key=str(row["aggregate_id"]).encode(),
                    value=row["payload"].encode() if isinstance(row["payload"], str)
                    else json.dumps(row["payload"]).encode(),
                )
                await conn.execute(
                    "UPDATE fraud_outbox SET published_at = now() WHERE id = $1", row["id"]
                )
            if rows:
                logger.debug("Published %d fraud-events", len(rows))


async def run_consumer(db_pool, bootstrap_servers: str, state: dict) -> None:
    """Consume payment-events and score them. Routes poison messages to the DLQ."""
    consumer = AIOKafkaConsumer(
        SOURCE_TOPIC,
        bootstrap_servers=bootstrap_servers,
        group_id=CONSUMER_GROUP,
        enable_auto_commit=False,
        auto_offset_reset="earliest",
    )
    dlq_producer = AIOKafkaProducer(
        bootstrap_servers=bootstrap_servers,
        acks="all",
        enable_idempotence=True,
    )
    fraud = FraudConsumer(db_pool)
    await consumer.start()
    await dlq_producer.start()
    state["kafka"] = True
    logger.info("fraud consumer started on topic %s", SOURCE_TOPIC)
    try:
        async for msg in consumer:
            try:
                event = json.loads(msg.value)
                await fraud.handle(event)
                await consumer.commit()
            except EventValidationError as exc:
                logger.error("Invalid event, routing to DLQ: %s", exc)
                await _send_to_dlq(dlq_producer, msg.key, msg.value, exc, "fraud-service")
                await consumer.commit()
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                logger.error("Unparseable event, routing to DLQ: %s", exc)
                await _send_to_dlq(dlq_producer, msg.key, msg.value, exc, "fraud-service")
                await consumer.commit()
            except Exception:
                logger.exception("Failed to process payment event")
    finally:
        state["kafka"] = False
        await consumer.stop()
        await dlq_producer.stop()


async def _send_to_dlq(producer: AIOKafkaProducer, key, raw_value,
                        error, consumer_group: str) -> None:
    dlq_payload = json.dumps({
        "error": str(error),
        "consumer": consumer_group,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    })
    raw_str = raw_value.decode("utf-8", errors="replace") if isinstance(raw_value, bytes) else str(raw_value)
    wrapper = json.dumps({"original": raw_str, "dlq_meta": dlq_payload})
    await producer.send_and_wait(
        DLQ_TOPIC,
        key=key,
        value=wrapper.encode(),
    )
