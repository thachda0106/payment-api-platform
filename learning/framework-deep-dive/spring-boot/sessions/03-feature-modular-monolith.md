# Session 03: Feature-Based & Modular Monolith Architecture

## 1. Why This Topic Exists

Layered architecture organizes code by **technical concern** (controllers, services, repositories). Feature-based architecture organizes code by **business capability** (users, orders, payments). The shift from technical to business organization is the single most impactful architecture decision for teams growing from 5 to 20 engineers.

The modular monolith is the natural evolution: enforce feature boundaries at the build tool level (Gradle/Maven modules), not just the package level. This gives you **compile-time dependency enforcement** without the operational complexity of microservices.

**Staff engineer insight**: The modular monolith is the most underrated architecture pattern. Companies like Shopify, GitHub, and Basecamp operated modular monoliths at massive scale before extracting services. The pattern delivers 90% of microservices benefits at 10% of the cost.

## 2. Mental Model

```
FEATURE-BASED (package level):
├── users/          ← Everything about users
├── orders/         ← Everything about orders
└── payments/       ← Everything about payments
     Convention: Don't import from other features
     Enforcement: Code review, ArchUnit

MODULAR MONOLITH (build tool level):
├── :users          ← Gradle module: users
├── :orders         ← Gradle module: orders
└── :payments       ← Gradle module: payments
     Convention: Doesn't matter
     Enforcement: Compiler error if you try
```

The difference: one is a **convention**, the other is a **compiler-enforced contract**.

## 3. Internal Architecture

### Feature-Based Package Structure

```
src/main/java/com/example/
├── shared/                          ← What IS shared
│   ├── audit/
│   │   └── Auditable.java
│   ├── events/
│   │   ├── DomainEvent.java
│   │   └── DomainEventPublisher.java
│   ├── id/
│   │   └── EntityId.java
│   └── result/
│       └── Result.java              ← Either<Error, Success>
│
├── user/
│   ├── UserController.java          ← @RestController
│   ├── UserService.java             ← @Service
│   ├── UserRepository.java          ← @Repository
│   ├── User.java                    ← @Entity + Domain
│   ├── UserDto.java                 ← DTO
│   ├── UserMapper.java              ← DTO ↔ Domain
│   ├── UserValidator.java           ← Validation logic
│   └── UserTest.java
│
├── order/
│   ├── OrderController.java
│   ├── OrderService.java
│   ├── OrderRepository.java
│   ├── Order.java
│   ├── OrderItem.java
│   ├── OrderStatus.java             ← Enum
│   ├── OrderDto.java
│   ├── OrderMapper.java
│   ├── CreateOrderService.java      ← Use-case specific
│   ├── CancelOrderService.java
│   ├── FulfillOrderService.java
│   └── OrderTest.java
│
├── payment/
│   ├── PaymentController.java
│   ├── PaymentService.java
│   ├── PaymentRepository.java
│   ├── Payment.java
│   ├── PaymentDto.java
│   ├── PaymentGateway.java          ← External API adapter
│   └── PaymentTest.java
│
└── notification/
    ├── NotificationService.java     ← @Service + @Async
    ├── EmailAdapter.java
    ├── SmsAdapter.java
    └── NotificationTest.java
```

### Feature Communication Rules

```
┌──────────┐     DomainEvent      ┌──────────┐
│  ORDER   │ ────OrderPlaced─────▶ │ PAYMENT  │
│          │                       │          │
│          │ ◀──PaymentConfirmed── │          │
└──────────┘     DomainEvent      └──────────┘
      │                                   │
      │  ORDER CANNOT directly call       │
      │  PaymentService.process()         │
      │                                   │
      ▼                                   ▼
┌──────────────────────────────────────────┐
│              EVENT BUS                    │
│    (Spring ApplicationEventPublisher)     │
└──────────────────────────────────────────┘
```

Features communicate through **domain events**, not direct method calls. This prevents feature coupling.

### Modular Monolith (Gradle Multi-Module)

