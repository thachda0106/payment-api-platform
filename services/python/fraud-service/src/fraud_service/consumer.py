"""
Kafka consumer for fraud-service.
Consumes PaymentCreated events from payment-events topic.
Atomic idempotency via INSERT ... ON CONFLICT DO NOTHING.
Publishes PaymentApproved/PaymentRejected to fraud-events topic.
"""

import json
import uuid
import logging
from datetime import datetime, timezone
from typing import Optional

from fraud_service.config import settings
from fraud_service.scorer import FraudScorer

logger = logging.getLogger(__name__)


class FraudConsumer:
    """Kafka consumer with atomic idempotency and multi-rule scoring."""

    def __init__(self, db_pool, kafka_producer):
        self.db = db_pool
        self.producer = kafka_producer
        self.scorer = FraudScorer()

    async def handle(self, event: dict) -> None:
        """Process a PaymentCreated event with idempotency."""
        event_id = event.get("eventId")
        payment_id = event.get("paymentId", "unknown")

        if not event_id:
            logger.warning(f"Event missing eventId for payment {payment_id} — skipping")
            return

        # Atomic idempotency: INSERT ... ON CONFLICT DO NOTHING
        async with self.db.acquire() as conn:
            result = await conn.execute(
                """INSERT INTO processed_events (event_id, consumer_group, processed_at)
                   VALUES ($1, $2, now())
                   ON CONFLICT (event_id, consumer_group) DO NOTHING
                   RETURNING event_id""",
                event_id, "fraud-service",
            )
            if not result:
                logger.info(f"Duplicate event {event_id} — skipping")
                return

        # Score the transaction
        result = self.scorer.score(event)
        logger.info(
            f"Fraud check: payment={payment_id} score={result.score} decision={result.decision}"
        )

        # Save fraud score to DB
        async with self.db.acquire() as conn:
            await conn.execute(
                """INSERT INTO fraud_scores (id, payment_id, score, decision, reason)
                   VALUES ($1, $2, $3, $4, $5)""",
                str(uuid.uuid4()), payment_id, result.score, result.decision, result.reason,
            )

        # Publish result to fraud-events topic
        event_type = "PaymentApproved" if result.decision == "APPROVED" else "PaymentRejected"
        outbox_event = {
            "eventId": str(uuid.uuid4()),
            "type": event_type,
            "paymentId": payment_id,
            "score": result.score,
            "decision": result.decision,
            "reason": result.reason,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

        await self.producer.send_and_wait(
            "fraud-events",
            key=payment_id.encode() if payment_id else None,
            value=json.dumps(outbox_event).encode(),
        )
        logger.info(f"Published {event_type} for payment {payment_id}")
