# Phase 02 — Requirements & SLOs

## MoMo-like Payment API Platform

> **Document Status**: Draft v3.0 — Pending Review  
> **Last Updated**: 2026-03-23  
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

### 1.6 Disputes & Chargebacks

#### US-016: Dispute / Chargeback

```
AS A registered user
I WANT TO file a dispute on a transaction I believe is incorrect or unauthorized
SO THAT I can get my money back when something goes wrong
```

**Acceptance Criteria:**
- [ ] User selects transaction → files dispute with reason code (unauthorized, not received, wrong amount, duplicate, other)
- [ ] Evidence upload: screenshots, receipts, correspondence (max 5 files, 10 MB each)
- [ ] Dispute types: `UNAUTHORIZED_TRANSACTION`, `GOODS_NOT_RECEIVED`, `INCORRECT_AMOUNT`, `DUPLICATE_CHARGE`, `OTHER`
- [ ] Merchant notified with 7-day response deadline
- [ ] Disputed amount held in escrow: DEBIT merchant_pending, CREDIT escrow:{dispute_id}
- [ ] Merchant submits counter-evidence or accepts dispute
- [ ] If merchant doesn't respond within 7 days → auto-resolve in user's favor
- [ ] Resolution: User wins → DEBIT escrow, CREDIT user_wallet; Merchant wins → DEBIT escrow, CREDIT merchant_pending
- [ ] Escalation path: unresolved disputes → arbitration queue (manual review)
- [ ] Dispute window: 180 days from transaction date
- [ ] Duplicate dispute on same transaction → reject
- [ ] All dispute actions logged to audit trail with actor + timestamp
- [ ] Both parties notified at each status change (opened, evidence requested, resolved)

**NFR:** Dispute creation < 1s, Resolution SLA ≤ 48h (auto) / 7 days (manual), Availability 99.9%

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

## 8. Error Budget Policy

### 8.1 Error Budget Definitions

The error budget is the inverse of the SLO target. It defines how much downtime or failure is "allowed" before corrective action is triggered.

| Service Tier | SLO Target | Error Budget (30 days) | Error Budget (quarterly) |
|-------------|-----------|----------------------|-------------------------|
| **Tier 0 — Critical** (Wallet, Ledger, Payment, Fraud, Limit) | 99.99% | 4.38 minutes | 13.15 minutes |
| **Tier 1 — Core** (Account, Transaction, Refund, Bank Integration, Merchant) | 99.95% | 21.9 minutes | 65.7 minutes |
| **Tier 2 — Supporting** (Notification, Settlement, Reconciliation, Dispute, KYC, Audit Log) | 99.9% | 43.8 minutes | 131.4 minutes |
| **Tier 3 — Non-critical** (Reporting) | 99.5% | 3.65 hours | 10.95 hours |

### 8.2 Composite SLO Analysis

For multi-service journeys, composite availability is the product of individual service availabilities (assuming serial dependency):

| Journey | Services in Path | Composite Availability | Notes |
|---------|-----------------|----------------------|-------|
| **P2P Transfer** | Wallet × Fraud × Limit × Ledger × Payment | 99.99%⁵ ≈ 99.95% | Co-location of Wallet+Ledger mitigates; treat as 99.99%⁴ ≈ 99.96% |
| **QR Payment** | Wallet × Fraud × Limit × Ledger × Payment × Merchant | 99.99%⁴ × 99.95% ≈ 99.91% | Merchant service is not in hot path for payment execution |
| **Wallet Top-Up** | Wallet × Ledger × Bank Integration | 99.99%² × 99.95% ≈ 99.93% | Bank integration is async; failure doesn't block API response |

> **Mitigation**: Co-locating Wallet + Ledger eliminates one network hop. Async patterns (outbox) decouple non-critical services from the hot path, making the effective composite higher than the theoretical calculation.

### 8.3 Error Budget Escalation Policy

| Budget Consumed | Action | Owner |
|----------------|--------|-------|
| **0–50%** | Normal operations. Feature velocity prioritized. | Engineering teams |
| **50–75%** | Alert raised. Reliability tasks added to sprint backlog. | Team Lead |
| **75–90%** | Feature freeze for affected service. All effort on reliability. | Engineering Manager |
| **90–100%** | Full incident mode. No deployments except fixes. Post-mortem required. | VP Engineering |
| **> 100%** (budget exhausted) | Mandatory reliability sprint. Architecture review triggered. Release freeze until budget recovers. | CTO + VP Engineering |

### 8.4 Error Budget Review Cadence

| Review | Frequency | Participants | Output |
|--------|-----------|-------------|--------|
| SLO Dashboard Review | Daily (automated) | On-call engineer | Slack alert if burn rate > 2× |
| Error Budget Stand-up | Weekly | Service owners, SRE | Budget status per service, action items |
| Quarterly SLO Review | Quarterly | Engineering leadership, Product | SLO adjustments, architecture decisions |

---

## 9. Observability & SLI Measurement

### 9.1 SLI Definitions

Service Level Indicators (SLIs) are the **measured metrics** that determine whether SLOs are being met.

| SLO Category | SLI | Measurement Method | Good Event Definition |
|-------------|-----|-------------------|----------------------|
| **Availability** | Request success rate | API Gateway access logs | HTTP status < 500 (excludes 4xx client errors) |
| **Latency** | Request duration | OpenTelemetry traces (span duration) | Duration ≤ SLO threshold (e.g., p99 < 500ms) |
| **Correctness** | Ledger balance accuracy | Reconciliation job output | `sum(debits) == sum(credits)` per journal entry |
| **Freshness** | Event processing lag | Kafka consumer lag metric | Consumer lag < 5 seconds for critical topics |
| **Throughput** | Successful transactions/sec | Prometheus counter (payment.success.total) | Actual TPS ≥ expected TPS (no drops) |
| **Durability** | Data persistence verification | Backup restore test + replication lag | Replication lag < 1s for sync replicas, backup verifiable |

### 9.2 RED Metrics (per Service)

Every service MUST expose **Rate, Errors, Duration** metrics for all endpoints:

| Metric | Prometheus Name | Labels | Description |
|--------|----------------|--------|-------------|
| **Rate** | `http_requests_total` | `service`, `method`, `endpoint`, `status_code` | Total request count (counter) |
| **Errors** | `http_requests_errors_total` | `service`, `method`, `endpoint`, `error_type` | Failed requests: 5xx, timeout, circuit-open (counter) |
| **Duration** | `http_request_duration_seconds` | `service`, `method`, `endpoint` | Request latency (histogram: p50, p95, p99) |

**Per-service RED targets (from NFR matrix):**

| Service | Rate (design RPS) | Error Rate Target | Duration p99 Target |
|---------|-------------------|-------------------|---------------------|
| Wallet Service | 500 RPS | < 0.01% | < 100ms |
| Ledger Service | 1000 RPS | < 0.01% | < 150ms |
| Payment Service | 500 RPS | < 0.5% | < 500ms |
| Fraud Service | 500 RPS | < 0.1% | < 50ms |
| Limit Service | 500 RPS | < 0.01% | < 30ms |
| Transaction Service | 1000 RPS | < 0.1% | < 200ms |
| Notification Service | 200 RPS | < 1% | < 3s |
| Bank Integration | 100 RPS | < 0.5% | < 5s |

**Additional business metrics per service:**

| Service | Business Metric | Prometheus Name |
|---------|----------------|-----------------|
| Payment Service | `payment_success_total`, `payment_failed_total`, `payment_amount_total` | Transaction outcomes + volume |
| Wallet Service | `wallet_balance_operation_total{op=credit\|debit\|hold}` | Balance operation counts |
| Ledger Service | `ledger_journal_entry_total`, `ledger_balance_check_failures` | Journal entries + integrity checks |
| Fraud Service | `fraud_decision_total{decision=allow\|review\|block}` | Risk decision distribution |
| Settlement Service | `settlement_batch_total`, `settlement_amount_total`, `settlement_exceptions` | EOD batch metrics |

