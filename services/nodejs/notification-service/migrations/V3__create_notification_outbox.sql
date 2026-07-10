-- V3__create_notification_outbox.sql
-- Transactional outbox for reliable notification-events publishing.
-- Written in the SAME transaction (BEGIN/COMMIT) as notifications + processed_events,
-- then drained to Kafka by the poller (eliminates the dual-write gap).
CREATE TABLE notification_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID UNIQUE NOT NULL,       -- per-event dedup id (NOT paymentId)
    aggregate_id UUID NOT NULL,          -- paymentId — Kafka key for ordering
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_notification_outbox_unpublished ON notification_outbox(created_at, id) WHERE published_at IS NULL;
