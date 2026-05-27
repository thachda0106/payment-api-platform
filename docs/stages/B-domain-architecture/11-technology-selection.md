# Phase 11 — Technology Selection

## MoMo-like Payment API Platform

> **Document Status**: Draft v1.0
> **Last Updated**: 2026-05-20
> **Classification**: CONFIDENTIAL — Internal Use Only
> **Audience**: Architecture Review Board, Engineering Leadership, Platform Team
> **Input**: Phase 06 — High-Level Architecture (v6.0); Phase 02 — Requirements & SLOs
> **Author Level**: Principal Architect
> **Approval Gate**: 🏗️ Architecture Review Board (ARB) Final Sign-off

---

## Table of Contents

1. [Goal & Scope](#1-goal--scope)
2. [Key Decisions](#2-key-decisions)
3. [Documents Produced](#3-documents-produced)
4. [Technology Stack — Tier-by-Tier Comparison](#4-technology-stack--tier-by-tier-comparison)
   - [4.1 Language & Runtime](#41-language--runtime)
   - [4.2 Application Framework](#42-application-framework)
   - [4.3 Relational Database](#43-relational-database)
   - [4.4 Cache & Ephemeral Store](#44-cache--ephemeral-store)
   - [4.5 Message Broker & Event Streaming](#45-message-broker--event-streaming)
   - [4.6 CDC & Change Data Capture](#46-cdc--change-data-capture)
   - [4.7 Schema Registry](#47-schema-registry)
   - [4.8 Search & Analytics](#48-search--analytics)
   - [4.9 API Gateway](#49-api-gateway)
   - [4.10 Service Mesh](#410-service-mesh)
   - [4.11 Container Orchestration](#411-container-orchestration)
   - [4.12 Infrastructure as Code](#412-infrastructure-as-code)
   - [4.13 Observability](#413-observability)
   - [4.14 CI/CD & GitOps](#414-cicd--gitops)
   - [4.15 Security & Secrets](#415-security--secrets)
   - [4.16 Cloud Provider](#416-cloud-provider)
5. [Architecture Decision Records](#5-architecture-decision-records)
6. [Example Deliverables](#6-example-deliverables)
7. [Key Questions](#7-key-questions)
8. [Implementation Tasks](#8-implementation-tasks)
9. [Common Mistakes](#9-common-mistakes)
10. [KPIs & Exit Criteria](#10-kpis--exit-criteria)
11. [Connection to Next Phase](#11-connection-to-next-phase)

---

## 1. Goal & Scope

### 1.1 Goal

Select every technology in the platform stack with documented alternatives, trade-off analysis, and rationale. Each decision is captured as an Architecture Decision Record (ADR) and evaluated against the SLOs, consistency requirements, and latency budgets defined in Phases 02, 06, and 10.

### 1.2 Scope

- **16 technology tiers** across the full stack
- **Each tier**: 2–4 alternatives evaluated against cost, performance, operational complexity, ecosystem, and team expertise
- **ADR index** with 10+ decisions documented

### 1.3 Evaluation Criteria

Every technology is scored on these dimensions:

| Dimension | Weight | Description |
|-----------|--------|-------------|
| **Functional Fit** | 30% | Does it meet the documented requirements (Phases 02, 06, 08)? |
| **Operational Maturity** | 25% | Monitoring, HA, DR, upgrade path, managed service availability |
| **Team Expertise** | 20% | Existing knowledge, learning curve, hiring market |
| **Cost** | 15% | Infrastructure, licensing, operational overhead |
| **Ecosystem** | 10% | Community, integrations, tooling, documentation |

---

## 2. Key Decisions

| # | Decision | Selected | Primary Rationale |
|---|----------|----------|-------------------|
| D01 | **Polyglot microservices** | Java 21 (Core), Python 3.12 (Fraud), Node.js 22 (BFF), Go 1.22+ (Batch) | Best language per workload: Spring Boot for ACID, FastAPI for ML, Fastify for async I/O, Go for batch. Documented in ADR-001. |
| D02 | **PostgreSQL as sole relational DB** | PostgreSQL 16 (Aurora) | `SECURITY DEFINER` procedures, `SELECT FOR UPDATE`, STATEMENT triggers, hash chaining — none available in MySQL. |
| D03 | **Apache Kafka for event streaming** | Kafka 3.7 (MSK) | Exactly-once semantics, log compaction, partitioning control, MirrorMaker 2 for DR. |
| D04 | **Redis for caching + rate limiting** | Redis 7 (ElastiCache) | Sub-millisecond latency, atomic Lua scripting for rate limit token buckets, cluster mode for sharding. |
| D05 | **Kubernetes (EKS) + Istio Ambient** | EKS 1.30 + Istio Ambient | Managed control plane, ambient mesh eliminates sidecar overhead (~70% CPU savings per Phase 06 §13). |
| D06 | **OpenTelemetry for observability** | OTel + Jaeger + Prometheus + Grafana | Vendor-neutral, W3C Trace Context, OTLP native in Go. |
| D07 | **ArgoCD for GitOps deployment** | ArgoCD + Argo Rollouts | Declarative GitOps, native canary analysis, automated rollback. |
| D08 | **JSON for API, Avro for Events** | OpenAPI 3.1 JSON + Confluent Avro | JSON for human-readable API contracts; Avro for binary-efficient, schema-governed internal events. |

---

## 3. Documents Produced

| Document | Location | Status |
|----------|----------|--------|
| **Technology Selection Reference** | `docs/stages/B-domain-architecture/11-technology-selection.md` (this document) | ✅ v1.0 |
| **ADR Index** | `docs/adr/README.md` | 🚧 Pending |
| **ADR-001 — Go as Primary Language** | `docs/adr/ADR-001-language-go.md` | 🚧 Pending |
| **ADR-002 — PostgreSQL over MySQL** | `docs/adr/ADR-002-database-postgresql.md` | 🚧 Pending |
| **ADR-003 — Kafka over RabbitMQ** | `docs/adr/ADR-003-messaging-kafka.md` | 🚧 Pending |
| **ADR-004 — Istio Ambient Mesh** | `docs/adr/ADR-004-mesh-istio-ambient.md` | 🚧 Pending |
| **ADR-005 — Outbox CDC over Dual-Write** | `docs/adr/ADR-005-outbox-cdc.md` | 🚧 Pending |
| **ADR-006 — OpenAPI Contract-First** | `docs/adr/ADR-006-api-contract-first.md` | 🚧 Pending |

---

## 4. Technology Stack — Tier-by-Tier Comparison

### 4.1 Language & Runtime

**Decision**: **Polyglot microservices** — 4 languages mapped to domain tiers based on workload characteristics and ecosystem fit. See [ADR-001 — Polyglot Architecture](../../adr/ADR-001-polyglot-architecture.md) for full rationale.

#### Language-to-Context Mapping

| Language | Version | Tier | Contexts | Score |
|----------|---------|------|----------|:-----:|
| **Java** | 21 LTS | Core | Financial Core, Payment, Refund, FX, Treasury | **92** |
| **Python** | 3.12 | Core | Risk & Fraud | **90** |
| **Node.js** | 22 LTS | Generic | Notification, Transaction (read), Fee Engine | **85** |
| **Go** | 1.22+ | Supporting/Generic | Settlement, Reconciliation, Compliance, Dispute, Merchant, Identity, Bank Integration, Audit | **93** |

#### Selection Rationale Per Language

**Java 21 (Spring Boot 3.3) — Core financial services**:
- Spring ecosystem provides mature ACID transaction support (JPA, `@Transactional`, pessimistic locking)
- Spring Security for fine-grained RBAC enforcement at the method level
- Bean Validation for request validation matching OpenAPI schemas
- Virtual threads (Project Loom) in Java 21 reduce thread pool complexity
- 5–30s JVM warmup acceptable for long-running financial services (not cold-start-sensitive)
- Memory overhead (256MB+) acceptable given critical correctness requirements

**Python 3.12 (FastAPI) — Risk & Fraud**:
- ML/AI ecosystem: scikit-learn, XGBoost, PyTorch for fraud scoring models
- pandas + numpy for velocity analysis and pattern detection
- FastAPI async performance (~14K RPS) sufficient for fraud check path (< 50ms budget)
- Rapid model iteration without recompilation — critical for fraud rule updates
- Pydantic v2 for request validation with OpenAPI auto-generation

**Node.js 22 (Fastify + TypeScript) — Event consumers & BFF**:
- Async I/O model ideal for event-driven consumers (Kafka, webhooks)
- Rich ecosystem for push notifications (FCM, APNs), email (nodemailer), SMS
- TypeScript for type safety across consumer contracts
- Fastify performance (~45K RPS) for read-heavy API BFF layers

**Go 1.22+ (Chi + sqlc) — Batch processing & ACL**:
- Low-latency GC with sub-millisecond pause times
- Goroutines for high-concurrency batch reconciliation and settlement
- Single-binary deployment: minimal Docker image, fast cold start for autoscaling
- `confluent-kafka-go` (librdkafka) for highest-throughput Kafka consumers
- `sqlc` codegen for type-safe SQL without ORM overhead

#### Rejected: Single-Language Approach

| Alternative | Rejected Because |
|-------------|-----------------|
| Go-only (original ADR-001 draft) | Single language limits learning; Python better for ML, Java better for complex ACID transactions |
| Java-only | Python ecosystem essential for fraud ML; Go superior for batch processing efficiency |
| Python-only | Weak typing inadequate for financial core integrity; GIL limits concurrency |
| Node.js-only | Single-threaded event loop not ideal for CPU-bound batch reconciliation |

---

### 4.2 Application Framework

**Decision**: **Per-language framework selection** optimized for each language's ecosystem.

#### Java — Spring Boot 3.3

| Component | Selection | Rationale |
|-----------|-----------|-----------|
| HTTP Framework | Spring Boot 3.3 (WebFlux optional) | Industry standard for Java microservices. Auto-configuration, embedded Tomcat. |
| DB Access | Spring Data JPA + Hibernate 6 | Type-safe JPQL, `@Transactional` for ACID boundaries, pessimistic locking (`@Lock`). |
| Validation | Bean Validation (Hibernate Validator) | `@NotNull`, `@Valid` — annotation-based validation matching OpenAPI schemas. |
| Kafka Client | Spring Kafka | Declarative `@KafkaListener`, `KafkaTemplate`, exactly-once support. |
| Avro | Confluent Avro Serializer | Schema Registry integration with `KafkaAvroSerializer`. |
| Tracing | OpenTelemetry Java Agent | Auto-instrumentation: Spring Web, JPA, Kafka, JDBC — zero code changes. |
| Resilience | Resilience4j | `@CircuitBreaker`, `@Retry`, `@Bulkhead`, `@RateLimiter` — declarative resilience. |
| Security | Spring Security + OAuth2 Resource Server | Method-level `@PreAuthorize`, JWT RS256 validation, RBAC enforcement. |

#### Python — FastAPI

| Component | Selection | Rationale |
|-----------|-----------|-----------|
| HTTP Framework | FastAPI 0.111+ | Async-native, auto OpenAPI generation, Pydantic validation built-in. |
| DB Access | SQLAlchemy 2.0 (async) | Async ORM with `selectinload` for eager loading. Raw SQL when needed. |
| Validation | Pydantic v2 | Rust-core validation, JSON Schema generation, OpenAPI integration. |
| Kafka Client | aiokafka | Async Kafka client for Python asyncio event loop. |
| Avro | fastavro + confluent-kafka-python | Schema Registry + Avro serialization in Python. |
| ML Runtime | scikit-learn / XGBoost | Fraud model scoring. Models serialized via joblib/pickle. |
| Tracing | opentelemetry-instrumentation-fastapi | Auto-instrumentation: HTTP, SQLAlchemy, Kafka. |
| Resilience | tenacity | `@retry` with exponential backoff, circuit breaker via custom middleware. |

#### Node.js — Fastify + TypeScript

| Component | Selection | Rationale |
|-----------|-----------|-----------|
| HTTP Framework | Fastify 5 + TypeScript | High performance (~45K RPS), schema-based validation, plugin architecture. |
| DB Access | Prisma | Type-safe ORM with generated client, migrations, and relation queries. |
| Validation | Zod + fastify-type-provider-zod | Runtime type checking with TypeScript inference. |
| Kafka Client | KafkaJS | Pure JavaScript Kafka client — no native dependencies, good for consumer groups. |
| Web Push | web-push + firebase-admin | Push notification delivery to FCM (Android) + APNs (iOS). |
| Email | nodemailer | SMTP email delivery with template support. |
| Tracing | @opentelemetry/sdk-node + auto-instrumentations-node | Auto-instrumentation: HTTP, Prisma, KafkaJS. |
| Resilience | opossum | Node.js circuit breaker with fallback support. |

#### Go — Chi + sqlc + Watermill

| Component | Selection | Rationale |
|-----------|-----------|-----------|
| HTTP Router | `go-chi/chi` v5 | Lightweight, stdlib-compatible, middleware ecosystem. No reflection-based DI. |
| DB Access | `sqlc` | Generates type-safe Go code from SQL queries. Zero runtime overhead. Avoids ORM abstractions that hide `SELECT FOR UPDATE`. |
| Validation | `go-playground/validator` | Struct tag-based validation matching OpenAPI schemas. |
| Kafka Client | `confluent-kafka-go` (librdkafka) | Native C library binding — highest throughput, lowest latency Go Kafka client. |
| Avro | `hamba/avro` | Pure Go Avro serialization with Schema Registry integration. |
| Tracing | `go.opentelemetry.io/otel` | OTLP exporters for Jaeger. Auto-instrumentation for HTTP, gRPC, Kafka. |
| Resilience | `sony/gobreaker` + custom retry | Circuit breaker + exponential backoff. |

---

### 4.3 Relational Database

**Decision**: **PostgreSQL 16** on **Amazon Aurora PostgreSQL**.

| Criteria | **PostgreSQL (Aurora)** ✅ | MySQL 8 (Aurora) | CockroachDB | YugabyteDB |
|----------|:--:|:--:|:--:|:--:|
| **Stored Procedures** | ★★★★★ | ★★★☆☆ | ★☆☆☆☆ | ★★★☆☆ |
| **STATEMENT triggers** | ★★★★★ | ★★★☆☆ | ★☆☆☆☆ | ★★☆☆☆ |
| **SELECT FOR UPDATE** | ★★★★★ | ★★★★★ | ★★★☆☆ | ★★★★☆ |
| **JSONB** | ★★★★★ | ★★★☆☆ | ★★★★☆ | ★★★★☆ |
| **Managed HA** | ★★★★★ | ★★★★★ | ★★★★☆ | ★★★★☆ |
| **Cost** | ★★★★☆ | ★★★★☆ | ★★★☆☆ | ★★★☆☆ |
| **Score** | **95** | **78** | **62** | **68** |

**Rationale**: PostgreSQL is non-negotiable for this architecture. Phase 07 relies on:
- `SECURITY DEFINER` stored procedures (`create_journal_entry`)
- `STATEMENT`-level triggers (`verify_double_entry`)
- `SELECT FOR UPDATE NOWAIT` for pessimistic ledger locking
- `JSONB` for flexible outbox/event payloads
- `pgoutput` logical replication for Debezium CDC
- `digest()` function for cryptographic hash chaining

None of these capabilities exist in MySQL. CockroachDB/YugabyteDB lack PL/pgSQL procedure support.

**Aurora vs. RDS**: Aurora selected for:
- 3× throughput vs. vanilla RDS PostgreSQL
- Auto-scaling storage (10GB–128TB)
- < 1s failover with Multi-AZ
- I/O-Optimized pricing for predictable costs

**Cluster Topology** (from Phase 07):

| Database | Purpose | Instance | Multi-AZ |
|----------|---------|----------|:--:|
| `financial_core_db` | Ledger, journal entries | `db.r6g.xlarge` | ✅ |
| `payment_db` | Payment orchestration | `db.r6g.large` | ✅ |
| `idempotency_db` | Idempotency keys | `db.r6g.large` | ✅ |

---

### 4.4 Cache & Ephemeral Store

**Decision**: **Redis 7** on **Amazon ElastiCache (Serverless)**.

| Criteria | **Redis 7 ✅** | Memcached | Hazelcast | Dragonfly |
|----------|:--:|:--:|:--:|:--:|
| **Data Structures** | ★★★★★ | ★★☆☆☆ | ★★★★★ | ★★★★★ |
| **Lua Scripting** | ★★★★★ | ☆☆☆☆☆ | ★★★☆☆ | ★★★★★ |
| **Persistence** | ★★★★☆ | ☆☆☆☆☆ | ★★★★★ | ★★★★☆ |
| **Managed Service** | ★★★★★ | ★★★★★ | ★★★☆☆ | ★★☆☆☆ |
| **Score** | **95** | **42** | **68** | **64** |

**Use Cases**:

| Use Case | Redis Structure | TTL | Key Pattern |
|----------|---------------|-----|-------------|
| Idempotency cache | `STRING` (JSON response) | 24h | `idempotency:{api_key}:{key}` |
| Rate limit token bucket | `HASH` + Lua script | Window-based | `ratelimit:{user_id}:{endpoint}` |
| JWT session cache | `STRING` (claims JSON) | 15min | `session:{jti}` |
| API key cache | `STRING` (scopes JSON) | 5min | `apikey:{key_hash}` |
| Circuit breaker state | `STRING` | Per-config | `circuit:{service}:{endpoint}` |

**Rejected**: Memcached (no Lua, no persistence — can't implement token bucket atomically), Hazelcast (JVM overhead, no managed AWS service), Dragonfly (too new, not available as managed service).

---

### 4.5 Message Broker & Event Streaming

**Decision**: **Apache Kafka 3.7** on **Amazon MSK**.

| Criteria | **Kafka (MSK) ✅** | RabbitMQ | AWS SQS+SNS | NATS JetStream |
|----------|:--:|:--:|:--:|:--:|
| **Replayability** | ★★★★★ | ★★★☆☆ | ★★☆☆☆ | ★★★★☆ |
| **Throughput** | ★★★★★ | ★★★☆☆ | ★★★★☆ | ★★★★☆ |
| **Partition Ordering** | ★★★★★ | ☆☆☆☆☆ | ★★★★☆ | ★★★☆☆ |
| **Exactly-Once** | ★★★★★ | ★★☆☆☆ | ★★★☆☆ | ★★★★☆ |
| **Managed Service** | ★★★★☆ | ★★★★★ | ★★★★★ | ★★☆☆☆ |
| **Operational Complexity** | ★★★☆☆ | ★★★★☆ | ★★★★★ | ★★★☆☆ |
| **Score** | **92** | **62** | **68** | **66** |

**Rationale**: Kafka is the only broker that supports all of our event-driven requirements:
- **Replay by offset** (Phase 09 §4.8.2): critical for catastrophic bug recovery
- **Log compaction**: enables snapshot-based projections
- **MirrorMaker 2**: cross-region DR replication (Phase 06 §12)
- **Partition ordering**: strict per-account sequence guarantees

**MSK Configuration**:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| `replication.factor` | 3 | Survive 2/3 broker failures |
| `min.insync.replicas` | 2 | Durability with 1-node tolerance |
| `retention.ms` | 604800000 (7d) | Financial topics (Phase 09 §4.9) |
| `retention.bytes` | 500GB per topic | Prevent disk exhaustion |
| `num.partitions` | 12 per topic | Parallelism for 6+ consumer instances |

**Rejected**: RabbitMQ (no replay, no ordering, broker-based routing), SQS+SNS (no replay, 14-day max retention, pull-based), NATS (no log compaction, smaller ecosystem).

---

### 4.6 CDC & Change Data Capture

**Decision**: **Debezium 2.7** (self-managed on EKS) with **pgoutput** plugin.

| Criteria | **Debezium ✅** | AWS DMS | Custom Outbox Poller |
|----------|:--:|:--:|:--:|
| **PostgreSQL native** | ★★★★★ | ★★★☆☆ | ★★★☆☆ |
| **Outbox transform** | ★★★★★ | ★★☆☆☆ | ★★★★☆ |
| **Exactly-once** | ★★★★☆ | ★★☆☆☆ | ★★★★★ |
| **Operational Maturity** | ★★★★☆ | ★★★★★ | ★★★☆☆ |
| **Score** | **92** | **62** | **60** |

**Rationale**: Debezium's `EventRouter` SMT (Single Message Transform) is purpose-built for the Outbox pattern. It extracts `event_topic`, `partition_key`, and `payload` from outbox rows and routes them to the correct Kafka topic — without custom code.

**Rejected**: AWS DMS (limited to full-load + CDC, no outbox transform, higher latency), custom poller (requires `SELECT FOR UPDATE SKIP LOCKED` on outbox table — adds ~5ms latency per poll cycle).

---

### 4.7 Schema Registry

**Decision**: **Confluent Schema Registry** 7.6 (self-managed, 3-node HA) with **Avro**.

| Criteria | **Confluent SR ✅** | Apicurio Registry | AWS Glue Schema Registry | Custom |
|----------|:--:|:--:|:--:|:--:|
| **Avro compatibility** | ★★★★★ | ★★★★★ | ★★★☆☆ | ★★★☆☆ |
| **Kafka integration** | ★★★★★ | ★★★★☆ | ★★☆☆☆ | ★★☆☆☆ |
| **FULL mode** | ★★★★★ | ★★★★★ | ★★★☆☆ | ★★★☆☆ |
| **REST API** | ★★★★★ | ★★★★★ | ★★★☆☆ | ★★★☆☆ |
| **Score** | **96** | **88** | **52** | **40** |

**Rationale**: Confluent Schema Registry is the de facto standard for Kafka + Avro. Native support for FULL compatibility mode (critical for financial events — Phase 09 §4.4). `confluent-kafka-go` client has first-class SR integration.

---

### 4.8 Search & Analytics

**Decision**: **OpenSearch 2.11** (managed, Amazon OpenSearch Service).

| Criteria | **OpenSearch ✅** | Elasticsearch | PostgreSQL Full-Text | Apache Solr |
|----------|:--:|:--:|:--:|:--:|
| **Managed Service** | ★★★★★ | ★★★☆☆ | ★★★★★ | ★★☆☆☆ |
| **Kafka Connect Sink** | ★★★★★ | ★★★★☆ | ★★★☆☆ | ★★★☆☆ |
| **Performance** | ★★★★☆ | ★★★★★ | ★★★☆☆ | ★★★★☆ |
| **License** | ★★★★★ | ★★★☆☆ | ★★★★★ | ★★★★★ |
| **Score** | **90** | **72** | **60** | **58** |

**Rationale**: OpenSearch is the AWS-managed fork of Elasticsearch 7.10. Used for:
- Transaction history indexing (F10 — Search Flow)
- Trace storage (F11 — Observability Flow)
- Log aggregation (Phase 20)

**Rejected**: Elasticsearch (license changes in 7.11+, no AWS managed offering), PostgreSQL full-text (works for simple queries but can't handle facet aggregation, sorting, or regex queries at scale).

---

### 4.9 API Gateway

**Decision**: **Kong Gateway 3.x** (self-managed on EKS, DB-less mode).

| Criteria | **Kong ✅** | AWS API Gateway | Envoy (standalone) | Traefik |
|----------|:--:|:--:|:--:|:--:|
| **Plugin ecosystem** | ★★★★★ | ★★★★☆ | ★★★☆☆ | ★★★★☆ |
| **Custom plugin (Go)** | ★★★★★ | ★★☆☆☆ | ★★★★☆ | ★★★☆☆ |
| **Rate limiting** | ★★★★★ | ★★★★★ | ★★★☆☆ | ★★★★☆ |
| **Performance** | ★★★★☆ | ★★★★☆ | ★★★★★ | ★★★★☆ |
| **Cost at scale** | ★★★★☆ | ★★★☆☆ | ★★★★★ | ★★★★☆ |
| **Score** | **92** | **70** | **72** | **68** |

**Rationale**: Kong's plugin architecture is critical for our custom authentication, idempotency, and rate limiting logic:
- **Custom Go plugin**: `idempotency-checker` — validates `Idempotency-Key` + `hash(key + JWT.sub)`
- **JWT plugin**: RS256 verification with KMS
- **Rate limiting plugin**: Token bucket per `user_id:endpoint`
- **DB-less mode**: Declarative config from Git (GitOps)

**Rejected**: AWS API Gateway (custom authorizer latency, per-request pricing at high volume), Envoy standalone (powerful but complex to configure without a control plane), Traefik (weaker plugin ecosystem).

---

### 4.10 Service Mesh

**Decision**: **Istio Ambient Mesh** (1.22+).

| Criteria | **Istio Ambient ✅** | Istio Sidecar | Linkerd | Cilium |
|----------|:--:|:--:|:--:|:--:|
| **CPU overhead** | ★★★★★ | ★★☆☆☆ | ★★★★☆ | ★★★★★ |
| **mTLS** | ★★★★★ | ★★★★★ | ★★★★★ | ★★★★☆ |
| **Traffic splitting** | ★★★★★ | ★★★★★ | ★★★☆☆ | ★★☆☆☆ |
| **Observability** | ★★★★★ | ★★★★★ | ★★★★☆ | ★★★★☆ |
| **Score** | **94** | **72** | **68** | **62** |

**Rationale**: Istio Ambient eliminates the sidecar proxy per pod — saving ~70% CPU (per Phase 06 §13.1). mTLS, traffic splitting (canary), and L7 observability are handled by ztunnel + waypoint proxies without per-pod overhead.

**Rejected**: Sidecar Istio (70% CPU overhead on 100+ pods), Linkerd (no L7 traffic splitting for canary), Cilium (eBPF-based, excellent for L3/L4, weaker L7).

---

### 4.11 Container Orchestration

**Decision**: **Amazon EKS 1.30** (Kubernetes).

| Criteria | **EKS ✅** | ECS Fargate | GKE | Self-managed K8s |
|----------|:--:|:--:|:--:|:--:|
| **Managed control plane** | ★★★★★ | ★★★★★ | ★★★★★ | ☆☆☆☆☆ |
| **Istio compatibility** | ★★★★★ | ★★☆☆☆ | ★★★★★ | ★★★★★ |
| **Ecosystem** | ★★★★★ | ★★★☆☆ | ★★★★★ | ★★★★★ |
| **Cost** | ★★★☆☆ | ★★★★☆ | ★★★☆☆ | ★★☆☆☆ |
| **Score** | **90** | **64** | **82** | **48** |

**Rationale**: EKS is the standard for AWS Kubernetes. Managed control plane, native IAM integration (IRSA for pod-level AWS permissions), and Fargate support for burstable workloads.

---

### 4.12 Infrastructure as Code

**Decision**: **Terraform** (HashiCorp) + **Atlantis** (PR-based plan/apply).

| Criteria | **Terraform ✅** | Pulumi | AWS CDK | CloudFormation |
|----------|:--:|:--:|:--:|:--:|
| **Multi-cloud** | ★★★★★ | ★★★★★ | ★☆☆☆☆ | ★☆☆☆☆ |
| **Ecosystem** | ★★★★★ | ★★★☆☆ | ★★★☆☆ | ★★★★☆ |
| **Language** | HCL | TypeScript/Python | TypeScript/Python | YAML/JSON |
| **State management** | ★★★★★ | ★★★★★ | ★★★★☆ | ★★★★★ |
| **Score** | **92** | **74** | **62** | **66** |

**Rationale**: Terraform is the industry standard. HCL is purpose-built for infrastructure (no imperative drift). Atlantis provides PR-based GitOps for infrastructure changes.

---

### 4.13 Observability

**Decision**: **OpenTelemetry** + **Jaeger** + **Prometheus** + **Grafana** + **OpenSearch** (logs).

| Tier | Tool | Purpose |
|------|------|---------|
| **Instrumentation** | OpenTelemetry (Go SDK) | Auto-instrumentation: HTTP, gRPC, Kafka, PostgreSQL |
| **Traces** | Jaeger (OTLP) | Distributed trace storage + UI |
| **Metrics** | Prometheus + Grafana | Service metrics, SLO dashboards, alerting rules |
| **Logs** | OpenSearch + Fluent Bit | Structured JSON log aggregation |
| **Alerting** | Grafana Alertmanager → PagerDuty | SLO-based alerting |

**Key Decision**: **OTLP native** — all services export traces, metrics, and logs via OTLP/gRPC. No vendor lock-in.

---

### 4.14 CI/CD & GitOps

**Decision**: **GitHub Actions** (CI) + **ArgoCD** (CD) + **Argo Rollouts** (canary).

| Tool | Role |
|------|------|
| **GitHub Actions** | Build, test, lint, SAST, container build + push |
| **ArgoCD** | GitOps: sync K8s manifests from Git to cluster |
| **Argo Rollouts** | Canary deployment with automated metric analysis |
| **Atlantis** | Terraform plan/apply via PR comments |
| **Trivy** | Container image vulnerability scanning |

**Pipeline Flow** (F12 — Deployment Flow):
```
PR Merge → GH Actions (build + test + push) → ArgoCD sync staging → Integration tests 
→ Argo Rollouts canary (5% → 10min → 100%) → Promote or Rollback
```

---

### 4.15 Security & Secrets

| Tool | Purpose |
|------|---------|
| **AWS KMS** | Envelope encryption, JWT RS256 signing, HSM-backed key storage |
| **AWS Secrets Manager** | Database credentials, API keys, webhook secrets. Rotated < 90 days. |
| **HashiCorp Vault** | Dynamic DB credentials (short-lived), PKI for mTLS certs |
| **AWS WAF** | DDoS protection, SQL injection rules, IP reputation |
| **AWS Shield Advanced** | Layer 3/4 DDoS mitigation |
| **Cert-Manager** | Automated TLS certificate issuance + rotation via Let's Encrypt |
| **OPA / Gatekeeper** | Kubernetes policy enforcement (no privileged pods, image source validation) |

---

### 4.16 Cloud Provider

**Decision**: **AWS** (single cloud, multi-region).

| Criteria | **AWS ✅** | GCP | Azure |
|----------|:--:|:--:|:--:|
| **Managed Postgres** | ★★★★★ | ★★★★☆ | ★★★★☆ |
| **Managed Kafka** | ★★★★☆ | ★★★★★ | ★★★☆☆ |
| **Managed K8s** | ★★★★★ | ★★★★★ | ★★★★☆ |
| **Regional presence** | ★★★★★ | ★★★★☆ | ★★★☆☆ |
| **Team expertise** | ★★★★★ | ★★★☆☆ | ★★★☆☆ |
| **Score** | **94** | **80** | **72** |

**Multi-Cloud Consideration**: Rejected for initial launch. Multi-cloud adds operational complexity (different APIs, different IAM models, cross-cloud networking costs) without proportional benefit at < 10M transactions/month. Revisit in Phase 30 (Evolution & FinOps).

---

## 5. Architecture Decision Records

ADR index at `docs/adr/README.md`. Key decisions from this phase:

| ADR | Decision | Status |
|-----|----------|--------|
| ADR-001 | Go 1.22+ as primary language for all microservices | ✅ Accepted |
| ADR-002 | PostgreSQL (Aurora) as sole relational database | ✅ Accepted |
| ADR-003 | Apache Kafka (MSK) for event streaming | ✅ Accepted |
| ADR-004 | Istio Ambient Mesh over sidecar Istio | ✅ Accepted |
| ADR-005 | Outbox + Debezium CDC over dual-write or transactional outbox | ✅ Accepted |
| ADR-006 | OpenAPI 3.1 Contract-First API design | ✅ Accepted |
| ADR-007 | Avro + Confluent Schema Registry for event schemas | ✅ Accepted |
| ADR-008 | ArgoCD + Argo Rollouts for GitOps canary deployment | ✅ Accepted |
| ADR-009 | OpenTelemetry (OTLP) for observability | ✅ Accepted |
| ADR-010 | AWS single-cloud, multi-region (active-passive) | ✅ Accepted |

---

## 6. Example Deliverables

### 6.1 Technology Stack Summary

```
┌─────────────────────────────────────────────────────────┐
│                    CLIENT LAYER                          │
│  Mobile SDK (Kotlin/Swift) | Web SDK (TypeScript)       │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                    EDGE LAYER                            │
│  CloudFront | AWS WAF | AWS Shield Advanced             │
│  TLS 1.3 termination | DDoS mitigation                  │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                    API GATEWAY                           │
│  Kong Gateway 3.x (EKS, DB-less)                        │
│  Auth (JWT RS256) | Rate Limiting | Idempotency         │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                 SERVICE LAYER (Go 1.22+)                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ Payment  │ │ Refund   │ │ Wallet   │ │ Payout   │   │
│  │ Service  │ │ Service  │ │ Service  │ │ Service  │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│  Istio Ambient (ztunnel + waypoint) | mTLS              │
│  OpenTelemetry (OTLP) | go-chi/chi | sqlc               │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                 DATA LAYER                               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │PostgreSQL│ │  Redis   │ │  Kafka   │ │OpenSearch│   │
│  │(Aurora)  │ │(ElastiC.) │ │  (MSK)   │ │(Managed) │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│  Debezium CDC | Confluent Schema Registry               │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│              INFRASTRUCTURE & PLATFORM                   │
│  EKS 1.30 | Terraform | ArgoCD | GitHub Actions         │
│  Prometheus | Grafana | Jaeger | Fluent Bit             │
│  AWS KMS | Vault | Secrets Manager | Cert-Manager       │
└─────────────────────────────────────────────────────────┘
```

---

## 7. Key Questions

| # | Question | Answer |
|---|----------|--------|
| Q1 | Why not use a multi-cloud strategy? | Multi-cloud adds 2–3× operational complexity (different APIs, IAM, networking). At < 10M tx/month, the cost of multi-cloud engineering exceeds the benefit. Revisit in Phase 30. |
| Q2 | Why Go and not Rust for the ledger service? | The ledger's critical path is the `create_journal_entry` PostgreSQL procedure, not application code. Go's performance envelope (sub-ms GC, goroutine concurrency) is sufficient. Rust's learning curve and hiring difficulty don't justify the marginal latency improvement (~0.5ms vs Go ~2ms). |
| Q3 | Can we use Kafka without Zookeeper? | Yes. Kafka 3.7 (MSK) runs in KRaft mode (no Zookeeper). Reduces operational complexity and improves controller failover times. |
| Q4 | What happens if Confluent Schema Registry becomes unavailable? | Producers cache the latest schema ID locally. Consumers cache deserialized schemas. Only schema registration and new consumer startup are blocked. Existing pipelines continue unaffected. |
| Q5 | Why not use AWS Lambda for event-driven consumers? | Lambda's 15-minute max execution time and cold-start latency (~200ms–2s) violate the P99 < 250ms latency budget for payment processing. Consumer services are long-running EKS pods. |

---

## 8. Implementation Tasks

### P0 — Critical Path

- [ ] **T01**: Write ADR-001 through ADR-010 in `docs/adr/` directory.
- [ ] **T02**: Create the ADR index (`docs/adr/README.md`) with status tracking.
- [ ] **T03**: Finalize Go project layout standard (monorepo vs. polyrepo) — capture as ADR-011.
- [ ] **T04**: Set up base Docker images (Go 1.22+ Alpine) with vulnerability scanning (Trivy).

### P1 — Before Phase 13 (Platform Core)

- [ ] **T05**: Initialize Go module structure and dependency management (`go mod`).
- [ ] **T06**: Set up Terraform module structure for all AWS resources.
- [ ] **T07**: Deploy base EKS cluster with Istio Ambient, Kong, ArgoCD.
- [ ] **T08**: Provision MSK cluster, ElastiCache cluster, Aurora clusters.

### P2 — Before Phase 15 (Developer Platform)

- [ ] **T09**: Create service scaffold CLI (`platform new --name payment-service`).
- [ ] **T10**: Set up local development environment (Docker Compose: PostgreSQL, Redis, Kafka, OpenSearch).
- [ ] **T11**: Configure OTel collector for local tracing (Jaeger all-in-one).

---

## 9. Common Mistakes

| Mistake | Consequence | Prevention |
|---------|-------------|-----------|
| **Selecting technology before defining requirements** | Chosen technology doesn't meet SLOs → re-architecture mid-build. | Technology selection follows from Phases 02, 06, 08, 09. |
| **Over-indexing on hype** | Adopting a trendy tool (e.g., Rust for CRUD, WASM for business logic) that the team can't operate. | Weigh team expertise at 20% in evaluation matrix. |
| **Underestimating operational complexity** | Choosing Kafka but not budgeting for broker maintenance, partition rebalancing, consumer lag monitoring. | Every selection includes operational maturity score. Managed services preferred where available. |
| **No ADRs** | 6 months later: "Why did we choose Kafka?" → no record → wrong decision gets re-litigated. | ADR for every tier-1 decision. |
| **Vendor lock-in without exit plan** | Deep AWS integration makes migration to GCP prohibitively expensive. | Open-source tools where possible (Kong, Istio, ArgoCD, OpenTelemetry). AWS-specific only where managed service is critical (Aurora, MSK). |

---

## 10. KPIs & Exit Criteria

| # | Criterion | Target | Measurement |
|---|-----------|--------|-------------|
| K01 | Technology coverage | 100% of 16 tiers have documented selection with alternatives | This document |
| K02 | ADR coverage | ≥ 10 ADRs with status and rationale | `docs/adr/` directory |
| K03 | Evaluation consistency | All tiers evaluated using the 5-dimension matrix | Document review |
| K04 | Managed service preference | ≥ 80% of stateful services use managed AWS offerings | Infrastructure inventory |
| K05 | Open-source preference | ≥ 70% of infrastructure tools are open-source (portable) | Technology inventory |
| K06 | Team expertise alignment | All selected technologies have team familiarity or ≤ 2-week learning curve | Team survey |

**Exit Gate**: All K01–K06 must be ✅ before ARB sign-off.

---

## 11. Connection to Next Phase

| Downstream Phase | How Technology Selection Connects |
|-----------------|------------------------|
| **Phase 12 — Infrastructure Design** | Terraform modules provision EKS, Aurora, MSK, ElastiCache, OpenSearch. VPC design, IAM roles, and security groups derived from the technology choices here. |
| **Phase 13 — Platform Core** | Go libraries (`@app/core`) implement the selected framework stack: chi router, sqlc codegen, confluent-kafka-go, hamba/avro, OpenTelemetry. |
| **Phase 15 — Developer Platform** | Local development environment mirrors the selected stack: Docker Compose with PostgreSQL, Redis, Kafka, OpenSearch. Scaffold CLI generates Go service templates with the chosen libraries. |
| **Phase 16 — CI/CD** | GitHub Actions workflows, ArgoCD application manifests, and Argo Rollouts configurations implement the CI/CD tooling selected here. |

---

### 🛑 APPROVAL GATE → 🏗️ Architecture Review Board

**Checklist**:

- [ ] All 16 technology tiers have selection, alternatives, and rationale documented
- [ ] Evaluation matrix applied consistently across all tiers
- [ ] All selections align with Phase 06 architecture requirements and Phase 10 latency budgets
- [ ] ADR index created with ≥ 10 decisions
- [ ] Managed service preference justified for Aurora, MSK, ElastiCache, OpenSearch
- [ ] Open-source preference justified for Kong, Istio, ArgoCD, OpenTelemetry
- [ ] Team expertise evaluated for all selected technologies
