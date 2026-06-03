# Session 15: Chi Routing Groups, Subrouters, Context

## Why This Topic Exists

Every non-trivial API organizes routes into logical groups: `/api/v1/users/...`, `/api/v1/orders/...`, `/api/v2/admin/...`. Chi provides three mechanisms for grouping — `r.Route()`, `r.Group()`, and `r.Mount()` — each with distinct semantics around path prefixing, middleware isolation, and handler resolution. Choosing the wrong one produces silent bugs: middleware that doesn't fire, routes that resolve to wrong handlers, or 404s where 405s are expected.

The `r.Route()` pattern allows you to define a block of routes sharing a common path prefix and middleware. The `r.Group()` pattern creates a subrouter that inherits parent middleware but returns a separate `chi.Router` handle. The `r.Mount()` pattern attaches an entirely separate router instance, complete with its own middleware chain, at a path prefix on the parent. These three are not interchangeable, yet their differences are subtle and poorly documented.

As a Staff/Principal Engineer, you will own API surface partitioning. You will decide that `/admin` belongs on a separate router with stricter middleware. You will debug a bug where `r.Group()`-scoped middleware runs before or after unexpectedly. You will implement `chi.URLParam()` correctly in deeply nested route trees. You will answer: "Why does `/*` catch routes registered below it but `/{param}` doesn't?" This session is the definitive reference.

---

## Mental Model

### The Router as a Tree of Subrouters

Think of a Chi router as a tree. At the root is the `chi.Mux` returned by `chi.NewRouter()`. When you call `r.Route("/users", func(r chi.Router) { ... })`, you create a subtree: a new subrouter whose path prefix is `/users`. The subrouter is not a separate Mux instance — it is a lightweight "mount point" in the same tree. When you call `r.Group(func(r chi.Router) { ... })`, you similarly get a subrouter, but with no path prefix. When you call `r.Mount("/admin", adminRouter)`, you splice a completely separate Mux instance into the tree at `/admin`.

The mental model for each:

```
r.Route("/v1", func(r chi.Router) {
    r.Use(v1Middleware)              ← middleware applies ONLY inside this block
    r.Get("/users", usersHandler)    ← full path: /v1/users
})
```

- `r.Route("/prefix", fn)` — creates temporary subrouter with path prefix, passes it to `fn`. When `fn` returns, the subrouter is "sealed" — no more routes can be added to that prefix directly (though nested `Route` calls within `fn` work fine).
- `r.Group(fn)` — identical semantics but NO path prefix. Useful when you want middleware isolation without changing the URL path.
- `r.Mount("/prefix", otherRouter)` — attaches an independent router. `/prefix` is stripped before the otherRouter sees the request. `/prefix/users` → otherRouter sees `/users`.

### Middleware Inheritance: Parent First, Then Subrouter

When a request arrives, middleware fires in a defined order. Chi walks the middleware in registration order at EACH level of the tree:

```
r.Use(A)              // fires 1st
r.Route("/v1", func(r chi.Router) {
    r.Use(B)          // fires 2nd (after parent middleware)
    r.Use(C)          // fires 3rd
    r.Get("/x", h)    // handler h fires 4th
})
```

A concrete trace for `GET /v1/x`:

```
Middleware A (before next.ServeHTTP)
  Middleware B (before next.ServeHTTP)
    Middleware C (before next.ServeHTTP)
      Handler h executes
    Middleware C (after next.ServeHTTP)
  Middleware B (after next.ServeHTTP)
Middleware A (after next.ServeHTTP)
```

The critical rule: **parent middleware ALWAYS wraps subrouter middleware**. You cannot register middleware on a parent that fires "inside" subrouter middleware. If you need a different order, restructure the tree.

---

## Internal Architecture

### How `r.Route()` Works

The implementation in `chi/mux.go` is deceptively simple:

```go
func (mx *Mux) Route(pattern string, fn func(r Router)) Router {
    subRouter := NewRouter()
    mx.Mount(pattern, subRouter)
    if fn != nil {
        fn(subRouter)
    }
    return subRouter
}
```

`r.Route()` creates a brand-new `chi.Mux`, mounts it at the given pattern on the parent, calls `fn` to let the caller register routes/middleware on it, and returns the subrouter. The returned subrouter is the same instance passed to `fn`. This means you can capture the subrouter:

```go
var apiRouter chi.Router
r.Route("/api", func(r chi.Router) {
    apiRouter = r
    r.Get("/status", statusHandler)
})
// apiRouter is now the /api subrouter
// BUT: middleware added to the parent AFTER this block does NOT affect apiRouter
```

Key implementation detail: `Mount()` adds the subrouter to the parent's radix tree as a child node. The parent routes the request prefix, strips the matched prefix, and forwards to the subrouter. This is different from `Handle()`, which adds a single handler for a complete path pattern.

### How `r.Group()` Works

```go
func (mx *Mux) Group(fn func(r Router)) Router {
    subRouter := NewRouter()
    if fn != nil {
        fn(subRouter)
    }
    mx.Mount("/", subRouter)
    return subRouter
}
```

`Group()` is `Route()` without the path prefix. It mounts the subrouter at `"/"` on the parent. This means all routes registered on the subrouter live at the parent's path level. The only purpose of `Group()` is to scope middleware to a subset of routes without changing the URL structure:

```go
r.Group(func(r chi.Router) {
    r.Use(authMiddleware)       // only applies to routes in this group
    r.Get("/profile", profileHandler)    // path is /profile, not /something/profile
    r.Get("/settings", settingsHandler)  // path is /settings
})
```

Group's subrouter at `"/"` is a special case in the radix tree. Because the prefix is empty (or `/`), the parent doesn't strip any path segments. The subrouter sees the full path, and the radix tree handles overlap resolution: if both the parent and subrouter register handlers for the same path, the radix tree's node merging logic determines which wins — generally the first registered handler takes priority.

