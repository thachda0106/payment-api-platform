# Session 13: Chi Router Internals — Radix Tree, Route Resolution, Route Matching

## Why This Topic Exists

Every HTTP framework must answer one fundamental question: given a request with method `GET` and path `/users/42/orders/active`, which handler should execute? A naive implementation iterates all registered routes linearly — `O(n)` where `n` is the number of routes. For a service with 200 endpoints, this might be acceptable. For a gateway with 20,000 routes, it is not.

Chi's answer is a compressed radix tree (compact prefix tree / Patricia trie). This data structure achieves `O(path_length)` lookup — constant with respect to the number of registered routes. Understanding the radix tree is understanding *why Chi is fast*, *how route conflicts arise*, and *why route ordering matters less in Chi than in other routers*.

As a Staff/Principal Engineer, you will be asked: "Why did we choose Chi over gorilla/mux or gin?" or "Can we register 10,000 dynamic routes without degrading latency?" or "Why does this route return 405 instead of 404?" The answers are in `tree.go`.

---

## Mental Model

### The Tree as a Routing Decision Machine

Imagine a trie where each node represents a path segment. A naive trie for `/users`, `/users/{id}`, `/users/{id}/orders`:

```
(root)
 └── "users"
      ├── (GET, POST) → handlers["/users"]
      └── "{id}"
           ├── (GET, PUT, DELETE) → handlers["/users/{id}"]
           └── "/orders"
                └── (GET) → handlers["/users/{id}/orders"]
```

Chi compresses this further. Consecutive static segments without branching are merged into a single node:

```
(root) → "users/{id}" → "/orders"
```

The key insight: **route lookup walks the tree character-by-character (actually segment-by-segment), never revisiting nodes, never backtracking.** At each node, Chi checks: does this node have a handler for my HTTP method? If yes, match. Does this node have children matching the next path segment? If yes, descend.

### Chi's Radix Tree vs Standard Trie

| Property | Standard Trie | Chi Radix Tree |
|----------|--------------|----------------|
| Node per character | Yes | No (per segment) |
| Compressed paths | No | Yes (merged static segments) |
| Lookup complexity | O(path_len) | O(segments) |
| Memory per route | Higher | Lower |
| Rebalancing needed | No | No (insertion builds, no rebalance) |

### The Three Pattern Types

```
/users               → static segment (exact match)
/users/{userID}      → parameter segment (single segment, captured)
/users/{userID:^[a-z]+$} → parameter with regex constraint
/users/{*}           → wildcard (matches everything remaining)
/files/{path:*}      → named wildcard (Go 1.22+ style)
```

---

## Internal Architecture

### The `node` Struct

Chi's `node` struct (in `tree.go`) is the fundamental building block:

```
type node struct {
    // The path prefix this node represents (e.g., "users/" or "{id}/")
    // In a compressed tree, this is the full prefix from parent
    prefix   string

    // Child nodes, indexed by the first byte of their prefix
    // This is a sorted slice, searched with binary search during lookup
    children []*node

    // Handlers stored per HTTP method for exact match at this node
    // methodTyp is an uint8 enum: methodGet, methodPost, methodPut, etc.
    handlers  map[methodTyp]http.Handler

    // Subroutes attached to this node (for Mount)
    subroutes Routes

    // When this node matches, this is stored in request context
    routePath string   // e.g., "/users/{userID}/orders"
}
```

Critical design decisions:
1. **`handlers` is a `map[methodTyp]http.Handler`**, not a slice. This means method lookup is `O(1)` after reaching the correct node.
2. **`children []*node`** is sorted by first byte. During lookup, Chi performs a binary search on the first byte, then linear scan for the specific child. This is effective because even large APIs rarely have more than ~50 children per node.
3. **`prefix`** is the compressed path segment. For `/users/orders/active`, the prefix might be `"users/orders/active"` all in one node if there's no branching.

### The `methodTyp` Type

Chi represents HTTP methods as `uint8` constants, not strings. This avoids string comparisons during hot-path lookups:

```
const (
    methodGet     methodTyp = 1 << iota  // 1
    methodHead                           // 2
    methodPost                           // 4
    methodPut                            // 8
    methodPatch                          // 16
    methodDelete                         // 32
    methodConnect                        // 64
    methodOptions                        // 128
    methodTrace                          // 256
)
```

