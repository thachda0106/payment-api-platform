# Session 12: net/http Server Internals: From ListenAndServe to ServeHTTP

## Why This Topic Exists

Every Go HTTP service, regardless of framework (Chi, Gin, Echo, Fiber), is built on top of `net/http`. Chi wraps `http.Handler`. Gin wraps `http.Handler`. Even Fiber, which claims to be "inspired by Express.js," ultimately calls `net/http` under the hood (or fasthttp, which is a different beast entirely). Understanding `net/http` at the source code level is the difference between treating the HTTP server as a black box and truly understanding why timeouts work (or don't), how connection pooling behaves, and what happens when a client disconnects mid-request.

The `net/http` package is approximately 8,000 lines of Go code in `server.go` alone, plus another 3,000+ in `transport.go` (client), `request.go`, `response.go`, and `h2_bundle.go` (HTTP/2). It has evolved significantly from Go 1.0 to Go 1.22+, most notably with the addition of the enhanced ServeMux in Go 1.22 that supports method-based routing and path parameters (making simple use cases viable without any third-party framework). Reading this code is a masterclass in Go systems programming: non-blocking I/O with goroutines, connection pooling, timeouts, and graceful shutdown.

This session is essential for staff and principal engineers because production incidents involving the HTTP server are inevitably subtle: slow clients tying up connections, timeouts that trigger at unexpected moments, connection leaks that accumulate over days, and HTTP/2 multiplexing issues that manifest only under specific client conditions. You cannot debug these from middleware alone—you need to understand what `conn.serve()` does, how `ServeMux` matches routes, and how the server's various timeouts interact with Chi's middleware stack.

## Mental Model

Think of `net/http.Server` as an orchestrator. It performs three distinct roles: (1) a TCP listener that accepts connections in a loop, (2) a connection manager that spawns a goroutine for each accepted connection and manages its lifecycle, and (3) a request dispatcher that parses HTTP requests from the connection, passes them to a `Handler`, and writes responses back. The key insight is that these three roles are concurrent: the listener loop runs in one goroutine, each connection runs in its own goroutine, and within a connection, requests can be pipelined (HTTP/1.1) or multiplexed (HTTP/2).

The `Handler` interface (`ServeHTTP(ResponseWriter, *Request)`) is the seam between the server infrastructure and your application code. Chi implements this interface with its `Mux` (which itself satisfies `http.Handler`). Every Chi middleware is an `http.Handler` wrapping an `http.Handler`. This is the decorator/composition pattern that makes Go HTTP middleware composable and testable.

```
net/http.Server Lifecycle:

┌─────────────────────────────────────────────────────────────────┐
│ main goroutine                                                  │
│                                                                 │
│  srv.ListenAndServe()                                           │
│    │                                                            │
│    ├── net.Listen("tcp", addr) → net.Listener                   │
│    │                                                            │
│    └── srv.Serve(ln)                                            │
│          │                                                      │
│          │  for {                                               │
│          │    rw, err := ln.Accept()   ← blocks for new conn    │
│          │    go srv.newConn(rw).serve() ← goroutine per conn   │
│          │  }                                                   │
│          │                                                      │
│          │    ┌─── conn goroutine ────────────────────┐        │
│          │    │                                       │        │
│          │    │  c.setState(c.rwc, StateNew)          │        │
│          │    │                                       │        │
│          │    │  for {                                │        │
│          │    │    w, err := c.readRequest(ctx)       │        │
│          │    │    // parse method, URL, headers      │        │
│          │    │    // read body (if any)              │        │
│          │    │                                       │        │
│          │    │    serverHandler{c.server}.ServeHTTP( │        │
│          │    │      w, w.req                         │        │
│          │    │    )                                  │        │
│          │    │    // Handler runs (Chi → your code)  │        │
│          │    │                                       │        │
│          │    │    w.finishRequest()                  │        │
│          │    │    // flush response, check keep-alive│        │
│          │    │                                       │        │
│          │    │    if !w.shouldReuseConnection() {    │        │
│          │    │      break                            │        │
│          │    │    }                                  │        │
│          │    │  }                                    │        │
│          │    │                                       │        │
│          │    │  c.close()                            │        │
│          │    │  c.setState(c.rwc, StateClosed)       │        │
│          │    └───────────────────────────────────────┘        │
│                                                                 │
│  srv.Shutdown(ctx)                                              │
│    │                                                            │
│    ├── close(ln)          ← stop accepting new connections      │
│    ├── for each conn: c.rwc.SetReadDeadline(time.Now())         │
│    │     ↑ triggers existing reads to unblock                   │
│    └── wait for conn count → 0 (or ctx deadline)                │
└─────────────────────────────────────────────────────────────────┘
```

## Internal Architecture

### The Server Struct

The `http.Server` struct is defined in `net/http/server.go` at ~line 2600 (Go 1.22). Its key fields:

```go
type Server struct {
    Addr              string
    Handler           Handler
    TLSConfig         *tls.Config
    ReadTimeout       time.Duration
    ReadHeaderTimeout time.Duration
    WriteTimeout      time.Duration
    IdleTimeout       time.Duration
    MaxHeaderBytes    int
    ConnState         func(net.Conn, ConnState)
    ErrorLog          *log.Logger
    BaseContext       func(net.Listener) context.Context
    ConnContext       func(ctx context.Context, c net.Conn) context.Context
    // ... private fields: mu, listeners, activeConn, inShutdown, etc.
}
```

Critical design details of each field:

**`Addr`**: The TCP address to listen on (`":8080"`, `"127.0.0.1:3000"`). If empty, `ListenAndServe` defaults to `":http"` which resolves to port 80 (on Unix, requires root). In a real deployment, always set this explicitly.

**`Handler`**: The root handler for all requests. If `nil`, `http.DefaultServeMux` is used (the global `http.Handle`/`http.HandleFunc` router). Chi sets this to its `Mux` instance. If a request matches no route, the handler receives it and can return 404.

**`TLSConfig`**: Configures TLS for `ListenAndServeTLS`. If nil, a default config is used. At minimum, you need certificates. For production, configure `MinVersion: tls.VersionTLS12` (minimum), `CurvePreferences: []tls.CurveID{tls.X25519, tls.CurveP256}`, and consider `GetCertificate` for dynamic cert loading (LetsEncrypt auto-renewal).

**`ReadTimeout`**: Maximum duration for reading the entire request, including the body. This is a connection-level timeout: from the moment the connection is accepted, the server has `ReadTimeout` to read the full request. If the request body is 100MB and uploaded over a slow connection, ReadTimeout must be long enough to accommodate it (or use `http.MaxBytesReader` to limit body size and set a shorter ReadTimeout). In Go 1.5 and earlier, this was the ONLY timeout. Go 1.6+ introduced `ReadHeaderTimeout` to address the slow-loris attack: a client that sends headers very slowly could hold a connection open for ReadTimeout seconds, tying up a goroutine.

**`ReadHeaderTimeout`**: Maximum duration for reading the request headers ONLY (not the body). After the headers are read, the body can take longer (up to `ReadTimeout`). This is the defense against slow-loris attacks. Set this to something short (5-10 seconds). If a client takes more than this to send headers, the connection is closed.

**`WriteTimeout`**: Maximum duration before timing out writes of the response. This starts at the end of the request header read (after reading headers, before writing the response). It covers writing the status line, headers, and body. If your handler takes 30 seconds to compute a response, you need a WriteTimeout > 30s, OR ensure the handler completes within WriteTimeout. In practice, WriteTimeout should be set long enough for the worst-case valid response, plus a buffer.

**`IdleTimeout`**: Maximum amount of time to wait for the next request on a keep-alive connection. After a response is fully written, the connection enters an idle state. If no new request arrives within IdleTimeout, the connection is closed. This prevents dead connections from accumulating (clients that disappeared without sending FIN). Set to something reasonable (60-120 seconds). Setting to 0 disables the timeout, which is dangerous in production.

**`MaxHeaderBytes`**: Maximum size of the request headers (including the request line). Defaults to 1MB (1 << 20) if not set. Large headers can be a DoS vector. In practice, 1MB is generous; consider 64KB (1 << 16) for most services.

**`BaseContext`**: A function that returns the base context for incoming requests. The returned context is the root of the context chain for each request. If nil, `context.Background()` is used. Useful for injecting server-wide context values (e.g., server version, instance ID). The `net.Listener` parameter receives the listener the request came in on (useful for multi-listener setups).

**`ConnContext`**: A function that modifies the context for each new connection. Called once per connection (before any request is read from it). Returns a new context that becomes the base for all requests on that connection. Useful for connection-level metadata (client IP, TLS version, connection ID).

### ListenAndServe: The Main Flow

`ListenAndServe()` (~line 3200) is a convenience method that calls `net.Listen` and then `Serve`:

```go
func (srv *Server) ListenAndServe() error {
    if srv.shuttingDown() {
        return ErrServerClosed
    }
    addr := srv.Addr
    if addr == "" {
        addr = ":http"
    }
    ln, err := net.Listen("tcp", addr)
    if err != nil {
        return err
    }
    return srv.Serve(ln)
}
```

`net.Listen` returns a `*net.TCPListener` which wraps the OS file descriptor for the listening socket. On Linux, this involves `socket()`, `bind()`, `listen()` system calls. The listener's `Accept()` method blocks until a new TCP connection arrives (kernel's accept queue), then returns a `*net.TCPConn`.