### 9.3 USE Metrics (Infrastructure)

Every infrastructure component MUST expose **Utilization, Saturation, Errors**:

| Resource | Utilization | Saturation | Errors |
|----------|-------------|------------|--------|
| **CPU** | `node_cpu_seconds_total` (usage %) | Run queue length (`node_load1`) | N/A |
| **Memory** | `node_memory_MemAvailable_bytes` / total | Swap usage, OOM events | OOM kills (`node_vmstat_oom_kill`) |
| **Disk** | `node_filesystem_avail_bytes` / size | I/O wait (`node_disk_io_time_seconds_total`) | Disk errors (`node_disk_io_errors`) |
| **Network** | `node_network_transmit_bytes_total` | TCP retransmits, dropped packets | Interface errors |
| **PostgreSQL** | Active connections / `max_connections` | Connection queue wait, lock waits | Deadlocks (`pg_stat_database_deadlocks`) |
| **Redis** | Memory used / `maxmemory` | Eviction rate, key misses | Rejected connections |
| **Kafka** | Broker disk usage, ISR count | Consumer lag (messages behind), under-replicated partitions | Failed produce/consume |
| **Connection Pool** | Active / max pool size | Queue depth (waiting threads) | Timeout errors |

### 9.4 Logging Strategy

#### Log Schema (Structured JSON)

All services MUST emit structured JSON logs with this schema:

```json
{
  "timestamp": "2026-03-23T09:15:23.456Z",
  "level": "INFO|WARN|ERROR|DEBUG",
  "service": "payment-service",
  "version": "1.2.3",
  "environment": "production",
  "traceId": "abc123def456",
  "spanId": "span789",
  "correlationId": "corr-uuid-here",
  "requestId": "req-uuid-here",
  "userId": "usr_xxxxx",
  "method": "POST",
  "path": "/payments/p2p",
  "statusCode": 200,
  "duration_ms": 145,
  "message": "P2P transfer completed",
  "context": {
    "transactionId": "txn_xxxxx",
    "amount": 50000,
    "currency": "VND"
  },
  "error": {
    "code": "INSUFFICIENT_BALANCE",
    "message": "Balance 30000 < requested 50000",
    "stack": "..."
  }
}
```

#### Log Levels Policy

| Level | Usage | Examples | Volume Target |
|-------|-------|---------|---------------|
| **ERROR** | Unrecoverable failures requiring attention | DB connection failure, unhandled exception, ledger imbalance | < 0.1% of requests |
| **WARN** | Recoverable issues, degraded behavior | Circuit breaker open, retry triggered, rate limit hit | < 1% of requests |
| **INFO** | Significant business events | Payment completed, user registered, settlement batch started | ~1 log per request |
| **DEBUG** | Detailed execution flow (disabled in prod) | SQL queries, cache hits/misses, serialization details | Disabled in production |

#### PII Handling in Logs

| Field | Log Treatment | Example |
|-------|--------------|---------|
| Phone number | Mask: `****1234` | Never log full phone |
| National ID | Never log | Redact completely |
| PIN / passwords | Never log | Redact completely |
| Wallet balance | Log amount only in financial events | Allowed in transaction logs |
| User name | Mask: `N****` | First char only |
| IP address | Log in security events only | Fraud, auth failure |

#### Log Pipeline

```
Application → Fluentd/Fluent Bit (sidecar) → Kafka (log topic) → Logstash → 
  ├── Elasticsearch (hot: 30d) → Kibana (search/dashboards)
  ├── S3 (warm: 90d, cold: 1yr) → Athena (ad-hoc queries)
  └── Alert rules → PagerDuty/Slack (error spike detection)
```

### 9.5 Distributed Tracing

#### Trace Propagation

| Component | Propagation Method | Context Format |
|-----------|-------------------|----------------|
| HTTP (service-to-service) | `traceparent` / `tracestate` headers (W3C Trace Context) | OpenTelemetry |
| Kafka events | `traceparent` in event headers | OpenTelemetry |
| Database queries | Span annotation on DB client | OpenTelemetry JDBC/pg instrumentation |
| Redis operations | Span annotation on Redis client | OpenTelemetry Redis instrumentation |
| External APIs (banks) | `X-Correlation-Id` header | Custom (mapped to trace) |

#### Trace Sampling Strategy

| Traffic Type | Sampling Rate | Rationale |
|-------------|--------------|-----------|
| Error responses (5xx) | 100% | Always capture failures |
| Slow requests (> 2× p99 SLO) | 100% | Always capture latency outliers |
| Payment transactions | 100% | Financial audit trail |
| Balance queries | 10% | High volume, low value |
| Health checks | 0% | Noise |
| Normal traffic | 5% baseline | Sufficient for trend analysis |

#### Span Naming Convention

```
{service}.{operation}.{target}

Examples:
  payment-service.http.POST./payments/p2p
  wallet-service.db.query.wallets.findByUserId
  fraud-service.redis.get.velocity_counter
  payment-service.kafka.produce.payment.events
  bank-integration.http.POST.napas.transfer
```

### 9.6 Observability Stack

| Layer | Tool | Purpose |
|-------|------|---------|
| **Metrics** | Prometheus + Grafana | Time-series metrics, dashboards, alerting |
| **Logs** | ELK Stack (Elasticsearch, Logstash, Kibana) or Loki | Structured logging, log aggregation, search |
| **Traces** | OpenTelemetry + Jaeger/Tempo | Distributed tracing, request flow visualization |
| **Alerting** | Grafana Alerting + PagerDuty | SLO-based alerts, on-call notification |
| **Synthetic Monitoring** | Grafana Synthetic / custom probes | Uptime probes, critical path health checks |
| **Real User Monitoring** | Custom metrics from API Gateway | Actual user-experienced latency and error rates |
| **Profiling** | Pyroscope / Grafana Phlare | Continuous profiling for latency root cause |

### 9.7 SLI Measurement Windows

| Window Type | Duration | Use Case |
|------------|----------|----------|
| **Rolling window** | 30 days | Primary SLO measurement. Smooths daily variance. |
| **Calendar window** | Monthly / Quarterly | Reporting, error budget reviews, stakeholder communication. |
| **Short burn-rate** | 1 hour | Fast alert: catches sudden outages. Alert if burn rate > 14.4× (1h budget consumed in 5 min). |
| **Slow burn-rate** | 6 hours | Slow alert: catches gradual degradation. Alert if burn rate > 6× over 6 hours. |

### 9.8 Key Dashboards

| Dashboard | Content | Audience |
|-----------|---------|----------|
| **Platform Health** | Aggregate availability, latency p50/p99, error rate, active users | Engineering leadership, on-call |
| **Per-Service SLO** | Per-service SLI vs SLO, error budget remaining, burn rate | Service owners |
| **RED Dashboard** | Rate/Error/Duration per service with drill-down | Service owners |
| **USE Dashboard** | CPU/Memory/Disk/Network utilization per node + DB | SRE, platform team |
| **Payment Flow** | Transaction success rate, payment latency breakdown, failure reasons | Payment team |
| **Financial Integrity** | Ledger balance check, reconciliation status, settlement progress | Finance, compliance |
| **Infrastructure** | CPU, memory, disk, network, DB connections, Kafka lag | SRE, platform team |
| **External Dependencies** | Bank API latency, eKYC success rate, SMS delivery rate | Integration team |
| **Cost Dashboard** | Per-service cost, cost-per-transaction, monthly burn | Engineering leadership, FinOps |

