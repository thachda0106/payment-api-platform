# Session 06: Hexagonal Architecture in Go

## Why This Topic Exists

Hexagonal Architecture (Ports and Adapters), introduced by Alistair Cockburn in 2005, addresses a fundamental software design problem: business logic becomes entangled with infrastructure concerns. In Java/Spring ecosystems, hexagonal architecture is well-established with frameworks like Spring's `@Component`, `@Repository`, and dependency injection containers. In Go, the implementation differs significantly — Go has no DI container, no annotations, and no convention-over-configuration.

This session exists because hexagonal architecture is the most misunderstood architectural pattern in Go. Developers either dismiss it as "Java complexity in Go" (wrong) or implement it as a mechanical file-layout exercise without understanding the dependency inversion principle at its core (also wrong). A Staff/Principal Engineer must understand when hexagonal architecture solves real problems and when it creates unnecessary indirection.

Go's interface system makes hexagonal architecture more natural than in any other language — interfaces are implicit, defined at the consumer, and satisfied without declaration. This is the pattern's natural home.

---

## Mental Model

### The Core Metaphor

Imagine a hexagon. At the center is your **domain logic** — pure Go code that knows nothing about HTTP, PostgreSQL, Kafka, or gRPC. The edges of the hexagon are **ports** — Go interfaces that define the contract between the domain and the outside world. The adapters plug into these ports — Chi HTTP handlers for incoming REST, PostgreSQL repositories for outgoing persistence, Kafka producers for outgoing events.

```
              ┌───────────────────────────────┐
              │                               │
    HTTP ────►│  ┌─────────────────────────┐  │────► PostgreSQL
              │  │                         │  │
   gRPC ────►│  │       DOMAIN            │  │────► Redis
              │  │   (Pure Go, zero deps)  │  │
   Kafka ───►│  │                         │  │────► Kafka
   (in)      │  └─────────────────────────┘  │      (out)
              │                               │
  Schedule ──►│                               │────► Email
              └───────────────────────────────┘
```

Every arrow pointing INWARD is a **driving adapter** (primary actor). Every arrow pointing OUTWARD is a **driven adapter** (secondary actor). The domain defines the interfaces for both directions.

### Mental Map: Three Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    ADAPTER LAYER                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │   Chi    │  │   gRPC   │  │PostgreSQL│  │   Kafka    │  │
│  │ Handlers │  │ Handlers │  │   Repo   │  │  Producer  │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └─────┬──────┘  │
│       │             │             │              │          │
├───────┼─────────────┼─────────────┼──────────────┼──────────┤
│       ▼             ▼             ▲              ▲          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                 PORT LAYER                           │   │
│  │  Primary ports (interfaces):                        │   │
│  │    OrderService interface — what the world can do   │   │
│  │                                                     │   │
│  │  Secondary ports (interfaces):                      │   │
│  │    OrderRepository interface — what the domain needs│   │
│  │    EventPublisher interface — what the domain needs │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                  │
├─────────────────────────┼──────────────────────────────────┤
│                         ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                DOMAIN LAYER                          │   │
│  │  Aggregate: Order                                    │   │
│  │  Value Objects: Money, OrderID                       │   │
│  │  Domain Service: PricingService                      │   │
│  │  Domain Events: OrderSubmitted                       │   │
│  │                                                     │   │
│  │  IMPORTS: only standard library, maybe a UUID pkg   │   │
│  │  NO: database/sql, net/http, kafka, grpc, redis     │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Go-Specific Mental Shift

| Concept | Java/Spring Hexagonal | Go Hexagonal |
|---|---|---|
| Port definition | Java interface + `@Port` annotation | Go `interface` in domain package |
| Adapter wiring | `@Autowired` / `@Inject` / Spring context | Manual constructor injection in `main.go` |
| Interface location | Often co-located with implementation | Defined in domain, implemented in adapters |
| Dependency direction | Enforced by ArchUnit tests | Enforced by Go import rules (no cycles) |
| Testing ports | Mockito `@Mock` + `when().thenReturn()` | Manual stub structs implementing interfaces |
| Configuration | `application.yml` + `@Value` | Explicit struct + env vars in `main.go` |
| Lifecycle hooks | `@PostConstruct` / `@PreDestroy` | Explicit `Start()`/`Shutdown()` methods |

---

## Internal Architecture

### Directory Structure: Go Hexagonal

```
project/
├── cmd/
│   └── server/
│       └── main.go                  # Wire-up: compose adapters → domain
│
├── domain/                          # Center of the hexagon
│   ├── order/
│   │   ├── order.go                 # Aggregate root
│   │   ├── money.go                 # Value object
│   │   ├── order_status.go          # Enum + state machine
│   │   ├── ports.go                 # PRIMARY + SECONDARY ports (interfaces)
│   │   └── order_test.go            # Domain unit tests
│   ├── payment/
│   │   ├── payment.go
│   │   ├── ports.go
│   │   └── payment_test.go
│   └── customer/
│       ├── customer.go
│       ├── ports.go
│       └── customer_test.go
│
├── adapters/
│   ├── inbound/                     # Driving adapters (PRIMARY)
│   │   ├── http/
│   │   │   ├── order_handler.go     # Chi handler → OrderService
│   │   │   ├── payment_handler.go
│   │   │   ├── middleware.go
│   │   │   └── router.go           # Chi router setup
│   │   ├── grpc/
│   │   │   ├── order_server.go     # gRPC handler → OrderService
│   │   │   └── payment_server.go
│   │   └── kafka_consumer/
│   │       └── order_consumer.go    # Kafka consumer → OrderService
│   │
│   └── outbound/                    # Driven adapters (SECONDARY)
│       ├── postgres/
│       │   ├── order_repository.go  # OrderRepository implementation
│       │   ├── payment_repository.go
│       │   ├── migrations/
│       │   └── connection.go
│       ├── redis/
│       │   ├── order_cache.go
│       │   └── connection.go
│       ├── kafka_producer/
│       │   ├── event_publisher.go   # EventPublisher implementation
│       │   └── connection.go
│       └── email/
│           └── sendgrid_adapter.go
│
├── application/                     # Optional: use case orchestration
│   ├── order_service.go             # Implements primary port interface
│   └── payment_service.go
│
└── pkg/                             # Shared utilities (avoid if possible)
    └── idgen/
        └── idgen.go
```

### Ports (Interfaces)

Ports are Go interfaces defined in the **domain package**. The domain defines what it needs and what it offers. Never the other way around.

**Primary Ports (inbound/driving):** What the outside world can do with your domain.

```go
// domain/order/ports.go
package order

import "context"

// Primary port: the use cases the application exposes
type OrderService interface {
    CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error)
    SubmitOrder(ctx context.Context, id OrderID) error
    AddLineItem(ctx context.Context, id OrderID, productID string, qty int) error
    GetOrder(ctx context.Context, id OrderID) (*Order, error)
    ListCustomerOrders(ctx context.Context, customerID string, limit, offset int) ([]*Order, error)
}
```

**Secondary ports (outbound/driven):** What the domain needs from the outside world.

