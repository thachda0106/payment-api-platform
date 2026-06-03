# Session 21: Domain Layer Design

## 1. Why This Topic Exists

The domain layer is where your application either earns its paycheck or becomes a liability. When every business rule lives in 800-line service classes operating on getter/setter entities, you have an anemic domain model — objects that carry data but no behavior, a design that Martin Fowler called "contrary to the basic idea of object-oriented design." The cost surfaces slowly: duplicated validation across ten service methods, business rules that diverge between the web endpoint and the batch job, entities whose invariants can be broken at any time by any caller with a setter, and "bug fixes" that fix the symptom in one place while leaving five other violations untouched.

Conversely, a rich domain model — where entities enforce their own invariants, value objects validate themselves on construction, and aggregates define clear consistency boundaries — localizes each business rule to a single place. When the rule changes, you change one class. When the rule is violated, the exception stack trace points directly at the offending domain object, not at line 347 of `OrderServiceImpl`.

**Staff engineer insight**: The domain model is a deliberate choice, not a default. Spring Boot and Spring Data JPA heavily encourage the anemic model (`@Entity` with getters/setters + `@Service` with all logic) because it's the path of least resistance. The engineer who chooses a rich domain model must fight the framework's gravity. The engineer who accepts the anemic model must build compensating controls: exhaustive testing, static analysis rules, and a team discipline that service methods only orchestrate. Either path works — but only if chosen consciously, with full understanding of the trade-offs that will accumulate over years of maintenance.

## 2. Mental Model

```
The Domain Layer Decision Space:

  Anemic Model:                          Rich Model:
  
  Entity = Data Bag                      Entity = Behavior + Data
  Service = All Logic                    Service = Orchestration Only
  
  Order {                                 Order {
    - Long id;                             - OrderId id;
    - String status;  // getters/setters   - Money total;
    - List<Item> items;                    - OrderStatus status;
    - Money total;                         - List<LineItem> items;
  }                                        + void place() { validate(); self validate(); }
                                           + void cancel() { checkCanCancel(); self validate(); }
  OrderService {                           + Money calculateTotal() { ... }
    void place(Order o) {                 }
      if (o.total < 0) throw;              OrderPlacementService {
      if (items empty) throw;                @Transactional
      o.status = "PLACED";                  void place(PlaceOrderCommand c) {
      repo.save(o);                           Order order = Order.place(c);
    }                                          repo.save(order);
  }                                            eventPublisher.publish(order.getEvents());
                                             }
  Problems:                               }
   * Logic scattered across services      Benefits:
   * Entity can be in invalid state        * Single source of truth for each rule
   * No encapsulation                     * Entity always in valid state
   * Hard to unit test domain logic        * Domain logic testable without Spring
   * Business rules duplicated            * Changes local to the entity
```

```
Core Domain Building Blocks:

  +---------------------------------------------------------+
  | ENTITY                                                  |
  |                                                         |
  | * Defined by IDENTITY, not attributes                  |
  | * Mutable (with rules), has lifecycle                  |
  | * Equality: same ID = same entity                      |
  | * Thread of continuity through state changes           |
  |                                                         |
  |  Example: Order, Customer, Invoice, Shipment            |
  +---------------------------------------------------------+

  +---------------------------------------------------------+
  | VALUE OBJECT                                            |
  |                                                         |
  | * Defined by ATTRIBUTES, not identity                  |
  | * Immutable                                            |
  | * Self-validating on construction                      |
  | * Equality: all attributes equal = same value          |
  | * Freely replaceable                                   |
  |                                                         |
  |  Example: Money, Email, Address, OrderNumber            |
  +---------------------------------------------------------+

  +---------------------------------------------------------+
  | AGGREGATE                                               |
  |                                                         |
  | * Cluster of entities + value objects                   |
  | * One entity is the AGGREGATE ROOT                      |
  | * External references ONLY to the root                  |
  | * Consistency boundary: invariants within aggregate     |
  |   are always satisfied after any operation              |
  | * Transaction boundary = aggregate boundary            |
  |                                                         |
  |  Example: Order (root) + LineItem[] + ShippingAddress   |
  |  Example: Customer (root) + Address[] + PaymentMethod[] |
  +---------------------------------------------------------+

  +---------------------------------------------------------+
  | DOMAIN EVENT                                            |
  |                                                         |
  | * Something that happened in the domain                 |
  | * Immutable, named in past tense                        |
  | * Carries enough data for consumers to react            |
  | * Published by aggregate root                          |
  |                                                         |
  |  Example: OrderPlaced, PaymentAuthorized,               |
  |           ShipmentDelivered, CustomerUpgraded           |
  +---------------------------------------------------------+

  +---------------------------------------------------------+
  | DOMAIN SERVICE                                          |
  |                                                         |
  | * Stateless operation that doesn't belong to an entity  |
  | * Operates on domain objects                           |
  | * No transaction management (caller handles that)       |
  |                                                         |
  |  Example: PricingService, FraudDetectionService         |
  +---------------------------------------------------------+
```

## 3. Internal Architecture

### Rich Domain Model -- Same Feature, Two Ways

```java
// === ANEMIC MODEL (common but problematic) ===

@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue
    private Long id;
    private Long customerId;
    private String status;
    private BigDecimal totalAmount;
    private LocalDateTime createdAt;
    
    @OneToMany(mappedBy = "order", cascade = ALL)
    private List<OrderItem> items = new ArrayList<>();
    
    // Getters and setters for EVERYTHING
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public List<OrderItem> getItems() { return items; }
    // ... more getters/setters
}

// All logic lives in services:
@Service
@Transactional
public class OrderService {
    public Order placeOrder(PlaceOrderRequest request) {
        // Validation scattered here:
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Items required");
        }
        if (request.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Total must be positive");
        }
        
        Order order = new Order();
        order.setCustomerId(request.getCustomerId());
        order.setStatus("PENDING");
        order.setCreatedAt(LocalDateTime.now());
        
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemReq : request.getItems()) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setSku(itemReq.getSku());
            item.setQuantity(itemReq.getQuantity());
            item.setUnitPrice(itemReq.getUnitPrice());
            // Price validation duplicated in another service method
            order.getItems().add(item);
            total = total.add(itemReq.getUnitPrice()
                    .multiply(BigDecimal.valueOf(itemReq.getQuantity())));
        }
        order.setTotalAmount(total);
        // Bug: This validation should be on Order but is here
        // Bug: Another developer wrote the same logic in BatchOrderService
        // Bug: Nothing prevents order.setStatus("INVALID_STATE") anywhere
        
        return orderRepo.save(order);
    }
    
    public void cancelOrder(Long orderId) {
        Order order = orderRepo.findById(orderId).orElseThrow();
        // Business rule duplicated here and in OrderController
        if ("SHIPPED".equals(order.getStatus())) {
            throw new IllegalStateException("Cannot cancel shipped order");
        }
        order.setStatus("CANCELLED");
        orderRepo.save(order);
    }
}


// === RICH DOMAIN MODEL (same feature) ===

// Value objects -- immutable, self-validating:
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException(
                    "Amount scale exceeds currency fraction digits");
        }
    }
    
    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }
    
    public Money add(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot add different currencies: " + 
                    this.currency + " vs " + other.currency);
        }
        return new Money(this.amount.add(other.amount), this.currency);
    }
    
    public Money multiply(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }
    
    public boolean isGreaterThan(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }
    
    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }
    
    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch");
        }
    }
}

public record OrderId(UUID value) {
    public OrderId {
        Objects.requireNonNull(value, "OrderId must not be null");
    }
    
    public static OrderId generate() {
        return new OrderId(UUID.randomUUID());
    }
}

public enum OrderStatus {
    PENDING, CONFIRMED, PAID, SHIPPED, DELIVERED, CANCELLED, REFUNDED;
    
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING  -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == PAID || target == CANCELLED;
            case PAID     -> target == SHIPPED || target == REFUNDED;
            case SHIPPED  -> target == DELIVERED;
            case DELIVERED -> target == REFUNDED;
            case CANCELLED, REFUNDED -> false;
        };
    }
}

// Value object for line items:
@Embeddable
public record LineItem(String sku, int quantity, Money unitPrice) {
    public LineItem {
        Objects.requireNonNull(sku, "SKU must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        Objects.requireNonNull(unitPrice, "Unit price must not be null");
        if (!unitPrice.isPositive()) {
            throw new IllegalArgumentException("Unit price must be positive");
        }
    }
    
    public Money subtotal() {
        return unitPrice.multiply(quantity);
    }
}

// Rich entity -- behavior + data, private setters:
@Entity
@Table(name = "orders")
public class Order extends AbstractAggregateRoot<Order> {
    
    @EmbeddedId
    private OrderId id;
    
    @Column(name = "customer_id")
    private Long customerId;
    
    @Enumerated(STRING)
    private OrderStatus status;
    
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount", 
                column = @Column(name = "total_amount")),
        @AttributeOverride(name = "currency", 
                column = @Column(name = "currency"))
    })
    private Money total;
    
    @ElementCollection
    @CollectionTable(name = "order_items", 
            joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "item_index")
    private List<LineItem> items = new ArrayList<>();
    
    @Version
    private Long version;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // NO-ARG CONSTRUCTOR FOR JPA ONLY (protected, not public)
    protected Order() {}
    
    // STATIC FACTORY METHOD -- the only way to create a valid Order:
    public static Order place(PlaceOrderCommand command) {
        Order order = new Order();
        order.id = OrderId.generate();
        order.customerId = command.customerId();
        order.status = OrderStatus.PENDING;
        order.createdAt = command.timestamp();
        order.updatedAt = command.timestamp();
        
        // Domain logic: build line items and calculate total
        Money total = Money.zero(command.currency());
        for (LineItemCommand itemCmd : command.items()) {
            LineItem item = new LineItem(
                    itemCmd.sku(), 
                    itemCmd.quantity(), 
                    itemCmd.unitPrice());
            order.items.add(item);
            total = total.add(item.subtotal());
        }
        
        // Domain invariant: order must have at least one item
        if (order.items.isEmpty()) {
            throw new IllegalArgumentException("Order must have at least one item");
        }
        
        order.total = total;
        
        // Register domain event:
        order.registerEvent(new OrderPlaced(
                order.id, order.customerId, order.total, order.createdAt));
        
        return order;
    }
    
    // BEHAVIOR METHODS -- the ONLY way to change state:
    public void cancel(String reason) {
        if (!this.status.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new IllegalStateException(
                    "Cannot cancel order in status " + this.status);
        }
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
        
        registerEvent(new OrderCancelled(this.id, reason, this.updatedAt));
    }
    
    public void confirm() {
        if (!this.status.canTransitionTo(OrderStatus.CONFIRMED)) {
            throw new IllegalStateException(
                    "Cannot confirm order in status " + this.status);
        }
        this.status = OrderStatus.CONFIRMED;
        this.updatedAt = LocalDateTime.now();
        
        registerEvent(new OrderConfirmed(this.id, this.updatedAt));
    }
    
    public void markAsPaid() {
        if (!this.status.canTransitionTo(OrderStatus.PAID)) {
            throw new IllegalStateException(
                    "Cannot mark as paid in status " + this.status);
        }
        this.status = OrderStatus.PAID;
        this.updatedAt = LocalDateTime.now();
        
        registerEvent(new OrderPaid(this.id, this.total, this.updatedAt));
    }
    
    public Money calculateTotal() {
        return this.total;
    }
    
    // IMMUTABLE or UNMODIFIABLE access for external reading:
    public OrderId getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public Money getTotal() { return total; }
    public List<LineItem> getItems() { 
        return Collections.unmodifiableList(items);  // Cannot modify externally
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }
}

// Application Service -- thin orchestration:
@Service
public class OrderPlacementService {
    private final OrderRepository orderRepo;
    
    @Transactional
    public PlaceOrderResult placeOrder(PlaceOrderCommand command) {
        // Domain logic encapsulated in the entity:
        Order order = Order.place(command);
        orderRepo.save(order);
        
        // publishAllEventsAfterCommit() is inherited from AbstractAggregateRoot
        // Events are published AFTER the transaction commits
        
        return PlaceOrderResult.from(order);
    }
}

// Repository -- simple interface:
public interface OrderRepository extends JpaRepository<Order, OrderId> {
    // No finder methods that bypass aggregate root
    // No direct access to LineItems (they're inside the aggregate)
}
```

