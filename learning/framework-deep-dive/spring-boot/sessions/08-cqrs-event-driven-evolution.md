# Session 08: CQRS, Event-Driven Architecture & Architecture Evolution

## 1. Why This Topic Exists

CQRS and Event-Driven Architecture are the most misunderstood patterns in backend engineering. Most engineers think CQRS = "separate read and write databases." That's one implementation. The pattern itself is much simpler: **use different models for commands (writes) and queries (reads)**.

Event-Driven Architecture is about **inverting control flow**: instead of A calling B, A publishes an event, and B decides to react. This decoupling is the foundation of scalable, evolvable systems.

**Staff engineer insight**: CQRS without event sourcing is just "separate DTOs for reads and writes" — something you probably already do. The real value of CQRS emerges when combined with event sourcing and distinct read models optimized for query patterns. Event-Driven Architecture is the secret weapon for monolith-to-microservices migration.

## 2. Mental Model

### CQRS Core Concept

```
TRADITIONAL (Same model for reads and writes):
  OrderService {
    createOrder(OrderDto dto)   ← Writes
    getOrder(Long id)            ← Reads
    searchOrders(OrderFilter f)  ← Reads
    updateOrder(Long id, ...)    ← Writes
  }
  Problem: The Order model must serve both writes (enforcing invariants)
           and reads (joining 5 tables for display).

CQRS (Separate models):
  COMMAND SIDE:                    QUERY SIDE:
  CreateOrderCommand                GetOrderQuery
    ↓                                 ↓
  CreateOrderHandler               GetOrderQueryHandler
    ↓                                 ↓
  OrderAggregate                   OrderReadModel (denormalized, flat, optimized)
    ↓                                 ↓
  ORDER_WRITE_DB                   ORDER_READ_DB (materialized view)
```

### Event-Driven Architecture Core Concept

```
REQUEST-DRIVEN (Traditional):      EVENT-DRIVEN:
                                   
A calls B                         A publishes OrderCreated
A waits for B                     B subscribes to OrderCreated
A depends on B being up           B can be down; event is queued
A must know B's interface         A doesn't know B exists
                                   
Synchronous, coupled              Asynchronous, decoupled
```

## 3. Internal Architecture

### CQRS Source Structure

```
src/main/java/com/example/order/
├── command/                              ← WRITE side
│   ├── CreateOrderCommand.java          ← Command object (imperative: "Create order!")
│   ├── CancelOrderCommand.java
│   ├── AddItemToOrderCommand.java
│   └── handler/
│       ├── CreateOrderCommandHandler.java
│       ├── CancelOrderCommandHandler.java
│       └── AddItemToOrderCommandHandler.java
│
├── query/                                ← READ side
│   ├── GetOrderQuery.java               ← Query object (interrogative: "Get order #123")
│   ├── SearchOrdersQuery.java
│   ├── GetOrderStatisticsQuery.java
│   └── handler/
│       ├── GetOrderQueryHandler.java
│       ├── SearchOrdersQueryHandler.java
│       └── GetOrderStatisticsQueryHandler.java
│
├── model/
│   ├── OrderAggregate.java              ← Write model (rich domain, invariants)
│   ├── OrderReadModel.java              ← Read model (flat, optimized for display)
│   ├── OrderProjection.java             ← Projects write model → read model
│   └── OrderItemReadModel.java
│
├── repository/
│   ├── OrderCommandRepository.java      ← Write repository (JPA, full aggregate)
│   └── OrderQueryRepository.java        ← Read repository (JDBC, native SQL, Redis)
│
└── event/
    ├── OrderCreatedEvent.java
    ├── OrderCancelledEvent.java
    └── OrderItemAddedEvent.java
```

### Command Handler Implementation

```java
// command/CreateOrderCommand.java
public record CreateOrderCommand(
    CustomerId customerId,
    List<OrderItemRequest> items,
    PaymentMethod paymentMethod,
    Address shippingAddress
) implements Command<OrderId> {}

// command/handler/CreateOrderCommandHandler.java
@Component
public class CreateOrderCommandHandler implements CommandHandler<CreateOrderCommand, OrderId> {
    
    private final OrderCommandRepository orderRepository;
    private final EventPublisher eventPublisher;
    
    @Override
    @Transactional
    public OrderId handle(CreateOrderCommand cmd) {
        // 1. Create aggregate (enforces invariants during construction)
        OrderAggregate order = OrderAggregate.create(
            cmd.customerId(),
            cmd.items(),
            cmd.shippingAddress()
        );
        
        // 2. Persist
        orderRepository.save(order);
        
        // 3. Publish domain events (they will update read model)
        order.getDomainEvents().forEach(event -> {
            eventPublisher.publish(event);
        });
        
        // 4. Return ID (not the full object — reads use query side)
        return order.getId();
    }
}
```

