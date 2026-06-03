# Session 19: Service Layer — Business Logic & Orchestration

## Why This Topic Exists

HTTP handlers parse requests and write responses. Databases store and retrieve data. The space between them — the service layer — is where the business lives. It validates business rules, orchestrates multiple repositories, enforces transactional boundaries, emits domain events, and calls external services. Get this layer wrong, and your codebase becomes a tangle of handler functions with embedded SQL, duplicated validation, and impossible-to-test business logic.

Go lacks the annotation-driven service patterns of Java (no `@Transactional`, no `@Service`, no `@Autowired`). This is a feature, not a bug. Explicit service construction, explicit transaction management, and explicit dependency injection make Go service layers more readable, more testable, and more debuggable than their annotation-heavy counterparts. But the patterns must be learned — there is no framework to enforce them.

As a Staff/Principal Engineer, you will define the service layer patterns for your organization. You will decide between the "Service Interface + Impl" pattern and the "Use Case" pattern. You will design the transaction management strategy that prevents data corruption. You will implement idempotency for payment processing. You will orchestrate sagas across multiple services. This session provides the architectural reasoning and concrete Go code for all of these decisions.

---

## Mental Model

### The Service Layer as a Transaction Script vs Domain Model Spectrum

Service layers exist on a spectrum:

```
Transaction Script ←————————————————————————————→ Domain Model
(procedural)                                        (object-oriented)
     │                                                    │
     │  func PlaceOrder(cmd) {                      │  order.Place(cmd)
     │    validate(cmd)                              │  order.Emit(OrderPlaced)
     │    tx.Begin()                                  │  repo.Save(order)
     │    repo.SaveOrder(order)                      │
     │    repo.UpdateInventory(items)                │
     │    eventBus.Publish(OrderPlaced)              │
     │    tx.Commit()                                 │
     │  }                                             │
```

**Transaction Script** (left): The service function orchestrates everything. It reads a command, validates, calls repositories in order, manages the transaction, and publishes events. Business rules are in if/else blocks. Simple, explicit, easy to understand — but the business logic lives in procedural code that's hard to unit test in isolation from the orchestration.

**Domain Model** (right): The domain entity encapsulates business rules. `order.Place()` validates the transition, applies the change, and records the `OrderPlaced` event. The service calls the domain method, then persists. Business logic is testable without databases or HTTP — just create an `Order` instance, call `.Place()`, and assert on the resulting events and state.

Most Go services sit in the middle: a service function that calls domain methods, manages transactions, and coordinates repositories. The critical decision is WHERE business rules live — in the service orchestration function or in the domain entity.

### The Build-Compose-Execute Pattern

Every service operation follows a three-phase structure:

```
1. BUILD phase:
   ├─→ Parse and validate input (command/request DTO)
   ├─→ Load required data from repositories (aggregates, reference data)
   └─→ Construct domain objects from loaded data

2. COMPOSE/EXECUTE phase:
   ├─→ Call business logic methods on domain objects
   ├─→ Validate business rules (state transitions, invariants)
   └─→ Generate domain events for state changes

3. PERSIST phase:
   ├─→ Save modified aggregates to repositories
   ├─→ Commit transaction (all-or-nothing)
   └─→ Publish domain events (after commit, for at-least-once delivery)
```

This pattern separates mechanical orchestration (loading, saving, committing) from business logic (state transitions, validations, event generation). The mechanical parts are tested with integration tests. The business logic is tested with fast unit tests.

### The Explicit Dependency Principle

In Go, services receive their dependencies explicitly through constructor injection:

```go
func NewOrderService(
    orderRepo OrderRepository,
    inventoryRepo InventoryRepository,
    paymentGateway PaymentGateway,
    eventBus EventBus,
) *OrderService {
    return &OrderService{
        orderRepo:     orderRepo,
        inventoryRepo: inventoryRepo,
        paymentGateway: paymentGateway,
        eventBus:      eventBus,
    }
}
```

No DI framework. No reflection. No annotation scanning. Just a constructor function that takes interfaces and returns a struct. This makes:
- Dependencies visible (read the constructor signature)
- Testing trivial (pass mocks)
- Lifecycle explicit (no `@PostConstruct` magic)
- Dead code obvious (if nothing calls `NewOrderService`, the linter flags it)

---

## Internal Architecture

### Service Interface Pattern: Exported Interface + Unexported Struct

The most common Go service pattern:

```go
// Exported interface — used by consumers (handlers, other services)
type OrderService interface {
    CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error)
    GetOrder(ctx context.Context, orderID string) (*Order, error)
    CancelOrder(ctx context.Context, orderID string) error
}

// Unexported struct — implementation detail
type orderService struct {
    orderRepo     OrderRepository
    inventoryRepo InventoryRepository
    paymentClient  PaymentClient
    eventBus      EventBus
}

// Constructor returns the interface type
func NewOrderService(
    orderRepo OrderRepository,
    inventoryRepo InventoryRepository,
    paymentClient PaymentClient,
    eventBus EventBus,
) OrderService {
    return &orderService{
        orderRepo:     orderRepo,
        inventoryRepo: inventoryRepo,
        paymentClient:  paymentClient,
        eventBus:      eventBus,
    }
}

// Implementation methods on the unexported struct
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    // ...
}
```

**Why exported interface + unexported struct?**

1. **Testing**: Handlers depend on `OrderService` (interface), not `*orderService` (struct). Tests inject a mock `OrderService` without knowing implementation details.

2. **Abstraction**: The interface defines the contract. The struct implements it. Changing the implementation (e.g., switching from PostgreSQL to MySQL) doesn't affect consumers.

3. **Compiler enforcement**: `var _ OrderService = (*orderService)(nil)` at package level ensures the struct satisfies the interface at compile time.

4. **No accidental coupling**: Consumers can't access unexported fields or methods of `orderService` — they only see the interface methods.

**Counterpoint — when NOT to use the interface pattern:**

If a service has one implementation and will never have another, the interface is unnecessary indirection:

```go
// Simpler: export the struct directly
type OrderService struct {
    orderRepo OrderRepository
    // ...
}

func NewOrderService(...) *OrderService {
    return &OrderService{...}
}
```

Teams debate this. The pragmatic rule: if the service is consumed by multiple packages (handlers in different modules), use an interface. If consumed only within its own package, export the struct. Err on the side of interfaces for services that cross module boundaries.

### Constructor Injection: Explicit Dependencies

```go
func NewOrderService(
    orderRepo OrderRepository,
    inventoryRepo InventoryRepository,
    paymentGateway PaymentGateway,
    eventBus EventBus,
) *OrderService {
    if orderRepo == nil {
        panic("NewOrderService: orderRepo is required")
    }
    if inventoryRepo == nil {
        panic("NewOrderService: inventoryRepo is required")
    }
    return &OrderService{
        orderRepo:     orderRepo,
        inventoryRepo: inventoryRepo,
        paymentGateway: paymentGateway,
        eventBus:      eventBus,
    }
}
```

**Constructor rules:**

1. **Validate required dependencies at construction time**: Panic on nil required dependencies. These panics fire at startup (in `main()`), not during request processing. A service started with missing dependencies is a broken service — fail fast, fail loud.

2. **Accept interfaces, not concrete types**: `OrderRepository` is an interface. `*postgres.OrderRepo` is a concrete type. The constructor takes the interface, making the service testable and decoupled from the database.

3. **Default optional dependencies**: For optional dependencies (e.g., metrics, optional caches), provide sensible defaults:

