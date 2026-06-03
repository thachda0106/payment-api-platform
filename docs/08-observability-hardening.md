# Phase 08 — Observability & Hardening

## 🎯 Goal

Transition from "it works" to "can we operate it?" Define reliability targets, build operational visibility, and harden the system for production readiness.

## 📥 Input

- Phase 7: Working vertical slice (payment → fraud → ledger → notification)
- Phase 5: Platform libs with structured logging, metrics, tracing
- Phase 6: CI/CD pipeline with arch tests and smoke tests

---

## Phase 8A: Define Reliability

### SLI → SLO → Error Budget

**12 Service Level Indicators** defined across 5 services + platform:

| Service | SLI | SLO |
|---------|-----|-----|
| payment-service | Availability | 99.9% (43.2 min/month downtime) |
| payment-service | p95 Latency | < 300ms |
| payment-service | Success Rate | > 99% |
| fraud-service | p95 Scoring Latency | < 200ms |
| fraud-service | Consumer Lag | < 100 |
| financial-core | p95 Posting Latency | < 500ms |
| financial-core | Balance Accuracy | 100% (double-entry) |
| notification-service | Delivery Time | < 60s |
| notification-service | Success Rate | > 95% |
| Kafka Platform | Consumer Lag | < 1000 per partition |
| Kafka Platform | DLQ Messages | 0 (must be empty) |
| Infrastructure | DB/Redis Availability | 99.99% |

### Error Budget Policy

| Burn Rate | Action |
|-----------|--------|
| < 50% | Normal operations |
| > 50% | Stop feature deploys |
| > 80% | Incident declared |
| > 100% | Freeze all changes |

### Alert Rules (9 rules)

| Severity | Rule | Threshold |
|----------|------|-----------|
| **Critical** | ServiceDown | `up == 0` for 1m |
| **Critical** | DLQNotEmpty | DLQ offset > 0 for 30s |
| **Critical** | PaymentSuccessRateCritical | Approval rate < 80% for 5m |
| **Critical** | LedgerBalanceBroken | Balance invariant ≠ 0 |
| Warning | ConsumerLagHigh | Lag > 500 for 5m |
| Warning | High5xxErrorRate | 5xx > 5% for 5m |
| Warning | DatabaseConnectionPoolExhausted | Pending > 5 for 2m |
| Warning | OutboxBacklogGrowing | Published < Created for 10m |
| Warning | PaymentLatencyHigh | p95 > 300ms for 5m |
| Warning | FraudScoringLatencyHigh | p95 > 200ms for 5m |
| Warning | DiskSpaceLow | < 10% available |

---

## Phase 8B: Dashboards & Runbooks

### 3 Grafana Dashboards

1. **RED Dashboard** — Rate, Errors, Duration per service. Variable `$service` selector.
2. **Kafka Dashboard** — Consumer lag, message rate, DLQ depth per partition.
3. **Business Dashboard** — Payments created/approved/rejected, notifications sent, ledger entries.

### Jaeger Tracing Correlation

Every log line now includes: `traceId`, `spanId`, `requestId`, `paymentId`, `eventId`.
Trace context propagated via Kafka headers across all 4 languages.

### 4 Runbooks

| Runbook | Failure Scenario |
|---------|-----------------|
| `payment-processing-stuck.md` | Consumer lag increasing, payments stuck in CREATED |
| `dlq-growth.md` | Poison messages accumulating in DLQ |
| `db-connection-exhaustion.md` | Connection pool exhausted (Spring + Kafka common) |
| `outbox-backlog-growth.md` | Transactional Outbox publishing slower than creation |

---

## Phase 8C: Hardening & Readiness

### Security

| Check | Tool | Where |
|-------|------|-------|
| Dependency vulnerability | OWASP Dependency Check | CI pipeline |
| Container vulnerability | Trivy (CRITICAL, HIGH) | CD scan job |
| Secret detection | Gitleaks | CI pipeline (Phase 9) |
| Non-root containers | Dockerfile USER | All 4 Dockerfiles |
| Port audit | `libs/archtest/scripts/check-port-uniqueness.sh` | CI arch-test |

### Backup Restore (Verified)

`scripts/verify-backup-restore.sh`:
1. `pg_dump` backup
2. Restore into fresh database
3. Count restored tables + sample query
4. Cleanup

### Synthetic Monitor

`scripts/synthetic-monitor.sh` runs every 5 minutes via cron:
1. POST `/v1/payments` with synthetic merchant
2. Wait 15s for processing
3. Verify all 4 service liveness probes
4. Check consumer lag and DLQ depth

### Production Readiness Checklist (30 items)

✅ All 30 items across 5 categories: Observability, Reliability, Security, Data, Operations, Development.

See: `docs/cross-cutting/operations/production-readiness-checklist.md`

---

## 📤 Output (Artifacts)

### New Files
```
shared/config/
├── observability-contract.md         # SLI/SLO/Error Budget definitions
├── alert-rules.yml                   # 11 alert rules (critical + warning)
├── grafana-dashboards.md             # 3 dashboard designs (RED, Kafka, Business)

docs/cross-cutting/operations/
├── runbooks/
│   ├── payment-processing-stuck.md
│   ├── dlq-growth.md
│   ├── db-connection-exhaustion.md
│   └── outbox-backlog-growth.md
├── production-readiness-checklist.md  # 30-item checklist

scripts/
├── verify-backup-restore.sh           # Backup restore test
├── synthetic-monitor.sh               # 5-min synthetic payment probe
```

### Modified Files
```
docker-compose.yml                     # Alert rules volume mount
shared/config/prometheus.yml           # rule_files: alert-rules.yml
```

---

## ✅ Done Criteria

- [x] 12 SLIs with SLO targets defined
- [x] 11 alert rules (critical + warning)
- [x] 3 Grafana dashboard designs
- [x] 4 runbooks for top failures
- [x] Backup restore test script
- [x] Synthetic monitor every 5 minutes
- [x] Production readiness checklist
- [x] Phase 8 documentation

---

## Connection to Phase 9 (Deploy, Stabilize & Evolve)

Phase 9 will:
1. Execute production deployment runbook
2. Run smoke tests against deployed system
3. Monitor for 1-2 weeks (stabilization period)
4. Establish SLO baselines
5. Create v2 roadmap (refund, FX, treasury services)
