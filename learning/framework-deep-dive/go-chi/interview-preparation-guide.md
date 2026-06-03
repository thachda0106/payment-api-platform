# Go/Chi Staff Engineer Interview Preparation Guide

> **Level**: Staff/Principal Engineer
> **Purpose**: Prepare for Staff+ engineering interviews with Go/Chi-specific scenarios
> **Usage**: Read the problem statement, formulate your approach, then compare with the solution approach
> **Note**: These scenarios test decision-making, trade-off analysis, and architectural reasoning — not just coding

---

## Scenario 1: Design a Payment Processing API with Chi — Handle 10K QPS

### Problem Statement

Design a payment processing API using Go and Chi that must handle 10,000 QPS with p99 latency under 200ms. The API must accept payment requests, validate them, route to appropriate payment processors (Stripe, Adyen, internal), handle idempotency, and return results. The system must be horizontally scalable and survive instance failures without data loss.

### What the Interviewer Is Testing

- Capacity planning with Go's performance characteristics
- Chi middleware pipeline design for high-throughput scenarios
- Understanding of idempotency and exactly-once processing
- Knowledge of Go's concurrency patterns (goroutines, channels, contexts)
- Production readiness thinking (graceful shutdown, connection pooling, timeouts)

### Solution Approach

**Capacity Planning**: Start with the math. At 10K QPS with p99 < 200ms, you need to size instances around Go's strengths. A Go/Chi service on 4 vCPUs handles ~1,500-2,500 QPS for I/O-bound payment processing (validation + DB write + PSP call). You need 4-6 instances with N+1 redundancy. Memory: 512MB-1GB per instance (Go's goroutine model means you're CPU-bound, not memory-bound). Compare: the same workload in Java/Spring would need 25-30 instances — a 5-7x cost multiplier.

**Chi Router Design**: The router structure should use `chi.Mount` for API versioning and `chi.Route` for related endpoint groups:

```go
r := chi.NewRouter()
r.Use(middleware.RequestID)
r.Use(middleware.RealIP)
r.Use(observabilityMiddleware)
r.Use(recoveryMiddleware)

r.Route("/api/v1", func(r chi.Router) {
    r.Use(authMiddleware)
    r.Use(rateLimitMiddleware)

    r.Post("/payments", paymentHandler.Create)
    r.Get("/payments/{id}", paymentHandler.Get)
    r.Post("/payments/{id}/capture", paymentHandler.Capture)
    r.Post("/payments/{id}/refund", paymentHandler.Refund)
})
```

**Idempotency**: This is critical for payment APIs. Implement idempotency key validation as middleware. Store (key + response) in Redis with TTL. On retry with same key, return cached response. The idempotency store must be atomic: `SET key value NX EX 86400` in Redis (only set if not exists, with 24h expiry).

**Payment Processor Routing**: Use the Strategy pattern. Each PSP implements a `PaymentProcessor` interface. Route selection based on payment method, amount, currency, and merchant configuration. This keeps handlers thin — the handler extracts the request, delegates to the service, and writes the response.

**Concurrency Model**: Accept requests concurrently (Go handles this naturally via goroutines per connection). But control concurrency to downstream PSPs — use a bounded semaphore or worker pool to limit concurrent outbound calls. This prevents overwhelming PSPs and provides backpressure.

### Key Points to Mention
- Go's 2KB goroutine stack means 10K concurrent requests use only ~20MB for goroutine stacks
- Chi middleware ordering matters: idempotency check BEFORE PSP call, rate limit BEFORE auth (reject early)
- Use `context.WithTimeout` for every PSP call; propagate context cancellation
- Implement graceful shutdown: `server.Shutdown(ctx)` drains in-flight requests before exiting
- Circuit breaker on PSP calls — if Stripe is degraded, route to Adyen automatically
- Connection pooling: `http.Transport.MaxIdleConnsPerHost = 100` for PSP connections
- Database: use connection pool (`SetMaxOpenConns(50)`) and query timeouts

### Common Mistakes
- Not implementing idempotency — double-charging customers is the #1 payment API failure
- Using synchronous processing for the entire payment flow — acknowledge the request first, process asynchronously
- Not accounting for PSP failure modes (timeout vs 4xx vs 5xx — different retry strategies)
- Assuming Go's goroutines mean you don't need concurrency control for outbound calls
- Not considering the database as a bottleneck — 10K QPS × 2 DB queries = 20K QPS to the database

---

## Scenario 2: Review This Chi Middleware Pipeline — Find the Bugs

### Problem Statement

You're reviewing a PR for a new Chi service. Here's the middleware setup in `main.go`. Find at least 5 issues:

```go
r := chi.NewRouter()

r.Use(middleware.Logger)
r.Use(middleware.Recoverer)
r.Use(middleware.Timeout(60 * time.Second))

r.Use(func(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        userID := r.Header.Get("X-User-ID")
        ctx := context.WithValue(r.Context(), "userID", userID)
        r = r.WithContext(ctx)
        next.ServeHTTP(w, r)
    })
})

r.Use(func(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json")
        w.WriteHeader(200)
        next.ServeHTTP(w, r)
    })
})

r.Get("/api/users/{id}", func(w http.ResponseWriter, r *http.Request) {
    id := chi.URLParam(r, "id")
    user, err := db.Query("SELECT * FROM users WHERE id = ?", id)
    if err != nil {
        http.Error(w, "not found", http.StatusNotFound)
    }
    json.NewEncoder(w).Encode(user)
})
```

