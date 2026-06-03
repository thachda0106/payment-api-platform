# Go HTTP Framework Architecture Decision Matrix

> **Purpose**: Data-driven framework selection for Go HTTP services
> **Audience**: Staff/Principal Engineers, Engineering Managers, Architects
> **Methodology**: Score each framework 1-10 across 12 dimensions, then apply weighted scoring per scenario

---

## Scoring Tables

### Dimension Definitions

| Dimension | Description | Weight (1-10) |
|-----------|-------------|---------------|
| **Learning Curve** | Hours for a Go developer to become productive. Lower = better. | 8 |
| **Performance** | ns/op route lookup + handler overhead. Measured, not perceived. | 6 |
| **stdlib Compatibility** | Does it implement `http.Handler`? Can handlers be tested with `httptest`? | 10 |
| **Ecosystem** | Available middleware, plugins, community libraries. | 7 |
| **Community Size** | GitHub stars, contributors, forum activity, Stack Overflow questions. | 5 |
| **Documentation** | Quality of docs, examples, tutorials, API reference. | 6 |
| **Testability** | Ease of unit/integration testing handlers and middleware. | 9 |
| **Middleware Ecosystem** | Built-in middleware quality and quantity, third-party middleware availability. | 7 |
| **Production Readiness** | Graceful shutdown, timeouts, TLS, health checks, deployment tooling. | 10 |
| **Long-term Stability** | Release frequency, breaking change history, maintainer commitment. | 9 |
| **Go Idiom Alignment** | Uses standard Go patterns, error handling, context usage. | 8 |
| **Upgrade Risk** | Difficulty of upgrading major versions, breaking changes, migration effort. | 9 |

### Framework Comparison (1-10 scale, 10 = best)

| Dimension | Chi | Gin | Echo | Fiber | gorilla/mux | net/http |
|-----------|-----|-----|------|-------|-------------|----------|
| Learning Curve | 8 | 7 | 7 | 5 | 8 | 10 |
| Performance | 8 | 9 | 9 | 10 | 4 | 9 |
| stdlib Compatibility | 10 | 6 | 9 | 2 | 9 | 10 |
| Ecosystem | 7 | 9 | 8 | 6 | 8 | 10 |
| Community Size | 6 | 9 | 8 | 9 | 8 | 10 |
| Documentation | 7 | 8 | 8 | 8 | 7 | 10 |
| Testability | 10 | 7 | 8 | 4 | 9 | 10 |
| Middleware Ecosystem | 7 | 9 | 8 | 7 | 6 | 1 |
| Production Readiness | 8 | 8 | 7 | 6 | 5 | 8 |
| Long-term Stability | 9 | 7 | 7 | 5 | 2 | 10 |
| Go Idiom Alignment | 10 | 5 | 6 | 3 | 8 | 10 |
| Upgrade Risk | 9 (low) | 6 | 7 | 4 | 10 (dead) | 10 (guaranteed) |

### Scoring Rationale

#### Chi (go-chi/chi)
- **stdlib Compatibility (10)**: Every Chi handler is `http.Handler`. Middleware is `func(http.Handler) http.Handler`. Zero custom types. Testable with `httptest` without adapters.
- **Go Idiom Alignment (10)**: Uses `context.Context` natively. Error handling is explicit (no framework error wrappers). Composition over inheritance. Minimal magic.
- **Upgrade Risk (9)**: Chi has had 5 major versions, but core API (`NewRouter`, `Get`, `Use`, `Route`, `Mount`) is stable since v1. Migration between major versions is usually import path changes.
- **Community Size (6)**: Smaller community than Gin (12K stars vs 77K), but active and responsive. Pressly (the company behind it) uses it in production at scale.
- **Long-term Stability (9)**: Codebase is ~3K lines. Even if abandoned, it's small enough for any team to fork and maintain. No external dependencies.

#### Gin (gin-gonic/gin)
- **Performance (9)**: Radix tree routing similar to Chi. Uses `jsoniter` for faster JSON (but adds dependency). ~200ns per route lookup.
- **stdlib Compatibility (6)**: `gin.HandlerFunc` is NOT `http.Handler`. Must use `gin.WrapH()` to bridge. `gin.Context` replaces `http.ResponseWriter` and `*http.Request` — handlers are not portable.
- **Go Idiom Alignment (5)**: Uses `c.Next()` and `c.Abort()` for middleware flow control (non-standard). Error handling through `c.Error()` and `c.Errors` (accumulates errors). `c.MustBindWith()` can panic.
- **Upgrade Risk (6)**: Gin v1.9 introduced breaking changes. The `binding` package API changes between versions. Large codebase (~15K lines) — harder to fork and maintain if abandoned.

