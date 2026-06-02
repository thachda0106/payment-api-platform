# Session 05: Domain-Driven Design — Tactical Patterns

## 1. Why This Topic Exists

Strategic DDD defines boundaries. Tactical DDD fills those boundaries with well-designed domain models. Without tactical patterns, you get **anemic domain models**: entities with getters/setters and no behavior, where all logic lives in services. This is the most common architecture anti-pattern in Spring Boot applications.

**Staff engineer insight**: Strategic DDD is harder to learn but provides more value at scale. Tactical DDD is easier to learn but easier to over-engineer. Know when to use each pattern and, critically, when to NOT use them.

## 2. Mental Model

```
TACTICAL DDD BUILDING BLOCKS

┌─────────────────────────────────────────────┐
│                 AGGREGATE                    │
│  ┌──────────────────┐  ┌──────────────────┐ │
│  │    ENTITY         │  │    ENTITY         │ │
│  │   (Aggregate Root)│  │   (Child Entity)  │ │
│  │   - id: OrderId   │  │   - id: ItemId    │ │
│  │   - customerId    │  │   - productId      │ │
│  │   - status        │  │   - quantity       │ │
│  │   - totalAmount   │  │   - unitPrice      │ │
│  │   - items: List   │  │                    │ │
│  └──────────────────┘  └──────────────────┘ │
│                                               │
│  ┌──────────────────┐  ┌──────────────────┐ │
│  │  VALUE OBJECT    │  │  VALUE OBJECT    │ │
│  │  - Address       │  │  - Money          │ │
│  │  - street        │  │  - amount         │ │
│  │  - city          │  │  - currency       │ │
│  │  - zipCode       │  │                    │ │
│  └──────────────────┘  └──────────────────┘ │
└─────────────────────────────────────────────┘
        │
        │ Persisted as a unit
        ▼
┌─────────────────────────────────────────────┐
│              REPOSITORY                      │
│    save(aggregate), findById(id), delete()   │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│           DOMAIN SERVICE                      │
│   Operations that don't belong to any entity  │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│           DOMAIN EVENT                        │
│   Something important happened in the domain  │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│              FACTORY                          │
│   Complex object creation                     │
└─────────────────────────────────────────────┘
```

## 3. Internal Architecture

### Entity

An object defined by its **identity**, not its attributes. Two `User` objects with the same `userId` are the same user, even if other fields differ.

```java
@Entity
@Table(name = "orders")
public class Order {  // ← Also Aggregate Root
    @EmbeddedId
    private OrderId id;                    // ← Value Object as ID
    
    @Embedded
    private CustomerId customerId;
    
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    
    @Embedded
    private Money totalAmount;
    
    @ElementCollection
    @CollectionTable(name = "order_items")
    private List<OrderItem> items;         // ← Child entities
    
    @Embedded
    private Address deliveryAddress;
    
    // ═══ BEHAVIOR (not just getters/setters) ═══
    
    public void addItem(ProductId productId, Quantity quantity, Money unitPrice) {
        if (status != OrderStatus.DRAFT) {
            throw new OrderDomainException("Can only add items to draft orders");
        }
        OrderItem item = new OrderItem(new OrderItemId(), productId, quantity, unitPrice);
        this.items.add(item);
        recalculateTotal();
    }
    
    public void confirm() {
        if (items.isEmpty()) {
            throw new OrderDomainException("Cannot confirm empty order");
        }
        if (status != OrderStatus.DRAFT) {
            throw new OrderDomainException("Order is not in draft status");
        }
        this.status = OrderStatus.CONFIRMED;
        registerEvent(new OrderConfirmedEvent(this.id, this.customerId, this.totalAmount));
    }
    
    public void cancel(String reason) {
        if (status == OrderStatus.SHIPPED) {
            throw new OrderDomainException("Cannot cancel shipped order");
        }
        if (status == OrderStatus.CANCELLED) {
            return; // Idempotent
        }
        this.status = OrderStatus.CANCELLED;
        registerEvent(new OrderCancelledEvent(this.id, reason));
    }
    
    private void recalculateTotal() {
        this.totalAmount = items.stream()
            .map(OrderItem::getSubtotal)
            .reduce(Money.ZERO, Money::add);
    }
    
    // Protected no-arg constructor for JPA
    protected Order() {
        this.items = new ArrayList<>();
    }
    
    // Factory method
    public static Order create(CustomerId customerId, Address deliveryAddress) {
        Order order = new Order();
        order.id = new OrderId(UUID.randomUUID());
        order.customerId = customerId;
        order.deliveryAddress = deliveryAddress;
        order.status = OrderStatus.DRAFT;
        order.totalAmount = Money.ZERO;
        return order;
    }
}
```