```go
// domain/order/ports.go (continued)
package order

import "context"

// Secondary port: persistence the domain needs
type Repository interface {
    Save(ctx context.Context, order *Order) error
    FindByID(ctx context.Context, id OrderID) (*Order, error)
    FindByCustomerID(ctx context.Context, customerID string, limit, offset int) ([]*Order, error)
}

// Secondary port: event publishing the domain needs
type EventPublisher interface {
    PublishOrderEvent(ctx context.Context, event DomainEvent) error
}

// Secondary port: external service the domain needs
type InventoryChecker interface {
    CheckAvailability(ctx context.Context, productIDs []string) (map[string]int, error)
}
```

**Critical rule**: The domain package never imports `database/sql`, `net/http`, `github.com/segmentio/kafka-go`, or any infrastructure library. If your `domain/order/` package has any of these imports, you've violated hexagonal architecture.

### Inbound Adapters (Chi HTTP Handler)

An inbound adapter translates between the external world (HTTP) and the domain (primary port):

```go
// adapters/inbound/http/order_handler.go
package http

import (
    "encoding/json"
    "net/http"

    "github.com/go-chi/chi/v5"
    "myapp/domain/order"
)

type OrderHandler struct {
    service order.OrderService  // Depends on PORT interface, not implementation
}

func NewOrderHandler(svc order.OrderService) *OrderHandler {
    return &OrderHandler{service: svc}
}

func (h *OrderHandler) RegisterRoutes(r chi.Router) {
    r.Route("/api/v1/orders", func(r chi.Router) {
        r.Post("/", h.createOrder)
        r.Get("/{orderID}", h.getOrder)
        r.Post("/{orderID}/lines", h.addLineItem)
        r.Post("/{orderID}/submit", h.submitOrder)
    })
}

func (h *OrderHandler) createOrder(w http.ResponseWriter, r *http.Request) {
    var req CreateOrderRequest  // DTO — NOT a domain object
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        writeError(w, http.StatusBadRequest, "invalid request body")
        return
    }

    // Map DTO to domain command (application layer concern)
    cmd := order.CreateOrderCommand{
        CustomerID: req.CustomerID,
        Items:      mapItems(req.Items),
    }

    result, err := h.service.CreateOrder(r.Context(), cmd)
    if err != nil {
        writeError(w, http.StatusInternalServerError, err.Error())
        return
    }

    writeJSON(w, http.StatusCreated, OrderResponse{
        ID:     string(result.ID()),
        Status: result.Status().String(),
        Total:  formatMoney(result.Total()),
    })
}

// CreateOrderRequest is a DTO — it has no business logic, no invariants
type CreateOrderRequest struct {
    CustomerID string               `json:"customer_id"`
    Items      []CreateOrderItemDTO `json:"items"`
}

type OrderResponse struct {
    ID     string `json:"id"`
    Status string `json:"status"`
    Total  string `json:"total"`
}
```

**Key observations:**
1. The handler knows about HTTP (JSON encoding, status codes, chi router) — this is its job
2. The handler knows about DTOs — mapping between HTTP JSON and domain commands
3. The handler has ZERO business logic — no validation (that's the aggregate's job), no calculations (that's the domain service's job)
4. The handler depends on the `order.OrderService` **interface** — it can be swapped with a mock for testing
5. The handler does not import `domain/order/order.go` directly — it only uses the port interface

### Outbound Adapters (PostgreSQL Repository)

An outbound adapter implements a secondary port and handles infrastructure-specific concerns:

```go
// adapters/outbound/postgres/order_repository.go
package postgres

import (
    "context"
    "database/sql"
    "fmt"

    "myapp/domain/order"
)

type OrderRepository struct {
    db *sql.DB
}

// Compile-time check: OrderRepository implements order.Repository
var _ order.Repository = (*OrderRepository)(nil)

func NewOrderRepository(db *sql.DB) *OrderRepository {
    return &OrderRepository{db: db}
}

func (r *OrderRepository) Save(ctx context.Context, o *order.Order) error {
    tx, err := r.db.BeginTx(ctx, nil)
    if err != nil {
        return fmt.Errorf("begin tx: %w", err)
    }
    defer tx.Rollback()

    _, err = tx.ExecContext(ctx,
        `INSERT INTO orders (id, customer_id, status, total_amount, total_currency, version)
         VALUES ($1, $2, $3, $4, $5, $6)
         ON CONFLICT (id) DO UPDATE SET
           status = EXCLUDED.status,
           total_amount = EXCLUDED.total_amount,
           total_currency = EXCLUDED.total_currency,
           version = order.version + 1
         WHERE order.version = $7`,
        o.ID(), o.CustomerID(), o.Status(), o.Total().Amount(),
        o.Total().Currency(), o.Version()+1, o.Version(),
    )
    if err != nil {
        return fmt.Errorf("upsert order: %w", err)
    }

    // Delete removed lines, upsert current lines
    // ...implementation...

    // Write domain events to outbox (same transaction!)
    for _, event := range o.Events() {
        if err := r.insertOutbox(ctx, tx, event); err != nil {
            return fmt.Errorf("insert outbox: %w", err)
        }
    }

    return tx.Commit()
}

func (r *OrderRepository) FindByID(ctx context.Context, id order.OrderID) (*order.Order, error) {
    var (
        orderID      string
        customerID   string
        status       string
        totalAmount  int64
        totalCurrency string
        version      int
    )

    err := r.db.QueryRowContext(ctx,
        `SELECT id, customer_id, status, total_amount, total_currency, version
         FROM orders WHERE id = $1`, string(id),
    ).Scan(&orderID, &customerID, &status, &totalAmount, &totalCurrency, &version)

    if err == sql.ErrNoRows {
        return nil, order.ErrNotFound
    }
    if err != nil {
        return nil, fmt.Errorf("query order: %w", err)
    }

    lines, err := r.findOrderLines(ctx, id)
    if err != nil {
        return nil, err
    }

    // Reconstitute the domain aggregate from database rows
    return order.Reconstitute(
        order.OrderID(orderID),
        customerID,
        parseStatus(status),
        totalAmount, totalCurrency,
        lines,
        version,
    ), nil
}
```

**Key observations:**
1. The repository imports `database/sql` — this is fine, it's an outbound adapter
2. The repository imports `domain/order` — to satisfy the interface and reconstitute aggregates
3. The repository handles SQL dialects, transaction management, connection pooling
4. `var _ order.Repository = (*OrderRepository)(nil)` — compile-time verification that the interface is satisfied
5. The repository does NOT contain business logic — it persists and reconstitutes, nothing more

### Application Service Layer (Implementation of Primary Port)

The application service is the bridge between inbound adapters and domain logic. It implements the primary port:

```go
// application/order_service.go
package application

import (
    "context"
    "fmt"

    "myapp/domain/order"
)

type OrderService struct {
    repo         order.Repository
    publisher    order.EventPublisher
    inventory    order.InventoryChecker
}

// Compile-time check
var _ order.OrderService = (*OrderService)(nil)

func NewOrderService(
    repo order.Repository,
    publisher order.EventPublisher,
    inventory order.InventoryChecker,
) *OrderService {
    return &OrderService{
        repo: repo,
        publisher: publisher,
        inventory: inventory,
    }
}

func (s *OrderService) CreateOrder(ctx context.Context, cmd order.CreateOrderCommand) (*order.Order, error) {
    // Validate command (input validation, not business invariants)
    if cmd.CustomerID == "" {
        return nil, fmt.Errorf("customer ID is required")
    }

    // Delegate to domain factory
    o := order.NewOrder(order.GenerateID(), cmd.CustomerID)

    // Add line items using domain logic
    for _, item := range cmd.Items {
        price, err := order.NewMoney(item.Price, item.Currency)
        if err != nil {
            return nil, fmt.Errorf("invalid price: %w", err)
        }
        if err := o.AddLine(item.ProductID, item.Quantity, price); err != nil {
            return nil, fmt.Errorf("adding line: %w", err)
        }
    }

    // Persist through secondary port
    if err := s.repo.Save(ctx, o); err != nil {
        return nil, fmt.Errorf("saving order: %w", err)
    }

    return o, nil
}

func (s *OrderService) SubmitOrder(ctx context.Context, id order.OrderID) error {
    o, err := s.repo.FindByID(ctx, id)
    if err != nil {
        return fmt.Errorf("finding order: %w", err)
    }

    // Check inventory through secondary port
    avail, err := s.inventory.CheckAvailability(ctx, o.ProductIDs())
    if err != nil {
        return fmt.Errorf("checking inventory: %w", err)
    }
    if !o.CanFulfillFrom(avail) { // Domain method
        return order.ErrInsufficientInventory
    }

    // Delegate to aggregate for state transition
    if err := o.Submit(); err != nil {
        return fmt.Errorf("submitting order: %w", err)
    }

    if err := s.repo.Save(ctx, o); err != nil {
        return fmt.Errorf("saving order: %w", err)
    }

    // Publish events through secondary port
    for _, event := range o.Events() {
        if err := s.publisher.PublishOrderEvent(ctx, event); err != nil {
            // Log but don't fail — outbox guarantees delivery
            // The outbox table has the event; publisher will retry
        }
    }

    return nil
}
```

### Wire-Up in main.go

This is where hexagonal architecture either succeeds or fails in Go. There is no DI framework — just explicit, ordered constructor calls:

```go
// cmd/server/main.go
package main

import (
    "context"
    "database/sql"
    "log/slog"
    "net/http"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/go-chi/chi/v5"
    "github.com/go-chi/chi/v5/middleware"
    _ "github.com/lib/pq"

    "myapp/adapters/inbound/http/chi"
    "myapp/adapters/outbound/kafka"
    "myapp/adapters/outbound/postgres"
    "myapp/adapters/outbound/redis"
    "myapp/application"
)

func main() {
    logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
    cfg := LoadConfig()

    // ─── Initialize secondary (outbound) adapters ───
    db, err := sql.Open("postgres", cfg.DatabaseURL)
    if err != nil {
        logger.Error("failed to open database", "error", err)
        os.Exit(1)
    }
    defer db.Close()

    orderRepo := postgres.NewOrderRepository(db)
    paymentRepo := postgres.NewPaymentRepository(db)

    cache, err := redis.NewClient(cfg.RedisURL)
    if err != nil {
        logger.Error("failed to connect to redis", "error", err)
        os.Exit(1)
    }
    orderCache := redis.NewOrderCache(cache)

    kafkaPublisher := kafka.NewEventPublisher(cfg.KafkaBrokers, logger)

    // ─── Initialize application services (primary port implementations) ───
    orderSvc := application.NewOrderService(orderRepo, kafkaPublisher, orderCache)
    paymentSvc := application.NewPaymentService(paymentRepo, kafkaPublisher)

    // ─── Initialize primary (inbound) adapters ───
    orderHandler := chi.NewOrderHandler(orderSvc)
    paymentHandler := chi.NewPaymentHandler(paymentSvc)

    // ─── Compose HTTP server ───
    r := chi.NewRouter()
    r.Use(middleware.Logger)
    r.Use(middleware.Recoverer)
    r.Use(middleware.Timeout(30 * time.Second))
    r.Use(middleware.RealIP)

    r.Route("/api/v1", func(r chi.Router) {
        orderHandler.RegisterRoutes(r)
        paymentHandler.RegisterRoutes(r)
    })

    // Health check
    r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
        w.WriteHeader(http.StatusOK)
        w.Write([]byte("ok"))
    })

    srv := &http.Server{
        Addr:         ":" + cfg.Port,
        Handler:      r,
        ReadTimeout:  10 * time.Second,
        WriteTimeout: 30 * time.Second,
        IdleTimeout:  120 * time.Second,
    }

    // ─── Graceful shutdown ───
    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

    go func() {
        logger.Info("server starting", "port", cfg.Port)
        if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
            logger.Error("server error", "error", err)
            os.Exit(1)
        }
    }()

    <-quit
    logger.Info("shutting down gracefully...")

    ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()

    if err := srv.Shutdown(ctx); err != nil {
        logger.Error("forced shutdown", "error", err)
    }
    if err := kafkaPublisher.Close(); err != nil {
        logger.Error("kafka publisher close error", "error", err)
    }
    if err := db.Close(); err != nil {
        logger.Error("database close error", "error", err)
    }

    logger.Info("server stopped")
}
```

**Critical wire-up rules:**
1. Secondary adapters initialized FIRST (they have no domain dependencies)
2. Application services initialized SECOND (they depend on secondary port interfaces)
3. Primary adapters initialized LAST (they depend on primary port interfaces)
4. All dependencies are interfaces, all implementations are concrete structs
5. No DI framework, no reflection, no magic — explicit, traceable wiring

### Testing Strategy

Hexagonal architecture enables testing at every layer independently:

**Domain tests (no mocks, no infrastructure):**

```go
// domain/order/order_test.go
func TestOrder_Submit(t *testing.T) {
    o := NewOrder(OrderID("test"), "cust_1")
    o.AddLine("prod_1", 2, mustMoney(1000, "USD"))

    err := o.Submit()

    assert.NoError(t, err)
    assert.Equal(t, Submitted, o.Status())
    assert.Len(t, o.Events(), 1)
    _, ok := o.Events()[0].(OrderSubmitted)
    assert.True(t, ok)
}
```

**Application service tests (with stub adapters):**

```go
// application/order_service_test.go
type StubOrderRepository struct {
    orders map[order.OrderID]*order.Order
}

func (s *StubOrderRepository) Save(ctx context.Context, o *order.Order) error {
    s.orders[o.ID()] = o
    return nil
}

func (s *StubOrderRepository) FindByID(ctx context.Context, id order.OrderID) (*order.Order, error) {
    if o, ok := s.orders[id]; ok {
        return o, nil
    }
    return nil, order.ErrNotFound
}

type SpyEventPublisher struct {
    events []order.DomainEvent
}

func (s *SpyEventPublisher) PublishOrderEvent(ctx context.Context, e order.DomainEvent) error {
    s.events = append(s.events, e)
    return nil
}

func TestOrderService_SubmitOrder(t *testing.T) {
    repo := &StubOrderRepository{orders: make(map[order.OrderID]*order.Order)}
    publisher := &SpyEventPublisher{}
    svc := NewOrderService(repo, publisher)

    // Arrange: create an order via the service
    o, err := svc.CreateOrder(context.Background(), order.CreateOrderCommand{
        CustomerID: "cust_1",
    })
    require.NoError(t, err)

    // Act: submit it
    err = svc.SubmitOrder(context.Background(), o.ID())

    // Assert
    assert.NoError(t, err)
    assert.Len(t, publisher.events, 1)
}
```

