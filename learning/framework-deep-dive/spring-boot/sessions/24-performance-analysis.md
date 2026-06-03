# Session 24: Performance Analysis & Optimization

## 1. Why This Topic Exists

The difference between a Spring Boot application that handles 100 RPS and one that handles 10,000 RPS on identical hardware is not architecture — it's the accumulated effect of hundreds of micro-decisions: the `@Transactional` propagation level, the Jackson `ObjectMapper` configuration, the HikariCP validation timeout, the `String.format()` in a hot path, the number of auto-configuration classes evaluated at startup. Each of these decisions costs fractions of a millisecond. Multiplied by thousands of requests per second, across dozens of services, the aggregate cost determines whether your infrastructure bill is $50,000/month or $500,000/month.

Performance engineering is not premature optimization. It is the discipline of measuring before changing, profiling before guessing, and understanding the cost model of every abstraction layer. The Staff engineer who can produce a flame graph, interpret the JFR recording's hot methods, and tune the JVM appropriately is worth more to the organization than the engineer who adds another `@Cacheable` and hopes.

**Staff engineer insight**: Performance problems in Spring Boot follow Pareto distributions. 80% of the improvement comes from fixing 20% of the issues. The 20% is almost always: (1) database query performance (missing indexes, N+1 queries, large result sets), (2) HTTP call patterns (serial calls that could be parallel, missing connection pooling), (3) serialization overhead (Jackson configuration, large response bodies), and (4) lack of caching. The profiling methodology is: find the bottleneck (not the symptom), measure the improvement, and move to the next bottleneck. Repeat until SLO is met.

The performance engineering lifecycle:
```
1. Define: What throughput/latency does the SLO require? (1000 TPS, p99 < 100ms)
2. Measure: How does the system actually perform? (load test → 200 TPS, p99 = 500ms)
3. Profile: Where is the time going? (flame graph → 60% in DB queries)
4. Optimize: Fix the bottleneck (add index → DB time drops to 5%)
5. Verify: Re-measure (load test → 800 TPS, p99 = 80ms)
6. Repeat: Next bottleneck (now 40% in JSON serialization)
```

## 2. Mental Model

### The Performance Analysis Stack

```
┌──────────────────────────────────────────────────────────────────┐
│                     WHAT YOU'RE MEASURING                          │
│                                                                    │
│  LEVEL 5: BUSINESS METRICS                                        │
│    Orders/second, payments/minute, users served                    │
│    Tool: Grafana business dashboards                               │
│                                                                    │
│  LEVEL 4: ENDPOINT PERFORMANCE                                     │
│    p50/p95/p99 latency per endpoint, error rate                    │
│    Tool: Grafana RED dashboards (Prometheus data)                  │
│                                                                    │
│  LEVEL 3: SYSTEM RESOURCES                                         │
│    CPU%, heap usage, GC pause, thread count, fd count              │
│    Tool: Grafana USE dashboards, OS tools (top, htop, vmstat)      │
│                                                                    │
│  LEVEL 2: JVM INTERNALS                                           │
│    JIT compilation, GC events, lock contention, thread states      │
│    Tool: JFR (Java Flight Recorder), JConsole, jcmd                │
│                                                                    │
│  LEVEL 1: METHOD-LEVEL CPU/MEMORY                                  │
│    Which methods consume CPU? Allocate memory? Block threads?      │
│    Tool: async-profiler, JFR method profiling, perf (Linux)        │
│                                                                    │
│  LEVEL 0: NATIVE                                                │
│    Kernel calls, page faults, context switches, cache misses       │
│    Tool: perf, bpftrace, eBPF                                      │
└──────────────────────────────────────────────────────────────────┘
```

### Performance Testing Methodology

```
┌───────────────────┐  ┌──────────────────┐  ┌───────────────────┐  ┌───────────────┐
│   LOAD TESTING    │  │  STRESS TESTING   │  │   SOAK TESTING    │  │SPIKE TESTING  │
│   (Constant)      │  │  (Increasing)     │  │  (Long duration)  │  │ (Sudden)      │
├───────────────────┤  ├──────────────────┤  ├───────────────────┤  ├───────────────┤
│ Fixed RPS over    │  │ Gradually        │  │ Moderate load     │  │ Sudden jump   │
│ time to verify    │  │ increase load     │  │ over 24-72 hours │  │ from low to   │
│ system can sustain│  │ until system      │  │ to find memory   │  │ peak RPS to   │
│ target throughput │  │ breaks            │  │ leaks, GC issues,│  │ test auto-    │
│                   │  │                   │  │ connection creep  │  │ scaling        │
│ Goal: Verify SLO  │  │ Goal: Find max    │  │ Goal: Detect     │  │ Goal: Verify   │
│ Ask: "Does p99    │  │ Ask: "What is the │  │ Ask: "Does       │  │ Ask: "Does     │
│ stay under 100ms  │  │ breaking point?"   │  │ memory grow?"    │  │ recovery       │
│ at 1000 RPS?"     │  │                    │  │                  │  │ work?"         │
└───────────────────┘  └──────────────────┘  └───────────────────┘  └───────────────┘

TOOL SELECTION:
  ┌──────────┬────────────┬───────────┬──────────────────────────────────────┐
  │ Tool     │ Script Lang│ Best For  │ Notes                                │
  ├──────────┼────────────┼───────────┼──────────────────────────────────────┤
  │ k6       │ JavaScript │ CI/CD     │ Easy scripts, great metrics, cloud   │
  │          │            │ perf test │ integration, developer-friendly       │
  ├──────────┼────────────┼───────────┼──────────────────────────────────────┤
  │ wrk2     │ Lua        │ Latency   │ Coordinated omission free, accurate  │
  │          │ (limited)  │ testing   │ latency at specific throughput        │
  ├──────────┼────────────┼───────────┼──────────────────────────────────────┤
  │ Gatling  │ Scala/Java │ Complex   │ Full DSL, session management,        │
  │          │            │ scenarios │ checkpoints in test flows             │
  ├──────────┼────────────┼───────────┼──────────────────────────────────────┤
  │ JMeter   │ GUI + XML  │ Legacy    │ Rich plugin ecosystem, slow GUI,     │
  │          │            │ setups    │ not ideal for CI/CD                   │
  └──────────┴────────────┴───────────┴──────────────────────────────────────┘
```

## 3. Internal Architecture

### JFR (Java Flight Recorder) Architecture

```java
// JFR is a built-in low-overhead profiling framework in the JVM.
// It records events from JVM internals, GC, JIT, threads, I/O, and custom events.

// Programmatic enabling (JDK 11+):
import jdk.jfr.*;

Configuration config = Configuration.getConfiguration("profile");
Recording recording = new Recording(config);
recording.setName("PaymentAPI-Perf");
recording.setDuration(Duration.ofSeconds(120));  // Record for 120 seconds
recording.setMaxSize(50 * 1024 * 1024);         // 50MB max file
recording.start();

// Later:
recording.stop();
recording.dump(Path.of("/tmp/perf.jfr"));
recording.close();

// Command-line enabling:
// java -XX:StartFlightRecording=filename=perf.jfr,duration=120s -jar app.jar
// jcmd <pid> JFR.start name=profile duration=120s filename=/tmp/perf.jfr
// jcmd <pid> JFR.dump name=profile filename=/tmp/perf.jfr
// jcmd <pid> JFR.stop name=profile

// Key JFR events for Spring Boot performance analysis:
// ┌──────────────────────────────────────────────────────────────────┐
// │ EVENT TYPE           │ WHAT IT TELLS YOU                          │
// ├──────────────────────┼────────────────────────────────────────────┤
// │ jdk.ExecutionSample  │ Which methods are consuming CPU            │
// │ jdk.GarbageCollection│ GC type, duration, cause, before/after mem │
// │ jdk.ThreadDump       │ Thread state snapshot                      │
// │ jdk.ThreadSleep      │ Threads sleeping (Thread.sleep())          │
// │ jdk.ThreadPark       │ Threads waiting (locks, I/O, futures)      │
// │ jdk.JavaMonitorWait  │ Threads waiting on synchronized monitors   │
// │ jdk.JavaMonitorEnter │ Threads blocked entering sync block        │
// │ jdk.FileRead         │ File I/O read events (size, duration)      │
// │ jdk.FileWrite        │ File I/O write events                      │
// │ jdk.SocketRead       │ Socket read events (duration)              │
// │ jdk.SocketWrite      │ Socket write events                        │
// │ jdk.ObjectAllocationInNewTLAB │ Allocation rate (per thread)       │
// │ jdk.ObjectAllocationOutsideTLAB │ Large object allocation          │
// │ jdk.CodeCacheFull    │ JIT code cache exhaustion                  │
// │ jdk.ClassLoad        │ Class loading events (startup perf)        │
// └──────────────────────────────────────────────────────────────────┘
```

### async-profiler Architecture

```
async-profiler is a low-overhead sampling profiler that uses two data sources:

1. perf_events (Linux kernel):
   └── CPU cycle counters, cache misses, page faults, context switches
   └── Sample rate: configurable, default ~1000 Hz (1000 samples/second)

2. JVM TI (JVM Tool Interface):
   └── GetStackTrace() — resolves native stack frames to Java methods
   └── Used to convert native instruction pointers → Java method names

Profiling modes:
  cpu       → Samples CPU-consuming methods (using perf_events)
  alloc     → Samples object allocation sites (using TLAB/outside-TLAB)
  lock      → Samples contended locks (using JVM TI monitor events)
  wall      → Samples thread states regardless of CPU usage
  itimer    → Legacy mode, lower accuracy (don't use in production)

Flame graph generation:
  asprof -d 30 -f /tmp/profile.html <pid>
    → Records 30 seconds of CPU samples
    → Generates interactive flame graph in HTML
    → Each box = a stack frame (method)
    → Width = proportion of samples in that method
    → Color: green=Java, yellow=C++, red=system, orange=kernel
```

### Spring Boot Startup Performance Internals

