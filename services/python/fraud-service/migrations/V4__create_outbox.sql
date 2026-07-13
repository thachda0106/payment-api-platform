-- V4__create_outbox.sql
-- Debezium CDC outbox (Phase-9) — replaces fraud_outbox + the asyncio poller.
-- FraudConsumer writes a CloudEvents envelope here in the same transaction as the
-- fraud_scores insert; Debezium publishes it to payments.payment.succeeded/failed (Avro).
CREATE TABLE outbox (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(255) NOT NULL,
    event_topic    VARCHAR(255) NOT NULL,
    payload        JSONB NOT NULL,
    partition_key  VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
