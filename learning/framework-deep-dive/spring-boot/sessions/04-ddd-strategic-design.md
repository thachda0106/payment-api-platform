# Session 04: Domain-Driven Design — Strategic Patterns

## 1. Why This Topic Exists

DDD is NOT about entities, value objects, and repositories. That's tactical DDD — useful but not transformative. **Strategic DDD** is about drawing lines around business capabilities and defining how they communicate. It's the single most powerful tool for decomposing large systems.

Without strategic DDD, you get the "Big Ball of Mud" anti-pattern: 200 services that all talk to each other because nobody agreed on boundaries. The cure is bounded contexts.

**Staff engineer insight**: If you master one DDD concept, master **Bounded Contexts**. All architecture mistakes at scale are ultimately context boundary mistakes.

## 2. Mental Model

```
The Business Domain (e.g., E-Commerce)

┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   IDENTITY   │    │   ORDERING   │    │   BILLING    │
│   CONTEXT    │    │   CONTEXT    │    │   CONTEXT    │
│              │    │              │    │              │
│  User        │    │  Order       │    │  Payment     │
│  Profile     │    │  OrderItem   │    │  Invoice     │
│  Auth        │    │  Fulfillment │    │  Refund      │
│              │    │              │    │              │
│ "Customer"   │◄──►│ "Buyer"      │◄──►│ "Payer"      │
│  (user term) │    │  (order term)│    │  (billing    │
│              │    │              │    │   term)      │
└──────────────┘    └──────────────┘    └──────────────┘
       │                   │                    │
       │    ┌──────────────┤                    │
       │    ▼              ▼                    │
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   CATALOG    │    │  INVENTORY   │    │ NOTIFICATION │
│              │    │              │    │              │
│  Product     │    │  Stock       │    │  Email       │
│  Category    │    │  Warehouse   │    │  SMS         │
│  Price       │    │  Allocation  │    │  Push        │
└──────────────┘    └──────────────┘    └──────────────┘
```

**Critical insight**: The SAME real-world thing means DIFFERENT things in different contexts:
- In **Identity**, a "Customer" is a profile with credentials
- In **Ordering**, a "Buyer" is a delivery address and order history
- In **Billing**, a "Payer" is payment methods and credit scores

Each context has its own model, its own database, its own vocabulary. This is intentional — the models serve different purposes.

## 3. Internal Architecture

### Bounded Context Definition

A bounded context is a **linguistic and logical boundary** where a particular domain model is valid. Inside the context, terms have precise, unambiguous meanings. Outside the context, the same term may mean something different.

```
Bounded Context: ORDERING

Inside this context:
  "Order" = a buyer's intent to purchase, with line items, status, delivery address

Outside this context (in INVENTORY):
  "Order" = a demand signal that reduces available stock

These are DIFFERENT Order objects in DIFFERENT databases.
They share only an orderId for correlation.
```

### Context Map Patterns

#### 1. Shared Kernel

Two contexts share a subset of the domain model. Used when full separation is too costly.

```
IDENTITY ←──Shared──→ ORDERING
              │
        ┌─────┴─────┐
        │ CustomerId │  ← shared between contexts
        │ Address    │  ← same model, same semantics
        └───────────┘

Risks: Changes to shared kernel require coordination.
When to use: When teams are collocated and communicate frequently.
When to avoid: When teams are independent (creates coupling).
```

#### 2. Customer-Supplier (Upstream-Downstream)

```
UPSTREAM (Catalog) ──products data──▶ DOWNSTREAM (Ordering)
    │                                      │
    └── Sets the pace                      └── Must adapt to upstream changes

Catalog team decides the API.
Ordering team consumes it and adapts.
```

#### 3. Conformist

```
UPSTREAM (Legacy ERP) ──weird data──▶ DOWNSTREAM (Ordering - conformist)
    
Ordering simply accepts whatever the ERP gives. 
No translation layer. No negotiation.

When to use: When upstream has all the power and won't change.
Cost: Downstream model is corrupted by upstream's model.
```

#### 4. Anti-Corruption Layer (ACL)

```
┌──────────┐     ┌──────────────┐     ┌──────────┐
│ ORDERING │     │     ACL      │     │ LEGACY   │
│ (clean)  │────▶│ (translator) │────▶│ ERP      │
│          │◀────│              │◀────│          │
└──────────┘     └──────────────┘     └──────────┘

The ACL translates between two models.
Ordering's model remains clean.
Legacy's model remains unchanged.
The ACL absorbs the complexity.
```

