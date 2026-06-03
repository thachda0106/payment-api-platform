# Runbook 2: DLQ Growth

## Symptom
- `DLQNotEmpty` alert firing
- Poison messages accumulating in `payment-events-dlq` topic

## Diagnosis

### Step 1: Inspect DLQ messages
```bash
docker exec payment-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic payment-events-dlq \
  --from-beginning --max-messages 5
```

### Step 2: Identify failure pattern
- Check message payload for malformed JSON
- Check if message key/headers are missing
- Check if paymentId references a non-existent payment

### Step 3: Check consumer error logs
```bash
docker-compose logs fraud-service | grep -i "error\|failed\|exception" | tail -20
docker-compose logs financial-core | grep -i "error\|failed\|exception" | tail -20
```

## Mitigation

| Cause | Action |
|-------|--------|
| **Malformed event** | Manually discard: consume and acknowledge |
| **Missing paymentId** | Discard — unrecoverable |
| **Transient error (DB timeout)** | Replay: publish back to original topic |
| **Schema incompatibility** | Fix consumer schema, then replay |

### Replay DLQ messages (discard)
```bash
# Discard all DLQ messages (DESTRUCTIVE)
docker exec payment-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group dlq-cleanup --topic payment-events-dlq --reset-offsets --to-latest --execute
```

### Replay DLQ messages back to source
```bash
# Manual replay: consume DLQ → publish to payment-events
# Use Kafka Connect or a script for production
```

## Prevention
- [ ] Fix consumer error handling (catch specific exceptions, not all)
- [ ] Add schema validation before consuming
- [ ] Monitor DLQ depth as a Grafana panel
