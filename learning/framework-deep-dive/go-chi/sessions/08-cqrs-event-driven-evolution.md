# Session 08: CQRS, Event-Driven Architecture & Architecture Evolution

## Why This Topic Exists

Most Go HTTP services begin life as a simple CRUD application: a single PostgreSQL database, a Chi router, and handlers that read and write the same tables using the same models. This works until it doesn't. The first crack typically appears when the read patterns diverge from the write patterns: the `GET /orders` endpoint needs to join 5 tables and return a denormalized view, while `POST /orders` writes to exactly 2 tables. The same `Order` struct serves both purposes, accumulating `json:"-,omitempty"` tags for fields that don't apply, and database queries that `SELECT *` return 30 columns when the read only needs 6. This is the core problem that Command Query Responsibility Segregation (CQRS) solves: separate the model used for writes (commands) from the model used for reads (queries).

The second crack appears when the service needs to react to things that happened in other services. An order is created in the orders service, and the inventory service needs to decrement stock, the notification service needs to send an email, and the analytics service needs to update its dashboard. Without events, these become synchronous HTTP calls: the orders handler calls the inventory service, waits for a response, calls the notification service, waits again—and if any of these fail at 3 AM, the order fails too. Event-driven architecture decouples these concerns: the orders service emits an `OrderCreated` event and moves on. Other services react independently. This is not just a performance optimization; it is a reliability strategy.

Understanding CQRS and event-driven architecture is essential for staff and principal engineers because these patterns represent inflection points in a system's life. A 200-line CRUD service becomes unmaintainable at 50+ endpoints with complex read models. A synchronous microservice mesh becomes unreliable at 5+ services with cross-cutting concerns. The decision to adopt CQRS or events is irreversible in practice—once you have separate read/write models and event-driven communication, rolling back is a major architectural effort. You need to know when these patterns are necessary, when they are premature, and how to implement them in idiomatic Go.

## Mental Model

CQRS separates the write path (commands) from the read path (queries). A command is an intention to change state—it can fail due to business rules. A query is a request for information—it should never fail due to business rules, only due to infrastructure issues. In Go, this separation manifests as distinct types, distinct handlers, and often distinct data stores. The write model uses a normalized PostgreSQL schema optimized for transactional integrity. The read model uses a denormalized view—possibly in the same PostgreSQL (materialized views), possibly in a separate read replica, possibly in Elasticsearch or a dedicated read-optimized database.

Event-driven architecture models state changes as facts. An event is not a request ("please send an email")—it is a statement of past fact ("an order was created at 14:32 UTC by user 42 for $99.99"). This distinction is critical: events cannot be rejected. If the notification service is down when `OrderCreated` fires, the event is still true—the order was created. The notification service catches up when it comes back online. In Go, events are typically represented as structs implementing a `DomainEvent` interface, published to an `EventBus` interface, and consumed by `EventHandler` implementations.

```
CQRS Architecture (Single Service):

                    ┌──────────────────────────────┐
                    │        Chi Router             │
                    │                               │
     POST /orders ──┤                               ├── GET /orders
                    │   Command Side     Query Side  │
                    └──────┬──────────────────┬──────┘
                           │                  │
                    ┌──────▼──────┐    ┌──────▼──────┐
                    │  Command    │    │   Query     │
                    │  Handler    │    │   Handler   │
                    │             │    │             │
                    │ Validate    │    │  Build SQL  │
                    │ Business    │    │  or Search  │
                    │ Rules       │    │  Query      │
                    └──────┬──────┘    └──────┬──────┘
                           │                  │
                    ┌──────▼──────┐    ┌──────▼──────┐
                    │ Write DB    │    │  Read DB    │
                    │ (Normalized)│    │ (Denorm'd)  │
                    │ PostgreSQL  │    │ PostgreSQL  │
                    │             │    │ Materialized│
                    └──────┬──────┘    │ View or     │
                           │           │ Elasticsearch│
                           │           └──────▲──────┘
                           │                  │
                           │    ┌─────────────┴──────┐
                           │    │  Event-Driven Sync │
                           └───►│  (Outbox + Poller  │
                                │   or LISTEN/NOTIFY)│
                                └────────────────────┘
```

```
Event-Driven Flow (Multi-Service):

  Orders Service        Event Bus (Kafka/NATS)     Inventory Service    Notification Svc
       │                        │                        │                    │
       │ OrderCreated Event     │                        │                    │
       │───────────────────────>│                        │                    │
       │                        │ OrderCreated            │                    │
       │                        │───────────────────────>│ DeductStock()      │
       │                        │                        │─────── business ───│
       │                        │                        │                    │
       │                        │                        │ StockDeducted Event │
       │                        │                        │───────────────────>│
       │                        │                        │                    │
       │                        │ OrderCreated            │                    │
       │                        │────────────────────────────────────────────>│ SendEmail()
       │                        │                                             │──── business ──
       │                        │                                             │
       │                        │  OrderCreated           │                    │
       │                        │─────────────────────────│                    │
       │                        │─────────────────────────────────────────────│
       │                        │                                             │
       │                        │  (All consumers process                    │
       │                        │   independently, async)                    │
```

Key insight: the event bus is the backbone. If it's down, nothing works. If it's slow, everything degrades. Choosing the right event infrastructure (Kafka for high throughput, NATS for simplicity, PostgreSQL LISTEN/NOTIFY for minimal infrastructure) is a critical architecture decision.

## Internal Architecture

### CQRS Command Handler Pattern in Go

A command handler is a struct with a single `Handle` method. It accepts a command struct (the intention) and returns an error (indicating business rule violation or infrastructure failure). The command handler encapsulates all write-side business logic:

```go
// commands/create_order.go
package commands

import (
    "context"
    "fmt"
)

type CreateOrderCommand struct {
    OrderID  string
    UserID   string
    Items    []OrderItemCommand
}

type OrderItemCommand struct {
    ProductID string
    Quantity  int
}

type CreateOrderHandler struct {
    orderRepo   OrderWriteRepository
    productRepo ProductWriteRepository
    eventBus    EventBus
}

func NewCreateOrderHandler(
    orderRepo OrderWriteRepository,
    productRepo ProductWriteRepository,
    eventBus EventBus,
) *CreateOrderHandler {
    return &CreateOrderHandler{
        orderRepo:   orderRepo,
        productRepo: productRepo,
        eventBus:    eventBus,
    }
}

func (h *CreateOrderHandler) Handle(ctx context.Context, cmd CreateOrderCommand) error {
    if len(cmd.Items) == 0 {
        return fmt.Errorf("%w: order must have at least one item", ErrInvalidCommand)
    }
    for _, item := range cmd.Items {
        product, err := h.productRepo.FindByID(ctx, item.ProductID)
        if err != nil {
            return fmt.Errorf("product %s: %w", item.ProductID, err)
        }
        if product.Stock < item.Quantity {
            return fmt.Errorf("%w: insufficient stock for %s", ErrBusinessRule, item.ProductID)
        }
    }
    order := buildOrderFromCommand(cmd)
    if err := h.orderRepo.Save(ctx, order); err != nil {
        return fmt.Errorf("saving order: %w", err)
    }
    h.eventBus.Publish(ctx, OrderCreatedEvent{
        OrderID: cmd.OrderID,
        UserID:  cmd.UserID,
        Items:   cmd.Items,
        At:      order.CreatedAt,
    })
    return nil
}

func buildOrderFromCommand(cmd CreateOrderCommand) WriteOrder {
    return WriteOrder{
        ID:     cmd.OrderID,
        UserID: cmd.UserID,
        Status: "pending",
        // map items...
    }
}
```

Notice the pattern: command handler accepts a command struct, validates business rules, calls repository methods, publishes events. The `WriteOrder` type (write model) is separate from `ReadOrder` (read model)—they may have different fields, different validation, different database mappings.

