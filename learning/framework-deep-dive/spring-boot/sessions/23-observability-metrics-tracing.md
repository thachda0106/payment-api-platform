# Session 23: Observability — Metrics, Traces & Structured Logging

## 1. Why This Topic Exists

Observability is not a feature you add to an application. It is a property the application either has or lacks. A Spring Boot application with 20 REST endpoints, 5 downstream dependencies, and a PostgreSQL database generates millions of data points per minute. Without instrumented code, those data points are invisible. The on-call engineer stares at a 502 spike on the load balancer with no way to determine which service is failing, which endpoint is slow, or which database query changed.

The three pillars — **logs**, **metrics**, and **traces** — answer different questions:

- **Logs**: What specifically happened during this request? (Structured event data)
- **Metrics**: How many requests? How fast? How many errors? (Aggregated numeric data)
- **Traces**: How did this single request flow through our distributed system? (Request-level causal chain)

**Staff engineer insight**: Observability is a competitive advantage during incidents. A team with dashboards showing RED metrics (Rate, Errors, Duration) per endpoint, traces showing the exact call chain of a failed request, and structured logs searchable by `requestId` can diagnose a production issue in minutes. A team without these capabilities is flying blind — they restart pods and hope. The investment in observability is not measured in data stored but in MTTR (Mean Time to Recovery) reduction.

The evolution from logs-only to full observability typically looks like:

```
Phase 1: Application logs to stdout → grep in log files
Phase 2: Centralized logging (ELK / Loki) → searchable logs
Phase 3: Metrics (Prometheus + Grafana) → dashboards and alerts
Phase 4: Distributed tracing → request-level visibility
Phase 5: SLOs + Error Budgets → business-aligned reliability
Phase 6: Continuous profiling → always-on production profiling
```

Most Spring Boot applications stall at Phase 2 because teams underestimate the value of metrics and tracing. This session covers the full stack from instrumentation to dashboards to SLOs, with deep dives into the Spring Boot components that make it all work.

## 2. Mental Model

### The Three Pillars in Context

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         OBSERVABILITY STACK                               │
│                                                                           │
│  ┌───────────────────┐  ┌────────────────────┐  ┌──────────────────────┐ │
│  │      LOGS          │  │      METRICS        │  │       TRACES         │ │
│  │                    │  │                      │  │                      │ │
│  │ Structured JSON    │  │ RED: Rate, Errors,   │  │ Distributed context  │ │
│  │ events             │  │       Duration       │  │ propagation          │ │
│  │                    │  │ USE: Utilization,    │  │                      │ │
│  │ Per-request detail │  │       Saturation,    │  │ Request-level        │ │
│  │ timestamp, userId  │  │       Errors         │  │ causal chain         │ │
│  │                    │  │                      │  │                      │ │
│  │ Stored in:         │  │ Aggregated numeric   │  │ Sampled (not 100%)   │ │
│  │  Loki / ELK        │  │ time series data     │  │                      │ │
│  │                    │  │                      │  │ Stored in:           │ │
│  │ Retention: 7-30d   │  │ Stored in:           │  │  Tempo / Jaeger /    │ │
│  │                    │  │  Prometheus /        │  │  Zipkin              │ │
│  │ Search by:         │  │  VictoriaMetrics     │  │                      │ │
│  │  requestId, userId,│  │                      │  │ Retention: 1-7d      │ │
│  │  level, logger     │  │ Retention: 15d-1y    │  │                      │ │
│  │                    │  │                      │  │ Search by:           │ │
│  │                    │  │ Query: PromQL        │  │  traceId, service    │ │
│  │                    │  │ Visualize: Grafana   │  │ Visualize: Gantt     │ │
│  └───────────────────┘  └────────────────────┘  └──────────────────────┘ │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │                        CORRELATION                                    │ │
│  │                                                                       │ │
│  │  Log entry contains traceId → jump from log to trace in Tempo/Jaeger │ │
│  │  Trace span contains logs URL → jump from trace to log in Loki/ELK   │ │
│  │  Metric label contains error=true → filter dashboard by error type   │ │
│  │  Alert includes trace exemplar → one-click jump to the slow request  │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### The Core Instrumentation Layer

```
YOUR APPLICATION CODE
  |
  v
┌──────────────────────────────────────────────────────────────┐
│                 SPRING BOOT ACTUATOR                          │
│                                                              │
│  /actuator/health     → HealthIndicator beans                │
│  /actuator/metrics    → MeterRegistry                        │
│  /actuator/env        → Environment                          │
│  /actuator/threaddump → ThreadMXBean                         │
│  /actuator/heapdump   → HotSpotDiagnosticMXBean              │
│  /actuator/info       → InfoContributor beans                │
└──────────────────────────────────────────────────────────────┘
  |
  v
┌──────────────────────────────────────────────────────────────┐
│                    MICROMETER                                 │
│                                                              │
│  MeterRegistry (binding to monitoring system)                │
│  ├── PrometheusMeterRegistry  (pull-based, /actuator/prometheus)
│  ├── OtlpMeterRegistry        (OTLP push to collector)       │
│  ├── JmxMeterRegistry         (JMX MBeans)                   │
│  ├── DatadogMeterRegistry     (Datadog Agent)                │
│  └── CloudWatchMeterRegistry  (AWS CloudWatch)               │
│                                                              │
│  Meter types:                                                │
│  ├── Counter        — monotonically increasing count         │
│  ├── Gauge          — instantaneous value                    │
│  ├── Timer          — duration + count                       │
│  ├── DistributionSummary — value distribution                │
│  └── LongTaskTimer  — in-flight task duration                │
└──────────────────────────────────────────────────────────────┘
  |
  v
┌──────────────────────────────────────────────────────────────┐
│               MICROMETER TRACING                              │
│                                                              │
│  Observation API (io.micrometer.observation)                 │
│  ├── ObservationRegistry                                    │
│  ├── Observation (context + lifecycle handlers)             │
│  ├── ObservationHandler (metrics + tracing integration)     │
│  └── ObservationConvention (naming convention)              │
│                                                              │
│  Tracer bridge implementations:                              │
│  ├── Brave (Zipkin-compatible)                               │
│  └── OpenTelemetry (OTLP protocol)                           │
└──────────────────────────────────────────────────────────────┘
```

## 3. Internal Architecture

### Spring Boot Actuator Architecture

```java
// Source: org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration
// Key class: EndpointDiscoverer discovers @Endpoint and @WebEndpoint beans

// Health Endpoint architecture:
// ┌────────────────────────────────────────────────────────┐
// │ HealthEndpoint (Web)                                    │
// │  GET /actuator/health                                   │
// │  GET /actuator/health/{component}                       │
// │  GET /actuator/health/{component}/{instance}             │
// └────────────────────┬───────────────────────────────────┘
//                      │
//                      v
// ┌────────────────────────────────────────────────────────┐
// │ HealthEndpoint (Core Logic)                             │
// │                                                         │
// │  HealthContributorRegistry                              │
// │  ├── CompositeHealthContributor                         │
// │  │   └── Iterates ALL HealthContributor beans            │
// │  │       ├── HealthIndicator (simple: UP/DOWN)          │
// │  │       └── ReactiveHealthIndicator (reactive)         │
// │  │                                                      │
// │  │  StatusAggregator: Aggregates statuses               │
// │  │    ├── Default: any DOWN → aggregate is DOWN         │
// │  │    └── Custom: can order by severity                │
// │  └─────────────────────────────────────────────────────┘
// │                                                         │
// │  HealthEndpointGroups                                   │
// │  ├── (default group) — all indicators                   │
// │  ├── liveness — indicators that don't check externals   │
// │  └── readiness — indicators that check external systems  │
// └────────────────────────────────────────────────────────┘

// Source code path for a health check call:
// 1. GET /actuator/health → HealthEndpointWebExtension.health()
// 2. → HealthEndpoint.health()
// 3. → HealthContributorRegistry.getContributor()
// 4. → CompositeHealthContributor.health()
// 5. → For each HealthIndicator:
// 6.   indicator.health() -> calls isHealthy() or health()
// 7.   For DataSourceHealthIndicator:
//     → connection.isValid(validationTimeout) → SELECT 1
// 8. StatusAggregator.getAggregateStatus(details)
```

### Micrometer MeterRegistry Internals

```java
// Source: io.micrometer.core.instrument.MeterRegistry
// The MeterRegistry is the central abstraction — it's a concurrent map
// of Meter.Id → Meter, with thread-safe registration.

public abstract class MeterRegistry {
    // Each meter is uniquely identified by its Id
    private final ConcurrentHashMap<Id, Meter> meterMap = new ConcurrentHashMap<>();

    // Counter creation — returns existing meter if Id matches
    public Counter counter(String name, Iterable<Tag> tags) {
        return Counter.builder(name)
            .tags(tags)
            .register(this); // register() checks meterMap, returns existing if found
    }

    // Timer creation — same pattern
    public Timer timer(String name, Iterable<Tag> tags) {
        return Timer.builder(name)
            .tags(tags)
            .register(this);
    }
}

// Meter ID uniqueness:
// Two meters are the same if: name + tags are identical
// Different tag VALUES → different meters (different time series)
// This is why high-cardinality tags (userId, requestId) are dangerous:
// each unique tag combination creates a new meter
```

### PrometheusMeterRegistry: How Metrics Are Exposed

