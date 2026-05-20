# Event Catalog — Cross-Cutting Index

## MoMo-like Payment API Platform

> **Source**: Phase 09 — Event Schema & Governance (v1.0)
> **Purpose**: Single-source index of all Kafka topics, event types, producers, consumers, and schemas
> **Audience**: All engineering teams
> **Last Updated**: 2026-05-20

---

## Topic Quick Reference

### Payments Domain

| Topic | Event Types | Producer | Consumers | Schema |
|-------|------------|----------|-----------|--------|
| `payments.payment.created` | `payment.created` | Payment Service (Outbox CDC) | Wallet Projector, Risk Engine, Webhook Sender, Notification Service, Search Indexer | `payments.payment-created-value` (FULL) |
| `payments.payment.succeeded` | `payment.succeeded` | Payment Service (Outbox CDC) | Wallet Projector, Webhook Sender, Notification Service, Settlement Service, Search Indexer | `payments.payment-succeeded-value` (FULL) |
| `payments.payment.failed` | `payment.failed` | Payment Service (Outbox CDC) | Notification Service, Search Indexer | `payments.payment-failed-value` (FULL) |
| `payments.payment.canceled` | `payment.canceled` | Payment Service (Outbox CDC) | Wallet Projector, Webhook Sender, Search Indexer | `payments.payment-canceled-value` (FULL) |

### Refunds Domain

| Topic | Event Types | Producer | Consumers | Schema |
|-------|------------|----------|-----------|--------|
| `refunds.refund.created` | `refund.created` | Refund Service (Outbox CDC) | Wallet Projector, Settlement Service, Webhook Sender, Search Indexer | `refunds.refund-created-value` (FULL) |
| `refunds.refund.succeeded` | `refund.succeeded` | Refund Service (Outbox CDC) | Wallet Projector, Webhook Sender, Notification Service, Search Indexer | `refunds.refund-succeeded-value` (FULL) |
| `refunds.refund.failed` | `refund.failed` | Refund Service (Outbox CDC) | Notification Service, Search Indexer | `refunds.refund-failed-value` (FULL) |

### Wallets Domain

| Topic | Event Types | Producer | Consumers | Schema |
|-------|------------|----------|-----------|--------|
| `wallets.balance.updated` | `wallet.balance.updated` | Wallet Projector (CDC trigger) | Search Indexer, Analytics Pipeline, Notification Service | `wallets.balance-updated-value` (BACKWARD) |
| `wallets.account.frozen` | `wallet.frozen` | Admin Service (Outbox CDC) | Risk Engine, Notification Service, Search Indexer | `wallets.account-frozen-value` (FULL) |
| `wallets.account.unfrozen` | `wallet.unfrozen` | Admin Service (Outbox CDC) | Risk Engine, Notification Service, Search Indexer | `wallets.account-unfrozen-value` (FULL) |
| `wallets.account.created` | `wallet.created` | Identity Service (Outbox CDC) | KYC Service, Analytics Pipeline | `wallets.account-created-value` (BACKWARD) |

### Ledger Domain (Internal)

| Topic | Event Types | Producer | Consumers | Schema |
|-------|------------|----------|-----------|--------|
| `ledger.entry.committed` | `ledger.entry.committed` | Payment/Refund Service (Outbox CDC) | Audit Service, Reconciliation Engine, Analytics Pipeline, Archive Service | `ledger.entry-committed-value` (FULL) |
| `ledger.balance.reconciled` | `ledger.balance.reconciled` | Reconciliation Job | Audit Service, Analytics Pipeline | `ledger.balance-reconciled-value` (BACKWARD) |

### Payouts Domain

| Topic | Event Types | Producer | Consumers | Schema |
|-------|------------|----------|-----------|--------|
| `payouts.payout.created` | `payout.created` | Payout Service (Outbox CDC) | Settlement Service, Webhook Sender, Notification Service | `payouts.payout-created-value` (FULL) |
| `payouts.payout.succeeded` | `payout.succeeded` | Payout Service (Outbox CDC) | Webhook Sender, Notification Service, Search Indexer | `payouts.payout-succeeded-value` (FULL) |
| `payouts.payout.failed` | `payout.failed` | Payout Service (Outbox CDC) | Notification Service, Search Indexer | `payouts.payout-failed-value` (FULL) |

### Notifications Domain

| Topic | Event Types | Producer | Consumers | Schema |
|-------|------------|----------|-----------|--------|
| `notifications.email.queued` | `email.queued` | Notification Service | Email Delivery Service | `notifications.email-queued-value` (BACKWARD) |
| `notifications.push.queued` | `push.queued` | Notification Service | Push Delivery Service (FCM/APNs) | `notifications.push-queued-value` (BACKWARD) |
| `notifications.webhook.delivered` | `webhook.delivered`, `webhook.failed` | Webhook Sender | Webhook Monitoring Dashboard | `notifications.webhook-delivered-value` (BACKWARD) |

### Platform Domain (Internal)

