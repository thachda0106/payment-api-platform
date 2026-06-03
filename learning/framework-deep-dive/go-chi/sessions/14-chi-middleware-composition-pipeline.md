# Session 14: Chi Middleware — Composition Pipeline, Ordering, next.ServeHTTP

## Why This Topic Exists

HTTP middleware is net/http's most powerful abstraction. It is the mechanism by which orthogonal concerns — logging, authentication, rate limiting, tracing, CORS, compression — are composed without coupling handler code to infrastructure code. Every Go framework implements middleware differently, but Chi's approach is the simplest: middleware is just `func(http.Handler) http.Handler`.

This session exists because middleware is where most production bugs originate. Wrong ordering, missing `next.ServeHTTP()`, panic recovery that fires after the response is written, request-scoped values that leak between requests — these failures trace back to misunderstanding the middleware pipeline.

As a Staff/Principal Engineer, you will design middleware that multiple teams depend on. A single misordered middleware can silently break every request in the system. You need to understand not just *how* to write middleware, but *why* Chi's composition model works the way it does, and how it compares to alternatives (Gin's `gin.HandlerFunc` chain, Echo's middleware groups, standard library `http.Handler` wrapping).

---

## Mental Model

### The Onion (Russian Doll) Model

Middleware wraps handlers like layers of an onion:

```
                    ┌─────────────────────────────┐
                    │        RequestID (outer)     │
                    │  ┌─────────────────────────┐ │
                    │  │     Logger              │ │
                    │  │  ┌─────────────────────┐ │ │
                    │  │  │   Recoverer        │ │ │
                    │  │  │  ┌─────────────────┐│ │ │
                    │  │  │  │  Timeout        ││ │ │
                    │  │  │  │  ┌─────────────┐││ │ │
                    │  │  │  │  │  Auth       │││ │ │
                    │  │  │  │  │  ┌─────────┐│││ │ │
                    │  │  │  │  │  │ Handler ││││ │ │
                    │  │  │  │  │  └─────────┘│││ │ │
                    │  │  │  │  └─────────────┘││ │ │
                    │  │  │  └─────────────────┘│ │ │
                    │  │  └─────────────────────┘ │ │
                    │  └─────────────────────────┘ │
                    └─────────────────────────────┘
```

A request enters the outermost layer and passes through each layer until it reaches the handler. The response travels back through the layers in reverse order. Each layer has:
- **Pre-handler logic**: Code before `next.ServeHTTP(w, r)` — executes on the way in.
- **Post-handler logic**: Code after `next.ServeHTTP(w, r)` — executes on the way out.
- **Bypass logic**: Code that decides NOT to call `next.ServeHTTP()` — short-circuits the chain.

### Chi Middleware Signature

```go
type Middleware func(http.Handler) http.Handler
```

This is the standard library compatible signature — no Chi-specific types, no custom context, no framework lock-in. A middleware takes an `http.Handler` (the "next" handler in the chain) and returns a new `http.Handler` that adds behavior before/after calling the next handler.

### The Chain as Compression

A `chi.Chain` is a list of middlewares `[m1, m2, m3]`. When you call `chain.Handler(handler)`, it composes them:

```go
// Without Chain:
m1(m2(m3(handler)))  // Manual composition, reads inside-out

// With Chain:
chain := chi.NewChain(m1, m2, m3)
chain.Handler(handler)  // Equivalent: m1(m2(m3(handler)))
```

`Chain` also provides `chain.HandlerFunc(handlerFunc)` for convenience with `http.HandlerFunc` types.

---

## Internal Architecture

### How `chi.Chain` Works

From `chi/chain.go`:

```go
type Chain struct {
    middlewares []func(http.Handler) http.Handler
}

func NewChain(middlewares ...func(http.Handler) http.Handler) Chain {
    return Chain{middlewares: append([]func(http.Handler) http.Handler(nil), middlewares...)}
}

func (c Chain) Handler(handler http.Handler) http.Handler {
    for i := len(c.middlewares) - 1; i >= 0; i-- {
        handler = c.middlewares[i](handler)
    }
    return handler
}
```

The iteration goes **backwards** through the middleware list. Why? Because the *first* middleware in the list should be the *outermost* wrapper. If middlewares are `[A, B, C]` and the handler is `H`:

```
Desired wrapping order: A(B(C(H)))
Execution: C wraps H first → B wraps that → A wraps that
```

This is the same pattern as `http.TimeoutHandler`, `http.StripPrefix`, etc. — all standard library wrappers follow the `outer(inner(handler))` convention.

### How Middleware is Applied in the Mux

When you call `r.Use(middleware)`, the middleware is stored in the mux's `middlewares` slice. When a request arrives, `ServeHTTP` composes the chain:

```go
func (mx *Mux) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // ... route resolution ...
    
    // Build the middleware chain for this request
    handler := rctx.Route.Handler   // the matched handler
    
    // Apply route-level middlewares (from r.With())
    for i := len(rctx.Route.Middlewares) - 1; i >= 0; i-- {
        handler = rctx.Route.Middlewares[i](handler)
    }
    
    // Apply mux-level middlewares (from r.Use())
    for i := len(mx.middlewares) - 1; i >= 0; i-- {
        handler = mx.middlewares[i](handler)
    }
    
    handler.ServeHTTP(w, r)
}
```

Note: Route-level middlewares (from `r.With()`) wrap the handler FIRST, then mux-level middlewares (from `r.Use()`) wrap around those. This means mux-level middlewares are the *outermost* layers.

### Middleware Scoping with Groups

When you call `r.Group(fn)`, Chi:
1. Creates a new mux with the group's path prefix.
2. Copies the parent's middlewares into the new mux.
3. Executes `fn(newMux)` to register routes and additional middlewares.

This means group-level `r.Use()` middlewares apply only to routes within that group:

```go
r.Use(m1)                // m1 applies to everything

r.Group(func(r chi.Router) {
    r.Use(m2)            // m2 applies only inside this group
    r.Get("/a", h1)      // m1 → m2 → h1
})

r.Get("/b", h2)          // m1 → h2  (m2 does NOT apply)
```

### The `next.ServeHTTP` Contract

`next.ServeHTTP(w, r)` is the call that passes control to the next handler in the chain. The contract is:

1. **`w` and `r` are passed through**: The middleware receives the same `http.ResponseWriter` and `*http.Request` that the handler will receive. Any modifications to `r` (adding context, stripping prefix) are visible to inner handlers.
2. **`w` can be wrapped**: A middleware can wrap `w` with a custom `http.ResponseWriter` (e.g., for capturing status codes, buffering output, compressing). The wrapped writer is passed to `next`.
3. **Not calling `next` short-circuits**: If a middleware doesn't call `next.ServeHTTP`, no inner middleware or handler runs. This is how auth middleware returns 401.
4. **Post-handler code runs after the handler returns**: Code after `next.ServeHTTP()` executes with the response already written. Modifying headers here is too late.

---

## Runtime Behavior

### Request Flow Through Middleware Chain

```
 Client                     Middleware Pipeline                      Handler
   │                                                                   │
   │  GET /users/42                                                    │
   │──────────────────────────────────────────────────────────────────►│
   │                                                                   │
   │  ┌── RequestID middleware ──────────────────────────────────────┐ │
   │  │  Before next:                                                │ │
   │  │  1. Generate/read request ID (X-Request-Id header)           │ │
   │  │  2. Set request ID on response header                        │ │
   │  │  3. Add request ID to context: ctx = WithRequestID(ctx, id)  │ │
   │  │  4. Update request: r = r.WithContext(ctx)                   │ │
   │  │  5. Call next.ServeHTTP(w, r) ──────────────────────────────►│ │
   │  │◄─────────────────────────────────────────────────────────────│ │
   │  │  After (nothing for RequestID — it's pre-only)               │ │
   │  └──────────────────────────────────────────────────────────────┘ │
   │                                                                   │
   │  ┌── Logger middleware ─────────────────────────────────────────┐ │
   │  │  Before next:                                                │ │
   │  │  1. Record start = time.Now()                                │ │
   │  │  2. Call next.ServeHTTP(ww, r) ────────────────────────────►│ │
   │  │◄─────────────────────────────────────────────────────────────│ │
   │  │  After:                                                      │ │
   │  │  3. duration = time.Since(start)                             │ │
   │  │  4. status = ww.Status()  (from wrapped ResponseWriter)      │ │
   │  │  5. log: method, path, status, duration, request_id          │ │
   │  └──────────────────────────────────────────────────────────────┘ │
   │                                                                   │
   │  ┌── Recoverer middleware ──────────────────────────────────────┐ │
   │  │  Before next:                                                │ │
   │  │  1. defer recover() block registered                         │ │
   │  │  2. Call next.ServeHTTP(w, r) ─────────────────────────────►│ │
   │  │◄─────────────────────────────────────────────────────────────│ │
   │  │  After:                                                      │ │
   │  │  3. If panic recovered: write 500, log stack trace           │ │
   │  │  4. If no panic: nothing                                     │ │
   │  └──────────────────────────────────────────────────────────────┘ │
   │                                                                   │
   │  ┌── Auth middleware ───────────────────────────────────────────┐ │
   │  │  Before next:                                                │ │
   │  │  1. Extract token from Authorization header                  │ │
   │  │  2. Validate token → if invalid: write 401, return (no next) │ │
   │  │  3. If valid: add user to context, call next(w, r) ────────►│ │
   │  │◄─────────────────────────────────────────────────────────────│ │
   │  │  After (nothing — auth is pre-only, or short-circuits)       │ │
   │  └──────────────────────────────────────────────────────────────┘ │
   │                                                                   │
   │  ┌── Handler ───────────────────────────────────────────────────┐ │
   │  │  Business logic, database query, response marshaling          │ │
   │  └──────────────────────────────────────────────────────────────┘ │
   │                                                                   │
   ▼                                                                   ▼
 Response to client
```

