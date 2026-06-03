# TASKS: Phase 5 — Platform Skeleton & Dev Setup

**Date**: 2026-06-03
**Status**: Draft — Awaiting Approval
**Depends on**: PLAN.md (APPROVED)

---

## 🎯 Goal

Build the shared foundation every service will use. After this phase, adding a new service takes < 5 minutes via scaffold. All 4 existing services get working OTel traces, standardized probe endpoints, structured logging with `traceId`/`spanId`/`requestId`, and dependency-aware readiness checks.

---

## 📋 Recommendations Incorporated (from PLAN review)

| # | Recommendation | Implementation |
|---|----------------|----------------|
| R1 | OTel Collector between Service and Jaeger | Add `otel-collector` to docker-compose. Services send to collector. Collector exports to Jaeger. |
| R2 | Java Agent OR SDK — not both | Use OTel Java Agent ONLY for auto-instrumentation. Remove SDK-based `OpenTelemetryConfig`. |
| R3 | Cached dependency registry for readiness | Readiness probes check cache (TTL 5s), not live deps on every call. Fresh check only on expiry. |
| R4 | Modular config — not every service needs DB/Kafka/Redis | Base config (server, logging, otel) is mandatory. DB/Kafka/Redis are optional modules. Validate only declared modules. |
| R5 | Separate arch tests: libs boundaries vs service boundaries | `libs/archtest/libs/` enforces internal libs boundaries. `libs/archtest/services/` enforces service→libs direction, layer boundaries. |
| R6 | Scaffold generates default ADR-0001 | Each scaffolded service gets `docs/adr/ADR-0001-{service-name}-architecture.md` documenting its architecture decisions. |

---

## 📊 Task Overview

```
Day 1: Infrastructure + Java (Tasks 1-5)
Day 2: Go + Python (Tasks 6-11)
Day 3: Node.js + Remaining packages (Tasks 12-16)
Day 4: Arch Tests + Scaffold + Docker (Tasks 17-21)
Day 5: Docs + Full Verification (Tasks 22-25)
```

---

## TASK 1: Add OTel Collector to docker-compose

**Priority**: HIGH — Unblocks all tracing work
**Dependencies**: None
**Estimated**: 30 min

### What
Add OpenTelemetry Collector between services and Jaeger. Services send OTLP gRPC (4317) to collector. Collector batches, samples, and exports to Jaeger.

### Files
| Action | File | Details |
|--------|------|---------|
| MODIFY | `docker-compose.yml` | Add `otel-collector` service before `jaeger` |
| CREATE | `shared/config/otel-collector-config.yaml` | Collector pipeline: OTLP receiver → batch processor → OTLP exporter to Jaeger |

### Collector Config
```yaml
# shared/config/otel-collector-config.yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  batch:
    timeout: 1s
    send_batch_size: 1024

exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp/jaeger]
```

### docker-compose Entry
```yaml
otel-collector:
  image: otel/opentelemetry-collector-contrib:0.103.0
  container_name: payment-otel-collector
  restart: unless-stopped
  command: ["--config=/etc/otel-collector-config.yaml"]
  volumes:
    - ./shared/config/otel-collector-config.yaml:/etc/otel-collector-config.yaml:ro
  ports:
    - "4317:4317"   # OTLP gRPC
    - "4318:4318"   # OTLP HTTP
  depends_on:
    - jaeger
  networks:
    - payment-network
```

### Service Env Var Update
All services change from:
```
OTEL_EXPORTER_OTLP_ENDPOINT: http://jaeger:4317
```
To:
```
OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
```

### Verification
```bash
docker-compose up -d otel-collector jaeger
# Collector starts, exports to Jaeger
docker-compose logs otel-collector | head -20
# Should show: "Everything is ready. Begin running and processing data."
```

---

## TASK 2: Create libs/java/ — Foundation Package

**Priority**: HIGH — First language bridge
**Dependencies**: TASK 1
**Estimated**: 2 hours

### What
Create `libs/java/` as a single Maven module with Spring Boot auto-configuration. Contains 7 logical sub-packages. Java Agent is the tracing mechanism (no SDK init code).

### Architecture Decision: Java Agent Only

The OTel Java Agent (`opentelemetry-javaagent.jar`) provides auto-instrumentation for:
- HTTP (Servlet, Spring Web, RestTemplate, WebClient)
- JPA/Hibernate (database calls)
- Kafka (producer + consumer)
- Logging (logback MDC injection of traceId/spanId)

**The libs/java/ package does NOT contain any SDK-based TracerProvider initialization.** It only provides:
1. Spring Boot auto-configuration that reads OTel resource attributes from `application.yml`
2. Logback encoder that injects `traceId`/`spanId`/`requestId` into structured JSON logs
3. Health probe endpoints (liveness, readiness, startup)
4. Modular config with validation

### Files to Create

```
libs/java/
├── pom.xml
│   <!-- Single Maven module: com.paymentapi:platform-libs -->
│   <!-- Depends on: spring-boot-starter-web, spring-boot-starter-actuator, micrometer-registry-prometheus -->
│   <!-- spring-boot-autoconfigure for auto-configuration -->
│
├── src/main/java/com/paymentapi/platform/
│   ├── telemetry/
│   │   └── TelemetryProperties.java
│   │       // @ConfigurationProperties("otel") — reads service.name, service.version, etc.
│   │       // Does NOT create TracerProvider. Java Agent handles that.
│   │       // Only provides resource attributes for the Agent to consume.
│   │
│   ├── health/
│   │   ├── ProbeResponse.java
│   │   │   // DTO: status, service, version, timestamp, uptime, checks
│   │   │
│   │   ├── DependencyStatus.java
│   │   │   // Enum: OK, DOWN, UNUSED
│   │   │
│   │   ├── CheckResult.java
│   │   │   // DTO: status, latencyMs, lastChecked
│   │   │
│   │   ├── CachedDependencyRegistry.java
│   │   │   // Thread-safe registry with TTL cache.
│   │   │   // register(name, Supplier<CheckResult>) — registers a dependency check
│   │   │   // getStatuses() → Map<String, CheckResult> — cached (TTL 5s)
│   │   │   // invalidate() → force recheck
│   │   │
│   │   ├── LivenessController.java
│   │   │   // GET /liveness — always 200 if thread alive. No I/O.
│   │   │
│   │   ├── ReadinessController.java
│   │   │   // GET /readiness — checks CachedDependencyRegistry.
│   │   │   // 200 if all deps OK, 503 if any DOWN.
│   │   │   // Cache hit (within TTL): instant response.
│   │   │   // Cache miss: calls all registered checks, caches results.
│   │   │
│   │   ├── StartupController.java
│   │   │   // GET /startup — returns readiness status.
│   │   │   // After startup complete (first successful readiness), returns 200.
│   │   │
│   │   └── HealthAutoConfiguration.java
│   │       // @AutoConfiguration — registers all probe controllers
│   │
│   ├── config/
│   │   ├── PlatformProperties.java
│   │   │   // @ConfigurationProperties("platform")
│   │   │   // Only contains base config: server (port, host), logging (level, format),
│   │   │   // otel (serviceName, serviceVersion, exporterEndpoint)
│   │   │   // All fields have defaults except exporterEndpoint.
│   │   │
│   │   ├── DatabaseProperties.java
│   │   │   // @ConfigurationProperties("platform.database")
│   │   │   // url, maxPoolSize, minIdle
│   │   │   // Optional: only validated if platform.database.url is set
│   │   │
│   │   ├── KafkaProperties.java
│   │   │   // @ConfigurationProperties("platform.kafka")
│   │   │   // bootstrapServers, consumerGroup
│   │   │   // Optional: only validated if platform.kafka.bootstrap-servers is set
│   │   │
│   │   ├── RedisProperties.java
│   │   │   // @ConfigurationProperties("platform.redis")
│   │   │   // url
│   │   │   // Optional: only validated if platform.redis.url is set
│   │   │
│   │   └── ConfigValidationConfig.java
│   │       // @Bean — runs at startup
│   │       // Validates PlatformProperties (always), and optional modules (if declared)
│   │       // Fails fast with clear error message listing each missing/bad value
│   │
│   ├── logging/
│   │   └── LoggingAutoConfiguration.java
│   │       // Configures Logback JSON encoder via logback-spring.xml
│   │       // Injects traceId, spanId (from OTel Java Agent MDC), requestId (from custom filter)
│   │       // Adds service.name, service.version as static fields
│   │
│   ├── metrics/
│   │   └── MetricsAutoConfiguration.java
│   │       // Thin wrapper. Micrometer is auto-configured by Spring Boot Actuator.
│   │       // Adds custom RED meters: http.requests.total, http.request.duration
│   │       // if not already provided by the framework.
│   │
│   ├── errors/
│   │   ├── ProblemDetailResponse.java
│   │   │   // RFC 7807 DTO: type, title, status, detail, instance, requestId
│   │   │
│   │   └── GlobalExceptionHandler.java
│   │       // @ControllerAdvice
│   │       // Maps common exceptions → ProblemDetailResponse
│   │       // Extracts requestId from request attribute
│   │
│   └── lifecycle/
│       └── GracefulShutdownConfig.java
│           // @EventListener(ContextClosedEvent.class)
│           // Flushes OTel spans (calls GlobalOpenTelemetry.get().shutdown())
│           // Logs graceful shutdown start/complete
│
└── src/main/resources/
    ├── logback-spring.xml
    │   <!-- JSON encoder with traceId, spanId, requestId, service fields -->
    └── META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
            <!-- Lists all @AutoConfiguration classes -->
```

