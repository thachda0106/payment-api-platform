# Architecture Decision Matrix for Spring Boot Applications

## Comparison Matrix

The following matrix evaluates 11 architectural styles across 12 dimensions relevant to Spring Boot development. Ratings are on a 1-5 scale where 1 = poor fit, 3 = adequate, 5 = excellent fit. Qualitative ratings are tagged as: Very Low / Low / Moderate / High / Very High.

| Dimension | Layered | Feature-Based | Modular Monolith | DDD Strategic | DDD Tactical | Hexagonal (Ports & Adapters) | Clean Architecture | CQRS | Event-Driven | Microservices | Vertical Slice |
|-----------|---------|---------------|------------------|---------------|--------------|------------------------------|--------------------|--------|--------------|---------------|----------------|
| **Learning Curve** | 2 (Very Low) | 2 (Very Low) | 3 (Moderate) | 4 (High) | 5 (Very High) | 3 (Moderate) | 4 (High) | 4 (High) | 4 (High) | 5 (Very High) | 2 (Very Low) |
| **Team Size Range** | 1-5 | 3-10 | 5-20 | 10-50 | 15-100+ | 5-30 | 10-50 | 5-30 | 10-50 | 20-200+ | 1-10 |
| **Maintainability** | 2 (Low) | 3 (Moderate) | 4 (High) | 4 (High) | 5 (Very High) | 4 (High) | 5 (Very High) | 3 (Moderate) | 3 (Moderate) | 2 (Low) | 3 (Moderate) |
| **Testability** | 2 (Low) | 3 (Moderate) | 3 (Moderate) | 4 (High) | 5 (Very High) | 5 (Very High) | 5 (Very High) | 4 (High) | 3 (Moderate) | 3 (Moderate) | 3 (Moderate) |
| **Scalability (Performance)** | 2 (Low) | 3 (Moderate) | 3 (Moderate) | 3 (Moderate) | 3 (Moderate) | 3 (Moderate) | 3 (Moderate) | 5 (Very High) | 4 (High) | 5 (Very High) | 3 (Moderate) |
| **Scalability (Organization)** | 1 (Very Low) | 2 (Low) | 4 (High) | 5 (Very High) | 5 (Very High) | 4 (High) | 4 (High) | 4 (High) | 4 (High) | 5 (Very High) | 2 (Low) |
| **Cognitive Load** | 2 (Low) | 2 (Low) | 3 (Moderate) | 4 (High) | 5 (Very High) | 3 (Moderate) | 4 (High) | 4 (High) | 4 (High) | 5 (Very High) | 2 (Low) |
| **Deployment Complexity** | 1 (Very Low) | 1 (Very Low) | 1 (Very Low) | 1 (Very Low) | 1 (Very Low) | 1 (Very Low) | 1 (Very Low) | 2 (Low) | 3 (Moderate) | 5 (Very High) | 1 (Very Low) |
| **Refactoring Safety** | 1 (Very Low) | 2 (Low) | 4 (High) | 4 (High) | 5 (Very High) | 4 (High) | 5 (Very High) | 3 (Moderate) | 3 (Moderate) | 2 (Low) | 2 (Low) |
| **Time to First Feature** | 5 (Very High) | 4 (High) | 3 (Moderate) | 2 (Low) | 1 (Very Low) | 2 (Low) | 1 (Very Low) | 2 (Low) | 2 (Low) | 1 (Very Low) | 5 (Very High) |
| **Spring Boot Alignment** | 5 (Very High) | 5 (Very High) | 4 (High) | 3 (Moderate) | 2 (Low) | 3 (Moderate) | 2 (Low) | 3 (Moderate) | 3 (Moderate) | 4 (High) | 5 (Very High) |
| **Best For** | Prototypes, simple CRUD, 1-2 developer projects | Small teams, moderate complexity, rapid delivery | Medium-large teams, complex domain, single deployable | Large orgs, very complex domain, multiple teams | Very complex domain model, high business logic density | Portability, testing, I/O-heavy, adapter replacement | Framework independence, strict dependency rules, long-lived | Read-heavy, different read/write models, reporting | Async workflows, loose coupling, high throughput | Autonomous teams, independent scaling, polyglot | Rapid prototyping, simple apps, proof-of-concept |
| **Worst For** | Complex domains, large teams, long-lived systems | Very large teams, independent deployment needs | Independent scaling per feature, polyglot persistence | Simple CRUD, solo projects, strict delivery deadlines | Simple business logic, rapid prototyping, small teams | Simple CRUD, small applications, rapid prototyping | Simple applications, small teams, short-lived projects | Simple CRUD with few reads, write-heavy systems | Synchronous request-response, simple workflows | Small teams, simple domains, latency-sensitive inter-communication | Complex domain, large teams, cross-cutting concerns |

---

## Architecture Style Definitions

