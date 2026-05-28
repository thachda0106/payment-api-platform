# Mini Project — Production-Grade Ledger Database

## Goal

Design and implement a complete PostgreSQL ledger database that enforces all financial invariants at the database level: immutability, double-entry verification, automatic balance updates, idempotency, and audit trail.

## What You Will Build

A database with:
- **Chart of Accounts**: Hierarchical account tree with types and normal balances
- **Journal Entries + Lines**: Multi-line journal model (1 entry = N lines), partitioned by month
- **Wallet Balances**: Materialized projection updated by triggers in same transaction
- **Outbox Events**: Transactional outbox for CDC to Kafka
- **5 Indexes**: B-tree for point lookups, covering index for index-only scans, partial index for outbox, BRIN for append-only journal entries
- **4 Triggers**: Immutability (reject UPDATE/DELETE), double-entry verification (SUM(DEBIT)=SUM(CREDIT)), auto balance update, DEFERRABLE constraint for verification at COMMIT time

## Run

```bash
psql phase9 < mini-project/ledger_database.sql
```

## Acceptance Criteria

1. Insert valid journal entry → COMMIT succeeds, balances updated
2. Insert unbalanced entry (DEBIT≠CREDIT) → COMMIT fails with error
3. UPDATE/DELETE on journal_entries → rejected by immutability trigger
4. Duplicate idempotency_key → UNIQUE constraint violation
5. Debit with insufficient balance → CHECK constraint violation
6. Reconciliation query shows zero differences between wallet_balances and SUM(journal_lines)
