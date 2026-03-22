# Phase 02 — Requirements & SLOs

## MoMo-like Payment API Platform

> **Document Status**: Draft v1.0 — Pending Review  
> **Last Updated**: 2026-03-22  
> **Audience**: Engineering Leadership, Product, Architecture Review Board  
> **Input**: Phase 01 — Product Discovery (v4.0, Approved)

---

## 1. User Stories

### 1.1 Core Money Movement

#### US-001: User Registration & Wallet Creation

```
AS A new user
I WANT TO register with my phone number and create a wallet
SO THAT I can start making payments and transfers
```

**Acceptance Criteria:**
- [ ] User registers via phone number + OTP (SMS)
- [ ] OTP expires after 5 minutes, max 3 attempts per session, max 5 OTPs per phone per hour
- [ ] Basic KYC: name, date of birth, national ID number
- [ ] Wallet created automatically upon successful registration (VND, balance = 0)
- [ ] Transaction PIN set during registration (6 digits, hashed with Argon2id)
- [ ] Account status: `ACTIVE` with KYC tier = `NON_KYC` (Tier 0)
- [ ] Duplicate phone number → reject with clear error
- [ ] All registration events logged to audit trail

**NFR:** Latency < 3s (OTP delivery < 5s), Availability 99.95%

---

#### US-002: Wallet Top-Up (Bank → Wallet)

```
AS A registered user
I WANT TO add money to my wallet from my linked bank account
SO THAT I have funds to make payments
```

**Acceptance Criteria:**
- [ ] User selects linked bank account and enters amount
- [ ] Amount validation: min 10,000 VND, max per KYC tier limit
- [ ] PIN authentication required
- [ ] Fraud check: risk scoring on top-up pattern (velocity, amount, device)
- [ ] Ledger: DEBIT bank_pooled_account, CREDIT user_wallet:{uid}
- [ ] Wallet balance updated upon bank confirmation callback
- [ ] Pending state visible to user while awaiting bank confirmation
- [ ] Push notification + in-app notification on success/failure
- [ ] Idempotency: same request with same idempotency_key returns same result
- [ ] Bank timeout (> 30s) → mark as PENDING, reconcile via bank statement

**NFR:** API response < 500ms (async), Bank settlement 5s–24h, Availability 99.95%

---

#### US-003: P2P Transfer (Phone-to-Phone)

```
AS A registered user
I WANT TO transfer money to another user by phone number
SO THAT I can send money instantly to friends and family
```

**Acceptance Criteria:**
- [ ] Enter recipient phone number → validate recipient exists and is active
- [ ] Enter amount + optional note (max 200 chars)
- [ ] Amount validation: min 1,000 VND, max per KYC tier limit, daily limit check
- [ ] PIN authentication required
- [ ] Fraud check: velocity, amount, device fingerprint, geo-anomaly
- [ ] Atomic operation: DEBIT sender_wallet + CREDIT receiver_wallet in single transaction
- [ ] Ledger journal entry recorded in same transaction (co-located DB)
- [ ] Both parties receive push + in-app notification
- [ ] Transaction receipt generated with unique reference number
- [ ] Self-transfer (sender = receiver) → reject
- [ ] Frozen account (sender or receiver) → reject with appropriate message

**NFR:** < 200ms p50, < 500ms p99, Availability 99.99% (critical path)

---

#### US-004: QR Code Payment (at Store)

```
AS A registered user
I WANT TO scan a merchant's QR code to pay
SO THAT I can pay quickly at physical stores
```

**Acceptance Criteria:**
- [ ] Scan QR code → decode merchant_id + optional amount + optional reference
- [ ] If amount not in QR → user enters amount manually
- [ ] Display: merchant name, amount, fee (if any)
- [ ] PIN authentication required
- [ ] Fraud check on user + merchant
- [ ] Ledger: DEBIT user_wallet, CREDIT merchant_pending, CREDIT platform_fee
- [ ] Merchant receives real-time notification of payment
- [ ] User receives receipt with merchant name, amount, reference, timestamp
- [ ] Invalid/expired QR code → clear error message
- [ ] Merchant account inactive → reject with message

**NFR:** Scan-to-confirmation < 3s (p95), Availability 99.95%

---

#### US-005: Bill Payment

```
AS A registered user
I WANT TO pay utility bills (electricity, water, internet, telecom)
SO THAT I can manage all payments from one app
```

