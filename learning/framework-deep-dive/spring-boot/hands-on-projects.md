# Hands-On Projects: Production-Grade Spring Boot

## Project Selection Guide

These 8 projects are designed to be completed sequentially within the curriculum. Each builds on concepts from previous projects and targets specific Staff/Principal-level competencies. Prerequisites reference sessions from the main curriculum.

| Project | Focus Area | Difficulty | Duration |
|---------|-----------|------------|----------|
| 1. Modular Monolith | Architecture boundaries | Intermediate | 2-3 weeks |
| 2. Hexagonal Architecture | Ports & Adapters | Intermediate | 2-3 weeks |
| 3. Custom Spring Boot Starter | Framework internals | Advanced | 1-2 weeks |
| 4. Saga Orchestrator | Distributed transactions | Advanced | 3-4 weeks |
| 5. Full Observability Stack | Production monitoring | Intermediate | 2-3 weeks |
| 6. High-Performance API | Performance engineering | Advanced | 3-4 weeks |
| 7. Kubernetes Operator | Platform engineering | Expert | 4-6 weeks |
| 8. Framework Migration | Platform evaluation | Expert | 4-6 weeks |

---

## Project 1: Modular Monolith

### Objectives
- Design and implement a Spring Boot application with 5 bounded contexts that are independently deployable as modules but run as a single process
- Use domain events for inter-module communication without direct coupling
- Enforce architectural boundaries at compile time and test time
- Understand when a modular monolith is the right choice vs. microservices

### Prerequisite Sessions
- DDD Strategic Design (bounded contexts, context mapping, ubiquitous language)
- Domain Events and ApplicationEventPublisher
- Gradle Multi-Module Builds
- ArchUnit and Architecture Testing

### Tech Stack
- Spring Boot 3.x, Spring Modulith, Spring Data JPA
- Gradle 8.x multi-module with `api` vs. `implementation` dependency enforcement
- PostgreSQL (shared database, per-context schema or table prefix)
- ArchUnit (architecture unit tests)
- Testcontainers (integration testing)
- Spring Modulith Verifier (module structure verification at test time)
- Flyway or Liquibase (per-module migration paths)

### Architecture Diagram
```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                   │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ Identity │  │ Catalog  │  │ Ordering │  │ Payment  │   │
│  │ Module   │  │ Module   │  │ Module   │  │ Module   │   │
│  │          │  │          │  │          │  │          │   │
│  │ User     │  │ Product  │  │ Order    │  │ Payment  │   │
│  │ Role     │  │ Category │  │ Cart     │  │ Refund   │   │
│  │ Auth     │  │Inventory │  │ Checkout │  │ Ledger   │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │             │             │             │          │
│  ┌────┴─────────────┴─────────────┴─────────────┴────┐     │
│  │              Shared Kernel                         │     │
│  │  DomainEvent, AggregateRoot, DomainPrimitives,     │     │
│  │  IdentityGenerators, Exceptions                    │     │
│  └────────────────────────────────────────────────────┘     │
│                            │                                │
│  ┌─────────────────────────┴─────────────────────────┐     │
│  │         ApplicationEventPublisher                  │     │
│  │  (async domain events via @TransactionalEventList. │     │
│  │   ener + @Async)                                   │     │
│  └────────────────────────────────────────────────────┘     │
│                            │                                │
│  ┌──────────┐                                              │
│  │ Notific. │  Module (subscribes to events from all)      │
│  │ Module   │                                              │
│  └──────────┘                                              │
└─────────────────────────────────────────────────────────────┘
```

### Gradle Module Structure
```
payment-platform/
├── build.gradle.kts                    (root build)
├── settings.gradle.kts
├── shared-kernel/                      (no Spring, pure domain primitives)
│   ├── src/main/java/com/platform/shared/
│   │   ├── DomainEvent.java
│   │   ├── AggregateRoot.java
│   │   ├── Money.java
│   │   └── Result.java
│   └── build.gradle.kts
├── identity-module/
│   ├── src/main/java/com/platform/identity/
│   │   ├── application/               (public API: services, DTOs)
│   │   ├── domain/                    (internal: entities, VOs, repos)
│   │   └── infrastructure/            (internal: JPA impls, config)
│   └── build.gradle.kts
├── catalog-module/                     (same structure)
├── ordering-module/                    (same structure)
├── payment-module/                     (same structure)
├── notification-module/               (same structure)
└── application/                        (wiring: Spring Boot main, config)
    ├── src/main/java/com/platform/
    │   ├── PaymentPlatformApplication.java
    │   └── config/
    └── build.gradle.kts
```

### Implementation Phases

**Phase 1: Foundation (3-4 days)**
1. Create the Gradle multi-module structure. Define `api` and `implementation` dependency scopes for each module. The `application` module depends on all modules. Other modules only depend on `shared-kernel`.
2. Implement `shared-kernel`: `DomainEvent` base class (with `eventId`, `occurredAt`, `aggregateId`), `AggregateRoot` base class (with `registerEvent()`, `clearEvents()`), `Money` value object (amount + currency, with arithmetic operations), `Result<T, E>` monad for error handling.
3. Set up PostgreSQL with Testcontainers. Create a base integration test class that starts PostgreSQL and applies Flyway migrations.
4. Configure Spring Modulith in `application` module. Verify empty module structure passes `ApplicationModules.of(PaymentPlatformApplication.class).verify()`.

**Phase 2: Identity Module (3-4 days)**
1. Implement domain model: `User` (aggregate root), `Role`, `Email` (value object), `PasswordHash` (value object with BCrypt).
2. Implement application layer: `UserService` (create, update, authenticate), `UserDto`, `CreateUserRequest`.
3. Implement infrastructure: JPA entities, Spring Data repositories, `BCryptPasswordEncoder` bean.
4. Implement REST API: `POST /api/users`, `GET /api/users/{id}`, `POST /api/auth/login`, `POST /api/auth/refresh`.
5. Implement domain events: `UserCreatedEvent`, `UserActivatedEvent`.
6. Write ArchUnit tests verifying: identity module does not depend on other modules, domain layer has no Spring dependencies, application layer only depends on domain layer.

**Phase 3: Catalog Module (3-4 days)**
1. Implement domain: `Product` (aggregate), `Category`, `SKU` (value object), `Price` (delegates to `Money` from shared kernel).
2. Implement inventory tracking: `Inventory` entity, `reserveInventory()` and `releaseInventory()` methods with optimistic locking (`@Version`).
3. Implement REST API: `GET /api/products`, `POST /api/products`, `PATCH /api/products/{id}/inventory`.
4. Implement domain events: `ProductCreatedEvent`, `InventoryReservedEvent`, `InventoryReleasedEvent`, `OutOfStockEvent`.

**Phase 4: Ordering Module (4-5 days)**
1. Implement domain: `Order` (aggregate root, lifecycle: CREATED → CONFIRMED → PAID → SHIPPED → DELIVERED → CANCELLED), `OrderItem`, `ShippingAddress` (value object).
2. Implement `Cart` as a separate aggregate with `addItem()`, `removeItem()`, `checkout()`.
3. Implement checkout flow: `CheckoutService` validates cart → creates Order → publishes `OrderPlacedEvent`.
4. Implement domain event subscribers: listen for `PaymentCompletedEvent` (from payment module) → advance order to PAID state. Listen for `InventoryReservedEvent` → advance order.
5. Implement circuit breaker (Resilience4j) for cross-module calls within the monolith (simulating eventual external service calls).

**Phase 5: Payment Module (4-5 days)**
1. Implement domain: `Payment` (aggregate), `Refund`, `LedgerEntry` (append-only).
2. Implement payment processing: amount validation, currency conversion (using a mock FX service).
3. Implement idempotency: `IdempotencyKey` value object, repository with `findByKey()`.
4. Implement domain events: `PaymentInitiatedEvent`, `PaymentCompletedEvent`, `PaymentFailedEvent`, `RefundProcessedEvent`.
5. Implement ledger: every mutation creates a `LedgerEntry` (immutable, double-entry). `LedgerService` provides `getBalance()`, `getTransactions()`.

**Phase 6: Notification Module (2-3 days)**
1. Implement event subscribers for all domain events from other modules.
2. Implement `NotificationService` with template-based rendering.
3. Implement channel abstraction: `EmailNotifier`, `SmsNotifier`, `PushNotifier` (all mocked, logging output).
4. Implement notification preferences: per-user, per-event-type opt-in/opt-out.

**Phase 7: Boundary Enforcement (2-3 days)**
1. Write comprehensive ArchUnit tests:
   - No module may access another module's `domain` or `infrastructure` package directly.
   - Modules may only access other modules' `application` package (public API).
   - `shared-kernel` has no dependencies on any module or Spring.
   - `@Service`, `@Repository`, `@Controller` annotations follow package conventions.
   - Cyclic dependency detection between modules.
2. Set up Spring Modulith Verifier: `ApplicationModules.verify()` detects illegal cross-module references at test time.
3. Document all module APIs in a `MODULE_API.md` per module — what's public, what's internal, what events are published and consumed.

**Phase 8: Testing Strategy (2-3 days)**
1. Unit tests: domain logic in each module, pure POJO tests, no Spring context.
2. Integration tests per module: `@SpringBootTest` scoped to module with `@Import(ModuleConfig.class)`, other modules mocked. Testcontainers for real PostgreSQL.
3. Cross-module integration tests: full application context, verify event chains (User registers → Order placed → Payment processed → Notification sent).
4. Performance test: verify module boundaries don't add measurable overhead vs. non-modular monolith (same JVM).