Methods are bitwise flags. A node can have handlers for multiple methods, and the lookup simply checks if `handlers[method]` exists. The `methodNotAllowed` handler checks all registered methods on a node to construct the `Allow` header.

### The `routesByMethod` Type

For the special case of `MethodNotAllowed`, Chi uses `routesByMethod` — a map from `methodTyp` to a list of routes:

```
type routesByMethod map[methodTyp][]*node
```

When a request matches a node's prefix but has the wrong method, Chi constructs the 405 response by iterating over all methods that *do* have handlers at that node.

---

## Runtime Behavior

### Route Insertion Algorithm

When you call `r.Get("/users/{userID}/orders", handler)`:

1. **Path normalization**: Chi cleans the path — removes double slashes, ensures trailing slash consistency (depending on router configuration).

2. **Node traversal and splitting**: The algorithm walks the tree from root:
   - Find the longest common prefix between the current node's `prefix` and the remaining path.
   - If the node's prefix matches the path prefix partially, **split the node**:
     ```
     Before: node prefix="users/active"
     Insert: "/users/{id}"
     Result: node prefix="users/"
             ├── child prefix="active"
             └── child prefix="{id}"
     ```
   - This splitting preserves the compressed nature of the tree.

3. **Parameter and wildcard extraction**: When inserting a segment like `{userID}`, Chi:
   - Stores the parameter name (e.g., `userID`)
   - Creates a `param` child node with `prefix="{userID}"`
   - For wildcards `{*}`, creates a `wildcard` child with a flag indicating catch-all behavior

4. **Handler assignment**: At the final node (after inserting/splitting), store the handler in `node.handlers[methodGet]`.

5. **Subroute attachment**: If using `r.Mount()` or `r.Route()`, the subroute's root node is attached to the parent node's `subroutes` field.

### Route Matching Algorithm (the hot path)

Given `GET /users/42/orders/active`:

```
Step 1: Normalize the request path (same as insertion normalization).
Step 2: Start at root node.
Step 3: For each remaining path segment:
    a. Match against node.prefix:
       - If node.prefix is a static prefix (e.g., "users/"):
         Check if path starts with "users/" → consume those bytes.
       - If node.prefix is a parameter (e.g., "{userID}"):
         Consume until next "/" → capture "42" as userID parameter.
       - If node.prefix is a wildcard (e.g., "{*}"):
         Consume everything remaining → capture "orders/active".
    b. After consuming prefix:
       - Check node.handlers[methodGet]:
         If found AND no more path → MATCH! Return handler + params.
         If found AND more path AND node has subroutes → try subroute match.
         If found AND more path AND no subroutes → continue to children.
       - If not found:
         Check node.children for next segment match.
         If no children match → 404.
Step 4: If we run out of path before finding a handler → 404.
```

### Resolution Priority Order

When multiple pattern types could match the same path, Chi resolves conflicts with this priority:

1. **Static segments** (e.g., `/users/active`) — most specific, highest priority
2. **Regex-constrained parameters** (e.g., `/users/{id:^[0-9]+$}`)
3. **Regular parameters** (e.g., `/users/{id}`)
4. **Wildcard** (e.g., `/users/{*}`) — least specific, lowest priority

If two routes have identical priority (e.g., two regex parameters), Chi uses **first-registered wins** but emits a panic at startup for truly conflicting patterns.

### Route Conflict Detection

Chi detects some conflicts at registration time:

```
// These will panic at startup:
r.Get("/users/{id}", handler1)
r.Get("/users/{name}", handler2)  // PANIC: conflicting parameter names for same pattern

// These will NOT panic — different patterns, different priorities:
r.Get("/users/{id}", handler1)
r.Get("/users/{*}", handler2)     // OK: parameter > wildcard, but wildcard catches deeper paths
```

---

## Request Flow Diagrams

### Route Registration Flow