```java
// Source: io.micrometer.prometheus.PrometheusMeterRegistry
// When /actuator/prometheus is called:

public class PrometheusMeterRegistry extends MeterRegistry {
    private final ConcurrentMap<Id, PrometheusCollector> collectorMap;

    @Override
    public String scrape() {
        // Step 1: Iterate all registered meters
        // Step 2: For each meter, convert to Prometheus exposition format
        // Step 3: Return text/plain response

        StringBuilder sb = new StringBuilder();
        for (PrometheusCollector collector : collectorMap.values()) {
            collector.write(sb);
        }
        return sb.toString();
    }
}

// Example output:
// # HELP http_server_requests_seconds
// # TYPE http_server_requests_seconds histogram
// http_server_requests_seconds_bucket{uri="/api/orders",method="GET",le="0.01"} 45.0
// http_server_requests_seconds_bucket{uri="/api/orders",method="GET",le="0.05"} 120.0
// http_server_requests_seconds_bucket{uri="/api/orders",method="GET",le="0.1"} 180.0
// http_server_requests_seconds_bucket{uri="/api/orders",method="GET",le="+Inf"} 200.0
// http_server_requests_seconds_count{uri="/api/orders",method="GET"} 200.0
// http_server_requests_seconds_sum{uri="/api/orders",method="GET"} 15.2
```

### Micrometer Tracing — Observation API

```java
// Source: io.micrometer.observation.Observation
// The Observation API unifies metrics AND tracing in a single abstraction.

public class Observation {
    private final ObservationRegistry registry;
    private final Context context;
    // Context carries: key-value baggage, parent trace info, low/high cardinality keys

    // observe() wraps a Runnable/Supplier with full observability
    public <T> T observe(Supplier<T> supplier) {
        // 1. START: Notify all ObservationHandlers
        //    - MeterHandler starts Timer.Sample
        //    - TracingHandler starts a Span (if enabled)
        //    - LoggingHandler logs START event with context

        Observation.Context ctx = start();

        try {
            // 2. Execute the actual business logic
            T result = supplier.get();

            // 3. STOP: Notify all handlers with success
            ctx.put("result", "SUCCESS");
            stop();
            return result;
        } catch (Exception e) {
            // 4. ERROR: Notify all handlers with error
            ctx.put("result", "ERROR");
            error(e);
            stop();
            throw e;
        }
    }
}

// ObservationRegistry handler chain:
// Observation.start()
//   → MeterHandler.onStart() — starts Timer.Sample
//   → TracingHandler.onStart() — creates Span
//   → LoggingHandler.onStart() — logs observation start
//
// Observation.stop()
//   → MeterHandler.onStop() — stops Timer.Sample, records in MeterRegistry
//   → TracingHandler.onStop() — ends Span, sends to collector
//   → LoggingHandler.onStop() — logs observation completion
```

### Logging Architecture — Structured JSON with Logback

```xml
<!-- logback-spring.xml — Production-grade structured logging -->
<configuration>
    <!-- Standard JSON encoder for console/container output -->
    <appender name="CONSOLE_JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <!-- Custom field names -->
            <fieldNames>
                <timestamp>@timestamp</timestamp>
                <message>message</message>
                <logger>logger_name</logger>
                <thread>thread_name</thread>
                <level>level</level>
            </fieldNames>
            <!-- Include MDC fields in output -->
            <includeMdcKeyName>requestId</includeMdcKeyName>
            <includeMdcKeyName>userId</includeMdcKeyName>
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <!-- Shortened fully-qualified logger names -->
            <shortenedLoggerNameLength>30</shortenedLoggerNameLength>
        </encoder>
    </appender>

    <!-- Async wrapper to avoid blocking application threads on log writes -->
    <appender name="ASYNC_JSON" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>8192</queueSize>
        <neverBlock>true</neverBlock>  <!-- Drop messages if queue full (prefer OTel) -->
        <appender-ref ref="CONSOLE_JSON" />
    </appender>

    <!-- Rolling file for local development / debugging -->
    <appender name="FILE_JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.json</file>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder" />
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/application.%d{yyyy-MM-dd}.json.gz</fileNamePattern>
            <maxHistory>30</maxHistory>
            <totalSizeCap>3GB</totalSizeCap>
        </rollingPolicy>
    </appender>

    <!-- Custom field injection via Logstash custom fields -->
    <appender name="CONSOLE_JSON_ENRICHED" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp/>
                <pattern>
                    <pattern>
                        {
                            "service": "${springAppName:-unknown}",
                            "environment": "${springProfilesActive:-default}",
                            "version": "${buildVersion:-0.0.0}",
                            "hostname": "${HOSTNAME:-unknown}",
                            "instance": "${instanceId:-unknown}"
                        }
                    </pattern>
                </pattern>
                <mdc/>
                <logstashMarkers/>
                <stackTrace>
                    <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
                        <maxDepthPerThrowable>50</maxDepthPerThrowable>
                        <maxLength>2048</maxLength>
                        <shortenedClassNameLength>40</shortenedClassNameLength>
                        <rootCauseFirst>true</rootCauseFirst>
                    </throwableConverter>
                </stackTrace>
            </providers>
        </encoder>
    </appender>

    <!-- Application logger levels -->
    <logger name="com.example.payment" level="INFO" />
    <logger name="org.springframework" level="WARN" />
    <logger name="org.hibernate" level="WARN" />
    <logger name="com.zaxxer.hikari" level="INFO" />
    <logger name="io.micrometer" level="WARN" />

    <root level="INFO">
        <appender-ref ref="ASYNC_JSON" />
    </root>
</configuration>
```

### MDC (Mapped Diagnostic Context) Internals

```java
// MDC is the thread-local storage that Logback uses to enrich each log line.
// Understanding its internals is critical for avoiding ThreadLocal leaks.

// Source: org.slf4j.MDC (SLF4J API, delegates to implementation)

// Logback's implementation uses a ThreadLocal<Map<String, String>>
// Each thread has its own MDC map. When the thread processes a new request,
// the filter sets MDC values. Those values appear in ALL log lines until removed.

// CORRECT PATTERN: Set → Use → Clear in finally block
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Extract or generate request identifiers
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        String userId = request.getHeader("X-User-Id");
        String tenantId = request.getHeader("X-Tenant-Id");
        String userAgent = request.getHeader("User-Agent");

        // Populate MDC — these appear in every log line for this request
        MDC.put("requestId", requestId);
        MDC.put("userId", userId);
        MDC.put("tenantId", tenantId);
        MDC.put("clientIp", request.getRemoteAddr());
        MDC.put("method", request.getMethod());
        MDC.put("path", request.getRequestURI());

        // Return requestId to client
        response.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // CRITICAL: Clear ALL MDC entries in FINALLY block
            // If any @ExceptionHandler path throws, MDC is still cleaned
            MDC.clear();  // Removes ALL entries for this thread
        }
    }
}

// What happens inside MDC.put():
// 1. MDC.getMDCAdapter() → returns LogbackMDCAdapter
// 2. LogbackMDCAdapter.put(key, value):
//      Map<String, String> map = copyOnThreadLocal.get();
//      if (map == null) {
//          map = new HashMap<>();
//          copyOnThreadLocal.set(map);
//      }
//      map.put(key, value);
//
// The "copyOnThreadLocal" is a ThreadLocal<Map<String, String>>.
// It is NOT automatically cleaned when a request ends.
// If you don't call MDC.clear() or MDC.remove(), stale values
// persist for the lifetime of the thread (which is pooled in Tomcat).

// MDC in @Async methods — MANUAL PROPAGATION REQUIRED:
@Async
public CompletableFuture<String> processAsync(String data) {
    // At this point, MDC is EMPTY (different thread!)

    // Solution 1: Capture MDC context map before async call
    // In the caller thread:
    Map<String, String> contextMap = MDC.getCopyOfContextMap();

    // Pass to async method:
    @Async
    public CompletableFuture<String> processAsync(String data,
            Map<String, String> mdcContext) {
        if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
        }
        try {
            // MDC available here
            log.info("Processing {}", data);
            return CompletableFuture.completedFuture("done");
        } finally {
            MDC.clear();  // Clean up when done
        }
    }

    // Solution 2: Use a TaskDecorator for automatic propagation:
    @Bean("asyncExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    static class MdcTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            // Capture MDC context in CALLER thread
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    // Restore MDC context in TASK thread
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    }
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        }
    }
}
```

### Grafana Dashboard Provisioning

```json
// RED Dashboard — Grafana provisioning JSON (simplified excerpt)
{
  "dashboard": {
    "title": "Payment API — RED Dashboard",
    "tags": ["payment-api", "auto-generated"],
    "timezone": "browser",
    "panels": [
      {
        "title": "Request Rate per Endpoint",
        "type": "graph",
        "targets": [{
          "expr": "sum(rate(http_server_requests_seconds_count{service=\"payment-api\",uri=~\"/api/.*\"}[5m])) by (uri)",
          "legendFormat": "{{uri}}"
        }]
      },
      {
        "title": "Error Rate per Endpoint",
        "type": "graph",
        "targets": [{
          "expr": "sum(rate(http_server_requests_seconds_count{service=\"payment-api\",status=~\"5..\",uri=~\"/api/.*\"}[5m])) by (uri) / sum(rate(http_server_requests_seconds_count{service=\"payment-api\",uri=~\"/api/.*\"}[5m])) by (uri)",
          "legendFormat": "{{uri}}"
        }]
      },
      {
        "title": "p99 Latency per Endpoint",
        "type": "graph",
        "targets": [{
          "expr": "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{service=\"payment-api\",uri=~\"/api/.*\"}[5m])) by (le, uri))",
          "legendFormat": "{{uri}}"
        }]
      },
      {
        "title": "Error Budget Remaining",
        "type": "stat",
        "targets": [{
          "expr": "1 - (sum(rate(http_server_requests_seconds_count{service=\"payment-api\",status=~\"5..\"}[30d])) / sum(rate(http_server_requests_seconds_count{service=\"payment-api\"}[30d])))",
          "legendFormat": "Error Budget"
        }],
        "fieldConfig": {
          "defaults": {
            "unit": "percentunit",
            "thresholds": {
              "steps": [
                {"value": 0, "color": "red"},
                {"value": 0.001, "color": "orange"},
                {"value": 0.005, "color": "green"}
              ]
            }
          }
        }
      }
    ]
  }
}
```