### How `r.Mount()` Works

```go
func (mx *Mux) Mount(pattern string, handler http.Handler) {
    // ...
    mx.tree.InsertRoute(methodAll, pattern, handler)
}
```

`Mount()` treats the subrouter as an `http.Handler` and inserts it into the parent's radix tree at the given pattern. When a request matches the mount pattern, the parent calls `subRouter.ServeHTTP(w, r)` — but first it strips the matched prefix from the request path and stores the route parameters.

The prefix stripping is the critical distinction:

```
r.Mount("/api/v2", v2Router)

Request: GET /api/v2/users/42
→ Parent matches "/api/v2", strips it
→ v2Router receives request with path: /users/42
→ v2Router's radix tree searches for /users/42
→ Returns v2Router's handler for that path
```

This means `v2Router` must register its routes using paths like `/users/{id}`, NOT `/api/v2/users/{id}`. The URL nesting is transparent to the subrouter.

### How `r.With()` Works

`r.With()` is the most granular middleware scoping mechanism:

```go
func (mx *Mux) With(middlewares ...func(http.Handler) http.Handler) Router {
    subRouter := NewRouter()
    subRouter.Use(middlewares...)
    mx.Mount("/", subRouter)
    return subRouter
}
```

`r.With()` creates a new subrouter, adds the given middlewares to it, mounts it at `"/"` on the parent, and returns it. The caller can then add routes or further With/Group/Route to the returned subrouter:

```go
r.Route("/api", func(r chi.Router) {
    // Public routes — no auth
    r.Get("/health", healthHandler)

    // Authenticated routes
    r.With(authMiddleware).Get("/me", meHandler)

    // Authenticated + admin
    r.With(authMiddleware, adminMiddleware).Group(func(r chi.Router) {
        r.Get("/users", listUsersHandler)
        r.Delete("/users/{id}", deleteUserHandler)
    })
})
```

The returned router from `r.With()` is a separate subrouter. Middleware added to the parent after the With() call does NOT apply to routes on the With() subrouter.

### RouteContext: The Request-Scoped Values Store

Every Chi request has a `RouteContext` stored in the `context.Context` of the request. The storage mechanism uses `context.WithValue()`:

```go
func (mx *Mux) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // RouteContext is created per-request
    rctx := NewRouteContext()
    // Attempt route match
    if !mx.tree.FindRoute(rctx, method, path) {
        // 404 or 405
        return
    }
    // Store RouteContext in request context
    r = r.WithContext(context.WithValue(r.Context(), RouteCtxKey, rctx))
    // Call the matched handler
    rctx.RouteHandler.ServeHTTP(w, r)
}
```

The `RouteContext` struct (from `chi/context.go`):

```go
type RouteContext struct {
    Routes       Routes          // The full route tree pattern (for URL generation)
    RoutePath    string          // The matched route pattern (e.g., "/users/{userID}")
    RouteMethod  string          // Method override (for method-based routing hints)
    URLParams    RouteParams     // Extracted URL parameters [{key:"userID", value:"42"}]
    RoutePattern string          // The route pattern that matched
    SubRoutes    []Routes        // Nested subrouter patterns (for Mount)
    // ...
}
```

`URLParams` is a struct containing a slice of key-value pairs:

```go
type RouteParams struct {
    Keys   []string
    Values []string
}
```

The lookup is a linear scan — O(n) where n is the number of parameters in the route. This is acceptable because most routes have fewer than 5 parameters.

### chi.URLParam() Internals

```go
func URLParam(r *http.Request, key string) string {
    if rctx := RouteContext(r.Context()); rctx != nil {
        for k := len(rctx.URLParams.Keys) - 1; k >= 0; k-- {
            if rctx.URLParams.Keys[k] == key {
                return rctx.URLParams.Values[k]
            }
        }
    }
    return ""
}
```

The lookup iterates **backwards** through the params slice. Why? Because when subrouters mount, the parent's URL params are prepended to the child's. Searching backwards means child params (more specific) are found before parent params (more general), preventing shadowing:

```
Request: GET /organizations/acme/users/42

With nested routes:
  r.Route("/organizations/{orgID}", func(r chi.Router) {
    r.Route("/users/{userID}", func(r chi.Router) {
      // URLParams after matching: [{orgID, acme}, {userID, 42}]
      // Backward search finds userID first
    })
  })
```

`URLParamFromCtx()` is the zero-allocation variant that takes a `*Context` directly (not an `*http.Request`), useful in hot paths where you already have the context.

### Wildcard Patterns: /* and {param:*}

Chi supports two wildcard mechanisms:

**`/*` — Catch-all wildcard:**

```go
r.Get("/static/*", serveFiles)
// Matches: /static/, /static/css/style.css, /static/js/app.js
// Does NOT match: /static (no trailing slash)
// Does NOT match: /static/other (unless /* is registered)
```

The `/*` pattern matches zero or more path segments after the prefix. The matched wildcard content is accessible via `chi.URLParam(r, "*")`:

```go
func serveFiles(w http.ResponseWriter, r *http.Request) {
    filePath := chi.URLParam(r, "*")  // "css/style.css" for /static/css/style.css
}
```

Internally, when Chi's radix tree encounters a `*` segment, it consumes ALL remaining path segments. No child routes can exist below a `/*` catch-all — attempting to register `r.Get("/static/*/debug", ...)` is invalid (Chi explicitly prevents this during registration).

