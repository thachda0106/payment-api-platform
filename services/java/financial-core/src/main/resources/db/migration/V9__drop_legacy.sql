-- V9__drop_legacy.sql
-- Phase-9 P5 decommission: remove the app-level ledger_outbox (superseded by `outbox`, V6)
-- and processed_events (superseded by consumer_inbox, V7).
DROP TABLE IF EXISTS ledger_outbox;
DROP TABLE IF EXISTS processed_events;
