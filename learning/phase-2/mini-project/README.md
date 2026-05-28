# Mini Project — Accounting Database

## Goal

Design and implement a double-entry accounting database that enforces financial invariants at the database level: immutability, double-entry verification, and automatic balance updates.

## What You Will Build

A complete ledger database with:
- Chart of accounts (hierarchical)
- Journal entries and journal lines (append-only, immutable)
- Wallet balances (materialized projection, updated by triggers)
- Double-entry verification (SUM(DEBIT) = SUM(CREDIT))
- Hash-chained audit trail
- Reconciliation query

## Files

- `accounting-database.sql` — Complete SQL implementation (run with psql)

## How to Run

```bash
psql phase2 < learning/phase-2/mini-project/accounting-database.sql
```

## Acceptance Criteria

1. **Insert a journal entry**: Create a payment (DEBIT user, CREDIT merchant + fee). Verify the double-entry trigger accepts it.
2. **Violation detection**: Try to insert unbalanced lines (DEBIT=100000, CREDIT=50000). Verify the trigger REJECTS with an error.
3. **Immutability**: Try to UPDATE or DELETE a journal entry. Verify the trigger blocks it.
4. **Balance update**: After inserting a journal entry, verify wallet_balances are updated automatically.
5. **Reconciliation**: Run the reconciliation query. Verify all balances match (difference = 0).
6. **Hash chain**: Verify each journal entry's hash is computed from the previous entry's hash — tampering is detectable.

## What You Will Learn

- How to enforce business rules at the database level (CHECK, TRIGGER)
- How to design an append-only ledger (immutable, auditable)
- How materialized views (wallet_balances) work as performance optimizations
- How deferred constraint triggers work (INITIALLY DEFERRED — check at COMMIT, not per-row)
- How to write a reconciliation query (FULL OUTER JOIN + COALESCE)
- How to use recursive CTEs for hierarchical data (chart of accounts tree)

## Connection to Phase 9

Phase 9 (PostgreSQL Internals) goes deeper into:
- How triggers are implemented internally
- MVCC visibility of trigger-affected rows
- Performance impact of STATEMENT vs ROW triggers
- Partitioning strategy for journal_entries (monthly RANGE partitioning)
