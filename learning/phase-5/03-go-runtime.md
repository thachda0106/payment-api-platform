# Module 03 — Go Runtime & Production Practices

## 3.1 GMP Scheduler

Go's scheduler maps M goroutines onto N OS threads using P logical processors. GOMAXPROCS (default: number of CPU cores) controls how many OS threads can execute Go code simultaneously.

```
┌────────────────────────────────────────────────────────┐
│                   Go Runtime Scheduler                   │
│                                                         │
│  G (Goroutines)        P (Processors)     M (Machines)  │
│  ┌──┐ ┌──┐ ┌──┐      ┌──┐               ┌──┐          │
│  │G1│ │G2│ │G3│  ───▶│P1│ (local queue)  │M1│ (thread) │
│  └──┘ └──┘ └──┘      │  │──────────────▶ │  │          │
│                       └──┘               └──┘          │
│  ┌──┐ ┌──┐ ┌──┐      ┌──┐               ┌──┐          │
│  │G4│ │G5│ │G6│  ───▶│P2│──────────────▶│M2│          │
│  └──┘ └──┘ └──┘      └──┘               └──┘          │
│                                                         │
│  Global run queue: Gs without a P end up here           │
│  Work stealing: Idle P steals Gs from another P         │
│  Handoff: When G blocks in syscall, M is handed off     │
└────────────────────────────────────────────────────────┘
```

**What happens when a goroutine blocks on I/O**:
1. G makes a syscall. M (OS thread) enters the syscall with G.
2. P detaches from M. P picks up a new M to continue executing other Gs.
3. When syscall returns: G is placed back in a run queue. M parks (or takes new G).
4. Result: P is NEVER idle during I/O — it keeps executing other Gs.

**What happens when a goroutine does CPU work too long**:
- Sysmon (system monitor thread) preempts G after ~10ms.
- G is placed back in the global run queue.
- Other Gs get a chance to run.

## 3.2 Escape Analysis

The compiler decides whether a variable goes on STACK (fast) or HEAP (GC-managed).

```go
// STACK: lifetime limited to function scope
func processPayment() int64 {
    amount := int64(100000)  // Stays on stack — no heap allocation
    return amount + 5000
}

// HEAP: pointer escapes the function
func newPayment() *Payment {
    p := Payment{Amount: 100000}  // &p escapes → heap allocation
    return &p
}

// See escape analysis decisions
// go build -gcflags="-m" main.go
// Output:
// ./main.go:5:2: moved to heap: p
```

## 3.3 Garbage Collection

Go uses a concurrent mark-sweep collector with sub-millisecond pause targets.

**GC Pacer**: The Go GC adjusts its pace based on allocation rate. If you allocate faster, the GC runs more often. Target: GC CPU time ≤ 25% of total.

```bash
# GC tuning via GOGC (default 100)
# GOGC=100: GC triggers when heap doubles since last GC
# GOGC=200: GC triggers less often (uses more memory, less CPU)
# GOGC=off: Disable GC (not recommended)

GODEBUG=gctrace=1 ./myapp  # Print GC traces
# Output:
# gc 1 @0.005s 0%: 0.022+0.27+0.010 ms clock, 0.17+0.16/0.16/0+0.080 ms cpu, 4->4->1 MB
# │  │  │      │    │     │                                        │    │    │   │
# │  │  │      │    │     └─ STW time (sweep termination)          │    │    │   └─ Live heap after GC
# │  │  │      │    └─ Concurrent mark/scan time                   │    │    └─ Heap before GC
# │  │  │      └─ STW time (mark termination)                      │    └─ Heap at GC start
# │  │  └─ CPU utilization during GC                               └─ Goal heap size
# │  └─ # of GC cycles since start
# └─ Time since program start
```

## 3.4 Profiling with pprof

```go
import (
    _ "net/http/pprof"
    "net/http"
)

func main() {
    go func() { http.ListenAndServe(":6060", nil) }()
    // Now access: http://localhost:6060/debug/pprof/
}
```

