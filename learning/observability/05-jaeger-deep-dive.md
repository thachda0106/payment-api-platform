# Phase 5 — Jaeger Deep Dive

> **Duration**: 1 week | **Prerequisites**: Phases 1-4
>
> **Goal**: Understand distributed tracing storage internals, how Jaeger stores and queries spans at scale, and production deployment.

---

## 5.1 The Distributed Tracing Storage Problem

### 5.1.1 What Makes Tracing Storage Different

Tracing storage must handle workloads that traditional databases are not optimized for:

| Characteristic | Tracing Workload | Traditional DB (PostgreSQL) |
|---------------|------------------|----------------------------|
| **Write pattern** | Append-only, massive throughput | Mixed read/write, lower throughput |
| **Write volume** | Millions of spans/minute | Thousands of transactions/minute |
| **Query pattern** | Search by trace_id (point query), Search by attribute + time range | Primary key lookup, JOINs |
| **Data lifetime** | Hours to days (detailed), weeks (sampled) | Years |
| **Data shape** | Nested (span tree within trace), variable attributes | Flat rows, fixed schema |
| **Retention** | Time-based deletion, TTL | Application-level archiving |

### 5.1.2 The Core Data Structures

A **span** in Jaeger:

```
Span {
    trace_id:  "a1b2c3d4e5f6a1b2"  (16 bytes, high cardinality)
    span_id:   "1a2b3c4d5e6f1a2b"  (8 bytes)
    operation: "POST /payments"    (variable string)
    start_time: 1705310605123456789 (uint64, unix nanos)
    duration:   2450000000          (uint64, nanos)
    tags: {                         (key-value pairs)
        "http.method": "POST",
        "http.status_code": 201,
        "service.name": "payment-service"
    }
    references: [                   (span relationships)
        {ref_type: CHILD_OF, trace_id: ..., span_id: ...}
    ]
    process: {
        service_name: "payment-service",
        tags: {...}
    }
}
```

**Critical insight**: Spans within a trace form a tree (or DAG). Queries almost always retrieve the ENTIRE trace, not individual spans. This impacts storage design: spans from the same trace should be stored together.

---

## 5.2 Jaeger Architecture

### 5.2.1 Components

```
┌──────────────────────────────────────────────────────┐
│                     Jaeger Components                 │
│                                                       │
│  ┌──────────┐     ┌──────────┐     ┌──────────────┐ │
│  │  Agent   │────→│ Collector│────→│   Storage    │ │
│  │ (client) │     │ (server) │     │ (persistence)│ │
│  └──────────┘     └──────────┘     └──────┬───────┘ │
│                                           │          │
│                                     ┌─────▼──────┐  │
│                                     │   Query    │  │
│                                     │ (read API) │  │
│                                     └────────────┘  │
└──────────────────────────────────────────────────────┘
```

### 5.2.2 Agent

The Jaeger Agent is a network proxy that runs on every host (DaemonSet).

```
Service (app) → UDP/Thrift → Jaeger Agent → gRPC/Thrift → Jaeger Collector
```

**Why UDP**: Minimizes overhead to the application. If the agent is slow, UDP drops the packet rather than blocking the application thread.

**Why not use the Agent anymore**: With OpenTelemetry, services send OTLP (gRPC/HTTP) directly to the Collector. The Agent is a legacy component for pre-OpenTelemetry Jaeger clients.

**Modern replacement**: OTel Collector (DaemonSet) receives OTLP and forwards to Jaeger Collector.

### 5.2.3 Collector

The Collector validates, indexes, and stores spans.

```
Collector responsibilities:
1. Receive spans (OTLP, Thrift, Zipkin formats)
2. Validate (required fields, format correctness)
3. Enrich (add service name, detected IP)
4. Apply sampling strategies
5. Queue (Kafka for buffering in large deployments)
6. Write to storage backend
```

**Internal processing pipeline:**

```
Receive → Validate → Pre-process → [Kafka Queue] → Store
```

**Why Kafka in the middle (large deployments):**

```
Collectors (stateless) → Kafka → Ingestors (write to storage)
```

Kafka decouples ingestion from storage. If the storage backend is slow (Elasticsearch indexing lag), spans accumulate in Kafka instead of backpressuring service SDKs. Collectors are stateless and can be scaled independently.

### 5.2.4 Query Service

The Query Service provides the read API for the Jaeger UI.

```
Jaeger UI → Query Service → Storage Backend
```

