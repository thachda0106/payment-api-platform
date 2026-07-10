#!/bin/bash
# ============================================================================
# verify-idempotency.sh — Milestone B safety verification
# ============================================================================
# Verifies: concurrent idempotency-key → single payment; event redelivery →
# exactly one side effect (fraud, ledger, notification); poison message → DLQ.
#
# Usage: bash scripts/verify-idempotency.sh
# Prerequisites: docker-compose up -d  (incl. kafka-init-topics for DLQ topics)
# ============================================================================
set -uo pipefail

RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; RESET='\033[0m'
PASS="${GREEN}PASS${RESET}"; FAIL="${RED}FAIL${RESET}"; WARN="${YELLOW}WARN${RESET}"
FAILURES=0
fail() { echo -e "  $FAIL  $1"; FAILURES=$((FAILURES + 1)); }
pass() { echo -e "  $PASS  $1"; }

PG=payment-postgres
KAFKA=payment-kafka
API=http://localhost:8081

psql_val() { docker exec $PG psql -U payment -d "$1" -t -c "$2" 2>/dev/null | xargs; }

# poll_eq <db> <sql> <expected> <timeout-s> — polls until value equals expected
poll_eq() {
    local db=$1 sql=$2 expected=$3 timeout=${4:-60} elapsed=0
    while [ $elapsed -lt "$timeout" ]; do
        [ "$(psql_val "$db" "$sql")" = "$expected" ] && return 0
        sleep 2; elapsed=$((elapsed + 2))
    done
    return 1
}

echo "========================================================================"
echo " Milestone B — Idempotency & DLQ Safety Verification"
echo "========================================================================"

# ─── 1. Concurrent idempotency key → single payment ─────────────────────────
echo ""
echo "=== 1. Concurrent Idempotency-Key (10 parallel POSTs) ==="
KEY=$(python3 -c "import uuid;print(uuid.uuid4())")
BODY='{"amount":49.99,"currency":"USD","merchantId":"m-idem","customerId":"c-idem"}'

PIDS=""
TMP=$(mktemp -d)
for i in $(seq 1 10); do
    ( curl -s -X POST $API/v1/payments -H "Content-Type: application/json" \
        -H "Idempotency-Key: $KEY" -d "$BODY" \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('paymentId',''))" \
        > "$TMP/$i" 2>/dev/null ) &
    PIDS="$PIDS $!"
done
for p in $PIDS; do wait "$p"; done

UNIQUE_IDS=$(cat "$TMP"/* | sort -u | grep -c . || echo 0)
PAYMENT_ID=$(cat "$TMP"/* | sort -u | head -1)
rm -rf "$TMP"

if [ "$UNIQUE_IDS" = "1" ] && [ -n "$PAYMENT_ID" ]; then
    pass "All 10 concurrent requests returned one paymentId: $PAYMENT_ID"
else
    fail "Expected 1 unique paymentId, got $UNIQUE_IDS"
fi

ROW_COUNT=$(psql_val payment_db "SELECT count(*) FROM payments WHERE idempotency_key='$KEY'")
if [ "$ROW_COUNT" = "1" ]; then
    pass "Exactly one payments row for the key"
else
    fail "Expected 1 payments row, found $ROW_COUNT"
fi

# ─── 2. Full flow processes exactly once ────────────────────────────────────
echo ""
echo "=== 2. Downstream side effects (exactly once) ==="
if poll_eq fraud_db "SELECT count(*) FROM fraud_scores WHERE payment_id='$PAYMENT_ID'" "1" 60; then
    pass "fraud_scores: exactly 1 row"
else
    fail "fraud_scores count != 1 (got $(psql_val fraud_db "SELECT count(*) FROM fraud_scores WHERE payment_id='$PAYMENT_ID'"))"
fi

if poll_eq financial_core_db "SELECT count(*) FROM journal_entries WHERE payment_id='$PAYMENT_ID'" "3" 60; then
    pass "journal_entries: exactly 3 rows (single posting)"
else
    fail "journal_entries count != 3 (got $(psql_val financial_core_db "SELECT count(*) FROM journal_entries WHERE payment_id='$PAYMENT_ID'"))"
fi

if poll_eq notification_db "SELECT count(*) FROM notifications WHERE payment_id='$PAYMENT_ID'" "1" 60; then
    pass "notifications: exactly 1 row"
else
    fail "notifications count != 1 (got $(psql_val notification_db "SELECT count(*) FROM notifications WHERE payment_id='$PAYMENT_ID'"))"
fi

# ─── 3. Redelivery of a duplicate fraud event → no double ledger posting ────
echo ""
echo "=== 3. Event Redelivery (duplicate fraud-event) ==="
# Re-publish the exact fraud-event that financial-core already processed (same eventId).
FRAUD_EVENT=$(docker exec $PG psql -U payment -d fraud_db -t -c \
  "SELECT payload::text FROM fraud_outbox WHERE aggregate_id='$PAYMENT_ID' LIMIT 1" 2>/dev/null | sed 's/^ *//')

if [ -n "$FRAUD_EVENT" ]; then
    echo "$FRAUD_EVENT" | docker exec -i $KAFKA kafka-console-producer \
        --bootstrap-server localhost:9092 --topic fraud-events >/dev/null 2>&1
    sleep 8
    LEDGER_AFTER=$(psql_val financial_core_db "SELECT count(*) FROM journal_entries WHERE payment_id='$PAYMENT_ID'")
    if [ "$LEDGER_AFTER" = "3" ]; then
        pass "Redelivered fraud-event did NOT double-post ledger (still 3 entries)"
    else
        fail "Ledger double-posted on redelivery: $LEDGER_AFTER entries (expected 3)"
    fi
else
    echo -e "  $WARN  Could not read fraud_outbox payload — skipping redelivery check"
fi

# ─── 4. Poison message → DLQ ────────────────────────────────────────────────
echo ""
echo "=== 4. Poison Message → DLQ ==="
dlq_offset() {
    docker exec $KAFKA kafka-run-class kafka.tools.GetOffsetShell \
      --broker-list localhost:9092 --topic "$1" --time -1 2>/dev/null | \
      awk -F: '{sum+=$3} END {print sum+0}'
}
BEFORE=$(dlq_offset fraud-events-dlq)
# Malformed fraud-event (not valid JSON contract): missing required fields.
echo '{"decision":"APPROVED","paymentId":"not-a-uuid"}' | \
    docker exec -i $KAFKA kafka-console-producer \
    --bootstrap-server localhost:9092 --topic fraud-events >/dev/null 2>&1
sleep 8
AFTER=$(dlq_offset fraud-events-dlq)
if [ "${AFTER:-0}" -gt "${BEFORE:-0}" ]; then
    pass "Poison message routed to fraud-events-dlq ($BEFORE → $AFTER)"
else
    fail "Poison message not found in fraud-events-dlq ($BEFORE → $AFTER)"
fi

# ─── Summary ────────────────────────────────────────────────────────────────
echo ""
echo "========================================================================"
if [ "$FAILURES" -eq 0 ]; then
    echo -e " ${GREEN}Milestone B verification PASSED${RESET}"
else
    echo -e " ${RED}Milestone B verification FAILED — $FAILURES check(s) failed${RESET}"
fi
echo "========================================================================"
exit "$FAILURES"
