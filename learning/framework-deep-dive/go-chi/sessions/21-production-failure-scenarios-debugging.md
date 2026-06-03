# Session 21: Production Failure Scenarios & Debugging

## Why This Topic Exists

Production failures in Go services don't look like compile errors or test failures. They look like a pager going off at 3 AM because latency jumped from 5ms to 30s, or a service that's been running fine for weeks suddenly OOM-killed, or a goroutine count that's been climbing by 1,000 per hour since the last deploy. These failures have one thing in common: the code that's broken is not the code you're looking at. The handler that's leaking goroutines is buried three layers deep in a shared library. The GC pause that's causing request timeouts is triggered by a memory leak in a background worker you didn't know existed. The race condition that corrupts data only manifests under load with a specific request ordering that happens once per million requests.

Go gives you world-class debugging tools — pprof, execution tracer, race detector — but knowing they exist isn't enough. You need to know how to interpret the output, which tool to reach for first in each scenario, and how to collect data from a production service without causing further degradation. A goroutine profile showing 50,000 goroutines is terrifying, but knowing how to read the stack traces to identify whether they're all blocked on the same channel, all waiting on the same mutex, or all in different states distinguishes a fix that takes 5 minutes from one that takes 5 days.

Staff engineers don't just fix the immediate bug — they build the diagnostic capability into the service so that next time, the on-call engineer has the data they need in 30 seconds instead of 30 minutes. This means pprof endpoints enabled by default (behind a separate port, not exposed externally), structured logs that include goroutine state at error boundaries, metrics that detect anomalies before they become incidents, and runbooks that walk through each failure mode with concrete commands. The tools are free; the discipline to use them systematically is what you're building.

## Mental Model

Think of production debugging as a funnel. At the wide end, you have **symptoms**: latency spike, error rate increase, OOM kill, CPU saturation. Your first job is to narrow the symptom to a **system resource**: is CPU high because of GC pressure, or because of actual work? Is memory high because of a leak, or because the service legitimately needs it? Is latency high because of database slowness, or because goroutines are contending on a mutex?

Once you've identified the resource, you drill into the **mechanism**: if it's goroutine-related, `pprof goroutine` shows you the stack traces and states. If it's memory-related, `pprof heap` shows you allocation patterns. If it's CPU-related, `pprof profile` shows you hot functions. The mechanism tells you **what** code is consuming the resource. Then you trace back to the **root cause**: the code change, configuration change, or traffic pattern change that triggered the mechanism. This is the hardest step because it requires understanding the interaction between your code, Go's runtime, and the infrastructure.

The final step is **prevention**: what do you add to the service so this failure can't happen again, or at least so it's detected before it becomes an incident? This might be a metric, an alert, a linter rule, a load test scenario, or a middleware that enforces a resource limit. The best incident is the one that never happens, and the second-best is the one that's auto-remediated before a human wakes up.

```
                          PRODUCTION INCIDENT FUNNEL

  ┌──────────────────────────────────────────────────────────────┐
  │  SYMPTOM                                                      │
  │  "p99 latency 30s" "OOM killed" "50k goroutines" "503 errors" │
  └────────────────────┬─────────────────────────────────────────┘
                       │ narrowing with metrics/dashboards
                       ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  RESOURCE                                                     │
  │  CPU │ Memory │ Goroutines │ FDs │ Connections │ Disk I/O     │
  └────────────────────┬─────────────────────────────────────────┘
                       │ profiling (pprof/trace)
                       ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  MECHANISM                                                    │
  │  "goroutine leak in client.Do()" "1GB allocation in parser"   │
  │  "mutex contention in rate limiter" "GC spending 80% CPU"     │
  └────────────────────┬─────────────────────────────────────────┘
                       │ code analysis, git bisect
                       ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  ROOT CAUSE                                                   │
  │  "missing resp.Body.Close()" "unbounded map in background job"│
  │  "ReadHeaderTimeout not set" "race on shared counter"         │
  └────────────────────┬─────────────────────────────────────────┘
                       │ prevention
                       ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  PREVENTION                                                   │
  │  Linter rule │ Alert │ Load test │ Middleware │ Runbook       │
  └──────────────────────────────────────────────────────────────┘
```

## Internal Architecture

**The Go scheduler and goroutine lifecycle.** Every goroutine starts as a small stack (2KB minimum since Go 1.4, growing dynamically). The Go scheduler (in `runtime/proc.go`) maps M goroutines onto N OS threads (M:N model). When a goroutine makes a blocking system call (I/O, channel send to full channel, mutex lock), the scheduler detaches it from its OS thread and attaches another goroutine to keep the thread busy. When the blocked goroutine becomes runnable again, it is placed on a P's (logical processor's) local run queue. The `GODEBUG=schedtrace=1000` flag prints scheduler statistics every 1000ms: `SCHED 0ms: gomaxprocs=8 idleprocs=3 threads=12 spinning=0 idlethreads=2 runqueue=5 [0 0 0 0 1 2 0 1]` — this tells you how many goroutines are runnable, how many OS threads exist, and how many Ps are idle.

