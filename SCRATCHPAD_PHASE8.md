# SCRATCHPAD: Phase 8 — Observability & Hardening

**Date**: 2026-06-03
**Status**: Draft — Awaiting Approval
**Phase**: Phase 8 of 9 (Minimum Build System Workflow)

---

## Current State (from Phases 5-7)

| Pillar | What Exists | What's Missing |
|--------|-------------|----------------|
| **Logs** | Structured JSON (Java logback, Go slog, Python structlog-ready, Node.js pino) with traceId/spanId/requestId | Log aggregation (ELK/CloudWatch not yet) |
| **Metrics** | Prometheus targets configured for all 5 services + otel-collector | Dashboards, alert rules, SLI definitions |
| **Traces** | OTel SDK/Agent → Collector → Jaeger across 4 languages | No sampling config, no error span tagging |
| **Alerts** | None | No alert rules defined |
| **Dashboards** | Grafana running (empty) | No imported dashboards |
| **Hardening** | Health probes, graceful shutdown, config validation | No OWASP check, no load test, no resilience test |
| **Runbooks** | None | No incident response docs |

---

## Phase 8 Deliverables

| # | Deliverable | Priority |
|---|-------------|----------|
| 1 | Grafana RED dashboard (JSON) for all 5 services | HIGH |
| 2 | Prometheus alert rules (consumer lag, error rate, DLQ, disk) | HIGH |
| 3 | Jaeger sampling + error span tagging config | MEDIUM |
| 4 | Production readiness checklist | HIGH |
| 5 | Runbook for top 3 failure scenarios | MEDIUM |
| 6 | Basic hardening (OWASP check config, verify backups) | MEDIUM |
| 7 | Phase 8 documentation | LOW |

## OUT OF SCOPE

- Log aggregation (ELK/CloudWatch — infrastructure dependent)
- Multi-region DR (Phase 9+)
- Chaos engineering (Phase 9+)
- Full performance load testing (Phase 9+)

---

## Files

| File | Change |
|------|--------|
| `shared/config/grafana-dashboard.json` | NEW — RED metrics dashboard |
| `shared/config/alert-rules.yml` | NEW — Prometheus alert rules |
| `shared/config/prometheus.yml` | UPDATE — add alert rule file |
| `docker-compose.yml` | UPDATE — add alertmanager (optional) |
| `docs/08-observability-hardening.md` | NEW |
| `docs/cross-cutting/operations/runbooks/` | NEW — 3 runbooks |

---

Phase 1 (SCRATCHPAD) complete. **5 files, 7 deliverables, ~1 day.**

Reply **APPROVE** to implement, or provide feedback.
