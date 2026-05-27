# PLAN — Multi-Language Build System

## 1. Architecture Overview

### 1.1 Guiding Principle
> Single build command. Docker-native. Language-agnostic contracts.

### 1.2 Target State

```
make build-all      # Builds all 4 languages
make test-all       # Runs all tests
make docker-build   # Builds all Docker images
make dev            # Starts full local environment
```

---

## 2. Directory Structure (Deliverable)

```
payment-api-platform/
│
├── Makefile                          # Top-level build orchestration
├── docker-compose.yml                # Full local dev environment
├── .github/
│   └── workflows/
│       ├── ci.yml                    # CI: lint, test, build per language
│       └── cd.yml                    # CD: Docker build, push, deploy
│
├── docker/                           # Language-specific Dockerfiles
│   ├── Dockerfile.java               # Spring Boot multi-stage
│   ├── Dockerfile.python             # FastAPI multi-stage
│   ├── Dockerfile.nodejs             # Node.js multi-stage
│   └── Dockerfile.go                 # Go multi-stage (scratch)
│
├── services/                         # All microservices by language
│   ├── java/                         # ─── Java Spring Boot ───
│   │   ├── parent-pom.xml            # Maven parent POM (shared deps, plugins)
│   │   ├── financial-core/           # Financial Core (Ledger + Wallet)
│   │   ├── payment-service/          # Payment Orchestrator
│   │   ├── refund-service/           # Refund & Reversal
│   │   ├── fx-service/               # FX & Multi-Currency
│   │   └── treasury-service/         # Treasury
│   │
│   ├── python/                       # ─── Python FastAPI ───
│   │   └── fraud-service/            # Risk & Fraud Detection
│   │
│   ├── nodejs/                       # ─── Node.js ───
│   │   ├── notification-service/     # Notification (push, email, SMS)
│   │   ├── transaction-service/      # Transaction Read Model (CQRS)
│   │   └── fee-engine/               # Fee Calculation & Pricing
│   │
│   └── go/                           # ─── Go ───
│       ├── settlement-service/       # EOD Settlement
│       ├── reconciliation-service/   # Reconciliation Engine
│       ├── compliance-service/       # Compliance / AML
│       ├── dispute-service/          # Dispute Management
│       ├── merchant-service/         # Merchant Onboarding
│       ├── identity-service/         # Identity & Auth
│       ├── bank-integration/         # Bank Integration (ACL)
│       └── audit-service/            # Audit Log
│
├── shared/                           # Language-agnostic contracts
│   ├── api/                          # OpenAPI 3.1 specs (shared)
│   │   ├── payments-api.yaml
│   │   ├── wallets-api.yaml
│   │   ├── refunds-api.yaml
│   │   └── ...                       # Copied from docs/cross-cutting/api/specs/
│   └── events/                       # Avro schemas (shared)
│       ├── payment-succeeded.avsc
│       └── ...                       # Copied from docs/cross-cutting/events/schemas/
│
├── scripts/                          # Utility scripts
│   ├── scaffold-java.sh
│   ├── scaffold-python.sh
│   ├── scaffold-nodejs.sh
│   └── scaffold-go.sh
│
└── docs/                             # Existing documentation (unchanged structure)
    ├── adr/
    │   └── ADR-001-language-polyglot.md   # NEW: Polyglot architecture decision
    ├── stages/
    │   └── B-domain-architecture/
    │       └── 11-technology-selection.md # UPDATED: §4.1 polyglot revision
    └── ...
```

### 2.1 Key Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Monorepo** with `services/{language}/` layout | Single CI pipeline, shared contracts, atomic changes across services |
| D2 | **Makefile** as top-level orchestrator | Universal availability, simple delegation, no new tool |
| D3 | **No shared code library** across languages | Contracts (OpenAPI, Avro) are the shared layer; each language generates own clients |
| D4 | **Docker-first local dev** | `docker-compose up` = full env. No need to install 4 toolchains locally |
| D5 | **Per-language Dockerfiles** in `docker/` | One docker build strategy per language, multi-stage, minimal images |

---

## 3. Build Orchestration — Makefile

