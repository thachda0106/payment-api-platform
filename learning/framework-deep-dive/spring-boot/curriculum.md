# Spring Boot: Complete Curriculum Roadmap

## Learning Objectives

### By Phase 0 Completion (Architecture)
- Evaluate any project structure against team size and growth trajectory
- Choose between layered, feature-based, modular monolith, hexagonal, and clean architecture
- Apply DDD strategic patterns to define bounded contexts
- Design aggregates, domain events, and repositories correctly
- Explain when CQRS and event sourcing add value vs complexity
- Plan architecture evolution paths

### By Phase 1 Completion (Runtime)
- Diagnose thread starvation and connection pool exhaustion in production
- Profile JVM memory and identify leak sources from heap dumps
- Choose between platform threads, virtual threads, and reactive programming
- Implement distributed tracing with proper context propagation
- Read JVM GC logs and tune garbage collection

### By Phase 2 Completion (Framework Core)
- Trace a request from Socket to Controller through framework internals
- Explain Spring's bean lifecycle, including post-processors and AOP proxies
- Debug auto-configuration failures by reading condition evaluation reports
- Understand how `@Transactional` actually works (proxy-based AOP)
- Extend Spring Boot with custom starters, auto-configuration, and conditionals

### By Phase 3 Completion (Application Architecture)
- Design rich domain models that protect invariants
- Apply the Thin Controller pattern and avoid service bloat
- Optimize repository queries for production workloads
- Handle distributed transactions with sagas and outbox patterns

### By Phase 4 Completion (Production)
- Build comprehensive observability with OpenTelemetry
- Create Service Level Objectives (SLOs) and error budgets
- Run load tests and interpret flame graphs
- Debug production failures from logs, traces, and heap dumps

### By Phase 5 Completion (Source Code Reading)
- Navigate Spring Framework's 1M+ lines of code efficiently
- Understand the internal architecture of DI container, AOP, and transaction management
- Contribute to Spring Framework with confidence

