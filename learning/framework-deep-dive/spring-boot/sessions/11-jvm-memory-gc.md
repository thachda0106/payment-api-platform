# Session 11: JVM Memory Model & Garbage Collection

## 1. Why This Topic Exists

Spring Boot applications die from: (1) thread pool exhaustion and (2) memory problems. Understanding JVM memory means understanding why your application OOMs at 3 AM, why GC pauses spike to 500ms, and why your heap is 4GB but RSS is 8GB.

**Staff engineer insight**: You cannot tune what you cannot measure. Heap dumps and GC logs are not optional — they are the primary debugging tools for any production JVM application. If you cannot read a heap dump, you cannot diagnose memory leaks on-call.

## 2. Mental Model

```
JVM MEMORY LAYOUT

┌──────────────────────────────────────────────────────┐
│                  JVM PROCESS MEMORY                   │
│                                                      │
│ ┌──────────────────────────────────────────────────┐ │
│ │                    HEAP                           │ │
│ │  -Xms / -Xmx: Heap size (e.g., -Xmx2g)          │ │
│ │                                                  │ │
│ │  ┌──────────────────┐  ┌──────────────────────┐ │ │
│ │  │   Young Gen       │  │    Old Gen (Tenured) │ │ │
│ │  │                   │  │                      │ │ │
│ │  │  ┌──────┐ ┌─────┐ │  │  Long-lived objects │ │ │
│ │  │  │ Eden │ │ S0  │ │  │  Big objects that   │ │ │
│ │  │  │      │ │ S1  │ │  │  survived many GCs  │ │ │
│ │  │  └──────┘ └─────┘ │  │                      │ │ │
│ │  └──────────────────┘  └──────────────────────┘ │ │
│ └──────────────────────────────────────────────────┘ │
│                                                      │
│ ┌──────────────────────────────────────────────────┐ │
│ │                 OFF-HEAP                          │ │
│ │  ┌────────────┐  ┌─────────────┐  ┌───────────┐ │ │
│ │  │ Metaspace  │  │ Direct      │  │ Thread    │ │ │
│ │  │ (classes,  │  │ Buffers     │  │ Stacks    │ │ │
│ │  │  metadata) │  │ (NIO,       │  │ (-Xss)    │ │ │
│ │  │            │  │  Netty)     │  │           │ │ │
│ │  └────────────┘  └─────────────┘  └───────────┘ │ │
│ │                                                  │ │
│ │  ┌────────────┐  ┌─────────────┐                │ │
│ │  │ Code Cache │  │ JVM         │                │ │
│ │  │ (JIT code) │  │ Internal    │                │ │
│ │  └────────────┘  └─────────────┘                │ │
│ └──────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘

RSS (Resident Set Size) = Heap + Off-heap
If RSS = 8GB and -Xmx = 4GB, the "missing" 4GB is off-heap.
Common culprits: Metaspace, DirectByteBuffers, Thread stacks, native libraries.
```

## 3. Internal Architecture

### Heap Generations (Classic GC Layout)

```
Object Lifecycle:

┌────────────────────┐
│      ALLOCATION    │
│   new Object()     │──────────────┐
└────────────────────┘              │
                                    ▼
                            ┌──────────────┐
                            │     EDEN      │ ← Most objects allocated here
                            │  (Young Gen)  │    Small, short-lived objects
                            └──────┬───────┘
                                   │ Minor GC:
                                   │ Dead objects: freed (cheap)
                                   │ Live objects: copied to Survivor
                                   ▼
                       ┌──────────────────────┐
                       │    SURVIVOR (S0/S1)  │ ← Objects that survived 1+ GC
                       │    (Young Gen)       │    Age counter increments
                       └──────────┬───────────┘
                                  │ After surviving N GCs
                                  │ (default: -XX:MaxTenuringThreshold=15)
                                  ▼
                       ┌──────────────────────┐
                       │   OLD GEN (TENURED)  │ ← Long-lived objects
                       │                      │    Database pools, caches,
                       │                      │    singletons, application state
                       └──────────┬───────────┘
                                  │ Major/Full GC:
                                  │ Expensive! Stop-the-world.
                                  ▼
                          FREED or moved
```

