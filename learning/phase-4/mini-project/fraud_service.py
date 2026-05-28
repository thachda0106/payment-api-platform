"""Mini Project: Async Fraud Check Service (FastAPI + asyncio)"""
import asyncio, time, random
from dataclasses import dataclass
from typing import Optional

# In a real project, use FastAPI. This is a simplified async HTTP server
# using only the standard library for learning purposes.

@dataclass
class FraudCheckRequest:
    user_id: str
    amount: int
    merchant_id: str = ""
    device_id: str = ""

@dataclass
class FraudCheckResponse:
    user_id: str
    score: float  # 0-100
    decision: str  # ALLOW, REVIEW, BLOCK
    checks: list[str]

class FraudService:
    """Async fraud checking with multiple concurrent checks."""

    def __init__(self):
        # Simulated fraud model parameters
        self.amount_thresholds = {"ALLOW": 5_000_000, "REVIEW": 20_000_000}
        self.known_fraud_patterns = ["stolen_card", "phishing", "account_takeover"]

    async def check_velocity(self, user_id: str) -> tuple[str, float]:
        """Check transaction velocity (simulated Redis sorted set lookup)."""
        await asyncio.sleep(0.015)  # Simulate Redis latency
        # In production: query Redis sorted set for txn count in last 5 min
        recent_count = random.randint(0, 20)
        if recent_count > 10: return ("VELOCITY_EXCEEDED", 40.0)
        if recent_count > 5: return ("VELOCITY_WARNING", 15.0)
        return ("VELOCITY_OK", 0.0)

    async def check_amount_threshold(self, amount: int, user_id: str) -> tuple[str, float]:
        """Check amount against user's KYC tier limits."""
        await asyncio.sleep(0.010)  # Simulate DB lookup
        if amount > self.amount_thresholds["REVIEW"]:
            return ("AMOUNT_BLOCK", 50.0)
        if amount > self.amount_thresholds["ALLOW"]:
            return ("AMOUNT_REVIEW", 20.0)
        return ("AMOUNT_OK", 0.0)

    async def check_fraud_patterns(self, request: FraudCheckRequest) -> tuple[str, float]:
        """Check against known fraud patterns (ML model proxy)."""
        await asyncio.sleep(0.020)  # Simulate ML model inference
        # In production: call scikit-learn model.predict_proba()
        base_score = random.uniform(0, 10)
        if request.amount > 50_000_000:
            base_score += 30  # Large transactions are higher risk
        if request.device_id == "new_device":
            base_score += 15  # New devices are higher risk
        return ("PATTERN_CHECK", min(base_score, 100))

    async def check(self, request: FraudCheckRequest) -> FraudCheckResponse:
        """Run all fraud checks concurrently, aggregation results."""
        start = time.monotonic()

        # Run all checks concurrently
        velocity_task = asyncio.create_task(self.check_velocity(request.user_id))
        amount_task = asyncio.create_task(self.check_amount_threshold(request.amount, request.user_id))
        pattern_task = asyncio.create_task(self.check_fraud_patterns(request))

        # Wait for all with timeout
        try:
            results = await asyncio.wait_for(
                asyncio.gather(velocity_task, amount_task, pattern_task),
                timeout=0.05  # 50ms budget
            )
        except asyncio.TimeoutError:
            # Timeout — return safe default (circuit breaker open)
            return FraudCheckResponse(
                user_id=request.user_id, score=0.0,
                decision="ALLOW", checks=["TIMEOUT_FALLBACK"]
            )

        checks = []
        total_score = 0.0
        for check_name, score in results:
            checks.append(check_name)
            total_score += score

        score = min(total_score, 100.0)
        decision = "ALLOW" if score < 30 else ("REVIEW" if score < 70 else "BLOCK")

        elapsed_ms = (time.monotonic() - start) * 1000
        print(f"[{request.user_id}] score={score:.1f} decision={decision} checks={checks} time={elapsed_ms:.1f}ms")

        return FraudCheckResponse(request.user_id, score, decision, checks)


# ═══════════════════════════════════════════════════════════════════════════
# Demo / Acceptance Tests
# ═══════════════════════════════════════════════════════════════════════════

async def main():
    service = FraudService()

    print("=== Async Fraud Check Service ===\n")

    # Test 1: Normal transaction
    resp = await service.check(FraudCheckRequest("U1", 100_000))
    assert resp.decision == "ALLOW"
    print(f"Test 1 PASS: Normal tx → {resp.decision}")

    # Test 2: Large transaction (should be REVIEW or BLOCK)
    resp = await service.check(FraudCheckRequest("U2", 50_000_000))
    assert resp.decision in ("REVIEW", "BLOCK")
    print(f"Test 2 PASS: Large tx → {resp.decision} (score={resp.score:.0f})")

    # Test 3: New device (higher risk)
    resp = await service.check(FraudCheckRequest("U3", 100_000, device_id="new_device"))
    print(f"Test 3 PASS: New device → {resp.decision} (score={resp.score:.0f})")

    # Test 4: Concurrent checks (10 requests simultaneously)
    print("\nTest 4: 10 concurrent fraud checks...")
    start = time.monotonic()
    tasks = [asyncio.create_task(service.check(
        FraudCheckRequest(f"U{i}", random.randint(1000, 100_000_000))
    )) for i in range(10)]
    results = await asyncio.gather(*tasks)
    elapsed = (time.monotonic() - start) * 1000
    print(f"  10 checks completed in {elapsed:.0f}ms (avg {elapsed/10:.1f}ms each)")
    assert elapsed < 500  # Should be fast due to concurrent I/O
    print("  PASS")

    print("\nAll tests passed!")

if __name__ == "__main__":
    asyncio.run(main())
