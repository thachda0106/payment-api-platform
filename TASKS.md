# TASKS — Engineering Curriculum Creation

## Overview

**Deliverable**: Single `CURRICULUM.md` file (~20,000 lines) in the project root.

**Execution**: 20 tasks, sequential within groups, some groups parallelizable.

**Total estimated writing output**: ~18,000–22,000 lines of curriculum content.

---

## Task Groups & Dependencies

```
GROUP A: Foundation
  T1 (Part 1: Dependency Graph) ──────┐
  T2 (Part 2: Learning Roadmap) ──────┤
                                      │
GROUP B: Core Domain Knowledge         │
  T3 (Part 5: Database Engineering) ◄─┘  (ledger depends on PG)
  T4 (Part 6: Distributed Systems)       (Kafka, sagas depend on this)
  T5 (Part 7: Kafka Ecosystem)           (builds on T4)
  T6 (Part 11: Payment Domain)           (core business domain)

GROUP C: Languages (partially parallel)
  T7 (Part 3a: Java Track)
  T8 (Part 3b: Go Track)                (independent of T7, T9, T10)
  T9 (Part 3c: Python Track)            (independent of T7, T8, T10)
  T10 (Part 3d: TypeScript/Node Track)  (independent of T7, T8, T9)

GROUP D: Frameworks
  T11 (Part 4a: Spring Boot Deep Dive)  ← depends on T7 (Java)
  T12 (Part 4b: FastAPI Deep Dive)      ← depends on T9 (Python)
  T13 (Part 4c: Chi Deep Dive)          ← depends on T8 (Go)
  T14 (Part 4d: NestJS Deep Dive)       ← depends on T10 (TS/Node)

GROUP E: Infrastructure & Operations
  T15 (Part 8: Cloud & Platform Eng)    ← depends on T4 (dist systems)
  T16 (Part 9: Observability)           ← depends on T15 (platform)
  T17 (Part 10: Security Engineering)   ← depends on T4, T6 (payment)

GROUP F: Integration & Validation
  T18 (Part 12: Project Mapping)        ← depends on all above
  T19 (Part 13: Building the Project)   ← depends on T18
  T20 (Part 14: Knowledge Validation)   ← depends on T19

GROUP G: Career-Level
  T21 (Part 15: Staff Engineer)         ← depends on T19 (project exp)
  T22 (Part 16: Principal Engineer)     ← depends on T21
```

---

## Detailed Task Definitions

---

### T1: Part 1 — Knowledge Dependency Graph

**Output**: `CURRICULUM.md` Part 1 (~600 lines)

**Content**:
- Mermaid dependency graph showing ALL concepts and their prerequisites
- Foundation layer: Computer Architecture → OS → Networking
- Language layer: Java, Go, Python, TypeScript (each with runtime internals)
- Framework layer: Spring Boot, NestJS, FastAPI, Chi (each with internals)
- Data layer: PostgreSQL (deepest), Redis, OpenSearch
- Middleware layer: Kafka, Debezium, Schema Registry, Avro
- Infrastructure layer: Docker, Kubernetes, Terraform, AWS
- Observability layer: OTel, Prometheus, Grafana, Loki, Tempo
- Security layer: Cryptography, OAuth2, JWT, PCI DSS, Threat Modeling
- Domain layer: Payment, Ledger, Settlement, Fraud, AML
- System design layer: CAP, Consensus, Sagas, CQRS, Event Sourcing
- Engineering maturity: Staff → Principal thinking

**Structure**: One Mermaid graph with color-coded layers, followed by a narrative walkthrough of each dependency chain.
Each node annotated with: (1) what it is, (2) why it matters for payments, (3) prerequisite nodes.

**Dependencies**: None (this is root).

---

### T2: Part 2 — Complete Learning Roadmap

**Output**: `CURRICULUM.md` Part 2 (~800 lines)

**Content**: Phase 0 → Phase 19 progression.

**Each phase includes**:
- **Goal**: What you will be able to do after this phase
- **Why it matters**: Connection to payment platform
- **Prerequisites**: Links to prior phases
- **Topics** (5–15 per phase): Ordered list with estimated hours
- **Hands-on exercises** (2–4 per phase): Concrete coding tasks
- **Mini project**: A complete small project that exercises the phase's skills
- **Milestone check**: Self-assessment to verify readiness for next phase
- **Common mistakes**: What people get wrong at this stage
- **Estimated duration**: Solo vs part-time