```json
// POST /api/traces?service=payment-service&operation=POST%20%2Fpayments&tags=%7B%22error%22%3A%22true%22%7D&start=1705310000000000&end=1705313600000000
{
  "data": [
    {
      "traceID": "a1b2c3d4e5f6a1b2",
      "spans": [...],
      "processes": {...}
    }
  ]
}
```

**Query flow:**
1. UI sends search criteria (service, operation, tags, time range)
2. Query Service translates to storage backend query
3. Storage returns matching trace IDs
4. Query Service fetches full traces by trace ID
5. UI renders the trace waterfall view

### 5.2.5 Storage Backend

Jaeger supports pluggable storage backends:

| Backend | Best For | Limitations |
|---------|----------|-------------|
| **In-Memory** | Development/testing | Data lost on restart |
| **Badger** | Single-node, low volume | Not distributed |
| **Cassandra** | High write throughput | Complex operations |
| **Elasticsearch** | Rich search, logs+trace in one | Heavy resource usage |
| **OpenSearch** | AWS managed, ES-compatible | Same as Elasticsearch |
| **ClickHouse** | Columnar, fast aggregations | Newer support |
| **S3 (via Tempo)** | Cheap, unlimited retention | Grafana Tempo, not Jaeger |

---

## 5.3 Storage Internals: Elasticsearch/OpenSearch

### 5.3.1 Span Indexing Strategy

Spans are stored in daily (or hourly) indices:

```
jaeger-span-2024-01-15
jaeger-span-2024-01-16
jaeger-service-2024-01-15
jaeger-dependencies-2024-01-15
```

**Why daily indices**: Enables efficient time-based deletion (drop entire index). Aligns with Elasticsearch's ILM (Index Lifecycle Management).

### 5.3.2 Span Document Structure

```json
{
  "_index": "jaeger-span-2024-01-15",
  "_id": "a1b2c3d4e5f6a1b2",
  "_source": {
    "traceID": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
    "spanID": "1a2b3c4d5e6f1a2b",
    "operationName": "POST /payments",
    "startTime": 1705310605123456,
    "startTimeMillis": 1705310605123,
    "duration": 2450000,
    "flags": 1,
    "process": {
      "serviceName": "payment-service",
      "tags": [
        {"key": "hostname", "type": "string", "value": "pod-abc123"},
        {"key": "ip", "type": "string", "value": "10.0.3.42"}
      ]
    },
    "references": [
      {"refType": "CHILD_OF", "traceID": "...", "spanID": "..."}
    ],
    "tags": [
      {"key": "http.method", "type": "string", "value": "POST"},
      {"key": "http.status_code", "type": "int64", "value": 201},
      {"key": "sampler.type", "type": "string", "value": "probabilistic"}
    ]
  }
}
```

**Why `startTimeMillis` exists**: Elasticsearch `date` type works with milliseconds. `startTime` (microseconds) enables precise duration calculation.

**Why the nested `tags` array structure**: Elasticsearch flattens JSON into keys. `tags.key` becomes a multi-field that can be queried: `tags.key=error AND tags.value=true`.

### 5.3.3 The Nested Tags Problem

Tags stored as:

```json
"tags": [
  {"key": "error", "type": "bool", "value": true},
  {"key": "http.method", "type": "string", "value": "POST"},
  {"key": "http.status_code", "type": "int64", "value": 500}
]
```

**Problem**: Elasticsearch flattens this, losing the association between key and value. Querying `tags.key=error AND tags.value=500` would match a span with `error=true` AND `http.status_code=500` (different tag, same document — but context lost).

**Solution**: Nested objects (Elasticsearch nested type):

```json
"tag": {
  "type": "nested",
  "properties": {
    "key": {"type": "keyword"},
    "value": {"type": "keyword"},
    "type": {"type": "keyword"}
  }
}
```

Nested queries preserve the key-value association within each nested object.

### 5.3.4 Trace Retrieval Pattern

Jaeger stores all spans of a trace in the same index. When querying:

```
Step 1: Search for all spans with traceID="a1b2c3d4e5f6a1b2"
        → Elasticsearch: GET jaeger-span-2024-01-15/_search
          {query: {term: {traceID: "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"}}}
        → Returns N span documents

Step 2: Reassemble the trace tree in memory
        → Sort spans by startTime
        → Build parent-child relationships from references
        → Return complete trace to UI
```

**Important**: Step 1 does a term lookup on `traceID`, which is indexed as a keyword. This is fast (inverted index). Step 2 is in-memory processing — the bottleneck is NOT Elasticsearch query time but span count per trace (500 spans = 500 documents to fetch and reassemble).