### Key Design Decisions (TASK 2 specific)

1. **Java Agent only**: No `OpenTelemetrySdk.builder()` anywhere in code. The agent handles `traceId`/`spanId` injection into MDC. The `requestId` is a separate concern handled by a Servlet `Filter`.

2. **Cached dependency registry**: `CachedDependencyRegistry` uses `ConcurrentHashMap<String, CachedCheck>` where `CachedCheck` has `result`, `lastChecked`, and `ttl`. Thread-safe reads, single-threaded refresh.

3. **Modular config**: `PlatformProperties` is always validated. `DatabaseProperties` is only validated if `platform.database.url` is non-null in the environment. Same for Kafka and Redis. This means a service only declares what it uses.

### Verification (TASK 2)
```bash
cd libs/java && mvn clean compile
# Should compile successfully
# No OpenTelemetry SDK in classpath — Java Agent is separate
```

---

## TASK 3: Create libs/java/ — Request ID Filter

**Priority**: HIGH — `requestId` is needed for all log output
**Dependencies**: TASK 2
**Estimated**: 30 min

### What
Add a Servlet `Filter` that extracts or generates `requestId` and makes it available to the logging framework.

### Files
| Action | File | Details |
|--------|------|---------|
| CREATE | `libs/java/src/main/java/com/paymentapi/platform/logging/RequestIdFilter.java` | Filter: extracts `X-Request-Id` header or generates UUID v4. Sets as request attribute. Injects into SLF4J MDC as `requestId`. |

### Design
```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // Runs early, after tracing filter
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                     HttpServletResponse response, 
                                     FilterChain chain) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
```

Note: `traceId` and `spanId` are automatically injected into MDC by the OTel Java Agent. `requestId` is separate.

---

## TASK 4: Remediate financial-core with libs/java/

**Priority**: HIGH
**Dependencies**: TASK 2, TASK 3
**Estimated**: 1 hour

### What
Update the existing Java service to depend on `libs/java/` and use its auto-configured components. Remove noop `OpenTelemetryConfig`. Replace custom `HealthController` with libs probe controllers.

### Files

| Action | File | Details |
|--------|------|---------|
| MODIFY | `services/java/financial-core/pom.xml` | Add `<dependency>com.paymentapi:platform-libs</dependency>`. Remove direct OTel SDK deps (Agent handles it). |
| DELETE | `services/java/financial-core/src/.../config/OpenTelemetryConfig.java` | No longer needed — Java Agent handles OTel |
| MODIFY | `services/java/financial-core/src/.../controller/HealthController.java` | Replace: add `/health` → 301 redirect to `/liveness`. Remove old `/health`, `/ready` implementations (libs provides them). |
| MODIFY | `services/java/financial-core/src/.../FinancialCoreApplication.java` | Add `@ComponentScan("com.paymentapi.platform")` to pick up libs auto-config |
| MODIFY | `services/java/financial-core/src/main/resources/application.yml` | Restructure to match `platform.*` prefix from libs config |
| MODIFY | `services/java/financial-core/src/main/resources/application-local.yml` | Restructure to use `platform.*` namespace for local dev |
| MODIFY | `docker/Dockerfile.java` | Add OTel Java Agent download in build stage, `-javaagent` flag in ENTRYPOINT |
| MODIFY | `docker-compose.yml` | Update env vars to `platform.*` namespace, update healthcheck to `/liveness` |

### Dockerfile.java Update
```dockerfile
# In builder stage — add OTel Java Agent
ARG OTEL_VERSION=1.33.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_VERSION}/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# In runtime stage
COPY --from=builder /app/opentelemetry-javaagent.jar /opentelemetry-javaagent.jar

# Updated ENTRYPOINT
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -javaagent:/opentelemetry-javaagent.jar org.springframework.boot.loader.launch.JarLauncher"]
```

### application.yml Restructure
```yaml
# OLD (flat):
server:
  port: ${SERVER_PORT:8080}
spring:
  datasource:
    url: ${DATABASE_URL}

# NEW (under platform.*):
platform:
  server:
    port: ${SERVER_PORT:8080}
  database:
    url: ${DATABASE_URL:}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:}
  logging:
    level: ${LOG_LEVEL:info}
    format: ${LOG_FORMAT:json}
  otel:
    service-name: ${OTEL_SERVICE_NAME:financial-core}
    service-version: ${SERVICE_VERSION:0.1.0}
    exporter-endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4317}
```

### Verification (TASK 4)
```bash
cd services/java/financial-core && mvn spring-boot:run
curl http://localhost:8080/liveness
# → {"status":"ok","service":"financial-core","version":"0.1.0",...}
curl http://localhost:8080/readiness
# → 503 if DB/Kafka not reachable
curl http://localhost:8080/health
# → 301 → /liveness
curl http://localhost:8080/metrics
# → Prometheus text with http_requests_total
# Verify log output has traceId, spanId, requestId
```

---

## TASK 5: Create libs/go/ — Foundation Package

**Priority**: HIGH
**Dependencies**: TASK 1
**Estimated**: 2 hours

### What
Create `libs/go/` as a single Go module with logically separated sub-packages. Unlike Java, Go uses SDK-based OTel initialization (no agent equivalent).

### Files to Create