#### Echo (labstack/echo)
- **stdlib Compatibility (9)**: Echo handlers accept `echo.Context` but it wraps `http.ResponseWriter` and `*http.Request`. Can convert to/from `http.Handler` easily. Middleware is function-based but can adapt to stdlib signature.
- **Documentation (8)**: Excellent official docs with recipes and examples. Generated API reference. Cookbook with common patterns.
- **Middleware Ecosystem (8)**: Built-in middleware covers most needs (JWT, CORS, Logger, Recover, Rate Limiter, Body Limit, Secure, Session). Third-party middleware readily available.
- **Long-term Stability (7)**: Maintained by a single developer (LabStack). Has had periods of inactivity. Bus factor of 1 is a concern for enterprise adoption.

#### Fiber (gofiber/fiber)
- **Performance (10)**: Built on `fasthttp`, not `net/http`. Uses zero-allocation request parsing. ~5-10x faster than `net/http`-based routers for trivial routes.
- **stdlib Compatibility (2)**: Fundamentally incompatible with `net/http`. Cannot use `httptest`, `otelhttp`, or any `net/http` middleware without adapters. Separate ecosystem (Fiber middleware, not `net/http` middleware).
- **Go Idiom Alignment (3)**: Inspired by Express.js (JavaScript), not Go conventions. Uses method chaining extensively. Error handling through `c.Next()` with error accumulation.
- **Testability (4)**: No `httptest` compatibility. Must use Fiber's own test utilities. Cannot test Fiber handlers as pure Go functions.
- **Upgrade Risk (4)**: Fiber v3 introduced major breaking changes. Large API surface area (~20K lines). Fast-moving project with frequent API changes.

#### gorilla/mux (gorilla/mux)
- **Long-term Stability (2)**: **Archived** in December 2022. No longer maintained. No security patches. No Go version compatibility updates. Do not use for new projects.
- **Performance (4)**: Regex-based routing. Linear scan on route registration for conflict detection. Each route match requires regex execution. ~3-5x slower than Chi for >100 routes.
- **Production Readiness (5)**: Archived means no security patches. This is a dealbreaker for production services.

#### net/http (stdlib)
- **Everything (10 or 1)**: The standard library is the baseline. It's compatible with everything (it IS everything). Zero dependencies. Guaranteed maintenance with Go itself. But it lacks middleware composition primitives, route grouping, URL parameter extraction (pre-1.22), and subrouter mounting.
- **Middleware Ecosystem (1)**: No built-in middleware (no logger, no recovery, no CORS, no auth). You must build or import everything.
- **Learning Curve (10)**: If you know Go, you know `net/http`. Zero additional API to learn.

---

## Weighted Scoring by Scenario

### Scenario 1: Startup MVP (Speed to Market)

**Priorities**: Fast development, large ecosystem, minimal boilerplate, junior-friendly.

| Dimension | Weight | Chi (score × weight) | Gin | Echo | Fiber | net/http |
|-----------|--------|-----------------|-----|------|-------|----------|
| Learning Curve | 8 | 8×8=64 | 7×8=56 | 7×8=56 | 5×8=40 | 10×8=80 |
| Performance | 3 | 8×3=24 | 9×3=27 | 9×3=27 | 10×3=30 | 9×3=27 |
| stdlib Compatibility | 5 | 10×5=50 | 6×5=30 | 9×5=45 | 2×5=10 | 10×5=50 |
| Ecosystem | 8 | 7×8=56 | 9×8=72 | 8×8=64 | 6×8=48 | 10×8=80 |
| Community Size | 7 | 6×7=42 | 9×7=63 | 8×7=56 | 9×7=63 | 10×7=70 |
| Documentation | 8 | 7×8=56 | 8×8=64 | 8×8=64 | 8×8=64 | 10×8=80 |
| Testability | 5 | 10×5=50 | 7×5=35 | 8×5=40 | 4×5=20 | 10×5=50 |
| Middleware Ecosystem | 9 | 7×9=63 | 9×9=81 | 8×9=72 | 7×9=63 | 1×9=9 |
| Production Readiness | 6 | 8×6=48 | 8×6=48 | 7×6=42 | 6×6=36 | 8×6=48 |
| Long-term Stability | 5 | 9×5=45 | 7×5=35 | 7×5=35 | 5×5=25 | 10×5=50 |
| Go Idiom Alignment | 4 | 10×4=40 | 5×4=20 | 6×4=24 | 3×4=12 | 10×4=40 |
| Upgrade Risk | 4 | 9×4=36 | 6×4=24 | 7×4=28 | 4×4=16 | 10×4=40 |
| **TOTAL** | | **574** | **555** | **553** | **427** | **624** |

