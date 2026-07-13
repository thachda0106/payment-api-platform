-- V6__drop_legacy.sql
-- Phase-9 P5 decommission: remove notification_outbox (superseded by `outbox`, V4) and
-- processed_events (superseded by consumer_inbox, V5).
DROP TABLE IF EXISTS notification_outbox;
DROP TABLE IF EXISTS processed_events;
