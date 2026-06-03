# Session 24: Chi & net/http Source Code Reading Mastery

- **Phase**: 5 — Source Code Reading Mastery
- **Duration**: 4-5 hours
- **Prerequisites**: Sessions 12 (net/http Server Internals), 13-17 (Chi Framework Core)
- **Goal**: Navigate Chi's ~5K lines and net/http's ~5K lines of core source code efficiently, build a mental model from reading source alone, and prepare to contribute to Chi or Go stdlib.

---

## Why This Topic Exists

Most engineers interact with frameworks through their public API. Staff/Principal engineers interact through source code. The difference is fundamental:

| Engineer Level | How They Know Chi |
|---------------|-------------------|
| Junior | "I use `chi.NewRouter()` and it works" |
| Senior | "I know Chi uses a radix tree, and middleware wraps handlers" |
| Staff | "I can trace a request from `net.Listen` through `conn.serve()` through Chi's `radixTree.find()` through the middleware chain, and I know exactly which lines of source execute in which order" |
| Principal | "I've read the entire Chi source, understand every design trade-off, can contribute bug fixes and features, and can reproduce the architecture from memory" |

Why source code reading matters for Go specifically:

1. **Go's stdlib IS the framework.** Unlike Spring where the framework is a separate artifact, Go's `net/http` is compiled into your binary. There is no magic — just code you can read.

2. **Chi is small enough to read in a day.** At ~3,000 lines of core code (excluding tests), Chi's entire router is smaller than Spring Boot's `DispatcherServlet` class. You can achieve total understanding.

3. **Source reading builds debugging instincts.** When a production issue occurs at 3 AM, you need to know what happens when `conn.serve()` encounters a panic, or what `radixTree.find()` returns for a route that doesn't exist — not from documentation, but from having traced the code yourself.

4. **Go's tooling makes source reading trivial.** `gopls` jump-to-definition, `go doc`, and `go tool compile` are purpose-built for navigating the standard library and module source.

5. **Contribution readiness.** Chi is actively maintained and accepts community PRs. Go's stdlib accepts proposals. Reading source first is table stakes for contributing.

The Java/Spring contrast: In Spring Boot, reading "the source" means navigating a dependency graph of 200+ jars, annotation processors, proxy factories, and bytecode manipulation. In Go/Chi, reading "the source" means reading ~6,000 lines of straightforward Go code. The barrier to total understanding is orders of magnitude lower.

---

## Mental Model

### The Source Reading Pyramid

```
               ┌─────────────┐
               │  Go Runtime  │  ← Level 4: Understand goroutine scheduling,
               │  proc.go,    │                GC, memory allocation (advanced)
               │  mgc.go      │                Read selectively, not completely
               ├─────────────┤
               │  net/http    │  ← Level 3: Understand Server.Serve, conn.serve,
               │  server.go,  │                Request/Response lifecycle
               │  request.go  │                ~3K lines of core code
               ├─────────────┤
               │  Chi Router  │  ← Level 2: Understand Mux, radix tree,
               │  mux.go,     │                middleware chain, context
               │  tree.go     │                ~3K lines of core code
               ├─────────────┤
               │  Chi Usage   │  ← Level 1: You're already here
               │  (Your App)  │                Use Chi.NewRouter(), r.Get(), etc.
               └─────────────┘
```

The pyramid is cumulative — each level depends on understanding the level below it. You cannot truly understand Chi's middleware chain until you understand `net/http.Handler`. You cannot understand `net/http`'s request handling until you understand goroutines.

### The Three-Phase Reading Strategy

**Phase 1: Reconnaissance (30 minutes)**
- Skim all files to understand structure
- Identify the "load-bearing" types (the structs everything depends on)
- Find the entry points (public functions)
- Build a file dependency map in your head

**Phase 2: Deep Dive (2-3 hours)**
- Read the critical files line by line
- Follow the execution path for a single request
- Annotate with your own comments (use godoc, not just reading)
- Answer specific questions: "What happens when no route matches?" "How does middleware wrapping work?"

**Phase 3: Reproduction (1-2 hours)**
- Close the source. Write the key structures from memory.
- Implement a minimal radix tree router
- Trace a request path without looking at the code
- Identify what you got wrong — those are your knowledge gaps

---

## Internal Architecture

### Chi Router Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                              chi.Mux                                 │
│                                                                      │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────────┐ │
│  │   radix tree │   │  middleware  │   │     subroutes            │ │
│  │   (tree.go)  │   │  chain       │   │     (children map)       │ │
│  ├──────────────┤   ├──────────────┤   ├──────────────────────────┤ │
│  │ node struct: │   │ []func(      │   │ map[string]*Mux          │ │
│  │ - prefix     │   │   http.      │   │                          │ │
│  │ - children   │   │   Handler)   │   │ Each subroute is a       │ │
│  │ - handlers   │   │   http.      │   │ fully independent Mux    │ │
│  │ - subroutes  │   │   Handler    │   │ with its own tree,       │ │
│  │ - routes     │   │              │   │ middleware, subroutes    │ │
│  └──────────────┘   └──────────────┘   └──────────────────────────┘ │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                   RouteContext (context.go)                   │   │
│  ├──────────────────────────────────────────────────────────────┤   │
│  │ Stored in context.Context                                     │   │
│  │ - URLParams (route parameters like {id})                     │   │
│  │ - RoutePath (matched route pattern)                          │   │
│  │ - RouteMethod (HTTP method)                                   │   │
│  │ - Routes (stack of matched routes for subrouter nesting)      │   │
│  │ - RoutePatterns (patterns at each nesting level)              │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

### net/http Server Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                         http.Server                                  │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Server.Serve(l net.Listener) error                          │   │
│  │  ┌────────────────────────────────────────────────────────┐  │   │
│  │  │  for {                                                 │  │   │
│  │  │      rw, err := l.Accept()   // Accept TCP connection   │  │   │
│  │  │      go c.serve(ctx)         // Spawn goroutine         │  │   │
│  │  │  }                                                     │  │   │
│  │  └────────────────────────────────────────────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  conn.serve(ctx context.Context)                             │   │
│  │  ┌────────────────────────────────────────────────────────┐  │   │
│  │  │  for {                                                 │  │   │
│  │  │      // Read HTTP request from connection              │  │   │
│  │  │      req, err := readRequest(c.reader)                 │  │   │
│  │  │      // Create ResponseWriter                          │  │   │
│  │  │      w, err := c.newResponseWriter(req)                │  │   │
│  │  │      // Call handler                                   │  │   │
│  │  │      serverHandler{c.server}.ServeHTTP(w, req)         │  │   │
│  │  │      // Close request body, check if Connection: close │  │   │
│  │  │      if req.closeAfterReply { break }                  │  │   │
│  │  │  }                                                     │  │   │
│  │  └────────────────────────────────────────────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

### Chi Radix Tree Structure

```
                    Root Node (prefix: "/")
                           │
                    ┌──────┼──────────────────────┐
                    │      │                      │
            "api/" node  "admin/" node      "health" node
                    │         │               (leaf, has handler)
            ┌───────┤    ┌────┼─────┐
            │       │    │    │      │
      "v1/" node  "v2/"  │  "login" │
            │       │    │   node    │
      ┌─────┼──┐    │    │           │
      │     │  │    │    │
  "users" │ "orders"  │
   node   │  node     │
          │           │
    ┌─────┼────┐      │
    │     │     │      │
  "{id}" "me" "./"   (trailing slash)

  Each node stores:
  - prefix: string               // The path segment
  - children: []*node             // Child nodes sorted by first byte
  - paramChild: *node            // Parameter child ({id})
  - handlers: methodTyp           // Handlers per HTTP method
  - subroutes: Routes             // *Mux for .Route() subrouters

  methodTyp is a map[methodTyp]http.Handler where methodTyp
  is uint16 corresponding to HTTP method + route pattern index
```

---

## Runtime Behavior

### Complete Request Lifecycle: From TCP Accept to Handler Execution

