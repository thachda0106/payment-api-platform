# Session 26: Framework-Agnostic Mastery

- **Phase**: 6 — Staff Engineering Practices
- **Duration**: 5-6 hours
- **Prerequisites**: Sessions 13-18 (Chi internals), Sessions 22-24 (source code mastery), production experience with at least 2 Go HTTP frameworks
- **Goal**: Develop the ability to evaluate, adopt, or discard any Go HTTP framework based on first principles — not community hype, not personal preference, not resume-driven development.

---

## Why This Topic Exists

Most engineers "know" one framework. Staff/Principal engineers understand frameworks as a *category* — they can evaluate any new framework in hours, articulate trade-offs without religious attachment, and design services that are framework-agnostic at the business logic level.

The Go ecosystem has a unique property that makes framework-agnostic mastery achievable: **every Go HTTP framework ultimately compiles down to `net/http.Handler`**. Unlike Python (where Flask, Django, and FastAPI have completely incompatible request/response models) or Java (where JAX-RS, Spring MVC, and Vert.x are fundamentally different abstractions), Go's standard library is the universal interface.

```
┌─────────────────────────────────────────────────────────┐
│                  net/http.Handler                         │
│         ServeHTTP(ResponseWriter, *Request)               │
│                                                           │
│    The universal interface binding ALL Go HTTP routers    │
└─────────────────────────────────────────────────────────┘
                          ▲
        ┌─────────────────┼─────────────────┐
        │                 │                 │
   ┌────┴────┐      ┌────┴────┐      ┌────┴────┐
   │   Chi   │      │   Gin   │      │  Echo   │
   │ native  │      │ adapter │      │ native  │
   │ http.H. │      │ wraps   │      │ http.H. │
   └─────────┘      └─────────┘      └─────────┘
        │                 │                 │
   ┌────┴────┐      ┌────┴────┐      ┌────┴────┐
   │  Fiber  │      │ gorilla │      │ net/http│
   │ fasthttp│      │  /mux   │      │ (stdlib)│
   │ (NOT    │      │ http.H. │      │ http.H. │
   │  compat)│      └─────────┘      └─────────┘
   └─────────┘
```

This property means:
1. **You can evaluate any framework by how it wraps `net/http`.** Does it implement `http.Handler`? Yes → portable. No → locked in.
2. **You can design framework-agnostic services.** Business logic accepts `io.Reader`/`io.Writer`, not `http.Request`/`http.ResponseWriter`. The HTTP layer is an adapter.
3. **You can switch frameworks in hours, not weeks.** Handlers that are `http.Handler` work in Chi, Gin (with adapter), Echo, gorilla/mux, and stdlib.
4. **Framework knowledge is additive, not siloed.** Understanding Chi's radix tree helps you understand why Gin is faster in some benchmarks (it uses a similar data structure). Understanding Chi's middleware chain helps you understand Echo's — they all compose `func(http.Handler) http.Handler`.

---

## Mental Model

### The Framework Evaluation Matrix

Evaluate any Go HTTP framework across these 10 dimensions:

```
                        ┌── 1. net/http Compatibility ──┐
                        │  Does it implement http.Handler?│
                        │  Can handlers be tested with    │
                        │  net/http/httptest?             │
                        └────────────────────────────────┘

┌── 2. Middleware Model ──┐     ┌── 3. Routing Algorithm ──┐
│ func(http.Handler)       │     │ Radix tree, regex, or    │
│   http.Handler?          │     │ linear scan?             │
│ Composition order?       │     │ Parameter extraction?    │
│ Per-route vs global?     │     │ Conflict resolution?     │
└──────────────────────────┘     └──────────────────────────┘

┌── 4. Dependency Footprint ─┐  ┌── 5. Performance ────────┐
│ Direct + transitive deps?   │  │ QPS at p50/p99?          │
│ Any deprecated/unmaintained?│  │ Allocations per request?  │
│ CGO requirements?           │  │ Memory footprint?         │
└─────────────────────────────┘  └──────────────────────────┘

┌── 6. Learning Curve ──────┐   ┌── 7. Community & Docs ───┐
│ Hours to productive?       │   │ GitHub stars/forks?       │
│ Hours to advanced?         │   │ Issue response time?      │
│ New-team-member ramp-up?   │   │ Release frequency?        │
└────────────────────────────┘   └──────────────────────────┘

┌── 8. Long-term Stability ─┐   ┌── 9. Testability ─────────┐
│ Years of stable releases?  │   │ Can I unit-test handlers? │
│ Breaking change frequency? │   │ Can I integration-test    │
│ Maintainer responsiveness? │   │ the full middleware chain?│
│ Corporate backing?         │   │ httptest compatible?      │
└────────────────────────────┘   └──────────────────────────┘

┌── 10. Go Idiom Alignment ────────────────────────────────┐
│ Does it embrace Go conventions or fight them?             │
│ Error handling: returns error or writes response?         │
│ Context usage: standard context.Context or custom?        │
│ Interfaces: standard library or framework-specific?       │
└──────────────────────────────────────────────────────────┘
```

### The stdlib-First Philosophy

