# Session 07: Clean Architecture & Vertical Slice Architecture

## Why This Topic Exists

Most Go HTTP services start with a single `main.go` that handles everything: routing, business logic, database queries, and JSON marshaling in one file. This works for a week, then the file hits 800 lines, then 2000 lines. At some point, a teammate asks "where does the order validation logic live?" and nobody can answer definitively because it is scattered across three route handlers and a helper function named `validateStuff()`. Clean Architecture and Vertical Slice Architecture are two distinct answers to the question "how should I organize Go code so that changing business rules does not require touching HTTP or database code, and vice versa?"

The broader industry context matters here. Uncle Bob's Clean Architecture (2012) formalized what the Go community had been rediscovering through trial and error: inner layers define interfaces, outer layers implement them. This maps naturally onto Go because Go interfaces are satisfied implicitly—you never declare `MyStruct implements MyInterface`. This means you can define an interface in your domain package and implement it in your PostgreSQL adapter package without either package importing the other. The compiler enforces a directed acyclic graph for imports, which means circular dependencies are impossible. This is the single most important Go-specific characteristic that makes Clean Architecture work: the compiler physically prevents you from violating the dependency rule.

Vertical Slice Architecture emerged later as a reaction to Clean Architecture's primary weakness: cognitive overhead. In Clean Architecture, adding a single new endpoint touches 4-5 directories (`domain/`, `usecases/`, `adapters/inbound/`, `adapters/outbound/`, `infrastructure/`). For a team of 3 engineers building a CRUD API with 12 endpoints, this is exhausting. Vertical Slice says: put everything one feature needs—the HTTP handler, the business logic, the database query, the response type—into a single file or a small cluster of co-located files. The tradeoff is that cross-cutting concerns (auth, logging, metrics) now need explicit wiring rather than being handled by layered middleware stacks. Understanding when to use which pattern, and how to transition between them, is what separates senior from staff-level engineers.

## Mental Model

Think of Clean Architecture as a set of concentric rings. The innermost ring is your domain entities—pure Go structs with no framework annotations, no ORM tags, no HTTP-specific concerns. The next ring is use cases (interactors), which orchestrate domain entities to fulfill application-specific operations. The outer rings are adapters: inbound adapters translate HTTP requests into use case inputs, outbound adapters implement repository interfaces against PostgreSQL, Redis, or external gRPC services. The outermost ring is the framework itself—Chi router, database driver, configuration loader. The dependency rule says source code dependencies point inward: an HTTP handler may import a use case, a use case may import a domain entity, but never the reverse.

Vertical Slice has a different mental model: a feature is a vertical column that cuts through all "layers" simultaneously. Instead of grouping code by technical role (all handlers together, all repositories together), you group by business capability (all the code for "create order" lives together). This maps onto Conway's Law: if your team is organized around features (order team, payment team, user team), your architecture should mirror that. If your team is organized around layers (frontend team, backend team, database team), Clean Architecture maps better.

```
Clean Architecture Directory Tree:

┌─────────────────────────────────────────────────────┐
│ domain/                                             │
│   entities/                                         │
│     order.go              ← Order struct, pure      │
│     payment.go            ← Payment struct, pure    │
│ usecases/                                           │
│   create_order.go         ← CreateOrderUseCase      │
│   process_payment.go      ← ProcessPaymentUseCase   │
│ adapters/                                           │
│   inbound/                                          │
│     http/                                           │
│       order_handler.go    ← chi handler             │
│       payment_handler.go  ← chi handler             │
│   outbound/                                         │
│     postgres/                                       │
│       order_repo.go       ← implements OrderRepo    │
│       payment_repo.go     ← implements PaymentRepo  │
│ infrastructure/                                     │
│   router.go               ← chi.NewRouter()         │
│   config.go               ← env/config loading      │
└─────────────────────────────────────────────────────┘

Vertical Slice Directory Tree:

┌─────────────────────────────────────────────────────┐
│ features/                                           │
│   orders/                                           │
│     create_order.go       ← handler+usecase+repo    │
│     get_order.go          ← handler+usecase+repo    │
│     cancel_order.go       ← handler+usecase+repo    │
│     types.go              ← shared request/response │
│   payments/                                         │
│     process_payment.go                              │
│     refund.go                                       │
│     types.go                                        │
│ shared/                                             │
│   middleware/            ← cross-cutting            │
│   database.go            ← *sql.DB singleton        │
│   errors.go              ← shared error types       │
└─────────────────────────────────────────────────────┘
```

The key insight: in Clean Architecture, adding a feature requires modifying files across 4+ directories. In Vertical Slice, most features are a single file. The cost of Vertical Slice is that if 6 features need the same database query, you either duplicate it (pragmatic, debated) or extract it to a shared package (moving back toward Clean Architecture). Neither approach is universally correct; the right choice depends on team size, project maturity, and the cost of change.

## Internal Architecture

### Clean Architecture in Go: The Dependency Rule in Practice

The most important Go file in a Clean Architecture system is the one that lives in the `domain/entities` package. It contains no imports from your application, only standard library types. An order entity looks like this:

```go
// domain/entities/order.go
package entities

import "time"

type OrderStatus string

const (
    OrderPending   OrderStatus = "pending"
    OrderConfirmed OrderStatus = "confirmed"
    OrderCancelled OrderStatus = "cancelled"
)

type Order struct {
    ID        string
    UserID    string
    Items     []OrderItem
    Status    OrderStatus
    Total     Money
    CreatedAt time.Time
}

type OrderItem struct {
    ProductID string
    Quantity  int
    UnitPrice Money
}

type Money struct {
    Amount   int64
    Currency string
}
```

Notice what is absent: no `json:"id"` struct tags, no `db:"user_id"` annotations, no `validate:"required"` tags. These are pure domain types. The JSON representation is defined in the HTTP adapter. The database mapping is defined in the PostgreSQL adapter. If you switch from PostgreSQL to DynamoDB, you change only the adapter—the entity never changes. This is the core value proposition of Clean Architecture, and in Go it is enforced at the package level by the compiler.

### The Repository Interface

In Clean Architecture, the use case layer defines what it needs, not what exists. The repository interface is defined in the use case package (or sometimes in a `domain/repository` package), NOT in the PostgreSQL adapter:

```go
// usecases/create_order.go
package usecases

import "context"

type OrderRepository interface {
    Save(ctx context.Context, order *Order) error
    FindByID(ctx context.Context, id string) (*Order, error)
    FindByUserID(ctx context.Context, userID string) ([]*Order, error)
}
```

This is Go-idiomatic: define interfaces where they are consumed, not where they are implemented. The PostgreSQL adapter in `adapters/outbound/postgres/order_repo.go` simply implements this interface without declaring that it does so. The compiler verifies at the DI (dependency injection) site—typically `main.go` or a wire-like tool—that the concrete type satisfies the interface.

### The Use Case (Interactor) Pattern

The use case is a single struct with a single exported method. All dependencies are injected via the constructor. This pattern is sometimes called the "Interactor" pattern from Clean Architecture literature:

```go
// usecases/create_order.go
package usecases

import (
    "context"
    "fmt"

    "github.com/example/payment/domain/entities"
)

type CreateOrderUseCase struct {
    orderRepo    OrderRepository
    productRepo  ProductRepository
    paymentGateway PaymentGateway
    eventBus     EventBus
}

func NewCreateOrderUseCase(
    orderRepo OrderRepository,
    productRepo ProductRepository,
    paymentGateway PaymentGateway,
    eventBus EventBus,
) *CreateOrderUseCase {
    return &CreateOrderUseCase{
        orderRepo:    orderRepo,
        productRepo:  productRepo,
        paymentGateway: paymentGateway,
        eventBus:     eventBus,
    }
}

type CreateOrderInput struct {
    UserID string
    Items  []OrderItemInput
}

type OrderItemInput struct {
    ProductID string
    Quantity  int
}

type CreateOrderOutput struct {
    OrderID string
    Status  entities.OrderStatus
}

func (uc *CreateOrderUseCase) Execute(
    ctx context.Context,
    input CreateOrderInput,
) (*CreateOrderOutput, error) {
    // 1. Validate business rules
    if len(input.Items) == 0 {
        return nil, fmt.Errorf("order must have at least one item")
    }
    // 2. Load domain data
    products, err := uc.productRepo.FindByIDs(ctx, productIDs(input.Items))
    if err != nil {
        return nil, fmt.Errorf("loading products: %w", err)
    }
    // 3. Build domain entity
    order := uc.buildOrder(input, products)
    // 4. Persist
    if err := uc.orderRepo.Save(ctx, order); err != nil {
        return nil, fmt.Errorf("saving order: %w", err)
    }
    // 5. Emit event
    uc.eventBus.Publish(ctx, OrderCreatedEvent{OrderID: order.ID})
    return &CreateOrderOutput{OrderID: order.ID, Status: order.Status}, nil
}
```

Key design decisions visible here: the `Execute` method takes `context.Context` as the first argument (Go idiom), returns a concrete output type (not `interface{}`), uses `fmt.Errorf` with `%w` for error wrapping (Go 1.13+), and never references HTTP concepts (no `*http.Request`, no status codes). This means the same use case can be called from an HTTP handler, a gRPC handler, a CLI command, or a test—all without modification.

### The HTTP Adapter (Inbound)

The HTTP adapter translates between the web and the use case:

```go
// adapters/inbound/http/order_handler.go
package http

import (
    "encoding/json"
    "net/http"

    "github.com/example/payment/usecases"
    "github.com/go-chi/chi/v5"
)

type OrderHandler struct {
    createOrder *usecases.CreateOrderUseCase
}

func NewOrderHandler(createOrder *usecases.CreateOrderUseCase) *OrderHandler {
    return &OrderHandler{createOrder: createOrder}
}

func (h *OrderHandler) RegisterRoutes(r chi.Router) {
    r.Post("/api/v1/orders", h.CreateOrder)
}

func (h *OrderHandler) CreateOrder(w http.ResponseWriter, r *http.Request) {
    var req struct {
        UserID string                `json:"user_id"`
        Items  []usecases.OrderItemInput `json:"items"`
    }
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        http.Error(w, `{"error":"invalid json"}`, http.StatusBadRequest)
        return
    }
    output, err := h.createOrder.Execute(r.Context(), usecases.CreateOrderInput{
        UserID: req.UserID,
        Items:  req.Items,
    })
    if err != nil {
        http.Error(w, `{"error":"`+err.Error()+`"}`, http.StatusInternalServerError)
        return
    }
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusCreated)
    json.NewEncoder(w).Encode(output)
}
```

The handler is thin—translation only. All business logic lives in the use case. This is testable: you can test the handler with a mock use case, and the use case with mock repositories, independently.

### The PostgreSQL Adapter (Outbound)

```go
// adapters/outbound/postgres/order_repo.go
package postgres

import (
    "context"
    "database/sql"

    "github.com/example/payment/domain/entities"
)

type OrderRepository struct {
    db *sql.DB
}

func NewOrderRepository(db *sql.DB) *OrderRepository {
    return &OrderRepository{db: db}
}

func (r *OrderRepository) Save(ctx context.Context, order *entities.Order) error {
    tx, err := r.db.BeginTx(ctx, nil)
    if err != nil {
        return err
    }
    defer tx.Rollback()
    _, err = tx.ExecContext(ctx,
        `INSERT INTO orders (id, user_id, status, total_amount, total_currency, created_at)
         VALUES ($1, $2, $3, $4, $5, $6)`,
        order.ID, order.UserID, order.Status,
        order.Total.Amount, order.Total.Currency, order.CreatedAt,
    )
    if err != nil {
        return err
    }
    for _, item := range order.Items {
        _, err = tx.ExecContext(ctx,
            `INSERT INTO order_items (order_id, product_id, quantity, unit_price_amount, unit_price_currency)
             VALUES ($1, $2, $3, $4, $5)`,
            order.ID, item.ProductID, item.Quantity,
            item.UnitPrice.Amount, item.UnitPrice.Currency,
        )
        if err != nil {
            return err
        }
    }
    return tx.Commit()
}
```

This adapter knows about PostgreSQL (transactions, SQL queries, `database/sql`) but does not know about HTTP, Chi, or any other delivery mechanism. It implements the `OrderRepository` interface implicitly—Go's structural typing means no `implements` keyword is needed.

### Wire-Up in main.go

```go
func main() {
    db := mustConnectDB(os.Getenv("DATABASE_URL"))
    orderRepo := postgres.NewOrderRepository(db)
    productRepo := postgres.NewProductRepository(db)
    paymentGateway := stripe.NewPaymentGateway(os.Getenv("STRIPE_KEY"))
    eventBus := inmemory.NewEventBus()

    createOrderUC := usecases.NewCreateOrderUseCase(
        orderRepo, productRepo, paymentGateway, eventBus,
    )
    orderHandler := httpadapter.NewOrderHandler(createOrderUC)

    r := chi.NewRouter()
    r.Use(middleware.Logger)
    r.Use(middleware.RequestID)
    orderHandler.RegisterRoutes(r)
    http.ListenAndServe(":8080", r)
}
```

This is manual DI. For larger systems, Google Wire (`github.com/google/wire`) generates this code from provider functions, eliminating the manual wiring while preserving compile-time safety.

### Vertical Slice Architecture Implementation

A vertical slice co-locates everything for a feature. Here is `features/orders/create_order.go`:

```go
// features/orders/create_order.go
package orders

import (
    "context"
    "database/sql"
    "encoding/json"
    "net/http"

    "github.com/go-chi/chi/v5"
)

type CreateOrderFeature struct {
    db *sql.DB
}

func NewCreateOrderFeature(db *sql.DB) *CreateOrderFeature {
    return &CreateOrderFeature{db: db}
}

func (f *CreateOrderFeature) RegisterRoutes(r chi.Router) {
    r.Post("/api/v1/orders", f.handle)
}

func (f *CreateOrderFeature) handle(w http.ResponseWriter, r *http.Request) {
    var req CreateOrderRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        respondError(w, http.StatusBadRequest, "invalid json")
        return
    }
    order, err := f.execute(r.Context(), req)
    if err != nil {
        respondError(w, http.StatusInternalServerError, err.Error())
        return
    }
    respondJSON(w, http.StatusCreated, order)
}

type CreateOrderRequest struct {
    UserID string          `json:"user_id"`
    Items  []OrderItemReq  `json:"items"`
}

type OrderItemReq struct {
    ProductID string `json:"product_id"`
    Quantity  int    `json:"quantity"`
}

type CreateOrderResponse struct {
    OrderID string `json:"order_id"`
    Status  string `json:"status"`
}

func (f *CreateOrderFeature) execute(ctx context.Context, req CreateOrderRequest) (*CreateOrderResponse, error) {
    if len(req.Items) == 0 {
        return nil, errEmptyOrder
    }
    tx, err := f.db.BeginTx(ctx, nil)
    if err != nil {
        return nil, err
    }
    defer tx.Rollback()
    orderID := generateOrderID()
    _, err = tx.ExecContext(ctx,
        `INSERT INTO orders (id, user_id, status) VALUES ($1, $2, 'pending')`,
        orderID, req.UserID,
    )
    if err != nil {
        return nil, err
    }
    for _, item := range req.Items {
        _, err = tx.ExecContext(ctx,
            `INSERT INTO order_items (order_id, product_id, quantity) VALUES ($1, $2, $3)`,
            orderID, item.ProductID, item.Quantity,
        )
        if err != nil {
            return nil, err
        }
    }
    if err := tx.Commit(); err != nil {
        return nil, err
    }
    return &CreateOrderResponse{OrderID: orderID, Status: "pending"}, nil
}
```

The differences from Clean Architecture: the SQL queries, business validation, HTTP handling, and type definitions are all in one file. The cost: if `get_order.go` needs the same `OrderItem` type, you either duplicate it or extract a shared `types.go`. The benefit: understanding a feature requires reading one file, not five. For teams of 3-5 engineers building a bounded-context service with 15-30 endpoints, this is often the right call.

### Package Cycle Prevention in Go

Go's compiler enforces that the import graph is a directed acyclic graph. If `package A` imports `package B`, then `package B` cannot import `package A` (directly or transitively). This means:

- In Clean Architecture: `adapters/inbound/http` imports `usecases`, but `usecases` never imports `adapters/inbound/http`. The interface is defined in `usecases` and implemented in `adapters/outbound/postgres`. No cycle.
- A common mistake in Vertical Slice: `features/orders/create_order.go` imports `shared/validation`, and `shared/validation` later imports `features/orders` because someone added an order-specific validator. The compiler rejects this. Fix: move the specific validator into the `features/orders` package and keep `shared/validation` truly generic.

The DAG property means your architecture diagram and your import graph should match. Use `go mod graph` and `go vet` to detect structural issues early.

### Comparison: Hexagonal vs Clean vs Vertical Slice in Go

- **Hexagonal (Ports & Adapters)**: Emphasizes the "port" (interface) between the application core and the outside world. The application core has "driving" ports (use cases the outside world calls) and "driven" ports (interfaces to infrastructure). In Go, ports are just `interface` types. Works well for services with multiple inbound/outbound adapters (HTTP + gRPC + CLI inbound; PostgreSQL + Redis + S3 outbound).
- **Clean Architecture**: Adds the concept of "use cases" and "entities" as distinct layers within the core. More prescriptive about internal structure. Better for complex business logic where the distinction between "what the system does" (use case) and "what the system is" (entity) matters.
- **Vertical Slice**: Abandons layer separation in favor of feature cohesion. All code for a feature lives together. Best for teams organized around features, with moderate complexity, where the cost of cross-file navigation exceeds the benefit of layer isolation.
- **Layered (classic MVC)**: `handlers/`, `services/`, `repos/` directories. Simple, well-understood, but tends toward the "lasagna architecture" anti-pattern where abstractions proliferate without adding value.

## Runtime Behavior

### Clean Architecture Request Flow at Runtime

1. **Chi router receives HTTP request**: `POST /api/v1/orders`. Chi middleware stack runs: RequestID injects a request ID into the context, Logger logs the start time, Recoverer catches panics.
2. **Route matched**: Chi matches the registered route and calls `OrderHandler.CreateOrder(w, r)`.
3. **Request deserialization**: `json.NewDecoder(r.Body).Decode(&req)` parses the JSON body into a handler-local request struct. This struct is HTTP-specific and is never seen by the use case.
4. **Adapter translation**: The handler constructs a `usecases.CreateOrderInput` from the HTTP request data. This is the boundary crossing—no HTTP concepts cross into the use case.
5. **Use case execution**: `CreateOrderUseCase.Execute(ctx, input)` runs. The use case owns the transaction boundary: it may call multiple repository methods within a single logical operation. The use case does not manage database transactions directly—either the repository handles that, or a unit-of-work pattern wraps the use case.
6. **Repository calls**: The use case calls `orderRepo.Save(ctx, order)`. The Go runtime dispatches this to the concrete `OrderRepository.Save` method in `adapters/outbound/postgres`. The repository has a `*sql.DB` reference and executes SQL queries through it.
7. **Response construction**: The use case returns a `CreateOrderOutput`, which the handler converts to JSON and writes via `ResponseWriter`. The `Content-Type` header is set to `application/json`.
8. **Middleware post-processing**: After the handler returns, Chi middleware runs cleanup: Logger records the status code and duration, RequestID adds the X-Request-Id response header.

### What the Go Runtime Is Doing During This Flow

- Goroutine: each request runs in its own goroutine (Chi inherits this from `net/http`). The goroutine stack starts small (~2KB) and grows/shrinks as needed.
- Context propagation: `r.Context()` is a `*http.Request`'s context, which is derived from the server's base context (set via `Server.BaseContext`). When the HTTP connection dies (client disconnects, timeout), the context is canceled. Code that checks `ctx.Err() != nil` or uses `ctx.Done()` will react to this.
- Garbage collection: the handler-local request struct, the use case input/output structs, and any intermediate allocations are eligible for GC once the handler function returns. Stack-allocated values (small structs, typically < ~64 bytes) avoid heap allocation entirely via Go's escape analysis.
- Database connection pool: `*sql.DB` manages a pool of connections. When the repository calls `db.ExecContext(ctx, ...)`, it acquires a connection from the pool, executes the query, and returns the connection. If the context is canceled mid-query, `database/sql` aborts the query (via PostgreSQL's `pg_cancel_backend` or MySQL's KILL QUERY).

### Vertical Slice Runtime Flow

The runtime flow is identical—Chi still handles routing and middleware, the handler still deserializes JSON, and the database is still accessed via `*sql.DB`. The difference is purely structural: in Vertical Slice, the same goroutine executes the HTTP translation, business validation, and SQL query without crossing package boundaries. This means:

- Fewer heap allocations (no intermediate structs crossing package boundaries)
- Simpler stack traces (fewer nested function calls across packages)
- Harder to mock individual components for testing (the entire feature is tested as a unit)

### Context Cancellation and Clean Architecture

A critical runtime concern: if the HTTP client disconnects (`curl` is killed, browser tab closed), `r.Context()` is canceled. In Clean Architecture, the context is passed from the handler through the use case to the repository. If the repository uses `ExecContext(ctx, ...)`, the database driver will cancel the in-flight query. This prevents wasted database work. In Vertical Slice, the same propagation happens, but because there are fewer layers, it is harder to forget to pass the context through.

### Go's Escape Analysis and Architecture Choice

Go's compiler performs escape analysis to decide whether a variable is allocated on the stack or heap. When you pass a struct across package boundaries, the compiler has less visibility and is more likely to heap-allocate. In Clean Architecture, each layer boundary involves copying or passing structs between packages, which can increase heap allocations. In Vertical Slice, because everything lives in one function or package, the compiler can often keep more values on the stack. This is a micro-optimization that matters at scale: at 10,000 requests/second, a 5% reduction in heap allocations from better escape analysis translates to lower GC pause times.

## Flow Diagrams

```
HTTP Request Flow in Clean Architecture (Go/Chi):

Client                    Chi Router              Handler                 UseCase              Repository           PostgreSQL
  │                          │                      │                       │                      │                    │
  │  POST /api/v1/orders     │                      │                       │                      │                    │
  │─────────────────────────>│                      │                       │                      │                    │
  │                          │                      │                       │                      │                    │
  │                          │  middleware stack    │                       │                      │                    │
  │                          │───── RequestID ─────>│                       │                      │                    │
  │                          │───── Logger ────────>│                       │                      │                    │
  │                          │───── Recoverer ─────>│                       │                      │                    │
  │                          │                      │                       │                      │                    │
  │                          │  route match         │                       │                      │                    │
  │                          │─────────────────────>│ CreateOrder(w, r)     │                      │                    │
  │                          │                      │                       │                      │                    │
  │                          │                      │ json.Decode(r.Body)   │                      │                    │
  │                          │                      │──────── mapping ──────│                      │                    │
  │                          │                      │                       │                      │                    │
  │                          │                      │  Execute(ctx, input)  │                      │                    │
  │                          │                      │──────────────────────>│                      │                    │
  │                          │                      │                       │                      │                    │
  │                          │                      │                       │ Validate(input)      │                    │
  │                          │                      │                       │────── business ──────│                    │
  │                          │                      │                       │     rules check      │                    │
  │                          │                      │                       │                      │                    │
  │                          │                      │                       │ FindByIDs(ctx, ids)  │                    │
  │                          │                      │                       │─────────────────────>│ SELECT * FROM      │
  │                          │                      │                       │                      │───────────────────>│
  │                          │                      │                       │                      │   products WHERE.. │
  │                          │                      │                       │                      │<───────────────────│
  │                          │                      │                       │<─────────────────────│   rows returned    │
  │                          │                      │                       │                      │                    │
  │                          │                      │                       │ buildOrder(items)    │                    │
  │                          │                      │                       │────── domain ────────│                    │
  │                          │                      │                       │     entity creation  │                    │
  │                          │                      │                       │                      │                    │
  │                          │                      │                       │ Save(ctx, order)     │                    │
  │                          │                      │                       │─────────────────────>│ BEGIN; INSERT INTO │
  │                          │                      │                       │                      │───────────────────>│
  │                          │                      │                       │                      │   orders (...)     │
  │                          │                      │                       │                      │<───────────────────│
  │                          │                      │                       │<─────────────────────│   COMMIT; ok       │
  │                          │                      │                       │                      │                    │
  │                          │                      │                       │ Publish(event)       │                    │
  │                          │                      │                       │────── event bus ─────│                    │
  │                          │                      │                       │                      │                    │
  │                          │                      │<──────────────────────│ CreateOrderOutput    │                    │
  │                          │                      │                       │                      │                    │
  │                          │                      │ json.Encode(output)   │                      │                    │
  │                          │                      │ writeHeader(201)      │                      │                    │
  │                          │                      │                       │                      │                    │
  │  HTTP 201 Created        │<─────────────────────│                       │                      │                    │
  │<─────────────────────────│                      │                       │                      │                    │
  │                          │                      │                       │                      │                    │
```

```
Architecture Evolution Flow:

  ┌──────────────┐     ┌──────────────┐     ┌──────────────────┐     ┌────────────────┐     ┌───────────────────┐
  │ Single File  │────>│ Layered      │────>│ Feature Packages │────>│ Modular        │────>│ Microservices     │
  │ main.go      │     │ handlers/    │     │ features/orders/ │     │ Monolith       │     │ orders-svc        │
  │ (200 lines)  │     │ services/    │     │ features/pay/    │     │ cmd/orders/    │     │ payments-svc      │
  │              │     │ repos/       │     │ features/users/  │     │ cmd/payments/  │     │ users-svc         │
  └──────────────┘     └──────────────┘     └──────────────────┘     └────────────────┘     └───────────────────┘
       ▲                      ▲                       ▲                       ▲                       ▲
       │                      │                       │                       │                       │
  Trigger:              Trigger:               Trigger:               Trigger:               Trigger:
  1 dev, MVP           >3 devs, need          >10 features,           >5 teams,              >50 devs,
                       to find code           cross-feature           independent            independent
                       by role                conflicts rising        deploy needed          scaling needed
```

## Source Code Reading Guide

Read these files in this order:

1. **`github.com/go-chi/chi/v5/mux.go`** — Lines 1-50 (package doc, Mux struct). Understand that Chi embeds a `sync.Mutex` and maintains a tree of `node` structs for route matching. Skip the `node` implementation details (lines 200+) unless you need to understand route conflict detection.
2. **Your project's `usecases/create_order.go`** — Focus on the interface definitions at the top of the file. Notice that interfaces are small (1-3 methods each) and defined in the package that uses them. Compare with the Java/C# approach of large repository interfaces in separate files.
3. **`database/sql/sql.go`** — Read the `DB` struct definition (around line 250-320). Understand `connector`, `freeConn`, `connectionRequest`. This is the connection pool your repository uses. Skip the driver interface unless you're writing a database driver.
4. **`context/context.go`** in Go standard library — All 500 lines. This is essential context for understanding how `r.Context()` flows through your Clean Architecture layers. Pay attention to `valueCtx` (lines ~70-110), `cancelCtx` (~120-180), and `WithCancel` (~200-250).
5. **`net/http/server.go`** — Read the `Server.Serve` method (~lines 3000-3100 in Go 1.22) and `conn.serve` (~lines 1800-2000). This is where the goroutine-per-connection model happens, which is the foundation of how your handler code executes.
6. **`encoding/json/stream.go`** — Read `NewDecoder` and `Decode`. Understand that `json.NewDecoder(r.Body).Decode(&v)` streams from the `io.ReadCloser` without buffering the entire body in memory. For large request bodies, this matters.