---

## 5.4 Sampling Strategies in Depth

### 5.4.1 The Sampling Decision Flow

```
Span arrives at Collector
    ↓
Is there an explicit sampling strategy for this service+operation?
    ├── YES → Apply that strategy
    └── NO  → Use default strategy
    ↓
Strategy evaluation:
    ├── Probabilistic: random() < samplingRate?
    ├── Rate Limiting: spans_this_second < max_spans_per_second?
    └── Guaranteed Throughput: probabilistic + minimum rate
    ↓
sampling.priority tag set (0 = drop, > 0 = keep)
    ↓
Span stored or dropped
```

### 5.4.2 Probabilistic Sampling

```json
{
  "service": "payment-service",
  "type": "probabilistic",
  "param": 0.1
}
```

Every span has a 10% chance of being kept.

**Problem**: In a trace with 10 services, each service independently samples at 10%. The probability of a COMPLETE trace is `0.1^10 = 0.0000000001`. Most stored traces are incomplete — missing intermediate spans.

**Solution**: Head-based sampling in the OTel SDK. The root span makes the decision and propagates it. Child spans respect the parent's decision (`ParentBased` sampler). Traces are either complete (sampled) or absent (not sampled).

### 5.4.3 Rate Limiting

```json
{
  "service": "payment-service",
  "type": "rate_limiting",
  "param": 100
}
```

Maximum 100 spans per second for this service.

**When to use**: Protect Jaeger from a noisy service. High-throughput but low-value services (health checks, metrics endpoints) should be rate-limited.

### 5.4.4 Guaranteed Throughput (Adaptive)

```json
{
  "service": "payment-service",
  "type": "probabilistic",
  "param": 0.1,
  "lower_bound_per_second": 10
}
```

Probabilistic 10%, BUT guarantee at least 10 traces per second. If traffic is low (< 100/s), all traces are kept. As traffic increases, probabilistic sampling takes over.

**This is the most production-appropriate strategy**: It ensures low-traffic services still generate traces (debugging rare failures) while protecting Jaeger from high-throughput services.

---

## 5.5 Performance at Scale

### 5.5.1 The Span Volume Problem

| Scale | Spans/second | Spans/minute | Storage/day |
|-------|-------------|-------------|-------------|
| 10 services | 1,000 | 60,000 | ~50 GB |
| 50 services | 5,000 | 300,000 | ~250 GB |
| 100 services | 20,000 | 1,200,000 | ~1 TB |
| 500 services | 100,000 | 6,000,000 | ~5 TB |
| 1000 services | 500,000 | 30,000,000 | ~25 TB |

*Assumes avg 2KB per span, 10% sampling, 7 day retention*

### 5.5.2 Elasticsearch Tuning for Jaeger

**Index design:**

```
# of shards per index = write_throughput / single_shard_capacity

Single shard can handle ~20,000 docs/sec
At 20,000 spans/sec → 1 primary shard
At 100,000 spans/sec → 5 primary shards
```

**Refresh interval** (how often new data becomes searchable):

```
Default: 1s
Jaeger: 5s-10s  (traces don't need instant searchability; reduce index overhead)
```

**Replication**:

```
# of replicas = hot_spare requirement
1 replica: tolerate 1 node loss
2 replicas: tolerate 2 node loss
Trade-off: more replicas = more disk, more network, faster reads
```

**Force merge for old indices** (reduce segment count):

```
Indices older than 1 day: force merge to 1 segment
Reduces memory (fewer file handles) at the cost of I/O during merge
```

### 5.5.3 Cassandra Tuning for Jaeger

Cassandra is preferred for VERY high write throughput with simple query patterns.

**Table schema (simplified):**

```sql
CREATE TABLE traces (
    trace_id blob,
    span_id bigint,
    -- ... span fields
    PRIMARY KEY (trace_id, span_id)
);
```

**Why Cassandra works for tracing:**
- Write-heavy: Cassandra's LSM-tree storage engine optimizes for writes
- Trace lookup by trace_id: Primary key lookup is O(1)
- Time-based TTL: `WITH default_time_to_live = 604800` (7 days)

**Why Cassandra fails for complex queries:**
- "Find all spans with error=true in the last 15 minutes" → full table scan
- No inverted index on tags → can't search by attributes
- Counter-intuitive data modeling (design tables by query pattern, not entity)

**When to use Cassandra vs Elasticsearch:**