### Query Handler Implementation

```java
// query/SearchOrdersQuery.java
public record SearchOrdersQuery(
    CustomerId customerId,
    OrderStatus status,
    DateRange dateRange,
    PageRequest pageRequest
) implements Query<Page<OrderReadModel>> {}

// query/handler/SearchOrdersQueryHandler.java
@Component
public class SearchOrdersQueryHandler implements QueryHandler<SearchOrdersQuery, Page<OrderReadModel>> {
    
    private final JdbcTemplate jdbc;  // NOT JPA — direct SQL for read performance
    
    @Override
    public Page<OrderReadModel> handle(SearchOrdersQuery query) {
        // Optimized read query — flat join, no aggregate loading
        String sql = """
            SELECT o.id, o.status, o.total, o.created_at,
                   c.name as customer_name, c.email as customer_email,
                   COUNT(oi.id) as item_count
            FROM orders o
            JOIN customers c ON o.customer_id = c.id
            LEFT JOIN order_items oi ON o.id = oi.order_id
            WHERE o.customer_id = ?
              AND o.status = COALESCE(?, o.status)
              AND o.created_at BETWEEN ? AND ?
            GROUP BY o.id, c.name, c.email
            ORDER BY o.created_at DESC
            LIMIT ? OFFSET ?
            """;
        
        return jdbc.query(sql, /* ... */);
    }
}
```

### Read Model Projection (Write → Read sync)

```java
// Event listener updates read model when command side changes
@Component
public class OrderProjector {
    private final JdbcTemplate jdbc;
    
    @EventListener
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        jdbc.update("""
            INSERT INTO order_read_model (id, customer_id, status, total, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            event.getOrderId(), event.getCustomerId(), 
            "CREATED", event.getTotalAmount(), event.getTimestamp()
        );
    }
    
    @EventListener
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onOrderCancelled(OrderCancelledEvent event) {
        jdbc.update("""
            UPDATE order_read_model SET status = 'CANCELLED', cancelled_at = ?
            WHERE id = ?
            """,
            event.getTimestamp(), event.getOrderId()
        );
    }
}
```

### Event-Driven Architecture Source Structure

```
src/main/java/com/example/
├── shared/
│   └── events/                           ← Shared event schemas
│       ├── OrderPlacedEvent.java
│       ├── PaymentCompletedEvent.java
│       ├── ShipmentCreatedEvent.java
│       └── InventoryUpdatedEvent.java
│
├── ordering/
│   ├── event/
│   │   └── publisher/
│   │       └── OrderEventPublisher.java  ← Publishes events
│   └── handler/
│       └── ShipmentCreatedHandler.java   ← Consumes events from shipping
│
├── shipping/
│   ├── event/
│   │   └── listener/
│   │       └── OrderPlacedListener.java  ← Consumes OrderPlacedEvent
│   └── event/publisher/
│       └── ShipmentEventPublisher.java   ← Publishes ShipmentCreatedEvent
│
└── notification/
    └── event/listener/
        ├── OrderPlacedListener.java      ← Consumes OrderPlacedEvent
        └── ShipmentCreatedListener.java  ← Consumes ShipmentCreatedEvent
```

### Event Versioning Strategy

```java
// V1 of the event
public class OrderPlacedEventV1 {
    private String orderId;
    private String customerId;
    private BigDecimal total;
}

// V2 adds payment method
public class OrderPlacedEventV2 extends OrderPlacedEventV1 {
    private String paymentMethodType;
    private String paymentMethodLast4;
}

// Consumer handles both
@EventHandler
public void onOrderPlaced(OrderPlacedEvent event) {
    if (event instanceof OrderPlacedEventV2 v2) {
        // Use payment method info
    } else {
        // V1: no payment info available
    }
}
```