### Layered Architecture (N-Tier)
```
Controller → Service → Repository → Database
```
Organization by technical concern. Packages: `controller/`, `service/`, `repository/`, `entity/`, `dto/`, `config/`. Each layer depends only on the layer directly below it (in theory). In practice, Spring Boot projects often skip the service layer or have controllers directly accessing repositories when "it's just CRUD."

**Spring Boot footprint**: `@RestController`, `@Service`, `@Repository`. Spring Data JPA repositories. Thin service layer that delegates to JPA. This is what `@SpringBootApplication` with a basic tutorial project gives you out of the box.

---

### Feature-Based (Package by Feature)
```
UserFeature/
├── UserController.java
├── UserService.java
├── UserRepository.java
├── UserEntity.java
└── UserDto.java

OrderFeature/
├── OrderController.java
├── OrderService.java
├── OrderRepository.java
├── OrderEntity.java
└── OrderDto.java
```
Organization by business feature rather than technical layer. Each feature package is self-contained. Cross-cutting concerns (security, logging) in `common/` or `shared/`.

**Spring Boot footprint**: Standard annotations, just organized differently. Component scanning still works. This is a pure organizational change — no framework changes needed.

---

### Modular Monolith (DDD-Lite with Bounded Contexts)
```
identity-module/              (Gradle/Maven module)
├── application/              (public API)
│   ├── UserService.java
│   ├── UserDto.java
│   └── CreateUserRequest.java
├── domain/                   (private, internal)
│   ├── User.java
│   ├── Role.java
│   └── UserRepository.java   (interface only)
└── infrastructure/           (private, internal)
    ├── JpaUserRepository.java
    └── IdentityConfig.java

payment-module/               (separate Gradle/Maven module)
├── application/
├── domain/
└── infrastructure/
```
Each bounded context is a separate build module. Modules communicate through well-defined public APIs (interfaces in the `application` package) and domain events. No direct access to another module's `domain` or `infrastructure` packages. ArchUnit enforces this at compile/test time. Deployed as a single JAR but structured as independent modules.

**Spring Boot footprint**: Gradle multi-module. Spring Modulith for module verification. `ApplicationEventPublisher` for cross-module events. `@Service` classes in each module.

---

### DDD Strategic (Domain-Driven Design — Bounded Contexts, Context Maps, Ubiquitous Language)

Focus on the strategic patterns: Bounded Contexts, Context Maps (Shared Kernel, Customer-Supplier, Conformist, Anti-Corruption Layer, Open Host Service, Published Language, Separate Ways), and Subdomains (Core, Supporting, Generic).

**Spring Boot footprint**: Can be applied to any physical architecture. The key output is the Context Map — a diagram and document showing how bounded contexts relate. Within each bounded context, you may apply DDD Tactical, Hexagonal, or even Layered architecture.

---

### DDD Tactical (Aggregates, Entities, Value Objects, Domain Events, Repositories, Factories, Domain Services)

Full tactical DDD with rich domain models. Entities have identity and lifecycle. Value Objects are immutable and equality is by value. Aggregates enforce consistency boundaries. Domain Events capture business occurrences. Repositories abstract persistence. Factories encapsulate complex creation. Domain Services contain stateless business logic that doesn't belong in any entity.

**Spring Boot footprint**: Domain classes have zero framework annotations. Repositories are interfaces in the domain, implemented in infrastructure by Spring Data JPA adapters. Domain Events published via Spring's `ApplicationEventPublisher` or a custom domain event bus. This is the richest model but has the highest learning curve and most code per feature.

---

### Hexagonal Architecture (Ports & Adapters)

```
Application (Domain + Ports)
    ↑               ↑
    |               |
Inbound           Outbound
Adapters          Adapters
(REST, GraphQL,   (PostgreSQL, Redis,
 gRPC, CLI,       Kafka, Email,
 Message Queue)   External APIs)
```

The domain is at the center. All I/O is pushed to the edges through Ports (interfaces owned by the domain) and Adapters (implementations of ports). Key rule: dependencies point inward. Nothing in the domain knows about the outside world. The domain defines what it needs (`LoadUserPort`, `SaveUserPort`) and adapters implement those interfaces.

**Spring Boot footprint**: Domain is pure Java. Ports are interfaces. Adapters use Spring stereotypes (`@RestController`, `@Repository`, `@Component`) but the domain does not. Configuration class wires adapters to ports.

---

### Clean Architecture (Uncle Bob)

```
Frameworks & Drivers (Web, DB, UI)
    ↓
Interface Adapters (Controllers, Presenters, Gateways)
    ↓
Application Business Rules (Use Cases)
    ↓
Enterprise Business Rules (Entities)
```

More prescriptive than Hexagonal. Four concentric circles with strict dependency direction inward. Use Cases orchestrate the flow of data between Entities and Gateways. Dependency Inversion Principle at every boundary.

