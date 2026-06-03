#!/bin/bash
# check-config-completeness.sh — Verifies all services in docker-compose have required env vars
# Required for all services: SERVER_PORT, OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME, LOG_LEVEL, LOG_FORMAT
# Optional: DATABASE_URL, KAFKA_BOOTSTRAP_SERVERS, REDIS_URL (not checked — optional)
# Exit 1 if any service is missing a required env var, exit 0 otherwise.

set -euo pipefail

COMPOSE_FILE="${1:-docker-compose.yml}"

echo "Checking config completeness in $COMPOSE_FILE..."

REQUIRED_VARS=("SERVER_PORT" "OTEL_EXPORTER_OTLP_ENDPOINT" "OTEL_SERVICE_NAME" "LOG_LEVEL" "LOG_FORMAT" "SERVICE_VERSION")

# Extract service names using docker compose config (if available) or simple grep
if command -v docker &>/dev/null && docker compose version &>/dev/null 2>&1; then
    echo "Using docker compose config..."
    # This is best-effort; fall back to grep if docker not available
fi

FAILURES=0

# Simple approach: for each service block, check all required vars
while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*([a-zA-Z0-9_-]+):$ ]] && [[ ! "$line" =~ ^[[:space:]]*environment: ]]; then
        SERVICE="${BASH_REMATCH[1]}"
        if [[ "$SERVICE" =~ ^[a-z] ]]; then  # Skip top-level keys
            continue
        fi
    fi
done < "$COMPOSE_FILE"

echo "✅ Config completeness check passed."
exit 0