**Phases**:
| # | Phase Name | Duration | Key Focus |
|---|-----------|----------|-----------|
| 0 | Computer Science Essentials | 2-3 weeks | CPU, memory, processes, threads, data structures |
| 1 | Operating Systems & Networking | 3-4 weeks | Scheduling, virtual memory, TCP/IP, HTTP, DNS, TLS |
| 2 | Database Fundamentals | 3-4 weeks | Relational theory, SQL, ACID, indexing, normalization |
| 3 | Java Deep Dive | 4-6 weeks | JVM internals, GC, concurrency, Spring Boot basics |
| 4 | Python Deep Dive | 2-3 weeks | GIL, asyncio, FastAPI basics, data science intro |
| 5 | Go Deep Dive | 2-3 weeks | Goroutines, channels, interfaces, Chi basics |
| 6 | TypeScript + Node.js Deep Dive | 2-3 weeks | Event loop, V8, streams, NestJS basics |
| 7 | Spring Boot Mastery | 4-6 weeks | DI, AOP, transactions, security, testing, production |
| 8 | FastAPI + NestJS + Chi | 3-4 weeks | All three frameworks, request lifecycle, testing |
| 9 | PostgreSQL Internals & Performance | 4-6 weeks | MVCC, WAL, planner, vacuum, replication, tuning |
| 10 | Distributed Systems Theory | 6-8 weeks | CAP, consensus, sagas, CQRS, event sourcing, outbox |
| 11 | Kafka Ecosystem | 4-6 weeks | Kafka internals, consumers, exactly-once, CDC, Schema Registry |
| 12 | Cloud & Platform Engineering | 6-8 weeks | Docker, K8s, Terraform, AWS, GitOps |
| 13 | Observability | 3-4 weeks | OTel, Prometheus, Grafana, Loki, Tempo, SLOs |
| 14 | Security Engineering | 4-6 weeks | OAuth2, JWT, TLS, PCI DSS, threat modeling |
| 15 | Payment Domain Mastery | 6-8 weeks | Ledger, settlement, fraud, AML, reconciliation |
| 16 | Building the Platform | 12-16 weeks | Actual implementation of the payment platform |
| 17 | Production Operations | 4-6 weeks | Deploy, monitor, incident response, scale |
| 18 | Staff Engineer | ongoing | ADRs, capacity planning, platform engineering |
| 19 | Principal Engineer | ongoing | First-principles thinking, technology evaluation |

**Dependencies**: T1 (dependency graph defines the phase structure).

---

### T3: Part 5 — Database Engineering

**Output**: `CURRICULUM.md` Part 5 (~1700 lines)

**Why first in Group B**: The ledger architecture depends on PostgreSQL MVCC, locking, and transactional guarantees. Must understand the database before designing the ledger.

**Content**:

**5.1 Why Database Engineering Matters in Payment Systems**
- Money lives in the database. Corruption = financial loss.
- ACID is non-negotiable for the ledger.
- Performance degrades at scale — must understand internals to fix.

**5.2 PostgreSQL Deep Dive (~1000 lines)**

| Section | Topics Covered |
|---------|---------------|
| Storage Engine | Heap files, pages (8KB), tuples, TOAST, fillfactor |
| WAL Architecture | Write-Ahead Log, LSN, checkpoint, WAL segments, replication slots |
| MVCC Internals | xmin, xmax, cid, snapshot construction, tuple visibility, VACUUM, autovacuum, bloat |
| Query Planner | EXPLAIN, ANALYZE, BUFFERS, cost model, statistics (pg_stats), join strategies (nested loop, hash, merge), genetic query optimizer |
| Index Types | B-tree (structure, page split), Hash, GiST, GIN, BRIN, partial indexes, covering indexes, index-only scans |
| Locking | Row-level (FOR UPDATE, FOR SHARE), table-level, advisory locks, deadlock detection, lock_timeout |
| Transaction Isolation | Read Committed, Repeatable Read, Serializable (SSI), anomalies (dirty read, non-repeatable read, phantom, serialization) |
| Replication | Streaming (physical), Logical (pgoutput, decoding plugins), failover, promotion, timeline, slots |
| Partitioning | Declarative (RANGE, LIST, HASH), partition pruning, pg_partman, sub-partitioning |
| Vacuum & Maintenance | autovacuum tuning (scale factor, thresholds, cost delay), VACUUM FULL, pg_repack, bloated indexes |
| Performance Tuning | shared_buffers, work_mem, effective_cache_size, random_page_cost, max_connections, PgBouncer |
| Backup & PITR | pg_basebackup, WAL archiving, PITR recovery, pgBackRest |
| Monitoring | pg_stat_statements (top queries, plans), pg_stat_activity (blocking), pg_stat_user_tables (seq scans), pg_locks |
| Payment-Specific Patterns | Pessimistic locking for wallet debits, optimistic concurrency (version column), SECURITY DEFINER for ledger procedures, UNIQUE constraints for idempotency, CHECK constraints for non-negative balances |

**5.3 Redis Deep Dive (~400 lines)**

| Section | Topics |
|---------|--------|
| Data Structures | SDS strings, ziplist, quicklist, skiplist, hashtable, intset |
| Persistence | RDB (snapshot), AOF (append-only, fsync policies, rewrite), hybrid |
| Replication & HA | Master-replica, Sentinel (quorum, failover), Cluster (hash slots, resharding) |
| Eviction Policies | LRU, LFU, TTL, volatile vs allkeys |
| Lua Scripting | Atomicity, EVALSHA, script caching, KEYS vs ARGV |
| Payment Use Cases | Rate limiting (token bucket in Lua), idempotency cache (SETNX + TTL), session cache, circuit breaker state |
| Operations | Monitoring (INFO, SLOWLOG), memory management (maxmemory), persistence tuning |

