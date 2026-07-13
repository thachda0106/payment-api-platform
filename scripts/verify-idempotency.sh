#!/bin/bash
# ============================================================================
# verify-idempotency.sh — Phase-9 idempotency & inbox safety verification
# ============================================================================
# Usage: bash scripts/verify-idempotency.sh
# Prereqs: full stack up + schemas + connectors registered.
#
# NOTE: redelivery/poison injection requires Avro+Schema-Registry framing, so
# those are covered by docs/cross-cutting/operations/phase9-integration-test.md
# (T3/T4). This script verifies API-level idempotency + inbox exactly-once.
# ============================================================================
set -uo pipefail
export MSYS_NO_PATHCONV=1

RED='\033[31m'; GREEN='\033[32m'; RESET='\033[0m'
PASS="${GREEN}PASS${RESET}"; FAIL="${RED}FAIL${RESET}"
FAILURES=0
fail() { echo -e "  $FAIL  $1"; FAILURES=$((FAILURES + 1)); }
pass() { echo -e "  $PASS  $1"; }

PG=payment-postgres
API=http://localhost:8081
psql_val() { docker exec $PG psql -U payment -d "$1" -t -c "$2" 2>/dev/null | xargs; }

echo "========================================================================"
echo " Phase-9 Idempotency & Inbox Safety"
echo "========================================================================"

# ─── 1. Concurrent Idempotency-Key → single payment ────────────────────────
echo ""; echo "=== 1. Concurrent Idempotency-Key (10 parallel POSTs) ==="
KEY=$(python3 -c "import uuid;print(uuid.uuid4())")
BODY='{"amount":4999,"currency":"USD","merchantId":"m-idem","customerId":"c-idem"}'
TMP=$(mktemp -d)
for i in $(seq 1 10); do
    ( curl -s -X POST $API/v1/payments -H "Content-Type: application/json" \
        -H "Idempotency-Key: $KEY" -d "$BODY" \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('paymentId',''))" 2>/dev/null > "$TMP/$i" ) &
done
wait
UNIQUE=$(cat "$TMP"/* | sort -u | grep -c .)
PAYMENT_ID=$(cat "$TMP"/* | sort -u | head -1)
rm -rf "$TMP"
{ [ "$UNIQUE" = "1" ] && [ -n "$PAYMENT_ID" ]; } && pass "10 requests → 1 paymentId ($PAYMENT_ID)" || fail "expected 1 unique id, got $UNIQUE"
[ "$(psql_val payment_db "SELECT count(*) FROM payments WHERE idempotency_key='$KEY'")" = "1" ] \
    && pass "payments: exactly 1 row" || fail "payments row count != 1"

# ─── 2. Inbox exactly-once (poll up to 120s) ────────────────────────────────
echo ""; echo "=== 2. Inbox exactly-once downstream ==="
E=0; while [ $E -lt 120 ]; do
    [ "$(psql_val financial_core_db "SELECT count(*) FROM journal_entries WHERE payment_id='$PAYMENT_ID'")" = "3" ] && break
    sleep 3; E=$((E+3))
done

FS=$(psql_val fraud_db "SELECT count(*) FROM fraud_scores WHERE payment_id='$PAYMENT_ID'")
[ "$FS" = "1" ] && pass "fraud_scores: exactly 1" || fail "fraud_scores: $FS"

JE=$(psql_val financial_core_db "SELECT count(*) FROM journal_entries WHERE payment_id='$PAYMENT_ID'")
[ "$JE" = "3" ] && pass "journal_entries: exactly 3 (ledger guard holds)" || fail "journal_entries: $JE"

NT=$(psql_val notification_db "SELECT count(*) FROM notifications WHERE payment_id='$PAYMENT_ID'")
[ "$NT" = "1" ] && pass "notifications: exactly 1" || fail "notifications: $NT"

# ─── 3. Inbox rows COMPLETED, no stuck FAILED/retries ───────────────────────
echo ""; echo "=== 3. Inbox status ==="
for db in fraud_db financial_core_db notification_db; do
    FAILED=$(psql_val "$db" "SELECT count(*) FROM consumer_inbox WHERE status IN ('FAILED','DLQ')")
    [ "${FAILED:-0}" = "0" ] && pass "$db: no FAILED/DLQ inbox rows" || fail "$db: $FAILED FAILED/DLQ rows"
done

echo ""; echo "========================================================================"
[ "$FAILURES" -eq 0 ] && echo -e " ${GREEN}Idempotency verification PASSED${RESET}" || echo -e " ${RED}FAILED — $FAILURES check(s)${RESET}"
echo "========================================================================"
exit "$FAILURES"
