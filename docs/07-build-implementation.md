# Phase 07 — Build: Architecture Validation (Vertical Slice)

## 🎯 Goal

Prove the polyglot architecture works end-to-end: `Java + Python + Node.js + Go → Kafka → Postgres → OpenTelemetry` by implementing ONE complete payment flow with production-grade patterns.

## 📥 Input

- Phase 5: Platform libs (telemetry, health, config for all 4 languages)
- Phase 6: CI/CD pipeline with arch tests, system smoke tests
- Phase 4: System flows design, Kafka topic topology

## ⚙️ What Was Built

### Services in Vertical Slice

| Service | Language | Port | DB | Responsibility |
|---------|----------|------|-----|----------------|
| **payment-service** | Java 21 | 8081 | payment_db | POST /v1/payments → Outbox → Kafka |
| **fraud-service** | Python 3.12 | 8000 | fraud_db | Consume PaymentCreated → Score → Publish |
| **financial-core** | Java 21 | 8080 | financial_core_db | Consume PaymentApproved → Double-entry ledger → Publish |
| **notification-service** | Node.js 22 | 3001 | notification_db | Consume LedgerEntryCreated → Email receipt → Publish |
| settlement-service | Go 1.22 | 8088 | settlement_db | Skeleton only (not in vertical slice) |

### Event Flow

```
POST /v1/payments (Idempotency-Key: uuid)
         │
    ┌────▼─────────────────────────────────┐
    │ payment-service                       │
    │  - Idempotency check (UNIQUE key)    │
    │  - INSERT payments                    │
    │  - INSERT payment_outbox (same tx)    │  ← Transactional Outbox
    └────┬─────────────────────────────────┘
         │
    [PaymentCreated] ── payment-events topic
         │
    ┌────▼─────────────────────────────────┐
    │ fraud-service (Python)               │
    │  - Atomic idempotency (ON CONFLICT)  │
    │  - Multi-rule scorer                 │
    │  - Publish PaymentApproved/Rejected   │
    └────┬─────────────────────────────────┘
         │
    [PaymentApproved] ── fraud-events topic
         │
    ┌────▼─────────────────────────────────┐
    │ financial-core (Java)                │
    │  - Accounts + Journal Entries        │
    │  - Customer Wallet: -$100            │
    │  - Merchant Payable: +$97            │
    │  - Platform Fee: +$3                  │
    │  - Double-entry sum = 0              │
    │  - Publish LedgerEntryCreated         │
    └────┬─────────────────────────────────┘
         │
    [LedgerEntryCreated] ── ledger-events topic
         │
    ┌────▼─────────────────────────────────┐
    │ notification-service (Node.js)       │
    │  - Send email receipt                │
    │  - Publish NotificationSent           │
    └──────────────────────────────────────┘

Failed events → payment-events-dlq (auto-created)
```

### Key Patterns Implemented

| Pattern | Where | Implementation |
|---------|-------|----------------|
| **Transactional Outbox** | payment-service | `payments` + `payment_outbox` in same `@Transactional` |
| **SKIP LOCKED** | payment-service | `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100` in OutboxPoller |
| **Idempotency (API)** | payment-service | `payments.idempotency_key UNIQUE` → duplicate returns cached 200 |
| **Idempotency (Consumer)** | fraud, ledger, notification | `processed_events PK(event_id, consumer_group)` → `INSERT ... ON CONFLICT DO NOTHING` |
| **eventId ≠ paymentId** | All services | `eventId` = unique per event (dedup). `paymentId` = Kafka key (ordering) |
| **Double-Entry Ledger** | financial-core | 3 journal entries per payment, `SUM(credit) - SUM(debit) = 0` |
| **balance = Projection** | financial-core | `accounts.balance` is cached; `journal_entries` is source of truth |
| **Distributed Tracing** | All 4 services | `traceId` propagated via Kafka headers → visible in Jaeger |
| **Dead Letter Queue** | All consumers | `payment-events-dlq` topic — poison messages after N retries |
| **Contract Compatibility** | Tests | Producer v2 with new fields → Consumer v1 parses (unknown fields ignored) |

### Database Schemas

**payment-service (payment_db):**
- `payments` — idempotency_key UNIQUE, amount, currency, merchant, customer
- `payment_outbox` — event_id UNIQUE, aggregate_id (paymentId), SKIP LOCKED index
- `processed_events` — PK(event_id, consumer_group) for consumer dedup

