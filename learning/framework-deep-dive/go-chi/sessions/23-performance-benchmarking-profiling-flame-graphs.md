# Session 23: Performance — Benchmarking, Profiling, pprof, Flame Graphs

## Why This Topic Exists

Go services have a reputation for being fast out of the box, and that reputation is earned — the Go compiler produces native code, the garbage collector is concurrent and low-latency, and goroutines are cheap enough that a single process can handle tens of thousands of concurrent connections. But "fast enough" is a moving target. A handler that takes 2ms in isolation might be fine until you have 500 concurrent requests, at which point GC pressure, lock contention, and memory bandwidth become your bottleneck. Knowing that your service is slow is easy — your p99 latency graph tells you. Knowing why it is slow and exactly what to change is a different skill entirely.

Go provides a world-class suite of profiling and benchmarking tools built into the standard library and toolchain. `go test -bench` gives you precise, repeatable microbenchmarks. `pprof` profiles CPU, heap, goroutines, mutexes, and blocking operations with near-zero configuration. The execution tracer (`go tool trace`) captures every goroutine scheduling event, GC cycle, and network poll operation. Flame graphs visualize all of this in a single interactive view where you can drill from a 30-second service profile down to the exact line of Go source code consuming CPU cycles.

Staff engineers use these tools not just to fix performance bugs but to build a performance culture. That means every critical path has a benchmark that runs in CI and blocks regressions, every service exports pprof endpoints for on-demand profiling in production, and every developer knows how to read a flame graph and identify the difference between "this function is slow" and "this function is called 10,000 times more than expected." The tools are the easy part — the discipline to measure before optimizing, to profile before guessing, and to verify optimizations with benchmarks before deploying is what separates a performance-informed team from one that cargo-cults "use sync.Pool" without understanding why.

## Mental Model

Think of performance work as a funnel with three stages. Stage 1 is **benchmarking**: you write a Go benchmark function that exercises a specific code path (a handler, a query, a serialization step) and measure its throughput, latency, and allocations. Benchmarks give you a controlled, repeatable baseline — you know exactly what you are measuring and can detect a 5% regression with confidence. But benchmarks are synthetic; they don't tell you how the code behaves under real load with real data.

Stage 2 is **profiling**: you collect data from a running service — either from a benchmark (`go test -cpuprofile`) or from a production service (`/debug/pprof/profile`) — and analyze it to find where time and memory are spent. Profiling answers "what is this service actually doing?" as opposed to "what do I think it is doing?" A CPU profile of a handler you think is I/O-bound might show 40% of time in JSON serialization, which is actionable. A heap profile might show that 80% of allocations come from a logging call you assumed was negligible.

Stage 3 is **optimization**: you make a targeted code change, re-benchmark to confirm improvement, and re-profile to verify that you didn't shift the bottleneck somewhere else. The key insight: **never optimize without benchmarking first, and never optimize without profiling**. Guessing is how you spend two days implementing a `sync.Pool` that makes the code more complex but provides zero improvement because the allocation wasn't the bottleneck — the network round-trip was.

```
                     PERFORMANCE OPTIMIZATION FUNNEL

  +------------------------------------------------------------------+
  | 1. BENCHMARK                                                      |
  |    go test -bench=. -benchmem                                     |
  |    -> Establishes baseline: X ns/op, Y B/op, Z allocs/op          |
  |    -> Runs in CI, blocks regressions                              |
  +------------------------------+-----------------------------------+
                                 |
                                 v
  +------------------------------------------------------------------+
  | 2. PROFILE                                                        |
  |    go test -cpuprofile=cpu.out -bench=.                            |
  |    go tool pprof -http=:8080 cpu.out                               |
  |    -> Identifies hot functions (CPU)                               |
  |    -> Identifies allocation sites (heap)                           |
  |    -> Identifies blocking calls (block/mutex)                      |
  +------------------------------+-----------------------------------+
                                 |
                                 v
  +------------------------------------------------------------------+
  | 3. OPTIMIZE                                                       |
  |    Make targeted change to hottest function                        |
  |    -> Examples: sync.Pool, preallocate slice, batch writes        |
  +------------------------------+-----------------------------------+
                                 |
                                 v
  +------------------------------------------------------------------+
  | 4. VERIFY                                                         |
  |    Re-run benchmark -> confirms improvement                        |
  |    Re-run profile   -> confirms bottleneck moved                   |
  |    Run with -race   -> confirms no data races introduced          |
  +------------------------------------------------------------------+


  LATENCY BREAKDOWN MENTAL MODEL:

  Total request latency = sum of:

     +-------+  Chi routing + middleware overhead
     | 50us  |  (radix tree lookup, middleware execution)
     +-------+
     +-------+  Handler logic
     | 200us |  (business logic, validation, computation)
     +-------+
     +-------+  Repository / Database
     | 5ms   |  (connection acquisition + query execution + scanning)
     +-------+
     +-------+  External API calls
     | 2ms   |  (HTTP round-trip to downstream service)
     +-------+
     +-------+  Serialization
     | 300us |  (JSON marshal/unmarshal, protobuf encode/decode)
     +-------+
     +-------+  GC assist time
     | 50us  |  (goroutine helps GC mark phase during allocation)
     +-------+

  The profiling approach: measure each layer independently, find the
  largest slice, and optimize there. Optimizing 200us of handler logic
  when 5ms is spent in the database yields a 0.4% improvement.
  Optimizing the database query yields a potential 5ms improvement.
```

## Internal Architecture

