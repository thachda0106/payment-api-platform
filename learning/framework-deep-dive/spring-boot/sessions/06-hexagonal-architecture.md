# Session 06: Hexagonal Architecture (Ports & Adapters)

## 1. Why This Topic Exists

Layered architecture has one fatal flaw: the domain depends on the database. Change your ORM, rewrite your domain. Switch from PostgreSQL to MongoDB, rewrite your services. This is because `@Service` directly depends on `@Repository`, which depends on JPA interfaces.

Hexagonal architecture flips this: the domain defines **ports** (interfaces), and infrastructure implements **adapters**. The domain knows nothing about PostgreSQL, HTTP, or Kafka. It speaks only its own language.

**Staff engineer insight**: Hexagonal architecture is the most pragmatic "clean" architecture for Spring Boot. It provides the core benefit (domain independence from infrastructure) without the full ceremony of Clean Architecture's 4 layers.

## 2. Mental Model

```
┌──────────────────────────────────────────────────────┐
│                    DOMAIN CORE                        │
│                                                      │
│  Entities, Value Objects, Domain Services,           │
│  Repository INTERFACES (ports), Event INTERFACES     │
│                                                      │
│  ← KNOWS NOTHING about HTTP, SQL, Kafka, JSON       │
│  ← No framework annotations (no @Entity, @Table)    │
│  ← Pure Java/Kotlin. Can run in a unit test.         │
│                                                      │
│         │                              │              │
│    ┌────┴────┐                   ┌────┴────┐         │
│    │  PORT   │                   │  PORT   │         │
│    │  (in)   │                   │  (out)  │         │
│    └────┬────┘                   └────┬────┘         │
└─────────┼──────────────────────────────┼────────────┘
          │                              │
    ┌─────┴──────┐                 ┌─────┴──────┐
    │  ADAPTER   │                 │  ADAPTER   │
    │  (primary) │                 │ (secondary)│
    └────────────┘                 └────────────┘
    
PRIMARY (Driving) adapters:       SECONDARY (Driven) adapters:
  - REST Controller                - PostgreSQL Repository
  - GraphQL Resolver               - Redis Cache
  - gRPC Service                   - Kafka Producer
  - Kafka Consumer                 - External API Client
  - CLI Command                    - File System
  - Scheduled Task                 - Email/SMS Gateway
```

**Dependency Rule**: All arrows point TOWARD the domain. Primary adapters depend on ports. Ports depend on nothing external. Secondary adapters implement ports.

## 3. Internal Architecture

### Source Tree

```
src/main/java/com/example/
├── domain/                              ← DOMAIN CORE
│   ├── order/
│   │   ├── Order.java                  ← Entity (pure, no @Entity)
│   │   ├── OrderId.java                ← Value Object
│   │   ├── OrderItem.java
│   │   ├── Money.java
│   │   ├── OrderStatus.java
│   │   ├── OrderService.java           ← Domain Service
│   │   └── OrderRepository.java        ← PORT (interface!)
│   │       └── interface OrderRepository {
│   │             Optional<Order> findById(OrderId id);
│   │             void save(Order order);
│   │           }
│   ├── customer/
│   │   ├── Customer.java
│   │   ├── CustomerRepository.java     ← PORT (interface)
│   │   └── ...
│   └── shared/
│       ├── DomainEvent.java
│       ├── Specification.java
│       └── Result.java                 ← Either<Error, T>
│
├── application/                         ← APPLICATION LAYER (use cases)
│   ├── order/
│   │   ├── CreateOrderUseCase.java
│   │   ├── ConfirmOrderUseCase.java
│   │   ├── CancelOrderUseCase.java
│   │   └── OrderDto.java
│   └── customer/
│       ├── RegisterCustomerUseCase.java
│       └── CustomerDto.java
│
└── infrastructure/                      ← ADAPTERS
    ├── persistence/
    │   ├── OrderJpaRepository.java     ← JPA adapter implements OrderRepository
    │   ├── OrderJpaEntity.java         ← @Entity (JPA-specific, not domain)
    │   ├── OrderEntityMapper.java      ← Maps between domain Order and JPA entity
    │   └── CustomerJpaRepository.java
    │
    ├── web/
    │   ├── OrderController.java        ← REST adapter
    │   └── CustomerController.java
    │
    ├── messaging/
    │   ├── OrderEventPublisher.java    ← Kafka adapter
    │   ├── PaymentEventConsumer.java   ← Kafka consumer adapter
    │   └── EventSchemaRegistry.java
    │
    ├── external/
    │   ├── PaymentGatewayAdapter.java  ← External API adapter
    │   └── EmailNotificationAdapter.java
    │
    └── config/
        ├── PersistenceConfig.java
        ├── WebConfig.java
        └── MessagingConfig.java
```

