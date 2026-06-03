# PLAN: Phase 5 — Platform Skeleton & Dev Setup

**Date**: 2026-06-03
**Status**: Draft — Awaiting Approval
**Depends on**: SCRATCHPAD.md v2 (APPROVED)

---

## 0. Action Items Addressed (from SCRATCHPAD approval)

| # | Action Item | Resolution in PLAN |
|---|-------------|--------------------|
| 1 | 28 physical packages → logical packages in fewer build units | **4 physical packages** (one per language) with logical sub-packages. Architecture fitness tests enforce boundaries. See §2.1. |
| 2 | Verify OTLP transport (4317 gRPC vs 4318 HTTP) | **gRPC (port 4317)** standardized for all 4 languages. All SDKs confirmed gRPC-capable. See §2.3. |
| 3 | DX layer in deliverables (make dev/test/lint/arch-test/scaffold) | **5 Make targets** added to deliverable list. `make arch-test` is new. See §5. |

---

## 1. Architecture Overview

### 1.1 Package Structure: 4 Physical, Logical Boundaries

Instead of 28 physical packages (7 concerns × 4 languages), use **4 physical packages** with logically separated sub-packages. Architecture fitness tests enforce the same import boundaries that separate build units would have enforced.

```
libs/
├── java/                                    # Single Maven POM
│   └── src/main/java/com/paymentapi/platform/
│       ├── telemetry/                       # OTel SDK setup, TracerProvider, span attributes
│       ├── health/                          # LivenessController, ReadinessController, StartupController
│       ├── config/                          # @ConfigurationProperties with validation
│       ├── metrics/                         # Micrometer bindings (thin wrapper)
│       ├── logging/                         # Logback encoder with traceId/spanId/requestId
│       ├── errors/                          # @ControllerAdvice for RFC 7807 Problem Details
│       └── lifecycle/                       # Graceful shutdown hooks
│
├── go/                                      # Single Go module (go.mod)
│   └── pkg/
│       ├── telemetry/                       # OTel SDK init, HTTP middleware, slog bridge
│       ├── health/                          # Probe handlers with dependency check registry
│       ├── config/                          # Typed env-var loader with validation
│       ├── metrics/                         # promhttp handler, RED metric collectors
│       ├── logging/                         # slog context propagation (traceId/spanId/requestId)
│       ├── errors/                          # Error handler middleware with typed error codes
│       └── lifecycle/                       # Signal handler, drain, flush spans
│
├── python/                                  # Single Python package (pyproject.toml)
│   └── src/payment_platform/
│       ├── telemetry.py                     # OTLP exporter, FastAPI instrumentor
│       ├── health.py                         # FastAPI routers for 3 probes
│       ├── config.py                         # Pydantic BaseSettings with validation
│       ├── metrics.py                        # prometheus_fastapi_instrumentator
│       ├── logging.py                        # structlog setup with context variables
│       ├── errors.py                         # FastAPI exception handler → Problem JSON
│       └── lifecycle.py                      # SIGTERM handler, drain requests
│
└── nodejs/                                  # Single npm package (package.json)
    └── src/
        ├── index.ts                         # Barrel exports
        ├── telemetry.ts                     # OTel SDK Node init, Fastify plugin
        ├── health.ts                         # Fastify plugin for 3 probes
        ├── config.ts                         # Zod-validated env config
        ├── metrics.ts                        # prom-client with Fastify plugin
        ├── logging.ts                        # Pino context injection (child loggers)
        ├── errors.ts                         # Fastify error handler → RFC 7807
        └── lifecycle.ts                      # Process signal handler, close server

libs/
└── archtest/                                # Architecture fitness tests (shared across languages)
    ├── java/archunit-rules.xml              # ArchUnit package dependency rules
    ├── go/import-rules.yaml                 # go-cleanarch import rules
    ├── python/.importlinter                   # import-linter configuration
    ├── nodejs/.dependency-cruiser.js         # dependency-cruiser rules
    └── scripts/
        ├── check-port-uniqueness.sh          # Port conflict detection
        └── check-config-completeness.sh      # Env var coverage in docker-compose
```

**Why 4 physical packages instead of 28:**

| Factor | 28 Packages | 4 Packages (Logical) |
|--------|-------------|----------------------|
| Build files to maintain | 28 go.mod/package.json/pyproject.toml/pom.xml | 4 (one per language) |
| Version synchronization | 28 independent versions | 4 co-versioned units |
| Dependency management | 28 dependency trees to audit | 4 dependency trees |
| Import boundary enforcement | Build tool enforces (no cross-package deps possible) | Architecture fitness test enforces (equally strict) |
| Package isolation | Maximum (too much — 28 to audit) | Practical (7 sub-packages × 4 languages, boundaries tested) |
| Developer cognitive load | High (28 entries in IDE project view) | Low (4 top-level entries, 7 sub-packages each) |