**Acceptance Criteria:**
- [ ] Select bill category → enter bill code/account number
- [ ] Fetch bill details from provider API (amount, due date, subscriber info)
- [ ] Display bill details for confirmation
- [ ] PIN authentication required
- [ ] Escrow flow: DEBIT user_wallet, CREDIT escrow:{bill_txn_id}
- [ ] Forward to provider → on confirmation: DEBIT escrow, CREDIT bank_pooled_account
- [ ] On provider rejection: DEBIT escrow, CREDIT user_wallet (refund)
- [ ] Receipt with bill reference number
- [ ] Provider API timeout → retry 3x, then mark for manual resolution

**NFR:** Bill query < 3s, Payment + confirmation < 5s, Availability 99.9%

---

#### US-006: Transaction History & Receipts

```
AS A registered user
I WANT TO view my transaction history and download receipts
SO THAT I can track my spending and have proof of payment
```

**Acceptance Criteria:**
- [ ] List all transactions with: type, amount, counterparty, status, timestamp
- [ ] Filter by: date range, type (top-up, transfer, payment, withdrawal), status
- [ ] Pagination: 20 items per page, cursor-based
- [ ] Transaction detail view: full information + receipt download (PDF)
- [ ] Real-time: new transaction appears in history within 2 seconds
- [ ] Search by: reference number, counterparty name/phone, amount range

**NFR:** List API < 100ms (p50), < 300ms (p99), Availability 99.9%

---

#### US-007: Wallet Withdrawal (Wallet → Bank)

```
AS A registered user
I WANT TO withdraw money from my wallet to my bank account
SO THAT I can access my funds in my bank
```

**Acceptance Criteria:**
- [ ] Select linked bank account, enter amount
- [ ] Amount validation: min 50,000 VND, max per daily withdrawal limit, balance check
- [ ] PIN authentication + fraud check
- [ ] Phase 1: Hold funds (wallet debit, bank_payout_pending credit)
- [ ] Phase 2: Bank Integration Service initiates transfer
- [ ] Phase 3: On bank confirmation → finalize (bank_payout_pending cleared)
- [ ] On bank rejection → compensating entry (restore wallet balance)
- [ ] Withdrawal fee applied (flat or tier-based, configurable)
- [ ] Notification on success/failure
- [ ] Timeout > 30 min → escalate to manual review

**NFR:** API response < 500ms, Bank transfer 1 min–24 hours, Availability 99.95%

---

#### US-008: Refund / Reversal

```
AS A merchant (or system)
I WANT TO issue a full or partial refund for a payment
SO THAT customers can be refunded when needed
```

**Acceptance Criteria:**
- [ ] Merchant initiates via API (POST /refunds) or dashboard
- [ ] Validate: original payment exists, status = SUCCESS, within 90-day refund window
- [ ] Partial refund: amount ≤ remaining refundable amount
- [ ] Idempotency: refund_id = payment_id + refund_sequence
- [ ] Ledger: DEBIT merchant_pending, CREDIT user_wallet
- [ ] Fee handling: configurable (platform absorbs or merchant absorbs)
- [ ] Settlement adjustment for next merchant settlement cycle
- [ ] Both parties notified
- [ ] Original transaction status → REFUNDED (or PARTIALLY_REFUNDED)

**NFR:** Processing < 500ms, Instant wallet credit, Availability 99.95%

---

### 1.2 Merchant Platform

#### US-009: Merchant Onboarding

```
AS A merchant
I WANT TO register my business and get API access
SO THAT I can accept payments from customers
```

**Acceptance Criteria:**
- [ ] Submit: business name, registration number, category, director info, bank account
- [ ] KYB verification: document upload, automated + manual review
- [ ] Sandbox credentials generated immediately upon registration
- [ ] Sandbox access: full API functionality with test data
- [ ] Production access: after compliance approval (1–3 business days)
- [ ] Settlement account linked and verified
- [ ] Webhook URL configured and tested (ping test)
- [ ] API documentation + SDK access provided

**NFR:** Sandbox access < 10 min, Production review SLA ≤ 3 business days

---

#### US-010: Merchant Payment API (Online Checkout)

```
AS A developer integrating our payment API
I WANT TO redirect customers to a payment page and receive webhooks
SO THAT I can accept payments on my e-commerce site
```

