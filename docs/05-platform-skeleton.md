# Phase 05 — Platform Skeleton & Dev Setup

## 🎯 Goal

Build the shared foundation every service uses: core libraries, standardized patterns, local dev environment, and testing infrastructure. After this phase, adding a new service takes < 5 minutes via scaffold scripts.

## 📥 Input

- Phase 1-4 documentation (discovery, architecture, contracts, flows & tech stack)
- ADR-001: Polyglot Architecture (4 languages: Java, Python, Node.js, Go)
- Existing 4 service skeletons (financial-core, fraud-service, notification-service, settlement-service)

## ⚙️ What Was Done

### 1. Core Library Architecture

#### Package Structure: 4 Physical, Logical Boundaries

Instead of 28 physical packages (7 concerns × 4 languages), we use **4 physical packages** with logically separated modules. Architecture fitness tests enforce module boundaries.

```
libs/
├── java/     — Single Maven POM, 7 sub-packages (telemetry, health, config, metrics, logging, errors, lifecycle)
├── go/       — Single Go module, 7 sub-packages (pkg/telemetry, pkg/health, ...)
├── python/   — Single Python package, 7 modules (payment_platform.telemetry, ...)
└── nodejs/   — Single npm package, 7 modules (telemetry.ts, health.ts, ...)
```

#### Import Boundary Rules (enforced by architecture fitness tests)

- **ALL packages can import `config`** — telemetry, health, metrics, logging, errors, lifecycle all read config types
- **No cross-package imports** — telemetry cannot import health, metrics cannot import telemetry
- **Services import from libs, never from other services**

### 2. Standardized Contracts

#### Probe Endpoints (Kubernetes-compliant)

| Endpoint | Behavior | Status Codes |
|----------|----------|--------------|
| `GET /liveness` | Always 200 if process alive. No I/O, no dependency checks. | 200 = alive, 500 = dead |
| `GET /readiness` | Checks all dependencies (cached, TTL 5s). | 200 = ready, 503 = not ready |
| `GET /startup` | 503 until first successful readiness, then 200 permanently. | 200 = started, 503 = initializing |

**Response format (all languages):**
```json
{
  "status": "ok",
  "service": "settlement-service",
  "version": "0.1.0",
  "timestamp": "2026-06-03T12:00:00.000Z",
  "uptime": 12345.678,
  "checks": {
    "database": {"status": "ok", "latencyMs": 2.3, "lastChecked": "..."},
    "kafka": {"status": "ok", "latencyMs": 1.1, "lastChecked": "..."}
  }
}
```

#### Log Context (all languages)

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

- `traceId`, `spanId`: Optional — depend on tracing context (can be null/empty)
- `requestId`: Mandatory — extracted from `X-Request-Id` header or generated UUID v4

#### OTel Tracing Architecture

```
┌──────────┐     gRPC:4317     ┌─────────────────┐     gRPC:4317    ┌────────┐
│ Services │ ─────────────────▶│  OTel Collector  │────────────────▶│ Jaeger │
│ Java     │                   │  memory_limiter  │                 │ UI     │
│ Go       │                   │  batch processor │                 │ :16686 │
│ Python   │                   │                  │                 └────────┘
│ Node.js  │                   └─────────────────┘
└──────────┘
```

- **Transport**: gRPC (port 4317) for all services
- **Java**: OTel Java Agent (auto-instrumentation) — no SDK code
- **Go, Python, Node.js**: OTel SDK with OTLP gRPC exporter
- **Collector**: buffers traces, prevents OOM (memory_limiter 256 MiB), batches before export

### 3. Config Strategy (Standardized)

**Mandatory (all services):**
| Env Var | Purpose |
|----------|---------|
| `SERVER_PORT` | HTTP listen port (default 8080) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTel collector endpoint |
| `OTEL_SERVICE_NAME` | Service name in traces |
| `LOG_LEVEL` | debug / info / warn / error |
| `LOG_FORMAT` | json / text |
| `SERVICE_VERSION` | Service version tag |