### 9.9 Alerting Rules & SLO Burn Rate Alerts

| Alert Type | Condition | Severity | Notification | Response |
|-----------|-----------|----------|-------------|----------|
| **SLO Burn Rate (fast)** | >14.4× burn rate over 1h | P1 — Critical | PagerDuty (wake-up) | Immediate investigation |
| **SLO Burn Rate (slow)** | >6× burn rate over 6h | P2 — Warning | Slack + PagerDuty (business hours) | Investigate within 1h |
| **Error Spike** | Error rate >5% for 5 min | P1 — Critical | PagerDuty | Immediate triage |
| **Latency Degradation** | p99 > 2× SLO for 10 min | P2 — Warning | Slack | Investigate within 30 min |
| **Kafka Consumer Lag** | Lag > 10K messages for 5 min | P2 — Warning | Slack | Scale consumers or investigate |
| **DB Connection Pool** | Utilization > 80% for 5 min | P2 — Warning | Slack | Check for leaks, scale pool |
| **DB Replication Lag** | Lag > 1s (sync) or > 30s (async) for 2 min | P1 — Critical | PagerDuty | Investigate replication |
| **Disk Usage** | >80% on any volume | P3 — Info | Slack | Plan capacity expansion |
| **Reconciliation Failure** | Any unmatched entry | P2 — Warning | Slack + Email | Manual review within 4h |
| **Ledger Imbalance** | `sum(debits) != sum(credits)` | P0 — Critical | PagerDuty + SMS | Stop processing, investigate immediately |
| **Circuit Breaker Open** | Any circuit breaker trips | P2 — Warning | Slack | Investigate downstream service |

### 9.10 Example SLI Measurement Queries (PromQL)

**Availability SLI (rolling 30d):**

```promql
# Availability = 1 - (error requests / total requests)
1 - (
  sum(rate(http_requests_total{status_code=~"5..", service="wallet-service"}[30d]))
  /
  sum(rate(http_requests_total{service="wallet-service"}[30d]))
)
```

**Latency SLI (p99):**

```promql
# p99 latency for payment service
histogram_quantile(0.99,
  sum(rate(http_request_duration_seconds_bucket{service="payment-service"}[5m])) by (le)
)
```

**Error Budget Remaining:**

```promql
# Error budget remaining (%) for 99.99% SLO over 30d rolling window
(
  1 - (
    sum(rate(http_requests_total{status_code=~"5..", service="wallet-service"}[30d]))
    /
    sum(rate(http_requests_total{service="wallet-service"}[30d]))
  )
  - 0.9999
) / (1 - 0.9999) * 100
```

**SLO Burn Rate (fast — 1h window):**

```promql
# Burn rate: how fast we're consuming error budget
# Alert if > 14.4x (consuming 1h of 30d budget in 5 min)
(
  sum(rate(http_requests_total{status_code=~"5..", service="wallet-service"}[1h]))
  /
  sum(rate(http_requests_total{service="wallet-service"}[1h]))
) / (1 - 0.9999)
```

**Kafka Consumer Lag:**

```promql
# Consumer lag per consumer group
sum(kafka_consumer_group_lag{topic="payment.events"}) by (consumer_group)
```

**Payment Success Rate:**

```promql
# Payment success rate (business SLI)
sum(rate(payment_success_total[5m]))
/
(sum(rate(payment_success_total[5m])) + sum(rate(payment_failed_total[5m])))
```

**Database Connection Pool Saturation:**

```promql
# Connection pool utilization
sum(hikaricp_connections_active{service="wallet-service"})
/
sum(hikaricp_connections_max{service="wallet-service"})
```

---

## 10. DR / Multi-Region Strategy

### 10.1 DR Objectives

| Objective | Target | Rationale |
|-----------|--------|-----------|
| **RTO (Recovery Time Objective)** | ≤ 15 minutes (Tier 0), ≤ 30 minutes (Tier 1–2), ≤ 1 hour (Tier 3) | Financial platform requires fast recovery |
| **RPO (Recovery Point Objective)** | 0 (Tier 0 — sync replication), < 1 minute (Tier 1), < 5 minutes (Tier 2–3) | Zero data loss for wallet/ledger |
| **DR Test Frequency** | Quarterly | Validate failover procedures |

### 10.2 Multi-Region Strategy (Year 1 → Year 2)

**Year 1 — Single Region with DR:**

```
┌─────────────────────────────────────────┐
│          Primary Region (AZ-1 + AZ-2)   │
│                                         │
│  AZ-1: Services + Primary DBs           │
│  AZ-2: Services + Sync Standby DBs      │
│                                         │
│  ← Sync replication (Tier 0 DBs) ──→    │
│  ← Async replication (Tier 1-3 DBs) ──→ │
└─────────────────┬───────────────────────┘
                  │ Async replication
                  ▼
┌─────────────────────────────────────────┐
│         DR Region (cold standby)        │
│                                         │
│  DB replicas (async, < 1 min lag)       │
│  Pre-provisioned infra (IaC ready)      │
│  No active services (cold)              │
└─────────────────────────────────────────┘
```

**Year 2+ — Active-Passive Multi-Region:**
- Primary region handles all traffic
- Secondary region receives async replication + warm standby services
- DNS failover via Route 53 health checks (TTL: 60s)
- Regional data residency compliance (if required)

### 10.3 Failover Procedures

| Scenario | Detection | Failover Action | RTO |
|----------|-----------|----------------|-----|
| **Single AZ failure** | Health check failure | Auto-failover to AZ-2 (ALB routing) | < 1 minute |
| **Primary DB failure** | PostgreSQL streaming replication | Promote sync standby → new primary | < 5 minutes |
| **Full region failure** | Route 53 health checks | DNS failover to DR region + promote DB replicas | < 15 minutes |
| **Kafka broker failure** | ISR count drop | Auto leader election (replication factor = 3) | < 30 seconds |
| **Redis node failure** | Sentinel / Cluster heartbeat | Auto failover to replica | < 10 seconds |

### 10.4 Data Replication Matrix

| Data Store | Replication Type | Lag Target | Cross-AZ | Cross-Region |
|-----------|-----------------|-----------|----------|-------------|
| `wallet_db` (+ ledger) | Synchronous | 0 | ✅ Yes (sync standby) | ✅ Async (< 1 min) |
| `payment_db` | Asynchronous | < 100ms | ✅ Yes | ✅ Async (< 1 min) |
| `transaction_db` | Asynchronous | < 100ms | ✅ Yes | ✅ Async (< 5 min) |
| Kafka | ISR replication | 0 (in-sync) | ✅ Yes (3 AZs) | ❌ Year 2 (MirrorMaker) |
| Redis | Async replication | < 1s | ✅ Yes | ❌ Year 2 |
| S3 | Cross-region replication | < 15 min | N/A (managed) | ✅ Yes (CRR enabled) |

---

## 11. Rate Limiting Strategy

### 11.1 Rate Limiting Layers

```
┌──────────────┐    ┌─────────────┐    ┌──────────────┐    ┌───────────────┐
│   WAF / CDN  │ →  │ API Gateway │ →  │   Service    │ →  │    Database   │
│  (L3/L4 DDoS)│    │ (L7 rate    │    │ (business    │    │  (connection  │
│              │    │  limiting)  │    │  rate checks)│    │   pooling)    │
└──────────────┘    └─────────────┘    └──────────────┘    └───────────────┘
```

### 11.2 Rate Limit Tiers