**Spring Boot footprint**: Requires discipline. Domain is completely independent of Spring. Use cases (interactors) are plain Java classes. Spring is relegated to the outermost ring — controllers, repository implementations, configuration. Not well-aligned with Spring Boot's convention-over-configuration philosophy, which encourages framework presence throughout the codebase.

---

### CQRS (Command Query Responsibility Segregation)

```
Command Model                    Query Model
(optimized for writes)          (optimized for reads)
        |                              |
   Write Database                Read Database(s)
   (PostgreSQL Primary)          (PostgreSQL Replica,
        |                         Elasticsearch,
        |                         materialized view)
        └───────────┬──────────────────┘
                    |
              Event / CDC Sync
```

Commands mutate state, Queries return data. Separate models, separate services, potentially separate databases. Commands are validated, processed, and events published. Queries are direct reads from optimized data stores.

**Spring Boot footprint**: Separate `@Service` classes for commands and queries. `@Transactional` writes go to primary, `@Transactional(readOnly = true)` reads go to replica or read-optimized store. CDC (Debezium) or application-level events propagate changes from write model to read model.

---

### Event-Driven Architecture

```
Producer Service → [Event Bus / Kafka] → Consumer Service
                                          Consumer Service
                                          Consumer Service
```

Services communicate exclusively through events. No synchronous request-response between services. Each service owns its data. Events are the API. Loose temporal coupling — producer doesn't know or care who consumes.

**Spring Boot footprint**: Spring Kafka, Spring Cloud Stream, or RabbitMQ. `ApplicationEventPublisher` for in-process. `@KafkaListener` / `@RabbitListener` for consumers. The Transactional Outbox pattern is critical for reliability.

---

### Microservices

Independent services, each with its own database, deployed independently, communicating over the network. Each service owns a specific business capability. Team autonomy is the primary goal (not technical scalability).

**Spring Boot footprint**: Multiple Spring Boot applications. Spring Cloud for service discovery (Eureka/Consul), config server, API gateway (Spring Cloud Gateway). Resilience4j for circuit breaking. OpenFeign or WebClient for inter-service communication. Distributed tracing (Micrometer Tracing + Zipkin/Jaeger).

---

### Vertical Slice Architecture

```
OrderManagement/
├── CreateOrder/
│   ├── CreateOrderController.java
│   ├── CreateOrderHandler.java
│   ├── CreateOrderRequest.java
│   └── CreateOrderResponse.java
├── GetOrder/
│   ├── GetOrderController.java
│   ├── GetOrderHandler.java
│   └── GetOrderResponse.java
└── CancelOrder/
    ├── CancelOrderController.java
    ├── CancelOrderHandler.java
    └── CancelOrderRequest.java
```

Each feature (use case / user story) is a self-contained slice containing everything it needs: controller, handler (use case), request/response DTOs. No "service" layer shared across features. Cross-cutting concerns (validation, logging, auth) are handled by middleware/filters, not a base class. The handler is the single source of truth for what happens in that feature.

**Spring Boot footprint**: `@RestController` per slice. Handlers are plain Java classes or `@Service` beans. Can coexist with Spring Data repositories. Minimalist and pragmatic — adds structure without heavyweight patterns.

---

## Decision Tree: Choosing an Architecture

```
START
  │
  ├─ Team size = 1-3?
  │    ├─ Yes → Expected lifetime < 1 year? → Layered
  │    └─ Yes → Expected lifetime > 1 year? → Feature-Based
  │
  ├─ Team size = 4-10?
  │    ├─ Simple domain (CRUD-heavy, few business rules)?
  │    │    └─ Feature-Based
  │    ├─ Moderate domain (some business rules, validation logic)?
  │    │    └─ Feature-Based → migrate to Modular Monolith when team exceeds 10
  │    └─ Complex domain (dense business logic, regulatory compliance)?
  │         └─ Hexagonal (Ports & Adapters) within each feature
  │
  ├─ Team size = 11-40?
  │    ├─ Simple domain? → Modular Monolith (keeps deployment simple)
  │    ├─ Moderate domain? → Modular Monolith + DDD Strategic
  │    │    └─ If read/write asymmetry AND high query volume → add CQRS
  │    └─ Complex domain? → DDD Strategic + Hexagonal within each bounded context
  │
  ├─ Team size = 41-200+?
  │    ├─ Need independent deployment (teams have different release cadences)?
  │    │    └─ Microservices
  │    ├─ Need independent scaling (some features have 100x traffic of others)?
  │    │    └─ Microservices
  │    └─ Can all teams deploy together (with trunk-based development)?
  │         └─ Modular Monolith (still valid for surprisingly large orgs)
  │
  ├─ Is there a hard requirement for async, event-based communication?
  │    └─ Yes → Event-Driven on top of Modular Monolith or Microservices
  │
  └─ Is the application I/O-heavy with multiple interchangeable infrastructure technologies?
       └─ Yes → Hexagonal within whatever top-level architecture you chose
```