### Value Object JPA Mapping with @Embeddable

```java
// Money value object mapped as @Embeddable:
@Embeddable
public class Money {
    
    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;
    
    @Column(name = "currency", length = 3)
    private String currencyCode;
    
    // JPA constructor (package-private or protected):
    protected Money() {}
    
    // Domain constructor:
    public Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currencyCode = currency.getCurrencyCode();
        validate();
    }
    
    private void validate() {
        if (amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("Currency must not be null");
        }
    }
    
    // Transient (not persisted) helper:
    @Transient
    public Currency getCurrency() {
        return Currency.getInstance(currencyCode);
    }
    
    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(
                this.amount.add(other.amount), 
                getCurrency());
    }
    
    // equals/hashCode based on ALL attributes (value object semantics):
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money that)) return false;
        return Objects.equals(amount, that.amount) &&
               Objects.equals(currencyCode, that.currencyCode);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(amount, currencyCode);
    }
}

// Address value object:
@Embeddable
public class Address {
    @Column(name = "street")
    private String street;
    
    @Column(name = "city")
    private String city;
    
    @Column(name = "postal_code")
    private String postalCode;
    
    @Column(name = "country_code", length = 2)
    private String countryCode;
    
    protected Address() {}
    
    public Address(String street, String city, String postalCode, String countryCode) {
        this.street = street;
        this.city = city;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
        validate();
    }
    
    private void validate() {
        if (street == null || street.isBlank()) 
            throw new IllegalArgumentException("Street is required");
        if (city == null || city.isBlank()) 
            throw new IllegalArgumentException("City is required");
        if (postalCode == null || postalCode.isBlank()) 
            throw new IllegalArgumentException("Postal code is required");
        if (countryCode == null || countryCode.length() != 2) 
            throw new IllegalArgumentException("Country code must be 2 characters");
    }
}

// Email value object:
@Embeddable
public class EmailAddress {
    @Column(name = "email")
    private String value;
    
    private static final Pattern EMAIL_PATTERN = 
            Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
    
    protected EmailAddress() {}
    
    public EmailAddress(String value) {
        if (value == null || !EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid email: " + value);
        }
        this.value = value.toLowerCase();
    }
    
    public String getDomain() {
        return value.substring(value.indexOf('@') + 1);
    }
    
    public String getValue() {
        return value;
    }
}

// Using @Embeddable value objects in an entity:
@Entity
@Table(name = "customers")
public class Customer {
    @EmbeddedId
    private CustomerId id;
    
    @Column(name = "name")
    private String name;
    
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "value", 
                column = @Column(name = "email"))
    })
    private EmailAddress email;
    
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "street", 
                column = @Column(name = "shipping_street")),
        @AttributeOverride(name = "city", 
                column = @Column(name = "shipping_city")),
        @AttributeOverride(name = "postalCode", 
                column = @Column(name = "shipping_postal_code")),
        @AttributeOverride(name = "countryCode", 
                column = @Column(name = "shipping_country_code"))
    })
    private Address shippingAddress;
}
```

### Domain Events: AbstractAggregateRoot Internals

```java
// Source: org.springframework.data.domain.AbstractAggregateRoot

public class AbstractAggregateRoot<A extends AbstractAggregateRoot<A>> {
    
    // Transient list: events are NOT persisted, only held in memory
    @Transient
    private final List<Object> domainEvents = new ArrayList<>();
    
    // Register an event. Called from entity behavior methods.
    protected <T> T registerEvent(T event) {
        Assert.notNull(event, "Domain event must not be null");
        this.domainEvents.add(event);
        return event;
    }
    
    // Called by Spring Data after save() to retrieve and clear events.
    @DomainEvents
    @JsonIgnore
    public Collection<Object> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }
    
    // Called by Spring Data after publishing to clear the event list.
    @AfterDomainEventPublication
    @JsonIgnore
    public void clearDomainEvents() {
        this.domainEvents.clear();
    }
}

// Source: org.springframework.data.repository.core.support
//         RepositoryFactorySupport.EventPublishingMethodInterceptor

// When you call orderRepo.save(order), Spring Data checks if the entity
// implements AggregateRoot and has @DomainEvents methods.

// After save(), an EventPublishingMethodInterceptor intercepts and:
// 1. Calls @DomainEvents method to get the event list
// 2. Publishes each event via ApplicationEventPublisher
// 3. Calls @AfterDomainEventPublication method to clear the list

// The key architectural point: events are published AFTER the save()
// but WITHIN the @Transactional boundary. If the transaction commits,
// @TransactionalEventListener(phase=AFTER_COMMIT) picks up the events.
// If the transaction rolls back, no listener fires.

// Full flow:
@Transactional
public PlaceOrderResult placeOrder(PlaceOrderCommand cmd) {
    Order order = Order.place(cmd);  // Events: [OrderPlaced]
    // domainEvents = [OrderPlaced{orderId=..., total=...}] (in memory)
    
    orderRepo.save(order);
    // Spring Data's EventPublishingMethodInterceptor fires:
    //   -> Calls order.domainEvents() -> [OrderPlaced]
    //   -> Publishes OrderPlaced via ApplicationEventPublisher
    //   -> Calls order.clearDomainEvents() -> domainEvents = []
    // 
    // At this point, @TransactionalEventListener(phase=BEFORE_COMMIT)
    // handlers have already executed. AFTER_COMMIT handlers are queued
    // in TransactionSynchronization and will fire after commit.
    
    return PlaceOrderResult.from(order);
}  // -> Transaction commits
   // -> TransactionSynchronization.afterCommit():
   //    -> @TransactionalEventListener(phase=AFTER_COMMIT) fires
   //    -> onOrderPlaced(OrderPlaced event) executes
   //       -> Sends confirmation email
   //       -> Updates analytics
   //       -> Notifies fulfillment system
```

### Aggregate Design with Optimistic Locking

```java
// The AGGREGATE consistency boundary:
// External code should ONLY reference the aggregate root (Order).
// LineItem is part of the Order aggregate -- never referenced directly.

@Entity
@Table(name = "orders")
public class Order extends AbstractAggregateRoot<Order> {
    
    @EmbeddedId
    private OrderId id;
    
    @Version  // Optimistic concurrency control
    private Long version;
    
    @ElementCollection
    @CollectionTable(name = "order_items",
            joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "item_index")
    private List<LineItem> items = new ArrayList<>();
    
    @Embedded
    private Money total;
    
    // DOMAIN INVARIANT: Total must equal sum of line item subtotals
    public void addItem(LineItem newItem) {
        this.items.add(newItem);
        recalculateTotal();
        registerEvent(new OrderItemAdded(this.id, newItem.sku(), 
                newItem.quantity()));
    }
    
    public void removeItem(String sku) {
        LineItem item = items.stream()
                .filter(i -> i.sku().equals(sku))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Item not found: " + sku));
        
        if (items.size() <= 1) {
            throw new IllegalStateException(
                    "Cannot remove last item from order");
        }
        
        items.remove(item);
        recalculateTotal();
        registerEvent(new OrderItemRemoved(this.id, sku));
    }
    
    private void recalculateTotal() {
        this.total = items.stream()
                .map(LineItem::subtotal)
                .reduce(Money.zero(Currency.getInstance("USD")), 
                        Money::add);
    }
    
    // @Version ensures that if two concurrent requests read the same Order,
    // only ONE succeeds at updating:
    // 
    // Thread 1 reads: Order{id=1, version=3}
    // Thread 2 reads: Order{id=1, version=3} (same version)
    // Thread 1 save: UPDATE orders SET ..., version=4 WHERE id=1 AND version=3
    //              -> SUCCESS (row matched, version incremented)
    // Thread 2 save: UPDATE orders SET ..., version=4 WHERE id=1 AND version=3
    //              -> FAILS (version is now 4, WHERE version=3 doesn't match)
    //              -> ObjectOptimisticLockingFailureException
    // 
    // This enforces the invariant: within this aggregate, 
    // only one concurrent modification can succeed.
}
```

### Entity Identity Strategies

