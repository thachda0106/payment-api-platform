# Session 05: DDD Tactical Patterns in Go

## Why This Topic Exists

Domain-Driven Design (DDD) tactical patterns provide a vocabulary and toolset for modeling complex business domains in code. In the Go ecosystem, DDD adoption has been controversial — many Go developers associate it with Java-style over-engineering, abstract factories, and needless indirection. This reputation exists because DDD patterns are often transplanted from Java/C# directly into Go without adaptation for Go's idioms.

This session exists to bridge that gap. It teaches DDD tactical patterns as they should be written in Go: lean, interface-driven, and composition-based. Go lacks inheritance, annotations, and DI containers — and that is a strength when applying DDD. The patterns become simpler, more explicit, and easier to test.

For a Staff/Principal Engineer, understanding the right way (and the wrong way) to apply DDD in Go is a decision-making superpower. You will face services where DDD is essential (complex payment orchestration, regulatory compliance domains) and services where DDD is harmful overhead (simple CRUD APIs, proxy services). Knowing the difference is what separates a principal engineer from a senior one.

---

## Mental Model

### The Core Insight: Behavior Over Data

In Go, the fundamental shift DDD demands is moving from "structs that hold data" to "structs that enforce invariants." A typical Go developer writes:

```go
type Order struct {
    ID     string
    Status string
    Total  float64
}
```

A DDD practitioner writes:

```go
type Order struct {
    id     OrderID
    status OrderStatus
    total  Money
}

func (o *Order) Submit() error {
    if o.status != Draft {
        return ErrInvalidStateTransition
    }
    o.status = Submitted
    o.recordEvent(OrderSubmitted{OrderID: o.id})
    return nil
}
```

The difference: the second version is a **closed system**. You cannot set `Status` to an invalid value from outside the package. The only way to change state is through methods that validate the transition. This is the essence of a DDD aggregate in Go.

### Mental Map: Four Layers of Tactical DDD

```
┌──────────────────────────────────────────────────┐
│                  ENTITIES                         │
│  Mutable, identity-based, lifecycle-tracked       │
│  Example: Order, Customer, Payment                │
├──────────────────────────────────────────────────┤
│                VALUE OBJECTS                      │
│  Immutable, equality-by-value, no identity         │
│  Example: Money, Email, Address                   │
├──────────────────────────────────────────────────┤
│             AGGREGATES (AGGREGATE ROOT)           │
│  Cluster of entities + value objects              │
│  Root enforces all invariants across cluster      │
│  Example: Order (root) → OrderLine (entity)       │
│                    → Money (value object)         │
├──────────────────────────────────────────────────┤
│             DOMAIN SERVICES & EVENTS               │
│  Stateless operations spanning aggregates         │
│  Events: "something important happened"           │
│  Example: PricingService, OrderPlaced event       │
└──────────────────────────────────────────────────┘
```

### Go-Specific Mental Shift

| Java/Spring Pattern | Go Equivalent |
|---|---|
| Class with private fields + getters/setters | Struct with unexported fields + exported methods |
| Abstract base class | Interface + struct embedding |
| @Transactional annotation | Explicit transaction in repository method |
| @Autowired dependency injection | Constructor parameter passing |
| JPA @Entity with ORM | Plain struct + SQL in repository |
| Aspect-oriented programming | Middleware pattern (decorator functions) |
| Spring Events / ApplicationEventPublisher | Channel-based event bus or interface |
| Anemic domain model (common in Spring) | Rich domain model (structs with behavior) |

---

## Internal Architecture

### Entity Pattern in Go

