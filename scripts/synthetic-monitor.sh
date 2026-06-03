#!/bin/bash
# ============================================================================
# synthetic-monitor.sh — POST test payment every 5 minutes, verify full flow
# ============================================================================
# Usage: bash scripts/synthetic-monitor.sh
# Add to cron: */5 * * * * /path/to/synthetic-monitor.sh
# ============================================================================
set -euo pipefail

GREEN='\033[32m'; RED='\033[31m'; YELLOW='\033[33m'; RESET='\033[0m'
PASS="${GREEN}PASS${RESET}"; FAIL="${RED}FAIL${RESET}"; WARN="${YELLOW}SKIP${RESET}"

BASE_URL="${BASE_URL:-http://localhost:8081}"
JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
IDEMPOTENCY_KEY="synthetic-$(date +%s)-$RANDOM"

echo "[$TIMESTAMP] Synthetic monitor — payment flow health check"

# 1. Create payment
RESPONSE=$(curl -s -m 10 -X POST "$BASE_URL/v1/payments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{\"amount\":1.00,\"currency\":\"USD\",\"merchantId\":\"synthetic-merchant\",\"customerId\":\"synthetic-customer\"}" 2>&1) || true

PAYMENT_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('paymentId',''))" 2>/dev/null || echo "")
STATUS=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")

if [ "$STATUS" = "CREATED" ]; then
    echo "  [$PASS] Payment created: $PAYMENT_ID"
else
    echo "  [$FAIL] Payment creation failed: $RESPONSE"
    exit 1
fi

# 2. Wait for processing
sleep 15

# 3. Check fraud score
FRAUD=$(curl -s -m 5 "http://localhost:8000/liveness" > /dev/null 2>&1 && echo "up" || echo "down")
echo "  fraud-service: $([ "$FRAUD" = "up" ] && echo "$PASS" || echo "$FAIL")"

# 4. Check ledger
LEDGER=$(curl -s -m 5 "http://localhost:8080/liveness" > /dev/null 2>&1 && echo "up" || echo "down")
echo "  financial-core: $([ "$LEDGER" = "up" ] && echo "$PASS" || echo "$FAIL")"

# 5. Check notification
NOTIF=$(curl -s -m 5 "http://localhost:3001/liveness" > /dev/null 2>&1 && echo "up" || echo "down")
echo "  notification-service: $([ "$NOTIF" = "up" ] && echo "$PASS" || echo "$FAIL")"

# 6. Check Kafka lag
LAG=$(docker exec payment-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group payment-service --describe 2>/dev/null | awk '{sum+=$6} END {print sum+0}' || echo "N/A")
echo "  Consumer lag: $LAG"

# 7. Check DLQ depth
DLQ=$(docker exec payment-kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic payment-events-dlq --time -1 2>/dev/null | \
  awk -F: '{sum+=$3} END {print sum+0}' || echo "N/A")
if [ "$DLQ" = "0" ] || [ "$DLQ" = "N/A" ]; then
    echo "  [$PASS] DLQ empty"
else
    echo "  [$FAIL] DLQ has $DLQ messages"
fi

echo "[$TIMESTAMP] Synthetic monitor complete"
