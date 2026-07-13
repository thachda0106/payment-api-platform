-- V5__amount_minor_units.sql
-- Phase-9 P3: store amounts as bigint minor units (cents) instead of DECIMAL dollars.
-- Existing rows are converted by multiplying by 100 (DECIMAL(19,4) → integer cents).
ALTER TABLE payments
    ALTER COLUMN amount TYPE bigint USING (amount * 100)::bigint;

-- CHECK (amount > 0) from V1 remains valid for bigint minor units.