### Garbage Collector Comparison

| Collector | Young GC | Old GC | Pause | Throughput | Best For |
|-----------|----------|--------|-------|------------|----------|
| Serial | Serial | Serial | Long (STW) | Low | Small apps (<100MB) |
| Parallel | Parallel | Parallel | Medium (STW) | High | Batch, throughput > latency |
| G1 (default) | Concurrent | Concurrent | Short | Medium | Balanced, <4GB heaps |
| Shenandoah | Concurrent | Concurrent | Very Short | Medium | Low-pause, large heaps |
| ZGC | Concurrent | Concurrent | <1ms | Medium | Very low pause, 100GB+ |

### G1GC (Default since Java 9) Internals

```
G1 divides the heap into equal-sized regions:

┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ E │ E │ E │ S │ S │ O │ O │ O │ H │ F │
│   │   │   │   │   │   │   │   │   │   │
│ Eden│Surv│   Old (Tenured)  │Huge│Free│
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘

E = Eden region
S = Survivor region
O = Old region
H = Humongous region (objects >50% of region size)
F = Free region

G1 GC Cycle:
  1. Young-only phase: Collect Eden + Survivor, promote survivors
  2. Concurrent marking: Mark live objects in Old gen (while app runs)
  3. Remark: Stop-the-world, finalize marking
  4. Cleanup: Reclaim empty regions
  5. Mixed collections: Collect Young + some Old regions

Key tuning:
  -XX:MaxGCPauseMillis=200    ← Target pause time
  -XX:G1HeapRegionSize=4m     ← Region size (power of 2, 1MB-32MB)
  -XX:InitiatingHeapOccupancyPercent=45  ← When to start concurrent cycle
```

### Spring Boot Memory Consumption Pattern

```
Typical memory breakdown for a medium Spring Boot app:

Heap (4GB):
  ├── Spring beans: 50-100MB (ApplicationContext)
  ├── HikariCP connection pool: 2-10MB
  ├── JPA/Hibernate metadata: 100-200MB
  ├── Request/Response objects: 200-500MB (peak)
  ├── Caches (Caffeine/Redis): 500MB-2GB (varies)
  ├── Business objects: Variable
  └── GC overhead: 10-20%

Off-Heap (2-4GB):
  ├── Metaspace: 100-300MB (classes, method metadata)
  ├── Thread stacks: 200 × 1MB = 200MB (platform threads)
  ├── DirectByteBuffer: Variable (Netty, NIO, file I/O)
  ├── JIT code cache: 50-100MB
  ├── JVM internals: 100-200MB
  └── Native libraries: Variable
```

## 4. Runtime Behavior

### The Allocation Path

```java
// When you write:
User user = new User("Alice", "alice@example.com");

// The JVM:
// 1. Thread Local Allocation Buffer (TLAB) check:
//    - Each thread has a private allocation area in Eden
//    - Allocation is just a pointer bump (no lock, extremely fast)
//    - If TLAB has space → done in ~10 CPU instructions
//
// 2. If TLAB full → allocate new TLAB from Eden (still fast)
//
// 3. If Eden full → trigger Young GC (Minor GC)
//    - Stop-the-world (but usually <50ms)
//    - Copy live objects to Survivor/Old
//    - Free Eden
```

### What Makes Objects Survive

```java
// This object dies young (in Eden, never promoted):
@GetMapping("/users/{id}")
public UserDto getUser(@PathVariable Long id) {
    User user = userRepository.findById(id);       // Allocated in Eden
    return UserDto.from(user);                      // Allocated in Eden
}                                                  // Both eligible for GC after method returns

// This object survives:
@RestController
public class UserController {
    private final UserService userService;    // Singleton → Old Gen immediately
    // (Injected at startup, lives forever)
}

// This object is problematic:
@Cacheable("users")
public User getUser(Long id) {
    // The User goes to cache (Caffeine) → Old Gen
    // If 1M users cached → 500MB+ in Old Gen → Old Gen grows → Full GC more frequent
}
```

### GC Logs and What They Mean

