# PLAN: Phase 7 — Architecture Validation (A+)

**Date**: 2026-06-03
**Status**: Draft — Awaiting Approval
**Depends on**: SCRATCHPAD_PHASE7.md (APPROVED)

---

## 1. Architecture Overview

### 1.1 Service Topology

```
                    POST /v1/payments
                    Idempotency-Key: uuid
                          │
            ┌─────────────▼──────────────┐
            │     payment-service (Java)  │
            │     Port: 8081              │
            │     DB: payment_db          │
            │                             │
            │  ┌───────────────────────┐  │
            │  │ Transactional Outbox  │  │
            │  │ payments + outbox     │  │
            │  └───────────┬───────────┘  │
            └──────────────┼──────────────┘
                           │
                    [PaymentCreated]
                           │
                    ┌──────▼──────┐
                    │    Kafka    │
                    │   topics:   │
                    │ payment-    │
                    │ events      │
                    │ payment-    │
                    │ events-dlq  │
                    └──────┬──────┘
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                  │
    ┌────▼────┐      ┌─────▼──────┐    ┌─────▼──────────┐
    │ fraud   │      │ financial  │    │ notification   │
    │ service │      │ core       │    │ service        │
    │ (Python)│      │ (Java)     │    │ (Node.js)      │
    │ :8000   │      │ :8080      │    │ :3001          │
    │ fraud_db│      │ financial_ │    │ notification_db│
    │         │      │ core_db    │    │                │
    └────┬────┘      └─────┬──────┘    └─────┬──────────┘
         │                 │                  │
    [FraudChecked]   [LedgerPosted]     [ReceiptSent]
```

### 1.2 Kafka Topic Design

| Topic | Partitions | Key | Producers | Consumers |
|-------|-----------|-----|-----------|-----------|
| `payment-events` | 3 | `paymentId` | payment-service | fraud-service, settlement-service |
| `fraud-events` | 3 | `paymentId` | fraud-service | financial-core |
| `ledger-events` | 3 | `paymentId` | financial-core | notification-service |
| `notification-events` | 3 | `paymentId` | notification-service | (future) |
| `payment-events-dlq` | 1 | `paymentId` | (system) | (manual replay) |

### 1.3 Database Schemas