### Stretch Goals
1. **Transactional Outbox Pattern**: Instead of ApplicationEventPublisher, persist events to an `outbox` table in the same DB transaction, then process them asynchronously. Compare reliability.
2. **Module Extraction**: Extract one module (e.g., Payment) into a separate Spring Boot service. Use Testcontainers + WireMock for integration tests across the network boundary. Document how many changes were needed.
3. **Multi-Tenancy**: Add tenant_id to all entities. Implement Hibernate `@Filter` for automatic tenant filtering. Verify no cross-tenant data leaks.
4. **API Versioning**: Implement API versioning per module (URL-based: `/api/v1/products` vs. `/api/v2/products`). Ensure modules can version independently.

### Evaluation Criteria
| Criterion | Weight | Description |
|-----------|--------|-------------|
| Boundary integrity | 25% | ArchUnit tests pass. No illegal cross-module references. Cyclic dependencies detected. |
| Domain model quality | 20% | Rich domain model with behavior, not anemic. Value objects for primitives. State machines for lifecycle. |
| Event-driven communication | 20% | Events are the only cross-module communication. No direct service calls between modules (except application layer). |
| Test coverage | 15% | >80% line coverage. Unit tests for domain logic (fast, no Spring). Integration tests for persistence and event chains. |
| Code organization | 10% | Consistent package structure across modules. Clear separation of application/domain/infrastructure. |
| Documentation | 10% | MODULE_API.md per module. Architecture Decision Records for key decisions (why modular monolith vs. microservices, why shared DB, why async events). |

### Estimated Time
- Core implementation: 15-18 days
- Testing and enforcement: 4-5 days
- Documentation and review: 2-3 days
- **Total**: 3-4 weeks (full-time)

---

## Project 2: Hexagonal Architecture Refactor

### Objectives
- Take an existing layered (Controller → Service → Repository) CRUD application and refactor it to Hexagonal (Ports & Adapters) architecture
- Quantify the differences in test speed, maintainability, and extensibility
- Understand when Hexagonal Architecture provides ROI and when it's over-engineering

### Prerequisite Sessions
- Hexagonal Architecture (Ports and Adapters)
- Dependency Inversion Principle
- Mocking strategies (Mockito vs. fakes vs. in-memory implementations)
- Testcontainers

### Tech Stack
- Spring Boot 3.x, Spring Data JPA
- PostgreSQL (Testcontainers for integration tests)
- JUnit 5, Mockito, AssertJ
- JMH (Java Microbenchmark Harness) for comparing test execution times
- ArchUnit (verify hexagonal boundaries)

### Starting Codebase (Provided)
A typical "pet clinic" or e-commerce application with layered architecture:
```
src/main/java/com/example/
├── controller/
│   └── UserController.java       (@RestController → UserService)
├── service/
│   └── UserService.java          (@Service → UserRepository)
├── repository/
│   └── UserRepository.java       (Spring Data JPA)
├── entity/
│   └── UserEntity.java           (JPA @Entity, getters/setters)
├── dto/
│   └── UserDto.java              (DTO with getters/setters)
└── config/
    └── SecurityConfig.java
```

### The Refactoring Target
```
src/main/java/com/example/
├── application/
│   ├── port/
│   │   ├── inbound/
│   │   │   ├── CreateUserUseCase.java       (interface)
│   │   │   ├── GetUserUseCase.java
│   │   │   └── DeleteUserUseCase.java
│   │   └── outbound/
│   │       ├── LoadUserPort.java            (interface)
│   │       ├── SaveUserPort.java
│   │       └── UserEventPublisher.java      (interface)
│   ├── service/
│   │   └── UserService.java                 (@Service, implements inbound ports)
│   └── domain/
│       ├── User.java                        (pure domain object, no annotations)
│       ├── Email.java                       (value object)
│       └── UserCreatedEvent.java
├── adapter/
│   ├── inbound/
│   │   ├── web/
│   │   │   └── UserController.java          (adapts HTTP → UseCase)
│   │   └── messaging/
│   │       └── UserEventListener.java       (adapts message → UseCase)
│   └── outbound/
│       ├── persistence/
│       │   ├── UserJpaRepository.java       (Spring Data JPA)
│       │   ├── UserPersistenceAdapter.java  (implements LoadUserPort, SaveUserPort)
│       │   └── UserEntity.java              (JPA entity, separate from domain)
│       └── messaging/
│           └── KafkaEventPublisher.java     (implements UserEventPublisher)
└── config/
    └── BeanConfiguration.java              (wire ports to adapters)
```

### Implementation Phases

**Phase 1: Pre-Refactor Baseline (1 day)**
1. Run all existing tests. Record: total test count, total execution time, test type breakdown (unit, integration), code coverage.
2. Add JMH benchmarks for the main service methods. Record baseline performance.
3. Document all pain points in the current architecture: where is business logic leaking into controllers? Where are repositories directly accessed from controllers? Where is testing hard?

**Phase 2: Extract Domain Model (2-3 days)**
1. Create `application/domain` package. Move pure business logic out of `UserEntity` and `UserService` into `User` domain class. No JPA annotations, no Spring annotations.
2. Identify value objects: extract `Email`, `PhoneNumber`, `Address` from string fields. Validate in constructors (fail fast).
3. Ensure domain objects are fully testable without Spring context. Unit test all domain logic (validation, state transitions, business rules).
4. Write a domain model test that creates a `User`, performs operations, and asserts — all in pure Java, no DI, running in < 10ms.

**Phase 3: Define Ports (1-2 days)**
1. Define inbound ports (use cases): `CreateUserUseCase`, `GetUserUseCase`, `UpdateUserUseCase`, `DeleteUserUseCase`, `SearchUsersUseCase`. Each with a single method, domain objects as parameters/returns.
2. Define outbound ports (driven): `LoadUserPort`, `SaveUserPort`, `DeleteUserPort`. Repository interfaces in the application layer, NOT extending Spring Data interfaces.
3. Define event port: `UserEventPublisher` with `publishUserCreated(UserCreatedEvent)`.
4. Refactor `UserService` to implement inbound ports and depend on outbound ports via constructor injection.

**Phase 4: Implement Persistence Adapter (2-3 days)**
1. Move existing `UserEntity` and `UserRepository` into `adapter/outbound/persistence/`.
2. Create `UserPersistenceAdapter` that implements `LoadUserPort` and `SaveUserPort`. This adapter maps between `User` (domain) and `UserEntity` (JPA). Mapping logic is owned by the adapter.
3. Ensure the domain knows nothing about JPA. No `@Entity`, no `@Column`, no Spring Data interfaces in the domain or application layer.
4. Write tests for the adapter: `@DataJpaTest` with `@Import(UserPersistenceAdapter.class)`. Verify CRUD operations, error translation, and mapping correctness.

**Phase 5: Implement Web Adapter (1-2 days)**
1. Refactor existing `UserController` into `adapter/inbound/web/`. Controller now depends on inbound port interfaces (use cases), not services directly.
2. Create DTOs in the adapter package (`CreateUserRequest`, `UserResponse`). Mapping between DTO → domain objects happens in the controller (or a dedicated mapper).
3. Ensure the controller has no knowledge of entities, repositories, or infrastructure. It only knows about ports and domain objects.

**Phase 6: Compare and Document (2-3 days)**
1. Run all tests again. Compare execution time.
   - Unit tests (domain logic): should be dramatically faster (no Spring context, < 10ms each vs. hundreds of ms).
   - Integration tests: similar speed, but more focused (adapter tests test only adapter logic).
2. Measure maintainability: pick 3 change scenarios (add a new field, change validation, add a new persistence mechanism). Time how long each takes in both architectures. Document.
3. Add a new outbound adapter: implement `LoadUserPort` using Redis (cache-aside pattern). Wire it as a decorator around the persistence adapter. No domain changes needed.

### Stretch Goals
1. **Event-driven outbound adapter**: Implement `UserEventPublisher` with real Kafka (Testcontainers Kafka module). Verify events are published.
2. **Secondary inbound adapter**: Add a GraphQL or gRPC adapter alongside REST. Both adapters use the same inbound ports. Demonstrate one business logic, multiple adapters.
3. **CQRS-lite**: Split inbound ports into Commands (`CreateUserUseCase`) and Queries (`GetUserUseCase`). Separate read and write outbound ports. Use read-optimized queries (native SQL, projections) without affecting the write model.

### Evaluation Criteria
| Criterion | Weight | Description |
|-----------|--------|-------------|
| Domain purity | 25% | Domain objects have zero framework annotations. No JPA, no Spring, no Jackson. |
| Port contract quality | 20% | Ports are minimal, focused, use domain types. No leaky abstractions. |
| Adapter isolation | 20% | Each adapter is independently testable and replaceable. Adapter mapping is correct. |
| Test metrics | 20% | Unit tests of domain logic execute in < 15ms avg. Integration test count decreases (more focused). Overall test coverage maintained or improved. |
| Documentation | 15% | Before/after comparison documented with metrics. Trade-offs honestly discussed (complexity increase for simple CRUD, flexibility gained). |

### Estimated Time
- Core refactoring: 7-10 days
- Comparison and documentation: 3-5 days
- Stretch goals: 3-5 days
- **Total**: 2-3 weeks

---

## Project 3: Custom Spring Boot Starter

### Objectives
- Build a production-quality Spring Boot starter with auto-configuration, health indicators, metrics, and configuration metadata
- Understand Spring Boot's auto-configuration mechanism, `@Conditional` annotations, and starter conventions
- Publish to local Maven repository and consume in a test application

### Prerequisite Sessions
- Spring Boot Auto-Configuration Deep Dive
- Spring Boot Actuator (Health, Metrics, Info)
- Gradle/Maven Publishing

### Tech Stack
- Spring Boot 3.x
- Gradle with `maven-publish` plugin
- Spring Boot Configuration Processor (for `additional-spring-configuration-metadata.json`)
- Micrometer (metrics)
- Spring Boot Actuator (health indicators)