What to skip:
- Chi's `node` tree implementation details (compressed radix tree). You do not need to understand the tree structure unless you are contributing to Chi.
- `net/http`'s HTTP/2 implementation details (`h2_bundle.go`). Valuable but a separate deep-dive.
- SQL driver implementations (`lib/pq`, `pgx`). Understand the interface, not the driver internals.
- Wire/fx DI tool internals. Understand the generated code pattern, not the code generation logic.

## Production Failure Scenarios

### Scenario 1: Interface Defined in Wrong Package Causes Import Cycle

**Cause**: A developer defines the `OrderRepository` interface in `adapters/outbound/postgres/` alongside the concrete implementation. Later, a new package `services/notifications/` needs to call `FindByID` to send an order confirmation email. The `notifications` package imports `postgres` for the interface, but `postgres` later needs something from `notifications` (e.g., a notification type). Go compiler says: `import cycle not allowed`.

**Symptom**: Build fails. Error message is clear: `package github.com/example/payment imports github.com/example/payment/adapters/outbound/postgres imports github.com/example/payment/services/notifications imports github.com/example/payment/adapters/outbound/postgres: import cycle not allowed`. The developer tries to work around it by moving types to a `common/` package, which becomes a dumping ground for everything.

**Fix**: Move the `OrderRepository` interface to the package that consumes it—either `usecases/` or a dedicated `domain/repository/` package. The rule: interfaces are owned by the consumer, not the implementer. Refactor: extract the interface to the use case package, update all imports, delete the interface from the PostgreSQL adapter. Commit and rebuild.

### Scenario 2: Use Case Performs HTTP-Specific Error Handling

**Cause**: A developer under pressure adds HTTP-specific logic to a use case. The `CreateOrderUseCase.Execute` method returns `(int, interface{}, error)` where `int` is the HTTP status code. When the order service is later called from a gRPC handler, the gRPC handler has no idea what to do with HTTP 422 vs HTTP 409. The use case has leaked HTTP concerns.

**Symptom**: The gRPC service returns `UNKNOWN` for all errors because the status code mapping is meaningless. A new developer adds a Kafka consumer that also calls `CreateOrderUseCase.Execute` and now has to map HTTP status codes to Kafka retry policies. Error handling becomes a tangle of adapter-specific switches.

**Fix**: Define domain-specific error types in the use case or domain package. Each use case returns errors like `ErrOrderValidation`, `ErrProductNotFound`, `ErrInsufficientStock`. Each adapter maps these domain errors to its own error protocol: HTTP handler maps to status codes, gRPC handler maps to gRPC status codes, Kafka consumer maps to retry/nack. The use case never knows about HTTP or gRPC. Example:

```go
// domain/errors.go
var (
    ErrOrderValidation   = errors.New("order validation failed")
    ErrProductNotFound   = errors.New("product not found")
    ErrInsufficientStock = errors.New("insufficient stock")
)

// adapters/inbound/http/order_handler.go
func (h *OrderHandler) CreateOrder(w http.ResponseWriter, r *http.Request) {
    output, err := h.createOrder.Execute(r.Context(), input)
    if err != nil {
        switch {
        case errors.Is(err, domain.ErrOrderValidation):
            http.Error(w, err.Error(), http.StatusUnprocessableEntity)
        case errors.Is(err, domain.ErrProductNotFound):
            http.Error(w, err.Error(), http.StatusNotFound)
        default:
            http.Error(w, "internal error", http.StatusInternalServerError)
        }
        return
    }
    // ...
}
```

### Scenario 3: Vertical Slice Duplication Causes Divergent Behavior

**Cause**: A Vertical Slice project has `features/orders/create_order.go` and `features/orders/bulk_create.go` that both implement order validation. Over 6 months, a developer fixes a validation bug in `create_order.go` but does not realize `bulk_create.go` has the same logic copied and pasted. Production runs with inconsistent validation: single orders allow max 10 items, bulk orders allow max 50 items because the limit was only updated in one place.

**Symptom**: Customer support tickets arrive: "Why can I order 50 items in bulk but only 10 one at a time?" The inconsistency is confusing to users and support staff. The codebase has two different `maxItemsPerOrder` constants in two different files. A grep for `maxItemsPerOrder` returns two results, and the values differ.

**Fix**: Extract shared business rules to a `features/orders/rules.go` or `features/orders/validation.go` file within the same package. All feature files in the `orders` package import from the same validation. This preserves Vertical Slice cohesion (everything in `features/orders/`) while eliminating duplication. The rule of thumb: if the same constant, function, or type appears in 3+ files within the same feature package, extract it to a shared file in that package.

## Debugging Techniques

### Technique 1: Trace an Inter-Layer Call with Delve

When a use case is returning unexpected results, trace the full call path:

```bash
# Start delve with the binary
dlv debug ./cmd/server

# Set breakpoints at each layer boundary
(dlv) b adapters/inbound/http/order_handler.go:68   # handler entry
(dlv) b usecases/create_order.go:95                  # use case entry
(dlv) b adapters/outbound/postgres/order_repo.go:42   # repo Save
(dlv) b adapters/outbound/postgres/order_repo.go:85   # repo FindByID

# Run until first breakpoint
(dlv) c

# At each breakpoint, inspect the data crossing the boundary
(dlv) p req          # handler sees HTTP-specific struct
(dlv) p input        # use case sees domain-specific struct
(dlv) p order        # repo sees domain entity

# Check context propagation
(dlv) p ctx          # same context? same request ID?
(dlv) p ctx.Value(middleware.RequestIDKey)
```

Key insight: at each layer boundary, verify that the data structure changes (HTTP types in handler, domain types in use case, SQL-annotated types in repository) and that the context value chain is intact.

### Technique 2: Visualize Import Graph for Cycle Detection

```bash
# Generate dependency graph
go mod graph | grep "github.com/example/payment" > deps.txt

# Find all packages in your module
go list ./... > packages.txt

# Check for import cycles (go vet catches these)
go vet ./...

# Install and use gocyclo for visual graph
go install github.com/loov/goda@latest
goda graph "./..." | dot -Tpng -o deps.png
```

The generated PNG shows arrows representing imports. If you see a cycle, the architecture has a violation. In Clean Architecture, all arrows should point inward (toward `domain/`). In Vertical Slice, arrows should flow from `features/*/` to `shared/` and never back.

### Technique 3: Verify Layer Isolation with Compiler Tricks

A quick test that your use case layer has no HTTP dependencies:

```bash
# Create a temporary file that imports only the use case package
cat > /tmp/check_isolation.go << 'EOF'
package main

import _ "github.com/example/payment/usecases"

func main() {}
EOF

# Build it - it should not pull in net/http or chi
go build -o /dev/null /tmp/check_isolation.go
```