| Topic | Event Types | Producer | Consumers | Schema |
|-------|------------|----------|-----------|--------|
| `platform.audit.action` | `audit.action` | All services | Audit Service, SIEM, Compliance Archive | `platform.audit-action-value` (FULL) |

---

## Dead Letter Queues

| DLQ Topic | Source Domain | Retention | Alert Threshold |
|-----------|--------------|-----------|----------------|
| `payments.dlq` | Payments | 30 days | > 0 messages for > 5 min |
| `refunds.dlq` | Refunds | 30 days | > 0 messages for > 5 min |
| `wallets.dlq` | Wallets | 30 days | > 0 messages for > 5 min |
| `payouts.dlq` | Payouts | 30 days | > 0 messages for > 5 min |
| `notifications.dlq` | Notifications | 30 days | > 0 messages for > 5 min |
| `platform.dlq` | Platform | 90 days | > 0 messages for > 5 min |

---

## Consumer Mapping by Service

| Consumer Service | Subscribed Topics | Inbox Table |
|-----------------|-------------------|-------------|
| **Wallet Projector** | `payments.payment.created`, `payments.payment.succeeded`, `payments.payment.canceled`, `refunds.refund.created`, `refunds.refund.succeeded` | `wallet_projector_inbox` |
| **Risk Engine** | `payments.payment.created`, `wallets.account.frozen`, `wallets.account.unfrozen` | `risk_engine_inbox` |
| **Webhook Sender** | `payments.payment.succeeded`, `payments.payment.failed`, `payments.payment.canceled`, `refunds.refund.created`, `refunds.refund.succeeded`, `payouts.payout.created`, `payouts.payout.succeeded` | `webhook_sender_inbox` |
| **Notification Service** | `payments.payment.*`, `refunds.refund.succeeded`, `refunds.refund.failed`, `wallets.balance.updated`, `wallets.account.*`, `payouts.payout.*` | `notification_inbox` |
| **Search Indexer** | `payments.payment.*`, `refunds.refund.*`, `wallets.balance.updated`, `wallets.account.*`, `payouts.payout.*` | `search_indexer_inbox` |
| **Settlement Service** | `payments.payment.succeeded`, `refunds.refund.created`, `payouts.payout.created` | `settlement_inbox` |
| **Audit Service** | `ledger.entry.committed`, `ledger.balance.reconciled`, `platform.audit.action` | `audit_inbox` |
| **Reconciliation Engine** | `ledger.entry.committed`, `ledger.balance.reconciled` | `reconciliation_inbox` |
| **Analytics Pipeline** | `wallets.balance.updated`, `wallets.account.created`, `ledger.entry.committed`, `ledger.balance.reconciled` | N/A (idempotent via event_id in data warehouse) |
| **Email Delivery** | `notifications.email.queued` | `email_delivery_inbox` |
| **Push Delivery** | `notifications.push.queued` | `push_delivery_inbox` |

---

## Schema Registry Reference

| Schema Name | File | Compatibility | Version |
|-------------|------|---------------|---------|
| `payments.payment-created-value` | `schemas/payment-events.avsc` | FULL | 1 |
| `payments.payment-succeeded-value` | `schemas/payment-events.avsc` | FULL | 1 |
| `payments.payment-failed-value` | `schemas/payment-events.avsc` | FULL | 1 |
| `payments.payment-canceled-value` | `schemas/payment-events.avsc` | FULL | 1 |
| `refunds.refund-created-value` | `schemas/refund-events.avsc` | FULL | 1 |
| `refunds.refund-succeeded-value` | `schemas/refund-events.avsc` | FULL | 1 |
| `refunds.refund-failed-value` | `schemas/refund-events.avsc` | FULL | 1 |
| `wallets.balance-updated-value` | `schemas/wallet-events.avsc` | BACKWARD | 1 |
| `wallets.account-frozen-value` | `schemas/wallet-events.avsc` | FULL | 1 |
| `wallets.account-unfrozen-value` | `schemas/wallet-events.avsc` | FULL | 1 |
| `wallets.account-created-value` | `schemas/wallet-events.avsc` | BACKWARD | 1 |
| `ledger.entry-committed-value` | `schemas/ledger-events.avsc` | FULL | 1 |
| `ledger.balance-reconciled-value` | `schemas/ledger-events.avsc` | BACKWARD | 1 |
| `payouts.payout-created-value` | `schemas/payout-events.avsc` | FULL | 1 |
| `payouts.payout-succeeded-value` | `schemas/payout-events.avsc` | FULL | 1 |
| `payouts.payout-failed-value` | `schemas/payout-events.avsc` | FULL | 1 |
| `notifications.email-queued-value` | `schemas/notification-events.avsc` | BACKWARD | 1 |
| `notifications.push-queued-value` | `schemas/notification-events.avsc` | BACKWARD | 1 |
| `notifications.webhook-delivered-value` | `schemas/notification-events.avsc` | BACKWARD | 1 |
| `platform.audit-action-value` | `schemas/audit-events.avsc` | FULL | 1 |
