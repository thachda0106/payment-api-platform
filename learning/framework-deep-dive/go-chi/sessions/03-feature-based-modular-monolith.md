# Session 3: Feature-Based & Modular Monolith Architecture in Go

## Why This Topic Exists

The layered architecture works until it doesn't. Around the 30-domain-type mark, the `handler/`, `service/`, and `repository/` packages each become dumping grounds with 30+ files. Engineers can't find code without grep. Adding a field to a domain type requires touching 5 files across 5 packages. Code ownership is impossible to assign because every package contains slices of every feature.

Feature-based architecture solves this by inverting the organizational axis: organize by **what the system does** (features/domains) rather than **how it does it** (layers). This is the architectural style of choice for monoliths that will eventually be decomposed, and for teams of 5-50 engineers working in a shared codebase.

The modular monolith takes this further: it applies module boundaries (separate `go.mod` files) to feature boundaries, creating the option to extract services later without requiring a rewrite. Each module has its own dependency graph, its own version, and its own release cadence, but they all deploy together as one binary during the monolith phase.

At the Staff/Principal level, you need to understand:

1. **The trade-off between cohesion and coupling** — feature-based maximizes cohesion within a feature at the risk of duplication across features
2. **How to enforce module boundaries in Go** — no ArchUnit, no Bazel visibility rules; Go enforces boundaries through packages and modules
3. **When to modularize and when to merge** — premature modularization is as harmful as excessive coupling
4. **The shared kernel problem** — what code truly belongs everywhere vs. what is premature generalization
5. **The migration path** — how to move from layered to feature-based without a rewrite

## Mental Model

### What Is a "Feature"?

A feature is a vertical slice of functionality that delivers business value independently. It is NOT a technical layer. It is NOT a database table. It is NOT a REST endpoint.

```
FEATURE: "User Registration"
Includes:
  - POST /register endpoint (handler)
  - Email validation logic (service)
  - Password hashing (service)
  - User creation in database (repository)
  - Welcome email sending (service)
  - Audit log entry (service)

Does NOT include:
  - Payment processing (different feature: "Payments")
  - Order fulfillment (different feature: "Orders")
  - Admin dashboard (different feature: "Admin")

THE TEST: Can a single team own this feature end-to-end?
  YES - It's a feature package.
  NO  - It might be two features, or shared infrastructure.
```

### Feature-Based vs. Layered: Visual Comparison

```
LAYERED (Horizontal)               FEATURE-BASED (Vertical)
=================================== ====================================
internal/                          internal/
  handler/                           users/
    user_handler.go                    handler.go
    order_handler.go                   service.go
    payment_handler.go                 postgres.go
  service/                             models.go
    user_service.go                    dto.go
    order_service.go                   events.go
    payment_service.go               orders/
  repository/                          handler.go
    user_repo.go                       service.go
    order_repo.go                      postgres.go
  model/                               models.go
    user.go                            dto.go
    order.go                         payments/
    payment.go                         handler.go
                                       service.go
To add "user.email" (4 files,          postgres.go
4 packages)                            models.go

                                     To add "user.email" (4 files,
                                     1 package)
```

### The Feature Package Contract

Every feature package has a clear public/private boundary enforced by Go's exported/unexported conventions.

PUBLIC API (exported):
- Models/domain types (User, Order, Payment)
- DTOs used by the HTTP handler (CreateUserInput, UserResponse)
- Service INTERFACE (UserService)
- Domain errors (ErrDuplicateEmail, ErrUserNotFound)
- Event types (UserCreatedEvent, UserUpdatedEvent)

PRIVATE API (unexported):
- Service IMPLEMENTATION (userService struct)
- Repository IMPLEMENTATION (postgresUserRepo struct)
- Handler struct internals
- Internal validation logic
- Database queries (SQL strings)

IMPORT RULES:
- Allowed: common/middleware, common/httputil, common/telemetry
- Allowed: other feature's MODELS only
- Allowed: other feature's DTOs (for cross-feature views)
- Forbidden: other feature's service IMPLEMENTATION
- Forbidden: other feature's repository IMPLEMENTATION
- Forbidden: cmd/ (entry point should not be imported)

## Internal Architecture

### Anatomy of a Feature Package

Each feature package contains all the code for a single domain concept, structured internally with layers but scoped to that concept alone.

**models.go** — Core domain types, pure Go with no serialization or database concerns:
```go
package users

import "time"

type User struct {
    ID           string
    Email        string
    Name         string
    PasswordHash string
    Status       UserStatus
    CreatedAt    time.Time
    UpdatedAt    time.Time
}

type UserStatus string

const (
    StatusActive   UserStatus = "active"
    StatusInactive UserStatus = "inactive"
    StatusPending  UserStatus = "pending"
)
```