**Acceptance Criteria:**
- [ ] POST /payments → create payment session with redirect URL
- [ ] Customer redirected to payment page → login → authorize → redirect back
- [ ] Webhook POST to merchant's configured URL with payment result
- [ ] Webhook retry: exponential backoff (5s, 30s, 5min, 30min, 2h), max 5 retries
- [ ] Webhook signature verification (HMAC-SHA256)
- [ ] Idempotency key required on payment creation
- [ ] Payment page session timeout: 15 minutes
- [ ] Double-submission protection (idempotency key check)

**NFR:** Payment processing < 2s, Webhook delivery < 5s (first attempt), 99.9% webhook delivery rate

---

### 1.3 Compliance & Risk

#### US-011: PIN Authentication

```
AS A registered user
I WANT TO authenticate transaction with my PIN
SO THAT my money is protected from unauthorized use
```

**Acceptance Criteria:**
- [ ] 6-digit PIN required for all financial operations
- [ ] PIN hashed with Argon2id, never stored in plaintext
- [ ] Max 5 consecutive wrong attempts → temp lock (30 min)
- [ ] Max 10 consecutive wrong attempts → permanent lock (require identity verification)
- [ ] PIN change: requires current PIN + OTP verification
- [ ] PIN reset: OTP + identity verification (KYC re-check)
- [ ] Failed PIN attempts logged to audit trail with device info

**NFR:** PIN verification < 50ms, Availability 99.99%

---

#### US-012: KYC Verification

```
AS A registered user
I WANT TO upgrade my KYC tier by submitting identity documents
SO THAT I can increase my transaction limits
```

**Acceptance Criteria:**
- [ ] Tier 0 → Tier 1: ID photo + selfie liveness check
- [ ] Tier 1 → Tier 2: Proof of address + enhanced verification
- [ ] eKYC provider integration: OCR, liveness detection, ID verification
- [ ] Manual review fallback for failed automated verification
- [ ] KYC status: PENDING → APPROVED / REJECTED
- [ ] New limits applied immediately upon tier upgrade
- [ ] Documents stored encrypted in object storage (S3)
- [ ] Document retention: 5 years minimum (regulatory requirement)

**NFR:** Automated verification < 30s, Manual review SLA ≤ 24 hours

---

#### US-013: Fraud Detection & Rate Limiting

```
AS the platform
I WANT TO detect and block fraudulent transactions in real-time
SO THAT users' funds are protected
```

**Acceptance Criteria:**
- [ ] Real-time risk scoring on every transaction (< 50ms)
- [ ] Rules engine: velocity checks, amount thresholds, device fingerprint, geo-anomaly
- [ ] Decisions: ALLOW / REVIEW / BLOCK
- [ ] BLOCK → reject transaction + alert user + log event
- [ ] REVIEW → hold transaction + queue for manual review
- [ ] API rate limiting: per-user, per-merchant, per-IP
- [ ] Account freeze on confirmed fraud (automatic or manual trigger)

**NFR:** Risk check < 50ms p99, False positive rate < 5%, Availability 99.99%

---

### 1.4 Operations & Admin

#### US-014: Admin Dashboard

```
AS a platform admin
I WANT TO manage users, merchants, and transactions from a dashboard
SO THAT I can operate the platform efficiently
```

**Acceptance Criteria:**
- [ ] User management: search, view profile, freeze/unfreeze, view KYC status
- [ ] Merchant management: view status, approve/reject, update settings
- [ ] Transaction search: by ID, user, merchant, date, amount, status
- [ ] Dashboard: real-time transaction volume, success rate, active users
- [ ] RBAC: admin roles with permission matrix (view-only, operator, super-admin)
- [ ] All admin actions logged to audit trail
- [ ] Bulk operations: batch freeze (for fraud), batch notification

**NFR:** Dashboard load < 2s, Search results < 1s, Availability 99.9%

---

### 1.5 Notifications & Integrations

#### US-015: Notification Service

```
AS the platform
I WANT TO send notifications to users and merchants via multiple channels
SO THAT all parties are informed about transaction status
```

**Acceptance Criteria:**
- [ ] Channels: SMS, push notification (FCM/APNs), email, in-app
- [ ] Template-based notifications with dynamic variables
- [ ] Send on: payment success/failure, top-up, withdrawal, refund, dispute, freeze
- [ ] Delivery tracking + retry (max 3 attempts per channel)
- [ ] User preferences: opt-in/out per channel
- [ ] Batch notifications for settlement reports (merchants)

