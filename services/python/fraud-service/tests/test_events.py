"""Tests for vertical-slice event contract validation (fraud-service)."""

import pytest

from fraud_service.events import EventValidationError, validate_payment_event

VALID = {
    "eventId": "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
    "paymentId": "9f8c7b6a-1234-4c5d-8e9f-001122334455",
    "amount": "99.99",
    "currency": "USD",
    "customerId": "c1",
    "merchantId": "m1",
}


def test_valid_event_passes():
    validate_payment_event(dict(VALID))


def test_missing_event_id():
    e = dict(VALID); del e["eventId"]
    with pytest.raises(EventValidationError, match="eventId"):
        validate_payment_event(e)


def test_payment_id_not_uuid():
    e = dict(VALID); e["paymentId"] = "not-a-uuid"
    with pytest.raises(EventValidationError, match="paymentId"):
        validate_payment_event(e)


def test_negative_amount():
    e = dict(VALID); e["amount"] = "-5.00"
    with pytest.raises(EventValidationError, match="amount"):
        validate_payment_event(e)


def test_zero_amount():
    e = dict(VALID); e["amount"] = "0"
    with pytest.raises(EventValidationError, match="amount"):
        validate_payment_event(e)


def test_bad_currency():
    e = dict(VALID); e["currency"] = "usd"
    with pytest.raises(EventValidationError, match="currency"):
        validate_payment_event(e)


def test_missing_customer():
    e = dict(VALID); del e["customerId"]
    with pytest.raises(EventValidationError, match="customerId"):
        validate_payment_event(e)
