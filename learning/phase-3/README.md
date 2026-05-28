# Phase 3 — Java Deep Dive

> **Duration**: 4-6 weeks (full-time) | **Prerequisites**: Phase 2 (Database Fundamentals)
>
> **Goal**: Write production-grade Java services, understand JVM internals (class loading, JIT compilation, GC algorithms), reason about concurrency with threads, executors, and virtual threads, and use Maven/Gradle for builds and JUnit/Mockito for testing.
>
> **Why this matters for the payment platform**: Java + Spring Boot is the primary language for ALL core financial services — Financial Core (Ledger+Wallet), Payment Orchestrator, Refund, FX, and Treasury. These services handle ACID transactions via JPA, enforce RBAC via Spring Security, and process 10,000+ payment requests per second. Understanding the JVM is understanding the runtime that processes every VND in the system.

## Learning Objectives

1. Write idiomatic Java 21 code: records, sealed classes, pattern matching, virtual threads
2. Implement thread-safe data structures with `synchronized`, `Lock`, and `Atomic*` classes
3. Use `ExecutorService`, `CompletableFuture`, and virtual threads for concurrent processing
4. Explain JVM architecture: class loading, bytecode verification, JIT compilation tiers
5. Select and tune GC algorithms (Serial/Parallel/G1/ZGC) for payment workloads
6. Use jstack, jmap, JFR, and async-profiler for debugging and profiling
7. Set up a Maven multi-module project with JUnit 5, Mockito, and Testcontainers

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-3 | Module 01 | Java 21 syntax, records, sealed classes, pattern matching, OOP | 8h |
| 4-6 | Module 01 | Collections, Generics, Streams, lambdas, Exception handling, I/O/NIO | 10h |
| 7-10 | Module 02 | Threads, synchronized, volatile, wait/notify, Lock, Condition, atomics | 10h |
| 11-13 | Module 02 | ExecutorService, CompletableFuture, ForkJoinPool, Virtual Threads | 10h |
| 14-16 | Module 03 | JVM architecture, class loading, bytecode, JIT (C1/C2/tiered) | 8h |
| 17-19 | Module 03 | GC algorithms (Serial/Parallel/G1/ZGC), GC tuning, heap dump analysis | 8h |
| 20-21 | Module 04 | Maven (POM, lifecycle, multi-module) + Gradle basics | 6h |
| 22-24 | Module 04 | JUnit 5, Mockito, AssertJ, Testcontainers, Spring Boot Test | 8h |
| 25-28 | Mini Project | Thread-safe Idempotency Store | 12h |

## Setup

```bash
# Install Java 21
# macOS: brew install openjdk@21
# Ubuntu: sudo apt install openjdk-21-jdk
# Verify: java --version  # Should show 21.x

# Install Maven
# macOS: brew install maven
# Ubuntu: sudo apt install maven
# Verify: mvn --version

# Recommended: Use SDKMAN for Java version management
curl -s "https://get.sdkman.io" | bash
sdk install java 21.0.4-tem
```

## Resources

- **Book**: "Effective Java" (Bloch) — THE Java book. Read cover to cover.
- **Book**: "Java Concurrency in Practice" (Goetz)
- **Book**: "Optimizing Java" (Evans, Gough, Newland)
- **Doc**: JEP 444 (Virtual Threads), JEP 439 (ZGC)
- **Tool**: JFR (Java Flight Recorder), async-profiler, VisualVM

## Connection to Phase 7

Phase 7 (Spring Boot Mastery) builds directly on:
- Generics → Spring's type-safe dependency injection
- Annotations → Spring's @Autowired, @Transactional, @PreAuthorize
- ExecutorService → Spring's @Async, TaskExecutor
- JVM tuning → Spring Boot container configuration
- Testing → Spring Boot Test, @DataJpaTest, @WebMvcTest
