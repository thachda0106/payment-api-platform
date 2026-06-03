# Session 22: Observability — Logging, Metrics, Tracing with OpenTelemetry

## Why This Topic Exists

Observability is not monitoring. Monitoring tells you whether the system is working (up/down, CPU > 80%). Observability tells you why the system is behaving the way it does, even for failure modes you never anticipated. In a Go Chi service handling thousands of requests per second, you cannot predict every way the system might fail. A downstream service might start responding slowly, a database index might become bloated, a new deployment might trigger a latent race condition that only manifests under specific request ordering. If your only tool is a dashboard showing "p99 latency = 500ms", you know there is a problem but you are blind to the cause.

The three pillars — logging, metrics, tracing — work together. Logs tell the story of a single request: "at 14:23:01.234, request ABC123 called FindOrderByID(42); at 14:23:01.456, it received 5 rows; at 14:23:01.789, it returned 200." Metrics aggregate across requests: "in the last 5 minutes, 50,000 requests completed, 230 failed, p99 latency was 234ms." Traces connect the dots across service boundaries: "request ABC123 spent 12ms in the API gateway, 89ms in the orders service, 450ms in the database (single slow query), and 3ms returning to the client." Without all three, you have gaps in your understanding that become blind spots during an incident.

OpenTelemetry (OTel) is the emerging standard for vendor-neutral instrumentation. The Go SDK (`go.opentelemetry.io/otel`) provides a unified API for traces, metrics, and logs (the latter still maturing). When you instrument your Chi service with OTel, you get spans for every HTTP request, context propagation to downstream calls (via W3C traceparent headers), and automatic correlation between logs and traces (via trace ID and span ID in log context). The investment is in the instrumentation — once instrumented, you can switch from Jaeger to Tempo, or from Prometheus to Grafana Cloud Metrics, without changing application code. For a staff engineer, the architectural decision is not "which observability vendor" — it is "how to instrument so the signal-to-noise ratio supports debugging under production pressure."

## Mental Model

Think of observability as a pyramid. At the base is **structured logging**: every event the system produces carries a uniform set of attributes (timestamp, level, message, trace_id, span_id, service_name, request_id). This is the raw material — high volume, low signal density, but universal. It answers the question "what happened to this specific request?"

The middle layer is **distributed tracing**: spans form a directed acyclic graph where parent spans wrap child spans, creating a tree that represents the entire journey of a request through your system. Each span has a name (e.g., "GET /api/orders/:id"), a start and end time, attributes (e.g., `http.status_code=200`, `db.statement=SELECT ...`), and status (OK/Error). The trace context propagates across process boundaries via HTTP headers (`traceparent: 00-traceid-spanid-01`). Traces answer the question "where did the time go for this request?"

The top layer is **metrics**: time-series data that aggregates over time. Each metric has a name (e.g., `http_request_duration_seconds`), a type (counter, gauge, histogram, summary), labels/dimensions (e.g., `method=GET`, `path=/api/orders`, `status=200`), and a value. Metrics answer the question "is the system healthy?" at a glance, and support SLO/SLI frameworks for operational decision-making.

The pyramid works because each layer can generate the layer above it. Spans can be sampled and turned into metrics (span duration to histogram). Logs can be enriched with trace context to be queryable by trace ID. The key insight: **instrumentation is expensive to retrofit and cheap to build correctly the first time**. A Chi middleware that creates a span and injects trace context into logs costs 20 lines of code and negligible performance overhead. Retrofitting it onto 50 handlers costs days of developer effort and introduces bugs.

```
                   OBSERVABILITY PYRAMID
                        +---------+
                        | METRICS |  <-- Aggregated: "is it working?"
                        | (SLIs)  |      Low cardinality, continuous
                        +---------+
                        | TRACES  |  <-- Request-scoped: "where is time spent?"
                        | (spans) |      Medium cardinality, sampled
                        +---------+
                        |  LOGS   |  <-- Event-level: "what happened?"
                        |(slog)   |      High cardinality, high volume
                        +---------+

  +--------------------------------------------------------------+
  |  REQUEST JOURNEY THROUGH THE PYRAMID                          |
  |                                                               |
  |  Client -> Chi Router -> Handler -> Repo -> sqlc -> PostgreSQL |
  |    |          |           |          |        |         |      |
  |    |          +-- span ---+          |        |         |      |
  |    |          |  "GET     |          |        |         |      |
  |    |          |  /orders" |          |        |         |      |
  |    |          |           +-- span --+        |         |      |
  |    |          |           |  "Find   |        |         |      |
  |    |          |           |  Order"  +-- span -+         |      |
  |    |          |           |          |  "SELECT ..."     |      |
  |    |          |           |          |                   |      |
  |    |          v           v          v                   v      |
  |    |  +--------------------------------------------------+     |
  |    |  | slog.Info("request completed")                    |     |
  |    |  |   trace_id=abc123 span_id=def456                  |     |
  |    |  |   duration_ms=45 status=200                       |     |
  |    |  +--------------------------------------------------+     |
  |    |                                                            |
  |    v                                                            |
  |  +----------------------------------------------------------+  |
  |  | Prometheus metrics (from spans or middleware):             |  |
  |  |   http_request_duration_seconds_count{path="/orders"}++   |  |
  |  |   http_request_duration_seconds_sum{path="/orders"}+=45   |  |
  |  +----------------------------------------------------------+  |
  +--------------------------------------------------------------+
```

## Internal Architecture

