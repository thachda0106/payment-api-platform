# Session 07: Clean Architecture & Vertical Slice Architecture

## 1. Why This Topic Exists

Hexagonal architecture solves domain-infrastructure coupling. Clean Architecture adds **use-case centricity** and strict **dependency inversion** at every layer boundary. Vertical Slice Architecture is the counter-movement: organize code by feature, not layer — every feature is a self-contained vertical.

Understanding both is essential because:
- Clean Architecture = maximum discipline, maximum testability, maximum ceremony
- Vertical Slice = maximum cohesion, minimum indirection, pragmatic
- You must know when each is appropriate

**Staff engineer insight**: Clean Architecture is the "nuclear option" for domain purity. It's right for insurance underwriting engines, financial risk calculators, and healthcare compliance systems. It's wrong for 95% of business applications. Vertical Slice is the pragmatic choice for most systems.

## 2. Mental Model

### Clean Architecture (The Onion)

```
┌──────────────────────────────────────────────┐
│              FRAMEWORKS & DRIVERS             │ ◄── Spring Boot, DB, Web
│  ┌────────────────────────────────────────┐  │
│  │        INTERFACE ADAPTERS               │  │ ◄── Controllers, Repositories
│  │  ┌──────────────────────────────────┐  │  │
│  │  │      APPLICATION / USE CASES     │  │  │ ◄── CreateOrderUseCase
│  │  │  ┌────────────────────────────┐  │  │  │
│  │  │  │       DOMAIN ENTITIES       │  │  │  │ ◄── Order, Money
│  │  │  │                            │  │  │  │
│  │  │  │  Business rules that are   │  │  │  │
│  │  │  │  true regardless of any    │  │  │  │
│  │  │  │  application or framework  │  │  │  │
│  │  │  └────────────────────────────┘  │  │  │
│  │  └──────────────────────────────────┘  │  │
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘

DEPENDENCY RULE: Dependencies point INWARD.
Inner circles know NOTHING about outer circles.
```

### Vertical Slice Architecture

```
Instead of:                          Use:
                                     
controller/                          orders/
  OrderController.java                 CreateOrderEndpoint.java
  UserController.java                  ConfirmOrderEndpoint.java
service/                               CancelOrderEndpoint.java
  OrderService.java                    OrderQueries.cs
  UserService.java                     OrderDto.java
repository/                          users/
  OrderRepository.java                 RegisterUserEndpoint.java
  UserRepository.java                  UserQueries.cs
entity/                                UserDto.java
  Order.java                         payments/
  User.java                            ProcessPaymentEndpoint.java
                                       PaymentDto.java

Each "slice" is self-contained:
- Its own endpoint/controller
- Its own validation
- Its own service/handler
- Its own database query
- Its own DTO/response model
```

## 3. Internal Architecture

### Clean Architecture Source Tree

```
src/
├── domain/                              ← Innermost circle
│   ├── entity/
│   │   ├── Order.java                  ← Enterprise business rules
│   │   ├── Customer.java
│   │   └── Money.java
│   └── service/
│       └── PricingRules.java            ← Domain service
│
├── application/                         ← Use case circle
│   ├── port/
│   │   ├── in/
│   │   │   ├── CreateOrderUseCase.java ← Input port (interface)
│   │   │   └── GetOrderUseCase.java
│   │   └── out/
│   │       ├── OrderRepositoryPort.java ← Output port (interface)
│   │       ├── PaymentGatewayPort.java
│   │       └── EventPublisherPort.java
│   ├── service/
│   │   ├── CreateOrderService.java     ← Implements CreateOrderUseCase
│   │   └── GetOrderService.java        ← Implements GetOrderUseCase
│   └── dto/
│       ├── CreateOrderCommand.java
│       └── OrderResponse.java
│
├── adapter/                             ← Interface adapter circle
│   ├── in/
│   │   ├── web/
│   │   │   ├── OrderController.java    ← Calls CreateOrderUseCase
│   │   │   └── OrderDtoMapper.java
│   │   └── messaging/
│   │       └── OrderEventConsumer.java
│   └── out/
│       ├── persistence/
│       │   ├── OrderJpaRepository.java ← Implements OrderRepositoryPort
│       │   ├── OrderJpaEntity.java
│       │   └── OrderPersistenceMapper.java
│       └── external/
│           └── StripePaymentAdapter.java ← Implements PaymentGatewayPort
│
└── configuration/                       ← Outermost circle
    ├── ApplicationConfig.java           ← Wire everything together
    ├── PersistenceConfig.java
    └── WebSecurityConfig.java
```

