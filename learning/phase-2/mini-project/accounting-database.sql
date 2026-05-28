-- ============================================================================
-- Mini Project: Accounting Database (Double-Entry Ledger)
-- Run: psql phase2 < mini-project.sql
-- ============================================================================

-- ─── Chart of Accounts ─────────────────────────────────────────────────────

CREATE TABLE chart_of_accounts (
    account_id   VARCHAR(255) PRIMARY KEY,
    parent_id    VARCHAR(255) REFERENCES chart_of_accounts(account_id),
    name         VARCHAR(200) NOT NULL,
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    normal_balance VARCHAR(6) NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─── Journal Entries (immutable, append-only) ──────────────────────────────

CREATE TABLE journal_entries (
    entry_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_id      UUID NOT NULL,
    reference_type  VARCHAR(50) NOT NULL,
    reference_id    UUID NOT NULL,
    description     VARCHAR(500),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    prev_entry_hash BYTEA,
    entry_hash      BYTEA,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(100) NOT NULL
);

CREATE TABLE journal_lines (
    line_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id    UUID NOT NULL REFERENCES journal_entries(entry_id),
    account_id  VARCHAR(255) NOT NULL REFERENCES chart_of_accounts(account_id),
    entry_type  VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount      BIGINT NOT NULL CHECK (amount > 0),
    currency    CHAR(3) NOT NULL DEFAULT 'VND',
    line_order  INT NOT NULL DEFAULT 1
);

-- ─── Wallet Balances (materialized projection) ────────────────────────────

CREATE TABLE wallet_balances (
    account_id        VARCHAR(255) NOT NULL REFERENCES chart_of_accounts(account_id),
    currency          CHAR(3) NOT NULL DEFAULT 'VND',
    available_balance BIGINT NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    pending_balance   BIGINT NOT NULL DEFAULT 0 CHECK (pending_balance >= 0),
    version           INT NOT NULL DEFAULT 1,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, currency)
);

-- ─── Indexes ───────────────────────────────────────────────────────────────

CREATE INDEX idx_journal_lines_account ON journal_lines(account_id, created_at);
CREATE INDEX idx_journal_entries_reference ON journal_entries(reference_type, reference_id);
CREATE INDEX idx_journal_entries_created ON journal_entries(created_at);

-- ─── Immutability Trigger ──────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger tables are append-only. Cannot % on %', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_modification();

CREATE TRIGGER trg_journal_lines_immutable
    BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_modification();

-- ─── Double-Entry Verification Trigger ─────────────────────────────────────

CREATE OR REPLACE FUNCTION verify_double_entry()
RETURNS TRIGGER AS $$
DECLARE
    total_debit  BIGINT;
    total_credit BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
    INTO total_debit, total_credit
    FROM journal_lines
    WHERE entry_id = NEW.entry_id;

    IF total_debit != total_credit THEN
        RAISE EXCEPTION 'Double-entry violation in entry %: DEBIT=% CREDIT=%', NEW.entry_id, total_debit, total_credit;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_verify_double_entry
    AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_double_entry();

-- ─── Balance Update Trigger ────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION update_wallet_balance()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.entry_type = 'DEBIT' THEN
        UPDATE wallet_balances
        SET available_balance = available_balance - NEW.amount,
            version = version + 1, updated_at = NOW()
        WHERE account_id = NEW.account_id AND currency = NEW.currency;
    ELSE
        UPDATE wallet_balances
        SET available_balance = available_balance + NEW.amount,
            version = version + 1, updated_at = NOW()
        WHERE account_id = NEW.account_id AND currency = NEW.currency;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_update_balance
    AFTER INSERT ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION update_wallet_balance();

-- ─── Seed Data ─────────────────────────────────────────────────────────────

