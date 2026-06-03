# Phase 09 — Deploy, Stabilize & Evolve

## 🎯 Goal

Ship to production, stabilize for 1-2 weeks, establish operational practices, and set up the feedback loop for continuous improvement.

## 📥 Input

- Phase 7: Working vertical slice (payment → fraud → ledger → notification)
- Phase 8: Observability (SLI/SLO, alerts, dashboards, runbooks)
- Phase 6: CI/CD pipeline (build, test, validate, push)

---

## 1. Deployment Runbook

### Prerequisites
```bash
# Verify all tools
make check-tools

# Verify architecture
make arch-test

# Run E2E verification
bash scripts/verify-vertical-slice.sh
```

### Deploy to Production
```bash
# 1. Tag release
git tag -a v0.1.0 -m "Phase 9: Production deployment"
git push origin v0.1.0

# 2. CI/CD automatically:
#    - Builds all Docker images
#    - Validates runtime (curl /liveness on each)
#    - Pushes to GHCR
#    - Scans with Trivy
#    - Runs smoke tests against pushed images

# 3. Deploy (docker-compose on target machine)
ssh prod-server
cd /opt/payment-api-platform
git pull origin main
docker-compose pull          # Pull latest from GHCR
docker-compose up -d          # Rolling restart with new images

# 4. Verify deployment
bash scripts/verify-vertical-slice.sh
bash scripts/synthetic-monitor.sh
```

### Health Check Verification
```bash
# All liveness probes
curl -sf http://localhost:8080/liveness  # financial-core
curl -sf http://localhost:8081/liveness  # payment-service
curl -sf http://localhost:8000/liveness  # fraud-service
curl -sf http://localhost:3001/liveness  # notification-service

# All readiness probes
curl -sf http://localhost:8080/readiness  # should return 200 with checks
curl -sf http://localhost:8081/readiness

# Dashboard health
open http://localhost:16686   # Jaeger — verify traces flow
open http://localhost:3000    # Grafana — verify RED dashboard
open http://localhost:9090    # Prometheus — verify targets up
```

### Monitor 2 Hours Post-Deploy
- [ ] Error rate < 1% on all services
- [ ] Consumer lag = 0 on all groups
- [ ] DLQ empty
- [ ] Payment success rate > 99%
- [ ] p95 latency < SLO targets
- [ ] No critical alerts firing

### Rollback
```bash
# If deployment fails:
git checkout <previous-tag>
docker-compose up -d --force-recreate
bash scripts/verify-vertical-slice.sh
```

---

## 2. Stabilization Report

### Template (1-2 weeks post-deploy)

| Day | Incidents | P1/P2 Fixed | Error Budget Remaining | Notes |
|-----|-----------|-------------|----------------------|-------|
| 1 | 0 | — | 100% | |
| 2 | | | | |
| ... | | | | |
| 14 | | | | |

### Stabilization Rules
1. **No new features** during stabilization period
2. **P1 issues**: fix immediately, deploy same day
3. **P2 issues**: triage daily, fix within 48 hours
4. **Document every issue** in post-mortem template
5. **Establish error budget baseline** after 2 weeks

---

## 3. Incident Response Process

### Severity Levels

| Level | Definition | Response Time | Communication |
|-------|-----------|---------------|---------------|
| **P1** | Service down, payment flow broken, data loss | 15 min | All stakeholders |
| **P2** | Degraded performance, partial outage | 2 hours | Team only |
| **P3** | Minor bug, no user impact | Next business day | Ticket |

### Incident Lifecycle
```
Detect ──▶ Triage ──▶ Mitigate ──▶ Resolve ──▶ Post-Mortem
 (alert)   (assign)   (rollback/   (verify)    (learn)
                       hotfix)
```

### Communication Template (P1)
```
🔴 INCIDENT: [Service] is [symptom]
Impact: [users affected, payments blocked]
Start: [timestamp]
Owner: [name]
Status: Investigating

[Updates every 30 minutes]

✅ RESOLVED: [Service] restored at [timestamp]
Duration: [X minutes]
Root cause: [brief description]
Action items: [link to post-mortem]
```

---

## 4. Post-Mortem Template