**NFR:** Delivery within 5s (first attempt), 99.9% delivery rate, Availability 99.9%

---

## 2. Non-Functional Requirements (NFR) Matrix

### 2.1 Per-Service NFR Matrix

| Service | Availability | Latency (p50/p99) | Throughput | Consistency | Data Volume (Year 1) | Security Level | Scalability | Recoverability |
|---------|-------------|-------------------|------------|-------------|---------------------|---------------|------------|----------------|
| **Account Service** | 99.95% | 50ms / 200ms | 500 RPS | Strong (serializable) | 1M accounts, ~500 MB | High (PII) | Horizontal | RPO < 1min, RTO < 15min |
| **Wallet Service** | 99.99% | 20ms / 100ms | 500 RPS | Strong (serializable) | 1M wallets, ~2 GB | Critical (financial) | Vertical + read replicas | RPO 0 (sync replication), RTO < 5min |
| **Ledger Service** | 99.99% | 30ms / 150ms | 1000 RPS | Strong (serializable) | 324M entries, ~97 GB | Critical (financial) | Vertical + partitioning | RPO 0, RTO < 5min |
| **Payment Service** | 99.99% | 100ms / 500ms | 500 RPS | Strong (state machine) | 108M txns, ~54 GB | Critical | Horizontal | RPO < 1min, RTO < 10min |
| **Transaction Service** | 99.95% | 50ms / 200ms | 1000 RPS (read-heavy) | Strong writes, eventual reads | 108M records, ~54 GB | High | Horizontal + read replicas | RPO < 1min, RTO < 15min |
| **Settlement Service** | 99.9% | N/A (batch) | 50 RPS (batch) | Eventual | 1.8M reports, ~5 GB | High | Vertical | RPO < 1min, RTO < 30min |
| **Reconciliation Service** | 99.9% | N/A (batch) | 20 RPS (batch) | Eventual | ~2 GB | High | Vertical | RPO < 1min, RTO < 30min |
| **Refund Service** | 99.95% | 100ms / 500ms | 100 RPS | Strong | 5M refunds, ~2.5 GB | Critical | Horizontal | RPO < 1min, RTO < 15min |
| **Dispute Service** | 99.9% | 200ms / 1s | 20 RPS | Strong writes | 500K disputes, ~500 MB | High | Vertical | RPO < 1min, RTO < 30min |
| **Fraud Service** | 99.99% | 10ms / 50ms | 500 RPS | Eventual rules, strong decisions | 108M scores, ~10 GB | Critical | Horizontal | RPO < 5min, RTO < 10min |
| **Limit Service** | 99.99% | 5ms / 30ms | 500 RPS | Strong checks, eventual counters | 1M configs, ~1 GB (+ Redis) | High | Horizontal + Redis cluster | RPO < 1min, RTO < 5min |
| **Notification Service** | 99.9% | 500ms / 3s | 200 RPS | Eventual | 216M records, ~43 GB | Medium | Horizontal | RPO < 5min, RTO < 30min |
| **Merchant Service** | 99.95% | 50ms / 200ms | 100 RPS | Strong for onboarding | 5K merchants, ~100 MB | High | Vertical | RPO < 1min, RTO < 15min |
| **KYC Service** | 99.9% | 1s / 5s (eKYC vendor) | 50 RPS | Eventual | 360K docs, ~360 GB (S3) | Critical (PII) | Vertical | RPO < 1min, RTO < 30min |
| **Reporting Service** | 99.5% | 1s / 5s | 50 RPS | Eventual (hours) | Materialized views, ~20 GB | Medium | Read replicas | RPO < 1h, RTO < 1h |
| **Audit Log Service** | 99.9% | 100ms / 500ms | 500 RPS | Append-only | 216M events, ~86 GB | Critical (compliance) | Horizontal + partitioning | RPO < 1min, RTO < 30min |
| **Bank Integration Service** | 99.95% | 500ms / 5s (bank dep.) | 100 RPS | Eventual (async) | 10M callbacks, ~5 GB | Critical | Horizontal | RPO < 1min, RTO < 15min |

### 2.2 NFR Dimensions Explained