**dto.go** — Request/response DTOs with JSON tags, separate from domain models:
```go
package users

type CreateUserInput struct {
    Email    string `json:"email"`
    Password string `json:"password"`
    Name     string `json:"name"`
}

type UserResponse struct {
    ID        string     `json:"id"`
    Email     string     `json:"email"`
    Name      string     `json:"name"`
    Status    UserStatus `json:"status"`
    CreatedAt time.Time  `json:"created_at"`
}

func (i CreateUserInput) ToDomain() *User {
    return &User{
        ID:        uuid.New().String(),
        Email:     i.Email,
        Name:      i.Name,
        Status:    StatusPending,
        CreatedAt: time.Now(),
    }
}

func UserToResponse(u *User) UserResponse {
    return UserResponse{
        ID: u.ID, Email: u.Email, Name: u.Name,
        Status: u.Status, CreatedAt: u.CreatedAt,
    }
}
```

**service.go** — Business logic, interface-based contract:
```go
package users

import "context"

type UserService interface {
    Create(ctx context.Context, input CreateUserInput) (*User, error)
    GetByID(ctx context.Context, id string) (*User, error)
    Update(ctx context.Context, id string, input UpdateUserInput) (*User, error)
    Deactivate(ctx context.Context, id string) error
    List(ctx context.Context, filter UserFilter) ([]User, error)
}

type userService struct {
    repo     UserRepository
    cache    CacheInterface
    events   EventPublisher
    emailSvc EmailService
}

func NewUserService(repo UserRepository, cache CacheInterface, events EventPublisher, emailSvc EmailService) UserService {
    return &userService{repo: repo, cache: cache, events: events, emailSvc: emailSvc}
}

var _ UserService = (*userService)(nil)
```

**postgres.go** — Database access:
```go
package users

type UserRepository interface {
    FindByID(ctx context.Context, id string) (*User, error)
    FindByEmail(ctx context.Context, email string) (*User, error)
    Create(ctx context.Context, user *User) error
    Update(ctx context.Context, user *User) error
    List(ctx context.Context, filter UserFilter) ([]User, error)
}

type postgresUserRepo struct { db *sql.DB }
func NewPostgresUserRepo(db *sql.DB) UserRepository { return &postgresUserRepo{db: db} }
var _ UserRepository = (*postgresUserRepo)(nil)
```

**handler.go** — HTTP handlers, self-registering on Chi router:
```go
package users

type UserHandler struct { svc UserService; logger *slog.Logger }
func NewUserHandler(svc UserService, logger *slog.Logger) *UserHandler {
    return &UserHandler{svc: svc, logger: logger}
}
func (h *UserHandler) Routes() func(r chi.Router) {
    return func(r chi.Router) {
        r.Get("/", h.List)
        r.Post("/", h.Create)
        r.Get("/{id}", h.Get)
        r.Put("/{id}", h.Update)
        r.Delete("/{id}", h.Deactivate)
    }
}
```

**errors.go** — Domain-specific error sentinels:
```go
package users

import "errors"

var (
    ErrNotFound       = errors.New("user not found")
    ErrDuplicateEmail = errors.New("email already exists")
    ErrInvalidEmail   = errors.New("invalid email format")
    ErrUserNotActive  = errors.New("user is not active")
)
```

**events.go** — Feature's published events:
```go
package users

type UserCreatedEvent struct {
    UserID    string    `json:"user_id"`
    Email     string    `json:"email"`
    Timestamp time.Time `json:"timestamp"`
}

type UserDeactivatedEvent struct {
    UserID    string    `json:"user_id"`
    Timestamp time.Time `json:"timestamp"`
}
```

### Cross-Feature Communication Patterns

Feature packages need to communicate. Here are the Go-idiomatic patterns, from most to least preferred:

**PATTERN 1: Import Models Only (Preferred)**
```go
// internal/orders/service.go
import "github.com/company/myapp/internal/users"

func (s *orderService) Create(ctx context.Context, input CreateOrderInput) (*Order, error) {
    // Use users.User type for data; no behavior dependency
}
```
Advantage: Clean, minimal coupling. Only depends on types.
Disadvantage: If you need user behavior (not just data), use Pattern 2.

**PATTERN 2: Service Interface as Dependency**
```go
type orderService struct {
    repo    OrderRepository
    userSvc users.UserService  // Interface, not struct
}

func NewOrderService(repo OrderRepository, userSvc users.UserService) OrderService {
    return &orderService{repo: repo, userSvc: userSvc}
}

func (s *orderService) Create(ctx context.Context, input CreateOrderInput) (*Order, error) {
    user, err := s.userSvc.GetByID(ctx, input.UserID)
    if err != nil {
        return nil, fmt.Errorf("verifying user: %w", err)
    }
    // Continue with order creation...
}
```
Advantage: Full behavior available, still depends only on interface.
Disadvantage: Service methods that only return data are expensive through the full stack.

**PATTERN 3: Events (Async Communication)**
```go
// internal/users/service.go
func (s *userService) Deactivate(ctx context.Context, id string) error {
    s.events.Publish(ctx, UserDeactivatedEvent{UserID: id, Timestamp: time.Now()})
}

// internal/orders/subscriber.go
func (sub *OrderSubscriber) HandleUserDeactivated(ctx context.Context, event UserDeactivatedEvent) error {
    return sub.orderSvc.CancelByUser(ctx, event.UserID)
}
```
Advantage: Loose coupling, async, each feature handles events independently.
Disadvantage: Eventual consistency, harder to debug, needs event infrastructure.

