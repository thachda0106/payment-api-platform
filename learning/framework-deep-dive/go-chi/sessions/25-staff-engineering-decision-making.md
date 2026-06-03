# Session 25: Staff Engineering Decision-Making

- **Phase**: 6 — Staff Engineering Practices
- **Duration**: 5-6 hours
- **Prerequisites**: Sessions 1-24 (all prior sessions), production experience with Go/Chi services
- **Goal**: Build the analytical framework Staff/Principal engineers use to make architecture, cost, team, and platform decisions for Go/Chi services at scale.

---

## Why This Topic Exists

Senior engineers write code. Staff engineers make decisions that determine what code gets written — and what doesn't. The distinction is not about skill; it's about scope and time horizon.

| Engineer Level | Primary Output | Time Horizon | Failure Mode |
|---------------|----------------|--------------|--------------|
| Senior | Working software | 1 sprint | Bug, missed edge case |
| Staff | Technical strategy | 6-12 months | Wrong architecture, $500K cloud waste |
| Principal | Organizational capability | 2-5 years | Wrong language/framework, stranded team |

At the Staff/Principal level, you are measured not by lines of code written but by the quality and durability of your technical decisions. Go/Chi amplifies this because:

1. **Go's minimalism makes decisions more visible.** There are no annotation-driven proxies, no AOP interceptor chains, no DI container magic. Every architectural decision has a direct code footprint you can point to. If you decide on a middleware composition strategy, it's visible in `main.go` — not hidden in 15 XML files.

2. **Go's cost efficiency makes economic analysis critical.** Go's 2KB goroutine stacks and fast GC mean you can serve 10x more QPS per dollar than Java. But this only matters if you can quantify it and present it to leadership in financial terms.

3. **Chi's stdlib compatibility means framework decisions are reversible.** Unlike Spring Boot (where migration costs millions), moving from Chi to another `net/http`-compatible router is a weekend project. This changes how you think about technology risk.

4. **Go's uniform tooling standardizes team decision-making.** `gofmt`, `go vet`, `go test` — every Go team uses the same tools. This reduces the surface area of "how do we do X?" and lets you focus on "why would we do X?"

5. **Production readiness in Go is explicit.** There is no auto-configuration, no opinionated starter, no magic health check endpoint. You must decide: what observability, what circuit breaker, what graceful shutdown? Every omission is a potential 3 AM production incident.

This session teaches the decision frameworks that separate Staff/Principal engineers from senior engineers — applied specifically to the Go/Chi ecosystem.

---

## Mental Model

### The Decision Ladder

```
Level 5: Organizational Impact
        "How does this decision shape our engineering culture?"
        Example: Adopting gofmt as a code review gate — reduces
        style arguments by 90%, frees seniors for architecture review

Level 4: Platform & Ecosystem
        "What shared infrastructure does this decision require?"
        Example: Internal middleware library — 20 services benefit,
        but requires staffing a platform team of 2 engineers

Level 3: Architecture & Design
        "What is the system topology this decision implies?"
        Example: Monolith vs microservices for a Go team —
        Go's efficiency changes the break-even point significantly

Level 2: Implementation & Cost
        "What is the dollar cost of this decision over 3 years?"
        Example: Custom middleware vs community — build takes 2 weeks,
        maintain costs 4 hours/month; community costs 0 hours/month
        but introduces supply chain risk

Level 1: Code & Correctness
        "Does this code run correctly under load?"
        Example: Did we handle context cancellation in this handler?
```

Junior engineers operate at Level 1. Senior engineers span Levels 1-2. Staff engineers operate at Levels 3-4. Principal engineers operate at Level 5. The skill is knowing which level a decision lives at — applying a Level 1 solution to a Level 4 problem is as wasteful as applying a Level 4 solution to a Level 1 problem.

### The Three-Lens Decision Framework

Every meaningful architecture decision must be evaluated through three lenses simultaneously:

```
                   ┌──────────────┐
                   │  BUSINESS    │
                   │  Does this   │
                   │  make money  │
                   │  or save it? │
                   └──────┬───────┘
                          │
              ┌───────────┼───────────┐
              │                       │
     ┌────────▼────────┐   ┌─────────▼────────┐
     │    TECHNICAL     │   │    TEAM/ORG      │
     │  Is this correct │   │  Can our team    │
     │  and performant? │   │  execute this?   │
     └─────────────────┘   └──────────────────┘
```

**Business Lens**: What is the dollar value of this decision? Cloud cost savings? Faster time-to-market? Reduced incident cost? If you cannot express the value in dollars, it's a preference, not a decision.

**Technical Lens**: What are the runtime characteristics? Latency profile? Resource consumption? Failure modes? This is what most engineers default to — but it's only one lens.

**Team/Org Lens**: Does your team have the skills? Will this be maintainable after the original authors leave? Does it create a bottleneck (one person understands the auth middleware)? Does it create a bus factor of 1?

Example: Choosing a rate limiter:
- **Technical**: Redis-based distributed limiter adds 2ms latency but is accurate across instances
- **Business**: In-memory limiter saves $500/month in Redis costs but is inaccurate under auto-scaling
- **Team**: Only 1 engineer on the team understands Redis cluster management

The Staff engineer synthesizes all three and makes a recommendation.

### Decision Records as Organizational Memory

Architecture Decision Records (ADRs) are the primary output of a Staff engineer's decision work. Each ADR:

