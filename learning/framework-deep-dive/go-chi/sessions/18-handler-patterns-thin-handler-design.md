# Session 18: Handler Patterns — Thin Handler Design, DTOs, Validation

## Why This Topic Exists

HTTP handlers are the entry point for every request entering a Go web service. They sit at the boundary between the network and the application, making them the most critical surface area for correctness, security, and maintainability. A poorly designed handler leaks concerns: business logic creeps in, validation becomes scattered, response formats diverge, and the handler grows into an untestable monolith.

The Thin Handler pattern solves these problems by constraining handlers to exactly three responsibilities: parse the incoming request, delegate to a service layer, and serialize the response. Every other concern — validation, business rules, persistence, orchestration — lives elsewhere.

Chi embraces this separation naturally because it is built on `net/http`. Unlike frameworks that inject their own abstractions (controllers, actions, resource objects), Chi gives you a standard `http.Handler` and trusts you to structure the rest. This session establishes the handler design patterns that the remaining sessions — service layer, repository, observability — build upon.

---

## Mental Model

Think of a handler as a **translation layer** between two worlds:

```
HTTP World                          Application World
═══════════                        ═════════════════
HTTP Method      ──►               Use Case / Command
URL Path         ──►               Route Parameters
Query String     ──►               Query Filters
Request Body     ──►   Handler    Request DTO
Request Headers  ──►   ──────►    Context Values
                                  ──────►  Service Layer
                                  ◄──────  Result/Error
Response Body    ◄──              Response DTO
Status Code      ◄──              Error → Status Mapping
Response Headers ◄──              Metadata Headers
```

The handler speaks HTTP on one side and Go domain types on the other. It should never know about database queries, message queues, or business rules. Its job is translation, period.

**Decision tree for handler placement:**

```
Does this code touch http.Request or http.ResponseWriter?
├── YES → Handler layer
└── NO
    Is this code a business rule, orchestration, or domain logic?
    ├── YES → Service layer
    └── NO
        Does this code touch a database, external API, or file system?
        ├── YES → Repository / Adapter layer
        └── NO → It's a utility or domain type
```

---

## Internal Architecture

### Two Handler Patterns in Go/Chi

#### Pattern 1: Handler Struct (Constructor Injection)

```go
// order_handler.go
type OrderHandler struct {
    orderService OrderService
    logger       *slog.Logger
}

func NewOrderHandler(orderService OrderService, logger *slog.Logger) *OrderHandler {
    return &OrderHandler{
        orderService: orderService,
        logger:       logger,
    }
}

func (h *OrderHandler) Create(w http.ResponseWriter, r *http.Request) {
    // parse → call service → write response
}

func (h *OrderHandler) Get(w http.ResponseWriter, r *http.Request) {
    // parse → call service → write response
}
```

**Wiring in `main.go`:**

```go
orderRepo := postgres.NewOrderRepository(db)
orderService := service.NewOrderService(orderRepo, eventBus)
orderHandler := handler.NewOrderHandler(orderService, logger)

r.Route("/orders", func(r chi.Router) {
    r.Post("/", orderHandler.Create)
    r.Get("/{orderID}", orderHandler.Get)
})
```

Advantages of the struct pattern:
- Dependencies are explicit and validated at startup (nil checks in constructor).
- Multiple dependencies are trivially added.
- Method set is discoverable on the struct.
- Testing: instantiate struct with mock service, call method directly.

#### Pattern 2: Closure-Based (Function Factory)

```go
// create_order.go
func MakeCreateOrderHandler(svc OrderService, logger *slog.Logger) http.HandlerFunc {
    return func(w http.ResponseWriter, r *http.Request) {
        // svc and logger captured in closure
    }
}

// Wire in main.go:
r.Post("/orders", MakeCreateOrderHandler(orderService, logger))
```

Closure-based handlers are useful when:
- A handler has very few dependencies (1-2).
- You want per-handler dependency sets (handler A gets loggerA, handler B gets loggerB).
- You prefer functional composition over method receivers.

### Go Handler Signature Deep Dive

```go
func(w http.ResponseWriter, r *http.Request)
```

**`http.ResponseWriter`** — an interface:

```go
type ResponseWriter interface {
    Header() Header
    Write([]byte) (int, error)
    WriteHeader(statusCode int)
}
```

Common implementations encountered in Chi:
- `http.response` — the standard `net/http` concrete type.
- `middleware.WrapResponseWriter` — Chi's wrapper that captures status code and byte count for logging/metrics.
- `httptest.ResponseRecorder` — test-only implementation.

**Critical gotcha**: `WriteHeader` is implicitly called with 200 when you first call `Write`. You cannot change the status code after that. Order matters:

```go
w.Header().Set("Content-Type", "application/json")  // ✅ Before WriteHeader
w.WriteHeader(http.StatusCreated)                     // ✅ Explicit, correct
json.NewEncoder(w).Encode(response)                   // ✅ After headers are set
```

**Anti-pattern**: Setting headers after `Write` or `WriteHeader` — silently ignored in production, confusing in tests.

### Middleware Stack in Chi

Every handler in Chi runs inside a middleware chain. Understanding the order of execution is essential for debugging handler behavior:

```
Request → Middleware1 → Middleware2 → Middleware3 → Handler → Middleware3 → Middleware2 → Middleware1 → Response
           (before)      (before)      (before)       ▾         (after)       (after)       (after)
```

Key Chi middleware that affects handler design:
- `middleware.RequestID` — injects `request_id` into context; handler can extract it via `middleware.GetReqID(r.Context())`.
- `middleware.RealIP` — resolves the real client IP through proxies; handler reads `r.RemoteAddr`.
- `middleware.Logger` — logs after handler returns; handler doesn't need to log routing info.
- `middleware.Recoverer` — catches panics in handler; handler can `panic` intentionally for unrecoverable errors.
- `middleware.Timeout` — context cancellation; handler must check `r.Context().Err()` for long-running operations.
- `middleware.Throttle` — rate limiting; handler receives 429 before being invoked.