```java
// Source: org.springframework.boot.SpringApplication
// The startup performance critical path:

public ConfigurableApplicationContext run(String... args) {
    StopWatch stopWatch = new StopWatch();
    stopWatch.start();

    // PHASE 1: Bootstrap Environment (~5-10% of startup time)
    ConfigurableEnvironment environment = prepareEnvironment(listeners, args);

    // PHASE 2: Prepare ApplicationContext (~2-5%)
    ConfigurableApplicationContext context = createApplicationContext();
    prepareContext(context, environment, listeners, args);

    // PHASE 3: Refresh — THE BULK OF STARTUP TIME (~70-80%)
    refreshContext(context);
    // Inside refresh():
    //   ├── BeanFactoryPostProcessor invocation (ConfigurationClassParser)
    //   │   └── Classpath scanning: @Component, @Service, @Repository, @Controller
    //   │       ClasspathScanningCandidateComponentProvider.findCandidateComponents()
    //   │         → Iterates ALL packages, loads .class files, checks annotations
    //   │         → COST: linear with number of classes on classpath
    //   │   └── Configuration class parsing
    //   │       ConfigurationClassParser.doProcessConfigurationClass()
    //   │         → Processes @ComponentScan, @Import, @Bean methods
    //   │         → COST: linear with number of @Configuration classes
    //   ├── Auto-configuration evaluation (~15-25%)
    //   │   AutoConfigurationImportSelector.getAutoConfigurationEntry()
    //   │     → Loads META-INF/spring/org.springframework.boot.autoconfigure.
    //   │       AutoConfiguration.imports (180+ entries in Spring Boot 3.x)
    //   │     → For EACH: evaluates @ConditionalOnClass, @ConditionalOnBean,
    //   │       @ConditionalOnProperty, @ConditionalOnMissingBean
    //   │     → Condition evaluation is cheap individually, but 180 × 5 conditions
    //   │       = 900 condition evaluations adds up
    //   │     → COST: ~200-500ms for all auto-config classes
    //   ├── Bean instantiation (~30-40%)
    //   │   DefaultListableBeanFactory.preInstantiateSingletons()
    //   │     → Iterates all singleton bean definitions
    //   │     → Creates instance, injects dependencies, calls @PostConstruct
    //   │     → COST: linear with number of beans (200-500 beans typical)
    //   └── Web server start (~10-15%)
    //       ServletWebServerApplicationContext.onRefresh()
    //         → Creates embedded Tomcat, binds to port, starts acceptor threads

    // PHASE 4: After Refresh — Runners (~2-5%)
    afterRefresh(context, args);
    // ApplicationRunner and CommandLineRunner beans executed
    // COST: depends on what runners do (migrations, cache warmup, etc.)

    stopWatch.stop();
    return context;
}
```

## 4. Runtime Behavior

### JIT Compilation Warm-Up Behavior

```
TIERED COMPILATION (default in modern JVMs):

Level 0: Interpreted (bytecode interpreter)
  └── Fast startup, slow execution (~10-100x slower than compiled)

Level 1: C1 compiled (simple, no profiling)
  └── Quick compilation, moderate optimization
  └── Used for methods called a few times

Level 2: C1 compiled with profiling
  └── Collects: invocation counts, branch probabilities, type profiles
  └── Used to inform C2 optimization decisions

Level 3: C1 compiled with full profiling
  └── More detailed profiling data for C2

Level 4: C2 compiled (aggressive optimization)
  └── Slow compilation, maximum performance
  └── Optimizations: inlining, loop unrolling, escape analysis,
      dead code elimination, lock coarsening

WARM-UP CURVE FOR A TYPICAL SPRING BOOT APPLICATION:

             Throughput (requests/s)
             ^
  10,000 ───┤                             ╭────────────── (C2 compiled)
             │                         ╭───╯
   8,000 ───┤                     ╭───╯
             │                 ╭───╯
   6,000 ───┤             ╭───╯
             │         ╭───╯
   4,000 ───┤     ╭───╯
             │ ╭───╯
   2,000 ───┤─╯  (C1 compiled + profiling)
             │
       0 ───┼─────┬─────┬─────┬─────┬─────┬─────┬─────→ Time
             0     2     4     6     8    10    12    15 min

T+0:    Startup complete, all code interpreted
T+2:    Hot methods reach C1 compilation threshold
T+5:    C2 compilation begins for critical paths
T+10:   Most critical code is C2-compiled, throughput stabilizes
T+15:   Peak throughput achieved (warm-up complete)

Warm-up implications:
  - Load testing must include 10-15 min warm-up before measuring
  - Rolling deployments: new instances are SLOWER for 10-15 min
  - Solution: pre-warm with synthetic traffic before adding to LB pool
  - Or: use AppCDS (Application Class Data Sharing) for faster startup

JIT WARM-UP SPRING BOOT ANTI-PATTERNS:
  - Megamorphic call sites: > 3 receiver types at a call site → C2 can't inline
    → Falls back to virtual dispatch → 3-5x slower
    → Common in: polymorphic service interfaces, strategy patterns

  - Deep inlining limits: C2 can inline ~9 levels deep by default
    → Spring AOP proxy chains (proxy → interceptor → advice → target → ...)
    → Each proxy layer adds 1 depth → deep chains prevent inlining
```

### Garbage Collection Runtime Behavior

```
HEAP USAGE DURING A LOAD TEST (G1GC, -Xmx2g):

Memory
  │
2GB ├────────────────────────────────────────────────────────────╮
    │                              ╭─╮  ╭─╮  ╭─╮                │
    │        ╭─╮    ╭─╮    ╭──╮  ╭╯ ╰──╯ ╰──╯ ╰──               │
1GB ├────────╯ ╰────╯ ╰────╯  ╰──╯                               │
    │  (young)  (mixed) (mixed) (young)  (young) (young)         │
    │   GC       GC      GC      GC       GC      GC             │
    │                                                             │
 0  ├─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────→ Time
    0    30    60    90   120   150   180   210   240   seconds

GC EVENT BREAKDOWN:
  Young GC (~10-30ms): Clears Eden + Survivor
    Frequency: every 2-5 seconds under load
    Cost: 10-30ms pause time
    Good: frees lots of short-lived request/response objects

  Mixed GC (~30-80ms): Clears Young + some Old regions
    Frequency: every 30-60 seconds
    Cost: 30-80ms pause
    OK: normal part of G1 operation

  Full GC (~200ms-5s): Clears entire heap (all regions)
    Frequency: SHOULD BE ZERO in steady state
    Cost: 200ms-5s STOP-THE-WORLD
    BAD: indicates the heap is too small or a memory leak

GC PAUSE IMPACT ON LATENCY:
  If p99 target = 100ms and GC pause = 50ms:
    → 1 in 20 requests experience +50ms latency
    → Still within SLO (150ms < 500ms threshold)

  If p99 target = 50ms and GC pause = 200ms (Full GC):
    → Every request during Full GC waits 200ms
    → p99 spike to 250ms → SLO breach
    → Alert: "Payment API p99 latency > 100ms"
```

## 5. Request Flow Diagrams

### Request Processing Timing Breakdown

```
GET /api/orders/12345  — End-to-end timing (100ms total):

T+0ms     Socket accepted by Tomcat Acceptor
          │
T+0.1ms   Handed to Poller, registered with NIO Selector
          │
T+0.2ms   Worker thread picks up request from Poller
          │
T+0.5ms   Filter chain: SecurityContextFilter (0.1ms)
          │ RequestContextFilter (0.1ms)
          │ CorsFilter (0.05ms)
          │
T+1ms     DispatcherServlet.doDispatch()
          │ RequestMappingHandlerMapping.getHandler() → 0.1ms (cached)
          │
T+2ms     RequestResponseBodyMethodProcessor.resolveArgument()
          │ ├─ readWithMessageConverters() → Jackson deserialization → 1ms
          │ └─ validateIfApplicable() → Hibernate Validator → 0.5ms
          │
T+3ms     OrderController.getOrder() invoked
          │
T+4ms     OrderService.findById(12345)
          │ └─ OrderRepository.findById(12345)
          │
T+5ms     HikariCP: borrow connection from pool → 0.3ms
          │
T+6ms     JDBC: PreparedStatement.executeQuery()
          │ "SELECT * FROM orders WHERE id = ?"
          │ PostgreSQL: index scan on orders_pkey → 1ms
          │ Network round trip → 0.5ms
          │
T+8ms     Hibernate: ResultSet → Order entity hydration → 1.5ms
          │ (Entity loaded from persistence context cache if previously loaded)
          │
T+10ms    HikariCP: return connection to pool → 0.1ms
          │
T+12ms    OrderService calls InventoryClient.getStock(order.productId)
          │ ├─ HTTP connection from pool → 0.2ms
          │ ├─ Serialize request → 0.3ms
          │ ├─ Network round-trip → 5ms (p50)
          │ ├─ Deserialize response → 0.5ms
          │ └─ Return connection to pool → 0.1ms
          │ Total HTTP call: 6ms
          │
T+18ms    OrderService calls PricingService.getPrice(order.productId)
          │ └─ Caffeine cache hit → 0.01ms (cached)
          │
T+20ms    OrderService assembles OrderResponse DTO
          │ (15 fields mapped manually, no BeanUtils)
          │
T+21ms    Controller returns OrderResponse
          │
T+22ms    RequestResponseBodyMethodProcessor.handleReturnValue()
          │ └─ Jackson serialization → 2ms (DTO is simple, no circular refs)
          │
T+25ms    Response written to Tomcat output buffer
          │
T+26ms    TCP send buffer → OS → network
          │
T+30ms    Client receives complete response
          │
          ★ 30ms total observed at client
          │
          BREAKDOWN:
          │ Tomcat overhead:    2ms  (7%)
          │ Deserialization:     1ms  (3%)
          │ Validation:          1ms  (3%)
          │ DB query:            3ms  (10%)
          │ ORM hydration:       2ms  (7%)
          │ HTTP call:           6ms  (20%)
          │ Cache hit:           0ms  (0%)
          │ DTO assembly:        1ms  (3%)
          │ Serialization:       2ms  (7%)
          │ Network overhead:    4ms  (13%)
          │————————————————————————————
          │ Total:              30ms
```

## 6. Lifecycle Diagrams

### Spring Boot Startup Performance Lifecycle

