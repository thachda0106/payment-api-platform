# Go Chi: Complete Curriculum Roadmap

## Learning Objectives

### By Phase 0 Completion (Architecture)
- Evaluate any project structure against team size and growth trajectory in Go
- Choose between layered, feature-based, modular monolith, hexagonal, and clean architecture
- Apply DDD strategic patterns to define bounded contexts in Go services
- Design aggregates, domain events, and repositories correctly in idiomatic Go
- Explain when CQRS and event sourcing add value vs complexity
- Plan architecture evolution paths for Go-based systems

### By Phase 1 Completion (Runtime)
- Explain the GMP goroutine scheduling model and diagnose scheduler contention
- Read goroutine dumps and identify goroutine leaks in production
- Understand Go's escape analysis and optimize allocations
- Tune GOGC and debug GC latency using trace and pprof
- Implement distributed tracing with proper context.Context propagation
- Write benchmark tests and interpret CPU/memory profiles

### By Phase 2 Completion (Framework Core)
- Trace a request from `net.Listen` through `ServeHTTP` to the handler
- Read Chi's radix tree router source code and understand route resolution
- Design composable middleware pipelines using `func(http.Handler) http.Handler`
- Understand Chi's context-based route parameters and subrouter scoping
- Debug middleware ordering issues and `next.ServeHTTP` control flow
- Extend Chi with custom middleware following production patterns

### By Phase 3 Completion (Application Architecture)
- Design Thin Handler patterns with proper separation of concerns
- Implement service layer orchestration with explicit dependency injection
- Use `database/sql` and `sqlc` for type-safe, performant persistence
- Handle distributed transactions with sagas and outbox patterns
- Apply the Repository pattern idiomatically in Go (interfaces, not generics)

### By Phase 4 Completion (Production)
- Diagnose goroutine leaks, GC pauses, and connection pool exhaustion
- Build comprehensive observability with OpenTelemetry and Prometheus
- Create Service Level Objectives (SLOs) with error budgets
- Profile production services using pprof and flame graphs
- Debug production failures from goroutine dumps, trace spans, and metrics

### By Phase 5 Completion (Source Code Reading)
- Navigate Chi's ~5K lines of source code efficiently
- Understand `net/http.Server.Serve` internal architecture
- Read Go runtime scheduler source (proc.go, runtime2.go)
- Contribute to Chi or stdlib with confidence