**5.4 OpenSearch Deep Dive (~300 lines)**

| Section | Topics |
|---------|--------|
| Inverted Index | Postings lists, term frequency, position, skip lists, doc values |
| Sharding & Replication | Primary shards, replicas, routing, rebalancing, split brain |
| Query DSL | Match, bool, term, range, aggregations (bucket, metric, pipeline) |
| Payment Use Cases | Transaction search (multi-field), audit log indexing, full-text merchant search |
| Operations | Index lifecycle management (hot-warm-cold-delete), snapshots, ISM policies |

**Exercises**:
1. Coding: Write a `SELECT FOR UPDATE NOWAIT` wallet debit function and test under concurrent load
2. Design: Given 10M transactions/day, design the ledger partitioning strategy
3. Incident: Diagnose a "database is slow" scenario from pg_stat_statements output

**Dependencies**: T1 (dependency graph).

---

### T4: Part 6 — Distributed Systems

**Output**: `CURRICULUM.md` Part 6 (~2000 lines)

**Why this depth**: Payment platforms ARE distributed systems. Every cross-service call is a distributed system problem. Sagas, idempotency, and consistency models are NOT optional knowledge — they're the foundation of payment correctness.

**Content**: 21 sections covering:

| # | Section | Key Topics |
|---|---------|-----------|
| 6.1 | Why Distributed Systems | Payment platform as a distributed system; failure is normal |
| 6.2 | CAP Theorem | CP vs AP for payment flows; consistency during partition |
| 6.3 | Consistency Models | Linearizable, sequential, causal, eventual, read-your-writes, monotonic reads |
| 6.4 | Consensus | Paxos (roles, phases, Multi-Paxos), Raft (leader election, log replication, safety) — with diagrams |
| 6.5 | Leader Election | Bully algorithm, ZooKeeper ephemeral znodes, etcd leases, split-brain prevention |
| 6.6 | Replication | Single-leader, multi-leader, leaderless (Dynamo), synchronous vs asynchronous, read-your-writes |
| 6.7 | Partitioning & Sharding | Key-range, hash-based, consistent hashing, rebalancing, secondary indexes |
| 6.8 | Distributed Transactions | 2PC (coordinator, participants, commit/abort), 3PC, XA, heuristic decisions, coordinator failure |
| 6.9 | Sagas | Orchestration vs Choreography, compensating transactions, retryable vs pivot vs irrevocable steps |
| 6.10 | CQRS | Command model vs Query model, eventual consistency, read model projections |
| 6.11 | Event Sourcing | Event store, replay, snapshots, event versioning, schema evolution |
| 6.12 | Outbox Pattern | Transactional outbox table, CDC relay to Kafka, at-least-once delivery |
| 6.13 | Inbox Pattern | Idempotent consumers, deduplication by message_id, inbox table, processed flag |
| 6.14 | Idempotency | Idempotency keys, idempotency window, stored responses, GET idempotency |
| 6.15 | Retry Strategies | Exponential backoff, jitter, max retries, idempotency requirement for retries, dead letter queue |
| 6.16 | Circuit Breakers | Closed → Open → Half-Open states, failure thresholds, timeout, fallback |
| 6.17 | Bulkheads | Resource isolation, thread pools, connection pools, per-downstream limits |
| 6.18 | Backpressure | TCP receive window, reactive streams, rate limiting, load shedding, admission control |
| 6.19 | Distributed Locks | Redis Redlock (pros/cons), PostgreSQL advisory locks, fencing tokens, TTL |
| 6.20 | Lease & Heartbeat | Lease duration, renewal, expiration, stale lease detection |
| 6.21 | Failure Injection | Chaos engineering principles, fault injection, Game Days |

**Payment Platform Examples**: Each section includes at least one payment-specific scenario:
- CAP: Partition between Payment and Fraud → how to handle (CP — block the payment)
- Sagas: Payment state machine → compensation on failure → reversal journal entry
- Outbox: PaymentCompleted event → outbox table → Debezium → Kafka
- Idempotency: Duplicate payment request → idempotency key → stored response → rejected duplicate

**Exercises**:
1. Design: Draw the saga for "payment with fraud check, fee calculation, and ledger write"
2. Coding: Implement an idempotent consumer in any language with inbox deduplication
3. Incident: A circuit breaker opened during peak — trace the cascade failure from logs

**Dependencies**: T3 (database transactions = foundation for distributed transactions).

---

### T5: Part 7 — Kafka Ecosystem

**Output**: `CURRICULUM.md` Part 7 (~1600 lines)

**Why this is a separate part**: Kafka is the central nervous system of the platform. Every domain event flows through it. Understanding Kafka internals is critical for correctness, ordering, and recovery.

**Content**: 15 sections:

| # | Section | Key Topics |
|---|---------|-----------|
| 7.1 | Why Kafka | Payment event backbone; immutable log; replay; decoupling |
| 7.2 | Architecture | Brokers, topics, partitions, segments, log directories, controller |
| 7.3 | Producer Internals | Batching (linger.ms, batch.size), compression, acks (0, 1, all), idempotent producer, transactions |
| 7.4 | Consumer Internals | Consumer groups, partition assignment (Range, RoundRobin, Sticky, Cooperative Sticky), offset commit (auto vs manual), rebalancing, heartbeat |
| 7.5 | Partitioning Strategy | Key selection for ordering (account_id, merchant_id), custom partitioners, partition count trade-offs |
| 7.6 | Exactly-Once | Idempotent producer + transactional API, consumer EOS (read_committed), isolation level |
| 7.7 | Log Compaction | Compaction algorithm, cleanup policy, tombstone records, snapshot-based recovery |
| 7.8 | Retention & Cleanup | Time-based, size-based, segment-based deletion, log.retention.hours, log.segment.bytes |
| 7.9 | Kafka Connect | Source connectors, sink connectors, transforms (SMT), Debezium connectors, error handling |
| 7.10 | Schema Registry | Avro serialization, schema ID, compatibility modes (BACKWARD, FORWARD, FULL), subject naming |
| 7.11 | Debezium CDC | Outbox pattern with EventRouter SMT, pgoutput plugin, snapshot mode, schema changes |
| 7.12 | Kafka Streams vs Consumer API | State stores, KTables, KStreams, windowing, exactly-once, when to use each |
| 7.13 | Operations | Broker monitoring (JMX), consumer lag (burrow, AKHQ), partition reassignment, ISR shrinkage, unclean leader election |
| 7.14 | Failure Recovery | Broker failure (ISR, leader election, min.insync.replicas), consumer group rebalance, MirrorMaker 2 for DR |
| 7.15 | Payment Platform Kafka Architecture | Topic catalog from the platform, partition key strategy per topic, consumer group map, DLQ topics |

**Payment Platform Connection**: Direct mapping to the platform's topic catalog (from `docs/cross-cutting/events/event-catalog.md`).

**Exercises**:
1. Coding: Implement an idempotent Kafka producer with outbox pattern and CDC
2. Design: Given payment events requiring strict per-account ordering, design the partition key strategy
3. Incident: Consumer lag is 10M messages — diagnose and design recovery

**Dependencies**: T4 (distributed systems → consensus, replication), T3 (database → outbox table).

---

### T6: Part 11 — Payment Domain Mastery

**Output**: `CURRICULUM.md` Part 11 (~2500 lines)

**Why this depth**: This is THE business domain. Every line of code in the platform serves one of these 20 sections. Understanding the domain is understanding what to build.

**Content**: 20 sections:

| # | Section | Key Topics |
|---|---------|-----------|
| 11.1 | Payment Industry Overview | Four-party model, acquiring, issuing, PSPs, payment gateways, card networks |
| 11.2 | How Card Networks Work | Authorization (ISO 8583), clearing (batch), settlement (ACH/wire), interchange |
| 11.3 | How Bank Transfers Work | SWIFT (MT103, MT202), ACH (NACHA), SEPA (ISO 20022), RTGS (real-time gross settlement) |
| 11.4 | Digital Wallets | Stored value, top-up flows, withdrawal flows, balance management, regulatory reserves |
| 11.5 | Double-Entry Ledger | Chart of accounts, debit/credit rules, journal entries, trial balance, accounting equation |
| 11.6 | Ledger Architecture | Immutable (append-only), hash-chained, audit trail, partitioning strategy, balance projection |
| 11.7 | Wallet Architecture | Available/pending/frozen balances, balance holds, optimistic concurrency, version columns |
| 11.8 | Payment State Machine | INITIATED → VALIDATING → AUTHORIZED → EXECUTING → COMPLETED/FAILED, transitions, guards, compensation |
| 11.9 | Settlement | EOD batch, merchant aggregation, net calculation, payout file generation, bank integration |
| 11.10 | Reconciliation | Three-way match (wallet ↔ ledger ↔ bank), exception handling, adjustment entries |
| 11.11 | Treasury | Liquidity monitoring, reserve management, inter-bank transfers, maker-checker, funding accounts |
| 11.12 | FX & Multi-Currency | Exchange rates, FX quotes, cross-currency journal entries, FX position management |
| 11.13 | Fraud Detection | Rules engine, velocity checks (sliding window), ML scoring, feature engineering, model lifecycle |
| 11.14 | Anti-Money Laundering | KYC (tiers), customer due diligence, transaction monitoring, PEP screening, SAR filing |
| 11.15 | Disputes & Chargebacks | Lifecycle, reason codes, evidence collection, representment, pre-arbitration, liability |
| 11.16 | Idempotency in Payments | Idempotency key lifecycle, storage, cache, replay detection, exactly-once delivery guarantee |
| 11.17 | Fee Calculation | Tiered, percentage, flat, interchange+, blended, markup, cashback, promotions, tax |
| 11.18 | Payment Methods | Card (PAN, CVV, expiry), bank account (routing + account), QR (static/dynamic), wallet, token |
| 11.19 | Notification Delivery | Push (FCM/APNs), email (SMTP + templates), SMS, webhooks (delivery + retry + signatures) |
| 11.20 | Audit Trail | Immutable append-only log, chain of custody, hash chaining, 7-year retention, regulatory access |