**Go benchmark functions.** A benchmark is a function with signature `func BenchmarkXxx(b *testing.B)` in a `_test.go` file. The framework calls the function with `b.N` set to 1, measures the time, and increases `b.N` until the benchmark runs for at least 1 second (by default) or until the measurement stabilizes. The report shows: `BenchmarkHandler-8    50000    23456 ns/op    1234 B/op    12 allocs/op` — where `-8` is GOMAXPROCS, `50000` is iterations, `23456 ns/op` is nanoseconds per operation, `1234 B/op` is bytes allocated per operation, and `12 allocs/op` is allocations per operation. The `B/op` and `allocs/op` columns require `-benchmem` flag. Key patterns: `b.ResetTimer()` to exclude setup time, `b.StopTimer()` / `b.StartTimer()` to pause timing during setup steps within the loop, `b.ReportAllocs()` to always report allocation stats, `b.RunParallel(func(pb *testing.PB) { for pb.Next() { ... } })` to benchmark concurrent throughput.

**pprof CPU profiling internals.** The CPU profiler works by registering a signal handler for `SIGPROF` (on Unix). The operating system delivers `SIGPROF` at a configurable rate (default 100Hz = every 10ms). The signal handler records the current program counter and unwinds the stack by walking frame pointers. Each sample records the full call stack. After the profiling period ends, these samples are aggregated: each function's "flat" time is the number of samples where that function was at the top of the stack (currently executing), and each function's "cumulative" time is the number of samples where that function appears anywhere in the stack (including time spent in functions it called). The profile is written in pprof's protobuf format and can be analyzed with `go tool pprof`. Profiling overhead is roughly proportional to the sampling rate: at 100Hz, overhead is ~2-5% CPU. You can increase the rate with `runtime.SetCPUProfileRate(hz)` for finer-grained profiles.

**pprof heap profiling internals.** The heap profiler uses a sampling approach controlled by `runtime.MemProfileRate` (default 512KB). Every time `MemProfileRate` bytes have been allocated, the allocator records a sample: the stack trace of the allocation site and the size of the allocation. This means small, frequent allocations are sampled with lower probability than large, infrequent ones. The profile stores two views: `inuse_space` (bytes currently allocated and not freed) and `alloc_space` (cumulative bytes allocated over the program lifetime, including freed memory). `inuse_objects` and `alloc_objects` show object counts instead of bytes. When you trigger a heap dump (`/debug/pprof/heap`), the runtime walks all spans, identifies live objects, and writes a profile. For `inuse_*` views, only live objects are included. For `alloc_*` views, the runtime uses a separate cumulative counter that persists across GC cycles. A key detail: because of sampling, the reported values are statistical estimates, not exact counts — but they are accurate enough for identifying the dominant allocation sites.

**pprof goroutine profiling.** The goroutine profiler captures the stack trace and state of every goroutine at the moment the profile is taken. Unlike CPU profiling (which samples over time), goroutine profiling is a snapshot. The runtime suspends each goroutine, captures its stack, and records its state: `running` (currently on a P), `runnable` (waiting for a P), `waiting` (blocked on channel, mutex, I/O, timer, etc.), `syscall` (in a system call). The profile groups goroutines by their stack trace, so you see counts like: `50 goroutines @ main.handleRequest / handler.go:45 (waiting for channel receive)`. This is the primary diagnostic for goroutine leaks: if the goroutine count is growing, the goroutine profile shows which stack traces account for the most goroutines, and you trace back to find the `go func()` call that creates them without proper termination.

**Mutex and block profiling.** Mutex profiling (`go test -mutexprofile=mutex.out`) samples contended mutex operations. Each time a goroutine blocks on a mutex for longer than `runtime.SetMutexProfileFraction(n)` units, a sample is recorded with the stack trace and block duration. The default fraction is 0 (disabled); set to 1 to profile every contended lock, or higher to sample. Block profiling (`go test -blockprofile=block.out`) samples goroutine blocking on channel operations, network I/O, and system calls. Each blocking event longer than `runtime.SetBlockProfileRate(n)` nanoseconds is recorded. The default rate is 0 (disabled). Both profiles in `go tool pprof` show call sites where goroutines spend the most time blocked — critical for finding concurrency bottlenecks.

**Execution tracer (`go tool trace`).** While pprof profiles aggregate statistical samples, the execution tracer captures a complete timeline of events. It records: goroutine creation, blocking, and unblocking; GC start, mark assist, and sweep events; network poll blocking; system call entry and exit; and heap goal changes. The trace file is generated with `runtime/trace` in application code (`trace.Start(w)` / `trace.Stop()`) or via the `/debug/pprof/trace?seconds=5` HTTP endpoint. Running `go tool trace trace.out` opens a web browser with several views: (a) Timeline — Gantt chart showing each goroutine's state over time, (b) Goroutine analysis — groups goroutines by function and shows execution statistics, (c) Network blocking — which goroutines blocked on network I/O and for how long, (d) Synchronization blocking — which goroutines blocked on channels or mutexes, (e) GC events — timeline of GC cycles with heap size.

**Flame graphs in pprof's web UI.** The `-http` flag in `go tool pprof -http=:8080 profile.out` opens a web server with an interactive UI. The Flame Graph view stacks frames vertically: the bottom of each column is the root of the call tree (e.g., `main.main`), and the top is the leaf function currently executing. The width of each frame is proportional to the CPU samples that include that frame. Clicking a frame filters the view to show only the subtree rooted at that frame, allowing drill-down analysis. The Top view shows a sortable table of functions by flat or cumulative CPU time. The Graph view shows a call graph with node size proportional to CPU time. The Source view shows the annotated source code with per-line CPU samples. The key workflow: open Flame Graph, scan for wide frames (hot functions), click to zoom in, switch to Top to see exact percentages, switch to Source to see which lines within the function are hot.

## Runtime Behavior

