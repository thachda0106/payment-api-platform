# Phase 10 — System Flows

## MoMo-like Payment API Platform

> **Document Status**: Draft v1.0
> **Last Updated**: 2026-05-20
> **Classification**: CONFIDENTIAL — Internal Use Only
> **Audience**: All Engineering Teams, Architecture Review Board, SRE, QA
> **Input**: Phase 08 — API Design (v3.0); Phase 09 — Event Schema & Governance (v1.0); Phase 06 — High-Level Architecture (v6.0)
> **Author Level**: Principal Architect
> **Approval Gate**: 🏗️ Architecture Review Board (ARB) Final Sign-off

---

## Table of Contents

1. [Goal & Scope](#1-goal--scope)
2. [Key Decisions](#2-key-decisions)
3. [Documents Produced](#3-documents-produced)
4. [Flow Diagrams](#4-flow-diagrams)
   - [F01 — Payment Request Flow](#f01--payment-request-flow)
   - [F02 — Authentication & Authorization Flow](#f02--authentication--authorization-flow)
   - [F03 — Idempotency Enforcement Flow](#f03--idempotency-enforcement-flow)
   - [F04 — Event-Driven & CDC Flow](#f04--event-driven--cdc-flow)
   - [F05 — Refund Saga Flow](#f05--refund-saga-flow)
   - [F06 — Payout Settlement Flow](#f06--payout-settlement-flow)
   - [F07 — Error Handling & Mapping Flow](#f07--error-handling--mapping-flow)
   - [F08 — Webhook Delivery & Retry Flow](#f08--webhook-delivery--retry-flow)
   - [F09 — Dead Letter Queue Recovery Flow](#f09--dead-letter-queue-recovery-flow)
   - [F10 — Search & Transaction History Flow](#f10--search--transaction-history-flow)
   - [F11 — Observability & Tracing Flow](#f11--observability--tracing-flow)
   - [F12 — Deployment & Release Flow](#f12--deployment--release-flow)
5. [Example Deliverables](#5-example-deliverables)
6. [Key Questions](#6-key-questions)
7. [Implementation Tasks](#7-implementation-tasks)
8. [Common Mistakes](#8-common-mistakes)
9. [KPIs & Exit Criteria](#9-kpis--exit-criteria)
10. [Connection to Next Phase](#10-connection-to-next-phase)

---

## 1. Goal & Scope

### 1.1 Goal

Map every critical path through the system as an end-to-end flow diagram. Each flow traces a request, event, or operational process across all architectural layers — from client to database and back — providing a single source of truth for how the system behaves under normal, failure, and recovery conditions.

### 1.2 Scope

- **12 end-to-end flows** covering request, auth, idempotency, event processing, saga, payout, error handling, webhook delivery, DLQ recovery, search, observability, and deployment
- Every flow references specific API endpoints (Phase 08), Kafka topics (Phase 09), DB tables and procedures (Phase 07), and security controls (Phase 05)
- Each flow includes: happy path, failure modes, latency budgets, and retry behavior

### 1.3 Input Alignment

| Upstream Phase | Flow Dependency |
|---------------|----------------|
| **Phase 08 — API Design** | API endpoints, auth scopes, error codes, idempotency keys, webhook events |
| **Phase 09 — Event Schema** | Kafka topics, event types, outbox → CDC pipeline, DLQ topics, consumer inbox pattern |
| **Phase 07 — Data Architecture** | `create_journal_entry` procedure, `wallet_balances` projection, `idempotency_keys`, `journal_lines` |
| **Phase 06 — High-Level Architecture** | Backpressure, circuit breakers, consistency models, admission control, retry matrix |
| **Phase 05 — Security Architecture** | JWT validation, API key auth, RBAC scopes, mTLS, rate limiting, PII handling |

---

## 2. Key Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D01 | **One flow per critical path** | Each flow is self-contained, showing a complete end-to-end trace. Cross-references connect related flows. |
| D02 | **Show both happy path and failure modes** | Every flow includes at least one failure branch. Production incidents are almost always failure-path scenarios. |
| D03 | **Latency budgets on every hop** | Each inter-service call has a P99 budget. Flows that exceed budgets trigger alerting (Phase 20). |
| D04 | **Anchored to upstream phase artifacts** | Every API call references a Phase 08 endpoint. Every event references a Phase 09 topic. No orphaned references. |
| D05 | **Mermaid sequence diagrams** | Text-based diagrams stored in version control. Renderable in any Markdown viewer. |

---

## 3. Documents Produced

| Document | Location | Status |
|----------|----------|--------|
| **System Flows Reference** | `docs/stages/B-domain-architecture/10-system-flows.md` (this document) | ✅ v1.0 |
| **System Overview** | `docs/cross-cutting/architecture/system-overview.md` | 🚧 Pending |
| **Architecture Diagrams** | `docs/cross-cutting/architecture/architecture-diagrams.md` | 🚧 Pending |
| **Service Catalog** | `docs/cross-cutting/architecture/service-catalog.md` | 🚧 Pending |

---

## 4. Flow Diagrams

### F01 — Payment Request Flow

**Scope**: Full payment lifecycle — API ingress to DB commit to response.

```mermaid
sequenceDiagram
    title Payment Request Flow (Happy Path — P99 < 250ms)
    
    actor Client
    participant WAF as WAF / CloudFront
    participant GW as API Gateway
    participant Auth as Auth Service
    participant PS as Payment Service
    participant DB as PostgreSQL (financial_core_db)
    participant Outbox as Outbox Table

    Client->>WAF: POST /v1/payments (+ Idempotency-Key)
    Note over WAF: < 5ms — TLS termination, DDoS filter

    WAF->>GW: Forward (decrypted)
    Note over GW: < 2ms — IP allowlist, rate limit check

    GW->>Auth: Validate JWT / API Key
    Note over Auth: < 10ms — RS256 verification, scope check (write:payments)
    Auth-->>GW: JWT claims + scopes

    alt Auth Failed
        GW-->>WAF: 401 Unauthorized
        WAF-->>Client: 401 { error: authentication_error }
    end

    GW->>PS: Forward (+ X-Auth-User, X-Auth-Scopes)
    Note over PS: < 5ms — Deserialize request, validate parameters

    PS->>DB: CALL create_journal_entry(p_idempotency_key, p_entry_id, p_lines)
    Note over DB: < 15ms — ACID transaction:
    Note over DB: 1. INSERT idempotency_keys (ON CONFLICT → 409)
    Note over DB: 2. INSERT journal_entries
    Note over DB: 3. FOR each line: SELECT FOR UPDATE, compute balance, INSERT
    Note over DB: 4. INSERT outbox (event: payment.created)
    Note over DB: 5. COMMIT

    alt Insufficient Funds
        DB-->>PS: RAISE EXCEPTION 'insufficient_funds'
        PS->>DB: INSERT outbox (event: payment.failed)
        PS-->>GW: 422 { error: insufficient_funds }
        GW-->>Client: 422
    else Double-Entry Imbalance
        DB-->>PS: RAISE EXCEPTION 'double_entry_imbalance'
        PS-->>GW: 500 { error: double_entry_imbalance }
        GW-->>Client: 500
    else Success
        DB-->>PS: journal_entry_id, ledger_lines
        PS-->>GW: 201 Created { payment_id, status: succeeded }
        GW-->>Client: 201
    end
```

**Failure Modes**:

| Failure Point | Detection | Response | Recovery |
|--------------|-----------|----------|----------|
| WAF DDoS threshold | Shield Advanced auto-mitigation | 503 to malicious IPs | Auto-recover after attack subsides |
| Rate limit exceeded | Token bucket exhausted | 429 + Retry-After header | Client backs off |
| Auth token expired | JWT `exp` claim | 401 + redirect to refresh | Client refreshes token |
| DB connection pool full | HikariCP `PoolTimeoutException` | 503 (fast fail) | Pool recovers when connections released |
| Deadlock on `SELECT FOR UPDATE` | PostgreSQL `40001` | Retry 3x with 50ms + jitter | Auto-retry succeeds |

---

### F02 — Authentication & Authorization Flow

**Scope**: JWT issuance, validation, API key auth, scope enforcement across all endpoints.

```mermaid
sequenceDiagram
    title Authentication & Authorization Flow
    
    actor Client
    participant GW as API Gateway
    participant Auth as Auth Service
    participant Redis as Redis (Token Cache)
    participant KMS as AWS KMS

    Note over Client,Auth: === JWT Authentication (End-User) ===
    
    Client->>Auth: POST /v1/auth/login { phone, otp }
    Auth->>Auth: Verify OTP (Argon2id hash)
    Auth->>KMS: Sign JWT (RS256)
    KMS-->>Auth: JWT signature
    Auth->>Redis: Cache session (TTL: 15 min)
    Auth-->>Client: { access_token, refresh_token, expires_in }

    Note over Client,GW: === Subsequent API Request ===
    
    Client->>GW: GET /v1/transactions (Authorization: Bearer {jwt})
    GW->>Redis: Validate JWT (cache hit)
    alt Cache Hit
        Redis-->>GW: Token valid, user=usr_001, scopes=[read:transactions]
    else Cache Miss
        GW->>Auth: Validate JWT signature
        Auth->>KMS: Verify RS256 signature
        KMS-->>Auth: Signature valid
        Auth->>Redis: Cache validation result
        Auth-->>GW: Token valid, user=usr_001
    end

    GW->>GW: Check scope: read:transactions ∈ [read:transactions]?
    alt Scope Insufficient
        GW-->>Client: 403 { error: scope_insufficient }
    else Scope Sufficient
        GW->>GW: Enrich headers: X-Auth-User, X-Auth-Scopes
        Note over GW: Forward to downstream service
    end

    Note over Client,GW: === API Key Authentication (Partner) ===
    
    Client->>GW: POST /v1/payments (Authorization: Bearer sk_live_abc123)
    GW->>Redis: Lookup API key hash
    Redis-->>GW: key_id=key_001, scopes=[write:payments], user=partner_001
    GW->>GW: Validate scope + rate limit (20 req/s per key)
```

**Latency Budget**:

| Step | P99 Target | Notes |
|------|-----------|-------|
| JWT signature verification (cached) | < 5ms | Redis cache hit |
| JWT signature verification (uncached) | < 15ms | KMS round-trip |
| API key lookup (Redis) | < 2ms | Always cached |
| Scope check (Gateway in-memory) | < 1ms | Map lookup |

---

### F03 — Idempotency Enforcement Flow

**Scope**: Two-layer idempotency (Redis cache + DB UNIQUE constraint) with replay and collision detection.

```mermaid
sequenceDiagram
    title Idempotency Enforcement (Two-Layer)
    
    actor Client
    participant GW as API Gateway
    participant Redis as Redis (Idempotency Cache)
    participant PS as Payment Service
    participant DB as PostgreSQL

    Client->>GW: POST /v1/payments (Idempotency-Key: cb174dc0-...)
    Note over GW: Layer 1: Gateway cache check

    GW->>Redis: GET idempotency:sk_live:cb174dc0-...
    alt Key Not Found (First Request)
        Redis-->>GW: NULL
        GW->>Redis: SET idempotency:sk_live:cb174dc0-... = STARTED (TTL 1h)
        GW->>PS: Forward (with Idempotency-Key)

        Note over PS,DB: Layer 2: DB constraint
        PS->>DB: CALL create_journal_entry(idempotency_key=cb174dc0-...)
        
        alt First Insert (Success)
            DB->>DB: INSERT idempotency_keys (ON CONFLICT DO NOTHING)
            DB->>DB: Process journal entry
            DB->>DB: UPDATE idempotency_keys SET status='COMPLETED'
            DB-->>PS: Success
            PS-->>GW: 201 { payment_id, ... }
            GW->>Redis: SET idempotency:sk_live:cb174dc0-... = {response} (TTL 24h)
            GW-->>Client: 201
        else Concurrent Request (Still Processing)
            DB->>DB: INSERT idempotency_keys → CONFLICT (status=STARTED)
            DB-->>PS: EXCEPTION
            PS-->>GW: 409 { error: idempotency_key_in_progress }
            GW-->>Client: 409
        else DB Unique Violation (Already Completed)
            DB->>DB: INSERT idempotency_keys → CONFLICT (status=COMPLETED)
            DB-->>PS: EXCEPTION
            PS->>DB: SELECT response FROM idempotency_keys WHERE key=...
            DB-->>PS: Cached response payload
            PS-->>GW: 200 (original response) + Idempotent-Replayed: true
            GW-->>Client: 200 + Idempotent-Replayed: true
        end
    else Key Found in Redis (Cache Hit)
        Redis-->>GW: Cached response
        alt Status = COMPLETED
            GW-->>Client: 200 (cached response) + Idempotent-Replayed: true
        else Status = STARTED
            GW-->>Client: 409 { error: idempotency_key_in_progress }
        end
    end

    Note over Client,GW: === Replay Attack Prevention ===
    Note over GW: GW validates: hash(Idempotency-Key + JWT.sub) matches cached binding
```

**Key Differences from Stripe**:

| Aspect | Our Implementation | Stripe |
|--------|-------------------|--------|
| Header | `Idempotency-Key` | `Idempotency-Key` ✓ |
| Scope | Per API key | Per API key ✓ |
| TTL | 24 hours | 24 hours ✓ |
| Replay binding | Bound to `JWT.sub` via hash | Bound to API key |

---

### F04 — Event-Driven & CDC Flow

**Scope**: Outbox → Debezium CDC → Kafka → Consumer Inbox → Side Effects. (Expanded from Phase 09 §4.1.)

```mermaid
sequenceDiagram
    title Event-Driven CDC Flow (Payment → Consumers)
    
    participant DB as PostgreSQL
    participant CDC as Debezium (CDC)
    participant Kafka
    participant WP as Wallet Projector
    participant RE as Risk Engine
    participant WS as Webhook Sender
    participant DLQ as DLQ Topic

    Note over DB: === Phase 1: Atomic Commit ===
    DB->>DB: BEGIN
    DB->>DB: INSERT journal_entries
    DB->>DB: INSERT journal_lines (DEBIT + CREDIT)
    DB->>DB: INSERT outbox (event_type: payment.created, payload: {...})
    DB->>DB: COMMIT
    Note over DB: Outbox row is atomically committed with journal data

    Note over CDC: === Phase 2: CDC Capture ===
    CDC->>DB: Tail WAL (pgoutput plugin)
    DB-->>CDC: outbox row: id=evt_001, event_topic=payments.payment.created
    CDC->>Kafka: Produce to payments.payment.created (key: acc_001_wallet)
    CDC->>DB: UPDATE outbox SET status='SENT', processed_at=NOW()
    Note over CDC: < 50ms CDC lag (P99)

    Note over Kafka,WP: === Phase 3: Parallel Consumption ===
    
    Kafka->>WP: Consume (partition 3, offset 1245)
    WP->>WP: INSERT INTO inbox ON CONFLICT (event_id) DO NOTHING
    alt New Event
        WP->>DB: UPDATE wallet_balances SET available_balance = ...
        WP->>WP: Emit: wallets.balance.updated to Kafka
        WP->>WP: UPDATE inbox SET status='COMPLETED'
        WP->>Kafka: Commit offset
    else Duplicate Event
        WP->>WP: Skip (already COMPLETED)
        WP->>Kafka: Commit offset
    end

    Kafka->>RE: Consume (partition 3, offset 1245)
    RE->>RE: Inbox dedup
    RE->>RE: Evaluate fraud rules (async, non-blocking)
    RE->>Kafka: Commit offset

    Kafka->>WS: Consume (partition 3, offset 1245)
    WS->>WS: Inbox dedup
    WS->>WS: POST webhook to partner URL
    alt Partner Responds
        WS->>WS: Log delivery, commit offset
    else Partner Timeout (10s)
        WS->>WS: Retry (exponential backoff)
        alt Exhausted Retries (5x)
            WS->>DLQ: Produce to payments.dlq
            WS->>Kafka: Commit offset (event preserved in DLQ)
        end
    end
```

**CDC Latency Budget**:

| Metric | P50 | P95 | P99 |
|--------|-----|-----|-----|
| Outbox INSERT to Kafka produce | < 10ms | < 30ms | < 50ms |
| Consumer lag (committed offset vs. latest) | < 100ms | < 500ms | < 2s |
| Alert threshold | — | — | > 10,000 messages |

---

### F05 — Refund Saga Flow

**Scope**: Refund as a distributed saga with compensating transaction on failure.

```mermaid
sequenceDiagram
    title Refund Saga (Distributed Transaction)
    
    actor Merchant
    participant API as API Gateway
    participant RS as Refund Service
    participant DB as PostgreSQL
    participant Kafka
    participant WP as Wallet Projector
    participant SS as Settlement Service
    participant NS as Notification Service

    Merchant->>API: POST /v1/payments/{id}/refunds
    API->>RS: Forward

    Note over RS: === Step 1: Validate ===
    RS->>DB: SELECT payment WHERE id = 'pay_xyz' AND status = 'succeeded'
    DB-->>RS: payment found, remaining_refundable = 15000

    alt Payment Not Refundable
        RS-->>API: 409 { error: payment_already_refunded }
    end

    Note over RS: === Step 2: Create Refund Entry ===
    RS->>DB: CALL create_journal_entry (reverse: CREDIT source, DEBIT destination)
    DB->>DB: INSERT outbox (event: refund.created)
    DB->>DB: COMMIT
    DB-->>RS: refund_id = ref_def456, status = processing

    CDC->>Kafka: Produce: refunds.refund.created

    Note over Kafka,WP: === Step 3: Update Wallet Balances ===
    Kafka->>WP: Consume: refund.created
    WP->>WP: Inbox dedup
    WP->>DB: UPDATE wallet_balances (reverse: add to source, subtract from destination)
    WP->>Kafka: Produce: wallets.balance.updated

    Note over Kafka,SS: === Step 4: Adjust Settlement ===
    Kafka->>SS: Consume: refund.created
    SS->>SS: Inbox dedup
    SS->>SS: Queue settlement adjustment (reverse merchant settlement)
    
    Note over DB: === Step 5: Double-Entry Validation ===
    DB->>DB: Trigger: verify_double_entry → OK
    DB->>DB: INSERT outbox (event: refund.succeeded)
    CDC->>Kafka: Produce: refunds.refund.succeeded

    Note over Kafka,NS: === Step 6: Notify ===
    Kafka->>NS: Consume: refund.succeeded
    NS->>NS: Inbox dedup
    NS->>NS: Send email receipt to customer
    NS->>NS: Send push notification to merchant

    Kafka->>Kafka: Webhook Sender: POST refund.succeeded to partner URL

    Note over RS: === Compensating Transaction (if Step 3 fails) ===
    Note over RS: If wallet projection fails after refund journal committed:
    Note over RS: 1. Reconciliation job detects drift
    Note over RS: 2. Operator triggers compensating journal entry
    Note over RS: 3. Refund status updated to 'failed' — manual review
```

**Saga State Transitions**:

```
refund.created → refund.succeeded (all steps complete)
refund.created → refund.failed (validation or processing failed)
refund.succeeded → [immutable — no further transitions]
```

---

### F06 — Payout Settlement Flow

**Scope**: Merchant payout from platform wallet to external bank account.

```mermaid
sequenceDiagram
    title Payout to External Bank (Settlement)
    
    actor Merchant
    participant API as API Gateway
    participant PoS as Payout Service
    participant DB as PostgreSQL
    participant Kafka
    participant SS as Settlement Service
    participant Bank as External Bank API
    participant NS as Notification Service

    Merchant->>API: POST /v1/payouts (amount, destination_bank)
    API->>PoS: Forward

    Note over PoS: === Step 1: Validate ===
    PoS->>DB: SELECT available_balance FROM wallet_balances WHERE account_id = ...
    DB-->>PoS: balance = 500000

    alt Insufficient Balance
        PoS-->>API: 422 { error: insufficient_funds }
    end

    Note over PoS: === Step 2: Debit Platform Wallet ===
    PoS->>DB: CALL create_journal_entry (DEBIT merchant wallet, CREDIT settlement_clearing)
    DB->>DB: INSERT outbox (event: payout.created)
    DB->>DB: COMMIT
    DB-->>PoS: payout_id = po_ghi789

    PoS-->>API: 201 { payout_id, status: pending }
    API-->>Merchant: 201

    CDC->>Kafka: Produce: payouts.payout.created

    Note over Kafka,SS: === Step 3: Initiate Bank Transfer ===
    Kafka->>SS: Consume: payout.created
    SS->>SS: Inbox dedup
    SS->>Bank: POST /transfers { amount, destination_bank, reference }
    
    alt Bank Accepts
        Bank-->>SS: { status: accepted, settlement_ref: BANK-TXN-001 }
        SS->>DB: INSERT outbox (event: payout.succeeded, settlement_ref)
        CDC->>Kafka: Produce: payouts.payout.succeeded
        Kafka->>NS: Send notification: "Payout of 100,000 VND sent to bank"
        Kafka->>Kafka: Webhook Sender: POST payout.succeeded to merchant URL
    else Bank Rejects
        Bank-->>SS: { status: rejected, reason: account_closed }
        SS->>DB: INSERT outbox (event: payout.failed, error_code: bank_rejected)
        CDC->>Kafka: Produce: payouts.payout.failed
        Kafka->>NS: Send notification: "Payout failed — bank account closed"
        Note over SS: Compensating: CREDIT merchant wallet (return funds)
    else Bank Timeout
        Bank-->>SS: Timeout (30s)
        SS->>SS: Retry 3x with backoff
        alt All Retries Exhausted
            SS->>SS: Mark payout as 'requires_manual_review'
            SS->>Kafka: Produce to payouts.dlq
        end
    end
```

---

### F07 — Error Handling & Mapping Flow

**Scope**: How errors propagate from DB exceptions to standardized API error responses.

```mermaid
sequenceDiagram
    title Error Propagation & Mapping
    
    participant DB as PostgreSQL
    participant Svc as Service Layer
    participant GW as API Gateway
    participant Client

    Note over DB: === Error Sources ===

    rect rgb(255, 230, 230)
        Note over DB: Source 1: DB Exception
        DB->>DB: RAISE EXCEPTION 'insufficient_funds on acc_001'
        DB-->>Svc: SQLSTATE 23514 (CHECK_VIOLATION)
        Svc->>Svc: Map: DB exception → API error code
        Note over Svc: ErrorMapper.map(SQLSTATE, message)
        Svc-->>GW: 422 { error: { code: insufficient_funds } }
    end

    rect rgb(230, 240, 255)
        Note over Svc: Source 2: Business Logic Validation
        Svc->>Svc: Validate: refund.amount <= payment.remaining_refundable
        Note over Svc: Validation fails
        Svc-->>GW: 409 { error: { code: payment_already_refunded } }
    end

    rect rgb(230, 255, 230)
        Note over Svc: Source 3: External Dependency Failure
        Svc->>Svc: Risk Engine call times out (500ms)
        Svc->>Svc: Fallback: allow payment with capped amount
        Note over Svc: Degraded mode (Phase 06 §7.2)
    end

    Note over GW: === Gateway Error Handling ===

    rect rgb(255, 245, 230)
        Note over GW: Source 4: Rate Limit
        GW->>GW: Token bucket exhausted for user
        GW-->>Client: 429 + X-RateLimit-* headers
    end

    rect rgb(240, 230, 255)
        Note over GW: Source 5: Service Unavailable
        GW->>Svc: Forward request
        Svc--xGW: Connection timeout (circuit breaker open)
        GW->>GW: Circuit breaker: HALF_OPEN → try alternate instance
        alt Alternate Instance Available
            Svc2-->>GW: 200 OK
        else All Instances Down
            GW-->>Client: 503 + Retry-After: 30
        end
    end

    Note over Client: === Client Receives ===
    Note over Client: Standard error envelope (Phase 08 §4.4.4):
    Note over Client: { error: { type, code, message, param?, doc_url? }, request_id }
```

**Error Mapping Table** (Cross-reference with Phase 08 §4.8.2):

| DB Exception | SQLSTATE | API Error Code | HTTP | Retryable? |
|-------------|----------|---------------|------|:--:|
| `insufficient_funds` | 23514 | `insufficient_funds` | 422 | No |
| `duplicate key` (idempotency) | 23505 | `idempotency_replayed` / `duplicate_transaction` | 409 | No |
| `foreign key violation` | 23503 | `account_not_found` | 404 | No |
| `deadlock detected` | 40001 | N/A (auto-retry) | — | Yes (3x) |
| `connection timeout` | 08006 | `service_unavailable` | 503 | Yes (exponential backoff) |
| `statement timeout` | 57014 | `internal_error` | 500 | Yes (1x) |
| Double-entry imbalance | P0001 (RAISE) | `double_entry_imbalance` | 500 | No (manual investigation) |

---

### F08 — Webhook Delivery & Retry Flow

**Scope**: Webhook delivery lifecycle — signature generation, delivery, retry, DLQ.

```mermaid
sequenceDiagram
    title Webhook Delivery with Retry & DLQ
    
    participant WS as Webhook Sender
    participant Kafka
    participant Partner as Partner URL
    participant DB as Webhook DB
    participant DLQ as payments.dlq

    Note over Kafka: === Trigger ===
    Kafka->>WS: Consume: payment.succeeded

    Note over WS: === Phase 1: Resolve Endpoint ===
    WS->>DB: SELECT endpoint_url, webhook_secret FROM webhook_endpoints WHERE user_id = ...
    DB-->>WS: url = https://partner.example.com/webhook, secret = whsec_abc123

    Note over WS: === Phase 2: Generate Signature ===
    WS->>WS: payload = serialize(event)
    WS->>WS: timestamp = now()
    WS->>WS: signature = HMAC-SHA256(secret, "{timestamp}.{payload}")
    WS->>WS: header = "t={timestamp},v1={signature}"

    Note over WS: === Phase 3: Deliver ===
    loop Retry (max 5, exponential backoff)
        WS->>Partner: POST /webhook (Webhook-Id, Webhook-Signature, Webhook-Event)
        Note over Partner: Partner verifies signature
        
        alt Success (200 OK)
            Partner-->>WS: 200 OK
            WS->>DB: INSERT webhook_deliveries (status=delivered, status_code=200)
            WS->>Kafka: Produce: notifications.webhook.delivered (status=success)
            Note over WS: Done
        else Client Error (4xx)
            Partner-->>WS: 400 / 401 / 404
            WS->>DB: INSERT webhook_deliveries (status=failed, status_code=4xx)
            Note over WS: Non-retryable. Send to DLQ immediately.
            WS->>DLQ: Produce wrapped event + error
            Note over WS: P2 alert fires
        else Server Error (5xx) / Timeout
            Partner--xWS: 503 / Timeout
            WS->>WS: Backoff: 0s, 30s, 2m, 5m, 15m
            Note over WS: Retry attempt: +1
        end
    end

    alt Exhausted All Retries
        WS->>DB: UPDATE webhook_deliveries SET status=permanently_failed
        WS->>DLQ: Produce wrapped event (retry_count=5)
        Note over WS: P2 alert fires
        Note over WS: On-call investigates DLQ
    end
```

**Retry Schedule** (Phase 08 §4.9.2):

| Attempt | Delay | Cumulative Time |
|---------|-------|----------------|
| 1 | 0s | 0s |
| 2 | 30s | 30s |
| 3 | 2m | 2m 30s |
| 4 | 5m | 7m 30s |
| 5 | 15m | 22m 30s |
| 6 | 1h | 1h 22m |
| 7 | 4h | 5h 22m |
| 8 | 8h | 13h 22m → DLQ |

---

### F09 — Dead Letter Queue Recovery Flow

**Scope**: Operational workflow for recovering events from DLQ topics.

```mermaid
sequenceDiagram
    title DLQ Recovery — Operational Workflow
    
    participant Alert as PagerDuty
    participant OnCall as On-Call Engineer
    participant DLQ as DLQ Topic
    participant Dashboard as DLQ Dashboard
    participant OS as OpenSearch
    participant Kafka

    Note over Alert: === Phase 1: Detection ===
    DLQ->>DLQ: Message count > 0 for > 5 minutes
    Alert->>OnCall: P2 Alert: payments.dlq has 15 messages

    Note over OnCall: === Phase 2: Triage ===
    OnCall->>Dashboard: Open DLQ dashboard
    Dashboard-->>OnCall: 15 messages on payments.dlq
    OnCall->>Dashboard: Filter by consumer_service = webhook-sender
    Dashboard-->>OnCall: 12 of 15 from webhook-sender

    OnCall->>OnCall: Inspect top error: "ConnectTimeoutException" (10 of 12)
    OnCall->>OS: Query trace_id: "00-4bf9..."
    OS-->>OnCall: Trace shows: Webhook Sender → partner URL timeout after 10s

    Note over OnCall: === Phase 3: Diagnosis ===
    OnCall->>OnCall: Check partner status page → PARTNER DOWN
    Note over OnCall: Root cause: partner webhook endpoint unreachable
    Note over OnCall: Action: Wait for partner recovery, then replay

    Note over OnCall: === Phase 4: Resolution ===
    
    alt Partner Recovered
        OnCall->>OnCall: Replay DLQ messages to payments.payment.succeeded
        
        loop For each DLQ message
            OnCall->>Kafka: Produce (original event_id, original headers, original payload)
            Kafka->>Kafka: Webhook Sender consumes
            
            alt Delivery Succeeds
                Kafka->>Kafka: Webhook delivered. Inbox records COMPLETED.
            else Still Fails
                Kafka->>DLQ: Event lands back in DLQ with updated retry_count
            end
        end
        
        OnCall->>Alert: Resolve P2 alert
    else Bug in Webhook Sender (Code Fix Needed)
        OnCall->>OnCall: Fix code, create PR, deploy
        OnCall->>OnCall: Reset consumer group offset to before first DLQ'd event
        OnCall->>Kafka: Restart consumer → reprocess events
        Note over OnCall: Inbox dedup prevents double-processing of already-succeeded events
    end
```

---

### F10 — Search & Transaction History Flow

**Scope**: How transaction history is queried — CQRS read path via OpenSearch with eventual consistency.

```mermaid
sequenceDiagram
    title Transaction History Query (CQRS Read Path)
    
    actor User
    participant API as API Gateway
    participant TS as Transaction Service
    participant OS as OpenSearch
    participant Kafka
    participant SI as Search Indexer

    Note over Kafka,SI: === Write Path (Asynchronous) ===
    Kafka->>SI: Consume: payment.succeeded / refund.succeeded / wallet.balance.updated
    SI->>SI: Inbox dedup
    SI->>SI: Transform event → search document
    SI->>OS: Index document: POST /transactions/_doc/{id}
    OS-->>SI: 201 Indexed
    Note over SI: Consistency: EVENTUAL (BASE). P99 indexing lag < 500ms.

    Note over User,TS: === Read Path (Synchronous) ===
    User->>API: GET /v1/transactions?account_id=acc_001&limit=20&cursor=...
    API->>TS: Forward (scope: read:transactions)

    TS->>OS: POST /transactions/_search { query: { bool: { filter: [ {term: {account_id: "acc_001"}}, ... ] } }, sort: [{created_at: "desc"}], search_after: [1713024000, "entry_4fA1"], size: 20 }

    alt Results Found
        OS-->>TS: { hits: { hits: [...], total: 1245 } }
        TS->>TS: Build paginated response: { data: [...], has_more: true, next_cursor: "enc(1713023500,entry_3eF4)" }
        TS-->>API: 200 { object: list, data: [...], has_more: true, next_cursor: ... }
        API-->>User: 200 OK
    else Empty Result
        OS-->>TS: { hits: { hits: [], total: 0 } }
        TS-->>API: 200 { object: list, data: [], has_more: false, next_cursor: null }
    else OpenSearch Timeout
        OS-->>TS: Timeout (500ms)
        TS->>TS: Fallback: query PostgreSQL ledger directly (SLOWER)
        DB-->>TS: Results
        Note over TS: Alert: OpenSearch query timeout — check cluster health
    end
```

**Consistency Guarantee**:

| Query Type | Data Source | Consistency | Max Staleness |
|-----------|------------|-------------|---------------|
| Transaction List | OpenSearch (indexed from events) | Eventual (BASE) | P99 < 500ms |
| Single Transaction Detail | PostgreSQL (ledger) | Strong (ACID) | 0ms |
| Wallet Balance | PostgreSQL (wallet_balances) | Strong (ACID) | 0ms |
| Payment Status | PostgreSQL (journal_entries) | Strong (ACID) | 0ms |

**Fallback Strategy**: If OpenSearch is unavailable, queries route to PostgreSQL directly. This is slower (P99 ~150ms vs P99 ~30ms for OpenSearch) but ensures availability. Alert fires if fallback is active for > 5 minutes.

---

### F11 — Observability & Tracing Flow

**Scope**: How trace context propagates through all layers, enabling end-to-end debugging.

```mermaid
sequenceDiagram
    title Distributed Tracing (W3C Trace Context)
    
    actor Client
    participant GW as API Gateway
    participant PS as Payment Service
    participant DB as PostgreSQL
    participant Kafka
    participant WP as Wallet Projector
    participant OS as OpenSearch / Jaeger

    Note over Client: === Trace Initiation ===
    Client->>GW: POST /v1/payments
    Note over Client: traceparent: not included (client-generated)

    GW->>GW: Generate trace: trace_id=4bf9..., span_id=a3c2...
    GW->>GW: Record span: gateway.ingress
    GW->>PS: Forward + traceparent: 00-4bf9...-a3c2...-01

    Note over PS: === Service Span ===
    PS->>PS: Extract traceparent
    PS->>PS: Create child span: payment.create (parent: a3c2...)
    PS->>PS: Record attributes: payment_id, amount, currency

    PS->>DB: CALL create_journal_entry(...) + traceparent (SQL comment)
    Note over DB: PostgreSQL logs trace_id for query
    DB-->>PS: Success

    PS->>PS: Record span: db.call (P99 < 15ms)
    PS->>PS: Create child span: outbox.write
    DB->>DB: INSERT outbox (+ traceparent in headers)

    PS->>PS: End span: payment.create (total: 35ms)
    PS-->>GW: 201 Created

    GW->>GW: Record span: gateway.egress
    GW-->>Client: 201 + traceparent (optional response header)

    Note over Kafka: === Async Trace Propagation ===
    DB->>DB: Outbox CDC emits event to Kafka
    Note over DB: Event header: traceparent = 00-4bf9...-a3c2...-01

    Kafka->>WP: Consume event
    WP->>WP: Extract traceparent from CloudEvents header
    WP->>WP: Create child span: wallet.project (parent: a3c2...)
    WP->>DB: UPDATE wallet_balances
    WP->>WP: End span: wallet.project (total: 8ms)

    Note over OS: === Trace Assembly ===
    WP->>OS: Export spans (OTLP/gRPC)
    PS->>OS: Export spans
    GW->>OS: Export spans

    Note over OS: Full trace: gateway → payment → db → outbox → kafka → wallet
    Note over OS: Queryable by: trace_id=4bf9... or payment_id=pay_xyz
```

**Tracing SLOs**:

| Metric | Target |
|--------|--------|
| Trace sampling rate (production) | 100% of error traces, 10% of success traces |
| Span export latency | < 100ms |
| Trace retention (Jaeger/OpenSearch) | 7 days hot, 30 days warm |

**Debugging a Failed Transaction** (per Phase 06 §10.1):
1. Extract `payment_id` from support ticket.
2. Query OpenSearch: `trace_id` linked to `payment_id`.
3. Follow spans: did it reach `db.call`? Did it reach `outbox.write`? Did it reach `kafka.produce`?
4. If trace stops at a specific span → root cause identified.

---

### F12 — Deployment & Release Flow

**Scope**: CI/CD pipeline from PR merge to production deployment with canary and rollback.

```mermaid
sequenceDiagram
    title Deployment Pipeline (Canary Release)
    
    actor Dev as Developer
    participant GH as GitHub
    participant CI as CI/CD (Jenkins/GHA)
    participant Reg as Container Registry
    participant CD as ArgoCD / Spinnaker
    participant K8s as Kubernetes (EKS)
    participant Monitor as Monitoring (Grafana)

    Note over Dev: === Phase 1: Build & Test ===
    Dev->>GH: Merge PR to main
    GH->>CI: Trigger pipeline

    CI->>CI: Lint (OpenAPI spec, Avro schema, code)
    CI->>CI: Unit tests (JUnit/Jest)
    CI->>CI: Contract tests (Pact)
    CI->>CI: SAST scan (SonarQube)
    
    alt Tests / Lint Fail
        CI-->>GH: PR checks FAILED
        Note over Dev: Fix issues, re-push
    end

    CI->>CI: Build Docker image
    CI->>CI: Scan image (Trivy)
    CI->>Reg: Push image: payment-service:v1.2.3-abc123

    Note over CD: === Phase 2: Deploy to Staging ===
    CD->>K8s: Apply staging manifests
    K8s->>K8s: Rolling update (staging namespace)
    CD->>CI: Run integration tests against staging
    
    alt Integration Tests Fail
        CD-->>GH: Staging deployment FAILED
        Note over Dev: Fix, re-merge
    end

    Note over CD: === Phase 3: Canary to Production ===
    CD->>K8s: Deploy canary (5% traffic, production namespace)
    K8s->>K8s: New pods: payment-service-v1.2.3 (1 instance)
    K8s->>K8s: Old pods: payment-service-v1.2.2 (19 instances)
    Note over K8s: Istio routes 5% → v1.2.3, 95% → v1.2.2

    Note over Monitor: === Phase 4: Canary Analysis (10 min) ===
    Monitor->>Monitor: Compare canary vs baseline:

    alt Canary Healthy (10 min)
        Note over Monitor: P99 latency: +2% (within 10% threshold)
        Note over Monitor: Error rate: 0.01% (same as baseline)
        Note over Monitor: No new error codes in logs
        
        CD->>K8s: Promote canary → 100% traffic
        K8s->>K8s: Scale v1.2.3 → 20 instances
        K8s->>K8s: Terminate v1.2.2 instances
        CD-->>GH: Deployment SUCCEEDED
    else Canary Degraded
        Note over Monitor: P99 latency: +65% (EXCEEDS threshold)
        Note over Monitor: OR Error rate: 0.5% (50x baseline)
        
        CD->>K8s: Rollback: shift 100% traffic back to v1.2.2
        K8s->>K8s: Terminate v1.2.3 instances
        CD-->>GH: Deployment ROLLED BACK
        Note over Dev: Investigate regression, fix, re-deploy
    end

    Note over Monitor: === Phase 5: Post-Deploy Monitoring ===
    Note over Monitor: 2-hour stabilization window
    Note over Monitor: If error budget consumed → CI/CD freezes all deploys (Phase 06 §10.2)
```

**Deployment Gates** (per Phase 25 — Production Readiness):

| Gate | Check | Blocking? |
|------|-------|:--:|
| Unit tests | 100% pass | ✅ |
| Contract tests | No breaking changes | ✅ |
| SAST | No HIGH/CRITICAL findings | ✅ |
| Image scan | No CRITICAL CVEs | ✅ |
| Staging integration | All scenarios pass | ✅ |
| Canary analysis | P99 < 10% regression, error rate ≤ baseline | ✅ |
| Post-deploy monitoring | No SLO violation in 2 hours | ✅ |
| Error budget | > 0 remaining | ✅ |

---

## 5. Example Deliverables

### 5.1 Trace Propagation: Full Lifecycle

```json
{
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spans": [
    {
      "span_id": "a3c2d1e4f5",
      "parent_id": null,
      "name": "gateway.ingress",
      "service": "api-gateway",
      "start_time": "2026-05-20T10:30:00.000Z",
      "duration_ms": 82,
      "attributes": {
        "http.method": "POST",
        "http.url": "/v1/payments",
        "http.status_code": 201,
        "idempotency_key": "cb174dc0-..."
      }
    },
    {
      "span_id": "b4d3e2f1a6",
      "parent_id": "a3c2d1e4f5",
      "name": "payment.create",
      "service": "payment-service",
      "start_time": "2026-05-20T10:30:00.005Z",
      "duration_ms": 65,
      "attributes": {
        "payment_id": "pay_xyz987654",
        "amount": 15000,
        "currency": "VND"
      }
    },
    {
      "span_id": "c5e4f3a2b7",
      "parent_id": "b4d3e2f1a6",
      "name": "db.call",
      "service": "payment-service",
      "start_time": "2026-05-20T10:30:00.010Z",
      "duration_ms": 12,
      "attributes": {
        "db.system": "postgresql",
        "db.operation": "create_journal_entry",
        "db.sql.table": "journal_entries"
      }
    },
    {
      "span_id": "d6f5a4b3c8",
      "parent_id": "b4d3e2f1a6",
      "name": "outbox.write",
      "service": "payment-service",
      "start_time": "2026-05-20T10:30:00.025Z",
      "duration_ms": 3
    },
    {
      "span_id": "e7a6b5c4d9",
      "parent_id": "a3c2d1e4f5",
      "name": "wallet.project",
      "service": "wallet-projector",
      "start_time": "2026-05-20T10:30:00.055Z",
      "duration_ms": 8,
      "attributes": {
        "messaging.system": "kafka",
        "messaging.destination": "payments.payment.succeeded",
        "messaging.kafka.partition": 3,
        "messaging.kafka.offset": 1245789
      }
    }
  ]
}
```

---

## 6. Key Questions

| # | Question | Answer |
|---|----------|--------|
| Q1 | What trace sampling rate should we use in production? | 100% of error traces (5xx, circuit breakers, DLQ routing), 10% of success traces. This gives complete visibility into failures while controlling observability cost. |
| Q2 | How long should the canary analysis window be? | 10 minutes minimum. This covers at least one full metrics scrape cycle and allows P99 latency to stabilize. Extend to 30 minutes for database migrations. |
| Q3 | What happens if OpenSearch is down for transaction history? | Fallback to PostgreSQL ledger table for queries. This is slower but available. Alert fires immediately (P1). |
| Q4 | How do we handle idempotency key collisions across different users? | Idempotency keys are scoped to API key. Even if two users generate the same UUID, they are in different namespaces. Gateway enforces this by binding `hash(key + JWT.sub)`. |
| Q5 | Can the payment flow work if Kafka is down? | Yes. The synchronous payment request completes via DB commit only. The outbox table buffers events. When Kafka recovers, Debezium CDC resumes from the last committed WAL position. No events are lost. |
| Q6 | What is the rollback time for a canary deployment? | < 30 seconds. Istio shifts 100% traffic back to the stable version. Pods terminate gracefully (drain in-flight requests for 30s). |

---

## 7. Implementation Tasks

### P0 — Critical Path

- [ ] **T01**: Implement trace context propagation (W3C Trace Context) in API Gateway, all services, and Kafka event headers.
- [ ] **T02**: Implement idempotency key binding (`hash(key + JWT.sub)`) in API Gateway.
- [ ] **T03**: Implement circuit breaker + fallback for external dependencies (Risk Engine, Bank API).
- [ ] **T04**: Implement canary deployment pipeline with automated analysis (Argo Rollouts / Spinnaker).
- [ ] **T05**: Implement error mapping layer in all services (DB Exception → API Error Code).
- [ ] **T06**: Implement OpenSearch fallback to PostgreSQL for transaction history queries.

### P1 — Before Vertical Slice (Phase 17)

- [ ] **T07**: Implement DLQ dashboard (Kafka UI / Confluent Control Center) for operational triage.
- [ ] **T08**: Implement consumer retry + DLQ routing in consumer framework.
- [ ] **T09**: Implement webhook delivery retry with exponential backoff.
- [ ] **T10**: Configure canary analysis metrics (P99 latency, error rate, log anomalies).

### P2 — Before Production Readiness (Phase 25)

- [ ] **T11**: Implement automated DLQ replay tooling (by key, by offset range).
- [ ] **T12**: Implement reconciliation job for wallet balance drift detection.
- [ ] **T13**: Load-test canary deployment process (ensure < 30s rollback).
- [ ] **T14**: Document runbooks for each failure scenario in this phase.

---

## 8. Common Mistakes

| Mistake | Consequence | Prevention |
|---------|-------------|-----------|
| **No trace context on async boundaries** | Trace breaks at Kafka → consumer boundary. Can't debug end-to-end. | All Kafka event headers include `traceparent`. Consumers extract and create child spans. |
| **Blocking retries inside DB transaction** | Retry loop holds DB connection open → pool exhaustion → cascading failure. | Retries happen at the consumer level, outside any DB transaction. |
| **Canary analysis too short** | Promote canary after 30 seconds → regression only visible after 2 minutes → already on 100% traffic. | Minimum 10-minute analysis window. |
| **No fallback for search** | OpenSearch is down → transaction history returns 503 → user cannot see any transactions. | Always fall back to PostgreSQL. Degraded performance is better than no service. |
| **Hardcoded retry delays** | Fixed `sleep(1000)` in retry loop → thundering herd when dependency recovers. | Exponential backoff + random jitter. |
| **Missing idempotency binding** | Attacker sniffs valid `Idempotency-Key`, replays with their session → gets cached success response. | Gateway binds `hash(key + JWT.sub)`. |

---

## 9. KPIs & Exit Criteria

| # | Criterion | Target | Measurement |
|---|-----------|--------|-------------|
| K01 | Trace coverage | 100% error traces, 10% success traces sampled | Jaeger / OpenSearch trace count |
| K02 | Trace context propagation | `traceparent` present on 100% of Kafka event headers | Avro schema validation |
| K03 | Idempotency binding | 100% of idempotency keys bound to `JWT.sub` at Gateway | Gateway audit log |
| K04 | Canary analysis coverage | All deployments use canary with ≥ 10 min analysis | CI/CD pipeline metrics |
| K05 | Rollback time | < 30 seconds for any canary rollback | Deployment event log |
| K06 | Search fallback | OpenSearch → PostgreSQL fallback tested and functional | Integration test |
| K07 | Error mapping coverage | 100% of DB exception types mapped to API error codes | Static analysis of error mapper |
| K08 | Flow documentation | All 12 flows documented with diagrams and failure modes | This document |

**Exit Gate**: All K01–K08 must be ✅ before ARB sign-off.

---

## 10. Connection to Next Phase

| Downstream Phase | How System Flows Connect |
|-----------------|------------------------|
| **Phase 11 — Technology Selection** | The flows define latency budgets, consistency requirements, and throughput targets that drive technology choices (Kafka vs. RabbitMQ, OpenSearch vs. Elasticsearch, Redis vs. Memcached). |
| **Phase 12 — Infrastructure Design** | Network topology, VPC subnets, security groups, and load balancer configurations must support the communication patterns shown in each flow. |
| **Phase 13 — Platform Core** | The `@app/core` library implements trace context propagation, error mapping, retry policies, and circuit breaker patterns defined in these flows. |
| **Phase 14 — Testing Strategy** | Integration tests, contract tests, and chaos tests are designed to exercise the exact failure modes documented in each flow. |
| **Phase 17 — Vertical Slice** | The first E2E working flow implements F01 (Payment Request Flow) + F04 (Event-Driven Flow) end-to-end in staging. |
| **Phase 20 — Observability** | The tracing, metrics, and alerting configurations are derived from the latency budgets and failure modes in each flow. |
| **Phase 23 — Chaos Engineering** | Game day scenarios are selected from the failure modes in each flow (e.g., "what happens when Kafka is partitioned?"). |
| **Phase 25 — Production Readiness** | The deployment canary analysis (F12) and error budget policy are validated as part of the launch gate. |

---

### 🛑 APPROVAL GATE → 🏗️ Architecture Review Board

**Checklist**:

- [ ] All 12 flow diagrams are complete with happy path and failure modes
- [ ] Each flow references specific Phase 08 endpoints and Phase 09 topics
- [ ] Latency budgets are defined for every inter-service hop
- [ ] Trace context propagation is defined across all boundaries (sync + async)
- [ ] Idempotency binding (`hash(key + sub)`) is documented in F03
- [ ] Canary deployment flow (F12) has defined analysis gates and rollback trigger
- [ ] All failure modes reference specific error codes from Phase 08 §4.8
- [ ] DLQ recovery workflow (F09) is documented as an operational runbook
