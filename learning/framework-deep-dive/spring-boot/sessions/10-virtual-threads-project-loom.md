# Session 10: Virtual Threads & Project Loom Deep Dive

## 1. Why This Topic Exists

Virtual Threads (JEP 444, delivered in Java 21) are the most significant change to the JVM concurrency model since threads were introduced in Java 1.0. They fundamentally change how Spring Boot applications handle concurrency: no more thread pool tuning, no more reactive programming for I/O-bound workloads, no more `@Async` thread pool exhaustion.

**Staff engineer insight**: Virtual Threads are NOT a performance optimization for all workloads. They are a **concurrency model simplification** for I/O-bound workloads. If your application is CPU-bound, virtual threads provide zero benefit. If your application spends most of its time waiting for databases and APIs, virtual threads eliminate the need for reactive programming and thread pool management.

## 2. Mental Model

### Platform Threads vs Virtual Threads

```
PLATFORM THREADS (Traditional):         VIRTUAL THREADS (Project Loom):

┌──────────────────────┐                ┌──────────────────────┐
│     OS Thread 1      │                │  Virtual Thread 1    │ ← Cheap! (~200 bytes)
│  Stack: ~1MB         │                │  Virtual Thread 2    │
│  Context switch:     │                │  Virtual Thread 3    │
│    expensive (μs)    │                │  Virtual Thread 4    │
└──────────────────────┘                │  ...                 │
│                            │  Virtual Thread 1,000,000 │ ← Possible!
┌──────────────────────┐                └──────────┬───────────┘
│     OS Thread 2      │                           │
│  Stack: ~1MB         │                ┌──────────┴───────────┐
└──────────────────────┘                │   CARRIER THREAD     │
                                        │   (Platform Thread)  │ ← One per CPU core
... up to maybe 10K before              │   Actually executes   │
    memory exhausted                    │   virtual threads     │
                                        └──────────────────────┘

1:1 mapping to OS threads              M:N mapping (many virtual on few carrier)
1MB+ per thread                         ~200 bytes per virtual thread
~10K max threads                        1M+ virtual threads possible
Context switch = OS scheduler           Context switch = JVM (much cheaper)
Thread pool required                    No thread pool needed (unlimited)
```

### The Core Insight

When a virtual thread performs a blocking I/O operation (socket read, DB query, file read), the JVM **unmounts** the virtual thread from its carrier thread. The carrier thread is freed to run other virtual threads. When the I/O completes, the JVM **re-mounts** the virtual thread onto an available carrier thread.

```
Time →

VT-1: [RUN]──[IO wait (unmounted)]──[RUN]──
VT-2: ──[RUN]──[IO wait (unmounted)]──[RUN]
VT-3: ────[RUN]──[IO wait (unmounted)]──

Carrier-1: [VT-1]────[VT-2]────[VT-3]────[VT-1]────[VT-2]────
Carrier-2: [VT-2]────[VT-3]────[VT-1]────[VT-3]────[VT-1]────

Result: 3 carrier threads can handle millions of virtual threads
        because virtual threads don't hold carriers during I/O.
```

## 3. Internal Architecture

### How Virtual Threads Work

```java
// Traditional: Platform thread blocks during I/O
Thread platformThread = new Thread(() -> {
    String result = restTemplate.getForObject("http://slow-service/api", String.class);
    // Thread is BLOCKED in socketRead during the HTTP call
    // OS thread is wasted (can't run anything else)
});

// Virtual Thread: Thread yields carrier during I/O
Thread virtualThread = Thread.ofVirtual().start(() -> {
    String result = restTemplate.getForObject("http://slow-service/api", String.class);
    // When socketRead blocks:
    //  1. JVM detects blocking I/O call
    //  2. Virtual thread is UNMOUNTED from carrier
    //  3. Carrier thread is free to run other virtual threads
    //  4. I/O completes → JVM schedules virtual thread → MOUNTED again
});
```

### What Qualifies as "Blocking" for Virtual Threads

The JVM automatically detects and handles blocking on:
- `java.net.Socket` (socketRead, socketWrite, accept)
- `java.nio.channels.SocketChannel` (when in blocking mode)
- `java.io` (FileInputStream, FileOutputStream)
- `java.util.concurrent` (LockSupport.park(), Thread.sleep(), Future.get())
- `Object.wait()`
- JDBC drivers (when they use `java.net.Socket`)

**Critical**: JDBC drivers must be Virtual-Thread-aware. Old JDBC drivers that use `synchronized` may pin the carrier thread (preventing unmounting). Modern JDBC drivers (PostgreSQL 42.5+, MySQL Connector/J 8.0.30+) are compatible.