```
# ADR-042: Use Chi Router for Service Gateway

## Status
Accepted (2024-11-15)

## Context
We need an HTTP router for our API gateway serving 20 backend services.
Requirements: net/http compatibility, per-route middleware, no external deps.

## Decision
Use chi/v5 as the router.

## Alternatives Considered

### Gin
- + Fast, popular, large ecosystem
- - Requires gin.Context (not http.Handler), non-stdlib binding, adds 200+ lines of framework code per handler
- - Lock-in: migrating off Gin requires rewriting every handler signature
- Conclusion: Rejected due to stdlib incompatibility

### gorilla/mux
- + stdlib-compatible, well-known
- - Archived (no longer maintained), regex-based routing is slow for route sets >100
- Conclusion: Rejected due to unmaintained status

### net/http (stdlib only)
- + Zero dependencies, fastest possible
- - No URL parameter extraction, no middleware chain primitives, no route grouping
- - We would build poorly-tested internal replacements for chi.Route, chi.Use
- Conclusion: Rejected — we'd end up rebuilding Chi badly

### Chi
- + net/http compatible (handlers are http.Handler)
- + ~3K lines of core code, no external dependencies
- + Per-route middleware, Route grouping, Mount for subrouters
- + Actively maintained, Go idioms
- - Smaller community than Gin
- - Manual dependency injection (not a framework — intentional design)
- Conclusion: Accepted

## Consequences

Positive:
- Handlers remain standard http.Handler — testable with httptest, portable
- Zero vendor lock-in; migration to another net/http router requires only route registration changes

Negative:
- Team must learn Chi's middleware composition pattern (net/http compatible, 1-2 days learning curve)
- No built-in validation, binding, or rendering (we must choose separate libraries)

## Related ADRs
- ADR-017: Request validation library selection
- ADR-023: Structured logging with slog
```

Key properties of a good ADR:
1. **Context before decision** — what was the situation when we made this?
2. **Alternatives with concrete rejection reasons** — "rejected Gin because gin.Context locks us in" is a good reason; "rejected Gin because we prefer Chi" is not
3. **Consequences, not just benefits** — every decision creates new problems; document them
4. **Linked decisions** — ADRs form a graph; they should reference each other

---

## Internal Architecture

### The Go/Chi Decision Space

Every architectural decision in a Go/Chi service lives in one of these categories:

```
Go/Chi Decision Categories
├── Service Topology
│   ├── Monolith vs Microservices vs Modular Monolith
│   ├── Synchronous (HTTP/gRPC) vs Asynchronous (Kafka/NATS)
│   └── Database-per-service vs Shared database
├── Request Processing
│   ├── Middleware composition order
│   ├── Authentication & Authorization strategy
│   ├── Rate limiting (local vs distributed)
│   ├── Request/Response validation & transformation
│   └── Error handling & error response format
├── Observability
│   ├── Logging (slog vs zerolog vs zap)
│   ├── Tracing (OpenTelemetry vs Datadog vs none)
│   ├── Metrics (Prometheus vs VictoriaMetrics)
│   └── Profiling (pprof strategy, continuous profiling)
├── Data Access
│   ├── SQL (sqlc vs sqlx vs GORM vs raw database/sql)
│   ├── Caching (Redis vs in-memory vs none)
│   └── Transactions & consistency guarantees
├── Infrastructure
│   ├── Container orchestration (K8s vs Nomad vs EC2)
│   ├── CI/CD (GitHub Actions vs GitLab CI vs Jenkins)
│   └── Secrets management (Vault vs AWS Secrets Manager vs env vars)
└── Platform Engineering
    ├── Service template (cookiecutter vs internal generator)
    ├── Shared libraries (middleware, logging, metrics)
    └── Golden path enforcement
```

### Build vs Buy Decision Framework for Go/Chi Middleware

One of the most frequent Staff-level decisions: should we build this middleware or use a community library?

```
                          Is it core to our
                          competitive advantage?
                          /                    \
                        YES                     NO
                        /                        \
                  BUILD IT                  Is there a mature,
                                            maintained community lib
                                            that's net/http compatible?
                                            /                        \
                                          YES                         NO
                                          /                            \
                                   Does it add                      BUILD IT
                                   >2 dependencies?                  (small)
                                   /           \
                                 YES            NO
                                 /               \
                           Does the             USE IT
                           dependency tree
                           include anything
                           unvetted?
                           /          \
                         YES           NO
                         /               \
                   BUILD IT             USE IT
                   (copy pattern,       (with dependency
                    not impl)            monitoring)
```

**Concrete Example: Rate Limiter Decision**

```go
// Option A: Use community library (didip/tollbooth)
import "github.com/didip/tollbooth/v7"
import "github.com/didip/tollbooth/v7/limiter"
// + Fast integration (2 hours)
// + Battle-tested
// - Adds 3 dependencies to go.sum
// - Token bucket only (no sliding window)
// - If abandoned, you're maintaining their code

// Option B: Use Redis-based (ulule/limiter)
import "github.com/ulule/limiter/v3"
// + Sliding window algorithm
// + Distributed (Redis-backed)
// - Requires Redis infrastructure
// - Adds operational complexity

// Option C: Build custom
func NewRateLimiter(rate int, window time.Duration) func(http.Handler) http.Handler {
    // 80 lines of Go
    // Token bucket using sync/atomic — no external deps
    // Exactly the algorithm we need
    // 2 weeks to build, test, document
    // 4 hours/month maintenance
}
```

**Decision**: For a payment processor where rate limiting accuracy directly impacts revenue, Option C is correct — control the algorithm, own the behavior, no dependency risk. For an internal admin dashboard, Option A is correct — the cost of building exceeds the value of ownership.

### Cost Analysis: Go vs Java vs Node.js per 1000 Requests

This is a table every Staff engineer should be able to produce in a leadership meeting:

| Metric | Go (Chi) | Java (Spring Boot) | Node.js (Express) |
|--------|----------|-------------------|-------------------|
| Memory idle | ~8 MB | ~250 MB | ~40 MB |
| Memory under load (1K QPS) | ~50 MB | ~500 MB | ~150 MB |
| CPU per 1K req (CRUD) | ~0.05 vCPU | ~0.25 vCPU | ~0.10 vCPU |
| Startup time | ~0.1s | ~15-30s | ~0.3s |
| Container image size | ~5 MB (scratch) | ~200 MB (JRE) | ~60 MB |
| Goroutines/threads per 1K QPS | ~50 goroutines | ~200 threads | Event loop (1 thread) |
| Instances for 10K QPS | 2-3 (2 vCPU each) | 8-10 (2 vCPU each) | 4-5 (2 vCPU each) |
| Monthly compute cost (10K QPS, AWS) | ~$150 | ~$700 | ~$350 |
| Annual compute cost (10K QPS) | ~$1,800 | ~$8,400 | ~$4,200 |
| 3-year TCO (compute only) | ~$5,400 | ~$25,200 | ~$12,600 |