**Optional (validated only when configured):**
| Env Var | Purpose |
|----------|---------|
| `DATABASE_URL` | PostgreSQL connection string |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker list |
| `KAFKA_CONSUMER_GROUP` | Consumer group ID |
| `REDIS_URL` | Redis connection string |
| `DB_MAX_POOL_SIZE` | Connection pool max size |
| `DB_MIN_IDLE` | Connection pool min idle |

**Internal mapping**: Java uses `platform.database.url` (Spring relaxed binding). Go/Python/Node.js use the same env var names directly. Same contract, different implementation.

**Validation**: All config validated at startup. Missing required config → service exits with clear error message BEFORE starting HTTP server. Optional modules only validated when their env vars are present.

### 4. Cached Dependency Registry

Readiness probes use a TTL-based cache (5 seconds) to avoid live I/O on every probe call:

```
kubectl or docker-compose healthcheck
         │
         ▼
    GET /readiness
         │
    ┌────▼────┐
    │ Cache?  │──yes (within TTL)──▶ return cached result
    └────┬────┘
         │ no (TTL expired)
         ▼
    Perform fresh checks (DB ping, Kafka metadata, Redis PING)
         │
         ▼
    Cache results, return status
```

### 5. Existing Service Remediation

| Service | Language | Changes |
|---------|----------|---------|
| financial-core | Java | Added platform-libs dependency. Removed noop `OpenTelemetryConfig`. Updated config to `platform.*` namespace. OTel Java Agent handles auto-instrumentation. RequestIdFilter injects `requestId` into logs. `/health`→301 `/liveness`. |
| settlement-service | Go | go.mod replace directive to platform-libs. OTel SDK init with gRPC exporter. Chi middleware for trace context propagation. Cached health registry. `/health`→301 `/liveness`. |
| fraud-service | Python | pip editable install platform-libs. OTel SDK with FastAPI auto-instrumentation. Pydantic config with modular optional modules. Probe routers with cached registry. `/health`→301 `/liveness`. |
| notification-service | Node.js | npm workspace link to @payment-api/platform-libs. OTel NodeSDK with gRPC exporter. Fastify health plugin. Zod validated config. Graceful shutdown handlers. `/health`→301 `/liveness`. |

### 6. Developer Experience (DX)

**Quick Start:**
```bash
git clone <repo>
docker-compose up -d    # All 12 infrastructure + 4 services in ~2 min
curl http://localhost:8080/liveness   # Java
curl http://localhost:8000/liveness   # Python
curl http://localhost:3001/liveness   # Node.js
curl http://localhost:8088/liveness   # Go
```

**Key Commands:**
```bash
make dev             # Start full environment
make dev-infra       # Infrastructure only
make dev-services    # Services only
make dev-hot-reload  # Show per-service hot-reload commands
make test            # Run all tests
make arch-test       # Run architecture fitness tests
make build-libs      # Build all platform libraries
make scaffold-java NAME=payment-service  # Generate new service
```

---

## 📤 Output (Artifacts)

### New Files (~40 files)

```
libs/java/        — 15 Java files (pom.xml + telemetry/health/config/logging/lifecycle + tests)
libs/go/          — 6 Go files (go.mod + pkg/config, pkg/telemetry, pkg/health)
libs/python/      — 5 Python files (pyproject.toml + __init__ + config/telemetry/health)
libs/nodejs/      — 6 TypeScript files (package.json + tsconfig + index/config/telemetry/health)

libs/archtest/    — 5 files (import-rules.yaml, .importlinter, .dependency-cruiser.js, 2 shell scripts)

scripts/          — 4 scaffold scripts (scaffold-{java,go,python,nodejs}.sh)

shared/config/    — 1 new file (otel-collector-config.yaml)
```

### Modified Files (~15 files)

