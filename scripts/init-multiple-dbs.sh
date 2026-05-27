#!/bin/bash
# ============================================================================
# init-multiple-dbs.sh — Create multiple PostgreSQL databases on first start
# ============================================================================
# Called by postgres:16-alpine entrypoint on first container start.
# Creates one database per bounded context.

set -e
set -u

function create_database() {
  local database=$1
  echo "  Creating database '$database'..."
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    SELECT 'CREATE DATABASE $database'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$database')\gexec
EOSQL
}

if [ -n "$POSTGRES_MULTIPLE_DATABASES" ]; then
  echo "Initializing multiple databases..."
  for db in $(echo "$POSTGRES_MULTIPLE_DATABASES" | tr ',' ' '); do
    # Trim whitespace
    db=$(echo "$db" | xargs)
    create_database "$db"
  done
  echo "Database initialization complete."
fi
