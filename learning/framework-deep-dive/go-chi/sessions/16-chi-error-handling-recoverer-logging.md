# Session 16: Chi Error Handling, Recoverer, Logging

## Why This Topic Exists

HTTP services fail constantly. A downstream database times out. A JSON body is malformed. A goroutine panics on a nil pointer in an infrequently-tested code path. Without structured error handling, panics crash the entire server. Without structured logging, one log line per request among 10,000 req/s becomes undebuggable noise. Without proper error categorization, clients receive 500 for validation errors or 200 for server failures.

Chi provides two canonical middleware for this — `middleware.Recoverer` (panic recovery) and `middleware.Logger` (request logging) — along with a request ID mechanism (`middleware.RequestID`) that ties errors to requests. These are not black boxes. Reading `chi/middleware/recoverer.go` (~100 lines) and `chi/middleware/logger.go` (~120 lines) is a masterclass in Go middleware patterns, context usage, and production-grade error handling.

As a Staff/Principal Engineer, you will set the error handling standards for your entire organization. You will decide whether panics show stack traces to clients (no, unless you want to expose internal paths). You will define the JSON error envelope that every service in your platform uses. You will integrate structured logging (`log/slog`) with Chi's request context. You will write a custom recoverer that emits metrics, stores error IDs, and sanitizes production output — and you will be on-call when it misbehaves.

---

## Mental Model

### The Panic Propagation Chain

In Go, a panic in a handler unwinds the goroutine stack. Without a recoverer, the panic reaches the top of the goroutine and terminates it — which means the net/http server's goroutine dies. In Go 1.8+, `net/http` itself has a built-in recoverer in each connection goroutine, so a single panic does NOT crash the entire server (it used to, pre-1.8). But the connection is closed abruptly, the client gets no response (or a connection reset), and you have no logs, no metrics, no debugging information about what happened.

```
Without Recoverer:
  Request → Handler → panic! → goroutine termination → connection closed
  Result: Client gets ECONNRESET. No log. No trace. No metric.

With Recoverer:
  Request → Recoverer → Handler → panic!
  Recoverer catches: recover(), gets stack trace, logs error, returns 500
  Result: Client gets 500 JSON response with error ID. Logs emitted. Metrics incremented.
```

### The Onion Model Applied to Error Handling

