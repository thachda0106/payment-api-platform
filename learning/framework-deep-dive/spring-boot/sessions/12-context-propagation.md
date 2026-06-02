# Session 12: Context Propagation & Observability Foundation

## 1. Why This Topic Exists

When you get a production alert at 3 AM, you need to answer: "What happened to this specific user's request?" If your logs say `User not found` but you don't know WHICH user, WHICH request, or WHICH service caused it, you are blind. Context propagation solves this: carry trace IDs, user IDs, and request metadata across threads, services, and async boundaries.

**Staff engineer insight**: Context propagation is the plumbing that makes observability work. Without it, you have isolated logs that cannot be correlated. With it, you can trace a single request through 20 microservices in seconds. This is the difference between resolving an incident in 5 minutes vs 5 hours.

## 2. Mental Model

```
┌─────────────────────────────────────────────────────────────┐
│                    CONTEXT CARRIER                          │
│                                                             │
│  ┌────────────────────────────────────────────────────┐    │
│  │ traceId: "a1b2c3d4..."                              │    │
│  │ spanId:  "x9y8z7..."                                │    │
│  │ userId:  "user-456"                                  │    │
│  │ tenantId:"tenant-789"                                │    │
│  │ requestId:"req-abc"                                  │    │
│  └────────────────────────────────────────────────────┘    │
│                                                             │
│  This context must be available EVERYWHERE:                 │
│                                                             │
│  ThreadLocal (sync code)    ScopedValue (Java 21+)          │
│  MDC (logging)              SpanContext (OpenTelemetry)     │
│  HTTP Headers               gRPC Metadata                  │
│  Kafka Headers              JMS Properties                 │
└─────────────────────────────────────────────────────────────┘
```

### Context Propagation Across Boundaries

```
                                       Context must cross:
┌─────────────┐                        ┌─────────────────────┐
│  HTTP       │ ──── X-Trace-Id ────▶ │  Thread boundaries    │
│  Controller │                        │  (ThreadLocal→Thread)│
└──────┬──────┘                        ├─────────────────────┤
       │                               │  Service boundaries  │
       ▼                               │  (HTTP headers)      │
┌─────────────┐                        ├─────────────────────┤
│  Service    │ ──── Direct call ────▶ │  Async boundaries    │
│  Layer      │   (same thread)        │  (@Async, Completable│
└──────┬──────┘                        │   Future, EventBus)  │
       │                               ├─────────────────────┤
       ▼                               │  Process boundaries  │
┌─────────────┐                        │  (Kafka, RabbitMQ)   │
│  @Async     │ ──── Thread switch ──▶ │                      │
│  Method     │   CONTEXT LOST!         └─────────────────────┘
└─────────────┘
       │  Must explicitly propagate context!
       ▼
┌─────────────┐
│  Kafka      │ ──── Headers ────▶  Consumer
│  Producer   │   CONTEXT LOST!
└─────────────┘
```

## 3. Internal Architecture

### ThreadLocal (Traditional, Pre-Java 21)

```java
// How MDC works (simplified)
public class MDC {
    // ThreadLocalMap — each thread has its own copy
    private static final ThreadLocal<Map<String, String>> context = new ThreadLocal<>();
    
    public static void put(String key, String value) {
        context.get().put(key, value);
    }
    
    public static String get(String key) {
        return context.get().get(key);
    }
}

// Logback/Log4j pattern:
// %d %-5level [%thread] %X{traceId} %logger - %msg%n
// Output: 2024-01-15 14:30:00 INFO [http-nio-8080-exec-3] [a1b2c3] c.e.MyService - Processing order
```

**Problem with ThreadLocal**: When execution jumps threads (via `@Async`, `CompletableFuture`, thread pools), the ThreadLocal context is LOST. You must explicitly copy it.

### ScopedValue (Java 21+, Preferred)

```java
// ScopedValue: structured, immutable, auto-cleanup
public final static ScopedValue<String> TRACE_ID = ScopedValue.newInstance();
public final static ScopedValue<String> USER_ID = ScopedValue.newInstance();
public final static ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

// Setting context (bound to the scope)
ScopedValue.where(TRACE_ID, "a1b2c3")
    .where(USER_ID, "user-456")
    .where(REQUEST_ID, "req-abc")
    .run(() -> {
        // All code here (including virtual threads!) sees these values
        String traceId = TRACE_ID.get();  // "a1b2c3"
        
        // Context AUTO-PROPAGATES to virtual threads
        Thread.ofVirtual().start(() -> {
            String id = TRACE_ID.get();  // STILL "a1b2c3"!
        });
    });
// After scope exits, values are cleared — no memory leak risk
```