```go
func NewOrderService(orderRepo OrderRepository, opts ...Option) *OrderService {
    s := &OrderService{
        orderRepo: orderRepo,
        logger:    slog.Default(), // Default logger
    }
    for _, opt := range opts {
        opt(s)
    }
    return s
}

type Option func(*OrderService)

func WithLogger(logger *slog.Logger) Option {
    return func(s *OrderService) {
        s.logger = logger
    }
}
```

### Use Case Pattern: Single-Purpose Struct with Execute Method

The Use Case pattern (from Clean Architecture) is an alternative to the Service Interface pattern:

```go
// A use case is a single-purpose struct
type CreateOrderUseCase struct {
    orderRepo     OrderRepository
    inventoryRepo InventoryRepository
    paymentClient  PaymentClient
    eventBus      EventBus
}

// Single Execute method
func (uc *CreateOrderUseCase) Execute(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    // All logic for creating an order, start to finish
}

// Constructor with only the dependencies this use case needs
func NewCreateOrderUseCase(
    orderRepo OrderRepository,
    inventoryRepo InventoryRepository,
    paymentClient PaymentClient,
    eventBus EventBus,
) *CreateOrderUseCase {
    return &CreateOrderUseCase{
        orderRepo:     orderRepo,
        inventoryRepo: inventoryRepo,
        paymentClient:  paymentClient,
        eventBus:      eventBus,
    }
}
```

**When to use Use Case vs Service Interface:**

| Aspect | Service Interface | Use Case |
|--------|------------------|----------|
| Number of methods | Multiple (one "service") | One (one "use case") |
| Dependencies | All methods share deps | Each use case has only its deps |
| Testing | Mock entire service | Mock only use-case-specific deps |
| Complexity | Good for simple CRUD | Good for complex workflows |
| Naming | `OrderService.Create()` | `CreateOrderUseCase.Execute()` |
| File organization | One file per service | One file per use case |

Use Case pattern shines when a single "service" would have 15+ methods with disjoint dependency sets. A `CreateOrderUseCase` needs inventory and payment. A `GetOrderUseCase` only needs the order repo. Giving `GetOrder` the payment client is unnecessary coupling.

### Transaction Orchestration

Go has no `@Transactional`. You manage transactions explicitly. Three patterns exist:

**Pattern 1: Transaction passed as parameter (most explicit)**

```go
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    tx, err := s.db.BeginTx(ctx, nil)
    if err != nil {
        return nil, fmt.Errorf("begin tx: %w", err)
    }
    defer tx.Rollback() // No-op if Commit succeeds

    order, err := s.orderRepo.Create(ctx, tx, cmd.ToOrder())
    if err != nil {
        return nil, fmt.Errorf("create order: %w", err)
    }

    if err := s.inventoryRepo.Reserve(ctx, tx, cmd.Items); err != nil {
        return nil, fmt.Errorf("reserve inventory: %w", err)
    }

    if err := tx.Commit(); err != nil {
        return nil, fmt.Errorf("commit tx: %w", err)
    }
    return order, nil
}
```

The repository methods accept `*sql.Tx` (or an interface like `DBTX`):

```go
type OrderRepository interface {
    Create(ctx context.Context, tx DBTX, order Order) (*Order, error)
    GetByID(ctx context.Context, tx DBTX, id string) (*Order, error)
}

type DBTX interface {
    ExecContext(ctx context.Context, query string, args ...interface{}) (sql.Result, error)
    QueryContext(ctx context.Context, query string, args ...interface{}) (*sql.Rows, error)
    QueryRowContext(ctx context.Context, query string, args ...interface{}) *sql.Row
}
```

Both `*sql.DB` and `*sql.Tx` implement `DBTX`, so repository methods work with or without transactions.

**Pattern 2: Transaction in context (less explicit, more convenient)**

```go
type txKey struct{}

func WithTx(ctx context.Context, tx *sql.Tx) context.Context {
    return context.WithValue(ctx, txKey{}, tx)
}

func TxFromContext(ctx context.Context) (*sql.Tx, bool) {
    tx, ok := ctx.Value(txKey{}).(*sql.Tx)
    return tx, ok
}

// Repository implementation:
func (r *postgresOrderRepo) Create(ctx context.Context, order Order) (*Order, error) {
    tx, inTx := TxFromContext(ctx)
    var db DBTX = r.db
    if inTx {
        db = tx
    }
    // Use `db` for queries
}
```

The service puts the transaction in the context, and repositories pull it out. This is less explicit (the function signature doesn't show a transaction parameter) but more convenient (repos don't need a `tx` parameter).

**Which pattern to choose:**
- **Pass tx explicitly**: When you want compile-time guarantees that a method is called within a transaction. The `tx DBTX` parameter documents the requirement.
- **tx in context**: When repositories are deeply nested and passing `tx` through 5 layers of function calls is noisy. The trade-off is reduced explicitness.

**Pattern 3: Transaction callback (functional approach)**

```go
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    var order *Order
    err := s.transactionManager.WithTransaction(ctx, func(tx *sql.Tx) error {
        var innerErr error
        order, innerErr = s.orderRepo.Create(ctx, tx, cmd.ToOrder())
        if innerErr != nil {
            return innerErr
        }
        return s.inventoryRepo.Reserve(ctx, tx, cmd.Items)
    })
    return order, err
}

type TransactionManager struct {
    db *sql.DB
}

func (tm *TransactionManager) WithTransaction(ctx context.Context, fn func(tx *sql.Tx) error) error {
    tx, err := tm.db.BeginTx(ctx, nil)
    if err != nil {
        return fmt.Errorf("begin tx: %w", err)
    }
    defer func() {
        if p := recover(); p != nil {
            tx.Rollback()
            panic(p) // Re-panic after rollback
        }
    }()
    if err := fn(tx); err != nil {
        tx.Rollback()
        return err
    }
    return tx.Commit()
}
```

This encapsulates the `Begin/Commit/Rollback` boilerplate. The service provides a function, and the transaction manager wraps it in a transaction. If the function panics, the transaction is rolled back before the panic propagates.

### Domain Events: Publishing After State Changes

```go
type EventBus interface {
    Publish(ctx context.Context, events ...DomainEvent) error
}

type DomainEvent interface {
    EventName() string
    OccurredAt() time.Time
    AggregateID() string
}

// Concrete event:
type OrderPlaced struct {
    OrderID   string
    UserID    string
    Total     Money
    Timestamp time.Time
}

func (e OrderPlaced) EventName() string    { return "order.placed" }
func (e OrderPlaced) OccurredAt() time.Time { return e.Timestamp }
func (e OrderPlaced) AggregateID() string   { return e.OrderID }
```

**When to publish events:**

The critical rule: **publish events AFTER the transaction commits, not before.** If you publish before commit and the commit fails, the event was emitted for a state change that never happened. If you publish after commit, the event is emitted for an actual state change. This guarantees at-least-once delivery: if the publish fails after commit, the state change is recorded but the event is lost. For most systems, this is acceptable — idempotent consumers and idempotency keys handle duplicates.