```
┌──────────────────────────────────────────────────────┐
│                   RequestID                           │
│  ┌──────────────────────────────────────────────────┐ │
│  │                 Logger                            │ │
│  │  ┌──────────────────────────────────────────────┐ │ │
│  │  │               Recoverer (outermost recovery) │ │ │
│  │  │  ┌──────────────────────────────────────────┐ │ │ │
│  │  │  │              Timeout                      │ │ │ │
│  │  │  │  ┌──────────────────────────────────────┐ │ │ │ │
│  │  │  │  │           Auth                        │ │ │ │
│  │  │  │  │  ┌──────────────────────────────────┐ │ │ │ │ │
│  │  │  │  │  │          Handler                  │ │ │ │ │
│  │  │  │  │  │  panic("nil pointer deref")      │ │ │ │ │
│  │  │  │  │  └──────────────────────────────────┘ │ │ │ │ │
│  │  │  │  └──────────────────────────────────────┘ │ │ │ │
│  │  │  └──────────────────────────────────────────┘ │ │ │
│  │  └──────────────────────────────────────────────┘ │ │
│  └──────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

Critical ordering rule: **Recoverer must be OUTSIDE (registered first)** of Logger and Timeout middleware. If Recoverer is INSIDE Logger:

```
Bad: Logger → Recoverer → Handler → panic
     Recoverer catches → returns 500 → but Logger already captured start time
     Result: Logger logs 200 (or doesn't log at all) because Recoverer's 500 response
     overwrites what Logger saw. Confusing logs.

Good: Recoverer → Logger → Handler → panic
     Recoverer catches → calls next.ServeHTTP() which is Logger → Handler
     Inside Recoverer: recover() catches panic → log directly → write 500
     Logger sees the 500 response normally.
```

Actually, Chi's Recoverer is designed to work INSIDE Logger (registered after Logger). The Recoverer catches panics during its `next.ServeHTTP()` call, which is the Logger's `next.ServeHTTP()` → Handler chain. When a panic occurs, Recoverer catches it AFTER the handler panics but BEFORE the stack unwinds past Logger. Logger sees the response written by Recoverer (500 status) and logs correctly.

---

## Internal Architecture

### middleware.Recoverer Source Walkthrough

The canonical Chi Recoverer is in `chi/middleware/recoverer.go`. At under 100 lines, it's a model of middleware design:

```go
func Recoverer(next http.Handler) http.Handler {
    fn := func(w http.ResponseWriter, r *http.Request) {
        defer func() {
            if rvr := recover(); rvr != nil {
                if rvr == http.ErrAbortHandler {
                    panic(rvr) // Re-panic for net/http's abort mechanism
                }

                logEntry := GetLogEntry(r)
                if logEntry != nil {
                    logEntry.Panic(rvr, debug.Stack())
                } else {
                    PrintPrettyStack(rvr)
                }
                // ...
                http.Error(w, http.StatusText(http.StatusInternalServerError), http.StatusInternalServerError)
            }
        }()
        next.ServeHTTP(w, r)
    }
    return http.HandlerFunc(fn)
}
```

**Step-by-step internals:**

1. **`defer func() { ... }()`**: The critical pattern. `defer` ensures the recovery function executes even if `next.ServeHTTP` panics. The anonymous function closure captures `w`, `r`, `rvr`, and crucially, executes AFTER the panic unwinds to this stack frame.

2. **`if rvr == http.ErrAbortHandler`**: `http.ErrAbortHandler` is a sentinel panic value used by `net/http` to abort a handler. It should NOT be recovered — it must propagate. Chi's Recoverer re-panics with this value.

   Why does `http.ErrAbortHandler` exist? `http.TimeoutHandler` uses it to abort a handler that exceeds its deadline. If you recover this panic, the timeout handler's mechanism breaks — the request continues executing past the timeout.

3. **`GetLogEntry(r)`**: Chi's Logger middleware stores a `LogEntry` struct in the request context. If Logger is in the middleware chain (before Recoverer), `GetLogEntry(r)` returns this struct, which has a `Panic()` method that formats and logs the panic. If Logger is NOT present, `PrintPrettyStack()` is the fallback — it prints to stderr.

4. **`http.Error(w, ...)`**: The default Recoverer writes a plain text "Internal Server Error" with status 500. This is the baseline behavior — in production, you'll want a custom recoverer with JSON responses.

### Custom Recoverer: Structured Error Response, Error IDs

A production-grade custom recoverer:

```go
func CustomRecoverer(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        defer func() {
            if rvr := recover(); rvr != nil {
                if rvr == http.ErrAbortHandler {
                    panic(rvr)
                }

                // Generate unique error ID for correlation
                errorID := generateErrorID() // e.g., uuid.New().String()[:8]

                // Capture stack trace
                stack := debug.Stack()

                // Extract request ID from context (set by RequestID middleware)
                requestID := middleware.GetReqID(r.Context())

                // Log the full error with stack trace
                slog.Error("panic recovered",
                    "error_id", errorID,
                    "request_id", requestID,
                    "panic", fmt.Sprintf("%v", rvr),
                    "method", r.Method,
                    "path", r.URL.Path,
                    "stack", string(stack),
                )

                // Emit metric
                panicCounter.Inc()

                // Write sanitized response — NEVER include stack trace
                w.Header().Set("Content-Type", "application/json")
                w.WriteHeader(http.StatusInternalServerError)
                json.NewEncoder(w).Encode(map[string]interface{}{
                    "error":      "internal_server_error",
                    "message":    "An unexpected error occurred",
                    "request_id": requestID,
                    "error_id":   errorID,
                    "status":     500,
                })
            }
        }()
        next.ServeHTTP(w, r)
    })
}
```

Key design decisions in the custom recoverer:
- **Error ID**: A short unique identifier (e.g., 8 hex chars from UUID) appended to the response. The client can quote this to support, and engineers can grep logs for it. This decouples the public-facing response from internal details.
- **Sanitized stack traces**: Stack traces are logged (in dev AND production logs) but NEVER returned to the client. Production stack traces reveal file paths, function names, and potentially library versions — valuable to attackers.
- **Structured logging**: `log/slog` with key-value pairs enables log aggregation systems (Loki, ELK, Datadog) to index by `error_id`, `request_id`, `panic` type.
- **Metric emission**: A panic counter allows alerting. If the panic rate exceeds a threshold, trigger PagerDuty.

### Panic Propagation Through the Middleware Chain

Consider this middleware chain:

```go
r.Use(RequestID)
r.Use(Logger)
r.Use(Recoverer)
r.Use(Timeout)
r.Use(Auth)
// → Handler
```

What happens when the handler panics:

1. Handler panics with `runtime error: index out of range [3] with length 2`
2. Stack unwinds through Auth's `next.ServeHTTP()` call — Auth does NOT have a recover, so the panic continues upward
3. Stack unwinds through Timeout's goroutine — Timeout uses `http.TimeoutHandler` which runs the handler in a separate goroutine. The panic in the handler goroutine does NOT propagate through the timeout wrapper. This is a subtle point: if Timeout spawns a goroutine, the panic stays in that goroutine unless you explicitly coordinate.
4. Stack unwinds through Recoverer's `next.ServeHTTP()` call — Recoverer's `defer func()` fires, `recover()` captures the panic value
5. Recoverer writes 500 response to `w`
6. Stack unwinds normally through Logger (no panic), which logs the 500 status
7. Stack unwinds normally through RequestID
8. Response sent to client

If Timeout spawns a goroutine and the handler panics INSIDE that goroutine:

```go
// In Timeout middleware or handler:
go func() {
    doWork() // panic! ← NOT caught by Recoverer
}()
```

The panic in `doWork()` terminates the goroutine. The parent goroutine continues executing (or waits on a channel that never sends). This is why every goroutine should have its own recoverer:

```go
go func() {
    defer func() {
        if r := recover(); r != nil {
            slog.Error("goroutine panic", "error", r)
        }
    }()
    doWork()
}()
```

### Error Response Patterns: Consistent JSON Envelope

All errors in a Chi-based API should use the same JSON envelope:

```go
type ErrorResponse struct {
    Error     string `json:"error"`
    Message   string `json:"message"`
    RequestID string `json:"request_id,omitempty"`
    Status    int    `json:"status"`
    Details   []ErrorDetail `json:"details,omitempty"`  // For validation errors
    ErrorID   string `json:"error_id,omitempty"`        // For 500 errors
}

type ErrorDetail struct {
    Field   string `json:"field"`
    Message string `json:"message"`
    Code    string `json:"code,omitempty"`
}
```

Helper functions ensure consistency:

```go
func RespondError(w http.ResponseWriter, r *http.Request, status int, errCode string, message string) {
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(status)
    json.NewEncoder(w).Encode(ErrorResponse{
        Error:     errCode,
        Message:   message,
        RequestID: middleware.GetReqID(r.Context()),
        Status:    status,
    })
}

