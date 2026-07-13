# Payment API Platform

**Polyglot microservices payment platform** — equivalent combined backend of MoMo + Stripe + PayPal. Built as a production-grade system and Staff/Principal-level engineering curriculum.

```
Java 21  +  Python 3.12  +  Node.js 22  +  Go 1.22
     │            │             │            │
     └────────────┼─────────────┼────────────┘
                  │             │
           Apache Kafka 3.7    PostgreSQL 16
                  │
          OpenTelemetry → Jaeger
          Prometheus + Grafana
```

---

## 🏗️ Architecture — Vertical Slice Proven

```
POST /v1/payments (Idempotency-Key)
         │
    ┌────▼──────────┐      ┌─────────────────┐      ┌──────────────────┐      ┌───────────────────┐
    │ payment-service│─────▶│ fraud-service    │─────▶│ financial-core   │─────▶│ notification-svc  │
    │ Java · :8081   │      │ Python · :8000   │      │ Java · :8080     │      │ Node.js · :3001   │
    │ Outbox Pattern  │      │ Multi-rule Score │      │ Double-Entry     │      │ Email Receipt     │
    └────────────────┘      └─────────────────┘      │ Ledger (sum=0)    │      └───────────────────┘
                                                     └──────────────────┘
```

**5 services across 4 languages**, communicating via Kafka with transactional outbox, idempotency, dead letter queue, and distributed tracing.

---

## 🚀 Quick Start

```bash
# 1. Clone
git clone https://github.com/<org>/payment-api-platform
cd payment-api-platform

# 2. Start everything (12 infra + 5 services)
docker-compose up -d

# 3. Register Avro schemas + Debezium connectors (Phase-9 CDC pipeline)
bash scripts/register-schemas.sh
bash scripts/register-connectors.sh

# 4. Verify
curl http://localhost:8081/liveness  # payment-service (Java)
curl http://localhost:8000/liveness  # fraud-service (Python)
curl http://localhost:3001/liveness  # notification-service (Node.js)
curl http://localhost:8080/liveness  # financial-core (Java)
curl http://localhost:8088/liveness  # settlement-service (Go)

# 5. Create a payment (amount in minor units — 9999 = $99.99)
curl -X POST http://localhost:8081/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount":9999,"currency":"USD","merchantId":"m1","customerId":"c1"}'

# 6. Run E2E verification
bash scripts/verify-vertical-slice.sh
```

### Dashboard URLs

| Dashboard | URL | Credentials |
|-----------|-----|-------------|
| **Jaeger** (Tracing) | http://localhost:16686 | — |
| **Grafana** (Metrics) | http://localhost:3000 | admin / admin |
| **Prometheus** (Alerts) | http://localhost:9090 | — |
| **OpenSearch** (Logs) | http://localhost:9200 | — |

---

## 📋 Services

| Service | Language | Port | Database | Responsibility |
|---------|----------|------|----------|----------------|
| **payment-service** | Java 21 + Spring Boot 3.3 | 8081 | payment_db | Payment API, Outbox, Idempotency |
| **fraud-service** | Python 3.12 + FastAPI | 8000 | fraud_db | Multi-rule fraud scoring |
| **financial-core** | Java 21 + Spring Boot 3.3 | 8080 | financial_core_db | Double-entry ledger, wallets |
| **notification-service** | Node.js 22 + Fastify | 3001 | notification_db | Email/push/SMS delivery |
| **settlement-service** | Go 1.22 + Chi | 8088 | settlement_db | Skeleton (EOD settlement planned) |

---

## 🧩 Key Patterns