If this build succeeds and you can verify with `go tool nm` that no HTTP symbols are linked, your use case layer is clean. If you see `net/http` symbols in the binary, something in the use case package (or one of its transitive dependencies) imports HTTP packages.

## Observability Considerations

### What to Log

Log at architectural boundaries, not inside them:

1. **Inbound adapter (handler entry/exit)**: Log request method, path, request ID, and final status code. Use Chi's built-in `middleware.Logger` or a custom middleware that adds structured logging (slog/slog fields). Do not log request bodies (PII, payload size).

2. **Use case execution**: Log use case name, input summary (user ID, not full payload), duration, and outcome (success/error). If the use case fails, log the error with full context. Use `slog.Info("usecase completed", "usecase", "CreateOrder", "duration_ms", d, "status", status)`.

3. **Repository calls**: Log query name, duration, and row count. Do not log SQL queries with parameters (PII risk, log volume). Use `slog.Debug` for full query logging, only enabled in development.

4. **External service calls**: Log service name, method, latency, and status. Include the request ID for cross-service correlation. If the call fails, log the full error.

### What Metrics

1. **Request rate by use case**: Counter per use case (`create_order_total`, `process_payment_total`). Helps identify which features are most used and which are dead code.
2. **Use case latency histogram**: `create_order_duration_seconds` with buckets {0.01, 0.05, 0.1, 0.5, 1, 5}. Helps identify slow features and set SLOs.
3. **Repository call latency**: `postgres_query_duration_seconds` per query type (SELECT, INSERT, UPDATE). Helps identify missing indexes or connection pool issues.
4. **External dependency health**: `payment_gateway_errors_total`, circuit breaker state gauge. Helps identify downstream failures before users report them.
5. **Architecture boundary errors**: Counter for domain errors vs infrastructure errors. `order_errors_total{type="validation"}` vs `order_errors_total{type="database"}`. Helps identify whether bugs are in business logic or infrastructure.

### What Traces

1. **Span per architectural boundary**: One span for the HTTP handler, one span for the use case, one span for each repository call. Each span has `parent_id` linking upward. Use OpenTelemetry's `go.opentelemetry.io/otel` SDK.
2. **Attributes on use case span**: `usecase.name`, `user.id`, `order.id`, `outcome`. Do not put full request payloads in spans (attribute size limits).
3. **Context propagation**: The `trace_id` must be in the `context.Context` and must propagate across all layer boundaries. If you see broken traces (spans without parents), context propagation has failed at a boundary.
4. **External calls**: Inject trace context into outgoing HTTP/gRPC headers so the downstream service can continue the trace. Use W3C traceparent format.

## Performance Implications

### Concern 1: Allocation Overhead at Layer Boundaries

Each time data crosses a layer boundary in Clean Architecture, it is typically copied into a new struct: HTTP request struct → use case input struct → domain entity → repository DTO. Each allocation adds GC pressure. At 10,000 RPS, this can be noticeable.

Mitigation:
- Use value types (structs, not pointers) for small DTOs. Go's escape analysis may keep them on the stack.
- Reuse response buffers with `sync.Pool` for frequently allocated types.
- Profile with `go test -bench=. -benchmem` to measure allocations per operation.
- Consider whether `CreateOrderInput` and `CreateOrderRequest` can be the same type. If yes, skip one allocation.

### Concern 2: Interface Dispatch Cost

Every method call through an interface has a small cost: the runtime must look up the concrete method in the interface's itable. For repository calls that go to a database (network I/O latency ~1-5ms), this cost is negligible. For in-memory operations called in a tight loop, it adds up.

Mitigation:
- In performance-critical paths (e.g., order matching engine, real-time fraud detection), consider bypassing the repository interface and calling the concrete implementation directly. This violates Clean Architecture purity but may be necessary.
- The Go compiler can devirtualize some interface calls if it can prove the concrete type at compile time. Keep interface method sets small (1-3 methods) to help the compiler.

### Concern 3: Vertical Slice's SQL N+1 Problem

Vertical Slice encourages inline SQL. A developer building `features/orders/list_orders.go` writes a loop that calls `db.QueryRowContext` for each order to fetch its items (N+1 pattern). In Clean Architecture, the repository abstraction makes the N+1 pattern visible as multiple repository method calls, prompting a batch load.

Mitigation:
- In Vertical Slice, review SQL queries for N+1 patterns during code review. Use `pg_stat_statements` or slow query logs to detect repeated identical queries within a short time window.
- Write a JOIN query in the feature file instead of looping. Treat the feature file as a "transaction script" that can contain any SQL quality, but enforce quality through review.

## Architecture Implications

Clean Architecture fundamentally changes how your team reasons about change. When a business stakeholder says "we need to change how orders are validated—now we require phone verification for orders over $500," a developer using Clean Architecture knows exactly where to go: `usecases/create_order.go`, the `Execute` method, the validation section. They do not need to search through HTTP handlers, middleware, or database triggers. The change is isolated to one function in one file. This predictability reduces the cognitive load of maintenance and makes onboarding faster: new developers learn that business rules live in `usecases/`, period.

The cost is that Clean Architecture introduces a non-trivial amount of ceremony. Every new use case requires: defining input/output types, defining the use case struct and constructor, defining interfaces for any new repository methods, implementing those interfaces, wiring everything in `main.go` (or Wire provider sets), and writing separate tests for each layer. For a team building a greenfield product with rapidly changing requirements, this ceremony slows down iteration. The right approach is often: start with Vertical Slice, extract to Clean Architecture when the feature set stabilizes and testability becomes more important than iteration speed. This is not technical debt—it is intentional, strategic simplicity that you evolve when the cost of not having Clean Architecture exceeds the cost of implementing it.

Clean Architecture also changes how you think about testing. With properly isolated layers, you can test business logic without a database (using mock repositories), test HTTP handlers without business logic (using mock use cases), and test database queries without HTTP or business logic (using testcontainers or a real test database). Each test is fast, focused, and easy to debug. The alternative—integration tests that spin up a full server, database, and external services—is brittle and slow. A staff engineer should be able to articulate the testing strategy that Clean Architecture enables and the tradeoffs involved.

## Team Ownership Implications

In Clean Architecture with feature-based teams, ownership boundaries become blurred. The `orders` feature team owns `usecases/create_order.go`, but also needs to modify `adapters/outbound/postgres/order_repo.go` when the database schema changes. The `postgres` package is also used by the `payments` and `users` teams. This creates merge conflicts and coordination overhead. One solution is CODEOWNERS (GitHub) or ownership rules in the repository: the `adapters/outbound/postgres/` directory is owned by the platform/infrastructure team, and feature teams contribute to it through PRs. The platform team provides SDK-like interfaces (well-documented repository patterns, schema migration tooling, connection pool defaults) that feature teams consume.

In Vertical Slice, ownership is cleaner: each team owns its `features/<team>/` directory entirely. The `features/orders/` directory is owned by the orders team, and they can change anything in it without coordinating with other teams. The cost is that `shared/` becomes a coordination point: when the orders team wants to change the database connection configuration or add a new middleware, they need buy-in from the platform team. This tension between feature autonomy and shared infrastructure is the central organizational challenge of microservices and modular monoliths alike.