### Prometheus Recording Rules for Performance

```yaml
# Prometheus recording rules — precompute expensive queries
groups:
  - name: payment-api-recording-rules
    interval: 30s
    rules:
      # Per-endpoint error ratio (precompute for dashboards and alerts)
      - record: endpoint:http_errors:ratio_rate5m
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (uri, service)
          /
          sum(rate(http_server_requests_seconds_count[5m])) by (uri, service)

      # p99 latency per endpoint (expensive histogram_quantile precomputed)
      - record: endpoint:http_latency:p99_rate5m
        expr: |
          histogram_quantile(0.99,
            sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri, service)
          )

      # p50 latency per endpoint
      - record: endpoint:http_latency:p50_rate5m
        expr: |
          histogram_quantile(0.50,
            sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri, service)
          )

      # Apdex score: satisfied (<= 250ms), tolerating (<= 1s), frustrated (> 1s)
      - record: endpoint:http_apdex:satisfied
        expr: |
          sum(rate(http_server_requests_seconds_bucket{le="0.25"}[5m])) by (uri)
          /
          sum(rate(http_server_requests_seconds_count[5m])) by (uri)

      - record: endpoint:http_apdex:tolerating
        expr: |
          (sum(rate(http_server_requests_seconds_bucket{le="1.0"}[5m])) by (uri)
           -
           sum(rate(http_server_requests_seconds_bucket{le="0.25"}[5m])) by (uri))
          /
          sum(rate(http_server_requests_seconds_count[5m])) by (uri)
```

## 4. Runtime Behavior

### How a REST Request Gets Instrumented

```
CLIENT sends GET /api/orders/12345
   |
   v
TomcatWorker thread-42
   |
   v
ServerHttpObservationFilter (auto-configured)
  │
  ├── Creates Observation "http.server.requests"
  │   Context: {method=GET, uri=/api/orders/12345, status=200, outcome=SUCCESS}
  │
  ├── Observation.start():
  │   │
  │   ├── MeterHandler.onStart(context):
  │   │     │ Creates Timer.Sample (captures start time)
  │   │     │ context.put("micrometer.timer.Sample", sample)
  │   │
  │   ├── TracingHandler.onStart(context):
  │   │     │ Checks request headers for "traceparent" (W3C TraceContext)
  │   │     │ If present: extract traceId, spanId, traceFlags
  │   │     │ If absent or new trace needed: generate new traceId + spanId
  │   │     │ Creates Span with name = "GET /api/orders/12345"
  │   │     │ context.put("micrometer.tracing.Span", span)
  │   │     │ Returns SpanInScope (sets current span on thread context)
  │   │     │
  │   │     │ Headers checked:
  │   │     │   traceparent: 00-{traceId}-{spanId}-{traceFlags}
  │   │     │   tracestate: vendor-specific key-value pairs
  │   │
  │   └── LoggingHandler.onStart(context):
  │         │ MDC.put("traceId", span.context().traceId())
  │         │ MDC.put("spanId", span.context().spanId())
  │         │ Logs: "START GET /api/orders/12345"
  │
  ├── chain.doFilter(request, response) → DispatcherServlet → Controller
  │
  │   During controller execution:
  │   │ Any @Observed method creates child observations
  │   │ Any HTTP client call propagates traceId in outgoing headers
  │   │ Any MDC.put() values appear in all log lines
  │   │ Any @Timed method records a Timer metric
  │
  ├── Controller returns 200 OK
  │
  └── Observation.stop():
      │
      ├── MeterHandler.onStop(context):
      │     │ Timer.Sample.stop() → records duration in seconds
      │     │ Timer.builder("http.server.requests")
      │     │    .tag("method", "GET")
      │     │    .tag("uri", "/api/orders/{id}")  // templated URI
      │     │    .tag("status", "200")
      │     │    .tag("outcome", "SUCCESS")
      │     │    .register(meterRegistry)
      │     │    .record(durationNanos, TimeUnit.NANOSECONDS)
      │     │
      │     │ Creates/existing Timer gets another observation
      │     │ Prometheus format: http_server_requests_seconds_count + _sum + _bucket
      │
      ├── TracingHandler.onStop(context):
      │     │ Span.end()
      │     │ Sends completed span to Zipkin/Jaeger/Tempo collector
      │     │ Span data: traceId, spanId, parentSpanId, startTime, duration,
      │     │             name, tags (method, status, uri), remoteEndpoint
      │
      └── LoggingHandler.onStop(context):
            │ MDC.remove("traceId")
            │ MDC.remove("spanId")
            │ Logs: "STOP GET /api/orders/12345 duration=45ms"
```

### Prometheus Scrape Behavior

```
PROMETHEUS SERVER CONFIGURATION:

scrape_configs:
  - job_name: 'payment-api'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    scrape_timeout: 10s
    static_configs:
      - targets: ['payment-api-1:8080', 'payment-api-2:8080']

SCRAPE CYCLE (every 15 seconds):

T+0s    Prometheus opens HTTP connection to payment-api-1:8080
        GET /actuator/prometheus HTTP/1.1
        Host: payment-api-1:8080

T+0.1s  PrometheusMeterRegistry.scrape() called
        │ Iterates ALL registered meters (could be thousands)
        │ For each meter:
        │   Converts to Prometheus text format (HELP, TYPE, metric lines)
        │ Appends exemplars if enabled

T+0.5s  Response returned: text/plain; version=0.0.4
        Content-Length: ~500KB (depends on meter count)
        │ Prometheus parses response
        │ Stores time series in TSDB
        │ Time series: {__name__="..", job="payment-api", uri=".."} = value @ timestamp

T+15s   Next scrape cycle begins

MEMORY IMPLICATIONS:
  - Each meter creates a time series in Prometheus TSDB
  - 1000 meters × 1 byte per sample × 1 sample per 15s × 4 per minute × 60 × 24
    = minimal per-sample storage
  - BUT high-cardinality tags cause explosion:
    1000 userIds × 50 metrics × 1 sample/15s = 50,000 new time series
    → Prometheus memory usage grows unboundedly
```

## 5. Request Flow Diagrams

### Distributed Trace Propagation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      DISTRIBUTED TRACE FLOW                                  │
│                                                                              │
│  CLIENT                                                                      │
│  │  GET /api/orders?userId=42                                                │
│  │  (no trace headers — starts new trace)                                    │
│  v                                                                           │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ API GATEWAY                                                              │ │
│ │                                                                           │ │
│ │  Receives request, starts new trace:                                      │ │
│ │    traceId:  a1b2c3d4e5f67890                                             │ │
│ │    spanId:   f1e2d3c4b5a6     (root span)                                 │ │
│ │                                                                           │ │
│ │  Forwards to payment-api with headers:                                    │ │
│ │    traceparent: 00-a1b2c3d4e5f67890-f1e2d3c4b5a6-01                       │ │
│ │    tracestate: vendor=abc123                                               │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│  │                                                                           │
│  v                                                                           │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ PAYMENT-API (Service A)                                                   │ │
│ │                                                                           │ │
│ │  Extracts trace from headers:                                             │ │
│ │    traceId: a1b2c3d4e5f67890                                              │ │
│ │    parentSpanId: f1e2d3c4b5a6                                              │ │
│ │    Creates child span: "POST /api/payments"                                │ │
│ │      spanId: 1234567890abcdef                                              │ │
│ │                                                                           │ │
│ │  Calls inventory-service with propagated context:                         │ │
│ │                                                                           │ │
│ │ ┌───────────────────────────────────────────────────────────────────────┐ │ │
│ │ │ INVENTORY-SERVICE (Service B)                                          │ │ │
│ │ │                                                                         │ │ │
│ │ │  Extracts trace from headers:                                          │ │ │
│ │ │    traceId: a1b2c3d4e5f67890                                            │ │ │
│ │ │    parentSpanId: 1234567890abcdef                                        │ │ │
│ │ │    Creates child span: "GET /api/stock/{productId}"                     │ │ │
│ │ │      spanId: fedcba0987654321                                            │ │ │
│ │ │                                                                         │ │ │
│ │ │  Calls PostgreSQL:                                                      │ │ │
│ │ │    Span "SELECT FROM inventory" with link to parent                     │ │ │
│ │ │                                                                         │ │ │
│ │ │  Returns 200 OK                                                 │ │ │
│ │ │  Ends span fedcba0987654321 (duration: 12ms)                            │ │ │
│ │ └───────────────────────────────────────────────────────────────────────┘ │ │
│ │                                                                           │ │
│ │  Returns 200 OK                                                   │ │
│ │  Ends span 1234567890abcdef (duration: 45ms)                              │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  Final trace (viewed in Jaeger/Tempo):                                       │
│                                                                              │
│  traceId: a1b2c3d4e5f67890                                                   │
│  ├─ API Gateway: GET /api/orders        [0ms → 50ms]                        │
│  │  └─ payment-api: POST /api/payments  [2ms → 47ms]                        │
│  │     └─ inventory-api: GET /api/stock [5ms → 17ms]                        │
│  │        └─ PostgreSQL: SELECT ...      [6ms → 14ms]                        │
│  └── complete trace, 4 services, 1 crossing process boundary                │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 6. Lifecycle Diagrams

### Observation Lifecycle

