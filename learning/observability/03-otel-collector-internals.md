# Phase 3 — OpenTelemetry Collector Internals

> **Duration**: 1 week | **Prerequisites**: Phase 2 (OpenTelemetry Deep Dive)
>
> **Goal**: Understand the Collector's internal architecture, pipeline model, and deployment patterns.

---

## 3.1 Why the Collector Exists

### 3.1.1 The Problem Without a Collector

Without the Collector, each service SDK exports directly to backends:

```
Service A (Java) ──→ Prometheus (remote write)
Service A (Java) ──→ Jaeger (thrift)
Service A (Java) ──→ OpenSearch (bulk API)

Service B (Go)   ──→ Prometheus (remote write)
Service B (Go)   ──→ Jaeger (thrift)
Service B (Go)   ──→ OpenSearch (bulk API)

Service C (Node) ──→ Prometheus (remote write)
Service C (Node) ──→ Jaeger (thrift)
Service C (Node) ──→ OpenSearch (bulk API)
```

**Problems with this model:**

1. **Every service manages backend connections.** Connection pools, retries, timeouts — duplicated across N services.
2. **Changing backends requires re-deploying every service.** Switching from Jaeger to Tempo means updating every service's SDK config.
3. **No centralized processing.** Tail sampling, attribute filtering, data enrichment — each service must implement it independently.
4. **No buffering.** If a backend is temporarily unavailable, each service must handle backpressure independently.
5. **Backend authentication scattered.** API keys, TLS certs — distributed across every service.

### 3.1.2 The Solution

```
Service A (Java) ──┐
Service B (Go)   ──┤
Service C (Node) ──┼──→ [OTel Collector] ──→ Prometheus
Service D (Python)──┤                        ──→ Jaeger
Service E (Go)   ──┘                        ──→ OpenSearch
```

One Collector instance handles all backend communication. Services only need to know ONE endpoint: the Collector's OTLP port.

---

## 3.2 Collector Architecture

### 3.2.1 Component Model

```
┌─────────────────────────────────────────────────────┐
│                   OTel Collector                     │
│                                                      │
│  ┌──────────┐  ┌───────────┐  ┌──────────────────┐  │
│  │ Receivers│→ │ Processors│→ │    Exporters     │  │
│  └──────────┘  └───────────┘  └──────────────────┘  │
│       ↑              ↑                  ↓            │
│       │         ┌─────────┐              │            │
│       │         │Extensions│             │            │
│       │         └─────────┘              │            │
│       │              ↑                   │            │
│       └──────────────┴───────────────────┘            │
│               Connectors (connect pipelines)          │
└─────────────────────────────────────────────────────┘
```

### 3.2.2 Receivers

Receivers accept telemetry data from external sources. They're the ENTRY point.

| Receiver | Protocol | Use Case |
|----------|----------|----------|
| **OTLP** | gRPC/HTTP | Primary — receive from OTel SDKs |
| **Jaeger** | Thrift/gRPC | Backward compat — receive from Jaeger agents |
| **Zipkin** | HTTP/JSON | Backward compat — receive from Zipkin instrumented apps |
| **Prometheus** | HTTP scrape | Scrape Prometheus endpoints from apps |
| **Kafka** | Kafka consumer | Receive telemetry from Kafka topics |
| **Fluent Forward** | TCP | Receive logs from Fluentd/Fluent Bit |

**OTLP receiver configuration:**

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317        # gRPC (primary)
      http:
        endpoint: 0.0.0.0:4318        # HTTP (alternative)
```

**Why two protocols (gRPC and HTTP)?**
- gRPC: Better performance for intra-datacenter communication (binary, multiplexed)
- HTTP: Better for cross-datacenter (easier to load balance, proxy, firewall)

### 3.2.3 Processors

Processors transform, filter, enrich, or batch telemetry data between receivers and exporters.

| Processor | Function | When |
|-----------|----------|------|
| **Batch** | Accumulate spans/metrics and send in batches | ALWAYS — reduces network overhead |
| **Memory Limiter** | Bound memory usage, drop data if exceeded | ALWAYS — prevent OOM |
| **Tail Sampling** | Sample traces after completion | Production — keep errors, sample successes |
| **Attributes** | Add/remove/rename span attributes | When you need data sanitization |
| **Resource** | Modify resource attributes | When auto-detected resources are wrong |
| **Filter** | Drop spans based on conditions | Drop health check spans, specific endpoints |
| **Transform** | Complex data transformations (OTTL) | Advanced use cases |
| **K8s Attributes** | Add K8s metadata (pod name, namespace, labels) | Kubernetes environments |
| **Probabilistic Sampling** | Sample traces within collector pipeline | Reduce volume before export |

**Batch processor — critical for performance:**

```yaml
processors:
  batch:
    timeout: 10s              # Send batch after 10s even if not full
    send_batch_size: 8192     # Send when 8192 spans accumulated
    send_batch_max_size: 0    # 0 = unlimited
