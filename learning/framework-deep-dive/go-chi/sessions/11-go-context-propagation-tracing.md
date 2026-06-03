# Session 11: Context Propagation: context.Context Deep Dive

## Why This Topic Exists

Every Go HTTP handler receives a `context.Context` as `r.Context()`. Every database query accepts one as the first argument. Every gRPC call, Kafka consumer, and background worker passes a context around. Yet most Go developers use context as an opaque blob—they know it carries deadlines and values, but they do not understand how it actually works. When a request times out unexpectedly, they add `context.Background()` and move on. When a value is missing from the context, they silently return a zero value instead of an error. This session is about understanding `context.Context` at the level where you can debug production issues involving context propagation, trace requests across service boundaries, and design systems that correctly handle cancellation and deadlines.

The `context` package was introduced in Go 1.7 (2016) and is now one of the most fundamental packages in the standard library. It is approximately 500 lines of code—small enough to read in an hour, deep enough to study for a career. Understanding its internals reveals: why context keys must be typed (not strings), why `WithValue` stores values in a linked list (not a map), why `WithTimeout` allocates a timer on the heap (and the GC implications), and how parent context cancellation cascades to all children. This knowledge directly translates to writing correct, performant Go services.

Beyond the internals, context is the backbone of distributed tracing. When a request enters your Chi service, middleware extracts trace headers (`traceparent`, `tracestate`), creates a span, and stores the trace context in the request context. Every downstream call (database, gRPC, Kafka) propagates this trace context through the context. Without understanding context propagation, you cannot implement distributed tracing correctly—and without distributed tracing, you cannot debug microservices in production.

## Mental Model

Think of `context.Context` as a tree of immutable nodes. Each node has a pointer to its parent. The root of the tree is `context.Background()` (or `context.TODO()`). Each call to `WithCancel`, `WithTimeout`, `WithDeadline`, or `WithValue` creates a new child node that wraps the parent. When a parent is canceled, the cancellation propagates downward to all children. This is not a notification system—it is a tree of cancel channels, where each child goroutine can `select` on `ctx.Done()` to be notified.

For values, the tree becomes a linked list. `WithValue(parent, key, val)` creates a new node that stores the (key, val) pair and points to the parent. When `ctx.Value(key)` is called, the runtime traverses the linked list upward from the current node to the root, comparing keys. This is O(n) in the depth of the context tree, but in practice the depth is small (< 20 layers in most applications), so the lookup is fast. The key insight: context values are inherited downward (children see their parent's values) but cannot be modified upward (parents never see their children's values). This makes context value propagation unidirectional, which prevents subtle mutation bugs.

```
Context Tree for a Request:

                    context.Background()
                            |
                    ┌───────┴────────┐
                    │ WithCancel     │  ← server base context
                    │ (Server        │     (canceled on server shutdown)
                    │  BaseContext)  │
                    └───────┬────────┘
                            |
                    ┌───────┴────────┐
                    │ WithTimeout    │  ← request deadline (30s)
                    │ (ReadTimeout)  │
                    └───────┬────────┘
                            |
                    ┌───────┴────────┐
                    │ WithValue      │  ← request ID (middleware.RequestID)
                    │ (request_id)   │
                    └───────┬────────┘
                            |
                    ┌───────┴────────┐
                    │ WithValue      │  ← trace context (OpenTelemetry)
                    │ (trace_id,     │
                    │  span_id)      │
                    └───────┬────────┘
                            |
                    ┌───────┴────────┐
                    │ WithValue      │  ← user info (auth middleware)
                    │ (user_id,      │
                    │  permissions)  │
                    └───────┬────────┘
                            |
                    ┌───────┴────────┐
                    │ WithTimeout    │  ← DB query timeout (5s)
                    │ (dbCtx, 5s)    │     derived from request ctx
                    └───────┬────────┘
                            |
                      db.QueryContext(dbCtx, ...)
```

Each layer adds information. No layer removes or modifies parent values. When the request deadline expires (30s), the innermost `dbCtx` is canceled first (its `Done()` channel closes), then the parent contexts are canceled. The database driver sees `ctx.Err() == context.DeadlineExceeded` and aborts the query.

## Internal Architecture

### The Context Interface

The `context.Context` interface is defined in `context/context.go` (Go 1.22, ~line 55):

```go
type Context interface {
    Deadline() (deadline time.Time, ok bool)
    Done() <-chan struct{}
    Err() error
    Value(key any) any
}
```

Four methods. That's the entire interface. `Deadline()` returns the time when work should be canceled (if any). `Done()` returns a channel that is closed when the context is canceled or times out. `Err()` returns the reason for cancellation (`Canceled` or `DeadlineExceeded`) or `nil`. `Value()` retrieves a value for a key from the context chain.

The simplicity of this interface is deceptive. Every `Context` implementation in the standard library is a struct that satisfies these four methods, and the real complexity is in how they compose to form trees and how cancellation propagates.

### emptyCtx: The Root

`context.Background()` and `context.TODO()` both return an `emptyCtx`, defined at ~line 180 of `context/context.go`:

```go
type emptyCtx int

func (*emptyCtx) Deadline() (deadline time.Time, ok bool) { return }
func (*emptyCtx) Done() <-chan struct{}                   { return nil }
func (*emptyCtx) Err() error                              { return nil }
func (*emptyCtx) Value(key any) any                       { return nil }
```

`emptyCtx` is an `int` (not a struct), which means it's a zero-allocation value type. Its `Done()` channel is `nil`—a `nil` channel blocks forever in `select`, which is the correct behavior: `Background()` never cancels. Its `Value()` always returns `nil`—the root has no values. `emptyCtx` is the terminator of all context trees.

### cancelCtx: Cancellation with Propagation

`WithCancel(parent)` creates a `cancelCtx`, defined at ~line 220:

```go
type cancelCtx struct {
    Context
    mu       sync.Mutex
    done     atomic.Value  // of chan struct{}
    children map[canceler]struct{}
    err      error
    cause    error
}
```

Key design decisions in this struct:

1. **Embedded `Context`**: `cancelCtx` embeds the parent `Context` directly. This means `cancelCtx` inherits all parent methods (`Deadline()`, `Value()`) without explicit delegation. Calls to `ctx.Deadline()` on a `cancelCtx` fall through to the embedded parent's `Deadline()` method. This is the Go composition pattern applied to contexts.

2. **`done` as `atomic.Value`**: The `Done()` channel is stored in an `atomic.Value` so that it can be lazily created and atomically read by multiple goroutines. On the first call to `Done()`, the channel is created and stored atomically. Subsequent calls see the same channel via atomic load. This avoids allocating a channel for contexts that are never checked for cancellation.

3. **`children` map**: A `cancelCtx` maintains a set of child `canceler` instances. When `cancel()` is called, it iterates over `children`, calls `child.cancel(false, err, cause)` on each, and then clears the map. The `removeFromParent` parameter controls whether the child removes itself from the parent's `children` map—this prevents memory leaks when child contexts are garbage collected. Typically, `false` is passed during cascading cancellation because the parent is already iterating over the map and removing entries.

4. **`mu sync.Mutex`**: All mutations to the `children` map are protected by a mutex. `cancel()` acquires the lock, iterates over children, cancels each, and clears the map. Adding a child (during `WithCancel`) acquires the lock to insert into `children`. The `Done()` channel creation is lock-free (using `atomic.Value`), but registering as a child requires the lock.