**The pprof ecosystem.** There are three pprof profilers in the Go runtime, each with different overhead and use cases. The **CPU profiler** (`runtime/pprof`) uses the OS's interval timer (typically 100Hz on Linux) to sample the program counter 100 times per second. Each sample records the full call stack, and aggregating these tells you which functions consume the most CPU time. Overhead: ~5% at 100Hz. The **heap profiler** (`runtime.MemProfileRate`) samples one allocation per `MemProfileRate` bytes allocated (default 512KB). It records the stack trace of each sampled allocation. Overhead: negligible for default rate. The **goroutine profiler** captures the stack trace and state (running, runnable, waiting, syscall) of every goroutine. This is a snapshot — it pauses to walk all goroutines but the pause is measured in microseconds for typical goroutine counts (<10K).

**pprof's flame graph rendering.** When you run `go tool pprof -http=:8080 cpu.out`, pprof's web UI provides four views: Top (sorted list of functions by cumulative CPU time), Graph (call graph with node size proportional to CPU time), Flame Graph (bottom-up view where stack frames are stacked vertically — the top function is what's currently executing, the layers below are the callers), and Peek (like Top but shows caller/callee pairs). The Flame Graph is the most useful for quickly identifying bottlenecks: wide bars at the top mean leaf functions consuming CPU directly; wide bars deep in the stack mean that a high-level function's time is spread across many leaf calls. Clicking a frame filters the flame graph to show only calls downstream from that function.

**The execution tracer (`go tool trace`).** While pprof profiles aggregate statistical samples, the execution tracer captures a complete timeline of every goroutine event: creation, blocking (channel, mutex, I/O, sleep), unblocking, syscall entry/exit, GC start/stop, and heap goal changes. This generates very large files (100MB+ for 30 seconds of tracing a moderately loaded service) but provides insights that pprof cannot: you can see exactly when goroutine G12345 blocked on a channel `c` that goroutine G67890 was writing to, or that GC pause at 14.3 seconds coincided with a spike in heap allocation by a specific goroutine. The trace viewer (`go tool trace trace.out`) shows a Gantt chart of goroutines, a network blocking profile, and a synchronization blocking profile.

**The race detector.** Go's race detector (`go test -race`, `go build -race`) instruments every memory access with shadow state tracking. It intercepts all reads and writes, records which goroutine performed each access, and detects when two goroutines access the same memory location without synchronization where at least one access is a write. The overhead is severe: 5-10x slower execution and 5-10x more memory. This means you can't run it in production, but you should run it in CI on every PR and in staging load tests. The race detector catches races that reproduce under any schedule — it doesn't need the racy interleaving to actually occur; it detects the potential for the race based on observed access patterns.

**GODEBUG flags.** The `GODEBUG` environment variable exposes runtime debugging knobs. `GODEBUG=gctrace=1` prints GC events: `gc 1234 @45.678s 2%: 0.12+1.5+0.05 ms clock, 0.96+0.8/1.5/0+0.4 ms cpu, 125->126->100 MB, 128 MB goal, 8 P` — this reads as: GC cycle 1234, 45.678s into program execution, 2% of CPU spent in GC so far (cumulative). The three timings: 0.12ms STW sweep termination, 1.5ms concurrent mark/scan, 0.05ms STW mark termination. The heap sizes: 125MB before GC, 126MB at the mark phase peak, 100MB after GC. `GODEBUG=scheddetail=1,schedtrace=1000` prints detailed scheduler state every second. `GODEBUG=asyncpreemptoff=1` disables asynchronous preemption (for debugging scheduler issues).

## Runtime Behavior

**Goroutine leak: unclosed `http.Response.Body`.** When you call `http.Get(url)` or `client.Do(req)`, the HTTP client starts a goroutine for each connection. The response body is a stream — the goroutine that reads from the network is still running, writing data into a buffer that `resp.Body.Read()` consumes. If you don't call `resp.Body.Close()`, the network-reading goroutine never terminates. It blocks forever on a `write` to the internal buffer (because nobody is reading from it), or on a `read` from the network (because the server is still sending data). This leaks both the goroutine and the underlying TCP connection. A service making 100 req/s with a 0.1% leak rate accumulates 86,400 leaked goroutines per day. On the goroutine profile, these show up as goroutines with state `[IO wait]` in `net.(*netFD).Read` with `net/http.(*persistConn).readLoop` in the stack trace. The fix is always `defer resp.Body.Close()` immediately after checking for errors from `client.Do()`.

**GC pause spike.** The Go garbage collector is concurrent and generational-lite (it doesn't have generations, but it uses a write barrier and the "GC pacing" algorithm to keep pause times under its target). However, pause times can spike when: (1) the heap is very large (>10GB) and the mark termination phase has a lot of work to do, (2) `GOGC=off` was set and memory grew unbounded until the OOM killer kicked in, (3) finalizer-heavy code causes the finalizer goroutine to block the GC, or (4) a CGO call blocks the GC from proceeding because it can't preempt C code. Running `GODEBUG=gctrace=1` shows the pause distribution. The `runtime.MemStats.PauseNs` histogram captures the last 256 GC pause times. An increasing `PauseNs` with each GC cycle indicates the heap is growing faster than the GC can reclaim — you need to reduce allocation rate or increase `GOGC` (the target heap growth ratio).

**Connection pool exhaustion with `sql.DB`.** The symptom is requests timing out with `context deadline exceeded` after 5 seconds, but the database shows no slow queries. `db.Stats()` reveals `InUse = MaxOpenConns` (all connections busy) and `WaitCount` climbing. Looking at the PostgreSQL side with `SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction'` shows N connections stuck in "idle in transaction" — meaning a goroutine called `db.BeginTx()` but never called `tx.Commit()` or `tx.Rollback()`. The goroutine profile shows these goroutines blocked on something else (a channel, a mutex, an HTTP call) with an open transaction on their stack. The connection is held until the goroutine returns the transaction (which it never does because it's stuck). The immediate fix is to kill the stuck goroutines (which requires killing the process), but the root cause fix is adding `context.WithTimeout` to the transaction and ensuring every code path either commits or rolls back.

**Memory leak from unbounded data structure.** When a map or slice grows without bound, it's not a leak in the C sense (the memory is still reachable and tracked by the GC), but it's a leak in the operational sense (the process's RSS keeps growing until OOM). Common causes: (a) a map used as a cache without eviction, (b) a slice that appends log lines or events and is never trimmed, (c) a goroutine that holds a reference to a large object it doesn't need anymore (e.g., a slice that's been sliced but the underlying array is still referenced by the large original slice). The heap profile shows which allocations are live (`--inuse_space`) and cumulative allocations (`--alloc_space`). If a single allocation site accounts for 80% of in-use memory, that's your leak. Use `pprof --list=functionName` to see the exact line of code allocating.

## Flow Diagrams

```
GOROUTINE LEAK DETECTION FLOW:

  Symptom: goroutine count grows linearly with uptime
     │
     ▼
  $ curl http://localhost:6060/debug/pprof/goroutine?debug=1
     │
     ▼
  Output: 50274 goroutines, 50 goroutines per "state"
     │
     ├─► [IO wait]:    45,200 goroutines
     │       │
     │       ▼
     │   Sample stack: net.(*netFD).Read()
     │                  net/http.(*persistConn).readLoop()
     │                  net/http.(*persistConn).roundTrip()
     │                  net/http.(*Transport).roundTrip()
     │                  net/http.(*Client).Do()
     │                  myapp/external.(*Client).Fetch()    ← YOUR CODE
     │       │
     │       ▼
     │   Check: is resp.Body.Close() called?
     │       │
     │       ├─► NO → This is the leak. Add defer resp.Body.Close()
     │       │
     │       └─► YES but goroutine still blocked →
     │           Connection not being returned to pool.
     │           Check if server hung up without sending EOF.
     │           Add client timeout with context.WithTimeout.
     │
     ├─► [chan receive]: 4,800 goroutines
     │       │
     │       ▼
     │   All blocked on the same channel → no writer exists anymore.
     │   This channel had a writer that crashed/returned without closing.
     │   Fix: ensure the writer always closes the channel, or use a
     │   buffered channel + select with default/timeout.
     │
     ├─► [select]: 200 goroutines
     │       │
     │       ▼
     │   Likely in a for { select { case ... } } with ctx.Done() case
     │   that should terminate but doesn't. Check context cancellation.
     │
     └─► [running]: 74 goroutines
             Normal — these are actively executing.


MEMORY LEAK DIAGNOSIS FLOW:

  Symptom: RSS grows from 200MB to 4GB over 12 hours
     │
     ▼
  $ curl http://localhost:6060/debug/pprof/heap > heap.prof
  $ go tool pprof -http=:8081 heap.prof
     │
     ▼
  pprof web UI shows:
     │
     ├─► View: Flame Graph, Sample: inuse_space
     │       │
     │       ▼
     │   80% of memory: myapp/cache.(*LRU).Add() at cache.go:45
     │       │
     │       ▼
     │   cache.go:45:
     │       cache.entries[key] = value  ← BUG: no eviction policy
     │       │
     │       ▼
     │   Fix: Add LRU eviction, TTL-based expiry, or max size check.
     │
     ├─► View: Top, Sample: alloc_space
     │       │
     │       ▼
     │   Shows cumulative allocations since start:
     │   myapp/parser.(*Parser).Parse() allocated 120GB total
     │   but inuse is only 50MB → allocation churn, not a leak.
     │   Use sync.Pool to reuse parse buffers.
     │
     └─► View: Source, focus: myapp/handler.(*Handler).ServeHTTP
             Shows per-line allocation from this handler.
             If `json.Unmarshal` is the top allocator, consider
             json.Decoder with reusable buffers.


RACE CONDITION DETECTION FLOW:

  Symptom: intermittent data corruption or nil pointer dereference
  that doesn't reproduce in testing
     │
     ▼
  $ go test -race -run TestOrderProcessing ./...
     │
     ▼
  Output:
  WARNING: DATA RACE
  Write at 0x00c0001a0120 by goroutine 15:
    myapp/orders.(*Processor).updateCounter()
        orders/processor.go:87 +0x234

  Previous read at 0x00c0001a0120 by goroutine 12:
    myapp/orders.(*Processor).getCounter()
        orders/processor.go:92 +0x189

  Goroutine 15 (running) created at:
    myapp/orders.(*Processor).Start()
        orders/processor.go:45 +0x312
     │
     ▼
  Analysis:
  - processor.go:87: counter++ (write)
  - processor.go:92: return counter (read)
  - No mutex protecting counter
  - Goroutine 15 (writing) created at Start() line 45
  - Goroutine 12 (reading) also created at Start() line 45
     │
     ▼
  Fix options:
  1. Wrap counter access with sync.Mutex
  2. Use atomic.Int64 (for simple counters)
  3. Restructure to use channels (share memory by communicating)
```

## Source Code Reading Guide

**runtime/proc.go**: This is the Go scheduler. Start with `schedule()` — the function that picks the next goroutine to run. Then `findrunnable()` — how the scheduler finds work when a P is idle (checks global run queue, local run queue, netpoller, steals from other Ps). `newproc()` — how goroutines are created. `goexit0()` — what happens when a goroutine finishes. These four functions explain 90% of goroutine behavior. Skip the OS-level thread management (`newm`, `handoffp`) unless you're debugging at the OS level.

**runtime/mgc.go**: The garbage collector. Start with `gcStart()` — what triggers a GC cycle (heap growth, timer, explicit call). Then `gcBgMarkWorker()` — the concurrent marking goroutines. `gcMarkTermination()` — the stop-the-world mark termination phase. `gcControllerState` — the pacing algorithm that determines when to start the next GC. Skip the write barrier implementation (`runtime/mbarrier.go`) unless you're debugging compiler issues.

**net/http/client.go and transport.go**: The HTTP client. `Client.Do()` → `Transport.RoundTrip()` → `persistConn.roundTrip()`. The `readLoop()` and `writeLoop()` goroutines show how persistent connections are managed. `body.close()` and `body.Close()` show the difference between consuming and discarding the body. `Client.CloseIdleConnections()` shows how to force-close pooled connections.

**database/sql/sql.go**: `DB.conn()` for connection acquisition, `putConn()` for connection return, `Tx.Commit()` and `Tx.Rollback()` for transaction lifecycle. The `connRequest` channel shows how goroutines wait for connections.

**runtime/pprof/pprof.go**: The pprof registration. `pprof.Lookup("goroutine")` returns the goroutine profile. `pprof.StartCPUProfile()` begins CPU profiling. `pprof.WriteHeapProfile()` snapshots the heap.

**Reading order**: 1. `runtime/proc.go` (scheduler) → 2. `runtime/mgc.go` (GC) → 3. `net/http/client.go` (HTTP client goroutines) → 4. `database/sql/sql.go` (connection management) → 5. `runtime/pprof/pprof.go` (profiling API) → 6. `runtime/trace.go` (execution tracer).

**What to skip**: The assembler code in `runtime/rt0_*.s`, platform-specific files (`runtime/os_linux.go`, `runtime/sys_linux_amd64.s`), the race detector implementation (`runtime/race/`), and the pprof web UI code (`net/http/pprof/` — just the exported handlers).

## Production Failure Scenarios

**Scenario 1: Goroutine leak from HTTP client without timeout in a background worker.** A service handles 1,000 req/s on Chi with p99 latency of 15ms. After 3 days of uptime, p99 spikes to 5 seconds, then 30 seconds, then the service becomes unresponsive. The goroutine count has grown from 50 to 500,000. The goroutine profile shows 498,000 goroutines in state `[IO wait]`, all in `net.(*netFD).Read` with `net/http.(*persistConn).readLoop` in the stack. Tracing up: they're all from `external/partner.(*Client).SendWebhook()` — a background worker that sends webhooks to a partner API. The partner API occasionally hangs (doesn't close the connection), and the HTTP client has no timeout configured (`http.Client{Timeout: 0}`), so these goroutines block forever. Every 6 hours, the worker flushes a batch, creating ~5,000 new goroutines, 1% of which hang on the partner. Fix: `http.Client{Timeout: 30 * time.Second}`. Add monitoring: alert if goroutine count > 1000.

**Scenario 2: OOM kill from []byte slice retention.** A file upload service handles 100MB files. The handler reads the entire body with `io.ReadAll(r.Body)`, stores a copy in the database, then returns. Memory usage grows from 500MB to 8GB over 6 hours, and the OOM killer terminates the pod. Heap profile (`--inuse_space`) shows 7GB held in `io.ReadAll` stack traces, but looking deeper — the slices are from requests that completed hours ago. Root cause: the developer slices the byte array for multipart parsing: `part := body[headerEnd:bodyEnd]`. This creates a slice backed by the original 100MB array, so the 100MB array cannot be GC'd until all slices from it are released. Fix: `part := make([]byte, bodyEnd-headerEnd); copy(part, body[headerEnd:bodyEnd])`. Or use `bytes.Clone()` (Go 1.20+). Add a body size limit middleware: `http.MaxBytesReader(w, r.Body, 10<<20)`.

**Scenario 3: Panic not recovered kills the server without cleanup.** A Chi service with 4 handlers. One handler, handling 5% of traffic, has a nil pointer dereference in an edge case: `order.Customer.Name` where `order.Customer` is nil. Without `middleware.Recoverer`, the panic propagates up the HTTP handler goroutine stack, hits the top-level `ServeHTTP` in `net/http`, and the goroutine crashes. But `net/http` has a built-in recovery that logs the panic and closes the connection — it does not crash the server. However, the connection is closed ungracefully, the client sees `connection reset by peer`, and the goroutine that was serving that request leaks all its resources (database connection, any mutexes held, any channels it was writing to). Over time, 5% of requests leaking database connections creates connection pool exhaustion. With `middleware.Recoverer` at the top of the middleware stack (first middleware listed), the panic is caught, a 500 is returned, and the goroutine continues normally, returning its connection to the pool. The stack trace is logged at ERROR level with the panic value and the full goroutine stack.

## Debugging Techniques

**Technique 1: On-demand goroutine dump via SIGQUIT.** When a Go process receives `SIGQUIT`, it prints the stack trace of every goroutine to stderr and continues running (unlike SIGKILL). This is the fastest way to understand what your service is doing right now — no pprof endpoint needed, no config changes. Run: `kill -SIGQUIT <pid>`. The output (typically 50MB+ for 100K goroutines) goes to stderr, which your process manager (systemd, docker) captures. Parse with: `curl localhost:6060/debug/pprof/goroutine?debug=2` for the same data via HTTP. Look for: (1) goroutines with identical stack traces and high count — these are leaks; (2) goroutines waiting on the same channel/mutex — identifies contention; (3) goroutines in `[syscall]` with long durations — identifies slow I/O operations.

**Technique 2: Live heap profiling without restarts.** The heap profiler has near-zero overhead when not actively dumping. You can trigger a heap dump at any time: `curl -o heap.prof http://localhost:6060/debug/pprof/heap`. This captures a snapshot of all live allocations. To compare before/after (find what was allocated in a time window), capture two dumps and use `go tool pprof -base=before.prof after.prof`. This shows only allocations that appeared between the two snapshots — powerful for finding what a specific request or batch job is allocating. The `--inuse_space` sample shows live bytes; `--inuse_objects` shows live object count; `--alloc_space` shows cumulative allocated bytes (including freed); `--alloc_objects` shows cumulative object count. Use `--inuse_space` for memory leaks; use `--alloc_space` for allocation hot spots.

**Technique 3: Execution tracer for latency anomalies.** When a single request sometimes takes 5 seconds (p99) while p50 is 10ms, pprof profiles (which aggregate) won't show the slow path clearly. The execution tracer captures complete timelines. Run: `curl -o trace.out http://localhost:6060/debug/pprof/trace?seconds=30`. This record 30 seconds of all goroutine events. Open with `go tool trace trace.out`. In the viewer: click "Goroutine analysis" to see all goroutines grouped by function. Find the handler goroutines that took >1 second — click on one to see its timeline. You'll see the goroutine blocked in `sync.Mutex.Lock` for 4.9 seconds while another goroutine held the mutex. Click on the goroutine holding the mutex to see what it was doing — perhaps a slow database query inside a mutex-protected code block (which should never happen; mutexes should not be held across I/O operations).

**Technique 4: Benchmark-based race detection.** A race that only appears under load can be reproduced with benchmarks: `go test -run=^$ -bench=. -benchtime=10s -race ./pkg/critical/`. This runs the benchmark for 10 seconds with the race detector enabled, exposing races that require many iterations to trigger. For HTTP handler races, use `net/http/httptest` to simulate concurrent requests:

```go
func TestHandlerRace(t *testing.T) {
    h := NewHandler()
    var wg sync.WaitGroup
    for i := 0; i < 1000; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            req := httptest.NewRequest("POST", "/endpoint", strings.NewReader(body))
            rec := httptest.NewRecorder()
            h.ServeHTTP(rec, req)
        }()
    }
    wg.Wait()
}
```

Run with `go test -race -run TestHandlerRace` and the race detector analyzes all 1000 concurrent requests for races.

## Observability Considerations

**Log**: At ERROR level, log every panic recovery with the full goroutine stack: `slog.ErrorContext(ctx, "panic recovered", "panic", r, "stack", string(debug.Stack()))`. At WARN level, log when `runtime.NumGoroutine()` exceeds a threshold (e.g., 10000). Log slow requests (exceeding 5x p99) with method, path, duration, and the full request context (user ID, trace ID). Include `runtime.ReadMemStats(&m)` in a periodic background goroutine (every 30s) and log at INFO level: `HeapAlloc`, `HeapSys`, `NumGC`, `NumGoroutine`.

**Metrics**: Export these runtime metrics via `runtime.ReadMemStats` and Prometheus: `go_goroutines` (gauge — current count), `go_memstats_alloc_bytes` (gauge — currently allocated), `go_memstats_heap_alloc_bytes` (gauge), `go_memstats_heap_sys_bytes` (gauge — total obtained from OS), `go_memstats_gc_cpu_fraction` (gauge — fraction of CPU spent in GC), `go_gc_duration_seconds` (summary — GC pause durations), `process_open_fds` (gauge), `process_max_fds` (gauge). Set up alerts: `go_goroutines > 10000` (WARNING), `go_goroutines > 50000` (CRITICAL), `rate(go_memstats_alloc_bytes[1h]) > 100MB` (leak detection), `go_gc_duration_seconds.quantile(0.99) > 0.1` (GC pressure).

**Traces**: Instrument all HTTP client calls and database calls with spans. When a panic is recovered, record the event on the current span with `span.RecordError(err)` and set `span.SetStatus(codes.Error, "panic")`. This lets you trace from the client's 500 error to the exact handler line that panicked. For goroutine leaks, create a span in every `go func()` call that represents the spawned goroutine's lifecycle — this makes it visible in traces that work is being done but not completing.

**Alerts**: Multi-burn-rate alerts for error rate and latency. Goroutine growth alert: `go_goroutines / go_goroutines offset 1h > 2` (goroutines doubled in 1 hour). Memory growth alert: `predict_linear(go_memstats_heap_alloc_bytes[1h], 4*3600) >= go_memstats_heap_sys_bytes * 0.9` (heap will hit system limit in 4 hours at current growth rate). OOM prediction: `go_memstats_heap_alloc_bytes / go_memstats_heap_sys_bytes > 0.85` sustained for 10 minutes.

## Performance Implications

**Goroutine profiling overhead.** A goroutine dump (`debug=2`) for 100K goroutines can take 1-3 seconds and generate 50-200MB of output. The `debug=1` format (summary counts by state + first N stacks) is much lighter (a few KB). In production, prefer `debug=1` for health checks and `debug=2` only when investigating. The goroutine profile blocks all goroutines briefly to capture their stacks — at 10K goroutines, this is <1ms; at 500K goroutines, it can be 10-50ms. This is usually fine as an on-demand debugging tool but don't run it continuously.

**Race detector impact.** The race detector slows execution by 5-10x and increases memory by 5-10x. A service that handles 1000 req/s normally will handle 100-200 req/s with `-race`. This makes it unsuitable for production or even staging load tests. Use it in integration tests where you can afford the slowdown, and in a separate CI job that runs offline tests with `-race`. The race detector also changes scheduling timing, which can mask race conditions (the observer effect) — a clean run under `-race` doesn't guarantee no races exist; it only guarantees no races were detected in that particular execution.

**SIGQUIT behavior.** When a Go process receives SIGQUIT, it stops all goroutines (not the world, just prevents new scheduling) and prints their stacks to stderr. For a service with 500K goroutines, this produces ~200MB of output and takes 10-30 seconds. During this time, the service is unresponsive — no requests are processed. SIGQUIT should be used for emergency diagnostics, not routine monitoring. If your orchestrator (Kubernetes) sends SIGQUIT before SIGKILL for pod termination, it will cause 10-30 seconds of extra shutdown time.

**`runtime.ReadMemStats` STW pause.** Calling `runtime.ReadMemStats(&m)` stops the world momentarily to read GC-internal counters consistently. In Go 1.19+, this pause is <1μs. However, `runtime.ReadMemStats` also triggers GC metrics updates, which has a small cost. It's safe to call every 10-30 seconds in a metrics collection goroutine. Don't call it per-request.

## Architecture Implications

The difference between a service that takes 30 minutes to debug at 3 AM and one that takes 30 seconds is the `net/http/pprof` import. Add it behind a separate internal-only port (e.g., `:6060`) that's not exposed to the internet: `go func() { log.Println(http.ListenAndServe("localhost:6060", nil)) }()`. The `net/http/pprof` import registers handlers at `/debug/pprof/` on the default mux, which only `localhost:6060` can reach. This gives you heap, goroutine, CPU, trace, and mutex profiles with zero additional code. The overhead is negligible — the pprof handlers do nothing until invoked.

Every `go func()` call in your codebase should start a goroutine that: (a) recovers from panics at the top level (a panicked goroutine crashes the entire program if not recovered), (b) logs its start and completion at DEBUG level, (c) accepts a context and respects its cancellation, and (d) uses a `defer` to signal a `sync.WaitGroup` (so graceful shutdown knows when the goroutine is done). Build a utility: `pkg/safego.Go(ctx, func() error { ... })` that wraps all four behaviors. Ban raw `go func()` calls via a linter rule. This prevents the most common production failure type in Go services: the leaked goroutine you didn't know existed.

Your Chi middleware stack MUST include `middleware.Recoverer` as the FIRST middleware (outermost), and `middleware.Timeout(30 * time.Second)` as the SECOND. `Recoverer` catches panics from downstream middleware and handlers. `Timeout` ensures no request runs longer than 30 seconds — this bounds goroutine lifetime and prevents slow leaks from accumulating. Place `Recoverer` first so that if `Timeout`'s goroutine panics (unlikely but possible), it's still caught. The order matters: the outermost middleware wraps the next one, so `Recoverer(Timeout(handler))` catches panics from timeout propagation.

## Team Ownership Implications

On-call engineers must be able to run `pprof` commands during an incident without escalating to a senior engineer. This means runbooks must contain concrete, copy-paste-ready commands: `curl -o goroutine.txt "http://localhost:6060/debug/pprof/goroutine?debug=2"`, `go tool pprof -top -inuse_space http://localhost:6060/debug/pprof/heap`, `curl "http://localhost:6060/debug/pprof/trace?seconds=5" -o trace.out && go tool trace trace.out`. The team that owns the service owns the runbook, and a runbook that says "debug with pprof" without explicit commands is incomplete.

Every team should run a chaos engineering exercise once per quarter where a senior engineer injects a goroutine leak, a memory leak, or a connection pool exhaustion into a staging service, and on-call engineers must diagnose and fix it using only the runbook and monitoring dashboards. This converts theoretical knowledge into muscle memory. Record the time-to-diagnose and time-to-fix, and track improvement quarter-over-quarter. A team that can diagnose a goroutine leak in <5 minutes in staging will do it in <15 minutes with production pressure — acceptable for an incident. A team that's never done it will take 2+ hours and likely cause a secondary incident through misconfigured mitigation.

## Interview Questions

**Q1: A Go service has been running for 48 hours. Its goroutine count has grown from 100 to 50,000. The CPU is at 20%, memory is stable at 2GB. Requests are timing out. What's the likely cause and how do you diagnose it?**
Answer: This is a classic goroutine leak. The stable memory and CPU rule out GC pressure and CPU-bound work. The timeout symptom suggests goroutines are stuck (not running), because running goroutines would complete requests. Diagnosis: (1) `curl localhost:6060/debug/pprof/goroutine?debug=1` to see goroutine state distribution. If ~90% are `[IO wait]`, goroutines are blocked on network I/O — likely unclosed HTTP response bodies or database connections. If ~90% are `[chan receive]`, goroutines are blocked on channels — likely a writer goroutine crashed or a channel is full and no reader exists. If ~90% are `[sync.Mutex.Lock]`, there's a deadlock or extreme mutex contention. (2) Take a snapshot with `debug=2` and group goroutines by their stack trace. The most frequent identical stack trace is the leak source. (3) Trace up the stack to find the application code that created these goroutines (`go func()` call site). The fix depends on the mechanism: add `defer resp.Body.Close()` for HTTP leaks, fix channel lifecycle for channel leaks, or reduce mutex hold time for contention leaks.

**Q2: What's the difference between `--inuse_space` and `--alloc_space` in `go tool pprof`? When would you use each?**
Answer: `--inuse_space` shows bytes currently allocated and not yet freed — this is what's live in the heap. `--alloc_space` shows cumulative bytes allocated since the program started, regardless of whether they've been freed. Use `--inuse_space` to find memory leaks (a single allocation site accounting for 80% of live heap means that's what's holding the memory). Use `--alloc_space` to find allocation hot spots (the allocation site where the most garbage is generated). A function that allocates 1MB per call, called 1000 times per second, will be #1 in `--alloc_space` but might be #1 in `--inuse_space` only if those allocations are retained. If they're freed immediately (short-lived), `--inuse_space` won't show them, but they still cause GC pressure and should be optimized with `sync.Pool`. For OOM debugging, start with `--inuse_space`. For GC tuning, start with `--alloc_space`.

**Q3: You add `-race` to `go test` in CI and get 3 data race reports. How do you interpret a race report and determine which part of the code to fix?**
Answer: Each race report has two access types (Read/Write) and two stack traces. The report says: "Write at 0x... by goroutine N" and "Previous read at 0x... by goroutine M". The memory address `0x...` identifies the exact variable being raced on. Both stack traces show the line of code doing the access AND how those goroutines were created (all the way up to `main` or `go func()`). The fix depends on the access pattern: (a) If the variable is a simple counter, replace with `atomic.Int64` / `atomic.AddInt64`. (b) If the variable is a struct being read and written, protect accesses with `sync.Mutex` or `sync.RWMutex`. (c) If goroutines are expected to communicate via this variable, redesign to use channels — "share memory by communicating, don't communicate by sharing memory." The fix should go where the data is owned, not where each goroutine happens to access it. If the variable is shared across packages, it likely needs a synchronization primitive exported by the owning package, or a restructured API that eliminates the need for shared mutable state.

**Q4: Your service handles 5000 req/s. The p50 latency is 5ms, p99 is 100ms, but every 5 minutes there's a 2-second spike. How do you find the cause?**
Answer: First, check if the spike correlates with GC: `GODEBUG=gctrace=1` and look for GC cycles that coincide with the spikes. If GC pause durations are >100ms, the heap is too large or the allocation rate is too high. If not GC, try the execution tracer: `curl -o trace.out localhost:6060/debug/pprof/trace?seconds=30` and look at "Goroutine analysis" for handler goroutines that took >1 second. The trace shows exactly what the goroutine was doing during the spike — blocked on mutex, blocked on I/O, or actually running CPU. If blocks are on `sync.Mutex.Lock`, look at which goroutine held the mutex and what it was doing — likely a periodic background job (cleanup, sync, export) that runs every 5 minutes and acquires a lock needed by request-handling goroutines. Fix: make the background job use shorter critical sections, switch to `sync.RWMutex` if appropriate, or move the work to a separate service. If blocks are on network I/O, use distributed tracing to find the downstream service that's intermittently slow.

**Q5: What are the three most important HTTP server timeouts to set, and what happens if you don't set each one?**
Answer: (1) **ReadHeaderTimeout**: `srv.ReadHeaderTimeout = 5 * time.Second`. Without this, a slow-loris attacker can send headers one byte per second, holding a connection and its goroutine indefinitely. Each connection consumes a goroutine and a file descriptor. A single attacker can exhaust your goroutine limit (typically 1M), causing the service to accept no new connections. (2) **ReadTimeout**: `srv.ReadTimeout = 30 * time.Second`. Without this, a client that sends a request body very slowly (or pauses during upload) holds the goroutine for arbitrarily long. Even without malice, mobile clients on flaky networks can cause this. (3) **IdleTimeout**: `srv.IdleTimeout = 120 * time.Second`. Without this, keepalive connections from clients that have disappeared (mobile app backgrounded, laptop closed) accumulate until you run out of file descriptors. Additionally, `WriteTimeout` (`srv.WriteTimeout = 30 * time.Second`) controls how long the server will wait to finish writing the response. The key insight: all four timeouts should be set explicitly; Go's zero defaults make the server vulnerable to resource exhaustion.

## Hands-On Exercises

**Exercise 1: Inject and diagnose a goroutine leak.** Write a Chi handler that starts a goroutine for each request and intentionally leaks 1% of them (e.g., by writing to a channel that no one reads from). Run the service and send 10,000 requests with `hey -n 10000 -c 100 http://localhost:3000/leaky`. Observe the goroutine count increase: `curl localhost:6060/debug/pprof/goroutine?debug=1`. Identify the leak by looking at goroutine state and stack traces. Fix the leak by adding a `select` with a timeout and a log. Write a monitoring goroutine that alerts when goroutine count exceeds a threshold. Run the load test again and verify the alert fires and the goroutine count drops back after the leak is fixed.

**Exercise 2: Reproduce and fix a data race.** Write a simple in-memory counter that's shared across all HTTP handlers: `var counter int64` (or `int` for the bug). Write a handler that increments it and returns the new value. Write a test that fires 1000 concurrent requests at the handler. Run with `go test -race -run TestCounterRace -count=10`. Observe the race detector output. Fix with `atomic.AddInt64(&counter, 1)`. Re-run to confirm zero races. Then: change the handler to use a `map[string]int` (shared, no mutex). Run the race detector — it should report races. Fix with `sync.RWMutex`. Confirm.

**Exercise 3: Profile a handler that allocates excessively.** Write a handler that JSON-encodes a large response using `json.Marshal` for each request (allocating a new `[]byte` each time). Benchmark with `go test -bench=BenchmarkHandler -benchmem`. Observe ~50KB per request. Profile with `go test -memprofile=mem.out -bench=BenchmarkHandler`. Open with `go tool pprof -http=:8080 mem.out` and identify `json.Marshal` as the top allocator. Refactor to use `json.NewEncoder(w).Encode(v)` which streams directly to the response writer without allocating the full JSON buffer. Re-benchmark and confirm allocations dropped. Bonus: add a `sync.Pool` of `bytes.Buffer` for assembling response bodies that need preprocessing before JSON encoding.

## Advanced Challenges

**Challenge 1: Implement a goroutine lifecycle tracker that detects leaked goroutines in production.** Requirements: (a) Wrap `go func()` with a tracker that records: goroutine ID, creation time, stack trace at creation, and a user-provided label (e.g., "worker:kafka-consumer"). (b) Every N seconds, export the count and the 10 oldest goroutines to Prometheus metrics and to structured logs. (c) Detect leaked goroutines by pattern: if a labeled goroutine type's count has been monotonically increasing for M minutes (configurable per label), fire an alert. (d) Provide a `/debug/goroutine-leaks` endpoint that returns JSON with all labeled goroutine counts and the oldest N creation times, sorted by age. (e) Use `runtime.Stack()` to capture creation stacks — these are large (~4KB each) but only needed for a sample of goroutines, not all. (f) Ensure the tracker itself has minimal overhead: use atomic counters for fast path (goroutine start/finish), sampling for stack capture (1 in 1000 goroutines).

**Challenge 2: Build a chaos engineering tool that injects failures into a running Chi service on demand.** Requirements: (a) `/debug/chaos/goroutine-leak?rate=0.01` — starts 1% of subsequent requests with a goroutine that never finishes (blocked on channel). `/debug/chaos/goroutine-leak?rate=0` — stops injecting. (b) `/debug/chaos/memory-leak?mb_per_sec=10` — allocates 10MB/s but never releases it. (c) `/debug/chaos/latency?p99_ms=5000` — adds random delay to 1% of requests to simulate slow downstream. (d) `/debug/chaos/status` — reports currently active chaos injections and their parameters. (e) All endpoints require authentication (chaos-never-hits-production). (f) After injection, demonstrate that your monitoring dashboards detect each failure mode: goroutine growth alert for goroutine leak, heap growth alert for memory leak, latency SLO burn rate alert for latency injection. This challenge tests whether your observability stack can detect the failures you designed it to detect.

## Key Insights

- The default `http.Server` timeouts in Go are zero (infinite) — a single malicious client can hold a goroutine and file descriptor forever; set `ReadHeaderTimeout`, `ReadTimeout`, `WriteTimeout`, and `IdleTimeout` on every server
- `pprof` is the universal debugging tool for Go — CPU profile for "why is it slow", heap profile for "why is memory growing", goroutine profile for "why are requests timing out", execution tracer for "why does this one request take 2 seconds"
- A goroutine leak is the most common production Go failure mode — it's always caused by a goroutine blocking indefinitely (on a channel, network I/O, or mutex) while the thing that would unblock it has stopped existing
- `runtime.ReadMemStats` is safe to call every 30 seconds in a metrics goroutine (pause <1μs in Go 1.19+), and exporting `NumGoroutine` as a metric gives you goroutine leak detection for free
- The race detector (`-race`) catches actual data races, not potential ones — if it reports a race at line X between goroutines A and B, that exact memory location was accessed unsynchronized during that test run
- SIGQUIT (`kill -SIGQUIT <pid>`) dumps all goroutine stacks to stderr without killing the process — this is the emergency diagnostic of last resort when pprof HTTP endpoints are unreachable
- `middleware.Recoverer` must be the FIRST (outermost) middleware in your Chi stack — any panic from any middleware or handler must be caught and turned into a 500, not allowed to crash the goroutine and leak its resources