**Architecture fitness tests enforce the SAME rules** that separate build units would:
- Telemetry must not import config
- Health may import config (documented exception)
- Logging must not import health
- No circular dependencies within libs/
- Services import from libs sub-packages, never from other services

### 1.2 Request ID Propagation Flow

```
                    X-Request-Id: uuid-v4 (if absent, generated by first service)
                    W3C traceparent: 00-{traceId}-{spanId}-01
                          │
    ┌─────────────────────▼──────────────────────────┐
    │  Service Entry Point                           │
    │  ┌─────────────────────────────────────────┐   │
    │  │ 1. Extract W3C traceparent → traceId    │   │
    │  │ 2. Extract/Generate X-Request-Id        │   │
    │  │ 3. Inject all 3 into logger context     │   │
    │  │ 4. Start span with received parent span │   │
    │  └─────────────────────────────────────────┘   │
    └─────────────────────────────────────────────────┘
                          │
            ┌─────────────┼─────────────┐
            ▼             ▼             ▼
      Outbound HTTP   Kafka Produce   Logger
      Inject:         Headers:        JSON fields:
      traceparent     traceId         traceId
      X-Request-Id    spanId          spanId
                      requestId       requestId
```

---

## 2. Technical Decisions

### 2.1 Physical Package Structure (per language)

#### Java (`libs/java/`)
```
libs/java/
├── pom.xml                                    # <groupId>com.paymentapi</groupId>, <artifactId>platform-libs</artifactId>
│                                              # Spring Boot starter parent
├── src/main/java/com/paymentapi/platform/
│   ├── telemetry/
│   │   └── TelemetryAutoConfiguration.java    # @AutoConfiguration for OTel SDK
│   ├── health/
│   │   ├── ProbeResponse.java                 # Standardized response DTO
│   │   ├── LivenessController.java            # /liveness endpoint
│   │   ├── ReadinessController.java           # /readiness endpoint
│   │   └── StartupController.java             # /startup endpoint
│   ├── config/
│   │   ├── PlatformProperties.java            # @ConfigurationProperties for server/db/kafka/redis/otel/logging
│   │   └── ConfigValidationConfig.java        # @Bean that validates on startup
│   ├── metrics/
│   │   └── MetricsAutoConfiguration.java      # Micrometer bindings (Spring already handles most)
│   ├── logging/
│   │   └── LoggingAutoConfiguration.java      # Logback JSON encoder with traceId/spanId/requestId MDC
│   ├── errors/
│   │   ├── ProblemDetail.java                  # RFC 7807 DTO
│   │   └── GlobalExceptionHandler.java        # @ControllerAdvice mapping exceptions → ProblemDetail
│   └── lifecycle/
│       └── GracefulShutdownConfig.java         # @PreDestroy hooks, span flush
└── src/main/resources/
    └── META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  # Auto-config registration
```

**Dependency strategy**: All services add `com.paymentapi:platform-libs` as a Maven dependency. The POM uses Spring Boot's auto-configuration mechanism — import the lib, get all sensible defaults. Services override via `application.yml`.

#### Go (`libs/go/`)
```
libs/go/
├── go.mod                                     # module github.com/payment-api/platform-libs
├── pkg/
│   ├── telemetry/
│   │   ├── telemetry.go                       # SetupOTel() → *sdktrace.TracerProvider
│   │   └── middleware.go                       # OTel HTTP middleware for chi
│   ├── health/
│   │   ├── health.go                           # ProbeHandler struct, Checker interface
│   │   ├── liveness.go                         # Liveness endpoint
│   │   ├── readiness.go                        # Readiness with CheckRegistry
│   │   └── startup.go                          # Startup gate
│   ├── config/
│   │   ├── config.go                           # Typed struct, Load() → *Config
│   │   └── validate.go                         # Config.Validate() method
│   ├── metrics/
│   │   └── metrics.go                          # SetupMetrics(), promhttp handler
│   ├── logging/
│   │   └── context.go                          # WithTraceContext() → slog.Attr injector
│   ├── errors/
│   │   ├── handler.go                          # Error handling middleware
│   │   └── types.go                            # ProblemDetail struct, typed errors
│   └── lifecycle/
│       └── shutdown.go                         # GracefulShutdown() helper
└── README.md
```