### OpenTelemetry Context Propagation

```java
// OpenTelemetry wraps context in its own abstraction
SpanContext spanContext = SpanContext.create(
    traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()
);

// Inject into HTTP headers (outgoing request)
TextMapSetter<HttpURLConnection> setter = HttpURLConnection::setRequestProperty;
OpenTelemetry.getPropagators().getTextMapPropagator()
    .inject(Context.current(), connection, setter);
// Sets: traceparent: 00-a1b2c3d4e5f6-x9y8z7w6v5-01

// Extract from HTTP headers (incoming request)
Context extractedContext = OpenTelemetry.getPropagators().getTextMapPropagator()
    .extract(Context.current(), request, getter);
```

## 4. Runtime Behavior

### MDC in Spring Boot (Standard Filter Pattern)

```java
// Spring Boot's built-in MDC filter pattern:

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                     HttpServletResponse response,
                                     FilterChain chain) throws IOException, ServletException {
        // 1. Generate or extract trace ID
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        
        // 2. Set in MDC
        MDC.put("traceId", traceId);
        MDC.put("userId", request.getHeader("X-User-Id"));
        MDC.put("requestUri", request.getRequestURI());
        
        // 3. Add to response headers (for client-side tracing)
        response.setHeader("X-Trace-Id", traceId);
        
        try {
            chain.doFilter(request, response);  // All downstream code sees MDC
        } finally {
            // 4. CLEANUP: critical to prevent memory leaks
            MDC.clear();
        }
    }
}
```

### Context Propagation in Async Code

```java
// PROBLEM: @Async loses MDC context
@Service
public class OrderService {
    
    @Async
    public void sendConfirmation(Order order) {
        // traceId is NULL here! Thread switched.
        log.info("Sending confirmation for order {}", order.getId());
        // Output: 2024-01-15 14:30:00 INFO [task-1] [] c.e.OrderService - ...
        //                                         ^^^^  ^^\n        //                                        empty!  \n    }
}

// SOLUTION 1: Custom TaskDecorator
@Configuration
public class AsyncConfig implements AsyncConfigurer {
    
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(task -> {
            // Capture MDC from calling thread
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                // Restore MDC in worker thread
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}

// SOLUTION 2: MdcTaskDecorator (built-in, Spring Cloud Sleuth or custom)
executor.setTaskDecorator(new MdcTaskDecorator());
```

### OpenTelemetry Auto-Instrumentation

Spring Boot 3.x with Micrometer Tracing (successor to Spring Cloud Sleuth):

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 100% sampling for dev; 0.1 for production
  zipkin:
    tracing:
      endpoint: http://zipkin:9411/api/v2/spans
```

This automatically:
- Generates traceId and spanId for every HTTP request
- Propagates via `traceparent` (W3C) or `X-B3-TraceId` (Zipkin) headers
- Instruments `RestTemplate`, `WebClient`, `@Async`, `@Scheduled`
- Sends spans to Zipkin/Jaeger/OTLP collector

## 5. Request Flow Diagrams

### Full Trace: Single Request Across Services

```
Client → Service A → Service B → Service C → Database

traceId: a1b2c3d4 (same across ALL services!)
spanId chain:
  Service A (root span): spanA-1
    └── Service B (child): spanB-1
          └── Service C (child): spanC-1
                └── DB query (child): spanC-2

Zipkin/Jaeger/Grafana Tempo trace view:
┌──────────────────────────────────────────────────────────┐
│ traceId: a1b2c3d4                                       │
├──────────────────────────────────────────────────────────┤
│ Service A: handleOrder          ████ (50ms)              │
│   Service B: checkInventory     ██ (20ms)                │
│     Service C: reserveStock     ██ (15ms)                │
│       PostgreSQL: UPDATE         █ (8ms)                 │
│   Service B: createPayment      ███ (30ms)                │
│     Stripe API: charge          ███ (25ms)               │
│   Service A: sendConfirmation   █ (10ms)                 │
└──────────────────────────────────────────────────────────┘

Total: ~125ms
You can see EXACTLY where time was spent.
```

### Context Loss and Recovery

```
Request: GET /orders/123
traceId: a1b2c3d4

