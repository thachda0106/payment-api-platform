# Module 03 — CQRS, Event Sourcing, Outbox, Idempotency & Retry

## 3.1 CQRS (Command Query Responsibility Segregation)

Separate the WRITE path (commands that change state) from the READ path (queries). Different models, different databases, different optimization strategies.

```
WRITE SIDE:                        READ SIDE:
POST /v1/payments                  GET /v1/transactions
  (Payment Service → payment_db)     (Transaction Service → transaction_db)
        │                                    ▲
        │ outbox_events                       │
        └──────────▶ Kafka ◀─────────────────┘
```

**Write model** (`payment_db`): Normalized, ACID, row-level locking. Optimized for correctness.
**Read model** (`transaction_db`): Denormalized, eventually consistent, no locking. Optimized for fast reads + aggregations.

### When to Use CQRS

- Read patterns fundamentally different from write patterns (payment: writes = single-row inserts, reads = multi-table joins + search)
- Read and write scaling requirements differ (writes 1000/s, reads 50,000/s)
- Need independent optimization per path

## 3.2 Event Sourcing

Store EVENTS, not current state. Current state = `fold(initial_state, events)`.

```
Events:
  [PaymentCreated{id=P1, amount=100K}]
  [PaymentAuthorized{id=P1}]
  [PaymentCompleted{id=P1, entryId=JE-1}]

Current state = apply(PaymentCreated) → apply(PaymentAuthorized) → apply(PaymentCompleted)
→ Payment{id=P1, amount=100K, status=COMPLETED, entryId=JE-1}
```

**Benefits**: Full audit trail (events ARE the audit log), time travel (replay to any point), bug recovery (fix projection, replay events), multiple projections from same events.

**Drawbacks**: Eventual consistency, event schema evolution (upcasting), replay time for large event logs (use snapshots).

## 3.3 Outbox Pattern

**Problem**: Dual-write (DB + Kafka) cannot be atomic without 2PC. Solution: write to `outbox_events` table in the SAME database transaction. CDC (Debezium) reads outbox → publishes to Kafka.

```sql
BEGIN;
    INSERT INTO journal_entries (...) VALUES (...);
    INSERT INTO journal_lines (...) VALUES (...);
    INSERT INTO outbox_events (event_type, payload) VALUES ('PaymentCompleted', '{"payment_id":"..."}');
COMMIT;
-- Both succeed or both fail. CDC relays to Kafka after commit.
```

**At-least-once delivery**: CDC may redeliver if it crashes before acknowledging. This is why consumers MUST be idempotent.

## 3.4 Idempotency

### Pattern

1. Client generates `Idempotency-Key: unique-value`
2. Server checks: has this key been seen?
   - **No**: Process the request. Store `key → response`. Return response.
   - **Yes (within TTL)**: Return stored response. Do NOT reprocess.
   - **Yes (expired)**: Return 409 Conflict — "use a new key."

### Storage (Two-Layer)

- **Redis**: `SET idempotency:{api_key}:{key} {response} NX EX 86400` (fast, 99.9%)
- **PostgreSQL**: `INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING` (durable fallback)

### Why Idempotency is the Foundation of Payment Reliability

Without idempotency, you CANNOT safely retry ANY operation. Network timeout → retry → double-charge. Idempotency makes retries safe.

## 3.5 Retry Strategies

### Exponential Backoff + Jitter

```
delay = min(cap, base × 2^attempt)
delay_with_jitter = delay × (0.5 + random(0, 0.5))
```

| Attempt | Delay (base=100ms) | With Jitter |
|:-------:|:------------------:|:-----------:|
| 0 | 100ms | 50-150ms |
| 1 | 200ms | 100-300ms |
| 2 | 400ms | 200-600ms |
| 3 | 800ms | 400-1200ms |
| 4 | 1.6s | 0.8-2.4s |
| 5 | 3.2s | 1.6-4.8s |

**Jitter is critical**: Without jitter, all retrying clients synchronize (thundering herd) and overload the recovering service.

### Dead Letter Queue (DLQ)

Messages that exceed max retries are routed to a DLQ for manual inspection. Never silently drop messages in a payment system.

## 3.6 Exercises

### Ex 3.1 — Outbox Pattern Implementation
Build the outbox pattern: write to outbox table in same DB transaction as business data. Implement a relay (poll outbox → publish to message queue → mark processed). Handle crash-recovery correctly.

### Ex 3.2 — Idempotent Consumer
Implement an idempotent Kafka consumer with inbox deduplication. Store processed message IDs in a database table. Inject duplicate messages. Verify exactly-once processing.

### Ex 3.3 — Retry with Jitter
Write a function that retries a flaky API call with exponential backoff + jitter. Compare success rate with vs without jitter when 100 clients retry simultaneously.

## 3.7 Self-Assessment

- [ ] Can explain CQRS and when to apply it
- [ ] Understand the outbox pattern and why it solves the dual-write problem
- [ ] Can implement idempotency with two-layer storage (Redis + PostgreSQL)
- [ ] Know why jitter is essential for retry strategies
- [ ] Understand at-least-once delivery and why idempotent consumers are required
