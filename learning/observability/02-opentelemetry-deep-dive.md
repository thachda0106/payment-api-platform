# Phase 2 — OpenTelemetry Deep Dive

> **Duration**: 1-2 weeks | **Prerequisites**: Phase 1 (Observability Foundations)
>
> **Goal**: Understand OpenTelemetry's architecture, data model, and why it succeeded where OpenTracing and OpenCensus failed.

---

## 2.1 The History: Why OpenTelemetry Exists

### 2.1.1 The Problem Before OpenTelemetry (2015-2019)

Before OpenTelemetry, instrumenting a service meant choosing a vendor's SDK:

```java
// Datadog
import datadog.trace.api.Trace;

// Zipkin
import brave.Span;
import brave.Tracer;

// Jaeger
import io.opentracing.Tracer;

// AWS X-Ray
import com.amazonaws.xray.AWSXRay;
```

**The consequences:**
1. **Vendor lock-in**: Changing from Jaeger to Datadog required re-instrumenting every service. Every import statement changed. Every instrumentation call changed.
2. **No interoperability**: A service instrumented with Zipkin could not propagate traces to a service instrumented with Jaeger — different header formats, different context formats.
3. **SDK fragmentation**: Each vendor maintained their own SDK for each language. Zipkin's Java library was different from Datadog's Java library, which was different from Jaeger's. Duplicated effort across the industry.
4. **Library authors' nightmare**: If you wrote a database driver library, which observability API should you instrument with? Choose one and exclude others? Instrument with all? (Spoiler: they didn't instrument at all.)

### 2.1.2 OpenTracing (2016) — The First Unification Attempt

OpenTracing provided a VENDOR-NEUTRAL API for distributed tracing.

```java
// Same code, works with any OpenTracing-compatible tracer
import io.opentracing.Tracer;
import io.opentracing.Scope;

Tracer tracer = ...; // injected: Jaeger, Zipkin, Datadog, Lightstep

try (Scope scope = tracer.buildSpan("processPayment").startActive(true)) {
    scope.span().setTag("payment_id", "pay_123");
    // business logic
}
```