```bash
# CPU profile (30 seconds)
go tool pprof http://localhost:6060/debug/pprof/profile?seconds=30

# Heap profile
go tool pprof http://localhost:6060/debug/pprof/heap

# Goroutine profile
go tool pprof http://localhost:6060/debug/pprof/goroutine

# In pprof interactive mode:
top       # Top CPU consumers
list fn   # Show source with per-line profiling
web       # Generate call graph (requires graphviz)
peek      # Inline view
```

## 3.5 Race Detector

```bash
go test -race ./...
go run -race main.go
```

The race detector instruments memory accesses and reports when two goroutines access the same memory concurrently, at least one being a write, without synchronization. It adds ~10x memory overhead and ~2x CPU overhead.

```go
// DATA RACE: two goroutines write to counter without sync
var counter int
go func() { counter++ }()
go func() { counter++ }()
// Race detector reports: "DATA RACE: Write at 0x... by goroutine 7"

// FIX: Use sync/atomic
var counter atomic.Int64
go func() { counter.Add(1) }()
go func() { counter.Add(1) }()
```

## 3.6 Testing

```go
// Table-driven tests (Go convention)
func TestDebit(t *testing.T) {
    tests := []struct {
        name    string
        balance int64
        amount  int64
        want    bool
        wantBal int64
    }{
        {"sufficient balance", 100000, 30000, true, 70000},
        {"insufficient balance", 10000, 50000, false, 10000},
        {"exact balance", 100000, 100000, true, 0},
    }
    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            w := &Wallet{balance: tt.balance}
            got := w.Debit(tt.amount)
            if got != tt.want { t.Errorf("Debit() = %v, want %v", got, tt.want) }
            if w.balance != tt.wantBal { t.Errorf("balance = %d, want %d", w.balance, tt.wantBal) }
        })
    }
}

// Benchmarks
func BenchmarkDebit(b *testing.B) {
    wallet := &Wallet{balance: 1_000_000}
    b.ResetTimer()
    for i := 0; i < b.N; i++ { wallet.Debit(1000); wallet.balance = 1_000_000 }
}

// Fuzz testing
func FuzzDebit(f *testing.F) {
    f.Add(int64(100000), int64(30000))
    f.Fuzz(func(t *testing.T, balance, amount int64) {
        if amount < 0 { t.Skip() }
        w := &Wallet{balance: balance}
        result := w.Debit(amount)
        if result && w.balance != balance - amount { t.Errorf("balance mismatch") }
    })
}
```

## 3.7 Production Patterns

### Graceful Shutdown

```go
func main() {
    srv := &http.Server{Addr: ":8080"}

    // Run server in goroutine
    go func() { srv.ListenAndServe() }()

    // Wait for interrupt signal
    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    <-quit

    // Graceful shutdown with timeout
    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()
    if err := srv.Shutdown(ctx); err != nil { log.Fatal("forced shutdown:", err) }
}
```

### Structured Logging (slog — Go 1.21+)

```go
logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
logger.Info("payment_processed",
    "payment_id", paymentID,
    "amount", amount,
    "duration_ms", elapsed.Milliseconds(),
)
// Output: {"time":"...","level":"INFO","msg":"payment_processed","payment_id":"...","amount":100000,"duration_ms":45}
```

## 3.8 Exercises

### Ex 3.1 — Escape Analysis
Write functions that allocate on stack and heap. Use `go build -gcflags="-m"` to verify. Write a function that INTENTIONALLY causes a heap allocation and explain why.

### Ex 3.2 — pprof Analysis
Write a program that: (a) creates a goroutine leak, (b) has high CPU usage, (c) allocates frequently. Use pprof to identify each problem. Fix them. Re-profile and verify the fix.

### Ex 3.3 — Race Detector
Write code with a data race. Run `go test -race`. Read the race detector output and identify the race. Fix with sync/atomic. Verify the race is gone.

## 3.9 Self-Assessment

- [ ] Can explain the GMP scheduler: what happens when a goroutine makes a syscall
- [ ] Can read `go build -gcflags="-m"` output to identify heap allocations
- [ ] Can interpret GC trace output (gc 1 @0.005s...)
- [ ] Can use pprof to analyze CPU, heap, and goroutine profiles
- [ ] Can write table-driven tests and benchmarks
- [ ] Can implement graceful shutdown with context cancellation