**PATTERN 5: Direct Implementation Import — FORBIDDEN**
```go
// Never do this:
import userImpl "github.com/company/myapp/internal/users"
// Direct access to user repository or unexported functions is forbidden.
```

### The Shared Kernel Pattern in Go

The shared kernel is the most dangerous package in a feature-based architecture. It couples every feature.

WHAT BELONGS IN THE SHARED KERNEL (internal/common/):
- Base value objects: Money, Email, PhoneNumber, TenantID, Address
- Cross-cutting types: Pagination, Sorting, common error types
- HTTP utilities: renderJSON, renderError, request helpers
- Common middleware: RequestID, Logger, Recoverer, RateLimiter
- Telemetry: Tracing setup, metrics registration

WHAT DOES NOT BELONG:
- Feature-specific types (OrderStatus lives in orders/, not common/)
- Business logic (calculateTax belongs in a feature, not shared)
- Configuration that varies by feature
- "We might need it later" types — if only 1-2 features use it, keep it there

THE LITMUS TEST: If removing this type from shared kernel requires changes in 3+ feature packages, it might belong there. Otherwise, keep it in the owning feature.

### Modular Monolith: Module Boundaries

```
services/users/
  go.mod  - module github.com/company/services/users
  go.sum
  cmd/server/main.go  - entry point
  internal/users/
    handler.go, service.go, postgres.go, models.go, dto.go

services/orders/
  go.mod  - module github.com/company/services/orders
  go.sum
  cmd/server/main.go
  internal/orders/
    handler.go, service.go, postgres.go, models.go, dto.go

shared/kit/
  go.mod  - module github.com/company/shared/kit (versioned)
  middleware/, telemetry/, httputil/

KEY RULES:
1. Each module can have its own dependency versions.
2. Modules CANNOT import each other's internal/ packages — compiler-enforced.
3. Modules share code through explicitly versioned shared packages.
4. go.work ties modules together for local development.
5. In production, each service deploys from its go.mod versions.
```

## Runtime Behavior

### Startup: Wiring Multiple Feature Packages

```go
// cmd/server/main.go — Composition root for feature-based architecture
func main() {
    cfg := config.MustLoad()

    // Infrastructure (shared across all features)
    db, _ := sql.Open("postgres", cfg.DatabaseURL)
    redisClient := redis.NewClient(&redis.Options{Addr: cfg.RedisAddr})
    eventBus := events.NewKafkaPublisher(cfg.KafkaBrokers)
    logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

    // Feature: Users
    userRepo := users.NewPostgresUserRepo(db)
    emailSvc := notifications.NewEmailService(cfg.SendgridKey)
    userSvc := users.NewUserService(userRepo, redisClient, eventBus, emailSvc)
    userHandler := users.NewUserHandler(userSvc, logger)

    // Feature: Orders
    orderRepo := orders.NewPostgresOrderRepo(db)
    orderSvc := orders.NewOrderService(orderRepo, userSvc)  // cross-feature dependency
    orderHandler := orders.NewOrderHandler(orderSvc, logger)

    // Feature: Payments
    paymentRepo := payments.NewPostgresPaymentRepo(db)
    paymentSvc := payments.NewPaymentService(paymentRepo, cfg.StripeKey, eventBus)
    paymentHandler := payments.NewPaymentHandler(paymentSvc, logger)

    // Router assembly
    r := chi.NewRouter()
    r.Use(middleware.RequestID, middleware.Logger, middleware.Recoverer)

    r.Route("/api/v1", func(r chi.Router) {
        r.Use(auth.JWTAuth(cfg.JWTSecret))
        r.Route("/users", userHandler.Routes())
        r.Route("/orders", orderHandler.Routes())
        r.Route("/payments", paymentHandler.Routes())
    })

    r.Get("/health", healthCheck(db, redisClient))

    srv := &http.Server{Addr: ":" + cfg.Port, Handler: r}
    gracefulShutdown(srv, 30*time.Second)
    srv.ListenAndServe()
}
```

### Request Flow Across Features

```
POST /api/v1/orders
Body: {"user_id": "uuid-123", "items": [...], "payment_method": "card"}

  Handler Layer (orders/handler.go):
    1. Decode CreateOrderInput
    2. Validate input structure

  Service Layer (orders/service.go):
    1. Verify user exists and is active:
       user, err := s.userSvc.GetByID(ctx, input.UserID)
       -> calls users.UserService interface
       -> users/service.go: checks user status
       -> returns User or error

    2. Calculate order total:
       for each item, fetch product price from productSvc

    3. Create the order:
       order := &Order{...}
       s.repo.Create(ctx, order)

    4. Publish event:
       s.events.Publish(ctx, OrderCreatedEvent{...})

    5. Return order:
       return order, nil

  Key: Cross-feature calls go through SERVICE INTERFACES,
       not through direct database access.
```

### The Monolith That Can Split

```
TODAY (Monolith deployed as one binary):
  go build ./cmd/server
  -> one binary containing users + orders + payments

TOMORROW (Split backend, e.g., payments extracted):
  Step 1: services/payments/cmd/server/main.go already exists
  Step 2: go build ./services/payments/cmd/server
  Step 3: Deploy as separate service
  Step 4: Update router: instead of local paymentSvc, use gRPC client
  Step 5: Remove paymentSvc wiring from main monolith binary

  Zero code changes within the payment feature itself.
  Only wiring changes in main.go.
```