### Starter Features
Build a **Distributed Lock Starter** that provides:
- Auto-configured `DistributedLock` bean based on available infrastructure (Redis → Redisson, JDBC → table-based, in-memory → ReentrantLock for testing)
- `@EnableDistributedLock` annotation for explicit enablement
- Configuration properties: `distributed-lock.type`, `distributed-lock.redis.*`, `distributed-lock.jdbc.*`, `distributed-lock.default-timeout`, `distributed-lock.default-lease-time`
- Health indicator: `DistributedLockHealthIndicator` that reports lock provider status
- Metrics: lock acquisition time, lock hold time, contention rate (wait count vs. acquire count)
- Graceful fallback: if Redis is configured but unavailable, starter logs warning and falls back to in-memory lock (configurable: `fail-fast=true` to prevent startup)

### Gradle Module Structure
```
custom-spring-boot-starter/
├── build.gradle.kts
├── settings.gradle.kts
├── distributed-lock-spring-boot-starter/      (main starter module)
│   ├── build.gradle.kts
│   └── src/main/java/com/example/lock/
│       ├── autoconfigure/
│       │   ├── DistributedLockAutoConfiguration.java
│       │   ├── DistributedLockProperties.java
│       │   └── condition/
│       │       ├── OnRedisAvailableCondition.java
│       │       └── OnJdbcAvailableCondition.java
│       ├── core/
│       │   ├── DistributedLock.java               (interface)
│       │   ├── LockAcquisitionException.java
│       │   └── LockContext.java
│       ├── provider/
│       │   ├── redis/
│       │   │   ├── RedisDistributedLock.java      (Redisson-based)
│       │   │   └── RedisLockHealthIndicator.java
│       │   ├── jdbc/
│       │   │   ├── JdbcDistributedLock.java
│       │   │   └── JdbcLockSchemaInitializer.java
│       │   └── memory/
│       │       └── InMemoryDistributedLock.java   (for dev/test)
│       ├── metrics/
│       │   └── DistributedLockMetrics.java
│       └── annotation/
│           └── EnableDistributedLock.java
├── distributed-lock-spring-boot-starter-test/   (test support)
│   └── src/main/java/com/example/lock/test/
│       └── LockTestHarness.java
└── distributed-lock-sample/                     (sample app that uses the starter)
    └── src/main/java/com/example/sample/
        └── SampleApplication.java
```

### Implementation Phases

**Phase 1: Core Interface and In-Memory Provider (1-2 days)**
1. Define `DistributedLock` interface:
   ```java
   public interface DistributedLock {
       LockContext acquire(String lockKey, Duration timeout, Duration leaseTime);
       boolean tryAcquire(String lockKey, Duration waitTime, Duration leaseTime);
       void release(LockContext context);
       boolean isLocked(String lockKey);
   }
   ```
2. Implement `InMemoryDistributedLock` using `ConcurrentHashMap<String, ReentrantLock>` with expiration via `ScheduledExecutorService` for lease time enforcement.
3. Write unit tests for concurrent lock acquisition, lease expiry, and release.

**Phase 2: Auto-Configuration (2-3 days)**
1. Create `DistributedLockProperties` with `@ConfigurationProperties(prefix = "distributed-lock")`. Fields: `type` (enum: REDIS, JDBC, MEMORY), `redis.*`, `jdbc.*`, `defaultTimeout`, `defaultLeaseTime`, `failFast`.
2. Generate `spring-configuration-metadata.json` with `spring-boot-configuration-processor`. Add `additional-spring-configuration-metadata.json` with hints and documentation.
3. Create `DistributedLockAutoConfiguration`:
   ```java
   @AutoConfiguration
   @EnableConfigurationProperties(DistributedLockProperties.class)
   @ConditionalOnProperty(prefix = "distributed-lock", name = "enabled", havingValue = "true", matchIfMissing = true)
   public class DistributedLockAutoConfiguration {
       @Bean
       @ConditionalOnMissingBean
       @ConditionalOnProperty(name = "distributed-lock.type", havingValue = "memory", matchIfMissing = true)
       public DistributedLock inMemoryLock() { ... }

       @Bean
       @ConditionalOnMissingBean
       @ConditionalOnProperty(name = "distributed-lock.type", havingValue = "redis")
       @ConditionalOnClass(RedissonClient.class)
       public DistributedLock redisLock() { ... }

       @Bean
       @ConditionalOnMissingBean
       @ConditionalOnProperty(name = "distributed-lock.type", havingValue = "jdbc")
       @ConditionalOnClass(JdbcTemplate.class)
       public DistributedLock jdbcLock() { ... }
   }
   ```
4. Create `EnableDistributedLock` annotation:
   ```java
   @Target(ElementType.TYPE)
   @Retention(RetentionPolicy.RUNTIME)
   @Import(DistributedLockAutoConfiguration.class)
   public @interface EnableDistributedLock {
       LockType type() default LockType.MEMORY;
   }
   ```
5. Create `spring.factories` (Spring Boot 2.x) or `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot 3.x).

**Phase 3: Redis Provider (2-3 days)**
1. Add Redisson dependency (optional, in `compileOnly` with consumer documentation to include it).
2. Implement `RedisDistributedLock` using Redisson's `RLock`. Handle connection failures (retry, fallback).
3. Test with Testcontainers: start Redis, acquire lock from two threads, verify mutual exclusion.

**Phase 4: JDBC Provider (2-3 days)**
1. Implement `JdbcDistributedLock` using PostgreSQL advisory locks or a custom lock table:
   ```sql
   CREATE TABLE IF NOT EXISTS distributed_locks (
       lock_key VARCHAR(255) PRIMARY KEY,
       owner_id VARCHAR(255),
       acquired_at TIMESTAMP,
       expires_at TIMESTAMP
   );
   ```
2. Implement `JdbcLockSchemaInitializer` (implements `InitializingBean`) that creates the table on startup if not exists.
3. Implement lock acquisition with `INSERT ... ON CONFLICT DO NOTHING` or `SELECT ... FOR UPDATE`.

**Phase 5: Health and Metrics (1-2 days)**
1. Implement `DistributedLockHealthIndicator`:
   - Memory: always UP.
   - Redis: `PING` command → UP/DOWN with details (latency, connection pool stats).
   - JDBC: `SELECT 1` → UP/DOWN.
2. Implement `DistributedLockMetrics` using Micrometer:
   ```java
   @Bean
   public MeterBinder lockMetrics(DistributedLock lock) {
       return registry -> {
           // Custom Timer, Counter, Gauge
           // lock.acquire.time (Timer)
           // lock.contention (Counter: attempts - acquisitions)
           // lock.current.held (Gauge: number of currently held locks)
       };
   }
   ```

**Phase 6: Packaging and Publishing (1 day)**
1. Configure `maven-publish` plugin. Publish to `~/.m2/repository` (local Maven).
2. Create `distributed-lock-sample` module that depends on the starter.
3. Verify: add starter dependency, configure `application.yml`, observe auto-configuration report (actuator `/conditions`), test lock functionality.

### Stretch Goals
1. **Failure injection and resilience**: Add a `faulty` lock type that randomly fails 5% of acquisitions. Test consumer resilience.
2. **Multi-lock support**: `DistributedLock.acquireAll(List<String> lockKeys)` for locking multiple resources atomically.
3. **Observability**: Add distributed tracing spans to lock acquisition/release. Integrate with Micrometer Tracing.
4. **Spring Boot 3 Native**: Compile starter with GraalVM native image. Verify auto-configuration works in native mode.

### Evaluation Criteria
| Criterion | Weight | Description |
|-----------|--------|-------------|
| Auto-configuration correctness | 25% | Starter auto-configures correctly with and without each provider. Graceful fallback works. |
| Configuration metadata quality | 15% | IDE provides autocomplete and documentation for all properties. Hints for enums and defaults. |
| Health and metrics | 20% | Health indicator reports accurate status. Metrics are correctly registered and meaningful. |
| Consumer experience | 25% | Sample application works with minimal configuration (add dependency + 3 lines of config). |
| Documentation | 15% | README with getting started, configuration reference, advanced usage. Javadoc on public API. |

### Estimated Time
- **Total**: 1-2 weeks

---

## Project 4: Saga Orchestrator

### Objectives
- Implement the distributed saga pattern with compensation across 3 independent Spring Boot microservices
- Build a state machine-based saga orchestrator that guarantees eventual consistency
- Implement idempotent consumers and reliable message delivery
- Understand saga trade-offs vs. 2PC vs. eventual consistency alone

### Prerequisite Sessions
- Distributed Transactions and Saga Patterns
- Spring State Machine or custom state machine
- Kafka / RabbitMQ messaging
- Transactional Outbox Pattern
- Idempotency Patterns

### Tech Stack
- 3 Spring Boot microservices: Order Service, Payment Service, Inventory Service
- PostgreSQL per service (Testcontainers)
- Kafka (Testcontainers Kafka module)
- Spring State Machine (optional — can use enum-based state machine)
- Resilience4j (circuit breaker, retry)
- OpenFeign or WebClient (synchronous fallback calls)
- Debezium (optional, for CDC-based outbox)

### Architecture Diagram
```
                    ┌──────────────────────┐
                    │    Saga Orchestrator  │
                    │    (in Order Service) │
                    │                      │
                    │  ┌────────────────┐  │
                    │  │ State Machine  │  │
                    │  │                │  │
                    │  │ CREATED        │  │
                    │  │   ↓            │  │
                    │  │ PAYMENT_PEND   │──┼──▶ Payment Service
                    │  │   ↓            │  │    (process payment)
                    │  │ INVENTORY_PEND │──┼──▶ Inventory Service
                    │  │   ↓            │  │    (reserve inventory)
                    │  │ SHIPPING_PEND  │──┼──▶ Shipping Service (mock)
                    │  │   ↓            │  │
                    │  │ COMPLETED      │  │
                    │  └────────────────┘  │
                    └──────────┬───────────┘
                               │
                     ┌─────────┴─────────┐
                     ▼                   ▼
              ┌─────────────┐    ┌─────────────┐
              │ Order State │    │   Outbox    │
              │ (PostgreSQL)│    │  (PostgreSQL)│
              └─────────────┘    └──────┬──────┘
                                        │ Debezium CDC
                                        ▼
                                 ┌───────────┐
                                 │   Kafka   │
                                 │           │
                                 │ Commands: │
                                 │ payment.  │
                                 │ reserve.  │
                                 │ Response: │
                                 │ payment.  │
                                 │ completed │
                                 └───────────┘
