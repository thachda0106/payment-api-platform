# Phase 8 — Grafana Deep Dive

> **Duration**: 3-4 days | **Prerequisites**: Phases 1-7
>
> **Goal**: Understand Grafana's data source model, dashboard design principles, and the power of correlated signals.

---

## 8.1 Why Grafana Exists

### 8.1.1 The Data Fragmentation Problem

Each observability backend has its own query language and UI:

| Backend | Query Language | Native UI |
|---------|---------------|-----------|
| Prometheus | PromQL | Expression browser (basic) |
| Jaeger | Trace ID, tag search | Jaeger UI |
| OpenSearch | Query DSL | OpenSearch Dashboards |
| Loki | LogQL | Built-in (from Grafana Labs) |

Without Grafana, a typical debugging session:

```
1. Open Prometheus UI → query latency spike → see it correlates with deployment
2. Open Jaeger UI → search for slow traces during the spike → find a trace
3. Open OpenSearch Dashboards → search for logs from that trace → find error
```

Three different UIs, three different query languages, no links between them.

**Grafana's value proposition**: ONE UI that queries ALL backends, with native linking between them.

### 8.1.2 Grafana Is NOT a Database

Grafana stores almost nothing (except dashboards, users, and alerts). It's a pure visualization and exploration layer:

```
Grafana query engine
    ↓ Query translation
    ├── PromQL → Prometheus
    ├── TraceQL → Tempo/Jaeger
    ├── OpenSearch DSL → OpenSearch
    ├── LogQL → Loki
    └── SQL → PostgreSQL/MySQL
    ↓ Result rendering
    ├── Time series panels
    ├── Tables
    ├── Stat panels
    ├── Heatmaps
    └── Traces
```

---

## 8.2 Data Sources

### 8.2.1 The Data Source Abstraction

A data source is Grafana's plugin interface for connecting to a backend:

```
Grafana Core
├── Data Source Plugin Interface
│   ├── Query (send query → receive data)
│   ├── Health Check (is backend reachable?)
│   └── Variable Support (dynamic dropdowns)
│
├── Built-in Data Sources
│   ├── Prometheus
│   ├── Jaeger
│   ├── OpenSearch / Elasticsearch
│   ├── Loki
│   ├── InfluxDB
│   ├── PostgreSQL / MySQL
│   └── CloudWatch
│
└── External Plugins
    ├── ClickHouse
    ├── New Relic
    ├── Datadog
    └── Custom (build your own)
```

### 8.2.2 How a Prometheus Query Works in Grafana

```
Grafana dashboard panel: "p99 latency over time"
    ↓
Panel query editor (PromQL):
  histogram_quantile(0.99,
    sum(rate(http_request_duration_seconds_bucket{
      service="$service",
      endpoint="$endpoint"
    }[5m])) by (le)
  )
    ↓
Variable expansion: $service → "payment-service", $endpoint → "/payments"
    ↓
HTTP GET to Prometheus:
  http://prometheus:9090/api/v1/query_range?
    query=histogram_quantile(...)
    &start=1705305600
    &end=1705312800
    &step=60
    ↓
Prometheus returns:
  {
    "status": "success",
    "data": {
      "resultType": "matrix",
      "result": [
        {
          "metric": {},
          "values": [
            [1705305600, "0.245"],
            [1705305660, "0.251"],
            [1705305720, "0.248"],
            ...
          ]
        }
      ]
    }
  }
    ↓
Grafana renders time series line chart
```

### 8.2.3 Variables — The Key to Reusable Dashboards

Variables make dashboards dynamic and reusable across services:

```
Variable: $service
Type: Query
Data source: Prometheus
Query: label_values(up, service)
Result: [payment-service, auth-service, ledger-service, fraud-service]

Usage in panel queries:
  rate(http_requests_total{service="$service"}[5m])

When user selects "payment-service" from dropdown:
  rate(http_requests_total{service="payment-service"}[5m])
```

**Variable types:**