### Port Definition (Domain Layer)

```java
// domain/order/OrderRepository.java — THE PORT
public interface OrderRepository {
    Optional<Order> findById(OrderId id);
    List<Order> findByCustomerId(CustomerId customerId);
    void save(Order order);
    void delete(OrderId id);
}

// domain/order/OrderService.java — Uses the port (depends on interface, not implementation)
public class OrderService {
    private final OrderRepository orderRepository;  // ← INTERFACE
    
    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }
    
    public Order confirmOrder(OrderId orderId) {
        Order order = orderRepository.findById(orderId)  // ← Calls port
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.confirm();
        orderRepository.save(order);  // ← Calls port
        return order;
    }
}
```

### Adapter Implementation (Infrastructure Layer)

```java
// infrastructure/persistence/OrderJpaRepository.java
@Repository
public class OrderJpaRepository implements OrderRepository {  // ← Implements port
    private final OrderJpaDao dao;
    private final OrderEntityMapper mapper;
    
    @Override
    public Optional<Order> findById(OrderId id) {
        return dao.findById(id.getValue())  // ← JPA-specific
            .map(mapper::toDomain);          // ← Maps JPA entity → domain object
    }
    
    @Override
    public void save(Order order) {
        OrderJpaEntity entity = mapper.toEntity(order);  // ← Maps domain → JPA entity
        dao.save(entity);
    }
}

// infrastructure/persistence/OrderEntityMapper.java
@Component
public class OrderEntityMapper {
    public Order toDomain(OrderJpaEntity entity) {
        return Order.reconstitute(  // Factory method for loading from persistence
            new OrderId(entity.getId()),
            new CustomerId(entity.getCustomerId()),
            OrderStatus.valueOf(entity.getStatus()),
            entity.getItems().stream().map(this::toDomainItem).toList(),
            new Address(entity.getStreet(), entity.getCity(), entity.getZipCode())
        );
    }
    
    public OrderJpaEntity toEntity(Order domain) {
        OrderJpaEntity entity = new OrderJpaEntity();
        entity.setId(domain.getId().getValue());
        entity.setCustomerId(domain.getCustomerId().getValue());
        entity.setStatus(domain.getStatus().name());
        entity.setItems(domain.getItems().stream().map(this::toEntityItem).toList());
        entity.setStreet(domain.getDeliveryAddress().getStreet());
        entity.setCity(domain.getDeliveryAddress().getCity());
        entity.setZipCode(domain.getDeliveryAddress().getZipCode());
        return entity;
    }
}
```

### Wiring

```java
// infrastructure/config/OrderConfig.java
@Configuration
public class OrderConfig {
    
    @Bean
    public OrderService orderService(OrderRepository orderRepository) {
        // Spring injects OrderJpaRepository (the adapter) into OrderService
        // OrderService only knows about the OrderRepository interface
        return new OrderService(orderRepository);
    }
}
```

## 4. Runtime Behavior

### Request Flow

