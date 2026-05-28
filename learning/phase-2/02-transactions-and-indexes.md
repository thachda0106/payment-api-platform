# Module 02 — Transactions, Indexes & Locking

## 2.1 ACID Transactions

ACID is the foundation of financial correctness. If your database doesn't guarantee ACID, you cannot guarantee that money is not lost, duplicated, or corrupted.

### Atomicity

**All or nothing.** A transaction either commits ALL its changes or rolls back ALL of them. If any part fails, the entire transaction is undone.

```sql
BEGIN;
    INSERT INTO journal_entries (...) VALUES (...);
    INSERT INTO journal_lines (...) VALUES (...), (...);
    UPDATE wallet_balances SET balance = balance - 10000 WHERE account_id = 'U1';
    UPDATE wallet_balances SET balance = balance + 9850 WHERE account_id = 'M1';
    INSERT INTO outbox_events (...) VALUES (...);
COMMIT;  -- All succeed, or if any fails: ROLLBACK
```

**Payment relevance**: The wallet debit and credit MUST happen together. If the debit succeeds but the credit fails, money disappears. Atomicity prevents this.

### Consistency

**Valid state → valid state.** The transaction transforms the database from one valid state to another. All constraints (CHECK, FOREIGN KEY, UNIQUE, NOT NULL) are enforced at transaction boundaries.

```sql
-- This transaction preserves the invariant: SUM(DEBIT) = SUM(CREDIT)
BEGIN;
    INSERT INTO journal_lines VALUES ('entry1', 'U1', 'DEBIT', 100000, 'VND', 1);
    INSERT INTO journal_lines VALUES ('entry1', 'M1', 'CREDIT', 100000, 'VND', 2);
    -- If we forgot the credit line and ran a trigger check:
    -- STATEMENT-level AFTER INSERT trigger would detect SUM(DEBIT) ≠ SUM(CREDIT) and ROLLBACK
COMMIT;
```