| Type | Use Case | Example |
|------|----------|---------|
| Query | Dynamic from data source | All service names from Prometheus |
| Custom | Static option list | Environment: [prod, staging, dev] |
| Constant | Fixed value, hidden | Data center name |
| Interval | Time resolution step | Auto-calculate based on time range |
| Data Link | Link to specific values | Jump from service name to its dashboard |

**Chained variables**: Variables can depend on other variables.

```
$environment → $service → $endpoint
  prod       → payment-service → /payments, /refunds, /wallet
  staging    → payment-service → /payments
```

When the user selects `prod`, the `$service` dropdown only shows services in prod. Selecting `payment-service` then populates `$endpoint` with payment-service's endpoints in prod.

---

## 8.3 Dashboard Design

### 8.3.1 The SRE Dashboard Hierarchy

Google SRE defines a dashboard hierarchy from high-level to granular:

#### Level 1: Service Overview (Executive/Management)

```
┌─────────────────────────────────────────────────────────┐
│  Payment Service — Overview                              │
│                                                          │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │ Request    │  │ Error Rate │  │ P99 Latency      │  │
│  │ Rate       │  │            │  │                  │  │
│  │  1,234/s   │  │   0.12%    │  │    245ms         │  │
│  └────────────┘  └────────────┘  └──────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │              Error Budget Burn Rate                │   │
│  │  SLO: 99.9% | Current burn: 0.2x | Budget: 98.7%│   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  Show: Is the service healthy? (yes/no)                  │
│  Audience: Manager/VP asking "should I worry?"           │
└─────────────────────────────────────────────────────────┘
```

#### Level 2: Service RED Dashboard (Debugging)

```
┌─────────────────────────────────────────────────────────┐
│  Payment Service — RED Dashboard                         │
│                                                          │
│  ┌──────────────────────┐ ┌──────────────────────────┐  │
│  │ Request Rate by      │ │ Error Rate by Endpoint    │  │
│  │ Endpoint             │ │                          │  │
│  │ ██████ /payments     │ │ ██ /payments (0.2%)      │  │
│  │ ████   /wallet       │ │    /wallet (0%)          │  │
│  │ ██     /refunds      │ │    /refunds (0.1%)       │  │
│  └──────────────────────┘ └──────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │              P99 Latency by Endpoint               │   │
│  │  ═══ /payments (245ms)                             │   │
│  │  ─── /wallet (12ms)                               │   │
│  │  ··· /refunds (89ms)                              │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  Show: Which endpoint is degraded?                      │
│  Audience: On-call engineer investigating alert          │
└─────────────────────────────────────────────────────────┘
```

#### Level 3: Resource USE Dashboard (Infrastructure)

```
┌─────────────────────────────────────────────────────────┐
│  Payment Service — Resource Dashboard                    │
│                                                          │
│  CPU Util       Memory Util      Disk IO         Net     │
│  ▁▃▅▇███▇▅▃    ████████████     ▁▁▁▅▇██▇▅    ▃▅▇██▇▅  │
│    58%            72%             12 MB/s      45 Mbps   │
│                                                          │
│  JVM: Heap    JVM: Threads    DB Pool        Redis Pool  │
│  ████████     ▃▅▇███▇▅▃▁     ▇████████      ████████    │
│   4.2 GB        245 active     18/20 active    8/10 act  │
│                                                          │
│  Show: Is the infrastructure healthy?                   │
│  Audience: Platform/SRE team during capacity issues      │
└─────────────────────────────────────────────────────────┘
```

#### Level 4: Correlated Debug View (Incident Response)