```
[2024-01-15T14:30:00.123+0000][info][gc] GC(42) Pause Young (Normal) (G1 Evacuation Pause) 350M->220M(2048M) 12.3ms

Pause Young: Type of GC (Minor GC)
350M->220M: Heap before GC → after GC (350MB → 220MB)
2048M: Total heap size
12.3ms: Pause duration

[2024-01-15T14:35:00.456+0000][info][gc] GC(45) Pause Full (G1 Compaction Pause) 1800M->1400M(2048M) 452.1ms

Full GC: Major collection (Old Gen + Young Gen)
452.1ms: This is BAD for latency-sensitive apps
```

## 5. Request Flow Diagrams

### Memory During a Request

```
Request Starts:                                 Request Ends:
                                               
EDEN: [==========................]             EDEN: [===================.......]
       ↑ 35% used                                    ↑ 70% used
       (allocations from prior requests)             (this request's objects)

A single request allocates (example e-commerce):
  - Request object: ~200 bytes
  - CreateOrderDTO: ~500 bytes
  - Order (domain): ~2KB
  - OrderItem × 5: ~5KB
  - OrderResponse DTO: ~1KB
  - Hibernate PersistentContext: ~10KB (dirty checking, snapshots)
  - JDBC ResultSet buffer: ~20KB (depends on query size)
  - Temporary strings, byte[]: ~5KB
  Total: ~50KB per request (rough estimate, domain-dependent)

At 1000 RPS: 50MB/s allocation
With 4GB heap, 1GB young gen:
  Young GC every ~20 seconds (1000MB / 50MB/s)
  Each young GC: ~10-30ms pause
```

## 6. Lifecycle Diagrams

### Object Lifecycle

```
Birth: new Object()
  → Allocated in Eden (TLAB)
  → Reference in stack or another object

Young: Survives 1 Minor GC
  → Copied to Survivor S0
  → Age = 1

Aging: Survives N Minor GCs
  → Copied between S0 ↔ S1 (alternating)
  → Age increments

Promotion: Age ≥ MaxTenuringThreshold (15)
  → Copied to Old Gen

Death: No references remain
  → Young Gen: Freed during Minor GC (free, just don't copy)
  → Old Gen: Freed during Major/Mixed/Full GC (expensive)

Reanimation (resurrection): finalize() called
  → Object can make itself reachable again
  → deprecated since Java 9, finalization is unreliable
```

### Application Lifecycle (Memory View)

```
Startup:
  -Xms: Reserve initial heap (e.g., 512MB)
  -Xmx: Reserve maximum heap (e.g., 4GB)
  
  Spring loads:
  → Classes loaded → Metaspace grows (~100-200MB)
  → Beans instantiated → Old Gen (~50-100MB)
  → Connection pools → Old Gen (~10MB)
  → JIT compilation → Code cache grows (~50-100MB)

Warm-up (first 10K requests):
  → Eden fills, Minor GCs start
  → JIT compiles hot methods → Code cache grows
  → Survivor spaces fill, objects promote to Old Gen
  → Metaspace stabilizes

Steady state:
  → Regular Minor GCs (every N seconds)
  → Occasional Mixed GCs (G1)
  → Rare Full GCs (should be near zero)

Memory leak:
  → Old Gen grows continuously
  → Each GC recovers less memory
  → Full GCs become more frequent and longer
  → Eventually: OOM (OutOfMemoryError: Java heap space)
```

## 7. Source Code Reading Guide

1. **OpenJDK HotSpot GC source** (for the brave):
   - `src/hotspot/share/gc/g1/`: G1GC implementation
   - `src/hotspot/share/gc/shared/`: Shared GC infrastructure
   
2. **Spring Boot Memory Auto-configuration**:
   - Check `application.properties` for memory-related defaults
   
3. **JVM Heap dump format** (HPROF): Learn what a heap dump contains

## 8. Production Failure Scenarios

### Scenario 1: Gradual Memory Leak (OOM)

**Symptom**: Application runs fine for hours/days, then OOM kills it. Restart fixes temporarily.

**Root cause**: Objects accumulate in Old Gen. Each GC frees less memory. Eventually Old Gen is 100% full.

