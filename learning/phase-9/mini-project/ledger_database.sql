-- ============================================================================
-- Mini Project: Production-Grade Ledger Database
-- Run: psql phase9 < ledger_database.sql
-- ============================================================================

-- ═══════════════════════════════════════════════════════════════════════════
-- Chart of Accounts (hierarchical)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE chart_of_accounts (
    account_id   VARCHAR(255) PRIMARY KEY,
    parent_id    VARCHAR(255) REFERENCES chart_of_accounts(account_id),
    name         VARCHAR(200) NOT NULL,
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    normal_balance VARCHAR(6) NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE
);

-- ═══════════════════════════════════════════════════════════════════════════
-- Journal Entries (immutable, append-only, partitioned)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE journal_entries (
    entry_id        UUID DEFAULT gen_random_uuid(),
    journal_id      UUID NOT NULL,
    reference_type  VARCHAR(50) NOT NULL CHECK (reference_type IN ('PAYMENT','TRANSFER','REFUND','FEE','SETTLEMENT','ADJUSTMENT')),
    reference_id    UUID NOT NULL,
    description     VARCHAR(500),
    idempotency_key VARCHAR(255) NOT NULL,
    prev_entry_hash BYTEA,
    entry_hash      BYTEA,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(100) NOT NULL,
    PRIMARY KEY (entry_id, created_at)
) PARTITION BY RANGE (created_at);