**`{param:*}** — Named wildcard (Go 1.22+ style):

```go
r.Get("/files/{path:*}", serveFile)
// path = "images/2024/photo.jpg" for /files/images/2024/photo.jpg
```

This is syntactic sugar — internally it generates the same wildcard node in the radix tree.

### 405 Method Not Allowed vs 404

Chi differentiates between "route exists but wrong method" (405) and "route doesn't exist at all" (404) using the radix tree's handler map at each node.

When the tree walks the path and reaches a matching node, it checks the `handlers` map (keyed by HTTP method) on that node. If the node has handlers but none for the request method, Chi returns 405. If no node matches the path at all, Chi returns 404.

This happens in `tree.go`'s `FindRoute()` method:

```go
func (t *tree) FindRoute(rctx *RouteContext, method methodTyp, path string) bool {
    // Walk the tree...
    n := t.root.findEdge(path)
    if n == nil {
        // No matching node — 404
        rctx.methodNotAllowed = false
        return false
    }
    // Found a node — check handler
    if n.handlers[method] == nil {
        // Node exists but wrong method — 405
        rctx.methodNotAllowed = true
        return false
    }
    rctx.RouteHandler = n.handlers[method]
    return true
}
```

In `mux.go`'s `ServeHTTP()`, after `FindRoute()` returns false:

```go
func (mx *Mux) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // ...
    if !mx.tree.FindRoute(rctx, method, path) {
        if rctx.methodNotAllowed {
            mx.methodNotAllowedHandler.ServeHTTP(w, r)  // 405
        } else {
            mx.notFoundHandler.ServeHTTP(w, r)          // 404
        }
        return
    }
    // ...
}
```

This is why Chi returns correct 405 responses: it's not a heuristic, it's an artifact of route registration. Every registered node remembers which methods have handlers.

### Custom 404 and 405 Handlers

Chi exposes two methods to override the default handlers:

```go
r.NotFound(func(w http.ResponseWriter, r *http.Request) {
    render.JSON(w, r, map[string]string{
        "error": "resource not found",
        "path":  r.URL.Path,
    })
})

r.MethodNotAllowed(func(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Allow", "GET, POST, PUT")
    render.JSON(w, r, map[string]string{
        "error":  "method not allowed",
        "method": r.Method,
        "path":   r.URL.Path,
    })
})
```

The default Chi 404 handler returns `text/plain` "404 page not found\n". In production, override both with JSON responses matching your API's error envelope. The handlers receive the original request context, so `chi.RouteContext(r.Context())` will be `nil` (no route was matched).

### URL Generation from Route Patterns

Chi can generate URLs from named route patterns. This requires a `chi.Routes` list accessible via `r.Routes()`:

```go
r.Get("/users/{userID}", handleUser).Name("user-detail")

// Later:
rctx := chi.NewRouteContext()
if chi.RouteContext(r.Context()) != nil {
    // Build URL from current context + name
    url, err := rctx.RoutePattern("user-detail", "42")
    // url = "/users/42"
}
```

The `chi.Routes` type is `[]Route`, where each `Route` stores the pattern, handler, and optional name. The `chi.Walk()` function traverses all routes:

```go
chi.Walk(r, func(method, route string, handler http.Handler, middlewares ...func(http.Handler) http.Handler) error {
    fmt.Printf("%s %s\n", method, route)
    return nil
})
```

This is essential for generating OpenAPI/Swagger documentation from code — walking all registered routes and extracting their patterns, methods, and parameter names.

### RouteContext Thread Safety

`RouteContext` uses a sync.Pool for allocation efficiency:

```go
var routeCtxPool = sync.Pool{
    New: func() interface{} { return NewRouteContext() },
}

func (mx *Mux) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    rctx := routeCtxPool.Get().(*Context)
    rctx.Reset()
    defer routeCtxPool.Put(rctx)
    // ...
}
```

Each request gets a pooled `RouteContext`, which is reset (all fields zeroed) before use. This eliminates per-request allocations for `RouteContext` and its internal slices. The key invariant: `RouteContext` cannot be used across goroutines for the same request — it's single-request-scoped and reused after `ServeHTTP` returns.

---

## Runtime Behavior

### Request Lifecycle Through Nested Routers

Consider this configuration:

```go
r := chi.NewRouter()
r.Use(middleware.RequestID)
r.Use(middleware.Logger)

r.Route("/api/v1", func(r chi.Router) {
    r.Use(v1AuthMiddleware)

    r.Get("/users", listUsers)
    r.Get("/users/{id}", getUser)

    r.Route("/admin", func(r chi.Router) {
        r.Use(adminCheckMiddleware)
        r.Get("/dashboard", adminDashboard)
    })
})
```

Trace for `GET /api/v1/users/42`:

1. `r.ServeHTTP(w, req)` — root Mux receives request
2. Root Mux creates RouteContext, stores in `req.Context()`
3. Root Mux's middleware (RequestID, Logger) execute
4. RequestID generates/stores request ID
5. Logger records request entry
6. Radix tree walks: matches `/api/v1` prefix → strips it → forwards to v1 subrouter
7. v1 subrouter middleware (v1AuthMiddleware) executes → validates auth token
8. v1 subrouter radix tree walks: matches `/users/{id}` → extracts `id=42` → stores in RouteContext.URLParams
9. Handler `getUser` executes with `chi.URLParam(r, "id") == "42"`
10. Response flows back through Logger (logs status, duration) → RequestID (adds header)

Trace for `GET /api/v1/admin/dashboard`:

1-7: Same as above (root middleware + v1 auth)
8. v1 subrouter matches `/admin` prefix → strips it → forwards to admin subrouter
9. admin subrouter middleware (adminCheckMiddleware) executes → verifies admin role
10. admin subrouter matches `/dashboard` → handler executes

The key runtime property: each subrouter is a full `chi.Mux` with its own radix tree, its own middleware stack, and its own `ServeHTTP` method. The parent delegates to the child exactly as `http.Handler` composition — `a.ServeHTTP(w, r)` calls `b.ServeHTTP(w, r)`.

### Middleware Execution Order with r.With()

```go
r.Use(A)