### What the Interviewer Is Testing

- Deep understanding of Chi's middleware pipeline and `net/http` behavior
- Knowledge of common Go security vulnerabilities and anti-patterns
- Ability to spot subtle bugs that pass code review but fail in production

### Solution Approach

**Bug 1: Timeout before Auth middleware**: The `Timeout(60s)` is applied globally but positioned before the auth middleware. If auth takes 55s (e.g., OIDC provider degradation), the handler gets only 5s. Middleware ordering: place auth-specific timeouts around auth, handler timeout around handler.

**Bug 2: Context key as string**: `context.WithValue(r.Context(), "userID", userID)` uses a string key — collisions are possible. Always use an unexported type for context keys: `type contextKey string; const userIDKey contextKey = "userID"`.

**Bug 3: WriteHeader before next.ServeHTTP**: `w.WriteHeader(200)` is called before calling `next.ServeHTTP(w, r)`. The handler or downstream middleware cannot change the status code. If the handler encounters an error and tries to write 500, it's already 200. Writing the header commits it. Only set headers (not status) before calling next.

**Bug 4: SQL injection**: `db.Query("SELECT * FROM users WHERE id = ?", id)` — actually, this uses parameterized queries (the `?`), which prevents SQL injection. BUT: the error handling is wrong. `db.Query` returns `*sql.Rows, error`. If `err != nil`, the code returns "not found" (404). But many SQL errors are NOT "not found" — connection errors, syntax errors, permission errors. Check `errors.Is(err, sql.ErrNoRows)` for the not-found case; everything else is 500.

**Bug 5: Missing rows.Close()**: `db.Query` returns `*sql.Rows` that must be closed. Without `defer rows.Close()`, the database connection is leaked. This causes connection pool exhaustion under load.

**Bug 6: X-User-ID header trust**: The middleware blindly trusts the `X-User-ID` header without validation. Any client can impersonate any user. JWT validation or API key lookup should happen here — not just header passthrough.

**Bug 7: chi.URLParam not imported**: `chi.URLParam(r, "id")` — but `chi` is never imported in the shown code. The router is used via `chi.NewRouter()` with `r.Use`, but `chi.URLParam` would need `"github.com/go-chi/chi/v5"` imported.

### Key Points to Mention
- Middleware ordering is a resource budget: each middleware layer consumes time from the timeout budget
- Context keys should be private types to prevent key collisions
- `WriteHeader` commits the response — call it only once, after processing
- Database errors ≠ "not found" — always distinguish error types
- Every `db.Query` needs a `defer rows.Close()`
- Never trust client-supplied headers for auth without validation

### Common Mistakes
- Focusing only on obvious bugs and missing the subtle middleware ordering issue
- Not mentioning the SQL injection check (actually fine, but worth calling out)
- Missing the `rows.Close()` leak — this causes production outages exactly as described
- Not connecting this to the PRR (Production Readiness Review) template

---

## Scenario 3: A Chi Service Has p99 Latency of 5s — Debug It

### Problem Statement

A Chi service handling 500 QPS has p99 latency of 5 seconds, but p50 is 50ms. The service processes payment callbacks from Stripe. The team has checked: CPU is 15%, memory is 200MB (stable), GC pauses are <1ms. What's causing the p99 tail latency, and how would you find and fix it?

### What the Interviewer Is Testing

- Systematic debugging methodology for latency issues
- Understanding of Go's concurrency model and how it affects tail latency
- Knowledge of profiling tools (pprof, trace)
- Ability to differentiate between application, infrastructure, and external dependency issues

### Solution Approach

**Mental model**: p50 at 50ms and p99 at 5s means 1% of requests are 100x slower. This is almost never a uniform slowdown (where all requests would be equally affected). It's typically: (1) blocking on a contended resource, (2) intermittent slow dependency, (3) GC-related pauses (ruled out), or (4) goroutine scheduling delays.

**Step 1 — Execution trace**: Capture a 10-second trace: `curl -o trace.out http://localhost:6060/debug/pprof/trace?seconds=10`. Open with `go tool trace trace.out`. Look at the goroutine timeline. Are there goroutines in "runnable" state for seconds? That indicates goroutine scheduling starvation — too many goroutines, not enough OS threads. Look at "Network blocking" — are some goroutines waiting on network I/O for seconds?

**Step 2 — Goroutine profile**: `go tool pprof http://localhost:6060/debug/pprof/goroutine`. Look for goroutines blocked on channel operations, mutexes, or I/O. A goroutine blocked on `sync.Mutex.Lock` for seconds indicates lock contention in a hot path.

**Step 3 — Check external dependencies**: Payment callbacks from Stripe are webhooks — the service is receiving, not making, calls. But the handler likely calls Stripe's API to verify the webhook signature or fetch payment details. Check Stripe API latency. Even a 1% slow Stripe API response (e.g., 5s) would create this exact pattern.