```markdown
# Post-Mortem: [Incident Title]

## Timeline (UTC)
- HH:MM — Alert fired: [alert name]
- HH:MM — Engineer acknowledged
- HH:MM — Root cause identified
- HH:MM — Mitigation applied
- HH:MM — Service restored
- HH:MM — Verification complete

## Impact
- Duration: X minutes
- Payments affected: N
- Error budget consumed: Y%

## Root Cause
[What caused the incident — be specific]

## What Went Well
- [Thing we did right]
- [Thing we did right]

## What Went Wrong
- [Thing we should improve]
- [Thing we should improve]

## Action Items
| # | Action | Owner | Deadline |
|---|--------|-------|----------|
| 1 | [Preventive measure] | | |
| 2 | [Detective measure] | | |
| 3 | [Process improvement] | | |

## Lessons Learned
[One sentence summary of what we learned]
```

---

## 5. Tech Debt Backlog

| # | Item | Priority | Effort | Phase |
|---|------|----------|--------|-------|
| 1 | OutboxPoller async (remove `.get()`) | P1 | 2h | 9 |
| 2 | Fraud velocity tracker → Redis | P1 | 4h | 9 |
| 3 | Retry topics implementation | P2 | 4h | 9 |
| 4 | Consumer DLQ auto-replay | P2 | 3h | 9 |
| 5 | Contract testing (Pact) | P2 | 8h | Wave 2 |
| 6 | API rate limiting | P2 | 4h | Wave 1 |
| 7 | Circuit breakers on Kafka consumers | P2 | 4h | Wave 1 |
| 8 | Prometheus metrics for outbox backlog | P3 | 1h | 9 |
| 9 | Grafana dashboard import automation | P3 | 2h | 9 |
| 10 | Schema registry integration | P3 | 8h | Wave 3 |

---

## 6. v2 Roadmap (Post-Phase 9)

### Wave 1: Transaction Layer
- [ ] transaction-service (Node.js) — idempotency, history API
- [ ] Add to CI/CD pipeline

### Wave 2: Refund & Fees
- [ ] refund-service (Java) — refund flow, Outbox
- [ ] fee-engine (Node.js) — dynamic fee calculation

### Wave 3: Treasury & FX
- [ ] treasury-service (Java) — fund management, settlement triggers
- [ ] fx-service (Java) — currency conversion rates

### Wave 4: Reconciliation & Audit
- [ ] reconciliation-service (Go) — daily settlement reconciliation
- [ ] audit-service (Go) — immutable audit log

### Wave 5: Merchant & Compliance
- [ ] merchant-service (Go) — onboarding, KYC
- [ ] identity-service (Go) — authentication, authorization
- [ ] compliance-service (Go) — AML checks
- [ ] bank-integration (Go) — PSP adapters
- [ ] dispute-service (Go) — chargeback handling

---

## 7. SLO Review Cadence

| Frequency | Activity |
|-----------|----------|
| **Weekly** | Review alert history, check error budget remaining |
| **Monthly** | Full SLO review, adjust targets if needed, review incident post-mortems |
| **Quarterly** | Architecture review, tech debt prioritization, roadmap adjustment |

---

## 📤 Output (Artifacts)

```
docs/09-deploy-stabilize-evolve.md           # This document
docs/cross-cutting/operations/
├── deployment-runbook.md                    # (embedded above)
├── incident-response.md                     # Process + template
├── post-mortem-template.md                  # Blameless PM template
├── stabilization-report-template.md         # 2-week template
└── tech-debt-backlog.md                     # Prioritized backlog
```

---

## ✅ Done Criteria

- [x] Deployment runbook (with rollback)
- [x] Stabilization report template (2 weeks)
- [x] Incident response process (P1/P2/P3 + communication)
- [x] Post-mortem template (blameless)
- [x] Tech debt backlog (10 items, prioritized)
- [x] v2 roadmap (5 waves, 12 services)
- [x] SLO review cadence (weekly/monthly/quarterly)

---

## 🔄 Connection to Next Cycle

Phase 9 output feeds back to Phase 1 of the next iteration:
- **Operate → Learn → Improve → Build**
- v2 roadmap guides next feature cycle
- Tech debt backlog prevents accumulation
- SLO reviews drive reliability investment

---

**9-Phase Minimum Build System Workflow — COMPLETE ✅**
