# Go/Chi Troubleshooting Guide

> **Level**: Staff/Principal Engineer Reference
> **Purpose**: Systematic diagnosis and resolution of production issues in Go/Chi services
> **Usage**: Start with the Quick Reference Table. If symptom isn't listed, follow the relevant playbook.

---

## Quick Reference Table

| Symptom | Likely Cause | Diagnostic Command | Fix |
|---------|-------------|-------------------|-----|
| OOM kill (exit code 137) | Goroutine leak | `curl /debug/pprof/goroutine?debug=1 \| head -50` | Add context timeouts, check unbounded channel writes |
| OOM kill (slow growth) | Memory leak | `curl /debug/pprof/heap > heap.prof && go tool pprof -top heap.prof` | Check for accumulated slices, map growth, finalizer leaks |
| p99 latency spike (periodic) | GC pause | `curl /debug/pprof/trace?seconds=5 > trace.out && go tool trace trace.out` | Reduce allocations in hot path, tune GOGC |
| 503 errors under load | Connection pool exhaustion | `curl /debug/pprof/goroutine?debug=2 \| grep -c "database/sql"` | Increase MaxOpenConns, add connection timeouts |
| Request timeout (504) | Handler deadlock or slow I/O | `curl /debug/pprof/goroutine?debug=2 \| grep "sync.Mutex.Lock"` | Check mutex ordering, add context propagation |
| High CPU, normal QPS | Busy loop or excessive GC | `curl /debug/pprof/profile?seconds=30 > cpu.prof && go tool pprof -top cpu.prof` | Check for tight loops, excessive allocations |
| Chi returns 405, expected 200 | Method not allowed | `curl -v -X GET http://svc/api/users` (check Allow header) | Verify handler registration matches method |
| Chi returns 404, route registered | Route conflict | Check route patterns for ambiguity (e.g., `/users/{id}` vs `/users/new`) | Reorder routes, use more specific patterns first |
| Slow startup (>10s) | Docker pull or DB migration | `kubectl describe pod` (check image pull time), check migration logs | Pre-pull images, optimize migrations |
| Intermittent EOF errors | Connection dropped mid-response | Check `net/http.Server.WriteTimeout`, check upstream proxy timeout | Set WriteTimeout higher than upstream, add retry logic |
| Goroutine count steadily increases | Leak from missing context cancel | `curl /debug/pprof/goroutine?debug=1 \| grep "chan receive" \| wc -l` | Add `defer cancel()` for all `context.WithCancel()` |
| Chi middleware not executing | Wrong middleware ordering | Verify `r.Use()` order — middleware runs in registration order | Common: Place timeout AFTER auth to give handler full budget |
| High allocation rate | JSON encoding per request | `curl /debug/pprof/allocs > allocs.prof && go tool pprof -top allocs.prof` | Use `json.NewEncoder(w)` instead of `json.Marshal()`, pool buffers |
| Panic in handler | Nil pointer dereference | Check recoverer middleware logs for stack trace | Fix nil check, or add recoverer with structured logging |
| Database connection leak | Not closing `rows` | `curl /debug/pprof/goroutine?debug=2 \| grep "database/sql"` | Always `defer rows.Close()` |

---

## Goroutine Leak Diagnosis Playbook

### Step 1: Confirm a Leak

```bash
# Check goroutine count over time (5 samples, 30s apart)
for i in $(seq 1 5); do
  echo "$(date -Iseconds): $(curl -s http://localhost:6060/debug/pprof/goroutine?debug=1 | head -1)"
  sleep 30
done

# Expected: goroutine count fluctuates but returns to baseline after load
# Leak indicator: goroutine count monotonically increases, never decreases
```

### Step 2: Identify Leaking Goroutine Type

```bash
# Get goroutine profile with stack traces
curl -s http://localhost:6060/debug/pprof/goroutine?debug=2 > goroutine.txt

# Count goroutines by state
grep "goroutine" goroutine.txt | wc -l                                                    # total
grep "chan receive" goroutine.txt | wc -l                                                 # blocked on channel receive
grep "chan send" goroutine.txt | wc -l                                                    # blocked on channel send
grep "IO wait" goroutine.txt | wc -l                                                      # blocked on I/O
grep "sleep" goroutine.txt | wc -l                                                        # sleeping (time.Sleep)
grep "select" goroutine.txt | wc -l                                                       # in select statement
grep "sync.Mutex.Lock" goroutine.txt | wc -l                                              # blocked on mutex
```

### Step 3: Find the Source