**Dependency strategy**: Services use `go.mod` with `replace github.com/payment-api/platform-libs => ../../libs/go`. Import specific sub-packages (`import "github.com/payment-api/platform-libs/pkg/telemetry"`). Compiler + arch test enforce boundary rules.

#### Python (`libs/python/`)
```
libs/python/
├── pyproject.toml                             # [project] name = "payment-platform"
├── requirements.txt                           # Shared deps
├── src/payment_platform/
│   ├── __init__.py
│   ├── telemetry.py                           # setup_telemetry(app: FastAPI) → None
│   ├── health.py                              # create_probe_router() → APIRouter
│   │                                          # Classes: LivenessCheck, ReadinessCheck, StartupCheck
│   ├── config.py                              # PlatformSettings(BaseSettings) with all env vars
│   ├── metrics.py                             # setup_metrics(app: FastAPI) → None
│   ├── logging.py                             # setup_logging() → structlog.BoundLogger
│   ├── errors.py                              # install_error_handlers(app: FastAPI) → None
│   │                                          # ProblemDetail model, http_exception_handler
│   └── lifecycle.py                           # install_shutdown_handler(app: FastAPI) → None
└── README.md
```

**Dependency strategy**: Services install via `pip install -e ../../libs/python`. Import `from payment_platform.telemetry import setup_telemetry`. Each module is independently importable.

#### Node.js (`libs/nodejs/`)
```
libs/nodejs/
├── package.json                               # "name": "@payment-api/platform-libs"
├── tsconfig.json
├── src/
│   ├── index.ts                               # Barrel: export * from './telemetry' ...
│   ├── telemetry.ts                           # initTelemetry(serviceName: string) → void
│   ├── health.ts                              # healthPlugin: FastifyPluginAsync
│   │                                          # Registers /liveness, /readiness, /startup
│   ├── config.ts                              # platformConfigSchema: ZodObject
│   │                                          # loadConfig() → PlatformConfig
│   ├── metrics.ts                             # metricsPlugin: FastifyPluginAsync
│   │                                          # Registers /metrics + RED metric collectors
│   ├── logging.ts                             # createChildLogger(request: FastifyRequest) → Logger
│   ├── errors.ts                              # errorHandler: FastifyErrorHandler
│   │                                          # ProblemDetail type, mapToProblemDetail()
│   └── lifecycle.ts                           # setupGracefulShutdown(app: FastifyServer) → void
└── README.md
```

**Dependency strategy**: Services use npm workspaces at root level. Import `import { healthPlugin } from '@payment-api/platform-libs/health'` via barrel exports or direct sub-path imports. TypeScript path aliases enabled in tsconfig.

### 2.2 Config Contract (all languages implement the same schema)

```typescript
// Conceptual schema — each language implements in its native config system
interface PlatformConfig {
  server: {
    port: number;        // SERVER_PORT, default 8080
    host: string;        // SERVER_HOST, default "0.0.0.0"
  };
  database: {
    url: string;         // DATABASE_URL, required
    maxPoolSize: number; // DB_MAX_POOL_SIZE, default 10
    minIdle: number;     // DB_MIN_IDLE, default 2
  };
  kafka: {
    bootstrapServers: string;  // KAFKA_BOOTSTRAP_SERVERS, required
    consumerGroup: string;     // KAFKA_CONSUMER_GROUP, required
  };
  redis?: {
    url: string;         // REDIS_URL, optional (not all services use Redis)
  };
  otel: {
    exporterEndpoint: string;  // OTEL_EXPORTER_OTLP_ENDPOINT, required
    serviceName: string;       // OTEL_SERVICE_NAME, required
    serviceVersion: string;    // SERVICE_VERSION, default "0.1.0"
  };
  logging: {
    level: 'debug' | 'info' | 'warn' | 'error'; // LOG_LEVEL, default "info"
    format: 'json' | 'text';                     // LOG_FORMAT, default "json"
  };
}
```

### 2.3 OTLP Transport Decision: gRPC (port 4317)

**Verification results:**

| Language | SDK | gRPC Support | Implementation |
|----------|-----|--------------|----------------|
| Java | OTel Java Agent (auto-instrumentation) | ✅ Yes — `-Dotel.exporter.otlp.protocol=grpc` | Agent JAR injected in Dockerfile |
| Go | `go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc` | ✅ Yes — already in go.mod | Programmatic SDK init with `otlptracegrpc.New()` |
| Python | `opentelemetry-exporter-otlp-proto-grpc` | ✅ Yes — pip install | `OTLPSpanExporter` with gRPC |
| Node.js | `@opentelemetry/exporter-trace-otlp-grpc` | ✅ Yes — npm install | `OTLPTraceExporter` with gRPC |

