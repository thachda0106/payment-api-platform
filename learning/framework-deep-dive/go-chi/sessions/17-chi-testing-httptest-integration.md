# Session 17: Chi Testing with httptest — Handler Testing, Middleware Testing, Integration Testing

## Why This Topic Exists

HTTP handlers are the outermost layer of your application — they parse requests, delegate to services, and write responses. In a well-architected Go service, handlers contain minimal logic: input validation, service call, response rendering. This makes them highly testable. Yet handler tests are often neglected in favor of service-layer tests, leading to bugs in JSON serialization, status codes, header handling, and middleware interactions that only surface in integration or production environments.

`net/http/httptest` is the standard library's answer to HTTP handler testing. It provides `ResponseRecorder` (a fake `http.ResponseWriter`), `NewRequest` (a request factory with body, headers, and context), and `NewServer` (a real HTTP server on a random port). Combined with Chi's router testing patterns, these tools enable fast, isolated, and comprehensive handler tests without starting a real server or making real network calls.

As a Staff/Principal Engineer, you will establish the testing standards for your team. You will decide whether handlers are tested in isolation or through integration tests. You will review test code that asserts on raw JSON strings (fragile) vs structured assertions (robust). You will debug a race condition that only appears under `go test -race`. You will design the test strategy that gives your team confidence to deploy on Friday at 5 PM.

---

## Mental Model

### The Handler Test Triangle

Handler tests exist in three layers of isolation:

```
                    ┌─────────────────────┐
                    │  Integration Tests   │  ← Real server, real HTTP, real DB
                    │  (httptest.NewServer)│     Slow, comprehensive, fewer of these
                    │                     │
                    │  ┌─────────────────┐ │
                    │  │ Route Group Tests│ │  ← Full router, mock services
                    │  │ (chi.NewRouter) │ │     Verify middleware, routing, URL params
                    │  │                 │ │
                    │  │ ┌─────────────┐ │ │
                    │  │ │Handler Tests│ │ │  ← Single handler, no middleware
                    │  │ │(ht.HandlerFunc)│ │  Fast, isolated, many of these
                    │  │ └─────────────┘ │ │
                    │  └─────────────────┘ │
                    └─────────────────────┘
```

**Handler Tests**: Test a single `http.HandlerFunc` in isolation. Call it directly with a `ResponseRecorder` and `*http.Request`. Verify status code, headers, and body. These tests run in microseconds.

**Route Group Tests**: Create a `chi.NewRouter()`, register the handler + its middleware, and serve a request. Verify middleware effects (auth checks, header injection), URL parameter extraction, and routing correctness. These tests run in hundreds of microseconds.

**Integration Tests**: Use `httptest.NewServer` to start a real HTTP server with the full router. Make real HTTP requests with `http.Client`. Verify end-to-end behavior including real database, real external service mocks, and real JSON serialization. These tests run in milliseconds to seconds.

### The Test Request Lifecycle

```
Test function
    │
    ├─→ Create handler/mock dependencies
    │   └─→ serviceMock := new(MockOrderService)
    │
    ├─→ Create the handler (inject mocks)
    │   └─→ handler := NewOrderHandler(serviceMock)
    │
    ├─→ Create request
    │   └─→ req := httptest.NewRequest("GET", "/orders/42", nil)
    │       (Also: req.Header.Set(), req = req.WithContext(ctx))
    │
    ├─→ Create response recorder
    │   └─→ rec := httptest.NewRecorder()
    │
    ├─→ Create router (if testing with middleware/routing)
    │   └─→ r := chi.NewRouter()
    │       r.Use(middleware.RequestID)
    │       r.Get("/orders/{id}", handler.GetOrder)
    │
    ├─→ Serve the request
    │   └─→ r.ServeHTTP(rec, req)
    │
    └─→ Assert on the recorder
        ├─→ rec.Code → assert.Equal(t, 200, rec.Code)
        ├─→ rec.Header() → assert.Equal(t, "application/json", rec.Header().Get("Content-Type"))
        ├─→ rec.Body.String() → assert.JSONEq(t, expected, rec.Body.String())
        └─→ rec.Result() → full *http.Response for inspecting cookies, trailers
```

---

## Internal Architecture

### httptest.ResponseRecorder: Capture Everything

`ResponseRecorder` implements `http.ResponseWriter` and records everything written to it:

```go
type ResponseRecorder struct {
    Code      int           // HTTP status code (defaults to 200)
    HeaderMap http.Header   // Response headers
    Body      *bytes.Buffer // Response body bytes
    Flushed   bool          // Whether Flush() was called
    // ... internal fields for hijack detection, etc.
}
```

**Key implementation details:**

1. **Default status code**: If `WriteHeader()` is never called, `Code` defaults to 200. `Write()` calls `WriteHeader(200)` if it hasn't been called yet. This matches `net/http` server behavior.

2. **Header() returns the map**: `HeaderMap` is initialized lazily (on first access) and is the same map for the lifetime of the recorder. Headers set before `WriteHeader()` are retained; headers set after are ignored (matching `net/http` behavior).

3. **Body accumulates writes**: Each `Write()` call appends to `Body` (`bytes.Buffer`). The `.String()` method returns the full body. The `.Bytes()` method returns the raw bytes.

4. **Result() method** (Go 1.7+): Returns a full `*http.Response` constructed from `Code`, `HeaderMap`, and `Body`. This is useful when you need to pass the response to a function that expects `*http.Response`:

```go
rec := httptest.NewRecorder()
handler.ServeHTTP(rec, req)
resp := rec.Result()
// resp.StatusCode, resp.Header, resp.Body (io.ReadCloser over rec.Body.Bytes())
defer resp.Body.Close()
```

5. **Flush detection**: `ResponseRecorder` tracks whether `Flush()` was called via the `Flushed` field. This is important for testing streaming responses and SSE (Server-Sent Events).