### Detailed Decision Criteria

**When Layered Architecture suffices:**
- Single team of 1-5 engineers.
- Application is mostly CRUD with minimal business logic.
- Expected lifetime of project is < 2 years.
- No plans to extract features into separate services.
- You're building a prototype, PoC, or internal tool.
- The team is junior — Layered is the easiest to understand and onboard.

**When to move beyond Layered:**
- You've hit 50+ service classes and can't find things quickly.
- Pull requests regularly touch files in 5+ packages (controller, service, repo, entity, dto) for a single feature change.
- Merge conflicts are frequent because everyone is editing the same `UserService.java`.
- New team members take > 1 week to understand code organization.
- You want to enforce module boundaries (person X owns module A, person Y owns module B).

---

## Migration Paths

### From Layered → Feature-Based
**Trigger**: Team exceeds 5 engineers, or you can't see all files for a feature at once.
**Process**:
1. Identify features (User, Order, Payment, Product, etc.).
2. Create feature packages.
3. Move controller, service, repository, entity, DTO for each feature into its package.
4. Move truly shared code to `common/` or `shared/`.
5. Run all tests. Fix imports.
6. Enforce with ArchUnit: no feature package may import from another feature package's `service` or `repository` directly. Use shared `application` interfaces.

**Risk**: Low. Pure refactoring — no behavior change.

---

### From Feature-Based → Modular Monolith
**Trigger**: Team exceeds 10, or different features have different release cadences, or you need to enforce stronger boundaries.
**Process**:
1. Create Gradle multi-module structure.
2. Extract shared kernel: domain primitives, common utilities, shared interfaces.
3. Move each feature into its own module with `api` (public) and `impl` (internal) sub-packages.
4. Replace direct cross-feature calls with interfaces (ports) defined in each feature's API.
5. Use `ApplicationEventPublisher` for cross-feature notifications where eventual consistency is acceptable.
6. Enforce boundaries with ArchUnit module tests.
7. Publish to CI — verify tests pass. Deploy.

**Risk**: Medium. Requires discipline to define and maintain module APIs.

---

### From Modular Monolith → Microservices
**Trigger**: Teams need independent deploy, independent scale, or different technology choices for different bounded contexts.
**Process**:
1. Identify the bounded context(s) that benefit most from extraction (most churn, different scaling profile, separate team ownership).
2. Extract the module into a separate Spring Boot application with its own database.
3. Replace in-process event publishing with Kafka (Transactional Outbox pattern).
4. Replace synchronous calls with async events where possible. Use resilience patterns for remaining synchronous calls.
5. Iterate: extract one bounded context at a time. Prove it works before extracting the next.

**Risk**: High. Distributed systems are fundamentally different. Network failures, partial failures, eventual consistency, distributed tracing, and operational complexity all increase dramatically.

---

### From Modular Monolith → DDD Strategic
**Trigger**: Business domain is deeply complex. Multiple subdomains that were previously lumped together need explicit boundaries and distinct ubiquitous languages.
**Process**:
1. Conduct EventStorming or domain storytelling workshops with domain experts.
2. Identify bounded contexts (contexts where a term means the same thing).
3. Map bounded contexts to existing modules. Merge or split modules as needed.
4. Define Context Map: which contexts are upstream/downstream? Which are conformist? Where are anti-corruption layers needed?
5. Within each bounded context, apply Hexagonal or Full DDD Tactical as appropriate.

**Risk**: Medium. The hardest part is the organizational conversation to agree on bounded context boundaries.

---

### From DDD Strategic → DDD Tactical
**Trigger**: A bounded context contains very dense business logic where a rich domain model provides clear value (e.g., pricing engine, fraud detection, inventory allocation).
**Process**:
1. Within the bounded context's module, replace anemic entities with rich domain aggregates.
2. Extract value objects from primitives.
3. Define domain events for significant state changes.
4. Move business rules from services to domain objects.

**Risk**: Medium. Not every bounded context needs tactical DDD. Only apply to Core subdomains.

---

### From Any Architecture → Hexagonal
**Trigger**: Need to swap out infrastructure components without touching domain logic (e.g., change database, replace message broker, add a new API format).
**Process**:
1. Define ports (interfaces) for each external dependency.
2. Move existing implementations into adapter classes that implement those ports.
3. Inject adapters via constructor injection.
4. Write tests that use in-memory adapters for ports — much faster.

**Risk**: Medium. Adds indirection. Worth it if you've actually swapped infrastructure twice or expect to.

---

