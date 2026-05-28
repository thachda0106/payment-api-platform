-- ============================================================================
-- Phase 2 Exercises — SQL Practice
-- Run: psql phase2 < exercises.sql
-- ============================================================================

-- ─── Setup: Create tables for exercises ────────────────────────────────────

DROP TABLE IF EXISTS payments CASCADE;
DROP TABLE IF EXISTS refunds CASCADE;
DROP TABLE IF EXISTS merchants CASCADE;
DROP TABLE IF EXISTS users CASCADE;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE merchants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    tier VARCHAR(20) NOT NULL DEFAULT 'BASIC' CHECK (tier IN ('BASIC', 'PREMIUM', 'ENTERPRISE')),
    fee_pct NUMERIC(5,3) NOT NULL DEFAULT 1.500,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'FAILED', 'REFUNDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    reason VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─── Seed Data ─────────────────────────────────────────────────────────────

INSERT INTO users (name, email) VALUES
    ('Alice', 'alice@example.com'),
    ('Bob', 'bob@example.com'),
    ('Charlie', 'charlie@example.com'),
    ('Diana', 'diana@example.com'),
    ('Eve', 'eve@example.com');

INSERT INTO merchants (name, tier, fee_pct) VALUES
    ('MoMo Mart', 'PREMIUM', 1.200),
    ('Tech Store', 'BASIC', 1.500),
    ('Coffee Shop', 'BASIC', 1.500),
    ('Enterprise Corp', 'ENTERPRISE', 0.800);

-- Generate 100 sample payments across users and merchants
INSERT INTO payments (user_id, merchant_id, amount, currency, status, created_at)
SELECT
    u.id, m.id,
    (random() * 1000000 + 10000)::BIGINT,
    'VND',
    CASE WHEN random() < 0.9 THEN 'COMPLETED' ELSE 'FAILED' END,
    NOW() - (random() * INTERVAL '30 days')
FROM users u CROSS JOIN merchants m,
     generate_series(1, 5);  -- 5 payments per user-merchant pair → 100 total

-- ─── Exercise 1: JOINs ─────────────────────────────────────────────────────

-- 1a: List all COMPLETED payments with user name and merchant name
SELECT p.id, u.name AS user_name, m.name AS merchant_name, p.amount, p.created_at
FROM payments p
JOIN users u ON p.user_id = u.id
JOIN merchants m ON p.merchant_id = m.id
WHERE p.status = 'COMPLETED'
ORDER BY p.created_at DESC LIMIT 10;

-- 1b: Find users who have NEVER made a payment (LEFT JOIN + IS NULL)
SELECT u.id, u.name
FROM users u
LEFT JOIN payments p ON u.id = p.user_id
WHERE p.id IS NULL;

-- ─── Exercise 2: Aggregation ───────────────────────────────────────────────

-- 2a: Total payment volume per merchant, ranked
SELECT m.name, COUNT(*) AS txn_count, SUM(p.amount) / 1.0 AS total_vnd,
       RANK() OVER (ORDER BY SUM(p.amount) DESC) AS rank
FROM payments p
JOIN merchants m ON p.merchant_id = m.id
WHERE p.status = 'COMPLETED'
GROUP BY m.name
ORDER BY total_vnd DESC;

-- 2b: Daily payment volume for the last 7 days
SELECT DATE(created_at) AS day,
       COUNT(*) AS txn_count,
       SUM(amount) / 1.0 AS total_vnd,
       AVG(amount) / 1.0 AS avg_vnd
FROM payments
WHERE status = 'COMPLETED' AND created_at >= NOW() - INTERVAL '7 days'
GROUP BY DATE(created_at)
ORDER BY day;

-- ─── Exercise 3: Window Functions ──────────────────────────────────────────

-- 3a: Each user's most recent payment
SELECT DISTINCT ON (u.id) u.name, p.amount, p.created_at
FROM users u
JOIN payments p ON u.id = p.user_id
ORDER BY u.id, p.created_at DESC;

-- 3b: Running total of payments per user
SELECT u.name, p.created_at, p.amount,
       SUM(p.amount) OVER (PARTITION BY p.user_id ORDER BY p.created_at
           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) / 1.0 AS running_total
FROM payments p JOIN users u ON p.user_id = u.id
ORDER BY u.name, p.created_at;