**Go's structural cost advantage comes from three factors:**
1. **Goroutine efficiency**: 2KB initial stack vs 1MB Java thread stack. At 10K concurrent requests, Go uses 20MB for goroutine stacks; Java uses 200MB for thread stacks (if using thread-per-request model).
2. **Compiled binary**: No JIT warmup, no runtime compilation overhead per request. Steady-state performance from the first request.
3. **GC characteristics**: Go's GC is optimized for latency-sensitive workloads. Typical GC pauses are <1ms. Java's G1GC can have 10-50ms pauses under heavy load (ZGC improves this but adds complexity).

### Capacity Planning for Go/Chi Services

```
Capacity Estimation Formula:

Goroutines per request = goroutines in handler chain + goroutines from I/O
                       = ~5 per HTTP request (middleware + handler + I/O waits)

Max concurrent requests = (memory per instance - base memory) / (goroutine stack per request * goroutines per request + heap per request)
                        = (512 MB - 10 MB) / (2 KB * 5 + 50 KB heap)
                        = 502 MB / 60 KB
                        ≈ 8,500 concurrent requests per 512 MB instance

QPS per instance = max concurrent requests / avg request latency
                = 8,500 / 0.050s (avg 50ms latency)
                = 170,000 QPS (CPU-bound limit reached earlier)

Practical guideline:
- CRUD endpoint (10ms handler + 5ms DB): 1 vCPU handles ~200 QPS
- Compute-heavy (100ms processing): 1 vCPU handles ~10 QPS
- I/O-bound (50ms DB + 20ms Redis): 1 vCPU handles ~50 QPS

Instance sizing for 10K QPS CRUD workload:
- 10,000 QPS / 200 QPS-per-vCPU = 50 vCPUs total
- 3 instances × 16 vCPU each = 48 vCPUs (with N+1 redundancy)
- Each instance: 16 vCPU, 4 GB RAM (goroutine capacity), 1 Gbps network
```

---

## Runtime Behavior

### Production Readiness Review Template for Go/Chi Services

This is the checklist a Staff engineer should use when reviewing a Go/Chi service before it goes to production:

```yaml
Production Readiness Review — Go/Chi Service
=============================================

TRAFFIC HANDLING:
  [ ] Graceful shutdown: server.Shutdown(ctx) with timeout
  [ ] Read timeout:    http.Server.ReadTimeout = 5s
  [ ] Write timeout:   http.Server.WriteTimeout = 10s
  [ ] Idle timeout:    http.Server.IdleTimeout = 120s
  [ ] Max header bytes: http.Server.MaxHeaderBytes = 1 << 20

MIDDLEWARE:
  [ ] Request ID (chi/middleware.RequestID)
  [ ] Recovery (chi/middleware.Recoverer — with custom panic handler)
  [ ] Timeout (chi/middleware.Timeout with per-route overrides)
  [ ] Logging (structured, with trace_id, request_id, user_id)
  [ ] CORS (if needed, configured explicitly, not * wildcard)

OBSERVABILITY:
  [ ] Health check endpoint: GET /health (liveness + readiness)
  [ ] Metrics endpoint: GET /metrics (Prometheus — redacted if public)
  [ ] pprof endpoints: /debug/pprof/ (protected or disabled in production)
  [ ] Structured logging: all logs go to stdout, JSON in production
  [ ] Tracing: OpenTelemetry span per request, propagated to downstream

ERROR HANDLING:
  [ ] No panic() in request handlers (use recoverer middleware as safety net)
  [ ] Errors include context (not just "internal error")
  [ ] 5xx errors are logged with stack traces
  [ ] 4xx errors are informational (no stack trace)
  [ ] Error response format is consistent across all endpoints

SECURITY:
  [ ] TLS termination (at LB or in-app)
  [ ] Authentication middleware on all non-public routes
  [ ] Authorization checks in handlers (not just middleware gate)
  [ ] Input validation on all user-supplied data
  [ ] Rate limiting on public endpoints
  [ ] Security headers (X-Content-Type-Options, X-Frame-Options, etc.)

RELIABILITY:
  [ ] Retry logic with exponential backoff for external calls
  [ ] Circuit breaker for downstream dependencies (e.g., sony/gobreaker)
  [ ] Timeouts on all external calls (HTTP, DB, Redis)
  [ ] Connection pool limits configured (database/sql: SetMaxOpenConns, SetMaxIdleConns)

DEPLOYMENT:
  [ ] Docker image < 20 MB (multi-stage build, FROM scratch or distroless)
  [ ] Readiness probe points to /health
  [ ] Liveness probe points to /health
  [ ] Resource limits set (CPU, memory) in K8s manifests
  [ ] Graceful termination handling (SIGTERM → drain connections → exit)
  [ ] Canary deployment strategy or blue/green

DATA:
  [ ] Database migrations are automated and reversible
  [ ] Database connection strings not in code (env vars or secrets manager)
  [ ] Query timeouts configured
  [ ] No N+1 queries in hot paths (verified via pprof or tracing)
```

### Platform Engineering for Go/Chi: The Internal Service Template

At the Staff level, your impact scales through platforms, not code. An internal Go/Chi service template reduces new service creation from 3 days to 3 hours:

```
myplatform/service-template/
├── cmd/
│   └── service/
│       └── main.go              # Standardized main.go with all wiring
├── internal/
│   ├── handler/
│   │   ├── health.go            # /health, /ready
│   │   └── handler.go           # Handler interface + base implementation
│   ├── middleware/
│   │   ├── auth.go              # OIDC/JWT auth middleware
│   │   ├── logging.go           # Structured logging with slog
│   │   └── requestid.go         # Request ID (tracing-compatible)
│   ├── server/
│   │   └── server.go            # Server setup, graceful shutdown
│   ├── telemetry/
│   │   ├── metrics.go           # Prometheus metrics setup
│   │   └── tracing.go           # OpenTelemetry setup
│   └── config/
│       └── config.go            # Environment-based configuration
├── pkg/
│   └── apperror/
│       └── error.go             # Standardized error types
├── migrations/
│   └── 000001_init.up.sql       # Initial migration
├── Dockerfile                    # Multi-stage, <10MB image
├── docker-compose.yaml           # Local dev with DB, Redis
├── Makefile                      # build, test, lint, migrate, run
├── .github/
│   └── workflows/
│       ├── ci.yaml              # Test, lint, build, container scan
│       └── deploy.yaml          # Deploy to staging/production
└── README.md                    # Service-specific docs
```

The template's `main.go`:

```go
package main

import (
    "context"
    "log/slog"
    "net/http"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/go-chi/chi/v5"
    "github.com/example/service-template/internal/config"
    "github.com/example/service-template/internal/handler"
    "github.com/example/service-template/internal/middleware"
    "github.com/example/service-template/internal/server"
    "github.com/example/service-template/internal/telemetry"
)

func main() {
    cfg := config.Load()
    logger := telemetry.NewLogger(cfg.LogLevel)
    slog.SetDefault(logger)

    ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
    defer cancel()

    tracer, err := telemetry.InitTracing(ctx, cfg.ServiceName)
    if err != nil {
        logger.Error("failed to init tracing", "error", err)
    }
    defer tracer.Shutdown(ctx)

    r := chi.NewRouter()

    r.Use(middleware.RequestID)
    r.Use(middleware.Logger(logger))
    r.Use(chi.middleware.Recoverer)
    r.Use(chi.middleware.Timeout(30 * time.Second))
    r.Use(middleware.Auth(cfg.AuthConfig))

    h := handler.New(handler.Deps{
        DB:     openDB(cfg.DatabaseURL),
        Logger: logger,
    })
    h.RegisterRoutes(r)

    srv := server.New(cfg.Port, r)
    go srv.ListenAndServe()
    logger.Info("server started", "port", cfg.Port)

    <-ctx.Done()
    logger.Info("shutting down")
    shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer shutdownCancel()
    if err := srv.Shutdown(shutdownCtx); err != nil {
        logger.Error("shutdown error", "error", err)
    }
}
```

### Golden Path Enforcement

The "golden path" is the supported, documented, and tooling-backed way to build services. Deviating from it requires explicit approval.

```
Golden Path: Use the service-template
├── Automatic: CI/CD pipeline, observability, Docker build
├── Standardized: Logging format, error responses, health checks
└── Supported: Platform team provides on-call support

Off-Golden Path: Custom setup
├── Manual: CI/CD pipeline must be built from scratch
├── Non-standard: May not integrate with shared dashboards
├── Self-supported: Team is responsible for all operational issues
└── Requires: Architecture review + VP approval
```

This is not about restricting engineers — it's about scaling support. When you have 5 services, custom per-service setups are fine. At 50 services, they create an operational nightmare. At 200 services, without a golden path, each service is a unique snowflake that requires specialized knowledge to operate.

---

## Flow Diagrams

### Decision Flow: Monolith vs Microservices for a Go Team

```
                        Team size > 8?
                        /              \
                      YES               NO
                      /                   \
              Monolith is likely        MVP launched?
              becoming bottleneck       /           \
                                      YES           NO
                                      /              \
                              Growth rate > 50%     START WITH
                              QPS / month?          MONOLITH
                              /           \         (Go's efficiency
                            YES           NO         carries you far)
                            /               \
                    Extraction          Is team
                    candidates:         productive
                    - Auth service      in monolith?
                    - Payment service   /          \
                    - Notification     YES          NO
                      service          /              \
                                STAY MONOLITHIC   Modular monolith
                                (Go handles scale with Chi's Mount
                                 well vertically)  for DDD boundaries
                                                  before extracting
```

**Why Go teams can stay monolithic longer:**

A well-tuned Go monolith on 8 vCPUs can handle 8,000-15,000 QPS — comparable to what a Java shop would need 3-5 microservices for. This changes the break-even point:

| Factor | Java/Spring | Go/Chi |
|--------|------------|--------|
| Monolith capacity per instance | 500-2,000 QPS | 3,000-15,000 QPS |
| Horizontal scaling need | Earlier | Later |
| Microservice extraction urgency | High | Low |
| "Microservice penalty" (network overhead) | 2-5ms per hop | 0.5-2ms per hop |

**Decision rule**: Extract to a microservice when the bounded context has independent deploy needs, independent scaling needs, or independent team ownership. Never extract solely for performance — Go's performance profile doesn't justify the operational complexity of microservices for performance reasons alone.

### The ADR Creation Flow

```
  Problem/Decision identified
           │
           ▼
  ┌─────────────────────┐
  │ RESEARCH PHASE       │
  │ - Read source code   │
  │ - Benchmark options  │
  │ - Check prior ADRs   │
  │ - Talk to teams      │
  └─────────┬───────────┘
            │
            ▼
  ┌─────────────────────┐
  │ ANALYSIS PHASE       │
  │ - Technical lens     │
  │ - Business lens      │
  │ - Team/Org lens      │
  │ - Write draft ADR    │
  └─────────┬───────────┘
            │
            ▼
  ┌─────────────────────┐
  │ REVIEW PHASE         │
  │ - Share with peers   │
  │ - Incorporate feedback│
  │ - Identify blind spots│
  └─────────┬───────────┘
            │
            ▼
  ┌─────────────────────┐
  │ DECISION PHASE       │
  │ - Accept/Reject/Defer│
  │ - Record with date   │
  │ - Link related ADRs  │
  └─────────┬───────────┘
            │
            ▼
  ┌─────────────────────┐
  │ COMMUNICATE PHASE    │
  │ - Team announcement  │
  │ - Architecture review│
  │ - Update onboarding  │
  │   docs               │
  └─────────┬───────────┘
            │
            ▼
  ┌─────────────────────┐
  │ REVISIT PHASE        │
  │ - Review in 6 months │
  │ - Did assumptions    │
  │   hold?              │
  │ - Supersede if needed│
  └─────────────────────┘
```