```java
// --- Strategy 1: Auto-Increment (Long/Integer) ---
@Entity
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // Pros: Simple, space-efficient, fast B-tree index, sequential
    // Cons: Exposes row count, can't assign before persist, 
    //       not portable across databases, no distributed generation
    // Best for: Single-database, moderate scale, internal systems
}

// --- Strategy 2: UUID ---
@Entity
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    // Pros: Can generate in application code (before persist), 
    //       globally unique, no coordination, no sequential exposure
    // Cons: 16 bytes vs 8, slower B-tree index (random insertion),
    //       larger storage
    // Best for: Distributed systems, multi-database, public-facing IDs
    
    // Performance note: Use UUID v7 (time-ordered) for better B-tree indexing
    // Hibernate 6 supports @IdGeneratorType with custom UUID generators
}

// --- Strategy 3: Natural Key ---
@Entity
public class Customer {
    @Id
    @Column(length = 20)
    private String customerCode;  // e.g., "CUST-00042"
    // Pros: Meaningful, no synthetic key, domain-aligned
    // Cons: Can change (even when "immutable"), cascading updates,
    //       join tables store the full value, harder to change schema
    // Best for: Truly immutable identifiers (country codes, currency codes)
    // Avoid for: Most entity types -- synthetic keys are safer
}

// --- Strategy 4: Embedded ID with typed wrapper ---
@Embeddable
public record OrderId(UUID value) {
    public OrderId {
        Objects.requireNonNull(value);
    }
    public static OrderId generate() { return new OrderId(UUID.randomUUID()); }
}

// Entity equality based on ID:
@Entity
public class Order {
    @EmbeddedId
    private OrderId id;
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order that)) return false;
        return Objects.equals(id, that.id);
        // Equality by IDENTITY, not by all attributes
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
```

### Domain Services -- When Logic Doesn't Belong to an Entity

```java
// Domain Service: pure business logic that spans multiple entities
// or is stateless algorithmic computation.

// EXAMPLE 1: Pricing Service (cross-product discount rules):
@Component
public class PricingService {
    
    public Money calculatePrice(List<LineItemCommand> items, 
            CustomerTier tier, String couponCode) {
        
        // 1. Calculate base price from items
        Money basePrice = items.stream()
                .map(item -> item.unitPrice().multiply(item.quantity()))
                .reduce(Money.zero(Currency.getInstance("USD")), Money::add);
        
        // 2. Apply tier discount
        Money afterTier = applyTierDiscount(basePrice, tier);
        
        // 3. Apply coupon discount
        Money afterCoupon = applyCoupon(afterTier, couponCode);
        
        return afterCoupon;
    }
    
    private Money applyTierDiscount(Money price, CustomerTier tier) {
        return switch (tier) {
            case GOLD -> price.multiply(90).divide(100);    // 10% off
            case SILVER -> price.multiply(95).divide(100);  // 5% off
            case BRONZE -> price;                            // no discount
        };
    }
    
    private Money applyCoupon(Money price, String couponCode) {
        // Pure computational logic -- no database calls, no I/O
        // ...
    }
}

// EXAMPLE 2: Fraud Detection Service:
@Component
public class FraudDetectionService {
    
    public FraudAssessment assess(Order order, Customer customer, 
            PaymentMethod paymentMethod) {
        
        int riskScore = 0;
        List<String> flags = new ArrayList<>();
        
        // Rule 1: High-value order from new customer
        if (order.getTotal().amount().compareTo(new BigDecimal("10000")) > 0
                && customer.getDaysSinceRegistration() < 30) {
            riskScore += 30;
            flags.add("HIGH_VALUE_NEW_CUSTOMER");
        }
        
        // Rule 2: Multiple orders in short time
        if (customer.getRecentOrderCount(1, TimeUnit.HOURS) > 5) {
            riskScore += 25;
            flags.add("RAPID_ORDERING");
        }
        
        // Rule 3: Billing/shipping address mismatch
        if (!customer.getBillingAddress().getCountryCode()
                .equals(customer.getShippingAddress().getCountryCode())) {
            riskScore += 15;
            flags.add("ADDRESS_MISMATCH");
        }
        
        return new FraudAssessment(riskScore, flags);
    }
}

// Distinction from Application Service:
// Application Service: ORCHESTRATES the use case
// Domain Service: COMPUTES a domain value/decision
// 
// Application Service calls Domain Services, not the other way around.
```

### Spring Data Entity as Domain Object vs Separate Models

```
  +------------------------------------------------------------------+
  |     ENTITY AS DOMAIN OBJECT      vs    SEPARATE DOMAIN/DB MODEL   |
  +------------------------------------------------------------------+

  APPROACH 1: Entity IS the domain object (common in Spring Boot):
  
  +-----------+     +-----------+     +-----------+
  | @Entity   |<--->| JPA       |<--> | Database  |
  | Domain    |     | Entity    |     | Table     |
  | Logic     |     | Manager   |     |           |
  +-----------+     +-----------+     +-----------+
  
  Pros:
    * Less code (one class for domain + persistence)
    * No mapping layer between domain and persistence
    * Spring Data JPA works directly with domain objects
    * Simpler for teams new to DDD
  
  Cons:
    * JPA constraints leak into domain model (@Entity, @Id, @Column)
    * JPA inheritance strategies conflict with domain inheritance
    * Lazy loading struggles with rich domain models
    * Difficult to design aggregate boundaries with JPA relationships
    * Hard to unit test domain logic without JPA/Hibernate


  APPROACH 2: Separate domain model and persistence model:
  
  +-----------+           +-----------+     +-----------+
  | Domain    |  <----->  | Persist.  |<--->| Database  |
  | Model     |  Mapper   | Model     |     | Table     |
  | (pure POJO)           | (@Entity) |     |           |
  +-----------+           +-----------+     +-----------+
  
  Pros:
    * Domain model has ZERO framework dependencies
    * Domain logic unit-testable without Spring or JPA
    * JPA mapping optimized for database, domain for business rules
    * Can evolve domain model and database schema independently
    * Clean aggregate boundaries (no JPA relationship leaks)
  
  Cons:
    * Twice as many classes
    * Mapping layer adds complexity and bug surface
    * Duplicate field declarations
    * More ceremony for simple CRUD entities
    * Team must agree on and maintain mapping conventions

  DECISION FRAMEWORK:
  
  Use Entity-as-Domain when:
    * Domain is CRUD-heavy with simple business rules
    * Team is small, velocity matters more than purity
    * Entities are small with few JPA constraints leaking
    * You accept that @Entity annotations are an acceptable trade-off
  
  Use Separate Models when:
    * Domain has complex business rules and invariants
    * Multiple persistence mechanisms (JPA + Redis + Elasticsearch)
    * Domain model needs to be framework-agnostic
    * Aggregate boundaries diverge from JPA relationship model
    * You need different read and write models (CQRS)
```

## 4. Runtime Behavior

### Rich Domain Model Object Lifecycle

```
  Complete lifecycle of an Order aggregate:

  1. CREATION (PlaceOrderCommand -> static factory -> valid Order):
     Order order = Order.place(command);
     
     Internal state:
       id = OrderId.generate()       -> UUID
       customerId = cmd.customerId() -> 42L
       status = PENDING
       items = [LineItem(sku="A1", qty=2, price=$10), 
                LineItem(sku="B2", qty=1, price=$20)]
       total = Money($40, USD)       -> computed from items
       version = null                -> assigned by JPA on persist
       createdAt = now()
       updatedAt = now()
       domainEvents = [OrderPlaced{orderId=..., total=$40}]
     
     Invariants satisfied:
       - At least one item (enforced in factory)
       - Total = sum(item subtotals) (computed, not settable externally)
       - Status = PENDING (new orders always start PENDING)

  2. PERSISTENCE (JPA save):
     orderRepo.save(order);
     
     JPA actions:
       - INSERT INTO orders (id, customer_id, status, total_amount, 
                             currency, created_at, updated_at, version)
       - INSERT INTO order_items (order_id, item_index, sku, 
                                   quantity, unit_price_amount, 
                                   unit_price_currency)
       - @DomainEvents: publishes OrderPlaced via ApplicationEventPublisher
       - @AfterDomainEventPublication: clears domainEvents list
     
     In-memory state:
       domainEvents = []

  3. STATE TRANSITION (behavior method):
     order.confirm();
     
     Validation:
       status.canTransitionTo(CONFIRMED) = true (PENDING -> CONFIRMED)
     
     Mutation:
       status = CONFIRMED
       updatedAt = now()
       domainEvents = [OrderConfirmed{orderId=..., timestamp=...}]
     
     Invariants maintained:
       Can only transition through valid states
       Timestamps track when transitions occurred

  4. UPDATE PERSISTENCE:
     orderRepo.save(order);
     
     JPA actions:
       - UPDATE orders SET status='CONFIRMED', updated_at=?, version=version+1
         WHERE id=? AND version=?  --- OPTIMISTIC LOCK CHECK
       - @DomainEvents: publishes OrderConfirmed
       - @AfterDomainEventPublication: clears events
     
     If version doesn't match (concurrent update):
       - ObjectOptimisticLockingFailureException
       - Transaction rolls back
       - Caller must retry with fresh data
```

### Domain Event Publication Timeline