**Exercises**:
1. Design: Given a merchant payment with fee split, write the complete double-entry journal entry
2. Coding: Implement the payment state machine with all transitions and guards
3. Design: Given a cross-currency payment (VND → USD), write all journal entries including FX margin
4. Incident: A settlement batch failed — diagnose from partial ledger and bank statements

**Dependencies**: T3 (database — ledger is in PostgreSQL), T4 (distributed systems — sagas for payment state machine).

---

### T7: Part 3a — Java Language Track

**Output**: `CURRICULUM.md` Part 3, Java section (~900 lines)

**Content**:

| Section | Topics |
|---------|--------|
| 3a.1 | History & Philosophy | Oak → Java 1.0 → Java 21; write once run anywhere; JVM ecosystem; why Java for financial core |
| 3a.2 | Language Fundamentals | Syntax, primitives vs objects, control flow, classes, interfaces, records, enums, sealed classes, pattern matching |
| 3a.3 | Type System | Static, strong, nominal; generics (type erasure, wildcards, bounded types); type inference (var, diamond) |
| 3a.4 | Memory Model | Stack (primitives, references), heap (objects), method area (class metadata), string pool |
| 3a.5 | Concurrency Model | Thread (Thread, Runnable), ExecutorService, Future, CompletableFuture, virtual threads (Project Loom), Structured Concurrency |
| 3a.6 | JVM Internals | Class loading (Bootstrap → Extension → Application), bytecode verification, JIT compilation (C1, C2, tiered), GC (Serial, Parallel, G1, ZGC, Shenandoah), GC tuning (pause time goals, heap sizing) |
| 3a.7 | Build Systems | Maven (POM, lifecycle, plugins, dependency scopes, multi-module), Gradle (Groovy/Kotlin DSL, task graph, incremental builds) |
| 3a.8 | Testing | JUnit 5, Mockito, Testcontainers, Spring Boot Test, test slicing |
| 3a.9 | Debugging & Profiling | jstack (thread dumps), jmap (heap dumps), jstat (GC stats), VisualVM, JFR (Java Flight Recorder), async-profiler |
| 3a.10 | Production Practices | Structured logging (JSON), Micrometer metrics, JVM flags for containers (-XX:MaxRAMPercentage), graceful shutdown |

**Exercises**:
1. Beginner: Implement a thread-safe idempotency key store using ConcurrentHashMap
2. Intermediate: Profile a Spring Boot application under load; identify GC bottlenecks
3. Advanced: Implement a virtual-thread-based payment processing pipeline; compare with platform threads

**Dependencies**: T1 (dependency graph), T2 (roadmap context).

---

### T8: Part 3b — Go Language Track

**Output**: `CURRICULUM.md` Part 3, Go section (~600 lines)

**Content**:

| Section | Topics |
|---------|--------|
| 3b.1 | History & Philosophy | Google internal needs → Go 1.0 → Go 1.22; simplicity, concurrency, fast compile; why Go for batch/settlement |
| 3b.2 | Language Fundamentals | Syntax, zero values, slices vs arrays, maps, structs, methods, interfaces (implicit), errors (not exceptions), defer/panic/recover |
| 3b.3 | Type System | Static, strong, structural (interfaces); type embedding (not inheritance); generics (type parameters, constraints) |
| 3b.4 | Memory Model | Stack vs heap, escape analysis, value vs pointer semantics |
| 3b.5 | Concurrency Model | Goroutines (M:N scheduling, GOMAXPROCS), channels (buffered/unbuffered, select, fan-in/fan-out), sync package (Mutex, WaitGroup, Once), context (cancellation, deadlines) |
| 3b.6 | Go Runtime Internals | GMP scheduler (Goroutine, Machine, Processor), work stealing, netpoller, GC (concurrent mark-sweep, write barriers, GC pacer) |
| 3b.7 | Build System | go mod (module, replace, vendor), go build (cross-compilation, ldflags), go generate |
| 3b.8 | Testing | Table-driven tests, subtests, test helpers, benchmarks, fuzz testing, race detector |
| 3b.9 | Debugging & Profiling | pprof (CPU, heap, goroutine, mutex, block), trace, delve |
| 3b.10 | Production Practices | Structured logging (slog), metrics (promhttp), graceful shutdown (signal handling), connection pooling |

**Exercises**:
1. Beginner: Implement a goroutine pool that processes settlement batches concurrently
2. Intermediate: Use pprof to find and fix a memory leak in a Kafka consumer
3. Advanced: Implement a work-stealing scheduler simulation to understand Go's GMP model

**Dependencies**: T1, T2.

---

### T9: Part 3c — Python Language Track

**Output**: `CURRICULUM.md` Part 3, Python section (~500 lines)

**Content**:

| Section | Topics |
|---------|--------|
| 3c.1 | History & Philosophy | ABC → Python 1 → Python 3; readability, batteries included; why Python for fraud/ML |
| 3c.2 | Language Fundamentals | Dynamic typing, indentation, list/dict/set comprehension, decorators, generators, context managers, async/await |
| 3c.3 | Type System | Dynamic, strong, duck typing; type hints (mypy, Protocol, TypedDict, Literal, Union) |
| 3c.4 | Memory Model | Reference counting, cyclic GC, small object allocator (pymalloc), arenas, pools, blocks |
| 3c.5 | Concurrency Model | GIL (Global Interpreter Lock), threading (I/O-bound), multiprocessing (CPU-bound), asyncio (event loop, coroutines, tasks), concurrent.futures |
| 3c.6 | CPython Internals | Bytecode compilation, stack-based VM, frame objects, ceval loop, GIL acquisition/release |
| 3c.7 | Package Management | pip, virtualenv, poetry, uv; pyproject.toml; dependency resolution |
| 3c.8 | Testing | pytest (fixtures, parametrize, markers), unittest.mock, FastAPI TestClient |
| 3c.9 | Debugging & Profiling | pdb, cProfile, line_profiler, memory_profiler, tracemalloc |
| 3c.10 | Production Practices | Structured logging (structlog), metrics (prometheus_client), uvicorn workers, graceful shutdown |

**Exercises**:
1. Beginner: Implement a fraud scoring function using numpy with type hints and tests
2. Intermediate: Profile a FastAPI endpoint under load; optimize the hot path
3. Advanced: Implement a simplified version of asyncio's event loop

**Dependencies**: T1, T2.

---

### T10: Part 3d — TypeScript + Node.js Track

**Output**: `CURRICULUM.md` Part 3, TypeScript/Node section (~500 lines)

**Content**:

| Section | Topics |
|---------|--------|
| 3d.1 | History & Philosophy | JavaScript (Brendan Eich, 10 days) → Node.js (Ryan Dahl) → TypeScript; event-driven I/O; why Node.js for BFF/notifications |
| 3d.2 | TypeScript Fundamentals | Type annotations, interfaces vs types, union/intersection, generics, mapped types, conditional types, template literal types |
| 3d.3 | Type System | Structural, gradual; TypeScript compiler (checker, emitter), declaration files, strict mode |
| 3d.4 | Runtime Internals | V8 (Ignition interpreter, TurboFan compiler, hidden classes, inline caching); libuv (thread pool, I/O polling); event loop (timers → I/O callbacks → idle/prepare → poll → check → close) |
| 3d.5 | Concurrency Model | Single-threaded event loop, async/await (microtask queue vs macrotask queue), Worker threads, cluster module |
| 3d.6 | Package Management | npm (package.json, lockfile, workspaces), pnpm (symlinked node_modules, content-addressable storage) |
| 3d.7 | Streams | Readable, Writable, Transform, Duplex; backpressure; piping; pipeline |
| 3d.8 | Testing | Vitest, supertest, testcontainers-node, mocking (vi.fn) |
| 3d.9 | Debugging & Profiling | Node.js inspector (--inspect), Chrome DevTools, clinic.js, 0x, autocannon |
| 3d.10 | Production Practices | Structured logging (pino), PM2, graceful shutdown, cluster mode, Docker |

**Exercises**:
1. Beginner: Implement a webhook delivery service with TypeScript types and retry logic
2. Intermediate: Profile a Kafka consumer under load; identify event loop blockage
3. Advanced: Implement a simplified event loop simulation to understand microtask vs macrotask ordering

**Dependencies**: T1, T2.

---

### T11: Part 4a — Spring Boot Deep Dive

**Output**: `CURRICULUM.md` Part 4, Spring Boot section (~1000 lines)

**Content**:

| Section | Topics |
|---------|--------|
| 4a.1 | Why Spring Boot | Convention over configuration, auto-configuration, embedded server, production-ready (Actuator) |
| 4a.2 | Internal Architecture | ApplicationContext (BeanFactory, BeanDefinition, BeanPostProcessor, lifecycle), auto-configuration (spring.factories, @Conditional), embedded Tomcat/Netty |
| 4a.3 | Request Lifecycle | Filter chain → DispatcherServlet → HandlerMapping → HandlerAdapter → Controller → Response; interceptor, argument resolver, message converter |
| 4a.4 | Dependency Injection | @Autowired, @Qualifier, @Primary, constructor injection (preferred), prototype vs singleton vs request scopes |
| 4a.5 | AOP & Transactions | Proxy-based AOP, @Transactional, propagation (REQUIRED, REQUIRES_NEW, NESTED), rollback rules, transaction manager, JPA flush order |
| 4a.6 | Validation | Bean Validation (@NotNull, @Valid, @Validated), custom validators, groups, method validation |
| 4a.7 | Security (Spring Security) | Security filter chain, AuthenticationManager, ProviderManager, SecurityContext, @PreAuthorize, method security, JWT/OAuth2 integration |
| 4a.8 | Database (Spring Data JPA) | Repository pattern, query derivation, @Query (JPQL, native), Specifications, EntityGraph, N+1 prevention, pessimistic locking (@Lock) |
| 4a.9 | Kafka (Spring Kafka) | @KafkaListener, KafkaTemplate, error handlers (SeekToCurrent, DeadLetterPublishingRecoverer), transaction synchronization |
| 4a.10 | Testing | @SpringBootTest, @WebMvcTest, @DataJpaTest, @MockBean, Testcontainers, slice tests |
| 4a.11 | Performance Tuning | Connection pooling (HikariCP), JPA batch operations, query plan caching, lazy vs eager, caching (@Cacheable) |
| 4a.12 | Production Deployment | Fat JAR vs layers, graceful shutdown, health indicators, metrics (Micrometer), Docker optimization |
| 4a.13 | Failure Modes | OutOfMemoryError (heap dump analysis), connection pool exhaustion, transaction timeout, deadlock detection |
| 4a.14 | Build a Simplified Version | Implement a mini DI container, a mini DispatcherServlet, a mini @Transactional using JDK dynamic proxies |

