-- V7__create_consumer_inbox.sql
-- Inbox pattern (Phase-9 P2). Replaces processed_events as the dedup + retry mechanism.
-- Two-phase: the Kafka handler inserts PENDING and commits the offset immediately;
-- an InboxRetryScheduler re-processes FAILED rows with exponential backoff (≤5),
-- routing to the DLQ on exhaustion. processed_events is kept until P5 for rollback.
CREATE TABLE consumer_inbox (
    event_id       UUID NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING | COMPLETED | FAILED
    retry_count    INT NOT NULL DEFAULT 0,
    last_error     TEXT,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_inbox_retry ON consumer_inbox(updated_at)
    WHERE status = 'FAILED';