```
  Timeline of domain events within a transaction:

  T=0ms   @Transactional begins
  T=1ms   Order order = Order.place(cmd)
          -> domainEvents = [OrderPlaced(id, customerId, total, ts)]
  
  T=2ms   orderRepo.save(order)
          -> INSERT order row into DB
          -> INSERT order_items rows into DB
          -> Spring Data detects @DomainEvents:
             -> order.domainEvents() returns [OrderPlaced]
             -> ApplicationEventPublisher.publishEvent(OrderPlaced)
                -> @EventListener(OrderPlaced) fires IMMEDIATELY (sync)
                   -> onOrderPlaced(OrderPlaced) - runs in same TX
                -> @TransactionalEventListener(phase=BEFORE_COMMIT)
                   -> fires BEFORE the transaction commits
                   -> if this fails, TX rolls back
                -> @TransactionalEventListener(phase=AFTER_COMMIT)
                   -> QUEUED in TransactionSynchronization
                   -> does NOT execute yet
             -> order.clearDomainEvents() -> domainEvents = []
  
  T=3ms   order.confirm()
          -> domainEvents = [OrderConfirmed(id, ts)]
  
  T=4ms   orderRepo.save(order)
          -> UPDATE order row
          -> Publishes OrderConfirmed event (same flow as above)
          -> domainEvents = []
  
  T=5ms   @Transactional commits:
          -> JpaTransactionManager.doCommit():
             -> flush() -> dirty check -> execute pending updates
             -> Connection.commit()
          -> TransactionSynchronizationManager:
             -> AFTER_COMMIT synchronizations fire:
                -> @TransactionalEventListener(AFTER_COMMIT) handlers run:
                   -> onOrderPlaced(OrderPlaced) -> sends email
                   -> onOrderConfirmed(OrderConfirmed) -> notifies fulfillment
             -> AFTER_COMPLETION synchronizations fire
          -> EntityManager closed, connection returned to pool
  
  KEY INSIGHT: Domain event listeners with AFTER_COMMIT run 
  AFTER the database transaction commits. They are NOT rolled back
  if they fail (the data is already committed). Handle their failures
  with retry or outbox pattern for critical side effects.
```

## 5. Request Flow Diagrams

### Command Processing Through the Domain Layer

```
  HTTP POST /orders  { customerId: 42, items: [...] }
      |
      v
  +-------------------------------+
  | Controller                    |
  |  -> @Valid validation         |
  |  -> PlaceOrderRequest unboxed |
  |  -> request.toCommand()      |
  +---------------+---------------+
                  |
                  v
  +-------------------------------+
  | Application Service           |
  |  @Transactional               |
  |                               |
  |  -> cmd validated by caller   |
  |  -> Order.place(cmd)         |--+
  +-------------------------------+  |
                                     v
                      +-------------------------------+
                      | Order (Domain Entity)          |
                      |                                |
                      |  static factory:                |
                      |  1. Generate OrderId            |
                      |  2. Validate items not empty    |
                      |  3. Create LineItems            |
                      |  4. Calculate total             |
                      |  5. Set status = PENDING        |
                      |  6. registerEvent(OrderPlaced)  |
                      |  7. Return valid Order          |
                      +---------------+---------------+
                                      |
                      returns valid Order to service
                                      |
                  +-------------------v----------------+
                  | Application Service (cont.)        |
                  |                                    |
                  |  -> orderRepo.save(order)          |
                  |     -> JPA INSERT order + items    |
                  |     -> @DomainEvents:               |
                  |        publish OrderPlaced          |
                  |  -> return PlaceOrderResult        |
                  +----------------------+-------------+
                                         |
                  +----------------------v-------------+
                  | Controller                         |
                  |  -> Event processing still queued  |
                  |  -> ResponseEntity.created()       |
                  |     .body(OrderResponse.from())    |
                  |  -> Response returned to client    |
                  +------------------------------------+
                  |                                    |
       (meanwhile) v                                    v
  +-------------------+                    +-------------------+
  | AFTER_COMMIT      |                    | AFTER_ROLLBACK    |
  | synchronizations  |                    | synchronizations  |
  |                   |                    |                   |
  | If TX committed:  |                    | If TX rolled back:|
  | +-- send email    |                    | +-- release       |
  | +-- update stats  |                    |     inventory     |
  | +-- notify        |                    | +-- compensate    |
  |     fulfillment   |                    |     payment       |
  +-------------------+                    +-------------------+
```

### Aggregate Interaction: Safe References Only Through Root

```
  +------------------------------------------------------------------+
  |  CORRECT: External code references ONLY the aggregate root       |
  +------------------------------------------------------------------+

  OrderService                                  Order (Aggregate Root)
       |                                              |
       |  order.addItem(item)                         |
       |--------------------------------------------->|
       |                                              |
       |  order.removeItem(sku)                       |
       |--------------------------------------------->|
       |                                              |
       |  order.getItems()                            |
       |--------------------------------------------->|
       |  returns unmodifiable list                   |
       |                                              |


  +------------------------------------------------------------------+
  |  WRONG: External code references internal aggregate parts         |
  +------------------------------------------------------------------+

  OrderService                    Order              LineItem
       |                            |                    |
       |  LineItem item =           |                    |
       |  order.getItems().get(0)   |                    |
       |  (bypasses root)           |                    |
       |------------------------------------------------>|
       |  item.setQuantity(999)     |                    |
       |  (modifies internal state) |                    |
       |------------------------------------------------>|
       |                            |                    |
       |  BUG: Total is now wrong   |  Total: $40        |  Qty: 999
       |       Invariant violated   |  (not recalculated,|
       |                             |   should be $9990)|
```

## 6. Lifecycle Diagrams

### Aggregate Lifecycle State Machine

```
  +------------------------------------------------------------------+
  |                    ORDER AGGREGATE LIFECYCLE                      |
  |                                                                  |
  |  [PlaceOrderCommand]                                             |
  |       |                                                          |
  |       v                                                          |
  |  +-----------+       confirm()       +-----------+               |
  |  | PENDING    |--------------------->| CONFIRMED |               |
  |  +-----------+                      +-----------+               |
  |       |                                   |                      |
  |       | cancel()                          | markAsPaid()         |
  |       v                                   v                      |
  |  +-----------+                        +-----------+              |
  |  | CANCELLED |                        | PAID      |              |
  |  +-----------+                        +-----------+              |
  |       |                                   |                      |
  |       | (terminal)                        | ship()               |
  |       |                                   v                      |
  |       |                              +-----------+              |
  |       |                              | SHIPPED   |              |
  |       |                              +-----------+              |
  |       |                                   |                      |
  |       |                                   | deliver()            |
  |       |                                   v                      |
  |       |                              +-----------+              |
  |       |                              | DELIVERED |              |
  |       |                              +-----------+              |
  |       |                                   |                      |
  |       |                                   | refund()             |
  |       |                                   v                      |
  |       |                              +-----------+              |
  |       +----------------------------->| REFUNDED   |              |
  |           (from DELIVERED only)      +-----------+              |
  |                                           |                      |
  |                                           | (terminal)           |
  +------------------------------------------------------------------+

  Each transition is a BEHAVIOR METHOD on the aggregate root.
  Each behavior method: (1) validates canTransitionTo, (2) mutates state,
  (3) updates timestamp, (4) registers domain event.
```

### Value Object vs Entity Lifecycle

```
  VALUE OBJECT LIFECYCLE:
  
  Created -> Used -> Discarded (no identity, no persistent lifecycle)
  
  Money price = new Money(BigDecimal(100), USD)
  // self-validating on construction
  
  price = price.add(new Money(BigDecimal(20), USD))
  // returns NEW Money, old one unchanged (immutability)
  // old reference eligible for GC
  
  // Value objects are replaced, not modified
  
  vs
  
  ENTITY LIFECYCLE:
  
  Created -> Persisted -> Modified -> Persisted -> Archived/Deleted
  
  Order order = Order.place(cmd)       // Created (transient)
  orderRepo.save(order)                 // Persisted (managed by JPA)
  order.confirm()                       // Modified (dirty in PersistenceContext)
  orderRepo.save(order)                 // Updated (SQL UPDATE at flush)
  order.cancel("customer request")       // Modified
  orderRepo.save(order)                 // Updated
  // Entity identity persists through all state changes
```

## 7. Source Code Reading Guide

### Critical Files to Read (In Order)

```
1. org.springframework.data.domain.AbstractAggregateRoot
   spring-data-commons/.../domain/AbstractAggregateRoot.java (~80 lines)
   -> registerEvent() -- stores event in transient list
   -> @DomainEvents -- Spring Data calls this to get events
   -> @AfterDomainEventPublication -- Spring Data calls this to clear events

2. org.springframework.data.repository.core.support
        .RepositoryFactorySupport.EventPublishingMethodInterceptor
   spring-data-commons/.../support/RepositoryFactorySupport.java (inner class, ~60 lines)
   -> Intercepts save() to publish domain events
   -> Calls entity's @DomainEvents and @AfterDomainEventPublication methods

3. org.springframework.data.domain.DomainEvents (annotation)
   spring-data-commons/.../domain/DomainEvents.java
   -> Marker annotation for event getter

4. org.springframework.data.domain.AfterDomainEventPublication (annotation)
   spring-data-commons/.../domain/AfterDomainEventPublication.java
   -> Marker annotation for event clearing

5. org.springframework.data.jpa.repository.support.JpaEntityInformationSupport
   spring-data-jpa/.../support/JpaEntityInformationSupport.java (~300 lines)
   -> getEntityInformation() -- determines entity metadata
   -> getId() method resolution
   -> isNew() detection strategy

6. org.springframework.data.repository.core.support
        .AbstractEntityInformation.isNew()
   spring-data-commons/.../support/AbstractEntityInformation.java
   -> Default new-detection: ID is null (or primitive zero)

7. org.hibernate.engine.internal.StatefulPersistenceContext
   hibernate-core/.../internal/StatefulPersistenceContext.java (~700 lines)
   -> entitySnapshotsByKey -- snapshots for dirty checking
   -> getDatabaseSnapshot() -- loaded state vs current state
   -> isDirty() -- checks if entity needs UPDATE

8. org.springframework.data.repository.core.EntityInformation
   spring-data-commons/.../repository/core/EntityInformation.java
   -> Interface: getId(), getIdType(), getJavaType(), isNew()
```

### How AbstractAggregateRoot + @DomainEvents Processing Works

```java
// In RepositoryFactorySupport, when building a repository proxy:

// Step 1: Check if entity implements AggregateRoot
boolean isAggregateRoot = AggregateRoot.class.isAssignableFrom(
        information.getDomainType());

// Step 2: If yes, add EventPublishingMethodInterceptor to the proxy
if (isAggregateRoot) {
    adviceChain.add(new EventPublishingMethodInterceptor(
            information.getDomainType()));
}

// Step 3: EventPublishingMethodInterceptor intercepts save():
class EventPublishingMethodInterceptor implements MethodInterceptor {
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object result = invocation.proceed();  // Call actual save()
        
        Object entity = invocation.getArguments()[0];
        
        // Find @DomainEvents method (via reflection, cached):
        Method eventsMethod = findDomainEventsMethod(entity);
        if (eventsMethod != null) {
            Collection<Object> events = (Collection<Object>) 
                    eventsMethod.invoke(entity);
            
            for (Object event : events) {
                applicationEventPublisher.publishEvent(event);
            }
            
            // Find @AfterDomainEventPublication method and call it:
            Method clearMethod = findAfterEventPublicationMethod(entity);
            if (clearMethod != null) {
                clearMethod.invoke(entity);
            }
        }
        
        return result;
    }
}
```