```
OBSERVATION LIFECYCLE (for a single @Observed method):

CREATION:
  Observation.createNotStarted("payment.process", registry)
    .lowCardinalityKeyValue("tenantId", "acme-corp")  // tags for metrics
    .highCardinalityKeyValue("orderId", "ORD-12345")   // baggage for traces
    .contextualName("ProcessPayment for order ORD-12345")  // span name

START:
  Observation.start()
    │
    ├── [1] ObservationHandler.onStart(context)   — for ALL registered handlers
    │       │
    │       ├── MeterHandler:
    │       │     Sample = Timer.start()
    │       │     If @Timed annotation present:
    │       │       creates Timer "payment.process" with low-cardinality key-values
    │       │
    │       ├── TracingHandler:
    │       │     Gets current span from ThreadLocal context
    │       │     Creates child span with name = contextualName
    │       │     Sets span as current (SpanInScope pattern)
    │       │
    │       └── LoggingHandler:
    │             Logs: "START payment.process [tenantId=acme-corp, orderId=ORD-12345]"

STOP (success):
  Observation.stop()
    │
    ├── [2] ObservationHandler.onStop(context)
    │       │
    │       ├── MeterHandler:
    │       │     Sample.stop(Timer) → records duration in time window
    │       │     Timer.record() appends to histogram
    │       │     counter("payment.process.success").increment()
    │       │
    │       ├── TracingHandler:
    │       │     Span.end() → sends completed span to collector
    │       │     Restores parent span as current
    │       │
    │       └── LoggingHandler:
    │             Logs: "STOP payment.process duration=23ms result=SUCCESS"

ERROR (failure):
  Observation.error(exception)
  Observation.stop()
    │
    ├── [2a] ObservationHandler.onError(context)
    │       │
    │       ├── MeterHandler:
    │       │     counter("payment.process.error", "exception", ex.getClass()).increment()
    │       │
    │       ├── TracingHandler:
    │       │     Span.error(exception)
    │       │     Span.tag("error", "true")
    │       │     Span.tag("exception.message", exception.getMessage())
    │       │     Span.end()
    │       │
    │       └── LoggingHandler:
    │             Logs: "ERROR payment.process exception=PaymentDeclinedException ..."
```

### Prometheus Metric Lifecycle

```
COUNTER LIFECYCLE:
  Creation: Counter counter = Counter.builder("orders.created")
                .tag("status", "CONFIRMED")
                .description("Number of orders created")
                .register(meterRegistry);

  Registration:
    │ MeterRegistry.meterMap.putIfAbsent(id, counter)
    │ PrometheusMeterRegistry creates:
    │   PrometheusCounter with child for each tag combination

  Increment:
    │ counter.increment()
    │   → PrometheusCounter.increment()
    │   → doubleAdder.add(1)  // Striped64 for concurrent counting
    │   → NO lock contention, lock-free accumulation

  Scrape (every 15s):
    │ PrometheusMeterRegistry.scrape()
    │   → PrometheusCounter.write(sb)
    │   → sb.append("orders_created_total{status=\"CONFIRMED\"} ").append(count)
    │   → Returns text/plain; version=0.0.4
    │   → Prometheus reads count, stores as time series
    │
    │ Next scrape: reads SAME metric, different timestamp
    │ → PromQL: rate(orders_created_total[5m]) gives per-second rate

TIMER LIFECYCLE:
  Recording: Timer.Sample sample = Timer.start();
             // ... do work ...
             sample.stop(Timer.builder("orders.processing.time"));

  Internal storage:
    │ Timer uses TimeWindowHistogram (HdrHistogram implementation)
    │ Values are accumulated in buckets:
    │   bucket[0..4]: counts for values 0..10ms, 10..50ms, 50..100ms, 100..500ms
    │   bucket[5]: count for +Inf (total)
    │
    │ On each record:
    │   histogram.recordValue(durationNanos)
    │     → finds appropriate bucket
    │     → atomically increments bucket counter

  Scrape:
    │ PrometheusTimer.write(sb):
    │   sb.append("orders_processing_time_seconds_bucket{le=\"0.01\"} ").append(bucket0)
    │   sb.append("orders_processing_time_seconds_bucket{le=\"0.05\"} ").append(bucket1)
    │   sb.append("orders_processing_time_seconds_bucket{le=\"0.1\"} ").append(bucket2)
    │   sb.append("orders_processing_time_seconds_bucket{le=\"+Inf\"} ").append(bucketN)
    │   sb.append("orders_processing_time_seconds_count ").append(totalCount)
    │   sb.append("orders_processing_time_seconds_sum ").append(totalSum)

  PromQL for p99 latency:
    histogram_quantile(0.99,
      rate(orders_processing_time_seconds_bucket[5m])
    )
```

## 7. Source Code Reading Guide

### Micrometer Core Source

```
Core Abstractions:
  ✅ io.micrometer.core.instrument.MeterRegistry
     └── Counter, Gauge, Timer, DistributionSummary creation + management
     └── ConcurrentHashMap<Id, Meter> meterMap
     └── Where: micrometer-core

  ✅ io.micrometer.core.instrument.simple.SimpleMeterRegistry
     └── In-memory implementation (for testing/development)
     └── Where: micrometer-core

  ✅ io.micrometer.core.instrument.Timer
     └── Builder pattern, record(), start().stop()
     └── Uses TimeWindowHistogram internally
     └── Where: micrometer-core

Prometheus Integration:
  ✅ io.micrometer.prometheus.PrometheusMeterRegistry
     └── scrape() — converts meters to Prometheus text format
     └── Registry for Prometheus collector model
     └── Where: micrometer-registry-prometheus

  ✅ io.micrometer.prometheus.PrometheusConfig
     └── Configuration properties (step, descriptions, histogram flavor)
     └── Where: micrometer-registry-prometheus

Observation API:
  ✅ io.micrometer.observation.ObservationRegistry
     └── Central registry for ObservationHandlers
     └── Where: micrometer-observation

  ✅ io.micrometer.observation.Observation
     └── Context, start/stop/error lifecycle
     └── Where: micrometer-observation

  ✅ io.micrometer.observation.aop.ObservedAspect
     └── @Observed annotation support via AOP
     └── Where: micrometer-observation

Spring Boot Actuator Integration:
  ✅ org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration
     └── Automatically configures MeterRegistry
     └── Where: spring-boot-actuator-autoconfigure

  ✅ org.springframework.boot.actuate.autoconfigure.metrics.web.servlet.WebMvcMetricsAutoConfiguration
     └── Configures ServerHttpObservationFilter for MVC
     └── Decorates DispatcherServlet with observation
     └── Where: spring-boot-actuator-autoconfigure

Tracing Integration:
  ✅ io.micrometer.tracing.Tracer (Micrometer Tracing API)
     └── Bridge interface for Brave or OpenTelemetry
     └── Where: micrometer-tracing

  ✅ io.micrometer.tracing.brave.bridge.BraveTracer
     └── Brave implementation of Micrometer Tracer
     └── Where: micrometer-tracing-bridge-brave

  ✅ io.micrometer.tracing.otel.bridge.OtelTracer
     └── OpenTelemetry implementation of Micrometer Tracer
     └── Where: micrometer-tracing-bridge-otel
```

### Reading Order
1. `SimpleMeterRegistry` — understand meter storage (simplest implementation)
2. `Timer.Builder.register()` — understand how meters are registered
3. `Observation.start()` + `Observation.stop()` — understand the unified lifecycle
4. `PrometheusMeterRegistry.scrape()` — understand how metrics are serialized
5. `ServerHttpObservationFilter` — understand HTTP request instrumentation
6. `BraveTracer` or `OtelTracer` — understand trace/span management

### Key Code Snippets Worth Reading

```java
// 1. How Spring Boot wires the ObservationRegistry
// Source: org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration

@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
public class ObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }
    // The ObservationRegistry is created as a simple in-memory registry.
    // ObservationHandlers are added later by other auto-configurations.
}

// 2. How MeterHandler converts Observation to Metrics
// Source: io.micrometer.core.instrument.observation.DefaultMeterObservationHandler

public class DefaultMeterObservationHandler implements MeterObservationHandler<Observation.Context> {

    @Override
    public void onStart(Observation.Context context) {
        // Create a Timer.Sample to capture start time
        Timer.Sample sample = Timer.start(meterRegistry);
        context.put(Timer.Sample.class, sample);
    }

    @Override
    public void onStop(Observation.Context context) {
        Timer.Sample sample = context.get(Timer.Sample.class);
        if (sample != null) {
            // Build Timer with name and tags from context
            Timer timer = Timer.builder(context.getName())
                .tags(convertLowCardinalityKeys(context))
                .register(meterRegistry);
            sample.stop(timer);  // Records duration in histogram
        }
    }
}

// 3. How the span is propagated over HTTP
// Source: ObservationRegistry with TracingHandler
// When making an outbound HTTP call, the tracing interceptor:
// a) Gets the current Span from ThreadLocal (tracer.currentSpan())
// b) Creates a new child Span for the HTTP call
// c) Injects trace headers (traceparent) into the outgoing request
// d) The downstream service extracts trace headers and creates a child Span

// The injection uses W3C TraceContext format (by default):
// traceparent: 00-{traceId}-{spanId}-{traceFlags}
// Where:
//   traceId = 32 hex chars (128-bit TraceID)
//   spanId = 16 hex chars (64-bit SpanID)
//   traceFlags = 2 hex chars (01 = sampled, 00 = not sampled)

// 4. Prometheus serialization — how Timer becomes Prometheus histogram
// Source: io.micrometer.prometheus.PrometheusTimer

class PrometheusTimer extends PrometheusCollector implements Timer {
    private final PrometheusHistogram histogram;

    @Override
    public void record(long amount, TimeUnit unit) {
        double seconds = (double) unit.toNanos(amount) / TimeUnit.SECONDS.toNanos(1);
        // Each record updates:
        // 1. TimeWindowHistogram internal bucket counters (for cumulative output)
        // 2. Or Histogram (direct Prometheus style, for native histograms)
        histogram.observe(seconds);
    }

    // When Prometheus scrapes, this generates:
    // http_server_requests_seconds_bucket{le="0.01",uri="/"} 0.0
    // http_server_requests_seconds_bucket{le="0.05",uri="/"} 15.0
    // ...
    // http_server_requests_seconds_bucket{le="+Inf",uri="/"} 200.0
    // http_server_requests_seconds_count{uri="/"} 200.0
    // http_server_requests_seconds_sum{uri="/"} 12.5
}

// 5. Observation naming convention and hierarchy
// Observations form a hierarchy via parent-child relationships.
// The parent Observation is stored in the Context, and child Observations
// automatically inherit the parent's trace context.

// Example: Nested observations
@Observed(name = "order.process",
          contextualName = "processOrder #%s")
public Order processOrder(String orderId) {
    // This creates Observation "order.process" 
    // with contextualName "processOrder #12345"
    
    // Inside, we call other @Observed methods:
    validateOrder(orderId);  // "order.validate" — child of "order.process"
    chargePayment(orderId);  // "payment.charge" — child of "order.process"
    
    // The trace shows:
    // processOrder #12345 [50ms]
    //   ├── order.validate [5ms]
    //   └── payment.charge [30ms]
    //       └── HTTP POST /gateway [25ms]
    // All share the same traceId
}
```