### Pinning: The Silent Killer

```java
// Pinning: Virtual thread CANNOT be unmounted
synchronized(lock) {  // ← synchronized blocks pin the carrier thread
    socket.read();     // ← Blocks, but virtual thread stays on carrier!
}

// Solution: Use ReentrantLock instead of synchronized
lock.lock();           // ← ReentrantLock allows unmounting
try {
    socket.read();     // ← Blocks, virtual thread CAN be unmounted
} finally {
    lock.unlock();
}
```

**Diagnosing pinning**:
```bash
# JVM flags to detect pinning
-Djdk.tracePinnedThreads=full
# Logs every time a virtual thread is pinned (synchronized + I/O)
```

## 4. Runtime Behavior

### Virtual Thread Scheduler

```
┌──────────────────────────────────────────────────┐
│            ForkJoinPool (Scheduler)                │
│                                                    │
│  Carrier Thread 1  ──●──   ──●──                   │
│  Carrier Thread 2  ──●── ●──   ──                  │
│  Carrier Thread 3  ●──   ──●── ●──                 │
│                                                    │
│  A ● means a virtual thread is mounted, executing  │
│  Blank means idle (between tasks)                  │
└──────────────────────────────────────────────────┘
         │                              ▲
         │ Mount                        │ Unmount
         ▼                              │
┌──────────────────────────────────────────────────┐
│              Virtual Thread Queue                  │
│  [VT-1] [VT-2] [VT-3] ... [VT-1,000,000]         │
│  Runnables waiting for a carrier thread            │
└──────────────────────────────────────────────────┘
```

### Request Handling with Virtual Threads

```java
// Spring Boot 3.2+ with virtual threads enabled:
@Bean
public TomcatProtocolHandlerCustomizer<?> protocolHandlerCustomizer() {
    return protocolHandler -> protocolHandler.setExecutor(
        Executors.newVirtualThreadPerTaskExecutor()
    );
}

// Now every HTTP request runs on its own virtual thread:
// Request 1 → VirtualThread-1 → process → unmount on DB wait → remount → respond
// Request 2 → VirtualThread-2 → process → unmount on API wait → remount → respond
// ...
// Request 10000 → VirtualThread-10000 → ...
// All work! No thread pool exhaustion. No accept queue overflow.
```

### Connection Pool Behavior Changes

With platform threads: `pool_size = threads × (1 - cpu_time_ratio)`
With virtual threads: `pool_size = max_concurrent_actual_DB_work`

You still need connection pools because database connections are finite resources. The difference: with virtual threads, you don't have threads sitting idle holding connections. Each connection is used as needed, then returned.

## 5. Request Flow Diagrams

### Platform Threads vs Virtual Threads Under Load

```
Platform Threads (200 max, 10 DB connections):
────────────────────────────────────────────────

1000 requests arrive:
  ├── 200 grab threads ← pool full
  │   ├── 10 grab DB connections ← pool full
  │   │   └── Execute queries
  │   └── 190 WAITING for DB connections
  ├── 100 queue in accept-count
  └── 700 rejected / timeout


Virtual Threads (unlimited, 10 DB connections):
──────────────────────────────────────────────

1000 requests arrive:
  ├── 1000 virtual threads created ← no limit!
  │   ├── 500 blocked on socketRead (DB wait)
  │   │   └── UNMOUNTED from carriers
  │   ├── 10 executing queries on 10 connections
  │   └── 490 in queue for DB connections
  │       └── UNMOUNTED from carriers
  │
  └── All 1000 are "in progress"
      Carrier threads: only ~4-8 (CPU cores)
      Memory: 1000 × 200 bytes = 200KB (vs 1000MB for platform threads)
```

## 6. Lifecycle Diagrams

### Migration Path to Virtual Threads

```
Phase 1: Audit for pinning
  java -Djdk.tracePinnedThreads=full -jar app.jar
  → Find all sychronized blocks that contain I/O
  → Replace with ReentrantLock
  → Replace ThreadLocal with ScopedValue

Phase 2: Enable virtual threads on Tomcat
  spring.threads.virtual.enabled=true  (Spring Boot 3.2+)
  
Phase 3: Configure connection pools
  HikariCP: increase maximumPoolSize (virtual threads may use more)
  Database: increase max_connections (if needed)

Phase 4: Remove @Async thread pool configurations
  No longer need dedicated async pools → each @Async gets its own VT

Phase 5: Remove reactive code (if any)
  Replace WebFlux with WebMvc + Virtual Threads
  Replace R2DBC with JDBC + Virtual Threads
```