```

The batch processor accumulates spans and sends them in bulk. Without batching, the Collector would make one network call per span — catastrophic for performance.

**Why `timeout` matters**: If traffic is low, you can't wait forever. The timeout guarantees data is sent within 10 seconds even if the batch isn't full.

**Memory Limiter — prevent OOM:**

```yaml
processors:
  memory_limiter:
    check_interval: 1s
    limit_mib: 512             # Hard limit: 512 MiB
    spike_limit_mib: 128       # 25% of hard limit for transient spikes
```

The memory limiter is a safety valve. If the Collector's memory approaches the limit, it starts refusing new data (backpressure to services). Without this, a traffic spike or slow backend causes unbounded memory growth → OOM kill → data loss.

**Why two limits**: `limit_mib` is the hard ceiling. `spike_limit_mib` is for temporary spikes. During normal operation, the Collector operates below `spike_limit_mib`. If memory exceeds it, the Collector enters "limited" mode until memory drops.

**Tail Sampling processor:**

```yaml
processors:
  tail_sampling:
    decision_wait: 30s          # Wait for all spans in a trace
    num_traces: 100000          # Max traces buffered simultaneously
    expected_new_traces_per_sec: 5000
    policies:
      # Policy 1: Always keep errors
      - name: errors
        type: status_code
        status_code:
          status_codes: [ERROR]

      # Policy 2: Always keep traces > 5 seconds
      - name: slow
        type: latency
        latency:
          threshold_ms: 5000

      # Policy 3: Keep 10% of remaining (success, fast) traces
      - name: probabilistic
        type: probabilistic
        probabilistic:
          sampling_percentage: 10
```

**Policy evaluation order**: Policies are evaluated in order. The FIRST matching policy determines the decision. If no policy matches, the trace is dropped.

```
Trace: ERROR status → matches "errors" policy → KEPT (policy 2-3 skipped)
Trace: OK, 8000ms    → doesn't match "errors" → matches "slow" → KEPT
Trace: OK, 50ms      → doesn't match "errors" or "slow" → matches "probabilistic" → 10% chance KEPT
```

**Attributes processor — data sanitization:**

```yaml
processors:
  attributes:
    actions:
      # Remove sensitive attributes
      - key: credit_card_number
        action: delete
      - key: user.password
        action: delete

      # Rename for consistency
      - key: http.status_code
        action: update
        new_key: http.status_code  # normalize to semantic conventions

      # Add deployment info
      - key: deployment.environment
        action: insert
        value: production

      # Hash PII instead of deleting (preserves uniqueness)
      - key: user.email
        action: hash
```

### 3.2.4 Exporters

Exporters send processed telemetry to backends.

| Exporter | Backend | Data |
|----------|---------|------|
| **OTLP** | Another Collector (gateway) | Traces, Metrics, Logs |
| **Prometheus** | Prometheus scraper endpoint | Metrics |
| **Prometheus Remote Write** | Prometheus/Mimir/Thanos receivers | Metrics |
| **Jaeger** | Jaeger Collector | Traces |
| **OpenSearch** | OpenSearch/Elasticsearch | Logs, Traces |
| **Kafka** | Kafka topic | Traces, Metrics, Logs |
| **Debug/Logging** | stdout (development) | All |
| **File** | Filesystem | All |

**Debug exporter (development):**

```yaml
exporters:
  debug:
    verbosity: detailed   # Shows complete span data
```

### 3.2.5 Connectors

Connectors link two pipelines — they act as both an exporter (from pipeline A) AND a receiver (for pipeline B).

**The Span Metrics Connector** creates RED metrics from traces:

```yaml
connectors:
  spanmetrics:
    histogram:
      explicit:
        buckets: [1ms, 5ms, 10ms, 50ms, 100ms, 500ms, 1s, 5s, 10s]
    dimensions:
      - name: http.method
      - name: http.status_code
      - name: service.name
```

```
Pipeline 1: traces
  Receiver (OTLP) → Batch → SpanMetrics Connector → (metrics)