## 8. Production Failure Scenarios

### Scenario 1: Metric Cardinality Explosion

**Symptom:**
- Prometheus memory usage grows 10x overnight
- Prometheus scraping frequency drops (scrape timeouts)
- `/actuator/prometheus` response size grows from 500KB to 50MB
- AlertManager fires "PrometheusTargetDown" because scrape timeouts

**Investigation:**

```bash
# Check Prometheus target status
curl http://prometheus:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="payment-api")'

# Check scrape duration
curl 'http://prometheus:9090/api/v1/query?query=scrape_duration_seconds{job="payment-api"}' | jq .
# Returns: 12s — way above 10s scrape_timeout!

# Check which metrics have the most time series
curl 'http://prometheus:9090/api/v1/query?query=count({__name__=~".+",job="payment-api"}) by (__name__)' | jq .

# Check the /actuator/prometheus response size
curl -s http://payment-api:8080/actuator/prometheus | wc -c
# Returns: 52,428,800 (~50MB)

# Check for high-cardinality tag values
curl -s http://payment-api:8080/actuator/prometheus | grep "userId" | wc -l
# Returns: 450,000 — one time series per userId!

# Root cause: A developer added userId as a tag to a metric
@Timed(value = "user.operation", extraTags = {"userId", "#userId"})
public void processUser(String userId) { ... }
```

**Fix:**
```java
// NEVER use high-cardinality values as metric tags
// Tags should have bounded cardinality: method, status, outcome, tenantId (if < 50 tenants)

// CORRECT: userId goes in the trace/log, NOT in the metric
@Observed(name = "user.operation",
    contextualName = "processUser",
    highCardinalityKeyValues = {"userId", "#userId"})  // Only in span, not in metrics
public void processUser(String userId) {
    // Metrics: user.operation.count{status=SUCCESS}  (low cardinality)
    // Trace:   Span "processUser" with tag userId=abc123  (per-request)
}
```

### Scenario 2: Health Check Cascade Failure

**Symptom:**
- All 4 instances simultaneously marked unhealthy
- No application errors
- Health check shows: `{"status":"DOWN","components":{"db":{"status":"UP"},"inventory":{"status":"DOWN"}}}`

**Root Cause:** A custom `HealthIndicator` for the inventory service was added to the default health group. When the inventory service degraded, ALL health indicators (including the one for the default group used by the load balancer) reported DOWN. The Liveness check should only report on the JVM process, not external dependencies. But the default group, which the LB used, now included the inventory check.

**Fix:**
```yaml
# Separate liveness and readiness
management:
  endpoint:
    health:
      probes:
        enabled: true  # Spring Boot 2.3+ native k8s probes
      group:
        readiness:
          include: db, inventory, redis  # External dependencies
        liveness:
          include: ping  # Only local: disk space, JVM health
```

### Scenario 3: Missing Trace Propagation

**Symptom:**
- Traces in Jaeger show disconnected segments
- `payment-api` span shows as root span, not as child of API Gateway span
- Cannot correlate gateway request with backend processing

**Root Cause:** The team added Micrometer Tracing to the backend but forgot to configure trace header propagation in the API Gateway. The gateway received the trace headers but didn't forward them.

**Fix:**
```yaml
# Spring Cloud Gateway
spring:
  cloud:
    gateway:
      default-filters:
        - TracePropagation  # Built-in filter

# Or for RestClient:
@Bean
public RestClient restClient(RestClient.Builder builder) {
    return builder
        .requestInterceptor(new ObservationRestClientInterceptor(
            observationRegistry))
        .build();
}
```

## 9. Debugging Techniques

### Debugging Missing Metrics

```bash
# 1. Check what metrics are registered
curl -s http://localhost:8080/actuator/metrics | jq '.names[]'

# 2. Get detailed info on a specific metric
curl -s http://localhost:8080/actuator/metrics/http.server.requests | jq .

# Returns:
# {
#   "name": "http.server.requests",
#   "description": "Duration of HTTP server request handling",
#   "baseUnit": "seconds",
#   "measurements": [
#     {"statistic": "COUNT", "value": 12450},
#     {"statistic": "TOTAL_TIME", "value": 2456.7},
#     {"statistic": "MAX", "value": 5.2}
#   ],
#   "availableTags": [
#     {"tag": "method", "values": ["GET", "POST", "PUT"]},
#     {"tag": "status", "values": ["200", "201", "400", "404", "500"]},
#     {"tag": "uri", "values": ["/api/orders", "/api/payments", "/actuator/health"]}
#   ]
# }

# 3. Check if @Timed is working
# Ensure spring.aop.proxy-target-class=true is set
# @Timed uses AOP — if your class is not proxied, timing won't work

# 4. Enable Micrometer debug logging
logging.level.io.micrometer=DEBUG
# Watch for: "Failed to register meter" or "Rejected meter with same name"

# 5. Check MeterFilter configuration — they can silently drop metrics
@Bean
public MeterFilter myFilter() {
    return MeterFilter.deny(id -> {
        // This silently drops all metrics matching the predicate
        return id.getName().startsWith("jvm.");
    });
}
```

### Debugging Missing Traces

```bash
# 1. Check if tracing is enabled
curl -s http://localhost:8080/actuator/conditions | jq '.contexts.application.positiveMatches | keys[]' | grep -i trace

# 2. Check tracing configuration
management.tracing.sampling.probability=1.0  # 100% sampling for debugging

# 3. Check trace headers in request
curl -v http://localhost:8080/api/orders
# Look for response header: traceparent or X-B3-TraceId (Brave)

# 4. Check if spans are being sent to the collector
logging.level.io.micrometer.tracing=TRACE
logging.level.zipkin2.reporter=TRACE
# Watch for: "Sending span" or connection errors to collector

# 5. Verify W3C TraceContext propagation is enabled
management.tracing.propagation.type=w3c  # Default is W3C
```

## 10. Observability Considerations

### SLOs, SLIs, and Error Budgets

```
SLO DEFINITION FOR PAYMENT-API:

SLI (Service Level Indicator):
  The proportion of successful requests over total requests within a time window.

  PromQL (for 99.9% availability SLO):
    sum(rate(http_server_requests_seconds_count{status!~"5..",uri=~"/api/.*"}[30d]))
    /
    sum(rate(http_server_requests_seconds_count{uri=~"/api/.*"}[30d]))

SLO (Service Level Objective):
  99.9% of requests succeed over a 30-day rolling window.

Error Budget:
  Allowed failures = total_requests × (1 - SLO)
  If SLO = 99.9% and 1,000,000 requests/day:
    Error budget = 1,000,000 × 0.001 = 1,000 failures/day

Burn Rate:
  How fast are we consuming the error budget?

  If error rate is 1% over 1 hour (10× the budgeted rate):
    Burn rate = 10x (will exhaust monthly budget in 3 days)

  Alerting thresholds:
    Burn rate > 1x over 1 hour  → Page (SEV1)
    Burn rate > 2x over 6 hours → Page
    Burn rate > 10x over 3 days → Ticket
```

### Prometheus AlertManager Rules

```yaml
# prometheus-rules.yaml

groups:
  - name: payment-api-slos
    interval: 30s
    rules:
      # High burn rate alert (1h window, 14.4x burn rate → critical)
      - alert: SLOBurnRateCritical
        expr: |
          (
            sum(rate(http_server_requests_seconds_count{status=~"5..",service="payment-api"}[1h]))
            /
            sum(rate(http_server_requests_seconds_count{service="payment-api"}[1h]))
          ) > 14.4 * 0.001
        for: 2m
        labels:
          severity: critical
          team: payment
        annotations:
          summary: "Payment API SLO burn rate critical (1h window)"
          description: "Error budget being consumed at {{ $value | humanizePercentage }} rate. Budget exhaustion in ~2h."
          runbook: https://wiki.company.com/runbooks/payment-api-slo-burn

      # Error rate spike
      - alert: HighErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5..",service="payment-api"}[5m]))
          /
          sum(rate(http_server_requests_seconds_count{service="payment-api"}[5m]))
          > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Payment API error rate > 5% for 5 minutes"

      # p99 latency breach
      - alert: HighLatency
        expr: |
          histogram_quantile(0.99,
            sum(rate(http_server_requests_seconds_bucket{service="payment-api"}[5m]))
            by (le)
          ) > 3
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Payment API p99 latency > 3s for 5 minutes"

      # Thread pool approaching saturation
      - alert: TomcatThreadPoolSaturated
        expr: |
          tomcat_threads_busy_threads{service="payment-api"}
          /
          tomcat_threads_config_max_threads{service="payment-api"}
          > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Tomcat thread pool > 80% busy"

      # HikariCP connection pending
      - alert: HikariCPConnectionPending
        expr: |
          hikaricp_connections_pending{pool="HikariPool-1",service="payment-api"} > 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "HikariCP connections pending — pool may be exhausted"
```

