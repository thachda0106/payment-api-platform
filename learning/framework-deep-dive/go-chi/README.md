# Go Chi: Staff/Principal Engineer Learning Program

## Philosophy

This program does NOT teach Go Chi CRUD development. It teaches you to become **framework-agnostic** by understanding every layer of the Go ecosystem — from the Go runtime and `net/http` standard library, through Chi's router and middleware composition, to production architecture patterns.

```
Chi Router API   ←  What tutorials teach (5% of real knowledge)
net/http         ←  The real framework beneath Chi
Go Runtime       ←  GMP scheduler, escape analysis, GC, goroutines
Architecture     ←  Design patterns, DDD, hexagonal, CQRS, event-driven
Production       ←  Failure modes, observability, performance, profiling
Source Code      ←  Reading Chi, net/http, and Go runtime internals
Staff Thinking   ←  Trade-offs, build vs buy, organizational design
```

## Why Chi is Different

Chi is **not** a full-stack framework. It's a lightweight, idiomatic, composable HTTP router that sits on top of Go's `net/http`. This means:

- **No DI container** — use constructor injection and structs
- **No ORM** — use `database/sql`, `sqlc`, or `pgx`
- **No auto-configuration** — everything is explicit
- **No annotations/decorators** — just functions and structs
- **No magic** — you can read every line of code Chi depends on

Chi represents the Go philosophy: **composition over inheritance, explicitness over magic, standard library over framework**.

## Paradoxically Harder

Chi's simplicity means **you** must understand:

- How to structure applications without a framework dictating it
- How to compose middleware correctly
- How `context.Context` propagates through the call chain
- How goroutines interact with request lifecycle
- How to handle graceful shutdown, backpressure, connection pools

This program covers all of that, from first principles.

## Who This Is For

- Senior Engineers transitioning to Staff/Principal in Go shops
- Architects designing production Go services
- Engineers who debug Go production issues at 3 AM
- Anyone who wants to read `net/http` and Chi source code confidently
- Engineers evaluating Chi vs Gin vs Echo vs stdlib for their organization

## Prerequisites

- 5+ years of backend development
- Comfortable reading Go code
- Understanding of HTTP, databases, distributed systems
- Willingness to read Go standard library and runtime source code

## Program Structure

```
go-chi/
├── README.md                          ← This file
├── curriculum.md                      ← Full 26-session roadmap with learning objectives
│
├── sessions/
│   ├── 01-architecture-overview-project-structures.md
│   ├── 02-layered-architecture.md
│   ├── 03-feature-based-modular-monolith.md
│   ├── 04-ddd-strategic-design.md
│   ├── 05-ddd-tactical-design.md
│   ├── 06-hexagonal-architecture.md
│   ├── 07-clean-vertical-slice-architecture.md
│   ├── 08-cqrs-event-driven-evolution.md
│   ├── 09-go-runtime-goroutine-scheduler.md
│   ├── 10-go-memory-model-garbage-collection.md
│   ├── 11-go-context-propagation-tracing.md
│   ├── 12-go-net-http-server-internals.md
│   ├── 13-chi-router-internals-radix-tree.md
│   ├── 14-chi-middleware-composition-pipeline.md
│   ├── 15-chi-routing-groups-subrouters-context.md
│   ├── 16-chi-error-handling-recoverer-logging.md
│   ├── 17-chi-testing-httptest-integration.md
│   ├── 18-handler-patterns-thin-handler-design.md
│   ├── 19-service-layer-business-logic-orchestration.md
│   ├── 20-repository-persistence-sqlc-database-sql.md
│   ├── 21-production-failure-scenarios-debugging.md
│   ├── 22-observability-opentelemetry-prometheus.md
│   ├── 23-performance-benchmarking-profiling-flame-graphs.md
│   ├── 24-chi-net-http-source-code-reading-mastery.md
│   ├── 25-staff-engineering-decision-making.md
│   └── 26-framework-agnostic-mastery.md
│
├── hands-on-projects.md               ← 8 production-grade projects
├── troubleshooting-guide.md           ← Common production issues & resolution
├── interview-preparation-guide.md     ← Staff/Principal interview scenarios
└── architecture-decision-matrix.md    ← Framework for evaluating architecture choices
```

## How to Use This Program

### Self-Paced (4-5 months)

1. **Weeks 1-2**: Sessions 1-8 (Architecture foundation)
2. **Weeks 3-4**: Sessions 9-11 (Go runtime mastery)
3. **Weeks 5-7**: Sessions 12-17 (Chi + net/http internals)
4. **Weeks 8-9**: Sessions 18-20 (Application architecture)
5. **Weeks 10-11**: Sessions 21-23 (Production deep dive)
6. **Week 12**: Session 24 (Source code reading mastery)
7. **Week 13**: Sessions 25-26 (Staff/Principal thinking)
8. **Weeks 14-20**: Hands-on projects (parallel execution)

### Intensive (6-8 weeks)

Double the pace. Spend evenings reading `net/http`, Chi, and Go runtime source code.

### Just-In-Time

Jump to any session based on immediate need:
- Need to fix goroutine leak? → Session 9
- Debugging memory/GC issues? → Session 10
- Designing a middleware pipeline? → Session 14
- Building observability for Go services? → Session 22
- Profiling a production Chi service? → Session 23

## Key Differences from Spring Boot Program

| Aspect | Spring Boot Deep Dive | Go Chi Deep Dive |
|--------|----------------------|-----------------|
| Framework level | Full-stack (DI, ORM, Security, MVC) | HTTP router only |
| Magic ratio | High (auto-config, proxies, annotations) | Zero — all explicit |
| Runtime depth | JVM (GC, JIT, class loading) | Go runtime (GMP, escape analysis, GC) |
| Request model | Thread-per-request (Servlet) | Goroutine-per-request |
| Middleware model | Filter chain, interceptors | `func(http.Handler) http.Handler` |
| Persistence | JPA / Hibernate | `database/sql`, `sqlc`, `pgx` |
| Testing | @SpringBootTest, MockBean | `net/http/httptest`, interfaces |

## Learning Outcomes

By completing this program, you will:

1. **Read Go runtime source code** — understand the GMP scheduler, escape analysis, and GC
2. **Read `net/http` source code** — trace a request from `ListenAndServe` to `ServeHTTP`
3. **Read Chi source code** — understand the radix tree router and middleware chain
4. **Design production Go services** — from startup scale to enterprise scale
5. **Debug goroutine leaks, GC pauses, and connection exhaustion** in production
6. **Build comprehensive observability** with OpenTelemetry and Prometheus
7. **Make architecture decisions** with documented trade-offs
8. **Evaluate any Go framework** from first principles
9. **Choose between Chi, Gin, Echo, Fiber, stdlib** for your organization
10. **Evolve architectures** from monolith to modular monolith to microservices