[Filter]              MDC("traceId"="a1b2c3d4") ✓
[Controller]          log.info("...") → [a1b2c3d4] ... ✓
[Service]             log.info("...") → [a1b2c3d4] ... ✓
  │
  ├── [@Async sendEmail]    
  │     Thread switch!    MDC LOST!
  │     log.info("...") → [] ... ✗ (empty traceId)
  │
  ├── [CompletableFuture.supplyAsync(checkInventory)]
  │     Thread switch!    MDC LOST!
  │     log.info("...") → [] ... ✗
  │
  ├── [KafkaTemplate.send("orders")]
  │     Process boundary!  MDC LOST!
  │     Consumer:
  │       log.info("...") → [] ... ✗
  │
  └── [RestTemplate.getForObject("http://inventory/...")]
        Service boundary!   Header missing without propagation
        Inventory Service:
          log.info("...") → [] ... ✗

Without proper context propagation:
  - MDC lost across @Async: FIX with TaskDecorator
  - MDC lost across CompletableFuture: FIX with explicit capture
  - Context lost across Kafka: FIX with Kafka headers + interceptor
  - Context lost across REST: FIX with ClientHttpRequestInterceptor
```

## 6. Lifecycle Diagrams

### Trace Lifecycle

```
1. TRACE START
   Client sends request OR scheduled job starts
   → Generate traceId (UUID or 128-bit random)
   → Start root span
   
2. SPAN CREATION (child span)
   Internal operation or outbound call
   → Create child span with parent spanId
   → Inject trace context into outbound headers
   
3. CONTEXT PROPAGATION
   Extract trace context from inbound headers
   → Set as current span
   → Set MDC/ThreadLocal
   
4. SPAN END
   Operation completes (success or error)
   → Set span status (OK/ERROR)
   → Record duration, attributes, events
   → Export to collector
   
5. TRACE END
   Root span ends
   → All spans collected
   → Shipped to backend (Zipkin, Jaeger, Tempo)
   
6. RETENTION
   Stored for N days (configurable)
   → Searchable by traceId, service, operation, tag
```

## 7. Source Code Reading Guide

1. **Micrometer Tracing** (Spring Boot 3+):
   - `io.micrometer.tracing.Tracer`: Core tracing API
   - `io.micrometer.tracing.CurrentTraceContext`: Context propagation
   - `io.micrometer.tracing.handler`: Span export handlers
   
2. **Brave (Zipkin)**:
   - `brave.Tracing`: Main entry point
   - `brave.propagation.B3Propagation`: B3 header propagation
   - `brave.propagation.TraceContext`: Trace/span IDs

3. **OpenTelemetry Java**:
   - `io.opentelemetry.api.trace.Span`: Span API
   - `io.opentelemetry.context.Context`: Context storage
   - `io.opentelemetry.context.propagation.TextMapPropagator`: W3C TraceContext propagation

## 8. Production Failure Scenarios

### Scenario 1: Missing Trace Context in Logs

**Symptom**: Logs show `[]` for traceId in async operations. Cannot correlate async operations with the original request.

**Root cause**: `@Async` thread pool without MDC-aware `TaskDecorator`.

**Resolution**: Configure `MdcTaskDecorator` on all `TaskExecutor` beans. Validate with integration test: call async method, verify traceId in logs.

### Scenario 2: Trace Context Leak

**Symptom**: Request A's traceId appears in Request B's logs.

**Root cause**: `ThreadLocal` context not cleaned up after request. Thread reused from pool still has the previous request's MDC.

**Resolution**: ALWAYS call `MDC.clear()` in `finally` block in your filter. `OncePerRequestFilter.doFilterInternal()` must have the finally block. For `@Async`, `TaskDecorator` must clear MDC after task execution.

### Scenario 3: Span Explosion

**Symptom**: Tracing backend (Zipkin, Tempo) crashes due to too many spans. Storage costs skyrocket.

**Root cause**: 100% sampling in production. Each request generates 20+ spans. At 10K RPS, that's 200K spans/second = 17 billion spans/day.

**Resolution**: Reduce sampling to 0.1-1% in production. Use `sampling.probability=0.01`. Supplement with error-only sampling (always capture errors) and latency-based sampling (capture slow requests).

## 9. Debugging Techniques

### Verify Context Propagation

```java
@Test
void contextShouldPropagateAcrossAsyncBoundary() {
    String traceId = UUID.randomUUID().toString();
    MDC.put("traceId", traceId);
    
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        return MDC.get("traceId");  // Should return traceId
    }, taskExecutor);  // Must have MdcTaskDecorator
    
    assertThat(future.get()).isEqualTo(traceId);
}
```

### Trace Individual Requests

```bash
# Use curl with a known trace ID
curl -H "X-Trace-Id: debug-trace-123" http://localhost:8080/api/orders