## 11. Performance Implications

### Metric Collection Overhead

```
OVERHEAD ANALYSIS (measured on 8-core, 16GB RAM, 200 TPS workload):

Baseline (no Micrometer):                        CPU: 15%, p99: 45ms
With Micrometer + Actuator (no @Timed):           CPU: 16%, p99: 47ms  (+4% overhead)
With @Timed on every controller method (20 endpoints):  CPU: 18%, p99: 52ms  (+15% overhead)
With @Timed on every @Service method (50 methods):     CPU: 24%, p99: 68ms  (+51% overhead!)

Recommendations:
  1. @Timed on controllers (5-20 timers): negligible overhead (~5%)
  2. @Timed on every service method (50+ timers): measurable overhead (~20-50%)
  3. Timer.Sample per custom measurement: same cost as @Timed
  4. Counter.increment(): extremely cheap (~10ns) — use freely
  5. Gauge with lambda supplier: supplier called on EVERY scrape
     → Expensive gauge suppliers cause scrape timeouts

// BAD: Gauge supplier makes an HTTP call
Gauge.builder("inventory.stock", () ->
    restClient.get().uri("/api/stock/total").bodyToMono(Long.class).block()
).register(meterRegistry);  // Blocking HTTP call every 15s on scrape!

// GOOD: Asynchronously updated gauge
AtomicLong stockCache = new AtomicLong();
@Scheduled(fixedDelay = 30000)
public void updateStockGauge() {
    stockCache.set(restClient.get()...block());
}
Gauge.builder("inventory.stock", stockCache::get).register(meterRegistry);
```

### Prometheus Histogram Bucket Tuning

```
Default Micrometer histogram buckets (for http.server.requests):
  [0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300, +Inf]

These 15 buckets work for most web applications BUT:
  - If your p99 is 50ms, 5 of 15 buckets are at 1s+ (waste)
  - If your p99 is 500ms, the first 5 buckets are below p50 (waste)
  - Each bucket creates 15 time series per tag combination

Customize for your service:
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.metrics.distribution.slo.http.server.requests=1ms,5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s,2.5s,5s,10s

// In Java, per-meter customization:
@Bean
public MeterRegistryCustomizer<MeterRegistry> metricsCustomizer() {
    return registry -> registry.config().meterFilter(
        new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id,
                    DistributionStatisticConfig config) {
                if (id.getName().equals("custom.operation.time")) {
                    return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .minimumExpectedValue(Duration.ofMillis(1).toNanos())
                        .maximumExpectedValue(Duration.ofSeconds(10).toNanos())
                        .build()
                        .merge(config);
                }
                return config;
            }
        });
}

Cost calculation:
  1 timer with 10 buckets + 4 tag values (method/status/uri/outcome)
  10 (buckets) × 2 (methods) × 3 (uris) × 5 (statuses) × 1 (outcome)
  = 300 time series for ONE timer

  With 20 endpoints → 6000 time series
  Prometheus memory: ~3KB per time series → ~18MB for this one metric
```

## 12. Architecture Implications

### Agent-Based vs Library-Based Instrumentation

```
┌────────────────────────────────────────────────────────────────────────┐
│              AGENT-BASED (OpenTelemetry Java Agent)                      │
│                                                                          │
│  java -javaagent:opentelemetry-javaagent.jar -jar app.jar               │
│                                                                          │
│  PROS:                                                                   │
│    ✅ Zero code changes — auto-instruments 50+ libraries                │
│    ✅ Consistent across all services                                    │
│    ✅ Auto-instruments: Tomcat, HikariCP, JDBC, Redis, Kafka, gRPC     │
│    ✅ No dependency conflicts with Micrometer                           │
│                                                                          │
│  CONS:                                                                   │
│    ❌ Limited to known library instrumentation points                   │
│    ❌ Business metrics need custom spans (still needs code)             │
│    ❌ Agent version upgrades are Java agent upgrades, not app releases  │
│    ❌ Harder to customize span names and attributes                     │
│                                                                          │
├────────────────────────────────────────────────────────────────────────┤
│              LIBRARY-BASED (Micrometer Tracing + Brave/OTel)            │
│                                                                          │
│  implementation 'io.micrometer:micrometer-tracing-bridge-otel'          │
│                                                                          │
│  PROS:                                                                   │
│    ✅ Full control over sampling, span naming, attributes               │
│    ✅ @Observed provides business-level instrumentation                │
│    ✅ Unified API for both metrics and tracing                          │
│    ✅ Version managed with application dependencies                     │
│                                                                          │
│  CONS:                                                                   │
│    ❌ Manual instrumentation for custom code paths                     │
│    ❌ Dependency conflicts possible (especially Brave vs OTel)         │
│    ❌ Every service must add the same libraries                         │
│                                                                          │
├────────────────────────────────────────────────────────────────────────┤
│              RECOMMENDED: HYBRID APPROACH                                │
│                                                                          │
│  1. OTel Java Agent for infrastructure metrics:                        │
│     - Tomcat, HikariCP, JDBC, gRPC, Kafka auto-instrumentation          │
│     - Zero code changes for basic distributed tracing                   │
│                                                                          │
│  2. Micrometer for custom business metrics:                             │
│     - @Timed on controller methods                                      │
│     - Custom Counters/Gauges via MeterBinder                            │
│     - Observation API for complex workflows                             │
│                                                                          │
│  3. Both export to the same OTLP collector:                             │
│     - OTel Agent → OTLP exporter → otel-collector                       │
│     - Micrometer → OTLP exporter → otel-collector                       │
│     - Same backend (Tempo, Jaeger) for all traces                       │
└────────────────────────────────────────────────────────────────────────┘
```

### Sampling Strategies

```
HEAD-BASED SAMPLING (decision made at trace root):

  The sampling decision is made when the trace starts, before any spans
  are collected. All child spans inherit the decision.

  Pros:
    ✅ Simple, low overhead (entire trace kept or dropped)
    ✅ Works with any collector (no tail coordination needed)

  Cons:
    ❌ May miss rare errors (error at 0.1% rate, sampled at 1% → missed)
    ❌ May keep boring traces (fast, successful requests)

  Configuration:
    management.tracing.sampling.probability=0.01  # 1% sample rate

TAIL-BASED SAMPLING (decision made after trace completes):

  The entire trace is buffered, and the decision to keep or drop is made
  after the trace completes, based on the trace data.

  Pros:
    ✅ Keeps ALL error traces, ALL slow traces
    ✅ Drops fast, successful traces (what you want to discard)

  Cons:
    ❌ Requires a tail-sampling capable collector (Grafana Tempo, Honeycomb)
    ❌ Collector must buffer spans (memory overhead)
    ❌ Not available in pure library implementations

  Configuration (OTel Collector):
    processors:
      tail_sampling:
        decision_wait: 10s
        policies:
          - name: errors
            type: status_code
            status_code: {status_codes: [ERROR]}
          - name: latency
            type: latency
            latency: {threshold_ms: 1000}
```

## 13. Team Ownership Implications

### Observability Ownership Model

```
OBSERVABILITY STACK OWNERSHIP:

┌─────────────────────────────────────────────────────────────────┐
│ SERVICE TEAM OWNS                                                │
│                                                                  │
│  ✅ Metric definitions (what metrics, what tags, what buckets)   │
│  ✅ Custom MeterBinders (business metrics)                       │
│  ✅ @Timed / @Observed placement (which methods to instrument)   │
│  ✅ Health indicator customization (what's critical)             │
│  ✅ SLO definitions (what is the reliability target)             │
│  ✅ Alert thresholds (when to page)                              │
│  ✅ Dashboards (what the team needs to see during on-call)       │
│  ✅ Runbook linking (alerts → runbooks)                          │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│ PLATFORM / OBSERVABILITY TEAM OWNS                               │
│                                                                  │
│  ✅ Prometheus infrastructure (HA, storage, retention)           │
│  ✅ Grafana infrastructure (authentication, provisioning)        │
│  ✅ AlertManager configuration (routing, inhibition rules)       │
│  ✅ OTel Collector infrastructure (pipelines, tail sampling)     │
│  ✅ Trace backend (Tempo/Jaeger — scaling, retention)            │
│  ✅ Log aggregation (Loki/ELK — ingestion, storage)              │
│  ✅ Base dashboards (JVM, Tomcat, Spring Boot defaults)          │
│  ✅ SDK/libraries for standardized instrumentation               │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│ SHARED RESPONSIBILITY                                            │
│                                                                  │
│  ✅ Naming conventions for metrics and spans                     │
│  ✅ Cardinality limits (no userId in metric tags)                │
│  ✅ Sampling strategy (coordinated across services)              │
│  ✅ Correlation IDs (traceId in logs, requestId in responses)    │
│  ✅ Alert routing and on-call escalation policies                │
└─────────────────────────────────────────────────────────────────┘
```

### Dashboard Design Standards