```
STARTUP TIMELINE (annotated with cumulative time):

[0.0s]  JVM Bootstrap
        ├── -Xmx2g -Xms2g (heap pre-allocated → no resizing cost)
        ├── Class loading from JARs (3000+ classes)
        └── → 1.5s

[1.5s]  SpringApplication.run() begins
        │
[1.6s]  Prepare Environment
        ├── Load application.properties/yml from 8 locations
        ├── Override with environment variables
        └── → 0.1s

[1.7s]  Create ApplicationContext
        └── → 0.05s

[1.8s]  Refresh ApplicationContext ← MAJORITY OF TIME HERE
        │
[1.8s]  BeanFactoryPostProcessors
        ├── ConfigurationClassParser
        │   ├── @ComponentScan basePackages
        │   │   └── Scanning 1500 classes in com.example.** → 0.5s
        │   ├── @Import processing → 0.1s
        │   └── @Bean method detection → 0.1s
        └── → 0.8s

[2.6s]  AutoConfiguration evaluation
        ├── Load 185 auto-config classes from spring.factories
        ├── Evaluate @ConditionalOnClass × 185 → 0.3s
        ├── Evaluate @ConditionalOnBean × 100 → 0.2s
        ├── Evaluate @ConditionalOnProperty × 50 → 0.05s
        └── → 1.0s (180 evaluated, 72 matched, 108 skipped)

[3.6s]  Bean creation
        ├── 350 singleton beans created
        ├── Dependency injection resolved
        ├── @PostConstruct methods called
        │   ├── DataSource initialization (HikariCP) → 0.3s
        │   ├── EntityManagerFactory (Hibernate) → 1.5s
        │   │   └── Scan entities, build metamodel, validate mappings
        │   ├── Redis connection → 0.2s
        │   └── Kafka producer/consumer → 0.3s
        └── → 3.5s

[7.1s]  Web server initialization
        ├── Tomcat connector bind → 0.05s
        ├── Acceptor threads start → 0.02s
        └── → 0.3s

[7.4s]  finishRefresh()
        ├── LifecycleProcessor.onRefresh() → 0.1s
        └── ContextRefreshedEvent published → 0.05s

[7.6s]  ApplicationRunners
        ├── Database migration check (Flyway/Liquibase) → 0.5s
        │   (only checks if schema is up to date — no migrations to run)
        ├── Cache warmup → 0.3s
        └── → 1.0s

[8.6s]  STARTUP COMPLETE — ApplicationReadyEvent published
        │
        │ Post-startup warm-up:
        ├── [8.6s] C1 JIT compilation begins
        ├── [15s]  C2 JIT compilation begins
        └── [600s] Full performance stabilization
```

### Performance Optimization Lifecycle

```
OPTIMIZATION CYCLE:

  ┌────────────────────────────────────────────┐
  │ 1. IDENTIFY BOTTLENECK                      │
  │                                              │
  │ Run load test at target throughput          │
  │ Profile with async-profiler (CPU mode)      │
  │ Identify top 3 CPU-consuming methods        │
  │                                              │
  │ Example finding:                              │
  │   45% — JPAOrderRepository.findById          │
  │   22% — JacksonObjectMapper.writeValueAsString   │
  │   12% — String.format in hot loop            │
  └──────────────────┬─────────────────────────┘
                     │
                     v
  ┌────────────────────────────────────────────┐
  │ 2. FORMULATE HYPOTHESIS                     │
  │                                              │
  │ "findById is slow because we're doing       │
  │  N+1 queries — the entity graph loads       │
  │  Order → OrderItems → Product → Category     │
  │  eagerly, generating 50+ SQL queries"       │
  │                                              │
  │ Expected improvement: 60% reduction in      │
  │ CPU time for this call path                  │
  └──────────────────┬─────────────────────────┘
                     │
                     v
  ┌────────────────────────────────────────────┐
  │ 3. IMPLEMENT FIX                            │
  │                                              │
  │ @EntityGraph(attributePaths = {"items",     │
  │     "items.product", "items.product.category│
  │ }) + JOIN FETCH in JPQL                     │
  │ → Single SQL query with JOINs               │
  └──────────────────┬─────────────────────────┘
                     │
                     v
  ┌────────────────────────────────────────────┐
  │ 4. MEASURE IMPROVEMENT                      │
  │                                              │
  │ Re-run load test at same throughput         │
  │ Re-profile with async-profiler               │
  │                                              │
  │ BEFORE: 45% CPU in findById                   │
  │ AFTER:  8% CPU in findById                    │
  │ → 5.6x improvement confirmed                 │
  │                                              │
  │ Was the hypothesis correct? YES              │
  │ Did the fix introduce any regression? NO     │
  │ (full test suite passes, error rate unchanged)  │
  └──────────────────┬─────────────────────────┘
                     │
                     v
  ┌────────────────────────────────────────────┐
  │ 5. DOCUMENT & REPEAT                        │
  │                                              │
  │ Document: what was slow, why, what fixed it │
  │ Add performance test to CI pipeline         │
  │ Load test should now fail if p99 > 100ms    │
  │                                              │
  │ Next bottleneck: Jackson serialization →     │
  │ investigate if @JsonIgnoreProperties or     │
  │ ObjectMapper reconfiguration helps            │
  └────────────────────────────────────────────┘
```

## 7. Source Code Reading Guide

### Key Source Files for Performance Analysis

```
Spring Boot Startup:
  ✅ org.springframework.boot.SpringApplication
     └── run() — the full startup sequence
     └── Where: spring-boot

  ✅ org.springframework.boot.SpringApplicationRunListeners
     └── Event publishing during startup phases
     └── Where: spring-boot

  ✅ org.springframework.context.annotation.ConfigurationClassParser
     └── doProcessConfigurationClass() — parse @Configuration, @ComponentScan, @Import
     └── Where: spring-context

  ✅ org.springframework.boot.autoconfigure.AutoConfigurationImportSelector
     └── getAutoConfigurationEntry() — load and filter auto-config classes
     └── Where: spring-boot-autoconfigure

JIT and GC:
  ✅ (JDK source) jdk.jfr.* package
     └── Recording, Event, Configuration
     └── Where: JDK jdk.jfr module

Micrometer Performance Integration:
  ✅ io.micrometer.core.instrument.Timer
     └── record() — the hot path for metric recording
     └── Where: micrometer-core

  ✅ io.micrometer.core.instrument.distribution.TimeWindowHistogram
     └── recordLong() — accumulate histogram data
     └── Where: micrometer-core

Spring Data JPA Performance:
  ✅ org.springframework.data.jpa.repository.query.JpaQueryExecution
     └── execute() — query execution with result mapping
     └── Where: spring-data-jpa

  ✅ org.hibernate.internal.SessionImpl
     └── find() — entity loading from DB or cache
     └── Where: hibernate-core

Jackson Serialization:
  ✅ com.fasterxml.jackson.databind.ObjectMapper
     └── writeValue() — serialization entry point
     └── Where: jackson-databind

  ✅ com.fasterxml.jackson.databind.ser.BeanSerializer
     └── serialize() — per-bean serialization
     └── Where: jackson-databind
```

### Reading Order
1. `SpringApplication.run()` — understand the full startup sequence
2. `ConfigurationClassParser.doProcessConfigurationClass()` — understand classpath scanning
3. `AutoConfigurationImportSelector.getAutoConfigurationEntry()` — understand auto-config loading
4. `TimeWindowHistogram.recordLong()` — understand Micrometer recording cost
5. `ObjectMapper.writeValue()` — understand serialization cost model

### Key Code Snippets Worth Reading

```java
// 1. Spring Boot startup warmup — JIT compilation triggers
// Methods are compiled when call + loop back-edge counters hit thresholds:
//   C1 compilation: ~1,500 invocations (configurable with -XX:TieredStopAtLevel=1)
//   C2 compilation: ~10,000 invocations (configurable with -XX:CompileThreshold=10000)

// You can observe JIT activity with:
// -XX:+PrintCompilation
// Output:
//  124  1   3   java.lang.String::hashCode (55 bytes)   — C1 compiled (level 3)
//  125  2   4   com.example.OrderService::process (312 bytes) — C2 compiled (level 4)

// 2. Spring Data JPA: How findByEmail becomes a query
// Source: org.springframework.data.jpa.repository.query.JpaQueryLookupStrategy
// The resolution chain:
//   1. Check for @Query annotation → use declared JPQL/SQL
//   2. Check for named query (META-INF/jpa-named-queries.properties)
//   3. Derive query from method name:
//      PartTreeJpaQuery.fromQueryAnnotation() → PartTree → JpaQueryCreator
//      "findByEmailAndStatus" → "SELECT x FROM User x WHERE x.email = ?1 AND x.status = ?2"
//   4. For native queries, check @Query(nativeQuery = true)

// The derived query method name parser is implemented in:
// org.springframework.data.repository.query.parser.PartTree
// It splits "findByEmailAndStatusOrderByCreatedAtDesc" into:
//   Subject: "find"
//   Predicate: "EmailAndStatus"
//   OrderBy: "CreatedAtDesc"
// Then iterates each part to build the JPQL WHERE clause.

// 3. HikariCP connection pool — how validation works
// Source: com.zaxxer.hikari.pool.PoolBase.isConnectionAlive()

boolean isConnectionAlive(final Connection connection) {
    try {
        if (isUseJdbc4Validation) {
            // JDBC 4: connection.isValid(timeout) — sends a lightweight ping
            return connection.isValid(validationTimeout);
        }
        // Legacy: execute the validation query (e.g., SELECT 1)
        try (Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(validationTimeout);
            stmt.execute(config.getConnectionTestQuery());
        }
        return true;
    } catch (SQLException e) {
        return false;
    }
}
// Called: when connection is idle for > idleTimeout, before returning from pool
// Cost: ~1ms for local DB, ~5ms for remote DB — adds to connection borrow latency

// 4. Jackson serialization hot path
// Source: com.fasterxml.jackson.databind.ser.BeanSerializer.serialize()

public void serialize(Object bean, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
    // For each property in the bean:
    //   1. Call getter method via reflection (cached MethodHandle)
    //   2. Write property name
    //   3. Write property value (recursively serialize nested objects)
    //   4. Handle nulls, @JsonInclude, @JsonIgnore, etc.
    //
    // BEAN PROPERTY SERIALIZERS are computed once per class and cached.
    // The CACHED BeanPropertyWriter avoids reflection on every serialization.
    // First serialization: ~50µs (build serializer) + 10µs (serialize)
    // Subsequent: ~10µs (serialize only, using cached serializer)
}

// Jackson performance modules:
// Afterburner/Blackbird use bytecode generation to replace reflection
// with direct method calls, bypassing java.lang.reflect entirely.
// Speed improvement: 30-50% for serialization-heavy endpoints.

// 5. Thread pool sizing — the math behind pool size
// Source: com.zaxxer.hikari.HikariConfig
// Default maximumPoolSize = 10
//
// The Pool Sizing Formula (from HikariCP wiki):
// connections = ((core_count * 2) + effective_spindle_count)
//
// For a 4-core server with SSD (no spinning disk):
// connections = (4 * 2) + 1 = 9
// → Default of 10 is close to optimal for most cases
//
// But for applications with FAST queries (< 1ms):
// connections = core_count * 2 = 8 (less contention per connection)
//
// For applications with SLOW queries (> 100ms):
// connections = (core_count * 2 * avgQueryTime / targetLatency) + 1
//   = (4 * 2 * 100ms / 50ms) + 1 = 17
// → Need more connections to maintain throughput during slow queries
```