## Request Flow Diagrams

### Feature-to-Feature Communication Flow

```
FEATURE: ORDERS creates an order, needs user validation
==========================================================

orders/handler.go
  -> orders/service.go (CreateOrder)
    -> users.UserService.GetByID (via interface, injected)
      -> users/userService.GetByID (implementation, within users/)
        -> users/postgresUserRepo.FindByID (within users/)
        -> returns *User to orders service
    -> orders/postgresOrderRepo.Create (within orders/)
    -> events.Publish(OrderCreatedEvent)
    -> returns *Order to handler
  -> encode response, return to client

DEPENDENCY DIRECTION:
  orders/ -> users/ (users knows NOTHING about orders)
  This is correct: orders DEPENDS ON users, not the reverse.

VIOLATION (circular dependency):
  If users/ imported orders/ too -> compile error "import cycle"
  Go compiler prevents this automatically.
```

### Event-Driven Feature Communication

```
USER DELETED -> CASCADE CANCEL ORDERS
==========================================================

users/service.go:
  user.Deactivate() -> publishes UserDeactivatedEvent

orders/subscriber.go (in a goroutine, started at boot):
  listens for UserDeactivatedEvent
  receives event -> orders.OrderService.CancelByUser()
  -> database update
  -> publishes OrdersCancelledEvent (consumed by analytics feature)

payments/subscriber.go:
  listens for UserDeactivatedEvent
  -> cancels pending payment authorizations
  -> refunds if necessary

Notification feature:
  listens for UserDeactivatedEvent
  -> sends "account deactivated" email

Each feature reacts INDEPENDENTLY.
No feature knows about the other features' reactions.
Loose coupling via events. Eventually consistent.
```

## Lifecycle Diagrams

### Feature Package Lifecycle

```
DEVELOPMENT LIFECYCLE OF A FEATURE PACKAGE:
==========================================================

Phase 1: Feature package starts SMALL
  internal/newfeature/
    handler.go  (1 endpoint)
    service.go  (1 method)
    models.go   (1 struct)

Phase 2: Feature grows
  - +2 endpoints (handler grows)
  - +3 service methods (service grows)
  - +2 model types (models grows)
  Still manageable: all code in one directory.

Phase 3: Feature gets complex (consider sub-packages)
  internal/newfeature/
    handler.go       (delegates to sub-handlers)
    service.go       (orchestrator)
    models.go        (core types)
    importing/       (sub-domain: data import)
      handler.go, service.go
    exporting/       (sub-domain: data export)
      handler.go, service.go

Phase 4: Feature becomes a module (if separate deploy is needed)
  services/newfeature/
    go.mod
    cmd/server/main.go
    internal/...

Phase 5: Feature becomes a separate service
  - Builds from its own cmd/
  - Communicates via gRPC/REST with other services
  - Has its own database (database-per-service)
  - Deployed independently
```

### Migration Lifecycle: Layered to Feature-Based

```
Phase 1: PREPARATION (no code changes)
  - Identify feature boundaries from existing code
  - Map files to features: user_handler + user_service + user_repo = users feature
  - Identify cross-cutting code: auth middleware, error handling, utilities
  - Create a CODEOWNERS mapping for the target structure

Phase 2: PULL SHARED CODE OUT
  - Extract common/ middleware, utilities, shared types
  - Move to internal/common/ or internal/shared/
  - Update import paths in feature packages
  - Verify: go build ./..., go test ./...

Phase 3: CREATE FEATURE PACKAGES (one at a time)
  - Create internal/users/
  - Move user_*.go files from handler/, service/, repository/ into users/
  - Update package names from "handler"/"service"/"repository" to "users"
  - Update import paths everywhere
  - Add compile-time interface check: var _ UserService = (*userService)(nil)
  - Verify: go build ./..., go test ./...
  - Repeat for each feature

Phase 4: ENFORCE BOUNDARIES
  - Add golangci-lint depguard rules:
    internal/users: must NOT import internal/orders/service
  - Add CODEOWNERS file
  - Merge, deploy, communicate to the team

Phase 5: CREATE MODULE BOUNDARIES (if needed)
  - Extract feature to services/users/go.mod
  - Create go.work at root
  - Update CI to build modules independently
```

## Source Code Reading Guide

### Feature-Based Project — Reading Order

```
myapp/
  go.mod                     (0) Module declaration
  cmd/server/main.go         (1) Entry point, shows all features
  internal/
    common/
      middleware/auth.go      (2) Understand auth model first
      httputil/               (3) How responses/errors are rendered
    users/
      models.go               (4) Domain types (read first in each feature)
      dto.go                  (5) API contracts
      handler.go              (6) HTTP surface (Routes() shows all endpoints)
      service.go              (7) Business logic (interface + implementation)
      postgres.go             (8) Database queries
      errors.go               (9) Error types
      events.go               (10) Published events
    orders/
      models.go               (4') Read only when studying orders feature
      ... (same structure)
    payments/
      ... (same structure)
```