```
HTTP Request: POST /orders
  │
  ▼
[OrderController]  ← Primary Adapter (Web)
  │ Parses HTTP request
  │ Maps JSON → CreateOrderCommand
  └──▶ [CreateOrderUseCase]  ← Application (Use Case)
         │ Orchestrates the flow
         ├──▶ [CustomerRepository.findById()]  ← PORT (interface)
         │      │ resolved to:
         │      ▼
         │    [CustomerJpaRepository.findById()]  ← Secondary Adapter (JPA)
         │      │ SELECT * FROM customers WHERE id = ?
         │      │ Maps JPA entity → Customer domain object
         │      ▼
         │    returns Customer
         │
         ├──▶ [new Order(customer, items)]  ← DOMAIN (pure logic)
         │      Validates invariants
         │      Creates Order object
         │
         ├──▶ [orderRepository.save(order)]  ← PORT (interface)
         │      ▼
         │    [OrderJpaRepository.save()]  ← Secondary Adapter (JPA)
         │      │ Maps Order → JPA entity
         │      │ INSERT INTO orders ...
         │      ▼
         │
         └──▶ [orderEventPublisher.publish(event)]  ← PORT
                ▼
              [KafkaOrderEventPublisher.publish()]  ← Secondary Adapter (Kafka)
                │ Serializes event
                │ Produces to Kafka topic
                ▼
  ▶ [OrderController]
  │ Maps Order → OrderResponse DTO
  │ Returns 201 Created
  ▼
HTTP Response: 201 { "orderId": "123" }
```

### Testability in Action

```java
// Domain test: NO Spring, NO DB, NO HTTP. Just pure logic.
@Test
void shouldConfirmOrderWhenValid() {
    // Arrange: Mock the port (interface, not implementation)
    OrderRepository mockRepo = mock(OrderRepository.class);
    Order order = Order.create(customerId, address);
    order.addItem(productId, quantity, price);
    when(mockRepo.findById(order.getId())).thenReturn(Optional.of(order));
    
    OrderService service = new OrderService(mockRepo);
    
    // Act
    Order confirmed = service.confirmOrder(order.getId());
    
    // Assert
    assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    verify(mockRepo).save(order);
}
```

Contrast with layered architecture where testing `OrderService` requires mocking `EntityManager`, `DataSource`, and Spring context — or using `@DataJpaTest` with an embedded database.

## 5. Request Flow Diagrams

### Primary (Incoming) Adapters

```
┌──────────┐     ┌──────────────┐     ┌──────────────┐
│  HTTP    │────▶│  REST        │────▶│              │
│  Client  │     │  Controller  │     │              │
└──────────┘     └──────────────┘     │              │
                                      │   USE CASE   │
┌──────────┐     ┌──────────────┐     │   (in port)  │
│ GraphQL  │────▶│  GraphQL     │────▶│              │
│  Client  │     │  Resolver    │     │              │
└──────────┘     └──────────────┘     └──────┬───────┘
                                             │
┌──────────┐     ┌──────────────┐            │
│  gRPC    │────▶│  gRPC        │────────────┤
│  Client  │     │  Service     │            │
└──────────┘     └──────────────┘            │
                                             │
┌──────────┐     ┌──────────────┐            │
│  Kafka   │────▶│  Kafka       │────────────┘
│  Topic   │     │  Consumer    │
└──────────┘     └──────────────┘

ALL adapters call the SAME use case.
The use case doesn't know or care which adapter called it.
```

### Secondary (Outgoing) Adapters

```
                    ┌──────────────┐
                    │   USE CASE   │
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
    ┌─────────┴──┐  ┌─────┴──────┐  ┌──┴──────────┐
    │ Repository │  │ Event      │  │ External     │
    │   PORT     │  │ Publisher  │  │ API PORT     │
    └─────────┬──┘  │   PORT     │  └──┬──────────┘
              │     └─────┬──────┘     │
    ┌─────────┴──┐  ┌─────┴──────┐  ┌──┴──────────┐
    │ JPA        │  │ Kafka      │  │ HTTP Client  │
    │ ADAPTER    │  │ ADAPTER    │  │ ADAPTER      │
    └─────────┬──┘  └─────┬──────┘  └──┬──────────┘
              │            │            │
         ┌────┴──┐    ┌────┴───┐   ┌───┴──────┐
         │Postgre│    │ Kafka  │   │ Payment  │
         │ SQL   │    │ Broker │   │ Provider │
         └───────┘    └────────┘   └──────────┘
```

## 6. Lifecycle Diagrams

### Adding a New Storage Adapter

