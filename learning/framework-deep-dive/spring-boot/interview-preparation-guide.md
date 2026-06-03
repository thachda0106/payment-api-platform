# Staff/Principal Engineer Interview Preparation Guide

## Interview Formats at Top Tech Companies

### Coding Interviews
At Staff+ level, coding interviews shift from "can you invert a binary tree" to "can you write production-quality code under time pressure." Expect:

- **Pair-programming sessions** (45-60 min) where you implement a non-trivial service component — a rate limiter, a consensus algorithm, a distributed lock, a compacting log. The interviewer evaluates code structure, error handling, testability, and concurrency awareness.
- **Code review exercises**: You're given a pull request (200-400 lines) and asked to review it live. Evaluate for correctness, security, performance, maintainability, and style. The interviewer watches for your ability to prioritize issues — not every nit is worth flagging.
- **Debugging scenarios**: You're dropped into a broken service with failing tests. You must diagnose and fix the issue while explaining your thought process.
- **Concurrency deep-dives**: Implement a thread-safe data structure, fix a race condition, or reason about happens-before relationships. Java-specific: `volatile`, `synchronized`, `Lock`, `AtomicReference`, `CompletableFuture`, and the Java Memory Model.

**Language choice**: Since this curriculum is Java/Spring Boot, expect deep Java questions. Know the internals: how `HashMap` works at the bucket level, how `ConcurrentHashMap` achieves lock striping/non-blocking reads, how `ThreadPoolExecutor` manages its work queue (and why `Executors.newCachedThreadPool()` is dangerous in production).

### System Design Interviews
The centerpiece of Staff+ interviews. 60-90 minutes. You'll be asked to design a system from scratch. Unlike senior-level design (which focuses on component architecture), Staff-level design emphasizes:

- **Business impact**: How does this system generate or protect revenue? What's the cost model? How do you measure success?
- **Cross-cutting concerns**: Observability, security, compliance (PCI, GDPR, SOC2), multi-region deployment, disaster recovery.
- **Organizational alignment**: How does this system map to team boundaries? Conway's Law considerations. What happens when two teams disagree on the API contract?
- **Evolutionary architecture**: How does the system evolve from v1 to v5 without a rewrite? What are the extension points?
- **Quantitative reasoning**: Back-of-envelope calculations with actual numbers. Not just "use Redis" but "Redis at 10K TPS with 1KB values means ~80 Mbps bandwidth, ~10GB memory for 10M keys, single-threaded so CPU isn't the bottleneck — network and memory are."

### Architecture Deep-Dive Interviews
45-60 minutes on your past work. The interviewer wants to understand your decision-making framework:

- "Walk me through the most impactful architectural decision you made in the last 2 years."
- "What were the alternatives you considered and rejected? Why?"
- "How did you validate that the decision was correct?"
- "What would you do differently if you could go back?"
- "How did you communicate this decision to the engineering organization?"

### Behavioral / Leadership Interviews
At Staff+, these are not "tell me about a time you disagreed with a coworker." They probe:

- **Cross-functional influence**: "Tell me about a time you convinced a VP/Director to change a technical direction. How did you frame the argument in terms they care about?"
- **Technical strategy**: "How do you decide which technical debt to pay down vs. which to live with? Give me your framework."
- **Growing engineers**: "Tell me about someone you mentored from senior to staff. What specific interventions did you make? How did you know when to push vs. when to support?"
- **Organizational design**: "You join a company with 200 engineers. You notice 5 teams are duplicating work on authentication. What do you do?"
- **Crisis management**: "Walk me through the worst production incident you've led. What was your communication cadence? How did you make decisions under uncertainty?"

### Cross-Functional / Product Sense
Some companies (Stripe, Shopify, Amazon) include product-sense interviews even for infrastructure roles:

- "Design the billing system for our product. What are the customer-facing trade-offs?"
- "If you were the PM for our API platform, what's the first thing you'd improve and why?"
- "How do you decide whether to build vs. buy an infrastructure component?"

---

## 8 System Design Scenarios with Detailed Solutions

### Scenario 1: Payment Processing Platform (10K TPS)

**Requirements Clarification**
- What payment methods? Card, bank transfer, digital wallets, crypto? Start with cards, design for extensibility.
- What's the idempotency guarantee window? 24 hours minimum, 7 days for refund reconciliation.
- Multi-currency? Yes, with real-time FX rates or batched.
- Reconciliation frequency? Near-real-time (every 15 minutes) with end-of-day full reconciliation.
- Compliance requirements? PCI-DSS Level 1 (since we handle card data, even if tokenized).
- SLA? 99.99% availability for the payment API. 99.95% for settlement processing.

**Back-of-Envelope Calculations**
- 10K TPS peak, average ~3K TPS
- Assume 2KB per payment request (serialized): 10K × 2KB = 20 MB/s ingress
- Database writes: 10K TPS × 3 tables touched (payment, transaction, ledger) = 30K writes/s
- Idempotency key lookup: 10K TPS reads before processing = another 10K reads/s
- Total storage per day: 86400 × 3000 avg TPS × 2KB = ~500 MB/day for transactions only
- Retention: 7 years for financial records → ~1.3 TB of raw transaction data
- Reconciliation requires joining across payment gateway, bank, and internal ledgers — batch processing at scale

**API Design**
```
POST /v1/payments
  Header: Idempotency-Key: <uuid>
  Body: {
    amount: { value: 1000, currency: "USD" },    // amount in cents
    payment_method: { type: "CARD", token: "tok_xxx" },
    merchant_id: "mer_xxx",
    order_id: "ord_xxx",
    metadata: { ... },
    idempotency_key: "ik_..."                     // duplicate of header for tracing
  }
  Response: 201 Created | 200 OK (idempotent replay) | 409 Conflict (different body, same key) | 422 Unprocessable | 503 Service Unavailable

GET /v1/payments/{payment_id}
GET /v1/payments/{payment_id}/ledger
POST /v1/payments/{payment_id}/refund
POST /v1/payments/{payment_id}/capture           // for auth-then-capture flows
```

