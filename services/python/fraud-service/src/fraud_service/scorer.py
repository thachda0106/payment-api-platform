"""
Multi-rule fraud detection engine.

Rules:
  1. High-value transactions (>$1000) → REVIEW
  2. Velocity check (>3 txns per minute per customer) → REJECTED
  3. Merchant blacklist → REJECTED

Returns: FraudResult with score, decision, reason.
"""

import time
from dataclasses import dataclass
from typing import Dict


@dataclass
class FraudResult:
    payment_id: str
    score: float
    decision: str  # APPROVED, REVIEW, REJECTED
    reason: str


class FraudScorer:
    HIGH_VALUE_THRESHOLD = 1000.00
    VELOCITY_THRESHOLD = 3       # max transactions per minute
    VELOCITY_WINDOW = 60          # seconds
    BLACKLISTED_MERCHANTS = {"fraud-merchant-1", "suspicious-merchant-99"}

    def __init__(self):
        # In-memory velocity tracker (per customer: list of timestamps)
        # Phase 7: in-memory. Phase 8: Redis-backed.
        self._velocity_tracker: Dict[str, list] = {}

    def score(self, payment: dict) -> FraudResult:
        """Score a payment and return decision."""
        payment_id = payment.get("paymentId", "unknown")
        amount = float(payment.get("amount", 0))
        customer_id = payment.get("customerId", "")
        merchant_id = payment.get("merchantId", "")

        score = 0.0
        reasons = []
        decision = "APPROVED"

        # Rule 1: High-value transaction
        if amount > self.HIGH_VALUE_THRESHOLD:
            decision = "REVIEW"
            score = max(score, 30.0)
            reasons.append(f"High-value: ${amount:.2f} > ${self.HIGH_VALUE_THRESHOLD}")

        # Rule 2: Velocity check
        velocity_count = self._check_velocity(customer_id)
        if velocity_count > self.VELOCITY_THRESHOLD:
            decision = "REJECTED"
            score = max(score, 80.0)
            reasons.append(
                f"Velocity exceeded: {velocity_count} txns in {self.VELOCITY_WINDOW}s"
            )

        # Rule 3: Merchant blacklist
        if merchant_id in self.BLACKLISTED_MERCHANTS:
            decision = "REJECTED"
            score = max(score, 100.0)
            reasons.append(f"Blacklisted merchant: {merchant_id}")

        if not reasons:
            reasons.append("Low risk transaction")

        return FraudResult(
            payment_id=payment_id,
            score=score,
            decision=decision,
            reason="; ".join(reasons),
        )

    def _check_velocity(self, customer_id: str) -> int:
        """Count transactions in the velocity window."""
        now = time.time()
        if customer_id not in self._velocity_tracker:
            self._velocity_tracker[customer_id] = []

        # Clean old entries
        window_start = now - self.VELOCITY_WINDOW
        self._velocity_tracker[customer_id] = [
            t for t in self._velocity_tracker[customer_id] if t > window_start
        ]

        # Record this transaction
        self._velocity_tracker[customer_id].append(now)

        return len(self._velocity_tracker[customer_id])
