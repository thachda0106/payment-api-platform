#!/bin/bash
# ============================================================================
# zz-init-cdc.sh — Debezium CDC role + grants (runs after init-multiple-dbs.sh)
# ============================================================================
# Creates the `debezium_cdc` login/replication role and grants it read access
# to the outbox-bearing databases so Debezium PostgreSQL connectors can snapshot
# and stream changes. Requires wal_level=logical (set via the postgres command).
# ============================================================================
set -e

# Global role (LOGIN + REPLICATION), created once.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-'EOSQL'
  DO $$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'debezium_cdc') THEN
      CREATE ROLE debezium_cdc WITH LOGIN REPLICATION PASSWORD 'cdcpass';
    END IF;
  END
  $$;
EOSQL

# Per-database grants (current + future tables via default privileges) + publication.
for db in payment_db financial_core_db fraud_db notification_db; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-EOSQL
    GRANT CONNECT ON DATABASE $db TO debezium_cdc;
    GRANT USAGE ON SCHEMA public TO debezium_cdc;
    GRANT SELECT ON ALL TABLES IN SCHEMA public TO debezium_cdc;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO debezium_cdc;
    -- Publication for Debezium (created by superuser; covers the outbox table when
    -- Flyway creates it). Connectors use publication.autocreate.mode=disabled.
    DROP PUBLICATION IF EXISTS dbz_pub;
    CREATE PUBLICATION dbz_pub FOR ALL TABLES;
EOSQL
done

echo "debezium_cdc role, grants, and publications configured."