```
libs/go/
├── go.mod
│   # module github.com/payment-api/platform-libs
│   # go 1.22
│   # require: chi/v5, otel SDK, otlptracegrpc, prometheus/client_golang
│
├── pkg/
│   ├── telemetry/
│   │   ├── telemetry.go
│   │   │   // Setup(ctx, serviceName, endpoint) → (*sdktrace.TracerProvider, error)
│   │   │   // Creates TracerProvider with OTLP gRPC exporter
│   │   │   // Sets resource attributes: service.name, service.version, service.namespace
│   │   │   // Returns TracerProvider for caller to manage shutdown
│   │   │
│   │   └── middleware.go
│   │       // HTTPMiddleware() → func(http.Handler) http.Handler
│   │       // Extracts W3C traceparent, starts span for each request
│   │       // Injects traceId, spanId into context
│   │
│   ├── health/
│   │   ├── health.go
│   │   │   // Checker interface: Name() string, Check(ctx) → CheckResult
│   │   │   // CachedRegistry struct with TTL cache
│   │   │   // Register(checker Checker)
│   │   │   // Statuses() → map[string]CheckResult (cached, TTL 5s)
│   │   │
│   │   ├── liveness.go
│   │   │   // LivenessHandler(registry) → http.HandlerFunc
│   │   │   // Always 200. No I/O. Only checks goroutine health.
│   │   │
│   │   ├── readiness.go
│   │   │   // ReadinessHandler(registry) → http.HandlerFunc
│   │   │   // Calls registry.Statuses(). 200 if all OK, 503 if any DOWN.
│   │   │   // Cache controls refresh frequency.
│   │   │
│   │   └── startup.go
│   │   │   // StartupHandler(registry) → http.HandlerFunc
│   │   │   // Same as readiness but tracks whether initial startup complete
│   │
│   ├── config/
│   │   ├── config.go
│   │   │   // Modular config structs with envconfig tags
│   │   │   // Config struct: Server, Logging, Otel (mandatory)
│   │   │   // Database, Kafka, Redis are nil if not configured
│   │   │   // Load() → *Config — panics if mandatory fields missing
│   │   │
│   │   └── validate.go
│   │       // Validate(*Config) error
│   │       // Only validates non-nil optional modules
│   │
│   ├── metrics/
│   │   └── metrics.go
│   │       // SetupMetrics() → http.Handler (promhttp handler)
│   │       // Default RED metrics: http_requests_total, http_request_duration_seconds
│   │
│   ├── logging/
│   │   └── context.go
│   │       // ContextHandler with traceId, spanId, requestId attrs
│   │       // Provides slog.Handler wrapper that injects OTel trace context
│   │
│   ├── errors/
│   │   ├── types.go
│   │   │   // ProblemDetail struct (RFC 7807)
│   │   │   // Typed error constants
│   │   └── handler.go
│   │       // ErrorHandler middleware — catches panics, maps errors → ProblemDetail JSON
│   │
│   └── lifecycle/
│       └── shutdown.go
│           // GracefulShutdown(ctx, server, tp) — signal handler
│           // Catches SIGINT/SIGTERM, calls srv.Shutdown(), tp.Shutdown()
│
└── README.md
```

### Key Design Decisions (Go)

1. **Explicit SDK init**: Go has no agent. `Setup()` returns a `*sdktrace.TracerProvider` that the caller `defer`s in `main()`.

2. **Modular config**: `Config.Database` is `*DatabaseConfig` — nil means not configured, skipped in validation. Only validated if non-nil.

3. **Cached registry**: Same pattern as Java. `sync.RWMutex` guarded map with TTL timestamps.

### Verification (TASK 5)
```bash
cd libs/go && go build ./pkg/...
# Should compile cleanly
```

---

## TASK 6: Remediate settlement-service with libs/go/

**Priority**: HIGH
**Dependencies**: TASK 5
**Estimated**: 1.5 hours

### What
Wire the Go service to use `platform-libs` via `go.mod replace`. Replace custom `health.go` handler with libs probe handlers. Add OTel SDK init, metrics handler, structured logging with context.

### Files

| Action | File | Details |
|--------|------|---------|
| MODIFY | `services/go/settlement-service/go.mod` | Add `replace github.com/payment-api/platform-libs => ../../libs/go` |
| MODIFY | `services/go/settlement-service/cmd/server/main.go` | Full rewrite to wire: telemetry.Setup(), health.Register(), metrics.SetupMetrics(), logging.ContextHandler, lifecycle.Shutdown |
| DELETE | `services/go/settlement-service/internal/handler/health.go` | Replaced by libs |
| DELETE | `services/go/settlement-service/internal/handler/health_test.go` | Replaced by libs tests + service-level probe tests |
| MODIFY | `services/go/settlement-service/internal/config/config.go` | Replace with libs `config.Load()` |
| MODIFY | `docker-compose.yml` | Update env vars to `platform.*` namespace for Go service |

### Updated main.go Structure
```go
func main() {
    cfg := config.Load()
    
    // 1. OTel tracing
    ctx := context.Background()
    tp, err := telemetry.Setup(ctx, cfg.Otel)
    if err != nil { log.Fatal(err) }
    defer tp.Shutdown(ctx)
    
    // 2. Health probe registry
    registry := health.NewRegistry(5 * time.Second)
    // Register checks if deps available
    if cfg.Database != nil {
        registry.Register(health.NewDatabaseChecker(cfg.Database.URL))
    }
    
    // 3. HTTP router
    r := chi.NewRouter()
    r.Use(telemetry.HTTPMiddleware())        // OTel span per request
    r.Use(logging.ContextMiddleware(cfg))     // traceId/spanId/requestId in logs
    r.Use(middleware.RequestID)               // generates requestId (fallback)
    
    r.Get("/liveness", health.LivenessHandler(registry))
    r.Get("/readiness", health.ReadinessHandler(registry))
    r.Get("/startup", health.StartupHandler(registry))
    r.Get("/metrics", metrics.SetupMetrics().ServeHTTP)
    
    // 4. Start server with graceful shutdown
    srv := &http.Server{Addr: cfg.Server.Addr(), Handler: r}
    lifecycle.Shutdown(ctx, srv, tp)
}
```

### Verification (TASK 6)
```bash
cd services/go/settlement-service && go run ./cmd/server
curl http://localhost:8088/liveness
# → {"status":"ok","service":"settlement-service",...}
curl http://localhost:8088/metrics
# → Prometheus text
```

---

## TASK 7: Create libs/python/ — Foundation Package

**Priority**: HIGH
**Dependencies**: TASK 1
**Estimated**: 2 hours

### What
Create `libs/python/` as a single Python package (`payment_platform`). Each concern is a module within the package.

### Files to Create