# Search logs:
grep "debug-trace-123" application.log

# Should see this traceId on EVERY log line for this request,
# including @Async methods, outgoing HTTP calls, etc.
```

## 10. Observability Considerations

Context propagation builds the foundation for the "three pillars of observability":

| Pillar | What | Depends On |
|--------|------|-----------|
| **Logs** | Timestamped text records | MDC (traceId, spanId in log pattern) |
| **Metrics** | Aggregated numerical data | Micrometer tags (tenant, endpoint) |
| **Traces** | Request flow across services | Context propagation (traceId, spanId) |

Together: You get an alert (metrics) → search logs (traceId) → view trace (span graph) → find root cause.

## 11. Performance Implications

| Approach | Overhead |
|----------|----------|
| MDC ThreadLocal | Negligible (HashMap lookup per log) |
| ScopedValue | Slightly better than ThreadLocal |
| Span creation | ~1μs (with no-op exporter) |
| Span export (sampled) | ~50-500μs (serialization + network) |
| Unsampled spans | Almost zero overhead (decision made at trace root) |

**Performance rule**: Context propagation cost is negligible. The cost is in span EXPORT (sampled spans). Keep production sampling low (1-10%) to avoid tracing overhead becoming significant.

## 12. Architecture Implications

Context propagation requirements influence architecture:

- **@Async usage**: Must configure TaskDecorator → adds operational complexity
- **Event-driven**: Must include trace context in event payload → couples events to observability
- **Multi-service**: Must standardize on propagation format (W3C TraceContext is the standard)
- **gRPC**: Context is built-in (metadata) — easier than REST
- **Kafka**: Headers support context propagation — requires convention

## 13. Team Ownership Implications

Context propagation is an infrastructure/platform concern:
- Platform team: Provides base filter, TaskDecorator, interceptor configurations
- Service teams: Use the provided infrastructure; ensure cleanup in custom async code
- Standard: Document the context keys (traceId, userId, tenantId) that every service must propagate

## 14. Interview Questions

1. **"Why does MDC use ThreadLocal, and what are the alternatives?"**
   - **Answer**: ThreadLocal provides thread-scoped storage without synchronization overhead. The key issue is that it doesn't automatically propagate across thread boundaries. Alternatives: ScopedValue (Java 21+) auto-propagates to virtual threads; OpenTelemetry Context uses its own propagation mechanism; explicit parameter passing (clean but verbose). In Spring Boot, MDC + TaskDecorator is the pragmatic solution.

2. **"How do you propagate context through a Kafka message?"**
   - **Answer**: Producer interceptor adds traceId, spanId to Kafka message headers. Consumer interceptor extracts headers and sets MDC/Context before processing. OpenTelemetry provides Kafka interceptors out of the box. Custom solution: `ProducerInterceptor` + `ConsumerInterceptor` + header extraction in listener.

3. **"What happens if trace context is lost? How do you detect it?"**
   - **Answer**: Logs show empty `[]` or `null` for traceId. Metrics: count of log entries without traceId. Alert: if percentage of untraced logs exceeds threshold (1%). Detection: write integration tests that verify context propagation across all async boundaries (CompletableFuture, @Async, Kafka, REST calls).

## 15. Hands-On Exercises

1. **Set up distributed tracing**: Run Spring Boot app + Zipkin/Jaeger. Make a request that calls itself via RestTemplate. View the trace with all spans.

2. **Fix MDC propagation**: Write an @Async method that logs. Verify MDC is lost. Add MdcTaskDecorator. Verify MDC is present.

3. **Implement custom context propagation**: Propagate tenantId through all async boundaries. Write integration tests verifying it's present in every log line for a request.

## 16. Advanced Challenges

1. **Implement a context propagation interceptor framework**: Design a generic mechanism that propagates ANY context data across HTTP, gRPC, Kafka, and @Async boundaries without coupling the propagation logic to specific context keys.

2. **Build a trace context debugger**: Create an interceptor that attaches traceId to every outbound call and validates that responses include the same traceId (detect context loss early).

3. **Design a multi-tenant context system**: Propagate tenantId across all boundaries. Ensure one tenant's context can never leak into another tenant's context. Add tenant-isolation tests.