### 3.1 Target Hierarchy

```makefile
# ─── Top-level targets ───
all: build test                   # Build + test everything
build: build-java build-python build-nodejs build-go
test: test-java test-python test-nodejs test-go
lint: lint-java lint-python lint-nodejs lint-go
clean: clean-java clean-python clean-nodejs clean-go

# ─── Docker targets ───
docker-build: docker-build-java docker-build-python docker-build-nodejs docker-build-go
docker-push: docker-push-java docker-push-python docker-push-nodejs docker-push-go

# ─── Local dev ───
dev:                                # Start docker-compose with all deps
dev-up:                             # Start + rebuild
dev-down:                           # Stop all
dev-logs:                           # Tail all service logs

# ─── Utility ───
scaffold-java:                      # Create new Java service from template
scaffold-python:
scaffold-nodejs:
scaffold-go:
```

### 3.2 Per-Language Delegation

| Language | Build Command | Test Command | Lint Command | Docker Tag |
|----------|--------------|-------------|-------------|------------|
| Java | `mvn clean package -f services/java/parent-pom.xml` | `mvn test` | `mvn checkstyle:check` | `payment-api/{svc}:latest` |
| Python | `cd services/python/fraud-service && pip install .` | `pytest` | `ruff check && mypy` | `payment-api/fraud-service:latest` |
| Node.js | `npm run build --workspaces` | `npm test --workspaces` | `eslint .` | `payment-api/{svc}:latest` |
| Go | `go build ./services/go/...` | `go test ./services/go/...` | `golangci-lint run ./services/go/...` | `payment-api/{svc}:latest` |

---

## 4. Docker Strategy Per Language

### 4.1 Java Dockerfile (`docker/Dockerfile.java`)

```dockerfile
# Stage 1: Build with Maven
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src/ src/
RUN mvn package -DskipTests -B

# Stage 2: Runtime with JRE
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 4.2 Python Dockerfile (`docker/Dockerfile.python`)

```dockerfile
# Stage 1: Builder
FROM python:3.12-slim AS builder
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# Stage 2: Runtime
FROM python:3.12-slim
RUN addgroup --system app && adduser --system --group app
USER app
COPY --from=builder /root/.local /home/app/.local
COPY . .
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1
ENV PATH="/home/app/.local/bin:$PATH"
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

### 4.3 Node.js Dockerfile (`docker/Dockerfile.nodejs`)

```dockerfile
# Stage 1: Dependencies
FROM node:22-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production

# Stage 2: Build
FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 3: Runtime
FROM node:22-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY --from=builder /app/dist ./dist
COPY package*.json ./
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:3000/health || exit 1
CMD ["node", "dist/main.js"]
```

### 4.4 Go Dockerfile (`docker/Dockerfile.go`)

```dockerfile
# Stage 1: Build
FROM golang:1.22-alpine AS builder
RUN apk add --no-cache git ca-certificates
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /app/server ./cmd/server

# Stage 2: Runtime (scratch - minimal)
FROM scratch
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /app/server /server
EXPOSE 8080
ENTRYPOINT ["/server"]
```

---

## 5. Docker Compose — Local Development

### 5.1 Service Matrix

```yaml
# docker-compose.yml
services:
  # ─── Infrastructure ───
  postgres:
    image: postgres:16-alpine
    ports: ["5432:5432"]
    environment: ...
    volumes: [pgdata:/var/lib/postgresql/data]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    depends_on: [zookeeper]
    ports: ["9092:9092"]

  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0

  schema-registry:
    image: confluentinc/cp-schema-registry:7.6.0

  opensearch:
    image: opensearchproject/opensearch:2.11
    ports: ["9200:9200"]

  jaeger:
    image: jaegertracing/all-in-one:1.54
    ports: ["16686:16686", "4317:4317"]

  # ─── Build & run services via their Dockerfiles ───
  financial-core:
    build:
      context: ./services/java/financial-core
      dockerfile: ../../../docker/Dockerfile.java
    ports: ["8081:8080"]

  payment-service:
    build:
      context: ./services/java/payment-service
      dockerfile: ../../../docker/Dockerfile.java
    ports: ["8082:8080"]

  fraud-service:
    build:
      context: ./services/python/fraud-service
      dockerfile: ../../../docker/Dockerfile.python
    ports: ["8001:8000"]

  notification-service:
    build:
      context: ./services/nodejs/notification-service
      dockerfile: ../../../docker/Dockerfile.nodejs
    ports: ["3001:3000"]

  settlement-service:
    build:
      context: ./services/go/settlement-service
      dockerfile: ../../../docker/Dockerfile.go
    ports: ["8083:8080"]
```