## 4. Runtime Behavior

### CQRS Write Flow

```
POST /orders
  │
  ▼
OrderCommandController
  │ @PostMapping
  │ Validates request
  │ Creates CreateOrderCommand
  │
  ▼
CreateOrderCommandHandler.handle(cmd)
  │ @Transactional
  │
  ├── OrderAggregate.create(...)
  │     Validates invariants
  │     Creates Order with domain events
  │
  ├── orderCommandRepository.save(order)
  │     INSERT INTO orders (...)
  │     INSERT INTO order_items (...)
  │
  ├── eventPublisher.publish(order.getEvents())
  │     │
  │     ▼ (async or sync depending on config)
  │   OrderProjector.onOrderCreated(event)
  │     INSERT INTO order_read_model (...)
  │   InventoryListener.onOrderPlaced(event)
  │     UPDATE inventory SET reserved = reserved + ?
  │   NotificationListener.onOrderPlaced(event)
  │     sendEmail(...)
  │
  └── return order.getId()
  │
  ▼
OrderCommandController
  201 Created { "orderId": "abc-123" }
```

### CQRS Read Flow (from optimized read model)

```
GET /orders/abc-123
  │
  ▼
OrderQueryController
  │ @GetMapping("/{id}")
  │ Creates GetOrderQuery
  │
  ▼
GetOrderQueryHandler.handle(query)
  │ NO @Transactional (read-only, no transaction needed)
  │
  │ Query from read model (denormalized, indexed for reads)
  │ SELECT * FROM order_read_model WHERE id = ?
  │
  └── return OrderReadModel
  │
  ▼
OrderQueryController
  200 { "id": "abc-123", "status": "CREATED", "customerName": "Alice", ... }
```

## 5. Request Flow Diagrams

### Synchronous Event Processing (Monolith)

```
[OrderService]
    │ publish(OrderPlacedEvent)
    ▼
[ApplicationEventMulticaster]  ← In-process, same thread
    │
    ├──▶ [PaymentListener]  ← Same thread!
    │      │ processPayment()
    │      │ publish(PaymentCompletedEvent)
    │      ▼
    │    
    ├──▶ [InventoryListener]
    │      reserveStock()
    │    
    └──▶ [NotificationListener]
           sendEmail()
```

Risk: If `PaymentListener` is slow, the entire request blocks. Solution: `@Async`.

### Asynchronous Event Processing (Monolith)

```
[OrderService]  ← Thread: http-nio-8080-exec-1
    │ publish(OrderPlacedEvent)
    │ return 202
    ▼
[ApplicationEventMulticaster with ThreadPoolTaskExecutor]
    │
    ├──▶ Thread: task-1 → [PaymentListener]
    │      processPayment()
    │
    ├──▶ Thread: task-2 → [InventoryListener]
    │      reserveStock()
    │
    └──▶ Thread: task-3 → [NotificationListener]
           sendEmail()
```

## 6. Lifecycle Diagrams

### Architecture Evolution: The Real Path

```
STAGE 1: CRUD Monolith
  Packages: controller/, service/, repository/
  DB: Single schema, everything joined
  Deploy: Single JAR

STAGE 2: Feature Packages + Domain Events
  Packages: order/, payment/, shipping/
  Communication: ApplicationEventPublisher
  DB: Single schema (still), but domains own their tables
  Deploy: Single JAR

STAGE 3: CQRS Within Monolith
  Each domain has command/query separation
  Read models (materialized views) in same DB
  Deploy: Single JAR

STAGE 4: Extract Read Service
  catalog-query-service: Optimized for product search
  catalog-command-service: Optimized for product management
  DB: Same for now, separate schema
  Deploy: 2 JARs

STAGE 5: Event-Driven Microservices
  Each domain = service
  Communication: Kafka / RabbitMQ
  DB: Database per service
  Deploy: N JARs, Kubernetes

STAGE 6: CQRS + Event Sourcing
  Event store as source of truth
  Projections for all read models
  DB: Event store + multiple read stores
  Deploy: N JARs, Kubernetes
```

### Triggers for Each Stage

| From Stage | To Stage | Trigger |
|-----------|---------|---------|
| 1 | 2 | Team growth, merge conflicts in service layer |
| 2 | 3 | Query performance degrades with complex joins |
| 3 | 4 | Search latency exceeds SLA, need separate scaling |
| 4 | 5 | Team autonomy requires independent deployments |
| 5 | 6 | Need full audit trail, temporal queries, replay capability |