**NOTE**: The "C" in ACID is NOT about business rules (that's application logic). It's about database constraints — the database guarantees that constraints are satisfied after the transaction commits. If your application writes a payment with DEBIT=100,000 and CREDIT=50,000, the database considers that "consistent" as long as CHECK constraints pass. The DEBIT=CREDIT rule must be enforced by your application or a trigger.

### Isolation

**Transactions don't interfere.** Concurrent transactions see the database as if they ran sequentially (ideally). In practice, isolation levels trade off correctness for performance.

### Durability

**Committed data survives crashes.** Once COMMIT returns, the data is permanently stored. PostgreSQL guarantees this via the WAL (Write-Ahead Log). Even if the server crashes immediately after COMMIT, on recovery the WAL is replayed and the transaction is durable.

```sql
BEGIN;
    UPDATE wallet_balances SET balance = balance - 10000 WHERE account_id = 'U1';
COMMIT;  -- After this returns, the debit is permanent
-- Server crashes one millisecond later
-- On restart: PostgreSQL replays WAL → debit is present
```

---

## 2.2 Isolation Levels

### Read Committed (PostgreSQL default)

Each statement sees a snapshot of committed data AS OF THE STATEMENT START TIME. A later statement in the same transaction may see different data if another transaction committed in between.

**Problem — Non-Repeatable Read**:
```sql
-- Session A:
BEGIN;
SELECT balance FROM wallet_balances WHERE account_id = 'U1';  -- Returns 100,000

-- Session B (concurrent):
BEGIN;
UPDATE wallet_balances SET balance = 90000 WHERE account_id = 'U1';
COMMIT;

-- Session A (same transaction):
SELECT balance FROM wallet_balances WHERE account_id = 'U1';  -- Returns 90,000 (CHANGED!)
COMMIT;
```
Session A saw two different values for the same row in the same transaction — a non-repeatable read.

**When to use**: High-concurrency reads where minor inconsistencies are acceptable. Transaction history listings, report generation, search queries.

### Repeatable Read

A snapshot is taken at the FIRST statement in the transaction. All subsequent statements see that same snapshot — no matter what other transactions commit.

**Prevents**: Non-repeatable reads. Session A would see 100,000 both times.

**Does NOT prevent**: Serialization anomalies.

**Serialization Anomaly Example**:
```sql
-- Session A:
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT SUM(balance) FROM wallet_balances WHERE currency = 'VND';  -- Returns 1,000,000

-- Session B:
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT SUM(balance) FROM wallet_balances WHERE currency = 'VND';  -- Returns 1,000,000

-- Session A: adds 100,000 to account U1
UPDATE wallet_balances SET balance = balance + 100000 WHERE account_id = 'U1';
COMMIT;  -- Total is now 1,100,000

-- Session B: deducts 50,000 from account U2 (saw total was 1,000,000, thinks it's still 1,000,000)
UPDATE wallet_balances SET balance = balance - 50000 WHERE account_id = 'U2';
COMMIT;  -- Total is now 1,050,000 — But A+B should have been +100K-50K=1,050,000 vs original 1,000,000... actually that's correct.
-- A better example: both sessions try to maintain a minimum total balance
-- Both see 1,000,000. Both think "I can withdraw 500,000 and keep total above 500,000"
-- Both withdraw. Total drops to 0. But each THOUGHT the other wasn't withdrawing.
```

### Serializable (SSI — Serializable Snapshot Isolation)

PostgreSQL's Serializable uses **Serializable Snapshot Isolation** (SSI). It tracks "dangerous structures" — read-write dependencies between concurrent serializable transactions. If a cycle is detected (would cause a serialization anomaly if both committed), one transaction is rolled back with error 40001.

**Application must retry**: `ERROR: could not serialize access due to read/write dependencies among transactions`

```sql
BEGIN ISOLATION LEVEL SERIALIZABLE;
    -- Critical financial operation
    INSERT INTO journal_entries (...) VALUES (...);
    INSERT INTO journal_lines (...) VALUES (...), (...);
    UPDATE wallet_balances SET balance = balance - 10000 WHERE account_id = 'U1' AND balance >= 10000;
COMMIT;
-- If this fails with 40001, retry the entire transaction
```

**When to use Serializable**: Financial operations where correctness is paramount. Journal entry creation. Balance transfers. Settlement operations.

### Anomaly Summary

| Anomaly | Read Committed | Repeatable Read | Serializable |
|---------|:-------------:|:---------------:|:------------:|
| Dirty Read | No | No | No |
| Non-Repeatable Read | Yes | No | No |
| Phantom Read | Yes | No (in PG) | No |
| Serialization Anomaly | Yes | Yes | No |

**Payment isolation strategy**:
- Journal entry creation → SERIALIZABLE (correctness over performance)
- Balance check before payment → REPEATABLE READ + SELECT FOR UPDATE (lock-based, simpler)
- Idempotency key check → READ COMMITTED (just checking UNIQUE constraint)
- Reporting queries → READ COMMITTED

---

## 2.3 MVCC (Multi-Version Concurrency Control)

PostgreSQL doesn't lock rows when you SELECT. Instead, it maintains MULTIPLE VERSIONS of each row. Each transaction sees the version that was committed when its snapshot was taken.

### How It Works (Simplified)

Every row has hidden system columns:
- `xmin`: Transaction ID that INSERTED this row version
- `xmax`: Transaction ID that DELETED/UPDATED this row version (0 = still visible)

```sql
-- Create table and insert
INSERT INTO users VALUES ('U1', 'Alice');  -- Row: (xmin=100, xmax=0, id=U1, name=Alice)

-- Update
UPDATE users SET name = 'Alice Updated' WHERE id = 'U1';
-- Old row: (xmin=100, xmax=101, id=U1, name=Alice)        ← now invisible to new transactions
-- New row: (xmin=101, xmax=0,   id=U1, name=Alice Updated) ← visible

-- Delete
DELETE FROM users WHERE id = 'U1';
-- Row: (xmin=101, xmax=102, id=U1, name=Alice Updated)     ← invisible to new transactions
```

**Visibility check** for a transaction with snapshot (xmin=50, xmax=105, xip=[101, 103]):
1. Must have been inserted by a committed transaction: `xmin < 50` (committed before snapshot) OR `xmin` is committed and not in `xip` (not currently active)
2. Must NOT have been deleted: `xmax == 0` OR `xmax` is not committed OR `xmax` is in `xip` (deleting transaction not yet committed)

### Why MVCC Matters

- **SELECT never blocks INSERT/UPDATE/DELETE** (readers don't lock writers) — except for SELECT FOR UPDATE
- **INSERT/UPDATE/DELETE never blocks SELECT** (writers don't lock readers) — readers see old version
- **UPDATE blocks UPDATE** on the same row — both trying to acquire row lock

**The cost**: VACUUM must eventually clean up dead rows. Without VACUUM, tables bloat and performance degrades. (Covered in Phase 9.)

---

## 2.4 Locking

### Row-Level Locks

| Lock Mode | SQL Trigger | Conflicts With | Use Case |
|-----------|------------|:-------------:|----------|
| FOR UPDATE | `SELECT ... FOR UPDATE` | FOR UPDATE, FOR NO KEY UPDATE, FOR SHARE, FOR KEY SHARE | Wallet debit (exclusive lock) |
| FOR NO KEY UPDATE | `UPDATE` (non-key columns) | FOR UPDATE, FOR NO KEY UPDATE | Update non-key fields |
| FOR SHARE | `SELECT ... FOR SHARE` | FOR UPDATE, FOR NO KEY UPDATE | Read with intent to read again (prevents concurrent writes) |
| FOR KEY SHARE | FK checks automatically | FOR UPDATE, FOR NO KEY UPDATE | Foreign key validation |

### Deadlocks

```
Transaction A:                  Transaction B:
UPDATE accounts SET balance=90  UPDATE accounts SET balance=80
  WHERE id='U1';                 WHERE id='U2';
                                -- A holds lock on U1, B holds lock on U2
UPDATE accounts SET balance=110 UPDATE accounts SET balance=120
  WHERE id='U2'; ← WAITS FOR B  WHERE id='U1'; ← WAITS FOR A
                                    ↑
                            DEADLOCK! PostgreSQL detects this.
                            One transaction is rolled back (error 40P01).
                            The other continues.
```

**Prevention**: Always lock resources in a CONSISTENT ORDER. If both transactions lock U1 first, then U2, deadlock is impossible.

```sql
-- Always lock in alphabetical order of account_id
SELECT * FROM wallet_balances WHERE account_id IN ('U2', 'U1')
ORDER BY account_id FOR UPDATE;  -- Locks U1 first, then U2 — consistent order
```

### Advisory Locks

Application-defined locks not tied to database rows. Useful for: prevent concurrent execution of settlement batch, ensure only one reconciliation job runs at a time.

```sql
-- Transaction-level advisory lock (released on COMMIT/ROLLBACK)
SELECT pg_advisory_xact_lock(12345);  -- Lock ID 12345

-- Session-level advisory lock (released on disconnect)
SELECT pg_advisory_lock(12345);
SELECT pg_advisory_unlock(12345);
```

---

## 2.5 Indexes

### B-Tree (Default, Most Common)

Balanced tree, O(log n) lookup. Good for: equality (`=`) and range (`<`, `>`, `BETWEEN`, `ORDER BY`).

```sql
-- Primary key index (auto-created)
-- Index on foreign key (NOT auto-created!)
CREATE INDEX idx_payments_user_id ON payments(user_id);

-- Composite index: both columns filtered together
CREATE INDEX idx_payments_user_status ON payments(user_id, status);

-- Partial index: only index WHERE condition is met
CREATE INDEX idx_outbox_unprocessed ON outbox_events(created_at) WHERE processed = FALSE;

-- Covering index: include extra columns to enable index-only scan
CREATE INDEX idx_wallet_balance_account ON wallet_balances(account_id)
    INCLUDE (available_balance, version);

-- Expression index: index on function result
CREATE INDEX idx_payments_lower_email ON users(LOWER(email));
-- Use: SELECT * FROM users WHERE LOWER(email) = 'alice@example.com';
```

### When Indexes Help vs. Hurt

**Indexes HELP**:
- WHERE clause filters a small subset of rows (high selectivity)
- JOIN conditions (index on foreign key columns)
- ORDER BY (index provides ordering, avoids sort)

**Indexes HURT**:
- INSERT: must also insert into the index (slower writes)
- UPDATE: must update the index if indexed columns change
- DELETE: must remove from the index
- Small tables (< 1000 rows): sequential scan is faster than index lookup overhead

**Payment indexing strategy**:
```sql
-- journal_lines: most queries filter by account_id + created_at range
CREATE INDEX idx_journal_lines_account_date ON journal_lines(account_id, created_at);

-- wallet_balances: point lookup by account_id
CREATE INDEX idx_wallet_balances_account ON wallet_balances(account_id) INCLUDE (available_balance);

-- outbox_events: most queries filter by processed = FALSE
CREATE INDEX idx_outbox_unprocessed ON outbox_events(created_at) WHERE processed = FALSE;

-- idempotency_keys: point lookup by (api_key, key)
-- Already covered by PRIMARY KEY (api_key, key)
```

---

## 2.6 Hands-On Exercises

### Ex 2.1 — Isolation Level Experiment
Open two psql sessions. Run concurrent transactions at different isolation levels. Observe: (a) non-repeatable read at READ COMMITTED, (b) prevention at REPEATABLE READ, (c) serialization failure at SERIALIZABLE. Document your findings.

### Ex 2.2 — Deadlock Reproduction
Create two accounts. Session A: UPDATE U1, then UPDATE U2. Session B: UPDATE U2, then UPDATE U1 (opposite order). Observe PostgreSQL detect the deadlock. Fix by ordering locks consistently.

### Ex 2.3 — Index Effectiveness
Create a table with 1,000,000 rows. Run queries with and without indexes. Compare EXPLAIN ANALYZE output. Prove that a covering index enables index-only scan. Prove that a partial index is smaller than a full index.

### Ex 2.4 — MVCC Visibility
INSERT a row. BEGIN a transaction in another session. UPDATE the row in a third session. Observe what each session sees. Use `SELECT xmin, xmax, * FROM table` to see tuple metadata.

---

## 2.7 Self-Assessment

- [ ] Can explain all four ACID properties with concrete examples
- [ ] Can demonstrate non-repeatable read and serialization anomaly in psql
- [ ] Understand the difference between each row-level lock mode
- [ ] Can reproduce and fix a deadlock
- [ ] Can explain how MVCC enables concurrent reads and writes
- [ ] Know when to use a composite index, partial index, and covering index
- [ ] Can read EXPLAIN output to see which index was used