### Request Parsing Patterns

#### JSON Body Parsing

```go
func decodeJSONBody[T any](r *http.Request) (T, error) {
    var v T
    if err := json.NewDecoder(r.Body).Decode(&v); err != nil {
        return v, fmt.Errorf("invalid JSON: %w", err)
    }
    return v, nil
}

func (h *OrderHandler) Create(w http.ResponseWriter, r *http.Request) {
    dto, err := decodeJSONBody[CreateOrderRequest](r)
    if err != nil {
        respondError(w, r, http.StatusBadRequest, "invalid request body", err)
        return
    }
    // ...
}
```

**Important Go 1.x constraints & Chi-specific behavior:**

| Behavior | Detail |
|----------|--------|
| `json.NewDecoder(r.Body)` | Consumes body; cannot re-read. Use `io.TeeReader` if middleware needs body inspection. |
| Max body size | No default limit. Wrap with `io.LimitReader` or `http.MaxBytesReader` to prevent OOM. |
| Unknown fields | `json.Decoder.DisallowUnknownFields()` to reject extra JSON keys. |
| Empty body | `json.Decoder.Decode` on empty body returns `io.EOF` — this is NOT an invalid-JSON error; handle specially if body is required. |
| Chi `middleware.AllowContentType` | Rejects requests before handler with wrong Content-Type (415). |

#### URL Path Parameters (Chi-specific)

```go
// Route: /orders/{orderID}/items/{itemID}
orderID := chi.URLParam(r, "orderID")
itemID := chi.URLParam(r, "itemID")
```

