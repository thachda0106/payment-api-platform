# Module 01 — Storage Engine, WAL & MVCC

## 1.1 Storage Engine: Pages and Tuples

### Heap Files

PostgreSQL stores table data in heap files: `PGDATA/base/{dboid}/{relfilenode}`. Each file contains 8KB pages.

**Page structure** (8KB):
```
┌────────────────────────────────────────────────────┐
│ PageHeader (24 bytes)                               │ ← LSN, checksum, flags
├────────────────────────────────────────────────────┤
│ ItemId array (4 bytes each)                         │ ← Line pointers (offset, length, flags)
│ Items grow DOWN from top                            │
├────────────────────────────────────────────────────┤
│ Free space                                          │
├────────────────────────────────────────────────────┤
│ Tuples (actual row data)                            │
│ Tuples grow UP from bottom                          │
├────────────────────────────────────────────────────┤
│ Special space (index-specific)                      │
└────────────────────────────────────────────────────┘
```

### Tuple Structure

Each row is a "tuple" (heap tuple). Key fields in tuple header:
- `t_xmin`: Transaction ID that INSERTED this tuple
- `t_xmax`: Transaction ID that DELETED/UPDATED this tuple (0 = still visible)
- `t_ctid`: Current tuple ID `(page_number, offset)`. Gets updated on UPDATE (old tuple points to new)
- `t_infomask`: Visibility flags — `HEAP_XMIN_COMMITTED`, `HEAP_XMAX_INVALID`, etc.

**UPDATE does NOT modify in place**: It inserts a NEW tuple, marks the old one as dead (sets `xmax`), and updates indexes. The old tuple remains until VACUUM reclaims it.

### TOAST (The Oversized-Attribute Storage Technique)

Values larger than ~2KB are stored externally in a separate TOAST table. The main table stores a TOAST pointer. Strategies: PLAIN (inline), EXTENDED (compressed + external), EXTERNAL (external, no compression), MAIN (inline if fits, compressed if not). Financial data rarely exceeds 2KB, so TOAST is not primary concern for the ledger.

### fillfactor

Reserves space in each page for future UPDATEs. Default: 100 (no reserved space). For tables with frequent UPDATEs on variable-length columns, set to 70-80 to keep updated rows on the same page (HOT — Heap-Only Tuple updates, no index modification needed).

```sql
ALTER TABLE wallet_balances SET (fillfactor = 80);
```

## 1.2 Write-Ahead Log (WAL)

### Why WAL Exists

If PostgreSQL crashes after modifying a page in `shared_buffers` (memory) but before writing it to disk, the changes are lost. WAL ensures durability: ALL changes are first written to WAL (sequential, fast), then later flushed to data files (random, slow). On crash recovery, PostgreSQL replays WAL from the last checkpoint.

```
Client → SQL → shared_buffers (memory)
                  │
                  ├──→ WAL buffers (memory)
                  │       │
                  │       ▼ (WAL writer: wal_writer_delay=200ms)
                  │    pg_wal/ (disk) — WAL segments (16MB each)
                  │       │
                  │       ▼ (archive_command / pg_receivewal)
                  │    WAL archive (for PITR and replication)
                  │
                  ▼ (checkpointer + background writer)
               data files (disk — delayed writes for performance)
```

### LSN (Log Sequence Number)

A 64-bit monotonically increasing byte offset into the WAL. Every page header stores the LSN of the last WAL record that modified it. On recovery: replay WAL where `WAL_LSN > page_LSN`.

### Checkpoints

A checkpoint writes ALL dirty pages from `shared_buffers` to disk and records the checkpoint LSN. WAL before that LSN is no longer needed for crash recovery (but may be needed for replication or PITR).

```ini
checkpoint_timeout = 5min           # Max time between checkpoints
max_wal_size = 1GB                  # Soft limit — triggers CHECKPOINT
checkpoint_completion_target = 0.9  # Spread I/O over 90% of interval
```

**Payment relevance**: A checkpoint during peak payment volume causes an I/O storm. Monitor `pg_stat_bgwriter` — `checkpoints_req` (WAL limit exceeded) vs `checkpoints_timed` (timeout). Timed checkpoints are preferred.