```
libs/python/
├── pyproject.toml
│   # [project] name = "payment-platform"
│   # dependencies: fastapi, uvicorn, opentelemetry-*, prometheus_client, structlog, pydantic-settings
│
├── requirements.txt                        # Pinned versions for reproducibility
│
├── src/payment_platform/
│   ├── __init__.py                         # Re-exports key functions
│   │
│   ├── telemetry.py
│   │   # setup_telemetry(app: FastAPI, service_name: str, endpoint: str) → None
│   │   # Configures OTel SDK with OTLP gRPC exporter
│   │   # Adds FastAPI auto-instrumentation middleware
│   │   # Sets resource attributes
│   │
│   ├── health.py
│   │   # CachedDependencyRegistry class
│   │   #   - register(name: str, check_fn: Callable) → None
│   │   #   - get_status() → dict (cached, TTL 5s)
│   │   # create_liveness_router() → APIRouter
│   │   # create_readiness_router(registry) → APIRouter
│   │   # create_startup_router(registry) → APIRouter
│   │   # ProbeResponse model (Pydantic)
│   │   # CheckResult model
│   │
│   ├── config.py
│   │   # BaseSettings with modular config
│   │   # PlatformSettings (server, logging, otel — mandatory)
│   │   # DatabaseSettings (optional, validated only if database_url is set)
│   │   # KafkaSettings (optional)
│   │   # RedisSettings (optional)
│   │   # load_config() → PlatformSettings (with optional sub-settings)
│   │
│   ├── metrics.py
│   │   # setup_metrics(app: FastAPI) → None
│   │   # Adds prometheus_fastapi_instrumentator
│   │   # Exposes /metrics endpoint
│   │
│   ├── logging.py
│   │   # setup_logging(level: str, format: str) → None
│   │   # Configures structlog for JSON output
│   │   # RequestIdMiddleware: extracts X-Request-Id, injects into structlog context
│   │   # traceId/spanId extracted from OTel context via contextvars
│   │
│   ├── errors.py
│   │   # ProblemDetail model (Pydantic, RFC 7807)
│   │   # install_error_handlers(app: FastAPI) → None
│   │   # Maps common Python exceptions → ProblemDetail JSON responses
│   │
│   └── lifecycle.py
│       # install_shutdown_handler(app: FastAPI, tracer_provider) → None
│       # Catches SIGTERM/SIGINT, drains in-flight requests, flushes OTel spans
│
└── README.md
```

### Key Design Decisions (Python)

1. **SDK-based init** (like Go, unlike Java): Python OTel SDK initializes programmatically. No agent.

2. **Pydantic Settings** for config: Already the best approach. Modular: `PlatformSettings` always loaded. `DatabaseSettings` only if `DATABASE_URL` env var is set.

3. **structlog** for structured JSON logs: Context variables propagate `requestId`, `traceId`, `spanId` across coroutine boundaries.

### Verification (TASK 7)
```bash
cd libs/python && pip install -e .
python -c "from payment_platform.config import PlatformSettings; print('OK')"
```

---

## TASK 8: Remediate fraud-service with libs/python/

**Priority**: HIGH
**Dependencies**: TASK 7
**Estimated**: 1.5 hours

### Files

| Action | File | Details |
|--------|------|---------|
| MODIFY | `services/python/fraud-service/pyproject.toml` | Add `"payment-platform"` as path dependency |
| MODIFY | `services/python/fraud-service/requirements.txt` | Add `opentelemetry-exporter-otlp-proto-grpc`, `opentelemetry-instrumentation-fastapi`, `prometheus-fastapi-instrumentator`, `structlog`, `prometheus-client` |
| MODIFY | `services/python/fraud-service/src/fraud_service/main.py` | Full rewrite: call setup_telemetry, setup_metrics, setup_logging, register health routers, install error handlers, install shutdown |
| MODIFY | `services/python/fraud-service/src/fraud_service/config.py` | Replace with `from payment_platform.config import load_config` |
| DELETE | `services/python/fraud-service/src/fraud_service/__init__.py` | Replace with proper package init |

### Updated main.py Structure
```python
import structlog
from fastapi import FastAPI
from payment_platform.telemetry import setup_telemetry
from payment_platform.health import (
    CachedDependencyRegistry, 
    create_liveness_router, 
    create_readiness_router,
    create_startup_router,
)
from payment_platform.config import load_config
from payment_platform.metrics import setup_metrics
from payment_platform.logging import setup_logging
from payment_platform.errors import install_error_handlers

config = load_config()
setup_logging(config.logging.level, config.logging.format)

app = FastAPI(title="Fraud Service", version="0.1.0")

# Telemetry
setup_telemetry(app, config.otel.service_name, config.otel.exporter_endpoint)

# Metrics
setup_metrics(app)

# Health probes
registry = CachedDependencyRegistry(ttl_seconds=5)
if config.database:
    registry.register("database", DatabaseChecker(config.database.url))

app.include_router(create_liveness_router())
app.include_router(create_readiness_router(registry))
app.include_router(create_startup_router(registry))

# Error handling
install_error_handlers(app)

# Backward compat redirects
@app.get("/health", status_code=301)
async def health_redirect():
    return RedirectResponse("/liveness")

@app.get("/ready", status_code=301)
async def ready_redirect():
    return RedirectResponse("/readiness")
```

### Verification (TASK 8)
```bash
cd services/python/fraud-service && pip install -e ../../libs/python && uvicorn src.fraud_service.main:app
curl http://localhost:8000/liveness
curl http://localhost:8000/metrics
```

---

## TASK 9: Create libs/nodejs/ — Foundation Package

**Priority**: HIGH
**Dependencies**: TASK 1
**Estimated**: 2 hours

### What
Create `libs/nodejs/` as a single npm package with TypeScript. Each concern is a module with barrel exports.

### Files to Create

```
libs/nodejs/
├── package.json
│   # "name": "@payment-api/platform-libs"
│   # "main": "dist/index.js", "types": "dist/index.d.ts"
│   # dependencies: fastify, @opentelemetry/*, prom-client, pino, zod
│   # devDependencies: typescript, @types/node
│
├── tsconfig.json
│
├── src/
│   ├── index.ts                            # Barrel exports
│   │
│   ├── telemetry.ts
│   │   # initTelemetry(serviceName: string, endpoint: string): void
│   │   # Configures NodeSDK with OTLP gRPC exporter
│   │   # Registers Fastify auto-instrumentation
│   │
│   ├── health.ts
│   │   # CachedDependencyRegistry class (TTL cache)
│   │   # healthPlugin: FastifyPluginAsync
│   │   #   Registers GET /liveness, /readiness, /startup
│   │   #   Uses registry for dependency checks
│   │   # CheckResult, ProbeResponse types
│   │
│   ├── config.ts
│   │   # PlatformConfig Zod schema (server, logging, otel — mandatory)
│   │   # DatabaseConfig, KafkaConfig, RedisConfig — optional, validated if present
│   │   # loadConfig() → PlatformConfig & { database?, kafka?, redis? }
│   │   # Fails fast with clear Zod error messages
│   │
│   ├── metrics.ts
│   │   # metricsPlugin: FastifyPluginAsync
│   │   #   Registers GET /metrics with prom-client
│   │   #   Adds RED metric collectors on request hooks
│   │
│   ├── logging.ts
│   │   # requestIdPlugin: FastifyPluginAsync
│   │   #   Extracts/generates requestId from X-Request-Id
│   │   #   Creates pino child logger with traceId, spanId, requestId
│   │   #   Attaches child logger to request.log
│   │
│   ├── errors.ts
│   │   # errorHandler: FastifyErrorHandler
│   │   #   Maps errors → ProblemDetail JSON (RFC 7807)
│   │   #   Includes requestId in error response
│   │
│   └── lifecycle.ts
│       # setupGracefulShutdown(server: FastifyInstance, sdk: NodeSDK): void
│       #   Listens for SIGTERM/SIGINT
│       #   Closes server, shuts down SDK
│
└── README.md
```

### Key Design Decisions (Node.js)

1. **Pino child loggers**: Fastify's built-in pino logger. Each request gets a child logger with `traceId`, `spanId`, `requestId` via `request.log = request.log.child({...})`.

2. **Zod for config**: TypeScript types are inferred from Zod schemas. No need to duplicate type definitions.

3. **Fastify plugins**: Each concern is a Fastify plugin, composable via `app.register()`.

### Verification (TASK 9)
```bash
cd libs/nodejs && npm install && npm run build
# Should compile TypeScript cleanly
```