```go
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    tx, err := s.db.BeginTx(ctx, nil)
    if err != nil {
        return nil, err
    }
    defer tx.Rollback()

    order, err := s.orderRepo.Create(ctx, tx, cmd.ToOrder())
    if err != nil {
        return nil, err
    }

    if err := tx.Commit(); err != nil {
        return nil, err
    }

    // Publish AFTER commit success
    event := OrderPlaced{
        OrderID: order.ID,
        UserID:  cmd.UserID,
        Total:   cmd.Total,
        Timestamp: time.Now(),
    }
    if err := s.eventBus.Publish(ctx, event); err != nil {
        // Log and continue — order is already persisted
        s.logger.Error("failed to publish OrderPlaced event", "order_id", order.ID, "error", err)
        // Optionally: store event in outbox table for later retry
    }

    return order, nil
}
```

**The outbox pattern** solves the dual-write problem (database write + event publish must be atomic):

```go
// Instead of publishing directly, write the event to an outbox table in the same transaction
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    tx, _ := s.db.BeginTx(ctx, nil)
    defer tx.Rollback()

    order, _ := s.orderRepo.Create(ctx, tx, cmd.ToOrder())

    // Write event to outbox table (same transaction)
    event := OrderPlaced{...}
    _ = s.outboxRepo.Store(ctx, tx, event)

    tx.Commit() // Both order and event committed atomically

    // A separate worker polls outbox table and publishes events
}
```

### Idempotency: Execute-Exactly-Once Semantics

For payment processing, order creation, and any operation where duplicates cause financial impact:

```go
type IdempotencyStore interface {
    GetResult(ctx context.Context, key string) ([]byte, bool, error)
    StoreResult(ctx context.Context, key string, result []byte) error
}

func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    if cmd.IdempotencyKey == "" {
        return nil, fmt.Errorf("idempotency key is required")
    }

    // Check if we've already processed this request
    cachedResult, exists, err := s.idempotencyStore.GetResult(ctx, cmd.IdempotencyKey)
    if err != nil {
        return nil, fmt.Errorf("check idempotency: %w", err)
    }
    if exists {
        var order Order
        json.Unmarshal(cachedResult, &order)
        return &order, nil // Return cached result
    }

    // Process normally
    order, err := s.createOrderInternal(ctx, cmd)
    if err != nil {
        return nil, err
    }

    // Cache result for future identical requests
    resultBytes, _ := json.Marshal(order)
    if err := s.idempotencyStore.StoreResult(ctx, cmd.IdempotencyKey, resultBytes); err != nil {
        s.logger.Error("failed to store idempotency result", "key", cmd.IdempotencyKey, "error", err)
        // Non-fatal: order was created, cache failed. Next retry will create duplicate.
    }

    return order, nil
}
```

**Race condition protection**: If two requests with the same idempotency key arrive simultaneously, both might pass the `exists` check. Protection:

1. **Database unique constraint**: Put a UNIQUE constraint on the idempotency key column. The second insert fails with a duplicate key error — catch it and return the cached result.
2. **Advisory lock**: `SELECT pg_advisory_xact_lock(hashtext(key))` at the start of the transaction — serializes access per key.
3. **Optimistic locking**: Store a version number with the idempotency record. Second write with same version fails.

### Saga Orchestration

A saga coordinates a distributed transaction across multiple services. In Go, implement it as a state machine:

```go
type SagaState string

const (
    SagaPending        SagaState = "pending"
    SagaOrderCreated   SagaState = "order_created"
    SagaPaymentCharged SagaState = "payment_charged"
    SagaInventoryHeld  SagaState = "inventory_held"
    SagaCompleted      SagaState = "completed"
    SagaCompensating   SagaState = "compensating"
    SagaFailed         SagaState = "failed"
)

type CreateOrderSaga struct {
    orderRepo     OrderRepository
    paymentClient  PaymentClient
    inventoryRepo InventoryRepository
}

func (s *CreateOrderSaga) Execute(ctx context.Context, cmd CreateOrderCommand) error {
    saga := &Saga{
        ID:         uuid.New().String(),
        State:      SagaPending,
        Data:       marshalCommand(cmd),
        CreatedAt:  time.Now(),
    }

    err := s.orderRepo.SaveSaga(ctx, saga)
    if err != nil {
        return err
    }

    // Step 1: Create order
    order, err := s.orderRepo.Create(ctx, nil, cmd.ToOrder())
    if err != nil {
        return s.compensate(ctx, saga, err)
    }
    saga.State = SagaOrderCreated
    saga.AddStep("create_order", func() error { return nil }, func() error {
        return s.orderRepo.Delete(ctx, order.ID)
    })
    s.orderRepo.SaveSaga(ctx, saga)

    // Step 2: Charge payment
    payment, err := s.paymentClient.Charge(ctx, cmd.Payment)
    if err != nil {
        return s.compensate(ctx, saga, err)
    }
    saga.State = SagaPaymentCharged
    saga.AddStep("charge_payment", func() error { return nil }, func() error {
        return s.paymentClient.Refund(ctx, payment.ID)
    })
    s.orderRepo.SaveSaga(ctx, saga)

    // Step 3: Hold inventory
    if err := s.inventoryRepo.Hold(ctx, cmd.Items); err != nil {
        return s.compensate(ctx, saga, err)
    }
    saga.State = SagaCompleted
    s.orderRepo.SaveSaga(ctx, saga)

    return nil
}

func (s *CreateOrderSaga) compensate(ctx context.Context, saga *Saga, originalErr error) error {
    saga.State = SagaCompensating
    s.orderRepo.SaveSaga(ctx, saga)

    var compensationErrors []error
    // Execute compensation functions in reverse order
    for i := len(saga.Steps) - 1; i >= 0; i-- {
        if err := saga.Steps[i].Compensate(); err != nil {
            compensationErrors = append(compensationErrors, err)
            s.logger.Error("saga compensation failed",
                "saga_id", saga.ID,
                "step", saga.Steps[i].Name,
                "error", err,
            )
        }
    }

    saga.State = SagaFailed
    s.orderRepo.SaveSaga(ctx, saga)

    if len(compensationErrors) > 0 {
        return fmt.Errorf("saga %s failed: %w (compensation errors: %v)", saga.ID, originalErr, compensationErrors)
    }
    return originalErr
}
```

**Saga design principles:**
1. Each step has a forward action and a compensating action (undo).
2. Steps are recorded in persistent storage — if the process crashes, the saga can be resumed from the last completed step.
3. Compensation functions must be idempotent (they may be called multiple times).
4. Compensation runs in reverse order of execution.
5. If compensation fails, log and alert — manual intervention may be required.

### Error Handling: Sentinel Errors and Wrapping

```go
// Sentinel errors — package-level variables used with errors.Is()
package orders

var (
    ErrNotFound           = errors.New("order not found")
    ErrAlreadyExists      = errors.New("order already exists")
    ErrInvalidTransition  = errors.New("invalid order state transition")
    ErrInsufficientFunds  = errors.New("insufficient funds")
    ErrItemsOutOfStock    = errors.New("one or more items out of stock")
)

// Error wrapping — preserves the original error chain
func (s *orderService) CancelOrder(ctx context.Context, orderID string) error {
    order, err := s.orderRepo.GetByID(ctx, orderID)
    if err != nil {
        if errors.Is(err, ErrNotFound) {
            return err // Re-throw domain error
        }
        return fmt.Errorf("cancel order: get order %s: %w", orderID, err)
    }

    if !order.CanCancel() {
        return fmt.Errorf("order %s: %w", orderID, ErrInvalidTransition)
    }

    err = s.orderRepo.Update(ctx, order)
    if err != nil {
        return fmt.Errorf("cancel order: update order %s: %w", orderID, err)
    }
    return nil
}
```

