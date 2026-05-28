# Phase 7 — Spring Boot Mastery

> **Duration**: 4-6 weeks (full-time) | **Prerequisites**: Phase 3 (Java Deep Dive)
>
> **Goal**: Build production-grade enterprise microservices with Spring Boot. Understand every layer of the request lifecycle, configure transactions/sercurity/persistence correctly, and build a mini IoC container from scratch.
>
> **Why Spring Boot for the payment platform**: ALL core financial services — Financial Core (Ledger+Wallet), Payment Orchestrator, Refund, FX, and Treasury — use Java + Spring Boot. Spring provides: @Transactional for ACID boundaries (critical for ledger), Spring Security for RBAC enforcement, Spring Data JPA for type-safe persistence, Spring Kafka for event publishing.

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-3 | Module 01 | Spring Core: IoC, Bean lifecycle, DI, AOP, proxies | 10h |
| 4-6 | Module 02 | Spring Boot: auto-config, MVC, request lifecycle, validation | 10h |
| 7-9 | Module 03 | Spring Data JPA: repositories, EntityGraph, Locking, @Transactional deep dive | 10h |
| 10-12 | Module 04 | Spring Security (filter chain, JWT, RBAC), Spring Kafka | 10h |
| 13-14 | Module 05 | Testing (slice tests, Testcontainers), performance, actuator, production | 8h |
| 15-18 | Module 05 | Build a mini Spring (IoC container, DispatcherServlet, @Transactional) | 12h |
| 19-24 | Mini Project | Financial Core Ledger Service | 20h |

## Resources

- **Book**: "Spring in Action" (Walls)
- **Doc**: Spring Framework Reference, Spring Boot Reference
- **Course**: Spring Academy (spring.academy)