## 7. Source Code Reading Guide

1. **Axon Framework**: Production-grade CQRS/Event Sourcing for Spring
   - `CommandGateway`, `QueryGateway`: Dispatches commands and queries
   - `AggregateLifecycle`: Manages aggregate state from events
   - `TrackingEventProcessor`: Reliable event processing with checkpoints

2. **Spring Cloud Stream**: Event-driven microservices
   - `@EnableBinding`: Declare message channels
   - `@StreamListener`: Consume messages

3. **Debezium**: CDC (Change Data Capture) for event-driven DB sync
   - Reads PostgreSQL WAL log → publishes events to Kafka → consumers update read models

## 8. Production Failure Scenarios

### Scenario 1: Read Model Drift

**Symptom**: Order shows status "CREATED" on read side but "CANCELLED" in write DB. Customer sees stale data.

**Root cause**: Read model projection failed silently. Event was published, but the projector threw an exception that was swallowed.

**Detection**: Reconciliation job: periodically compare write DB vs read DB and report drift.

**Resolution**: 
1. Make projectors idempotent (they can be replayed)
2. Add dead letter queue for failed events
3. Run reconciliation on a schedule
4. Alert on drift exceeding threshold

### Scenario 2: Event Version Incompatibility

**Symptom**: `OrderPlacedEvent` deserialization fails after schema change. All downstream consumers stop processing.

**Root cause**: Producer added a field without backward compatibility. Consumer can't deserialize the new format.

**Resolution**: 
1. Use a schema registry (Confluent, Apicurio)
2. Follow backward compatibility rules: new fields must have defaults
3. Never rename or remove fields — only add
4. Deprecate fields, remove in next major version

### Scenario 3: Event Order Violation

**Symptom**: Order "CANCELLED" event processed BEFORE "CREATED" event. Read model shows null or inconsistent state.

**Root cause**: Events published from different threads/partitions arrive out of order.