**HTTP handler tests (with httptest):**

```go
// adapters/inbound/http/order_handler_test.go
func TestOrderHandler_CreateOrder(t *testing.T) {
    svc := &StubOrderService{}  // Implements order.OrderService interface
    handler := NewOrderHandler(svc)

    r := chi.NewRouter()
    handler.RegisterRoutes(r)

    body := `{"customer_id": "cust_1", "items": []}`
    req := httptest.NewRequest("POST", "/api/v1/orders", strings.NewReader(body))
    req.Header.Set("Content-Type", "application/json")
    w := httptest.NewRecorder()

    r.ServeHTTP(w, req)

    assert.Equal(t, http.StatusCreated, w.Code)
}
```

**Integration tests (real database):**

```go
func TestOrderRepository_Integration(t *testing.T) {
    if testing.Short() {
        t.Skip("skipping integration test")
    }

    db := setupTestDB(t)
    repo := postgres.NewOrderRepository(db)

    o := order.NewOrder(order.GenerateID(), "cust_1")
    m, _ := order.NewMoney(2000, "USD")
    o.AddLine("prod_1", 2, m)

    err := repo.Save(context.Background(), o)
    require.NoError(t, err)

    loaded, err := repo.FindByID(context.Background(), o.ID())
    require.NoError(t, err)
    assert.Equal(t, o.ID(), loaded.ID())
    assert.Equal(t, o.Total(), loaded.Total())
}
```

---

## Request Flow Diagrams

### Full Request Flow (HTTP → Domain → DB)

```
  HTTP POST /api/v1/orders
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  INBOUND ADAPTER: Chi Handler (order_handler.go)            │
│                                                             │
│  1. Decode JSON → CreateOrderRequest DTO                    │
│  2. Map DTO → order.CreateOrderCommand (domain object)      │
│  3. Call orderSvc.CreateOrder(ctx, cmd)                     │
│     │  (depends on order.OrderService interface, not impl)  │
└─────┼───────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  APPLICATION SERVICE: OrderService (order_service.go)       │
│                                                             │
│  1. Validate command input                                  │
│  2. Call order.NewOrder(id, customerID) — domain factory    │
│  3. Call o.AddLine(...) — aggregate method                  │
│  4. Call repo.Save(ctx, o) — secondary port                 │
│  5. Return constructed Order to handler                     │
└─────┼───────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  DOMAIN: Order Aggregate (order/order.go)                   │
│                                                             │
│  NewOrder() → struct with Draft status, empty lines         │
│  AddLine() → validate status, add line, recalc total        │
│  NO database code. NO HTTP code. Pure Go.                   │
└─────┼───────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  OUTBOUND ADAPTER: PostgreSQL Repo (postgres/order_repo.go) │
│                                                             │
│  BEGIN TRANSACTION                                           │
│  INSERT INTO orders (id, customer_id, status, ...)          │
│  INSERT INTO order_lines (order_id, product_id, qty, ...)   │
│  INSERT INTO outbox (event_type, payload, ...)              │
│  COMMIT                                                     │
└─────────────────────────────────────────────────────────────┘

Response: HTTP 201 {"id": "ord_abc123", "status": "draft", "total": "$20.00"}
```

### Dependency Direction

```
                     DEPENDS ON (Interface)
                     ◄────────────────────

  ┌──────────┐          ┌──────────────┐          ┌──────────┐
  │  Chi     │          │   Domain     │          │ Postgres │
  │ Handler  │─────────►│   Ports      │◄─────────│   Repo   │
  │          │          │ (interfaces) │          │          │
  └──────────┘          └──────────────┘          └──────────┘
       │                                                  │
       │ IMPLEMENTS (concrete)                            │ IMPLEMENTS
       │ uses the interface                               │ satisfies the interface
       ▼                                                  ▼
  ┌──────────────────────────────────────────────────────────┐
  │                  APPLICATION SERVICE                      │
  │  Depends on secondary ports (interfaces)                  │
  │  Implements primary ports (interfaces)                    │
  └──────────────────────────────────────────────────────────┘
```

No arrow points FROM domain TO infrastructure. That's the whole point.

---

## Lifecycle Diagrams

### Service Startup Lifecycle

```
┌────────────────────────────────────────────────────────────┐
│                    SERVICE STARTUP                          │
│                                                            │
│  main()                                                    │
│    │                                                       │
│    ├── 1. Load Config (env vars, flags, files)             │
│    │                                                       │
│    ├── 2. Initialize OUTBOUND adapters                     │
│    │       ├── PostgreSQL connection pool                  │
│    │       ├── Redis client                                │
│    │       └── Kafka producer                              │
│    │                       │                               │
│    │                       ▼ All ready                     │
│    ├── 3. Inject into APPLICATION SERVICES                 │
│    │       ├── NewOrderService(repo, publisher, cache)     │
│    │       └── NewPaymentService(repo, publisher)          │
│    │                       │                               │
│    │                       ▼ Services ready                │
│    ├── 4. Initialize INBOUND adapters                      │
│    │       ├── NewOrderHandler(orderSvc)                   │
│    │       ├── NewPaymentHandler(paymentSvc)               │
│    │       ├── chi.NewRouter() + middleware                │
│    │       └── Register routes                             │
│    │                       │                               │
│    │                       ▼ Router assembled              │
│    ├── 5. Start HTTP server (goroutine)                    │
│    │       srv.ListenAndServe()                            │
│    │                                                       │
│    ├── 6. Start Kafka consumers (goroutines)               │
│    │                                                       │
│    └── 7. Block on SIGINT/SIGTERM for shutdown             │
└────────────────────────────────────────────────────────────┘
```

### Service Shutdown Lifecycle

```
┌────────────────────────────────────────────────────────────┐
│                 GRACEFUL SHUTDOWN                           │
│                                                            │
│  SIGTERM received                                          │
│    │                                                       │
│    ├── 1. Stop accepting new connections                   │
│    │       http.Server.Shutdown(ctx)                       │
│    │       Wait for in-flight requests (30s timeout)       │
│    │                                                       │
│    ├── 2. Close INBOUND adapters (in reverse order)        │
│    │       gRPC server GracefulStop()                      │
│    │       Kafka consumers close()                         │
│    │                                                       │
│    ├── 3. Close OUTBOUND adapters                          │
│    │       Kafka producer Flush() + Close()                │
│    │       Redis client Close()                            │
│    │       PostgreSQL connection pool Close()              │
│    │                                                       │
│    └── 4. os.Exit(0)                                       │
└────────────────────────────────────────────────────────────┘
```

### Adapter Substitution Lifecycle

