# Module 08 — Observability Stack

## Duration: 3–4 hours | Critical: Yes

---

## Learning Objectives

By the end of this module, you will understand:
- The three pillars of observability: **metrics, traces, logs**
- Why OpenTelemetry is our chosen instrumentation standard
- How distributed tracing works (W3C Trace Context, span propagation)
- Prometheus metrics types and how payment services expose them
- Logging: structured JSON logs vs. free text
- Alerting: SLIs, SLOs, and how to define them for payment systems
- How all these pieces fit together in our stack

---

## 1. The Three Pillars of Observability

```
OBSERVABILITY
├── METRICS
│   "How many? How fast? How long? How many errors?"
│   └── Prometheus, Grafana
│
├── TRACES
│   "What happened for THIS specific request across all services?"
│   └── OpenTelemetry → Jaeger
│
└── LOGS
    "What did each service say about this request?"
    └── Fluent Bit → OpenSearch (Dashboards)
```

| Aspect | Metrics | Traces | Logs |
|--------|---------|--------|------|
| **Granularity** | Aggregate | Per-request | Per-event |
| **Cardinality** | Low (tags) | High (trace IDs) | Highest |
| **Storage cost** | Low | Medium | High |
| **Search** | Aggregation | Trace ID lookup | Full-text |
| **Retention** | Months (downsampled) | Days | Weeks |

---

## 2. Our Stack (From Phase 11)

```
┌─────────────────────────────────────────────────────────────┐
│                      GRAFANA                                 │
│  Dashboards · Alerting · SLO tracking · Unified UI          │
└───────────┬──────────────────────┬────────────────┬────────┘
            │                      │                │
    ┌───────▼───────┐    ┌────────▼───────┐   ┌────▼──────┐
    │  PROMETHEUS   │    │    JAEGER       │   │ OPENSEARCH │
    │   Metrics     │    │    Traces       │   │   Logs     │
    │   TSDB        │    │   OTLP natively │   │   (ELK fork)│
    └───────┬───────┘    └────────────────┘   └────┬───────┘
            │                                      │
            └────────────────┬─────────────────────┘
                             │
                ┌────────────▼────────────┐
                │   OPENTELEMETRY COLLECTOR│
                │   (OTel Collector)       │
                │   Receives OTLP/gRPC     │
                │   Processes + routes     │
                └────────────┬────────────┘
                             │
                ┌────────────▼────────────┐
                │   GO MICROSERVICES       │
                │   (OTel SDK auto-inst)   │
                │   HTTP · gRPC · Kafka    │
                │   PostgreSQL             │
                └─────────────────────────┘
```

### Instrumentation: OpenTelemetry Go SDK

Every microservice auto-instruments using `go.opentelemetry.io/otel`:

```go
import (
    "go.opentelemetry.io/otel"
    "go.opentelemetry.io/otel/attribute"
    "go.opentelemetry.io/otel/trace"
)

var tracer = otel.Tracer("payment-service")

func (s *Service) ProcessPayment(ctx context.Context, req PaymentRequest) error {
    ctx, span := tracer.Start(ctx, "payment.process",
        trace.WithAttributes(
            attribute.String("transaction_id", req.TransactionID),
            attribute.Int64("amount", req.Amount),
            attribute.String("sender_id", req.SenderID),
        ),
    )
    defer span.End()

    // All child calls automatically propagate context
    balance, err := s.walletService.GetBalance(ctx, req.SenderID)
    if err != nil {
        span.RecordError(err)
        span.SetStatus(codes.Error, err.Error())
        return err
    }
    // ...
}
```

---

## 3. Distributed Tracing Deep-Dive

### Why Tracing?

In a monolithic NestJS app, a single request hits one process. Logs are easy to correlate.

In our microservice platform, a single payment goes through:
```
API Gateway → Wallet Service → Ledger Service → Merchant Service → Notification Service
```

Without tracing, you cannot connect the dots. You see an error in Ledger but have no idea which payment request caused it.

### W3C Trace Context

Every request carries HTTP headers:

```
traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
             │  │                    │                │              │
             │  │                    │                │              └─ trace flags
             │  │                    │                └── span_id (current span)
             │  │                    └─── trace_id (global, 16 bytes)
             │  └──── version (00 = current)
             └──── version indicator
```