```
                Start here
                    │
                    ▼
          ┌─────────────────────┐
          │    net/http only    │
          │  ServeMux + handlers│
          └──────────┬──────────┘
                     │
              Do I need URL parameters?
              (e.g., /users/{id})
                     │
              ┌──────┴──────┐
             YES            NO
              │              │
              ▼              ▼
     ┌──────────────┐  ┌──────────────┐
     │ Add Chi      │  │ Stay with    │
     │ (stdlib      │  │ net/http     │
     │  compat)     │  │ + Go 1.22    │
     └──────┬───────┘  │ pattern mux  │
            │          └──────────────┘
            ▼
    ┌──────────────┐
    │ Need more?   │
    │ (subrouters, │
    │  per-route   │
    │  middleware) │
    └──────┬───────┘
           │
     ┌─────┴─────┐
    YES         NO
     │            │
     ▼            ▼
  ┌────────┐  ┌────────┐
  │ Chi    │  │ Stay   │
  │ Mount  │  │ with   │
  │ Route  │  │ basic  │
  │ Group  │  │ Chi    │
  └────────┘  └────────┘
```

The principle: add complexity only when the standard library (plus Go 1.22+ `net/http` pattern matching) cannot meet your requirements. Chi is not the first tool you reach for — it's the tool you reach for when `net/http.ServeMux` cannot express your routing needs.

---

## Internal Architecture

### Common Patterns Across All Go HTTP Routers

Every Go HTTP router, regardless of its specific implementation, must solve these problems:

#### 1. Route Registration

```go
// Pattern: Register a handler for a method + path pair
// Every router has this — the syntax varies

// net/http (Go 1.22+)
mux.HandleFunc("GET /users/{id}", handler)

// Chi
r.Get("/users/{id}", handler)

// Gin
r.GET("/users/:id", handler)

// Echo
e.GET("/users/:id", handler)

// gorilla/mux
r.HandleFunc("/users/{id}", handler).Methods("GET")
```

The underlying data structure varies:
- **Chi**: Compressed radix tree (`tree.go`)
- **Gin**: Radix tree (similar, independent implementation in `gin/tree.go`)
- **Echo**: Radix tree (independent implementation)
- **gorilla/mux**: Route list with regex matching (linear scan for conflicts at registration, linear scan for matches at runtime)
- **net/http (Go 1.22+)**: Pattern-based matching (new implementation, not a tree)

#### 2. Middleware Composition

```go
// Pattern: Wrap a handler with cross-cutting behavior
// All routers implement this — the type signature is the differentiator

// stdlib-compatible (Chi, gorilla/mux):
// func(http.Handler) http.Handler
func loggingMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        log.Println("request:", r.URL.Path)
        next.ServeHTTP(w, r)
    })
}

// Framework-specific (Gin, Echo):
// Gin: gin.HandlerFunc
// Echo: echo.MiddlewareFunc
func loggingMiddleware() gin.HandlerFunc {
    return func(c *gin.Context) {
        log.Println("request:", c.Request.URL.Path)
        c.Next()
    }
}
```

The `func(http.Handler) http.Handler` signature is the universal middleware type. Chi and gorilla/mux use it natively. Gin and Echo middleware can be adapted to it (Gin provides `gin.WrapH()` and `gin.WrapF()`). This is why you should write middleware in the stdlib-compatible form — it's portable across all routers.

#### 3. Context Propagation

```go
// Pattern: Pass request-scoped data through the handler chain
// All routers do this — the mechanism varies

// stdlib-compatible: context.WithValue on r.Context()
ctx := context.WithValue(r.Context(), key, value)
r = r.WithContext(ctx)

// Gin: gin.Context has Set/Get methods
c.Set("key", value)

// Echo: echo.Context has Set/Get methods
c.Set("key", value)
```

The `context.Context` approach is universally compatible. Gin/Echo's `Set`/`Get` are convenient but lock you into their context type. Prefer `context.WithValue` for framework-agnostic code.

#### 4. Route Parameter Extraction

```go
// Pattern: Extract URL parameters from the matched route
// Each router has its own extraction mechanism

// Chi: chi.URLParam(r, "id")
id := chi.URLParam(r, "id")

// Gin: c.Param("id")
id := c.Param("id")

// Echo: c.Param("id")
id := c.Param("id")

// gorilla/mux: mux.Vars(r)["id"]
id := mux.Vars(r)["id"]

// net/http (Go 1.22+): r.PathValue("id")
id := r.PathValue("id")
```

This is the most common lock-in point. `chi.URLParam()` is a thin wrapper around `context.Context` lookup. Gin/Echo's `Param()` is coupled to their context types. Go 1.22's `r.PathValue()` is a step toward standardization.

### How Chi Achieves Framework-Agnostic Compatibility

Chi's design makes three critical choices that maximize portability:

```go
// 1. Chi.Mux implements http.Handler
//    You can use a chi.Router anywhere an http.Handler is expected
var _ http.Handler = chi.NewRouter()

// 2. Chi handlers are plain http.HandlerFunc
//    No chi.HandlerFunc, no chi.Context — just standard types
r.Get("/users/{id}", func(w http.ResponseWriter, r *http.Request) {
    id := chi.URLParam(r, "id")  // only Chi-specific call
    // Everything else is standard Go
    json.NewEncoder(w).Encode(user)
})

// 3. Chi middleware is func(http.Handler) http.Handler
//    You can use any stdlib-compatible middleware with Chi,
//    and Chi middleware works with any stdlib-compatible router
func myMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        // Standard http.ResponseWriter and *http.Request
        next.ServeHTTP(w, r)
    })
}
```

