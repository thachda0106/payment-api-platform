# Event Flow Diagrams — Cross-Cutting Reference

## MoMo-like Payment API Platform

> **Source**: Phase 09 — Event Schema & Governance (v1.0)
> **Purpose**: Visual end-to-end event flow diagrams for all critical business processes
> **Audience**: All engineering teams
> **Last Updated**: 2026-05-20

> **⚠️ Implementation note (Phase-9 alignment, 2026-07-10):** The implemented platform keeps a
> **serial chain** `payment-service → fraud-service → financial-core (ledger) → notification-service`
> because ledger posting depends on the fraud decision. The parallel-consumer diagrams below
> (Risk Engine, Wallet Projector, etc.) are the target design for future services and run
> **alongside** the serial chain, not as a replacement. Preserving the serial order is a
> product decision recorded in `PLAN-phase9-alignment.md` (OQ-C).

---

## 1. Payment Success Flow (Happy Path)

```mermaid
sequenceDiagram
    actor Client
    participant API as API Gateway
    participant PS as Payment Service
    participant DB as PostgreSQL
    participant CDC as Debezium CDC
    participant Kafka
    participant WP as Wallet Projector
    participant RE as Risk Engine
    participant WS as Webhook Sender
    participant NS as Notification Service
    participant SI as Search Indexer
    participant Partner as Partner URL

    Client->>API: POST /v1/payments (Idempotency-Key)
    API->>PS: Forward (JWT validated)
    PS->>DB: CALL create_journal_entry(idempotency_key, ...)
    DB->>DB: INSERT journal_entries
    DB->>DB: INSERT journal_lines (DEBIT + CREDIT)
    DB->>DB: INSERT outbox (event: payment.created)
    DB->>DB: COMMIT
    DB-->>PS: Success

    Note over PS,DB: Atomic: journal + outbox

    PS-->>API: 201 Created
    API-->>Client: { payment_id, status: succeeded }

    CDC->>DB: Tail WAL (outbox table)
    CDC->>Kafka: Produce: payments.payment.created (key: account_id)

    par Parallel Consumers
        Kafka->>WP: Consume: payment.created
        WP->>WP: Inbox dedup (ON CONFLICT DO NOTHING)
        WP->>DB: UPDATE wallet_balances (source, destination)
        WP->>Kafka: Produce: wallets.balance.updated
    and
        Kafka->>RE: Consume: payment.created
        RE->>RE: Inbox dedup
        RE->>RE: Evaluate fraud rules
    and
        Kafka->>WS: Consume: payment.created
        WS->>WS: Inbox dedup
        WS->>Partner: POST webhook (payment.created)
        Partner-->>WS: 200 OK
    and
        Kafka->>NS: Consume: payment.created
        NS->>NS: Inbox dedup
        NS->>NS: Queue push notification
    and
        Kafka->>SI: Consume: payment.created
        SI->>SI: Inbox dedup
        SI->>SI: Index to OpenSearch
    end
```

---

## 2. Payment Failure Flow (Insufficient Funds)

```mermaid
sequenceDiagram
    actor Client
    participant API as API Gateway
    participant PS as Payment Service
    participant DB as PostgreSQL
    participant CDC as Debezium CDC
    participant Kafka
    participant NS as Notification Service
    participant SI as Search Indexer
    participant WS as Webhook Sender
    participant Partner as Partner URL

    Client->>API: POST /v1/payments
    API->>PS: Forward
    PS->>DB: CALL create_journal_entry(...)

    DB->>DB: BEGIN
    DB->>DB: INSERT idempotency_keys
    DB->>DB: INSERT journal_entries
    DB->>DB: FOR each line: check balance
    DB-->>DB: RAISE EXCEPTION 'insufficient_funds'
    DB->>DB: ROLLBACK (journal NOT committed)
    DB->>DB: INSERT outbox (event: payment.failed, error_code)
    DB->>DB: COMMIT (outbox ONLY)

    PS-->>API: 422 Unprocessable Entity
    API-->>Client: { error: { code: insufficient_funds } }

    CDC->>DB: Tail WAL (outbox table)
    CDC->>Kafka: Produce: payments.payment.failed

    par Failure Consumers
        Kafka->>NS: Consume: payment.failed
        NS->>NS: Inbox dedup
        NS->>NS: Send push: "Payment failed — insufficient funds"
    and
        Kafka->>SI: Consume: payment.failed
        SI->>SI: Index payment status = failed
    and
        Kafka->>WS: Consume: payment.failed
        WS->>Partner: POST webhook (payment.failed)
    end
```

---

## 3. Refund Flow

```mermaid
sequenceDiagram
    actor Merchant
    participant API as API Gateway
    participant RS as Refund Service
    participant DB as PostgreSQL
    participant CDC as Debezium CDC
    participant Kafka
    participant WP as Wallet Projector
    participant WS as Webhook Sender
    participant NS as Notification Service

    Merchant->>API: POST /v1/payments/{id}/refunds
    API->>RS: Forward (JWT validated)
    RS->>RS: Validate: payment.status == succeeded, refundable amount > 0
    RS->>DB: CALL create_journal_entry (reverse entry)
    DB->>DB: INSERT journal_lines (CREDIT source, DEBIT destination)
    DB->>DB: INSERT outbox (event: refund.created)
    DB->>DB: COMMIT

    RS-->>API: 201 Created
    API-->>Merchant: { refund_id, amount }

    CDC->>DB: Tail WAL
    CDC->>Kafka: Produce: refunds.refund.created

    par Refund Consumers
        Kafka->>WP: Consume: refund.created
        WP->>DB: UPDATE wallet_balances (reverse)
        WP->>Kafka: Produce: wallets.balance.updated
    and
        Kafka->>WS: Consume: refund.created
        WS->>WS: POST webhook (refund.created) to merchant URL
    and
        Kafka->>NS: Consume: refund.created
        NS->>NS: Queue email receipt to customer
    end

    Note over DB,CDC: Double-entry validation passes asynchronously

    DB->>DB: Trigger: verify_double_entry → OK
    DB->>DB: INSERT outbox (event: refund.succeeded)
    CDC->>Kafka: Produce: refunds.refund.succeeded

    Kafka->>WS: Consume: refund.succeeded
    WS->>WS: POST webhook (refund.succeeded)
    Kafka->>NS: Consume: refund.succeeded
    NS->>NS: Send completion notification
```