### What to Focus On When Reading Each File Type

```
models.go:
  - Domain types and their relationships
  - Enums/constants
  - Does the model use value objects or primitives?

dto.go:
  - What data enters and leaves the feature?
  - JSON shapes (public API contract)
  - Validation rules (if annotated)

handler.go:
  - What HTTP endpoints exist? (Routes() method)
  - What status codes are returned?
  - How are errors mapped to HTTP responses?

service.go:
  - What's the public interface? (consumed by other features)
  - What cross-feature dependencies exist?
  - What's the business logic?

postgres.go:
  - Repository interface (data access contract)
  - SQL queries (are they parameterized? indexed?)
  - Transaction handling

errors.go:
  - What can go wrong?
  - Are errors distinguishable by type?
```

### What to Ignore

- Test files (read when studying testing patterns)
- Generated code (protobuf, openapi — read the source specs)
- go.sum files (auto-generated checksums)
- Configuration files (environment-specific)

## Production Failure Scenarios

### Scenario 1: The Shared Kernel Becomes a God Package

**Symptom**: Every PR changes something in `internal/common/`. Changing a shared type breaks 6 feature packages. Nobody knows who owns the shared code.

**Root Cause**: Too much code migrated to the shared kernel. Types that belong in specific features were moved to common/ because "another feature might need it."

**Fix**:
1. Audit: list all types in `internal/common/`, count consumers per type
2. For types with 1-2 consumers: move to the primary consuming feature
3. For types with 3+ consumers: keep in shared kernel, but add deprecation review
4. Add a CODEOWNERS rule: `internal/common/ @team-platform` with a policy that changes require 2 feature team approvals

### Scenario 2: Feature Package Becomes a "Mini-Layered" Dumping Ground

**Symptom**: A feature package has 30 files. It's organized by layer again (handler/, service/, repository/ subdirectories within the feature). But the feature is still one package — it's just a horizontal split within a vertical package.

**Root Cause**: The feature is too broad. "Users" might actually be two features: "User Registration & Auth" and "User Profile Management."

**Fix**: Split the feature. Create `internal/user-auth/` and `internal/user-profile/`. Each gets its own handler, service, repository. They share the User model via importing the model from one (likely user-auth as the canonical source).

### Scenario 3: Cross-Feature Import Proliferation

**Symptom**: Almost every feature imports almost every other feature's service interface. The dependency graph is a near-complete graph.

**Root Cause**: Features are too tightly coupled. Business logic that spans features is implemented in the "closest" feature rather than being elevated to an orchestration layer.

**Fix**:
1. Identify the most imported feature (highest fan-in)
2. For read-only dependencies: can consumers use models only instead of service interface?
3. For write dependencies: introduce events instead of synchronous calls
4. Consider an orchestration layer: a saga/process manager that coordinates across features
5. Run `go mod graph` regularly to detect this pattern early

### Scenario 4: The "Extract to Service" Turns Into a Rewrite

**Symptom**: The team decides to extract the payment feature into a separate service. The work takes 3 months because payments imports 8 other features, and 5 other features import payments.

**Root Cause**: The feature was never truly modular. Despite being in its own directory, it's tightly coupled to the rest of the monolith.

**Prevention**: Use module boundaries BEFORE you need to extract. `services/payments/go.mod` forces the compiler to enforce that payments only depends on explicit shared contracts, not on sibling features' internals.

## Debugging Techniques

### Mapping the Feature Dependency Graph

```bash
# List all feature packages
find internal -maxdepth 1 -type d | grep -v "^internal$" | grep -v common

# For each feature, show what other features it imports
for feat in $(find internal -maxdepth 1 -type d | grep -v common); do
    echo "=== $feat imports: ==="
    rg "internal/(users|orders|payments|notifications|billing)" "$feat" --include="*.go" --no-filename -o | sort -u
done

# Detect potential circular dependencies
# (Should be none — Go compiler catches these)
go vet ./...

# Visualize the full import graph
go mod graph | grep "github.com/company/" | sort
```

### Detecting Feature Boundary Violations

```bash
# Check if any feature imports another feature's unexported types
# (Impossible in Go — unexported types can't be imported — but check for
# patterns suggesting the attempt)

# Check if any feature's handler imports database/sql directly
rg "database/sql" internal/*/handler.go

# Check if any feature depends on cmd/
rg "cmd/" internal/ --include="*.go"

# Check shared code for imports from feature packages
rg "internal/(users|orders|payments)" internal/common/ --include="*.go"
```

### Diagnosing Feature Bloat

```bash
# Count files per feature package
for feat in $(find internal -maxdepth 1 -type d ! -name common ! -name internal); do
    count=$(find "$feat" -name "*.go" ! -name "*_test.go" | wc -l)
    echo "$count $feat"
done | sort -rn

# Flag features with >15 non-test Go files as candidates for splitting
```

## Observability Considerations

### Feature-Level Metrics

Each feature should expose its own metrics, namespaced by feature name:

```
METRIC NAMING: {feature}_{operation}_{metric}

users_create_duration_seconds
users_create_errors_total
users_getbyid_duration_seconds
orders_create_duration_seconds
orders_create_errors_total
payments_process_duration_seconds

This enables per-feature dashboards and per-feature alerts.
"Payments p99 latency > 500ms" alerts the Payments team,
not a generic "API latency" alert.
```

### Cross-Feature Trace Visualization

When a request spans features (e.g., POST /orders calls users service), OpenTelemetry spans show the cross-feature dependency:

```
SPAN: POST /api/v1/orders (300ms total)
  SPAN: orderHandler.Create (5ms)
    SPAN: orderService.Create (295ms)
      SPAN: userService.GetByID (20ms)        <- CROSS-FEATURE
        SPAN: postgresUserRepo.FindByID (18ms)
      SPAN: orderService.calculateTotal (50ms)
      SPAN: postgresOrderRepo.Create (15ms)
      SPAN: kafka.Publish OrderCreated (200ms) <- async

Traces make cross-feature dependencies VISIBLE in production.
If orderService.GetByID suddenly takes 500ms, you know the
bottleneck is in the users feature, even though the request
entered through the orders feature.
```

### Feature Health Dashboard (per team)

```
TEAM: Commerce (orders feature)
  Endpoints:
    POST /orders       p50: 45ms  p99: 120ms  errors: 0.01%
    GET /orders/{id}   p50: 12ms  p99: 35ms   errors: 0.00%
    GET /orders         p50: 28ms  p99: 90ms   errors: 0.02%

  Cross-feature dependencies:
    users.GetByID       p50: 5ms   p99: 25ms   errors: 0.00%  [healthy]
    products.GetBySKU   p50: 8ms   p99: 30ms   errors: 0.10%  [degraded]

  Events published:
    OrderCreated:  120/min
    OrderCancelled: 5/min

  Events consumed:
    UserDeactivated: 2/min
```

## Performance Implications

### Cross-Feature Call Overhead

```
CALL TYPE              OVERHEAD        WHEN TO USE
==============================================================
In-process interface    ~0.1ms         Default. Use always within
  (Go function call)                   the same binary.

Database join instead  0ms extra       When two features share
  of cross-feature call                 a database. Faster but
                                       couples schemas.

Event (Kafka async)    ~5-50ms         When eventual consistency
                                       is acceptable.

gRPC (inter-service)   ~1-5ms          When features are separate
                                       services.

REST (inter-service)   ~5-20ms         When HTTP is the only option
                                       between services.
```

### Compile-Time Performance with Feature Packages

```
SINGLE MODULE (all features in one go.mod):
  - Changing users/models.go recompiles: users package + everything
    that imports users (orders, payments, notifications)
  - Typical: 5-15 seconds for incremental build

MULTI-MODULE (each feature has its own go.mod):
  - Changing users/models.go recompiles: users module only
    (if contracts haven't changed)
  - Other modules use cached compiled artifacts
  - Typical: 2-5 seconds for incremental build (per module)

The modular monolith trades compile-time complexity for faster
incremental builds. This matters at 200K+ lines of code.
```

## Architecture Implications

### When NOT to Create Microservices

The feature-based modular monolith is the answer to "we need microservices" for 80% of teams. Here's when NOT to extract:

```
REASON TO STAY MONOLITHIC               COUNTER-SIGNAL
==============================================================
Team is < 15 engineers                  Wait until 20+
Domain is not yet stable                Wait until bounded contexts are clear
No independent scaling needs            If all features scale together, keep together
Deployment is simple (one binary)       If deployment complexity is manageable
Transaction boundaries are unclear      If you need distributed transactions, stay monolithic

REASON TO EXTRACT TO MICROSERVICE       SIGNAL
==============================================================
Team > 15 engineers and splitting       Communication overhead exceeds code coupling cost
Feature has different scaling profile   Payments needs 100 instances, Users needs 2
Feature has different regulatory reqs   PCI-DSS isolation (Session 04 covers this)
Feature has independent deploy cadence  Team wants daily deploys, monolith deploys weekly
Feature uses different tech stack       Rare in Go, but possible (e.g., Python ML service)
```

### Dependency Rule Enforcement in Go

Go has no ArchUnit. Here's how to enforce feature boundaries:

```
LEVEL 1: COMPILER (automatic, free)
  - Cyclic imports: compile error
  - internal/ visibility: compile error
  - Unexported types: compile error when imported externally

LEVEL 2: golangci-lint (CI, requires config)
  - depguard: prevent packages from importing forbidden packages
    Example config:
    linters-settings:
      depguard:
        rules:
          users_internal:
            list-mode: lax
            files:
              - "internal/users/**/*.go"
            allow:
              - "$gostd"
              - "github.com/company/myapp/internal/common"
              # Does NOT list other feature packages
              # Importing internal/orders will fail lint

LEVEL 3: Custom tooling (CI, advanced)
  - Go AST analysis to detect:
    - Handler importing database/sql
    - Service importing net/http
    - Feature importing another feature's unexported symbols
    (Impossible to import unexported symbols, but you can detect
    patterns of tight coupling like direct struct access)

LEVEL 4: CODEOWNERS + PR review (process)
  - CODEOWNERS file maps each feature directory to its team
  - Cross-feature changes require multi-team approval
  - Platform team reviews shared/ changes
```