---

## Runtime Behavior

### Benchmarking Go HTTP Routers: What the Numbers Actually Mean

Benchmark numbers without context are misleading. Here's what matters:

```bash
# Run a representative benchmark (not the trivial "hello world")
go test -bench=. -benchmem -benchtime=10s ./benchmarks/

# Output interpretation:
# BenchmarkChi/StaticRoute-16     5000000    250 ns/op    0 allocs/op
#   - 5000000 iterations in 10 seconds
#   - 250ns per operation (route lookup + handler execution)
#   - 0 allocations per operation (no heap allocation in hot path)

# BenchmarkChi/ParameterizedRoute-16  3000000  400 ns/op    32 B/op  1 allocs/op
#   - 400ns per operation
#   - 32 bytes allocated (parameter extraction allocates)
#   - 1 allocation (the string for the parameter value)
```

**What matters for your decision:**

| Metric | Why It Matters | What's "Good Enough" |
|--------|---------------|---------------------|
| ns/op (route lookup) | Routing overhead above handler time | <500ns — any modern router achieves this |
| B/op (allocations) | GC pressure at high QPS | <100B — 0 is ideal for static routes |
| allocs/op | GC pause frequency | 0-2 — more allocs = more frequent GC |
| Router memory | Baseline memory for routing table | <5MB for 10K routes — Chi achieves ~2MB |

**The benchmark trap**: The difference between Chi (250ns) and Gin (200ns) is 50 nanoseconds. At 100K QPS, that's 5 milliseconds of total overhead difference. Your database call takes 20 milliseconds. The router overhead is 0.00025% of your request time. Any modern Go router is "fast enough" — the decision should be based on other criteria.

### Cross-Framework Performance Comparison

```
Framework        ns/op (static)   ns/op (param)   allocs/op   net/http compat?
──────────────────────────────────────────────────────────────────────────────
net/http (1.22)   180              350              1              YES (native)
Chi               250              400              1              YES (native)
Gin               200              350              0 (static)    Adapter needed
Echo              210              360              0 (static)    YES (native)
Fiber             150*             280*             0              NO (fasthttp)
gorilla/mux       1,200            1,800            3-5            YES (native)

*Fiber uses fasthttp, not net/http — not comparable. Different request/response
 model, different concurrency model, different Connect middleware ecosystem.
```

**Key insight**: Any router doing 200-400ns per lookup is fast enough for 99.9% of use cases. The 50ns difference between Chi and Gin translates to 0.00005ms per request. At 1M QPS, that's 50ms of total CPU time per second. If your service can't handle 50ms of routing overhead per second at 1M QPS, you have a very different problem than framework choice.

---

## Flow Diagrams

### Request Flow: Framework-Agnostic Design

```
┌──────────────────────────────────────────────────────────────────────┐
│                        INCOMING HTTP REQUEST                          │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    HTTP ADAPTER (1 file, swappable)                   │
│                                                                       │
│  func NewHTTPAdapter(service Service) http.Handler {                  │
│      r := chi.NewRouter()  // ← ONLY chi import in entire codebase    │
│      r.Use(chi.middleware.Logger)                                     │
│      r.Use(chi.middleware.Recoverer)                                  │
│      r.Get("/users/{id}", adapt(service.GetUser))                     │
│      return r                                                         │
│  }                                                                    │
│                                                                       │
│  func adapt(fn func(context.Context, Request) (Response, error))      │
│      http.HandlerFunc {                                               │
│      return func(w http.ResponseWriter, r *http.Request) {            │
│          // Parse URL params, query params, body into Request struct  │
│          // Call fn(ctx, req)                                         │
│          // Write Response to http.ResponseWriter                     │
│      }                                                                │
│  }                                                                    │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    SERVICE LAYER (framework-agnostic)                  │
│                                                                       │
│  type Service struct {                                                │
│      db     *sql.DB                                                   │
│      cache  Cache                                                     │
│      events EventPublisher                                            │
│  }                                                                    │
│                                                                       │
│  func (s *Service) GetUser(ctx context.Context, req Request)          │
│      (Response, error) {                                              │
│      // Pure business logic — no HTTP concepts                        │
│      user, err := s.repo.FindByID(ctx, req.UserID)                    │
│      if err != nil {                                                  │
│          return Response{}, fmt.Errorf("get user: %w", err)           │
│      }                                                                │
│      return Response{User: user}, nil                                 │
│  }                                                                    │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    DOMAIN LAYER (pure Go, testable in isolation)      │
│                                                                       │
│  type UserRepository interface {                                      │
│      FindByID(ctx context.Context, id string) (User, error)           │
│  }                                                                    │
│                                                                       │
│  type PostgresUserRepo struct {                                       │
│      db *sql.DB                                                       │
│  }                                                                    │
│                                                                       │
│  func (r *PostgresUserRepo) FindByID(ctx context.Context, id string)  │
│      (User, error) {                                                  │
│      // SQL logic — no HTTP, no framework                             │
│  }                                                                    │
└──────────────────────────────────────────────────────────────────────┘
```