### CQRS Query Handler Pattern in Go

A query handler is a struct that accepts a query struct and returns a result struct. It never modifies state:

```go
// queries/get_order.go
package queries

import (
    "context"
)

type GetOrderQuery struct {
    OrderID string
}

type GetOrderResult struct {
    OrderID     string `json:"order_id"`
    UserID      string `json:"user_id"`
    Status      string `json:"status"`
    TotalAmount int64  `json:"total_amount"`
    ItemCount   int    `json:"item_count"`
    CreatedAt   string `json:"created_at"`
    // Denormalized: includes data from product and user tables
    UserName    string `json:"user_name"`
    UserEmail   string `json:"user_email"`
}

type GetOrderHandler struct {
    orderRepo OrderReadRepository
}

func NewGetOrderHandler(repo OrderReadRepository) *GetOrderHandler {
    return &GetOrderHandler{orderRepo: repo}
}

func (h *GetOrderHandler) Handle(ctx context.Context, q GetOrderQuery) (*GetOrderResult, error) {
    return h.orderRepo.FindByID(ctx, q.OrderID)
}
```

The `OrderReadRepository` interface is separate from `OrderWriteRepository`. The read repository may query a read replica, a materialized view, or Elasticsearch. It uses a query-optimized SQL that JOINs all necessary tables in one query, because reads are not constrained by transactional integrity—a slightly stale read is acceptable for most queries.

### Directory Structure for CQRS

```
orders/
  commands/
    create_order.go       ← CreateOrderCommand + CreateOrderHandler
    cancel_order.go       ← CancelOrderCommand + CancelOrderHandler
    refund_order.go       ← RefundOrderCommand + RefundOrderHandler
  queries/
    get_order.go          ← GetOrderQuery + GetOrderHandler
    list_orders.go        ← ListOrdersQuery + ListOrdersHandler
    order_summary.go      ← OrderSummaryQuery + OrderSummaryHandler
  models/
    write_order.go        ← WriteOrder struct (normalized, for writes)
    read_order.go         ← ReadOrder struct (denormalized, for reads)
  events/
    order_created.go      ← OrderCreatedEvent struct
    order_cancelled.go    ← OrderCancelledEvent struct
  repository/
    write_repo.go         ← OrderWriteRepository interface
    read_repo.go          ← OrderReadRepository interface
    postgres/
      write_repo_pg.go    ← PostgreSQL write implementation
      read_repo_pg.go     ← PostgreSQL read implementation (materialized views)
```

This structure makes the separation explicit: no file contains both a command and a query. A developer adding a new query never touches the `commands/` directory. A developer adding a new command never touches `queries/`. This reduces the risk of breaking reads while changing writes (or vice versa).

### Event-Driven: DomainEvent Interface

```go
// events/events.go
package events

import (
    "time"
)

type DomainEvent interface {
    EventName() string
    OccurredAt() time.Time
    AggregateID() string
}

type OrderCreatedEvent struct {
    OrderID    string    `json:"order_id"`
    UserID     string    `json:"user_id"`
    Items      []Item   `json:"items"`
    TotalAmount int64    `json:"total_amount"`
    At         time.Time `json:"occurred_at"`
}

func (e OrderCreatedEvent) EventName() string   { return "order.created" }
func (e OrderCreatedEvent) OccurredAt() time.Time { return e.At }
func (e OrderCreatedEvent) AggregateID() string { return e.OrderID }

type EventBus interface {
    Publish(ctx context.Context, event DomainEvent) error
    Subscribe(eventName string, handler EventHandler)
}

type EventHandler interface {
    Handle(ctx context.Context, event DomainEvent) error
}
```

This is a minimal event system in Go. The `DomainEvent` interface captures the three essential attributes of any event: what happened (EventName), when (OccurredAt), and to which entity (AggregateID). The `EventBus` interface abstracts the transport—it could be in-memory channels, PostgreSQL NOTIFY, NATS, or Kafka. The `EventHandler` interface is the consumer contract.

### EventBus Implementations

**In-memory (for testing, small applications)**:

```go
type InMemoryEventBus struct {
    mu       sync.RWMutex
    handlers map[string][]EventHandler
}

func (b *InMemoryEventBus) Publish(ctx context.Context, event DomainEvent) error {
    b.mu.RLock()
    handlers := b.handlers[event.EventName()]
    b.mu.RUnlock()
    for _, h := range handlers {
        go h.Handle(ctx, event) // fire and forget
    }
    return nil
}

func (b *InMemoryEventBus) Subscribe(eventName string, handler EventHandler) {
    b.mu.Lock()
    defer b.mu.Unlock()
    b.handlers[eventName] = append(b.handlers[eventName], handler)
}
```

Note the `go h.Handle(ctx, event)`—events are processed asynchronously. This means the publisher does not wait for consumers. It also means if the process crashes before a consumer finishes, the event is lost. For production, use a durable transport.

**NATS-based (lightweight production)**:

```go
type NATSEventBus struct {
    conn *nats.Conn
}

func (b *NATSEventBus) Publish(ctx context.Context, event DomainEvent) error {
    data, err := json.Marshal(event)
    if err != nil {
        return err
    }
    return b.conn.Publish("events."+event.EventName(), data)
}

func (b *NATSEventBus) Subscribe(eventName string, handler EventHandler) {
    b.conn.Subscribe("events."+eventName, func(msg *nats.Msg) {
        var event OrderCreatedEvent
        json.Unmarshal(msg.Data, &event)
        handler.Handle(context.Background(), event)
    })
}
```

NATS provides at-most-once delivery (pub/sub) or at-least-once (JetStream). For event-driven systems, JetStream's at-least-once semantics are usually preferred because losing an event (order created but inventory not deducted) is a data integrity problem.

### Outbox Pattern in Go

The outbox pattern solves the dual-write problem: you need to write to the database AND publish an event atomically. Without the outbox, you might write to PostgreSQL and then crash before publishing to Kafka. The order is saved but the event is lost.

```go
// Outbox table schema:
// CREATE TABLE outbox (
//     id UUID PRIMARY KEY,
//     aggregate_id VARCHAR NOT NULL,
//     event_type VARCHAR NOT NULL,
//     payload JSONB NOT NULL,
//     created_at TIMESTAMP NOT NULL DEFAULT NOW(),
//     published_at TIMESTAMP
// );

func (h *CreateOrderHandler) Handle(ctx context.Context, cmd CreateOrderCommand) error {
    tx, _ := h.db.BeginTx(ctx, nil)
    defer tx.Rollback()

    order := buildOrderFromCommand(cmd)
    if err := h.orderRepo.SaveWithTx(ctx, tx, order); err != nil {
        return err
    }

    event := OrderCreatedEvent{OrderID: cmd.OrderID, UserID: cmd.UserID, At: time.Now()}
    payload, _ := json.Marshal(event)
    _, err := tx.ExecContext(ctx,
        `INSERT INTO outbox (id, aggregate_id, event_type, payload)
         VALUES ($1, $2, $3, $4)`,
        uuid.New(), cmd.OrderID, "order.created", payload,
    )
    if err != nil {
        return err
    }
    return tx.Commit()
    // After commit, the outbox poller picks up the event and publishes to Kafka
}

// OutboxPoller runs in a background goroutine
func (p *OutboxPoller) Run(ctx context.Context) {
    ticker := time.NewTicker(100 * time.Millisecond)
    for {
        select {
        case <-ctx.Done():
            return
        case <-ticker.C:
            p.pollAndPublish(ctx)
        }
    }
}

func (p *OutboxPoller) pollAndPublish(ctx context.Context) {
    rows, _ := p.db.QueryContext(ctx,
        `SELECT id, aggregate_id, event_type, payload FROM outbox
         WHERE published_at IS NULL ORDER BY created_at LIMIT 100`,
    )
    // for each row: publish to event bus, then mark as published
}
```