---

## 4. Payout Flow

```mermaid
sequenceDiagram
    actor Merchant
    participant API as API Gateway
    participant PoS as Payout Service
    participant DB as PostgreSQL
    participant CDC as Debezium CDC
    participant Kafka
    participant SS as Settlement Service
    participant WS as Webhook Sender
    participant Bank as External Bank

    Merchant->>API: POST /v1/payouts
    API->>PoS: Forward
    PoS->>DB: CALL create_journal_entry (debit merchant wallet)
    DB->>DB: INSERT outbox (event: payout.created)
    DB->>DB: COMMIT

    PoS-->>API: 201 Created
    API-->>Merchant: { payout_id }

    CDC->>Kafka: Produce: payouts.payout.created

    Kafka->>SS: Consume: payout.created
    SS->>SS: Inbox dedup
    SS->>Bank: Initiate bank transfer (ACH / wire)
    Bank-->>SS: Transfer accepted

    SS->>DB: INSERT outbox (event: payout.succeeded, settlement_ref)
    CDC->>Kafka: Produce: payouts.payout.succeeded
    Kafka->>WS: Consume → POST webhook (payout.succeeded)
```

---

## 5. Consumer DLQ Flow

```mermaid
sequenceDiagram
    participant Kafka
    participant Consumer as Consumer Service
    participant Inbox as Consumer Inbox (DB)
    participant DLQ as Dead Letter Queue
    participant Alert as Alerting (PagerDuty)
    participant OnCall as On-Call Engineer

    Kafka->>Consumer: Consume event (offset N)

    loop Retry (max 5)
        Consumer->>Inbox: INSERT ON CONFLICT DO NOTHING
        alt First Attempt
            Inbox-->>Consumer: Inserted (new event)
        else Replay / Retry
            Inbox-->>Consumer: Skipped (already COMPLETED)
            Consumer->>Kafka: ACK offset
        end

        Consumer->>Consumer: Process business logic

        alt Success
            Consumer->>Inbox: UPDATE status = COMPLETED
            Consumer->>Kafka: ACK offset
        else Transient Failure (DB timeout)
            Consumer->>Consumer: Backoff (2^retry seconds + jitter)
            Note over Consumer: Retry count: +1
        else Fatal Failure (Schema error)
            Consumer->>DLQ: Produce: wrapped event + error metadata
            Consumer->>Kafka: ACK offset (event preserved in DLQ)
            DLQ->>Alert: DLQ message count > 0
            Alert->>OnCall: P2 Alert: payments.dlq
        end
    end

    alt Exhausted Retries
        Consumer->>DLQ: Produce: wrapped event + error metadata + retry_count=5
        Consumer->>Kafka: ACK offset
        DLQ->>Alert: DLQ message count > 0
        Alert->>OnCall: P2 Alert
    end
```

---

## 6. Replay by Key (Targeted Fix)

```mermaid
sequenceDiagram
    actor OnCall as On-Call Engineer
    participant CLI as Replay CLI Tool
    participant DB as PostgreSQL
    participant Kafka

    OnCall->>CLI: replay --key pay_xyz987654 --topic payments.payment.succeeded
    CLI->>DB: SELECT * FROM outbox WHERE reference_id = 'pay_xyz987654'
    DB-->>CLI: Event payload (from outbox)

    CLI->>Kafka: Produce to payments.payment.succeeded (same event_id)

    Note over Kafka: Consumers receive replayed event

    Kafka->>Kafka: Wallet Projector: Inbox dedup → skip
    Kafka->>Kafka: Risk Engine: Inbox dedup → skip
    Kafka->>Kafka: Webhook Sender: Inbox NOT found → DELIVER webhook

    CLI-->>OnCall: Replay complete. Webhook delivered.
```

---

## 7. Outbox Table Structure

```sql
-- Each service's database contains an outbox table
-- This table is the source of truth for CDC → Kafka
CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    event_topic     VARCHAR(255) NOT NULL,
    payload         JSONB NOT NULL,
    partition_key   VARCHAR(255) NOT NULL,
    headers         JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_outbox_status_created ON outbox(status, created_at)
    WHERE status = 'PENDING';

-- Purge old outbox rows (scheduled job, runs hourly)
DELETE FROM outbox
WHERE status = 'SENT'
  AND processed_at < NOW() - INTERVAL '7 days';
```

---

## 8. Debezium Connector Configuration (Reference)

```json
{
  "name": "outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "financial-core-db.internal",
    "database.port": "5432",
    "database.user": "debezium_cdc",
    "database.dbname": "financial_core_db",
    "table.include.list": "public.outbox",
    "publication.autocreate.mode": "filtered",
    "plugin.name": "pgoutput",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.topic.replacement": "${routedByValue}",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "partition_key",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.field.event.timestamp": "created_at",
    "transforms.outbox.route.by.field": "event_topic",
    "transforms.outbox.table.expand.json.payload": "true",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "io.confluent.connect.avro.AvroConverter",
    "value.converter.schema.registry.url": "https://schema-registry.platform.com"
  }
}
```