`Serve(ln)` (~line 3050) is the core loop:

```go
func (srv *Server) Serve(l net.Listener) error {
    // ... setup: track listener, setup base context, etc.
    var tempDelay time.Duration // for exponential backoff on Accept errors
    for {
        rw, err := l.Accept()
        if err != nil {
            if ne, ok := err.(net.Error); ok && ne.Temporary() {
                // Temporary error: backoff and retry
                if tempDelay == 0 { tempDelay = 5 * time.Millisecond }
                else { tempDelay *= 2 }
                if max := 1 * time.Second; tempDelay > max { tempDelay = max }
                srv.logf("http: Accept error: %v; retrying in %v", err, tempDelay)
                time.Sleep(tempDelay)
                continue
            }
            // Permanent error (listener closed): return
            return err
        }
        tempDelay = 0
        c := srv.newConn(rw)
        c.setState(c.rwc, StateNew)
        go c.serve(connCtx)
    }
}
```

The loop: accept a connection, create a `*conn` wrapper, set state to `StateNew` (fires `ConnState` callback if set), spawn a goroutine with `c.serve()`. The goroutine handles ALL requests on that connection (for HTTP/1.1 keep-alive, this is multiple requests; for HTTP/2, multiple streams). The listener goroutine immediately loops back to `Accept()` for the next connection.

**Exponential backoff on Accept errors**: If `Accept()` returns a temporary error (e.g., system out of file descriptors, `accept: too many open files`), the server backs off with increasing delays (5ms → 10ms → 20ms → ... → 1s max). This prevents a tight error loop that would consume CPU. The `Temporary()` check uses Go 1.5's `net.Error` interface; not all Accept errors are temporary (e.g., `net.ErrClosed` when the listener is closed during shutdown).

### conn.serve(): Connection Lifecycle

`conn.serve()` (~line 1800) is the most important function in the HTTP server. It runs in its own goroutine and manages the entire lifecycle of a TCP connection. The simplified flow:

```go
func (c *conn) serve(ctx context.Context) {
    c.remoteAddr = c.rwc.RemoteAddr().String()
    ctx = context.WithValue(ctx, LocalAddrContextKey, c.rwc.LocalAddr())

    defer func() {
        if err := recover(); err != nil && err != ErrAbortHandler {
            // Log panic, don't crash the server
            c.server.logf("http: panic serving %v: %v", c.remoteAddr, err)
        }
        c.close()
        c.setState(c.rwc, StateClosed)
    }()

    if tlsConn, ok := c.rwc.(*tls.Conn); ok {
        // TLS handshake
        if err := tlsConn.HandshakeContext(ctx); err != nil {
            return
        }
    }

    // HTTP/1.1 keep-alive loop
    for {
        w, err := c.readRequest(ctx)
        if err != nil {
            // Connection error, close it
            break
        }

        // Set read deadline based on server.ReadTimeout or server.ReadHeaderTimeout
        c.rwc.SetReadDeadline(time.Time{}) // clear any previous deadline

        // Create response writer
        w.conn = c

        // Call the handler
        serverHandler{c.server}.ServeHTTP(w, w.req)

        // Finish the request
        w.finishRequest()

        // Check if connection should be reused
        if !w.shouldReuseConnection() {
            break
        }

        // Reset request state for next request on this connection
        w.req = nil
        w.res = nil
    }
}
```

Key observations:

1. **Panic recovery**: The `defer recover()` catches panics in your handler. `ErrAbortHandler` is a sentinel panic value used by the server internally to abort a handler (e.g., when the client disconnects). User panics are logged but do NOT crash the server—only the connection goroutine dies.

2. **TLS handshake**: If the connection is TLS, `tlsConn.HandshakeContext(ctx)` performs the TLS handshake. This respects context cancellation (the handshake has a deadline). On failure (cert mismatch, protocol error), the connection is closed.

3. **Keep-alive loop**: For HTTP/1.1, after one request completes, the connection is NOT closed. The loop continues: read the next request, handle it, check reuse. This is what makes persistent connections work. HTTP/1.0 requires `Connection: keep-alive` header for persistence; HTTP/1.1 implies keep-alive unless `Connection: close` is set.

4. **`readRequest`**: This function parses the HTTP request line (`GET /path HTTP/1.1`), headers, and optionally the body. It returns a `*response` (the response writer) and a `*http.Request`. Both are created here and reused across keep-alive requests on the same connection.

5. **`finishRequest()`**: Flushes any buffered response data to the TCP connection, sets trailers (if any), and checks the `Connection` header to decide if the connection should be reused.

6. **`shouldReuseConnection()`**: Returns false if the client sent `Connection: close`, the server is shutting down, the response required the connection to close (e.g., `Transfer-Encoding: chunked` with chunk extension), or any error occurred during response writing.

### readRequest: Parsing HTTP

`readRequest()` (~line 1000) is where the actual HTTP parsing happens:

```go
func (c *conn) readRequest(ctx context.Context) (w *response, err error) {
    // Set read deadline for headers
    if d := c.server.ReadHeaderTimeout; d != 0 {
        c.rwc.SetReadDeadline(time.Now().Add(d))
    }
    if d := c.server.ReadTimeout; d != 0 {
        c.rwc.SetReadDeadline(time.Now().Add(d))
    }

    // Read request line: METHOD /path HTTP/1.1
    req, err := readRequest(c.bufr)
    // ... parse headers, determine body reader, create ResponseWriter
}
```

Key implementation details:

1. **Buffered reader**: `c.bufr` is a `*bufio.Reader` wrapping the raw TCP connection (`c.rwc`). It buffers reads at the byte level. HTTP parsing works on the buffered reader, not directly on the TCP connection. The buffer size defaults to 4KB. When the buffer is exhausted, it reads more from the TCP connection.

2. **Header read deadline**: `ReadHeaderTimeout` is applied as a read deadline on the raw connection BEFORE reading the request. If the client sends headers slowly, the read will time out after `ReadHeaderTimeout` and the connection is closed. This is the slow-loris defense.

3. **Body read deadline**: After headers are read, the deadline is reset based on `ReadTimeout`. The body reader (`req.Body`) is a `*http.body` that wraps a `io.LimitedReader` or the buffered reader, with the connection's read deadline applied for each read.

4. **`Transfer-Encoding: chunked`**: If the request uses chunked transfer encoding, `req.Body` is a `*chunkedReader` that decodes the chunked format on the fly. Each chunk starts with a hex size line, followed by the chunk data, followed by CRLF. The final chunk has size 0. The reader handles this transparently.

5. **`Content-Length`**: If present, `req.Body` is wrapped in an `io.LimitedReader` that stops after Content-Length bytes. Reading past the content length returns `io.EOF`. The server knows exactly when the body ends.

6. **No body**: GET, HEAD, DELETE, OPTIONS, TRACE requests have no body (per HTTP spec). For these methods, `req.Body` is set to `http.NoBody` (an `io.ReadCloser` that returns EOF immediately and does nothing on Close). POST, PUT, PATCH can have bodies.

### ResponseWriter: Writing Responses

`http.ResponseWriter` is the interface your handler uses to write responses:

```go
type ResponseWriter interface {
    Header() Header
    Write([]byte) (int, error)
    WriteHeader(statusCode int)
}
```

The concrete type is `*http.response` (unexported struct, defined at ~line 320). Key internals:

1. **`Header()`**: Returns the response headers map. Headers can be set at any time before `WriteHeader()` or the first `Write()`. After the first `Write()` or explicit `WriteHeader()`, headers are sent over the wire and changing them has no effect. The header map is a `http.Header` (which is `map[string][]string`), pre-allocated with `Date` (set automatically by the server) and optionally `Content-Type` (sniffed from the first `Write` if not set).

