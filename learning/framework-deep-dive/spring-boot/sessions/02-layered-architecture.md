# Session 02: Layered Architecture Deep Dive

## 1. Why This Topic Exists

Layered architecture is the **default mental model** of Spring Boot. `@Controller` → `@Service` → `@Repository` is baked into every tutorial, every starter, every annotation. Understanding its strengths and failure modes is essential because:
- **80% of Spring Boot applications use layered architecture**
- It's the right choice for small-to-medium projects
- Its failure modes at scale are predictable and preventable
- Every alternative architecture exists because someone hit layered architecture's limits

## 2. Mental Model

```
┌──────────────────────────────────────┐
│         PRESENTATION LAYER           │  ← @Controller, @RestController
│     HTTP, validation, DTO mapping    │     Depends on: Application Layer
├──────────────────────────────────────┤
│         APPLICATION LAYER            │  ← @Service (orchestration)
│     Use cases, transactions, auth    │     Depends on: Domain Layer
├──────────────────────────────────────┤
│           DOMAIN LAYER               │  ← Entities, value objects, services
│     Business rules, invariants       │     Depends on: Nothing
├──────────────────────────────────────┤
│       INFRASTRUCTURE LAYER           │  ← @Repository, @Component
│     Persistence, messaging, external │     Depends on: Domain Layer (interfaces)
└──────────────────────────────────────┘

DEPENDENCY DIRECTION: Always inward/upward.
Upper layers depend on lower layers.
Lower layers NEVER depend on upper layers.
```

This is **not** the same as Clean Architecture (which inverts the dependency). In classic layered architecture, `@Service` directly depends on `@Repository`. In Clean Architecture, the domain defines repository interfaces, and infrastructure implements them.

## 3. Internal Architecture

### The Classic Spring Boot Layered Implementation

```
src/main/java/com/example/
├── controller/           ← @RestController classes
│   └── OrderController.java
│       - Maps HTTP to DTOs
│       - Calls OrderService
│       - Returns ResponseEntity
│
├── service/              ← @Service classes
│   ├── OrderService.java
│   │   - Orchestrates business logic
│   │   - Manages @Transactional boundaries
│   │   - Calls repositories and other services
│   └── OrderServiceImpl.java
│       - Contains actual logic
│
├── repository/           ← @Repository interfaces
│   └── OrderRepository.java
│       - Extends JpaRepository
│       - Provides data access
│
├── entity/               ← @Entity classes
│   └── Order.java
│       - JPA entity
│       - Often also the domain model
│
├── dto/                  ← Data Transfer Objects
│   ├── CreateOrderRequest.java
│   └── OrderResponse.java
│
├── exception/            ← Custom exceptions
│   └── OrderNotFoundException.java
│
└── config/               ← @Configuration classes
    └── AppConfig.java
```

### Dependency Flow

```
UserController
    ↓ depends on
UserService (interface)
    ↓ depends on
UserRepository, EmailService, AuditService
    ↓ depends on
User (entity), Database, SMTP, AuditLog
```

### What Spring Boot Does Under the Hood

When you write `@Service public class OrderService`, Spring:
1. Classpath scans `OrderService.class`
2. Detects `@Service` stereotype
3. Creates a `BeanDefinition` for `OrderService`
4. Analyzes constructor: `OrderService(OrderRepository repo, PaymentGateway pg)`
5. Resolves dependencies from container
6. Instantiates `OrderService` with resolved beans
7. Applies AOP proxies if needed (`@Transactional`, `@Cacheable`)

## 4. Runtime Behavior

### Request Execution Through Layers

```
Thread: http-nio-8080-exec-3
  │
  ▼
OrderController.createOrder(requestDto)
  │ validates, maps DTO → domain
  ▼
OrderService.createOrder(orderDetails)
  │ opens @Transactional
  │   ▼
  │ OrderRepository.save(order)        ← SQL INSERT
  │ PaymentGateway.charge(amount)       ← HTTP call to payment provider
  │ OrderRepository.save(order)         ← SQL UPDATE (status=PAID)
  │ InventoryService.reserve(items)     ← call to inventory service
  │   ▼
  │ commits @Transactional
  ▼
OrderController: return ResponseEntity<OrderResponse>
```

**Transaction boundary**: The `@Transactional` on `OrderService.createOrder()` means ALL database operations within that method (and any methods it calls) share a single transaction. If `PaymentGateway.charge()` succeeds but `InventoryService.reserve()` fails, the database operations roll back — but the payment charge does NOT (dual-write problem).

## 5. Request Flow Diagrams

### Happy Path

```
[Client] ──POST /orders──▶ [OrderController]
                               │ @Valid validates
                               │ Maps CreateOrderRequest → Order domain
                               ▼
                          [OrderService]
                               │ @Transactional begins
                               │ Validates business rules
                               ├──▶ [OrderRepository.save()]
                               ├──▶ [PaymentGateway.charge()]   (external HTTP)
                               ├──▶ [InventoryService.reserve()]
                               │ @Transactional commits
                               ▼
                          [OrderController]
                               │ Maps Order → OrderResponse
                               │ Returns 201 Created
                               ▼
                            [Client]
```