**Error handling rules for services:**
1. Use `errors.Is()` and `errors.As()` — never compare error strings or cast without `As()`.
2. Wrap errors with `fmt.Errorf("context: %w", err)` to add context at each layer. The handler can unwrap to find the root cause.
3. Define sentinel errors for domain-specific failures. Handlers map them to HTTP status codes.
4. Never return database-specific errors (e.g., `sql.ErrNoRows`) from the service layer — wrap them in domain sentinel errors.
5. Log at the boundaries: service layer logs warnings for business rule violations; handler layer logs errors for unexpected failures.

### Testing Services: All Dependencies Are Interfaces

Since all dependencies are interfaces, testing is straightforward:

```go
func TestCreateOrder_Success(t *testing.T) {
    // Mocks
    orderRepo := new(MockOrderRepository)
    inventoryRepo := new(MockInventoryRepository)
    paymentClient := new(MockPaymentClient)
    eventBus := new(MockEventBus)

    // Setup expectations
    cmd := CreateOrderCommand{
        UserID: "user-1",
        Items:  []OrderItem{{ProductID: "prod-1", Quantity: 2}},
    }

    orderRepo.On("Create", mock.Anything, mock.Anything, mock.Anything).
        Return(&Order{ID: "order-1", Status: "created"}, nil)
    inventoryRepo.On("Reserve", mock.Anything, mock.Anything, cmd.Items).
        Return(nil)
    paymentClient.On("Charge", mock.Anything, mock.Anything).
        Return(&Payment{ID: "pay-1", Status: "charged"}, nil)
    eventBus.On("Publish", mock.Anything, mock.Anything).
        Return(nil)

    // Service with mocks
    svc := NewOrderService(orderRepo, inventoryRepo, paymentClient, eventBus)

    // Execute
    order, err := svc.CreateOrder(context.Background(), cmd)

    // Assert
    require.NoError(t, err)
    assert.Equal(t, "order-1", order.ID)
    assert.Equal(t, "created", order.Status)
    orderRepo.AssertExpectations(t)
    inventoryRepo.AssertExpectations(t)
    paymentClient.AssertExpectations(t)
    eventBus.AssertExpectations(t)
}

func TestCreateOrder_InsufficientFunds(t *testing.T) {
    orderRepo := new(MockOrderRepository)
    paymentClient := new(MockPaymentClient)

    orderRepo.On("Create", mock.Anything, mock.Anything, mock.Anything).
        Return(&Order{ID: "order-1"}, nil)
    paymentClient.On("Charge", mock.Anything, mock.Anything).
        Return(nil, ErrInsufficientFunds)

    svc := NewOrderService(orderRepo, nil, paymentClient, nil)

    _, err := svc.CreateOrder(context.Background(), CreateOrderCommand{
        UserID: "user-1",
    })

    require.Error(t, err)
    assert.True(t, errors.Is(err, ErrInsufficientFunds),
        "error should be ErrInsufficientFunds")
}
```

**Hand-written mock vs testify/mock vs mockery:**

```go
// Hand-written mock — most control, most boilerplate
type MockOrderRepository struct {
    CreateFn func(ctx context.Context, tx DBTX, order Order) (*Order, error)
}

func (m *MockOrderRepository) Create(ctx context.Context, tx DBTX, order Order) (*Order, error) {
    return m.CreateFn(ctx, tx, order)
}

// testify/mock — less boilerplate, reflection-based
type MockOrderRepository struct {
    mock.Mock
}

func (m *MockOrderRepository) Create(ctx context.Context, tx DBTX, order Order) (*Order, error) {
    args := m.Called(ctx, tx, order)
    return args.Get(0).(*Order), args.Error(1)
}
```

Hand-written mocks are type-safe (no reflection, no `interface{}` return values). testify/mock is faster to write. mockery auto-generates testify mocks from interfaces. Choose hand-written for critical interfaces (few callers, must be correct), testify for internal interfaces (many callers, convenience matters).

### Dependency Wiring: The Composition Root

All wiring happens in `main.go` (or a dedicated `wire.go` / `dependencies.go`):

```go
func main() {
    cfg := config.Load()

    // Infrastructure
    db, err := sql.Open("postgres", cfg.DatabaseURL)
    if err != nil {
        log.Fatal(err)
    }
    defer db.Close()

    logger := logging.NewLogger(cfg.ServiceName, cfg.LogLevel)
    eventBus := events.NewRedisEventBus(cfg.RedisURL, logger)

    // Repositories
    orderRepo := postgres.NewOrderRepo(db)
    inventoryRepo := postgres.NewInventoryRepo(db)
    paymentRepo := postgres.NewPaymentRepo(db)

    // External clients
    paymentClient := stripe.NewClient(cfg.StripeKey, logger)

    // Services
    orderSvc := orders.NewService(orderRepo, inventoryRepo, paymentClient, eventBus)
    paymentSvc := payments.NewService(paymentRepo, paymentClient, eventBus)
    inventorySvc := inventory.NewService(inventoryRepo, eventBus)

    // Handlers
    orderHandler := handlers.NewOrderHandler(orderSvc)
    paymentHandler := handlers.NewPaymentHandler(paymentSvc)
    inventoryHandler := handlers.NewInventoryHandler(inventorySvc)

    // Router
    r := chi.NewRouter()
    r.Use(middleware.RequestID)
    r.Use(middleware.RealIP)
    r.Use(middleware.Logger)
    r.Use(middleware.Recoverer)

    r.Route("/api/v1", func(r chi.Router) {
        r.Mount("/orders", orderHandler.Routes())
        r.Mount("/payments", paymentHandler.Routes())
        r.Mount("/inventory", inventoryHandler.Routes())
    })

    // Server
    srv := &http.Server{
        Addr:    ":" + cfg.Port,
        Handler: r,
    }

    logger.Info("starting server", "port", cfg.Port)
    if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
        logger.Error("server error", "error", err)
        os.Exit(1)
    }
}
```

This is the only file where concrete implementations meet interfaces — the Composition Root pattern. Every other file in the codebase depends on interfaces (or concrete types in the same package). This means:
- You can swap PostgreSQL for MySQL by changing one line in main.go
- You can swap Stripe for PayPal by writing a new client and changing one line
- The entire application graph is visible in one file
- No reflection-based DI container is needed

---

## Runtime Behavior

### Transaction Lifecycle with Explicit tx

```
Service.CreateOrder(ctx, cmd)
    │
    ├─→ tx = db.BeginTx(ctx, nil)
    │   │  SQL: BEGIN
    │   │  Returns *sql.Tx (transaction handle)
    │   │  Holds a connection from the pool
    │
    ├─→ defer tx.Rollback()
    │   │  Registers cleanup — does nothing yet
    │
    ├─→ orderRepo.Create(ctx, tx, order)
    │   │  INSERT INTO orders ... RETURNING id
    │   │  Uses tx's connection
    │   │  Changes visible within the transaction
    │   │  NOT visible to other transactions
    │
    ├─→ inventoryRepo.Reserve(ctx, tx, items)
    │   │  UPDATE inventory SET reserved = reserved + ? WHERE product_id = ?
    │   │  Uses tx's connection (same connection)
    │   │  Row-level locks held on inventory rows until COMMIT
    │
    ├─→ (Option A) tx.Commit()
    │   │  SQL: COMMIT
    │   │  All changes durable
    │   │  Connection returned to pool
    │   │  defer tx.Rollback() → no-op (tx already committed)
    │
    ├─→ (Option B) error occurs anywhere above
    │   │  defer tx.Rollback() fires
    │   │  SQL: ROLLBACK
    │   │  All changes discarded
    │   │  Connection returned to pool
    │   │  Error propagated to caller
    │
    └─→ eventBus.Publish(ctx, event)  // AFTER commit
```

