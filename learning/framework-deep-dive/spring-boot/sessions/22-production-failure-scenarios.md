# Session 22: Production Failure Scenarios — Troubleshooting Runbook

## 1. Why This Topic Exists

When a Spring Boot application fails at 3 AM, the engineer on-call faces a binary outcome: the outage lasts 5 minutes or 5 hours. The difference is not intelligence or years of experience. The difference is whether they have seen this exact failure mode before, have a pre-written runbook, and know which five commands narrow the problem space from "the app is broken" to "the HikariCP connection pool is exhausted because an unindexed query on `orders.created_at` is running at 2 minutes per query during the nightly billing batch."

Production failures in Spring Boot applications fall into predictable categories. The JVM, Tomcat, HikariCP, Spring's own container, and the underlying OS each have documented failure modes with known symptoms. The Staff engineer who has catalogued these failures — who can look at a 502 spike on the load balancer and immediately check: (1) Tomcat worker thread count, (2) HikariCP active connections, (3) downstream latency p99, and (4) GC pause time — will find root cause in minutes. The Staff engineer who starts by reading stack traces will be reading for hours.

**Staff engineer insight**: Production failures are not mysteries to be solved by intuition. They are deterministic system behaviors triggered by resource constraints, timing interactions, or configuration errors. The troubleshooting methodology is: (1) Observe symptoms, (2) Narrow the blast radius, (3) Hypothesize root cause from a catalogue of known failure modes, (4) Validate hypothesis with targeted metrics/logs, (5) Apply mitigation, (6) Root-cause permanently. This session builds the catalogue and the methodology.

Consider these real-world statistics from Spring Boot production incidents across 200+ services:
- 34% of incidents: Thread pool exhaustion (Tomcat worker pool or HikariCP)
- 22% of incidents: Memory pressure (heap exhaustion, Metaspace OOM, off-heap leak)
- 18% of incidents: Cascading failure from downstream degradation
- 12% of incidents: Database deadlocks or lock contention
- 8% of incidents: Startup failures (port conflicts, health check timeouts)
- 6% of incidents: Resource exhaustion (file descriptors, disk space, open sockets)

Every category above is predictable and preventable. This session covers all of them.

## 2. Mental Model

The layered failure model for Spring Boot applications:

```
LAYER 0: OS / Network
  Failures: port already in use, socket exhaustion, DNS resolution failure, network partition
  Symptoms: Connection refused, timeout, UnknownHostException

    |
    v
LAYER 1: JVM
  Failures: OOM (heap, Metaspace, direct memory), GC death spiral, thread count limit, CPU saturation
  Symptoms: Full GC every 2s, RSS climbing, OutOfMemoryError, thread creation failure

    |
    v
LAYER 2: Embedded Server (Tomcat / Netty)
  Failures: Worker thread exhaustion, acceptor backlog overflow, keep-alive connection bloat, request body too large
  Symptoms: 503 Service Unavailable, 502 Bad Gateway from LB, increasing response latency

    |
    v
LAYER 3: Spring Container
  Failures: Bean creation failure, circular dependency, @Async pool exhaustion, @Scheduled overlap, AOP proxy depth
  Symptoms: ApplicationContext startup failure, @Async tasks silently dropped, scheduled tasks never run

    |
    v
LAYER 4: Application Infrastructure (HikariCP, Cache, HTTP Client)
  Failures: Connection pool exhaustion, cache stampede, response time creep, connection leak
  Symptoms: "Connection is not available", degraded throughput, cascading timeouts

    |
    v
LAYER 5: Business Logic
  Failures: Optimistic lock failure, dual writes, lost updates, phantom reads, data corruption
  Symptoms: Stale data, duplicate records, inconsistent state between services

    |
    v
LAYER 6: External Dependencies
  Failures: Downstream timeout, circuit breaker open, rate limit hit, TLS handshake failure
  Symptoms: TimeoutException, 429 Too Many Requests, SSLHandshakeException
```

**The cascading failure pattern** — the most dangerous failure mode — follows a consistent trajectory:

```
Trigger event:
  External service p99 latency increases from 50ms to 5s
  |
  v
T+0s:
  Application threads waiting for downstream response accumulate
  |
  v
T+30s:
  Tomcat worker pool (max 200 threads) saturates
  New requests queue in acceptCount backlog (100)
  |
  v
T+31s:
  Health check requests from load balancer also wait in queue
  Health check times out at LB (configured for 5s timeout)
  |
  v
T+36s:
  Load balancer marks ALL instances as unhealthy
  All traffic cut off — even to services that could respond instantly
  |
  v
T+40s:
  HikariCP connections held by threads blocked on downstream calls
  Connection pool exhausted — even local DB queries fail
  |
  v
T+60s:
  Requests queued in HikariCP timeout -> SQLException "Connection is not available"
  Cascading errors propagate through ALL endpoints
  |
  v
T+120s:
  Complete service outage
  All instances serve 502/503
```

The mental model for troubleshooting is: **"What resource is saturated?"** Every production failure in a Spring Boot application is fundamentally a resource saturation problem: threads, connections, memory, file descriptors, CPU, or disk. Find the saturated resource, find the consumer, and you have root cause.

## 3. Internal Architecture

### Thread Pool Architecture in Spring Boot

```
┌──────────────────────────────────────────────────────────────────┐
│                     SPRING BOOT THREAD POOLS                       │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ TOMCAT WORKER POOL                                            │ │
│  │  server.tomcat.threads.max=200                                │ │
│  │  server.tomcat.threads.min-spare=10                           │ │
│  │  server.tomcat.accept-count=100                               │ │
│  │  server.tomcat.max-connections=8192                           │ │
│  │                                                               │ │
│  │  Queue: SynchronousQueue (no queueing, direct handoff)        │ │
│  │  Rejection Policy: Queue full -> Acceptor pauses              │ │
│  │  Blocking: Worker threads block on I/O (traditional)          │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ HIKARICP CONNECTION POOL                                      │ │
│  │  spring.datasource.hikari.maximum-pool-size=10 (default)      │ │
│  │  spring.datasource.hikari.minimum-idle=10                     │ │
│  │  spring.datasource.hikari.connection-timeout=30000            │ │
│  │  spring.datasource.hikari.idle-timeout=600000                 │ │
│  │  spring.datasource.hikari.max-lifetime=1800000                │ │
│  │                                                               │ │
│  │  Queue: FastList (concurrent bag of connections)              │ │
│  │  Timeout behavior: SQLException "Connection is not available" │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ @ASYNC TASK EXECUTOR                                          │ │
│  │  spring.task.execution.pool.core-size=8                       │ │
│  │  spring.task.execution.pool.max-size=Integer.MAX_VALUE (!)    │ │
│  │  spring.task.execution.pool.queue-capacity=Integer.MAX_VALUE  │ │
│  │                                                               │ │
│  │  Queue: LinkedBlockingQueue (unbounded by default!)           │ │
│  │  Danger: Unbounded queue can cause OOM                        │ │
│  │  Rejection Policy: AbortPolicy (throws exception)             │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ @SCHEDULED TASK SCHEDULER                                      │ │
│  │  spring.task.scheduling.pool.size=1 (DEFAULT — SINGLE THREAD) │ │
│  │                                                               │ │
│  │  Danger: Overlapping executions queue up indefinitely         │ │
│  │  Symptom: One slow @Scheduled blocks ALL others               │ │
│  └──────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

### HikariCP Internal Connection Lifecycle

```java
// Source: com.zaxxer.hikari.pool.HikariPool
// The connection borrow path (simplified):

public Connection getConnection(final long hardTimeout) throws SQLException {
    long startTime = System.currentTimeMillis();

    try {
        long timeout = hardTimeout; // connection-timeout (30s default)

        do {
            // Step 1: Try to borrow from concurrent bag
            PoolEntry poolEntry = connectionBag.borrow(timeout, MILLISECONDS);

            if (poolEntry == null) {
                // Bag is empty — no connections available
                break; // fall through to timeout handling
            }

            // Step 2: Validate the connection
            final long now = System.currentTimeMillis();
            if (poolEntry.isMarkedEvicted() ||
                (elapsedMillis(poolEntry.lastAccessed, now) > ALIVE_BYPASS_WINDOW_MS
                 && !isConnectionAlive(poolEntry.connection))) {
                closeConnection(poolEntry, "connection evicted or dead");
                timeout = hardTimeout - elapsedMillis(startTime);
                continue;
            }

            // Step 3: Check maxLifetime
            if (elapsedMillis(poolEntry.created, now) > maxLifetime) {
                // Connection is too old — retire it
                softEvictConnection(poolEntry, "maxLifetime exceeded", false);
                timeout = hardTimeout - elapsedMillis(startTime);
                continue;
            }

            // Step 4: Return valid, alive connection
            return poolEntry.createProxyConnection(leakTaskFactory.schedule(poolEntry), now);
        }
        while (timeout > 0L);

        // Timed out waiting for a connection
        throw new SQLException(
            String.format("Connection is not available, request timed out after %dms.",
                elapsedMillis(startTime)));
    }
    catch (InterruptedException e) {
        throw new SQLException("Interrupted during connection acquisition", e);
    }
}
```

### Graceful Shutdown Architecture

```java
// Source: org.springframework.boot.web.embedded.tomcat.GracefulShutdown
// Spring Boot 2.3+ graceful shutdown:

public class GracefulShutdown implements TomcatConnectorCustomizer {

    private volatile boolean shuttingDown = false;

    @Override
    public void customize(Connector connector) {
        if (connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol) {
            // Step 1: Pause the acceptor — stop accepting new connections
            protocol.pause();  // No new connections accepted after this point
        }
    }

    public boolean abort() throws InterruptedException {
        // Step 2: Wait for in-flight requests to complete
        // server.shutdown.grace-period=30s (default)
        // After this period, force-close remaining connections
        return awaitShutdownComplete();
    }
}

// Actual lifecycle in TomcatWebServer:
// 1. ContextClosedEvent fires
// 2. GracefulShutdown.pause() — stop accepting connections
// 3. Wait `grace-period` for active requests to complete
// 4. GracefulShutdown.abort() — force-close remaining connections
// 5. Destroy beans in @PreDestroy order
// 6. Close ApplicationContext
// 7. Shutdown HikariCP
// 8. Shutdown executor services
```

### Kubernetes SIGTERM Handling

```
Kubernetes pod termination sequence:
  T+0s      kubectl delete pod or rolling update
  T+1s      kubelet sends SIGTERM to PID 1 in container
  T+2s      JVM receives SIGTERM
  T+3s      Spring's ApplicationShutdownHook (Runtime.getRuntime().addShutdownHook)
            triggers ContextClosedEvent
  T+4s      GracefulShutdown pauses Tomcat connector
  T+5s      Health endpoint starts returning OUT_OF_SERVICE
  T+6s      kubelet removes pod from Service endpoints
  T+10s     Graceful period (server.shutdown.grace-period) expires
            — Spring force-closes remaining connections
  T+11s     Bean destruction (@PreDestroy methods fire)
  T+12s     ApplicationContext.close()
  T+13s     HikariCP connections closed
  T+14s     JVM shuts down
  T+30s     IF JVM hasn't exited by T+30: kubelet sends SIGKILL
            — Force kill, no cleanup, in-flight requests dropped
