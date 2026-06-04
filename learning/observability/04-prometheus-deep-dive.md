# Phase 4 — Prometheus Deep Dive

> **Duration**: 1-2 weeks | **Prerequisites**: Phases 1-3
>
> **Goal**: Understand Prometheus internals at the level of a database engineer — TSDB, PromQL, scaling strategies, production operations.

---

## 4.1 Why Metrics Need a Specialized Database

### 4.1.1 The Mismatch: General-Purpose DBs vs Time-Series

**Attempting to store metrics in PostgreSQL:**

```sql
CREATE TABLE metrics (
    metric_name TEXT,
    labels JSONB,
    value DOUBLE PRECISION,
    timestamp TIMESTAMPTZ
);

-- 1000 metrics × 10 labels × 100 instances × 1/min = 1.4M rows/day
-- After 30 days: 43M rows
-- Query: "p99 latency of /payments for the last 5 minutes"
-- Full table scan of 43M rows → minutes to execute
```

Relational databases are optimized for:
- Random access (point queries by primary key)
- Transactions (ACID)
- Joins, aggregations on complex schemas

Time-series workloads require:
- **Sequential writes** (append-only, time-ordered)
- **Range scans** (read 5 minutes of data, not 1 row)
- **Efficient compression** (repeated label values, predictable value patterns)
- **No transactions** (eventual consistency is fine for monitoring data)

### 4.1.2 What a Time-Series Database (TSDB) Optimizes For

```
Write pattern:  New data appended to the end of time series
Read pattern:   Range scans over recent data, aggregation over ranges
Compression:    Labels repeated across millions of points; values change slowly
Retention:      Old data is less valuable → downsampled or deleted
```

---

## 4.2 Prometheus Architecture

### 4.2.1 Core Components

```
┌─────────────────────────────────────────────────────┐
│                 Prometheus Server                    │
│                                                      │
│  ┌──────────────┐   ┌───────────────┐               │
│  │  Scraper     │   │   PromQL      │               │
│  │  (pull HTTP) │   │   (query)     │               │
│  └──────┬───────┘   └───────┬───────┘               │
│         │                   │                        │
│  ┌──────▼───────────────────▼───────┐               │
│  │           TSDB                    │               │
│  │   ┌─────┐  ┌──────┐  ┌───────┐  │               │
│  │   │ WAL │→ │ Mem  │→ │ Disk  │  │               │
│  │   └─────┘  │Series│  │Blocks │  │               │
│  │            └──────┘  └───────┘  │               │
│  └─────────────────────────────────┘               │
│                                                      │
│  ┌──────────────┐   ┌──────────────┐                │
│  │Alert Rules   │   │Remote Storage│                │
│  │(periodic eval)│   │(read/write) │                │
│  └──────┬───────┘   └──────────────┘                │
│         │                                            │
│    Alertmanager                                      │
└─────────────────────────────────────────────────────┘
```

### 4.2.2 The Pull Model

Prometheus scrapes (pulls) metrics from targets via HTTP:

```
Prometheus Scraper
    │
    ├── GET http://payment-service:8080/metrics  (every 15s)
    ├── GET http://auth-service:8080/metrics     (every 15s)
    ├── GET http://postgres-exporter:9187/metrics (every 30s)
    ├── GET http://redis-exporter:9121/metrics   (every 30s)
    └── GET http://node-exporter:9100/metrics    (every 15s)
```

**Why pull instead of push:**

| Aspect | Pull (Prometheus) | Push (Graphite, InfluxDB) |
|--------|-------------------|---------------------------|
| **Health check** | Scrape failure = service is down (implied) | Separate health check needed |
| **Simplicity** | Service just exposes HTTP endpoint | Service must know where to push |
| **Rate control** | Prometheus controls scrape interval | Service controls push rate |
| **Discovery** | Prometheus discovers targets (k8s, consul, DNS) | Service must know server address |
| **Ephemeral jobs** | Prometheus can't pull (pod already dead) | Pushgateway exists for this |

**The pull model's limitation and Pushgateway:**

Short-lived jobs (batch jobs, cron jobs, serverless functions) cannot be scraped — they exit before Prometheus can pull.