**What OpenTracing got right:**
- Vendor-neutral API — swap tracers without changing instrumentation code
- Language-agnostic specification (Java, Go, Python, JavaScript, C#...)
- Standardized span model (operation name, start/end time, tags, logs, references)

**Why OpenTracing failed:**
1. **Traces only.** OpenTracing had no concept of metrics or logs. You still needed separate SDKs for Prometheus metrics and ELK logs.
2. **No implementation.** OpenTracing was only an API. You still needed a vendor's tracer implementation. Adoption required both the API and a vendor SDK.
3. **No wire protocol.** OpenTracing defined in-process APIs but not how traces traveled between services. Each vendor used their own headers.
4. **No auto-instrumentation.** OpenTracing required manual instrumentation of every library. No way to automatically instrument Spring, gRPC, Kafka, etc.

### 2.1.3 OpenCensus (2018) — Google/Microsoft's Attempt

OpenCensus went further than OpenTracing: it provided both an API AND implementations.

```
OpenCensus:
  ├── Tracing API + Implementation
  ├── Metrics API + Implementation
  ├── Context Propagation (wire format)
  └── Exporters (Jaeger, Prometheus, Zipkin, Stackdriver, Azure Monitor)
```

**What OpenCensus got right:**
- Both traces AND metrics from a single library
- Built-in exporters to multiple backends
- Context propagation built in
- Better performance (less allocation overhead)

**Why OpenCensus failed:**
1. **Google/Microsoft heavy.** The community perceived it as "Google's thing," which limited ecosystem participation.
2. **Limited language support** compared to OpenTracing.
3. **Still no auto-instrumentation** (initially).
4. **Two competing "standards" (OpenTracing vs OpenCensus)** fractured the community instead of uniting it.

### 2.1.4 The Merger: OpenTelemetry (2019)

In May 2019, at KubeCon Barcelona, the OpenTracing and OpenCensus projects announced their merger into **OpenTelemetry**.

**Why OpenTelemetry won where the predecessors failed:**

1. **CNCF incubation → Graduation.** Being part of the Cloud Native Computing Foundation gave it legitimacy and governance. Not controlled by any single vendor.

2. **ALL signals from ONE SDK.** Traces, metrics, logs — one API, one SDK, one data model. This was the killer feature.

3. **Auto-instrumentation.** OTel provides automatic instrumentation agents for Java, Node.js, Python, .NET that instrument popular libraries (Spring, Express, gRPC, Kafka, Redis, PostgreSQL) with ZERO code changes.

4. **Vendor-neutral wire protocol (OTLP).** Services communicate telemetry via OTLP, which any backend can implement. No proprietary headers.

5. **Massive industry adoption.** AWS, Google Cloud, Azure, Splunk, Datadog, New Relic, Dynatrace, Grafana Labs, Elastic, Honeycomb, Lightstep — all contribute to or support OpenTelemetry.

6. **Separation of API, SDK, and Collector.** You can use the OTel API without the SDK. You can use the Collector without the SDK. This modularity prevented the "heavy library" criticism.

---

## 2.2 OpenTelemetry Architecture

### 2.2.1 The Four Layers

```
┌──────────────────────────────────────────────┐
│              APPLICATION CODE                 │
│  Your business logic, frameworks, libraries   │
└──────────────────┬───────────────────────────┘
                   │ Calls OTel API
┌──────────────────▼───────────────────────────┐
│           OTel API (interfaces only)          │
│  Tracer, Meter, Logger — no-op by default    │
│  Safe for libraries to depend on              │
└──────────────────┬───────────────────────────┘
                   │ Implemented by
┌──────────────────▼───────────────────────────┐
│           OTel SDK (implementation)           │
│  Span processors, samplers, exporters        │
│  Context propagation, resource detection     │
└──────────────────┬───────────────────────────┘
                   │ OTLP protocol
┌──────────────────▼───────────────────────────┐
│        OTel Collector (pipeline)              │
│  Receive → Process → Export                  │
│  Buffering, batching, tail sampling          │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────▼───────────────────────────┐
│              BACKENDS                         │
│  Prometheus, Jaeger, OpenSearch, ...          │
└──────────────────────────────────────────────┘
```

### 2.2.2 Why the API/SDK Separation Matters

**Library authors** depend only on the API. Their code works whether or not the application uses OpenTelemetry:

```java
// Library code: depends only on OpenTelemetry API
import io.opentelemetry.api.trace.Tracer;

public class PaymentLibrary {
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("payment-lib");

    public void process(Payment payment) {
        Span span = tracer.spanBuilder("processPayment").startSpan();
        try {
            // library logic
        } finally {
            span.end();
        }
    }
}
```

If the application includes the OTel SDK, traces are produced. If the application has no OTel SDK, the API is a no-op (zero overhead). Library authors can instrument ONCE and support ALL observability backends.

**Application developers** configure the SDK once. They choose exporters, samplers, and processors. Library traces automatically flow through the configured pipeline.

---

## 2.3 The OpenTelemetry Data Model

### 2.3.1 Resource

A Resource describes the entity producing telemetry. It's attached to every span, metric, and log record.

```json
{
  "resource": {
    "attributes": [
      {"key": "service.name", "value": "payment-service"},
      {"key": "service.version", "value": "2.4.1"},
      {"key": "service.instance.id", "value": "pod-7f3a8b-xyz"},
      {"key": "host.name", "value": "ip-10-0-3-42.ec2.internal"},
      {"key": "cloud.region", "value": "us-east-1"},
      {"key": "k8s.namespace.name", "value": "production"},
      {"key": "k8s.pod.name", "value": "payment-service-7f3a8b-xyz"},
      {"key": "telemetry.sdk.name", "value": "opentelemetry"},
      {"key": "telemetry.sdk.language", "value": "java"},
      {"key": "telemetry.sdk.version", "value": "1.30.0"}
    ]
  }
}
```

**Why Resource matters**: In production, you have 100 instances of payment-service across 3 AZs in 2 regions. When you see a latency spike, you need to know: which region? which AZ? which pod? which version? Resource attributes enable this filtering.

**Critical resource attributes for production:**

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `service.name` | Primary identifier | `payment-service` |
| `service.version` | Correlate with deployments | `git:abc1234` or `2.4.1` |
| `service.instance.id` | Unique instance | Pod name or hostname |
| `deployment.environment` | Production vs staging | `production` |
| `cloud.region` | Region-level filtering | `us-east-1` |
| `cloud.availability_zone` | AZ-level filtering | `us-east-1a` |
| `k8s.namespace.name` | Kubernetes namespace | `production` |

### 2.3.2 Span

A Span is the core unit of work in distributed tracing.

```
Span {
    trace_id: "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"  (16 bytes)
    span_id:  "1a2b3c4d5e6f1a2b"                (8 bytes)
    parent_span_id: "abcdef1234567890"           (8 bytes, null for root)
    name: "POST /payments"
    kind: SERVER  (or CLIENT, INTERNAL, PRODUCER, CONSUMER)
    start_time_unix_nano: 1705310605123456789
    end_time_unix_nano:   1705310605123700000
    status: {code: OK}
    attributes: [
        {key: "http.method", value: "POST"},
        {key: "http.url", value: "https://api.example.com/payments"},
        {key: "http.status_code", value: 201},
        {key: "payment.id", value: "pay_123"},
        {key: "payment.amount", value: 100.00}
    ]
    events: [
        {name: "cache_miss", timestamp: ..., attributes: {key: "payment_id"}},
        {name: "retry", timestamp: ..., attributes: {attempt: 2}}
    ]
    links: [
        {trace_id: "other_trace", span_id: "other_span"}
    ]
}
```

**Span Kind — what it means:**

| Kind | Meaning | Parent of... |
|------|---------|-------------|
| `SERVER` | I received a request (inbound) | Children are CLIENT calls |
| `CLIENT` | I made a request (outbound) | - |
| `INTERNAL` | Internal operation (no network) | - |
| `PRODUCER` | I published to a queue/topic | CONSUMER spans (via links) |
| `CONSUMER` | I consumed from a queue/topic | - |

**Span hierarchy visual:**

```
SERVER  (POST /payments)          ← handled inbound request
  ├── CLIENT (POST /auth/verify)  ← called auth service
  ├── INTERNAL (validatePayment)  ← internal logic
  ├── CLIENT (SELECT FROM wallets)← database call
  ├── PRODUCER (publish to Kafka) ← async publish
  └── CLIENT (POST /notify)       ← notification service
```

### 2.3.3 Span Events

Span events are timestamped, structured annotations within a span. They represent significant moments during the span's execution.

```
Span "POST /payments" (0ms - 2450ms)
  ├── Event "cache_lookup_start" @ 5ms
  ├── Event "cache_miss" @ 6ms         {cache: "redis-payments"}
  ├── Event "db_query_start" @ 10ms
  ├── Event "db_query_end" @ 450ms     {rows_returned: 1}
  ├── Event "fraud_check_start" @ 451ms
  ├── Event "fraud_check_pass" @ 800ms {score: 0.03}
  ├── Event "kafka_publish" @ 820ms    {topic: "payments.completed"}
  └── Event "exception" @ 2400ms       {exception.message: "Connection timeout",
                                         exception.stacktrace: "..."}
```

**Span Events vs Span Attributes:**
- Attributes are for the span's WHOLE lifetime: `http.method`, `payment.id`, `user.id`
- Events are for MOMENTS within the span: cache misses, retries, exceptions, state transitions
- Events carry timestamps; attributes don't

### 2.3.4 Span Links

Links connect spans from different traces. They're used for asynchronous relationships where parent-child hierarchy doesn't apply.

```
Trace A: POST /order       Trace B: Process Order (from Kafka)
┌──────────────┐           ┌──────────────────────┐
│ Span: order   │           │ Span: process_order   │
│ (PRODUCER)    │───link──→│ (CONSUMER)            │
│ topic: orders │           │ parent: null          │
└──────────────┘           │ links: [trace A]      │
                           └──────────────────────┘
```

Without links, Trace B appears to originate from nowhere (root span with no parent). With links, you can navigate from the Kafka consumer back to the producer that generated the message.

### 2.3.5 Attributes (Tags)

Attributes are key-value pairs attached to spans, metrics, and logs.

**Attribute naming conventions (OpenTelemetry Semantic Conventions):**

```yaml
# HTTP
http.method: GET
http.url: https://api.example.com/payments
http.status_code: 200
http.route: /users/:userId/payments
http.request_content_length: 1024

# Database
db.system: postgresql
db.name: payments_db
db.statement: SELECT * FROM wallets WHERE user_id = ?
db.operation: SELECT
db.sql.table: wallets

# Messaging
messaging.system: kafka
messaging.destination: payments.completed
messaging.destination_kind: topic
messaging.kafka.message_key: user_42
messaging.kafka.partition: 3
messaging.kafka.consumer_group: payment-processor

# RPC
rpc.system: grpc
rpc.service: payment.PaymentService
rpc.method: ProcessPayment
rpc.grpc.status_code: 0

# General
service.name: payment-service
exception.type: java.sql.SQLTimeoutException
exception.message: Connection timed out
exception.stacktrace: ...
```

**Why semantic conventions matter:**

Without conventions, service-A uses `http_status` and service-B uses `http.status_code`. Queries that filter by "all HTTP 500 errors" need to check both attribute names. Semantic conventions standardize this.

### 2.3.6 Baggage

**Baggage is user-defined context that propagates across service boundaries.**

```java
// Service A
Span.current().setAttribute("user.id", "42");

// Baggage — propagates to downstream calls
Baggage.current().toBuilder()
    .put("user.id", "42")
    .put("experiment", "new_algorithm")
    .put("tenant.id", "tenant_abc")
    .build()
    .makeCurrent();
```

**Baggage vs Attributes:**
- Attributes stay WITHIN a service's spans
- Baggage TRAVELS WITH the request to downstream services

**Why baggage exists**: If service-A knows the `tenant.id`, it can store it in baggage. Service-D, 5 hops downstream, can read the `tenant.id` without needing it in the request payload. This is powerful for:
- Canary/feature flag routing
- Multi-tenancy context
- Business context for sampling decisions

**Baggage in HTTP headers:**

```
baggage: user.id=42,experiment=new_algorithm,tenant.id=tenant_abc
```

**Warning**: Baggage is transmitted with every request. Don't put large or sensitive data in it. PII in baggage headers is visible in transit.

### 2.3.7 Context Propagation — The Full Picture

```
Service A (receives HTTP request)
    │
    │  Incoming headers:
    │  traceparent: 00-abc123...-def456...-01
    │  tracestate: vendor=value
    │  baggage: user.id=42,tenant.id=abc
    │
    ├── Extract trace context from headers
    ├── Create Span (trace_id=abc123, parent=def456)
    ├── Read Baggage (user.id=42)
    │
    ├── Calls Service B
    │   │
    │   │  Outgoing headers:
    │   │  traceparent: 00-abc123...-789ghi...-01
    │   │  tracestate: vendor=value
    │   │  baggage: user.id=42,tenant.id=abc
    │   │
    │   └── Service B extracts and continues...
    │
    └── Span ends
```

**tracestate**: Vendor-specific key-value pairs that propagate alongside traceparent. Used for vendor-specific sampling decisions or routing. Opaque to most systems.

```
tracestate: dd=s:2;o:rum:4b6f,jaeger@1=abc:def
```

Each vendor adds their own entry (comma-separated). The standard guarantees at least 32 vendor entries can propagate.

---

## 2.4 Instrumentation

### 2.4.1 Auto-Instrumentation

Auto-instrumentation injects OpenTelemetry into your application without code changes. It works via:

**Java**: Java Agent (JVM bytecode manipulation)
```bash
java -javaagent:opentelemetry-javaagent.jar -jar myapp.jar
```

**Node.js**: Module loader hooks
```bash
node --require @opentelemetry/auto-instrumentations-node/register app.js
```

**Python**: Monkey-patching via import hooks
```bash
opentelemetry-instrument python app.py
```

**What auto-instrumentation gives you (zero config):**

| Library | Auto-instrumented Spans |
|---------|------------------------|
| Spring Boot / Express / FastAPI | HTTP server spans (inbound) |
| HTTP Clients (RestTemplate, HttpClient, axios, requests) | HTTP client spans (outbound) |
| gRPC | Server + Client spans |
| JDBC / SQLAlchemy / Sequelize | Database spans |
| Kafka (java client, kafkajs, confluent-kafka) | Producer + Consumer spans |
| Redis (Jedis, Lettuce, ioredis) | Cache spans |
| RabbitMQ | Producer + Consumer spans |
| Logback / log4j / winston | Log correlation (trace_id in logs) |

**Auto-instrumentation internals (Java agent example):**

The Java agent intercepts key class loading events. When `org.springframework.web.servlet.DispatcherServlet` is loaded, the agent injects bytecode around the `doDispatch()` method to create a SERVER span. When `java.sql.Statement.executeQuery()` is called, the agent creates a CLIENT span with `db.system=postgresql`.

This is done via `ClassFileTransformer` — the JVM's mechanism for modifying bytecode at class-load time.

### 2.4.2 Manual Instrumentation

Auto-instrumentation covers generic patterns (HTTP calls, DB queries). Manual instrumentation adds BUSINESS context that auto-instrumentation cannot infer.

```java
// Auto-instrumentation creates: Span "POST /payments"
// But it doesn't know about payment-specific logic:

@PostMapping("/payments")
public PaymentResponse createPayment(@RequestBody PaymentRequest request) {
    // Manual span for business logic
    Span span = tracer.spanBuilder("validateAndProcessPayment")
        .setAttribute("payment.amount", request.getAmount())
        .setAttribute("payment.currency", request.getCurrency())
        .setAttribute("payment.method", request.getMethod().toString())
        .startSpan();

    try (Scope scope = span.makeCurrent()) {
        // This spans wraps the business logic
        Payment payment = paymentService.process(request);

        // Add event for significant moments
        span.addEvent("payment.processed", Attributes.of(
            AttributeKey.stringKey("payment.id"), payment.getId(),
            AttributeKey.doubleKey("wallet.balance_after"), payment.getNewBalance()
        ));

        span.setStatus(StatusCode.OK);
        return toResponse(payment);
    } catch (InsufficientFundsException e) {
        span.setStatus(StatusCode.ERROR, "Insufficient funds");
        span.recordException(e);
        throw e;
    } finally {
        span.end();
    }
}
```

**What manual instrumentation adds:**
- Business-meaningful span names (`validateAndProcessPayment` vs generic `POST /payments`)
- Business attributes (`payment.amount`, `payment.currency`, `wallet.balance_after`)
- Business events (payment processed, fraud check passed)
- Structured error recording (exception type, message, code location)

### 2.4.3 Manual Instrumentation Patterns Per Language

**Java with Spring Boot:**

```java
// Constructor injection — standard Spring pattern
@RestController
public class PaymentController {
    private final Tracer tracer;
    private final Meter meter;

    public PaymentController(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("payment-service");
        this.meter = openTelemetry.getMeter("payment-service");
    }

    // Counter for metrics
    private final LongCounter paymentCounter = meter
        .counterBuilder("payments.processed")
        .setDescription("Number of processed payments")
        .setUnit("1")
        .build();

    @PostMapping("/payments")
    public ResponseEntity<?> create(@RequestBody PaymentRequest req) {
        Span span = tracer.spanBuilder("createPayment")
            .setAttribute("payment.amount", req.getAmount())
            .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            // process...
            paymentCounter.add(1, Attributes.of(
                AttributeKey.stringKey("status"), "success"
            ));
            span.setStatus(StatusCode.OK);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            paymentCounter.add(1, Attributes.of(
                AttributeKey.stringKey("status"), "error",
                AttributeKey.stringKey("error.type"), e.getClass().getSimpleName()
            ));
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

**Go with net/http:**

```go
func (s *PaymentService) ProcessPayment(w http.ResponseWriter, r *http.Request) {
    // Extract context from incoming request (auto-instrumented at HTTP layer)
    ctx := r.Context()
    tracer := otel.Tracer("payment-service")

    ctx, span := tracer.Start(ctx, "processPayment",
        trace.WithAttributes(
            attribute.String("payment.method", r.Header.Get("X-Payment-Method")),
        ),
    )
    defer span.End()

    // Counter for metrics
    paymentCounter, _ := meter.Int64Counter("payments.processed")

    payment, err := s.processor.Process(ctx, req)
    if err != nil {
        span.RecordError(err)
        span.SetStatus(codes.Error, err.Error())
        paymentCounter.Add(ctx, 1, metric.WithAttributes(
            attribute.String("status", "error"),
        ))
        http.Error(w, err.Error(), http.StatusInternalServerError)
        return
    }

    span.SetStatus(codes.Ok, "Payment processed")
    paymentCounter.Add(ctx, 1, metric.WithAttributes(
        attribute.String("status", "success"),
    ))
    w.WriteHeader(http.StatusCreated)
}
```

**Python with FastAPI:**

```python
from opentelemetry import trace
from opentelemetry import metrics

tracer = trace.get_tracer("payment-service")
meter = metrics.get_meter("payment-service")
payment_counter = meter.create_counter(
    "payments.processed",
    description="Number of processed payments"
)

@app.post("/payments")
async def create_payment(request: PaymentRequest):
    with tracer.start_as_current_span("createPayment") as span:
        span.set_attribute("payment.amount", request.amount)
        span.set_attribute("payment.currency", request.currency)

        try:
            payment = await process_payment(request)
            payment_counter.add(1, {"status": "success"})
            span.set_status(StatusCode.OK)
            return payment
        except InsufficientFunds as e:
            payment_counter.add(1, {"status": "error"})
            span.record_exception(e)
            span.set_status(StatusCode.ERROR, str(e))
            raise HTTPException(status_code=402, detail=str(e))
```

**Node.js with NestJS:**

```typescript
import { trace, metrics, SpanStatusCode } from '@opentelemetry/api';

const tracer = trace.getTracer('payment-service');
const meter = metrics.getMeter('payment-service');
const paymentCounter = meter.createCounter('payments.processed');

@Controller('payments')
export class PaymentController {
  @Post()
  async create(@Body() request: PaymentRequest) {
    const span = tracer.startSpan('createPayment');
    span.setAttribute('payment.amount', request.amount);

    try {
      const payment = await this.service.process(request);
      paymentCounter.add(1, { status: 'success' });
      span.setStatus({ code: SpanStatusCode.OK });
      return payment;
    } catch (error) {
      paymentCounter.add(1, { status: 'error' });
      span.recordException(error);
      span.setStatus({ code: SpanStatusCode.ERROR, message: error.message });
      throw error;
    } finally {
      span.end();
    }
  }
}
```

---

## 2.5 Sampling

### 2.5.1 Why Sampling Exists

At scale, you cannot record every trace. A payment system processing 10,000 requests/second generates 10,000 traces/second. Each trace might have 10-50 spans. That's 100,000-500,000 spans/second.

Storing every span is:
- **Expensive** (storage costs)
- **Unnecessary** (99.9% of successful traces are boring)

Sampling decides WHICH traces to keep and send to the backend.

### 2.5.2 Head Sampling (at span creation)

Decision made when the ROOT span is created, before any work is done.

```
Request arrives → Create root span → Should I sample? → YES/NO → Propagate decision
```

**Implemented in the OTel SDK — zero collector involvement.**

```java
// Head-based sampler: sample 10% of all traces
Sampler sampler = Sampler.traceIdRatioBased(0.1);

OpenTelemetry sdk = OpenTelemetrySdk.builder()
    .setTracerProvider(
        SdkTracerProvider.builder()
            .setSampler(sampler)
            .build()
    )
    .build();
```

**Head sampling strategies:**

| Strategy | When to Use | Downside |
|----------|-----------|----------|
| `AlwaysOn` | Development, low-traffic services | Too much data in production |
| `AlwaysOff` | Health checks, noisy endpoints | Misses everything |
| `TraceIdRatioBased(0.1)` | Fixed 10% sample rate | Misses errors, anomalies |
| `ParentBased` | Respect parent's decision (child spans) | Depends on root decision |

**The head sampling problem:** You can't sample based on the OUTCOME (error, latency) because the outcome hasn't happened yet. A trace that will result in a 10-second timeout looks the same as a 50ms success at creation time. If you sample 10%, you'll miss 90% of slow traces and errors.

### 2.5.3 Tail Sampling (at span completion)

Decision made AFTER all spans are complete, in the Collector.

```
Spans complete → Collector receives → Buffer → Analyze → Keep or Drop
```

**Tail sampling rules (e.g., keep ALL errors):**

```yaml
processors:
  tail_sampling:
    decision_wait: 30s           # Wait for all spans to arrive
    num_traces: 50000            # Buffer size
    policies:
      - name: errors
        type: status_code
        status_code:
          status_codes: [ERROR]
      - name: slow
        type: latency
        latency:
          threshold_ms: 5000
      - name: probabilistic
        type: probabilistic
        probabilistic:
          sampling_percentage: 10
```

**Tail sampling advantages:**
- Knows the outcome before deciding → keep ALL errors, ALL slow traces
- Can make decisions based on attributes across the whole trace

**Tail sampling disadvantages:**
- **Needs a buffer**: Must wait for all spans to arrive (`decision_wait: 30s`)
- **Requires the Collector**: Not in the SDK
- **Higher resource usage**: Collector must buffer spans before deciding
- **Complexity**: If Collector goes down, buffered spans are lost
- **All-or-nothing**: All spans are sent to the Collector (unsampled), then the Collector decides which to keep. Network bandwidth is NOT saved.

### 2.5.4 Sampling Trade-off Summary

| | Head Sampling | Tail Sampling |
|---|---|---|
| Where | SDK (in-process) | Collector (out-of-process) |
| Decision timing | At root span creation | After trace completion |
| Can filter by errors? | No | Yes |
| Can filter by latency? | No | Yes |
| Saves network bandwidth? | Yes (never send unsampled spans) | No (all spans sent to collector) |
| Saves backend storage? | Yes | Yes |
| Complexity | Low | High |
| Risk of losing interesting data | High (probabilistic misses anomalies) | None (keep criteria are evaluated) |

**Production recommendation:**
1. Use `ParentBased` + `TraceIdRatioBased(0.1)` in the SDK (head sampling)
2. Use tail sampling in the Collector for error/latency-based retention
3. The SDK sampling reduces volume. The Collector tail sampling ensures nothing important is lost.

---

## 2.6 Metrics in OpenTelemetry

The OTel Metrics API mirrors the Prometheus model but is backend-agnostic.

### 2.6.1 Metric Instruments

| Instrument | OTel Name | Prometheus Equivalent | Behavior |
|-----------|-----------|----------------------|----------|
| Counter | `LongCounter` / `DoubleCounter` | Counter | Only increases |
| UpDownCounter | `LongUpDownCounter` | Gauge | Increases and decreases |
| Histogram | `DoubleHistogram` | Histogram | Distribution with buckets |
| ObservableGauge | `ObservableDoubleGauge` | Gauge (callback) | Async callback |
| ObservableCounter | `ObservableDoubleCounter` | Counter (callback) | Async callback |

**Observable instruments** (gauge/counter) call a callback function each time Prometheus scrapes:

```java
meter.gaugeBuilder("jvm.memory.used")
    .setDescription("Heap memory used")
    .setUnit("By")
    .buildWithCallback(measurement -> {
        measurement.record(Runtime.getRuntime().totalMemory() -
                           Runtime.getRuntime().freeMemory());
    });
```

This is more efficient than updating a gauge after every GC event. The value is collected only when needed (on scrape).

---

## 2.7 Logs in OpenTelemetry

OTel's log model is still evolving (as of 2024), but the concept is:

```java
// Log bridge: OTel SDK intercepts log framework calls
// Logback/log4j → OTel Log Bridge → OTel Log Exporter

Logger logger = LoggerFactory.getLogger(PaymentService.class);

// This log automatically gets trace_id and span_id injected
logger.info("Payment processed: id={}, amount={}",
    payment.getId(), payment.getAmount());

// Output (JSON to OpenSearch):
{
  "timestamp": "2024-01-15T14:23:45.123Z",
  "severity": "INFO",
  "body": "Payment processed: id=pay_123, amount=100.00",
  "trace_id": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
  "span_id": "1a2b3c4d5e6f1a2b",
  "resource": {
    "service.name": "payment-service",
    "service.version": "2.4.1"
  },
  "attributes": {
    "payment.id": "pay_123",
    "payment.amount": 100.00
  }
}
```

**The log bridge**: OTel doesn't replace your logging framework. It wraps it. Your existing log statements get trace context injected automatically via the OTel Log Bridge.

---

## 2.8 Common Misconceptions

### "OpenTelemetry is just for tracing"

No. OTel handles traces, metrics, and logs. It's a unified telemetry framework, not a tracing library.

### "I need to rewrite all my logging to use OTel"

No. The OTel Log Bridge integrates with existing logging frameworks (Logback, log4j, winston, Python logging). Existing `logger.info()` calls get trace context automatically.

### "OpenTelemetry replaces my observability backend"

No. OTel generates and ships telemetry. It does NOT store or visualize it. You still need Prometheus, Jaeger, OpenSearch, Grafana.

### "Auto-instrumentation is enough for production"

For basic HTTP/database telemetry, yes. For business-level observability (payment amounts, wallet balances, fraud scores), you need manual instrumentation.

### "OpenTelemetry is production-ready for all signals"

Traces: Production-ready since ~2020. Metrics: Production-ready since ~2022. Logs: Still stabilizing (2024). Check the specification maturity level for each signal.

---

## Interview Questions — Phase 2

1. **Why did OpenTracing and OpenCensus fail, and what did OpenTelemetry do differently?**

   *Answer core points*: OpenTracing was traces-only, API-only (no SDK), no wire protocol. OpenCensus was Google-dominated, limited language support. OpenTelemetry merged both, added metrics and logs, provided API+SDK+Collector, joined CNCF for vendor-neutral governance, and got massive industry consensus.

2. **Explain the OpenTelemetry API/SDK separation. Why does it matter for library authors?**

   *Answer core points*: Library authors depend on the API only (zero overhead if no SDK present). Application developers configure the SDK. This allows libraries to be instrumented once and work with any observability backend.

3. **What is the difference between Baggage and Span Attributes?**

   *Answer core points*: Attributes stay within a single service's spans. Baggage propagates across service boundaries via HTTP headers. Baggage is for context that downstream services need (tenant ID, feature flags). Attributes are for telemetry data (HTTP method, payment amount).

4. **Compare head sampling and tail sampling. When would you recommend each?**

   *Answer core points*: Head sampling is in the SDK, before work completes — can't filter by outcome. Tail sampling is in the Collector, after trace completion — can filter by errors/latency but requires buffering. Production: combine both — head sampling for volume reduction (10%), tail sampling for error/latency guarantees.

5. **What is a Span Kind and why does it matter?**

   *Answer core points*: Span Kind (SERVER, CLIENT, INTERNAL, PRODUCER, CONSUMER) indicates the role of the span in a distributed trace. It enables automatic service maps, dependency graphs, and proper parent-child relationships in tracing UIs.

6. **How does the W3C traceparent header work? Why standardize it?**

   *Answer core points*: `traceparent: 00-{trace_id}-{parent_span_id}-{flags}`. Standardization allows different observability systems to understand each other's traces. Before W3C, Datadog and Zipkin headers were incompatible, breaking cross-vendor trace propagation.

7. **What are the OpenTelemetry Semantic Conventions and why do they matter?**

   *Answer core points*: Standardized attribute names and values across the industry (e.g., `http.method`, `db.system`, `messaging.kafka.topic`). Without them, every service names attributes differently, making cross-service queries impossible.

8. **What's the difference between a Metric Counter, a Histogram, and an ObservableGauge? When would you use each?**

   *Answer core points*: Counter: only increases, for request counts. Histogram: distribution, for latency. ObservableGauge: callback-based, for system metrics (memory, CPU) collected on demand rather than pushed.

---

**Next: Phase 3 — OpenTelemetry Collector Internals**
