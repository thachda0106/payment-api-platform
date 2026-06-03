# Phase 06 — CI/CD Pipeline (Lean)

## 🎯 Goal

Automated build, test, validate, and deploy pipeline for the polyglot Payment API Platform. CI runs on every push/PR. CD deploys to staging on merge to main with Docker runtime validation before push.

## 📥 Input

- Phase 5: 4 service skeletons + platform libs + docker-compose + Dockerfiles
- Phase 7 expanded to include payment-service

## ⚙️ What Was Built

### CI Pipeline (8 jobs)

| Job | Trigger | What |
|-----|---------|------|
| **build-libs** | Every push/PR | Builds all 4 platform libraries (Java, Go, Python, Node.js) |
| **java** | Every push/PR | Test + package financial-core + payment-service |
| **python** | Every push/PR | Test fraud-service |
| **nodejs** | Every push/PR | Test + build notification-service |
| **go** | Every push/PR | Lint + test + build settlement-service |
| **arch-test** | Every push/PR | Port uniqueness check, import boundary verification |
| **docker** | Main push only | Build all 5 Docker images |
| **system-smoke-test** | Main push only | docker-compose up + curl all 5 probes |

### CD Pipeline (3 jobs)

| Job | What |
|-----|------|
| **build-and-push** | Build image → validate runtime (curl /liveness, max 60s) → push to GHCR |
| **smoke-test** | Pull pushed images, run each, curl /liveness |
| **scan** | Trivy CRITICAL/HIGH severity scan on all 5 services → SARIF upload |

### Key Design Decisions

1. **build-libs first**: All services depend on platform libraries. `build-libs` job runs first, each service job depends on it.
2. **Docker runtime validation**: Before pushing to GHCR, each image is started, /liveness probed for up to 60s. Only passes if liveness responds 200.
3. **path-based triggers**: CI only runs when `services/`, `libs/`, `shared/`, `docker/`, or workflow files change.
4. **concurrency**: Cancel in-progress CI runs on new push to same branch (avoids queue buildup).

## 📤 Output (Artifacts)

- `.github/workflows/ci.yml` — 8 jobs, 325 lines
- `.github/workflows/cd.yml` — 3 jobs, 226 lines
- `docker/Dockerfile.java` — Multi-stage with OTel Java Agent
- `docker/Dockerfile.go` — Scratch-based static binary (no healthcheck — K8s probes only)
- `docker/Dockerfile.python` — Multi-stage slim Python
- `docker/Dockerfile.nodejs` — 3-stage Node.js

## ✅ Done Criteria

- [x] CI runs lint + tests + build on every push/PR
- [x] CD deploys to GHCR on merge to main
- [x] Docker runtime validation before push
- [x] Trivy security scanning (CRITICAL, HIGH)
- [x] System smoke test (docker-compose up + probe all services)
- [x] Architecture fitness tests in CI
- [x] < 10 minute pipeline (parallel matrix jobs)

## 🧠 What to Pay Attention To

- **OTel Java Agent is downloaded at build time** in Dockerfile.java. Version pinned via `OTEL_AGENT_VERSION` ARG. Upgrade requires updating this variable.
- **Go scratch image has no HEALTHCHECK**. Kubernetes liveness/readiness probes must be used. docker-compose will show `health: starting` permanently for Go services.
- **service matrices only include existing services**. When new services are scaffolded (Phase 7+), add them to the CI/CD matrices.

## Connection to Next Phase (Phase 7 — Build)

Phase 7 uses the CI pipeline to verify the vertical slice (payment → fraud → ledger → notification) passes all tests and smoke checks before merging to main.