| Tier | Scope | Limits | Algorithm | Store |
|------|-------|--------|-----------|-------|
| **Global** | Per-IP | 1000 req/min, 100 req/sec burst | Token bucket | Redis (shared) |
| **Authenticated User** | Per-user-id | 200 req/min, 30 req/sec burst | Sliding window | Redis |
| **Transaction** | Per-user-id | 20 txns/min, 100 txns/hour | Sliding window log | Redis |
| **Merchant API** | Per-API-key | Based on plan: Basic 100/min, Pro 1000/min, Enterprise custom | Token bucket | Redis |
| **Admin API** | Per-admin-user | 500 req/min | Fixed window | Redis |
| **OTP** | Per-phone-number | 5 OTPs/hour, 3 attempts per OTP | Fixed window + counter | Redis |
| **PIN** | Per-user-id | 5 attempts/session, 10 attempts → permanent lock | Counter with escalation | Redis + DB |

### 11.3 Rate Limit Response

```json
{
  "type": "https://api.paywallet.vn/errors/rate-limited",
  "title": "Rate Limit Exceeded",
  "status": 429,
  "detail": "You have exceeded the rate limit of 200 requests per minute.",
  "instance": "/payments/p2p",
  "retryAfter": 32,
  "errors": []
}
```

**Response Headers:**

| Header | Description | Example |
|--------|-------------|--------|
| `X-RateLimit-Limit` | Max requests in window | `200` |
| `X-RateLimit-Remaining` | Remaining requests | `0` |
| `X-RateLimit-Reset` | Unix timestamp when window resets | `1711180800` |
| `Retry-After` | Seconds until next request allowed | `32` |

### 11.4 DDoS Protection

| Layer | Protection | Tool |
|-------|-----------|------|
| **Network (L3/L4)** | Volumetric attack mitigation | AWS Shield Standard |
| **Application (L7)** | Request rate limiting, geo-blocking, bot detection | AWS WAF + custom rules |
| **API** | Per-client rate limiting, anomaly detection | API Gateway + Redis |
| **Business Logic** | Velocity checks, device fingerprinting | Fraud Service |

---

## 12. Data Retention & Compliance

### 12.1 Data Classification

| Classification | Description | Examples | Encryption | Access Control |
|---------------|-------------|----------|------------|---------------|
| **CRITICAL** | Financial data, payment credentials | Wallet balances, ledger entries, card tokens | AES-256 at-rest + TLS 1.3 in-transit | Strict RBAC, audit logged |
| **SENSITIVE (PII)** | Personally identifiable information | Name, phone, national ID, address, DOB | AES-256 at-rest + TLS 1.3 in-transit | RBAC, data masking in logs |
| **CONFIDENTIAL** | Business-sensitive data | Merchant API keys, settlement data, risk scores | AES-256 at-rest | RBAC |
| **INTERNAL** | Operational data | Metrics, logs (without PII), config | TLS in-transit | Team-based access |
| **PUBLIC** | Non-sensitive data | API documentation, public status page | None required | Open |

### 12.2 Retention Policies

| Data Type | Retention Period | Regulatory Basis | Storage Tier | Deletion Method |
|-----------|-----------------|-------------------|-------------|----------------|
| **Transaction records** | 10 years | State Bank of Vietnam regulations | Hot: 1 year, Warm: 3 years, Cold: 6 years (S3 Glacier) | Automated lifecycle policy |
| **Ledger entries** | 10 years (immutable) | Accounting regulations | Hot: 1 year, Archive: 9 years | Never deleted — archival only |
| **Audit logs** | 7 years | Compliance / SOC 2 | Hot: 6 months, Cold: 6.5 years (S3 Glacier) | Automated lifecycle |
| **KYC documents** | 5 years after account closure | AML regulations | S3 Standard (encrypted) | Automated deletion + verification |
| **User PII** | Duration of account + 5 years | PDPA / GDPR-equiv | Database (encrypted) | Hard delete on request + 5yr |
| **Session tokens** | 24 hours (refresh: 7 days) | Security best practice | Redis | Auto-expire (TTL) |
| **Kafka events** | 7 days (default), 30 days (critical topics) | Operational | Kafka log retention | Auto-purge |
| **Metrics data** | Raw: 15 days, Downsampled: 1 year, Summary: 3 years | Operational | Prometheus + S3 | Auto-retention policy |
| **Application logs** | 30 days (hot), 90 days (warm), 1 year (archive) | Operational / debugging | ELK / Loki + S3 | Auto-lifecycle |

### 12.3 Data Subject Rights (PDPA / GDPR-equivalent)

| Right | Implementation | SLA |
|-------|---------------|-----|
| **Right to Access** | API endpoint to export user data (JSON) | < 24 hours |
| **Right to Rectification** | Admin API to correct PII | < 48 hours |
| **Right to Erasure** | Soft delete → hard delete after regulatory hold period | 30 days (regulatory hold check) |
| **Right to Portability** | Structured data export (JSON/CSV) | < 72 hours |
| **Right to Object** | Opt-out of marketing notifications | Immediate |

### 12.4 Compliance Frameworks

| Framework | Scope | Key Requirements |
|-----------|-------|------------------|
| **PCI-DSS v4.0** | Card data handling (if applicable) | Tokenization, encryption, network segmentation, quarterly scans |
| **State Bank of Vietnam** | E-wallet operations | KYC tiers, transaction limits, reporting, capital requirements |
| **PDPA (Vietnam)** | Personal data processing | Consent, data minimization, breach notification (72h), cross-border rules |
| **SOC 2 Type II** | Platform security controls | Security, availability, confidentiality, processing integrity, privacy |

---

## 13. Backup & Restore Strategy

### 13.1 Backup Matrix

| Database | Backup Type | Frequency | Retention | Cross-Region | RTO | RPO |
|----------|-----------|-----------|-----------|-------------|-----|-----|
| `wallet_db` (+ ledger) | Continuous WAL archiving + daily full | Continuous (WAL), daily (full) | 30 days (full), 7 days (WAL) | ✅ S3 cross-region | < 5 min (PITR) | 0 (sync replication) |
| `payment_db` | Continuous WAL archiving + daily full | Continuous (WAL), daily (full) | 30 days (full), 7 days (WAL) | ✅ S3 cross-region | < 10 min (PITR) | < 1 min |
| `transaction_db` | Daily full + hourly incremental | Daily (full), hourly (incr) | 30 days | ✅ S3 cross-region | < 15 min | < 1 hour |
| `account_db` | Daily full + continuous WAL | Continuous (WAL), daily (full) | 30 days | ✅ S3 cross-region | < 10 min | < 1 min |
| `audit_db` | Daily full | Daily | 90 days (then S3 Glacier) | ✅ S3 cross-region | < 30 min | < 24 hours |
| Other service DBs | Daily full + hourly incremental | Daily (full), hourly (incr) | 14 days | ✅ S3 cross-region | < 30 min | < 1 hour |
| Redis | RDB snapshot + AOF | Hourly (RDB), continuous (AOF) | 7 days | ❌ (reconstructable) | < 5 min | < 1 min |
| Kafka | Topic replication (factor=3) | Continuous | 7–30 days (retention) | ❌ Year 2 | N/A (HA) | 0 (ISR) |
| S3 (KYC, receipts) | Versioning + cross-region replication | Continuous | Per retention policy | ✅ Auto CRR | N/A (managed) | < 15 min |

### 13.2 Backup Verification

| Verification | Frequency | Method | Owner |
|-------------|-----------|--------|-------|
| Automated restore test | Weekly | Restore latest backup to isolated env → run integrity checks | SRE |
| PITR test (wallet_db) | Monthly | Restore to random point in time → verify balance integrity | SRE + Finance |
| Full DR restore drill | Quarterly | Restore all databases from cross-region backups → run E2E tests | SRE + all teams |
| Backup integrity check | Daily (automated) | Checksum verification of backup files | Automated (cron) |