### Advanced JFR Configuration for Spring Boot

```bash
# JFR Configuration — always-on, low-overhead (1-2% CPU)

# Enable JFR at JVM startup with continuous recording:
java -XX:StartFlightRecording:name=app,settings=default,disk=true,maxsize=200m,dumponexit=true \
     -jar payment-api.jar

# The 'default' settings profile captures ~50 events at low overhead.
# The 'profile' settings profile captures ~150 events at ~2% overhead.

# Custom JFR settings file (jfr-settings.jfc):
<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0">
    <!-- Increase allocation sampling frequency for Spring Boot -->
    <event name="jdk.ObjectAllocationInNewTLAB">
        <setting name="enabled">true</setting>
        <setting name="period">everyChunk</setting>
    </event>
    <event name="jdk.ObjectAllocationOutsideTLAB">
        <setting name="enabled">true</setting>
        <setting name="period">everyChunk</setting>
    </event>

    <!-- Monitor socket I/O (HTTP calls) -->
    <event name="jdk.SocketRead">
        <setting name="enabled">true</setting>
        <setting name="stackTrace">true</setting>
        <setting name="threshold">10 ms</setting>
    </event>
    <event name="jdk.SocketWrite">
        <setting name="enabled">true</setting>
        <setting name="stackTrace">true</setting>
        <setting name="threshold">10 ms</setting>
    </event>

    <!-- Monitor lock contention -->
    <event name="jdk.JavaMonitorWait">
        <setting name="enabled">true</setting>
        <setting name="stackTrace">true</setting>
        <setting name="threshold">10 ms</setting>
    </event>

    <!-- GC events always enabled -->
    <event name="jdk.GarbageCollection">
        <setting name="enabled">true</setting>
        <setting name="stackTrace">true</setting>
    </event>
</configuration>

# Run with custom settings:
java -XX:StartFlightRecording:name=app,settings=jfr-settings.jfc,disk=true \
     -jar payment-api.jar

# Dump recording on-demand (no restart needed):
jcmd <pid> JFR.dump name=app filename=/tmp/dump.jfr

# Analyze the JFR file:
# 1. Open in JDK Mission Control (JMC):
#    jmc /tmp/dump.jfr
#
# 2. Programmatic analysis:
import jdk.jfr.consumer.RecordingFile;
Path file = Path.of("/tmp/dump.jfr");
try (RecordingFile recordingFile = new RecordingFile(file)) {
    while (recordingFile.hasMoreEvents()) {
        RecordedEvent event = recordingFile.readEvent();
        if ("jdk.SocketRead".equals(event.getEventType().getName())) {
            System.out.printf("Socket read: %d bytes in %d ms at %s%n",
                event.getLong("bytesRead"),
                event.getDuration().toMillis(),
                event.getStackTrace().getFrames().get(0).getMethod().getName());
        }
    }
}
```

## 8. Production Failure Scenarios

### Scenario 1: N+1 Query Degradation

**Symptom:**
- `/api/orders` endpoint was p99=80ms, now p99=3s
- Database CPU increased from 15% to 60%
- No code changes in the last deployment
- Load hasn't changed significantly

**Investigation:**
```bash
# Check slow query log
SELECT query, calls, mean_exec_time, total_exec_time
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;

# Shows:
# query: SELECT * FROM order_items WHERE order_id = $1
# calls: 4,500,000 (in past hour!)
# mean_exec_time: 0.5ms (fast individually)
# total_exec_time: 2,250,000ms (MASSIVE total)

# Check application code:
# OrderService.findAll():
#   List<Order> orders = orderRepository.findAll();  // 1 query
#   for (Order order : orders) {
#       List<OrderItem> items = itemRepository.findByOrderId(order.getId());
#       // N additional queries — ONE PER ORDER!
#       // With 200 orders: 201 queries total
#   }

# Profile in production:
# async-profiler shows:
#   55% CPU: HibernateResultSetHandler.readRow()
#   30% CPU: PostgreSQL JDBC driver socketRead()
#   → Confirmed: DB I/O is the bottleneck
```

**Root Cause:** The number of orders per page increased from 20 to 200 when a client changed `?size=200` in their pagination. Each order triggered a lazy-load query for its items. At 20 orders, 21 queries was fine. At 200 orders, 201 queries caused a 10x increase in DB load.

**Permanent Fix:**
```java
// BEFORE (N+1):
@GetMapping("/api/orders")
public List<OrderResponse> getOrders() {
    List<Order> orders = orderRepository.findAll();  // 1 query
    return orders.stream()
        .map(order -> {
            List<OrderItem> items = order.getItems(); // N queries (lazy load)
            return OrderResponse.from(order);
        })
        .collect(Collectors.toList());
}

// AFTER (single query with JOIN FETCH):
@GetMapping("/api/orders")
public List<OrderResponse> getOrders() {
    List<Order> orders = orderRepository.findAllWithItems();  // 1 query with JOIN
    return orders.stream()
        .map(OrderResponse::from)
        .collect(Collectors.toList());
}

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("SELECT DISTINCT o FROM Order o " +
           "LEFT JOIN FETCH o.items " +
           "ORDER BY o.createdAt DESC")
    List<Order> findAllWithItems();

    // Alternative: @EntityGraph
    @EntityGraph(attributePaths = {"items"})
    List<Order> findAll();
}

// Pagination: NEVER return unlimited results. Always page.
@GetMapping("/api/orders")
public Page<OrderResponse> getOrders(
        @PageableDefault(size = 20) Pageable pageable) {
    return orderRepository.findAll(pageable)
        .map(OrderResponse::from);
}
```

### Scenario 2: ObjectMapper Re-creation

**Symptom:**
- High CPU usage under load (80%+)
- Thread dump shows many threads in `ObjectMapper.<init>()`
- Heap histogram shows thousands of `ObjectMapper` instances
- Startup is also slow (ObjectMapper construction is expensive)

**Investigation:**
```bash
# Heap histogram:
jcmd <pid> GC.class_histogram | grep ObjectMapper
#   num     #instances     #bytes  class name
#  9047:         12345    1975200  com.fasterxml.jackson.databind.ObjectMapper
# → 12,345 ObjectMapper instances! Should be ONE.

# Find the code responsible:
grep -rn "new ObjectMapper()" src/main/java/
# Returns: 15 locations across 8 files
# Including: one in every @Service class for "convenience"
```

**Fix:**
```java
// BEFORE (anti-pattern):
@Service
public class OrderService {
    private final ObjectMapper mapper = new ObjectMapper();
    // Each @Service creates its OWN ObjectMapper
    // ObjectMapper is thread-safe and expensive to create
}

// AFTER:
@Configuration
public class JacksonConfig {
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json()
            .modulesToInstall(new JavaTimeModule())
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }
}

// Inject the single instance everywhere:
@Service
public class OrderService {
    private final ObjectMapper mapper;  // Injected — singleton
    // ...
}
```

### Scenario 3: G1GC Humongous Allocation

**Symptom:**
- G1GC log shows frequent "Humongous Allocation" events
- GC pauses spiking to 500ms+
- Application heap dumps show large byte[] arrays (10MB+ each)
- Throughput drops sharply during GC events

**Root Cause:** An endpoint returns a large JSON response (50MB) constructed as a single `String` before serialization. G1GC treats allocations larger than 50% of a heap region (default region size = 2MB for 2GB heap) as "humongous" and allocates them directly in the old generation. Frequent humongous allocations trigger GC early and fragment the heap.

**Fix:**
```java
// BEFORE:
@GetMapping("/api/report/large")
public String getLargeReport() {
    List<ReportRow> rows = get1MillionRows();
    // Builds a 50MB String in memory before returning
    String json = objectMapper.writeValueAsString(rows);
    return json;
}

// AFTER: Stream the response — never hold the full 50MB in memory
@GetMapping(value = "/api/report/large", produces = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<StreamingResponseBody> getLargeReport() {
    StreamingResponseBody stream = outputStream -> {
        JsonGenerator generator = objectMapper.getFactory()
            .createGenerator(outputStream, JsonEncoding.UTF8);
        generator.writeStartArray();

        try (Stream<ReportRow> rows = reportRepository.streamAll()) {
            rows.forEach(row -> {
                try {
                    generator.writeObject(row);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        generator.writeEndArray();
        generator.close();
    };

    return ResponseEntity.ok().body(stream);
}

// Even better: paginate and never return 1M rows
@GetMapping("/api/report/paged")
public Page<ReportRow> getReportPaged(Pageable pageable) {
    return reportRepository.findAll(pageable);
}
```

## 9. Debugging Techniques

### async-profiler Deep Dive

```bash
# Installation
wget https://github.com/async-profiler/async-profiler/releases/download/v3.0/async-profiler-3.0-linux-x64.tar.gz
tar xzf async-profiler-3.0-linux-x64.tar.gz

# === CPU PROFILING ===
# Record 30 seconds of CPU activity, generate flame graph
./profiler.sh -d 30 -f /tmp/cpu-flamegraph.html <pid>

# Record CPU with specific event (more accurate on some CPUs)
./profiler.sh -e cpu -d 30 -f /tmp/cpu.html <pid>

# === ALLOCATION PROFILING ===
# Record where objects are allocated (useful for memory pressure)
./profiler.sh -e alloc -d 30 -f /tmp/alloc-flamegraph.html <pid>

# Total allocation: X bytes allocated in 30 seconds
# Wide boxes = methods that allocate the most memory
# Common culprits: StringBuilder.toString(), byte[] for I/O, JSON parsing

# === WALL-CLOCK PROFILING ===
# Record what threads are doing (including waiting)
./profiler.sh -e wall -d 30 -f /tmp/wall-flamegraph.html <pid>

# Different from CPU profiling: shows I/O wait, lock wait, thread sleep
# Wide boxes where the stack doesn't contain a "Runnable" thread = waiting
# Use to find: blocking I/O, lock contention, thread starvation

# === LOCK PROFILING ===
# Record contended locks
./profiler.sh -e lock -d 30 -f /tmp/lock-flamegraph.html <pid>

# Shows monitors that threads are blocking on
# Wide boxes = highly contended monitors
# Use to find: synchronized block bottlenecks, ReentrantLock contention

# === LIVE PROFILING (attaching to running process) ===
./profiler.sh start <pid>
# ... let it run for N seconds ...
./profiler.sh stop <pid> -f /tmp/profile.html

# === PROFILING WITH FILTER ===
# Only profile methods matching a pattern
./profiler.sh -d 30 --include 'com/example/*' -f /tmp/filtered.html <pid>
./profiler.sh -d 30 --exclude 'java/*,javax/*,sun/*' -f /tmp/app-only.html <pid>
```