### Middleware Ordering Rules

The ordering of middleware is critical. Here are the rules:

| Middleware | Order | Rationale |
|-----------|-------|-----------|
| RequestID | Outermost | Must run first so all subsequent middleware/logs have the ID |
| Logger | After RequestID | Needs request ID for log correlation |
| Recoverer | After Logger, before everything else | Must catch panics from ALL below |
| Timeout | After Recoverer | Timeout should not prevent panic recovery |
| CORS | After potential auth short-circuit | CORS preflight (OPTIONS) should bypass auth |
| Auth | Before business logic | Must validate before handler executes |
| Rate Limiter | After Auth (if per-user), before auth (if per-IP) | Depends on limiting dimension |
| Compression | Outermost (but after Recoverer) | Compress everything going out |

**Wrong ordering example:**
```go
// BAD: Logger before RequestID — logs won't have request IDs
r.Use(middleware.Logger)
r.Use(middleware.RequestID)

// BAD: Recoverer after Auth — auth panic won't be caught
r.Use(AuthMiddleware)
r.Use(middleware.Recoverer)

// BAD: CORS after Auth — OPTIONS preflight hits Auth first
r.Use(AuthMiddleware)
r.Use(CORSMiddleware)
```

---

## Request Flow Diagrams

### Middleware Composition During Server Startup

```
    chi.NewRouter()
          │
          ▼
    ┌──────────────┐
    │  Mux created   │
    │  middlewares=[] │
    └──────┬───────┘
           │
    ┌──────▼───────────────────────────────────────────┐
    │  r.Use(RequestID)  →  mx.middlewares = [RequestID] │
    │  r.Use(Logger)     →  mx.middlewares = [RID, Log]  │
    │  r.Use(Recoverer)  →  mx.middlewares = [R,L,Rcv]   │
    │  r.Use(Timeout)    →  mx.middlewares = [R,L,Rc,T]  │
    │  r.Use(Auth)       →  mx.middlewares = [R,L,Rc,T,A]│
    └──────────────────────────────────────────────────┘
           │
    ┌──────▼───────────────────────────────────────────┐
    │  r.Get("/users/{id}", userHandler)                │
    │  → route registered in radix tree                 │
    └──────┬───────────────────────────────────────────┘
           │
           ▼
    http.ListenAndServe(":8080", r)
           │
           ▼
    ┌──────────────────────────────────────────────────┐
    │  Request arrives: GET /users/42                   │
    │  1. Radix tree resolves to userHandler            │
    │  2. Chain built: [R,L,Rc,T,A] + handler           │
    │  3. Chain.Handler(userHandler) called             │
    │     = RequestID(Logger(Recoverer(Timeout(Auth(handler)))))│
    │  4. Outermost.ServeHTTP(w, r) invoked             │
    └──────────────────────────────────────────────────┘
```

### Short-Circuit Flow (Auth Middleware Returns 401)

```
 Request arrives: GET /users/42 (no token)
          │
          ▼
  RequestID.ServeHTTP(w, r)
    │ before: set request_id = "abc123"
    │ call next(w, r_ctx)
  ────────────► Logger.ServeHTTP(w, r)
                  │ before: start timer
                  │ call next(ww, r)
                ───► Recoverer.ServeHTTP(w, r)
                      │ register defer recover()
                      │ call next(w, r)
                    ──► Timeout.ServeHTTP(w, r)
                          │ set deadline
                          │ call next(w, r)
                        ──► Auth.ServeHTTP(w, r)
                              │ Extract token → empty
                              │ WriteHeader(401)
                              │ Write("Unauthorized")
                              │ return  ← SHORT CIRCUIT
                        ◄──── Auth returns (no handler called)
                          │ (deadline cleanup)
                      ◄──── Timeout returns
                        │ (nothing recovered)
                    ◄──── Recoverer returns
                  │ after: log "GET /users/42 401 2ms abc123"
              ◄──── Logger returns
    │ after: nothing
◄─── RequestID returns

Response: 401 Unauthorized
```

---

## Lifecycle Diagrams

### Middleware Function Lifecycle