### 13.3 Restore Procedures

| Scenario | Procedure | Expected Duration |
|----------|-----------|-------------------|
| **Single table corruption** | PITR to before corruption → extract table → restore via pg_dump/pg_restore | 15–30 minutes |
| **Full database loss** | Promote standby OR restore from latest backup + WAL replay | 5–15 minutes (standby), 30–60 min (backup) |
| **Accidental data deletion** | PITR to before deletion → extract affected records → apply to production | 30–60 minutes |
| **Ransomware / total compromise** | Restore from cross-region backup (immutable) → rebuild infrastructure from IaC | 1–4 hours |

---

## 14. Deployment Strategy

### 14.1 Deployment Methods per Service Tier

| Tier | Strategy | Rollback Time | Risk Level |
|------|----------|--------------|------------|
| **Tier 0 — Critical** (Wallet, Ledger, Payment, Fraud, Limit) | **Canary** → 5% → 25% → 50% → 100% | < 1 minute (instant traffic shift) | Minimal |
| **Tier 1 — Core** (Account, Transaction, Refund, Bank Integration, Merchant) | **Blue/Green** | < 2 minutes (swap target group) | Low |
| **Tier 2 — Supporting** (Notification, Settlement, KYC, Dispute, Audit Log) | **Rolling update** | < 5 minutes (rollback deployment) | Low |
| **Tier 3 — Non-critical** (Reporting, Reconciliation) | **Rolling update** | < 5 minutes | Very low |

### 14.2 Canary Deployment Flow (Tier 0)

```
CI Build → Staging Deploy → Automated Tests → Approval Gate
    ↓
Canary (5%) ──[5 min observe]──→ SLI check
    ├── Pass → 25% ──[10 min]──→ SLI check
    │            ├── Pass → 50% ──[15 min]──→ SLI check
    │            │            ├── Pass → 100% ✅
    │            │            └── Fail → Auto-rollback 🚨
    │            └── Fail → Auto-rollback 🚨
    └── Fail → Auto-rollback 🚨
```

**Auto-rollback triggers:**
- Error rate > 1% (compared to baseline)
- p99 latency > 2× baseline
- Any 5xx spike > 5 errors/min
- SLO burn rate > 10× during canary window

### 14.3 Blue/Green Deployment Flow (Tier 1)

```
CI Build → Staging → Tests → Approval
    ↓
Deploy to Green (idle) → Smoke tests on Green
    ├── Pass → Switch ALB target group (Blue → Green) → Monitor 15 min
    │            ├── Stable → Drain Blue → Done ✅
    │            └── Issues → Switch back (Green → Blue) 🚨
    └── Fail → Keep Blue active, investigate 🚨
```

### 14.4 Database Migration Strategy

| Migration Type | Approach | Downtime |
|---------------|----------|----------|
| **Additive** (new column, new table, new index) | Online migration, backward compatible | Zero downtime |
| **Destructive** (drop column, rename) | Expand-contract pattern: add new → migrate data → remove old (across 3 deployments) | Zero downtime |
| **Large table migration** | `pg_repack` or online DDL with lock timeout | Zero downtime (< 1s lock) |
| **Data backfill** | Background job, batched (1000 rows/batch), idempotent | Zero downtime |

### 14.5 Feature Flags

| Use Case | Example | Flag Type |
|----------|---------|----------|
| **Release toggle** | `enable-bill-payment-v2` | Boolean, per-environment |
| **Gradual rollout** | `new-fraud-model-percentage` | Percentage (0–100%) |
| **User targeting** | `beta-qr-payment-redesign` | User segment (beta users, merchants) |
| **Kill switch** | `disable-bank-integration-{bank_code}` | Boolean, instant |
| **Ops toggle** | `enable-settlement-manual-mode` | Boolean, admin-only |

**Flag lifecycle**: Create → Enable (staged) → Monitor → Promote to 100% → Remove flag from code (within 30 days).

---

## 15. Incident Response Process

### 15.1 Severity Levels

| Severity | Definition | Examples | Response Time | Resolution Target |
|----------|-----------|----------|--------------|------------------|
| **P0 — Critical** | Complete service outage or data loss risk affecting financial transactions | Payment processing down, ledger inconsistency, security breach | < 5 minutes | < 1 hour |
| **P1 — Major** | Significant degradation of critical path or partial outage | P2P transfers > 5s latency, fraud service unavailable, 50% error rate on payments | < 15 minutes | < 4 hours |
| **P2 — Moderate** | Non-critical service degradation or intermittent issues | Notification delays, reporting service down, settlement delayed | < 1 hour | < 8 hours |
| **P3 — Minor** | Cosmetic issues or non-user-facing problems | Dashboard rendering issue, log pipeline delay, non-critical alert noise | < 4 hours | Next business day |

### 15.2 Incident Response Flow

```
Alert Fired → On-Call Engineer (Acknowledge < 5 min)
    ↓
Triage: Severity Classification (P0/P1/P2/P3)
    ↓
┌─ P0/P1 ──────────────────────────────────────────┐
│  1. Start incident channel (#inc-YYYYMMDD-NNN)    │
│  2. Assign Incident Commander (IC)                │
│  3. Assemble response team (page as needed)       │
│  4. Communicate status to stakeholders (15 min)   │
│  5. Mitigate (rollback, feature flag, failover)   │
│  6. Resolve → Verify → Close                      │
│  7. Post-mortem within 48 hours                   │
└───────────────────────────────────────────────────┘
    ↓
┌─ P2/P3 ──────────────────────────────────────────┐
│  1. Create ticket in issue tracker                │
│  2. Investigate and fix during business hours     │
│  3. Post-mortem if SLO impact > 10% error budget  │
└───────────────────────────────────────────────────┘
```

### 15.3 On-Call Structure

| Rotation | Scope | Schedule | Escalation |
|----------|-------|----------|------------|
| **Primary On-Call** | All services | Weekly rotation, 24/7 | Auto-page secondary after 5 min |
| **Secondary On-Call** | Escalation backup | Weekly rotation, 24/7 | Auto-page Engineering Manager after 15 min |
| **Service Expert** | Deep domain knowledge | As-needed (page by IC) | N/A |
| **Engineering Manager** | Escalation, stakeholder communication | Always available | CTO for P0 lasting > 1 hour |

### 15.4 Communication Protocol

| Audience | Channel | Update Frequency | Content |
|----------|---------|------------------|--------|
| **Response Team** | Slack incident channel | Real-time | Technical details, actions, hypotheses |
| **Engineering Leadership** | Slack + email | Every 30 min (P0), every 1h (P1) | Impact, ETA, mitigation status |
| **Business Stakeholders** | Email + status page | Every 1h (P0), on resolve (P1) | User-facing impact, ETA |
| **Affected Users** | In-app banner + status page | On detection + on resolve | Service status, expected resolution |

### 15.5 Post-Mortem Template

Every P0/P1 incident requires a blameless post-mortem within 48 hours:

| Section | Content |
|---------|--------|
| **Summary** | What happened, duration, user impact |
| **Timeline** | Minute-by-minute: detection → triage → mitigation → resolution |
| **Root Cause** | Technical root cause (5 Whys analysis) |
| **Impact** | Affected users, transactions, SLO burn, financial impact |
| **What Went Well** | Effective responses, tools that helped |
| **What Went Wrong** | Detection gaps, process failures, slow responses |
| **Action Items** | Preventive measures with owners and deadlines |
| **Lessons Learned** | Systemic improvements, process changes |

---

## 16. Cost & FinOps Model

### 16.1 Cost per Transaction