Pipeline 2: metrics
  (metrics from connector) → Batch → Prometheus Exporter
```

**Why this is powerful**: You get RED metrics (rate, errors, duration per endpoint) WITHOUT instrumenting metrics in your code. The Collector derives them from traces.

### 3.2.6 Extensions

Extensions provide infrastructure services to the Collector (not part of the data pipeline).

| Extension | Function |
|-----------|----------|
| **Health Check** | `/health` endpoint for liveness probes |
| **pprof** | Go profiling endpoints for debugging |
| **zpages** | Internal diagnostics UI |
| **File Storage** | Persistent storage for checkpointing |
| **OAuth2 Client** | Authenticate with backends |

---

## 3.3 Pipeline Configuration

### 3.3.1 Complete Pipeline Example

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  memory_limiter:
    check_interval: 1s
    limit_mib: 512
    spike_limit_mib: 128

  batch:
    timeout: 10s
    send_batch_size: 8192

  tail_sampling:
    decision_wait: 30s
    num_traces: 100000
    policies:
      - name: keep-errors
        type: status_code
        status_code:
          status_codes: [ERROR]
      - name: keep-slow
        type: latency
        latency:
          threshold_ms: 5000
      - name: sample-success
        type: probabilistic
        probabilistic:
          sampling_percentage: 10

  resource:
    attributes:
      - key: deployment.environment
        value: production
        action: upsert

exporters:
  otlp/traces:
    endpoint: jaeger-collector:4317
    tls:
      insecure: true

  prometheusremotewrite:
    endpoint: http://prometheus:9090/api/v1/write

  opensearch/logs:
    http:
      endpoint: https://opensearch:9200
    index: otel-logs

service:
  extensions: [health_check, pprof]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, tail_sampling, resource, batch]
      exporters: [otlp/traces]

    metrics:
      receivers: [otlp]
      processors: [memory_limiter, resource, batch]
      exporters: [prometheusremotewrite]

    logs:
      receivers: [otlp]
      processors: [memory_limiter, resource, batch]
      exporters: [opensearch/logs]
```

### 3.3.2 Data Flow Through a Pipeline

```
1. Service sends OTLP span to Collector:4317

2. Receiver (OTLP) deserializes protobuf → internal SpanData struct

3. Processor Chain:
   memory_limiter:  "Is memory below 512 MiB?" → YES → continue
   tail_sampling:   Buffer span, wait 30s for siblings
                    After 30s: trace status=OK, latency=50ms
                    → matches "sample-success" (10%) → KEEP
   resource:        Add "deployment.environment=production"
   batch:          Accumulate in batch buffer

4. When batch is full (8192 spans) or timeout (10s):
   Exporter (OTLP) serializes to protobuf → gRPC → Jaeger Collector
```

---

## 3.4 Collector Deployment Models

### 3.4.1 Agent (Sidecar / DaemonSet)

One Collector per NODE (DaemonSet) or per POD (sidecar).

```
Node 1
├── Pod A ──→ Collector (sidecar) ──→ Backend
├── Pod B ──→ Collector (sidecar) ──→ Backend
└── Pod C ──→ Collector (sidecar) ──→ Backend

OR

Node 1
├── Pod A ──┐
├── Pod B ──┤
├── Pod C ──┼──→ Collector (DaemonSet) ──→ Backend
└── Pod D ──┘
```

