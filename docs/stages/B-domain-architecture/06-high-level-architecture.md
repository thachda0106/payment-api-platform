# Phase 06 — High-Level Architecture

## MoMo-like Payment API Platform

> **Document Status**: Draft v6.0 — Principal Architect Revised (Tier-1 Production Excellence)  
> **Last Updated**: 2026-04-12  
> **Classification**: CONFIDENTIAL — Internal Use Only  
> **Audience**: Architecture Review Board, Engineering Leadership, On-Call Responders  
> **Input**: Phase 04 — Domain Design, Phase 05 — Security  
> **Author Level**: Principal Engineer  
> **Approval Gate**: 🏗️ Architecture Review Board (ARB) Final Sign-off

---

## Table of Contents

1. [Goal & Scope](#1-goal--scope)
2. [Boundary Enforcement & Consistency Models](#2-boundary-enforcement--consistency-models)
3. [Upgraded System Architecture Diagram](#3-upgraded-system-architecture-diagram)
4. [End-to-End Request Flow & Backpressure](#4-end-to-end-request-flow--backpressure)
5. [Advanced Concurrency Control & Idempotency](#5-advanced-concurrency-control--idempotency)
6. [Comprehensive Failure & Global Retry Matrix](#6-comprehensive-failure--global-retry-matrix)
7. [Service Mesh, Routing & Admission Control](#7-service-mesh-routing--admission-control)
8. [Event-Driven Architecture & Replay Strategy](#8-event-driven-architecture--replay-strategy)
9. [Financial Core & Partitioning Strategy](#9-financial-core--partitioning-strategy)
10. [Observability, Debuggability & SLOs](#10-observability-debuggability--slos)
11. [Security & Threat Modeling](#11-security--threat-modeling)
12. [Multi-Region Architecture](#12-multi-region-architecture)
13. [Cost Modeling & FinOps](#13-cost-modeling--finops)
14. [Testing Strategy & Chaos Engineering](#14-testing-strategy--chaos-engineering)

---

## 1. Goal & Scope

Push the system architecture to absolute top-tier production excellence. This specification eliminates abstraction, defining explicit multi-region scaling, cost constraints, adversarial threat mitigation, and deep partitioning strategies required to safely operate a financial system at the scale of Stripe or Square.

---

## 2. Boundary Enforcement & Consistency Models

Cross-boundary interactions are strictly governed by intentional consistency models. 

| Domain Flow | Consistency Model | Implementation Mechanism | Rationale |
|-------------|-------------------|--------------------------|-----------|
| **Ledger Commit (Debit/Credit)** | **Strong (ACID)** | Single DB transaction, Pessimistic DB locks on `wallet_balance`. | Cannot allow double-spending or ghost money creation. |
| **Idempotency Locks** | **Strong (ACID)** | Postgres `UNIQUE(payment_id)` constraint blocking duplicates at the block level. | API caches are volatile; DB constraints are absolute. |
| **Merchant Balance Projections** | **Eventual (BASE)** | Asynchronous materialized view built from append-only ledger entries. | Massive concurrent writes gridlock `SELECT FOR UPDATE`. |
| **Search / Transactions History** | **Eventual (BASE)** | Outbox pattern to Kafka → OpenSearch indexing. | P99 write paths should not block on analytic indexing. |

---

## 3. Upgraded System Architecture Diagram

```text
                               ┌──────────────────────────────────────────────┐
                               │                EDGE LAYER (CloudFront/WAF)   │
                               │  DDoS Protection, PCI-DSS Tokenization       │
                               └─────────────────────┬────────────────────────┘
                                                     │ (Global Anycast DNS)
                            ┌────────────────────────┴────────────────────────┐
                 ┌──────────▼──────────┐                         ┌────────────▼─────────┐
                 │ REGION A (Active)   │                         │ REGION B (Passive/DR)│
                 └──────────┬──────────┘                         └────────────┬─────────┘
                            │                                                 │
               ┌────────────▼─────────────────────────────┬───────┐           │
               │             API GATEWAY (Envoy/Kong)             │           │
               │ AuthN, Adaptive Load Shedding, Global Rate Limit │           │ (Standby
               └─┬────────────────────────────────────────┬───────┘           │  Routing)
                 │                                        │                   │
      ┌──────────▼────────┐                      ┌────────▼────────┐          │
      │   BFF: Mobile     │                      │   BFF: Merchant │          │
      └──────────┬────────┘                      └────────┬────────┘          │
                 │                                        │                   │
┌────────────────▼────────────────────────────────────────▼───────────────────┐               │
│                        SERVICE MESH (Istio Ambient mTLS)                    │               │
│                                                                             │               │
│  ┌──────────────── TIER 0: CRITICAL (99.99%) ───────────────┐               │               │
│  │                                                          │               │               │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │               │               │
│  │  │  Financial   │  │   Payment    │  │    Risk/     │    │               │               │
│  │  │    Core      │  │ Orchestrator │  │  Compliance  │    │               │               │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘    │               │               │
│  └─────────┼─────────────────┼─────────────────┼────────────┘               │               │
└────────────┼─────────────────┼─────────────────┼────────────────────────────┘               │
             │                 │                 │                                            │
┌────────────▼─────────────────▼─────────────────▼────────────────────────────────────────────▼──┐
│                                 DATA & EVENT TRANSPORT LAYER                                   │
│                                                                                                │
│   ┌───────────────────────── PostgreSQL ──────────────────────────┐  ┌───────── S3 ──────────┐ │
│   │ App DBs (Cross-Region Async Replication)                      │  │ Deep Event Arch / WAL │ │
│   └───────────────────────────────────────────────────────────────┘  └─────────┬─────────────┘ │
│                                                                                │               │
│   ┌──────────────── Kafka ────────────────┐  ┌────── Redis ───────┐  ┌─────────▼─────────────┐ │
│   │ 3-Broker, min.isr=2, acks=all         │  │ Auth Token Caching │  │  OpenSearch           │ │
│   │ MirrorMaker2 Cross-Region Sync        │  │ Sharded (Tenant-ID)│  │  Transactions Search  │ │
│   └───────────────────────────────────────┘  └────────────────────┘  └───────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. End-to-End Request Flow & Backpressure

### 4.1 Strict Latency Budgets
| Step | Component | Action | Network Hop Budget | Failure Handling |
|---|---|---|---|---|
| 1 | **Edge (WAF)** | Terminate SSL, Block Malicious IPs. | < 5ms | `Fail-Open` on strict rules. |
| 2 | **API Gateway** | Validate JWT. | < 2ms | 503 Return to client immediately via Load Shed. |
| 3 | **P. Orchestrator**| Parallel sync fan-out (Risk/Compliance/Fee). | < 2ms | Manages absolute maximum thread timeout. |
| 4 | **Financial Core** | Evaluate locks, commit ledger, write outbox. | < 15ms | Single connection retry on disconnect. |

### 4.2 Backpressure Propagation Strategy
Systems degrade as queues fill. We enforce explicit "fast-failure" mechanics:
1. **DB Tier**: PostgreSQL connection pool fills up due to IOPS saturation.
2. **App Tier**: `HikariCP` throws a `PoolTimeoutException` instantly. The App returns `503 Service Unavailable`.
3. **Mesh Tier**: Istio detects the `5xx` spike and trips the Circuit Breaker for the pod.
4. **Gateway Tier**: API Gateway recognizes global saturation, triggering **Load Shedding** of P1/P2 traffic to reserve capacity for P0 Commits.
5. **No Memory Leaks**: Threads aggressively abort rather than staying blocked in `WAITING` states, protecting node RAM limits.

---

## 5. Advanced Concurrency Control & Idempotency

### 5.1 Distributed Locks vs DB Constraints
* **Anti-Pattern**: Using Redis `Redlock` for orchestrating financial debits is prohibited. If Redis split-brains or GC-pauses, locks expire, causing catastrophic double-spends.
* **Implementation Requirement**: All financial locking relies **strictly** on PostgreSQL `SELECT FOR UPDATE NOWAIT` and `UNIQUE` constraints. DB transactional serialization is the only acceptable concurrency barrier.

### 5.2 Idempotency Token Lifecycle & Abuse Prevention
1. **Binding**: `Idempotency-Key` MUST be cryptographically bound to the user's `JWT.sub`. This prevents an attacker from iterating random UUIDs to hijack another user's cached success payload.
2. **TTL Matrix**: 
   * API Redis Cache: 24 Hours.
   * DB Unique Constraint Level: 7 Days (Purged to an archive ledger).
3. **Collision Attack Mitigation**: API limits reject users submitting >5 unique Idempotency Keys per second, stopping brute-force collision attacks designed to bypass API caches.

### 5.3 Global Rate Limiting
* Executed via Redis Token Buckets indexed by `user_id:endpoint`. 
* We shard Redis explicitly by `{tenant_id}` or `{hash(user_id)}` to ensure single-node throughput (100k TPS per shard) isn't exhausted by peak global traffic.

---

## 6. Comprehensive Failure & Global Retry Matrix

Blind retries cause Distributed Denial of Service (DDoS). 

| Origin | Target | Condition for Retry | Blocked from Retrying | Max Retries | Backoff Strategy |
|--------|--------|---------------------|-----------------------|-------------|------------------|
| **Client App** | Gateway | Network disjoint, `503`, `429`. | `4xx` Client/Business Errors. | 3 | Exponential (Base 1s) + Random Jitter. |
| **API Gateway**| BFF     | Connect Timeout, `502`. | `500`, `503`, `400`. | 1 | Immediate. |
| **App Node**   | Postgres| `40001 Deadlock Detected`, Net Drop. | `23505 Unique Violation`. | 3 | Base 50ms + Jitter. |
| **Kafka Prod** | Brokers | `NotLeaderOrFollowerException`. | `RecordTooLargeException`. | ∞ | Indefinite (Outbox buffer). |

---

## 7. Service Mesh, Routing & Admission Control

### 7.1 Service Mesh Context
Using Istio node-level Z-tunnels enforces mTLS networks and zero-trust verification (ZTNA) without incurring the massive 70% CPU proxy-overhead associated with legacy envoy sidecars. 

### 7.2 Global Admission Control
When the system degrades, it actively prioritizes revenue-generating actions:
*   Incoming requests are tagged:
    *   `P0`: Transactions, Ledgers, Risk.
    *   `P1`: Balance retrieval.
    *   `P2`: Reporting, Batch.
*   Once infrastructure latency hits 80% saturation, gateways drop `P2/P1` returning `503 Retry-After`.

---

## 8. Event-Driven Architecture & Replay Strategy

### 8.1 Replay Mechanics 
Every microservice implements the **Idempotent Inbox Pattern**: `INSERT INTO inbox (event_id) ON CONFLICT DO NOTHING`. 
*   **Replay by Key (Targeted Fix)**: Pushing a JSON payload matching a failed `payment_id` to `#platform-replay` triggers the Outbox orchestrated to re-emit the original payload gracefully.
*   **Replay by Partition (Catastrophic Bug)**: If a bug corrupted the `email_receipts` service for 4 hours, we revert the code, then execute `kafka-consumer-groups.sh --reset-offsets --to-datetime <time-minus-5-hours>`. The 1-hour overlap is swallowed harmlessly by the DB Inbox constraint, and the remaining 4 hours reprocess properly.

---

## 9. Financial Core & Partitioning Strategy

### 9.1 Database Partitioning Beyond Time-Series
Standard `PARTITION BY RANGE (created_at)` causes "Month-End Hotspots," overloading the active partition's physical SSD block.
*   **Composite Partitioning**: `PARTITION BY HASH(account_id)` + `RANGE (created_at)`.
*   This perfectly distributes physical disk I/O evenly across all nodes simultaneously while maintaining the capability to drop obsolete chronological partitions.

### 9.2 Kafka Hot-Partition Mitigation
*   **The Issue**: Partitioning by `merchant_id` ensures strict ordering, but a "Mega Merchant" sale will bottleneck a single Kafka partition.
*   **Resolution**: 
    1. Standard users partition by `wallet_id`.
    2. High-throughput Merchant aggregations push to a dedicated `#clearing-fast-lane` topic using a `Sticky Partitioner` (ignoring key constraints to parallelize wildly), relying on eventual consistency aggregators.

---

## 10. Observability, Debuggability & SLOs

### 10.1 The 3AM Stick Transaction Debug Guide
1. **Extract Trace**: Find `payment_id` in Gateway HTTP access logs to get `x-b3-traceid`.
2. **State Machine Delta**: Run `trace_id:<uuid>` in OpenSearch. If you see `event=payment.initiated` but NOT `event=fin.captured`, the transaction died between Orchestration and the DB commit.
3. **Check DLQ**: If Kafka events threw business exceptions (e.g. malformed null payloads), search the consumer logs via `trace_id` to confirm correct push into the Dead-Letter Queue.

### 10.2 Formal SLO Definitions & Error Budgets
* **Availability SLO**: `99.99%` (Max 4.32 minutes/month downtime).
* **Latency P99**: `< 250ms` Gateway-to-Gateway.
* **Error Budget Policy**: If Error Budget < 0, CI/CD unconditionally freezes all Feature deployments until rolling 30-day metrics recover. Only P0 reliability tickets are mergeable.

---

## 11. Security & Threat Modeling

Finance requires an adversarial architecture.

### 11.1 Threat Model Edge Cases
| Attack Vector | Hacker Strategy | Architectural Mitigation |
|---------------|-----------------|--------------------------|
| **Fallback Abuse** | Inject artificial TCP latency forcing Risk Engine SLA to fail-open. | Fallback configurations are capped. We trigger *Heuristic Rules* (e.g., hard cap `amount < $50`) upon timeout instead of passing blindly. |
| **Race-Condition Smurf**| Launch 100 concurrent withdrawals hoping processing latency lets them double-spend $5. | All balance mutations are enclosed in pessimistic DB row locks `WHERE balance >= $5`. 99 calls will return NSF natively from the engine. |
| **Idempotency Replay**| Sniffing a valid Idempotency-Key and firing it via an illegitimate user's session. | Validated in Gateway: `Hash(Idempotency-Key + JWT.sub)`. |

---

## 12. Multi-Region Architecture

Financial consistency forbids traditional Active-Active database writes without enormous latency hits (Spanner/CockroachDB). We utilize **Active-Passive (Cell-Based)**.

1. **Routing**: Users are geolocated and mapped to an Active Cell (e.g., US-East).
2. **Failover**: 
   * Primary DB runs cross-region async replication to US-West (RPO < 30s).
   * Events sync via Kafka `MirrorMaker2`.
3. **Data Bridging**: In a regional catastrophe, Route53 redirects traffic to Region B. The 30s replication gap necessitates the S3 Archive Reconciliation script (Section 14).

---

## 13. Cost Modeling & FinOps

Real-world architectural tradeoffs prioritize P0 stability vs Tier 2 costs.

### 13.1 Infrastructure Trade-offs
*   **Kafka vs S3 Event Storage**: Kafka storage is volatile and insanely expensive at 50TBs. We set Kafka retention to 7-days. A Kafka Connect sink continuously flushes topics to deeply archived S3 Parquet lakes, providing infinite retention at <1% of the cost.
*   **Mesh Costs**: Istio Ambient mesh bypasses the compute tax of 100 sidecars, saving estimated $5K/mo per 1M active daily users purely in dropped EC2 requirements.

### 13.2 Estimated Operational TCO (Per 1M Trx / Mo)
1. **Compute (EKS + Fargate)**: ~$1,400 to maintain baseline HA capacity.
2. **Database (Aurora I/O Optimized)**: ~$2,200 (Primaries + Replicas).
3. **Transit + Mesh**: ~$800.
**Total Run Rate**: ~$4,400 / month per 1M volume block.

---

## 14. Testing Strategy & Chaos Engineering

Code doesn't break systems; configuration limits do.

### 14.1 Load Testing Limits (K6)
*   **Test**: Sustain 20k TPS via synthetic bot-traffic over 5 minutes.
*   **Goal**: Verify API limit shedding drops excess load intelligently, and DB connection pooling saturates *gracefully* (bubbling 503s instead of OOM killing the Node).

### 14.2 Targeted Chaos Injection (Monthly Game Days)
*   Network partition between Redis and the Payment Orchestrator (Chaos Mesh).
*   Verify that `Rate Limits` fail-open dynamically, but `Idempotency checks` dynamically force a DB query instead.

### 14.3 Shadow Traffic Replay
Before deploying a major V2 Ledger update, traffic mirrors dump live `POST /payments` payloads asynchronously into the Staging environment. We verify V2 math outputs match V1 byte-for-byte under active 3AM production data profiles.
