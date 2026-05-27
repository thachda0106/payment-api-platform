# Mini Project — In-Memory Database Engine

## Goal

Build a simplified in-memory database engine that demonstrates your understanding of data structures, algorithms, and system design from Phase 0.

## Time Estimate
10-15 hours

## Requirements

### Core Features

1. **Storage Engine**
   - Store records in a hash table indexed by primary key (String key → Record)
   - Each record can have multiple String fields

2. **B-Tree Secondary Index**
   - Support a secondary index on any field using a B-tree
   - Support range queries: `findAll(field, minValue, maxValue)`
   - The B-tree must be implemented from scratch (no TreeMap, no library)

3. **Append-Only Transaction Log (WAL preview)**
   - Before any mutation (INSERT, UPDATE, DELETE), write the operation to a log
   - Log format: `[timestamp] [operation] [key] [old_value] [new_value]`
   - On restart, replay the log to restore state

4. **Transaction Support (BEGIN/COMMIT/ROLLBACK)**
   - `BEGIN`: Start a transaction
   - `COMMIT`: Apply all changes since BEGIN
   - `ROLLBACK`: Discard all changes since BEGIN
   - Only one active transaction at a time (simplified)
   - During a transaction, reads see the uncommitted changes (READ UNCOMMITTED isolation)

5. **Command Interface**
   - Interactive REPL (Read-Eval-Print Loop) or file-based execution
   - Commands:
     ```
     INSERT key field1=value1 field2=value2 ...
     SELECT key
     SELECT * WHERE field >= min AND field <= max
     UPDATE key field1=newValue
     DELETE key
     BEGIN
     COMMIT
     ROLLBACK
     EXIT
     ```

### Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Command Parser                      │
│  Parses: INSERT, SELECT, UPDATE, DELETE, BEGIN, ...   │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│                 Database Engine                       │
│                                                       │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │   Storage    │  │  Secondary   │  │ Transaction │ │
│  │   Engine     │  │  Index       │  │  Manager    │ │
│  │              │  │              │  │             │ │
│  │ Hash Table   │  │  B-Tree      │  │ BEGIN/      │ │
│  │ (primary key)│  │  (field)     │  │ COMMIT/     │ │
│  │              │  │              │  │ ROLLBACK    │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬─────┘ │
│         │                 │                  │        │
│         │                 │                  │        │
│  ┌──────▼─────────────────▼──────────────────▼─────┐ │
│  │              Write-Ahead Log (WAL)                │ │
│  │  Append-only file: [ts] [op] [key] [old] [new]   │ │
│  └──────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

### Data Structures to Implement

1. **Hash Table**: Chaining or open addressing. Support `put`, `get`, `remove`, `containsKey`.
2. **B-Tree**: Minimum degree t = 2 (each node has 1-3 keys, 2-4 children). Support `insert`, `search`, `rangeSearch(min, max)`. Each key maps to a set of record IDs (primary keys).
3. **Transaction State**: A map of pending changes. On COMMIT: apply to storage + index + write WAL. On ROLLBACK: discard.

### Constraints

- Everything is in-memory (except WAL which writes to disk)
- B-tree nodes are in-memory (not disk pages — simplified)
- No concurrency (single-threaded)
- Keys and values are Strings
- WAL file is plain text (JSON lines or custom format)

### Stretch Goals (Optional)

1. **Multiple indexes**: Support indexing on multiple fields
2. **Additional query operators**: `=`, `<`, `>`, `<=`, `>=`, `STARTS_WITH`
3. **Persistence**: Save/load the full database state to/from a file
4. **Simple query planner**: Choose index scan vs. full table scan based on selectivity
5. **B-Tree visualization**: Print the B-tree structure

### Example Session

```
> INSERT user1 name=Alice balance=100000 tier=PREMIUM
OK

> INSERT user2 name=Bob balance=50000 tier=BASIC
OK

> INSERT user3 name=Charlie balance=200000 tier=PREMIUM
OK

> SELECT user1
{name: Alice, balance: 100000, tier: PREMIUM}

> SELECT * WHERE balance >= 50000 AND balance <= 150000
[{user1: {name: Alice, balance: 100000, tier: PREMIUM}},
 {user2: {name: Bob, balance: 50000, tier: BASIC}}]

> BEGIN
TRANSACTION STARTED

> UPDATE user1 balance=90000
OK (uncommitted)

> SELECT user1
{name: Alice, balance: 90000, tier: PREMIUM}  (sees uncommitted change)

> ROLLBACK
TRANSACTION ROLLED BACK

> SELECT user1
{name: Alice, balance: 100000, tier: PREMIUM}  (original value restored)

> EXIT
Goodbye.
```

### What You Will Learn

1. How a database stores and retrieves data (hash table + B-tree)
2. How write-ahead logging works (this is what PostgreSQL, MySQL, and SQLite do)
3. How transactions work (BEGIN/COMMIT/ROLLBACK — the foundation of ACID)
4. How secondary indexes speed up queries (B-tree range scan vs. full scan)
5. How command parsing and execution flow works in a database

### Acceptance Criteria

Your engine must pass these tests:

1. Insert 1000 records, retrieve them all by primary key — no errors
2. Insert records, then build secondary index on `balance` field — range query returns correct results
3. BEGIN → UPDATE → COMMIT → SELECT sees new value
4. BEGIN → UPDATE → ROLLBACK → SELECT sees old value
5. Kill program after INSERT. Restart. WAL replay restores the inserted records.
6. Insert 10,000 records. Range query via B-tree returns in < 100ms (verify B-tree, not full scan)