```
Time │ What Happens                                    │ Source Location
─────┼─────────────────────────────────────────────────┼─────────────────────
T+0  │ main() calls http.ListenAndServe(":8080", r)    │ Your main.go
T+1  │ net.Listen("tcp", ":8080") — creates listener   │ net/net.go (TCP level)
T+2  │ Server.Serve(l) enters accept loop              │ net/http/server.go:2930+
T+3  │ l.Accept() blocks, waiting for TCP connection   │ net/http/server.go:2939
     │                                                 │
─── Incoming Request ───────────────────────────────────────────────────────
     │                                                 │
T+4  │ l.Accept() returns *net.TCPConn                 │ net/http/server.go:2940
T+5  │ srv.newConn(rw) creates conn struct             │ net/http/server.go:2950+
T+6  │ c.setState(c.rwc, StateNew)                     │ net/http/server.go:300+
T+7  │ go c.serve(connCtx) — spawns goroutine          │ net/http/server.go:2957
     │                                                 │
     │ [Goroutine-per-connection model]                │
     │                                                 │
T+8  │ c.readRequest(ctx) parses HTTP request          │ net/http/server.go:946+
     │  - readLine() reads request line                │ net/http/request.go:1037+
     │  - parseRequestLine() splits method, URI, proto │ net/http/request.go:943+
     │  - readHeaders() reads MIME headers             │ net/http/header.go:34+
     │  - newRequestWithContext() wraps as *Request     │ net/http/request.go:939+
     │                                                 │
T+9  │ w = c.newResponseWriter(&w)                     │ net/http/server.go:1056+
     │                                                 │
T+10 │ serverHandler{c.server}.ServeHTTP(w, req)       │ net/http/server.go:2936
     │   └─ srv.Handler.ServeHTTP(w, req)              │ net/http/server.go:2920+
     │      └─ YOUR HANDLER (e.g., chi.Mux)            │
     │                                                 │
T+11 │ chi.Mux.ServeHTTP(w, req)                       │ chi/mux.go:104+
     │  ┌─ rctx = NewRouteContext()                    │ chi/context.go:49+
     │  ├─ h = mx.route(ctx, req.Method, routePath)    │ chi/mux.go:118+
     │  │  └─ mx.tree.FindRoute(rctx, method, path)    │ chi/tree.go:271+
     │  │     └─ node.findRoute(rcx, method, path)     │ chi/tree.go:284+
     │  │        └─ Radix tree walk (recursive)        │ chi/tree.go:284-380+
     │  ├─ If no route found:                         │
     │  │  └─ mx.methodNotAllowedHandler.ServeHTTP()    │ chi/mux.go:135+
     │  │     └─ or mx.notFoundHandler.ServeHTTP()      │ chi/mux.go:140+
     │  ├─ ctx = context.WithValue(ctx,                │
     │  │   RouteCtxKey, rctx) — store in context      │ chi/context.go:32+
     │  ├─ h = mx.chain.Handler(h) — wrap with middleware│
     │  │   └─ Chain.Handler(handler)                  │ chi/chain.go:32+
     │  │      └─ Walks middleware array backwards:    │
     │  │         wrapped := handler                   │
     │  │         for i := len(mws)-1; i >= 0; i-- {  │
     │  │           wrapped = mws[i](wrapped)          │
     │  │         }                                    │
     │  │         return wrapped                       │
     │  ├─ h.ServeHTTP(w, req)                         │ chi/mux.go:148+
     │  │  └─ Outermost middleware executes            │
     │  │     └─ Calls next.ServeHTTP(w, req)          │
     │  │        └─ Next middleware executes            │
     │  │           └─ ...                             │
     │  │              └─ Innermost handler executes   │ Your handler
     │  │                 (Your business logic)        │
     │  └─ // Response written, stack unwinds          │
     │                                                 │
T+12 │ conn.serve() writes response to TCP             │ net/http/server.go:1600+
T+13 │ Response flush: c.bufw.Flush()                  │ net/http/server.go:1620+
T+14 │ Check Connection: close or keep-alive            │ net/http/server.go:1640+
T+15 │ If keep-alive: loop back to readRequest          │ net/http/server.go:1060+
T+16 │ If close: goroutine exits, conn closes           │ net/http/server.go:1650+
```

### Key Timing Observations

1. **Goroutine spawn at T+7**: Each connection gets its own goroutine. This is why Go can handle 10K+ concurrent connections — goroutines are lightweight (2KB initial stack, growing as needed).

2. **Chi runs at T+11**: Chi's entire routing + middleware chain executes inside the goroutine spawned at T+7. If your handler blocks (e.g., DB query), that goroutine blocks — but other goroutines continue serving other connections.

3. **Middleware wrapping at T+11**: The wrapping happens on EVERY request (not cached). This is intentional — middleware can be dynamic. The cost is negligible (a few function closures).

4. **Radix tree walk at T+11**: O(path_length) lookup. Independent of number of routes. A 500-route API has the same lookup time as a 5-route API (for similarly structured paths).

---

## Request Flow Diagrams

### Flow 1: Standard GET Request (Route Found)

```
Client                     net/http                     Chi
  │                           │                          │
  │  GET /api/v1/users/42    │                          │
  │─────────────────────────>│                          │
  │                           │                          │
  │                     [TCP Accept]                     │
  │                     [Spawn goroutine]                │
  │                     [Parse request]                  │
  │                           │                          │
  │                     serverHandler                    │
  │                     .ServeHTTP(w, r)                 │
  │                           │                          │
  │                           │   ServeHTTP(w, r)        │
  │                           │─────────────────────────>│
  │                           │                          │
  │                           │                [Create RouteContext]
  │                           │                [Extract routePath]
  │                           │                          │
  │                           │                mx.tree.FindRoute(
  │                           │                  rctx,
  │                           │                  "GET",
  │                           │                  "/api/v1/users/42"
  │                           │                )
  │                           │                          │
  │                           │     ┌────────────────────┼
  │                           │     │ Radix tree walk:   │
  │                           │     │                    │
  │                           │     │ Root "/"           │
  │                           │     │  └─ "api/"         │
  │                           │     │      └─ "v1/"      │
  │                           │     │          └─ "users/"│
  │                           │     │              └─ "{id}" → MATCH!
  │                           │     │                    │
  │                           │     │ URLParams:         │
  │                           │     │   "id" = "42"      │
  │                           │     │                    │
  │                           │     │ Return handler     │
  │                           │     └────────────────────┼
  │                           │                          │
  │                           │  [Wrap with middleware]  │
  │                           │                          │
  │                           │     ┌───── middleware    │
  │                           │     │ chain (backwards)  │
  │                           │     │                    │
  │                           │     │ Recoverer          │ ← outermost
  │                           │     │   └─ Logger        │
  │                           │     │       └─ Timeout   │
  │                           │     │           └─ Your  │ ← innermost
  │                           │     │              Handler│
  │                           │     └────────────────────┼
  │                           │                          │
  │                           │          Recoverer       │
  │                           │          ┌───────────────┤
  │                           │          │ (defer/recover)
  │                           │          │                │
  │                           │          │ next.ServeHTTP │
  │                           │          │────────────────┼─┐
  │                           │          │                │ │ Logger
  │                           │          │                │ │ ┌────────────
  │                           │          │                │ │ │ log(start)
  │                           │          │                │ │ │ next.ServeHTTP
  │                           │          │                │ │ │────────────────
  │                           │          │                │ │ │                │
  │                           │          │                │ │ │          Timeout
  │                           │          │                │ │ │          ┌─────
  │                           │          │                │ │ │          │ ctx, cancel
  │                           │          │                │ │ │          │ next.ServeHTTP
  │                           │          │                │ │ │          │───────────
  │                           │          │                │ │ │          │          │
  │                           │          │                │ │ │          │  Your Handler
  │                           │          │                │ │ │          │  ┌────────
  │                           │          │                │ │ │          │  │ id := chi.
  │                           │          │                │ │ │          │  │   URLParam(
  │                           │          │                │ │ │          │  │   r, "id")
  │                           │          │                │ │ │          │  │ user :=
  │                           │          │                │ │ │          │  │   svc.Get(id)
  │                           │          │                │ │ │          │  │ json.NewEncoder
  │                           │          │                │ │ │          │  │ (w).Encode(u)
  │                           │          │                │ │ │          │  └────────
  │                           │          │                │ │ │          │          │
  │                           │          │                │ │ │          │◀─────────┘
  │                           │          │                │ │ │          │ cancel()
  │                           │          │                │ │ │          └─────
  │                           │          │                │ │ │                │
  │                           │          │                │ │ │◀───────────────┘
  │                           │          │                │ │ │ log(end, status, dur)
  │                           │          │                │ │ └────────────
  │                           │          │                │ │                │
  │                           │          │◀───────────────┼─┘                │
  │                           │          │ (recover never triggers —           │
  │                           │          │  handler didn't panic)              │
  │                           │          └───────────────┤                    │
  │                           │                          │                    │
  │                           │                          │◀───────────────────┘
  │                           │                          │
  │     HTTP 200 OK           │                          │
  │     {"id":42,...}         │                          │
  │<─────────────────────────│                          │
  │                           │                          │
```

