"""
Multi-rule fraud detection engine.

Rules:
  1. High-value transactions (> threshold) → REVIEW
  2. Velocity check (> N txns per window per customer) → REJECTED
  3. Merchant blacklist → REJECTED

Returns: FraudResult with score, decision, reason.
Thresholds come from FraudSettings (env FRAUD_*).
"""

import time
from dataclasses import dataclass
from typing import Dict, List, Optional


@dataclass
class FraudResult:
    payment_id: str
    score: float
    decision: str  # APPROVED, REVIEW, REJECTED
    reason: str


class FraudScorer:
    BLACKLISTED_MERCHANTS = {"fraud-merchant-1", "suspicious-merchant-99"}

    def __init__(self, settings: Optional[object] = None):
        if settings is None:
            from fraud_service.config import fraud_settings  # lazy import (avoids hard dep in tests)
            settings = fraud_settings
        self._cfg = settings
        # In-memory velocity tracker (per customer: list of timestamps).
        # Phase 7: in-memory. Phase 8: Redis-backed.
        self._velocity_tracker: Dict[str, List[float]] = {}
        self._score_count = 0

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
        if amount > self._cfg.high_value_threshold:
            decision = "REVIEW"
            score = max(score, 30.0)
            reasons.append(f"High-value: ${amount:.2f} > ${self._cfg.high_value_threshold}")

        # Rule 2: Velocity check
        velocity_count = self._check_velocity(customer_id)
        if velocity_count > self._cfg.velocity_threshold:
            decision = "REJECTED"
            score = max(score, 80.0)
            reasons.append(
                f"Velocity exceeded: {velocity_count} txns in {self._cfg.velocity_window_seconds}s"
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
        """Count transactions in the velocity window (bounded memory)."""
        now = time.time()
        window_start = now - self._cfg.velocity_window_seconds

        timestamps = self._velocity_tracker.get(customer_id, [])
        timestamps = [t for t in timestamps if t > window_start]
        timestamps.append(now)
        self._velocity_tracker[customer_id] = timestamps

        # Periodically evict customers with no activity in the window so the
        # tracker does not grow unbounded with one-off customers.
        self._score_count += 1
        if self._score_count % self._cfg.velocity_sweep_every == 0:
            self._sweep_stale(window_start)

        return len(timestamps)

    def _sweep_stale(self, window_start: float) -> None:
        stale = [c for c, ts in self._velocity_tracker.items()
                 if not ts or ts[-1] <= window_start]
        for c in stale:
            del self._velocity_tracker[c]