### By Phase 6 Completion (Staff Engineer Thinking)
- Make architecture decisions with documented trade-offs
- Evaluate build vs buy for infrastructure components
- Choose between monolith, modular monolith, and microservices
- Design team structures aligned with system architecture (Conway's Law)
- Become framework-agnostic: evaluate any framework from first principles

---

## Session Schedule (28 Sessions)

### PHASE 0: Architecture & Source Structure

| Session | Topic | Duration |
|---------|-------|----------|
| 01 | Architecture Overview & Project Structures | 3-4 hours |
| 02 | Layered Architecture Deep Dive | 3-4 hours |
| 03 | Feature-Based & Modular Monolith Architecture | 3-4 hours |
| 04 | Domain-Driven Design: Strategic Patterns | 4-5 hours |
| 05 | Domain-Driven Design: Tactical Patterns | 4-5 hours |
| 06 | Hexagonal Architecture (Ports & Adapters) | 3-4 hours |
| 07 | Clean Architecture & Vertical Slice Architecture | 3-4 hours |
| 08 | CQRS, Event-Driven Architecture & Architecture Evolution | 4-5 hours |

### PHASE 1: Runtime Foundation

| Session | Topic | Duration |
|---------|-------|----------|
| 09 | JVM Concurrency Model & Thread Pools | 4-5 hours |
| 10 | Virtual Threads & Project Loom Deep Dive | 3-4 hours |
| 11 | JVM Memory Model & Garbage Collection | 4-5 hours |
| 12 | Context Propagation & Observability Foundation | 3-4 hours |

### PHASE 2: Framework Core

| Session | Topic | Duration |
|---------|-------|----------|
| 13 | Spring Boot Bootstrap & Auto-Configuration Internals | 4-5 hours |
| 14 | Application Context & Bean Lifecycle | 4-5 hours |
| 15 | Dependency Injection & IoC Container Internals | 4-5 hours |
| 16 | HTTP Layer: Embedded Tomcat, DispatcherServlet, Request Processing | 4-5 hours |
| 17 | Middleware Pipeline: Filters, Interceptors, AOP | 3-4 hours |
| 18 | Validation, Serialization & Error Handling Architecture | 3-4 hours |

### PHASE 3: Application Architecture

| Session | Topic | Duration |
|---------|-------|----------|
| 19 | Controller & Service Layer Architecture | 3-4 hours |
| 20 | Repository Layer, Transactions & Persistence | 4-5 hours |
| 21 | Domain Layer Design (Rich vs Anemic, Aggregates, Domain Events) | 4-5 hours |

### PHASE 4: Production Deep Dive

| Session | Topic | Duration |
|---------|-------|----------|
| 22 | Production Failure Scenarios & Debugging | 4-5 hours |
| 23 | Observability: Logging, Metrics, Tracing | 4-5 hours |
| 24 | Performance Analysis & Optimization | 4-5 hours |

### PHASE 5: Source Code Reading Mastery

| Session | Topic | Duration |
|---------|-------|----------|
| 25 | Spring Boot & Spring Framework Source Code Reading Guide | 4-5 hours |

### PHASE 6: Staff/Principal Engineer Thinking

| Session | Topic | Duration |
|---------|-------|----------|
| 26 | Architecture Decision-Making for Staff Engineers | 3-4 hours |
| 27 | Build vs Buy, Monolith vs Microservices, Organizational Design | 3-4 hours |
| 28 | Framework Mastery: Becoming Framework-Agnostic | 3-4 hours |

---

## Architecture Evolution Roadmap

This is the realistic path most successful systems follow:

```
Stage 1: CRUD Monolith
├── 1-3 engineers, 10-50 tables
├── Packages by layer: controller/service/repository
├── Single deployment unit
├── Works until: 5+ engineers, 100+ tables, team conflicts
│
↓ Migration trigger: Merge conflicts, unclear ownership, slow deploys
│
Stage 2: Package by Feature
├── 3-8 engineers, 50-150 tables
├── Packages: users/, orders/, payments/
├── Layers inside features
├── Works until: 15+ engineers, cross-cutting concerns, tight coupling
│
↓ Migration trigger: Features coupling, cannot deploy independently, shared code explosion
│
Stage 3: Modular Monolith
├── 8-20 engineers, 100-300 tables
├── Gradle/Maven modules with explicit APIs
├── Compile-time dependency enforcement
├── Works until: 40+ engineers, scaling bottlenecks, team autonomy needs
│
↓ Migration trigger: Need independent scaling, team autonomy, different tech stacks
│
Stage 4: DDD Strategic Design
├── 15-50 engineers
├── Bounded contexts mapped to modules/services
├── Domain events for cross-context communication
├── Works until: 100+ engineers, organizational Conway's Law pressure
│
↓ Migration trigger: Org structure demands independent deployables
│
Stage 5: Service-Oriented / Microservices
├── 30-200+ engineers
├── Bounded contexts become services
├── Event-driven communication between services
├── Independent deployment, scaling, tech stacks
│
↓ Ongoing: Continuous architecture evolution
```

### Key Insight

Most companies should STOP at Stage 3 or 4. **Modular monoliths with DDD boundaries deliver 90% of microservices benefits at 10% of the complexity cost.** Microservices solve organizational scaling problems, not technical ones. If you have 8 engineers, you don't need microservices.

---

## Source Code Reading Roadmap

### Level 1: Spring Boot (Start Here)
```
spring-boot/
├── spring-boot-autoconfigure/     ← 95% of what you need initially
│   └── src/main/java/org/springframework/boot/autoconfigure/
│       ├── AutoConfiguration.java          ← Core annotation
│       ├── condition/                      ← @Conditional* annotations
│       ├── web/servlet/                    ← WebMvc auto-config
│       ├── jdbc/                           ← DataSource auto-config
│       ├── orm/jpa/                        ← JPA/Hibernate auto-config
│       └── task/                           ← @Async, scheduling
│
├── spring-boot/
│   └── src/main/java/org/springframework/boot/
│       ├── SpringApplication.java          ← Bootstrap (READ THIS FIRST)
│       ├── SpringBootConfiguration.java
│       └── context/                        ← ApplicationContext setup
```

### Level 2: Spring Framework Core
```
spring-framework/
├── spring-core/
│   └── src/main/java/org/springframework/
│       ├── core/io/                        ← Resource abstraction
│       └── core/annotation/                ← Annotation utils
│
├── spring-beans/
│   └── src/main/java/org/springframework/beans/factory/
│       ├── BeanFactory.java                ← Root interface
│       ├── support/DefaultListableBeanFactory.java  ← Core implementation
│       ├── config/                         ← Bean definitions
│       └── annotation/                     ← @Autowired processing
│
├── spring-context/
│   └── src/main/java/org/springframework/context/
│       ├── ApplicationContext.java
│       ├── support/AbstractApplicationContext.java
│       ├── annotation/
│       │   └── CommonAnnotationBeanPostProcessor.java
│       └── event/                          ← Application events
│
├── spring-web/
│   └── src/main/java/org/springframework/web/
│       ├── servlet/DispatcherServlet.java  ← THE dispatcher
│       ├── filter/                         ← Filter chain
│       └── method/support/                 ← HandlerMethodArgumentResolver
│
├── spring-webmvc/
│   └── src/main/java/org/springframework/web/servlet/
│       ├── mvc/method/annotation/
│       │   └── RequestMappingHandlerAdapter.java
│       └── handler/
│
└── spring-tx/
    └── src/main/java/org/springframework/transaction/
        ├── interceptor/TransactionInterceptor.java
        └── annotation/AnnotationTransactionAttributeSource.java
```

### Level 3: Embedded Runtime
```
spring-boot/
└── spring-boot-starter-web/
    └── (pulls in spring-boot-starter-tomcat)

tomcat-embed-core/
└── org/apache/catalina/
    ├── startu/Tomcat.java
    ├── core/StandardServer.java
    ├── connector/Connector.java            ← Socket → HTTP
    ├── coyote/                             ← Request/Response adapters
    └── valves/                             ← Tomcat's pipeline
```

### Reading Order

1. `SpringApplication.java` — Understand bootstrap: prepareEnvironment → createApplicationContext → refresh → afterRefresh
2. `AbstractApplicationContext.refresh()` — The 12-step refresh sequence
3. `DefaultListableBeanFactory` — Bean definition registry, dependency resolution
4. `DispatcherServlet.doDispatch()` — Request processing pipeline
5. `TransactionInterceptor.invoke()` — How @Transactional actually works

---

## Production Troubleshooting Guide (Quick Reference)

| Symptom | Likely Cause | Diagnostic Command | Session |
|---------|-------------|-------------------|---------|
| Requests hanging | Thread pool exhaustion | `jstack <pid> \| grep BLOCKED` | 09 |
| OOM Killed | Memory leak / GC thrashing | `jmap -histo:live <pid>` | 11 |
| Slow startup | Too many auto-config classes | `--debug` flag, condition evaluation report | 13 |
| @Transactional not rolling back | Self-invocation (proxy bypass) | Check call site, refactor to self-injection | 15, 20 |
| N+1 queries | Lazy loading in loops | `spring.jpa.properties.hibernate.show_sql=true` | 20 |
| Connection pool timeout | Pool too small / connection leak | `HikariPool-1 - Connection is not available` | 22 |
| Request body missing | InputStream consumed twice | Check filter chain for body reads | 16, 17 |
| Circular dependency | Constructor injection cycle | `BeanCurrentlyInCreationException` | 15 |
| Context not propagated | Thread switch without copy | `MDC.get("traceId")` null in async calls | 12 |
| @Async silently fails | No TaskExecutor configured / exception swallowed | Check `AsyncUncaughtExceptionHandler` | 09, 22 |

---

## Interview Preparation Guide (Quick Reference)

### Staff Engineer Interview Themes

1. **System Design**: Design a payment processing system handling 10K TPS
2. **Architecture Evolution**: How would you evolve a monolith handling 1M users to 100M?
3. **Failure Analysis**: Given a production outage timeline, diagnose root cause
4. **Trade-off Analysis**: Synchronous vs asynchronous, consistency vs availability
5. **Team Design**: How would you structure 3 teams around this architecture?
6. **Technology Evaluation**: Evaluate Spring Boot vs Quarkus for a specific use case
7. **Incident Response**: Walk through your approach to a SEV1 incident
8. **Mentorship**: How do you grow Senior Engineers into Staff Engineers?

Full interview scenarios in `interview-preparation-guide.md`.

---

## Hands-On Projects

1. **Build a Modular Monolith**: 5 bounded contexts, domain events, compile-time boundaries
2. **Rewrite a Service Controller**: Thin controllers, rich domain model, hexagonal ports
3. **Build a Custom Spring Boot Starter**: Auto-configuration, health indicators, metrics
4. **Implement Saga Pattern**: Distributed transaction across 3 services with compensation
5. **Observability Platform**: OpenTelemetry, Prometheus, Grafana, distributed tracing
6. **Kubernetes Operator**: Custom Spring Boot operator with CRDs
7. **High-Performance API**: 50K RPS with Virtual Threads, connection pooling, caching
8. **Framework Migration**: Migrate a real service from Spring Boot to Quarkus (or vice versa)

Full project specifications in `hands-on-projects.md`.

---

## Advanced Engineering Challenges

1. **Read SpringApplication.refresh() end-to-end and document the exact sequence**
2. **Implement your own @Autowired replacement using reflection**
3. **Build a ThreadPoolTaskExecutor instrumentation that exports metrics to Prometheus**
4. **Write a custom BeanPostProcessor that times bean initialization**
5. **Trace a single @Transactional call from the annotation to the database COMMIT**
6. **Capture all SQL queries in a production system without restarting (byte-buddy agent)**
7. **Implement distributed tracing by wrapping ExecutorService with context propagation**
8. **Design a multi-tenant architecture: schema-per-tenant, database-per-tenant, discriminator column**

---

## Key References

- Spring Framework Reference: https://docs.spring.io/spring-framework/reference/
- Spring Boot Reference: https://docs.spring.io/spring-boot/reference/
- Spring Source Code: https://github.com/spring-projects/spring-framework
- Spring Boot Source Code: https://github.com/spring-projects/spring-boot
- JVM Specification: https://docs.oracle.com/javase/specs/jvms/se21/html/
- Java Concurrency in Practice (Brian Goetz)
- Designing Data-Intensive Applications (Martin Kleppmann)
- Domain-Driven Design (Eric Evans)
- Implementing Domain-Driven Design (Vaughn Vernon)
- Building Microservices (Sam Newman)
- Team Topologies (Matthew Skelton & Manuel Pais)

---

## Anti-Curriculum: What This Program Does NOT Cover

- Spring Boot "Hello World" tutorials
- REST API CRUD basics
- Spring Security basic configuration
- Spring Data JPA findBy* methods
- Thymeleaf / Mustache templating
- Spring Cloud Netflix (deprecated/legacy)
- XML-based Spring configuration (pre-2014)
- JSP / JSF / Struts (legacy)

This program assumes you can build CRUD APIs. It focuses on what comes after.