r.Route("/api", func(r chi.Router) {
    r.Use(B)
    r.Get("/public", publicHandler)

    r.With(C).Get("/private", privateHandler)
})
```

For `GET /api/public`: middleware order → A, B, handler
For `GET /api/private`: middleware order → A, B, C, handler

`r.With(C)` creates a new subrouter at `/` inside `/api`, with middleware C. The full middleware chain for `/api/private` is: A (root) → B (/api subrouter) → C (With subrouter) → handler.

### How Prefix Stripping Works at Runtime

When a parent router matches a mount point and forwards to a child, it modifies `r.URL.Path`:

```go
func (mx *Mux) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    // ...after tree matching...
    if n.subroutes != nil {
        rctx.RoutePath = mx.tree.resolveRoutePattern(pattern, n)
        r = r.WithContext(context.WithValue(r.Context(), RouteCtxKey, rctx))

        // Strip the matched prefix from the URL path
        // e.g., path="/api/v1/users", mount="/api/v1" → subrouter sees path="/users"
        r.URL.RawPath = r.URL.RawPath[len(pattern):]
        r.URL.Path = r.URL.Path[len(pattern):]

        n.subroutes.ServeHTTP(w, r)
        return
    }
}
```

This is a destructive operation on the request URL. The original path is lost unless you store it. If you need the full path for logging or observability, capture it BEFORE routing:

```go
r.Use(func(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        ctx := context.WithValue(r.Context(), "original_path", r.URL.Path)
        next.ServeHTTP(w, r.WithContext(ctx))
    })
})
```

---

## Flow Diagrams

### Route Registration Flow

```
r.Get("/users/{id}", handler)
│
└─→ mux.Handle("GET", "/users/{id}", handler)
    │
    ├─→ Validate pattern (no trailing slashes conflict, etc.)
    │
    ├─→ tree.InsertRoute(methodGet, "/users/{id}", handler)
    │   │
    │   ├─→ Split pattern into segments: ["users", "{id}"]
    │   │
    │   ├─→ Walk tree: find/create node for each segment
    │   │   │
    │   │   ├─→ Segment "users": static → find/create edge "users"
    │   │   │
    │   │   └─→ Segment "{id}": param → find/create param edge
    │   │       └─→ {id} node created with type ntParam
    │   │
    │   └─→ Store handler at terminal node:
    │       node.handlers[methodGet] = handler
    │
    └─→ Return registered route info