| Cost Component | Per Transaction | Monthly (300K txns/day) | Notes |
|---------------|----------------|------------------------|-------|
| **Compute** (service processing) | ~₫0.3 (~$0.000013) | ~$117 | Based on 35 instances at ~$3,500/month |
| **Database** (read + write) | ~₫0.5 (~$0.000021) | ~$189 | PostgreSQL managed, includes replicas |
| **Kafka** (produce + consume) | ~₫0.15 (~$0.0000063) | ~$57 | 3 brokers, ~3M events/day |
| **Redis** (cache + rate limit) | ~₫0.05 (~$0.0000021) | ~$19 | 3-node cluster |
| **Network** (inter-service) | ~₫0.1 (~$0.0000042) | ~$38 | ~8 internal calls per transaction |
| **External** (SMS, bank API) | ~₫5 (~$0.00021) | ~$1,890 | SMS: ~₫500/OTP, Bank API: ~₫50/call |
| **Total per transaction** | **~₫6.1 (~$0.00026)** | **~$2,310** | Excluding fixed infrastructure |

### 16.2 Cost per Service (Monthly)

| Service | Compute | Database | Redis | Kafka | Total | % of Budget |
|---------|---------|----------|-------|-------|-------|------------|
| Wallet Service | $270 | $800 | $100 | $80 | $1,250 | 10% |
| Ledger Service | $270 | (shared with wallet) | — | $100 | $370 | 3% |
| Payment Service | $270 | $600 | $50 | $80 | $1,000 | 8% |
| Fraud Service | $270 | — | $200 | $30 | $500 | 4% |
| Transaction Service | $180 | $600 | — | $50 | $830 | 7% |
| Bank Integration | $180 | $200 | — | $30 | $410 | 3% |
| Notification Service | $270 | $200 | $50 | $80 | $600 | 5% |
| All other services (10) | $1,200 | $1,600 | $100 | $100 | $3,000 | 25% |
| **Shared infra** (Kafka cluster, Redis, OpenSearch, S3, monitoring) | — | — | — | — | **$4,540** | **35%** |
| **Total** | | | | | **$12,500** | 100% |

### 16.3 Kafka Cost Model

| Component | Configuration | Monthly Cost |
|-----------|-------------|-------------|
| 3 brokers × (4 vCPU, 16 GB) | Reserved instances | $800–$1,200 |
| Storage (50 GB active, 7d retention) | EBS gp3 | $50 |
| Network (cross-AZ replication) | ~100 GB/month inter-AZ | $100 |
| MirrorMaker (Year 2) | Cross-region replication | +$400 |
| **Total Kafka** | | **$950–$1,350** |

**Cost optimization levers:**
- Reduce retention for non-critical topics (3d instead of 7d)
- Compress messages (Snappy/LZ4): ~40% size reduction
- Right-size broker instances based on actual throughput

### 16.4 Storage Growth Cost Projection

| Year | PostgreSQL | Kafka | S3 | Redis | OpenSearch | Total Storage Cost |
|------|-----------|-------|-----|-------|-----------|-------------------|
| Year 1 | ~250 GB ($50/mo) | ~50 GB ($10/mo) | ~400 GB ($10/mo) | ~15 GB (in RAM) | ~50 GB ($25/mo) | ~$95/mo |
| Year 2 (3× growth) | ~750 GB ($150/mo) | ~100 GB ($20/mo) | ~1.2 TB ($30/mo) | ~30 GB | ~150 GB ($75/mo) | ~$275/mo |
| Year 3 (5× growth) | ~1.25 TB ($250/mo) | ~150 GB ($30/mo) | ~3 TB ($50/mo) | ~50 GB | ~250 GB ($125/mo) | ~$455/mo |

**Tiered storage strategy:** Hot (SSD) → Warm (HDD/S3 IA) → Cold (S3 Glacier) with automated lifecycle policies.

### 16.5 Multi-Region Cost Impact

| Category | Single Region | + DR Region | + Active Multi-Region |
|----------|-------------|-------------|----------------------|
| Compute | $3,500 | $3,500 (cold) | +$2,800 (80% warm) |
| Database | $4,000 | +$1,500 (replicas only) | +$3,200 (full standby) |
| Kafka | $1,000 | +$200 (MirrorMaker) | +$800 (full cluster) |
| Network | $350 | +$200 (cross-region) | +$500 (active traffic) |
| **Total** | **$12,500** | **$17,900 (+43%)** | **$23,800 (+90%)** |

### 16.6 Budget Alerts & FinOps Guardrails

| Alert | Threshold | Action |
|-------|-----------|--------|
| Monthly spend > 110% of budget | $13,750 | Email to Eng Manager + FinOps |
| Daily cost spike > 150% of daily average | $625/day | Slack alert, investigate cause |
| Any single service > 120% of allocated budget | Per-service | Service owner investigation |
| Unused reserved instances detected | Any | Right-size or release |
| Database storage > 80% allocated | Per-DB | Scale storage or archive data |

### 16.7 Cost Optimization Strategies

| Strategy | Savings Estimate | Implementation |
|----------|-----------------|----------------|
| **Reserved instances** (1yr commitment) | 30–40% on compute | Commit for stable workloads (17 services) |
| **Spot instances** for batch jobs | 60–70% on settlement, recon | Checkpointing + retry on interruption |
| **Auto-scaling** (HPA) for stateless services | 20–30% during off-peak | Scale down 10 PM–6 AM (Vietnamese time) |
| **Kafka message compression** | ~40% on Kafka storage | Snappy compression (low CPU overhead) |
| **S3 lifecycle policies** | 60–80% on cold storage | Glacier after 90 days for audit/KYC |
| **Database connection pooling** | Smaller DB instances | PgBouncer: fewer connections, smaller instances |
| **Cache hit rate optimization** | Reduce DB read load | Target >90% cache hit rate for balance queries |

---

## 17. Security Architecture Requirements

### 17.1 Secrets Management

| Secret Type | Storage | Rotation | Access Method |
|------------|---------|----------|--------------|
| **Database credentials** | AWS Secrets Manager | Every 90 days (automated) | SDK retrieval at startup + cache |
| **API keys (merchant)** | Database (hashed) | On-demand (merchant-initiated) | Lookup on each request |
| **JWT signing keys** | AWS KMS (RSA-2048) | Every 365 days (planned rotation) | KMS API call for sign/verify |
| **Encryption keys** | AWS KMS (AES-256) | Every 365 days (automatic) | KMS envelope encryption |
| **Service-to-service tokens** | AWS Secrets Manager | Every 30 days | Injected via K8s secrets |
| **Bank API credentials** | AWS Secrets Manager | Per-bank policy | SDK retrieval, never in env vars |
| **SMS/push provider keys** | AWS Secrets Manager | Every 180 days | SDK retrieval |

**Rules:**
- ❌ Never store secrets in environment variables, config files, or code
- ❌ Never log secrets (even partially)
- ✅ All secret access logged to audit trail
- ✅ Alert on unauthorized secret access attempts
- ✅ Break-glass procedure for emergency secret rotation

### 17.2 Key Management & Rotation

| Key | Algorithm | KMS Key Type | Rotation | Usage |
|-----|-----------|-------------|----------|-------|
| **JWT signing** | RS256 (RSA-2048) | Asymmetric | 365 days | Token signing + verification |
| **Data encryption** | AES-256-GCM | Symmetric | 365 days (automatic) | PII encryption, KYC docs |
| **PIN hashing** | Argon2id | N/A (hash) | N/A | PIN verification |
| **Webhook signing** | HMAC-SHA256 | Symmetric | On merchant request | Webhook payload integrity |
| **API key hashing** | SHA-256 + salt | N/A (hash) | N/A | API key verification |