The `cancel()` function (~line 280) implements the actual cancellation logic:

```go
func (c *cancelCtx) cancel(removeFromParent bool, err, cause error) {
    if err == nil {
        panic("context: internal error: missing cancel error")
    }
    if cause == nil {
        cause = err
    }
    c.mu.Lock()
    if c.err != nil {
        c.mu.Unlock()
        return // already canceled
    }
    c.err = err
    c.cause = cause
    d, _ := c.done.Load().(chan struct{})
    if d == nil {
        c.done.Store(closedchan)
    } else {
        close(d)
    }
    for child := range c.children {
        child.cancel(false, err, cause)
    }
    c.children = nil
    c.mu.Unlock()

    if removeFromParent {
        removeChild(c.Context, c)
    }
}
```

The logic: check if already canceled (idempotent), set `err` and `cause`, close the `Done()` channel (or store a pre-closed `closedchan` if the channel was never created), cancel all children recursively, and optionally remove from parent. The use of `closedchan` (a package-level pre-closed channel) is an optimization: if `Done()` was never called, no goroutine is listening, so closing a real channel is wasted work.

### valueCtx: The Linked List

`WithValue(parent, key, val)` creates a `valueCtx`, defined at ~line 90:

```go
type valueCtx struct {
    Context
    key, val any
}
```

This is remarkably simple: a parent pointer and a single key-value pair. There is no map—each `valueCtx` stores exactly one key-value pair. This design choice has profound implications:

1. **Linked list traversal**: `Value(key)` must traverse the chain upward until it finds a matching key or reaches the root. The search is:

```go
func (c *valueCtx) Value(key any) any {
    if c.key == key {
        return c.val
    }
    return value(c.Context, key) // recursive: check parent
}
```

If the key matches, return the value. Otherwise, delegate to the parent's `Value()` method (which may be another `valueCtx`, a `cancelCtx`, a `timerCtx`, or an `emptyCtx`).

2. **Key comparison uses `==`**: The comparison `c.key == key` uses Go's `==` operator. This means `interface{}` keys are compared by their dynamic type and value. Two `string` keys `"user_id"` are equal. Two `int` keys `42` are equal. But `"user_id"` (string) and `MyKey("user_id")` (custom type) are NOT equal, even if they have the same underlying value. This is why typed keys are critical. If you use `string` keys and two packages both use `"user_id"`, they collide. Typed keys (`type ctxKeyUserID struct{}`) prevent collisions because the type is part of the comparison.

3. **Shadowing**: If a parent has `key=X, val="parent_value"` and a child stores `key=X, val="child_value"`, the child's value shadows the parent's. `Value(X)` on the child returns `"child_value"`. This is intentional: context values can be overridden for a subtree, which is useful for per-request configuration (e.g., a rate limit that applies to this request only, overriding a default).

4. **No iteration**: There is no way to iterate over all key-value pairs in a context. Context values are a black box. You can only ask for specific keys. This is by design—context values are for request-scoped data, not a general-purpose dictionary. If you need to enumerate all values, you should be using a different data structure.

### timerCtx: WithTimeout/WithDeadline

`WithTimeout(parent, duration)` and `WithDeadline(parent, time)` both create a `timerCtx`, defined at ~line 340:

```go
type timerCtx struct {
    cancelCtx
    timer    *time.Timer
    deadline time.Time
}
```

Key design:

1. **Embeds `cancelCtx`**: A `timerCtx` IS a `cancelCtx` with an additional timer and deadline. This means it inherits all the cancellation propagation logic. Calling `ctx.Done()` on a `timerCtx` first checks the embedded `cancelCtx.Done()`, which returns the channel that is closed when the timer fires (or the parent is canceled).

2. **`time.Timer` allocation**: `time.NewTimer(duration)` allocates a timer on the heap. In Go, timers are backed by the runtime's timer heap (a priority queue of timers managed by a dedicated goroutine in older Go versions, or by each P's local timer heap in Go 1.14+). The timer heap insertion is O(log n), where n is the number of active timers. At high concurrency (100,000 concurrent requests, each with a timeout), timer allocation and GC become significant. Go 1.14+ reduced this overhead with per-P timer heaps, but creating millions of timers is still expensive.

3. **Automatic cancellation**: When the timer fires, it calls `cancelCtx.cancel(true, DeadlineExceeded, DeadlineExceeded)`. The `true` argument means `removeFromParent = true`—the `timerCtx` removes itself from its parent's `children` map after canceling. This is important because a `timerCtx` is created for a single operation; once it fires, it should not be referenced by the parent anymore.

4. **Parent deadline truncation**: If the parent already has a deadline earlier than the requested deadline, `WithDeadline` returns a `cancelCtx` (not a `timerCtx`) tied to the parent's deadline. The logic at ~line 400:

```go
func WithDeadline(parent Context, d time.Time) (Context, CancelFunc) {
    if cur, ok := parent.Deadline(); ok && cur.Before(d) {
        return WithCancel(parent)
    }
    // ... create timerCtx
}
```

This optimization avoids allocating a timer that would never fire (the parent deadline is sooner). It also means the child inherits the parent's cancellation semantics: if the parent is canceled before the child's deadline, the child is also canceled.

5. **CancelFunc stops the timer**: The `CancelFunc` returned by `WithTimeout` and `WithDeadline` calls `timer.Stop()` before canceling. This is critical: a timer that fires without a listener has its channel backed up. If the timer fires and nobody is reading from the channel, the timer's goroutine blocks on writing to the channel (in Go < 1.23). Go 1.23 changed `time.Timer` to use a channel with a buffer of 1 and non-blocking sends in `SendTime()`, eliminating this leak risk. But in earlier Go versions, an unstopped timer leaks a goroutine.

### Propagation Mechanics: The parentCancelCtx Function

A critical internal function at ~line 430 of `context/context.go` is `parentCancelCtx`. This function walks up the context chain to find the nearest `*cancelCtx` (or `*timerCtx`, which embeds `cancelCtx`). It is used when creating a new child to register it with the correct parent for cancellation propagation:

```go
func parentCancelCtx(parent Context) (*cancelCtx, bool) {
    done := parent.Done()
    if done == closedchan || done == nil {
        return nil, false
    }
    p, ok := parent.Value(&cancelCtxKey).(*cancelCtx)
    if !ok {
        return nil, false
    }
    pdone, _ := p.done.Load().(chan struct{})
    if pdone != done {
        return nil, false
    }
    return p, true
}
```

This function uses the `cancelCtxKey` (a package-level `int`) stored in the context value chain to find the precise `*cancelCtx` to register as a child. It also verifies that the `Done()` channel matches (`pdone == done`) to guard against a custom context implementation that stores a `cancelCtxKey` but does not actually use it for cancellation.

This is why custom context implementations that wrap standard ones can break cancellation propagation: if your custom `Context` does not store `cancelCtxKey` in its value chain, `parentCancelCtx` returns `(nil, false)`, and the new child cannot register with the parent for cancellation propagation. The child will not be canceled when the parent is.

### Context Key Design: Typed Keys with struct{}

The standard library documentation states: "Use a custom, unexported type as the key. Do not use built-in types like `string`." The Go idiom:

```go
// Good: typed key, zero-size (struct{} uses 0 bytes)
type ctxKeyUserID struct{}

func WithUserID(ctx context.Context, id string) context.Context {
    return context.WithValue(ctx, ctxKeyUserID{}, id)
}

func UserIDFromContext(ctx context.Context) (string, bool) {
    id, ok := ctx.Value(ctxKeyUserID{}).(string)
    return id, ok
}

// Bad: string key — can collide with other packages
func WithUserID(ctx context.Context, id string) context.Context {
    return context.WithValue(ctx, "user_id", id) // EVIL: string collision
}
```

The `struct{}` type is zero-size, meaning the key itself costs nothing to store (Go's empty struct uses 0 bytes). The key exists only for type comparison. Two different packages declaring `type ctxKeyUserID struct{}` create two DIFFERENT types, even though they have the same definition. This is Go's type identity rule: types are identical only if they come from the same type declaration. `pkg1.ctxKeyUserID` and `pkg2.ctxKeyUserID` are different types, so `ctx.Value(pkg1.ctxKeyUserID{})` never matches a value stored with `pkg2.ctxKeyUserID{}`.

### Chi's Integration with context.Context

Chi middleware uses context extensively. The `middleware.RequestID` middleware stores the request ID in the context:

```go
// github.com/go-chi/chi/v5/middleware/request_id.go (~line 90)
func RequestID(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        ctx := r.Context()
        id := r.Header.Get("X-Request-Id")
        if id == "" {
            id = uuid.New().String()
        }
        ctx = context.WithValue(ctx, RequestIDKey, id)
        next.ServeHTTP(w, r.WithContext(ctx))
    })
}
```

Note the pattern: extract the existing context, create a new context with the value, and call `r.WithContext(ctx)` to create a new request with the updated context. The original request is immutable; `WithContext` returns a shallow copy with a different context.

Chi's `URLParam` function also uses context internally. When Chi matches a route like `/orders/{orderID}`, it stores the route parameters in the request context. `chi.URLParam(r, "orderID")` retrieves them. The implementation uses Chi's own `RouteCtx` key, not the standard `context.WithValue` directly, because Chi needs to store a map of parameters (not a single key-value pair). Chi stores a `*Context` struct (Chi's own context, not `context.Context`) in the context value chain, and `URLParam` extracts it and looks up the parameter.

### Distributed Tracing Integration

OpenTelemetry in Go integrates with `context.Context` for trace propagation. A typical Chi middleware:

```go
import (
    "go.opentelemetry.io/otel"
    "go.opentelemetry.io/otel/propagation"
    "go.opentelemetry.io/otel/trace"
)

func TracingMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        propagator := propagation.TraceContext{}
        ctx := propagator.Extract(r.Context(), propagation.HeaderCarrier(r.Header))
        tracer := otel.Tracer("my-service")
        ctx, span := tracer.Start(ctx, r.Method+" "+r.URL.Path)
        defer span.End()
        span.SetAttributes(
            attribute.String("http.method", r.Method),
            attribute.String("http.url", r.URL.String()),
        )
        next.ServeHTTP(w, r.WithContext(ctx))
    })
}
```

The `propagator.Extract` reads W3C `traceparent` and `tracestate` headers from the incoming request and stores the trace context in the `context.Context`. The `tracer.Start` creates a child span and stores it in the context. Downstream code can create child spans from this context:

```go
func (h *GetOrderHandler) Handle(ctx context.Context, q GetOrderQuery) (*GetOrderResult, error) {
    tracer := otel.Tracer("orders")
    ctx, span := tracer.Start(ctx, "GetOrderHandler.Handle")
    defer span.End()
    result, err := h.orderRepo.FindByID(ctx, q.OrderID)
    return result, err
}
```

The context carries the trace context across all internal boundaries. When making an outbound HTTP or gRPC call, the trace context is injected into the outgoing headers:

```go
func (r *OrderRepository) callPaymentService(ctx context.Context) error {
    propagator := propagation.TraceContext{}
    req, _ := http.NewRequestWithContext(ctx, "GET", "http://payments:8080/health", nil)
    propagator.Inject(ctx, propagation.HeaderCarrier(req.Header))
    resp, err := http.DefaultClient.Do(req)
    return err
}
```

The `propagator.Inject` reads the trace context from the Go context and writes the `traceparent` and `tracestate` headers into the outgoing HTTP request. The downstream service's middleware reads these headers and continues the trace.

## Runtime Behavior

### Context Value Lookup at Runtime

When `ctx.Value(key)` is called, here is what happens step by step:

1. If `ctx` is a `*valueCtx`, check `c.key == key`. If match, return `c.val`. If not, call `c.Context.Value(key)`—delegate to parent.
2. If `ctx` is a `*cancelCtx`, check `c.key == key` for the `cancelCtxKey` (the internal key). If match, return `c` itself (this is how `parentCancelCtx` finds the `*cancelCtx`). If not, call `c.Context.Value(key)`—delegate to parent.
3. If `ctx` is a `*timerCtx`, same as `cancelCtx` (timerCtx embeds cancelCtx).
4. If `ctx` is an `*emptyCtx`, return `nil`.
5. For custom `Context` implementations, `Value(key)` may do anything. If the custom implementation eventually calls `value(c.Context, key)`, the standard traversal continues.

The traversal is recursive but not tail-recursive—each call is a new stack frame. At depth 50 (50 nested contexts), this is 50 function calls, which is fast (nanoseconds) but measurable in tight loops. This is why you should not call `ctx.Value()` in a loop—extract the value once at the top of the function.

### Context Cancellation at Runtime

When `cancel()` is called (either manually via the `CancelFunc` or automatically via a timer expiry):

1. **Acquire mutex**: `c.mu.Lock()`. This serializes all cancellation operations on this node.
2. **Check if already canceled**: If `c.err != nil`, the context is already canceled. Return immediately (idempotent).
3. **Set error and cause**: `c.err = err; c.cause = cause`. The `cause` is available via `context.Cause(ctx)` (Go 1.20+).
4. **Close Done channel**: If `Done()` was never called, store a pre-closed `closedchan`. Otherwise, `close(d)`. Any goroutine blocked on `<-ctx.Done()` unblocks immediately. Multiple goroutines can select on the same channel safely; `close` wakes all of them.
5. **Cancel all children**: Iterate over `c.children` map, call `child.cancel(false, err, cause)` on each. The `false` parameter means children do not remove themselves from the parent (the parent is already clearing the map). Each child's `cancel()` recursively cancels their children. This is a depth-first traversal of the context tree.
6. **Clear children map**: `c.children = nil`. This allows the garbage collector to reclaim child contexts that are no longer referenced elsewhere.
7. **Release mutex**: `c.mu.Unlock()`.
8. **Remove from parent**: If `removeFromParent` is true, call `removeChild(c.Context, c)`, which acquires the parent's mutex and deletes this child from the parent's `children` map. This is what `WithTimeout` does when the timer fires.

### Timer Lifecycle at Runtime

When `WithTimeout(parent, 5*time.Second)` is called:

1. **Check parent deadline**: If parent has an earlier deadline, create a `cancelCtx` instead (no timer needed).
2. **Create timerCtx**: Allocate a `timerCtx` struct (heap allocation for the struct + embedded `cancelCtx`).
3. **Create timer**: `time.NewTimer(5*time.Second)`. This allocates a `time.Timer` struct and inserts it into the runtime's timer heap. In Go 1.14+, each P (logical processor) has its own timer heap, so insertion is O(log n_p) where n_p is timers on that P.
4. **Start goroutine**: A goroutine is created (or an existing timer goroutine is reused) that waits for the timer to fire, then calls `c.cancel(true, DeadlineExceeded, DeadlineExceeded)`.
5. **Register with parent**: `parentCancelCtx` finds the nearest `*cancelCtx` ancestor and adds this `timerCtx` to its `children` map.
6. **Return context + CancelFunc**: The `CancelFunc` is a closure that calls `timer.Stop()` (to prevent the timer from firing if it hasn't already) and then `cancelCtx.cancel(true, Canceled, Canceled)`.

When `CancelFunc` is called before the timer fires (e.g., via `defer cancel()`):

1. **Stop timer**: `timer.Stop()` removes the timer from the timer heap. Returns `true` if the timer was still pending, `false` if it already fired. If `false`, drain the timer channel to prevent goroutine leak (Go < 1.23). In Go 1.23+, the non-blocking send eliminates the need for draining.
2. **Cancel context**: `c.cancel(true, Canceled, Canceled)`. Cancels this context and all children.
3. **Return**: The `CancelFunc` returns. The context is now canceled.

### What Happens When a Request Times Out in Chi

1. **`net/http` Server sets deadline**: The server's `ReadTimeout` or the handler's `http.TimeoutHandler` wraps the context with a deadline. Chi receives `r.Context()` with this deadline.
2. **Handler runs**: The handler calls `usecase.Execute(ctx, input)`, which calls `db.QueryContext(ctx, query)`.
3. **Deadline expires**: The `timerCtx`'s timer fires. The `Done()` channel is closed.
4. **`database/sql` detects cancellation**: `database/sql`'s connection pool logic checks `ctx.Err()` before acquiring a connection. If the context is already canceled, it returns `ctx.Err()` immediately without acquiring a connection.
5. **OR, if query is in-flight**: If the query is already executing, `database/sql` uses `pg_cancel_backend` (PostgreSQL) or `KILL QUERY` (MySQL) to cancel the in-flight query. The driver's `conn.ExecContext` or `conn.QueryContext` call returns `context.DeadlineExceeded`.
6. **Error propagates up**: Use case returns the error. Handler writes HTTP 503 or 504. Chi's Logger middleware logs the status code and duration.
7. **Goroutine cleanup**: The goroutine that was waiting on the query returns. The goroutine that was waiting on the timer's channel stops waiting. Both goroutines become available for GC.

## Flow Diagrams

```
Context Cancellation Propagation:

    Background (never canceled)
        |
    Server BaseCtx (canceled on shutdown)
        |
    Request Ctx (timeout 30s)        ← cancel() called here
        |                                   |
    ┌───┴────┬──────────┬──────────┐       |
    |        |          |          |        ▼
  dbCtx   gRPCCtx   kafkaCtx   logCtx   1. close(Done) channel
  (5s)     (10s)     (30s)     (∞)
    |        |          |          |      2. c.children:
    ✓        ✓          ✓          ✗        [dbCtx, gRPCCtx, kafkaCtx]
                                           |
  Each child:                              ▼
  1. close own Done() channel            3. dbCtx.cancel(false, ...)
  2. dbCtx.children = nil                4. gRPCCtx.cancel(false, ...)
  3. removeFromParent = false            5. kafkaCtx.cancel(false, ...)
     (parent clearing map)               6. c.children = nil
                                         7. removeFromParent = true
                                            → removeChild(parent, c)


  Any goroutine doing:                   Now returns immediately:
  select {                               err = context.DeadlineExceeded
  case <-ctx.Done():
      return ctx.Err()                   // "context deadline exceeded"
  case result := <-work:
      return result, nil
  }
```

```
Distributed Tracing Flow Across Services:

  Client                    Service A (Chi)              Service B (gRPC)           Kafka
    |                            |                             |                      |
    | GET /api/orders            |                             |                      |
    | traceparent: 00-aaa...     |                             |                      |
    | tracestate: vendor=val     |                             |                      |
    |──────────────────────────>|                             |                      |
    |                            |                             |                      |
    |                    propagator.Extract(ctx, headers)      |                      |
    |                    ctx = ctx + {trace_id: aaa,           |                      |
    |                                 span_id: bbb}           |                      |
    |                            |                             |                      |
    |                    tracer.Start(ctx, "GET /orders")      |                      |
    |                    ctx = ctx + {span_id: ccc,            |                      |
    |                                 parent: bbb}            |                      |
    |                            |                             |                      |
    |                    handler processes...                  |                      |
    |                            |                             |                      |
    |                    call gRPC: GetUser(ctx, userID)       |                      |
    |                            |                             |                      |
    |                    propagator.Inject(ctx, metadata)      |                      |
    |                    metadata: {                           |                      |
    |                      traceparent: 00-aaa-ccc-01         |                      |
    |                    }                                     |                      |
    |                            |────────────────────────────>|                      |
    |                            |                             |                      |
    |                            |               propagator.Extract(ctx, metadata)  |
    |                            |               ctx = ctx + {trace_id: aaa,        |
    |                            |                            span_id: ccc}         |
    |                            |                             |                      |
    |                            |               tracer.Start(ctx, "GetUser")       |
    |                            |               ctx = ctx + {span_id: ddd}         |
    |                            |                             |                      |
    |                            |               emit event: UserRetrieved          |
    |                            |                             |─────────────────────>|
    |                            |                             |                      |
    |                            |                             |    event headers:    |
    |                            |                             |    traceparent:      |
    |                            |                             |    00-aaa-ddd-01     |
    |                            |                             |                      |
    |  HTTP 200                  |<────────────────────────────|                      |
    |<───────────────────────────|                             |                      |
```

## Source Code Reading Guide

Read these files in this order:

1. **`context/context.go`** (Go standard library, ~500 lines) — Read the entire file. Every line. This is the most important file for understanding Go contexts. Start with the `Context` interface (~line 55), then `emptyCtx` (~line 180), `valueCtx` (~line 70), `cancelCtx` (~line 220), `timerCtx` (~line 340). Pay attention to `parentCancelCtx` (~line 430) and `propagateCancel` (~line 260). Notice that `WithDeadline` checks the parent deadline before creating a timer (~line 400).

2. **`net/http/server.go`** — Read `Server.Serve` (~line 3000) to see how the server creates the base context and injects it into each `*http.Request`. Read `conn.serve` (~line 1800) to see how HTTP/1.1 connections are served. Notice that `r.Context()` starts from the server's base context, and Chi (or any middleware) adds layers on top.

3. **`github.com/go-chi/chi/v5/middleware/request_id.go`** — Read the `RequestID` middleware (~80 lines). See how it uses `context.WithValue` to store the request ID. This is the standard pattern for all Chi middleware: extract context, add value, call `r.WithContext(ctx)`.

4. **`github.com/go-chi/chi/v5/context.go`** (Chi's internal context) — Read how Chi stores route parameters in the context. Chi uses its own `*Context` struct (not `context.Context`) stored as a value in the standard context chain. `chi.URLParam(r, key)` extracts this struct and looks up parameters.

5. **`database/sql/sql.go`** — Read `DB.QueryContext` and `DB.ExecContext` (~line 1500-1700) to see how `database/sql` checks context cancellation before and during query execution. The functions `ctxDriverPrepare`, `ctxDriverExec`, `ctxDriverQuery` show the pattern of checking `ctx.Err()` at each step.

6. **OpenTelemetry Go SDK**: `go.opentelemetry.io/otel/trace/trace.go` — Read the `Tracer` interface and `Span` interface. Understand that `tracer.Start(ctx, name)` creates a child span and returns a new context containing the span. The span is stored in the context via `context.WithValue` with an internal key.

What to skip:
- Chi's radix tree routing implementation. Not relevant to context propagation.
- `time.Timer` internals (`runtime/time.go`). Interesting but a separate deep-dive.
- OpenTelemetry exporter implementations (Jaeger, OTLP, Zipkin). Focus on the API/SDK layer.
- gRPC context propagation internals. Same pattern as HTTP, different metadata carrier.

## Production Failure Scenarios

### Scenario 1: Background Context for Database Queries

**Cause**: A developer uses `context.Background()` instead of `r.Context()` in a database query:

```go
func (h *OrderHandler) GetOrder(w http.ResponseWriter, r *http.Request) {
    // BAD: Background context has no deadline, never cancels
    order, err := h.db.QueryContext(context.Background(),
        "SELECT * FROM orders WHERE id = $1", chi.URLParam(r, "orderID"))
    // ...
}
```

**Symptom**: When the client disconnects (browser tab closed, mobile app backgrounded, timeout), the HTTP handler returns and the goroutine exits, but the database query continues running. If this happens 10,000 times during a traffic spike, the database accumulates 10,000 orphaned queries. These queries hold connections from the pool (which has a max of, say, 20 connections), blocking other requests. P99 latency spikes from 10ms to 30s. The application appears "hung."

**Fix**: Always use `r.Context()` (or a derived context) for database queries. The `context.Context` carries the request's deadline and is canceled when the client disconnects. `database/sql` uses `pg_cancel_backend` to terminate the orphaned query, freeing the connection.

**Detection**: Monitor `pg_stat_activity` for long-running queries. A query that has been running for 5 minutes with no matching HTTP request is an orphan. Alert when count of queries with `state = 'active'` AND `query_start < NOW() - INTERVAL '30 seconds'` exceeds a threshold. Use `pg_terminate_backend(pid)` to kill known orphans.

### Scenario 2: Context Stored in Struct (Context Leak)

**Cause**: A developer stores a `context.Context` in a struct, hoping to reuse it across multiple requests:

```go
type OrderService struct {
    ctx context.Context // BAD: context is request-scoped, not service-scoped
    db  *sql.DB
}

func (s *OrderService) CreateOrder(input Input) error {
    // s.ctx is from the first request that created OrderService
    // Its deadline may have expired, its Done() channel closed
    return s.db.QueryContext(s.ctx, "INSERT INTO orders ...")
}
```

**Symptom**: The first request to use `OrderService` works fine. The second request (5 minutes later) calls `s.db.QueryContext(s.ctx, ...)` but `s.ctx` is already canceled (its deadline expired). The query returns `context.DeadlineExceeded` immediately. All subsequent requests fail with the same error, even though the client is sending fresh requests.

**Fix**: Never store `context.Context` in a struct. Contexts are request-scoped. Pass them as the first parameter to every function that needs them. If you need a long-lived context for background work (e.g., a goroutine that polls Kafka), create a new context with `context.WithCancel` and store the `CancelFunc` for cleanup, but never reuse a request's context.

### Scenario 3: Custom Context Implementation Breaks Cancellation

**Cause**: A developer creates a custom `Context` wrapper to add logging:

```go
type LoggingContext struct {
    context.Context
    logger *slog.Logger
}

func (c *LoggingContext) Value(key any) any {
    if key == loggerKey {
        return c.logger
    }
    return c.Context.Value(key)
}
```

This wrapper works for `Value()` but does NOT guarantee correct `cancelCtxKey` propagation. When `WithCancel(loggingCtx)` is called, `parentCancelCtx` looks for `cancelCtxKey` in the context chain. If `LoggingContext.Value(cancelCtxKey)` delegates to `c.Context.Value(cancelCtxKey)` and that returns the parent's `cancelCtx`, everything works. But if the order of delegation is wrong, the new child context may not register with the parent's `cancelCtx.children` map. The result: calling `cancel()` on the parent does not cancel the child.

**Symptom**: The child goroutine never receives cancellation. After a server shutdown, goroutines that should have been canceled continue running—processing Kafka messages, writing to databases, holding file handles. The server hangs on shutdown because `http.Server.Shutdown` waits for active connections, which are waiting for goroutines, which will never finish.

**Fix**: Do not wrap `context.Context` unless you fully understand `parentCancelCtx` and the `cancelCtxKey`. If you must add logging, use a decorator pattern that does not break the context chain, or store the logger in the context as a value: `ctx = context.WithValue(r.Context(), loggerKey, logger)`. Or, if you must wrap, ensure you delegate all four methods (`Deadline`, `Done`, `Err`, `Value`) to the embedded context AND ensure that `Value(cancelCtxKey)` returns the same value as the embedded context.

## Debugging Techniques

### Technique 1: Trace Context Cancellation with Delve

```bash
dlv debug ./cmd/server

# Set breakpoint at cancelCtx.cancel
(dlv) b context.(*cancelCtx).cancel

# Run until breakpoint
(dlv) c

# When breakpoint hits, inspect the context state
(dlv) p c.err          # nil (about to set)
(dlv) p c.cause        # nil
(dlv) p len(c.children) # how many children will cascade?
(dlv) bt               # who called cancel? (timer expiry? explicit cancel?)
# goroutine 42: time.(*Timer).Stop {0x123} called from WithTimeout CancelFunc
# goroutine 42: net/http.(*conn).serve {0x456} called from conn close

# Step through cancellation
(dlv) n  # c.err = err
(dlv) n  # close(d)
(dlv) n  # for child := range c.children ...
# At each child: inspect what's being canceled
(dlv) p child
```

Key insight: the backtrace tells you WHY the context was canceled. If `time.(*Timer).Stop` is in the trace, a timeout expired. If `net/http.(*conn).serve` is in the trace, the HTTP client disconnected. If a custom `CancelFunc` is in the trace, your code explicitly canceled it.

### Technique 2: Detect Missing Context Values

```go
// Add at handler entry to verify required context values exist
func handlerEntryCheck(ctx context.Context) {
    required := []struct {
        key  any
        name string
    }{
        {middleware.RequestIDKey, "request_id"},
        {tracing.TraceIDKey, "trace_id"},
        {auth.UserIDKey, "user_id"},
    }
    for _, r := range required {
        if ctx.Value(r.key) == nil {
            slog.Warn("missing context value", "key", r.name)
        }
    }
}
```

Place this at HTTP handler entry, gRPC interceptor, and Kafka consumer handler. If any required context value is missing, log a warning with the full context. In production, this catches middleware ordering bugs (e.g., the tracing middleware runs after the auth middleware, so auth middleware doesn't have a trace context available).

### Technique 3: Profile Context Allocations

Context chaining creates heap allocations. Profile how many contexts your application creates per request:

```go
// go test -bench=ContextChain -benchmem
func BenchmarkContextChain(b *testing.B) {
    b.ReportAllocs()
    for i := 0; i < b.N; i++ {
        ctx := context.Background()
        ctx = context.WithValue(ctx, key1, "val1")
        ctx = context.WithValue(ctx, key2, "val2")
        ctx = context.WithValue(ctx, key3, "val3")
        ctx, cancel := context.WithTimeout(ctx, time.Second)
        _ = ctx.Value(key1)
        cancel()
    }
}
```

Typical results: `WithValue` allocates ~48 bytes per call (the `valueCtx` struct + interface boxing). `WithTimeout` allocates ~200 bytes (`timerCtx` struct + `cancelCtx` struct + `time.Timer` + goroutine). At 10,000 RPS with 5 middleware layers and 3 query timeouts per request, that's ~80 MB/s of allocations from contexts alone. This is usually acceptable but worth knowing for high-throughput services.

## Observability Considerations

### What to Log

1. **Context cancellation reason**: When a handler detects `ctx.Err() != nil`, log the error at INFO level: `"request canceled" err="context deadline exceeded"`. This distinguishes normal timeouts from unexpected cancellations.
2. **Context value extraction failures**: If a required value is missing from the context (request ID, user ID, trace ID), log at WARN with the full context. This indicates a middleware ordering bug.
3. **Deadline proximity at handler entry**: Log the remaining deadline at handler entry: `slog.Debug("handler started", "deadline_ms", deadline.Sub(time.Now()).Milliseconds())`. If `deadline_ms < 0`, the context is already expired—the request will fail immediately.
4. **Span lifetime**: Each OpenTelemetry span logs its start and end. The span duration is the handler latency from the tracing perspective.

### What Metrics

1. **Cancelled requests**: Counter `http_requests_cancelled_total`. Differentiate between `context.Canceled` (client disconnect) and `context.DeadlineExceeded` (timeout). Alert if cancellation rate spikes (indicates clients timing out en masse, possibly a slow dependency).
2. **Context value lookup latency**: Histogram `context_value_lookup_ns`. In practice, this is sub-microsecond and not worth measuring. But if you have a custom fat context (e.g., a context that does database lookups on `Value()`), measure it.
3. **Goroutine leak**: Gauge `go_goroutines`. If the goroutine count grows monotonically, a goroutine is waiting on a context's `Done()` channel that never closes. Investigate with `pprof.Lookup("goroutine").WriteTo`.
4. **Active spans**: Gauge `tracing_active_spans`. Alert if this grows without bound (spans not being ended, probably a missing `defer span.End()`).

### What Traces

1. **Context creation**: Each `WithCancel`/`WithTimeout`/`WithDeadline` can be traced as a span event: "deadline set to 30s". This helps debug why a specific operation timed out.
2. **Context cancellation**: Record a span event when the context is canceled: "context canceled: deadline exceeded". This marks the end of the span (if still active).
3. **Value injection**: Optional: record which middleware injected which values. Useful for debugging missing values: "injected trace_id=abc123 at span bbb".
4. **Cross-boundary trace context**: Record the `traceparent` header at inbound and outbound boundaries. If the `traceparent` is missing at outbound, the trace is broken.

## Performance Implications

### Concern 1: Timer Allocation Overhead in WithTimeout

Every `WithTimeout` allocates a `time.Timer`. At 10,000 concurrent requests, each with a 30s timeout and a 5s DB query timeout, that's 20,000 timers allocated and deallocated per second (assuming 1,000 RPS with 10s average response time). Timer creation involves heap allocation and insertion into the timer heap. Timer cancellation involves removal from the timer heap (O(log n)). In Go 1.14+, per-P timer heaps reduce contention, but at scale, timer management is still a meaningful cost.

Mitigation:
- Use `WithTimeout` only when necessary. Many contexts do not need a timeout (e.g., background workers, cron jobs). Pass `context.Background()` for unbounded operations.
- Share timeouts: If you need to make 3 database calls within a 5s window, create one `dbCtx` with `WithTimeout(ctx, 5*time.Second)` and reuse it for all 3 calls. Do not create 3 separate contexts.
- Consider using `context.WithDeadline` with a pre-computed deadline (avoids the `time.Now()` call in `WithTimeout` which, in older Go versions, takes a global timer lock).

### Concern 2: Context Value Chain Depth

Each middleware adds a `valueCtx` node to the context chain. A typical request has: base context → request ID → trace context → auth context → logger context → 5 layers. Each `Value()` call traverses up to 5 nodes (O(n)). At 10,000 RPS with 10 context value lookups per request, that's 500,000 linked list traversals per second. Each traversal involves pointer chasing and type comparisons. This is fast (< 100ns per lookup at depth 5) but cache-unfriendly because the nodes are scattered across the heap.

Mitigation:
- Do not use context as a general-purpose dictionary. If you need to pass many values, use a struct with all the values and store a single pointer to it in the context. This reduces the chain depth.
- Extract all needed context values at the top of the function and pass them as explicit parameters. Do not call `ctx.Value()` deep in a call stack.

### Concern 3: Cancellation Cascade on Connection Close

When a client disconnects, `r.Context().Done()` is closed. This triggers cancellation of all child contexts (database queries, gRPC calls, Kafka consumers). Each child's `cancel()` iterates over ITS children, and so on. The cascade is O(n) in the number of contexts. For a single request, this is < 20 nodes and trivial. But during a server restart, hundreds of in-flight connections are canceled simultaneously, each with a context tree of depth 10-20. The combined lock contention on the `cancelCtx.mu` mutexes can cause a CPU spike.

Mitigation:
- Minimize the number of context nodes per request. Each `WithCancel`, `WithTimeout`, and `WithValue` adds a node. If you have 3 middleware layers each creating 2 context nodes, that's 6 levels. Reduce to essential middleware only.
- Deregister child contexts when they are no longer needed. If a DB query finishes in 100ms but the request timeout is 30s, the `dbCtx` remains registered in the parent's `children` map until the parent is canceled (30s later). This is wasted memory. The child IS garbage-collected (Go's GC can handle cycles), but the `children` map entry remains until the parent cancels.

## Architecture Implications

Understanding context deeply changes how you design APIs. Every function that performs I/O should accept a `context.Context` as its first parameter. This is Go's convention (enshrined in `go vet`'s `context` check) for good reason: the caller controls cancellation and deadlines, not the callee. If your function calls `http.Get(url)` without a context, you have no way to cancel it. If it calls `db.Query("SELECT ...")` without a context, the query cannot be canceled from outside. The context is the mechanism by which the caller says "I no longer need this result" without having to understand the callee's internals.

The architecture implication is that context propagation must be designed, not retrofitted. When you define a repository interface, the context is the first parameter. When you define a use case, the context is the first parameter. When you call an external service, the context is the calling convention. If you design your system such that context flows from the HTTP handler through the use case through the repository to the database, every layer can respond to cancellation. If a layer breaks the chain (e.g., a repository method that creates a `context.Background()` internally), everything downstream of that layer is uncancelable.

For distributed tracing, context is the vehicle that carries trace context across service boundaries. The W3C `traceparent` header is extracted from HTTP headers and stored in the context. The context is injected into outbound HTTP and gRPC calls. This means every outbound call must accept a context. If you have a library that makes HTTP calls without accepting a context, you cannot propagate traces to it. This is why modern Go libraries (at the staff level and above) always accept context.

## Team Ownership Implications

Context keys must be owned by a single team or package. Since typed keys prevent collisions, each package that stores values in the context should define its own unexported key type and export getter/setter functions. Example:

```go
// package auth (owned by the Auth team)
package auth

type ctxKeyAuthUser struct{}

func WithUser(ctx context.Context, user *User) context.Context {
    return context.WithValue(ctx, ctxKeyAuthUser{}, user)
}

func UserFromContext(ctx context.Context) (*User, bool) {
    user, ok := ctx.Value(ctxKeyAuthUser{}).(*User)
    return user, ok
}
```

Other packages should NEVER call `context.WithValue` with a key type they did not define. If the Orders team needs to store an order ID in the context, they define their own `ctxKeyOrderID` type in their own package. This prevents key collisions and makes it obvious which package owns which context values. During code review, any `context.WithValue` with a key from another package is an automatic reject.

## Interview Questions

### Q1: "Explain why context.WithValue uses a linked list instead of a map. What are the tradeoffs?"

**Answer**: The context package uses a linked list (`valueCtx` nodes chaining to parents) for three reasons:

1. **Immutability**: A map would need to be copied on every `WithValue` call to preserve immutability (the parent's values must not be affected by the child). Copying a map is O(n) in the number of entries. A linked list just adds a node, which is O(1). The child's new node points to the parent; the parent's chain is unmodified.

2. **Shadowing semantics**: A linked list naturally implements override semantics: a child's value for key X shadows a parent's value for key X because the child is searched first. With a map, you'd need copy-on-write or explicit shadowing logic.

3. **Concurrent access**: A linked list is immutable after creation. Multiple goroutines can traverse the list concurrently without locks. A map would require a mutex (or `sync.Map`) for concurrent reads during write operations.

The tradeoff is lookup performance: a linked list is O(n) in the depth of the context chain, while a map is O(1). But in practice, context chains are short (< 20 nodes), and the constant factor of linked list traversal is low (pointer chasing + interface equality). A map would be slower for the common case of 1-5 values due to hash computation, bucket lookup, and map header overhead. Only at > 100 values would a map outperform the linked list, and if you have 100 values in a context, you have an architectural problem.

### Q2: "A context is canceled but a goroutine is NOT receiving the cancellation signal. What could be wrong? List possible causes."

**Answer**: Common causes in Go:

1. **Goroutine not checking `ctx.Done()`**: The goroutine calls `<-someOtherChan` instead of using `select` with both channels. It is blocked on `someOtherChan` and never sees `ctx.Done()` close. Fix: always `select { case <-ctx.Done(): ...; case <-workChan: ... }`.

2. **`context.Background()` used instead of cancellable context**: A child goroutine was passed `context.Background()` (which has a nil `Done()` channel that never closes) instead of the cancellable context. Fix: pass the cancellable context.

3. **Custom context breaks `parentCancelCtx`**: A custom `Context` implementation does not propagate the `cancelCtxKey`, so the child cannot register with the parent's `children` map. When the parent cancels, the child is not in the map and is never notified. Fix: ensure custom contexts delegate `Value()` to the embedded context.

4. **Child context's `Done()` is not the parent's channel**: A goroutine creates its own context with `context.WithCancel(context.Background())` instead of deriving from the cancellable parent. The goroutine listens on its own `Done()` channel (which is only closed when that goroutine's own cancel function is called, never by the parent). Fix: derive child contexts from the parent context.

5. **Timeout already expired before goroutine starts**: The context's deadline has already passed at the time the goroutine is launched. `ctx.Done()` returns a channel that is already closed. If the goroutine does a non-blocking check first (`select { case <-ctx.Done(): return; default: }`), it would catch this. But if it goes straight into a blocking operation, it may appear stuck. Actually, `select` on a closed channel returns immediately, so this should work. The bug is usually cause 1 or 2.

### Q3: "Explain how Chi stores URL parameters in the context. Why doesn't Chi use context.WithValue directly?"

**Answer**: Chi does not use `context.WithValue` directly for URL parameters because they are multi-valued. A single route like `/orders/{orderID}/items/{itemID}` has two parameters. `context.WithValue` stores one key-value pair per call. To store two parameters, Chi would need two `WithValue` calls, which means two context chain nodes—messy and not ideal for looking up "all parameters."

Instead, Chi stores a `*Context` struct as a single value in the context chain using `context.WithValue(ctx, RouteCtxKey, routeCtx)`. The `*Context` struct contains a map of route parameters plus other routing metadata:

```go
// Simplified Chi Context (from chi/context.go)
type Context struct {
    Routes     Routes
    parent     *Context
    URLParams  RouteParams
    RoutePath  string
    // ...
}

type RouteParams struct {
    Keys   []string
    Values []string
}
```

When `chi.URLParam(r, "orderID")` is called, it does:
1. Get the `*Context` from `r.Context().Value(RouteCtxKey)`.
2. Iterate over `RouteParams.Keys` to find `"orderID"`.
3. Return the corresponding `RouteParams.Values[i]`.

The reason for not using raw `context.WithValue` is practical: a single value lookup is faster than two separate `Value()` traversals, and the `*Context` struct carries additional routing information (matched route pattern, route data) that Chi needs for other purposes (like building URLs, route listing).

If Chi used `context.WithValue` for each parameter, it would also need to handle the common case of `{orderID}` colliding with another middleware's `"orderID"` key. Chi's `RouteCtxKey` (an unexported struct type) prevents this.

### Q4: "You need to pass a logger through your application. Should you store it in the context or pass it as an explicit parameter? Justify your answer."

**Answer**: There is no universally correct answer; both approaches have valid use cases. The Go community has debated this.

**Store in context** (approach used by `slog` in Go 1.21+):
```go
ctx = sloghttp.WithLogger(r.Context(), logger)
// Later, deep in the call stack:
slog.InfoContext(ctx, "processing order")
```
- **Pros**: Logger is always available without threading through every function signature. Adding logging to a function deep in the call stack doesn't require changing all intermediate function signatures.
- **Cons**: Less explicit—you cannot tell from a function signature whether it logs. If the logger is missing from the context, you get a default (which may log to stderr, not your production log aggregator). Discovery: you have to know the context key to get the logger.

**Explicit parameter**:
```go
func ProcessOrder(ctx context.Context, logger *slog.Logger, order Order) error {
    logger.Info("processing order")
}
```
- **Pros**: Explicit in the function signature. The compiler ensures the logger is passed. Easy to mock in tests.
- **Cons**: Every function in the call chain must accept a logger. This can clutter function signatures, especially for deep call stacks.

**My recommendation**: Use context for cross-cutting, infrastructure concerns that are request-scoped (logger, tracer, metrics). Use explicit parameters for business domain objects (order, user, payment). A logger is cross-cutting and request-scoped (each request may have different log attributes like request ID, user ID, trace ID), so it belongs in the context. But note: `slog` stores the logger as a context value, and `slog.InfoContext(ctx, msg)` extracts it internally. This is the standard library's endorsed approach as of Go 1.21.

The key constraint: if you store the logger in the context, ALL functions in the call chain must accept `context.Context` as their first parameter. If you have functions that don't (legacy code, libraries without context support), you must pass the logger explicitly. This is why the Go community settled on "context is always first parameter" as a rule.

### Q5: "Design a context-aware retry mechanism. The retry should stop if the context is canceled and should respect the context's deadline."

**Answer**:

```go
func RetryWithContext(ctx context.Context, maxAttempts int, fn func(context.Context) error) error {
    for attempt := 0; attempt < maxAttempts; attempt++ {
        if err := ctx.Err(); err != nil {
            return fmt.Errorf("retry aborted (attempt %d): %w", attempt, err)
        }

        err := fn(ctx)
        if err == nil {
            return nil
        }

        backoff := time.Duration(math.Pow(2, float64(attempt))) * 100 * time.Millisecond
        if deadline, ok := ctx.Deadline(); ok {
            remaining := time.Until(deadline)
            if remaining <= 0 {
                return fmt.Errorf("retry aborted: deadline exceeded")
            }
            if backoff > remaining {
                backoff = remaining
            }
        }

        select {
        case <-ctx.Done():
            return fmt.Errorf("retry aborted (attempt %d): %w", attempt, ctx.Err())
        case <-time.After(backoff):
        }
    }
    return fmt.Errorf("retry exhausted after %d attempts", maxAttempts)
}
```

Key design points:
1. Check `ctx.Err()` before each attempt and after each sleep.
2. Cap the backoff delay to remain within the context's deadline.
3. Use `select` with `ctx.Done()` for context-aware sleep. Do NOT use `time.Sleep(backoff)`—it ignores context cancellation.
4. Pass `ctx` to the operation function so it can respond to cancellation internally.
5. Wrap errors with `%w` to preserve the original error chain. The caller can use `errors.Is(err, context.DeadlineExceeded)` to distinguish timeout from other failures.

## Hands-On Exercises

### Exercise 1: Trace Context Value Propagation Through Your Chi Service

**Goal**: Add logging at every context boundary to visualize the context chain.

**Steps**:
1. Create custom middleware that logs the context chain depth: count the number of `valueCtx` layers by iteratively calling `ctx.Value()` until nil (or use reflection to detect `*valueCtx`).
2. Log the chain at request start, after each middleware, and at handler entry. Include the keys and types stored.
3. Visualize the chain: request_id (string), trace_id (struct{}), user_id (struct{}), logger (struct{}).
4. Identify if any middleware stores redundant values (same key re-stored, shadowing parent value).
5. Optimize: reduce chain depth by combining related values into a single struct stored under one key.

### Exercise 2: Implement a Context-Based Feature Flag Toggle

**Goal**: Create a context-based feature flag system that can enable/disable features per request.

**Steps**:
1. Define a `FeatureFlags` struct with boolean fields: `EnableNewPricing`, `UseCachedInventory`.
2. Create middleware that reads a `X-Feature-Flags` header, parses it, and stores a `*FeatureFlags` in the context.
3. In the use case, extract `FeatureFlags` from the context and branch: `if flags.EnableNewPricing { ... } else { ... }`.
4. Write tests: one request with `X-Feature-Flags: new_pricing=true`, another with `false`. Verify both paths execute correctly.
5. Add a timeout: if `X-Feature-Flags` is present, wrap the context with `WithTimeout(ctx, 1*time.Second)` to limit the impact of expensive new-feature code paths.

### Exercise 3: Build a Context Propagation Debugger

**Goal**: Create a tool that traces a context's entire ancestry for debugging.

**Steps**:
1. Write a function `DumpContext(ctx context.Context) string` that walks up the context chain and prints each node's type and key-value pairs.
2. Use reflection to detect `*valueCtx`, `*cancelCtx`, `*timerCtx` nodes. For `*valueCtx`, print the key type and value. For `*cancelCtx`, print the error and Done channel state. For `*timerCtx`, print the deadline.
3. Count the total depth. Warn if depth exceeds 10 (performance concern).
4. Integrate into a Chi middleware: on every request, if the `X-Debug-Context: true` header is present, add the context dump to the response headers (`X-Context-Dump`).
5. Use this to debug a real issue: add/remove middleware and observe how the context chain changes.

## Advanced Challenges

### Challenge 1: Implement a Context-Aware Worker Pool with Graceful Shutdown

**Goal**: Build a worker pool that processes jobs with context cancellation and graceful shutdown.

**Constraints**:
- Workers accept a context for each job.
- When the worker pool's shutdown is triggered, all in-flight jobs receive a cancellation signal.
- Workers should finish their current job or abort within a grace period (5 seconds).
- The pool should not accept new jobs after shutdown is triggered.

**Approach**:
- The pool has a `context.Context` (created via `context.WithCancel`) that is canceled on shutdown.
- Each job is submitted with its own `context.Context`. The worker merges the pool's context and the job's context: `mergedCtx, cancel := MergeContext(poolCtx, jobCtx)` (this is your `MergeContext` implementation—when either is canceled, the merged context is canceled).
- Workers `select` on `mergedCtx.Done()` and the job's result channel. If `mergedCtx` is canceled, the worker stops processing (but might call a cleanup function).
- On shutdown: cancel pool context → all workers' merged contexts are canceled → workers stop (or finish quickly). Workers that don't finish within the grace period are abandoned (their goroutine will finish eventually, but the pool's shutdown returns without them).

### Challenge 2: Design a Distributed Tracing System Using Only context.Context

**Goal**: Implement basic distributed tracing without OpenTelemetry, using only `context.Context`.

**Constraints**:
- Generate trace IDs and span IDs (UUID or random 16-byte values).
- Store trace context in `context.Context` using custom typed keys.
- Propagate trace context via HTTP headers (W3C `traceparent` format: `00-{trace_id}-{span_id}-01`).
- Instrument a Chi handler: extract traceparent from request headers, create a server span, inject traceparent into outbound HTTP calls.
- Record span lifecycle: start time, end time, parent ID, span name. Store spans in memory and expose them via a `/debug/traces` endpoint.

**Evaluation criteria**: Can you trace a request from Chi → gRPC → another service and see the full span tree? Can you handle the case where no traceparent is present (start a new trace)? Can you limit the number of stored spans?

## Key Insights

- `context.Context` is a tree of immutable nodes, not a mutable bag of values. Each `With*` call adds a node. The tree is traversed upward for value lookup and downward for cancellation propagation. Understanding this duality is the key to all context usage.
- Typed context keys (`struct{}{}`) are not a stylistic preference—they are a correctness requirement. String keys collide between packages. Typed keys prevent collisions through Go's type identity rules. If you are using string keys, you have a latent bug waiting for the day two packages pick the same string.
- Context is for request-scoped values, not for passing parameters. The Go proverb: "context.Context should be for request-scoped data that is transitively process-wide." Logger, trace context, auth token, request ID, deadline—yes. Domain entity, database handle, configuration—no.
- `WithTimeout` and `WithDeadline` allocate a `time.Timer` on the heap and register it in the runtime's timer heap. At thousands of concurrent requests, this is a real cost. Use deadlines judiciously and derive child contexts to share the same deadline rather than creating new timers.
- Parent context cancellation cascades to ALL children. If you create 100 goroutines with `ctx, cancel := context.WithCancel(parentCtx)` and the parent is canceled, all 100 goroutines receive the cancellation signal. This is powerful for cleanup but means one connection timeout can cascade-cancel all concurrent operations in that request.
- NEVER store a `context.Context` in a struct. Contexts are request-scoped. A struct is typically long-lived. A context stored in a struct will either have an expired deadline or become a memory leak (keeping the context chain referenced). Pass context as the first parameter to every function.
- Read `context/context.go`. It is 500 lines. Every Go developer at staff level or above should have read it at least once. The internals are elegant, well-commented, and directly applicable to designing your own cancellation and tree structures.