```
[Batch Job] → Push → [Pushgateway] ← Scrape ← [Prometheus]
```

The Pushgateway is a temporary cache for ephemeral job metrics. **It is NOT a general push receiver.** Data in the Pushgateway persists even after the job is gone, which can cause stale metrics if not managed.

**Production rule**: Only use Pushgateway for ephemeral batch jobs. For long-running services, use the pull model.

### 4.2.3 Service Discovery

Prometheus discovers targets dynamically, not from a static list.

```yaml
scrape_configs:
  # Kubernetes pod discovery
  - job_name: 'kubernetes-pods'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      # Only scrape pods with annotation: prometheus.io/scrape: "true"
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
```

**Discovery mechanisms:**
- `kubernetes_sd_configs`: Pods, Services, Endpoints, Nodes, Ingresses
- `consul_sd_configs`: Consul service registry
- `dns_sd_configs`: DNS SRV records
- `ec2_sd_configs`: AWS EC2 instances
- `file_sd_configs`: JSON/YAML files (static, good for on-prem)

**The relabeling flow:**

```
Discovered target
  → relabel_configs (filter/modify before scrape)
    → scrape target
      → metric_relabel_configs (filter/modify metrics after scrape)
        → ingest into TSDB
```

**Critical `metric_relabel_configs` pattern — drop high-cardinality metrics:**

```yaml
metric_relabel_configs:
  # Drop metrics with high cardinality labels
  - source_labels: [__name__]
    regex: 'http_request_duration_seconds_bucket'
    action: keep
```

---

## 4.3 TSDB Internals

### 4.3.1 The Write Path

```
HTTP scrape response
    │
    ▼
[Parse: prometheus text format → time series]
    │
    ▼
[Append to Head (in-memory)]
    │
    ├──→ WAL (Write-Ahead Log) ← durability
    │
    ▼
[Head compaction: every 2 hours]
    │
    ▼
[Block (on-disk, immutable)]
```

### 4.3.2 Write-Ahead Log (WAL)

**Purpose**: Durability. If Prometheus crashes, the WAL replays and recovers data that hadn't been persisted to disk blocks yet.

```
Format:
  ┌─────────────────┐
  │ WAL Segment 000 │  (128 MiB max per segment)
  │ ├── Record 1     │  Series entry: {name, labels}
  │ ├── Sample 1     │  Data point: {series_ref, timestamp, value}
  │ ├── Sample 2     │
  │ ├── Record 2     │  New series entry
  │ ├── Sample 3     │
  │ └── ...          │
  ├─────────────────┤
  │ WAL Segment 001 │
  └─────────────────┘
```

**WAL checkpointing**: When a block is persisted to disk, the corresponding WAL segments are no longer needed for durability. Prometheus deletes old WAL segments (checkpointing). This prevents infinite WAL growth.

**Recovery on restart**: Prometheus reads the WAL from the last checkpoint, reconstructs the in-memory index, and resumes.

### 4.3.3 In-Memory (Head)

The Head is where all recent data lives. It's the active write buffer.

```
Head Structure:
  ┌─────────────────────────────────────────┐
  │  Series Map (hash map)                   │
  │  series_ref → {labels, chunks}           │
  │                                          │
  │  Series 1: {labels, [Chunk1, Chunk2]}   │
  │  Series 2: {labels, [Chunk1]}            │
  │  ...                                     │
  │  Series N: {labels, [Chunk1]}            │
  │                                          │
  │  Inverted Index (label → series_refs)    │
  │  "job=payment-service" → [1, 47, 892]   │
  │  "status=500" → [47, 892]               │
  │  "endpoint=/payments" → [892]            │
  └─────────────────────────────────────────┘
```

**Why the inverted index**: Queries like `http_requests_total{status="500"}` need to find ALL series with `status="500"`. Without the inverted index, this is a linear scan of all series. With it, it's a direct lookup.

**Chunks**: Within each series, data points are stored in chunks (compressed time-value pairs).

```
Chunk encoding (XOR compression):
  timestamp: delta-of-delta (usually 15s scrape interval → delta = 15000ms → constant → 0)
  value:     XOR with previous value (similar values → many zero bits)

Result: 120 samples (2 hours at 1/min) → ~200 bytes (vs 120×16 = 1920 bytes uncompressed)
```