---

## TASK 10: Remediate notification-service with libs/nodejs/

**Priority**: HIGH
**Dependencies**: TASK 9
**Estimated**: 1.5 hours

### Files

| Action | File | Details |
|--------|------|---------|
| MODIFY | `services/nodejs/notification-service/package.json` | Add `"@payment-api/platform-libs": "file:../../libs/nodejs"` |
| MODIFY | `services/nodejs/notification-service/src/main.ts` | Full rewrite: initTelemetry, register healthPlugin/metricsPlugin/loggingPlugin, loadConfig with Zod, graceful shutdown |
| MODIFY | `services/nodejs/notification-service/src/config.ts` | Replace with `import { loadConfig } from '@payment-api/platform-libs/config'` |
| MODIFY | `docker-compose.yml` | Update env vars to standardized names for notification-service |

### Updated main.ts Structure
```typescript
import Fastify from 'fastify';
import { initTelemetry } from '@payment-api/platform-libs/telemetry';
import { healthPlugin } from '@payment-api/platform-libs/health';
import { metricsPlugin } from '@payment-api/platform-libs/metrics';
import { requestIdPlugin } from '@payment-api/platform-libs/logging';
import { errorHandler } from '@payment-api/platform-libs/errors';
import { setupGracefulShutdown } from '@payment-api/platform-libs/lifecycle';
import { loadConfig } from '@payment-api/platform-libs/config';

async function start() {
  const config = loadConfig();

  // Must be first — initializes OTel SDK
  initTelemetry(config.otel.serviceName, config.otel.exporterEndpoint);

  const app = Fastify({
    logger: {
      level: config.logging.level,
      transport: config.logging.format === 'text' 
        ? { target: 'pino-pretty' } 
        : undefined,
    },
  });

  // Plugins (order matters: telemetry → requestId → health → metrics → error handler)
  await app.register(requestIdPlugin);
  await app.register(healthPlugin, { 
    registry: /* CachedDependencyRegistry with DB/Kafka checks */ 
  });
  await app.register(metricsPlugin);

  // Error handler
  app.setErrorHandler(errorHandler);

  // Backward compat
  app.get('/health', async (_req, reply) => reply.redirect(301, '/liveness'));
  app.get('/ready', async (_req, reply) => reply.redirect(301, '/readiness'));

  // Graceful shutdown
  setupGracefulShutdown(app);

  await app.listen({ port: config.server.port, host: config.server.host });
}

start();
```

### Verification (TASK 10)
```bash
cd services/nodejs/notification-service && npm install && npm run dev
curl http://localhost:3001/liveness
curl http://localhost:3001/metrics
```

---

## TASK 11: Add remaining packages — logging (Go, Python, Node.js)

**Priority**: MEDIUM
**Dependencies**: TASK 5, TASK 7, TASK 9
**Estimated**: 1.5 hours

### What
Complete the `logging` modules in Go, Python, and Node.js (Java already done in TASK 2-3). Ensure all 4 services produce structured JSON logs with `traceId`, `spanId`, `requestId`.

### Files

| Language | File | What |
|----------|------|------|
| Go | `libs/go/pkg/logging/context.go` | `ContextMiddleware` chi middleware — reads OTel span context, injects into slog attrs. `WithTraceContext()` helper for manual log calls. |
| Python | `libs/python/src/payment_platform/logging.py` | `RequestIdMiddleware` — Starlette middleware extracting X-Request-Id. `structlog` configuration with `trace_id`, `span_id`, `request_id` context variables. |
| Node.js | `libs/nodejs/src/logging.ts` | `requestIdPlugin` — Fastify `onRequest` hook extracting/generating requestId. Creates pino child logger per request. | Already started in TASK 9, finish implementation. |

### Standardized Log Output (all 4 services):
```json
{
  "timestamp": "2026-06-03T12:00:00.000Z",
  "level": "info",
  "service": "settlement-service",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Processing request"
}
```

### Verification (TASK 11)
- Start each service, make a request, check logs contain all 3 IDs
- `docker-compose logs | jq '.traceId, .spanId, .requestId'` — no null values

---

## TASK 12: Add remaining packages — metrics (Go, Python, Node.js)

**Priority**: MEDIUM
**Dependencies**: TASK 5, TASK 7, TASK 9
**Estimated**: 1 hour

### What
Complete metrics modules. All 4 services must expose `/metrics` in Prometheus format with RED metrics. Java already has this via Spring Actuator + Micrometer.

### Files

| Language | File | What |
|----------|------|------|
| Go | `libs/go/pkg/metrics/metrics.go` | `SetupMetrics()` — creates `promhttp.Handler()`. Registers `http_requests_total`, `http_request_duration_seconds` collectors. Provides middleware wrapper for chi. |
| Python | `libs/python/src/payment_platform/metrics.py` | `setup_metrics(app)` — adds `prometheus_fastapi_instrumentator.Instrumentator`. Exposes `/metrics`. Tracks request count, latency, status. |
| Node.js | `libs/nodejs/src/metrics.ts` | `metricsPlugin` — Fastify plugin. Exposes `/metrics` via `prom-client`. Collects RED metrics on `onResponse` hook. |

### Verification (TASK 12)
```bash
curl http://localhost:8080/metrics | grep http_requests_total   # Java
curl http://localhost:8088/metrics | grep http_requests_total   # Go
curl http://localhost:8000/metrics | grep http_requests_total   # Python
curl http://localhost:3001/metrics | grep http_requests_total   # Node.js
# All should return metrics
```

---

## TASK 13: Add remaining packages — errors (all 4 languages)

**Priority**: MEDIUM
**Dependencies**: TASK 11
**Estimated**: 1.5 hours

### What
Standardized error handling across all 4 languages. Every service returns RFC 7807 Problem Details for errors.

### Standard Error Format
```json
{
  "type": "https://api.payment.com/errors/internal-error",
  "title": "Internal Server Error",
  "status": 500,
  "detail": "Unexpected error processing request",
  "instance": "/v1/payments",
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### Files

| Language | File | What |
|----------|------|------|
| Java | `libs/java/src/.../errors/ProblemDetailResponse.java` | Record with RFC 7807 fields. |
| Java | `libs/java/src/.../errors/GlobalExceptionHandler.java` | @ControllerAdvice. Maps ValidationException→400, ResourceNotFound→404, generic→500. |
| Go | `libs/go/pkg/errors/types.go` | ProblemDetail struct. Typed error constants. |
| Go | `libs/go/pkg/errors/handler.go` | Middleware: recovers panics, maps errors to ProblemDetail JSON. |
| Python | `libs/python/src/payment_platform/errors.py` | ProblemDetail Pydantic model. Exception handler mapping. |
| Node.js | `libs/nodejs/src/errors.ts` | ProblemDetail type. Fastify error handler. |

### Verification (TASK 13)
```bash
# Trigger an error (e.g., 404 on unknown path)
curl http://localhost:8080/v1/nonexistent
# → {"type":"...","title":"Not Found","status":404,"detail":"...","instance":"/v1/nonexistent","requestId":"..."}
```

---

## TASK 14: Add remaining packages — lifecycle (Go, Python, Node.js)

**Priority**: LOW — can defer if time runs short
**Dependencies**: TASK 5, TASK 7, TASK 9
**Estimated**: 1 hour

### What
Graceful shutdown handlers for Go, Python, Node.js. Java already has Spring Boot's ContextClosedEvent handler in libs.

### Files

| Language | File | What |
|----------|------|------|
| Go | `libs/go/pkg/lifecycle/shutdown.go` | `GracefulShutdown(ctx, srv, tp)` — OS signal handler, drain HTTP, flush OTel spans, close tracer. |
| Python | `libs/python/src/payment_platform/lifecycle.py` | `install_shutdown_handler(app, tp)` — SIGTERM/SIGINT handler, drain in-flight requests, shutdown tracer. |
| Node.js | `libs/nodejs/src/lifecycle.ts` | `setupGracefulShutdown(server)` — Process signal listener, close Fastify, shutdown OTel SDK. |

### Verification (TASK 14)
```bash
# Start service, send SIGTERM
docker-compose stop settlement-service
docker-compose logs settlement-service | tail -5
# Should show: "shutting down gracefully", "otel tracer shutdown complete", "server stopped"
```

---

## TASK 15: Docker Compose Standardization

**Priority**: HIGH
**Dependencies**: TASK 1
**Estimated**: 1 hour

### What
Standardize all env vars across docker-compose services. Use consistent names (`SERVER_PORT`, not `PORT`). All services point to otel-collector. Healthchecks use `/liveness`.

### Changes

| Change | Details |
|--------|---------|
| Add `otel-collector` service | From TASK 1 |
| Standardize env var names | `SERVER_PORT`, `DATABASE_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317` |
| Update healthcheck paths | `/health` → `/liveness` for Java, Python, Node.js. Go: no healthcheck (scratch). |
| Add missing env vars | `LOG_LEVEL`, `LOG_FORMAT`, `SERVICE_VERSION` to all services |
| Fix Go OTel URL | Was `jaeger:4317` → now `http://otel-collector:4317` |
| Add `KAFKA_CONSUMER_GROUP` | Each service gets its own consumer group |