// Specialization for validation errors:
func RespondValidationError(w http.ResponseWriter, r *http.Request, details []ErrorDetail) {
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusUnprocessableEntity)
    json.NewEncoder(w).Encode(ErrorResponse{
        Error:     "validation_error",
        Message:   "The request contains invalid parameters",
        RequestID: middleware.GetReqID(r.Context()),
        Status:    422,
        Details:   details,
    })
}
```

### Error Categorization

Errors should be categorized by the HTTP status code they produce. Build a mapping table:

| Error Type | HTTP Status | Error Code String | When |
|---|---|---|---|
| Validation | 400 | `bad_request` | Malformed JSON, missing required fields |
| Authentication | 401 | `unauthorized` | Missing/expired/invalid token |
| Authorization | 403 | `forbidden` | Valid token, insufficient permissions |
| Not Found | 404 | `not_found` | Resource doesn't exist |
| Method Not Allowed | 405 | `method_not_allowed` | Wrong HTTP method |
| Conflict | 409 | `conflict` | Duplicate creation, version mismatch |
| Unprocessable | 422 | `validation_error` | Valid JSON, invalid business logic values |
| Too Many Requests | 429 | `rate_limited` | Rate limiter tripped |
| Internal Server Error | 500 | `internal_server_error` | Unexpected failure |

Define sentinel errors at the package level:

```go
var (
    ErrNotFound           = errors.New("resource not found")
    ErrAlreadyExists      = errors.New("resource already exists")
    ErrInsufficientFunds  = errors.New("insufficient funds")
    ErrInvalidTransition  = errors.New("invalid state transition")
    ErrUnauthorized       = errors.New("unauthorized")
    ErrForbidden          = errors.New("forbidden")
)
```

Map domain errors to HTTP statuses in a single translation layer (handler middleware or response helper):

```go
func ErrorToStatus(err error) int {
    switch {
    case errors.Is(err, ErrNotFound):
        return http.StatusNotFound
    case errors.Is(err, ErrAlreadyExists):
        return http.StatusConflict
    case errors.Is(err, ErrInsufficientFunds):
        return http.StatusUnprocessableEntity
    case errors.Is(err, ErrUnauthorized):
        return http.StatusUnauthorized
    case errors.Is(err, ErrForbidden):
        return http.StatusForbidden
    default:
        return http.StatusInternalServerError
    }
}
```

### middleware.Logger: What It Logs and How

The Chi Logger middleware records the following per request:

```
200 GET /api/users/42 HTTP/1.1 45.231ms 234B
```

Format: `[status] [method] [path] [protocol] [duration] [response_bytes]`

The implementation in `chi/middleware/logger.go`:

```go
func Logger(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        entry := NewLogEntry(logger, r) // Create per-request log entry
        ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor) // Wrap to capture status
        t1 := time.Now()
        defer func() {
            entry.Write(ww.Status(), ww.BytesWritten(), time.Since(t1))
        }()
        next.ServeHTTP(ww, r)
    })
}
```

**`middleware.NewWrapResponseWriter`**: Wraps `http.ResponseWriter` to capture:
- Status code (defaults to 200 if `WriteHeader()` is never called)
- Bytes written (accumulated over all `Write()` calls)
- HTTP protocol version

**`LogEntry`**: Stored in the request context via `context.WithValue()`:

```go
type LogEntry struct {
    Logger  LoggerInterface
    Request *http.Request
}

func (l *LogEntry) Write(status, bytes int, elapsed time.Duration) {
    l.Logger.Info("request completed",
        "status", status,
        "method", l.Request.Method,
        "path", l.Request.URL.Path,
        "duration", elapsed,
        "bytes", bytes,
    )
}
```

**The timing subtlety**: `defer` is used for the log write, which means it executes even if `next.ServeHTTP` panics (before the panic propagates past Logger). However, if Recoverer is AFTER Logger in the chain and catches the panic, Logger sees the 500 status correctly because Recoverer calls `w.WriteHeader(500)` before passing control back.

### Structured Logging with log/slog (Go 1.21+)

Go 1.21 introduced `log/slog` — structured logging in the standard library. Integrating it with Chi:

```go
func SlogMiddleware(logger *slog.Logger) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            start := time.Now()

            // Extract or generate request ID
            reqID := middleware.GetReqID(r.Context())

            // Create scoped logger with request-specific attributes
            reqLogger := logger.With(
                slog.String("request_id", reqID),
                slog.String("method", r.Method),
                slog.String("path", r.URL.Path),
            )

            // Store logger in context for handlers to use
            ctx := context.WithValue(r.Context(), ctxKeyLogger, reqLogger)

            // Wrap response writer
            ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)

            // Call next handler
            next.ServeHTTP(ww, r.WithContext(ctx))

            // Log after response
            reqLogger.Info("request",
                slog.Int("status", ww.Status()),
                slog.Int64("bytes", int64(ww.BytesWritten())),
                slog.Duration("duration", time.Since(start)),
            )
        })
    }
}
```

Handlers retrieve the logger from context:

```go
func GetLogger(ctx context.Context) *slog.Logger {
    if logger, ok := ctx.Value(ctxKeyLogger).(*slog.Logger); ok {
        return logger
    }
    return slog.Default()
}
```

This ensures all log lines from a single request share the same `request_id` and other request-scoped attributes, enabling correlation in log aggregation systems.

### Stack Trace Handling Policy

**Development environment**: Show stack traces in the response body (or in a development-only endpoint like `GET /__debug/stack`). Use `debug.Stack()`.

**Production environment**: NEVER show stack traces to clients. Instead:
1. Log the full stack trace (to stdout/stderr or log aggregator).
2. Generate a short error ID (UUID prefix, e.g., first 8 chars).
3. Return the error ID to the client.
4. Store the error ID → stack trace mapping in a ring buffer or external service if you need real-time lookup.

Implementation pattern:

```go
type ProductionRecoverer struct {
    errorStore ErrorStore // interface for storing/retrieving errors
}

func (p *ProductionRecoverer) Handler(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        defer func() {
            if rvr := recover(); rvr != nil && rvr != http.ErrAbortHandler {
                errorID := uuid.New().String()[:8]
                stack := debug.Stack()

                p.errorStore.Store(errorID, ErrorRecord{
                    Panic:    fmt.Sprintf("%v", rvr),
                    Stack:    string(stack),
                    Method:   r.Method,
                    Path:     r.URL.Path,
                    Timestamp: time.Now(),
                })

                slog.Error("panic_recovered",
                    "error_id", errorID,
                    "panic", rvr,
                    "stack", string(stack),
                )

                RespondError(w, r, 500, "internal_server_error",
                    fmt.Sprintf("Unexpected error. Reference: %s", errorID))
            }
        }()
        next.ServeHTTP(w, r)
    })
}
```

---

## Runtime Behavior

### Logger Timing and Response Capture

The Logger middleware uses `defer` to capture timing. The timing includes:
- The entire handler execution
- All middleware post-handler logic (e.g., response compression, trailing headers)
- The Logger's own write operation (negligible)

A request that takes 100ms end-to-end:
```
t=0: Request arrives at Logger
t=0: Logger captures t1, creates LogEntry, wraps ResponseWriter
t=0: Passes to next middleware
t=0-50ms: Auth middleware (JWT validation, DB lookup)
t=50-95ms: Handler executes (business logic, DB queries)
t=95-100ms: Response travels back through Auth (post-processing)
t=100ms: Logger defer fires: ww.Status() → captures status written by handler
                    ww.BytesWritten() → captures total response body size
                    time.Since(t1) → 100ms