When you run `go test -bench=BenchmarkHandler -benchmem -cpuprofile=cpu.out -memprofile=mem.out`, the benchmark framework first determines a stable `b.N` by running the benchmark repeatedly with increasing `b.N` until the execution time per iteration stabilizes (usually 3-5 seconds total warmup). During the final timing runs, the CPU profiler is active: every 10ms, a SIGPROF signal fires, the signal handler captures the program counter and stack, and the sample is buffered. After the benchmark completes, the profile is written to `cpu.out`. Meanwhile, the heap profiler samples one in every 512KB of allocations, recording the allocation site stack. The memory profile captures both cumulative allocations and in-use memory at the end of the benchmark.

When you open the CPU profile with `go tool pprof -http=:8080 cpu.out`, pprof parses the protobuf file and builds an in-memory call graph. It resolves function names by reading the binary's symbol table, maps addresses to source file:line, and caches the results. In the flame graph view, each frame is rendered as a colored rectangle. The color is arbitrary but typically indicates the package (standard library vs. your code vs. vendored). A wide frame at the top means a leaf function consuming significant CPU directly (e.g., `regexp.(*Regexp).Find` if your handler compiles a regex on every request). A wide frame deep in the stack means a high-level function whose time is spread across many callees — common in HTTP handlers where time is distributed across middleware, routing, business logic, and serialization.

When you open the heap profile with `pprof --inuse_space`, the profile shows what is currently allocated. Let us say 80% of in-use memory is in `io.ReadAll` — that means your service is reading entire request bodies into memory and not releasing them. When you switch to `--alloc_space`, you see cumulative allocations — a function that allocates 1GB total but retains only 1MB is an allocation hot spot (generating GC pressure) but not a leak. The distinction is critical: high `alloc_space` + low `inuse_space` = allocation churn (use `sync.Pool`); high `inuse_space` = memory leak or unbounded growth (fix the data structure).

When you run the execution tracer with `curl -o trace.out "http://localhost:6060/debug/pprof/trace?seconds=5"`, the tracer records events to an internal ring buffer during the 5-second window. After 5 seconds, the buffer is flushed to the HTTP response. The file size is approximately: (number of goroutines) * (goroutine events per second) * (seconds) * (~50 bytes per event). For a service handling 1,000 req/s with 100 active goroutines, 5 seconds of tracing generates ~5-10MB. Opening with `go tool trace trace.out`, the Timeline view shows a Gantt chart: the X axis is time, the Y axis lists goroutines by ID, and each goroutine's bar shows its state (green = running, orange = runnable, blue = sleeping, pink = waiting). A goroutine that stays pink for a long time is blocked. You can click the pink region to see the stack trace at the point it blocked — this is how you find that a handler goroutine spent 4.9 seconds in `net.(*netFD).Read` waiting for a slow downstream service.

## Flow Diagrams

```
BENCHMARK -> PROFILE -> OPTIMIZE WORKFLOW

  STEP 1: BENCHMARK THE BASELINE

    $ cat handler_test.go
    func BenchmarkGetOrder(b *testing.B) {
        h := setupHandler()       // Chi router with all middleware
        req := httptest.NewRequest("GET", "/api/orders/123", nil)
        b.ResetTimer()

        for i := 0; i < b.N; i++ {
            rec := httptest.NewRecorder()
            h.ServeHTTP(rec, req)
        }
    }

    $ go test -bench=BenchmarkGetOrder -benchmem -count=5

    BenchmarkGetOrder-8    30000    45000 ns/op    8200 B/op    45 allocs/op
    BenchmarkGetOrder-8    30500    44800 ns/op    8190 B/op    44 allocs/op
    BenchmarkGetOrder-8    30200    45200 ns/op    8210 B/op    45 allocs/op
    -> Baseline: 45us, 8.2KB, 45 allocs per request


  STEP 2: CPU PROFILE TO FIND HOT FUNCTIONS

    $ go test -bench=BenchmarkGetOrder -cpuprofile=cpu.out -benchtime=10s
    $ go tool pprof -http=:8080 cpu.out

    Flame Graph shows:
      GET /api/orders/:id (100% of samples)
        +-- middleware.Logger (3%)
        +-- handler.GetOrder (97%)
              +-- orderRepo.FindByID (15%)
              |     +-- sqlc.FindOrderByID (14%)
              |     |     +-- database/sql.QueryRowContext (8%)
              |     |     +-- rows.Scan (6%)
              |     +-- json.Marshal (2%)
              +-- json.NewEncoder.Encode (40%)   <-- HOT!
              +-- strconv operations (18%)
              +-- time.Time.Format (8%)

    -> JSON encoding is 40% of handler time. Why?
    -> Check source: encoder is allocating a new buffer per request.


  STEP 3: HEAP PROFILE TO FIND ALLOCATION HOT SPOTS

    $ go test -bench=BenchmarkGetOrder -memprofile=mem.out
    $ go tool pprof -http=:8081 mem.out

    Top (alloc_space):
      json.Encoder.Encode     4.2GB   45%
      handler.validateOrder   1.8GB   20%
      time.Time.Format        0.9GB   10%
      strconv.AppendInt       0.5GB    5%
      ...

    -> json.Encoder.Encode is the top allocator.
    -> Check if using json.NewEncoder(w).Encode(v) correctly.
    -> Issue: calling json.Marshal first (allocates []byte), then writing.
    -> Fix: stream directly with json.NewEncoder(w).Encode(v).


  STEP 4: OPTIMIZE

    Before:
    func (h *Handler) GetOrder(w http.ResponseWriter, r *http.Request) {
        order, _ := h.repo.FindByID(r.Context(), id)
        data, _ := json.Marshal(order)   // allocates []byte
        w.Write(data)
    }

    After:
    func (h *Handler) GetOrder(w http.ResponseWriter, r *http.Request) {
        order, _ := h.repo.FindByID(r.Context(), id)
        json.NewEncoder(w).Encode(order)  // streams directly
    }


  STEP 5: VERIFY IMPROVEMENT

    $ go test -bench=BenchmarkGetOrder -benchmem -count=5

    BenchmarkGetOrder-8    45000    32000 ns/op    5200 B/op    25 allocs/op

    -> 29% faster (45us -> 32us)
    -> 37% less memory (8.2KB -> 5.2KB)
    -> 44% fewer allocations (45 -> 25)


FINDING GOROUTINE LEAKS WITH PROFILE COMPARISON:

  $ curl -o before.prof http://localhost:6060/debug/pprof/goroutine
  # ... send 10000 requests ...
  $ curl -o after.prof http://localhost:6060/debug/pprof/goroutine
  $ go tool pprof -base=before.prof after.prof

  Shows goroutines that appeared between the two snapshots:
    250 goroutines @ net/http.(*persistConn).readLoop  <-- LEAK!
    250 goroutines @ net.(*netFD).Read

  -> 250 new goroutines that didn't exist before the test.
  -> They are HTTP client read loops that never terminate.
  -> Root cause: missing resp.Body.Close().
```

