# Module 02 — Concurrency in Go

## 2.1 Goroutines

Goroutines are lightweight, user-space threads managed by the Go runtime (M:N scheduling). They start with a 2KB stack that grows/shrinks dynamically. You can have MILLIONS of goroutines.

```go
// Launch a goroutine
go func() {
    fmt.Println("running concurrently")
}()

// Wait for goroutine to finish (Naive — use WaitGroup instead!)
time.Sleep(time.Second)  // DON'T DO THIS IN PRODUCTION!

// Correct: sync.WaitGroup
var wg sync.WaitGroup
for i := 0; i < 10; i++ {
    wg.Add(1)  // Increment counter
    go func(id int) {
        defer wg.Done()  // Decrement counter when done
        process(id)
    }(i)  // Pass i as argument! (closure captures variable, not value)
}
wg.Wait()  // Block until all goroutines finish
```

**Goroutine leaks**: A goroutine that never exits. Causes: blocked on channel, blocked on mutex, infinite loop without `context.Done()`.

```go
// LEAK: goroutine blocks forever on ch, never exits
ch := make(chan int)
go func() { ch <- 42 }()  // Blocks because no receiver!
// Fix: use buffered channel or ensure receiver exists
```

## 2.2 Channels

Channels are typed conduits for communication between goroutines. "Don't communicate by sharing memory; share memory by communicating."

```go
// Unbuffered channel: sender blocks until receiver is ready (synchronous)
ch := make(chan int)
go func() { ch <- 42 }()  // Blocks until main receives
val := <-ch  // 42

// Buffered channel: sender blocks only when buffer is full (asynchronous)
ch := make(chan int, 10)  // Buffer 10 ints
ch <- 1; ch <- 2; ch <- 3  // Doesn't block (buffer has room)

// Close channel: signals "no more values"
close(ch)
// Reading from closed channel: returns zero value immediately
v, ok := <-ch  // ok=false means channel is closed + empty
// Sending to closed channel: PANICS!

// Range over channel: reads until channel is closed
for v := range ch {
    fmt.Println(v)
}
```

### Channel Patterns

```go
// Pipeline: producer → processor → consumer
func producer(out chan<- int) {
    for i := 0; i < 10; i++ { out <- i }
    close(out)
}
func processor(in <-chan int, out chan<- int) {
    for v := range in { out <- v * v }
    close(out)
}
func consumer(in <-chan int) {
    for v := range in { fmt.Println(v) }
}

// Fan-out: multiple workers reading from same channel
func worker(id int, jobs <-chan int, results chan<- int) {
    for job := range jobs { results <- process(id, job) }
}
jobs := make(chan int, 100); results := make(chan int, 100)
for w := 0; w < 5; w++ { go worker(w, jobs, results) }  // 5 concurrent workers

// Fan-in: multiple producers, one consumer — use WaitGroup + close in goroutine
go func() { wg.Wait(); close(results) }()
```

## 2.3 Select

`select` is like `switch` for channels. It blocks until one case is ready. If multiple are ready, picks one randomly (prevents starvation).

```go
select {
case msg := <-ch1:
    fmt.Println("from ch1:", msg)
case msg := <-ch2:
    fmt.Println("from ch2:", msg)
case ch3 <- 42:
    fmt.Println("sent to ch3")
case <-time.After(5 * time.Second):
    fmt.Println("timeout!")
case <-ctx.Done():
    fmt.Println("cancelled:", ctx.Err())
default:
    fmt.Println("nothing ready — non-blocking")
}
```

## 2.4 sync Package

```go
// Mutex: mutual exclusion
var mu sync.Mutex
var balance int64
func debit(amount int64) bool {
    mu.Lock(); defer mu.Unlock()
    if balance >= amount { balance -= amount; return true }
    return false
}

// RWMutex: multiple readers, exclusive writer
var rw sync.RWMutex
func getBalance() int64 { rw.RLock(); defer rw.RUnlock(); return balance }
func setBalance(b int64) { rw.Lock(); defer rw.Unlock(); balance = b }

// Once: execute exactly once (singleton initialization)
var once sync.Once
var instance *Service
func GetService() *Service { once.Do(func() { instance = &Service{} }); return instance }

// Pool: reusable object pool (reduce GC pressure)
var bufPool = sync.Pool{New: func() any { return new(bytes.Buffer) }}
buf := bufPool.Get().(*bytes.Buffer)
// ... use buf ...
buf.Reset(); bufPool.Put(buf)  // Return to pool
```

## 2.5 Context

Context carries deadlines, cancellation signals, and request-scoped values across API boundaries.

```go
// Create contexts
ctx := context.Background()                 // Root context (main, init, tests)
ctx, cancel := context.WithCancel(ctx)      // Cancellable
ctx, cancel := context.WithTimeout(ctx, 5*time.Second)  // Timeout
ctx, cancel := context.WithDeadline(ctx, time.Now().Add(5*time.Second))
defer cancel()  // ALWAYS call cancel to avoid context leak!

// Propagate context through call chain
func ProcessPayment(ctx context.Context, payment *Payment) error {
    // Check cancellation before expensive work
    select {
    case <-ctx.Done(): return ctx.Err()
    default:
    }

    // Pass context to downstream calls
    if err := fraudCheck(ctx, payment); err != nil { return err }
    if err := ledgerWrite(ctx, payment); err != nil { return err }
    return nil
}

// HTTP server: extract context from request
func handler(w http.ResponseWriter, r *http.Request) {
    ctx := r.Context()  // Cancelled when client disconnects
    // ...
}
```

## 2.6 Concurrency Patterns for Payment Processing

```go
// Worker pool for settlement batch processing
func ProcessSettlementBatch(payments []Payment, numWorkers int) map[string]int64 {
    type Job struct { Index int; Payment Payment }
    type Result struct { MerchantID string; Amount int64 }

    jobs := make(chan Job, len(payments))
    results := make(chan Result, len(payments))

    // Start workers
    var wg sync.WaitGroup
    for w := 0; w < numWorkers; w++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            for job := range jobs {
                amount := calculateSettlement(job.Payment)
                results <- Result{job.Payment.MerchantID, amount}
            }
        }()
    }

    // Send jobs
    for i, p := range payments { jobs <- Job{i, p} }
    close(jobs)

    // Wait for workers, then close results
    go func() { wg.Wait(); close(results) }()

    // Collect results
    settlements := make(map[string]int64)
    for r := range results { settlements[r.MerchantID] += r.Amount }
    return settlements
}
```

## 2.7 Exercises

### Ex 2.1 — Goroutine Pipeline
Implement a 3-stage pipeline: `generate(numbers) → square(numbers) → print(numbers)`. Use channels for communication. Each stage runs as a goroutine.

### Ex 2.2 — Rate-Limited Worker Pool
Implement a worker pool that processes jobs with a rate limit (max N jobs/second). Use `time.Ticker` for rate limiting. Track metrics: jobs processed, errors, average latency.

### Ex 2.3 — Context Propagation
Implement a payment processing function that: (a) checks context cancellation before each step, (b) passes context with 50ms timeout to fraud check, (c) passes context to ledger write. Test with cancelled context and timeout context.

---

## 2.8 Self-Assessment

- [ ] Can explain when to use buffered vs unbuffered channels
- [ ] Understand the difference between `wg.Add(1)` (before goroutine) and `defer wg.Done()`
- [ ] Can write a select statement with timeout (time.After) and cancellation (ctx.Done())
- [ ] Know when to use `sync.Mutex` vs channels
- [ ] Understand that `context.Context` should always be the FIRST parameter
- [ ] Can identify a goroutine leak by reading code
