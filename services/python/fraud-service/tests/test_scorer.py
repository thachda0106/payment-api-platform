"""Tests for the fraud scorer with minor-unit (cents) thresholds (Phase-9 P3)."""

from types import SimpleNamespace

from fraud_service.scorer import FraudScorer

CFG = SimpleNamespace(
    high_value_threshold=100000.0,   # $1000.00 in cents
    velocity_threshold=3,
    velocity_window_seconds=60,
    velocity_sweep_every=1000,
)


def _score(amount_minor, customer="c1", merchant="m1"):
    return FraudScorer(CFG).score({
        "paymentId": "p1", "amount": amount_minor,
        "customerId": customer, "merchantId": merchant,
    })


def test_low_value_approved():
    assert _score(5000).decision == "APPROVED"          # $50.00


def test_high_value_review():
    r = _score(150000)                                  # $1500.00 > $1000
    assert r.decision == "REVIEW"
    assert r.score >= 30.0


def test_blacklisted_merchant_rejected():
    r = _score(5000, merchant="fraud-merchant-1")
    assert r.decision == "REJECTED"
    assert r.score == 100.0


def test_threshold_boundary_not_exceeded():
    assert _score(100000).decision == "APPROVED"        # exactly $1000, not strictly greater