## Source Code Reading Guide

**testing/benchmark.go**: The benchmark framework. `testing.(*B).runN()` — the core loop that runs N iterations. `testing.(*B).launch()` — starts the benchmark goroutine. `testing.(*B).ResetTimer()` and `StopTimer()`/`StartTimer()`. `testing.(*B).RunParallel()` — how concurrent benchmarks work using goroutines and a `sync.WaitGroup`.

**runtime/pprof/pprof.go**: The pprof API. `StartCPUProfile(w io.Writer) error` — starts CPU profiling to a writer. `StopCPUProfile()` — stops and flushes. `WriteHeapProfile(w io.Writer)` — snapshots the heap. `Lookup(name string) *Profile` — gets a profile by name (goroutine, heap, threadcreate, block, mutex). The `pprof.Handler(name string)` function returns an `http.Handler` that the `/debug/pprof/` endpoints use.

**runtime/cpuprof.go**: CPU profiling internals. The SIGPROF handler, how samples are buffered, how the profiler unwinds the stack, and how hash maps are used to aggregate identical stacks. This is ~500 lines and quite readable.

**runtime/mprof.go**: Memory profiling internals. `MemProfileRate`, how the allocator samples allocations, how live objects are tracked across GC cycles, and how `inuse_*` and `alloc_*` views are computed.

**runtime/trace.go**: Execution tracer. `trace.Start(w)` — begins tracing. `trace.Stop()` — stops tracing and flushes. The internal event types: `traceEvGoCreate`, `traceEvGoBlock`, `traceEvGoUnblock`, `traceEvGoSysCall`, `traceEvGCStart`, `traceEvGCDone`, etc.