### Interpreting Flame Graphs

```
READING A FLAME GRAPH:

Each box represents a stack frame (method).
Width = proportional to the number of samples in that method.
Color = JVM distinction (green=Java, yellow=C++, red=system, orange=kernel).

How to read it BOTTOM-TO-TOP:

┌────────────────────────────────────────────────────────────────────────┐
│  [             Controller.getOrder() — 2% of samples         ]          │
│  ┌──────────────────────────┐  ┌─────────────────────────────┐        │
│  │    service.findById()    │  │   validatePermissions()      │        │
│  │    ─ 20% of samples      │  │   ─ 5% of samples            │        │
│  │ ┌────────────────────┐   │  │                              │        │
│  │ │  repo.findById()   │   │  └──────────────────────────────┘        │
│  │ │  ─ 15%             │   │                                          │
│  │ │ ┌───────────────┐  │   │                                          │
│  │ │ │JPA/Hibernate  │  │   │                                          │
│  │ │ │─ 12%          │  │   │                                          │
│  │ │ │ ┌───────────┐ │  │   │                                          │
│  │ │ │ │ JDBC call │ │  │   │                                          │
│  │ │ │ │ ─ 8%      │ │  │   │                                          │
│  │ │ │ │ ┌───────┐ │ │  │   │                                          │
│  │ │ │ │ │ TCP   │ │ │  │   │                                          │
│  │ │ │ │ │Send   │ │ │  │   │                                          │
│  │ │ │ │ │─ 5%   │ │ │  │   │                                          │
│  │ │ │ │ └───────┘ │ │  │   │                                          │
│  │ │ │ └───────────┘ │  │   │                                          │
│  │ │ └───────────────┘  │   │                                          │
│  │ └────────────────────┘   │                                          │
│  └──────────────────────────┘                                          │
│                                                                        │
│  ┌─────────────────────────────────────────────────────────────┐      │
│  │  Spring Framework / Platform code (bottom)                   │      │
│  │  │ StartThread.dispatch() → DispatcherServlet → Controller  │      │
│  └─────────────────────────────────────────────────────────────┘      │
└────────────────────────────────────────────────────────────────────────┘

TOP-DOWN INTERPRETATION:
  - Wide box at bottom = many samples → hot path
  - Stack of narrow boxes on top = call chain
  - If a box is wide but children are narrow = methods that do WORK (not just calling)
  - If all children are as wide as parent = mostly delegating (proxy/chain pattern)

COMMON SPRING BOOT FLAME GRAPH PATTERNS:
  1. "Towers" — tall, narrow stacks = deep proxy/chain processing
     → Too many AOP proxies, filter chains, or interceptor chains
     → Fix: reduce proxy depth, bypass unnecessary interceptors

  2. "Plateaus" — wide, flat areas = methods that consume CPU directly
     → CPU-bound processing (serialization, hashing, regex)
     → Fix: optimize the algorithm, cache results

  3. "Split" — a box splits into many equal children
     → Polymorphic dispatch (interface with many implementations)
     → May cause megamorphic call site (C2 can't inline)

  4. "Missing" — expected method not visible or very thin
     → Inlined by C2 (good!) — the method was optimized away
     → Don't "fix" this — it's working as intended
```

## 10. Observability Considerations

### Performance Metrics to Monitor

```yaml
# ESSENTIAL PERFORMANCE METRICS FOR EVERY SPRING BOOT SERVICE:

# Latency (RED):
- http_server_requests_seconds_bucket          # Histogram per endpoint
- jdbc_connections_max                         # DB connection pool size
- spring_data_repository_invocations_seconds    # Repository method timing

# Throughput (RED):
- http_server_requests_seconds_count           # Total request count
- jvm_gc_memory_allocated_bytes_total          # Allocation rate (GB/s)

# Saturation (USE):
- tomcat_threads_busy_threads                  # Current busy threads
- hikaricp_connections_active                  # Active DB connections
- hikaricp_connections_pending                 # Waiting for connection
- jvm_threads_live_threads                     # Total live threads

# CPU:
- process_cpu_usage                            # CPU usage (0.0-1.0 per core)
- system_cpu_usage                             # System-wide CPU

# Memory:
- jvm_memory_used_bytes{area="heap"}           # Heap used
- jvm_memory_max_bytes{area="heap"}            # Heap max
- jvm_memory_used_bytes{area="nonheap"}        # Metaspace used
- jvm_memory_committed_bytes{area="nonheap"}   # Metaspace committed

# GC:
- jvm_gc_pause_seconds_count                   # GC pause frequency
- jvm_gc_pause_seconds_sum                     # GC pause total time
- jvm_gc_memory_promoted_bytes_total           # Promotion rate

# Thread Pool:
- executor_pool_size_threads                   # Pool size
- executor_pool_core_threads                   # Core pool size
- executor_pool_max_threads                    # Max pool size
- executor_queue_remaining_tasks               # Queue depth (available, not used)
- executor_completed_tasks_total               # Completed tasks

# HTTP Client:
- http_client_requests_seconds                 # Outbound request timing
- http_client_connections_time_pool_pending    # Connection wait time

# Custom business:
- orders_created_total                         # Business throughput
- payment_processing_time_seconds              # Business latency
```

### Performance Alerting

```yaml
groups:
  - name: payment-api-performance
    rules:
      # Latency breaches
      - alert: HighP99Latency
        expr: |
          histogram_quantile(0.99,
            rate(http_server_requests_seconds_bucket{uri!="/actuator/.*"}[5m])
          ) > 0.5
        for: 5m
        annotations:
          summary: "p99 latency > 500ms across all non-actuator endpoints"

      # Connection pool pressure
      - alert: HikariCPPoolExhaustion
        expr: |
          rate(hikaricp_connections_timeout_total[5m]) > 0
        for: 1m
        annotations:
          summary: "HikariCP connection timeouts occurring — pool exhausted"

      # GC pressure
      - alert: GCPauseHigh
        expr: |
          rate(jvm_gc_pause_seconds_sum[5m]) /
          rate(jvm_gc_pause_seconds_count[5m]) > 0.1
        for: 5m
        annotations:
          summary: "Average GC pause > 100ms — JVM under memory pressure"

      # Thread pool saturation
      - alert: ThreadPoolNearSaturation
        expr: |
          tomcat_threads_busy_threads / tomcat_threads_config_max_threads > 0.8
        for: 5m
        annotations:
          summary: "Tomcat thread pool > 80% utilization"

      # Allocation rate anomaly (potential leak)
      - alert: HighAllocationRate
        expr: |
          rate(jvm_gc_memory_allocated_bytes_total[5m]) > 500 * 1024 * 1024
        for: 10m
        annotations:
          summary: "Allocation rate > 500 MB/s — check for excessive object creation"
```

## 11. Performance Implications

### Spring Boot Common Bottlenecks — Quantified

```
┌──────────────────────────────────┬──────────────┬──────────────┬────────────┐
│ BOTTLENECK                       │ TYPICAL COST │ HOW TO FIX   │ IMPROVEMENT│
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ N+1 queries                      │ 50 queries   │ JOIN FETCH   │ 50x faster │
│ (lazy loading in loop)           │ per request  │ / @EntityGraph│             │
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ ObjectMapper re-creation          │ 50ms per     │ Singleton    │ Instant     │
│ (new ObjectMapper() per request) │ create       │ bean injection│ (50ms → 0) │
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ BeanUtils.copyProperties()       │ 50-100µs     │ Manual       │ 10-50x     │
│ (reflection-based copy)          │ per invocation│ mapping      │ faster     │
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ Deep AOP proxy chain             │ 10-50µs      │ Reduce proxy │ 5-10x      │
│ (3+ interceptors on a bean)      │ per invocation│ depth; use   │ faster     │
│                                  │              │ direct calls │             │
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ Synchronized Logback appenders   │ 2-5µs per    │ Async        │ 3-10x      │
│ (all threads contend on log)     │ log line     │ appenders    │ faster     │
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ Jackson without afterburner/     │ 2-10µs per   │ Default      │ 2-3x       │
│ blackbird (reflection mode)      │ serialization│ config        │ faster     │
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ Unbounded response size          │ 50MB JSON    │ Pagination or│ 100x+      │
│ (SELECT * FROM large_table)     │ allocation   │ streaming    │ less memory│
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ Repeated Pattern.compile()       │ 1µs per      │ static final │ 10x faster │
│ (compile regex on every call)    │ invocation   │ field        │             │
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ String concatenation in loop     │ O(n²) for    │ StringBuilder│ 10-100x    │
│ (creates N intermediate strings) │ N iterations │              │ faster     │
├──────────────────────────────────┼──────────────┼──────────────┼────────────┤
│ Static method calls via proxy    │ 5-10µs per   │ Bypass proxy │ 5-20x      │
│ (proxy intercepts every call)    │ invocation   │ when possible│ faster     │
└──────────────────────────────────┴──────────────┴──────────────┴────────────┘
```

### Caching Architecture

