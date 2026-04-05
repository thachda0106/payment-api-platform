# Phase 06 — High-Level Architecture

## MoMo-like Payment API Platform

> **Document Status**: Draft v2.0 — Revised after Principal Architect Review  
> **Last Updated**: 2026-04-05  
> **Classification**: CONFIDENTIAL — Internal Use Only  
> **Audience**: Engineering Leadership, Architecture Review Board  
> **Input**: Phase 04 — Domain Design (v7.0, Approved), Phase 05 — Security Architecture (v2.0, Pending)  
> **Author Level**: Principal/Staff Engineer  
> **Approval Gate**: 🏗️ Architecture Review (Staff Eng + Principal Eng sign-off)  
> **Review History**: v1.0 reviewed by Principal Architect; v2.0 incorporates all P0/P1 findings

---

## Table of Contents

1. [Goal & Scope](#1-goal--scope)
2. [Architecture Principles](#2-architecture-principles)
3. [Architecture Style Decision](#3-architecture-style-decision)
4. [System Architecture Diagram](#4-system-architecture-diagram)
5. [Service Catalog](#5-service-catalog)
6. [API Gateway Design](#6-api-gateway-design)
7. [Service Communication](#7-service-communication)
8. [Resilience Patterns](#8-resilience-patterns)
9. [Caching Strategy](#9-caching-strategy)
10. [Service Mesh & Networking](#10-service-mesh--networking)
11. [Deployment Architecture](#11-deployment-architecture)
12. [Data Consistency & Financial Integrity](#12-data-consistency--financial-integrity) *(v2.0)*
13. [Infrastructure Degradation Modes](#13-infrastructure-degradation-modes) *(v2.0)*
14. [Observability Architecture](#14-observability-architecture) *(v2.0)*
15. [Disaster Recovery & Multi-Region](#15-disaster-recovery--multi-region) *(v2.0)*
16. [Scalability Roadmap](#16-scalability-roadmap) *(v2.0)*
17. [Critical User Journey Flows](#17-critical-user-journey-flows) *(v2.0)*
18. [Team Topology & Conway's Law](#18-team-topology--conways-law)
19. [Architecture Decision Records](#19-architecture-decision-records)
20. [KPIs & Exit Criteria](#20-kpis--exit-criteria)
21. [Connection to Next Phase](#21-connection-to-next-phase)

---

## 1. Goal & Scope

### 1.1 Goal

Design the system-level architecture: define how bounded contexts (Phase 04) map to deployable services, how services interact (sync/async), how requests flow from client to data store and back, and which resilience patterns protect against cascading failures. All architecture decisions are informed by security boundaries (Phase 05).

### 1.2 Architecture Inputs from Previous Phases

| Phase | Architecture Input |
|-------|-------------------|
| **Phase 01** | 17 services identified, 14 user journeys, 8 subsystems, Year 1 scale: 1M users, 300K txns/day, 70 peak TPS |
| **Phase 02** | NFR matrix (17 services), SLO targets (99.99% Tier 0), capacity model (35 instances, ~90 vCPU), latency budgets |
| **Phase 03** | Risk register (31 risks), STRIDE threat model, SPOF analysis, third-party risk matrix, scalability cliff analysis |
| **Phase 04** | 17 bounded contexts (6 Core + 8 Supporting + 5 Generic), domain events catalog, sync/async communication matrix, DB co-location strategy |
| **Phase 05** | Zero Trust model, mTLS + SPIFFE IDs, JWT RS256 auth, RBAC/ABAC, 5 network zones, WAF, PCI zone isolation, service authorization matrix |

### 1.3 Architecture Outputs to Downstream Phases

| Phase | Architecture Output |
|-------|--------------------|
| **Phase 07 — Data** | Storage assignment per service, read/write split strategy, partitioning decisions |
| **Phase 08 — API Design** | Gateway routing rules, BFF patterns, rate limiting tiers |
| **Phase 09 — Event Schema** | Topic-per-context mapping, partition strategy, consumer group design |
| **Phase 10 — System Flows** | End-to-end request paths through this architecture |
| **Phase 11 — Tech Selection** | Technology candidates must satisfy these architecture requirements |
| **Phase 12 — Infrastructure** | VPC layout, EKS cluster topology, load balancer config |

---

## 2. Architecture Principles

| # | Principle | Description | Enforcement |
|---|-----------|-------------|-------------|
| 1 | **Domain-Aligned Services** | Service boundaries match bounded contexts (Phase 04). No cross-domain coupling | Architecture review, service catalog audit |
| 2 | **Async by Default, Sync by Exception** | Inter-service communication is event-driven via Kafka. Sync HTTP only when real-time response is required on the critical path | Communication matrix review, circuit breaker enforcement |
| 3 | **Database per Service** (with strategic co-location) | Each service owns its data. Exceptions: Financial Core (ledger + wallet), Payment (payment + refund) share databases for atomic consistency | DB ownership matrix, cross-DB query detection |
| 4 | **Fail-Closed on Security** | Authentication/authorization failures, fraud check unavailability → block the request, never allow | Circuit breaker defaults, security middleware |
| 5 | **Latency Budget Discipline** | Every request path has an end-to-end latency budget. Each service owns a fraction. Exceeding budget triggers alerts and review | Distributed tracing, SLO burn-rate alerts |
| 6 | **Resilience by Design** | Every inter-service call has timeout, retry, circuit breaker, and fallback strategy defined | Resilience matrix, chaos testing |
| 7 | **Observability First** | Every service emits RED metrics (Rate, Errors, Duration), structured logs, and distributed traces from day one | Platform core library enforcement |
| 8 | **Infrastructure as Code** | All infrastructure provisioned via Terraform/CDK. No manual console operations | IaC pipeline, drift detection |
| 9 | **12-Factor App** | Stateless services, config from environment, port binding, logs to stdout, disposability | Service template enforcement |
| 10 | **Conway's Law Alignment** | Service boundaries align with team boundaries. One team owns ≤ 3 services | Org chart ↔ service catalog mapping |

---

## 3. Architecture Style Decision

### 3.1 Decision: Modular Microservices (Domain-Aligned)

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Style** | Microservices (not monolith-first) | 17 bounded contexts with distinct SLOs, data ownership, and deployment cadences. Monolith would couple Tier 0 financial services (99.99%) with Tier 2 reporting (99.5%) |
| **Modularity** | One deployable service per bounded context | Clear ownership, independent scaling, independent failure domains |
| **Exceptions** | Financial Core is one service (ledger + wallet merged per Phase 04). Payment + Refund share a DB but are separate deployables | Atomic consistency requirements for financial integrity |
| **Monorepo vs Polyrepo** | Monorepo (NX/Turborepo) | Shared platform libraries (@app/core), consistent tooling, atomic cross-service refactors, unified CI/CD |

### 3.2 Alternatives Considered

| Alternative | Rejected Because |
|-------------|-----------------|
| **Monolith-first** | Cannot deliver 99.99% for Tier 0 without coupling to 99.5% Tier 3 services. Independent deployment is a hard requirement for financial services |
| **Modular monolith** | Still couples deployment and failure domains. A bug in reporting could crash payments |
| **Serverless** | Cold start latency incompatible with <200ms P2P SLO. Complex state machines (payment saga) are hard in FaaS. Vendor lock-in risk (BR-06) |
| **Event Sourcing (full)** | Complexity overkill for Year 1. Append-only ledger already provides auditability. Consider for Year 2 read models |

→ **ADR-009: Architecture Style — Modular Microservices**

---

## 4. System Architecture Diagram

### 4.1 Full System Architecture

```
                          ┌─────────────────────────────────────────────┐
                          │              CLIENT LAYER                    │
                          │  Mobile App · Merchant Portal · Admin Web   │
                          │  Partner APIs · Developer Sandbox            │
                          └───────────────────┬─────────────────────────┘
                                              │ HTTPS (TLS 1.3)
                          ┌───────────────────▼─────────────────────────┐
                          │              EDGE LAYER                      │
                          │  CloudFront (CDN) → AWS Shield → WAF        │
                          │  DDoS protection, geo-blocking, bot detect  │
                          └───────────────────┬─────────────────────────┘
                                              │
                          ┌───────────────────▼─────────────────────────┐
                          │         APPLICATION LOAD BALANCER            │
                          │  Health checks, TLS termination, routing    │
                          │  Zone: Public (10.0.1.0/24)                 │
                          └───────────────────┬─────────────────────────┘
                                              │
┌─────────────────────────────────────────────▼───────────────────────────────────────────────┐
│                                    API GATEWAY CLUSTER                                       │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┐   │
│  │  • JWT validation (RS256 public key verify)                                          │   │
│  │  • Rate limiting (Redis-backed: per-IP, per-user, per-merchant)                     │   │
│  │  • Request routing → downstream services                                             │   │
│  │  • CORS, security headers (HSTS, CSP, X-Frame-Options)                              │   │
│  │  • Request/response logging (PII-redacted)                                           │   │
│  │  • Idempotency key validation (X-Idempotency-Key header)                            │   │
│  │  • Correlation ID injection (X-Correlation-Id)                                       │   │
│  │  • BFF routing: /mobile/*, /merchant/*, /admin/*                                    │   │
│  └──────────────────────────────────────────────────────────────────────────────────────┘   │
│  Zone: Application (10.0.10.0/24)                                                           │
└─────────────┬──────────────┬─────────────┬───────────────┬──────────────────────────────────┘
              │              │             │               │
              ▼              ▼             ▼               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              SERVICE MESH (Istio)                                            │
│                              mTLS everywhere, SPIFFE identities                              │
│                                                                                              │
│  ┌─────────────────────────────── TIER 0: CRITICAL (99.99%) ──────────────────────────────┐ │
│  │                                                                                         │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │ │
│  │  │  Financial   │  │   Payment    │  │    Risk &    │  │  Compliance  │               │ │
│  │  │    Core      │  │   Service    │  │    Fraud     │  │   (Limits)   │               │ │
│  │  │ (Ledger +    │  │  (State      │  │   Service    │  │   Service    │               │ │
│  │  │   Wallet)    │  │  Machine      │  │  (Real-time) │  │  (KYC-tier)  │               │ │
│  │  │  3 replicas  │  │   Saga)      │  │  3 replicas  │  │  2 replicas  │               │ │
│  │  │  3 replicas  │  │  3 replicas  │  │              │  │              │               │ │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘               │ │
│  │         │                 │                  │                 │                        │ │
│  └─────────┼─────────────────┼──────────────────┼─────────────────┼────────────────────────┘ │
│            │                 │                  │                 │                          │
│  ┌─────────┼─────────────────┼──────────────────┼─────────────────┼────────────────────────┐ │
│  │ TIER 1: CORE (99.95%)     │                  │                 │                        │ │
│  │         │                 │                  │                 │                        │ │
│  │  ┌──────┴───────┐  ┌─────┴────────┐  ┌─────┴────────┐  ┌────┴─────────┐              │ │
│  │  │  Identity    │  │  Refund &    │  │  Transaction │  │   Merchant   │              │ │
│  │  │  Service     │  │  Reversal    │  │   Service    │  │   Service    │              │ │
│  │  │  2 replicas  │  │  2 replicas  │  │  (Read Model)│  │  1 replica   │              │ │
│  │  │              │  │              │  │  2 replicas  │  │              │              │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘              │ │
│  │  ┌──────────────┐  ┌──────────────┐                                                  │ │
│  │  │  Bank Integ. │  │  Fee Engine  │                                                  │ │
│  │  │  Service     │  │  2 replicas  │                                                  │ │
│  │  │  2 replicas  │  │              │                                                  │ │
│  │  └──────────────┘  └──────────────┘                                                  │ │
│  └──────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                           │
│  ┌──────────────────────────── TIER 2: SUPPORTING (99.9%) ────────────────────────────┐   │
│  │                                                                                     │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐            │   │
│  │  │ Settlement   │  │  Recon       │  │  Dispute     │  │ Notification │            │   │
│  │  │  Service     │  │  Service     │  │  Service     │  │  Service     │            │   │
│  │  │  1 replica   │  │  1 replica   │  │  1 replica   │  │  3 replicas  │            │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘            │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐            │   │
│  │  │  Audit Log   │  │  Reporting   │  │  KYC         │  │ Pay Method   │            │   │
│  │  │  Service     │  │  Service     │  │  Service     │  │  Service     │            │   │
│  │  │  2 replicas  │  │  1 replica   │  │  1 replica   │  │  1 replica   │            │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘            │   │
│  └─────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                            │
│  Zone: Application (10.0.10.0/24) + PCI Zone (10.0.30.0/24) for Financial Core            │
└────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                             │
┌────────────────────────────────────────────▼───────────────────────────────────────────────┐
│                                     DATA LAYER                                              │
│  Zone: Data (10.0.20.0/24) — No internet egress                                            │
│                                                                                              │
│  ┌────────────────── PostgreSQL Clusters ──────────────────┐                                │
│  │                                                          │                                │
│  │  financial_core_db    payment_db      fraud_db           │                                │
│  │  (Ledger + Wallet)    (Payment +      (Rules +           │                                │
│  │  Sync replication     Refund)          Assessments)      │                                │
│  │  1P + 1 Sync + 1R    1P + 1R          1P + 1R           │                                │
│  │                                                          │                                │
│  │  compliance_db   fx_db    treasury_db   settlement_db    │                                │
│  │  account_db    merchant_db  notification_db  audit_db    │                                │
│  │  recon_db      transaction_db  reporting_db              │                                │
│  └──────────────────────────────────────────────────────────┘                                │
│                                                                                              │
│  ┌──── Redis Cluster ────┐  ┌──── Kafka Cluster ────┐  ┌──── OpenSearch ────┐              │
│  │  3 nodes, 15 GB       │  │  3 brokers, RF=3      │  │  2 nodes, 50 GB   │              │
│  │  Sessions, rate limits│  │  Event bus             │  │  Txn search index │              │
│  │  Velocity counters    │  │  Outbox relay          │  │                   │              │
│  │  Balance cache (R/O)  │  │  Async choreography    │  │                   │              │
│  └───────────────────────┘  └───────────────────────┘  └───────────────────┘              │
│                                                                                              │
│  ┌──── S3 ───────────────────────────────────────────────────────────────────┐              │
│  │  KYC documents, audit archives, settlement reports, backups               │              │
│  └───────────────────────────────────────────────────────────────────────────┘              │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                             │
┌────────────────────────────────────────────▼───────────────────────────────────────────────┐
│                               EXTERNAL INTEGRATIONS                                         │
│                                                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                   │
│  │  NAPAS       │  │  Partner     │  │  eKYC        │  │  SMS / Push  │                   │
│  │  (Interbank) │  │  Banks       │  │  Provider    │  │  Gateways    │                   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘                   │
│  ┌──────────────┐  ┌──────────────┐                                                        │
│  │  Utility     │  │  Card        │                                                        │
│  │  Providers   │  │  Networks    │                                                        │
│  └──────────────┘  └──────────────┘                                                        │
│                                                                                              │
│  All external calls via Bank Integration Service (ACL pattern)                              │
│  mTLS + HMAC signatures + IP allowlisting                                                   │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Request Flow (Layered View)

```
Client → CDN → Shield → WAF → ALB → API Gateway → Service Mesh (mTLS) → Service → DB
                                                                            │
                                                                            └→ Outbox → Kafka → Consumers
```

**Latency budget for P2P transfer (SLO: <200ms p50, <500ms p99):**

| Layer | Budget (p50) | Budget (p99) |
|-------|-------------|-------------|
| CDN + Shield + WAF | ~2ms | ~5ms |
| ALB → Gateway | ~3ms | ~10ms |
| Gateway (auth + rate limit) | ~5ms | ~15ms |
| Gateway → Payment Service | ~2ms | ~5ms |
| Risk/Fraud check (sync) | ~10ms | ~40ms |
| Limit check (sync) | ~5ms | ~20ms |
| Fee Engine (sync) | ~5ms | ~15ms |
| Financial Core (journal + balance) | ~30ms | ~80ms |
| Response serialization | ~3ms | ~10ms |
| **Total** | **~65ms** | **~200ms** |
| **Buffer (mesh, GC, network)** | ~35ms | ~100ms |
| **Grand total** | **~100ms** | **~300ms** |

> ✅ Within SLO: <200ms p50, <500ms p99

---

## 5. Service Catalog

### 5.1 Service Registry

| # | Service | Bounded Context | Domain Tier | SLO | Owner Team | Database | Protocol | Replicas |
|---|---------|----------------|-------------|-----|-----------|----------|----------|----------|
| 1 | `financial-core-service` | Financial Core (Ledger + Wallet) | Core | 99.99% | Financial Core Team | `financial_core_db` (PG, sync rep) | gRPC (internal) | 3 |
| 2 | `payment-service` | Payment | Core | 99.99% | Payment Team | `payment_db` (PG) | REST + gRPC | 3 |
| 3 | `risk-fraud-service` | Risk & Fraud | Core | 99.99% | Risk Team | `fraud_db` (PG) + Redis | gRPC (internal) | 3 |
| 4 | `compliance-service` | Compliance / AML | Core → Supporting | 99.99% (limits), 99.9% (AML) | Compliance Team | `compliance_db` (PG) | gRPC (limits), REST (AML) | 2 |
| 5 | `refund-service` | Refund & Reversal | Core | 99.95% | Payment Team | `payment_db` (PG, shared) | REST | 2 |
| 6 | `fx-service` | FX & Multi-Currency | Core | 99.95% | Financial Core Team | `fx_db` (PG) | gRPC | 2 |
| 7 | `treasury-service` | Treasury | Core | 99.9% | Finance Ops Team | `treasury_db` (PG) | REST (admin only) | 1 |
| 8 | `identity-service` | Identity | Supporting | 99.95% | Identity Team | `account_db` (PG) | REST | 2 |
| 9 | `merchant-service` | Merchant | Supporting | 99.95% | Merchant Team | `merchant_db` (PG) | REST | 1 |
| 10 | `fee-engine-service` | Fee Engine / Pricing | Supporting | 99.95% | Payment Team | `payment_db` or cache | gRPC | 2 |
| 11 | `settlement-service` | Settlement | Supporting | 99.9% | Settlement Team | `settlement_db` (PG) | REST (admin) | 1 |
| 12 | `reconciliation-service` | Reconciliation | Supporting | 99.9% | Settlement Team | `recon_db` (PG) | REST (admin) | 1 |
| 13 | `dispute-service` | Dispute | Supporting | 99.9% | Support Team | `dispute_db` (PG) | REST | 1 |
| 14 | `payment-method-service` | Payment Method | Supporting | 99.95% | Identity Team | `payment_method_db` | REST | 1 |
| 15 | `transaction-service` | Transaction (read model) | Generic | 99.9% | Platform Team | `transaction_db` (PG) + OpenSearch | REST | 2 |
| 16 | `notification-service` | Notification | Generic | 99.9% | Platform Team | `notification_db` (PG) | REST (async) | 3 |
| 17 | `audit-service` | Audit | Generic | 99.9% | Platform Team | `audit_db` (TimescaleDB) | gRPC (fire-and-forget) | 2 |
| 18 | `reporting-service` | Reporting | Generic | 99.5% | Platform Team | `reporting_db` (read replicas) | REST | 1 |
| 19 | `bank-integration-service` | Bank Integration | Generic (ACL) | 99.95% | Integration Team | `bank_db` (PG) | REST + bank-specific | 2 |
| 20 | `kyc-service` | KYC (sub of Identity) | Supporting | 99.9% | Identity Team | `account_db` (PG) + S3 | REST | 1 |
| — | `api-gateway` | — | Infrastructure | 99.99% | Platform Team | — (stateless) | REST | 3 |

### 5.2 Service Dependency Map

```
                                    ┌──────────────┐
                                    │  API Gateway  │
                                    │ (entry point) │
                                    └───────┬───────┘
                          ┌─────────────────┼─────────────────┐
                          │                 │                 │
                   ┌──────▼──────┐   ┌──────▼──────┐  ┌──────▼──────┐
                   │  Identity   │   │   Payment   │  │  Merchant   │
                   │  Service    │   │  Service    │  │  Service    │
                   └─────────────┘   └──────┬──────┘  └─────────────┘
                                            │
                        ┌──────────┬────────┼────────┬──────────┐
                        │          │        │        │          │
                 ┌──────▼──┐ ┌────▼───┐ ┌──▼────┐ ┌─▼──────┐ ┌▼──────────┐
                 │Risk/Fraud│ │Compli- │ │  Fee  │ │Financial│ │  Refund   │
                 │ Service  │ │ance    │ │Engine │ │  Core   │ │  Service  │
                 └──────────┘ │(Limits)│ └───────┘ │(Ledger+ │ └───────────┘
                              └────────┘           │ Wallet) │
                                                   └────┬────┘
                                                        │
                              ┌──────────────────────────┤ (async via Kafka)
                              │          │          │    │         │
                       ┌──────▼──┐ ┌─────▼───┐ ┌───▼──┐│  ┌──────▼──────┐
                       │Transac- │ │Notifica-│ │Audit ││  │ Settlement  │
                       │tion Svc │ │tion Svc │ │ Svc  ││  │   Service   │
                       └─────────┘ └─────────┘ └──────┘│  └──────┬──────┘
                                                       │         │
                                                ┌──────▼─────┐   │
                                                │Recon Svc   │   │
                                                └────────────┘   │
                                                          ┌──────▼──────┐
                                                          │Bank Integr. │
                                                          │  Service    │
                                                          └─────────────┘

Legend:
  ──▶  Sync call (HTTP/gRPC, critical path)
  ···▶ Async event (Kafka, outbox/inbox)
```

### 5.3 Critical Path Analysis

The **payment critical path** is the most latency-sensitive chain. All services on this path must be Tier 0 (99.99%):

```
Payment Service ──sync──▶ Risk/Fraud Service     (≤50ms p99)
Payment Service ──sync──▶ Compliance/Limits       (≤30ms p99)
Payment Service ──sync──▶ Fee Engine              (≤30ms p99)
Payment Service ──sync──▶ Financial Core          (≤100ms p99)
```

**Composite availability**: 99.99%⁴ ≈ 99.96%

**Mitigation to achieve 99.99% user-facing**:
- Financial Core is co-located DB (no network hop for ledger + wallet)
- Fee Engine can degrade: if unavailable, use cached fee schedule (stale ≤ 5 min)
- All calls have circuit breakers with fail-fast
- Retry only for transient errors (timeout, 503), never for business errors (insufficient balance)

---

## 6. API Gateway Design

### 6.1 Gateway Responsibilities

| Responsibility | Implementation | Notes |
|---------------|---------------|-------|
| **Authentication (AuthN)** | JWT RS256 verification using KMS public key | Stateless — no DB lookup per request. Token blacklist check via Redis |
| **Rate Limiting** | Redis-backed sliding window counter | Per-IP, per-User, per-Merchant (separate policies) |
| **Request Routing** | Path-based routing to downstream services | `/v1/payments/*` → payment-service, `/v1/wallets/*` → financial-core-service |
| **BFF Routing** | Client-specific prefixes for Backend-for-Frontend | `/mobile/*`, `/merchant/*`, `/admin/*` route to different aggregation logic |
| **CORS** | Configurable per client type | `merchant.paywallet.vn`, `admin.paywallet.vn` allowlisted |
| **Security Headers** | HSTS, CSP, X-Frame-Options, X-Content-Type-Options | Enforced at gateway, not per-service |
| **Idempotency** | `X-Idempotency-Key` validation for state-mutating requests | Redis-backed with 24h TTL, gateway validates presence |
| **Correlation ID** | `X-Correlation-Id` injection | Generated at gateway, propagated through all services |
| **Request Logging** | Structured JSON log with PII redaction | Logged at gateway, not per-service (avoid duplication) |
| **Response Transformation** | Error format normalization (RFC 7807) | Consistent `{type, title, status, detail, instance}` |

### 6.2 Gateway Architecture

```
                           ┌─────────────────────────────────────────┐
                           │              API GATEWAY                 │
                           │                                          │
                           │  ┌─────────────────────────────────────┐│
                           │  │         MIDDLEWARE PIPELINE          ││
                           │  │                                      ││
                           │  │  1. CORS check                       ││
                           │  │  2. Security headers injection       ││
                           │  │  3. Rate limit check (Redis)         ││
                           │  │  4. JWT verification (RS256)         ││
                           │  │  5. Token blacklist check (Redis)    ││
                           │  │  6. Idempotency key validation       ││
                           │  │  7. Correlation ID injection         ││
                           │  │  8. Request logging (PII-redacted)   ││
                           │  │  9. Route matching → upstream proxy  ││
                           │  │ 10. Response transformation          ││
                           │  │ 11. Response logging                 ││
                           │  └─────────────────────────────────────┘│
                           └─────────────────────────────────────────┘
```

### 6.3 Rate Limiting Strategy

| Client Type | Endpoint Category | Rate Limit | Window | Key |
|------------|-------------------|-----------|--------|-----|
| Anonymous / Unauthenticated | Auth endpoints (`/auth/*`) | 5 req/sec | Sliding 1s | IP |
| Authenticated User | Read endpoints | 50 req/sec | Sliding 1s | `user_id` |
| Authenticated User | Payment endpoints | 10 req/sec | Sliding 1s | `user_id` |
| Authenticated User | OTP endpoints | 5 req/hour | Fixed 1h | Phone number |
| Merchant API | Payment creation | 100 req/sec | Sliding 1s | `merchant_id` |
| Merchant API | Read endpoints | 200 req/sec | Sliding 1s | `merchant_id` |
| Admin | All endpoints | 50 req/sec | Sliding 1s | `admin_id` |
| **Global** | All | 10,000 req/sec | Sliding 1s | Global | 

**Rate limit response**: HTTP `429 Too Many Requests` with `Retry-After` header.

### 6.4 Gateway Decision: Custom NestJS Gateway (NOT Managed)

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Type** | Custom API Gateway (NestJS-based) | Full control over middleware pipeline, custom BFF logic, no vendor lock-in |
| **Alternative: AWS API Gateway** | Rejected | Limited customization, cold start on Lambda integrations, hard to replicate in staging/local |
| **Alternative: Kong** | Considered for Year 2 | Kong is excellent but adds operational complexity. Custom gateway is simpler for Year 1 with 20 services |

→ **ADR-010: API Gateway — Custom NestJS Gateway**

---

## 7. Service Communication

### 7.1 Communication Matrix

| Caller | Callee | Pattern | Protocol | Latency Budget | Rationale |
|--------|--------|---------|----------|--------|-----------|
| Gateway → Identity | Sync | REST | ≤50ms | Auth verification on every request |
| Gateway → Payment | Sync | REST | ≤500ms | User-facing payment request |
| Gateway → Financial Core | Sync | REST | ≤100ms | Balance query |
| Gateway → Merchant | Sync | REST | ≤200ms | Merchant operations |
| Gateway → Transaction | Sync | REST | ≤200ms | Transaction history/search |
| Payment → Risk/Fraud | **Sync** | **gRPC** | ≤50ms | Real-time fraud decision, fail-closed |
| Payment → Compliance (Limits) | **Sync** | **gRPC** | ≤30ms | Limit enforcement, fail-closed |
| Payment → Fee Engine | **Sync** | **gRPC** | ≤30ms | Fee calculation before journal write |
| Payment → Financial Core | **Sync** | **gRPC** | ≤100ms | Write journal entry + update balance |
| Refund → Financial Core | Sync | gRPC | ≤100ms | Refund journal entry |
| FX → Financial Core | Sync | gRPC | ≤100ms | Cross-currency journal entries |
| Settlement → Financial Core | Sync | gRPC | ≤100ms (batch) | Settlement journal entries |
| Treasury → Bank Integration | Sync | REST | ≤200ms | Inter-bank transfers |
| Financial Core → Transaction | **Async** | **Kafka** | N/A | Update read model (eventual) |
| Financial Core → Notification | **Async** | **Kafka** | N/A | Balance change notifications |
| Financial Core → Audit | **Async** | **Kafka** | N/A | Financial audit trail |
| Financial Core → Reporting | **Async** | **Kafka** | N/A | Dashboard updates |
| Financial Core → Reconciliation | **Async** | **Kafka** | N/A | Reconciliation data |
| Payment → Transaction | **Async** | **Kafka** | N/A | Payment result read model |
| Payment → Notification | **Async** | **Kafka** | N/A | Payment notifications |
| Payment → Audit | **Async** | **Kafka** | N/A | Payment audit trail |
| Payment → Settlement | **Async** | **Kafka** | N/A | Settlement aggregation |
| Identity → Financial Core | **Async** | **Kafka** | N/A | Create wallet on registration |
| Compliance → Payment | **Async** | **Kafka** | N/A | Account restriction updates |

### 7.2 Protocol Selection

| Protocol | Used For | Rationale |
|----------|---------|-----------|
| **REST (HTTP/1.1 + JSON)** | External APIs (mobile, merchant, admin), bank callbacks | Universal compatibility, straightforward debugging, well-tooled |
| **gRPC (HTTP/2 + Protobuf)** | Internal sync calls between Tier 0 services | ~10x faster serialization, HTTP/2 multiplexing, strong typing via proto contracts, streaming support |
| **Kafka (async)** | All event-driven communication | Durable, ordered (per-partition), replay capability, at-least-once guaranteed |

**Rule**: External-facing = REST. Internal critical path = gRPC. Cross-service side-effects = Kafka.

### 7.3 Outbox/Inbox Pattern

All async communication uses the transactional outbox pattern (defined in Phase 04, Phase 09):

```
Producer Service:
  BEGIN TX
    1. Business logic (e.g., commit journal entry)
    2. INSERT INTO outbox_events (event_type, payload, status='PENDING')
  COMMIT TX

Outbox Relay (separate process):
  POLL outbox_events WHERE status='PENDING' ORDER BY created_at LIMIT 100
  FOR EACH event:
    Publish to Kafka topic
    UPDATE outbox_events SET status='PUBLISHED'

Consumer Service:
  Consume from Kafka topic
  BEGIN TX
    1. INSERT INTO inbox_events (event_id) — dedup check (UNIQUE constraint)
    2. Process event (idempotent business logic)
    3. COMMIT
  ON DUPLICATE KEY → skip (already processed)
```

---

## 8. Resilience Patterns

### 8.1 Resilience Matrix

Every inter-service call on the critical path has a defined resilience strategy:

| Caller → Callee | Timeout | Retry | Circuit Breaker | Fallback | Bulkhead |
|-----------------|---------|-------|-----------------|----------|----------|
| Payment → Risk/Fraud | 100ms | 0 (no retry) | YES: open after 5 failures in 10s, half-open after 30s | **BLOCK** (fail-closed) | YES: isolated thread pool |
| Payment → Compliance | 80ms | 0 | YES: open after 5/10s | **BLOCK** (fail-closed) | YES |
| Payment → Fee Engine | 80ms | 1 (idempotent) | YES: open after 5/10s | Use cached fee schedule (≤5 min stale) | YES |
| Payment → Financial Core | 200ms | 1 (idempotent, same key) | YES: open after 3/10s | **FAIL** (no fallback for money movement) | YES |
| Gateway → Identity | 100ms | 1 | YES: open after 10/30s | 401 Unauthorized | YES |
| Gateway → Any Service | 5s | 0 | YES: per-service | 503 Service Unavailable | YES: per-route |
| Settlement → Financial Core | 500ms | 3 (batch retry) | YES | Queue and retry | NO (batch) |
| Any → Redis | 50ms | 1 | YES: open after 10/10s | Bypass cache, use DB | YES |
| Any → Kafka (produce) | 5s | 3 | NO (outbox pattern handles) | Write to outbox (guaranteed) | NO |
| Bank Integration → Bank API | 30s | 3 (exponential) | YES: per-bank | Mark PENDING, reconcile later | YES: per-bank |

### 8.2 Circuit Breaker Configuration

```
Default Circuit Breaker Settings:
  failure_threshold:    5 failures within window
  window_duration:      10 seconds
  open_duration:        30 seconds (before half-open)
  half_open_max:        3 requests (test if service recovered)
  success_threshold:    2 consecutive successes to close

Critical Override (Financial Core):
  failure_threshold:    3 failures within window
  window_duration:      10 seconds
  open_duration:        15 seconds (recover faster)
```

### 8.3 Timeout Budget Enforcement

Every request receives a **deadline** at the API Gateway. The deadline propagates through all downstream calls via gRPC deadline propagation or HTTP `X-Request-Deadline` header.

```
Gateway sets deadline: now() + 5000ms

Payment Service receives: remaining = 4990ms
  → Allocates: Fraud=100ms, Limits=80ms, Fee=80ms, FinCore=200ms
  → Reserves: 100ms for own processing + response

If any downstream call exceeds its allocation → cancel and fail fast
If remaining deadline < minimum needed → fail immediately (no cascading timeout)
```

### 8.4 Bulkhead Isolation

Each downstream dependency gets its own connection pool / thread pool:

```
Payment Service bulkheads:
  fraud_pool:    max_concurrent=50,  queue_size=20
  limit_pool:    max_concurrent=30,  queue_size=10
  fee_pool:      max_concurrent=30,  queue_size=10
  fincore_pool:  max_concurrent=100, queue_size=50
  redis_pool:    max_concurrent=50,  queue_size=20
```

If one pool is exhausted, others continue operating. A slow Fraud Service does not block Financial Core calls.

---

## 9. Caching Strategy

### 9.1 Cache Layers

```
Client ──▶ CDN Cache ──▶ API Gateway Cache ──▶ Service Cache ──▶ Database
  (1)         (2)              (3)                 (4)           (source)
```

| Layer | Technology | What is Cached | TTL | Invalidation |
|-------|-----------|---------------|-----|-------------|
| **(1) Client** | Mobile app local storage | User profile, fee schedules, exchange rates | 5 min – 1 hour | On-demand refresh, push notification |
| **(2) CDN** | CloudFront | Static assets, API docs, Swagger UI | 1 hour – 24 hours | Cache invalidation on deploy |
| **(3) Gateway** | In-memory (LRU) | JWT public key, rate limit configs, route configs | 5 min | Restart on config change |
| **(4a) Service** | Redis (shared) | Session data, rate limit counters, velocity counters, token blacklist, balance snapshots (read-only) | Varies per key | Event-driven invalidation + TTL expiry |
| **(4b) Service** | In-memory | Fee schedules, fraud rules, exchange rates, KYC tier limits | 1 – 5 min | Event-driven reload |

### 9.2 Cache-Aside Pattern (Read Path)

```
Client requests balance:

1. Service checks Redis: GET wallet:balance:{user_id}
2. Cache HIT → return cached balance (read-only, may be 1-2s stale)
3. Cache MISS → query financial_core_db → SET wallet:balance:{user_id} TTL 30s → return

Write path (balance change):
1. Financial Core commits journal + balance update
2. Outbox event: WalletBalanceUpdated
3. Event published to Kafka
4. Cache invalidator consumer: DEL wallet:balance:{user_id}
5. Next read → cache miss → fresh value from DB
```

### 9.3 Cache Stampede Prevention

For high-read keys (exchange rates, fee schedules), use **probabilistic early expiration**:

```
On cache HIT:
  remaining_ttl = TTL(key)
  if remaining_ttl < EARLY_EXPIRE_WINDOW (e.g., 5s):
    if random() < 0.1:  // 10% chance of early refresh
      refresh_in_background()  // non-blocking
  return cached_value
```

### 9.4 What is NOT Cached

| Data | Why Not Cached |
|------|---------------|
| **Wallet available balance** (write path) | Must always read from DB for consistency. `SELECT FOR UPDATE` on every debit |
| **Payment state transitions** | State machine must be consistent — always read from payment_db |
| **Ledger journal entries** | Append-only, immutable — no benefit from caching writes |
| **Audit events** | Write-heavy, append-only — caching doesn't help |

→ **ADR-011: Caching Strategy — Cache-Aside with Event-Driven Invalidation**

---

## 10. Service Mesh & Networking

### 10.1 Service Mesh: Istio

| Aspect | Configuration |
|--------|-------------|
| **mTLS** | STRICT mode — all pod-to-pod traffic encrypted |
| **Identity** | SPIFFE IDs: `spiffe://paywallet.vn/<service-name>` |
| **Cert lifetime** | 1 hour (auto-rotated by SPIRE) |
| **Authorization** | Istio `AuthorizationPolicy` enforcing service authorization matrix (Phase 05 §4.6) |
| **Traffic management** | Per-service circuit breakers, retries, timeouts via `DestinationRule` |
| **Observability** | Automatic distributed tracing (OpenTelemetry), access logs, Prometheus metrics |

### 10.2 Network Zones (from Phase 05)

| Zone | Subnet | Services | Ingress | Egress |
|------|--------|----------|---------|--------|
| **Public** | `10.0.1.0/24` | ALB, WAF | Internet (443) | Application zone |
| **Application** | `10.0.10.0/24` | All microservices (EKS) | Public zone ALB only | Data zone, NAT for external APIs |
| **Data** | `10.0.20.0/24` | PostgreSQL, Redis, Kafka, OpenSearch, S3 | Application zone only | **NO internet egress** |
| **PCI/Core** | `10.0.30.0/24` | Financial Core, Bank Integration | Application zone (specific ports) | Bank partner IPs (allowlisted) |
| **Admin** | `10.0.99.0/28` | Bastion / Access Proxy | Corporate VPN only | Application + Data zone |

### 10.3 Kubernetes Network Policies

Default deny all. Explicit allow rules per service:

```yaml
# Example: Payment Service network policy
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: payment-service
spec:
  podSelector:
    matchLabels:
      app: payment-service
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - podSelector: { matchLabels: { app: api-gateway } }
      ports: [{ port: 3000, protocol: TCP }]
  egress:
    - to:
        - podSelector: { matchLabels: { app: risk-fraud-service } }
        - podSelector: { matchLabels: { app: compliance-service } }
        - podSelector: { matchLabels: { app: fee-engine-service } }
        - podSelector: { matchLabels: { app: financial-core-service } }
      ports: [{ port: 50051, protocol: TCP }]  # gRPC
    - to:
        - podSelector: { matchLabels: { app: redis } }
      ports: [{ port: 6379, protocol: TCP }]
    - to:
        - podSelector: { matchLabels: { app: kafka } }
      ports: [{ port: 9092, protocol: TCP }]
```

---

## 11. Deployment Architecture

### 11.1 Kubernetes Cluster Topology

| Component | Configuration |
|-----------|-------------|
| **EKS Cluster** | 1 production cluster, multi-AZ (3 AZs) |
| **Node Groups** | Tier 0 services: dedicated node group (tainted, tolerations). Tier 1-2: shared general node group |
| **Namespaces** | `financial-core`, `payment`, `identity`, `merchant`, `platform`, `monitoring`, `istio-system` |
| **Pod Anti-Affinity** | Tier 0 services spread across AZs (hard anti-affinity) |

### 11.2 Service Tier Deployment Strategy

| Tier | Deployment Strategy | Rollback | Health Check |
|------|-------------------|----------|-------------|
| **Tier 0** (Financial Core, Payment, Fraud, Limits) | Canary (5% → 25% → 50% → 100%) with automated SLI validation at each step | Auto-rollback if error rate > 0.1% or p99 latency > 2x baseline | Readiness + liveness probes, startup probe (30s grace) |
| **Tier 1** (Identity, Refund, Transaction, Merchant, Bank, Fee) | Blue/Green deployment | Auto-rollback on health check failure | Readiness + liveness probes |
| **Tier 2** (Settlement, Recon, Dispute, Notification, Audit, Reporting, KYC) | Rolling update (25% max unavailable) | Manual rollback | Readiness + liveness probes |

### 11.3 Resource Allocation per Service

| Service | CPU Request | CPU Limit | Memory Request | Memory Limit | HPA Min | HPA Max | Scale Metric |
|---------|-----------|----------|---------------|-------------|---------|---------|-------------|
| `financial-core-service` | 1000m | 2000m | 2Gi | 4Gi | 3 | 8 | CPU > 70% |
| `payment-service` | 1000m | 2000m | 2Gi | 4Gi | 3 | 10 | CPU > 70%, RPS > 200 |
| `risk-fraud-service` | 1000m | 2000m | 2Gi | 4Gi | 3 | 8 | CPU > 60% |
| `compliance-service` | 500m | 1000m | 1Gi | 2Gi | 2 | 5 | CPU > 70% |
| `identity-service` | 500m | 1000m | 1Gi | 2Gi | 2 | 5 | CPU > 70% |
| `notification-service` | 500m | 1000m | 1Gi | 2Gi | 3 | 10 | Kafka lag > 100 |
| All other services | 500m | 1000m | 1Gi | 2Gi | 1 | 3 | CPU > 70% |
| `api-gateway` | 1000m | 2000m | 2Gi | 4Gi | 3 | 10 | CPU > 60%, RPS > 1000 |

### 11.4 Feature Flags

Feature flags decouple deployment from release:

| Flag | Purpose | Default | Owner |
|------|---------|---------|-------|
| `payments.enabled` | Kill switch for all payment processing | ON | SRE |
| `payments.p2p.enabled` | Kill switch for P2P transfers | ON | Payment Team |
| `payments.qr.enabled` | Kill switch for QR payments | ON | Payment Team |
| `fraud.ml_model.enabled` | Toggle ML fraud model (Year 2) | OFF | Risk Team |
| `settlement.auto.enabled` | Enable/disable automated EOD settlement | ON | Settlement Team |
| `notification.sms.enabled` | Toggle SMS delivery (cost control) | ON | Platform Team |
| `fx.enabled` | Toggle multi-currency support | OFF | Financial Core |

---

## 12. Data Consistency & Financial Integrity

> *This section defines how the architecture guarantees that no money is created, lost, or double-counted across all failure scenarios.*

### 12.1 Source of Truth Hierarchy

| Data | Source of Truth | Projection/Cache | Staleness Tolerance |
|------|----------------|------------------|-------------------|
| **Wallet balance** | `financial_core_db.journal_lines` (calculated: sum of all credit lines − sum of all debit lines per wallet) | `wallets.available_balance` (materialized column updated in same TX as journal write) | **0** — write path always uses DB. Read-only cache may lag 1-2s for display |
| **Payment state** | `payment_db.payments.status` (state machine) | Transaction Service read model | Eventual (seconds) |
| **Ledger integrity** | `sum(debit_lines) == sum(credit_lines)` per journal entry, enforced by DB CHECK constraint | Reconciliation job every 15 min validates global invariant | **0** — any violation triggers P0 alert |
| **Transaction history** | `payment_db` (writes), `transaction_db` + OpenSearch (reads) | OpenSearch index | Eventual (seconds) |

### 12.2 Payment Saga Failure Modes

The Payment Service orchestrates a sequential saga. Each step must handle failure:

```
Step 1: Fraud Check      →  PASS/FAIL  →  If FAIL: reject immediately, no side effects
Step 2: Limit Check      →  PASS/FAIL  →  If FAIL: reject immediately, no side effects
Step 3: Fee Calculation   →  amount     →  If FAIL: use cached fee schedule (or reject)
Step 4: Financial Core    →  journal    →  If FAIL: *** CRITICAL *** — see below
```

**Step 4 failure handling (Financial Core write fails after steps 1-3 pass):**

| Failure Type | Behavior | Rationale |
|-------------|----------|-----------|
| **Timeout (no response)** | **Do NOT retry blindly**. Query Financial Core with idempotency key to check if journal was committed. If committed → success. If not → retry once with same key | Prevents double-journal (double-debit) |
| **Explicit rejection (insufficient balance, constraint violation)** | Fail the payment with appropriate error code. No compensation needed (no money moved) | Steps 1-3 are read-only checks, no state mutation |
| **Circuit breaker open** | Fail immediately with `503 SERVICE_UNAVAILABLE`. Client can retry | Payment Service has no pending state to compensate |

**Key insight**: Steps 1-3 are stateless checks. No saga compensation is needed because **money only moves in Step 4** (single atomic DB transaction in Financial Core). This is not a distributed saga — it's a sequential gatekeeper pattern.

### 12.3 Idempotency Architecture

| Layer | Mechanism | Key Format | Storage | TTL |
|-------|-----------|-----------|---------|-----|
| **API Gateway** | `X-Idempotency-Key` header check | Client-provided UUID | Redis | 24h |
| **Payment Service** | `payment_id` UNIQUE constraint | `pay_{ulid}` | `payment_db` | Permanent |
| **Financial Core** | `journal_id` UNIQUE constraint + idempotency_key column | Deterministic: `hash(payment_id + operation)` | `financial_core_db` | Permanent |
| **Kafka consumers** | Inbox table with `event_id` UNIQUE constraint | From outbox `event_id` | Consumer's own DB | 30 days |

### 12.4 Event Ordering Guarantees

| Guarantee | Mechanism |
|-----------|-----------|
| **Per-wallet ordering** | Kafka partition key = `wallet_id`. All events for one wallet go to same partition → ordered |
| **Per-payment ordering** | Kafka partition key = `payment_id`. All state transitions for one payment are ordered |
| **Cross-wallet ordering** | **Not guaranteed** (different partitions). Not needed: P2P debit/credit happens in single DB TX, not via events |
| **Out-of-order handling** | Consumers use event timestamp + version number. Reject events with version ≤ last processed version |
| **Late-arriving events** | Settlement Service uses event time windowing (not processing time). Late events processed in next settlement cycle |

### 12.5 Exactly-Once Processing

The system achieves **effectively exactly-once** via at-least-once delivery + idempotent processing:

```
Producer: Outbox pattern → at-least-once Kafka delivery (relay may re-publish)
Consumer: Inbox pattern → dedup via event_id UNIQUE constraint
Result:   Each event processed exactly once per consumer
```

For financial operations, the Financial Core additionally enforces idempotency via `journal_id` uniqueness. Even if the same event is processed twice, the second attempt fails the UNIQUE constraint and is safely skipped.

---

## 13. Infrastructure Degradation Modes

> *Every infrastructure dependency has a defined failure mode, detection mechanism, and service behavior.*

### 13.1 Redis Failure

| Redis Usage | Impact When Down | Service Behavior | Detection |
|------------|-----------------|------------------|-----------|
| **Rate limiting** | Rate limits not enforced | **Fail-open**: Allow requests through (WAF still provides DDoS protection) | Redis health check, counter miss rate |
| **Token blacklist** | Revoked tokens not caught | **Fail-closed for high-risk**: Block admin operations. Allow normal user operations (tokens have 15min expiry) | Blacklist check error rate |
| **Velocity counters** (Fraud) | Fraud velocity checks degraded | Fraud Service falls back to **DB-based velocity query** (`SELECT COUNT(*) FROM recent_transactions WHERE ...`). Slower (~20ms → ~100ms) but functional | Fraud check latency spike |
| **Balance cache** (read) | Cache misses → DB queries | **Graceful**: All reads go to DB read replicas. Increased DB load but functional | Cache hit rate drop to 0% |
| **Session data** | Active sessions lost | Users must re-authenticate. **Acceptable**: 15min session TTL means limited blast radius | Session lookup failure rate |

**Mitigation**: Redis Sentinel auto-failover (<10s). If entire cluster fails, circuit breaker opens and services degrade per above table.

### 13.2 Kafka Failure

| Failure Scenario | Impact | Service Behavior | Recovery |
|-----------------|--------|------------------|----------|
| **Broker failure (1 of 3)** | No impact: RF=3, min.insync=2 | Transparent failover via ISR leader election | Automatic |
| **Full cluster down** | Async events stop flowing | Outbox tables accumulate events. All **sync critical path** (payments) continues working. Async side-effects delayed (notifications, read model updates, settlement aggregation) | On recovery: outbox relay replays all pending events in order |
| **Consumer lag > 5 min** | Delayed downstream processing | Alert fired. Scale consumer instances. If lag > 30 min: pause non-critical consumers, prioritize financial event topics | Consumer scaling + prioritization |
| **Outbox table growth** | Disk pressure on service DBs | Alert at 100K pending rows. Emergency: run manual outbox drain script | Relay restart + drain |

**Key guarantee**: Kafka failure **never blocks payments**. All critical-path communication is synchronous. Kafka only carries async side-effects.

### 13.3 PostgreSQL Failure

| Failure Scenario | Impact | Service Behavior | Recovery |
|-----------------|--------|------------------|----------|
| **`financial_core_db` primary crash** | All payments fail (journal writes impossible) | Circuit breaker opens in Financial Core. Payment Service returns 503 | Auto-failover: promote sync standby (<2min). RPO=0 |
| **`financial_core_db` sync standby crash** | No immediate impact, but RPO degrades from 0 to ~1min | Alert: replication is now async-only. Promote async replica to sync standby | Rebuild standby from backup |
| **Read replica crash** | Increased load on primary | Route read traffic to primary. Alert: rebuild replica | Rebuild from snapshot |
| **`payment_db` primary crash** | New payments fail, existing payments in unknown state | Query `financial_core_db` to determine which payments were committed. Resume from known state | Auto-failover (<5min) |
| **Connection pool exhaustion** | Services can't acquire DB connections | PgBouncer transaction pooling limits blast radius. Alert at 80% pool utilization | Scale PgBouncer, identify connection leak |

### 13.4 Connection Pool Architecture (PgBouncer)

```
Service Pod (3 replicas × 10 connections each = 30 app connections)
     │
     ▼
PgBouncer (transaction pooling mode)
  max_client_connections = 200
  default_pool_size = 20
  reserve_pool_size = 5
     │
     ▼
PostgreSQL (max_connections = 100)
  Effective capacity: 20 concurrent queries (pool_size) + 5 reserve = 25
  Remaining: 75 for replication, admin, monitoring
```

**Critical**: All services MUST connect via PgBouncer, never directly to PostgreSQL.

---

## 14. Observability Architecture

### 14.1 Three Pillars

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│   METRICS   │      │    LOGS     │      │   TRACES    │
│  Prometheus │      │  Fluent Bit │      │ OpenTelemetry│
│  → Grafana  │      │ → OpenSearch│      │  → Tempo    │
└──────┬──────┘      └──────┬──────┘      └──────┬──────┘
       │                    │                    │
       └────────────────────┼────────────────────┘
                            ▼
                    ┌───────────────┐
                    │  ALERTING     │
                    │  Grafana →    │
                    │  PagerDuty →  │
                    │  Slack        │
                    └───────────────┘
```

### 14.2 Metrics Pipeline

| Component | Source | Scrape Interval | Retention |
|-----------|--------|----------------|-----------|
| **RED metrics** (per service) | OpenTelemetry SDK → Prometheus | 15s | 30 days (local), 1 year (Thanos/Mimir) |
| **USE metrics** (infrastructure) | Node exporter, PG exporter, Redis exporter, Kafka exporter | 15s | 30 days |
| **Business metrics** | Custom counters in service code | 15s | 1 year |
| **SLO burn rate** | Calculated from RED metrics via recording rules | 1 min | 30 days |

### 14.3 Distributed Tracing Strategy

| Configuration | Value | Rationale |
|--------------|-------|-----------|
| **Sampling rate (Tier 0)** | 100% | Every financial transaction must be traceable |
| **Sampling rate (Tier 1-2)** | 10% (normal), 100% (on error) | Balance cost vs debuggability |
| **Trace propagation** | W3C TraceContext (`traceparent` header) | gRPC metadata + HTTP headers |
| **Trace retention** | 7 days (hot), 90 days (cold/S3) | Compliance: 90-day dispute window |
| **Span attributes** | `payment_id`, `wallet_id`, `user_id` (hashed), `error.code` | Enable trace search by business entity |

### 14.4 SLO Monitoring & Alerting

| SLO | SLI | Burn Rate Alert (Page) | Burn Rate Alert (Ticket) |
|-----|-----|----------------------|------------------------|
| Payment availability 99.99% | `1 - (payment_errors / payment_total)` | >14.4x for 5 min (exhausts budget in 1h) | >3x for 1h |
| P2P latency p99 <500ms | `histogram_quantile(0.99, payment_duration)` | p99 > 500ms for 10 min | p99 > 400ms for 1h |
| Ledger integrity | `ledger_balance_check_failures` counter | Any increment → P0 page immediately | N/A |
| Kafka consumer lag | `kafka_consumer_lag_seconds` | >5 min for critical topics | >2 min for any topic |

### 14.5 Structured Logging Standard

All services emit JSON logs to stdout. Fluent Bit collects, enriches, and routes:

```
Application Pod → stdout → Fluent Bit DaemonSet → OpenSearch (indexed)
                                                 → S3 (archive, 1 year retention)
```

**PII redaction**: Middleware strips `phone`, `national_id`, `pin` fields before logging. Only `user_id` (opaque UUID) is logged.

---

## 15. Disaster Recovery & Multi-Region

### 15.1 Year 1: Single Region + DR (Active-Passive)

```
Primary Region: ap-southeast-1 (Singapore)
  └── All services, databases, Kafka, Redis (active)

DR Region: ap-southeast-3 (Jakarta)
  └── Pre-provisioned via IaC (cold compute)
  └── PostgreSQL: async cross-region replicas (financial_core_db, payment_db)
  └── S3: cross-region replication for backups and KYC docs
  └── Kafka: no replication (Year 1) — replay from outbox on failover
```

| Tier | RPO | RTO | Mechanism |
|------|-----|-----|-----------|
| **Tier 0** (Financial Core, Payment) | <1 min | <15 min | Async cross-region DB replica + IaC deploy |
| **Tier 1** | <5 min | <30 min | Daily backup restore + IaC deploy |
| **Tier 2** | <1 hour | <1 hour | Backup restore |

### 15.2 Failover Decision Tree

```
Route 53 health check fails (3 consecutive, 10s interval)
  │
  ├── Automated DNS failover → DR region
  │
  ▼ DR Runbook (automated + manual steps):
  1. [AUTO]  Promote PostgreSQL read replicas to primary (financial_core_db, payment_db)
  2. [AUTO]  Start EKS services from container registry (IaC: Terraform apply)
  3. [MANUAL] Verify data integrity: run ledger balance check
  4. [AUTO]  Start Kafka cluster + outbox relay (replay pending events)
  5. [MANUAL] Smoke test: synthetic P2P payment
  6. [AUTO]  Enable traffic via Route 53
  7. [MANUAL] Notify affected users of potential brief delays

  Post-failover:
  8. Reconcile data gap (events during replication lag window)
  9. Notify regulatory (if >4h outage per SBV requirements)
```

### 15.3 Split-Brain Prevention

Financial data cannot tolerate split-brain. During failover:

- **Primary region fenced**: DNS removed, security groups block all ingress
- **Financial Core DB**: PostgreSQL `recovery_target_timeline` ensures DR replica rejects writes from old primary if it comes back online
- **Kafka**: No cross-region replication means no split-brain risk for events. Outbox replay on DR guarantees consistency

### 15.4 Year 2: Active-Passive Multi-Region

- Add Kafka MirrorMaker 2 for cross-region event replication
- Promote PostgreSQL async replicas to sync (near-zero RPO)
- Pre-warmed compute in DR region (warm standby)
- Target: RTO <5 min for Tier 0

---

## 16. Scalability Roadmap

### 16.1 Database Scaling Strategy

| Phase | Scale Target | Strategy |
|-------|-------------|---------|
| **Year 1** (current) | 500 RPS write, 2000 RPS read | PgBouncer + read replicas + vertical scaling |
| **Year 1.5** (1000 RPS) | Database connection pressure | Add more PgBouncer pools, optimize query patterns, partition `journal_lines` by month |
| **Year 2** (2000+ RPS) | Vertical limit reached | Horizontal partitioning: `financial_core_db` sharded by `wallet_id` range (consistent hashing). Write shards: 4 partitions |

### 16.2 Kafka Scaling Strategy

| Topic | Partitions (Year 1) | Partition Key | Hot Partition Risk | Year 2 Plan |
|-------|---------------------|---------------|-------------------|-------------|
| `financial.events` | 12 | `wallet_id` | Low (1M wallets distributed) | Expand to 24 |
| `payment.events` | 12 | `payment_id` | Low (ULID is random) | Expand to 24 |
| `notification.commands` | 6 | `user_id` | Low | Expand to 12 |
| `audit.events` | 6 | `service_name` | Medium (payment-service dominates) | Re-key to `event_id` |
| `settlement.events` | 3 | `merchant_id` | Low (5K merchants) | Keep 3 |

### 16.3 Backpressure Strategy

| Pressure Point | Detection | Response |
|---------------|-----------|----------|
| Kafka consumer lag > 5 min | Consumer lag metric | Auto-scale consumers (HPA on lag metric). Pause non-critical consumers |
| Outbox table > 10K pending | Table row count metric | Alert SRE. Auto-restart relay. If persistent: emergency drain script |
| DB connection pool > 80% | PgBouncer stats | Alert. Block new deployments. Investigate connection-holding queries |
| API Gateway RPS > 80% capacity | Gateway RPS metric | HPA auto-scale. If sustained: WAF rate limit tightening |

### 16.4 Database Migration Strategy

| Rule | Detail |
|------|--------|
| **Expand-contract only** | Never destructive in single step. Add column → migrate data → drop old column (3 separate deploys) |
| **CI/CD only** | No manual SQL against production. All migrations via migration framework in pipeline |
| **Backward compatible** | New code must work with both old and new schema (during canary rollout) |
| **Dual approval** | Production migrations require 2 reviewers + automated staging test |
| **Rollback plan** | Every migration has a documented rollback migration. Tested in staging |

---

## 17. Critical User Journey Flows

### 17.1 P2P Transfer Flow (Critical Path)

```
Mobile App                API Gateway        Payment Svc      Risk/Fraud     Compliance    Fee Engine    Financial Core
    │                         │                   │                │              │              │              │
    │  POST /payments/p2p     │                   │                │              │              │              │
    │ X-Idempotency-Key: abc  │                   │                │              │              │              │
    │────────────────────────►│                   │                │              │              │              │
    │                         │ JWT verify        │                │              │              │              │
    │                         │ Rate limit check  │                │              │              │              │
    │                         │ Idemp. key check  │                │              │              │              │
    │                         │──────────────────►│                │              │              │              │
    │                         │                   │  gRPC: assess  │              │              │              │
    │                         │                   │───────────────►│              │              │              │
    │                         │                   │  score=15 ALLOW│              │              │              │
    │                         │                   │◄───────────────│              │              │              │
    │                         │                   │  gRPC: check   │              │              │              │
    │                         │                   │────────────────┼─────────────►│              │              │
    │                         │                   │  limits OK     │              │              │              │
    │                         │                   │◄───────────────┼──────────────│              │              │
    │                         │                   │  gRPC: calc    │              │              │              │
    │                         │                   │────────────────┼──────────────┼─────────────►│              │
    │                         │                   │  fee=0 (P2P)   │              │              │              │
    │                         │                   │◄───────────────┼──────────────┼──────────────│              │
    │                         │                   │  gRPC: commit  │              │              │              │
    │                         │                   │────────────────┼──────────────┼──────────────┼─────────────►│
    │                         │                   │                │              │              │   BEGIN TX   │
    │                         │                   │                │              │              │   INSERT journal_entry
    │                         │                   │                │              │              │   INSERT journal_lines (2)
    │                         │                   │                │              │              │   UPDATE wallets (sender -50K)
    │                         │                   │                │              │              │   UPDATE wallets (receiver +50K)
    │                         │                   │                │              │              │   INSERT outbox_events
    │                         │                   │                │              │              │   COMMIT TX │
    │                         │                   │  journal_id    │              │              │              │
    │                         │                   │◄───────────────┼──────────────┼──────────────┼──────────────│
    │                         │  200 OK           │                │              │              │              │
    │                         │◄──────────────────│                │              │              │              │
    │  200 {payment_id, ref}  │                   │                │              │              │              │
    │◄────────────────────────│                   │                │              │              │              │
    │                         │                   │                │              │              │              │
    │  Async (via Kafka):     │                   │                │              │              │              │
    │  • Transaction read model updated           │                │              │              │              │
    │  • Push notification to sender + receiver   │                │              │              │              │
    │  • Audit event recorded                     │                │              │              │              │
```

**Latency**: ~100ms p50, ~300ms p99. Well within <200ms p50 / <500ms p99 SLO.

### 17.2 EOD Settlement Flow (Batch)

```
1. Cron trigger (23:00 ICT)
2. Settlement Service reads payment.events from Kafka (merchant transactions for the day)
3. Aggregates per merchant: gross - fees - refunds = net
4. For each merchant:
   a. gRPC → Financial Core: DEBIT merchant_pending, CREDIT merchant_wallet (via settlement_clearing)
   b. Insert settlement report record
   c. Outbox → settlement.completed event
5. Bank Integration Service picks up settlements for bank transfer
6. Reconciliation Service runs post-settlement: bank ↔ ledger ↔ wallet three-way match
```

---

## 18. Team Topology & Conway's Law

### 18.1 Team-to-Service Mapping

| Team | Services Owned | Size | Focus |
|------|---------------|------|-------|
| **Financial Core Team** | `financial-core-service`, `fx-service` | 4-5 engineers | Ledger, wallet, FX, double-entry integrity |
| **Payment Team** | `payment-service`, `refund-service`, `fee-engine-service` | 4-5 engineers | Payment flows, state machines, refunds |
| **Risk Team** | `risk-fraud-service` | 3 engineers | Real-time risk, fraud rules, ML models |
| **Compliance Team** | `compliance-service` | 2-3 engineers | KYC limits, AML, regulatory |
| **Identity Team** | `identity-service`, `kyc-service`, `payment-method-service` | 3-4 engineers | Auth, KYC, payment instruments |
| **Merchant Team** | `merchant-service` | 2-3 engineers | Onboarding, API credentials, webhooks |
| **Settlement Team** | `settlement-service`, `reconciliation-service` | 2-3 engineers | EOD settlement, bank reconciliation |
| **Platform Team** | `api-gateway`, `notification-service`, `audit-service`, `transaction-service`, `reporting-service` | 5-6 engineers | Shared infrastructure, developer experience |
| **Integration Team** | `bank-integration-service` | 2-3 engineers | Bank APIs, NAPAS, external adapters |
| **Support Team** | `dispute-service` | 2 engineers | Dispute workflows |
| **Finance Ops Team** | `treasury-service` | 1-2 engineers | Treasury, liquidity |
| **SRE Team** | Infrastructure, monitoring, on-call | 3-4 engineers | Reliability, observability, deployments |

**Total**: ~40-50 engineers across 12 teams

### 18.2 Conway's Law Alignment

```
Conway's Law: "Organizations which design systems are constrained to produce designs
which are copies of the communication structures of these organizations."

✅ Our approach:
  - Team boundaries = service boundaries = bounded context boundaries
  - Each team owns ≤ 3 services → manageable cognitive load
  - Teams communicate via API contracts → services communicate via APIs
  - No shared database across teams → no cross-team data coupling

⚠️ Exceptions:
  - Payment Team owns refund-service but refund shares payment_db
    → Mitigated: separate schemas within same DB, separate aggregates
  - Platform Team owns 5 services
    → Mitigated: these are thin, low-complexity services (audit = append, notification = dispatch)
```

---

## 19. Architecture Decision Records

### ADR-009: Architecture Style — Modular Microservices

| Field | Value |
|-------|-------|
| **Status** | Accepted |
| **Context** | 17 bounded contexts with distinct SLOs (99.99% to 99.5%), independent deployment needs, and separate team ownership. Financial services require isolation from non-critical services |
| **Decision** | Modular microservices with one deployable per bounded context. Monorepo for code organization. Services communicate via REST (external), gRPC (internal sync), Kafka (async) |
| **Alternatives** | Monolith-first (rejected: SLO coupling), Modular monolith (rejected: deployment coupling), Serverless (rejected: cold start latency) |
| **Consequences** | Higher operational complexity (20 services). Mitigated by: shared platform libraries, service templates, unified CI/CD, service mesh |

### ADR-010: API Gateway — Custom NestJS Gateway

| Field | Value |
|-------|-------|
| **Status** | Accepted |
| **Context** | Need gateway for auth, rate limiting, routing, BFF. Must support custom middleware pipeline and be reproducible in local/staging |
| **Decision** | Custom API Gateway built with NestJS. Stateless, deployed as 3 replicas behind ALB |
| **Alternatives** | AWS API Gateway (rejected: limited customization, Lambda cold starts), Kong (considered for Year 2: excellent but adds ops complexity) |
| **Consequences** | Must build and maintain gateway code. Mitigated by: NestJS ecosystem, existing team NestJS expertise, shared middleware library |

### ADR-011: Caching Strategy — Cache-Aside with Event-Driven Invalidation

| Field | Value |
|-------|-------|
| **Status** | Accepted |
| **Context** | Need caching for read-heavy paths (balance queries, fee schedules, exchange rates) without compromising financial consistency |
| **Decision** | Cache-aside pattern with Redis. Event-driven invalidation via Kafka events. Write path NEVER reads from cache. Balance writes always go to DB with `SELECT FOR UPDATE` |
| **Alternatives** | Write-through cache (rejected: complexity, risk of stale writes), Read-through cache (rejected: coupling cache logic to data layer) |
| **Consequences** | Brief window (1-2s) of stale reads for balance. Acceptable for display purposes. All financial operations always use DB source of truth |

### ADR-012: Internal Communication — gRPC for Critical Path

| Field | Value |
|-------|-------|
| **Status** | Accepted |
| **Context** | Critical path latency budget is tight (<500ms p99 for payment). Internal calls between Tier 0 services need maximum efficiency |
| **Decision** | Use gRPC (Protobuf + HTTP/2) for all sync internal calls on the critical path. REST remains for external APIs and non-critical internal calls |
| **Alternatives** | REST everywhere (rejected: JSON serialization overhead, no multiplexing), GraphQL (rejected: inappropriate for service-to-service) |
| **Consequences** | Must maintain .proto files as contracts. Team must learn gRPC. Mitigated by: protobuf code generation, shared proto repository |

### ADR-013: Resilience — Fail-Closed for Security and Fraud

| Field | Value |
|-------|-------|
| **Status** | Accepted |
| **Context** | When the Risk/Fraud service or Compliance/Limits service is unavailable, should we allow or block transactions? |
| **Decision** | **Fail-closed**: If fraud check or limit check is unavailable, BLOCK the transaction. Never allow a potentially fraudulent or over-limit transaction |
| **Alternatives** | Fail-open (rejected: security risk, regulatory risk), Cached decisions (rejected: stale risk data could miss active fraud) |
| **Consequences** | Risk service outage causes payment outage. Mitigated by: Tier 0 SLO (99.99%), 3 replicas, circuit breaker with fast recovery, dedicated node group |

---

## 20. KPIs & Exit Criteria

| KPI | Target | How Measured |
|-----|--------|-------------|
| System architecture diagram approved | ✅ Reviewed by architecture review board | Approval gate sign-off |
| Service catalog 100% complete | All bounded contexts mapped to deployable services | Service catalog audit |
| Resilience patterns defined | Every inter-service call has timeout, retry, CB, fallback | Resilience matrix review |
| Latency budget calculated | Every user journey has end-to-end latency breakdown | Latency budget document |
| ADRs documented | All key decisions (architecture style, gateway, caching, protocols, resilience) have ADRs | ADR count ≥ 5 |
| Communication matrix complete | Every inter-service interaction classified as sync/async with protocol | Communication matrix |
| Conway's Law alignment | Team boundaries match service boundaries, ≤ 3 services per team | Org chart ↔ service catalog |
| Network zone design | 5 zones defined with explicit ingress/egress rules | Network policy review |
| Deployment strategy defined | Per-tier deployment strategy (canary, blue/green, rolling) | Deployment matrix |
| Caching strategy defined | What is/isn't cached, invalidation strategy, stampede prevention | Caching strategy document |
| Feature flag architecture | Kill switches for all critical paths | Feature flag inventory |
| **Financial integrity architecture** *(v2.0)* | Source of truth, saga failures, idempotency, event ordering documented | Financial integrity section review |
| **Infrastructure degradation matrix** *(v2.0)* | Redis, Kafka, DB failure modes with defined service behavior | Degradation matrix review |
| **Observability architecture** *(v2.0)* | Three pillars (logs, metrics, traces) with SLO monitoring pipeline | Observability section review |
| **Disaster recovery plan** *(v2.0)* | RPO/RTO per tier, failover runbook, split-brain prevention | DR plan review |
| **Scalability roadmap** *(v2.0)* | DB/Kafka scaling strategy, backpressure, migration strategy | Scalability section review |
| **Critical user journey flows** *(v2.0)* | P2P and settlement flows traced through architecture | Flow diagram review |

---

## 21. Connection to Next Phase

| Next Phase | What It Uses From This Phase |
|-----------|------------------------------|
| **Phase 07 — Data Architecture** | Service catalog → design per-service data models. Communication matrix → data flow diagrams. Caching strategy → Redis schema design |
| **Phase 08 — API Design** | Gateway routing rules → OpenAPI per service. BFF routes → client-specific API shapes. Rate limiting tiers → per-endpoint policies |
| **Phase 09 — Event Schema** | Communication matrix (async entries) → topic catalog. Outbox/inbox pattern → event envelope design |
| **Phase 10 — System Flows** | Full architecture → trace 14+ end-to-end flows through these layers |
| **Phase 11 — Technology Selection** | Architecture requirements → evaluate specific technologies (NestJS, PostgreSQL, Redis, Kafka, Istio, etc.) |

---

### 🛑 APPROVAL GATE → 🏗️ Architecture Review

**Reviewers**: Staff Engineer + Principal Engineer

**Checklist**:
- [ ] System architecture diagram complete (client → CDN → WAF → ALB → Gateway → services → data)
- [ ] Service catalog with all bounded contexts mapped to deployable services
- [ ] API Gateway design with responsibilities, rate limiting, BFF routing
- [ ] Communication matrix (sync/async, protocol, latency budget per call)
- [ ] Resilience patterns defined for every inter-service call
- [ ] Caching strategy with cache-aside, event-driven invalidation, stampede prevention
- [ ] Service mesh (Istio) and network zone configuration
- [ ] Deployment strategy per service tier (canary, blue/green, rolling)
- [ ] Team topology aligned with service boundaries (Conway's Law)
- [ ] Latency budget breakdown for P2P transfer (SLO: <200ms p50, <500ms p99)
- [ ] Feature flag architecture for kill switches
- [ ] ADRs documented (009–013)
- [ ] Critical path analysis with composite availability calculation
