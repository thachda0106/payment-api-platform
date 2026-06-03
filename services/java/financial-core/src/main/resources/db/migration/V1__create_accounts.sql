-- V1__create_accounts.sql
-- Double-entry accounting accounts
-- balance is a CACHED PROJECTION — journal_entries is the source of truth.
CREATE TYPE account_type AS ENUM (
    'CUSTOMER_WALLET',
    'MERCHANT_PAYABLE',
    'PLATFORM_FEE_REVENUE',
    'SETTLEMENT_ACCOUNT'
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_ref VARCHAR(64) UNIQUE NOT NULL,  -- customer-1, merchant-1, PLATFORM
    account_type account_type NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance DECIMAL(19,4) NOT NULL DEFAULT 0,   -- CACHED PROJECTION
    version BIGINT NOT NULL DEFAULT 0,           -- optimistic locking
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN accounts.balance IS 'Cached projection. Source of truth: journal_entries. Rebuildable via SUM(journal_entries) GROUP BY account_id.';

-- Seed platform fee revenue account
INSERT INTO accounts (external_ref, account_type, currency, balance)
VALUES ('PLATFORM', 'PLATFORM_FEE_REVENUE', 'USD', 0)
ON CONFLICT (external_ref) DO NOTHING;
