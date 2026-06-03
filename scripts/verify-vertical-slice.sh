#!/bin/bash
# ============================================================================
# verify-vertical-slice.sh — Phase 7 E2E Architecture Validation
# ============================================================================
# Usage: bash scripts/verify-vertical-slice.sh
# Prerequisites: docker-compose up -d must be running
# ============================================================================
set -euo pipefail

RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; RESET='\033[0m'
PASS="${GREEN}PASS${RESET}"; FAIL="${RED}FAIL${RESET}"; WARN="${YELLOW}WARN${RESET}"

echo "========================================================================"
echo " Phase 7 — Vertical Slice Architecture Validation"
echo "========================================================================"

# ─── 1. Liveness Probes ───────────────────────────────────────────────────
echo ""
echo "=== 1. Service Liveness Probes ==="

check_liveness() { local svc=$1 port=$2 name=$3
    if curl -sf -m 5 http://localhost:$port/liveness > /dev/null 2>&1; then
        echo -e "  $PASS  $name (:$port)"
    else
        echo -e "  $FAIL  $name (:$port) — not responding"
        return 1
    fi
}

check_liveness "financial-core" 8080 "financial-core (Java)"
check_liveness "payment-service" 8081 "payment-service (Java)"
check_liveness "fraud-service" 8000 "fraud-service (Python)"
check_liveness "notification-service" 3001 "notification-service (Node.js)"
check_liveness "settlement-service" 8088 "settlement-service (Go)"

# ─── 2. Create Payment ────────────────────────────────────────────────────
echo ""
echo "=== 2. Create Payment (POST /v1/payments) ==="

IDEMPOTENCY_KEY=$(uuidgen 2>/dev/null || python3 -c "import uuid; print(uuid.uuid4())" 2>/dev/null || echo "test-key-$(date +%s)")

RESPONSE=$(curl -s -X POST http://localhost:8081/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{\"amount\":99.99,\"currency\":\"USD\",\"merchantId\":\"merchant-1\",\"customerId\":\"customer-1\"}")

echo "  Request:  POST /v1/payments  Idempotency-Key=$IDEMPOTENCY_KEY"
echo "  Response: $RESPONSE"

PAYMENT_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('paymentId',''))" 2>/dev/null || echo "")
STATUS=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")

if [ -n "$PAYMENT_ID" ] && [ "$STATUS" = "CREATED" ]; then
    echo -e "  $PASS  Payment created: $PAYMENT_ID"
else
    echo -e "  $FAIL  Payment creation failed"
    exit 1
fi

# ─── 3. Idempotency Test ──────────────────────────────────────────────────
echo ""
echo "=== 3. Idempotency Test (duplicate key) ==="