t=100ms: Logger.Write() called with (200, 1234, 100ms)
t=100ms: Log line emitted
```

### Recoverer Timing Behavior

When a panic occurs, the Recoverer must:
1. Enter the `defer` block (~0.5μs)
2. Check for `http.ErrAbortHandler` (~0.1μs)
3. Call `debug.Stack()` (~10-50μs depending on goroutine stack depth)
4. Format and write the log entry (~5-20μs)
5. Write the HTTP response (~10-100μs depending on body size and network)

Total overhead of panic recovery: ~25-170μs. This is fast enough to handle hundreds of panics per second without significant latency impact — although if you're handling hundreds of panics per second, you have a bigger problem.

### ResponseWriter Hijacking and Logging

`http.ResponseWriter` supports `http.Hijacker`, `http.Flusher`, and `http.Pusher` interfaces. Chi's `WrapResponseWriter` preserves these interfaces via type assertion:

```go
func NewWrapResponseWriter(w http.ResponseWriter, protoMajor int) WrapResponseWriter {
    _, fl := w.(http.Flusher)
    _, cn := w.(http.CloseNotifier)
    _, hj := w.(http.Hijacker)
    _, rf := w.(http.Pusher)
    _, pu := w.(http.Pusher)

    bw := basicWriter{ResponseWriter: w}
    // ...
}
```

If the underlying `ResponseWriter` supports `Hijacker`, so does the wrapped version. This matters for WebSocket upgrades and SSE connections — the Logger middleware does not interfere with connection hijacking.

---

## Flow Diagrams

### Panic Recovery Flow

```
Handler panics!
    │
    ▼
┌─────────────────────────────────────────────┐
│ defer func() { ... }() in Recoverer fires   │
│                                             │
│ rvr := recover()                            │
│                                             │
│ Is rvr == http.ErrAbortHandler?              │
│   YES → panic(rvr) // re-panic for net/http │
│   NO → continue                             │
│                                             │
│ GetLogEntry(r) exists?                      │
│   YES → logEntry.Panic(rvr, debug.Stack())  │
│   NO  → PrintPrettyStack(rvr)              │
│                                             │
│ Write 500 response:                         │
│   w.Header().Set("Content-Type", "application/json")  │
│   w.WriteHeader(500)                        │
│   w.Write(JSON body with error_id)          │
│                                             │
│ defer returns normally — no more panic      │
└─────────────────────────────────────────────┘
    │
    ▼
Control returns to Recoverer's caller (the wrapping middleware)
Logger sees 500 status → logs normally
RequestID middleware appends header → response sent
```

### Logger Request Flow

```
Request arrives
    │
    ▼
Logger middleware
    ├─→ Create LogEntry
    ├─→ Create WrapResponseWriter
    ├─→ t1 = time.Now()
    ├─→ Store LogEntry in context
    │
    ▼
next.ServeHTTP(ww, r)
    │
    ├─→ Auth middleware (before next)
    │   ├─→ JWT validation
    │   └─→ Role check
    │
    ├─→ Handler
    │   ├─→ Parse request body
    │   ├─→ Call service layer
    │   ├─→ w.WriteHeader(200)
    │   └─→ w.Write(responseBody)
    │
    ├─→ Auth middleware (after next)
    │   └─→ (no post-processing)
    │
    ▼
defer: entry.Write(ww.Status(), ww.BytesWritten(), time.Since(t1))
    │
    ├─→ ww.Status() → 200 (captured from WriteHeader call)
    ├─→ ww.BytesWritten() → 1234 (accumulated from Write calls)
    ├─→ time.Since(t1) → 45.231ms
    │
    └─→ Log line emitted:
        200 GET /api/users/42 HTTP/1.1 45.231ms 1234B
```

### Error Response Translation Pipeline

```
Handler/Service returns error
    │
    ▼
Handler's error handling block
    │
    ├─→ Is it a validation error?
    │   └─→ RespondValidationError(w, r, details)
    │       └─→ 422 {error: "validation_error", details: [...]}
    │
    ├─→ Is it a sentinel domain error?
    │   ├─→ ErrNotFound → RespondError(w, r, 404, "not_found", msg)
    │   ├─→ ErrAlreadyExists → RespondError(w, r, 409, "conflict", msg)
    │   ├─→ ErrUnauthorized → RespondError(w, r, 401, "unauthorized", msg)
    │   └─→ ErrForbidden → RespondError(w, r, 403, "forbidden", msg)
    │
    ├─→ Is it a database error?
    │   ├─→ sql.ErrNoRows → RespondError(w, r, 404, "not_found", msg)
    │   └─→ Other DB error → log error, return 500
    │
    └─→ Unknown error
        ├─→ slog.Error("unhandled error", "error", err, "request_id", reqID)
        └─→ RespondError(w, r, 500, "internal_server_error", "Unexpected error")