## 8. Production Failure Scenarios

### Scenario 1: Anemic Model Causes Duplicate Business Rules

**Symptom**: An order cancellation that was valid through the REST API endpoint is rejected when the same cancellation comes through the batch processing job. Different behavior for the same business rule.

**Root cause**: The "can an order be cancelled?" rule is implemented in `OrderController.cancelOrder()` (checks status != SHIPPED) and in `BatchCancellationService.cancelExpiredOrders()` (checks status in [PENDING, CONFIRMED]) with inconsistent logic.

```java
// BROKEN: Business rule scattered across two services:
@RestController
public class OrderController {
    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        Order order = orderRepo.findById(id).orElseThrow();
        if ("SHIPPED".equals(order.getStatus())) {  // RULE VERSION A
            return ResponseEntity.badRequest()
                    .body("Cannot cancel shipped orders");
        }
        order.setStatus("CANCELLED");
        orderRepo.save(order);
        return ResponseEntity.ok().build();
    }
}

@Service
public class BatchCancellationService {
    public void cancelExpiredOrders() {
        // RULE VERSION B -- different logic!
        List<Order> orders = orderRepo.findByStatusAndCreatedAtBefore(
                OrderStatus.PENDING, LocalDateTime.now().minusDays(1));
        // Only looks for PENDING, doesn't check CONFIRMED vs SHIPPED
        
        for (Order order : orders) {
            if (order.getStatus() == OrderStatus.DELIVERED) {  
                // Third version!
                continue;
            }
            order.setStatus(OrderStatus.CANCELLED);
            orderRepo.save(order);
        }
    }
}

// FIXED: Rule lives ONCE in the domain entity:
@Entity
public class Order {
    public void cancel(String reason) {
        if (!this.status.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new OrderStateException(
                    "Cannot cancel order in status " + this.status);
        }
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
        registerEvent(new OrderCancelled(this.id, reason, this.updatedAt));
    }
    // Same cancel() method used by REST controller, batch job, and message listener.
    // Business rule is in ONE place. Behavior is identical in all contexts.
}
```

### Scenario 2: Optimistic Locking Failure in High-Concurrency Scenario

**Symptom**: `ObjectOptimisticLockingFailureException` under concurrent user load. Users see "Please try again" errors when modifying the same order.

**Root cause**: Two users/systems modify the same aggregate simultaneously. `@Version` detects the conflict and the second save fails.

```java
// The collision scenario:
// User A (web) and User B (admin panel) both load Order{id=42, version=5}
// User A: modifies shipping address, saves -> version becomes 6
// User B: modifies status, saves with WHERE version=5 -> NO ROW FOUND -> exception

// FIX: Retry with fresh data
@Service
public class OrderUpdateService {
    
    @Transactional
    public UpdateResult updateOrder(OrderId orderId, 
            Function<Order, Order> updateFn) {
        
        int retries = 3;
        ObjectOptimisticLockingFailureException lastException = null;
        
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                Order order = orderRepo.findById(orderId).orElseThrow();
                updateFn.apply(order);  // Apply the update to fresh entity
                orderRepo.save(order);
                return UpdateResult.success(order);
            } catch (ObjectOptimisticLockingFailureException e) {
                lastException = e;
                // EntityManager is broken after this exception -- 
                // clear and retry with a fresh transaction
                try { Thread.sleep(100L * (attempt + 1)); } 
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new OptimisticLockRetryExhaustedException(
                "Update failed after " + retries + " attempts", lastException);
    }
}
```

### Scenario 3: Domain Event Lost Due to Transaction Rollback Without Compensation

**Symptom**: An `@TransactionalEventListener(phase=AFTER_COMMIT)` listener sends an email "Your order is confirmed", but the order's payment authorization failed and rolled back — yet the email was already sent because the listener fired on a different transaction's commit.

**Root cause**: Misunderstanding of `phase=AFTER_COMMIT` — it fires after the CURRENT transaction commits. If the payment authorization runs in a nested `REQUIRES_NEW` transaction that commits but the outer transaction later rolls back, the listener for the inner transaction has already fired.

```java
// BROKEN:
@Transactional
public void placeOrder(PlaceOrderCommand cmd) {
    Order order = Order.place(cmd);
    orderRepo.save(order);
    // Domain events: [OrderPlaced]
    // AFTER_COMMIT listener: sends email "Order confirmed!"

    paymentService.capture(order);  // REQUIRES_NEW
    // Payment capture fails -> TX2 rolls back
    // But TX1 already published events, email was sent!
}

// FIX 1: Make payment capture in SAME transaction (fails together)
// FIX 2: Use BEFORE_COMMIT for events that should fire before commit
// FIX 3: Include payment status in the event, listener checks before emailing
// FIX 4: Use outbox pattern -- events only published to message broker
//         after the ENTIRE workflow commits
```

### Scenario 4: LazyInitializationException in Value Object Collections

**Symptom**: `LazyInitializationException: failed to lazily initialize a collection of role: ...order_items, could not initialize proxy - no Session` when serializing an Order outside a transaction.

**Root cause**: Value objects stored via `@ElementCollection` use lazy loading by default. If the entity is accessed outside a transaction (e.g., in an `AFTER_COMMIT` event listener, or after returning from a `@Transactional` method), the collection cannot be loaded.

```java
// BROKEN:
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onOrderPlaced(OrderPlaced event) {
    // Transaction is committed, Session is closed
    Order order = orderRepo.findById(event.orderId()).orElseThrow();
    // LazyInitializationException on order.getItems()
    
    for (LineItem item : order.getItems()) {  // BOOM
        inventoryService.decrement(item.sku(), item.quantity());
    }
}

// FIX: Include necessary data in the domain event itself
public record OrderPlaced(
        OrderId orderId, 
        Long customerId, 
        Money total,
        List<LineItem> items,  // Materialized in the event
        LocalDateTime timestamp) {
}

@TransactionalEventListener(phase = AFTER_COMMIT)
public void onOrderPlaced(OrderPlaced event) {
    // Use event data, not entity data
    for (LineItem item : event.items()) {
        inventoryService.decrement(item.sku(), item.quantity());
    }
}
```

## 9. Debugging Techniques

### Inspecting Domain Event Publication

```java
// Debug domain event registration and publication:

@Component
public class DomainEventDebugger {
    
    @EventListener
    public void logAllDomainEvents(Object event) {
        // Catches ALL application events, including domain events
        if (event.getClass().getPackageName().contains("domain")) {
            System.out.printf("[DOMAIN EVENT] %s: %s%n", 
                    event.getClass().getSimpleName(), event);
        }
    }
    
    @EventListener
    public void onOrderPlaced(OrderPlaced event) {
        System.out.printf("[ORDER PLACED] id=%s total=%s customer=%d%n",
                event.orderId(), event.total(), event.customerId());
        
        // Verify event is immutable:
        // event.orderId() returns a copy, can't modify the original
    }
}

// Manual event inspection:
Order order = Order.place(cmd);
// Check events before save:
@SuppressWarnings("unchecked")
List<Object> events = (List<Object>) ReflectionUtils
        .findField(AbstractAggregateRoot.class, "domainEvents")
        .get(order);
System.out.println("Events before save: " + events.size());
// Output: Events before save: 1

orderRepo.save(order);
// Events now cleared:
System.out.println("Events after save: " + events.size());
// Output: Events after save: 0
```

### Testing Domain Objects Without Spring

```java
// One of the biggest benefits of rich domain models:
// Domain logic can be tested WITHOUT Spring, WITHOUT JPA, WITHOUT a database.

class OrderTest {
    
    @Test
    void shouldPlaceOrderWithValidCommand() {
        PlaceOrderCommand cmd = new PlaceOrderCommand(
                42L, 
                List.of(new LineItemCommand("SKU-1", 2, 
                        new Money(new BigDecimal("10.00"), USD))),
                new Money(new BigDecimal("20.00"), USD),
                LocalDateTime.now(),
                USD);
        
        Order order = Order.place(cmd);
        
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getTotal().amount())
                .isEqualByComparingTo(new BigDecimal("20.00"));
    }
    
    @Test
    void shouldRejectOrderWithNoItems() {
        PlaceOrderCommand cmd = new PlaceOrderCommand(
                42L, 
                List.of(),  // Empty items
                Money.zero(USD),
                LocalDateTime.now(),
                USD);
        
        assertThatThrownBy(() -> Order.place(cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one item");
    }
    
    @Test
    void shouldRegisterOrderPlacedEvent() {
        PlaceOrderCommand cmd = validCommand();
        
        Order order = Order.place(cmd);
        
        // Access domain events via reflection or a test helper
        Collection<Object> events = getDomainEvents(order);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(OrderPlaced.class);
    }
    
    @Test
    void shouldRejectInvalidStateTransition() {
        Order order = Order.place(validCommand());
        order.confirm();
        order.markAsPaid();
        order.ship();
        
        assertThatThrownBy(() -> order.cancel("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel");
        // SHIPPED -> CANCELLED is not allowed
    }
    
    @Test
    void moneyValueObjectShouldSelfValidate() {
        assertThatThrownBy(() -> 
                new Money(new BigDecimal("-5.00"), USD))
                .isInstanceOf(IllegalArgumentException.class);
        
        assertThatThrownBy(() -> 
                new Money(null, USD))
                .isInstanceOf(IllegalArgumentException.class);
    }
    
    @Test
    void moneyValueObjectsShouldBeImmutable() {
        Money original = new Money(new BigDecimal("10.00"), USD);
        Money added = original.add(new Money(new BigDecimal("5.00"), USD));
        
        assertThat(original.amount())
                .isEqualByComparingTo(new BigDecimal("10.00"));
        // Original unchanged (immutability)
        assertThat(added.amount())
                .isEqualByComparingTo(new BigDecimal("15.00"));
    }
    
    @Test
    void moneyWithSameAmountAndCurrencyShouldBeEqual() {
        Money m1 = new Money(new BigDecimal("10.00"), USD);
        Money m2 = new Money(new BigDecimal("10.00"), USD);
        
        assertThat(m1).isEqualTo(m2);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
    }
    
    // Helper to access private domain events for testing:
    @SuppressWarnings("unchecked")
    private Collection<Object> getDomainEvents(Order order) {
        try {
            Field field = AbstractAggregateRoot.class
                    .getDeclaredField("domainEvents");
            field.setAccessible(true);
            return (Collection<Object>) field.get(order);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

### Property-Based Testing with jqwik

```java
// Property-based testing for domain objects using jqwik:

class MoneyProperties {
    
    @Property
    void additionShouldBeCommutative(
            @ForAll @BigRange(min = "0", max = "1000000") BigDecimal a,
            @ForAll @BigRange(min = "0", max = "1000000") BigDecimal b) {
        
        Money m1 = new Money(a, USD);
        Money m2 = new Money(b, USD);
        
        Money sum1 = m1.add(m2);
        Money sum2 = m2.add(m1);
        
        assertThat(sum1).isEqualTo(sum2);
    }
    
    @Property
    void additionShouldBeAssociative(
            @ForAll @BigRange(min = "0", max = "10000") BigDecimal a,
            @ForAll @BigRange(min = "0", max = "10000") BigDecimal b,
            @ForAll @BigRange(min = "0", max = "10000") BigDecimal c) {
        
        Money m1 = new Money(a, USD);
        Money m2 = new Money(b, USD);
        Money m3 = new Money(c, USD);
        
        Money sum1 = m1.add(m2).add(m3);
        Money sum2 = m1.add(m2.add(m3));
        
        assertThat(sum1).isEqualTo(sum2);
    }
    
    @Property
    void anyNegativeAmountShouldBeRejected(
            @ForAll @BigRange(min = "-9999999", max = "-1") BigDecimal amount) {
        assertThatThrownBy(() -> new Money(amount, USD))
                .isInstanceOf(IllegalArgumentException.class);
    }
    
    @Property
    void orderTotalShouldEqualSumOfLineItems(
            @ForAll("validLineItems") List<LineItemCommand> items) {
        
        PlaceOrderCommand cmd = new PlaceOrderCommand(42L, items,
                null, LocalDateTime.now(), USD);
        
        Order order = Order.place(cmd);
        
        Money expectedTotal = items.stream()
                .map(i -> i.unitPrice().multiply(i.quantity()))
                .reduce(Money.zero(USD), Money::add);
        
        assertThat(order.getTotal()).isEqualTo(expectedTotal);
    }
}
```

## 10. Observability Considerations

### Domain Event Tracing

```java
// Ensure domain events carry trace context for observability:

public record OrderPlaced(
        OrderId orderId,
        Long customerId,
        Money total,
        String traceId,    // From MDC
        String spanId,     // From MDC
        LocalDateTime timestamp
) {}

// In the entity factory, capture current trace context:
public static Order place(PlaceOrderCommand command) {
    Order order = new Order();
    // ... setup ...
    
    String traceId = MDC.get("traceId");
    String spanId = MDC.get("spanId");
    
    order.registerEvent(new OrderPlaced(
            order.id, order.customerId, order.total,
            traceId, spanId, order.createdAt));
    
    return order;
}

// Consumers can correlate events to the originating request:
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onOrderPlaced(OrderPlaced event) {
    MDC.put("traceId", event.traceId());
    MDC.put("spanId", event.spanId());
    // Now logging and metrics in this listener carry the original trace
    try {
        emailService.sendConfirmation(event);
    } finally {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }
}
```

### Metrics for Aggregate Operations

```java
// Micrometer metrics for domain model operations:

@Component
public class DomainMetrics {
    private final MeterRegistry registry;
    
    public void recordAggregateOperation(String aggregate, 
            String operation, String outcome) {
        registry.counter("domain.aggregate.operation",
                "aggregate", aggregate,
                "operation", operation,
                "outcome", outcome)
                .increment();
    }
    
    public void recordDomainEvent(Class<?> eventType, String outcome) {
        registry.counter("domain.event",
                "type", eventType.getSimpleName(),
                "outcome", outcome)
                .increment();
    }
}

// Usage in Application Service:
@Transactional
public PlaceOrderResult placeOrder(PlaceOrderCommand cmd) {
    try {
        Order order = Order.place(cmd);
        orderRepo.save(order);
        domainMetrics.recordAggregateOperation(
                "Order", "place", "SUCCESS");
        return PlaceOrderResult.from(order);
    } catch (Exception e) {
        domainMetrics.recordAggregateOperation(
                "Order", "place", "FAILURE");
        throw e;
    }
}
```

## 11. Performance Implications

### Value Object Allocation Overhead

```
  +------------------------------------------------------------------+
  |              VALUE OBJECT MEMORY IMPLICATIONS                     |
  |                                                                  |
  |  Value objects are immutable -> every "modification" creates     |
  |  a new instance. This allocates memory on the heap.              |
  |                                                                  |
  |  Example: Order with 20 LineItems, recalculating total:           |
  |    Money total = items.stream()                                  |
  |        .map(LineItem::subtotal)                                  |
  |        .reduce(Money.zero(USD), Money::add);                     |
  |                                                                  |
  |  This creates 20 intermediate Money objects for subtotals        |
  |  + 19 intermediate Money objects for reduce operations           |
  |  = 39 Money objects allocated (then garbage collected)           |
  |                                                                  |
  |  IS THIS A PROBLEM?                                              |
  |  For typical web requests (tens to hundreds of items): NO.      |
  |  Modern JVMs handle short-lived objects efficiently via          |
  |  the TLAB (Thread-Local Allocation Buffer) in Eden space.        |
  |  Allocation cost: ~10 CPU instructions on HotSpot.              |
  |                                                                  |
  |  For batch processing (millions of items): YES, can matter.     |
  |  Solution: Use mutable accumulator in batch contexts, or        |
  |  use primitive types with manual validation for hot paths.      |
  |                                                                  |
  |  The correctness benefit of value objects ALMOST ALWAYS          |
  |  outweighs the allocation cost. Profile before optimizing.      |
  +------------------------------------------------------------------+
```

### @ElementCollection vs @OneToMany for Value Objects

```
  +------------------------------------------------------------------+
  |         @ElementCollection vs @OneToMany PERFORMANCE              |
  |                                                                  |
  |  @ElementCollection (LineItem as @Embeddable):                   |
  |    +-- No separate entity lifecycle (no @Id, no @Version)        |
  |    +-- DELETE + INSERT on every collection update                |
  |    +-- Simpler queries (no join to separate table)               |
  |    +-- Collection table has composite key (order_id, item_index) |
  |    +-- Best for: Small, frequently-replaced collections          |
  |                                                                  |
  |  @OneToMany (LineItem as @Entity):                               |
  |    +-- Full entity lifecycle (separate table with PK)            |
  |    +-- Targeted UPDATE/DELETE (by ID)                            |
  |    +-- Can have its own @Version for optimistic locking          |
  |    +-- More query flexibility                                    |
  |    +-- Best for: Large collections, individually updatable items |
  +------------------------------------------------------------------+
```

### Aggregate Size and Transaction Contention

```
  +------------------------------------------------------------------+
  |            AGGREGATE SIZE -> LOCK CONTENTION                      |
  |                                                                  |
  |  Larger aggregates = more rows locked per transaction.           |
  |                                                                  |
  |  Example:                                                         |
  |  Order (root) + 100 LineItems + ShippingAddress + PaymentInfo    |
  |  Two users modifying the same order:                             |
  |    User A: changes ShippingAddress                                |
  |    User B: adds a LineItem                                       |
  |  @Version on Order -> User B gets OptimisticLockException        |
  |  even though they're modifying different data!                   |
  |                                                                  |
  |  DESIGN GUIDANCE:                                                 |
  |  * Keep aggregates SMALL. Design for consistency, not            |
  |    for "everything belongs to the order."                        |
  |  * Separate things that change at different rates:               |
  |    - Order (root): status, total -- changes frequently           |
  |    - OrderShipment (separate aggregate): tracking number,        |
  |      carrier, estimated delivery -- changes independently        |
  |    - OrderPayment (separate aggregate): authorization, capture,  |
  |      refund -- separate lifecycle                                |
  |  * Eventual consistency between aggregates:                      |
  |    Order.confirm() -> OrderConfirmed event ->                    |
  |    OrderShipment.create() in separate transaction               |
  +------------------------------------------------------------------+
```

## 12. Architecture Implications

### Decision Framework: Rich vs Anemic

```
  +------------------------------------------------------------------+
  |             WHEN RICH MODEL ADDS VALUE                            |
  |                                                                  |
  |  Rich model is worth the investment when:                         |
  |                                                                  |
  |  1. Complex business rules with many state transitions           |
  |     Example: Insurance policy with underwriting, issuance,        |
  |     premium calculation, renewal, lapse, reinstatement.           |
  |     Rich model: Policy entity with behavior methods that         |
  |     enforce 20+ transition rules and recalculate premiums        |
  |     on every change. Anemic model: 500-line PolicyService         |
  |     with duplicated rules across 3 entry points.                 |
  |                                                                  |
  |  2. Multiple entry points modify the same entities               |
  |     Example: Order modified by REST API, message queue,           |
  |     admin UI, and scheduled job. Rich model: all 4 call           |
  |     the same order.cancel() method. Anemic model: 4 different     |
  |     implementations of "can this order be cancelled?"            |
  |                                                                  |
  |  3. Strong domain invariants that must never be violated         |
  |     Example: Account balance cannot go below zero. Rich model:    |
  |     Account.debit(Money) validates before mutation. Anemic:       |
  |     any caller can set balance to negative via setter.           |
  |                                                                  |
  |  4. Long-lived entities that evolve through many states          |
  |     Example: Loan application: DRAFT -> SUBMITTED ->              |
  |     UNDER_REVIEW -> APPROVED -> FUNDED -> REPAID.                |
  |     Rich model: state machine in entity. Anemic: status string    |
  |     with validation scattered across services.                   |
  |                                                                  |
  |  5. Team has domain expertise and DDD experience                  |
  |     Rich models require conversation with domain experts,        |
  |     ubiquitous language, and deliberate modeling.                 |
  +------------------------------------------------------------------+

  +------------------------------------------------------------------+
  |             WHEN ANEMIC MODEL IS ACCEPTABLE                       |
  |                                                                  |
  |  Anemic model is pragmatic when:                                  |
  |                                                                  |
  |  1. Simple CRUD with minimal business logic                      |
  |     Example: Configuration settings entity. Just CRUD.           |
  |     Overhead of rich model not justified.                        |
  |                                                                  |
  |  2. Data-intensive, not behavior-intensive                       |
  |     Example: Analytics event log. Data is written once,          |
  |     never modified. Behavior is just validating fields.          |
  |                                                                  |
  |  3. Team is inexperienced with DDD / rich modeling               |
  |     A bad rich model (entity doing everything, god object)       |
  |     is worse than a disciplined anemic model.                    |
  |                                                                  |
  |  4. Framework/tooling encourages anemic pattern                  |
  |     Spring Data REST, MapStruct, Lombok all push toward          |
  |     DTO/Entity separation with getters/setters. If your          |
  |     toolchain requires anemic, fighting it costs more than       |
  |     the benefit.                                                 |
  |                                                                  |
  |  5. Prototype / MVP phase                                       |
  |     Start with what works fast. Refactor to rich model           |
  |     when complexity justifies it.                                |
  +------------------------------------------------------------------+