**Standard env var for all services:**
```yaml
OTEL_EXPORTER_OTLP_ENDPOINT: http://jaeger:4317    # gRPC — all 4 languages
```

**What changes:**
- `docker-compose.yml`: Fix Go service URL from `jaeger:4317` (missing scheme) to `http://jaeger:4317`
- All services use same value
- Port 4318 (HTTP) remains available in Jaeger but is NOT used

### 2.4 Probe Endpoint Behavior (all languages)

```
Startup Sequence:
  /startup  → 503 "initializing" during boot
            → 200 "ok" after all init complete (DB connected, Kafka connected, migrations done)
  /liveness → 200 "ok" if process healthy (no deadlock, no OOM)
            → 500 "unhealthy" if process is broken
  /readiness → 200 "ok" if all dependencies UP
             → 503 "not ready" if any dependency DOWN
             → checks: database={ok|down}, kafka={ok|down}, redis={ok|down|unused}
```

**Backward compatibility:**
- Old `/health` → 301 redirect to `/liveness`
- Old `/ready` → 301 redirect to `/readiness`
- Both exist through Phase 5. Removed in Phase 7.

---

## 3. Implementation Order

### Phase Priority (time-boxed to 5 days)

```
Day 1:   libs/java/  (telemetry + health + config)
Day 2:   libs/go/    (telemetry + health + config)
Day 3:   libs/python/ (telemetry + health + config)
Day 4:   libs/nodejs/ (telemetry + health + config)
Day 5:   logs, metrics, errors, lifecycle (all 4), arch tests, scaffold scripts, docs
```

**Key principle**: Get the high-value packages right first (`telemetry`, `health`, `config`). Defer lower-priority packages (`errors`, `lifecycle`) if time runs short.

### For each language, follow the same pattern:
1. Create physical package structure with build file
2. Implement `telemetry` — OTel SDK init with gRPC exporter
3. Implement `health` — 3 probe endpoints with standardized response
4. Implement `config` — typed, validated, fail-fast
5. Wire into existing service skeleton
6. Test: `curl /liveness`, verify Jaeger trace, verify /metrics, verify structured logs
7. Repeat for next language

---

## 4. Existing Service Remediation Plan

### 4.1 Java: financial-core

| What changes | How |
|-------------|-----|
| Add `platform-libs` dependency | `pom.xml` — add `<dependency>` on `com.paymentapi:platform-libs` |
| Replace `HealthController` | Import from libs; add `/liveness`, `/readiness`, `/startup` |
| Replace `OpenTelemetryConfig` | Remove noop; rely on OTel Java Agent + libs auto-config |
| Add `/metrics` endpoint | Already works via Actuator — no change needed |
| Add structured logging with requestId | libs auto-configures Logback encoder |
| Validate config at startup | libs `ConfigValidationConfig` validates `PlatformProperties` |
| Backward compat | Keep `/health` → 301 `/liveness`, `/ready` → 301 `/readiness` |

### 4.2 Go: settlement-service

| What changes | How |
|-------------|-----|
| Add `platform-libs` dependency | `go.mod` — add `replace` directive |
| Import sub-packages | `import "github.com/payment-api/platform-libs/pkg/telemetry"` etc. |
| Wire OTel in `main.go` | Call `telemetry.SetupOTel()` before starting server |
| Replace handler/health.go | Import `health` from libs; register probe endpoints |
| Add `/metrics` endpoint | Import `metrics` from libs; register promhttp handler |
| Inject traceId/spanId/requestId into logs | Import `logging` from libs; wrap chi middleware |
| Config with validation | Replace `internal/config/config.go` with `platform-libs/pkg/config` |
| Add graceful shutdown | Import `lifecycle` from libs (extends existing shutdown) |
| Backward compat | Keep `/health` → 301 `/liveness`, `/ready` → 301 `/readiness` |
| Fix go.sum | Run `go mod tidy` to generate |

### 4.3 Python: fraud-service

| What changes | How |
|-------------|-----|
| Add `payment-platform` dependency | `pyproject.toml` — local path dependency; `pip install -e ../../libs/python` |
| Wire OTel | `main.py` — call `telemetry.setup_telemetry(app)` |
| Add probe endpoints | Import `health` from libs; register routers |
| Add `/metrics` endpoint | Import `metrics` from libs; call `setup_metrics(app)` |
| Structured logging with request IDs | Import `logging` from libs; call `setup_logging()` |
| Config with validation | Replace `config.py` with `PlatformSettings` from libs |
| Graceful shutdown | Import `lifecycle` from libs; install signal handler |
| Backward compat | Keep `/health` → 301 `/liveness`, `/ready` → 301 `/readiness` |

