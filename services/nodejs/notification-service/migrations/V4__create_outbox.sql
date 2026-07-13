-- V4__create_outbox.sql
-- Debezium CDC outbox (Phase-9) — replaces notification_outbox + the setInterval poller.
-- The consumer writes a CloudEvents envelope here in the same BEGIN/COMMIT as the
-- notifications insert; Debezium publishes it to notifications.email.queued (Avro).
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
