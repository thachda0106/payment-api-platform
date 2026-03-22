# Phase 01 — Product Discovery

## MoMo-like Payment API Platform

> **Document Status**: Draft v4.0 — Pending Architecture Review  
> **Last Updated**: 2026-03-22  
> **Audience**: Engineering Leadership, Product, Architecture Review Board

---

## 1. Product Vision

> **Build a production-grade payment infrastructure platform that provides the complete backend for digital wallet operations, payment processing, merchant services, financial ledger, settlement, reconciliation, fraud management, and developer APIs — equivalent to the combined backend capabilities of MoMo, Stripe, and PayPal.**

This is not a simple wallet app. This is a **Payment Infrastructure Platform** that encompasses 8 interconnected subsystems:

| Subsystem | Responsibility |
|-----------|---------------|
| **Wallet System** | Balance management, top-up, withdrawal, holds, multi-currency readiness |
| **Payment Processing** | P2P transfers, QR payments, bill payments, merchant transactions |
| **Merchant Platform** | Onboarding, API credentials, payment page, webhooks, settlement |
| **Financial Ledger** | Double-entry accounting, journal entries, trial balance, audit trail |
| **Settlement & Reconciliation** | End-of-day settlement, bank reconciliation, exception handling |
| **Fraud & Risk System** | Real-time risk scoring, rule engine, velocity checks, AML screening |
| **Developer API Platform** | REST APIs, sandbox environment, documentation, SDKs, rate limiting |
| **Admin & Operations Platform** | Back-office tools, dispute management, compliance reporting, monitoring |

The platform serves as the **financial backbone** that mobile apps, merchant integrations, and partner systems connect to.

---

## 2. Platform Scope

### What We Are Building

```
┌──────────────────────────────────────────────────────────────┐
│                      CLIENT LAYER                            │
│   Mobile App · Merchant Portal · Admin Dashboard             │
│   Partner APIs · Developer Sandbox                           │
├──────────────────────────────────────────────────────────────┤
│                     API GATEWAY                              │
│   Auth · Rate Limit · Routing · Request Logging              │
├──────────────────────────────────────────────────────────────┤
│                   CORE SERVICES                              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐            │
│  │ Account │ │ Wallet  │ │ Payment │ │ Merchant│            │
│  │ Service │ │ Service │ │ Service │ │ Service │            │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘            │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐            │
│  │ Ledger  │ │ Settle- │ │  Fraud  │ │  Limit  │            │
│  │ Service │ │  ment   │ │ Service │ │ Service │            │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘            │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐            │
│  │ Transac-│ │ Refund  │ │ Dispute │ │ Notifi- │            │
│  │  tion   │ │ Service │ │ Service │ │ cation  │            │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘            │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐            │
│  │   KYC   │ │Reporting│ │Audit Log│ │  Bank   │            │
│  │ Service │ │ Service │ │ Service │ │ Integr. │            │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘            │
├──────────────────────────────────────────────────────────────┤
│                  DATA & MESSAGING                            │
│   PostgreSQL · Redis · Kafka · OpenSearch · S3               │
├──────────────────────────────────────────────────────────────┤
│                EXTERNAL INTEGRATIONS                         │
│   Banks · NAPAS · Card Networks · Telcos · Utilities         │
└──────────────────────────────────────────────────────────────┘
```

### What We Are NOT Building (in MVP)

- Consumer-facing mobile app (we build the APIs it consumes)
- Card issuing or acquiring (we integrate with existing processors)
- Lending / credit products
- Insurance products
- Cryptocurrency or blockchain features

---

## 3. User Personas

### Persona 1: End User (Consumer)

| Attribute | Detail |
|-----------|--------|
| **Who** | Vietnamese consumer, 18–45, smartphone user |
| **Goals** | Send money, pay bills, buy airtime, scan QR to pay merchants, manage wallet |
| **Needs** | Instant transfers, 24/7 availability, clear transaction history, transaction receipts |
| **Pain Points** | Slow bank transfers, cash dependency, fragmented payment options, unclear fees |
| **Success Metrics** | Complete a payment in < 3 seconds, 24/7 availability, zero unexplained balance discrepancies |

### Persona 2: Merchant

| Attribute | Detail |
|-----------|--------|
| **Who** | Small to large businesses accepting digital payments (coffee shops to e-commerce) |
| **Goals** | Accept payments (QR/online), view transaction history, reconcile daily, manage refunds |
| **Needs** | Easy integration, predictable settlement schedule, detailed transaction reports, refund tools |
| **Pain Points** | High fees, complex integration, delayed settlement, no reconciliation tools |
| **Success Metrics** | Integrate in < 1 day, T+1 settlement, < 1% payment failure rate, self-service reporting |

### Persona 3: Developer / Partner

| Attribute | Detail |
|-----------|--------|
| **Who** | Third-party developers integrating payment capabilities into their apps |
| **Goals** | Integrate payment APIs quickly, test in sandbox, receive webhooks reliably |
| **Needs** | Well-documented REST APIs, sandbox with test data, webhook retry, SDKs |
| **Pain Points** | Poor documentation, no sandbox, inconsistent error formats, no webhook debugging tools |
| **Success Metrics** | Working sandbox integration in 30 minutes, 99.9% webhook delivery rate |

### Persona 4: Platform Admin / Ops

| Attribute | Detail |
|-----------|--------|
| **Who** | Internal operations team managing day-to-day platform operations |
| **Goals** | Monitor system health, manage users/merchants, handle escalations, view dashboards |
| **Needs** | Real-time dashboards, user/merchant management tools, configuration management |
| **Pain Points** | No visibility into system state, manual processes, slow escalation paths |
| **Success Metrics** | Real-time visibility, < 5 min to find any transaction, < 15 min incident response |

### Persona 5: Financial Partner (Bank / Telco)

| Attribute | Detail |
|-----------|--------|
| **Who** | Partner banks (Vietcombank, VietinBank), telecom providers, utility companies |
| **Goals** | Secure API integration for fund transfers, settlement reconciliation, status callbacks |
| **Needs** | ISO 8583 or REST API, SFTP for batch files, reconciliation reports, SLA monitoring |
| **Pain Points** | Inconsistent APIs, no webhook support, manual batch processing, reconciliation gaps |
| **Success Metrics** | Automated integration, < 0.01% reconciliation exceptions, real-time status updates |

### Persona 6: Risk / Fraud Analyst

| Attribute | Detail |
|-----------|--------|
| **Who** | Internal risk team members analyzing suspicious activity and managing fraud rules |
| **Goals** | Detect fraud in real-time, investigate suspicious accounts, tune risk rules, reduce false positives |
| **Needs** | Risk scoring dashboard, transaction pattern analysis, rule engine configuration, case management |
| **Pain Points** | No real-time alerts, manual investigation, too many false positives, no velocity tracking |
| **Success Metrics** | < 0.01% fraud loss rate, < 5% false positive rate, < 2 min median investigation time per case |

### Persona 7: Compliance Officer

| Attribute | Detail |
|-----------|--------|
| **Who** | Legal and compliance team ensuring regulatory adherence |
| **Goals** | Ensure KYC/AML compliance, file regulatory reports, manage sanctions screening, audit readiness |
| **Needs** | KYC completion tracking, SAR filing tools, transaction threshold monitoring, audit trail access |
| **Pain Points** | Manual compliance checks, missed reporting deadlines, no audit trail, scattered data |
| **Success Metrics** | 100% KYC completion, 0 missed SARs, pass external audits with no findings, < 24h for any data request |

### Persona 8: Finance / Accounting Team

| Attribute | Detail |
|-----------|--------|
| **Who** | Internal finance team managing the platform's financial operations |
| **Goals** | Reconcile all accounts daily, produce financial reports, manage settlement, track revenue |
| **Needs** | Double-entry ledger access, reconciliation dashboards, settlement reports, P&L data |
| **Pain Points** | Ledger imbalances, manual reconciliation, delayed settlement reports, no drill-down capability |
| **Success Metrics** | Zero ledger imbalance, daily reconciliation < 30 min, T+0 settlement visibility, accurate revenue reports |

### Persona 9: Customer Support / Dispute Team

| Attribute | Detail |
|-----------|--------|
| **Who** | Support agents handling customer inquiries, disputes, and escalations |
| **Goals** | Resolve customer issues quickly, process refunds, manage disputes, track cases |
| **Needs** | Full transaction search, user profile view, refund/reversal tools, dispute workflow, escalation paths |
| **Pain Points** | Cannot find transactions, no refund tools, no dispute tracking, customer data across multiple systems |
| **Success Metrics** | < 5 min avg resolution for simple queries, < 48h dispute resolution, < 2% escalation rate |

---

## 4. Core User Journeys

### Journey 1: User Registration & KYC

```
User downloads app → Phone verification (OTP) → Basic KYC (name, DOB, ID)
→ E-wallet created → Set transaction PIN → Ready to transact
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Registration completes in < 2 minutes |
| **Error Paths** | Invalid OTP, KYC rejection, duplicate phone number, rate-limited OTP requests |
| **Latency** | OTP delivery < 5s, KYC submission response < 3s |
| **Services** | Auth Service, KYC Service, Wallet Service, Notification Service, Limit Service |

### Journey 2: Wallet Top-Up

```
User selects "Top Up" → Choose source (linked bank account)
→ Enter amount → Authenticate (PIN) → Initiate bank debit
→ [Pending] Bank processes debit → Confirmation callback received
→ Ledger: DEBIT bank_funding, CREDIT user_wallet
→ Wallet balance updated → Notification sent
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Wallet credited within 5–30 seconds depending on bank |
| **Error Paths** | Insufficient bank funds, bank timeout (>30s), amount exceeds limits, bank account not linked, bank system down |
| **Latency** | API response < 500ms (async processing), settlement varies by bank |
| **Services** | Wallet Service, Payment Service, Ledger Service, Limit Service, Fraud Service, Notification Service, Bank Integration |

### Journey 3: P2P Transfer

```
User selects "Transfer" → Enter recipient (phone number)
→ Recipient validated → Enter amount + note → Authenticate (PIN)
→ Fraud check (real-time risk scoring)
→ Ledger: DEBIT sender_wallet, CREDIT receiver_wallet (atomic)
→ Sender balance decremented, Receiver balance incremented
→ Both parties notified (push + in-app)
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Instant transfer, < 500ms end-to-end |
| **Error Paths** | Insufficient balance, recipient not found, daily limit exceeded, sender/receiver account frozen, fraud rule triggered (held for review) |
| **Latency** | < 200ms p50, < 500ms p99 |
| **Services** | Wallet Service, Payment Service, Ledger Service, Transaction Service, Limit Service, Fraud Service, Notification Service |

### Journey 4: QR Payment (at Store)

```
User scans merchant QR (contains merchant_id + optional amount)
→ Display payment details → Enter amount if not pre-filled
→ Authenticate (PIN) → Fraud check
→ Ledger: DEBIT user_wallet, CREDIT merchant_wallet (available at settlement)
→ Merchant receives "pending" balance (settled T+1)
→ Both parties receive confirmation
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Scan to confirmation in < 3 seconds |
| **Error Paths** | Invalid QR, expired QR, insufficient balance, merchant account inactive, merchant daily limit, QR amount mismatch |
| **Latency** | < 1s p50, < 3s p95 |
| **Services** | Merchant Service, Wallet Service, Payment Service, Ledger Service, Fraud Service, Settlement Service, Notification Service |

### Journey 5: Bill Payment

