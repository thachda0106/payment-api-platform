# Runbook 3: Database Connection Exhaustion

## Symptom
- `DatabaseConnectionPoolExhausted` alert firing
- Services returning 503 on `/readiness` (database check failing)
- `hikaricp_connections_pending > 5`

## Diagnosis

### Step 1: Check connection pool stats
```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq '.measurements'
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending | jq '.measurements'
```

### Step 2: Check active queries
```sql
SELECT pid, now() - pg_stat_activity.query_start AS duration, query, state
FROM pg_stat_activity
WHERE state != 'idle' AND pid != pg_backend_pid()
ORDER BY duration DESC;
```

### Step 3: Check for long-running transactions
```sql
SELECT pid, now() - xact_start AS duration, query
FROM pg_stat_activity
WHERE xact_start IS NOT NULL AND pid != pg_backend_pid()
ORDER BY duration DESC;
```

## Mitigation

| Cause | Action |
|-------|--------|
| **Slow query** | Kill: `SELECT pg_terminate_backend(pid)` |
| **Connection leak** | Restart service: `docker-compose restart financial-core` |
| **Pool too small** | Increase: `DB_MAX_POOL_SIZE=20` in docker-compose |
| **Transaction not committed** | Restart service |
| **Lock contention** | Check `pg_locks` for blocking locks |

### Emergency pool increase
```bash
# docker-compose override
echo "DB_MAX_POOL_SIZE=30" >> .env
docker-compose up -d --force-recreate financial-core
```

## Prevention
- [ ] Review connection pool size: `actions_per_minute / 60 * avg_query_time / 1000 * 1.5`
- [ ] Add query timeout: `statement_timeout = 30s`
- [ ] Add connection leak detection: `leakDetectionThreshold = 10000`
- [ ] Monitor hikaricp metrics in Grafana dashboard
