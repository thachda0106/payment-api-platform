# Phase 04 — Domain Design

## MoMo-like Payment API Platform

> **Document Status**: Draft v7.0 — Approved with Changes
> **Last Updated**: 2026-03-29
> **Audience**: Engineering Leadership, Architecture Review Board
> **Input**: Phase 01 (v4.0), Phase 02 (v3.0), Phase 03 (v3.1)
> **Author Level**: Principal/Staff Engineer

---

## 1. System Domain Overview

This document defines the domain model for a large-scale digital wallet and payment platform. All bounded contexts are classified using DDD strategic design: **Core**, **Supporting**, and **Generic** domains.

**Key Architecture Decisions**:

| Decision | Detail |
|----------|--------|
| Ledger + Wallet merged into **Financial Core** | Single bounded context, co-located DB. Wallet balance is a projection of ledger journal entries. |
| Journal entries use **journal_entries + journal_lines** | Multi-line journal support (1 header + N lines) instead of single-row debit/credit. |
| **Refund** is its own bounded context | Separated from Payment to avoid God Service. Owns refund lifecycle, reversals, chargebacks. |
| **Compliance/AML** is its own bounded context | Separated from Risk & Fraud. Handles KYC enforcement, AML screening, SAR filing. |
| **FX** is a Core Domain context | Manages exchange rates, quotes, FX positions, multi-currency journal entries. |
| **Treasury** promoted to Core Domain | Manages liquidity, reserves, inter-bank transfers. Critical for financial operations. |
| Domain boundary ≠ database boundary | Multiple contexts can share a DB for atomicity. Contexts share schemas, not tables. |

---

## 2. Core Domains

Core domains provide competitive advantage. They contain the most complex business logic and are built in-house.

| Context | Responsibility | Why Core |
|---------|---------------|----------|
| **Financial Core** (Ledger + Wallet) | Double-entry journal (source of truth) + wallet balance projection | Every VND flows through here. Financial integrity depends on it. |
| **Payment** | Payment lifecycle state machine, saga coordination | Core product — all user-facing money movement starts here. |
| **Refund & Reversal** | Refund lifecycle, chargebacks, reversals, escrow | Financial correctness of money-back flows. |
| **FX & Multi-Currency** | Exchange rates, FX quotes, cross-currency journals | Required for international expansion. |
| **Treasury** | Liquidity, reserves, inter-bank transfers, funding accounts | Platform solvency depends on treasury operations. |
| **Risk & Fraud** | Real-time fraud scoring, velocity checks, account freezing | Direct revenue protection. |

---

## 3. Supporting Domains

Supporting domains are necessary but not differentiating. They support core domains.

| Context | Responsibility | Why Supporting |
|---------|---------------|----------------|
| **Settlement** | EOD merchant batch, net calculation, bank payout | Operational process, not a differentiator. |
| **Reconciliation** | Three-way match (wallet ↔ ledger ↔ bank) | Ensures correctness but doesn't create value. |
| **Compliance / AML** | KYC enforcement, AML screening, SAR filing, watchlists | Regulatory requirement, not competitive advantage. |
| **Dispute** | Dispute lifecycle, evidence, deadlines | Low volume, process-driven. |
| **Merchant** | Onboarding, API keys, webhooks, fee schedules | Business ops, not core payments logic. |
| **Identity** | Account, auth, KYC data, PIN | Identity is foundational but not differentiating. |
| **Fee Engine / Pricing** | Fee calculation, tiered pricing, promotions, cashback | Important but swappable. |
| **Payment Method** | Card, bank account, QR, mandates, tokenization | Instrument management. |

---

## 4. Generic Domains

Generic domains can be bought or built with minimal customization.

| Context | Responsibility | Why Generic |
|---------|---------------|-------------|
| **Notification** | SMS, push, email, in-app delivery | Off-the-shelf capable. |
| **Audit** | Immutable append-only log, 7yr retention | Standard compliance logging. |
| **Reporting** | Dashboards, financial reports, materialized views | BI tooling. |
| **Transaction** (read model) | Transaction history, search, user statements | CQRS read projection. |
| **Bank Integration** | ACL to bank APIs, protocol translation | Adapter layer. |

---

## 5. Bounded Context Definitions

### 5.1 Financial Core Context (Ledger + Wallet — MERGED)

> **Key change**: Ledger and Wallet are merged into a single bounded context. They share `financial_core_db`. Wallet balance is a materialized projection updated atomically in the same TX as journal entry writes.