```
┌──────────────────────────────────────────────────────────────────────┐
│                    MIDDLEWARE EXECUTION LIFE CYCLE                    │
│                                                                       │
│  func MyMiddleware(next http.Handler) http.Handler {                  │
│      ┌─────────────────────────────────────────────────────────┐     │
│      │ SCOPED SETUP (runs once, at registration time)            │     │
│      │ - Create any shared state (rate limiter, cache)          │     │
│      │ - Initialize external connections (tracer, logger)       │     │
│      │ - Allocate pooled resources (buffer pools)              │     │
│      └─────────────────────────────────────────────────────────┘     │
│                                                                       │
│      return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { │
│          ┌─────────────────────────────────────────────────────┐     │
│          │ PER-REQUEST SETUP                                     │     │
│          │ - Extract/validate request data                      │     │
│          │ - Wrap ResponseWriter (if needed)                    │     │
│          │ - Add values to request context                      │     │
│          │ - Allocate per-request resources                     │     │
│          └─────────────────────────┬───────────────────────────┘     │
│                                    │                                  │
│                                    ▼                                  │
│                          next.ServeHTTP(w, r)                         │
│                                    │                                  │
│                                    ▼                                  │
│          ┌─────────────────────────┴───────────────────────────┐     │
│          │ PER-REQUEST TEARDOWN                                  │     │
│          │ - Read response status (from wrapped writer)         │     │
│          │ - Log/metrics emission                                │     │
│          │ - Release per-request resources                      │     │
│          │ - Modify response (only if not yet sent!)            │     │
│          └─────────────────────────────────────────────────────┘     │
│      })                                                               │
│  }                                                                    │
└──────────────────────────────────────────────────────────────────────┘
```

**Key distinction**: The outer function (closure) runs **once** at registration time. The inner `http.HandlerFunc` runs **per request**. Use the outer scope for shared state, the inner scope for per-request state.

### Chain Building Lifecycle

```
 Registration Phase                    Request Phase
 (once, startup)                       (once per request)

 ┌──────────────────┐                 ┌──────────────────────┐
 │ chi.NewChain(m1,m2)│                │ chain.Handler(h)     │
 │ middlewares=[m1,m2]│                │                      │
 └────────┬─────────┘                 │ for i:=1..0:         │
          │                           │   h = m[i](h)        │
          │                           │                      │
          │   chain stored             │ = m1(m2(h))          │
          │   on router               │                      │
          ▼                           └──────────┬───────────┘
 ┌──────────────────┐                            │
 │ r.Use(m3)         │                            ▼
 │ chain.middlewares  │                 ┌──────────────────────┐
 │  = [m1,m2,m3]     │                 │ result.ServeHTTP(w,r)│
 └──────────────────┘                 │ → m1's inner func     │
                                       │   → m2's inner func  │
                                       │     → m3's inner func│
                                       │       → h.ServeHTTP  │
                                       └──────────────────────┘
```

---

## Source Code Reading Guide

### Files to Read (In Order)

| Order | File | Lines | What to Focus On |
|-------|------|-------|------------------|
| 1 | `chi/chain.go` | ~80 | `Chain` struct, `NewChain`, `Handler`, `HandlerFunc`. The entire file — it's short and critical. |
| 2 | `chi/mux.go` (ServeHTTP) | ~50 | How the mux builds the chain at request time. See how mux-level and route-level middlewares compose. |
| 3 | `chi/middleware/recoverer.go` | ~60 | The canonical example of middleware. pre-handler: register defer. post-handler: recover, log, write 500. |
| 4 | `chi/middleware/logger.go` | ~150 | Wrapping `ResponseWriter` to capture status. Pre: start timer. Post: log. Demonstrates the wrapping pattern. |
| 5 | `chi/middleware/request_id.go` | ~40 | Pre-only middleware. Shows adding to context, setting response headers. |
| 6 | `chi/middleware/timeout.go` | ~80 | Demonstrates context-based cancellation. Uses `http.TimeoutHandler` internally. |
| 7 | `chi/middleware/heartbeat.go` | ~30 | Short-circuit pattern: check path, return 200, don't call next. |
| 8 | `chi/middleware/middleware.go` | ~30 | Utility functions: `AllowContentType`, `SetHeader`, `Compress`. |

### What to Ignore

- `chi/middleware/compress.go` — compression is covered in performance sessions.
- `chi/middleware/throttle.go` — rate limiting has its own session.
- `chi/middleware/realip.go` — proxy concerns.

### Deep-Dive: `next.ServeHTTP` and Control Flow

The most important line in every middleware is:

```go
next.ServeHTTP(w, r)
```

This single call:
1. **Transfers control** to the next middleware (or handler) in the chain.
2. **Blocks** until the next handler returns (synchronous).
3. **Must be called with the same or wrapped `w` and `r`** that the middleware received, or one derived from them.

Common mistakes:
- Calling `next.ServeHTTP(w, r)` in a goroutine (race on `w`).
- Not calling it at all (handler never runs).
- Calling it after writing a response (double-write, may panic or produce corrupted output).
- Modifying `w` headers after `next.ServeHTTP` returns (too late, headers already sent).

---

## Production Failure Scenarios

### Scenario 1: Logger Before RequestID

**Symptom**: Logs show empty request IDs despite RequestID middleware being registered.

**Root Cause**: The Logger middleware is registered before RequestID middleware. Logger reads the request ID from the context *before* RequestID has added it.