### Key Difference from Hexagonal

Clean Architecture explicitly separates:
1. **Domain Entities** (enterprise-wide business rules) from **Use Cases** (application-specific business rules)
2. **Input Ports** (what the application can do) from **Output Ports** (what the application needs)

In hexagonal architecture, these are often collapsed into "domain services" and "ports". Clean Architecture gives each a distinct layer with distinct responsibility.

### Clean Architecture Use Case Implementation

```java
// application/port/in/CreateOrderUseCase.java
public interface CreateOrderUseCase {
    OrderResponse execute(CreateOrderCommand command);
}

// application/port/out/OrderRepositoryPort.java
public interface OrderRepositoryPort {
    Order save(Order order);
    Optional<Order> findById(OrderId id);
}

// application/port/out/PaymentGatewayPort.java
public interface PaymentGatewayPort {
    PaymentResult charge(Money amount, PaymentMethod method);
}

// application/service/CreateOrderService.java
public class CreateOrderService implements CreateOrderUseCase {
    private final OrderRepositoryPort orderRepository;
    private final CustomerRepositoryPort customerRepository;
    private final PaymentGatewayPort paymentGateway;
    private final EventPublisherPort eventPublisher;
    
    // Constructor injection of OUTPUT PORTS
    
    @Override
    public OrderResponse execute(CreateOrderCommand command) {
        // 1. Load
        Customer customer = customerRepository.findById(command.getCustomerId())
            .orElseThrow(() -> new CustomerNotFoundException(command.getCustomerId()));
        
        // 2. Domain logic
        Order order = Order.create(customer.getId(), command.getItems());
        
        // 3. Persist
        orderRepository.save(order);
        
        // 4. Side effects
        PaymentResult payment = paymentGateway.charge(order.getTotalAmount(), command.getPaymentMethod());
        if (payment.isSuccess()) {
            order.markAsPaid(payment.getTransactionId());
            orderRepository.save(order);
            eventPublisher.publish(new OrderPlacedEvent(order.getId()));
        }
        
        // 5. Return
        return OrderResponse.from(order, payment);
    }
}
```

### Vertical Slice Architecture Implementation

```java
// orders/CreateOrderEndpoint.java — THE ENTIRE SLICE
@RestController
@RequestMapping("/orders")
public class CreateOrderEndpoint {
    
    @PostMapping
    public ResponseEntity<CreateOrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request) {
        
        // Validation, business logic, persistence — ALL in one place
        // (for this specific feature)
        
        // 1. Validate
        if (request.getItems().isEmpty()) {
            throw new OrderMustHaveItemsException();
        }
        
        // 2. Business logic
        Order order = new Order();
        order.setCustomerId(request.getCustomerId());
        for (ItemRequest item : request.getItems()) {
            order.addItem(item.getProductId(), item.getQuantity(), item.getPrice());
        }
        order.calculateTotal();
        
        // 3. Persist
        orderRepository.save(order);
        
        // 4. Notify
        eventPublisher.publish(new OrderPlacedEvent(order.getId()));
        
        // 5. Response
        return ResponseEntity.status(201)
            .body(new CreateOrderResponse(order.getId(), order.getTotal()));
    }
}
```

**Key difference**: The vertical slice contains EVERYTHING needed for "create order" in one class (or a few tightly grouped classes). No jumping between 6 package layers to understand a feature.

### Vertical Slice with Mediator Pattern (C# inspiration, applicable to Java)

```java
// orders/CreateOrderHandler.java
@Component
public class CreateOrderHandler implements RequestHandler<CreateOrderCommand, CreateOrderResult> {
    
    @Override
    public CreateOrderResult handle(CreateOrderCommand command) {
        // All logic for this use case
        // ...
    }
}

// Orders are routed by convention or a mediator
// @RestController → delegates to mediator → mediator finds CreateOrderHandler → handler handles
```

This is more common in .NET with MediatR. In Spring Boot, the same effect is achieved with:
- `@RestController` per feature
- Service class per use case (instead of per entity)
- Repository per aggregate (already standard with Spring Data)

## 4. Runtime Behavior

### Clean Architecture Dependency Resolution

```
Spring Boot Boot Sequence:

1. @ComponentScan finds all @Configuration, @Service, @Repository, @Component
2. For each bean, resolve dependencies:
   
   OrderController
     depends on: CreateOrderUseCase (interface)
       implemented by: CreateOrderService
         depends on: OrderRepositoryPort (interface)
           implemented by: OrderJpaRepository
       
   Spring resolves: OrderController → CreateOrderService → OrderJpaRepository

3. Each layer only knows about the layer directly beneath it (interface, not impl)
4. The wiring is centralized in @Configuration classes or via @Primary qualifiers
```

