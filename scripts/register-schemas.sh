#!/bin/bash
# ============================================================================
# register-schemas.sh — register all Avro schemas with the Schema Registry.
# Thin wrapper around register-schemas.py (portable stdlib implementation).
# Usage: bash scripts/register-schemas.sh [registry_url]
# ============================================================================
set -euo pipefail
REGISTRY="${1:-http://localhost:8081}"
DIR="$(cd "$(dirname "$0")" && pwd)"
python "${DIR}/register-schemas.py" "${REGISTRY}"