```
CAFFEINE LOCAL CACHE:

Cache<String, Order> orderCache = Caffeine.newBuilder()
    .maximumSize(10_000)             // Max 10K entries, evicts LRU
    .expireAfterWrite(5, TimeUnit.MINUTES)  // Data freshness guarantee
    .refreshAfterWrite(1, TimeUnit.MINUTES) // Async refresh, serves stale if needed
    .recordStats()                    // Expose hit/miss/eviction stats
    .build(key -> repository.findById(key));

// Cache stampede prevention:
// When an entry expires under high load, ALL threads race to reload it.
// This causes a "thundering herd" — hundreds of identical DB queries.

// Solution: Caffeine's refreshAfterWrite refreshes async via a SINGLE thread:
// Thread-1: entry expires → sees it needs refresh → starts async refresh
// Threads 2-100: entry expired → sees refresh IN PROGRESS → serves stale value
// → Only 1 DB query, not 100

REDIS DISTRIBUTED CACHE (via Spring Cache):

@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public RedisCacheManagerBuilderCustomizer cacheManagerBuilderCustomizer() {
        return builder -> builder
            .withCacheConfiguration("orders",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(10))
                    .prefixCacheNameWith("payment:")     // Namespace
                    .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            );
    }
}

// Usage:
@Cacheable(value = "orders", key = "#orderId")
public Order getOrder(Long orderId) {
    return orderRepository.findById(orderId).orElseThrow();
}

@CacheEvict(value = "orders", key = "#order.id")
public Order updateOrder(Order order) {
    return orderRepository.save(order);
}

// Cache invalidation strategies:
// 1. TTL-based: expire naturally (simplest, eventual consistency)
// 2. Write-through: update cache on every write (strong consistency)
// 3. Cache-aside: application manages cache (most common in Spring)
// 4. CDC-based: Debezium reads DB WAL → invalidates cache (strongest)

// Multi-layer caching:
// L1: Caffeine (local, < 1ms, 10K entries, 5min TTL)
// L2: Redis (distributed, 1-5ms, 100K entries, 30min TTL)
// L3: Database (authoritative, 10-50ms)

public Order getOrderWithLayeredCache(Long orderId) {
    // L1: Caffeine
    Order order = localCache.getIfPresent(orderId);
    if (order != null) return order;

    // L2: Redis
    order = redisTemplate.opsForValue().get("orders:" + orderId);
    if (order != null) {
        localCache.put(orderId, order);
        return order;
    }

    // L3: Database
    order = orderRepository.findById(orderId).orElseThrow();
    redisTemplate.opsForValue().set("orders:" + orderId, order, 30, TimeUnit.MINUTES);
    localCache.put(orderId, order);
    return order;
}
```

### Capacity Planning

```
CAPACITY PLANNING FORMULA:

Given:
  - Target throughput: 1000 requests/second
  - p99 target latency: 100ms
  - Average latency per request: 20ms
  - Each request: 1 DB query (5ms) + 2 HTTP calls (10ms avg each) + 5ms overhead

Resource Estimation:

CPU cores needed:
  Using Little's Law: L = λ × W
    L = concurrent requests in system
    λ = arrival rate (requests/second)
    W = average time in system (seconds)

    L = 1000 req/s × 0.020s = 20 concurrent requests on average

  If each request uses ~1 core for 20ms:
    CPU time/sec = 1000 req/s × 0.020s = 20 CPU-seconds per wall-second
    → Need at least 20 CPU cores

  Practical: 20 / 0.7 (70% target utilization) = ~28 cores

Memory needed:
  Heap: (request size + session data + caches)
    Request size: 1000 concurrent × 50KB (req + resp objects) = 50MB
    Session data: minimal (stateless)
    Cache: Caffeine 10K entries × ~2KB = 20MB
    Framework overhead: Spring beans + Hibernate = 200MB
    GC overhead: ~20% of heap = 40MB

    Total heap: ~350MB → round up to 512MB minimum
    With 2x headroom for load spikes: 1GB
    -Xmx1g

  Direct memory: Netty/HTTP client buffers: ~100MB
  Metaspace: ~128MB (classes + method metadata)
  Total JVM memory: ~1.3GB RSS

Connections needed:
  HikariCP: pool_size = Tn × (Cm - 1) + 1
    Tn = number of threads accessing pool
    Cm = max concurrent connections per thread (typically 1)

    With 200 Tomcat threads: pool_size = 200 × (1 - 1) + 1 = 1 ???
    (This formula assumes threads release connections between queries)

    BETTER formula for web apps:
    pool_size = active_threads × avg_query_time / (avg_query_time + avg_business_logic_time)

    With 200 threads, 5ms query, 15ms business logic:
    pool_size = 200 × 5 / 20 = 50 connections

    Practical: START with 10, scale up to 50 based on load testing

  HTTP client pools per downstream: 20 connections each

File descriptors:
  Per connection: 1 fd
  Tomcat connections: up to 8192 (tune down!)
  HikariCP: 50
  HTTP clients: 4 downstreams × 20 = 80
  Log files, JAR files: ~50
  Total: up to 8372 fds

  Default ulimit -n: 1024 → MUST INCREASE
  Recommended: 65535

  Kubernetes container: nofile limit set in securityContext
```

## 12. Architecture Implications

### Designing for Performance

```
PERFORMANCE ARCHITECTURAL PATTERNS:

1. READ/WRITE SPLITTING
   ┌────────────────────────────────────────────────────┐
   │ WRITE PATH: POST/PUT/DELETE → Primary DB            │
   │   - Consistency critical                             │
   │   - Use @Transactional(readOnly = false)           │
   │   - Smaller HikariCP pool (5-10 connections)        │
   │                                                      │
   │ READ PATH: GET → Read Replica DB                    │
   │   - Can tolerate replication lag                     │
   │   - Use @Transactional(readOnly = true)             │
   │   - Larger HikariCP pool (20-50 connections)         │
   │   - Routes queries to read-replica via             │
   │     AbstractRoutingDataSource                      │
   └────────────────────────────────────────────────────┘

2. ASYNC BY DEFAULT
   ┌────────────────────────────────────────────────────┐
   │ Synchronous:                                         │
   │   Thread blocked for full request duration           │
   │   Throughput = threads / latency                     │
   │                                                      │
   │ Async (CompletableFuture):                           │
   │   Thread released during I/O                         │
   │   Higher throughput with fewer threads               │
   │                                                      │
   │ Reactive (WebFlux):                                  │
   │   Event loop, no thread-per-request                  │
   │   Maximum throughput (hardware-limited)              │
   │   BUT: debugging is harder, stack traces unusable    │
   └────────────────────────────────────────────────────┘

3. CALL PATTERN OPTIMIZATION
   ┌────────────────────────────────────────────────────┐
   │ Serial calls:                                        │
   │   inventory = getInventory(id)       // 10ms         │
   │   pricing = getPricing(id)           // 15ms         │
   │   shipping = getShipping(id)         // 8ms          │
   │   → Total: 33ms                                      │
   │                                                      │
   │ Parallel (independent calls):                        │
   │   var futures = List.of(                             │
   │       CompletableFuture.supplyAsync(() -> getInventory(id)),
   │       CompletableFuture.supplyAsync(() -> getPricing(id)),
   │       CompletableFuture.supplyAsync(() -> getShipping(id))
   │   );                                                 │
   │   CompletableFuture.allOf(futures).join();           │
   │   → Total: max(10, 15, 8) = 15ms                     │
   │   → 2.2x faster!                                     │
   └────────────────────────────────────────────────────┘
```

## 13. Team Ownership Implications

### Performance Review Process

```
PERFORMANCE GATE IN CI/CD PIPELINE:

1. Every PR must pass a performance smoke test:
   └── k6 script that hits the changed endpoints at 100 RPS for 60s
   └── p99 must stay below baseline + 10%
   └── Memory must not grow beyond baseline + 20%
   └── If it fails: PR is blocked until author optimizes or justifies

2. Weekly performance regression test:
   └── Full k6 scenario at 1000 RPS for 15 minutes
   └── Compared against last week's results
   └── Slack alert if p99 degrades > 5%

3. Monthly capacity planning:
   └── Stress test to find breaking point
   └── Extrapolate: at current growth rate, when will we hit 80% capacity?
   └── Ticket created 2 months before predicted capacity exhaustion

PERFORMANCE REVIEW CHECKLIST (PR review):
  □ New endpoint has @Timed annotation
  □ New DB query has EXPLAIN ANALYZE attached in PR description
  □ No SELECT * without pagination
  □ No new ObjectMapper() — injected singleton
  □ No BeanUtils.copyProperties() in hot path
  □ No Thread.sleep() outside test code
  □ No regex compiled per-request (static final Pattern)
  □ No String concatenation in loops
  □ HTTP client has timeout configured
  □ Cache strategy documented if new cache added
```

## 14. Interview Questions

### Question 1: "You have a Spring Boot service that handles 200 RPS at p99=500ms. The SLO requires 1000 RPS at p99=100ms. Walk me through your optimization process."

**Staff-level answer:**

**Phase 1: Establish baseline and profile (1-2 days).**
I set up a k6 load test that simulates production traffic patterns at the current 200 RPS, measuring p50/p95/p99 latency, error rate, and throughput. I attach async-profiler in CPU mode and JFR recording for the duration of the load test. The flame graph reveals: 45% of CPU in Hibernate/JDBC (database access), 25% in Jackson serialization, 15% in Spring AOP proxy chains, 10% in business logic, and 5% in everything else.

**Phase 2: Attack the biggest bottleneck — database (1-2 days).**
I export the Hibernate SQL log and find: (a) the `/api/orders` endpoint does 1 query for orders + N queries for order items (N+1 problem), (b) a reporting endpoint does `SELECT * FROM transactions` without a date filter, scanning 50M rows, (c) 5 out of 20 endpoints have no indexes on their `WHERE` columns.

Fixes: (a) Replace `findAll()` with a custom JPQL query using `LEFT JOIN FETCH items` — reduces 1+N queries to 1 query. (b) Add `WHERE created_at > :since` with a created_at index — reduces 50M row scan to 10K. (c) Add indexes on `orders.user_id`, `transactions.status`, and `products.category_id`.

Re-profile: database CPU drops from 45% to 12%. Throughput increases to 450 RPS at p99=250ms. Good progress, but 4.5x below target.

**Phase 3: Optimize serialization (1 day).**
Jackson at 25% CPU: I check `ObjectMapper` configuration. Multiple instances were being created. I configure a singleton `ObjectMapper` bean, register the `JavaTimeModule`, disable `WRITE_DATES_AS_TIMESTAMPS` (uses strings instead of long parsing), and disable `FAIL_ON_UNKNOWN_PROPERTIES` (skip unknown fields instead of throwing). For the heaviest responses, I switch from returning full entity objects to DTO projections with only needed fields.

Re-profile: serialization drops to 10% CPU. Throughput: 650 RPS, p99=150ms.

**Phase 4: Reduce proxy overhead and add caching (1 day).**
Spring AOP at 15% CPU: I audit AOP proxies. The `@Transactional` annotation is fine, but a custom logging aspect intercepts all service methods. I refactor it to use `@Observed` (Micrometer, lower overhead) and restrict it to only instrumented methods. I also find a case where `@Cacheable` wasn't used on a frequently-called, infrequently-changing reference data endpoint. Adding a Caffeine cache with `refreshAfterWrite(5min)` drops database calls for this endpoint by 98%.

Re-profile: AOP overhead drops to 5% CPU. Throughput: 900 RPS, p99=110ms.