- **trace_id**: Same across ALL services for a single request
- **span_id**: Unique per operation within a service
- **parent_span_id**: Links spans together to form the trace tree

### Tracing Span Hierarchy

```
Trace: payment.create
├── Span: api-gateway.handle [60ms]
│   └── Span: wallet.deduct [25ms]
│       ├── Span: db.select_for_update [8ms]
│       ├── Span: db.update_balance [5ms]
│       └── Span: kafka.publish [12ms]
├── Span: ledger.create_entry [30ms]
│   ├── Span: db.stored_procedure [20ms]
│   └── Span: outbox.insert [10ms]
└── Span: notification.send [15ms]
```

This tree tells you:
- **Which service is slow**: wallet.deduct took 25ms (within the 60ms total)
- **Which DB query is slow**: The SELECT FOR UPDATE took 8ms — should be < 1ms
- **Where the error happened**: If ledger.create_entry fails, you can see exactly which payment caused it

### Instrumenting Database Queries

```go
func (r *WalletRepository) DeductBalance(ctx context.Context, walletID string, amount int64) error {
    ctx, span := tracer.Start(ctx, "wallet_repo.deduct_balance",
        trace.WithAttributes(
            attribute.String("wallet_id", walletID),
            attribute.Int64("amount", amount),
        ),
    )
    defer span.End()

    // The DB driver (pgx) has OTel instrumentation
    _, err := r.db.ExecContext(ctx,
        "UPDATE wallets SET balance = balance - $1 WHERE id = $2 AND balance >= $1",
        amount, walletID,
    )
    if err != nil {
        span.RecordError(err)
        span.SetStatus(codes.Error, err.Error())
        return err
    }

    return nil
}
```

### Sampling Strategy

Tracing every request is expensive. Our strategy:

| Sampling Type | Rate | Applied To |
|--------------|------|------------|
| **Head-based** (at gateway) | 100% | Failed/error requests |
| **Head-based** | 10% | Normal requests |
| **Tail-based** | 100% | High-value users |
| **Tail-based** | 100% | Transactions > 10M VND |

---

## 4. Metrics with Prometheus

### Metric Types

| Type | Description | Example in Payment System |
|------|-------------|--------------------------|
| **Counter** | Only increases; cumulative | `payments_total{status="success"} 1500` |
| **Gauge** | Goes up and down | `active_connections 42`, `wallet_balance{user_id="A"} 500000` |
| **Histogram** | Counts observations in configurable buckets | `payment_duration_seconds{le="0.1"} 1200` — 1200 payments completed in < 100ms |
| **Summary** | Like histogram but calculates quantiles server-side | `payment_duration_seconds{quantile="0.99"} 0.250` — P99 = 250ms |

### What to Instrument in a Payment Service

```go
// Prometheus metrics for a payment service
var (
    // Counters
    paymentsTotal = promauto.NewCounterVec(prometheus.CounterOpts{
        Name: "payments_total",
        Help: "Total number of payment transactions processed",
    }, []string{"status", "payment_method"})  // status: success, failed, declined

    paymentAmountTotal = promauto.NewCounterVec(prometheus.CounterOpts{
        Name: "payments_amount_total",
        Help: "Total amount (VND) processed",
    }, []string{"currency"})

    // Histogram
    paymentDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
        Name:    "payment_duration_seconds",
        Help:    "Payment processing duration in seconds",
        Buckets: prometheus.ExponentialBuckets(0.005, 2, 10), // 5ms to ~2.5s
    }, []string{"payment_method"})

    // Gauge
    activeSessions = promauto.NewGauge(prometheus.GaugeOpts{
        Name: "payment_service_sessions_active",
        Help: "Number of active payment sessions",
    })
)

// In business logic:
func (s *PaymentService) ProcessPayment(ctx context.Context, req PaymentRequest) error {
    start := time.Now()

    err := s.executePayment(ctx, req)

    duration := time.Since(start).Seconds()
    status := "success"
    if err != nil {
        status = "failed"
    }

    paymentsTotal.WithLabelValues(status, req.Method).Inc()
    paymentAmountTotal.WithLabelValues(req.Currency).Add(float64(req.Amount))
    paymentDuration.WithLabelValues(req.Method).Observe(duration)

    return err
}
```