**Structured logging with `log/slog` (Go 1.21+).** The `log/slog` package provides a structured logging API with levels (DEBUG, INFO, WARN, ERROR), attributes (key-value pairs), and handlers that process log records. The default handler outputs text to stderr. For production, use `slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})` for machine-parseable JSON. The Chi integration point is a middleware that extracts or generates a request ID, adds it to the logger's context via `slog.With("request_id", requestID)`, and stores the logger in the request context: `ctx = context.WithValue(ctx, loggerKey, logger)`. Handlers retrieve the logger with `slog.FromContext(ctx)` and call `logger.InfoContext(ctx, "order found", "order_id", id, "duration_ms", d.Milliseconds())`. The `InfoContext` variant includes context, which enables extracting trace/span IDs from context for correlation.

**`middleware.RequestID` and log correlation.** Chi's `middleware.RequestID` generates a unique ID for each request (or uses the `X-Request-Id` header if present). The ID is stored in the context and set on the response header `X-Request-Id`. A custom logging middleware (replacing Chi's built-in one) should extract this ID and add it to the request-scoped logger. The chain: `middleware.RequestID` runs first, generates the ID; custom slog middleware runs second, creates a logger with `slog.With("request_id", middleware.GetReqID(ctx))` and stores it in context; handler retrieves logger from context and uses it; all logs from this request are tagged with the same `request_id`. The client receives this ID in the response header, so if a user reports an error with the `X-Request-Id` value, you can search logs for that exact ID.

**The RED method for HTTP metrics.** RED stands for Rate (requests per second), Errors (failed requests), Duration (latency distribution). These three metrics provide a complete picture of HTTP service health. In Prometheus: **Rate**: `http_requests_total{method="GET", path="/api/orders", status="200"}` — a counter incremented by a Chi middleware that runs after the handler. Track `status` as a label to distinguish 4xx (client errors, not actionable) from 5xx (server errors, actionable). **Errors**: Derived from rate: `rate(http_requests_total{status=~"5.."}[5m])` gives the error rate. **Duration**: `http_request_duration_seconds{method="GET", path="/api/orders"}` — a histogram with buckets: `[.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10]`. The histogram enables calculating arbitrary quantiles (p50, p95, p99) and Apdex scores. The `+Inf` bucket equals total request count, so you can derive rate from the histogram.

**The USE method for resource metrics.** USE stands for Utilization, Saturation, Errors. Apply this to Go runtime resources: Utilization — `go_memstats_heap_alloc_bytes / go_memstats_heap_sys_bytes` (heap utilization), `process_cpu_seconds_total` rate (CPU utilization). Saturation — `go_goroutines` (goroutine saturation), `go_memstats_gc_cpu_fraction` (GC saturation), `db_pool_wait_count` rate (connection pool saturation). Errors — `go_memstats_mspan_lookups_total` (allocation failures indicate fragmentation), `process_open_fds / process_max_fds` (file descriptor exhaustion risk).

**OpenTelemetry Go SDK architecture.** The SDK is organized into: (1) a `TracerProvider` (usually singleton, created at startup) that manages `Tracer` instances, (2) `SpanProcessor` implementations that handle span lifecycle (SimpleProcessor for synchronous export in tests, BatchProcessor for asynchronous batching in production), (3) `SpanExporter` implementations that send spans to a backend (OTLP exporter for Jaeger/Tempo/OTel Collector, stdout exporter for debugging), (4) a `Propagator` (typically W3C TraceContext + Baggage) that handles context injection/extraction for cross-process propagation, and (5) a `Resource` describing the service (service name, version, deployment environment, host name).

**otelhttp.NewHandler — automatic HTTP instrumentation.** The `otelhttp` package provides an `http.Handler` wrapper: `otelhttp.NewHandler(chiRouter, "api-gateway")`. This wrapper: (a) extracts the trace context from incoming HTTP headers (W3C `traceparent`, or B3 if configured), (b) creates a new span for each request named `{method} {path}` (e.g., "GET /api/orders/:id"), (c) sets span attributes: `http.method`, `http.url`, `http.target`, `http.host`, `http.scheme`, `http.status_code`, `http.response_content_length`, (d) records errors if the handler returns a status >= 400, (e) injects the span into the request context so downstream code can create child spans. The wrapper works with any `http.Handler`, including Chi's `chi.Mux`.

**Custom span instrumentation for database and external calls.** Inside a handler, when calling a repository or external API, create child spans manually:

```go
func (r *OrderRepo) FindByID(ctx context.Context, id uuid.UUID) (*Order, error) {
    tracer := otel.Tracer("orders-repo")
    ctx, span := tracer.Start(ctx, "FindOrderByID",
        trace.WithAttributes(
            attribute.String("db.system", "postgresql"),
            attribute.String("db.operation", "SELECT"),
            attribute.String("db.sql.table", "orders"),
            attribute.String("order.id", id.String()),
        ),
    )
    defer span.End()

    order, err := r.q.FindOrderByID(ctx, id)
    if err != nil {
        span.RecordError(err)
        span.SetStatus(codes.Error, err.Error())
        return nil, err
    }
    span.SetAttributes(attribute.String("order.status", order.Status))
    return order, nil
}
```

The key pattern: `tracer.Start(ctx, "OperationName")` creates a span whose parent is extracted from the context (which `otelhttp.NewHandler` put there). The `defer span.End()` ensures the span is closed even on panics (deferred functions still run). `span.RecordError(err)` adds an error event to the span timeline. `span.SetStatus(codes.Error, msg)` marks the span as failed. Without `RecordError`, the span's status is OK and the error is invisible in the trace UI.

**W3C trace context propagation.** When a Chi service calls a downstream HTTP service, it must propagate the trace context. Without OTel, this requires manual header management. With OTel, use `otelhttp`'s instrumented `http.Client`:

