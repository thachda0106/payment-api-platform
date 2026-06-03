# Runbook 1: Payment Processing Stuck

## Symptom
- `PaymentSuccessRateDrop` alert firing
- Consumer lag increasing on `fraud-events` or `ledger-events`
- Payments stuck in CREATED status

## Diagnosis

### Step 1: Check Kafka consumer lag
```bash
docker exec payment-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group fraud-service --describe
docker exec payment-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group financial-core --describe
```

### Step 2: Check service health
```bash
curl -s http://localhost:8000/readiness | jq '.checks'   # fraud
curl -s http://localhost:8080/readiness | jq '.checks'   # financial-core
```

### Step 3: Check outbox backlog
```sql
SELECT COUNT(*) FROM payment_outbox WHERE published_at IS NULL;
```

### Step 4: Check DLQ
```bash
docker exec payment-kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic payment-events-dlq --time -1
```

## Mitigation

| Cause | Action |
|-------|--------|
| **fraud-service down** | Restart: `docker-compose restart fraud-service` |
| **financial-core down** | Restart: `docker-compose restart financial-core` |
| **DB connection pool exhausted** | See `db-connection-exhaustion.md` runbook |
| **Kafka broker issue** | `docker-compose restart kafka` |
| **OutboxPoller stuck** | Restart payment-service: `docker-compose restart payment-service` |

## Verification
- [ ] Consumer lag returning to 0
- [ ] PaymentCreated → PaymentApproved → LedgerEntryCreated → NotificationSent flow restored
- [ ] `PaymentSuccessRateDrop` alert resolved