The outbox poller runs in a goroutine within the same process. It polls the `outbox` table every 100ms, finds unpublished events, publishes them to the real event bus (Kafka/NATS), and marks them as published. This guarantees at-least-once event delivery: the event is written in the same transaction as the business data, so either both succeed or both fail. If the poller crashes, it resumes from the last unpublished event.

**Alternative: PostgreSQL LISTEN/NOTIFY** instead of polling:

```go
func (p *OutboxListener) Run(ctx context.Context) {
    _, err := p.db.ExecContext(ctx, "LISTEN outbox_channel")
    // ...
    for {
        notification := p.listener.WaitForNotification(ctx)
        p.publishByID(ctx, notification.Extra) // Extra contains the outbox row ID
    }
}
```

LISTEN/NOTIFY is more real-time than polling (no 100ms delay) but has limitations: NOTIFY payload is lost if no listener is connected, max payload size ~8000 bytes. For high-throughput systems, polling is more reliable.

### Event Versioning with Protobuf

Events evolve over time. `OrderCreatedEvent` v1 has 5 fields; v2 adds a `coupon_code` field; v3 changes `total_amount` from int32 to int64. Protobuf provides backward/forward compatibility through field numbering and optional fields:

```protobuf
// events/order_created.proto
syntax = "proto3";
package events;

message OrderCreatedEvent {
    string order_id = 1;
    string user_id = 2;
    int64 total_amount = 3;
    repeated OrderItem items = 4;
    int64 occurred_at_unix_ms = 5;
    string coupon_code = 6;       // added in v2
    int64 tax_amount = 7;         // added in v3, default 0
}
```

In Go, generate the code with `protoc --go_out=. order_created.proto`. Consumers compiled against v1 can still deserialize a v3 message—they ignore unknown fields (or preserve them, depending on the Go protobuf library configuration).

For JSON events, use a version field and semantic versioning:

```json
{
    "event": "order.created",
    "version": "2.1.0",
    "data": {
        "order_id": "ord_123",
        "coupon_code": "SAVE10"
    }
}
```

Downstream consumers check `version` and choose the appropriate deserializer. This is more flexible than Protobuf but requires explicit version management.

## Runtime Behavior

### CQRS Write Path at Runtime

1. **HTTP request arrives**: `POST /api/v1/orders` hits the Chi router.
2. **Handler deserializes**: `json.NewDecoder(r.Body).Decode(&cmd)` maps JSON to `CreateOrderCommand`.
3. **Handler delegates to command handler**: `h.commands.CreateOrder.Handle(r.Context(), cmd)`.
4. **Command handler validates business rules**: checks item count > 0, verifies products exist, checks stock levels.
5. **Command handler opens transaction**: `db.BeginTx(ctx, nil)`.
6. **Writes to write model**: INSERT INTO orders, INSERT INTO order_items. These are normalized tables.
7. **Writes to outbox**: INSERT INTO outbox with the `OrderCreatedEvent` payload. This is in the same transaction.
8. **Commits transaction**: `tx.Commit()`. At this point, the write is durable and the event is queued.
9. **Returns to handler**: handler serializes the response (just the order ID and status) and writes HTTP 201.
10. **Outbox poller (separate goroutine)**: picks up the outbox row, publishes to Kafka/NATS, marks as published.
11. **Event consumers**: Inventory service, Notification service, Analytics service receive the event and process it independently.

### CQRS Read Path at Runtime

1. **HTTP request arrives**: `GET /api/v1/orders/ord_123`.
2. **Handler deserializes**: extracts order ID from Chi URL param (`chi.URLParam(r, "orderID")`).
3. **Handler delegates to query handler**: `h.queries.GetOrder.Handle(r.Context(), GetOrderQuery{OrderID: id})`.
4. **Query handler calls read repository**: `h.orderRepo.FindByID(ctx, id)`.
5. **Read repository executes optimized query**:
   ```sql
   SELECT o.id, o.status, o.total_amount, u.name AS user_name, u.email AS user_email,
          COUNT(oi.id) AS item_count
   FROM orders o
   JOIN users u ON o.user_id = u.id
   LEFT JOIN order_items oi ON o.id = oi.order_id
   WHERE o.id = $1
   GROUP BY o.id, u.name, u.email
   ```
   This single query denormalizes data from orders, users, and order_items. No business validation happens—the query is purely retrieval. It may return stale data (read replica lag) which is acceptable for most queries.
6. **Returns result**: the query handler returns `GetOrderResult` directly. The HTTP handler serializes it to JSON.
7. **No event publishing**: reads never produce events (CQRS principle).

### Event Flow Across Services at Runtime

1. **`OrderCreated` event is published to Kafka topic `events.order.created`** (by the outbox poller in the Orders service).
2. **Inventory Service consumer receives the event** (offset managed by Kafka consumer group).
   - Calls `DeductStock(ctx, event.Items)`. On failure, retries (at-least-once semantics).
   - Inventory handler MUST be idempotent: if `OrderCreated` is delivered twice (Kafka redelivery), deduping logic (idempotency key = `event.OrderID`) prevents double-deduction.
   - After successful deduction, emits `StockDeducted` event (or not, depending on design).
3. **Notification Service consumer receives the event**.
   - Calls `SendConfirmationEmail(ctx, event.UserID, event.OrderID)`.
   - If email service is down, the consumer retries with backoff. No other service is affected.
   - Max retry count: 5. After 5 failures, move to dead letter queue (DLQ) for manual inspection.
4. **Analytics Service consumer receives the event**.
   - Updates the real-time dashboard (Redis increment, Elasticsearch insert).
   - Analytics are "best effort"—if they miss an event, the daily batch job corrects it. No retries, no DLQ.

### Context Propagation Across Event Boundaries

When an event crosses a service boundary (HTTP → Kafka → consumer), the `context.Context` from the original HTTP request is no longer available. The consumer creates a new context (typically `context.Background()` or a context with a new deadline). To maintain traceability, the original trace context (W3C traceparent) is included in the event payload or headers:

```go
// Publisher (in Orders service)
traceHeader := tracing.InjectTraceContext(ctx)
event := OrderCreatedEvent{
    OrderID:    cmd.OrderID,
    TraceParent: traceHeader, // propagates across async boundaries
}

// Consumer (in Inventory service)
parentCtx := tracing.ExtractTraceContext(event.TraceParent)
ctx, span := tracer.Start(parentCtx, "inventory.ProcessOrderCreated")
defer span.End()
```

This allows distributed tracing tools (Jaeger, Honeycomb, Datadog) to show the full end-to-end flow: HTTP request → order created → event published → inventory deducted → email sent.

## Flow Diagrams