### 4.3.4 On-Disk Blocks

Every 2 hours, the Head is compacted into an immutable block:

```
data/
├── 01H3A2B4C5D6E7F8/       ← Block (2-hour time range)
│   ├── chunks/               ← Raw sample data (compressed chunks)
│   │   └── 000001
│   ├── index                  ← Inverted index for this block
│   ├── meta.json              ← Block metadata (time range, stats)
│   └── tombstones             ← Deleted series markers
├── 01H3A4B5C6D7E8F9/       ← Next block
│   └── ...
└── wal/
    └── ...
```

**Why immutable blocks**: Once written, a block never changes. This enables:
- **Zero-copy queries**: Read from mmap'd files, no deserialization
- **Efficient compression**: Immutable data can be compressed more aggressively
- **Safe deletion**: Delete entire blocks for retention, no fragmentation

**Block compaction**: Older blocks are merged into larger blocks over time.

```
Original:  [2h][2h][2h][2h][2h][2h][2h][2h]  (8 blocks × 2h)
Level 1:   [8h][8h][8h][8h]                    (4 blocks × 8h)
Level 2:   [32h][32h]                           (2 blocks × 32h)
Final:     [64h]                                  (1 block × 64h)

After retention (e.g., 30 days): delete blocks entirely outside the window.
```

### 4.3.5 Time Series Identity

A time series is identified by its **metric name + sorted label set**:

```
up{job="payment-service", instance="10.0.1.5:8080"} = 1
up{job="payment-service", instance="10.0.1.6:8080"} = 1
up{job="payment-service", instance="10.0.1.7:8080"} = 0  ← different series
```

**Series churn**: Every time a label combination appears for the first time, a new series is created. This is expensive — it allocates memory, writes a WAL entry, and creates index entries.

**Why series churn is dangerous**: If a label has high cardinality and changes frequently (e.g., pod name in a rolling deployment), each new pod creates new series. Over time, memory grows unbounded (old series are only removed when their time range falls outside the retention window).

---

## 4.4 PromQL

### 4.4.1 The Query Language

PromQL (Prometheus Query Language) is a functional query language for selecting and aggregating time series.

### 4.4.2 Instant Vector vs Range Vector

**Instant Vector**: A set of time series, each with a single sample at the query time.

```promql
http_requests_total{job="payment-service"}
# Result (at time T):
# {endpoint="/payments", status="200"} 123456
# {endpoint="/payments", status="500"} 47
# {endpoint="/wallet", status="200"} 89231
```

**Range Vector**: A set of time series, each with a range of samples over a time window.

```promql
http_requests_total{job="payment-service"}[5m]
# Result (5 minutes of data at scrape interval):
# {endpoint="/payments", status="200"} [123400@T-5m, 123410@T-4m45s, 123420@T-4m30s, ...]
```

### 4.4.3 Essential Functions

#### rate() — Per-second rate of increase

```promql
rate(http_requests_total{job="payment-service"}[5m])
```

Internally: Takes the first and last data point in the range, divides by the time difference. Handles counter resets.

**Why [5m]**: The range vector provides enough data points for a reliable rate calculation. Too short (30s) → noisy. Too long (1h) → slow to react.

#### increase() — Total increase over a range

```promql
increase(http_requests_total{job="payment-service"}[1h])
# How many requests in the last hour?
```

Internally: Similar to `rate() × range_seconds`.

#### histogram_quantile() — Quantile from histogram

```promql
# p99 latency over 5 minutes
histogram_quantile(0.99,
  rate(http_request_duration_seconds_bucket{job="payment-service"}[5m])
)
```

Internally: Sorts all buckets, interpolates between them to find the value at the requested percentile.

**How bucket selection affects p99 accuracy:**

```
Buckets: {0.1, 0.5, 1, 5}
99% of requests fall in [0.5, 1] bucket → p99 ≈ 0.75 (interpolated) → reasonable

Buckets: {0.1, 0.5, 1}
99% of requests fall in [0.5, 1] → p99 ≈ 0.75 → same

Buckets: {0.1, 0.5}
99% of requests fall in [0.5, +Inf] → p99 = 0.5 (last finite bucket) → WRONG
```