```bash
# Interactive pprof analysis
go tool pprof http://localhost:6060/debug/pprof/goroutine

# In pprof shell:
(pprof) top10           # Top goroutine stacks
(pprof) list functionName  # See source code with goroutine counts per line
(pprof) web             # Generate call graph (requires graphviz)
```

### Step 4: Common Leak Patterns and Fixes

**Pattern 1: Unbounded Goroutine Creation**

```go
// LEAK: New goroutine per request with no timeout
func handler(w http.ResponseWriter, r *http.Request) {
    go processSlowly()  // goroutine lives forever if processSlowly blocks
}

// FIX: Use worker pool or bound concurrency with context
func handler(w http.ResponseWriter, r *http.Request) {
    ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
    defer cancel()

    done := make(chan struct{})
    go func() {
        defer close(done)
        processSlowly()  // ideally, processSlowly should accept context
    }()

    select {
    case <-done:
    case <-ctx.Done():
        // goroutine may still be running, but handler returns
        // Better: make processSlowly context-aware
    }
}
```

**Pattern 2: Channel Send Without Receiver**

```go
// LEAK: Channel send blocks forever
results := make(chan Result)  // unbuffered channel
go func() {
    results <- computeResult()  // blocks if nobody reads
}()

// FIX: Buffer the channel or use select with timeout
results := make(chan Result, 1)  // buffered — send doesn't block
go func() {
    results <- computeResult()
}()
// OR
select {
case results <- computeResult():
case <-ctx.Done():
}
```

**Pattern 3: Missing context cancellation in Chi middleware**

```go
// LEAK: Middleware spawns goroutines without respecting request context
func LeakyMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        go auditLog(r)  // spawned goroutine, no context binding
        next.ServeHTTP(w, r)
    })
}

// FIX: Always pass context to spawned goroutines
func SafeMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        go func() {
            select {
            case <-r.Context().Done():
                return  // request cancelled, stop
            case <-time.After(100 * time.Millisecond):
                auditLog(r)
            }
        }()
        next.ServeHTTP(w, r)
    })
}
```

**Pattern 4: HTTP client with no timeout**

```go
// LEAK: Every slow upstream creates a stuck goroutine
var client = &http.Client{}  // no timeout!

// FIX: Always set timeouts on HTTP clients
var client = &http.Client{
    Timeout: 10 * time.Second,
    Transport: &http.Transport{
        MaxIdleConns:        100,
        MaxIdleConnsPerHost: 10,
        IdleConnTimeout:     90 * time.Second,
    },
}
```

---

## GC Pause Investigation Playbook

### Step 1: Measure Current GC Behavior

```bash
# Check GC stats
GODEBUG=gctrace=1 ./service 2>&1 | grep "gc "

# Output: gc 7 @0.123s 0%: 0.022+0.34+0.012 ms clock, 0.17+0/0.22/0+0.10 ms cpu, 4->4->0 MB, 5 MB goal, 8 P
#                                  ^^^^^^    ^^^^^
#                            STW mark   STW sweep

# Key metrics:
# 0.022 ms = STW mark phase (should be <1ms)
# 0.012 ms = STW sweep phase (should be <1ms)
# 4->4->0 MB = heap before GC, after GC, live heap
```

### Step 2: Profile GC Pauses

```bash
# Generate a trace with GC events
curl -o trace.out http://localhost:6060/debug/pprof/trace?seconds=10

# Open trace viewer
go tool trace trace.out

# In browser:
# - Check "GC events" section for pause durations
# - Check "Goroutine analysis" for goroutines blocked on GC
# - Check "MMU" (Minimum Mutator Utilization) graph
```

### Step 3: Diagnose Root Cause

```bash
# Memory allocation rate (high allocation = frequent GC)
go tool pprof http://localhost:6060/debug/pprof/allocs

# Top allocation sources
(pprof) top20
(pprof) list functionName
```

### Step 4: Mitigation Strategies

1. **Reduce allocations in hot paths**:
```go
// BEFORE: Allocates on every request
func handler(w http.ResponseWriter, r *http.Request) {
    data := json.Marshal(response)  // allocates []byte
    w.Write(data)
}

// AFTER: Stream to response writer (zero allocation)
func handler(w http.ResponseWriter, r *http.Request) {
    json.NewEncoder(w).Encode(response)  // streams, fewer allocs
}
```