```

The critical insight: **The gap between T+5s (health endpoint returns OUT_OF_SERVICE) and T+6s (kubelet removes the pod from endpoints) is a race condition.** During second 5-6, the pod still receives traffic but reports unhealthy. The solution is a preStop hook that sleeps longer than the endpoint propagation delay:

```yaml
lifecycle:
  preStop:
    exec:
      command: ["/bin/sh", "-c", "sleep 15"]  # Wait for endpoint propagation
```

## 4. Runtime Behavior

### Thread Pool Exhaustion: Tomcat Worker Starvation

**Runtime behavior at different load levels:**

```
Load: 50 concurrent requests, avg latency 100ms
  → Tomcat workers active: 50
  → Workers idle: 150
  → Queue depth: 0
  → Status: HEALTHY

Load: 200 concurrent requests, avg latency 100ms
  → Tomcat workers active: 200
  → Workers idle: 0
  → Queue depth: 0
  → Status: SATURATED (but still serving)

Load: 300 concurrent requests, avg latency 100ms
  → Tomcat workers active: 200 (maximum)
  → Accept backlog: 100 (fills up)
  → New connections: REJECTED (Connection refused)
  → Status: DEGRADED

Load: 200 concurrent requests, avg latency 5000ms (downstream slow)
  → Tomcat workers active: 200 (all blocked)
  → Workers idle: 0
  → Accept backlog: 100 (fills up)
  → Health check requests: REJECTED (can't be accepted)
  → Load balancer: MARKS INSTANCE UNHEALTHY
  → Status: FAILING
```

**Why the Tomcat default of max-connections=8192 is misleading:**

```java
// Tomcat accepts up to 8192 connections BUT only 200 can be processed
// The remaining 7992 connections sit in a keep-alive or waiting state

// In the NIO connector, connections are tracked in a ConcurrentHashMap
// Key = SocketChannel, Value = Connection state (OPEN, READING, WRITING, CLOSE)

// A connection that is accepted but not yet being read (waiting for poller)
// still consumes: a SocketChannel (file descriptor), a PollerEvent object,
// memory in the selector key set.

// At 8192 connections, even with zero request processing:
// File descriptors: 8192 (toward the default 1048576 limit)
// Memory: ~80MB (socket buffers + channel objects)
// This is why max-connections should be tuned, not left at default.
```

### HikariCP Connection Pool Exhaustion Sequence

```
T+0ms     Request arrives, needs DB connection
T+1ms     HikariCP.hikariPool.borrow(): bag has 10 connections, all active
T+2ms     Waits in queue for a connection to be returned
T+500ms   Connection 3 becomes idle, returned to pool
T+501ms   Borrow succeeds, connection 3 assigned to waiting thread
          → Total wait: 500ms

— Alternative scenario: all connections blocked on slow query —

T+0ms     Request arrives, needs DB connection
T+1ms     All 10 connections active, blocked on 30s slow query
T+30000ms connection-timeout expires
T+30001ms SQLException: "Connection is not available, request timed out after 30000ms."
          → 50 requests queue behind this one, ALL will time out
```

### Memory Leak Progression

```
ClassLoader leak via DevTools redeploy:
  T+0h     App starts: MetaSpace=80MB, Heap=200MB
  T+1h     50 redeploys: MetaSpace=200MB, Heap=250MB
  T+2h     100 redeploys: MetaSpace=350MB, Heap=300MB
  T+4h     200 redeploys: MetaSpace=600MB, Heap=400MB
  T+6h     MetaSpace OOM: "OutOfMemoryError: Metaspace"
           → Redeploy impossible, restart required

ThreadLocal leak via MDC.put() without MDC.clear():
  T+0h     Heap: 200MB, ThreadLocals: 100 entries
  T+1h     ThreadLocals: 50,000 entries (accumulated per request)
  T+2h     Heap: 800MB, GC frequency increases
  T+3h     Heap: 1.5GB, Full GC every 30s (GC death spiral)
  T+4h     OOM: "OutOfMemoryError: Java heap space"
           → ThreadLocals in 200 threads × hundreds of accumulated values
```

## 5. Request Flow Diagrams

### Failure Flow: Cascading Downstream Timeout

```
Client
  |
  v
Load Balancer (health check: GET /actuator/health, timeout 5s, interval 10s)
  |
  v
+------------------------------------------------------------------+
| INSTANCE-1 (HEALTHY)                                             |
|                                                                   |
|  GET /api/orders                                                  |
|  |                                                                |
|  v                                                                |
|  Tomcat Worker Thread-42 (from pool of 200)                       |
|  |                                                                |
|  v                                                                |
|  OrderController.getOrders()                                      |
|  |                                                                |
|  v                                                                |
|  OrderService.findAll()                                           |
|  |                                                                |
|  v                                                                |
|  HikariCP Connection-7 (from pool of 10)                          |
|  |  SELECT * FROM orders WHERE ...    (10ms, fast)                |
|  v                                                                |
|  List<Order> orderList                                            |
|  |                                                                |
|  v                                                                |
|  FOR EACH order: call downstream INVENTORY SERVICE                 |
|  |                                                                |
|  |  RestClient.get("http://inventory-service/api/stock/{id}")     |
|  |  |                                                             |
|  |  v                                                             |
|  |  HTTP Connection to inventory-service                          |
|  |  |  ---- REQUEST SENT ----                                     |
|  |  |  ---- WAITING ----                                          |
|  |  |  ---- WAITING ----   ← INVENTORY SERVICE IS SLOW (p99=5s)  |
|  |  |  ---- WAITING ----                                         |
|  |  v                                                             |
|  |  Response: 200 OK  {stock: 42}                                 |
|  |  Time elapsed: 5000ms                                          |
|  |                                                                |
|  v  (loop for 20 orders)                                          |
|                                                                   |
|  20 orders × 5000ms = 100s total — BUT we only have 30s timeout   |
|                                                                   |
|  ┌─────────────────────────────────────────────────────────────┐  |
|  │ FAILURE CASCADE:                                             │  |
|  │                                                              │  |
|  │ Worker-42 still processing (blocked on downstream)           │  |
|  │ Worker-43 also blocked on downstream                         │  |
|  │ Worker-44 also blocked on downstream                         │  |
|  │ ...                                                          │  |
|  │ Worker-241: ALL 200 workers blocked on downstream            │  |
|  │                                                              │  |
|  │ New request arrives → No worker available → Queued            │  |
|  │ accept-count=100 fills up → New connections REFUSED          │  |
|  │ Health check connection REFUSED → LB marks UNHEALTHY         │  |
|  │ ALL instances eventually unhealthy → COMPLETE OUTAGE          │  |
|  └─────────────────────────────────────────────────────────────┘  |
+------------------------------------------------------------------+

OTHER INSTANCES: Same cascade, domino effect
```

### Failure Flow: Database Deadlock Between Two Transactions

```
TRANSACTION A (UserService.processOrder):
  T+0ms    BEGIN
  T+1ms    UPDATE orders SET status='PROCESSING' WHERE id=12345
              → Acquires ROW EXCLUSIVE LOCK on orders:12345
  T+2ms    -- Business logic, validation, 5ms computation --
  T+7ms    UPDATE inventory SET quantity = quantity - 1 WHERE product_id=789
              → WAITING for lock on inventory:789
              → HELD by Transaction B


TRANSACTION B (InventoryService.reserveStock):
  T+0ms    BEGIN
  T+1ms    UPDATE inventory SET reserved = reserved + 1 WHERE product_id=789
              → Acquires ROW EXCLUSIVE LOCK on inventory:789
  T+2ms    -- Validation, check against max stock --
  T+7ms    UPDATE orders SET status='INVENTORY_RESERVED' WHERE id=12345
              → WAITING for lock on orders:12345
              → HELD by Transaction A

              ╔══════════════════════════════════════════╗
              ║   DEADLOCK DETECTED BY POSTGRESQL        ║
              ║                                          ║
              ║   PostgreSQL deadlock_timeout=1s          ║
              ║   After 1s, deadlock detector runs        ║
              ║   Detects cycle: A→B→A                    ║
              ║   Chooses victim: Transaction B           ║
              ║                                          ║
              ║   Transaction B: ROLLBACK                 ║
              ║   Error: "deadlock detected"              ║
              ║   Transaction A: proceeds normally        ║
              ╚══════════════════════════════════════════╝


IMPACT ON APPLICATION:
  InventoryService.reserveStock() receives:
    org.springframework.dao.DeadlockLoserDataAccessException
      → Wrapped from: org.postgresql.util.PSQLException:
        ERROR: deadlock detected

  Application-level consequence:
    Inventory NOT reserved, but order.status='PROCESSING'
    → Inconsistent state: order processing but no inventory held
```

## 6. Lifecycle Diagrams

### Pod Startup Failure Lifecycle

```
STATE: PENDING
  └── Pod scheduled, waiting for container image pull

STATE: INITIALIZING
  └── Init containers run (if any)

STATE: STARTING
  │
  ├─[0s]── JVM bootstrap
  │   └── -Xmx, -Xms, GC flags parsed
  │   └── System classloader loads rt.jar, java.base module
  │
  ├─[2s]── SpringApplication.run()
  │   └── SpringApplicationBuilder.configure()
  │   └── ApplicationContext prepared
  │   └── Environment prepared
  │   └── Banner printed
  │
  ├─[4s]── Auto-configuration evaluation
  │   └── 180+ auto-config classes evaluated
  │   └── ConditionEvaluationReport built
  │   └── FAILURE POINT: @ConditionalOnClass not met → silent skip (expected)
  │   └── FAILURE POINT: @ConditionalOnBean → circular reference → startup failure
  │
  ├─[6s]── Bean creation
  │   └── @Configuration classes processed
  │   └── @Bean methods invoked
  │   └── @Component classes instantiated
  │   └── Dependency injection resolved
  │   └── FAILURE POINT: Missing dependency → NoSuchBeanDefinitionException
  │   └── FAILURE POINT: Circular dependency → BeanCurrentlyInCreationException
  │   └── FAILURE POINT: @PostConstruct throws → BeanCreationException
  │
  ├─[8s]── Web server initialization
  │   └── TomcatServletWebServerFactory.getWebServer()
  │   └── Connector binds to port 8080
  │   └── FAILURE POINT: Port in use → PortInUseException
  │   └── FAILURE POINT: Insufficient permissions → BindException (port < 1024)
  │
  ├─[10s]── DispatcherServlet initialization
  │   └── HandlerMapping beans registered
  │   └── HandlerAdapter beans registered
  │   └── ExceptionResolver beans registered
  │
  ├─[12s]── Health check endpoints registered
  │   └── /actuator/health → Status: DOWN (not yet ready)
  │   └── /actuator/health/readiness → Status: DOWN
  │   └── /actuator/health/liveness → Status: DOWN
  │
  ├─[14s]── ApplicationRunner / CommandLineRunner executed
  │   └── FAILURE POINT: Database migration fails → startup abort
  │   └── FAILURE POINT: External service unavailable → startup abort
  │
  └─[15s]── Application STARTED
      │
      └── SpringApplication.run() returns
          └── ContextRefreshedEvent published
          └── ApplicationReadyEvent published
          └── Health indicators flip to UP

KUBERNETES PROBES (in parallel):
  │
  ├─[0s]── Liveness probe (initialDelaySeconds=30)
  │   └── GET /actuator/health/liveness
  │   └── Expected: 200 OK
  │
  ├─[0s]── Readiness probe (initialDelaySeconds=15)
  │   └── GET /actuator/health/readiness
  │   └── Expected: 200 OK
  │   └── FAILURE POINT: Health check times out (> probe timeout)

FAILURE: If readiness probe fails after failureThreshold * periodSeconds:
  → Pod marked "Unready" → removed from Service endpoints → no traffic
  → Pod remains running but receives zero traffic
  → If liveness probe also fails → Pod restarted (kubelet kills container)
```

### Graceful Shutdown Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│                    GRACEFUL SHUTDOWN LIFECYCLE                    │
│                                                                   │
│  T+0.0s  Kubernetes sends SIGTERM to process                      │
│          │                                                        │
│  T+0.1s  JVM ShutdownHook thread starts                           │
│          │                                                        │
│  T+0.2s  Spring publishes ContextClosedEvent                      │
│          │                                                        │
│  T+0.3s  GracefulShutdown bean receives ContextClosedEvent        │
│          │  → Tomcat connector paused (no new connections)        │
│          │  → /actuator/health returns OUT_OF_SERVICE             │
│          │                                                        │
│  T+0.4s  Application processes in-flight requests                 │
│          │  → Active HTTP requests complete (up to grace-period)  │
│          │  → Waiting for response to be fully written            │
│          │  → @Async tasks given time to complete                 │
│          │                                                        │
│  T+30.4s Grace period expires (server.shutdown.grace-period)      │
│          │  → Remaining in-flight connections force-closed        │
│          │  → Embedded server stopped                             │
│          │                                                        │
│  T+30.5s @PreDestroy methods invoked                              │
│          │  → ORDER: Highest @Priority/@Order → last-called       │
│          │  → ISSUE: If @PreDestroy in A depends on @PreDestroy  │
│          │    in B, ordering matters. Wrong order → NPE on close  │
│          │  → Typical: close HTTP clients, then close thread pools│
│          │    then close DataSource, then close caches            │
│          │                                                        │
│  T+30.8s SmartLifecycle beans stopped                             │
│          │  → stop() called in reverse dependency order           │
│          │                                                        │
│  T+31.0s HikariCP DataSource closed                               │
│          │  → All connections returned and closed                 │
│          │  → FAILURE: If connections borrowed and never returned │
│          │    (leaked), close hangs waiting for them              │
│          │                                                        │
│  T+31.5s ExecutorServices shutdown                                │
│          │  → shutdown() called → no new tasks accepted           │
│          │  → awaitTermination(30s) → wait for running tasks      │
│          │                                                        │
│  T+32.0s ApplicationContext.close() completes                     │
│          │                                                        │
│  T+45.0s IF process not exited by now:                            │
│          │  Kubernetes sends SIGKILL (terminationGracePeriodSeconds)
│          │  → Force kill, no cleanup, connections dropped          │
│          └─────────────────────────────────────────────────────────┘
```

## 7. Source Code Reading Guide

### Key Source Files for Troubleshooting

```
Tomcat Integration:
  ✅ org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
     └── getWebServer(), customizeConnector(), prepareContext()
     └── Where: spring-boot-autoconfigure

  ✅ org.springframework.boot.web.embedded.tomcat.TomcatWebServer
     └── initialize(), start(), stop(), shutDown()
     └── Where: spring-boot

  ✅ org.apache.coyote.http11.Http11NioProtocol
     └── Constructor creates NioEndpoint
     └── Where: embedded in tomcat-embed-core

  ✅ org.apache.tomcat.util.net.NioEndpoint
     └── Acceptor, Poller inner classes
     └── bind(), startInternal(), processSocket()
     └── Where: embedded in tomcat-embed-core

HikariCP Integration:
  ✅ com.zaxxer.hikari.HikariDataSource
     └── getConnection(), close()
     └── Where: HikariCP JAR

  ✅ com.zaxxer.hikari.pool.HikariPool
     └── getConnection(long hardTimeout), recycle()
     └── Where: HikariCP JAR

  ✅ com.zaxxer.hikari.util.ConcurrentBag
     └── borrow(), requite(), reserve()
     └── The lock-free data structure that manages connections
     └── Where: HikariCP JAR

Graceful Shutdown:
  ✅ org.springframework.boot.web.embedded.tomcat.GracefulShutdown
     └── customize(), abort(), awaitShutdownComplete()
     └── Where: spring-boot

  ✅ org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory
     └── setShutdown(Shutdown.GRACEFUL)
     └── Where: spring-boot-autoconfigure

Health Check:
  ✅ org.springframework.boot.actuate.health.HealthEndpoint
     └── health(), healthForPath()
     └── Where: spring-boot-actuator

  ✅ org.springframework.boot.actuate.health.HealthIndicator
     └── health() — implemented by DataSource, Redis, RabbitMQ, etc.
     └── Where: spring-boot-actuator

Memory Diagnostics:
  ✅ java.lang.management.MemoryMXBean (JDK)
     └── getHeapMemoryUsage(), getNonHeapMemoryUsage()
     └── Programmatic access to heap/Metaspace usage

  ✅ java.lang.management.ThreadMXBean (JDK)
     └── findDeadlockedThreads(), getThreadInfo()
     └── Detecting JVM-level deadlocks
```

### Reading Order for a New Spring Boot Engineer Learning Troubleshooting:
1. `TomcatServletWebServerFactory.getWebServer()` — understand how Tomcat is embedded
2. `NioEndpoint.bind()` and inner class `Acceptor` — understand connection acceptance
3. `DispatcherServlet.doDispatch()` — understand request processing entry
4. `HikariPool.getConnection()` — understand connection borrow path
5. `AbstractApplicationContext.close()` — understand shutdown sequence
6. `GracefulShutdown.customize()` — understand graceful shutdown

## 8. Production Failure Scenarios

### Scenario 1: Tomcat Worker Thread Exhaustion

**Symptom:**
- Load balancer reports: "Instance 10.0.1.15: 5 consecutive health check failures"
- Application logs show no errors for the past 2 minutes
- 502/503 errors only at the load balancer level
- Metrics: `tomcat_threads_busy_threads = 200`, `tomcat_threads_config_max_threads = 200`

**Investigation (exact commands):**

```bash
# Step 1: Check if the process is even running
kubectl get pods -n payment | grep payment-api
kubectl exec -n payment payment-api-7d4f8-abc123 -- ps aux | grep java

# Step 2: Get thread dump to see what all 200 threads are doing
kubectl exec -n payment payment-api-7d4f8-abc123 -- \
  jcmd $(pgrep -f payment-api) Thread.print > threaddump.txt

# Alternative via actuator (requires configuration):
curl http://10.0.1.15:8080/actuator/threaddump | jq '.threads[] | {name, threadState, blockedCount}'

# Step 3: Analyze thread dump — look for BLOCKED or RUNNABLE threads doing I/O
grep -A 5 "BLOCKED" threaddump.txt
grep -c "SocketInputStream.socketRead" threaddump.txt

# If 190+ threads are in "SocketInputStream.socketRead" or "socketRead0":
# → They are waiting for downstream HTTP responses

# Step 4: Confirm with metrics endpoint
curl http://10.0.1.15:8080/actuator/metrics/tomcat.threads.busy
# Returns: {"name":"tomcat.threads.busy","measurements":[{"value":200.0}]}
```

**Root Cause:** Downstream inventory service had a degraded database node, causing p99 latency to spike from 50ms to 8s. The payment service made 5 downstream HTTP calls per request, each blocking a Tomcat worker thread. With 50 concurrent requests, 50 × 5 = 250 threads needed, but only 200 available. Threads accumulated, pool exhausted, health checks blocked because they also needed a Tomcat thread.

**Immediate Fix:**
```bash
# Option A: Scale up temporarily (more pods = more total threads)
kubectl scale deployment payment-api --replicas=5 -n payment

# Option B: Restart affected pods to reset thread pools
kubectl rollout restart deployment payment-api -n payment

# Option C (if you have circuit breaker in place):
# Manually open the circuit breaker to stop calls to degraded downstream
curl -X POST http://10.0.1.15:8080/actuator/circuitbreakers/inventoryService/open
```

**Permanent Fix:**
```java
// 1. Add timeout to all HTTP client calls
@Bean
public RestClient restClient(RestClient.Builder builder) {
    return builder
        .requestFactory(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))     // Connection timeout
                .build()))
        .defaultHeader("Connection", "keep-alive")
        .build();
}

// 2. Use non-blocking HTTP client (WebClient) for downstream calls
// so that Tomcat workers are not blocked during I/O
WebClient webClient = WebClient.builder()
    .baseUrl("http://inventory-service")
    .build();

Mono<InventoryResponse> inventory = webClient.get()
    .uri("/api/stock/{id}", productId)
    .retrieve()
    .bodyToMono(InventoryResponse.class)
    .timeout(Duration.ofSeconds(5))        // Per-request timeout
    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)));

// 3. Add Resilience4j Circuit Breaker
@CircuitBreaker(name = "inventoryService", fallbackMethod = "inventoryFallback")
public InventoryResponse getInventory(String productId) { ... }

public InventoryResponse inventoryFallback(String productId, Exception e) {
    return InventoryResponse.fromCache(productId); // Fallback to stale cache
}

// 4. Configure Tomcat with more headroom
// But NOT as the primary fix! More threads mask the root cause.
server.tomcat.threads.max=400      # Increase from default 200
server.tomcat.threads.min-spare=20 # Pre-warm threads
server.tomcat.accept-count=200    # Increase accept backlog

// 5. Configure circuit breaker timeout < downstream timeout < Tomcat accept timeout
// Order matters: circuit breaks before threads are consumed
resilience4j.circuitbreaker.configs.default.slowCallDurationThreshold=2000ms
resilience4j.timelimiter.configs.default.timeoutDuration=3000ms
```

**Prevention:**
1. Require circuit breaker on ALL cross-service HTTP calls
2. Set per-request timeouts on every HTTP client
3. Alert when `tomcat_threads_busy / tomcat_threads_config_max > 0.8`
4. Load test with degraded downstream to verify circuit opens before thread pools exhaust
5. Chaos engineering: inject latency into downstream and verify system degrades gracefully

---

### Scenario 2: HikariCP Connection Pool Exhaustion

**Symptom:**
- Application logs: `java.sql.SQLException: HikariPool-1 - Connection is not available, request timed out after 30000ms.`
- All endpoints that touch the database return 500
- Metrics: `hikaricp_connections_active = 10`, `hikaricp_connections_max = 10`, `hikaricp_connections_pending = 47`

**Investigation (exact commands):**

```bash
# Step 1: Check HikariCP pool state via actuator
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending
curl http://localhost:8080/actuator/metrics/hikaricp.connections.timeout

# Step 2: Check for long-running queries
# In PostgreSQL:
SELECT pid, now() - pg_stat_activity.query_start AS duration,
       query, state
FROM pg_stat_activity
WHERE state != 'idle'
  AND pid <> pg_backend_pid()
ORDER BY duration DESC;

# This shows: 10 queries running for 5+ minutes each
# All from the same application host
# One query in particular: SELECT * FROM orders WHERE created_at > '2024-01-01'

# Step 3: Explain the slow query
EXPLAIN ANALYZE
SELECT * FROM orders WHERE created_at > '2024-01-01';
# Seq Scan on orders (cost=0.00..25432.00 rows=1200000 width=356)
# → No index on created_at → full table scan of 1.2M rows

# Step 4: Check which code path issues these queries
# Enable HikariCP leak detection (development only):
spring.datasource.hikari.leak-detection-threshold=30000  # 30s

# Log output:
# "HikariPool-1 - Connection leak detection triggered for conn67,
#  stack trace follows: java.lang.Exception: Apparent connection leak detected
#    at com.example.OrderService.findOrdersSlow(OrderService.java:42)
#    at com.example.OrderController.listOrders(OrderController.java:28)"
```

**Root Cause:** A reporting endpoint `GET /api/orders/report` issued an unindexed query `SELECT * FROM orders WHERE created_at > ?` that scanned 1.2M rows, taking 5+ minutes per execution. The endpoint was called by the nightly billing batch job with 10 concurrent threads. All 10 HikariCP connections were consumed by these 10 × 5-minute queries. All other endpoints that needed a database connection waited in the pool queue and timed out after 30s.

**Immediate Fix:**
```sql
-- Emergency index creation (but careful: blocks writes on the table)
CREATE INDEX CONCURRENTLY idx_orders_created_at ON orders(created_at);
-- CONCURRENTLY prevents table lock, but takes longer

-- Kill long-running queries to free connections (urgent)
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE state = 'active'
  AND query LIKE '%orders WHERE created_at%'
  AND query_start < now() - interval '1 minute';
```

**Permanent Fix:**
```java
// 1. Add index (migration)
// V2__add_orders_created_at_index.sql:
CREATE INDEX idx_orders_created_at ON orders(created_at);

// 2. Add query timeout to prevent any single query from consuming
//    a connection for too long
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "10000"))
    @Query("SELECT o FROM Order o WHERE o.createdAt > :since")
    List<Order> findRecentOrders(@Param("since") LocalDateTime since, Pageable pageable);
}

// 3. Configure global query timeout via Hikari
spring.datasource.hikari.connection-timeout=10000   # 10s max wait for connection
spring.datasource.hikari.idle-timeout=300000         # 5 min, then recycle
spring.datasource.hikari.max-lifetime=600000         # 10 min max connection age

// 4. At database level (PostgreSQL):
// ALTER ROLE payment_app SET statement_timeout = '30s';

// 5. Paginate large result sets — NEVER SELECT * without LIMIT
@Query("SELECT o FROM Order o WHERE o.createdAt > :since ORDER BY o.createdAt")
List<Order> findRecentOrders(@Param("since") LocalDateTime since, Pageable pageable);

// Caller must always use pagination:
Page<Order> page = orderRepository.findRecentOrders(since, PageRequest.of(0, 100));

// 6. Separate read/write pools: batch jobs use a dedicated DataSource
@Primary
@Bean("writeDataSource")
@ConfigurationProperties("spring.datasource.write")
public DataSource writeDataSource() { ... }

@Bean("readDataSource")
@ConfigurationProperties("spring.datasource.read")
public DataSource readDataSource() { ... }

// 7. Use connection pool sizing formula:
// pool_size = Tn * (Cm - 1) + 1
// Where:
//   Tn = max thread count accessing the pool
//   Cm = max concurrent connections a single thread holds
//
// For a typical web app: Tn = Tomcat max threads (200), Cm = 1
// pool_size = 200 * (1 - 1) + 1 = 1??? NO — this formula is for optimal throughput
//
// Practical formula for Spring Boot:
// pool_size = ((core_count * 2) + effective_spindle_count)
// For an 8-core server with a fast SSD:
// pool_size = (8 * 2) + 1 = 17
// → Use 20 as a practical max
```

**Prevention:**
1. Alert when `hikaricp_connections_pending > 0` for > 60s
2. Alert when `hikaricp_connections_active / hikaricp_connections_max > 0.8`
3. Add `@Timeout` annotation on repository methods
4. Enable HikariCP metrics with `spring.datasource.hikari.metrics-tracker=micrometer`
5. Review query plans in CI pipeline using `EXPLAIN ANALYZE`

---

### Scenario 3: Memory Leak — ThreadLocal Cleanup Failure

**Symptom:**
- Heap usage steadily increases over time, never decreases after garbage collection
- After 24 hours, application experiences GC death spiral:
  - Full GC runs every 30 seconds
  - Each Full GC takes 200-500ms
  - CPU spikes to 100% during GC
- Eventually: `java.lang.OutOfMemoryError: Java heap space`
- Restart fixes the issue temporarily (heap resets), but problem returns within 24 hours

**Investigation (exact commands):**

```bash
# Step 1: Get heap histogram to see what's accumulating
jcmd <pid> GC.class_histogram | head -30

# Or via jmap (pauses JVM briefly):
jmap -histo:live <pid> | head -30

# Output might show:
#  num     #instances         #bytes  class name
#  ----------------------------------------------
#    1:      1200000      192000000  java.util.LinkedHashMap$Entry
#    2:      1200000       96000000  java.lang.String
#    3:        50000       40000000  [C
#    4:        50000       20000000  com.example.UserContext

# The UserContext at position 4 is suspicious — 50,000 instances
# Should only be a few hundred (one per thread)

# Step 2: Get heap dump and analyze offline
jmap -dump:live,format=b,file=heap.bin <pid>

# In Eclipse Memory Analyzer (MAT):
#   File → Open Heap Dump → heap.bin
#   Run "Leak Suspects" report
#   Look at "Path to GC Roots" for UserContext instances
#
# Expected finding:
#   UserContext → value of java.lang.ThreadLocal$ThreadLocalMap$Entry
#   → table of ThreadLocalMap → threadLocals of Thread
#
# This means UserContext is stored as a ThreadLocal value
# and thousands of entries accumulated in thread's ThreadLocalMap

# Step 3: Confirm ThreadLocal leak in code
grep -r "ThreadLocal" src/main/java/ | grep -v "test"

# Looking for:
grep -r "MDC.put\|RequestContextHolder\|SecurityContextHolder" src/main/java/

# Step 4: Check code for missing cleanup
# Find all MDC.put() calls without corresponding MDC.clear():
grep -r "MDC.put" src/main/java/ | wc -l   # 12 locations
grep -r "MDC.clear" src/main/java/ | wc -l # 3 locations
# → 9 locations put but never clear
```

**Root Cause:** A filter added `userId`, `requestId`, and `tenantId` to MDC for structured logging. Another filter correlated HTTP requests with `RequestContextHolder`. But neither filter cleaned up in a `finally` block when exceptions occurred. Over thousands of requests, each Tomcat worker thread accumulated hundreds of ThreadLocal entries. Since Tomcat keeps worker threads alive indefinitely, the ThreadLocal values were never garbage collected, even though the HTTP requests were long completed.

**Immediate Fix:**
```bash
# Restart the application to clear accumulated ThreadLocals
kubectl rollout restart deployment payment-api -n payment

# This is a TEMPORARY fix — memory leak resumes on restart
```

**Permanent Fix:**
```java
// BEFORE (buggy):
@Component
public class RequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // SETUP (runs before controller)
        MDC.put("requestId", UUID.randomUUID().toString());
        MDC.put("userId", request.getHeader("X-User-Id"));
        MDC.put("tenantId", request.getHeader("X-Tenant-Id"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        chain.doFilter(request, response);
        // BUG: No cleanup! If chain.doFilter() throws, MDC and RequestContextHolder
        // retain stale values. ThreadLocal references persist for thread lifetime.
    }
}

// AFTER (fixed):
@Component
public class RequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        boolean isNewContext = (MDC.getMDCAdapter() == null
                || MDC.get("requestId") == null);
        if (isNewContext) {
            MDC.put("requestId", UUID.randomUUID().toString());
        }
        MDC.put("userId", request.getHeader("X-User-Id"));
        MDC.put("tenantId", request.getHeader("X-Tenant-Id"));

        try {
            chain.doFilter(request, response);
        } finally {
            // CRITICAL: Always clean up, even on exception
            if (isNewContext) {
                MDC.remove("requestId");
            }
            MDC.remove("userId");
            MDC.remove("tenantId");
            // RequestContextHolder uses thread-bound strategy by default
            // In a filter, you MUST reset it after the request
        }
    }
}

// Even better: Use MDC.clear() for complete cleanup
// And configure Logback to auto-clear via MDCInsertingServletFilter
// (but still wrap in try/finally!)
```

**Prevention:**
1. Write an ArchUnit test that enforces: every `MDC.put()` must have a corresponding `MDC.remove()` or `MDC.clear()` in a finally block
2. Add `MdcCleanupFilter` as the outermost filter in the chain:
```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcCleanupFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
```
3. Monitor `jvm_memory_used_bytes{area="heap"}` for monotonic increase
4. Enable GC logging: `-Xlog:gc*:file=gc.log:time,level,tags`
5. Periodic review of ThreadLocal usage in PR reviews — any ThreadLocal must document its cleanup strategy

---

### Scenario 4: Port Already In Use

**Symptom:**
- New pod fails to start
- Application log: `java.net.BindException: Address already in use: bind`
- Pod restarts in CrashLoopBackOff

**Investigation (exact commands):**

```bash
# Step 1: Check pod events
kubectl describe pod payment-api-8f3a2-xyz789 -n payment

# Step 2: Check container logs
kubectl logs payment-api-8f3a2-xyz789 -n payment --previous

# Look for:
# Caused by: java.net.BindException: Address already in use: bind
#   at java.base/sun.nio.ch.Net.bind0(Native Method)
#   at java.base/sun.nio.ch.Net.bind(Net.java:555)
#   at org.apache.tomcat.util.net.NioEndpoint.bind(NioEndpoint.java:229)

# Step 3: Check what's using the port (if you can get a shell on the node or another pod)
# On a Kubernetes node or via hostNetwork pod:
ss -tlnp | grep 8080
# Output: LISTEN  0  100  *:8080  *:*  users:(("java",pid=12345,fd=156))
# → Another Java process is already bound to port 8080

# Step 4: Check if this is the same application double-started
# On the node, check if there are two java processes:
ps aux | grep java
# root  12344  java -jar payment-api.jar
# root  12345  java -jar payment-api.jar   ← OLD PROCESS didn't shut down!

# Root cause: graceful shutdown failed, old process still running on port 8080
# when new pod (re)started
```

**Root Cause:** The previous instance of the application did not shut down cleanly. During a rolling update, the old pod received SIGTERM but the graceful shutdown took longer than `terminationGracePeriodSeconds` (default 30s). Kubernetes sent SIGKILL before the embedded Tomcat server unbind the port. However, the JVM did not actually exit because some non-daemon threads were still running (a hung `@PreDestroy` method creating a deadlock in the shutdown hook).

**Immediate Fix:**
```bash
# Find and kill any rogue Java processes holding the port
kubectl exec -n payment payment-api-8f3a2-xyz789 -c debug -- sh
# (debug container with hostPID: true)

# Or: let Kubernetes handle it
kubectl delete pod payment-api-8f3a2-xyz789 -n payment --force --grace-period=0
```

**Permanent Fix:**
```yaml
# 1. Increase termination grace period
spec:
  terminationGracePeriodSeconds: 60  # Up from 30

  containers:
  - name: payment-api
    lifecycle:
      preStop:
        exec:
          command:
          - /bin/sh
          - -c
          - |
            # Give time for kube-proxy to update iptables
            sleep 15

# 2. Ensure all user threads are daemon or have shutdown hooks
# Any non-daemon thread keeps the JVM alive!
@Bean
public ExecutorService asyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(8);
    executor.setMaxPoolSize(16);
    executor.setThreadGroupName("async-");
    executor.setDaemon(true);  // CRITICAL: threads won't prevent JVM shutdown
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor.getThreadPoolExecutor();
}

// 3. Add a shutdown watchdog: if shutdown takes > 25s, force exit
// This ensures the JVM exits before Kubernetes sends SIGKILL
@Component
public class ShutdownWatchdog {
    @EventListener
    public void onShutdown(ContextClosedEvent event) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(25000);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("Shutdown exceeded 25s, forcing exit");
            Runtime.getRuntime().halt(1); // Force exit, bypasses shutdown hooks
        });
        watchdog.setDaemon(true);
        watchdog.start();
    }
}

// 4. Enable shutdown logging
logging.level.org.springframework.context.support=DEBUG
```

### Scenario 5: Data Corruption via Dual Writes

**Symptom:**
- Database shows `order.status = 'PROCESSING'` but the message queue/DWH/other service never received an event
- OR: Event published but database transaction rolled back
- Data inconsistency between two systems that grows over time
- No application errors in logs

**Investigation:**

```sql
-- Step 1: Find orders in inconsistent state
SELECT o.id, o.status, o.updated_at
FROM orders o
LEFT JOIN order_events e ON o.id = e.order_id
WHERE o.status = 'PROCESSING'
  AND e.id IS NULL
  AND o.updated_at < now() - interval '5 minutes';
-- Returns 47 orders: "processing" but no event published

-- Step 2: Find events without orders
SELECT e.order_id, e.event_type, e.created_at
FROM order_events e
LEFT JOIN orders o ON e.order_id = o.id
WHERE o.id IS NULL;
-- Returns 3 events referencing orders that don't exist

-- Step 3: Check application code for the dual write pattern
grep -r "sendMessage\|publishEvent" src/main/java/ | grep -B5 -A5 "save\|persist"
```

**Root Cause — Pattern 1 (Write then Emit):**
```java
// BUGGY: DB write succeeds, but event publish fails
@Transactional
public Order processOrder(OrderRequest request) {
    Order order = orderRepository.save(new Order(request));
    // DB COMMITTED here (transactional boundary)

    orderEventPublisher.publish(new OrderProcessedEvent(order));
    // If this FAILS (broker down, network error):
    // → DB has the order, but downstream services never know about it
    // → Data inconsistency
}
```

**Root Cause — Pattern 2 (Emit then Write):**
```java
// BUGGY: Event published, but DB write fails
@Transactional
public Order processOrder(OrderRequest request) {
    orderEventPublisher.publish(new OrderProcessedEvent(...));
    // EVENT PUBLISHED — downstream services already reacted

    Order order = orderRepository.save(new Order(request));
    // If this FAILS (constraint violation, disk full):
    // → Event exists, but there's no order
    // → Data inconsistency in the opposite direction
}
```

**Permanent Fix — Transactional Outbox Pattern:**
```java
// Instead of publishing events directly, write them to an OUTBOX table
// in the SAME transaction as the business data change.

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id @GeneratedValue
    private Long id;
    private String aggregateType;  // "Order"
    private String aggregateId;    // order.getId()
    private String eventType;      // "OrderProcessed"
    @Column(columnDefinition = "jsonb")
    private String payload;        // JSON serialized event
    private LocalDateTime createdAt;
    private boolean published;     // false initially
}

// Service:
@Transactional
public Order processOrder(OrderRequest request) {
    Order order = orderRepository.save(new Order(request));

    outboxEventRepository.save(OutboxEvent.builder()
        .aggregateType("Order")
        .aggregateId(order.getId().toString())
        .eventType("OrderProcessed")
        .payload(toJson(new OrderProcessedEvent(order)))
        .createdAt(LocalDateTime.now())
        .build());

    // BOTH order AND outbox_event are committed atomically in one transaction
    // If either fails, both roll back → NO inconsistency
}

// Outbox Poller (runs on schedule or via Debezium CDC):
@Component
public class OutboxPoller {
    @Scheduled(fixedDelay = 1000)  // Poll every 1s
    @Transactional
    public void publishOutboxEvents() {
        List<OutboxEvent> events = outboxEventRepository
            .findByPublishedFalse(Pageable.ofSize(100));

        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(event.getEventType(), event.getPayload());
                event.setPublished(true);
            } catch (Exception e) {
                log.error("Failed to publish event {}", event.getId(), e);
                // Event stays unpublished, will be retried next poll
            }
        }
    }
}
```

### Scenario 6: Gradual Degradation — Response Time Creep

**Symptom:**
- p50 latency increases from 50ms to 200ms over 72 hours: not enough to trigger alerts
- p99 latency increases from 200ms to 3s: triggers alert only at p99 > 1s
- Error rate stays below 1%: no error-rate alerts fire
- PagerDuty fires at 3 AM Saturday: "p99 latency > 5s"

**Investigation (exact commands):**

```bash
# Step 1: Query the latency timeline
# In Grafana / Prometheus:
#   rate(http_server_requests_seconds_sum[5m]) / rate(http_server_requests_seconds_count[5m])
# Look at the chart: is latency increasing linearly, stepwise, or randomly?

# Step 2: Break down by component
# Endpoint latency breakdown:
#   @Timed("orders.findAll") on the controller method
http_server_requests_seconds{uri="/api/orders",quantile="0.99"}  # p99: 3.2s

# Database query time:
#   @Timed on repository method via Aspect
hikaricp_connections_usage_seconds{pool="HikariPool-1",quantile="0.99"}  # p99: 50ms
# → DB is NOT the bottleneck

# Downstream HTTP calls:
http_client_requests_seconds{uri="/api/inventory/stock",quantile="0.99"}  # p99: 15ms
# → Downstream is NOT the bottleneck either

# So what IS taking time?
# Step 3: Profile the application with async-profiler
kubectl cp async-profiler.tar.gz payment-api-xxx:/tmp/
kubectl exec payment-api-xxx -- \
  /tmp/async-profiler/bin/asprof -d 30 -f /tmp/profile.html <pid>

# Flame graph shows:
#   45% Compon...getPrice() → CacheLoader.load() → calculateDiscount()
#
# The calculateDiscount() method calls an external pricing service that
# has been gradually degrading over 72 hours. Cache miss rate increased
# from 2% to 40% because cache entries expired and couldn't be reloaded
# fast enough.
```

**Root Cause — Cache evaporation without refresh:** A Caffeine cache with `expireAfterWrite(1, HOURS)` was serving pricing data. Over 72 hours, the external pricing service degraded (p99 from 10ms to 500ms). As cache entries expired, each reload took 500ms instead of 10ms. With 100 TPS and 1-hour TTL, roughly 100,000 entries expired per hour. Each reload blocking a Tomcat thread caused thread accumulation. The system didn't crash, it just got progressively slower until p99 breached the SLO.

**Permanent Fix:**
```java
// 1. Use refreshAfterWrite NOT just expireAfterWrite
// Refresh reloads the entry asynchronously, serving stale data in the meantime
LoadingCache<String, Price> priceCache = Caffeine.newBuilder()
    .expireAfterWrite(24, TimeUnit.HOURS)    // Hard expiry (data too old)
    .refreshAfterWrite(10, TimeUnit.MINUTES)  // Soft expiry (async refresh)
    .maximumSize(100_000)
    .recordStats()
    .build(key -> pricingService.getPrice(key));  // Loader used by refresh too

// 2. Add resilience to the loader
LoadingCache<String, Price> priceCache = Caffeine.newBuilder()
    .expireAfterWrite(24, TimeUnit.HOURS)
    .refreshAfterWrite(10, TimeUnit.MINUTES)
    .build(key -> {
        try {
            return pricingService.getPrice(key);
        } catch (Exception e) {
            // Return LAST KNOWN GOOD value rather than failing
            Price cached = priceCache.getIfPresent(key);
            if (cached != null) return cached;
            throw e;  // Re-throw if no cached value exists
        }
    });

// 3. Alert on cache hit rate degradation
@Scheduled(fixedDelay = 60000)
public void checkCacheHealth() {
    CacheStats stats = priceCache.stats();
    double hitRate = stats.hitRate();
    if (hitRate < 0.8) {  // Below 80% hit rate
        meterRegistry.counter("cache.health.degraded").increment();
        log.warn("Price cache hit rate degraded to {:.2f}%", hitRate * 100);
    }
}
```

## 9. Debugging Techniques

### The SEV1 Troubleshooting Flowchart

```
ALERT FIRES
  |
  v
1. ISOLATE THE BLAST RADIUS (30 seconds)
   |-- Check: Is this ONE instance or ALL instances?
   |   kubectl get pods -n payment -o wide | grep payment-api
   |   For each pod: curl http://pod-ip:8080/actuator/health
   |
   |-- Check: Is this ONE endpoint or ALL endpoints?
   |   Look at error distribution in Grafana / Datadog / Prometheus:
   |     sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (uri)
   |
   |-- Decision: If SINGLE instance → cordon and drain
   |            If ALL instances → escalate severity, check dependencies
   |
   v
2. IDENTIFY THE SATURATED RESOURCE (2 minutes)
   |
   |-- Check threads:  curl /actuator/metrics/tomcat.threads.busy
   |   If = server.tomcat.threads.max → THREAD POOL EXHAUSTION
   |
   |-- Check connections: curl /actuator/metrics/hikaricp.connections.active
   |   If = max-pool-size → CONNECTION POOL EXHAUSTION
   |
   |-- Check memory: curl /actuator/metrics/jvm.memory.used
   |   If heap > 90% AND Full GC frequent → MEMORY PRESSURE
   |
   |-- Check CPU: curl /actuator/metrics/process.cpu.usage
   |   If > 80% → CPU SATURATION
   |
   |-- Check GC: curl /actuator/metrics/jvm.gc.pause
   |   If p99 > 500ms → GC PAUSE PROBLEM
   |
   |-- Check file descriptors: curl /actuator/metrics/process.files.open
   |   If > process.files.max * 0.8 → FD EXHAUSTION
   |
   v
3. CAPTURE FORENSIC DATA (run in parallel, 30 seconds)
   |
   |-- Thread dump: jcmd <pid> Thread.print > threaddump_$(date +%s).txt
   |-- Heap histogram: jcmd <pid> GC.class_histogram > histogram_$(date +%s).txt
   |-- If memory leak suspected: jcmd <pid> GC.heap_dump heap_$(date +%s).hprof
   |-- If CPU high: jcmd <pid> JFR.start duration=60s filename=jfr_$(date +%s).jfr
   |
   v
4. MITIGATE (1 minute)
   |
   |-- Threads saturated: scale up replicas OR restart affected pods
   |-- Connections saturated: kill long-running DB queries (pg_terminate_backend)
   |-- Memory pressure: restart (heap resets), then tune -Xmx
   |-- CPU saturation: scale horizontally
   |-- Downstream failure: circuit-break to failing dependency
   |
   v
5. ROOT CAUSE (post-mortem, not during incident)
   |-- Analyze captured data (thread dumps, heap dumps, JFR recordings)
   |-- Identify code path that consumed the saturated resource
   |-- Fix the root cause (add timeout, add index, fix ThreadLocal leak, etc.)
```

### Essential Debugging Commands

```bash
# === ACTUATOR ENDPOINTS (must be enabled in production) ===

# Health check with details (why are we DOWN?)
curl http://localhost:8080/actuator/health | jq .

# Thread states summary
curl http://localhost:8080/actuator/metrics/jvm.threads.states | jq .

# Memory breakdown
curl http://localhost:8080/actuator/metrics/jvm.memory.used | jq .
curl http://localhost:8080/actuator/metrics/jvm.memory.max | jq .

# GC stats
curl http://localhost:8080/actuator/metrics/jvm.gc.pause | jq .
curl http://localhost:8080/actuator/metrics/jvm.gc.memory.allocated | jq .

# Tomcat thread pool
curl http://localhost:8080/actuator/metrics/tomcat.threads.busy | jq .
curl http://localhost:8080/actuator/metrics/tomcat.threads.config.max | jq .

# Tomcat connection stats
curl http://localhost:8080/actuator/metrics/tomcat.connections.current | jq .
curl http://localhost:8080/actuator/metrics/tomcat.connections.max | jq .

# HikariCP
curl http://localhost:8080/actuator/metrics/hikaricp.connections | jq .
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq .
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending | jq .
curl http://localhost:8080/actuator/metrics/hikaricp.connections.timeout | jq .

# HTTP request metrics
curl http://localhost:8080/actuator/metrics/http.server.requests | jq .

# File descriptors
curl http://localhost:8080/actuator/metrics/process.files.open | jq .
curl http://localhost:8080/actuator/metrics/process.files.max | jq .

# === JDK TOOLS ===

# Thread dump (does NOT pause JVM)
jcmd <pid> Thread.print

# Thread dump with deadlock detection
jcmd <pid> Thread.print -l  # Show lock information

# Heap histogram (live objects only)
jcmd <pid> GC.class_histogram

# Full heap dump (pauses JVM briefly)
jcmd <pid> GC.heap_dump filename=heap.hprof

# JFR recording (continuous profiling)
jcmd <pid> JFR.start name=profile duration=120s filename=profile.jfr
jcmd <pid> JFR.dump name=profile filename=profile.jfr
jcmd <pid> JFR.stop name=profile

# VM flags (check what -Xmx, GC, etc. are set to)
jcmd <pid> VM.flags -all

# === OS-LEVEL TOOLS ===

# Which ports are listening?
netstat -tlnp | grep java

# Open file descriptors per process
ls -l /proc/<pid>/fd | wc -l
lsof -p <pid> | wc -l

# Thread count per process
cat /proc/<pid>/status | grep Threads

# Memory usage (RSS, VSS)
cat /proc/<pid>/status | grep Vm

# === DATABASE ===

# Active queries (PostgreSQL)
SELECT pid, age(clock_timestamp(), query_start), state, query
FROM pg_stat_activity
WHERE state != 'idle' AND pid <> pg_backend_pid()
ORDER BY query_start;

# Locks held (PostgreSQL)
SELECT relation::regclass, mode, granted, pid
FROM pg_locks
WHERE NOT granted;

# Kill a specific query
SELECT pg_terminate_backend(<pid>);
SELECT pg_cancel_backend(<pid>);  # Gentler: sends cancel signal
```

## 10. Observability Considerations

### Building a Troubleshooting Runbook Template

```markdown
# Runbook: {{Service Name}} — {{Alert Name}}

## Alert Definition
- **Alert**: `payment_api_p99_latency > 5000ms for 5m`
- **PromQL**: `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{service="payment-api"}[5m])) > 5`
- **Severity**: SEV2 (auto-escalate to SEV1 after 10m)
- **Slack Channel**: #payment-alerts
- **Runbook URL**: https://wiki.company.com/runbooks/payment-api-latency

## Initial Triage (First 2 Minutes)
- [ ] **Check blast radius**: `kubectl get pods -n payment -l app=payment-api`
- [ ] **Active incidents**: https://status.company.com
- [ ] **Recent deployments**: `kubectl rollout history deployment/payment-api -n payment`
- [ ] **Dashboard**: https://grafana.company.com/d/payment-api/SLO

## Diagnosis Decision Tree
1. **All pods affected?**
   - YES → Check downstream dependencies, database, recent config change
   - NO → Investigate individual pod (uneven load, noisy neighbor)

2. **CPU high on affected pods?** (`curl /actuator/metrics/process.cpu.usage`)
   - YES → CPU profiling needed (JFR / async-profiler)
   - NO → Check thread pool and connection pool

3. **Thread pool saturated?** (`curl /actuator/metrics/tomcat.threads.busy`)
   - YES → Thread dump analysis → Check what threads are doing
   - NO → Check connection pool, GC, or downstream latency

4. **HikariCP saturated?** (`curl /actuator/metrics/hikaricp.connections.pending`)
   - YES → Check active DB queries → Kill long queries → Add index
   - NO → Check downstream HTTP calls

5. **Downstream latency high?** (Grafana: downstream-latency dashboard)
   - YES → Circuit-break to that dependency → Follow its runbook
   - NO → Check GC pauses

6. **GC pauses excessive?** (`curl /actuator/metrics/jvm.gc.pause`)
   - YES → Check heap usage → Heap dump if needed → Tune GC
   - NO → Escalate to platform team

## Emergency Mitigations
### Mitigation A: Restart affected pods
```bash
kubectl rollout restart deployment/payment-api -n payment
```
**When**: Single-instance issue, memory leak, thread pool exhaustion
**Risk**: Brief unavailability during restart

### Mitigation B: Scale up
```bash
kubectl scale deployment/payment-api --replicas=8 -n payment
```
**When**: Load spike, downstream degradation (more threads = more headroom)
**Risk**: May exacerbate DB connection pressure

### Mitigation C: Circuit break downstream
```bash
curl -X POST https://payment-api.internal/actuator/circuitbreakers/inventory/transition-to-open
```
**When**: Downstream service is degraded and causing cascading failure
**Risk**: Stale/fallback data served instead

### Mitigation D: Rollback
```bash
kubectl rollout undo deployment/payment-api -n payment
```
**When**: Recent deployment correlates with incident onset
**Risk**: Loses any data written by new version

## Escalation Path
| Role | Contact | When to Escalate |
|------|---------|-----------------|
| Primary On-Call | PagerDuty schedule | Always first responder |
| Secondary On-Call | PagerDuty schedule | If primary doesn't ack within 5m |
| Service Owner | @jane-doe on Slack | After 15m without resolution |
| Platform Team | #platform-oncall | Infrastructure-level issue (DB, network, K8s) |
| Engineering Manager | @eng-mgr on Slack | After 30m, SEV1 declaration |
| VP Engineering | Phone tree | SEV1 only, after 1h |
```