**Winner**: `net/http` (Go 1.22+ ServeMux) for simplicity, then Chi for routing complexity.
**Runner-up**: Gin (ecosystem, middleware, community size).
**Rationale**: For a startup MVP, `net/http` minimizes dependencies and learning curve. If routing needs exceed stdlib (subrouters, per-route middleware), Chi adds the needed capability while preserving stdlib compatibility.

### Scenario 2: Enterprise Platform (30+ Services, 3+ Year Horizon)

**Priorities**: Stability, maintainability, stdlib compatibility, low upgrade risk, team scalability.

| Dimension | Weight | Chi (score × weight) | Gin | Echo | Fiber | net/http |
|-----------|--------|-----------------|-----|------|-------|----------|
| Learning Curve | 6 | 8×6=48 | 7×6=42 | 7×6=42 | 5×6=30 | 10×6=60 |
| Performance | 5 | 8×5=40 | 9×5=45 | 9×5=45 | 10×5=50 | 9×5=45 |
| stdlib Compatibility | 10 | 10×10=100 | 6×10=60 | 9×10=90 | 2×10=20 | 10×10=100 |
| Ecosystem | 6 | 7×6=42 | 9×6=54 | 8×6=48 | 6×6=36 | 10×6=60 |
| Community Size | 4 | 6×4=24 | 9×4=36 | 8×4=32 | 9×4=36 | 10×4=40 |
| Documentation | 6 | 7×6=42 | 8×6=48 | 8×6=48 | 8×6=48 | 10×6=60 |
| Testability | 9 | 10×9=90 | 7×9=63 | 8×9=72 | 4×9=36 | 10×9=90 |
| Middleware Ecosystem | 7 | 7×7=49 | 9×7=63 | 8×7=56 | 7×7=49 | 1×7=7 |
| Production Readiness | 10 | 8×10=80 | 8×10=80 | 7×10=70 | 6×10=60 | 8×10=80 |
| Long-term Stability | 10 | 9×10=90 | 7×10=70 | 7×10=70 | 5×10=50 | 10×10=100 |
| Go Idiom Alignment | 8 | 10×8=80 | 5×8=40 | 6×8=48 | 3×8=24 | 10×8=80 |
| Upgrade Risk | 10 | 9×10=90 | 6×10=60 | 7×10=70 | 4×10=40 | 10×10=100 |
| **TOTAL** | | **775** | **661** | **691** | **479** | **822** |

**Winner**: `net/http` (weighted by stability/upgrade risk), closely followed by Chi.
**Recommended**: Chi (because `net/http` lacks middleware composition primitives needed for 30+ services, and Chi is the minimal addition that preserves all stdlib benefits).
**Rationale**: For an enterprise platform with a 3+ year horizon, stability and stdlib compatibility dominate. Chi's zero external dependencies, small codebase, and `http.Handler` compatibility mean the platform investment (service templates, shared middleware, CI/CD) is protected from framework changes.

### Scenario 3: High-Performance Edge Service (<1ms p99, 100K+ QPS)

**Priorities**: Raw performance, minimal allocations, zero GC pressure in hot path.