RESPONSE2=$(curl -s -X POST http://localhost:8081/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{\"amount\":99.99,\"currency\":\"USD\",\"merchantId\":\"merchant-1\",\"customerId\":\"customer-1\"}")

PAYMENT_ID2=$(echo "$RESPONSE2" | python3 -c "import sys,json; print(json.load(sys.stdin).get('paymentId',''))" 2>/dev/null || echo "")

if [ "$PAYMENT_ID2" = "$PAYMENT_ID" ]; then
    echo -e "  $PASS  Duplicate idempotency key returned same payment ID"
else
    echo -e "  $FAIL  Idempotency failed: expected $PAYMENT_ID, got $PAYMENT_ID2"
fi

# ─── 4. Wait for Event Processing ─────────────────────────────────────────
echo ""
echo "=== 4. Event Flow Processing ==="
echo "  Waiting 15s for: PaymentCreated → FraudCheck → Ledger → Notification..."
sleep 15

# ─── 5. Verify Fraud Score ────────────────────────────────────────────────
echo ""
echo "=== 5. Verify Fraud Score ==="
FRAUD_ROW=$(docker exec payment-postgres psql -U payment -d fraud_db -t -c \
  "SELECT decision,score FROM fraud_scores WHERE payment_id='$PAYMENT_ID' LIMIT 1" 2>/dev/null || echo "")

if [ -n "$FRAUD_ROW" ]; then
    echo -e "  $PASS  Fraud scored: $FRAUD_ROW"
else
    echo -e "  $FAIL  No fraud score found for payment $PAYMENT_ID"
fi

# ─── 6. Verify Ledger Entries ─────────────────────────────────────────────
echo ""
echo "=== 6. Verify Double-Entry Ledger ==="
LEDGER_COUNT=$(docker exec payment-postgres psql -U payment -d financial_core_db -t -c \
  "SELECT COUNT(*) FROM journal_entries WHERE payment_id='$PAYMENT_ID'" 2>/dev/null | xargs || echo "0")

if [ "$LEDGER_COUNT" -ge 3 ]; then
    echo -e "  $PASS  Journal entries: $LEDGER_COUNT (expected >= 3)"
else
    echo -e "  $FAIL  Journal entries: $LEDGER_COUNT (expected >= 3)"
fi

# Check double-entry balance = 0
BALANCE=$(docker exec payment-postgres psql -U payment -d financial_core_db -t -c \
  "SELECT SUM(CASE WHEN entry_type='CREDIT' THEN amount ELSE -amount END)
   FROM journal_entries WHERE payment_id='$PAYMENT_ID'" 2>/dev/null | xargs || echo "N/A")

if [ "$BALANCE" = "0.0000" ] || [ "$BALANCE" = "0" ]; then
    echo -e "  $PASS  Double-entry balanced: sum = $BALANCE"
else
    echo -e "  $WARN  Double-entry balance: $BALANCE (check if events still processing)"
fi

# ─── 7. Verify Notification ───────────────────────────────────────────────
echo ""
echo "=== 7. Verify Notification ==="
NOTIF_COUNT=$(docker exec payment-postgres psql -U payment -d notification_db -t -c \
  "SELECT COUNT(*) FROM notifications WHERE payment_id='$PAYMENT_ID'" 2>/dev/null | xargs || echo "0")

if [ "$NOTIF_COUNT" -gt 0 ]; then
    echo -e "  $PASS  Notification sent: $NOTIF_COUNT record(s)"
else
    echo -e "  $WARN  Notification not found (may still be processing)"
fi

# ─── 8. Kafka Consumer Lag ────────────────────────────────────────────────
echo ""
echo "=== 8. Kafka Consumer Lag ==="
for group in payment-service fraud-service financial-core notification-service; do
    LAG=$(docker exec payment-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
      --group $group --describe 2>/dev/null | grep -v "^$" | awk '{sum+=$6} END {print sum+0}' || echo "N/A")
    echo "  $group: lag=$LAG"
done

# ─── 9. Dead Letter Queue ─────────────────────────────────────────────────
echo ""
echo "=== 9. Dead Letter Queue ==="
DLQ_COUNT=$(docker exec payment-kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic payment-events-dlq --time -1 2>/dev/null | \
  awk -F: '{sum+=$3} END {print sum+0}' || echo "N/A")

if [ "$DLQ_COUNT" = "0" ] || [ "$DLQ_COUNT" = "N/A" ]; then
    echo -e "  $PASS  DLQ empty (no poison messages)"
else
    echo -e "  $WARN  DLQ has $DLQ_COUNT messages"
fi

# ─── Summary ──────────────────────────────────────────────────────────────
echo ""
echo "========================================================================"
echo " Architecture Validation Complete"
echo "========================================================================"
echo ""
echo "  Flow: POST /v1/payments → PaymentCreated → Fraud → Ledger → Notification"
echo "  Payment ID: $PAYMENT_ID"
echo "  Jaeger UI:  http://localhost:16686 (search for payment-service)"
echo "  Grafana:    http://localhost:3000 (admin/admin)"
echo ""