| Element | Type | Description |
|---------|------|-------------|
| `JournalEntry` | **Aggregate Root** | Header: `{ entry_id, journal_id, reference_type, reference_id, description, idempotency_key, created_at, created_by }`. Immutable, append-only. |
| `JournalLine` | Entity (child of JournalEntry) | `{ line_id, entry_id, account_id, entry_type: DEBIT/CREDIT, amount, currency }`. Each entry has ≥ 2 lines. |
| `LedgerAccount` | **Aggregate Root** | `{ account_id, parent_id, account_type, name, normal_balance, currency, is_active }` |
| `WalletBalance` | **Aggregate Root** | `{ account_id, available_balance, pending_balance, frozen_balance, version }`. Projection from journal lines. |
| `BalanceHold` | Entity | `{ hold_id, account_id, amount, reason, expires_at }` |
| `Money` | Value Object | `{ amount: bigint, currency: CHAR(3) }` — always positive integer |

**Domain Events**: `JournalEntryCreated`, `WalletBalanceUpdated`, `BalanceHoldPlaced`, `BalanceHoldReleased`, `LedgerImbalanceDetected`

**Invariants**:
- `SUM(DEBIT lines) == SUM(CREDIT lines)` per journal entry
- `available_balance >= 0` (DB CHECK)
- Balance updated ONLY in same TX as journal entry INSERT
- No UPDATE/DELETE on journal_entries or journal_lines (append-only)
- `version` column for optimistic concurrency

### 5.2 Payment Context

| Element | Type | Description |
|---------|------|-------------|
| `Payment` | **Aggregate Root** | State machine. Types: `P2P`, `MERCHANT`, `QR`, `TOPUP`, `WITHDRAWAL`, `BILL` |
| `IdempotencyKey` | Value Object | Client-provided, UNIQUE |
| `SagaState` | Entity | `{ saga_id, type, current_step, status, compensation_data, timeout_at }` |
| `PaymentStateTransition` | Entity | `{ from, to, reason, timestamp }` |

**Domain Events**: `PaymentInitiated`, `PaymentValidating`, `PaymentExecuting`, `PaymentCompleted`, `PaymentFailed`, `PaymentDeclined`, `PaymentCancelled`, `PaymentReversed`

**What Payment does NOT do**: ❌ Write journal entries. ❌ Update balances. ❌ Send notifications. ❌ Write audit. ❌ Write transaction history.

### 5.3 Refund & Reversal Context (NEW)

| Element | Type | Description |
|---------|------|-------------|
| `Refund` | **Aggregate Root** | Lifecycle: `REQUESTED → VALIDATED → PROCESSING → COMPLETED / FAILED`. Links to original payment. |
| `Reversal` | **Aggregate Root** | System-initiated: `{ original_entry_id, reason, reversal_entry_id }` |
| `Chargeback` | **Aggregate Root** | Bank-initiated: `{ chargeback_id, original_payment_id, amount, status, deadline, evidence[] }` |
| `RefundPolicy` | Value Object | `{ max_window_days: 90, partial_allowed: true, fee_refund: true }` |

**Domain Events**: `RefundRequested`, `RefundCompleted`, `RefundFailed`, `ReversalCompleted`, `ChargebackReceived`, `ChargebackResolved`

**Invariants**: `SUM(refunds) <= original_amount`. Refund window ≤ 90 days. Chargeback response ≤ 7 days.

### 5.4 FX & Multi-Currency Context (NEW as Core)

| Element | Type | Description |
|---------|------|-------------|
| `ExchangeRate` | **Aggregate Root** | `{ pair, mid_rate, bid, ask, spread, source, valid_from, valid_until }` |
| `FXQuote` | Entity | Locked rate: `{ quote_id, rate, expires_at: 30s, used: bool }` |
| `FXPosition` | Entity | `{ currency, long_amount, short_amount, net }` |
| `FXSettlement` | Entity | Daily batch: close positions with FX provider |

**Domain Events**: `ExchangeRateUpdated`, `FXQuoteCreated`, `FXQuoteExpired`, `FXSettlementCompleted`

### 5.5 Treasury Context (Promoted to Core)

| Element | Type | Description |
|---------|------|-------------|
| `TreasuryTransfer` | **Aggregate Root** | Inter-bank: `{ from_bank, to_bank, amount, status, approved_by }` — dual approval (maker-checker) |
| `LiquidityPosition` | Entity | Per-bank snapshot: `{ bank_code, available, reserved, last_synced }` |
| `FundingAccount` | Entity | `{ account_number, bank_code, balance, type: COLLECTION / PAYOUT }` |
| `ReserveRequirement` | Value Object | `{ min_balance, alert_threshold }` |

**Domain Events**: `TreasuryTransferCompleted`, `LiquidityAlertRaised`, `ReserveBreached`