### Flow 2: Route Not Found (404)

```
Client                net/http               Chi
  │                      │                    │
  │  GET /api/v1/XYZ     │                    │
  │─────────────────────>│                    │
  │                      │                    │
  │                [Accept, Parse]            │
  │                      │                    │
  │                ServeHTTP(w, r)            │
  │                      │                    │
  │                      │   ServeHTTP        │
  │                      │───────────────────>│
  │                      │                    │
  │                      │          tree.FindRoute(
  │                      │            rctx,
  │                      │            "GET",
  │                      │            "/api/v1/XYZ"
  │                      │          )
  │                      │                    │
  │                      │   ┌────────────────┤
  │                      │   │ Root "/"       │
  │                      │   │  └─ "api/"     │
  │                      │   │      └─ "v1/"  │
  │                      │   │          ├─ "users/"
  │                      │   │          │   └─ "{id}"
  │                      │   │          └─ "orders/"
  │                      │   │              └─ "{id}"
  │                      │   │                │
  │                      │   │ "XYZ" doesn't  │
  │                      │   │ match any child│
  │                      │   │ Return:        │
  │                      │   │  node == nil   │
  │                      │   │  handler == nil│
  │                      │   └────────────────┤
  │                      │                    │
  │                      │         if h == nil
  │                      │                    │
  │                      │         Check if method
  │                      │         not allowed
  │                      │         ├─ YES → 405
  │                      │         └─ NO  →
  │                      │                    │
  │                      │    mx.notFoundHandler
  │                      │    .ServeHTTP(w, r)
  │                      │                    │
  │  HTTP 404 Not Found   │                    │
  │<─────────────────────│                    │
  │                      │                    │
```

### Flow 3: Method Not Allowed (405)

```
Client                net/http               Chi
  │                      │                    │
  │  POST /api/v1/health │                    │
  │─────────────────────>│                    │
  │                      │                    │
  │                [Accept, Parse]            │
  │                      │                    │
  │                ServeHTTP(w, r)            │
  │                      │                    │
  │                      │   ServeHTTP        │
  │                      │───────────────────>│
  │                      │                    │
  │                      │   tree.FindRoute(
  │                      │     rctx,
  │                      │     "POST",          ← note: POST
  │                      │     "/api/v1/health"
  │                      │   )
  │                      │                    │
  │                      │   ┌────────────────┤
  │                      │   │ Root "/"       │
  │                      │   │  └─ "api/"     │
  │                      │   │      └─ "v1/"  │
  │                      │   │          └─ "health"
  │                      │   │   handlers[GET]   → handler exists
  │                      │   │   handlers[POST]  → nil
  │                      │   │                    │
  │                      │   │ Route MATCHES but │
  │                      │   │ method is wrong    │
  │                      │   └────────────────────┤
  │                      │                    │
  │                      │         node ≠ nil
  │                      │         but handler == nil
  │                      │         → method not allowed
  │                      │                    │
  │                      │   mx.methodNotAllowedHandler
  │                      │   .ServeHTTP(w, r)
  │                      │                    │
  │  HTTP 405 Method     │                    │
  │  Not Allowed         │                    │
  │  Allow: GET          │                    │
  │<─────────────────────│                    │
  │                      │                    │
```

---

## Lifecycle Diagrams

### Chi Router Lifecycle

```
  Application Startup
         │
         ▼
  ┌──────────────────────┐
  │ chi.NewRouter()      │ ← chi.go:29
  │ - Creates Mux struct │
  │ - Initializes tree   │
  │ - Sets default       │
  │   NotFound handler   │
  └──────┬───────────────┘
         │
         ▼
  ┌──────────────────────┐
  │ r.Use(middleware...) │ ← mux.go:211
  │ - Appends to         │
  │   mx.middlewares[]   │
  │ - These apply to ALL │
  │   routes on this mux │
  └──────┬───────────────┘
         │
         ▼
  ┌──────────────────────┐
  │ r.Get("/path", h)    │ ← mux.go:81
  │ r.Post("/path", h)   │ ← mux.go:86
  │ r.Put("/path", h)    │ ← mux.go:91
  │ ...                  │
  │ - Calls mx.handle()  │
  │ - Inserts into radix │
  │   tree               │
  └──────┬───────────────┘
         │
         ▼
  ┌──────────────────────┐
  │ r.Route("/prefix",   │ ← mux.go:132
  │   func(r chi.Router){│
  │     r.Get(...)       │
  │   })                 │
  │ - Creates child Mux  │
  │ - Mounts at /prefix  │
  │ - Route() executes   │
  │   callback with child│
  │ - Child routes are   │
  │   registered         │
  └──────┬───────────────┘
         │
         ▼
  ┌──────────────────────┐
  │ r.Mount("/prefix", h)│ ← mux.go:163
  │ - Mounts ANY http.   │
  │   Handler at prefix  │
  │ - Unlike Route(),    │
  │   Mount() doesn't    │
  │   create subrouter   │
  │ - Handler receives   │
  │   stripped path      │
  └──────┬───────────────┘
         │
         ▼
  ┌──────────────────────┐
  │ http.ListenAndServe( │ ← Your main.go
  │   ":8080", r)        │
  │ - Starts the server  │
  │ - Accept loop begins │
  │ - For each connection:│
  │   go c.serve(ctx)   │
  │   └─ r.ServeHTTP()   │
  └──────────────────────┘
```

### Radix Tree Node Lifecycle

```
  ┌──────────────────────┐
  │ Node Creation        │
  │ - Created during     │
  │   router.Insert()    │
  │ - prefix = segment   │
  │ - children = nil     │
  │ - handlers = nil     │
  └──────┬───────────────┘
         │
         ▼
  ┌──────────────────────┐
  │ Edge Insertion       │ ← tree.go:insert()
  │ - Find matching      │
  │   prefix in children │
  │ - If exact match:    │
  │   recurse into child │
  │ - If partial match:  │
  │   split the node     │
  │ - If no match:       │
  │   create new child   │
  └──────┬───────────────┘
         │
         ▼
  ┌──────────────────────┐
  │ Route Registration   │ ← node.insertRoute()
  │ - Set handler for    │
  │   HTTP method at     │
  │   subroute index     │
  │ - Store in            │
  │   handlers map        │
  └──────┬───────────────┘
         │
         ▼
  ┌──────────────────────┐
  │ Route Lookup         │ ← node.findRoute()
  │ - Match prefix       │
  │ - Walk children      │
  │ - Capture params     │
  │ - Return handler     │
  │ - O(path_length)     │
  └──────────────────────┘
```

### Middleware Chain Lifecycle