## Interview Questions

### Q1: "Explain how Go's package system enforces the Clean Architecture dependency rule, and how this differs from Java or C#."

**Answer**: Go's compiler enforces a DAG (directed acyclic graph) on imports. If package A imports package B, then B cannot import A—the compiler rejects the build. This maps directly onto Clean Architecture's rule that source code dependencies point inward. In Java or C#, you can create circular dependencies between packages in the same module; the compiler allows it and it's a runtime concern (ClassNotFoundException or stack overflow from circular initialization).

In Go, the enforcement is physical: you define the `OrderRepository` interface in `usecases/` (inner layer), and the PostgreSQL adapter in `adapters/outbound/postgres/` (outer layer) implements it. `usecases/` never imports `adapters/`, so the dependency direction is correct. If someone tries to put the interface in the adapter package and import it from the use case, the compiler says "import cycle not allowed" as soon as any code in the adapter package needs something from the use case package.

This compile-time enforcement is stronger than convention-based enforcement (e.g., ArchUnit in Java). In Go, you cannot accidentally violate the dependency rule—the code simply won't build. This means Clean Architecture in Go is not a convention; it's a property that emerges from Go's module and import system when you structure your packages correctly.

### Q2: "A use case calls three repositories within a single Execute method. How do you handle transactions? Where does the transaction live?"

**Answer**: The transaction boundary is an architectural decision point. Three common approaches in Go:

1. **Transaction in the use case**: The use case accepts a `*sql.Tx` or a `UnitOfWork` interface that wraps a transaction. All repositories use the same transaction. This violates strict layering (use case knows about database transactions) but is pragmatic for most applications. Example:

```go
type OrderCreationService struct {
    txProvider TxProvider // returns *sql.Tx
}
func (s *OrderCreationService) CreateOrder(ctx context.Context, input Input) error {
    tx, _ := s.txProvider.Begin(ctx)
    defer tx.Rollback()
    orderRepo.SaveWithTx(tx, order)     // repository takes *sql.Tx
    inventoryRepo.DeductWithTx(tx, items)
    paymentRepo.RecordWithTx(tx, payment)
    return tx.Commit()
}
```

2. **Unit of Work pattern**: An `OrderUnitOfWork` struct that wraps a transaction and provides access to repositories that are scoped to that transaction. The use case calls methods on the UoW rather than individual repositories. This keeps transaction management out of the use case while still allowing multi-repository atomicity.

3. **Repository-managed transaction**: Each repository manages its own transaction internally. This is architecturally pure but cannot guarantee atomicity across repositories. If `orderRepo.Save()` succeeds and `inventoryRepo.Deduct()` fails, the order is saved without inventory deduction. This is acceptable only when eventual consistency is the design goal (e.g., using sagas or outbox patterns).

The right answer for most Go services is approach 1 or 2. Approach 3 requires distributed transaction patterns (saga, outbox) which are significant architectural commitments. A staff engineer should know all three and the tradeoffs of each.

### Q3: "When is Clean Architecture overkill for a Go service? Provide specific triggers."

**Answer**: Clean Architecture is overkill when:

1. **Single bounded context with < 10 use cases**: A CRUD API for a `products` table with GET/POST/PUT/DELETE has no complex business logic. Clean Architecture adds 4-5 packages for what could be 200 lines in a single file. Trigger: the most complex business rule is "price must be positive."

2. **Team size < 3 developers**: With 1-2 developers, the primary value of Clean Architecture (isolating changes so multiple developers can work in parallel) doesn't apply. The ceremony of creating new use cases slows down the solo developer. Trigger: you can fit the entire codebase in your head.

3. **Rapid prototyping phase**: When you are validating a product idea and the code will be rewritten in 3 months, Clean Architecture is premature optimization of code structure. Trigger: the product manager says "we might pivot."

4. **No external dependencies beyond a single database**: If the service only talks to PostgreSQL, there is no adapter diversity to justify the port/adapter pattern. Trigger: the only infra dependency is a database and the only inbound is HTTP.

5. **The domain logic is purely CRUD**: If `CreateOrder` = validate input + INSERT INTO orders + return ID, there is no domain logic to protect with layering. Trigger: all use cases are < 20 lines.

### Q4: "Compare Vertical Slice Architecture to Clean Architecture for a team of 8 split into 3 feature squads."

**Answer**: For 8 developers in 3 squads (e.g., Orders, Payments, Users):

**Vertical Slice wins** when:
- Each squad fully owns 1-2 feature directories (`features/orders/`, `features/payments/`).
- Squads deploy independently (monorepo with targeted builds or separate services).
- Cross-squad coordination is minimal (orders and payments have clear interfaces, e.g., "orders emit OrderCreated events, payments listen").
- The codebase grows to 100+ features; with Clean Architecture, finding "where is the cancel order logic?" requires checking 4 directories.

**Clean Architecture wins** when:
- Multiple squads modify the same use cases (e.g., orders squad and fraud squad both modify order creation logic).
- The domain logic is complex and shared (e.g., tax calculation used by orders, payments, and invoicing).
- The system has multiple inbound/outbound adapters (HTTP + gRPC inbound; PostgreSQL + Redis + Kafka outbound) and you want consistent adapter implementations across squads.
- You need to swap infrastructure (e.g., migrate from PostgreSQL to CockroachDB) without changing business logic.

In practice, hybrid approaches are common: Clean Architecture for the complex domain core, Vertical Slice for simpler features. The `core/` package uses Clean Architecture (entities, use cases, repository interfaces), and `features/` packages are Vertical Slices that call into the core. This gives you the testability of Clean Architecture for complex logic and the cohesion of Vertical Slice for simpler features.

### Q5: "A use case needs to call an external gRPC service. In Clean Architecture, how do you prevent the use case from depending on gRPC-generated code?"

**Answer**: Define an interface in the use case package that represents the external service's capabilities, without referencing gRPC types:

```go
// usecases/create_order.go
package usecases

type PaymentGateway interface {
    Authorize(ctx context.Context, amount Money, paymentMethodID string) (PaymentAuthorization, error)
    Capture(ctx context.Context, authorizationID string) error
}
```

Then implement the interface in the adapter layer using the gRPC-generated client:

```go
// adapters/outbound/paymentgateway/stripe_adapter.go
package paymentgateway

import (
    "context"
    pb "github.com/payments/proto/v1" // gRPC-generated code
    "github.com/example/payment/usecases"
)

type StripeAdapter struct {
    client pb.PaymentServiceClient
}

func (a *StripeAdapter) Authorize(ctx context.Context, amount usecases.Money, methodID string) (usecases.PaymentAuthorization, error) {
    resp, err := a.client.Authorize(ctx, &pb.AuthorizeRequest{
        Amount:          amount.Amount,
        Currency:        amount.Currency,
        PaymentMethodId: methodID,
    })
    if err != nil {
        return usecases.PaymentAuthorization{}, err
    }
    return usecases.PaymentAuthorization{
        ID:    resp.AuthorizationId,
        Status: resp.Status,
    }, nil
}
```

