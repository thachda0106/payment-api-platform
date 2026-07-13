-- V8__amount_minor_units.sql
-- Phase-9 P3: store ledger amounts/balances as bigint minor units (cents).
-- Existing DECIMAL(19,4) values are converted by multiplying by 100.
ALTER TABLE accounts
    ALTER COLUMN balance TYPE bigint USING (balance * 100)::bigint;

ALTER TABLE journal_entries
    ALTER COLUMN amount TYPE bigint USING (amount * 100)::bigint,
    ALTER COLUMN balance_before TYPE bigint USING (balance_before * 100)::bigint,
    ALTER COLUMN balance_after TYPE bigint USING (balance_after * 100)::bigint;

-- CHECK (amount > 0) from V2 remains valid for bigint minor units.