### 5.6 Risk & Fraud Context

| Element | Type | Description |
|---------|------|-------------|
| `RiskAssessment` | **Aggregate Root** | `{ score: 0-100, decision: ALLOW/REVIEW/BLOCK, reasons[] }` |
| `FraudRule` | Entity | Configurable: condition → threshold → action |
| `VelocityCounter` | Entity | Redis + DB rolling window |
| `TransactionLimit` | **Aggregate Root** | Per-KYC-tier: per-txn, daily, monthly, annual |
| `FreezeOrder` | Entity | `FULL / DEBIT_ONLY / CREDIT_ONLY / WITHDRAWAL_ONLY` |

### 5.7 Compliance / AML Context (NEW)

| Element | Type | Description |
|---------|------|-------------|
| `AMLScreening` | **Aggregate Root** | `{ screening_id, trigger, result: CLEAR/MATCH/REVIEW, watchlist_hits[] }` |
| `SuspiciousActivityReport` | Entity | SAR: `{ sar_id, account_id, amount, reason, filed_at, regulator_ref }` |
| `AccountRestriction` | **Aggregate Root** | Granular freeze with type, scope, reason, expiry |
| `WatchlistEntry` | Entity | PEP/sanctions: `{ name, aliases, source, score }` |
| `ComplianceCase` | Entity | Investigation workflow: `{ case_id, status, assignee, evidence[], resolution }` |

**Domain Events**: `AMLScreeningCompleted`, `SARFiled`, `AccountRestrictionPlaced`, `WatchlistMatchFound`, `ComplianceReviewRequired`

### 5.8 Other Contexts (Summary)

| Context | Key Aggregates |
|---------|---------------|
| **Settlement** | `SettlementBatch`, `MerchantSettlement` |
| **Reconciliation** | `ReconciliationRun`, `ReconException` |
| **Dispute** | `Dispute`, `DisputeEvidence` |
| **Merchant** | `Merchant`, `APICredential`, `WebhookConfig`, `FeeSchedule` |
| **Identity** | `Account`, `KYCProfile`, `TransactionPIN` |
| **Fee Engine** | `FeeConfiguration`, `FeeRule`, `Promotion`, `CashbackRule` |
| **Payment Method** | `PaymentMethod`, `Card`, `BankAccount`, `Mandate` |
| **Notification** | `Notification`, `Template`, `UserPreference` |
| **Audit** | `AuditEntry` (append-only, 7yr, partitioned monthly) |
| **Reporting** | Materialized views (read-only) |
| **Transaction** | `TransactionRecord` (CQRS read model) |
| **Bank Integration** | `BankConnection`, `BankTransaction`, `BankCallback` |

---

## 6. Aggregates per Context (Summary)

| Context | Aggregate Roots | Entities | Value Objects |
|---------|----------------|----------|---------------|
| **Financial Core** | JournalEntry, LedgerAccount, WalletBalance | JournalLine, BalanceHold | Money, EntryType, ReferenceType |
| **Payment** | Payment | SagaState, PaymentStateTransition | IdempotencyKey, PaymentMethod, PaymentParties |
| **Refund** | Refund, Reversal, Chargeback | — | RefundPolicy |
| **FX** | ExchangeRate | FXQuote, FXPosition, FXSettlement | CurrencyPair |
| **Treasury** | TreasuryTransfer | LiquidityPosition, FundingAccount | ReserveRequirement |
| **Risk & Fraud** | RiskAssessment, TransactionLimit | FraudRule, VelocityCounter, FreezeOrder | RiskDecision |
| **Compliance** | AMLScreening, AccountRestriction | SAR, WatchlistEntry, ComplianceCase | RestrictionType |

---

## 7. Domain Events (Complete Catalog)

| Context | Events |
|---------|--------|
| **Financial Core** | `JournalEntryCreated`, `WalletBalanceUpdated`, `BalanceHoldPlaced`, `BalanceHoldReleased`, `LedgerImbalanceDetected` |
| **Payment** | `PaymentInitiated`, `PaymentValidating`, `PaymentExecuting`, `PaymentCompleted`, `PaymentFailed`, `PaymentDeclined`, `PaymentCancelled` |
| **Refund** | `RefundRequested`, `RefundCompleted`, `RefundFailed`, `ReversalCompleted`, `ChargebackReceived`, `ChargebackResolved` |
| **FX** | `ExchangeRateUpdated`, `FXQuoteCreated`, `FXQuoteExpired`, `FXSettlementCompleted` |
| **Treasury** | `TreasuryTransferInitiated`, `TreasuryTransferCompleted`, `LiquidityAlertRaised`, `ReserveBreached` |
| **Risk** | `FraudCheckCompleted`, `FraudAlertRaised`, `LimitExceeded`, `AccountFreezeOrdered` |
| **Compliance** | `AMLScreeningCompleted`, `SARFiled`, `AccountRestrictionPlaced`, `WatchlistMatchFound` |
| **Settlement** | `SettlementBatchStarted`, `SettlementCalculated`, `SettlementCompleted` |
| **Identity** | `UserRegistered`, `KYCTierUpgraded`, `AccountFrozen`, `AccountClosed` |
| **Merchant** | `MerchantRegistered`, `KYBApproved`, `ProductionAccessGranted` |

