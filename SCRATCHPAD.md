# SCRATCHPAD: Phase 5 — Platform Skeleton & Dev Setup

**Date**: 2026-06-03
**Status**: Draft v2 — Updated with mandatory changes
**Phase**: Phase 5 of 9 (Minimum Build System Workflow)

---

## 🔄 Changelog (v2)

| # | Change | Reason |
|---|--------|--------|
| 1 | `libs/core/*` → focused packages with clear boundaries | Monolithic core creates hidden coupling; single-responsibility packages scale to 19 services |
| 2 | `traceId` + `spanId` + `requestId` — all three standardized | OTel W3C trace context + application-level request correlation are different concerns |
| 3 | Kubernetes probe endpoints: `/liveness`, `/readiness`, `/startup` | Follows K8s pod lifecycle conventions exactly |
| 4 | Config Strategy standardized across 4 languages | Typed, validated, env-var-driven, profile-aware — same contract, per-language implementation |
| 5 | Architecture Fitness Tests added to deliverables | Prevent architecture erosion as platform scales to 19 services |

---

## 1. Current State Assessment

### 1.1 Phases Complete (✅ Documented)

| Phase | Deliverable | Status |
|-------|-------------|--------|
| Phase 1 (Discovery) | `01-product-discovery.md`, `02-requirements-slos.md`, `03-risk-analysis.md` | ✅ Documented |
| Phase 2 (Architecture) | `04-domain-design.md`, `05-security-architecture.md`, `06-high-level-architecture.md` | ✅ Documented |
| Phase 3 (Contracts) | `07-data-architecture.md`, `08-api-design.md`, `09-event-schema-governance.md` | ✅ Documented |
| Phase 4 (Flows & Tech) | `10-system-flows.md`, `11-technology-selection.md`, `12-infrastructure-design.md` | ✅ Documented |
| ADR | `adr/ADR-001-polyglot-architecture.md` | ✅ Accepted |

### 1.2 What Partially Exists (Phase 5 pre-work)

| Component | Current State | Gap |
|-----------|---------------|-----|
| Docker compose | Full 12-infra + 4-service environment | Go port bug, env var inconsistencies |
| Service skeletons | 4 services with health endpoints | No business logic, no working OTel |
| CI/CD | `.github/workflows/ci.yml`, `cd.yml` | Pipeline exists but services untested |
| Makefile | 315 lines, 15+ targets | Scaffold scripts referenced but missing |
| Dockerfiles | 4 multi-stage Dockerfiles | Go missing healthcheck, path fragility |
| OTel dependencies | Libraries in all 4 build files | **Zero services have working OTel** |
| Metrics | Only Java has `/metrics` endpoint | 3 of 4 missing Prometheus metrics |
| Structured logging | Java (logback), Go (slog), Node.js (pino) | Python missing JSON logs |
| Correlation IDs | Java only (via Sleuth auto) | Go/Node.js/Python missing |
| Readiness checks | All 4 always return READY | No dependency health verification |
| Graceful shutdown | Java (built-in), Go (manual) | Python/Node.js missing |
| Scaffold scripts | Referenced by Makefile | All 4 scripts missing |

### 1.3 Key Architectural Constraints (from prior phases)

| Constraint | Source | Implication |
|-----------|--------|-------------|
| Polyglot (4 languages) | ADR-001 | No single shared library — patterns must be replicated per language |
| Contracts as shared layer | ADR-001 | OpenAPI + Avro are the cross-language truth |
| 19 services planned | Phase 01 | Scaffold must scale to 19 services |
| Tier-1 production target | Phase 06 | Logging, tracing, metrics non-negotiable |
| Event-driven (Kafka) | Phase 04 | Outbox/Inbox patterns needed in core |
| PostgreSQL per service | Phase 07 | Connection pooling per service |
| mTLS + OAuth2 planned | Phase 05 | Auth guard must be pluggable |

---

## 2. Scope Definition

### 2.1 IN SCOPE (Must deliver — 7 work packages)

#### WP1️⃣ Focused Core Packages (one concern per package)

Instead of one monolithic `libs/core/{lang}`, each concern gets its own package with a clear boundary:

```
libs/
├── telemetry/
│   ├── java/     — TracerProvider setup, OTLP exporter, span attributes, log MDC injection
│   ├── go/       — OTel SDK init, HTTP middleware, slog bridge
│   ├── python/   — FastAPI auto-instrumentation, OTLP exporter, log injector
│   └── nodejs/   — SDK node init, Fastify plugin, pino context injection
├── health/
│   ├── java/     — LivenessController, ReadinessController, StartupController
│   ├── go/       — Handler implementations with dependency check registry
│   ├── python/   — FastAPI routers with pluggable health indicators
│   └── nodejs/   — Fastify plugin with probe handlers
├── config/
│   ├── java/     — Type-safe config with Spring Boot conventions
│   ├── go/       — Typed env-var loader with validation (Playbook/cleanenv style)
│   ├── python/   — Pydantic-settings based with explicit schema
│   └── nodejs/   — Zod-validated env config with TypeScript types
├── metrics/
│   ├── java/     — Micrometer bindings (already standard)
│   ├── go/       — promhttp handler, RED metric collectors
│   ├── python/   — prometheus_fastapi_instrumentator
│   └── nodejs/   — prom-client with Fastify plugin
├── logging/
│   ├── java/     — Logback JSON encoder with traceId/spanId/requestId MDC
│   ├── go/       — slog JSON handler with context propagation
│   ├── python/   — structlog JSON output with context variables
│   └── nodejs/   — Pino JSON with child logger injection
├── errors/
│   ├── java/     — @ControllerAdvice RFC 7807 error response
│   ├── go/       — Error handler middleware with typed error codes
│   ├── python/   — FastAPI exception handler with Problem JSON
│   └── nodejs/   — Fastify error handler with RFC 7807 format
└── lifecycle/
    ├── java/     — Graceful shutdown hooks (Spring Boot native + custom)
    ├── go/       — Signal handler, drain connections, flush spans
    ├── python/   — SIGTERM/SIGINT capture, drain in-flight requests
    └── nodejs/   — Process signal handler, close server, flush telemetry
```