### From Any Architecture → Event-Driven
**Trigger**: Need loose coupling between features, or need to fan out to multiple consumers for the same event (e.g., "order placed" triggers email, inventory deduction, analytics event, fraud check).
**Process**:
1. Identify synchronous calls that don't need an immediate response.
2. Replace with events: producer publishes event, consumers subscribe.
3. Implement Transactional Outbox for reliable publishing.
4. Ensure consumers are idempotent (they may receive the same event more than once).

**Risk**: Medium-High. Eventual consistency changes the user experience and error handling model.

---

## Anti-Patterns Per Architecture

### Layered Architecture Anti-Patterns
1. **Skip-the-service-layer**: Controller directly calls repository. "It's just a simple CRUD." Six months later, the same validation logic is copied across three controllers.
2. **Layer leakage**: Repository called from controller, business logic in controller, SQL in service. The layers exist only in package names — all logic is in the wrong place.
3. **God Service**: `UserService` is 5000 lines because it handles user CRUD, authentication, profile management, preferences, notifications, and admin operations. The service layer is a dumping ground.
4. **Shared Entity**: The same `UserEntity` is used for registration (needs 3 fields), profile display (needs 10 fields), admin (needs 20 fields), and reporting (needs 5 fields with joins). Every query selects all columns.

### Feature-Based Anti-Patterns
1. **Feature as dump**: The feature package contains everything — all services, all repositories, all entities — with no internal structure. A feature package with 50 files is just the layered architecture with a different top-level directory.
2. **Feature cross-coupling**: `OrderFeature/OrderService` imports `UserFeature/UserService` directly. Over time, every feature depends on every other feature. Rename to "Spaghetti by Feature."
3. **Fake feature isolation**: Feature packages exist in name only. A change to the User feature requires changes in Order, Payment, and Notification features because they all access internal User classes.

### Modular Monolith Anti-Patterns
1. **Distributed monolith disguised as modules**: Each module is a separate build artifact, but they share a database with tight coupling (foreign keys across modules). You can't deploy independently, but you pay the module complexity tax anyway.
2. **Module enums for everything**: Creating a module for every noun in the system — `address-module`, `phone-number-module`, `currency-module`. The overhead of managing 47 module APIs outweighs any benefit.
3. **Application module as god class**: The application/wiring module contains all the business logic and orchestration. Modules are just data access objects with a thin domain layer. The "modular" part is a facade.

### DDD Anti-Patterns
1. **DDD everywhere**: Applying full tactical DDD to a simple CRUD application. Every entity is an aggregate, every string is a value object, every state change is a domain event. 10x the code for no benefit. "DDD is for the core domain. CRUD is for everything else."
2. **Anemic domain model with DDD naming**: `Order` is still a bag of getters/setters, but now it's called an "Aggregate" and the service is called a "Domain Service." The names changed but the model didn't.
3. **Entity-as-database-row**: Every database table gets an entity. Every entity has an ID field. No value objects, no domain logic, no business rules. It's a 1:1 ORM mapping with DDD vocabulary.
4. **Missing ubiquitous language**: Developers use technical terms while domain experts use business terms. The code says `setStatus(OrderStatus.CANCELLED)` but the business says "voided" and "reversed" for different cancellation scenarios. The model doesn't capture the distinction.

### Hexagonal / Clean Architecture Anti-Patterns
1. **Port explosion**: Every repository method gets its own port interface. A port per stored procedure parameter combination. 200 port interfaces for a CRUD app.
2. **Mapping hell**: Domain → DTO → Entity → Domain with 4 layers of mapping. "Mapping is not business logic. It's plumbing. Minimize it."
3. **The framework is still everywhere**: Hexagonal packages exist, but `@Autowired` is in domain objects, `@Entity` annotations are on domain classes, and the service imports `HttpServletRequest`. It's layered with extra package directories.
4. **"We might need it" ports**: Creating outbound ports for every possible future infrastructure change. "What if we switch from PostgreSQL to MongoDB? From Kafka to RabbitMQ?" If the switch hasn't happened in 3 years, the ports are dead code.

### CQRS Anti-Patterns
1. **CQRS for everything**: Applying command/query separation to every model in the system, including simple configuration data that changes once per quarter. The cognitive overhead exceeds the performance benefit.
2. **Stale reads without stale-read handling**: The read model is eventually consistent, but the UI treats it as strongly consistent. Users see "order not found" right after placing an order because the projection hasn't caught up.
3. **Synchronous CQRS**: Commands write, then synchronously wait for the read model to update before returning. This is just the standard architecture with extra steps.
4. **One database, one model, "CQRS" in naming**: `OrderCommandService` and `OrderQueryService` both use the same `Order` entity with the same `OrderRepository` on the same database. This is method naming convention, not CQRS.