Chi stores URL params in the request context. `chi.URLParam` is a thin wrapper around `chi.RouteContext(r.Context()).URLParams.Get()`. The param map is populated before the handler runs (by Chi's router) and is read-only for the handler.

**Validation of path params**: Chi does NOT validate param format. A route `/orders/{orderID}` matches `/orders/123` and `/orders/../../etc/passwd`. Validate in the handler:

```go
orderID := chi.URLParam(r, "orderID")
if _, err := uuid.Parse(orderID); err != nil {
    respondError(w, r, http.StatusBadRequest, "invalid order ID format", err)
    return
}
```

#### Query String Parsing

```go
// Standard library:
page := r.URL.Query().Get("page")        // Returns "" if not present
limit := r.URL.Query().Get("limit")

// For typed parsing with defaults:
page, err := strconv.Atoi(r.URL.Query().Get("page"))
if err != nil || page < 1 {
    page = 1
}
```

Common query parameter patterns:
- **Pagination**: `?cursor=eyJ...&limit=20` (cursor-based, not offset).
- **Filtering**: `?status=paid&from=2024-01-01&to=2024-03-31`.
- **Sorting**: `?sort=-created_at` (minus sign = descending).
- **Field selection**: `?fields=id,name,status` (sparse fieldsets for mobile optimization).

#### Multipart / File Upload

```go
const maxUploadSize = 10 << 20 // 10 MB

func (h *MediaHandler) Upload(w http.ResponseWriter, r *http.Request) {
    r.Body = http.MaxBytesReader(w, r.Body, maxUploadSize)

    if err := r.ParseMultipartForm(maxUploadSize); err != nil {
        respondError(w, r, http.StatusBadRequest, "file too large or invalid form", err)
        return
    }

    file, header, err := r.FormFile("file")
    if err != nil {
        respondError(w, r, http.StatusBadRequest, "missing file field", err)
        return
    }
    defer file.Close()

    // Stream to object storage — don't buffer in memory
    err = h.storage.Upload(r.Context(), header.Filename, file)
    // ...
}
```

**Streaming concern**: `ParseMultipartForm` buffers the entire form in memory (up to `maxUploadSize`). For files larger than memory budget, use `r.MultipartReader()` which streams:

```go
mr, err := r.MultipartReader()
for {
    part, err := mr.NextPart()
    if err == io.EOF { break }
    // part is an io.Reader — stream it
}
```

---

## Runtime Behavior

### Request Lifecycle Through a Thin Handler

```
TIME  EVENT
────  ─────
T0    TCP connection accepted by net/http server
T1    TLS handshake (if HTTPS)
T2    HTTP request headers fully received
T3    Chi router matches route, extracts URL params, builds context
T4    Middleware stack begins execution (outermost → innermost)
T5    Middleware.RequestID injects X-Request-Id into context
T6    Middleware.Timeout creates context.WithTimeout
T7    Middleware.Logger starts request timer
T8    Handler is invoked  ←── Thin Handler starts here
T9      r.Body is read and JSON-decoded into DTO struct
T10     DTO is validated (go-playground/validator or custom)
T11     DTO is mapped to domain command/query object
T12     Service layer is called with context and command
T13       Service calls repository (DB query)
T14       Service calls event bus (publish domain event)
T15     Service returns result or error
T16     Result is mapped to response DTO
T17     Response DTO is JSON-encoded to w
T18     w.WriteHeader is called (or implicit via first Write)
T19    Handler returns  ←── Thin Handler ends here
T20    Middleware.Logger logs duration, status, bytes written
T21    Middleware stack unwinds (innermost → outermost)
T22    TCP connection returned to pool or closed
```

### Context Propagation

The `context.Context` is the thread that ties the entire request together:

```
Request context (created by net/http server)
  └── Chi route context (URL params injected)
        └── Middleware values (request ID, logger, user)
              └── Timeout/deadline context
                    └── Handler extracts values, passes to service
                          └── Service passes to repository
                                └── Repository passes to database driver
                                      └── database/sql respects context cancellation
```

Never ignore the context. Every slow operation (DB, HTTP call, I/O) must accept and respect the context. The handler receives it as `r.Context()` and must pass it forward.

### Error Handling Flow

```
Handler encounters error
  ├── Validation error → 400 Bad Request
  ├── Authentication error → 401 Unauthorized
  ├── Authorization error → 403 Forbidden
  ├── Resource not found → 404 Not Found
  ├── Business rule violation → 409 Conflict / 422 Unprocessable
  ├── Downstream timeout → 504 Gateway Timeout
  ├── Unknown/internal error → 500 Internal Server Error
  └── Panic → 500 (caught by middleware.Recoverer)
```

**Mapping Go errors to HTTP status codes** should be centralized in a helper function or response utility:

```go
func respondError(w http.ResponseWriter, r *http.Request, status int, message string, err error) {
    logger := middleware.GetLogEntry(r) // Chi logger from context
    logger.Error("handler error",
        slog.Int("status", status),
        slog.String("message", message),
        slog.String("error", err.Error()),
    )

    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(status)
    json.NewEncoder(w).Encode(map[string]string{"error": message})
}
```

---

## Request Flow Diagrams

### Flow 1: Create Order — End-to-End

```
Client                Chi Router          Middleware Stack        OrderHandler          OrderService         OrderRepo(PostgreSQL)
  │                      │                      │                      │                      │                      │
  │  POST /orders        │                      │                      │                      │                      │
  │  Body: {items:[...]} │                      │                      │                      │                      │
  │─────────────────────►│                      │                      │                      │                      │
  │                      │  Route Match         │                      │                      │                      │
  │                      │  /orders (POST)      │                      │                      │                      │
  │                      │─────────────────────►│                      │                      │                      │
  │                      │                      │  RequestID           │                      │                      │
  │                      │                      │─────────────────────►│                      │                      │
  │                      │                      │                      │  Validate JWT        │                      │
  │                      │                      │                      │  (via middleware)    │                      │
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │  decodeJSONBody()    │                      │
  │                      │                      │                      │  → CreateOrderReq    │                      │
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │  validate(dto)       │                      │
  │                      │                      │                      │  (go-playground)     │                      │
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │  toCommand(dto)      │                      │
  │                      │                      │                      │  → CreateOrderCmd    │                      │
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │  svc.Create(ctx,cmd) │                      │
  │                      │                      │                      │─────────────────────►│                      │
  │                      │                      │                      │                      │  Validate business   │
  │                      │                      │                      │                      │  rules (inventory,   │
  │                      │                      │                      │                      │  credit limit)       │
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │                      │  repo.Create(ctx,o)  │
  │                      │                      │                      │                      │─────────────────────►│
  │                      │                      │                      │                      │                      │  INSERT INTO orders
  │                      │                      │                      │                      │                      │  RETURNING id
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │                      │  repo.SaveItems()    │
  │                      │                      │                      │                      │─────────────────────►│
  │                      │                      │                      │                      │                      │  INSERT INTO items
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │                      │  Publish Event       │
  │                      │                      │                      │                      │  OrderCreated        │
  │                      │                      │                      │                      │─────────────────     │
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │                      │  return Order        │
  │                      │                      │                      │                      │◄─────────────────────│
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │  Order result         │                      │
  │                      │                      │                      │◄─────────────────────│                      │
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │  toDTO(order)        │                      │
  │                      │                      │                      │  → OrderResponse     │                      │
  │                      │                      │                      │                      │                      │
  │                      │                      │                      │  w.WriteHeader(201)  │                      │
  │                      │                      │                      │  json.Encode(resp)   │                      │
  │                      │                      │                      │                      │                      │
  │  201 Created         │                      │                      │                      │                      │
  │  {id,status,items...}│                      │                      │                      │                      │
  │◄─────────────────────│◄─────────────────────│◄─────────────────────│                      │                      │
  │                      │                      │                      │                      │                      │
```

### Flow 2: Streaming Response (SSE)

```
Client                Chi Router          Middleware          Handler              PriceFeed
  │                      │                    │                   │                    │
  │  GET /events         │                    │                   │                    │
  │  Accept: text/event-stream              │                   │                    │
  │─────────────────────►│                    │                   │                    │
  │                      │───────────────────►│                   │                    │
  │                      │                    │──────────────────►│                    │
  │                      │                    │                   │  Flusher check     │
  │                      │                    │                   │  w.(http.Flusher)  │
  │                      │                    │                   │                    │
  │                      │                    │                   │  Subscribe()       │
  │                      │                    │                   │───────────────────►│
  │                      │                    │                   │                    │
  │  Headers: Connection: keep-alive          │                   │                    │
  │          Cache-Control: no-cache          │                   │                    │
  │          Content-Type: text/event-stream  │                   │                    │
  │◄─────────────────────│◄───────────────────│◄──────────────────│                    │
  │                      │                    │                   │                    │
  │  data: {"price":100} │                    │                   │◄─── PriceTick(100) │
  │◄─────────────────────│◄───────────────────│◄──────────────────│                    │
  │                      │                    │                   │  flusher.Flush()   │
  │                      │                    │                   │                    │
  │  data: {"price":101} │                    │                   │◄─── PriceTick(101) │
  │◄─────────────────────│◄───────────────────│◄──────────────────│                    │
  │                      │                    │                   │                    │
  │  ... connection stays alive, streaming    │                   │                    │
  │                      │                    │                   │                    │
  │  (client closes)     │                    │                   │  ctx.Done()        │
  │                      │                    │                   │  unsubscribe       │
  │                      │                    │                   │                    │
```

### Flow 3: File Upload with Streaming

```
Client                Chi                Handler             BlobStorage
  │                    │                    │                    │
  │  POST /files       │                    │                    │
  │  multipart/form    │                    │                    │
  │───────────────────►│                    │                    │
  │                    │───────────────────►│                    │
  │                    │                    │                    │
  │                    │                    │  MaxBytesReader    │
  │                    │                    │  (10 MB limit)     │
  │                    │                    │                    │
  │                    │                    │  MultipartReader() ← stream mode
  │                    │                    │                    │
  │ chunk1 (64KB)      │                    │─────────┐          │
  │───────────────────►│───────────────────►│  Read   │          │
  │                    │                    │◄────────┘          │
  │                    │                    │  Write(chunk1)     │
  │                    │                    │───────────────────►│
  │                    │                    │                    │  PUT /objects/abc
  │                    │                    │                    │  (partial upload)
  │                    │                    │                    │
  │ chunk2 (64KB)      │                    │─────────┐          │
  │───────────────────►│───────────────────►│  Read   │          │
  │                    │                    │◄────────┘          │
  │                    │                    │  Write(chunk2)     │
  │                    │                    │───────────────────►│
  │                    │                    │                    │
  │ ...continues       │                    │                    │
  │                    │                    │                    │
  │ (stream ends)      │                    │  Close()           │
  │                    │                    │───────────────────►│
  │                    │                    │                    │  Finalize upload
  │                    │                    │  return URL        │
  │                    │                    │◄───────────────────│
  │  201 Created       │                    │                    │
  │  {url: "..."}      │                    │                    │
  │◄───────────────────│◄───────────────────│                    │
```

---

## Lifecycle Diagrams

### Handler Struct Lifecycle

```
Application Startup
  │
  ▼
main()
  ├── db, _ := sql.Open("postgres", dsn)
  ├── repo := postgres.NewOrderRepository(db)           ← Constructor: validates db != nil
  ├── svc  := service.NewOrderService(repo, eventBus)   ← Constructor: validates deps
  ├── h    := handler.NewOrderHandler(svc, logger)       ← Constructor: validates deps
  │
  ├── r := chi.NewRouter()
  ├── r.Use(middleware.RequestID)
  ├── r.Use(middleware.RealIP)
  ├── r.Use(middleware.Logger)
  ├── r.Use(middleware.Recoverer)
  │
  ├── r.Route("/orders", func(r chi.Router) {
  │       r.Post("/", h.Create)    ← Method reference (func value)
  │       r.Get("/{id}", h.Get)    ← Method reference
  │       r.Put("/{id}", h.Update)
  │       r.Delete("/{id}", h.Cancel)
  │   })
  │
  ├── srv := &http.Server{Addr: ":8080", Handler: r}
  └── srv.ListenAndServe()

───────────────────────────────────────────────────────

During Request (per-request lifecycle):
  Go runtime goroutine pool allocates goroutine for request
    │
    ▼
  net/http reads request, builds *http.Request
    │
    ▼
  Chi router matches route, builds context with URL params
    │
    ▼
  Middleware chain executes (wrapping handler)
    │
    ▼
  h.Create(w, r) is called  ← Handler method executes
    │ request body decoded into CreateOrderRequest struct (allocation)
    │ CreateOrderRequest validated (go-playground/validator)
    │ DTO mapped to CreateOrderCommand (may be same struct)
    │ h.orderService.CreateOrder(ctx, cmd) called
    │   └── Service executes business logic
    │   └── Service returns Order or error
    │ Result mapped to OrderResponse DTO
    │ w.Header().Set("Content-Type", "application/json")
    │ w.WriteHeader(201)
    │ json.NewEncoder(w).Encode(orderResponse)
    │
    ▼
  Handler returns (goroutine returns to pool)
    │
    ▼
  Middleware unwinds (Logger logs, Recoverer clears)
    │
    ▼
  TCP connection returned to pool (HTTP/1.1 keep-alive) or closed

───────────────────────────────────────────────────────

Graceful Shutdown:
  SIGTERM received
    │
    ▼
  srv.Shutdown(ctx) called
    ├── No new connections accepted
    ├── In-flight requests drain (respect context deadline)
    │     └── Handlers receive context cancellation via r.Context().Done()
    └── All connections closed
```

---

## Source Code Reading Guide

### Files to Read (in order)

| # | File | Purpose | Time |
|---|------|---------|------|
| 1 | `net/http/server.go` lines 1-100, `Server.Serve` method | Understand how Go accepts connections and dispatches to handlers. Focus on `serverHandler.ServeHTTP` which is where your handler is invoked. | 30 min |
| 2 | `net/http/server.go` `response` struct (around line 350) | See the concrete implementation of `http.ResponseWriter`. Understand the `wroteHeader` boolean — this is why you can't change the status code after writing. | 20 min |
| 3 | `net/http/httputil/reverseproxy.go` `ReverseProxy.ServeHTTP` (optional) | If your service acts as a reverse proxy, understand how Chi integrates with reverse proxy patterns. | 15 min |
| 4 | `go-chi/chi/v5/mux.go` `Mux.ServeHTTP` method | How Chi routes requests to your handler. Understand the radix tree route matching. | 30 min |
| 5 | `go-chi/chi/v5/context.go` `RouteContext` struct | How URL params, route patterns, and route paths are stored in context. | 15 min |
| 6 | `go-chi/chi/v5/middleware/logger.go` | How Chi's logger middleware captures and logs handler results. | 20 min |
| 7 | `go-chi/chi/v5/middleware/recoverer.go` | How panics in your handler are caught and converted to 500 responses. | 10 min |
| 8 | `go-chi/render/render.go` (from `github.com/go-chi/render`) | Chi's response rendering utilities: `render.JSON`, `render.XML`, `render.PlainText`. Understand `render.Respond` pattern. | 20 min |

### What to Ignore

- `mux.go` radix tree implementation details (unless debugging routing performance).
- `middleware/` implementations other than Logger, Recoverer, Timeout, RequestID (read on demand).
- `render/` protobuf and XML rendering (unless you use them).
- Chi's internal test files.

### Reading Strategy

Start with `net/http/server.go` because Chi sits on top of it. Understanding Go's built-in HTTP server is prerequisite to understanding Chi. When you encounter a `ServeHTTP` call in Chi's source, trace upward to see which `http.Handler` is being wrapped.

---

## Production Failure Scenarios

### Failure 1: Unbounded Request Body (OOM)

**Scenario**: Attacker sends a 4 GB JSON payload to `/orders`.

**Root cause**: No `http.MaxBytesReader` or `io.LimitReader` wrapping `r.Body`.

**Symptom**: Memory usage spikes, GC pauses increase, OOM killer terminates process.

**Detection**: Goroutine profiles show handler goroutines stuck in `json.NewDecoder.Decode` with large memory allocations.

**Fix**:
```go
const maxBodySize = 1 << 20 // 1 MB
r.Body = http.MaxBytesReader(w, r.Body, maxBodySize)
```

Chi middleware approach:
```go
// Per-route or global
func MaxBodySize(limit int64) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            r.Body = http.MaxBytesReader(w, r.Body, limit)
            next.ServeHTTP(w, r)
        })
    }
}
```

### Failure 2: Unhandled JSON Unknown Fields (Silent Data Loss)

**Scenario**: Client sends `{"user_name": "alice", "amount_cents": 1000}`. Handler struct has `Amount` (tag: `json:"amount"`) not `amount_cents`. The `amount_cents` field is silently ignored.

**Root cause**: `json.Decoder` by default ignores unknown fields.

**Fix**:
```go
dec := json.NewDecoder(r.Body)
dec.DisallowUnknownFields()
```

### Failure 3: Panic Not Caught (Process Crash)

**Scenario**: Handler dereferences nil pointer because service dependency was nil (forgot nil check in constructor).

**Root cause**: Lack of `middleware.Recoverer` in middleware stack, OR Recoverer placed after the handler in the middleware chain order.

**Fix**: Always include `recoverer` as one of the outermost middleware:
```go
r.Use(middleware.Recoverer)  // Should be early in the Use() chain
```

### Failure 4: Slow Header Parsing (Slowloris)

**Scenario**: Attacker sends HTTP headers 1 byte per second. Handler is never reached — the attack hits net/http's header reading phase.

**Root cause**: `http.Server` has no `ReadHeaderTimeout` set (zero value = infinite).

**Fix**:
```go
srv := &http.Server{
    Addr:              ":8080",
    Handler:           r,
    ReadHeaderTimeout: 10 * time.Second,  // ← critical
    ReadTimeout:       30 * time.Second,
    WriteTimeout:      30 * time.Second,
    IdleTimeout:       120 * time.Second,
}
```

### Failure 5: Response Already Written (Double Write)

**Scenario**: Handler calls `w.WriteHeader(500)` but middleware (or earlier code path) already called `w.WriteHeader(200)`.

**Root cause**: `http.ResponseWriter` writes the first status code and then ignores subsequent `WriteHeader` calls. The response body might show success, but the flow indicates an error.

**Fix**: Use Chi's `middleware.WrapResponseWriter` which tracks the written status. Check `ww.Status()` before writing.

### Failure 6: Context Ignored (CPU Waste)

**Scenario**: Client disconnects (closes browser tab), but handler continues processing (DB query + external API call) because it never checks `r.Context().Err()`.

**Root cause**: Handler doesn't pass context to service layer, or service layer ignores `ctx.Done()`.

**Fix**: Always check `select { case <-ctx.Done(): return ctx.Err(); default: }` before expensive operations. Pass `r.Context()` to service → repository → database driver.

---

## Debugging Techniques

### Technique 1: Request Body Logging for Debug

```go
// DEBUG ONLY — logs full request body (beware: consumes the body!)
bodyBytes, _ := io.ReadAll(r.Body)
logger.Debug("request body", slog.String("body", string(bodyBytes)))
r.Body = io.NopCloser(bytes.NewBuffer(bodyBytes)) // Replace body for downstream
```

### Technique 2: Handler Latency Breakdown

```go
func latencyMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        t0 := time.Now()
        defer func() {
            logger.Debug("handler latency", slog.Duration("total", time.Since(t0)))
        }()
        next.ServeHTTP(w, r)
    })
}
```

### Technique 3: Request Dump for Replay

```go
// Chi middleware to dump requests for debugging
import "net/http/httputil"

func debugDumpMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        dump, _ := httputil.DumpRequest(r, true) // true = include body
        logger.Debug("request dump", slog.String("dump", string(dump)))
        next.ServeHTTP(w, r)
    })
}
```

### Technique 4: Finding Which Middleware Added a Header

Use `curl -v` to inspect response headers. Each middleware adds specific headers:
- `X-Request-Id` → `middleware.RequestID`
- `X-Real-Ip` → `middleware.RealIP`
- `Content-Type` → your handler (or `render.JSON`)

### Technique 5: Route Dump in Tests

```go
import "net/http/httptest"

func TestRouteMatching(t *testing.T) {
    r := chi.NewRouter()
    // ... register routes ...
    chi.Walk(r, func(method, path string, handler http.Handler, middlewares ...func(http.Handler) http.Handler) error {
        t.Logf("%s %s", method, path)
        return nil
    })
}
```

### Technique 6: Reproduce Production Request

```go
// Capture a real request in production via middleware
func captureMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        if os.Getenv("DEBUG_CAPTURE") == "true" {
            // Serialize and log the full request for replay in tests
            dump, _ := httputil.DumpRequest(r, true)
            fmt.Println(string(dump))
        }
        next.ServeHTTP(w, r)
    })
}
```

---

## Observability Considerations

### What to Log in Handlers

| Event | Level | Fields |
|-------|-------|--------|
| Request received | Debug (or middleware-level Info) | method, path, request_id |
| Parse failure | Warn | error, body_snippet, content_type |
| Validation failure | Info | validation_errors, dto_type |
| Input sanitized/rejected | Warn | rejected_field, reason, value_snippet |
| Response written | Debug (or middleware-level Info) | status, duration, bytes_written |

Handlers should NOT log successful responses at Info level — this duplicates middleware logging. Handlers should log **exceptional** conditions: malformed requests, validation failures, security-relevant rejections.

### Metrics from Handlers

Use middleware-level metrics for RED (Rate, Errors, Duration). Handlers should emit **business-level metrics**:

```go
// Inside handler, after successful service call:
metrics.OrdersCreated.Inc()
metrics.OrderValue.Add(float64(order.TotalCents) / 100.0)
```

### Traces from Handlers

Handlers typically don't create spans themselves — the `otelhttp` middleware creates the root span. But handlers can add attributes:

```go
import "go.opentelemetry.io/otel/trace"

func (h *OrderHandler) Create(w http.ResponseWriter, r *http.Request) {
    span := trace.SpanFromContext(r.Context())
    span.SetAttributes(
        attribute.String("order.customer_id", dto.CustomerID),
        attribute.Float64("order.amount", dto.Amount),
    )
    // ...
}
```

### Structured Logging with slog in Handlers

```go
func (h *OrderHandler) Create(w http.ResponseWriter, r *http.Request) {
    logger := h.logger.With(
        slog.String("handler", "OrderHandler.Create"),
        slog.String("request_id", middleware.GetReqID(r.Context())),
    )

    dto, err := decodeJSONBody[CreateOrderRequest](r)
    if err != nil {
        logger.Warn("failed to decode JSON body",
            slog.String("error", err.Error()),
            slog.String("content_type", r.Header.Get("Content-Type")),
        )
        respondError(w, r, http.StatusBadRequest, "invalid request body", err)
        return
    }
    logger.Debug("request parsed", slog.Any("dto", dto)) // Be careful with PII in dto!
    // ...
}
```

---

## Performance Implications

### Allocation Profile of a Thin Handler

Each request through a thin handler allocates:

| Allocation | Size (approx) | Frequency |
|------------|---------------|-----------|
| Request DTO | Varies by struct | per request |
| Response DTO | Varies by struct | per request |
| JSON decoding buffer | Internal to `json.Decoder` | per request |
| JSON encoding scratch space | Internal to `json.Encoder` | per request |
| URL param map (from context) | Already allocated by Chi | 0 (reused from pool) |

**Optimization**: Use `sync.Pool` for frequently allocated DTOs:

```go
var createOrderPool = sync.Pool{
    New: func() any { return new(CreateOrderRequest) },
}

func (h *OrderHandler) Create(w http.ResponseWriter, r *http.Request) {
    dto := createOrderPool.Get().(*CreateOrderRequest)
    defer func() {
        *dto = CreateOrderRequest{} // Reset
        createOrderPool.Put(dto)
    }()

    if err := json.NewDecoder(r.Body).Decode(dto); err != nil {
        // ...
    }
}
```

This is a micro-optimization; only apply if profiling shows significant DTO allocation overhead (> 5% of alloc space).

### JSON vs Protobuf Tradeoffs for Handlers

| Aspect | JSON (`encoding/json`) | Protobuf (`google.golang.org/protobuf`) |
|--------|------------------------|------------------------------------------|
| Human readable | Yes | No (binary) |
| Serialization CPU | High (reflection-based) | Low (generated code) |
| Allocations | Many small allocs | Fewer, controlled allocs |
| Schema enforcement | Runtime (validation library) | Compile-time (proto definition) |
| Cross-language | Universal | Requires proto schema distribution |
| Content-Type | `application/json` | `application/x-protobuf` |
| Browser-visible | Yes (fetch + JSON.parse) | Requires binary decoding in JS |

For internal service-to-service communication, prefer protobuf. For public APIs, JSON is standard.

### Middleware Overhead

Each middleware in your Chi stack adds overhead. Profile the tradeoff:

```go
// Benchmark with different middleware configurations
func BenchmarkHandlerWithMiddleware(b *testing.B) {
    r := chi.NewRouter()
    r.Use(middleware.RequestID)
    r.Use(middleware.Logger)
    r.Get("/", func(w http.ResponseWriter, r *http.Request) {})
    // ...
}
```

Typical overhead per middleware: 1-5µs. A stack of 10 middleware = 10-50µs per request. At 10,000 req/s, this is 0.1-0.5 seconds of CPU per second spent on middleware. Acceptable for most services; profile before optimizing.

---

## Architecture Implications

### Multi-File Handler Organization

For a feature (e.g., Orders), organize handlers as:

```
handler/
  order/
    handler.go         // OrderHandler struct + constructor
    create.go          // Create method
    get.go             // Get method
    list.go            // List method
    update.go          // Update method
    cancel.go          // Cancel method
    dto.go             // Request/Response DTOs
    mapping.go         // DTO ↔ Domain mapping functions
    order_test.go      // Shared test utilities for order handler tests
    create_test.go     // Tests for Create
    get_test.go        // Tests for Get
```

Each file maps to a single handler method. The `handler.go` file contains the struct definition and constructor. This organization scales to hundreds of endpoints without creating a single 5000-line file.

### Validation Strategy

Three layers of validation, each at the right boundary:

| Layer | What to Validate | Tool |
|-------|-----------------|------|
| HTTP layer (handler) | Data types, required fields, format constraints | `go-playground/validator`, custom check |
| Domain layer (service) | Business rules, invariants | Go code (no library) |
| Database layer (repository) | Uniqueness, foreign keys, NOT NULL | Database constraints |

```go
// Handler-level: structural validation
type CreateOrderRequest struct {
    CustomerID string          `json:"customer_id" validate:"required,uuid"`
    Items      []OrderItemDTO  `json:"items" validate:"required,min=1,max=100,dive"`
}

// Service-level: business validation
func (s *OrderService) Create(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    if cmd.TotalCents > s.config.MaxOrderValue {
        return nil, ErrOrderExceedsLimit
    }
    // ...
}
```

**Rule**: If validation requires a database lookup (e.g., "customer must exist"), it belongs in the service layer, not the handler.

### DTO Design

```go
// Request DTO — what the client sends
type CreateOrderRequest struct {
    CustomerID  string         `json:"customer_id"`
    Items       []OrderItemDTO `json:"items"`
    ShippingAddress AddressDTO `json:"shipping_address"`
    IdempotencyKey string      `json:"idempotency_key,omitempty"`
}

// Response DTO — what the client receives
type OrderResponse struct {
    ID         string         `json:"id"`
    Status     string         `json:"status"`
    TotalCents int64          `json:"total_cents"`
    Items      []OrderItemDTO `json:"items"`
    CreatedAt  time.Time      `json:"created_at"`
}

// Domain model — internal representation
type Order struct {
    ID         uuid.UUID
    CustomerID uuid.UUID
    Status     OrderStatus
    Items      []OrderItem
    TotalCents int64
    CreatedAt  time.Time
    UpdatedAt  time.Time
}

// Mapping functions
func (dto CreateOrderRequest) ToCommand() CreateOrderCommand { /* ... */ }
func (o Order) ToResponse() OrderResponse { /* ... */ }
```

**Key DTO principles**:
1. DTOs are owned by the handler layer, never imported by service or repository.
2. DTO fields use JSON tags matching the external API contract.
3. Domain models use Go types (`uuid.UUID`, enums, `time.Time`) without JSON tags.
4. Mapping is explicit — use mapping functions, not struct embedding or type aliases.
5. When DTO == domain model in structure, it's still worth keeping them separate for decoupling.

---

## Team Ownership Implications

### Handler Team Ownership

| Factor | Recommendation |
|--------|---------------|
| Who owns handlers? | The team that owns the API contract (often the frontend-facing team). |
| Handler + Service coupling | Low. Handlers depend on service interfaces, not implementations. |
| DTO versioning | API versioning belongs to the handler layer. A v2 handler can map to the same v1 service. |
| Documentation | OpenAPI/Swagger specs map 1:1 to handler endpoints. Handlers are the source of truth for API documentation. |
| Testing | Handlers should be tested with mock services (unit tests) and integration tests with real HTTP server. |

### Separation of Concerns Across Teams

```
Team A (API/Frontend)          Team B (Business Logic)       Team C (Infrastructure)
──────────────────────         ─────────────────────         ──────────────────────
Handler layer                  Service layer                 Repository layer
- DTOs                         - Business rules              - SQL queries
- Validation (structural)      - Orchestration               - Connection pooling
- Response formatting          - Domain events               - Transaction mgmt
- API versioning               - Validation (business)       - Migration scripts
- OpenAPI specs                - Domain models               - Read replicas
```

The handler's thin nature makes it easy for a frontend-focused team to own the API surface without understanding database internals.

---

## Interview Questions

### Q1: What are the three responsibilities of a well-designed HTTP handler?

**Answer**: 1) Parse/validate the incoming HTTP request into domain DTOs, 2) Call the service layer with the command/query and context, 3) Serialize the service result into an HTTP response. The handler should never contain business logic, database calls, or orchestration logic.

