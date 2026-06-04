# Phase 1 — Observability Foundations

> **Duration**: 1-2 weeks | **Prerequisites**: Operating systems, networking, databases (Phases 0-2)
>
> **Goal**: Understand WHY observability exists as an engineering discipline, what problems it solves, and build the mental models that underpin every tool in this curriculum.

---

## 1.1 The Problem: Software Systems Are Black Boxes

### 1.1.1 Before Observability: The SSH Era

In 2005, when your application broke, you did this:

```
ssh production-server-01
tail -f /var/log/app.log
grep ERROR /var/log/app.log | less
top
free -m
df -h
```

This worked when:
- You had 1-3 servers
- One application per server
- Low traffic volume
- A single engineer on call

**What changed**: The shift from monoliths to microservices, from VMs to containers, from 3 servers to 300. The SSH approach fails because:

1. **You cannot SSH into 300 pods.** Even with automation, the volume overwhelms.
2. **You cannot correlate.** An error in service-A caused a timeout in service-B. Each log file shows only its own perspective.
3. **Containers are ephemeral.** A pod crashes and restarts. Its logs vanish with it unless shipped elsewhere.
4. **You cannot search.** "Find all errors across all services in the last 5 minutes." grep doesn't scale horizontally.
5. **You cannot trend.** "Is latency increasing over the last hour?" You can't answer this from log files alone.

### 1.1.2 The Fundamental Question

Every observability system exists to answer one question:

> **"Is the system doing what it should be doing, and if not, WHY?"**

This breaks into sub-questions:
- **WHAT broke?** → Alert fired
- **WHERE did it break?** → Distributed trace
- **WHEN did it start?** → Metric timeline
- **WHY did it break?** → Logs with context
- **HOW OFTEN does this happen?** → Metric aggregation
- **WHO is affected?** → Attribute dimensions in traces/metrics

---

## 1.2 Monitoring vs Observability

### 1.2.1 The Critical Distinction

This is the most important conceptual distinction in the entire curriculum:

| | Monitoring | Observability |
|---|---|---|
| **Definition** | Pre-defined checks against known failure modes | Ability to ask arbitrary questions about system behavior |
| **Mental model** | "I know what can break, so I'll watch for it" | "I don't know what unknown-unknowns exist" |
| **Data** | Dashboards showing pre-aggregated metrics | High-cardinality raw telemetry with rich context |
| **Question types** | Known questions (is CPU > 80%?) | Unknown questions (why is THIS user's payment slow?) |
| **Evolved from** | Nagios, Zabbix, Icinga | OpenTelemetry, Honeycomb, Lightstep |
| **Analogy** | Car dashboard (fuel, temp, RPM gauge) | Car telemetry recorder (black box) |

### 1.2.2 Why Monitoring Alone Fails

A Nagios check `check_http -H api.example.com -p 443 -w 5 -c 10` tells you:
- Is the HTTP endpoint responding?
- Is response time over 5 seconds?

It tells you NOTHING about:
- Which internal service call is slow (auth? database? downstream API?)
- Whether it's all users or only users in region `us-east-1`
- Whether the slowdown correlates with a deployment 3 minutes ago
- What the actual error message was on the failing code path
- Whether the failure cascaded to other services

**The monitoring trap**: Adding more pre-defined checks. "If I add checks for auth service, database, downstream APIs, I'll catch everything!" This is impossible at scale. The combinatorial explosion of failure modes in a distributed system exceeds what any human can pre-define.

### 1.2.3 Observability as a Property, Not a Product

Observability is not a tool you install. It's a property your system either has or doesn't.

A system is **observable** if, from its external outputs (telemetry), you can infer its internal state without deploying new instrumentation.

```
Control theory definition:
A system is observable if the current state
can be determined in finite time using only
the outputs of the system.
```

**Translation for software**: Your system emits telemetry (metrics, traces, logs). If that telemetry contains enough information to answer questions about internal state without deploying new code, the system is observable.

---

## 1.3 The Three Pillars (And Why They're Actually One)

### 1.3.1 Traditional Model: Three Separate Pillars

```
   Logs           Metrics         Traces
    │               │               │
    │               │               │
  [Elastic]     [Prometheus]    [Jaeger]
    │               │               │
    └───────────────┼───────────────┘
                    │
                [Grafana]
```

This model treats logs, metrics, and traces as separate data types with separate pipelines. **This is wrong.** It creates three silos of partial information that cannot be correlated.

### 1.3.2 Modern Model: Unified Telemetry

```
              Application
                   │
          ┌────────▼────────┐
          │  OTel SDK       │
          │  (one API)      │
          └────────┬────────┘
                   │
          ┌────────▼────────┐
          │  OTel Collector │
          └────┬───┬───┬────┘
               │   │   │
        [Metrics] [Traces] [Logs]
               │   │   │
          └────┴───┴───┴────┘
               │
          [Grafana]  ← correlated
```

OpenTelemetry treats telemetry as **one data model with multiple signal types**. A span (trace) can carry log events. A metric can link to exemplar traces. Logs carry trace context.

### 1.3.3 What Each Signal Tells You

| Signal | Question Answered | Granularity | Example |
|--------|------------------|-------------|---------|
| **Logs** | WHAT happened? | Event-level | "Payment-123 failed with InsufficientFunds" |
| **Metrics** | HOW MANY? HOW FAST? | Aggregate | "payment.failure count = 47/min; p99 latency = 2.3s" |
| **Traces** | WHERE in the call chain? | Request-level | "Payment → Auth(check) → Ledger(debit) → Kafka(publish)" |

**The power is in correlation**, not individual signals. A metric spike tells you something is wrong. You click the metric to see an exemplar trace. The trace shows the failing span. The span links to the log entry with the stack trace. In 30 seconds, you've gone from "something is wrong" to "line 247 in LedgerService threw a constraint violation."

---

## 1.4 Logs — Deep Dive

### 1.4.1 Why Logs Are the Oldest Telemetry

Logs are the first thing every developer adds:

```java
System.out.println("Processing payment " + paymentId);
```

They require no infrastructure, no collector, no database. `stdout`/`stderr` just works. This is why logs have been the default telemetry for 40+ years.

### 1.4.2 The Problem with Unstructured Logs

```log
2024-01-15 14:23:45 ERROR PaymentService - Payment failed for user 42: insufficient funds
```

**What's wrong with this line:**

1. **Not parseable by machines.** Every tool must regex-parse this to extract fields. Every regex is fragile.
2. **No context propagation.** You cannot tell that this error was caused by auth-service returning a 401 3 seconds earlier.
3. **Arbitrary format.** Every service logs differently. No standard. No schema.
4. **Expensive to store and index.** Free-text search is slow and imprecise.

### 1.4.3 Structured Logging

```json
{
  "timestamp": "2024-01-15T14:23:45.123Z",
  "level": "ERROR",
  "service": "payment-service",
  "trace_id": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
  "span_id": "1a2b3c4d5e6f1a2b",
  "message": "Payment failed: insufficient funds",
  "attributes": {
    "payment_id": "pay_abc123",
    "user_id": "42",
    "amount": 100.00,
    "error_code": "INSUFFICIENT_FUNDS",
    "wallet_balance": 50.00
  }
}
```

**What changed:**
- Fields are typed (numeric, string, boolean) → indexable by search engines
- `trace_id` and `span_id` link to distributed traces
- `attributes` carry structured context → filterable dimensions
- Machine-consumable → dashboards, alerts, aggregation without parsing

### 1.4.4 Log Aggregation

**The problem**: 100 services each writing to their own `stdout`. No central visibility.

**The solution**: Log aggregation pipeline.

```
Service-1 (stdout) ──┐
Service-2 (stdout) ──┤
Service-3 (stdout) ──┤    ┌──────────────┐    ┌───────────┐
Service-4 (stdout) ──┼───►│ Log Shipper   │───►│ OpenSearch│
Service-5 (stdout) ──┤    │ (Fluentd/     │    │ (Indexing)│
Service-N (stdout) ──┘    │  Fluent Bit)  │    └───────────┘
                          └──────────────┘
```

**Key components:**
- **Log Shipper** (Fluent Bit/Fluentd/Logstash): Collects, parses, enriches, and forwards logs. Runs as a DaemonSet in Kubernetes (one per node, reads container logs from `/var/log/containers`).
- **Log Storage** (OpenSearch/Elasticsearch): Indexes logs for search and aggregation.
- **Log Lifecycle**: Hot (SSD, fast query) → Warm (HDD, slower query) → Cold (S3, archive) → Delete.

**Why not just write to OpenSearch directly from the app?** Coupling, backpressure, buffering. If OpenSearch is slow, your application threads block on HTTP calls. The log shipper decouples writing from shipping.

### 1.4.5 Log Levels — When to Use Each

| Level | Meaning | Example | Action |
|-------|---------|---------|--------|
| **TRACE** | Internal control flow, variable values | `Entering calculateFee()` | Never in production |
| **DEBUG** | Diagnostic information for developers | `Query plan: seq scan on payments` | Can be enabled temporarily |
| **INFO** | Significant business events | `Payment pay_123 completed, amount=100.00` | Always on — this is your audit trail |
| **WARN** | Something unexpected but non-fatal | `Retry attempt 3/5 for ledger write` | Review weekly |
| **ERROR** | Operation failed | `Failed to process payment pay_456` | Alert on count > threshold |
| **FATAL** | Service cannot continue | `Cannot connect to database, shutting down` | Immediate page |

**The INFO level rule**: Every INFO log should represent a business-meaningful event. "Processing request" is DEBUG. "Payment completed" is INFO.

---

## 1.5 Metrics — Deep Dive

### 1.5.1 Why Logs Alone Are Insufficient

Logs tell you WHAT happened, not HOW MANY or HOW FAST.

If you want to answer "What is the p99 latency of the payment endpoint over the last 5 minutes?" using only logs:

1. Query all payment-related log lines for 5 minutes (potentially millions)
2. Parse the `duration_ms` field from each one
3. Sort all values
4. Calculate the 99th percentile

This is slow, expensive, and doesn't scale. You're re-computing the same aggregation every time.

**Metrics pre-aggregate.** Instead of storing every individual event, metrics store pre-computed statistics. "In this 1-second window, 47 payments completed with p99 latency of 350ms."

### 1.5.2 The Four Metric Types

#### Counter

A value that only increases (or resets to zero on restart). Like an odometer.

```
# HELP http_requests_total Total HTTP requests
# TYPE http_requests_total counter
http_requests_total{method="GET", status="200"} 147234
http_requests_total{method="POST", status="200"} 89231
http_requests_total{method="GET", status="500"} 42
```

**What you can compute from a counter:**
- `rate(http_requests_total[5m])` — requests per second (averaged over 5 minutes)
- `increase(http_requests_total[1h])` — total requests in the last hour
- Never query a counter raw — always use `rate()` or `increase()`

**Why counters reset on restart**: Counters are in-memory. When the process restarts, the counter starts at 0. Prometheus's `rate()` function handles this by detecting the reset and assuming the counter wrapped.

#### Gauge

A value that can go up or down. Like a speedometer.

```
# HELP jvm_memory_used_bytes Used heap memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap"} 536870912
jvm_memory_used_bytes{area="nonheap"} 134217728

# HELP kafka_consumer_lag Current consumer lag
# TYPE kafka_consumer_lag gauge
kafka_consumer_lag{group="payment-processor", partition="0"} 1423
```

**What you can compute from a gauge:**
- Raw value at any instant (current memory, current lag)
- `delta(jvm_memory_used_bytes[5m])` — change over time
- `predict_linear(jvm_memory_used_bytes[1h], 3600)` — predicted value in 1 hour

#### Histogram

Measures the distribution of values. Answers "What percentage of requests completed within X time?"

```
# HELP http_request_duration_seconds HTTP request duration
# TYPE http_request_duration_seconds histogram
http_request_duration_seconds_bucket{le="0.005"} 1234
http_request_duration_seconds_bucket{le="0.01"}  3456
http_request_duration_seconds_bucket{le="0.025"} 7890
http_request_duration_seconds_bucket{le="0.05"}  12034
http_request_duration_seconds_bucket{le="0.1"}   15678
http_request_duration_seconds_bucket{le="0.25"}  18901
http_request_duration_seconds_bucket{le="0.5"}   19876
http_request_duration_seconds_bucket{le="1.0"}   19950
http_request_duration_seconds_bucket{le="+Inf"}  20000
http_request_duration_seconds_sum 3456.78
http_request_duration_seconds_count 20000
```

**How buckets work:**
- Each `_bucket` is a counter: "How many requests had duration <= this value?"
- `_sum` is the total of all observed values
- `_count` is the total number of observations
- `+Inf` bucket catches everything

**Computing percentiles:**
```promql
# p99 latency over 5 minutes
histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))
```

**The bucket design problem**: You must choose buckets at instrumentation time. If all your buckets are below the actual p99, the p99 calculation is wrong. Choose buckets that cover your SLO threshold plus headroom.

**Recommended bucket strategy for APIs:**
```
{0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10}
```

#### Summary

Similar to histogram but calculates quantiles client-side.

```
# TYPE http_request_duration summary
http_request_duration{quantile="0.5"} 0.043
http_request_duration{quantile="0.9"} 0.214
http_request_duration{quantile="0.99"} 1.532
http_request_duration_sum 3456.78
http_request_duration_count 20000
```

**Histogram vs Summary — critical trade-off:**

| | Histogram | Summary |
|---|---|---|
| Quantile calculation | Server-side (Prometheus) | Client-side (your app) |
| Aggregatable across instances | YES — `histogram_quantile(0.99, sum(rate(...)))` | NO — client quantiles cannot be merged |
| Quantiles can change after creation | YES — just query different percentile | NO — fixed at instrumentation time |
| CPU cost | Prometheus server (query time) | Your application (always) |
| Memory cost | Higher (multiple buckets) | Lower (few pre-computed values) |
| **Recommendation** | Always use histogram | Never use in production |

**Summary is a trap.** It seems simpler (pre-computed quantiles!) but the inability to aggregate across instances makes it useless for distributed systems. If you have 3 instances of payment-service, you cannot calculate the combined p99 from their individual p99 summaries. With histograms, you can.

### 1.5.3 Labels and Cardinality

Every metric line has labels (key-value pairs):

```
http_requests_total{method="GET", endpoint="/payments", status="200"} 1234
```

Labels are what make metrics powerful. They're the "GROUP BY" of time-series data.

**But labels have a cost: cardinality.**

```
total_unique_time_series = product of (unique_values_for_each_label)
```

If you have:
- 10 HTTP methods
- 100 endpoints
- 10 status codes

That's `10 × 100 × 10 = 10,000` unique time series — just for one metric.

**The cardinality explosion problem:**

Add `user_id` as a label:
```
http_requests_total{user_id="12345"} ...
```
Now you have `10 × 100 × 10 × 1,000,000 = 10 billion` time series. Your Prometheus server dies.

**Rules for labels:**
1. **Never use unbounded values as labels** (user IDs, request IDs, IP addresses)
2. **Labels should have bounded, low cardinality** (< 100 unique values)
3. **High-cardinality data belongs in logs/traces**, not metrics
4. **Delete unused label combinations** — every unique combination is a time series

---

## 1.6 Traces — Deep Dive

### 1.6.1 The Problem Traces Solve

In a monolith, a request is a single thread of execution. To debug a slow request, you profile the thread. The call stack tells you exactly where time is spent.

In a distributed system:
```
Client → API Gateway → Auth Service → Payment Service → Ledger Service → PostgreSQL
                                              ↘ Fraud Service → Redis
```

A single user request creates 6 network calls across 5 services. If the response takes 3 seconds, which service is slow? No single service knows. Each service sees only its own contribution.

**A trace is a distributed call stack.**

### 1.6.2 Core Concepts

#### Span

A span represents a single unit of work. It has:
- **Name**: What operation (e.g., `POST /payments`, `SELECT payments`)
- **Start time** and **End time** (duration)
- **Parent span ID** (who called this)
- **Trace ID** (which request does this belong to)
- **Attributes** (key-value context: `http.method=POST`, `db.statement=SELECT...`)
- **Events** (timestamped log lines within the span: `cache miss`, `retry attempt 2`)
- **Status** (OK or Error)

#### Trace

A trace is a directed acyclic graph of spans sharing the same `trace_id`. It represents the complete journey of a single request through the system.

```
Trace: a1b2c3d4e5f6 (duration: 2450ms)

Span A: API Gateway (0ms - 2450ms, root)
  ├── Span B: Auth Service (5ms - 22ms, child)
  │     └── Span C: Redis GET (6ms - 20ms)
  └── Span D: Payment Service (23ms - 2448ms)
        ├── Span E: Fraud Check (30ms - 450ms)
        │     └── Span F: Redis INCR (31ms - 48ms)
        └── Span G: Ledger Service (452ms - 2440ms)
              └── Span H: PostgreSQL INSERT (455ms - 2435ms)
```

**From this trace, you immediately know**: The ledger write to PostgreSQL took 1980ms. That's the bottleneck. Fraud check was fine. Auth was fine. The problem is `Span H: PostgreSQL INSERT`.

### 1.6.3 Context Propagation

How does Service B know it's part of the same trace as Service A?

**The mechanism: HTTP headers.**

When Service A calls Service B, it injects trace context into the HTTP headers:

```
GET /auth/verify HTTP/1.1
Host: auth-service
traceparent: 00-a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4-1a2b3c4d5e6f1a2b-01
tracestate: vendor=some-value
```

Service B extracts these headers, creates a child span with `trace_id=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4` and `parent_span_id=1a2b3c4d5e6f1a2b`, and continues the trace.

**W3C Trace Context standard:**

`traceparent` format:
```
00-a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4-1a2b3c4d5e6f1a2b-01
│  │                                  │                │
│  │                                  │                flags (01 = sampled)
│  │                                  parent-span-id (16 hex chars)
│  trace-id (32 hex chars)
version (00)
```

**Why standardizing trace context matters**: Before W3C Trace Context, every vendor had their own format. Zipkin used `X-B3-TraceId`. Datadog used `x-datadog-trace-id`. Services instrumented with different vendors could not connect their traces. W3C Trace Context is now the universal standard.

### 1.6.4 Spans vs Logs vs Metrics — When Each Fails

| Scenario | Logs Alone | Metrics Alone | Traces Alone | Proper Approach |
|----------|-----------|---------------|-------------|-----------------|
| P99 latency spike | Can't aggregate cheaply | `histogram_quantile(0.99, ...)` detects | Shows which span is slow | Metric alerts → exemplar trace → span events |
| Rare edge case (1 in 10M) | Log exists somewhere | Lost in aggregation | Must sample it | 100% error tracing + error logs |
| Capacity planning | Can't aggregate | `rate(http_requests_total[30d])` | Too much data | Metrics (aggregates are sufficient) |
| "Why is user X's payment failing?" | Search logs for user_id | user_id not a label (cardinality) | Search traces by attribute | Trace with user_id attribute + span events |
| Memory leak over days | No trend view | `jvm_memory_used_bytes` detects | N/A (not request-scoped) | Metric alerts with process-level metrics |

---

## 1.7 The Golden Signals (Google SRE)

### 1.7.1 The Four Signals

Google's Site Reliability Engineering book defines four signals that cover most system health questions:

#### 1. Latency

How long does it take to serve a request?

**Measure as a distribution, NOT an average.**

```
WRONG: avg latency = 200ms  ← hides the 1% of requests taking 5 seconds
RIGHT: p50=50ms, p95=200ms, p99=2000ms  ← exposes the tail
```

*Why the average lies*: If 99 requests take 10ms and 1 request takes 10100ms, the average is 110ms. You think everything is fine. The 1% of users experiencing 10-second delays disagree.

#### 2. Traffic

How much demand is the system handling?

```
For HTTP: requests per second
For gRPC: RPCs per second
For Kafka: messages per second
```

Traffic is almost always a counter: `rate(http_requests_total[5m])`.

#### 3. Errors

How many requests are failing?

**Distinguish between:**
- **Explicit failures**: HTTP 5xx, gRPC error codes, thrown exceptions
- **Implicit failures**: HTTP 200 with wrong content, slow responses (degraded, not broken)
- **Policy failures**: Responses that exceed your SLO (e.g., HTTP 200 but took > 1 second)

```
error_rate = rate(http_requests_total{status=~"5.."}[5m])
           / rate(http_requests_total[5m])
```

#### 4. Saturation

How "full" is the system? At what point will it degrade?

```
Examples:
- CPU utilization (is there headroom?)
- Memory usage (are we near OOM?)
- Thread pool queue depth (are threads all busy?)
- Connection pool utilization (are we exhausting DB connections?)
- Kafka consumer lag (are we falling behind?)
- Disk I/O utilization (are we nearing I/O limits?)
```

**Saturation is a leading indicator** — it tells you something will break BEFORE it breaks. CPU at 95% hasn't caused errors yet, but it will when traffic spikes.

### 1.7.2 Why Google SRE Chose These Four

The Golden Signals are a **minimal sufficient set**. If you monitor these four for every service, you can:
1. **Detect** most problems before users notice
2. **Diagnose** which signal is abnormal → narrows investigation
3. **Alert** on thresholds with meaningful error budgets

**Counter-example**: Monitoring 200 custom business metrics but NOT monitoring p99 latency or error rate. You'll catch 95% of "payment amount exceeds limit" but miss the database connection pool exhaustion that breaks ALL payments.

---

## 1.8 The RED Method

RED is the Golden Signals specialized for **request-driven services** (HTTP, gRPC endpoints).

| Signal | Meaning | Metric |
|--------|---------|--------|
| **Rate** | Requests per second | `rate(http_requests_total[5m])` |
| **Errors** | Failed requests per second | `rate(http_requests_total{status=~"5.."}[5m])` |
| **Duration** | Request latency distribution | `histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))` |

**Every endpoint in every service should expose these three.** They are the minimum viable telemetry for any HTTP/gRPC service.

```
RED Dashboard for payment-service:

┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│       Rate          │  │       Errors         │  │      Duration       │
│                     │  │                     │  │                     │
│  POST /payments     │  │  POST /payments     │  │  p50: 45ms          │
│  ██████████  120/s  │  │  ▏1.2% error        │  │  p95: 180ms         │
│                     │  │                     │  │  p99: 450ms         │
│  GET /wallet/{id}   │  │  GET /wallet/{id}   │  │                     │
│  ████         45/s  │  │  0% error           │  │  p50: 5ms           │
│                     │  │                     │  │  p95: 12ms          │
│  POST /refunds      │  │  POST /refunds      │  │  p99: 25ms          │
│  ▏           3/s    │  │  0% error           │  │                     │
│                     │  │                     │  │  p50: 60ms          │
│                     │  │                     │  │  p95: 200ms         │
│                     │  │                     │  │  p99: 800ms         │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
```

---

## 1.9 The USE Method

USE is for **resources** (CPU, memory, disk, network, connections), not requests.

| Signal | Meaning | Example Question |
|--------|---------|-----------------|
| **Utilization** | % of resource being used | "Is disk 90% full?" |
| **Saturation** | Amount of queued work | "Is there a queue waiting for CPU?" |
| **Errors** | Resource error count | "Are there disk I/O errors?" |

**USE is applied per resource:**

| Resource | Utilization | Saturation | Errors |
|----------|------------|------------|--------|
| CPU | `node_cpu_seconds_total` (util%) | `node_load1` vs CPU count | N/A (CPU doesn't error) |
| Memory | `node_memory_MemAvailable_bytes` / `MemTotal_bytes` | OOM kill count, swap usage | ECC memory errors |
| Disk | `node_filesystem_avail_bytes` / `node_filesystem_size_bytes` | `node_disk_io_now` (I/O queue depth) | `node_disk_read_errors_total` |
| Network | `node_network_speed_bytes` | `node_network_transmit_drop_total` (dropped packets) | `node_network_transmit_errs_total` |
| PostgreSQL | `pg_stat_database_numbackends` / `max_connections` | `pg_stat_activity` (idle in transaction) | `pg_stat_database_deadlocks` |

**USE for internal resources (not just hardware):**
- Thread pool: utilization = active / max, saturation = queue size, errors = rejected tasks
- Connection pool: utilization = active / max, saturation = wait queue, errors = timeout
- Kafka consumer: utilization = poll rate / produce rate, saturation = lag, errors = deserialization failures

---

## 1.10 The Evolution of Observability

### 1.10.1 Phase 1: Manual (1990s)

```
ssh → tail grep → manual investigation
```

### 1.10.2 Phase 2: Monitoring (2000s)

Nagios, Zabbix, Icinga. Pre-defined checks. Alert on threshold breach.

**Limitation**: Only catches what you pre-defined.

### 1.10.3 Phase 3: Centralized Logging (2010s)

ELK Stack (Elasticsearch, Logstash, Kibana). Logs are shipped, indexed, searchable.

**Limitation**: Logs are verbose, expensive, and slow to query at scale.

### 1.10.4 Phase 4: Metrics Systems (2010s)

Prometheus, Graphite, InfluxDB. Pre-aggregated time-series for efficient trending and alerting.

**Limitation**: Metrics lose request-level detail. Cannot answer "why" questions.

### 1.10.5 Phase 5: Distributed Tracing (2015+)

Zipkin, Jaeger, then OpenTelemetry. Traces connect events across services.

**Limitation**: High data volume requires sampling. Must choose what to keep.

### 1.10.6 Phase 6: Unified Observability (2020+)

OpenTelemetry + correlated signals. One SDK, shared context, correlated views.

**The key insight**: These aren't separate phases to implement separately. They're layers that build on each other. A modern observability platform has ALL of these.

---

## 1.11 Common Misconceptions

### "More dashboards = more observability"

Dashboards show pre-composed questions. Observability is the ability to ask NEW questions. Adding dashboards doesn't increase observability — adding instrumentation does.

### "We have APM, so we're observable"

APM (Application Performance Monitoring) products (Datadog, New Relic, Dynatrace) provide pre-built views. They answer known questions well. They don't help with novel failure modes unless you've instrumented deeply.

### "Logs are enough"

No. Logs are expensive to store, expensive to query, and poor at showing trends or distributions. A p99 latency spike cannot be efficiently queried from raw logs.

### "Metrics are enough"

No. Metrics lose request-level context. When a user reports "my payment failed," you cannot look up that specific payment in aggregate metrics.

### "Traces are enough"

No. Traces show the WHAT and WHERE but not the aggregate trend. You need metrics to detect anomalies, then traces to diagnose them.

### "Observability is a DevOps problem"

If you write backend code, instrumenting it IS your job. The observability platform team provides the infrastructure. You provide the telemetry.

---

## 1.12 Summary: The Observability Stack

```
┌─────────────────────────────────────────┐
│              Alerting Layer              │
│  Alertmanager → PagerDuty → On-Call     │
├─────────────────────────────────────────┤
│           Visualization Layer           │
│  Grafana (metrics + traces + logs)      │
├──────────┬──────────┬───────────────────┤
│ Metrics  │ Traces   │ Logs              │
│ Prometheus│ Jaeger  │ OpenSearch        │
├──────────┴──────────┴───────────────────┤
│           Collection Layer              │
│  OpenTelemetry Collector                │
├─────────────────────────────────────────┤
│         Instrumentation Layer           │
│  OpenTelemetry SDK (per service)        │
├─────────────────────────────────────────┤
│           Application Layer             │
│  Your services emitting telemetry       │
└─────────────────────────────────────────┘
```

**Every layer builds on the layer below. Every layer fails without the layer above.**

---

## Interview Questions — Phase 1

1. **What is the difference between monitoring and observability? Why does the distinction matter in distributed systems?**

   *Answer core points*: Monitoring is pre-defined checks; observability is the ability to ask arbitrary questions. The distinction matters because distributed systems have failure modes too numerous to pre-define. Observability enables debugging unknown-unknowns.

2. **Why is a histogram superior to a summary for measuring latency? Provide a concrete example where summary fails.**

   *Answer core points*: Histograms are server-aggregatable; summaries are client-aggregated. With 3 instances each reporting p99 via summary, you cannot compute the combined p99. With histograms, `histogram_quantile(0.99, sum(rate(...)))` works.

3. **Explain the RED method. Why are these three signals sufficient for request-driven services?**

   *Answer core points*: Rate (demand), Errors (correctness), Duration (quality). Together they cover "how much, is it working, how fast." No other signal is needed to detect anomalies in request-processing services.

4. **What is context propagation and why did W3C standardize it?**

   *Answer core points*: Context propagation carries trace identifiers across service boundaries via HTTP headers (traceparent). W3C standardized it because every vendor (Zipkin, Datadog, AWS X-Ray) had incompatible formats, breaking traces across differently-instrumented services.

5. **What is cardinality in Prometheus and why is it dangerous?**

   *Answer core points*: Cardinality = number of unique label combinations. Each combination is a time series consuming memory. Unbounded labels (user_id, request_id) cause exponential time series growth, crashing Prometheus. Rule: labels must have bounded cardinality.

6. **When would you use USE vs RED?**

   *Answer core points*: USE for resources (CPU, memory, disk, connections — things with finite capacity). RED for request-driven endpoints (HTTP, gRPC — things processing requests). Both are needed; they cover different layers.

7. **Why do Google SRE's Golden Signals include Saturation even though it's harder to measure than Latency/Errors/Traffic?**

   *Answer core points*: Saturation is a leading indicator — it predicts failures before they happen. A service at 95% CPU hasn't errored yet, but a 5% traffic increase will push it over. Without saturation monitoring, your first alert is the error spike, which is too late.

8. **A team says "we log everything, so we're observable." What's wrong with this statement?**

   *Answer core points*: (1) Logs alone don't show trends/efficient aggregations (need metrics). (2) Logs alone don't show causal chains across services (need traces). (3) Logs at volume are expensive to store/query. (4) Unstructured logs are not machine-consumable. Logs are one signal of three.

---

**Next: Phase 2 — OpenTelemetry Deep Dive**