```

---

## Source Code Reading Guide

**Reading order (estimate: 2-3 hours for deep understanding):**

1. **`chi/middleware/recoverer.go`** (entire file, ~100 lines) — Read this FIRST. It's the canonical Chi middleware example: short, complete, demonstrates defer/recover, context usage, and error handling. Pay attention to `http.ErrAbortHandler` handling and `GetLogEntry()` integration.

2. **`chi/middleware/logger.go:1-80`** — `Logger()` function and `LogEntry` struct. Understand how `WrapResponseWriter` captures status and bytes, how `defer` ensures logging even on panic, and where `LogEntry` is stored in context.

3. **`chi/middleware/wrap_writer.go`** (entire file, ~200 lines) — `WrapResponseWriter` implementation. Understand the interface composition (Hijacker, Flusher, Pusher preservation). This is a reference for building your own ResponseWriter wrappers.

4. **`chi/middleware/request_id.go`** — `RequestID` middleware. Understand how request IDs are generated, stored in context via `middleware.GetReqID(r.Context())`, and set on response headers.

5. **`chi/middleware/heartbeat.go`** — Simple example of a middleware that returns early without calling `next.ServeHTTP()`. Useful as a pattern reference.

6. **`log/slog` package** (Go 1.21+ standard library, use `go doc log/slog`) — Not Chi-specific, but essential for production logging. Understand `slog.Logger`, `slog.Handler`, levels, attributes, and groups.

**What to skip on first read:**
- `chi/middleware/content_charset.go`, `content_encoding.go` — unrelated to error handling
- `chi/middleware/compress.go` — compression is a separate concern
- `chi/middleware/throttle.go`, `timeout.go` — covered in middleware session (14)
- Test files (read `recoverer_test.go` if you want to understand how panic recovery is tested)

---

## Production Failure Scenarios

### Scenario 1: Recoverer Inside Logger — Incorrect 200 Logs for Panics

**What happened:** A team added custom middleware between Logger and Recoverer that inadvertently wrote headers before the handler executed. After a panic, the Recoverer wrote 500, but the Logger had already recorded 200 (WrapResponseWriter default) because the custom middleware had called `WriteHeader(200)` before the handler ran.

**The problematic middleware chain:**
```go
r.Use(RequestID)
r.Use(Logger)       // 3. Logger sees ww.Status() → 500
r.Use(PreHandlerMiddleware) // 2. Wrote 200 before calling next
r.Use(Recoverer)    // 1. Catches panic, writes 500
```

**Root cause:** The `PreHandlerMiddleware` called `w.WriteHeader(200)` before calling `next.ServeHTTP(w, r)`. Since Chi's `WrapResponseWriter` records only the FIRST call to `WriteHeader()`, the 200 was "locked in" — the Recoverer's subsequent `w.WriteHeader(500)` was silently ignored, and Logger logged 200 for a request that panicked and returned 500 to the client.

Wait — actually, `http.ResponseWriter` ignores subsequent `WriteHeader` calls after the first (it's a documented behavior in `net/http`). So if `PreHandlerMiddleware` called `WriteHeader(200)`, the Recoverer's `WriteHeader(500)` would be a no-op. The Logger sees 200, but the client gets... actually the client gets whatever was written. If Recoverer wrote a JSON body with 500 and the status was 200, the client gets a confusing 200 response with an error body.

**Fix:** Never call `WriteHeader()` before calling `next.ServeHTTP()`. If you need to short-circuit a request (e.g., auth failure), call `WriteHeader()` and `Write()` and return WITHOUT calling `next.ServeHTTP()`. Reorder middleware so Recoverer is registered first (outermost).

### Scenario 2: Stack Trace Leaked to Production Clients

**What happened:** An engineer used `debug.Stack()` in a custom error handler and accidentally included the stack trace in the JSON response body:

```go
// BAD: Stack trace in response
json.NewEncoder(w).Encode(map[string]interface{}{
    "error": "internal error",
    "stack": string(debug.Stack()), // ← LEAKS FILE PATHS!
})
```

Production incident: A pentest found file paths like `/home/deploy/app/internal/database/postgres.go:142` in error responses. This revealed the Go version, dependency names, internal package structure, and gave attackers targets for path traversal attacks.

**Fix:** Always use a two-tier approach: full stack trace in logs, error ID in response. Build a CI check that greps for `debug.Stack()` outside of log statements and test files.

**CI check (add to your lint pipeline):**
```bash
# grep for debug.Stack() in non-test, non-log files
rg 'debug\.Stack\(\)' --type go --glob '!*_test.go' --glob '!*_log*.go'
```

### Scenario 3: Logger Blocks on Slow Disk I/O

**What happened:** A service used Chi's default Logger, which writes to `os.Stderr` via the `log` package. The stderr was redirected to a file on a network-mounted filesystem (NFS). Under load, NFS latency spiked to 300ms per write. Since Logger's `defer` writes synchronously inside the request goroutine, every request added 300ms of latency — not for application logic, but for log writing.

**The failure chain:**
```
Request goroutine:
  1. Handler executes (50ms)
  2. Logger defer fires
  3. Log write to NFS (300ms blocking)
  4. Response sent (350ms total)
```

This 7x latency increase went unnoticed for days because the application metrics showed 50ms handler time — the log write happened AFTER handler execution and wasn't measured by application spans.

**Fix:** Use asynchronous logging (e.g., `zap` with buffered writer, `zerolog` with async writer) or a dedicated logging sidecar. If using synchronous logging, measure and alert on P99 latency of `http.Handler` duration including log writes:

```go
func InstrumentedLogger(logger *slog.Logger) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            start := time.Now()
            ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
            next.ServeHTTP(ww, r)
            duration := time.Since(start)

            // Log AND emit metric
            logger.Info("request", slog.Duration("duration", duration))
            requestDuration.Observe(duration.Seconds()) // Histogram metric
        })
    }
}
```

---

## Debugging Techniques

### Technique 1: Reproducing a Panic from Logs

Given a panic log with stack trace, reproduce the exact failure in a test:

```go
func TestReproducePanic(t *testing.T) {
    // Setup matches production
    r := chi.NewRouter()
    r.Use(middleware.Recoverer)
    r.Get("/users/{id}", handlerThatPanics)

    // Request that triggers the panic
    req := httptest.NewRequest("GET", "/users/42?filter=invalid%00byte", nil)
    rec := httptest.NewRecorder()

    // Verify we get 500, not a crash
    r.ServeHTTP(rec, req)
    require.Equal(t, http.StatusInternalServerError, rec.Code)

    // Verify response body is valid JSON
    var resp map[string]interface{}
    err := json.Unmarshal(rec.Body.Bytes(), &resp)
    require.NoError(t, err)
    require.Equal(t, "internal_server_error", resp["error"])
}
```

### Technique 2: Tracing Logger Behavior with a Custom Writer

Wrap the Logger's output to capture what's being logged:

```go
type LogCapture struct {
    mu   sync.Mutex
    Logs []string
}

func (c *LogCapture) Write(p []byte) (n int, err error) {
    c.mu.Lock()
    defer c.mu.Unlock()
    c.Logs = append(c.Logs, string(p))
    return len(p), nil
}