### Q2: Why does Chi's `middleware.Logger` log after the handler returns, not before?

**Answer**: Because to log the response status code and duration, the middleware must observe the handler result. The Logger wraps the ResponseWriter to capture the status code (implicitly set to 200 by first Write call if not explicitly set), then after the handler returns and the response is complete, it emits the log entry with method, path, status, and duration.

### Q3: When would you choose the Handler struct pattern over closure-based handlers?

**Answer**: The struct pattern is preferred when: (1) the handler has 3+ dependencies, (2) multiple handler methods share the same dependencies (e.g., CRUD operations), (3) you want explicit constructor validation of all dependencies at startup time. Closure-based handlers are simpler for 1-2 dependency cases and offer more granular dependency selection per endpoint.

### Q4: You call `w.WriteHeader(500)` but the client receives 200. What happened?

**Answer**: Some code (middleware or earlier handler logic) already called `Write` (which implicitly calls `WriteHeader(200)`) or explicitly called `WriteHeader(200)`. The `http.ResponseWriter` implementation has a `wroteHeader` boolean flag — once set to true, subsequent `WriteHeader` calls are silently ignored.

### Q5: A handler calls `json.NewDecoder(r.Body).Decode(&v)` twice. What happens?

**Answer**: The second `Decode` receives `io.EOF` because the body stream has been consumed. The `r.Body` is an `io.ReadCloser` and cannot be re-read. To read the body multiple times, use `io.TeeReader` in middleware to capture body bytes, or replace `r.Body` with a `bytes.NewReader` wrapping a saved copy.