Key property: The Service and Domain layers have zero imports from any HTTP framework. They accept `context.Context`, `io.Reader`, and standard library types. The HTTP Adapter is the only file that imports Chi — and it can be swapped to Gin or Echo in under an hour.

### The Framework Evaluation Flow

```
  New framework discovered
           │
           ▼
  ┌────────────────────────┐
  │ Is it net/http compat? │
  │ (Does handler implement│
  │  http.Handler?)        │
  └───────────┬────────────┘
              │
       ┌──────┴──────┐
      YES            NO
       │              │
       ▼              ▼
  ┌────────────┐  ┌────────────────────┐
  │ Green zone │  │ RED FLAG            │
  │ Portable   │  │ Why doesn't it?     │
  │ Low risk   │  │ What does it give   │
  └──────┬─────┘  │ in exchange for     │
         │        │ lock-in? (Fiber:    │
         │        │ fasthttp has 10x    │
         │        │ performance but     │
         ▼        │ incompatible model) │
  ┌────────────┐  └────────────────────┘
  │ 10-point   │
  │ evaluation │
  │ (see below)│
  └──────┬─────┘
         │
         ▼
  ┌────────────────┐
  │  Score > 7 on  │
  │  all criteria? │
  └──────┬─────────┘
         │
    ┌────┴────┐
   YES       NO
    │         │
    ▼         ▼
  ┌─────┐  ┌──────────┐
  │ Adopt│  │Identify  │
  │ for  │  │gaps. Are │
  │ eval │  │they fix- │
  │      │  │able? RTO │
  └─────┘  │(return on│
           │time) for  │
           │addressing│
           │each gap? │
           └──────────┘
```

---

## Source Code Reading Guide

### Key Files for Cross-Framework Understanding

| Framework | File | Lines | What to Study |
|-----------|------|-------|--------------|
| **net/http** | `server.go` | ~800 | Server.Serve, conn.serve — the base that all routers build on |
| **net/http** | `server.go:Handler` | Interface | The 3-method interface: `ServeHTTP(ResponseWriter, *Request)` |
| **net/http** | `pattern.go` (1.22+) | ~600 | New ServeMux pattern matching, route conflicts |
| **Chi** | `mux.go:1-200` | 200 | Mux struct, how Chi wraps http.Handler |
| **Chi** | `tree.go:1-400` | 400 | Radix tree implementation |
| **Chi** | `middleware.go:1-150` | 150 | Middleware chain composition |
| **Gin** | `gin.go:1-200` | 200 | Engine struct, how Gin wraps net/http |
| **Gin** | `tree.go:1-300` | 300 | Gin's radix tree (compare to Chi's) |
| **Gin** | `context.go:1-200` | 200 | gin.Context — the lock-in point |
| **Echo** | `echo.go:1-200` | 200 | Echo struct, router setup |
| **Echo** | `router.go:1-300` | 300 | Echo's radix tree |
| **Fiber** | `app.go` | ~500 | Fiber App — fasthttp-based, NOT net/http |

### How to Learn Any New Go Framework in 2 Hours

```
Hour 1: Surface
├── 10 min: README, quickstart example
├── 10 min: Find the http.Handler implementation (if any)
├── 10 min: Find the middleware type signature
├── 10 min: Find the router registration API
├── 10 min: Find how URL parameters are extracted
└── 10 min: Check dependency graph (go mod graph)

Hour 2: Depth
├── 15 min: Read the router implementation (tree/radix/pattern matching)
├── 15 min: Trace one request from accept to response (use go doc + source links)
├── 15 min: Check test files — they reveal expected usage patterns
├── 10 min: Read middleware examples (logging, auth, recovery)
└── 5 min: Check issues/PRs for maintenance health indicators
```

After this 2-hour session, you should be able to:
1. Write a complete service using the framework
2. Identify its strengths and weaknesses compared to Chi
3. Articulate its lock-in characteristics
4. Make a recommendation to your team

---

## Production Failure Scenarios

### Scenario 1: The Framework Migration Failure

**Situation**: A team migrated from Chi to Gin because "Gin is 20% faster in benchmarks." After 3 months of migration:
- 40% of endpoints rewritten (Gin requires different handler signatures)
- Custom middleware rewritten (no longer stdlib-compatible)
- Test suite partially broken (`gin.CreateTestContext` vs `httptest.NewRecorder`)
- Migration abandoned — service runs Gin for new endpoints, Chi for old ones

**Root Cause**: Decision based on benchmark numbers without considering migration cost and ongoing maintenance.

**Lesson**: Framework choice is a 5-year decision. The 50ns performance difference between Chi and Gin is irrelevant next to the cost of maintaining two frameworks in the same service or the cost of rewriting handlers.

### Scenario 2: The fasthttp Trap

**Situation**: A team adopted Fiber for a new Go service because benchmarks showed "10x faster than Chi." Six months later:
- Needed a WebSocket library → ecosystem was net/http-based, incompatible
- Needed OpenTelemetry middleware → had to build custom fasthttp adapter
- New team members struggled: Fiber's context model differed from everything they knew
- Debugging: `net/http/pprof` incompatible with fasthttp (different request model)

**Root Cause**: Fiber/fasthttp is fast because it abandons the `net/http` interface. It optimizes by having a different memory model (zero-allocation request parsing). The speed comes at the cost of ecosystem compatibility.