2. **`WriteHeader(statusCode int)`**: Sends the HTTP status line over the wire (`HTTP/1.1 200 OK\r\n`) followed by headers. Can only be called once. If not called explicitly, the first `Write()` implicitly calls `WriteHeader(http.StatusOK)`. The status code is stored in the `*response` struct for logging.

3. **`Write([]byte)`**: Writes data to the response body. If `WriteHeader` hasn't been called yet, it calls `WriteHeader(200)` first. Each `Write` call results in one or more TCP writes. For small responses (< ~4KB), the data may be buffered and written in a single TCP segment. For large responses, multiple TCP writes happen.

4. **Buffered writes**: The default `*response` writes directly to the `*bufio.Writer` wrapping the TCP connection. This means response data is buffered before being sent. The buffer flushes automatically when full (4KB default) or when `finishRequest()` is called. For streaming (SSE, long-polling), call `w.(http.Flusher).Flush()` to send buffered data immediately.

5. **`http.Flusher`**: An optional interface that `*response` implements. Calling `Flush()` sends any buffered response data to the client immediately. Essential for Server-Sent Events (SSE), chunked streaming, and long-polling. Chi's `middleware.Compress` may not be compatible with `Flush()` (compression requires knowing the full response).

6. **`http.Hijacker`**: Another optional interface. Allows taking over the TCP connection from the HTTP server (for WebSocket upgrades, HTTP CONNECT tunneling). After hijacking, the server stops processing requests on that connection. Chi does not implement WebSocket natively, but `gorilla/websocket` uses `Hijacker` to upgrade connections.

### ServeMux: The Standard Library Router

`http.ServeMux` (~line 2300) is Go's built-in HTTP request multiplexer. Chi replaces ServeMux with its own trie-based router, but understanding ServeMux reveals what Chi improves upon.

**Go 1.21 and earlier**: ServeMux used simple longest-prefix matching. A route `/foo/` matched any path starting with `/foo/`. A route `/foo` matched exactly `/foo` (or `/foo` with a redirect). There was no method-based routing, no path parameters. This was insufficient for REST APIs, which is why frameworks like Chi exist.

**Go 1.22+**: ServeMux was significantly enhanced with pattern-based routing:

```go
mux := http.NewServeMux()
mux.HandleFunc("GET /items/{id}", handleGetItem)
mux.HandleFunc("POST /items", handleCreateItem)
mux.HandleFunc("GET /items/{id}/comments/{commentID}", handleGetComment)
```

Path parameters are available via `r.PathValue("id")`. Method matching is part of the pattern. The pattern syntax supports `{name}` for single-segment wildcards, `{name...}` for multi-segment wildcards, and `{$}` for exact path termination (prevents `/foo/` from matching `/foobar`).

**Why Chi still matters with Go 1.22+**: Chi provides middleware (`Use`, `With`, `Group`), route grouping, sub-routers, and a more expressive API for complex routing. Go 1.22's ServeMux handles basic REST routing but doesn't have middleware, groups, or route-specific middleware. For a simple service with 5 endpoints, `http.NewServeMux` (Go 1.22+) is sufficient. For a complex API with 50+ routes, versioned endpoints, and per-route middleware, Chi is still the better choice.

### HTTP/2: h2c and h2

HTTP/2 support is provided by `golang.org/x/net/http2/h2c` (cleartext) and Go's built-in `http2` package (TLS, bundled via `h2_bundle.go`). When a client connects with TLS and negotiates HTTP/2 via ALPN (Application-Layer Protocol Negotiation), the server automatically uses HTTP/2. No additional configuration is needed—just configure TLS.

**HTTP/2 properties that matter to Chi**:

1. **Multiplexed streams**: Multiple HTTP requests can be in-flight on a single TCP connection simultaneously. This means one conn goroutine handles multiple requests concurrently (via goroutines for each stream). Chi's middleware stack is called for each stream independently, just like HTTP/1.1.

2. **Server push**: The server can push resources to the client before the client requests them. Rarely used in practice (browser support is being removed). Not relevant to Chi middleware.

3. **Flow control**: HTTP/2 has per-stream and per-connection flow control windows. If the client doesn't read responses fast enough, the server's flow control window fills up, and writes block. This can look like a slow handler but is actually a slow client. Chi cannot help here—it's a transport concern.

4. **Header compression (HPACK)**: HTTP/2 headers are compressed with HPACK, which maintains a dynamic table across streams. This is transparent to Chi—headers are decompressed before Chi sees them.

5. **`h2c` (cleartext HTTP/2)**: For non-TLS connections, the `http2/h2c` package provides HTTP/2 upgrade via the `Upgrade: h2c` header. You wrap your Chi handler with `h2c.NewHandler(chiMux, &http2.Server{})` to support cleartext HTTP/2. This is useful for internal services (Kubernetes sidecars, gRPC without TLS).

## Runtime Behavior

### Connection Acceptance and Goroutine Lifecycle

When `ListenAndServe` is called:

1. **OS-level**: `net.Listen("tcp", ":8080")` calls `socket(AF_INET, SOCK_STREAM, 0)` → `bind(fd, :8080)` → `listen(fd, backlog)`. The `backlog` is the maximum number of pending connections in the kernel's accept queue. On Linux, this is `somaxconn` (default 4096, adjustable via `/proc/sys/net/core/somaxconn`). On macOS, the default is 128. Go's `net.Listen` attempts to set backlog to `maxListenerBacklog()` (which is `net.core.somaxconn` on Linux or a high value like 1<<16-1).

2. **Go runtime**: The listener goroutine calls `ln.Accept()`, which is a blocking system call. The Go runtime parks the goroutine (puts it to sleep) and uses the OS thread for other goroutines. When a new connection arrives, the kernel wakes the goroutine.

3. **Connection accepted**: `Accept()` returns a `*net.TCPConn`. A new `*conn` is created wrapping the TCP connection. The connection's state is set to `StateNew` (fires `ConnState` callback). A new goroutine is launched with `go c.serve(ctx)`.

4. **Goroutine count**: Each active connection occupies one goroutine. At 10,000 concurrent connections (keep-alive, idle), there are 10,000 goroutines. Go's goroutine scheduler handles this efficiently (goroutines are ~2KB stack minimum, growing as needed), but each goroutine still consumes memory. At 10,000 goroutines with 8KB average stack, that's ~80MB. Plus connection buffers (4KB read + 4KB write per connection), that's another ~80MB. Total: ~160MB for 10,000 idle connections.