2. **Use sync.Pool for frequently allocated objects**:
```go
var bufferPool = sync.Pool{
    New: func() interface{} {
        return new(bytes.Buffer)
    },
}

func handler(w http.ResponseWriter, r *http.Request) {
    buf := bufferPool.Get().(*bytes.Buffer)
    defer bufferPool.Put(buf)
    buf.Reset()
    // use buf...
}
```

3. **Tune GOGC for latency-sensitive services**:
```bash
# Default: GOGC=100 (GC triggers when heap doubles)
# For latency-sensitive: GOGC=200 (less frequent GC, higher memory)
# For memory-constrained: GOGC=50 (more frequent GC, lower memory)

# Set via environment variable
GOGC=200 ./service

# Or set soft memory limit (Go 1.19+)
GOMEMLIMIT=512MiB ./service
```

4. **Pre-allocate slices with known capacity**:
```go
// BEFORE: Multiple allocations as slice grows
var users []User
for rows.Next() {
    var u User
    rows.Scan(&u)  // scan into new User each iteration
    users = append(users, u)  // slice may reallocate
}

// AFTER: Single allocation
var users []User
for rows.Next() {
    var u User
    rows.Scan(&u)
    users = append(users, u)  // still may grow, but less
}

// BEST: Pre-allocate if count is known
users := make([]User, 0, expectedCount)
```

---

## Memory Leak Diagnosis Playbook

### Step 1: Confirm a Leak

```bash
# Monitor heap size over time
while true; do
  curl -s http://localhost:6060/debug/pprof/heap?debug=1 | head -1
  sleep 60
done

# Expected: heap size cycles (grows then GC reduces)
# Leak indicator: heap size baseline keeps increasing
```

### Step 2: Find Leaking Objects

```bash
# Capture heap profile
curl -o heap.prof http://localhost:6060/debug/pprof/heap

# Analyze top allocations (inuse_space = current usage, not cumulative)
go tool pprof -inuse_space -top heap.prof

# Compare two heap profiles (find what grew between t1 and t2)
go tool pprof -base heap_t1.prof heap_t2.prof
(pprof) top20
```

### Step 3: Common Leak Sources

1. **Unbounded map/slice growth**:
```go
// LEAK: Global cache with no eviction
var cache = make(map[string]*BigStruct)

// FIX: Use a bounded LRU cache or time-based eviction
type CacheEntry struct {
    Value    *BigStruct
    LastUsed time.Time
}
// Periodically evict entries older than TTL
```

2. **Goroutine reference holding memory**:
```go
// LEAK: Goroutine holds reference to large object
func handler(w http.ResponseWriter, r *http.Request) {
    largeObject := loadLargeObject()
    go func() {
        // This goroutine holds largeObject alive
        // If goroutine never returns, largeObject is never GC'd
        processSlowly(largeObject)
    }()
}
```

3. **Finalizer leaks**:
```go
// LEAK: Object with finalizer that creates cycle
type Node struct {
    next *Node
}
runtime.SetFinalizer(node, func(n *Node) {
    // If finalizer references the object, it's resurrected
    n.next = otherNode  // this can prevent GC
})
```

4. **CGO memory leaks**:
```bash
# Check if C/C++ allocations from CGO are leaking
# These won't show up in Go heap profiles
curl http://localhost:6060/debug/pprof/allocs?debug=1 | grep "C.malloc"
```

---

## Connection Pool Exhaustion Playbook

### Step 1: Diagnose

```bash
# Check database connection pool stats
curl -s http://localhost:6060/debug/pprof/goroutine?debug=2 | grep -c "database/sql.*connect"

# Check via application metrics (if instrumented)
curl -s http://localhost:6060/metrics | grep "db_connections"

# Check via database directly
psql -c "SELECT count(*) FROM pg_stat_activity WHERE state = 'active';"
```

### Step 2: Identify the Cause

```bash
# Look for goroutines blocked on database connections
go tool pprof http://localhost:6060/debug/pprof/goroutine
(pprof) list database/sql
```

### Step 3: Fix

```go
// PROBLEM: Default settings too conservative
db, err := sql.Open("postgres", dsn)
// Default: MaxOpenConns = 0 (unlimited) — sounds good, but can overwhelm DB
// Default: MaxIdleConns = 2 — may be too low

// FIX: Configure connection pool explicitly
db.SetMaxOpenConns(25)               // limit open connections
db.SetMaxIdleConns(10)               // keep idle connections for reuse
db.SetConnMaxLifetime(5 * time.Minute) // recycle old connections
db.SetConnMaxIdleTime(1 * time.Minute)  // close idle connections
```

### Common Connection Pool Misconfigurations

