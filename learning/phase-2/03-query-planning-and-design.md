# Module 03 — Query Planning & Database Design

## 3.1 Query Planning with EXPLAIN

EXPLAIN shows you the execution plan PostgreSQL chose for your query. This is the single most important skill for database performance. Every slow payment query should be investigated with EXPLAIN ANALYZE.

### EXPLAIN vs EXPLAIN ANALYZE

```sql
-- EXPLAIN: estimates only, doesn't execute the query
EXPLAIN SELECT * FROM payments WHERE user_id = 'U1';

-- EXPLAIN ANALYZE: executes the query, shows actual times and rows
EXPLAIN ANALYZE SELECT * FROM payments WHERE user_id = 'U1';

-- EXPLAIN (ANALYZE, BUFFERS): adds I/O statistics (pages read/hit)
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM payments WHERE user_id = 'U1';
```

### Reading EXPLAIN Output

```sql
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT p.payment_id, p.amount, u.name, m.name AS merchant
FROM payments p
JOIN users u ON p.user_id = u.id
JOIN merchants m ON p.merchant_id = m.id
WHERE p.status = 'COMPLETED'
  AND p.created_at >= NOW() - INTERVAL '7 days'
ORDER BY p.created_at DESC
LIMIT 50;
```

**Sample output** (simplified):
```
Limit  (cost=1250.42..1250.55 rows=50 width=128)
       (actual time=12.345..12.389 rows=50 loops=1)
       Buffers: shared hit=892 read=34
  -> Sort  (cost=1250.42..1255.42 rows=2000 width=128)
           (actual time=12.343..12.365 rows=50 loops=1)
           Sort Key: p.created_at DESC
           Sort Method: top-N heapsort  Memory: 27kB
           Buffers: shared hit=892 read=34
     -> Hash Join  (cost=45.00..1180.00 rows=2000 width=128)
                   (actual time=5.123..11.890 rows=1834 loops=1)
                   Hash Cond: (p.merchant_id = m.id)
                   Buffers: shared hit=889 read=34
        -> Hash Join  (cost=30.00..1100.00 rows=2000 width=100)
                      (actual time=3.456..9.234 rows=1834 loops=1)
                      Hash Cond: (p.user_id = u.id)
                      Buffers: shared hit=750 read=30
           -> Index Scan using idx_payments_created on payments p
              (cost=0.42..850.00 rows=2000 width=80)
              (actual time=0.015..2.345 rows=1850 loops=1)
              Index Cond: (created_at >= '2026-05-20' AND created_at <= '2026-05-27')
              Filter: (status = 'COMPLETED'::text)
              Rows Removed by Filter: 450
              Buffers: shared hit=300 read=30
           -> Hash  (cost=22.00..22.00 rows=1200 width=28)
                    (actual time=3.420..3.422 rows=1200 loops=1)
                    Buckets: 2048  Batches: 1  Memory Usage: 65kB
                    Buffers: shared hit=450
              -> Seq Scan on users u  (cost=0.00..22.00 rows=1200 width=28)
                                       (actual time=0.010..1.234 rows=1200 loops=1)
                                       Buffers: shared hit=450
        -> Hash  (cost=10.00..10.00 rows=500 width=28)
                 (actual time=1.650..1.651 rows=500 loops=1)
                 Buckets: 1024  Batches: 1  Memory Usage: 28kB
                 Buffers: shared hit=139
           -> Seq Scan on merchants m  (cost=0.00..10.00 rows=500 width=28)
                                       (actual time=0.005..0.678 rows=500 loops=1)
                                       Buffers: shared hit=139
```

### How to Read It — Bottom to Top

**Leaf nodes execute first** (indented → inner). Results flow upward.

1. **Seq Scan on merchants** (bottom): Reads all 500 rows from heap (cost=0.00..10.00). Builds hash table for Hash Join.
2. **Seq Scan on users**: Reads all 1,200 users. Builds hash table.
3. **Index Scan on payments**: Uses `idx_payments_created` to find payments in the last 7 days. Filters out non-COMPLETED ones.
4. **Hash Join** (payments ⋈ users): Probes user hash table for each payment row.
5. **Hash Join** (result ⋈ merchants): Probes merchant hash table.
6. **Sort**: Orders by `created_at DESC`.
7. **Limit**: Returns top 50 rows.

### Key Metrics

| Field | Meaning | What to Watch For |
|-------|---------|-------------------|
| `cost=0.42..850.00` | Startup cost (0.42) and total cost (850.00) | High total cost = expensive node |
| `actual time=0.015..2.345` | Real startup time and total time (ms) | Compare with estimated cost |
| `rows=2000` vs `actual rows=1850` | Estimated vs actual rows | Big discrepancy = stale statistics → ANALYZE |
| `loops=N` | How many times this node executed | High loops × high time = bottleneck |
| `Buffers: shared hit=N read=M` | Pages from cache (hit) vs disk (read) | High `read` = data not in memory |
| `Rows Removed by Filter: 450` | Rows that passed index condition but failed filter | Index not selective enough for filter |
| `Memory Usage: 65kB` | Memory used for hash/sort | If it says `Disk: xxxkB`, work_mem was exceeded |

### Common Bottlenecks Identified by EXPLAIN