### By Phase 6 Completion (Staff Engineer Thinking)
- Make architecture decisions with documented trade-offs (ADRs)
- Evaluate build vs buy for Go ecosystem components
- Choose between Chi, Gin, Echo, Fiber, and stdlib for your organization
- Design team structures aligned with system architecture (Conway's Law)
- Become framework-agnostic: evaluate any framework from first principles

---

## Session Schedule (26 Sessions)

### PHASE 0: Architecture & Source Structure

| Session | Topic | Duration |
|---------|-------|----------|
| 01 | Architecture Overview & Project Structures for Go Services | 3-4 hours |
| 02 | Layered Architecture Deep Dive (Go idioms vs framework patterns) | 3-4 hours |
| 03 | Feature-Based & Modular Monolith Architecture in Go | 3-4 hours |
| 04 | Domain-Driven Design: Strategic Patterns | 4-5 hours |
| 05 | Domain-Driven Design: Tactical Patterns in Go | 4-5 hours |
| 06 | Hexagonal Architecture (Ports & Adapters) in Go | 3-4 hours |
| 07 | Clean Architecture & Vertical Slice Architecture | 3-4 hours |
| 08 | CQRS, Event-Driven Architecture & Architecture Evolution | 4-5 hours |

### PHASE 1: Go Runtime Foundation

| Session | Topic | Duration |
|---------|-------|----------|
| 09 | Go Runtime: GMP Scheduler & Goroutine Internals | 4-5 hours |
| 10 | Go Memory Model: Escape Analysis & Garbage Collection | 4-5 hours |
| 11 | Context Propagation: context.Context Deep Dive & Distributed Tracing | 3-4 hours |
| 12 | net/http Server Internals: From ListenAndServe to ServeHTTP | 4-5 hours |

### PHASE 2: Chi Framework Core

| Session | Topic | Duration |
|---------|-------|----------|
| 13 | Chi Router Internals: Radix Tree, Route Resolution, Route Matching | 4-5 hours |
| 14 | Chi Middleware: Composition Pipeline, Ordering, next.ServeHTTP | 4-5 hours |
| 15 | Chi Routing: Groups, Subrouters, Context, URL Parameters | 3-4 hours |
| 16 | Chi Error Handling: Recoverer, Custom Error Responses, Logging | 3-4 hours |
| 17 | Chi Testing: httptest, Integration Tests, Test Helpers | 3-4 hours |

### PHASE 3: Application Architecture

| Session | Topic | Duration |
|---------|-------|----------|
| 18 | Handler Patterns: Thin Handler Design, DTOs, Validation | 3-4 hours |
| 19 | Service Layer: Business Logic, Orchestration, Dependency Injection | 3-4 hours |
| 20 | Repository & Persistence: database/sql, sqlc, pgx, Transactions | 4-5 hours |

### PHASE 4: Production Deep Dive

| Session | Topic | Duration |
|---------|-------|----------|
| 21 | Production Failure Scenarios & Debugging | 4-5 hours |
| 22 | Observability: Logging, Metrics, Tracing with OpenTelemetry | 4-5 hours |
| 23 | Performance: Benchmarking, Profiling, pprof, Flame Graphs | 4-5 hours |

### PHASE 5: Source Code Reading Mastery

| Session | Topic | Duration |
|---------|-------|----------|
| 24 | Chi & net/http Source Code Reading Mastery | 4-5 hours |

### PHASE 6: Staff/Principal Engineer Thinking

| Session | Topic | Duration |
|---------|-------|----------|
| 25 | Architecture Decision-Making for Staff Engineers | 3-4 hours |
| 26 | Framework-Agnostic Mastery: Evaluating Go Frameworks from First Principles | 3-4 hours |

---

## Architecture Evolution Roadmap

This is the realistic path most successful Go microservices follow:

```
Stage 1: Single-File HTTP Server
├── 1-2 engineers, <10 endpoints
├── Everything in main.go or a single handler file
├── Uses net/http directly or Chi with inline handlers
├── Works until: 3+ engineers, 20+ endpoints, merge conflicts
│
↓ Migration trigger: Single file too large, no clear ownership, testing hard
│
Stage 2: Layered Packages
├── 2-5 engineers, 20-50 endpoints
├── Packages: handler/, service/, repository/ (Go-style layers)
├── struct-based handlers, explicit wire-up in main.go
├── Works until: 8+ engineers, cross-cutting concerns, feature coupling
│
↓ Migration trigger: Different teams touch same layers, cannot deploy independently
│
Stage 3: Feature Packages
├── 5-15 engineers, 50-150 endpoints
├── Packages: orders/, payments/, users/
├── Each feature contains handler, service, repository, models
├── Works until: 20+ engineers, shared domain logic, scaling needs
│
↓ Migration trigger: Features coupling through database, need independent scaling
│
Stage 4: Modular Monolith (Go Modules)
├── 10-30 engineers
├── Separate Go modules with explicit APIs (interfaces in api/ packages)
├── Compile-time dependency enforcement via Go imports
├── Shared kernel for common types, no shared database models
├── Works until: 50+ engineers, org boundaries require independent services
│
↓ Migration trigger: Conway's Law pressure, need different deploy cadences
│
Stage 5: Domain-Oriented Services
├── 20-80+ engineers
├── Bounded contexts become independently deployed services
├── Event-driven communication via Kafka/NATS
├── Each service owns its database
├── Chi remains the HTTP router for each service (no framework migration needed!)
│
↓ Ongoing: Split services by organizational boundaries, not technical convenience
```

### Key Insight

**Go services scale differently than Java/Spring services.** A single Go binary with Chi can handle 10K+ concurrent connections with <50MB memory. You can out-scale a 20-engine Spring Boot service with a 3-engine Go service. Architecture evolution in Go is more about **team structure and code organization** than infrastructure scaling.

**Chi scales from startup to enterprise without changing the framework.** Unlike Spring Boot where you might migrate from embedded Tomcat to reactive WebFlux, Chi works the same way at 10 QPS and 100K QPS — because `net/http` + goroutines was designed for scale from day one.

---

## Source Code Reading Roadmap

### Level 1: Chi Router (Start Here — ~2K lines)

```
go-chi/chi/v5/
├── chi.go               ← Core: NewRouter(), Mux type, Use(), Handle(), Route()
├── mux.go               ← Route matching, ServeHTTP, route building
├── tree.go               ← Radix tree implementation (READ THIS — it's beautiful)
├── middleware/
│   ├── middleware.go     ← RequestID, RealIP, Logger, Recoverer, Timeout, etc.
│   ├── logger.go         ← Structured request logging
│   ├── recoverer.go      ← Panic recovery (the pattern for all custom middleware)
│   ├── timeout.go        ← Context-based timeout middleware
│   └── compress.go       ← Response compression
├── context.go            ← Route context: URLParam, RouteContext
└── chain.go              ← Middleware chain execution
```

### Level 2: Go net/http (Standard Library — ~5K lines core)

```
go/src/net/http/
├── server.go             ← Server.Serve(), conn.serve(), ServeMux (READ FIRST)
├── request.go            ← Request type, context integration, body parsing
├── response.go           ← ResponseWriter interface, write path
├── transport.go          ← DefaultTransport, connection pooling (client side)
├── handler.go            ← Handler interface, HandlerFunc, ServeMux
├── status.go             ← Status codes
├── cookie.go             ← Cookie handling
├── httptest/             ← Test utilities (ResponseRecorder, NewServer)

Key reading order:
  1. handler.go — understand the Handler interface
  2. server.go — read Serve() and conn.serve() in detail
  3. request.go — understand context integration
  4. response.go — understand ResponseWriter
```

### Level 3: Go Runtime (For goroutine/GC mastery)

```
go/src/runtime/
├── runtime2.go           ← goroutine struct (g), machine struct (m), processor struct (p)
├── proc.go               ← Scheduler: schedule(), findrunnable(), sysmon()
├── stack.go              ← Goroutine stack management, stack growth
├── malloc.go             ← Memory allocator
├── mgc.go                ← GC entry points, GC phases
├── mgcmark.go            ← Mark phase
├── mgcsweep.go           ← Sweep phase
├── mheap.go              ← Heap management
├── mcache.go             ← Per-P allocation cache
├── chan.go               ← Channel implementation
├── select.go             ← Select statement implementation
├── map.go                ← Map implementation (read this!)
├── slice.go              ← Slice implementation
└── trace.go              ← Execution tracer

Go runtime source guide:
  Key files for understanding:
  1. runtime2.go — type definitions (g, m, p structs)
  2. proc.go — the GMP scheduler in action
  3. mgc.go — GC lifecycle
  Files to ignore initially:
  - os_*.go (platform-specific code)
  - asm_*.s (assembly)
  - *_test.go (test files with 10K+ lines)
```

---

## Production Troubleshooting Guide (Quick Reference)

### Symptom → Root Cause → Tool

| Symptom | Likely Root Cause | Diagnostic Command |
|---------|-------------------|-------------------|
| Latency spikes at steady QPS | GC pause | `GODEBUG=gctrace=1`, `go tool trace` |
| Memory grows unbounded | Goroutine leak or allocation leak | `pprof goroutine`, `pprof heap` |
| Requests hang indefinitely | Goroutine leak (unclosed body/response) | `pprof goroutine`, check `block` profile |
| 502 errors from reverse proxy | Server overload, accept queue full | `netstat -an`, check `ListenAndServe` backpressure |
| Random 500s under load | Unhandled panics (missing Recoverer) | Add `middleware.Recoverer`, check logs |
| DB connection timeout | Connection pool exhaustion | `DB.Stats()`, check `MaxOpenConns`/`MaxIdleConns` |
| Slow response at low QPS | N+1 queries or serialization bottleneck | `pprof CPU profile`, sql query logs |
| Context canceled errors | Timeout middleware too aggressive | Check `middleware.Timeout` duration |
| High goroutine count | Goroutine leak or unbounded concurrency | `pprof goroutine`, `runtime.NumGoroutine()` |
| OOM killed by Kubernetes | Memory leak or GC not keeping up | `pprof heap`, set `GOMEMLIMIT` |

### Quick Diagnostic Commands

```bash
# Goroutine dump
curl http://localhost:6060/debug/pprof/goroutine?debug=2

# Heap profile
go tool pprof -http=:8081 http://localhost:6060/debug/pprof/heap

# CPU profile (30-second sample)
go tool pprof -http=:8081 http://localhost:6060/debug/pprof/profile?seconds=30

# GC trace
GODEBUG=gctrace=1 ./myapp

# Goroutine leak detection
go test -race ./...                       # Race detector
go test -count=1 -run=TestX ./...         # Run once to detect leaks

# Benchmark with memory
go test -bench=. -benchmem -memprofile=mem.out ./...
```

---

## Framework Comparison: Chi vs Alternatives

| Dimension | Chi | Gin | Echo | Fiber | net/http |
|-----------|-----|-----|------|-------|----------|
| Paradigm | stdlib-compatible | Framework-specific | Framework-specific | fasthttp-based | Standard library |
| Handler signature | `http.Handler` | `gin.HandlerFunc` | `echo.HandlerFunc` | `fiber.Handler` | `http.Handler` |
| Middleware | `func(http.Handler) http.Handler` | Custom chain | Custom chain | Custom chain | Manual wrapping |
| Routing | Radix tree | Radix tree | Radix tree | Radix tree | Simple prefix |
| Performance | Excellent | Excellent | Excellent | Fastest | Good |
| Stdlib compat | 100% | Partial | Partial | None | 100% |
| Dependencies | Minimal (~5 packages) | Moderate | Moderate | Moderate | Zero |
| Learning curve | Low (idiomatic Go) | Low-Medium | Low-Medium | Low | Lowest |
| Production readiness | High | High | High | Medium (fasthttp gaps) | High |
| Ecosystem lock-in | None | Some | Some | High | None |

### When to Choose Chi

- You want 100% `net/http` compatibility (critical for ecosystem interop)
- You value composability and explicitness over convenience
- Your team understands Go idioms and doesn't want framework magic
- You need middleware that works across any `http.Handler` (not just Chi handlers)
- You're building a platform where different services may use different routers

### When NOT to Choose Chi

- You want a full-stack framework (Chi is just a router)
- Your team prefers convention over configuration (Chi is convention-free)
- You want built-in validation, serialization, ORM (Chi does none of this)
- You need maximum raw throughput at the cost of stdlib compatibility (use Fiber/fasthttp)