```
  ┌──────────────────────────────────────────────┐
  │ Middleware Registration (at startup)         │
  │                                              │
  │ mx.middlewares = []func(http.Handler)        │
  │   http.Handler                               │
  │                                              │
  │ Append:                                      │
  │   [Recoverer, Logger, Timeout, Auth, Handler]│
  └──────────────────────┬───────────────────────┘
                         │
                         ▼
  ┌──────────────────────────────────────────────┐
  │ Request Arrives (per request)                │
  │                                              │
  │ mx.chain.Handler(h)                          │
  │                                              │
  │ Walk backwards:                              │
  │  wrapped = handler (your business handler)   │
  │  i=3: wrapped = Timeout(wrapped)             │
  │  i=2: wrapped = Logger(wrapped)              │
  │  i=1: wrapped = Auth(wrapped)                │
  │  i=0: wrapped = Recoverer(wrapped)           │
  │                                              │
  │ Result:                                      │
  │  Recoverer(                                  │
  │    Auth(                                     │
  │      Logger(                                 │
  │        Timeout(handler)                      │
  │      )                                       │
  │    )                                         │
  │  )                                           │
  └──────────────────────┬───────────────────────┘
                         │
                         ▼
  ┌──────────────────────────────────────────────┐
  │ Execution Order (request arrives)            │
  │                                              │
  │ 1. Recoverer: defer/recover wraps everything │
  │    └─ next = Auth                            │
  │       └─ next = Logger                       │
  │          └─ next = Timeout                   │
  │             └─ next = YourHandler            │
  │                                              │
  │ Request flows INWARD: 1→2→3→4→Handler        │
  │                                                 │
  │ Response flows OUTWARD: Handler→4→3→2→1      │
  │                                              │
  │ Each middleware can:                         │
  │  - Run code before next.ServeHTTP (pre)      │
  │  - Run code after next.ServeHTTP (post)      │
  │  - NOT call next at all (short-circuit)      │
  │  - Modify the response (wrap ResponseWriter) │
  └──────────────────────────────────────────────┘
```

---

## Source Code Reading Guide

### Chi Source Code: Complete Reading Plan (~3,000 lines)

**Priority 1: Core Router (Read First — 30 minutes)**

| File | Lines | What It Does | Reading Time |
|------|-------|-------------|-------------|
| `chi.go` | ~150 | `NewRouter()`, `Mux` struct definition, `Use()`, `Handle()`, `Method()` | 10 min |
| `mux.go` | ~350 | `ServeHTTP()` — the main entry point, `Route()`, `Mount()`, handler registration | 20 min |

**Priority 2: Radix Tree (Read Second — 45 minutes, most important)**

| File | Lines | What It Does | Reading Time |
|------|-------|-------------|-------------|
| `tree.go` | ~600 | Radix tree: `node` struct, `insertRoute()`, `findRoute()`, edge splitting | 45 min |

This is the most important file in Chi. The radix tree is the data structure that makes route matching O(path_length) instead of O(num_routes). Every router worth using uses a radix tree. Understanding this file gives you transferable knowledge for Gin, Echo, and any future Go router.

**Priority 3: Middleware Chain (Read Third — 15 minutes)**

| File | Lines | What It Does | Reading Time |
|------|-------|-------------|-------------|
| `chain.go` | ~80 | `Chain` type, `Handler()`, middleware wrapping | 15 min |

**Priority 4: Context (Read Fourth — 15 minutes)**

| File | Lines | What It Does | Reading Time |
|------|-------|-------------|-------------|
| `context.go` | ~200 | `RouteContext`, `URLParam()`, `RoutePattern()`, context key management | 15 min |

**Priority 5: Built-in Middleware (Read for Pattern Recognition — 1 hour)**

| File | Lines | What It Does | Key Pattern |
|------|-------|-------------|-------------|
| `middleware/recoverer.go` | ~60 | Panic recovery middleware | The canonical middleware pattern |
| `middleware/logger.go` | ~150 | Request logging | ResponseWriter wrapping |
| `middleware/timeout.go` | ~60 | Request timeout via context | Context-based cancellation |
| `middleware/request_id.go` | ~40 | Request ID generation | Header injection |
| `middleware/realip.go` | ~40 | Real IP extraction | Proxy header parsing |
| `middleware/compress.go` | ~150 | Gzip response compression | ResponseWriter wrapping |

**Read Order Summary:**

```
1. chi.go        (10 min)  ← What is a Mux?
2. mux.go        (20 min)  ← How does ServeHTTP work?
3. tree.go       (45 min)  ← How does the radix tree work? ★ MOST IMPORTANT
4. chain.go      (15 min)  ← How does middleware wrapping work?
5. context.go    (15 min)  ← How are URL params stored?
6. recoverer.go  (10 min)  ← How to write middleware (the canonical example)
7. logger.go     (15 min)  ← How to wrap ResponseWriter
8. timeout.go    (10 min)  ← How to use context for cancellation
```

### net/http Source Code: Complete Reading Plan (~5,000 lines core)

**Priority 1: Interfaces and Types (Read First — 20 minutes)**

```go
// go/src/net/http/handler.go

// The Handler interface — the foundation of all HTTP handling in Go
type Handler interface {
    ServeHTTP(ResponseWriter, *Request)
}

// HandlerFunc — function adapter (like http.HandlerFunc)
type HandlerFunc func(ResponseWriter, *Request)

// ServeMux — the stdlib router (simpler than Chi's)
type ServeMux struct {
    mu    sync.RWMutex
    m     map[string]muxEntry
    es    []muxEntry
    hosts bool
}
```

| File | Lines | What to Read | Skip |
|------|-------|-------------|------|
| `handler.go` | ~240 | `Handler` interface, `HandlerFunc`, `ServeMux`, `StripPrefix`, `TimeoutHandler` | `FileSystem` types (for file serving) |

**Priority 2: Server Core (Read Second — 45 minutes, most important)**

| File | Lines | What to Read | Skip |
|------|-------|-------------|------|
| `server.go` | ~3300 | `Server` struct, `Serve()`, `conn.serve()`, `newConn()`, `readRequest()`, `ListenAndServe()` | TLS-specific code (if not using HTTPS) |

**Key sections within server.go:**

```
Section          │ Lines (approx) │ What It Does
─────────────────┼────────────────┼─────────────────────────────────
Server struct    │ ~29-80         │ Configuration fields
ListenAndServe   │ ~3160-3180     │ Convenience wrapper
Serve(l)         │ ~2930-3000     │ Main accept loop — READ THIS FIRST
newConn(rwc)     │ ~200-250       │ Connection wrapper creation
conn.serve(ctx)  │ ~940-1650      │ Per-connection request loop — READ THIS SECOND
readRequest(ctx) │ ~946-1069      │ HTTP request parsing
newResponseWriter│ ~1056-1075     │ Response wrapper creation
serverHandler    │ ~2916-2937     │ Adapter from Server to Handler
```

**Priority 3: Request & Response (Read Third — 30 minutes)**

| File | Lines | What to Read | Skip |
|------|-------|-------------|------|
| `request.go` | ~1400 | `Request` struct, `(*Request).Context()`, body reading, form parsing | Multipart form parsing, cookie jar |
| `response.go` | ~400 | `ResponseWriter` interface, `response` struct (private), `writeHeader()`, `write()` | Hijack, Flush internals |
| `transfer.go` | ~1000 | Chunked encoding, content-length handling | Transfer-encoding internals |

**Priority 4: Client Side (Read for Completeness — 20 minutes)**

| File | Lines | What to Read | Skip |
|------|-------|-------------|------|
| `client.go` | ~900 | `Client` struct, `Do()`, `Get()`, `Post()`, redirect handling | Cookie jar |

**Priority 5: Test Utilities (Read for Test Knowledge — 15 minutes)**

| File | Lines | What to Read |
|------|-------|-------------|
| `httptest/server.go` | ~350 | `NewServer()`, `NewTLSServer()` |
| `httptest/recorder.go` | ~150 | `ResponseRecorder` |

**Read Order Summary:**

```
1. handler.go         (20 min)  ← Handler interface, ServeMux
2. server.go:Serve()  (15 min)  ← The accept loop
3. server.go:conn.serve() (30 min) ← Per-connection lifecycle ★ MOST IMPORTANT
4. request.go:struct  (10 min)  ← Request fields and context
5. response.go        (20 min)  ← ResponseWriter interface
6. client.go          (20 min)  ← Client-side HTTP
7. httptest/          (15 min)  ← Test utilities
```