```

### Common Mistakes Table

```
  +----------+-------------------------------------+--------------------------------------+
  | Mistake  | What It Looks Like                  | Fix                                  |
  +----------+-------------------------------------+--------------------------------------+
  | Anemic   | Entity = getters/setters only.      | Move behavior INTO the entity.        |
  | Model    | All logic in @Service classes.      | Encapsulate state behind behavior     |
  |          | Direct field mutation anywhere.     | methods. Hide setters (protected).   |
  +----------+-------------------------------------+--------------------------------------+
  | Fat      | Entity with 50+ methods.             | Split into smaller aggregates.        |
  | Aggregate| Single entity is "the order"        | Use eventual consistency between      |
  |          | that does everything.               | aggregates via domain events.        |
  +----------+-------------------------------------+--------------------------------------+
  | Entity   | Entity injects a repository via     | Never inject repos into entities.    |
  | depending| @Autowired. Entity calls            | Move orchestration to application     |
  | on       | database directly.                  | service. Pass needed data into        |
  | Repo     |                                     | entity methods.                      |
  +----------+-------------------------------------+--------------------------------------+
  | Violated | Constructor sets total to 0.         | Every constructor/factory method     |
  | Invariant| Setter allows total=any value.       | validates invariants. No setters      |
  |          | No validation on state changes.     | for mutable fields that must be       |
  |          |                                     | consistent.                          |
  +----------+-------------------------------------+--------------------------------------+
  | Mutable  | Money.setAmount(newAmount).          | Value objects must be IMMUTABLE.     |
  | Value    | Address.setStreet(newName).          | Every "change" creates a new         |
  | Objects  |                                     | instance. Use records in Java 17+.  |
  +----------+-------------------------------------+--------------------------------------+
  | Identity | Entity.equals() compares ALL fields. | Entity equality = IDENTITY only.     |
  | by Value | Two order objects with same ID but  | Order{id=1,name="A"}.equals(         |
  |          | different loaded data are unequal.  |   Order{id=1,name="B"}) == true.     |
  +----------+-------------------------------------+--------------------------------------+
  | No Domain| Service calls service calls service. | Use domain events to decouple         |
  | Events   | Tight coupling between operations.  | side effects. After order.placed(),   |
  |          | "OrderService creates, then calls   | email listener reacts to              |
  |          |  EmailService, then calls           | OrderPlaced event.                   |
  |          |  AnalyticsService..."               |                                      |
  +----------+-------------------------------------+--------------------------------------+
  | JPA      | @ManyToOne fetch=EAGER everywhere.   | Default to LAZY. Use @EntityGraph    |
  | Leaks    | LazyInitializationException in       | or DTO projections for specific       |
  | into     | @ExceptionHandler.                  | use cases. Don't expose entity       |
  | Domain   |                                     | graph to non-transactional code.     |
  +----------+-------------------------------------+--------------------------------------+
```

## 13. Team Ownership Implications

```
  +------------------------------------------------------------------+
  |                     OWNERSHIP MATRIX                               |
  |                                                                  |
  |  Domain Architect / Tech Lead Owns:                              |
  |  +-- Aggregate design and boundaries                             |
  |  +-- Entity identity strategy (UUID vs auto-increment vs natural)|
  |  +-- Value object conventions (immutability, validation)         |
  |  +-- Domain event catalog (which events exist, their schema)     |
  |  +-- Decision: Rich vs Anemic model per bounded context          |
  |  +-- Cross-aggregate interaction patterns                        |
  |  +-- ArchUnit rules for domain layer purity                      |
  |                                                                  |
  |  Domain Experts + Developers Own:                                |
  |  +-- Ubiquitous language and domain model terminology            |
  |  +-- Business rule discovery and documentation                   |
  |  +-- State machine definitions (valid status transitions)        |
  |  +-- Domain invariants specification                             |
  |                                                                  |
  |  Developers Own:                                                 |
  |  +-- Entity and value object implementations                     |
  |  +-- Domain service implementations (pure algorithms)            |
  |  +-- Unit tests for domain objects (no Spring context)           |
  |  +-- Property-based tests for invariants                         |
  |  +-- Application services that orchestrate domain objects        |
  |                                                                  |
  |  Platform Team Owns:                                             |
  |  +-- JPA/Hibernate configuration                                 |
  |  +-- @Embeddable mapping patterns and conventions                |
  |  +-- Domain event infrastructure (AbstractAggregateRoot usage)   |
  |  +-- @Version / optimistic locking defaults                      |
  |  +-- Database migration tooling (Liquibase/Flyway)               |
  +------------------------------------------------------------------+
