#!/bin/bash
# check-port-uniqueness.sh — Detects duplicate port mappings in docker-compose.yml
# Exit 1 if any port conflicts found, exit 0 otherwise.

set -euo pipefail

COMPOSE_FILE="${1:-docker-compose.yml}"

echo "Checking port uniqueness in $COMPOSE_FILE..."

# Extract all host:container port mappings, count duplicates
DUPLICATES=$(grep -oP '"\d+:\d+"' "$COMPOSE_FILE" | sort | uniq -d)

if [ -n "$DUPLICATES" ]; then
    echo "❌ PORT CONFLICT DETECTED:"
    echo "$DUPLICATES"
    echo "Each port mapping must be unique across all services."
    exit 1
fi

echo "✅ No port conflicts detected."
exit 0