| Dimension | Weight | Chi | Gin | Echo | Fiber | net/http |
|-----------|--------|-----|-----|------|-------|----------|
| Learning Curve | 3 | 8×3=24 | 7×3=21 | 7×3=21 | 5×3=15 | 10×3=30 |
| Performance | 10 | 8×10=80 | 9×10=90 | 9×10=90 | 10×10=100 | 9×10=90 |
| stdlib Compatibility | 5 | 10×5=50 | 6×5=30 | 9×5=45 | 2×5=10 | 10×5=50 |
| Ecosystem | 4 | 7×4=28 | 9×4=36 | 8×4=32 | 6×4=24 | 10×4=40 |
| Community Size | 3 | 6×3=18 | 9×3=27 | 8×3=24 | 9×3=27 | 10×3=30 |
| Documentation | 4 | 7×4=28 | 8×4=32 | 8×4=32 | 8×4=32 | 10×4=40 |
| Testability | 5 | 10×5=50 | 7×5=35 | 8×5=40 | 4×5=20 | 10×5=50 |
| Middleware Ecosystem | 5 | 7×5=35 | 9×5=45 | 8×5=40 | 7×5=35 | 1×5=5 |
| Production Readiness | 8 | 8×8=64 | 8×8=64 | 7×8=56 | 6×8=48 | 8×8=64 |
| Long-term Stability | 6 | 9×6=54 | 7×6=42 | 7×6=42 | 5×6=30 | 10×6=60 |
| Go Idiom Alignment | 6 | 10×6=60 | 5×6=30 | 6×6=36 | 3×6=18 | 10×6=60 |
| Upgrade Risk | 5 | 9×5=45 | 6×5=30 | 7×5=35 | 4×5=20 | 10×5=50 |
| **TOTAL** | | **536** | **482** | **493** | **379** | **569** |

**Winner**: `net/http` (best balance of performance + compatibility).
**Recommended**: `net/http` (Go 1.22+) for simple routing, Chi for complex routing. Or Fiber if you control the entire stack and need absolute maximum performance (accept the ecosystem trade-off).
**Rationale**: Even for high-performance scenarios, the router overhead (200-400ns) is negligible compared to handler logic. The decision should weigh ecosystem compatibility as heavily as raw performance. Fiber's 10x advantage only matters if your handlers are also <1μs — which is almost never the case for real services.

---

## Architecture Style Compatibility Matrix

| Architecture Style | Chi | Gin | Echo | Fiber | net/http |
|-------------------|-----|-----|------|-------|----------|
| **Layered Architecture** | Native | Good | Good | Good | Good |
| **Hexagonal (Ports & Adapters)** | Native | Adapter needed | Good | Difficult | Native |
| **Modular Monolith** | Native (`Mount`) | Groups | Groups | Groups | Manual |
| **Microservices** | Native | Good | Good | Good | Good |
| **Event-Driven (CQRS/ES)** | Native | Good | Good | Difficult | Native |
| **API Gateway** | Native | Good | Good | Limited | Good |
| **BFF (Backend for Frontend)** | Native | Good | Good | Good | Good |
| **DDD (Domain-Driven Design)** | Native | Adapter needed | Good | Difficult | Native |
| **Clean Architecture** | Native | Adapter needed | Good | Difficult | Native |

Definitions:
- **Native**: Framework naturally supports this architecture without adapters or workarounds
- **Good**: Architecture works well, minor adjustments needed
- **Adapter needed**: Framework requires conversion layer between architecture concepts and framework concepts
- **Manual**: Framework provides no support — must be built from scratch
- **Difficult**: Framework actively works against this architecture pattern
- **Limited**: Framework supports a subset of the pattern

---

## Team Size Recommendations

| Team Size | Recommended Router | Rationale |
|-----------|-------------------|-----------|
| 1-3 engineers | `net/http` (Go 1.22+) | Simplicity wins. If routing needs are simple, avoid external deps entirely. |
| 4-8 engineers | Chi | stdlib compatibility matters when code is shared across team members. Route groups reduce merge conflicts. |
| 8-20 engineers | Chi + Platform Team | Platform team provides service template, shared middleware. Chi's minimalism makes template maintenance easy. |
| 20-50 engineers | Chi + Multiple Options | Platform supports Chi (golden path) and `net/http` (simple services). Gin/Echo require explicit approval. |
| 50+ engineers | Multi-framework with Governance | Chi as default. Gin/Echo/Fiber for specific use cases. Governance via ADR process. Platform team maintains middleware in stdlib-compatible form. |

---

## Anti-Patterns for Each Framework

### Chi Anti-Patterns
- **Using Chi for every service regardless of complexity.** A 3-endpoint internal tool doesn't need Chi — use `net/http`.
- **Over-nesting `Route` and `Mount`.** Deeply nested subrouters (>3 levels) are hard to trace. Flatten the route tree.
- **Writing framework logic in handlers.** Handlers should delegate to services. Handlers are HTTP adapters, not business logic.
- **Not setting server timeouts.** Chi doesn't set `ReadTimeout`, `WriteTimeout`, `IdleTimeout` — you must configure these on `http.Server`.
- **Using `chi.URLParam` in business logic.** URL params are HTTP concepts. Extract them in the handler, pass as domain types to the service layer.

