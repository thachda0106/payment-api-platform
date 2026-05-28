# Module 03 — JVM Internals

## 3.1 JVM Architecture

```
┌────────────────────────────────────────────────────────────┐
│                     JVM ARCHITECTURE                        │
├────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────┐  │
│  │              CLASS LOADER SUBSYSTEM                   │  │
│  │  Bootstrap → Platform (Extension) → Application      │  │
│  │  Loading → Linking (verify→prepare→resolve) → Init   │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         ▼                                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │                 RUNTIME DATA AREAS                     │  │
│  │                                                        │  │
│  │  ┌──────────┐  ┌──────────┐  ┌────────────────────┐  │  │
│  │  │  Method   │  │   Heap   │  │  Java Threads      │  │  │
│  │  │   Area    │  │          │  │  ┌──────┐┌──────┐  │  │  │
│  │  │ (Class    │  │ Young Gen│  │  │ PC   ││Stack │  │  │  │
│  │  │ metadata, │  │ Old Gen  │  │  │Register│     │  │  │  │
│  │  │ constants,│  │ Metaspace│  │  └──────┘└──────┘  │  │  │
│  │  │ static    │  │ (off-heap│  │  ┌──────────────┐  │  │  │
│  │  │ fields)   │  │  since 8)│  │  │Native Method │  │  │  │
│  │  └──────────┘  └──────────┘  │  │    Stack     │  │  │  │
│  │                              │  └──────────────┘  │  │  │
│  └──────────────────────────────┴────────────────────┘  │  │
│                                                           │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              EXECUTION ENGINE                         │  │
│  │  Interpreter → JIT Compiler (C1/C2) → GC             │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────┘
```

### Class Loading

Classes are loaded ON DEMAND (lazy), not at startup.

**Bootstrap ClassLoader**: Loads core Java classes (`java.lang.*`, `java.util.*`). Written in native code. No parent. Part of the JVM.

**Platform ClassLoader** (formerly Extension): Loads classes from `java.ext.dirs` and `jrt-fs.jar`. Child of Bootstrap.

**Application ClassLoader**: Loads classes from classpath (`-cp` or `-classpath`). Child of Platform.

**Delegation model**: Child delegates to parent FIRST. If parent can't load, child tries. This prevents duplicate loading and ensures core classes can't be replaced by application code (security).

```java
// See which ClassLoader loaded a class
ClassLoader cl = Payment.class.getClassLoader();
System.out.println(cl);  // jdk.internal.loader.ClassLoaders$AppClassLoader
System.out.println(cl.getParent());  // PlatformClassLoader
System.out.println(cl.getParent().getParent());  // null (Bootstrap — native code)
```

### Linking Steps

1. **Verify**: Bytecode is valid, no stack overflow, type safety
2. **Prepare**: Allocate memory for static fields, initialize to defaults
3. **Resolve**: Symbolic references → actual memory references (can be lazy)

---

## 3.2 Just-In-Time (JIT) Compilation

Java bytecode is INTERPRETED initially. Hot methods (frequently called) are COMPILED to native code by the JIT.

### Tiered Compilation (default since Java 8)

```
Level 0: Interpreted
   │ (method called many times)
   ▼
Level 1: C1 compilation with no profiling (simple methods)
   │
   ▼
Level 2: C1 compilation with basic profiling (method invocation count, back-edge count)
   │
   ▼
Level 3: C1 compilation with full profiling (branch probabilities, type profiles)
   │
   ▼
Level 4: C2 compilation (highly optimized native code using profiling data)
```

**C1 (Client Compiler)**: Fast compilation, simple optimizations. Good for: GUI apps, short-lived code.

**C2 (Server Compiler)**: Slow compilation, aggressive optimizations (inlining, escape analysis, loop unrolling, dead code elimination). Good for: long-running server applications.

```bash
# See JIT compilation in action
java -XX:+PrintCompilation MyApp

# Output: 123  1  %  java.lang.String::hashCode @ 15 (56 bytes)
#          │  │  │
#          │  │  └─ OSR (On-Stack Replacement — compiled while method was running)
#          │  └─ Tier (0-4)
#          └─ Compilation ID
```

### Inlining

The most important JIT optimization. The compiler copies the body of a frequently-called method INTO the caller, eliminating method call overhead.

```java
// Before inlining:
int add(int a, int b) { return a + b; }
int calc() { return add(1, 2); }  // Method call: push args, jump, return, pop

// After inlining:
int calc() { return 1 + 2; }  // No method call — just the computation
```

### Escape Analysis

Determines whether an object "escapes" the method (returned, stored in field, passed to another thread). If not, the object can be allocated on the STACK instead of the heap — no GC overhead.

```java
// The Point object never escapes this method → allocated on stack (scalar replacement)
void processPayment() {
    Point p = new Point(10, 20);  // Does p escape? No.
    int result = p.x + p.y;        // Compiler replaces with: int result = 10 + 20;
}
// Use -XX:+PrintEscapeAnalysis -XX:+PrintEliminateAllocations to see
```

---

## 3.3 Garbage Collection

### GC Algorithms Comparison

| GC | Type | Pause | Heap Size | Enable |
|----|------|:-----:|:---------:|--------|
| **Serial** | Single-thread, stop-the-world | 100ms-1s | <512MB | `-XX:+UseSerialGC` |
| **Parallel** | Multi-thread, stop-the-world | 50ms-500ms | Any | `-XX:+UseParallelGC` |
| **G1** | Concurrent + regional | <100ms target | 4-32GB | `-XX:+UseG1GC` (default JDK 9+) |
| **ZGC** | Concurrent, ultra-low pause | <1ms | 16MB-16TB | `-XX:+UseZGC` |
| **Shenandoah** | Concurrent, low pause | <10ms | Any | `-XX:+UseShenandoahGC` |

