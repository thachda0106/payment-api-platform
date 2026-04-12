# Phase 07 — Data Architecture

## MoMo-like Payment API Platform

> **Document Status**: Draft v1.0 — Architecture Review
> **Last Updated**: 2026-04-12  
> **Classification**: CONFIDENTIAL — Internal Use Only  
> **Audience**: Data Architects, Backend Engineers, DBA Team  
> **Input**: Phase 04 — Domain Design, Phase 06 — High-Level Architecture  
> **Author Level**: Principal Engineer  
> **Approval Gate**: 🏗️ Architecture Review Board (ARB) Final Sign-off

---

## 1. Goal

Design data models, storage strategies, data flows, partitioning, backup, and data governance for every bounded context. This ensures absolute financial consistency, horizontal scalability, and regulatory compliance at Tier-1 payment volumes.

---

## 2. Key Decisions

-   **Database Strategy**: PostgreSQL (Aurora I/O Optimized) is the primary relational store for transactional contexts. Redis is used *exclusively* for volatile caches, rate limits, and idempotent locking (with DB fallback). OpenSearch is used for cross-context search and CQRS read projections.
-   **Partitioning**: Composite Partitioning (Hash by `account_id` + Range by `created_at`) is mandated for high-growth tables.
-   **Financial Concurrency**: Rely exclusively on pessimistic DB row locks (`SELECT FOR UPDATE NOWAIT`) and DB `UNIQUE` constraints. Distributed caching (e.g., Redlock) is strictly forbidden for financial consistency boundaries.
-   **No Cross-Database Joins**: Contexts must pull required aggregate data upfront or use eventual consistency via Data Flows (Kafka).

---

## 3. Storage Type Matrix

| Bounded Context | Primary Database | Key Technologies | Rationale |
| :--- | :--- | :--- | :--- |
| **Financial Core** | `financial_core_db` | PostgreSQL, Redis | ACID guarantees for journals and ledger. Redis used only for account-level caching. |
| **Payment & Refund** | `payment_db` | PostgreSQL, Redis | Relational data for state machines and sagas; Redis for TTL-based idempotency. |
| **Risk & Fraud** | `fraud_db` | Redis, PostgreSQL | Redis for high-throughput velocity counters. Postgres for persistent rule sets and historical profiles. |
| **Identity & Merchant** | `account_db`, `merchant_db` | PostgreSQL | High-security isolation; infrequent writes but heavy read caching. |
| **FX & Treasury** | `fx_db`, `treasury_db` | PostgreSQL | Strongly consistent tracking of limits, rates, and multi-currency ledgers. |
| **Audit** | `audit_db` | TimescaleDB | Append-only hyper-tables optimized for time-series and strict retention. |
| **Transaction (Search)** | OpenSearch | OpenSearch | Read model projection populated via Kafka. Searchable up to T-365 days. |
| **Cold Storage** | Deep Archive | S3 (Parquet) | Events and legacy journals older than 365 days are moved here via Kafka Connect. |

---

## 4. Per-Service Data Models

### 4.1 Financial Core (`financial_core_db`)

The heart of the system. Implements multi-line journals mapping 1-to-N lines.

```sql
-- Partitioned by Hash(journal_id) to spread INSERT load, then Range(created_at) by month.
CREATE TABLE journal_entries (
    entry_id        UUID PRIMARY KEY,     -- Client-side generated UUIDv7 for precise chronos
    journal_id      UUID NOT NULL,        -- Group related cross-currency/multi-party entries
    reference_type  VARCHAR(50) NOT NULL, -- e.g., 'PAYMENT', 'REFUND'
    reference_id    UUID NOT NULL,
    movement_type   VARCHAR(30) NOT NULL, -- e.g., 'FX_SETTLEMENT', 'TOPUP'
    description     VARCHAR(500),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE, -- Absolute uniqueness guard
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(100) NOT NULL
);

CREATE INDEX idx_je_reference ON journal_entries (reference_type, reference_id);

CREATE TABLE journal_lines (
    line_id         UUID PRIMARY KEY,
    entry_id        UUID NOT NULL REFERENCES journal_entries(entry_id),
    account_id      VARCHAR(255) NOT NULL,
    entry_type      VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount          BIGINT NOT NULL CHECK (amount > 0),
    currency        CHAR(3) NOT NULL DEFAULT 'VND',
    line_order      INT NOT NULL DEFAULT 1
);

CREATE INDEX idx_jl_account ON journal_lines (account_id, created_at);

CREATE TABLE wallet_balances (
    account_id        VARCHAR(255) PRIMARY KEY,
    available_balance BIGINT NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    pending_balance   BIGINT NOT NULL DEFAULT 0,
    frozen_balance    BIGINT NOT NULL DEFAULT 0,
    currency          CHAR(3) NOT NULL DEFAULT 'VND',
    version           BIGINT NOT NULL DEFAULT 1 -- Optimistic locking
);
```

### 4.2 Payment Context (`payment_db`)

```sql
CREATE TABLE payments (
    payment_id      UUID PRIMARY KEY,
    payer_id        VARCHAR(255) NOT NULL,
    payee_id        VARCHAR(255) NOT NULL,
    amount          BIGINT NOT NULL CHECK (amount > 0),
    currency        CHAR(3) NOT NULL DEFAULT 'VND',
    status          VARCHAR(30) NOT NULL, -- 'INITIATED', 'VALIDATING', 'AUTHORIZED', 'COMPLETED'
    payment_type    VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- For Saga coordination
CREATE TABLE saga_states (
    saga_id           UUID PRIMARY KEY REFERENCES payments(payment_id),
    type              VARCHAR(50) NOT NULL,
    current_step      VARCHAR(50) NOT NULL,
    status            VARCHAR(30) NOT NULL,
    compensation_data JSONB,
    timeout_at        TIMESTAMPTZ
);
```