### Event-Driven Anti-Patterns
1. **Event without schema**: Producers change event structure without versioning. Consumers break silently. No schema registry, no compatibility checks.
2. **Request-response over events**: Service A publishes `RequestPaymentEvent`, Service B processes it and publishes `PaymentResponseEvent`, Service A subscribes to the response. This is RPC over Kafka with extra latency, serialization, and failure modes.
3. **Missing idempotency**: Consumer receives duplicate events (Kafka at-least-once delivery) but doesn't check idempotency. Payment is processed twice. Ledger is corrupted.
4. **Event as internal implementation detail**: Events contain JPA entities, internal status codes, and database IDs. The event becomes a remote API into internal state. Consumers couple to producer internals.

### Microservices Anti-Patterns
1. **Distributed monolith**: Services are independently deployable in theory, but a change to any service requires coordinated deployment of 5 other services. Latency, complexity, and operational overhead of microservices with the coupling of a monolith.
2. **Entity services**: One service per database entity (UserService, OrderService, ProductService). The services are so granular that every business operation spans 4+ services. Each service is an anemic data access layer with an HTTP interface.
3. **Shared database**: Multiple services read/write to the same database. Service A's schema change breaks Service B's queries. No independent deployment.
4. **Microservices for a 3-person team**: The operational overhead of CI/CD pipelines, monitoring, distributed tracing, and coordination for 12 services is higher than the value those services provide. The team spends more time on infrastructure than features.
5. **No resilience patterns**: Services call other services synchronously without circuit breakers, timeouts, or retries. A slow downstream causes thread pool exhaustion cascade across the entire system.

### Vertical Slice Anti-Patterns
1. **Duplicated business logic**: The same validation rule for "order amount must be > 0" is implemented in CreateOrder, UpdateOrder, and CancelOrder slices. When the rule changes (e.g., minimum order is now $1), three places must be updated.
2. **Slice as mini-layered**: The slice contains a controller, service, and repository just like the layered architecture. The slice is just a renamed package — same coupling, same problems.
3. **Cross-slice chaos**: Slices that need to coordinate (e.g., "CreateOrder" needs user validation from UserFeature) either duplicate the validation or have ad-hoc dependencies that create spaghetti.

---

## Architecture Styles That Fight Spring Boot

Some architectural patterns conflict with Spring's conventions and design philosophy. Choose these only when their benefits clearly outweigh the friction:

### Hexagonal / Clean Architecture vs. Spring Boot

**Conflict**: Spring Boot is opinionated. It expects `@Service`, `@Repository`, `@Autowired`, and component scanning throughout the codebase. Hexagonal/Clean Architecture insists that the domain knows nothing about the framework.

**Specific friction points:**
- Spring Data JPA — its magic comes from interfaces that Spring proxies at runtime. In Clean Architecture, these interfaces should be in the outer ring, but the convenience of `findByEmail(String email)` without implementation is very hard to give up.
- `@Transactional` — declarative transaction management requires Spring AOP, which requires Spring-managed beans. If your domain services are pure Java (not Spring beans), you can't use `@Transactional` and must manage transactions manually.
- `@EventListener` / `ApplicationEventPublisher` — the simplest in-process event mechanism requires Spring context.
- `@Cacheable` — same issue as `@Transactional`.
- `@Scheduled` — same issue.

**Mitigation strategies:**
- Accept a moderate level of framework coupling in infrastructure/adapter layers. The domain core stays pure.
- Use Spring's `@Configuration` classes as the wiring layer — domain objects are instantiated by `@Bean` methods, not by `@Component` scanning.
- Create thin Spring wrappers around pure domain services to add `@Transactional`: `@Service class TransactionalUserService extends UserService { ... }`.

### DDD Tactical (Full) vs. Spring Boot

**Conflict**: Full DDD Tactical requires rich domain models with zero infrastructure concerns. Spring Boot's productivity features (JPA auto-configuration, repository magic, declarative transactions) all assume your domain objects are Spring-managed and JPA-annotated.

**Specific friction points:**
- JPA `@Entity` requires a no-arg constructor (often `protected`). Pure DDD entities should enforce invariants in constructors, not allow creation in an invalid state.
- JPA `@OneToMany` with lazy loading creates a tight coupling between domain model and persistence mechanism. `LazyInitializationException` is the result.
- Aggregate roots with 50 fields where only 3 are needed for a specific use case — JPA loads all columns by default, breaking performance.
- Domain events need to be dispatched after persistence, which requires integration with the ORM lifecycle (`@PostPersist`, `@PostUpdate` callbacks).

### Microservices Prematurely

**Conflict**: Spring Boot makes creating a new service trivially easy. `start.spring.io` → download → import → run. The low barrier to entry encourages microservices before they're justified.

**The real cost of a microservice:**
- CI/CD pipeline (build, test, deploy, rollback).
- Container orchestration (Kubernetes manifest, resource limits, health probes).
- Monitoring and alerting (one more service to monitor).
- Distributed tracing (must propagate trace context across service boundaries).
- Schema management (database per service, no foreign keys between services).
- API versioning and backward compatibility.
- Integration testing environment.
- On-call runbook.