---

## 8. Payment State Machine

### 8.1 State Diagram

```
                                    ┌──────────────────────────┐
                                    │                          │
   ┌───────────┐  validate  ┌──────┴───┐  fraud+limit  ┌──────────┐
   │ INITIATED ├───────────▶│VALIDATING├──────────────▶│AUTHORIZED│
   └─────┬─────┘            └────┬─────┘               └─────┬────┘
         │                       │                            │
         │ cancel                │ fraud=BLOCK                │ ledger write
         ▼                       ▼                            ▼
   ┌──────────┐          ┌──────────┐                  ┌───────────┐
   │CANCELLED │          │ DECLINED │                  │ EXECUTING │
   └──────────┘          └──────────┘                  └─────┬─────┘
                                                             │
                              ┌───────────────────────────────┤
                              │ success                       │ fail
                              ▼                               ▼
                       ┌─────────────┐                 ┌──────────┐
                       │  COMPLETED  │                 │  FAILED  │
                       └──────┬──────┘                 └─────┬────┘
                              │                              │
                  ┌───────────┼──────────┐         (if funds locked)
                  │           │          │                   ▼
                  ▼           ▼          ▼          ┌──────────────┐
           ┌──────────┐ ┌────────┐ ┌─────────┐    │ COMPENSATING │
           │ REFUND   │ │DISPUTED│ │CHARGEBACK│   └──────┬───────┘
           │_PENDING  │ │        │ │_PENDING  │          ▼
           └────┬─────┘ └───┬────┘ └────┬─────┘  ┌──────────┐
                ▼           ▼           ▼         │ REVERSED │
           ┌────────┐ ┌──────────┐ ┌─────────┐   └──────────┘
           │REFUNDED│ │DISPUTE   │ │CHARGED  │
           │        │ │_RESOLVED │ │  BACK   │
           └────────┘ └──────────┘ └─────────┘
```

### 8.2 State Transitions

| From | To | Trigger | Guard | Ledger Action |
|------|----|---------|-------|---------------|
| INITIATED | VALIDATING | System | — | — |
| INITIATED | CANCELLED | User | < 5s window | — |
| VALIDATING | AUTHORIZED | Fraud=ALLOW, Limit=PASS | — | — |
| VALIDATING | DECLINED | Fraud=BLOCK or Limit=FAIL | — | — |
| AUTHORIZED | EXECUTING | System (auto) | — | Write journal entry + update balance |
| EXECUTING | COMPLETED | Ledger TX committed | — | Emit PaymentCompleted |
| EXECUTING | FAILED | Ledger TX failed | — | Emit PaymentFailed |
| FAILED | COMPENSATING | Funds already locked | Has saga compensation data | Write reversal journal |
| COMPENSATING | REVERSED | Compensation TX committed | — | Emit PaymentReversed |
| COMPLETED | REFUND_PENDING | Refund requested | Within 90d, amount valid | — (Refund Context handles) |
| COMPLETED | DISPUTED | User dispute | Within 180d | Escrow hold journal |
| COMPLETED | CHARGEBACK_PENDING | Bank notice | — | Reserve hold journal |

**New state: AUTHORIZED** — separates "approved by risk" from "money moved." Allows auth-capture flows.

---

## 9. Ledger / Accounting Model (Journal Entries + Journal Lines)

### 9.1 Schema Change: journal_entries + journal_lines

> **Key change**: Replace single-row `debit_account, credit_account` with multi-line journal model. This supports complex movements (fee splits, FX, multi-party) natively.

