# SCRATCHPAD: Phase 7 — Architecture Validation (A+)

**Date**: 2026-06-03
**Status**: Draft — Awaiting Approval
**Phase**: Phase 7 of 9 (Minimum Build System Workflow)
**Approach**: A+ — Vertical Slice with Real Patterns

---

## 🎯 Goal

Prove the polyglot architecture actually works: `Java + Python + Node.js + Go → Kafka → Postgres → Redis → OpenTelemetry` by implementing ONE complete payment flow end-to-end with production-grade patterns.

---

## 🔨 Services in Vertical Slice (4 + 1 skeleton)

| Service | Language | Status | What to Build |
|---------|----------|--------|---------------|
| **payment-service** | Java | NEW (scaffold) | POST /payments api, Idempotency-Key, Outbox, Kafka producer |
| **fraud-service** | Python | EXISTING | Fraud scoring, Kafka consumer → producer |
| **financial-core** | Java | EXISTING | Ledger entries, wallet debit/credit, Kafka consumer |
| **notification-service** | Node.js | EXISTING | Email receipt via nodemailer, Kafka consumer |
| **settlement-service** | Go | EXISTING | Keep as skeleton (NOT in vertical slice) |

## 📨 Event Flow

```
POST /v1/payments (Idempotency-Key: uuid)
   │
   ▼
payment-service  ──[PaymentCreated]──▶  Kafka
   │                                      │
   ▼                                      ▼
payment_outbox                        fraud-service
 (transactional)                          │
                                          ▼
                                   [FraudChecked]
                                          │
                                          ▼
                                   financial-core
                                          │
                                          ▼
                                   [LedgerPosted]
                                          │
                                          ▼
                                   notification-service
                                          │
                                          ▼
                                   [ReceiptSent]
                                          │
                                          ▼
                                   (end of flow)

Failed events → payment-events-dlq
```

## 🔑 Critical Patterns (real implementation, not stub)

| Pattern | Where | Implementation |
|---------|-------|----------------|
| **Idempotency** | payment-service | `Idempotency-Key` header → check `payments.idempotency_key` unique → return cached response if duplicate |
| **Transactional Outbox** | payment-service | `INSERT INTO payments + INSERT INTO payment_outbox` in same transaction |
| **Consumer Idempotency** | fraud, ledger, notification | `processed_events` table — dedup by `event_id` before processing |
| **DLQ** | All consumers | `payment-events-dlq` topic — poison messages after N retries |
| **Distributed Tracing** | ALL services | `traceId` propagated via Kafka headers, visible in Jaeger across all 4 services |
| **DB Migrations** | payment-service, financial-core | Flyway migrations for `payments`, `payment_outbox`, `ledger_entries`, `wallets`, `processed_events` |
| **Docker Compose** | All 4 services | Updated with new services + real env vars |
| **CI Matrix** | CI updated | New payment-service added to Java matrix |

## ❌ NOT IN SCOPE

- ❌ 11 other services (refund, fx, treasury, transaction, fee-engine, reconciliation, compliance, dispute, merchant, identity, bank-integration, audit)
- ❌ Full PSP features (refunds, chargebacks, FX conversion, treasury management)
- ❌ DB schemas for non-slice services
- ❌ Settlement batch processing
- ❌ Complex fraud ML models (rule-based only)

## 📤 Wave Plan (Post Vertical Slice)

### After Phase 7 proves architecture works:

```
Wave 1: payment-service, financial-core, transaction-service
Wave 2: refund-service, fee-engine
Wave 3: treasury-service, fx-service
Wave 4: reconciliation-service, audit-service
Wave 5: merchant, identity, compliance, bank-integration, dispute
```

## 📊 File Changes

| Category | Files | Details |
|----------|-------|---------|
| Scaffold | 1 service dir | `services/java/payment-service/` |
| Business logic | 4 services | payment (Java), fraud (Python), ledger (Java), notification (Node.js) |
| DB Migrations | 4 services | Flyway: payments, payment_outbox, ledger_entries, wallets, processed_events |
| Kafka config | 4 services | Producers + consumers + DLQ topic |
| Docker compose | 1 file | Add payment-service entry |
| CI matrix | 1 file | Add payment-service to Java job |
| Tests | 4 services | Integration tests for each with Testcontainers/embedded Kafka |
| Docs | 1 file | `docs/07-build-implementation.md` |

## ✅ Success Criteria

| # | Criterion |
|---|-----------|
| C1 | `POST /v1/payments` with `Idempotency-Key` creates payment + outbox entry atomically |
| C2 | Duplicate `Idempotency-Key` returns cached 200 (no duplicate payment) |
| C3 | PaymentCreated event published to Kafka from outbox |
| C4 | fraud-service consumes PaymentCreated, scores, produces FraudChecked |
| C5 | financial-core consumes FraudChecked, posts ledger, produces LedgerPosted |
| C6 | notification-service consumes LedgerPosted, sends email, produces ReceiptSent |
| C7 | All 4 services show correlated traces in Jaeger (same traceId across services) |
| C8 | Poison message retried N times then moved to payment-events-dlq |
| C9 | `docker-compose up` starts all 4 + infra, flow runs end-to-end |
| C10 | All integration tests pass in CI |

---

Phase 1 (SCRATCHPAD) complete — APPROVED for PLAN. Reply **APPROVE** or provide feedback.