| Dimension | Description |
|-----------|-------------|
| **Availability** | Uptime percentage. 99.99% = 4.38 min downtime/month. 99.95% = 21.9 min/month. 99.9% = 43.8 min/month. |
| **Latency** | p50 (median) and p99 (tail) response time. Measured end-to-end at API Gateway. |
| **Throughput** | Maximum sustained requests per second. Design capacity = 7× peak TPS. |
| **Consistency** | Strong = linearizable (serializable isolation). Eventual = read-after-write may be stale for seconds. |
| **Data Volume** | Total storage for Year 1 based on Phase 01 scale estimates. |
| **Security Level** | Critical = financial data + PII, requires encryption + strict access. High = sensitive, encrypted. Medium = internal. |
| **Scalability** | Horizontal = add instances. Vertical = scale up single instance. Partitioning = shard data. |
| **Recoverability** | RPO = max data loss on failure. RTO = max time to restore service. |

---

## 3. Traffic Estimation Model

### 3.1 Baseline Assumptions (from Phase 01)

| Metric | Value |
|--------|-------|
| Registered Users | 1,000,000 |
| DAU | 100,000 |
| MAU | 300,000 |
| Avg transactions per DAU/day | 3 |
| Daily Transactions | 300,000 |
| Active Hours | 12 hours (8 AM – 8 PM) |
| Peak Multiplier | 10× |
| Design Headroom | 7× above peak |

### 3.2 Request Volume per Endpoint

| Endpoint | Daily Requests | Avg RPS | Peak RPS | Design RPS | Notes |
|----------|---------------|---------|----------|------------|-------|
| `POST /auth/otp` | 10,000 | 0.2 | 2 | 15 | New registrations + PIN resets |
| `POST /auth/verify` | 10,000 | 0.2 | 2 | 15 | OTP verification |
| `POST /auth/login` | 150,000 | 3.5 | 35 | 250 | DAU logins + refreshes |
| `GET /wallets/{id}/balance` | 500,000 | 12 | 120 | 840 | Frequent balance checks |
| `POST /wallets/{id}/topup` | 50,000 | 1.2 | 12 | 84 | ~17% of txns are top-ups |
| `POST /payments/p2p` | 100,000 | 2.3 | 23 | 160 | ~33% of txns |
| `POST /payments/qr` | 80,000 | 1.9 | 19 | 130 | ~27% of txns |
| `POST /payments/bill` | 30,000 | 0.7 | 7 | 50 | ~10% of txns |
| `POST /payments/merchant` | 40,000 | 0.9 | 9 | 65 | ~13% of txns |
| `POST /wallets/{id}/withdraw` | 20,000 | 0.5 | 5 | 35 | ~7% of active users withdraw daily |
| `POST /refunds` | 2,000 | 0.05 | 0.5 | 5 | ~0.7% refund rate |
| `GET /transactions` | 300,000 | 7 | 70 | 490 | History queries |
| `GET /merchants/dashboard` | 10,000 | 0.2 | 2 | 15 | Merchant portal |
| `POST /notifications` | 600,000 | 14 | 140 | 980 | 2 notifications per txn avg |
| **Total API (estimated)** | **~2,200,000** | **~45 RPS** | **~450 RPS** | **~3,150 RPS** | Design capacity |

### 3.3 Internal Service-to-Service Traffic

| Call Pattern | Per User Transaction | Daily Volume | Notes |
|-------------|---------------------|-------------|-------|
| Payment → Fraud Service | 1 call | 300K | Sync, < 50ms |
| Payment → Limit Service | 1 call | 300K | Sync, < 50ms |
| Payment → Wallet Service | 2 calls (debit + credit) | 600K | Sync, critical path |
| Payment → Ledger Service | 1 call | 300K | Sync (co-located) or outbox |
| Payment → Transaction Service | 1 call | 300K | Outbox event |
| Wallet → Notification Service | 2 events | 600K | Async via Kafka |
| Settlement → Ledger | batch | 5K batches | EOD batch |
| Reconciliation → Bank Integration | 1 per bank | ~10/day | Batch SFTP/API |
| **Total internal calls** | **~8 per txn** | **~2,400,000/day** | |

### 3.4 Kafka Event Volume