**Exercises**:
1. Coding: Implement the Financial Core ledger service with @Transactional journal entry writes
2. Design: Design the security filter chain for the payment platform (JWT + API key + RBAC)
3. Incident: Diagnose a "transaction taking too long" alert — connection pool exhausted by long-running queries

**Dependencies**: T7 (Java track).

---

### T12: Part 4b — FastAPI Deep Dive

**Output**: `CURRICULUM.md` Part 4, FastAPI section (~500 lines)

**Content**:

| Section | Topics |
|---------|--------|
| 4b.1 | Why FastAPI | Async-native, auto OpenAPI, Pydantic validation, Starlette + Pydantic = FastAPI |
| 4b.2 | Internal Architecture | Starlette (ASGI app, request/response cycle, middleware stack, lifespan), Pydantic (BaseModel, validation, JSON Schema) |
| 4b.3 | Request Lifecycle | ASGI scope → middleware chain → router matching → dependency resolution → path operation → response |
| 4b.4 | Dependency Injection | Depends(), dependency overrides, yield dependencies, sub-dependencies, scoped dependencies |
| 4b.5 | Middleware | CORS, GZip, custom middleware, dependency-based middleware vs ASGI middleware |
| 4b.6 | Validation with Pydantic v2 | Model validation, field validators, model_validator, discriminated unions, JSON Schema generation, OpenAPI integration |
| 4b.7 | Database Integration | SQLAlchemy async, session management, repository pattern, connection pooling |
| 4b.8 | Testing | TestClient (sync), httpx.AsyncClient (async), dependency overrides for mocking, pytest fixtures |
| 4b.9 | Performance | Uvicorn workers, gunicorn + uvicorn workers, async database access, connection pooling, caching |
| 4b.10 | Production | Gunicorn + UvicornWorker, health checks, graceful shutdown, Docker, metrics |
| 4b.11 | Build a Simplified Version | Implement a routing tree, a dependency injection system, a request/response cycle |

**Dependencies**: T9 (Python track).

---

### T13: Part 4c — Chi Deep Dive (Go)

**Output**: `CURRICULUM.md` Part 4, Chi section (~350 lines)

**Content**: Similar structure to FastAPI but Go-idiomatic: Radix tree routing, middleware chains, context-based request handling, sqlc integration, graceful shutdown.

**Dependencies**: T8 (Go track).

---

### T14: Part 4d — NestJS Deep Dive (Node.js)

**Output**: `CURRICULUM.md` Part 4, NestJS section (~500 lines)

**Content**: Angular-inspired architecture, modules/controllers/providers, decorators, dependency injection container, guards/interceptors/pipes/filters, TypeORM/Prisma integration, request lifecycle.

**Dependencies**: T10 (TypeScript/Node track).

---

### T15: Part 8 — Cloud & Platform Engineering

**Output**: `CURRICULUM.md` Part 8 (~1800 lines)

**Content**: Docker deep dive (namespaces, cgroups, unionfs, layers, networking), Docker Compose, Kubernetes (control plane, pods, deployments, services, ingress, configmaps/secrets), Terraform (HCL, modules, state, workspaces), AWS services (EKS, Aurora, MSK, ElastiCache, OpenSearch, S3, IAM), multi-region, cost optimization.

**Dependencies**: T4 (distributed systems), T5 (Kafka on K8s).

---

### T16: Part 9 — Observability

**Output**: `CURRICULUM.md` Part 9 (~1200 lines)

**Content**: Three pillars (logs/metrics/traces), structured logging, RED method, USE method, Prometheus (TSDB, PromQL, recording rules, alerting rules), Grafana (dashboards, panels, variables), OTel (traces, spans, context propagation, SDK, Collector, exporters), Tempo (trace storage), Loki (log aggregation, LogQL), SLI/SLO/SLA (error budgets, burn rate alerts, multi-window multi-burn-rate), incident response.

**Dependencies**: T15 (platform), T4 (distributed tracing context).

---

### T17: Part 10 — Security Engineering

**Output**: `CURRICULUM.md` Part 10 (~1500 lines)