### Incident Command Structure for SEV1

```
SEV1 INCIDENT RESPONSE ROLES:

┌─────────────────────────────────────────────────────────┐
│ INCIDENT COMMANDER (IC)                                  │
│  - Runs the incident, makes all decisions                │
│  - Keeps timeline of events and actions                  │
│  - Communicates status to stakeholders every 15m         │
│  - NOT debugging — IC is a coordination role             │
│  - Says: "I need a thread dump from pod X"               │
│    not: "I need to check the HikariCP source code"       │
└─────────────────────────────────────────────────────────┘
         │
         ├──────────────────┬──────────────────┐
         v                  v                  v
┌─────────────────┐ ┌──────────────┐ ┌──────────────────┐
│ OPS LEAD         │ │ TECH LEAD    │ │ COMMUNICATIONS    │
│ (Infrastructure) │ │ (Application)│ │ LEAD              │
│                  │ │              │ │                   │
│ - K8s commands   │ │ - Thread dump│ │ - Status page     │
│ - Traffic shifts │ │ - Heap dump  │ │   updates         │
│ - Rollback       │ │ - JFR capture│ │ - Slack updates   │
│ - Scale up/down  │ │ - Source code│ │ - Ticket updates  │
│ - Network checks │ │   analysis   │ │ - Stakeholder     │
│                  │ │ - Query plan │ │   briefings       │
└─────────────────┘ └──────────────┘ └──────────────────┘
```