### Failure Path (Business Rule Violation)

```
[Client] ──POST /orders──▶ [OrderController]
                               ▼
                          [OrderService]
                               │ Validates: "Customer has unpaid orders"
                               │ Business rule violation detected
                               │ Throws BusinessRuleViolationException
                               ▼
                          [ExceptionHandler]
                               │ @ExceptionHandler catches
                               │ Maps to 422 Unprocessable Entity
                               │ Returns ErrorResponse
                               ▼
                            [Client]  ← 422 { "error": "CUSTOMER_HAS_UNPAID_ORDERS" }
```

### Failure Path (Infrastructure Failure)

```
[Client] ──POST /orders──▶ [OrderController]
                               ▼
                          [OrderService]
                               │ @Transactional begins
                               │ OrderRepository.save(order) ✓
                               │ PaymentGateway.charge(amount) ✗ TIMEOUT
                               │   │
                               │   ▼ (exception propagates)
                               │ @Transactional ROLLBACK
                               │ Exception re-thrown
                               ▼
                          [ExceptionHandler]
                               │ Maps to 502 Bad Gateway
                               │ Logs: "Payment gateway timeout"
                               ▼
                            [Client] ← 502 { "error": "PAYMENT_FAILED" }
```

## 6. Lifecycle Diagrams

### Application Bootstrap (Layered App)

```
SpringApplication.run()
  │
  ├── 1. Create ApplicationContext (AnnotationConfigServletWebServerApplicationContext)
  ├── 2. Register @Configuration classes
  ├── 3. Component scan: com.example
  │     ├── Finds @RestController → OrderController
  │     ├── Finds @Service → OrderService
  │     └── Finds @Repository → OrderRepository
  ├── 4. Resolve dependencies:
  │     OrderController depends on OrderService
  │     OrderService depends on OrderRepository, PaymentGateway
  │     OrderRepository depends on DataSource, EntityManager
  ├── 5. Instantiate beans in dependency order
  ├── 6. Apply BeanPostProcessors:
  │     ├── @Transactional → AOP proxy around OrderService
  │     ├── @Async → AOP proxy around NotificationService
  │     └── @Cacheable → AOP proxy around ProductService
  ├── 7. Start embedded Tomcat on port 8080
  └── 8. Application ready
```

## 7. Source Code Reading Guide

To understand layered architecture's relationship with Spring:

1. **`org.springframework.context.annotation.ClassPathBeanDefinitionScanner`**
   - How `@ComponentScan` discovers your `@Service` classes
   - `doScan()` method: scans packages, finds annotated classes, registers BeanDefinitions

2. **`org.springframework.beans.factory.support.DefaultListableBeanFactory`**
   - `resolveDependency()`: How Spring resolves constructor arguments
   - See how dependency resolution traverses the bean graph

3. **`org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator`**
   - How `@Transactional` generates proxies around your services
   - `wrapIfNecessary()`: Decides whether to create a proxy

## 8. Production Failure Scenarios

### Scenario 1: Service Bloat

**Symptom**: `OrderService.java` is 3,000 lines. Every merge request touches it. Nobody knows what all the methods do.

**Root cause**: Layered architecture concentrates business logic in service classes. Every feature adds methods to the same `OrderService`.

**Detection**: `wc -l src/main/java/com/example/service/OrderService.java` > 500.

**Resolution**: Extract use-case-specific services: `OrderCreationService`, `OrderCancellationService`, `OrderFulfillmentService`. Consider vertical slices.

### Scenario 2: Circular Dependency

**Symptom**: `BeanCurrentlyInCreationException` at startup with "Is there an unresolvable circular reference?"

**Root cause**: `OrderService` depends on `PaymentService`, which depends on `OrderService`.

**Detection**: Spring Boot `--debug` flag shows the circular reference chain.

**Resolution**: Extract the shared logic into a third service, or use `@Lazy` on one side (band-aid), or refactor to use events.

### Scenario 3: Transaction Boundary Leak

**Symptom**: Database changes are committed even when business logic fails.

**Root cause**: `@Transactional` on the controller method instead of the service method. Exception occurs in the service but is caught by the controller — Spring never sees the exception, so it commits.

**Detection**: Enable `logging.level.org.springframework.transaction=TRACE` and verify transaction boundaries.

## 9. Debugging Techniques

### Finding Layered Architecture Violations

```java
// ArchUnit test: Controllers should not access repositories directly
@Test
void controllersShouldNotAccessRepositories() {
    noClasses()
        .that().resideInAPackage("..controller..")
        .should().accessClassesThat()
        .resideInAPackage("..repository..")
        .check(classes);
}

// Services should not access controllers
@Test
void servicesShouldNotDependOnControllers() {
    noClasses()
        .that().resideInAPackage("..service..")
        .should().dependOnClassesThat()
        .resideInAPackage("..controller..")
        .check(classes);
}
```