## Team Ownership Implications

### Feature-Based Team Structure (Inverse Conway Maneuver)

```
ORGANIZATIONAL STRUCTURE             CODE STRUCTURE
================================     ================================
Team Identity (5 engineers)          internal/users/**
  Owns: User lifecycle, auth         internal/auth/**
  Expertise: identity, security
  On-call: Identity rotation

Team Commerce (6 engineers)          internal/orders/**
  Owns: Orders, checkout, cart       internal/cart/**
  Expertise: commerce, inventory     internal/products/**
  On-call: Commerce rotation

Team Payments (5 engineers)          internal/payments/**
  Owns: Payments, settlement
  Expertise: payments, PCI
  On-call: Payments rotation

Team Platform (4 engineers)          internal/common/**
  Owns: Shared infrastructure        infrastructure/**
  Expertise: CI/CD, reliability
  On-call: Platform rotation

KEY PRINCIPLE: One team = one set of feature packages.
If a team owns two unrelated feature packages, consider
splitting the team or merging the features.
```

### Cross-Team Dependency Management

```
WHEN TEAM A (orders) NEEDS SOMETHING FROM TEAM B (users):

1. Check: Is it already in the users.Service interface?
   - YES: Use it. No coordination needed.
   - NO: Continue.

2. Open a PR against users/ adding the needed method to the interface.
   - Team B reviews.
   - If backward-compatible: merge and deploy.
   - If breaking: negotiate timeline.

3. Can't get Team B's approval in time?
   - Duplicate the logic in orders/ (with a TODO to deduplicate).
   - This is BETTER than breaking the architecture.
   - Temporary duplication < permanent wrong coupling.

4. Pattern repeats? (3+ features need the same thing from users)
   - Consider: is this actually a new bounded context?
   - Consider: should this be an event, not a synchronous call?
   - Escalate to architecture review.
```

## Interview Questions

### Q1: "When would you choose feature-based over layered architecture in Go?"

**Answer**: The trigger is not lines of code or number of engineers — it's cognitive load. When developers consistently need grep to find code, when feature changes touch 5+ files across 3+ packages, when code ownership is ambiguous — these signal that layered architecture has exceeded its useful scale. Feature-based organizes code by business capability, which aligns code structure with team structure (Conway's Law). For Go specifically, feature-based avoids the god-package problem where `handler/` and `service/` packages grow to 30+ files that nobody fully understands.

### Q2: "How do you prevent feature packages from becoming tightly coupled?"

**Answer**: Three mechanisms work together. First, Go's compiler prevents circular imports — the dependency graph must be a DAG. Second, features expose only interfaces (not concrete types) for cross-feature consumption. Third, golangci-lint with depguard rules prevents feature packages from importing their siblings' internal implementation details. The architecture rule is: features can import sibling feature's models and service interfaces, but never their repositories or concrete implementations. Cross-feature writes should use events for loose coupling.

### Q3: "What's the difference between a feature package and a bounded context?"

**Answer**: A feature package is a code organization pattern (a directory in your monorepo). A bounded context is a domain modeling concept from DDD (a boundary within which a domain model is consistent). They often align — one bounded context maps to one feature package — but they can diverge. A single bounded context might span multiple feature packages (e.g., "Billing" might span `billing-invoicing/` and `billing-payments/`). Conversely, a feature package might contain multiple bounded contexts if the domain is simple. When they do align 1:1, the feature package IS the bounded context implementation, and the module boundary (`go.mod`) becomes the bounded context boundary.

### Q4: "How do you handle shared database tables across feature packages?"

**Answer**: Ideally, you don't. Each feature package owns its own tables. When Feature B needs data from Feature A's tables, it goes through Feature A's service interface, not through direct SQL. If you must share tables (legacy constraint or performance requirement), define a clear owner (one feature package OWNS the table, others READ via a read-only repository interface defined by the owning feature). Never allow two feature packages to write to the same table. This is the single-writer principle: exactly one feature package is the authority for each data entity.

### Q5: "How do you handle cross-feature transactions in a modular monolith?"

**Answer**: Within a single Go binary and a single database, you can use explicit transaction objects passed through service interfaces. The orchestrating feature creates a transaction and passes it to the participating features' service methods (which accept `*sql.Tx` as a parameter). This works for a monolith with a shared database. For separate modules with separate databases, you need sagas (orchestrated choreographed sequences with compensating actions). The key architectural decision: if you frequently need cross-feature transactions, those features may be a single bounded context that should NOT be separated.

### Q6: "When should you create a go.mod for a feature package?"

**Answer**: Create a separate `go.mod` when: (1) the feature has a different release cadence than the monolith, (2) the feature needs different dependency versions, (3) the feature is a candidate for extraction to a separate service, (4) different teams own different features and you want compiler-enforced boundaries. Do NOT create a `go.mod` for every feature — the overhead of go.work and cross-module coordination exceeds the benefit for small teams. The rule of thumb: if you have more go.mod files than engineers, you have too many.

### Q7: "How do you handle API versioning across feature packages?"