### Go Runtime Source Code: Selective Reading Plan

**Priority 1: Scheduler (For goroutine understanding)**

| File | Lines | What to Read | Why |
|------|-------|-------------|-----|
| `runtime2.go` | ~900 | `g` struct (goroutine), `m` struct (machine/thread), `p` struct (processor) | Understand what a goroutine IS |
| `proc.go` | ~6000 | `schedule()`, `findrunnable()`, `sysmon()`, `newproc()` | Understand scheduling |


**Priority 2: GC (For memory understanding)**

| File | Lines | What to Read | Why |
|------|-------|-------------|-----|
| `mgc.go` | ~1800 | GC phases, `gcStart()`, `gcMarkDone()`, GODEBUG flags | Understand GC lifecycle |

**Priority 3: Map (For data structure understanding)**

| File | Lines | What to Read | Why |
|------|-------|-------------|-----|
| `map.go` | ~1500 | Hash table implementation, `mapaccess`, `mapassign` | Maps are everywhere in Go |

**What to Skip in Runtime:**

```
Files to ignore initially:
├── os_*.go            ← Platform-specific (linux, darwin, windows)
├── asm_*.s            ← Assembly (garbage for reading)
├── *_test.go          ← Test files (2-5x the size of source)
├── syscall wrappers   ← Low-level syscall details
├── race detector code ← Unless you're debugging races
└── trace.go           ← Execution tracer (useful to read later, not now)
```

---

## Production Failure Scenarios

### Scenario 1: Route Not Found at 2 AM — But Route IS Registered

**Symptom:** A route that definitely exists returns 404 in production but works locally.

**Source-level diagnosis process:**

```
Step 1: Check if the handler was registered
  → Read chi/mux.go:Handle() — Does it panic on duplicate routes?
  → Answer: No. Chi silently overwrites. But maybe a second
    registration is causing issues with subrouters.

Step 2: Check subrouter mounting
  → Read chi/mux.go:Route() — How does Route() create a child Mux?
  → The child Mux gets its own tree. The parent's tree stores
    a reference to the subroute Mux. If the subroute callback
    doesn't register handlers, they're silently missing.

Step 3: Check radix tree behavior with trailing slashes
  → Read chi/tree.go:findRoute() — How does trailing slash handling work?
  → Chi has specific trailing slash behavior controlled by
    the route pattern. A route registered as "/users" won't
    match "/users/" unless the trailing slash handler exists.

Step 4: Check middleware short-circuiting
  → Read chi/mux.go:chain.Handler() — Could middleware be blocking?
  → If any middleware in the chain doesn't call next.ServeHTTP(),
    the handler never executes. This includes auth middleware,
    CORS middleware, and custom middleware.
```

### Scenario 2: Memory Leak Under Load

**Symptom:** Memory grows linearly with request count, never released.

**Source-level diagnosis (net/http side):**

```
Step 1: Check conn.serve() cleanup
  → Read net/http/server.go:conn.serve() — What happens after ServeHTTP?
  → The request body must be fully consumed before the next request
    can be read on the same connection. If your handler reads the body
    but doesn't io.Copy(ioutil.Discard, r.Body), the connection leaks.

Step 2: Check hijacked connections
  → Read net/http/server.go:conn.serve() — What about hijacked connections?
  → If a handler calls Hijack(), the conn.serve() goroutine exits.
    The handler is now responsible for the raw TCP connection.
    If the handler doesn't close it, TCP connections leak.

Step 3: Check ResponseWriter buffering
  → Read net/http/server.go:response — How is the response buffered?
  → The response has a bufio.Writer. If the handler panics after
    writing headers but before flushing, the buffer may not be flushed.
    Chi's Recoverer middleware should handle this, but if it's not
    at the outermost position, it might not catch the panic.
```

### Scenario 3: Goroutine Count Explosion

**Symptom:** `runtime.NumGoroutine()` grows without bound.

**Source-level diagnosis:**

```
Step 1: Check conn.serve() goroutine lifecycle
  → Read net/http/server.go:conn.serve() — When does the goroutine exit?
  → The goroutine exits when: conn is closed, or after Connection: close,
    or on error. If connections are kept alive forever, goroutines
    accumulate.

Step 2: Check if handlers are launching goroutines
  → Chi doesn't launch goroutines for you (unlike some frameworks).
  → If your handler does `go doWork()`, that goroutine lives until
    doWork() returns. If doWork() blocks forever, goroutines leak.

Step 3: Check context cancellation propagation
  → Read chi/middleware/timeout.go — How does Timeout middleware work?
  → It creates a context.WithTimeout. When the timeout fires, the
    context is cancelled. BUT: goroutines spawned by the handler
    must check ctx.Done() explicitly. Go doesn't kill goroutines
    on context cancellation.
```

---

## Debugging Techniques

### Source-Level Debugging Workflow

```
1. REPRODUCE THE ISSUE LOCALLY
   ├── Write a minimal test case using httptest.NewServer
   ├── Use httptest.NewRequest to craft the exact request
   └── Set breakpoints or add log.Printf at key source locations

2. ADD INSTRUMENTATION AT KEY POINTS
   ├── chi/mux.go:ServeHTTP — entry point
   ├── chi/tree.go:findRoute — route matching
   ├── chi/chain.go:Handler — middleware wrapping
   ├── net/http/server.go:conn.serve — request parsing
   └── Your handler

3. USE pprof ENDPOINTS FOR LIVE SERVICES
   ├── /debug/pprof/goroutine?debug=2 — full goroutine stack dump
   ├── /debug/pprof/heap — memory allocation profile
   └── /debug/pprof/trace?seconds=5 — execution trace

4. USE go tool trace FOR TEMPORAL ANALYSIS
   ├── curl http://localhost:6060/debug/pprof/trace?seconds=5 > trace.out
   └── go tool trace trace.out (opens browser-based visualization)
```

### Chi-Specific Debugging Commands

```bash
# Print all registered routes (Chi v5+)
# In your app, iterate r.Routes():
for _, route := range r.Routes() {
    fmt.Printf("%s %s -> %v\n", route.Method, route.Pattern, route.Handler)
}

# Debug middleware chain
go test -v -run TestRoutes ./handler/...

# Inspect radix tree structure
# Add debug logging to tree.go:insert() to print tree shape

# Check route context at runtime
func debugContext(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        rctx := chi.RouteContext(r.Context())
        log.Printf("Route: %s, Params: %v, Patterns: %v",
            rctx.RoutePath, rctx.URLParams, rctx.RoutePatterns)
        next.ServeHTTP(w, r)
    })
}
```

### net/http-Specific Debugging Commands

```bash
# Enable HTTP/2 debug logging
GODEBUG=http2debug=2 ./myapp

# Enable detailed GC logging
GODEBUG=gctrace=1 ./myapp

# Trace HTTP client requests
export GODEBUG=http2debug=1

# Check connection state
curl http://localhost:6060/debug/pprof/goroutine?debug=2 | grep "net/http"

# Profile the accept loop
go tool pprof -http=:8081 http://localhost:6060/debug/pprof/profile?seconds=30
# Look for net/http.(*conn).serve in the flame graph
```

### Source Navigation Drills