---

## 6. CI/CD Pipeline (GitHub Actions)

### 6.1 CI Pipeline (`ci.yml`)

```yaml
name: CI
on: [push, pull_request]

jobs:
  # ─── Java services ───
  java:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [financial-core, payment-service, refund-service, fx-service, treasury-service]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: mvn -f services/java/${{ matrix.service }}/pom.xml verify

  # ─── Python services ───
  python:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [fraud-service]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - run: pip install -r services/python/${{ matrix.service }}/requirements-dev.txt
      - run: cd services/python/${{ matrix.service }} && pytest && ruff check .

  # ─── Node.js services ───
  nodejs:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [notification-service, transaction-service, fee-engine]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - run: cd services/nodejs/${{ matrix.service }} && npm ci && npm test && npm run lint

  # ─── Go services ───
  go:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [settlement-service, reconciliation-service, compliance-service, dispute-service, merchant-service, identity-service, bank-integration, audit-service]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with: { go-version: '1.22' }
      - run: cd services/go/${{ matrix.service }} && go test ./... && golangci-lint run ./...

  # ─── Docker build (all services) ───
  docker:
    needs: [java, python, nodejs, go]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: make docker-build
```

### 6.2 CD Pipeline (`cd.yml`)

```yaml
name: CD
on:
  push:
    branches: [main]

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@v3
        with: { registry: ghcr.io, username: ${{ github.actor }}, password: ${{ secrets.GITHUB_TOKEN }} }
      - run: make docker-build docker-push
```

---

## 7. Service Scaffold Templates

### 7.1 Java Scaffold (`services/java/{service}/`)

```
{service}/
├── pom.xml                          # Dependencies: Spring Boot 3.3, Spring Web, Actuator, OTel
├── src/
│   ├── main/
│   │   ├── java/com/paymentapi/{service}/
│   │   │   ├── {Service}Application.java    # Spring Boot entry point
│   │   │   ├── config/
│   │   │   │   └── OpenTelemetryConfig.java # OTel instrumentation
│   │   │   ├── controller/
│   │   │   │   └── HealthController.java    # /health, /ready endpoints
│   │   │   └── domain/                      # DDD aggregates, entities, VOs
│   │   └── resources/
│   │       ├── application.yml              # Spring config
│   │       └── application-local.yml        # Local dev overrides
│   └── test/
│       └── java/com/paymentapi/{service}/
│           └── HealthControllerTest.java
└── README.md
```

### 7.2 Python Scaffold (`services/python/{service}/`)

```
{service}/
├── pyproject.toml                    # Project metadata + dependencies
├── requirements.txt                  # Pinned dependencies
├── requirements-dev.txt              # Dev: pytest, ruff, mypy
├── src/
│   └── {service_snake}/
│       ├── __init__.py
│       ├── main.py                   # FastAPI app entry point
│       ├── config.py                 # Pydantic settings
│       ├── api/
│       │   ├── __init__.py
│       │   └── health.py             # /health endpoint
│       └── domain/                   # DDD aggregates
│           └── __init__.py
├── tests/
│   ├── __init__.py
│   └── test_health.py
└── README.md
```

### 7.3 Node.js Scaffold (`services/nodejs/{service}/`)

```
{service}/
├── package.json                      # Dependencies: express/fastify, OTel
├── tsconfig.json                     # TypeScript config
├── src/
│   ├── main.ts                       # Entry point
│   ├── config.ts                     # Configuration
│   ├── routes/
│   │   └── health.ts                 # /health endpoint
│   └── domain/                       # DDD aggregates
├── tests/
│   └── health.test.ts
├── .eslintrc.js
└── README.md
```