```
┌────────────────────────────────────────────────────────────┐
│           ADAPTER SUBSTITUTION (at any lifecycle point)    │
│                                                            │
│  Production:            Test:              Migration:      │
│  ┌──────────┐          ┌──────────┐        ┌──────────┐   │
│  │Postgres  │          │In-Memory │        │CockroachDB│   │
│  │  Repo    │─────────►│   Repo   │────────►│   Repo   │   │
│  └──────────┘          └──────────┘        └──────────┘   │
│       │                     │                    │          │
│       └─────────────────────┴────────────────────┘          │
│                             │                               │
│                     order.Repository                        │
│                     (interface unchanged)                   │
│                             │                               │
│                     order.OrderService                      │
│                     (logic unchanged)                       │
│                             │                               │
│                     order.OrderHandler                      │
│                     (handler unchanged)                     │
└────────────────────────────────────────────────────────────┘
```

The domain, application, and handler layers never change. Only the adapter implementation changes. This is the payoff.

---

## Source Code Reading Guide

### Recommended Reading Order

| Order | File / Package | Focus | Time |
|-------|---------------|-------|------|
| 1 | `domain/order/ports.go` | Primary + secondary interface definitions | 10 min |
| 2 | `domain/order/order.go` | Pure domain logic, no infrastructure imports | 20 min |
| 3 | `domain/order/order_test.go` | Domain unit tests, no mocks | 15 min |
| 4 | `adapters/outbound/postgres/order_repository.go` | SQL implementation of secondary port | 20 min |
| 5 | `adapters/outbound/postgres/order_repository_test.go` | Integration tests with real DB | 15 min |
| 6 | `application/order_service.go` | Service implements primary port, uses secondary ports | 15 min |
| 7 | `application/order_service_test.go` | Stub-based service tests | 10 min |
| 8 | `adapters/inbound/http/order_handler.go` | Chi handler → primary port | 10 min |
| 9 | `adapters/inbound/http/router.go` | Chi router assembly | 5 min |
| 10 | `cmd/server/main.go` | Wire-up, config, graceful shutdown | 15 min |

### What to Ignore

- **Framework-level DI containers**: Go hexagonal architecture uses explicit constructor injection. If you see `wire` or `dig` imports, that's a separate architectural discussion.
- **Auto-configuration magic**: There is no `@EnableHexagonalArchitecture` annotation. All wiring is explicit in `main.go`.
- **Reflection-based mocking**: Go stubs are hand-written structs that implement interfaces. Don't look for Mockito equivalents.
- **Repository base classes**: Go doesn't have inheritance. Each repository is a standalone struct.
- **ORM entities with hexagonal annotations**: In Go, the domain aggregate IS the entity. There is no separate "JPA entity" for persistence.

---

## Production Failure Scenarios

### Scenario 1: Implicit Dependency Leak

**Symptom**: Someone imports `net/http` in the domain package to write a helper function. Compilation succeeds. Test stubs seem fine. But the domain is now coupled to the HTTP protocol — you cannot reuse it for gRPC.

**Root cause**: No compile-time enforcement of layer boundaries. Go allows any import.

**Fix**: Use `go-cleanarch` or `depguard` linter rules to enforce import direction:

```yaml
# .golangci.yml
linters-settings:
  depguard:
    rules:
      domain:
        deny:
          - pkg: "database/sql"
            desc: "domain must not import database/sql"
          - pkg: "net/http"
            desc: "domain must not import net/http"
          - pkg: "github.com/segmentio/kafka-go"
            desc: "domain must not import kafka"
```

### Scenario 2: Accidental Bypass of Application Service

**Symptom**: A Chi handler directly instantiates and calls `postgres.OrderRepository` instead of going through `order.OrderService`. Business rules in the service are skipped. Production data is corrupted.

**Root cause**: Both the service and the repository are available in scope. Developer picks the wrong one.

**Fix**: Enforce through `internal/` package visibility. Only export the `OrderService` interface from the application package. Keep repository implementations in `internal/adapters/`.

```go
// Only this is visible to external packages:
package application

type OrderService interface { /* ... */ }

// Repository is not exported — handlers can't access it directly
```

### Scenario 3: Outbound Adapter Blocks the Event Loop

**Symptom**: A Kafka producer adapter blocks for 5 seconds on a broker connection timeout. During this time, the Chi handler's goroutine is blocked. Request latency spikes to 5+ seconds.

**Root cause**: Synchronous outbound adapter calls in the request path.

**Fix**: Wrap slow outbound calls in goroutines with timeouts, or use buffered channels:

```go
func (s *OrderService) SubmitOrder(ctx context.Context, id order.OrderID) error {
    // ... save order ...

    // Fire-and-forget event publishing (outbox guarantees delivery)
    go func() {
        pubCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
        defer cancel()
        for _, event := range o.Events() {
            s.publisher.PublishOrderEvent(pubCtx, event)
        }
    }()

    return nil
}
```

### Scenario 4: Port Interface Overgrowth

**Symptom**: The `order.Repository` interface has 35 methods. Adding a new method requires updating 3 implementation files, 5 stub files, and 2 mock files. Changing one parameter cascades.

**Root cause**: Interface not following interface segregation principle. Monolithic repository interface.

**Fix**: Split into role-specific interfaces:

```go
// Before — monolithic
type Repository interface {
    Save(ctx, *Order) error
    FindByID(ctx, OrderID) (*Order, error)
    FindByCustomerID(ctx, string, int, int) ([]*Order, error)
    FindPendingOrders(ctx) ([]*Order, error)
    CountByStatus(ctx, OrderStatus) (int, error)
    DeleteOrder(ctx, OrderID) error
    // ... 30 more methods
}

// After — segregated by consumer
type OrderSaver interface {
    Save(ctx context.Context, order *Order) error
}

type OrderFinder interface {
    FindByID(ctx context.Context, id OrderID) (*Order, error)
}

type OrderLister interface {
    FindByCustomerID(ctx context.Context, customerID string, limit, offset int) ([]*Order, error)
}

// Consumers declare only the interfaces they need
type OrderService struct {
    saver  OrderSaver
    finder OrderFinder
    lister OrderLister
}
```

### Scenario 5: Wiring Order Dependency Hell

**Symptom**: `main.go` grows to 600 lines of constructor calls. Order matters deeply. Adding a new adapter requires inserting it at exactly the right position.

**Root cause**: No dependency graph visualization or validation.

**Fix**: Break `main.go` into composable setup functions:

```go
// cmd/server/setup/database.go
func SetupDatabase(cfg Config, logger *slog.Logger) (*sql.DB, func()) {
    db, err := sql.Open("postgres", cfg.DatabaseURL)
    if err != nil { logger.Error(...); os.Exit(1) }
    return db, func() { db.Close() }
}

// cmd/server/setup/services.go
func SetupOrderService(db *sql.DB, kafka kafka.Producer, logger *slog.Logger) order.OrderService {
    repo := postgres.NewOrderRepository(db)
    publisher := kafka.NewEventPublisher(kafka)
    return application.NewOrderService(repo, publisher)
}

// cmd/server/main.go — now 30 lines
func main() {
    db, cleanupDB := setup.SetupDatabase(cfg, logger)
    defer cleanupDB()

    kafka := setup.SetupKafka(cfg, logger)
    defer kafka.Close()

    orderSvc := setup.SetupOrderService(db, kafka, logger)
    server := setup.SetupHTTPServer(orderSvc, cfg, logger)

    setup.GracefulShutdown(server, logger)
}
```