```
                    r.Get("/users/{id}/posts", h)
                              │
                              ▼
                    ┌─────────────────┐
                    │ Clean/Normalize  │
                    │    the path       │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ Walk tree from   │
                    │ root, find LCP   │
                    └────────┬────────┘
                             │
                    ┌────────┼────────┐
                    │                 │
               Prefix               Prefix
              matches?            partial?
                    │                 │
                    ▼                 ▼
            ┌──────────┐    ┌──────────────┐
            │ Traverse  │    │ Split node    │
            │ to child  │    │ at divergence │
            └─────┬────┘    └──────┬───────┘
                  │                │
                  ▼                ▼
            ┌──────────┐    ┌──────────────┐
            │ Insert at │    │ Insert new    │
            │ leaf or   │    │ node as child │
            │ traverse  │    │ of split node │
            └─────┬────┘    └──────┬───────┘
                  │                │
                  └────────┬───────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ Store handler in │
                  │ node.handlers    │
                  │ [methodGet] = h   │
                  └─────────────────┘
```

### Request Routing Flow (Hot Path)

```
                GET /users/42/posts/recent
                           │
                           ▼
                  ┌─────────────────┐
                  │ Clean/Normalize  │
                  │    request URL   │
                  └────────┬────────┘
                           │
                           ▼
           ┌───────────────────────────────┐
           │ Start at root. Search children │
           │ by first byte of path ("/u")   │
           └───────────────┬───────────────┘
                           │
                           ▼
                   ┌──────────────┐
                   │ node[0]: "/"   │──── skip (separator node)
                   │ node[1]: "api" │──── no match
                   │ node[2]: "users"───► MATCH!
                   └──────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Consume "users" from     │
              │ path. Remaining:         │
              │ "/42/posts/recent"       │
              │ Check handlers: none     │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Search children for     │
              │ first char "/" or "{"   │
              │ → found: "{userID}"     │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Parameter match:         │
              │ consume "42",            │
              │ capture userID = "42"    │
              │ Remaining: "/posts/recent"│
              │ Check handlers: none     │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Search children for     │
              │ first char "/"          │
              │ → found: "/posts"       │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Static match:            │
              │ consume "/posts"         │
              │ Remaining: "/recent"     │
              │ Check handlers: none     │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Search children for     │
              │ first char "/"          │
              │ → found: "/recent"      │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Static match:            │
              │ consume "/recent"        │
              │ Remaining: "" (empty)    │
              │ Check handlers:          │
              │ handlers[methodGet]=h    │
              │ → MATCH! Return h        │
              └────────────┬───────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Set RouteContext:        │
              │ params = [userID:"42"]   │
              │ routePattern =           │
              │   "/users/{userID}/posts/recent" │
              └────────────────────────┘
```

---

## Lifecycle Diagrams

### Router Lifecycle

```
 ┌──────────────────────────────────────────────────────────────────┐
 │                        ROUTER LIFECYCLE                           │
 │                                                                    │
 │  chi.NewRouter()          Route Registration      Serving Requests │
 │  ┌──────────┐            ┌──────────────┐       ┌──────────────┐ │
 │  │ Allocate  │───────────►│ Insert routes │──────►│ Accept conn  │ │
 │  │ Mux struct│            │ into radix    │       │ from listener │ │
 │  │           │            │ tree          │       └──────┬───────┘ │
 │  │ tree: radix│           └──────┬───────┘              │         │
 │  │  tree     │                  │                      ▼         │
 │  │ middleware│           ┌──────▼───────┐       ┌──────────────┐ │
 │  │  stack    │           │ Tree built    │       │ For each req: │ │
 │  │           │           │ & validated   │       │ Parse URL     │ │
 │  │ notFound: │           └──────────────┘       │ Lookup route  │ │
 │  │  handler  │                                   │ Walk tree     │ │
 │  └──────────┘              ┌──────────────┐       │ Match handler │ │
 │                            │ Panic if      │       │ Build ctx     │ │
 │                            │ conflicts     │       │ Apply midware │ │
 │                            │ detected      │       │ Serve HTTP    │ │
 │                            └──────────────┘       └──────────────┘ │
 └──────────────────────────────────────────────────────────────────┘
```

### Tree Node Lifecycle

