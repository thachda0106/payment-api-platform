-- V4__create_outbox.sql
-- Debezium CDC outbox (Phase-9). Producers write a CloudEvents envelope into `payload`
-- in the same transaction as the state change; a Debezium PostgreSQL connector tails the
-- WAL, and the EventRouter SMT publishes each row to the topic named by `event_topic`
-- (Avro via Schema Registry). Replaces the app-level poller + payment_outbox table.
CREATE TABLE outbox (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- CloudEvents id / dedup key
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(255) NOT NULL,
    event_topic    VARCHAR(255) NOT NULL,
    payload        JSONB NOT NULL,
    partition_key  VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