-- 3c: Time between consecutive payments for each user
SELECT u.name, p.created_at, p.amount,
       p.created_at - LAG(p.created_at) OVER (PARTITION BY p.user_id ORDER BY p.created_at) AS time_since_prev
FROM payments p JOIN users u ON p.user_id = u.id
ORDER BY u.name, p.created_at;

-- ─── Exercise 4: CTEs ──────────────────────────────────────────────────────

-- 4a: User segment analysis (high/medium/low spenders)
WITH user_totals AS (
    SELECT user_id, SUM(amount) AS total_spent
    FROM payments WHERE status = 'COMPLETED' GROUP BY user_id
),
avg_all AS (
    SELECT AVG(total_spent) AS avg_spent FROM user_totals
)
SELECT u.name, ut.total_spent / 1.0 AS total_vnd,
       CASE WHEN ut.total_spent > a.avg_spent * 1.5 THEN 'HIGH'
            WHEN ut.total_spent < a.avg_spent * 0.5 THEN 'LOW'
            ELSE 'MEDIUM' END AS segment
FROM user_totals ut JOIN users u ON u.id = ut.user_id
CROSS JOIN avg_all a
ORDER BY total_vnd DESC;

-- ─── Exercise 5: Reconciliation Query ──────────────────────────────────────

-- Compare payment amounts with refund amounts (find over-refunded payments)
WITH refund_totals AS (
    SELECT payment_id, SUM(amount) AS total_refunded
    FROM refunds GROUP BY payment_id
)
SELECT p.id, p.amount / 1.0 AS payment_amount,
       COALESCE(r.total_refunded, 0) / 1.0 AS total_refunded,
       (p.amount - COALESCE(r.total_refunded, 0)) / 1.0 AS remaining
FROM payments p
LEFT JOIN refund_totals r ON p.id = r.payment_id
WHERE COALESCE(r.total_refunded, 0) > p.amount  -- Over-refunded!
   OR p.status = 'REFUNDED';

-- ─── Exercise 6: Index Analysis ────────────────────────────────────────────

-- Check which indexes exist
SELECT tablename, indexname, indexdef FROM pg_indexes
WHERE schemaname = 'public' AND tablename IN ('payments', 'users', 'merchants');

-- Analyze a query plan
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, u.name, m.name, p.amount
FROM payments p
JOIN users u ON p.user_id = u.id
JOIN merchants m ON p.merchant_id = m.id
WHERE p.status = 'COMPLETED'
  AND p.amount > 500000
ORDER BY p.created_at DESC
LIMIT 20;

-- Add a composite index and re-analyze
CREATE INDEX IF NOT EXISTS idx_payments_status_amount ON payments(status, amount);
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, u.name, m.name, p.amount
FROM payments p
JOIN users u ON p.user_id = u.id
JOIN merchants m ON p.merchant_id = m.id
WHERE p.status = 'COMPLETED'
  AND p.amount > 500000
ORDER BY p.created_at DESC
LIMIT 20;

-- ─── Exercise 7: Isolation Level Experiment ────────────────────────────────

-- Run these in TWO separate psql sessions to observe different behaviors

-- Session A (Read Committed):
BEGIN;
SELECT SUM(amount) FROM payments WHERE user_id = (SELECT id FROM users WHERE name = 'Alice');
-- Session B: UPDATE payments SET amount = amount + 1000 WHERE ... COMMIT;
SELECT SUM(amount) FROM payments WHERE user_id = (SELECT id FROM users WHERE name = 'Alice');
-- Value changed! (non-repeatable read)
COMMIT;

-- Session A (Repeatable Read):
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT SUM(amount) FROM payments WHERE user_id = (SELECT id FROM users WHERE name = 'Alice');
-- Session B: UPDATE payments SET amount = amount + 1000 WHERE ... COMMIT;
SELECT SUM(amount) FROM payments WHERE user_id = (SELECT id FROM users WHERE name = 'Alice');
-- Value UNCHANGED! (repeatable read prevented)
COMMIT;

-- ─── Cleanup ───────────────────────────────────────────────────────────────
-- Uncomment to clean up:
-- DROP TABLE IF EXISTS refunds, payments, merchants, users CASCADE;