### Gin Anti-Patterns
- **Overusing `gin.Context` methods.** `c.Set()`/`c.Get()` create invisible dependencies between middleware and handlers. Use `context.Context` with typed keys instead.
- **Treating `c.Abort()` as a control flow mechanism.** Deeply nested middleware with abort logic becomes impossible to reason about.
- **Using Gin's binding for complex validation.** `c.ShouldBindJSON()` is fine for simple structs. For domain validation (business rules), validate in the service layer.
- **Returning errors via `c.JSON(500, ...)` without logging.** Gin swallows errors silently if not explicitly logged.
- **Migrating from another framework by wrapping with `gin.WrapH()` everywhere.** If you're wrapping every handler, you should have stayed with Chi.

### Echo Anti-Patterns
- **Treating middleware as context mutators.** Adding `c.Set("tenant", ...)` in middleware and reading in handlers couples middleware implementation to handler expectations.
- **Deep middleware nesting with `c.Next()`.** Similar to Gin — control flow through `c.Next()` is hard to debug.
- **Not setting `Echo.HideBanner` and `Echo.HidePort` in production.** Noisy startup output pollutes log aggregation.

### Fiber Anti-Patterns
- **Using Fiber for services that need `net/http` middleware.** If you need `otelhttp`, `promhttp`, `httptest`, etc., Fiber is the wrong choice.
- **Porting Express.js patterns directly.** Fiber's API mimics Express, but Go's concurrency model is different. Express patterns (callback-based, single-threaded event loop) don't translate well.
- **Assuming `fasthttp` performance benefits carry to real workloads.** `fasthttp` is fast at parsing HTTP. If your handler takes 20ms, the parsing overhead is irrelevant.

### net/http Anti-Patterns
- **Rebuilding Chi poorly.** If you find yourself writing a route param extractor, middleware chain, or router group — use Chi. Don't rebuild framework features in `main.go`.
- **Using default `http.DefaultServeMux`.** It's a global, package-level variable. Any package can register routes on it. Use a local `http.NewServeMux()`.
- **Not setting server timeouts.** `http.Server` has no default timeouts. Production services MUST set `ReadTimeout`, `WriteTimeout`, `IdleTimeout`.

---

## Decision Flowchart

```
                          Need an HTTP router for a Go service
                                      │
                                      ▼
                         ┌─────────────────────────┐
                         │ Are you building a       │
                         │ specialized edge proxy   │
                         │ (>500K QPS, <1ms p99)?   │
                         └────────────┬────────────┘
                                      │
                              ┌───────┴───────┐
                             YES               NO
                              │                 │
                              ▼                 ▼
                    ┌─────────────────┐  ┌─────────────────┐
                    │ Can you tolerate │  │ Is this a       │
                    │ fasthttp's       │  │ long-lived      │
                    │ ecosystem        │  │ service (>1yr)? │
                    │ limitations?     │  └────────┬────────┘
                    └────────┬────────┘           │
                             │             ┌──────┴──────┐
                      ┌──────┴──────┐     YES           NO
                     YES           NO      │             │
                      │             │      ▼             ▼
                      ▼             ▼  ┌──────────┐  ┌──────────┐
                  ┌───────┐   ┌─────────┐│Need route│  │Do you need│
                  │ Fiber │   │ Custom  ││params,   │  │rapid proto│
                  │       │   │ fasthttp││groups,   │  │typing with│
                  └───────┘   │ or Rust ││subrouters│  │junior devs│
                              │ (Nginx, ││?         │  │?         │
                              │ Envoy)  │└────┬─────┘  └─────┬─────┘
                              └─────────┘     │              │
                                       ┌──────┴──────┐  ┌───┴───┐
                                      YES           NO  YES     NO
                                       │             │   │       │
                                       ▼             ▼   ▼       ▼
                                  ┌─────────┐  ┌─────────┐┌───┐┌─────────┐
                                  │   Chi   │  │net/http ││Gin││Team has │
                                  │         │  │(Go 1.22+)││or ││strong Go│
                                  │ Is stdlib│  │         ││Echo││idiomatic│
                                  │ compat  │  │ Add Chi │└───┘│preference│
                                  │ critical│  │ only when│     │?        │
                                  │ for your │  │ needed   │     └────┬────┘
                                  │ platform?│  └─────────┘          │
                                  └────┬─────┘                ┌──────┴──────┐
                                       │                     YES           NO
                                ┌──────┴──────┐               │             │
                               YES           NO               ▼             ▼
                                │             │          ┌─────────┐  ┌─────────┐
                                ▼             ▼          │   Chi   │  │   Gin   │
                           ┌─────────┐  ┌─────────┐     │         │  │  or     │
                           │   Chi   │  │net/http │     │ stdlib  │  │  Echo   │
                           │ (default│  │         │     │ compat  │  │ (team   │
                           │  golden │  │ Is your │     │ future  │  │prefers) │
                           │  path)  │  │ routing │     │ proof   │  └─────────┘
                           └─────────┘  │ simple? │     └─────────┘
                                        │(<10     │
                                        │routes)? │
                                        └────┬────┘
                                             │
                                      ┌──────┴──────┐
                                     YES           NO
                                      │             │
                                      ▼             ▼
                                 ┌─────────┐  ┌─────────┐
                                 │net/http │  │   Chi   │
                                 │         │  │  (you   │
                                 │ stdlib  │  │  need   │
                                 │ only    │  │  groups)│
                                 └─────────┘  └─────────┘
```