| Problem | What You See | Fix |
|---------|-------------|-----|
| **Sequential scan on large table** | `Seq Scan` on millions of rows | Add index on WHERE columns |
| **Nested loop on large tables** | `Nested Loop` with millions of rows | ANALYZE (stale stats), or force hash join |
| **Sort spills to disk** | `Sort Method: external merge` | Increase `work_mem` |
| **Hash spills to disk** | `Batches: N` where N > 1 | Increase `work_mem` |
| **Row estimate way off** | `rows=1` but `actual rows=100000` | Run `ANALYZE tablename` |
| **Index scan then filter** | `Rows Removed by Filter` is significant | Add filtered column to index (composite or expression) |
| **Index condition not used** | `Filter:` instead of `Index Cond:` | Index doesn't cover the WHERE clause — create better index |

---

## 3.2 Database Design Patterns for Payment Systems

### Immutable Ledger with Append-Only Tables

```sql
-- NEVER UPDATE or DELETE from ledger tables
CREATE TABLE journal_entries (
    entry_id UUID PRIMARY KEY,
    ...
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Prevent UPDATE/DELETE with trigger
CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Cannot modify ledger entries: table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_modification();

CREATE TRIGGER trg_journal_lines_immutable
    BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_modification();
```

### Materialized Balance Projection

```sql
-- wallet_balances is a materialized projection of journal_lines
-- Can be rebuilt at any time:
INSERT INTO wallet_balances (account_id, currency, available_balance, version)
SELECT account_id, currency,
       SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS balance,
       1
FROM journal_lines
WHERE account_id NOT IN (SELECT account_id FROM wallet_balances)
GROUP BY account_id, currency;

-- But we maintain it in real-time via triggers:
CREATE OR REPLACE FUNCTION update_wallet_balance()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.entry_type = 'DEBIT' THEN
        UPDATE wallet_balances SET available_balance = available_balance - NEW.amount,
            version = version + 1 WHERE account_id = NEW.account_id;
    ELSE
        UPDATE wallet_balances SET available_balance = available_balance + NEW.amount,
            version = version + 1 WHERE account_id = NEW.account_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

### Optimistic Concurrency Control (Version Column)

```sql
-- Read current version
SELECT available_balance, version FROM wallet_balances WHERE account_id = 'U1';
-- Returns: balance=100000, version=5

-- Update with version check
UPDATE wallet_balances
SET available_balance = 95000, version = version + 1
WHERE account_id = 'U1' AND version = 5;  -- Only updates if version is still 5

-- If 0 rows updated → another transaction modified this row → retry
```

### Hash-Chained Audit Trail

```sql
-- Each journal entry links to the previous one via cryptographic hash
ALTER TABLE journal_entries ADD COLUMN prev_entry_hash BYTEA;
ALTER TABLE journal_entries ADD COLUMN entry_hash BYTEA;

-- On INSERT: compute hash of current entry + previous entry's hash
INSERT INTO journal_entries (entry_id, prev_entry_hash, entry_hash, ...)
SELECT 'new-id', entry_hash,
       digest('new-id' || digest(prev_row::text, 'sha256')::text, 'sha256')
FROM (SELECT entry_hash, * FROM journal_entries ORDER BY created_at DESC LIMIT 1) prev_row;
-- This creates a tamper-evident chain
```

### Outbox Pattern (Preview of Phase 5)

```sql
-- Outbox table in the SAME database as business data
CREATE TABLE outbox_events (
    event_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type  VARCHAR(100) NOT NULL,
    payload     JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed   BOOLEAN NOT NULL DEFAULT FALSE
);

-- Write outbox event in the SAME transaction as business data
BEGIN;
    INSERT INTO journal_entries (...) VALUES (...);
    INSERT INTO journal_lines (...) VALUES (...);
    INSERT INTO outbox_events (event_type, payload) VALUES ('PaymentCompleted', '{"payment_id": "..."}');
COMMIT;
-- Both succeed or both fail — no dual-write problem
```

---

## 3.3 Hands-On Exercises

### Ex 3.1 — EXPLAIN Analysis
Take the payment query from the example. Run EXPLAIN (ANALYZE, BUFFERS). Identify: (a) which node uses the most time, (b) whether indexes are used, (c) whether any node spilled to disk. Add an index. Compare plans.

### Ex 3.2 — Optimize a Slow Query
Given a query that joins 4 tables with no indexes, optimize it to run 100x faster. Use EXPLAIN ANALYZE to measure each improvement. Document the final index strategy.

### Ex 3.3 — Design a Ledger Schema
Design the complete ledger schema: `chart_of_accounts`, `journal_entries`, `journal_lines`, `wallet_balances`. Include all constraints. Write triggers for: (a) immutability (no UPDATE/DELETE on journal tables), (b) balance update on journal line INSERT, (c) double-entry verification (SUM(DEBIT)=SUM(CREDIT) per journal entry).

### Ex 3.4 — Recon Query
Write a query using FULL OUTER JOIN + COALESCE that finds all accounts where `wallet_balances.available_balance` differs from `SUM(journal_lines)`.

---

## 3.4 Self-Assessment

- [ ] Can read an EXPLAIN (ANALYZE, BUFFERS) output top-to-bottom
- [ ] Can identify: sequential scan, index scan, hash join, nested loop, sort from EXPLAIN
- [ ] Understand what `Rows Removed by Filter` means and how to fix it
- [ ] Know when to add an index, when to remove one
- [ ] Can design an append-only ledger schema with triggers
- [ ] Understand optimistic concurrency control with version columns
- [ ] Can write a reconciliation query to find data inconsistencies