**Lesson**: Fiber is the right choice when you control the entire stack and performance is the singular goal (e.g., an edge proxy serving millions of QPS). It is the wrong choice for a general-purpose API service that needs the Go ecosystem.

### Scenario 3: Framework Abandonment

**Situation**: A team built 12 services on gorilla/mux. In December 2022, gorilla/mux was archived by its maintainer. The team now faces:
- No security patches
- No Go version compatibility updates
- No community support for bugs
- Migration to another router required — estimate: 2 weeks per service

**Root Cause**: No framework sustainability assessment at adoption time.

**Lesson**: Always evaluate framework sustainability:
1. Number of maintainers (>2 preferred)
2. Corporate backing (Google for Chi via Pressly, Google for Gin, no corporate backing for gorilla/mux)
3. Release frequency (<6 months between releases is healthy, >1 year is a warning)
4. Bus factor (what happens if the sole maintainer leaves?)

---

## Debugging Techniques

### Framework Identification in Production

```bash
# 1. Check go.mod for router dependency
grep -E "chi|gin|echo|fiber|gorilla" go.mod

# 2. Check binary for framework symbols
go tool nm ./service | grep -i "chi\|gin\|echo\|fiber"

# 3. Check pprof for framework allocation patterns
curl http://localhost:6060/debug/pprof/allocs?debug=1 | grep -i "chi\|gin\|echo"

# 4. Check HTTP response headers for framework fingerprints
curl -v http://localhost:8080/ 2>&1 | grep -i "server\|x-powered"
```

### Portability Audit

```bash
# Find all framework-specific imports
rg "github.com/go-chi/chi" --type go
rg "github.com/gin-gonic/gin" --type go
rg "github.com/labstack/echo" --type go

# Find all http.Handler usage vs framework-specific handler types
rg "http\.Handler[^F]" --type go     # portable
rg "gin\.HandlerFunc" --type go       # locked to Gin
rg "echo\.HandlerFunc" --type go      # locked to Echo

# Count: if gin.HandlerFunc > 20, migration cost is high
```

---

## Observability Considerations

### Framework-Agnostic Observability

```go
// Framework-agnostic tracing (OpenTelemetry — works with any router)
import "go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

// otelhttp.NewHandler wraps any http.Handler (works with Chi, Gin, Echo, stdlib)
r := chi.NewRouter()
r.Use(func(next http.Handler) http.Handler {
    return otelhttp.NewHandler(next, "service-name")
})

// Framework-agnostic metrics (Prometheus — works with any router)
import "github.com/prometheus/client_golang/prometheus/promhttp"

r.Handle("/metrics", promhttp.Handler())
```

Key principle: Observability instruments should target `net/http.Handler`, not framework-specific types. This makes your observability stack portable across routers.

---

## Performance Implications

### Framework Overhead Reality Check

| Concern | Reality |
|---------|---------|
| "Chi is slower than Gin" | 50ns difference per route lookup. Database call is 20,000,000ns. Framework overhead is 0.00025% of request time. |
| "Fiber is 10x faster" | Compared to gin/echo/chi at trivial routes. For real handlers with DB, the difference is <5%. Fast due to different concurrency model (fasthttp), not better engineering. |
| "stdlib is fastest" | True for trivial routes. False for complex routing — stdlib ServeMux pre-1.22 doesn't handle path params. Post-1.22 pattern matching is competitive. |
| "More middleware = slower" | Each middleware adds ~1μs. At 5 middleware layers, that's 5μs. Your database call is 20,000μs. Write the middleware you need. |
| "JSON library choice matters" | Yes. The difference between `encoding/json` and `json-iterator/go` can be 3-5x. Much more impactful than router choice. |
| "Context.Value is slow" | ~100ns per lookup. Chi uses it for URL params. If you're doing 100 context value lookups per request (you shouldn't be), that's still only 10μs. |

---

## Architecture Implications

### Framework-Agnostic Service Design

```go
// framework_agnostic.go — ZERO framework imports
package service

import (
    "context"
    "io"
)

type CreateOrderRequest struct {
    UserID  string
    Items   []OrderItem
    Coupon  string
}

type CreateOrderResponse struct {
    OrderID string
    Status  string
    Total   float64
}

type OrderService struct {
    repo    OrderRepository
    payment PaymentGateway
    coupon  CouponValidator
}

func (s *OrderService) CreateOrder(ctx context.Context, req CreateOrderRequest) (CreateOrderResponse, error) {
    // PURE BUSINESS LOGIC
    // - No HTTP concepts (no http.Request, no http.ResponseWriter)
    // - No framework imports
    // - Testable with standard Go testing tools
    // - Can be called from HTTP, gRPC, CLI, Lambda, Kafka consumer

    if err := s.validateItems(ctx, req.Items); err != nil {
        return CreateOrderResponse{}, fmt.Errorf("validate items: %w", err)
    }

    if req.Coupon != "" {
        discount, err := s.coupon.Validate(ctx, req.Coupon)
        if err != nil {
            return CreateOrderResponse{}, fmt.Errorf("validate coupon: %w", err)
        }
        // apply discount
    }

    total := s.calculateTotal(req.Items)
    paymentID, err := s.payment.Charge(ctx, req.UserID, total)
    if err != nil {
        return CreateOrderResponse{}, fmt.Errorf("charge payment: %w", err)
    }

    order, err := s.repo.Create(ctx, Order{
        UserID:    req.UserID,
        Items:     req.Items,
        Total:     total,
        PaymentID: paymentID,
    })
    if err != nil {
        return CreateOrderResponse{}, fmt.Errorf("create order: %w", err)
    }

    return CreateOrderResponse{
        OrderID: order.ID,
        Status:  "confirmed",
        Total:   total,
    }, nil
}
```