**Key properties of an entity**:
- Has identity (an ID that is unique within its context)
- Mutable (state changes over time)
- Identity equality: `a.equals(b)` compares IDs, not all fields
- Protects invariants (business rules that must always be true)

### Value Object

An object defined by its **attributes**, not identity. Two `Money(100, USD)` objects are interchangeable.

```java
@Embeddable
public class Money {
    @Column(name = "amount")
    private BigDecimal amount;
    
    @Column(name = "currency")
    private Currency currency;
    
    protected Money() {} // For JPA
    
    public Money(BigDecimal amount, Currency currency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be non-negative");
        }
        this.amount = amount;
        this.currency = currency;
    }
    
    public Money add(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot add different currencies");
        }
        return new Money(this.amount.add(other.amount), this.currency);
    }
    
    public Money multiply(int factor) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)), this.currency);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return amount.compareTo(other.amount) == 0 && currency == other.currency;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }
    
    public static final Money ZERO = new Money(BigDecimal.ZERO, Currency.USD);
}
```

**Key properties of a value object**:
- No identity (no ID field)
- Immutable (changes create new instances)
- Attribute equality: `a.equals(b)` compares all attributes
- Self-validating (constructor enforces invariants)
- Replaceable (swap one for another with same values)

### Aggregate and Aggregate Root

An **aggregate** is a cluster of entities and value objects that are treated as a **single unit** for data changes. The **aggregate root** is the single entry point — all external references to entities within the aggregate must go through the root.

```
┌──────────────────────────────────────┐
│           ORDER (Aggregate Root)      │
│                                      │
│  Rules enforced by the root:         │
│  ✗ External code cannot directly    │
│    modify OrderItem.quantity          │
│  ✓ Must call order.addItem(...)      │
│                                      │
│  ┌────────────┐  ┌────────────┐     │
│  │ OrderItem 1 │  │ OrderItem 2 │    │
│  │ product: A  │  │ product: B  │    │
│  │ qty: 2      │  │ qty: 1      │    │
│  └────────────┘  └────────────┘     │
│                                      │
│  ┌────────────┐                      │
│  │ Address     │  (value object)     │
│  │ street: ... │                      │
│  └────────────┘                      │
└──────────────────────────────────────┘

Outside the aggregate:
  ✗ orderItemRepository.save(orderItem)    ← No!
  ✗ order.getItems().get(0).setQuantity(5)  ← No! Exposes internals
  
  ✓ order.addItem(productId, quantity, price) ← Yes!
  ✓ orderRepository.save(order)               ← Yes! Cascade persist
```

**Aggregate design rules**:
1. **Reference by identity**: Other aggregates reference Order by `OrderId`, not by object reference
2. **Small aggregates**: If your aggregate has 20 entities, it's too big. Split it. Average: 1-3 entities per aggregate.
3. **Consistency boundary**: Strong consistency within the aggregate (single transaction). Eventual consistency between aggregates.
4. **One transaction per aggregate**: Never modify two aggregates in one transaction.

### Domain Service

Operations that don't naturally belong to any entity or value object:

```java
@Service
public class PricingService {  // Domain Service, NOT Application Service
    
    public Money calculatePrice(Product product, CustomerTier tier) {
        Money basePrice = product.getBasePrice();
        
        // Complex pricing rules that don't belong in Product
        Discount discount = calculateDiscount(tier);
        Tax tax = calculateTax(product.getCategory(), basePrice);
        
        return basePrice.subtract(discount.amount()).add(tax.amount());
    }
    
    private Discount calculateDiscount(CustomerTier tier) {
        return switch (tier) {
            case PREMIUM -> new Discount(new BigDecimal("0.20"));  // 20%
            case STANDARD -> new Discount(new BigDecimal("0.05")); // 5%
            case BASIC -> Discount.NONE;
        };
    }
}
```

**When to use Domain Service vs Application Service**:
- **Domain Service**: Stateless, pure domain logic, no infrastructure dependencies. Lives in domain layer.
- **Application Service**: Orchestrates use cases, manages transactions, calls repositories. Lives in application layer.

### Domain Event

Something important that happened in the domain:

```java
public class OrderConfirmedEvent extends DomainEvent {
    private final OrderId orderId;
    private final CustomerId customerId;
    private final Money totalAmount;
    private final LocalDateTime confirmedAt;
    
    public OrderConfirmedEvent(OrderId orderId, CustomerId customerId, 
                                Money totalAmount) {
        super(UUID.randomUUID(), LocalDateTime.now());
        this.orderId = orderId;
        this.customerId = customerId;
        this.totalAmount = totalAmount;
        this.confirmedAt = LocalDateTime.now();
    }
}

// In the aggregate:
public void confirm() {
    // ... validate, change state
    registerEvent(new OrderConfirmedEvent(this.id, this.customerId, this.totalAmount));
}

// In the repository or application service:
@Transactional
public void confirmOrder(OrderId orderId) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new OrderNotFoundException(orderId));
    order.confirm();
    orderRepository.save(order);
    
    // Publish all registered events AFTER successful persistence
    order.getDomainEvents().forEach(eventPublisher::publish);
    order.clearDomainEvents();
}
```

### Factory

Complex object creation (when constructor isn't enough):

```java
public class OrderFactory {
    private final ProductRepository productRepository;
    private final PricingService pricingService;
    private final CustomerRepository customerRepository;
    
    public Order createOrder(CreateOrderRequest request) {
        Customer customer = customerRepository.findById(request.getCustomerId())
            .orElseThrow(() -> new CustomerNotFoundException(request.getCustomerId()));
        
        Order order = Order.create(
            new CustomerId(customer.getId()),
            request.getDeliveryAddress()
        );
        
        for (OrderItemRequest itemReq : request.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(itemReq.getProductId()));
            
            Money price = pricingService.calculatePrice(product, customer.getTier());
            
            order.addItem(
                new ProductId(product.getId()),
                new Quantity(itemReq.getQuantity()),
                price
            );
        }
        
        return order;
    }
}
```

### Specification

A predicate that encapsulates a business rule:

```java
public class OrderEligibleForCancellationSpec implements Specification<Order> {
    @Override
    public boolean isSatisfiedBy(Order order) {
        return order.getStatus() != OrderStatus.SHIPPED
            && order.getStatus() != OrderStatus.DELIVERED
            && order.getStatus() != OrderStatus.CANCELLED;
    }
}

// Usage:
if (!new OrderEligibleForCancellationSpec().isSatisfiedBy(order)) {
    throw new OrderCannotBeCancelledException(order.getId());
}
```

Spring Data JPA also supports specifications for query composition:
```java
public interface OrderRepository extends JpaRepository<Order, OrderId>,
        JpaSpecificationExecutor<Order> {
}

List<Order> pendingOrders = orderRepository.findAll(
    where(orderHasStatus(PENDING))
        .and(orderCreatedAfter(lastWeek))
        .and(orderTotalGreaterThan(new Money(100, USD)))
);
```

## 4. Runtime Behavior

### Aggregate Persistence

```
ApplicationService.confirmOrder(orderId)
  │
  ├── 1. Load aggregate from DB
  │      orderRepository.findById(orderId)
  │      SELECT * FROM orders WHERE id = ?
  │      SELECT * FROM order_items WHERE order_id = ?
  │
  ├── 2. Execute domain logic
  │      order.confirm()  ← domain object method
  │      order.registerEvent(OrderConfirmedEvent)
  │
  ├── 3. Persist aggregate
  │      orderRepository.save(order)
  │      UPDATE orders SET status = 'CONFIRMED' WHERE id = ?
  │      (No changes to items, so no item queries)
  │
  └── 4. Publish domain events
         eventPublisher.publish(order.getDomainEvents())
```

### Invariant Enforcement Timeline

```
Time →

T0: Order created (DRAFT)
    Invariant: items can be empty
    Allowed: addItem(), removeItem()
    Forbidden: confirm() ← requires at least 1 item

T1: Items added (DRAFT)
    Invariant: totalAmount = sum(items.subtotal)
    Allowed: confirm(), cancel(), addItem()
    Forbidden: ship()

T2: Order confirmed (CONFIRMED)
    Invariant: at least 1 item, payment is pending
    Allowed: pay(), cancel()
    Forbidden: addItem(), removeItem() ← items frozen

T3: Order shipped (SHIPPED)
    Invariant: payment confirmed, items packed
    Allowed: deliver(), return()
    Forbidden: cancel() ← Cannot cancel after shipping
```

## 5. Request Flow Diagrams

### Rich Domain Model Flow

```
POST /orders/{id}/confirm
  │
  ▼
OrderController.confirmOrder(id)
  │ // Thin controller: no business logic
  ▼
ConfirmOrderService.confirm(id)
  │ // Application service: orchestration
  ├── order = orderRepository.findById(id)
  │     │
  │     ▼
  │   Order.confirm()  ← Domain object enforces rules:
  │     checks status == DRAFT
  │     checks items not empty
  │     changes status → CONFIRMED
  │     registers OrderConfirmedEvent
  │
  ├── orderRepository.save(order)
  │
  ├── eventPublisher.publish(order.getDomainEvents())
  │     │
  │     ▼ (async)
  │   PaymentService listens to OrderConfirmedEvent
  │   NotificationService listens to OrderConfirmedEvent
  │
  └── return OrderDto.from(order)
```

### Anemic Domain Model Flow (Anti-Pattern)

```
POST /orders/{id}/confirm
  │
  ▼
OrderController.confirmOrder(id)
  │
  ▼
OrderService.confirmOrder(id)  ← 500 lines
  │
  │ // ALL business logic here:
  ├── order = orderRepository.findById(id)
  ├── if (order.getStatus() != "DRAFT") throw ...
  ├── if (order.getItems().isEmpty()) throw ...
  ├── order.setStatus("CONFIRMED")  ← setter!
  ├── order.setUpdatedAt(now)
  ├── orderRepository.save(order)
  ├── // payment logic...
  ├── // notification logic...
  └── return toDto(order)
```

The anemic model has the same behavior but scatters it across services. The rich model encapsulates behavior in the domain object where it belongs.

## 6. Lifecycle Diagrams

### Aggregate Lifecycle

```
Creation:
  Factory.createOrder(cmd) → new Order(DRAFT)
  or
  new Order(customerId, address)  (factory method)

Active:
  order.addItem(product, qty, price)
  order.removeItem(itemId)
  order.confirm()
  order.pay()
  
Terminal:
  order.cancel(reason)     → CANCELLED
  order.markAsDelivered()  → DELIVERED
  order.markAsReturned()   → RETURNED
```

### When to Split an Aggregate

```
When:
  - Two entities have independent lifecycles
  - One part of the aggregate changes 1000x more often than the rest
  - Business invariants don't span both parts
  - Aggregate has many entities (anti-pattern: large aggregate)

How:
  1. Identify the splitting boundary (where invariants are loose)
  2. Create new aggregate with its own root
  3. Replace object references with ID references
  4. Use domain events for coordination between them
```

## 7. Source Code Reading Guide

1. **Axon Framework**: Best reference implementation of tactical DDD in Java
   - `org.axonframework.modelling.command.AggregateRoot`
   - `org.axonframework.eventsourcing.EventSourcingAggregate`
   
2. **jMolecules**: Annotation library for DDD building blocks
   - `org.jmolecules.ddd.annotation.Entity`
   - `org.jmolecules.ddd.annotation.AggregateRoot`
   - `org.jmolecules.ddd.annotation.ValueObject`

3. **Spring Data JPA Specifications**:
   - `org.springframework.data.jpa.domain.Specification`
   - See how Spring transforms Specifications into JPA Criteria queries

## 8. Production Failure Scenarios

### Scenario 1: Large Aggregate Causes Database Contention

**Symptom**: Optimistic locking failures on every concurrent order update.

**Root cause**: The `Order` aggregate includes `Customer` profile, `Payment` history, and `Shipping` tracking - 15+ entities. Every update locks the entire aggregate.

**Resolution**: Split into `Order`, `Payment`, and `Shipment` aggregates. Reference by ID. Accept eventual consistency between them.

### Scenario 2: Anemic Model Leads to Business Logic Duplication

**Symptom**: The same business rule (e.g., "order cannot be cancelled after shipping") is implemented in 3 different services with slightly different logic.

**Root cause**: Business logic lives in services, not domain objects. Every service re-implements the same rules.

**Resolution**: Move invariants into the domain object. `order.cancel()` checks its own state. Services call `order.cancel()` and trust the domain object to enforce the rule.

### Scenario 3: Domain Events Lost in Transaction Rollback

**Symptom**: Payment is processed twice for the same order.

**Root cause**: Domain event was published BEFORE the transaction committed. Transaction rolled back, but the event was already consumed. Consumer acted on invalid data.

**Resolution**: Use `@TransactionalEventListener(phase = AFTER_COMMIT)`. Only publish events after the transaction is confirmed committed. Consumers will only see events for committed data.

## 9. Debugging Techniques

### Tracing Aggregate State Changes

```java
// Add change tracking to your aggregate base class
public abstract class AggregateRoot<T extends EntityId> {
    private final List<DomainEvent> changes = new ArrayList<>();
    
    protected void apply(DomainEvent event) {
        changes.add(event);
        mutate(event); // Apply event to state
    }
    
    // For debugging: log every state change
    // In production: event sourcing, audit log
}
```

### Finding Anemic Domain Models

Run ArchUnit or a custom static analysis:
```java
// Detect setters on entities (anemia indicator)
classes().that().areAnnotatedWith(Entity.class)
    .should().haveSimpleNameEndingWith("")
    // A heuristic: if Entity has >5 setters and <3 behavioral methods, it's likely anemic
```

## 10. Observability Considerations

Domain events are observability gold:
- Every domain event is a timestamped record of "something important happened"
- Correlate domain events with HTTP requests via traceId
- Aggregate domain events into business metrics: `orders_placed`, `orders_confirmed`, `orders_cancelled`

## 11. Performance Implications

| Pattern | Performance Impact |
|---------|-------------------|
| Small aggregates | Lower lock contention, better concurrency |
| Large aggregates | Higher lock contention, more data loaded per transaction |
| Value objects (embedded) | Fewer joins (embedded in parent table) |
| Entity references by ID | More queries (but fewer joins, better caching) |
| Domain events | Async overhead negligible, better decoupling |
| Specifications | Translates to SQL WHERE clauses, minimal overhead |

## 12. Architecture Implications

### When to Use Rich Domain Model
- Complex business rules (many invariants)
- Business logic changes frequently
- Domain experts are available
- Long-lived system

### When Anemic Model is Acceptable
- Simple CRUD with no complex rules
- Data-focused applications (reporting, analytics)
- Short-lived systems
- Team unfamiliar with DDD (anemic is better than wrong DDD)

### DDD Anti-Patterns
1. **DDD for everything**: If your system is a glorified spreadsheet, DDD is overhead
2. **DDD without domain experts**: You're just guessing at boundaries
3. **Injecting repositories into entities**: Entities should not access persistence
4. **Putting everything in one aggregate**: "Single aggregate system" - the worst of all worlds
5. **Event sourcing without need**: Adds enormous complexity unless you need audit trail or temporal queries

## 13. Team Ownership Implications

Tactical DDD patterns are typically owned within a single team (one bounded context). Cross-team decisions:
- Shared value objects (Money, Address) belong in shared kernel
- Domain events crossing context boundaries need versioned schemas
- Aggregate boundaries should align with team boundaries

## 14. Interview Questions

1. **"When is an anemic domain model acceptable?"**
   - **Answer**: When the system has no complex business rules. A reporting dashboard, a configuration management tool, or a simple CRUD API doesn't benefit from rich domain models. The complexity of DDD patterns must be justified by domain complexity. Never use DDD patterns "just because."

2. **"How big should an aggregate be?"**
   - **Answer**: As small as possible while still enforcing invariants. Most aggregates should be a single entity. If you have more than 3-5 entities, reconsider. The default should be ONE entity per aggregate. Only add child entities if a business rule requires consistency between them within a single transaction.

3. **"How do you handle a use case that modifies two aggregates?"**
   - **Answer**: You don't modify them in one transaction. Accept eventual consistency. Use domain events: Aggregate A changes and publishes an event. Aggregate B's event handler changes B. If B's handler fails, publish a failure event. Aggregate A's compensation handler reverts the change. This is the saga pattern — complex but necessary.

## 15. Hands-On Exercises

1. **Refactor an anemic model**: Take a setter/getter entity with business logic in the service. Move logic into entity methods. Compare the code.

2. **Design an aggregate boundary**: Given an e-commerce domain with Order, OrderItem, Payment, Shipment, Customer, Address — decide which belong together as aggregates. Justify each boundary.

3. **Implement a Specification**: Replace a service method with 5 if-checks with composable Specifications. Test query composition.

## 16. Advanced Challenges

1. **Implement event sourcing**: Design an `Order` aggregate that stores state as a sequence of events (OrderCreated → ItemAdded → OrderConfirmed) rather than current state. Implement replay.

2. **Design a CQRS read model**: After implementing event sourcing, build a read model projection that optimizes for query patterns. Handle eventual consistency between write and read models.

3. **Implement snapshotting**: For event sourcing, implement periodic snapshots to avoid replaying thousands of events. Handle snapshot creation, storage, and recovery.
