# Session 09: JVM Concurrency Model & Thread Pools

## 1. Why This Topic Exists

Spring Boot runs on the JVM. Every HTTP request arrives on a JVM thread. Every `@Async` method runs on a thread pool. Every database connection borrows a thread. Understanding JVM concurrency is understanding **why your application is slow, stuck, or crashed under load**.

**Staff engineer insight**: 90% of production performance issues in Spring Boot applications are thread-related: pool exhaustion, thread starvation, blocking calls on limited threads. The remaining 10% are database queries and memory leaks. Master threads, master production.

## 2. Mental Model

```
JVM Process
│
├── Heap (shared)
│   ├── Application objects
│   └── Thread-shared data
│
├── Thread 1 (http-nio-8080-exec-1)
│   ├── Java Stack (local variables, method calls)
│   └── OS Thread (mapped to OS thread)
│
├── Thread 2 (http-nio-8080-exec-2)
│   ├── Java Stack
│   └── OS Thread
│
├── Thread 3 (scheduling-1)
│   ├── Java Stack
│   └── OS Thread
│
└── ... up to max threads

Java threads ARE OS threads (1:1 mapping, pre-Project Loom).
Each thread consumes ~1MB stack memory (configurable with -Xss).
10,000 threads = ~10GB just for thread stacks.
```

### Thread Types in a Spring Boot Application

```
┌─────────────────────────────────────────────────────────────┐
│                   THREAD POOLS                               │
├─────────────────┬──────────────┬─────────────┬──────────────┤
│ Tomcat          │ @Async       │ Scheduled   │ JVM Internal │
│ (servers)       │ (application)│ (timers)    │ (GC, etc)    │
│                 │              │             │              │
│ http-nio-8080-  │ task-1       │ scheduling-1│ GC Threads   │
│ exec-1          │ task-2       │             │ JIT Threads  │
│ exec-2          │ task-3       │             │ ...          │
│ exec-3          │ ...          │             │              │
│ ...             │              │             │              │
│ (default: 200)  │ (no default) │ (default:1) │              │
└─────────────────┴──────────────┴─────────────┴──────────────┘
```

## 3. Internal Architecture

### Tomcat Thread Pool

```yaml
server:
  tomcat:
    threads:
      max: 200          # Maximum worker threads
      min-spare: 10     # Minimum idle threads kept alive
    accept-count: 100   # Queue size when all threads busy
    max-connections: 10000  # Maximum connections (including waiting)
```

```
Request lifecycle with Tomcat thread pool:

1. Client connects → OS accepts TCP connection
2. Connection sits in Tomcat's connection queue (accept-count)
3. A worker thread from the pool picks up the connection
4. Thread reads HTTP request, calls DispatcherServlet
5. Thread runs through filters → controller → service → DB
6. Thread writes HTTP response
7. Thread returns to pool

If all 200 threads are busy:
  - Up to 100 requests join the accept queue (waiting)
  - Request 101+ gets "Connection refused" or timeout
  - Existing threads may be BLOCKED (DB, external API) or RUNNING (CPU-bound)
```

### Thread States and What They Mean

```
NEW → RUNNABLE → BLOCKED/WAITING/TIMED_WAITING → TERMINATED

RUNNABLE:     Thread is executing or ready to execute (on CPU queue)
BLOCKED:      Thread is waiting to enter a synchronized block/method
WAITING:      Thread.wait(), LockSupport.park() — waiting indefinitely
TIMED_WAITING: Thread.sleep(), Lock.tryLock(timeout) — waiting with timeout
TERMINATED:   Thread has finished execution

In production:
  - Many RUNNABLE threads = CPU-bound (good or bad depending on load)
  - Many BLOCKED threads = Lock contention (BAD — serialized execution)
  - Many WAITING threads = Idle pool threads, or waiting for async results
  - Many threads in socketRead = Waiting for DB/external API response
```

### Thread Pool Internals (ThreadPoolExecutor)