```
User selects bill category (electric, water, internet, phone)
→ Enter bill code → Fetch bill from provider API
→ Display bill details (amount, due date, account info)
→ Confirm → Authenticate (PIN) → Fraud check
→ Ledger: DEBIT user_wallet, CREDIT bill_provider_escrow
→ Forward payment to provider → Provider confirms
→ Receipt generated → Notification sent
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Bill paid and confirmed in < 5 seconds |
| **Error Paths** | Invalid bill code, provider API timeout, bill already paid, insufficient balance, provider rejects payment |
| **Latency** | Bill query < 3s, payment + confirmation < 5s |
| **Services** | Payment Service, Wallet Service, Ledger Service, Limit Service, Fraud Service, Notification Service, Provider Integration |

### Journey 6: Merchant Payment (Online Checkout)

```
Customer on e-commerce site → Select "Pay with PayWallet"
→ Redirect to payment gateway page → User logs in → Authorize payment
→ Fraud check → Ledger: DEBIT user_wallet, CREDIT merchant_pending
→ Redirect to merchant with payment result
→ Webhook notification to merchant server (with retry)
→ Merchant fulfills order
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Complete checkout in < 10 seconds |
| **Error Paths** | Session expired, insufficient balance, payment timeout, double submission (idempotency key), webhook delivery failure |
| **Latency** | Payment processing < 2s, webhook delivery < 5s |
| **Services** | Merchant Service, Payment Service, Wallet Service, Ledger Service, Fraud Service, Transaction Service, Notification Service |

### Journey 7: Merchant Onboarding

```
Merchant signs up → Submit business information + documents
→ KYC/KYB verification (automated + manual review)
→ Compliance check (sanctions, PEP screening)
→ API credentials generated (sandbox) → Sandbox access granted
→ Integration testing in sandbox → Go-live application
→ Compliance approval → Production credentials issued
→ Settlement account linked
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Sandbox in 10 minutes, production in 1–3 business days |
| **Error Paths** | Document rejection, compliance flag, incomplete information, failed KYB, sanctions match |
| **Latency** | Sandbox: real-time; Production: 1–3 business days (manual review) |
| **Services** | Merchant Service, KYC Service, Compliance Service, Auth Service, Notification Service |

### Journey 8: Wallet Withdrawal (Wallet → Bank)

```
User selects "Withdraw" → Choose linked bank account
→ Enter amount → Validate: amount ≤ available_balance AND within limits
→ Authenticate (PIN) → Fraud check
→ Ledger: DEBIT user_wallet, CREDIT bank_payout_pending
→ Place hold on wallet balance → Initiate bank credit transfer
→ Bank confirms credit → Ledger: DEBIT bank_payout_pending, CREDIT bank_payout_settled
→ Release hold → Notification sent
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Bank credit within 1–15 minutes (NAPAS fast transfer) or T+1 (standard) |
| **Error Paths** | Insufficient balance, invalid bank account, bank rejection, amount below minimum, withdrawal limit exceeded, bank system down |
| **Latency** | API response < 500ms (async), bank settlement 1 min – 24 hours |
| **Services** | Wallet Service, Payment Service, Ledger Service, Limit Service, Fraud Service, Notification Service, Bank Integration |

### Journey 9: Refund / Reversal

```
Merchant initiates refund (via API or dashboard) OR System auto-reverses (timeout)
→ Validate: original transaction exists, refundable, amount ≤ original
→ Idempotency check (refund_id)
→ Ledger: DEBIT merchant_wallet, CREDIT user_wallet (full or partial)
→ Update original transaction status → Notification to both parties
→ Settlement adjustment (deducted from next merchant settlement)
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Refund processed in < 2 seconds, wallet credit instant |
| **Error Paths** | Original transaction not found, already refunded, amount exceeds original, merchant insufficient balance, refund window expired (>90 days) |
| **Latency** | < 500ms processing, instant wallet credit |
| **Services** | Refund Service, Wallet Service, Ledger Service, Transaction Service, Settlement Service, Notification Service |

### Journey 10: Dispute / Chargeback

```
User files dispute (claim: didn't receive goods, unauthorized transaction, wrong amount)
→ Dispute created with evidence → Merchant notified (has 7 days to respond)
→ Merchant submits evidence OR accepts → Dispute reviewed (manual or automated)
→ Decision: User wins → Refund issued + merchant debited
   Decision: Merchant wins → Dispute closed, user notified
→ If unresolved → Escalation to arbitration
→ Audit trail recorded for all actions
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Dispute resolved in < 48 hours |
| **Error Paths** | Merchant doesn't respond (auto-resolve in user's favor), insufficient evidence, duplicate dispute, dispute window expired (>180 days) |
| **Latency** | Dispute creation < 1s, resolution 24–168 hours |
| **Services** | Dispute Service, Refund Service, Wallet Service, Ledger Service, Transaction Service, Notification Service, Audit Log Service |

### Journey 11: Settlement (End-of-Day Merchant Settlement)

```
Scheduled job triggers at EOD (e.g., 23:00 ICT)
→ Aggregate all merchant transactions for the day
→ Calculate: gross_amount - fees - refunds - chargebacks = net_settlement
→ Generate settlement report per merchant
→ Ledger: DEBIT merchant_pending, CREDIT merchant_settled
→ Initiate bank transfer to merchant's settlement account
→ Bank confirms → Mark settlement as completed
→ Merchant notified with settlement report
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Settlement calculated by 00:00, bank transfers initiated by 02:00, completed by 09:00 next day (T+1) |
| **Error Paths** | Bank transfer failure (retry), settlement amount mismatch, merchant bank account invalid, below minimum settlement threshold |
| **Latency** | Batch processing: < 1 hour for calculation, bank transfer: depends on bank |
| **Services** | Settlement Service, Ledger Service, Transaction Service, Reporting Service, Bank Integration, Notification Service |

### Journey 12: Reconciliation (Bank vs Ledger vs Wallet)

```
Scheduled job triggers post-settlement
→ Fetch bank statement (API or SFTP)
→ Match: bank_transactions ↔ ledger_entries ↔ wallet_movements
→ Identify: matched, unmatched (in our system not in bank), unmatched (in bank not in our system)
→ Generate reconciliation report
→ Auto-resolve known patterns (timing differences)
→ Flag exceptions for manual review
→ Finance team resolves exceptions → Adjusting entries if needed
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | > 99.9% auto-match rate, reconciliation completed within 2 hours of bank statement receipt |
| **Error Paths** | Bank statement delayed, format mismatch, duplicate transactions, missing entries, amount discrepancies |
| **Latency** | Batch process: 1–2 hours, exception resolution: < 24 hours |
| **Services** | Reconciliation Service, Ledger Service, Settlement Service, Reporting Service, Bank Integration |

### Journey 13: Account Freeze / Unfreeze

```
Trigger: Fraud rule fires OR Compliance officer manual action OR Law enforcement request
→ Account freeze initiated → Freeze type determined (full freeze, debit-only freeze, credit-only freeze)
→ Ledger: All pending transactions halted
→ User notified: "Account restricted, contact support"
→ Investigation by Risk/Compliance team
→ Decision: Unfreeze (clear) OR Permanent block (close account + return funds)
→ Audit trail recorded with reason, actor, timestamp
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Freeze effective in < 1 second, investigation resolved in < 24 hours |
| **Error Paths** | Freeze during active transaction (must complete or rollback), incorrect freeze (false positive), regulatory hold (indefinite) |
| **Latency** | Freeze: < 100ms, investigation: 1–72 hours |
| **Services** | Fraud Service, Wallet Service, Limit Service, Compliance Service, Audit Log Service, Notification Service |

### Journey 14: Transaction Limits & KYC Tier Upgrade

```
User attempts transaction → Limit Service checks: amount, daily total, monthly total
→ If within limits → Proceed
→ If exceeds limits → Reject with clear message + prompt to upgrade KYC tier
→ User submits additional KYC documents (ID photo, selfie, proof of address)
→ eKYC verification (automated + manual review)
→ KYC tier upgraded → New limits applied immediately
→ User notified of new limits
```

| Aspect | Detail |
|--------|--------|
| **Happy Path** | Limit check < 50ms, KYC upgrade approval 5 min – 24 hours |
| **Error Paths** | KYC document rejection, verification failure, suspicious pattern (KYC upgrade denied), document forgery detected |
| **Latency** | Limit check: < 50ms per transaction, KYC review: minutes to hours |
| **Services** | Limit Service, KYC Service, Wallet Service, Fraud Service, Notification Service, Audit Log Service |

---

## 5. MVP Scope

> **Note**: We intentionally include a large MVP to gain learning across the full payment architecture. All listed features are in scope.

### Core Money Movement

| Feature | Priority | Notes |
|---------|----------|-------|
| User registration + OTP verification | P0 | Phone-based, SMS OTP |
| E-Wallet (create, balance, history) | P0 | Core wallet operations |
| Wallet top-up (bank link, virtual account) | P0 | Bank integration required |
| P2P transfer (phone-to-phone) | P0 | Core money movement |
| QR code payment (static + dynamic) | P0 | In-store payments |
| Transaction history + receipts | P0 | User + merchant views |
| Refund / reversal | P1 | Full + partial refunds |

### Merchant Platform

| Feature | Priority | Notes |
|---------|----------|-------|
| Merchant onboarding (basic) | P0 | API keys, sandbox |
| Merchant payment API (online checkout) | P0 | Payment page + webhooks |

### Compliance & Risk

| Feature | Priority | Notes |
|---------|----------|-------|
| PIN authentication | P0 | Transaction security |
| KYC (basic: ID photo + selfie) | P1 | Compliance requirement |
| Rate limiting + fraud rules (basic) | P1 | Basic protection |

### Operations & Admin

| Feature | Priority | Notes |
|---------|----------|-------|
| Admin dashboard (basic) | P1 | User, merchant, txn management |

### Notifications & Integrations

| Feature | Priority | Notes |
|---------|----------|-------|
| Bill payment (electricity, water, telecom) | P1 | Provider integration |
| Notification service (push + SMS) | P1 | Transaction alerts |

---

## 6. Financial System Components

The platform is decomposed into 17 core services, each owning a distinct financial responsibility:

### Core Financial Services

| # | Service | Responsibility |
|---|---------|---------------|
| 1 | **Account Service** | User and merchant account lifecycle: registration, profile management, KYC tier tracking, account status (active, suspended, frozen, closed). Owns `accounts` table. Single source of truth for account identity and status. All other services reference `account_id` from this service. |
| 2 | **Wallet Service** | Balance management (available, pending, frozen), holds, top-up, withdrawal. Owns the `wallets` table. Enforces balance invariants: `balance >= 0` at all times. Supports operations: `credit`, `debit`, `hold`, `release_hold`. Each account has one or more wallets (default VND wallet). |
| 3 | **Ledger Service** | Double-entry accounting engine. Every money movement produces a journal entry: `DEBIT account_a, CREDIT account_b`. Maintains chart of accounts, trial balance, and ensures `sum(debits) == sum(credits)` invariant at all times. Source of truth for all financial state. |
| 4 | **Payment Service** | Orchestrates payment flows (P2P, QR, bill pay, merchant). Handles payment lifecycle: `INITIATED → PROCESSING → COMPLETED/FAILED`. Coordinates between Wallet, Ledger, Fraud, and Limit services. Manages idempotency keys. |
| 5 | **Transaction Service** | Immutable record of all financial transactions. Stores: `transaction_id, type, amount, currency, status, parties, metadata, timestamps`. Provides search and history APIs. Never mutates past records — only appends status changes. |
| 6 | **Settlement Service** | End-of-day batch processing for merchant settlements. Aggregates daily merchant transactions, deducts fees and refunds, calculates net settlement, and initiates bank transfers. Produces settlement reports. |
| 7 | **Reconciliation Service** | Three-way matching: bank statements ↔ ledger entries ↔ wallet movements. Identifies matched, unmatched, and discrepant records. Auto-resolves known patterns (timing delays). Flags exceptions for manual resolution. |
| 8 | **Refund Service** | Processes full and partial refunds. Validates original transaction, enforces refund window (90 days), handles idempotency. Coordinates with Wallet (credit customer) and Settlement (adjust merchant settlement). |
| 9 | **Dispute Service** | Manages dispute lifecycle: `OPENED → EVIDENCE_REQUESTED → UNDER_REVIEW → RESOLVED`. Tracks deadlines, evidence submission, decisions, and escalations. Integrates with Refund Service for resolution. |

### Risk & Compliance Services

| # | Service | Responsibility |
|---|---------|---------------|
| 10 | **Fraud / Risk Service** | Real-time transaction risk scoring. Evaluates: velocity rules, amount thresholds, device fingerprint, geo-anomaly, behavioral patterns. Returns `ALLOW / REVIEW / BLOCK` decisions in < 50ms. Manages fraud rules engine. |
| 11 | **Limit / Compliance Service** | Enforces transaction limits based on KYC tier. Tracks daily / monthly / per-transaction limits. Manages account freeze / unfreeze. AML screening (sanctions, PEP lists). Controls transaction threshold monitoring. |

### Platform Services

| # | Service | Responsibility |
|---|---------|---------------|
| 12 | **Notification Service** | Multi-channel delivery: SMS, push notification, email, in-app. Template management. Delivery tracking and retry. Batch notifications for settlement reports. |
| 13 | **Merchant Service** | Merchant lifecycle: onboarding, KYB verification, API key management, webhook configuration, settlement account setup. Provides merchant dashboard API. |
| 14 | **KYC Service** | Identity verification: document upload, OCR/liveness check (via eKYC vendor), manual review workflow. Manages KYC tier transitions and document storage. |
| 15 | **Reporting Service** | Generates financial reports (daily summary, monthly P&L, settlement reports), compliance reports (SAR, threshold monitoring), and operational reports (transaction volume, success rates). Supports scheduled and ad-hoc reporting. |
| 16 | **Audit Log Service** | Immutable append-only log of all significant system actions: admin operations, configuration changes, account modifications, dispute decisions, security events. Provides search and export for compliance audits. Retention: 7 years minimum. |

### Integration Services

| # | Service | Responsibility |
|---|---------|---------------|
| 17 | **Bank Integration Service** | Abstraction layer over all external bank connections. Handles: NAPAS interbank transfers, individual bank APIs (Vietcombank, VietinBank, BIDV, Techcombank), card network processing (via acquirer). Manages connection pooling, circuit breakers, retry logic, protocol translation (ISO 8583 ↔ REST), bank statement retrieval (API/SFTP), and callback processing. Isolates all bank-specific logic from core services. |

### Service Interaction Overview

```
User Request
     │
     ▼
 [API Gateway] ─── Auth, Rate Limit, Logging
     │
     ▼
 [Account Service] ─── Account lookup + status check
     │
     ▼
 [Payment Service] ─── Orchestrates the flow
     │
     ├──► [Fraud Service] ─── Risk check (< 50ms)
     ├──► [Limit Service] ─── Limit check (< 50ms)
     ├──► [Wallet Service] ─── Balance check + debit/credit
     ├──► [Ledger Service] ─── Double-entry journal entry
     ├──► [Transaction Service] ─── Record transaction
     ├──► [Bank Integration Service] ─── External bank ops (async)
     └──► [Notification Service] ─── Notify parties (async)

 [Settlement Service] ─── EOD batch ──► [Ledger] + [Bank Integration Service]
 [Reconciliation Service] ─── Post-settlement ──► [Ledger] + [Bank Integration Service]