```sql
CREATE TABLE journal_entries (
  entry_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  journal_id      UUID NOT NULL,              -- groups related entries
  reference_type  VARCHAR(50) NOT NULL,       -- PAYMENT, TRANSFER, REFUND, FEE, INTEREST, etc
  reference_id    UUID NOT NULL,
  movement_type   VARCHAR(30) NOT NULL,       -- see §9.2
  description     VARCHAR(500),
  idempotency_key VARCHAR(255) NOT NULL UNIQUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by      VARCHAR(100) NOT NULL
) PARTITION BY RANGE (created_at);

CREATE TABLE journal_lines (
  line_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  entry_id        UUID NOT NULL REFERENCES journal_entries(entry_id),
  account_id      VARCHAR(255) NOT NULL,      -- ledger account
  entry_type      VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
  amount          BIGINT NOT NULL CHECK (amount > 0),
  currency        CHAR(3) NOT NULL DEFAULT 'VND',
  line_order      INT NOT NULL DEFAULT 1
);

-- Constraint: per entry, SUM(DEBIT amounts) == SUM(CREDIT amounts)
-- Enforced by application + async reconciliation check
```

### 9.2 Movement Types

| Movement Type | Description | Example |
|--------------|-------------|---------|
| `PAYMENT` | User-initiated payment (P2P, merchant, QR, bill) | User pays merchant |
| `TRANSFER` | Internal transfer between accounts | P2P wallet transfer |
| `REFUND` | Return of funds from a previous payment | Merchant refund |
| `FEE` | Platform transaction fee | 1.5% merchant fee |
| `INTEREST` | Interest earned on pooled funds | Monthly interest accrual |
| `CASHBACK` | Promotional cashback to user | 5% cashback campaign |
| `FX_SETTLEMENT` | FX position close between currencies | Daily FX batch settlement |
| `ADJUSTMENT` | Manual finance correction | Recon adjustment |
| `REVERSAL` | System-initiated undo of a prior entry | Saga compensation |
| `TOPUP` | External money-in from bank | Bank → wallet |
| `WITHDRAWAL` | External money-out to bank | Wallet → bank |
| `SETTLEMENT` | Merchant batch payout | EOD settlement |
| `CHARGEBACK` | Bank-initiated dispute reversal | Card network chargeback |
| `ESCROW_HOLD` | Funds held in escrow | Dispute escrow |
| `ESCROW_RELEASE` | Escrow funds released | Dispute resolution |

### 9.3 Multi-Line Journal Example: Merchant Payment with Fee

```
JournalEntry:
  entry_id: je_001
  reference_type: PAYMENT
  reference_id: pay_abc
  movement_type: PAYMENT
  description: "Merchant payment with fee split"

JournalLines:
  | line_id | account_id                | entry_type | amount  | currency |
  |---------|---------------------------|------------|---------|----------|
  | jl_001  | liability:user_wallet:u1  | DEBIT      | 100,000 | VND      |
  | jl_002  | liability:merchant_pending:m1 | CREDIT | 98,500  | VND      |
  | jl_003  | revenue:platform_fee      | CREDIT     | 1,500   | VND      |

  Validation: DEBIT(100,000) == CREDIT(98,500 + 1,500) ✅
```

### 9.4 Balance Projection (Same TX)

```sql
BEGIN;
  -- 1. Insert journal entry
  INSERT INTO journal_entries (...) VALUES (...);
  INSERT INTO journal_lines (...) VALUES
    (..., 'liability:user_wallet:u1', 'DEBIT', 100000, 'VND'),
    (..., 'liability:merchant_pending:m1', 'CREDIT', 98500, 'VND'),
    (..., 'revenue:platform_fee', 'CREDIT', 1500, 'VND');

  -- 2. Update wallet projections
  UPDATE wallet_balances SET available_balance = available_balance - 100000,
    version = version + 1 WHERE account_id = 'u1' AND available_balance >= 100000;
  UPDATE wallet_balances SET available_balance = available_balance + 98500,
    version = version + 1 WHERE account_id = 'm1';

  -- 3. Outbox event
  INSERT INTO outbox_events (...) VALUES (..., 'PaymentCompleted', ...);
COMMIT;
```

---

## 10. FX and Multi-Currency Handling

### 10.1 Cross-Currency Journal (Multi-Line)

```
User pays 2,500,000 VND → USD merchant. Rate: 25,125 VND/USD (incl. 0.5% spread)

JournalEntry: movement_type = FX_SETTLEMENT
JournalLines:
  | account_id                  | entry_type | amount    | currency |
  |-----------------------------|------------|-----------|----------|
  | liability:user_wallet:u1    | DEBIT      | 2,500,000 | VND      |
  | liability:fx_payable:VND    | CREDIT     | 2,500,000 | VND      |

JournalEntry: movement_type = PAYMENT
JournalLines:
  | account_id                      | entry_type | amount | currency |
  |---------------------------------|------------|--------|----------|
  | asset:fx_receivable:USD         | DEBIT      | 99.50  | USD      |
  | liability:merchant_pending:m1   | CREDIT     | 98.01  | USD      |
  | revenue:platform_fee:merchant   | CREDIT     | 1.49   | USD      |

JournalEntry: movement_type = FEE (FX margin)
JournalLines:
  | account_id               | entry_type | amount | currency |
  |--------------------------|------------|--------|----------|
  | liability:fx_payable:VND | DEBIT      | 12,500 | VND      |
  | revenue:fx_margin        | CREDIT     | 12,500 | VND      |
```