```java
// Anti-Corruption Layer implementation
@Service
public class LegacyErpAntiCorruptionLayer {
    
    public OrderCreatedEvent translate(LegacyOrderMessage legacy) {
        // Translate legacy field names to domain language
        return new OrderCreatedEvent(
            new OrderId(legacy.getZ_ORD_NBR()),           // z_ORD_NBR → orderId
            new CustomerId(legacy.getZ_CUST_ID()),         // z_CUST_ID → customerId
            LocalDateTime.parse(legacy.getZ_CRTD_DT()),    // z_CRTD_DT → createdAt
            // ... handle all the legacy quirks here
        );
    }
    
    public LegacyOrderMessage translateBack(Order order) {
        LegacyOrderMessage msg = new LegacyOrderMessage();
        msg.setZ_ORD_NBR(order.getId().getValue());
        msg.setZ_CUST_ID(order.getCustomerId().getValue());
        // ... map back
        return msg;
    }
}
```

#### 5. Open Host Service + Published Language

```
┌──────────┐     REST/GraphQL     ┌──────────┐
│ ORDERING │─────────────────────│ ANYONE   │
│  (OHS)   │  Published Language  │          │
└──────────┘  (well-documented)   └──────────┘

Ordering exposes a well-documented, stable API.
Any context can consume it.
Like a public API, but internal.
```

### Context Map Visualization

```
        ┌──────────┐  Customer-  ┌──────────┐
        │ CATALOG  │──Supplier──▶│ ORDERING │
        └──────────┘             └──────────┘
                                      │
                     ┌────────────────┼────────────────┐
                     │                │                │
              Customer-Supplier  Customer-Supplier  Conformist
                     │                │                │
                     ▼                ▼                ▼
              ┌──────────┐    ┌──────────┐    ┌──────────┐
              │ BILLING  │    │INVENTORY │    │  LEGACY  │
              │          │    │          │    │   ERP    │
              └──────────┘    └──────────┘    └──────────┘
                     │
               ACL (translates)
                     │
                     ▼
              ┌──────────┐
              │ PAYMENT  │
              │ GATEWAY  │
              └──────────┘
```

## 4. Runtime Behavior

### Cross-Context Communication Patterns

**Synchronous (REST/gRPC)**:
```
Ordering needs Customer data:
  GET /identity/customers/{id}
  ← CustomerDto { id, name, email }
  
  Ordering maps CustomerDto → Buyer (its own model)
  
Trade-off: Coupling in time (Identity must be up).
           Simpler model. Immediate consistency.
```

**Asynchronous (Domain Events)**:
```
Ordering creates order:
  → Publishes OrderPlaced { orderId, customerId, items, total }
  
  Inventory listens:
    → Reserves stock (its own model)
    → Publishes StockReserved { orderId, warehouseId }
  
  Billing listens:
    → Creates invoice (its own model)
    → Publishes InvoiceCreated { orderId, invoiceId }
  
Trade-off: Eventual consistency.
           Decoupled in time. Each context can be down without blocking others.
           Complex failure handling. Requires idempotency.
```

## 5. Request Flow Diagrams

### Synchronous Cross-Context Flow

```
[Client] → POST /orders
    │
    ▼
OrderingContext
    │ 1. Validate command
    │ 2. GET /identity/customers/{id}  ← synchronous call to Identity
    │ 3. GET /catalog/products/{id}    ← synchronous call to Catalog
    │ 4. POST /inventory/reserve        ← synchronous call to Inventory
    │ 5. Save Order
    │ 6. Return 201
    ▼
[Client] ← 201 Created

Problem: If (2) succeeds but (4) fails, Ordering must roll back.
         The Inventory reserve was already committed → inconsistency.
Solution: Use saga pattern or accept eventual consistency.
```

### Asynchronous Cross-Context Flow (Saga)

```
[Client] → POST /orders
    │
    ▼
OrderingContext
    │ 1. Save Order (status=PENDING)
    │ 2. Publish OrderPlaced event
    │ 3. Return 202 Accepted
    ▼
[Client] ← 202 { orderId, status: "PENDING" }

    ↓ (async)
InventoryContext receives OrderPlaced
    │ Reserve stock
    │ Publish StockReserved event
    ▼
BillingContext receives StockReserved
    │ Create invoice
    │ Publish InvoiceCreated event
    ▼
OrderingContext receives InvoiceCreated
    │ Update Order status → CONFIRMED
    │ Publish OrderConfirmed event
    ▼
NotificationContext receives OrderConfirmed
    │ Send email to buyer
```