5. **Goroutine lifecycle**: The goroutine lives until the connection is closed. For keep-alive connections that are idle, the goroutine is blocked on `c.bufr.Read()` (waiting for the next request's first bytes). The `IdleTimeout` ensures these goroutines don't live forever—if no request arrives within `IdleTimeout`, `c.rwc.SetReadDeadline(time.Now().Add(c.server.IdleTimeout))` triggers a read timeout, the goroutine wakes up, sees the error, and closes the connection.

### Request Parsing Details

When a request arrives on a keep-alive connection:

1. **First bytes arrive**: The kernel delivers data to the TCP receive buffer. Go's runtime netpoller detects the socket is readable and wakes the connection goroutine.

2. **Reading the request line**: `readRequest()` calls `readRequest(c.bufr)`, which calls `c.bufr.ReadSlice('\n')` to read the first line. This reads from the `bufio.Reader` buffer, which reads from the TCP connection as needed. The line is parsed as `METHOD SPACE URI SPACE PROTO\r\n`.

3. **Header parsing**: After the request line, headers are read line by line (`Key: Value\r\n`). Each line is parsed into the `http.Header` map. Multi-line headers (obsolete, RFC 7230 deprecated) are supported for backward compatibility. The header section ends with a blank line (`\r\n\r\n`).

4. **Body determination**: After headers, the server determines how to read the body based on `Transfer-Encoding` and `Content-Length`:
   - If `Transfer-Encoding: chunked`, create a `*chunkedReader`.
   - If `Content-Length: N`, wrap the reader with `io.LimitedReader{R: connReader, N: N}`.
   - If neither (GET, HEAD), `req.Body = http.NoBody`.
   - If both (invalid per spec), return error "400 Bad Request".

5. **Creating the Request**: A `*http.Request` is constructed with the parsed method, URL, headers, body reader, and context. The URL is parsed with `url.Parse()`. The context is derived from the server's base context with the connection's context values.

6. **Handler dispatch**: `serverHandler{c.server}.ServeHTTP(w, w.req)` calls the configured `Handler`. If it's a Chi `Mux`, the `Mux.ServeHTTP` runs the middleware stack + route handler.

### Response Writing Details

When your handler writes a response:

1. **Headers**: Your handler calls `w.Header().Set("Content-Type", "application/json")`. This modifies the `*response`'s internal `Header` map. No data is sent yet.

2. **Status code**: Your handler calls `w.WriteHeader(http.StatusCreated)`. This triggers `*response.WriteHeader()`:
   - Sends the status line: `HTTP/1.1 201 Created\r\n`.
   - Iterates over the `Header` map, sends each as `Key: Value\r\n`.
   - Adds the `Date` header (current time in RFC 1123 format).
   - Sends a blank line: `\r\n` (end of headers).
   - Sets `w.wroteHeader = true` (prevents duplicate writes).

3. **Body**: Your handler calls `w.Write(data)`. If `WriteHeader` hasn't been called, it's implicitly called with 200. The data is written to the buffered writer (`c.bufw`). If the response needs chunked transfer encoding (because `Content-Length` was not set), the writer wraps the data in chunked format before writing.

4. **Flushing**: The buffered writer accumulates data until the buffer is full (4KB) or `finishRequest()` is called. If your handler calls `w.(http.Flusher).Flush()`, the buffered data is sent immediately to the TCP connection (write syscall). For SSE, you flush after each event.

5. **finishRequest()**: After the handler returns, `conn.serve()` calls `w.finishRequest()`:
   - If the response used chunked encoding, writes the final `0\r\n\r\n` chunk.
   - Sends trailers if set (HTTP/1.1 chunked trailers).
   - Flushes the buffered writer completely.
   - Checks `Connection` header to decide keep-alive.
   - Logs the request (if `Server.ErrorLog` is set, though middleware logging is preferred).

### Graceful Shutdown

`Server.Shutdown(ctx)` (~line 3300) provides graceful shutdown:

```go
func (srv *Server) Shutdown(ctx context.Context) error {
    // 1. Set inShutdown flag (prevents new connections from being tracked)
    srv.inShutdown.Store(true)
    
    // 2. Close all listeners (stop Accept loop)
    for _, l := range srv.listeners {
        l.Close()
    }
    
    // 3. Close idle connections
    for _, c := range srv.activeConn {
        c.rwc.SetReadDeadline(time.Now())
    }
    
    // 4. Wait for active connections to finish (or ctx deadline)
    ticker := time.NewTicker(100 * time.Millisecond)
    defer ticker.Stop()
    for {
        if srv.activeConn == 0 {
            return nil
        }
        select {
        case <-ticker.C:
        case <-ctx.Done():
            return ctx.Err()
        }
    }
}
```

Key details:
- `Shutdown` does NOT kill in-flight requests. It waits for them to finish (or the context deadline).
- Idle connections (keep-alive, waiting for next request) are closed immediately (by setting a read deadline to `time.Now()`, which triggers an immediate timeout on the next read attempt).
- Active connections (handling a request) continue until the handler returns.
- If a handler takes longer than the shutdown context's deadline, `Shutdown` returns `ctx.Err()`. The connections remain open. The process should exit after `Shutdown` returns (or use a supervisor that kills the process).
- `Close()` is the "hard stop" alternative: it immediately closes all connections without waiting for handlers to finish.

### HTTP/1.1 Persistent Connections (Keep-Alive)

In HTTP/1.1, a single TCP connection can carry multiple requests sequentially:

1. **After first request completes**: The server checks `shouldReuseConnection()`. If true, the connection stays open.
2. **Server resets read deadline**: Sets `IdleTimeout` as the read deadline. If no data arrives within `IdleTimeout`, the read times out and the connection is closed.
3. **Client sends next request**: The read call returns with the new request data. The deadline is reset based on `ReadHeaderTimeout`/`ReadTimeout`.
4. **Pipelining**: HTTP/1.1 allows pipelining (sending the second request before the first response is received). Go's HTTP client supports this in theory but rarely uses it. Go's HTTP server supports it: after the first response is written, the server checks if there's already another request buffered and processes it immediately without waiting for `IdleTimeout`. Pipelining is practically unused because of head-of-line blocking (a slow response blocks all subsequent responses).
5. **Max requests per connection**: The server can limit the number of requests per connection to prevent resource hogging. `Server.MaxConnsPerHost` (client-side, in transport) limits connections per host. The server doesn't have a built-in `MaxRequestsPerConnection`, but you can implement it by counting requests in `ConnState` and closing the connection after N requests.

## Flow Diagrams

```
HTTP/1.1 Request-Response Flow on a Keep-Alive Connection:

  Client                        Server (goroutine)                  Handler (Chi)
    |                                |                                   |
    | ──── TCP SYN ────────────────>|                                   |
    | <──── TCP SYN+ACK ─────────────|                                   |
    | ──── TCP ACK ────────────────>|                                   |
    |                                |                                   |
    |  TCP connection ESTABLISHED    |                                   |
    |                                |                                   |
    | ──── GET /users HTTP/1.1 ────>| (blocked on Read)                 |
    |     Host: api.example.com      |                                   |
    |     User-Agent: curl/8.0       |                                   |
    |     Accept: application/json   |                                   |
    |                                |                                   |
    |                                | readRequest()                     |
    |                                |── parse request line ──>          |
    |                                |── parse headers ────>             |
    |                                |── determine body reader ──>       |
    |                                |                                   |
    |                                | ServeHTTP(w, req)                 |
    |                                |──────────────────────────────────>|
    |                                |                                   | Chi middleware stack
    |                                |                                   |── middleware.RequestID
    |                                |                                   |── middleware.Logger
    |                                |                                   |── auth middleware
    |                                |                                   |── route handler
    |                                |                                   |
    |                                |                     handler calls:|
    |                                |                     w.Header().Set(...)
    |                                |                     w.WriteHeader(200)
    |                                |                     w.Write(body)
    |                                |                                   |
    | <──── HTTP/1.1 200 OK ────────|                                   |
    |      Content-Type: app/json    |<──────────────────────────────────|
    |      Date: Mon, 01 Jan...      |                                   |
    |      Content-Length: 42        |                                   |
    |                                |                                   |
    | <──── {"users": [...]} ────────|                                   |
    |                                |                                   |
    |                                | finishRequest()                   |
    |                                |── flush buffer ──>                |
    |                                |── check Connection header ──>     |
    |                                |── shouldReuseConnection() = true  |
    |                                |                                   |
    |                                | (connection remains open)         |
    |                                | (ReadTimeout reset)               |
    |                                |                                   |
    | ──── GET /users/42 HTTP/1.1 ─>| (another request on same conn)    |
    |     ...                        |                                   |
    |     [repeat flow above]        |                                   |
    |                                |                                   |
    |     ... idle for IdleTimeout...|                                   |
    |                                | ReadTimeout expires               |
    |                                |── close connection ──>            |
    |                                |                                   |
```

```
Graceful Shutdown Sequence:

  main goroutine      listener goroutine    conn goroutine (handler)   Client
       |                     |                      |                     |
       | Shutdown(ctx)       |                      |                     |
       |── inShutdown=true   |                      |                     |
       |                     |                      |                     |
       |── close(listener) ─>|                      |                     |
       |                     | Accept() returns ErrClosed               |
       |                     | serve() loop exits  |                     |
       |                     | X                    |                     |
       |                     |                      |                     |
       |── close idle conns─>|                      |                     |
       |   SetReadDeadline() |                      |                     |
       |                     |                      |  (active, handling)  |
       |                     |                      |  handler still runs  |
       |                     |                      |──── doing work ──────|
       |                     |                      |                     |
       |── wait loop ───────>|                      |                     |
       |   activeConn == 1  |                      |                     |
       |                     |                      |                     |
       |   ... wait ...      |                      | handler finishes    |
       |                     |                      |──── write response ──>|
       |                     |                      |<──── keep-alive? ────|
       |                     |                      |──── close conn ─────>|
       |                     |                      |                     |
       |   activeConn == 0  |                      |                     |
       |── return nil        |                      |                     |
       |                     |                      |                     |
       | exit process        |                      |                     |
```

## Source Code Reading Guide

Read these files in this order:

1. **`net/http/server.go`** — The main file (~3800 lines in Go 1.22). Read in this order:
   - `Handler` interface (~line 85) — 1 line of code. The most important interface in Go HTTP.
   - `ResponseWriter` interface (~line 90) — 3 methods. The write side.
   - `response` struct (~line 320) — the concrete implementation. Notice `wroteHeader` boolean (prevents double status codes), `chunking` boolean (auto-detected), `wroteBytes` counter.
   - `conn` struct (~line 350) — the connection wrapper. `rwc` is the raw `net.Conn`, `bufr` is the buffered reader, `bufw` is the buffered writer, `server` points back to the `Server`.
   - `readRequest()` (~line 1000) — how HTTP is parsed from bytes. Notice the deadline setup at the top.
   - `conn.serve()` (~line 1800) — the per-connection goroutine. The keep-alive loop is here. Notice the `defer recover()`.
   - `ServeMux` and `Handler` method (~line 2300-2450 for Go 1.21; ~line 2500-2700 for Go 1.22+). See the pattern matching logic.
   - `Server.Serve()` (~line 3050) — the accept loop. Notice exponential backoff.
   - `Server.ListenAndServe()` (~line 3200) — convenience method.
   - `Server.Shutdown()` (~line 3300) — graceful shutdown.

2. **`net/http/request.go`** — Read `Request` struct (~line 110). Notice `ctx context.Context` field, `Body io.ReadCloser`, `ContentLength int64`, `TransferEncoding []string`. The `Context()` method returns the context. `WithContext(ctx)` returns a shallow copy with a different context.

3. **`net/http/response.go`** — Read `response` struct and its `Write`, `WriteHeader`, `finishRequest` methods. Notice the `contentLength` auto-detection logic: if `Content-Length` header is set, don't chunk; if it's -1 (explicit "don't know"), use chunked encoding.

4. **`net/http/transport.go`** — The client side. Read `Transport` struct (~line 100). Understanding the client's connection pooling helps debug server-side connection behavior. Notice `MaxIdleConns`, `IdleConnTimeout`, `DisableKeepAlives`.

5. **`net/http/h2_bundle.go`** — HTTP/2 internals. Start with the `// Package http2` doc comment (~line 1). Read `serverConn` struct and `serve` method to understand how HTTP/2 multiplexes streams over a single TCP connection. Skip the frame parsing details.

6. **`github.com/go-chi/chi/v5/mux.go`** — Chi's `Mux` implements `http.Handler`. Read `ServeHTTP` (~line 80) to see how Chi integrates with `net/http`. Chi calls `mux.handler(r)` to find the matching handler (route + middleware stack), then calls `handler.ServeHTTP(w, r)`.

What to skip:
- HTTP/2 frame-level parsing (`readFrame`, `writeFrame`, individual frame types). Understand the conceptual model, skip the binary protocol details.
- Cookie jar implementation (`net/http/jar.go`). Separate topic.
- Protocol-specific details in `h2_bundle.go` beyond the `serve` method flow.
- The `httptest` package. Useful for testing but not relevant to server internals.

## Production Failure Scenarios

### Scenario 1: Missing ReadHeaderTimeout Enables Slow-Loris Attack

**Cause**: A server is configured with `ReadTimeout: 30s` but no `ReadHeaderTimeout`. A malicious client (or a buggy mobile app on a flaky 2G connection) opens a connection and sends headers one byte per second.

```go
// VULNERABLE configuration
srv := &http.Server{
    Addr:        ":8080",
    Handler:     chiMux,
    ReadTimeout: 30 * time.Second,
    // ReadHeaderTimeout: 0  ← NOT SET!
}
```

**Symptom**: The attacker opens 10,000 connections, each sending headers at 1 byte/second. Each connection ties up a goroutine for up to 30 seconds (the ReadTimeout). The server's goroutine count climbs to 10,000+. Memory usage increases proportionally. Legitimate clients cannot connect because all connections are consumed. P99 latency for legitimate requests spikes to 30 seconds (they queue behind the attack connections).

**Fix**: Always set `ReadHeaderTimeout` to a short value (5-10 seconds). This ensures headers are received quickly or the connection is closed, freeing resources. The body can take longer (up to `ReadTimeout`). Combined with `IdleTimeout: 60s` to close idle keep-alive connections.

```go
srv := &http.Server{
    Addr:              ":8080",
    Handler:           chiMux,
    ReadTimeout:       30 * time.Second,
    ReadHeaderTimeout: 5 * time.Second,  // slow-loris defense
    WriteTimeout:      30 * time.Second,
    IdleTimeout:       60 * time.Second,
}
```

### Scenario 2: WriteTimeout Causes Partial Responses on Slow Clients

**Cause**: A server has `WriteTimeout: 10s`. A legitimate handler takes 8 seconds to compute a response (complex report generation). That fits within 10s. But then the client has a slow connection (mobile network, 50 KB/s). The response body is 2MB. Writing 2MB at 50 KB/s takes ~40 seconds. At T+10s (from the start of writing), `WriteTimeout` fires. The connection is closed mid-response.

**Symptom**: Clients receive HTTP 200 status but incomplete body (TCP RST before full body). Client libraries (axios, fetch, net/http) report `unexpected EOF` or `connection reset by peer`. The server log shows `write tcp 10.0.1.5:8080->10.0.2.10:54321: i/o timeout` at ERROR level. The handler completed successfully but the client never got the full response. This is catastrophic for file downloads.

**Fix**: 
1. Increase `WriteTimeout` to account for the largest possible response at the slowest expected client speed. For a service that serves files up to 10MB over mobile connections (50 KB/s worst case), set `WriteTimeout: 200s`.
2. Use `io.Copy` with a wrapper that checks context cancellation but uses a longer write deadline for body writes.
3. Alternatively, offload large file serving to a CDN (S3 presigned URLs, CloudFront) and keep `WriteTimeout` short for API responses.

### Scenario 3: Connection Leak from Missing IdleTimeout

**Cause**: A server has `IdleTimeout: 0` (disabled). Keep-alive connections stay open indefinitely. Over weeks of uptime, clients connect, make requests, and then... disappear. Network failures (load balancer health checks that don't close connections properly, mobile apps backgrounded without closing sockets, AWS NAT gateway idle timeout of 350 seconds) leave connections open on the server side but dead on the client side.

**Symptom**: After several days of uptime, the server has 50,000 "open" connections but only 5,000 are actually active. The other 45,000 are ghost connections: the client went away but the TCP connection never received a FIN/RST (network partition, NAT timeout). The server's goroutine count is 50,000, consuming memory. New connections are accepted but the OS file descriptor limit is approaching (default 1024 on many systems, 65535 on tuned systems). `too many open files` errors appear in logs.

**Fix**: Set `IdleTimeout` to 60-120 seconds. This ensures ghost connections are detected and closed. The server sets a read deadline for `IdleTimeout` while waiting for the next request. If no data arrives, the read times out, the goroutine detects the error, and the connection is closed. Also, configure the OS file descriptor limit: `ulimit -n 65535` or `LimitNOFILE=65535` in systemd unit.

**Detection**: Monitor `net_connections_open` gauge (custom metric). If it grows monotonically without a corresponding increase in request rate, you have a connection leak. Correlate with `http_requests_total`—if requests are flat but connections are growing, idle connections are not being timed out.

## Debugging Techniques

### Technique 1: Inspect Server State with pprof and Runtime Metrics

```bash
# Check goroutine count and where they're blocked
go tool pprof http://localhost:6060/debug/pprof/goroutine

# In the pprof interface:
(pprof) top
# Shows functions with most goroutines. If conn.serve or bufio.(*Reader).Read dominates,
# many goroutines are idle waiting for requests.

(pprof) list conn.serve
# Shows which line the goroutines are blocked on.

(pprof) web
# Opens a visual graph showing goroutine stacks.

# Check connection state via netstat/ss
# Linux:
ss -tan state established '( sport = :8080 )' | wc -l
# Counts established connections to port 8080.

ss -tan state time-wait '( sport = :8080 )' | wc -l
# TIME_WAIT connections—normal after connection close, but excessive counts
# (> 10,000) indicate connection churn.
```

### Technique 2: Add Custom ConnState Logging for Connection Lifecycle

```go
srv := &http.Server{
    Addr:    ":8080",
    Handler: chiMux,
    ConnState: func(conn net.Conn, state http.ConnState) {
        switch state {
        case http.StateNew:
            log.Printf("Connection opened: %s", conn.RemoteAddr())
        case http.StateActive:
            log.Printf("Connection active: %s", conn.RemoteAddr())
        case http.StateIdle:
            log.Printf("Connection idle: %s", conn.RemoteAddr())
        case http.StateHijacked:
            log.Printf("Connection hijacked: %s", conn.RemoteAddr())
        case http.StateClosed:
            log.Printf("Connection closed: %s", conn.RemoteAddr())
        }
    },
}
```

This reveals the lifecycle of each connection. In production, use structured logging and metrics instead of `log.Printf`. Track: number of connections in each state, average duration in each state, transitions to `StateClosed` with reason. A connection that spends > 5 minutes in `StateIdle` without a `IdleTimeout` is a leak.

### Technique 3: Reproduce Timeout Issues with a Slow Client Simulator

```go
// slow_client_simulator.go
package main

import (
    "fmt"
    "net"
    "time"
)

func main() {
    conn, _ := net.Dial("tcp", "localhost:8080")
    defer conn.Close()

    // Send headers very slowly (simulate slow-loris)
    fmt.Fprintf(conn, "GET /slow HTTP/1.1\r\n")
    time.Sleep(1 * time.Second)
    fmt.Fprintf(conn, "Host: localhost\r\n")
    time.Sleep(1 * time.Second)
    fmt.Fprintf(conn, "User-Agent: slow-loris\r\n")
    time.Sleep(1 * time.Second)
    fmt.Fprintf(conn, "\r\n") // end of headers

    // Read response
    buf := make([]byte, 4096)
    n, _ := conn.Read(buf)
    fmt.Printf("Response: %s\n", buf[:n])
}
```

Run this against your server with `ReadHeaderTimeout: 2s`. The simulator should see the connection closed before headers are complete. Then increase `ReadHeaderTimeout` to 10s and verify the request succeeds. This validates that your timeout configuration actually works.

## Observability Considerations

### What to Log

1. **Connection lifecycle events**: `StateNew`, `StateClosed` at DEBUG level in development, INFO level in production (sampled, e.g., 1% of connections). Include: remote address, TLS version, cipher suite.
2. **Panic recovery**: When `conn.serve` recovers a panic, log at ERROR level with the full stack trace. This is your safety net for handler bugs. Monitor: if you see panics in production, fix the handler ASAP.
3. **Request timeouts**: When `ReadTimeout`, `ReadHeaderTimeout`, or `WriteTimeout` fires, log at WARN level with the connection info and the specific timeout type. This helps distinguish between slow clients (ReadTimeout) and slow handlers (WriteTimeout).
4. **Graceful shutdown events**: Log "shutting down, X active connections" when `Shutdown` is called. Log "shutdown complete" when all connections finish. Log "shutdown timed out with Y remaining connections" if the context deadline passes.

### What Metrics

1. **Connection count by state**: Gauge `http_connections{state="new|active|idle|hijacked|closed"}`. Alert if idle connections exceed a threshold (e.g., > 1,000 idle connections indicates missing IdleTimeout).
2. **Connection churn rate**: Counter `http_connections_total`. Divide by uptime to get connections/second. High churn (> 100/sec with IdleTimeout=120s) indicates clients are not using keep-alive or connections are being killed by a load balancer.
3. **Request timeouts by type**: Counter `http_timeouts_total{type="read|read_header|write|idle"}`. Alert if write timeouts spike (indicating slow handlers or slow clients).
4. **Goroutine count**: Gauge `go_goroutines`. If goroutine count grows linearly with uptime, you have a goroutine leak (likely connection goroutines that never exit).
5. **File descriptors**: Gauge `process_open_fds` (exposed by `prometheus/client_golang`). Alert at 80% of `ulimit -n`.

### What Traces

1. **TCP connection establishment**: Not typically traced by application code (happens before request parsing). Useful to know: TCP handshake latency increases with network distance. If your server is in us-east-1 and clients are in ap-southeast-1, the handshake adds ~300ms before the first byte of the request arrives.
2. **Request parsing latency**: The time from connection acceptance to handler entry. If this is > 100ms, the client is sending headers slowly (potential slow-loris) or there's network congestion.
3. **Handler duration**: From `ServeHTTP` entry to return. This is your actual application latency. Compare with end-to-end latency from the client's perspective—the difference is network + TCP + TLS + HTTP parsing overhead.
4. **Response flush latency**: From the last `Write` to the last byte sent (buffered writer flush + TCP write syscall). Usually sub-millisecond for small responses, but can spike under TCP congestion.

## Performance Implications

### Concern 1: Connection Goroutine Memory Per Idle Connection

Each idle keep-alive connection occupies:
- 1 goroutine (minimum 2KB stack, typically 8KB with read buffer)
- 1 `*conn` struct (~500 bytes)
- 1 read buffer (`bufio.Reader`, 4KB default)
- 1 write buffer (`bufio.Writer`, 4KB default)
- TCP socket buffer in the kernel (read: ~16KB default, write: ~16KB default)
Total per idle connection: ~45KB + socket buffers. At 10,000 idle connections: ~450MB.

Mitigation:
- Set `IdleTimeout` to close idle connections aggressively (30-60 seconds for APIs, 120s for browsers).
- Use a reverse proxy (nginx, Envoy, HAProxy) that handles keep-alive from clients and opens fresh connections to your Go service for each request (or uses HTTP/2 multiplexing).
- Consider a connection limit: `srv.MaxConnsPerHost` is client-side only. For server-side, implement a custom listener that wraps `Accept()` and rejects connections when a counter exceeds a threshold.

### Concern 2: Write Buffering and Latency

The default `*response` writes to a `bufio.Writer` with a 4KB buffer. For responses smaller than 4KB, the entire response is buffered in memory before being flushed to the TCP connection. This means the first byte of the response is not sent until the handler finishes writing (or calls `Flush()`). For time-to-first-byte (TTFB) sensitive applications, this adds latency.

Mitigation:
- For SSE/streaming: use `Flush()` after each event.
- For large file downloads: use `io.Copy(w, file)` which writes in chunks and triggers buffer flushes automatically.
- For TTFB-sensitive JSON responses: build the response in memory, then write it in one call. The initial buffering doesn't matter because the response is built before any bytes are sent.
- For responses that mix header updates with body writes (chunked), use `Transfer-Encoding: chunked` which flushes periodically.

### Concern 3: TCP TIME_WAIT and Connection Churn

When the server closes a connection, it enters the TCP TIME_WAIT state for 2 * MSL (Maximum Segment Lifetime, typically 60-120 seconds). During TIME_WAIT, the (source_ip, source_port, dest_ip, dest_port) tuple cannot be reused. At high connection churn (> 10,000 connections/second), you can exhaust the available ephemeral ports.

This is NOT an HTTP problem per se—it's a TCP problem. But it manifests as HTTP connection errors.

Mitigation:
- Enable TCP keep-alive probes on connections (`net.TCPConn.SetKeepAlive(true)` with a short period). This detects dead connections earlier and frees ports.
- Use HTTP/2 (which uses a single connection for many requests, dramatically reducing churn).
- Tune kernel parameters: `net.ipv4.tcp_tw_reuse=1` (allow reusing TIME_WAIT sockets for outgoing connections), `net.ipv4.ip_local_port_range=10240 65535` (increase ephemeral port range).
- If your Go server is behind a load balancer, configure the load balancer to use keep-alive connections to your backend. This reduces connection churn.

## Architecture Implications

The `net/http` server architecture—goroutine per connection, synchronous request handling, no event loop—shapes how Go services scale. Each request is handled in a dedicated goroutine with its own stack, its own flow of control. This means handlers can be written as straightforward synchronous code: read from database, compute result, write response. There is no callback hell, no async/await complexity, no event loop that must not be blocked. This simplicity is one of Go's primary advantages for HTTP services.

The tradeoff is that I/O must be managed carefully. If a handler makes a blocking I/O call (database query, HTTP call to another service) without respecting context cancellation, the goroutine is stuck until the I/O completes—potentially forever if the target is unreachable. The context propagates the deadline and cancellation signal, but it is the handler's responsibility to use it. A goroutine waiting on a `db.Query("SELECT ...")` without context will never be canceled by `Server.Shutdown`. The graceful shutdown will time out, and the process will either hang or be killed.

This architecture also means that the number of concurrent requests is limited by the number of goroutines the runtime can efficiently schedule. Go can handle millions of goroutines, but each one consumes memory (stack, buffers). At 100,000 concurrent requests with 8KB goroutine stacks + 4KB read buffer + 4KB write buffer = ~1.6GB overhead. For most services, this is fine (100K concurrent requests is unusual). For services that need to handle millions of concurrent connections (long-polling, WebSockets, SSE), the goroutine-per-connection model may not scale—consider a framework that uses epoll/kqueue directly (e.g., `gnet`, `evio`), though this sacrifices the simplicity of synchronous handlers.

## Team Ownership Implications

The `net/http.Server` configuration (timeouts, TLS, connection management) should be owned by the platform/infrastructure team, not by individual feature teams. A single misconfigured timeout can cause cascading failures across all features. The SRE/DevOps team should define standard `Server` configurations as code (a shared `httpconfig` package or Terraform module) that all services use:

```go
// pkg/httpconfig/server.go (owned by platform team)
package httpconfig

import "net/http"

func NewDefaultServer(handler http.Handler) *http.Server {
    return &http.Server{
        Addr:              ":8080",
        Handler:           handler,
        ReadTimeout:       30 * time.Second,
        ReadHeaderTimeout: 5 * time.Second,
        WriteTimeout:      60 * time.Second,
        IdleTimeout:       120 * time.Second,
        MaxHeaderBytes:    1 << 16, // 64KB
    }
}
```

Feature teams should not override timeouts without explicit approval from the platform team. A "small tweak" to `WriteTimeout: 300s` to accommodate a slow report generation feature can cause connection leaks that affect all other features. Instead, the slow feature should be redesigned to work within the standard timeout boundary (async processing with polling, streaming responses, or offloading to a background job).

## Interview Questions

### Q1: "Explain the difference between ReadTimeout, ReadHeaderTimeout, and WriteTimeout. How do they interact?"

**Answer**: 

- **`ReadTimeout`**: Total time from connection acceptance to the end of the request. Covers reading headers AND body. If the client sends headers in 1 second and then uploads a 100MB file over 5 minutes, ReadTimeout must be > 5 minutes.
- **`ReadHeaderTimeout`**: Time allowed to read JUST the request headers. Defends against slow-loris attacks. After headers are read, the deadline transitions to ReadTimeout for the body.
- **`WriteTimeout`**: Time allowed to write the response. Starts counting from the end of request header read. Covers the handler execution time + response writing time.

Interaction: They don't directly interact; they are sequential phases. ReadHeaderTimeout applies first. When headers are fully received, ReadHeaderTimeout is cleared and ReadTimeout applies (for body reading). When the handler starts writing, WriteTimeout applies. If WriteTimeout is shorter than the handler execution time, the response is cut off.

A common misconfiguration: setting `ReadTimeout` to a short value (e.g., 5 seconds) without setting `ReadHeaderTimeout`. A slow-loris attack sending headers for 24 seconds will timeout, but a legitimate file upload that takes 30 seconds will ALSO timeout because ReadTimeout covers both headers and body. Fix: set `ReadHeaderTimeout` short (5s) and `ReadTimeout` long (300s for file uploads).

### Q2: "Walk through what happens from the OS perspective when Go's HTTP server calls l.Accept() and a client connects."

**Answer**: 

1. **Server calls `l.Accept()`**: This is a Go wrapper around the `accept()` system call. The goroutine enters a blocking syscall. Go's runtime detaches the goroutine from the OS thread (puts the OS thread into the "syscall" state in the scheduler), allowing other goroutines to use that OS thread.

2. **Client sends SYN**: The client's TCP stack sends a SYN packet to the server's IP:port. The server's kernel receives it and checks if there's a listening socket on that port.

3. **Kernel TCP handshake**: If the listen backlog is not full, the kernel completes the TCP three-way handshake (SYN → SYN+ACK → ACK) and places the established connection in the accept queue. If the accept queue is full (`net.core.somaxconn`), the kernel drops the SYN (or sends RST, depending on `tcp_abort_on_overflow`).

4. **`accept()` returns**: The `accept()` syscall removes a connection from the accept queue and returns a new file descriptor (the client socket). Go's runtime wakes the goroutine. The goroutine receives a `*net.TCPConn` wrapping the file descriptor.

5. **Go wraps the connection**: `srv.newConn(rw)` creates a `*conn` struct with buffered readers/writers. The connection is registered with Go's netpoller, which uses epoll (Linux), kqueue (macOS/BSD), or IOCP (Windows) to monitor the file descriptor for readability.

6. **Goroutine spawned**: `go c.serve(ctx)` starts a new goroutine. The runtime scheduler assigns it to an available OS thread (or creates a new one). The goroutine starts reading the HTTP request.

Critical detail: the accept queue size determines how many connections can be waiting before the kernel starts dropping them. If your Go server processes requests slowly (high latency), the accept queue fills up, and new connections are dropped BEFORE `Accept()` ever sees them. This is "listen overflow" and is visible in `netstat -s | grep overflow` or `/proc/net/netstat` as `ListenOverflows` and `ListenDrops`.

### Q3: "Describe how HTTP/2 differs from HTTP/1.1 in Go's net/http server. What changes for the handler?"

**Answer**: 

**HTTP/1.1**: One goroutine per connection. The goroutine processes requests sequentially (request → response → next request). Keep-alive allows multiple requests on the same connection, but they are serialized. Head-of-line blocking: a slow response blocks all subsequent responses on that connection.

**HTTP/2**: One goroutine per connection, plus one goroutine per stream. Multiple requests can be in-flight simultaneously on the same TCP connection, each in its own stream. The connection goroutine reads frames from the TCP connection and dispatches them to the appropriate stream goroutine. Streams are multiplexed, so a slow response does not block other responses.

**What changes for the handler**:
- The handler interface is identical: `ServeHTTP(ResponseWriter, *Request)`. The handler does not know whether the request came over HTTP/1.1 or HTTP/2.
- `*http.Request.Proto` is `"HTTP/2.0"` (or `"HTTP/2"` depending on Go version).
- `ResponseWriter` supports `http.Pusher` for server push (rarely used, being deprecated).
- `ResponseWriter` does NOT support `http.Hijacker` in HTTP/2 (hijacking is HTTP/1.1-specific). This breaks WebSocket libraries that use hijacking. `gorilla/websocket` detects HTTP/2 and falls back to a non-hijacking mode.
- Headers are compressed with HPACK. This is transparent to the handler.
- Flow control: if the client's flow control window is exhausted, `w.Write()` blocks until the client sends a WINDOW_UPDATE frame. The handler sees this as a slow/flaky connection.

The key insight: a single misbehaving HTTP/2 client can impact all streams on the same connection (they share the TCP connection). In HTTP/1.1, a misbehaving client only impacts its own connection. HTTP/2's multiplexing is generally a net win for performance but introduces shared-fate between streams.

### Q4: "Explain the relationship between Chi and net/http. Where does Chi's routing happen relative to net/http's request parsing?"

**Answer**: Chi sits entirely within the `Handler` role in `net/http`'s architecture. The sequence is:

1. `net/http` accepts the TCP connection, reads and parses the HTTP request (headers, body reader), creates `*http.Request` and `http.ResponseWriter`.
2. `net/http` calls `serverHandler{c.server}.ServeHTTP(w, req)`.
3. The `serverHandler` wraps the call with any top-level `Handler` set on the `Server` struct. If the `Handler` is a Chi `Mux`, execution enters Chi.
4. Chi's `Mux.ServeHTTP` runs the middleware stack. Middleware is applied in order: global middleware (from `r.Use()`) → group middleware (from `r.Group()`) → route-specific middleware (from `r.With()`) → handler.
5. Chi matches the route: it looks up the request's method and path in its radix tree. The route finds the specific `http.Handler` registered for that pattern.
6. Chi runs the handler's `ServeHTTP(w, req)`, which is your application code.

At this point, `net/http` has done its job (parsing) and delegated to Chi. The `w` and `r` were created by `net/http`. Chi adds URL parameters, route data, and middleware context values to `r.Context()`. After your handler returns, control returns to `net/http`, which calls `w.finishRequest()` to flush the response.

Chi does NOT replace any of `net/http`'s transport-layer responsibilities: TCP accept, TLS handshake, connection management, HTTP parsing, graceful shutdown. Chi is purely a routing + middleware framework on top of `net/http`'s `Handler` interface.

### Q5: "Design a production-ready HTTP server configuration for a Go service that handles file uploads (up to 100MB) and API requests (small JSON)."

**Answer**:

```go
srv := &http.Server{
    Addr:              ":8080",
    Handler:           chiMux,
    
    // API requests: headers received quickly
    // File uploads: headers ALSO received quickly (just metadata, not the body)
    ReadHeaderTimeout: 5 * time.Second,
    
    // File uploads: body can take time (100MB over slow connection)
    // Set generously but NOT infinite
    ReadTimeout:       600 * time.Second, // 10 minutes max
    
    // Responses: 60 seconds should cover API + small files
    // For large file downloads (100MB), offload to CDN or use io.Copy
    // with per-chunk deadline extension
    WriteTimeout:      60 * time.Second,
    
    // Close idle keep-alive connections after 2 minutes
    IdleTimeout:       120 * time.Second,
    
    // Limit header size to prevent abuse
    MaxHeaderBytes:    64 * 1024, // 64KB
    
    // Base context with server instance metadata
    BaseContext: func(l net.Listener) context.Context {
        return context.WithValue(context.Background(), "server_id", instanceID)
    },
    
    // Connection context with client info
    ConnContext: func(ctx context.Context, c net.Conn) context.Context {
        return context.WithValue(ctx, "client_ip", c.RemoteAddr().String())
    },

    // TLS for production
    TLSConfig: &tls.Config{
        MinVersion: tls.VersionTLS12,
        CurvePreferences: []tls.CurveID{
            tls.X25519,
            tls.CurveP256,
        },
        CipherSuites: []uint16{
            tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        },
    },
}
```

Key decisions:
- `ReadHeaderTimeout: 5s`: Headers are small (a few KB). Even on 3G, 5 seconds is generous. Defends against slow-loris.
- `ReadTimeout: 600s`: Accommodates 100MB uploads at ~170 KB/s (worst-case mobile). A 10-minute upload is extreme but possible. Add a body size limit via `http.MaxBytesReader` in a middleware: `r.Body = http.MaxBytesReader(w, r.Body, 100<<20)` to enforce the 100MB limit BEFORE the body is fully read (aborts early if exceeded).
- `WriteTimeout: 60s`: API responses + small file downloads fit here. For large file downloads, either use a CDN (recommended) or implement per-chunk write deadline extensions.
- `IdleTimeout: 120s`: Standard value. Browsers use keep-alive with ~115s idle timeout. Slightly higher ensures server-side timeout happens first (cleaner).
- TLS configuration: Modern, secure defaults. TLS 1.2 minimum (TLS 1.3 preferred if Go version supports it). X25519 + P256 for key exchange. AES-256-GCM for encryption.

## Hands-On Exercises

### Exercise 1: Trace a Request Through the Full net/http Stack

**Goal**: Use Delve to trace a single HTTP request from TCP connection to handler return.

**Steps**:
1. Write a minimal Chi server with one route (`GET /hello` returns `{"message": "hello"}`).
2. Set breakpoints in Delve: `net/http.(*conn).serve`, `net/http.(*conn).readRequest`, `net/http.serverHandler.ServeHTTP`, and your handler function.
3. Send a request with `curl http://localhost:8080/hello`.
4. Step through the code at each breakpoint. Inspect the `*conn` struct: see `bufr` and `bufw`. Inspect the `*Request` after parsing: see URL, headers, Body.
5. Measure the time spent in: TCP accept → handler entry, handler entry → handler return, handler return → response flush.
6. Modify the server to use a custom `ConnState` callback and observe state transitions during the request lifecycle.

### Exercise 2: Benchmark Connection Handling with Different Configurations

**Goal**: Measure the impact of keep-alive, IdleTimeout, and goroutine count on performance.

**Steps**:
1. Write a benchmark server with a `GET /ping` endpoint that sleeps for 10ms to simulate work.
2. Benchmark with `wrk` or `hey`: `wrk -t4 -c100 -d30s http://localhost:8080/ping`. Record: RPS, P99 latency, goroutine count (`/debug/pprof/goroutine`).
3. Change the server configuration: disable keep-alive (`Connection: close` in response), set `IdleTimeout: 0`, set `IdleTimeout: 5s`. Re-benchmark each.
4. Compare: how does RPS change with keep-alive disabled? How does goroutine count change with `IdleTimeout: 0` vs `IdleTimeout: 5s`?
5. Run a connection leak test: open 1000 connections, send one request each, and DON'T close them. Observe goroutine count over time with `IdleTimeout: 0` (leak) vs `IdleTimeout: 10s` (recovery).

### Exercise 3: Implement a Custom Middleware That Wraps ResponseWriter

**Goal**: Understand the `ResponseWriter` interface by implementing a custom wrapper.

**Steps**:
1. Create a `loggingResponseWriter` that wraps `http.ResponseWriter` and records the status code, bytes written, and whether `WriteHeader` was called.
2. Ensure it satisfies the `http.ResponseWriter` interface AND `http.Flusher` (delegate to underlying writer if it implements `Flusher`).
3. Use it in a Chi middleware that logs: method, path, status code, bytes written, duration.
4. Test edge cases: handler writes body without `WriteHeader`, handler calls `WriteHeader` after `Write` (should be a no-op, but capture that it happened), handler never writes body, handler calls `Flush()`.
5. Compare with Chi's built-in `middleware.Logger`. What does the built-in logger do differently?

## Advanced Challenges

### Challenge 1: Implement a Connection Pool Throttling Middleware

**Goal**: Create a middleware that limits the number of concurrent active connections, queuing excess requests.

**Constraints**:
- Use a buffered channel as a semaphore (`make(chan struct{}, maxConns)`).
- When the semaphore is full, the middleware should return HTTP 503 (or wait with a timeout).
- Track the number of queued requests and the wait time.
- Must not break keep-alive (waiting for a slot should not close the connection).

**Approach**: The middleware acquires the semaphore before calling `next.ServeHTTP` and releases it after. If the semaphore is full, the middleware can:
- Option A: Return 503 immediately (fast fail).
- Option B: Wait with a context-aware timeout (`select { case <-sem: ...; case <-ctx.Done(): return }`).
Option A is better for APIs (clients retry). Option B is better for critical requests where queuing is acceptable.

**Bonus**: Implement per-route limits: `/admin` allows 10 concurrent, `/api` allows 1000 concurrent. Use different semaphores for different route groups.

### Challenge 2: Build an HTTP/1.1 Server from Scratch Using Only net.Conn

**Goal**: Implement a minimal HTTP/1.1 server using only `net.Listen` and `net.Conn`, without `net/http`.

**Constraints**:
- Parse HTTP request line and headers manually (no `textproto` or `http` package imports).
- Support GET and POST methods.
- Support keep-alive (Connection: keep-alive).
- Support Content-Length body reading.
- Handle `Transfer-Encoding: chunked` (basic: parse chunk sizes, concatenate data).
- Implement a simple `ServeMux`-like router.

**Approach**:
1. `net.Listen("tcp", ":8080")` → accept loop → goroutine per connection.
2. In each goroutine: `bufio.NewReader(conn)` → read line by line until blank line.
3. Parse request line: split by spaces → method, path, version.
4. Parse headers: split each line by `: ` → key-value pairs.
5. If `Content-Length: N`, read exactly N bytes from the body.
6. Dispatch to handler based on method + path.
7. Write response: `HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n` + body.
8. Check `Connection` header for keep-alive. If keep-alive, loop for next request.

**Evaluation**: Compare your implementation with `net/http`. What edge cases does `net/http` handle that your implementation doesn't? (Hint: header normalization, multi-value headers, chunked encoding edge cases, trailer headers, CONNECT method, HTTP/1.0 compatibility).

## Key Insights

- `net/http.Server` is not a black box. It's ~3800 lines of well-written Go that you should read at least once. Understanding `conn.serve()` (the per-connection goroutine) and `readRequest()` (HTTP parsing) demystifies how your Chi service actually works and enables you to debug the hardest production issues.
- The goroutine-per-connection model means each connection costs ~45KB+ (goroutine stack + buffers). At scale (100K+ connections), this is real money. Configure `IdleTimeout` aggressively, use keep-alive wisely, and consider a reverse proxy to reduce the number of concurrent connections to your Go service.
- `ReadHeaderTimeout` is NOT optional for production. Without it, a single slow-loris attacker can exhaust your connection pool by holding connections open with 1-byte-per-second header sends. Set it to 5-10 seconds. This is the single most important timeout that most developers miss.
- `WriteTimeout` covers handler execution + response writing. If your handler takes 30 seconds to compute and WriteTimeout is 10 seconds, the connection will be closed mid-handler. Design handlers to respect timeouts, or increase WriteTimeout to accommodate worst-case latency.
- Chi is NOT a replacement for `net/http`. Chi is a routing and middleware framework that implements `http.Handler`. All the transport concerns (TCP, TLS, HTTP/2, connection management, graceful shutdown) are handled by `net/http`. Chi adds value at the application layer: routing, middleware composition, and URL parameter extraction.
- Graceful shutdown is a server concern, not a Chi concern. `Server.Shutdown(ctx)` closes idle connections, waits for in-flight requests to complete, and returns when done (or on context timeout). Chi does not participate in shutdown—it just stops receiving new requests because the listener is closed.
- HTTP/2 is enabled transparently when you configure TLS. No code changes needed. The handler interface is identical. But HTTP/2 changes the connection model fundamentally: multiple streams share one TCP connection, hijacking is not supported, and flow control can cause unexpected write blocking.
- Read `net/http/server.go`. It's one of the most instructive pieces of Go standard library code. The patterns you'll learn (goroutine lifecycle management, graceful shutdown, panic recovery, exponential backoff) are applicable far beyond HTTP servers.