| | Cassandra | Elasticsearch |
|---|---|---|
| Write throughput | Excellent | Good |
| Trace ID lookup | Excellent | Good |
| Attribute search | Poor (no inverted index) | Excellent |
| Operational complexity | High | Medium |
| Recommended for | Very high volume, simple queries | Most use cases |

---

## 5.6 Production Architecture

### 5.6.1 Small Scale (up to 5,000 spans/sec)

```
OTel Collector (DaemonSet) → Jaeger All-in-One (memory/disk) → Jaeger UI
```

Single Jaeger instance with in-memory or Badger storage. No separate components.

### 5.6.2 Medium Scale (5,000 - 50,000 spans/sec)

```
OTel Collector (DaemonSet) → Jaeger Collector (HA, LB)
                                 ↓
                          Elasticsearch (3 nodes)
                                 ↓
                          Jaeger Query (HA) → Jaeger UI
```

### 5.6.3 Large Scale (50,000+ spans/sec)

```
OTel Collector (DaemonSet) → OTel Gateway Collectors (tail sampling)
                                 ↓
                          Kafka (buffering)
                                 ↓
                          Jaeger Ingestors (consume from Kafka)
                                 ↓
                          Elasticsearch (5-10 nodes, daily indices)
                                 ↓
                          Jaeger Query (HA, cache) → Jaeger UI
```

**Why Kafka is necessary at scale:**
- **Decoupling**: Elasticsearch can't keep up with 100,000 writes/sec continuously
- **Buffering**: Elasticsearch maintenance (rolling restart) doesn't lose spans
- **Replayability**: If Elasticsearch index corrupts, replay from Kafka
- **Consumer groups**: Multiple consumers (Jaeger ingestor, data lake, analytics pipeline) from the same Kafka topic

---

## 5.7 Common Misconceptions

### "Jaeger is a logging system"

Jaeger stores TRACES (span trees), not logs. It answers "show me the waterfall for this slow request." It does NOT answer "show me all ERROR lines from auth-service in the last hour." That's OpenSearch's job.

### "More retention = better"

Long retention on detailed traces is expensive and rarely useful. After 7 days, individual traces are rarely needed. For trend analysis, use metrics (Prometheus). For long-term trace sampling (> 30 days), use a dedicated analytics pipeline with heavy downsampling.

### "Jaeger and Zipkin are competitors that I must choose between"

Both are CNCF projects for distributed tracing. Jaeger was originally inspired by Zipkin (Dapper paper). OTel makes the choice irrelevant: use OTel SDK + Collector → export to either (or both). The backend choice is about operations preference, not instrumentation.

---

## Interview Questions — Phase 5

1. **Why is Elasticsearch a good fit for span storage? What makes spans challenging for a relational database?**

   *Answer core points*: Elasticsearch's inverted index enables fast search by arbitrary tag key-value pairs. Relational DBs require fixed schemas — spans have variable attributes. Time-based indices align with span retention (drop entire day). Relational DBs would require partitioned tables and complex index management.

2. **Explain the Jaeger Collector's role. Why use Kafka between Collector and Storage?**

   *Answer core points*: Collector validates, enriches, and routes spans. Kafka decouples ingestion from storage, providing buffering during Elasticsearch slowness, enabling multiple consumers (storage + analytics), and preventing backpressure on applications.

3. **How does head-based sampling interact with Jaeger? Why might stored traces be incomplete?**

   *Answer core points*: Head sampling in the SDK (before Collector) decides at trace creation. If each service independently samples (no ParentBased), child spans may be sampled when the root was dropped, resulting in orphaned spans in Jaeger. Solution: ParentBased sampler propagates the root's decision.

4. **At 500,000 spans/second, design a Jaeger architecture. What are the bottlenecks?**

   *Answer core points*: OTel Gateway with tail sampling → Kafka (30+ partitions) → Jaeger Ingestors → Elasticsearch (10+ nodes, daily indices, 5 primary shards). Bottlenecks: (1) Elasticsearch write throughput per shard, (2) Kafka consumer lag if ingesters are slow, (3) Elasticsearch refresh/merge overhead. Mitigation: increase shards, add ingesters, tune refresh interval to 10s.

5. **What is the "nested tags" problem in Elasticsearch span storage?**

   *Answer core points*: Elasticsearch flattens JSON arrays, losing the key-value association within tag objects. Querying `tags.key=error AND tags.value=true` requires nested queries to preserve the association between a tag's key and its value. Without nesting, a span with `{key: http.status_code, value: 500}` AND `{key: error, value: true}` would incorrectly match a query for "error=true AND status_code=500".

---

**Next: Phase 6 — OpenSearch Deep Dive**