## 11. Performance Implications

### Cascading Failure Performance Model

```
Throughput = threads / latency

With 200 Tomcat workers and 100ms avg latency:
  Max throughput = 200 / 0.1 = 2000 requests/second

When downstream latency increases to 5s:
  Max throughput = 200 / 5.0 = 40 requests/second

When circuit breaker opens at 100ms timeout:
  Threads blocked on downstream: 0
  Max throughput = 200 / 0.1 = 2000 requests/second (restored!)
  BUT: requests served from fallback/stale cache

The performance implication: a circuit breaker with a 100ms timeout
can prevent a 98% throughput degradation caused by a single slow downstream.
Without it, the slowest dependency determines the throughput of the entire service.
```

### Thread Pool Sizing for Resilience

```
Recommended ratios for a Spring Boot service with 5 downstream dependencies:

┌────────────────────┬──────────┬─────────────────────────────────┐
│ Pool               │ Size     │ Rationale                       │
├────────────────────┼──────────┼─────────────────────────────────┤
│ Tomcat workers     │ 200      │ Standard, adjusted per load test│
│ HikariCP           │ 20       │ Per DB instance, not per service│
│ @Async pool        │ bounded  │ NEVER unbounded!                │
│ HTTP client pool   │ 5 × 20   │ 5 downstreams × 20 conns each  │
│ Circuit breaker    │ 50% of   │ Open when > 50% slow            │
│ slow call threshold│ workers  │                                 │
│ Timeout            │ 3× less  │ Timeout << breaker threshold    │
│                    │ than CB  │                                 │
└────────────────────┴──────────┴─────────────────────────────────┘

Rule: timeout < slowCallDurationThreshold < maxThreadBlockingDuration
  timeout = 2s
  slowCallDurationThreshold = 3s
  max thread blocking before queues fill = Tomcat.threads.max × average latency
```