| Pattern | Implementation |
|---------|---------------|
| **Transactional Outbox (CDC)** | Producers write a CloudEvents envelope to an `outbox` table in the same TX as state; **Debezium** tails the WAL and publishes to Kafka |
| **Avro + Schema Registry** | Events are Avro-encoded (Confluent Schema Registry); `docs/cross-cutting/events/schemas/*.avsc` are the design reference |
| **CloudEvents envelope** | `{ id, type, time, data{…}, trigger{…} }` on every event |
| **Idempotency (API)** | `UNIQUE(idempotency_key)` → concurrent duplicate returns cached payment (no 500) |
| **Inbox Pattern** | Consumers dedup + retry via `consumer_inbox` (status/retry_count); commit offset on claim, reprocess FAILED via a retry scheduler |
| **Retry + Backoff → DLQ** | Failed events retried ≤5× with exponential backoff, then routed to `<domain>.dlq` |
| **Serial chain (preserved)** | `payment → fraud → ledger → notification` (ledger depends on the fraud decision) |
| **Double-Entry Ledger** | 3 journal entries per payment, `SUM(CREDIT) - SUM(DEBIT) = 0` |
| **Minor-unit amounts** | All amounts are `long` cents (no floating point) |
| **Kafka + Registry auth** | SASL_PLAINTEXT/PLAIN + Schema Registry HTTP BASIC (dev-only creds) |
| **eventId ≠ paymentId** | `id` (CloudEvents) dedups; `paymentId` (partition key) orders |

---

## 🛠️ Development Commands

```bash
make dev              # Start full environment (docker-compose up)
make dev-infra        # Infrastructure only (DB, Kafka, Redis, Jaeger, etc.)
make dev-services     # Application services only
make dev-hot-reload   # Show per-service hot-reload commands
make test             # Run all tests (Java, Python, Node.js, Go)
make lint             # Lint all services
make arch-test        # Architecture fitness tests (import boundaries, ports)
make build-libs       # Build all 4 platform libraries
make scaffold-java NAME=my-service   # Generate new Java service
make scaffold-go NAME=my-service     # Generate new Go service
make scaffold-python NAME=my-service # Generate new Python service
make scaffold-nodejs NAME=my-service # Generate new Node.js service
make check-tools      # Verify toolchain (Java, Python, Node.js, Go, Docker)
make clean            # Clean all build artifacts
```

---

## 📦 Project Structure

```
payment-api-platform/
├── services/                   # 5 microservices (4 languages)
│   ├── java/
│   │   ├── payment-service/    # Payment API + Outbox
│   │   └── financial-core/     # Double-entry ledger
│   ├── python/
│   │   └── fraud-service/      # Fraud scoring
│   ├── nodejs/
│   │   └── notification-service/  # Email delivery
│   └── go/
│       └── settlement-service/    # EOD settlement
│
├── libs/                       # Shared platform libraries
│   ├── java/                   # Spring Boot auto-config
│   ├── go/                     # Chi middleware
│   ├── python/                 # FastAPI routers
│   ├── nodejs/                 # Fastify plugins
│   └── archtest/               # Architecture fitness tests
│
├── docker/                     # Multi-stage Dockerfiles
│   ├── Dockerfile.java         # eclipse-temurin:21 + OTel Agent
│   ├── Dockerfile.go           # static binary / scratch
│   ├── Dockerfile.python       # python:3.12-slim
│   └── Dockerfile.nodejs       # node:22-alpine
│
├── shared/config/              # Infrastructure configuration
│   ├── prometheus.yml          # Metrics scraping
│   ├── alert-rules.yml         # Prometheus alert rules
│   ├── otel-collector-config.yaml  # OTel collector pipeline
│   ├── observability-contract.md   # SLI/SLO/Error Budget
│   └── grafana-dashboards.md       # Dashboard specifications
│
├── .github/workflows/          # CI/CD
│   ├── ci.yml                  # 8 jobs (lint, test, build, arch-test, smoke)
│   └── cd.yml                  # 3 jobs (validate, push, scan)
│
├── scripts/                    # Utility scripts
│   ├── scaffold-*.sh           # Service generators
│   ├── verify-vertical-slice.sh   # E2E flow validation
│   ├── verify-backup-restore.sh   # Backup restore test
│   └── synthetic-monitor.sh       # 5-min synthetic probe
│
├── docs/                       # Full SDLC documentation
│   ├── 05-platform-skeleton.md
│   ├── 06-cicd-pipeline.md
│   ├── 07-build-implementation.md
│   ├── 08-observability-hardening.md
│   ├── 09-deploy-stabilize-evolve.md
│   ├── stages/                 # Detailed phase documents
│   ├── adr/                    # Architecture Decision Records
│   └── cross-cutting/          # API specs, event schemas, runbooks
│
└── docker-compose.yml          # 12 infra + 5 services
```

