-- V4__create_ledger_outbox.sql
-- Transactional outbox for reliable ledger-events publishing.
-- Written in the SAME @Transactional as the journal entries + processed_events mark,
-- then drained to Kafka by LedgerOutboxPoller (eliminates the dual-write gap).
CREATE TABLE ledger_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID UNIQUE NOT NULL,       -- per-event dedup id (NOT paymentId)
    aggregate_id UUID NOT NULL,          -- paymentId — Kafka key for ordering
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_ledger_outbox_unpublished ON ledger_outbox(created_at, id) WHERE published_at IS NULL;