```
Drill 1: "Where is the Handler interface defined?"
  Expected: net/http/handler.go, line ~80
  How to find: grep "type Handler interface" or gopls jump-to-definition

Drill 2: "Given chi.NewRouter(), trace to the Mux struct definition"
  Expected: chi.go:NewRouter → chi.go:Mux struct (within same file)
  Time limit: 15 seconds

Drill 3: "A request to GET /api/v1/users/42 returns 404.
          Find the exact line where this happens."
  Expected: chi/mux.go line ~135-140 (notFoundHandler)
  Path: mux.go:ServeHTTP → tree.FindRoute → handler == nil → notFoundHandler

Drill 4: "Where does conn.serve() spawn a goroutine?"
  Expected: It doesn't. conn.serve() IS the goroutine spawned by Server.Serve().
  Trick question. Server.Serve() spawns `go c.serve()` at server.go:~2957

Drill 5: "Where does Chi store the URL parameter {id}?"
  Expected: context.go:RouteContext.URLParams, accessed via URLParam()
  Runtime: context.Context key RouteCtxKey

Drill 6: "What happens when you call next.ServeHTTP in middleware?"
  Expected: chain.go: The closure wraps the next handler. Calling
  next.ServeHTTP executes the wrapped inner handler.

Drill 7: "Where is the request body closed?"
  Expected: net/http/server.go:conn.serve() — after ServeHTTP returns,
  the body is consumed and closed at server.go:~1600

Drill 8: "How does Chi handle OPTIONS requests for CORS?"
  Expected: Chi itself doesn't handle CORS (it's a router, not a framework).
  CORS middleware must be added. chi/middleware/ has no CORS middleware.
  You need rs/cors or go-chi/cors as separate packages.
```

---

## Observability Considerations

### What to Instrument in Chi

```
Request Flow Stages            │ Metric to Collect
───────────────────────────────┼─────────────────────────────────
1. Connection accepted          │ http_connections_total
2. Request parsed               │ (covered by http_request_duration_seconds)
3. Route matching (Chi tree)    │ chi_route_lookup_duration_seconds
4. Middleware chain execution   │ chi_middleware_duration_seconds (per middleware)
5. Handler execution            │ http_request_duration_seconds (standard)
6. Connection closed            │ http_connections_closed_total
```

### Key Observability Gaps in Default Chi

1. **No built-in route lookup timing.** You must add a custom middleware or use OpenTelemetry auto-instrumentation.

2. **No built-in middleware chain profiling.** If a specific middleware is slow, you won't know unless you instrument each one.

3. **No goroutine leak detection.** Add `runtime.NumGoroutine()` to your health check or metrics endpoint.

4. **No connection pool metrics.** If you're making outbound HTTP calls via `http.Client`, add metrics around the transport's connection pool.

5. **No default request tracing.** Chi has no built-in OpenTelemetry support. Use `go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp` as middleware.

### Adding Observability to Chi

```go
// Custom observability middleware
func ObservabilityMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        routePattern := chi.RouteContext(r.Context()).RoutePattern()

        // Start timer
        start := time.Now()

        // Wrap ResponseWriter to capture status code
        ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)

        // Call next handler
        next.ServeHTTP(ww, r)

        // Record metrics
        duration := time.Since(start)
        status := ww.Status()

        // Emit to your metrics system (Prometheus, Datadog, etc.)
        httpRequestDuration.WithLabelValues(
            r.Method, routePattern, strconv.Itoa(status),
        ).Observe(duration.Seconds())
    })
}
```

### OpenTelemetry Integration Points

```
┌──────────────────────────────────────────────────────────────┐
│  otelhttp.NewHandler(chiRouter, "api-server")                │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Every incoming request gets a span                      │  │
│  │ Spans downstream calls automatically if using           │  │
│  │ otelhttp for outbound HTTP or otelsql for DB            │  │
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  Chi middleware for span enrichment                          │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Route pattern as span attribute                        │  │
│  │ URL params as span attributes (careful: PII!)          │  │
│  │ Request ID propagation                                 │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## Performance Implications

### Source-Level Performance Insights

**1. Radix tree lookup is O(path_length), not O(num_routes)**

From `tree.go:findRoute()`:
```go
// For each character in the remaining path:
//  - Binary search children by first byte
//  - Match common prefix
//  - Recurse or return