```
┌─────────────────────────────────────────────────────────┐
│  Investigation: High Latency on /payments                │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │ Timelines: Alert | Deployment | Spike             │   │
│  │ ███████████▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │   │
│  │              ↑ Deploy v2.4.1                      │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────────┐ ┌───────────────────────┐  │
│  │ Representative Trace     │ │ Related Logs          │  │
│  │ (click to expand)        │ │ (from trace_id)       │  │
│  │                          │ │                       │  │
│  │ POST /payments  [2450ms] │ │ ERROR: conn timeout   │  │
│  │ ├─ Auth [17ms]          │ │ WARN: retry 3/5       │  │
│  │ ├─ Fraud [420ms]        │ │ INFO: payment started │  │
│  │ └─ Ledger [1980ms] ←!!  │ │                       │  │
│  │    └─ PG INSERT [1975ms]│ │                       │  │
│  └─────────────────────────┘ └───────────────────────┘  │
│                                                          │
│  Show: Jump between signals to find root cause           │
│  Audience: On-call during active incident                │
└─────────────────────────────────────────────────────────┘
```

### 8.3.2 Dashboard Design Principles

**1. Answer one question per dashboard.**
A dashboard titled "Payment Service" that shows metrics, traces, logs, deployments, and errors is a "wall of information." Split into focused dashboards: "RED Dashboard," "Resource Dashboard," "Error Budget Dashboard."

**2. Left to right, top to bottom importance.**
Most important information in the top-left. Eye naturally starts there. Error rate, latency, and request rate should be the first things visible.

**3. Use consistent color semantics.**
- Green = healthy (low error, good latency)
- Yellow = warning (approaching threshold)
- Red = critical (threshold breached)
- Blue = informational (rates, counts)
- Gray = neutral/no-data

**4. Show thresholds on graphs.**
A latency graph without an SLO line is a raw number. A latency graph with a horizontal SLO line at 500ms shows whether you're compliant.

**5. Use percentages, not absolute numbers.**
"47 errors" means nothing. "47 errors (0.47% of 10,000 requests)" means everything.

**6. Show change over time, not instant values.**
"Memory: 8.2 GB" is a data point. "Memory: 8.2 GB (↑15% in last hour)" is a signal.

---

## 8.4 Signal Correlation

### 8.4.1 The Metric → Trace → Log Jump

This is the most powerful workflow in Grafana:

```
Step 1: Metric anomaly detected
  Dashboard: P99 latency for /payments spiked from 200ms to 2s at 14:23

Step 2: Click the spike → "View exemplar traces"
  Prometheus saved an exemplar (representative trace) for the 14:23 spike
  
Step 3: Jaeger trace view opens
  Shows the trace waterfall: LedgerService.PG_INSERT took 1975ms
  
Step 4: Click the slow span → "View related logs"
  Opens OpenSearch logs filtered by trace_id=a1b2c3d4e5f6
  
Step 5: Root cause found
  Log shows: "FATAL: connection limit exceeded for database payments_db"
  Root cause: database connection pool saturation
  Fix: increase max_connections or add connection pooling
```

**How exemplars work (Prometheus → Grafana → Jaeger):**

A Prometheus histogram sample can record an exemplar — a specific observation that contributed to the bucket:

```
http_request_duration_seconds_bucket{le="2.5"} 12345
  # Exemplar: trace_id="a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", span_id="..."
```

When a high-latency value bumps the 2.5s bucket, the exemplar records which trace that was. Grafana reads exemplars and provides links from the metric spike to the specific trace.

### 8.4.2 Data Links — Custom Correlation

Data links create navigation paths between dashboards and data sources:

```yaml
# On a service name in a table panel, add a link to its RED dashboard
data_links:
  - title: "View RED Dashboard"
    url: "/d/red-dashboard?var-service=${__data.fields.service}"

# On a trace ID, add a link to Jaeger
  - title: "View Trace in Jaeger"
    url: "/explore?orgId=1&left=${__data.fields.trace_id}"
```

**Power user pattern**: A "Home" dashboard that shows all services with their health status (red/yellow/green). Clicking any service navigates to its detailed dashboard. Clicking any anomaly opens the correlated trace/log view.

---

## 8.5 Alerting in Grafana

Grafana Alerting (since v9) provides an alternative to Prometheus alert rules + Alertmanager:

```
Grafana Alerting Architecture:
  ┌────────────────────────────────┐
  │ Alert Rules (PromQL or DSL)    │
  │ Evaluate every N seconds       │
  └──────────┬─────────────────────┘
             │
             ▼
  ┌────────────────────────────────┐
  │ Grafana Alertmanager (built-in)│
  │ Route → Group → Silence → Send  │
  └────────────────────────────────┘
```

**When to use Grafana Alerting vs Prometheus Alertmanager:**

| | Grafana Alerting | Prometheus + Alertmanager |
|---|---|---|
| Data sources | Any Grafana data source | Prometheus only |
| Rule location | Grafana (UI or provisioning) | Config files (GitOps) |
| Complexity | Lower (UI-driven) | Higher (file-based) |
| Multi-tenancy | Better (Grafana orgs + roles) | Limited |
| GitOps | Weaker (provisioning is limited) | Stronger (config files) |
| Recommendation | Smaller teams, simpler setups | Infrastructure-as-code, large teams |

---

## 8.6 Common Misconceptions

### "Grafana stores dashboards and that's it"

Grafana also stores: users, teams, organizations, alert rules, alert state, API keys, and data source configurations. It uses its own PostgreSQL or SQLite database.

### "One dashboard per service is enough"

One dashboard tells one story. A single "Payment Service" dashboard cannot effectively show both the executive overview AND the detailed debug view. Design dashboards for specific questions and audiences.

### "Grafana is a monitoring tool"

Grafana is a visualization platform that QUERIES monitoring tools (Prometheus) and other data sources. It doesn't collect or store telemetry (except alert state).

### "The more panels, the better the dashboard"

A dashboard that requires scrolling is a failed design. The most important information should be visible without scrolling. If you have 50 panels, you have 5 dashboards trying to be one.

---

## Interview Questions — Phase 8

1. **How does Grafana achieve signal correlation between metrics, traces, and logs?**

   *Answer core points*: (1) Prometheus exemplars link histogram buckets to specific trace IDs, enabling metric→trace navigation. (2) Trace IDs embedded in log entries (via OTel log bridge) enable trace→log navigation. (3) Data links enable custom navigation paths. (4) Grafana's Explore view provides a split-pane interface for simultaneous PromQL + TraceQL + LogQL queries with linked time ranges.

2. **Design a dashboard hierarchy for a payment platform. What dashboards exist and who uses them?**

   *Answer core points*: Level 1: Service Overview (execs/PMs — is the platform healthy?). Level 2: RED Dashboard per service (on-call — which endpoint is broken?). Level 3: Resource USE Dashboard (SRE/platform — is infra healthy?). Level 4: Correlated Debug View (on-call during incident — metric→trace→log drill-down). Level 5: Business Dashboard (product — payment volume, revenue, conversion rate).

3. **What is an exemplar and how does it enable metric-to-trace correlation?**

   *Answer core points*: An exemplar is a specific data point recorded alongside a histogram bucket update, containing the trace_id and span_id of the request that contributed to that bucket. Prometheus stores exemplars. Grafana reads them and renders clickable links from metric spikes to the exact trace that caused the spike.

4. **How do Grafana variables make dashboards reusable? Give an example of chained variables.**

   *Answer core points*: Variables parameterize queries ($service, $endpoint). Chained variables cascade: selecting `$environment=prod` filters the `$service` dropdown to only prod services; selecting a service filters `$endpoint` accordingly. This allows a single dashboard template to serve 100 services.

5. **When would you use Grafana Alerting instead of Prometheus's built-in alert rules?**

   *Answer core points*: Grafana Alerting when you need: multi-data-source alert queries (e.g., Prometheus + OpenSearch), UI-driven alert management (non-GitOps teams), or built-in multi-tenancy (Grafana orgs). Prometheus rules when you need: GitOps, pure-Prometheus infrastructure, or tighter coupling to Prometheus recording rules.

---

**Next: Phase 9 — Local Development**