```

### Saga Lifecycle
```
Order Saga: Create Order → Reserve Payment → Reserve Inventory → Ship → Complete

Happy Path:
  1. Client POST /orders → Order CREATED, saga starts
  2. Orchestrator sends RESERVE_PAYMENT command → Payment Service
  3. Payment Service reserves funds, replies PAYMENT_RESERVED
  4. Orchestrator sends RESERVE_INVENTORY command → Inventory Service
  5. Inventory Service reserves stock, replies INVENTORY_RESERVED
  6. Orchestrator sends SHIP_ORDER command → Shipping Service
  7. Shipping Service creates shipment, replies ORDER_SHIPPED
  8. Orchestrator marks saga COMPLETED

Compensation Path (Payment succeeds, Inventory fails):
  1. Steps 1-3 complete (payment reserved)
  2. Inventory Service replies INVENTORY_RESERVATION_FAILED
  3. Orchestrator triggers compensation:
     a. Sends RELEASE_PAYMENT command → Payment Service
     b. Payment Service refunds/reserves, replies PAYMENT_RELEASED
  4. Orchestrator marks saga COMPENSATED, Order → CANCELLED

Compensation Path (Payment fails):
  1. Payment Service replies PAYMENT_RESERVATION_FAILED
  2. No inventory steps were taken → no compensation needed
  3. Orchestrator marks saga FAILED, Order → PAYMENT_FAILED
```

### Implementation Phases

**Phase 1: Project Scaffolding (1-2 days)**
1. Create 3 Spring Boot projects with Gradle multi-module. Each service has its own PostgreSQL (Testcontainers).
2. Set up Kafka infrastructure with Testcontainers: `KafkaContainer` with topics: `saga-commands`, `saga-responses`, `order-events`.
3. Implement shared library: `SagaCommand` base class (with `sagaId`, `commandId`, `commandType`, `timestamp`), `SagaResponse` base class, `MessageHeaders` constants.

**Phase 2: Saga Orchestrator (in Order Service) (4-5 days)**
1. Implement saga state machine:
   ```java
   public enum SagaState {
       CREATED, PAYMENT_PENDING, INVENTORY_PENDING, SHIPPING_PENDING, COMPLETED,
       COMPENSATING_PAYMENT, COMPENSATING_INVENTORY, COMPENSATED, FAILED
   }

   public enum SagaEvent {
       START_SAGA, PAYMENT_RESERVED, PAYMENT_FAILED,
       INVENTORY_RESERVED, INVENTORY_FAILED,
       ORDER_SHIPPED, SHIPPING_FAILED,
       PAYMENT_RELEASED, INVENTORY_RELEASED
   }
   ```
2. Implement state machine transitions:
   ```java
   Map<SagaState, Map<SagaEvent, SagaState>> transitions = Map.of(
       CREATED, Map.of(START_SAGA, PAYMENT_PENDING),
       PAYMENT_PENDING, Map.of(PAYMENT_RESERVED, INVENTORY_PENDING, PAYMENT_FAILED, FAILED),
       INVENTORY_PENDING, Map.of(INVENTORY_RESERVED, SHIPPING_PENDING, INVENTORY_FAILED, COMPENSATING_PAYMENT),
       SHIPPING_PENDING, Map.of(ORDER_SHIPPED, COMPLETED, SHIPPING_FAILED, COMPENSATING_INVENTORY),
       COMPENSATING_PAYMENT, Map.of(PAYMENT_RELEASED, COMPENSATED),
       COMPENSATING_INVENTORY, Map.of(INVENTORY_RELEASED, COMPENSATING_PAYMENT)
   );
   ```
3. Implement `SagaOrchestrator`:
   - `startSaga(Order order)`: creates `saga_instance` record with state=CREATED, publishes command.
   - `handleResponse(SagaResponse response)`: loads saga by ID, determines next state from transition map, executes action or compensation, updates state, publishes next command.
   - Actions map: `Map<SagaState, Consumer<SagaInstance>>` — business logic executed on state entry.
4. Persist `saga_instances` table: `saga_id`, `order_id`, `state`, `payload (JSONB)`, `created_at`, `updated_at`, `version (@Version)`.

**Phase 3: Outbox Pattern (2-3 days)**
1. Implement `outbox` table:
   ```sql
   CREATE TABLE outbox (
       id UUID PRIMARY KEY,
       aggregate_type VARCHAR(255),
       aggregate_id VARCHAR(255),
       event_type VARCHAR(255),
       payload JSONB,
       created_at TIMESTAMP,
       published BOOLEAN DEFAULT FALSE
   );
   ```
2. SAGA commands are written to `outbox` in the same DB transaction as state update.
3. Implement `OutboxPublisher`: scheduled task polls unpublished records → sends to Kafka → marks published.

**Phase 4: Payment and Inventory Services (3-4 days each)**
1. **Payment Service**:
   - Consumer: `@KafkaListener` on `saga-commands` topic, filters by `commandType=RESERVE_PAYMENT`.
   - Checks idempotency (has this command_id been processed? → `payment_processed` table with UNIQUE constraint on command_id).
   - Processes payment: validates amount, reserves funds, records in `payment_reservations` table.
   - Publishes response via outbox → `PAYMENT_RESERVED` or `PAYMENT_FAILED`.
   - Compensation handler: `RELEASE_PAYMENT` command → release reservation, record refund.
2. **Inventory Service**: Same pattern. Reserve inventory quantities with optimistic locking. Compensation: release reserved quantities.

**Phase 5: Idempotency and Retry (2-3 days)**
1. Every consumer checks `processed_commands` table: `INSERT INTO processed_commands (command_id, result) VALUES (?, ?)`. If duplicate (UNIQUE constraint), return stored result.
2. Kafka consumer configuration: enable idempotent producer (`enable.idempotence=true`), consumer reads committed (`isolation.level=read_committed`), manual ack after DB commit.
3. Retry: if a command processing fails transiently, retry with exponential backoff (Resilience4j Retry). If fails permanently, move to dead-letter topic (Kafka DLT).

**Phase 6: Testing (3-4 days)**
1. **Unit tests**: State machine transitions, saga actions, compensation logic. Mock Kafka, mock other services.
2. **Integration tests per service**: `@SpringBootTest` with Testcontainers (PostgreSQL, Kafka). Happy path + each failure mode.
3. **End-to-end tests**: All 3 services running. Test happy path, each compensation path, timeout scenarios (simulate service not responding), duplicate command handling.
4. **Chaos tests**: Kill a service mid-saga. Verify saga eventually completes (compensated or completed). Measure recovery time.

### Stretch Goals
1. **Temporal replacement**: Replace custom saga orchestrator with Temporal.io SDK. Compare code complexity, operational overhead, testing approach.
2. **Saga monitoring dashboard**: Build a dashboard showing active sagas, completion rate, compensation rate, average duration. Use Actuator endpoints.
3. **Parallel sagas**: If a saga has independent steps (e.g., reserve payment AND send confirmation email simultaneously), implement parallel execution with join.
4. **Saga versioning**: Support versioned sagas (v1 and v2 running concurrently for different orders). Handle migration of in-flight sagas.

### Evaluation Criteria
| Criterion | Weight | Description |
|-----------|--------|-------------|
| Saga correctness | 30% | All state transitions handled. Compensation works for every failure point. No stuck sagas. |
| Reliability | 25% | Outbox ensures no lost commands. Idempotent consumers handle duplicates. DLT for poison messages. |
| State persistence | 15% | Saga state survives service restart. Recovery reads state from DB and resumes. |
| Testing | 15% | Comprehensive tests for happy path and all failure modes. Chaos test results documented. |
| Code quality | 15% | Clear separation of state machine logic from business logic. Consistent error handling. |

### Estimated Time
- **Total**: 3-4 weeks

---

## Project 5: Full Observability Stack

### Objectives
- Deploy a complete observability stack: OpenTelemetry + Micrometer + Prometheus + Grafana + Loki + Tempo
- Implement custom business metrics and SLO-based alerting
- Build Grafana dashboards that provide actionable operational insight
- Understand the three pillars (metrics, logs, traces) and how they connect

### Prerequisite Sessions
- Observability Fundamentals (Metrics, Logs, Traces)
- Micrometer and Spring Boot Actuator
- Prometheus and Grafana
- OpenTelemetry

### Tech Stack
- Spring Boot 3.x with Micrometer and Actuator
- OpenTelemetry Java Agent (auto-instrumentation)
- Prometheus (metrics scraping)
- Grafana (visualization, alerting)
- Loki (log aggregation) + Promtail (log collection)
- Tempo (distributed tracing backend)
- Docker Compose for local stack
- A demo microservices application (3 services: Gateway, Order Service, Payment Service)

### Architecture Diagram
```
┌─────────────────────────────────────────────────────────────┐
│                     Application Services                     │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐                │
│  │ Gateway  │──▶│ Order    │──▶│ Payment  │                │
│  │ (8080)   │   │ (8081)   │   │ (8082)   │                │
│  │          │   │          │   │          │                │
│  │ OTel Agent│  │ OTel Agent│  │ OTel Agent│                │
│  │ Micrometer│  │ Micrometer│  │ Micrometer│                │
│  │ Logback   │  │ Logback   │  │ Logback   │                │
│  └────┬─────┘   └────┬─────┘   └────┬─────┘                │
│       │              │              │                       │
├───────┼──────────────┼──────────────┼───────────────────────┤
│       │              │              │  Observability Backend │
│       ▼              ▼              ▼                       │
│  ┌──────────────────────────────────────┐                   │
│  │        OpenTelemetry Collector       │                   │
│  │  (receives: OTLP traces, metrics)   │                   │
│  └───┬──────────┬───────────┬──────────┘                   │
│      │ Traces   │ Metrics   │ Logs (via Loki)              │
│      ▼          ▼           ▼                              │
│  ┌───────┐ ┌──────────┐ ┌──────┐                           │
│  │ Tempo │ │Prometheus│ │ Loki │                           │
│  │(trace)│ │(metrics) │ │(logs)│                           │
│  └───┬───┘ └────┬─────┘ └──┬───┘                           │
│      │          │           │                               │
│      └──────────┼───────────┘                               │
│                 ▼                                           │
│          ┌──────────┐                                      │
│          │ Grafana  │ (unified UI)                         │
│          │ Dashboards│                                      │
│          │ Alerting │                                       │
│          │ Explore  │                                       │
│          └──────────┘                                      │
└─────────────────────────────────────────────────────────────┘
```

### Implementation Phases

**Phase 1: Infrastructure Setup (1-2 days)**
1. Write `docker-compose.yml` with: Prometheus, Grafana, Loki, Tempo, OpenTelemetry Collector.
2. Configure Prometheus scrape config for Spring Boot Actuator metrics.
3. Configure OpenTelemetry Collector pipeline: OTLP receiver → batch processor → exporters (Tempo for traces, Prometheus for metrics).
4. Configure Loki + Promtail for log collection from Docker containers.

**Phase 2: Application Instrumentation (2-3 days)**
1. Add dependencies: `micrometer-registry-prometheus`, `spring-boot-starter-actuator`, OpenTelemetry Java Agent JAR (JVM argument).
2. Configure `application.yml`:
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health,metrics,prometheus,loggers,env,info
     metrics:
       export:
         prometheus:
           enabled: true
       tags:
         application: ${spring.application.name}
     tracing:
       sampling:
         probability: 1.0
   ```
