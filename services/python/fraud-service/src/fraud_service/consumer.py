"""
Kafka consumer (Avro) + inbox pattern for fraud-service (Phase-9 P2).

Consumes `payments.payment.created` (Avro/CloudEvents), scores the payment, and
writes the fraud result to the CDC `outbox` (payments.payment.succeeded/failed).
Uses the inbox pattern:
  1. decode + normalize the event → claim inbox (PENDING); commit Kafka offset
  2. process (score + outbox insert + mark COMPLETED) in one transaction
  3. on failure mark FAILED; InboxRetryScheduler retries with backoff → DLQ
"""

import asyncio
import json
import os
import uuid
import logging
from datetime import datetime, timezone

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from fraud_service.avro_registry import AvroRegistryDecoder
from fraud_service.scorer import FraudScorer

logger = logging.getLogger(__name__)

SOURCE_TOPIC = "payments.payment.created"
DLQ_TOPIC = "payments.dlq"
CONSUMER_GROUP = "fraud-service"
MAX_RETRIES = 5
RETRY_INTERVAL_SECONDS = 5
CURRENCY_RE = __import__("re").compile(r"^[A-Z]{3}$")


def _kafka_security() -> dict:
    """SASL kwargs for aiokafka from env (Phase-9 P4). Default PLAINTEXT (no auth)."""
    proto = os.getenv("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT")
    kw: dict = {"security_protocol": proto}
    if proto.startswith("SASL"):
        kw["sasl_mechanism"] = os.getenv("KAFKA_SASL_MECHANISM", "PLAIN")
        kw["sasl_plain_username"] = os.getenv("KAFKA_SASL_USERNAME", "")
        kw["sasl_plain_password"] = os.getenv("KAFKA_SASL_PASSWORD", "")
    return kw


def _registry_auth() -> tuple[str, str] | None:
    user = os.getenv("SR_BASIC_AUTH_USER", "")
    return (user, os.getenv("SR_BASIC_AUTH_PASS", "")) if user else None


def _normalize(event: dict) -> dict:
    """Flatten Avro/CloudEvents → inbox payload; raises ValueError if invalid."""
    data = event.get("data") or {}
    n = {
        "eventId": event.get("id"),
        "paymentId": data.get("payment_id"),
        "customerId": data.get("customer_id"),
        "merchantId": data.get("merchant_id"),
        "amountMinor": data.get("amount"),
        "currency": data.get("currency"),
    }
    if not n["eventId"]:
        raise ValueError("missing id")
    uuid.UUID(str(n["paymentId"]))  # raises if not a UUID / missing
    if not n["customerId"] or not n["merchantId"]:
        raise ValueError("missing customer_id/merchant_id")
    if not n["currency"] or not CURRENCY_RE.match(str(n["currency"])):
        raise ValueError("currency not ISO 4217")
    if n["amountMinor"] is None or int(n["amountMinor"]) <= 0:
        raise ValueError("amount must be > 0")
    return n


class FraudProcessor:
    """Scores + writes outbox + marks inbox COMPLETED in one transaction."""

    def __init__(self, db_pool):
        self.db = db_pool
        self.scorer = FraudScorer()

    async def process(self, payload_json: str) -> None:
        p = json.loads(payload_json)
        payment_id = p["paymentId"]
        amount_minor = int(p["amountMinor"])

        scoring_input = {
            "paymentId": payment_id,
            "amount": amount_minor,  # minor units (cents) — thresholds are in minor units
            "customerId": p["customerId"],
            "merchantId": p["merchantId"],
        }
        result = self.scorer.score(scoring_input)

        approved = result.decision == "APPROVED"
        out_id = str(uuid.uuid4())
        event_type = "payment.succeeded" if approved else "payment.failed"
        event_topic = "payments.payment.succeeded" if approved else "payments.payment.failed"
        data = {
            "payment_id": payment_id,
            "customer_id": p["customerId"],
            "merchant_id": p["merchantId"],
            "amount": amount_minor,
            "currency": p["currency"],
            "fraud_score": result.score,
            "fraud_decision": result.decision,
        }
        if not approved:
            data["reason"] = result.reason
        envelope = {
            "id": out_id, "type": event_type,
            "time": datetime.now(timezone.utc).isoformat(),
            "data": data,
            "trigger": {"service": "fraud-service", "instance": "", "request_id": "", "idempotency_key": ""},
        }

        async with self.db.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    """INSERT INTO fraud_scores (id, payment_id, score, decision, reason)
                       VALUES ($1, $2, $3, $4, $5)""",
                    str(uuid.uuid4()), payment_id, result.score, result.decision, result.reason,
                )
                await conn.execute(
                    """INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type,
                                           event_topic, payload, partition_key)
                       VALUES ($1, 'payment', $2, $3, $4, $5::jsonb, $2)""",
                    out_id, payment_id, event_type, event_topic, json.dumps(envelope),
                )
                await conn.execute(
                    """UPDATE consumer_inbox SET status='COMPLETED', updated_at=now()
                       WHERE event_id=$1::uuid AND consumer_group=$2""",
                    p["eventId"], CONSUMER_GROUP,
                )
        logger.info("Fraud scored payment=%s decision=%s", payment_id, result.decision)


