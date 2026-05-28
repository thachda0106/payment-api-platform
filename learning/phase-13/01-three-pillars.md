# Module 01 — Three Pillars, Prometheus, Grafana & OTel

## 1.1 The Three Pillars

| Pillar | Question | Tool |
|--------|----------|------|
| **Logs** | WHAT happened at this exact moment? | Structured JSON → OpenSearch/Loki |
| **Metrics** | HOW MANY and HOW FAST over time? | Prometheus → Grafana |
| **Traces** | WHERE did this request go across services? | OTel → Jaeger/Tempo |

**You need all three.** Logs without traces: can't follow a request across services. Metrics without logs: know error rate is 5% but not WHY. Traces without metrics: can debug one request but not see patterns.

## 1.2 Structured Logging with Correlation IDs

```json
{"timestamp":"2026-05-27T10:30:00Z","level":"INFO","service":"payment-service",
 "trace_id":"a1b2c3d4","span_id":"e5f6a7b8",
 "payment_id":"PAY-001","amount":100000,"status":"COMPLETED","duration_ms":45}
```

**Correlation ID** (`trace_id`): Propagated across ALL services via HTTP headers (`traceparent`). Every log line in every service includes it. At 3 AM, search for `trace_id=X` and see the ENTIRE request lifecycle across all services.

## 1.3 Metrics: RED Method

| Metric | PromQL | What It Tells You |
|--------|--------|-------------------|
| **Rate** | `rate(http_requests_total[5m])` | How many requests/sec |
| **Errors** | `rate(http_requests_total{status=~"5.."}[5m]) / rate(http_requests_total[5m])` | Error ratio |
| **Duration** | `histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))` | P99 latency |

### USE Method (Infrastructure)

- **Utilization**: CPU, memory, disk usage
- **Saturation**: Queue depth, connection pool waiters, Goroutine count
- **Errors**: Network errors, disk I/O errors, OOM kills

## 1.4 Prometheus

### Architecture

Prometheus PULLS metrics from targets (not push). Each service exposes `/metrics` endpoint. Prometheus scrapes every 15s. Stores in TSDB.

### Metric Types

| Type | Example | Use |
|------|---------|-----|
| **Counter** | `http_requests_total` | Only increases (or resets on restart) |
| **Gauge** | `http_requests_in_flight` | Goes up and down |
| **Histogram** | `http_request_duration_seconds` | Distribution (P50/P90/P99) |
| **Summary** | Similar to Histogram | Client-side quantiles |

### PromQL (Key Queries)

```promql
# Request rate per second
rate(http_requests_total[5m])

# Error rate (5xx)
rate(http_requests_total{status=~"5.."}[5m])

# P99 latency over 5 minutes
histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))

# P50 latency
histogram_quantile(0.50, rate(http_request_duration_seconds_bucket[5m]))

# CPU usage per pod
sum(rate(container_cpu_usage_seconds_total{namespace="payment"}[5m])) by (pod)

# Kafka consumer lag
sum(kafka_consumer_lag) by (topic, consumergroup)
```

### Recording Rules (Precompute Expensive Queries)

```yaml
groups:
- name: payment_slos
  rules:
  - record: job:http_errors:rate5m
    expr: rate(http_requests_total{status=~"5.."}[5m]) / rate(http_requests_total[5m])
```

### Alerting Rules

```yaml
groups:
- name: payment_alerts
  rules:
  - alert: HighErrorRate
    expr: job:http_errors:rate5m > 0.05
    for: 5m
    labels: {severity: critical}
    annotations: {summary: "Payment error rate > 5%", runbook: "https://wiki/runbooks/payment-errors"}
```

## 1.5 Grafana

### Dashboard Design

- **Row 1**: Service Overview — Request rate, error rate, P50/P90/P99 latency
- **Row 2**: Infrastructure — CPU, memory, disk, network per pod
- **Row 3**: Dependencies — DB query latency, Kafka consumer lag, Redis hit rate
- **Row 4**: Business Metrics — Payment success rate, avg amount, fraud rate, settlement totals

### Variables

```promql
# Multi-select variable: $service
label_values(http_requests_total, service)
# Use in queries:
rate(http_requests_total{service=~"$service"}[5m])
```

## 1.6 Distributed Tracing (OpenTelemetry)

### How It Works

1. Incoming request has `traceparent: 00-{trace_id}-{span_id}-01` header
2. Service creates a SPAN (operation name, start/end time, attributes)
3. Service calls downstream → passes `traceparent` with NEW `span_id`
4. All spans with the same `trace_id` form a TRACE
5. Traces exported to Jaeger/Tempo via OTLP

### Auto-Instrumentation (Zero Code Changes)

- **Java**: `java -javaagent:opentelemetry-javaagent.jar -jar app.jar`
- **Python**: `opentelemetry-instrument uvicorn main:app`
- **Node.js**: `node --require @opentelemetry/auto-instrumentations-node/register app.js`
- **Go**: Add OTel SDK in `main.go` (manual, but minimal)

### OTel Collector

```
Services (OTLP/gRPC) → Collector (receivers→processors→exporters) → Jaeger + Prometheus
```

Deployed as DaemonSet (one per node). Services send to local collector → collector batches and forwards.

## 1.7 Exercises

### Ex 1.1 — PromQL Challenge
Given metrics (http_requests_total, http_request_duration_seconds_bucket), write PromQL for: (a) per-endpoint request rate, (b) P99 latency per service, (c) error budget burn rate, (d) 95th percentile DB query latency.

### Ex 1.2 — Grafana Dashboard
Create a dashboard for the payment service with: request rate, error rate, P50/P90/P99, active connections, DB query latency, Kafka lag. Use variables for service selection.

### Ex 1.3 — OTel Integration
Add OTel auto-instrumentation to one service in each language. Send traces to Jaeger. Trace a request across all 4 services. Verify the full trace appears in Jaeger.

## 1.8 Self-Assessment

- [ ] Can explain the three pillars and what question each answers
- [ ] Can write PromQL for RED metrics and SLO burn rates
- [ ] Can design a Grafana dashboard that answers "is the payment flow healthy?"
- [ ] Understand how W3C Trace Context propagates trace_id across services
- [ ] Can instrument a service with OTel auto-instrumentation
