#!/bin/bash
# ============================================================================
# register-connectors.sh — register Debezium outbox connectors with Kafka Connect.
# Usage: bash scripts/register-connectors.sh [connect_url]
#        (default connect_url: http://localhost:8083)
# Idempotent: uses PUT /connectors/<name>/config (create-or-update).
# ============================================================================
set -euo pipefail
CONNECT="${1:-http://localhost:8083}"
DIR="$(cd "$(dirname "$0")/../shared/config/debezium" && pwd)"

echo "Waiting for Kafka Connect at ${CONNECT} ..."
for i in $(seq 1 60); do
  if curl -sf "${CONNECT}/connectors" >/dev/null 2>&1; then break; fi
  sleep 5
done

for f in "${DIR}"/*.json; do
  name="$(python -c "import json,sys;print(json.load(open('$f'))['name'])")"
  cfg="$(python -c "import json,sys;print(json.dumps(json.load(open('$f'))['config']))")"
  echo "Registering ${name} ..."
  curl -sf -X PUT -H "Content-Type: application/json" \
    --data "${cfg}" \
    "${CONNECT}/connectors/${name}/config" >/dev/null \
    && echo "  OK ${name}" \
    || echo "  FAILED ${name}"
done

echo "Connector status:"
curl -s "${CONNECT}/connectors?expand=status" | python -c "
import json,sys
d=json.load(sys.stdin)
for n,v in d.items():
    st=v.get('status',{}).get('connector',{}).get('state','?')
    print(f'  {n}: {st}')
" 2>/dev/null || true