### RED Method (Rate, Errors, Duration)

Every service must expose these three metrics:

```
Rate:     How many requests per second?
Errors:   What fraction fail?
Duration: How long do successful requests take? (P50, P95, P99)
```

| Metric | PromQL | Business Meaning |
|--------|--------|-----------------|
| **Rate** | `rate(payments_total[5m])` | Transaction throughput |
| **Error Rate** | `rate(payments_total{status="failed"}[5m]) / rate(payments_total[5m])` | Failure ratio |
| **P99 Latency** | `histogram_quantile(0.99, rate(payment_duration_seconds_bucket[5m]))` | Worst-case user experience |

### USE Method (Utilization, Saturation, Errors)

For infrastructure resources (the "SRE way"):

| Resource | Utilization | Saturation | Errors |
|----------|-------------|------------|--------|
| CPU | `node_cpu_seconds_total` | Load average | — |
| Memory | `node_memory_MemTotal - node_memory_MemFree` | OOM kills | — |
| Disk | `node_filesystem_size_bytes` | `node_disk_io_time_ms` | Disk errors |
| DB | Active connections | Connections waiting | Query errors |
| Kafka | Partitions leader | Consumer lag | Producer errors |

---

## 5. Logging

### Structured JSON Logs (No Free Text)

```go
// BAD — Grep-dependent, unstructured
log.Printf("Payment %s processed for user %s amount %d", txnID, userID, amount)

// GOOD — Structured, filterable
log.WithFields(log.Fields{
    "txn_id":    txnID,
    "user_id":   userID,
    "amount":    amount,
    "method":    req.Method,
    "trace_id":  span.SpanContext().TraceID().String(),
    "duration":  duration.Milliseconds(),
}).Info("payment_processed")
```

Output:

```json
{"level":"info","time":"2026-03-21T14:30:00.000Z",
 "message":"payment_processed",
 "txn_id":"txn-8f7a3b","user_id":"user-A","amount":50000,
 "method":"wallet","trace_id":"0af7651916cd43dd8448eb211c80319c",
 "duration":45}
```

### Log Levels

| Level | Usage | Examples |
|-------|-------|----------|
| `ERROR` | Something is wrong NOW | Payment failed, DB connection lost, Kafka unavailable |
| `WARN` | Something might be wrong | High latency, rate limit approaching, retry happened |
| `INFO` | Normal operations | Payment processed, user created, settlement completed |
| `DEBUG` | Troubleshooting details | SQL queries executed, external API calls (only with feature flag) |

### What to Log in a Payment System

**Always log**:
- Transaction ID and amount
- Sender and receiver IDs
- Request and response (masked sensitive fields)
- Idempotency key
- Trace context
- Request duration

**Never log**:
- Full PAN (card number)
- CVV/CVC
- PIN codes
- Passwords
- Full ID numbers

### Log Correlation with Traces

Every log entry includes the `trace_id`, so you can:
1. See an error in OpenSearch
2. Click the trace_id → opens Jaeger trace
3. See every service involved in that exact request
4. Find exactly which database query, Kafka message, or HTTP call caused the error

---

## 6. Alerting & SLOs

### SLIs (Service Level Indicators)

An SLI is a quantifiable measurement:

```yaml
# Example SLIs for payment service
slis:
  - name: payment_success_rate
    description: Fraction of payment requests that complete successfully
    metric: rate(payments_total{status="success"}[5m]) / rate(payments_total[5m])
    unit: ratio

  - name: payment_latency_p99
    description: 99th percentile payment processing time
    metric: histogram_quantile(0.99, rate(payment_duration_seconds_bucket[5m]))
    unit: seconds

  - name: settlement_timeliness
    description: Fraction of EOD settlement batches completed before cutoff (T+1 09:00)
    metric: rate(settlement_batches{status="completed"}[1d])
    unit: ratio
```

### SLOs (Service Level Objectives)

