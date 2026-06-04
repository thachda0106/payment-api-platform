-- V2__create_payment_outbox.sql
-- Transactional outbox for reliable Kafka publishing
CREATE TABLE payment_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID UNIQUE NOT NULL,       -- Per-event dedup ID (NOT paymentId)
    aggregate_id UUID NOT NULL REFERENCES payments(id),  -- paymentId for Kafka key ordering
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON payment_outbox(created_at, id) WHERE published_at IS NULL;