**Critical detail**: The `defer tx.Rollback()` runs whether the function returns normally or panics. If `tx.Commit()` succeeded, `Rollback()` is a no-op on the committed transaction (it returns `sql.ErrTxDone`, which is safe to ignore). If any error occurred before `Commit()`, `Rollback()` discards all pending changes and returns the connection to the pool.

### Race Condition in Idempotency Check

```
Request A (key="abc")                Request B (key="abc")
───────────────                     ──────────────
GetResult("abc") → not found
                                     GetResult("abc") → not found
CreateOrder()
                                     CreateOrder()    ← DUPLICATE!
StoreResult("abc", orderA)
                                     StoreResult("abc", orderB)
```

**Mitigation 1 — Unique constraint:**

```sql
CREATE TABLE idempotency (
    key         VARCHAR(255) PRIMARY KEY,
    result      JSONB NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
```

```go
func (s *orderService) createWithIdempotency(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    tx, _ := s.db.BeginTx(ctx, nil)
    defer tx.Rollback()

    order, err := s.orderRepo.Create(ctx, tx, cmd.ToOrder())
    if err != nil {
        return nil, err
    }

    // Store idempotency record in the SAME transaction
    resultJSON, _ := json.Marshal(order)
    _, err = tx.ExecContext(ctx,
        `INSERT INTO idempotency (key, result) VALUES ($1, $2)
         ON CONFLICT DO NOTHING`, // PostgreSQL: skip if duplicate
        cmd.IdempotencyKey, resultJSON)
    if err != nil {
        return nil, fmt.Errorf("store idempotency: %w", err)
    }

    if err := tx.Commit(); err != nil {
        return nil, err
    }
    return order, nil
}
```

This guarantees at-most-one creation: the INSERT into the idempotency table fails with a duplicate key if another request already committed with the same key.

### Dependency Graph Resolution at Startup

Go's explicit wiring means the construction order is visible in main.go:

```
1. Config loaded from env/file
2. Logger created (needs config → no circular deps)
3. Database opened (needs config → no circular deps)
4. Repositories created (needs DB, logger)
5. External clients created (needs config, logger)
6. EventBus created (needs config, logger)
7. Services created (needs repos, clients, eventBus)
8. Handlers created (needs services)
9. Router created (needs handlers)
10. Server started (needs router)
```

The compiler guarantees no circular dependencies because each layer depends only on layers below it. If Service A depends on Service B and Service B depends on Service A, the code won't compile — you'll get an import cycle.

---

## Flow Diagrams

### Service Method Execution Flow

```
Handler receives request
    │
    ▼
handler.CreateOrder(w, r)
    │
    ├─→ Parse request body → CreateOrderCommand
    │
    ├─→ Validate command (required fields, value ranges)
    │   └─→ Invalid? → return 400 error
    │
    ▼
orderService.CreateOrder(ctx, cmd)
    │
    ├─→ (Optional) Idempotency check
    │   └─→ Found cached result? → return cached order
    │
    ├─→ Begin transaction
    │
    ├─→ Validate business rules
    │   ├─→ Check user exists
    │   ├─→ Check items in stock
    │   ├─→ Check payment method valid
    │   └─→ Check total within limits
    │
    ├─→ Create order aggregate
    │   └─→ order := Order{ID: newID(), Status: "pending", ...}
    │
    ├─→ Call domain methods
    │   ├─→ order.Place()
    │   │   └─→ Validate state transition: pending → placed
    │   │   └─→ Record OrderPlaced event
    │   └─→ order.ReserveInventory(items)
    │
    ├─→ Persist changes
    │   ├─→ orderRepo.Save(ctx, tx, order)
    │   ├─→ inventoryRepo.Reserve(ctx, tx, items)
    │   └─→ (If idempotent) idempotencyStore.Store(key, result)
    │
    ├─→ Commit transaction
    │   └─→ Commit fails? → return error, caller retries
    │
    ├─→ Publish domain events (AFTER commit)
    │   └─→ eventBus.Publish(ctx, order.Events()...)
    │   └─→ Publish fails? → log error, continue (order persisted)
    │
    ▼
Return order to handler
    │
    ▼
Handler encodes order as JSON → writes 201 response
```

### Saga State Machine

```
                         ┌──────────┐
                         │  Pending  │
                         └─────┬─────┘
                               │
                     Step 1: CreateOrder
                               │
                    ┌──────────▼──────────┐
                    │   OrderCreated       │
                    └──────────┬──────────┘
                               │
                    Step 2: ChargePayment
                               │
               ┌───────────────┼───────────────┐
               │               │               │
      Success  │        Failure│               │
               │               │               │
    ┌──────────▼──────────┐    │               │
    │   PaymentCharged    │    │               │
    └──────────┬──────────┘    │               │
               │               │               │
     Step 3: HoldInventory     │               │
               │               │               │
    ┌──────────┼──────────┐    │               │
    │          │          │    │               │
Success│  Failure│          │    │               │
    │          │          │    │               │
    ▼          ▼          │    │               │
┌──────────┐ ┌──────────────┐ │               │
│Completed │ │ Compensating │◄┼───────────────┘
└──────────┘ └──────┬───────┘ │
                    │         │
            Reverse steps:    │
            1. RefundPayment  │
            2. DeleteOrder    │
                    │         │
                    ▼         │
               ┌──────────┐   │
               │  Failed   │◄──┘
               └──────────┘
```

---

## Source Code Reading Guide

This session covers architectural patterns, not a specific Go package. The source code to study is Go standard library and idiomatic open source projects:

**Reading order (estimate: 3-4 hours):**

1. **`database/sql/sql.go:SqlDB.BeginTx()`** — Understand how `*sql.Tx` is created, what isolation levels are supported, and how connections are managed. Pay attention to `BeginTx(ctx, opts)` vs `Begin()`.

2. **`database/sql/sql.go:Tx.Commit()` and `Tx.Rollback()`** — Understand the lifecycle of a transaction. Note that `Rollback()` after `Commit()` returns `sql.ErrTxDone` (safe to ignore).

3. **`context/context.go:WithValue()`** — Understand how context values are stored and retrieved. Why `WithValue` is rarely the right choice for passing dependencies vs request-scoped data.

4. **Go blog: "Error handling and Go"** — Read the canonical error handling article. Understand `errors.Is`, `errors.As`, error wrapping with `%w`, and sentinel errors.

