# Mini Project — Observability Platform

## Goal

Design and implement the complete observability platform for the Payment API Platform: dashboards, alerts, runbooks, and incident response templates.

## Deliverables

### 1. Grafana Dashboard JSON

```json
{
  "dashboard": {
    "title": "Payment API — Service Overview",
    "panels": [
      {"title": "Request Rate", "targets": [{"expr": "rate(http_requests_total{service=\"payment\"}[5m])"}]},
      {"title": "Error Rate", "targets": [{"expr": "rate(http_requests_total{service=\"payment\",status=~\"5..\"}[5m])/rate(http_requests_total{service=\"payment\"}[5m])"}]},
      {"title": "P99 Latency", "targets": [{"expr": "histogram_quantile(0.99,rate(http_request_duration_seconds_bucket{service=\"payment\"}[5m]))"}]},
      {"title": "DB Query Latency", "targets": [{"expr": "histogram_quantile(0.99,rate(db_query_duration_seconds_bucket[5m]))"}]},
      {"title": "Kafka Consumer Lag", "targets": [{"expr": "sum(kafka_consumer_lag) by (topic)"}]},
      {"title": "Payment Success Rate", "targets": [{"expr": "rate(payment_success_total[5m])/rate(payment_total[5m])"}]}
    ]
  }
}
```

### 2. Alerting Rules

```yaml
groups:
- name: payment_critical
  rules:
  - alert: PaymentHighErrorRate
    expr: rate(http_requests_total{service="payment",status=~"5.."}[5m])/rate(http_requests_total{service="payment"}[5m]) > 0.05
    for: 5m
    labels: {severity: P0, service: payment}
    annotations: {summary: "Payment error rate > 5%", runbook: "/runbooks/payment-errors.md"}

  - alert: PaymentHighLatency
    expr: histogram_quantile(0.99,rate(http_request_duration_seconds_bucket{service="payment"}[5m])) > 1.0
    for: 10m
    labels: {severity: P1, service: payment}
    annotations: {summary: "P99 latency > 1s", runbook: "/runbooks/payment-latency.md"}

  - alert: PaymentErrorBudgetBurn
    expr: (rate(http_requests_total{service="payment",status=~"5.."}[1h])/rate(http_requests_total{service="payment"}[1h])) > 14.4*0.0005
    for: 5m
    labels: {severity: P0, service: payment}
    annotations: {summary: "Error budget burning at 14.4x", runbook: "/runbooks/error-budget.md"}

  - alert: KafkaConsumerLag
    expr: sum(kafka_consumer_lag) by (topic, consumergroup) > 50000
    for: 10m
    labels: {severity: P1, service: kafka}
    annotations: {summary: "Consumer lag > 50K", runbook: "/runbooks/kafka-lag.md"}

  - alert: DBConnectionPoolExhausted
    expr: db_connections_active / db_connections_max > 0.9
    for: 5m
    labels: {severity: P1, service: postgres}
    annotations: {summary: "DB connection pool > 90%", runbook: "/runbooks/db-connections.md"}
```

### 3. Runbook Template

```markdown
# Runbook: Payment High Error Rate

## Symptoms
- Grafana alert: PaymentHighErrorRate
- PagerDuty notification
- `/v1/payments` returning 5xx errors

## Triage (First 2 Minutes)
1. Check Grafana dashboard: which endpoints are failing? /v1/payments, /v1/refunds?
2. Check recent deployments: `kubectl rollout history deployment/payment-service`
3. Check downstream health: Fraud Service, Ledger Service, DB

## Common Causes & Fixes
### Cause 1: Database Connection Pool Exhausted
- Symptom: `db_connections_active` ≈ `db_connections_max`
- Fix: Restart pods `kubectl rollout restart deployment/payment-service`
- Permanent: Increase pool size, add connection pool monitoring

### Cause 2: Downstream Service Timeout
- Symptom: Circuit breaker open on Fraud Service
- Fix: Check Fraud Service status. If degraded, manually close circuit breaker.
- Permanent: Tune timeout, add fallback

### Cause 3: Invalid Configuration
- Symptom: Recent deployment
- Fix: Rollback to previous version `helm rollback payment 42`
- Permanent: Add canary deployment, config validation in CI

## Escalation
- If unresolved after 30 minutes → Escalate to Staff Engineer
- If revenue impact → Escalate to VP Engineering
```

### 4. Acceptance Criteria

1. Dashboard shows RED metrics for all services
2. Alerting rules cover: error rate, latency, error budget burn, Kafka lag, DB pool
3. Every alert has severity label and runbook link
4. Runbook template covers triage steps + 3 common causes with fixes
5. Postmortem template includes: timeline, 5 Whys, action items with owners and due dates