3. Add OpenTelemetry JVM args: `-javaagent:opentelemetry-javaagent.jar -Dotel.service.name=order-service -Dotel.traces.exporter=otlp`.
4. Verify: traces appear in Tempo, metrics appear in Prometheus, logs appear in Loki.

**Phase 3: Custom Business Metrics (2-3 days)**
1. Implement business metrics using Micrometer:
   ```java
   @Service
   public class OrderService {
       private final Counter ordersCreated;
       private final Timer orderProcessingTime;
       private final DistributionSummary orderValue;

       public OrderService(MeterRegistry registry) {
           this.ordersCreated = Counter.builder("orders.created.total")
               .description("Total orders created")
               .tag("service", "order-service")
               .register(registry);

           this.orderProcessingTime = Timer.builder("orders.processing.time")
               .description("Order processing duration")
               .publishPercentiles(0.5, 0.95, 0.99)
               .register(registry);

           this.orderValue = DistributionSummary.builder("orders.value")
               .description("Order value distribution")
               .baseUnit("dollars")
               .publishPercentiles(0.5, 0.95, 0.99)
               .register(registry);
       }

       public Order createOrder(CreateOrderRequest req) {
           return orderProcessingTime.record(() -> {
               Order order = doCreateOrder(req);
               ordersCreated.increment();
               orderValue.record(order.getTotalAmount().getValue());
               return order;
           });
       }
   }
   ```
2. Implement SLIs (Service Level Indicators):
   - Availability: `up{job="order-service"}` — 1 for up, 0 for down.
   - Latency: `histogram_quantile(0.99, rate(orders_processing_time_seconds_bucket[5m]))`.
   - Error rate: `rate(orders_created_total{status="error"}[5m]) / rate(orders_created_total[5m])`.
   - Throughput: `rate(orders_created_total[5m])`.
3. Define SLOs in code:
   ```java
   @Component
   public class OrderSLO {
       // 99.9% availability over 30 days
       // 99th percentile latency < 500ms
       // Error rate < 0.1%
   }
   ```

**Phase 4: Grafana Dashboards (2-3 days)**
1. Build **Golden Signals Dashboard** for each service:
   - Row 1: Request rate, error rate, latency (p50/p95/p99) — RED method.
   - Row 2: CPU, memory, GC, thread count, connection pool — USE method.
   - Row 3: Business metrics (order value, payment success rate, inventory levels).
2. Build **SLO Dashboard**:
   - Error budget remaining (gauge).
   - Burn rate (how fast error budget is being consumed).
   - Multi-window burn rate alerting (short window: 1h with 14.4x burn rate = 2% budget consumed in 1h → page).
3. Build **Dependency Dashboard**:
   - Service dependency map (from traces).
   - Downstream call latency and error rate per dependency.
4. Build **Trace Explorer Dashboard**:
   - Searchable trace view with waterfall.
   - Correlation between high-latency traces and log patterns.
5. Export dashboards as JSON for version control.

**Phase 5: Alerting Rules (1-2 days)**
1. Configure Prometheus alert rules:
   ```yaml
   groups:
     - name: order-service-slos
       rules:
         - alert: HighErrorRate
           expr: rate(orders_created_total{status="error"}[5m]) / rate(orders_created_total[5m]) > 0.01
           for: 5m
           labels: { severity: critical }
           annotations:
             summary: "Order Service error rate > 1%"
             description: "Error rate is {{ $value | humanizePercentage }} over the last 5 minutes"

         - alert: HighLatency
           expr: histogram_quantile(0.99, rate(order_processing_time_seconds_bucket[5m])) > 0.5
           for: 5m
           labels: { severity: warning }

         - alert: ErrorBudgetBurn
           expr: error_budget_burn_rate > 14.4
           for: 1h
           labels: { severity: page }
           annotations:
             summary: "Error budget burning fast - 2% consumed in 1 hour"
   ```
2. Configure Grafana alerting for business-level alerts (e.g., "No orders in last 15 minutes during business hours").
3. Implement alert routing: critical → PagerDuty/Opsgenie, warning → Slack, info → email digest.

**Phase 6: Log Correlation and Structured Logging (1-2 days)**
1. Configure Logback with JSON layout (Logstash encoder) including trace_id and span_id in each log line:
   ```xml
   <encoder class="net.logstash.logback.encoder.LogstashEncoder">
       <includeMdcKeyName>traceId</includeMdcKeyName>
       <includeMdcKeyName>spanId</includeMdcKeyName>
   </encoder>
   ```
2. Verify: in Grafana, click a log line → "View trace" jumps to Tempo. Click a trace span → "View logs" shows all logs with matching trace_id.

### Stretch Goals
1. **Custom OpenTelemetry spans**: Add manual instrumentation for critical business operations. Create child spans for each saga step.
2. **Synthetic monitoring**: Write a canary that places a fake order every 5 minutes. Alert if canary fails — tests the full path from user perspective.
3. **Cost attribution**: Tag metrics with `cost_center`. Build dashboard showing resource usage per team/cost center.
4. **Continuous profiling**: Integrate Pyroscope or Parca for continuous CPU/memory profiling. Correlate profiles with high-latency traces.

### Evaluation Criteria
| Criterion | Weight | Description |
|-----------|--------|-------------|
| Coverage | 25% | All 3 pillars (metrics, logs, traces) are instrumented and correlated. |
| SLO implementation | 25% | SLIs defined, SLOs configured, error budgets calculated, multi-window burn rate alerts. |
| Dashboard quality | 20% | Dashboards are actionable (not just pretty graphs). Answer: "Is the service healthy?" in < 5 seconds. |
| Alert quality | 15% | Alerts are meaningful (no false positives), well-documented (runbook link), and correctly routed by severity. |
| Production readiness | 15% | Configuration is version-controlled. Dashboards are exported as JSON. Setup is reproducible (docker-compose up). |

### Estimated Time
- **Total**: 2-3 weeks

---

## Project 6: High-Performance API

### Objectives
- Build a Spring Boot API that achieves 50,000 requests per second on modest hardware
- Apply CQRS read/write separation, multi-level caching, Virtual Threads, and connection pool optimization
- Use k6 for load testing with scientific methodology (ramp-up, soak, spike tests)
- Identify and eliminate bottlenecks methodically

### Prerequisite Sessions
- Java Virtual Threads (Project Loom)
- CQRS Pattern
- Caching Strategies (Cache-Aside, Read-Through, Write-Behind)
- Database Connection Pool Tuning
- Performance Testing Methodology

### Tech Stack
- Spring Boot 3.2+ (Virtual Threads on Tomcat)
- PostgreSQL 15+ (primary writes)
- Read Replicas (for CQRS read separation)
- Redis 7+ (L2 cache)
- Caffeine (L1 in-process cache)
- HikariCP (connection pool tuning)
- k6 (load testing)
- JMH (microbenchmarks)
- jasync-profiler / async-profiler (CPU profiling)

### Target Environment
- 4 vCPU, 8 GB RAM (AWS c6i.xlarge equivalent)
- PostgreSQL: db.r6g.xlarge (4 vCPU, 32 GB RAM)
- Redis: cache.r6g.large (2 vCPU, 6 GB RAM)