5. **`github.com/google/wire`** (Google's DI tool) — Read the documentation for compile-time dependency injection. Understand the trade-offs between manual wiring (explicit but verbose) and Wire (concise but generated code). Note: This is optional—manual wiring in main.go is simpler and adequate for most projects.

6. **Open source service layers** (look at any well-structured Go project):
   - `github.com/kubernetes/kubernetes` — Look at how controllers orchestrate reconciliation (analogous to service orchestration)
   - `github.com/goharbor/harbor` — Look at service layer in `src/core/api/` and `src/pkg/`
   - Your own project's existing service layer

**What to skip on first read:**
- ORM documentation (GORM, sqlx, etc.) — focus on `database/sql` first
- Event sourcing frameworks — understand the pattern from first principles before using a framework
- CQRS libraries — same as above

---

## Production Failure Scenarios

### Scenario 1: Transaction Leaked Due to Missing Rollback

**What happened:** A service method created a transaction but didn't `defer tx.Rollback()`. An error occurred midway through the method, and the function returned without rolling back. The connection remained in-transaction and was returned to the pool.

```go
// BUG: No defer tx.Rollback()
func (s *service) ProcessPayment(ctx context.Context, paymentID string) error {
    tx, _ := s.db.BeginTx(ctx, nil)
    
    payment, err := s.paymentRepo.GetByID(ctx, tx, paymentID)
    if err != nil {
        return err // ← Transaction NOT rolled back! Connection leaked.
    }
    
    // ... 
    return tx.Commit()
}
```

**Symptom:** After running for several hours, the application started hanging. All database connections were in use — pg_stat_activity showed connections idle in transaction. The connection pool was exhausted.

**Fix:** ALWAYS use `defer tx.Rollback()`:
```go
tx, err := s.db.BeginTx(ctx, nil)
if err != nil {
    return fmt.Errorf("begin tx: %w", err)
}
defer tx.Rollback() // Safe: no-op if Commit succeeds
```

### Scenario 2: Event Published Before Commit — Phantom Notification

**What happened:** A service published an `OrderCreated` event INSIDE the transaction (before commit). The notification service received the event, tried to read the order from the database, and found nothing — the transaction hadn't committed yet. By the time the notification service retried, the transaction had committed. However, another service received the event, processed it, and failed with an optimistic lock error when it tried to update the order (the original transaction hadn't released its row lock yet).

**Symptoms:**
- Notification sent before order visible (confused users: "Your order is ready" → click link → 404)
- Spurious optimistic lock failures in downstream services
- Intermittent failures that disappeared on retry

**Fix:** Publish events AFTER `tx.Commit()`:
```go
tx.Commit()
// Only after commit success:
s.eventBus.Publish(ctx, event)
```

Or use the outbox pattern for exactly-once semantics.

### Scenario 3: Idempotency Key Collision Under Load

**What happened:** A payment service used a simple idempotency implementation: check Redis for the key, if not found, process payment, store result in Redis. Under load (~500 req/s), two concurrent requests with the same key both passed the Redis check (Redis GET returned nil for both), resulting in a double charge.

**Symptoms:**
- Customer charged twice for a single order
- Support tickets from confused users
- Refund processing overhead

**Root cause:** The "check-then-act" pattern in the idempotency layer had no atomicity guarantee. Between the Redis GET (not found) and Redis SET (store result), a second request could also GET (not found).

**Fix:** Use Redis `SETNX` (set-if-not-exists) with TTL, or use a database UNIQUE constraint:
```go
// Redis SETNX — atomic
ok, err := redisClient.SetNX(ctx, key, "processing", 30*time.Second).Result()
if !ok {
    // Key already exists — another request is processing or has processed
    result, _ := redisClient.Get(ctx, key).Result()
    return result, nil
}
// Proceed: this request owns the key for 30 seconds
```

---

## Debugging Techniques

### Technique 1: Tracing Transaction Boundaries

Add transaction-aware logging:

```go
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (_ *Order, err error) {
    tx, txErr := s.db.BeginTx(ctx, nil)
    if txErr != nil {
        return nil, fmt.Errorf("begin tx: %w", txErr)
    }
    defer func() {
        if err != nil {
            s.logger.Error("transaction rolled back",
                "operation", "CreateOrder",
                "error", err,
                "tx_id", fmt.Sprintf("%p", tx),
            )
            tx.Rollback()
        } else {
            s.logger.Info("transaction committed",
                "operation", "CreateOrder",
                "tx_id", fmt.Sprintf("%p", tx),
            )
        }
    }()

    // ... business logic ...

    return order, tx.Commit()
}
```

This logs every transaction begin, commit, and rollback with the transaction pointer (unique for each BeginTx call). In production, search logs for `tx_id` to correlate all operations within a single transaction.

### Technique 2: Testing Service with Delayed/Slow Dependencies

Simulate slow downstream dependencies to test timeout handling:

```go
type SlowRepository struct {
    delay time.Duration
    repo  OrderRepository
}

func (s *SlowRepository) Create(ctx context.Context, tx DBTX, order Order) (*Order, error) {
    select {
    case <-time.After(s.delay):
        return s.repo.Create(ctx, tx, order)
    case <-ctx.Done():
        return nil, ctx.Err()
    }
}

// In test:
func TestCreateOrder_Timeout(t *testing.T) {
    slowRepo := &SlowRepository{delay: 5 * time.Second, repo: realRepo}
    svc := NewOrderService(slowRepo, ...)

    ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
    defer cancel()

    _, err := svc.CreateOrder(ctx, validCommand)
    assert.True(t, errors.Is(err, context.DeadlineExceeded))
}
```

### Technique 3: Service Dependency Graph Visualization

For complex services with many dependencies, generate a DOT graph:

```go
// Run as a test to visualize dependencies
func TestDependencyGraph(t *testing.T) {
    // Build the full service graph (same as main.go)
    orderSvc := orders.NewService(orderRepo, inventoryRepo, ...)
    paymentSvc := payments.NewService(paymentRepo, ...)

    // Generate DOT
    fmt.Println("digraph services {")
    fmt.Println("  rankdir=LR;")
    fmt.Println(`  "OrderService" -> "OrderRepository";`)
    fmt.Println(`  "OrderService" -> "InventoryRepository";`)
    fmt.Println(`  "OrderService" -> "PaymentClient";`)
    fmt.Println(`  "PaymentService" -> "PaymentRepository";`)
    fmt.Println(`  "PaymentService" -> "PaymentClient";`)
    fmt.Println("}")
}

// Run: go test -run TestDependencyGraph | dot -Tpng > deps.png
```

This reveals circular dependencies and unnecessarily deep dependency chains.

---

## Observability Considerations

### Logging

**Service-layer log levels:**

```go
// INFO: Normal operation milestones
s.logger.Info("order created", "order_id", order.ID, "user_id", cmd.UserID)

// WARN: Business rule violations (expected but notable)
s.logger.Warn("order cancelled due to insufficient inventory",
    "order_id", order.ID, "items", cmd.Items)

// ERROR: Unexpected failures (database, external services)
s.logger.Error("failed to charge payment",
    "order_id", order.ID, "error", err, "payment_method", cmd.PaymentMethod)

// DEBUG: Detailed execution trace (disabled in production)
s.logger.Debug("validating order items", "item_count", len(cmd.Items))
```

### Metrics

**Essential service-layer metrics:**

```go
var (
    serviceOperationDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
        Name:    "service_operation_duration_seconds",
        Help:    "Duration of service operations",
        Buckets: prometheus.DefBuckets,
    }, []string{"service", "operation", "status"})

    serviceOperationTotal = promauto.NewCounterVec(prometheus.CounterOpts{
        Name: "service_operation_total",
        Help: "Total service operations",
    }, []string{"service", "operation", "status"})

    transactionDuration = promauto.NewHistogram(prometheus.HistogramOpts{
        Name:    "transaction_duration_seconds",
        Help:    "Duration of database transactions",
        Buckets: []float64{.001, .005, .01, .025, .05, .1, .25, .5, 1, 5},
    })
)

// Usage in service method:
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    start := time.Now()
    defer func() {
        status := "success"
        // err captured in return
        duration := time.Since(start).Seconds()
        serviceOperationDuration.WithLabelValues("orders", "create", status).Observe(duration)
        serviceOperationTotal.WithLabelValues("orders", "create", status).Inc()
    }()
    // ...
}
```