```
settings.gradle:
    include ':shared'
    include ':user'
    include ':order'
    include ':payment'

user/build.gradle:
    dependencies {
        implementation project(':shared')
        // CANNOT: implementation project(':order')
        // CANNOT: implementation project(':payment')
    }

order/build.gradle:
    dependencies {
        implementation project(':shared')
        // CANNOT: implementation project(':user')
        // CANNOT: implementation project(':payment')
        // Can depend on user-api IF you define an api module:
        // implementation project(':user-api')  ← interfaces only
    }
```

### API Module Pattern

```
user/
├── user-api/                        ← PUBLIC: interfaces, DTOs, events
│   └── src/main/java/com/example/user/api/
│       ├── UserPublicApi.java       ← Interface that core implements
│       ├── UserDto.java             ← Public DTO
│       └── UserRegisteredEvent.java ← Domain event schema
│
├── user-core/                       ← PRIVATE: implementation
│   └── src/main/java/com/example/user/core/
│       ├── UserService.java         ← Implements UserPublicApi
│       ├── User.java                ← Domain entity
│       └── UserRepository.java
│
└── user-web/                        ← ADAPTER: REST controllers
    └── src/main/java/com/example/user/web/
        └── UserController.java
```

**Dependency rules enforced by Gradle**:
- `user-api` depends on `shared` only
- `user-core` depends on `user-api` and `shared`
- `user-web` depends on `user-core`, `user-api`, `shared`
- `order-core` depends on `user-api` (interfaces) but NOT `user-core` (implementation)
- `order-core` CANNOT depend on `user-core` (compile error)

## 4. Runtime Behavior

### Event-Based Communication Between Features

```java
// In order/ package
@Service
public class OrderService {
    private final ApplicationEventPublisher events;

    @Transactional
    public Order createOrder(CreateOrderCommand cmd) {
        Order order = new Order(cmd);
        orderRepository.save(order);
        events.publishEvent(new OrderPlacedEvent(order.getId(), order.getTotal()));
        // Payment feature listens for this event
        return order;
    }
}

// In payment/ package
@Component
public class PaymentEventListener {
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderPlaced(OrderPlacedEvent event) {
        // Process payment in a SEPARATE transaction
        // If this fails, the order creation transaction has already committed
        // This is the trade-off: eventual consistency
        paymentService.initiatePayment(event.getOrderId(), event.getAmount());
    }
}
```

### Transaction Isolation Between Features

```
Request Thread: http-nio-8080-exec-5

[OrderService.createOrder()]
    @Transactional(TX-1)
    ├── orderRepository.save(order)
    └── events.publishEvent(OrderPlacedEvent)
        
[PaymentEventListener.onOrderPlaced()]   ← Same thread by default
    @Transactional(TX-2, REQUIRES_NEW)   ← Suspends TX-1, creates TX-2
    ├── paymentRepository.save(payment)
    └── paymentGateway.charge()
        │
        ├── SUCCESS → TX-2 commits → TX-1 resumes → TX-1 commits
        │              Order = CREATED, Payment = COMPLETED
        │
        └── FAILURE → TX-2 rolls back → exception thrown → TX-1 rolls back
                       Order NOT saved (undesirable? depends on requirements)
```

If you want order to persist even if payment fails, use `@Async` on the event listener:
```
Thread: http-nio-8080-exec-5           Thread: task-1
[OrderService.createOrder()]           [PaymentEventListener.onOrderPlaced()]
    TX-1: save order                       TX-2: initiate payment
    TX-1: commit ✓                          TX-2: fails ✗
    return order                             (order already persisted)
```

Trade-off: eventual consistency. The order exists without payment. The system must handle this intermediate state.

## 5. Request Flow Diagrams

### Feature-Based (Synchronous via Shared Public API)

```
[Client] ──POST /orders──▶ [order/OrderController]
                               │
                               ▼
                          [order/OrderService]
                               │ Needs user data for validation
                               │ Calls user/UserPublicApi.getUser(userId)
                               │         ↑ Only depends on user-api interface
                               │ Implementation provided by user-core
                               │
                               │ Needs to validate inventory
                               │ Calls inventory/InventoryPublicApi.checkStock(productId, qty)
                               │
                               ▼
                          [order/OrderRepository.save()]
                               ▼
                            [Client] ← 201 Created
```

### Modular Monolith (Asynchronous via Events)