### httptest.NewRequest: Request Factory

```go
func NewRequest(method, target string, body io.Reader) *http.Request
```

`NewRequest` is a wrapper around `http.NewRequest` that panics on error (instead of returning one). This is safe in tests because the inputs are hard-coded — if you pass an invalid method or URL, your test should fail immediately.

**Important traits:**

1. **Context**: The returned request has a `context.Background()` context. Override it with `req.WithContext(ctx)` to inject request-scoped values or cancellation.

2. **Host**: Defaults to `"example.com"`. If your handler examines `r.Host`, set it explicitly: `req.Host = "api.example.com"`.

3. **RemoteAddr**: Defaults to `"192.0.2.1:1234"` (from `net/http/httptest`). Override for IP-based tests:

```go
req.RemoteAddr = "10.0.0.1:56789"
```

4. **Body**: The body reader is NOT automatically closed. If you read `r.Body` in your handler, the test won't leak resources — `bytes.Reader` and `strings.Reader` don't need closing.

5. **Content-Length**: Set automatically if the body implements `io.Seeker` (which `bytes.Reader` and `strings.Reader` do). For streaming bodies, set manually:

```go
body := strings.NewReader(`{"name": "test"}`)
req := httptest.NewRequest("POST", "/users", body)
req.ContentLength = body.Size() // bytes.Reader has Size()
req.Header.Set("Content-Type", "application/json")
```

### Table-Driven Tests: The Go Testing Pattern

Go's standard testing pattern applied to Chi handlers:

```go
func TestCreateUserHandler(t *testing.T) {
    tests := []struct {
        name           string
        requestBody    string
        mockSetup      func(*MockUserService)
        expectedStatus int
        expectedBody   string
    }{
        {
            name:        "valid user creation",
            requestBody: `{"name": "Alice", "email": "alice@example.com"}`,
            mockSetup: func(svc *MockUserService) {
                svc.On("CreateUser", mock.Anything, mock.Anything).
                    Return(&User{ID: "1", Name: "Alice"}, nil)
            },
            expectedStatus: http.StatusCreated,
            expectedBody:   `{"id":"1","name":"Alice","email":"alice@example.com"}`,
        },
        {
            name:        "duplicate email",
            requestBody: `{"name": "Bob", "email": "bob@example.com"}`,
            mockSetup: func(svc *MockUserService) {
                svc.On("CreateUser", mock.Anything, mock.Anything).
                    Return(nil, ErrDuplicateEmail)
            },
            expectedStatus: http.StatusConflict,
            expectedBody:   `{"error":"conflict","message":"email already exists"}`,
        },
        {
            name:           "invalid JSON",
            requestBody:    `{invalid`,
            expectedStatus: http.StatusBadRequest,
            expectedBody:   `{"error":"bad_request","message":"invalid request body"}`,
        },
    }

    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            svc := new(MockUserService)
            if tt.mockSetup != nil {
                tt.mockSetup(svc)
            }

            handler := NewUserHandler(svc)
            r := chi.NewRouter()
            r.Post("/users", handler.CreateUser)

            req := httptest.NewRequest("POST", "/users", strings.NewReader(tt.requestBody))
            req.Header.Set("Content-Type", "application/json")
            rec := httptest.NewRecorder()

            r.ServeHTTP(rec, req)

            assert.Equal(t, tt.expectedStatus, rec.Code)
            if tt.expectedBody != "" {
                assert.JSONEq(t, tt.expectedBody, rec.Body.String())
            }
        })
    }
}
```

**Table-driven test structure rules:**
- Each test case has a `name` that describes the scenario (used as `t.Run` name)
- The `mockSetup` function configures mock expectations for THAT specific case — not shared across cases
- Expected status and body are explicit — no "default" expectations
- Use `t.Run()` to create sub-tests, enabling `go test -run TestCreateUserHandler/valid` to run a single case

### Middleware Testing: Mock Handler Wrapper

Testing middleware requires verifying it alters the request/response correctly:

```go
func TestAuthMiddleware(t *testing.T) {
    tests := []struct {
        name          string
        authHeader    string
        mockAuthSetup func(*MockAuthService)
        expectNext    bool       // Whether next handler was called
        expectedCtx   func(context.Context) bool // Verify context values
        expectedCode  int
    }{
        {
            name:       "valid token",
            authHeader: "Bearer valid-token",
            mockAuthSetup: func(svc *MockAuthService) {
                svc.On("ValidateToken", "valid-token").
                    Return(&User{ID: "123"}, nil)
            },
            expectNext: true,
            expectedCtx: func(ctx context.Context) bool {
                user, ok := ctx.Value("user").(*User)
                return ok && user.ID == "123"
            },
            expectedCode: http.StatusOK,
        },
        {
            name:       "expired token",
            authHeader: "Bearer expired-token",
            mockAuthSetup: func(svc *MockAuthService) {
                svc.On("ValidateToken", "expired-token").
                    Return(nil, ErrTokenExpired)
            },
            expectNext: false, // Next handler NOT called
            expectedCode: http.StatusUnauthorized,
        },
    }

    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            svc := new(MockAuthService)
            if tt.mockAuthSetup != nil {
                tt.mockAuthSetup(svc)
            }

            middleware := NewAuthMiddleware(svc)

            // Create a mock handler that records it was called and what it received
            var nextCalled bool
            nextHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
                nextCalled = true
                if tt.expectedCtx != nil {
                    assert.True(t, tt.expectedCtx(r.Context()),
                        "context does not contain expected values")
                }
                w.WriteHeader(http.StatusOK)
            })

            // Wrap with middleware
            wrapped := middleware(nextHandler)

            req := httptest.NewRequest("GET", "/protected", nil)
            if tt.authHeader != "" {
                req.Header.Set("Authorization", tt.authHeader)
            }
            rec := httptest.NewRecorder()

            wrapped.ServeHTTP(rec, req)

            assert.Equal(t, tt.expectNext, nextCalled,
                "next handler called state mismatch")
            assert.Equal(t, tt.expectedCode, rec.Code)
        })
    }
}
```

**The "mock handler" pattern:** Create an `http.HandlerFunc` that records:
- Whether it was called (`nextCalled` boolean)
- What context it received (for middleware that injects context values)
- What request it received (for middleware that modifies the request)

If the middleware short-circuits (doesn't call `next.ServeHTTP`), `nextCalled` stays false. This is the single most important assertion for auth middleware, rate limiters, and CORS middleware.

### Route Group Testing: Middleware Inheritance and URL Parameters

Testing route groups verifies:
1. Middleware fires in the expected order
2. URL parameters are correctly extracted through nested routes
3. Subrouter isolation works (middleware on one group doesn't leak to another)

```go
func TestNestedRouteParameters(t *testing.T) {
    r := chi.NewRouter()

    r.Route("/organizations/{orgID}", func(r chi.Router) {
        r.Use(func(next http.Handler) http.Handler {
            return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
                orgID := chi.URLParam(r, "orgID")
                ctx := context.WithValue(r.Context(), "orgID", orgID)
                next.ServeHTTP(w, r.WithContext(ctx))
            })
        })

        r.Route("/users/{userID}", func(r chi.Router) {
            r.Get("/", func(w http.ResponseWriter, r *http.Request) {
                resp := map[string]string{
                    "orgID":  chi.URLParam(r, "orgID"),
                    "userID": chi.URLParam(r, "userID"),
                }
                json.NewEncoder(w).Encode(resp)
            })
        })
    })

    req := httptest.NewRequest("GET", "/organizations/acme/users/42", nil)
    rec := httptest.NewRecorder()
    r.ServeHTTP(rec, req)

    assert.Equal(t, http.StatusOK, rec.Code)

    var resp map[string]string
    json.Unmarshal(rec.Body.Bytes(), &resp)
    assert.Equal(t, "acme", resp["orgID"])
    assert.Equal(t, "42", resp["userID"])
}
```

**Testing middleware inheritance:**

```go
func TestMiddlewareInheritance(t *testing.T) {
    var executionOrder []string

    parentMiddleware := func(name string) func(http.Handler) http.Handler {
        return func(next http.Handler) http.Handler {
            return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
                executionOrder = append(executionOrder, name+"_pre")
                next.ServeHTTP(w, r)
                executionOrder = append(executionOrder, name+"_post")
            })
        }
    }

    r := chi.NewRouter()
    r.Use(parentMiddleware("root"))

    r.Route("/api", func(r chi.Router) {
        r.Use(parentMiddleware("api"))

        r.Get("/data", func(w http.ResponseWriter, r *http.Request) {
            executionOrder = append(executionOrder, "handler")
        })
    })

    req := httptest.NewRequest("GET", "/api/data", nil)
    rec := httptest.NewRecorder()
    r.ServeHTTP(rec, req)

    expected := []string{"root_pre", "api_pre", "handler", "api_post", "root_post"}
    assert.Equal(t, expected, executionOrder)
}
```

### Integration Tests: Real HTTP with httptest.NewServer

```go
func TestIntegrationFullRouter(t *testing.T) {
    // Setup real dependencies (or containerized DB, or mock server)
    db := setupTestDB(t)
    defer db.Close()

    // Build real service with real DB
    userRepo := postgres.NewUserRepo(db)
    userService := users.NewService(userRepo)
    userHandler := users.NewHandler(userService)

    // Build full router
    r := chi.NewRouter()
    r.Use(middleware.RequestID)
    r.Use(middleware.Logger)
    r.Use(middleware.Recoverer)

    r.Route("/api/v1", func(r chi.Router) {
        r.Route("/users", func(r chi.Router) {
            r.Post("/", userHandler.Create)
            r.Get("/{id}", userHandler.GetByID)
            r.Get("/", userHandler.List)
        })
    })

    // Start real HTTP server on random port
    ts := httptest.NewServer(r)
    defer ts.Close()

    // Make real HTTP requests
    client := ts.Client() // *http.Client with test server transport

    // Test create
    body := strings.NewReader(`{"name":"Alice","email":"alice@example.com"}`)
    resp, err := client.Post(ts.URL+"/api/v1/users", "application/json", body)
    require.NoError(t, err)
    defer resp.Body.Close()

    assert.Equal(t, http.StatusCreated, resp.StatusCode)

    var created map[string]interface{}
    json.NewDecoder(resp.Body).Decode(&created)
    userID := created["id"].(string)

    // Test get by ID
    resp, err = client.Get(ts.URL + "/api/v1/users/" + userID)
    require.NoError(t, err)
    defer resp.Body.Close()

    assert.Equal(t, http.StatusOK, resp.StatusCode)
}
```

`httptest.NewServer` benefits:
- Real TCP connections, real HTTP transport, real goroutines
- Test server runs on `127.0.0.1` with random port (no port conflicts)
- `ts.Client()` returns an `*http.Client` that dials the test server directly (no network, no DNS)
- Errors like connection refused, timeout, and write-after-close are real — you can test error handling for network failures

### Benchmark Tests for Handlers

```go
func BenchmarkGetUserHandler(b *testing.B) {
    svc := new(MockUserService)
    svc.On("GetUser", mock.Anything, "42").
        Return(&User{ID: "42", Name: "Alice"}, nil)

    handler := NewUserHandler(svc)
    r := chi.NewRouter()
    r.Get("/users/{id}", handler.GetByID)

    req := httptest.NewRequest("GET", "/users/42", nil)
    rec := httptest.NewRecorder()

    b.ResetTimer()
    for i := 0; i < b.N; i++ {
        rec = httptest.NewRecorder() // Reset recorder per iteration
        r.ServeHTTP(rec, req)
    }
}
```

**Benchmark flags to use:**
```bash
go test -bench=. -benchmem -benchtime=10s -count=5
# -benchmem: report memory allocations per operation
# -benchtime: run benchmarks for at least 10 seconds
# -count: run each benchmark 5 times for statistical significance
```

**What to measure in handler benchmarks:**
- Allocations per request (`allocs/op`)
- Bytes per request (`B/op`)
- Nanoseconds per request (`ns/op`)

Typical results for a well-optimized handler:
```
BenchmarkGetUserHandler-8    100000    15000 ns/op    2048 B/op    24 allocs/op
```

If `allocs/op` exceeds 50 for a simple handler, profile and optimize. Common culprits:
- `fmt.Sprintf` in hot paths (use `strconv.AppendInt` or manual byte buffer)
- Repeated JSON encoding/decoding (use `json.NewEncoder`/`json.NewDecoder` with pre-allocated buffer)
- `time.Now()` for every debug log (use `slog.LogAttrs` or defer time capture)

### Race Detection

```bash
go test -race ./...
```

Race detection instruments all memory accesses and detects concurrent read/write conflicts. It slows tests by 5-10x and increases memory by 5-10x, but catches data races that are invisible in normal test runs.

**Common race conditions in handlers:**
1. Shared state in handler structs accessed by concurrent goroutines
2. `http.ResponseWriter` accessed after `ServeHTTP` returns (async response writing)
3. Mock expectations (`testify/mock.On()`) accessed concurrently in parallel subtests

```go
func TestConcurrentRequests(t *testing.T) {
    handler := NewCounterHandler() // Has a shared counter field

    var wg sync.WaitGroup
    for i := 0; i < 100; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            req := httptest.NewRequest("GET", "/count", nil)
            rec := httptest.NewRecorder()
            handler.ServeHTTP(rec, req)
        }()
    }
    wg.Wait()
}
```

Run this with `-race` to detect unsynchronized access to the shared counter.

### Coverage

```bash
go test -coverprofile=coverage.out ./...
go tool cover -html=coverage.out -o coverage.html
```

**Coverage targets for Chi handlers:**
- Handler functions: 90%+ (they're small and testable)
- Middleware: 90%+ (test with mock next handler)
- Route registration: Not worth targeting (registering routes is declarative)
- Error response helpers: 100% (they're pure functions)

---

## Runtime Behavior

### How httptest.Server Handles Concurrent Requests

`httptest.NewServer` uses `net.Listen("tcp", "127.0.0.1:0")` to bind a real TCP port. Each request creates a real `net.Conn`. The server runs in a goroutine. Requests are handled just like a production server: `srv.Serve(listener)` calls `Accept()` in a loop, spawns goroutines for each connection, and routes through `http.Handler.ServeHTTP`.

The key difference from a production server: `httptest.Server` uses a `http.Server` with zero timeouts by default. Long-running test handlers will block the test indefinitely unless you:
- Use `context.WithTimeout` in the test request
- Call `ts.Close()` to forcefully shut down the server
- Set `ts.Config.ReadTimeout` / `ts.Config.WriteTimeout`

### Recorder State Between Test Iterations

In benchmarks, recreating `httptest.NewRecorder()` per iteration (as shown above) is essential because:
1. `Recorder.Body` accumulates writes across calls (it's a `bytes.Buffer` that grows)
2. `Recorder.HeaderMap` persists across calls (subsequent headers merge with previous)
3. `Recorder.Code` persists (defaults to 200, but if a previous iteration called `WriteHeader(500)`, it stays 500)

In table-driven tests with `t.Run()`, each subtest gets its own scope, so recorder reuse is less dangerous — but creating a fresh recorder per case is the safest pattern:

```go
for _, tt := range tests {
    t.Run(tt.name, func(t *testing.T) {
        rec := httptest.NewRecorder() // Fresh per subtest
        // ...
    })
}
```

### ResponseWriter Hijacking in Tests

`httptest.ResponseRecorder` does NOT support `http.Hijacker` by default. If your handler attempts to hijack the connection (WebSocket upgrade), it will panic. Use `httptest.NewServer` for WebSocket tests:

```go
func TestWebSocketUpgrade(t *testing.T) {
    ts := httptest.NewServer(websocketHandler)
    defer ts.Close()

    // Use gorilla/websocket or nhooyr.io/websocket client to dial
    url := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
    conn, _, err := websocket.DefaultDialer.Dial(url, nil)
    require.NoError(t, err)
    defer conn.Close()
    // ... test websocket interactions
}
```

---

## Flow Diagrams

### Handler Test Flow

```
┌──────────────────────────────────────────────────┐
│ 1. Create mock service dependencies              │
│    svc := new(MockUserService)                   │
│    svc.On("GetUser", "42").Return(user, nil)     │
├──────────────────────────────────────────────────┤
│ 2. Create handler with injected mocks            │
│    handler := NewUserHandler(svc)                │
├──────────────────────────────────────────────────┤
│ 3. Create request                                │
│    req := httptest.NewRequest("GET", "/users/42", nil) │
│    req.Header.Set("Authorization", "Bearer ...") │
├──────────────────────────────────────────────────┤
│ 4. Create recorder                               │
│    rec := httptest.NewRecorder()                 │
├──────────────────────────────────────────────────┤
│ 5. Create router (optional, for middleware/routing) │
│    r := chi.NewRouter()                          │
│    r.Use(middleware.RequestID)                   │
│    r.Get("/users/{id}", handler.GetByID)         │
├──────────────────────────────────────────────────┤
│ 6. Serve                                         │
│    r.ServeHTTP(rec, req)                         │
├──────────────────────────────────────────────────┤
│ 7. Assert                                        │
│    assert.Equal(t, 200, rec.Code)                │
│    assert.JSONEq(t, `{"id":"42"}`, rec.Body.String()) │
│    assert.Equal(t, "application/json",           │
│        rec.Header().Get("Content-Type"))         │
│    svc.AssertExpectations(t)                     │
└──────────────────────────────────────────────────┘
```

### Integration Test Flow

```
┌──────────────────────────────────────────────────┐
│ 1. Setup real dependencies                       │
│    db := setupTestDB(t)                           │
│    defer db.Close()                               │
├──────────────────────────────────────────────────┤
│ 2. Build full application                        │
│    repo := postgres.NewUserRepo(db)              │
│    service := users.NewService(repo)             │
│    handler := users.NewHandler(service)          │
│    r := chi.NewRouter()                          │
│    r.Use(middleware.RequestID)                   │
│    r.Mount("/api/v1/users", handler.Routes())    │
├──────────────────────────────────────────────────┤
│ 3. Start test server                             │
│    ts := httptest.NewServer(r)                   │
│    defer ts.Close()                              │
├──────────────────────────────────────────────────┤
│ 4. Make HTTP requests                            │
│    resp, err := http.Post(ts.URL+"/api/v1/users",│
│        "application/json", body)                 │
├──────────────────────────────────────────────────┤
│ 5. Assert on response                            │
│    assert.Equal(t, 201, resp.StatusCode)         │
│    var created User                              │
│    json.NewDecoder(resp.Body).Decode(&created)    │
│    assert.NotEmpty(t, created.ID)                │
└──────────────────────────────────────────────────┘
```

### Benchmark Flow

```
Benchmark function
    │
    ├─→ b.N times loop
    │   │
    │   ├─→ rec := httptest.NewRecorder()  ← Reset state
    │   ├─→ r.ServeHTTP(rec, req)          ← Execute handler
    │   │
    │   └─→ (NO assertions — assertions add noise to benchmark)
    │
    └─→ b.ReportMetric(total_bytes / b.N, "bytes/op")
```

**Benchmark-specific rules:**
- Do NOT assert in benchmarks — assertions are slow and skew results
- Pre-allocate memory outside `b.ResetTimer()` — initialization cost shouldn't be measured
- Use `b.ReportAllocs()` or `-benchmem` flag to report allocations
- Run with `-count=5` and compare results with `benchstat` tool

---

## Source Code Reading Guide

**Reading order (estimate: 2-3 hours for deep understanding):**

1. **`net/http/httptest/recorder.go`** (entire file, ~150 lines) — `ResponseRecorder` struct and methods. Understand how `WriteHeader`, `Write`, `Header`, `Flush`, and `Result` work. This is the foundation for all handler tests.

2. **`net/http/httptest/server.go:1-120`** — `Server` struct, `NewServer()`, `NewTLSServer()`, `Start()`, `Close()`, `Client()`. Understand how the test server creates a real listener, serves HTTP, and provides a configured client.

3. **`net/http/httptest/httptest.go`** (entire file, ~80 lines) — `NewRequest()`. Understand how it wraps `http.NewRequest` and sets up test defaults (RemoteAddr, Host).

4. **`net/http/httptest/example_test.go`** — Official examples. Read `ExampleResponseRecorder`, `ExampleServer`, and any handlers.

5. **`chi/mux_test.go`** (selected portions) — Chi's own tests for routing, middleware, and URL params. Focus on how Chi tests group nesting, method not allowed, and parameter extraction.

6. **`chi/middleware/` test files** — Choose one middleware (`recoverer_test.go`, `logger_test.go`) and read its tests. Understand how Chi tests middleware: what mock patterns they use, what assertions they make.

**What to skip on first read:**
- `net/http/httptest` edge cases (trailers, 1xx responses) — rare in practice
- Benchmark code in Chi's test files — read for performance insight, not test patterns
- Go standard library test utilities like `testing/quick` — irrelevant to handler testing

---

## Production Failure Scenarios

### Scenario 1: Flaky Integration Test Due to Port Exhaustion

**What happened:** A test suite with 200 integration tests, each calling `httptest.NewServer()`, ran fine locally but failed in CI with "address already in use" after 30 minutes of runtime.

**Root cause:** `httptest.NewServer()` binds to `127.0.0.1:0` which returns a random available port from the ephemeral range. On Linux, the ephemeral port range is typically 32768-60999 (~28,000 ports). Each `Close()` releases the port, but the kernel keeps it in `TIME_WAIT` state for 60 seconds (2 * MSL). With 200 tests running in parallel, the test suite consumed ports faster than the kernel recycled them.

**Fix:**
1. Reuse servers across test cases with `t.Cleanup(ts.Close)` instead of per-test servers
2. Increase the ephemeral port range: `echo 1024 65535 > /proc/sys/net/ipv4/ip_local_port_range`
3. Reduce `TIME_WAIT` duration: `echo 1 > /proc/sys/net/ipv4/tcp_tw_reuse` (Linux)
4. Set `SO_REUSEADDR` on the test server listener (requires custom `httptest.Server` config)

### Scenario 2: Race Condition Only in -race Test

**What happened:** A handler function used a closure over a loop variable `tt` in parallel subtests:

```go
// BUG: Race condition
for _, tt := range tests {
    t.Run(tt.name, func(t *testing.T) {
        t.Parallel()
        // Handler closure captures `tt` — shared across goroutines
        handler := NewHandler(tt.config) // BUG: tt is shared!
        // ...
    })
}
```

The test passed without `-race` because tests ran sequentially. With `-race` and `t.Parallel()`, multiple goroutines read `tt` simultaneously, creating a data race on the loop variable.

**Fix:** Capture the loop variable per iteration:
```go
for _, tt := range tests {
    tt := tt // Create new variable per iteration
    t.Run(tt.name, func(t *testing.T) {
        t.Parallel()
        handler := NewHandler(tt.config) // tt is now per-goroutine
        // ...
    })
}
```

### Scenario 3: Mock Not Called Assertion Fails in Nested t.Run

**What happened:** A test used `testify/mock` with `.On()` expectations. When the handler returned early (e.g., validation error), the mock's method was never called, and `AssertExpectations(t)` failed.

```go
svc.On("GetUser", "42").Return(user, nil) // Expects GetUser to be called

// Handler validates input FIRST, then calls service
// If input is invalid, GetUser is never called
handler.GetUser(...)
svc.AssertExpectations(t) // FAILS: GetUser not called
```

**Fix:** Use `.Maybe()` for mock expectations that may not be called, or separate test cases for validation errors (where the service is never called) and service errors (where the service IS called but returns an error):

```go
// For validation error tests: don't set GetUser expectation
// For service error tests: set the expectation normally
if !tt.expectServiceCall {
    // Don't set expectations — handler should return before calling service
} else {
    svc.On("GetUser", "42").Return(nil, tt.serviceError)
}
```

---

## Debugging Techniques

### Technique 1: Inspecting Middleware Chain Order

Insert a request-scoped logger that records middleware entry/exit:

```go
type middlewareTracker struct {
    mu     sync.Mutex
    events []string
}

func (t *middlewareTracker) Track(name string) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            t.mu.Lock()
            t.events = append(t.events, "enter:"+name)
            t.mu.Unlock()
            next.ServeHTTP(w, r)
            t.mu.Lock()
            t.events = append(t.events, "exit:"+name)
            t.mu.Unlock()
        })
    }
}
```

Use in tests:
```go
tracker := &middlewareTracker{}
r := chi.NewRouter()
r.Use(tracker.Track("root"))
r.Route("/api", func(r chi.Router) {
    r.Use(tracker.Track("api"))
    r.Get("/data", handler)
})
// ... serve request ...
fmt.Println(tracker.events)
// [enter:root, enter:api, handler, exit:api, exit:root]
```

### Technique 2: Dumping Full Request/Response

When a test fails, dump both sides:

```go
func dumpRequest(req *http.Request) string {
    dump, _ := httputil.DumpRequest(req, true) // true = include body
    return string(dump)
}

func dumpResponse(rec *httptest.ResponseRecorder) string {
    resp := rec.Result()
    defer resp.Body.Close()
    dump, _ := httputil.DumpResponse(resp, true)
    return string(dump)
}

// In test:
if rec.Code != tt.expectedStatus {
    t.Logf("Request:\n%s", dumpRequest(req))
    t.Logf("Response:\n%s", dumpResponse(rec))
    t.FailNow()
}
```

This produces human-readable HTTP messages with headers, body, and status line.

### Technique 3: CPU Profiling Slow Handler Tests

```go
func TestSlowHandler(t *testing.T) {
    f, _ := os.Create("cpu.prof")
    pprof.StartCPUProfile(f)
    defer pprof.StopCPUProfile()

    // ... run handler test ...
}
```

```bash
go test -run TestSlowHandler
go tool pprof -http=:8080 cpu.prof
```

This opens a flame graph in the browser, showing where CPU time is spent — whether in your handler, the mock framework, or the router.

---

## Observability Considerations

### Tests as Documentation

Well-written handler tests document the contract. A new team member should be able to read the test file and understand:
- What HTTP methods are supported for each endpoint
- What status codes are returned for each scenario
- What the JSON request/response schemas look like
- What headers are required and returned
- How authentication/authorization errors are surfaced

Use descriptive test names: `TestCreateUser_ValidInput_Returns201` not `TestCreateUser1`.

### Test Coverage as a Gate

Set coverage thresholds in CI:
```bash
go test -coverprofile=coverage.out ./...
go tool cover -func=coverage.out | grep total | awk '{print $3}' | sed 's/%//'
# If < 80, fail the build (adjust threshold by project)
```

But coverage percentage is a floor, not a ceiling. Uncovered code isn't necessarily untested, and covered code isn't necessarily correct. Combine coverage with mutation testing (`go-mutesting`) for high-criticality modules.

### Flaky Test Detection

Flaky tests erode trust in the test suite. Mark tests as flaky and track them:

```go
func TestIntermittentFailure(t *testing.T) {
    if os.Getenv("CI") == "true" {
        // Retry flaky test in CI
        for attempt := 0; attempt < 3; attempt++ {
            ok := t.Run(fmt.Sprintf("attempt-%d", attempt), func(t *testing.T) {
                // ... test logic ...
            })
            if ok {
                return
            }
        }
        t.Fatal("test failed after 3 retries")
    }
}
```

Better: root-cause the flakiness (race conditions, timeout assumptions, external dependency) rather than papering over it with retries.

---

## Performance Implications

### Handler Test Speed

A well-written handler test takes 10-100μs per iteration. A suite of 500 handler tests runs in under 1 second. The bottleneck is typically:
1. Mock expectation setup (testify/mock uses reflection — ~5-10μs per `.On()`)
2. JSON parsing in assertions (use `assert.JSONEq` for readability, `assert.Equal` for speed)
3. Context value chains (each `context.WithValue()` adds a linked-list node — ~50ns per level)

### Benchmark Noise Reduction

Handler benchmarks are sensitive to system noise. To get reliable results:

```bash
# Run on a quiet machine (no browser, no IDE, no background processes)
# Use performance CPU governor
sudo cpupower frequency-set -g performance

# Pin to specific CPU
taskset -c 0 go test -bench=. -benchtime=30s -count=10

# Use benchstat to compare
go test -bench=. -count=10 > old.txt
# ... make change ...
go test -bench=. -count=10 > new.txt
benchstat old.txt new.txt
```

### Memory Allocations in Handler Tests

The test framework itself allocates memory. Be aware of overhead:
- `httptest.NewRecorder`: ~200 bytes (bytes.Buffer + Header map)
- `httptest.NewRequest`: ~500 bytes (URL, Header, Body reader)
- `chi.RouteContext` via sync.Pool: ~0 bytes (pooled) or ~300 bytes (cold)
- `testify/mock.On()`: ~200 bytes per expectation (stores method name, args, return values)

These allocations are insignificant for individual tests but accumulate in benchmarks. Always benchmark with `-benchmem` to separate handler allocations from framework allocations.

---

## Architecture Implications

### Test-Driven API Design

Testing handlers first reveals API design flaws before they reach production. If testing a handler requires 10 lines of setup and 5 mock expectations, the handler is doing too much — split it into smaller handlers or push logic to the service layer.

The "handler should be thin" principle is directly test-driven: a handler that's easy to test is probably well-designed. A handler that requires extensive mocking of HTTP internals (headers, cookies, response streaming) is probably mixing infrastructure concerns with business logic.

### Mocking Strategy

For handler tests, mock at the service layer, not at the HTTP layer:
- DO mock: service interfaces, repository interfaces, external API clients
- DON'T mock: `http.ResponseWriter`, `*http.Request`, Chi middleware, `chi.URLParam()`

Mocking HTTP primitives is fragile — it couples tests to the exact interaction pattern with `net/http`. Mocking service interfaces is stable — it couples tests to the business logic contract.

### Test Pyramid Application

```
    ╱  Integration (10-20%)
   ╱   Route Group (20-30%)
  ╱    Handler Unit (50-70%)
```

Handler unit tests are the base: fast, reliable, and numerous. They catch most regressions.
Route group tests verify middleware integration and routing — critical for security and cross-cutting concerns.
Integration tests verify end-to-end flows and catch configuration issues (missing middleware, incorrect path prefixes).

---

## Team Ownership Implications

The platform/infrastructure team owns:
- Test helper utilities (`testhelper.NewRouter()`, `testhelper.MakeRequest()`, `testhelper.AssertJSON()`)
- Integration test infrastructure (test database setup/teardown, mock external services)
- CI pipeline with race detector, coverage thresholds, and flaky test tracking
- Test naming conventions and table-driven test structure guidelines

Application teams own:
- Handler-specific tests in their module's test files
- Mock implementations for their domain service interfaces
- Integration test scenarios for their specific endpoints
- Benchmarking their handlers for performance-critical paths

Cross-team responsibility:
- Shared mock implementations for cross-cutting services (auth, logging)
- Test data factories for domain entities used across modules

---

## Interview Questions

### Q1: What's the difference between httptest.NewRequest and http.NewRequest, and when would you use each?

**Answer:** `httptest.NewRequest` wraps `http.NewRequest` and panics on error instead of returning one. Use it exclusively in test code where errors are unexpected — if your test method or URL is invalid, the test should fail immediately. `http.NewRequest` should be used in production code where request creation can fail (e.g., when building a proxy that forwards requests with user-provided URLs). Using `httptest.NewRequest` in tests simplifies error handling: no `if err != nil { t.Fatal(err) }` for every request creation.

### Q2: How do you test that a Chi middleware calls `next.ServeHTTP()` and doesn't short-circuit?

**Answer:** Create a mock `http.Handler` that records whether it was called:

```go
var called bool
next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
    called = true
})
wrapped := myMiddleware(next)
wrapped.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/", nil))
assert.True(t, called, "middleware must call next.ServeHTTP")
```

For middleware that should short-circuit (e.g., auth failure), assert `called == false` and verify the response status code matches what the middleware wrote.

### Q3: Why does httptest.ResponseRecorder not support http.Hijacker? How do you test WebSocket handlers?

**Answer:** `ResponseRecorder` is a fake `ResponseWriter` — it captures all writes to a buffer but doesn't have a real network connection. Hijacking requires a real `net.Conn` for bidirectional communication, which a buffer cannot provide. To test WebSocket handlers, use `httptest.NewServer` (which creates a real TCP listener) and a WebSocket client library to dial the test server.

### Q4: How would you test a handler that depends on chi.URLParam(r, "id")?

**Answer:** Create a `chi.NewRouter()` with the parameterized route, register the handler, and feed a request with the param in the path:

```go
r := chi.NewRouter()
r.Get("/users/{id}", handler.GetUser)

req := httptest.NewRequest("GET", "/users/42", nil)
rec := httptest.NewRecorder()
r.ServeHTTP(rec, req)

// URLParam is populated by Chi's ServeHTTP during route matching
// Inside handler.GetUser: chi.URLParam(r, "id") == "42"
```

Without the router, `chi.URLParam()` returns `""` because no `RouteContext` exists in the request context. This is correct behavior — `URLParam` only works when the request has been routed through Chi.

### Q5: What's the best way to test that a handler returns the correct JSON error envelope for multiple error scenarios?

**Answer:** Use table-driven tests with a shared assertion helper:

```go
func assertErrorResponse(t *testing.T, rec *httptest.ResponseRecorder, expectedStatus int, expectedCode, expectedMessage string) {
    t.Helper()
    assert.Equal(t, expectedStatus, rec.Code)
    var resp ErrorResponse
    err := json.Unmarshal(rec.Body.Bytes(), &resp)
    require.NoError(t, err)
    assert.Equal(t, expectedCode, resp.Error)
    assert.Equal(t, expectedMessage, resp.Message)
    assert.NotEmpty(t, resp.RequestID)
}

// Usage in table-driven test:
assertErrorResponse(t, rec, 404, "not_found", "user with id 42 not found")
```

This helper validates the status code, error code, message, and that request_id is present — all in one call. It also has `t.Helper()` so test failures point to the test case line, not the helper function.

---

## Hands-On Exercises

### Exercise 1: Build a Complete Handler Test Suite

**Goal:** Write table-driven tests for a CRUD handler covering all success and error paths.

**Steps:**
1. Define a `UserHandler` with Create, GetByID, List, Update, Delete methods
2. Define a `UserService` interface (mock it with `testify/mock` or hand-written mock)
3. Write table-driven tests for each handler method:
   - Create: valid input (201), invalid JSON (400), duplicate email (409), validation error (422)
   - GetByID: found (200), not found (404), invalid UUID format (400)
   - List: with results (200), empty results (200), pagination params (200)
   - Update: success (200), not found (404), version conflict (409)
   - Delete: success (204), not found (404)
4. For each test case, assert status code, response body (JSON structure), and headers
5. Verify mock expectations with `svc.AssertExpectations(t)`

### Exercise 2: Test Middleware with Mock Handler

**Goal:** Write comprehensive middleware tests covering next handler call, short-circuit, context injection, and header modification.

**Steps:**
1. Implement a `RateLimitMiddleware(limit int) func(http.Handler) http.Handler` that:
   - Extracts `X-API-Key` from headers
   - Checks a counter in memory per API key
   - Returns 429 with `Retry-After` header if limit exceeded
   - Calls `next.ServeHTTP()` if within limit
2. Test scenarios:
   - First request: next handler called, status 200
   - Request exceeding limit: next handler NOT called, status 429
   - Missing API key: short-circuit with 401
   - Verify `Retry-After` header is set in 429 responses
   - Verify `X-RateLimit-Remaining` header is set in 200 responses
3. Create a mock next handler that records calls and context values

### Exercise 3: Integration Test with Test Database

**Goal:** Write an integration test that starts a full router, connects to a real test database, and exercises the create-read-update-delete lifecycle.

**Steps:**
1. Set up a test PostgreSQL database (using `testcontainers-go` or a dedicated test DB)
2. Run migrations to create the schema
3. Build the full router with real services, real repository, real handlers
4. Start `httptest.NewServer(r)`
5. Create a user via POST → verify 201 and JSON response
6. Read the user via GET → verify 200 and correct data
7. Update the user via PUT → verify 200
8. Delete the user via DELETE → verify 204
9. Read the user again → verify 404
10. Use `t.Cleanup()` to drop test data and close the database connection

---

## Advanced Challenges

### Challenge 1: Build a Fuzzing Harness for Handler Validation

**Goal:** Use Go's native fuzzing (`go test -fuzz`) to find edge cases in handler input validation.

1. Write a fuzz test for your CreateUser handler: `func FuzzCreateUser(f *testing.F)`
2. Seed the fuzz corpus with valid and invalid JSON payloads
3. Fuzz the request body, URL path, and headers
4. Assert invariants:
   - Handler never panics (any panic is a bug — even on bad input)
   - Response is always valid JSON (even 400/500 errors)
   - Status code is always in a valid range (100-599)
   - `Content-Type` is set on error responses
5. Run: `go test -fuzz=FuzzCreateUser -fuzztime=10m`

**Principal-level aspect:** Fuzzing reveals input validation gaps that code review misses. The challenge is designing invariants that can be checked programmatically and creating seed corpus that covers the space efficiently.

### Challenge 2: Build a Deterministic Simulator for Concurrent Request Testing

**Goal:** Create a test framework that simulates concurrent request patterns, randomizes goroutine schedules, and detects race conditions deterministically.

1. Use `testing/synctest` (Go 1.24+) or Golang's race detector + deterministic scheduling
2. Define concurrent request scenarios: N goroutines all calling the same handler with a shared counter
3. Simulate 1000 different goroutine interleavings — each test run randomizes the schedule
4. Detect violations:
   - Response body corruption (two goroutines writing to the same buffer)
   - Missing/duplicate database writes
   - Context value leakage between goroutines
5. Run with `-count=100` to exercise different interleavings

**Principal-level aspect:** This requires understanding Go's memory model, the happens-before relationship, and how `-race` detects races. The solution should be reusable across handlers — a framework, not a one-off test.

---

## Key Insights

- `httptest.ResponseRecorder` implements `http.ResponseWriter` with a `bytes.Buffer` for the body and a `Header` map. It defaults to status 200 if `WriteHeader()` is never called. Use `rec.Code`, `rec.Header()`, `rec.Body.String()` for assertions.

- Table-driven tests using `t.Run()` are the Go standard for handler testing. Each test case defines: name, input request, mock setup, expected status, expected body. Subtests can be run individually with `go test -run TestName/subtest`.

- Test middleware by creating a mock `http.Handler` that records whether it was called. The most important assertion for auth/ratelimit middleware: verify that `next.ServeHTTP` is NOT called when the middleware short-circuits.

- `httptest.NewServer` creates a real HTTP server on `127.0.0.1:0`. Use it for integration tests that need real TCP connections (WebSocket, streaming, timeout testing). `ts.Client()` returns a pre-configured `*http.Client`.

- Run `go test -race` in CI for all handler tests. Race conditions in handlers typically manifest as data races on shared state — captured by the race detector but invisible in normal test runs.

- Benchmarks should reset `httptest.NewRecorder()` per iteration (`b.ResetTimer()` and creating fresh recorder inside the loop). Forgetting to reset causes allocations to accumulate across iterations, inflating `B/op` and `allocs/op`.

- Coverage is a floor, not a ceiling. Target 90%+ for handler code (small and testable), but focus on covering edge cases and error paths, not just the happy path. An uncovered error path is a latent production bug.