## 12. Architecture Implications

### Designing for Degradation

```
ARCHITECTURAL DECISIONS TO PREVENT PRODUCTION FAILURES:

1. TIME OUT EVERYTHING
   └── Every cross-service call, every DB query, every cache access
       has a timeout. Default: 5 seconds is NOT acceptable.

2. CIRCUIT BREAK EVERY DEPENDENCY
   └── No external call without a circuit breaker. Period.
       Fallback strategy must be defined before code is written.

3. BULKHEAD WITH BOUNDED QUEUES
   └── Every thread pool has a bounded queue with a rejection policy.
       Unbounded queues hide problems until OOM.

4. SHED LOAD AT THE EDGE
   └── Rate limit at the API gateway / load balancer level.
       Shed excess traffic before it reaches application threads.

5. DESIGN FOR ASYNC BY DEFAULT
   └── Don't block Tomcat workers on I/O. Use WebClient, R2DBC,
       Project Loom virtual threads, or CompletableFuture.

6. SEPARATE READ AND WRITE PATHS
   └── Read path can handle stale data with cached fallbacks.
       Write path typically needs consistency. Different SLOs,
       different pool configurations, different circuit breakers.

7. DEFINE AND MONITOR SLOs PER ENDPOINT
   └── Not all endpoints are equal. /health must serve in < 100ms.
       /report can take 5s. Define error budgets per endpoint.

8. CHAOS ENGINEERING
   └── Regularly inject: downstream latency, downstream errors,
       connection pool exhaustion, network partitions.
       Verify system degrades as designed, not as feared.
```