```
You want to add Redis caching alongside PostgreSQL.
  (Without hexagonal: modify OrderService, add cache logic inline)
  (With hexagonal: implement a new adapter)

Step 1: Create RedisOrderCacheAdapter implements OrderRepository
Step 2: Create CachingOrderRepository (Decorator pattern)
Step 3: Wire in config

@Configuration
public class OrderConfig {
    @Bean
    public OrderRepository orderRepository(
            OrderJpaRepository jpaAdapter,
            RedisOrderCacheAdapter redisAdapter) {
        return new CachingOrderRepository(jpaAdapter, redisAdapter);
    }
}

// Decorator
public class CachingOrderRepository implements OrderRepository {
    private final OrderRepository primary;    // JPA
    private final OrderRepository cache;      // Redis
    
    @Override
    public Optional<Order> findById(OrderId id) {
        return cache.findById(id)
            .or(() -> primary.findById(id)
                .map(order -> {
                    cache.save(order);
                    return order;
                }));
    }
    
    @Override
    public void save(Order order) {
        primary.save(order);
        cache.save(order);
    }
}

// OrderService does NOT change. Zero code changes in domain layer.
```

This is the power of hexagonal architecture: swap infrastructure without touching domain logic.

## 7. Source Code Reading Guide

1. **Original Hexagonal Architecture article** by Alistair Cockburn: Understand the original vision
2. **Spring Modulith**: Observe how Spring applies hexagonal principles
3. **`spring-boot-starter-data-jpa`**: See how Spring Data JPA's repository abstraction IS a port/adapter pattern (`JpaRepository` interface → `SimpleJpaRepository` implementation)

## 8. Production Failure Scenarios

### Scenario 1: Adapter Logic Leaks into Domain

**Symptom**: `OrderService` contains `@Query` annotations or `EntityManager` references.

**Root cause**: Adapter concern (SQL optimization) leaked into domain.

**Resolution**: Move the optimized query to the JPA adapter. The port interface declares `List<Order> findPendingOrdersOlderThan(Duration maxAge)`. The adapter implements it with `@Query(... nativeQuery = true ...)`.

### Scenario 2: Too Many Adapter Layers

**Symptom**: Adding a simple field requires changes in 8 files: DTO → Mapper → Domain → Port → Adapter → Entity → Mapper → DB migration.

**Root cause**: Hexagonal architecture applied to a simple CRUD application that doesn't benefit from the indirection.

**Resolution**: For simple modules, skip the mapper, use the domain object as the JPA entity (Spring Data allows this). Hexagonal architecture doesn't require purity — it requires the ABILITY to swap adapters. If you'll never swap, the indirection is waste.

### Scenario 3: Mapper Bugs

**Symptom**: Field silently lost during save because `OrderEntityMapper` forgot to map it.

**Root cause**: Manual mapper code is error-prone. No compile-time safety for field mapping.

**Resolution**: Use MapStruct or generated mappers. Write integration tests that verify round-trip mapping (domain → entity → domain equality). Or consider using the domain object directly as JPA entity when hexagonal purity isn't needed.

## 9. Debugging Techniques

### Detecting Port Violations

ArchUnit test for hexagonal architecture compliance:

```java
@Test
void domainShouldNotDependOnInfrastructure() {
    noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAPackage("..infrastructure..")
        .check(classes);
}

@Test
void domainShouldNotDependOnSpringFramework() {
    noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "com.fasterxml.jackson.."
        )
        .check(classes);
}
```

## 10. Observability Considerations

Adapter boundaries are natural observability points:

```java
// Metrics around every port call
public class MeteredOrderRepository implements OrderRepository {
    private final OrderRepository delegate;
    private final MeterRegistry registry;
    
    @Override
    public Optional<Order> findById(OrderId id) {
        return registry.timer("order.repository.findById")
            .recordCallable(() -> delegate.findById(id));
    }
    
    @Override
    public void save(Order order) {
        registry.counter("order.repository.save").increment();
        delegate.save(order);
    }
}
```

This gives you adapter-level metrics without modifying domain code. You can measure: "How many DB calls does this use case make?" "What's the P99 latency of the Kafka adapter?"