// This means:
//  - 1000 routes with different prefixes: fast
//  - 10 routes with the same long prefix: slightly slower (more recursion)
//  - Parameterized routes ({id}) cause fallthrough checks
```

**2. Middleware wrapping is per-request, not cached**

From `chain.go:Handler()`:
```go
// Every request rebuilds the middleware chain:
// for i := len(mws)-1; i >= 0; i-- {
//     handler = mws[i](handler)
// }
// This is O(num_middleware) per request. Negligible for <50 middleware.
```

**3. Get concurrency right from the source**

From `net/http/server.go:Serve()`:
```go
for {
    rw, err := l.Accept()
    // ...
    go c.serve(connCtx)  // One goroutine per connection
}
// This means: NO thread pool, NO connection pool, NO worker pool needed
// Go's concurrency model handles it natively.
```

**4. The ResponseWriter has a bufio.Writer**

From `net/http/server.go:response`:
```go
// Default buffer size: 4KB (bufio.NewWriterSize(chunkWriter, 4<<10))
// If your response is <4KB, it's fully buffered in memory.
// If >4KB, it's flushed in chunks.
// This matters for: large streaming responses, SSE, file downloads
```

### Performance Anti-Patterns Discovered from Source

| Anti-Pattern | Source Location | Fix |
|-------------|-----------------|-----|
| Not closing request body | server.go:conn.serve expects body consumed | `io.Copy(io.Discard, r.Body); r.Body.Close()` |
| ResponseWriter before Body written | response.go:WriteHeader must be first write | Call w.WriteHeader before w.Write |
| Write after WriteHeader | response.go:WriteHeader ignores duplicate calls | But logs show incorrect behavior; check handlers |
| Context without deadline | context.go:Background has no deadline | Always use context.WithTimeout for I/O |
| HTTP client without timeout | client.go:DefaultClient has no timeout | Always set Client.Timeout |
| Closing response body on client | client.go:Do expects body.Close() | Always `defer resp.Body.Close()` |

---

## Architecture Implications

### What Source Reading Reveals About Chi's Design

1. **Chi is NOT MVC.** There is no controller, no model, no view. Source reveals `Mux` is just a router with middleware. You must bring your own architecture. This is liberating compared to Spring MVC where the framework dictates `@Controller`, `@Service`, `@Repository` patterns.

2. **Chi is NOT a framework.** Source confirms: Chi provides routing + middleware composition + context. Anything else (DI, ORM, validation, serialization) you build or bring yourself. Contrast with Spring Boot where `@SpringBootApplication` bootstraps an entire world.

3. **Chi IS net/http.** Source confirms: `Mux.ServeHTTP` takes `(http.ResponseWriter, *http.Request)`. Every handler is an `http.Handler`. There is no `chi.Handler` type. This means any library that works with `net/http` works with Chi.

4. **Chi's middleware model is the Go standard pattern.** `func(http.Handler) http.Handler` is used by Chi AND stdlib's `StripPrefix`, `TimeoutHandler`. Gin and Echo use different middleware signatures. This matters for ecosystem interop.

5. **Chi's radix tree is intentionally simple.** No regex, no pattern matching, no conditional routes. Just prefix-based matching with parameters. This constraint forces clean URL design. Contrast with Spring's `@RequestMapping` which supports regex, conditions, headers, params, content types.

### Architecture Decisions Informed by Source

| Decision | Informed by Source | Why |
|----------|-------------------|-----|
| Use constructor injection | Chi has no DI | Go's way: pass dependencies explicitly |
| Thin handlers | chi/mux.go handle() is 5 lines | Handler should delegate to service, not contain logic |
| Repository interfaces | chi has no ORM | Define interfaces; implement with sqlc/pgx |
| Middleware for cross-cutting | chi/chain.go wrapping | Everything orthogonal to business logic goes in middleware |
| Context for request-scoped data | chi/context.go RouteCtxKey | Use Context for request-scoped, not global state |

---

## Team Ownership Implications

### Who Should Read Source Code (and When)

| Role | What to Read | When |
|------|-------------|------|
| All Go engineers | `handler.go`, `server.go:Serve()` | Onboarding Week 1 |
| Senior engineers | Full `mux.go`, `tree.go`, `chain.go` | Month 1-3 |
| Tech leads | Full Chi source, `server.go:conn.serve()` | Month 3-6 |
| Staff/Principal | Full Chi source, full net/http core, selective runtime | Ongoing |
| Platform engineers | Full Chi + net/http + middleware source | Before building internal platform |

### Organizational Benefits of Internal Source Knowledge

1. **Reduced framework vendor risk.** Chi is open source. If it were abandoned, your team could fork and maintain it — because they understand the source. Contrast: Abandoned Spring Boot = 2M+ lines of replacement code.

2. **Faster debugging.** When the error is "not found handler returned 404", a source-literate engineer traces to `mux.go:~140` in seconds rather than guessing.

3. **Better architecture decisions.** Understanding that Chi stores URL params in context (not function args, like Gin/Echo does) informs whether to adopt Chi vs alternatives.

4. **Contribution culture.** Engineers who read source are far more likely to contribute bug fixes upstream, improving the ecosystem for everyone.

5. **Interview signal.** Engineers who can discuss Chi's radix tree structure and middleware wrapping pattern demonstrate deep Go understanding beyond "I've used Chi for 2 years."

---

## Interview Questions (10 with Answers)

### Q1: Walk me through what happens when `chi.Mux.ServeHTTP(w, r)` is called.
**Answer:**
1. Creates a new `RouteContext` (or reuses from pool in recent versions)
2. Extracts the route path from the context or request URL
3. Calls `mx.tree.FindRoute(rctx, method, routePath)` which walks the radix tree
4. If `handler == nil`: checks `methodNotAllowed` and falls back to `notFoundHandler`
5. Stores the route context in `r.Context()` via `context.WithValue`
6. Wraps the route handler with the middleware chain: `mx.chain.Handler(handler)`
7. Calls `handler.ServeHTTP(w, r)` on the wrapped handler

### Q2: How does Chi's radix tree differ from a trie?
**Answer:**
A standard trie stores one character per node. A radix tree (Patricia trie) compresses single-child paths into a single node. In Chi's implementation (`tree.go`):
- Each `node` stores a `prefix` string (not single char)
- Children are sorted by first byte for binary search
- Edge splitting occurs when a new route partially matches an existing prefix
- This reduces tree depth from O(string_length) to O(unique_prefixes)
- Parameterized children (`{id}`) are stored in a separate `paramChild` field for priority fallback

### Q3: What is the difference between `r.Route()` and `r.Mount()` in Chi?
**Answer:**
- `r.Route("/prefix", fn)` creates a child `*Mux` with its own radix tree, middleware stack, and `notFound` handler. The callback receives a `chi.Router` and registers routes. The child's middleware does NOT inherit from the parent.
- `r.Mount("/prefix", handler)` mounts an arbitrary `http.Handler` at a prefix. The handler receives the path with the prefix stripped. No new router is created. Middleware is NOT applied.
- Key difference: `Route()` is for organizing Chi routes; `Mount()` is for integrating non-Chi handlers (e.g., a gRPC gateway, a static file server).

### Q4: How does Chi's middleware chain differ from Gin's middleware chain?
**Answer:**
- **Chi**: `func(http.Handler) http.Handler`. Wraps at request time by iterating backwards through the middleware slice. Each middleware is a closure that wraps the next handler. Standard Go pattern.
- **Gin**: `gin.HandlerFunc` — a custom type `func(*gin.Context)`. Gin's context is a framework-specific struct that carries request/response, errors, and a `Next()` method. Middleware calls `c.Next()` to advance the chain.
- **Implication**: Chi middleware works with ANY `http.Handler` (stdlib, third-party). Gin middleware only works with Gin handlers. Chi's approach is more composable but less feature-rich (no built-in error management, no validation helpers).

### Q5: What happens in `net/http` when a request pipe is broken (client disconnects)?
**Answer:**
In `server.go:conn.serve()`:
1. Go checks `r.Context().Err()` after each read/write
2. If the client disconnects, the TCP connection is closed
3. Subsequent reads from `r.Body` return `io.EOF` or a network error
4. Writes to the `ResponseWriter` may succeed (buffered) or fail (if flush attempts)
5. The `conn.serve()` goroutine exits via the error path
6. The request context is cancelled — any goroutines monitoring `r.Context().Done()` will receive the signal
7. **Critical:** handlers that spawn background goroutines without checking `r.Context().Done()` will continue running until they complete or timeout

### Q6: How would you diagnose a goroutine leak in a Chi service?
**Answer:**
1. `curl http://localhost:6060/debug/pprof/goroutine?debug=2 > goroutines.txt`
2. Look for goroutines with the same stack trace appearing many times (100+ identical stacks = leak)
3. Common Chi-related leaks:
   - `net/http.(*conn).serve` goroutines accumulating (connection leak)
   - `net/http.(*Transport).getConn` goroutines waiting for connections (client leak)
   - Custom goroutines launched in handlers without context cancellation
4. Check `created by` lines to find the origin
5. Use `runtime.NumGoroutine()` in a health check endpoint to track trends
6. Set up alerts: goroutine_count > baseline * 2 → investigate

### Q7: Where exactly does `net/http` parse the HTTP request line?
**Answer:**
In `request.go`:
- `readRequest(b *bufio.Reader)` at line ~1000 calls `ReadRequest(b)`
- `ReadRequest(b)` reads the first line with `readLine()`
- The request line "GET /path HTTP/1.1" is parsed at `parseRequestLine(line)` line ~1037
- `parseRequestLine` splits on spaces, validates method, parses URI, checks proto
- The URI is parsed further into `*url.URL` inside `newRequestWithContext()`

### Q8: Explain Chi's trailing slash handling from source.
**Answer:**
In `tree.go:findRoute()`:
- Routes registered WITH trailing slash (`/users/`) redirect requests WITHOUT trailing slash (`/users`) to the slashed version (301 redirect)
- Routes registered WITHOUT trailing slash (`/users`) redirect requests WITH trailing slash (`/users/`) to the unslashed version
- This is controlled by the route pattern — `//` at end of pattern enables strict slash matching
- The subroute handler is also checked: a subroute with a trailing slash handler will match `/users/` if the parent has a subroute at `/users/`
- Custom behavior: set `mux.NotFound` handler to avoid automatic redirects

### Q9: How does `net/http` handle request body size limits?
**Answer:**
By default, `net/http` DOES NOT limit request body size. The entire body can be read into memory.
- `Request.Body` is an `io.ReadCloser` wrapping a `LimitedReader` ONLY if `MaxBytesReader` is used
- `http.MaxBytesReader(w, r.Body, maxBytes)` wraps the body with a reader that returns an error when maxBytes is exceeded
- `Request.ParseForm()` has a hardcoded limit of 10MB (`maxFormSize`)
- `Request.ParseMultipartForm()` has a default limit of 32MB
- Chi recommendation: use a middleware that wraps `http.MaxBytesReader` before the body is read:
  ```go
  func LimitBodySize(limit int64) func(http.Handler) http.Handler {
      return func(next http.Handler) http.Handler {
          return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
              r.Body = http.MaxBytesReader(w, r.Body, limit)
              next.ServeHTTP(w, r)
          })
      }
  }
  ```

### Q10: What is the internal mechanism behind `http.TimeoutHandler` and how does it relate to Chi's Timeout middleware?
**Answer:**
`http.TimeoutHandler` in `handler.go`:
- Wraps a handler with a timeout
- Creates a context with deadline via `context.WithTimeout`
- Runs the handler in a SEPARATE goroutine
- Uses a timer channel to detect timeout
- On timeout: writes a 503 Service Unavailable response
- The original handler goroutine continues running if it doesn't check context

Chi's `middleware.Timeout` in `middleware/timeout.go`:
- Uses the SAME mechanism internally (`http.TimeoutHandler`)
- Wraps it in Chi's middleware signature
- Same caveat: spawned goroutines in the handler are NOT killed, only the HTTP response is cut off
- **Critical insight:** both rely on the handler checking `r.Context().Done()`. Neither kills goroutines. Go has no goroutine cancellation primitive — only cooperative cancellation via context.

---

## Hands-On Exercises

### Exercise 1: Build a Minimal Radix Tree Router (90 minutes)
**Goal:** Implement a working HTTP router using a radix tree from scratch.