Each service adds operational overhead. At 3-5 services, it's manageable. At 50+, it's a platform in itself.

---

## Recommended Starting Points

### Solo / Small Team (1-3 engineers), Simple Domain
**Start: Layered**
```
src/main/java/com/example/
├── controller/
├── service/
├── repository/
├── entity/
└── dto/
```
**Why**: Minimal overhead. Every Spring Boot tutorial uses this. Fastest time to first feature. IDE support is excellent.

**When to change**: At ~5 engineers OR when a single service class exceeds 500 lines, migrate to Feature-Based. At ~10 engineers, consider Modular Monolith.

---

### Small Team (3-7 engineers), Moderate Domain
**Start: Feature-Based**
```
src/main/java/com/example/feature/
├── user/
│   ├── UserController.java
│   ├── UserService.java
│   └── UserRepository.java
├── order/
└── product/
```
**Why**: Better code organization without adding architecture complexity. Features are co-located. Onboarding is straightforward.

**When to change**: At ~10 engineers or when cross-feature coupling becomes problematic, migrate to Modular Monolith.

---

### Medium Team (8-20 engineers), Complex Domain
**Start: Modular Monolith**
```
modules/
├── shared-kernel/
├── identity-module/
├── catalog-module/
├── ordering-module/
├── payment-module/
└── application/               (wiring)
```
**Why**: Strong boundaries without operational complexity of microservices. Teams can work on different modules with minimal merge conflicts. Architecture enforced by build tools (Gradle module dependencies) and ArchUnit.

**When to change**: At ~20+ engineers with 3+ independent teams, add DDD Strategic to formalize bounded context boundaries. If teams need independent deploy → extract as microservices (one at a time).

---