**Fix**:
```go
// WRONG
r.Use(middleware.Logger)
r.Use(middleware.RequestID)

// CORRECT
r.Use(middleware.RequestID)
r.Use(middleware.Logger)
```

### Scenario 2: Double Response Write

**Symptom**: `http: superfluous response.WriteHeader call` in logs, or `panic: http: multiple response.WriteHeader calls`.

**Root Cause**: A middleware writes a response header (e.g., 401) but then calls `next.ServeHTTP(w, r)`, which also writes a header. The second call causes a panic or log warning.

**Fix**: When short-circuiting, **do not call `next.ServeHTTP`**:
```go
// WRONG
w.WriteHeader(401)
next.ServeHTTP(w, r)  // handler will try to write header again

// CORRECT
w.WriteHeader(401)
w.Write([]byte(`{"error":"unauthorized"}`))
return  // no next.ServeHTTP
```

### Scenario 3: Panic After Response Written

**Symptom**: A handler panics after writing a 200 response. The Recoverer catches the panic and writes a 500, but the connection is already closed or the client receives a truncated 200.

**Root Cause**: Recoverer can catch the panic but cannot "un-send" the 200 response. The client already received headers.

**Mitigation**: Buffer the response body (with a middleware) and only flush after the handler completes. This adds latency but prevents partial responses. Chi's middleware does not do this by default because it breaks streaming.

### Scenario 4: Request Context Leak Between Requests

**Symptom**: A user receives data belonging to another user intermittently.

**Root Cause**: A middleware stores request-scoped data in a closure variable instead of the request context:

```go
// WRONG — closure variable shared across requests
var currentUser *User
r.Use(func(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        currentUser = getUserFromToken(r)  // RACE! shared across requests
        next.ServeHTTP(w, r)
    })
})
```

**Fix**: Always use `context.WithValue` for request-scoped data:
```go
r.Use(func(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        user := getUserFromToken(r)
        ctx := context.WithValue(r.Context(), userKey, user)
        next.ServeHTTP(w, r.WithContext(ctx))
    })
})
```

---

## Debugging Techniques

### 1. Trace Middleware Execution Order

```go
func TraceMiddleware(name string) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            fmt.Printf(">>> %s: entering\n", name)
            defer fmt.Printf("<<< %s: exiting\n", name)
            next.ServeHTTP(w, r)
        })
    }
}

// Use at each layer
r.Use(TraceMiddleware("RequestID"))
r.Use(TraceMiddleware("Logger"))
r.Use(TraceMiddleware("Auth"))
```

Output for a successful request:
```
>>> RequestID: entering
>>> Logger: entering
>>> Auth: entering
<<< Auth: exiting
<<< Logger: exiting
<<< RequestID: exiting
```

### 2. Inspect the Composed Handler

```go
func InspectChain(chain chi.Chain) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        fmt.Printf("Chain: %T\n", chain)
        fmt.Printf("Middleware count: %d\n", len(chain.Middlewares()))
        
        for i, mw := range chain.Middlewares() {
            name := runtime.FuncForPC(reflect.ValueOf(mw).Pointer()).Name()
            fmt.Printf("  [%d] %s\n", i, name)
        }
    })
}
```

### 3. Test Middleware in Isolation

```go
func TestMyMiddleware(t *testing.T) {
    // Mock handler that records it was called
    called := false
    mockHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        called = true
        w.WriteHeader(200)
    })

    mw := MyMiddleware()
    wrapped := mw(mockHandler)

    req := httptest.NewRequest("GET", "/", nil)
    rec := httptest.NewRecorder()
    wrapped.ServeHTTP(rec, req)

    assert.True(t, called)
    assert.Equal(t, 200, rec.Code)
}
```

### 4. Wrapping ResponseWriter to Debug Status

```go
type debugResponseWriter struct {
    http.ResponseWriter
    wroteHeader bool
    status      int
}

func (d *debugResponseWriter) WriteHeader(code int) {
    if d.wroteHeader {
        fmt.Printf("WARNING: double WriteHeader call! First: %d, Second: %d\n", d.status, code)
    }
    d.wroteHeader = true
    d.status = code
    d.ResponseWriter.WriteHeader(code)
}

func (d *debugResponseWriter) Write(b []byte) (int, error) {
    if !d.wroteHeader {
        d.WriteHeader(200)  // implicit 200
    }
    fmt.Printf("DEBUG: writing %d bytes with status %d\n", len(b), d.status)
    return d.ResponseWriter.Write(b)
}
```

---

## Observability Considerations

### Middleware Latency Attribution

Each middleware should contribute a span or metric for its own execution time:

```go
func InstrumentedMiddleware(name string, next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        start := time.Now()
        next.ServeHTTP(w, r)
        duration := time.Since(start)
        middlewareLatency.WithLabelValues(name).Observe(duration.Seconds())
    })
}
```