---

## Debugging Techniques

### 1. Layer Boundary Violation Detection

Add a linter check in CI that verifies domain packages have no infrastructure imports:

```bash
# Check domain purity
go list -f '{{ join .Imports "\n" }}' ./domain/... | \
  grep -E '(database/sql|net/http|kafka|redis|grpc)' && \
  echo "DOMAIN PURITY VIOLATION" && exit 1
```

### 2. Interface Satisfaction Verification

Use the `var _ Interface = (*Implementation)(nil)` pattern at compile time:

```go
// In each adapter file, verify it satisfies the domain interface
var _ order.Repository = (*postgres.OrderRepository)(nil)
var _ order.EventPublisher = (*kafka.EventPublisher)(nil)
var _ order.OrderService = (*application.OrderService)(nil)
```

If the interface changes, the adapter files won't compile. This catches drift immediately.

### 3. Dependency Graph Visualization

Generate a dependency graph to verify arrows point the right direction:

```bash
go mod graph | grep myapp | grep -v indirect | sort
```

This shows all module-level dependencies. For package-level, use `go-callvis`:

```bash
go-callvis -focus domain/order ./cmd/server
```

### 4. Runtime Port Tracing

Wrap all port implementations with a tracing decorator for debugging:

```go
type TracingOrderRepository struct {
    inner  order.Repository
    logger *slog.Logger
}

func (t *TracingOrderRepository) Save(ctx context.Context, o *order.Order) error {
    start := time.Now()
    err := t.inner.Save(ctx, o)
    t.logger.Debug("repository.save",
        "order_id", o.ID(),
        "duration_ms", time.Since(start).Milliseconds(),
        "error", err,
    )
    return err
}

// In main.go, wrap the real repo:
// repo := postgres.NewOrderRepository(db)
// tracedRepo := &TracingOrderRepository{inner: repo, logger: logger}
// svc := application.NewOrderService(tracedRepo, publisher)
```

### 5. Adapter Latency Profiling

Since all traffic flows through port interfaces, instrument them with Prometheus histograms:

```go
type InstrumentedOrderRepository struct {
    inner   order.Repository
    saveDur prometheus.Histogram
    findDur prometheus.Histogram
}

func (i *InstrumentedOrderRepository) Save(ctx context.Context, o *order.Order) error {
    timer := prometheus.NewTimer(i.saveDur)
    defer timer.ObserveDuration()
    return i.inner.Save(ctx, o)
}
```

---

## Observability Considerations

### Key Metrics

| Metric | Type | Labels | Source |
|--------|------|--------|--------|
| `service_create_order_duration_seconds` | Histogram | `status` | Application service |
| `repository_save_duration_seconds` | Histogram | `entity`, `operation` | Outbound adapter |
| `event_publish_total` | Counter | `event_type`, `status` | Outbound adapter |
| `http_request_duration_seconds` | Histogram | `method`, `path`, `status` | Inbound adapter (Chi middleware) |
| `domain_errors_total` | Counter | `error_type` | Domain layer |
| `adapter_errors_total` | Counter | `adapter_type`, `operation` | All adapters |

### Tracing Strategy

Inject trace context through `context.Context` — Go's standard mechanism:

```go
// Inbound adapter starts the span
func (h *OrderHandler) createOrder(w http.ResponseWriter, r *http.Request) {
    ctx, span := tracer.Start(r.Context(), "OrderHandler.createOrder")
    defer span.End()
    // ... handler logic ...
    result, err := h.service.CreateOrder(ctx, cmd) // Span propagates via ctx
}

// Application service adds business tags
func (s *OrderService) CreateOrder(ctx context.Context, cmd order.CreateOrderCommand) (*order.Order, error) {
    ctx, span := tracer.Start(ctx, "OrderService.CreateOrder")
    defer span.End()
    span.SetAttributes(
        attribute.String("customer.id", cmd.CustomerID),
        attribute.Int("item.count", len(cmd.Items)),
    )
    // ...
}
```

### Health Checks per Adapter

Each outbound adapter should expose a health check:

```go
type HealthChecker interface {
    HealthCheck(ctx context.Context) error
}

func (r *OrderRepository) HealthCheck(ctx context.Context) error {
    return r.db.PingContext(ctx)
}

// In Chi router:
r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
    results := map[string]string{
        "postgres": healthStatus(postgresRepo.HealthCheck(r.Context())),
        "redis":    healthStatus(redisCache.HealthCheck(r.Context())),
        "kafka":    healthStatus(kafkaPub.HealthCheck(r.Context())),
    }
    writeJSON(w, http.StatusOK, results)
})
```

---

## Performance Implications

### The Indirection Cost

Hexagonal architecture adds function call indirection. In Go, this cost is typically insignificant:

| Layer Transition | Overhead | Measurement |
|-----------------|----------|-------------|
| HTTP handler → service | ~5ns | Interface method dispatch |
| Service → repository | ~5ns | Interface method dispatch |
| Repository → SQL driver | ~50ns | Go → C interop |
| SQL query execution | 1-10ms | Database latency |

The interface dispatch overhead (nanoseconds) is irrelevant compared to I/O latency (milliseconds). The real performance cost is NOT the indirection — it's the potential for N+1 queries when adapters are poorly designed.

### Adapter Caching Pattern

Use the decorator pattern to add caching without changing domain logic:

```go
type CachedOrderRepository struct {
    inner order.Repository
    cache *redis.Client
    ttl   time.Duration
}

func (c *CachedOrderRepository) FindByID(ctx context.Context, id order.OrderID) (*order.Order, error) {
    key := "order:" + string(id)

    // Try cache
    if cached, err := c.cache.Get(ctx, key).Bytes(); err == nil {
        return order.Unmarshal(cached), nil
    }

    // Cache miss — delegate to inner adapter
    o, err := c.inner.FindByID(ctx, id)
    if err != nil {
        return nil, err
    }

    // Populate cache (fire and forget)
    go func() {
        data, _ := order.Marshal(o)
        c.cache.Set(context.Background(), key, data, c.ttl)
    }()

    return o, nil
}
```

### Bulk Operations

When a service needs to process many aggregates, batch at the repository level:

```go
// BAD — N+1 queries
for _, id := range orderIDs {
    order, _ := repo.FindByID(ctx, id)
    orders = append(orders, order)
}

// GOOD — single query
orders, _ := repo.FindByIDs(ctx, orderIDs)
// SELECT * FROM orders WHERE id IN ($1, $2, $3, ...)
```

---

## Architecture Implications

### When Hexagonal is Worth It

- **Multi-protocol services**: Same business logic exposed via REST, gRPC, and async events
- **Multi-storage**: Need to swap PostgreSQL for CockroachDB or DynamoDB without rewriting business logic
- **Complex testing**: Need to run business logic in isolation, in integration, and in load tests
- **Team independence**: Infrastructure team vs domain team — clean boundaries matter
- **High regulatory requirements**: Separation of business logic from I/O simplifies auditing