**Diagnosis**:
```bash
# 1. Get heap dump before OOM
jmap -dump:live,format=b,file=heap.hprof <pid>

# 2. Or auto-dump on OOM
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/path/to/dumps

# 3. Analyze with Eclipse MAT or IntelliJ Profiler
# Look for: "Problem Suspects" → Leak Suspects
# Most common: Growing collections (HashMap, ArrayList, ConcurrentHashMap)
```

**Common Spring Boot Memory Leak Causes**:
1. `@Cacheable` without eviction policy → cache grows forever
2. `ThreadLocal` not cleaned up → values accumulate
3. `@EventListener` without cleanup → listener references hold objects
4. `BeanPostProcessor` registering without deregistration
5. `MetricRegistry` with unbounded tags → metrics cardinality explosion
6. Open `InputStream`/`OutputStream` not closed → native memory leak

### Scenario 2: Metaspace OOM

**Symptom**: `OutOfMemoryError: Metaspace` after running for weeks.

**Root cause**: Classloader leak. Classes are loaded but never unloaded. Common in dynamic proxy generation, Groovy scripts, or redeploy-heavy environments.

**Diagnosis**:
```bash
-XX:MaxMetaspaceSize=256m  # Too small for heavy frameworks
# Check: jstat -gc <pid> shows "MU" (Metaspace Used) growing continuously
```

**Resolution**: Increase `-XX:MaxMetaspaceSize`. Fix classloader leak (typically in `-devtools` or Groovy scripting).

### Scenario 3: Direct Buffer Memory Leak

**Symptom**: OOM but heap dump shows heap is fine. `OutOfMemoryError: Direct buffer memory`.

**Root cause**: Netty, NIO, or file I/O allocating `DirectByteBuffer` without releasing. RSS grows but heap is normal.

**Diagnosis**:
```bash
# Monitor direct buffer usage
jcmd <pid> VM.native_memory summary
# Look at "Direct Buffer" section
```

## 9. Debugging Techniques

### Heap Dump Analysis with Eclipse MAT

```
1. Load heap dump
2. Look at Dominator Tree: "What's holding all the memory?"
3. Check Leak Suspects report
4. Path to GC Roots: "Why isn't this object being collected?"
5. Compare two heap dumps: "What grew between dump1 and dump2?"

Common patterns:
  - HashMap with 100K entries → cache without eviction
  - char[] and String dominating → lots of text data
  - byte[] dominating → binary data (file uploads, images)
  - ArrayList with 1M entries → unbounded in-memory collection
```

### GC Log Analysis

```bash
# Enable GC logging (Java 21)
-Xlog:gc*:file=gc.log:time,level,tags:filecount=10,filesize=100m

# Analyze with GCViewer, GCeasy, or manual parsing
# Look for:
#   - GC pause frequency (should be stable)
#   - GC pause duration (should be stable, under target)
#   - Memory freed per GC (if declining → leak)
#   - Full GC count (should be near zero)
#   - Promotion rate (objects moved to Old Gen per second)
```

### Native Memory Tracking

```bash
# Enable NMT
-XX:NativeMemoryTracking=summary

# Check memory
jcmd <pid> VM.native_memory summary

# Shows breakdown:
#   - Java Heap
#   - Class (Metaspace)
#   - Thread (stacks)
#   - Code (JIT)
#   - GC (GC data structures)
#   - Internal
#   - Symbol
#   - Native Memory Tracking
#   - Arena Chunk
```

## 10. Observability Considerations

| Metric | What It Tells You |
|--------|------------------|
| `jvm.memory.used` | Heap used (Eden+Survivor+Old) |
| `jvm.memory.committed` | Heap committed (from OS) |
| `jvm.memory.max` | Max heap (-Xmx) |
| `jvm.memory.used` (area=nonheap) | Metaspace + Code Cache |
| `jvm.gc.pause` | GC pause duration and frequency |
| `jvm.gc.memory.allocated` | Allocation rate |
| `jvm.gc.memory.promoted` | Promotion rate to Old Gen |
| `jvm.gc.live.data.size` | Live data after GC (good leak indicator) |

