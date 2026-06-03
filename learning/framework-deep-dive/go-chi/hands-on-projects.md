# Go/Chi Hands-On Projects

> **Level**: Staff/Principal Engineer Learning Program
> **Purpose**: Apply all framework knowledge to real-world systems with production-grade quality
> **Prerequisites**: Completion of Sessions 1-26, Go proficiency, Chi expertise

---

## Project 1: Production-Ready Chi API Service

### Goal

Build a complete, production-deployable CRUD API service using Chi that demonstrates mastery of middleware composition, database access with sqlc, performance profiling with pprof, structured logging, graceful shutdown, and CI/CD pipeline integration. This project establishes the baseline for all subsequent projects.

### Prerequisites
- Go 1.22+, Docker, PostgreSQL
- Familiarity with SQL and database migrations
- Understanding of Chi middleware pipeline (Sessions 13-18)

### Estimated Duration: 2 weeks

### Deliverables

1. **Chi Router with complete middleware stack**
   - Request ID generation (`chi/middleware.RequestID`)
   - Structured JSON logging (`log/slog`) with correlation IDs
   - Panic recovery with stack trace logging
   - Request timeout (30s global, per-route overrides on write endpoints)
   - CORS configuration (explicit allowed origins, not wildcard)
   - Authentication middleware (JWT validation with key rotation support)

2. **Full CRUD API for a domain entity** (e.g., Products)
   - `POST /api/v1/products` — create with validation
   - `GET /api/v1/products` — list with pagination, filtering, sorting
   - `GET /api/v1/products/{id}` — get by ID (404 if not found)
   - `PUT /api/v1/products/{id}` — update with optimistic locking
   - `DELETE /api/v1/products/{id}` — soft delete
   - Consistent error response format across all endpoints
   - Request validation (required fields, format validation, business rules)

3. **Database layer with sqlc**
   - `schema.sql` — table definitions with proper constraints
   - `query.sql` — all CRUD queries with sqlc annotations
   - Migrations using `golang-migrate/migrate`
   - Connection pool configuration (`SetMaxOpenConns`, `SetMaxIdleConns`, `SetConnMaxLifetime`)
   - Transaction support for multi-table operations
   - Database health check endpoint

4. **Observability**
   - `/health` — liveness (process alive)
   - `/ready` — readiness (DB reachable, migrations applied)
   - `/metrics` — Prometheus metrics (request count, latency histogram, error rate, DB pool stats)
   - `/debug/pprof/` — profiling endpoints (protected by auth or internal-only)
   - OpenTelemetry tracing spans per request

5. **Testing**
   - Unit tests for handlers using `net/http/httptest`
   - Integration tests with testcontainers (PostgreSQL)
   - Middleware tests (verify request ID propagation, timeout behavior, recovery)
   - Benchmark tests for critical handlers

6. **CI/CD Pipeline** (GitHub Actions)
   - Lint: `golangci-lint run`
   - Test: `go test ./... -race -coverprofile=coverage.out`
   - Build: Multi-stage Docker build, image <15MB
   - Security scan: `govulncheck`, Trivy container scan
   - Deploy: Kubernetes manifest with health probes, resource limits

### Architecture Diagram

```
                           ┌──────────────────┐
                           │    K8s Ingress    │
                           │    (TLS termination│
                           │     rate limiting) │
                           └────────┬─────────┘
                                    │
                                    ▼
                           ┌──────────────────┐
                           │   Chi Router     │
                           │                  │
                           │  [Middleware Stack]│
                           │  RequestID       │
                           │  Logger          │
                           │  Recoverer       │
                           │  Timeout         │
                           │  Auth (JWT)      │
                           │  [Route Groups]  │
                           │  /health         │
                           │  /metrics        │
                           │  /api/v1/...     │
                           └───┬──────┬──────┘
                               │      │
                    ┌──────────┘      └──────────┐
                    ▼                             ▼
           ┌──────────────┐              ┌──────────────┐
           │  PostgreSQL  │              │   Redis      │
           │  (primary)   │              │   (cache)    │
           └──────────────┘              └──────────────┘
```

### Learning Outcomes
- Complete mental model of a production Go/Chi service from request to response
- Proficiency with sqlc for type-safe database access
- Understanding of middleware ordering and its runtime implications
- Ability to configure production observability (metrics, traces, health checks)
- Experience with multi-stage Docker builds for minimal Go images

### Stretch Goals
- Add Redis caching layer with cache-aside pattern
- Implement idempotency key support for write endpoints
- Add rate limiting middleware (token bucket, configurable per route)
- Implement OpenAPI/Swagger documentation generation
- Add gRPC endpoint alongside HTTP for internal service communication