The HTTP adapter (framework-specific, swappable):

```go
// http_adapter.go — the ONLY file importing a framework
package httpadapter

import (
    "net/http"

    "github.com/go-chi/chi/v5"
    "github.com/example/service"
)

type Adapter struct {
    svc *service.OrderService
}

func NewAdapter(svc *service.OrderService) http.Handler {
    a := &Adapter{svc: svc}
    r := chi.NewRouter()
    r.Post("/api/orders", a.handleCreateOrder)
    return r
}

func (a *Adapter) handleCreateOrder(w http.ResponseWriter, r *http.Request) {
    var req service.CreateOrderRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        http.Error(w, "invalid request", http.StatusBadRequest)
        return
    }

    resp, err := a.svc.CreateOrder(r.Context(), req)
    if err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
        return
    }

    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusCreated)
    json.NewEncoder(w).Encode(resp)
}
```

**To switch from Chi to Gin**, you change exactly one file — `http_adapter.go`. The service layer is untouched. This is what framework-agnostic mastery looks like in code.

---

## Team Ownership Implications

### Framework Choice and Team Composition

| Team Profile | Recommended Router | Why |
|-------------|-------------------|-----|
| Junior-heavy team, rapid prototyping | Gin or Echo | More "magic" (binding, validation, rendering built-in), faster onboarding for non-Go developers |
| Senior-heavy team, long-lived services | Chi | stdlib compatibility, minimal magic, explicit design, easier to reason about |
| Mixed team, platform engineering focus | Chi + internal abstractions | Write framework-agnostic services on a Chi-based platform |
| Edge proxy, extreme performance | Fiber (or build custom) | fasthttp performance justified by use case |
| Minimal dependencies, long-term stability | net/http (Go 1.22+) | Zero external dependencies, guaranteed maintenance |

### The Go Philosophy vs Framework Philosophy

| Aspect | Go Philosophy | Framework Philosophy (Rails/Django/Spring) |
|--------|--------------|-------------------------------------------|
| **Design principle** | Composition over inheritance | Convention over configuration |
| **Developer experience** | Explicit, verbose, predictable | Implicit, concise, magical |
| **Error handling** | Returns errors, explicit handling | Exceptions, try-catch, rescue blocks |
| **Dependency injection** | Manual (pass deps as args) or `wire` | Automatic (annotation-driven, container-managed) |
| **Configuration** | Structs with environment variables | YAML/XML with annotation overrides |
| **Learning curve** | Gentle start, steep mastery | Steep start, plateau in middle |
| **Debugging** | Follow the code — no hidden control flow | Debug annotation processing, proxy chains, bytecode manipulation |
| **Upgrade risk** | Low — Go 1.x compatibility guarantee | High — Spring Boot 2→3 migration took months |

Chi sits at the sweet spot of this spectrum: it provides the routing and middleware primitives that Go's stdlib lacks, but imposes no opinion on how you structure your application. Gin and Echo lean more toward "convention over configuration" with their built-in binding, validation, and rendering.

---

## Interview Questions

### Question 1: Framework Recommendation

**Q**: We're building a new API platform with 50 planned services over the next 3 years. Our team is 60% experienced Go developers, 40% new to Go (coming from Java/Node.js). Which router should we choose, and why?

**A**: Recommend Chi for this scenario. The decision framework:

1. **stdlib compatibility** (most important criterion for 50 services): Chi handlers are `http.Handler` — portable, testable with `httptest`, no lock-in. This matters most when you have 50 services and need consistency.

2. **Team composition**: The 60% experienced Go developers will prefer Chi's explicit, minimal-magic approach. The 40% Java developers will initially prefer Gin (feels like Spring annotations), but after 3 months of Go, they'll appreciate Chi's simplicity. Chi's learning curve is shallow — productive in 1 day.

3. **Long-term stability**: Chi has been stable for 5+ years, is maintained by Pressly (a company that uses it in production), and has a small, clean codebase (~3K lines). The bus factor is low, but the code is small enough that any experienced Go developer could maintain it.

4. **Platform compatibility**: For 50 services, you'll build internal middleware libraries, shared observability, and a service template. Chi's `func(http.Handler) http.Handler` middleware signature means your internal middleware works with any stdlib-compatible router — future-proofing your platform investment.

5. **Performance**: Chi's routing overhead (~250ns) is irrelevant compared to database calls (~20ms). The 50ns advantage of Gin over Chi amounts to 0.00025% latency difference.

**What NOT to do**: Choose Fiber. The performance gain is real but the ecosystem incompatibility (fasthttp, not net/http) means every middleware, every monitoring tool, every library must be Fiber-compatible. For 50 services over 3 years, this lock-in risk is unacceptable.