INSERT INTO chart_of_accounts (account_id, parent_id, name, account_type, normal_balance) VALUES
    ('asset', NULL, 'Assets', 'ASSET', 'DEBIT'),
    ('asset:bank', 'asset', 'Bank Accounts', 'ASSET', 'DEBIT'),
    ('asset:bank:techcombank', 'asset:bank', 'Techcombank', 'ASSET', 'DEBIT'),
    ('liability', NULL, 'Liabilities', 'LIABILITY', 'CREDIT'),
    ('liability:user_wallet', 'liability', 'User Wallets', 'LIABILITY', 'CREDIT'),
    ('liability:user_wallet:U1', 'liability:user_wallet', 'User 1 Wallet', 'LIABILITY', 'CREDIT'),
    ('liability:user_wallet:U2', 'liability:user_wallet', 'User 2 Wallet', 'LIABILITY', 'CREDIT'),
    ('liability:merchant_pending', 'liability', 'Merchant Pending', 'LIABILITY', 'CREDIT'),
    ('liability:merchant_pending:M1', 'liability:merchant_pending', 'Merchant 1', 'LIABILITY', 'CREDIT'),
    ('equity', NULL, 'Equity', 'EQUITY', 'CREDIT'),
    ('equity:retained_earnings', 'equity', 'Retained Earnings', 'EQUITY', 'CREDIT'),
    ('revenue', NULL, 'Revenue', 'REVENUE', 'CREDIT'),
    ('revenue:platform_fee', 'revenue', 'Platform Fee', 'REVENUE', 'CREDIT'),
    ('expense', NULL, 'Expenses', 'EXPENSE', 'DEBIT'),
    ('expense:chargeback_loss', 'expense', 'Chargeback Loss', 'EXPENSE', 'DEBIT'),
    ('expense:bank_fee', 'expense', 'Bank Fee', 'EXPENSE', 'DEBIT');

INSERT INTO wallet_balances (account_id, available_balance) VALUES
    ('liability:user_wallet:U1', 500000),
    ('liability:user_wallet:U2', 300000),
    ('liability:merchant_pending:M1', 0);

-- ─── Create a test journal entry ───────────────────────────────────────────

-- Payment: U1 pays M1 100,000 VND with 1,500 VND fee
INSERT INTO journal_entries (journal_id, reference_type, reference_id, description, idempotency_key, created_by)
VALUES (gen_random_uuid(), 'PAYMENT', gen_random_uuid(), 'Test payment U1→M1', 'test-key-001', 'system');

INSERT INTO journal_lines (entry_id, account_id, entry_type, amount, currency, line_order)
SELECT entry_id, account_id, entry_type, amount, currency, line_order
FROM (VALUES
    ('liability:user_wallet:U1',     'DEBIT',  100000, 'VND', 1),
    ('liability:merchant_pending:M1', 'CREDIT',  98500, 'VND', 2),
    ('revenue:platform_fee',         'CREDIT',   1500, 'VND', 3)
) AS v(account_id, entry_type, amount, currency, line_order)
CROSS JOIN (SELECT entry_id FROM journal_entries WHERE idempotency_key = 'test-key-001') e;

-- ─── Verify ────────────────────────────────────────────────────────────────

-- Check balances
SELECT account_id, available_balance FROM wallet_balances WHERE account_id LIKE 'liability:%';

-- Check trial balance (should be 0)
SELECT SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) AS trial_balance
FROM journal_lines;

-- Expected: U1 balance = 400000, M1 balance = 98500, platform_fee = 1500 (revenue, not in wallet_balances)
-- Trial balance: DEBIT(100000) - CREDIT(98500) - CREDIT(1500) = 0 ✓

-- ─── Reconciliation Query ──────────────────────────────────────────────────

-- Compare wallet_balances with SUM(journal_lines)
WITH ledger_balances AS (
    SELECT account_id, currency,
           SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS ledger_balance
    FROM journal_lines
    GROUP BY account_id, currency
)
SELECT wb.account_id, wb.available_balance AS wallet_balance,
       COALESCE(lb.ledger_balance, 0) AS ledger_balance,
       wb.available_balance - COALESCE(lb.ledger_balance, 0) AS difference
FROM wallet_balances wb
FULL OUTER JOIN ledger_balances lb ON wb.account_id = lb.account_id AND wb.currency = lb.currency
WHERE wb.available_balance IS DISTINCT FROM COALESCE(lb.ledger_balance, 0)
ORDER BY ABS(wb.available_balance - COALESCE(lb.ledger_balance, 0)) DESC;

-- ─── Chart of Accounts Tree Query ──────────────────────────────────────────

WITH RECURSIVE account_tree AS (
    SELECT account_id, parent_id, name, account_type, 1 AS level,
           ARRAY[account_id] AS path
    FROM chart_of_accounts WHERE parent_id IS NULL
    UNION ALL
    SELECT coa.account_id, coa.parent_id, coa.name, coa.account_type, at.level + 1,
           at.path || coa.account_id
    FROM chart_of_accounts coa
    JOIN account_tree at ON coa.parent_id = at.account_id
)
SELECT repeat('  ', level - 1) || name AS account_name, account_type, level
FROM account_tree ORDER BY path;
