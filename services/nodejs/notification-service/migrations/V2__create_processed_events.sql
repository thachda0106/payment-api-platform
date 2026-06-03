-- V2__create_processed_events.sql
CREATE TABLE processed_events (
    event_id VARCHAR(128) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_group)
);