**Steps:**
1. Define a `node` struct with `prefix`, `children`, `handler`
2. Implement `insert(path string, handler http.Handler)` — handle edge splitting
3. Implement `find(path string) http.Handler` — walk the tree
4. Test with 20+ routes of varying depth and structure
5. Add parameterized routes (`{id}`, `{slug}`)
6. Compare your implementation to Chi's `tree.go`

**Deliverable:** A working `router.go` file with tests.

### Exercise 2: Trace a Request with Source Annotations (60 minutes)
**Goal:** Build a complete execution trace with line numbers.

**Steps:**
1. Set up a minimal Chi server with one route
2. Using `go tool trace` or manual logging, trace a request from `main()` to your handler
3. Annotate every step with the exact file and line number in Chi and net/http source
4. Create a sequence diagram with source locations
5. Repeat for: 404 scenario, 405 scenario, middleware panic scenario

**Deliverable:** An annotated trace document or diagram.

### Exercise 3: Contribute a Documentation Fix to Chi (90 minutes)
**Goal:** Make your first open-source contribution to Chi.

**Steps:**
1. Read Chi's `CONTRIBUTING.md` (or check `.github/`)
2. Find a documentation gap: missing godoc comment, unclear example, typo
3. Fork Chi, create a branch, make the fix
4. Run `go test ./...` to ensure nothing breaks
5. Open a PR with a clear description
6. If documentation fix not possible, find a "good first issue" labeled issue

**Deliverable:** A link to your PR.

### Exercise 4: Read and Explain server.go:Serve() in Your Own Words (45 minutes)
**Goal:** Achieve deep comprehension of net/http's accept loop.

**Steps:**
1. Read `net/http/server.go:Serve()` (lines ~2930-3000) carefully
2. Write a 500-word explanation of what each block does
3. Answer: "What would happen if `go c.serve(connCtx)` were NOT in a goroutine?"
4. Answer: "How does Serve() handle errors from l.Accept()?"
5. Answer: "What is the purpose of the `doneChan` channel?"
6. Compare your answer with a colleague or rubber duck

**Deliverable:** Written explanation (500+ words).

### Exercise 5: Identify a Bug Through Source Reading (60 minutes)
**Goal:** Use source reading to diagnose a real or simulated bug.

**Steps:**
1. Instructor creates a buggy Chi service (common issues: missing request body close, middleware ordering, context timeout too short)
2. Without running the code, read the handler and trace execution through Chi source
3. Identify the bug and its root cause
4. Propose a fix with source justification (e.g., "This fails because middleware.X wraps the handler before middleware.Y, but Y depends on X's side effects")
5. Verify by running the code

**Deliverable:** Bug diagnosis document with source references.

---

## Advanced Challenges

### Challenge 1: Implement Route Prioritization (3-4 hours)
**Goal:** Extend Chi's radix tree to support route prioritization similar to Gin's.

**Background:** Chi matches routes in insertion order. Gin sorts routes by "specificity" (static routes before parameterized). Your challenge: modify Chi's tree.go to support this without breaking existing behavior.

**Constraints:**
- Existing routes must behave identically
- New registration: `r.GetWithPriority("/users/{id}", handler, priority)`
- Higher priority routes are tried first when multiple routes could match

**Evaluation:** Tests pass, code review with explanation of design choices.

### Challenge 2: Build a Production-Grade Middleware from Source Knowledge (2-3 hours)
**Goal:** Create a middleware that only reading source code makes possible.

**Middleware Idea:** "Graceful Degradation Middleware" — when the service is under load:
- Read `runtime.NumGoroutine()` and compare to a threshold
- If goroutine count exceeds threshold, return 503 for non-critical endpoints
- Critical endpoints continue to serve normally
- Leverage Chi's route context to identify critical vs non-critical routes

**Deliverable:** Middleware with tests, benchmark, and documentation.

### Challenge 3: Port a net/http Server to Use Raw TCP Connections (5-6 hours)
**Goal:** Understand the net/http abstraction by working below it.

**Steps:**
1. Implement a minimal HTTP server using `net.Listen` and raw TCP socket reads
2. Parse HTTP/1.1 requests manually (no net/http)
3. Support: request line parsing, header parsing, body reading
4. Support: response writing with status codes and headers
5. Implement a basic radix tree router on top
6. Serve a real HTTP/1.1 endpoint
7. Compare your implementation to net/http source

**Deliverable:** Working server, test file, comparison document.

---

## Key Insights

1. **Chi's total core source is ~3,000 lines.** You can read the entire thing in a day. A Spring engineer cannot say the same about Spring Boot (2M+ lines across 200+ jars). This is a strategic advantage of Go's philosophy.

2. **The radix tree is the core data structure in Chi AND Gin AND Echo.** Learn it once, understand all three routers. The implementations differ in details (Gin's supports regex in parameters, Chi's doesn't) but the fundamental algorithm is the same.

3. **Chi has no magic.** Every type, function, and interface is visible in the source. There are no code generators (unlike Gin's `binding` package), no reflection-based DI (unlike Spring), no bytecode manipulation. What you read is what runs.

4. **net/http's conn.serve() is the unsung hero.** It handles keep-alive, request parsing, response writing, and connection lifecycle — all in one function. Understanding this function is worth more than understanding any framework's equivalent code.

5. **Middleware wrapping backwards is intentional.** `chain.go:Handler()` wraps from innermost to outermost because Go closures capture by reference. Walking backwards ensures the outermost middleware is the first to execute.

6. **Go runtime source is the next frontier.** After Chi and net/http, the runtime source (proc.go, mgc.go, map.go) reveals why Go behaves the way it does. Goroutine scheduling, GC pauses, map iteration order — all explained in source.

7. **Source reading is a skill that compounds.** The first 1,000 lines of Go source you read are hard. The next 10,000 are easier. After 50,000 lines, you can read any Go codebase. This skill separates Staff/Principal engineers from Senior engineers.

8. **Chi's simplicity is its superpower.** Because Chi is small, you can achieve total understanding. Because you can achieve total understanding, you can debug anything, extend anything, and contribute with confidence. This is impossible with large frameworks.

9. **Contribution readiness is a responsibility.** If your company depends on Chi (or any open-source library), your team should have the capability to contribute bug fixes upstream. Reading source is the prerequisite for contribution.

10. **Java/Spring engineers spend years learning framework APIs. Go/Chi engineers spend days reading source.** The productivity difference is not about language syntax — it's about the size and complexity of the abstraction stack between your code and the runtime.

---

## Source Code Reading Checklist

```
□ Read chi.go (10 min) — Mux struct, NewRouter()
□ Read mux.go (20 min) — ServeHTTP, Handle, Route, Mount
□ Read tree.go (45 min) — Radix tree: insert, find, split
□ Read chain.go (15 min) — Chain.Handler, middleware wrapping
□ Read context.go (15 min) — RouteContext, URLParam
□ Read recoverer.go (10 min) — Canonical middleware pattern
□ Read logger.go (15 min) — ResponseWriter wrapping
□ Read timeout.go (10 min) — Context-based timeout
□ Read handler.go (net/http) (20 min) — Handler interface, ServeMux
□ Read server.go:Serve() (15 min) — Accept loop
□ Read server.go:conn.serve() (30 min) — Per-connection lifecycle ★
□ Read request.go struct (10 min) — Request fields
□ Read response.go (20 min) — ResponseWriter interface
□ Read client.go (20 min) — HTTP client
□ Read httptest/ (15 min) — Test utilities

□ Complete Exercise 1: Build a minimal radix tree router
□ Complete Exercise 2: Trace a request with source annotations
□ Complete Exercise 3: Contribute to Chi
□ Complete Exercise 4: Explain server.go:Serve() in your own words
□ Complete Exercise 5: Identify a bug through source reading
□ Complete Challenge 1, 2, or 3

□ Run: go doc net/http Server | less
□ Run: go doc net/http Handler | less
□ Run: go tool nm <your-binary> | grep chi  (see what's compiled in)
□ Run: go test -race ./... on Chi source
□ Run: go vet ./... on Chi source
```