**Alert thresholds**:
- `jvm.memory.used` / `jvm.memory.max` > 85% for 5+ minutes → investigate
- `jvm.gc.pause` P99 > 100ms for 5+ minutes → GC tuning needed
- `jvm.gc.pause` count of Full GC > 0 → investigate

## 11. Performance Implications

| GC Flag | Effect |
|---------|--------|
| `-Xms2g -Xmx2g` (same) | Avoid heap resizing overhead |
| `-XX:+UseG1GC` | Default, balanced |
| `-XX:MaxGCPauseMillis=100` | Target 100ms pauses |
| `-XX:ConcGCThreads=2` | Limit concurrent GC CPU usage |
| `-XX:+UseZGC` | <1ms pauses for large heaps |

**Containers**: Always set `-Xmx` explicitly. JVM's adaptive sizing uses the container's visible memory (cgroups v1 uses `/proc/meminfo` which shows host memory, not container limit).

```bash
# For containers:
-XX:MaxRAMPercentage=75.0  # Use 75% of container memory
# OR
-XX:MaxRAM=1536m  # (older JVMs)
```

## 12. Architecture Implications

Memory considerations affect architecture decisions:

| Decision | Memory Impact |
|----------|--------------|
| In-memory cache (Caffeine) vs Redis | More heap usage vs network calls |
| Full-text search in DB vs Elasticsearch | Less infra but more DB memory |
| Batch processing size | Larger batches = more heap per request |
| Session replication | Each session replicates heap across nodes |
| Sticky sessions | Simpler but unbalanced memory distribution |

## 13. Team Ownership Implications

Memory tuning is typically owned by the platform/SRE team:
- Set JVM flags as platform defaults
- Provide GC dashboards
- Provide heap dump analysis tooling
- Service teams own their allocation patterns

## 14. Interview Questions

1. **"Your application has a 4GB heap. `jmap -histo` shows 2GB of `byte[]`. Why?"**
   - **Answer**: Likely file uploads, image processing, or large API responses held in memory. Check for: unbuffered InputStream reads, large byte[] cached without reason, Netty buffer leaks, or serialization of large objects to byte[]. The specific fix depends on which component is holding the byte[] — identify via heap dump dominator analysis.

2. **"What's the difference between Minor GC, Major GC, and Full GC?"**
   - **Answer**: Minor GC = collects Young Gen only (fast, frequent). Major GC = collects Old Gen only (slower, less frequent). Full GC = collects both + Metaspace (slowest, stop-the-world). In G1GC, there are no true "Major GC" — it uses "Mixed GC" which collects Young + some Old regions concurrently.

3. **"How do you diagnose a memory leak in production?"**
   - **Answer**: (1) Check GC logs: is memory freed per GC declining? (2) Check `jvm.memory.used` after GC trending up. (3) Take heap dump: `jmap -dump:live,format=b,file=heap.hprof <pid>`. (4) Analyze: dominator tree showing largest objects + path to GC roots. (5) Take second dump 1 hour later, compare. (6) Fix: identify which component holds references, add cleanup/eviction.

## 15. Hands-On Exercises

1. **Create and diagnose a memory leak**: Write code that adds to a static `List<byte[]>` every request without cleanup. Deploy, load test, observe GC and heap growth. Take heap dump. Find the leak in Eclipse MAT.

2. **Tune GC for low latency**: Configure G1GC with `MaxGCPauseMillis=50`. Run a load test. Observe GC pauses. Tune until pauses are within target.

3. **Compare GC collectors**: Run the same workload with Serial, Parallel, G1, and ZGC. Compare: throughput, P99 pause, P99.9 pause.

## 16. Advanced Challenges

1. **Implement a memory budget**: Design a system where each tenant/sub-request has a memory budget. When exceeded, the request is rejected with 429 Too Many Requests before it causes OOM.

2. **Build a heap dump analyzer**: Write a tool that reads HPROF format and identifies: top 10 classes by retained memory, objects with suspiciously deep graphs, collections with >10K elements.

3. **Design a zero-GC-pause system**: Use off-heap memory (DirectByteBuffer, Chronicle, or native memory) for latency-critical paths. Ensure no GC pauses affect the critical path.