### When Hexagonal is Overkill

- **Simple CRUD APIs**: If your service is a thin wrapper over database tables, hexagonal architecture adds 3x files for zero benefit
- **Short-lived services**: If the service will be replaced in 6 months, invest in something else
- **Single-protocol, single-storage**: If you will never add gRPC or swap databases, the abstraction is speculative
- **Startups with < 5 engineers**: The wiring ceremony slows down delivery without proportional value

### Decision Matrix

```
┌──────────────────────────────────────────────────────────────────┐
│  Question                            │ Yes →        │ No →       │
├──────────────────────────────────────┼──────────────┼────────────┤
│  Will service expose ≥ 2 protocols?  │ Hexagonal    │ Consider   │
│  Will service live > 3 years?        │ Hexagonal    │ Simpler    │
│  Will > 2 teams contribute?          │ Hexagonal    │ Simpler    │
│  Are there complex business rules?   │ Hexagonal    │ Simpler    │
│  Need to swap storage layer later?   │ Hexagonal    │ N/A        │
│  Is this a startup MVP?              │ Simpler      │ N/A        │
└──────────────────────────────────────────────────────────────────┘
```

### Comparison: Hexagonal vs Feature-Based vs Clean

| Aspect | Hexagonal | Feature-Based | Clean Architecture |
|--------|-----------|---------------|-------------------|
| Ports/adapters explicit | Yes | No | Partial (use cases) |
| Test isolation | Excellent | Good | Excellent |
| File count per feature | High (5-8) | Low (2-3) | Very high (8-12) |
| New team member ramp-up | Moderate | Fast | Slow |
| Refactoring safety | High | Low | High |
| Go idiomatic-ness | Good | Best | Poor |

---

## Team Ownership Implications

### Adapter Ownership

| Adapter | Owned By | Contract | Change Frequency |
|---------|----------|----------|-----------------|
| PostgreSQL OrderRepository | Data Platform team | order.Repository interface | Low |
| Kafka EventPublisher | Streaming Platform team | order.EventPublisher interface | Low |
| Chi OrderHandler | API Gateway team | order.OrderService interface | Medium |
| gRPC OrderServer | Internal Services team | order.OrderService interface | Medium |
| Order aggregate (domain) | Checkout Domain team | N/A (owns the interfaces) | High |

### Interface Evolution Rules

1. **Domain team owns all port interfaces.** They define what they need. Adapter teams implement it.
2. **Adding a field to a value object** is a domain team decision. Adapter teams adapt their persistence/transport code.
3. **Adding a method to a secondary port** means ALL adapter teams must implement it. Coordinate carefully.
4. **Breaking changes to port interfaces** require versioned interfaces or migration periods:

```go
// Version 1 of the port
type OrderService interface {
    CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error)
}

// Version 2 extends (adapter teams implement both during migration)
type OrderServiceV2 interface {
    OrderService  // Embed v1
    CancelOrder(ctx context.Context, id OrderID, reason string) error
}
```

---

## Interview Questions

**1. Q: What is the difference between a port and an adapter in hexagonal architecture?**

A: A port is an interface defined by the domain that specifies a contract. A primary (driving) port defines what the application can do (e.g., `OrderService` interface). A secondary (driven) port defines what the domain needs from the outside world (e.g., `OrderRepository` interface). An adapter is a concrete implementation of a port — it translates between the domain's language and the outside world's protocol (HTTP, gRPC, PostgreSQL wire protocol, Kafka protocol). The domain defines ports; infrastructure implements adapters.

**2. Q: How does Go's interface system make hexagonal architecture more natural than in Java?**

A: Three reasons: (1) Go interfaces are implicit — a struct satisfies an interface by implementing its methods, no `implements` keyword needed. Adapters don't need to declare they implement `order.Repository`. (2) Go convention is to define interfaces at the consumer — the domain package defines `order.Repository` exactly where it's used. Java convention puts interfaces near implementations, making dependency inversion feel unnatural. (3) Go has no DI framework — constructor injection is explicit, making the dependency graph visible and traceable in `main.go` rather than hidden in annotations.

**3. Q: How do you enforce that domain packages don't import infrastructure in a large Go project?**

A: Use `depguard` in `.golangci.yml` to deny specific imports (e.g., `database/sql`, `net/http`, Kafka/Redis/gRPC libraries) in domain packages. For stronger enforcement, write a custom Go analysis pass using `go/analysis` that rejects any import from a domain package to an adapter package. In CI, run `go list -f '{{ join .Imports "\n" }}' ./domain/... | sort -u` and fail if any infrastructure package appears. Also use `go-callvis` to generate visual dependency graphs reviewed in PRs.

**4. Q: What happens when a secondary port needs a new method? How do you manage the interface evolution?**

A: (1) The domain team adds the method to the secondary port interface. (2) All adapter implementations now fail to compile — this is intentional feedback. (3) Each adapter team implements the new method for their storage/transport. (4) For external adapters (managed by other teams), coordinate via a deprecation/migration window. (5) Alternative: define a new, smaller interface (`OrderAnonymizer`) instead of growing the monolithic `OrderRepository`. The adapter implements both the old and new interfaces during migration. Go's implicit interfaces make this seamless.

**5. Q: How do you test a hexagonal Go service end-to-end without mocking everything?**

A: Use Go's `testing.T` with build tags to swap adapter implementations. Write two test suites: (1) `unit` tests with stubs (in-memory repository, fake event publisher) for domain + application layers. (2) `integration` tests with real adapters using test containers (PostgreSQL via `testcontainers-go`, Kafka via test containers, Redis via test containers). Tag integration tests with `//go:build integration` and run them separately. For HTTP handler tests, use `httptest.NewServer` with the real Chi router and stub services — this tests the full adapter chain from HTTP to domain and back.

**6. Q: How do you organize a Go project with 50+ secondary ports? Doesn't the interface count become unmanageable?**

A: Apply interface segregation aggressively. A single "God Repository" with 50 methods violates the principle. Instead, define small, role-specific interfaces per consumer:

```go
type OrderSaver interface { Save(ctx, *Order) error }
type OrderFinder interface { FindByID(ctx, OrderID) (*Order, error) }
type OrderStatusUpdater interface { UpdateStatus(ctx, OrderID, OrderStatus) error }
```

Group related ports into domain sub-packages (`order/shipping/`, `order/billing/`). For common CRUD operations, consider whether a generic repository makes sense (it rarely does in DDD) or whether you're building CRUD apps that don't need ports at all. If you genuinely have 50 distinct business operations each with its own contract, that's a sign your bounded contexts need splitting.

**7. Q: How does hexagonal architecture interact with Go's context.Context?**

A: `context.Context` is the cross-cutting concern that flows through the hexagon. Primary adapters create the context (Chi injects `r.Context()`). It flows through the primary port into the application service, through the secondary port into the adapter, and finally to the database driver. Context carries deadlines, cancellation signals, and trace metadata. The domain layer receives context but doesn't create one — it only passes it to secondary ports. This is correct: the domain doesn't know who created the deadline or why; it just respects it.