**Payment platform**: ZGC for Financial Core (sub-ms pauses prevent payment timeouts). G1 for other services.

### G1 GC — How It Works

G1 divides the heap into REGIONS (typically 2048, each ~1-32MB). A region can be: Eden, Survivor, Old, Humongous (object > 50% of region size).

**Young Collection** (STW, fast): Collect Eden + Survivor regions. Copy live objects to new Survivor or Old regions.

**Mixed Collection** (STW + concurrent): Collect Young + some Old regions (chosen for most garbage — "garbage-first"). The concurrent marking phase finds regions with the most garbage.

**Concurrent Marking**:
1. Initial Mark (STW, short): Mark GC roots
2. Root Region Scan (concurrent): Scan Survivor regions
3. Concurrent Mark (concurrent): Walk object graph
4. Remark (STW, short): Catch modifications during concurrent mark
5. Cleanup (STW + concurrent): Reclaim empty regions, prepare for mixed collections

### GC Tuning for Containers

```bash
# Essential JVM flags for containerized payment services
java \
  -XX:+UseZGC \
  -XX:MaxRAMPercentage=75.0 \          # Use container memory limit, NOT host memory!
  -XX:+ExitOnOutOfMemoryError \        # Crash on OOM, let K8s restart
  -Xlog:gc*:file=/logs/gc.log::filecount=5,filesize=10M \  # GC logging
  -jar payment-service.jar
```

### Reading GC Logs

```
[gc] GC(0) Pause Young (Normal) 15M->3M(256M) 2.345ms
[gc] GC(1) Pause Young (Normal) 25M->4M(256M) 1.890ms
[gc] GC(2) Pause Full (Allocation Failure) 200M->180M(512M) 345.678ms
                                                                  ^^^^^^^ BAD!
```
Full GCs in production are a red flag. They mean: heap too small, memory leak, or GC not keeping up with allocation rate.

---

## 3.4 Memory Management & Troubleshooting

### Heap Dump Analysis

```bash
# Generate heap dump on OOM
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps/

# Manual heap dump
jmap -dump:format=b,file=heap.hprof <pid>

# Analyze with:
# 1. jhat (built-in, basic)
# 2. Eclipse MAT (Memory Analyzer Tool) — free, excellent
# 3. VisualVM — built-in, basic

# Start JFR recording
jcmd <pid> JFR.start name=profile duration=60s filename=recording.jfr
```

### Common Memory Leak Patterns

1. **Static collection that never clears**:
```java
private static final Map<String, Payment> cache = new HashMap<>();  // Leak!
// Fix: Use LinkedHashMap with access-order + removeEldestEntry, or Caffeine cache
```

2. **Unclosed resources** (streams, connections):
```java
// BAD:
Connection conn = dataSource.getConnection();
// ... forget to close → connection leak → pool exhaustion

// GOOD:
try (Connection conn = dataSource.getConnection()) {
    // auto-closed
}
```

3. **ThreadLocal without remove()**: In thread pools, ThreadLocal values persist and accumulate.

4. **Inner class holding reference to outer**:
```java
public class PaymentService {
    private byte[] heavyData = new byte[100_000_000];
    
    Runnable createTask() {
        return () -> process(heavyData);  // Lambda captures 'this' → heavyData can't be GC'd
    }
}
```

### Profiling Tools

| Tool | What It Does |
|------|-------------|
| `jstack <pid>` | Thread dump (see what every thread is doing) |
| `jmap -histo <pid>` | Heap histogram (count + size per class) |
| `jstat -gc <pid> 1s` | GC statistics every second |
| JFR (Java Flight Recorder) | Low-overhead profiling: CPU, allocation, lock, I/O |
| async-profiler | CPU + allocation profiling (Linux/macOS) |
| VisualVM | GUI: threads, heap, CPU, GC visualization |

---

## 3.5 Exercises

### Ex 3.1 — GC Experiment
Write a program that creates short-lived and long-lived objects at controlled rates. Run with Serial, Parallel, G1, and ZGC. Compare: pause times, throughput, heap occupancy. Use `-Xlog:gc*` to capture GC logs.

### Ex 3.2 — Memory Leak Creation & Detection
Create each leak pattern: (a) static collection, (b) unclosed connection, (c) ThreadLocal without cleanup. Use jmap + Eclipse MAT to find the leaking objects. Fix each leak.

### Ex 3.3 — JIT Watch
Write a simple loop method. Run with `-XX:+PrintCompilation`. Observe: (a) when it transitions from interpreted → C1 → C2, (b) on-stack replacement (OSR). Add `-XX:+PrintInlining` to see what gets inlined.

### Ex 3.4 — Heap Analysis
Take a heap dump from a running Spring Boot application. Use Eclipse MAT to: (a) find the largest objects, (b) identify duplicate strings, (c) find objects retained by finalizers, (d) calculate retained heap for a class.

---

## 3.6 Self-Assessment

- [ ] Can explain the class loading delegation model (Bootstrap → Platform → Application)
- [ ] Understand tiered compilation (Level 0-4) and what each tier does
- [ ] Can explain when inlining and escape analysis apply
- [ ] Can select the right GC algorithm for a given workload
- [ ] Can read a GC log and identify: minor GC, full GC, pause time, allocation rate
- [ ] Can generate and analyze a heap dump to find memory leaks
- [ ] Know how to use jstack, jmap, jstat, and JFR
