# Production Readiness Checklist — Payment API Platform

## Phase 8: Observability & Hardening Gate

### 🟢 Observability

- [ ] **Structured Logging**: All 5 services output JSON logs with `traceId`, `spanId`, `requestId`
- [ ] **Metrics**: All 5 services expose `/metrics` (or `/actuator/prometheus` for Java)
- [ ] **Tracing**: All 5 services send spans to OTel collector → Jaeger. Correlation visible across services.
- [ ] **RED Dashboard**: Grafana dashboard shows Rate/Errors/Duration per service
- [ ] **Kafka Dashboard**: Consumer lag, message rate, DLQ depth visible
- [ ] **Business Dashboard**: Payment created/approved/rejected/notified counts visible
- [ ] **SLI/SLO Defined**: 12 SLIs with SLO targets documented in `observability-contract.md`
- [ ] **Alert Rules**: Prometheus alerts configured for: service down, DLQ, consumer lag, error rate, DB pool, latency
- [ ] **Synthetic Monitor**: `/v1/payments` synthetic probe runs every 5 minutes, verifies full flow

### 🟢 Reliability

- [ ] **Health Probes**: All services expose `/liveness`, `/readiness`, `/startup`
- [ ] **Readiness Checks**: Dependency-aware with cached registry (TTL 5s). Returns 503 when DB/Kafka down.
- [ ] **Graceful Shutdown**: All services handle SIGTERM, drain requests, flush spans
- [ ] **Idempotency**: Payment API returns cached response on duplicate `Idempotency-Key`
- [ ] **Consumer Idempotency**: All consumers use `INSERT ... ON CONFLICT DO NOTHING`
- [ ] **Double-Entry Verified**: Ledger balance invariant (sum = 0) verified
- [ ] **DLQ Strategy**: Poison messages go to `payment-events-dlq`. Runbook for replay exists.
- [ ] **Outbox Strategy**: Transactional outbox with SKIP LOCKED. Backlog monitoring in place.
- [ ] **Config Validation**: All services fail fast on missing required env vars

### 🟢 Security

- [ ] **Dependency Scan**: OWASP Dependency Check + Trivy in CI/CD pipeline
- [ ] **Secret Scan**: Gitleaks/trufflehog scan in CI (no secrets in code)
- [ ] **Non-Root User**: All Docker images run as non-root
- [ ] **No Exposed Secrets**: No hardcoded credentials in configuration files
- [ ] **TLS Ready**: OTel gRPC uses TLS in production (insecure in local dev)
- [ ] **Port Audit**: Only required ports exposed to host (16686 Jaeger, 5432 Postgres, 9093 Kafka, 3000 Grafana, 9090 Prometheus)

### 🟢 Data

- [ ] **Backup Verified**: `verify-backup-restore.sh` script tests full backup → restore cycle
- [ ] **Migration Versioned**: All DB schemas versioned via Flyway migrations
- [ ] **Migration Repeatable**: `docker-compose down -v && docker-compose up -d` recreates all schemas

### 🟢 Operations

- [ ] **Runbooks**: 4 runbooks for top failure scenarios
  - [ ] `payment-processing-stuck.md`
  - [ ] `dlq-growth.md`
  - [ ] `db-connection-exhaustion.md`
  - [ ] `outbox-backlog-growth.md`
- [ ] **Incident Response**: Defined process: detect → triage → mitigate → resolve → post-mortem
- [ ] **Rollback Procedure**: `docker-compose down && docker-compose up -d` restores all services

### 🟢 Development

- [ ] **Scaffold**: `make scaffold-{lang} NAME=...` generates working service in < 5 min
- [ ] **Arch Tests**: `make arch-test` verifies import boundaries and port uniqueness
- [ ] **E2E Verification**: `scripts/verify-vertical-slice.sh` validates full payment flow
- [ ] **Contract Tests**: Producer schema compatibility verified against consumer

---

## Sign-Off

| Role | Name | Date | Status |
|------|------|------|--------|
| Developer | | | ☐ |
| Reviewer | | | ☐ |

**Gate**: All items must be ✅ before Phase 9 (Deploy, Stabilize & Evolve).