#### payment-service (payment_db)
```sql
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(64) UNIQUE NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    payment_method VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payment_outbox (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES payments(id),
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE TABLE processed_events (
    event_id VARCHAR(128) PRIMARY KEY,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### financial-core (financial_core_db)
```sql
CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(64) UNIQUE NOT NULL,
    balance DECIMAL(19,4) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    version BIGINT NOT NULL DEFAULT 0,  -- optimistic locking
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    wallet_id UUID NOT NULL REFERENCES wallets(id),
    entry_type VARCHAR(20) NOT NULL,  -- DEBIT / CREDIT
    amount DECIMAL(19,4) NOT NULL,
    balance_before DECIMAL(19,4) NOT NULL,
    balance_after DECIMAL(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processed_events (
    event_id VARCHAR(128) PRIMARY KEY,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### fraud-service (fraud_db)
```sql
CREATE TABLE fraud_scores (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    score DECIMAL(5,2) NOT NULL,
    decision VARCHAR(20) NOT NULL,  -- APPROVED / REJECTED / REVIEW
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processed_events (
    event_id VARCHAR(128) PRIMARY KEY,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### notification-service (notification_db)
```sql
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    recipient_email VARCHAR(255),
    template VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processed_events (
    event_id VARCHAR(128) PRIMARY KEY,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 2. Pattern Implementations

### 2.1 Idempotency (payment-service)

```java
// IdempotencyFilter.java
// 1. Extract Idempotency-Key from header
// 2. SELECT * FROM payments WHERE idempotency_key = ?
// 3. If found → return cached response (200 + existing payment)
// 4. If not found → proceed

// Payments table has UNIQUE(idempotency_key) constraint
// Concurrent requests: one succeeds, one gets constraint violation → return cached
```

### 2.2 Transactional Outbox (payment-service)

```java
@Transactional
public PaymentResponse createPayment(CreatePaymentRequest req) {
    // 1. INSERT INTO payments (...)
    Payment payment = paymentRepo.save(req.toEntity());
    
    // 2. INSERT INTO payment_outbox (event_type='PaymentCreated', payload={...})
    OutboxEvent event = OutboxEvent.create("PaymentCreated", payment);
    outboxRepo.save(event);
    
    // Both committed atomically. If either fails, both roll back.
    return PaymentResponse.from(payment);
}

// OutboxPoller (scheduled, separate thread)
// SELECT * FROM payment_outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 100
// For each: publish to Kafka, UPDATE published_at = now()
// At-least-once: consumer must be idempotent
```

### 2.3 Consumer Idempotency (all services)

```java
// Every Kafka consumer:
public void consume(ConsumerRecord<String, PaymentEvent> record) {
    String eventId = record.key();  // event_id from outbox
    
    // 1. Check processed_events
    if (processedEventRepo.existsById(eventId)) {
        log.info("Duplicate event {} — skipping", eventId);
        return;  // Already processed
    }
    
    // 2. Process the event
    processEvent(record.value());
    
    // 3. Record as processed (in same transaction as business logic)
    processedEventRepo.save(new ProcessedEvent(eventId, consumerGroup));
}
```

### 2.4 Dead Letter Queue (all consumers)

```
Kafka consumer config:
  max.poll.interval.ms = 300000
  enable.auto.commit = false
  
Error handling:
  try { process(event) } catch (RetryableException e) {
    // Retry via consumer pause + seek
    consumer.seek(offset);
  } catch (NonRetryableException e) {
    // Dead letter
    dlqProducer.send("payment-events-dlq", eventId, event);
    consumer.commitSync();  // acknowledge to move past
  }
```

### 2.5 Distributed Tracing

```
Kafka Headers:
  traceparent: 00-{traceId}-{spanId}-01
  X-Request-Id: {requestId}

Producer: inject current trace context into Kafka headers
Consumer: extract trace context from headers, create child span
```

---

## 3. Event Schema

### PaymentCreated
```json
{
  "type": "PaymentCreated",
  "paymentId": "uuid",
  "amount": 99.99,
  "currency": "USD",
  "merchantId": "merchant-1",
  "customerId": "customer-1",
  "timestamp": "2026-06-03T12:00:00Z"
}
```

### FraudChecked
```json
{
  "type": "FraudChecked",
  "paymentId": "uuid",
  "score": 95.5,
  "decision": "APPROVED",
  "reason": "Low risk transaction",
  "timestamp": "2026-06-03T12:00:01Z"
}
```

### LedgerPosted
```json
{
  "type": "LedgerPosted",
  "paymentId": "uuid",
  "customerId": "customer-1",
  "amount": 99.99,
  "balanceBefore": 500.00,
  "balanceAfter": 400.01,
  "timestamp": "2026-06-03T12:00:02Z"
}
```

### ReceiptSent
```json
{
  "type": "ReceiptSent",
  "paymentId": "uuid",
  "recipientEmail": "customer@example.com",
  "amount": 99.99,
  "timestamp": "2026-06-03T12:00:03Z"
}
```

---

## 4. Implementation Tasks

### Task 1: Create payment-service (Java) — scaffold + schema
- `make scaffold-java NAME=payment-service`
- Flyway migrations: V1__payments.sql, V2__payment_outbox.sql, V3__processed_events.sql
- Add to docker-compose.yml (port 8081, payment_db)
- Add to CI matrix

### Task 2: Implement payment-service — API + Outbox + Idempotency
- `POST /v1/payments` controller
- `IdempotencyFilter` (checks idempotency_key)
- `PaymentService.createPayment()` — transactional outbox
- `OutboxPoller` — scheduled Kafka publishing
- Integration test with Testcontainers

### Task 3: Implement payment-service — Kafka producer
- `KafkaProducerConfig` (Spring Kafka)
- Publish `PaymentCreated` to `payment-events` topic
- Inject `traceparent` + `X-Request-Id` headers

### Task 4: Implement fraud-service — Kafka consumer + scoring
- `KafkaConsumer` — consume `payment-events`
- `FraudScorer` — simple rule-based (amount > $1000 → REVIEW, else APPROVED)
- `processed_events` dedup
- Publish `FraudChecked` to `fraud-events`
- DB migration: V1__fraud_scores.sql, V2__processed_events.sql

### Task 5: Implement financial-core — ledger + wallet
- `LedgerService` — INSERT ledger_entries
- `WalletService` — debit wallet (optimistic locking via version column)
- Kafka consumer for `fraud-events`
- `processed_events` dedup
- Publish `LedgerPosted` to `ledger-events`
- DB migration: V1__wallets.sql, V2__ledger_entries.sql, V3__processed_events.sql

### Task 6: Implement financial-core — idempotent wallet debit
- `SELECT ... FOR UPDATE` on wallet row
- Check version — retry on mismatch
- `UPDATE wallets SET balance = ?, version = version + 1 WHERE id = ? AND version = ?`

### Task 7: Implement notification-service — email sender
- Kafka consumer for `ledger-events`
- `EmailService` — nodemailer (or mock in test)
- `processed_events` dedup
- Publish `ReceiptSent` to `notification-events`
- DB migration: V1__notifications.sql, V2__processed_events.sql

### Task 8: Docker Compose — add payment-service
- Port 8081, payment_db database
- Standardized env vars
- Healthcheck: `/liveness`

### Task 9: Integration tests
- Payment-service: Testcontainers Postgres + embedded Kafka
- Fraud-service: pytest + test DB
- Financial-core: Spring Boot test + Testcontainers
- Notification-service: vitest + test DB

### Task 10: E2E verification
- `docker-compose up`
- `curl -X POST /v1/payments -H "Idempotency-Key: test-1"`
- Verify trace in Jaeger across all 4 services
- Verify duplicate idempotency key returns cached
- Stop a consumer → verify DLQ after retries

### Task 11: CI/CD updates
- Add payment-service to CI matrix
- Add Kafka topic creation script
- Update system-smoke-test to include payment flow

### Task 12: Documentation
- `docs/07-build-implementation.md`

---

## 5. File Change Inventory

### New Files (~30+)
```
services/java/payment-service/          # Full service directory
├── pom.xml
├── src/main/java/com/paymentapi/paymentservice/
│   ├── PaymentServiceApplication.java
│   ├── controller/PaymentController.java
│   ├── service/PaymentService.java
│   ├── service/OutboxPoller.java
│   ├── entity/Payment.java
│   ├── entity/OutboxEvent.java
│   ├── entity/ProcessedEvent.java
│   ├── repository/PaymentRepository.java
│   ├── repository/OutboxRepository.java
│   ├── repository/ProcessedEventRepository.java
│   ├── dto/CreatePaymentRequest.java
│   ├── dto/PaymentResponse.java
│   ├── filter/IdempotencyFilter.java
│   ├── config/KafkaConfig.java
│   └── consumer/FraudEventConsumer.java  (if consuming downstream)
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_payments.sql
│       ├── V2__create_payment_outbox.sql
│       └── V3__create_processed_events.sql
└── src/test/java/...

services/python/fraud-service/          # Updated
├── src/fraud_service/
│   ├── consumer.py                     # Kafka consumer
│   ├── scorer.py                       # Fraud scoring
│   └── models.py                       # FraudScore entity
├── alembic/versions/                   # DB migrations
└── tests/

services/java/financial-core/           # Updated
├── src/main/java/.../
│   ├── service/LedgerService.java
│   ├── service/WalletService.java
│   ├── entity/Wallet.java
│   ├── entity/LedgerEntry.java
│   └── consumer/FraudEventConsumer.java
├── src/main/resources/db/migration/
│   ├── V1__create_wallets.sql
│   ├── V2__create_ledger_entries.sql
│   └── V3__create_processed_events.sql
└── src/test/java/...

services/nodejs/notification-service/    # Updated
├── src/
│   ├── consumer.ts                      # Kafka consumer
│   ├── email-service.ts                 # Email sender
│   └── models.ts                        # Notification entity
├── migrations/                          # DB migrations
└── tests/
```

### Modified Files (~8)
```
docker-compose.yml                      # Add payment-service entry
.github/workflows/ci.yml               # Add payment-service to Java matrix
.github/workflows/cd.yml               # Add payment-service to CD matrix
services/java/financial-core/pom.xml   # Add Spring Kafka dependency
services/java/financial-core/src/...    # Business logic
services/python/fraud-service/          # Business logic
services/nodejs/notification-service/   # Business logic
docs/07-build-implementation.md         # Phase 7 doc
```

---

PLAN complete. Reply **APPROVE** to continue to TASKS, or provide feedback.