**The last bucket problem**: If your p99 is above the highest finite bucket, `histogram_quantile` falls back to the last finite bucket. Your p99 measurement is WRONG. Always ensure your highest bucket covers your SLO threshold.

#### avg_over_time() / max_over_time() — Aggregate over range

```promql
avg_over_time(node_cpu_seconds_total[5m])  # Average CPU over 5 minutes
max_over_time(node_memory_used_bytes[1h])   # Peak memory in last hour
```

### 4.4.4 Aggregation Operators

```promql
# Total requests per second across ALL instances of payment-service
sum(rate(http_requests_total{job="payment-service"}[5m]))

# Per-endpoint breakdown
sum by (endpoint) (rate(http_requests_total{job="payment-service"}[5m]))

# Error rate as percentage
sum(rate(http_requests_total{status=~"5.."}[5m]))
  /
sum(rate(http_requests_total[5m]))
  * 100

# Per-instance error rate (but only if > 1%)
sum by (instance) (rate(http_requests_total{status=~"5.."}[5m]))
  /
sum by (instance) (rate(http_requests_total[5m]))
  > 0.01
```

### 4.4.5 PromQL Execution Internals

When you write `sum(rate(http_requests_total[5m]))`:

1. **Parse**: Build AST (Abstract Syntax Tree)
2. **Select**: Find all series matching `http_requests_total` from the inverted index
3. **Range**: For each series, read 5 minutes of data (in-memory for recent, disk for older)
4. **rate()**: For each series, compute per-second rate
5. **sum()**: Aggregate all rate values into a single number
6. **Evaluate**: Graph this over the query time range

**Step 3 performance**: If you query "last 6 hours" but retention is 30 days, Prometheus only reads blocks that overlap with [now-6h, now]. It does NOT scan all blocks.

---

## 4.5 Recording Rules

Recording rules pre-compute and store commonly used queries.

```yaml
groups:
  - name: payment-service-rules
    interval: 60s
    rules:
      - record: job:http_requests_total:rate5m
        expr: sum(rate(http_requests_total[5m])) by (job)

      - record: job:http_request_duration:p99
        expr: histogram_quantile(0.99,
                sum(rate(http_request_duration_seconds_bucket[5m])) by (job, le))

      - record: instance:http_errors:rate5m
        expr: rate(http_requests_total{status=~"5.."}[5m])
```

**Why recording rules:**

1. **Performance**: `histogram_quantile(0.99, rate(...[5m]))` is expensive to compute on every dashboard refresh. Pre-compute it every 60s and read the result instantly.

2. **Historical aggregation**: Dashboards that show "last 30 days" with 1-hour granularity don't need raw data. Pre-aggregate to hourly and query the pre-aggregated series.

3. **Consistency**: Multiple dashboards using the same query → compute once, read many times.

**Recording rule naming convention:**

```
level:metric:operation
job:http_requests_total:rate5m
instance:http_errors:rate5m
cluster:node_cpu_utilization:avg
```

---

## 4.6 Alert Rules

```yaml
groups:
  - name: payment-alerts
    rules:
      - alert: HighErrorRate
        expr: |
          sum(rate(http_requests_total{job="payment-service", status=~"5.."}[5m]))
            /
          sum(rate(http_requests_total{job="payment-service"}[5m]))
          > 0.05
        for: 5m
        labels:
          severity: critical
          team: payments
        annotations:
          summary: "Payment service error rate > 5%"
          description: "Error rate is {{ $value | humanizePercentage }} for the last 5 minutes"
          runbook: https://wiki.example.com/runbooks/payment-high-error-rate

      - alert: HighP99Latency
        expr: |
          histogram_quantile(0.99,
            sum(rate(http_request_duration_seconds_bucket{
              job="payment-service",
              endpoint="/payments"
            }[5m])) by (le)
          ) > 2
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Payment service p99 latency > 2s"
          runbook: https://wiki.example.com/runbooks/payment-high-latency
```

**`for: 5m`**: The condition must be true for 5 continuous minutes before the alert fires. This prevents flapping (momentary spikes that self-resolve).

**Alert state machine:**

