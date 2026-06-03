# Runbook 4: Outbox Backlog Growth

## Symptom
- `OutboxBacklogGrowing` alert firing
- `payment_outbox` table growing (published_at IS NULL count increasing)
- Payments created but events not reaching Kafka

## Diagnosis

### Step 1: Check outbox backlog size
```sql
SELECT COUNT(*) AS backlog,
       MAX(now() - created_at) AS oldest_unpublished
FROM payment_outbox WHERE published_at IS NULL;
```

### Step 2: Check OutboxPoller logs
```bash
docker-compose logs payment-service | grep -i "Failed to publish outbox\|OutboxPoller"
```

### Step 3: Check Kafka producer availability
```bash
docker exec payment-kafka kafka-broker-api-versions --bootstrap-server localhost:9092
```

### Step 4: Check if OutboxPoller is running
```bash
docker-compose logs payment-service | grep -c "Publishing.*outbox events"
# If count is 0, poller may be stopped
```

## Mitigation

| Cause | Action |
|-------|--------|
| **Kafka broker down** | `docker-compose restart kafka` |
| **OutboxPoller thread dead** | `docker-compose restart payment-service` |
| **Network partition** | Check Docker network: `docker network inspect payment-network` |
| **Kafka topic not created** | `docker exec payment-kafka kafka-topics --create --topic payment-events --bootstrap-server localhost:9092` |

### Force batch publish (manual)
```sql
-- Reset unpublished events (forces republish on next poll)
UPDATE payment_outbox SET published_at = NULL
WHERE event_type = 'PaymentCreated' AND published_at IS NOT NULL
AND created_at > now() - interval '1 hour';
```

## Prevention
- [ ] Monitor outbox backlog in Grafana
- [ ] Add health check that verifies Kafka producer connectivity
- [ ] Add circuit breaker on OutboxPoller (Phase 8)
- [ ] Phase 8: convert to async batch with `whenComplete()` for better error handling