**8. Q: What are the real-world Go projects that successfully use hexagonal architecture?**

A: (1) **Mattermost** uses a plugin architecture that's essentially hexagonal — core defines interfaces, plugins implement them. (2) **Thanos** uses store interfaces that allow swapping object storage backends. (3) **Grafana** uses a backend plugin SDK based on ports and adapters. (4) **Hashicorp Vault** uses a storage backend interface with dozens of adapter implementations. (5) **NATS** uses connector interfaces. None of them call it "hexagonal architecture" but the pattern is identical: core defines interfaces, infrastructure plugs in.

**9. Q: How do you prevent a hexagonal Go project from becoming an "onion of indirection"?**

A: Three rules: (1) Don't create a port for something with only one implementation and no foreseeable second implementation. If you'll never swap PostgreSQL, you don't need a repository interface — use the concrete type. (2) Don't rename methods just to satisfy different interfaces — use the same method name across ports when the semantics are identical. (3) Don't create adapter layers that just delegate without adding value — a 3-line adapter that calls a 3-line port method is noise. Be pragmatic: hexagonal architecture is a tool, not a religion. Skip it for simple services.

**10. Q: How do you handle transactions that span multiple secondary ports (e.g., save to PostgreSQL AND publish to Kafka)?**

A: You don't — this is the fundamental constraint. A hexagonal port call is a single I/O operation. For atomic multi-port operations: (1) Use the outbox pattern — write to PostgreSQL and the outbox table in one transaction; a separate process reads the outbox and publishes to Kafka. (2) Use sagas — each step has a compensating action if subsequent steps fail. (3) For read-after-write consistency, use a materialized view or read from the same database. Hexagonal architecture doesn't solve distributed transactions — it makes explicit that they don't exist.

---

## Hands-On Exercises

### Exercise 1: Build a Complete Hexagonal Service

Create a `payment-api` service with:
- Domain: `Payment` aggregate with `Pending → Authorized → Captured → Refunded` lifecycle
- Primary port: `PaymentService` interface with `Authorize`, `Capture`, `Refund` methods
- Secondary ports: `PaymentRepository`, `PaymentGateway` (external payment processor)
- Inbound adapter: Chi HTTP handler with routes for all three operations
- Outbound adapters: PostgreSQL repository, stub payment gateway (for development)
- Full test suite: domain unit, service with stubs, handler with httptest

Time: 90 minutes.

### Exercise 2: Swap a Storage Adapter

Given a working PostgreSQL-backed order service, swap the storage adapter to an in-memory map-based implementation. Then swap it to Redis. Measure:
- Code changes required (should be zero in domain/service/handler layers)
- Performance difference (benchmarks)
- Any behavioral differences (should be none)

Time: 45 minutes.

### Exercise 3: Add a Second Inbound Protocol

Add a gRPC adapter to an existing REST-based hexagonal service. The business logic must remain identical — only the transport changes. Implement:
- Protobuf service definition
- gRPC server adapter (implements same `OrderService` interface)
- gRPC → domain command mapping
- Integration test that proves REST and gRPC behave identically

Time: 60 minutes.

### Exercise 4: Port Segregation Refactoring

Given a monolithic `CustomerRepository` interface with 22 methods, refactor it into role-specific interfaces:
- `CustomerFinder` (read operations)
- `CustomerSaver` (write operations)
- `CustomerDeactivator` (specialized operation)
- `CustomerBulkImporter` (bulk operations)

Update all service structs to depend only on the interfaces they use. Verify that changing one interface doesn't break unrelated services.

Time: 40 minutes.

### Exercise 5: Health Check Dashboard

Implement a `/health` endpoint that aggregates health from all outbound adapters. Each adapter must implement:
```go
type HealthChecker interface {
    HealthCheck(ctx context.Context) error
    Name() string
}
```

Register all adapters automatically. Return JSON with per-adapter status, latency, and last error. Add a goroutine that periodically checks health and exports metrics.

Time: 35 minutes.

---

## Advanced Challenges

### Challenge 1: Multi-Tenant Adapter Router

Implement an outbound adapter that routes to different database connections based on tenant ID. The domain knows nothing about tenancy — it's implemented entirely in the adapter layer.

Requirements:
- Single `OrderRepository` interface implementation
- Routes to tenant-specific database pools
- Connection pool management per tenant
- Tenant resolution from context (injected by middleware)
- Zero changes to domain or application layers

### Challenge 2: Adapter Canary Deploy

Implement a "comparing adapter" that shadows traffic to two different backends simultaneously, compares results, and reports discrepancies:

```go
type ComparingOrderRepository struct {
    primary   order.Repository  // PostgreSQL
    shadow    order.Repository  // CockroachDB (migration target)
    differ    Differ            // Reports differences
}

func (c *ComparingOrderRepository) FindByID(ctx context.Context, id order.OrderID) (*order.Order, error) {
    // Query both, return primary result, report if different
    primary, err := c.primary.FindByID(ctx, id)

    go func() {
        shadow, shadowErr := c.shadow.FindByID(context.Background(), id)
        if !reflect.DeepEqual(primary, shadow) {
            c.differ.Report(ctx, Difference{Primary: primary, Shadow: shadow})
        }
    }()

    return primary, err
}
```

### Challenge 3: Zero-Allocation Domain Messaging

Design a port interface system that allows domain events to flow from aggregates to outbound adapters with zero heap allocations in the hot path. Use sync.Pool for event structs, pre-allocated buffers for serialization, and channel-based event dispatch. Benchmark and prove zero-alloc behavior.

---

## Key Insights

1. **Hexagonal architecture is about dependency direction, not file layout.** You can have perfectly named `domain/`, `ports/`, `adapters/` directories and still violate the architecture if your domain imports `database/sql`. The directory structure is a convention; the import direction is the rule.

2. **Go makes hexagonal architecture feel native.** Implicit interfaces, consumer-side interface definition, and explicit constructor injection align perfectly with hexagonal principles. This is not an imported Java pattern — it's a Go pattern that happens to share a name with its Java counterpart.

3. **The main.go file IS the composition root.** In Spring, the DI container is a black box. In Go, every dependency wiring decision is visible in `main.go`. This is a feature, not a limitation. It's grep-able, debug-able, and review-able.

4. **Port interfaces should be SMALL.** A repository interface with 35 methods is a monolith masquerading as a port. Apply interface segregation: each consumer defines the exact methods it needs. Go's implicit interfaces make this painless.

5. **Health checks are not optional in hexagonal architecture.** With multiple outbound adapters, you need to know which one is failing. Implement `HealthChecker` on every adapter and aggregate them in the health endpoint. This is one of the few places where a "meta-adapter" pattern makes sense.

6. **Don't create ports for single-implementation, single-future dependencies.** If you know you'll never swap PostgreSQL, skip the repository interface — use the concrete struct. Hexagonal architecture is about managing change. If there's no change risk, there's no need for the abstraction.

7. **The outbox pattern is hexagonal architecture's distributed transaction.** You cannot atomically save to a database AND publish to Kafka across two different secondary ports. The outbox pattern solves this by making the database the source of truth and the event publication an eventually consistent side effect.