**Data Model**
```
payments table (partitioned by created_at, monthly):
  - payment_id (UUID, PK)
  - idempotency_key (UUID, UNIQUE constraint)
  - idempotency_key_created_at (TIMESTAMP, for TTL-based cleanup)
  - merchant_id
  - amount_value, amount_currency
  - status: PENDING | AUTHORIZED | CAPTURED | SETTLED | FAILED | REFUNDED | PARTIALLY_REFUNDED
  - payment_method_type, payment_method_reference
  - gateway_reference (external gateway transaction ID)
  - created_at, updated_at

payment_attempts table (one-to-many with payments):
  - attempt_id (UUID, PK)
  - payment_id (FK)
  - gateway_name (e.g., "stripe", "adyen")
  - gateway_request (JSONB)
  - gateway_response (JSONB)
  - attempt_status
  - attempt_at

ledger_entries table (immutable, append-only):
  - ledger_id (UUID, PK)
  - payment_id (FK)
  - entry_type: DEBIT | CREDIT
  - amount_value
  - balance_after
  - created_at

reconciliation_records:
  - recon_id
  - payment_id
  - internal_amount, gateway_amount, bank_amount
  - discrepancy_amount (derived)
  - status: MATCHED | MISMATCHED | PENDING
  - recon_date
```

**Architecture Diagram Description**
```
┌──────────────┐     ┌─────────────────┐     ┌──────────────────┐
│ API Gateway  │────▶│ Payment Service │────▶│ Payment Gateway  │
│ (rate limit) │     │ (orchestrator)  │     │ Adapter (Stripe, │
└──────────────┘     └───────┬─────────┘     │ Adyen, etc.)     │
                             │                └──────────────────┘
                    ┌────────┼────────┐
                    ▼        ▼        ▼
              ┌─────────┐ ┌──────┐ ┌──────────┐
              │Idempot. │ │Ledger│ │Reconcili-│
              │ Service │ │Service│ │ation Svc │
              └────┬────┘ └──┬───┘ └─────┬────┘
                   │         │           │
              ┌────┴─────────┴───────────┴────┐
              │       PostgreSQL (primary)      │
              │  + Redis (idempotency cache)   │
              └─────────────────────────────────┘

Payment Flow:
1. API Gateway validates auth, applies rate limiting
2. Payment Service checks idempotency key (Redis → PostgreSQL fallback)
3. If new: creates payment record (PENDING), inserts ledger placeholder
4. Routes to appropriate Payment Gateway Adapter via strategy pattern
5. Payment Gateway Adapter translates to provider-specific API, returns result
6. Payment Service updates status, appends ledger entries
7. Returns response to client

Async Flows:
- Reconciliation Service runs every 15 min: fetches gateway settlement reports,
  compares with internal ledger, flags discrepancies
- Payment expiration sweeper: cleans pending payments older than configurable TTL
- Idempotency key cleanup: deletes records older than retention window
- Ledger compaction: archives old entries to cold storage (S3/object store)
```

**Technology Choices with Justification**
- **PostgreSQL**: ACID compliance for financial data. Partitioning by date for manageability. JSONB for flexible gateway responses. `SELECT ... FOR UPDATE` for pessimistic locking on payment state transitions. Why not MySQL? PostgreSQL has better support for partial indexes, exclusion constraints, and richer JSON operations.
- **Redis**: Idempotency key cache with TTL matching the guarantee window. `SET key value NX EX <ttl>` for atomic set-if-not-exists-with-expiry. Why Redis vs. memcached? Need persistence (AOF) for recovery; memcached is purely cache.
- **Kafka**: Payment events published to Kafka for downstream consumers (notification, analytics, fraud detection). Ordering guaranteed by payment_id as partition key. Compaction for ledger topics.
- **Why not DynamoDB/Spanner?** If single-region is acceptable, PostgreSQL works. If multi-region active-active is required, Spanner or CockroachDB.
- **Circuit Breaker**: Resilience4j around each payment gateway adapter. If Stripe fails, fail over to Adyen without cascading failures.
- **Distributed Tracing**: Every payment_id flows through all service boundaries via trace context propagation.