func TestLoggerOutput(t *testing.T) {
    capture := &LogCapture{}
    logger := log.New(capture, "", 0)

    r := chi.NewRouter()
    r.Use(middleware.RequestID)
    r.Use(middleware.DefaultLogger) // Uses `log` package → redirect to capture
    r.Get("/hello", func(w http.ResponseWriter, r *http.Request) {
        w.Write([]byte("world"))
    })

    req := httptest.NewRequest("GET", "/hello", nil)
    rec := httptest.NewRecorder()
    r.ServeHTTP(rec, req)

    // Verify log output contains expected fields
    require.Len(t, capture.Logs, 1)
    assert.Contains(t, capture.Logs[0], "200")
    assert.Contains(t, capture.Logs[0], "GET")
    assert.Contains(t, capture.Logs[0], "/hello")
}
```

### Technique 3: Runtime Error Rate Monitoring

Add a debug endpoint that exposes panic counts and recent error IDs:

```go
type PanicTracker struct {
    mu       sync.RWMutex
    Count    int64
    Recent   []PanicRecord // ring buffer of last 100 panics
}

type PanicRecord struct {
    ErrorID   string
    Timestamp time.Time
    Path      string
    PanicType string
}

// Add to router:
r.Get("/__debug/panics", func(w http.ResponseWriter, r *http.Request) {
    tracker.mu.RLock()
    defer tracker.mu.RUnlock()
    json.NewEncoder(w).Encode(map[string]interface{}{
        "total_panics": tracker.Count,
        "recent":       tracker.Recent,
    })
})
```

This endpoint allows on-call engineers to check recent panics without log access, speeds up incident triage.

---

## Observability Considerations

### Logs

**What Chi's default Logger outputs:**
- Method, path, protocol, status code, duration, response bytes
- Format: `200 GET /api/users/42 HTTP/1.1 45.231ms 234B`

**What a production Logger SHOULD add (via custom middleware):**
- `request_id` — from RequestID middleware, for log correlation
- `user_id` / `tenant_id` — from auth middleware, for audit trails
- `trace_id` / `span_id` — from OpenTelemetry, for distributed tracing
- `client_ip` — from X-Forwarded-For or RemoteAddr, for rate limiting and abuse detection
- `user_agent` — for client analytics and debugging client-specific issues
- `route_pattern` — the matched Chi route pattern (not the raw path): discriminates `/users/123` from `/users/456` for cardinality control

**Log level decisions:**
- `INFO`: All successful requests (2xx, 3xx, 4xx). These are expected outcomes.
- `WARN`: 429 rate limited, 401 unauthenticated (could indicate attack), deprecation warnings.
- `ERROR`: Panics (via Recoverer), 500 errors from handlers, database errors, external service failures.

### Metrics

**Essential error-handling metrics:**

```go
var (
    // Counter: total panics recovered
    panicRecoveredTotal = promauto.NewCounterVec(prometheus.CounterOpts{
        Name: "chi_panic_recovered_total",
        Help: "Total number of panics recovered by Recoverer middleware",
    }, []string{"path_pattern", "method"})

    // Counter: HTTP responses by status code
    httpResponsesTotal = promauto.NewCounterVec(prometheus.CounterOpts{
        Name: "chi_http_responses_total",
        Help: "Total HTTP responses by status code",
    }, []string{"status", "method", "path_pattern"})

    // Histogram: request duration including all middleware
    requestDurationSeconds = promauto.NewHistogramVec(prometheus.HistogramOpts{
        Name:    "chi_request_duration_seconds",
        Help:    "Request duration in seconds",
        Buckets: []float64{.001, .005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10},
    }, []string{"method", "path_pattern"})

    // Gauge: current in-flight requests
    requestsInFlight = promauto.NewGauge(prometheus.GaugeOpts{
        Name: "chi_requests_in_flight",
        Help: "Current number of in-flight requests",
    })
)
```

**Alerting rules:**
- `rate(chi_panic_recovered_total[5m]) > 0.1` → P1: Panics are happening (even 1 per 10 seconds is too many)
- `rate(chi_http_responses_total{status="500"}[5m]) > 0.01 * rate(chi_http_responses_total[5m])` → P2: 1% error rate
- `histogram_quantile(0.99, rate(chi_request_duration_seconds[5m])) > 5` → P3: P99 latency > 5s

### Traces

**Middleware spans:**

```go
func TracingMiddleware(tracer trace.Tracer) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            ctx, span := tracer.Start(r.Context(), "http.request",
                trace.WithAttributes(
                    attribute.String("http.method", r.Method),
                    attribute.String("http.url", r.URL.Path),
                ),
            )
            defer span.End()

            ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
            next.ServeHTTP(ww, r.WithContext(ctx))

            span.SetAttributes(
                attribute.Int("http.status_code", ww.Status()),
                attribute.Int("http.response_bytes", ww.BytesWritten()),
            )

            if ww.Status() >= 500 {
                span.SetStatus(codes.Error, "server error")
            }
        })
    }
}
```

For panics, the Recoverer should set the span status to ERROR and record the exception:

```go
span.RecordError(fmt.Errorf("panic: %v", rvr))
span.SetStatus(codes.Error, "panic recovered")
```

---

## Performance Implications

### Recoverer Overhead

The defer/recover overhead on the happy path (no panic) is approximately 20-30ns per request. This is the cost of setting up the defer stack frame. There is zero allocation overhead on the happy path because `recover()` is only called in the defer function, and the defer function itself is not executed unless a panic occurs.

On the panic path, the overhead is significant but acceptable (25-170μs as discussed earlier). The dominant cost is `debug.Stack()`, which walks the entire goroutine stack and formats it as bytes.

### Logger Overhead

Chi's Logger creates:
- 1 `LogEntry` struct (~64 bytes)
- 1 `WrapResponseWriter` (~128 bytes for the struct + interface pointers)
- 1 log line write (varies by backend)

Per-request allocation: ~200-300 bytes. At 10,000 req/s, this is ~2-3 MB/s of allocations. Go's garbage collector handles this easily.

For ultra-low-latency services (P99 < 1ms), consider:
- Using `middleware.DefaultLogger` with a `NullLogger` (discards output) — still useful for the `GetLogEntry(r)` context value used by Recoverer
- Sampling logs: log only 1/N requests or requests slower than a threshold
- Using `zerolog` or `zap` with `Sync()` disabled for lower allocation overhead

### Request ID Generation Overhead

Chi's RequestID middleware generates IDs using `crypto/rand` if available, falling back to a pseudo-random generator. `crypto/rand` reads from `/dev/urandom` (or equivalent on Windows), which involves a syscall — approximately 500ns-2μs per call. This is acceptable for most services. For services requiring sub-microsecond overhead, use a faster ID generation scheme (e.g., Xorshift + base62 encoding).

---

## Architecture Implications

### Global Error Handling Strategy

Every service in your platform should share the same error handling approach. Define it as a shared library package (`pkg/httputil` or `pkg/apierror`):

```go
// pkg/apierror/error.go
package apierror