---

## Source Code Reading Guide

### Key Files for Understanding Go's Cost Efficiency

| File | What to Learn | Staff Relevance |
|------|--------------|-----------------|
| `runtime/stack.go` | Goroutine stack management (2KB initial, grows/shrinks) | Capacity planning: goroutines per instance |
| `runtime/proc.go` | Goroutine scheduling (G-M-P model) | Understand why Go handles 100K goroutines when Java dies at 2K threads |
| `runtime/mgc.go` | GC pacer, GC phases | Building latency SLOs that account for GC |
| `net/http/server.go` | `Server.Serve`, `conn.serve` lifecycle | Understanding graceful shutdown, connection draining |
| `net/http/transport.go` | `DefaultTransport` connection pooling | Debugging connection pool exhaustion |
| `database/sql/sql.go` | Connection pool management | Setting MaxOpenConns, MaxIdleConns correctly |

### Chi Files for Decision-Making Context

| File | Key Insight | Decision Impact |
|------|------------|----------------|
| `mux.go:1-150` | `Mux` struct — owns router, middleware, notFound handler | Understand Chi's extension points |
| `tree.go:1-200` | `node` struct, `find()` algorithm | Understand routing performance characteristics for large route tables |
| `middleware.go:1-120` | Middleware chain construction (`Use`, `With`, `Route`) | Design middleware composition strategy |

---

## Production Failure Scenarios

### Scenario 1: The $500K Cloud Bill

**Situation**: A team migrated from Java/Spring to Go/Chi but didn't adjust their instance sizing. They kept the same 16 vCPU × 64 GB RAM instances. At 1K QPS, Go's CPU utilization was 3%. They were paying for 97% idle capacity across 20 services.

**Root Cause**: Capacity planning based on Java assumptions, not Go reality.

**Fix**: Rightsizing campaign. Used pprof to measure actual CPU and memory per QPS. Reduced instance sizes by 75%, saving $500K/year.

**Prevention**: Always measure, never guess. Go services should start small (2 vCPU, 512 MB) and scale based on metrics, not assumptions inherited from other languages.

### Scenario 2: The Unbounded Goroutine

**Situation**: A Chi service handling file uploads started OOMing at 500 concurrent uploads. Investigation showed each upload spawned 3 goroutines but none had timeout contexts. Under slow clients, goroutines accumulated to 50,000+ before OOM kill.

**Root Cause**: Missing context timeouts in the upload handler chain.

**Fix**: Added `chi.middleware.Timeout(30 * time.Second)` to the upload route group. Added explicit context propagation with `context.WithTimeout` in the handler.

```go
// BEFORE (vulnerable)
func uploadHandler(w http.ResponseWriter, r *http.Request) {
    file, _, err := r.FormFile("file")
    // process file — no timeout, goroutine lives forever
}

// AFTER (safe)
func uploadHandler(w http.ResponseWriter, r *http.Request) {
    ctx, cancel := context.WithTimeout(r.Context(), 25*time.Second)
    defer cancel()
    file, _, err := r.FormFile("file")
    // process file with ctx — cancelled after 25s
}
```

### Scenario 3: The Cascading Middleware Timeout

**Situation**: A Chi service had middleware configured as:
1. Timeout (30s)
2. Auth (OIDC call, 5s timeout)
3. Rate Limiter
4. Handler

Under load, the auth middleware took 25s (network degradation), leaving the handler only 5s. The handler needed 10s minimum for DB operations. Result: all requests returned 503.

**Root Cause**: Middleware ordering created an implicit resource budget that didn't account for worst-case upstream latency.

**Fix**: Reordered middleware to apply timeout only to the handler:
```go
r.Group(func(r chi.Router) {
    r.Use(authMiddleware)  // auth has its own timeout
    r.Use(rateLimiter)
    r.Use(chi.middleware.Timeout(30 * time.Second))  // timeout wraps handler only
    r.Get("/api/payments", paymentHandler)
})
```

---

## Debugging Techniques

### Cost Debugging Flow

```
1. Identify cost anomaly: Monthly cloud bill spike for service X
2. Check deployment history: Was a new version deployed? More instances?
3. Check instance sizing: Are instances oversized for Go?
4. Profile CPU/Memory: go tool pprof for actual usage vs allocated
5. Check autoscaling config: Is min replica count too high?
6. Check idle behavior: Is service burning CPU even at 0 QPS?
```

```bash
# 1. Get instance CPU/memory config
kubectl describe pod -l app=payment-service | grep -E "Limits|Requests"

# 2. Check actual usage over last 7 days
kubectl top pod -l app=payment-service

# 3. Profile a running instance
kubectl port-forward pod/payment-service-abc123 6060:6060 &
go tool pprof http://localhost:6060/debug/pprof/profile?seconds=30

# 4. Check goroutine count (resource leak indicator)
curl -s http://localhost:6060/debug/pprof/goroutine?debug=1 | head -5

# 5. Heap profile for memory sizing
curl -s http://localhost:6060/debug/pprof/heap > heap.prof
go tool pprof -top heap.prof
```

### Team Velocity Debugging Flow

