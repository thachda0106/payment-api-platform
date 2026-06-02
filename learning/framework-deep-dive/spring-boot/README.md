# Spring Boot: Staff/Principal Engineer Learning Program

## Philosophy

This program does NOT teach Spring Boot CRUD development. It teaches you to become **framework-agnostic** by understanding every framework layer from source code to production architecture.

```
Framework API  ←  What tutorials teach (5% of real knowledge)
Framework Core  ←  Internal architecture, lifecycle, DI container
Runtime         ←  JVM, threads, memory, GC, concurrency
Architecture    ←  Design patterns, DDD, hexagonal, CQRS
Production      ←  Failure modes, observability, performance
Source Code     ←  Reading and navigating framework internals
Staff Thinking  ←  Trade-offs, build vs buy, organizational design
```

## Who This Is For

- Senior Engineers transitioning to Staff/Principal
- Architects designing large-scale Spring Boot systems
- Engineers who debug production issues at 3 AM
- Anyone who wants to read Spring Framework source code confidently
- Engineers who need to make architecture decisions with trade-off awareness

## Prerequisites

- 5+ years of backend development
- Comfortable reading code in a new language
- Basic understanding of HTTP, databases, distributed systems
- Willingness to read framework source code

## Program Structure

```
spring-boot/
├── README.md                          ← This file
├── curriculum.md                      ← Full 28-session roadmap with learning objectives
├── reading-roadmap.md                 ← Source code reading guides for Spring internals
│
├── sessions/
│   ├── 01-architecture-overview.md
│   ├── 02-layered-architecture.md
│   ├── 03-feature-modular-monolith.md
│   ├── 04-ddd-strategic-design.md
│   ├── 05-ddd-tactical-design.md
│   ├── 06-hexagonal-architecture.md
│   ├── 07-clean-vertical-slice.md
│   ├── 08-cqrs-event-driven-evolution.md
│   ├── 09-jvm-concurrency-thread-pools.md
│   ├── 10-virtual-threads-project-loom.md
│   ├── 11-jvm-memory-gc.md
│   ├── 12-context-propagation.md
│   ├── 13-bootstrap-autoconfiguration.md
│   ├── 14-application-context-bean-lifecycle.md
│   ├── 15-di-ioc-container-internals.md
│   ├── 16-http-layer-tomcat-dispatcher.md
│   ├── 17-middleware-filters-interceptors-aop.md
│   ├── 18-validation-serialization-errors.md
│   ├── 19-controller-service-layer.md
│   ├── 20-repository-transactions-persistence.md
│   ├── 21-domain-layer-design.md
│   ├── 22-production-failure-scenarios.md
│   ├── 23-observability-metrics-tracing.md
│   ├── 24-performance-analysis.md
│   ├── 25-source-code-reading-mastery.md
│   ├── 26-architecture-decision-making.md
│   ├── 27-build-vs-buy-organizational-design.md
│   └── 28-framework-agnostic-mastery.md
│
├── interview-preparation-guide.md     ← Staff/Principal interview scenarios
├── hands-on-projects.md               ← 8 production-grade projects
├── troubleshooting-guide.md           ← Common production issues & resolution
└── architecture-decision-matrix.md    ← Framework for evaluating architecture choices
```

## How to Use This Program

### Self-Paced (4-6 months)

1. **Weeks 1-2**: Sessions 1-8 (Architecture foundation)
2. **Weeks 3-4**: Sessions 9-12 (Runtime mastery)
3. **Weeks 5-8**: Sessions 13-18 (Framework internals)
4. **Weeks 9-10**: Sessions 19-21 (Application architecture)
5. **Weeks 11-12**: Sessions 22-24 (Production deep dive)
6. **Weeks 13-14**: Session 25 (Source code reading)
7. **Weeks 15-16**: Sessions 26-28 (Staff engineer thinking)
8. **Weeks 17-24**: Hands-on projects (parallel execution)

### Intensive (8-10 weeks)

Double the pace. Spend evenings reading Spring Framework source code.

### Just-In-Time

Jump to any session based on immediate need:
- Need to fix a memory leak? → Session 11
- Designing a new architecture? → Sessions 4-7, 26-27
- Debugging request failures? → Session 16
- Building observability? → Session 23

## Key Principles

1. **Every topic answers "Why does this exist?"** before "How do I use it?"
2. **Trade-offs are explicit.** Every architecture choice has costs.
3. **Production behavior matters more than ideal behavior.**
4. **Source code is the ultimate documentation.**
5. **Framework-specific knowledge decays. Architectural understanding compounds.**

## Learning Outcomes

After completing this program, you will:

- Read any framework's source code and understand its internal architecture
- Design systems from startup (1 engineer) to enterprise (100+ engineers) scale
- Debug production issues by understanding runtime behavior at the JVM level
- Make architecture decisions with explicit trade-off analysis
- Design modular monoliths that can evolve to microservices if needed
- Understand why Spring Boot behaves the way it does under load
- Evaluate any new framework against first principles
- Lead architecture discussions with Staff/Principal-level depth
- Know when NOT to use Spring Boot

## Contributing

This is a living document. As Spring Boot evolves (Virtual Threads, GraalVM, Project Leyden), this program evolves with it. If you find gaps or want to add depth, create a PR.

## License

This is a personal learning resource. Use it, share it, improve it.