```go
client := &http.Client{
    Transport: otelhttp.NewTransport(http.DefaultTransport),
}
resp, err := client.Do(req.WithContext(ctx))
```

`otelhttp.NewTransport` wraps the transport so that: (a) it creates a child span for the outbound HTTP call, (b) it injects the current span context into the HTTP headers (`traceparent: 00-{trace_id}-{span_id}-01`, `tracestate: ...`), and (c) it records HTTP status and error attributes. The downstream service's `otelhttp.NewHandler` extracts this context and links the spans. The result: a trace that spans the API gateway, Chi service, downstream service, and database, with accurate timing for each hop.

**Exporting traces via OTLP.** The OpenTelemetry Protocol (OTLP) is the standard wire format. The Go SDK exports spans via `otlptracegrpc.New(ctx, otlptracegrpc.WithEndpoint("jaeger:4317"))` for gRPC, or `otlptracehttp.New(...)` for HTTP/protobuf. In production, export to an OTel Collector that batches, filters, and routes spans to the backend (Jaeger, Tempo, Honeycomb, etc.). The collector is critical for production: it provides buffering (if the backend is slow, spans are not dropped), tail sampling (send 100% of error traces, 1% of success traces), and data transformation (add attributes, drop PII, convert to backend-specific format).

**Prometheus metrics endpoint for Chi.** Use `promhttp.Handler()` from `github.com/prometheus/client_golang/prometheus/promhttp` to expose a `/metrics` endpoint:

```go
r := chi.NewRouter()
r.Handle("/metrics", promhttp.Handler())
```

Prometheus scrapes this endpoint every 15-60 seconds. Each metric is registered with `prometheus.MustRegister()` at package init time. For HTTP metrics, use `prometheus.NewHistogramVec` for duration, `prometheus.NewCounterVec` for request count. The histogram's `Observe(duration.Seconds())` is called in middleware after the handler completes. Warning: do not use high-cardinality label values (URL with query parameters, user IDs, trace IDs) — each unique label combination creates a new time series, and Prometheus's TSDB degrades with >100K active series. Sanitize paths: `/api/orders/550e8400-e29b-41d4-a716-446655440000` to `/api/orders/:id`.

## Runtime Behavior

When a request arrives at a Chi service instrumented with OTel, the `otelhttp.NewHandler` wrapper runs first (before any Chi middleware). It calls `propagator.Extract(ctx, propagation.HeaderCarrier(r.Header))`, which reads the `traceparent` header. If the header is present, the span context (trace ID, parent span ID, trace flags) is extracted into the context. If absent, a new trace is started. A new span is created with `tracer.Start(ctx, "GET /api/orders/:id")`, and the span is stored in the request context. The `r = r.WithContext(ctx)` pattern replaces the request's context with the one containing the span.

The Chi middleware chain runs inside this span. `middleware.RequestID` adds a request ID. The logging middleware creates a logger with `slog.With("trace_id", span.SpanContext().TraceID().String(), "span_id", span.SpanContext().SpanID().String(), "request_id", requestID)` and stores it in context. The handler runs, creates child spans for database and external API calls — each child span inherits the trace ID from the parent and has its own span ID. The parent-child relationship forms a tree: the root span wraps the entire HTTP handler, child spans wrap individual operations.

After the handler returns, the logging middleware logs the final request summary: `logger.InfoContext(ctx, "request completed", "method", r.Method, "path", r.URL.Path, "status", ww.Status(), "duration_ms", duration.Milliseconds(), "bytes_written", ww.BytesWritten())`. The `otelhttp` wrapper ends the root span, setting `http.status_code` and `http.response_content_length` attributes. The span is passed to the `SpanProcessor` chain: `BatchSpanProcessor` adds it to a local buffer; when the buffer is full or a timeout elapses, all buffered spans are exported via `SpanExporter.ExportSpans()` to the OTLP endpoint. The Prometheus middleware (or the RED metrics middleware) observes the duration histogram and increments the request counter with method, sanitized path, and status labels.

The `BatchSpanProcessor` is critical for production performance: without it, every span would trigger a synchronous HTTP/gRPC call to the backend, adding 1-10ms to every request. With batching (default batch size = 512 spans, batch timeout = 5 seconds), the export overhead is amortized across many requests. The tradeoff is that spans are not available in the backend for up to 5 seconds — acceptable for debugging, not acceptable for real-time alerting (use metrics for that).

The Prometheus scrape cycle is independent. Every 15-60 seconds, Prometheus calls `GET /metrics`. The `promhttp.Handler` gathers all registered metrics from the global registry, serializes them to the Prometheus text format, and returns them. The scrape is atomic: Prometheus reads a consistent snapshot of all counter values and histogram bucket counts at that instant. Between scrapes, counters increment and histograms observe values in memory. The `rate()` function in PromQL calculates per-second rates from counter differences between scrapes.

## Flow Diagrams