```
1. Measure: What is the average time from PR open to merge?
2. Identify bottleneck: Is it code review wait time? CI time? Merge conflicts?
3. Instrument: Add labels to PRs for categories (feature, bugfix, refactor)
4. Go-specific interventions:
   - gofmt eliminates formatting debates (style reviews → 0)
   - go vet catches common bugs before human review
   - Go's fast compilation reduces CI time vs Java projects
5. Track: Is gofmt-enforced style actually reducing review friction?
```

---

## Observability Considerations

### Decision-Making Observability

A Staff engineer needs visibility into the *outcomes* of their decisions, not just the decisions themselves. This requires instrumenting:

```go
// Decision: "We will use caching to reduce DB load"
// Observable outcome: cache hit rate, DB query reduction

var (
    cacheHits = prometheus.NewCounter(prometheus.CounterOpts{
        Name: "cache_hits_total",
        Help: "Total cache hits.",
    })
    cacheMisses = prometheus.NewCounter(prometheus.CounterOpts{
        Name: "cache_misses_total",
        Help: "Total cache misses.",
    })
    dbQueriesSaved = prometheus.NewGauge(prometheus.GaugeOpts{
        Name: "db_queries_saved_estimate",
        Help: "Estimated DB queries avoided by caching.",
    })
)
```

For every decision in an ADR, define 1-3 metrics that validate whether the decision achieved its intended outcome. Review these at the 6-month ADR revisit.

---

## Performance Implications

### Decision-Performance Interaction Matrix

| Decision | Performance Impact | When It Hurts | Mitigation |
|----------|-------------------|---------------|------------|
| Chi Router | Negligible (<0.1ms overhead) | >10K routes (binary search in children) | Use Mount for route grouping |
| Custom middleware | Variable | Blocking I/O in middleware body | Run I/O in goroutine, use context for cancellation |
| JSON encoding | 0.1-0.5ms per encode | Large response bodies (>1MB) | Use streaming JSON (`json.NewEncoder`) |
| Context propagation | Negligible | Value lookup in hot loops | Cache context values outside hot loops |
| sqlc over GORM | 2-10x faster queries | Complex dynamic queries | Use raw SQL for complex cases, sqlc for CRUD |

### Concrete Performance: Chi Middleware Chain Cost

```go
// Benchmark: Chi middleware overhead per request
func BenchmarkChiMiddlewareChain(b *testing.B) {
    r := chi.NewRouter()
    r.Use(middleware1, middleware2, middleware3, middleware4, middleware5)
    r.Get("/test", func(w http.ResponseWriter, r *http.Request) {
        w.WriteHeader(200)
    })
    // Result: ~1.2 μs per middleware layer
    // 5 middleware layers: ~6 μs overhead per request
    // At 10K QPS: 60ms total CPU spent on middleware overhead per second
    // Conclusion: Middleware overhead is not a concern — focus on handler logic
}
```

---

## Architecture Implications

### Go-Specific Organizational Impacts

**Uniform code style reduces code review friction**: Go's `gofmt` eliminates entire categories of code review comments. A Java team might spend 20% of review time on formatting and style. A Go team spends 0%. This doesn't just save time — it changes the quality of reviews. When reviewers aren't distracted by formatting, they focus on logic, correctness, and architecture.

**Fast compile times create tighter feedback loops**: A Java/Spring Boot developer saves a file and waits 15-120 seconds for a restart. During that wait, context switches to Slack, email, or another task. A Go developer saves and tests in <1 second. The cognitive difference is profound: Go's compile speed keeps developers in flow state.

**Go's explicit error handling forces architectural clarity**: Java exceptions can cross layer boundaries invisibly. Go's `if err != nil` makes every error path explicit. This isn't just a code style difference — it's an architectural constraint. In Go, you cannot accidentally swallow errors. In Spring, a misconfigured exception handler can silently convert a `DataAccessException` to a 500 error with no stack trace.

**Small binaries enable deployment flexibility**: A Go/Chi binary is 8-15 MB. A Spring Boot fat jar is 80-200 MB. This affects deployment speed, Canary rollouts, cold start time, and infrastructure cost.

### The Go Team Topology

```
Go/Chi Team Structure at Scale (50+ engineers)

┌─────────────────────────────────────────────────────────┐
│                    PLATFORM TEAM (3-5)                  │
│  - Internal service template                            │
│  - Shared middleware library                            │
│  - CI/CD pipeline maintenance                           │
│  - Golden path enforcement                              │
│  - Go tooling: linters, generators, build optimization  │
└──────────────────────┬──────────────────────────────────┘
                       │ provides templates, tooling, support
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ Payment Team │ │  Users Team  │ │ Orders Team  │
│   (4-6 eng)  │ │  (4-6 eng)  │ │  (3-5 eng)   │
├──────────────┤ ├──────────────┤ ├──────────────┤
│ 3 Go/Chi     │ │ 3 Go/Chi     │ │ 2 Go/Chi     │
│ services     │ │ services     │ │ services     │
└──────────────┘ └──────────────┘ └──────────────┘
```

The Platform Team is the Staff/Principal engineer's primary lever for organizational impact. Without a platform team, each service team independently solves logging, metrics, tracing, auth — leading to 15 different logging formats across 15 services.

---

## Team Ownership Implications

### Staff Engineer Ownership Model

```
Service ownership by level:

Senior Engineer:
  Owns: 1-3 services, end-to-end
  Scope: Code quality, testing, deployment
  Decision rights: Implementation details, library choices within approved list

Staff Engineer:
  Owns: Architecture across 5-15 services, platform components
  Scope: Cross-cutting concerns, technical standards, ADR process
  Decision rights: Framework selection, middleware strategy, data architecture

Principal Engineer:
  Owns: Technical direction for 50+ services, organizational capability
  Scope: Language/platform strategy, build vs buy, team topology
  Decision rights: Technology stack, architectural patterns, platform investment
```

### Decision Rights Matrix