**Key rotation procedure (zero-downtime):**
1. Generate new key version in KMS
2. Begin encrypting with new key, decrypting with both old + new
3. Background job re-encrypts all data with new key (batched)
4. After re-encryption complete, disable old key version
5. After 30-day grace period, schedule old key deletion

### 17.3 mTLS for Service-to-Service Communication

```
┌─────────────┐     mTLS (TLS 1.3)     ┌─────────────┐
│   Service A  │ ◄──────────────────────► │   Service B  │
│              │  Client cert + Server   │              │
│  (has cert)  │  cert verified both ends│  (has cert)  │
└─────────────┘                          └─────────────┘
```

| Component | Implementation |
|-----------|---------------|
| **Certificate Authority** | Private CA (AWS Private CA or cert-manager with Vault) |
| **Certificate issuance** | Auto-issued per service pod (cert-manager) |
| **Certificate lifetime** | 24 hours (short-lived, auto-rotated) |
| **Verification** | Both client and server certificates verified |
| **Scope** | All internal service-to-service HTTP calls |
| **Exception** | Kafka uses SASL/SCRAM (mTLS optional), Redis uses TLS (server-only) |

### 17.4 Network Segmentation

```
┌─────────────────────────────────────────────────────┐
│  VPC (10.0.0.0/16)                                  │
│                                                     │
│  ┌──────────────┐  ┌──────────────┐                 │
│  │ Public Subnet │  │ Public Subnet │ ← ALB, NAT GW │
│  │  (10.0.1.0)  │  │  (10.0.2.0)  │                │
│  └──────────────┘  └──────────────┘                 │
│                                                     │
│  ┌──────────────┐  ┌──────────────┐                 │
│  │ App Subnet   │  │ App Subnet   │ ← Services     │
│  │  (10.0.10.0) │  │  (10.0.11.0) │                │
│  └──────────────┘  └──────────────┘                 │
│                                                     │
│  ┌──────────────┐  ┌──────────────┐                 │
│  │ Data Subnet  │  │ Data Subnet  │ ← RDS, Redis   │
│  │  (10.0.20.0) │  │  (10.0.21.0) │                │
│  └──────────────┘  └──────────────┘                 │
│                                                     │
│  ┌──────────────┐                                   │
│  │  PCI Subnet  │ ← Isolated payment processing    │
│  │  (10.0.30.0) │                                   │
│  └──────────────┘                                   │
└─────────────────────────────────────────────────────┘
```

| Security Group | Ingress Rules | Egress Rules |
|---------------|---------------|-------------|
| **ALB SG** | 443 from internet | App SG on service ports |
| **App SG** | Service ports from ALB SG + App SG (inter-service) | Data SG on DB/Redis ports, internet via NAT |
| **Data SG** | DB ports from App SG only | No internet |
| **PCI SG** | Payment ports from App SG (Payment, Wallet, Ledger only) | Bank API endpoints only (allowlisted IPs) |

### 17.5 Zero Trust Model

| Principle | Implementation |
|-----------|---------------|
| **Never trust, always verify** | Every request authenticated + authorized, even internal |
| **Least privilege** | Services have only permissions they need (IAM roles, RBAC) |
| **Assume breach** | Network segmentation, encryption everywhere, audit logging |
| **Verify explicitly** | JWT validation on every request, mTLS for service calls |
| **Limit blast radius** | Circuit breakers, bulkheads, PCI isolation, feature flags |

### 17.6 PCI Zone / Payment Isolation

| Zone | Services | Network | Data | Access |
|------|----------|---------|------|--------|
| **PCI Zone** | Wallet, Ledger, Payment, Bank Integration | Isolated PCI subnet, strict SG rules | Encrypted at-rest (AES-256, TDE), encrypted in-transit (TLS 1.3) | Only PCI-authorized services, audit logged |
| **General Zone** | All other services | App subnet, standard SG rules | Encrypted at-rest, encrypted in-transit | Standard RBAC |

**PCI compliance controls:**
- No PII/CHD stored outside PCI zone
- All PCI zone access logged and auditable
- Quarterly vulnerability scans (ASV)
- Annual penetration testing
- File integrity monitoring on PCI zone servers

### 17.7 Encryption Matrix

| Layer | Method | Algorithm | Key Management |
|-------|--------|-----------|---------------|
| **In-transit (external)** | TLS 1.3 | ECDHE + AES-256-GCM | AWS ACM (managed certificates) |
| **In-transit (internal)** | mTLS | TLS 1.3 | Private CA (cert-manager) |
| **At-rest (PostgreSQL)** | TDE (Transparent Data Encryption) | AES-256 | AWS KMS (per-database key) |
| **At-rest (S3)** | SSE-KMS | AES-256 | AWS KMS (per-bucket key) |
| **At-rest (Redis)** | Redis encryption at-rest | AES-256 | AWS KMS |
| **Application-level PII** | Field-level encryption | AES-256-GCM (envelope) | AWS KMS (data key per record) |
| **Kafka** | TLS in-transit, encryption at-rest | AES-256 | AWS KMS |
| **Backups** | Encrypted backups | AES-256 | AWS KMS (same as source) |

### 17.8 Security Audit Logging

| Event Category | Examples | Retention | Alert |
|---------------|----------|-----------|-------|
| **Authentication** | Login success/failure, OTP attempts, PIN failures | 7 years | Alert on >5 failures/min per user |
| **Authorization** | RBAC denials, resource access, admin operations | 7 years | Alert on any admin privilege escalation |
| **Data access** | PII queries, bulk data export, report generation | 7 years | Alert on unusual data access patterns |
| **Configuration** | Secret rotation, feature flag changes, rate limit changes | 7 years | Alert on production config changes |
| **Financial** | Ledger entries, balance modifications, refunds, settlements | 10 years | Alert on manual adjustments |
| **Security** | Certificate rotation, key rotation, firewall changes | 7 years | Alert on all security config changes |

---

## 18. Access Control Model

### 18.1 RBAC Model (Role-Based Access Control)

#### User Roles

| Role | Scope | Permissions |
|------|-------|------------|
| **USER** | Own account + wallet | View balance, initiate transfers, view history, manage profile |
| **MERCHANT** | Own merchant account | View dashboard, initiate refunds, manage webhooks, view settlements |
| **MERCHANT_ADMIN** | Merchant org | Manage merchant users, view all org transactions, API key management |

#### Admin Roles

| Role | Scope | Permissions |
|------|-------|------------|
| **SUPPORT_AGENT** | User lookup | View user profile (masked PII), view transactions, create dispute, read-only |
| **SUPPORT_LEAD** | User actions | All SUPPORT_AGENT + freeze/unfreeze accounts, initiate refunds (≤ ₫500,000) |
| **COMPLIANCE_OFFICER** | Compliance | View KYC documents, file SARs, access audit logs, manage sanctions lists |
| **FRAUD_ANALYST** | Risk | View risk scores, configure fraud rules, review flagged transactions |
| **FINANCE_ADMIN** | Financial ops | View ledger, run reconciliation, approve manual adjustments, view settlements |
| **PLATFORM_OPERATOR** | Operations | View dashboards, manage feature flags, trigger settlement runs, view logs |
| **PLATFORM_ADMIN** | Full | All permissions, user/role management, system configuration |
| **SUPER_ADMIN** | Emergency | All permissions + destructive operations (only 2 holders, requires MFA + approval) |

#### RBAC Permission Matrix (Key Resources)