### Architecture Diagram
```
                     ┌───────────┐
                     │  k6 Load  │
                     │ Generator │
                     └─────┬─────┘
                           │ 50K RPS
                           ▼
┌──────────────────────────────────────────────────────────┐
│                  Spring Boot on Virtual Threads          │
│                                                          │
│  ┌──────────────────────┐   ┌────────────────────────┐  │
│  │     Write Path       │   │      Read Path          │  │
│  │                      │   │                         │  │
│  │ REST Controller      │   │ REST Controller         │  │
│  │   │                  │   │   │                     │  │
│  │   ▼                  │   │   ▼                     │  │
│  │ Command Service      │   │ Query Service           │  │
│  │   │                  │   │   │                     │  │
│  │   ▼                  │   │   ├── L1: Caffeine      │  │
│  │ Write Repository     │   │   ├── L2: Redis         │  │
│  │   │                  │   │   └── L3: Read Replica  │  │
│  │   ▼                  │   │                         │  │
│  │ [Write DB]           │   │ [Read Replica]          │  │
│  │ PostgreSQL Primary   │   │ PostgreSQL Replica      │  │
│  └──────────────────────┘   └────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │ HikariCP: write-pool (20 conns) | read-pool (50) │   │
│  └──────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

### Implementation Phases

**Phase 1: Baseline Application (1-2 days)**
1. Create a simple order management API: `POST /orders` (write), `GET /orders/{id}` (read), `GET /orders?status=PENDING` (search).
2. Single PostgreSQL instance. HikariCP with default settings (10 connections).
3. Platform threads (default Tomcat).
4. Run baseline load test with k6:
   ```javascript
   // k6 script
   import http from 'k6/http';
   import { check } from 'k6';

   export const options = {
       stages: [
           { duration: '2m', target: 1000 },   // ramp up
           { duration: '5m', target: 1000 },   // steady state
           { duration: '2m', target: 5000 },   // ramp up
           { duration: '5m', target: 5000 },   // steady state
           { duration: '1m', target: 0 },      // ramp down
       ],
       thresholds: {
           http_req_duration: ['p(95)<500', 'p(99)<1000'],
           http_req_failed: ['rate<0.01'],
       },
   };

   export default function () {
       // Create order
       const createRes = http.post('http://localhost:8080/orders', JSON.stringify({
           userId: `user_${__VU}_${__ITER}`,
           items: [{ productId: 'prod_1', quantity: 1 }]
       }), { headers: { 'Content-Type': 'application/json' } });

       if (createRes.status === 201) {
           const orderId = createRes.json('id');
           // Read order
           http.get(`http://localhost:8080/orders/${orderId}`);
       }
   }
   ```
5. Record baseline: requests/sec, p50/p95/p99 latency, error rate, CPU/memory/GC.

**Phase 2: Virtual Threads (1-2 days)**
1. Enable Virtual Threads:
   ```yaml
   spring:
     threads:
       virtual:
         enabled: true
   ```
2. Re-run load test. Compare with platform threads. Expect: significantly higher concurrency (thousands of concurrent requests vs. hundreds), lower thread context switching overhead.
3. Tune Tomcat: set `server.tomcat.max-connections` and test at different values (1000, 5000, 10000, unlimited). Find the sweet spot.
4. Document: Virtual Threads eliminate the need for reactive programming for most I/O-bound workloads. CPU-bound workloads still benefit from bounded thread pools.

**Phase 3: CQRS Read/Write Separation (2-3 days)**
1. Set up PostgreSQL read replica (can be same instance with different port for local dev, or use Testcontainers with replica).
2. Configure dual DataSources:
   ```java
   @Configuration
   public class DataSourceConfig {
       @Primary @Bean
       @ConfigurationProperties("spring.datasource.write")
       public DataSource writeDataSource() { ... }

       @Bean
       @ConfigurationProperties("spring.datasource.read")
       public DataSource readDataSource() { ... }
   }
   ```
3. Route reads to replica, writes to primary. Use Spring's `@Transactional(readOnly = true)` → auto-routing via `AbstractRoutingDataSource`.
4. Separate service layers: `OrderCommandService` (write), `OrderQueryService` (read). Command service uses write DS, query service uses read DS.
5. Run load test. Compare: read-heavy workload should see significant improvement (reads don't compete with writes for DB resources).

**Phase 4: Multi-Level Caching (2-3 days)**
1. **L1: Caffeine (in-process)**:
   ```java
   @Configuration
   public class CacheConfig {
       @Bean
       public CacheManager caffeineCacheManager() {
           CaffeineCacheManager mgr = new CaffeineCacheManager();
           mgr.setCaffeine(Caffeine.newBuilder()
               .maximumSize(10_000)
               .expireAfterWrite(5, TimeUnit.MINUTES)
               .recordStats());
           return mgr;
       }
   }
   ```
   Use `@Cacheable(value = "orders", key = "#orderId")` on query methods.

2. **L2: Redis (distributed)**:
   ```java
   @Configuration
   @EnableCaching
   public class RedisConfig {
       @Bean
       public RedisCacheManagerBuilderCustomizer customizer() {
           return builder -> builder
               .withCacheConfiguration("orders",
                   RedisCacheConfiguration.defaultCacheConfig()
                       .entryTtl(Duration.ofMinutes(10))
                       .disableCachingNullValues());
       }
   }
   ```

3. **Multi-Level Read Pattern**:
   ```java
   public OrderDto getOrder(String orderId) {
       // L1: Caffeine
       OrderDto cached = caffeineCache.getIfPresent(orderId);
       if (cached != null) { metrics.incrementCacheHit("L1"); return cached; }
       metrics.incrementCacheMiss("L1");

       // L2: Redis
       cached = redisTemplate.opsForValue().get("orders:" + orderId);
       if (cached != null) { metrics.incrementCacheHit("L2"); caffeineCache.put(orderId, cached); return cached; }
       metrics.incrementCacheMiss("L2");

       // L3: Database
       OrderDto fromDb = orderRepository.findById(orderId).map(OrderDto::from);
       fromDb.ifPresent(dto -> {
           redisTemplate.opsForValue().set("orders:" + orderId, dto, Duration.ofMinutes(10));
           caffeineCache.put(orderId, dto);
       });
       metrics.incrementCacheMiss("L3");
       return fromDb.orElseThrow(() -> new OrderNotFoundException(orderId));
   }
   ```

4. **Cache Invalidation**: On write (create/update order), evict from Caffeine (local, instant) and Redis (distributed, via pub/sub to all instances). Write-Through pattern: update cache synchronously on write.

**Phase 5: Connection Pool Optimization (1-2 days)**
1. Measure connection pool metrics (via Micrometer/HikariCP metrics):
   - Active connections, idle connections, pending (waiting) connections, connection timeout count.
2. Tune HikariCP:
   ```yaml
   spring:
     datasource:
       write:
         hikari:
           maximum-pool-size: 20
           minimum-idle: 5
           connection-timeout: 3000
           idle-timeout: 600000
           max-lifetime: 1800000
           leak-detection-threshold: 10000
       read:
         hikari:
           maximum-pool-size: 50
           minimum-idle: 10
   ```
3. Key metric: `hikaricp_connections_pending` should be 0 at steady state. If > 0, increase pool size.
4. Database-side tuning: `max_connections = write_pool_size * instances + read_pool_size * instances + 20 (overhead)`. If PostgreSQL is configured for 200 max connections and you have 10 instances with 50 connections each → you need 520 connections. Adjust.

**Phase 6: Load Testing and Tuning (3-4 days)**
1. **Ramp test**: Gradually increase load (100 → 500 → 1000 → 5000 → 10000 → 50000 RPS). Find the breaking point. Analyze what breaks first (DB, app CPU, network, connection pool).
2. **Soak test**: 50% of max load for 24 hours. Monitor for memory leaks, gradual performance degradation, log growth.
3. **Spike test**: Baseline at 1000 RPS, spike to 50000 for 30 seconds, back to 1000. Measure recovery time.
4. **Bottleneck analysis**: Use async-profiler to generate flame graphs. Identify hot methods. Optimize.
5. **Database query optimization**: Analyze slow queries with `EXPLAIN ANALYZE`. Add indexes. Denormalize where necessary.
6. **Document the journey**: Baseline → after each optimization → final. RPS, latency percentiles, CPU%, memory, GC times.

### Stretch Goals
1. **Reactive alternative**: Build the same API with WebFlux + R2DBC. Compare performance characteristics and code complexity with Virtual Threads.
2. **GraalVM Native Image**: Compile to native image. Compare startup time, memory footprint, and throughput.
3. **Connection pooling deeper dive**: Implement a connection proxy that logs slow queries (> 100ms) and connection leaks (> 5s hold time).
4. **Adaptive concurrency**: Implement a concurrency limiter that detects overload and sheds excess load gracefully (return 503 vs. queue and timeout).

### Evaluation Criteria
| Criterion | Weight | Description |
|-----------|--------|-------------|
| Performance achievement | 30% | Achieve target RPS (50K) on target hardware with p99 < 100ms. |
| Methodology | 20% | Scientific approach: hypothesis → test → measure → analyze → iterate. Documented at each step. |
| Bottleneck identification | 20% | Correctly identify and eliminate bottlenecks. Use profiling data to justify changes. |
| Caching correctness | 15% | Cache invalidation is correct. No stale data served. Cache hit rate measured and optimized. |
| Production readiness | 15% | Graceful degradation under overload. No cascading failures. Circuit breakers for downstream calls. |

### Estimated Time
- **Total**: 3-4 weeks

---

## Project 7: Kubernetes Operator for Spring Boot

### Objectives
- Build a custom Kubernetes operator using Java and the fabric8 Kubernetes client
- Manage the full lifecycle of Spring Boot applications: deploy, scale, upgrade, rollback, health monitoring
- Implement custom resources (CRDs) for declarative application management
- Understand the Kubernetes operator pattern and reconciliation loop

### Prerequisite Sessions
- Kubernetes Fundamentals (Pods, Services, Deployments, ConfigMaps, Secrets)
- Kubernetes Controllers and Operator Pattern
- fabric8 Kubernetes Client
- Spring Boot on Kubernetes (liveness/readiness probes, graceful shutdown)

### Tech Stack
- Spring Boot 3.x (the operator itself is a Spring Boot app)
- fabric8 Kubernetes Client 6.x
- JUnit 5 + fabric8 Kubernetes Mock Server (for testing)
- Testcontainers (optional: K3s container for integration testing)
- Docker (for building operator image)

### Custom Resource Definition (CRD)
```yaml
apiVersion: platform.example.com/v1
kind: SpringBootApplication
metadata:
  name: my-order-service