```java
// Simplified ThreadPoolExecutor logic
public class ThreadPoolExecutor {
    BlockingQueue<Runnable> workQueue;
    Set<Worker> workers;
    int corePoolSize;   // Always keep this many threads alive
    int maxPoolSize;    // Maximum threads allowed
    
    public void execute(Runnable task) {
        if (workerCount < corePoolSize) {
            addWorker(task);  // Create new thread
        } else if (workQueue.offer(task)) {
            // Queue accepted, will be picked up later
        } else if (workerCount < maxPoolSize) {
            addWorker(task);  // Create new thread (emergency)
        } else {
            reject(task);     // RejectedExecutionException
        }
    }
}
```

**Critical insight**: The queue fills BEFORE new threads are created. If you set `corePoolSize=10` and `maxPoolSize=100`, the first 10 tasks create threads. Tasks 11-110 go to the queue. Only task 111 (if queue size=100) triggers a new thread. This means with `LinkedBlockingQueue` (unbounded), `maxPoolSize` is effectively ignored — threads never grow beyond `corePoolSize`.

## 4. Runtime Behavior

### A Day in the Life of a Request Thread

```
Time 0.000ms:    Thread created (or borrowed from pool)
Time 0.001ms:    Accept TCP connection
Time 0.050ms:    Read HTTP headers
Time 0.100ms:    Enter DispatcherServlet
Time 0.150ms:    AuthenticationFilter.doFilter()
Time 0.200ms:    Controller method invoked
Time 0.210ms:    Service method invoked
Time 0.220ms:    @Transactional begins → getConnection() from HikariCP
                    │
                    │ Thread: WAITING (for DB connection)
                    │ Time: 1-5ms normally, 500ms+ if pool exhausted
                    │
Time 0.225ms:    Repository.save() called
                    │
                    │ Thread: RUNNING in JDBC driver
                    │ Sends SQL to DB via socket
                    │ Thread: WAITING (socketRead) for DB response
                    │ Time: 5-50ms normally, seconds if slow query
                    │
Time 50ms:       DB responds, thread processes ResultSet
Time 55ms:       @Transactional commits
Time 60ms:       Write HTTP response to socket
Time 62ms:       Thread returns to pool

Total wall time: 62ms
Thread active time: ~15ms (CPU)
Thread waiting time: ~47ms (DB, pool)
Thread utilization: 24%
```

This is typical: threads spend 70-80% of their time WAITING. This is why:
1. You need many threads (to overlap waiting)
2. Virtual Threads are revolutionary (they make waiting cheap)

### Thread Pool Exhaustion Simulation

```
System: 200 Tomcat threads, 10 HikariCP connections, 50ms avg DB query

Scenario: 300 concurrent requests

t=0ms:    200 requests grab Tomcat threads
           10 requests grab DB connections
           190 Tomcat threads BLOCKED waiting for DB connections

t=50ms:   10 DB queries complete, connections released
           10 new requests grab DB connections
           180 Tomcat threads still BLOCKED

t=500ms:  All 300 requests completed
           Average latency: 250ms (vs 50ms at low load)
           
Problem: Threads wait for connections, connections are scarce.
         Thread count >> Connection count = Threads waste time waiting.
         
Solution: Connection pooling should match thread pool:
          threads × expected DB time ratio = connections needed
          200 × (50ms/62ms) ≈ 160 connections needed for optimal throughput
          OR: Use fewer threads with async DB access
          OR: Use Virtual Threads (no pool needed, effectively unlimited)
```

## 5. Request Flow Diagrams

### Thread Lifecycle During a Request