| Decision | Senior | Staff | Principal |
|----------|--------|-------|-----------|
| Which library for JSON parsing | Decide | Consult | — |
| Chi vs Gin for new service | Propose | Decide | Consult |
| Monolith vs microservices | — | Propose | Decide |
| Internal platform investment | — | Propose | Decide |
| Language choice (Go vs Java vs Rust) | — | — | Decide |
| 5-year technology roadmap | — | — | Decide |

---

## Interview Questions

### Question 1: Capacity Planning Decision

**Q**: You're designing a Chi-based payment processing API expected to handle 5,000 QPS with p99 latency under 200ms. Walk through your capacity planning and instance sizing decisions.

**A**: Start with the capacity estimation formula. For a payment processing handler averaging 80ms (validation + DB write + external PSP call):
- QPS per vCPU = 1 second / 0.080s = 12.5 QPS per vCPU (simplified)
- Actually: goroutines handle I/O concurrency, so real throughput is higher
- Realistic: 1 vCPU handles ~25-30 payment QPS (due to I/O overlap via goroutines)
- For 5,000 QPS: need ~200 vCPUs total
- 10 instances × 20 vCPU each = 200 vCPUs with N+1 redundancy
- Memory: 5,000 concurrent requests × 2KB goroutine stack = 10 MB (stacks only)
- Add 50KB heap per request = 250 MB heap
- Total: ~300 MB per instance with significant headroom
- Each instance: 20 vCPU, 2 GB RAM

Key point: Go's goroutine model means you're CPU-bound, not memory-bound or thread-bound. Instance sizing should optimize for CPU, with memory as a secondary concern.

### Question 2: Build vs Buy Middleware

**Q**: Your team needs a JWT authentication middleware for 15 Chi services. The auth requirements include: JWT validation, token refresh, role-based access control, and key rotation. One engineer proposes using an open-source library; another wants to build custom. How do you decide?

**A**: Apply the build-vs-buy decision framework:

1. **Is auth core to competitive advantage?** For a payment platform, auth is critical infrastructure but not a differentiator. The open-source option is acceptable.

2. **What's available?** Evaluate `github.com/golang-jwt/jwt/v5` + custom middleware vs `github.com/go-chi/jwtauth/v5`:
   - `golang-jwt/jwt/v5`: Token parsing/validation only, 0 additional dependencies, 4,000+ stars, actively maintained
   - `go-chi/jwtauth/v5`: Chi-specific middleware, 700+ stars, opinionated, moderate maintenance

3. **Build required** for: Role-based access control (wraps JWT validation), token refresh endpoint (business logic), key rotation (JWKS endpoint polling).

4. **Decision**: Use `golang-jwt/jwt/v5` for parsing/verification (it's the de facto standard, maintained by the Go community, tiny dependency footprint). Build middleware wrapper (~100 lines) that adds RBAC, key rotation, and Chi middleware integration. This gives us control over auth behavior while leveraging a battle-tested JWT library.

5. **Cost**: 1 week to build, 2 hours/month maintenance. Open-source alternative would save build time but reduce control over error handling and RBAC semantics.

### Question 3: Migration from Monolith

**Q**: A 2-year-old Go/Chi monolith serves 8K QPS across 50 endpoints. The team has grown from 4 to 16 engineers. Merge conflicts on `main.go` and shared middleware are increasing. How do you evolve the architecture?

**A**: This is a modular monolith, not a microservices extraction. Chi's `Mount` and `Route` already give you the isolation benefits of microservices without the network overhead:

```go
// Phase 1: Modularize with chi.Mount (2 weeks, zero downtime)
r := chi.NewRouter()
r.Mount("/payments", payments.Router())  // separate package
r.Mount("/users", users.Router())        // separate package
r.Mount("/orders", orders.Router())      // separate package
```

Each module gets its own package, its own middleware, its own tests. This eliminates merge conflicts on route registration and lets teams own their domain logic independently.

**Extraction trigger**: Extract to a separate service when:
1. Independent deploy cadence needed (payments deploys weekly, users deploys daily)
2. Independent scaling needed (payments needs 20 instances, users needs 3)
3. Independent team ownership with different on-call rotations

**Migration path**: Strangler Fig pattern — route traffic gradually from monolith to new service, verified by comparing responses. When new service handles 100% of traffic for that domain, remove from monolith.

### Question 4: $/Request Cost Analysis

**Q**: Your CTO wants to know the cost per million requests for Go vs Java for a new platform. Present the analysis.

**A**: 

| Cost Factor | Go/Chi | Java/Spring Boot | Calculation |
|------------|--------|-----------------|-------------|
| Compute (per instance/month) | $56 (4 vCPU, 4GB) | $112 (4 vCPU, 16GB) | AWS m6i.xlarge vs m6i.2xlarge |
| QPS per instance | 1,500 | 400 | Measured for equivalent CRUD workload |
| Instances for 1M req/hr | 0.67 | 2.5 | 1M/hr ÷ 3600s ÷ QPS-per-instance |
| Monthly compute cost | $37 | $280 | Instances × $/instance |
| Annual compute for 1M req/hr | $444 | $3,360 | |
| Plus: JVM tuning (person-hours) | 0 | 80 hrs/year | GC tuning, heap analysis |
| Plus: Container registry cost | $0.05/GB (5MB images) | $0.05/GB (200MB images) | 40x larger images |

**$/1M requests (CRUD)**:
- Go: ~$0.03
- Java: ~$0.23

**$/1M requests (compute-heavy, 100ms processing)**:
- Go: ~$0.18
- Java: ~$0.25

Go's cost advantage shrinks for CPU-bound workloads (where both languages approach the metal) and grows for I/O-bound workloads (where Go's goroutine efficiency dominates).

### Question 5: Platform Investment Justification

**Q**: You want to invest 3 engineers for 6 months building an internal Go/Chi platform (service templates, shared middleware, CI/CD automation). How do you justify this to leadership?

**A**: Frame it as an investment with measurable ROI:

**Cost**: 3 engineers × 6 months = 18 engineer-months = ~$270K (at $15K/month fully loaded)