**Phase 5: Parallelize independent calls (1 day).**
The `/api/orders/{id}/details` endpoint serially calls: inventory service (10ms), pricing service (15ms), shipping service (8ms). These are independent. I refactor to use `CompletableFuture.allOf()` to make them in parallel: `max(10, 15, 8) = 15ms` instead of `10+15+8 = 33ms`. The endpoint latency drops from 55ms to 30ms.

Final result: 1050 RPS at p99=95ms. SLO met. Remaining budget: 10% headroom.

**Phase 6: Prevent regression.**
I add the performance test to the CI pipeline. Any PR that degrades p99 by >10% is automatically blocked. I add alerts on p99 latency and HikariCP connection pool utilization. The flame graph baseline is stored for comparison in the next optimization cycle.

### Question 2: "Explain startup performance optimization for a Spring Boot application. What techniques reduce startup time from 30 seconds to 5 seconds?"

**Staff-level answer:**

Startup time is dominated by: (1) classpath scanning, (2) auto-configuration evaluation, (3) Hibernate metadata building, (4) bean creation. Optimizing in that order yields the most improvement.

**Technique 1: Reduce classpath scanning scope (biggest impact).**
By default, `@SpringBootApplication` scans from its package downward. If your application class is in `com.example` and you have 3000 classes on the classpath, all 3000 are scanned. Narrow the scan with `@SpringBootApplication(scanBasePackages = "com.example.payment")` to scan only your application code. Exclude test dependencies and libraries that don't contain Spring components: `scanBasePackageClasses = PaymentApplication.class`. This reduces scanning from seconds to milliseconds.

**Technique 2: Exclude unnecessary auto-configurations (2-5 second reduction).**
Spring Boot 3.x loads 180+ auto-configuration classes. Each one evaluates `@ConditionalOnClass`, `@ConditionalOnBean`, and `@ConditionalOnProperty`. If you don't use MongoDB, exclude it:

```java
@SpringBootApplication(exclude = {
    MongoAutoConfiguration.class,
    MongoDataAutoConfiguration.class,
    ElasticsearchDataAutoConfiguration.class,
    Neo4jAutoConfiguration.class,
    BatchAutoConfiguration.class,
    IntegrationAutoConfiguration.class,
    QuartzAutoConfiguration.class
})
```

Use `@ConditionalOnProperty(name = "app.experimental", havingValue = "false", matchIfMissing = true)` on your own auto-config classes. Run with `--debug` and check the auto-configuration report to see which classes matched and which were skipped.

**Technique 3: Lazy initialization (2-4 second reduction).**
`spring.main.lazy-initialization=true` defers bean creation until first use. The application starts fast, but the first request is slow (because beans are created on first access). This is a tradeoff — good for development, dangerous for production (first-request latency is unpredictable). Use selectively: lazy-init only non-critical beans.

**Technique 4: Hibernate optimization (3-5 second reduction).**
Hibernate scans entity classes, builds metamodel, and validates mappings at startup. Exclude unnecessary entities: `spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false` (don't validate DB schema at startup). Use `spring.jpa.open-in-view=false` (don't hold EntityManager for entire request). For even faster startup, use Spring Data JDBC instead of JPA — no metamodel, no session cache, 3-5x faster startup.

**Technique 5: Application Class Data Sharing (AppCDS) (30-50% reduction).**
Equivalent to creating a "warmed-up" class archive. Run once: `java -XX:ArchiveClassesAtExit=app-cds.jsa -jar app.jar`. Then run with: `java -XX:SharedArchiveFile=app-cds.jsa -jar app.jar`. Classes are loaded from a memory-mapped archive instead of JAR files. This reduces class loading time by 30-50%.

**Technique 6: GraalVM Native Image (10x startup improvement).**
Ahead-of-time compilation into a native executable. Startup time drops from seconds to milliseconds. But: dynamic features (reflection, proxies, serialization) must be preconfigured via reachability metadata. Spring Boot 3.x has built-in AOT support: `./mvnw spring-boot:process-aot`. The native image is built with: `./mvnw -Pnative native:compile`. Tradeoffs: longer build time, no JIT warm-up improvement, no dynamic class loading, no JMX, limited debugging.

Result: applying techniques 1-4 brings startup from 30s to ~8s. Adding AppCDS brings it to ~5s. GraalVM native image brings it to ~0.05s (50ms) but requires significant adaptation effort.

### Question 3: "How do you optimize a Spring Boot application's garbage collection for low-latency requirements (p99 < 10ms GC pause)?"

**Staff-level answer:**

For sub-10ms GC pauses, neither ParallelGC nor G1GC is sufficient. The JVM provides two low-pause collectors: Shenandoah (Oracle JDK 12+) and ZGC (JDK 11+). Both do the majority of their work concurrently with application threads, with sub-millisecond pause times.

**Step 1: Choose the right GC.**
ZGC is generally preferred for Spring Boot: it scales to multi-terabyte heaps, has < 1ms average pause time, and is the default low-pause collector in JDK 21. Enable with: `-XX:+UseZGC`. ZGC uses colored pointers (metadata stored in unused pointer bits) and load barriers to perform concurrent compaction.

**Step 2: Tune heap size correctly.**
ZGC needs enough headroom for concurrent operation. Rule of thumb: allocated heap should be at least 3× the steady-state live set. If your application uses 500MB live data, allocate at least 1.5GB: `-Xms1536m -Xmx1536m`. Set min and max equal to avoid heap resizing (resizing triggers stop-the-world pauses).

**Step 3: Control allocation rate.**
ZGC can handle high allocation rates, but allocating faster than GC can collect leads to `Allocation Stall` — the application pauses until GC frees memory. Key metrics to monitor: `jvm_gc_memory_allocated_bytes_total` (allocation rate). If allocation rate > 500MB/s, look for unnecessary allocations: (a) use primitive types instead of boxed types (avoid `Long` where `long` suffices), (b) reuse `StringBuilder` instead of `+` concatenation in hot loops, (c) use `ByteBuffer.allocateDirect()` for long-lived large buffers, (d) return DTOs with only needed fields instead of full entities.

**Step 4: Reduce humongous allocations.**
ZGC handles humongous allocations (objects > region size) differently — they go to a separate area and are collected less efficiently. Configure region size to handle your typical allocation sizes: `-XX:ZAllocationSpikeTolerance=5.0` allows 5× allocation spike before stalling.

**Step 5: Enforce pause time targets.**
`-XX:ZCollectionInterval=60` forces a GC cycle at most every 60 seconds (even if heap isn't full), preventing accumulation of work. `-XX:SoftMaxHeapSize=1g` allows ZGC to keep the heap compact at 1GB, but permits growth to `-Xmx` under pressure.

**Step 6: Full configuration example:**
```
-Xms2g -Xmx2g
-XX:+UseZGC
-XX:+ZGenerational (JDK 21+, separate young/old generations for better throughput)
-XX:ConcGCThreads=4  (number of concurrent GC threads, ~25% of CPU cores)
-XX:SoftMaxHeapSize=1536m
-XX:ZAllocationSpikeTolerance=3.0
-XX:ZCollectionInterval=300
-Xlog:gc*:file=/var/log/app/gc.log:time,level,tags:filecount=10,filesize=10m
```

With this configuration and a 2GB heap, ZGC pauses average 0.3ms (300 microseconds), with p99.9 < 1ms. This is 100-500× better than G1GC pauses for the same heap size. The cost is ~10-15% lower throughput compared to G1GC due to concurrent overhead, but for latency-sensitive applications, this is an excellent tradeoff.

## 15. Hands-On Exercises

1. **Load test with k6 and identify the bottleneck**: Write a k6 script that simulates realistic traffic to your Spring Boot application: 50 virtual users, ramping up over 2 minutes, then steady at 50 for 5 minutes, hitting `/api/orders`, `/api/orders/{id}`, `/api/products`. Run the test and observe: p95 latency, error rate, and throughput. Add `@Timed` to all controller methods and create a Grafana dashboard showing latency percentiles. Identify the slowest endpoint from the metrics dashboard. Form a hypothesis about why it's slow (query plan, N+1, serialization, missing cache). Profile the endpoint with async-profiler during load. Compare the flame graph to your hypothesis. Did the profiling data confirm or refute your hypothesis? Document the discrepancy between what you THOUGHT was slow and what the profiler SHOWED was slow.

2. **CPU profile with async-profiler and read a flame graph**: Run your application under the k6 load. Attach async-profiler: `./profiler.sh -d 60 -f /tmp/cpu.html <pid>`. Open the HTML file in a browser. Find: (a) The widest box — this is the hottest method (most CPU samples). Trace UP from it to find which application code triggers it. (b) The deepest stack trace — count the layers of proxies, interceptors, and framework code. If > 15 layers deep, proxy overhead is likely significant. (c) Any method you didn't expect to see — this is a surprise bottleneck. (d) Look for "plateaus" (wide boxes that don't narrow as you go up) — these indicate methods doing actual CPU work, not just delegating. Document the top 5 CPU consumers with their call chains, CPU percentages, and whether they're application code or framework code.

3. **Fix an N+1 query and measure the improvement**: Set `spring.jpa.show-sql=true` and `spring.jpa.properties.hibernate.format_sql=true`. Create an endpoint that loads an entity with a lazy-loaded collection (e.g., `Order` → `List<OrderItem>` with `fetch = FetchType.LAZY`). Write a test that calls the endpoint 100 times and counts the SQL queries from the log output. Replace the simple `findAll()` call with a `@Query` using `LEFT JOIN FETCH`. Verify: (a) the SQL log shows exactly 1 query instead of N+1, (b) the endpoint latency decreases (measure with `@Timed`), (c) the CPU flame graph shows less time in `HibernateResultSetProcessor` and `SqlStatementLogger`. Run both versions under JMH and report: throughput improvement (orders/second), latency improvement (p99), and memory allocation reduction (bytes allocated per invocation).

4. **Tune HikariCP and measure connection pool performance**: Configure `maximum-pool-size=5`. Write a JMeter/Gatling test that fires 20 concurrent threads at a DB-dependent endpoint. Record: p99 latency, `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`. Increase `maximum-pool-size` to 10, 15, 20, and 30, re-running the test each time with the same load. Graph: pool size vs average latency vs pending count vs timeout count. Find the knee in the curve — the pool size beyond which adding more connections doesn't reduce latency. Calculate the optimal pool size using the formula `Tn × (1 - (avgBusinessLogicTime / (avgBusinessLogicTime + avgQueryTime)))`. Compare your empirical optimal to the formula's prediction. Explain why HikariCP's default of 10 is insufficient at 20 concurrent threads with 100ms queries.