This reveals if a specific middleware (e.g., auth token validation) is the latency bottleneck.

### Error Rate by Middleware

Track which middleware returns errors:

```go
func AuthMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        token := extractToken(r)
        if !validateToken(token) {
            authFailures.Inc()
            w.WriteHeader(401)
            return
        }
        authSuccesses.Inc()
        next.ServeHTTP(w, r)
    })
}
```

### Panic Recovery Rate

```go
func Recoverer(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        defer func() {
            if rv := recover(); rv != nil {
                panicsRecovered.Inc()
                // ... log stack trace ...
                w.WriteHeader(500)
            }
        }()
        next.ServeHTTP(w, r)
    })
}
```

A sudden spike in panics-recovered is an incident signal.

---

## Performance Implications

### Middleware Allocation Overhead

Each middleware wrapping creates a closure. For Chi's built-in middlewares, this is typically 1–3 allocations per request. For 5 middlewares, that's ~5–15 allocations per request.

At 10,000 RPS, this is 50,000–150,000 allocations/second — significant but usually not the bottleneck compared to JSON marshaling and database queries.

**Optimization**: For middlewares that are pure functions (no closure state needed), use `http.HandlerFunc` with a package-level function instead of a closure:

```go
// Allocation: 1 closure per request
func loggingMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        log.Println("request")
        next.ServeHTTP(w, r)
    })
}

// Zero allocation version (function pointer, no closure)
func loggingMiddlewareFunc(next http.Handler) http.Handler {
    return &loggingHandler{next: next}
}

type loggingHandler struct {
    next http.Handler
}

func (h *loggingHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    log.Println("request")
    h.next.ServeHTTP(w, r)
}
```

### Chain.Prepend vs Chain.Append

Chi's helper functions:
- `chain.Handler(h)` — appends `h` to the end (innermost).
- `chain.HandlerFunc(f)` — same, for `func(http.ResponseWriter, *http.Request)`.

There is no `Prepend` because middlewares are always applied in registration order (outermost first). If you need a middleware that runs after all others, register it last with `r.Use()`.

### Conditional Middleware

Don't build a separate chain for each condition — it defeats `sync.Pool` optimizations:

```go
// BAD: allocates a new chain per request
if needsAuth {
    handler = chi.Chain(authMiddleware).Handler(handler)
}

// GOOD: wrap conditionally in the handler
r.Use(func(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        if needsAuth(r) {
            authMiddleware(next).ServeHTTP(w, r)
        } else {
            next.ServeHTTP(w, r)
        }
    })
})
```

---

## Architecture Implications

### Middleware as Cross-Cutting Concern Enforcement

Middleware is the mechanism for enforcing invariants that span all handlers:
- Every request has a request ID.
- Every request is logged.
- Every panic is recovered.
- Every authenticated request has a valid user in the context.

When a new cross-cutting requirement emerges (e.g., "add distributed tracing to all services"), it should be implemented as middleware, not as a code change in every handler.

### Middleware Composition Patterns