**financial-core (financial_core_db):**
- `accounts` — external_ref, account_type (CUSTOMER_WALLET/MERCHANT_PAYABLE/PLATFORM_FEE_REVENUE), balance, version
- `journal_entries` — ledger_transaction_id, payment_id, entry_type (DEBIT/CREDIT), balance_before/after
- `processed_events`

**fraud-service (fraud_db):**
- `fraud_scores` — payment_id, score, decision, reason
- `processed_events`

**notification-service (notification_db):**
- `notifications` — payment_id, recipient_email, template, status
- `processed_events`

## 📤 Output (Artifacts)

### New Files (~25)
```
services/java/payment-service/                    # Full service
├── pom.xml, application.yml
├── entity/Payment.java, OutboxEvent.java
├── repository/PaymentRepository.java, OutboxRepository.java
├── service/PaymentService.java, OutboxPoller.java
├── controller/PaymentController.java
├── dto/CreatePaymentRequest.java, PaymentResponse.java
└── db/migration/V1__payments.sql, V2__outbox.sql, V3__processed_events.sql

services/java/financial-core/                     # Updated
├── entity/Account.java, JournalEntry.java
├── repository/AccountRepository.java, JournalEntryRepository.java, ProcessedEventRepository.java
├── service/LedgerService.java
├── consumer/FraudEventConsumer.java
└── db/migration/V1__accounts.sql, V2__journal_entries.sql, V3__processed_events.sql

services/python/fraud-service/                    # Updated
├── scorer.py, consumer.py
└── (models pending ORM setup)

services/nodejs/notification-service/             # Updated
├── consumer.ts
└── migrations/V1__notifications.sql, V2__processed_events.sql

scripts/verify-vertical-slice.sh                  # E2E verification
```

### Modified Files (~5)
```
docker-compose.yml           # Added payment-service entry (port 8081)
.github/workflows/ci.yml     # Added payment-service to Java matrix + smoke test
.github/workflows/cd.yml     # Added payment-service to CD matrix + scan
```

## ✅ Done Criteria

| # | Criterion | Status |
|---|-----------|--------|
| C1 | POST /v1/payments creates payment + outbox atomically | ✅ `@Transactional` |
| C2 | Duplicate Idempotency-Key returns cached 200 | ✅ UNIQUE constraint + Service-layer check |
| C3 | Outbox published to Kafka with SKIP LOCKED | ✅ `FOR UPDATE SKIP LOCKED LIMIT 100` |
| C4 | fraud-service scores with multi-rule engine | ✅ Amount + velocity + merchant blacklist |
| C5 | financial-core posts double-entry (sum = 0) | ✅ 3 journal entries with balance invariant |
| C6 | notification-service sends email receipt | ✅ Consumer + nodemailer |
| C7 | traceId propagated across all 4 services | ✅ Kafka headers + OTel SDK |
| C8 | Poison events go to DLQ | ✅ payment-events-dlq topic |
| C9 | eventId ≠ paymentId (separate dedup + ordering) | ✅ OutboxEvent.eventId vs aggregateId |
| C10 | Contract compatibility: v2 producer → v1 consumer | ✅ Test verifies unknown fields ignored |

## 🧠 Lessons Learned

1. **eventId vs paymentId separation is critical**. One payment produces multiple events. Using paymentId as dedup key would silently drop valid events (Captured, Refunded after Created).

2. **Idempotency in Service layer beats Servlet Filter**. Filters can't easily cache response bodies. The service layer has full context and transactional guarantees.

3. **balance is a projection, not source of truth**. Always rebuildable from `SUM(journal_entries)`. This mindset prevents ledger corruption.

4. **SKIP LOCKED from day 1**. Adding it later means changing the query, re-testing, and possibly discovering concurrency bugs that were hidden.

## ⚠️ Known Limitations

1. **Fraud velocity tracker is in-memory** — lost on restart. Phase 8: Redis-backed.
2. **OutboxPoller uses synchronous `.get()`** — blocks on Kafka ack. Phase 8: async `whenComplete()`.
3. **No retry topics implemented** — documented in topic catalog but not running. Phase 8.
4. **Settlement-service excluded** — skeleton only. Wave 4 implementation.

## Connection to Next Phase (Phase 8 — Observability & Hardening)

Phase 8 will:
1. Add Prometheus dashboards for payment throughput, fraud rejection rate, ledger balance
2. Add alerting rules (DLQ depth > 0, consumer lag > 100)
3. Convert OutboxPoller to async batch publishing
4. Add Redis-backed velocity tracker for fraud
5. Implement retry topics with backoff
6. Production readiness checklist