### When to NOT Use Virtual Threads

```
❌ CPU-bound workloads: Virtual threads don't help.
   - Image/video processing
   - Cryptographic operations
   - Machine learning inference
   - Complex calculations (no I/O)

❌ Frequent synchronized blocks: Pinning negates benefits.
   - Legacy code with pervasive synchronized usage
   - Libraries that haven't been updated

❌ Native code / JNI calls: Virtual threads can't be unmounted during native calls.

❌ ThreadLocal-heavy code: Virtual threads are short-lived → ThreadLocal cleanup not guaranteed.
   - Use ScopedValue instead.

❌ Extremely latency-sensitive: The scheduler adds a tiny overhead.
   - Most apps: negligible. Real-time trading: maybe not.
```

## 7. Source Code Reading Guide

1. **`java.lang.VirtualThread`** (JDK 21+):
   - `run(Runnable)`: Mount/unmount logic
   - `unmount()`: Detach from carrier
   - `mount()`: Attach to carrier

2. **`java.lang.Continuation`**: The primitive that powers virtual threads
   - `yield()`: Save execution state, return
   - `run()`: Resume from saved state

3. **`jdk.internal.vm.ContinuationScope`**: Internal VM support
   - Stack frames are stored on heap (not stack) for virtual threads
   - "StackChunk" objects represent the virtual thread's stack

4. **Spring Boot Virtual Thread support**:
   - `org.springframework.boot.autoconfigure.thread.Threading`
   - `org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration`

## 8. Production Failure Scenarios

### Scenario 1: Carrier Thread Pinning

**Symptom**: Virtual threads enabled but throughput didn't improve. P99 latency still high.

**Root cause**: Code uses `synchronized` blocks around I/O operations. Virtual threads are pinned to carriers and cannot be unmounted during I/O.

**Diagnosis**:
```bash
java -Djdk.tracePinnedThreads=short -jar app.jar
# Look for: "VirtualThread[#123]/runnable@ForkJoinPool-1-worker-1 pinned"
```

**Resolution**: Replace `synchronized(lock)` with `ReentrantLock.lock()/unlock()`. Update libraries (HikariCP, Jedis, etc.) to versions that are VT-friendly.

### Scenario 2: ThreadLocal Memory Leak

**Symptom**: Memory usage grows continuously under load with virtual threads.

**Root cause**: `ThreadLocal` values are not cleaned up when virtual threads die. With 1M virtual threads created per minute, each leaving a ThreadLocal entry, memory leaks quickly.

**Resolution**: 
- Use `ScopedValue` (JEP 446) instead of `ThreadLocal`
- Always `remove()` from ThreadLocal in finally blocks
- Avoid ThreadLocal caching in libraries that may be used from virtual threads

### Scenario 3: Connection Pool Saturation

**Symptom**: Virtual threads enabled, but throughput limited by `HikariCP connection timeout`.

**Root cause**: With unlimited virtual threads, more requests reach the connection pool simultaneously. The pool becomes the bottleneck.

**Resolution**: Increase `maximumPoolSize`. Monitor database `max_connections`. Add a semaphore (or bounded elastic pattern) to limit concurrent DB operations while allowing unlimited threads for non-DB work.

## 9. Debugging Techniques

```bash
# Take a thread dump with virtual threads
jcmd <pid> Thread.dump_to_file -format=json threaddump.json

# Virtual threads are listed separately in newer JDK versions
# Look for "VirtualThread" entries

# Monitor virtual thread count
jstat -virtualthread <pid>

# JFR (Java Flight Recorder) for virtual thread events
-XX:StartFlightRecording:settings=profile
# Events: jdk.VirtualThreadStart, jdk.VirtualThreadEnd, jdk.VirtualThreadPinned
```

## 10. Observability Considerations

Virtual threads change what you monitor:

| Old Metric (Platform Threads) | New Metric (Virtual Threads) |
|------------------------------|------------------------------|
| tomcat.threads.busy | tomcat.threads.busy (carrier usage) |
| tomcat.threads.config.max | Not meaningful (unlimited virtual) |
| Thread pool queue depth | Connection pool pending |
| Thread count | Virtual thread count |
| Thread creation rate | Virtual thread creation rate |

Key new metrics:
- Virtual threads created per second
- Carrier thread utilization (%)
- Pinned thread count (cumulative)
- Platform thread count (carriers, should be ≈ CPU cores)

## 11. Performance Implications