Entities are the backbone of DDD. In Go, an entity is a struct with:
- An **identity field** (UUID, int64, or domain-specific ID)
- **Unexported fields** (enforced by the package boundary)
- **Behavior methods** that mutate state
- **Thread-safety NOT assumed** (Go's convention: caller manages concurrency)

```go
package order

import "errors"

type OrderID string

type OrderStatus int

const (
    Draft OrderStatus = iota
    Submitted
    Paid
    Shipped
    Cancelled
)

var (
    ErrInvalidTransition = errors.New("invalid state transition")
    ErrOrderNotDraft     = errors.New("order cannot be modified after submission")
)

type Order struct {
    id      OrderID
    status  OrderStatus
    total   Money
    lines   []OrderLine
    events  []DomainEvent
}

func NewOrder(id OrderID) *Order {
    return &Order{
        id:     id,
        status: Draft,
        lines:  make([]OrderLine, 0),
        events: make([]DomainEvent, 0),
    }
}

func (o *Order) ID() OrderID        { return o.id }
func (o *Order) Status() OrderStatus { return o.status }
func (o *Order) Total() Money        { return o.total }

func (o *Order) AddLine(productID string, quantity int, price Money) error {
    if o.status != Draft {
        return ErrOrderNotDraft
    }
    line := newOrderLine(productID, quantity, price)
    o.lines = append(o.lines, line)
    o.recalculateTotal()
    return nil
}

func (o *Order) Submit() error {
    if o.status != Draft {
        return ErrInvalidTransition
    }
    if len(o.lines) == 0 {
        return errors.New("cannot submit empty order")
    }
    o.status = Submitted
    o.events = append(o.events, OrderSubmitted{OrderID: o.id})
    return nil
}

func (o *Order) Events() []DomainEvent { return o.events }
func (o *Order) ClearEvents()          { o.events = nil }

func (o *Order) recalculateTotal() {
    var total Money
    for _, line := range o.lines {
        total = total.Add(line.LineTotal())
    }
    o.total = total
}
```

Key observations:
- ID, status, total, lines are **unexported** — no external package can mutate them directly
- `recalculateTotal()` is **unexported** — an internal invariant enforcement
- Accessor methods exist only when external consumers need data (read models, projections)
- Events are collected and can be dispatched after persistence
- Zero values are defensively handled in constructors (`NewOrder`)

### Value Object Pattern in Go

Value objects are the most underrated DDD pattern in Go. A value object is immutable, has no identity, and is compared by value. In Go, this naturally maps to structs with factory functions:

```go
package money

import (
    "errors"
    "fmt"
)

var (
    ErrNegativeAmount = errors.New("money amount cannot be negative")
    ErrCurrencyMismatch = errors.New("currency mismatch")
)

type Money struct {
    amount   int64  // stored in minor units (cents, pence)
    currency string // ISO 4217
}

func NewMoney(amount int64, currency string) (Money, error) {
    if amount < 0 {
        return Money{}, ErrNegativeAmount
    }
    if currency == "" {
        return Money{}, errors.New("currency is required")
    }
    return Money{amount: amount, currency: currency}, nil
}

func (m Money) Add(other Money) (Money, error) {
    if m.currency != other.currency {
        return Money{}, ErrCurrencyMismatch
    }
    return Money{amount: m.amount + other.amount, currency: m.currency}, nil
}

func (m Money) Multiply(factor int) Money {
    return Money{amount: m.amount * int64(factor), currency: m.currency}
}

func (m Money) Amount() int64    { return m.amount }
func (m Money) Currency() string  { return m.currency }
func (m Money) IsZero() bool      { return m.amount == 0 }
func (m Money) Equal(other Money) bool { return m.amount == other.amount && m.currency == other.currency }

func (m Money) String() string {
    return fmt.Sprintf("%s %.2f", m.currency, float64(m.amount)/100.0)
}
```

Another example — Email as a value object:

```go
package email

import (
    "errors"
    "net/mail"
    "strings"
)

type Email struct {
    value string
}

func NewEmail(raw string) (Email, error) {
    trimmed := strings.TrimSpace(raw)
    if trimmed == "" {
        return Email{}, errors.New("email cannot be empty")
    }
    addr, err := mail.ParseAddress(trimmed)
    if err != nil {
        return Email{}, fmt.Errorf("invalid email %q: %w", raw, err)
    }
    return Email{value: addr.Address}, nil
}

func (e Email) String() string   { return e.value }
func (e Email) Domain() string   { return strings.SplitN(e.value, "@", 2)[1] }
func (e Email) Equal(other Email) bool { return e.value == other.value }
```

**Go-specific value object rules:**
1. Use `func NewXxx(...) (Xxx, error)` — validation at construction time, never after
2. Keep fields unexported — immutability enforced by package boundary
3. Methods return new instances — never mutate `self`
4. Implement `Equal()` — Go has no `==` operator overloading; don't rely on struct equality for types with slices/maps
5. Value objects are always valid — if you have an instance, the invariants hold

### Aggregate Root Pattern in Go

The aggregate root is where DDD shines in Go. The root entity is the single entry point for all mutations within the aggregate boundary. External code never modifies child entities directly.

```go
package order

type Order struct {          // Aggregate Root
    id      OrderID
    status  OrderStatus
    total   Money
    lines   []OrderLine      // Child entities, accessed only through Order
    events  []DomainEvent
}

type OrderLine struct {      // Child entity (no public constructor in other packages)
    productID string
    quantity  int
    price     Money
}

// Unexported constructor — only Order can create OrderLines
func newOrderLine(productID string, quantity int, price Money) OrderLine {
    return OrderLine{
        productID: productID,
        quantity:  quantity,
        price:     price,
    }
}

// Exported for read models only, no mutation allowed
func (ol OrderLine) ProductID() string { return ol.productID }
func (ol OrderLine) Quantity() int     { return ol.quantity }
func (ol OrderLine) LineTotal() Money  { return ol.price.Multiply(ol.quantity) }
```

**Aggregate design rules in Go:**
1. **Reference by ID, not by pointer**: `Order` references `CustomerID` (a value object), not `*Customer`
2. **One aggregate = one transaction**: modify one aggregate per database transaction
3. **Small aggregates**: prefer many small aggregates over one giant one
4. **Eventual consistency between aggregates**: use domain events + message broker
5. **No ORM magic**: Go repositories write raw SQL; no lazy loading, no dirty checking

### Repository Interface Pattern (Go Style)

Go's convention is: **define interfaces where they are consumed, not where they are implemented.**

```go
// order/order_repository.go — defined in the domain package
package order

import "context"

type Repository interface {
    Save(ctx context.Context, order *Order) error
    FindByID(ctx context.Context, id OrderID) (*Order, error)
    FindByCustomerID(ctx context.Context, customerID string) ([]*Order, error)
}

// postgres/order_repository.go — implementation in adapter layer
package postgres

type OrderRepository struct {
    db *sql.DB
}

func NewOrderRepository(db *sql.DB) *OrderRepository {
    return &OrderRepository{db: db}
}

func (r *OrderRepository) Save(ctx context.Context, order *Order) error {
    // Raw SQL, explicit transaction handling
    tx, err := r.db.BeginTx(ctx, nil)
    // ... INSERT or UPDATE order, INSERT/UPDATE/DELETE order_lines
    // ... INSERT domain events into outbox table
}
```

This is the **dependency inversion principle** in Go: the domain defines what it needs (the interface), and the infrastructure provides the implementation. The domain package has zero imports of database drivers, HTTP libraries, or message brokers.

### Domain Events in Go

Two approaches exist for domain events in Go, and the choice depends on service boundaries:

**Approach 1: In-process event bus (same service)**

```go
package event

type DomainEvent interface {
    EventName() string
    OccurredAt() time.Time
}

type Bus interface {
    Publish(ctx context.Context, events ...DomainEvent) error
    Subscribe(eventName string, handler EventHandler)
}

type EventHandler func(ctx context.Context, event DomainEvent) error
```

Usage in the aggregate:

```go
func (o *Order) Submit() error {
    // ... validation ...
    o.status = Submitted
    o.events = append(o.events, OrderSubmitted{
        OrderID:   o.id,
        SubmittedAt: time.Now(),
    })
    return nil
}
```

After persistence, the application service dispatches:

```go
func (s *OrderService) SubmitOrder(ctx context.Context, id OrderID) error {
    order, _ := s.repo.FindByID(ctx, id)
    if err := order.Submit(); err != nil {
        return err
    }
    if err := s.repo.Save(ctx, order); err != nil {
        return err
    }
    return s.eventBus.Publish(ctx, order.Events()...)
}
```

**Approach 2: Outbox pattern (cross-service events)**

For events that cross service boundaries, you need guaranteed delivery. The outbox pattern stores events in the same database transaction as the aggregate:

```go
// The repository writes events to an `outbox` table in the same transaction
func (r *OrderRepository) Save(ctx context.Context, order *Order) error {
    tx, _ := r.db.BeginTx(ctx, nil)
    defer tx.Rollback()

    r.upsertOrder(tx, order)
    r.upsertOrderLines(tx, order)
    for _, event := range order.Events() {
        r.insertOutbox(tx, event) // Same transaction, same database
    }
    return tx.Commit()
}
```

A separate publisher process reads from the outbox table and publishes to Kafka/NATS.

### Domain Services vs Application Services

This distinction is critical and frequently confused:

| Aspect | Domain Service | Application Service |
|---|---|---|
| **Location** | `domain/` package | `application/` or `service/` package |
| **Dependencies** | None (pure domain logic) | Repositories, event buses, external APIs |
| **State** | Stateless (pure functions on a struct) | Stateless, coordinates infrastructure |
| **Example** | `PricingService.CalculateDiscount(order)` | `OrderService.SubmitOrder(ctx, id)` |
| **Tests** | Unit tests, no mocks | Integration tests or mocks for repos |

```go
// Domain Service — pure logic, no infrastructure
package order

type PricingService struct{}

func (PricingService) CalculateDiscount(order *Order, customerTier CustomerTier) Money {
    // Pure business logic
    total := order.Total()
    switch customerTier {
    case Gold:
        return total.Multiply(int(15)).Div(100) // 15%
    case Silver:
        return total.Multiply(int(10)).Div(100) // 10%
    default:
        return Money{} // no discount
    }
}

// Application Service — orchestrates infrastructure
package application

type OrderService struct {
    repo     order.Repository
    pricing  order.PricingService
    eventBus event.Bus
}

func (s *OrderService) SubmitOrder(ctx context.Context, id order.OrderID) error {
    o, err := s.repo.FindByID(ctx, id)
    if err != nil {
        return fmt.Errorf("finding order: %w", err)
    }
    if err := o.Submit(); err != nil {
        return fmt.Errorf("submitting order: %w", err)
    }
    if err := s.repo.Save(ctx, o); err != nil {
        return fmt.Errorf("saving order: %w", err)
    }
    return s.eventBus.Publish(ctx, o.Events()...)
}
```

### Factory Functions in Go

Go's `NewXxx` convention maps naturally to DDD factory patterns:

```go
// Simple factory
func NewOrder(id OrderID, customerID string) *Order {
    return &Order{
        id:         id,
        customerID: customerID,
        status:     Draft,
        lines:      make([]OrderLine, 0),
        events:     make([]DomainEvent, 0),
    }
}

// Factory with validation
func NewMoney(amount int64, currency string) (Money, error) {
    if amount < 0 {
        return Money{}, ErrNegativeAmount
    }
    return Money{amount: amount, currency: currency}, nil
}

// Factory from another aggregate (reconstitution)
func ReconstituteOrder(id OrderID, status OrderStatus, lines []OrderLine, total Money) *Order {
    return &Order{
        id:     id,
        status: status,
        lines:  lines,
        total:  total,
    }
}
```

`Reconstitute` functions bypass constructor validation — they are for loading from the database where you trust the stored data is consistent. This is a deliberate escape hatch.

### Specification Pattern in Go

The Specification pattern allows composing business rules. In Go, this is expressed through the functional options pattern or, more classically, through an interface:

```go
package specification

type Specification[T any] interface {
    IsSatisfiedBy(candidate T) bool
}

type AndSpec[T any] struct {
    specs []Specification[T]
}

func (a AndSpec[T]) IsSatisfiedBy(candidate T) bool {
    for _, spec := range a.specs {
        if !spec.IsSatisfiedBy(candidate) {
            return false
        }
    }
    return true
}

type OrSpec[T any] struct {
    specs []Specification[T]
}

func (o OrSpec[T]) IsSatisfiedBy(candidate T) bool {
    for _, spec := range o.specs {
        if spec.IsSatisfiedBy(candidate) {
            return true
        }
    }
    return false
}
```

A more Go-idiomatic approach is to use function types directly:

```go
type OrderSpec func(*Order) bool

func HasMinimumTotal(min Money) OrderSpec {
    return func(o *Order) bool {
        return o.Total().Amount() >= min.Amount()
    }
}

func IsInStatus(statuses ...OrderStatus) OrderSpec {
    return func(o *Order) bool {
        for _, s := range statuses {
            if o.Status() == s {
                return true
            }
        }
        return false
    }
}

func And(specs ...OrderSpec) OrderSpec {
    return func(o *Order) bool {
        for _, s := range specs {
            if !s(o) { return false }
        }
        return true
    }
}
```

### Go Embedding vs Inheritance

Go has no inheritance. Use struct embedding for composition:

```go
type BaseEntity struct {
    id        string
    createdAt time.Time
    updatedAt time.Time
}

type Order struct {
    BaseEntity              // Embedding — NOT inheritance
    customerID string
    status     OrderStatus
    total      Money
}
```

This is composition, not a "is-a" relationship. `Order` does not inherit from `BaseEntity`; it embeds it. Methods on `BaseEntity` are promoted to `Order`, but `Order` cannot be passed where `BaseEntity` is expected (unless `BaseEntity` satisfies an interface).

**Preferred Go pattern for shared fields: use value objects, not embedding.**

```go
// Prefer this:
type Order struct {
    id        EntityID
    audit     AuditInfo  // value object: CreatedAt, UpdatedAt
    status    OrderStatus
}

// Over this:
type Order struct {
    BaseEntity  // embedding mixes concerns
    status OrderStatus
}
```

---

## Runtime Behavior

### Aggregate Lifecycle

```
 ┌──────────┐    Create()     ┌──────────┐    Submit()    ┌──────────┐
 │          │ ───────────────→│  Draft   │ ──────────────→│Submitted │
 │  (nil)   │                 │          │                │          │
 │          │                 └────┬─────┘                └────┬─────┘
 └──────────┘                      │                           │
                                   │ Cancel()        Pay()     │
                                   ↓                           ↓
                              ┌──────────┐               ┌──────────┐
                              │Cancelled │               │   Paid   │
                              │  (end)   │               │          │
                              └──────────┘               └────┬─────┘
                                                              │
                                                         Ship()│
                                                              ↓
                                                         ┌──────────┐
                                                         │ Shipped  │
                                                         │  (end)   │
                                                         └──────────┘
```

Each transition is a method on the aggregate. The aggregate validates the transition and records a domain event. The repository persists both the new state and the events atomically.

### Repository Save Flow

```
Application Service
    │
    ├─► repo.FindByID(ctx, id)       // Load aggregate from DB
    │       │
    │       └─► SELECT ... FROM orders WHERE id = $1
    │           SELECT ... FROM order_lines WHERE order_id = $1
    │
    ├─► order.AddLine(product, qty, price)  // Mutate in memory
    │       │
    │       └─► Validate invariants, recalculate total, record events
    │
    └─► repo.Save(ctx, order)        // Persist changes
            │
            └─► BEGIN TRANSACTION
                UPSERT INTO orders ...
                DELETE FROM order_lines WHERE order_id = $1 AND id NOT IN (...)
                UPSERT INTO order_lines ...
                INSERT INTO outbox (event_type, payload) ...
                COMMIT
```

### Domain Event Flow

```
Aggregate                      Repository                    Event Bus
   │                               │                            │
   │── Submit() recorded            │                            │
   │   OrderSubmitted event ──────► │                            │
   │                               │                            │
   │                               │── Save() writes             │
   │                               │   event to outbox          │
   │                               │   in same transaction      │
   │                               │                            │
   │                               │── COMMIT (aggregate        │
   │                               │   + outbox committed)      │
   │                               │                            │
   │                               │── Publish events ─────────►│
   │                               │                            │
   │                               │            OrderSubmitted   │
   │                               │◄────────────────────────────│
   │                               │            event delivered  │
```

---

## Request Flow Diagrams

### Create Order (Happy Path)

```
    HTTP POST /api/orders
         │
         ▼
┌─────────────────────┐
│  Chi Handler         │  Parse JSON → CreateOrderRequest
│  (adapter/inbound)   │
└────────┬────────────┘
         │ CreateOrderRequest
         ▼
┌─────────────────────┐
│  Application Service  │  Validate input, generate ID
│  OrderService         │  Call domain factory
└────────┬────────────┘
         │ order.NewOrder(id)
         ▼
┌─────────────────────┐
│  Domain Layer         │  Create Order aggregate
│  Order (aggregate)    │  Status = Draft, lines = []
│  Money (value object) │  All invariants hold
└────────┬────────────┘
         │ Aggregate constructed
         ▼
┌─────────────────────┐
│  Application Service  │  Call repository.Save()
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  PostgreSQL Repo     │  BEGIN; INSERT order; COMMIT;
│  (adapter/outbound)  │  Return saved order
└────────┬────────────┘
         │
         ▼
    HTTP 201 Created
    {"order_id": "ord_abc123", "status": "draft"}
```

### Add Line Item (State Machine Validation)

```
    HTTP POST /api/orders/{id}/lines
         │
         ▼
┌─────────────────────┐
│  Chi Handler         │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  Application Service │  repo.FindByID(ctx, id)
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  Order Aggregate     │  if o.status != Draft → error
│                      │  o.AddLine(productID, qty, price)
│                      │  → recalculateTotal()
│                      │  → append OrderLineAdded event
└────────┬────────────┘
         │ Error: "order cannot be modified after submission"
         │ OR
         │ Success: aggregate mutated
         ▼
┌─────────────────────┐
│  Repository          │  Save aggregate + events
└────────┬────────────┘
         │
         ▼
    HTTP 200 OK / 409 Conflict / 422 Unprocessable
```

---

## Lifecycle Diagrams

### Aggregate Instance Lifecycle

```
┌──────────────────────────────────────────────────────────────┐
│                    AGGREGATE INSTANCE LIFE                    │
│                                                              │
│  [Created]          [Loaded]          [Mutated]    [Saved]    │
│      │                  │                  │           │      │
│      │ NewOrder(id)     │ FindByID()       │           │      │
│      ▼                  ▼                  │           │      │
│  ┌──────┐          ┌──────────────┐        │           │      │
│  │ New  │─────────►│ Reconstitute │        │           │      │
│  │Order │          │  from DB     │        │           │      │
│  └──────┘          └──────┬───────┘        │           │      │
│                           │                │           │      │
│                           │ AddLine()      │           │      │
│                           │ Submit()       │           │      │
│                           │ Cancel()       │           │      │
│                           │                ▼           │      │
│                           │    ┌──────────────────┐   │      │
│                           │    │ Invariants Check │   │      │
│                           │    │ Events Collected │   │      │
│                           │    └────────┬─────────┘   │      │
│                           │             │             │      │
│                           │             ▼             │      │
│                           │    ┌──────────────────┐   │      │
│                           └───►│   Mutated State   │───┘      │
│                                └──────────────────┘          │
│                                       │                      │
│                                       │ Save()               │
│                                       ▼                      │
│                                ┌──────────────────┐          │
│                                │ DB Transaction    │          │
│                                │ Aggregate + Events│          │
│                                └──────────────────┘          │
└──────────────────────────────────────────────────────────────┘
```

### Event Publication Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│                DOMAIN EVENT LIFECYCLE                        │
│                                                              │
│  [Emitted]         [Stored]           [Published]             │
│     │                  │                    │                 │
│     │ o.events=append  │                    │                 │
│     ▼                  ▼                    ▼                 │
│  ┌────────┐       ┌──────────┐       ┌──────────────┐       │
│  │In-Memory│─────►│  Outbox   │──────►│ Message Broker│      │
│  │ Event   │       │  Table    │       │ (Kafka/NATS)  │      │
│  │ Struct  │       │           │       │               │      │
│  └────────┘       └──────────┘       └───────┬───────┘       │
│                                              │               │
│                  ┌───────────────────────────┘               │
│                  ▼                                           │
│           ┌──────────────┐                                   │
│           │  Consumer     │  Other service / same service     │
│           │  Handler      │  Process event                    │
│           └──────────────┘                                   │
└─────────────────────────────────────────────────────────────┘
```

---

## Source Code Reading Guide

### Recommended Reading Order

| Order | File / Package | Focus | Time |
|-------|---------------|-------|------|
| 1 | `domain/order/order.go` | Aggregate root definition, state machine | 20 min |
| 2 | `domain/order/order_test.go` | Test-driven aggregate invariants | 15 min |
| 3 | `domain/order/money.go` | Value object with validation | 10 min |
| 4 | `domain/order/order_repository.go` | Repository interface (consumer-side) | 5 min |
| 5 | `application/order_service.go` | Application service orchestration | 10 min |
| 6 | `adapters/postgres/order_repo.go` | Repository implementation, outbox | 15 min |
| 7 | `adapters/inbound/order_handler.go` | Chi HTTP handler, request mapping | 10 min |
| 8 | `cmd/server/main.go` | Wire-up, dependency composition | 10 min |
| 9 | `domain/event/bus.go` | Event bus interface, in-memory implementation | 10 min |
| 10 | `domain/order/specification.go` | Specification pattern for queries | 10 min |

### What to Ignore

- **ORM-style code**: Go DDD repositories use raw SQL, not GORM. If you see GORM models with annotations, it's not DDD — it's anemic CRUD.
- **Auto-generated getter/setter boilerplate**: Go doesn't use it. A few accessor methods are fine; dozens of `GetXxx`/`SetXxx` methods mean the design is wrong.
- **DTOs in the domain layer**: Domain objects are not DTOs. Request/response DTOs belong in the handler/adapter layer.
- **Cross-aggregate references by pointer**: If you see `order.Customer *Customer`, that's wrong. Use `order.CustomerID string`.
- **Transaction scripts named as services**: If `OrderService` has 20 methods that just call repository methods, it's not DDD — it's a transaction script.

---

## Production Failure Scenarios

### Scenario 1: Concurrent Aggregate Modification

**Symptom**: Two API calls modify the same order simultaneously. One succeeds, the other silently overwrites changes.

**Root cause**: No optimistic concurrency control on the aggregate.

**Fix**: Add a `version` field to the aggregate and use `UPDATE ... WHERE version = $currentVersion`:

```go
type Order struct {
    id      OrderID
    version int  // Incremented on every mutation
    // ...
}

func (r *OrderRepository) Save(ctx context.Context, order *Order) error {
    result, err := r.db.ExecContext(ctx,
        "UPDATE orders SET status=$1, total=$2, version=version+1 WHERE id=$3 AND version=$4",
        order.Status(), order.Total(), order.ID(), order.Version(),
    )
    if rows, _ := result.RowsAffected(); rows == 0 {
        return ErrConcurrentModification
    }
    return err
}
```

### Scenario 2: Partial Aggregate Load

**Symptom**: Order is loaded without all order lines. Total is recalculated to zero. Money is lost.

**Root cause**: Repository implementation loads the aggregate incompletely.

**Fix**: The repository MUST load all child entities in a single query or transaction:

```go
func (r *OrderRepository) FindByID(ctx context.Context, id OrderID) (*Order, error) {
    order, err := r.findOrder(ctx, id)
    if err != nil {
        return nil, err
    }
    lines, err := r.findOrderLines(ctx, id)
    if err != nil {
        return nil, err
    }
    return order.Reconstitute(lines), nil // Always load full aggregate
}
```

### Scenario 3: Event Lost After Save

**Symptom**: Order state is persisted but domain event is not published. Downstream systems never process the event.

**Root cause**: Event publishing happens outside the database transaction.

**Fix**: Use the outbox pattern — write events in the same transaction as the aggregate:

```go
func (r *OrderRepository) Save(ctx context.Context, order *Order) error {
    tx, _ := r.db.BeginTx(ctx, nil)
    defer tx.Rollback()

    r.upsertOrder(tx, order)
    r.upsertLines(tx, order)
    for _, evt := range order.Events() {
        r.insertOutbox(tx, evt) // Same transaction
    }
    return tx.Commit()
    // Publisher reads from outbox later (at-least-once delivery)
}
```

### Scenario 4: Rich Domain Model Performance

**Symptom**: Loading an Order with 10,000 line items for a status check takes 5 seconds.

**Root cause**: Full aggregate loaded for a simple read operation.

**Fix**: Separate read models from write models. For status checks, query a dedicated read table:

```go
// Write model — full aggregate (used for mutations)
type Order struct { /* full aggregate */ }

// Read model — projection (used for queries)
type OrderSummary struct {
    ID        OrderID     `json:"id"`
    Status    OrderStatus `json:"status"`
    LineCount int         `json:"line_count"`
    Total     Money       `json:"total"`
}
// Query SELECT id, status, line_count, total FROM order_summaries WHERE id = $1
```

### Scenario 5: Zero Value Confusion

**Symptom**: A `Money{amount: 0, currency: ""}` value object passes through validation silently. Prices appear as "$0.00".

**Root cause**: Go zero values bypass factory function validation when struct literals are used.

**Fix**: Never use struct literals for value objects outside of factory functions. Consider linter rules to enforce this:

```go
// BAD — bypasses validation
m := Money{amount: 0} // currency is ""

// GOOD — always goes through factory
m, err := NewMoney(0, "USD")
```

---

## Debugging Techniques

### 1. Aggregate State Inspection

When an aggregate is in the wrong state, dump its full history:

```go
func DebugOrderHistory(ctx context.Context, repo order.Repository, id OrderID) {
    events, err := repo.FindEvents(ctx, id) // Query event stream / outbox
    fmt.Printf("Event stream for order %s:\n", id)
    for i, e := range events {
        fmt.Printf("  %d. %T at %s: %+v\n", i+1, e, e.OccurredAt(), e)
    }
}
```

### 2. Invariant Violation Tracing

Wrap aggregate methods with a debug decorator:

```go
type DebugOrder struct {
    *order.Order
    logger *slog.Logger
}

func (d *DebugOrder) Submit() error {
    d.logger.Info("Submit called", "status_before", d.Status())
    err := d.Order.Submit()
    d.logger.Info("Submit result", "status_after", d.Status(), "error", err)
    return err
}
```

### 3. Repository Query Logging

Enable SQL query logging at the repository level, not the ORM level (Go repos use raw SQL):

```go
type LoggingOrderRepository struct {
    inner  order.Repository
    logger *slog.Logger
}

func (r *LoggingOrderRepository) FindByID(ctx context.Context, id OrderID) (*Order, error) {
    start := time.Now()
    o, err := r.inner.FindByID(ctx, id)
    r.logger.Info("FindByID", "id", id, "duration", time.Since(start), "error", err)
    return o, err
}
```

### 4. Event Bus Tracing

Intercept all events published and log them:

```go
type TracingEventBus struct {
    inner event.Bus
}

func (b *TracingEventBus) Publish(ctx context.Context, events ...event.DomainEvent) error {
    for _, e := range events {
        log.Printf("[TRACE] Publishing event: %T | %+v", e, e)
    }
    return b.inner.Publish(ctx, events...)
}
```

---

## Observability Considerations

### Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aggregate_load_duration_seconds` | Histogram | `aggregate_type`, `operation` | Time to load aggregate from DB |
| `aggregate_save_duration_seconds` | Histogram | `aggregate_type` | Time to persist aggregate |
| `domain_events_published_total` | Counter | `event_type`, `status` | Events published (success/fail) |
| `invariant_violations_total` | Counter | `aggregate_type`, `violation` | Business rule violations caught |
| `aggregate_concurrent_conflicts_total` | Counter | `aggregate_type` | Optimistic lock failures |

### Structured Logging (slog)

```go
logger.Info("aggregate_mutated",
    "aggregate_type", "Order",
    "aggregate_id", order.ID(),
    "mutation", "AddLine",
    "new_total", order.Total(),
    "event_count", len(order.Events()),
)

logger.Error("invariant_violation",
    "aggregate_type", "Order",
    "aggregate_id", id,
    "violation", "cannot submit empty order",
)
```

### Tracing

Inject trace context into the aggregate for correlation:

```go
func (r *OrderRepository) Save(ctx context.Context, order *Order) error {
    span, ctx := tracer.Start(ctx, "OrderRepository.Save")
    defer span.End()
    span.SetAttributes(
        attribute.String("order.id", string(order.ID())),
        attribute.String("order.status", order.Status().String()),
    )
    // ...
}
```

---

## Performance Implications

### Aggregate Size Matters

| Aggregate Size | Load Time | Memory | Concurrency Risk | Recommendation |
|---------------|-----------|--------|-------------------|----------------|
| < 100 lines | < 5ms | < 1MB | Low | Ideal |
| 100-1000 lines | 10-50ms | 5-50MB | Medium | Acceptable with pagination of child entities |
| > 1000 lines | 100ms+ | 100MB+ | High | Refactor: split into smaller aggregates |

### Repository Performance Patterns

1. **Batch loading**: Load multiple aggregates at once with `WHERE id IN ($1, $2, $3)`
2. **Snapshot-based loading**: For event-sourced aggregates, maintain periodic snapshots
3. **Read models**: Separate query-optimized tables that bypass aggregate loading entirely
4. **Lazy loading: DON'T**: Lazy loading in Go leads to N+1 problems; always eager-load the full aggregate

### Event Bus Backpressure

An in-memory event bus with unbuffered channels can block the HTTP handler. Use a buffered channel or a proper message queue for production:

```go
// Development — fine for testing
type InMemoryBus struct {
    handlers map[string][]event.EventHandler
}

// Production — buffered with backpressure
type BufferedBus struct {
    queue chan event.DomainEvent
    handlers map[string][]event.EventHandler
}
```

---

## Architecture Implications

### When DDD Adds Value

- **Complex payment workflows**: Multiple state transitions, invariants across entities
- **Regulatory compliance**: Audit trails, immutable event logs, strict validation
- **Multi-stakeholder domains**: Ordering, fulfillment, billing — different teams own different aggregates
- **Long-lived business processes**: Orders that span days/weeks with multiple state changes

### When DDD is Harmful

- **Simple CRUD APIs**: `GET /users`, `POST /users` — DDD adds 5x files with zero business value
- **Proxies / Passthroughs**: Services that just forward requests to another API
- **Data pipelines**: ETL jobs that transform and move data
- **Internal tools / Admin panels**: Low complexity, high iteration speed required
- **Startup MVPs**: You don't know your domain boundaries yet; premature DDD locks you into wrong abstractions

### Decision Framework

```
Is business logic complex? (many rules, states, invariants)
    │
    ├── YES ──► Will multiple teams work on the same code?
    │               │
    │               ├── YES ──► Full DDD (aggregates, bounded contexts)
    │               │
    │               └── NO  ──► Light DDD (aggregates, no bounded contexts)
    │
    └── NO  ──► Is the service expected to live > 2 years?
                    │
                    ├── YES ──► Feature-based with rich domain objects
                    │
                    └── NO  ──► Transaction script / simple service layer
```

### Common Misuses

1. **DDD for simple CRUD**: Every entity becomes an aggregate, every field a value object. Result: 50 files for a TODO app.
2. **Anemic domain model masquerading as DDD**: Structs with exported fields, all logic in "services." This is transactional scripting with DDD vocabulary.
3. **Bounded context fever**: Splitting a 10-table schema into 5 bounded contexts. Cross-context queries become a nightmare.
4. **Event sourcing everything**: Not every aggregate needs event sourcing. Use event sourcing only when you need temporal queries or audit trails.

---

## Team Ownership Implications

### Aggregate Ownership Matrix

| Aggregate | Owning Team | Consumers | Contract Type |
|-----------|------------|-----------|---------------|
| Order | Checkout Team | Fulfillment, Billing | REST API + events |
| Payment | Payments Team | Checkout, Subscriptions | gRPC + events |
| Customer | Identity Team | All teams | GraphQL + events |

### Cross-Team Aggregate Rules

1. **One team owns the aggregate, period.** Shared ownership leads to inconsistent invariants.
2. **Other teams consume via API, not direct database access.** The owning team's database is a private implementation detail.
3. **Events are the integration contract.** If the owning team changes the aggregate's internal structure but keeps the event schema compatible, consumers are unaffected.
4. **Repository interfaces define the contract within the service.** The owning team can swap PostgreSQL for CockroachDB without changing the domain layer.

---

## Interview Questions

**1. Q: What is the difference between an entity and a value object in Go?**

A: An entity has identity (an `ID` field that distinguishes it from other instances even if all other fields are identical). Example: `Order{ID: "ord_1"}` and `Order{ID: "ord_2"}` are different entities even if all values match. A value object has no identity — equality is based on all fields. Example: `Money{1000, "USD"}` and `Money{1000, "USD"}` are the same value. In Go, entities are typically structs with unexported fields mutated through methods; value objects are immutable structs created through factory functions.

**2. Q: How do you enforce aggregate invariants in Go without inheritance or AOP?**

A: Three mechanisms: (1) Unexported fields — only methods in the same package can mutate state. (2) Factory functions with validation (`NewXxx` returns `(Xxx, error)`) — invariants checked at construction. (3) Mutator methods that validate state transitions — every `Submit()`, `AddLine()`, `Cancel()` method checks preconditions before mutating. There is no equivalent of Spring's `@Transactional` or `@PrePersist` — Go makes invariants explicit.

**3. Q: Where should repository interfaces be defined in a Go DDD project?**

A: In the domain package, where they are consumed. This follows Go's convention: "define interfaces where you use them, not where you implement them." The domain package defines `order.Repository` as an interface. The PostgreSQL adapter in `adapters/postgres/` implements it as a struct. The domain package imports nothing from the infrastructure layer — dependency inversion.

**4. Q: How do you handle cross-aggregate business rules in Go?**

A: Use domain services for stateless operations, domain events for eventual consistency. For immediate consistency: load both aggregates in the application service, pass them to a domain service, validate the rule, save both. For eventual consistency: save the first aggregate with a domain event, process the event in a handler, validate the rule asynchronously, and potentially trigger compensating actions. The key Go idiom: domain services are pure logic with no infrastructure dependencies.

**5. Q: What is the outbox pattern and why is it critical in Go DDD?**

A: The outbox pattern ensures atomicity between aggregate persistence and event publication. When an aggregate saves, domain events are written to an `outbox` table in the same database transaction. A separate publisher process reads from the outbox and publishes to the message broker. Without this, you risk: (a) aggregate saved, event not published (lost event), or (b) event published, aggregate not saved (phantom event). In Go, the repository's `Save()` method is the natural place to implement this — raw SQL, no ORM magic.

**6. Q: How does Go's lack of generics (pre-1.18) or generics (post-1.18) affect DDD patterns?**

A: Pre-1.18: Repository interfaces were aggregate-specific (`OrderRepository`, `PaymentRepository`), not generic. Value objects had no common base type. Event handlers used `interface{}` and type assertions. Post-1.18: You CAN write `type Repository[T Aggregate] interface{}` but Go idiomatically prefers specific interfaces. Generics are useful for specification patterns (`Specification[T]`), event bus dispatch (`Publish[T Event](ctx, T)`), and generic value object helpers. Most production Go DDD codebases still use concrete interfaces — generics add complexity without proportional benefit in DDD patterns.

**7. Q: What are the signals that DDD is being over-applied in a Go codebase?**

A: (1) More than 3 files per simple CRUD entity. (2) Repository interfaces with methods that always map 1:1 to CRUD operations (FindByID, Save, Delete — no business semantics). (3) Value objects wrapping single strings without validation beyond "not empty." (4) Event sourcing for aggregates with 2 states. (5) Domain services that just delegate to repositories. (6) DDD vocabulary in code but structs have fully exported fields.

**8. Q: How do you test an aggregate root in Go?**

A: Test three things: (1) Valid transitions — call `Submit()` on a draft order, verify status changed and events recorded. (2) Invalid transitions rejected — call `Submit()` on an already submitted order, verify the correct error is returned. (3) Invariant enforcement — try to add a negative-price line item, verify the error. No mocking frameworks needed — use interfaces for dependencies at the application service level. Aggregate tests are pure unit tests: instantiate, call methods, assert. The test structure follows:

```go
func TestOrder_Submit(t *testing.T) {
    t.Run("valid transition from draft", func(t *testing.T) {
        order := NewOrder(testID)
        order.AddLine("prod_1", 2, mustMoney(1000, "USD"))
        err := order.Submit()
        assert.NoError(t, err)
        assert.Equal(t, Submitted, order.Status())
        assert.Len(t, order.Events(), 1)
    })
    t.Run("rejects when already submitted", func(t *testing.T) {
        order := submittedOrderFixture(t)
        err := order.Submit()
        assert.ErrorIs(t, err, ErrInvalidTransition)
    })
}
```

**9. Q: How do you decide between a domain service and putting logic on the aggregate?**

A: If the logic operates on data owned by a single aggregate and doesn't require external information, it belongs on the aggregate (e.g., `order.AddLine()`). If the logic operates across multiple aggregates, needs external data (e.g., a tax rate from a tax service), or is a stateless calculation, it belongs in a domain service (e.g., `PricingService.CalculateDiscount(order, customerTier)`). Application services coordinate between the two but contain no business logic themselves.

**10. Q: How do value objects interact with Go's JSON serialization and database persistence?**

A: Value objects need custom `MarshalJSON`/`UnmarshalJSON` and database `Scan`/`Value` methods:

```go
func (m Money) MarshalJSON() ([]byte, error) {
    return json.Marshal(struct {
        Amount   int64  `json:"amount"`
        Currency string `json:"currency"`
    }{m.amount, m.currency})
}

func (m *Money) UnmarshalJSON(data []byte) error {
    var raw struct{ Amount int64; Currency string }
    json.Unmarshal(data, &raw)
    parsed, err := NewMoney(raw.Amount, raw.Currency)
    if err != nil {
        return err
    }
    *m = parsed
    return nil
}

func (m *Money) Scan(src interface{}) error {
    // Parse from PostgreSQL composite type or JSONB column
}
```

This is Go boilerplate but it ensures no invalid value object enters the system through serialization boundaries.

---

## Hands-On Exercises

### Exercise 1: Build a Payment Aggregate

Create a `Payment` aggregate with these invariants:
- Payment has states: `Pending → Authorized → Captured` or `Pending → Failed`
- Payment amount must be positive, in a valid currency
- Cannot capture a failed or already-captured payment
- Captured amount cannot exceed authorized amount

Test all state transitions. Time: 30 minutes.

### Exercise 2: Implement the Outbox Pattern

Extend the `OrderRepository` to write domain events to an `outbox` table in the same transaction. Write a simple in-memory event bus that dispatches events to registered handlers. Implement an `OrderConfirmed` event handler that sends a fake email notification. Time: 45 minutes.

### Exercise 3: Refactor Anemic to Rich Domain Model

Given this anemic code, refactor it to a rich domain model:

```go
type Order struct {
    ID     string
    Status string // "draft", "submitted", "paid" — no validation
    Total  float64
    Lines  []OrderLine
}

type OrderService struct {
    db *sql.DB
}

func (s *OrderService) SubmitOrder(orderID string) error {
    var order Order
    // SELECT ... scan into order
    if order.Status != "draft" {
        return errors.New("invalid status")
    }
    order.Status = "submitted"
    // UPDATE ... set status
    return nil
}
```

Move validation into the aggregate, add proper types for status, amount, and ID. Time: 30 minutes.

### Exercise 4: Implement a Specification

Create a `CustomerSpecification` that evaluates whether a customer is eligible for a loan based on:
- Credit score > 650
- Annual income > $50,000
- Age between 21 and 75

Use the functional options pattern. Compose multiple specifications with `And`/`Or`. Write tests for each combination. Time: 25 minutes.

### Exercise 5: Domain Event Versioning

Design version 2 of an `OrderSubmitted` event that adds a `CustomerTier` field. Implement a consumer that handles both v1 (without tier) and v2 (with tier) events. Write tests for both versions. Time: 30 minutes.

---

## Advanced Challenges

### Challenge 1: Event-Sourced Order Aggregate

Replace the state-based `Order` aggregate with an event-sourced version. Instead of storing current state, store all events. Rebuild state by replaying events. Implement snapshots at every 100 events. Measure the performance difference between state-based and event-sourced for loading aggregates with 10,000 events.

Requirements:
- `Order` has no mutable state fields — all state derived from events
- `Apply(event)` method that mutates state (private)
- `Events() []DomainEvent` returns uncommitted events
- Repository stores events, not state
- Snapshotting at configurable intervals

### Challenge 2: Multi-Aggregate Saga

Implement a saga pattern across three aggregates: `Order`, `Payment`, `Inventory`. When an order is submitted:
1. Reserve inventory (Inventory aggregate)
2. Authorize payment (Payment aggregate)
3. If payment fails, release inventory (compensating action)
4. If inventory fails, cancel payment authorization (compensating action)

Use domain events and a saga orchestrator. Ensure idempotency — the saga can be retried without duplicating side effects.

### Challenge 3: Aggregate Performance Benchmark

Build a benchmark comparing three approaches for a complex query ("find all orders containing product X, placed by gold-tier customers, in the last 30 days"):
1. Load all orders as full aggregates, filter in memory
2. Use a dedicated read model (materialized view / query table)
3. Use the repository with a specification pattern (translated to SQL WHERE clauses)

Measure throughput, latency (p50, p99), and memory usage for 10,000 orders. Present findings with recommendations.

---

## Key Insights

1. **DDD in Go is about discipline, not frameworks.** Go has no `@Entity`, `@AggregateRoot`, or `@DomainService` annotations. The patterns are enforced by conventions: unexported fields, constructor validation, and method-based state transitions. This is both liberating (no magic) and demanding (you must enforce consistency yourself).

2. **Go's package system is the bounded context boundary.** The `order` package is a natural bounded context. Unexported identifiers are private to the context. Exported identifiers are the context's public API. No framework needed — the language itself provides the encapsulation.

3. **The repository interface is the most important design decision.** Where you define it (domain package) and how you implement it (adapter package) determines whether your architecture is truly layered or just annotated spaghetti. Define the interface in the domain, implement it in infrastructure.

4. **Rich domain models are not always better than anemic models.** For a simple CRUD service, an anemic model with transaction scripts is 5x faster to build and 3x easier to understand. Use DDD when the business complexity justifies the ceremony, not because a blog post told you to.

5. **Events are the escape hatch from aggregate isolation.** Aggregates should not reference each other directly. Events allow them to react to changes in other aggregates asynchronously. The outbox pattern makes this reliable. Without the outbox pattern, event-driven DDD is a ticking time bomb.

6. **Zero values are Go's silent DDD killer.** A `Money{}` with zero amount and empty currency is a valid Go value but an invalid money concept. Factory functions are not optional in DDD — they are the only defense. Use linters and code review to catch struct literals for value objects.

7. **Go DDD is smaller, faster, and more explicit than Java DDD.** A Java DDD aggregate might involve 5 files (entity class, repository interface, JPA repository, service class, DTO). In Go, it's often 2-3 files (aggregate, repository interface, repository implementation). The boilerplate reduction is dramatic, but the design discipline is the same.