**Design rules for each package:**
- Each package does ONE thing and does it well
- Zero cross-package runtime dependencies (only language stdlib + the concern's tool)
- Package boundaries enforced by directory layout and import rules
- Each package independently testable
- `health/` may import `config/` (to know which deps to check), but never `telemetry/`

#### WP2️⃣ Standardized Service Identifiers (traceId, spanId, requestId)

Three distinct identifiers with clear semantics:

| Identifier | Source | Scope | Propagation | Format |
|-----------|--------|-------|-------------|--------|
| `traceId` | OTel SDK (auto-generated) | Distributed trace across all services | W3C `traceparent` header | 32 hex chars |
| `spanId` | OTel SDK (auto-generated) | Single operation within a service | W3C `traceparent` header (parent) | 16 hex chars |
| `requestId` | Gateway / first service (UUID v4) | User request from entry to response | Custom `X-Request-Id` header | UUID v4 |

**Propagation contract:**
```
Incoming request:
  - Extract W3C traceparent → traceId, spanId
  - Extract X-Request-Id → requestId (or generate if missing)
  - All 3 injected into logging context (MDC / slog attrs / structlog contextvars / pino child)
  - requestId returned in response header X-Request-Id

Outgoing HTTP call:
  - Inject W3C traceparent (auto via OTel)
  - Inject X-Request-Id header with current requestId

Kafka message:
  - traceId, spanId, requestId embedded in message headers
  - Consumer extracts all 3 and restores context
```

**What each service sees in logs:**
```json
{
  "timestamp": "2026-06-03T12:00:00.000Z",
  "level": "INFO",
  "service": "settlement-service",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Processing settlement batch"
}
```

#### WP3️⃣ Kubernetes Probe Endpoints (3 endpoints per service)

Replace current `/health` + `/ready` with Kubernetes-compliant probes:

| Endpoint | Purpose | Behavior | Status Codes |
|----------|---------|----------|--------------|
| **`/liveness`** | Is the process alive? | Lightweight. Checks goroutine/event-loop not blocked. Never checks dependencies. | 200 = alive, 500 = dead |
| **`/readiness`** | Can this pod serve traffic? | Checks all dependencies (DB, Kafka, Redis, etc.). Returns 503 if any dependency unhealthy. | 200 = ready, 503 = not ready |
| **`/startup`** | Has initialization completed? | Checks expensive startup tasks (DB migration, cache warm, schema registry connection). | 200 = started, 503 = still initializing |

**Response format (all languages, all probes):**
```json
{
  "status": "ok",
  "service": "settlement-service",
  "version": "0.1.0",
  "timestamp": "2026-06-03T12:00:00.000Z",
  "uptime": 12345.678,
  "checks": {
    "database": {"status": "ok", "latencyMs": 2.3},
    "kafka":    {"status": "ok", "latencyMs": 1.1}
  }
}
```

**Probe behavior rules:**
- `/liveness`: returns immediately (no I/O). Only fails if process is completely broken (deadlock, OOM, GC thrashing)
- `/readiness`: may perform I/O (DB ping, Kafka metadata). Returns 503 + which dependency failed
- `/startup`: only meaningful during initial boot. After startup complete, returns same as `/liveness`
- All probes return JSON, never redirect, never cache

**docker-compose healthcheck alignment:**
```
financial-core:    http://localhost:8080/liveness
settlement-service: http://localhost:8088/liveness
fraud-service:     http://localhost:8000/liveness
notification-svc:  http://localhost:3001/liveness
```

#### WP4️⃣ Config Strategy — Standardized Across 4 Languages

**Single contract, per-language implementation:**

```
CONTRACT (language-agnostic):
  1. All config from env vars (12-factor principle)
  2. Typed — strings, ints, bools, durations, URLs validated at startup
  3. Profile-aware — local / staging / production profiles
  4. Fails fast — missing required config = crash on startup (not later)
  5. Implicit defaults only for non-critical values
  6. Structured — nested config objects, not flat KEY=VALUE soup
```

**Required config schema (every service must implement):**

```yaml
server:
  port: int (required, default 8080)
  host: string (required, default "0.0.0.0")

database:
  url: string (required)
  maxPoolSize: int (default 10)
  minIdle: int (default 2)

kafka:
  bootstrapServers: string (required)
  consumerGroup: string (required)

redis:
  url: string (optional)

otel:
  exporterEndpoint: string (required)
  serviceName: string (required)
  serviceVersion: string (default "0.1.0")

logging:
  level: enum(debug|info|warn|error) (default "info")
  format: enum(json|text) (default "json")
```

**Per-language implementation:**

| Language | Tool | Key Pattern |
|----------|------|-------------|
| Java | Spring Boot `@ConfigurationProperties` + `application.yml` placeholders | `@Validated` with Bean Validation annotations |
| Go | `envconfig` or lightweight wrapper over `os.Getenv` | Struct tags: `envconfig:"DATABASE_URL" required:"true"` |
| Python | Pydantic `BaseSettings` (Pydantic v2) | `model_config = SettingsConfigDict(env_prefix="")` |
| Node.js | Zod schema over `process.env` | `z.object({...}).parse(process.env)` with clear error messages |

**Validation strategy:**
```
Startup sequence:
  1. Load env vars into typed config object
  2. Validate all required fields present
  3. Validate formats (URL, duration, enum)
  4. If validation fails → log error + exit(1) BEFORE starting HTTP server
  5. If validation passes → config is immutable for process lifetime
```

**docker-compose standardization — all services use identical env var names:**
```yaml
environment:
  SERVER_PORT: "8080"
  DATABASE_URL: postgresql://payment:payment@postgres:5432/financial_core_db
  KAFKA_BOOTSTRAP_SERVERS: kafka:9092
  REDIS_URL: redis://redis:6379/0                      # (where applicable)
  OTEL_EXPORTER_OTLP_ENDPOINT: http://jaeger:4317
  OTEL_SERVICE_NAME: financial-core
  LOG_LEVEL: info
  LOG_FORMAT: json
```

#### WP5️⃣ Architecture Fitness Tests

Tests that verify architectural invariants. These run in CI and prevent architectural erosion:

```
libs/archtest/
├── java/
│   └── ArchUnit rules: no cycle in packages, layer boundaries enforced
├── go/
│   └── go-cleanarch / custom rules: import graph validation
├── python/
│   └── import-linter: forbidden import rules
└── nodejs/
    └── dependency-cruiser: .dependency-cruiser.js rules
```

**Fitness test categories (per service):**

| Test | What it verifies | Language |
|------|-----------------|----------|
| **Package dependency rules** | `controller → service → repository` layering, no reverse imports | All 4 |
| **No shared database** | Each service's DB connection only to its own database | All 4 (config-level check) |
| **Core library boundaries** | Service only imports from allowed `libs/` packages | All 4 |
| **No framework leakage** | Domain code does not import framework-specific classes | Java, Python, Node.js |
| **Config schema completeness** | All required env vars present in docker-compose | Bash script |
| **Port uniqueness** | No two services on same port | Bash script |
| **Endpoint contract compliance** | All services expose `/liveness`, `/readiness`, `/startup` with correct contract | Integration test |
| **Imports direction** | Dependencies flow inward (services → libs, not libs → services) | All 4 |

**Fitness test execution:**
- Run as part of CI pipeline (separate job)
- Fail the build if any architecture rule is violated
- Rules defined ONCE per language, applied to all services of that language
- New services automatically subject to all rules

#### WP6️⃣ Service Scaffold Scripts

- `scripts/scaffold-java.sh` — Generate new Java Spring Boot service
- `scripts/scaffold-python.sh` — Generate new Python FastAPI service
- `scripts/scaffold-nodejs.sh` — Generate new Node.js Fastify service
- `scripts/scaffold-go.sh` — Generate new Go Chi service
- Each generates: full project structure, build files, Dockerfile, probe endpoints, OTel, metrics, tests, architecture fitness tests

**Scaffold must produce services that:**
- Import from focused `libs/*/{lang}` packages (not monolithic core)
- Expose `/liveness`, `/readiness`, `/startup` endpoints
- Use standardized config with validation
- Inject `traceId`, `spanId`, `requestId` into logs
- Pass architecture fitness tests immediately after generation
- Start and respond in < 30 seconds after generation

#### WP7️⃣ Existing Skeleton Remediation + Dev Environment

- Fix all 4 existing services to conform to ALL new patterns above
- Enable working OTel tracing (all 4)
- Add Prometheus `/metrics` endpoints (all 4)
- Add `/liveness`, `/readiness`, `/startup` probes (all 4)
- Standardize config loading with validation (all 4)
- Add `traceId` + `spanId` + `requestId` to structured logs (all 4)
- Add graceful shutdown (Python, Node.js)
- Fix docker-compose env var naming, port issues
- Fix Go OTel URL bug (`jaeger:4317` → `http://jaeger:4317`)

### 2.2 OUT OF SCOPE (Deferred to Phase 7)

- Business logic implementation (payment, fraud, settlement, notification)
- Database migrations with actual schemas
- Kafka consumer/producer implementation
- Authentication and authorization (Spring Security, JWT, OAuth2)
- Circuit breakers and resilience patterns
- Rate limiting
- Idempotency key handling
- Outbox/Inbox pattern implementation
- OpenAPI spec generation from code
- Contract testing (Pact)
- E2E tests

### 2.3 OUT OF SCOPE (Deferred to Later Phases)

- Phase 6: CI/CD pipeline refinement
- Phase 7: Service business logic
- Phase 8: Production hardening, load testing, chaos engineering

---

## 3. Design Decisions

### 3.1 Focused Packages vs Monolithic Core

| Aspect | Monolithic `libs/core/` ❌ | Focused packages ✅ |
|--------|--------------------------|---------------------|
| Coupling | High — health pulls in telemetry, config pulls in logging | Low — each package is standalone |
| Testability | Must mock entire core to test one concern | Test each concern in isolation |
| Versioning | All concerns version together | Each concern evolves independently |
| Import clarity | `import core.*` — what actually gets used? | `import telemetry.TracerProvider` — explicit |
| New service needs | Pulls in everything even if using only health | Only import what you need |
| Boundary enforcement | Hard — easy to create circular deps within core | Easy — directory layout IS the boundary |

### 3.2 Standardized Contracts

#### Probe Response (all languages, all 3 probes)
```json
{
  "status": "ok",
  "service": "service-name",
  "version": "0.1.0",
  "timestamp": "2026-06-03T12:00:00.000Z",
  "uptime": 12345.678,
  "checks": {
    "database": {"status": "ok", "latencyMs": 2.3},
    "kafka": {"status": "ok", "latencyMs": 1.1},
    "redis": {"status": "ok", "latencyMs": 0.5}
  }
}
```

#### Error Response (RFC 7807, all languages)
```json
{
  "type": "https://api.payment.com/errors/validation-error",
  "title": "Validation Error",
  "status": 400,
  "detail": "Field 'amount' must be positive",
  "instance": "/v1/payments",
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### Standard Env Var Names
| Variable | Purpose |
|----------|---------|
| `DATABASE_URL` | PostgreSQL connection string |
| `REDIS_URL` | Redis connection string (optional) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker list |
| `SERVER_PORT` | HTTP listen port |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTel OTLP collector endpoint |
| `OTEL_SERVICE_NAME` | Service name for traces |
| `LOG_LEVEL` | Logging level (debug/info/warn/error) |
| `LOG_FORMAT` | Logging format (json/text) |
| `SERVICE_VERSION` | Service version tag |

#### Standard OTel Attributes (all languages)
```
service.name = service-name
service.version = 0.1.0
service.namespace = payment-api
deployment.environment = local|staging|production
```

#### Standard Metrics (all languages, RED method)
```
http_requests_total{method, path, status}
http_request_duration_seconds{method, path, quantile}
http_requests_in_flight
```

### 3.3 Scaffold Template Structure (updated)

#### Java Template
```
services/java/{name}/
├── pom.xml                              # Depends on libs/{telemetry,health,config,metrics,logging,errors}/java
├── src/main/java/com/paymentapi/{name}/
│   ├── {Name}Application.java           # Main class, imports libs
│   ├── config/{Name}Config.java         # @ConfigurationProperties, validated
│   └── config/{Name}Properties.java     # Config record
├── src/main/resources/
│   ├── application.yml                  # With placeholder env vars
│   └── application-local.yml            # Local dev overrides
├── src/test/java/com/paymentapi/{name}/
│   ├── ProbesTest.java                  # Tests /liveness, /readiness, /startup
│   ├── ConfigValidationTest.java        # Tests config fails fast on missing env
│   └── ArchitectureFitnessTest.java     # ArchUnit rules
└── README.md
```

#### Go Template
```
services/go/{name}/
├── go.mod                               # replace directives to libs/*/go
├── cmd/server/main.go                   # Wire telemetry, health, config, graceful shutdown
├── internal/
│   ├── config/config.go                 # Typed struct with envconfig tags + Validate()
│   ├── handler/
│   │   ├── liveness.go                  # From libs/health/go
│   │   ├── readiness.go                 # Dependency checks
│   │   └── startup.go                   # Initialization gate
│   ├── telemetry/telemetry.go           # Wraps libs/telemetry/go
│   └── middleware/
│       ├── logging.go                   # requestId injection, slog attrs
│       └── tracing.go                   # OTel HTTP middleware
├── test/
│   ├── probes_test.go                   # HTTP test for all 3 probes
│   └── architecture_test.go             # Import graph validation
├── .golangci.yml
└── README.md
```

#### Python Template
```
services/python/{name}/
├── pyproject.toml                       # Dependencies on libs/*/python via path
├── requirements.txt
├── src/{name}/
│   ├── __init__.py
│   ├── main.py                          # FastAPI app, registers health routers, middleware
│   ├── config.py                        # Pydantic BaseSettings with validation
│   └── routers/
│       ├── liveness.py                  # From libs/health/python
│       ├── readiness.py
│       └── startup.py
├── tests/
│   ├── conftest.py
│   ├── test_probes.py                   # AsyncClient tests for 3 probes
│   └── test_architecture.py             # import-linter rules
└── README.md
```

#### Node.js Template
```
services/nodejs/{name}/
├── package.json                         # Dependencies on libs/*/nodejs via path
├── tsconfig.json
├── src/
│   ├── main.ts                          # Fastify server, registers plugins
│   ├── config.ts                        # Zod schema + parse
│   ├── routes/
│   │   ├── liveness.ts                  # Probes from libs/health
│   │   ├── readiness.ts
│   │   └── startup.ts
│   └── plugins/
│       ├── telemetry.ts                 # OTel SDK init from libs/telemetry
│       └── metrics.ts                   # Prometheus from libs/metrics
├── tests/
│   ├── probes.test.ts                   # Fastify inject tests
│   └── architecture.test.ts             # dependency-cruiser checks
├── .dependency-cruiser.js
└── README.md
```

---

## 4. Package Boundary Map

```
┌────────────────────────────────────────────────────────────┐
│  SERVICES (services/*/{lang}/*)                            │
│  Can import from any libs package.                         │
│  Cannot import from other services.                        │
│  Cannot import from libs of another language.              │
└───────────┬───────────┬───────────┬───────────┬───────────┘
            │           │           │           │
    ┌───────▼──┐ ┌──────▼───┐ ┌─────▼────┐ ┌───▼────────┐
    │logging/  │ │telemetry/│ │health/   │ │config/      │
    │ {lang}   │ │ {lang}   │ │ {lang}   │ │ {lang}      │
    └──────────┘ └──────────┘ └─────┬────┘ └─────────────┘
                                    │
                            (health may import config
                             for dependency knowledge)
            ┌──────────┐ ┌──────────┐ ┌───────────┐
            │metrics/  │ │errors/   │ │lifecycle/ │
            │ {lang}   │ │ {lang}   │ │ {lang}    │
            └──────────┘ └──────────┘ └───────────┘
```

**Import rules (enforced by architecture fitness tests):**
- Each `libs/*/{lang}` package is self-contained — no imports from other libs packages (except health → config)
- Services import from `libs/*/{lang}` packages — never from other services
- No cross-language imports (Java cannot import Go)
- No circular dependencies between packages

---

## 5. Risk Register (Phase 5 Specific — Updated)

| # | Risk | Probability | Impact | Mitigation |
|---|------|-------------|--------|------------|
| R1 | Polyglot patterns diverge over time | Medium (3) | High (4) | Strong contracts, scaffold enforces patterns, arch fitness tests catch divergence |
| R2 | OTel agent/SDK configuration differs per language | High (4) | Medium (3) | Test all 4 locally, standardize exporter config, same env var names |
| R3 | Go Docker image FROM scratch has no healthcheck | High (5) | Low (2) | Accept limitation, document Kubernetes probes as alternative |
| R4 | Scaffold scripts fall out of sync with services | Medium (3) | Medium (3) | Scaffold from template files, not code generation; arch fitness tests verify |
| R5 | Too many small packages → dependency management overhead | Medium (3) | Low (2) | Each libs package is < 200 lines; version via monorepo (no publishing) |
| R6 | Phase takes too long (7 packages × 4 languages = 28 impls) | High (4) | Medium (3) | Time-box to 5 days; prioritize telemetry + health + config; defer errors + metrics + lifecycle |
| R7 | docker-compose changes break existing CI | Low (2) | Medium (3) | Test CI after changes |
| R8 | Config strategy differs enough per language to break standardization | Medium (3) | High (4) | Strict contract first, implement per-language but verify contract with arch fitness tests |

---

## 6. Invariants (Things That Must NOT Change)

1. **No breaking changes to existing service API paths** — old `/health` and `/ready` endpoints may remain as deprecated aliases (301 → `/liveness`, `/readiness`) but MUST keep working through Phase 5
2. **No change to CI/CD pipeline workflow triggers** — CI must remain green
3. **No change to service ownership** — financial-core stays Java, fraud-service stays Python, etc.
4. **No new infrastructure dependencies** — docker-compose remains same services
5. **No language version changes** — Java 21, Python 3.12, Node.js 22, Go 1.22
6. **No framework changes** — Spring Boot 3.3, FastAPI, Fastify, Chi
7. **No cross-package imports within libs/** — except health → config (documented exception)

---

## 7. Success Criteria (Updated)

| # | Criterion | Measurement |
|---|-----------|-------------|
| C1 | All 4 services have working OTel traces visible in Jaeger with `traceId` | curl `/liveness` → trace with `traceId` appears in Jaeger UI at http://localhost:16686 |
| C2 | All 4 services expose `/metrics` endpoint in Prometheus format with RED metrics | `curl localhost:{port}/metrics` returns `http_requests_total`, `http_request_duration_seconds` |
| C3 | All 4 services have structured JSON logs with `traceId`, `spanId`, `requestId` | `docker-compose logs` shows JSON with all 3 identifiers |
| C4 | All 4 services have working `/liveness`, `/readiness`, `/startup` probes | curl each → 200; `/readiness` returns 503 when DB/Kafka is down |
| C5 | All 4 services validate config at startup and fail fast on missing required vars | Start without DATABASE_URL → service exits with clear error |
| C6 | Architecture fitness tests pass for all 4 services | CI job: `make arch-test` returns 0 |
| C7 | Scaffold scripts generate a working service in < 5 minutes per language | Run `make scaffold-{lang} NAME=test-svc`, start it, curl all 3 probes |
| C8 | `docker-compose up` starts all infra + services successfully | No error logs on startup, all `/liveness` endpoints return 200 |
| C9 | All existing tests still pass (backward compatible) | `make test` returns 0 |
| C10 | CI pipeline stays green after changes | GitHub Actions CI passes |
| C11 | Phase 5 document complete | `docs/05-platform-skeleton.md` written |

---

## 8. File Change Inventory (Updated)

### New Files (~40+ new files across 7 packages × 4 languages)

```
libs/
├── telemetry/
│   ├── java/pom.xml, src/main/java/.../TelemetryAutoConfiguration.java, README.md
│   ├── go/go.mod, pkg/telemetry/telemetry.go, tracing/middleware.go, README.md
│   ├── python/pyproject.toml, src/payment_telemetry/__init__.py, middleware.py, README.md
│   └── nodejs/package.json, src/index.ts, telemetry-plugin.ts, README.md
├── health/
│   ├── java/pom.xml, src/main/java/.../LivenessController.java, ReadinessController.java, StartupController.java
│   ├── go/go.mod, pkg/health/liveness.go, readiness.go, startup.go
│   ├── python/pyproject.toml, src/payment_health/routers/liveness.py, readiness.py, startup.py
│   └── nodejs/package.json, src/routes/liveness.ts, readiness.ts, startup.ts
├── config/
│   ├── java/pom.xml, src/main/java/.../ConfigProperties.java
│   ├── go/go.mod, pkg/config/config.go, validator.go
│   ├── python/pyproject.toml, src/payment_config/config.py
│   └── nodejs/package.json, src/config.ts, schema.ts
├── metrics/
│   ├── java/pom.xml (Micrometer auto-config — thin wrapper)
│   ├── go/go.mod, pkg/metrics/metrics.go
│   ├── python/pyproject.toml, src/payment_metrics/middleware.py
│   └── nodejs/package.json, src/metrics-plugin.ts
├── logging/
│   ├── java/pom.xml, src/main/resources/logback-spring.xml
│   ├── go/go.mod, pkg/logging/context.go
│   ├── python/pyproject.toml, src/payment_logging/setup.py
│   └── nodejs/package.json, src/logger.ts
├── errors/
│   ├── java/pom.xml (RFC 7807 — thin wrapper)
│   ├── go/go.mod, pkg/errors/handler.go, types.go
│   ├── python/pyproject.toml, src/payment_errors/handler.py
│   └── nodejs/package.json, src/error-handler.ts
└── lifecycle/
    ├── java/pom.xml (thin wrapper)
    ├── go/go.mod, pkg/lifecycle/shutdown.go
    ├── python/pyproject.toml, src/payment_lifecycle/hooks.py
    └── nodejs/package.json, src/shutdown.ts
```

### Arch Test Package (new)
```
libs/archtest/
├── java/archunit-rules.xml               # ArchUnit rule set
├── go/import-linter.yaml                 # Import rules
├── python/.importlinter                   # Import-linter config
├── nodejs/.dependency-cruiser.js          # Dependency cruiser config
└── scripts/
    ├── check-port-uniqueness.sh           # Port conflict detection
    └── check-config-completeness.sh       # Env var coverage in docker-compose
```

### Scaffold Scripts + Templates (new)
```
scripts/
├── scaffold-java.sh
├── scaffold-python.sh
├── scaffold-nodejs.sh
└── scaffold-go.sh

templates/service/
├── java/    (all template files from §3.3 Java Template)
├── go/      (all template files from §3.3 Go Template)
├── python/  (all template files from §3.3 Python Template)
└── nodejs/  (all template files from §3.3 Node.js Template)
```

### Modified Files

```
docker-compose.yml                        # Fix OTel URL, standardize env vars, update healthcheck paths
services/java/financial-core/pom.xml      # Update dependencies to focused libs
services/java/financial-core/src/.../*    # Update to use new libs, add probes, logging, config
services/go/settlement-service/go.mod     # Update dependencies
services/go/settlement-service/cmd/.../*  # Wire OTel, metrics, probe endpoints
services/python/fraud-service/pyproject.toml  # Update dependencies
services/python/fraud-service/src/.../*   # Wire OTel, metrics, structured logging, probes
services/nodejs/notification-service/package.json # Update dependencies
services/nodejs/notification-service/src/.../*    # Wire OTel, metrics, correlation IDs, probes
Makefile                                  # Add arch-test target, update scaffold targets
shared/config/prometheus.yml              # Update scrape paths from /health to /metrics, add new services
```

---

## 9. Build & Dependency Strategy (Monorepo, No Registry)

All `libs/*/*` packages are referenced locally:

| Language | Mechanism |
|----------|-----------|
| Java | Parent POM at `libs/` level with `<modules>` listing all packages. Services reference via `<dependency>` on Maven artifact within reactor |
| Go | `go.mod` with `replace` directive pointing to local path. Monorepo workspace at `libs/` level |
| Python | `pip install -e ../libs/telemetry/python` (editable install). `pyproject.toml` with local path dependency |
| Node.js | npm workspaces at root. Services depend on `"@payment-api/telemetry": "file:../../libs/telemetry/nodejs"` |

No packages are published to any registry during Phase 5. All development uses local paths.

---

## 10. Open Questions (Updated)

1. **Q**: Should the old `/health` and `/ready` endpoints be removed or kept as deprecated aliases?
   **A**: Keep as 301 redirects to `/liveness` and `/readiness` during Phase 5. Remove in Phase 7. Document in release notes.

2. **Q**: How many `libs/` packages to implement now vs. later?
   **A**: Phase 5 priority order: `telemetry/` → `health/` → `config/` → `logging/` → `metrics/` → `errors/` → `lifecycle/`. First 3 are must-have. Time-box: if 7 packages × 4 languages exceeds 5 days, defer `errors/` and `lifecycle/` to Phase 7.

3. **Q**: Should architecture fitness tests be in a separate CI job or part of each service's test suite?
   **A**: Separate CI job (`make arch-test`). Architecture rules are applied globally, not per service. A service cannot opt out of architectural constraints.

4. **Q**: What about the 15 unscaffolded services?
   **A**: Validate scaffold scripts by generating one test service per language, verify all probes + metrics work, then delete. Actual scaffolding of remaining services happens in Phase 7.

5. **Q**: Should `requestId` be generated at the gateway or at the first service?
   **A**: First service that receives the request. If `X-Request-Id` header is present, reuse it (allows clients to set their own). If absent, generate UUID v4. This works for both direct service calls and gateway-routed calls.

---

## 11. Phase 5 Document Outline (Updated)

```
docs/05-platform-skeleton.md
├── Goal & Input
├── Core Library Architecture
│   ├── Why focused packages (not monolithic core)
│   ├── Package boundary map with import rules
│   ├── Per-package design: telemetry, health, config, logging, metrics, errors, lifecycle
│   └── Build & dependency strategy (monorepo, local references)
├── Standardized Contracts
│   ├── Probe response format (liveness/readiness/startup)
│   ├── Error response format (RFC 7807)
│   ├── Log context fields (traceId, spanId, requestId)
│   ├── OTel attributes (service.name, service.version, service.namespace, deployment.environment)
│   ├── Metrics naming convention (RED method)
│   ├── Env var naming convention
│   └── Config validation contract
├── Config Strategy (Standardized)
│   ├── 12-factor configuration principles
│   ├── Per-language implementation (Spring Boot / envconfig / Pydantic / Zod)
│   ├── Fail-fast validation pattern
│   └── Docker-compose env var mapping
├── Service Scaffold
│   ├── Template structure per language
│   ├── Scaffold script documentation
│   └── "Generate and run in 5 minutes" guide
├── Testing Architecture
│   ├── Test pyramid (unit / integration / contract / E2E)
│   ├── Per-language testing tools and conventions
│   ├── Architecture fitness tests (packages, rules, execution)
│   └── Coverage targets
├── Dev Environment
│   ├── docker-compose overview (updated)
│   ├── Hot-reload setup per language
│   └── Seed data strategy
├── Developer Setup Guide
│   ├── Prerequisites
│   ├── Clone → Run in 5 minutes
│   └── Troubleshooting
├── Done Criteria checklist
└── Connection to Phase 6 (CI/CD Pipeline)
```

---

Phase 1 (SCRATCHPAD) updated with 5 mandatory changes. Please review the updated scratchpad.

**Summary of changes:**
1. ✅ `libs/core/*` → 7 focused packages (`telemetry/`, `health/`, `config/`, `metrics/`, `logging/`, `errors/`, `lifecycle/`) with import boundary rules enforced by architecture fitness tests
2. ✅ `traceId` + `spanId` + `requestId` — all three defined, propagated via W3C traceparent + X-Request-Id, all injected into log context
3. ✅ `/liveness`, `/readiness`, `/startup` endpoints with standardized response contract + behavior rules
4. ✅ Config Strategy: typed, validated, 12-factor, fail-fast — Spring Boot Config Props / Go envconfig / Python Pydantic / Node.js Zod
5. ✅ Architecture Fitness Tests: package dependency rules, import direction, port uniqueness, config completeness — `libs/archtest/` with per-language tooling

Reply **APPROVE** to continue to the PLAN phase, or provide additional feedback.