| Topic | Events/Day | Events/Sec (avg) | Peak Events/Sec | Size/Event |
|-------|-----------|------------------|-----------------|-----------|
| `payment.events` | 300,000 | 7 | 70 | ~500B |
| `wallet.events` | 600,000 | 14 | 140 | ~400B |
| `ledger.entries` | 900,000 | 21 | 210 | ~300B |
| `notification.commands` | 600,000 | 14 | 140 | ~300B |
| `audit.events` | 600,000 | 14 | 140 | ~400B |
| `fraud.alerts` | 3,000 | 0.07 | 0.7 | ~500B |
| `settlement.events` | 5,000 | 0.1 | 1 | ~1KB |
| Other topics | 100,000 | 2.3 | 23 | ~400B |
| **Total** | **~3,100,000** | **~72/sec** | **~725/sec** | |

### 3.5 Storage Growth Model

| Data Store | Daily Growth | Monthly Growth | Year 1 Total | Notes |
|-----------|-------------|----------------|-------------|-------|
| PostgreSQL (all DBs) | ~700 MB | ~21 GB | ~250 GB | Transactions, ledger, accounts |
| Kafka (retained) | ~1.5 GB | ~45 GB | ~50 GB active | 7-day default retention |
| Object Storage (S3) | ~1 GB | ~30 GB | ~400 GB | KYC docs, receipts, reports |
| Redis | — | — | ~15 GB steady | Sessions, rate limits, counters |
| OpenSearch | ~150 MB | ~4.5 GB | ~50 GB | Transaction search index |
| **Total** | **~3.3 GB/day** | **~100 GB/month** | **~765 GB** | |

---

## 4. SLO Definitions

### 4.1 Platform-Level SLOs

| SLO | Target | Error Budget | Measurement |
|-----|--------|-------------|-------------|
| **API Availability** | 99.95% | 21.9 min downtime/month | Synthetic probes + real user monitoring |
| **Critical Path Availability** (P2P, QR) | 99.99% | 4.38 min downtime/month | Real transaction success rate |
| **Payment Success Rate** | > 99.5% | < 0.5% failure | Excluding user-caused failures (insufficient balance) |
| **Data Durability** | 99.999999999% (11 nines) | Immeasurably small | Replication + backup verification |

### 4.2 Per-Journey SLOs

| Journey | Latency p50 | Latency p99 | Availability | Error Rate |
|---------|-------------|-------------|--------------|------------|
| User Registration | < 2s | < 5s | 99.95% | < 1% |
| Wallet Top-Up (API response) | < 500ms | < 2s | 99.95% | < 0.5% |
| P2P Transfer | < 200ms | < 500ms | 99.99% | < 0.1% |
| QR Payment | < 500ms | < 3s | 99.99% | < 0.5% |
| Bill Payment | < 2s | < 5s | 99.9% | < 1% |
| Merchant Payment | < 1s | < 3s | 99.95% | < 0.5% |
| Withdrawal (API) | < 500ms | < 2s | 99.95% | < 0.5% |
| Refund | < 500ms | < 2s | 99.95% | < 0.1% |
| Transaction History | < 100ms | < 300ms | 99.9% | < 0.1% |
| Balance Check | < 20ms | < 100ms | 99.99% | < 0.01% |

### 4.3 Internal Service SLOs

| Service | Availability SLO | Latency SLO (p99) | Error Budget |
|---------|-----------------|-------------------|-------------|
| Wallet Service | 99.99% | < 100ms | 4.38 min/month |
| Ledger Service | 99.99% | < 150ms | 4.38 min/month |
| Payment Service | 99.99% | < 500ms | 4.38 min/month |
| Fraud Service | 99.99% | < 50ms | 4.38 min/month |
| Limit Service | 99.99% | < 30ms | 4.38 min/month |
| Notification Service | 99.9% | < 3s | 43.8 min/month |
| Settlement Service | 99.9% | N/A (batch) | 43.8 min/month |
| Bank Integration Service | 99.95% | < 5s (bank-dependent) | 21.9 min/month |

### 4.4 SLO → Architecture Implications

| SLO Requirement | Architecture Decision |
|----------------|----------------------|
| Wallet 99.99% availability | Synchronous replication (zero data loss), hot standby, connection pooling, no dependency on non-critical services |
| P2P < 200ms p50 | Wallet + Ledger co-located (same DB), no network hop for core path, Redis for limit cache |
| Fraud check < 50ms | In-memory rule engine, pre-loaded risk models, Redis for velocity counters |
| 99.5% payment success rate | Circuit breakers on bank integration, retry with backoff, fallback banks, graceful degradation |
| 11-nines data durability | Synchronous replication for wallet/ledger, async for others, daily backups tested monthly |
| EOD settlement correctness | Three-way reconciliation, idempotent settlement, manual override for exceptions |
| Zero ledger imbalance | Same-DB transaction for wallet+ledger, daily reconciliation job, automated alerts |

