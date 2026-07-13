#!/usr/bin/env python3
"""Register all vertical-slice + catalog Avro schemas with the Schema Registry.

Sets per-subject compatibility (CI-5 decision: BACKWARD default; ledger
BACKWARD_TRANSITIVE; audit FULL). Idempotent — safe to re-run.

Usage: python scripts/register-schemas.py [registry_url]
       (default registry_url: http://localhost:8081)
"""
import base64
import json
import os
import sys
import urllib.request

REGISTRY = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8081"
SCHEMA_DIR = os.path.join(os.path.dirname(__file__), "..", "docs", "cross-cutting", "events", "schemas")
SCHEMA_DIR = os.path.abspath(SCHEMA_DIR)

CT = "application/vnd.schemaregistry.v1+json"

# Optional HTTP BASIC auth for the Schema Registry (Phase-9 P4).
_SR_USER = os.getenv("SR_BASIC_AUTH_USER", "")
_SR_PASS = os.getenv("SR_BASIC_AUTH_PASS", "")

# Subjects PRODUCED by Debezium (Option A): the connector auto-registers the
# inferred value schema at runtime, so we must NOT pre-register a conflicting
# hand-authored version. The .avsc files remain the design reference + basis for
# P2 consumer deserialization. These subjects are skipped here.
DEBEZIUM_OWNED = {
    "payments.payment.created-value",
    "payments.payment.succeeded-value",
    "payments.payment.failed-value",
    "ledger.entry.committed-value",
    "notifications.email.queued-value",
}

# filename (without .avsc) -> (subject, compatibility)
SUBJECTS = {
    "payment-created": ("payments.payment.created-value", "BACKWARD"),
    "payment-succeeded": ("payments.payment.succeeded-value", "BACKWARD"),
    "payment-failed": ("payments.payment.failed-value", "BACKWARD"),
    "payment-canceled": ("payments.payment.canceled-value", "BACKWARD"),
    "ledger-entry-committed": ("ledger.entry.committed-value", "BACKWARD_TRANSITIVE"),
    "ledger-balance-reconciled": ("ledger.balance.reconciled-value", "BACKWARD"),
    "notification-email-queued": ("notifications.email.queued-value", "BACKWARD"),
    "notification-push-queued": ("notifications.push.queued-value", "BACKWARD"),
    "notification-webhook-delivered": ("notifications.webhook.delivered-value", "BACKWARD"),
    "refund-created": ("refunds.refund.created-value", "BACKWARD"),
    "refund-succeeded": ("refunds.refund.succeeded-value", "BACKWARD"),
    "refund-failed": ("refunds.refund.failed-value", "BACKWARD"),
    "wallet-balance-updated": ("wallets.balance.updated-value", "BACKWARD"),
    "wallet-account-frozen": ("wallets.account.frozen-value", "BACKWARD"),
    "wallet-account-unfrozen": ("wallets.account.unfrozen-value", "BACKWARD"),
    "wallet-account-created": ("wallets.account.created-value", "BACKWARD"),
    "payout-created": ("payouts.payout.created-value", "BACKWARD"),
    "payout-succeeded": ("payouts.payout.succeeded-value", "BACKWARD"),
    "payout-failed": ("payouts.payout.failed-value", "BACKWARD"),
    "audit-action": ("platform.audit.action-value", "FULL"),
}


def _req(method, path, body):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(REGISTRY + path, data=data, method=method,
                                 headers={"Content-Type": CT})
    if _SR_USER:
        token = base64.b64encode(f"{_SR_USER}:{_SR_PASS}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode())


def main():
    failures = 0
    skipped = 0
    for name, (subject, compat) in SUBJECTS.items():
        if subject in DEBEZIUM_OWNED:
            print(f"SKIP {subject:45s} (Debezium auto-registers at runtime)")
            skipped += 1
            continue
        path = os.path.join(SCHEMA_DIR, name + ".avsc")
        with open(path) as f:
            avro = f.read()
        try:
            # Set subject compatibility first (subject config is created on demand).
            _req("PUT", f"/config/{subject}", {"compatibility": compat})
            # Register the schema (validates Avro + enforces compatibility).
            res = _req("POST", f"/subjects/{subject}/versions",
                       {"schema": avro, "schemaType": "AVRO"})
            print(f"OK   {subject:45s} id={res.get('id')} compat={compat}")
        except Exception as e:
            detail = getattr(e, "read", lambda: b"")()
            print(f"FAIL {subject:45s} {e} {detail.decode(errors='replace') if detail else ''}")
            failures += 1
    total = len(SUBJECTS) - skipped
    print(f"\n{total - failures}/{total} design schemas registered; {skipped} Debezium-owned skipped.")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