-- Monthly partitions
CREATE TABLE journal_entries_2026_05 PARTITION OF journal_entries
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE journal_entries_2026_06 PARTITION OF journal_entries
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE journal_lines (
    line_id     UUID DEFAULT gen_random_uuid(),
    entry_id    UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    account_id  VARCHAR(255) NOT NULL REFERENCES chart_of_accounts(account_id),
    entry_type  VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount      BIGINT NOT NULL CHECK (amount > 0),
    currency    CHAR(3) NOT NULL DEFAULT 'VND',
    line_order  INT NOT NULL DEFAULT 1,
    PRIMARY KEY (line_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE journal_lines_2026_05 PARTITION OF journal_lines
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE journal_lines_2026_06 PARTITION OF journal_lines
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

-- ═══════════════════════════════════════════════════════════════════════════
-- Wallet Balances (materialized projection)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE wallet_balances (
    account_id         VARCHAR(255) NOT NULL REFERENCES chart_of_accounts(account_id),
    currency           CHAR(3) NOT NULL DEFAULT 'VND',
    available_balance  BIGINT NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    pending_balance    BIGINT NOT NULL DEFAULT 0 CHECK (pending_balance >= 0),
    version            INT NOT NULL DEFAULT 1,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, currency)
) WITH (fillfactor = 80);

-- ═══════════════════════════════════════════════════════════════════════════
-- Outbox Events (for CDC)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE outbox_events (
    event_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type  VARCHAR(100) NOT NULL,
    payload     JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed   BOOLEAN NOT NULL DEFAULT FALSE
);

-- ═══════════════════════════════════════════════════════════════════════════
-- Indexes (payment-optimized)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE INDEX idx_journal_lines_account ON journal_lines(account_id, created_at);
CREATE UNIQUE INDEX idx_journal_entries_idempotency ON journal_entries(idempotency_key);
CREATE INDEX idx_wallet_balances_account ON wallet_balances(account_id) INCLUDE (available_balance, version);
CREATE INDEX idx_outbox_unprocessed ON outbox_events(created_at) WHERE processed = FALSE;
CREATE INDEX idx_journal_entries_created_brin ON journal_entries USING brin(created_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- Immutability Trigger
-- ═══════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN RAISE EXCEPTION 'Ledger tables are append-only. Cannot % on %', TG_OP, TG_TABLE_NAME; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutable BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_modification();
CREATE TRIGGER trg_journal_lines_immutable BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_modification();

-- ═══════════════════════════════════════════════════════════════════════════
-- Double-Entry Verification Trigger (DEFERRABLE — checks at COMMIT)
-- ═══════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION verify_double_entry()
RETURNS TRIGGER AS $$
DECLARE total_debit BIGINT; total_credit BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
    INTO total_debit, total_credit FROM journal_lines WHERE entry_id = NEW.entry_id;
    IF total_debit != total_credit THEN
        RAISE EXCEPTION 'Double-entry violation in entry %: DEBIT=% CREDIT=%', NEW.entry_id, total_debit, total_credit;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_verify_double_entry
    AFTER INSERT ON journal_lines DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_double_entry();

-- ═══════════════════════════════════════════════════════════════════════════
-- Balance Update Trigger
-- ═══════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION update_wallet_balance()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.entry_type = 'DEBIT' THEN
        UPDATE wallet_balances SET available_balance = available_balance - NEW.amount, version = version + 1, updated_at = NOW()
        WHERE account_id = NEW.account_id AND currency = NEW.currency;
    ELSE
        UPDATE wallet_balances SET available_balance = available_balance + NEW.amount, version = version + 1, updated_at = NOW()
        WHERE account_id = NEW.account_id AND currency = NEW.currency;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_update_balance AFTER INSERT ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION update_wallet_balance();

-- ═══════════════════════════════════════════════════════════════════════════
-- Seed Data + Test Transaction
-- ═══════════════════════════════════════════════════════════════════════════
INSERT INTO chart_of_accounts VALUES
    ('asset:bank:techcombank', NULL, 'Techcombank', 'ASSET', 'DEBIT', TRUE),
    ('liability:user_wallet:U1', NULL, 'User 1', 'LIABILITY', 'CREDIT', TRUE),
    ('liability:user_wallet:U2', NULL, 'User 2', 'LIABILITY', 'CREDIT', TRUE),
    ('liability:merchant_pending:M1', NULL, 'Merchant 1', 'LIABILITY', 'CREDIT', TRUE),
    ('revenue:platform_fee', NULL, 'Platform Fee', 'REVENUE', 'CREDIT', TRUE);

INSERT INTO wallet_balances VALUES ('liability:user_wallet:U1', 'VND', 500000, 0, 1),
    ('liability:user_wallet:U2', 'VND', 300000, 0, 1), ('liability:merchant_pending:M1', 'VND', 0, 0, 1);

-- Create a payment: U1 pays M1 100K VND with 1.5K fee
DO $$
DECLARE eid UUID := gen_random_uuid();
BEGIN
    INSERT INTO journal_entries (entry_id, journal_id, reference_type, reference_id, description, idempotency_key, created_by)
    VALUES (eid, gen_random_uuid(), 'PAYMENT', gen_random_uuid(), 'Test payment', 'phase9-test-001', 'system');

    INSERT INTO journal_lines (entry_id, account_id, entry_type, amount, currency, line_order)
    VALUES
        (eid, 'liability:user_wallet:U1',     'DEBIT',  100000, 'VND', 1),
        (eid, 'liability:merchant_pending:M1', 'CREDIT',  98500, 'VND', 2),
        (eid, 'revenue:platform_fee',         'CREDIT',   1500, 'VND', 3);
END $$;

-- ═══════════════════════════════════════════════════════════════════════════
-- Verification Queries
-- ═══════════════════════════════════════════════════════════════════════════
SELECT account_id, available_balance FROM wallet_balances ORDER BY account_id;
SELECT SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END) AS trial_balance FROM journal_lines;

-- Reconciliation: compare wallet_balances with SUM(journal_lines)
WITH ledger AS (
    SELECT account_id, SUM(CASE WHEN entry_type='CREDIT' THEN amount ELSE -amount END) AS ledger_balance
    FROM journal_lines GROUP BY account_id
)
SELECT wb.account_id, wb.available_balance AS wallet, COALESCE(l.ledger_balance, 0) AS ledger,
       wb.available_balance - COALESCE(l.ledger_balance, 0) AS diff
FROM wallet_balances wb LEFT JOIN ledger l ON wb.account_id = l.account_id
WHERE wb.available_balance IS DISTINCT FROM COALESCE(l.ledger_balance, 0);