### Traces

**Service-layer spans:**

```go
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    ctx, span := tracer.Start(ctx, "OrderService.CreateOrder",
        trace.WithAttributes(
            attribute.String("user_id", cmd.UserID),
            attribute.Int("item_count", len(cmd.Items)),
        ),
    )
    defer span.End()

    // ... business logic ...

    if err != nil {
        span.RecordError(err)
        span.SetStatus(codes.Error, err.Error())
    }
}
```

Child spans for operations within the service:
```go
// Span for repository call
ctx, repoSpan := tracer.Start(ctx, "OrderRepository.Create")
order, err := s.orderRepo.Create(ctx, tx, cmd.ToOrder())
if err != nil {
    repoSpan.RecordError(err)
}
repoSpan.End()
```

---

## Performance Implications

### N+1 Query Problem in Service Layer

```go
// BAD: N+1 queries
func (s *orderService) ListOrders(ctx context.Context, userID string) ([]OrderDetail, error) {
    orders, err := s.orderRepo.FindByUserID(ctx, userID)
    if err != nil {
        return nil, err
    }

    var details []OrderDetail
    for _, order := range orders {
        // 1 query per order — N+1 problem
        items, _ := s.itemRepo.FindByOrderID(ctx, order.ID)
        details = append(details, OrderDetail{
            Order: order,
            Items: items,
        })
    }
    return details, nil
}

// GOOD: Single query with JOIN or batch fetch
func (s *orderService) ListOrders(ctx context.Context, userID string) ([]OrderDetail, error) {
    // Option A: JOIN in SQL
    return s.orderRepo.FindByUserIDWithItems(ctx, userID)

    // Option B: Batch fetch
    orders, _ := s.orderRepo.FindByUserID(ctx, userID)
    orderIDs := extractIDs(orders)
    items, _ := s.itemRepo.FindByOrderIDs(ctx, orderIDs) // Single query: WHERE order_id IN (...)
    return assembleDetails(orders, items), nil
}
```

### Transaction Duration

Long-running transactions hold row-level locks and connection pool resources. Measure transaction duration and set alerts:

```go
func (s *orderService) CreateOrder(ctx context.Context, cmd CreateOrderCommand) (*Order, error) {
    txStart := time.Now()
    tx, _ := s.db.BeginTx(ctx, nil)
    defer func() {
        txDuration.Observe(time.Since(txStart).Seconds())
    }()
    // ...
}
```

Alert: `histogram_quantile(0.99, rate(transaction_duration_seconds[5m])) > 5` → P99 transaction > 5 seconds.

### Connection Pool Exhaustion

Services that don't close `sql.Rows` or don't roll back transactions leak connections from the pool. Monitor:

```go
dbStats := s.db.Stats()
// dbStats.InUse — connections currently in use
// dbStats.Idle — idle connections in pool
// dbStats.MaxOpenConnections — max pool size
// dbStats.WaitCount — number of waits for connections
// dbStats.WaitDuration — total time spent waiting

metrics.Gauge("db_connections_in_use", float64(dbStats.InUse))
metrics.Gauge("db_connections_idle", float64(dbStats.Idle))
metrics.Counter("db_wait_count", float64(dbStats.WaitCount))
```

Alert: `db_connections_in_use / db_max_open_connections > 0.9` → near pool exhaustion.

---

## Architecture Implications

### Service Granularity

How many methods per service? Signs your service is too large:
- The constructor has 10+ parameters
- Test files exceed 2000 lines
- Methods are grouped into separate "aspect" files within the service package
- You need "God object" mocks that implement 15 methods

Split into focused services or use the Use Case pattern.

Signs your service is too small:
- Every service has exactly one method
- There are 50 service packages in a small application
- Wiring in main.go is 200+ lines of service instantiation

Group related operations into cohesive services.

### Service-to-Service Communication

Within a monolith, services call each other directly (in-process). Between services, use HTTP/gRPC + messaging.

**In-process service call:**
```go
// Payment service calls notification service directly
func (s *paymentService) ProcessPayment(ctx context.Context, paymentID string) error {
    payment, _ := s.paymentRepo.GetByID(ctx, paymentID)
    // ...
    s.notificationSvc.SendReceipt(ctx, payment.UserID, payment)
}
```

**Cross-service call via messaging:**
```go
// Payment service publishes event
func (s *paymentService) ProcessPayment(ctx context.Context, paymentID string) error {
    // ...
    s.eventBus.Publish(ctx, PaymentProcessed{PaymentID: paymentID})
    // Notification service (separate process) subscribes to PaymentProcessed
}
```

---

## Team Ownership Implications

The service layer is where business logic lives. Typically owned by domain teams (orders team, payments team, inventory team) — NOT the platform team.

**Domain teams own:**
- Service interfaces and implementations in their domain package
- Business rule validation
- Transaction orchestration for their domain operations
- Domain event definitions and publishing

**Platform team owns:**
- Base service layer patterns and conventions (documented in team playbook)
- `TransactionManager` / `DBTX` interface (shared infrastructure)
- `EventBus` implementation and client library
- `IdempotencyStore` interface and implementations (Redis, PostgreSQL)
- Service-layer observability middleware (auto-instrumentation)

**Cross-team responsibilities:**
- Service-to-service contracts (handled by the caller's domain team proposing, callee's domain team approving)
- Shared domain event schemas (versioned, coordinated through API working group)
- Error code taxonomy (standardized by the platform team, populated by domain teams)

---

## Interview Questions

### Q1: Why does Go not use `@Transactional` annotations? How do you manage transactions instead?

**Answer:** Go prefers explicitness over magic. Annotations require runtime reflection or compile-time code generation, both of which obscure the control flow. Go services manage transactions explicitly with `db.BeginTx()`, `defer tx.Rollback()`, and `tx.Commit()`. The `defer tx.Rollback()` pattern ensures transactions are always rolled back on error, even on panic. For convenience, you can use a `TransactionManager` with a callback pattern (`WithTransaction(ctx, fn)`) or pass `*sql.Tx` through the call chain. The explicitness means you always know where transactions start and end, and the stack trace for a transaction timeout points directly to the code that held the lock.

### Q2: What's the difference between the Service Interface pattern and the Use Case pattern? When would you choose each?

**Answer:** The Service Interface pattern groups related operations under a single interface (`OrderService` with `Create`, `Get`, `Cancel`, `List`). All methods share the same dependencies. The Use Case pattern creates a separate struct per operation (`CreateOrderUseCase`, `CancelOrderUseCase`), each with its own `Execute()` method and only the dependencies it needs. Choose Service Interface for cohesive operations with similar dependencies (typical CRUD). Choose Use Case for complex workflows with diverse dependency sets, or when following Clean Architecture where the use case is a core abstraction.

### Q3: How do you handle the dual-write problem (writing to database AND publishing an event atomically)?

**Answer:** The outbox pattern: in a single database transaction, write both the domain state change AND an event record to an outbox table. The event bus is not called within the transaction. A separate poller (or change data capture) reads unprocessed events from the outbox table and publishes them to the message broker. This guarantees atomicity: either both writes succeed (transaction commits) or neither does (rollback). The poller provides at-least-once delivery semantics, and consumers must be idempotent.

