# Mini Project — Async Fraud Check Service

## Goal

Build an async fraud checking service that runs multiple checks concurrently (velocity, amount threshold, ML pattern), aggregates results, and returns a fraud score — all within a 50ms budget.

## What You Will Build

A Python service using asyncio that:
- Accepts fraud check requests (user_id, amount, merchant_id, device_id)
- Runs 3 checks CONCURRENTLY: velocity check, amount threshold check, fraud pattern check
- Each check simulates I/O (database, Redis, ML model) with realistic async delays
- Aggregates scores and returns a decision (ALLOW/REVIEW/BLOCK)
- Enforces a 50ms timeout — if checks are too slow, returns ALLOW fallback
- Processes 10+ concurrent requests efficiently

## Architecture

```
Request → FraudService.check()
              │
              ├── asyncio.create_task(check_velocity)   ──┐
              ├── asyncio.create_task(check_amount)      ──┤ asyncio.gather
              └── asyncio.create_task(check_pattern)     ──┘  (concurrent)
              │
              ▼
         Aggregate scores → decision
         │
         ▼
      Response {score, decision, checks[]}
```

## Run

```bash
python fraud_service.py
```

## Acceptance Criteria

1. Normal transaction (100K VND) → ALLOW
2. Large transaction (50M VND) → REVIEW or BLOCK
3. New device → higher risk score
4. 10 concurrent checks complete in < 500ms (concurrent I/O)
5. 50ms timeout triggers ALLOW fallback

## What You Will Learn

- How `asyncio.create_task` + `asyncio.gather` enable concurrent I/O
- How `asyncio.wait_for` enforces timeouts
- How to aggregate results from multiple async checks
- The pattern for building an async service that maps directly to FastAPI