---

## 5. Capacity Planning (Year 1)

### 5.1 Compute Resources

| Service | Instances (prod) | CPU / Instance | RAM / Instance | Notes |
|---------|-----------------|----------------|----------------|-------|
| API Gateway | 3 | 2 vCPU | 4 GB | Nginx/Kong, stateless |
| Account Service | 2 | 2 vCPU | 4 GB | Low traffic |
| Wallet Service | 3 | 4 vCPU | 8 GB | Critical, high traffic |
| Ledger Service | 2 | 4 vCPU | 8 GB | Co-located with wallet DB |
| Payment Service | 3 | 4 vCPU | 8 GB | Orchestrator, high traffic |
| Transaction Service | 2 | 2 vCPU | 4 GB | Read-heavy, replicas |
| Settlement Service | 1 | 4 vCPU | 8 GB | Batch processing, scale up at EOD |
| Reconciliation Service | 1 | 2 vCPU | 4 GB | Batch |
| Refund Service | 2 | 2 vCPU | 4 GB | |
| Dispute Service | 1 | 2 vCPU | 4 GB | Low traffic |
| Fraud Service | 3 | 4 vCPU | 8 GB | Low latency critical |
| Limit Service | 2 | 2 vCPU | 4 GB | Redis-backed |
| Notification Service | 3 | 2 vCPU | 4 GB | Async, bursty |
| Merchant Service | 1 | 2 vCPU | 4 GB | |
| KYC Service | 1 | 2 vCPU | 4 GB | |
| Reporting Service | 1 | 4 vCPU | 8 GB | Heavy queries |
| Audit Log Service | 2 | 2 vCPU | 4 GB | High write throughput |
| Bank Integration Service | 2 | 2 vCPU | 4 GB | External connections |
| **Total** | **35 instances** | **~90 vCPU** | **~180 GB RAM** | |

### 5.2 Database Resources

| Database | Engine | Size (Year 1) | Instance Type | Replication |
|----------|--------|--------------|---------------|-------------|
| `wallet_db` (+ ledger) | PostgreSQL 16 | ~100 GB | 4 vCPU, 16 GB RAM | Synchronous (1 primary + 1 sync standby + 1 async read replica) |
| `payment_db` (+ refunds) | PostgreSQL 16 | ~60 GB | 4 vCPU, 16 GB RAM | Async (1 primary + 1 async replica) |
| `transaction_db` | PostgreSQL 16 | ~55 GB | 4 vCPU, 16 GB RAM | Async (1 primary + 2 read replicas) |
| `account_db` | PostgreSQL 16 | ~1 GB | 2 vCPU, 8 GB RAM | Async (1 primary + 1 replica) |
| `audit_db` | PostgreSQL 16 (TimescaleDB) | ~90 GB | 4 vCPU, 16 GB RAM | Async (partitioned by month) |
| Other service DBs (7) | PostgreSQL 16 | ~45 GB total | 2 vCPU, 8 GB RAM each | Async (1 primary + 1 replica) |
| **Redis Cluster** | Redis 7 | ~15 GB | 3 nodes × (2 vCPU, 8 GB) | Redis Sentinel / Cluster |
| **Kafka Cluster** | Kafka 3.6 | ~50 GB active | 3 brokers × (4 vCPU, 16 GB) | Replication factor = 3 |
| **OpenSearch** | OpenSearch 2.x | ~50 GB | 2 nodes × (4 vCPU, 16 GB) | 1 replica per shard |

### 5.3 Network Bandwidth

| Traffic Type | Bandwidth (avg) | Bandwidth (peak) |
|-------------|-----------------|-------------------|
| API Gateway ingress | ~5 Mbps | ~50 Mbps |
| Service-to-service | ~20 Mbps | ~200 Mbps |
| Kafka internal | ~10 Mbps | ~100 Mbps |
| Database replication | ~5 Mbps | ~50 Mbps |
| External (banks, providers) | ~2 Mbps | ~20 Mbps |
| **Total** | **~42 Mbps** | **~420 Mbps** |