### Verification (TASK 15)
```bash
docker-compose down -v
docker-compose up -d
# Wait 60s for all services to start
docker-compose ps
# All services should be "healthy" (except Go — no healthcheck in scratch)
curl http://localhost:8080/liveness && echo "Java OK"
curl http://localhost:8000/liveness && echo "Python OK"
curl http://localhost:3001/liveness && echo "Node.js OK"
curl http://localhost:8088/liveness && echo "Go OK"
```

---

## TASK 16: Architecture Fitness Tests — libs Boundaries

**Priority**: MEDIUM
**Dependencies**: TASK 2, TASK 5, TASK 7, TASK 9
**Estimated**: 1.5 hours

### What
Create arch tests that verify internal libs boundaries. No cross-package imports within libs (except health → config, which is allowed). Applied to all 4 languages.

### Files

```
libs/archtest/
├── libs/
│   ├── java/
│   │   └── LibsArchitectureTest.java
│   │       // ArchUnit test: no cycles, no imports between sub-packages
│   │       // Allowed exception: health → config
│   │       // Run via Maven: mvn test -pl libs/java -Dtest=LibsArchitectureTest
│   │
│   ├── go/
│   │   └── import_rules_test.go
│   │       // Go vet custom check or simple grep-based test
│   │       // Verifies pkg/telemetry does not import pkg/health
│   │       // Verifies pkg/health only imports pkg/config (and stdlib)
│   │
│   ├── python/
│   │   └── test_imports.py
│   │       // Uses import-linter or manual AST scan
│   │       // Forbidden: payment_platform.telemetry imports payment_platform.health
│   │       // Allowed: payment_platform.health imports payment_platform.config
│   │
│   └── nodejs/
│       └── arch-rules.test.ts
│           // Uses dependency-cruiser programmatic API or manual check
│           // Forbidden: @payment-api/platform-libs/telemetry → /health
│           // Allowed: @payment-api/platform-libs/health → /config
```

**Import boundary rules (enforced):**

| From | Cannot Import |
|------|---------------|
| telemetry | health, config, metrics, logging, errors, lifecycle |
| health | telemetry, metrics, logging, errors, lifecycle |
| config | telemetry, health, metrics, logging, errors, lifecycle |
| metrics | telemetry, health, config, logging, errors, lifecycle |
| logging | telemetry, health, config, metrics, errors, lifecycle |
| errors | telemetry, health, config, metrics, logging, lifecycle |
| lifecycle | telemetry, health, config, metrics, logging, errors |

**Exception**: `health` → `config` is allowed (health needs config to know which deps to check).

---

## TASK 17: Architecture Fitness Tests — Service Boundaries

**Priority**: MEDIUM
**Dependencies**: TASK 4, TASK 6, TASK 8, TASK 10
**Estimated**: 1.5 hours

### What
Create arch tests that verify service-level architecture rules. Services import from libs, never from other services. Layer boundaries enforced (controller → service → repository).

### Files

```
libs/archtest/
├── services/
│   ├── java/
│   │   └── ServiceArchitectureTest.java
│   │       // ArchUnit: no imports from other services
│   │       // Layers: controller → service → repository (no reverse)
│   │       // Domain code does not import Spring annotations
│   │
│   ├── go/
│   │   └── service_import_test.go
│   │       // Verifies settlement-service does not import financial-core
│   │       // Verifies internal/handler → internal/service → internal/repository layering
│   │
│   ├── python/
│   │   └── test_service_boundaries.py
│   │       // Verifies fraud_service does not import from other service packages
│   │       // Verifies routers → services → repositories layering
│   │
│   └── nodejs/
│       └── service-arch.test.ts
│           // Verifies notification-service does not import other services
│           // Verifies routes → services → repositories layering
│
└── scripts/
    ├── check-port-uniqueness.sh
    │   // Parses docker-compose.yml, detects duplicate port mappings
    │   // Fails with list of conflicts if found
    │
    └── check-config-completeness.sh
        // Parses docker-compose.yml, verifies all services have required env vars
        // Checks: SERVER_PORT, DATABASE_URL (optional), KAFKA_BOOTSTRAP_SERVERS (optional)
        // Checks: OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME, LOG_LEVEL, LOG_FORMAT
```

### Verification (TASK 17)
```bash
make arch-test
# Should pass. If any cross-service import found, fails with clear message.
```

---

## TASK 18: Create Scaffold Scripts (4 scripts)

**Priority**: MEDIUM
**Dependencies**: TASK 4, TASK 6, TASK 8, TASK 10 (need working services as templates)
**Estimated**: 2 hours

### What
Create 4 bash scripts that generate a new service from template. Each service includes:
1. Full project structure matching libs conventions
2. Working `/liveness`, `/readiness`, `/startup` endpoints
3. OTel tracing, Prometheus metrics, structured logging
4. Architecture fitness test file
5. Default ADR-0001

### Scripts

```
scripts/
├── scaffold-java.sh      # Usage: bash scripts/scaffold-java.sh payment-service
├── scaffold-python.sh    # Usage: bash scripts/scaffold-python.sh fraud-detector-v2
├── scaffold-nodejs.sh    # Usage: bash scripts/scaffold-nodejs.sh email-service
└── scaffold-go.sh        # Usage: bash scripts/scaffold-go.sh reconciliation-service
```