```

## 14. Interview Questions

### Question 1: "Compare rich domain model vs anemic domain model. When would you use each? Show code examples of the same feature implemented both ways and explain the trade-offs."

**Staff-level answer**: The distinction is fundamentally about where behavior lives. In an anemic model, entities are data carriers with getters and setters, and all business logic resides in service classes. In a rich model, entities encapsulate both data and behavior — they enforce their own invariants, manage their own state transitions, and expose only behavior methods (not raw setters).

The anemic model's primary advantage is simplicity and framework alignment. Spring Boot, Spring Data JPA, Lombok, and MapStruct all encourage getter/setter entities with logic in `@Service` beans. This makes CRUD operations trivially easy and is well-understood by most developers. The cost is deferred: business rules scatter across multiple service methods, invariants can be violated by any code with a reference to the entity, and refactoring business rules requires finding every place the relevant setter is called.

The rich model's primary advantage is correctness through encapsulation. When `Order.cancel()` is the ONLY way to cancel an order, the cancellation rules (is the status cancellable? what events fire? what timestamps update?) execute identically whether called from a REST controller, a batch job, or a message listener. The `Order` object cannot exist in an invalid state because its factory method and behavior methods validate all invariants before allowing any state change.

A concrete example: an order with a total that must equal the sum of line item subtotals. Anemic: `order.setTotal(anyValue)` — nothing prevents setting total to zero while line items sum to $100. Rich: `total` is computed internally, never exposed as a setter, recalculated automatically when items change. The invariant is guaranteed, not hoped for.

The decision framework I use: Rich model when (1) business rules are complex with multiple state transitions, (2) multiple entry points modify the same entities, (3) invariants must be absolutely enforced, (4) the team has DDD experience. Anemic model when (1) the domain is CRUD-heavy with simple rules, (2) the team is new to domain modeling, (3) the framework stack pushes hard toward anemic patterns and the project timeline doesn't allow fighting that gravity, (4) it's a prototype that may not survive. The key is conscious choice — never default to anemic without understanding what you're giving up.

### Question 2: "Explain aggregate design. What is an aggregate root? How do you determine aggregate boundaries? What are the consequences of making an aggregate too large or too small?"

**Staff-level answer**: An aggregate is a cluster of domain objects (entities and value objects) treated as a single unit for data changes. The aggregate root is the single entity through which all external references to the aggregate must pass. The root enforces the consistency boundary: after any operation on the aggregate, all invariants within that boundary must be satisfied.

Determining aggregate boundaries requires understanding business invariants — rules that must always be true. A business invariant is "an order's total must equal the sum of its line item subtotals" — this means `Order` and `LineItem` belong in the same aggregate. A business invariant that can be eventually consistent is "when an order is delivered, the customer's loyalty points must be updated" — this does NOT require `Order` and `Customer` to be in the same aggregate; a domain event (`OrderDelivered`) can trigger the points update in a separate transaction. The heuristic: if two things must be immediately consistent, they belong in the same aggregate. If they can be eventually consistent, they belong in separate aggregates.

The aggregate root enforces three rules: (1) external code references only the root (never internal entities or value objects directly), (2) the root is responsible for maintaining all invariants within the aggregate, (3) the aggregate is the unit of persistence — you load and save the entire aggregate, and the transaction boundary equals the aggregate boundary. In Spring Data JPA, this means `orderRepo.save(order)` persists the entire aggregate (order row + line item rows + embedded value objects), and `@Version` on the root provides optimistic concurrency for the entire aggregate.

Too-large aggregates: When everything is part of the Order aggregate (Order + LineItems + CustomerInfo + ShippingInfo + PaymentInfo + Invoice + Delivery + Returns + Complaints), concurrent modifications to different parts of the aggregate conflict on the root's `@Version`. Two users can't simultaneously update the shipping address and add a return item because they're fighting over the same version field. Transaction duration grows as more data is loaded and saved. The fix: split by consistency boundary — `Order`, `OrderShipment`, `OrderPayment`, `OrderReturn` are separate aggregates that communicate via domain events.

Too-small aggregates: When every entity is its own aggregate (Order, LineItem, ShippingAddress each as separate root), enforcing invariants that span them becomes impossible within a single transaction. "Order total must equal sum of LineItem subtotals" can't be guaranteed if LineItem is updated independently of Order. The fix: identify what must be immediately consistent and group those into a single aggregate.

The practical guideline: start with larger aggregates and split them when you experience contention (optimistic lock failures) or performance issues (loading too much data for simple operations). It's easier to split an aggregate than to merge two that have developed independent transaction boundaries.

### Question 3: "How do domain events work in Spring Data JPA? Walk through the lifecycle from event registration in an entity to the event listener executing. What are the pitfalls of AFTER_COMMIT listeners?"

**Staff-level answer**: Spring Data JPA integrates domain events through `AbstractAggregateRoot`, a base class that provides event storage and two lifecycle methods annotated with `@DomainEvents` and `@AfterDomainEventPublication`. The mechanism works within the repository save operation.

When an entity behavior method calls `registerEvent(event)`, the event is added to a transient `List<Object>` field in the aggregate root. Transient means it's not persisted to the database — it exists only in memory. This is intentional: domain events are a consequence of state changes, not state themselves.

When `orderRepo.save(order)` is called, the `EventPublishingMethodInterceptor` (a Spring Data interceptor added to the repository proxy) intercepts the save invocation. After the actual database save completes, it calls `@DomainEvents` on the entity to retrieve the accumulated events, publishes each one via Spring's `ApplicationEventPublisher`, then calls `@AfterDomainEventPublication` to clear the event list. This ensures events are published exactly once per save operation.

The publication uses standard Spring event infrastructure. `@EventListener`-annotated methods execute synchronously within the same transaction. `@TransactionalEventListener` gives more control: `BEFORE_COMMIT` fires before the transaction commits (within the transaction boundary), `AFTER_COMMIT` fires after the transaction commits (in a separate, non-transactional context), `AFTER_ROLLBACK` fires after rollback, and `AFTER_COMPLETION` fires after either outcome. The `AFTER_COMMIT` phase is the most commonly used because by the time it fires, the database state is committed and visible to other transactions.

The critical pitfall of `AFTER_COMMIT` listeners: they execute OUTSIDE any transaction, and their failure does NOT roll back the committed data. If an `AFTER_COMMIT` listener sends a confirmation email and the email server is down, the order is already committed — the email is lost. Solutions: use the outbox pattern (write the event to an outbox table within the transaction, have a separate process read the outbox and publish to a message broker), use a message broker with at-least-once delivery directly from the transaction (using the outbox as the source), or accept the risk for non-critical side effects. A second pitfall: `AFTER_COMMIT` listeners run on the same thread that committed the transaction. If the listener makes a slow external call, it delays the HTTP response to the client. Use `@Async` with a dedicated thread pool for `AFTER_COMMIT` listeners that involve external I/O, but note that `@Async` loses the security context and MDC — you must explicitly propagate those.

A third pitfall: accessing lazy-loaded entity associations in `AFTER_COMMIT` listeners fails because the Hibernate Session is closed. The domain event must carry all necessary data, not rely on the entity being attached. This is by design: the event is a published fact, not a reference to the entity. Design events as immutable records carrying the data consumers need.

## 15. Hands-On Exercises

1. **Refactor an anemic Order model into a rich domain model**:
   Take an existing anemic `Order` entity (getters/setters, all logic in `OrderService`) and refactor it step by step: (a) make constructors private/protected, add a static factory method `Order.place(PlaceOrderCommand)` that validates invariants, (b) replace status setters with behavior methods (`confirm()`, `cancel(String reason)`, `markAsPaid()`), (c) introduce `OrderStatus` as an enum with `canTransitionTo()`, (d) extract `Money` and `LineItem` as immutable value objects, (e) register domain events from behavior methods. Run the existing integration tests after each step to verify no behavior changes.

2. **Implement a Money value object with JPA @Embeddable mapping**:
   Create a `Money` class with `BigDecimal amount` and `String currencyCode`. Implement: (a) self-validation on construction (no nulls, no negative amounts, correct scale), (b) arithmetic operations (`add`, `subtract`, `multiply`) that return new instances (immutability), (c) `equals`/`hashCode` based on all attributes, (d) JPA `@Embeddable` mapping with `@Column` overrides for precision/scale, (e) a `@Transient` `getCurrency()` method that returns `java.util.Currency`. Write unit tests for validation, arithmetic, equality, and an integration test verifying JPA correctly persists and loads the value object.

3. **Set up comprehensive domain object tests (no Spring, no JPA, no database)**:
   Write 20+ unit tests for your domain entities and value objects that run in milliseconds without Spring context: (a) valid creation through factory methods, (b) invalid creation throws appropriate exceptions, (c) state transitions enforce business rules (valid transitions succeed, invalid transitions throw), (d) domain events are registered on state changes, (e) value object immutability (operations return new instances, originals unchanged), (f) entity identity equality (same ID = same entity, different ID = different entity, even with same data). Use parameterized tests for boundary value testing.

4. **Implement domain events with AbstractAggregateRoot and verify the full lifecycle**:
   Create an `Order` entity extending `AbstractAggregateRoot<Order>`. Register events from behavior methods. Implement `@TransactionalEventListener` listeners for each event type (both `BEFORE_COMMIT` for validation and `AFTER_COMMIT` for side effects). Write an integration test that: (a) creates an order and verifies the event is registered before save, (b) verifies the event is published during save, (c) verifies `BEFORE_COMMIT` listeners run in the transaction, (d) verifies `AFTER_COMMIT` listeners run after the transaction commits, (e) verifies events are cleared after publication, (f) verifies that if the transaction rolls back, `AFTER_COMMIT` listeners do NOT fire but `AFTER_ROLLBACK` listeners do.

5. **Implement property-based testing with jqwik for domain invariants**:
   Add jqwik as a test dependency. Write property tests for: (a) `Money.add()` is commutative and associative, (b) `Order` total always equals sum of line item subtotals regardless of how items are added/removed, (c) any `OrderStatus` transition that violates `canTransitionTo()` throws `IllegalStateException`, (d) `Money` with any negative amount throws on construction, (e) `LineItem` with any non-positive quantity throws on construction. Let jqwik generate edge cases automatically.

6. **Build a small aggregate with optimistic locking and test concurrent modification**:
   Create an aggregate with `@Version`. Write a test that simulates concurrent modification: (a) thread 1 reads entity (version N), modifies, saves (version becomes N+1), (b) thread 2 reads entity (version N, same time as thread 1), modifies, attempts to save — verify `ObjectOptimisticLockingFailureException`. Implement a retry mechanism with exponential backoff. Write a test that verifies the retry eventually succeeds when the conflicting modification is resolved.

## 16. Advanced Challenges

1. **Design a complex aggregate with multiple entities, value objects, and domain events for an insurance policy system**:
   Model an insurance policy aggregate: `Policy` (root) contains `PolicyHolder` (entity), `InsuredItem[]` (entity), `Coverage[]` (value object), `Premium` (value object), `PolicyTerm` (value object). Implement the complete lifecycle: `DRAFT -> SUBMITTED -> UNDERWRITING -> APPROVED -> ACTIVE -> EXPIRED / CANCELLED / LAPSED`. Each transition involves: premium recalculation, risk assessment updates, coverage validation, policy document generation trigger, and notification events. Handle: policy renewal (creates new policy period, copies relevant data), mid-term adjustments (add/remove coverage, recalculate premium), cancellation (pro-rata refund calculation). Test all state transitions with property-based testing to ensure no invalid state is reachable.

2. **Implement a domain event versioning and migration strategy**:
   Design a system where domain events are persisted to an `event_store` table and can evolve over time. Implement: (a) event schema versioning (each event type has a version number), (b) upcasters that transform old event versions to new versions when loaded from the store, (c) a `EventStore` that appends events atomically and allows replay, (d) a `ProjectionRebuilder` that drops and rebuilds read models by replaying all events from the store, (e) integration with Spring's event infrastructure so existing `@EventListener` code works with versioned events. Handle: event schema breaking changes, backfilling new fields with default values, and zero-downtime event schema deployments.

3. **Build a saga orchestrator using domain events instead of direct service calls**:
   Replace direct service orchestration (`orderService -> paymentService -> inventoryService`) with a saga driven by domain events. Each service: (a) listens for domain events from other services, (b) performs its local work in its own transaction, (c) publishes its own domain events. The saga's state is tracked in a `SagaState` entity. Implement: saga completion detection (all steps succeeded), saga compensation on failure (each step registers a compensating action, executed in reverse order via `AFTER_ROLLBACK` listeners), timeout handling (if a step doesn't complete within a time limit, trigger compensation), idempotency (duplicate events are detected and ignored via saga ID tracking). Test with concurrent sagas, partial failures, and system restarts mid-saga.

4. **Create a "Domain Model Auditor" that statically analyzes domain object quality**:
   Build a static analysis tool (ArchUnit-based or custom annotation processor) that grades domain model quality. Checks: (a) entities with public setters (red flag for anemic model), (b) entities with public no-arg constructors (should be protected for JPA only), (c) value objects that are mutable (non-final fields, setters), (d) entities depending on repositories or services (should never happen), (e) collection getters returning mutable collections (should return unmodifiable), (f) domain logic in services that should be on entities (heuristic: service method that operates on a single entity passed as parameter), (g) missing `equals`/`hashCode` on entities with `@Id`, (h) missing `@Version` on entities that are aggregate roots. Generate a per-class score and a team-wide report. Integrate with CI to fail builds when scores drop.

5. **Implement a multi-bounded-context system with event-driven integration between aggregates**:
   Design two bounded contexts (`OrderContext` and `FulfillmentContext`) with separate aggregates, separate databases, and event-driven integration. `OrderContext` owns `Order` aggregate. When `Order.confirm()` is called, it publishes `OrderConfirmed`. `FulfillmentContext` listens for `OrderConfirmed`, creates a `Fulfillment` aggregate in its own database, and publishes `FulfillmentCreated`. `OrderContext` listens for `FulfillmentCreated` and updates the order status. Implement: transactional outbox in each context for reliable event publication, a message bridge (Kafka/RabbitMQ) that reads outbox tables and publishes to topics, idempotent event handling at the consumer side, event deduplication, and eventual consistency visualization (API endpoint that shows the current state of both aggregates and any pending events). Test the full flow with chaos engineering (kill each context mid-flow and verify consistency is eventually achieved).