| Problem | Symptom | Fix |
|---------|---------|-----|
| MaxOpenConns too low | goroutines blocked on `sql.DB.conn` | Increase MaxOpenConns (calculate: expected concurrent requests × avg DB query time) |
| No transaction timeout | Connections held by slow transactions | Always use `context.WithTimeout` for DB operations |
| Not closing `rows` | Connection leak (returned but not closed) | Always `defer rows.Close()` |
| MaxIdleConns = 0 | Connection churn (new TCP + TLS per query) | Set to at least 5-10 |
| ConnMaxLifetime too long | Stale connections after DB failover | Set to less than DB connection timeout (e.g., 5 min) |

---

## Panic Recovery & Debugging

### Chi's Built-in Recoverer

```go
// Chi's recoverer catches panics and returns 500
// Check middleware/realip.go, middleware/heartbeat.go in chi/middleware
r.Use(chi.middleware.Recoverer)

// Custom recoverer with structured logging
func CustomRecoverer(logger *slog.Logger) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            defer func() {
                if rvr := recover(); rvr != nil {
                    if rvr == http.ErrAbortHandler {
                        panic(rvr)  // re-panic to abort connection
                    }

                    // Capture full stack trace
                    buf := make([]byte, 2048)
                    n := runtime.Stack(buf, false)
                    stack := string(buf[:n])

                    logger.Error("panic recovered",
                        "error", rvr,
                        "stack", stack,
                        "url", r.URL.String(),
                        "method", r.Method,
                    )

                    if r.Header.Get("Connection") != "Upgrade" {
                        w.WriteHeader(http.StatusInternalServerError)
                    }
                }
            }()
            next.ServeHTTP(w, r)
        })
    }
}
```

### Finding the Panic Source

```bash
# 1. Check logs for the panic message and stack trace
kubectl logs -l app=payment-service --tail=100 | grep -A 50 "panic"

# 2. If recoverer doesn't log stack traces, add a custom one (see above)

# 3. Common panic causes in Chi services:
# - Nil pointer: chi.URLParam() on a route without params
# - Type assertion fail: wrong type in context value
# - Index out of bounds: empty slice access
# - Concurrent map write: shared map without mutex
# - Send on closed channel

# 4. Run with race detector in staging
go test -race ./...
go build -race -o service ./cmd/service
```

---

## High Latency Investigation Playbook

### Step 1: Isolate the Source

```bash
# Quick check: where is time spent?
curl -o trace.out "http://localhost:6060/debug/pprof/trace?seconds=5"
go tool trace trace.out
# Look at "Goroutine analysis" and "Network blocking profile"

# CPU profile
curl -o cpu.prof "http://localhost:6060/debug/pprof/profile?seconds=30"
go tool pprof -top cpu.prof
```

### Step 2: Middleware Timing

```go
// Add timing middleware to identify slow middleware layers
func TimingMiddleware(name string) func(http.Handler) http.Handler {
    return func(next http.Handler) http.Handler {
        return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
            start := time.Now()
            next.ServeHTTP(w, r)
            elapsed := time.Since(start)
            if elapsed > 100*time.Millisecond {
                log.Printf("SLOW middleware=%s duration=%v", name, elapsed)
            }
            // Emit Prometheus histogram for the middleware layer
            middlewareLatency.WithLabelValues(name).Observe(elapsed.Seconds())
        })
    }
}

r.Use(TimingMiddleware("requestID"))
r.Use(TimingMiddleware("auth"))
r.Use(TimingMiddleware("rateLimiter"))
// ...
```

### Step 3: Database Query Timing

```bash
# PostgreSQL: find slow queries
psql -c "SELECT query, calls, mean_exec_time, total_exec_time
FROM pg_stat_statements
ORDER BY mean_exec_time DESC LIMIT 20;"
```

### Step 4: Upstream Service Timing

```bash
# Check if latency comes from upstream HTTP calls
go tool pprof http://localhost:6060/debug/pprof/goroutine
(pprof) list net/http.*roundTrip

# Add timing to outbound HTTP calls
# Use chi/middleware or a custom http.RoundTripper wrapper
```

---

## Production Debugging Checklist

### Pre-Flight (before touching production)

- [ ] Read all recent alerts and incidents for context
- [ ] Check recent deployments: `kubectl describe pod -l app=x` (look at image tag, deploy time)
- [ ] Check recent config changes: `kubectl get configmap -o yaml | grep -A 5 "last-applied"`
- [ ] Check recent scale events: `kubectl describe hpa <service>`
- [ ] Verify this isn't a known issue: check runbooks, post-mortems, ADRs
- [ ] Identify who's on-call for dependencies (DB, Redis, Kafka, upstream services)
- [ ] Ensure monitoring dashboards are loading correctly
- [ ] Open incident channel/thread for coordination