### Large Team (20-50+ engineers), Very Complex Domain
**Start: DDD Strategic + Hexagonal within each Bounded Context**
```
Context Map (document):
  Identity → Customer-Supplier → Order
  Order → Customer-Supplier → Payment
  Payment → Conformist → Gateway (external)

Within each Bounded Context:
  modules/<bounded-context>/
  ├── application/
  │   ├── port/inbound/        (use cases)
  │   ├── port/outbound/       (repository interfaces, etc.)
  │   └── domain/              (pure domain model)
  ├── adapter/
  │   ├── inbound/web/
  │   ├── outbound/persistence/
  │   └── outbound/messaging/
  └── config/
```
**Why**: DDD Strategic handles organizational boundaries (Conway's Law). Hexagonal provides technical isolation for testing and infrastructure flexibility. Each bounded context can independently choose whether to apply DDD Tactical (rich domain model) or stay simpler.

**When to change**: If a bounded context needs to scale independently or its team needs independent deployment, extract it as a microservice. If event-driven communication is needed across bounded contexts, add Event-Driven architecture.

---

### Very Large Organization (50-200+ engineers)
**Architecture: DDD Strategic + Microservices + Event-Driven + CQRS**
- **DDD Strategic**: Bounded contexts map to teams. Context Map defines integration patterns.
- **Microservices**: One service per bounded context (or 2-3 microservices per bounded context for very granular scaling).
- **Event-Driven**: Services communicate via events (Kafka). No synchronous coupling between bounded contexts.
- **CQRS**: Applied to bounded contexts with read/write asymmetry (e.g., reporting, search).

---

## How to Apply Multiple Styles (Mixed Architecture)

Most real-world systems are not a single architecture style. They're a pragmatic mix.

### Pattern: DDD Bounded Contexts + Hexagonal Within Each Context

```
bounded-context-identity/
├── application/
│   ├── port/inbound/
│   ├── port/outbound/
│   └── domain/          ← DDD Tactical (aggregates, value objects)
├── adapter/
│   ├── inbound/web/
│   ├── outbound/persistence/   ← Hexagonal adapter
│   └── outbound/messaging/
└── config/

bounded-context-catalog/
├── application/
│   ├── port/inbound/
│   ├── port/outbound/
│   └── domain/          ← Simpler, CRUD-ish (no need for full DDD)
├── adapter/
│   ├── inbound/web/
│   └── outbound/persistence/
└── config/
```
**Decision rule**: Apply DDD Tactical only to Core subdomains (where business logic is dense and differentiating). Supporting and Generic subdomains use Hexagonal or even Feature-Based internally. The bounded context boundary is what matters — how you structure inside is contextual.

### Pattern: Modular Monolith with Event-Driven Internal Communication

All bounded contexts in one process. Cross-context communication via domain events (ApplicationEventPublisher). But when you extract a bounded context as a microservice, the event-driven communication pattern is already established — "just" switch the transport from in-process to Kafka.

### Pattern: CQRS Only for Specific Bounded Contexts

Don't apply CQRS globally. Apply it only to bounded contexts where:
- Read volume is 10x+ write volume.
- Read models differ significantly from write models.
- You need different query capabilities (full-text search, aggregations) that the write database doesn't provide well.

For simple bounded contexts, use the same model for reads and writes.

### Pattern: Microservices + Modular Monolith Backend

Some organizations run microservices for customer-facing features (frequent change, independent scaling) but keep administrative/back-office functions in a modular monolith (less frequent change, smaller scale, shared operational data). This is pragmatic and common in practice.

---

## When NOT to Use Each Architecture

This is the most important column in the decision matrix. Choosing the wrong architecture is more damaging than choosing a suboptimal one — actively bad architectures create more problems than they solve.

| Architecture | When NOT to Use |
|-------------|----------------|
| **Layered** | When the project will outlive the original team. When business logic is non-trivial. When you have more than 5 engineers. When you need to enforce boundaries between features. |
| **Feature-Based** | When features have complex interdependencies that need formal interfaces. When teams need independent deployment. When the application outgrows single-team ownership. |
| **Modular Monolith** | When you genuinely need independent scaling (one module handles 1000x the traffic of others). When teams are geographically distributed and need to deploy independently. When you need polyglot persistence (different databases for different modules, justified). |
| **DDD Strategic** | When your domain is simple CRUD. When you don't have access to domain experts. When the cost of event storming and context mapping workshops exceeds the benefit of clearer boundaries. When the system is a technical service (API gateway, monitoring tool), not a business domain. |
| **DDD Tactical** | When business logic is simple. When entities have > 20 fields but only 3 are used in any given operation (too much code for too little logic). When the team is not experienced with DDD. When delivery deadlines are tight and the domain model can evolve later. |
| **Hexagonal / Ports & Adapters** | When the application has only one infrastructure provider and no foreseeable need to change. When the application is small (< 20 endpoints). When the team is junior and the indirection adds confusion without benefit. When time to market is critical for the first version. |
| **Clean Architecture** | When you're using Spring Boot as a full-stack framework (not just DI). When you value Spring Boot's autoconfiguration and conventions. When you want minimal boilerplate. When the team expects "standard Spring Boot" patterns. |
| **CQRS** | When reads and writes are symmetric (same data, same frequency). When eventual consistency on reads would break user experience. When the system is write-heavy (reads are trivial). When the team is small and the complexity of dual models is not justified. When you only have one database. |
| **Event-Driven** | When operations must be synchronous and immediate. When the domain is simple CRUD. When you don't have a messaging infrastructure. When eventual consistency is unacceptable for correctness (financial ledger with real-time balance). When debugging complexity of async flows outweighs the decoupling benefit. |
| **Microservices** | When you have fewer than 3 teams. When the domain is simple. When you cannot handle operational complexity (monitoring 20 services, distributed tracing, CI/CD per service). When inter-service latency is critical. When you don't have strong DevOps/platform engineering support. When you're building the first version of a product. |
| **Vertical Slice** | When cross-cutting concerns dominate (lots of shared validation, shared business rules). When you need formal boundaries between teams. When the application has > 50 use cases that share significant logic. When you need to reuse logic across features extensively. |

---

## Summary: One-Sentence Guidance

1. **Layered Architecture**: Start here for solo projects and prototypes; move on when the codebase outgrows a single developer's mental model.
2. **Feature-Based**: The sweet spot for small teams that want better code organization without adding architecture complexity.
3. **Modular Monolith**: The best default architecture for teams of 5-20 that need strong boundaries but not the operational complexity of microservices.
4. **DDD Strategic**: Apply when your business domain is complex enough that aligning code boundaries with organizational boundaries becomes the primary technical challenge.
5. **DDD Tactical**: Only apply to Core subdomains where dense business logic justifies the modeling investment — CRUD is fine for everything else.
6. **Hexagonal (Ports & Adapters)** : Use when test speed matters (pure domain tests without Spring context) or when you genuinely need to swap infrastructure, not just "in case" you might someday.
7. **Clean Architecture**: Choose only if you need framework independence above all else, and accept that you'll write significantly more code per feature than standard Spring Boot.
8. **CQRS**: Apply surgically to bounded contexts with 10x+ read/write asymmetry; never apply globally to an entire system.
9. **Event-Driven**: Use when loose temporal coupling between features is the goal, and you're willing to invest in idempotency, schema evolution, and async debugging.
10. **Microservices**: Evolve into these one bounded context at a time as team autonomy, independent scaling, or independent deployment become genuine business constraints — never start here for a new product.
11. **Vertical Slice**: Use for rapid prototyping and simple applications where the overhead of abstraction layers costs more than the duplication of a few business rules.

### The Meta-Principle

> **Start simple, add structure only when the current structure causes more pain than the new structure would add. Every architectural boundary has a cost. Pay it only when the problem demands it.**