### 4.3 Outbox Table (Universal in all DBs)

```sql
CREATE TABLE outbox_events (
    event_id        UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ NULL
);

CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;
```

---

## 5. Advanced Partitioning Strategy

Standard time-based partitioning creates "Hot Partitions" at the end of the month, overloading a single disk. We implement **Composite Partitioning**.

1.  **Level 1: Hash Partitioning**:
    -   Tables like `journal_entries` and `journal_lines` are first partitioned by `HASH(account_id)` or `HASH(journal_id)` into 16 logical partitions.
    -   *Impact*: Spreads the physical insert IOPS evenly across multiple physical storage blocks at all times.
2.  **Level 2: Range Partitioning**:
    -   Each Hash partition is then partitioned by `RANGE (created_at)` into monthly chunks.
    -   *Impact*: Allows cheap drop of obsolete data (e.g., dropping data older than 2 years from hot storage without expensive `DELETE` operations).

---

## 6. Data Flows and CQRS

### 6.1 Transaction Event Logging Flow (Sync to Eventual)

1.  **Write Operation**: A payment executes. `financial_core_db` updates `wallet_balances`, writes `journal_entries`, and inserts an `outbox_events` record in a single transaction.
2.  **Relay**: Debezium/Kafka Connect tails the WAL (Write-Ahead Log) to read the `outbox_events` and streams messages to Kafka topics (e.g., `financial-core.journal.entries`).
3.  **Read Projection (OpenSearch)**: The `Transaction` Service consumes Kafka, joining user contexts, and indexing documents into OpenSearch for high-speed faceted search capabilities.
4.  **Deep Archive (S3)**: A secondary Kafka Connect S3 Sink consumes the same topic, buffering and writing Parquet files to S3 for unlimited historical retention (Compliance/BI).

---

## 7. Caching and Concurrency Control

### 7.1 Absolute Rule on Financial Synchronization

-   *What NOT to do*: Never map financial synchronization variables (wallet states, ledger balances) strictly in Redis. Cache invalidation anomalies cause split brains.
-   *What to do*: Lock rows actively at the DB level via `SELECT ... FOR UPDATE NOWAIT`. If node connection fails, transaction resets cleanly.

### 7.2 Redis for Idempotency TTL

-   **Flow**:
    1.  Request arrives with `Idempotency-Key` and `JWT.sub`.
    2.  Check Redis. If lock exists, map return. If missing, acquire lock with TTL 10 minutes.
    3.  Proceed down stack. Write to postgres, where `UNIQUE(idempotency_key)` serves as the ultimate constraint.
    4.  Update Redis with outcome payload, extend TTL to 24 hours.
-   **Why**: Guards the DB from burst collision abuse, while absolute guarantee remains at the ACID database layer.

---

## 8. Data Governance & PII Classification

Different types of columns require different regulatory control under PCI-DSS, GDPR, and SBV (State Bank of Vietnam) guidelines.

| Data Type | Example Columns | Classification | Encryption Strategy |
| :--- | :--- | :--- | :--- |
| **Financial Ledger** | `amount`, `normal_balance`, `created_at` | Confidential | Transparent Data Encryption (TDE) at Rest. |
| **Platform Ops** | `fee_config`, `merchant_webhook_url` | Internal | TDE at Rest. |
| **PII (Standard)**| `email`, `phone_number`, `home_address` | Highly Restricted | Application-Level Encryption (ALE) prior to DB write. Plaintext never hits the DB. |
| **PCI Data** | `card_number`, `cvv` | PCI-DSS Scoped | Stored *only* in PCI-compliant Tokenization vaults. App only sees tokens like `tok_1234`. |

---

## 9. Backup, DR, AND Retention Policies

### 9.1 RPO & RTO Targets

| System Tier | RPO (Data Loss max) | RTO (Downtime max) | Strategy |
| :--- | :--- | :--- | :--- |
| **Tier-0 (Financial Core)** | 0 seconds | < 5 mins | Synchronous multi-AZ commit. Async cross-region read-replicas. |
| **Tier-1 (Payment, Identity)** | < 1 second | < 10 mins | Async multi-AZ replication. |
| **Tier-2 (Reports)** | 1 hour | < 4 hours | Rebuilt from Kafka streams or S3 snapshots if required. |

### 9.2 Backup Mechanics

-   **Continuous Backup**: Aurora continuous PITR (Point-In-Time-Recovery) enabled for 35 days.
-   **Cross-Region Archiving**: Automated snapshots copied to DR Region (Region B) daily.
-   **Long-Term Retention**:
    -   Financial data must be kept for 7 years (Audit compliance).
    -   After 365 days, PostgreSQL scheduled jobs offload cold partitions to S3. They are dropped from the active Aurora cluster to reduce IOPS cost, searchable purely via AWS Athena.

---

## 10. Connection to Next Phase

This structural data architecture enables Phase 08 (API Design). The API Request and Response Data Transfer Objects (DTOs) will directly align with the bounded context schemas defined here.

### 🛑 APPROVAL GATE → 🏗️ Architecture Review

**Checklist**:
- [ ] Database per service aligns with Phase 04 domains.
- [ ] No `Redlock` used for wallet boundaries.
- [ ] Composite database partitioning documented.
- [ ] Redis caching clearly delineated from transactional locking.
- [ ] Outbox + Eventual consistency sync detailed.
- [ ] TimescaleDB assigned for Audit context.
