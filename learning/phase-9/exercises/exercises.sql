-- ============================================================================
-- Phase 9 Exercises — PostgreSQL Internals
-- Run: psql phase9 < exercises.sql
-- ============================================================================

-- ═══════════ Ex 1.1 — MVCC Visibility Lab ═══════════
-- Run these in TWO separate psql sessions

-- Session A:
BEGIN;
INSERT INTO mvcc_test (id, val) VALUES (1, 'original');
-- Check: SELECT xmin, xmax, ctid, * FROM mvcc_test;

-- Session B:
BEGIN;
SELECT xmin, xmax, ctid, * FROM mvcc_test;  -- Can you see Session A's insert? (No — not committed)

-- Session A: COMMIT;

-- Session B:
SELECT xmin, xmax, ctid, * FROM mvcc_test;  -- Can you see it now? (Yes — committed)
COMMIT;

-- ═══════════ Ex 1.2 — WAL Generation Rate ═══════════
SELECT pg_current_wal_lsn() AS before_lsn \gset
-- Generate 100K INSERTs
INSERT INTO wal_test (data) SELECT 'data-' || generate_series FROM generate_series(1, 100000);
SELECT pg_current_wal_lsn() AS after_lsn \gset
SELECT pg_wal_lsn_diff(:'after_lsn', :'before_lsn') AS wal_bytes_generated;
-- Calculate: bytes per transaction = wal_bytes / 100000

-- ═══════════ Ex 1.3 — Dead Tuple Accumulation ═══════════
SELECT n_dead_tup, n_live_tup FROM pg_stat_user_tables WHERE relname = 'dead_tuple_test';

INSERT INTO dead_tuple_test (data) SELECT 'data-' || i FROM generate_series(1, 10000) i;
DELETE FROM dead_tuple_test WHERE id % 2 = 0;  -- Delete 5,000 rows

SELECT n_dead_tup, n_live_tup FROM pg_stat_user_tables WHERE relname = 'dead_tuple_test';
-- n_dead_tup should be ~5000

VACUUM dead_tuple_test;
SELECT n_dead_tup, n_live_tup FROM pg_stat_user_tables WHERE relname = 'dead_tuple_test';
-- n_dead_tup should be ~0

-- ═══════════ Ex 2.1 — EXPLAIN Analysis ═══════════
CREATE TABLE payments_ex AS SELECT id, (random()*1000000)::bigint AS amount, (ARRAY['COMPLETED','FAILED'])[1+(random()>.9)::int] AS status, now()-random()*interval'90 days' AS created_at, 'U'||(1+random()*999)::int AS user_id FROM generate_series(1,100000) id;
CREATE INDEX IF NOT EXISTS idx_payments_ex_status ON payments_ex(status);

-- Before optimization
EXPLAIN (ANALYZE, BUFFERS) SELECT user_id, SUM(amount), COUNT(*) FROM payments_ex WHERE status='COMPLETED' GROUP BY user_id ORDER BY SUM(amount) DESC LIMIT 10;

-- Add composite index
CREATE INDEX idx_payments_ex_status_amount ON payments_ex(status, amount);

-- After optimization
EXPLAIN (ANALYZE, BUFFERS) SELECT user_id, SUM(amount), COUNT(*) FROM payments_ex WHERE status='COMPLETED' GROUP BY user_id ORDER BY SUM(amount) DESC LIMIT 10;

-- ═══════════ Ex 2.2 — Deadlock Reproduction (2 sessions) ═══════════
-- Session A:
BEGIN; UPDATE wallets SET balance = balance - 100 WHERE id = 1;
-- Session B:
BEGIN; UPDATE wallets SET balance = balance + 100 WHERE id = 2;
-- Session A:
UPDATE wallets SET balance = balance + 100 WHERE id = 2; -- WAITS for B
-- Session B:
UPDATE wallets SET balance = balance - 100 WHERE id = 1; -- DEADLOCK! PostgreSQL detects, one transaction aborted.

-- Fix: always lock in alphabetical/numeric order:
-- Session A: LOCK id=1, then id=2. Session B: ALSO lock id=1, then id=2 → no deadlock.

-- ═══════════ Ex 3.4 — Diagnostic Drill ═══════════
-- Simulate "database is slow" — run these diagnostic queries:

-- 1. What's running right now?
SELECT pid, state, wait_event_type, wait_event, query_start, LEFT(query, 80) FROM pg_stat_activity WHERE state != 'idle' ORDER BY query_start;

-- 2. What's consuming the most time?
SELECT queryid, calls, mean_exec_time, total_exec_time FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;

-- 3. Any lock waits?
SELECT COUNT(*) AS lock_waits FROM pg_locks WHERE NOT granted;

-- 4. Dead tuples accumulating?
SELECT relname, n_dead_tup, last_autovacuum FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 10;

-- 5. Cache hit ratio OK?
SELECT blks_hit * 100.0 / NULLIF(blks_hit + blks_read, 0) AS hit_ratio FROM pg_stat_database WHERE datname = current_database();

-- 6. Connection usage?
SELECT COUNT(*) AS total_connections, (SELECT setting::int FROM pg_settings WHERE name='max_connections') AS max_conn FROM pg_stat_activity;