```
                     ┌─────────────┐
                     │   POOL      │
                     │ (threads)   │
                     └──┬───┬──────┘
                        │   │
              borrow    │   │  return
                        │   │
    ┌───────────────────┼───┼──────────────────────┐
    │                   ▼   ▲                      │
    │  ┌──────────────────────┐                    │
    │  │  RUNNABLE            │                    │
    │  │  (parsing request)   │                    │
    │  └──────────┬───────────┘                    │
    │             │                                │
    │             ▼                                │
    │  ┌──────────────────────┐                    │
    │  │  WAITING             │                    │
    │  │  (waiting for DB     │                    │
    │  │   connection)        │  ← HikariCP pool   │
    │  └──────────┬───────────┘                    │
    │             │                                │
    │             ▼                                │
    │  ┌──────────────────────┐                    │
    │  │  RUNNABLE            │                    │
    │  │  (executing query)   │                    │
    │  └──────────┬───────────┘                    │
    │             │                                │
    │             ▼                                │
    │  ┌──────────────────────┐                    │
    │  │  WAITING             │                    │
    │  │  (socketRead,        │                    │
    │  │   waiting for DB     │  ← PostgreSQL      │
    │  │   response)          │                    │
    │  └──────────┬───────────┘                    │
    │             │                                │
    │             ▼                                │
    │  ┌──────────────────────┐                    │
    │  │  RUNNABLE            │                    │
    │  │  (processing result, │                    │
    │  │   building response) │                    │
    │  └──────────┬───────────┘                    │
    │             │                                │
    │             ▼                                │
    │  ┌──────────────────────┐                    │
    │  │  WAITING             │                    │
    │  │  (socketWrite,       │                    │
    │  │   sending response)  │                    │
    │  └──────────────────────┘                    │
    └──────────────────────────────────────────────┘
```

## 6. Lifecycle Diagrams

### Thread Pool Lifecycle

```
T0: Application starts
    ├── Tomcat thread pool created
    │   ├── min-spare=10 threads pre-created (or 0 if not set)
    │   └── Threads in WAITING state (parked, waiting for tasks)
    │
    ├── HikariCP connection pool created
    │   ├── 10 connections established to PostgreSQL
    │   └── Connections idle in pool
    │
    ├── @Async thread pool created (if configured)
    │   └── corePoolSize threads pre-created

T1: First request arrives
    ├── Tomcat borrows a thread from pool
    ├── Thread processes request (RUNNABLE → WAITING → RUNNABLE cycle)
    ├── Thread returns to pool (WAITING for next task)

T2: Peak load (200 concurrent requests)
    ├── All 200 Tomcat threads busy
    ├── Accept queue filling (100 max)
    ├── HikariCP connections all busy (10 max)
    ├── 190 threads BLOCKED waiting for connections
    ├── 10 threads RUNNABLE (executing queries)
    └── Response times climb exponentially

T3: Load subsides
    ├── Threads complete, return to pool
    ├── Excess threads above min-spare eventually time out and die
    ├── Connections returned to HikariCP, idle
    └── System returns to steady state
```

### Thread Pool Tuning Lifecycle

```
1. Observe: Monitor thread count, queue depth, response time
2. Identify bottleneck: CPU? DB? External API? Connection pool?
3. Tune: Adjust pool sizes, timeouts
4. Verify: Load test the new configuration
5. Repeat until SLA met
```

## 7. Source Code Reading Guide

1. **`java.util.concurrent.ThreadPoolExecutor`**: The source of truth for thread pool behavior
   - `execute(Runnable)`: Task submission logic
   - `addWorker(Runnable)`: Thread creation
   - `runWorker(Worker)`: Main worker loop
   - `processWorkerExit()`: Thread death
   
2. **`org.apache.tomcat.util.threads.ThreadPoolExecutor`**: Tomcat's thread pool
   - Extends standard ThreadPoolExecutor
   - `execute(Runnable)`: Overrides to handle Tomcat-specific lifecycle

3. **`com.zaxxer.hikari.HikariDataSource`**: Connection pool
   - `getConnection()`: Connection borrowing with timeout
   - `HikariPool`: Internal pool implementation

4. **`org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor`**: Spring's wrapper
   - `initialize()`: Sets up the executor
   - `execute(Runnable, long startTimeout)`: Adds timeout to standard behavior

## 8. Production Failure Scenarios

### Scenario 1: Thread Pool Exhaustion