```
REQUEST OBSERVABILITY PIPELINE (Chi + OTel + Prometheus + slog)

  INCOMING HTTP REQUEST
     |
     v
  otelhttp.NewHandler (wraps Chi router)
     |
     +--> Extract trace context from HTTP headers
     |    (W3C traceparent, tracestate, baggage)
     |
     +--> Create root span: "GET /api/orders/:id"
     |    ctx = trace.ContextWithSpan(ctx, span)
     |    r = r.WithContext(ctx)
     |
     v
  +----------------------------------------------------+
  |  Chi Middleware Stack                                |
  |                                                      |
  +--> middleware.RequestID                              |
  |    requestID = uuid.New() or X-Request-Id           |
  |    ctx = context.WithValue(ctx, reqIDKey, id)        |
  |    w.Header().Set("X-Request-Id", id)                |
  |                                                      |
  +--> slogMiddleware (custom)                            |
  |    traceID = span.SpanContext().TraceID()             |
  |    logger = slog.Default().With(                      |
  |        "trace_id", traceID,                           |
  |        "request_id", requestID,                       |
  |    )                                                  |
  |    ctx = context.WithValue(ctx, loggerKey, logger)     |
  |                                                      |
  +--> prometheusMiddleware (custom)                      |
  |    sw := &statusWriter{ResponseWriter: w}             |
  |    defer func() {                                    |
  |        duration := time.Since(start)                  |
  |        path := sanitizePath(r.URL.Path)               |
  |        httpDuration.WithLabelValues(                   |
  |            r.Method, path,                            |
  |            strconv.Itoa(sw.status),                   |
  |        ).Observe(duration.Seconds())                  |
  |        httpRequestsTotal.WithLabelValues(              |
  |            r.Method, path,                            |
  |            strconv.Itoa(sw.status),                   |
  |        ).Inc()                                        |
  |    }()                                                |
  |                                                      |
  +--> middleware.Timeout(30 * time.Second)               |
  |                                                      |
  +--> handler.ServeHTTP(sw, r)  --------------------+   |
  +---------------------------------------------------+   |
                                                      |   |
                                                      v
  HANDLER EXECUTION
     |
     +--> logger := slog.FromContext(ctx)
     |
     +--> order, err := orderRepo.FindByID(ctx, id)
     |       |
     |       +--> tracer.Start(ctx, "FindOrderByID")
     |       |    (child span, parent = root span)
     |       |
     |       +--> q.FindOrderByID(ctx, id)    (database query)
     |       |
     |       +--> span.End()
     |       |
     |       +--> return order, nil
     |
     +--> logger.InfoContext(ctx, "order processed",
     |       "order_id", id, "status", order.Status)
     |
     +--> respondWithJSON(w, 200, order)
     |
     v
  POST-HANDLER (deferred functions run in reverse order)
     |
     +--> prometheusMiddleware defer:
     |    httpDuration.Observe(45ms)
     |    httpRequestsTotal.Inc()
     |
     +--> slogMiddleware defer:
     |    logger.Info("request completed", "duration_ms", 45,
     |        "status", 200, "bytes", 1234)
     |
     +--> otelhttp wrapper defer:
     |    span.SetAttributes(
     |        attribute.Int("http.status_code", 200),
     |        attribute.Int("http.response_content_length", 1234),
     |    )
     |    span.End()
     |    -> BatchSpanProcessor buffers span
     |    -> after 5s or 512 spans: ExportSpans() to OTLP endpoint
     |    -> OTel Collector -> Jaeger / Tempo / Honeycomb
     |
     +--> Response sent to client (with X-Request-Id header)


TRACE CONTEXT PROPAGATION ACROSS SERVICES:

  Service A (Chi API Gateway)
     |
     +--> Root span: "GET /api/orders"
     |    TraceID: abc...123
     |    SpanID:  spanA-001
     |
     +--> HTTP call to Service B (Orders Service)
     |    |
     |    |  otelhttp.NewTransport injects:
     |    |    traceparent: 00-abc...123-spanB-001-01
     |    |    tracestate: vendor=...
     |    |
     |    v
  Service B (Chi Orders Service)
     |
     +--> otelhttp.NewHandler extracts:
     |    TraceID: abc...123       <-- same trace!
     |    ParentSpanID: spanB-001   <-- Service A's outbound span
     |    SpanID: spanB-002         <-- Service B's inbound span
     |
     +--> Child span: "FindOrderByID" (spanB-003)
     |    Parent: spanB-002
     |
     +--> HTTP call to Service C (Payment Service)
     |    |
     |    |  injects: traceparent: 00-abc...123-spanB-004-01
     |    |
     |    v
  Service C (Chi Payment Service)
     |
     +--> otelhttp.NewHandler extracts:
         TraceID: abc...123       <-- same trace!
         ParentSpanID: spanB-004
         SpanID: spanC-001

  Result: Trace with 5 spans across 3 services, all sharing TraceID abc...123
```

## Source Code Reading Guide

**log/slog**: `log/slog/logger.go` — the `Logger` struct and `log()` / `logAttrs()` methods. `log/slog/handler.go` — the `Handler` interface. `log/slog/json_handler.go` — the JSON handler implementation (how structured logs are serialized). `log/slog/record.go` — the `Record` struct that carries log data between handler and handler chain.

**prometheus/client_golang**: `prometheus/registry.go` — how metrics are registered and gathered. `prometheus/histogram.go` — histogram implementation including bucket tracking. `prometheus/promhttp/http.go` — the `/metrics` HTTP handler, including `HandlerFor()` with error handling. `prometheus/desc.go` — the `Desc` struct that defines metric identity (name + labels).

**go.opentelemetry.io/otel**: `otel/trace/tracer.go` — the `Tracer` interface with `Start()` method. `otel/trace/span.go` — the `Span` interface (End, SetAttributes, RecordError, SetStatus, AddEvent). `otel/trace/context.go` — `ContextWithSpan()` and `SpanFromContext()`. `otel/propagation/propagation.go` — the `TextMapPropagator` interface for context injection/extraction.

**otelhttp**: `instrumentation/net/http/otelhttp/handler.go` — `NewHandler()` wraps an `http.Handler` with trace instrumentation. This is a ~300-line file — read it in full; it's the best documentation for how HTTP instrumentation works. `instrumentation/net/http/otelhttp/transport.go` — `NewTransport()` wraps `http.RoundTripper` for outbound calls.