```

---

## 7. Money Flow Overview

### 7.1 Double-Entry Accounting Principle

Every money movement in the system produces exactly **two ledger entries** that must balance:

```
DEBIT  Account_A   Amount
CREDIT Account_B   Amount
```

**Sum of all debits must always equal sum of all credits** (fundamental invariant).

### 7.2 Chart of Accounts (Simplified)

> **Key insight**: User and merchant wallet balances are **Liabilities** to the platform, not Assets. The platform owes these funds to the account holders. The platform's **Asset** is the pooled bank account where the actual money sits.

| Account Type | Account Name | Normal Balance | Purpose |
|-------------|-------------|----------------|---------|
| **Asset** | `bank_pooled_account` | Debit | Actual money held in bank (master pooled account) |
| **Asset** | `bank_payout_pending` | Debit | Money in transit to external bank accounts |
| **Asset** | `bank_receivable` | Debit | Money expected from banks (top-ups in transit) |
| **Liability** | `user_wallet:{user_id}` | Credit | Platform owes this balance to the user |
| **Liability** | `merchant_wallet:{merchant_id}` | Credit | Platform owes this settled balance to the merchant |
| **Liability** | `merchant_pending:{merchant_id}` | Credit | Unsettled merchant funds (not yet available) |
| **Liability** | `escrow:{txn_id}` | Credit | Funds held in escrow (bill pay, disputes, pending delivery) |
| **Liability** | `chargeback_reserve:{merchant_id}` | Credit | Reserve held from merchant for potential chargebacks |
| **Liability** | `settlement_clearing` | Credit | Temporary account used during EOD settlement batch |
| **Revenue** | `platform_fee` | Credit | Transaction fees earned by platform |
| **Revenue** | `interest_income` | Credit | Interest earned on pooled funds |
| **Expense** | `bank_fee` | Debit | Bank transfer and processing charges |
| **Expense** | `chargeback_loss` | Debit | Losses from chargebacks absorbed by platform |

**Fundamental invariant**: `sum(Assets) = sum(Liabilities) + sum(Revenue) - sum(Expenses)`

### 7.3 Money Flows

> **Accounting convention reminder**: Wallet accounts are **Liabilities** (credit normal balance). `CREDIT wallet` = increase balance (platform owes more). `DEBIT wallet` = decrease balance (platform owes less). Asset accounts like `bank_pooled_account` have debit normal balance.

#### Top-Up (Bank → Wallet)

```
Bank debits user's external bank account, money arrives in our pooled bank account

DEBIT  bank_pooled_account      100,000 VND    (asset ↑: our pooled bank balance increases)
CREDIT user_wallet:{uid}        100,000 VND    (liability ↑: we now owe user more)
```

User sees: `Available Balance: +100,000 VND`

#### P2P Transfer

```
DEBIT  user_wallet:{sender_id}      50,000 VND    (liability ↓: we owe sender less)
CREDIT user_wallet:{receiver_id}    50,000 VND    (liability ↑: we owe receiver more)
```

Zero-sum between liabilities. No money enters or leaves the platform. `bank_pooled_account` is unchanged.

#### Merchant Payment (with fee)

```
User pays 100,000 VND to merchant (platform fee: 1.5% = 1,500 VND)

DEBIT  user_wallet:{uid}                100,000 VND    (liability ↓: user owes less)
CREDIT merchant_pending:{mid}            98,500 VND    (liability ↑: we owe merchant, pending settlement)
CREDIT platform_fee                       1,500 VND    (revenue ↑: platform earnings)
```

The merchant sees `Pending Balance: +98,500 VND` (not yet available for withdrawal until settlement).

#### Refund (Full)

```
Refund 100,000 VND back to user

DEBIT  merchant_pending:{mid}      100,000 VND    (liability ↓: reduce what we owe merchant)
CREDIT user_wallet:{uid}           100,000 VND    (liability ↑: restore what we owe user)