### 7.4 Go Scaffold (`services/go/{service}/`)

```
{service}/
├── go.mod
├── go.sum
├── cmd/
│   └── server/
│       └── main.go                   # Entry point
├── internal/
│   ├── config/
│   │   └── config.go                 # Configuration
│   ├── handler/
│   │   └── health.go                 # /health endpoint
│   └── domain/                       # DDD aggregates
├── tests/
│   └── health_test.go
├── .golangci.yml
└── README.md
```

---

## 8. Documentation Updates

### 8.1 New Document: ADR-001 — Polyglot Architecture

**Location**: `docs/adr/ADR-001-language-polyglot.md`

**Content**:
- **Context**: Originally selected Go for all 19 services. Re-evaluating for learning + fit.
- **Decision**: Polyglot architecture with 4 languages mapped to domain tiers
- **Consequences**:
  - + Build complexity (mitigated by Makefile + Docker)
  - + Learning value across 4 stacks
  - + Best tool for each job (Spring Boot for financial core, Python for ML, etc.)
  - - Shared library overhead (mitigated by contract-first design)

### 8.2 Updated Document: Phase 11 §4.1 — Language & Runtime

**Location**: `docs/stages/B-domain-architecture/11-technology-selection.md`

**Change**: Section 4.1 — Replace Go-only with polyglot decision table:

| Context Tier | Language | Score | Primary Rationale |
|-------------|----------|-------|-------------------|
| Core (Financial, Payment, Refund, FX, Treasury) | Java 21 + Spring Boot 3.3 | 92 | ACID transactions, Spring ecosystem, JPA, type safety |
| Risk & Fraud | Python 3.12 + FastAPI | 90 | ML/AI ecosystem, rapid prototyping, data science |
| Event Consumers, BFF | Node.js 22 + Express/Fastify | 85 | Async I/O, event-driven, rich npm ecosystem |
| Batch Processing, ACL | Go 1.22 | 93 | Low resource, high concurrency, single binary |

### 8.3 New Document: Phase 13 — Platform Skeleton

**Location**: `docs/stages/C-platform-infrastructure/13-platform-core.md`

**Content**: Documents the multi-language build system, docker strategy, service scaffolds, and local dev setup (this document).

---

## 9. Implementation Order (7 Tasks)

| # | Task | Files Created/Modified | Priority |
|---|------|----------------------|----------|
| T1 | Create ADR-001 | `docs/adr/ADR-001-language-polyglot.md` | P0 |
| T2 | Update Phase 11 | `docs/stages/B-domain-architecture/11-technology-selection.md` (edit §4.1-4.2) | P0 |
| T3 | Create top-level Makefile | `Makefile` | P0 |
| T4 | Create 4 Dockerfiles | `docker/Dockerfile.{java,python,nodejs,go}` | P0 |
| T5 | Create docker-compose.yml | `docker-compose.yml` | P0 |
| T6 | Create CI/CD pipelines | `.github/workflows/ci.yml`, `.github/workflows/cd.yml` | P0 |
| T7 | Create service scaffolds (4) | `services/{java,python,nodejs,go}/` with templates | P1 |

---

## 10. Quality Gates

| Check | Target |
|-------|--------|
| `make build-all` exits 0 | All 4 language build targets succeed |
| `docker-compose up` starts all infra | PostgreSQL, Redis, Kafka, OpenSearch, Jaeger healthy |
| Each scaffold service has health endpoint | `GET /health` returns 200 |
| CI pipeline completes < 10 min | GitHub Actions matrix builds parallel |
| No service shares another's database | DB name per service in docker-compose |

---

## 11. Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Developer needs 4 toolchains | Docker Compose abstracts; local dev uses containers for running services |
| Build times increase | Matrix parallel CI; per-language caching |
| OTel version conflicts across languages | Pin OTel SDK versions per language; validate with smoke test |
| Java cold start in Docker | JVM CDS (Class Data Sharing) or GraalVM native image (future) |