### Q6: How do you handle request timeouts in a Chi handler?

**Answer**: Use `middleware.Timeout` which wraps the request context with a deadline. In the handler, always pass `r.Context()` to downstream operations (service → repository → DB). Before expensive operations, check `select { case <-ctx.Done(): return ctx.Err(); default: }`. The net/http server also has `ReadTimeout` and `WriteTimeout` as safety nets.

### Q7: What's the difference between `chi.URLParam` and `r.URL.Query().Get`?

**Answer**: `chi.URLParam(r, "id")` returns a path parameter from the route pattern (e.g., `/orders/{id}` → `orders/123` → `"123"`). `r.URL.Query().Get("id")` returns a query string parameter (e.g., `/orders?id=123`). Chi stores URL params in the request context; query params are in the URL struct. Both return `""` if the key is not found.

### Q8: Your handler processes a 1 GB file upload. Is `r.ParseMultipartForm()` appropriate?

**Answer**: No. `ParseMultipartForm` buffers the entire form in memory up to the provided `maxMemory` parameter. For streaming uploads, use `r.MultipartReader()` which returns `*multipart.Reader` — an iterator over multipart parts that does not buffer the full body. Each `part` is an `io.Reader` that can be streamed to object storage.

### Q9: Where should request validation live — handler or service layer?