| Resource | USER | MERCHANT | SUPPORT | COMPLIANCE | FINANCE | PLATFORM_ADMIN |
|----------|------|----------|---------|-----------|---------|---------------|
| Own wallet balance | R | — | R | — | R | R |
| Other user wallet | — | — | R (masked) | R | R | R |
| Initiate P2P transfer | CRU | — | — | — | — | — |
| Initiate refund | — | CRU (own) | CRU (≤ limit) | — | CRU | CRUD |
| View audit logs | — | — | — | R | R | R |
| Freeze account | — | — | — | CRU | — | CRUD |
| Configure fraud rules | — | — | — | — | — | CRUD |
| Manage roles | — | — | — | — | — | CRUD |

> **Legend**: C=Create, R=Read, U=Update, D=Delete

### 18.2 ABAC (Attribute-Based Access Control)

ABAC extends RBAC for fine-grained decisions where role alone is insufficient:

| Policy | Attributes Evaluated | Example |
|--------|---------------------|---------|
| **Transaction limit** | KYC tier, transaction type, amount, daily total | Tier 0 user can only transfer ≤ ₫5,000,000/day |
| **Time-based access** | Current time, user's timezone | Settlement runs only between 22:00–06:00 ICT |
| **Geo-restriction** | User IP, device location, account country | Block transactions from sanctioned countries |
| **Device trust** | Device fingerprint, is registered, is trusted | New device requires additional OTP verification |
| **Merchant tier** | Merchant plan, integration age, volume | Enterprise merchants get higher rate limits |
| **Admin action scope** | Admin role, target user's KYC tier, amount | SUPPORT_LEAD can refund ≤ ₫500,000 only |

### 18.3 Service-to-Service Authentication

| Method | Use Case | Implementation |
|--------|----------|---------------|
| **mTLS** | All HTTP service-to-service calls | Cert-manager issued certs, 24h lifetime |
| **Service JWT** | Service identity in request context | Short-lived JWT with `iss=service-name`, `sub=service-id` |
| **Kafka SASL/SCRAM** | Kafka producer/consumer auth | Per-service credentials, rotated every 90 days |
| **IAM roles** | AWS service access (S3, KMS, SQS) | Pod-level IAM roles via IRSA |
| **API Gateway pass-through** | External → internal routing | Gateway validates external JWT, issues internal service context |

**Service identity chain:**

```
External Request (User JWT)
  → API Gateway (validates JWT, extracts user context)
    → Service A (mTLS cert + service JWT + user context)
      → Service B (mTLS cert + service JWT + propagated user context)
        → Database (IAM role or connection credentials)
```

### 18.4 Admin Permission Enforcement

| Control | Implementation |
|---------|---------------|
| **MFA required** | All admin operations require MFA (TOTP or WebAuthn) |
| **Session timeout** | Admin sessions expire after 30 minutes of inactivity |
| **IP allowlist** | Admin API accessible only from office IP ranges or VPN |
| **Dual approval** | Destructive operations (freeze, bulk refund, role change) require 2 admin approvals |
| **Break-glass** | Emergency access with SUPER_ADMIN requires post-incident review within 24h |
| **Privilege escalation** | All role changes logged + alert to security team |

### 18.5 Audit Logs for Admin Actions

Every admin action produces an immutable audit record:

```json
{
  "eventId": "evt_uuid",
  "timestamp": "2026-03-23T09:15:23.456Z",
  "actor": {
    "adminId": "admin_001",
    "role": "SUPPORT_LEAD",
    "ip": "10.0.1.50",
    "mfaVerified": true
  },
  "action": "ACCOUNT_FREEZE",
  "target": {
    "type": "USER_ACCOUNT",
    "id": "usr_xxxxx"
  },
  "reason": "Fraud investigation - case FR-2026-0142",
  "approver": "admin_002",
  "previousState": { "status": "ACTIVE" },
  "newState": { "status": "FROZEN" },
  "metadata": {
    "ticketRef": "JIRA-4521",
    "source": "admin-dashboard"
  }
}
```

### 18.6 Multi-Tenant Isolation Model

| Isolation Level | Implementation | Scope |
|----------------|---------------|-------|
| **Data isolation** | Row-level security (RLS) via `tenant_id` on all tables | All shared databases |
| **API isolation** | API key scoped to merchant, all queries filtered by `merchant_id` | Merchant API |
| **Network isolation** | Shared services, separate API keys (not separate networks per tenant) | Cost-effective for Year 1 |
| **Rate limit isolation** | Per-merchant rate limits (prevents noisy neighbor) | API Gateway + Redis |
| **Logging isolation** | `merchant_id` tag on all logs, filterable in Kibana | Observability |
| **Settlement isolation** | Per-merchant settlement calculations, no cross-merchant visibility | Settlement Service |

**Database RLS example:**

```sql
-- Enable RLS on merchant transactions table
ALTER TABLE merchant_transactions ENABLE ROW LEVEL SECURITY;

-- Policy: merchants can only see their own data
CREATE POLICY merchant_isolation ON merchant_transactions
  USING (merchant_id = current_setting('app.current_merchant_id')::uuid);
```

**Year 2+ evolution:** Dedicated database per high-volume merchant (> 10K txns/day) for performance isolation.

---

## 19. Connection to Phase 03

**Phase 03 — Risk Analysis & Threat Modeling** will use this document to produce:

| Input (from Phase 02) | Output (Phase 03) |
|----------------------|-------------------|
| NFR matrix (17 services × 8 dimensions) | Risk register per dimension |
| SLO definitions + error budget policy | FMEA for SLO-breaching failure modes |
| Integration SLAs | Third-party risk assessment + fallback validation |
| Traffic model | Scalability cliff analysis (at what RPS does architecture break?) |
| Security levels per service | STRIDE threat model per service |
| Capacity plan + cost model | Cost overrun risk + FinOps guardrails |
| Rate limiting strategy | DDoS risk assessment + attack surface analysis |
| DR / Multi-region strategy | Disaster recovery risk analysis + failover validation |
| Data retention & compliance | Compliance risk register + audit readiness |
| Incident response process | Response capability assessment |
| Security architecture | Encryption gap analysis, secret management risk |
| Access control model | Privilege escalation risk, authorization bypass analysis |

---

### 🛑 APPROVAL GATE — 📋 Document Review

> **Review `02-requirements-slos.md` (v3.0)**
>
> This document defines the complete requirements, SLOs, operational strategies, and architecture-level requirements for the payment platform. Please verify:
> - [ ] All 16 user stories cover MVP features with acceptance criteria
> - [ ] NFR matrix covers all 17 services × 8 dimensions
> - [ ] Traffic model is consistent with Phase 01 scale estimates
> - [ ] SLOs are realistic (99.99% for wallet/payment, 99.9% for batch services)
> - [ ] Error budget policy defines clear escalation thresholds
> - [ ] Observability covers RED + USE metrics, log schema, tracing, and PromQL queries
> - [ ] Cost & FinOps model provides per-transaction and per-service cost breakdown
> - [ ] Security architecture defines mTLS, PCI zone, encryption matrix, and secrets management
> - [ ] Access control model covers RBAC, ABAC, service auth, and multi-tenant isolation
> - [ ] DR / Multi-region strategy has clear RTO/RPO targets
> - [ ] Rate limiting covers all API tiers with DDoS protection
> - [ ] Data retention policies meet regulatory requirements
> - [ ] Backup strategy covers all databases with verification schedule
> - [ ] Deployment strategy uses canary for critical services
> - [ ] Incident response process defines severity levels and SLAs
> - [ ] Capacity plan is reasonable for Year 1 (~35 instances, ~$8K–16K/month)
> - [ ] ADRs document the two key architecture decisions
> - [ ] Integration SLAs have proper fallback strategies
>
> Reply **APPROVE** to proceed to Phase 03 (Risk Analysis & Threat Modeling), or provide feedback.

