#!/bin/bash
# ============================================================================
# verify-backup-restore.sh — Verify PostgreSQL backup and restore works
# ============================================================================
set -euo pipefail

GREEN='\033[32m'; RED='\033[31m'; RESET='\033[0m'
DB="${1:-financial_core_db}"
BACKUP_FILE="/tmp/payment_backup_${DB}_$(date +%Y%m%d_%H%M%S).sql"
RESTORE_DB="${DB}_restore_test"

echo "=== Backup & Restore Verification ==="
echo "  Database: $DB"

# 1. Create backup
echo "  Creating backup..."
docker exec payment-postgres pg_dump -U payment -d "$DB" --no-owner --no-acl > "$BACKUP_FILE" 2>/dev/null
if [ -s "$BACKUP_FILE" ]; then
    echo -e "  ${GREEN}PASS${RESET}  Backup created: $(wc -c < "$BACKUP_FILE") bytes"
else
    echo -e "  ${RED}FAIL${RESET}  Backup empty or failed"
    exit 1
fi

# 2. Create restore test database
echo "  Creating restore test database..."
docker exec payment-postgres psql -U payment -c "DROP DATABASE IF EXISTS $RESTORE_DB" > /dev/null 2>&1 || true
docker exec payment-postgres psql -U payment -c "CREATE DATABASE $RESTORE_DB" 2>/dev/null

# 3. Restore backup
echo "  Restoring backup..."
docker exec -i payment-postgres psql -U payment -d "$RESTORE_DB" < "$BACKUP_FILE" > /dev/null 2>&1
RESTORE_OK=$?

if [ $RESTORE_OK -eq 0 ]; then
    echo -e "  ${GREEN}PASS${RESET}  Restore succeeded"
else
    echo -e "  ${RED}FAIL${RESET}  Restore failed"
    exit 1
fi

# 4. Verify data (count tables)
TABLE_COUNT=$(docker exec payment-postgres psql -U payment -d "$RESTORE_DB" -t -c \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'" 2>/dev/null | xargs)

echo "  Restored tables: $TABLE_COUNT"

# 5. Verify data (sample query if accounts table exists)
if docker exec payment-postgres psql -U payment -d "$RESTORE_DB" -t -c \
    "SELECT 1 FROM information_schema.tables WHERE table_name='accounts'" 2>/dev/null | grep -q 1; then
    ACCOUNT_COUNT=$(docker exec payment-postgres psql -U payment -d "$RESTORE_DB" -t -c \
        "SELECT COUNT(*) FROM accounts" 2>/dev/null | xargs)
    echo "  Restored accounts: $ACCOUNT_COUNT"
fi

# 6. Cleanup
docker exec payment-postgres psql -U payment -c "DROP DATABASE IF EXISTS $RESTORE_DB" > /dev/null 2>&1
rm -f "$BACKUP_FILE"

echo -e "  ${GREEN}PASS${RESET}  Backup & restore verified for $DB"