The use case imports only `usecases` and `context`. The adapter imports `usecases` and the gRPC-generated `pb` package. If the payment service changes from gRPC to REST, you replace the adapter. The use case never changes. If the payment service changes its API, you update the adapter's translation logic. If the conceptual capability changes (e.g., you need a `Refund` method), you add it to the `PaymentGateway` interface in the use case package, update all adapters, and update all use cases that call `Refund`. The key insight: the interface represents "what the business needs from a payment gateway," not "what the payment gateway's gRPC API looks like."

## Hands-On Exercises

### Exercise 1: Refactor a Monolithic Handler to Clean Architecture

**Goal**: Take a handler that does everything inline and extract it into Clean Architecture layers.

**Steps**:
1. Start with a single-file handler that receives JSON, validates it, inserts into PostgreSQL, and returns JSON. (~150 lines)
2. Identify the business logic: what rules are being enforced? (e.g., "order total must be > 0", "user must exist", "product must be in stock"). Extract these into a `CreateOrderUseCase.Execute` method.
3. Extract the PostgreSQL queries into repository methods: `FindUserByID`, `FindProductByID`, `SaveOrder`. Define these as interfaces in the use case package.
4. Implement the repository interfaces in a `postgres` package.
5. Create a thin HTTP handler that deserializes JSON, calls the use case, and serializes the response.
6. Write unit tests for the use case with mock repositories. Write integration tests for the repository with a test PostgreSQL instance.
7. Benchmark: `go test -bench=. -benchmem` before and after. How much overhead did the abstractions add?

### Exercise 2: Convert a Clean Architecture Use Case to a Vertical Slice

**Goal**: Experience the tradeoff by converting a use case to a Vertical Slice and comparing.

**Steps**:
1. Take a Clean Architecture implementation of a single use case (handler + use case + repository across 3 files in 3 packages).
2. Create a new file `features/orders/create_order.go` that contains everything in one file: handler, request/response types, validation, SQL queries.
3. Compare the two implementations on: lines of code, number of files touched to make a change, test setup complexity, ability to swap the database.
4. Answer: for this specific use case, which architecture is better? Under what conditions would you switch?

### Exercise 3: Build an Architecture Linter with `go vet` or Custom Analysis

**Goal**: Create tooling that enforces your architecture decisions at CI time.

**Steps**:
1. Define your architecture rules (e.g., `usecases/` must not import `net/http` or `chi`).
2. Write a `go/analysis` pass that checks import statements in each package against allowed/disallowed lists. See `golang.org/x/tools/go/analysis/passes/nilfunc` for a simple example.
3. Alternatively, use `github.com/loov/goda` or `go mod graph` with a shell script that greps for forbidden import patterns and exits non-zero on violations.
4. Add this to your CI pipeline (GitHub Actions, Makefile target). Ensure PRs that violate architecture rules are blocked.
5. Extend: check that all files in `usecases/` follow the naming convention `*_usecase.go` or contain a struct with an `Execute` method.

## Advanced Challenges

### Challenge 1: Implement a Dual-Write Repository with Clean Architecture

**Goal**: Modify a PostgreSQL repository to also write to Redis (cache) and Elasticsearch (search index), without changing the use case.

**Constraints**:
- The use case must not be modified.
- A write failure in Elasticsearch should not fail the PostgreSQL write (cache/index is eventually consistent).
- Redis should be updated synchronously (cache-aside invalidation).

**Approach**: Create a `CompositeOrderRepository` that implements `OrderRepository` and contains three inner repositories: `pgRepo`, `redisRepo`, `esRepo`. The `Save` method writes to PostgreSQL first, then Redis (sync), then publishes a message for async Elasticsearch indexing. The use case sees only the `OrderRepository` interface and has no knowledge of Redis or Elasticsearch.

**Bonus**: Handle the failure mode where PostgreSQL succeeds but Redis update fails. Should the use case return success or failure? Design the composite repository's error handling strategy and explain your reasoning.

### Challenge 2: Design a Migration Path from Vertical Slice to Clean Architecture

**Goal**: Create a strategy and tooling for incrementally migrating 50 Vertical Slice features to Clean Architecture, without a big-bang rewrite.

**Constraints**:
- The service must remain deployable at every step.
- Features should be migrated one at a time.
- During migration, some features are Vertical Slice and some are Clean Architecture, sharing the same `*sql.DB` connection pool and the same Chi router.

**Approach**:
1. Define the target Clean Architecture package structure.
2. Implement the shared infrastructure once: `adapters/outbound/postgres/` with connection pool configuration, health checks, migration runner.
3. For each feature to migrate:
   a. Create the use case (with interfaces).
   b. Create the repository implementation (delegating to the shared PostgreSQL adapter).
   c. Create the thin HTTP handler.
   d. Update `main.go` wiring to use the new handler instead of the Vertical Slice handler.
   e. Run tests, deploy, observe metrics.
   f. Delete the old Vertical Slice code.
4. Track migration progress: 15/50 features migrated. Set a target (e.g., migrate 2 features per sprint).

**Bonus**: Write a code generation tool that reads a Vertical Slice file and generates the Clean Architecture scaffolding (use case skeleton, handler skeleton, repository interface). Describe how the tool determines what to extract.

## Key Insights

- Go's compiler-enforced DAG on imports is the strongest enforcement of Clean Architecture's dependency rule available in any mainstream language. You cannot accidentally create a circular dependency; the build will fail. Use this as a design tool, not just a constraint.
- Interfaces belong to the consumer, not the implementer. In Go, this means defining `OrderRepository` in `usecases/`, not in `adapters/outbound/postgres/`. This is the single most important Go idiom for Clean Architecture.
- Vertical Slice is not "worse" than Clean Architecture; it is optimized for different constraints (fast iteration, small teams, feature cohesion). The sign of a staff engineer is knowing when to use which and how to transition between them without a rewrite.
- The `context.Context` is the backbone of Clean Architecture in Go. It carries request-scoped values (request ID, trace ID, user ID, deadline) across all layer boundaries without coupling layers to HTTP or gRPC specifics. Every function that crosses a boundary must accept `context.Context` as its first parameter.
- Use case structs should have a single exported method (`Execute`). This enforces the Single Responsibility Principle and makes it obvious what dependencies each use case needs. If a struct has 5 methods, it is a service, not a use case, and is probably doing too much.
- Architecture decisions are business decisions. The cost of Clean Architecture (more files, more ceremony, slower feature development) must be justified by the business value (faster onboarding, safer changes, better testability). If your team is 2 developers and you are pivoting every month, Clean Architecture is a liability, not an asset.
- The right architecture for today is not the right architecture for 2 years from now. Start with what works for your current team size and complexity, and evolve when the pain of the current architecture exceeds the cost of changing it. Document your architecture decision records (ADRs) so future developers understand why you chose what you did.