**Rule**: Each journal entry has ONE currency. Cross-currency = multiple entries linked by `journal_id`.

---

## 11. Treasury Management

```
Treasury responsibilities:
  ✅ Inter-bank transfers (maker-checker dual approval)
  ✅ Liquidity monitoring (per-bank position every 15 min)
  ✅ Reserve management (SBV regulatory + internal buffers)
  ✅ Funding account reconciliation (daily T+1)
  ✅ FX position settlement with FX providers

Treasury is Core because:
  - Platform solvency depends on liquidity management
  - Regulatory reserves are mandatory (SBV)
  - Poor treasury management → cannot process withdrawals
```

---

## 12. Compliance / AML

```
Compliance flow:
  1. [Sync] Limit check on critical path (< 30ms) — per-KYC-tier enforcement
  2. [Async] Post-transaction AML screening:
     - Velocity pattern analysis (structuring detection)
     - Watchlist screening (PEP, sanctions)
     - If MATCH → ComplianceReviewRequired event
     - If threshold exceeded → auto-generate SAR draft
  3. [Manual] SAR filing by compliance officer (maker-checker)
  4. [Async] Account restrictions immediately enforced

KYC-Tier Limits:
  | Tier | Per-Txn | Daily | Monthly |
  |------|---------|-------|---------|
  | NON_KYC (0) | 2M VND | 5M | 20M |
  | BASIC_KYC (1) | 10M | 30M | 100M |
  | FULL_KYC (2) | 50M | 200M | 500M |
```

---

## 13. Refund and Reversal Flows

### 13.1 Refund Flow

```
RefundRequested → Validate(original exists, COMPLETED, ≤90d, amount ≤ remaining)
  → Financial Core: journal entry (DEBIT merchant_pending, CREDIT user_wallet + fee_refund)
    → RefundCompleted → [choreography: Transaction, Notification, Settlement(adj), Audit]
```

### 13.2 Reversal Flow (System)

```
Trigger: saga failure / fraud / duplicate
  → Financial Core: mirror journal entry (swap all DEBIT↔CREDIT, entry_type=REVERSAL)
    → ReversalCompleted → [choreography: Transaction, Notification, Audit]
```

### 13.3 Chargeback Flow

```
ChargebackReceived (from bank)
  → Escrow hold: DEBIT merchant_pending → CREDIT escrow:{cb_id}
    → Notify merchant (7-day deadline)
      → Merchant wins: DEBIT escrow → CREDIT merchant_pending
      → User wins: DEBIT escrow → CREDIT user_wallet
        → If reserve insufficient: DEBIT expense:chargeback_loss → CREDIT chargeback_reserve
```

---

