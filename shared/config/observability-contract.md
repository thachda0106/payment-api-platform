# Observability Contract — Payment API Platform

## SLI → SLO → Error Budget

### payment-service (Java — Payment API)

| SLI | Type | SLO | Error Budget (monthly) | Owner |
|-----|------|-----|------------------------|-------|
| `payment_api_availability` | Availability | **99.9%** | 43.2 min downtime | payment-service |
| `payment_api_latency_p95` | Latency | **< 300ms** | — | payment-service |
| `payment_api_latency_p99` | Latency | **< 1s** | — | payment-service |
| `payment_success_rate` | Correctness | **> 99%** | < 1% errors | payment-service |
| `payment_duplicate_rate` | Correctness | **0%** | Must be zero | payment-service |

### fraud-service (Python — Risk Engine)

| SLI | Type | SLO | Error Budget | Owner |
|-----|------|-----|-------------|-------|
| `fraud_scoring_latency_p95` | Latency | **< 200ms** | — | fraud-service |
| `fraud_decision_rate` | Freshness | **> 99.5%** | < 0.5% dropped | fraud-service |
| `fraud_consumer_lag` | Freshness | **< 100** | — | fraud-service |

### financial-core (Java — Ledger)

| SLI | Type | SLO | Error Budget | Owner |
|-----|------|-----|-------------|-------|
| `ledger_posting_latency_p95` | Latency | **< 500ms** | — | financial-core |
| `ledger_balance_accuracy` | Correctness | **100%** | Double-entry must balance | financial-core |
| `ledger_consumer_lag` | Freshness | **< 500** | — | financial-core |

### notification-service (Node.js — Email)

| SLI | Type | SLO | Error Budget | Owner |
|-----|------|-----|-------------|-------|
| `notification_delivery_time_p95` | Latency | **< 60s** | — | notification-service |
| `notification_success_rate` | Correctness | **> 95%** | < 5% failed deliveries | notification-service |

### Kafka Platform

| SLI | Type | SLO | Owner |
|-----|------|-----|-------|
| `kafka_consumer_lag` (all groups) | Freshness | **< 1000** per partition | Platform |
| `dlq_message_count` | Correctness | **0** (must be empty) | Platform |
| `kafka_broker_availability` | Availability | **99.99%** | Platform |

### Infrastructure

| SLI | Type | SLO | Owner |
|-----|------|-----|-------|
| `postgres_connection_availability` | Availability | **99.99%** | Platform |
| `redis_availability` | Availability | **99.99%** | Platform |

---

## Error Budget Policy

| Burn Rate | Action |
|-----------|--------|
| < 50% consumed | Normal operations |
| > 50% consumed | Stop feature deploys, investigate |
| > 80% consumed | Incident declared, all-hands on reliability |
| > 100% consumed | Freeze all changes until SLO restored |

---

## Alert Rules

### Critical (P1 — wake up)

```yaml
# Service Down
- alert: ServiceDown
  expr: up == 0
  for: 1m
  labels: { severity: critical }
  annotations:
    summary: "{{ $labels.job }} is down"
    runbook: "docs/cross-cutting/operations/runbooks/service-down.md"

# DLQ Growth
- alert: DLQGrowth
  expr: kafka_topic_partition_current_offset{topic="payment-events-dlq"} > 0
  for: 30s
  labels: { severity: critical }
  annotations:
    summary: "DLQ has {{ $value }} poison messages"
    runbook: "docs/cross-cutting/operations/runbooks/dlg-growth.md"

# Payment Success Rate Drop
- alert: PaymentSuccessRateDrop
  expr: rate(payment_success_total[5m]) / rate(payment_total[5m]) < 0.95
  for: 5m
  labels: { severity: critical }
  annotations:
    summary: "Payment success rate dropped below 95%"
    runbook: "docs/cross-cutting/operations/runbooks/payment-processing-stuck.md"

# Double-Entry Balance Broken
- alert: LedgerBalanceBroken
  expr: ledger_balance_sum != 0
  for: 1m
  labels: { severity: critical }
  annotations:
    summary: "Double-entry ledger balance invariant violated"
```

### Warning (P2 — investigate during business hours)

```yaml
# Consumer Lag
- alert: ConsumerLagHigh
  expr: kafka_consumer_group_lag > 500
  for: 5m
  labels: { severity: warning }
  annotations:
    summary: "Consumer lag {{ $labels.consumer_group }} = {{ $value }}"

# High Error Rate
- alert: HighErrorRate
  expr: rate(http_requests_total{status=~"5.."}[5m]) / rate(http_requests_total[5m]) > 0.05
  for: 5m
  labels: { severity: warning }

# Database Connection Pool Low
- alert: DatabaseConnectionPoolExhausted
  expr: hikaricp_connections_pending > 5
  for: 2m
  labels: { severity: warning }
  annotations:
    summary: "Connection pool has {{ $value }} pending connections"
    runbook: "docs/cross-cutting/operations/runbooks/db-connection-exhaustion.md"

# Outbox Backlog Growth
- alert: OutboxBacklogGrowth
  expr: rate(payment_outbox_published_total[15m]) < rate(payment_outbox_created_total[15m])
  for: 10m
  labels: { severity: warning }
  annotations:
    summary: "Outbox backlog growing — publishing slower than creation"
    runbook: "docs/cross-cutting/operations/runbooks/outbox-backlog-growth.md"

# High p95 Latency
- alert: PaymentLatencyHigh
  expr: histogram_quantile(0.95, rate(http_request_duration_seconds_bucket{job="payment-service"}[5m])) > 0.3
  for: 5m
  labels: { severity: warning }
```