```
Inactive → (expr true) → Pending → (expr true for 5m) → Firing → (expr false for 15m) → Inactive
```

**Why the `for` clause**: Without it, every transient blip triggers an alert. With it, only sustained problems alert.

**`{{ $value }}`**: Template variable containing the current value of the alert expression.

---

## 4.7 Cardinality Management

### 4.7.1 What Is Cardinality

Cardinality = number of unique time series in a Prometheus instance.

```
Unique time series = Σ (unique_label_combinations_for_each_metric)
```

### 4.7.2 Why High Cardinality Kills Prometheus

Each time series consumes:
- **Memory**: 1-10 KB (labels, inverted index entries, chunk data in head)
- **Disk**: WAL writes, block storage
- **CPU**: Scrape parsing, compaction, query evaluation

```
Cardinality → Memory → Performance
100,000 series   → ~1 GB   → Fast
1,000,000 series → ~10 GB  → Noticeable slowdown
10,000,000 series→ ~100 GB → Very slow, OOM risk
```

### 4.7.3 Common Cardinality Explosions

| Mistake | Cardinality Impact | Fix |
|---------|-------------------|-----|
| `user_id` as metric label | × number of users (millions) | Log user_id, don't label it |
| `request_id` as metric label | × every request (infinite) | Request ID is a trace/long attribute, not a label |
| `pod_name` with rolling deployments | × pods created/deleted over time | Use `k8s_cluster` label, not pod_name |
| `path` with path parameters (`/users/123`) | × unique path values | Use `route` param (`/users/:id`) |

**Detecting cardinality problems:**

```promql
# Top 10 metrics by series count
topk(10, count by (__name__)({__name__=~".+"}))

# Total series count
count({__name__=~".+"})
```

**Production cardinality budget:**
- "Low traffic" (< 500k series): 1 Prometheus per cluster
- "Medium" (500k - 2M series): 1 Prometheus per cluster, careful label management
- "High" (> 2M series): Shard by label (e.g., separate Prometheus per service) or use Thanos/Cortex/Mimir

---

## 4.8 Scaling Prometheus

### 4.8.1 Vertical Scaling (Bigger Machine)

**Works up to ~10M active series.** Add more CPU, RAM, SSD.

**Limitation**: Single point of failure. If this machine dies, all monitoring is blind.

### 4.8.2 Federation

```
Global Prometheus (aggregates)
    ↓ scrape /federate
    ├── DC1 Prometheus
    ├── DC2 Prometheus
    └── DC3 Prometheus
```

```yaml
# Global Prometheus scrape config
scrape_configs:
  - job_name: 'federate-dc1'
    honor_labels: true
    metrics_path: '/federate'
    params:
      'match[]':
        - '{__name__=~"job:.*"}'  # Only scrape recording rules
    static_configs:
      - targets:
        - 'prometheus-dc1:9090'
```

**Federation pulls aggregates**, not raw data. DC Prometheus stores raw 15s data. Global Prometheus pulls 60s aggregated recording rules.

**Good for**: Multi-DC, hierarchical org structure.