spec:
  image: "registry.example.com/order-service:1.2.3"
  replicas: 3
  javaOpts: "-Xms256m -Xmx512m -XX:+UseZGC"
  config:
    ref: order-service-config          # ConfigMap reference
  secrets:
    ref: order-service-secrets         # Secret reference
  env:
    - name: EXTRA_FLAG
      value: "true"
  resources:
    requests:
      cpu: "500m"
      memory: "512Mi"
    limits:
      cpu: "1000m"
      memory: "1Gi"
  probes:
    liveness:
      path: "/actuator/health/liveness"
      port: 8080
      initialDelaySeconds: 30
      periodSeconds: 10
    readiness:
      path: "/actuator/health/readiness"
      port: 8080
      initialDelaySeconds: 10
      periodSeconds: 5
  scaling:
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilization: 70
    targetMemoryUtilization: 80
  upgradeStrategy:
    type: RollingUpdate                # or BlueGreen, Canary
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
    canary:
      steps:
        - setWeight: 20
          pauseDuration: 5m
        - setWeight: 50
          pauseDuration: 5m
        - setWeight: 100
  rollback:
    enabled: true
    revisionsToKeep: 5
status:
  phase: Running                       # Pending, Running, Upgrading, RollingBack, Failed
  availableReplicas: 3
  currentRevision: 2
  conditions:
    - type: Available
      status: "True"
      lastTransitionTime: "2024-01-15T10:00:00Z"
    - type: Progressing
      status: "False"