---

## Summary Recommendations

### Default Choice: Chi

For most Go services, Chi is the correct default:
- stdlib compatible (handlers are `http.Handler`, middleware is `func(http.Handler) http.Handler`)
- Minimal dependency footprint (zero external dependencies beyond stdlib)
- Small enough to read and understand completely (~3K lines)
- Provides the routing primitives missing from stdlib (route groups, subrouters, per-route middleware, URL params)
- Stable API across 5 major versions

### When to Use net/http Only

- Service has <10 routes
- All routes are static (no URL parameters needed)
- No middleware beyond a simple logging wrapper
- The team has Go expertise and prefers minimal dependencies
- Go 1.22+ (for pattern matching in ServeMux)

### When to Use Gin

- Team is mostly junior developers or developers new to Go (Gin's `c.JSON()`, `c.ShouldBindJSON()` reduce boilerplate)
- Rapid prototyping where speed to market matters more than long-term maintainability
- Team prefers convention-over-configuration style (similar to Express.js, Flask, Spring)

### When to Use Echo

- Need a middle ground between Gin's magic and Chi's minimalism
- Want built-in middleware without Gin's coupling
- Single developer or small team (bus factor concern)
- Need WebSocket support built into the framework

### When to Use Fiber

- Building an edge proxy, API gateway, or reverse proxy where performance is the primary concern
- Building a service that does minimal processing (header manipulation, routing, lightweight auth)
- You control the entire stack (no third-party `net/http` middleware dependencies)
- You accept the ecosystem trade-off and are prepared to build/maintain Fiber-compatible versions of needed middleware

### Never: gorilla/mux

Archived. Do not use for new services. Migrate existing services off gorilla/mux.

---

## Framework Migration Complexity

| From → To | Chi | Gin | Echo | Fiber | net/http |
|-----------|-----|-----|------|-------|----------|
| **Chi** | — | Medium (handler sigs, gin.Context) | Easy (both stdlib compat) | Hard (fasthttp) | Easy (same interface) |
| **Gin** | Medium (remove gin.Context) | — | Medium (both framework-specific) | Hard | Medium (adapt handlers) |
| **Echo** | Easy (both stdlib compat) | Medium | — | Hard | Easy |
| **Fiber** | Hard (different request model) | Hard | Hard | — | Hard |
| **net/http** | Trivial (add Chi context) | Medium (add gin.Context) | Easy | Hard | — |
| **gorilla/mux** | Easy (both stdlib compat) | Easy (mux.Vars→c.Param) | Easy | Hard | Easy |

---

## ADR Template

When writing a framework selection ADR, include this matrix as an appendix:

```markdown
# ADR-XXX: HTTP Router Selection for [Service/Platform]

## Context
[Describe the project, team, scale, constraints]

## Decision
[Router choice with version]

## Alternatives Considered
- **Chi**: [score], [pros/cons]
- **Gin**: [score], [pros/cons]
- **Echo**: [score], [pros/cons]
- **net/http**: [score], [pros/cons]
- **Fiber**: [score], [pros/cons]

## Weighted Scoring
[Include weighted table for your scenario]

## Consequences
### Positive
[Benefits of the choice]

### Negative
[Downsides, migration complexity, team training needs]

### Mitigations
[How you'll address the negatives]

## Appendix
[Include this matrix as reference data]
```