```

### Request Handling Flow

```
GET /users/42
│
├─→ mux.ServeHTTP(w, r)
│   │
│   ├─→ routeCtx = pool.Get().Reset()
│   │
│   ├─→ middleware chain starts (parent middlewares)
│   │   │
│   │   ├─→ RequestID middleware
│   │   │   ├─→ [pre]: generate ID, store in context
│   │   │   ├─→ next.ServeHTTP(w, r)
│   │   │   │   │
│   │   │   │   ├─→ Logger middleware
│   │   │   │   │   ├─→ [pre]: record start time
│   │   │   │   │   ├─→ next.ServeHTTP(w, r)
│   │   │   │   │   │   │
│   │   │   │   │   │   └─→ mux.route(w, r)
│   │   │   │   │   │       │
│   │   │   │   │   │       ├─→ tree.FindRoute(routeCtx, methodGet, "/users/42")
│   │   │   │   │   │       │   │
│   │   │   │   │   │       │   ├─→ Walk segments: ["users", "42"]
│   │   │   │   │   │       │   │   ├─→ "users": found static edge → descend
│   │   │   │   │   │       │   │   └─→ "42": found param node "{id}" → capture value
│   │   │   │   │   │       │   │
│   │   │   │   │   │       │   ├─→ Check node.handlers[methodGet] → FOUND
│   │   │   │   │   │       │   │
│   │   │   │   │   │       │   └─→ Set routeCtx.URLParams = [{id, "42"}]
│   │   │   │   │   │       │       Set routeCtx.RouteHandler = handler
│   │   │   │   │   │       │       Return true
│   │   │   │   │   │       │
│   │   │   │   │   │       ├─→ If subroutes exist at matched node:
│   │   │   │   │   │       │   └─→ Strip prefix, recurse into subrouter
│   │   │   │   │   │       │
│   │   │   │   │   │       └─→ routeCtx.RouteHandler.ServeHTTP(w, r)
│   │   │   │   │   │           │
│   │   │   │   │   │           └─→ User handler executes
│   │   │   │   │   │               │
│   │   │   │   │   │               └─→ chi.URLParam(r, "id") → "42"
│   │   │   │   │   │
│   │   │   │   │   ├─→ [post]: log status, duration
│   │   │   │   │   └─→ return
│   │   │   │   │
│   │   │   │   └─→ return
│   │   │   │
│   │   │   ├─→ [post]: set X-Request-Id header
│   │   │   └─→ return
│   │   │
│   │   └─→ return
│   │
│   ├─→ If !FindRoute and methodNotAllowed → 405 handler
│   ├─→ If !FindRoute and !methodNotAllowed → 404 handler
│   │
│   └─→ routeCtx.Reset(); pool.Put(routeCtx)
```

### Route/Mount/Group/With Decision Tree

```
┌─────────────────────────────────────────────────────────────┐
│ Do you need a path prefix for this group of routes?        │
│                                                             │
│   YES ──────────────────────────────────────────────────┐   │
│   │                                                      │   │
│   ├─→ r.Route("/prefix", fn)                             │   │
│   │   Use when: routes share a prefix + middleware       │   │
│   │   Ex: r.Route("/api/v1", ...)                        │   │
│   │                                                      │   │
│   └─→ r.Mount("/prefix", otherRouter)                    │   │
│       Use when: independent router instance needed       │   │
│       Ex: r.Mount("/admin", admin.NewRouter())            │   │
│                                                          │   │
│   NO ───────────────────────────────────────────────────┐│   │
│   │                                                      ││   │
│   ├─→ r.Group(fn)                                        ││   │
│   │   Use when: middleware scoping only, no path change  ││   │
│   │   Ex: r.Group(func(r chi.Router) {                   ││   │
│   │           r.Use(authMiddleware)                       ││   │
│   │           r.Get("/profile", ...)                     ││   │
│   │        })                                           ││   │
│   │                                                      ││   │
│   └─→ r.With(mw1, mw2).Method(...)                       ││   │
│       Use when: adding middleware to specific routes     ││   │
│       Ex: r.With(auth).Get("/me", ...)                   ││   │
│                                                          ││   │
└──────────────────────────────────────────────────────────┘│   │
```

### URL Parameter Extraction Internals

```
chi.URLParam(r, "userID")
│
├─→ rctx := chi.RouteContext(r.Context())
│   │
│   ├─→ rctx == nil ?
│   │   └─→ YES → return "" (no route matched, e.g., 404 handler)
│   │
│   └─→ rctx != nil → iterate rctx.URLParams.Keys (backwards)
│       │
│       ├─→ Index 0: "orgID"  → "acme"    → no match
│       ├─→ Index 1: "userID" → "42"      → MATCH → return "42"
│       │
│       └─→ No match → return ""
│
└─→ Result: "42"
```

---

## Source Code Reading Guide

**Reading order (estimate: 3-4 hours for deep understanding):**

1. **`chi/mux.go:1-90`** — Mux struct definition. Understand the fields: `tree` (radix tree), `middlewares` (global middleware slice), `notFoundHandler`, `methodNotAllowedHandler`, `pool` (RouteContext pool).

2. **`chi/mux.go:100-180`** — `Route()`, `Group()`, `Mount()`, `With()`, `Handle()` methods. Read these together to understand how they all converge on `Mount()` and `Handle()`.

3. **`chi/mux.go:250-350`** — `ServeHTTP()` method. This is the request entry point. Trace: pool get → middleware compose → tree find route → handler serve → pool put.

4. **`chi/context.go:1-120`** — `RouteContext` struct, `RouteParams` struct, `NewRouteContext()`, `Reset()`, `URLParam()`, `URLParamFromCtx()`. Understand what data lives in the context and why it's pooled.

5. **`chi/tree.go:1-80`** — `node` struct. Understand `prefix`, `children`, `handlers`, `subroutes`, `typ` (node type constants: `ntStatic`, `ntParam`, `ntCatchAll`).

6. **`chi/tree.go:100-250`** — `InsertRoute()` method. Trace how a route pattern is split into segments and inserted into the tree.

7. **`chi/tree.go:300-450`** — `FindRoute()` method. Trace how a request path is matched against the tree. Pay attention to the methodNotAllowed flag.

8. **`chi/chain.go:1-60`** — `Chain` struct. Understand how `chain.Handler()` composes middlewares.

**What to skip on first read:**
- URL generation helpers (`RoutePattern()`, `RoutePath()`) unless you use named routes
- `chi.Walk()` unless you need route introspection
- The `middleware` directory (covered in Session 14)
- Test files (read separately if you want to understand test patterns)

---

## Production Failure Scenarios

### Scenario 1: r.Mount() vs r.Route() Confusion — Missing Middleware

**What happened:** A team migrated from `r.Route("/admin", ...)` to `r.Mount("/admin", adminRouter)` to split the codebase. Users reported that authentication middleware no longer fired for admin endpoints.

**Root cause:** `r.Use(authMiddleware)` was defined on the parent router BEFORE the original `r.Route()` call. With `r.Route()`, the subrouter inherits all parent middleware registered before it. With `r.Mount()`, the `adminRouter` is a completely separate `chi.NewRouter()` with its own middleware stack. The parent's `authMiddleware` was not added to `adminRouter`.

**The failure trace:**
```
Parent router:
  Middleware: [RequestID, Logger, AuthMiddleware]
  
With r.Route("/admin", ...):
  RequestID → Logger → AuthMiddleware → admin subrouter → handler ✓

With r.Mount("/admin", adminRouter):
  RequestID → Logger → AuthMiddleware → adminRouter.ServeHTTP(w, r) ← no auth!
  adminRouter has only: [] → handler
