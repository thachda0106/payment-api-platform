-- V5__ledger_dedup_index.sql
-- Belt-and-suspenders: prevents duplicate entry rows within a single
-- ledger transaction (same txn, same account, same direction).
-- Does NOT prevent multiple ledger transactions for the same payment —
-- the application-level existsByPaymentId guard in LedgerService handles that.
--
-- Pre-check: migration fails with a clear message if duplicates already exist.
DO $$
DECLARE
    dupe_count INTEGER;
BEGIN
    SELECT count(*) INTO dupe_count FROM (
        SELECT ledger_transaction_id, account_id, entry_type
        FROM journal_entries
        GROUP BY 1, 2, 3
        HAVING count(*) > 1
    ) t;

    IF dupe_count > 0 THEN
        RAISE EXCEPTION 'V5 pre-check failed: % duplicate journal-entry rows exist. '
            'Repair data before re-running this migration.', dupe_count;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS idx_journal_txn_account_entry
    ON journal_entries(ledger_transaction_id, account_id, entry_type);