### Vertical Slice Request Flow

```
POST /orders
  │
  ▼
CreateOrderEndpoint.createOrder(request)
  │ No delegation to separate service layer
  │ Business logic is here (or in CreateOrderHandler)
  │
  ├── orderRepository.save(order)         ← Direct call, no port interface
  ├── eventPublisher.publish(event)        ← Direct call
  └── return response
```

## 5. Request Flow Diagrams

### Clean Architecture

```
[HTTP Client]
    │ POST /orders
    ▼
[OrderController]  ← Adapter layer
    │ Maps HTTP → CreateOrderCommand
    │ Calls createOrderUseCase.execute(cmd)
    ▼
[CreateOrderService]  ← Application layer
    │ customer = customerPort.findById(cmd.customerId)
    │ order = Order.create(customer, cmd.items)  ← Domain layer
    │ orderPort.save(order)
    │ payment = paymentPort.charge(order.total)  ← Port (interface)
    │ eventPort.publish(OrderPlacedEvent)
    │ return OrderResponse.from(order)
    ▼
[OrderController]
    │ Maps OrderResponse → HTTP 201
    ▼
[HTTP Client]
```

### Vertical Slice

```
[HTTP Client]
    │ POST /orders
    ▼
[CreateOrderEndpoint]  ← Everything here
    │ Validate
    │ Business logic
    │ Persist
    │ Publish event
    │ Return response
    ▼
[HTTP Client]
```

## 6. Lifecycle Diagrams

### When to Migrate Layered → Vertical Slice

```
Trigger: Merge conflicts in OrderService.java (3 teams modifying same file)

Phase 1: Identify use cases
  OrderService methods:
    createOrder()  → CreateOrderHandler
    cancelOrder()  → CancelOrderHandler
    getOrder()     → GetOrderHandler
    searchOrders() → SearchOrdersHandler
    etc.

Phase 2: Create handler per use case
  CreateOrderHandler {
    handle(CreateOrderCommand cmd) → CreateOrderResult
  }

Phase 3: Move DTOs and validators into slices

Phase 4: Delete OrderService (now empty or delegating)
```

### When to Migrate Vertical Slice → Clean Architecture

```
Trigger: Need to support multiple interfaces (REST + gRPC + CLI)
         Same use case, different adapters

Phase 1: Extract use case interface from handler
         handle() → UseCase.execute()

Phase 2: Extract ports (interfaces) for external dependencies
         orderRepository → OrderRepositoryPort

Phase 3: Create adapters for each interface
         REST adapter, gRPC adapter, CLI adapter

Phase 4: Move domain entities to domain layer
         Ensure domain has zero framework dependencies
```

## 7. Source Code Reading Guide

1. **Clean Architecture Book** (Robert C. Martin): The definitive reference
2. **Spring Data JPA**: Spring's repository abstraction IS clean architecture's output port pattern
3. **jMolecules**: `@Port`, `@Adapter`, `@UseCase` annotations
4. **ArchUnit**: Enforce clean architecture dependency rules

## 8. Production Failure Scenarios

### Scenario 1: Clean Architecture Clutter

**Symptom**: 30 classes for "create user" feature. 10 interfaces with single implementations.

**Root cause**: Clean Architecture applied to a system with no domain complexity. Every port has exactly one adapter. Every use case has exactly one implementation.

**Resolution**: Remove unused interfaces. If `OrderRepositoryPort` has exactly one implementation forever, delete the interface and use the implementation directly. Clean Architecture is for when you NEED multiple implementations.

### Scenario 2: Vertical Slice Code Duplication

**Symptom**: The same validation logic duplicated across `CreateOrderHandler` and `UpdateOrderHandler`.

**Root cause**: Over-zealous vertical slicing. Some logic IS shared.

**Resolution**: Extract shared logic into domain objects (`Order.validate()`), shared validators, or utility classes. Vertical slice doesn't mean zero sharing — it means feature-specific code lives in the slice. Domain rules live in the domain.

### Scenario 3: Wrong Architecture for the Problem

**Symptom**: Adding a field takes 2 hours because you touch 12 files: Request DTO → Command → UseCase interface → UseCase impl → Entity → Port → Adapter → JPA Entity → Mapper → Response DTO → Test → Migration.

**Root cause**: Architecture overkill. This is a simple CRUD form, not a complex domain.