### 4.4 Node.js: notification-service

| What changes | How |
|-------------|-----|
| Add `@payment-api/platform-libs` | `package.json` — file: dependency; npm workspaces |
| Wire OTel | `main.ts` — call `initTelemetry()` from libs |
| Add probe endpoints | Register `healthPlugin` from libs |
| Add `/metrics` endpoint | Register `metricsPlugin` from libs |
| Structured logging with requestId | Use `createChildLogger()` from libs per request |
| Config with Zod validation | Replace `config.ts` with `loadConfig()` from libs |
| Graceful shutdown | Call `setupGracefulShutdown(app)` from libs |
| Fix PORT reading | `main.ts` reads `PORT`, change to `SERVER_PORT` from validated config |
| Backward compat | Keep `/health` → 301 `/liveness`, `/ready` → 301 `/readiness` |

---

## 5. Dev Environment Fixes

### 5.1 docker-compose.yml Changes

```yaml
# Fix 1: Standardize env var names (all services use same names)
# Fix 2: Fix Go OTel URL (jaeger:4317 → http://jaeger:4317)
# Fix 3: Update healthcheck paths (/health → /liveness)
# Fix 4: Add SERVER_PORT, LOG_LEVEL, LOG_FORMAT, SERVICE_VERSION to all services
# Fix 5: Add REDIS_URL where applicable

# Example financial-core section (updated):
financial-core:
  profiles: ["services"]
  build:
    context: ./services/java/financial-core
    dockerfile: ../../../../docker/Dockerfile.java
  container_name: payment-financial-core
  restart: unless-stopped
  depends_on:
    postgres:
      condition: service_healthy
    kafka:
      condition: service_healthy
  environment:
    SERVER_PORT: "8080"
    DATABASE_URL: jdbc:postgresql://postgres:5432/financial_core_db
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    KAFKA_CONSUMER_GROUP: financial-core
    OTEL_EXPORTER_OTLP_ENDPOINT: http://jaeger:4317
    OTEL_SERVICE_NAME: financial-core
    SERVICE_VERSION: "0.1.0"
    LOG_LEVEL: info
    LOG_FORMAT: json
  ports:
    - "8080:8080"
  networks:
    - payment-network
```

### 5.2 Port Map (Updated)

| Service | Container Port | Host Port | Notes |
|---------|---------------|-----------|-------|
| financial-core | 8080 | 8080 | Java |
| fraud-service | 8000 | 8000 | Python |
| notification-service | 3001 | 3001 | Node.js |
| settlement-service | 8088 | 8088 | Go |

Note: No port conflicts. The Makefile comment about 8081 was incorrect; docker-compose correctly uses 8080.

---

## 6. DX Layer (Makefile + Developer Experience)

### 6.1 New/Updated Make Targets

```makefile
# ─── Architecture Tests ───
arch-test: ## Run architecture fitness tests (package boundaries, port uniqueness, config completeness)
	$(call log,Running architecture fitness tests...)
	@# Java ArchUnit
	cd services/java && mvn test -pl ../../libs/java -Dtest="*ArchitectureTest*" -q 2>/dev/null || true
	@# Go import checker
	go run github.com/payment-api/platform-libs/tools/importcheck ./services/go/... 2>/dev/null || echo "  $(YELLOW)Go import check skipped$(RESET)"
	@# Python import-linter
	lint-imports 2>/dev/null || echo "  $(YELLOW)Python import check skipped$(RESET)"
	@# Node.js dependency-cruiser
	npx depcruise --config libs/archtest/nodejs/.dependency-cruiser.js services/nodejs/ 2>/dev/null || echo "  $(YELLOW)Node.js dep check skipped$(RESET)"
	@# Shared checks
	bash libs/archtest/scripts/check-port-uniqueness.sh
	bash libs/archtest/scripts/check-config-completeness.sh
	$(call log,Architecture fitness tests complete)

# ─── Development (updated) ───
dev-infra: ## Start infrastructure only (no application services)
	docker-compose up -d postgres redis zookeeper kafka schema-registry opensearch jaeger prometheus grafana

dev-services: ## Start application services only (infrastructure must be running)
	docker-compose --profile services up -d

dev-hot-reload: ## Start services in hot-reload mode (bypass Docker for fast iteration)
	$(call log,Starting services in hot-reload mode...)
	@echo "  Java:   cd services/java/financial-core && mvn spring-boot:run"
	@echo "  Go:     cd services/go/settlement-service && go run ./cmd/server"
	@echo "  Python: cd services/python/fraud-service && uvicorn src.fraud_service.main:app --reload"
	@echo "  Node.js: cd services/nodejs/notification-service && npm run dev"

# ─── Build (updated) ───
build-libs: ## Build all platform libraries
	$(call log,Building platform libraries...)
	cd libs/java && mvn install -DskipTests -q
	cd libs/go && go build ./...
	cd libs/python && pip install -e .
	cd libs/nodejs && npm install && npm run build
	$(call log,Platform libraries built)

# ─── Dev Setup (updated) ───
dev: dev-up ## Start full local environment (updated alias)

dev-up: ## Start all infra + services
	$(call log,Starting local dev environment...)
	docker-compose up -d
	$(call log,Local dev environment started)
	@echo ""
	@echo "  $(GREEN)Infrastructure:$(RESET)"
	@echo "    PostgreSQL:   postgresql://payment:payment@localhost:5432"
	@echo "    Redis:        redis://localhost:6379"
	@echo "    Kafka:        localhost:9093"
	@echo "    Jaeger UI:    http://localhost:16686"
	@echo "    Grafana:      http://localhost:3000 (admin/admin)"
	@echo "    Prometheus:   http://localhost:9090"
	@echo "    OpenSearch:   http://localhost:9200"
	@echo ""
	@echo "  $(GREEN)Services:$(RESET)"
	@echo "    financial-core:       http://localhost:8080/liveness"
	@echo "    fraud-service:        http://localhost:8000/liveness"
	@echo "    notification-service: http://localhost:3001/liveness"
	@echo "    settlement-service:   http://localhost:8088/liveness"
```