**Pattern 1: The Stack (Chi's default)**
```go
r.Use(a, b, c)
// Result: a(b(c(handler)))
```

**Pattern 2: Conditional Stack**
```go
func Conditional(a, b func(http.Handler) http.Handler, condition func(*http.Request) bool) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            if condition(r) {
                a(b(next)).ServeHTTP(w, r)
            } else {
                next.ServeHTTP(w, r)
            }
        })
    }
}
```

**Pattern 3: Fan-Out (one middleware, many handlers)**
```go
r.Route("/api", func(r chi.Router) {
    r.Use(authMiddleware)
    // authMiddleware applies to all routes in this group
    r.Get("/users", listUsers)
    r.Get("/users/{id}", getUser)
})
```

### Avoid Framework Lock-In

Chi middlewares are `func(http.Handler) http.Handler` — the standard library type. This means:
- You can use Chi middlewares with any `http.Handler` (standard library, gorilla/mux, etc.).
- You can use any standard-library-compatible middleware with Chi.
- Your middleware is portable. If you migrate away from Chi, your middleware comes with you.

Contrast with Gin's `gin.HandlerFunc` or Echo's `echo.MiddlewareFunc` — those middlewares are framework-specific and cannot be reused.

---

## Team Ownership Implications

### Middleware as a Shared Library

Centralize production middleware in a shared `pkg/middleware` package:

```
pkg/middleware/
├── auth/
│   ├── jwt.go           # JWT validation
│   └── apikey.go        # API key validation
├── logging/
│   ├── structured.go    # Structured (slog/zerolog) logging
│   └── audit.go         # Audit trail logging
├── tracing/
│   ├── opentelemetry.go # OpenTelemetry span creation
│   └── baggage.go       # W3C Baggage propagation
└── common/
    ├── requestid.go     # Request ID generation
    ├── ratelimit.go     # Rate limiting
    └── cors.go          # CORS configuration
```

Each middleware should:
1. Be individually testable with `httptest`.
2. Export configuration structs (not global configs).
3. Follow semantic versioning (middleware API changes are breaking).
4. Have clear documentation about ordering requirements.

### Middleware Review Checklist

When a team proposes a new middleware, review for:
1. **Is it truly cross-cutting?** If only one handler needs it, it's not middleware.
2. **Does it respect the `next.ServeHTTP` contract?** Does it pass `w` and `r` through correctly?
3. **Does it allocate a closure per request?** If yes, can it be refactored to a struct method?
4. **Does it handle timeouts correctly?** If the request context is cancelled, does the middleware stop work?
5. **Does it set response headers before or after `next`?** Headers set after `next` are ignored.
6. **Does it panic?** If so, is there a Recoverer above it in the stack?

---

## Interview Questions

**Q1: What is the type signature of a Chi middleware, and why is this important?**

A: `func(http.Handler) http.Handler`. It's important because this is the standard library's middleware pattern — it's framework-agnostic, composable, and works with any `http.Handler`. This means Chi middlewares are portable.

**Q2: Explain the difference between pre-handler and post-handler logic in middleware, and give an example of each.**

A: Pre-handler logic (before `next.ServeHTTP`) executes on the way in — examples: authentication validation, request ID generation, request context enrichment. Post-handler logic (after `next.ServeHTTP`) executes on the way out — examples: logging response status/duration, adding response headers (before headers are sent in the right wrapping), metrics emission.

**Q3: What happens if a middleware calls `next.ServeHTTP(w, r)` twice?**

A: The entire chain from that point downward executes twice. This can cause double database queries, double response writes (which panic in net/http), and corrupted state. There is no guard against this — it's a logic error that must be caught in review.

**Q4: In what order should RequestID, Logger, Recoverer, Timeout, CORS, and Auth middleware be applied, and why?**

A: RequestID (outermost, so all logs have IDs), Logger (needs RequestID), Recoverer (catch panics from everything below), Timeout (after Recoverer, before Auth — don't timeout auth), CORS (preflight OPTIONS should bypass Auth), Auth (short-circuit before handler runs). CORS can also go before Timeout depending on requirements.

**Q5: How does Chi's `Chain` type differ from manually composing middlewares?**

A: `Chain` stores middlewares as a slice and composes them lazily when `Handler()` is called. It provides `Handler()` and `HandlerFunc()` convenience methods. The key value is that it can be built incrementally (via `r.Use()`) and attached to a router without immediate composition.

**Q6: How do you pass request-scoped data from middleware to a handler?**

A: Use `context.WithValue` on the request context, then pass the updated request via `next.ServeHTTP(w, r.WithContext(ctx))`. The handler retrieves the value with `ctx.Value(key)`. Never use closure variables — they are shared across concurrent requests.

**Q7: What are the performance implications of middleware?**

A: Each middleware adds function call overhead (~5-20ns per call) and typically 1 closure allocation per request. For 5 middlewares at 10k RPS, that's ~50k allocations/sec. This is usually negligible compared to business logic. The bigger risk is middleware that does slow synchronous work (e.g., external auth service calls) on the hot path.

**Q8: How do you test that middleware executes in the correct order?**

A: Use a shared buffer or slice. Each middleware appends its name before and after calling `next`. After processing a test request, assert the sequence matches expectations (e.g., `["RequestID:pre", "Logger:pre", "Auth:pre", "Handler", "Auth:post", "Logger:post", "RequestID:post"]`).

**Q9: What is the difference between mux-level middleware (`r.Use()`) and route-level middleware (`r.With()`) in terms of scoping?**

A: `r.Use()` applies middleware to ALL routes registered on that mux or its descendants. `r.With()` applies middleware only to the specific routes that follow it in the same chain. `r.Group()` creates a new mux that inherits parent middleware and allows adding group-specific middleware.

**Q10: Can you use Chi middleware with the standard library's `http.DefaultServeMux`?**

A: Yes. Chi middleware accepts `http.Handler` and returns `http.Handler`. You can wrap any handler:

```go
wrapped := chi.Chain(logger, auth).Handler(myHandler)
http.Handle("/api/", wrapped)
```

---

## Hands-On Exercises

### Exercise 1: Build a Response Timing Middleware

Implement a middleware that:
1. Records the start time before calling `next`.
2. Captures the response status code (by wrapping `ResponseWriter`).
3. After `next` returns, logs: method, path, status, duration, and request ID.
4. Emits a Prometheus histogram metric with labels for method, path, and status.

Test with multiple requests and verify the metric values.

### Exercise 2: Debug the Ordering

Given this router:

```go
r := chi.NewRouter()
r.Use(middlewareA)  // logs "A: in" then calls next, then logs "A: out"
r.Get("/", handlerB) // logs "B: handler"

r.Group(func(r chi.Router) {
    r.Use(middlewareC) // logs "C: in" then calls next, then logs "C: out"
    r.Get("/group", handlerD) // logs "D: handler"
})
```

Predict the log output for `GET /` and `GET /group`. Write a test to verify. Explain why the order is what it is.

### Exercise 3: Implement a Per-Request Rate Limiter Middleware

Implement a middleware that:
1. Extracts a client identifier (IP or API key) from the request.
2. Uses a token bucket or sliding window algorithm (use `golang.org/x/time/rate` or implement from scratch).
3. Returns 429 Too Many Requests with `Retry-After` header when limit exceeded.
4. Includes the current rate limit status in response headers (`X-RateLimit-Remaining`, `X-RateLimit-Limit`, `X-RateLimit-Reset`).

### Exercise 4: Write Middleware Unit Tests

Write comprehensive tests for a custom auth middleware:
- Test with a valid token → handler is called, user in context.
- Test with an expired token → 401, handler NOT called.
- Test with a malformed token → 400, handler NOT called.
- Test with no Authorization header → 401, handler NOT called.
- Test that the `WWW-Authenticate` header is set correctly.

### Exercise 5: Middleware Performance Benchmark

Create a router with 5 middleware layers (RequestID, Logger, Recoverer, Timeout, Auth). Benchmark:
1. Router with all 5 middlewares + handler.
2. Router with no middleware + handler.

Calculate the overhead per middleware. Profile with `go tool pprof` to identify allocation hotspots.

---

## Advanced Challenges

### Challenge 1: Implement a Context-Aware Middleware Scheduler

Design a middleware that can dynamically reorder or skip other middlewares based on request attributes:

```go
type RouteConfig struct {
    SkipAuth bool
    RateLimit int
}

// Middleware that reads config from request context and adjusts behavior
func DynamicMiddleware(configProvider func(*http.Request) RouteConfig) func(http.Handler) http.Handler {
    // Implementation: selectively skip middleware based on config
}
```

The challenge: Chi's middleware is applied at registration time, not request time. You need to build a single middleware that can emulate "skipping" other middlewares by conditionally calling them.

### Challenge 2: Implement an Async Post-Handler Pipeline

Design a middleware that captures the response and, after returning it to the client, executes post-processing work asynchronously:

```go
func AsyncPostProcess(next http.Handler, postProcess func(status int, body []byte)) http.Handler {
    // 1. Wrap ResponseWriter to capture response
    // 2. Call next
    // 3. After response is sent, spawn goroutine for postProcess
    // 4. Handle context cancellation (goroutine should not block forever)
}
```

Consider: how do you ensure the goroutine doesn't outlive the process? What about error handling in the async work?

### Challenge 3: Build a Middleware Compatibility Layer Between Chi and Gin/Echo/Fiber

Implement adapters that:
1. Allow Gin middlewares to be used in Chi routers.
2. Allow Chi middlewares to be used in Echo routers.
3. Handle the different context types (`gin.Context`, `echo.Context`, `context.Context`).

This requires understanding each framework's middleware model deeply and mapping between their abstractions.

---

## Key Insights

1. **Chi middleware is `func(http.Handler) http.Handler`** — the standard library pattern. This is not an accident. It's a deliberate design choice that ensures portability, composability, and zero framework lock-in.

2. **`next.ServeHTTP(w, r)` is the single most important line in the middleware pipeline.** Everything before it is pre-processing; everything after it is post-processing; not calling it means short-circuiting. The `w` and `r` passed to `next` are what downstream handlers see — wrapping them is how middleware communicates.

3. **Middleware ordering is not arbitrary — it's a contract.** RequestID before Logger. Recoverer before everything else. CORS before Auth. Getting the order wrong produces bugs that are hard to diagnose because they manifest as incorrect behavior in unrelated components.

4. **The outer function (closure) runs once at registration; the inner function runs per request.** Use the outer scope for shared state (rate limiter buckets, connection pools). Use the inner scope for per-request state (user, request ID). Never use the outer scope for per-request state — it's shared across concurrent requests.

5. **Chi's `Chain` type is a lightweight composition helper, not a framework requirement.** You can compose middlewares manually (`m1(m2(m3(h)))`) and get identical behavior. `Chain` just makes incremental construction cleaner.

6. **Middleware is the mechanism for enforcing cross-cutting concerns.** Every time a team says "we need to add [X] to every endpoint," the answer should be middleware, not a copy-paste into every handler.

7. **The `http.ResponseWriter` wrapping pattern is critical for post-handler middleware behavior.** To capture status codes, buffer responses, compress output, or detect double-writes, you must wrap `ResponseWriter` before passing it to `next`. Chi's Logger middleware is the canonical example.

8. **Portability is a superpower.** Because Chi middleware uses the standard library signature, you can move your middleware to any Go HTTP framework, use any third-party middleware, and test middleware in isolation with httptest — no mocking framework required.