---

## Project 2: Multi-Service Saga Orchestration

### Goal

Build a distributed transaction system across 3 independent Chi services using the Saga pattern with Kafka as the event backbone. Demonstrates understanding of distributed systems, eventual consistency, compensating transactions, and event-driven architecture.

### Prerequisites
- Completion of Project 1
- Understanding of Apache Kafka or willing to use Redpanda (Kafka-compatible, single binary)
- Familiarity with distributed transaction patterns

### Estimated Duration: 3 weeks

### Deliverables

1. **Three independent Chi services**
   - **Order Service**: Creates orders, publishes `OrderCreated` event
   - **Payment Service**: Processes payments, publishes `PaymentProcessed`/`PaymentFailed` events
   - **Inventory Service**: Reserves inventory, publishes `InventoryReserved`/`InventoryReservationFailed` events
   - Each service has its own database (separation of concerns)
   - Each service connects to Kafka independently

2. **Saga Orchestrator** (embedded in Order Service)
   - State machine tracking saga progress: `STARTED → PAYMENT_PROCESSING → INVENTORY_RESERVING → COMPLETED`
   - Compensating transactions for each step:
     - If payment fails → no compensation needed (no state changed yet)
     - If inventory reservation fails → refund payment (compensating transaction)
   - Saga timeout handling (if a step doesn't respond within 30s, trigger compensation)
   - Idempotency: duplicate events must not cause double-processing
   - Saga state persisted to database for crash recovery

3. **Kafka Integration**
   - Producer with idempotent delivery (`enable.idempotence=true`)
   - Consumer with at-least-once semantics, manual offset commit
   - Dead letter topic for failed messages after max retries
   - Schema registry with Avro or Protobuf serialization

4. **Observability**
   - Distributed tracing across all 3 services (trace propagation via Kafka headers)
   - Saga status dashboard (how many sagas started/completed/failed/compensating?)
   - Lag monitoring for each Kafka consumer group
   - Dead letter queue alerting

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                           KAFKA CLUSTER                             │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────┐│
│  │order-events  │  │payment-events│  │inventory-    │  │order-dlq ││
│  │              │  │              │  │events        │  │          ││
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────────┘│
└─────────┼─────────────────┼──────────────────┼──────────────────────┘
          │                 │                  │
    ┌─────┴─────┐     ┌─────┴─────┐     ┌──────┴──────┐
    │  ORDER    │     │ PAYMENT   │     │ INVENTORY   │
    │  SERVICE  │     │ SERVICE   │     │  SERVICE    │
    ├───────────┤     ├───────────┤     ├─────────────┤
    │ Chi Router│     │ Chi Router│     │ Chi Router  │
    │ Saga Orch.│     │ Payment   │     │ Inventory   │
    │ PostgreSQL│     │ Processing│     │ Reservation │
    └───────────┘     │ PostgreSQL│     │ PostgreSQL  │
                      └───────────┘     └─────────────┘
        │                   │                  │
        ▼                   ▼                  ▼
  ┌──────────┐       ┌──────────┐       ┌──────────┐
  │ Order DB │       │Payment DB│       │Invtry DB │
  └──────────┘       └──────────┘       └──────────┘
```

### Learning Outcomes
- Practical experience with distributed saga orchestration
- Understanding of eventual consistency trade-offs
- Proficiency with Kafka producer/consumer patterns in Go
- Implementing compensating transactions and idempotency
- Debugging distributed system failures

### Stretch Goals
- Implement Choreography-based saga as an alternative (no central orchestrator)
- Add Outbox pattern: write events to DB in same transaction as state change, then publish from outbox
- Implement saga rollback testing (inject failures and verify compensation)
- Add WebSocket endpoint for real-time saga progress updates to UI

---

## Project 3: Rate Limiter Middleware

### Goal

Design and implement a production-grade rate limiter middleware for Chi that supports multiple algorithms (token bucket, sliding window, fixed window), local and distributed modes (Redis-backed for horizontal scaling), and per-route configuration. This is a deep dive into middleware design patterns and concurrent algorithm implementation.

### Prerequisites
- Completion of Project 1
- Understanding of Chi middleware pipeline (Session 14)
- Redis basics

### Estimated Duration: 2 weeks

### Deliverables

1. **Rate Limiter middleware with pluggable algorithms**
   - Token bucket algorithm using `sync/atomic` and `time.Ticker`
   - Sliding window algorithm (more accurate burst handling)
   - Fixed window algorithm (simpler, less accurate)
   - Each algorithm implements a common `Algorithm` interface

2. **Dual-mode operation**
   - **Local mode**: In-memory rate limiting using `sync.Map` with TTL cleanup goroutine
   - **Distributed mode**: Redis-backed using Lua scripts for atomicity
   - Auto-detection: if Redis is configured, use distributed; otherwise local
   - Graceful degradation: if Redis is unavailable, fall back to local mode

3. **Per-route and per-key configuration**
   - Per-route: `r.With(RateLimit(100, time.Minute)).Get("/api/heavy", handler)`
   - Per-user: rate limit by user ID from JWT claims
   - Per-IP: rate limit by client IP for unauthenticated endpoints
   - Composite: rate limit by (user, endpoint) tuple

4. **Rate limit response headers**
   - `X-RateLimit-Limit`: Maximum requests allowed in window
   - `X-RateLimit-Remaining`: Requests remaining in current window
   - `X-RateLimit-Reset`: Unix timestamp when window resets
   - `Retry-After`: Seconds until next request is allowed (when rate limited)

5. **Admin API and observability**
   - `GET /admin/rate-limits` — list all active rate limit configurations
   - `DELETE /admin/rate-limits/{key}` — reset rate limit for a specific key
   - Prometheus metrics: `rate_limit_hits_total`, `rate_limit_exceeded_total`, `rate_limit_active_keys`
   - Rate limit decision logging (sampled at 1%)

6. **Comprehensive testing**
   - Concurrency test: 1000 goroutines making simultaneous requests — verify no race conditions
   - Burst test: send 200 requests in 10ms, verify exactly `burst` are allowed
   - Distributed test: 3 instances with shared Redis — verify global limit enforcement
   - Performance benchmark: measure overhead of rate limit check (target: <10μs for local, <1ms for distributed)

### Architecture Diagram

```
┌────────────────────────────────────────────────────────────────┐
│                    Chi Router                                   │
│                                                                │
│  r.Group(func(r chi.Router) {                                 │
│    r.Use(rateLimiter.Middleware(rateLimiter.Config{            │
│      Algorithm: rateLimiter.SlidingWindow,                     │
│      Rate:      rateLimiter.PerMinute(100),                    │
│      KeyFunc:   rateLimiter.ByUserID,                          │
│    }))                                                         │
│    r.Get("/api/payments", paymentHandler)                      │
│  })                                                            │
└────────────────────┬───────────────────────────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────────────────────────┐
│                  Rate Limiter Middleware                        │
│                                                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │  Key Func   │  │  Algorithm  │  │     Store              │ │
│  │  (extracts  │──│  (enforces  │──│  ┌──────────┐          │ │
│  │   user/IP   │  │   limit)    │  │  │  Local   │ (sync.Map)│ │
│  │   from req) │  └─────────────┘  │  └──────────┘          │ │
│  └─────────────┘                   │  ┌──────────┐          │ │
│                                    │  │  Redis   │ (cluster)│ │
│                                    │  └──────────┘          │ │
│                                    └─────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

### Learning Outcomes
- Deep understanding of rate limiting algorithms and their trade-offs
- Concurrent programming with `sync/atomic`, `sync.Map`, and `time.Ticker`
- Redis Lua scripting for atomic distributed operations
- Middleware pattern design with configurable behavior
- Performance benchmarking and optimization of hot-path code

### Stretch Goals
- Implement leaky bucket algorithm
- Add hierarchical rate limiting (global limit + per-user limit)
- Implement rate limit quota API (burst = 10x sustained rate)
- Add WebAssembly-based dynamic rate limit policy (hot-reload without restart)
- Integrate with envoy/istio rate limiting gRPC service for sidecar compatibility

---

## Project 4: Audit Trail Service

### Goal

Build an event-sourced audit trail service using Chi, PostgreSQL, and CQRS projection patterns. Every state change in the system is recorded as an immutable event, enabling full audit history, temporal queries ("what was the state at time T?"), and event replay for rebuilding projections.

### Prerequisites
- Completion of Project 1
- Understanding of event sourcing and CQRS concepts
- PostgreSQL experience (triggers, JSONB, materialized views)

### Estimated Duration: 3 weeks

### Deliverables

1. **Event Store** (append-only)
   - `events` table: `id (UUID)`, `aggregate_id`, `aggregate_type`, `event_type`, `event_data (JSONB)`, `metadata (JSONB)`, `occurred_at`, `version`
   - Append-only: no updates or deletes to events table
   - Optimistic concurrency: version check on append to prevent race conditions
   - Event deduplication: idempotent event insertion

2. **Chi API Endpoints**
   - `POST /api/v1/events` — append one or more events (batch atomic)
   - `GET /api/v1/events?aggregate_id=X` — get event stream for an aggregate
   - `GET /api/v1/events?aggregate_id=X&at_time=T` — snapshot state at a point in time
   - `GET /api/v1/events?event_type=PaymentInitiated` — query by event type
   - `GET /api/v1/events/search` — full-text search over event data (JSONB GIN index)

3. **CQRS Projections** (eventually consistent read models)
   - Projection processor: subscribes to event stream, updates read models
   - Read models: `order_summary`, `user_activity_feed`, `daily_transaction_totals`
   - Projection rebuild: replay all events to rebuild a corrupted or new projection
   - Lag monitoring: how far behind is each projection from the event stream?

4. **Audit Trail Features**
   - Immutable audit log: every state change is recorded
   - Chain of custody: each event links to the user/service that triggered it
   - Retention policy: auto-archive events older than N years to cold storage
   - Compliance export: generate audit reports for a time range

5. **Replay Capability**
   - Event replay endpoint: replay events for an aggregate to rebuild state
   - Partial replay: replay from checkpoint (version number)
   - Replay validation: compare replayed state with current state to detect corruption
   - Replay performance: stream events, process in batches of 1000

### Architecture Diagram

```
                         WRITE PATH                    READ PATH
                    ┌─────────────────┐          ┌─────────────────┐
                    │   Chi Service   │          │   Chi Service   │
                    │   (Command)     │          │   (Query)       │
                    └────────┬────────┘          └────────▲────────┘
                             │                            │
                             ▼                            │
                    ┌─────────────────┐          ┌─────────────────┐
                    │   Event Store   │          │   Projections   │
                    │   (append-only) │──────────│   (read models) │
                    └────────┬────────┘  events  └─────────────────┘
                             │
                    ┌────────┴────────┐
                    │   PostgreSQL    │
                    │                 │
                    │  ┌────────────┐ │
                    │  │ events     │ │
                    │  │ (immutable)│ │
                    │  └────────────┘ │
                    │  ┌────────────┐ │
                    │  │projections │ │
                    │  │(mutable)   │ │
                    │  └────────────┘ │
                    └─────────────────┘
```

### Learning Outcomes
- Practical event sourcing implementation in Go/PostgreSQL
- CQRS pattern with eventual consistency between command and query sides
- PostgreSQL JSONB indexing and querying patterns
- Event replay and system state reconstruction
- Audit trail and compliance requirements in code

### Stretch Goals
- Implement event upcasting (v1 event schema → v2 event schema on replay)
- Add snapshotting to reduce replay time (snapshot every 100 events)
- Implement multi-tenancy (each tenant has isolated event stream)
- Add Postgres WAL-based change data capture for projections (instead of application-level publishing)

---

## Project 5: API Gateway with Chi

### Goal

Build a lightweight API gateway using Chi that handles request routing, authentication, rate limiting, request/response transformation, and circuit breaking for upstream services. This project demonstrates how Chi's subrouter (`Mount`) and middleware composition make it an excellent foundation for gateway patterns.

### Prerequisites
- Completion of Projects 1 and 3
- Understanding of reverse proxy patterns
- Familiarity with circuit breaker concepts

### Estimated Duration: 3 weeks

### Deliverables

1. **Reverse Proxy Engine**
   - Route-based upstream routing: `/api/payments/*` → `payment-service:8080`
   - Load balancing: round-robin across multiple upstream instances
   - Health checking: periodic upstream health probes, remove unhealthy backends
   - Connection pooling: reuse connections to upstreams, configurable pool size
   - Request timeout: per-route upstream timeout configuration
   - WebSocket proxy support (bidirectional streaming)

2. **Authentication & Authorization**
   - JWT validation at the gateway layer (extract, validate, enrich headers)
   - API key authentication for service-to-service calls
   - OAuth2/OIDC integration (token introspection endpoint)
   - Role-based access control: route-level permission checks
   - Auth bypass list for public endpoints

3. **Rate Limiting & Throttling**
   - Per-client rate limiting (by API key, user ID, or IP)
   - Per-route rate limiting (different limits per backend)
   - Burst allowance configuration
   - Rate limit headers forwarded to clients
   - Graceful degradation: return 429 with Retry-After

4. **Request/Response Transformation**
   - Request header injection (add `X-Request-ID`, `X-User-ID`, `X-Trace-ID`)
   - Response header stripping (remove internal headers before client response)
   - Request body validation (schema validation for critical endpoints)
   - Response caching headers injection (Cache-Control, ETag)
   - URL rewriting (legacy endpoint mapping to new backends)

5. **Circuit Breaker** (using `sony/gobreaker`)
   - Per-upstream circuit breaker configuration
   - Failure threshold: open after N consecutive failures
   - Half-open state: allow 1 probe request after timeout
   - Fallback responses: cached response or static error page
   - Circuit state metrics (closed, open, half-open) per upstream

6. **Observability**
   - Per-route latency metrics (p50, p95, p99)
   - Per-upstream error rate and circuit breaker state
   - Request tracing with OpenTelemetry (propagate trace context to upstreams)
   - Gateway-level access logs (structured JSON to stdout)

### Architecture Diagram

```
                    ┌─────────────────────────────┐
                    │        CLIENTS              │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
    ┌──────────────────────────────────────────────────────────────┐
    │                     API GATEWAY (Chi)                        │
    │                                                              │
    │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐ │
    │  │Auth      │  │Rate      │  │Circuit   │  │Request/Resp  │ │
    │  │Middleware│──│Limiter   │──│Breaker   │──│Transform     │ │
    │  └──────────┘  └──────────┘  └──────────┘  └──────────────┘ │
    │                                                              │
    │  r.Mount("/api/payments", paymentsProxy)                     │
    │  r.Mount("/api/users",    usersProxy)                        │
    │  r.Mount("/api/orders",   ordersProxy)                       │
    └──────────────────┬──────┬──────┬─────────────────────────────┘
                       │      │      │
            ┌──────────┘      │      └──────────┐
            ▼                 ▼                 ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
    │  Payments    │  │   Users      │  │   Orders     │
    │  Service     │  │   Service    │  │   Service    │
    └──────────────┘  └──────────────┘  └──────────────┘
```

### Learning Outcomes
- Reverse proxy implementation with Go's `net/http/httputil.ReverseProxy`
- Circuit breaker pattern integration
- Gateway security patterns (JWT validation, API key management)
- Load balancing and health checking strategies
- Request/response transformation at the edge

### Stretch Goals
- Implement API versioning strategy (header-based, path-based, query param)
- Add response caching with Redis for idempotent GET requests
- Implement GraphQL federation gateway (route to GraphQL subgraphs)
- Add canary routing (10% of traffic to new version, 90% to stable)
- Implement rate limit synchronization across multiple gateway instances (Redis-backed)

---

## Project 6: Real-Time Notification Service

### Goal

Build a real-time notification service using Chi with WebSocket and Server-Sent Events (SSE) support, Redis Pub/Sub for cross-instance message fan-out, and persistent notification storage. Demonstrates mastery of concurrent connection management, backpressure handling, and real-time communication patterns in Go.

### Prerequisites
- Completion of Project 1
- Understanding of goroutines, channels, and concurrency patterns (Session 9)
- Redis Pub/Sub basics
- WebSocket protocol understanding

### Estimated Duration: 3 weeks

### Deliverables

1. **WebSocket Endpoint** (`GET /ws`)
   - Gorilla WebSocket upgrade from HTTP to WebSocket
   - Per-connection goroutine for reading messages
   - Per-connection goroutine for writing messages (with buffered channel)
   - Heartbeat/ping-pong: disconnect stale clients after 60s
   - Graceful connection close with close frame
   - Connection limit: max 10K concurrent WebSocket connections per instance
   - Authentication: JWT token in query param or first message

2. **SSE Endpoint** (`GET /sse`)
   - Standard SSE protocol (`text/event-stream` content type)
   - Event ID, event type, and data fields
   - Automatic reconnection with `Last-Event-ID` header
   - Connection heartbeat: comment line every 30s to prevent proxy timeout
   - Fallback for browsers that don't support WebSocket

3. **Redis Pub/Sub Fan-Out**
   - Each service instance subscribes to Redis channels for each connected user
   - When a notification is published, Redis fans out to all instances
   - Each instance delivers to its locally connected clients
   - Channel management: subscribe on connect, unsubscribe on disconnect
   - Message deduplication: prevent double delivery when user has multiple connections

4. **Persistent Notification Storage**
   - `notifications` table: `id`, `user_id`, `type`, `title`, `body`, `data (JSONB)`, `read_at`, `created_at`
   - `POST /api/v1/notifications` — create and deliver notification
   - `GET /api/v1/notifications?user_id=X` — get notification history (paginated)
   - `PATCH /api/v1/notifications/{id}/read` — mark as read
   - `GET /api/v1/notifications/unread-count?user_id=X` — unread count badge

5. **Delivery Guarantees**
   - At-least-once delivery with deduplication (idempotency key)
   - Missed notification recovery: on WebSocket reconnect, deliver missed notifications since last event ID
   - Delivery acknowledgment: client sends `ack` for each message received
   - Retry with exponential backoff for unacknowledged messages

6. **Observability**
   - Active WebSocket/SSE connection count gauge
   - Notification delivery latency histogram
   - Redis Pub/Sub channel count per instance
   - Delivery failure rate by notification type

### Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────┐
│                         REDIS PUB/SUB                              │
│                    (cross-instance fan-out)                        │
└────────┬───────────────────────────────┬───────────────────────────┘
         │                               │
    ┌────┴─────────────┐          ┌──────┴───────────┐
    │  Instance 1      │          │  Instance 2       │
    │  ┌─────────────┐ │          │  ┌─────────────┐  │
    │  │ Chi Router  │ │          │  │ Chi Router  │  │
    │  └──────┬──────┘ │          │  └──────┬──────┘  │
    │         │        │          │         │         │
    │  ┌──────┴──────┐ │          │  ┌──────┴──────┐  │
    │  │ Connection  │ │          │  │ Connection  │  │
    │  │ Manager     │ │          │  │ Manager     │  │
    │  │             │ │          │  │             │  │
    │  │ UserA: ws1  │ │          │  │ UserB: ws1  │  │
    │  │ UserB: ws1  │ │          │  │ UserC: sse1 │  │
    │  └─────────────┘ │          │  └─────────────┘  │
    └──────────────────┘          └───────────────────┘
              │                            │
              ▼                            ▼
    ┌──────────────────┐          ┌──────────────────┐
    │   PostgreSQL     │          │   PostgreSQL     │
    │   (notifications)│          │   (notifications)│
    └──────────────────┘          └──────────────────┘
```

### Learning Outcomes
- WebSocket and SSE protocol implementation in Go
- Concurrent connection management (thousands of goroutines per instance)
- Redis Pub/Sub for cross-instance messaging
- Real-time delivery patterns with reconnection and missed message recovery
- Backpressure management in push-based systems

### Stretch Goals
- Implement presence system (online/offline status per user)
- Add typing indicators using Redis Pub/Sub
- Implement notification preferences (per-type opt-in/opt-out, quiet hours)
- Add push notification fallback (FCM/APNs) for disconnected mobile clients
- Implement message batching (accumulate messages for 100ms, deliver in batch to reduce connection overhead)

---

## Project 7: Multi-Tenant SaaS Platform

### Goal

Build a multi-tenant SaaS platform using Chi where tenant resolution happens at the middleware layer, data isolation is guaranteed at the database level (per-tenant schemas or row-level security), and tenant context propagates through the entire request lifecycle. This project demonstrates mastery of Go context propagation, database isolation patterns, and middleware-driven architecture.

### Prerequisites
- Completion of Project 1
- PostgreSQL schemas or row-level security understanding
- Go context.Context proficiency

### Estimated Duration: 3 weeks

### Deliverables

1. **Tenant Resolution Middleware**
   - Tenant identification: subdomain (`tenant.example.com`), header (`X-Tenant-ID`), or JWT claim
   - Tenant validation: verify tenant exists and is active
   - Tenant context injection: store tenant ID in `context.Context`
   - Tenant not found → 404 with consistent error format
   - Tenant suspended → 403 with suspension reason
   - Cross-tenant access prevention (user A from tenant X cannot access tenant Y data)

2. **Per-Tenant Database Isolation** (choose one approach)
   - **Schema-per-tenant**: `tenant_abc.users`, `tenant_abc.orders` (strongest isolation)
   - **Row-level security**: single schema, `tenant_id` column, RLS policy
   - **Database-per-tenant**: separate PostgreSQL database (extreme isolation, higher ops cost)
   - Tenant-aware connection pool: route to correct DB/schema

3. **Multi-Tenant API**
   - `POST /api/v1/tenants` — tenant provisioning (admin endpoint)
   - `GET /api/v1/tenants/{id}` — tenant configuration
   - `PUT /api/v1/tenants/{id}` — update tenant settings
   - `DELETE /api/v1/tenants/{id}` — tenant deprovisioning (soft delete + data retention policy)
   - Tenant-scoped endpoints: `GET /api/v1/users` (returns only users in current tenant)

4. **Isolation Guarantees**
   - Automated test that attempts cross-tenant data access — must fail
   - Per-tenant rate limiting (different limits per plan tier)
   - Per-tenant feature flags (enable/disable features per tenant)
   - Data export: tenant admin can export all their data (GDPR compliance)
   - Data deletion: hard delete all tenant data after retention period

5. **Tenant Provisioning Pipeline**
   - Create database schema (if schema-per-tenant)
   - Run migrations for new schema
   - Create default admin user
   - Send welcome email with setup instructions
   - All steps idempotent — safe to retry if provisioning fails mid-way

6. **Observability**
   - Per-tenant request metrics (QPS, latency, error rate per tenant)
   - Per-tenant resource usage (DB connections, storage, Redis memory)
   - Tenant activity audit log (login, data export, admin actions)
   - Anomaly detection: unusual traffic spike for a tenant

### Architecture Diagram

```
                    ┌─────────────────────────────────┐
                    │          CLIENTS                │
                    │  tenant-a.example.com            │
                    │  tenant-b.example.com            │
                    └──────────────┬──────────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────────┐
                    │        Chi Router               │
                    │                                 │
                    │  [Tenant Resolution Middleware]  │
                    │   1. Extract tenant from host    │
                    │   2. Validate tenant is active   │
                    │   3. Store tenant in ctx         │
                    │                                 │
                    │  [Authorization Middleware]      │
                    │   1. Extract user from JWT       │
                    │   2. Verify user belongs to      │
                    │      resolved tenant             │
                    │                                 │
                    │  [Data Isolation Layer]          │
                    │   1. Get tenant from ctx         │
                    │   2. Route to correct DB schema  │
                    └──────────────┬──────────────────┘
                                   │
                    ┌──────────────┴──────────────────┐
                    │          PostgreSQL              │
                    │                                 │
                    │  Schema: tenant_a                │
                    │    ├── users                     │
                    │    ├── orders                    │
                    │    └── products                  │
                    │                                 │
                    │  Schema: tenant_b                │
                    │    ├── users                     │
                    │    ├── orders                    │
                    │    └── products                  │
                    └─────────────────────────────────┘
```

### Learning Outcomes
- Multi-tenant architecture patterns and isolation guarantees
- Middleware-driven tenant resolution and context propagation
- Database isolation strategies (schema-per-tenant, RLS)
- Tenant lifecycle management (provisioning, configuration, deprovisioning)
- Per-tenant observability and resource management

### Stretch Goals
- Implement cross-tenant sharing (tenant A can grant access to specific data to tenant B)
- Add tenant migration (move tenant from one infrastructure to another)
- Implement usage-based billing (track API calls per tenant, generate invoices)
- Add tenant backup/restore API (self-service data export for enterprise tenants)
- Implement tenant warm-up (pre-warm DB connections for frequent tenants after deploy)

---

## Project 8: Observability Platform Integration

### Goal

Build comprehensive observability into a Chi service portfolio: OpenTelemetry distributed tracing, Prometheus metrics with Grafana dashboards, structured logging with correlation IDs, continuous profiling with pprof, and SLO-based alerting. This project demonstrates Staff-level operational excellence.

### Prerequisites
- Completion of Projects 1 and 2
- Docker for running Prometheus, Grafana, Jaeger/Tempo, Loki
- Understanding of observability concepts (RED metrics, USE metrics, SLOs)

### Estimated Duration: 2 weeks

### Deliverables

1. **OpenTelemetry Integration**
   - Auto-instrumentation: wrap Chi router with `otelhttp.NewHandler`
   - Span per request: `HTTP {method} {route}` (not full path — avoid high cardinality)
   - Span attributes: `http.method`, `http.status_code`, `http.route`, `user.id`, `tenant.id`
   - Span events: log errors, cache hits/misses, DB query start/end
   - Trace propagation: W3C Trace Context headers to downstream services
   - Sampling: head-based (1% production, 100% development)
   - Export to Jaeger or Grafana Tempo

2. **Prometheus Metrics**
   - RED metrics per endpoint: Rate (requests/sec), Errors (error rate), Duration (p50, p95, p99)
   - USE metrics per resource: CPU utilization, memory usage, goroutine count, GC pause time
   - Business metrics: orders created, payments processed, users registered
   - Custom collectors: database connection pool usage, Redis connection pool, Kafka consumer lag
   - `/metrics` endpoint with authentication (or internal-only)
   - Metric naming convention: `{namespace}_{subsystem}_{name}_{unit}`

3. **Grafana Dashboards** (provisioned as code)
   - **Service Overview**: RED metrics, error rate, p95 latency, QPS per endpoint
   - **Go Runtime**: goroutines, heap, GC pauses, OS threads
   - **HTTP Details**: per-route latency distribution, status code breakdown
   - **Database**: query latency, connection pool, transaction rate
   - **Business KPIs**: orders/minute, payment success rate, user signups
   - **SLO Dashboard**: error budget remaining, burn rate, SLI over time

4. **Structured Logging with slog**
   - JSON output to stdout (production) / text output (development)
   - Standard fields: `timestamp`, `level`, `message`, `trace_id`, `span_id`, `request_id`, `user_id`
   - Log levels: DEBUG (local), INFO (prod default), WARN, ERROR
   - Contextual logging: `slog.With("user_id", userID, "trace_id", traceID)`
   - Sensitive data redaction (never log passwords, tokens, PII)
   - Log aggregation: shipped to Loki or Elasticsearch

5. **Continuous Profiling with pprof**
   - CPU profiling endpoint: `/debug/pprof/profile?seconds=30`
   - Heap profiling: `/debug/pprof/heap`
   - Goroutine profiling: `/debug/pprof/goroutine`
   - Periodic profiling: cron job that captures and stores profiles hourly
   - Profile comparison: diff today's profile vs yesterday's to detect regressions
   - Integration: push profiles to Pyroscope or Grafana Phlare

6. **SLOs and Alerting**
   - Define SLIs: availability (% of 2xx responses), latency (p95 < 200ms)
   - Define SLOs: 99.9% availability, p95 < 200ms over 30-day window
   - Error budget calculation: 0.1% error budget = 43 minutes of downtime/month
   - Alerting rules: burn rate > 14.4x (critical), burn rate > 3x (warning)
   - Multi-window, multi-burn-rate alerting (Google SRE approach)
   - Alertmanager configuration with routing to PagerDuty/Slack

### Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                     CHI SERVICE (Instrumented)                       │
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │ OpenTelemetry│  │  Prometheus  │  │    slog      │               │
│  │ (traces)     │  │  (metrics)   │  │   (logs)     │               │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘               │
│         │                 │                 │                        │
│         ▼                 ▼                 ▼                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │   /debug/    │  │  /metrics    │  │  stdout      │               │
│  │   pprof/     │  │              │  │  (JSON)      │               │
│  └──────────────┘  └──────────────┘  └──────────────┘               │
└──────────────────────────────────────────────────────────────────────┘
        │                    │                    │
        ▼                    ▼                    ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   Grafana    │  │  Prometheus  │  │    Loki      │
│  (dashboards)│  │  (metrics)   │  │   (logs)     │
│              │  │              │  │              │
│  ┌────────┐  │  │  ┌────────┐  │  │  ┌────────┐  │
│  │Service │  │  │  │Alerts  │──┼──┼──│AlertMgr│  │
│  │Overview│  │  │  │Rules   │  │  │  └────┬───┘  │
│  └────────┘  │  │  └────────┘  │  │       │      │
│  ┌────────┐  │  └──────────────┘  │  ┌────┴───┐  │
│  │ SLO    │  │                    │  │PagerDuty│  │
│  │Dashboard│  │                    │  └────────┘  │
│  └────────┘  │                    └──────────────┘
└──────────────┘
        │
        ▼
┌──────────────┐
│ Jaeger/Tempo │
│  (traces)    │
└──────────────┘
```

### Learning Outcomes
- Production-grade observability with OpenTelemetry, Prometheus, and slog
- RED and USE metric design for Go services
- Grafana dashboard provisioning as code
- SLO-based alerting with error budgets and burn rates
- Continuous profiling integration for performance regression detection
- Log aggregation with structured, correlation-ID-linked logs

### Stretch Goals
- Implement exemplars (linking metrics to traces for outlier investigation)
- Add synthetic monitoring (probes that simulate user journeys and measure SLOs)
- Implement cost attribution (tag metrics by team/service/tenant for showback/chargeback)
- Create a "golden signals" CLI tool that generates boilerplate observability code
- Add anomaly detection using Prometheus recording rules and statistical thresholds

---

## Project Completion Checklist

For each project, verify:

- [ ] Code compiles without errors (`go build ./...`)
- [ ] All tests pass with race detector (`go test -race ./...`)
- [ ] No lint errors (`golangci-lint run`)
- [ ] Docker image builds successfully and is <20MB
- [ ] Graceful shutdown works (SIGTERM → drain → exit)
- [ ] Health/readiness endpoints respond correctly
- [ ] Metrics are exposed at `/metrics`
- [ ] Structured logging is JSON-formatted in production
- [ ] README explains architecture, setup, and deployment
- [ ] Architecture diagram (ASCII or Mermaid) in README
