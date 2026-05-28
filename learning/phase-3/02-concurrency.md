# Module 02 — Concurrency & Virtual Threads

## 2.1 Why Concurrency Matters for Payment Platforms

The Payment Service handles 10,000 requests/second. Each request must: validate input, check fraud score, calculate fees, write journal entries, update balances, publish events. If you process these one at a time, you can handle ~100/second. With concurrency, you handle 10,000.

But concurrency introduces: race conditions (two debits reading the same balance), deadlocks (waiting for locks held by each other), visibility problems (thread A's write not visible to thread B), and resource exhaustion (too many threads).

---

## 2.2 Thread Fundamentals

### Creating Threads

```java
// Platform thread (OS-managed, ~1MB stack)
Thread thread = new Thread(() -> {
    System.out.println("Running in " + Thread.currentThread());
});
thread.start();  // DON'T call run() — that runs synchronously on current thread

// Daemon thread: JVM exits when only daemon threads remain
thread.setDaemon(true);

// Virtual thread (Java 21+, JVM-managed, lightweight)
Thread vThread = Thread.startVirtualThread(() -> {
    System.out.println("Virtual thread: " + Thread.currentThread());
});
```

### Thread States

```
NEW → (start()) → RUNNABLE ⇄ BLOCKED (waiting for lock)
                    ↓           ↑
                 WAITING ← (notify/signal)
                    ↓           ↑
              TIMED_WAITING (sleep/join with timeout)
                    ↓
               TERMINATED
```

```java
Thread t = new Thread(...);
System.out.println(t.getState());  // NEW
t.start();
System.out.println(t.getState());  // RUNNABLE (or BLOCKED if waiting for CPU)
Thread.sleep(100);                 // Main thread → TIMED_WAITING
```

---

## 2.3 Synchronization

### synchronized

```java
// Every Java object has an intrinsic lock (monitor)
public class WalletService {
    private final Map<String, Long> balances = new ConcurrentHashMap<>();

    // synchronized method: lock is 'this'
    public synchronized boolean debit(String accountId, long amount) {
        long balance = balances.getOrDefault(accountId, 0L);
        if (balance < amount) return false;
        balances.put(accountId, balance - amount);
        return true;
    }

    // synchronized block: smaller lock scope, better concurrency
    public boolean debitBetter(String accountId, long amount) {
        synchronized (this) {  // lock only when accessing shared state
            long balance = balances.getOrDefault(accountId, 0L);
            if (balance < amount) return false;
            balances.put(accountId, balance - amount);
            return true;
        }
    }
}
```

### volatile

Guarantees VISIBILITY (not atomicity). A write to a volatile variable is immediately visible to all other threads.

```java
public class PaymentProcessor {
    private volatile boolean running = true;  // Without volatile, other threads may NEVER see the change

    public void shutdown() { running = false; }

    public void processLoop() {
        while (running) {  // Read from main memory every time
            Payment p = queue.poll();
            if (p != null) process(p);
        }
    }
}
```

### Lock and Condition (java.util.concurrent.locks)

More flexible than synchronized: tryLock (timeout), read/write lock (multiple readers, exclusive writer), fairness.

```java
private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

public long getBalance(String accountId) {
    rwLock.readLock().lock();
    try { return balances.get(accountId); }
    finally { rwLock.readLock().unlock(); }  // ALWAYS unlock in finally!
}

public void debit(String accountId, long amount) {
    rwLock.writeLock().lock();
    try { balances.merge(accountId, -amount, Long::sum); }
    finally { rwLock.writeLock().unlock(); }
}
```

### Atomic Classes

Lock-free, CAS-based (Compare-And-Swap). Faster than locks for simple operations. Used by ConcurrentHashMap internally.

```java
private final AtomicLong paymentCount = new AtomicLong(0);
private final AtomicReference<PaymentStatus> status = new AtomicReference<>(PaymentStatus.PENDING);

paymentCount.incrementAndGet();  // Atomic counter
paymentCount.addAndGet(100);

status.compareAndSet(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED);  // CAS: only if currently PENDING
// Returns true if updated, false if another thread changed it first

// AtomicReference for optimistic concurrency
AtomicReference<WalletBalance> walletRef = new AtomicReference<>(currentBalance);
WalletBalance current, updated;
do {
    current = walletRef.get();
    if (current.available < amount) return false;
    updated = new WalletBalance(current.available - amount, current.version + 1);
} while (!walletRef.compareAndSet(current, updated));
// Loop until CAS succeeds — optimistic locking pattern
```

---

## 2.4 Executor Framework

### Thread Pools

```java
// Fixed thread pool: bounded parallelism
ExecutorService pool = Executors.newFixedThreadPool(10);
// CAUTION: unbounded queue! If all 10 threads busy, tasks queue up → OOM

// Cached thread pool: creates new threads as needed, reuses idle ones
ExecutorService cached = Executors.newCachedThreadPool();
// CAUTION: unbounded thread creation → OOM

// Virtual thread executor (Java 21+): PREFERRED for I/O-bound workloads
ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();
// Each task gets its own virtual thread — no pool management needed

// Scheduled executor: delayed/recurring tasks
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
scheduler.schedule(() -> checkFraudRules(), 5, TimeUnit.MINUTES);
scheduler.scheduleAtFixedRate(() -> settleBatch(), 0, 1, TimeUnit.DAYS);
```

### CompletableFuture

Asynchronous computation pipelines. Chain operations without blocking.

```java
// Payment processing pipeline: fraud check → fee calc → ledger write → notify
CompletableFuture<FraudResult> fraudFuture = CompletableFuture.supplyAsync(() ->
    fraudService.check(payment), virtualPool);

CompletableFuture<FeeResult> feeFuture = CompletableFuture.supplyAsync(() ->
    feeService.calculate(payment), virtualPool);

// Wait for both, then proceed
CompletableFuture<PaymentResult> result = fraudFuture
    .thenCombine(feeFuture, (fraud, fee) -> {
        if (fraud.score() > 70) return PaymentResult.DECLINED;
        return ledgerService.createEntry(payment, fee.amount());
    })
    .thenApply(entry -> { notificationService.send(payment); return entry; })
    .exceptionally(ex -> { log.error("Payment failed", ex); return PaymentResult.FAILED; });

// Error handling per stage
fraudFuture
    .completeOnTimeout(FraudResult.ALLOW, 50, TimeUnit.MILLISECONDS)  // Fallback on timeout
    .orTimeout(100, TimeUnit.MILLISECONDS)                            // Exception on timeout
    .exceptionally(ex -> FraudResult.MANUAL_REVIEW);                  // Fallback on error
```

---

## 2.5 Virtual Threads Deep Dive

### How They Work

Virtual threads are M:N scheduled — N virtual threads multiplexed onto M platform (OS) threads. When a virtual thread blocks (I/O, sleep, lock), the JVM UNMOUNTS it from the carrier thread and mounts ANOTHER virtual thread. The carrier thread never blocks — it keeps doing useful work.

```java
// 10,000 concurrent tasks with virtual threads — ~10MB total memory
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<PaymentResult>> futures = new ArrayList<>();
    for (Payment p : payments) {
        futures.add(executor.submit(() -> processPayment(p)));
    }
    for (Future<PaymentResult> f : futures) {
        results.add(f.get());  // Wait for all to complete
    }
}
// Compare: 10,000 platform threads = ~10GB memory, context switch overhead
```

### When NOT to Use Virtual Threads

- **CPU-bound work**: Virtual threads don't speed up CPU-bound tasks — still one CPU core per operation at a time. Use `ForkJoinPool` for CPU-bound parallelism.
- **Pinned threads**: If a virtual thread holds a monitor (`synchronized`) and blocks, it PINs the carrier thread — preventing unmount. Use `ReentrantLock` instead of `synchronized` in virtual thread code.
- **Thread-local variables**: Virtual threads are cheap — don't cache heavy objects in ThreadLocal and forget to clean up.

### Pinning Detection

```java
// JVM flags to detect pinned virtual threads:
// -Djdk.tracePinnedThreads=full   — logs stack trace when pinning occurs
// Pinning locations:
// 1. synchronized blocks/methods (use ReentrantLock instead)
// 2. Native methods (JNI)
// 3. Foreign function calls (FFM API)
```

---

## 2.6 Exercises

### Ex 2.1 — Thread-Safe Wallet
Implement a `Wallet` class with `debit(amount)` and `credit(amount)`. Make it thread-safe using `synchronized`, `ReentrantLock`, and `AtomicLong`. Compare: (a) correctness under 100 concurrent threads, (b) throughput (ops/second) for each approach.

### Ex 2.2 — Payment Pipeline with CompletableFuture
Build the payment pipeline: `fraudCheck → feeCalculation → ledgerWrite → notification`. Each step simulated with random delay (10-50ms). Run 1,000 payments. Compare synchronous vs CompletableFuture vs virtual thread execution times.

### Ex 2.3 — Virtual Thread Web Server
Write an HTTP server that uses virtual threads to handle each connection. Benchmark with 100,000 concurrent connections. Compare memory usage with a fixed-thread-pool version (200 threads).

### Ex 2.4 — Deadlock Detection
Intentionally create a deadlock: two threads each lock on two wallet balances in opposite order. Use `jstack` to capture the thread dump. Identify the deadlocked threads from the output. Fix by always locking in alphabetical order.

---

## 2.7 Self-Assessment

- [ ] Can implement a thread-safe counter using `synchronized`, `ReentrantLock`, and `AtomicLong`
- [ ] Understand the difference between `volatile` (visibility) and `synchronized` (atomicity + visibility)
- [ ] Can choose between `HashMap`, `ConcurrentHashMap`, and `Collections.synchronizedMap`
- [ ] Can compose multiple `CompletableFuture` operations with `thenCombine`, `thenCompose`, `allOf`
- [ ] Understand when virtual threads help (I/O-bound) and when they don't (CPU-bound)
- [ ] Can detect and fix deadlocks using `jstack` thread dumps
- [ ] Know the difference between `submit()` and `execute()` on ExecutorService
