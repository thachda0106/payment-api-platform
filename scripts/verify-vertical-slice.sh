#!/bin/bash
# ============================================================================
# verify-vertical-slice.sh — Phase-9 E2E verification (Debezium + Avro + inbox)
# ============================================================================
# Usage: bash scripts/verify-vertical-slice.sh
# Prereqs: docker-compose up -d  &&  bash scripts/register-schemas.sh
#          &&  bash scripts/register-connectors.sh
# ============================================================================
set -uo pipefail
export MSYS_NO_PATHCONV=1   # keep /etc/... paths intact on Git Bash (Windows)

RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; RESET='\033[0m'
PASS="${GREEN}PASS${RESET}"; FAIL="${RED}FAIL${RESET}"; WARN="${YELLOW}WARN${RESET}"
FAILURES=0
fail() { echo -e "  $FAIL  $1"; FAILURES=$((FAILURES + 1)); }
pass() { echo -e "  $PASS  $1"; }

PG=payment-postgres
psql_val() { docker exec $PG psql -U payment -d "$1" -t -c "$2" 2>/dev/null | xargs; }

echo "========================================================================"
echo " Phase-9 Vertical Slice — Debezium CDC → Avro → inbox"
echo "========================================================================"

# ─── 1. Liveness ────────────────────────────────────────────────────────────
echo ""; echo "=== 1. Service Liveness ==="
for pair in "financial-core:8080" "payment-service:8081" "fraud-service:8000" "notification-service:3001" "settlement-service:8088"; do
    name="${pair%%:*}"; port="${pair##*:}"
    curl -sf -m 5 "http://localhost:$port/liveness" >/dev/null 2>&1 && pass "$name (:$port)" || fail "$name (:$port) not responding"
done

# ─── 2. Debezium connectors RUNNING ─────────────────────────────────────────
echo ""; echo "=== 2. Debezium Connectors ==="
for c in payment-outbox-connector fraud-outbox-connector financial-core-outbox-connector notification-outbox-connector; do
    state=$(curl -s "http://localhost:8083/connectors/$c/status" 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin)['connector']['state'])" 2>/dev/null || echo "MISSING")
    [ "$state" = "RUNNING" ] && pass "$c: RUNNING" || fail "$c: $state"
done

# ─── 3. Create payment (amount in minor units: 9999 = \$99.99) ──────────────
echo ""; echo "=== 3. Create Payment ==="
KEY=$(python3 -c "import uuid;print(uuid.uuid4())")
RESP=$(curl -s -X POST http://localhost:8081/v1/payments \
  -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
  -d '{"amount":9999,"currency":"USD","merchantId":"m1","customerId":"c1"}')
PAYMENT_ID=$(echo "$RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('paymentId',''))" 2>/dev/null || echo "")
[ -n "$PAYMENT_ID" ] && pass "Payment created: $PAYMENT_ID" || { fail "Payment creation failed: $RESP"; echo "aborting"; exit 1; }

# ─── 4. Poll the serial chain (Debezium+Avro+inbox), timeout 120s ───────────
echo ""; echo "=== 4. Serial chain: fraud → ledger → notification ==="
ok() { [ "$(psql_val "$1" "$2")" = "$3" ]; }
ELAPSED=0
while [ $ELAPSED -lt 120 ]; do
    ok fraud_db "SELECT count(*) FROM fraud_scores WHERE payment_id='$PAYMENT_ID'" "1" \
      && ok financial_core_db "SELECT count(*) FROM journal_entries WHERE payment_id='$PAYMENT_ID'" "3" \
      && ok notification_db "SELECT count(*) FROM notifications WHERE payment_id='$PAYMENT_ID'" "1" \
      && break
    sleep 3; ELAPSED=$((ELAPSED + 3))
done
echo "  (processed after ~${ELAPSED}s)"

[ "$(psql_val fraud_db "SELECT count(*) FROM fraud_scores WHERE payment_id='$PAYMENT_ID'")" = "1" ] \
    && pass "fraud_scores: 1 row" || fail "fraud_scores not found"
[ "$(psql_val fraud_db "SELECT status FROM consumer_inbox WHERE consumer_group='fraud-service' ORDER BY updated_at DESC LIMIT 1")" = "COMPLETED" ] \
    && pass "fraud inbox COMPLETED" || fail "fraud inbox not COMPLETED"

LC=$(psql_val financial_core_db "SELECT count(*) FROM journal_entries WHERE payment_id='$PAYMENT_ID'")
[ "$LC" = "3" ] && pass "journal_entries: 3" || fail "journal_entries: $LC (expected 3)"
BAL=$(psql_val financial_core_db "SELECT SUM(CASE WHEN entry_type='CREDIT' THEN amount ELSE -amount END) FROM journal_entries WHERE payment_id='$PAYMENT_ID'")
[ "$BAL" = "0" ] && pass "double-entry balanced (sum=0, minor units)" || fail "double-entry sum=$BAL"

[ "$(psql_val notification_db "SELECT count(*) FROM notifications WHERE payment_id='$PAYMENT_ID'")" = "1" ] \
    && pass "notification: 1 row" || fail "notification not found"

# ─── 5. DLQs empty ──────────────────────────────────────────────────────────
echo ""; echo "=== 5. Dead Letter Queues ==="
for dlq in payments.dlq ledger.dlq notifications.dlq; do
    n=$(docker exec $PG true 2>/dev/null; docker exec payment-kafka kafka-run-class kafka.tools.GetOffsetShell \
        --broker-list localhost:9092 --topic "$dlq" --command-config /etc/kafka/client-sasl.properties --time -1 2>/dev/null \
        | awk -F: '{s+=$3} END{print s+0}')
    [ "${n:-0}" = "0" ] && pass "$dlq empty" || echo -e "  $WARN  $dlq has ${n} message(s)"
done

echo ""; echo "========================================================================"
[ "$FAILURES" -eq 0 ] && echo -e " ${GREEN}Vertical slice PASSED${RESET}" || echo -e " ${RED}FAILED — $FAILURES check(s)${RESET}"
echo "========================================================================"
exit "$FAILURES"