**Bad for**: Global queries over raw data (each DC's raw data stays local).

### 4.8.3 Thanos

Thanos extends Prometheus with:
- **Global query view**: Query across multiple Prometheus instances
- **Unlimited retention**: Offload old blocks to object storage (S3)
- **High availability**: Duplicate Prometheus instances with dedup
- **Downsampling**: Automatically create 5m and 1h resolution data

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Prometheus A │  │ Prometheus B │  │ Prometheus C │
│  (DC1, HA)   │  │  (DC2, HA)   │  │  (DC3, HA)   │
└───┬────┬─────┘  └───┬────┬─────┘  └───┬────┬─────┘
    │    │             │    │             │    │
    │   Sidecar       │   Sidecar       │   Sidecar
    │    │             │    │             │    │
    ▼    ▼             ▼    ▼             ▼    ▼
  ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ S3 Bucket│     │ S3 Bucket│     │ S3 Bucket│
  └────┬─────┘     └────┬─────┘     └────┬─────┘
       │                 │                 │
       └─────────────────┼─────────────────┘
                         │
                  ┌──────▼──────┐
                  │ Thanos Query│  ← Global PromQL with dedup
                  └─────────────┘
```

**When Thanos is the right choice:**
- Multiple Prometheus instances
- Need > 30 days retention without huge local disks
- Need global query view across clusters
- Don't want a full distributed TSDB (Cortex/Mimir)

### 4.8.4 Cortex / Mimir

Horizontally-scalable, clustered Prometheus. Stores all data in object storage (S3/GCS).

```
Write path:
  Services → Distributor (hash by labels → consistent hash ring)
             → Ingester (in-memory + WAL, periodic flush to S3)
             → S3 (long-term blocks)

Read path:
  Querier ← S3 (long-term blocks)
          ← Ingester (recent data)
  → Query Frontend (cache, split, parallelize)
```

**When Cortex/Mimir is the right choice:**
- Single, massive Prometheus deployment (10M+ active series)
- Managed service (Grafana Cloud, AWS Managed Prometheus)
- Multi-tenancy required
- Don't want to manage individual Prometheus instances

**Trade-off**: Operational complexity. Running Cortex/Mimir is significantly harder than running a few Prometheus instances. Only adopt when you've outgrown a few Prometheus servers.

---

## 4.9 Common Misconceptions

### "Prometheus is a monitoring tool"

Prometheus is a time-series database with a query language and alerting engine. The "monitoring tool" is Grafana (visualization) + Alertmanager (notification). Prometheus provides the metric storage and computation layer.

### "Prometheus can handle any cardinality"

Prometheus is a single-node TSDB (by design). It handles up to ~10M active series per instance. Beyond that, you need Thanos/Cortex/sharding.

### "Summaries are better than histograms because they pre-compute quantiles"

Summaries prevent cross-instance aggregation. Three instances each reporting p99=100ms do NOT mean the combined p99 is 100ms. Histograms solve this: `histogram_quantile(0.99, sum(rate(...)))`.

### "You should always set a long retention in Prometheus"

Prometheus local storage is for operational queries (last 7-30 days). For longer retention, use Thanos (S3 offload) or remote write to Cortex/Mimir. Long retention on local disk = huge disk costs + slow queries.

---

## Interview Questions — Phase 4

1. **Why does Prometheus use a pull model? What are the trade-offs?**

   *Answer core points*: Pull means Prometheus controls scrape timing, service health is implied by scrape success, and services don't need to know Prometheus's address. Trade-off: short-lived jobs need Pushgateway; firewalled targets may need explicit network access.

2. **Explain Prometheus's write path: from HTTP scrape response to on-disk block.**

   *Answer core points*: Scrape response is parsed into time series. Data is appended to the Head (in-memory) and written to WAL for durability. Every 2 hours, the Head is compacted into an immutable on-disk block. Old blocks are merged over time. Retention deletes entire blocks outside the time window.

3. **What is the difference between `rate()` and `increase()`? When does `rate()` handle counter resets?**

   *Answer core points*: `rate()` computes per-second increase over a range. `increase()` computes total increase. Both handle counter resets by detecting when the counter value drops (assumes reset to 0) and extrapolating. `rate()` is typically used for dashboards; `increase()` for absolute change queries.

4. **Why is cardinality dangerous in Prometheus? Give a real example of a cardinality explosion.**

   *Answer core points*: Each unique label combination creates a time series consuming memory. Adding `user_id` as a label with 1M users creates 1M time series per metric. Memory explodes. Example: `http_requests_total{user_id="..."}` with millions of users = Prometheus OOM.

5. **Compare the scaling approaches: federation vs Thanos vs Cortex.**

   *Answer core points*: Federation = hierarchical Promethei, each stores own data, global scrapes aggregates. Thanos = Promethei + S3 offload + global query with dedup, unlimited retention. Cortex = fully distributed, horizontally-scalable, all data in S3, operationally complex. Choose based on scale and ops complexity tolerance.

6. **How does `histogram_quantile` work? What happens if all your buckets are below the actual p99?**

   *Answer core points*: Interpolates between bucket boundaries to estimate the quantile. If actual p99 exceeds the highest finite bucket, `histogram_quantile` returns the last finite bucket value — the result is incorrect. Always ensure the highest bucket exceeds your SLO.

---

**Next: Phase 5 — Jaeger Deep Dive**