async def _claim(pool, event_id: str, payload_json: str) -> str:
    """INSERT PENDING (idempotent) and return current status."""
    async with pool.acquire() as conn:
        await conn.execute(
            """INSERT INTO consumer_inbox (event_id, consumer_group, status, payload)
               VALUES ($1::uuid, $2, 'PENDING', $3::jsonb)
               ON CONFLICT (event_id, consumer_group) DO NOTHING""",
            event_id, CONSUMER_GROUP, payload_json,
        )
        return await conn.fetchval(
            "SELECT status FROM consumer_inbox WHERE event_id=$1::uuid AND consumer_group=$2",
            event_id, CONSUMER_GROUP,
        )


async def _mark_failed(pool, event_id: str, error: str) -> None:
    async with pool.acquire() as conn:
        await conn.execute(
            """UPDATE consumer_inbox SET status='FAILED', last_error=$1, updated_at=now()
               WHERE event_id=$2::uuid AND consumer_group=$3""",
            error[:1000], event_id, CONSUMER_GROUP,
        )


async def run_consumer(db_pool, bootstrap_servers: str, registry_url: str, state: dict) -> None:
    consumer = AIOKafkaConsumer(
        SOURCE_TOPIC, bootstrap_servers=bootstrap_servers, group_id=CONSUMER_GROUP,
        enable_auto_commit=False, auto_offset_reset="earliest", **_kafka_security(),
    )
    dlq_producer = AIOKafkaProducer(bootstrap_servers=bootstrap_servers, acks="all",
                                    enable_idempotence=True, **_kafka_security())
    decoder = AvroRegistryDecoder(registry_url, auth=_registry_auth())
    processor = FraudProcessor(db_pool)
    await consumer.start()
    await dlq_producer.start()
    state["kafka"] = True
    logger.info("fraud consumer started on topic %s (Avro)", SOURCE_TOPIC)
    try:
        async for msg in consumer:
            try:
                event = await decoder.decode(msg.value)
                normalized = _normalize(event)
                payload_json = json.dumps(normalized)
            except Exception as exc:  # decode/validation → non-retryable → DLQ
                logger.error("Invalid event → DLQ: %s", exc)
                await _to_dlq(dlq_producer, msg.key, msg.value, exc)
                await consumer.commit()
                continue

            event_id = normalized["eventId"]
            status = await _claim(db_pool, event_id, payload_json)
            if status == "COMPLETED":
                await consumer.commit()
                continue
            try:
                await processor.process(payload_json)
            except Exception:
                logger.exception("Processing failed for %s (will retry)", event_id)
                await _mark_failed(db_pool, event_id, "processing error")
            await consumer.commit()
    finally:
        state["kafka"] = False
        await consumer.stop()
        await dlq_producer.stop()


async def run_retry_scheduler(db_pool, bootstrap_servers: str) -> None:
    """Retry FAILED inbox rows with exponential backoff; route exhausted to DLQ."""
    producer = AIOKafkaProducer(bootstrap_servers=bootstrap_servers, acks="all",
                                enable_idempotence=True, **_kafka_security())
    processor = FraudProcessor(db_pool)
    await producer.start()
    try:
        while True:
            await asyncio.sleep(RETRY_INTERVAL_SECONDS)
            try:
                await _retry_batch(db_pool, processor, producer)
            except Exception:
                logger.exception("retry scheduler tick failed")
    finally:
        await producer.stop()


async def _retry_batch(pool, processor: "FraudProcessor", producer: AIOKafkaProducer) -> None:
    async with pool.acquire() as conn:
        due = await conn.fetch(
            """SELECT event_id::text AS id, payload::text AS payload FROM consumer_inbox
               WHERE consumer_group=$1 AND status='FAILED' AND retry_count < $2
                 AND updated_at < now() - (power(2, retry_count) * interval '1 second')
               ORDER BY updated_at LIMIT 50""",
            CONSUMER_GROUP, MAX_RETRIES,
        )
        exhausted = await conn.fetch(
            """SELECT event_id::text AS id, payload::text AS payload FROM consumer_inbox
               WHERE consumer_group=$1 AND status='FAILED' AND retry_count >= $2
               ORDER BY updated_at LIMIT 50""",
            CONSUMER_GROUP, MAX_RETRIES,
        )
    for row in due:
        try:
            await processor.process(row["payload"])
        except Exception as exc:
            async with pool.acquire() as conn:
                await conn.execute(
                    """UPDATE consumer_inbox SET retry_count=retry_count+1, last_error=$1, updated_at=now()
                       WHERE event_id=$2::uuid AND consumer_group=$3""",
                    str(exc)[:1000], row["id"], CONSUMER_GROUP,
                )
    for row in exhausted:
        await producer.send_and_wait(DLQ_TOPIC, value=row["payload"].encode())
        async with pool.acquire() as conn:
            await conn.execute(
                "UPDATE consumer_inbox SET status='DLQ', updated_at=now() WHERE event_id=$1::uuid AND consumer_group=$2",
                row["id"], CONSUMER_GROUP,
            )
        logger.error("Event %s exhausted retries — routed to %s", row["id"], DLQ_TOPIC)


async def _to_dlq(producer: AIOKafkaProducer, key, raw_value, error) -> None:
    raw = raw_value.hex() if isinstance(raw_value, bytes) else str(raw_value)
    wrapper = json.dumps({
        "error": str(error), "consumer": CONSUMER_GROUP,
        "timestamp": datetime.now(timezone.utc).isoformat(), "original_hex": raw,
    })
    await producer.send_and_wait(DLQ_TOPIC, key=key, value=wrapper.encode())