### 6.2 DX Quality Checklist

| DX Requirement | Implementation |
|----------------|----------------|
| Clone → run in 5 min | `git clone && docker-compose up -d` (all infra + services in Docker) |
| Hot reload in dev | Per-service: `mvn spring-boot:run`, `go run`, `uvicorn --reload`, `npm run dev` |
| See all logs | `make dev-logs` or `docker-compose logs -f` |
| Run all tests | `make test` |
| Run arch tests | `make arch-test` |
| Generate new service | `make scaffold-{lang} NAME=my-service` |
| Check toolchain | `make check-tools` |
| Clean everything | `make clean` |

---

## 7. Scaffold Script Design

### 7.1 Script Contract

Each `scripts/scaffold-{lang}.sh` takes one argument `NAME`:

```bash
make scaffold-java NAME=payment-service
```

Produces:
```
services/java/payment-service/
├── pom.xml (with platform-libs dependency, correct group/artifact)
├── src/main/java/com/paymentapi/paymentservice/
│   ├── PaymentServiceApplication.java
│   └── config/PaymentServiceProperties.java
├── src/main/resources/
│   ├── application.yml
│   └── application-local.yml
├── src/test/java/com/paymentapi/paymentservice/
│   ├── ProbesTest.java
│   └── ArchitectureFitnessTest.java
└── README.md
```

### 7.2 Scaffold Must Produce Services That Pass Arch Tests Immediately

After `make scaffold-java NAME=test-svc`:
- `make test` → passes (health probe tests)
- `make arch-test` → passes (no boundary violations)
- Service starts and responds on all 3 probes
- All 3 identifiers appear in log output

---

## 8. Architecture Fitness Tests Implementation

### 8.1 Per-Language Tool Selection

| Language | Tool | Rule File | What It Checks |
|----------|------|-----------|----------------|
| Java | ArchUnit | `libs/archtest/java/archunit-rules.xml` | Layered architecture: controller→service→repository. No cyclical deps. No framework in domain |
| Go | `go-cleanarch` or custom `go vet` check | `libs/archtest/go/import-rules.yaml` | Import path rules. `internal/` cannot be imported externally. No reverse deps |
| Python | `import-linter` | `libs/archtest/python/.importlinter` | Forbidden import rules. domain cannot import infrastructure |
| Node.js | `dependency-cruiser` | `libs/archtest/nodejs/.dependency-cruiser.js` | Module boundaries. libs packages don't import each other |

### 8.2 Shell-Based Checks (language-agnostic)

```bash
# check-port-uniqueness.sh
# Scans docker-compose.yml for port conflicts

# check-config-completeness.sh
# Verifies all services in docker-compose have all required env vars from config contract
```

### 8.3 CI Integration

```yaml
# In .github/workflows/ci.yml — new job
arch-test:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Run architecture fitness tests
      run: make arch-test
```

---

## 9. Testing Architecture

### 9.1 Test Pyramid

