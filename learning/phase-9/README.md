# Phase 9 — PostgreSQL Internals & Performance

> **Duration**: 4-6 weeks | **Prerequisites**: Phase 2 (DB Fundamentals)
>
> **Goal**: Master PostgreSQL internals to the level where you can tune a production database for financial workloads, diagnose performance problems from `pg_stat_statements`, configure replication, and understand every line of an EXPLAIN output.
>
> **Why this is critical**: The ledger is PostgreSQL. Every journal entry, every balance update, every idempotency check is a PostgreSQL operation. If you lose or corrupt the database, you lose or corrupt the money. Understanding PostgreSQL internals isn't optional — it's the difference between a payment platform that scales and one that fails at 1000 transactions/second.

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-4 | Module 01 | Storage engine (pages/tuples), WAL (LSN/checkpoint/archiving), MVCC (xmin/xmax/snapshots/vacuum) | 16h |
| 5-8 | Module 02 | Query planner (cost model/statistics), indexes (all types), locking (row/table/advisory/deadlock), isolation (SSI) | 16h |
| 9-12 | Module 03 | Replication (streaming/logical), partitioning, vacuum tuning, performance tuning (shared_buffers/work_mem/PgBouncer), backup/PITR, monitoring (pg_stat_statements) | 16h |
| 13-18 | Exercises + Mini Project | MVCC lab, deadlock reproduction, WAL analysis, ledger database | 20h |

## Resources

- **Book**: "PostgreSQL 14 Internals" (Rogov) — THE internals book, free PDF
- **Doc**: PostgreSQL Official Documentation (postgresql.org/docs/16/)
- **Blog**: cybertec-postgresql.com, 2ndQuadrant
- **Tool**: pgMustard (EXPLAIN visualization), pg_stat_statements