```
 ┌──────────────────────────────────────────────────────────────────┐
 │                       NODE LIFECYCLE                              │
 │                                                                    │
 │    Creation              Modification             Lookup           │
 │    ┌────────┐           ┌────────────┐          ┌─────────┐       │
 │    │ Insert  │──────────►│ Split node  │          │ Match    │       │
 │    │ new node│           │ Insert child│          │ request  │       │
 │    │ at leaf │           │ Add handler │          │ path     │       │
 │    └────────┘           └─────┬──────┘          └────┬────┘       │
 │                               │                      │            │
 │                               ▼                      ▼            │
 │                        ┌────────────┐          ┌─────────┐       │
 │                        │ Re-sort     │          │ Binary   │       │
 │                        │ children    │          │ search   │       │
 │                        │ slice       │          │ children │       │
 │                        └────────────┘          └────┬────┘       │
 │                                                     │            │
 │                            ┌────────────┐           ▼            │
 │                            │ Handlers    │      ┌─────────┐       │
 │                            │ stored per  │      │ Compare  │       │
 │                            │ methodTyp   │      │ prefix   │       │
 │                            └────────────┘      │ strings  │       │
 │                                                └────┬────┘       │
 │                                                     │            │
 │                               ┌─────────┐           ▼            │
 │                               │ Tree is  │      ┌─────────┐       │
 │                               │ immutable│      │ Extract  │       │
 │                               │ after    │      │ params   │       │
 │                               │ serve    │      │ from path│       │
 │                               │ starts   │      └─────────┘       │
 │                               └─────────┘                        │
 └──────────────────────────────────────────────────────────────────┘
```

**Critical insight**: The radix tree is built entirely at startup (route registration phase). Once `http.ListenAndServe` is called, the tree is **immutable**. No locks, no synchronization, no copy-on-write — just pure, fast reads. This is why Chi scales well: the hot path has zero contention on the routing structure.

---

## Source Code Reading Guide

### Files to Read (In Order)

| Order | File | Lines | What to Focus On |
|-------|------|-------|------------------|
| 1 | `chi/tree.go` | ~300 | **Read every line.** This is the core. Understand `node` struct, `insertRoute`, `findRoute`, `addRoute`. |
| 2 | `chi/mux.go` | ~400 | `route()`, `handle()`, `buildPath()`, `ServeHTTP()`. How the Mux calls into the tree. |
| 3 | `chi/chain.go` | ~80 | How `Chain` wraps middleware into a single `http.Handler`. |
| 4 | `chi/context.go` | ~150 | `RouteContext`, `URLParam`, `WithRouteContext`. How parameters are threaded through context. |
| 5 | `chi/mux_test.go` | ~500 | Test coverage reveals edge cases: what happens with trailing slashes, double slashes, conflicting patterns. |

### What to Ignore

- `chi/middleware/` — we cover middleware in Sessions 14/16.
- Test helper functions in test files — focus on the test cases themselves.
- `chi/walk.go` — used for printing route trees, not for production routing.

### Deep-Dive: `findRoute` in `tree.go`

```go
func (n *node) findRoute(rctx *Context, method methodTyp, path string) (http.Handler, bool)
```

This is the function on the hot path. Key steps:

1. **Prefix matching**: Walk `path` against `n.prefix` character by character. If the path is shorter than the prefix → no match.
2. **Parameter extraction**: When a `{param}` pattern is encountered, consume until the next `/` or end of string.
3. **Wildcard extraction**: When a `{*}` pattern is encountered, consume everything remaining.
4. **Handler lookup**: After consuming the prefix, check `n.handlers[method]`.
5. **Child traversal**: If no handler and more path, binary-search children by first byte, then compare prefixes.

The most subtle part: **edge handling**. Chi must handle:
- Trailing slashes: `/users/` vs `/users`
- Double slashes: `/users//42`
- Empty path components
- Paths that exactly match a node with a subroute

---

## Production Failure Scenarios

### Scenario 1: The 405 vs 404 Confusion

**Symptom**: A POST to `/users/42` returns 405 Method Not Allowed, but developers expected 404 (route not found).

**Root Cause**: Chi's tree has a node at `/users/{userID}` with a GET handler but no POST handler. Chi correctly identifies that the *path* exists but the *method* doesn't. The 405 is correct behavior.

**Resolution**: Either add a POST handler to `/users/{userID}` or ensure the client corrects its route. Do not silence 405s — they're valuable debugging signals.

### Scenario 2: Shadowed Routes