| Workload | Platform Threads (200) | Virtual Threads (unlimited) | Improvement |
|----------|----------------------|---------------------------|-------------|
| I/O-bound, 1000 concurrency | 200 req/s (700 timeout) | 1000 req/s | 5x throughput |
| I/O-bound, 100 concurrency | 100 req/s | 100 req/s | 0% (no contention) |
| CPU-bound, 16 cores | 16 tasks/s | 16 tasks/s | 0% (CPU-limited) |
| Mixed I/O + CPU | Variable | Slightly better | 5-20% |

**The throughput improvement is NOT from faster execution. It's from eliminating thread pool bottlenecks.**

## 12. Architecture Implications

### Virtual Threads vs Reactive Programming

```
Before Virtual Threads:
  I/O-bound + high concurrency → WE MUST use reactive (WebFlux, R2DBC)
  Why? Platform threads are too expensive for 10K concurrent requests.

After Virtual Threads:
  I/O-bound + high concurrency → Use WebMvc + JDBC + Virtual Threads
  Why? Virtual threads are cheap. No need for reactive complexity.

Decision framework:
  ┌─────────────────────────────────────────────────────┐
  │ Is your workload I/O-bound?                           │
  │  YES → Virtual Threads (WebMvc + JDBC) ← SIMPLER!    │
  │  NO  → Is it CPU-bound?                               │
  │         YES → Platform Thread pool (fixed, = cores)   │
  │         └── Or reactive if you already know it        │
  └─────────────────────────────────────────────────────┘
```

Virtual Threads make reactive programming unnecessary for I/O-bound applications. The complexity of reactive programming (backpressure, operators, debugging) was the price for high concurrency on the JVM. Virtual Threads eliminate that trade-off.

## 13. Team Ownership Implications

Adopting Virtual Threads reduces operational burden:
- No thread pool tuning → platform team provides defaults
- No `@Async` pool configuration → service teams don't need to think about it
- Easier code reviews → synchronous code is easier to read than reactive
- Onboarding faster → new engineers don't need to learn reactive patterns

## 14. Interview Questions

1. **"Do virtual threads make reactive programming obsolete?"**
   - **Answer**: For I/O-bound workloads, yes. Virtual threads provide the same scalability benefits with simpler code. Reactive programming remains useful for: (a) CPU-bound streaming with backpressure, (b) systems where event-driven semantics are natural (UI frameworks, IoT), (c) existing reactive codebases that don't justify migration. But for new Spring Boot applications that are I/O-bound, WebMvc + Virtual Threads is the recommended approach.

2. **"What is the biggest risk when adopting virtual threads?"**
   - **Answer**: Pinning caused by `synchronized` blocks in I/O paths. This silently degrades performance to worse-than-platform-threads levels. Requires auditing your codebase and dependencies. Also: ThreadLocal misuse can cause memory leaks at scale.

3. **"How do virtual threads interact with ThreadLocal?"**
   - **Answer**: Poorly. Virtual threads are short-lived and numerous. ThreadLocal values may not be cleaned up, causing memory leaks. `ScopedValue` (JEP 446) is the replacement — it's structured (guaranteed cleanup), immutable, and inheritable. Migration: `ThreadLocal<String> traceId = new ThreadLocal<>()` → `ScopedValue<String> TRACE_ID = ScopedValue.newInstance()`.

## 15. Hands-On Exercises

1. **Benchmark virtual vs platform threads**: Create an endpoint that calls a slow external API (simulated with Thread.sleep). Compare throughput at 100, 500, 1000, 5000 concurrency with and without virtual threads.

2. **Detect pinning**: Enable `-Djdk.tracePinnedThreads=full`. Write code with `synchronized` around `Thread.sleep()`. Observe pinning warnings. Replace with `ReentrantLock`. Observe pinning warnings disappear.

3. **Migrate ThreadLocal to ScopedValue**: Take code that uses `ThreadLocal` for request context. Migrate to `ScopedValue`. Verify context propagation works.

## 16. Advanced Challenges

1. **Implement a bounded elastic pattern**: Use a Semaphore to limit concurrent database operations to 50 while allowing unlimited virtual threads for request handling. Compare throughput against unbounded (where DB becomes bottleneck) and platform-thread-pool (where threads become bottleneck).

2. **Build a virtual thread scheduler dashboard**: Create a custom `ForkJoinPool` that reports carrier utilization, queue depth, and pinning events to Micrometer. Visualize in Grafana.

3. **Port a reactive application to virtual threads**: Take a WebFlux + R2DBC application and rewrite it to WebMvc + JDBC + Virtual Threads. Compare: lines of code, test complexity, throughput, memory usage.