```

**Fix:** Explicitly add middleware to `adminRouter`:
```go
adminRouter := chi.NewRouter()
adminRouter.Use(authMiddleware)  // Must replicate parent middleware
adminRouter.Use(adminCheckMiddleware)
r.Mount("/admin", adminRouter)
```

Or use a shared middleware constructor that both routers call.

### Scenario 2: Catch-All Route Shadowing

**What happened:** A team added a `/*` catch-all for SPA client-side routing at the end of their route definitions. Suddenly, the health check endpoint `/health` returned HTML instead of JSON.

**Root cause:** The route registration order matters when catch-alls are involved. If the `/*` handler was registered on a Mount() that covered `/`, it overrode previously registered routes.

**How it happens in Chi's tree:**

```
Tree after correct registration:
  /health → healthHandler (GET)
  /*      → spaHandler (GET)

Tree after incorrect registration (Mount overwrites):
  /health → healthHandler (GET)    ← overridden by SPI handler
  /*      → spaHandler (GET)       ← matched first because parent router
                                     mounts subrouter at "/"
```

This happens specifically with `r.Mount("/", spaRouter)` where `spaRouter` registers `r.Get("/*", spaHandler)`. Since Mount() adds the subrouter AT the parent's root, the radix tree may merge nodes in an unexpected way.

**Fix:** Register specific routes on the parent router and the catch-all on a separate group or on the parent itself:

```go
// Correct approach:
r.Get("/health", healthHandler)
r.Get("/api/*", apiHandler)
r.Get("/*", spaHandler)  // not Mount; direct handle on parent

// Or use a middleware approach:
r.Group(func(r chi.Router) {
    r.Get("/api/*", apiHandler)
})
r.Get("/*", spaHandler)
```

### Scenario 3: URLParam Returns Empty String in Nested Mount

**What happened:** A service used nested `r.Mount()` calls. The inner handler called `chi.URLParam(r, "orgID")` and got an empty string, even though the URL path clearly contained the organization ID.

**The routing structure:**
```go
r.Mount("/organizations/{orgID}", orgRouter)  // orgRouter is separate Mux
// In orgRouter:
orgRouter.Mount("/users/{userID}", userRouter) // userRouter is separate Mux
// In userRouter:
userRouter.Get("/profile", profileHandler)
```

**Root cause:** With `r.Mount()`, prefix stripping removes the matched path segment including the parameter. The parent's `{orgID}` parameter is stored in the parent's `RouteContext`, which is a different instance than the child's `RouteContext`. When `userRouter` receives the request at `/profile`, its `RouteContext` has no `orgID` parameter.

**The fix:** Chi handles this correctly with `r.Route()` because nested `Route()` calls share the same Mux tree, and URL params from all nesting levels accumulate in a single `RouteContext.URLParams`. With `r.Mount()`, each Mux has its own `RouteContext`. To bridge the gap, extract params in parent middleware:

```go
func OrgIDMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        orgID := chi.URLParam(r, "orgID") // Extract before prefix is stripped
        ctx := context.WithValue(r.Context(), ctxKeyOrgID, orgID)
        next.ServeHTTP(w, r.WithContext(ctx))
    })
}

// In orgRouter setup:
orgRouter.Use(OrgIDMiddleware) // Must be registered on the ORG router, not parent
```

---

## Debugging Techniques

### Technique 1: Walking All Registered Routes

Chi provides `chi.Walk()` to introspect the route tree at runtime:

```go
func printRoutes(r chi.Router) {
    chi.Walk(r, func(method string, route string, handler http.Handler, middlewares ...func(http.Handler) http.Handler) error {
        fmt.Printf("[%s] %s → %d middleware(s)\n", method, route, len(middlewares))
        return nil
    })
}

// Usage:
r := chi.NewRouter()
// ... register routes ...
printRoutes(r)
// Output:
// [GET] /health → 0 middleware(s)
// [GET] /api/v1/users → 1 middleware(s)
// [GET] /api/v1/users/{userID} → 1 middleware(s)
// [POST] /api/v1/users → 1 middleware(s)
```

This reveals route registration order, which middleware is attached to which routes, and whether catch-alls are registered in the right place.

### Technique 2: Middleware Tracing

Add a debug middleware that prints the call stack:

```go
func TraceMiddleware(name string) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            // Print entry
            fmt.Printf("[TRACE] → %s (path=%s, method=%s)\n", name, r.URL.Path, r.Method)

            // Print URL params if any
            if rctx := chi.RouteContext(r.Context()); rctx != nil {
                for i, k := range rctx.URLParams.Keys {
                    fmt.Printf("[TRACE]   param: %s = %s\n", k, rctx.URLParams.Values[i])
                }
            }

            next.ServeHTTP(w, r)

            // Print exit
            fmt.Printf("[TRACE] ← %s\n", name)
        })
    }
}
```

Insert this at various levels to verify execution order:
```go
r.Use(TraceMiddleware("root"))
r.Route("/api", func(r chi.Router) {
    r.Use(TraceMiddleware("api"))
    r.Get("/users/{id}", handler)
})
```

### Technique 3: Isolating Route Registration Issues

When a route returns unexpected 404/405, temporarily register ONLY the problematic route and test:

```go
func TestIsolatedRoute(t *testing.T) {
    r := chi.NewRouter()
    r.Get("/users/{userID}/orders/{orderID}/items", handler)

    // Test the exact path
    req := httptest.NewRequest("GET", "/users/42/orders/99/items", nil)
    rec := httptest.NewRecorder()
    r.ServeHTTP(rec, req)

    assert.Equal(t, http.StatusOK, rec.Code)
}
```

This eliminates interference from other routes, middleware, or subrouters.

---

## Observability Considerations

### Logs

**What to log at the routing level:**
- The resolved route pattern (not just the raw path) — use a custom logger middleware that reads `chi.RouteContext(r.Context()).RoutePattern`
- Route parameters — useful for debugging: `{"route": "/users/{id}", "params": {"id": "42"}}`
- Matching duration — the time spent in `FindRoute()`. For most Chi trees, this is <100ns, but anomalously slow matches (>100μs) indicate tree degeneration
- Route pattern vs actual path mismatch — log when a route pattern differs significantly from the request path, which could indicate URL manipulation

**What NOT to log in the router layer:**
- Full request/response bodies — these belong in handler-level logging or request/response middleware
- Database query parameters extracted from URL — these leak sensitive data through logs

### Metrics

**Routing-specific metrics to emit:**
- `chi_route_not_found_total` — counter for 404s, tagged by path pattern (not raw path, to avoid cardinality explosion)
- `chi_route_method_not_allowed_total` — counter for 405s, tagged by method and path pattern
- `chi_route_lookup_duration_seconds` — histogram of `FindRoute()` execution time
- `chi_route_middleware_chain_depth` — gauge of how many middlewares wrap each handler (high depth = high overhead)
- `chi_routes_registered_total` — gauge at startup showing total route count

### Traces

**Spans at the routing layer:**
- One span per `ServeHTTP` call: `"chi.route"` — captures the full request routing decision
- Attributes on the span: `route.pattern` (the matched pattern), `route.params` (serialized as JSON), `route.method`
- For 404/405: add `error.type` attribute = `"route_not_found"` or `"method_not_allowed"`
- Middleware spans: each middleware should emit a span. Chi doesn't do this natively — add OpenTelemetry middleware

---

## Performance Implications

### Tree Depth and Lookup Time

Chi's radix tree lookup is `O(k)` where `k` is the number of path segments, not the number of registered routes. A route like `/a/b/c/d/e/f/g/h/i/j` has 10 segments → 10 node lookups, regardless of whether 10 or 10,000 routes are registered. However, deep trees with many branching nodes at each level can increase the number of children searched per node (binary search, O(log c) where c is child count).

**Performance concern:** Registering 10,000 unique dynamic patterns like `/resource/{id}` uses a single param node — cheap. But 10,000 static prefixes like `/resource-1/`, `/resource-2/`, ..., `/resource-10000/` creates 10,000 children at one level — binary search of 10,000 children = ~13 comparisons per lookup.

**Mitigation:** For truly thousands of dynamic prefixes, use a single param node `/{resourceName}` instead of separate static routes.

### Route Registration Overhead

Route registration is `O(n * m)` where `n` is the number of routes and `m` is the average segment depth. For 1000 routes, registration takes ~1ms and is only done at startup. The radix tree is NOT rebalanced after registration — the tree shape depends on insertion order. Registering routes in alphabetical order produces a more balanced tree than random or reverse order.

### RouteContext Pool Contention

The `sync.Pool` for `RouteContext` is per-P (per OS thread in Go's runtime), so there is zero contention under normal load. However, very high concurrency (>100k req/s per instance) with long-lived handlers (WebSocket, SSE) can exhaust the pool, causing new `RouteContext` allocations. This shows up in pprof heap profiles as `chi.NewRouteContext` allocations.

### Subrouter Overhead

Each `r.Mount()` creates a full `chi.Mux` instance with its own radix tree. For deeply nested Mount calls (5+ levels), the overhead of traversing multiple Mux instances and their ServeHTTP wrappers adds ~500ns per level. This is negligible for most applications but measurable in ultra-low-latency systems. For maximum performance, flatten the route tree.

---

## Architecture Implications

### Monolith Route Partitioning

In a modular monolith, each module typically exports a function like `func RegisterRoutes(r chi.Router)` that takes a subrouter and registers its routes. The main router is constructed in `cmd/server/main.go`:

```go
func main() {
    r := chi.NewRouter()
    r.Use(commonMiddleware...)

    r.Route("/api/v1", func(r chi.Router) {
        users.RegisterRoutes(r)    // mounts at /api/v1/users/...
        orders.RegisterRoutes(r)   // mounts at /api/v1/orders/...
        payments.RegisterRoutes(r) // mounts at /api/v1/payments/...
    })
}
```

Use `r.Route()` for modules that share a common prefix and middleware. Use `r.Mount()` only if a module needs a completely independent middleware stack (e.g., admin panel with different auth from user-facing API).

### Versioning Strategy

For API versioning, two patterns emerge:

**Header-based versioning** (no URL prefix): Use `r.Group()` to scope version-specific middleware, not `r.Route()`.

**URL-based versioning** (`/v1/`, `/v2/`): Use `r.Route("/v1", ...)` and `r.Route("/v2", ...)`. Each version block gets its own subrouter with version-specific middleware.

### Multi-Tenant Routing

For multi-tenant APIs where the tenant is in the path (`/tenants/{tenantID}/...`), prefer `r.Route("/tenants/{tenantID}", ...)` — Chi stores `tenantID` in URLParams and it's accessible by all nested handlers. Avoid `r.Mount()` for this because the param gets lost across subrouter boundaries.

---

## Team Ownership Implications

The router configuration is typically owned by the platform/infrastructure team because it defines cross-cutting concerns: logging, tracing, auth, rate limiting. Application teams own their handler implementations and register them on provided subrouters.

Define a clear contract: the platform team provides a `func ApplyStandardMiddleware(r chi.Router) chi.Router` helper and a `func NewRouter() chi.Router` factory. Application teams call `NewRouter()`, add their handlers, and return the router. The platform team mounts application routers in `main.go`.

This separation prevents application teams from accidentally removing security middleware or reordering the middleware chain. If an application team needs a route WITHOUT certain middleware (e.g., public health check), the platform provides `r.Group()` blocks with reduced middleware, and the application team registers handlers inside those blocks.

---

## Interview Questions

### Q1: What is the difference between chi.Route(path, fn), chi.Group(fn), and chi.Mount(path, handler)?

**Answer:** `Route()` creates a temporary subrouter with a path prefix — all routes registered inside `fn` are prefixed with `path`. `Group()` creates a temporary subrouter WITHOUT a path prefix — same path level as parent, used only for middleware scoping. `Mount()` attaches an entirely separate `chi.Mux` instance at `path` — the subrouter has its own middleware stack and radix tree independent of the parent. `Mount()` strips the prefix before forwarding to the subrouter; `Route()`'s subrouter shares URLParams with the parent.

### Q2: How does Chi determine whether to return 404 or 405?

**Answer:** During tree traversal in `FindRoute()`, if a matching node is found for the path but the node has no handler for the request's HTTP method, Chi sets `rctx.methodNotAllowed = true` and returns false. In `ServeHTTP()`, this flag determines whether to call the 405 handler (`r.MethodNotAllowed()`) or the 404 handler (`r.NotFound()`). If no node matches the path at all, the flag stays false → 404.

### Q3: What happens to URLParams when you use r.Mount() with nested subrouters?

**Answer:** Each `Mount()` creates a separate `chi.Mux` with its own `RouteContext`. URLParams from the parent are extracted into the parent's RouteContext but are NOT automatically available in the child's RouteContext. The child router receives a stripped URL path and starts fresh. To pass params across Mount boundaries, extract them in middleware on the child router (before the parent strips the prefix) or store them in the `context.Context` via `context.WithValue()`.

### Q4: How does chi.URLParam() find parameters when there are nested routes?

**Answer:** `chi.URLParam()` iterates `URLParams.Keys` **backwards** — from last to first element. Since nested `Route()` calls append child params AFTER parent params, backward iteration finds the most specific (innermost) parameter first. This prevents outer-route parameter shadowing: if both `/organizations/{id}` and `/users/{id}` exist, the inner `{id}` is found first and returned.

### Q5: What would happen if you register r.Get("/*", handler) and then r.Get("/health", healthHandler) on the same router?

**Answer:** Chi's radix tree algorithms prevent direct conflicts — `/*` catch-all is a terminal node that consumes all remaining paths. However, the specific behavior depends on registration ORDER. If `/*` is registered first, `/health` registration will succeed but `/health` will never match because `/*` consumes all paths (including `/health`). If `/health` is registered first, it takes priority for exact match at that segment, while `/*` catches everything else. The general rule: register specific routes before catch-all routes.

---

## Hands-On Exercises

### Exercise 1: Build a Versioned API Router

**Goal:** Create a router with `/v1` and `/v2` API versions, each with different middleware and handlers, using the appropriate Chi grouping mechanisms.

**Steps:**
1. Create a base router with RequestID and Logger middleware.
2. Add a `/v1` subrouter with v1-specific authentication (simple header check).
3. Register `/v1/users`, `/v1/orders` handlers.
4. Add a `/v2` subrouter with v2-specific authentication (JWT-based).
5. Register `/v2/users`, `/v2/orders`, `/v2/analytics` handlers.
6. Add a `/health` endpoint on the root router (no auth).
7. Use `chi.Walk()` to print all registered routes.
8. Test with `httptest`: verify that `/v1/users` fails with v2 auth, `/v2/users` fails with v1 auth, and `/health` works with no auth.

### Exercise 2: Debug a 405 vs 404 Confusion

**Goal:** Intentionally create a route configuration that produces 405 for some requests and 404 for others, then build a custom 405 handler that includes the `Allow` header.

**Steps:**
1. Register `r.Get("/resource/{id}", getHandler)` and `r.Post("/resource/{id}", createHandler)`.
2. Do NOT register a PUT handler for `/resource/{id}`.
3. Send a PUT request to `/resource/1` — verify you get 405.
4. Send a GET request to `/nonexistent` — verify you get 404.
5. Override `r.MethodNotAllowed()` with a custom handler that sets `Allow` header from the route tree.
6. Override `r.NotFound()` with a JSON error response.

### Exercise 3: Implement URL Generation from Named Routes

**Goal:** Register named routes and generate URLs programmatically.

**Steps:**
1. Register named routes: `r.Get("/users/{id}", handler).Name("user-detail")` and `r.Get("/users/{id}/orders/{orderId}", handler).Name("user-order")`.
2. Implement a middleware that stores the `chi.RouteContext` in the request context.
3. Write a helper `URLFor(r *http.Request, name string, params ...string) (string, error)` that generates a URL from a route name.
4. Use `chi.Walk()` to build a reverse-lookup map from route name to pattern.
5. Test URL generation with actual parameter values.

---

## Advanced Challenges

### Challenge 1: Build a Router Introspection Dashboard

**Goal:** Create an HTTP endpoint that returns the full route tree as structured JSON, including middleware depth, handler types, and route patterns.

Build a `GET /__routes` endpoint that:
1. Walks the entire route tree using `chi.Walk()`.
2. For each route, extracts the handler's type name using reflection (`reflect.TypeOf(handler).String()`).
3. Groups routes by path prefix hierarchy.
4. Counts middleware depth for each route.
5. Returns a JSON response with the full routing table.

**Principal-level aspect:** This requires understanding both Chi's introspection API and Go's reflection system. The challenge is handling `http.HandlerFunc` vs custom `http.Handler` implementations and computing the middleware chain depth for nested routers.

### Challenge 2: Implement a Zero-Allocation Route Matcher

**Goal:** Profile Chi's route matching allocation overhead and build a wrapper that eliminates allocations for hot-path routes.

1. Use `go test -bench=. -benchmem` to measure allocations per request.
2. Identify where allocations occur in `ServeHTTP()`: RouteContext pool get/put, URLParams slice reallocation, context.WithValue().
3. Implement a fast-path bypass that caches the resolved handler for frequently-hit routes (LRU cache keyed by `method + path`), skipping the radix tree walk entirely.
4. Handle cache invalidation: if a route is registered after the cache is built, the cache must be cleared.
5. Benchmark the fast-path vs normal path and show allocation reduction.

**Principal-level aspect:** This exercise forces you to understand the trade-offs between Chi's dynamic routing flexibility and raw performance. The solution must handle URL parameter extraction, 405 vs 404 differentiation, and subrouter traversal — all while eliminating allocations.

---

## Key Insights

- `r.Route()` creates a temporary subrouter with path prefix — use it for logical API groups sharing a URL prefix. `r.Group()` creates a temporary subrouter without prefix — use it ONLY for middleware scoping. `r.Mount()` attaches an independent router — use it when a subsystem needs its own middleware stack.

- Middleware inheritance follows a strict parent-first order: parent middleware always wraps subrouter middleware. You cannot interleave parent middleware between subrouter middleware layers — the tree structure is the middleware structure.

- `r.With()` is the most granular scoping mechanism: it creates a subrouter with injected middleware and immediately allows chaining `.Method()` on it. Use it for adding middleware to 1-3 specific routes without restructuring the tree.

- `chi.URLParam()` searches URLParams backwards because nested Route() calls append child params after parent params. Backward search finds the innermost definition first, preventing parameter shadowing.

- The radix tree's handler-per-method storage is what enables correct 405 responses. Each node stores a map of `methodTyp → http.Handler`. If a node matches the path but has no handler for the method, Chi returns 405 — not a heuristic, a structural guarantee.

- `RouteContext` is pooled per-request via `sync.Pool` to eliminate allocations. Each request gets a fresh context, which is reset and returned to the pool after the response. Never hold a reference to RouteContext across goroutine boundaries.

- When designing module interfaces, prefer `func RegisterRoutes(r chi.Router)` that takes a subrouter and registers routes on it. This pattern composes: the caller decides the path prefix via `r.Route("/prefix", module.RegisterRoutes)`.