```yaml
objectives:
  - name: payment_success_rate_monthly
    sli: payment_success_rate
    target: 0.9995  # 99.95% — allows ~21 failures per million
    window: 28d

  - name: payment_latency_monthly
    sli: payment_latency_p99
    target: 0.250  # 250ms max
    window: 28d
    source: gauge

  - name: settlement_timeliness_monthly
    sli: settlement_timeliness
    target: 0.99  # 99% of settlements on time
    window: 28d
```

### Error Budget

```
Error budget = 1 - SLO target

For payment_success_rate = 99.95%:
  Error budget = 0.05% = 500 failures per 1,000,000 requests per month

If we have 10,000 successful payments and 100 failures this month:
  Error budget consumed: 100 / (10,000 / 0.0005) = 20%
```

When error budget is depleted:
- **Stop all non-critical deployments** (canaries, feature flags, config changes)
- **Freeze new features**
- **Focus on reliability**

### Alerting Rules

```yaml
# Alertmanager rules
groups:
  - name: payment_alerts
    rules:
      - alert: HighPaymentErrorRate
        expr: |
          rate(payments_total{status="failed"}[5m])
          / rate(payments_total[5m]) > 0.001  # 0.1% failure rate
        for: 5m
        labels:
          severity: critical
          pagerduty: "payment-team"
        annotations:
          summary: "Payment error rate exceeds 0.1% for 5 minutes"
          description: "Current rate: {{ $value | humanizePercentage }}"

      - alert: HighPaymentLatency
        expr: |
          histogram_quantile(0.99, rate(payment_duration_seconds_bucket[5m])) > 1.0
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "P99 payment latency exceeds 1 second"
```

### On-Call Response

| Severity | Response Time | Example |
|----------|--------------|---------|
| **P0 (Critical)** | < 5 minutes | Payment service down, money at risk |
| **P1 (Major)** | < 15 minutes | Error rate spike, settlement delayed |
| **P2 (Minor)** | < 1 hour | Latency degradation, non-critical feature broken |
| **P3 (Low)** | Next business day | Cosmetic bug, dashboard missing data |

---

## 7. Practical: Full Observability Setup for a Go Service

```go
// main.go — Service initialization
func main() {
    // 1. OpenTelemetry
    tp, _ := initTracer()
    defer tp.Shutdown(context.Background())
    otel.SetTracerProvider(tp)

    // 2. Prometheus
    metricsMux := http.NewServeMux()
    metricsMux.Handle("/metrics", promhttp.Handler())
    go http.ListenAndServe(":9090", metricsMux)

    // 3. Structured logging
    log.SetFormatter(&log.JSONFormatter{})
    log.SetLevel(log.InfoLevel)

    // 4. Start service
    r := chi.NewRouter()
    r.Use(otelhttp.NewMiddleware("payment-service"))
    r.Use(loggingMiddleware)

    s := NewService()
    r.Post("/api/v1/transactions", s.HandleCreateTransaction)
    http.ListenAndServe(":8080", r)
}

func initTracer() (*sdktrace.TracerProvider, error) {
    exporter, _ := otlptracegrpc.New(context.Background(),
        otlptracegrpc.WithEndpoint("otel-collector:4317"),
        otlptracegrpc.WithInsecure(),
    )
    tp := sdktrace.NewTracerProvider(
        sdktrace.WithBatcher(exporter),
        sdktrace.WithResource(resource.NewWithAttributes(
            semconv.SchemaURL,
            semconv.ServiceNameKey.String("payment-service"),
            semconv.DeploymentEnvironmentKey.String("production"),
        )),
    )
    return tp, nil
}
```

---

## Check Questions

1. What are the three pillars of observability?
2. What problem does distributed tracing solve that logs alone don't?
3. What does a W3C `traceparent` header contain?
4. Why is structured JSON logging better than `log.Printf()`?
5. What's the difference between a Counter and a Histogram in Prometheus?
6. What's an SLI? What's an SLO? How do they relate to an error budget?
7. What should you NEVER log in a payment system?
8. Why is tail-based sampling better than head-based for certain transactions?

---

## Final Module

[Module 07 — Go Language & Ecosystem](07-go-language-and-ecosystem.md)

If you haven't yet, also review [Phase 20 — Observability & Monitoring](../../docs/stages/E-hardening/20-observability-monitoring.md) for the full production plan.