**cmd/pprof/**: The pprof command-line tool. `internal/driver/interactive.go` — the interactive shell. `internal/report/report.go` — how profiles are formatted into text reports. The `-http` web UI is in a separate `internal/driver/webhtml.go`.

**Reading order**: 1. `testing/benchmark.go` (the `B` struct and `runN()`) -> 2. `runtime/pprof/pprof.go` (the public API) -> 3. `runtime/cpuprof.go` (CPU profiling internals) -> 4. `runtime/mprof.go` (memory profiling internals) -> 5. `runtime/trace.go` (execution tracer). Skip the pprof web UI code unless you're building custom visualization.

**What to skip**: The pprof protobuf format definition (auto-generated), the pprof internal symbolization code (deals with DWARF/ELF/PE/Mach-O formats — very platform-specific), and the runtime assembler for signal handling.

## Production Failure Scenarios

**Scenario 1: JSON encoding becomes the bottleneck after a schema change.** A new field was added to the Order struct: an `Items` slice that can contain up to 500 line items. Each item has 15 fields including nested structs. The handler's p99 latency immediately tripled. The CPU profile shows `encoding/json.(*encodeState).marshal` at 65% of handler CPU time. The heap profile shows 500KB allocated per JSON encoding call (up from 50KB). The root cause: `json/encoding` uses reflection to iterate struct fields, and with 500 items * 15 fields = 7,500 field serializations per request, the reflection overhead dominates. Solution options: (a) Switch to `easyjson` or `ffjson` (code-generated marshaling that avoids reflection, ~5x faster), (b) Use protobuf or MessagePack instead of JSON if the client supports it, (c) Paginate the items list in the response rather than sending all 500 items. Benchmark each option before choosing. The `encoding/json` performance is acceptable for small payloads but degrades super-linearly with struct complexity and slice length.

**Scenario 2: GC pause spikes from allocation storms in a background goroutine.** A service with 500ms p99 latency suddenly has 3-second spikes every 30 seconds. The CPU profile looks normal, but `GODEBUG=gctrace=1` shows: `gc 234 @15.678s 45%: 8.5+150+3.2 ms clock`. The 45% means 45% of total CPU has been spent in GC — unsustainable. The 150ms concurrent mark phase suggests a very large heap. A heap profile shows 80% of live heap is in a `map[int64]*OrderCache` with 10 million entries — a background goroutine that rebuilds the cache every 30 seconds and holds both the old and new cache in memory during the rebuild. Fix: (a) Switch to a streaming merge that replaces cached entries one at a time, (b) Use `sync.Map` or a partitioned cache to avoid allocating a 10M-entry map in one go, (c) Reduce the cache TTL so the map never grows to 10M entries. The underlying lesson: a goroutine that allocates 2GB in 3 seconds creates a GC nightmare — the allocator must trigger a GC cycle to free the 2GB from the previous rebuild, and the concurrent mark phase takes 150ms because it must scan 10M map entries.

**Scenario 3: `sync.Mutex` contention causes tail latency under load.** A Chi service handles 500 req/s with p50 = 10ms, p99 = 50ms. After a traffic increase to 2000 req/s, p50 stays at 12ms but p99 spikes to 800ms. A mutex profile (`go test -mutexprofile=mutex.out`) shows 90% of contention on `sync.(*Mutex).Lock` in `rate.(*Limiter).Allow`. The rate limiter uses a `sync.Mutex` to protect a token bucket. Under 500 req/s, the critical section is short enough (<1us) that contention is negligible. Under 2000 req/s, goroutines stack up waiting for the mutex: a goroutine that arrives just after another goroutine acquired the lock waits for the lock holder to finish its critical section, then for all other goroutines ahead of it in the queue. With 4 goroutines waiting, a 1us critical section becomes ~5us per goroutine — still fine. But at 2000 req/s with bursts, 50 goroutines queue up, and the last one waits 50 * 1us = 50us, which still does not explain 800ms. The real issue: the critical section includes an `time.Now()` call (a vDSO call that is usually fast but occasionally takes 10us due to scheduling). Fix: replace the mutex-based rate limiter with `golang.org/x/time/rate` which uses a lock-free algorithm (atomic operations) for the common path and a single mutex for rare refill operations. The benchmark confirms: 12us p50, 45us p99 at 5000 req/s — the tail latency problem disappears.

## Debugging Techniques

**Technique 1: Flame graph drill-down for CPU bottlenecks.** Open a CPU profile: `go tool pprof -http=:8080 cpu.out`. In the Flame Graph view: (a) Scan vertically — the widest frames are the hottest paths. (b) Click a wide frame to zoom into its subtree — this shows you only CPU time spent in calls from that function. (c) Switch to Source view to see per-line CPU samples. A line that accounts for 15% of samples might be an innocent looking `for _, item := range items` — this usually indicates that `items` is much larger than expected (a slice with 10,000 elements instead of 10). (d) Look for frames with unexpected width: if `strconv.FormatInt` is 12% wide, you are formatting integers in a hot path — consider using `strconv.AppendInt` with a reusable buffer. (e) Look for functions from packages you did not write taking unexpected time: `regexp` in the top 10 means a regex is being compiled per-request (`regexp.Compile` is expensive, `regexp.Match` with a pre-compiled regex is cheap).

**Technique 2: Differential profiling to isolate a single endpoint.** When you have a CPU profile from a production service, it is an aggregate of all endpoints. To isolate one slow endpoint, use `pprof`'s filtering: `go tool pprof -http=:8080 -focus=handler.GetOrder -ignore=middleware cpu.out`. This shows only samples where `handler.GetOrder` appears in the call stack, excluding samples that include `middleware` (to filter out common overhead). Even better: collect a profile while hitting only the slow endpoint with a load generator. For heap profiling, use differential analysis: `pprof -base=before.prof after.prof` to see only allocations that occurred between two snapshots. Run your target endpoint 10,000 times between snapshots, and the difference shows exactly what that endpoint allocates.

**Technique 3: Benchmark comparison with `benchstat`.** The `golang.org/x/perf/cmd/benchstat` tool compares two sets of benchmark results and computes statistical significance:

```bash
# Run benchmarks before optimization
$ go test -bench=. -count=10 > old.txt

# Make optimizations

# Run benchmarks after optimization
$ go test -bench=. -count=10 > new.txt

# Compare
$ benchstat old.txt new.txt

name          old time/op    new time/op    delta
GetOrder-8      45.0us +- 2%   32.0us +- 3%   -28.89%  (p=0.000 n=10+10)

name          old alloc/op   new alloc/op   delta
GetOrder-8      8.20KB +- 1%   5.20KB +- 2%   -36.59%  (p=0.000 n=10+10)

name          old allocs/op  new allocs/op  delta
GetOrder-8        45.0 +- 2%     25.0 +- 3%   -44.44%  (p=0.000 n=10+10)
```

The `p=0.000` means there is a <0.1% probability the difference is due to chance — the optimization is real. `benchstat` also checks for sample size adequacy and warns if the number of iterations is too low. Always run at least 10 benchmark iterations and use `benchstat` for comparison — never trust a single benchmark run.

## Observability Considerations

**Log**: Log performance-relevant events at DEBUG level: handler duration, query duration, external call duration, serialization duration. These should use `slog.Duration` attributes for consistent formatting. Log at WARN level when any of these exceed a threshold: single query > 100ms, handler total > 500ms, GC pause > 10ms. At INFO level, log aggregate performance metrics every 5 minutes: p50/p95/p99 handler latency, average allocations per request, GC CPU percentage.

**Metrics**: Export pprof-like data as Prometheus metrics via `net/http/pprof` combined with a Prometheus collector that parses pprof profiles. Better: use `runtime.ReadMemStats()` every 30 seconds and export: `go_memstats_alloc_bytes`, `go_memstats_heap_alloc_bytes`, `go_memstats_heap_sys_bytes`, `go_memstats_heap_objects`, `go_memstats_mallocs_total`, `go_memstats_frees_total`, `go_memstats_gc_cpu_fraction`, `go_memstats_gc_sys_bytes`, `go_memstats_stack_inuse_bytes`. Export goroutine count via `runtime.NumGoroutine()`. Export `go_gc_duration_seconds` histogram from `runtime.ReadMemStats`'s `PauseNs` bucket. Export allocation rate: `rate(go_memstats_alloc_bytes_total[5m])` — a sustained high allocation rate (>100MB/s per instance) indicates GC pressure.

**Traces**: Add `db.statement` and `http.url` as span attributes so you can correlate slow traces with specific queries and endpoints. Set `span.SetAttributes(attribute.Int("gc_pause_ms", pauseMs))` for spans that overlap with a GC pause — this reveals when latency spikes correlate with GC. For critical handlers, record the count of allocations within the handler span using `runtime.MemStats.TotalAlloc` diff (note: this requires `runtime.ReadMemStats`, which is cheap in Go 1.19+).

**Alerts**: Alert on: `rate(go_memstats_alloc_bytes_total[1h]) > 100MB/s` sustained for 30 minutes (allocation storm), `go_gc_duration_seconds{quantile="0.99"} > 0.1` (GC pause spikes), `go_goroutines > 10000` (goroutine leak), `go_memstats_heap_alloc_bytes / go_memstats_heap_sys_bytes > 0.9` (heap near system limit), and benchmark regressions in CI (a benchmark that has >10% degradation from the baseline commit fails the PR). Set up continuous profiling with Pyroscope or Parca to record CPU profiles continuously with <5% overhead — this gives you a pprof profile from 5 minutes before the incident, which is the holy grail of post-incident debugging.

## Performance Implications

**pprof overhead in production.** The CPU profiler at 100Hz has ~2-5% CPU overhead. This is low enough to run continuously in production (which is what continuous profiling tools like Pyroscope do). The heap profiler at default `MemProfileRate=512KB` has negligible overhead (<0.1%) because sampling is rare. The goroutine profiler, when triggered, has a brief (<1ms for <10K goroutines) pause to capture stacks — this is acceptable for on-demand debugging but should not be called continuously. The execution tracer has substantial overhead (10-30% CPU) and generates large output files — use it for targeted debugging, not continuous monitoring. The mutex and block profilers have zero overhead when disabled (default fraction = 0) and ~1-2% when enabled at fraction = 1 (profile every contention).

**Benchmark stability.** Several factors affect benchmark repeatability: (a) CPU frequency scaling (Turbo Boost, power management) — disable with `sudo cpupower frequency-set -g performance` on Linux, (b) thermal throttling — ensure the machine is cool before benchmarking, (c) other processes — close browsers and other CPU consumers, (d) GC during measurements — if your benchmark allocates heavily, GC pauses during the timing window add noise; use `b.ResetTimer()` after warmup and consider setting `GOGC=off` temporarily (with caution — the benchmark should still test realistic conditions), (e) code alignment — changes in unrelated code can change instruction cache alignment, causing apparent regressions; this is rare but real. The `-count=10` flag (run benchmark 10 times) combined with `benchstat` filters out most noise.

**sync.Pool is not always faster.** `sync.Pool` reduces allocations by reusing objects, but it adds overhead for: (a) type assertion on `pool.Get()`, which returns `interface{}` — recovering a typed value requires a type assertion, (b) objects that are too small (allocating a 16-byte struct is already cheap; the pool overhead might exceed the savings), (c) objects with high per-instance variability (if each object needs different capacity, the pool must reallocate internally anyway), (d) objects that are rarely reused (pool entries are cleared on each GC cycle, so an object allocated between GCs and used only once per cycle is wasted memory). Before adding `sync.Pool`, benchmark with and without it — do not assume it helps.

**Preallocation is the simplest win.** The single most common performance win in Go code is preallocating slices and maps with a capacity hint. `make([]Item, 0, expectedSize)` avoids slice growth (which involves allocation + copy of existing elements). `make(map[Key]Value, expectedSize)` avoids repeated map resizing. In Chi handlers, you typically know the expected slice size: the pagination limit, the number of items in a batch, the number of query results. A one-line change from `var items []Item` to `items := make([]Item, 0, limit)` can reduce allocations by 50% and CPU by 20% in list endpoints.

## Architecture Implications

Performance-critical services need benchmarks that run in CI and block PRs that cause >10% degradation on core endpoints. This requires: (a) a dedicated CI runner with stable CPU performance (no other jobs, fixed frequency scaling, no thermal throttling), (b) benchmark results stored as artifacts so `benchstat` can compare the PR branch against `main`, (c) a `SKIP_BENCHMARKS` label for PRs that knowingly change performance (with review required). The `golang.org/x/perf` repository provides `benchsave` and `benchstat` for this workflow.

The profiling infrastructure should mirror the observability stack: a separate port (e.g., 9090) serving `/debug/pprof/` endpoints, accessible only from the monitoring infrastructure. Continuous profiling (Pyroscope/Parca) agents should run as a sidecar or DaemonSet that scrapes `/debug/pprof/profile?seconds=10` every 60 seconds on each instance. The aggregated profiles can be queried by time range and tag, so you can compare "the profile from 5 minutes before the incident" with "the profile from the same time yesterday."

For Chi services specifically, the middleware stack has a measurable performance cost that grows linearly with the number of middleware. Each middleware adds function call overhead, context operations, and possible allocations. A service with 10 middleware has 10 function calls per request in the middleware chain alone. Benchmark the middleware chain at startup (similar to health check) and export `chi_middleware_chain_overhead_us` as a gauge — if it jumps after a deploy, a new middleware was added or an existing one started allocating. Common offenders: logging middleware that formats strings even when the log level is disabled, tracing middleware that creates spans even when not sampled, and `middleware.RequestID` with UUID generation (use a faster ID generator like ULID or XID if it becomes a bottleneck).

## Team Ownership Implications

Every team should have a performance lead — not a dedicated role, but a designated engineer who: (a) reviews benchmarks for critical paths, (b) ensures CI benchmark comparison is working, (c) teaches profiling tools to the team, and (d) runs a quarterly performance review of the service (looking at p99 trends, allocation trends, GC pause trends, goroutine count trends). The performance lead is also the go-to person when a handler needs optimization — they know which profile to collect, which tool to open, and what patterns to look for.

The platform team provides: (a) a Go module `pkg/perf` with pre-configured benchmark helpers (handler benchmark setup, HTTP server benchmark setup, database mock for handler benchmarks), (b) CI integration that runs `go test -bench=. -benchtime=1s -count=5` on every PR and posts a `benchstat` comparison against `main` as a PR comment, (c) a Grafana dashboard showing per-endpoint p50/p99 latency from the RED metrics, heap allocation rate from `go_memstats_alloc_bytes_total`, and GC CPU fraction from `go_memstats_gc_cpu_fraction`, (d) Pyroscope/Parca for continuous profiling, accessible to all developers. The goal: a developer should be able to go from "this endpoint feels slow" to "the CPU profile shows 40% in JSON encoding, switching to easyjson gives 28% improvement" in under 30 minutes, without asking a senior engineer for help.

## Interview Questions

**Q1: What is the difference between "flat" and "cumulative" time in a pprof CPU profile, and when would a function have high cumulative but low flat time?**
Answer: Flat time is the number of samples where the function was at the top of the stack (the function currently executing). Cumulative time is the number of samples where the function appears anywhere in the stack — including time spent in functions it called. A function with high cumulative but low flat time (e.g., `main.main` with 100% cumulative but 1% flat) is a coordinator — it calls other functions that do the actual work. This is common for HTTP handlers: the handler function has high cumulative time (all work happens inside it) but low flat time (the actual work is spread across JSON encoding, database queries, and business logic functions). When optimizing, functions with high flat time are the direct CPU consumers — these are the functions whose code you should optimize. Functions with high cumulative but low flat time suggest you should look at their callees instead.

**Q2: How does `go test -benchmem` measure allocations, and what are the limitations of this measurement?**
Answer: `-benchmem` reports two allocation metrics: `B/op` (bytes allocated per operation) and `allocs/op` (number of allocation calls per operation). These are measured by reading `runtime.MemStats.TotalAlloc` and `runtime.MemStats.Mallocs` before and after the benchmark loop, then dividing by `b.N`. Limitations: (a) Allocations from other goroutines (e.g., the GC background worker) are included in the total — if GC runs during the benchmark, the reported allocations include GC's own allocations, inflating the numbers. Use `b.ResetTimer()` after a warmup phase to let GC stabilize. (b) Stack allocations are not counted — only heap allocations. A small struct on the stack costs zero `B/op`. (c) `TotalAlloc` is cumulative and never decreases — it includes freed memory. `B/op` tells you allocation rate (GC pressure), not heap growth (memory leak). For leak detection, use `--inuse_space` in pprof heap profiles, not `-benchmem`. (d) Sampling-based profiling (`MemProfileRate`) is statistical; `TotalAlloc` is exact. They can disagree because of the sampling granularity.

**Q3: You are profiling a Chi handler and find that `runtime.mallocgc` is 30% of CPU time. What does this tell you, and what are the likely causes?**
Answer: `runtime.mallocgc` is the Go runtime's memory allocation function — it is called whenever the heap allocator needs memory. High `mallocgc` CPU time means the handler is allocating heavily. Likely causes in a Chi handler: (a) Repeated `json.Marshal` calls that allocate a new `[]byte` each time — fix with `json.NewEncoder(w).Encode(v)` or a `sync.Pool` of `bytes.Buffer`. (b) Growing slices without preallocation: `var items []Item; for ... { items = append(items, item) }` — fix with `items := make([]Item, 0, expectedSize)`. (c) String concatenation in a loop: `result += str` — fix with `strings.Builder`. (d) Boxing values into `interface{}`: `fmt.Sprintf("%v", value)` or logging that converts values to `interface{}`. (e) Closure allocations: a function literal that captures local variables allocates a closure struct on the heap. (f) Large object allocation: reading a 10MB request body into memory. To distinguish between many small allocations (use `--alloc_objects` in pprof) and a few large ones (use `--alloc_space`). Many small allocations suggest poolable objects or preallocation opportunities; few large allocations suggest streaming or memory limits.

**Q4: What is the difference between `pprof --inuse_space` and `pprof --alloc_space` for heap profiles, and how do you use them together to distinguish a memory leak from allocation churn?**
Answer: `--inuse_space` shows bytes currently allocated on the heap — this is what is live, what is consuming RSS, what would be freed if the application terminated. `--alloc_space` shows cumulative bytes allocated since the program started, including bytes that have since been freed. A memory leak: high `inuse_space` from a single allocation site that keeps growing over time — e.g., a map that accumulates cache entries with no eviction. Allocation churn: high `alloc_space` from a site but low `inuse_space` — e.g., a function that allocates 1KB per call, called 1 million times (1GB cumulative), but each allocation is freed within microseconds. Strategy: start with `--inuse_space` to check for leaks. If the largest in-use allocation site accounts for <30% of total, switch to `--alloc_space` to find allocation hot spots driving GC pressure. A function at 80% `alloc_space` but 1% `inuse_space` is not leaking — it is creating garbage fast, and you need `sync.Pool` or preallocation to reduce GC pressure. A function at 60% `inuse_space` that grows over time is a leak — fix the data structure.

**Q5: How would you profile and optimize a Chi service that handles 10,000 req/s but has p99 latency of 2 seconds, while p50 is 3ms?**
Answer: The extreme gap between p50 (3ms) and p99 (2s) suggests intermittent blocking, not steady-state CPU saturation. Approach: (1) Check GC pause times first — `GODEBUG=gctrace=1` or look at `go_gc_duration_seconds` metric. If p99 spikes correlate with GC pauses >100ms, tune GOGC or reduce allocation rate. (2) Collect a mutex profile: the long tail could be goroutines contending on a lock held by a slow operation. `curl -o mutex.out http://localhost:6060/debug/pprof/mutex` and open with `pprof --web`. Look for mutexes with high `cumulative delay` — these are where goroutines spend time waiting. (3) Collect an execution tracer trace: `curl -o trace.out http://localhost:6060/debug/pprof/trace?seconds=30`. In the trace viewer, use "Goroutine analysis" to find handler goroutines with >1s duration. Click on them to see their timeline — the trace shows exactly where they blocked (channel, mutex, network I/O, or GC). (4) If the trace shows goroutines blocked on network I/O (`net.(*netFD).Read`), a downstream dependency is intermittently slow — add circuit breakers and timeouts. (5) If the trace shows goroutines blocked on `sync.Mutex.Lock`, identify what holds the mutex (usually a background job or a pathologically slow operation inside a critical section). (6) If none of the above — the 2s spikes are in "running" state — check for CPU throttling from the container orchestrator (Kubernetes CPU limits), which pauses the entire process when it exceeds its quota, causing all goroutines to stall simultaneously.

## Hands-On Exercises

**Exercise 1: Profile and optimize a "slow by design" Chi handler.** Write a handler with: (a) an unbuffered slice append in a loop (1000 items), (b) `fmt.Sprintf` for each item, (c) `json.Marshal` to create the response body before writing. Benchmark it: `go test -bench=BenchmarkSlowHandler -benchmem`. Profile with `-cpuprofile=cpu.out` and `-memprofile=mem.out`. Use the flame graph to identify the hot spots. Optimize step by step: (1) preallocate the slice with `make([]Item, 0, 1000)`, (2) replace `fmt.Sprintf` with `strconv` functions, (3) replace `json.Marshal` + `w.Write` with `json.NewEncoder(w).Encode`. After each optimization, re-benchmark and confirm improvement. The final benchmark should show >50% latency reduction and >60% allocation reduction.

**Exercise 2: Add a benchmark comparison step to CI.** Configure a CI pipeline (GitHub Actions, GitLab CI, or Jenkins) that: (a) checks out the PR branch and `main`, (b) runs `go test -bench=. -benchtime=3s -count=10` on each, saving results to `pr.txt` and `main.txt`, (c) runs `benchstat main.txt pr.txt` and posts the result as a PR comment, (d) fails the CI check if any benchmark shows >10% regression with p < 0.01. Verify: push a commit that intentionally slows down a handler (add `time.Sleep(1ms)`) and confirm CI fails with a clear message showing the benchmark regression.

**Exercise 3: Set up continuous profiling and investigate a production latency spike.** Deploy a Chi service with `/debug/pprof/` endpoints enabled. Use `vegeta` to generate steady load at 500 req/s. Use Pyroscope (or `curl` loop) to capture CPU profiles every 30 seconds. Introduce a latency spike: add a feature flag that enables a slow code path (e.g., regex compilation per request) for 3 minutes. After the spike, compare the CPU profile from during the spike with the profile from before — identify the `regexp.Compile` calls. Remove the slow path. Verify the profiles return to normal. The exercise goal: demonstrate that with continuous profiling, you can open a profile from the exact time of the incident and see the root cause immediately.

## Advanced Challenges

**Challenge 1: Build a tool that automatically detects performance regressions from pprof profiles in CI.** Requirements: (a) The tool accepts two pprof CPU profiles (baseline and candidate) in protobuf format. (b) It identifies functions where CPU time increased by >5% AND the increase is statistically significant (using repeated benchmark runs with benchstat). (c) It outputs a report with: function name, flat time change, cumulative time change, and the source file:line. (d) It uses `go tool pprof`'s `-diff_base` functionality internally and parses the output. (e) It runs as part of CI and posts the report as a PR comment. (f) It handles the case where new functions appear in the candidate profile (new code added) by flagging them for manual review but not failing. (g) It stores historical benchmark results in a time-series database (e.g., Prometheus via `benchstat` to JSON to pushgateway) so you can track performance trends over months.

**Challenge 2: Implement a self-tuning `sync.Pool` with adaptive sizing.** Build a generic pool (`type AdaptivePool[T any]`) that: (a) Starts with no cached objects. (b) Tracks the ratio of `Get()` calls to `Put()` calls over a rolling window of the last 1,000 operations. If the ratio is >1.2 (more Gets than Puts), the pool is under-provisioned — increase the cache size by creating additional objects on Get. If the ratio is <0.8 (more Puts than Gets), the pool is over-provisioned — allow objects to be GC'd on the next GC cycle (don't Put them). (c) Limits the cache to a configurable maximum (prevent unbounded growth). (d) Periodically (every N Gets), measures the average time to create a new object vs. the average time to Get from the pool, and logs a recommendation: "pool is reducing allocation latency by X%" or "pool overhead exceeds allocation savings — consider removing pool." (e) Exports Prometheus metrics: `pool_size`, `pool_hit_rate`, `pool_new_object_duration_seconds`, `pool_get_duration_seconds`. (f) The pool must be safe for concurrent use (use a mutex or lock-free data structure). Benchmark against `sync.Pool` for hot-path handler scenarios with object sizes from 64 bytes to 64KB.

## Key Insights

- `go test -bench` + `-benchmem` + `-cpuprofile` + `-memprofile` gives you a complete performance picture in one command — always collect all four when investigating a performance issue
- The flame graph in `go tool pprof -http` is the single most effective visualization for identifying hot functions — look for wide frames, click to zoom, switch to Source to see the exact lines
- `json.Marshal` allocates a `[]byte` the size of the entire response; `json.NewEncoder(w).Encode(v)` streams directly to the `io.Writer` — this single change reduces allocations by 30-50% in most JSON-heavy Chi handlers
- `--inuse_space` shows what is consuming memory right now (leak detection); `--alloc_space` shows what generates garbage (GC pressure) — use them together, not interchangeably
- Mutex profiling (`-mutexprofile`) and block profiling (`-blockprofile`) are disabled by default but are essential for diagnosing tail latency — a mutex held for 1us with 1000 waiting goroutines creates 1ms of cumulative latency
- The execution tracer (`go tool trace`) is the tool of last resort for "this one request took 5 seconds and I cannot figure out why" — it shows every goroutine event, every GC cycle, and every blocking operation in a timeline
- Benchmark comparisons in CI with `benchstat` are the only reliable way to prevent performance regressions — a single benchmark run is not statistically significant; always run at least 10 iterations and compare with `benchstat` before claiming improvement or regression