```
[Client] ──POST /orders──▶ [order-web/OrderController]
                               │
                               ▼
                          [order-core/OrderService]
                               │ Saves order
                               │ Publishes OrderPlacedEvent
                               ▼
                          [Spring ApplicationEventMulticaster]
                               │
                    ┌──────────┼──────────┬──────────┐
                    ▼          ▼          ▼          ▼
              [payment]   [inventory]  [notification]  [analytics]
              .onOrder    .reserve     .sendConfirm    .track
              Placed()    Stock()      Email()         Order()
```

## 6. Lifecycle Diagrams

### Feature Extraction: Monolith → Module

```
Phase 1: Package by feature
Phase 2: Define public interfaces in feature/.api subpackage
Phase 3: Move to Gradle module with api/core split
Phase 4: Enforce dependencies via Gradle
Phase 5: (Optional) Extract module to separate service
```

### Team Ownership Evolution

```
Week 1-12:
    All engineers → All packages
    (no ownership boundaries)

Month 3-6:
    Team A → users/
    Team B → orders/
    Team C → payments/
    shared/ → requires 2+ approvals

Month 6-12:
    Team A → :users module (Gradle)
    Team B → :orders module (Gradle)
    Team C → :payments module (Gradle)
    :shared → Architecture review required

Year 2+:
    Team A → User Service (separate deployable)
    Team B → Order Service
    Team C → Payment Service
```

## 7. Source Code Reading Guide

For modular monoliths in Spring Boot:

1. **Spring Modulith** (official Spring project): `https://github.com/spring-projects/spring-modulith`
   - `ModuleTest`: Verifies module structure constraints
   - `ApplicationModuleListener`: Async, transactional event handling between modules
   - `Moments`: Time-based event publication for temporal coupling

2. **Spring ApplicationEventMulticaster**
   - `multicastEvent()`: How events are dispatched to listeners
   - `SimpleApplicationEventMulticaster`: Default synchronous implementation
   - How to configure with a `TaskExecutor` for async event handling

## 8. Production Failure Scenarios

### Scenario 1: Feature Coupling Ignored

**Symptom**: Changing `User.java` field name breaks the `order` module at compile time.

**Root cause**: `order` module imported `user-core` instead of `user-api`. The `User.java` entity leaked across module boundaries.

**Resolution**: Create `user-api` module with only interfaces and DTOs. `order` depends on `user-api`, not `user-core`.

### Scenario 2: Event Listener Failures Silent

**Symptom**: Payment is never processed for some orders. No errors in logs.

