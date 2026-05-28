# Mini Project — Financial Core Ledger Service

## Goal

Build the core ledger service that implements double-entry accounting, idempotency, balance checks, and transactional guarantees — the heart of the payment platform.

## What You Will Build

A Java service implementing:
- **Journal Entry creation**: DEBIT one account, CREDIT another — atomic
- **Double-entry verification**: `SUM(DEBIT) == SUM(CREDIT)` enforced
- **Idempotency**: Duplicate idempotency keys return original result, don't double-charge
- **Balance check**: `SELECT FOR UPDATE`-style pessimistic balance verification
- **Transaction semantics**: All-or-nothing — if any step fails, rollback entire entry

## Architecture

```
CreateEntryCommand
    │
    ▼
LedgerService.createJournalEntry()
    │
    ├── 1. Idempotency check (UNIQUE key)
    ├── 2. Balance check (SELECT FOR UPDATE)
    ├── 3. INSERT journal_entry
    ├── 4. INSERT journal_lines (DEBIT + CREDIT)
    ├── 5. UPDATE wallet_balances
    └── 6. COMMIT (or ROLLBACK on any failure)
```

## Run

```bash
javac FinancialCoreLedger.java && java FinancialCoreLedger
```

## Acceptance Criteria

1. Valid payment → COMPLETED, balances updated correctly
2. Duplicate idempotency key → DUPLICATE, balances unchanged
3. Insufficient balance → FAILED, balances unchanged
4. Double-entry verification passes (DEBIT sum == CREDIT sum)
5. Each operation is atomic — no partial state if failure occurs

## What You Will Learn

- How `@Transactional` (simulated here) provides atomicity across multiple operations
- How to implement idempotency with UNIQUE key constraints
- How double-entry accounting is enforced at the application level
- The full lifecycle of a journal entry from request to balance update