**Answer**: Each feature package owns its own API versioning within the `Routes()` method. Version groups in Chi:

```go
func (h *UserHandler) Routes() func(r chi.Router) {
    return func(r chi.Router) {
        r.Route("/v1", func(r chi.Router) {
            r.Get("/", h.ListV1)
            r.Post("/", h.CreateV1)
        })
        r.Route("/v2", func(r chi.Router) {
            r.Get("/", h.ListV2)
            r.Post("/", h.CreateV2)
        })
    }
}
```

Features can version independently. The users feature can be at v3 while orders is at v1. The monolith router mounts both. When a feature is extracted to a separate service, the API version is already part of its route structure.

## Hands-On Exercises

### Exercise 1: Restructure from Layered to Feature-Based

Take a layered project with 6+ domain types. Restructure it:
1. Identify feature boundaries (which handler+service+repo files belong together)
2. Create feature directories under internal/
3. Move files, update package names
4. Update all import paths
5. Verify: `go build ./...` and `go test ./...`
6. Time yourself: how long did the migration take? How many files changed?

### Exercise 2: Enforce Feature Boundaries with golangci-lint

Add depguard rules to a feature-based project:
1. For each feature package, list its allowed imports
2. Feature packages must NOT import other feature packages' service implementations
3. Feature packages CAN import other feature packages' models
4. Shared/common CANNOT import feature packages
5. Run lint in CI and verify violations are caught

### Exercise 3: Implement Cross-Feature Communication

Given two feature packages (users and orders):
1. Implement Pattern 1: orders imports users models only
2. Implement Pattern 2: orders depends on users.Service interface
3. Implement Pattern 3: orders listens for UserDeactivatedEvent
4. Compare the coupling, testability, and complexity of each

### Exercise 4: Create a Modular Monolith

Starting from Exercise 1's feature-based structure:
1. Extract one feature to its own go.mod
2. Extract shared code to shared/kit/go.mod
3. Create go.work at the root
4. Verify local development works
5. Build each module independently
6. Create a top-level cmd/ that imports and wires all modules

### Exercise 5: Feature Ownership Map

For your current project:
1. Map each feature package to a team
2. Identify cross-feature dependencies
3. Calculate the "communication overhead" for each feature (how many teams need to be involved in a change)
4. Propose structural changes to reduce cross-team dependencies
5. Write the CODEOWNERS file

## Advanced Challenges

### Challenge 1: Multi-Module CI/CD Pipeline

Design a CI/CD pipeline for a 5-module monorepo that:
1. Detects which modules changed in a PR
2. Only builds and tests changed modules (and their dependents)
3. Runs integration tests when shared code changes
4. Caches module builds across CI runs
5. Deploys only changed modules (or the monolith if shared code changed)

### Challenge 2: Migration Without Downtime

Design a zero-downtime migration from a layered monolith to a modular monolith with eventual service extraction. Handle:
1. Database schema changes (who owns which tables?)
2. API contract changes (when do you switch from in-process to gRPC?)
3. Feature flags to toggle between old and new code paths
4. Rollback plan for each phase
5. Performance comparison before/after

### Challenge 3: Feature Dependency Analyzer

Build a Go tool that:
1. Parses all import statements in a feature-based project
2. Builds a directed graph of feature-to-feature dependencies
3. Detects architectural violations (circular deps, forbidden imports)
4. Calculates instability metrics (fan-in/fan-out per feature)
5. Generates a visualization of the feature dependency graph
6. Suggests refactoring opportunities (features with high fan-out should depend on abstractions)

## Key Insights

1. **Feature-based architecture inverts the axis of organization.** Instead of grouping by technical layer (handler, service, repo), group by business capability (users, orders, payments). This maximizes cohesion within a feature.

2. **The Go compiler IS your architecture validator.** Cyclic imports, `internal/` visibility, and unexported types enforce feature boundaries at compile time. No need for ArchUnit.

3. **Cross-feature communication goes through interfaces, never through concrete types.** `users.UserService` is importable. `users.userService` is not. This is Go's native encapsulation mechanism.

4. **The shared kernel is a coupling point.** Every type in `internal/common/` couples every feature. Share only value objects that are universally agreed upon and stable.

5. **A Go module is an option on future service extraction.** Create module boundaries at domain seams today, even if you deploy as a monolith. The module IS the service boundary waiting to happen.

6. **Events are the safest cross-feature communication pattern.** Synchronous service calls couple features. Events decouple them. Prefer events for cross-feature writes, service interfaces for cross-feature reads.

7. **Feature self-registration via Routes() keeps wiring clean.** Each handler registers its own routes. `main.go` just mounts them. No central route registration file that grows to 500 lines.

8. **Premature modularization is as harmful as tight coupling.** Only create go.mod for features that have independent lifecycles. For most teams, a single go.mod with feature packages is sufficient.

9. **Team structure should mirror feature structure.** One team owns one set of feature packages. If team boundaries and code boundaries misalign, either restructure the code or restructure the teams.

10. **The modular monolith gives you 80% of microservices benefits at 20% of the complexity cost.** You get independent team ownership, bounded contexts, and the option to extract services — without distributed systems complexity.