type ErrorCode string

const (
    ErrBadRequest       ErrorCode = "bad_request"
    ErrUnauthorized     ErrorCode = "unauthorized"
    ErrForbidden        ErrorCode = "forbidden"
    ErrNotFound         ErrorCode = "not_found"
    ErrConflict         ErrorCode = "conflict"
    ErrValidation       ErrorCode = "validation_error"
    ErrInternal         ErrorCode = "internal_server_error"
)

type Error struct {
    Code       ErrorCode `json:"error"`
    Message    string    `json:"message"`
    Status     int       `json:"status"`
    Details    []Detail  `json:"details,omitempty"`
    RequestID  string    `json:"request_id,omitempty"`
}

// pkg/httputil/respond.go
func RespondError(w http.ResponseWriter, r *http.Request, apiErr *apierror.Error) {
    apiErr.RequestID = middleware.GetReqID(r.Context())
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(apiErr.Status)
    json.NewEncoder(w).Encode(apiErr)
}
```

Every service imports this package. The response format is guaranteed consistent across all endpoints.

### Logging Standardization

Similarly, standardize logging initialization:

```go
// pkg/logging/logger.go
func NewLogger(serviceName string, level slog.Level) *slog.Logger {
    handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
        Level: level,
        ReplaceAttr: func(groups []string, a slog.Attr) slog.Attr {
            // Rename standard keys to match your logging platform
            switch a.Key {
            case "msg":
                a.Key = "message"
            case "time":
                a.Key = "timestamp"
            }
            return a
        },
    })
    return slog.New(handler).With("service", serviceName)
}
```

All services use the same handler configuration, attribute names, and log levels. This makes cross-service log correlation trivial.

---

## Team Ownership Implications

The Recoverer and Logger middleware implementations are owned by the platform/infrastructure team. Application teams MUST NOT write their own panic recovery or request logging — using the platform-provided middleware ensures consistency in error formats, log structure, and metric names.

Application teams CAN extend error handling by:
- Defining domain-specific sentinel errors in their package
- Implementing `ErrorToStatus()` mapping for their domain errors
- Adding structured fields to the per-request logger via context (e.g., `tenant_id`, `user_id`)

The platform team owns:
- The shared `pkg/apierror` package
- The custom Recoverer implementation
- The custom Logger middleware (structured logging integration)
- The error ID generation and storage
- Alerting rules for panic rates and 500 error rates
- CI checks that prevent stack trace leakage

Application teams are responsible for:
- Returning domain errors from their service layer
- Mapping domain errors to API errors in their handler layer
- NOT calling `panic()` — reserve panics for truly unrecoverable programmer errors
- Testing error paths with table-driven handler tests

---

## Interview Questions

### Q1: Why does Chi's Recoverer check for `http.ErrAbortHandler` and re-panic?

**Answer:** `http.ErrAbortHandler` is a sentinel value used by `net/http`'s `TimeoutHandler` to abort a handler that exceeds its time limit. If Recoverer catches this panic and returns a 500 response, the timeout mechanism is broken — the handler continues executing past the deadline. By re-panicking with `http.ErrAbortHandler`, Recoverer allows the abort to propagate to `net/http`'s goroutine management layer, which cleans up correctly.

### Q2: What happens if Logger middleware is registered AFTER Recoverer?

**Answer:** Recoverer recovers from panics in handlers, but Logger is outside Recoverer in the middleware chain. When a panic occurs, the stack unwinds through Logger (before reaching Recoverer if Recoverer is inside). With Logger OUTSIDE (registered before) Recoverer: Logger's `defer` fires, and the panic hasn't been recovered yet. Logger's `defer` calls `entry.Write()`, which itself might panic (or at minimum, the `ww.Status()` might be unreliable since the handler panicked before writing a status). With Logger INSIDE (registered after) Recoverer: Recoverer catches the panic first, writes 500, then control returns to Logger, which sees the 500 status correctly. The correct order: Recoverer first, Logger second.

### Q3: How would you implement error ID generation and log correlation?

**Answer:** In the custom Recoverer, generate a short unique ID (e.g., `uuid.New().String()[:8]`). Log the full error with this ID, the stack trace, and the request ID. Return the error ID in the API response. Create a debug endpoint (`GET /__debug/error/{errorID}`) that returns the full stack trace if the caller is authenticated as an internal service. Use a ring buffer or external key-value store (Redis, DynamoDB) with TTL of 1 hour for error ID → stack trace lookup.

### Q4: How does Chi's Logger capture the response status code when the handler hasn't called WriteHeader?

**Answer:** `http.ResponseWriter` defaults to status 200 if `WriteHeader()` is never called. Chi's `WrapResponseWriter` initializes its internal status field to `http.StatusOK` (200). If `Write()` is called before `WriteHeader()`, `net/http` automatically calls `WriteHeader(200)`, which `WrapResponseWriter` intercepts. If neither `Write()` nor `WriteHeader()` is called, the status remains at the default 200. This causes subtle bugs: a handler that returns early without writing anything produces a 200 log entry with 0 bytes, even though no response was sent.

### Q5: What's the difference between using `log.Fatal()` and letting a panic propagate to Recoverer?

**Answer:** `log.Fatal()` calls `os.Exit(1)` after writing the log message — it terminates the entire process. A panic caught by Recoverer gracefully handles the error for that single request while the server continues processing other requests. Never use `log.Fatal()` in request-handling code — it's only appropriate in `func main()` for unrecoverable startup errors (missing config, database connection failure). Using `log.Fatal()` in a handler would cause a single bad request to crash the entire server.

---

## Hands-On Exercises

### Exercise 1: Build a Production-Grade Custom Recoverer

**Goal:** Implement a Recoverer that generates error IDs, emits structured logs, returns JSON errors, and never leaks stack traces to clients.

**Steps:**
1. Write a custom recoverer middleware that:
   - Generates a unique 8-character error ID
   - Logs the full stack trace using `log/slog` at ERROR level
   - Returns a JSON error response with `{error, message, request_id, error_id, status}`
   - Re-panics for `http.ErrAbortHandler`
2. Integrate with Chi's RequestID middleware to include `request_id`
3. Register it on a test router
4. Write a handler that intentionally panics
5. Verify the response contains `error_id` but NO stack trace
6. Verify the logs contain the full stack trace and error ID

### Exercise 2: Implement Structured Logging with slog Integration

**Goal:** Build a custom logger middleware using `log/slog` that captures request-scoped attributes and propagates the logger through context.

**Steps:**
1. Create an `slog` logger with JSON handler
2. Build middleware that:
   - Creates a per-request child logger with `request_id`, `method`, `path`
   - Stores the child logger in context
   - Wraps ResponseWriter to capture status and bytes
   - Logs `request` event after handler completion with status, duration, bytes
3. Write a handler that retrieves the logger from context and adds domain-specific fields
4. Test with table-driven tests: 200, 400, 500 responses
5. Verify log output contains all expected fields

### Exercise 3: Build Error Categorization and Response Helpers

**Goal:** Create a unified error response system that maps domain errors to standardized HTTP error envelopes.

**Steps:**
1. Define the `ErrorResponse` struct with `error`, `message`, `request_id`, `status`, `details` fields
2. Implement `RespondError(w, r, status, code, message)` helper
3. Implement `RespondValidationError(w, r, details)` for 422 responses
4. Define domain sentinel errors: `ErrNotFound`, `ErrAlreadyExists`, `ErrInsufficientFunds`
5. Implement `ErrorToStatus(err) int` that maps domain errors to HTTP statuses
6. Write handler-level error translation logic
7. Test that each error type produces the correct HTTP status and JSON envelope

---

## Advanced Challenges

### Challenge 1: Build a Distributed Error Tracking System

**Goal:** Extend the custom Recoverer to store errors in a time-series database with deduplication and alerting.

1. Store each panic in a PostgreSQL table with columns: `error_id`, `timestamp`, `panic_type`, `stack_trace_hash`, `path_pattern`, `method`, `count`
2. Hash the stack trace (excluding line numbers) to deduplicate — identical panics increment a counter rather than storing duplicates
3. Build a dashboard showing top panic types, panic rate over time, and per-endpoint panic distribution
4. Implement an alert: if a NEW panic type appears (stack hash not seen before), send a Slack notification with the stack trace and error ID
5. Add a configurable threshold: if a known panic type exceeds N occurrences in M minutes, escalate to PagerDuty

**Principal-level aspect:** This requires designing a data model for error fingerprinting, handling high write throughput during cascading failures, and building a system that on-call engineers will actually use during incidents.

### Challenge 2: Implement a Zero-Allocation (or Near-Zero) Instrumented Handler Wrapper

**Goal:** Measure and reduce allocations in the Logger + Recoverer middleware chain to sub-100-bytes per request at P99.

1. Benchmark the current Logger + Recoverer chain: `go test -bench=. -benchmem -benchtime=10s`
2. Profile heap allocations: `go test -bench=. -memprofile=mem.out && go tool pprof -alloc_objects mem.out`
3. Identify allocation sources: RouteContext pool, WrapResponseWriter, LogEntry, log buffers
4. Implement optimizations:
   - Pre-allocate LogEntry and WrapResponseWriter via sync.Pool
   - Use `strconv.AppendInt` instead of `fmt.Sprintf` for log formatting
   - Use a fixed-size ring buffer for log lines, flushing asynchronously
5. Re-benchmark and report allocation reduction percentage

**Principal-level aspect:** This exercise demands understanding Go's memory model, escape analysis, sync.Pool semantics, and the trade-off between allocation reduction and code complexity. The optimized version should be benchmarked against the naive version to prove the improvement is worth the complexity.

---

## Key Insights

- Chi's Recoverer uses the `defer/recover` pattern — `defer func() { recover() }()` in the middleware function. The defer fires when the handler panics, before the panic propagates to the HTTP server. This is the standard Go pattern for panic recovery, applied to the HTTP middleware context.

- The critical ordering for middleware is: Recoverer FIRST (registered before Logger), Logger SECOND. This ensures the Recoverer catches panics before the Logger's defer fires, so the Logger correctly sees the 500 status that Recoverer wrote. Get this wrong and you'll log 200 for panicked requests.

- `http.ErrAbortHandler` is a sentinel panic that MUST NOT be recovered. Recoverer re-panics with it to preserve net/http's timeout and connection abort mechanisms. Custom recoverers must include this check.

- Chi's `WrapResponseWriter` captures the FIRST status code written (via `WriteHeader()` or implied by first `Write()` call). Subsequent `WriteHeader()` calls are silently ignored — this is `net/http` behavior, not Chi-specific. Middleware that writes an early status prevents handlers from changing it.

- Production error responses should use a consistent JSON envelope: `{error, message, request_id, status}` with optional `details` for validation errors and `error_id` for 500s. Define this once in a shared package and enforce via code review.

- `log/slog` (Go 1.21+) replaces the need for third-party logging libraries in most cases. Integrate it with Chi by creating per-request child loggers with request-scoped attributes (`request_id`, `method`, `path`) and propagating them through `context.Context`.

- `debug.Stack()` in the Recoverer captures full goroutine stack traces (~10-50μs). This is acceptable overhead for panic recovery (which should be rare). For any other purpose (debug logging, error wrapping), avoid `debug.Stack()` — it's expensive and the information is rarely needed outside of panic scenarios.