**Symptom**: API returns 503 Service Unavailable after 30 seconds. Health check fails. Application restart fixes it temporarily.

**Root cause**: All 200 Tomcat threads busy. Each thread waiting for a slow downstream service (10s timeout × 0 connection timeout). No threads available for new requests.

**Diagnosis**:
```bash
# Thread dump
jstack <pid> > threaddump.txt

# Analysis
grep "http-nio" threaddump.txt | wc -l  # 200 threads
grep "WAITING" threaddump.txt | wc -l   # 180 waiting on slow service
grep "BLOCKED" threaddump.txt | wc -l   # 20 blocked on connection pool
```

**Resolution**:
- Short term: Increase `server.tomcat.threads.max` (band-aid)
- Real fix: Add circuit breaker (Resilience4j) on slow downstream service
- Add timeout: `spring.cloud.loadbalancer.retry.timeout=2000`
- Consider async/non-blocking: WebClient instead of RestTemplate

### Scenario 2: Connection Pool Exhaustion

**Symptom**: `HikariPool-1 - Connection is not available, request timed out after 30000ms`

**Root cause**: All HikariCP connections in use. No connection returned within timeout.

**Diagnosis**:
```bash
# HikariCP metrics
/actuator/metrics/hikaricp.connections.active
/actuator/metrics/hikaricp.connections.pending

# If pending > 0 for more than a few seconds, pool is undersized
```

**Resolution**:
- Check for connection leaks: Are connections always closed? `@Transactional` boundaries correct?
- Increase `spring.datasource.hikari.maximumPoolSize`
- Formula: `pool_size = Tn * (Cm - 1) + 1` where Tn=max threads, Cm=max concurrent connections per thread

### Scenario 3: Deadlock

**Symptom**: Application hangs completely. Zero CPU usage. Zero requests processed. No errors in logs.

**Root cause**: Thread A holds Lock1, waiting for Lock2. Thread B holds Lock2, waiting for Lock1.

**Diagnosis**:
```bash
jstack <pid> | grep -A 30 "deadlock"
# Found one Java-level deadlock:
# "Thread-1": waiting to lock Lock2, held by "Thread-2"
# "Thread-2": waiting to lock Lock1, held by "Thread-1"
```

**Resolution**: Consistent lock ordering. Or better: avoid explicit locks; use concurrent data structures.

## 9. Debugging Techniques

### Thread Dump Analysis Toolkit

```bash
# Take 3 thread dumps, 5 seconds apart (to see what's stuck vs transitory)
for i in {1..3}; do
  jstack <pid> > threaddump_$i.txt
  sleep 5
done

# Find threads in same method across all dumps (stuck threads)
grep "at com.example" threaddump_1.txt | sort | uniq -c | sort -rn

# Count thread states
grep "java.lang.Thread.State" threaddump_1.txt | sort | uniq -c

# Find blocking threads (they're causing others to block)
grep -B 1 "locked <0x" threaddump_1.txt
```

### Spring Boot Actuator Thread Metrics

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "threaddump,metrics"
  metrics:
    export:
      prometheus:
        enabled: true
```

Prometheus queries:
```promql
# Thread pool utilization
jvm_threads_live_threads / jvm_threads_peak_threads

# Tomcat busy threads
tomcat_threads_busy_threads / tomcat_threads_config_max_threads