**otlptracegrpc**: `exporters/otlp/otlptrace/otlptracegrpc/client.go` — the gRPC exporter. `exporters/otlp/otlptrace/otlptracehttp/client.go` — the HTTP exporter. These are relatively thin wrappers around the protobuf serialization.

**Reading order**: 1. `log/slog/logger.go` + `json_handler.go` -> 2. `otelhttp/handler.go` (this is the most important file — reads like a tutorial) -> 3. `prometheus/histogram.go` + `promhttp/http.go` -> 4. `otel/trace/tracer.go` + `otel/trace/span.go` -> 5. `otlptracegrpc/client.go`.

**What to skip**: The protobuf definitions in `opentelemetry-proto` (auto-generated, huge), the Jaeger-specific exporter (deprecated in favor of OTLP), the Prometheus Go client's text format parser, and sampling logic in the OTel SDK unless you're implementing a custom sampler.

## Production Failure Scenarios

**Scenario 1: Traces are sampling 1% but the 1-second spike only happens in the other 99%.** You are on call. P99 latency alerts fire — latency spiked to 5 seconds for 2 minutes, then recovered. You open Jaeger/Tempo, search for traces with duration > 1 second during the incident window. You find 5 traces. None of them show anything unusual — all database queries under 10ms, all external calls under 50ms. But these are 5 traces out of 500,000 requests (1% sample). The slow requests were in the 99% that you didn't sample. Root cause: the sampling config was set to `parentbased_traceidratio` with `ratio=0.01` for all traffic. Fix: implement tail-based sampling in the OTel Collector: forward 100% of spans to the collector, keep a buffer of the last N spans, and when a span completes with a duration > 500ms, export all spans from that trace. This gives you 100% of slow traces and 1% of fast traces. The collector's `tailsamplingprocessor` handles this.

**Scenario 2: Metrics show high error rate but no error traces exist.** The `http_requests_total{status="500"}` counter is incrementing rapidly, but searching traces for status=Error returns nothing. Two possible causes: (1) The `otelhttp` wrapper records the HTTP status code correctly, but the error happens outside any span — e.g., in middleware that runs before `otelhttp.NewHandler`, or in a deferred function after the span has ended. (2) The span exporter is dropping spans because the backend is overloaded, and the dropped spans happen to be the error spans. Debugging: check the `otelcol_exporter_sent_spans` counter vs. `otelcol_receiver_accepted_spans` — if the former is flat while the latter increases, spans are being dropped in the collector. Check the `otelcol_exporter_send_failed_spans` counter — if it's non-zero, the backend is rejecting spans. Ensure the exporter has retry logic (`retry_on_failure` enabled) and a dead-letter queue for spans that cannot be delivered.

**Scenario 3: High-cardinality label causes Prometheus OOM.** A new endpoint was added: `GET /api/orders/{orderId}`. The path sanitization middleware correctly replaces the UUID with `:id` for metrics. But the developer added a custom metric: `orders_total{order_id="550e8400-..."}`. With millions of unique order IDs, this creates millions of time series. Prometheus's TSDB can handle ~10 million active series comfortably, but the service now has 5 million `orders_total` time series — plus the HTTP metrics, Go runtime metrics, etc., pushing it to 12 million. Prometheus OOM-kills. Fix: do not use high-cardinality values as metric labels. Use labels for dimensions with bounded cardinality (<1000 values): method, path, status, database_name, cache_name. For tracking individual orders, use traces or logs, not metrics. Add a linter rule that rejects Prometheus metric registrations with label values derived from request parameters.

## Debugging Techniques

**Technique 1: Debug OTel span configuration with console exporter.** When traces are not appearing in the backend, the fastest debug tool is the stdout exporter: replace `otlptracegrpc.New(...)` with `stdouttrace.New(stdouttrace.WithPrettyPrint())`. This outputs every span to stdout in human-readable JSON. Run the service locally, send a single request, and inspect the output. Check: (a) Is the trace ID non-zero? (b) Is the parent span ID correct (for child spans)? (c) Are the attributes present? (d) Are the span start and end times reasonable? If the console exporter shows correct spans but Jaeger does not, the problem is in the OTLP connection (wrong endpoint, TLS misconfiguration, network partition). If the console exporter shows missing spans (e.g., the root span exists but no child spans), the context is not being propagated correctly — check that `r.WithContext(ctx)` is called after `tracer.Start()`.

**Technique 2: Trace a specific request with `traceresponse` header.** When debugging a specific production issue, send a synthetic request with a predetermined trace ID: `curl -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" http://localhost:3000/debug/orders/42`. The traceparent header forces the service to use your specified trace ID instead of generating a new one. Combined with a 100% sampling rate for the debug endpoint, this guarantees the trace is captured. Search the trace backend for `4bf92f3577b34da6a3ce929d0e0e4736` — you will see the exact execution path with timings. This is especially powerful when combined with feature flags: if a user reports an error, enable debug tracing for that user's next request by injecting a known trace ID.