```
CQRS Write + Outbox + Event Publishing Flow:

Client        Chi Handler     CommandHandler      WriteRepo      Outbox Table    OutboxPoller     Kafka
  │               │                │                  │               │               │              │
  │ POST /orders  │                │                  │               │               │              │
  │──────────────>│                │                  │               │               │              │
  │               │ Decode JSON    │                  │               │               │              │
  │               │── mapping ────>│                  │               │               │              │
  │               │                │                  │               │               │              │
  │               │  cmd.Handle()  │                  │               │               │              │
  │               │───────────────>│                  │               │               │              │
  │               │                │ Validate Rules   │               │               │              │
  │               │                │── business ──────│               │               │              │
  │               │                │                  │               │               │              │
  │               │                │ BEGIN TRANSACTION│               │               │              │
  │               │                │── tx.Begin() ────│               │               │              │
  │               │                │                  │               │               │              │
  │               │                │ SaveWithTx(order)│               │               │              │
  │               │                │─────────────────>│               │               │              │
  │               │                │                  │ INSERT orders │               │              │
  │               │                │                  │── SQL ────────│               │              │
  │               │                │                  │               │               │              │
  │               │                │  INSERT outbox   │               │               │              │
  │               │                │─────────────────────────────────>│               │              │
  │               │                │                  │               │ Row inserted  │              │
  │               │                │                  │               │ (same tx)     │              │
  │               │                │                  │               │               │              │
  │               │                │ COMMIT  ◄─────────────────────────────────────────              │
  │               │                │── tx.Commit() ───│               │               │              │
  │               │                │                  │               │               │              │
  │               │                │ (async fire)     │               │               │              │
  │               │                │                  │               │               │              │
  │ HTTP 201      │<───────────────│                  │               │               │              │
  │<──────────────│                │                  │               │               │              │
  │               │                │                  │               │               │              │
  │               │                │                  │     ... time passes (up to 100ms) ...        │
  │               │                │                  │               │               │              │
  │               │                │                  │               │  Poll rows    │              │
  │               │                │                  │               │── SELECT ────>│              │
  │               │                │                  │               │  unpublished  │              │
  │               │                │                  │               │<── rows ──────│              │
  │               │                │                  │               │               │              │
  │               │                │                  │               │  Publish to   │              │
  │               │                │                  │               │  Kafka ───────│─────────────>│
  │               │                │                  │               │               │              │
  │               │                │                  │               │  Mark pub'd   │              │
  │               │                │                  │               │── UPDATE ────>│              │
  │               │                │                  │               │  published_at │              │
  │               │                │                  │               │  = NOW()      │              │
```

```
Architecture Evolution Over Time:

    Stage 1            Stage 2           Stage 3           Stage 4           Stage 5
    ─────────          ─────────         ─────────         ─────────         ─────────
    Single File        Layered           Feature Pkgs      Modular Mono      Microservices

    main.go            handlers/         features/         cmd/orders/       orders-svc
    (all in one)       services/         orders/           cmd/payments/     payments-svc
                       repos/            create_order.go   cmd/users/        users-svc
                                         get_order.go      pkg/shared/       (each with
                                         types.go                            own DB)

    Triggers:          Triggers:         Triggers:         Triggers:         Triggers:
    ─────────          ─────────         ─────────         ─────────         ─────────
    1 dev, MVP        >3 devs,          >10 features,     >5 teams,         >50 devs,
                      need to find      cross-feature     independent       independent
                      code by role      conflicts rise    deploy needed     scaling needed

    Tech signals:     Tech signals:     Tech signals:     Tech signals:     Tech signals:
    ────────────      ────────────      ────────────      ────────────      ────────────
    File > 500 lines  Can't find        Merge conflicts   Deploy pipeline   One service
                      handler quickly   in same package   bottleneck        needs 10x scale

    DB pattern:       DB pattern:       DB pattern:       DB pattern:       DB pattern:
    ──────────        ──────────        ──────────        ──────────        ──────────
    Single DB         Shared DB         Shared DB         DB per module     DB per service
    all tables        connection        connection        (still shared     (or dedicated)
                                       pool              cluster)

    Event pattern:    Event pattern:    Event pattern:    Event pattern:    Event pattern:
    ────────────      ────────────      ────────────      ────────────      ────────────
    None              None              In-memory chan    Kafka/NATS        Kafka/NATS
                                                          + CQRS            + CQRS
                                                          + Outbox          + Schema registry
```

## Source Code Reading Guide

Read these files in this order:

1. **`github.com/ThreeDotsLabs/watermill`** — A Go event-driven library. Start with `message/message.go` to understand the Message struct (UUID, Payload, Metadata, Context). Then read `pubsub/gochannel/pubsub.go` for the in-memory pub/sub implementation. Skip the Kafka and NATS implementations until you understand the core interfaces. Key interfaces: `Publisher`, `Subscriber`, `HandlerFunc`.

2. **Your project's `commands/` directory** — Pick one command handler (e.g., `create_order.go`). Read the entire file. Notice: command struct at top, validation in Handle method, repository calls, event publishing at the end. Compare with the query handler in `queries/get_order.go`. Note the absence of validation and event publishing in queries.

3. **`database/sql/sql.go`** — Re-read the `Tx` struct and `BeginTx` method. Understand that `*sql.Tx` is isolated from other transactions (ACID) and that `tx.Commit()` is what makes the outbox pattern atomic: the order row and the outbox row are committed together (or neither is).

4. **`github.com/segmentio/kafka-go`** (or `github.com/twmb/franz-go`) — Read the `Reader` (consumer) and `Writer` (producer) structs. Understand consumer group semantics: each partition is consumed by exactly one member of a consumer group. Understand offset management: auto-commit vs manual commit. In event-driven systems, manual commit after successful processing is safer.

5. **Protobuf event schema**: `events/order_created.proto` or equivalent. Read the field numbering rules: fields 1-15 use 1 byte for field number + type; fields 16-2047 use 2 bytes. Put frequently used fields in 1-15. Never reuse field numbers after removing a field.

6. **`context/context.go`** — Re-read the `WithValue` implementation. Understand that trace context is stored in context values. When you cross an async boundary (HTTP → Kafka), you must extract trace headers from the context, serialize them, and inject them into a new context on the consumer side.

What to skip:
- Watermill's Kafka, NATS, SQL, and Google Pub/Sub implementations initially. Focus on the core pub/sub interfaces and the Go channel implementation.
- The internals of the Sarama Kafka client or NATS client. Libraries, not architecture concepts.
- Event store implementations (EventStoreDB, event-sourcing databases). Separate topic from event-driven architecture basics.

## Production Failure Scenarios

### Scenario 1: Outbox Poller Fails Silently, Events Accumulate