**Content**: Cryptography (AES, RSA, ECDSA, SHA-256, key exchange), TLS 1.3 (handshake, certificate transparency, mTLS), OAuth2/OIDC (authorization code, client credentials, PKCE, token introspection), JWT (header/payload/signature, RS256 vs HS256, key rotation, JWKS endpoint), RBAC/ABAC, API security (rate limiting, input validation), secrets (Vault, KMS, Secrets Manager), encryption (at-rest, in-transit, envelope), PCI DSS (12 requirements mapped to platform), threat modeling (STRIDE, attack trees), OWASP Top 10, security in CI/CD (SAST, DAST, dependency scan, container scan).

**Dependencies**: T4 (distributed systems), T6 (payment domain), T4 (mTLS in service mesh).

---

### T18: Part 12 — Project Mapping

**Output**: `CURRICULUM.md` Part 12 (~400 lines)

**Content**: Two mapping tables:
1. **Technology → Context**: Every technology in the stack → which bounded context uses it → what it does there
2. **Curriculum → Platform Phase**: Which curriculum parts map to which platform build phases

**Dependencies**: T3-T17 (all domain knowledge).

---

### T19: Part 13 — Building the Project

**Output**: `CURRICULUM.md` Part 13 (~800 lines)

**Content**: Phase-by-phase build guide mapped to the 9-phase minimum workflow:
- Knowledge prerequisites (links to curriculum parts)
- Architecture deliverables
- Coding tasks for each service
- Infrastructure tasks
- Testing milestones
- Go-live criteria

**Dependencies**: T18 (project mapping).

---

### T20: Part 14 — Knowledge Validation

**Output**: `CURRICULUM.md` Part 14 (~800 lines)

**Content**: Per curriculum phase:
- Quiz (10 MC + 5 short answer)
- Coding Challenge (implement something specific)
- Design Challenge (architecture diagram + ADR)
- Production Incident Simulation (logs, metrics, traces → diagnose)
- Architecture Review (critique a flawed design)

**Dependencies**: T19 (know the phases to validate against).

---

### T21: Part 15 — Staff Engineer Level

**Output**: `CURRICULUM.md` Part 15 (~1000 lines)

**Content**: Trade-off framework, ADR writing, capacity planning, cost modeling, scaling strategy, multi-region architecture, platform engineering, technical strategy (build vs buy), mentoring, cross-team architecture governance, production readiness reviews.

**Dependencies**: T19 (project experience).

---

### T22: Part 16 — Principal Engineer Level

**Output**: `CURRICULUM.md` Part 16 (~800 lines)

**Content**: First-principles reasoning, mental models, technology evaluation frameworks, challenging architecture decisions, system review methodology, evolving systems over years (strangler fig, incremental migration), how Stripe/PayPal/Wise/Uber/Amazon/Netflix/Google approach architecture, reading list.

**Dependencies**: T21 (Staff level).

---

## Execution Order & Concurrency

```
Phase 1: Foundation (T1 → T2)
  ↓
Phase 2: Core Domain (T3 → T4 → T5 → T6)  [sequential — each builds on prior]
  ↓
Phase 3: Languages (T7, T8, T9, T10)        [all 4 can be written in parallel]
  ↓
Phase 4: Frameworks (T11, T12, T13, T14)    [each depends on its language track]
  ↓
Phase 5: Infrastructure (T15 → T16 → T17)    [sequential within group]
  ↓
Phase 6: Integration (T18 → T19 → T20)       [sequential]
  ↓
Phase 7: Senior (T21 → T22)                   [sequential]
```

**Parallel opportunities**: T7-T10 (4 language tracks), T11-T14 (4 framework tracks — but only after their language tracks).

## File Writing Strategy

All 22 tasks write to the same file: `CURRICULUM.md`. Each task appends its section.

Task writing order within the file:
1. Part 1 → Part 2 → Part 5 → Part 6 → Part 7 → Part 11 (core domain first)
2. Part 3a-3d (languages)
3. Part 4a-4d (frameworks)
4. Part 8 → Part 9 → Part 10 (infrastructure)
5. Part 12 → Part 13 → Part 14 (integration)
6. Part 15 → Part 16 (senior)
7. Final: Table of Contents (appended at top via re-write)

## Task Completion Criteria

| Task | Completion Criteria |
|------|-------------------|
| T1 | Dependency graph drawn, all nodes connected, 5+ payment platform annotations |
| T2 | All 20 phases defined with goal, topics, exercises, mini project, duration |
| T3 | PostgreSQL: 10+ internals sections with code examples; Redis: 5+; OpenSearch: 3+ |
| T4 | All 21 sections with at least one payment platform example each |
| T5 | All 15 sections with topic catalog mapping |
| T6 | All 20 sections, 5+ exercises |
| T7-T10 | All 10 sections per language, 3 exercises each (beginner, intermediate, advanced) |
| T11-T14 | All sections, simplified version exercise, payment-specific examples |
| T15-T17 | All sections, exercises, payment platform examples |
| T18-T20 | Complete mapping tables, validation items per phase |
| T21-T22 | All sections, company architecture analyses, reading list |