### Scaffold Logic (per language, same pattern)
```bash
#!/bin/bash
# scaffold-java.sh
NAME=$1
if [ -z "$NAME" ]; then echo "Usage: scaffold-java.sh <service-name>"; exit 1; fi

SERVICE_DIR="services/java/$NAME"
PACKAGE_DIR="com/paymentapi/$(echo $NAME | tr '-' '.')"
CLASS_NAME=$(echo $NAME | sed -E 's/(^|-)([a-z])/\U\2/g')

# 1. Copy template
cp -r templates/service/java "$SERVICE_DIR"

# 2. Replace placeholders
find "$SERVICE_DIR" -type f -exec sed -i \
  -e "s/{{SERVICE_NAME}}/$NAME/g" \
  -e "s/{{CLASS_NAME}}/$CLASS_NAME/g" \
  -e "s/{{PACKAGE_PATH}}/$PACKAGE_DIR/g" \
  {} +

# 3. Generate ADR-0001
mkdir -p "$SERVICE_DIR/docs/adr"
cat > "$SERVICE_DIR/docs/adr/ADR-0001-${NAME}-architecture.md" <<EOF
# ADR-0001: ${CLASS_NAME} Architecture

## Status
Accepted

## Context
The ${CLASS_NAME} service is part of the Payment API Platform.

## Decision
- Language: Java 21
- Framework: Spring Boot 3.3 with platform-libs
- Storage: PostgreSQL (own database: ${NAME//-/_}_db)
- Messaging: Kafka (if configured)

## Consequences
- Standard probe endpoints at /liveness, /readiness, /startup
- OTel traces via gRPC to otel-collector
- Prometheus metrics at /metrics
- Structured JSON logging with traceId, spanId, requestId
EOF

echo "✅ Service scaffolded: $SERVICE_DIR"
echo "   cd $SERVICE_DIR && mvn spring-boot:run"
```

### Templates Directory Structure
```
templates/service/
├── java/
│   ├── pom.xml.tmpl
│   ├── src/main/java/{{PACKAGE_PATH}}/{{CLASS_NAME}}Application.java.tmpl
│   ├── src/main/resources/application.yml.tmpl
│   ├── src/main/resources/application-local.yml.tmpl
│   ├── src/test/java/{{PACKAGE_PATH}}/ProbesTest.java.tmpl
│   └── src/test/java/{{PACKAGE_PATH}}/ArchitectureFitnessTest.java.tmpl
├── go/
│   ├── go.mod.tmpl
│   ├── cmd/server/main.go.tmpl
│   ├── test/probes_test.go.tmpl
│   ├── test/architecture_test.go.tmpl
│   └── .golangci.yml.tmpl
├── python/
│   ├── pyproject.toml.tmpl
│   ├── src/{{SERVICE_NAME}}/main.py.tmpl
│   ├── tests/test_probes.py.tmpl
│   └── tests/test_architecture.py.tmpl
└── nodejs/
    ├── package.json.tmpl
    ├── src/main.ts.tmpl
    ├── tests/probes.test.ts.tmpl
    └── tests/architecture.test.ts.tmpl
```

### Verification (TASK 18)
```bash
# Test all 4
make scaffold-java NAME=test-java-svc
make scaffold-go NAME=test-go-svc
make scaffold-python NAME=test-python-svc
make scaffold-nodejs NAME=test-nodejs-svc

# Verify each scaffolded service starts and responds
# Then clean up:
rm -rf services/java/test-java-svc services/go/test-go-svc services/python/test-python-svc services/nodejs/test-nodejs-svc
```

---

## TASK 19: Update Makefile with DX Targets

**Priority**: MEDIUM
**Dependencies**: TASK 16, TASK 17, TASK 18
**Estimated**: 45 min

### What
Add new Make targets and update existing ones.

### New/Updated Targets

```makefile
# New targets
arch-test:         ## Run ALL architecture fitness tests (libs + services + scripts)
build-libs:        ## Build all 4 language platform libraries
dev-infra:         ## Start infrastructure only (no app services)
dev-services:      ## Start app services only
dev-hot-reload:    ## Start services in hot-reload (bypass Docker)

# Updated targets
dev:               ## (unchanged, alias for dev-up)
dev-up:            ## Updated: starts all infra + services, prints new endpoint URLs
scaffold-java:     ## (unchanged, script now generates ADR + arch tests)
scaffold-python:   ## (unchanged)
scaffold-nodejs:   ## (unchanged)
scaffold-go:       ## (unchanged)
```

### arch-test Make Target Detail
```makefile
arch-test: ## Run architecture fitness tests
	$(call log,Running architecture fitness tests...)
	@# Libs boundary checks (per language)
	cd libs/java && mvn test -Dtest="*LibsArchitectureTest" -q 2>/dev/null || echo "  $(YELLOW)Java libs arch test failed$(RESET)"
	cd libs/go && go test ./pkg/... -run "Architecture" -count=1 2>/dev/null || echo "  $(YELLOW)Go libs arch test failed$(RESET)"
	cd libs/python && python -m pytest tests/test_architecture.py -q 2>/dev/null || echo "  $(YELLOW)Python libs arch test failed$(RESET)"
	cd libs/nodejs && npx vitest run tests/arch-rules.test.ts 2>/dev/null || echo "  $(YELLOW)Node.js libs arch test failed$(RESET)"
	@# Service boundary checks (per language)
	cd services/java && mvn test -Dtest="*ServiceArchitectureTest" -q 2>/dev/null || true
	@# Script checks
	bash libs/archtest/scripts/check-port-uniqueness.sh || exit 1
	bash libs/archtest/scripts/check-config-completeness.sh || exit 1
	$(call log,Architecture fitness tests complete)
```

---

## TASK 20: Create Service Templates (4 templates)

**Priority**: MEDIUM
**Dependencies**: TASK 18 (scaffold scripts reference templates)
**Estimated**: 1.5 hours

### What
Create `templates/service/{java,go,python,nodejs}/` directories with template files. Each template includes:

1. Build file (pom.xml, go.mod, pyproject.toml, package.json)
2. Main entry point with all libs wired
3. Probe test file
4. Architecture fitness test file
5. README.md

### Template Content (Java example)

`templates/service/java/pom.xml.tmpl`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.paymentapi</groupId>
        <artifactId>platform-libs</artifactId>
        <version>0.1.0</version>
        <relativePath>../../libs/java</relativePath>
    </parent>
    <artifactId>{{SERVICE_NAME}}</artifactId>
    <name>{{CLASS_NAME}}</name>
    <dependencies>
        <dependency>
            <groupId>com.paymentapi</groupId>
            <artifactId>platform-libs</artifactId>
        </dependency>
    </dependencies>
</project>
```

`templates/service/java/.../{{CLASS_NAME}}Application.java.tmpl`:
```java
package com.paymentapi.{{PACKAGE_NAME}};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.paymentapi")
public class {{CLASS_NAME}}Application {
    public static void main(String[] args) {
        SpringApplication.run({{CLASS_NAME}}Application.class, args);
    }
}
```

Same pattern for Go, Python, Node.js templates.

---

## TASK 21: Update Prometheus Config

**Priority**: LOW
**Dependencies**: TASK 1 (collector), TASK 12 (metrics)
**Estimated**: 15 min

### What
Update `shared/config/prometheus.yml` to reflect new port configurations and add otel-collector metrics scraping if desired.

### Changes
- Keep existing service scrape targets (addresses are same)
- Update `metrics_path` comments — all should be `/metrics` for non-Java, `/actuator/prometheus` for Java
- Add optional `otel-collector` scrape target for collector's own metrics
- Remove nonexistent `payment-service:8080` target (service doesn't exist yet)

---

## TASK 22: Full Integration Verification

**Priority**: HIGH
**Dependencies**: TASK 1-21
**Estimated**: 1 hour

### What
End-to-end verification that all 4 services work together via docker-compose.

### Verification Steps

```bash
# 1. Clean start
docker-compose down -v
docker-compose up -d
sleep 60  # Wait for all services to be healthy