**Resolution**: 
1. Partition by aggregate ID (events for Order #123 always go to same partition → ordered)
2. Include sequence number in events
3. Consumer checks: if event.sequence > lastSeen + 1, buffer and wait for missing event

## 9. Debugging Techniques

### CQRS Debugging

```java
// Command handler logging
@Slf4j
@Component
public class LoggingCommandHandlerDecorator<T extends Command<R>, R> 
        implements CommandHandler<T, R> {
    
    private final CommandHandler<T, R> delegate;
    
    @Override
    public R handle(T command) {
        log.info("Handling command: {}", command);
        StopWatch sw = new StopWatch();
        sw.start();
        try {
            R result = delegate.handle(command);
            log.info("Command succeeded: {} → {} ({}ms)", command, result, sw.getTotalTimeMillis());
            return result;
        } catch (Exception e) {
            log.error("Command failed: {} ({}ms)", command, sw.getTotalTimeMillis(), e);
            throw e;
        }
    }
}
```

### Event Tracing

```java
// Add correlation to all events
public abstract class DomainEvent {
    private final String eventId = UUID.randomUUID().toString();
    private final String causationId;   // Previous event ID that caused this
    private final String correlationId; // Root event ID (original trigger)
    private final String traceId = MDC.get("traceId");
    
    // This creates a causal chain:
    // HTTP Request → OrderPlaced(evt-1) 
    //              → PaymentInitiated(evt-2, causation=evt-1)
    //              → PaymentCompleted(evt-3, causation=evt-2)
    //              → OrderConfirmed(evt-4, causation=evt-3)
    // You can trace the entire chain even across async boundaries.
}
```

## 10. Observability Considerations

Event-driven systems need distinct observability:

| Metric | What to Track |
|--------|--------------|
| Event publish rate | orders_placed_total, payments_completed_total |
| Event processing lag | time between publish and last consumer processed |
| Dead letter queue size | events that failed all retries |
| Read model lag | time between write and read model update |
| Event processing errors | failures by event type, handler |

## 11. Performance Implications

### CQRS Read Performance

```
Traditional (JPA):
  Order order = entityManager.find(Order.class, id);
  // Loads: Order, OrderItems, Customer, Payment, Shipment (5+ tables)
  // If lazy-loaded: N+1 queries
  // Time: 50-200ms (with joins) or 500ms+ (N+1)

CQRS Read (Optimized):
  SELECT * FROM order_read_model WHERE id = ?
  // Single table, all data denormalized
  // Time: 2-5ms
  
Trade-off: Storage duplication, eventual consistency, projection maintenance cost.
```

### Event-Driven vs Request-Driven Latency

```
Request-Driven (REST sync):
  Client → A → B → C → Client
  Latency: sum(A, B, C)
  Availability: product(A, B, C)  ← If any fails, the chain fails

Event-Driven (async):
  Client → A → return (fast!)
           A → publishes event → B → publishes event → C
  Latency: A only (for client)
  Availability: A only (client-facing)
  But: eventual consistency, harder to debug
```

## 12. Architecture Implications

### When CQRS Adds Value
- Read and write patterns are fundamentally different
- Read models require different optimization than write models
- Read/write ratio is heavily skewed (>100:1 reads to writes)
- Need to scale reads and writes independently

### When CQRS is Unnecessary
- Simple CRUD with similar read/write models
- Low query complexity (no complex joins or aggregations)
- Single database handles both reads and writes adequately
- Team cannot manage eventual consistency

### When Event-Driven Architecture Adds Value
- Multiple consumers need to react to the same event
- Services/contexts need to be truly decoupled
- Audit trail is required
- Future consumers are unknown (extensibility)

### When Event-Driven is Unnecessary
- Simple request-response patterns
- Only one consumer for each event
- Synchronous responses are required
- System is small enough to reason about synchronously

## 13. Team Ownership Implications

| Pattern | Team Coordination | Contract Management |
|---------|------------------|-------------------|
| Synchronous API | Tight coupling. Team A must be available when Team B calls | API versioning |
| Shared events | Loose coupling. Teams publish and forget | Event schema versioning |
| CQRS within team | No coordination (same team) | Internal only |

**Event ownership rule**: The producer owns the event schema. The consumer adapts. If the consumer needs changes, they negotiate with the producer. Never let consumers dictate producer's domain events — that's the path to a distributed monolith.

## 14. Interview Questions

1. **"Is CQRS just separating reads and writes?"**
   - **Answer**: At minimum, yes — using different models (not necessarily different databases). The command model enforces invariants; the query model optimizes for display. Different databases is an optimization when read/write loads need independent scaling. Event sourcing is a related but separate pattern (event store as write model, projections as read models).

2. **"How do you handle transactional consistency in event-driven architecture?"**
   - **Answer**: You accept eventual consistency. The Outbox Pattern ensures at-least-once event publication: write to database + write event to outbox table → single transaction. A separate process reads the outbox and publishes to Kafka. If publishing fails, the outbox entry remains and is retried. Consumers must be idempotent. For cross-service transactions, use sagas with compensating actions.

3. **"When should you NOT use event sourcing?"**
   - **Answer**: When you don't need audit trail, temporal queries, or event replay. Event sourcing adds enormous complexity: event schema evolution, snapshotting, projection rebuilding, eventual consistency. Most business applications are better served by state-based persistence. Event sourcing is for accounting, compliance, and complex workflow systems.

## 15. Hands-On Exercises

1. **Implement CQRS in a simple Spring Boot app**: Separate `OrderCommandService` and `OrderQueryService`. Use JPA for writes, JDBC for reads. Measure performance difference.

2. **Implement the Outbox Pattern**: Replace direct event publishing with outbox table + scheduled publisher. Verify at-least-once delivery.

3. **Build event replay**: Implement a mechanism to replay all events from a point in time to rebuild read models. Test with a corrupted read model.

## 16. Advanced Challenges

1. **Implement event sourcing from scratch**: Store events as JSON in PostgreSQL. Implement aggregate replay. Add snapshotting. Handle concurrent writes with optimistic locking on aggregate version.

2. **Design a cross-service saga**: Implement a saga orchestrator for the "place order" flow spanning 4 services. Handle compensation for each step. Test every failure scenario.

3. **Build a schema evolution strategy**: Design a system that can handle 3 versions of the same event type concurrently. Producers publish the latest version. Consumers can handle all versions. Schema registry enforces compatibility.