```
REQUIRED DASHBOARDS PER SERVICE:

1. RED Dashboard (Rate, Errors, Duration):
   ┌──────────────────────────────────────────────────────┐
   │ Row 1: Request Rate                                   │
   │   rate(http_server_requests_seconds_count[5m]) by uri │
   │                                                       │
   │ Row 2: Error Rate                                     │
   │   rate(http_server_requests_seconds_count{status~5..} │
   │         [5m]) by uri                                  │
   │                                                       │
   │ Row 3: Latency (p50, p95, p99)                       │
   │   histogram_quantile(0.99, rate(..._bucket[5m]))     │
   │   by uri                                              │
   │                                                       │
   │ Row 4: Error Budget Remaining                         │
   │   (1 - error_rate) / SLO_remaining                    │
   └──────────────────────────────────────────────────────┘

2. USE Dashboard (Utilization, Saturation, Errors):
   ┌──────────────────────────────────────────────────────┐
   │ Row 1: JVM Heap                                       │
   │   jvm_memory_used_bytes / jvm_memory_max_bytes        │
   │                                                       │
   │ Row 2: Tomcat Threads                                 │
   │   tomcat_threads_busy / tomcat_threads_config_max     │
   │                                                       │
   │ Row 3: HikariCP Connections                           │
   │   hikaricp_connections_active / hikaricp_connections  │
   │         _max                                           │
   │                                                       │
   │ Row 4: GC Pause Time                                  │
   │   rate(jvm_gc_pause_seconds_sum[5m]) /                │
   │   rate(jvm_gc_pause_seconds_count[5m])                │
   │                                                       │
   │ Row 5: File Descriptors                               │
   │   process_files_open_files / process_files_max_files  │
   └──────────────────────────────────────────────────────┘

3. Business Dashboard:
   ┌──────────────────────────────────────────────────────┐
   │ Row 1: Business KPI (orders created, payments)        │
   │   Custom counters/gauge                               │
   │                                                       │
   │ Row 2: Business Error Rate                            │
   │   Payment declines, validation failures               │
   │                                                       │
   │ Row 3: Revenue Metrics (if applicable)                │
   │   Sum of payment amount gauge                         │
   └──────────────────────────────────────────────────────┘
```

## 14. Interview Questions

### Question 1: "Explain how a @Timed annotation on a @RestController method results in a Prometheus histogram metric. Walk through the entire chain from annotation to scrape."

**Staff-level answer:**

The chain has five stages:

**Stage 1 — AOP interception.** `@Timed` is intercepted by `TimedAspect`, which is an `@Aspect` bean auto-configured by `MetricsAutoConfiguration`. When the controller method is invoked, Spring's AOP proxy intercepts the call and routes to `TimedAspect.timedMethod(ProceedingJoinPoint)`. The aspect reads the `@Timed` annotation's value (metric name) and any `extraTags`. It creates a `Timer.Sample` by calling `Timer.start(meterRegistry)`, which captures `System.nanoTime()` as the start timestamp. It then calls `pjp.proceed()` to invoke the actual controller method. In a `finally` block, it calls `sample.stop(Timer.builder(name).tags(tags).register(meterRegistry))`.

**Stage 2 — Timer registration.** `Timer.Builder.register(meterRegistry)` constructs a `Meter.Id` from the name and tags. It calls `MeterRegistry.registerMeterIfNeeded(id, Timer.class)`, which checks `meterMap.putIfAbsent(id, ...)` — a `ConcurrentHashMap` operation. If the meter doesn't exist, a new `DefaultTimer` is created with a `TimeWindowHistogram` (or `CumulativeHistogram` for Prometheus). If it exists, the existing timer is returned. This means the first request to a new tag combination registers the meter; subsequent requests reuse it.

**Stage 3 — Recording.** `Timer.record(duration, TimeUnit.NANOSECONDS)` delegates to the histogram, which finds the appropriate bucket for the duration and atomically increments the bucket counter. The histogram also increments a total count (`Adder`) and total duration (`Adder`). For Prometheus, the histogram buckets are configured via `DistributionStatisticConfig`, which reads properties like `management.metrics.distribution.slo` or `percentiles-histogram`.

**Stage 4 — Scrape request.** Every 15 seconds (configurable), Prometheus sends `GET /actuator/prometheus`. Spring's `PrometheusScrapeEndpoint` handles this, calling `prometheusMeterRegistry.scrape()`. The scrape method iterates `collectorMap.values()` and for each `PrometheusTimer`, calls `write(sb)`. The write method appends the `# HELP`, `# TYPE`, and `_bucket`, `_count`, `_sum` lines in Prometheus exposition format.

**Stage 5 — Prometheus ingestion.** Prometheus parses the text response and creates (or updates) time series in its TSDB. Each bucket line becomes a separate time series identified by `{__name__="..._bucket", le="0.1", uri="/api/orders"}`. The value is monotonically increasing (cumulative histogram). PromQL functions like `rate()` and `histogram_quantile()` compute rates and percentiles from these cumulative bucket counts.

The key performance insight: `Timer.record()` involves only a `System.nanoTime()` for elapsed time calculation (if a `Sample` was used) plus an atomic bucket increment. Total overhead is ~100ns — negligible compared to the instrumented code.

### Question 2: "How does distributed trace context propagation work in Micrometer Tracing? What happens when a request arrives without trace headers vs with trace headers?"

**Staff-level answer:**

When a request arrives, the `ServerHttpObservationFilter` (or the older `ServerRequestObservationConvention`) is invoked before the filter chain. Inside the `Observation.start()` lifecycle, the registered `TracingHandler` is called via `onStart(context)`.

**Without trace headers (new trace):** The `TracingHandler` checks the request for `traceparent` and `tracestate` headers (W3C TraceContext format) or `X-B3-TraceId` and `X-B3-SpanId` (B3 format). Finding none, it creates a new trace context: a random 128-bit `traceId` and a random 64-bit `spanId`, with `sampled=true` based on the configured sampling probability. The `traceId` and newly generated `spanId` are set as the current trace context on the thread via a `ThreadLocal` (ScopedSpan or SpanInScope pattern). The span is named from the observation's `contextualName` (e.g., "GET /api/orders/{id}").