---

## 📊 Infrastructure (docker-compose)

| Service | Image | Port |
|---------|-------|------|
| PostgreSQL 16 | `postgres:16-alpine` | 5432 |
| Redis 7 | `redis:7-alpine` | 6379 |
| Apache Kafka 3.7 | `confluentinc/cp-kafka:7.6.0` | 9092, 9093 |
| Schema Registry | `confluentinc/cp-schema-registry:7.6.0` | 8081 |
| OTel Collector | `otel/opentelemetry-collector-contrib:0.103.0` | 4317, 4318 |
| Jaeger | `jaegertracing/all-in-one:1.54` | 16686 |
| Prometheus | `prom/prometheus:v2.50.0` | 9090 |
| Grafana | `grafana/grafana:10.4.0` | 3000 |
| OpenSearch | `opensearchproject/opensearch:2.11.1` | 9200 |

---

## 🔒 Observability & SLOs

| Service | SLI | SLO |
|---------|-----|-----|
| payment-service | Availability | **99.9%** |
| payment-service | p95 Latency | **< 300ms** |
| payment-service | Success Rate | **> 99%** |
| fraud-service | p95 Scoring | **< 200ms** |
| financial-core | Balance Accuracy | **100%** (double-entry) |
| notification-service | Delivery Time | **< 60s** |
| Kafka | Consumer Lag | **< 1000** |
| Kafka | DLQ Messages | **0** |

**11 alert rules** (3 critical, 8 warning) configured in Prometheus.

---

## 🗺️ Build Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | Business & Domain Discovery | ✅ |
| 2 | Architecture & Domain Design | ✅ |
| 3 | Data, API & Contract Design | ✅ |
| 4 | System Flows & Tech Stack | ✅ |
| 5 | Platform Skeleton & Dev Setup | ✅ |
| 6 | CI/CD Pipeline | ✅ |
| 7 | Build: Vertical Slice | ✅ |
| 8 | Observability & Hardening | ✅ |
| 9 | Deploy, Stabilize & Evolve | ✅ |

**v2 Roadmap**: transaction-service, refund-service, fee-engine, treasury-service, fx-service, reconciliation, audit, merchant, identity, compliance, bank-integration, dispute.

---

## 📚 Documentation

- `docs/BUILD-COMPLETE.md` — Complete build summary
- `docs/cross-cutting/operations/runbooks/` — 4 incident runbooks
- `docs/cross-cutting/operations/production-readiness-checklist.md` — 30-item gate
- `docs/adr/ADR-001-polyglot-architecture.md` — Why 4 languages

---

## 🔧 Tech Stack

| Layer | Technology |
|-------|-----------|
| **Languages** | Java 21, Python 3.12, Node.js 22, Go 1.22 |
| **Frameworks** | Spring Boot 3.3, FastAPI, Fastify, Chi |
| **Database** | PostgreSQL 16 (per-service) |
| **Cache** | Redis 7 |
| **Messaging** | Apache Kafka 3.7 |
| **Tracing** | OpenTelemetry → Collector → Jaeger |
| **Metrics** | Prometheus + Grafana |
| **CI/CD** | GitHub Actions |
| **Container** | Docker + docker-compose |
| **Infra (designed)** | AWS (EKS, Aurora, MSK, ElastiCache) via Terraform |
| **Service Mesh (planned)** | Istio Ambient |

---

## 🤝 Contributing

1. Use `make scaffold-{lang} NAME=...` to generate new services
2. Run `make arch-test` before pushing
3. All services must expose `/liveness`, `/readiness`, `/startup`
4. All config validated at startup (fail-fast)
5. CI must pass before merge

---

## 📄 License

Proprietary — Internal Use Only