### Investigation

- [ ] Check service health endpoints: `/health`, `/ready`
- [ ] Check error rate and latency dashboards
- [ ] Check resource utilization: CPU, memory, goroutines, file descriptors
- [ ] Sample recent error logs: `kubectl logs --tail=200 | grep ERROR`
- [ ] Check database: connection count, slow queries, replication lag
- [ ] Check Redis/Kafka: connection status, consumer lag
- [ ] Check network: pod-to-pod latency, DNS resolution
- [ ] Check pprof: goroutine count trend, heap growth, CPU hotspots

### Mitigation

- [ ] Can we rollback the last deployment? (always have a rollback plan)
- [ ] Can we scale up/down to handle load?
- [ ] Can we disable a non-critical feature via feature flag?
- [ ] Can we increase timeouts/retries temporarily?
- [ ] Does adding more DB connections help (connection pool exhausted)?
- [ ] Can we shed load (rate limit, queue, reject low-priority traffic)?

### Post-Mortem

- [ ] Timeline of events (detection → investigation → mitigation → resolution)
- [ ] Root cause: what specifically broke?
- [ ] Why didn't monitoring catch this sooner?
- [ ] What prevents this from happening again?
- [ ] Update runbooks, monitoring thresholds, alerts
- [ ] File actionable tickets with owners and deadlines

---

## Essential Commands Cheat Sheet

### pprof Quick Access

```bash
# Start pprof web UI (interactive flame graphs, call graphs)
go tool pprof -http=:8081 http://localhost:6060/debug/pprof/profile?seconds=30

# Goroutine dump (text format)
curl http://localhost:6060/debug/pprof/goroutine?debug=2 > goroutines.txt

# Heap profile (current in-use memory)
curl -o heap.prof http://localhost:6060/debug/pprof/heap
go tool pprof -inuse_space -top heap.prof

# Heap profile (total allocated, useful for allocation rate)
curl -o allocs.prof http://localhost:6060/debug/pprof/allocs
go tool pprof -alloc_space -top allocs.prof

# CPU profile (30 seconds)
curl -o cpu.prof http://localhost:6060/debug/pprof/profile?seconds=30
go tool pprof -top cpu.prof

# Execution trace (includes GC, scheduling, blocking)
curl -o trace.out http://localhost:6060/debug/pprof/trace?seconds=5
go tool trace trace.out

# Mutex contention
curl -o mutex.prof http://localhost:6060/debug/pprof/mutex
go tool pprof -top mutex.prof

# Blocking profile
curl -o block.prof http://localhost:6060/debug/pprof/block
go tool pprof -top block.prof
```

### Runtime Diagnostics

```bash
# Goroutine count
curl -s http://localhost:6060/debug/pprof/goroutine?debug=1 | head -1

# Heap summary
curl -s http://localhost:6060/debug/pprof/heap?debug=1 | head -1

# GC summary
curl -s http://localhost:6060/debug/pprof/

# Stack trace of all goroutines
curl -s http://localhost:6060/debug/pprof/goroutine?debug=2

# Memory stats
curl -s http://localhost:6060/debug/pprof/heap?debug=1
```

### Chi-Specific Diagnostics

```bash
# Check registered routes (if you've instrumented it)
curl http://localhost:6060/debug/routes  # requires custom debug handler

# Check middleware chain (if you've instrumented it)
curl http://localhost:6060/debug/middleware  # requires custom debug handler

# Force a route lookup (test if route is registered correctly)
curl -v -X OPTIONS http://service/api/path  # check Allow header for registered methods
```

### Docker/Kubernetes Diagnostics

```bash
# Check resource usage
kubectl top pod -l app=payment-service

# Check recent events (OOMKilled, CrashLoopBackOff, etc.)
kubectl describe pod -l app=payment-service | grep -A 10 "Events"

# Check container logs
kubectl logs -l app=payment-service --tail=200 --previous  # previous crashed container

# Port-forward for pprof access
kubectl port-forward pod/payment-service-abc123 6060:6060

# Execute shell in container
kubectl exec -it pod/payment-service-abc123 -- sh
```

---

## pprof Recipes

### Recipe 1: Find the Hottest Function