**Answer**: Structural validation (is it a valid UUID? Is the email format correct? Are required fields present?) belongs in the handler layer because it's about the HTTP/API contract. Business validation (does this customer have sufficient credit? Is this item in stock?) belongs in the service layer because it requires domain knowledge and often database access. Never duplicate the same validation in both layers.

### Q10: How does Chi handle a request to `/orders/123` when the pattern is `/orders/{id}`?

**Answer**: Chi's radix tree routes the request to the `/orders/{id}` handler. Before the handler executes, Chi stores `{"id": "123"}` in the `RouteContext` within the request context. `chi.URLParam(r, "id")` retrieves this value. If no route matches, Chi's built-in NotFound handler returns 404 (or a custom NotFound handler if configured via `r.NotFound()`).

---

## Hands-On Exercises

### Exercise 1: Build a Thin CRUD Handler (60 min)

Create a complete CRUD handler for a "Product" resource:

```
Product:
  - id: UUID
  - name: string (required, 1-200 chars)
  - price_cents: int64 (required, > 0)
  - category: string (enum: "food", "electronics", "clothing")
  - created_at: time.Time
  - updated_at: time.Time
```

**Tasks**:
1. Define `CreateProductRequest`, `UpdateProductRequest`, `ProductResponse` DTOs with JSON tags and `go-playground/validator` tags.
2. Define `ProductService` interface with `Create`, `Get`, `List`, `Update`, `Delete` methods.
3. Implement `ProductHandler` struct that takes `ProductService` and `*slog.Logger`.
4. Implement each handler method: parse → call service → write response.
5. Write a response helper `respondJSON` and `respondError` that writes consistent JSON responses.
6. Write unit tests for each handler using `httptest.NewRecorder` and a mock `ProductService`.
7. Wire everything in a `main()` with Chi router and test with `curl`.