### Question 2: Framework Migration Strategy

**Q**: You inherit a codebase with 15 services: 8 using Chi, 4 using Gin, 3 using gorilla/mux (archived). The CTO wants to standardize on one framework. What's your recommendation?

**A**: Standardize on Chi, with a phased migration:

**Phase 1: gorilla/mux → Chi (months 1-2)**
- gorilla/mux is archived — this is the highest-risk group
- Both are stdlib-compatible, so handler migration is straightforward
- Replace `mux.Vars(r)["id"]` with `chi.URLParam(r, "id")`
- Replace `r.HandleFunc()` with `r.Get()`/`r.Post()`
- Estimated: 3-5 days per service

**Phase 2: Gin → Chi (months 3-6)**
- Larger effort due to `gin.Context` → `http.ResponseWriter`/`*http.Request` conversion
- Use `gin.WrapH()` as an intermediate step to convert Chi-style handlers to Gin-compatible
- Extract business logic from `gin.HandlerFunc` to framework-agnostic functions first
- Estimated: 1-2 weeks per service

**Phase 3: Consolidation (months 7-8)**
- Shared middleware library (Chi-compatible, stdlib-signature)
- Standardized observability across all services
- Service template for new services

**Phase 4: Framework-Agnostic Architecture (ongoing)**
- All new services follow the framework-agnostic pattern (business logic in separate package, HTTP adapter only imports Chi)
- Future framework changes require changing only the HTTP adapter

### Question 3: Framework Evaluation for New Hire

**Q**: A new Staff engineer joins and says "We should switch to Echo — it has better performance benchmarks and a larger community." How do you respond?

**A**: 

1. **Acknowledge**: Echo is a solid framework. It's stdlib-compatible, has good performance, and an active community. The suggestion is reasonable.

2. **Evaluate the claim**: "Better performance benchmarks" — show the real numbers. For our workload (CRUD with 20ms DB calls), the 40ns routing difference between Chi and Echo is 0.0002% of request time. Not a meaningful difference.

3. **Evaluate the cost**: Switching 15 services from Chi to Echo would cost:
   - Handler migration: ~2 days per service (both stdlib-compatible, relatively easy) = 30 engineer-days
   - Middleware migration: ~3 days (shared middleware library) = 3 engineer-days
   - Testing: ~5 days across all services = 5 engineer-days
   - Risk: potential production issues during migration = hard to quantify
   - Total: ~38 engineer-days = ~$50K

4. **Evaluate the benefit**: Zero measurable performance improvement. Slightly different API (`c.Param()` vs `chi.URLParam()`). No net-new capability.

