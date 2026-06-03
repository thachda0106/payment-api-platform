# SCRATCHPAD: Phase 6 — CI/CD Pipeline (Lean)

**Date**: 2026-06-03
**Status**: Draft — Awaiting Approval
**Phase**: Phase 6 of 9 (Minimum Build System Workflow)

---

## Current State

CI (`ci.yml`) and CD (`cd.yml`) already exist with:
- ✅ Multi-language matrix builds (Java/Python/Node.js/Go)
- ✅ Lint, test, build per language
- ✅ Docker image build (main push only)
- ✅ Trivy security scanning (CRITICAL, HIGH)
- ✅ Contract validation (OpenAPI + Avro)
- ✅ GitHub Container Registry push
- ✅ Caching (Maven, npm, pip, go)

## What's Needed

| # | Change | Why |
|---|--------|-----|
| 1 | Add `arch-test` job | Phase 5 introduced architecture fitness tests — must run in CI |
| 2 | Add `build-libs` step before services | Services depend on `libs/` packages — must build first |
| 3 | Remove nonexistent services from matrices | `ci.yml` lists 17 services; only 4 exist. CI wastes time on missing dirs. |
| 4 | Add docker-compose service integration test | Verify all 4 services start and probes respond in CI |
| 5 | Update CD service matrix | Only build/push 4 existing services, not 17 |
| 6 | Add health check smoke test after CD deploy | curl all 4 `/liveness` endpoints after push |
| 7 | Add `libs/` path trigger | When libs change, all services should be rebuilt |

## OUT OF SCOPE (Team of 1 — no staging infra)

- Multi-environment deployment (staging/production)
- Manual approval gates for production
- Canary/blue-green deployment
- Full integration test suite (Phase 7)

---

## File Changes

| File | Change |
|------|--------|
| `.github/workflows/ci.yml` | Add arch-test job, build-libs step, prune matrices, add docker-compose test |
| `.github/workflows/cd.yml` | Prune to 4 services, add smoke test step |
| `Makefile` | Ensure `make arch-test` works in CI context |

---

Phase 1 (SCRATCHPAD) complete. Reply **APPROVE** to proceed to implementation.