### Q4: How do you implement idempotency for payment processing?

**Answer:** The client generates a unique idempotency key (UUID) and sends it in the request header (`Idempotency-Key: abc-123`). The server stores the key and the response in a database table with a UNIQUE constraint on the key. On each request: (1) check if the key exists — if yes, return the cached response; (2) if no, process the request and INSERT the key + response in the same transaction as the domain state change. For race conditions (two requests with the same key arriving simultaneously), the UNIQUE constraint guarantees only one INSERT succeeds. The second request catches the duplicate key violation and retries (the cached response now exists).

### Q5: How do you test a service that orchestrates multiple repositories and external services?

**Answer:** All dependencies are interfaces, so inject mocks. Use table-driven tests covering success paths and error paths. For success: set up mock expectations for all repository and external service calls, execute the service method, assert the returned value, and verify all expectations were met (`mock.AssertExpectations(t)`). For errors: simulate each dependency failing (repository returns error, external service times out, business rule violation) and assert the error is wrapped correctly and propagated. Integration tests use a real database (testcontainers or dedicated test DB) and mock only external HTTP services (with `httptest.NewServer`).

---

## Hands-On Exercises

### Exercise 1: Build an Order Service with Transaction Management

**Goal:** Implement a service with explicit transaction management that coordinates multiple repositories.

**Steps:**
1. Define `OrderRepository`, `InventoryRepository`, `PaymentRepository` interfaces with methods accepting `DBTX` (supports both `*sql.DB` and `*sql.Tx`).
2. Implement `OrderService` with a `PlaceOrder(ctx, cmd)` method that:
   - Begins a transaction
   - Validates inventory availability (calls `InventoryRepository`)
   - Creates the order (calls `OrderRepository`)
   - Processes payment (calls `PaymentRepository`)
   - Commits or rolls back based on success
   - Publishes an `OrderPlaced` event after commit
3. Write unit tests with mock repositories covering:
   - Successful order placement (all repos succeed)
   - Inventory shortage (inventory repo returns error → rollback)
   - Payment failure (payment repo returns error → rollback)
   - Commit failure (simulate by mocking the DB)

### Exercise 2: Implement Idempotent Payment Processing

**Goal:** Build an idempotency layer that prevents duplicate payments.

**Steps:**
1. Create an `IdempotencyStore` interface with `GetResult` and `StoreResult` methods.
2. Implement a PostgreSQL-backed idempotency store with UNIQUE constraint on the key.
3. Create a `PaymentService.ProcessPayment(ctx, cmd)` method that:
   - Requires `IdempotencyKey` in the command
   - Checks for cached result before processing
   - Stores result in the same transaction as the payment
   - Returns cached result for duplicate idempotency keys
4. Write tests simulating concurrent requests with the same idempotency key.
5. Test the race condition: two goroutines with the same key calling ProcessPayment simultaneously.

### Exercise 3: Build a Saga Orchestrator

**Goal:** Implement a saga pattern for a distributed order creation workflow.

**Steps:**
1. Define a `SagaStep` interface with `Execute()` and `Compensate()` methods.
2. Implement a `Saga` struct that holds state, step history, and execution data.
3. Implement a `SagaOrchestrator` that:
   - Stores saga state in a database table
   - Executes steps sequentially
   - On failure, runs compensations in reverse order
   - Handles orchestrator crashes mid-saga (resume from last completed step)
4. Implement a specific `CreateOrderSaga` with steps:
   - ReserveInventory → ReleaseInventory
   - ChargePayment → RefundPayment
   - CreateShipment → CancelShipment
5. Test: inject a failure at step 2, verify compensations run for step 1 only.

---

## Advanced Challenges

### Challenge 1: Build a Generic Event-Sourced Service Base

**Goal:** Create a reusable base service that supports event sourcing: append events, rebuild state from events, and publish to event bus.

1. Define generics-based interfaces:
   ```go
   type Aggregate[ID comparable] interface {
       ID() ID
       Version() int
       ApplyEvents(events ...Event) error
       UncommittedEvents() []Event
   }
   ```
2. Implement `EventSourcedRepository[A Aggregate[ID]]` with:
   - `Save(ctx, tx, aggregate A) error` — appends uncommitted events to event store
   - `Load(ctx, id ID) (A, error)` — loads all events, rebuilds aggregate via `ApplyEvents`
3. Handle optimistic concurrency: check `aggregate.Version()` against event store `MAX(version)`.
4. Implement snapshotting: periodically save aggregate state to avoid replaying all events.

**Principal-level aspect:** Design the generics, the concurrency model, and the snapshot strategy. Ensure the API is intuitive for domain teams while preventing incorrect usage (e.g., forgetting to call ApplyEvents after loading).

### Challenge 2: Implement a Distributed Transaction Coordinator with Two-Phase Commit

**Goal:** Build a coordinator that orchestrates 2PC across multiple services using their own databases (no global transaction manager).

1. Define a `Coordinator` interface: `Prepare(ctx, txID) error` and `Commit(ctx, txID) error` and `Rollback(ctx, txID) error`.
2. Each participant registers with the coordinator and implements:
   - `Prepare(ctx, txID) (bool, error)` — execute locally, report ready/abort
   - `Commit(ctx, txID) error` — make local changes durable
   - `Rollback(ctx, txID) error` — discard local changes
3. Handle failures at each phase:
   - Participant fails Prepare → coordinator sends Rollback to all
   - Coordinator fails after Prepare, before Commit → on recovery, check participant statuses
   - Participant fails during Commit → coordinator retries Commit
4. Persist coordinator state to survive crashes.

**Principal-level aspect:** 2PC is rare in Go microservices (sagas are preferred), but understanding the failure modes and recovery strategies is essential for distributed systems design. This challenge forces you to handle network partitions, crash recovery, and idempotency at scale.

---

## Key Insights

- Explicit transaction management with `defer tx.Rollback()` is Go's answer to `@Transactional`. The pattern is verbose but transparent — you always know where transactions start, what they encompass, and when they end. Never omit `defer tx.Rollback()`.

- The Service Interface + Unexported Struct pattern (`type OrderService interface { ... }; type orderService struct { ... }`) provides compile-time interface guarantees, testability via mocking, and prevents accidental coupling to implementation details. Use it for services consumed across package boundaries.

- Domain events MUST be published AFTER `tx.Commit()`, not before. Publishing before commit creates phantom notifications (event received, state not yet persisted) and stale reads (downstream service reads pre-commit state). The outbox pattern solves the atomic dual-write problem.

- Idempotency requires atomic check-and-set. A simple cache-check-then-process approach has a race condition. Use database UNIQUE constraints, Redis `SETNX`, or advisory locks to guarantee exactly-once processing for concurrent requests with the same idempotency key.

- Saga compensation functions must be idempotent. They may run multiple times (on retry, on crash recovery). Compensations run in reverse order of execution. If a compensation fails, log and escalate — do not silently swallow compensation failures.

- Error wrapping with `fmt.Errorf("context: %w", err)` preserves the error chain. Handlers use `errors.Is()` to check for sentinel errors (`ErrNotFound`, `ErrInsufficientFunds`) and map them to HTTP status codes. Database-specific errors (`sql.ErrNoRows`) never escape the service layer.

- The composition root (`main.go`) is the only place where concrete implementations are wired to interfaces. Every other file depends on abstractions. This makes the dependency graph visible, swappable, and testable — no DI framework required.