5. **Compare JSON serialization configurations with JMH**: Create an endpoint returning a moderately complex object graph (50 fields, 5 nested objects, dates, enums, collections). Benchmark serialization throughput using JMH with these configurations: (a) Default Spring Boot `ObjectMapper`, (b) `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=false` and `WRITE_DATES_AS_TIMESTAMPS=false`, (c) `ObjectMapper` with `BlackbirdModule` (replaces reflection with bytecode-generated accessors), (d) Manually constructed `ObjectMapper` with all performance flags: `DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL`, `MapperFeature.AUTO_DETECT_GETTERS=false`, `MapperFeature.AUTO_DETECT_IS_GETTERS=false`. Report for each: serialization ops/sec, allocation rate (bytes/op), and CPU usage. Which configuration gives the best throughput? What's the percentage improvement from (a) to (d)? At what throughput does the serialization overhead become significant enough to matter (> 10% of total request time)?

6. **Analyze JFR recording from a load test**: Enable JFR during a k6 load test: `-XX:StartFlightRecording:name=loadtest,settings=profile,duration=120s,filename=perf.jfr`. After the test, open the JFR file in JDK Mission Control. Answer: (a) What are the top 5 methods by CPU time? (b) What are the top 3 allocation sites? (c) How many GC pauses occurred and what was the distribution (p50, p99, max)? (d) Which SQL queries consumed the most time? (e) Which socket reads (HTTP calls) took the longest? (f) Did any thread spend > 50% of CPU time in `BLOCKED` or `WAITING` state? Generate an automated report in HTML format using the JFR event stream API. Write a Spring Boot endpoint that accepts a JFR file and returns a JSON summary of the above metrics.

7. **Compare G1GC vs ZGC for a Spring Boot workload**: Run the same load test (1000 RPS, mixed read/write) against the same application with two GC configurations: (a) G1GC: `-XX:+UseG1GC -Xms2g -Xmx2g`, (b) ZGC: `-XX:+UseZGC -Xms2g -Xmx2g`. Record for each: p50/p99/p99.9 latency, throughput (RPS sustained), GC pause time distribution, and CPU usage. Graph the latency distribution side by side. Which GC is better for this workload? At what throughput does ZGC become cost-effective despite its ~10% higher CPU overhead? Produce a GC recommendation document that explains which GC to use based on latency SLO and heap size.

8. **Startup time optimization — measure and reduce**: Measure your application's startup time with `BufferingApplicationStartup`: (a) Record a baseline by enabling `BufferingApplicationStartup` with 10,000 steps and exporting the JSON timeline. (b) Identify the top 5 startup phases by duration. (c) Narrow the `@ComponentScan` base packages — measure the time reduction. (d) Exclude 10 unused auto-configuration classes — measure the time reduction. (e) Enable `spring.main.lazy-initialization=true` — measure the time reduction (and verify with a test that first-request latency is acceptable). (f) Enable AppCDS: run with `-XX:ArchiveClassesAtExit=app-cds.jsa`, then restart with `-XX:SharedArchiveFile=app-cds.jsa` — measure the time reduction. Create a startup time budget for CI/CD: each new dependency must be justified if it adds > 100ms to startup time.

## 16. Advanced Challenges

1. **Build a continuous performance testing pipeline**: Create a GitHub Actions / Jenkins pipeline that: (a) Deploys the application to a dedicated performance test environment (using Testcontainers with a Docker Compose file that includes PostgreSQL, Redis, and mock downstream services via WireMock), (b) Runs k6 scripts simulating production traffic at 20%, 50%, 100%, and 150% of target throughput, (c) Captures from `/actuator/metrics`: p50/p95/p99 latency, error rate, CPU/memory usage, HikariCP utilization, Tomcat thread count, and GC statistics for each load level, (d) Compares ALL metrics against the previous release stored in an S3 bucket or artifact repository (regression detection using Z-score > 2), (e) Generates a performance report in markdown format with: a summary table comparing key metrics, a latency distribution chart (ASCII art or embedded image), an allocation pressure trend, and a pass/fail verdict, (f) Posts the report as a PR comment via GitHub/GitLab API, (g) Fails the build if p99 degrades by >20% or error rate increases by >1% or HikariCP timeout count > 0. The pipeline must run in < 15 minutes to not slow down development velocity.

2. **Implement a production-safe profiling library**: Create a Spring Boot starter (`profiler-spring-boot-starter`) that enables on-demand profiling in production without restarting the JVM. Expose endpoints: `POST /actuator/profiler/start?mode=cpu&duration=30s&frequency=100hz` → returns a session ID, `GET /actuator/profiler/{sessionId}/flamegraph` → returns interactive flame graph HTML, `GET /actuator/profiler/{sessionId}/jfr` → returns JFR recording file, `GET /actuator/profiler/{sessionId}/summary` → returns JSON with top-10 hot methods and their CPU percentage. The starter should: (a) Bundle async-profiler native binaries for Linux x64, Linux ARM64, and macOS ARM64 (using JNA for native code), (b) Enforce safety limits: max 120s duration, max 1 concurrent session, max 10 sessions per hour, (c) Auto-profile on alert: when p99 latency exceeds threshold × 2 for > 60s, automatically start a 30s CPU profile and store it for post-mortem analysis, (d) Encrypt and compress generated flame graphs before storing (contains stack traces which may contain business data), (e) Integrate with the observability stack: when a profile is captured during an alert, annotate the alert in AlertManager with a link to the flame graph.

3. **Build an "allocation hotspot detector" using JFR event streaming**: Create a tool that processes JFR recordings and identifies allocation hotspots — methods that create excessive objects. The tool should: (a) Parse `jdk.ObjectAllocationInNewTLAB` and `jdk.ObjectAllocationOutsideTLAB` events from the JFR file using `jdk.jfr.consumer.RecordingFile`, (b) Group allocations by stack trace hash and calculate: total bytes allocated, object count, average object size, allocation rate (bytes/sec), (c) Rank by "allocation pressure" = bytes_allocated / method_invocation_count (identifies expensive per-call allocations), (d) Detect patterns: large `String`/`byte[]` allocations (potential humongous objects causing GC issues), repeated small allocations of the same type (potential allocation sites to pool with `ObjectPool` or `ThreadLocal` reuse), `ArrayList` resizes (suggest pre-sizing with `new ArrayList<>(knownSize)`), (e) Generate an HTML report with allocation flame graphs, top-20 allocation sites with source code snippets extracted via source path analysis, and automated remediation suggestions with code examples (e.g., "Replace `new StringBuilder()` in a loop with a pre-sized builder"). The tool should process 1 hour of JFR data in < 5 minutes.

4. **Create a "Performance SLO Enforcer" middleware**: Build a Spring Boot middleware that enforces per-endpoint performance contracts. Each endpoint declares its performance contract via annotations: `@PerformanceSLO(p99ms = 100, maxConcurrent = 50, circuitBreakAfter = 5)`. The middleware: (a) Tracks per-endpoint p99 latency using a sliding window of the last 1000 requests (circular buffer for efficient O(1) percentile approximation), (b) Tracks concurrent request count using an `AtomicInteger` incremented on `doFilterInternal` entry and decremented in `finally`, (c) If p99 > contract.p99ms for 5 consecutive windows → circuit break that endpoint → return HTTP 429 Too Many Requests for all subsequent requests until 10 consecutive in-SLO windows pass, (d) If concurrent > maxConcurrent → queue excess requests (bounded queue of 100) or reject with HTTP 503 Service Unavailable if queue is full, (e) Provides `GET /performance/status` returning JSON with per-endpoint status: `{"/api/orders": {"status": "OPEN", "p99ms": 45, "concurrent": 12, "maxConcurrent": 50, "recentBreaches": 0}}`, `{"/api/reports": {"status": "HALF_OPEN", "p99ms": 150, "concurrent": 3}}`, (f) Logs every enforcement action (circuit open/close, request rejected) to a dedicated `enforcement.log` file, (g) Supports dynamic SLO updates via `PUT /performance/slo/{endpoint}` during incidents to raise/lower thresholds without restarting.

5. **Implement a "Startup Time Budget" system**: Create a Spring Boot `ApplicationStartup` listener that monitors startup phases using `BufferingApplicationStartup`: (a) At application start, records duration of each startup step: Environment preparation, context creation, `BeanFactoryPostProcessor` invocation (split into: component scanning, configuration class parsing, auto-configuration loading), bean instantiation (split into: `@PostConstruct` average time, top-5 slowest beans), web server start, runner execution, (b) Compares each step's duration against a budget defined in `startup-budget.yaml`:
```yaml
budgets:
  component_scanning: "500ms"
  auto_config_evaluation: "1s"
  bean_instantiation_total: "3s"
  hibernate_metadata: "2s"
  web_server_start: "500ms"
  total_startup: "8s"
  per_new_dependency: "100ms"
overage_action: "warn"  # or "fail" for CI/CD
```
(c) If any step exceeds its budget, log a WARNING with: the exceeded budget, actual duration, percentage over, and a link to a wiki page with optimization instructions for that specific step, (d) If total startup time exceeds `total_startup` budget AND overage_action is "fail", call `SpringApplication.exit(context, () -> 1)` to exit with code 1 (CI/CD enforcement), (e) Generate a `startup-report.json` file with full timeline data, suitable for ingestion by a CI/CD pipeline as an artifact, (f) Compare against historical startup times: store each build's startup report in a time-series database (or append to a CSV in the artifact repository), compute the week-over-week trend, and detect regressions when any step's time increases by > 20% compared to the 7-day rolling average.

6. **Build a GC Log Analyzer for Spring Boot workloads**: Create a tool that parses Java GC logs (unified JVM logging format: `-Xlog:gc*:file=gc.log`) and answers Spring-specific questions: (a) What is the allocation rate (GB/min) during steady state? Is it correlated with request rate? (b) Are there humongous allocations? If yes, which allocation sites? (Cross-reference with JFR allocation events if available.) (c) What is the average GC pause time per collector type (Young, Mixed, Full)? Is the G1 `MaxGCPauseMillis` target being met? (d) What is the "GC overhead" — the percentage of CPU time spent in GC? (e) Does the application show a "GC death spiral" pattern — Full GC becoming more frequent over time? (f) What is the recommended `-Xmx` based on the steady-state live set size + 50% headroom? The tool should output a JSON report with these answers and a recommendation for GC tuning. Bonus: integrate it into the CI/CD pipeline — a build that shows Full GC in a 10-minute load test should fail.