**With trace headers (continuing trace):** The `TracingHandler` extracts the `traceparent` header value. The W3C format is `version-traceId-parentSpanId-traceFlags` (e.g., `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`). It parses this to obtain the incoming `traceId`, the incoming `parentSpanId` (which becomes the parent of this service's span), and the `traceFlags` (which indicate whether this trace is sampled). It creates a new child span with a new `spanId`, sets the `traceId` and `parentSpanId` from the incoming trace, and sets it as the current trace context.

For outgoing requests, the reverse happens: when making an HTTP call (via `RestClient`/`WebClient` instrumented with `ObservationRestClientInterceptor`), the current span's context is serialized to the `traceparent` header: `00-{currentTraceId}-{currentSpanId}-01`. The downstream service receives this as the parent.

The thread context propagation uses `ThreadLocal` (for synchronous code) or Reactor's `Context` (for reactive code). With `@Async` methods, the trace context must be explicitly propagated — Spring's `ThreadPoolTaskExecutor` with `setTaskDecorator(new ObservationPropagatingTaskDecorator(registry))` handles this by copying the current observation context to the async thread. Without this decorator, `@Async` methods execute in a new thread with an empty trace context, breaking the distributed trace.

If propagation is configured but NOT working, the most common causes are: (a) `ThreadPoolTaskExecutor` not using `ObservationPropagatingTaskDecorator`, (b) `RestClient` not using the instrumented `ClientHttpRequestFactory`, (c) reactive WebClient not subscribed with the correct Reactor Context (must use `contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation))`).

### Question 3: "Design the observability strategy for a payment processing service with 99.99% availability SLO. What metrics, traces, logs, and alerts do you need?"

**Staff-level answer:**

For a payment processing service with a 99.99% SLO (52 minutes of allowed downtime per year), the observability strategy must detect anomalies in under 2 minutes and provide enough data to root-cause within 5 minutes.

**Metrics (RED + USE + Business):**

RED metrics per endpoint:
- `http_server_requests_seconds` with tags `{uri, method, status, outcome}` — Prometheus histogram with buckets tuned for payment latency (1ms to 30s).
- `http_server_requests_seconds_count{status=~"5.."}` — error count per endpoint.
- Business-specific: `payment_processing_time_seconds` — end-to-end payment processing time across all downstream calls.

USE metrics for saturation:
- `hikaricp_connections_active / hikaricp_connections_max` — connection pool utilization.
- `tomcat_threads_busy / tomcat_threads_config_max` — thread pool utilization.
- `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}` — heap usage.
- `jvm_gc_pause_seconds` — GC pause time histogram.

Business metrics:
- `payments_processed_total{status="approved|declined|error"}` — Counter by outcome.
- `payment_amount_total{currency="USD|EUR"}` — Counter of total payment amounts (compliance use).
- `payment_processing_stage_duration_seconds{stage="validation|fraud_check|gateway_call|confirmation"}` — Timer per stage for bottleneck identification.

**Traces:**

100% sampling for the payment processing critical path (using a custom sampler that checks the URI path). Head-based sampling with probability=0.01 for non-payment endpoints. Traces must propagate through: API Gateway → Payment Service → Fraud Service → Payment Gateway (external) → Confirmation. Each service must propagate W3C TraceContext headers. Trace spans should carry business attributes: `payment.amount`, `payment.currency`, `payment.merchant_id`, `payment.status`, `payment.fraud_score`.

**Logs:**

Structured JSON logs (logstash-logback-encoder) with fields: `timestamp`, `level`, `logger`, `message`, `traceId`, `spanId`, `service`, `userId`, `requestId`, `paymentId`. The `traceId` in log entries enables correlation with traces in Tempo/Jaeger. The `paymentId` enables correlation across ALL log lines for a single payment (from request arrival to gateway response). Log level dynamically adjustable via `/actuator/loggers` without restart.

**Alerts:**

Multi-window, multi-burn-rate alerting:
- 1h window at 14.4x burn rate: critical alert, page on-call immediately. This catches catastrophic failures (error rate > ~1.4%).
- 6h window at 6x burn rate: critical alert, page on-call. Catches sustained degradation.
- 3d window at 1x burn rate: warning ticket. Catches slow SLO erosion.

Additionally:
- `hikaricp_connections_pending > 0` for 2 minutes: critical alert (pool exhaustion imminent).
- `hikaricp_connections_timeout_total rate > 0`: critical alert (connections have already timed out).
- `tomcat_threads_busy / max > 0.85` for 5 minutes: warning.
- `payment_gateway_error_rate > 0.05` for 5 minutes: warning.
- `fraud_service_circuit_breaker_state == OPEN`: critical alert.

**Dashboards:**

- RED dashboard: request rate, error rate, latency percentiles per endpoint.
- USE dashboard: all saturation metrics.
- Business dashboard: payment count, amount, approval rate, decline rate per merchant.
- SLO dashboard: Error budget remaining, burn rate over time, SLO compliance trend.
- Dependency dashboard: latency and error rate for each downstream service.

**Implementation priority order:**
1. Actuator + Prometheus + Grafana (day 1)
2. Structured JSON logging with traceId injection (day 2)
3. RED + USE dashboards with alerts (day 5)
4. Distributed tracing with W3C propagation (day 15)
5. SLO/SLI definition with burn-rate alerts (day 30)
6. Business metrics and dashboards (day 45)

## 15. Hands-On Exercises

1. **Build a custom MeterBinder for business metrics**: Create a `PaymentMetricsMeterBinder` that binds: `payments.total` (Counter, tagged by status with values `approved`, `declined`, `error`), `payments.amount` (DistributionSummary, tagged by currency), and `payments.active` (Gauge showing currently in-flight payment count tracked via an `AtomicInteger` incremented/decremented in a try/finally block). Register it via `@Bean`. Verify the metrics appear on `/actuator/prometheus` in valid Prometheus exposition format. Send 1000 test payments with random outcomes via a load test, then query the `/actuator/metrics/payments.total` endpoint and verify the counter values sum to 1000. Check that the gauge returns to zero when all payments complete. Write a JUnit test using `SimpleMeterRegistry` to assert the counter increments and the gauge updates correctly without needing a running Prometheus instance.

2. **Implement structured JSON logging with trace correlation**: Add `logstash-logback-encoder` dependency. Configure `logback-spring.xml` to output JSON to both stdout and a rolling file. Add custom fields: `service` (from `spring.application.name`), `environment` (from Spring profile), `version` (from `build-info.properties`). Create an `MdcFilter` that extracts `X-Request-Id` from incoming requests (and generates one if absent), extracts `X-User-Id`, and puts them in the MDC. Verify that `traceId` and `spanId` appear automatically in all log lines after adding Micrometer Tracing. Write a test that sends 10 requests, collects the JSON log lines from the file, and uses `jq` to verify: (a) each log line has `requestId`, (b) all 10 lines from the same request share the same `requestId`, (c) `traceId` is present and consistent within a request, (d) `userId` is populated from the header.

3. **Configure a custom health indicator aggregation**: Create a `PaymentGatewayHealthIndicator` that checks the external payment gateway by making an HTTP HEAD request to the gateway's health endpoint. Add it to the `readiness` group only (not `liveness`). Create a `CompositeHealthContributor` that includes: `DataSourceHealthIndicator` (DB), `RedisHealthIndicator` (Redis), and your `PaymentGatewayHealthIndicator`. Configure `management.endpoint.health.show-details=always` and `management.endpoint.health.show-components=always`. Test three scenarios: (a) All healthy — both readiness and liveness return UP, (b) Stop payment gateway mock — readiness returns DOWN, liveness returns UP, (c) Stop PostgreSQL — both readiness and liveness return DOWN. Verify the Kubernetes probe simulation: if readiness is DOWN, the pod should not receive traffic; if only liveness is DOWN, the pod should restart.

4. **Set up end-to-end distributed tracing**: Configure Micrometer Tracing with the OTel bridge (`micrometer-tracing-bridge-otel`). Start a local Tempo/Jaeger instance. Create two Spring Boot services (`order-service` port 8081, `inventory-service` port 8082) that communicate via `RestClient`. Instrument both with `ObservationRegistry`. Send a request from order-service to inventory-service (`GET /api/orders/1` → calls `GET /api/inventory/stock/ABC`). In Jaeger, find the trace and confirm it shows: order-service span (root) → inventory-service span (child) → database query span (grandchild). Add `@Observed(name = "order.validation")` on the `validateOrder()` method and confirm it appears as a nested child span within the order-service span. Add `lowCardinalityKeyValue("tenantId", "acme")` to the observation and verify it appears as a span tag. Change `management.tracing.sampling.probability=0` and confirm NO traces appear. Change it to `1.0` and verify 100% of requests are traced.

5. **Build a Prometheus recording rule and alerting rule**: Install a local Prometheus and configure it to scrape your Spring Boot application's `/actuator/prometheus` endpoint. Define a recording rule that pre-computes `endpoint:http_latency:p99_rate5m` (p99 latency per endpoint, 5-minute rate, summed across instances). Define an alerting rule that fires when this value exceeds 3s for any endpoint. Configure AlertManager with a Slack webhook receiver or a file-based receiver for local testing. Artificially increase latency by adding `Thread.sleep(4000)` in one controller endpoint. Generate traffic with `curl` in a loop. Verify: (a) Prometheus UI shows the recording rule result, (b) The alert fires after `for: 2m`, (c) AlertManager receives the alert, (d) The alert notification includes the trace exemplar link (`traceID` in alert annotations). Tune the `for` duration and verify that brief latency spikes (< `for` duration) do NOT fire the alert (avoiding alert flapping).

6. **Profile Micrometer metric collection overhead**: Create a JMH benchmark that compares the same business logic with and without Micrometer instrumentation. Benchmark: (a) A method that takes 1ms to execute, without any instrumentation, (b) The same method with `@Timed`, (c) The same method with `@Observed`, (d) The same method with a manually created `Timer.Sample` and custom `Counter.increment()`. Report: nanoseconds of overhead per instrumented call. Determine: How many `@Timed` annotations can you add before overhead exceeds 5% of the method's execution time? Use `SimpleMeterRegistry` (no Prometheus text generation overhead) to isolate the instrumentation cost from the scrape cost. Run the benchmark with different histogram implementations (TimeWindowHistogram vs CumulativeHistogram) and compare the recording overhead.

## 16. Advanced Challenges

1. **Build an SLO Compliance Dashboard with Error Budget Burn Alerts**: Implement a Spring Boot microservice that calculates SLO compliance from Prometheus data. The service should: (a) Query Prometheus for monthly error budget using `prometheus_client_java`, (b) Calculate burn rate for 1h, 6h, and 3d windows, (c) Expose an endpoint `GET /slo/status` returning JSON with current error budget %, burn rates, and time-to-exhaustion, (d) Push custom metrics to Prometheus: `slo_error_budget_remaining_ratio`, `slo_burn_rate{window="1h|6h|3d"}`, (e) Generate an SLO report as a PDF using `iText` with weekly budget consumption trends.

2. **Create a "Trace Anomaly Detector"**: Build a Spring Boot service that consumes spans from Kafka (SpanIngest microservice sends spans as messages). The detector analyzes span data using a sliding window: (a) Counts spans per endpoint per minute, (b) Computes latency distribution (p50/p95/p99) per endpoint, (c) Detects anomalies using statistical methods (Z-score > 3 or rate-of-change > 200% from 24-hour baseline). When an anomaly is detected, publish an `AnomalyDetectedEvent` to Kafka with the affected endpoint, the anomalous metric, the expected vs actual value, and a link to the affected trace in the trace viewer.

3. **Implement a "Cost-Aware Sampling" Strategy**: Instead of a fixed sampling rate, implement an adaptive sampler that adjusts sampling probability based on: (a) Error budget remaining — increase sampling when budget is low to capture more data for debugging, (b) Traffic volume — decrease sampling during peak to control metric volume, (c) Path criticality — always sample `/api/payments/*` at 100%. The sampler should be a custom `SamplerFunction<HttpRequest>` wired into ObservationRegistry. It should periodically query the SLO service for current error budget and adjust sampling rate in real-time without restarting.

4. **Build a "Distributed Context Propagation Framework" for custom business context**: Beyond traceId, propagate business context through the distributed system: `merchantId`, `idempotencyKey`, `channel` (mobile/web/api). Implement: (a) A custom `ObservationConvention` that adds these as low-cardinality key-values to every observation, (b) A custom `HttpClientRequestInterceptor` that encodes them in a custom header `X-Business-Context: merchantId=abc;channel=mobile`, (c) A custom filter that extracts them from incoming requests and sets them on the MDC, (d) A `ThreadPoolTaskDecorator` that propagates them to `@Async` threads. Ensure they appear in all log lines, all metric dimensions (as appropriate), and all trace span attributes.

5. **Create an "Observability Health Score" for Services**: Build a scoring system that evaluates each service's observability maturity on a 0-100 scale. Score dimensions: (a) Metrics: has RED metrics? (20pts), USE metrics? (20pts), custom business metrics? (10pts), (b) Traces: distributed tracing enabled? (15pts), trace propagation to all downstream calls? (10pts), (c) Logs: structured JSON? (10pts), traceId in logs? (10pts), (d) SLOs: defined? (5pts), dashboards exist? (5pts), alerts exist? (5pts). The score is computed by querying each service's `/actuator/metrics`, `/actuator/health`, and by checking for trace headers in cross-service calls. Expose the results at `GET /observability/scoreboard` as a ranked leaderboard. Integrate this into the CI/CD pipeline — deployments are blocked if the score drops below 80.