## 11. Performance Implications

| Aspect | Hexagonal | Layered |
|--------|-----------|---------|
| Object allocation | Higher (DTOs, mappers, adapters) | Lower |
| Method call depth | +1-2 levels (adapter indirection) | +0 |
| Test speed | Fast (unit tests without Spring) | Slower (need Spring context or mocks) |
| Build time | Same | Same |
| Runtime overhead | Negligible (<1%) | Baseline |

The performance cost is negligible. The benefit is testability and maintainability.

## 12. Architecture Implications

### When to Use Hexagonal
- Domain logic is complex and should be testable in isolation
- Infrastructure will change (DB migration, message broker change)
- Multiple primary adapters (REST + GraphQL + gRPC)
- Long-lived system (>5 years)

### When NOT to Use Hexagonal
- Simple CRUD with no business logic
- Prototype that will be thrown away
- Single adapter (just REST, just one DB)
- Team finds the indirection confusing (pragmatism > purity)

### Hexagonal vs Layered vs Clean Architecture

| | Layered | Hexagonal | Clean |
|---|---|---|---|
| Layers | 4 (by role) | 3 (domain, application, infrastructure) | 4 (entities, use cases, adapters, frameworks) |
| Domain independence | No (depends on infra interfaces) | Yes (ports invert dependency) | Yes |
| Ceremony | Low | Medium | High |
| Learning curve | 1 day | 1 week | 2-4 weeks |
| Best for | Simple apps | Complex domain, multiple adapters | Very complex domain, multiple teams |

## 13. Team Ownership Implications

| Layer | Team Ownership |
|-------|---------------|
| Domain (ports) | Domain team (owns interfaces) |
| Application (use cases) | Same team (owns orchestration) |
| Infrastructure (adapters) | Same team OR platform team |
| Shared kernel | Architecture review required |

A platform team can provide adapters (Kafka, PostgreSQL, Redis) that domain teams consume. The contract is the port interface.

## 14. Interview Questions

1. **"Why are they called 'ports' and 'adapters'?"**
   - **Answer**: From the electronics metaphor. A port is a connector specification (USB-C defines shape, pins, protocol). An adapter connects a specific device to that port (USB-C to HDMI adapter, USB-C to Ethernet adapter). In software: `OrderRepository` is the port. `PostgresOrderRepository` and `InMemoryOrderRepository` are adapters. You can swap adapters without changing the port.

2. **"What's the difference between hexagonal and layered architecture?"**
   - **Answer**: Dependency direction. In layered, the service layer depends on the repository layer's concrete implementation. In hexagonal, the service layer depends on a port (interface) that the repository layer implements. This inverts the dependency: the service defines what it needs; infrastructure provides it. The domain owns its contracts.

3. **"Does hexagonal architecture slow down development?"**
   - **Answer**: Yes, initially. You write more files (ports, adapters, mappers, config). The payoff comes in months 6-24 when you need to change infrastructure or add adapters. For a 2-week prototype, hexagonal is overhead. For a 2-year system, hexagonal is insurance. The art is knowing which modules need it and which don't.

## 15. Hands-On Exercises

1. **Refactor a layered service to hexagonal**: Take a `@Service` that directly uses `EntityManager`. Extract port interfaces. Create JPA adapter. Wire through config. Compare test speeds before and after.

2. **Add a second adapter**: After completing #1, add an in-memory repository adapter for testing. Swap between JPA and in-memory via Spring profiles.

3. **Add a new primary adapter**: Add a GraphQL or gRPC endpoint that uses the same use case as the REST controller.

## 16. Advanced Challenges

1. **Implement a caching decorator**: Create a `CachingXxxRepository` that wraps any repository adapter with Redis caching. Use Spring's `@ConditionalOnProperty` to enable/disable caching.

2. **Build a multi-tenancy adapter**: Implement a `TenantAwareDataSourceAdapter` that routes queries to different database schemas based on tenant context. The domain code should not know about tenancy.

3. **Design an adapter versioning strategy**: When you have 2 versions of an external API (v1, v2), design how the adapter abstracts version differences so the domain doesn't know which version is being used.