### The Multi-Pool Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  THREAD POOL ARCHITECTURE                         │
│                                                                   │
│ ┌───────────────────┐  ┌───────────────────┐  ┌───────────────┐ │
│ │ TOMCAT WORKERS    │  │ ASYNC EXECUTOR    │  │ SCHEDULER     │ │
│ │ (200 threads)     │  │ (8/16 threads)    │  │ (2 threads)   │ │
│ │                   │  │                   │  │               │ │
│ │ Serves:           │  │ Serves:           │  │ Serves:       │ │
│ │  - HTTP requests  │  │  - Email sending  │  │  - @Scheduled │ │
│ │  - API responses  │  │  - PDF generation │  │  - Cron jobs  │ │
│ │  - Health checks  │  │  - File upload    │  │  - Heartbeat  │ │
│ │                   │  │  - Notifications  │  │               │ │
│ │ BULKHEAD:         │  │                   │  │ BULKHEAD:     │ │
│ │ Slow @Async tasks │  │ Slow scheduled    │  │ HTTP handling │ │
│ │ do not block      │  │ tasks do not      │  │ is unaffected │ │
│ │ HTTP requests     │  │ block @Async work │  │               │ │
│ └───────────────────┘  └───────────────────┘  └───────────────┘ │
│                                                                   │
│ ┌───────────────────────────────────────────────────────────────┐ │
│ │ HIKARICP CONNECTION POOL (20 connections per DB instance)      │ │
│ │                                                                │ │
│ │ ├─ Pool 1: Primary (writes + critical reads) — 15 connections │ │
│ │ ├─ Pool 2: Read-replica (non-critical reads, reports) — 5     │ │
│ │ └─ Pool 3: Analytics (batch, ETL) — dedicated 5               │ │
│ │                                                                │ │
│ │ BULKHEAD: Report query cannot exhaust connections for         │ │
│ │           critical order-processing queries                    │ │
│ └───────────────────────────────────────────────────────────────┘ │
│                                                                   │
│ ┌───────────────────────────────────────────────────────────────┐ │
│ │ HTTP CLIENT CONNECTION POOLS (per downstream)                  │ │
│ │                                                                │ │
│ │ ├─ inventory-service: 20 connections, 2000ms timeout           │ │
│ │ ├─ payment-gateway: 30 connections, 5000ms timeout             │ │
│ │ ├─ notification-service: 10 connections, 10000ms timeout       │ │
│ │ └─ analytics-service: 5 connections, 30000ms timeout           │ │
│ │                                                                │ │
│ │ BULKHEAD: Payment gateway pool exhaustion does not affect      │ │
│ │           inventory service calls                               │ │
│ └───────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