**Savings** (for 50 services over 2 years):
- Service creation time: 3 days → 3 hours (saves 2.5 days/service × 50 services = 125 engineer-days saved)
- Standardized observability: eliminates 2 days/service debugging non-standard logging formats × 50 services = 100 engineer-days
- Shared middleware bugs: fix once (in platform library) vs fix 50 times = saves ~200 bug-fix days
- Onboarding: new engineer productive in 2 days vs 2 weeks = 8 days saved/new engineer × 20 new engineers = 160 days
- Total saved: ~585 engineer-days = $93K

**Risk reduction** (non-monetary but real):
- Standardized security: Auth middleware updated once, propagates to all services automatically
- Consistent error handling: 50 services with same error format → simpler debugging, better user experience
- Bus factor: Knowledge centralized in platform, not siloed per service

**ROI**: $93K direct savings vs $270K investment = payback in ~18 months for direct savings alone. Including risk reduction and developer experience, the investment justifies itself.

---

## Hands-On Exercises

### Exercise 1: Write an ADR

**Task**: Choose a real technical decision your team has made (or needs to make) for a Go/Chi service. Write a complete ADR using the template from this session.

**Deliverable**: An ADR document with Context, Decision, Alternatives Considered (at least 3 alternatives with concrete rejection reasons), and Consequences.

**Validation**: Have a peer review it. Can they understand the decision context without asking you? Can they identify why each alternative was rejected? Would this ADR be useful to someone joining the team in 18 months?

### Exercise 2: Capacity Planning for a Hypothetical Service

**Task**: You're designing a Chi service that proxies requests to 5 downstream services and aggregates responses. Expected load: 2,000 QPS, each downstream takes 20-50ms. Calculate:
- Instance sizing (CPU, memory)
- Number of instances (with N+1 redundancy)
- Autoscaling thresholds
- Estimated monthly AWS cost

**Deliverable**: A capacity plan document with calculations and assumptions.

### Exercise 3: Build vs Buy Analysis

**Task**: Pick 3 middleware components your Chi services need (e.g., rate limiter, circuit breaker, CORS handler, request validation). For each, perform a build-vs-buy analysis:
1. Identify the best community library
2. Estimate build cost (hours)
3. Estimate maintenance cost (hours/month)
4. Assess dependency risk (how many transitive deps? maintained?)
5. Make a recommendation

**Deliverable**: A build-vs-buy recommendation document.

---

## Advanced Challenges

### Principal Challenge 1: Platform Investment Proposal

**Task**: Design a Go/Chi internal platform for an organization with 200 engineers, 80 Go services, and no existing platform team. You have 12 months and a team of 5.

**Requirements**:
- Service template that reduces new service creation to <4 hours
- Shared observability (logs, metrics, traces) with unified dashboards
- Automated deployment pipeline (build, test, scan, deploy, canary, rollback)
- Security baseline (auth, secrets, vulnerability scanning)
- Golden path adoption target: 80% of services within 12 months

**Deliverable**: A 10-page proposal including team structure, 12-month roadmap, success metrics, adoption strategy, and risk mitigation.

### Principal Challenge 2: Architectural Transformation Strategy

**Task**: A company has 40 Go services, each independently built by different teams over 3 years. Services use 4 different routers (Chi, Gin, Echo, gorilla/mux), 3 different logging libraries, 5 different config approaches, and no standardized CI/CD.

**Requirements**:
- Strategy to converge on a standard stack without disrupting feature development
- Migration plan with phases, timelines, and rollback strategies
- Governance model to prevent future divergence
- Cost estimate for the transformation

**Deliverable**: A technical strategy document with phased migration plan, governance proposal, and executive summary for VP-level consumption.

---

## Key Insights

1. **Staff engineers make decisions, not code.** Your primary output is ADRs, architecture reviews, and platform investments. Code is a tool for understanding, not the end product.

2. **Go's cost efficiency is a strategic weapon.** The 5-10x cost advantage over Java at scale is real and measurable. Use it in budget conversations. A $1M Java cloud bill becomes a $150K Go cloud bill — that's 5 additional engineers you can hire.

3. **Go's minimalism makes decisions more impactful.** Every middleware choice, every concurrency pattern, every context cancellation strategy is visible in the code. There's no magic framework hiding your decisions — which means bad decisions are equally visible.

4. **Chi's stdlib compatibility is risk insurance.** Choosing Chi over Gin is a decision about reversibility. Chi handlers are `http.Handler` — portable. Gin handlers are `gin.HandlerFunc` — locked in. The cost of a wrong framework choice with Chi is a weekend migration; with Gin, it's a multi-quarter rewrite.

5. **The golden path scales you, not restricts you.** Platform engineering is not about saying "no" — it's about making the right way also the easiest way. When the golden path is easier than custom, teams choose it voluntarily.

6. **Every ADR should include rejection reasons.** "We chose X" is not a decision record — it's a preference statement. "We chose X because Y was rejected due to Z" is a decision record. Future engineers can reassess if Z is no longer true.

7. **Cost per request is the universal translation layer.** Technical decisions expressed in dollars get approved. Technical decisions expressed in preferences get debated. Learn to translate: "Go's goroutine model is more efficient" → "Go serves 10x more QPS per dollar than Java."

---

## Additional Resources

- Go Runtime Scheduler: `src/runtime/proc.go` in Go source tree
- Chi Source: `github.com/go-chi/chi/v5` (read `mux.go`, `tree.go`, `middleware.go`)
- ADR Template: `https://adr.github.io/madr/`
- Team Topologies: Matthew Skelton & Manuel Pais (organizational design patterns)
- Accelerate: Nicole Forsgren et al. (measuring engineering performance)
- Google SRE Book: Chapter 32 — The Evolving SRE Engagement Model (platform engineering)
- `go tool pprof -http=:8080` for interactive performance profiling
- `gcvis` — Go GC visualization tool for understanding GC behavior under load