### Exercise 2: DTO Mapping Patterns (30 min)

Start with a handler that passes the request DTO directly to the service (anti-pattern). Refactor to:
1. Separate Request DTO, Command struct, Domain Model, and Response DTO.
2. Write explicit mapping functions.
3. Handle the case where the Request DTO has a different field name for a domain field (e.g., JSON `"customer_email"` maps to domain `EmailAddress`).
4. Add a `version` field to the API. Create v2 handler that maps old DTO format to the same domain command.

### Exercise 3: Streaming Response Handler (45 min)

Implement a Server-Sent Events (SSE) endpoint that streams order status updates:
1. Implement `GET /orders/{id}/events` that streams status changes.
2. Subscribe to a Go channel of `OrderEvent` in the handler.
3. Write SSE formatted events: `data: {"status": "shipped"}\n\n`.
4. Use `http.Flusher` to flush each event immediately.
5. Handle client disconnection via `r.Context().Done()`.
6. Write a test that creates multiple events and verifies they are received in order.
7. Handle the edge case where no updates occur within a timeout (send a heartbeat `:comment\n\n`).

### Exercise 4: Validation Middleware (30 min)

Create a custom Chi middleware that validates request bodies against a `validator.Validate` instance:
1. Define a `Validatable` interface: `Validate() error`.
2. Implement middleware that calls `Validate()` after decoding.
3. Handle the case where the request body is not JSON.
4. Return structured validation errors (field → error message).
5. Test with both valid and invalid payloads.