**Trade-offs**
- **Synchronous vs. Asynchronous processing**: Synchronous is simpler, gives immediate feedback to client, but limits throughput. Asynchronous (return 202, webhook callback) scales better but complicates the client contract. Recommendation: synchronous for API response (sub-second), async for settlement/reconciliation.
- **Idempotency key storage**: Storing in primary DB with Redis cache means eventual consistency risk — if Redis evicts the key but DB has it, a duplicate could slip through. Mitigation: UNIQUE constraint on idempotency_key in PostgreSQL catches this; the Redis layer is an optimization, not the source of truth.
- **Multi-provider complexity**: Each payment gateway has different error codes, retry semantics, and settlement formats. The adapter pattern abstracts this but leaks when providers have fundamentally different capabilities (e.g., Stripe's real-time webhooks vs. a batch-only provider). Accept the leak and handle it in the orchestrator.

**Failure Modes**
1. **Duplicate charge**: Idempotency key collision. Mitigation: UNIQUE constraint + database-level locking.
2. **Lost payment**: Gateway responds 200 but network drops the response to the client. Mitigation: client retries with same idempotency key → Payment Service replays the stored response.
3. **Partial settlement**: Gateway charges the card but fails to settle with the bank. Mitigation: reconciliation catches the discrepancy; manual intervention or automated clawback.
4. **Database partition**: Primary DB unavailable. Mitigation: circuit breaker opens, payments are rejected fast (fail-open to merchant with clear error), queued for retry via dead-letter topic.
5. **Clock skew**: Timestamps across services for reconciliation. Mitigation: all services use UTC, NTP synchronized. Reconciliation uses event timestamps, not wall-clock time.

**Evolution Path**
- **V1**: Single gateway (Stripe), PostgreSQL, synchronous processing, manual reconciliation.
- **V2**: Multi-gateway with adapter pattern, Redis idempotency cache, automated reconciliation reports.
- **V3**: Event-sourced ledger for full audit trail, real-time fraud detection via Kafka streams, multi-currency settlement.
- **V4**: Multi-region active-active with distributed consensus on payment state, PCI-DSS Level 1 certification.

---

### Scenario 2: Multi-Tenant SaaS Platform

**Requirements Clarification**
- Tenant isolation model: Database-per-tenant? Schema-per-tenant? Row-level (discriminator column)? Shared database with tenant_id? This is the single most important question — it affects every other decision.
- Custom domains: Tenants can bring their own domain (app.tenant.com → custom domain with SSL). How many tenants? Up to 10K.
- Usage-based billing: Metered by API calls, storage, compute minutes, or seat count? Multiple pricing dimensions.
- Max tenants: 10K initially, designed for 100K+. Max users per tenant: 1M for enterprise plans.
- Data residency: EU tenants' data must stay in EU region. This might force a different isolation strategy.

**Architecture Decision: Tenant Isolation**
The core decision tree:
- < 100 tenants, high per-tenant customization → Database-per-tenant (strongest isolation, highest ops overhead).
- 100-10K tenants, moderate customization → Schema-per-tenant (good isolation, manageable ops).
- 10K+ tenants, standardized offering → Row-level with tenant_id discriminator (scalable, weakest isolation, must verify EVERY query).
- Mixed: pool-based approach — enterprise tenants get dedicated databases, SMB tenants share.

For this design, assume row-level with tenant_id as the default, with a "dedicated" tier that provisions separate database instances.

**API Design**
```
POST /v1/tenants
  Body: { name, billing_email, plan: "starter|growth|enterprise", region: "us-east-1|eu-west-1" }

GET /v1/tenants/{tenant_id}

PUT /v1/tenants/{tenant_id}/domain
  Body: { custom_domain: "app.mycompany.com" }
  Returns: DNS verification record (CNAME or TXT)

GET /v1/tenants/{tenant_id}/usage
  Query: { from, to, granularity: "hourly|daily|monthly" }
  Returns: { api_calls, storage_bytes, compute_seconds, estimated_cost }

POST /v1/tenants/{tenant_id}/webhooks
  Body: { url, events: ["user.created", "payment.succeeded"], secret }

Tenant context injection:
  All API calls include X-Tenant-ID header.
  TenantContextHolder (ThreadLocal) stores tenant_id.
  Hibernate @Filter(name = "tenantFilter") auto-applies WHERE tenant_id = ?.
```

**Data Model**
```
tenants:
  - tenant_id (UUID, PK)
  - name
  - plan: STARTER | GROWTH | ENTERPRISE
  - billing_email
  - region
  - custom_domain (nullable)
  - domain_verified (boolean)
  - status: ACTIVE | SUSPENDED | DELETED
  - created_at

tenant_configurations (JSONB for flexible per-tenant settings):
  - tenant_id (FK)
  - config_key
  - config_value
  - Examples: max_users, rate_limit_tps, features_enabled, whitelabel_css_url

all_resource_tables include:
  - tenant_id (partition key or indexed column)
  - Composite PK: (tenant_id, resource_id) or tenant_id as partition key

usage_records (timeseries, partitioned by day):
  - tenant_id
  - timestamp
  - metric: API_CALL_COUNT | STORAGE_BYTES | COMPUTE_MS
  - value (bigint)
  - resource_id (optional, for per-resource breakdown)
```

**Technology Choices**
- **Tenant-aware connection pooling**: HikariCP with custom TenantAwareDataSource that routes to the correct database pool based on tenant_id. Maintains separate connection pools per database instance.
- **Row-level security**: PostgreSQL RLS policies: `CREATE POLICY tenant_isolation ON resources USING (tenant_id = current_setting('app.tenant_id'))`. Defense in depth on top of application-level filtering.
- **Custom domains**: Nginx reverse proxy + Let's Encrypt for automatic SSL certificates. Tenant domain verification via DNS CNAME → our ingress → extract tenant from routing table → inject X-Tenant-ID header.
- **Usage aggregation**: Materialized views refreshed every hour for billing. Raw event stream via Kafka for real-time dashboards.
- **Rate limiting**: Per-tenant rate limiting via Redis sorted sets or token bucket. Enforce at API gateway level.

**Failure Modes**
1. **Cross-tenant data leak**: Missing WHERE tenant_id clause. Mitigation: Hibernate @Filter + PostgreSQL RLS + integration tests that assert no cross-tenant data access + code review checklist.
2. **Noisy neighbor**: One tenant consumes disproportionate resources. Mitigation: per-tenant rate limiting, connection pool quotas, async task queue with per-tenant isolation.
3. **Custom domain SSL expiry**: Let's Encrypt certificates not renewed. Mitigation: monitoring alert on cert expiry < 7 days, automated renewal via cert-manager.

**Evolution Path**
- V1: Row-level isolation, single database, shared schema.
- V2: Connection pool isolation, per-tenant rate limiting, Hibernate filters.
- V3: Pool-based architecture — dedicated databases for enterprise tenants, shared for SMB.
- V4: Multi-region deployment with data residency routing, cross-region admin console.

---

### Scenario 3: Real-Time Notification System

**Requirements Clarification**
- Channels: Push (FCM/APNs), Email (SMTP/SendGrid), SMS (Twilio), In-App (WebSocket), Webhook.
- Priority levels: CRITICAL (payment failure, < 5 sec), HIGH (order confirmation, < 30 sec), MEDIUM (marketing, < 5 min), LOW (digest, batch).
- Delivery guarantees: At-least-once for transactional, best-effort for marketing. Idempotency on delivery to prevent duplicates.
- User preferences: Per-user, per-channel, per-notification-type opt-in/opt-out. Do-not-disturb windows.
- Throughput: 100K notifications/sec peak (flash sale), 1K/sec average.

**Architecture**
```
Producer Services ──▶ Kafka (Notification Topic) ──▶ Notification Orchestrator
                                                           │
                              ┌─────────────────────────────┼─────────────────────────────┐
                              ▼                             ▼                             ▼
                      Push Dispatcher              Email Dispatcher              SMS Dispatcher
                              │                             │                             │
                              ▼                             ▼                             ▼
                      FCM / APNs SDK              SendGrid API                  Twilio API
                              │                             │                             │
                              └─────────────────────────────┼─────────────────────────────┘
                                                            ▼
                                                  Delivery Tracking
                                                  (PostgreSQL + Redis)
                                                            │
                                                            ▼
                                                  User Preference Service
                                                  (cached in Redis, sourced from DB)
```

**Data Model**
```
notification_templates:
  - template_id, channel, locale, subject_template, body_template, provider_config

notifications:
  - notification_id (UUID)
  - user_id, tenant_id
  - channel, priority
  - template_id (FK) or raw_content
  - context (JSONB) — variables for template rendering
  - status: PENDING | RENDERED | QUEUED | SENT | DELIVERED | FAILED | BOUNCED
  - scheduled_at, sent_at, delivered_at
  - idempotency_key (UNIQUE)

delivery_attempts:
  - attempt_id, notification_id (FK)
  - channel, provider, provider_message_id
  - status, error_code, error_message
  - attempt_at

user_preferences:
  - user_id, channel, notification_type, enabled (boolean)
  - quiet_hours_start, quiet_hours_end (TIME, nullable)
```

**Technology Choices**
- **Kafka**: Decouples producers from the notification system. Topic partitioned by user_id hash for ordering within a user's notifications. Retention: 7 days for replay.
- **Priority queuing**: Multiple Kafka topics (critical, high, medium, low) with dedicated consumer pools, or single topic with priority header and priority queue in the orchestrator (Redis ZSET for priority-based dequeuing).
- **Template rendering**: Server-side template engine (Thymeleaf or Handlebars Java port) with locale resolution. Templates versioned in database for A/B testing.
- **Provider abstraction**: Adapter pattern per channel. Each adapter implements: `send(Notification) → ProviderResponse`, `getStatus(providerMessageId) → DeliveryStatus`, `handleWebhook(payload) → void`.
- **Batching**: Marketing emails batched into provider's bulk send API. SMS concatenation for multi-part messages.

**Failure Modes & Handling**
1. **Provider outage (FCM down)**: Circuit breaker opens → notifications queued → retry with exponential backoff → dead-letter queue after max retries.
2. **Duplicate delivery**: Idempotency key on each notification. Provider returns message ID → store mapping → deduplicate on webhook callback.
3. **Throttling**: Provider rate limits exceeded → backpressure via consumer pause → adaptive rate limiting.
4. **Template rendering error**: Invalid context variables → notification moves to FAILED with error details → alert for template maintainer.

**Evolution Path**
- V1: Single channel (email), embedded in monolith via ApplicationEventPublisher.
- V2: Multi-channel with adapter pattern, Kafka for async processing.
- V3: Priority queuing, user preference engine, delivery tracking dashboard.
- V4: Multi-provider failover per channel, A/B testing for templates, ML-based send-time optimization.

---

### Scenario 4: API Gateway

**Requirements Clarification**
- Scale: 100K requests/sec peak, 10K/sec average.
- Features: Routing, authentication/authorization, rate limiting, request/response transformation, API versioning, observability (metrics, tracing, logging), circuit breaking, caching.
- Management plane: Real-time config updates without restart. Admin API + UI for route configuration.
- Multi-tenancy: Each tenant has their own rate limits, auth policies, and routing rules.

**Architecture**
```
                    ┌──────────────────────────────────┐
                    │          Admin Console            │
                    │  (Route config, policies, keys)  │
                    └──────────────┬───────────────────┘
                                   │ gRPC / REST
                                   ▼
┌─────────────────────────────────────────────────────────┐
│                   Control Plane                          │
│  ┌───────────┐  ┌─────────┐  ┌──────────┐  ┌─────────┐ │
│  │ Route Mgr │  │Auth Mgr │  │Rate Lim. │  │Config DB│ │
│  └───────────┘  └─────────┘  │   Mgr    │  │(Postgres│ │
│                               └──────────┘  │ + Redis)│ │
└──────────────────────┬──────────────────────────────────┘
                       │ Config sync (polling or push)
                       ▼
┌─────────────────────────────────────────────────────────┐
│                   Data Plane (per-instance)              │
│  ┌──────────────────────────────────────────────────┐   │
│  │              Request Pipeline                     │   │
│  │  Auth → Rate Limit → Transform → Route → Observe  │   │
│  └──────────────────────────────────────────────────┘   │
│  ┌──────────────┐  ┌───────────────┐  ┌─────────────┐  │
│  │ Route Cache  │  │ Token Cache   │  │ Rate Limiter│  │
│  │ (Caffeine)   │  │ (Caffeine+    │  │(Token Bucket│  │
│  │              │  │  Redis)       │  │ in Redis)   │  │
│  └──────────────┘  └───────────────┘  └─────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**Technology Choices**
- **Spring Cloud Gateway**: Reactive (WebFlux) for non-blocking I/O at high concurrency. Route definitions in RouteLocator beans, refreshed via `RefreshRoutesEvent`.
- **Why not Zuul 1?** Blocking → thread-per-request → low throughput. Zuul 2 (Netty-based) is viable but Spring Cloud Gateway has better Spring ecosystem integration.
- **Why not Kong/APISIX?** If you're already Spring Boot, Spring Cloud Gateway avoids operational overhead of managing a separate technology. But if you need Lua scripting or a plugin marketplace, Kong wins.
- **Rate limiting**: RequestRateLimiter filter backed by Redis. Lua script for atomic token bucket operations. Per-route, per-tenant, per-user limits configurable.
- **Auth**: JWT validation with JWKS endpoint polling and caching. API key authentication via hashed key comparison.

**Key Implementation Details**
- **Route hot-reload**: Routes stored in DB, polled every 30 seconds, diffed, only updated routes are refreshed via `ApplicationEventPublisher` → `RefreshRoutesEvent`.
- **Request transformation**: ModifyRequestBody / ModifyResponseBody filters. For complex transformations, a scripting engine (GraalJS for JavaScript transformations uploaded by platform users).
- **Observability**: Every request gets a correlation ID. Metrics: request count, latency percentiles (p50, p95, p99), error rate, rate limit rejections. Traces: propagate trace context to downstream services.

**Failure Modes**
1. **Config sync failure**: Data plane runs with stale config. Mitigation: config version number, health check fails if config age > 5 minutes, causing load balancer to drain the instance.
2. **Redis failure (rate limiting)**: Fail-open (allow requests) vs fail-closed (reject all). Decision depends on business: for API monetization (fail-closed to prevent revenue leakage), for internal services (fail-open to prevent outage).
3. **Memory leak in route cache**: Unbounded cache growth. Mitigation: Caffeine with maximumSize and expiry, metrics on cache size with alert threshold.

---

### Scenario 5: Distributed Job Scheduler

**Requirements Clarification**
- Job types: Cron (recurring), one-shot (scheduled), DAG workflows (dependency graph).
- Exactly-once execution: Critical for payment/financial jobs. At-least-once for idempotent jobs.
- Throughput: 10K job executions per minute peak.
- Visibility: Job history, logs, metrics, alerting on failures, rerun capability.
- Multi-tenancy: Tenant isolation for job queues (prevent tenant A's jobs from starving tenant B's).

**Architecture**
```
┌────────────────────────────────────────────────────────┐
│                    Scheduler Service                    │
│  ┌──────────────┐  ┌────────────┐  ┌───────────────┐  │
│  │ Cron Parser  │  │ DAG Engine │  │ Job Dispatcher │  │
│  │ (parses cron │  │(topological│  │ (gRPC to      │  │
│  │  expressions)│  │ sort, exec)│  │  workers)     │  │
│  └──────────────┘  └────────────┘  └───────────────┘  │
└───────────────────────┬────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  Worker Pool │ │  Worker Pool │ │  Worker Pool │
│  (Tenant A)  │ │  (Tenant B)  │ │  (Default)   │
└──────────────┘ └──────────────┘ └──────────────┘
        │               │               │
        └───────────────┼───────────────┘
                        ▼
┌────────────────────────────────────────────────────────┐
│                    Job Store (PostgreSQL)               │
│  Tables: jobs, job_runs, job_dependencies, schedules   │
└────────────────────────────────────────────────────────┘
```

**Technology Choices**
- **Why not Quartz?** Quartz uses pessimistic locking (`SELECT ... FOR UPDATE`) for cluster coordination — doesn't scale past ~10 nodes. For 10K jobs/min, you need sharded queues.
- **Alternative**: Use PostgreSQL as the job queue (SKIP LOCKED pattern). Each scheduler instance polls: `SELECT ... FROM jobs WHERE status = 'READY' AND scheduled_at <= now() ORDER BY priority LIMIT 100 FOR UPDATE SKIP LOCKED`. This scales linearly with instances.
- **DAG execution**: Topological sort stored in `job_dependencies` table. DAG engine builds execution plan, polls for dependency completion before dispatching child jobs.
- **Exactly-once**: Idempotency key per job run. Before execution: INSERT into job_runs with UNIQUE(idempotency_key). If duplicate, return stored result.
- **Cron evaluation**: Parse cron expression, compute next fire time. Store in `schedules` table. Scheduler wakes every second, compares current time with fire times → creates job runs.

**Failure Modes**
1. **Missed cron fire**: Scheduler is down during fire time. Mitigation: on startup, backfill missed schedules within grace period (e.g., 5 minutes).
2. **Duplicate execution**: Two schedulers pick up the same job. Mitigation: advisory lock or `FOR UPDATE SKIP LOCKED` ensures only one instance gets the row.
3. **Worker death**: Worker picks up job, crashes, job never completes. Mitigation: heartbeat + visibility timeout. If worker doesn't heartbeat within TTL, job is reassigned.
4. **DAG deadlock**: Cyclic dependency created by job definition. Mitigation: cycle detection on DAG registration (DFS), rejection of cyclic DAGs.

**Evolution Path**
- V1: Single-node cron scheduler with Quartz, embedded in monolith.
- V2: Distributed scheduler with PostgreSQL job queue, SKIP LOCKED pattern, worker pools.
- V3: DAG workflow engine, priority queuing, per-tenant worker isolation.
- V4: Global scheduler with regional worker pools, cross-region job orchestration.

---

### Scenario 6: Event-Driven Order Management System

**Requirements Clarification**
- Domain: E-commerce order lifecycle: Created → Payment Authorized → Inventory Reserved → Shipped → Delivered. Also: Cancelled (with compensation at any stage).
- Consistency model: Eventual consistency with compensating transactions (Saga pattern). No distributed transactions (2PC).
- Read model: Separate read models for different views (order history, analytics, customer dashboard).
- Audit: Complete event history, immutable, queryable.

**Architecture — CQRS + Event Sourcing + Saga**
```
┌─────────────────────────────────────────────────────────┐
│                     Command Side                         │
│  ┌──────────┐  ┌───────────┐  ┌──────────┐             │
│  │ Order    │  │ Payment   │  │Inventory │             │
│  │ Service  │  │ Service   │  │ Service  │             │
│  └────┬─────┘  └─────┬─────┘  └────┬─────┘             │
│       │              │             │                    │
│       └──────────────┼─────────────┘                    │
│                      ▼                                  │
│               ┌─────────────┐                           │
│               │  Event Store │  (PostgreSQL or Kafka)   │
│               └─────────────┘                           │
└─────────────────────┬───────────────────────────────────┘
                      │ Events
                      ▼
┌─────────────────────────────────────────────────────────┐
│                     Query Side                           │
│  ┌──────────────┐  ┌─────────────┐  ┌───────────────┐  │
│  │ Order View   │  │ Analytics   │  │ Customer      │  │
│  │ Projector    │  │ Projector   │  │ Dashboard Proj│  │
│  └──────┬───────┘  └──────┬──────┘  └──────┬────────┘  │
│         │                 │                 │           │
│         ▼                 ▼                 ▼           │
│  ┌───────────┐  ┌──────────────┐  ┌───────────────┐    │
│  │ Order Read│  │Analytics Read│  │Customer Read  │    │
│  │ DB (PG)   │  │DB (ClickHouse│  │DB (MongoDB)   │    │
│  │           │  │ or Druid)    │  │               │    │
│  └───────────┘  └──────────────┘  └───────────────┘    │
└─────────────────────────────────────────────────────────┘
```

**Saga Orchestration**
```
OrderSaga states: CREATED → PAYMENT_PENDING → PAYMENT_COMPLETED → INVENTORY_RESERVED → SHIPPED → COMPLETED
Compensation paths:
  - PAYMENT_PENDING → CancelPayment → COMPENSATED
  - INVENTORY_RESERVED → CancelPayment + ReleaseInventory → COMPENSATED
  - SHIPPED → CancelPayment + ReleaseInventory + InitiateReturn → COMPENSATED

Implementation:
  - SagaOrchestrator maintains saga state in database (saga_instances table)
  - Each step: publish command event → wait for response event (correlation by saga_id) → advance or compensate
  - Timeout per step: if no response within TTL, trigger compensation
  - Idempotent command handlers: each service checks if command already processed via idempotency key
```

**Technology Choices**
- **Event Store**: PostgreSQL with append-only `events` table (event_id, aggregate_id, aggregate_type, event_type, payload JSONB, version, created_at). For higher throughput, Kafka with compacted topic.
- **Projections**: CDC (Change Data Capture) via Debezium to propagate events from event store to Kafka, then independent projection consumers update read models. Alternatively, Transactional Outbox pattern: events written to `outbox` table in same transaction as event store, Debezium reads from outbox → Kafka.
- **Read model databases**: PostgreSQL for operational views, ClickHouse for analytics (columnar, fast aggregations), MongoDB for flexible customer dashboard views.

**Failure Modes**
1. **Saga timeout**: A step doesn't respond. Saga triggers compensation path. But what if the command WAS processed but the response was lost? Idempotent command handlers ensure replay is safe.
2. **Compensation failure**: Refund failed, inventory release failed. Escalation to manual intervention queue. Saga enters `COMPENSATION_FAILED` state → alerts on-call.
3. **Event ordering violation**: Events for the same aggregate must be processed in order. Partition by aggregate_id in Kafka. Sequence number in event store for detection.
4. **Projection lag**: Read model stale. Monitor projection lag via event store sequence number vs. projection checkpoint. Alert if lag > N seconds.

---

### Scenario 7: Feature Flag Platform

**Requirements Clarification**
- Evaluation latency: < 1ms (flags are evaluated on every request). Must be in-process, no network call.
- Targeting: Percentage rollout (10% of users), user attributes (region=EU, plan=enterprise), user segments, gradual rollout (0→100% over time).
- Flag types: Boolean (kill switch), String (experiment variant), Number (config value), JSON (complex config).
- Updates: Changes propagate in < 30 seconds. No restart required.
- Kill switches: Must work even when 90% of infrastructure is degraded.

**Architecture**
```
┌──────────────────────────────────────────────────────────┐
│                   Management Plane                        │
│  ┌──────────────┐  ┌─────────────┐  ┌───────────────┐   │
│  │ Flag Admin UI│  │ Audit Log   │  │ Change Approv.│   │
│  └──────┬───────┘  └──────┬──────┘  └──────┬────────┘   │
│         └─────────────────┼─────────────────┘            │
│                           ▼                              │
│                    ┌─────────────┐                       │
│                    │ Flag Store  │ (PostgreSQL)           │
│                    └──────┬──────┘                       │
└───────────────────────────┼──────────────────────────────┘
                            │ Change events
                            ▼
┌──────────────────────────────────────────────────────────┐
│                   Distribution Layer                      │
│  ┌──────────────────────────────────────────────────┐    │
│  │              Flag Evaluation Server               │    │
│  │  (gRPC streaming or polling for flag updates)     │    │
│  └──────────────────────┬───────────────────────────┘    │
│                         │ gRPC push / SSE              │
│                         ▼                              │
│              ┌─────────────────────┐                   │
│              │   SDK (In-Process)  │                   │
│              │ ┌─────────────────┐ │                   │
│              │ │  Flag Cache     │ │ (ConcurrentHashMap │
│              │ │  + Rule Engine  │ │  snapshot)         │
│              │ └─────────────────┘ │                   │
│              └─────────────────────┘                   │
└──────────────────────────────────────────────────────────┘
```

**Technology Choices**
- **In-process evaluation**: SDK embedded in each service. Flags cached in memory as an immutable snapshot. Evaluation is a hash map lookup + optional rule evaluation (sub-microsecond). Why not server-side evaluation? Every flag check becomes a network call → latency + single point of failure.
- **Rule engine**: Targeting rules expressed as JSON (e.g., `{"attribute": "region", "operator": "IN", "values": ["EU", "US"]}`). Rule engine evaluates against user context. Complex rules use a simple expression language (MVEL or a custom parser — keep it minimal for speed).
- **Distribution**: gRPC streaming from flag evaluation server to SDK instances. Server streams flag deltas (changes only). SDK starts with full snapshot, applies deltas on push. Fallback: polling every 30 seconds. Why not WebSocket? gRPC gives us bidirectional streaming with built-in reconnection, backpressure, and HTTP/2 multiplexing.
- **Kill switches**: SDK has a hardcoded "bootstrap" set of critical kill switch flags loaded from a known endpoint (or even baked into config) for startup. If the flag server is unreachable, kill switches still work.

**Data Model**
```
flags:
  - flag_key (PK), name, description, flag_type (BOOLEAN|STRING|NUMBER|JSON)
  - enabled (boolean, master kill switch)
  - created_at, updated_at, updated_by

targeting_rules (ordered list per flag):
  - rule_id (PK), flag_key (FK), priority (integer, lower = higher)
  - attribute, operator, values (JSON array)
  - serve_value (JSONB, the value to serve if rule matches)

flag_rollouts:
  - rollout_id, flag_key, percentage (0-100)
  - incremental (boolean: if true, ramp from 0 to percentage over duration)
  - start_time, duration_minutes

segments:
  - segment_key, name, rules (JSONB, same format as targeting_rules)
  - Can be referenced by targeting_rules: {"attribute": "_segment", "operator": "IN", "values": ["beta_users"]}

audit_log:
  - log_id, flag_key, action (CREATED|UPDATED|DELETED|TOGGLED), old_value (JSONB), new_value (JSONB)
  - performed_by, performed_at
```

**Failure Modes**
1. **SDK cold start**: Service starts, hasn't received flag snapshot yet. Mitigation: SDK blocks startup until initial fetch completes (or uses baked-in defaults with short timeout).
2. **Stale flags**: Network partition prevents flag updates. Mitigation: SDK detects heartbeat failure, logs warning, continues with last-known-good state. Kill switch flags have a separate, more aggressive refresh (polling at 1s interval).
3. **Wrong targeting**: Percentage rollout targeting changes while user is in mid-session. Mitigation: consistent hashing (hash(user_id + flag_key) % 100) ensures users stay in the same bucket even as percentage changes.
4. **Flag explosion**: 10,000 flags → SDK memory footprint. Mitigation: lazy loading (only fetch flags actually evaluated), compression, hard limit on flag count per service.

**Evolution Path**
- V1: Config file-based feature flags, requires restart.
- V2: Database-backed flags, polling by services, in-memory cache.
- V3: Push-based distribution, targeting rules, segments, audit log, admin UI.
- V4: A/B experiment engine (statistical analysis built in), gradual rollout automation (canary analysis).

---

### Scenario 8: Idempotency Service

**Requirements Clarification**
- Scope: Provide idempotency as a shared service used by multiple other services (payments, orders, notifications).
- Key lifecycle: Create key before operation → check before execution → store result after execution → expire after TTL.
- Throughput: 100K TPS key lookups, 20K TPS key creations.
- Consistency: Must never allow duplicate execution for the same key. Strong consistency on key existence check.
- TTL: Configurable per service (24h default, 30d max). Automatic cleanup of expired keys.

**Architecture**
```
┌──────────────────────────────────────────────────────────┐
│                 Idempotency Service API                   │
│                                                          │
│  POST   /v1/idempotency/keys        — Create/reserve key │
│  GET    /v1/idempotency/keys/{key}  — Check key status   │
│  PUT    /v1/idempotency/keys/{key}  — Store result       │
│  DELETE /v1/idempotency/keys/{key}  — Release key        │
└──────────────────────┬───────────────────────────────────┘
                       │
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
   ┌──────────┐ ┌──────────┐ ┌──────────┐
   │  L1:     │ │  L2:     │ │  L3:     │
   │ Caffeine │ │  Redis   │ │PostgreSQL│
   │ (local)  │ │(cluster) │ │(durable) │
   └──────────┘ └──────────┘ └──────────┘
```

**Key Lifecycle States**
```
  CREATED → IN_PROGRESS → COMPLETED (with result)
             ↘ FAILED (error stored, client can retry with same key)
             ↘ EXPIRED (TTL reached, key released)

  Read-only replay: if key is COMPLETED and client requests with same key and same request hash → return stored result (200).
  Conflict detection: if key exists but request hash differs → 409 Conflict (different payload for same idempotency key).
```

**Data Model**
```
idempotency_keys:
  - idempotency_key (VARCHAR, PK/HASH partition key)
  - service_name (VARCHAR) — e.g., "payment-service", "order-service"
  - request_hash (VARCHAR) — SHA-256 of normalized request body
  - status: CREATED | IN_PROGRESS | COMPLETED | FAILED
  - response_payload (BYTEA or JSONB) — stored result for replay
  - error_code, error_message — if status = FAILED
  - created_at, updated_at, expires_at
  - ttl_seconds (INTEGER)
  - version (INTEGER) — for optimistic locking
  - INDEX on (service_name, expires_at) — for cleanup sweeper
```

**Technology Choices**
- **Three-tier storage (L1→L2→L3)**:
  - L1 (Caffeine): In-process cache, < 0.1ms lookup. Stores COMPLETED keys only (most common query). TTL: 5 minutes, size: 10,000 entries.
  - L2 (Redis Cluster): Sub-millisecond lookup. Stores all active keys. `SET key value NX EX <ttl>` for atomic creation. TTL matches key's configured TTL. Handles 100K TPS easily on a 3-node cluster.
  - L3 (PostgreSQL): Source of truth. Durable storage for replay after Redis eviction or failure. Partitioned by month on created_at.
- **Consistency guarantee**: Key creation goes Redis-first (atomic `SET NX`). If Redis returns OK → INSERT into PostgreSQL (async, with retry). If Redis is down → fallback to PostgreSQL with `INSERT ... ON CONFLICT DO NOTHING`. Key lookup checks Redis first, then PostgreSQL.
- **Cleanup**: Scheduled sweeper queries `SELECT idempotency_key FROM idempotency_keys WHERE expires_at < now() LIMIT 10000`. Deletes from PostgreSQL, then deletes from Redis. Runs continuously with configurable batch size.

**Failure Modes**
1. **Redis → DB async write fails**: Key exists in Redis but not PostgreSQL. On Redis eviction/expiry, subsequent lookup with same key → PostgreSQL → key not found → duplicate execution possible. Mitigation: Use Redis persistence (AOF) so keys survive Redis restart. PostgreSQL is always checked on lookup (read-after-write) — if key is in Redis but not PG, PG insert is retried. Also: write to PG first, then Redis (but this adds latency). Trade-off: accept Redis-first with AOF + reconciliation.
2. **Key collision**: Two different requests with same idempotency key (client bug). Request hash comparison catches this → 409 Conflict returned. Client must generate unique keys.
3. **TTL too short**: Key expires before client retries → duplicate execution. Mitigation: enforce minimum TTL per service, alert if TTL < expected client retry window.

**Cross-Service Usage Pattern**
```java
@Service
public class PaymentService {
    @Autowired private IdempotencyClient idempotencyClient;

    @PostMapping("/payments")
    public ResponseEntity<?> processPayment(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody PaymentRequest request) {

        // 1. Check/create idempotency key
        IdempotencyResponse existing = idempotencyClient.checkOrCreate(
            key, hash(request), "payment-service");

        if (existing != null) {
            // Replay stored response
            return ResponseEntity.ok(existing.getResponsePayload());
        }

        try {
            // 2. Process payment
            PaymentResult result = doProcessPayment(request);

            // 3. Store successful result
            idempotencyClient.complete(key, result);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 4. Store failure
            idempotencyClient.fail(key, e);
            throw e;
        }
    }
}
```

---

## Architecture Deep-Dive Questions

### "Walk me through your most impactful architecture decision in the last 2 years."

Structure your answer:

1. **Context** (30 seconds): "We had a payment monolith handling 500 TPS. The business was growing 3x year-over-year, and we had a product roadmap to add 3 new payment methods and an installment/BNPL feature. The monolith's Oracle database was our bottleneck — one slow query would cascade into thread pool exhaustion across all features."

2. **Options considered** (1 minute): "I evaluated three paths: (A) vertical scaling — bigger DB, more app instances, but Oracle licensing cost was prohibitive; (B) strangler fig — extract payment processing into a microservice while leaving the rest in the monolith; (C) modular monolith — restructure internally with clear boundaries, event-driven within the process, then extract later. I rejected (A) on cost/ROI analysis. I rejected (B) because payment was too entangled with order and ledger — extraction would take 9+ months and block feature delivery."

3. **Decision** (1 minute): "Chose modular monolith first, then microservice extraction in phases. Phase 1 (3 months): create bounded contexts for Payment, Order, Ledger within the monolith. Enforced boundaries with ArchUnit. Moved to event-driven communication between contexts via ApplicationEventPublisher. Phase 2 (3 months): extracted Payment context as a microservice with its own PostgreSQL database. Used the Transactional Outbox pattern + Debezium for reliable event publishing."

4. **Validation** (30 seconds): "We measured: deployment frequency (went from bi-weekly to daily for payment features), P99 latency (dropped from 2000ms to 400ms because payment was no longer competing with reporting queries), and developer onboarding time (2 days vs 2 weeks before). Most importantly, we shipped BNPL in 6 weeks instead of the estimated 4 months."

5. **What I'd do differently**: "I underestimated the organizational challenge. Three teams had to coordinate on the bounded context boundaries. In retrospect, I should have socialized the vision through architecture decision records (ADRs) and lunch-and-learns 2 months before we started coding. I learned that architecture is 20% technical and 80% alignment."

### "What's the biggest technical failure you've been part of, and what did you learn?"

Frame as: situation → my role → what went wrong → root cause → what I learned → how I changed my practice.

---

## Behaviorals at Staff+ Level

### Conflict Resolution
"Tell me about a time you had a strong technical disagreement with another senior engineer."

Good answer structure:
- Describe the disagreement objectively (not "they were wrong")
- Show you understood their perspective
- Explain how you resolved it (data? prototype? escalation? compromise?)
- What was the outcome?
- What did you learn about conflict resolution?

Red flags interviewers watch for:
- You "won" every argument (means you either avoid conflict or bulldoze)
- You escalated before trying to resolve directly
- You can't articulate the other person's reasoning
- No learning or changed behavior afterward

### Mentoring: Growing Seniors to Staff

The question behind the question: "Can you multiply your impact through others?"

Key points to hit:
- Staff is not "senior++." It's a different role: less code, more influence, broader scope, longer time horizons, higher ambiguity tolerance.
- How you help senior engineers identify their first staff-scope project. It should be something they own end-to-end, spans multiple teams, and has measurable business impact.
- How you give feedback without giving solutions. Ask questions: "What's the riskiest assumption here?" "Who else needs to buy into this?" "What happens if this fails?"
- How you create a safe environment for them to fail and learn. Your job is to prevent catastrophic failures while letting them experience non-catastrophic ones.
- How you advocate for their promotion: document their impact in business terms, gather peer feedback, coach them on the promo packet.

### Cross-Team Influence
"Tell me about a time you drove a technical initiative that required changes from teams you don't manage."

- How you identified the problem (data, not opinion)
- How you built the coalition (found allies, addressed their concerns)
- How you communicated (RFCs, design reviews, brown bags)
- How you handled resistance (not everyone will agree)
- How you measured success

### Technical Strategy
"How do you decide what to work on?"

Give your framework:
1. **Business impact**: Revenue generated/protected, cost reduced, risk mitigated. Quantify.
2. **Urgency**: Is there a deadline? Competitor pressure? Regulatory requirement?
3. **Dependencies**: What must be done first? What's blocked on this?
4. **Capability building**: Does this unlock future work?
5. **Team health**: Does this address a pain point that's causing attrition or slowing velocity?

Show how you prioritize across these dimensions. Give a real example where you said no to something important because something else was more important.

### Incident Response
"Walk me through how you handled a critical production incident."

The interviewer is evaluating:
- Do you follow a structured incident response process? (IC role: incident commander, communications lead, operations lead)
- Do you communicate effectively under pressure? (regular status updates, clear language, no blame)
- How do you make decisions with incomplete information? (mitigate first, investigate later)
- Do you know when to escalate? (customer impact threshold, duration threshold)
- What's your post-incident process? (blameless postmortem, action items with owners and deadlines, follow-through)

---

## Whiteboarding Best Practices

### How to Structure
1. **Requirements clarification** (5 min): Ask questions. Don't assume. "What scale?" "What consistency?" "Who are the users?" Write answers visibly.
2. **Back-of-envelope estimates** (5 min): Data size, QPS, bandwidth, storage. Shows quantitative reasoning.
3. **High-level design** (10 min): Draw the major components, data flow, API surface. Keep it clean.
4. **Deep-dive on critical paths** (15 min): The interviewer will ask you to go deeper on 1-2 areas. This is where you show depth.
5. **Trade-offs and alternatives** (5 min): Proactively discuss what you chose NOT to do and why.
6. **Wrap-up** (5 min): Summarize, identify remaining risks, discuss evolution path.

### Handling Ambiguity
- When the problem is vague ("design YouTube"), narrow the scope: "Let me focus on the video upload and transcoding pipeline, and the serving/CDN layer. I'll touch on recommendations and search briefly."
- Say "I'm going to make an assumption here — tell me if you had something different in mind."
- If you're stuck, think out loud. The interviewer wants to see your thought process, not a perfect answer.

### Clarifying Questions
Good questions for any system design:
- "What's the read-to-write ratio?"
- "What's more important: consistency or availability?"
- "What's the expected data size per [entity]?"
- "What's the acceptable latency for [operation]?"
- "Is this internal or customer-facing?"
- "What's the security/compliance model?"
- "Do we need multi-region?"

### When to Go Deep vs. Broad
- **Go deep** when: The interviewer asks a follow-up question on a specific component. This is your chance to show expertise.
- **Stay broad** when: You're still sketching the architecture. Don't dive into the database schema before the interviewer has agreed on the component topology.
- **Signal your depth**: "I can go deeper on the caching layer, the database partitioning strategy, or the API contract — which would be most valuable?"
- **The depth areas that matter most at Staff level**: data modeling, consistency models, failure modes, operational concerns (deployment, monitoring, incident response), and evolutionary architecture.

---

## What Interviewers Evaluate at Staff+ Level

| Dimension | Senior | Staff+ |
|-----------|--------|--------|
| **Scope** | Team/project | Multi-team/org |
| **Time horizon** | Weeks-months | Quarters-years |
| **Impact** | Delivers features | Defines technical direction |
| **Ambiguity** | Executes on clear requirements | Creates clarity from ambiguity |
| **Communication** | Explains decisions | Builds consensus, influences without authority |
| **Technical depth** | Expert in 1-2 areas | Deep in several, broad across the stack |
| **Business acumen** | Understands product requirements | Connects technical decisions to business outcomes |
| **Mentoring** | Helps junior engineers | Grows senior engineers to staff |
| **Trade-offs** | Makes correct technical decisions | Balances technical, business, and organizational trade-offs |
| **Failure handling** | Prevents failures | Designs systems that fail gracefully, learns from failures organizationally |

### The Unwritten Rubric

Interviewers at this level are looking for:
1. **Business impact orientation**: You frame technical decisions in terms of revenue, cost, risk, and user experience. You don't optimize for technical elegance alone.
2. **Breadth with spikes**: You have deep expertise in a few areas (e.g., distributed systems, databases, performance) but can reason competently across the full stack.
3. **Organizational awareness**: You understand Conway's Law, team dynamics, and how to drive change without authority. You don't complain about "the business" — you partner with it.
4. **Communication clarity**: You can explain complex topics to a VP of Product, a junior engineer, and a peer Staff engineer — each at the right level of abstraction.
5. **Ownership**: You don't just design — you ensure implementation, measure outcomes, and iterate. You write ADRs, postmortems, and design documents that become organizational standards.
6. **Disagree and commit**: You can passionately argue for a decision, lose the argument, and then execute the chosen direction with full commitment.
7. **Anti-fragility**: The systems and teams you influence become stronger under stress, not weaker. You build practices that compound over time.

---

## Preparation Roadmap

### 4-6 Weeks Before
- Review fundamentals: operating systems (virtual memory, scheduling, I/O), networking (TCP, HTTP/2, TLS, DNS), databases (indexing, transactions, replication, partitioning).
- Read 3-5 design documents from your past work. Be ready to discuss any of them in depth.

### 2-4 Weeks Before
- Practice system design: 2-3 full designs per week with a peer or timer. Focus on clarity and structure.
- Practice coding: 1-2 problems per day on concurrency, data structures, and system-level Java.
- Prepare your "greatest hits": 5-7 stories covering architecture decisions, team leadership, conflict resolution, failure/learning, cross-team influence, technical strategy, and mentoring.

### 1 Week Before
- Mock interviews: minimum 2 full sessions (system design + coding + behavioral) with feedback.
- Review the company's engineering blog, tech talks, and open source projects. Understand their tech stack and challenges.

### Day Before
- Light review of your stories and frameworks. No cramming.
- Sleep well.

### Interview Day
- For each session: restate the problem/question before answering (confirms understanding, buys 10 seconds to think).
- After each answer: "Does that cover what you were looking for, or would you like me to go deeper on any part?"
- Take notes during the interview. Show you're engaged.
- Ask thoughtful questions at the end: "What's the biggest technical challenge your team is facing right now?" "How does the Staff+ role differ here compared to other companies you've worked at?"
