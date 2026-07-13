-- V6__drop_legacy.sql
-- Phase-9 P5 decommission: remove the pre-Debezium outbox and the unused
-- (producer-only service) processed_events table. Superseded by `outbox` (V4).
DROP TABLE IF EXISTS payment_outbox;
DROP TABLE IF EXISTS processed_events;