## 13. Team Ownership Implications

### Ownership Model for Production Reliability

```
SERVICE OWNERSHIP MODEL:

┌────────────────────────────────────────────────────────────────┐
│ SERVICE TEAM (Payment Squad)                                     │
│                                                                  │
│ OWNS:                                                            │
│  ✅ Service code and configuration                               │
│  ✅ Deployment pipeline and rollback procedures                  │
│  ✅ SLOs, SLIs, and error budgets                                │
│  ✅ Alert definitions and thresholds                             │
│  ✅ Runbooks (documented, tested, kept current)                  │
│  ✅ On-call rotation (primary + secondary)                       │
│  ✅ Capacity planning and load testing                           │
│  ✅ Database schema and query performance                        │
│  ✅ Circuit breaker configuration                                │
│                                                                  │
│ DOES NOT OWN (but depends on):                                   │
│  ❌ Kubernetes cluster and node health (platform team)           │
│  ❌ Database server and replication (infra team)                 │
│  ❌ Network and load balancer (networking team)                  │
│  ❌ Monitoring infrastructure: Prometheus, Grafana, AlertManager │
│     (observability team)                                         │
│  ❌ CI/CD platform: GitHub Actions, Jenkins (DevOps team)        │
│                                                                  │
│ ESCALATION CONTRACT:                                             │
│  "Payment team owns the application up to the JVM process.       │
│   Below the JVM, infra/platform/networking teams own the         │
│   infrastructure. Payment team will provide thread dumps,        │
│   heap dumps, and query plans. Infra team will provide node      │
│   metrics, network traces, and disk health."                     │
└────────────────────────────────────────────────────────────────┘
```

### Runbook Maintenance Schedule

```
RUNBOOK LIFECYCLE:

  [CREATE] → Author writes runbook as part of incident post-mortem
      │
      v
  [REVIEW] → Team review during sprint planning
      │       Check: still accurate? commands still work?
      │       Check: time estimates still valid?
      │
      v
  [TEST]   → Quarterly game day / chaos engineering exercise
      │       On-call engineer follows runbook against staging
      │       Verify: runbook resolves the simulated incident
      │
      v
  [UPDATE] → If runbook didn't work, update and re-test
      │
      v
  [RETIRE] → If alert is removed or service is decommissioned

  RUNBOOK CHECKLIST (monthly review):
  □ All actuator endpoints listed produce expected output
  □ All kubectl commands work against current cluster
  □ All SQL queries still valid against current schema
  □ Escalation contacts are current
  □ Mitigation steps tested in the last 90 days
  □ Grafana dashboard links still valid
```

## 14. Interview Questions

### Question 1: "You are on-call and get paged at 3 AM: 'Service payment-api is returning 502 errors on all endpoints.' The load balancer shows all 4 instances as unhealthy. The last deployment was 3 days ago. Walk me through your investigation and decision-making process."

**Staff-level answer:**

Phase 1 — Immediate blast radius assessment (< 2 min):
I check whether all 4 instances are actually down or just marked unhealthy. `kubectl get pods` shows all pods running but with `READY 0/1`. This means the liveness or readiness probe is failing, not necessarily that the JVM has crashed. I try direct pod access: `kubectl port-forward pod/payment-api-xxx 8081:8080` and `curl localhost:8081/actuator/health` — it times out. The JVM is alive (pod isn't restarting), but it's not responding.

Phase 2 — Identify the saturated resource (2-5 min):
Since 3 days since the last deploy rules out a bad release, I focus on resource saturation. I exec into one pod: `jcmd <pid> Thread.print`. The thread dump shows 200 threads, all in `RUNNABLE` state with stack frames ending in `java.net.SocketInputStream.socketRead0()` — reading from sockets. The bottom of each stack trace shows they're HTTP client threads calling our inventory service. The pattern: `OrderController → OrderService → InventoryClient.getStock() → HttpClient.send() → socketRead`.

I check the metrics endpoint (if responding) or get the JFR recording: `hikaricp_connections_active = 3` — DB is fine. `tomcat_threads_busy = 200` — pool saturated. `http_client_requests_seconds{uri="/inventory/stock"}` shows p99 = 30s.

Phase 3 — Root cause and mitigation (5-10 min):
All 200 Tomcat workers are blocked waiting for responses from the inventory service, which is slow. The health check is slow because it checks the same inventory service (bad health indicator configuration). Since all instances are affected, scaling won't help (new instances will hit the same slow downstream). I need to either: (a) fix the inventory service (if I can), (b) circuit-break to inventory, or (c) redeploy with the health indicator disabled for inventory.

If the circuit breaker is configured: `POST /actuator/circuitbreakers/inventory/transition-to-open`. This stops calls to inventory, threads unblock (HTTP calls fail immediately with `CallNotPermittedException`), Tomcat workers free up, health checks pass, and service recovers within seconds (serving fallback/stale data for inventory-dependent endpoints).

If the circuit breaker is NOT configured, I would: (a) redeploy with `spring.cloud.circuitbreaker.resilience4j.configs.default.register-health-indicator=false` so the health check doesn't depend on inventory, (b) add `spring.mvc.async.request-timeout=5s` to kill slow requests, or (c) temporarily remove the inventory health check by patching the ConfigMap.

Post-incident: The root cause is that the health indicator called the same slow downstream that killed the service, creating a self-reinforcing failure loop. The fix is: (1) health checks should NOT depend on external services (liveness should only check local resources), (2) all downstream calls need circuit breakers, (3) the service needs an HTTP-level timeout shorter than the Tomcat accept timeout.

### Question 2: "Explain how a ThreadLocal memory leak occurs in a Spring Boot application. What tools would you use to detect it, and what architectural pattern prevents it?"

**Staff-level answer:**

A ThreadLocal memory leak occurs when ThreadLocal values are set during request processing but never removed. In Spring Boot's embedded Tomcat, the worker threads are pooled and reused across thousands of requests. Each thread maintains a `ThreadLocalMap` — a hash table mapping `ThreadLocal` instances to their values. If a filter sets `MDC.put("userId", ...)`, a new entry is added to the current thread's `ThreadLocalMap`. If the filter never calls `MDC.remove("userId")` (especially in a `finally` block when exceptions occur), that entry persists for the lifetime of the thread. With 200 worker threads, each processing 10,000 requests per day, a single missed cleanup accumulates 200 × 10,000 = 2,000,000 leaked entries per day.

The three most common sources: (1) MDC (Logback's Mapped Diagnostic Context), (2) `RequestContextHolder` (Spring's request attributes holder), and (3) `SecurityContextHolder` (Spring Security's authentication holder). All three use `ThreadLocal` internally.

Detection tools: (1) **Heap histogram** via `jcmd <pid> GC.class_histogram` — look for domain classes with instance counts that correlate with request count, not thread count. (2) **Heap dump** analyzed in Eclipse MAT — run "Leak Suspects" report, then trace "Path to GC Roots" for the suspected class. If the path goes through `ThreadLocalMap.Entry → table → threadLocals → Thread`, it's a ThreadLocal leak. (3) **Actuator metrics** — `jvm_memory_used_bytes{area="heap"}` showing monotonic increase without decrease after GC. (4) **Micrometer's ThreadLocal gauge** (a custom `MeterBinder` that counts ThreadLocal entries per thread).

The architectural pattern that prevents this is the **try/finally boundary cleanup** pattern, best enforced by an outermost filter:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ThreadLocalCleanupFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req,
            HttpServletResponse res, FilterChain chain) {
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
            RequestContextHolder.resetRequestAttributes();
            SecurityContextHolder.clearContext();
        }
    }
}
```

This filter runs before and after every request. Because it's at `HIGHEST_PRECEDENCE`, it wraps the entire filter chain. The `finally` block guarantees cleanup regardless of exceptions. For `@Async` methods, the same pattern applies: the task executor should be configured with a `DecoratedTask` that wraps the `Runnable`/`Callable` to propagate and later clear ThreadLocal context.

### Question 3: "A database deadlock is occurring in production. Your application logs show `DeadlockLoserDataAccessException` every few minutes. How do you investigate and fix this?"

**Staff-level answer:**

Investigation proceeds in five steps:

**Step 1: Capture the deadlock details from the database.** In PostgreSQL, deadlock information is logged to the PostgreSQL log when `log_lock_waits = on` and `deadlock_timeout = 1s`. The log entry contains the exact SQL statements, the locks held, and the locks waited for by both transactions:

```
DETAIL: Process 12345 waits for ShareLock on transaction 67890;
        blocked by process 67891.
        Process 67891 waits for ShareLock on transaction 12345;
        blocked by process 12345.
        Process 12345: UPDATE orders SET status='PROCESSING' WHERE id=100
        Process 67891: UPDATE orders SET status='CANCELLED' WHERE id=100