5. **Recommendation**: No migration. The cost ($50K + risk) has no corresponding benefit. If Echo has a specific capability we need (e.g., built-in WebSocket support that we're currently building manually), evaluate adopting just for services that need it — not a wholesale migration.

6. **Process**: Propose writing an ADR comparing Chi vs Echo for our specific use case, with concrete benchmarks from our actual services. Use data, not preferences.

### Question 4: The stdlib-Only Argument

**Q**: "Go 1.22 added pattern matching to `net/http.ServeMux`. We should use only the standard library from now on — no Chi, no frameworks." Do you agree?

**A**: Evaluate based on routing requirements. Go 1.22 pattern matching handles:
- Static routes: `GET /users`
- Path parameters: `GET /users/{id}`
- Wildcards: `GET /files/{path...}`
- Method-based routing: `GET /users/{id}`
- Conflict detection at registration time

What it does NOT handle:
- Middleware composition (no `r.Use()` equivalent)
- Per-route middleware (no `r.With(middleware).Get(...)` equivalent)
- Route grouping (no `r.Route("/api/v1", ...)` equivalent)
- Subrouter mounting (no `r.Mount("/admin", adminRouter)` equivalent)
- Route-specific timeouts
- Built-in middleware (logger, recoverer, request ID, etc.)

**Decision**: If your service has <10 routes, no middleware beyond request logging, and is maintained by a small team — use `net/http` directly. The simplicity is valuable.

For services with 20+ routes, multiple middleware layers, route groups with different auth requirements, or subrouter patterns — use Chi. The code organization benefits (route groups, middleware composition) outweigh the external dependency cost.

The key insight: Go 1.22's `ServeMux` improvement reduces the gap between stdlib and Chi, but it doesn't close it for non-trivial services. Chi's value is not just in route matching — it's in the middleware composition, route grouping, and subrouter mounting that `ServeMux` doesn't provide.

### Question 5: Framework Lock-In Assessment

**Q**: How would you assess the lock-in risk of our current 20-service codebase? What metrics would you use?

**A**: Conduct a framework coupling audit:

```bash
# 1. Count framework-specific imports per service
for svc in services/*/; do
  chi_count=$(rg "chi\.\w+\(" $svc --count --type go | awk -F: '{sum+=$2} END {print sum}')
  echo "$svc: $chi_count chi-specific calls"
done

# 2. Check handler signatures
# stdlib-compatible: func(http.ResponseWriter, *http.Request) — LOW lock-in
# framework-specific: func(*gin.Context) error — HIGH lock-in
rg "func\(.*http\.ResponseWriter.*\*http\.Request" --type go | wc -l
rg "func\(.*\*gin\.Context\)" --type go | wc -l

# 3. Check middleware signatures
rg "func\(.*http\.Handler\).*http\.Handler" --type go | wc -l  # portable
rg "gin\.HandlerFunc" --type go | wc -l  # locked
```

**Metrics to assess lock-in**:
1. **Framework import count**: Number of files importing the framework. `>50 files` = high lock-in.
2. **Handler signature ratio**: Percentage of handlers using framework-specific signatures vs `http.Handler`. `<20% stdlib` = high lock-in.
3. **Middleware portability**: Percentage of middleware using `func(http.Handler) http.Handler` vs framework-specific types.
4. **Context usage**: Number of calls to framework-specific context methods vs `context.Context`.
5. **Migration estimate**: Number of handler signature changes required to switch frameworks.

---

## Hands-On Exercises

### Exercise 1: Cross-Framework Benchmark Suite

**Task**: Create a benchmark that compares Chi, Gin, Echo, and stdlib for 3 scenarios:
1. Static route (`GET /health`)
2. Parameterized route (`GET /users/{id}`)
3. Nested route with middleware (`GET /api/v1/users/{id}/orders`)

Run each benchmark with:
- Route table size: 10, 100, 1000 routes
- Measure: ns/op, B/op, allocs/op

**Deliverable**: A benchmark report with analysis. Which framework has the most consistent performance across route table sizes?

### Exercise 2: Framework-Agnostic Service Refactoring

**Task**: Take an existing Chi service (or create one with 5 endpoints) and refactor it to be framework-agnostic:
1. Extract all business logic into a service layer that accepts `context.Context` and domain types (no HTTP types)
2. Create an HTTP adapter that's the single file importing Chi
3. Verify: can you swap Chi for Gin by changing only the adapter file?

**Deliverable**: Before/after comparison of the service structure. Count the framework imports in each.

### Exercise 3: Framework Evaluation Report

**Task**: Pick a Go HTTP framework you haven't used before (e.g., Echo if you know Chi; Fiber if you know Gin). In 2 hours:
1. Read the README and quickstart
2. Build a simple CRUD API (3 endpoints)
3. Write a 1-page evaluation using the 10-point checklist

**Deliverable**: A framework evaluation report with scores for each dimension and a final recommendation (adopt / evaluate further / reject).

---

## Advanced Challenges

### Principal Challenge 1: Design a Framework-Agnostic Platform

**Task**: Design an internal platform for a 200-engineer organization that allows service teams to choose their own HTTP router (Chi, Gin, Echo, or stdlib) while maintaining:
- Consistent observability (same log format, same metrics, same traces)
- Shared middleware (auth, rate limiting, request ID)
- Common deployment pipeline
- Standard error response format

**Constraints**:
- Platform must not force a specific router
- Platform must provide value that makes voluntary adoption the rational choice
- Migration between routers must be a 1-day effort per service

**Deliverable**: Platform architecture document, middleware library design (using `func(http.Handler) http.Handler`), service template variants for each supported router, and adoption strategy.

### Principal Challenge 2: Evaluate a Non-stdlib Framework for Adoption

**Task**: Your organization is considering adopting Fiber for high-performance edge services. Conduct a comprehensive evaluation:

1. Benchmark Fiber vs Chi for your actual workload (not "hello world")
2. Audit the ecosystem: which libraries in your stack are fasthttp-incompatible?
3. Assess the team's ability to debug fasthttp-based services
4. Model the total cost of ownership (development, operations, hiring, training)
5. Build a proof-of-concept Fiber service with your actual middleware requirements
6. Write an ADR with a recommendation

**Deliverable**: A 5-page evaluation report with benchmarks, ecosystem audit, TCO model, and ADR.

---

## Key Insights

1. **`net/http.Handler` is the universal interface.** Every Go HTTP framework ultimately serves `http.Handler`. Design your services so that the framework is an implementation detail of the HTTP adapter, not the foundation of your architecture.

2. **Framework performance differences are negligible for real workloads.** The 50ns difference between Chi and Gin is invisible next to a 20ms database call. Choose frameworks based on compatibility, maintainability, and team familiarity — not benchmark numbers.

3. **stdlib compatibility is the most important criterion.** It determines portability, testability, and ecosystem access. A framework that implements `http.Handler` can use any `net/http` middleware, any `httptest` test pattern, and any `net/http`-based observability tool. A framework that doesn't (Fiber) is a separate ecosystem.

4. **Chi's design is deliberate, not limited.** Chi doesn't have built-in validation, binding, or rendering because those are separate concerns. You choose the best library for each — not the one bundled with your router. This is Go's composition-over-inheritance philosophy in action.

5. **The stdlib-first approach minimizes dependency risk.** Start with `net/http`. Add Chi only when routing complexity demands it. You might be surprised how far `net/http` (especially Go 1.22+) takes you.

6. **Framework lock-in is measurable and manageable.** Count framework-specific imports, handler signatures, and middleware types. A service with <5 framework-specific imports is trivially portable. A service with 200+ gin.Context references is effectively locked in.

7. **Staff engineers evaluate frameworks strategically, not religiously.** "Chi is better than Gin" is a religious statement. "Chi is a better fit for our platform because stdlib compatibility reduces migration risk across 50 services by 90%" is a strategic evaluation. Always provide the "why."