**When to use Agent:**
- Tail sampling (each agent sees only its node's traces — limited context)
- Low latency (no extra network hop)
- Resource isolation (collector memory scoped to one node)
- Simple deployments

**When NOT to use Agent:**
- Tail sampling requires seeing ALL spans of a trace (spans may be on different nodes)
- Many services → many sidecars → resource waste
- Backend connection pool multiplication

### 3.4.2 Gateway (Centralized)

One Collector cluster that ALL services send to.

```
Node 1 ──┐
Node 2 ──┤
Node 3 ──┼──→ Collector Cluster (HA) ──→ Backends
Node 4 ──┤
Node N ──┘
```

**When to use Gateway:**
- Tail sampling across the entire system (sees all spans)
- Centralized backend authentication
- Backend connection pooling (N services → M collectors → K backend connections)
- Attribute enrichment/redaction consistency

**When NOT to use Gateway:**
- Single point of failure (mitigated by HA: load balancer + multiple collectors)
- Extra network hop (agent adds latency)
- If gateway is down, ALL telemetry is lost (no local buffer)

### 3.4.3 Agent + Gateway (Production Pattern)

```
Node 1 ──→ Agent Collector (DaemonSet) ──┐
Node 2 ──→ Agent Collector (DaemonSet) ──┤
Node 3 ──→ Agent Collector (DaemonSet) ──┼──→ Gateway Collectors ──→ Backends
Node 4 ──→ Agent Collector (DaemonSet) ──┤       (HA, Load Balanced)
Node N ──→ Agent Collector (DaemonSet) ──┘
```

**Agent role**: Batching, memory limiting, basic filtering. Lightweight. Low memory.
**Gateway role**: Tail sampling, attribute processing, k8s enrichment. Sees ALL spans.

```
Agent Collector config:
  receivers: [otlp]
  processors: [memory_limiter, batch]
  exporters: [otlp/gateway]     # Send to gateway, not to backends

Gateway Collector config:
  receivers: [otlp]
  processors: [memory_limiter, tail_sampling, k8sattributes, resource, batch]
  exporters: [prometheusremotewrite, jaeger, opensearch]
```

**Benefits:**
1. Offloads processing from agents (light, stable memory)
2. Centralized tail sampling (sees all spans for a trace)
3. Agents buffer if gateway is temporarily unavailable
4. Backend auth centralized at gateway
5. Scale gateway independently

**Risks:**
1. If ALL gateways are down, telemetry buffered at agents until OOM
2. Extra network hop: 2x OTLP serialization/deserialization
3. More complex configuration

### 3.4.4 When to Use Each Deployment

| Scale | Recommendation |
|-------|---------------|
| 1-5 services, dev environment | Single Collector (Docker) |
| 5-20 services, production | Agent (DaemonSet) → Backends |
| 20-100 services, production | Agent (DaemonSet) → Gateway → Backends |
| 100+ services, multi-cluster | Agent → Per-cluster Gateway → Central Gateway → Backends |

---

## 3.5 Scaling the Collector

### 3.5.1 Horizontal Scaling

Multiple Collector instances behind a load balancer:

```
Services → Load Balancer (L4, consistent hashing) → Collector-1
                                                   → Collector-2
                                                   → Collector-3
```

**Load balancing strategy**: Consistent hashing on `trace_id`. This ensures all spans from the same trace go to the same collector instance — essential for tail sampling.

Without consistent hashing:
```
Trace a1b2: Span-A → Collector-1
            Span-B → Collector-2  ← different collector, can't reassemble trace!
```

With consistent hashing:
```
Trace a1b2: Span-A → Collector-1
            Span-B → Collector-1  ← same collector, can tail sample
```

### 3.5.2 Memory Sizing

A Collector instance's memory requirement depends on:

1. **Throughput**: Spans/second × average span size
2. **Batch buffer size**: `send_batch_size` × average span size
3. **Tail sampling buffer**: `num_traces` × average spans_per_trace × average span size
4. **Receiver buffer**: Queued incoming spans

**Rough sizing formula:**

```
Memory ≈ (throughput_spans_per_sec × decision_wait_seconds × avg_span_bytes)
       + (batch_config × avg_span_bytes)
       + (receiver_queue × avg_span_bytes)
       + 100 MiB (Collector overhead + Go runtime)
```

**Example**: 10,000 spans/sec, 30s decision_wait, 2KB per span:
```
Memory ≈ (10000 × 30 × 2048) + (8192 × 2048) + (1000 × 2048) + 100MB
       ≈ 614 MB + 16 MB + 2 MB + 100 MB
       ≈ 732 MB
```

**Configure at least 2x for safety**: `limit_mib: 1500`.

### 3.5.3 CPU Sizing

Collector operations are CPU-bound (protobuf serialization/deserialization, compression).

**Key CPU consumers:**
- OTLP deserialization (receiving from services)
- Attribute processing (string manipulation)
- Tail sampling (trace reassembly, policy evaluation)
- OTLP serialization (exporting to backends)
- Compression (if enabled)

**Rough guideline**: 1 vCPU per 5,000-10,000 spans/second (with batching and compression).

---

## 3.6 Failure Scenarios

### 3.6.1 Collector Crashes

**Without file storage extension**: All buffered (unsent) data is lost. The Collector is stateless.

**With file storage extension**: Buffered data is checkpointed to disk. On restart, buffered data is replayed.

```yaml
extensions:
  file_storage:
    directory: /var/lib/otelcol
    timeout: 1s
    compaction:
      directory: /var/lib/otelcol/compaction
      on_start: true
```

**Critical**: Enable file storage extension in production. Without it, Collector restarts lose buffered data.

### 3.6.2 Backend Unavailable

**What happens**: The exporter's retry/queue mechanism kicks in.

```yaml
exporters:
  otlp/jaeger:
    endpoint: jaeger:14250
    sending_queue:
      enabled: true
      num_consumers: 10
      queue_size: 10000      # Hold up to 10,000 batches
    retry_on_failure:
      enabled: true
      initial_interval: 5s   # First retry after 5s
      max_interval: 30s      # Max interval between retries
      max_elapsed_time: 300s # Give up after 5 minutes
```

**Data flow during Jaeger outage:**
1. Exporter fails to send → retry with exponential backoff
2. Batch accumulates in sending queue
3. Queue fills → Collector applies backpressure to processors
4. Processors slow down → memory limiter kicks in → Collector refuses new spans
5. Service SDKs receive errors → buffer locally → retry

**After 5 minutes** (`max_elapsed_time`), unsent data in that batch is dropped.

### 3.6.3 Backpressure Chain

```
Backend is slow
    ↓
Exporter can't send fast enough → queue fills
    ↓
Processors can't pass data to exporters → slow down
    ↓
Memory limiter threshold hit → refuse incoming spans
    ↓
Service SDKs see errors → buffer locally → retry
    ↓
If SDK buffer fills → drop spans → degrade gracefully
```

**The chain prevents cascade failure.** Each layer absorbs some backpressure before propagating it upstream.

---

## 3.7 Common Misconceptions

### "The Collector is just a proxy"

No. It's a telemetry processing pipeline. Batching, tail sampling, attribute mutation, metric creation — these are transformations, not passthrough.

### "One Collector per cluster is enough"

For tail sampling, you need multiple collectors with consistent-hashed routing to see complete traces. A single Collector has finite memory (can buffer a limited number of concurrent traces).

### "The Collector automatically handles backpressure"

It handles backpressure, but you must configure memory limiter correctly. Without it, a slow backend causes unbounded memory growth.

### "Tail sampling in the Collector works automatically"

Tail sampling requires careful configuration: decision_wait, num_traces, policies, and consistent-hashed routing. Naive setup loses data.

---

## Interview Questions — Phase 3

1. **Explain the OTel Collector's pipeline model. What's the difference between a Receiver, Processor, and Exporter?**

   *Answer core points*: Receiver = data ingress (OTLP, Jaeger, Prometheus scrape). Processor = transformation (batch, memory limiter, tail sampling, attribute mutation). Exporter = data egress (Prometheus remote write, Jaeger gRPC, OpenSearch). Data flows Receiver → Processor(s) → Exporter(s) in a linear pipeline. Multiple pipelines can exist with separate configurations.

2. **Why would you deploy an Agent + Gateway pattern instead of just a Gateway?**

   *Answer core points*: Agent provides local buffering (if gateway is unreachable), reduces the blast radius of a gateway outage, and offloads basic processing (batching, memory limiting) closer to the source. Gateway provides centralized tail sampling (sees complete traces), backend auth, and attribute normalization.

3. **How does consistent hashing enable tail sampling in a horizontally-scaled Collector?**

   *Answer core points*: Hash on trace_id ensures all spans from the same trace arrive at the same Collector instance. Without consistent hashing, spans from a single trace are scattered across instances, making tail sampling impossible because no single instance sees the complete trace.

4. **What happens when the Collector's memory limiter is triggered?**

   *Answer core points*: The collector enters a "memory limited" state. It starts returning errors to upstream senders (service SDKs). SDKs buffer and retry with backoff. This backpressure propagates upstream, preventing the Collector from OOM-killing. Once memory drops below spike_limit, the Collector resumes accepting data.

5. **When would you use the Span Metrics Connector instead of instrumenting RED metrics in your application code?**

   *Answer core points*: When you want RED metrics without code changes (e.g., for auto-instrumented services), or for legacy services you cannot modify. Trade-off: span metrics are derived from traces — if you're head-sampling traces, your metrics will be incomplete. Direct metric instrumentation is always more accurate.

6. **What's the risk of setting `decision_wait` too low in tail sampling?**

   *Answer core points*: If decision_wait is shorter than the max trace duration, some spans from slow traces haven't arrived yet when the sampling decision is made. The decision is based on incomplete information — a trace that will eventually have errors might be dropped because the error span arrives after the decision deadline.

---

**Next: Phase 4 — Prometheus Deep Dive**