### WAL Configuration for Financial Workloads

```ini
wal_level = logical              # Required for Debezium CDC
wal_compression = zstd           # Reduce WAL volume (CPU trade-off)
wal_log_hints = on               # Required for pg_rewind
max_wal_senders = 10             # For replication + logical decoding
wal_keep_size = 1GB              # Retain WAL for replication catch-up
```

## 1.3 MVCC Deep Dive

### Why MVCC

PostgreSQL doesn't lock rows when you SELECT (unlike 2PL databases). Instead, it maintains MULTIPLE VERSIONS of each row. Each transaction sees a "snapshot" of the database as of its start time. This means: readers never block writers, writers never block readers.

### How Snapshots Work

When a transaction starts, PostgreSQL records:
```
Snapshot {
    xmin:  200   // Oldest active XID — tuples with xmax < 200 are definitely dead
    xmax:  205   // Next unassigned XID — tuples with xmin >= 205 are from the future
    xip: [201, 203, 204]  // Active (in-progress) XIDs
}
```

### Tuple Visibility Rules

A tuple is VISIBLE if ALL of:
1. `t_xmin` is committed (`t_xmin < snapshot.xmin` OR committed and not in `snapshot.xip`)
2. `t_xmax` is NOT committed (`= 0` OR aborted OR in `snapshot.xip`)

### Transaction ID Wraparound

XIDs are 32-bit (~4 billion). They WRAP AROUND. PostgreSQL uses modulo arithmetic with a 2 billion "horizon." Tuples with XIDs more than 2 billion transactions old appear to be "from the future" and become INVISIBLE — DATA LOSS.

**Prevention**: VACUUM FREEZE marks old tuples with `FrozenTransactionId` (special value = 2) which is always visible.

```sql
-- Monitor wraparound risk
SELECT datname, age(datfrozenxid) FROM pg_database ORDER BY age DESC;
-- If age > 1,000,000,000 → aggressive VACUUM needed
-- If age > 2,000,000,000 → database will shut down for safety
```

### VACUUM

Removes dead tuples (old versions no longer visible to any transaction). Without VACUUM, tables bloat and performance degrades.

```sql
-- See dead tuple count
SELECT schemaname, relname, n_dead_tup, n_live_tup, last_vacuum, last_autovacuum
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC;

-- Manual VACUUM
VACUUM (VERBOSE, ANALYZE) wallet_balances;

-- Aggressive VACUUM (freezes tuples to prevent wraparound)
VACUUM FREEZE;
```

### Vacuum Tuning for High-Write Tables

```sql
ALTER TABLE wallet_balances SET (
    autovacuum_vacuum_scale_factor = 0.01,  -- 1% dead tuples (not default 20%)
    autovacuum_vacuum_threshold = 100
);
ALTER TABLE journal_entries SET (
    autovacuum_vacuum_scale_factor = 0.05,  -- 5%
    autovacuum_vacuum_threshold = 1000
);
```

## 1.4 Exercises

### Ex 1.1 — MVCC Visibility Lab
Open 3 psql sessions. Session A: BEGIN; INSERT a row. Session B: BEGIN; SELECT (can it see A's insert?). Session A: COMMIT. Session B: SELECT (can it see now?). Session C (new): SELECT. Use `SELECT xmin, xmax, ctid, * FROM table` to see tuple metadata.

### Ex 1.2 — WAL Generation Rate
Generate 100K INSERTs. Use `SELECT pg_current_wal_lsn()` before and after. Calculate WAL bytes per transaction. Use `pg_wal_lsn_diff()` to measure.

### Ex 1.3 — Dead Tuple Accumulation
INSERT 10K rows. DELETE 5K. Check `n_dead_tup`. Run `VACUUM`. Check again. Observe the effect.

## 1.5 Self-Assessment

- [ ] Can draw the 8KB page structure from memory
- [ ] Understand what happens to the old tuple during an UPDATE (new tuple inserted, old marked dead)
- [ ] Can explain the WAL → checkpoint → crash recovery flow
- [ ] Understand tuple visibility using xmin, xmax, and snapshot
- [ ] Know why transaction ID wraparound is dangerous and how to prevent it
- [ ] Can tune autovacuum for high-write financial tables