**Technique 3: Verify log-trace correlation.** To confirm that logs contain trace IDs, search the log aggregator (Elasticsearch, Loki, CloudWatch) for a recent log line: `trace_id:*`. If this returns zero results, the trace ID is not being included in log context. Check the logging middleware: it must extract the trace ID from `span.SpanContext().TraceID()` in the `otelhttp` wrapper, not from a middleware-created context (which won't have the span yet if called before `otelhttp`). The order is critical: `otelhttp.NewHandler` (span created) -> custom middleware extracts span from context -> adds to logger. If custom middleware runs before `otelhttp` middleware, the span does not exist yet and the trace ID is zero.

## Observability Considerations

**Log**: Every log line must include `trace_id` and `span_id` (when inside a span), plus `service_name` and `environment` (via `slog.With()` at the `logger` creation during startup, not per-request). Log levels: DEBUG for diagnostic events (query parameters, cache hits/misses), INFO for business events (order created, payment processed), WARN for recoverable errors and degraded states (circuit breaker open, retry exhausted), ERROR for unrecoverable errors and panics. Never log at INFO for per-request diagnostic data (cache hit/miss per request) — this generates massive log volume with zero operational value. Never use FATAL level — it calls `os.Exit(1)` without running deferred functions, leaking resources. Use ERROR + return error instead.

**Metrics**: Beyond RED metrics, export business metrics: `orders_created_total{customer_type="new|returning"}`, `payments_amount_total{currency="USD"}`, `refunds_initiated_total`, `inventory_reservations_duration_seconds`. These connect system health to business health — if `orders_created_total` drops but `http_requests_total` is constant, the issue is a functional bug (orders failing silently), not an infrastructure problem. Alert on business metrics: `rate(orders_created_total[15m]) dropped by 50% compared to same time 7 days ago` — this detects functional regressions that infrastructure alerts miss.

**Traces**: Annotate spans with business attributes that aid debugging: `order.id`, `customer.id`, `payment.amount`, `error.message`. But never include PII in span attributes (no names, emails, phone numbers, full credit card numbers). Spans are retained in trace backends (Jaeger/Tempo) and are accessible to anyone with access to the tracing UI. Use a custom `SpanProcessor` that redacts known PII patterns before export.

**Alerts**: Define SLOs for each Chi service: e.g., "99.9% of requests complete in <200ms over a 30-day window." From the SLO, derive the error budget: at 1000 req/s, 0.1% error budget = 1 error/s allowed = 86,400 errors/day = 2.6 million errors/month. Burn rate alerts: fire a WARNING if the error budget is burning at >2x in the last hour (alert the on-call during business hours). Fire a CRITICAL page if burning at >10x in the last 5 minutes (wake someone up). The multi-window, multi-burn-rate approach from the Google SRE book: a burn rate of 14.4x in the last 1 hour AND 6x in the last 5 minutes means you will exhaust the 30-day budget in ~2 hours — time to page.

## Performance Implications

**slog performance.** The `slog.Info("msg", "key", "value")` pattern requires allocating a `[]any` for the key-value pairs, plus allocating a `Record` struct. In the hot path (DEBUG logging in a per-request handler), this can add ~1-2us and ~500 bytes of allocations per log call. Use `slog.LogAttrs()` instead: `slog.LogAttrs(ctx, slog.LevelInfo, "msg", slog.String("key", "value"))` — this avoids the `[]any` allocation. For DEBUG-level logs that are often disabled, wrap the log call in a level check: `if logger.Enabled(ctx, slog.LevelDebug) { ... }` — this avoids constructing the log attributes entirely when DEBUG is off. In JSON mode, the handler serialization does per-call allocations; for extremely hot paths (>10K log lines/s), consider batching log writes or using a lighter log format (logfmt).

**OTel span overhead.** Creating a span with attributes has real overhead: `tracer.Start()` allocates a span, copies attributes into the span's internal storage, and records the span in the context's span stack. For a simple handler that processes in <1ms, the span overhead can be 5-10% of total handler time. The `BatchSpanProcessor` amortizes export overhead but not creation overhead. To reduce cost, use a sampler that creates spans only for a percentage of requests: `traceidratio.New(0.1)` creates spans for 10% of requests. But this discards the other 90% — use `parentbased.New(traceidratio.New(0.1))` to ensure that once a trace is sampled, all child spans are also sampled (preserving trace completeness).

**Prometheus histogram cost.** Each `Observe()` call on a histogram does: (1) acquire a mutex (per-histogram, so contention under high concurrency), (2) linear search through buckets to find the right bucket, (3) atomic increment of the bucket counter and sum. With 20 buckets and 100K observations/s, this is ~200ns per observation — negligible for most services. The real cost is memory: each histogram with 20 buckets and 5 label dimensions (method, path, status, error_type, service) creates 20 * (cardinality of label combinations) time series. If your path sanitizer leaves query parameters in paths, cardinality explodes. Always sanitize paths to fixed patterns (`/api/orders/:id`, not `/api/orders/550e8400...`).

## Architecture Implications

The observability stack should be a shared platform capability, not a per-service decision. A platform team provides: (1) a Go module `pkg/observability` that configures OTel SDK, Prometheus registerer, and slog handler with production defaults (JSON format, INFO level, OTLP gRPC exporter to the collector at `otel-collector:4317`), (2) an OTel Collector deployment with tail sampling, attribute redaction, and batching, (3) a Grafana dashboard template that visualizes RED metrics from any service that imports `pkg/observability`, (4) alerting rules for SLO burn rate that apply to all services. Individual service teams call `observability.Init(ctx, ServiceConfig{Name: "orders", Version: "1.2.3"})` at startup and get a fully instrumented Chi router.

The `otelhttp.NewHandler` wrapper should wrap the entire Chi router, not individual routes. If you wrap individual route groups, you get multiple root spans per request (one per middleware group) instead of one root span per request. The correct setup:

```go
func main() {
    tp := observability.InitTracer(ctx, "orders-service", "1.0.0")
    defer tp.Shutdown(ctx)

    r := chi.NewRouter()
    r.Use(middleware.RequestID)
    r.Use(slogMiddleware)
    r.Use(prometheusMiddleware)
    r.Use(middleware.Timeout(30 * time.Second))
    r.Mount("/api/orders", OrderRoutes())

    wrapped := otelhttp.NewHandler(r, "orders-service")
    http.ListenAndServe(":3000", wrapped)
}
```

The port for observability endpoints should be separate from the service port. Use port 9090 for metrics (`/metrics`), pprof (`/debug/pprof/`), and health checks (`/health`, `/ready`). Use port 3000 for the actual service API. This allows network policies that restrict access: port 9090 is only accessible from the monitoring infrastructure (Prometheus scrapers, pprof debuggers), while port 3000 is accessible from the load balancer. Never expose pprof on the public-facing port.

Slack time between observability instrumentation approaches creates a fractured incident response experience. If service A uses OpenCensus, service B uses raw Prometheus, and service C uses OTel, the on-call engineer has to switch mental models between each debugging step. Standardizing on OTel across all services (with a migration path for existing OpenCensus/OpenTracing services) is a high-leverage architectural investment that pays for itself in reduced MTTR during incidents.

## Team Ownership Implications

Observability instrumentation is not optional or "nice to have" — it must be part of the definition of done for every endpoint. A code review checklist should include: (1) Are all external calls (database, HTTP, gRPC, Redis) wrapped with spans? (2) Are error paths instrumented with `span.RecordError`? (3) Are business metrics exported for key operations? (4) Is the log context propagating correctly (trace ID visible in logs)? (5) Are Prometheus metrics using bounded cardinality labels? (6) Is the endpoint visible in the RED dashboard?

The platform team owns the observability infrastructure (collector, Tempo, Prometheus, Grafana) and the `pkg/observability` library. Service teams own the instrumentation in their handlers — what to measure, what thresholds matter, what SLOs to adopt. The platform team provides linters that enforce: no raw `prometheus.NewCounter(opts)` calls (use the platform-provided factory that enforces label name conventions), no `fmt.Sprintf` log formatting (use `slog` structured logging), no `context.Background()` in request handlers (breaks context propagation), and no high-cardinality label values in Prometheus metric registrations.

## Interview Questions

**Q1: What is the difference between a Prometheus histogram and a summary, and when would you use each?**
Answer: A histogram quantizes observed values into pre-defined buckets and counts observations in each bucket. You calculate quantiles server-side in PromQL using `histogram_quantile()`, and you can aggregate histograms across instances (sum the bucket counters). A summary calculates quantiles client-side using a streaming algorithm and exposes them directly as metric values (p50, p90, p99). You cannot aggregate summaries across instances because quantiles are not additive. Use histograms for virtually all HTTP latency metrics — they support aggregation across instances and arbitrary quantile calculation. Use summaries only when you need exact quantiles per-instance (e.g., latency distribution of a single goroutine pool operation) and aggregation is not needed. The Go Prometheus client's default summaries have configurable quantiles and a configurable error margin.

**Q2: How does the W3C traceparent header encode trace context, and what do each of its fields mean?**
Answer: The format is `traceparent: 00-{trace_id}-{parent_span_id}-{trace_flags}`. `00` is the version. `trace_id` is a 32-character hex string (16 bytes) — the globally unique identifier for the entire distributed trace. `parent_span_id` is a 16-character hex string (8 bytes) — the span ID of the caller (the span that caused this request). The receiving service creates its own `span_id` for the inbound request. `trace_flags` is a 2-character hex string (1 byte) where `01` means "this trace is sampled" (all spans in this trace should be recorded). A second header, `tracestate`, carries vendor-specific data in key-value pairs separated by commas — this is used by tracing vendors to propagate their internal state alongside the standard traceparent header. This dual-header design is what makes cross-vendor tracing work: traceparent carries the standard data; tracestate carries vendor extensions.

**Q3: Why should you never use user ID or order ID as a Prometheus metric label?**
Answer: Each unique combination of label values creates a separate time series in Prometheus. A user ID label with 1 million unique users creates 1 million time series per metric — 20 million if you have 20 buckets in a histogram. Prometheus's TSDB is designed for ~10 million active series on commodity hardware; beyond that, query performance degrades, compaction takes longer, and memory usage grows linearly with series count. High-cardinality labels also make queries meaningless: graphing `http_requests_total` with `user_id` as a label produces a rainbow of 1 million lines, none individually useful. Instead, use labels for bounded dimensions (HTTP method: 5 values, status: ~10 common values, path: ~100 route patterns) and defer per-entity tracking to logs and traces, which are designed for high cardinality.

**Q4: What is the difference between a Counter, Gauge, and Histogram in Prometheus, and what Go operations correspond to each?**
Answer: A **Counter** is a cumulative metric that only increases (or resets to zero on restart). Use `prometheus.NewCounter(opts)` and `.Inc()` / `.Add(n)`. Counter examples: `http_requests_total`, `errors_total`, `bytes_sent_total`. In PromQL, use `rate(counter[5m])` to get per-second rate. A **Gauge** is a value that can go up and down. Use `prometheus.NewGauge(opts)` and `.Set(v)` / `.Inc()` / `.Dec()`. Gauge examples: `go_goroutines`, `memory_alloc_bytes`, `db_pool_in_use_connections`. A **Histogram** samples observations and counts them in configurable buckets. Use `prometheus.NewHistogram(opts)` and `.Observe(v)`. Histogram examples: `http_request_duration_seconds`, `db_query_duration_seconds`. In PromQL, use `histogram_quantile(0.99, rate(histogram[5m]))` for p99. The key distinction: counters are for events (how many), gauges are for current state (how much right now), histograms are for distributions (how it varies).

**Q5: How do you configure OpenTelemetry sampling to ensure that 100% of error traces are captured while only 1% of success traces are kept?**
Answer: Head-based sampling (in the application SDK) cannot make decisions based on the trace outcome because the outcome is not known when the root span is created. You need tail-based sampling in the OTel Collector. Configure the `tailsamplingprocessor` with a composite policy: (1) `latency` policy — if any span in the trace has a duration > 500ms, sample the trace at 100%, (2) `status_code` policy — if any span has status ERROR, sample at 100%, (3) `probabilistic` policy — sample 1% of remaining traces. The collector buffers spans in memory (configurable buffer size, e.g., 50,000 spans) and holds them until the trace is "complete" (either all expected spans arrive or a timeout elapses, e.g., 10s). At that point, it evaluates the policies and forwards sampled traces to the backend. The buffer uses memory proportional to the number of in-flight traces; for a service handling 10,000 spans/s with a 10s decision timeout, that's ~100,000 buffered spans, which is manageable with a few GB of heap.

## Hands-On Exercises

**Exercise 1: Instrument a Chi service with full RED metrics and structured logging.** Create a Chi service with a `/metrics` endpoint, custom Prometheus middleware that tracks RED metrics, and custom slog middleware that creates a per-request logger with trace_id, span_id, and request_id. Verify: (a) `curl /metrics` returns `http_requests_total` and `http_request_duration_seconds` with correct method/path/status labels, (b) `curl -v /api/orders/42` shows `X-Request-Id` header in the response, (c) log output includes `trace_id`, `span_id`, `request_id`, `duration_ms`, `status`, and `bytes_written` for each request.

**Exercise 2: Set up distributed tracing across two services.** Create two Chi services (Service A and Service B) that run on different ports. Service A has an endpoint that calls Service B via HTTP. Instrument both with OTel using a console exporter. Verify: (a) a curl to Service A produces a trace with at least 3 spans (root span in A, child span for the HTTP call to B, root span in B), (b) all spans share the same trace ID, (c) the parent-child relationship is correct (Service B's inbound span has Service A's outbound span as parent). Replace the console exporter with an OTLP gRPC exporter pointing to a local Jaeger instance and verify all spans appear in the Jaeger UI.

**Exercise 3: Define SLOs and implement burn-rate alerts.** Define an SLO for your Chi service: "99.5% of requests complete in <200ms over a 28-day window." Implement a Prometheus recording rule that calculates the error budget remaining: `(total_requests * 0.005) - total_errors_over_threshold`. Implement two Prometheus alert rules: (a) WARNING — error budget consumed > 10% in the last hour (burn rate > 2.4x), (b) CRITICAL — error budget consumed > 5% in the last 5 minutes (burn rate > 14.4x). Simulate errors by adding a handler that randomly returns 500, and verify the alerts fire at the correct burn rates.

## Advanced Challenges

**Challenge 1: Build a custom OTel SpanProcessor that implements PII redaction, dynamic sampling based on span attributes, and automatic span-to-metric conversion.** Requirements: (a) Before each span is exported, scan all attribute keys for patterns matching PII (e.g., `*.email`, `*.phone`, `*.name`) and replace their values with `[REDACTED]`. (b) If a span has attribute `priority=high` or `customer.tier=enterprise`, force-sample the entire trace (set `Sampled=true` on the span context). (c) For every span with attribute `http.method` and `http.status_code`, automatically export a counter metric `span_duration_seconds_count{method, path, status}` and a histogram `span_duration_seconds`. (d) The SpanProcessor must be non-blocking (use a channel buffer for spans that need force-sampling) and must handle the case where `ForceFlush()` is called during graceful shutdown.

**Challenge 2: Implement a multi-service trace validation system.** Build a sidecar or collector plugin that validates trace completeness in real time. Requirements: (a) Accept spans from all services via OTLP. (b) Maintain a sliding window of expected trace patterns: for a trace starting with "GET /api/orders/:id", expect child spans for "FindOrderByID", "GetCustomerByID", and "GetPaymentByOrderID" (configurable per endpoint). (c) If a trace is missing an expected child span, fire an alert with the trace ID and missing span name — this indicates that instrumentation was added to some code paths but not all. (d) Calculate a "trace completeness score" per endpoint as a Prometheus metric: `traces_with_all_expected_spans / total_traces`. Alert if the score drops below 95%. (e) The validator must handle high-cardinality span names (dynamic route patterns must be normalized) and must not OOM even if traces have thousands of spans (sample representative traces for detailed validation).

## Key Insights

- Structured logging with `log/slog` + trace context injection turns logs from a firehose of text into queryable, correlated records — a single trace ID links all logs from all services involved in a request
- The RED method (Rate, Errors, Duration) is the minimum viable HTTP observability — with just three metrics, you can answer "is the service healthy?", "are errors increasing?", and "is latency degrading?"
- otelhttp.NewHandler wrapping the entire Chi router is the single most impactful line of observability code you can write — it gives you root spans, context propagation, and standard HTTP attributes with zero per-handler work
- Prometheus metric labels must have bounded cardinality (<1000 values) — a single high-cardinality label explodes time series count and causes Prometheus OOM; path sanitization is not optional
- Tail-based sampling in the OTel Collector is how you get 100% of error traces and 1% of success traces — head-based sampling in the SDK cannot make decisions based on trace outcome
- The OTel Collector is a critical production dependency: it provides buffering, tail sampling, batching, retry, and format translation — run it as a DaemonSet or sidecar, never skip it and export directly from the application to the backend
- SLO-based alerting (burn rate, error budget) is categorically better than threshold-based alerting (CPU > 80%) because it ties alerts to user experience and prevents alert fatigue from false positives
