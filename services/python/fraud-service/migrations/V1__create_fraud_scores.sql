-- V1__create_fraud_scores.sql
-- Fraud detection results for each scored payment
CREATE TABLE fraud_scores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL,
    score DECIMAL(5,2) NOT NULL,
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('APPROVED', 'REVIEW', 'REJECTED')),
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_scores_payment ON fraud_scores(payment_id);