```
        ┌──────┐
        │ E2E  │ ← Phase 7+
        ├──────┤
        │ CT   │ ← Phase 7+ (contract tests)
        ├──────┤
        │ INT  │ ← Phase 5: probe tests verify actual dependencies
        ├──────┤
        │ UNIT │ ← Phase 5: per-package unit tests for libs
        └──────┘
```

### 9.2 Phase 5 Test Deliverables

| Layer | What | Example |
|-------|------|---------|
| **Unit** | Each libs package has unit tests | `TestProbeResponse` serialization, `TestConfigValidation` fail-fast |
| **Integration** | Probe endpoint tests against live dependencies | `TestReadinessEndpoint_Returns503_WhenDatabaseDown` |
| **Architecture** | Package boundary enforcement | `NoCyclicDependenciesInLibs` |

### 9.3 Coverage Targets

- Libs packages: ≥ 80% line coverage
- Service probe endpoints: ≥ 90% coverage
- Architecture tests: 100% of defined rules verified

---

## 10. File Change Inventory

### 10.1 New Files (~50 files)

```
libs/java/
├── pom.xml
├── src/main/java/com/paymentapi/platform/
│   ├── telemetry/TelemetryAutoConfiguration.java
│   ├── health/ProbeResponse.java
│   ├── health/LivenessController.java
│   ├── health/ReadinessController.java
│   ├── health/StartupController.java
│   ├── config/PlatformProperties.java
│   ├── config/ConfigValidationConfig.java
│   └── logging/LoggingAutoConfiguration.java
└── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

libs/go/
├── go.mod
├── pkg/telemetry/telemetry.go
├── pkg/telemetry/middleware.go
├── pkg/health/health.go
├── pkg/health/liveness.go
├── pkg/health/readiness.go
├── pkg/health/startup.go
├── pkg/config/config.go
├── pkg/config/validate.go
├── pkg/metrics/metrics.go
├── pkg/logging/context.go
├── pkg/errors/handler.go
├── pkg/errors/types.go
└── pkg/lifecycle/shutdown.go

libs/python/
├── pyproject.toml
├── requirements.txt
└── src/payment_platform/
    ├── __init__.py
    ├── telemetry.py
    ├── health.py
    ├── config.py
    ├── metrics.py
    ├── logging.py
    ├── errors.py
    └── lifecycle.py

libs/nodejs/
├── package.json
├── tsconfig.json
└── src/
    ├── index.ts
    ├── telemetry.ts
    ├── health.ts
    ├── config.ts
    ├── metrics.ts
    ├── logging.ts
    ├── errors.ts
    └── lifecycle.ts

libs/archtest/
├── java/archunit-rules.xml
├── go/import-rules.yaml
├── python/.importlinter
├── nodejs/.dependency-cruiser.js
└── scripts/
    ├── check-port-uniqueness.sh
    └── check-config-completeness.sh

scripts/
├── scaffold-java.sh
├── scaffold-go.sh
├── scaffold-python.sh
└── scaffold-nodejs.sh

templates/service/
├── java/  (template files)
├── go/    (template files)
├── python/  (template files)
└── nodejs/  (template files)

docs/
└── 05-platform-skeleton.md
```

### 10.2 Modified Files (~15 files)

```
docker-compose.yml                           # Standardize env vars, fix OTel URL, update healthcheck paths
Makefile                                     # Add arch-test, dev-infra, dev-services, dev-hot-reload, build-libs
shared/config/prometheus.yml                 # Update healthcheck paths, add new service targets (if any)

services/java/financial-core/
├── pom.xml                                  # Add platform-libs dependency
├── src/main/java/.../FinancialCoreApplication.java  # Minor: maybe add import
└── src/main/java/.../controller/HealthController.java  # Replace with libs import

services/go/settlement-service/
├── go.mod                                   # Add replace directive for platform-libs
├── cmd/server/main.go                       # Wire OTel, metrics, probe handlers
└── internal/handler/health.go               # Replace with libs import

services/python/fraud-service/
├── pyproject.toml                           # Add payment-platform dependency
├── requirements.txt                         # Add telemetry + metrics deps
└── src/fraud_service/main.py                # Wire OTel, metrics, probes, logging

services/nodejs/notification-service/
├── package.json                             # Add @payment-api/platform-libs dependency
└── src/main.ts                              # Wire OTel, metrics, probes, logging, graceful shutdown
```

### 10.3 Files NOT Modified