```

### Implementation Phases

**Phase 1: Operator Scaffolding (1-2 days)**
1. Create Spring Boot project with dependencies: Spring Web, Spring Actuator, fabric8 kubernetes-client, fabric8 kubernetes-server-mock (test).
2. Generate CRD Java model using fabric8's `@Version`, `@Group`, `@Kind` annotations, or from YAML using fabric8 CRD generator.
   ```java
   @Group("platform.example.com")
   @Version("v1")
   @Kind("SpringBootApplication")
   public class SpringBootApplication extends CustomResource<SpringBootApplicationSpec, SpringBootApplicationStatus> {
   }

   public class SpringBootApplicationSpec {
       private String image;
       private int replicas;
       private String javaOpts;
       private ResourceRequirements resources;
       private ProbeConfig probes;
       private ScalingConfig scaling;
       private UpgradeStrategy upgradeStrategy;
       private RollbackConfig rollback;
       // getters/setters
   }

   public class SpringBootApplicationStatus {
       private String phase;
       private int availableReplicas;
       private int currentRevision;
       private List<Condition> conditions;
       // getters/setters
   }
   ```

**Phase 2: Reconciliation Loop (3-4 days)**
1. Implement `SpringBootApplicationReconciler`:
   ```java
   @Component
   public class SpringBootApplicationReconciler implements Reconciler<SpringBootApplication> {

       private final KubernetesClient client;

       @Override
       public Result reconcile(Request request, SpringBootApplication resource) {
           String namespace = resource.getMetadata().getNamespace();
           String name = resource.getMetadata().getName();

           // 1. Retrieve or create Deployment
           Deployment existingDeploy = client.apps().deployments()
               .inNamespace(namespace)
               .withName(name)
               .get();

           if (existingDeploy == null) {
               createDeployment(resource);
               updateStatus(resource, "Created", "Deployment created");
               return new Result(true, Duration.ofSeconds(10)); // re-schedule
           }

           // 2. Check if spec has changed (image, replicas, env, config)
           if (specChanged(resource, existingDeploy)) {
               updateDeployment(resource, existingDeploy);
               updateStatus(resource, "Upgrading", "Applying spec changes");
               return new Result(true, Duration.ofSeconds(5));
           }

           // 3. Check if deployment is healthy
           DeploymentStatus deployStatus = existingDeploy.getStatus();
           if (deployStatus.getAvailableReplicas() == null ||
               deployStatus.getAvailableReplicas() < resource.getSpec().getReplicas()) {
               updateStatus(resource, "Progressing", "Waiting for replicas");
               return new Result(true, Duration.ofSeconds(5));
           }

           // 4. Check if scaling is needed
           int currentReplicas = existingDeploy.getSpec().getReplicas();
           if (currentReplicas != resource.getSpec().getReplicas()) {
               client.apps().deployments()
                   .inNamespace(namespace)
                   .withName(name)
                   .scale(resource.getSpec().getReplicas());
               return new Result(true, Duration.ofSeconds(5));
           }

           // 5. All good
           updateStatus(resource, "Running", "All replicas available");
           return new Result(false); // no re-schedule unless watch event
       }
   }
   ```
2. Implement reconciliation actions:
   - `createDeployment()`: Build a `Deployment` object from CR spec. Create Service, ConfigMap, HPA.
   - `updateDeployment()`: Apply rolling update with strategy from spec.
   - `rollback()`: Revert to previous revision.
3. Register the reconciler:
   ```java
   @Configuration
   public class OperatorConfig {
       @Bean
       public Controller controller(KubernetesClient client, Reconciler<SpringBootApplication> reconciler) {
           return client.resources(SpringBootApplication.class)
               .inAnyNamespace()
               .watch(new ControllerBuilder(reconciler, client).build());
       }
   }
   ```

**Phase 3: Upgrade Strategies (3-4 days)**
1. **RollingUpdate**: Default Kubernetes behavior. Set `maxSurge`, `maxUnavailable` from spec.
2. **BlueGreen**: Create new Deployment (v2) alongside existing (v1). Wait for v2 to be ready → switch Service selector to v2 → delete v1. Implement health check polling during cutover.
3. **Canary**: Use Istio/Nginx Ingress canary annotations or a separate Canary Service. Implement step-based traffic shifting with pause durations.

**Phase 4: Health Monitoring and Auto-Remediation (2-3 days)**
1. Watch Pod health via readiness probes. If Pods are not ready after timeout, mark Deployment as degraded.
2. Implement liveness-based restart: if Pod is in CrashLoopBackOff, escalate to rollback (if recent upgrade) or alert.
3. Watch Actuator health endpoint directly: `GET /actuator/health` on each Pod. Aggregate health status. If down → attempt restart, then rollback.
4. Implement HPA management: create/update HorizontalPodAutoscaler based on `scaling` spec.

**Phase 5: Operator Testing (3-4 days)**
1. **Unit tests**: Test reconciler logic with mock KubernetesClient.
   ```java
   @Test
   void shouldCreateDeploymentWhenNotExists() {
       SpringBootApplication app = createTestApp("my-app", "image:1.0", 3);
       // Configure mock server
       server.expect().get()
           .withPath("/apis/apps/v1/namespaces/default/deployments/my-app")
           .andReturn(404, "").once();
       server.expect().post()
           .withPath("/apis/apps/v1/namespaces/default/deployments")
           .andReturn(201, deploymentJson).once();

       Result result = reconciler.reconcile(new Request("my-app"), app);

       assertThat(result.isSchedule()).isTrue();
       // Verify status update was called
   }
   ```
2. **Integration tests**: Use fabric8 Kubernetes Mock Server or Testcontainers K3s.
3. **End-to-end tests**: Deploy operator to real Kubernetes cluster. Create a `SpringBootApplication` CR. Verify Deployment, Service, HPA are created. Update CR spec → verify rolling update. Trigger failure → verify auto-remediation.

**Phase 6: Operator Packaging (1-2 days)**
1. Write `Dockerfile` for operator.
2. Generate Helm chart for deploying the operator.
3. Write RBAC manifests (ClusterRole, ClusterRoleBinding) with least privilege.
4. Implement leader election for HA operator deployment.

### Stretch Goals
1. **Multi-version CRD support**: Support v1alpha1, v1beta1, v1 of the CRD. Implement conversion webhooks.
2. **Operator SDK comparison**: Port a subset of the operator to Go using Operator SDK. Compare developer experience, type safety, testing.
3. **GitOps integration**: Watch a Git repository for `SpringBootApplication` manifests. Reconcile cluster state with Git (ArgoCD-like, simplified).
4. **Metrics and alerts**: Expose operator metrics (reconciliation count, duration, error rate). Integrate with Prometheus Operator.

### Evaluation Criteria
| Criterion | Weight | Description |
|-----------|--------|-------------|
| Reconciliation correctness | 25% | All spec changes correctly reconciled. No drift between desired and actual state. |
| Upgrade safety | 20% | Rolling update works. Canary upgrade with pause/resume. Rollback works. |
| Health monitoring | 20% | Spring Boot health endpoints are monitored. Auto-remediation triggers correctly. |
| Testing | 20% | Unit tests for reconciler logic. Integration tests with K3s/Kind. E2E scenarios. |
| Operator production readiness | 15% | RBAC, leader election, Helm chart, Docker image, metrics. |

### Estimated Time
- **Total**: 4-6 weeks

---

## Project 8: Framework Migration (Spring Boot ↔ Quarkus)

### Objectives
- Migrate a non-trivial Spring Boot application to Quarkus (or vice versa)
- Understand the architectural differences between the two frameworks
- Compare performance, memory, startup time, developer experience
- Create a migration playbook and production readiness checklist

### Prerequisite Sessions
- Spring Boot Internals (auto-configuration, DI, AOP)
- Quarkus Fundamentals (CDI, build-time processing, native compilation)
- MicroProfile specifications
- Performance benchmarking

### Tech Stack
- Spring Boot 3.x (source) + Quarkus 3.x (target)
- PostgreSQL (shared between both)
- Docker + Docker Compose
- Apache Bench / k6 / wrk (load testing)
- JMH (microbenchmarks)

### Source Application (Non-Trivial)
A REST API service with the following characteristics:
- 15+ endpoints across 3 resource types (Products, Orders, Customers)
- JPA/Hibernate with 8+ entities, complex relationships
- Spring Security with JWT authentication
- Spring Cache (Caffeine)
- Spring Actuator with custom health indicators
- Spring Scheduling (`@Scheduled`)
- Spring Events (`ApplicationEventPublisher`)
- Kafka producer and consumer (Spring Kafka)
- OpenFeign client for external service calls
- Custom exception handling (`@ControllerAdvice`)
- Validation (`jakarta.validation`)
- Pagination and sorting
- File upload/download

### Migration Mapping
| Spring Boot | Quarkus |
|-------------|---------|
| `@RestController` | `@Path` + JAX-RS `@GET`/`@POST` |
| `@Service` | `@ApplicationScoped` |
| `@Autowired` / constructor injection | `@Inject` / constructor injection |
| `@Transactional` | `@Transactional` (same, Panache) |
| `@Scheduled` | `@Scheduled` (Quartz or built-in) |
| Spring Security | Quarkus Security + `quarkus-oidc` |
| Spring Cache | Quarkus Cache (`quarkus-cache`) |
| Spring Actuator | SmallRye Health, Micrometer |
| `ApplicationEventPublisher` | CDI Events (`@Observes`, `Event.fire()`) |
| Spring Kafka | SmallRye Reactive Messaging (Kafka) |
| OpenFeign | REST Client Reactive (`@RegisterRestClient`) |
| `application.yml` | `application.properties` (or YAML with extension) |
| Spring Data JPA | Panache (`PanacheRepository`) or Spring Data JPA (compatibility layer) |

### Implementation Phases

**Phase 1: Baseline Measurement (1-2 days)**
1. Profile the Spring Boot application:
   - Startup time (to first request served).
   - Memory footprint (RSS, heap used, Metaspace).
   - Throughput: requests/sec at steady state.
   - Latency: p50, p95, p99.
   - GC behavior: pause times, frequency.
   - Build time, JAR size.
2. Run load tests: ramp test, soak test (30 min), spike test.
3. Document all metrics in a baseline sheet.

**Phase 2: Scaffolding the Quarkus Project (1-2 days)**
1. Generate Quarkus project: `quarkus create app --maven --java=21`.
2. Add extensions: `quarkus-resteasy-reactive-jackson`, `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-smallrye-health`, `quarkus-micrometer`, `quarkus-smallrye-openapi`, `quarkus-cache`, `quarkus-security`, `quarkus-smallrye-jwt`, `quarkus-scheduler`, `quarkus-messaging-kafka`.
3. Set up equivalent package structure.

**Phase 3: Data Layer Migration (3-4 days)**
1. Migrate JPA entities: replace `@Entity` with `@Entity` (same). Replace Spring Data repositories with Panache:
   ```java
   // Spring Data
   public interface ProductRepository extends JpaRepository<Product, Long> {
       List<Product> findByCategory(String category);
   }

   // Panache
   @ApplicationScoped
   public class ProductRepository implements PanacheRepository<Product> {
       public List<Product> findByCategory(String category) {
           return find("category", category).list();
       }
   }
   ```
2. Handle relationships, lazy loading, N+1 queries. Compare with Hibernate's `JOIN FETCH` vs. Panache's `find().project()`.
3. Migration test: run all data access queries, compare results.

**Phase 4: REST Layer Migration (2-3 days)**
1. Convert `@RestController` to JAX-RS resources.
2. Map request/response DTOs (Jackson annotations are mostly compatible).
3. Implement exception handling: `@ControllerAdvice` → JAX-RS `ExceptionMapper`.
4. Implement pagination: Spring's `Pageable` → Quarkus `@QueryParam("page")` + custom `PagedResponse`.
5. Test all endpoints: status codes, response bodies, error cases match.

**Phase 5: Cross-Cutting Concerns (3-4 days)**
1. **Security**: Migrate JWT authentication. Spring Security filter chain → Quarkus HTTP Security Policy. Test role-based access, token validation, refresh.
2. **Caching**: `@Cacheable` → `@CacheResult`. Cache configuration (Caffeine is the default in both).
3. **Scheduling**: `@Scheduled` → `@Scheduled` (Quarkus uses Quartz by default, or `quarkus-scheduler` for simpler cases).
4. **Events**: `ApplicationEventPublisher.publishEvent()` → CDI `Event.fire()`. `@EventListener` → `@Observes` or `@ObservesAsync`.
5. **Kafka**: Spring Kafka `@KafkaListener` → SmallRye Reactive Messaging `@Incoming`. Check serialization, error handling, dead-letter topics.

**Phase 6: Observability and Operations (2-3 days)**
1. **Health**: Custom `HealthIndicator` → SmallRye `HealthCheck`. Map all health indicators.
2. **Metrics**: Micrometer is used by both. Tags, naming conventions (Spring uses `.`, MicroProfile uses `_` — normalize).
3. **Logging**: Logback → JBoss Logging (or Logback can still be used). Structured logging with JSON.
4. **Graceful shutdown**: Spring's `server.shutdown=graceful` → Quarkus's `quarkus.shutdown.timeout`.

**Phase 7: Testing Migration (2-3 days)**
1. Migrate unit tests: `@ExtendWith(MockitoExtension.class)` → `@QuarkusTest` or `@ExtendWith(MockitoExtension.class)` (Mockito works independently).
2. Migrate integration tests: `@SpringBootTest` → `@QuarkusIntegrationTest`. Test containers setup.
3. Ensure all tests pass. Compare test execution time (Quarkus tests boot faster due to build-time processing).

**Phase 8: Benchmarking and Comparison (2-3 days)**
1. Profile the Quarkus application with same methodology as Phase 1.
2. Compare:
   | Metric | Spring Boot (JVM) | Quarkus (JVM) | Quarkus (Native) |
   |--------|-------------------|---------------|------------------|
   | Startup time | X.Xs | Y.Ys | Z.Zs |
   | RSS memory | X MB | Y MB | Z MB |
   | Throughput | X req/s | Y req/s | Z req/s |
   | P99 latency | X ms | Y ms | Z ms |
   | Build time | Xs | Ys | Zs |
   | JAR/Binary size | X MB | Y MB | Z MB |
3. Document findings with analysis: why is one faster? (build-time processing vs. runtime reflection, reactive core vs. servlet, Panache vs. Spring Data optimizations).

**Phase 9: Migration Playbook (2-3 days)**
Create a comprehensive migration guide:
1. Prerequisites and compatibility matrix.
2. Step-by-step migration path per layer (data, REST, security, etc.).
3. Common pitfalls and solutions.
4. Testing strategy during migration.
5. Rollback plan.
6. Production readiness checklist:
   - [ ] All tests pass with equivalent coverage.
   - [ ] Performance meets or exceeds baseline.
   - [ ] Security audit: all endpoints protected, tokens validated.
   - [ ] Health checks working.
   - [ ] Metrics exported to Prometheus.
   - [ ] Logging parity.
   - [ ] CI/CD pipeline updated.
   - [ ] Runbook updated with new diagnostics commands.
   - [ ] Load test completed and compared.
   - [ ] Canary deployment plan.
   - [ ] Rollback procedure tested.

### Stretch Goals
1. **Native compilation**: Compile the Quarkus application to a native binary. Compare with JVM mode. Address any native-image compatibility issues (reflection config, resource config).
2. **Reverse migration**: Migrate the same application from Quarkus back to Spring Boot. Compare the two migration experiences.
3. **Strangler Fig migration**: Instead of big-bang, migrate endpoint by endpoint. Use a gateway to route traffic. Document the strangler fig process.

### Evaluation Criteria
| Criterion | Weight | Description |
|-----------|--------|-------------|
| Functional correctness | 25% | All features work identically post-migration. Test suite passes with equivalent coverage. |
| Performance comparison | 20% | Thorough, honest comparison with analysis. No cherry-picking metrics. |
| Migration completeness | 20% | All cross-cutting concerns migrated (security, caching, scheduling, events, Kafka). |
| Playbook quality | 20% | Actionable, step-by-step guide. Another engineer should be able to follow it. |
| Trade-off analysis | 15% | Identifies when Spring Boot is better, when Quarkus is better. No fanboyism. |

### Estimated Time
- **Total**: 4-6 weeks

---

## General Project Guidelines

### Setup and Submission
- Every project starts from a Git repository with a `README.md` explaining the project, prerequisites, and setup instructions.
- Use Testcontainers for all infrastructure dependencies (PostgreSQL, Kafka, Redis). No manual setup required. `docker-compose up` or embedded Testcontainers.
- All configuration is in `application.yml` (or `.properties`) with sensible defaults.
- Include a `TROUBLESHOOTING.md` with common issues and solutions.

### Code Quality Standards (All Projects)
- Immutable DTOs where possible (Java records).
- Constructor injection (no `@Autowired` on fields).
- Meaningful test names: `shouldRejectOrder_whenInventoryInsufficient()`.
- No `System.out.println` in production code — use SLF4J.
- No raw types. No `@SuppressWarnings("unchecked")` without justification.
- Exception handling: don't catch and swallow. Don't log and rethrow same exception.
- Architectural decisions documented as ADRs in `docs/adr/`.
- CI-ready: `./gradlew build` (or `./mvnw verify`) must pass with all tests.

### Evaluation Process
- Code review by a peer or instructor against the evaluation criteria.
- Live demo of key functionality.
- Discussion of trade-offs and design decisions.
- Reflection: what would you do differently next time?
