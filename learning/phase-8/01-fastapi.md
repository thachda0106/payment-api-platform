# Module 01 — FastAPI (Python)

## 1.1 FastAPI Architecture

FastAPI is built on Starlette (ASGI framework) + Pydantic (validation). ASGI is the async successor to WSGI — supports WebSockets, HTTP/2, and long-lived connections.

```
Client Request
    │
    ▼
  Uvicorn (ASGI server) → Starlette (ASGI app)
    │
    ▼
  Middleware Stack (CORS, GZip, custom)
    │
    ▼
  Router → Path Operation → Dependency Resolution → Pydantic Validation → Handler
    │
    ▼
  Response (Pydantic model → JSON)
```

## 1.2 Routes and Path Operations

```python
from fastapi import FastAPI, Path, Query, HTTPException, status
from pydantic import BaseModel, Field

app = FastAPI(title="Fraud Service", version="0.1.0")

class FraudCheckRequest(BaseModel):
    user_id: str = Field(..., min_length=1, max_length=50)
    amount: int = Field(..., gt=0, description="Amount in smallest currency unit")
    currency: str = Field(default="VND", min_length=3, max_length=3)
    merchant_id: str | None = None
    device_id: str | None = None

class FraudCheckResponse(BaseModel):
    user_id: str
    score: float = Field(ge=0, le=100)
    decision: str  # ALLOW, REVIEW, BLOCK
    checks: list[str]

@app.post("/fraud/check", response_model=FraudCheckResponse,
          status_code=status.HTTP_200_OK)
async def check_fraud(request: FraudCheckRequest):
    return FraudCheckResponse(
        user_id=request.user_id, score=15.0, decision="ALLOW",
        checks=["velocity_ok", "amount_ok"]
    )

@app.get("/fraud/rules/{rule_id}")
async def get_rule(rule_id: str = Path(..., min_length=1),
                   include_disabled: bool = Query(False)):
    return {"rule_id": rule_id, "active": not include_disabled}
```

## 1.3 Dependency Injection

```python
from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession

# Dependency: database session
async def get_db() -> AsyncSession:
    async with async_session() as session:
        yield session  # Cleanup happens after response

# Dependency: current user from JWT
async def get_current_user(token: str = Header(...)) -> dict:
    payload = jwt.decode(token, PUBLIC_KEY, algorithms=["RS256"])
    return payload

# Use dependencies in route
@app.post("/fraud/check")
async def check_fraud(
    request: FraudCheckRequest,
    db: AsyncSession = Depends(get_db),
    user: dict = Depends(get_current_user),
):
    # db is an async session, user is the decoded JWT
    pass
```

## 1.4 Pydantic v2 Validation

```python
from pydantic import BaseModel, field_validator, model_validator, Field
from typing import Literal

class CreatePaymentRequest(BaseModel):
    amount: int = Field(gt=0)
    currency: Literal["VND", "USD"]
    source_account: str
    destination_account: str

    @field_validator("amount")
    @classmethod
    def amount_not_too_large(cls, v: int) -> int:
        if v > 500_000_000: raise ValueError("Amount exceeds maximum (500M VND)")
        return v

    @model_validator(mode="after")
    def accounts_must_differ(self):
        if self.source_account == self.destination_account:
            raise ValueError("Source and destination must differ")
        return self
```

## 1.5 SQLAlchemy Async

```python
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession, async_sessionmaker
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy import select

engine = create_async_engine("postgresql+asyncpg://payment:@localhost/fraud_db")
async_session = async_sessionmaker(engine, expire_on_commit=False)

class Base(DeclarativeBase): pass

class FraudRule(Base):
    __tablename__ = "fraud_rules"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str]
    threshold: Mapped[float]
    is_active: Mapped[bool] = mapped_column(default=True)

async def get_active_rules(db: AsyncSession) -> list[FraudRule]:
    result = await db.execute(select(FraudRule).where(FraudRule.is_active == True))
    return result.scalars().all()
```

## 1.6 Testing

```python
from httpx import AsyncClient, ASGITransport
from main import app
import pytest

@pytest.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac

@pytest.mark.asyncio
async def test_fraud_check_valid(client):
    resp = await client.post("/fraud/check", json={"user_id": "U1", "amount": 100000})
    assert resp.status_code == 200
    assert resp.json()["decision"] in ("ALLOW", "REVIEW", "BLOCK")

@pytest.mark.asyncio
async def test_fraud_check_missing_field(client):
    resp = await client.post("/fraud/check", json={"user_id": "U1"})
    assert resp.status_code == 422
```

## 1.7 Exercises

### Ex 1.1 — CRUD API
Build a CRUD API for fraud rules: `POST /rules`, `GET /rules`, `GET /rules/{id}`, `PUT /rules/{id}`, `DELETE /rules/{id}`. Use SQLAlchemy async for persistence. Write pytest tests for all endpoints.

### Ex 1.2 — Custom Dependency
Create a `Depends(get_rate_limit)` dependency that checks Redis for rate limiting. If rate limited, raise HTTPException 429.

### Ex 1.3 — Middleware
Write a custom ASGI middleware that logs request duration and adds `X-Request-ID` header if not present.

---

## 1.8 Self-Assessment

- [ ] Can create a FastAPI app with path/query/body parameters
- [ ] Can use Depends() for database sessions, auth, and business logic
- [ ] Can write Pydantic field_validator and model_validator
- [ ] Can test with httpx.AsyncClient and pytest fixtures
- [ ] Understand the ASGI middleware stack order
