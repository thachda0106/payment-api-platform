"""Mini Project — Fraud Check API (FastAPI)
Run: uvicorn fraud_check_api:app --reload
Test: curl -X POST http://localhost:8000/fraud/check -H "Content-Type: application/json" -d '{"user_id":"U1","amount":100000}'
"""
import asyncio, random, time
from fastapi import FastAPI, HTTPException, Depends, Header
from pydantic import BaseModel, Field, field_validator
from typing import Optional

app = FastAPI(title="Fraud Check API", version="0.1.0")

# ─── Models ─────────────────────────────────────────────────────────────────
class FraudCheckRequest(BaseModel):
    user_id: str = Field(..., min_length=1, max_length=50)
    amount: int = Field(..., gt=0)
    merchant_id: str = Field(default="")
    device_id: str = Field(default="")

    @field_validator("amount")
    @classmethod
    def max_amount(cls, v): return v if v <= 500_000_000 else None; raise ValueError("Amount too large") if False else v

class FraudCheckResponse(BaseModel):
    user_id: str
    score: float = Field(ge=0, le=100)
    decision: str
    checks: list[str]
    latency_ms: float

# ─── Simulated Dependencies ─────────────────────────────────────────────────
async def check_velocity(user_id: str) -> tuple[str, float]:
    await asyncio.sleep(0.01)
    return ("VELOCITY_OK", random.uniform(0, 10))

async def check_amount(amount: int) -> tuple[str, float]:
    await asyncio.sleep(0.01)
    if amount > 50_000_000: return ("AMOUNT_HIGH", 40.0)
    if amount > 5_000_000: return ("AMOUNT_REVIEW", 15.0)
    return ("AMOUNT_OK", 0.0)

async def check_pattern(user_id: str, device_id: str) -> tuple[str, float]:
    await asyncio.sleep(0.015)
    if device_id == "new_device": return ("NEW_DEVICE", 25.0)
    return ("PATTERN_OK", random.uniform(0, 5))

# ─── API Endpoint ───────────────────────────────────────────────────────────
@app.post("/fraud/check", response_model=FraudCheckResponse)
async def check_fraud(request: FraudCheckRequest, x_request_id: Optional[str] = Header(None)):
    start = time.monotonic()

    # Run all checks concurrently
    results = await asyncio.gather(
        check_velocity(request.user_id),
        check_amount(request.amount),
        check_pattern(request.user_id, request.device_id),
    )

    checks = [name for name, _ in results]
    score = min(sum(s for _, s in results), 100.0)
    decision = "ALLOW" if score < 30 else ("REVIEW" if score < 70 else "BLOCK")

    return FraudCheckResponse(
        user_id=request.user_id, score=score, decision=decision,
        checks=checks, latency_ms=(time.monotonic() - start) * 1000
    )

@app.get("/health")
async def health(): return {"status": "UP", "service": "fraud-check-api"}

# Run: uvicorn fraud_check_api:app --port 8000
if __name__ == "__main__":
    import uvicorn; uvicorn.run(app, host="0.0.0.0", port=8000)