# HikariCP pending connections
hikaricp_connections_pending
```

## 10. Observability Considerations

Every thread pool should export:
- Active threads
- Queued tasks
- Completed tasks
- Rejected tasks
- Pool size (current, max)
- Queue utilization (%)
- Task wait time (time in queue)
- Task execution time

## 11. Performance Implications

| Configuration | Throughput (req/s) | P99 Latency | Thread Count |
|--------------|-------------------|-------------|-------------|
| 10 threads, 10 connections | 200 | 50ms | 10 |
| 10 threads, 5 connections | 100 | 100ms | 10 (5 waiting) |
| 200 threads, 10 connections | 200 | 300ms | 200 (190 waiting!) |
| 200 threads, 100 connections | 2000 | 50ms | 200 |

**Key insight**: Adding threads WITHOUT adding connections does NOT increase throughput. It increases latency and memory usage. The bottleneck is the connection pool, not the thread pool.

## 12. Architecture Implications

### Thread Model Decisions

| Use Case | Recommended Thread Model |
|----------|------------------------|
| CPU-bound (computation, encoding) | Threads = CPU cores |
| I/O-bound (DB, APIs, files) | Threads = connections × 2 |
| Mixed workload | Separate pools for CPU vs I/O tasks |
| High concurrency (10K+ concurrent) | Virtual Threads (Project Loom) |

### When to Use @Async

```java
// Good: Fire-and-forget notification
@Async
public void sendOrderConfirmationEmail(Order order) {
    emailService.send(order); // ~500ms, don't make user wait
}

// Good: Parallel independent operations
@Async
CompletableFuture<InventoryStatus> checkInventory(Order order) { ... }

@Async
CompletableFuture<PaymentResult> processPayment(Order order) { ... }

// Wait for both
CompletableFuture.allOf(inventory, payment).join();

// Bad: @Async on something that the caller immediately blocks on
Order order = orderService.createAsync(order).get(); // Blocks a thread anyway
```

## 13. Team Ownership Implications

Thread pool configuration should be owned by the platform/DevOps team with defaults. Service teams can override if they understand their workload profile. Defaults should be safe (prevent exhaustion) rather than aggressive (maximize throughput).

## 14. Interview Questions

1. **"Your API has 200 Tomcat threads and 10 HikariCP connections. P99 latency is 50ms at 200 RPS but 3000ms at 500 RPS. Why?"**
   - **Answer**: At 200 RPS, 200 threads × 50ms = 10 concurrent requests → 10 connections sufficient. At 500 RPS, 500 × 50ms = 25 concurrent requests → 15 requests are queuing for connections. Each wait adds ~connection-timeout / queue-position of latency. Solution: increase connections to match concurrency, or reduce per-request DB time.

2. **"When should you use `CompletableFuture` vs `@Async`?"**
   - **Answer**: `CompletableFuture` for composing multiple async operations within a request. `@Async` for fire-and-forget or truly independent background work. `CompletableFuture` keeps the composition logic in one place; `@Async` separates it across classes. Use `CompletableFuture` for request-scoped parallelism; `@Async` for background work that outlives the request.

3. **"How do Virtual Threads change thread pool design?"**
   - **Answer**: They eliminate the need for thread pools. With Virtual Threads, you can create one thread per request (even 1M concurrent requests) because virtual threads are cheap (~200 bytes vs ~1MB for platform threads). You still need connection pools (DB connections are still limited), but the thread count is no longer a bottleneck. This simplifies architecture: no more `@Async` thread pools, no more thread pool tuning, no more reactive programming for I/O-bound workloads.

## 15. Hands-On Exercises

1. **Simulate thread pool exhaustion**: Create an endpoint that sleeps for 10 seconds. Set `server.tomcat.threads.max=5`. Send 10 concurrent requests. Observe thread pool exhaustion via Actuator and thread dumps.

2. **Tune HikariCP**: Benchmark your application with different connection pool sizes (5, 10, 20, 50). Find the optimal throughput point.

3. **Implement a thread pool monitor**: Export custom metrics for all thread pools in the application. Build a Grafana dashboard showing pool utilization over time.

## 16. Advanced Challenges

1. **Write a custom thread pool**: Implement a thread pool that rejects tasks based on queue wait time (not just queue size). If a task would wait >5s in the queue, reject it immediately with 503.

2. **Implement graceful degradation**: When the Tomcat accept queue is >80% full, have a filter reject non-critical requests with 503 while still processing critical requests.

3. **Build a thread pool simulator**: Simulate 10 different thread pool configurations under varying load patterns. Identify the optimal configuration for each load pattern without running a full application.
