# Module 03 — Replication, Partitioning, Tuning, Backup & Monitoring

## 3.1 Replication

### Streaming (Physical) Replication

Primary sends WAL records to standby via replication slot. Standby replays WAL → physical copy of primary.

```ini
# Primary
wal_level = replica
max_wal_senders = 5
wal_keep_size = 1GB
# Create replication slot: SELECT * FROM pg_create_physical_replication_slot('standby1');

# Standby
primary_conninfo = 'host=primary port=5432 user=replicator password=...'
primary_slot_name = 'standby1'
restore_command = 'cp /archive/%f %p'
```

**synchronous_commit options**: `on` (wait for local WAL flush), `remote_apply` (wait for standby to apply — strongest, slowest), `remote_write` (wait for standby to receive), `off` (don't wait — data loss on crash).

### Logical Replication

Publisher defines "publication" (set of tables). Subscriber defines "subscription." Uses `pgoutput` decoding plugin. Reads WAL, decodes changes, sends to subscriber. Used by Debezium for CDC.

```sql
-- Publisher
CREATE PUBLICATION payment_pub FOR TABLE payments, journal_entries, journal_lines;

-- Subscriber
CREATE SUBSCRIPTION payment_sub CONNECTION 'host=primary ...' PUBLICATION payment_pub;
```

## 3.2 Partitioning

```sql
-- Declarative partitioning by RANGE (monthly)
CREATE TABLE journal_entries (
    entry_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL, ...
) PARTITION BY RANGE (created_at);

CREATE TABLE journal_entries_2026_01 PARTITION OF journal_entries
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE journal_entries_2026_02 PARTITION OF journal_entries
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
-- ... up to 36 months

-- Partition pruning: query only scans relevant partitions
SELECT * FROM journal_entries WHERE created_at >= '2026-05-01';
-- Scans only 2026_05 partition (others pruned!)

-- Archival: DROP old partition (fast — just unlink files)
DROP TABLE journal_entries_2020_01;
```

### Why Partition for the Ledger

- Query performance: partition pruning avoids scanning all time
- Maintenance: VACUUM one partition at a time (not entire table)
- Archival: DROP whole partition (instant) instead of DELETE (slow, generates WAL)
- Index management: create/drop indexes per partition

## 3.3 Performance Tuning

### Memory Configuration (for db.r6g.xlarge: 4 vCPU, 32 GB RAM)

```ini
shared_buffers = 8GB              # 25% of RAM — PostgreSQL's internal cache
effective_cache_size = 24GB       # 75% of RAM — planner estimates OS cache
work_mem = 256MB                  # Per-operation sort/hash memory
maintenance_work_mem = 1GB        # For VACUUM, CREATE INDEX
random_page_cost = 1.1            # SSD — prefer index scans
effective_io_concurrency = 200    # NVMe concurrent I/O
max_connections = 50              # Limit — use PgBouncer!
```

### Connection Pooling with PgBouncer

PostgreSQL forks a process per connection (~5-10 MB each). 100 connections = 500MB-1GB overhead. PgBouncer maintains a small pool of actual PostgreSQL connections (20-50) and multiplexes client connections (500+).

```ini
# pgbouncer.ini
pool_mode = transaction  # Return connection after each transaction
default_pool_size = 25   # Connections per database
max_client_conn = 500
```

### Query Tuning Checklist

1. Run `EXPLAIN (ANALYZE, BUFFERS)` on slow query
2. Check `rows` estimate vs `actual rows` — if far off, run `ANALYZE`
3. Check `Buffers: shared read` — high = data not in shared_buffers (increase size or add index)
4. Check `Sort Method: external merge` — sort spills to disk → increase `work_mem`
5. Check `Hash Batches: N > 1` — hash spills to disk → increase `work_mem`
6. Check for sequential scan on large table → add index on WHERE columns
7. Check for Nested Loop on large tables → force hash join or add missing index

## 3.4 Backup & Point-in-Time Recovery

```bash
# Base backup
pg_basebackup -D /backup/base -Ft -z -P -X stream

# WAL archiving (postgresql.conf)
archive_mode = on
archive_command = 'test ! -f /archive/%f && cp %p /archive/%f'

# PITR recovery (recovery.signal file + restore_command)
restore_command = 'cp /archive/%f %p'
recovery_target_time = '2026-05-27 10:30:00'
```

### pgBackRest (Recommended)

Parallel backup, delta restore, encryption, S3 support.

```bash
pgbackrest --stanza=prod backup --type=full
pgbackrest --stanza=prod restore --delta --target-time="2026-05-27 10:30:00"
```

### Backup Strategy for Payment Platform
- Full backup: Daily at 2 AM (off-peak)
- WAL archiving: Continuous (`archive_timeout = 60s`)
- Retention: 30 days
- Restore test: Monthly (automated in staging)

## 3.5 Monitoring

### Essential Queries

```sql
-- Top queries by total time
SELECT queryid, calls, mean_exec_time, total_exec_time, rows, shared_blks_hit, shared_blks_read, LEFT(query, 100) AS query
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;

-- Cache hit ratio
SELECT datname, blks_hit * 100.0 / NULLIF(blks_hit + blks_read, 0) AS hit_ratio
FROM pg_stat_database WHERE datname = current_database();
-- Must be > 99%

-- Dead tuples (vacuum lagging)
SELECT relname, n_dead_tup, n_live_tup, last_autovacuum
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 10;

-- Transaction ID wraparound risk
SELECT datname, age(datfrozenxid) FROM pg_database ORDER BY age DESC;

-- Replication lag (bytes)
SELECT client_addr, state, pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS lag_bytes
FROM pg_stat_replication;

-- Blocking queries (lock waits)
SELECT blocked.pid, blocked.query AS blocked_query, blocking.pid AS blocking_pid, blocking.query AS blocking_query
FROM pg_stat_activity blocked JOIN pg_locks bl ON blocked.pid = bl.pid AND NOT bl.granted
JOIN pg_locks bkl ON bl.locktype = bkl.locktype AND bl.database = bkl.database AND bl.relation = bkl.relation AND bkl.granted
JOIN pg_stat_activity blocking ON bkl.pid = blocking.pid;
```

### Alert Thresholds

| Metric | Warning | Critical |
|--------|:-------:|:--------:|
| Cache hit ratio | < 99% | < 95% |
| Dead tuples (per table) | > 1M | > 10M |
| Transaction ID age | > 200M | > 1B |
| Replication lag | > 10MB | > 100MB |
| Lock waits | > 0 for 1s | > 10 for 10s |
| Connection usage | > 70% | > 90% |

## 3.6 Exercises

### Ex 3.1 — Replication Setup
Set up streaming replication: primary + standby. Measure: (a) replication lag under idle, (b) replication lag under 1000 INSERTs/second, (c) failover procedure (promote standby).

### Ex 3.2 — Partitioning Migration
Create a table with 10M rows. Partition by month. Migrate data. Compare: (a) query performance before/after, (b) VACUUM duration before/after, (c) index creation time before/after.

### Ex 3.3 — Backup & Restore
Take a base backup. Insert data. Archive WAL. Simulate disaster (rm -rf data directory). Restore to point-in-time. Verify data is correct.

### Ex 3.4 — Diagnostic Drill
Given a "database is slow" scenario: use `pg_stat_activity`, `pg_stat_statements`, `pg_locks`, `pg_stat_user_tables` to diagnose the root cause. Write incident report with fix.

## 3.7 Self-Assessment

- [ ] Can set up streaming replication with replication slots
- [ ] Understand the difference between physical and logical replication
- [ ] Can design a partitioning strategy for a 100M row/month payment table
- [ ] Know the purpose of every major postgresql.conf parameter for financial workloads
- [ ] Can configure PgBouncer with transaction pooling
- [ ] Can perform a backup and PITR restore
- [ ] Can diagnose a slow database using pg_stat_statements and pg_stat_activity