**Likely root cause**: The handler verifies webhook signatures by fetching payment details from Stripe. 1% of Stripe API calls are slow (network, Stripe's own tail latency). Without a timeout, the handler blocks until the Stripe call completes. Middleware timeout is 60s, so 5s calls pass through.

**Fix**:
```go
// BEFORE: No timeout on Stripe call
payment, err := stripeClient.GetPayment(r.Context(), paymentID)

// AFTER: Explicit timeout + circuit breaker
ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
defer cancel()
payment, err := stripeClient.GetPayment(ctx, paymentID)
if errors.Is(err, context.DeadlineExceeded) {
    // Queue for retry, return 202 Accepted to Stripe
    // Stripe will retry the webhook
}
```

**Alternative root cause**: Connection pool exhaustion. If `http.Transport.MaxConnsPerHost` is set to 10 and all 10 connections are in use by slow requests, the 11th request waits in a queue. Check `net/http` connection pool sizing.

**Another possibility**: The service uses a single database connection (or pool with MaxOpenConns=1) and some queries are slow (missing index, table scan). Check database slow query log.

### Key Points to Mention
- p50 vs p99 disparity almost always indicates contention or intermittent slow dependency
- Use `go tool trace` for goroutine scheduling analysis (not just pprof)
- Check `net/http` transport settings: `MaxConnsPerHost`, `MaxIdleConnsPerHost`
- Stripe webhook handlers should respond quickly (2xx) — verify signature, acknowledge, process asynchronously
- Add per-dependency latency metrics to isolate the slow component

### Common Mistakes
- Jumping to "add more CPU" when CPU is at 15%
- Not capturing an execution trace (most engineers reach for pprof, but trace is more useful for latency)
- Assuming the problem is in their code when it's an external dependency
- Not setting explicit timeouts for every outbound call

---

## Scenario 4: Chi vs Gin vs stdlib — Make a Recommendation for Our Platform

### Problem Statement

Your CTO asks: "We're building a new platform with 30 services over 3 years. We have 40 engineers, half are senior Go developers, half are junior developers new to Go. We need to choose an HTTP router. The leadership team has heard Gin is faster and has more features. What do you recommend?"

### What the Interviewer Is Testing

- Strategic evaluation of frameworks vs libraries
- Understanding of team composition and learning curves
- Long-term thinking (3-year horizon, not 3-month)
- Ability to communicate technical decisions to non-technical leadership
- Understanding of Go ecosystem dynamics (the stdlib commitment)

### Solution Approach

**Recommendation**: Chi, with the stdlib-first approach for simple services.

**For the leadership team (business case)**:
- Chi handlers are `http.Handler` — portable across any `net/http`-compatible tool. Gin handlers are `gin.HandlerFunc` — locked to Gin. If Gin is abandoned or we outgrow it, migrating 30 services with `gin.Context` usage is a multi-quarter rewrite.
- Chi has been stable for 5+ years, maintained by Pressly (production usage), no dependency tree (0 external dependencies beyond stdlib). Gin has ~10 transitive dependencies including a custom JSON library (`jsoniter`).
- Performance difference: Chi is ~250ns per route lookup, Gin is ~200ns. This 50ns difference is 0.00025% of a typical 20ms database-backed request. It matters only in "hello world" benchmarks.
- Go 1.22 added pattern matching to `net/http.ServeMux`. This makes Chi's routing capability ("we need it for path params") available in stdlib. We can use stdlib for simple services and Chi for complex routing needs — all within the same `http.Handler` interface.

**For the engineering team (technical case)**:
- Chi's middleware signature (`func(http.Handler) http.Handler`) is the Go standard. Middleware written for Chi works with any `net/http` server. Gin middleware (`gin.HandlerFunc`) only works with Gin.
- Chi doesn't hide complexity. Gin's `c.JSON(200, data)` and `c.ShouldBindJSON(&req)` are convenient but hide error paths. Chi forces explicit error handling — matching Go's philosophy.
- Junior developers will learn the right patterns: `http.ResponseWriter`, `*http.Request`, `context.Context`. If they learn on Gin, they learn `*gin.Context` — and struggle when moving to any other Go project.

**Implementation strategy**:
1. Create an internal service template using Chi
2. Simple microservices (5-10 routes, minimal middleware) can use stdlib only
3. Services with route grouping, subrouters, or complex middleware use Chi
4. All services share the same `http.Handler` interface — Chi or not

**Decision record**: Write an ADR documenting this choice with the alternatives and rejection reasons.

### Key Points to Mention
- `http.Handler` compatibility = portability, testability, future-proofing
- Chi's zero external dependencies vs Gin's ~10 transitive deps
- Go 1.22 stdlib improvements reduce the gap — this makes Chi MORE valuable (it's the step up, not the only option)
- Framework choice is a 3-5 year decision — benchmark numbers are a 50ns consideration

### Common Mistakes
- Recommending based on personal preference without data
- Not considering the team's composition and learning curve
- Ignoring the long-term cost of framework lock-in
- Not addressing the CTO's concern (they heard Gin is faster — explain why that doesn't matter)

---

## Scenario 5: Design an Idempotency System for a Payment API

### Problem Statement

Design an idempotency system for a payment API built with Chi. Clients send an `Idempotency-Key` header. If a request with the same key is retried (network failure, timeout, client error), the system must return the same response without re-processing the payment. The system must handle concurrent requests with the same key, survive instance restarts, and scale to 10K QPS.

### What the Interviewer Is Testing

- Understanding of distributed systems consistency
- Database and cache design for idempotency
- Race condition handling in concurrent environments
- Chi middleware design for cross-cutting concerns
- Operational thinking (TTL, cleanup, exactly-once semantics)

### Solution Approach

**Architecture**: Idempotency middleware sitssl before the payment handler:

```go
r.Post("/api/v1/payments", idempotency.Middleware(idempotency.Config{
    KeyHeader: "Idempotency-Key",
    TTL:       24 * time.Hour,
    Store:     idempotency.NewRedisStore(redisClient),
}), paymentHandler.Create)
```

**Storage strategy — Redis as primary, PostgreSQL as backup**:
- Redis stores `{key → (status, response)}` with TTL of 24-48 hours
- For durability across Redis restarts: also write to PostgreSQL with async replication
- On cache miss, check PostgreSQL

**Concurrent request handling**:

```go
func Middleware(cfg Config) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            key := r.Header.Get(cfg.KeyHeader)
            if key == "" {
                next.ServeHTTP(w, r)
                return
            }

            // Atomically check and lock
            // SET key LOCK NX EX 10  → if OK, we own the lock
            locked, err := cfg.Store.TryLock(r.Context(), key, 10*time.Second)
            if err != nil {
                http.Error(w, "internal error", 500)
                return
            }

            if !locked {
                // Someone else is processing — wait and return their result
                result, err := cfg.Store.WaitForResult(r.Context(), key, 30*time.Second)
                if err != nil {
                    http.Error(w, "request in progress, retry later", 409)
                    return
                }
                writeStoredResponse(w, result)
                return
            }

            // We own the lock — check if result already exists
            existing, err := cfg.Store.Get(r.Context(), key)
            if existing != nil {
                cfg.Store.Release(r.Context(), key)
                writeStoredResponse(w, existing)
                return
            }

            // Process the request, capturing the response
            rec := httptest.NewRecorder()
            next.ServeHTTP(rec, r)

            // Store result atomically
            result := StoredResult{
                StatusCode: rec.Code,
                Headers:    rec.Header(),
                Body:       rec.Body.Bytes(),
            }
            cfg.Store.Store(r.Context(), key, result, cfg.TTL)
            cfg.Store.Release(r.Context(), key)

            // Write to original response writer
            writeStoredResponse(w, result)
        })
    }
}
```

**Redis Lua script for atomic lock-and-check**:
```lua
-- lock_and_store.lua
local key = KEYS[1]
local lock_key = key .. ':lock'
local result_key = key .. ':result'
local ttl = ARGV[1]
local existing = redis.call('GET', result_key)
if existing then
    return {1, existing}  -- already processed
end
local locked = redis.call('SET', lock_key, '1', 'NX', 'EX', '10')
if locked then
    return {2, ''}  -- lock acquired
else
    return {3, ''}  -- locked by another request
end
```

**Cleanup strategy**: Redis TTL handles automatic cleanup (24h expiry). For PostgreSQL, a cron job deletes records older than 48h. For long-running payments (e.g., 3DS authentication taking minutes), extend the lock TTL with a heartbeat goroutine.

### Key Points to Mention
- Idempotency keys must be client-generated UUIDs, not server-generated
- The lock timeout must be significantly longer than the maximum expected processing time
- For extremely long operations, use a lock heartbeat goroutine
- Store the complete response (status, headers, body) — clients expect identical responses on retry
- The `Idempotency-Key` header should be required for all POST/PUT/PATCH/DELETE endpoints
- Consider idempotency key replay attack: if key reuse reveals responses, implement user-scoped keys

### Common Mistakes
- Storing only "success" results — need to store error responses too (client retries should get the same error)
- Not handling the race between concurrent requests with the same key
- Using the idempotency key as a cache key without locking
- Making the lock TTL too short (operation not complete) or too long (resource waste after failure)
- Not considering the storage layer's failure modes (Redis goes down)

---

## Scenario 6: How Would You Evolve a Chi Monolith to Microservices?

### Problem Statement

A 3-year-old Go/Chi monolith serves 50 endpoints, 8K QPS. 16 engineers work on it across 4 feature teams. Merge conflicts, deployment coordination, and testing bottlenecks are increasing. The CTO wants to evolve to microservices. How do you approach this?

### What the Interviewer Is Testing

- Incremental migration strategy (not big-bang rewrite)
- Understanding of domain boundaries and bounded contexts
- Ability to assess when microservices help vs hurt
- Chi-specific migration patterns (Mount, Route, handler portability)
- Organizational alignment (Conway's Law)

### Solution Approach

**Step 1 — Don't start with extraction. Start with modularization.** Chi's `Mount` and `Route` provide within-process isolation that solves 80% of the monolith problems without introducing network complexity:

```go
// Phase 1: Modular Monolith (2 weeks)
r := chi.NewRouter()
r.Mount("/payments", payments.NewRouter(paymentsDB))
r.Mount("/users", users.NewRouter(usersDB))
r.Mount("/orders", orders.NewRouter(ordersDB))
r.Mount("/notifications", notifications.NewRouter(notificationsDB))
```

Each domain gets its own package with its own router, middleware, and database access. Teams own packages without touching each other's code. This eliminates merge conflicts on route registration and middleware configuration.

**Step 2 — Identify extraction candidates using these criteria:**
1. Independent deployment cadence (payments deploys twice/week, users once/month → extract payments)
2. Independent scaling (payments needs 20 instances at peak, users needs 3)
3. Independent team ownership with separate on-call rotations
4. Different data compliance requirements (PII in user service, PCI in payment service)

**Step 3 — Extract using the Strangler Fig pattern:**
1. Create new Chi service for the extracted domain
2. Route traffic via API gateway: 5% → new service, 95% → monolith
3. Compare responses between old and new for correctness
4. Gradually increase traffic to 100%
5. Remove domain code from monolith

**Step 4 — Extract shared concerns:**
- Shared middleware library (Chi middleware for auth, logging, tracing)
- Service template (standardized Chi service structure)
- Event bus (Kafka/NATS) for cross-service communication

**Step 5 — Database decomposition:**
- Each extracted service gets its own database
- Initially: read from monolith DB (read-only replica), write to own DB
- Eventual consistency: use CDC or outbox pattern to sync data
- Eventually: monolith DB tables become service-specific

### Key Points to Mention
- Go's efficiency means the monolith can scale much further vertically than Java monoliths — extraction urgency is lower
- Chi's `Mount` is the bridge between monolith and microservices — get the within-process boundaries right before adding network boundaries
- Every service extraction adds ~2-5ms network latency AND ~1-2% failure rate (imperfect network) — justify each extraction
- Domain-driven design should guide the boundaries, not team size or code size
- Use the "two-pizza team" heuristic but apply it to domain ownership, not codebase size

### Common Mistakes
- Starting with "let's split into microservices" without modularizing first
- Extracting services that are tightly coupled (every order needs a user lookup → cascading failures)
- Not establishing shared observability before extraction (distributed debugging is hard)
- Creating microservices that are too small (every endpoint is a service — now you have 50 services, not 3-5)
- Not addressing the data problem early (shared database is the hardest part to decompose)

---

## Scenario 7: Design Observability for 50 Chi Services

### Problem Statement

Your organization has 50 Chi services in production, each built by different teams with different observability approaches. Some use `zerolog`, some `logrus`, some `fmt.Println`. Metrics formats are inconsistent. There's no distributed tracing. An incident takes 2 hours to triage because you can't trace a request across services. Design a unified observability strategy.

### What the Interviewer Is Testing

- Platform-level thinking (not single-service)
- Observability standards and enforcement strategies
- Understanding of OpenTelemetry, Prometheus, and structured logging
- Migration strategy (can't change 50 services overnight)
- SLO and alerting design

### Solution Approach

**Phase 1 — Standardize the signal format (sprint 1-4)**:
- **Logs**: Structured JSON to stdout. Standard fields: `timestamp`, `level`, `message`, `trace_id`, `span_id`, `service`, `instance`. Use `log/slog` (Go 1.21+) as the standard library. For existing services, add a `slog` adapter that wraps their existing logger — don't force rewrite.
- **Metrics**: Prometheus format at `/metrics`. Standard RED metrics: `http_requests_total`, `http_request_duration_seconds`, `http_errors_total`. Namespace: `{service}_{metric}`.
- **Traces**: OpenTelemetry SDK. Auto-instrument HTTP with `otelhttp.NewHandler`. Manual spans for database, Redis, Kafka calls. W3C Trace Context headers for propagation.

**Phase 2 — Build the observability platform (sprint 5-8)**:
- **Log aggregation**: All logs to Grafana Loki (or Elasticsearch). Parse JSON logs, index by trace_id, service, level.
- **Metrics**: Prometheus + Grafana. Standard dashboards provisioned as code. One dashboard per service (auto-generated from service template). SLO dashboards for critical services.
- **Traces**: Grafana Tempo or Jaeger. Link traces to logs and metrics (exemplars).

**Phase 3 — Enforce adoption via the golden path (ongoing)**:
- Service template generates observability boilerplate automatically
- CI/CD pipeline checks: does the service expose `/metrics`? Is `/health` returning 200? Are traces being emitted?
- Platform team reviews new services before production deployment — observability is a blocking item

**Phase 4 — Define and monitor SLOs (ongoing)**:
- Define SLIs: availability (2xx/total), latency (p95 < threshold)
- Define SLOs: 99.9% monthly availability, p95 < 200ms
- Multi-window, multi-burn-rate alerting (Google SRE chapter 5)
- Error budget policy: if error budget is exhausted, freeze features, fix reliability

**Chi-specific implementation**:
```go
// Standardized Chi observability setup
func NewObservabilityRouter() chi.Router {
    r := chi.NewRouter()

    r.Use(func(next http.Handler) http.Handler {
        return otelhttp.NewHandler(next, "service-name",
            otelhttp.WithMessageEvents(otelhttp.ReadEvents, otelhttp.WriteEvents),
        )
    })

    r.Use(func(next http.Handler) http.Handler {
        return promhttp.InstrumentHandlerDuration(
            prometheus.HistogramVec{...}, next,
        )
    })

    h := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
    r.Use(NewSlogMiddleware(slog.New(h)))

    return r
}
```

### Key Points to Mention
- Auto-instrumentation over manual — engineers won't add tracing spans manually in every handler
- Observability must be zero-effort for service teams (comes from the platform, not their code)
- Standardize formats, not libraries — if a team uses `zerolog` with JSON output, that's fine if the format is consistent
- SLOs create shared language between product and engineering (error budget)
- The observability stack itself needs observability — monitor Loki, Prometheus, Tempo

### Common Mistakes
- Mandating a specific library across all teams (creates migration burden)
- Focusing on dashboards before data quality (pretty dashboards with inconsistent data are useless)
- Not instrumenting the observability infrastructure itself
- Setting SLOs at 100% (unachievable, demotivating, leads to ignoring SLOs entirely)
- Building custom observability when OpenTelemetry is the industry standard

---

## Scenario 8: A Goroutine Leak Is Causing OOM — How Do You Find and Fix It?

### Problem Statement

A Chi service handling file uploads is crashing with OOMKilled (exit code 137) every 6 hours in production. The service handles 500 concurrent uploads at peak. Memory graph shows a sawtooth pattern: slow increase, sharp drop (GC), but baseline creeps up each cycle until OOM. How do you diagnose and fix this?

### What the Interviewer Is Testing

- Production debugging methodology under time pressure
- Goroutine leak detection using pprof
- Understanding of Go's memory model and GC
- Handler timeout patterns in Chi
- Root cause analysis vs symptom treatment

### Solution Approach

**Step 1 — Confirm goroutine leak**: `curl http://localhost:6060/debug/pprof/goroutine?debug=1 | head -1`. Take 3 samples, 30s apart. If goroutine count increases monotonically (doesn't return to baseline after load), it's a leak.

**Step 2 — Find leaking goroutines**: `go tool pprof http://localhost:6060/debug/pprof/goroutine`.

```bash
(pprof) top10
# Shows: 32000 goroutines in "syscall.Syscall" at io.ReadFull
#        15000 goroutines in "chan receive" at uploadHandler.func1
#        8000  goroutines in "select" at http.(*conn).serve
```

**Step 3 — Identify the root cause**: The file upload handler reads from `r.Body` without a timeout. Slow clients (mobile on 3G) send data at 10KB/s. A 10MB file takes 1000 seconds. During those 1000 seconds, the goroutine holds:
- One goroutine stack (starts at 2KB, grows)
- The request body buffer
- The connection (one file descriptor)

500 concurrent slow uploads = 500 goroutines × (stack + buffer) × growing over time = OOM.

**Step 4 — Fix (immediate)**:
```go
// Add request-level timeout that covers the entire upload lifecycle
r.Group(func(r chi.Router) {
    r.Use(chi.middleware.Timeout(60 * time.Second))
    r.Post("/upload", uploadHandler)
})

// Or add timeout specifically to body reading
func uploadHandler(w http.ResponseWriter, r *http.Request) {
    ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
    defer cancel()

    // Wrap r.Body with a timeout-aware reader
    r.Body = http.MaxBytesReader(w, r.Body, 10<<20) // 10MB max
    body, err := io.ReadAll(r.Body)
    // ...
}
```

**Step 5 — Fix (long-term)**:
```go
// Use io.LimitReader with context-aware wrapper
type contextReader struct {
    ctx    context.Context
    reader io.Reader
}

func (r *contextReader) Read(p []byte) (int, error) {
    select {
    case <-r.ctx.Done():
        return 0, r.ctx.Err()
    default:
        return r.reader.Read(p)
    }
}

func uploadHandler(w http.ResponseWriter, r *http.Request) {
    r.Body = &contextReader{ctx: r.Context(), reader: r.Body}
    // r.Body.Read will return error if context is cancelled (timeout)
}
```

**Step 6 — Verify**: Deploy fix to canary. Monitor goroutine count for 24 hours. Verify baseline returns to normal after load subsides.

### Key Points to Mention
- Goroutine leak detection: goroutine count returns to baseline after load → normal; monotonically increases → leak
- File upload handlers are the #1 source of goroutine leaks (slow clients, no timeouts)
- `io.ReadAll` or `ioutil.ReadAll` without a timeout on `r.Body` is dangerous
- `r.Body` is unbounded by default — always use `http.MaxBytesReader`
- The `chi.middleware.Timeout` middleware cancels the request context — but `io.ReadAll` on an `r.Body` without context-awareness won't notice

### Common Mistakes
- Just adding `chi.middleware.Timeout` without making I/O context-aware (the timeout cancels the context, but `io.ReadAll` doesn't check the context)
- Not using `http.MaxBytesReader` — a malicious client can send an infinite stream
- Fixing the symptom (increase memory limit) instead of the root cause (leaking goroutines)
- Not verifying the fix in production with canary deployment

---

## Scenario 9: Design a Rate Limiting System Across Multiple Chi Instances

### Problem Statement

Design a rate limiting system for a Chi service running across 10 instances behind a load balancer. Requirements: 1000 requests/minute per user globally (across all instances), burst of 50, accurate to within 1 second, must not add >5ms latency. Users are identified by API key or JWT subject. When rate limited, return 429 with `Retry-After` header.

### What the Interviewer Is Testing

- Distributed rate limiting algorithms (token bucket, sliding window)
- Redis atomicity with Lua scripting
- Trade-off between accuracy and performance
- Chi middleware design for distributed systems
- Understanding of the CAP theorem applied to rate limiting (choose consistency or availability)

### Solution Approach

**Algorithm choice**: Sliding window with Redis. A sliding window is more accurate than fixed window (which has edge-of-window burst problem) and simpler than a full token bucket.

**Redis Lua script for atomic rate limit check**:
```lua
-- rate_limit.lua
local key = KEYS[1]           -- "ratelimit:{userID}:{endpoint}"
local now = tonumber(ARGV[1]) -- current unix timestamp in ms
local window = tonumber(ARGV[2]) -- window size in ms (60000 = 1 minute)
local limit = tonumber(ARGV[3])  -- max requests per window

-- Remove expired entries (outside the window)
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- Count current entries
local current = redis.call('ZCARD', key)
if current >= limit then
    -- Get the oldest entry timestamp
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')[2]
    local retryAfter = math.ceil((oldest + window - now) / 1000)
    return {0, retryAfter}  -- rate limited
end

-- Add current request's timestamp
redis.call('ZADD', key, now, now .. '-' .. math.random())
redis.call('EXPIRE', key, math.ceil(window / 1000) + 1)
return {1, current + 1}  -- allowed
```

**Middleware Implementation**:
```go
func RateLimitMiddleware(store RateLimitStore, config RateLimitConfig) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            key := config.KeyFunc(r) // e.g., extract user ID from JWT or API key

            result, err := store.Check(r.Context(), key, config.Window, config.Limit)
            if err != nil {
                // Redis unavailable — what now?
                switch config.FailureMode {
                case FailOpen:
                    next.ServeHTTP(w, r) // allow request
                case FailClosed:
                    http.Error(w, "rate limit unavailable", 503)
                default:
                    next.ServeHTTP(w, r) // log and allow
                }
                return
            }

            // Set rate limit headers
            w.Header().Set("X-RateLimit-Limit", strconv.Itoa(config.Limit))
            w.Header().Set("X-RateLimit-Remaining", strconv.Itoa(config.Limit - result.Remaining))
            w.Header().Set("X-RateLimit-Reset", strconv.Itoa(result.Reset))

            if result.Allowed {
                next.ServeHTTP(w, r)
            } else {
                w.Header().Set("Retry-After", strconv.Itoa(result.RetryAfter))
                http.Error(w, "rate limit exceeded", http.StatusTooManyRequests)
            }
        })
    }
}
```

**Trade-off — Consistency vs Availability**: If Redis is unavailable, you must choose:
- **Fail open** (allow requests): Users get rate limit bypass during Redis outage. Acceptable for non-critical endpoints.
- **Fail closed** (reject requests): Service is unavailable during Redis outage. Required for payment endpoints.
- **Degraded mode** (fall back to local in-memory rate limiter): Best of both, but less accurate (per-instance limits, not global).

**Performance optimization**:
- Use Redis pipeline or client-side caching for header-only checks (no script execution)
- Async local rate limit counter as a fast-path: if local counter hasn't hit the global limit, skip Redis call
- Redis cluster with read replicas for high availability
- Connection pooling: maintain persistent Redis connections

### Key Points to Mention
- Redis sorted sets provide O(log N) insertion and O(log N + M) range queries — perfect for sliding window
- Lua scripts ensure atomicity (check + increment is a single operation, no race conditions)
- The "failure mode" decision (fail open vs fail closed) should be per-endpoint, not global
- Rate limit keys should include user ID AND endpoint to prevent one endpoint's limit from affecting others
- Per-instance local caching can reduce Redis calls by 80-90% while maintaining accuracy

### Common Mistakes
- Using fixed window (edge-of-window burst: user sends 1000 requests at 11:59:59 and 1000 at 12:00:01 — doubles the rate)
- Not accounting for clock skew across instances
- Making the Redis key too large (use hashed user IDs, not full UUIDs)
- Not setting TTL on Redis keys (keys accumulate indefinitely)
- Ignoring the CAP theorem — rate limiting is inherently a consistency problem in a distributed system

---

## Scenario 10: How Would You Structure a Go Monorepo with 20 Chi Services?

### Problem Statement

Your organization has decided to move 20 Go services into a monorepo. Currently, each service has its own repo with independent dependency management, CI/CD, and versioning. The goal: shared dependencies, atomic cross-service changes, and unified tooling. How do you structure a Go monorepo with 20 Chi services?

### What the Interviewer Is Testing

- Monorepo design principles for Go
- Go module management in monorepos (one go.mod vs many)
- Build system design (CI/CD efficiency — don't rebuild everything on every change)
- Shared library management
- Code generation and tooling strategy

### Solution Approach

**Repository Structure**:
```
platform/
├── go.mod                          # Single Go module for the entire repo
├── go.sum
├── Makefile                        # Top-level build orchestration
├── .golangci.yml                   # Shared lint config
├── .github/
│   └── workflows/
│       └── ci.yaml                 # Unified CI with change detection
│
├── cmd/                            # Service entrypoints
│   ├── payment-service/
│   │   └── main.go
│   ├── user-service/
│   │   └── main.go
│   └── order-service/
│       └── main.go
│
├── pkg/                            # Shared libraries (importable by all services)
│   ├── middleware/
│   │   ├── auth.go                 # JWT auth middleware (Chi-compatible)
│   │   ├── logging.go              # slog middleware
│   │   ├── ratelimit.go            # Rate limiting middleware
│   │   └── middleware_test.go
│   ├── telemetry/
│   │   ├── tracing.go              # OpenTelemetry setup
│   │   └── metrics.go              # Prometheus metrics setup
│   ├── apperror/
│   │   └── error.go                # Standard error types
│   └── config/
│       └── config.go               # Environment-based config
│
├── internal/                       # Service-internal packages (NOT importable by other services)
│   ├── payment/
│   │   ├── handler/
│   │   ├── service/
│   │   ├── repository/
│   │   └── model/
│   ├── user/
│   │   ├── handler/
│   │   ├── service/
│   │   └── repository/
│   └── order/
│       ├── handler/
│       └── service/
│
├── api/                            # Shared API definitions
│   ├── protobuf/                   # Protobuf/gRPC definitions
│   └── openapi/                    # OpenAPI specs
│
├── deployments/                    # K8s manifests, Helm charts
│   ├── payment-service/
│   └── user-service/
│
├── scripts/                        # Build and CI scripts
│   ├── detect-changes.sh           # Detect which services changed
│   └── gen-mocks.sh
│
└── tools/                          # Tool dependencies (go.mod with tools.go pattern)
    └── tools.go
```

**Key design decisions**:

1. **Single `go.mod`** for the entire monorepo. This ensures all services use the exact same dependency versions. Trade-off: `go.mod` is large, but Go handles large modules efficiently. Alternative: multiple `go.mod` per service (workspace mode with `go.work`) — more complex, allows different deps per service.

2. **`internal/` for service code, `pkg/` for shared code**: The `internal/` directory enforces that `payment/` cannot import `user/` — preventing accidental coupling. `pkg/` is the shared library surface area. This is enforced at the compiler level.

3. **Change detection for CI**:
```bash
#!/bin/bash
# detect-changes.sh — only rebuild changed services
CHANGED_FILES=$(git diff --name-only HEAD~1)
if echo "$CHANGED_FILES" | grep -q "^pkg/"; then
    echo "all"  # shared code changed → rebuild everything
elif echo "$CHANGED_FILES" | grep -q "^go.mod"; then
    echo "all"  # dependency change → rebuild everything
else
    for svc in cmd/*/; do
        if echo "$CHANGED_FILES" | grep -q "^internal/$(basename $svc)/"; then
            echo "$(basename $svc)"
        fi
    done
fi
```

4. **Service template enforcement**: Use a code generation tool that verifies every service follows the template:
```bash
# verify-service-structure.sh
for svc in cmd/*/; do
    required=("main.go" ".golangci.yml")
    for req in "${required[@]}"; do
        if [ ! -f "$svc/$req" ]; then
            echo "ERROR: $svc missing $req"
            exit 1
        fi
    done
done
```

5. **Dependency management**:
```go
// tools/tools.go — track tool dependencies
//go:build tools

package tools

import (
    _ "github.com/golangci/golangci-lint/cmd/golangci-lint"
    _ "github.com/sqlc-dev/sqlc/cmd/sqlc"
    _ "github.com/bufbuild/buf/cmd/buf"
    _ "google.golang.org/protobuf/cmd/protoc-gen-go"
)
```

### Key Points to Mention
- Single `go.mod` vs `go.work` workspace — trade-off between consistency and autonomy
- `internal/` enforcement prevents accidental coupling between services (compiler-level guarantee)
- Change detection in CI: only build/test affected services, not all 20
- Shared middleware in `pkg/` — fix a bug once, all 20 services benefit
- Atomic cross-service refactoring: change a shared type, all consumers updated in one commit
- Tool versioning via `tools.go` ensures everyone uses the same `golangci-lint`, `sqlc`, etc.

### Common Mistakes
- Putting service code in `pkg/` (importable by other services) instead of `internal/`
- Not implementing change detection — CI takes 45 minutes for every PR
- Allowing services to diverge from the template
- Having a `pkg/util` dumping ground (no semantic grouping)
- Not versioning shared libraries (services break when `pkg/` changes without versioning)
- Using `replace` directives in `go.mod` for local packages (fragile, breaks `go install`)

---

## Interview Strategy Summary

### For Each Scenario, Structure Your Response:

1. **Clarify requirements** (30s): Ask about constraints, scale, team size, existing infrastructure
2. **State your approach** (1 min): High-level architecture decision
3. **Explain the "why"** (2 min): Trade-offs, alternatives considered, rejection reasons
4. **Get concrete** (2 min): Code examples, configuration, tooling
5. **Address edge cases** (1 min): Failure modes, scale limits, migration path
6. **Invite feedback** (30s): "Does this align with what you're thinking? What constraints am I missing?"

### Red Flags Interviewers Look For:
- Jumping to code without understanding requirements
- Not considering alternatives ("Chi is the best" without explaining why)
- Ignoring operational concerns (deployment, monitoring, debugging)
- Designing for current scale without considering growth
- Not mentioning trade-offs (every decision has downsides — acknowledge them)