# 2. Verify all services respond
curl -s http://localhost:8080/liveness | jq '.status'  # "ok" (Java)
curl -s http://localhost:8000/liveness | jq '.status'  # "ok" (Python)
curl -s http://localhost:3001/liveness | jq '.status'  # "ok" (Node.js)
curl -s http://localhost:8088/liveness | jq '.status'  # "ok" (Go)

# 3. Verify readiness (should be OK since infra is up)
curl -s http://localhost:8080/readiness | jq '.checks'  # database: ok
curl -s http://localhost:8088/readiness | jq '.checks'  # database: ok

# 4. Verify /metrics endpoints
curl -s http://localhost:8080/actuator/prometheus | grep http_requests_total
curl -s http://localhost:8000/metrics | grep http_requests_total
curl -s http://localhost:3001/metrics | grep http_requests_total
curl -s http://localhost:8088/metrics | grep http_requests_total

# 5. Verify traces in Jaeger (via collector)
# Make a few requests to generate traces
for i in {1..5}; do curl -s http://localhost:8080/liveness > /dev/null; done
# Open Jaeger UI: http://localhost:16686
# Search for service: financial-core → should show traces

# 6. Verify structured logs
docker-compose logs financial-core | head -5 | jq '.traceId, .spanId, .requestId'
# All should be non-null

# 7. Verify backward compatibility
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health   # 301
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ready    # 301

# 8. Verify readiness fails with DB down
docker-compose stop postgres
sleep 5
curl -s http://localhost:8080/readiness | jq '.checks.database.status'  # "down"
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/readiness  # 503
docker-compose start postgres

# 9. Verify arch tests
make arch-test  # Should pass with all checks

# 10. Verify scaffold
make scaffold-java NAME=verify-svc
cd services/java/verify-svc && mvn compile  # Should compile
cd ../../.. && rm -rf services/java/verify-svc
```

---

## TASK 23: Run Existing Test Suite

**Priority**: HIGH
**Dependencies**: TASK 4, TASK 6, TASK 8, TASK 10 (remediated services)
**Estimated**: 30 min

### What
Ensure all existing tests still pass after changes. Update or remove tests that are broken by the probe endpoint changes.

### Commands
```bash
make test
# Should return 0
# If any test fails, fix the test (don't break backward compat)
```

### Known Test Updates Needed
| Service | Test | Change |
|---------|------|--------|
| Java | `HealthControllerTest.java` | Update to test `/liveness` instead of `/health`. Remove old `/health` test assertions. |
| Go | `health_test.go` | Update to test `/liveness`. Add `/readiness` test. |
| Python | `test_health.py` | Update to test `/liveness`. Add `/readiness` test. |
| Node.js | `health.test.ts` | Update to test `/liveness`. Add `/readiness` test. |

---

## TASK 24: Verify CI Pipeline Stays Green

**Priority**: HIGH
**Dependencies**: TASK 22, TASK 23
**Estimated**: 30 min

### What
Verify that GitHub Actions CI still passes with all changes.

### Steps
- Push changes to a feature branch
- Wait for CI to run
- Verify all jobs pass: lint, test, build, docker-build
- If `arch-test` job was added to CI, verify it passes
- Fix any CI failures

### Known CI Changes Needed
- Update `ci.yml` to add `arch-test` job
- Update any hardcoded health check paths in CI scripts
- Ensure `docker-compose` in CI has enough resources for all 12 infra containers

---

## TASK 25: Write Phase 5 Documentation

**Priority**: MEDIUM
**Dependencies**: TASK 1-24
**Estimated**: 1.5 hours

### What
Write `docs/05-platform-skeleton.md` following the outline from the PLAN.

### Document Structure
```
docs/05-platform-skeleton.md
├── 🎯 Goal
├── 📥 Input (from Phases 1-4)
├── ⚙️ What Was Done
│   ├── Core Library Architecture
│   ├── Package Boundary Map
│   ├── Config Strategy
│   ├── Kubernetes Probe Endpoints
│   ├── OTel Tracing Architecture
│   ├── Request ID Propagation
│   ├── Standardized Error Handling
│   └── Architecture Fitness Tests
├── 📤 Output (artifacts created)
├── ✅ Done Criteria (with verification results)
├── 🧠 Lessons Learned
├── ⚠️ Known Limitations
└── Connection to Phase 6 (CI/CD)
```

---

## 📊 Task Dependency Graph

```
TASK 1 (Collector)
├── TASK 2 (Java libs)
│   ├── TASK 3 (Request ID Filter)
│   └── TASK 4 (Java remediation)
├── TASK 5 (Go libs)
│   └── TASK 6 (Go remediation)
├── TASK 7 (Python libs)
│   └── TASK 8 (Python remediation)
├── TASK 9 (Node.js libs)
│   └── TASK 10 (Node.js remediation)
│
├── TASK 11 (logging — all) ← depends on TASK 5, 7, 9
├── TASK 12 (metrics — all) ← depends on TASK 5, 7, 9
├── TASK 13 (errors — all) ← depends on TASK 11
├── TASK 14 (lifecycle — all) ← depends on TASK 5, 7, 9
├── TASK 15 (docker-compose) ← depends on TASK 1
│
├── TASK 16 (arch tests: libs) ← depends on TASK 2, 5, 7, 9
├── TASK 17 (arch tests: services) ← depends on TASK 4, 6, 8, 10
├── TASK 18 (scaffold scripts) ← depends on TASK 4, 6, 8, 10
├── TASK 19 (Makefile) ← depends on TASK 16, 17, 18
├── TASK 20 (templates) ← depends on TASK 18
├── TASK 21 (Prometheus config) ← depends on TASK 12
│
├── TASK 22 (full verification) ← depends on TASK 1-21
├── TASK 23 (run tests) ← depends on TASK 22
├── TASK 24 (CI check) ← depends on TASK 23
└── TASK 25 (documentation) ← depends on TASK 1-24
```

---

## ⏱️ Time Estimates

| Day | Tasks | Hours |
|-----|-------|-------|
| Day 1 | 1-5 (Infra + Java) | 5h |
| Day 2 | 6-8 (Go + Python) | 5h |
| Day 3 | 9-14 (Node.js + remaining) | 6h |
| Day 4 | 15-21 (Arch tests + Scaffold + Docker) | 5h |
| Day 5 | 22-25 (Verify + Docs) | 4h |
| **Total** | | **~25h** |

---

TASKS complete. Please review the TASKS.

**25 tasks** organized into 5 days:
- Day 1: Infrastructure (OTel Collector) + Java (libs + remediation)
- Day 2: Go + Python (libs + remediation)
- Day 3: Node.js + remaining packages (logging, metrics, errors, lifecycle)
- Day 4: Arch tests + Scaffold scripts + Docker standardization + Templates
- Day 5: Full verification + Test suite + CI check + Documentation

**6 recommendations all incorporated:**
1. ✅ OTel Collector at TASK 1
2. ✅ Java Agent only at TASK 2 (no SDK)
3. ✅ Cached dependency registry at TASK 2 (Java), TASK 5 (Go), TASK 7 (Python), TASK 9 (Node.js)
4. ✅ Modular config at TASK 2 (Java), TASK 5 (Go), TASK 7 (Python), TASK 9 (Node.js)
5. ✅ Separate arch tests: libs (TASK 16) + services (TASK 17)
6. ✅ Scaffold ADR-0001 at TASK 18

Reply **APPROVE** to begin implementation, or provide feedback for adjustments.