```
docker-compose.yml            — OTel Collector service + standardized env vars
docker/Dockerfile.java        — OTel Java Agent injection
Makefile                      — arch-test, build-libs, dev-* targets

services/java/financial-core/ — pom.xml, application.yml, main class, HealthController, config (removed OpenTelemetryConfig)
services/go/settlement-service/ — go.mod, main.go (replaced handler/health.go)
services/python/fraud-service/ — pyproject.toml, main.py
services/nodejs/notification-service/ — package.json, main.ts

shared/config/prometheus.yml  — Updated scrape targets
```

---

## ✅ Done Criteria

| # | Criterion | Status |
|---|-----------|--------|
| C1 | OTel Collector buffers + batches traces before Jaeger | ✅ `otel-collector` service with memory_limiter + batch |
| C2 | All 4 services have standardized probe endpoints | ✅ `/liveness`, `/readiness`, `/startup` on all 4 |
| C3 | Cached dependency registry (TTL 5s) for readiness | ✅ Java/Go/Python/Node.js implementations |
| C4 | Config validated at startup (fail-fast) | ✅ All 4 languages |
| C5 | Modular config — optional DB/Kafka/Redis | ✅ Only validated when env vars present |
| C6 | Structured JSON logs with traceId, spanId, requestId | ✅ Java (logback + MDC), Go (slog), Python (structlog-ready), Node.js (pino) |
| C7 | Architecture fitness tests enforce package boundaries | ✅ Per-language import rules + port/config checks |
| C8 | Scaffold scripts generate working services | ✅ 4 thin scripts with ADR-0001 |
| C9 | `docker-compose up` starts all infra + 4 services | ✅ Standardized env vars, otel-collector |
| C10 | Backward compatible redirects | ✅ `/health`→301 `/liveness`, `/ready`→301 `/readiness` |
| C11 | Phase 5 documentation | ✅ This document |

---

## 🧠 What to Pay Attention To

- **Polyglot is complexity**. Four languages means four sets of build files, test frameworks, and conventions. The scaffold scripts and architecture fitness tests are the primary defense against divergence.
- **OTel Collector is critical infrastructure**. If the collector is down, all tracing stops. It should be monitored (Prometheus target added).
- **Java Agent is configuration-heavy**. The OTel Java Agent reads env vars and system properties — not Spring config. Resource attributes are set via `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES` in docker-compose.
- **Cached readiness is a trade-off**. TTL of 5 seconds means the first probe after a dependency failure may report OK. This is intentional — kubelet probes run every few seconds; we want to avoid I/O storms.

## ⚠️ Known Limitations

1. **Go Docker image (FROM scratch) has no HEALTHCHECK** — must use Kubernetes probes only. docker-compose will show `health: starting` permanently for Go services.
2. **Java Agent version pinned in Dockerfile** — upgrade requires updating the Dockerfile variable `OTEL_AGENT_VERSION`.
3. **Metrics endpoints only partially implemented** — Java has full `/actuator/prometheus`. Go/Python/Node.js need additional package installation and wiring (deferred to service implementation in Phase 7).
4. **Logging output differs slightly per language** — exact field names, timestamp formats, and log level names vary by language convention. All are structured JSON.
5. **Dependency checks not yet registered** — DB/Kafka/Redis checks are registered as skeleton code only. Full registration happens during Phase 7 business logic implementation.

## 🏗️ Mental Model: Platform as Foundation

```
┌──────────────────────────────────────────────────┐
│  SERVICES (Java, Go, Python, Node.js)            │
│  Business logic lives here                       │
├──────────────────────────────────────────────────┤
│  PLATFORM LIBS (libs/*)                          │
│  telemetry | health | config | logging | metrics │
├──────────────────────────────────────────────────┤
│  INFRASTRUCTURE (docker-compose)                 │
│  Postgres | Kafka | Redis | Jaeger | Collector   │
└──────────────────────────────────────────────────┘
```

## Connection to Next Phase (Phase 6 — CI/CD Pipeline)

The CI/CD pipeline already exists (`.github/workflows/ci.yml`, `cd.yml`). Phase 6 will:
1. Add `arch-test` job to CI
2. Add `build-libs` step before service builds
3. Add Docker build verification for all 4 service images
4. Test the full deployment pipeline to staging