```bash
go tool pprof http://localhost:6060/debug/pprof/profile?seconds=30
(pprof) top20
(pprof) list functionName  # Show source with per-line CPU time
(pprof) web functionName   # Call graph for this function
```

### Recipe 2: Find Memory Leak

```bash
# Capture two heap profiles 5 minutes apart
curl -o heap1.prof http://localhost:6060/debug/pprof/heap
sleep 300
curl -o heap2.prof http://localhost:6060/debug/pprof/heap

# Compare: what grew?
go tool pprof -base heap1.prof heap2.prof
(pprof) top20
(pprof) list leakingFunction
```

### Recipe 3: Find Goroutine Leak Source

```bash
go tool pprof http://localhost:6060/debug/pprof/goroutine
(pprof) top10          # Top stacks by goroutine count
(pprof) list handler   # Goroutines per line in the handler
(pprof) traces handler # Full call traces leading to handler goroutines
```

### Recipe 4: Identify Allocation Hotspots

```bash
go tool pprof http://localhost:6060/debug/pprof/allocs
(pprof) top20
(pprof) list json.Marshal  # If JSON encoding is the culprit
# Look for:
# - Bytes allocated per call
# - Func is called N times with M bytes each
# Focus on: high N * M (total allocation volume)
```

### Recipe 5: Analyze GC Impact on Latency

```bash
# Capture trace
curl -o trace.out http://localhost:6060/debug/pprof/trace?seconds=10

# Open trace viewer
go tool trace trace.out

# In the trace viewer:
# 1. Check "GC events" — look for pauses >1ms
# 2. Check "Minimum Mutator Utilization" — what % of time is the app doing work vs GC?
# 3. Check "Goroutine analysis" — find goroutines that trigger GC
# 4. Look at the timeline: do latency spikes align with GC events?
```

---

## CI/CD Leak/Race Detection Integration

### GitHub Actions: Race Detection + Leak Detection

```yaml
name: Go Leak & Race Detection

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  race-detection:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_PASSWORD: test
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-go@v5
        with:
          go-version: '1.22'

      - name: Race Detection Tests
        run: go test -race -count=5 ./...
        # -race: enable race detector
        # -count=5: run tests 5 times to catch flaky races

      - name: Goroutine Leak Detection
        run: go test -v -count=1 ./... -run . 2>&1 | tee test_output.log
        # After tests, check for goroutine leaks in test teardown

      - name: Leak Sanitizer (using uber-go/goleak)
        run: go test -v ./... -gcflags="-d=checkptr=0"
        # Alternative: use testing.T.Cleanup with goleak.VerifyTestMain

  benchmark-regression:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-go@v5
        with:
          go-version: '1.22'

      - name: Run Benchmarks
        run: go test -bench=. -benchmem -count=5 ./... | tee bench_output.txt

      - name: Compare with Previous (using benchstat)
        run: |
          go install golang.org/x/perf/cmd/benchstat@latest
          benchstat base_bench.txt bench_output.txt
        # Fails PR if benchmark regression > 10%

  fuzz-testing:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-go@v5
        with:
          go-version: '1.22'

      - name: Fuzz Tests
        run: |
          for fuzz_test in $(grep -rl "func Fuzz" ./...); do
            go test -fuzz=. -fuzztime=30s ./$(dirname $fuzz_test)
          done

  profiling-in-ci:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-go@v5
        with:
          go-version: '1.22'

      - name: Build with Profiling
        run: go build -o service ./cmd/service

      - name: Start Service
        run: |
          ./service &
          sleep 2

      - name: Run Load Test
        run: |
          go install github.com/rakyll/hey@latest
          hey -n 10000 -c 100 http://localhost:8080/api/health

      - name: Capture Profiles
        run: |
          curl -o cpu.prof http://localhost:6060/debug/pprof/profile?seconds=10
          curl -o heap.prof http://localhost:6060/debug/pprof/heap
          curl -o goroutine.prof http://localhost:6060/debug/pprof/goroutine

      - name: Analyze Profiles
        run: |
          go tool pprof -top cpu.prof
          go tool pprof -top heap.prof

      - name: Check Goroutines (fail if >1000)
        run: |
          count=$(curl -s http://localhost:6060/debug/pprof/goroutine?debug=1 | head -1 | awk '{print $1}')
          if [ "$count" -gt 1000 ]; then
            echo "ERROR: Goroutine count $count exceeds threshold 1000"
            exit 1
          fi

      - name: Archive Profiles
        uses: actions/upload-artifact@v4
        with:
          name: profiles
          path: "*.prof"
```