**Cause**: The outbox poller goroutine panics on a malformed event payload (e.g., JSON that was valid at write time but the consumer's struct changed). The poller's `recover()` catches the panic but logs it at DEBUG level. Nobody notices for 6 hours. During this time, 50,000 events accumulate in the outbox table.

**Symptom**: Customers report that order confirmation emails are not arriving. Inventory levels are incorrect because the Inventory service never received `OrderCreated` events. The outbox table has 50,000 rows with `published_at IS NULL`. Database disk usage is climbing.

**Fix**: 
1. Add a metric: `outbox_unpublished_count` gauge. Alert when > 1000 for > 5 minutes.
2. Add a metric: `outbox_poller_errors_total` counter. Alert when error rate > 0.
3. Fix the panic: the malformed payload decode should return an error and move the row to a dead letter queue, not crash the poller.
4. After restart: the poller processes the 50,000 events. Kafka partition throughput is 10,000/sec, so this takes ~5 seconds. The Inventory service sees a burst and processes 50,000 stock deductions. Idempotency keys prevent double-deduction.

### Scenario 2: Dual Write Without Outbox — Lost Event on Crash

**Cause**: A developer implements event publishing by writing to PostgreSQL and then publishing to Kafka in the handler (no outbox table). Between the PostgreSQL `COMMIT` and the Kafka `Publish`, the process crashes (OOM killed by the kernel). The order is in the database but the `OrderCreated` event was never published.

**Symptom**: The order appears in the database (`SELECT * FROM orders WHERE id = 'ord_123'` returns a row), but the Inventory service never deducted stock. The user was charged but the product is still shown as available. The notification email was never sent.

**Fix**: Implement the outbox pattern. The event row is inserted in the same transaction as the order. If the process crashes after `COMMIT`, the event row is already in the `outbox` table, and the outbox poller will publish it after the process restarts. Guarantee: at-least-once delivery. Consumer must be idempotent.

### Scenario 3: Event Version Mismatch Causes Consumer Crash Loop

**Cause**: The Orders service adds a new field `tax_amount` to `OrderCreatedEvent` (v3). The Inventory service is still on v2 (does not know about `tax_amount`). With JSON events, the consumer deserializes successfully but silently ignores the new field—works fine. BUT: the Analytics service uses Protobuf and was compiled with `required` fields. Deserialization of a v3 message without the `tax_amount` field (which is `required` in the old schema) fails with a protobuf unmarshal error: "required field tax_amount not set."

**Symptom**: The Analytics consumer enters a crash loop: consume message → unmarshal error → crash → restart → consume same message → unmarshal error → crash. Kafka offset is never committed. The consumer group stalls at this partition offset. All subsequent events on that partition are not processed.

**Fix**:
1. Short-term: manually advance the Kafka consumer group offset past the problematic message (lose that one event).
2. Long-term: adopt Protobuf best practices. Never use `required` fields in Protobuf v3 (they were removed in Proto3, only in Proto2). All fields should have sensible defaults (0, empty string, false). Consumers should ignore unknown fields. Always test forward compatibility: deploy a consumer compiled with the old schema against events produced by the new schema.
3. Add a DLQ: if a message fails to deserialize after N retries, publish it to a dead letter queue and continue. Monitor DLQ depth.

## Debugging Techniques

### Technique 1: Inspect Outbox State with Direct SQL

```sql
-- Check unpublished event count (should be near 0 in healthy system)
SELECT event_type, COUNT(*) AS count, MIN(created_at) AS oldest
FROM outbox
WHERE published_at IS NULL
GROUP BY event_type
ORDER BY oldest DESC;

-- Check event publishing latency
SELECT event_type,
       AVG(EXTRACT(EPOCH FROM (published_at - created_at)) * 1000) AS avg_latency_ms,
       MAX(EXTRACT(EPOCH FROM (published_at - created_at)) * 1000) AS max_latency_ms
FROM outbox
WHERE published_at IS NOT NULL
  AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY event_type;

-- Find stuck events (older than 5 minutes, still unpublished)
SELECT id, event_type, aggregate_id, created_at
FROM outbox
WHERE published_at IS NULL
  AND created_at < NOW() - INTERVAL '5 minutes'
LIMIT 20;
```

These queries identify: (1) whether the outbox poller is keeping up, (2) what the expected latency is under load, (3) specific events that are stuck.

### Technique 2: Trace an Event Across Service Boundaries

Given a customer complaint ("I placed an order but didn't get a confirmation email"), trace the event:

```bash
# Step 1: Find the order in the Orders database
psql -d orders_db -c "SELECT id, status, created_at FROM orders WHERE id = 'ord_123';"

# Step 2: Check if the event was published
psql -d orders_db -c "SELECT id, event_type, created_at, published_at FROM outbox WHERE aggregate_id = 'ord_123';"

# Step 3: Check Kafka for the event
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic events.order.created --from-beginning --max-messages 10 \
  | grep 'ord_123'

# Step 4: Check if the Notification service received it
# Use your tracing tool (Jaeger UI, Grafana) to search for trace_id
# Or check Notification service logs:
grep 'ord_123' /var/log/notification-service/*.log

# Step 5: Check Notification consumer lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group notification-service --describe \
  | grep events.order.created
```

The flow reveals where the event was lost: never published (outbox issue), published but not consumed (consumer lag), consumed but processing failed (service error).

### Technique 3: Reproduce Event Deserialization Failures

When a consumer cannot deserialize an event, reproduce exactly:

```go
// save_debug_event.go
// Run this against the failing event payload (from DLQ or logs)
package main

import (
    "encoding/json"
    "fmt"
    "os"
)

func main() {
    raw := []byte(os.Args[1])
    var event OrderCreatedEvent
    if err := json.Unmarshal(raw, &event); err != nil {
        fmt.Printf("Unmarshal error: %v\n", err)
        // Use json.RawMessage to inspect partial fields
        var partial map[string]json.RawMessage
        json.Unmarshal(raw, &partial)
        for key, val := range partial {
            fmt.Printf("  %s: %s\n", key, string(val))
        }
        os.Exit(1)
    }
    fmt.Printf("OK: %+v\n", event)
}
```

Run with `go run save_debug_event.go '{"order_id":"ord_123","new_field_consumer_doesnt_understand":true}'` to see exactly which field causes the issue.

## Observability Considerations

### What to Log

1. **Command handler entry/exit**: command name, aggregate ID, duration, result (success/error). Include business rule violations at WARN level, infrastructure errors at ERROR level.
2. **Query handler**: query name, parameters, duration, row count. Queries that return 0 rows should be INFO (expected case: resource not found). Queries that fail should be ERROR.
3. **Event publishing**: event name, aggregate ID, outbox insert status. If outbox insert fails within the transaction, the whole transaction rolls back—log at ERROR.
4. **Event consumption**: event name, aggregate ID, consumer group, processing duration, result (success/retry/DLQ). Log at INFO for success, WARN for retries, ERROR for DLQ.
5. **Outbox poller**: poll cycle duration, events published per cycle, errors. Log at INFO each cycle (with gauge metrics so you can suppress logs if volume is high).

### What Metrics

1. **Commands total**: `commands_total{command="create_order", status="success|error"}`. High error rate on a specific command indicates a bug or a business rule violation pattern.
2. **Command duration histogram**: `command_duration_seconds{command="create_order"}`. P99 latency > 500ms needs investigation.
3. **Queries total and duration**: same pattern as commands. Separate dashboard panels for reads vs writes.
4. **Outbox unpublished gauge**: `outbox_unpublished_count`. Alert threshold > 1000. This is the most critical metric for event-driven systems—if unpublished events grow, all downstream systems are starved.
5. **Outbox publishing rate**: `outbox_published_total{event_type="order.created"}`. Compare with `commands_total{command="create_order", status="success"}`—they should track closely. Divergence means events are being produced but not published.
6. **Event consumer lag**: Kafka consumer group lag (`kafka_consumer_lag` via JMX or kafka-go metrics). Alert if lag > 10,000 for > 5 minutes.
7. **DLQ depth**: `dlq_messages_total`. Non-zero DLQ means events are being dropped and need manual inspection.

### What Traces

1. **Span for command handler**: includes `command.name`, `aggregate.id`, `outcome`. This span links the HTTP request to the database write.
2. **Span for outbox publish**: child span of the command handler span, includes `event.name`. Links the write side to the event infrastructure.
3. **New trace for event consumption**: the consumer creates a new trace context from the event's `traceparent`. The root span is the event handler. This trace is separate from the original HTTP trace (async boundary) but linked via trace context injection/extraction.
4. **Span for each downstream service call**: Inventory deduction, email sending, analytics update. Linked to the consumer span.

## Performance Implications

### Concern 1: Read Model Synchronization Lag

In CQRS, the read model is updated asynchronously from the write model. After a command succeeds, the corresponding query may return stale data (e.g., `POST /orders` returns 201, but immediate `GET /orders/ord_123` returns 404 because the materialized view hasn't refreshed).

Mitigation options:
- **Accept staleness**: document the SLA (e.g., read models are updated within 100ms). Most UIs display a "Your order is being processed" state anyway.
- **Read-your-writes**: include the created resource in the command response so the client doesn't need to query immediately. `POST /orders` returns the full order object in the 201 response.
- **Synchronous read model update**: update the read model in the same transaction as the write. Defeats the purpose of CQRS separation but may be acceptable for low-throughput systems.
- **Cache invalidation**: use a short TTL cache (Redis, 1-second TTL) that caches queries. After a write, invalidate the cache key. Next read hits the database, gets fresh data, and re-caches.

### Concern 2: Event Fanout Overhead

A `OrderCreated` event might have 10 consumers (inventory, notification, analytics, fraud detection, accounting, reporting, customer support, search index, recommendation engine, audit log). The outbox poller publishes the event once to Kafka; Kafka delivers it to all 10 consumer groups. This is efficient. But if you're using an in-memory event bus, each consumer is a goroutine that runs for each event. At 1000 events/second with 10 consumers = 10,000 goroutine launches per second.

Mitigation:
- Move to a durable event bus (Kafka/NATS) when consumer count > 3 or event rate > 100/sec.
- In the in-memory bus, use a worker pool pattern: a fixed pool of goroutines processes events from a channel. This bounds goroutine creation.
- Profile goroutine count with `runtime.NumGoroutine()` periodically. If goroutine count grows unboundedly, you have a leak (event handlers that block forever).

### Concern 3: Outbox Table Bloat

The outbox table grows indefinitely if events are never deleted. A system processing 1000 events/second generates ~86 million outbox rows per day. Even with `published_at` indexing, the table becomes a performance problem.

Mitigation:
- Add a scheduled cleanup job: `DELETE FROM outbox WHERE published_at < NOW() - INTERVAL '7 days'`. Run during low-traffic hours.
- Partition the outbox table by date (PostgreSQL 10+ declarative partitioning). Drop old partitions instead of DELETE (faster, no VACUUM).
- Retention period: enough to debug any issue (7 days is standard), short enough to keep the table manageable.

## Architecture Implications

CQRS and event-driven architecture require your organization to adopt asynchronous thinking. In a synchronous CRUD system, a developer can trace the full call stack: `handler → service → repository → database`. In CQRS, the trace splits: the command handler writes, publishes an event, and returns; what happens after that is someone else's problem. This is a feature, not a bug, but it changes how developers debug: you cannot set a breakpoint and step through the entire flow anymore. You need distributed tracing, correlation IDs, and log aggregation to follow a request across service and time boundaries.

Event-driven architecture also introduces the challenge of eventual consistency. When the Orders service emits `OrderCreated`, the Inventory service might take 5 seconds to process it (due to consumer lag, retries, backpressure). During those 5 seconds, the system is in an inconsistent state: the order exists but inventory is not deducted. If another user tries to order the same product during that window, they might succeed even though there is no stock. This is the classic "inventory oversell" problem. Solutions include: optimistic concurrency (version field on inventory, retry on conflict), reservation patterns (reserve stock on order creation, confirm on payment), or accepting the business risk (manual review of oversold orders, which is cheaper than the infrastructure to prevent it).

The long-term implication is that event-driven systems are harder to reason about but more resilient to failure. If the Notification service is down for 2 hours, the Orders service continues operating normally—events accumulate in Kafka, and the Notification service catches up when it restarts. In a synchronous system, the Orders service would fail (or degrade with timeouts) while the Notification service is down. The tradeoff is operational complexity: you now have Kafka to operate, monitor, and scale. For a 3-person team, this is a significant operational burden. For a 50-person platform team, it's table stakes.

## Team Ownership Implications

In an event-driven system, event schemas become the API contract between teams. The Orders team "owns" the `OrderCreated` event schema—they define its structure, its versioning policy, and its deprecation timeline. The Inventory, Notification, and Analytics teams are consumers of this event. When the Orders team wants to add a field, they must consider backward compatibility: existing consumers must not break. When they want to remove a field, they must coordinate a deprecation period: stop emitting the field, wait for all consumers to stop reading it, then remove it from the schema. This is analogous to API versioning but with one critical difference: events have multiple consumers who each migrate independently.

This requires organizational maturity. Teams need to know who consumes their events (event registry, schema registry). Consumers need SLAs on event delivery (how long after publishing will the event be available? 1 second? 1 minute?). The platform team needs to provide tooling: schema compatibility checks (can this new schema be consumed by all registered consumers?), consumer lag dashboards, DLQ inspection UIs. Without this organizational support, event-driven architecture devolves into a tangle of undocumented, fragile point-to-point integrations.

## Interview Questions

### Q1: "You have a bug where orders are saved to the database but inventory is not deducted. Walk through how you would diagnose this in a CQRS + event-driven system."

**Answer**: Systematic diagnosis path:

1. **Confirm the order exists in the write database**: `SELECT * FROM orders WHERE id = 'ord_123'`. If it does not exist, the command handler never saved it—look at command handler logic.

2. **Check the outbox table**: `SELECT * FROM outbox WHERE aggregate_id = 'ord_123'`. If no row exists, the event was never emitted—the command handler did not insert into outbox. If a row exists but `published_at IS NULL`, the outbox poller has not picked it up—check poller health. If `published_at` is set, the event was published—move to step 3.

3. **Check Kafka/NATS for the event**: Verify the event reached the broker. Check the Inventory consumer group offset: has the consumer consumed this event? If consumer lag is high, the consumer is behind. If the consumer has passed this offset, it consumed the event but processing failed.

4. **Check Inventory service logs for the event**: `grep 'ord_123' /var/log/inventory/*.log`. Look for `"Processing OrderCreated"` and `"StockDeducted"` or error messages.

5. **Check Inventory handler idempotency**: If the event was delivered twice, did the handler skip the second delivery? If idempotency check is wrong, the first delivery deducted stock, the second delivery deducted again—double deduction.

6. **Root cause hypothesis**: Each step narrows the search. Likely causes: (a) outbox poller silently failing (metric: `outbox_unpublished_count`), (b) consumer crash loop on malformed event (metric: consumer restart rate), (c) consumer processing succeeded but `UPDATE inventory SET stock = stock - quantity` failed silently (no error check in code), (d) consumer processed but `stock` was already 0 and the handler swallowed the error.

### Q2: "Explain the Outbox pattern and why it's necessary. What problem does it solve that a simple try-catch-finally cannot?"

**Answer**: The Outbox pattern solves the dual-write problem: you need to atomically write to two storage systems (PostgreSQL for business data, Kafka for events) but there is no distributed transaction protocol between them.

A try-catch-finally approach cannot solve this because the failure can occur after the first write and before the second write, and the process crashes in between. No amount of Go error handling can recover from a process crash:

```go
// Attempt (broken): try to publish event after DB commit
tx.Commit() // step 1: committed to PostgreSQL
eventBus.Publish(event) // step 2: publish to Kafka
// Process crashes here! Event is lost. Order is saved, no event published.
```

Even with `defer` or `recover()`, if the process dies (SIGKILL, OOM, power loss), nothing runs after the crash. The outbox solves this by making the event write part of the same database transaction:

```go
tx.Exec("INSERT INTO orders ...")
tx.Exec("INSERT INTO outbox ...") // same transaction!
tx.Commit() // both succeed or both fail, atomically
// Process can crash here - the event row is already in the database
```

After restart, a separate process (the outbox poller) reads from the outbox table, publishes to Kafka, and marks events as published. The key insight: the database transaction is the atomicity boundary. Everything inside the transaction succeeds or fails together. The event is "published" by being written to the outbox table, not by being sent to Kafka. Kafka delivery is deferred and can be retried.

Alternative approaches:
- **PostgreSQL LISTEN/NOTIFY**: After commit, a trigger fires `NOTIFY outbox_channel`. A listener goroutine receives the notification. This avoids polling but has the same reliability model (event is in the database first).
- **Change Data Capture (CDC)**: Use Debezium or similar to tail the PostgreSQL WAL. Writes to the `orders` table are captured as events automatically. No outbox table needed, but more infrastructure to operate.
- **XA transactions / 2PC**: Two-phase commit between PostgreSQL and Kafka. Not practical: Kafka does not support XA, and even if it did, the availability cost (locking during coordinator failure) is unacceptable for most systems.

### Q3: "When should you NOT use CQRS? Give specific criteria."

**Answer**: Do NOT use CQRS when:

1. **Your read and write models are identical**: If `GET /orders/ord_123` returns exactly the `orders` table row with no JOINs, and `POST /orders` writes to exactly the `orders` table, CQRS adds separation without benefit. Trigger: read model == write model.

2. **You have < 5 query patterns**: If you have one or two ways to read data (GET by ID, GET list with pagination), the complexity of maintaining separate read models is not justified. Trigger: your API has more endpoints than query handlers.

3. **Eventual consistency is unacceptable**: If your business requires immediate consistency after writes (e.g., financial ledger where balance must be immediately correct, medical system where readings must be immediately available), the read-model lag of CQRS is a problem. Use traditional layered architecture with synchronous reads.

4. **Your team has no experience with async patterns**: CQRS introduces eventual consistency, separate data stores, and synchronization logic. If your team is struggling with basic Go concurrency, adding CQRS will create more bugs than it prevents. Trigger: team members cannot explain `select{}` or `context.WithCancel`.

5. **You have a single database that handles both reads and writes fine**: CQRS is often adopted to scale reads independently from writes. If your PostgreSQL instance handles your read/write load easily (P99 read latency < 10ms, write throughput < 1000 TPS), you do not need CQRS. Trigger: your database CPU is consistently < 30%.

### Q4: "Walk through the architecture evolution from a single-file Go service to a microservices architecture with CQRS and events. When do you make each transition?"

**Answer**: The evolution follows a predictable pattern driven by team size, complexity, and scale:

**Stage 1: Single File (1-2 developers, < 500 lines)**. Everything in `main.go`: Chi router, handlers, SQL queries, types. No package structure. When the file exceeds 500 lines, you can no longer hold the whole service in your head → Stage 2.

**Stage 2: Layered (3-5 developers, 5-20 endpoints)**. Split into `handlers/`, `services/`, `repos/` directories. Each layer is in its own package. When a feature change requires touching 3+ files across different packages (handler validation + service logic + repo query), cognitive overhead is high → Stage 3.

**Stage 3: Feature Packages (5-10 developers, 20-50 endpoints)**. Group by feature: `features/orders/`, `features/payments/`. All code for a feature lives together (Vertical Slice). When multiple features need the same database tables and cross-feature coordination increases → Stage 4.

**Stage 4: Modular Monolith (10-30 developers, 50-200 endpoints)**. Separate `cmd/orders/`, `cmd/payments/` with a shared `pkg/` library. Each module can in theory be extracted into its own service. When teams need independent deployment (orders team wants to deploy 5x/day, payments team deploys weekly) → Stage 5.

**Stage 5: Microservices (30+ developers, 200+ endpoints)**. Each module becomes an independent service with its own database, deploy pipeline, and team. Communication shifts from in-process function calls to network calls (HTTP/gRPC). Events replace synchronous calls for cross-service coordination.

The CQRS transition typically happens between Stage 3 and Stage 4, when read patterns diverge significantly from write patterns. The event-driven transition typically happens between Stage 4 and Stage 5, when cross-module coordination becomes complex.

Key principle: each transition should be triggered by concrete pain, not by anticipation of future pain. A startup with 2 developers should stay at Stage 1 until the pain of Stage 1 exceeds the cost of Stage 2. A 100-person platform should be at Stage 5 (or 4) because the pain of a monolith at that scale is existential.

### Q5: "How do you test an event-driven system in Go? Describe your testing strategy at unit, integration, and end-to-end levels."

**Answer**: Each level tests a different aspect of the event-driven system:

**Unit tests** test individual handlers in isolation:

```go
func TestCreateOrderHandler_Success(t *testing.T) {
    orderRepo := &mockOrderRepo{}
    productRepo := &mockProductRepo{products: map[string]Product{"p1": {Stock: 10}}}
    eventBus := &mockEventBus{}
    handler := commands.NewCreateOrderHandler(orderRepo, productRepo, eventBus)

    cmd := commands.CreateOrderCommand{OrderID: "o1", Items: []commands.OrderItemCommand{{ProductID: "p1", Quantity: 2}}}
    err := handler.Handle(context.Background(), cmd)

    assert.NoError(t, err)
    assert.True(t, orderRepo.savedCalled)
    assert.Len(t, eventBus.publishedEvents, 1)
    assert.Equal(t, "order.created", eventBus.publishedEvents[0].EventName())
}
```

Mock all dependencies. Test business rules: insufficient stock, empty items, duplicate order ID. Test idempotency: calling Handle twice with the same command should not create two orders.

**Integration tests** test the interaction with real infrastructure:

```go
func TestOutboxIntegration(t *testing.T) {
    db := testDB(t) // real PostgreSQL via testcontainers or docker-compose
    defer db.Close()

    // Create schema, run migrations
    migrateDB(t, db)

    handler := commands.NewCreateOrderHandler(
        postgres.NewOrderWriteRepository(db),
        postgres.NewProductWriteRepository(db),
        events.NewOutboxEventBus(db, natsConn), // publishes to NATS via outbox
    )

    cmd := commands.CreateOrderCommand{OrderID: "o1", Items: []commands.OrderItemCommand{{ProductID: "p1", Quantity: 2}}}
    err := handler.Handle(context.Background(), cmd)
    require.NoError(t, err)

    // Verify outbox row exists
    var publishedAt *time.Time
    db.QueryRow("SELECT published_at FROM outbox WHERE aggregate_id = 'o1'").Scan(&publishedAt)
    assert.Nil(t, publishedAt) // not yet published

    // Run poller for one cycle
    poller := events.NewOutboxPoller(db, natsConn, 0) // 0 interval = run once
    poller.Poll(context.Background())

    // Verify published
    db.QueryRow("SELECT published_at FROM outbox WHERE aggregate_id = 'o1'").Scan(&publishedAt)
    assert.NotNil(t, publishedAt)
}
```

Use real PostgreSQL (Testcontainers) and real NATS (or NATS in-memory server for tests). Test the full flow: command handler → outbox → poller → NATS.

**End-to-end tests** test across service boundaries:

```go
func TestOrderCreated_InventoryDeducted(t *testing.T) {
    ordersSvc := startOrdersService(t)     // starts real HTTP server + outbox + Kafka
    inventorySvc := startInventoryService(t) // starts real HTTP server + Kafka consumer
    defer ordersSvc.Stop()
    defer inventorySvc.Stop()

    // Create order via orders service API
    resp := postJSON(t, ordersSvc.URL+"/api/v1/orders", map[string]interface{}{
        "user_id": "u1",
        "items":   []map[string]interface{}{{"product_id": "p1", "quantity": 2}},
    })
    assert.Equal(t, 201, resp.StatusCode)

    // Wait for event propagation (poll with timeout)
    assert.Eventually(t, func() bool {
        inv := getInventory(t, inventorySvc.URL, "p1")
        return inv.Stock == 8 // started at 10, deducted 2
    }, 10*time.Second, 100*time.Millisecond)
}
```

Key pattern: `assert.Eventually` with timeout. Event-driven systems are eventually consistent—tests must accommodate this. Use a reasonable timeout (10 seconds for integration tests, 30 seconds for E2E).

## Hands-On Exercises

### Exercise 1: Implement CQRS for an Existing CRUD Service

**Goal**: Take a CRUD order service and split it into separate command and query paths.

**Steps**:
1. Start with a CRUD service that has `POST /orders` (create), `GET /orders/{id}` (get), `GET /orders` (list), `PUT /orders/{id}` (update), `DELETE /orders/{id}` (cancel). All handlers use the same `OrderService` and the same `OrderRepository`.
2. Create separate command types: `CreateOrderCommand`, `CancelOrderCommand`, `UpdateOrderCommand`. Create command handler structs for each.
3. Create separate query types: `GetOrderQuery`, `ListOrdersQuery`. Create query handler structs.
4. Split the repository: `OrderWriteRepository` (Save, Update) and `OrderReadRepository` (FindByID, FindAll).
5. Implement the read repository with a materialized view or a JOIN query that denormalizes order + user + product data.
6. Wire everything in `main.go`. The HTTP handler for POST uses command handlers; GET uses query handlers.
7. Verify: can you change the read model (add a new aggregated field) without touching any command code?

### Exercise 2: Implement the Outbox Pattern from Scratch

**Goal**: Build an outbox-based event publishing system in Go without any libraries.

**Steps**:
1. Create the `outbox` table with columns: `id UUID`, `aggregate_id VARCHAR`, `event_type VARCHAR`, `payload JSONB`, `created_at TIMESTAMP`, `published_at TIMESTAMP`.
2. Write a `CreateOrderHandler` that inserts into `orders` and `outbox` in the same transaction.
3. Write an `OutboxPoller` that runs in a background goroutine. Every 100ms, it SELECTs rows WHERE `published_at IS NULL` ORDER BY `created_at` LIMIT 100, publishes each to an in-memory channel (your EventBus), and UPDATEs `published_at = NOW()`.
4. Write a test that creates an order, verifies the outbox row exists, runs the poller once, and verifies the event was published and `published_at` is set.
5. Add a failure mode: what happens if the poller publishes the event but the UPDATE fails? (Double-publishing.) Add idempotency: the consumer skips events with a previously seen `aggregate_id + event_type` combination.
6. Benchmark: how many events/second can the poller process with a 100ms poll interval and batch size of 100?

### Exercise 3: Build a Schema-Compatible Event Evolution System

**Goal**: Create a system that handles event schema evolution with backward compatibility.

**Steps**:
1. Define `OrderCreatedEvent` v1 in Protobuf: fields `order_id`, `user_id`, `total_amount`.
2. Generate Go code with `protoc`. Write a producer that creates v1 events.
3. Write a consumer that deserializes v1 events. Test it works.
4. Define `OrderCreatedEvent` v2: add `coupon_code` (optional, default empty string). Generate new Go code.
5. Upgrade the producer to v2. The v1 consumer should still be able to deserialize v2 events (backward compatibility). Write a test: produce v2, consume with v1 consumer, verify `coupon_code` is empty string (Proto3 default).
6. Upgrade the consumer to v2. Write a test: produce v1 (old format), consume with v2 consumer, verify `coupon_code` is empty string (default for missing field in Proto3).
7. Add a field removal scenario: v3 removes `total_amount`. The v2 consumer should ignore unknown fields (Proto3 default behavior). Write a test: produce v2, consume with v3 consumer—the removed field is silently ignored.

## Advanced Challenges

### Challenge 1: Design an Exactly-Once Event Processing Guarantee

**Goal**: Implement exactly-once semantics for event processing in Go, despite at-least-once delivery from Kafka.

**Constraints**:
- The consumer must not use Kafka transactions (too expensive, too much coupling to Kafka).
- The consumer maintains a local deduplication table in PostgreSQL.
- Events have a unique key: `aggregate_id + event_type + occurrence_time` (or a sequence number).

**Approach**: Implement an idempotent event handler wrapper:

```go
type IdempotentHandler struct {
    inner EventHandler
    db    *sql.DB
}

func (h *IdempotentHandler) Handle(ctx context.Context, event DomainEvent) error {
    tx, _ := h.db.BeginTx(ctx, nil)
    defer tx.Rollback()

    key := event.AggregateID() + ":" + event.EventName() + ":" + strconv.FormatInt(event.OccurredAt().UnixNano(), 10)

    var processed bool
    tx.QueryRow("SELECT EXISTS(SELECT 1 FROM idempotency_keys WHERE key = $1)", key).Scan(&processed)
    if processed {
        return nil // already processed, skip
    }

    err := h.inner.Handle(ctx, event)
    if err != nil {
        return err
    }

    tx.Exec("INSERT INTO idempotency_keys (key) VALUES ($1)", key)
    return tx.Commit()
}
```

The idempotency key insert and the business logic run in the same transaction. If the handler succeeds and the idempotency key is committed, the event is marked as processed. If the handler fails (or crashes), the transaction rolls back and the event can be retried.

**Bonus**: Handle the case where the same event is published twice with different `occurred_at` values (due to clock skew or retry logic). Does your deduplication strategy handle this? If not, propose a sequence number scheme.

### Challenge 2: Design a Migration from Synchronous HTTP to Event-Driven for an Existing System

**Goal**: Create a phased migration plan for a system with 10 services communicating synchronously via HTTP, converting to event-driven communication incrementally.

**Constraints**:
- Zero downtime during migration.
- Services should be converted one at a time.
- During migration, some service pairs communicate via HTTP, others via events.
- Rollback capability at each phase.

**Approach**:
1. **Phase 1: Emit events alongside HTTP calls**. Each service publishes events for state changes it makes. No service consumes events yet. This is a no-op for consumers—they still use HTTP. Purpose: validate event publishing infrastructure (Kafka, schema registry, outbox) works at production scale.
2. **Phase 2: Consume events in shadow mode**. Target services consume events but do not act on them. They log the event, compare with the equivalent HTTP call data, and report discrepancies. Purpose: validate event schemas match business needs, identify missing data in events.
3. **Phase 3: Consume events in production mode with fallback**. Target services process events AND maintain the HTTP endpoint. If event processing succeeds, the HTTP endpoint is not needed. If event processing fails (consumer lag, crash), the caller falls back to HTTP. Purpose: transition off HTTP with a safety net.
4. **Phase 4: Deprecate HTTP endpoints**. After monitoring shows HTTP calls have dropped to 0 (all communication is via events), mark HTTP endpoints as deprecated. Keep them running for one release cycle, then remove them.
5. **Phase 5: Clean up**. Remove HTTP client code from callers. Remove fallback logic from consumers. Simplify infrastructure.

**Bonus**: How would you handle the case where the event schema needs to change during the migration? (Answer: version the event, or use a compatibility layer that translates between the old HTTP API shape and the new event shape during the transition.)

## Key Insights

- CQRS is not a binary choice. You can apply CQRS to specific aggregates (orders, payments) while keeping simpler entities (user preferences, feature flags) as CRUD. A staff engineer identifies which domains benefit from CQRS and which don't, rather than applying it uniformly.
- The Outbox pattern is the only reliable way to atomically update a database and publish an event in a non-distributed-transaction environment. Any system that writes to a database and publishes events without an outbox will lose events in production. The question is not "if" but "when."
- Event schemas are your API contract. Treat them with the same care as REST API contracts: version them, document them, test backward compatibility, provide deprecation timelines. A breaking event schema change can take down every downstream service simultaneously.
- Idempotency is not optional in event-driven systems. Every event handler must handle duplicate delivery correctly. Use idempotency keys, deduplication tables, or check-before-act patterns. The simplest approach: `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING` in PostgreSQL.
- Architecture evolution is a one-way street for a reason: each stage adds complexity that cannot be removed without a rewrite. You can go from Vertical Slice to Clean Architecture incrementally. You cannot go from Clean Architecture back to a single `main.go` without throwing away the layered structure. Evolve only when the pain of the current architecture is measurable and quantified.
- Event-driven systems make debugging harder and operations more complex. The benefit—loose coupling, independent deployability, resilience to partial failure—must justify the cost. For a team of 3 with a single service and a PostgreSQL database, event-driven architecture is overengineering. For a team of 50 with 20 services, it's essential.
- The most important skill for a staff/principal engineer is not knowing how to implement CQRS or event-driven architecture—it's knowing when not to. The ability to say "we don't need this yet" and articulate why, with data, is more valuable than the ability to implement it.
