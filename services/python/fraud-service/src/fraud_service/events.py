"""Vertical-slice event contract validation (no I/O dependencies)."""

import re
import uuid
from decimal import Decimal, InvalidOperation

_CURRENCY_RE = re.compile(r"^[A-Z]{3}$")


class EventValidationError(ValueError):
    """Raised when an inbound event violates the vertical-slice contract."""


def validate_payment_event(event: dict) -> None:
    """Validate the shared-contract fields required to score + post a payment.

    Backward-compatible: `v` and `timestamp` are optional (payment-service, an
    unmodified upstream producer, does not emit `v`).
    """
    if not event.get("eventId"):
        raise EventValidationError("missing eventId")
    payment_id = event.get("paymentId")
    if not payment_id:
        raise EventValidationError("missing paymentId")
    try:
        uuid.UUID(str(payment_id))
    except ValueError as exc:
        raise EventValidationError(f"paymentId not a UUID: {payment_id}") from exc
    try:
        if Decimal(str(event.get("amount"))) <= 0:
            raise EventValidationError(f"amount must be > 0: {event.get('amount')}")
    except (InvalidOperation, TypeError) as exc:
        raise EventValidationError(f"amount not decimal: {event.get('amount')}") from exc
    currency = event.get("currency")
    if not currency or not _CURRENCY_RE.match(str(currency)):
        raise EventValidationError(f"currency not ISO 4217: {currency}")
    if not event.get("customerId"):
        raise EventValidationError("missing customerId")
    if not event.get("merchantId"):
        raise EventValidationError("missing merchantId")