**Resolution**: Simplify. Use Spring Data REST. Generate code. Or accept that NOT all features need the full architecture treatment. Some endpoints can be simple CRUD.

## 9. Debugging Techniques

### Architecture Compliance Tests

```java
// Clean Architecture dependency rule enforcement
@Test
void applicationLayerShouldNotDependOnAdapters() {
    noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat()
        .resideInAPackage("..adapter..")
        .check(classes);
}

@Test
void domainLayerShouldNotDependOnApplicationOrAdapters() {
    noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("..application..", "..adapter..", "..configuration..")
        .check(classes);
}
```

## 10. Observability Considerations

Clean Architecture's explicit port boundaries create natural instrumentation points. Wrap every output port call with metrics. Vertical Slice doesn't have these natural boundaries — you must instrument at handler entry/exit instead.

## 11. Performance Implications

| Architecture | Classes per Feature | Method Call Depth | Memory (object allocation) |
|-------------|-------------------|-------------------|---------------------------|
| Vertical Slice | 2-5 | 2-3 | Low |
| Hexagonal | 5-10 | 3-5 | Medium |
| Clean Architecture | 8-20 | 4-7 | High |

The performance difference is negligible for business applications. You're adding microseconds of overhead. Choose based on maintainability, not performance.

## 12. Architecture Implications

### Decision Framework

| Criterion | Vertical Slice | Clean Architecture |
|-----------|---------------|-------------------|
| Team size | 1-10 | 10+ |
| Domain complexity | Low-Medium | High |
| Adapter variety | 1-2 | 3+ |
| System lifetime | <3 years | 5+ years |
| Learning curve | Low | High |
| Test isolation | Medium | High |
| Refactoring safety | Medium | High |

### The Pragmatic Hybrid

Most real systems are hybrids:
- Complex billing logic → Clean Architecture
- Simple CRUD admin panels → Vertical Slice  
- External integrations → Hexagonal (ports for testability)

One architecture doesn't need to rule the entire codebase.

## 13. Team Ownership Implications

| Architecture | Team Boundary | Dependencies |
|-------------|---------------|-------------|
| Vertical Slice | Feature = team | Low cross-slice deps |
| Clean Architecture | Layer ownership | Team per layer (anti-pattern) OR full-stack team per domain |

**Critical insight**: Clean Architecture does NOT mean "frontend team owns controllers, backend team owns use cases, DBA team owns repositories." That's organizational anti-pattern. One team owns a vertical from controller to database, using Clean Architecture for internal discipline.

## 14. Interview Questions

1. **"When would you NOT use Clean Architecture?"**
   - **Answer**: When the domain is simple (CRUD), when the system is short-lived, when the team is small and the indirection cost exceeds the isolation benefit, when there's only one of each adapter (one DB, one API style). Clean Architecture is insurance — you pay a premium for protection you may never need.

2. **"Compare Vertical Slice vs Clean Architecture for a 5-engineer team building a SaaS product."**
   - **Answer**: Start with Vertical Slice. It maximizes cohesion per feature, minimizes file-jumping, and allows fast iteration. As the product grows, selectively apply Clean Architecture to the most complex domains (billing, compliance, core algorithms). Don't pre-optimize architecture.

3. **"How do you prevent a vertical slice from becoming a monolith of spaghetti?"**
   - **Answer**: Domain objects (entities, value objects) are shared across slices. Business rules live in domain objects, not in handlers. Handlers orchestrate; domain objects decide. This is the key: vertical slicing of use cases, horizontal sharing of domain model.

## 15. Hands-On Exercises

1. **Implement the same feature in both styles**: Create a "place order" feature using Clean Architecture and Vertical Slice. Compare: file count, lines of code, test setup, time to understand the feature flow.

2. **Refactor a Vertical Slice to Clean Architecture**: As the feature grows complex (discounts, taxes, multi-currency), extract ports and use cases incrementally.

3. **Build an architecture fitness function**: Create a CI check that measures architecture drift. Tracks dependency violations, layer purity, and adapter-to-port ratios over time.

## 16. Advanced Challenges

1. **Implement a multi-module Clean Architecture**: Separate domain, application, adapter, and configuration into Gradle modules. Enforce dependency rules at compile time.

2. **Design a Clean Architecture with event sourcing**: The domain layer uses events as the source of truth. The application layer projects events to read models. Ports and adapters handle event storage and projection persistence.

3. **Measure the real cost of architecture**: Instrument a Clean Architecture application to count: objects created per request, method calls per request, memory allocated per request. Compare with a vertical slice equivalent. Quantify the overhead precisely.
