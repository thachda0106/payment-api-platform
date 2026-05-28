# Module 02 — Query Planner, Indexes, Locking & Isolation

## 2.1 Query Planner

### Cost-Based Optimization

The planner generates candidate plans, estimates costs, and picks the cheapest. Costs are in arbitrary units (~1 unit = 1 sequential page read). Key cost parameters:

```sql
SHOW seq_page_cost;      -- 1.0 (default)
SHOW random_page_cost;   -- 4.0 (default — FOR HDD. Set to 1.1 for SSD!)
SHOW cpu_tuple_cost;     -- 0.01
SHOW cpu_index_tuple_cost; -- 0.005
SHOW cpu_operator_cost;  -- 0.0025
```

**Critical**: `random_page_cost = 4.0` on SSDs causes the planner to AVOID index scans because it thinks random access is 4x slower than sequential. On SSDs, set to 1.1.

### Statistics (ANALYZE)

```sql
-- The planner relies on these statistics. Stale stats = bad plans.
SELECT attname, n_distinct, most_common_vals, most_common_freqs, histogram_bounds, correlation
FROM pg_stats WHERE tablename = 'payments' AND attname = 'status';

-- Run ANALYZE after bulk INSERT/UPDATE/DELETE
ANALYZE payments;
```

### Reading EXPLAIN

```sql
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT p.id, u.name, p.amount
FROM payments p JOIN users u ON p.user_id = u.id
WHERE p.status = 'COMPLETED' AND p.amount > 1000000
ORDER BY p.created_at DESC LIMIT 20;
```

Key fields: `cost` (estimated startup..total), `actual time`, `rows` vs `actual rows` (big gap = stale stats), `Buffers: shared hit/read` (cache hit ratio), `loops`, `Rows Removed by Filter`.

### Join Strategies

| Strategy | How | Best When | Cost Estimate |
|----------|-----|-----------|:------------:|
| **Nested Loop** | For each outer row, probe inner (with index) | Small outer, indexed inner | `outer_rows × log(inner_rows)` |
| **Hash Join** | Build hash table from inner, probe from outer | Large tables, no index | `build_hash_table(inner) + probe(outer)` |
| **Merge Join** | Sort both inputs, merge | Both sorted (by index) or large | `sort(A) + sort(B) + merge` |

## 2.2 Indexes

### B-tree (Default)
O(log n) lookups. Good for: `=`, `<`, `>`, `BETWEEN`, `ORDER BY`. Each node = one page.

### GIN (Generalized Inverted Index)
For composite values: arrays, JSONB, full-text search (tsvector). Posting tree + posting lists.

### GiST (Generalized Search Tree)
Extensible index. Used by: PostGIS (geospatial), full-text search (alternative to GIN), trigram (similarity).

### BRIN (Block Range INdex)
For very large tables with physical correlation to column value. Summary per block range (128 pages). Tiny index, sequential-like scan.

### Payment Index Strategy

```sql
-- Primary lookup: account_id + date range
CREATE INDEX idx_journal_lines_account_date ON journal_lines(account_id, created_at);

-- Point lookup: idempotency key (UNIQUE guarantees no duplicates)
CREATE UNIQUE INDEX idx_journal_entries_idempotency ON journal_entries(idempotency_key);

-- Covering index for balance queries (index-only scan)
CREATE INDEX idx_wallet_balances_account ON wallet_balances(account_id)
    INCLUDE (available_balance, version);

-- Partial index: only unprocessed outbox events
CREATE INDEX idx_outbox_unprocessed ON outbox_events(created_at) WHERE processed = FALSE;

-- BRIN for append-only journal entries (correlated with insertion order)
CREATE INDEX idx_journal_entries_created_brin ON journal_entries USING brin(created_at);
```

## 2.3 Locking

### Row-Level Locks

| Lock Mode | Acquired By | Conflicts With |
|-----------|------------|:-------------:|
| FOR UPDATE | `SELECT ... FOR UPDATE` | FOR UPDATE, FOR NO KEY UPDATE, FOR SHARE, FOR KEY SHARE |
| FOR NO KEY UPDATE | `UPDATE` (non-key columns) | FOR UPDATE, FOR NO KEY UPDATE |
| FOR SHARE | `SELECT ... FOR SHARE` | FOR UPDATE, FOR NO KEY UPDATE |
| FOR KEY SHARE | FK checks automatically | FOR UPDATE, FOR NO KEY UPDATE |

### Payment Locking: Wallet Debit

```sql
BEGIN;
-- Pessimistic lock: prevents concurrent debits
SELECT available_balance, version FROM wallet_balances
WHERE account_id = 'U1' FOR UPDATE;

-- Check + update in same transaction
UPDATE wallet_balances SET available_balance = available_balance - 10000, version = version + 1
WHERE account_id = 'U1' AND available_balance >= 10000;

-- If UPDATE affected 0 rows → insufficient balance → ROLLBACK
INSERT INTO journal_entries (...) VALUES (...);
INSERT INTO journal_lines (...) VALUES (...), (...);
COMMIT;
```

### Deadlock Detection

PostgreSQL detects deadlocks every `deadlock_timeout` (default 1s). It aborts one transaction (error 40P01 — the one that's done less work).

**Prevention**: Always lock resources in consistent ORDER (alphabetical by account_id).

### Advisory Locks

Application-defined locks not tied to rows:
```sql
SELECT pg_advisory_xact_lock(12345);  -- Released at transaction end
SELECT pg_advisory_lock(12345);       -- Released explicitly or on disconnect
```

## 2.4 Transaction Isolation

| Level | Dirty Read | Non-Repeatable Read | Phantom | Serialization Anomaly |
|-------|:----------:|:-------------------:|:-------:|:---------------------:|
| Read Committed | No | Yes | Yes | Yes |
| Repeatable Read | No | No | No (in PG) | Yes |
| Serializable | No | No | No | No |

### Serializable Snapshot Isolation (SSI)

PostgreSQL's Serializable uses SSI: tracks "dangerous structures" (read-write dependencies between concurrent serializable transactions). If a cycle is detected, one transaction is aborted with error `40001` — "could not serialize access."

**Application MUST retry serialization failures**. This is the strongest guarantee — use for ledger writes.

```sql
BEGIN ISOLATION LEVEL SERIALIZABLE;
-- If this fails with 40001, retry the entire transaction
INSERT INTO journal_entries (...) VALUES (...);
-- ...
COMMIT;
```

## 2.5 Exercises

### Ex 2.1 — EXPLAIN Analysis Contest
Given a slow payment query joining 4 tables, use EXPLAIN (ANALYZE, BUFFERS). Add indexes, rewrite query, adjust config. Target: 100x improvement. Document each iteration.

### Ex 2.2 — Deadlock Reproduction
Session A: UPDATE wallet U1; UPDATE wallet U2. Session B: UPDATE wallet U2; UPDATE wallet U1 (opposite order). Observe deadlock detection. Fix by ordering consistently.

### Ex 2.3 — Isolation Level Experiment
Run concurrent transactions at SERIALIZABLE. Trigger a serialization failure. Implement retry logic. Verify correctness after retry.

## 2.6 Self-Assessment

- [ ] Can read an EXPLAIN (ANALYZE, BUFFERS) output and identify the slowest node
- [ ] Know when to use B-tree vs GIN vs BRIN for payment queries
- [ ] Understand the locking matrix (FOR UPDATE vs FOR SHARE vs FOR KEY SHARE)
- [ ] Can reproduce and fix a deadlock
- [ ] Know when to use SERIALIZABLE (ledger writes) vs REPEATABLE READ (balance checks)
