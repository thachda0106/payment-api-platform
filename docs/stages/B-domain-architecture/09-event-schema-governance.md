# Phase 09 — Event Schema & Governance

## MoMo-like Payment API Platform

> **Document Status**: Draft v1.0
> **Last Updated**: 2026-05-20
> **Classification**: CONFIDENTIAL — Internal Use Only
> **Audience**: Backend Engineers, Data Engineers, Platform Engineers, SRE
> **Input**: Phase 07 — Data Architecture (v5.0); Phase 08 — API Design (v3.0); Phase 06 — High-Level Architecture (v6.0)
> **Author Level**: Principal Event Architect
> **Approval Gate**: 🏗️ Architecture Review Board (ARB) Final Sign-off

---

## Table of Contents

1. [Goal & Scope](#1-goal--scope)
2. [Key Decisions](#2-key-decisions)
3. [Documents Produced](#3-documents-produced)
4. [Architecture Artifacts](#4-architecture-artifacts)
   - [4.1 Event-Driven Architecture Overview](#41-event-driven-architecture-overview)
   - [4.2 Event Catalog — Topic & Event Taxonomy](#42-event-catalog--topic--event-taxonomy)
   - [4.3 Event Envelope Standard](#43-event-envelope-standard)
   - [4.4 Schema Registry & Compatibility Rules](#44-schema-registry--compatibility-rules)
   - [4.5 Event Schemas by Domain](#45-event-schemas-by-domain)
   - [4.6 Event Flow Diagrams](#46-event-flow-diagrams)
   - [4.7 Dead Letter Queue & Poison Pill Handling](#47-dead-letter-queue--poison-pill-handling)
   - [4.8 Replay Strategy & Disaster Recovery](#48-replay-strategy--disaster-recovery)
   - [4.9 Event Retention & Archival](#49-event-retention--archival)
   - [4.10 PII & Data Classification in Events](#410-pii--data-classification-in-events)
5. [Example Deliverables](#5-example-deliverables)
6. [Key Questions](#6-key-questions)
7. [Implementation Tasks](#7-implementation-tasks)
8. [Common Mistakes](#8-common-mistakes)
9. [KPIs & Exit Criteria](#9-kpis--exit-criteria)
10. [Connection to Next Phase](#10-connection-to-next-phase)

---

## 1. Goal & Scope

### 1.1 Goal

Define the **complete event-driven backbone** of the Payment API Platform. Every domain event — from ledger commits to webhook notifications — is catalogued, schema-governed, and bound by strict compatibility rules. This phase ensures that the asynchronous communication fabric (Kafka topics, Avro schemas, consumer contracts) is as rigorously defined as the synchronous REST API surface from Phase 08.

### 1.2 Scope

- **18 Kafka topics** across 6 event domains
- **36 event types** with versioned Avro schemas
- **Schema Registry** (Confluent / Apicurio) governance rules: FORWARD, BACKWARD, FULL compatibility
- **Event envelope** standard: headers, metadata, tracing context
- **Outbox → CDC → Kafka** pipeline from PostgreSQL journal entries
- **Idempotent Inbox Pattern** for all consumers
- **Dead Letter Queue** (DLQ) strategy per topic
- **Replay mechanics**: targeted (by key) and catastrophic (by partition offset reset)
- **Event retention & archival**: 7-day Kafka retention → S3 Parquet cold storage
- **PII governance**: zero raw PII in event payloads

### 1.3 Input Alignment

| Upstream Phase | Event Schema Dependency |
|---------------|------------------------|
| **Phase 06 — High-Level Architecture** | Outbox pattern, Kafka partitioning (§9.2), idempotent inbox (§8.1), replay strategy (§8.1), Kafka → S3 archival (§13.1), DLQ traceability (§10.1) |
| **Phase 07 — Data Architecture** | Journal entry creation (`create_journal_entry`), CDC triggers (`sync_wallet_balances`, `verify_double_entry`), `idempotency_keys` table |
| **Phase 08 — API Design** | 12 webhook event types (§4.9.1), event envelope structure (§4.9.3), idempotency semantics (§4.5) |
| **Phase 05 — Security Architecture** | No raw PII in event payloads (§1.4), event audit trail, HMAC signing for partner callbacks |

---

## 2. Key Decisions

| # | Decision | Rationale | Trade-offs |
|---|----------|-----------|------------|
| D01 | **Avro + Schema Registry for All Internal Events** | Strongly-typed, binary-efficient, schema-evolution enforced at registration. Avoids runtime deserialization errors. | Adds operational dependency on Schema Registry; requires Avro tooling in all services. |
| D02 | **Outbox Pattern — No Dual Writes** | DB transaction commits the journal entry AND the outbox row atomically. CDC (Debezium) tails the outbox table into Kafka. Eliminates dual-write inconsistency. | Adds ~2ms write latency for outbox INSERT; requires Debezium connector management. |
| D03 | **Idempotent Inbox for ALL Consumers** | Every consumer: `INSERT INTO inbox (event_id) ON CONFLICT DO NOTHING` before processing. Guarantees exactly-once processing semantics atop at-least-once delivery. | Requires an `inbox` table per consumer DB; adds ~0.5ms processing overhead. |
| D04 | **FULL Compatibility for Financial Events; BACKWARD for Analytics** | Financial events (`payment.*`, `refund.*`, `ledger.*`) are immutable — no field can ever be removed (FULL). Analytics/notification events allow additive-only changes (BACKWARD). | Prevents accidental breaking changes on financial data; limits schema evolution flexibility on critical topics. |
| D05 | **Partition by `account_id` for Ordering; Sticky Partitioner for High-Throughput Merchants** | Account-scoped events need strict ordering → keyed by `account_id`. High-volume merchant settlement events use Sticky Partitioner to avoid single-partition hotspots (Phase 06 §9.2). | Per-account ordering guaranteed; merchant aggregation requires eventual-consistency readers. |
| D06 | **Event Envelope Standard: CloudEvents + Tracing** | Every event wrapped in a CloudEvents-compatible envelope with mandatory `traceparent` (W3C Trace Context) for cross-service observability. | Standardizes event metadata; adds ~200 bytes overhead per event. |
| D07 | **7-Day Kafka Retention → S3 Parquet Cold Storage** | Kafka stores 7 days (hot path → replay, debugging). Kafka Connect S3 Sink flushes to partitioned Parquet in S3 (infinite retention, queryable via Athena/Spark). | 7-day hot window limits replay scope; cold storage requires batch tooling. |
| D08 | **DLQ per Domain, Not per Service** | One DLQ topic per event domain (`payments-dlq`, `refunds-dlq`). Consumers across services share the same DLQ namespace. Simplifies operational triage. | Cross-service DLQ requires namespace discipline in error headers. |
| D09 | **Schema Version in Event Header** | Every event carries `schema-version: 1` in its CloudEvents header. Consumers can detect and handle version-specific logic without inspecting the payload. | Requires consumers to implement version-aware handlers. |
| D10 | **No Synchronous Event Chains** | A consumer of event A that emits event B does so asynchronously. No RPC-over-Kafka patterns (request-reply topics). | Prevents deadlocks; requires saga/compensating patterns for multi-step workflows. |

---

## 3. Documents Produced

| Document | Location | Status |
|----------|----------|--------|
| **Event Schema & Governance Reference** | `docs/stages/B-domain-architecture/09-event-schema-governance.md` (this document) | ✅ v1.0 |
| **Event Catalog (Index)** | `docs/cross-cutting/events/event-catalog.md` | 🚧 Pending |
| **Event Flow Diagrams** | `docs/cross-cutting/events/event-flows.md` | 🚧 Pending |
| **Avro Schema Files** | `docs/cross-cutting/events/schemas/*.avsc` | 🚧 Pending |
| **Payment Event Schema** | `docs/cross-cutting/events/schemas/payment-events.avsc` | 🚧 Pending |
| **Refund Event Schema** | `docs/cross-cutting/events/schemas/refund-events.avsc` | 🚧 Pending |
| **Wallet Event Schema** | `docs/cross-cutting/events/schemas/wallet-events.avsc` | 🚧 Pending |
| **Ledger Event Schema** | `docs/cross-cutting/events/schemas/ledger-events.avsc` | 🚧 Pending |
| **Notification Event Schema** | `docs/cross-cutting/events/schemas/notification-events.avsc` | 🚧 Pending |

---

## 4. Architecture Artifacts

### 4.1 Event-Driven Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SYNCHRONOUS REQUEST PATH                      │
│                                                                      │
│  Client ──► API Gateway ──► Payment Service ──► PostgreSQL           │
│                               │                                      │
│                               │  INSERT INTO outbox ...              │
│                               │  COMMIT (atomic: journal + outbox)   │
│                               ▼                                      │
├─────────────────────────────────────────────────────────────────────┤
│                        ASYNCHRONOUS EVENT PATH                       │
│                                                                      │
│  PostgreSQL outbox ──► Debezium CDC ──► Kafka ──► Consumers         │
│       │                     │               │         │              │
│       │                (binlog tail)    (topics)   ┌────┼────────┐   │
│       │                                            │    │        │   │
│       ▼                                            ▼    ▼        ▼   │
│  outbox table                              Wallet    Risk    Webhook │
│  (ordered writes)                          Project.  Engine  Sender  │
│                                            │         │        │      │
│                                            │         │        │      │
│                          ┌─────────────────┘         │        │      │
│                          ▼                           ▼        ▼      │
│                     PostgreSQL                Fraud rules   Partner  │
│                     (wallet_balances)         evaluation     URLs    │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│                        ERROR & OBSERVABILITY                         │
│                                                                      │
│  DLQ Topics ◄── Failed consumers ──► Alerting                       │
│  OpenSearch ◄── Logstash/Kafka Connect ──► Debugging, Trace queries  │
│  S3 Parquet ◄── Kafka Connect Sink ──► Athena / Spark cold queries  │
└─────────────────────────────────────────────────────────────────────┘
```

**Data Flow** (7 steps):

1. **API Request** → Payment Service calls `create_journal_entry` stored procedure.
2. **Atomic DB Commit**: Journal entry + outbox row committed in a single PostgreSQL transaction.
3. **CDC (Debezium)**: Tails the PostgreSQL WAL for the `outbox` table. Emits each new row as a Kafka message.
4. **Kafka Topic**: Message lands on the appropriate topic partition (keyed by `account_id` or sticky for merchants).
5. **Consumer Reads**: Each consumer (Wallet Projector, Risk Engine, Webhook Sender, Notification Service) reads from its assigned partition.
6. **Idempotent Inbox**: Consumer writes `event_id` to its local `inbox` table with `ON CONFLICT DO NOTHING`. Only new events proceed to processing.
7. **Side Effects**: Consumer updates projections (wallet_balances), evaluates risk rules, or sends webhooks. On failure → DLQ. On success → offset committed.

---

### 4.2 Event Catalog — Topic & Event Taxonomy

#### 4.2.1 Topic Naming Convention

```
{domain}.{entity}.{action}
```

| Component | Convention | Example |
|-----------|-----------|---------|
| `domain` | Bounded context: `payments`, `wallets`, `refunds`, `ledger`, `notifications`, `platform` | `payments` |
| `entity` | Entity/Domain object | `payment` |
| `action` | Past-tense verb | `created`, `succeeded`, `failed` |

#### 4.2.2 Topic Catalog

##### Domain: Payments

| Topic | Partition Key | Event Types | Consumers | Compatibility | Retention |
|-------|--------------|-------------|-----------|---------------|-----------|
| `payments.payment.created` | `account_id` (source) | `payment.created` | Wallet Projector, Risk Engine, Webhook Sender, Notification Service, Search Indexer | FULL | 7 days |
| `payments.payment.succeeded` | `account_id` (source) | `payment.succeeded` | Wallet Projector, Webhook Sender, Notification Service, Settlement Service, Search Indexer | FULL | 7 days |
| `payments.payment.failed` | `account_id` (source) | `payment.failed` | Notification Service, Search Indexer | FULL | 7 days |
| `payments.payment.canceled` | `account_id` (source) | `payment.canceled` | Wallet Projector, Webhook Sender, Search Indexer | FULL | 7 days |

##### Domain: Refunds

| Topic | Partition Key | Event Types | Consumers | Compatibility | Retention |
|-------|--------------|-------------|-----------|---------------|-----------|
| `refunds.refund.created` | `payment_id` | `refund.created` | Wallet Projector, Settlement Service, Webhook Sender, Search Indexer | FULL | 7 days |
| `refunds.refund.succeeded` | `payment_id` | `refund.succeeded` | Wallet Projector, Webhook Sender, Notification Service, Search Indexer | FULL | 7 days |
| `refunds.refund.failed` | `payment_id` | `refund.failed` | Notification Service, Search Indexer | FULL | 7 days |

##### Domain: Wallets

| Topic | Partition Key | Event Types | Consumers | Compatibility | Retention |
|-------|--------------|-------------|-----------|---------------|-----------|
| `wallets.balance.updated` | `account_id` | `wallet.balance.updated` | Search Indexer, Analytics Pipeline, Notification Service | BACKWARD | 30 days |
| `wallets.account.frozen` | `account_id` | `wallet.frozen` | Risk Engine, Notification Service, Search Indexer | FULL | 30 days |
| `wallets.account.unfrozen` | `account_id` | `wallet.unfrozen` | Risk Engine, Notification Service, Search Indexer | FULL | 30 days |
| `wallets.account.created` | `account_id` | `wallet.created` | KYC Service, Analytics Pipeline | BACKWARD | 30 days |

##### Domain: Ledger (Internal)

| Topic | Partition Key | Event Types | Consumers | Compatibility | Retention |
|-------|--------------|-------------|-----------|---------------|-----------|
| `ledger.entry.committed` | `account_id` | `ledger.entry.committed` | Audit Service, Reconciliation Engine, Analytics Pipeline, Archive Service | FULL | 7 days |
| `ledger.balance.reconciled` | `account_id` | `ledger.balance.reconciled` | Audit Service, Analytics Pipeline | BACKWARD | 30 days |

##### Domain: Payouts

| Topic | Partition Key | Event Types | Consumers | Compatibility | Retention |
|-------|--------------|-------------|-----------|---------------|-----------|
| `payouts.payout.created` | `merchant_id` (sticky) | `payout.created` | Settlement Service, Webhook Sender, Notification Service | FULL | 7 days |
| `payouts.payout.succeeded` | `merchant_id` (sticky) | `payout.succeeded` | Webhook Sender, Notification Service, Search Indexer | FULL | 7 days |
| `payouts.payout.failed` | `merchant_id` (sticky) | `payout.failed` | Notification Service, Search Indexer | FULL | 7 days |

##### Domain: Notifications (Fan-Out)

| Topic | Partition Key | Event Types | Consumers | Compatibility | Retention |
|-------|--------------|-------------|-----------|---------------|-----------|
| `notifications.email.queued` | `user_id` | `email.queued` | Email Delivery Service | BACKWARD | 3 days |
| `notifications.push.queued` | `user_id` | `push.queued` | Push Delivery Service (FCM/APNs) | BACKWARD | 3 days |
| `notifications.webhook.delivered` | `endpoint_id` | `webhook.delivered`, `webhook.failed` | Webhook Monitoring Dashboard | BACKWARD | 3 days |

##### Domain: Platform (Internal)

| Topic | Partition Key | Event Types | Consumers | Compatibility | Retention |
|-------|--------------|-------------|-----------|---------------|-----------|
| `platform.audit.action` | `user_id` | `audit.action` | Audit Service, SIEM, Compliance Archive | FULL | 90 days |

##### Dead Letter Queues (DLQ)

| DLQ Topic | Source Topics | Retention |
|-----------|--------------|-----------|
| `payments.dlq` | All `payments.*` topics | 30 days |
| `refunds.dlq` | All `refunds.*` topics | 30 days |
| `wallets.dlq` | All `wallets.*` topics | 30 days |
| `payouts.dlq` | All `payouts.*` topics | 30 days |
| `notifications.dlq` | All `notifications.*` topics | 30 days |
| `platform.dlq` | All `platform.*` topics | 90 days |

---

### 4.3 Event Envelope Standard

All events follow the **CloudEvents 1.0** specification with platform-specific extensions.

#### 4.3.1 CloudEvents Headers (Kafka Record Headers)

| Header | Required | Value / Example | Description |
|--------|:--------:|-----------------|-------------|
| `ce_id` | ✅ | `evt_aBc123DeF456` | Unique event identifier (UUID v7) |
| `ce_type` | ✅ | `payment.succeeded` | Event type (matches topic suffix) |
| `ce_source` | ✅ | `/v1/payments/pay_xyz987654` | URI identifying the event producer |
| `ce_specversion` | ✅ | `1.0` | CloudEvents specification version |
| `ce_time` | ✅ | `2026-05-20T10:30:00.000Z` | RFC 3339 event timestamp |
| `ce_subject` | ✅ | `pay_xyz987654` | Business entity identifier |
| `ce_datacontenttype` | ✅ | `application/avro` | Payload content type |
| `ce_dataschema` | ✅ | `https://schema-registry.platform.com/schemas/payment-succeeded/v1` | Schema Registry reference |
| `schema-version` | ✅ | `1` | Schema version (integer) |
| `traceparent` | ✅ | `00-4bf9...-a3c2...-01` | W3C Trace Context |
| `tracestate` | — | `vendor=...` | Vendor-specific trace data |
| `partition-key` | ✅ | `acc_001_wallet` | Kafka partition key value (for debugging) |

#### 4.3.2 Event Payload (Avro)

The Avro payload follows this structural pattern:

```json
{
  "id": "evt_aBc123DeF456",
  "type": "payment.succeeded",
  "time": "2026-05-20T10:30:00.000Z",
  "data": {
    // Domain-specific schema — see §4.5
  },
  "trigger": {
    "service": "payment-service",
    "instance": "payment-service-7d3f-abc",
    "request_id": "req_7f3aB9cD1eF2",
    "idempotency_key": "cb174dc0-2ed2-4b2a-bf35-a131015fc65e"
  }
}
```

**Mandatory Payload Fields**:
- `id`: CloudEvent ID (duplicated for Avro-native consumers)
- `type`: Event type string
- `time`: RFC 3339 timestamp
- `data`: Domain entity payload — the only variable part across event types
- `trigger`: Provenance information — which service, instance, and request produced this event

---

### 4.4 Schema Registry & Compatibility Rules

#### 4.4.1 Compatibility Modes

| Mode | Allowed Changes | Prohibited Changes | Applied To |
|------|----------------|-------------------|------------|
| **FULL** | No changes. Schema is immutable once registered. | All changes. | Financial topics: `payments.*`, `refunds.*`, `ledger.entry.*`, `payouts.*` |
| **BACKWARD** | Add optional fields (with defaults). Remove optional fields (only if no consumers read them). | Remove required fields. Change field types. Rename fields. | Analytics/notification topics: `wallets.balance.*`, `wallets.account.created`, `notifications.*`, `ledger.balance.*` |
| **FORWARD** | Not used. | — | — |
| **BACKWARD_TRANSITIVE** | Not used directly. | — | — |

**Rationale for FULL on financial events**: A `payment.succeeded` event is a legal record. If a field is added, old consumers cannot interpret it. If a field is removed, downstream auditors lose data. FULL compatibility prevents any accidental mutation of financial truth.

**Rationale for BACKWARD on analytics**: New optional fields (e.g., a new `device_fingerprint` on balance updates) are safe to add because consumers written for the old schema can still read the new schema (backward-compatible).

#### 4.4.2 Schema Registration Flow

1. Developer authors schema in `docs/cross-cutting/events/schemas/{domain}-events.avsc`.
2. PR opened → CI validates schema against compatibility rules.
3. Schema Registry (Confluent) compatibility check runs: passes if mode rules satisfied.
4. Merge → CI registers schema to Schema Registry.
5. Producer service updates to new schema version → starts emitting new events.
6. Consumer services update independently (backward-compatible mode ensures old consumers don't break).

#### 4.4.3 Schema Naming Convention

```
{domain}.{event-type}-value
```

| Schema Name | Example |
|-------------|---------|
| `payments.payment-created-value` | Schema for the value (payload) of `payment.created` events |
| `payments.payment-succeeded-value` | Schema for `payment.succeeded` |

---

### 4.5 Event Schemas by Domain

#### 4.5.1 Payment Events

**`payment.created`** (emitted when journal entry is created, before double-entry validation):
```json
{
  "payment_id": "pay_xyz987654",
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "amount": 15000,
  "currency": "VND",
  "description": "Coffee Purchase",
  "idempotency_key": "cb174dc0-2ed2-4b2a-bf35-a131015fc65e",
  "metadata": { "order_id": "ORD-2026-0001" },
  "created_at": 1713024000
}
```

**`payment.succeeded`** (emitted after double-entry validation passes):
```json
{
  "payment_id": "pay_xyz987654",
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "amount": 15000,
  "currency": "VND",
  "description": "Coffee Purchase",
  "ledger_entry_id": "entry_4fA1bC2dE3",
  "source_running_balance": 485000,
  "destination_running_balance": 115000,
  "metadata": { "order_id": "ORD-2026-0001" },
  "succeeded_at": 1713024001
}
```

**`payment.failed`** (emitted when journal entry or validation fails):
```json
{
  "payment_id": "pay_failed001",
  "source_account_id": "acc_001_wallet",
  "destination_account_id": "acc_002_merchant",
  "amount": 15000,
  "currency": "VND",
  "error_code": "insufficient_funds",
  "error_message": "The source account lacks sufficient funds for this transaction.",
  "failed_at": 1713024001
}
```

**`payment.canceled`** (emitted when a payment is reversed):
```json
{
  "payment_id": "pay_xyz987654",
  "canceled_at": 1713024200,
  "reason": "Customer requested cancellation",
  "reversal_entry_id": "entry_5gB2dE3fG4"
}
```

#### 4.5.2 Refund Events

**`refund.created`**:
```json
{
  "refund_id": "ref_def456789",
  "payment_id": "pay_xyz987654",
  "amount": 15000,
  "currency": "VND",
  "reason": "requested_by_customer",
  "idempotency_key": "fe817dc0-3ae3-5c3b-cg46-b242126gd76f",
  "metadata": {},
  "created_at": 1713025000
}
```

**`refund.succeeded`**:
```json
{
  "refund_id": "ref_def456789",
  "payment_id": "pay_xyz987654",
  "amount": 15000,
  "currency": "VND",
  "ledger_entry_id": "entry_6hC3eF4gH5",
  "succeeded_at": 1713025001
}
```

**`refund.failed`**:
```json
{
  "refund_id": "ref_failed001",
  "payment_id": "pay_xyz987654",
  "amount": 15000,
  "error_code": "payment_already_refunded",
  "error_message": "This payment has already been fully refunded.",
  "failed_at": 1713025001
}
```

#### 4.5.3 Wallet Events

**`wallet.balance.updated`** (CDC-triggered from `wallet_balances` table):
```json
{
  "account_id": "acc_001_wallet",
  "currency": "VND",
  "available_balance": 485000,
  "last_sequence": 42,
  "updated_at": 1713024001
}
```

**`wallet.frozen`**:
```json
{
  "account_id": "acc_001_wallet",
  "frozen_by": "admin_user_001",
  "reason": "Suspicious activity detected — Risk Engine flag #RF-042",
  "frozen_at": 1713024500
}
```

**`wallet.unfrozen`**:
```json
{
  "account_id": "acc_001_wallet",
  "unfrozen_by": "admin_user_002",
  "reason": "Risk review completed — no fraud confirmed",
  "unfrozen_at": 1713030000
}
```

#### 4.5.4 Ledger Events (Internal)

**`ledger.entry.committed`** (emitted by Outbox CDC after `create_journal_entry` commits):
```json
{
  "entry_id": "entry_4fA1bC2dE3",
  "journal_id": "jrn_abc123def456",
  "reference_type": "payment",
  "reference_id": "pay_xyz987654",
  "movement_type": "customer_payment",
  "lines": [
    {
      "line_id": "line_aaa111bbb222",
      "account_id": "acc_001_wallet",
      "entry_type": "DEBIT",
      "amount": 15000,
      "currency": "VND",
      "account_sequence": 42,
      "running_balance": 485000,
      "hash_chain": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    },
    {
      "line_id": "line_ccc333ddd444",
      "account_id": "acc_002_merchant",
      "entry_type": "CREDIT",
      "amount": 15000,
      "currency": "VND",
      "account_sequence": 15,
      "running_balance": 115000,
      "hash_chain": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
  ],
  "description": "Coffee Purchase",
  "created_by": "payment-service",
  "created_at": 1713024000
}
```

**`ledger.balance.reconciled`** (periodic reconciliation job result):
```json
{
  "account_id": "acc_001_wallet",
  "currency": "VND",
  "ledger_balance": 485000,
  "projected_balance": 485000,
  "drift": 0,
  "reconciled_sequence": 42,
  "reconciled_at": 1713100800
}
```

#### 4.5.5 Payout Events

**`payout.created`**:
```json
{
  "payout_id": "po_ghi789012",
  "merchant_id": "merchant_001",
  "source_account_id": "acc_002_merchant",
  "destination_bank_account": "BANK-VCB-1234567890",
  "amount": 100000,
  "currency": "VND",
  "idempotency_key": "gh817dc0-4bf4-6d4c-dh57-c353237he87g",
  "created_at": 1713026000
}
```

**`payout.succeeded`**:
```json
{
  "payout_id": "po_ghi789012",
  "settlement_reference": "BANK-TXN-20260520-001",
  "succeeded_at": 1713026100
}
```

**`payout.failed`**:
```json
{
  "payout_id": "po_failed001",
  "error_code": "bank_rejected",
  "error_message": "Destination bank account is closed.",
  "failed_at": 1713026100
}
```

---

### 4.6 Event Flow Diagrams

#### 4.6.1 Payment Success Flow (Happy Path)

```
┌──────────┐     ┌──────────────┐     ┌───────────────┐     ┌──────────────┐
│  Client  │────►│   Payment    │────►│  PostgreSQL    │     │   Debezium   │
│          │     │   Service    │     │  (financial_   │     │   (CDC)      │
│ POST /v1/│     │              │     │   core_db)     │     │              │
│ payments │     │ 1. Validate  │     │                │     │ 3. Tail WAL  │
└──────────┘     │ 2. Call SP   │     │  INSERT INTO   │     │  for outbox  │
                 │    create_   │     │  journal_      │     │  table       │
                 │    journal_  │     │  entries       │     │              │
                 │    entry()   │     │  INSERT INTO   │     │              │
                 └──────┬───────┘     │  journal_lines │     └──────┬───────┘
                        │             │  INSERT INTO   │            │
                        │             │  outbox        │            │
                        │             │  COMMIT        │            │
                        │             └───────────────┘            │
                        │                                           │
                        │◄──── 201 Created ─────────────────────────┘
                        │                                           │
                                                                    ▼
                                                              ┌──────────┐
                                                              │  Kafka   │
                                                              │  Topic:  │
                                                              │ payments │
                                                              │ .payment │
                                                              │ .created │
                                                              └────┬─────┘
                                                                   │
                    ┌──────────────────────────────────────────────┼────────────────────┐
                    │                  │                           │                    │
                    ▼                  ▼                           ▼                    ▼
            ┌──────────────┐  ┌──────────────┐          ┌──────────────┐    ┌──────────────┐
            │   Wallet     │  │    Risk      │          │   Webhook    │    │  Notification│
            │  Projector   │  │   Engine     │          │   Sender     │    │   Service    │
            │              │  │              │          │              │    │              │
            │ INSERT INTO  │  │ Evaluate     │          │ POST to      │    │ Send push    │
            │ inbox        │  │ fraud rules  │          │ partner URL  │    │ notification │
            │ ON CONFLICT  │  │              │          │ + signature  │    │ + email      │
            │ DO NOTHING   │  │              │          │              │    │              │
            └──────┬───────┘  └──────┬───────┘          └──────┬───────┘    └──────────────┘
                   │                 │                         │
                   ▼                 │                         │
            UPDATE                 │                         │
            wallet_balances        │                         │
            SET available_         │                         │
            balance = ...         │                         │
                                   │                         │
            Emit:                  │                         │
            wallet.balance.        │                         │
            updated                │                         │
                   │                 │                         │
                   ▼                 ▼                         ▼
            ┌──────────┐     ┌──────────┐            ┌──────────┐
            │  Kafka   │     │  Kafka   │            │  Kafka   │
            │ wallets  │     │ (no out- │            │  notifi- │
            │ .balance │     │  bound   │            │  cations │
            │ .updated │     │  event)  │            │ .webhook │
            └──────────┘     └──────────┘            │ .deliver │
                                                     └──────────┘
```

#### 4.6.2 Payment Failure Flow (Error Path)

```
Payment Service
       │
       │ create_journal_entry() ──► RAISE EXCEPTION 'insufficient_funds'
       │
       ▼
   INSERT INTO outbox (event_type: 'payment.failed', payload: {error_code: ...})
       │
       ▼
   Debezium CDC ──► Kafka: payments.payment.failed
       │
       ├──► Notification Service ──► Push notification to user: "Payment failed"
       ├──► Search Indexer ──► OpenSearch: payment status = failed
       └──► Webhook Sender ──► POST to partner URL: payment.failed event
```

#### 4.6.3 Refund Flow

```
POST /v1/payments/{id}/refunds
       │
       ▼
   Refund Service ──► create_journal_entry (reverse entry)
       │
       ├──► Outbox: refund.created
       │       │
       │       ├──► Wallet Projector ──► Update wallet_balances
       │       ├──► Webhook Sender ──► POST refund.created to merchant URL
       │       └──► Settlement Service ──► Queue settlement adjustment
       │
       ▼
   Double-entry validation passes
       │
       ▼
   Outbox: refund.succeeded
       │
       ├──► Wallet Projector ──► Finalize balance
       ├──► Webhook Sender ──► POST refund.succeeded
       └──► Notification Service ──► Email receipt to customer
```

---

### 4.7 Dead Letter Queue & Poison Pill Handling

#### 4.7.1 DLQ Routing Rules

| Failure Mode | Detection | DLQ Action | Alert |
|-------------|-----------|------------|-------|
| **Schema deserialization error** | Consumer throws `SerializationException` | Route to DLQ immediately (no retry). Include raw bytes in DLQ payload for debugging. | P1 — Schema Registry alert |
| **Business logic exception** | Consumer throws domain exception (e.g., `AccountNotFound`) | Retry 3 times with exponential backoff (1s, 5s, 25s). Then DLQ. | P2 |
| **Transient downstream failure** | HTTP 503 / DB connection timeout | Retry 5 times with exponential backoff + jitter (1s, 2s, 4s, 8s, 16s). Then DLQ. | P2 after 5th failure |
| **Poison pill (always fails)** | Same `event_id` fails > 3 times across retry cycles | DLQ + pause consumer partition. Requires manual intervention to skip offset. | P1 |
| **Consumer timeout** | Processing exceeds `max.poll.interval.ms` (5 min) | Kafka triggers rebalance. Consumer rejoins and retries. After 3 rebalances, DLQ. | P1 |

#### 4.7.2 DLQ Message Envelope

Each DLQ message wraps the original event with error metadata:

```json
{
  "original_event_id": "evt_aBc123DeF456",
  "original_topic": "payments.payment.succeeded",
  "original_partition": 3,
  "original_offset": 1245789,
  "original_key": "acc_001_wallet",
  "original_headers": { "ce_type": "payment.succeeded", "traceparent": "00-4bf9..." },
  "original_payload": "<raw bytes base64>",
  "error": {
    "consumer_service": "webhook-sender",
    "consumer_instance": "webhook-sender-7d3f-abc",
    "exception_type": "java.net.ConnectTimeoutException",
    "exception_message": "Connection to partner URL https://partner.example.com/webhook timed out after 10000ms",
    "stack_trace": "<truncated to 4KB>",
    "retry_count": 5,
    "first_failure_at": "2026-05-20T10:30:05.000Z",
    "last_failure_at": "2026-05-20T10:30:45.000Z"
  },
  "routed_to_dlq_at": "2026-05-20T10:30:45.500Z"
}
```

#### 4.7.3 DLQ Operational Workflow

1. **Alert fires**: DLQ message count > 0 for > 5 minutes triggers P2 alert.
2. **Triage**: On-call engineer queries DLQ topic via Kafka console consumer or DLQ dashboard.
3. **Diagnosis**: Check `error.exception_message` and `traceparent` in OpenSearch.
4. **Resolution**:
   - **Transient**: Replay the original event from DLQ → original topic (skip inbox — already processed).
   - **Data bug**: Fix data, replay event.
   - **Code bug**: Fix code, deploy, replay all DLQ events for that consumer group.
   - **Schema bug**: Fix schema, register new version, replay.
5. **Close**: DLQ message acknowledged (offset committed) after successful replay.

---

### 4.8 Replay Strategy & Disaster Recovery

#### 4.8.1 Replay by Key (Targeted Fix)

**Use Case**: A single payment failed to trigger a webhook. The event was processed by Wallet Projector and Risk Engine, but Webhook Sender was down.

**Procedure**:
1. Identify `payment_id` from the support ticket.
2. Query the outbox table or OpenSearch for the event: `SELECT * FROM outbox WHERE reference_id = 'pay_xyz987654'`.
3. Manually re-emit the event to `payments.payment.succeeded` topic using the same `event_id`.
4. Webhook Sender's idempotent inbox recognizes the event as new → processes → delivers webhook.
5. Wallet Projector and Risk Engine idempotent inboxes deduplicate → no double-processing.

#### 4.8.2 Replay by Partition Offset (Catastrophic Bug)

**Use Case**: A bug in the Wallet Projector caused incorrect `wallet_balances` for 4 hours across all accounts on partition 5.

**Procedure** (Per Phase 06 §8.1):
1. Fix the code, deploy the corrected Wallet Projector.
2. Identify the offset range: first broken offset to last broken offset on partition 5.
3. Stop the consumer group for Wallet Projector.
4. Reset consumer group offset: `kafka-consumer-groups.sh --reset-offsets --to-offset {first_broken_offset} --topic wallets.balance.updated:5 --execute`.
5. Restart consumer. It reprocesses from the first broken offset.
6. All events between `first_broken_offset` and `last_broken_offset` are replayed.
7. The idempotent inbox deduplicates any events that were already correctly processed.
8. Monitor `wallet_balances` drift: reconciliation job confirms 0 drift after replay.

#### 4.8.3 Full Topic Replay (Region Failover)

**Use Case**: Region A fails. Region B activates. Kafka MirrorMaker 2 has replicated all topics. Consumers in Region B start from the last committed offset.

**Procedure**:
1. Region B consumers join the same consumer group.
2. Kafka reassigns partitions. Consumers resume from the last committed offset.
3. Max 30 seconds of data lost (RPO, per Phase 06 §12). Reconciliation job detects and repairs.
4. Once Region A recovers, consumers fail back. MirrorMaker 2 handles bidirectional sync.

---

### 4.9 Event Retention & Archival

| Tier | Storage | Retention | Purpose | Access Pattern |
|------|---------|-----------|---------|---------------|
| **Hot (Kafka)** | Kafka brokers (EBS gp3) | 7 days (financial), 30 days (wallets/audit), 3 days (notifications) | Real-time consumption, replay, debugging | Low-latency random access by offset |
| **Warm (S3 Parquet)** | S3 Standard | 1 year | Analytics, compliance queries, reconciliation | Athena / Spark SQL batch queries |
| **Cold (S3 Glacier)** | S3 Glacier Deep Archive | 7 years (regulatory) | Regulatory audits, legal holds | Restore (12-48 hours), then query |

**Archival Pipeline**:
```
Kafka ──► Kafka Connect S3 Sink ──► S3 (Parquet, partitioned by topic/yyyy/mm/dd/)
                                          │
                                          ▼
                                     AWS Glue Crawler ──► Athena Table (queryable)
```

**Partition Structure in S3**:
```
s3://payment-platform-events/
  payments.payment.succeeded/
    year=2026/month=05/day=20/
      payments.payment.succeeded+0+0000000000.parquet
      payments.payment.succeeded+0+0000000100.parquet
      ...
```

---

### 4.10 PII & Data Classification in Events

Per Phase 05 §1.4: **No raw PII in event payloads.**

| Data Classification | Allowed in Events? | Example | Handling |
|--------------------|:------------------:|---------|----------|
| **Public** | ✅ | `payment_id`, `amount`, `currency`, `status` | As-is |
| **Internal** | ✅ | `account_id`, `ledger_entry_id`, `hash_chain` | As-is (internal-only topics) |
| **Confidential** | ❌ | User email, phone number, full name | Reference by `user_id`; lookup in Identity Service if needed |
| **Restricted (PII)** | ❌ | National ID, bank account number, card PAN | NEVER in any event. Tokenized reference only. |
| **Secrets** | ❌ | API keys, passwords, webhook secrets | NEVER in any event. |

**Enforcement**:
- Schema Registry rejects schemas containing PII field names (`email`, `phone`, `pan`, `ssn`, `national_id`).
- CI pipeline runs a PII field name scanner against all `.avsc` files.
- Outbox projection query selects only approved columns — PII columns excluded at the DB query level.

---

## 5. Example Deliverables

### 5.1 Event: `payment.succeeded` (Full Wire Format)

**Kafka Record Headers**:
```
ce_id: evt_aBc123DeF456
ce_type: payment.succeeded
ce_source: /v1/payments/pay_xyz987654
ce_specversion: 1.0
ce_time: 2026-05-20T10:30:00.000Z
ce_subject: pay_xyz987654
ce_datacontenttype: application/avro
ce_dataschema: https://schema-registry.platform.com/schemas/payments.payment-succeeded-value/v1
schema-version: 1
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
partition-key: acc_001_wallet
```

**Kafka Record Value** (Avro binary → decoded JSON):
```json
{
  "id": "evt_aBc123DeF456",
  "type": "payment.succeeded",
  "time": "2026-05-20T10:30:00.000Z",
  "data": {
    "payment_id": "pay_xyz987654",
    "source_account_id": "acc_001_wallet",
    "destination_account_id": "acc_002_merchant",
    "amount": 15000,
    "currency": "VND",
    "description": "Coffee Purchase",
    "ledger_entry_id": "entry_4fA1bC2dE3",
    "source_running_balance": 485000,
    "destination_running_balance": 115000,
    "metadata": {
      "order_id": "ORD-2026-0001"
    },
    "succeeded_at": 1713024001
  },
  "trigger": {
    "service": "payment-service",
    "instance": "payment-service-7d3f-abc",
    "request_id": "req_7f3aB9cD1eF2",
    "idempotency_key": "cb174dc0-2ed2-4b2a-bf35-a131015fc65e"
  }
}
```

### 5.2 Idempotent Inbox — Consumer SQL Pattern

```sql
-- Each consumer maintains its own inbox table
CREATE TABLE consumer_inbox (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(255) NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Consumer processing logic
BEGIN;
    -- Attempt insert — silently skip if already processed
    INSERT INTO consumer_inbox (event_id, event_type, status)
    VALUES ('evt_aBc123DeF456', 'payment.succeeded', 'PROCESSING')
    ON CONFLICT (event_id) DO NOTHING;

    -- Check if this is the row we just inserted (not a conflict skip)
    IF FOUND THEN
        -- Process the event: update projection, send notification, etc.
        PERFORM process_payment_succeeded(payload);
        
        -- Mark complete
        UPDATE consumer_inbox
        SET status = 'COMPLETED', processed_at = NOW()
        WHERE event_id = 'evt_aBc123DeF456';
    END IF;
COMMIT;
```

### 5.3 Consumer Retry + DLQ Pattern (Pseudocode)

```python
MAX_RETRIES = 5
RETRY_BACKOFF = [1, 2, 4, 8, 16]  # seconds

def consume_event(event):
    retry_count = 0
    while retry_count < MAX_RETRIES:
        try:
            # 1. Idempotency check
            if inbox_already_processed(event.id):
                return  # ACK, skip
                
            # 2. Insert inbox (ON CONFLICT DO NOTHING)
            inbox_insert(event.id, status='PROCESSING')
            
            # 3. Process business logic
            process_business_logic(event)
            
            # 4. Mark complete
            inbox_update(event.id, status='COMPLETED')
            return  # ACK
            
        except TransientException as e:
            retry_count += 1
            sleep(RETRY_BACKOFF[retry_count - 1] + random_jitter())
            
        except (BusinessException, FatalException) as e:
            # Non-retryable — send directly to DLQ
            send_to_dlq(event, error=e, retry_count=0)
            return  # ACK (offset committed, event preserved in DLQ)
    
    # Exhausted retries — send to DLQ
    send_to_dlq(event, error=last_exception, retry_count=MAX_RETRIES)
    return  # ACK
```

---

## 6. Key Questions

| # | Question | Answer |
|---|----------|--------|
| Q1 | Why Avro and not Protobuf or JSON Schema? | Avro has the strongest schema registry integration (Confluent), native compatibility enforcement, and binary encoding for efficient Kafka storage. Protobuf is preferred for gRPC services (which we don't use for events). JSON Schema lacks binary encoding. |
| Q2 | What happens if Schema Registry is down? | Producers cache the latest schema ID locally. They can continue producing as long as the schema hasn't changed. Consumers also cache schemas. A Schema Registry outage does not halt the event pipeline — only schema registration and new consumer startup are blocked. |
| Q3 | How are schema-breaking changes handled for FULL-compatibility topics? | You don't change the schema. Instead, you create a **new event type** (e.g., `payment.succeeded.v2`) on a new topic (`payments.payment.succeeded.v2`). Consumers subscribe to both topics during migration. Once all consumers migrate, the old topic is deprecated. |
| Q4 | What prevents a consumer from falling behind indefinitely? | `max.poll.interval.ms` = 5 min. If processing exceeds this, the consumer is kicked from the group, triggering a rebalance. Consumer lag alerts fire at > 10,000 messages behind. |
| Q5 | How do we guarantee event ordering within a partition? | Kafka guarantees order within a partition. All events for a given `account_id` go to the same partition (keyed by `account_id`). A consumer processes exactly one partition → strict ordering for that account. |
| Q6 | How are outbox table bloat and CDC lag managed? | Outbox rows are deleted after Debezium confirms delivery (tombstone cleanup). A scheduled job purges rows older than 7 days. CDC lag alert fires at > 60 seconds. |
| Q7 | Can events be consumed by non-JVM services? | Yes. Avro is language-agnostic. Schema Registry provides REST APIs for schema retrieval. Consumers in Python, Go, Node.js use their language's Avro library + Schema Registry client. |
| Q8 | What happens to events during a Kafka broker failure? | Topics have `replication.factor = 3`, `min.insync.replicas = 2`. A single broker failure causes no data loss. Producer `acks = all` ensures durability. |

---

## 7. Implementation Tasks

### P0 — Critical Path (Must Complete Before ARB Sign-off)

- [ ] **T01**: Define Avro schemas for all 21 event types in `docs/cross-cutting/events/schemas/` as `.avsc` files.
- [ ] **T02**: Register all schemas in Schema Registry (Confluent) with appropriate compatibility modes (FULL for financial, BACKWARD for analytics).
- [ ] **T03**: Create the Event Catalog document (`docs/cross-cutting/events/event-catalog.md`) — index of all topics, event types, owners, and schemas.
- [ ] **T04**: Create Event Flow Diagrams (`docs/cross-cutting/events/event-flows.md`) — Mermaid diagrams for payment success, payment failure, refund, payout, and wallet flows.
- [ ] **T05**: Document the Outbox table schema and CDC configuration (Debezium connector settings, topic routing, transformation rules).
- [ ] **T06**: Configure CI pipeline: Avro schema validation, compatibility check, PII field scanner against `.avsc` files.

### P1 — Required Before Phase 17 (Vertical Slice)

- [ ] **T07**: Implement the Outbox table in `financial_core_db` (Phase 07 DB migration).
- [ ] **T08**: Deploy Debezium CDC connector for `outbox` table → Kafka topic routing.
- [ ] **T09**: Implement idempotent inbox pattern in all consumer services (Wallet Projector, Webhook Sender, Notification Service).
- [ ] **T10**: Implement DLQ routing logic in consumer framework (retry counts, backoff, DLQ producer).
- [ ] **T11**: Implement the Schema Registry client in all producer/consumer services.
- [ ] **T12**: Deploy Kafka Connect S3 Sink for archival pipeline.

### P2 — Required Before Phase 25 (Production Readiness)

- [ ] **T13**: Implement replay tooling — "replay by key" CLI and "replay by partition offset" script.
- [ ] **T14**: Implement DLQ dashboard (Kafka UI / Confluent Control Center) for operational triage.
- [ ] **T15**: Deploy consumer lag monitoring and alerting (Prometheus + Grafana).
- [ ] **T16**: Implement reconciliation job that compares ledger entries to event stream for missing events.
- [ ] **T17**: Configure Kafka MirrorMaker 2 for cross-region event replication (DR).

---

## 8. Common Mistakes

### 8.1 Design Mistakes

| Mistake | Consequence | Prevention |
|---------|-------------|-----------|
| **Dual writes (DB + Kafka without Outbox)** | If DB commit succeeds but Kafka produce fails → lost event. If Kafka produce succeeds but DB commit fails → phantom event. | ALWAYS use the Outbox pattern. DB commit and event emission must be atomic. |
| **No idempotent inbox** | Consumer processes same event twice → double notification, double balance update. | EVERY consumer implements `INSERT INTO inbox ON CONFLICT DO NOTHING`. |
| **BLOCKING calls in consumer** | Consumer calls an external HTTP API synchronously. API is slow → consumer lag builds → rebalance triggered → processing stops. | Non-blocking I/O or bounded thread pools. External calls go through a separate async client with configurable timeouts. |
| **Ignoring schema compatibility** | Adding a required field without default → old consumers crash on deserialization → entire consumer group halts. | Schema Registry compatibility check in CI blocks incompatible changes. FULL mode on financial topics prevents all changes. |
| **PII in event payloads** | `customer_email` in a `payment.succeeded` event → PII leaks into Kafka logs, S3 archives, DLQ → GDPR violation. | PII field scanner in CI. Schema Registry naming convention review. |
| **Sync request-reply over Kafka** | Service A sends event to topic X, waits for response on topic Y. Timeout → retry → duplicate events → chaos. | No synchronous event chains. Use sagas with compensating transactions. |

### 8.2 Operational Mistakes

| Mistake | Consequence | Prevention |
|---------|-------------|-----------|
| **Kafka retention too short** | Retention = 1 hour. Bug discovered after 2 hours → events gone → cannot replay. | 7-day minimum for financial topics. |
| **No DLQ** | Poison pill event blocks entire partition → consumer stuck → lag builds → no events processed. | DLQ per domain, automatic routing after max retries, alert on DLQ message count. |
| **Single partition for high-volume topic** | All merchant payouts go to partition 0 → 1 consumer thread bottlenecked → lag. | Sticky Partitioner for high-volume merchant topics (Phase 06 §9.2). |
| **Manual offset reset without coordination** | Engineer resets offset on a running consumer → duplicate processing → incorrect balances. | Stop consumer group before reset. Coordinate via incident management. |

---

## 9. KPIs & Exit Criteria

| # | Criterion | Target | Measurement |
|---|-----------|--------|-------------|
| K01 | Schema coverage | 100% of event types have registered Avro schemas in Schema Registry | Schema Registry API check |
| K02 | Compatibility enforcement | 0 incompatible schema registrations allowed | CI pipeline compliance check |
| K03 | PII-free schemas | 0 PII field names detected in any `.avsc` file | CI PII scanner |
| K04 | Idempotent inbox coverage | 100% of event consumers implement inbox dedup | Code review + integration test |
| K05 | DLQ routing | All consumer topics have a corresponding DLQ topic | Kafka topic inventory |
| K06 | Outbox pattern | 0 dual-write (DB + Kafka) occurrences in codebase | Static analysis rule |
| K07 | Schema Registry HA | Schema Registry cluster ≥ 2 nodes, local schema caching in all services | Infrastructure check |
| K08 | Event documentation | Event Catalog and Event Flows documents published | Document review |

**Exit Gate**: All K01–K08 must be ✅ before ARB sign-off.

---

## 10. Connection to Next Phase

| Downstream Phase | How Event Schema Connects |
|-----------------|------------------------|
| **Phase 10 — System Flows** | The C4, sequence, and flow diagrams reference specific Kafka topics and event types from this catalog. Every "event emitted" step in a flow diagram maps to an entry in §4.2. |
| **Phase 12 — Infrastructure Design** | Kafka cluster sizing (brokers, partitions, replication factor), Debezium connector deployment, Schema Registry HA configuration, S3 archival pipeline are all derived from the topic catalog and retention policies in this phase. |
| **Phase 13 — Platform Core** | The `@app/core` library implements: CloudEvents envelope builder, Avro serializer/deserializer, inbox pattern utility, DLQ routing abstraction, and schema registry client — all based on the standards defined here. |
| **Phase 14 — Testing Strategy** | Contract tests verify producer/consumer Avro schema compatibility. Integration tests validate the outbox → CDC → Kafka → consumer pipeline. Chaos tests verify DLQ routing and replay mechanics. |
| **Phase 16 — CI/CD** | Schema compatibility checks, PII field scanning, and Kafka Connect connector configuration are automated CI pipeline steps. |
| **Phase 17 — Vertical Slice** | The first E2E flow validates the full event pipeline: API → DB outbox → Debezium CDC → Kafka → consumer inbox → projection update. |
| **Phase 20 — Observability** | Consumer lag metrics, DLQ message counts, CDC lag, and event trace queries are configured in Grafana dashboards. |
| **Phase 24 — Multi-Region DR** | Kafka MirrorMaker 2 configuration for cross-region topic replication. Consumer group failover from active to passive region. |

---

### 🛑 APPROVAL GATE → 🏗️ Architecture Review Board

**Checklist**:

- [ ] All 18 topics + 6 DLQ topics defined and documented
- [ ] All 21 event types have Avro schemas in `docs/cross-cutting/events/schemas/`
- [ ] Schema Registry compatibility modes assigned and enforced in CI
- [ ] Event envelope (CloudEvents + tracing) standardized across all topics
- [ ] Idempotent inbox pattern documented for all consuming services
- [ ] DLQ strategy documented: routing rules, retry policies, operational workflow
- [ ] Replay strategy documented: by-key, by-offset, full topic
- [ ] Event retention & archival pipeline defined
- [ ] PII governance rules enforced in schema CI
- [ ] Event Catalog and Event Flow diagrams published