**Symptom**: `GET /api/v2/users/export` always hits `/api/v2/users/{id}` handler, never the export handler.

**Root Cause**: The export route was registered AFTER the parameter route, and both share the same prefix `/api/v2/users/`. Chi resolves static > parameter, so `/export` should match before `{id}`. The actual issue is likely that the export route was registered incorrectly (e.g., with a typo or wrong pattern).

**Debugging**: Use `chi.Walk()` to print the entire routing tree and verify node placement.

### Scenario 3: Memory Pressure from Route Explosion

**Symptom**: After deploying a multi-tenant gateway with per-tenant routes, memory usage doubled.

**Root Cause**: Generating routes dynamically per tenant (e.g., `/tenant-001/users`, `/tenant-002/users`, ... `.`tenant-1000/users`) creates 1000x more nodes than a single `/api/{tenantID}/users` parameterized route. Each node stores handlers and metadata.

**Resolution**: Use URL parameters (`{tenantID}`) instead of encoding tenant identity in the path structure. If you truly need static routes (for security or isolation), consider a separate router per tenant behind a reverse proxy.

### Scenario 4: Startup Panic from Conflicting Routes

**Symptom**: Service won't start, panics with "conflicting routes" message.

**Root Cause**: Two identical route patterns with different handler signatures (e.g., two different teams registering `/health` handlers in separate packages).

**Resolution**: Chi detects this at registration time and panics — this is a feature, not a bug. The fix is to consolidate the handlers or use subrouters with different prefixes.

---

## Debugging Techniques

### 1. Print the Full Route Tree

```go
import "github.com/go-chi/chi/v5"

func init() {
    chi.Walk(r, func(method string, route string, handler http.Handler, middlewares ...func(http.Handler) http.Handler) error {
        fmt.Printf("[%s] %s\n", method, route)
        return nil
    })
}
```

This prints every route in the order Chi would consider them. Use this to verify route registration and spot shadowing.

### 2. Inspect RouteContext on Every Request

```go
r.Use(func(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        next.ServeHTTP(w, r)
        rctx := chi.RouteContext(r.Context())
        fmt.Printf("Route: %s, Params: %v\n", rctx.RoutePattern(), rctx.URLParams)
    })
})
```

This logs which route actually matched and what parameters were extracted.

### 3. Trace Route Resolution Manually

```go
// Add at the top of ServeHTTP in a custom wrapper:
func traceRoute(r *chi.Mux, method, path string) {
    rctx := chi.NewRouteContext()
    if r.Match(rctx, method, path) {
        fmt.Printf("MATCH: %s %s → %s\n", method, path, rctx.RoutePattern())
        for i, key := range rctx.URLParams.Keys {
            fmt.Printf("  param[%d]: %s = %s\n", i, key, rctx.URLParams.Values[i])
        }
    } else {
        fmt.Printf("NO MATCH: %s %s\n", method, path)
    }
}
```

### 4. Benchmark Individual Routes

```go
func BenchmarkRouteLookup(b *testing.B) {
    r := chi.NewRouter()
    r.Get("/users/{id}/orders/{orderID}/items/{itemID}", handler)
    rctx := chi.NewRouteContext()
    b.ResetTimer()
    for i := 0; i < b.N; i++ {
        rctx.Reset()
        r.Match(rctx, "GET", "/users/123/orders/456/items/789")
    }
}
```

---

## Observability Considerations

### Route Resolution Latency

Chi's route lookup is `O(segments)`, typically 3–8 segments for REST APIs. This means ~50–200 nanoseconds per lookup. You should almost never see route resolution in your trace profiles.

If you do, check:
- Are you using `r.Handle()` with a path containing regex that backtracks?
- Are you using wildcard catch-all `{*}` on extremely long paths (hundreds of segments)?
- Are you accidentally re-parsing the URL in middleware?

### Metric: Route Cardinality

Track the total number of registered routes per service. This should be O(100), not O(10,000). If you have 10,000 routes, you're probably encoding data in path structure instead of URL parameters.

```go
routeCount := 0
chi.Walk(r, func(method, route string, handler http.Handler, middlewares ...func(http.Handler) http.Handler) error {
    routeCount++
    return nil
})
metrics.SetGauge("chi_routes_total", float64(routeCount))
```

### Metric: 404 vs 405 Ratio

A high ratio of 405s to 404s suggests clients are reaching the right paths with wrong methods — possibly an API versioning issue or outdated client SDK.

---

## Performance Implications

### O(path_length) is the Key Property

Chi's routing performance is independent of the number of registered routes. A router with 10 routes and a router with 10,000 routes have the same per-request lookup cost, *provided the tree depth (number of segments) is the same*.

**Benchmark data (approximate):**
| Scenario | Routes | Lookup Time |
|----------|--------|-------------|
| 5-route router, 3-segment path | 5 | ~50ns |
| 500-route router, 3-segment path | 500 | ~50ns |
| 5-route router, 10-segment path | 5 | ~150ns |
| 500-route router, 10-segment path | 500 | ~150ns |

The dominant factor is path depth, not route count.

### Child Search: Binary + Linear

Chi searches `node.children` by first byte using Go's `sort.Search` (binary search). After narrowing to matching first byte, it does a linear scan through remaining candidates. This is optimal because:
- First-byte binary search reduces candidates from `n` to ~`n/256`.
- Remaining candidates rarely exceed 3–5 in practice.
- Most API routers have < 10 children per node.

### Avoiding Allocation on the Hot Path

Chi's `RouteContext` is designed for reusability:
- `rctx.Reset()` clears it between requests (zeroes the struct).
- Parameter storage uses `Keys []string` and `Values []string` slices that are reused across requests via `sync.Pool` (in some configurations).
- No `map[string]string` for URL parameters — two parallel slices (`Keys`, `Values`) for cache-friendly iteration.

---

## Architecture Implications

### Don't Encode Logic in Route Structure

Bad (exponential route growth):
```
/api/v1/tenants/acme/users/export
/api/v1/tenants/beta/users/export
/api/v1/tenants/gamma/users/export
```

Good (single route, O(1) tree size):
```
/api/v1/tenants/{tenantID}/users/export
```

### Subrouters for Logical Boundaries

When you have distinct subsystems with separate middleware requirements, use subrouters:

```go
r.Route("/api/v1", func(r chi.Router) {
    // Public routes — no auth
    r.Group(func(r chi.Router) {
        r.Get("/health", healthHandler)
        r.Get("/version", versionHandler)
    })

    // Authenticated routes
    r.Group(func(r chi.Router) {
        r.Use(authMiddleware)
        r.Get("/users/{id}", getUserHandler)
    })

    // Admin routes — auth + admin check
    r.Group(func(r chi.Router) {
        r.Use(authMiddleware, adminMiddleware)
        r.Get("/admin/stats", adminStatsHandler)
    })
})
```

This keeps the tree organized and middleware scoping explicit.

### Mount for Third-Party Integration

Use `r.Mount()` when integrating a standalone handler that manages its own routing:

```go
r.Mount("/debug", debug.Profiler())     // net/http/pprof
r.Mount("/metrics", promhttp.Handler())  // Prometheus
```

The mounted handler receives requests with the prefix stripped from `r.URL.Path`.

---

## Team Ownership Implications

### Route Registration Conventions

Establish a standard for how packages register their routes:

```go
// pkg/users/routes.go
func RegisterRoutes(r chi.Router, svc *Service) {
    r.Route("/users", func(r chi.Router) {
        r.Get("/", svc.List)
        r.Post("/", svc.Create)
        r.Get("/{id}", svc.Get)
        r.Put("/{id}", svc.Update)
        r.Delete("/{id}", svc.Delete)
    })
}
```

This pattern:
- Keeps route registration co-located with the handler package.
- Allows each team to own their route subtree independently.
- Prevents merge conflicts on a central `routes.go` file.

### Route Ownership via CODEOWNERS

Map route subtrees to team ownership using GitHub CODEOWNERS:

```
/pkg/users/      @team-users
/pkg/orders/     @team-orders
/pkg/payments/   @team-payments
```

When a route lookup fails or produces unexpected behavior, the owning team is immediately identifiable.

### Preventing Route Collisions

In monorepos with 10+ teams, route collisions are a real risk. Mitigations:
1. **CI check**: Run `chi.Walk()` in a test, assert no route is registered twice.
2. **Prefix convention**: `/{service}/{resource}` (e.g., `/users/profiles`, `/orders/items`).
3. **Route registry test**: A single integration test that enumerates all routes from all packages and panics on duplicates.

---

## Interview Questions

**Q1: What data structure does Chi use for routing, and what is its lookup complexity?**

A: Chi uses a compressed radix tree (Patricia trie). Lookup complexity is `O(P)` where `P` is the number of path segments — typically 3–8 for REST APIs. It is independent of the total number of registered routes.

**Q2: What happens when two routes match the same path pattern?**

A: Chi panics at registration time with a "conflicting route" message. This is by design — route conflicts should be caught at startup, not silently produce incorrect behavior in production.

**Q3: Explain the difference between `{param}`, `{param:regex}`, and `{*}` in Chi route patterns.**

A: `{param}` matches a single path segment (up to the next `/`). `{param:regex}` matches a single segment constrained by the regular expression. `{*}` is a wildcard that matches everything remaining in the path, including slashes.

**Q4: Why does Chi use `map[methodTyp]http.Handler` instead of storing handlers in a slice?**

A: Method lookup is `O(1)` with a map, versus `O(methods)` with a slice. For the hot path where we've already found the correct tree node, we want instant handler retrieval. The map overhead (~8 methods max) is negligible.

**Q5: How does Chi handle the 405 Method Not Allowed case?**

A: When a request matches a tree node's path but no handler exists for that specific HTTP method, Chi constructs a `routesByMethod` map from all methods registered at that node and returns a 405 response with an `Allow` header listing the permitted methods.

**Q6: What is the difference between `r.Get()` and `r.Method("GET", ...)`?**

A: `r.Get()` is syntactic sugar — it calls `r.Method("GET", ...)` internally. Both resolve to `r.MethodFunc("GET", pattern, handler)`. There is zero performance difference.

**Q7: Can you register routes dynamically at runtime after the server has started?**

A: Yes, technically the tree is not locked, but this is dangerous without synchronization. Chi's tree is designed to be built at startup and read-only during serving. Modifying the tree concurrently with requests will cause data races. Use `r.Mount()` with a dynamically-routed handler if you need runtime flexibility.

**Q8: How does Chi's `Mount()` differ from `Route()`?**

A: `Mount()` attaches an `http.Handler` at a prefix and strips that prefix from `r.URL.Path` before passing the request to the handler. `Route()` registers a subrouter declared inline. `Mount()` is for integrating external handlers (pprof, Prometheus); `Route()` is for organizing your own routes.

**Q9: What happens to the request path during a mount?**

A: Chi strips the mount prefix from `r.URL.Path` before passing it to the mounted handler. If you mount at `/api/v1`, a request to `/api/v1/users/42` arrives at the mounted handler with `r.URL.Path = "/users/42"`.

**Q10: How do you inspect the entire routing tree at runtime?**

A: Use `chi.Walk(r, walkFunc)` which calls `walkFunc` for every registered route, passing the HTTP method, route pattern, handler, and list of middlewares. This is useful for debugging, documentation generation, and route conflict detection.

---

## Hands-On Exercises

### Exercise 1: Build a Minimal Radix Tree Router

Implement a simplified version of Chi's radix tree with the following constraints:
- Support only static segments and `{param}` patterns (no regex, no wildcard).
- Support only GET, POST, PUT, DELETE methods.
- Implement `addRoute(method, path, handler)` and `findRoute(method, path)`.

**Learning goal**: Understand tree splitting, prefix matching, and parameter extraction without Chi's additional features.

**Starter code**:
```go
type node struct {
    prefix   string
    handlers map[string]http.Handler
    children []*node
    param    string  // non-empty if this is a parameter node
}
```

### Exercise 2: Visualize Route Conflicts

Given a Chi router with the following routes, predict which will panic and why:
```go
r.Get("/users/{id}", h1)
r.Get("/users/{name}", h2)
r.Get("/users/active", h3)
r.Get("/users/{id}/posts", h4)
r.Get("/users/{id}/posts/{postID}", h5)
r.Get("/users/{id}/posts/recent", h6)
r.Get("/users/{*}", h7)
```

Run the code, observe the panic, fix the conflicts, and explain the resolution priority order.

### Exercise 3: Benchmark Route Lookup at Scale

Create a router with 1000 parameterized routes (e.g., `/resource/{id}/subresource/{subID}/action/{actionID}`). Benchmark lookup time for:
- Best case (first route added)
- Worst case (last route added)
- Deepest path (10+ segments)

Compare against a linear scan of `[]route` to demonstrate why the radix tree matters.

### Exercise 4: Trace a Real Request Through `findRoute`

Add debug logging to a fork of `chi/tree.go` that prints every node visited during route lookup. Run a request and trace:
- Which nodes were examined
- Which nodes were skipped (why?)
- Where the final handler was found
- What parameters were extracted and when

### Exercise 5: Implement a Route Diff Tool

Write a CLI tool that:
1. Takes two Go files containing router definitions.
2. Parses them and extracts all routes.
3. Prints a diff: added routes, removed routes, changed routes.
4. Flags potential breaking changes (e.g., parameter name change from `{userID}` to `{id}`).

---

## Advanced Challenges

### Challenge 1: Implement Regex-Backed Route Matching

Chi's built-in regex support is limited (Go 1.22+ pattern matching). Implement a custom `node` type that compiles regex patterns at registration time and matches against them at lookup time. Handle the case where a regex matches but consumes too much of the path (e.g., `{id:[a-z]+}` matching "abc123" should stop at the digit).

Consider the performance implications: regex matching is `O(n)` for the segment length but involves an NFA/DFA evaluation, which is orders of magnitude slower than string comparison. Measure the overhead.

### Challenge 2: Build a Thread-Safe Dynamic Route Tree

Chi's tree is not safe for concurrent modification. Design and implement a thread-safe variant using one of:
- `sync.RWMutex` (simple but high contention)
- Copy-on-write with atomic pointer swap (low read contention, writes require full copy)
- Lock-free trie (research challenge)

Benchmark read and write throughput under concurrent load. Determine which approach is appropriate for a read-heavy (99.9% reads) workload vs a write-heavy workload.

### Challenge 3: Implement Route-Level Caching

For services where route lookup dominates latency (e.g., very deep paths, high request rates), implement an LRU cache in front of the radix tree:

```go
type CachingMux struct {
    tree  *node
    cache *lru.Cache  // key: "GET:/users/42/posts" → handler + params
}
```

Measure the cache hit rate under realistic traffic patterns. Determine if the overhead of cache management outweighs the benefit given Chi's already-fast lookups.

---

## Key Insights

1. **Chi's radix tree is the reason Chi is fast.** The `O(path_segments)` lookup is bounded by URL depth, not route count. For REST APIs with shallow paths (3–8 segments), this means ~50–200ns per lookup regardless of whether you have 5 routes or 5,000.

2. **The tree is built once and never modified during serving.** This eliminates synchronization overhead on the hottest path in the framework. No locks, no atomics, no copy-on-write — just pure, deterministic reads.

3. **Route priority is static > regex > parameter > wildcard.** Understanding this order explains 95% of route resolution surprises. When in doubt, print the tree with `chi.Walk()`.

4. **Chi's `node` struct is elegant because it's simple.** ~300 lines in `tree.go` implement the entire routing algorithm. There's no bloom filter, no hash table, no LRU cache — just a compressed trie with sorted children. Simplicity is the source of reliability.

5. **Parameters are stored as parallel slices (`Keys`, `Values`), not a map.** This is a deliberate performance optimization: slices are cache-friendly, iteration is fast, and the allocation pattern is predictable. The tradeoff is that lookup by key is `O(params)` instead of `O(1)`, but routes rarely have more than 3–5 parameters.

6. **The methodTyp bitmask enables fast 405 handling.** When a node matches the path but not the method, Chi uses the pre-computed method map to build the `Allow` header in `O(methods)` time — no tree re-walk needed.

7. **Mount vs Route vs Group** — these three composition primitives serve different purposes. Mount is for external `http.Handler`s. Route is for logical subgrouping. Group is for middleware inheritance. Confusing them leads to subtle bugs around path prefix stripping.

8. **The 405 vs 404 distinction is a feature, not an annoyance.** When a client gets 405, the path exists but the method is wrong. This tells the client "your URL is correct, but change your HTTP method." Suppressing this with a custom 404 handler destroys that signal.
