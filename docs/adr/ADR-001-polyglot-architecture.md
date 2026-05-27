# ADR-001 — Polyglot Microservices Architecture

- **Status**: Accepted
- **Date**: 2026-05-27
- **Deciders**: Architecture Review Board
- **Supersedes**: Original ADR-001 draft (Go-only for all services)
- **Version**: v1.0

---

## Context

Phase 11 (Technology Selection) initially selected **Go 1.22+** as the sole language for all 19 microservices, scoring it 93/100 against Java 21 (88), Kotlin (68), and Rust (56). The primary rationale was low-latency GC, single-binary deployment, and goroutine concurrency — all critical for a Tier-1 production payment system.

Two factors prompted re-evaluation:

1. **Learning objective**: The project must serve as a hands-on learning platform across multiple technology stacks (Java Spring Boot, Python FastAPI, Node.js, Go).
2. **Best tool for each job**: Different bounded contexts have fundamentally different characteristics — ACID-heavy financial core vs. ML-driven fraud detection vs. event-driven notification consumers vs. batch reconciliation — and no single language is optimal for all.

---

## Decision

**Adopt a polyglot microservices architecture with 4 languages**, each mapped to bounded contexts based on domain characteristics and team learning goals.

### Language Mapping

| Language | Version | Framework | Contexts | Rationale |
|----------|---------|-----------|----------|-----------|
| **Java** | 21 LTS | Spring Boot 3.3 | Financial Core, Payment, Refund, FX, Treasury | Strong typing, JPA/Hibernate for complex ACID transactions, Spring Security for RBAC, mature financial ecosystem |
| **Python** | 3.12 | FastAPI | Risk & Fraud | ML/AI ecosystem (scikit-learn, XGBoost), rapid fraud model iteration, pandas for data analysis |
| **Node.js** | 22 LTS | Fastify + TypeScript | Notification, Transaction (read), Fee Engine | Async I/O for event-driven consumers, rich webhook/web push ecosystem, fast API BFF layers |
| **Go** | 1.22+ | Chi + sqlc | Settlement, Reconciliation, Compliance, Dispute, Merchant, Identity, Bank Integration, Audit | Low resource footprint, high concurrency for batch processing, single-binary deployment, fast cold start |

### Design Principles

1. **Contract-first**: All inter-service communication defined by OpenAPI 3.1 specs and Avro schemas. Language-agnostic.
2. **Database-per-service**: Each service owns its database. No shared tables across services regardless of language.
3. **Event-driven async**: Kafka as the single message broker. CDC via Debezium Outbox pattern.
4. **Consistent observability**: OpenTelemetry SDK per language — W3C Trace Context for cross-language correlation IDs.
5. **Docker-native**: Every service ships as a container. Local dev is `docker-compose up`.

---

## Consequences

### Positive

- **Learning breadth**: Team gains production experience across 4 major ecosystems
- **Domain-language fit**: Each context uses the language best suited for its workload pattern
- **Hiring flexibility**: Can hire for any of the 4 language ecosystems
- **Independent evolution**: Each language stack can upgrade independently

### Negative (Mitigated)

| Concern | Mitigation |
|---------|-----------|
| Increased build complexity | Single `Makefile` at repo root delegates to language-specific tools; Docker abstracts runtime |
| No shared library code | Contracts (OpenAPI, Avro) are the shared layer; each language generates clients from specs |
| Developer must know 4 languages | Scaffold templates per language; Docker Compose means no local toolchain needed to run |
| CI/CD matrix grows | GitHub Actions parallel matrix builds; per-language caching |

---

## Alternatives Considered

| Alternative | Rejected Because |
|-------------|-----------------|
| Go-only (original) | Single language limits learning; Python better for ML, Java better for complex transactions |
| Java-only | Python ecosystem essential for fraud ML; Go superior for batch processing efficiency |
| Python-only | Weak typing inadequate for financial core integrity; GIL limits concurrency for payment processing |
| Node.js-only | Single-threaded event loop not ideal for CPU-bound batch reconciliation |

---

## References

- Phase 11 — Technology Selection (`docs/stages/B-domain-architecture/11-technology-selection.md`)
- Phase 04 — Domain Design (`docs/stages/B-domain-architecture/04-domain-design.md`)
- Phase 06 — High-Level Architecture (`docs/stages/B-domain-architecture/06-high-level-architecture.md`)
- PLAN.md — Multi-Language Build System (`PLAN.md`)
- TASKS.md — Implementation Tasks (`TASKS.md`)