### 5.4 Monthly Cost Estimate (Cloud)

| Category | Estimated Monthly Cost |
|----------|----------------------|
| Compute (35 instances) | $2,500 – $4,000 |
| Databases (PostgreSQL managed) | $3,000 – $5,000 |
| Redis Cluster | $500 – $800 |
| Kafka (managed) | $1,000 – $2,000 |
| Object Storage (S3) | $100 – $200 |
| Network / CDN | $200 – $500 |
| Monitoring / Logging | $300 – $500 |
| SMS / Push / Email | $500 – $1,500 |
| eKYC provider | $500 – $2,000 |
| **Total** | **$8,600 – $16,500/month** |

---

## 6. ADR (Architecture Decision Records)

### ADR-001: Database per Service (with Strategic Co-location)

**Status:** Accepted  
**Context:** Microservices best practice dictates database-per-service for loose coupling. However, the Wallet ↔ Ledger consistency requirement demands ACID guarantees.  
**Decision:** Each service owns its database. Exception: Wallet + Ledger co-located in `wallet_db`, Payment + Refund co-located in `payment_db`.  
**Consequences:** Strong consistency for financial core. Each team must never query another service's DB directly — use APIs or events.

### ADR-002: Async-First Communication (Outbox/Inbox Pattern)

**Status:** Accepted  
**Context:** Distributed transactions (2PC) are fragile and slow. We need reliable cross-service communication.  
**Decision:** All inter-service communication uses async events via Kafka, with outbox pattern on producer side and inbox pattern on consumer side. Sync HTTP calls only for real-time requirements (fraud check, limit check, balance query).  
**Consequences:** Eventual consistency for non-critical paths. At-least-once delivery with inbox deduplication. DLQ for failed events. Saga orchestration for multi-step flows.

---

## 7. Integration SLAs & Fallback Strategies

| Integration | Expected SLA | Our Timeout | Retry | Fallback |
|-------------|-------------|-------------|-------|----------|
| **NAPAS** (interbank) | 99.9%, < 5s | 10s | 3x exponential | Queue and retry; fallback to direct bank API |
| **Partner Banks** | 99.5%, < 15s | 30s | 3x | Mark as PENDING; reconcile via bank statement |
| **Card Networks** (via acquirer) | 99.95%, < 3s | 5s | 2x | Display "Card payment unavailable" |
| **eKYC Provider** | 99.5%, < 10s | 15s | 2x | Queue for manual review |
| **SMS Gateway** | 99.9%, < 5s | 10s | 3x | Fallback to secondary provider |
| **Push Service** (FCM/APNs) | 99.9%, < 3s | 5s | 3x | In-app notification as fallback |
| **Utility Providers** | 99%, < 10s | 15s | 3x | "Provider temporarily unavailable" |
| **Object Storage** (S3) | 99.99%, < 500ms | 2s | 3x | N/A (critical dependency) |

---

## 8. Connection to Phase 03

**Phase 03 — Risk Analysis & Threat Modeling** will use this document to produce:

| Input (from Phase 02) | Output (Phase 03) |
|----------------------|-------------------|
| NFR matrix (17 services × 8 dimensions) | Risk register per dimension |
| SLO definitions | FMEA for SLO-breaching failure modes |
| Integration SLAs | Third-party risk assessment + fallback validation |
| Traffic model | Scalability cliff analysis (at what RPS does architecture break?) |
| Security levels per service | STRIDE threat model per service |
| Capacity plan | Cost overrun risk + FinOps guardrails |

---

### 🛑 APPROVAL GATE — 📋 Document Review

> **Review `02-requirements-slos.md` (v1.0)**
>
> This document defines the complete requirements and SLOs for the payment platform. Please verify:
> - [ ] All 15 user stories cover MVP features with acceptance criteria
> - [ ] NFR matrix covers all 17 services × 8 dimensions
> - [ ] Traffic model is consistent with Phase 01 scale estimates
> - [ ] SLOs are realistic (99.99% for wallet/payment, 99.9% for batch services)
> - [ ] Capacity plan is reasonable for Year 1 (~35 instances, ~$8K–16K/month)
> - [ ] ADRs document the two key architecture decisions
> - [ ] Integration SLAs have proper fallback strategies
>
> Reply **APPROVE** to proceed to Phase 03 (Risk Analysis & Threat Modeling), or provide feedback.