## 14. Domain Context Map

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         DOMAIN CONTEXT MAP                              │
│                                                                         │
│  ╔══════════════════════════════════════════════════════════╗            │
│  ║                CORE DOMAINS                              ║            │
│  ║                                                          ║            │
│  ║  ┌──────────────────┐      ┌────────────────┐           ║            │
│  ║  │  FINANCIAL CORE  │◄─────│    PAYMENT     │           ║            │
│  ║  │  (Ledger+Wallet) │ sync │ (State Machine)│           ║            │
│  ║  │  Source of Truth  │      └───────┬────────┘           ║            │
│  ║  └────────┬─────────┘              │                    ║            │
│  ║           │                        │                    ║            │
│  ║     ┌─────┼──────────┬─────────────┤                    ║            │
│  ║     │     │          │             │                    ║            │
│  ║     ▼     ▼          ▼             ▼                    ║            │
│  ║  ┌──────┐ ┌────────┐ ┌──────────┐ ┌──────────┐         ║            │
│  ║  │REFUND│ │   FX   │ │ TREASURY │ │RISK/FRAUD│         ║            │
│  ║  │      │ │        │ │          │ │  (sync)  │         ║            │
│  ║  └──┬───┘ └───┬────┘ └────┬─────┘ └──────────┘         ║            │
│  ╚═════╪═════════╪══════════╪════════════════════════════════╝            │
│        │         │          │                                            │
│  ╔═════╪═════════╪══════════╪════════════════════════════════╗            │
│  ║     │  SUPPORTING DOMAINS                                 ║            │
│  ║     │         │          │                                ║            │
│  ║  ┌──▼───┐ ┌───▼────┐ ┌──▼─────────┐ ┌──────────┐       ║            │
│  ║  │SETTLE│ │MERCHANT│ │ COMPLIANCE  │ │ IDENTITY │       ║            │
│  ║  │ MENT │ │        │ │   / AML    │ │          │       ║            │
│  ║  └──────┘ └────────┘ └────────────┘ └──────────┘       ║            │
│  ║  ┌──────┐ ┌────────┐ ┌────────────┐ ┌──────────┐       ║            │
│  ║  │RECON │ │DISPUTE │ │ FEE ENGINE │ │ PAY METHOD│      ║            │
│  ║  └──────┘ └────────┘ └────────────┘ └──────────┘       ║            │
│  ╚═══════════════════════════════════════════════════════════╝            │
│                                                                         │
│  ╔═══════════════════════════════════════════════════════════╗            │
│  ║     GENERIC DOMAINS                                       ║            │
│  ║  ┌────────────┐ ┌───────┐ ┌──────────┐ ┌──────────────┐  ║            │
│  ║  │NOTIFICATION│ │ AUDIT │ │REPORTING │ │ TRANSACTION  │  ║            │
│  ║  │            │ │       │ │          │ │ (read model) │  ║            │
│  ║  └────────────┘ └───────┘ └──────────┘ └──────────────┘  ║            │
│  ║  ┌────────────────┐                                       ║            │
│  ║  │BANK INTEGRATION│                                       ║            │
│  ║  │     (ACL)      │                                       ║            │
│  ║  └────────────────┘                                       ║            │
│  ╚═══════════════════════════════════════════════════════════╝            │
└─────────────────────────────────────────────────────────────────────────┘

Relationship Types:
  Payment ──sync──▶ Financial Core    (Customer-Supplier: writes journal entries)
  Payment ──sync──▶ Risk/Fraud        (Customer-Supplier: fraud/limit check)
  Payment ──sync──▶ Fee Engine        (Customer-Supplier: calculate fees)
  Refund  ──sync──▶ Financial Core    (Customer-Supplier: refund journals)
  FX      ──sync──▶ Financial Core    (Customer-Supplier: FX journal entries)
  Treasury ─sync──▶ Bank Integration  (Anti-Corruption Layer)
  Settlement─sync─▶ Financial Core    (Customer-Supplier: settlement journals)
  Payment ─async──▶ Transaction, Notification, Audit, Settlement, Reporting  (Published Language)
  Financial Core ─async──▶ Reconciliation, Reporting  (Published Language)
  Identity ──────▶ Payment, Merchant  (Conformist: downstream accepts identity model)
```

---

## 15. Service Boundaries vs Database Boundaries

> **Key principle**: A bounded context defines a domain boundary, NOT a database boundary. Multiple contexts CAN share a database when atomicity requires it.

| Bounded Context(s) | Database | Rationale |
|--------------------|---------|-----------| 
| **Financial Core** (Ledger + Wallet) | `financial_core_db` | Atomic: journal entry + balance projection in single TX |
| **Payment** + **Refund** | `payment_db` | Refund references original payment. Separate aggregates, same DB for consistency. |
| **Risk & Fraud** | `fraud_db` + Redis | Redis for velocity counters (hot path). DB for rules, assessments. |
| **Compliance / AML** | `compliance_db` | Separate from fraud — different team, different SLOs. |
| **FX** | `fx_db` | Rates, quotes, positions. Independent lifecycle. |
| **Treasury** | `treasury_db` | Internal finance ops. Restricted access. |
| **Settlement** | `settlement_db` | Batch processing. Lower SLO acceptable. |
| **Reconciliation** | `recon_db` | Batch. References ledger data via API, not direct DB. |
| **Identity** | `account_db` | Auth, KYC. High security isolation. |
| **Merchant** | `merchant_db` | Onboarding, API keys. Independent lifecycle. |
| **Notification** | `notification_db` | Async. Eventual consistency. |
| **Audit** | `audit_db` (TimescaleDB) | Append-only. Partitioned monthly. 7yr retention. |
| **Reporting** | `reporting_db` (replicas) | Materialized views. Hours-stale acceptable. |
| **Transaction** | `transaction_db` + OpenSearch | Read model. Eventually consistent. |

```
   ┌─── financial_core_db ────┐    ┌─── payment_db ───────┐
   │  journal_entries          │    │  payments             │
   │  journal_lines            │    │  saga_states          │
   │  ledger_accounts          │    │  idempotency_keys     │
   │  wallet_balances          │    │  refunds              │
   │  balance_holds            │    │  chargebacks          │
   │  outbox_events            │    │  reversals            │
   └───────────────────────────┘    └──────────────────────┘
   Shared DB ≠ shared context.      Shared DB ≠ shared context.
   Ledger + Wallet = 1 context.     Payment + Refund = 2 contexts, 1 DB.