Fee handling (platform absorbs or merchant absorbs — configurable):
DEBIT  platform_fee                  1,500 VND    (revenue ↓: reverse fee)
CREDIT merchant_pending:{mid}       1,500 VND    (liability ↑: restore merchant amount)
```

#### Withdrawal (Wallet → Bank)

```
Phase 1: Initiate — hold user funds
DEBIT  user_wallet:{uid}           200,000 VND    (liability ↓: we owe user less)
CREDIT bank_payout_pending         200,000 VND    (asset ↑: money in transit to user's bank)

Phase 2: Bank confirms credit to user's external account
DEBIT  bank_payout_pending         200,000 VND    (asset ↓: transit cleared)
CREDIT bank_pooled_account         200,000 VND    (asset ↓: actual money left our pooled account)
```

User sees: `Available Balance: -200,000 VND` immediately at Phase 1.

#### Settlement (EOD Merchant Payout)

```
Step 1: Aggregate and calculate net settlement for Merchant M
  Gross sales:      5,000,000 VND
  Fees:              -75,000 VND
  Refunds:          -200,000 VND
  Net settlement:  4,725,000 VND

Step 2: Move from pending to settled (via clearing account)
DEBIT  merchant_pending:{mid}    4,725,000 VND    (liability ↓: clear pending)
CREDIT settlement_clearing       4,725,000 VND    (liability ↑: temp clearing)

DEBIT  settlement_clearing       4,725,000 VND    (liability ↓: clear temp)
CREDIT merchant_wallet:{mid}     4,725,000 VND    (liability ↑: now available)

Step 3: Merchant requests bank withdrawal
DEBIT  merchant_wallet:{mid}     4,725,000 VND    (liability ↓: we owe less)
CREDIT bank_payout_pending       4,725,000 VND    (asset ↑: in transit)
  → Bank transfer initiated via Bank Integration Service
```

#### Dispute Hold

```
User files dispute on 500,000 VND merchant payment

Step 1: Hold disputed amount from merchant's pending balance
DEBIT  merchant_pending:{mid}    500,000 VND    (liability ↓: reduce merchant pending)
CREDIT escrow:{dispute_id}       500,000 VND    (liability ↑: held in escrow)

Step 2a: User wins dispute — refund from escrow
DEBIT  escrow:{dispute_id}       500,000 VND    (liability ↓: release escrow)
CREDIT user_wallet:{uid}         500,000 VND    (liability ↑: credit user)

Step 2b: Merchant wins dispute — return to merchant
DEBIT  escrow:{dispute_id}       500,000 VND    (liability ↓: release escrow)
CREDIT merchant_pending:{mid}    500,000 VND    (liability ↑: restore merchant)
```

#### Chargeback

```
Card network or bank initiates chargeback for 300,000 VND

Step 1: Debit merchant from chargeback reserve (or pending balance)
DEBIT  chargeback_reserve:{mid}  300,000 VND    (liability ↓: use reserve)
CREDIT bank_pooled_account       300,000 VND    (asset ↓: money returned to bank/network)

If merchant has insufficient reserve — platform absorbs loss:
DEBIT  chargeback_loss           300,000 VND    (expense ↑: platform loss)
CREDIT bank_pooled_account       300,000 VND    (asset ↓: money left platform)
```

#### Escrow (Bill Payment)

```
User pays 200,000 VND electricity bill

Step 1: Debit user, hold in escrow until provider confirms
DEBIT  user_wallet:{uid}         200,000 VND    (liability ↓: user pays)
CREDIT escrow:{bill_txn_id}      200,000 VND    (liability ↑: held pending confirmation)

Step 2: Provider confirms payment
DEBIT  escrow:{bill_txn_id}      200,000 VND    (liability ↓: release escrow)
CREDIT bank_pooled_account       200,000 VND    (asset ↓: money sent to provider via bank)

Step 2-fail: Provider rejects — refund to user
DEBIT  escrow:{bill_txn_id}      200,000 VND    (liability ↓: release escrow)
CREDIT user_wallet:{uid}         200,000 VND    (liability ↑: money returned to user)
```

#### Platform Fee Settlement

```
Monthly platform fee settlement to platform's operating account

Accumulated fees for the month: 150,000,000 VND

DEBIT  platform_fee             150,000,000 VND    (revenue ↓: fees recognized)
CREDIT bank_pooled_account      150,000,000 VND    (asset ↓: transfer to ops account)
  → Bank transfer to platform's own operating bank account
```

#### Manual Adjustment (Admin Correction)

```
Finance team discovers reconciliation discrepancy: user charged 10,000 VND extra

Corrective credit to user:
DEBIT  chargeback_loss            10,000 VND    (expense ↑: platform absorbs cost)
CREDIT user_wallet:{uid}          10,000 VND    (liability ↑: correct user balance)

Audit record: { reason: "Reconciliation discrepancy #REC-2026-0142",
                 approved_by: "finance_admin_001", ticket: "JIRA-4521" }
```

> **All manual adjustments require**: dual approval, audit log entry, ticket reference, and reason code.

#### Account Freeze (Funds Hold)

```
Fraud system triggers freeze on user with 2,000,000 VND balance

Step 1: Move all available balance to frozen state (within Wallet Service)
  wallet.available_balance: 2,000,000 → 0
  wallet.frozen_balance:            0 → 2,000,000
  (No ledger entry — this is a wallet-internal status change, total balance unchanged)

Step 2: Ledger records hold for audit
DEBIT  user_wallet:{uid}         2,000,000 VND    (liability ↓: reduce available)
CREDIT escrow:{freeze_id}        2,000,000 VND    (liability ↑: held in escrow)

Step 3a: Investigation clears — unfreeze
DEBIT  escrow:{freeze_id}        2,000,000 VND    (liability ↓: release escrow)
CREDIT user_wallet:{uid}          2,000,000 VND    (liability ↑: restore user)

Step 3b: Confirmed fraud — account closed, funds returned via bank
DEBIT  escrow:{freeze_id}        2,000,000 VND    (liability ↓: release escrow)
CREDIT bank_payout_pending       2,000,000 VND    (asset ↑: return to user's bank)
```

### 7.4 Key Concepts

| Concept | Explanation |
|---------|-------------|
| **Available Balance** | Funds user can spend right now. Excludes holds, frozen, and pending transactions. |
| **Pending Balance** | Funds from transactions not yet settled (merchant payments before EOD settlement). |
| **Frozen Balance** | Funds held due to fraud investigation or compliance action. Cannot be spent or withdrawn. |
| **Hold / Escrow** | Temporary reservation on balance. Used during: withdrawal processing, dispute investigation, bill payment confirmation, delivery escrow. |
| **Settlement Delay** | Time between transaction and funds becoming available to merchant. T+1 (next business day) by default. |
| **Chargeback Reserve** | Percentage of merchant settlements held back (e.g., 5%) to cover potential chargebacks. Released after chargeback window (180 days). |
| **Reconciliation** | Verifying that bank records, internal ledger, and wallet balances all agree. Runs daily after settlement. Three-way match: bank ↔ ledger ↔ wallet. |
| **Idempotency** | Every transaction has a unique `idempotency_key`. Retrying the same request produces the same result, preventing double-charges. |
| **Journal Entry** | The atomic unit of the ledger. Always balanced (total debits = total credits). Immutable once written — corrections create new compensating entries. |
| **Compensating Entry** | A new journal entry that reverses a previous entry. Used for refunds, adjustments, and corrections. The original entry is never deleted or modified. |

---

## 8. KPI & Scale Estimates

### Year 1 Scale Model

| Metric | Value | Derivation |
|--------|-------|-----------|
| Registered Users | 1,000,000 | Growth target |
| Monthly Active Users (MAU) | 300,000 | 30% of registered |
| Daily Active Users (DAU) | 100,000 | 33% of MAU |
| Avg transactions per DAU per day | 3 | Top-up + 1-2 payments |
| **Daily Transactions** | **300,000** | 100K DAU × 3 txns |
| Avg TPS (over 12h active window) | ~7 TPS | 300K / (12 × 3600) |
| **Peak TPS** (10× avg, lunch/evening rush) | **70 TPS** | 7 × 10 |
| **Design Capacity TPS** | **500 TPS** | 7× headroom over peak |
| API Calls per Transaction | ~5 | Auth + fraud + limit + wallet + notification |
| **Daily API Calls** | **1,500,000** | 300K txns × 5 calls |
| Kafka Events per Transaction | ~4 | Payment, ledger, notification, audit |
| **Daily Kafka Events** | **1,200,000** | 300K × 4 |
| Active Merchants | 5,000 | Year 1 target |
| Monthly GMV | $50M | 300K MAU × ~$167 monthly spend |
| **Storage Growth** | **~200 GB/year** | Transactions + events + audit logs |

### Technical KPIs

| KPI | p50 | p95 | p99 | SLO |
|-----|-----|-----|-----|-----|
| P2P Transfer Latency | < 200ms | < 500ms | < 1s | 99th < 1s |
| QR Payment Latency | < 500ms | < 2s | < 3s | 99th < 3s |
| API Response (general) | < 100ms | < 300ms | < 500ms | 99th < 500ms |
| Fraud Check Latency | < 20ms | < 50ms | < 100ms | 99th < 100ms |
| Limit Check Latency | < 10ms | < 30ms | < 50ms | 99th < 50ms |

### Availability & Reliability KPIs

| KPI | Target |
|-----|--------|
| API Availability | 99.95% (≤ 22 min downtime/month) |
| Payment Success Rate | > 99.5% |
| Webhook Delivery Rate | > 99.9% (with retry) |
| Data Durability | 99.999999999% (11 nines) |
| RTO (Recovery Time Objective) | < 15 minutes |
| RPO (Recovery Point Objective) | < 1 minute |

### Business KPIs

| KPI | Year 1 Target |
|-----|---------------|
| Registered Users | 1,000,000 |
| MAU | 300,000 |
| Monthly Transactions | 9,000,000 |
| Monthly GMV | $50,000,000 |
| Merchant Partners | 5,000 |
| Payment Failure Rate | < 0.5% |
| Customer Complaints per 1K txns | < 1 |
| Avg Revenue Per User (ARPU) | $2/month |
| Fraud Loss Rate | < 0.01% |

---

## 9. Compliance Requirements

| Regulation | Applicability | Impact on Architecture |
|------------|--------------|----------------------|
| **PCI-DSS** | Card data handling | Tokenization, encryption at rest (AES-256), network segmentation, quarterly ASV scans, annual audit |
| **SBV (State Bank of Vietnam)** | E-wallet license | KYC requirements, transaction limits, capital requirements, quarterly reporting |
| **Vietnamese Cybersecurity Law** | Data localization | All user data must reside in Vietnam; primary data center must be in-country |
| **Circular 39/2014** | E-payment regulations | Transaction limits per tier, mandatory record keeping, real-time reporting |
| **AML/CFT** | Anti-money laundering | Transaction monitoring engine, sanctions screening, SAR filing, record retention 5+ years |
| **PDPA (Vietnam)** | Personal data protection | Consent management, data minimization, breach notification within 72 hours, right to deletion (where not conflicting with financial record keeping) |

### Transaction Limits (SBV Regulated)

| KYC Tier | Per Transaction | Daily Limit | Monthly Limit |
|----------|----------------|-------------|---------------|
| Non-KYC (Tier 0) | 5,000,000 VND | 10,000,000 VND | 20,000,000 VND |
| Basic KYC (Tier 1) | 20,000,000 VND | 50,000,000 VND | 100,000,000 VND |
| Full KYC (Tier 2) | 100,000,000 VND | 200,000,000 VND | 500,000,000 VND |
| Merchant | Per agreement | Per agreement | Per agreement |

### Compliance Architecture Implications

| Requirement | Architectural Decision |
|-------------|----------------------|
| KYC tiering | Limit Service enforces per-tier limits; KYC Service manages tier transitions |
| AML screening | Fraud Service integrates sanctions/PEP lists; Transaction Service monitors thresholds |
| Audit trail | Audit Log Service: immutable, append-only, 7-year retention, tamper-evident |
| Data localization | All databases, caches, and message brokers deployed in Vietnam region |
| PCI-DSS | Card data never stored; tokenized via payment processor; network segmentation via VPC |
| Breach notification | Audit Log triggers alert pipeline; incident response < 72 hours |

---

## 10. External Integrations

### Financial & Banking

| System | Purpose | Protocol | Priority |
|--------|---------|----------|----------|
| **NAPAS** | Interbank transfers, ATM network | ISO 8583 / REST API | P0 |
| **Partner Banks** (Vietcombank, VietinBank, BIDV, Techcombank) | Wallet top-up, withdrawal, settlement | Bank-specific API (REST/SOAP) | P0 |
| **Card Networks** (Visa, Mastercard) | Card-based top-up, international payments | Payment processor API (via acquirer) | P1 |
| **Sanctions Lists** (OFAC, UN, local) | AML/CFT screening | Batch download + API | P1 |

### Service Providers

| System | Purpose | Protocol | Priority |
|--------|---------|----------|----------|
| **Telecom Providers** (Viettel, Mobifone, Vinaphone) | Airtime top-up, mobile bill | REST API | P1 |
| **Utility Companies** (EVN, water, internet) | Bill queries and payment | REST API / SFTP batch | P1 |
| **eKYC Provider** | Identity verification (OCR, liveness, ID verification) | REST API | P0 |
| **SMS Gateway** (Twilio / local provider) | OTP delivery, transaction alerts | REST API | P0 |
| **Push Notification** (Firebase FCM / APNs) | Real-time push alerts | SDK | P1 |
| **Email Service** (SendGrid / SES) | Receipts, settlement reports, alerts | REST API / SMTP | P1 |

### Infrastructure & Operations

| System | Purpose | Protocol | Priority |
|--------|---------|----------|----------|
| **Object Storage** (S3 / MinIO) | KYC documents, receipts, invoices, reports | S3-compatible API | P0 |
| **Data Warehouse** (BigQuery / Redshift / ClickHouse) | Analytics, reporting, business intelligence | SQL / ETL pipeline | P1 |
| **Monitoring & Alerting** (Prometheus, Grafana, PagerDuty) | System health, SLO tracking, incident alerting | Metrics API + webhook | P0 |
| **Logging** (ELK / Loki) | Centralized log aggregation and search | Syslog / HTTP | P0 |
| **Distributed Tracing** (Jaeger / Tempo) | Request tracing across services | OpenTelemetry | P1 |
| **Accounting System / ERP** | Financial reporting, regulatory filings | REST API / file export | P2 |

---

## 11. Team & Organization

| Team | Size | Responsibilities |
|------|------|-----------------|
| **Wallet Team** | 3–4 engineers | Wallet Service, balance management, top-up, withdrawal. Owns `wallets`, `wallet_transactions` tables. Key invariant: balance correctness. |
| **Payments Team** | 4–5 engineers | Payment Service, Transaction Service, P2P, QR, bill pay. Owns payment orchestration and transaction records. Coordinates with Wallet and Ledger. |
| **Merchant Platform Team** | 3–4 engineers | Merchant Service, onboarding, API credentials, payment page, webhook system. Owns merchant lifecycle and developer experience. |
| **Ledger & Finance Team** | 2–3 engineers | Ledger Service, Settlement Service, Reconciliation Service, Reporting Service. Owns double-entry accounting, EOD settlement, and bank reconciliation. Critical financial accuracy. |
| **Risk & Fraud Team** | 2–3 engineers | Fraud Service, Limit Service, Dispute Service. Owns risk scoring engine, rule configuration, AML screening, and dispute lifecycle. |
| **Infrastructure / SRE Team** | 2–3 engineers | Cloud infrastructure, CI/CD, monitoring, alerting, database administration. Owns availability SLOs and incident response. |
| **Security & Compliance Team** | 1–2 engineers | KYC Service, Audit Log Service, security architecture, PCI-DSS compliance, penetration testing. Owns security posture and regulatory compliance. |
| **Data Team** | 1–2 engineers | Data warehouse, ETL pipelines, analytics, reporting dashboards. Owns business intelligence and data governance. |
| **Total** | **18–26 engineers** | |

### Team Dependencies

```
                    ┌─────────────┐
                    │   Gateway   │
                    │   (SRE)     │
                    └──────┬──────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
   ┌─────────────┐  ┌──────────┐  ┌──────────────┐
   │   Wallet    │  │ Payments │  │  Merchant    │
   │   Team      │  │  Team    │  │  Platform    │
   └──────┬──────┘  └────┬─────┘  └──────┬───────┘
          │               │               │
          └───────┬───────┘               │
                  ▼                       │
          ┌──────────────┐               │
          │ Ledger &     │◄──────────────┘
          │ Finance Team │
          └──────┬───────┘
                 │
          ┌──────▼───────┐
          │ Risk & Fraud │
          │    Team      │
          └──────────────┘
                 │
    ┌────────────┼────────────┐
    ▼            ▼            ▼
┌────────┐ ┌─────────┐ ┌──────────┐
│Security│ │  SRE    │ │  Data    │
│& Compl.│ │  Team   │ │  Team    │
└────────┘ └─────────┘ └──────────┘
```

---

## 12. Key Risks

### Business & Operational Risks

| # | Risk | Probability | Impact | Mitigation |
|---|------|-------------|--------|------------|
| 1 | **Ledger imbalance / financial discrepancy** | Medium | Critical | Double-entry enforcement, reconciliation service, automated balance checks, immutable journal |
| 2 | **Bank integration instability** | High | High | Circuit breakers, retry with backoff, fallback banks, async processing with outbox pattern |
| 3 | **Fraud / unauthorized transactions** | Medium | Critical | Real-time risk scoring, velocity checks, device fingerprinting, manual review queue |
| 4 | **Regulatory non-compliance** | Medium | Critical | Compliance-first design, limit enforcement, audit trail, regular compliance audits |
| 5 | **Data breach / security incident** | Low | Critical | Encryption at rest + in transit, WAF, network segmentation, security audits, incident response plan |
| 6 | **Performance under peak load** | Medium | High | Load testing, auto-scaling, write-path optimization, read replicas, caching |
| 7 | **Settlement/reconciliation failure** | Medium | High | Idempotent operations, automated reconciliation, exception alerting, manual override tools |
| 8 | **Single point of failure** | Medium | High | Multi-AZ deployment, database replication, stateless services, health checks |
| 9 | **Scope creep (MVP too large)** | High | Medium | Strict prioritization (P0 first), iterative delivery, tier-based implementation |
| 10 | **Team scaling & knowledge silos** | Medium | Medium | Documentation-first, service ownership model, cross-team code reviews |

### Distributed System Risks

| # | Risk | Description | Mitigation |
|---|------|-------------|------------|
| 11 | **Double spending** | User spends same balance concurrently via two parallel requests | Optimistic locking with `version` column on wallet row. `UPDATE wallets SET balance = balance - amount WHERE id = ? AND version = ? AND balance >= amount`. If 0 rows affected → reject. Database row-level lock ensures atomicity. |
| 12 | **Idempotency failure** | Same payment processed twice due to retry without unique key | Every mutation requires `idempotency_key`. Store in `idempotency_keys` table with response. On duplicate key → return cached response. TTL: 24 hours. |
| 13 | **Event duplication** | Kafka consumer processes same event twice after rebalance or crash | Inbox pattern: store `event_id` in `inbox_events` table within same DB transaction as business logic. Deduplicate by primary key on `event_id`. |
| 14 | **Wallet ↔ Ledger mismatch** | Wallet balance diverges from ledger balance due to partial failure | Wallet debit/credit and ledger journal entry must be in same database transaction (if co-located) or choreographed via saga with compensation. Daily reconciliation job: `sum(ledger entries) == wallet.balance` per account. Alert on mismatch > 0. |
| 15 | **Settlement mismatch** | Settlement amount doesn't match sum of individual transactions | Settlement Service recalculates from raw transaction data. Compare with running totals. Three-way match: `transactions sum == settlement amount == bank transfer amount`. |
| 16 | **Reconciliation backlog** | Unresolved reconciliation exceptions pile up faster than team resolves | Auto-resolve known patterns (timing delays < 24h). Escalation rules: > 3 days → auto-alert manager. Weekly reconciliation review meeting. Dashboard with aging metrics. |
| 17 | **Split-brain during partition** | Network partition between services causes inconsistent state | Strong consistency for wallet (single writer, serializable isolation). Saga compensation for distributed flows. No distributed transactions — use outbox + inbox pattern. |
| 18 | **Clock skew across services** | Inconsistent timestamps cause ordering issues in ledger | Use server-generated timestamps (database `now()`). NTP synchronization. Combine `timestamp + sequence_number` for ordering. Ledger entries use database-assigned monotonic IDs. |

---

## 13. Data Volume Estimation (Year 1)

| Data Category | Per Transaction | Daily (300K txns) | Monthly | Yearly | Storage (Year 1) |
|---------------|----------------|-------------------|---------|--------|-------------------|
| **Transactions** | 1 row (~500B) | 300K rows | 9M rows | 108M rows | ~54 GB |
| **Ledger Entries** | 2–3 rows (~300B each) | 600K–900K rows | 18M–27M rows | 216M–324M rows | ~65–97 GB |
| **Audit Log Events** | 1–2 rows (~400B) | 300K–600K rows | 9M–18M rows | 108M–216M rows | ~43–86 GB |
| **Kafka Events** | 4 events (~500B each) | 1.2M events | 36M events | 432M events | ~216 GB (raw, pre-compaction) |
| **Notifications** | 1–2 records (~200B) | 300K–600K records | 9M–18M records | 108M–216M records | ~22–43 GB |
| **KYC Documents** | ~1MB per user (avg) | — | ~30K new users/month | ~360K new users/year | ~360 GB (object storage) |
| **Settlement Reports** | — | 5K reports/day | 150K/month | 1.8M/year | ~5 GB |
| **Reconciliation Data** | — | 1 batch/day | 30/month | 365/year | ~2 GB |

### Storage Summary

| Category | Year 1 Estimate |
|----------|-----------------|
| PostgreSQL (all services) | ~250–350 GB |
| Kafka (7-day retention) | ~50 GB active |
| Object Storage (S3) | ~400 GB |
| Redis (cache/sessions) | ~10–20 GB |
| OpenSearch (search index) | ~50 GB |
| **Total** | **~760–870 GB** |

> **Growth factor**: Plan for 3× growth in Year 2 as user base scales.

---

## 14. Security & Encryption Architecture

### Encryption at Rest

| Data Store | Encryption | Key Management |
|-----------|-----------|----------------|
| PostgreSQL | AES-256-CBC (TDE or filesystem-level) | KMS-managed keys, auto-rotation every 90 days |
| Redis | AES-256 (if supported) or encrypted EBS/disk | KMS-managed |
| Kafka | Broker-level encryption (encrypted volumes) | KMS-managed |
| S3 / Object Storage | SSE-S3 or SSE-KMS (AES-256) | KMS-managed, per-bucket keys |
| Backups | Encrypted at rest using separate backup key | KMS-managed, cross-region key replication |

### Encryption in Transit

| Channel | Protocol | Minimum Version |
|---------|----------|-----------------|
| Client → API Gateway | TLS 1.3 | Enforced, HSTS enabled |
| Service ↔ Service | mTLS (mutual TLS) | Certificate-based identity |
| Service → Database | TLS 1.2+ | Certificate verification |
| Service → Kafka | SASL_SSL | TLS 1.2+ with SASL authentication |
| Service → Redis | TLS 1.2+ | Certificate verification |

### Key Management (KMS / HSM)

| Component | Purpose |
|-----------|---------|
| **Cloud KMS** (AWS KMS / GCP CMEK) | Master key management, envelope encryption for data keys, automatic rotation |
| **HSM** (CloudHSM or equivalent) | JWT signing keys (RS256), PIN encryption keys, PCI-DSS compliant key storage |
| **Key hierarchy** | Master Key (HSM) → Data Encryption Keys (KMS) → per-service/per-table encryption |
| **Key rotation** | Automated: 90-day rotation for data keys, annual rotation for master keys |

### Access Control (RBAC + ABAC)

| Model | Scope | Example |
|-------|-------|---------|
| **RBAC** | API-level authorization | Roles: `user`, `merchant`, `admin`, `support`, `finance`, `compliance`. Permissions matrix: `role × resource × action (CRUD)` |
| **ABAC** | Fine-grained data access | Attributes: `account_kyc_tier`, `transaction_amount`, `device_trust_score`, `ip_geo_location`. Policies: "Allow withdrawal if `kyc_tier >= 1 AND amount <= daily_limit`" |
| **Service-level** | Inter-service auth | mTLS certificates + service identity. Service A can only call APIs it's authorized for. |
| **Admin RBAC** | Back-office tools | Separation of duties: `support` can view transactions but not issue refunds > $1K. `finance` can view ledger but not modify accounts. Dual approval for sensitive operations. |

### Secrets Management

| Aspect | Approach |
|--------|----------|
| **Storage** | HashiCorp Vault or AWS Secrets Manager. Never in code, env files, or config files. |
| **Injection** | Injected at runtime via sidecar or init container. Environment variables or mounted files. |
| **Rotation** | Database passwords: 30-day auto-rotation. API keys: on-demand rotation. JWT signing keys: 90-day rotation. |
| **Audit** | All secret access logged. Alert on unusual access patterns (access from unknown service, bulk reads). |
| **Blast radius** | Per-service credentials. Compromised service credential only accesses that service's database. |

### PII Protection

| PII Field | Storage Rule | Access Control |
|-----------|-------------|----------------|
| Phone number | Stored hashed (lookup) + encrypted (display) | Support + compliance only |
| National ID | Encrypted at rest, field-level encryption | Compliance only, audit logged |
| Full name | Encrypted at field level | Support, compliance |
| Bank account number | Tokenized (token maps to encrypted value) | Wallet Service only |
| PIN | Hashed (Argon2id), never stored in plaintext | Auth Service only, never logged |
| KYC documents | Encrypted in object storage, access-logged | KYC Service + compliance |

---

## 15. Event-Driven Architecture Overview

### Kafka Topic Catalog

| Topic | Partition Key | Partitions | Retention | Publishers | Consumers |
|-------|--------------|------------|-----------|------------|-----------|
| `payment.events` | `payment_id` | 12 | 7 days | Payment Service | Transaction, Notification, Reporting, Fraud |
| `wallet.events` | `wallet_id` | 12 | 7 days | Wallet Service | Ledger, Notification, Reporting |
| `ledger.entries` | `journal_id` | 6 | 30 days | Ledger Service | Reconciliation, Reporting |
| `merchant.events` | `merchant_id` | 6 | 7 days | Merchant Service | Settlement, Notification |
| `settlement.events` | `merchant_id` | 6 | 30 days | Settlement Service | Reporting, Notification, Reconciliation |
| `fraud.alerts` | `account_id` | 6 | 30 days | Fraud Service | Limit Service, Notification, Audit Log |
| `kyc.events` | `account_id` | 3 | 30 days | KYC Service | Account Service, Limit Service |
| `audit.events` | `entity_id` | 6 | 90 days | All Services | Audit Log Service |
| `notification.commands` | `recipient_id` | 6 | 3 days | All Services | Notification Service |
| `*.dlq` (per topic) | original key | 3 | 90 days | Inbox processor | Manual replay / DLQ handler |

### Outbox / Inbox Pattern

```
PRODUCER SIDE (Outbox):
┌─────────────────────────────────────────┐
│ Within same DB transaction:             │
│   1. Business write (e.g., debit wallet)│
│   2. Insert into outbox_events table    │
│      { id, topic, key, payload, status } │
└─────────────────────────────────────────┘
         ↓ (polling every 5s or CDC)
┌─────────────────────────────────────────┐
│ Outbox Processor:                       │
│   1. SELECT * FROM outbox_events        │
│      WHERE status = 'PENDING' LIMIT 100 │
│   2. Publish to Kafka                   │
│   3. UPDATE status = 'PUBLISHED'        │
└─────────────────────────────────────────┘

CONSUMER SIDE (Inbox):
┌─────────────────────────────────────────┐
│ Inbox Consumer:                         │
│   1. Receive event from Kafka           │
│   2. INSERT INTO inbox_events (event_id)│
│      -- deduplicate by PK on event_id   │
│   3. Process business logic             │
│   4. UPDATE inbox status = 'PROCESSED'  │
│   5. Commit Kafka offset                │
└─────────────────────────────────────────┘
    ↓ (on failure)
┌─────────────────────────────────────────┐
│ Retry + DLQ:                            │
│   1. Retry with exponential backoff     │
│      (1s, 2s, 4s, 8s, 16s — max 5)     │
│   2. After max retries → move to DLQ   │
│   3. Alert on DLQ (PagerDuty/Slack)     │
│   4. Manual replay from DLQ             │
└─────────────────────────────────────────┘
```

### Saga Orchestration (Merchant Payment Example)

```
Payment Service (Orchestrator):
  Step 1: Fraud Check      → Fraud Service (sync, < 50ms)
    → ALLOW: continue | BLOCK: reject | REVIEW: hold
  Step 2: Limit Check      → Limit Service (sync, < 50ms)
    → PASS: continue | FAIL: reject with limit info
  Step 3: Debit User       → Wallet Service (sync, strong consistency)
    → SUCCESS: continue | FAIL: reject (insufficient balance)
  Step 4: Credit Merchant  → Wallet Service (sync, atomic with Step 3 if same DB)
    → SUCCESS: continue | FAIL: compensate Step 3
  Step 5: Record Ledger    → Ledger Service (sync or outbox event)
  Step 6: Record Txn       → Transaction Service (outbox event)
  Step 7: Notify           → Notification Service (async via Kafka)

Compensation (if Step 4 fails):
  Compensate Step 3: Credit back user wallet
  Record: FAILED transaction with reason
```

---

## 16. Consistency Model

| Service | Consistency Model | Rationale |
|---------|------------------|-----------|
| **Account Service** | Strong (serializable) | Account status changes must be immediately visible. `SELECT FOR UPDATE` on account row. |
| **Wallet Service** | Strong (serializable) | Balance operations require strict serialization to prevent double spending. Single-writer pattern with row-level locks. |
| **Ledger Service** | Strong (serializable) | Double-entry invariant (`debits == credits`) must never be violated. Journal entries are append-only within serializable transactions. |
| **Payment Service** | Strong for state transitions | Payment state machine transitions are atomic. Idempotency key checked under serializable isolation. |
| **Transaction Service** | Strong for writes, eventual for reads | Write path: serializable (immutable append). Read path: read replicas with < 1s lag acceptable. |
| **Settlement Service** | Eventual (batch) | EOD batch processing tolerates minutes of delay. Reconciliation catches errors next cycle. |
| **Reconciliation Service** | Eventual (batch) | Runs post-settlement. Stale data within 24h is acceptable. |
| **Fraud / Risk Service** | Eventual for rules, strong for decisions | Rule updates propagate eventually. Individual risk decisions are synchronous and deterministic. |
| **Limit Service** | Strong for checks, eventual for counter updates | Limit checks are synchronous. Daily/monthly counter aggregation may have brief lag (< 1s via Redis). |
| **Notification Service** | Eventual | Notifications can be delayed seconds without business impact. At-least-once delivery. |
| **Reporting Service** | Eventual (minutes to hours) | Reports generated from read replicas or data warehouse. Staleness up to 1 hour acceptable. |
| **Audit Log Service** | Eventually durable (append-only) | Writes are append-only and never modified. May batch-flush with < 5s delay. |
| **Merchant Service** | Strong for onboarding, eventual for status | Merchant creation is serializable. Status queries can serve from cache (< 1min TTL). |
| **KYC Service** | Eventual | KYC verification is asynchronous by nature. Status changes propagated via events. |
| **Bank Integration Service** | Eventual (async) | Bank operations are inherently async. Callbacks/polling for status. Outbox pattern for reliability. |

### Consistency Rules

1. **Wallet + Ledger must always agree** — both updated in same transaction boundary or via saga with compensation
2. **Write path is always strong consistency** — read path can be eventual for non-financial queries
3. **No distributed transactions** — use saga orchestration with compensation for cross-service operations
4. **Idempotency on all mutations** — every write operation is safe to retry

---

## 17. Transaction State Machine

```
                              ┌──────────────┐
                              │  INITIATED   │
                              │  (created)   │
                              └──────┬───────┘
                                     │
                          ┌──────────▼──────────┐
                          │      PENDING        │
                          │  (processing/hold)  │
                          └──────┬────────┬─────┘
                                 │        │
                    ┌────────────▼─┐   ┌──▼──────────┐
                    │   SUCCESS    │   │   FAILED     │
                    │ (completed)  │   │ (rejected)   │
                    └───┬─────┬───┘   └──────────────┘
                        │     │
               ┌────────▼─┐  ┌▼───────────┐
               │ REFUNDED  │  │  DISPUTED  │
               │(returned) │  │(under inv.)│
               └───────────┘  └──────┬─────┘
                                     │
                              ┌──────▼───────┐
                              │   REVERSED   │
                              │  (chargeback │
                              │   resolved)  │
                              └──────────────┘
```

### State Transition Rules

| From | To | Trigger | Conditions |
|------|----|---------|------------|
| `INITIATED` | `PENDING` | Payment processing started | Fraud check passed, limit check passed |
| `INITIATED` | `FAILED` | Pre-check failed | Fraud blocked, limit exceeded, invalid request |
| `PENDING` | `SUCCESS` | All steps completed | Wallet debited, ledger recorded, merchant credited |
| `PENDING` | `FAILED` | Processing failed | Insufficient balance, bank timeout, wallet error |
| `SUCCESS` | `REFUNDED` | Refund issued | Merchant or auto-refund, within 90-day window |
| `SUCCESS` | `DISPUTED` | User files dispute | Valid dispute reason, within 180-day window |
| `DISPUTED` | `SUCCESS` | Merchant wins dispute | Evidence reviewed, dispute rejected |
| `DISPUTED` | `REVERSED` | User wins dispute | Chargeback issued, funds returned to user |
| `DISPUTED` | `REFUNDED` | Merchant accepts dispute | Merchant agrees, refund issued |

### Invariants

- **Immutable history**: State transitions are append-only. A transaction record is never updated — new status entries are appended to `transaction_status_history`.
- **No backward transitions**: A `SUCCESS` transaction cannot go back to `PENDING`. Only forward transitions are allowed.
- **Terminal states**: `FAILED`, `REFUNDED`, and `REVERSED` are terminal — no further transitions.
- **Single active state**: A transaction has exactly one current state at any time.
- **Audit trail**: Every state transition records: `new_state, actor, reason, timestamp, metadata`.

---

## 19. Idempotency Strategy

### Why Idempotency Is Critical

In a payment system, network failures, timeouts, and retries are inevitable. Without idempotency, a single payment can be processed multiple times — resulting in double charges, ledger imbalances, and financial loss.

### Idempotency Key Design

```
Header: X-Idempotency-Key: <client-generated UUID v4>

Example: X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

### Implementation

```
┌─────────────────────────────────────────────────────┐
│ Request arrives with idempotency_key                │
│                                                     │
│ 1. SELECT * FROM idempotency_keys                   │
│    WHERE key = ? AND endpoint = ?                   │
│                                                     │
│ 2a. Key EXISTS and status = 'COMPLETED'             │
│     → Return cached response (HTTP 200 + body)      │
│     → No business logic executed                    │
│                                                     │
│ 2b. Key EXISTS and status = 'PROCESSING'            │
│     → Return HTTP 409 Conflict                      │
│     → Client should wait and retry                  │
│                                                     │
│ 2c. Key NOT FOUND                                   │
│     → INSERT { key, endpoint, status: 'PROCESSING' }│
│     → Execute business logic                        │
│     → UPDATE status = 'COMPLETED', response = ?     │
│     → Return response                               │
│                                                     │
│ 2d. Business logic FAILS                            │
│     → UPDATE status = 'FAILED', error = ?           │
│     → Client can retry with SAME key                │
│     (failed entries are retryable)                  │
└─────────────────────────────────────────────────────┘
```

### Idempotency Key Table

```sql
CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    endpoint        VARCHAR(255) NOT NULL,
    method          VARCHAR(10) NOT NULL,        -- POST, PUT
    status          VARCHAR(20) NOT NULL,         -- PROCESSING, COMPLETED, FAILED
    request_hash    VARCHAR(64),                  -- SHA-256 of request body
    response_code   INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    expires_at      TIMESTAMPTZ DEFAULT now() + INTERVAL '24 hours',

    UNIQUE(idempotency_key, endpoint)
);

-- Auto-cleanup expired entries
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);
```

### Rules

| Rule | Detail |
|------|--------|
| **Key scope** | Per endpoint + per API key (merchant). Same key on different endpoints = different operations. |
| **Key ownership** | Client generates the key (UUID v4). Server never generates idempotency keys. |
| **TTL** | 24 hours. After expiry, same key can be reused (but shouldn't). |
| **Request body check** | Hash request body. If same key but different body → reject with HTTP 422 (misuse). |
| **Retryable failures** | `FAILED` status entries can be retried with the same key. `COMPLETED` entries always return cached response. |
| **Which endpoints** | All mutation endpoints (POST, PUT, DELETE). GET requests are naturally idempotent. |

### Per-Service Idempotency

| Service | Idempotency Key | Scope |
|---------|----------------|-------|
| Payment Service | `payment_idempotency_key` (client-provided) | Per payment request |
| Wallet Service | `wallet_operation_id` (service-generated) | Per debit/credit operation |
| Ledger Service | `journal_entry_id` (service-generated) | Per journal entry |
| Refund Service | `refund_id` (derived from `payment_id + refund_sequence`) | Per refund |
| Settlement Service | `settlement_id` (date + merchant_id) | Per settlement batch |
| Notification Service | `notification_id` (event_id + channel) | Per notification delivery |

---

## 20. Wallet vs Ledger Consistency Model

### The Problem

The Wallet Service and Ledger Service both track financial state, but from different perspectives:

| Aspect | Wallet Service | Ledger Service |
|--------|---------------|----------------|
| **Purpose** | Real-time balance for user-facing operations | Accounting truth for financial reporting |
| **Data model** | `wallet_id → { available, pending, frozen }` | `journal_entries → { debit_account, credit_account, amount }` |
| **Optimized for** | Low-latency reads (< 10ms balance check) | Auditability, trial balance, reconciliation |
| **Update pattern** | Increment/decrement balance atomically | Append-only journal entries |
| **Query pattern** | "What is my balance?" (single row lookup) | "Show all transactions for account X" (range scan) |

### Consistency Invariant

```
For every account at any point in time:
  wallet.available_balance + wallet.pending_balance + wallet.frozen_balance
  ==
  sum(ledger credits for this account) - sum(ledger debits for this account)
```

If this invariant is violated → **critical alert + automated investigation**.

### Consistency Strategy: Synchronous Write Path

```
Within a SINGLE database transaction (same PostgreSQL instance):

BEGIN;
  -- Step 1: Debit sender wallet
  UPDATE wallets SET available_balance = available_balance - 50000,
                     version = version + 1
  WHERE id = sender_wallet_id
    AND available_balance >= 50000
    AND version = current_version;
  -- If 0 rows affected → ROLLBACK (insufficient balance or race condition)

  -- Step 2: Credit receiver wallet
  UPDATE wallets SET available_balance = available_balance + 50000,
                     version = version + 1
  WHERE id = receiver_wallet_id;

  -- Step 3: Insert ledger journal entry (same DB)
  INSERT INTO journal_entries (debit_account, credit_account, amount, ...)
  VALUES ('user_wallet:sender', 'user_wallet:receiver', 50000, ...);

  -- Step 4: Insert transaction record
  INSERT INTO transactions (...) VALUES (...);

  -- Step 5: Insert outbox event (for async consumers)
  INSERT INTO outbox_events (topic, key, payload) VALUES (...);
COMMIT;
```

> **Key design**: Wallet + Ledger tables are **co-located in the same PostgreSQL database** for P2P and internal transfers. This allows a single ACID transaction to ensure both are updated atomically.

### When Co-location Is Not Possible

For flows involving external systems (bank top-up, withdrawal), wallet and ledger may be updated in separate steps:

```
Step 1: Debit wallet (strong consistency, within wallet DB transaction)
Step 2: Record ledger entry (via outbox event → Ledger Service inbox)
Step 3: If ledger insert fails → Compensation: credit wallet back

Daily Reconciliation:
  wallet_balances vs ledger_balances → flag mismatches → auto-alert
```

### Reconciliation Job (Daily, Automated)

```sql
-- For each wallet, compare wallet balance vs ledger balance
SELECT
    w.id AS wallet_id,
    w.available_balance + w.pending_balance + w.frozen_balance AS wallet_total,
    COALESCE(SUM(CASE WHEN je.credit_account = w.account_name THEN je.amount ELSE 0 END), 0)
    - COALESCE(SUM(CASE WHEN je.debit_account = w.account_name THEN je.amount ELSE 0 END), 0)
    AS ledger_total
FROM wallets w
LEFT JOIN journal_entries je
  ON je.credit_account = w.account_name OR je.debit_account = w.account_name
GROUP BY w.id
HAVING wallet_total != ledger_total;
-- Any rows returned = MISMATCH → trigger P0 alert
```

---

## 21. Event Topics & Kafka Streams

### Event Envelope Standard

Every event published to Kafka follows this envelope:

```typescript
interface DomainEvent<T> {
  eventId: string;          // UUID v4, globally unique
  eventType: string;        // e.g., "payment.completed"
  schemaVersion: number;    // e.g., 1, for schema evolution
  source: string;           // e.g., "payment-service"
  correlationId: string;    // Traces request across services
  causationId: string;      // ID of the event/command that caused this
  timestamp: string;        // ISO 8601, e.g., "2026-03-22T12:00:00Z"
  actor: {
    type: 'user' | 'merchant' | 'system' | 'admin';
    id: string;
  };
  payload: T;               // Event-specific data
  metadata: {
    environment: string;    // "production", "sandbox"
    traceId: string;        // OpenTelemetry trace ID
    spanId: string;
  };
}
```

### Complete Topic Catalog

#### Payment Domain

| Topic | Key | Events | Partitions | Retention |
|-------|-----|--------|------------|-----------|
| `payment.commands` | `payment_id` | `InitiatePayment`, `CancelPayment` | 12 | 3 days |
| `payment.events` | `payment_id` | `PaymentInitiated`, `PaymentPending`, `PaymentCompleted`, `PaymentFailed` | 12 | 7 days |
| `payment.dlq` | `payment_id` | Failed events from `payment.events` consumers | 3 | 90 days |

#### Wallet Domain

| Topic | Key | Events | Partitions | Retention |
|-------|-----|--------|------------|-----------|
| `wallet.events` | `wallet_id` | `WalletCredited`, `WalletDebited`, `BalanceHeld`, `BalanceReleased`, `WalletFrozen`, `WalletUnfrozen` | 12 | 7 days |
| `wallet.dlq` | `wallet_id` | Failed events | 3 | 90 days |

#### Ledger Domain

| Topic | Key | Events | Partitions | Retention |
|-------|-----|--------|------------|-----------|
| `ledger.entries` | `journal_id` | `JournalEntryCreated`, `JournalEntryCompensated` | 6 | 30 days |

#### Merchant Domain

| Topic | Key | Events | Partitions | Retention |
|-------|-----|--------|------------|-----------|
| `merchant.events` | `merchant_id` | `MerchantOnboarded`, `MerchantActivated`, `MerchantSuspended`, `WebhookRegistered` | 6 | 7 days |

#### Settlement & Reconciliation

| Topic | Key | Events | Partitions | Retention |
|-------|-----|--------|------------|-----------|
| `settlement.events` | `settlement_id` | `SettlementCalculated`, `SettlementInitiated`, `SettlementCompleted`, `SettlementFailed` | 6 | 30 days |
| `reconciliation.events` | `recon_batch_id` | `ReconciliationStarted`, `ReconciliationCompleted`, `ExceptionFound` | 3 | 30 days |

#### Risk & Compliance

| Topic | Key | Events | Partitions | Retention |
|-------|-----|--------|------------|-----------|
| `fraud.alerts` | `account_id` | `FraudDetected`, `AccountFrozen`, `RiskScoreUpdated` | 6 | 30 days |
| `kyc.events` | `account_id` | `KycSubmitted`, `KycApproved`, `KycRejected`, `TierUpgraded` | 3 | 30 days |
| `dispute.events` | `dispute_id` | `DisputeOpened`, `EvidenceSubmitted`, `DisputeResolved` | 6 | 30 days |

#### Platform

| Topic | Key | Events | Partitions | Retention |
|-------|-----|--------|------------|-----------|
| `notification.commands` | `recipient_id` | `SendSMS`, `SendPush`, `SendEmail`, `SendInApp` | 6 | 3 days |
| `audit.events` | `entity_id` | `AdminAction`, `ConfigChanged`, `SecurityEvent` | 6 | 90 days |
| `account.events` | `account_id` | `AccountCreated`, `AccountUpdated`, `AccountStatusChanged` | 6 | 7 days |

### Consumer Group Naming Convention

```
Format: {service-name}.{topic-name}.{purpose}

Examples:
  notification-service.payment.events.send-receipt
  reporting-service.payment.events.aggregate-daily
  fraud-service.payment.events.risk-scoring
  ledger-service.wallet.events.journal-sync
```

### Ordering Guarantees

| Guarantee | Mechanism |
|-----------|-----------|
| **Per-account ordering** | Partition key = `wallet_id` or `account_id`. All events for same account go to same partition → processed in order by single consumer. |
| **Per-payment ordering** | Partition key = `payment_id`. Payment state transitions are ordered within partition. |
| **Cross-service ordering** | Not guaranteed. Services use `correlationId` to reconstruct order. Saga orchestrator manages sequence. |

---

## 22. Database per Service

### Database Ownership Matrix

| Service | Database | Schema | Primary Tables | Storage Engine |
|---------|----------|--------|---------------|----------------|
| **Account Service** | `account_db` | `accounts` | `accounts`, `account_status_history`, `login_credentials` | PostgreSQL 16 |
| **Wallet Service** | `wallet_db` | `wallets` | `wallets`, `wallet_operations`, `holds` | PostgreSQL 16 |
| **Ledger Service** | `wallet_db` *(co-located)* | `ledger` | `journal_entries`, `chart_of_accounts`, `trial_balance_snapshots` | PostgreSQL 16 |
| **Payment Service** | `payment_db` | `payments` | `payments`, `payment_attempts`, `idempotency_keys` | PostgreSQL 16 |
| **Transaction Service** | `transaction_db` | `transactions` | `transactions`, `transaction_status_history` | PostgreSQL 16 |
| **Settlement Service** | `settlement_db` | `settlements` | `settlement_batches`, `settlement_items`, `settlement_reports` | PostgreSQL 16 |
| **Reconciliation Service** | `recon_db` | `reconciliation` | `recon_batches`, `recon_items`, `exceptions` | PostgreSQL 16 |
| **Refund Service** | `payment_db` *(co-located)* | `refunds` | `refunds`, `refund_items` | PostgreSQL 16 |
| **Dispute Service** | `dispute_db` | `disputes` | `disputes`, `dispute_evidence`, `dispute_decisions` | PostgreSQL 16 |
| **Fraud Service** | `fraud_db` | `fraud` | `risk_scores`, `fraud_rules`, `review_queue`, `velocity_counters` | PostgreSQL 16 + Redis |
| **Limit Service** | `limit_db` | `limits` | `limit_configs`, `limit_counters` | PostgreSQL 16 + Redis |
| **Merchant Service** | `merchant_db` | `merchants` | `merchants`, `merchant_api_keys`, `webhook_configs` | PostgreSQL 16 |
| **KYC Service** | `kyc_db` | `kyc` | `kyc_submissions`, `kyc_documents`, `kyc_reviews` | PostgreSQL 16 + S3 |
| **Notification Service** | `notification_db` | `notifications` | `notifications`, `notification_templates`, `delivery_logs` | PostgreSQL 16 |
| **Reporting Service** | `reporting_db` (read replica) | `reporting` | Materialized views, aggregation tables | PostgreSQL 16 (read replica) |
| **Audit Log Service** | `audit_db` | `audit` | `audit_logs` (append-only, partitioned by month) | PostgreSQL 16 (TimescaleDB) |
| **Bank Integration Service** | `bank_integration_db` | `bank` | `bank_connections`, `bank_callbacks`, `bank_statements` | PostgreSQL 16 |

### Co-location Decisions

| Co-located Services | Same Database | Rationale |
|--------------------|--------------|---------| 
| Wallet + Ledger | `wallet_db` | **ACID guarantee**: wallet debit/credit + journal entry in single transaction. This is the most critical consistency requirement in the entire platform. |
| Payment + Refund | `payment_db` | Refund directly references payment records. Same transactional boundary for refund validation. |

> **All other services have isolated databases.** Cross-service data access is via API calls or event consumption — never direct database queries.

### Cross-Service Data Access Rules

| Pattern | When to Use | Example |
|---------|-------------|---------|
| **Sync API call** | Real-time data needed for request processing | Payment Service → Fraud Service (risk check) |
| **Event consumption** | Service needs to react to another service's state change | Notification Service consumes `payment.events` |
| **Read replica / materialized view** | Reporting or analytics on another service's data | Reporting Service reads from transaction read replica |
| **Never: Direct DB query** | ❌ Never cross database boundaries | ~~Merchant Service queries wallet_db~~ |

### Connection Pooling

| Component | Strategy |
|-----------|----------|
| **Per-service pool** | Each service instance: min 5, max 20 connections |
| **PgBouncer** | Transaction-level pooling in front of each database |
| **Total per database** | Max 200 connections (10 service instances × 20 connections) |
| **Monitoring** | Alert when pool utilization > 80% |

---

## 23. Withdrawal & Chargeback Money Flows (Detailed)

### Withdrawal: Complete Lifecycle

```
Phase 1: Request (t=0)
  User requests withdrawal of 500,000 VND to linked bank account
  ├── Validate: amount > 0, amount <= available_balance, within daily limit
  ├── Fraud check: risk scoring on withdrawal pattern
  └── Bank account validation: account exists and is linked

Phase 2: Hold (t=0, same transaction)
  ├── Wallet: available_balance -= 500,000; pending_withdrawal += 500,000
  ├── Ledger:
  │     DEBIT  user_wallet:{uid}     500,000 VND   (liability ↓)
  │     CREDIT bank_payout_pending   500,000 VND   (asset ↑: in transit)
  └── Transaction: status = PENDING, type = WITHDRAWAL

Phase 3: Bank Transfer (t=0 to t=15min)
  ├── Bank Integration Service initiates transfer via NAPAS/bank API
  ├── Await callback or poll for status
  └── Timeout: 30 minutes → mark for manual review

Phase 4a: Success (bank confirms credit)
  ├── Wallet: pending_withdrawal -= 500,000
  ├── Ledger:
  │     DEBIT  bank_payout_pending   500,000 VND   (asset ↓: transit cleared)
  │     CREDIT bank_pooled_account   500,000 VND   (asset ↓: money left pool)
  ├── Transaction: status = SUCCESS
  └── Notification: "Withdrawal of 500,000 VND completed"

Phase 4b: Failure (bank rejects)
  ├── Wallet: pending_withdrawal -= 500,000; available_balance += 500,000
  ├── Ledger (compensating entry):
  │     DEBIT  bank_payout_pending   500,000 VND   (asset ↓: cancel transit)
  │     CREDIT user_wallet:{uid}     500,000 VND   (liability ↑: restore user)
  ├── Transaction: status = FAILED, reason = bank_rejection
  └── Notification: "Withdrawal failed — funds returned to wallet"
```

### Chargeback: Complete Lifecycle

```
Phase 1: Chargeback Received (t=0)
  Card network or bank notifies platform of chargeback
  ├── Amount: 300,000 VND
  ├── Reason code: "Goods not received" or "Unauthorized"
  └── Original transaction identified via reference number

Phase 2: Evidence Window (t=0 to t=7 days)
  ├── Dispute created internally
  ├── Merchant notified: "Chargeback received, submit evidence within 7 days"
  ├── Hold from merchant:
  │     IF merchant has chargeback_reserve >= 300,000:
  │       DEBIT  chargeback_reserve:{mid}  300,000  (liability ↓)
  │       CREDIT escrow:{chargeback_id}    300,000  (liability ↑: held)
  │     ELSE:
  │       DEBIT  merchant_pending:{mid}    300,000  (liability ↓)
  │       CREDIT escrow:{chargeback_id}    300,000  (liability ↑: held)
  └── Transaction: original payment marked as DISPUTED

Phase 3a: Platform Accepts Chargeback (merchant loses)
  ├── Ledger:
  │     DEBIT  escrow:{chargeback_id}    300,000  (liability ↓: release escrow)
  │     CREDIT bank_pooled_account       300,000  (asset ↓: return to bank)
  ├── Transaction: status = REVERSED
  ├── Merchant report: chargeback deducted from settlement
  └── Notification: merchant + internal teams

Phase 3b: Platform Contests Chargeback (merchant wins)
  ├── Submit evidence to card network
  ├── If accepted:
  │     DEBIT  escrow:{chargeback_id}    300,000  (liability ↓: release)
  │     CREDIT chargeback_reserve:{mid}  300,000  (liability ↑: restore reserve)
  │     OR CREDIT merchant_pending:{mid} 300,000  (liability ↑: restore pending)
  ├── Transaction: DISPUTED → SUCCESS (restored)
  └── Notification: "Chargeback reversed in your favor"

Phase 3c: Platform Absorbs Loss (insufficient merchant funds)
  ├── Ledger:
  │     DEBIT  chargeback_loss           300,000  (expense ↑: platform loss)
  │     CREDIT bank_pooled_account       300,000  (asset ↓: return to bank)
  ├── Recovery: attempt to recover from merchant in future settlements
  └── Risk action: flag merchant for increased chargeback_reserve percentage
```

---

## 24. Fee & Revenue Flows

### Fee Types

| Fee Type | Calculation | Charged To | When |
|----------|-------------|------------|------|
| **P2P Transfer Fee** | Free (or flat fee for large amounts) | Sender | At transaction time |
| **Merchant Payment Fee** | 1.0%–2.5% of transaction amount (tiered) | Merchant (deducted from payment) | At transaction time |
| **QR Payment Fee** | 0.5%–1.5% | Merchant | At transaction time |
| **Bill Payment Fee** | Flat fee: 2,000–5,000 VND | User | At transaction time |
| **Withdrawal Fee** | Flat fee: 5,000–10,000 VND or free (tier-based) | User | At withdrawal time |
| **Top-Up Fee** | Free (bank absorbs) | Platform | At top-up time |
| **Chargeback Fee** | Flat: 200,000 VND per chargeback | Merchant | At chargeback resolution |
| **Late Settlement Fee** | 0.05%/day on overdue settlement | Merchant | Daily accrual |

### Merchant Fee Calculation (per transaction)

```
Merchant receives payment of 100,000 VND

Fee Schedule (tiered by monthly volume):
  0 - 100M VND/month     → 2.0% fee
  100M - 500M VND/month  → 1.5% fee
  500M+ VND/month        → 1.0% fee (negotiated)

Example (Tier 1: 2.0%):
  Gross amount:     100,000 VND
  Platform fee:      -2,000 VND  (2.0%)
  Net to merchant:   98,000 VND

Ledger entries:
  DEBIT  user_wallet:{uid}              100,000 VND  (liability ↓)
  CREDIT merchant_pending:{mid}          98,000 VND  (liability ↑)
  CREDIT platform_fee                     2,000 VND  (revenue ↑)
```

### Revenue Recognition Flow (Monthly)

```
Monthly Revenue Cycle:

1. Daily: Transaction fees collected into platform_fee account
   (accumulated as CREDIT to platform_fee throughout the month)

2. Month-end: Generate revenue report
   Total platform_fee balance: 150,000,000 VND

3. Revenue recognition entry:
   DEBIT  platform_fee              150,000,000 VND  (revenue → recognized)
   CREDIT platform_revenue_realized 150,000,000 VND  (P&L account)

4. Cash sweep to operating account:
   DEBIT  platform_revenue_realized 150,000,000 VND
   CREDIT bank_pooled_account       150,000,000 VND  (asset ↓: transfer out)
   → Bank transfer to platform's own operating bank account
```

### Bank Fee Tracking

```
Every bank transfer incurs a fee charged by the bank:

Top-up (bank charges platform):
  DEBIT  bank_fee     3,000 VND  (expense ↑)
  CREDIT bank_pooled_account  3,000  (asset ↓: bank deducted)

Withdrawal (bank charges platform):
  DEBIT  bank_fee     5,000 VND  (expense ↑)
  CREDIT bank_pooled_account  5,000  (asset ↓: bank deducted)

Net revenue per transaction = platform_fee - bank_fee
```

---

## 25. Manual Adjustment Flow (Detailed)

### When Manual Adjustments Occur

| Scenario | Example | Frequency |
|----------|---------|-----------|
| **Reconciliation discrepancy** | Bank credited user but wallet not updated (system failure) | 1-5 per day |
| **Customer complaint** | User charged twice due to timeout + retry race condition | 1-3 per day |
| **Regulatory requirement** | SBV orders freeze + return of specific funds | Rare |
| **Promotional credit** | Marketing campaign: credit 50,000 VND to 1,000 users | Periodic |
| **System error correction** | Bug caused incorrect fee calculation for batch of transactions | Rare but high-impact |
| **Partner dispute** | Utility provider claims payment not received despite our records | 1-2 per week |

### Adjustment Workflow

```
Step 1: Case Creation
  ├── Source: Support ticket, reconciliation alert, or compliance directive
  ├── Adjustment proposal created:
  │     { type, amount, account_id, reason, evidence_links, ticket_id }
  └── Status: DRAFT

Step 2: Dual Approval
  ├── Requester submits proposal (e.g., support agent)
  ├── First approver reviews (e.g., team lead)
  ├── Second approver reviews (e.g., finance manager)
  ├── For adjustments > 10,000,000 VND: CFO approval required
  └── Status: APPROVED or REJECTED

Step 3: Execution
  ├── System generates unique adjustment_id
  ├── Idempotency key = adjustment_id (prevents double execution)
  ├── Ledger entries created:
  │
  │   Credit adjustment (give money to user):
  │     DEBIT  adjustment_expense       50,000 VND  (expense ↑)
  │     CREDIT user_wallet:{uid}        50,000 VND  (liability ↑)
  │
  │   Debit adjustment (take money from user — rare):
  │     DEBIT  user_wallet:{uid}        50,000 VND  (liability ↓)
  │     CREDIT adjustment_recovery      50,000 VND  (revenue ↑)
  │
  ├── Wallet balance updated
  ├── Transaction record created: type = ADJUSTMENT
  └── Status: EXECUTED

Step 4: Notification & Audit
  ├── User notified: "Your account has been adjusted +50,000 VND. Ref: ADJ-2026-0142"
  ├── Audit log entry:
  │     { actor, action: "manual_adjustment", amount, reason,
  │       ticket_id, approvers: [approver_1, approver_2],
  │       timestamp, ip_address }
  └── Status: COMPLETED
```

### Adjustment Controls

| Control | Rule |
|---------|------|
| **Dual approval** | Always required. No single person can both propose and approve. |
| **Amount thresholds** | < 1M VND: Team lead + Finance. 1M–10M: Finance Manager + Finance Director. > 10M: CFO required. |
| **Batch adjustments** | Promotional credits: requires pre-approved campaign ID + budget. Each individual credit still logged. |
| **Reversal** | Adjustments can be reversed via another adjustment (compensating entry). Original adjustment is never deleted. |
| **Daily limit** | Max 50 manual adjustments per day. Exceeding triggers executive alert. |
| **Audit** | All adjustments are included in monthly compliance report. External auditors review quarterly. |
| **Segregation of duties** | Support agents cannot access adjustment API directly. Only via back-office tool with approval workflow. |

### Adjustment Accounts in Chart of Accounts

| Account | Type | Purpose |
|---------|------|---------|
| `adjustment_expense` | Expense | Credits given to users (goodwill, error correction) |
| `adjustment_recovery` | Revenue | Debits from users (error correction, clawback) |
| `promotional_expense` | Expense | Marketing campaign credits |

---

## 26. Connection to Phase 02

**Phase 02 — Requirements & SLOs** will use this document to produce:

| Input (from Phase 01) | Output (Phase 02) |
|----------------------|-------------------|
| User journeys (14 journeys) | Detailed user stories with acceptance criteria |
| KPI targets | SLO definitions per service (availability, latency, error rate) |
| Scale estimates (300K daily txns, 500 TPS design) | Traffic model per endpoint (RPS, storage/year, bandwidth) |
| Financial system components (17 services) | Service-level NFRs (consistency model, data volume, security) |
| Compliance requirements | NFR matrix (8 dimensions per service) |
| External integrations | Integration SLAs and fallback strategies |
| Money flow overview (12+ flows) | Data consistency requirements per flow |
| Data volume estimation | Storage capacity planning per service |
| Security architecture | Per-service security requirements |
| Consistency model | CAP trade-off decisions per service |
| Transaction state machine | State-specific SLOs (latency per transition) |
| Event-driven architecture | Event throughput, Kafka sizing, topic SLOs |
| Idempotency strategy | Per-endpoint idempotency requirements |
| Database ownership matrix | Per-service data isolation verification |
| Fee & revenue model | Financial reporting and reconciliation requirements |

---

### 🛑 APPROVAL GATE — 📋 Document Review

> **Review `01-product-discovery.md` (v4.0)**
>
> This document has been expanded with architecture deep-dive sections. Please verify:
> - [ ] Idempotency strategy covers all mutation endpoints with key design + TTL
> - [ ] Wallet vs Ledger consistency model explains co-location + reconciliation
> - [ ] Event topics catalog covers all domains with envelope standard
> - [ ] Database-per-service matrix correctly identifies co-located services
> - [ ] Withdrawal & chargeback flows cover all lifecycle phases
> - [ ] Fee & revenue flows include all fee types + revenue recognition
> - [ ] Manual adjustment flow has proper dual-approval controls
> - [ ] MVP scope is unchanged (all original features preserved)
>
> Reply **APPROVE** to proceed to Phase 02 (Requirements & SLOs), or provide feedback.

