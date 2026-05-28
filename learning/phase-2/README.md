# Phase 2 — Database Fundamentals

> **Duration**: 3-4 weeks (full-time) | **Prerequisites**: Phase 1 (OS & Networking)
>
> **Goal**: Design normalized database schemas, write complex SQL queries with JOINs, subqueries, CTEs, and window functions, understand ACID transactions from the isolation level down, and read query execution plans.
>
> **Why this matters for the payment platform**: The payment ledger is a set of PostgreSQL tables. Every money movement is a SQL transaction. Every balance check is a query. You cannot design the ledger schema without understanding normalization. You cannot guarantee correct balances without understanding isolation levels. You cannot optimize payment queries without reading EXPLAIN ANALYZE output.

## Learning Objectives

After completing Phase 2, you will be able to:
1. Design a normalized (3NF/BCNF) database schema from a domain description
2. Write SQL queries with JOINs, subqueries, CTEs, window functions, and aggregations
3. Explain every ACID property and how PostgreSQL enforces them
4. Understand the four isolation levels and the anomalies each prevents
5. Choose the right index type for a given query pattern
6. Read EXPLAIN ANALYZE output and identify bottlenecks
7. Explain how MVCC enables concurrent reads and writes without blocking

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-3 | Module 01 | Relational model, normalization, DDL, constraints, data types | 8h |
| 4-6 | Module 01 | SQL DML, JOINs, subqueries, CTEs, window functions, aggregations | 10h |
| 7-8 | Module 02 | Transactions, ACID, isolation levels, anomalies, MVCC | 8h |
| 9-10 | Module 02 | Indexes (B-tree, Hash, GIN, covering, partial), locking | 8h |
| 11-12 | Module 03 | Query planning (EXPLAIN, ANALYZE, BUFFERS), cost model | 8h |
| 13-14 | Module 03 | Database design patterns, schema design exercises | 6h |
| 15-21 | Mini Project | Accounting Database (double-entry ledger with constraints) | 15h |

## Prerequisites Check

- [ ] PostgreSQL 16 installed and running (`psql --version`)
- [ ] Can create a database and connect: `createdb phase2; psql phase2`
- [ ] Understand basic terminal and file I/O from Phase 1
- [ ] Can write programs that connect to PostgreSQL (JDBC, psycopg2, pgx, pg)

## Setup

```bash
# Install PostgreSQL 16
# macOS: brew install postgresql@16
# Ubuntu: sudo apt install postgresql-16
# Windows: Download from postgresql.org

# Start PostgreSQL and create database
createdb phase2
psql phase2
```

## Resources

- **Primary book**: "Database Design for Mere Mortals" (Hernandez)
- **SQL practice**: pgexercises.com, sqlzoo.net
- **PostgreSQL docs**: postgresql.org/docs/16/ (the best database documentation)
- **Interactive**: DB Fiddle (dbfiddle.uk) for quick SQL experiments
- **Course**: CMU 15-445/645 Database Systems (free on YouTube)

## Connection to Next Phase

Phase 3 (Java Deep Dive) uses JDBC and JPA to interact with PostgreSQL. Phase 5 (Database Engineering — Part 5 in CURRICULUM.md) goes deep into PostgreSQL internals: storage engine, WAL, MVCC internals, query planner internals, vacuum, replication.