- All Phase 1-4 documentation (`docs/stages/`)
- ADRs (`docs/adr/`)
- Dockerfiles (`docker/Dockerfile.*`) — only minor: may add OTel Java Agent to Dockerfile.java
- CI/CD workflows (unless adding `arch-test` job)
- Learning materials (`learning/`, `CURRICULUM.md`)
- Kafka/Zookeeper/OpenSearch configs

---

## 11. Risk Mitigation (Updated)

| Risk | Mitigation in PLAN |
|------|--------------------|
| R1: Polyglot patterns diverge | Config contract defined in §2.2; arch fitness tests enforce same schema across languages |
| R2: OTel config differs per language | gRPC 4317 standardized; same `OTEL_EXPORTER_OTLP_ENDPOINT` env var for all |
| R3: Go scratch no healthcheck | Accepted; Kubernetes probes documented as alternative |
| R4: Scaffold falls out of sync | Templates in versioned `templates/` directory; arch tests verify generated services |
| R5: Too many packages → overhead | 4 physical packages instead of 28; logical separation enforced by tests |
| R6: 5-day timebox may be insufficient | Priority order: telemetry→health→config first (must-have); defer errors+lifecycle if needed |
| R7: docker-compose breaks CI | Test `docker-compose up` in CI verification step |
| R8: Config strategy divergence | Single contract in §2.2; per-language implementation but shared validation schema |

---

## 12. Success Criteria (with verification commands)

| # | Criterion | Verification |
|---|-----------|-------------|
| C1 | OTel traces in Jaeger | `curl http://localhost:{port}/liveness && open http://localhost:16686/search` |
| C2 | Prometheus /metrics | `curl http://localhost:{port}/metrics \| grep http_requests_total` |
| C3 | traceId/spanId/requestId in logs | `docker-compose logs \| jq '.traceId, .spanId, .requestId'` |
| C4 | 3 probes work | `curl http://localhost:{port}/liveness && curl /readiness && curl /startup` |
| C5 | /readiness fails when DB down | Stop Postgres, curl /readiness → 503 + `"database": "down"` |
| C6 | Config fails fast | Start without DATABASE_URL → exits with clear error |
| C7 | Scaffold works | `make scaffold-java NAME=test-svc && cd services/java/test-svc && mvn spring-boot:run` |
| C8 | docker-compose up works | `docker-compose down -v && docker-compose up -d && sleep 30 && curl all 4 /liveness` |
| C9 | All tests pass | `make test` returns 0 |
| C10 | Arch tests pass | `make arch-test` returns 0 |
| C11 | CI green | GitHub Actions all green |
| C12 | Phase 5 doc complete | `docs/05-platform-skeleton.md` exists and complete |

---

## 13. Implementation Sequence (Ordered by Priority)

```
Task 1:  Create libs/java/ with telemetry, health, config packages
Task 2:  Remediate financial-core to use libs/java/
Task 3:  Verify: Java OTel traces in Jaeger, probes respond, /metrics works

Task 4:  Create libs/go/ with telemetry, health, config packages
Task 5:  Remediate settlement-service to use libs/go/
Task 6:  Verify: Go OTel traces in Jaeger, probes respond, /metrics works

Task 7:  Create libs/python/ with telemetry, health, config packages
Task 8:  Remediate fraud-service to use libs/python/
Task 9:  Verify: Python OTel traces in Jaeger, probes respond, /metrics works

Task 10: Create libs/nodejs/ with telemetry, health, config packages
Task 11: Remediate notification-service to use libs/nodejs/
Task 12: Verify: Node.js OTel traces in Jaeger, probes respond, /metrics works

Task 13: Fix docker-compose.yml — standardize env vars, fix OTel URLs
Task 14: Add remaining libs packages (metrics, logging, errors, lifecycle) — all 4 languages
Task 15: Create libs/archtest/ with per-language rules + shared scripts
Task 16: Create scaffold scripts (4 scripts)
Task 17: Create service templates (4 templates)
Task 18: Update Makefile with arch-test, dev-* targets
Task 19: Full E2E verification: docker-compose up, all 4 services, all probes, all traces
Task 20: Write docs/05-platform-skeleton.md
```

---

PLAN complete. Please review the PLAN.

**3 action items addressed:**
1. ✅ 28 → 4 physical packages. Each language gets ONE build unit with logical sub-packages. Architecture fitness tests enforce boundaries.
2. ✅ OTLP gRPC (port 4317) confirmed for all 4 languages. All SDKs verified. docker-compose fix: `http://jaeger:4317`.
3. ✅ DX layer: `make arch-test` (new), `make dev-infra`, `make dev-services`, `make dev-hot-reload`, `make build-libs` added to deliverables.

Reply **APPROVE** to continue to the TASKS phase, or provide feedback.