### Finding Transaction Boundaries

Enable Spring transaction logging:
```properties
logging.level.org.springframework.transaction.interceptor=TRACE
logging.level.org.springframework.orm.jpa=TRACE
```

Then trace in logs:
```
TRACE o.s.t.i.TransactionInterceptor - Getting transaction for [com.example.service.OrderService.createOrder]
TRACE o.s.t.i.TransactionInterceptor - Completing transaction for [com.example.service.OrderService.createOrder]
```

## 10. Observability Considerations

In layered architecture, tracing a business transaction requires propagating context across layers:

```java
// Controller
@PostMapping("/orders")
public OrderResponse createOrder(@Valid CreateOrderRequest request) {
    MDC.put("userId", request.getUserId());
    MDC.put("operation", "CREATE_ORDER");
    try {
        return orderService.createOrder(request);
    } finally {
        MDC.clear();
    }
}
```

Without this, you get siloed logs per layer with no way to correlate them.

## 11. Performance Implications

| Aspect | Impact |
|--------|--------|
| AOP proxies | ~1-5% overhead per proxied method call |
| Classpath scanning | Startup time increases linearly with number of classes |
| Layered indirection | Direct method calls, negligible overhead |
| @Transactional | Connection acquisition overhead, pool contention under load |

The performance cost of layered architecture is negligible compared to: bad SQL queries, N+1 problems, and network calls inside transactions.

## 12. Architecture Implications

### Advantages
- **Low cognitive load**: Every Spring developer understands it
- **Fast onboarding**: New team members know where code lives
- **Framework alignment**: Spring stereotypes match layer names
- **Simple testing**: Each layer can be unit-tested independently

### Disadvantages
- **Low cohesion within layers**: All services in one package, regardless of domain
- **High coupling between features**: OrderService imports UserService, PaymentService, InventoryService → spaghetti
- **Poor team ownership**: No clear "who owns OrderService" when 3 teams modify it
- **Doesn't scale with team size**: Beyond 5-8 engineers, merge conflicts increase exponentially

### Scaling Limitations
- **Team scale**: Fails at 8+ engineers because everyone modifies the same files
- **Domain scale**: Fails at 50+ entities because service layer becomes a tangled graph
- **Deployment scale**: Single deployable, so all features deploy together (slow, risky)

## 13. Team Collaboration Implications

| Team Size | Layered Architecture Viability |
|-----------|-------------------------------|
| 1-3 | Ideal |
| 4-7 | Manageable with discipline |
| 8-15 | Painful — migrate to feature-based |
| 15+ | Unsustainable — migrate to modular monolith |

## 14. Interview Questions

1. **"When would you choose layered architecture over hexagonal architecture?"**
   - **Answer**: When team size ≤ 5, domain complexity is low, and the system is a simple CRUD API. Hexagonal architecture adds ceremony (ports, adapters, dependency inversion) that provides no value for simple systems. Start layered, refactor to hexagonal when complexity warrants it.

2. **"What is the biggest risk of layered architecture at scale?"**
   - **Answer**: The service layer becomes a god object. Every feature adds methods to the same services. Eventually you cannot deploy one feature without deploying all features because everything is coupled through shared services. The fix is vertical decomposition.

3. **"Why does Spring use AOP proxies for @Transactional instead of compile-time weaving?"**
   - **Answer**: Runtime proxies are simpler to set up (no build-time bytecode manipulation), don't require a special compiler or agent, and work with any Spring-managed bean. The trade-off is proxy limitations: self-invocation bypasses the proxy, and only public methods can be transactional. Virtual threads (Project Loom) are reducing the overhead concern.

## 15. Hands-On Exercises

1. **Analyze transaction boundaries**: Add `logging.level.org.springframework.transaction.interceptor=TRACE` to a Spring Boot app. Trace 5 different API calls and map which methods open/close transactions.

2. **Measure AOP proxy overhead**: Create a service with `@Transactional` and one without. Benchmark 1M calls to each. Measure the overhead.

3. **Refactor a controller to be thin**: Take an existing controller that does validation, business logic, AND HTTP concerns. Extract business logic to a service, validation to a separate validator, and HTTP mapping to a DTO mapper.

## 16. Advanced Challenges

1. **Implement a layer violation detector**: Write a custom ArchUnit rule that detects when a controller calls a repository directly or when a service depends on a controller.

2. **Benchmark layered vs hexagonal architecture**: Build two versions of the same simple API (users CRUD). One layered, one hexagonal. Measure: lines of code, test coverage, time to add a new feature, cognitive load.

3. **Track architecture drift over time**: Set up a CI pipeline that generates ArchUnit reports and compares them against the previous build. Alert when dependency violations increase.