```

---

## 16. Event Flow Between Domains

### 16.1 Sync Calls (Critical Path)

| Caller | Callee | Why Sync | Budget |
|--------|--------|----------|--------|
| Payment → Risk/Fraud | Real-time risk decision | < 50ms |
| Payment → Compliance (Limits) | Limit enforcement | < 30ms |
| Payment → Fee Engine | Fee calculation before journal write | < 30ms |
| Payment → Financial Core | Write journal entry + update balance | < 100ms |
| Payment → Wallet (read) | Balance pre-check | < 20ms |
| Refund → Financial Core | Refund journal entry | < 100ms |
| FX → Financial Core | Cross-currency journal entries | < 100ms |
| Settlement → Financial Core | Settlement journal entries | < 100ms |
| Treasury → Bank Integration | Inter-bank transfers | < 200ms |
| Gateway → Identity | Auth verification | < 50ms |

### 16.2 Async Events (Kafka Choreography)

| Producer | Event | Consumers | Topic |
|----------|-------|-----------|-------|
| Financial Core | `JournalEntryCreated` | Reconciliation, Reporting, Audit | `financial-core.journal.entries` |
| Financial Core | `WalletBalanceUpdated` | Notification, Reporting | `financial-core.wallet.events` |
| Payment | `PaymentCompleted` | Transaction, Notification, Settlement, Audit | `payment.events` |
| Payment | `PaymentFailed` | Notification, Audit | `payment.events` |
| Refund | `RefundCompleted` | Transaction, Notification, Settlement, Audit | `refund.events` |
| Refund | `ChargebackResolved` | Transaction, Notification, Audit | `refund.events` |
| Identity | `UserRegistered` | Financial Core (create wallet), Notification | `identity.events` |
| Identity | `AccountFrozen` | Financial Core (freeze), Notification | `identity.events` |
| Compliance | `AccountRestrictionPlaced` | Payment (check before processing) | `compliance.events` |
| FX | `ExchangeRateUpdated` | Payment (cache), Reporting | `fx.events` |
| Settlement | `SettlementCompleted` | Notification, Reporting | `settlement.events` |
| Treasury | `LiquidityAlertRaised` | Notification (alert Finance) | `treasury.events` |

### 16.3 Payment Execution Flow

```
Client → API Gateway → Payment Service
  │
  │ [sync] Payment → Risk/Fraud: FraudCheck
  │ [sync] Payment → Compliance: LimitCheck
  │ [sync] Payment → Fee Engine: CalculateFee
  │
  │ [sync] Payment → Financial Core: CreateJournalEntry
  │   ┌────── SINGLE DB TX (financial_core_db) ──────┐
  │   │ INSERT journal_entries (header)               │
  │   │ INSERT journal_lines (DEBIT user, CREDIT      │
  │   │   merchant + CREDIT fee)                      │
  │   │ UPDATE wallet_balances (sender -amt)           │
  │   │ UPDATE wallet_balances (receiver +net)         │
  │   │ INSERT outbox_events (PaymentCompleted)        │
  │   │ COMMIT                                        │
  │   └───────────────────────────────────────────────┘
  │
  │ Payment state → COMPLETED
  │
  │ [async via Kafka outbox relay]
  │
  ├──→ Transaction Context:  subscribes → writes read model
  ├──→ Notification Context: subscribes → sends push/SMS
  ├──→ Audit Context:        subscribes → writes immutable log
  ├──→ Settlement Context:   subscribes → aggregates for EOD
  └──→ Reporting Context:    subscribes → updates dashboards
```

---

### 🛑 APPROVAL GATE → 📋 Document Review

**Reviewers**: Tech Lead + 1 Peer
**Checklist**:
- [ ] Financial Core merges Ledger + Wallet in single bounded context
- [ ] journal_entries + journal_lines model (multi-line)
- [ ] Refund Context separated from Payment
- [ ] Compliance/AML Context separated from Risk & Fraud
- [ ] FX promoted to Core Domain
- [ ] Treasury promoted to Core Domain
- [ ] Core / Supporting / Generic classification clear
- [ ] Payment state machine with AUTHORIZED state
- [ ] 15 movement types defined
- [ ] Domain boundary ≠ database boundary clarified
- [ ] Context map with relationship types
- [ ] Event flow: sync calls + async choreography
- [ ] JournalEntryCreated BEFORE WalletBalanceUpdated (same TX)