### Exercise 5: File Upload with Progress (60 min)

Build a file upload handler with progress tracking:
1. Accept multipart file upload.
2. Stream the file to a local filesystem or mock storage.
3. Report upload progress via Server-Sent Events on a separate endpoint.
4. Use `sync.Map` or a concurrent-safe map to store upload progress.
5. Handle concurrent uploads.
6. Clean up stale upload progress entries (use a background goroutine with TTL).
7. Benchmark upload throughput with different buffer sizes.

---

## Advanced Challenges

### Challenge 1: Protocol Negotiation Handler

Implement a single Chi route `/orders/{id}` that returns JSON or Protobuf based on the `Accept` header:

```go
func (h *OrderHandler) Get(w http.ResponseWriter, r *http.Request) {
    order, err := h.service.Get(r.Context(), chi.URLParam(r, "id"))
    if err != nil { /* ... */ }

    switch r.Header.Get("Accept") {
    case "application/x-protobuf":
        data, _ := proto.Marshal(order.ToProto())
        w.Header().Set("Content-Type", "application/x-protobuf")
        w.Write(data)
    default:
        respondJSON(w, r, http.StatusOK, order.ToResponse())
    }
}
```

Extend this to handle gzip compression (look at Accept-Encoding), If-None-Match (ETags), and Range requests for partial responses.

### Challenge 2: Handler Generator from OpenAPI Spec

Write a Go program (or a code generation script) that reads an OpenAPI/Swagger YAML file and generates:
1. Chi route registration code.
2. DTO structs with JSON and validation tags.
3. Handler method stubs.
4. Router wiring in `main.go` format.

The generated handlers should compile immediately (though they'll have `// TODO` comments for service implementations).

### Challenge 3: Zero-Timeout Handler

Implement a Chi middleware + handler pair that guarantees response within a hard deadline:
1. If the handler takes longer than N milliseconds, write a 503 response and cancel the context.
2. Write a response that includes a `Retry-After` header.
3. Ensure the handler goroutine doesn't leak after cancellation (use context properly).
4. Implement a circuit breaker that stops even invoking the handler if too many timeouts occur.
5. Write integration tests that inject artificial delays and verify the circuit breaker behavior.

---

## Key Insights

1. **Handlers are a translation layer, not a logic layer.** If your handler contains `if` statements with business meaning, move them to the service layer.

2. **Chi is `net/http`-native.** Every Go HTTP technique works with Chi. You don't need "Chi way" vs "Go way" — they are the same thing.

3. **`WriteHeader` is a one-shot operation.** After the first byte is written (or `WriteHeader` called), the status code is locked. Order your handler code: headers → status → body.

4. **Never ignore `r.Context()`.** The context carries deadline, cancellation, and trace information. Every downstream call must receive and respect it.

5. **DTOs are defense in depth.** They prevent API contract changes from rippling into business logic. They prevent domain model changes from breaking client expectations.

6. **slog is now standard (Go 1.21+).** Use structured logging with slog in handlers. Extract the logger from middleware context (Chi's `middleware.GetLogEntry`) for consistent fields across the request lifecycle.

7. **Test handlers with `httptest.NewRecorder` and mocked services.** Handlers should be the most tested layer because they are the attack surface of your application.

8. **Middleware is composition, not inheritance.** Compose handler behavior by stacking middleware, not by creating base handler classes or embedded types.

9. **Streaming isn't just for video.** SSE for real-time updates, multipart streaming for large uploads, and gzip streaming for large responses all keep memory usage constant regardless of data size.

10. **The thin handler pattern enables everything else.** Clean service interfaces, testable business logic, consistent error handling, and observable request flows all depend on handlers staying thin.