```

**Step 2: Map SQL to application code paths.** I trace the SQL back to the repository methods: `OrderRepository.updateStatus(Long orderId, String status)` — called from both `OrderService.processOrder()` and `OrderService.cancelOrder()`. The deadlock occurs because both methods acquire locks on the `orders` row and the `order_items` rows in different orders.

**Step 3: Identify the inconsistent lock ordering:**

```java
// Transaction A (processOrder):
UPDATE orders SET status='PROCESSING' WHERE id=100;        // Lock orders:100
UPDATE order_items SET status='RESERVED' WHERE order_id=100; // Lock order_items where order_id=100

// Transaction B (cancelOrder):
UPDATE order_items SET status='CANCELLED' WHERE order_id=100; // Lock order_items where order_id=100
UPDATE orders SET status='CANCELLED' WHERE id=100;            // Lock orders:100 (DEADLOCK!)
```

Transaction A acquires lock on `orders` first, then `order_items`. Transaction B acquires lock on `order_items` first, then `orders`. When A holds `orders` and wants `order_items`, and B holds `order_items` and wants `orders` — deadlock.

**Step 4: Fix options:**
- **(a) Consistent lock ordering (simplest):** Always lock `orders` before `order_items`, or vice versa, in every transaction. Use `SELECT ... FOR UPDATE` to acquire locks explicitly and in order.
- **(b) Single UPDATE statement:** Instead of separate UPDATEs, use a single statement: `UPDATE orders SET status = 'CANCELLED' WHERE id = 100 AND status = 'PROCESSING'` with optimistic locking.
- **(c) Retry with exponential backoff:** Spring retries deadlocked transactions automatically with `@Retryable(value = DeadlockLoserDataAccessException.class, backoff = @Backoff(delay = 100, multiplier = 2))`.
- **(d) Advisory locks:** Use PostgreSQL advisory locks to serialize operations on the same order: `SELECT pg_advisory_xact_lock(100)` at the beginning of each transaction to prevent concurrent modifications of the same order.

**Step 5: Monitor for recurrence.** After the fix, add a counter for deadlock events:
```java
@Repository
public class DeadlockAwareOrderRepository {
    private final MeterRegistry meterRegistry;

    @Retryable(value = DeadlockLoserDataAccessException.class, maxAttempts = 3)
    public void updateStatus(Long id, String status) {
        meterRegistry.counter("db.deadlock.encountered").increment();
        // ... actual update ...
    }
}
```

## 15. Hands-On Exercises

1. **Simulate Tomcat thread pool exhaustion**: Create an endpoint `GET /api/slow` that calls `Thread.sleep(60000)`. Configure `server.tomcat.threads.max=5`. Use Apache Bench or k6 to send 20 concurrent requests. Observe: 5 succeed (eventually), 15 fail or queue. Use `/actuator/metrics/tomcat.threads.busy` to confirm saturation. Take a thread dump and identify the `Thread.sleep()` in all 5 worker threads.

2. **Simulate HikariCP connection pool exhaustion**: Configure a DataSource with `maximum-pool-size=2`. Create an endpoint that executes a slow query (e.g., `SELECT pg_sleep(30)`). Send 10 concurrent requests. Observe `hikaricp_connections_pending` climb and eventually `SQLException: Connection is not available`. Test with and without a connection timeout.

3. **Simulate and fix a ThreadLocal memory leak**: Create a `OncePerRequestFilter` that calls `MDC.put("bigString", new String(new char[1000000]))` on every request but does NOT call `MDC.clear()`. Send 10,000 requests via a load test. Monitor heap usage with `jcmd GC.heap_info`. Take a heap dump, open in Eclipse MAT, and trace the `String` instances through `ThreadLocalMap` to confirm the leak. Then fix with `MDC.clear()` in a `finally` block and verify the heap stabilizes.

4. **Simulate a database deadlock**: Create two endpoints: `POST /api/order/{id}/process` and `POST /api/order/{id}/cancel`. Both update the `orders` table and the `order_items` table, but in opposite order. Configure PostgreSQL with `deadlock_timeout=1s` and `log_lock_waits=on`. Fire both endpoints simultaneously for the same order ID. Capture the deadlock in PostgreSQL logs. Add `@Retryable` with exponential backoff and verify the second attempt succeeds.

5. **Build a circuit breaker integration test**: Use Testcontainers to run the inventory service as a mock. Introduce 5s latency on the `/api/stock` endpoint. Configure a Resilience4j circuit breaker with `slowCallDurationThreshold=2s` and `slidingWindowSize=5`. Send 10 requests — the first few should go through (slowly), the rest should throw `CallNotPermittedException`. Verify that the fallback method serves cached data. Open the circuit breaker manually via `/actuator/circuitbreakers` and verify immediate recovery.

6. **Simulate graceful shutdown issues**: Configure `server.shutdown=graceful` with `grace-period=10s`. Create an endpoint with a 60-second processing time. During a request, send SIGTERM (`kill -15 <pid>`). Observe: the in-flight request continues for 10 seconds (grace period), then is force-closed. The client sees a partial response or connection reset. Increase `grace-period` to 90s and verify the in-flight request completes. Add a `preStop` hook and verify the pod stays alive to drain connections.

## 16. Advanced Challenges

1. **Build a "Production Failure Simulator" Maven plugin**: Create a plugin that, given a service's dependency graph (declared in `failure-sim.yaml`), injects failures at runtime: (a) adds latency to specific HTTP endpoints, (b) throws exceptions, (c) exhausts the HikariCP pool, (d) fills the heap with a memory leak. Each failure type has configurable parameters (latency ms, exception type, leak rate). The plugin uses ByteBuddy to instrument classes at class loading time (Java agent approach). Build a dashboard that shows which failures the system survives and which cause cascading outages.

2. **Implement an automated post-mortem generator**: Build a Spring Boot starter that, on SEV1 incident resolution, automatically generates a post-mortem document. The starter should capture: (a) the alert timeline from AlertManager/PagerDuty, (b) the metric snapshots from Prometheus for the incident window, (c) thread dumps and heap histograms captured during the incident, (d) kubectl pod logs for the affected pods, (e) recent deployments from the deployment history. Generate a markdown document with timeline, root cause analysis (structured template with hypotheses/supporting evidence/rejected hypotheses), impact assessment, and action items.

3. **Create a "Chaos Monkey" for Spring Boot**: Implement a library that, via a `@Scheduled` method, introduces controlled chaos: (a) randomly kill a HikariCP connection every N minutes, (b) inject latency into `@Service` method calls (using AOP), (c) fill memory by allocating byte arrays on a schedule, (d) close random HTTP client connections, (e) cause a ThreadLocal leak by skipping cleanup in 1% of requests. Each chaos action is gated by a feature flag (can be enabled/disabled via `/actuator/chaos`). Provide a Web UI that shows current chaos state and allows manual chaos injection. Run this in staging continuously for 30 days.

4. **Build a "Self-Healing" framework**: Create a `SelfHealingAutoConfiguration` that monitors health indicators and automatically applies mitigations: (a) if `hikaricp_connections_pending > 0` for 60s → kill the longest-running query (via a separate admin connection), (b) if `tomcat_threads_busy > 0.9 × max` → start rejecting non-critical requests with 503 (load shedding), (c) if heap > 85% after GC → trigger a controlled restart (mark pod as draining, wait for connections to close, then restart). Each self-healing action must be approved by an operator unless the system is in "auto-pilot" mode (for staging only). Build a decision log that records every action and its outcome.

5. **Implement a production-grade canary analysis system**: Build a controller that gradually shifts traffic to a new version and automatically decides whether to promote or rollback based on SLO compliance. The canary analyzer compares metrics from canary pods (new version) vs baseline pods (old version): error rate, p99 latency, CPU, memory, GC pause, HikariCP timeout count, circuit breaker open count. Use statistical tests (Mann-Whitney U test) to determine if the difference is significant. Define a "scorecard" of weighted metrics. If score < threshold for 10 consecutive analysis windows, auto-rollback. If score > threshold for all windows in the analysis period, auto-promote. Integrate with Argo Rollouts or Flux.

