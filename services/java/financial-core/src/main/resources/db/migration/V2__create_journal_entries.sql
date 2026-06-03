-- V2__create_journal_entries.sql
-- Double-entry journal — source of truth for all account balances.
-- Each payment generates multiple entries linked by ledger_transaction_id.
-- Invariant: SUM(CASE WHEN entry_type='CREDIT' THEN amount ELSE -amount END) = 0
--            for a given ledger_transaction_id.
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_transaction_id UUID NOT NULL,         -- groups entries per payment (audit trail)
    payment_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount DECIMAL(19,4) NOT NULL CHECK (amount > 0),
    balance_before DECIMAL(19,4) NOT NULL,
    balance_after DECIMAL(19,4) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_journal_txn ON journal_entries(ledger_transaction_id);
CREATE INDEX idx_journal_payment ON journal_entries(payment_id);
CREATE INDEX idx_journal_account ON journal_entries(account_id);