## 6. Lifecycle Diagrams

### Context Identification Process

```
Phase 1: Event Storming
  │
  ├── Gather domain experts + engineers
  ├── Identify domain events on sticky notes
  ├── Group events that cluster together
  └── Draw boundaries around clusters → Candidate Bounded Contexts

Phase 2: Context Mapping
  │
  ├── For each pair of contexts, define relationship
  ├── Shared Kernel? Customer-Supplier? ACL?
  └── Document in Context Map

Phase 3: Model Design
  │
  ├── Within each context, design Ubiquitous Language
  ├── Define Aggregates, Entities, Value Objects
  └── Implement

Phase 4: Evolution
  │
  ├── Monitor context cohesion
  ├── Split contexts that grow too large
  ├── Merge contexts that are too coupled
  └── Repeat
```

### Organizational Mapping (Conway's Law Applied)

```
Architecture → Team Structure

Bounded Context: ORDERING  →  Team: Order Team (5-8 engineers)
Bounded Context: BILLING   →  Team: Billing Team (4-6 engineers)
Bounded Context: CATALOG   →  Team: Catalog Team (3-5 engineers)

Each team:
  - Owns their context's code
  - Owns their database
  - Publishes their API/events
  - Consumes others' APIs/events
  - Deploys independently
```

## 7. Source Code Reading Guide

1. **Axon Framework** (`https://github.com/AxonFramework/AxonFramework`): Reference implementation of DDD/CQRS/Event Sourcing
   - `Aggregate`: How Axon manages aggregate lifecycle
   - `CommandBus`/`EventBus`: Cross-context message routing
   
2. **Spring Modulith** (`https://github.com/spring-projects/spring-modulith`):
   - `@ApplicationModuleListener`: Cross-module event handling
   - `ModuleTest`: Verifies module (context) boundaries

3. **jMolecules** (`https://github.com/xmolecules/jmolecules`):
   - Annotations for DDD building blocks
   - `@Entity`, `@ValueObject`, `@AggregateRoot`, `@Repository`

## 8. Production Failure Scenarios

### Scenario 1: Wrong Context Boundary

**Symptom**: Every `Order` change requires changing the `User` service. Deployment coupling is 1:1.

**Root cause**: Ordering context imports `User` entity from Identity context instead of maintaining its own `Buyer` model. The models are coupled at the code level.

**Resolution**: Introduce `Buyer` in Ordering context with only the fields Ordering needs (`buyerId`, `deliveryAddress`). Fetch buyer data via API, not database join.

### Scenario 2: Missing Anti-Corruption Layer

**Symptom**: Your clean domain model has fields like `legacy_sys_cd`, `z_proc_flag`, `old_cust_type`. Domain logic is littered with legacy translation code.

**Root cause**: No ACL between your domain and the legacy system. Legacy model leaked in.

**Resolution**: Build an ACL that translates legacy message → clean domain event → legacy message. Keep the legacy corruption contained in one layer.

### Scenario 3: Context Coupling via Shared Database

**Symptom**: Ordering team changes a column. Billing team's reports break. Inventory team's queries return wrong results.

**Root cause**: Three contexts share the same database schema. No bounded context is truly bounded because the database couples them.

**Resolution**: Each context gets its own schema (or at least its own set of tables). No cross-context database joins. APIs and events for data sharing.

## 9. Debugging Techniques

### Detecting Context Boundary Violations

```java
// ArchUnit: Verifying context boundaries
@Test
void orderingContextMustNotImportIdentityContextInternals() {
    noClasses()
        .that().resideInAPackage("..ordering.core..")
        .should().dependOnClassesThat()
        .resideInAPackage("..identity.core..")
        .check(classes);
}

// Only allow depending on identity-api
@Test
void orderingContextMayOnlyUseIdentityApi() {
    classes()
        .that().resideInAPackage("..ordering.core..")
        .should().onlyAccessClassesThat()
        .resideInAnyPackage(
            "..ordering..",    // Self
            "..identity.api..", // Only API, not core
            "..shared..",
            "java.."
        )
        .check(classes);
}
```

## 10. Observability Considerations

Cross-context tracing requires explicit context propagation:

```java
@Data
public class OrderPlacedEvent {
    private String orderId;
    private String traceId;       // From MDC
    private String spanId;        // From OpenTelemetry
    private String customerId;
    private List<OrderLineItem> items;
}

// Producer
events.publishEvent(new OrderPlacedEvent(
    orderId,
    MDC.get("traceId"),
    Span.current().getSpanContext().getSpanId(),
    customerId,
    items
));

// Consumer
@EventListener
public void onOrderPlaced(OrderPlacedEvent event) {
    MDC.put("traceId", event.getTraceId());
    Span span = tracer.spanBuilder("handle-order-placed")
        .setParent(Context.current().with(
            Span.wrap(TraceContextFromString(event.getSpanId()))
        ))
        .startSpan();
    try (Scope ignored = span.makeCurrent()) {
        // processing
    } finally {
        span.end();
        MDC.clear();
    }
}
```

## 11. Performance Implications

| Communication Pattern | Latency | Consistency | Coupling |
|----------------------|---------|-------------|----------|
| Direct DB join (same DB) | <1ms | Immediate | Very High |
| REST/gRPC sync call | 1-50ms | Immediate (mostly) | High (temporal) |
| Domain event (sync) | <1ms | Immediate | Low |
| Domain event (async) | 10-100ms | Eventual | Lowest |

The performance cost of bounded contexts is primarily in query patterns: you can't do `SELECT * FROM orders JOIN users ON ...`. You must make API calls or maintain local projections. This is the core trade-off: **query flexibility vs team autonomy**.

## 12. Architecture Implications

### When to Use Strategic DDD
- Complex business domain (insurance, banking, healthcare)
- Multiple teams with independent velocity
- Long-lived system (>5 years expected lifetime)
- Domain experts and engineers can collaborate

### When NOT to Use Strategic DDD
- Simple CRUD applications (most internal tools)
- Data-intensive without complex rules (analytics platforms)
- Short-lived systems (prototypes, MVPs that will be rewritten)
- Team cannot access domain experts

### Organizational Implications
```
DDD + Conway's Law = Organization Design

You CANNOT have:
  - 3 bounded contexts
  - 1 team owning all 3
  → The team will merge the contexts in practice

You SHOULD have:
  - 1 bounded context → 1 team
  - Team owns code, database, deployment
  - Cross-team communication via APIs and events
```

## 13. Team Ownership Implications

| Context Pattern | Team Structure | Coordination |
|----------------|---------------|-------------|
| Shared Kernel | Collocated teams | Daily sync |
| Customer-Supplier | Supplier team decides API | Weekly alignment |
| Conformist | Downstream adapts | Minimal |
| ACL | ACL team owns translator | Integration testing |
| Open Host Service | OHS team serves many | API versioning discipline |

## 14. Interview Questions

1. **"How do you identify bounded contexts in a brownfield system?"**
   - **Answer**: Look for linguistic boundaries. Where does the same term mean different things to different teams? Event Storming with domain experts. Analyze data — where are the natural transaction boundaries? Look at team communication patterns — Conway's Law tells you where boundaries already exist implicitly.

2. **"When would you merge two bounded contexts?"**
   - **Answer**: When the coordination cost of keeping them separate exceeds the autonomy benefit. Signs: every change requires both contexts to change simultaneously, the ACL is more code than the domains themselves, or the contexts share the same ubiquitous language (they were never truly separate).

3. **"How do you handle transactions that span bounded contexts?"**
   - **Answer**: You don't. Distributed transactions (2PC) don't scale. Use sagas (choreography or orchestration) with compensating actions. Accept eventual consistency. Design idempotent consumers. This is one of the hardest parts of DDD at scale — the business must accept that "order placed" and "payment processed" are eventually consistent.

## 15. Hands-On Exercises

1. **Event Storming**: Gather 3-5 people. Model an e-commerce domain on a whiteboard. Identify domain events → commands → aggregates → bounded contexts. Draw a context map.

2. **Context Mapping**: Take an existing system. Draw the current context map (reality, not ideal). Identify where reality violates DDD principles. Prioritize fixes by pain.

3. **Build an ACL**: Given a legacy system with terrible naming (`FLG_01`, `Z_AMT`, `CUST_TYP_CD`), build an anti-corruption layer that translates to a clean domain model.

## 16. Advanced Challenges

1. **Design a multi-context system with event sourcing**: Each context has its own event store. Cross-context sagas coordinate business processes. Design the event schema evolution strategy.

2. **Implement a context cannibalization strategy**: Given two contexts that grew too coupled, design a plan to merge them without downtime. Handle data migration, API deprecation, team restructuring.

3. **Design a context extraction strategy**: Given a monolith with implicit contexts, design the step-by-step plan to extract one context as a separate service. Include: data ownership, API design, event flows, deployment, rollback.