**Root cause**: `@EventListener` exception is swallowed by default (Spring's `SimpleApplicationEventMulticaster` behavior). The payment listener threw an exception but `OrderService.createOrder()` returned 201 anyway.

**Detection**: Metric: `orders_placed_total - payments_initiated_total` grows over time.

**Resolution**: 
```java
@Bean
public ApplicationEventMulticaster applicationEventMulticaster() {
    SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
    multicaster.setErrorHandler(t -> log.error("Event listener failed", t));
    return multicaster;
}
```
Better: Use `@TransactionalEventListener` with proper error handling and retry.

### Scenario 3: Gradle Module Explosion

**Symptom**: Build takes 15 minutes. 80 Gradle modules. Most modules contain 3 classes.

**Root cause**: Premature microservice mindset applied to modules. Every tiny concept becomes a module.

**Resolution**: Consolidate. 5-10 modules is the sweet spot for a monolithic deployable. If you have more, you probably need separate services (or you're over-engineering).

## 9. Debugging Techniques

### Verifying Module Boundaries

```java
// Spring Modulith verification
@Test
void verifyModularStructure() {
    ApplicationModules.of(Application.class).verify();
    // Fails if any module violates its declared dependencies
}
```

### Verifying Feature Isolation with ArchUnit

```java
@Test
void featuresShouldNotDependOnEachOther() {
    slices().matching("com.example.(*)..")
        .should().notDependOnEachOther()
        .ignoreDependency(AlwaysTrue.class, "com.example.shared..")
        .check(classes);
}
```

## 10. Observability Considerations

Event-based communication between features creates tracing challenges:

```java
// In order module
events.publishEvent(new OrderPlacedEvent(orderId));
// The trace context from the HTTP request is in ThreadLocal (MDC).
// If listener runs async, ThreadLocal is lost.

// Solution: Explicit context propagation
@Component
public class PaymentEventListener {
    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        MDC.put("traceId", event.getTraceId());
        MDC.put("orderId", event.getOrderId().toString());
        try {
            paymentService.initiatePayment(event);
        } finally {
            MDC.clear();
        }
    }
}
```

## 11. Performance Implications

| Architecture | Startup Time | Runtime Overhead | Build Time |
|-------------|-------------|-----------------|------------|
| Layered (single module) | 3s | 0 | 10s |
| Feature-based (single module) | 3s | 0 | 10s |
| Modular monolith (5 modules) | 3s | 0 | 20s |
| Modular monolith (20 modules) | 3s | 0 | 60s |
| Microservices (5 services) | 3s × 5 = 15s | Network overhead | 10s × 5 = 50s |

The modular monolith has zero runtime overhead vs layered architecture. All communication is in-process method calls. You pay only at build time (Gradle module resolution) and team coordination time.

## 12. Architecture Implications

### When to Use Feature-Based
- 5-15 engineers
- Multiple business domains
- Single deployment unit is acceptable
- Team boundaries are forming but still fluid

### When to Use Modular Monolith
- 8-30 engineers
- Clear team boundaries exist
- Want independent deployability later
- Want compile-time enforcement of boundaries
- Not ready for microservices operational complexity

### When NOT to Use Either
- 1-3 engineers (layered is fine)
- Need independent scaling now (go microservices)
- Need polyglot persistence (different DB per module → microservices)

## 13. Team Ownership Implications

| Pattern | Team A Owns | Team B Owns | Shared |
|---------|-------------|-------------|--------|
| Feature-based | `users/` package | `orders/` package | `shared/` (review required) |
| Modular monolith | `:users` module | `:orders` module | `:shared` module (arch review) |
| Microservices | User Service repo | Order Service repo | API contracts (versioned) |

## 14. Interview Questions

1. **"Why would you choose a modular monolith over microservices?"**
   - **Answer**: Same team velocity benefits (independent work) at a fraction of the operational cost. No network latency, no distributed transactions, no service discovery, no circuit breakers, one database to manage. If you have 15 engineers and don't need independent scaling, modular monolith is the optimal choice. Extract services later if needed — the module boundaries make extraction straightforward.

2. **"How do you prevent teams from bypassing module boundaries in a modular monolith?"**
   - **Answer**: Compile-time enforcement via Gradle/Maven module dependencies. Team A's module simply cannot compile against Team B's internal classes. This is stronger than code review conventions because it's enforced by the compiler. Supplement with ArchUnit tests and CI checks.

3. **"What's the difference between a modular monolith and microservices?"**
   - **Answer**: Deployment unit count. Modular monolith = 1 deployable, microservices = N deployables. Everything else (boundaries, ownership, APIs, domain events) can be identical. The modular monolith has all the architectural benefits of microservices without the operational complexity. It's the "best of both worlds" until you need independent scaling or fully autonomous teams.

## 15. Hands-On Exercises

1. **Convert a layered app to feature-based**: Take a project with `controller/`, `service/`, `repository/` and reorganize into `users/`, `orders/`, `payments/`. Run ArchUnit tests to verify no cross-feature dependencies.

2. **Build a modular monolith**: Create a Gradle multi-module project with 3 business modules + 1 shared module. Wire them together via `applicationContext`. Add ArchUnit/Modulith tests.

3. **Implement event-based communication**: Replace direct service calls between modules with `ApplicationEventPublisher`. Measure the latency difference.

## 16. Advanced Challenges

1. **Implement Spring Modulith from scratch**: Build your own `ModuleTest` verification that checks Gradle module boundaries using the Java compiler API. Understand what Modulith does internally.

2. **Design a gradual extraction strategy**: Take a modular monolith and write a step-by-step plan to extract one module as a microservice. Include: API versioning, data migration, cutover strategy, rollback plan.

3. **Benchmark in-process vs network communication**: Build two identical modules. Measure latency for: (a) direct method call, (b) domain event (sync), (c) domain event (async), (d) REST call (simulating microservices). Document the exact latency profiles.
