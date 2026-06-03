# Session 2: Layered Architecture Deep Dive

## Why This Topic Exists

Layered architecture is the most widely adopted architectural pattern in enterprise software. It's the default for Spring Boot engineers (`@Controller → @Service → @Repository`), and it's often the first architecture Go engineers reach for when coming from Java backgrounds. The pattern is simple, well-understood, and creates a clear separation of concerns.

However, implementing layered architecture in Go is fundamentally different from implementing it in Spring Boot. Go has no annotations, no DI container, no AOP, no component scanning, no auto-configuration, no `@Transactional`, no `@Cacheable`, no `@Async`. Every piece of infrastructure that Spring provides magically must be built explicitly in Go.

At the Staff/Principal level, you need to understand:

1. **How to implement layers idiomatically in Go** — struct-based handlers, interface-based service contracts, explicit constructor injection
2. **What Spring gives you that Go doesn't** — and whether you actually need it
3. **When layered architecture works in Go and when it breaks** — the scaling limits are different than in Spring
4. **How to make layered architecture testable** — Go's testing philosophy and tooling enable different approaches

## Mental Model

### The Layered Architecture Contract

```
┌─────────────────────────────────────────────────────────────────────┐
│                  LAYERED ARCHITECTURE MENTAL MODEL                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    PRESENTATION LAYER                         │  │
│  │  Responsibility: Translate HTTP ↔ domain types                │  │
│  │  Knows about: HTTP, JSON, validation, error formatting        │  │
│  │  Does NOT know about: Database queries, business rules        │  │
│  │  Go implementation: Handler structs with ServeHTTP methods    │  │
│  └──────────────────────┬───────────────────────────────────────┘  │
│                         │ depends on                                │
│                         ▼                                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                      SERVICE LAYER                            │  │
│  │  Responsibility: Business logic, orchestration, transactions   │  │
│  │  Knows about: Business rules, use cases, service interfaces    │  │
│  │  Does NOT know about: HTTP, database drivers, SQL              │  │
│  │  Go implementation: Interface + struct, explicit DI            │  │
│  └──────────────────────┬───────────────────────────────────────┘  │
│                         │ depends on                                │
│                         ▼                                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    REPOSITORY LAYER                           │  │
│  │  Responsibility: Persistence, data access, external APIs      │  │
│  │  Knows about: SQL, database drivers, HTTP clients             │  │
│  │  Does NOT know about: Business rules, HTTP handlers           │  │
│  │  Go implementation: Interface + struct, database/sql or pgx   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### The Dependency Rule (Both Directions Apply)

```
HIGHER LAYER → depends on → LOWER LAYER

Handler knows about Service interface    ✅
Service knows about Repository interface ✅
Repository knows about Handler           ❌ VIOLATION
Service knows about HTTP                 ❌ VIOLATION
Handler knows about SQL                  ❌ VIOLATION

THE KEY INSIGHT:
In Go, this dependency direction is enforced by package imports.
If handler/ imports service/, that's fine.
If service/ tries to import handler/, the compiler says NO (and even if
it compiled, it would be an architectural violation).
```

### Go's Implementation Philosophy vs Spring's

```
┌───────────────────────────────────────────────────────────────────┐
│  CONCERN              │  SPRING BOOT          │  GO/CHI           │
├───────────────────────────────────────────────────────────────────┤
│  HTTP Routing         │  @RequestMapping      │  chi.NewRouter()  │
│                       │  @GetMapping          │  r.Get(), r.Post()│
│                       │  Annotation-driven    │  Explicit method  │
│                       │                       │  calls            │
├───────────────────────────────────────────────────────────────────┤
│  Dependency Injection │  @Autowired           │  Constructor args │
│                       │  Field/Constructor/   │  func NewHandler( │
│                       │  Setter injection     │    svc Service,   │
│                       │  Container-managed    │  ) *Handler { }   │
├───────────────────────────────────────────────────────────────────┤
│  Validation           │  @Valid, @NotNull     │  Manual checks or │
│                       │  Hibernate Validator  │  go-playground/   │
│                       │                       │  validator        │
├───────────────────────────────────────────────────────────────────┤
│  Transactions         │  @Transactional       │  Manual tx:       │
│                       │  AOP-driven           │  tx, _ := db.Begin│
│                       │                       │  defer tx.Rollback│
│                       │                       │  tx.Commit()      │
├───────────────────────────────────────────────────────────────────┤
│  Error Handling       │  @ExceptionHandler    │  Explicit if err   │
│                       │  @ControllerAdvice    │  != nil checks    │
│                       │                       │  Custom middleware │
├───────────────────────────────────────────────────────────────────┤
│  Async Processing     │  @Async               │  go func() { }    │
│                       │  Thread pool auto     │  Goroutine        │
├───────────────────────────────────────────────────────────────────┤
│  Caching              │  @Cacheable           │  Manual cache     │
│                       │  Auto-generated cache │  checks, no magic │
│                       │  keys                 │                   │
├───────────────────────────────────────────────────────────────────┤
│  Testing              │  @SpringBootTest      │  Table-driven     │
│                       │  @MockBean            │  Manual mock      │
│                       │  Full context loads   │  No context loads │
└───────────────────────────────────────────────────────────────────┘
```

## Internal Architecture

### Handler Layer: Struct-Based, Chi-Compatible

Go handlers are not classes with annotated methods—they are structs with method sets that satisfy the `http.Handler` interface, or functions that match `http.HandlerFunc`.

```go
// === IDIOMATIC GO HANDLER ===

// Handler is a struct holding its dependencies.
// It is NOT a Spring @RestController.
// It has NO annotations, NO magic, NO framework coupling.
type UserHandler struct {
    svc    UserService        // Interface, not concrete type
    logger *slog.Logger
    // Add more dependencies as fields—explicit, visible, traceable
}

// NewUserHandler is the constructor. This is the only way to create
// a UserHandler. There is no DI container to discover it.
// This is both a limitation (more typing) and an advantage (no magic).
func NewUserHandler(svc UserService, logger *slog.Logger) *UserHandler {
    return &UserHandler{svc: svc, logger: logger}
}

// Routes registers all user routes on a Chi router.
// The handler is responsible for its own route registration.
// This keeps route registration colocated with handler logic.
func (h *UserHandler) Routes() func(r chi.Router) {
    return func(r chi.Router) {
        r.Get("/users", h.List)
        r.Post("/users", h.Create)
        r.Get("/users/{id}", h.Get)
        r.Put("/users/{id}", h.Update)
        r.Delete("/users/{id}", h.Delete)
    }
}

// Get handles GET /users/{id}
// Three responsibilities ONLY:
// 1. Extract/decode request parameters
// 2. Call the service layer
// 3. Encode the response or error
func (h *UserHandler) Get(w http.ResponseWriter, r *http.Request) {
    id := chi.URLParam(r, "id")

    user, err := h.svc.GetByID(r.Context(), id)
    if err != nil {
        // Error handling is explicit. No @ExceptionHandler.
        // Use a helper for consistency across handlers.
        renderError(w, r, err)
        return
    }

    renderJSON(w, http.StatusOK, user)
}
```

**Key differences from Spring's `@RestController`:**

1. **No automatic parameter binding**: `chi.URLParam(r, "id")` instead of `@PathVariable String id`
2. **No automatic JSON marshaling**: `renderJSON()` is a helper, not framework magic
3. **No automatic error handling**: Every error path is explicit
4. **Route registration is explicit**: `h.Get("/users/{id}", h.Get)` instead of `@GetMapping("/users/{id}")`
5. **Handler is testable in isolation**: Pass a mock service, call `handler.Get(w, r)` directly

### Service Layer: Interface-Based Contracts

This is where Go's philosophy diverges most from Spring's. In Go, services are defined by interfaces, not by classes with annotations.

```go
// === IDIOMATIC GO SERVICE ===

// UserService defines the contract. It is an INTERFACE.
// This is the key difference from Spring: interfaces define
// the contract, not @Service annotations on concrete classes.
//
// Go convention: Define interfaces where they are CONSUMED,
// not where they are IMPLEMENTED.
type UserService interface {
    GetByID(ctx context.Context, id string) (*User, error)
    Create(ctx context.Context, input CreateUserInput) (*User, error)
    Update(ctx context.Context, id string, input UpdateUserInput) (*User, error)
    Delete(ctx context.Context, id string) error
    List(ctx context.Context, filter UserFilter) ([]User, error)
}

// userService is the concrete implementation. It is UNEXPORTED (lowercase).
// This is deliberate: external packages consume the INTERFACE,
// never the concrete type. This enables easy mocking, testing,
// and future replacement.
//
// In Spring: @Service → implicitly creates a bean, DI discovers it.
// In Go: NewUserService → explicit constructor, explicit DI.
type userService struct {
    repo   UserRepository    // Interface, not concrete PostgreSQL implementation
    cache  *redis.Client      // Optional: can be nil if no cache
    events EventPublisher     // Optional: event publishing for CQRS
}

// NewUserService is the ONLY way to create a userService.
// Every dependency is visible in the function signature.
// There is no hidden dependency, no field injection, no magic.
func NewUserService(repo UserRepository, cache *redis.Client, events EventPublisher) UserService {
    return &userService{
        repo:   repo,
        cache:  cache,
        events: events,
    }
}

// GetByID implements UserService. Contains ONLY business logic.
// No SQL, no HTTP concerns, no request parsing.
func (s *userService) GetByID(ctx context.Context, id string) (*User, error) {
    // Business rule: validate the ID format
    if !isValidUUID(id) {
        return nil, ErrInvalidUserID
    }

    // Try cache first (infrastructure concern, but via interface)
    if s.cache != nil {
        if cached, err := s.cache.Get(ctx, cacheKey(id)).Result(); err == nil {
            var user User
            json.Unmarshal([]byte(cached), &user)
            return &user, nil
        }
    }

    // Delegate to repository (interface, not concrete PostgreSQL)
    user, err := s.repo.FindByID(ctx, id)
    if err != nil {
        return nil, fmt.Errorf("finding user: %w", err)
    }

    // Business rule: user must be active
    if user.Status != StatusActive {
        return nil, ErrUserNotActive
    }

    return user, nil
}
```

**Why the unexported struct + exported interface pattern?**

```
1. TESTABILITY: Tests can mock UserService interface without knowing
   anything about userService internals.

2. ENCAPSULATION: External packages see only the interface. They cannot
   create a userService directly—they MUST use NewUserService.

3. REPLACEABILITY: You can swap userService with CachedUserService,
   AuditUserService, or a gRPC proxy—as long as they implement
   UserService, nothing breaks.

4. MULTIPLE IMPLEMENTATIONS: You can have productionUserService and
   stubUserService in the same codebase. The interface makes this
   possible. Spring does this too, but with @Profile and @Qualifier,
   which are harder to reason about.

5. COMPILE-TIME SAFETY: If the struct doesn't implement all interface
   methods, the compiler tells you. This is checked at the assignment
   in NewUserService: return &userService{} — if userService doesn't
   implement UserService, this line fails to compile.
```

### Repository Layer: Interface + PostgreSQL Implementation

```go
// === IDIOMATIC GO REPOSITORY ===

// UserRepository defines the persistence contract.
// Methods accept and return DOMAIN types, not database types.
// This is critical: the repository translates between domain
// and persistence, not the service layer.
type UserRepository interface {
    FindByID(ctx context.Context, id string) (*User, error)
    FindByEmail(ctx context.Context, email string) (*User, error)
    Create(ctx context.Context, user *User) error
    Update(ctx context.Context, user *User) error
    Delete(ctx context.Context, id string) error
    List(ctx context.Context, filter UserFilter) ([]User, error)
}

// postgresUserRepo implements UserRepository using PostgreSQL.
// Unexported struct with exported constructor.
type postgresUserRepo struct {
    db *sql.DB           // database/sql connection pool
    // OR use pgxpool.Pool for pgx
}

func NewPostgresUserRepo(db *sql.DB) UserRepository {
    return &postgresUserRepo{db: db}
}

func (r *postgresUserRepo) FindByID(ctx context.Context, id string) (*User, error) {
    // Raw SQL with proper parameterization (prevents SQL injection)
    query := `SELECT id, email, name, status, created_at, updated_at
               FROM users WHERE id = $1`

    var user User
    err := r.db.QueryRowContext(ctx, query, id).Scan(
        &user.ID, &user.Email, &user.Name, &user.Status,
        &user.CreatedAt, &user.UpdatedAt,
    )
    if err == sql.ErrNoRows {
        return nil, ErrNotFound
    }
    if err != nil {
        return nil, fmt.Errorf("querying user %s: %w", id, err)
    }
    return &user, nil
}
```

**Repository pattern options in Go:**

```
1. RAW database/sql
   ├── Pros: No dependencies, full control, explicit SQL
   └── Cons: Boilerplate scanning, error-prone

2. sqlc (code generation from SQL)
   ├── Pros: Type-safe, auto-generated, fast, SQL-first
   └── Cons: Requires SQL files, dynamic queries harder
   // Recommended for most new projects.

3. pgx (PostgreSQL driver)
   ├── Pros: High performance, PostgreSQL-specific features
   └── Cons: PostgreSQL-only, more manual than sqlc

4. GORM (ORM)
   ├── Pros: Familiar to Rails/Hibernate users, fast prototyping
   └── Cons: Hidden queries, performance surprises, N+1 problems
   // NOT recommended for Staff/Principal-level projects.
   // At scale, you need to know exactly what SQL is being executed.

5. sqlx (extensions on database/sql)
   ├── Pros: Reduces scanning boilerplate, StructScan
   └── Cons: Still manual SQL, adds dependency
```

### The Main Function: Explicit Wiring (The "Composition Root")

```go
// cmd/server/main.go — THE COMPOSITION ROOT
//
// This file is the ONLY place where concrete types are created and
// wired together. It is the "composition root" — the single point
// where the object graph is assembled.
//
// In Spring, this is scattered across @Configuration classes,
// @ComponentScan, and auto-configuration. In Go, it's HERE.

func main() {
    // 1. Load configuration (from env, files, flags)
    cfg := config.MustLoad()

    // 2. Initialize infrastructure
    logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
    db, err := sql.Open("postgres", cfg.DatabaseURL)
    if err != nil {
        logger.Error("failed to open database", "error", err)
        os.Exit(1)
    }
    defer db.Close()

    redisClient := redis.NewClient(&redis.Options{Addr: cfg.RedisAddr})

    // 3. Build the dependency tree — bottom-up
    // Repositories (leaf nodes — no dependencies except infrastructure)
    userRepo := user.NewPostgresUserRepo(db)
    orderRepo := order.NewPostgresOrderRepo(db)

    // Event publisher (leaf node)
    eventPub := events.NewKafkaPublisher(cfg.KafkaBrokers)

    // Services (depend on repositories + infrastructure)
    userSvc := user.NewUserService(userRepo, redisClient, eventPub)
    orderSvc := order.NewOrderService(orderRepo, userRepo, eventPub)
    //                    ^ order service depends on user repo
    //                    This is explicit. In Spring, you'd @Autowire
    //                    and hope the container resolves it.

    // 4. Build handlers (depend on services)
    userHandler := user.NewUserHandler(userSvc, logger)
    orderHandler := order.NewOrderHandler(orderSvc, logger)

    // 5. Build router and register routes
    r := chi.NewRouter()

    // Global middleware — applied to ALL routes
    r.Use(middleware.RequestID)
    r.Use(middleware.RealIP)
    r.Use(middleware.Logger)
    r.Use(middleware.Recoverer)
    r.Use(middleware.Timeout(30 * time.Second))

    // Group-specific middleware
    r.Group(func(r chi.Router) {
        r.Use(auth.JWTAuth(cfg.JWTSecret))

        // Register feature routes
        r.Route("/api/v1", func(r chi.Router) {
            r.Route("/users", userHandler.Routes())
            r.Route("/orders", orderHandler.Routes())
        })
    })

    // Health check (no auth required)
    r.Get("/health", healthHandler)

    // 6. Start server with graceful shutdown
    srv := &http.Server{
        Addr:    ":" + cfg.Port,
        Handler: r,
    }

    // Graceful shutdown on SIGTERM/SIGINT
    go func() {
        sigCh := make(chan os.Signal, 1)
        signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT)
        <-sigCh

        ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
        defer cancel()
        srv.Shutdown(ctx)
    }()

    logger.Info("server starting", "port", cfg.Port)
    if err := srv.ListenAndServe(); err != http.ErrServerClosed {
        logger.Error("server error", "error", err)
        os.Exit(1)
    }
}
```

**What this `main()` tells you (and what Spring doesn't):**
1. The exact order of initialization
2. Every dependency between components
3. Which middleware applies to which routes
4. How long the shutdown timeout is
5. Where to add a new dependency

In Spring, you'd need to trace annotations across dozens of files to understand this. In Go, it's 100 lines in one file.

## Runtime Behavior

### Request Processing Timeline (Go vs Spring)

```
GO/CHI REQUEST PROCESSING:
─────────────────────────────────────────────────────────────────
Client → net/http (accept connection, create goroutine)
→ chi middleware stack (RequestID → Logger → Recoverer → Timeout)
→ chi router (pattern match on method + path)
→ Handler method (extract params, call service, encode response)
→ net/http (write response, close or reuse connection)

Each step is a direct function call. No reflection. No proxy objects.
Goroutine (~2KB stack) handles the entire request lifecycle.

SPRING BOOT REQUEST PROCESSING (for contrast):
─────────────────────────────────────────────────────────────────
Client → Tomcat (accept connection, borrow thread from pool)
→ Filter chain (OncePerRequestFilter → SecurityFilter → CorsFilter...)
→ DispatcherServlet (determine handler)
→ HandlerMapping (find @RequestMapping match)
→ HandlerAdapter (adapt to controller method)
→ ArgumentResolver chain (resolve @PathVariable, @RequestBody, @Valid...)
→ AOP proxy chain (@Transactional, @Cacheable, @Async interceptors)
→ Controller method (your code)
→ ReturnValueHandler chain (handle ResponseEntity, @ResponseBody...)
→ MessageConverter (Jackson serialize to JSON)
→ Response

Many steps use reflection. Many involve proxy objects. Thread (~1MB)
must wait through the entire chain, limiting concurrency without pooling.
```

### Transaction Management: Go vs Spring

```
SPRING: @Transactional
─────────────────────────────────────────────────────────────────
@Transactional
public User createUser(CreateUserRequest req) {
    // Spring opens a transaction via AOP proxy BEFORE this method
    User user = userRepository.save(req.toUser());
    auditLog.record("user_created", user.getId());
    // Spring commits the transaction AFTER this method
    // If any exception: rollback
    // If checked exception: depends on rollbackFor config
}
// Problem: You don't control the transaction boundaries precisely.
// The method IS the transaction boundary.

GO: Explicit Transaction Management
─────────────────────────────────────────────────────────────────
func (s *userService) Create(ctx context.Context, input CreateUserInput) (*User, error) {
    // Option 1: Let repository handle transactions
    return s.repo.Create(ctx, input.ToUser())

    // Option 2: Explicit transaction spanning multiple repositories
    tx, err := s.db.BeginTx(ctx, nil)
    if err != nil {
        return nil, err
    }
    defer tx.Rollback() // no-op if already committed

    user, err := s.userRepo.CreateTx(ctx, tx, input.ToUser())
    if err != nil {
        return nil, err
    }

    if err := s.auditRepo.RecordTx(ctx, tx, "user_created", user.ID); err != nil {
        return nil, err
    }

    if err := tx.Commit(); err != nil {
        return nil, err
    }
    return user, nil
}
// Advantage: Complete control. You decide the transaction boundaries.
// Disadvantage: More verbose. Every transaction is explicit.
// KEY: You CAN'T accidentally join an existing transaction or
// propagate one incorrectly—there IS no implicit propagation.
```

## Request Flow Diagrams

### Full Request Lifecycle with All Layers

```
POST /api/v1/users HTTP/1.1
Content-Type: application/json
Authorization: Bearer eyJ...

{"email": "alice@example.com", "name": "Alice"}

                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        net/http Server                               │
│  - Accept TCP connection                                             │
│  - Create goroutine (G#42)                                           │
│  - Parse HTTP request                                                │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Chi Middleware Stack                           │
│                                                                      │
│  RequestID → adds X-Request-ID header, sets context value            │
│  Logger    → logs method, path, status, duration                     │
│  Recoverer → recovers from panics, logs stack trace, returns 500     │
│  Timeout   → sets context deadline (30s), cancels if exceeded        │
│  JWTAuth   → validates JWT, extracts claims, sets context value      │
│                                                                      │
│  Each middleware:                                                    │
│    1. Does pre-processing                                            │
│    2. Calls next.ServeHTTP(w, r)                                     │
│    3. Does post-processing                                           │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                           chi.Router                                 │
│                                                                      │
│  Match: POST /api/v1/users                                           │
│  Resolves to: UserHandler.Create                                     │
│                                                                      │
│  Radix tree lookup:                                                  │
│    / → api → /v1/ → users  [POST] → handler.Create                  │
│                                                                      │
│  chi.URLParam not used here (no path params)                         │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      UserHandler.Create                              │
│                                                                      │
│  1. Decode request body:                                             │
│     var input CreateUserInput                                        │
│     json.NewDecoder(r.Body).Decode(&input)                           │
│                                                                      │
│  2. Validate input:                                                  │
│     if input.Email == "" → 400 Bad Request                           │
│                                                                      │
│  3. Call service:                                                    │
│     user, err := h.svc.Create(r.Context(), input)                    │
│                                                                      │
│  4. Handle errors:                                                   │
│     if errors.Is(err, ErrDuplicateEmail) → 409 Conflict              │
│     if errors.Is(err, ErrValidation) → 422 Unprocessable Entity      │
│     if err != nil → 500 Internal Server Error                        │
│                                                                      │
│  5. Encode response:                                                 │
│     w.Header().Set("Content-Type", "application/json")               │
│     w.WriteHeader(http.StatusCreated)                                │
│     json.NewEncoder(w).Encode(UserResponse{...})                     │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     userService.Create                               │
│                                                                      │
│  1. Validate business rules:                                         │
│     - Email format valid?                                            │
│     - Password meets complexity requirements?                        │
│     - Domain not in blocklist?                                       │
│                                                                      │
│  2. Check uniqueness:                                                │
│     existing, _ := s.repo.FindByEmail(ctx, input.Email)              │
│     if existing != nil → return ErrDuplicateEmail                    │
│                                                                      │
│  3. Build domain object:                                             │
│     user := &User{                                                   │
│         ID:        uuid.New().String(),                              │
│         Email:     input.Email,                                      │
│         Name:      input.Name,                                       │
│         Status:    StatusPending,                                    │
│         CreatedAt: time.Now(),                                       │
│     }                                                                │
│                                                                      │
│  4. Hash password (security concern):                                │
│     user.PasswordHash = bcrypt.Hash(input.Password)                  │
│                                                                      │
│  5. Persist:                                                         │
│     if err := s.repo.Create(ctx, user); err != nil {                 │
│         return nil, err                                              │
│     }                                                                │
│                                                                      │
│  6. Publish event:                                                   │
│     s.events.Publish(ctx, UserCreatedEvent{UserID: user.ID})         │
│                                                                      │
│  7. Return domain object:                                            │
│     return user, nil                                                 │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    postgresUserRepo.Create                           │
│                                                                      │
│  1. Build SQL:                                                       │
│     INSERT INTO users (id, email, name, password_hash, status,       │
│       created_at, updated_at)                                        │
│     VALUES ($1, $2, $3, $4, $5, $6, $7)                             │
│                                                                      │
│  2. Execute with context (respects timeout):                         │
│     r.db.ExecContext(ctx, query, user.ID, user.Email, ...)           │
│                                                                      │
│  3. Handle errors:                                                   │
│     - If context deadline exceeded → return ctx.Err()                │
│     - If unique constraint violation → return ErrDuplicate           │
│                                                                      │
│  4. Return nil on success:                                           │
│     return nil                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

## Lifecycle Diagrams

### Dependency Lifecycle (What Gets Created When)

```
APPLICATION STARTUP:
─────────────────────────────────────────────────────────────────
time ──────────────────────────────────────────────────────────►

T=0    main() starts
T=1ms  config.Load()                          → cfg (lives forever)
T=3ms  sql.Open("postgres", ...)              → *sql.DB pool (lives forever)
T=5ms  redis.NewClient(...)                   → *redis.Client (lives forever)
T=6ms  user.NewPostgresUserRepo(db)            → UserRepository (lives forever)
T=6ms  order.NewPostgresOrderRepo(db)          → OrderRepository (lives forever)
T=6ms  events.NewKafkaPublisher(brokers)       → EventPublisher (lives forever)
T=7ms  user.NewUserService(repo, cache, events) → UserService (lives forever)
T=7ms  order.NewOrderService(repo, userRepo, events) → OrderService (lives forever)
T=8ms  user.NewUserHandler(svc, logger)        → *UserHandler (lives forever)
T=8ms  order.NewOrderHandler(svc, logger)      → *OrderHandler (lives forever)
T=9ms  chi.NewRouter()                         → chi.Router (lives forever)
T=10ms Route registration (r.Get, r.Post...)   → Radix tree built
T=11ms http.ListenAndServe(":8080", r)          → Blocking (running)

KEY OBSERVATION:
- Everything is created ONCE at startup.
- NOTHING is created per-request (no per-request DI scopes).
- NOTHING is lazily created (no lazy bean initialization).
- If you need per-request state, use context.Context.

CONTRAST WITH SPRING:
- Spring creates many per-request beans (@RequestScope, @SessionScope)
- Spring lazy-initializes many beans by default
- Spring's DI container manages lifecycle callbacks (@PostConstruct, @PreDestroy)
- Go: you manage lifecycle. There is no container.
```

### Request-Level Dependency Lifecycle

```
REQUEST LIFECYCLE:
─────────────────────────────────────────────────────────────────
time ──────────────────────────────────────────────────────────►

T=0   Request arrives at net/http server
      └→ Goroutine G#42 created from pool (or new if pool empty)

T=1ms Goroutine enters middleware stack
      └→ Each middleware adds values to request context

T=2ms Router matches route → handler method called
      └→ Handler extracts params from request
      └→ Handler calls service (same instance as startup, no proxy)
      └→ Service calls repository (same instance as startup)
      └→ Repository calls database (via connection pool)

T=20ms Response written to wire
       └→ Goroutine G#42 returns to pool (NOT destroyed)
       └→ Goroutine stack shrinks (memory efficient)

NO OBJECTS ARE CREATED PER-REQUEST (except value types on the stack).
All dependencies are long-lived, goroutine-safe singletons.
This is why Go can handle 10K+ concurrent requests without
blowing up memory or thread pools.
```

### Error Propagation Through Layers

```
ERROR FLOW THROUGH LAYERS:
─────────────────────────────────────────────────────────────────

DATABASE ERROR:
  postgres.ExecContext → pq.Error{Code: "23505"}  // unique violation
  ↓ wraps with fmt.Errorf("creating user: %w", err)
  ↓
SERVICE LAYER:
  userService receives error
  ↓ checks if errors.Is(err, sql.ErrNoRows) → ErrNotFound
  ↓ wraps with domain error: fmt.Errorf("user service create: %w", ErrDuplicateEmail)
  ↓
HANDLER LAYER:
  handler.Create receives error
  ↓ switches on error type:
  ↓   ErrDuplicateEmail → 409 Conflict
  ↓   ErrNotFound → 404 Not Found
  ↓   ErrValidation → 422 Unprocessable Entity
  ↓   default → 500 Internal Server Error
  ↓
  renderError(w, r, err)
  ↓ writes JSON: {"error": "duplicate email", "code": "DUPLICATE_EMAIL"}

KEY DESIGN DECISIONS:
1. Repository errors are wrapped, not replaced.
   fmt.Errorf("creating user: %w", err) preserves the original error
   for debugging while adding context.

2. Service errors use sentinel values (ErrNotFound, ErrDuplicateEmail).
   These are defined in the service package and tested with errors.Is().

3. Handler maps errors to HTTP status codes.
   This is the ONLY layer that knows about HTTP.
   The service layer should NEVER import net/http.

4. Error details exposed to clients are controlled in the handler.
   Internal error messages (SQL, stack traces) are NEVER exposed.
   Only domain error codes are returned to clients.
```

## Source Code Reading Guide

### Standard Layered Project — Reading Order

For a standard layered Go/Chi project, read in this order:

```
myapp/
├── cmd/server/main.go              ← (1) Entry point, composition root
├── internal/
│   ├── handler/
│   │   ├── user_handler.go         ← (4) HTTP surface area
│   │   ├── order_handler.go
│   │   └── middleware/
│   │       └── auth.go             ← (3) Cross-cutting concerns
│   ├── service/
│   │   ├── user_service.go         ← (5) Business logic (interface + struct)
│   │   ├── order_service.go
│   │   └── errors.go               ← (6) Domain errors
│   ├── repository/
│   │   ├── user_repo.go            ← (7) Data access (interface + struct)
│   │   ├── order_repo.go
│   │   └── postgres.go             ← (8) Database connection management
│   └── model/
│       ├── user.go                 ← (2) Domain types (read first!)
│       ├── order.go
│       └── dto.go                  ← (9) Request/response types
└── go.mod                          ← (0) Module declaration, dependencies
```

**What to ignore on first reading:**
- Test files (`*_test.go`) — read them separately when studying testing patterns
- Migration files — infrastructure concern
- Configuration files — environment-specific, read when deploying
- Generated code — read the source schema, not the generated output

### How to Trace a Feature End-to-End

```
To understand how "Create User" works:

1. Start at cmd/server/main.go
   → Find: userHandler := user.NewUserHandler(userSvc, logger)
   → Find: r.Route("/users", userHandler.Routes())
   → This tells you: UserHandler handles /users/* routes

2. Open internal/handler/user_handler.go
   → Find: func (h *UserHandler) Routes()
   → Find: r.Post("/users", h.Create)  ← This is the endpoint
   → Find: func (h *UserHandler) Create(w, r)
   → See what the handler does with the request
   → See what service method it calls: h.svc.Create(ctx, input)

3. Open internal/service/user_service.go
   → Find: type UserService interface
   → Find: Create(ctx context.Context, input CreateUserInput) (*User, error)
   → Find: func (s *userService) Create(...) // implementation
   → See the business logic, validation, event publishing

4. Open internal/repository/user_repo.go
   → Find: type UserRepository interface
   → Find: Create(ctx context.Context, user *User) error
   → Find: func (r *postgresUserRepo) Create(...) // implementation
   → See the SQL query, error handling

5. Open internal/model/user.go
   → Find: type User struct { ... }
   → See the domain model

COMPLETE: You've traced "Create User" from HTTP → Handler → Service → Repository → Database.
Time: ~5 minutes for a well-structured project.
```

## Production Failure Scenarios

### Scenario 1: The God Handler

**Symptom**: A single handler file has 500+ lines. It does validation, business logic, database queries, and external API calls—all in the handler.

**Root Cause**: No discipline in separating layers. The handler was "just a quick fix" that grew over time.

**Why it's dangerous:**
- Cannot test business logic without HTTP (need httptest.ResponseRecorder)
- Cannot reuse logic (calling from another handler means copy-paste)
- Cannot change database without touching HTTP code
- Every change risks breaking the HTTP contract

**Fix**: Extract service layer. The handler should be <50 lines per method.

```go
// BEFORE: God handler (WRONG)
func (h *Handler) CreateUser(w http.ResponseWriter, r *http.Request) {
    // 50 lines of validation
    // 30 lines of business logic
    // 20 lines of database queries
    // 15 lines of response formatting
    // = 115 lines in a handler
}

// AFTER: Proper separation (RIGHT)
func (h *UserHandler) Create(w http.ResponseWriter, r *http.Request) {
    var input CreateUserInput
    json.NewDecoder(r.Body).Decode(&input)
    user, err := h.svc.Create(r.Context(), input)
    // ... error handling, response encoding (~20 lines total)
}
```

### Scenario 2: Service Layer Bypass

**Symptom**: A handler calls the repository directly, bypassing the service layer entirely.

```go
// WRONG: Handler calling repository directly
func (h *UserHandler) Get(w http.ResponseWriter, r *http.Request) {
    id := chi.URLParam(r, "id")
    user, err := h.repo.FindByID(r.Context(), id) // ← BYPASSING SERVICE
}

// RIGHT: Handler only calls service
func (h *UserHandler) Get(w http.ResponseWriter, r *http.Request) {
    id := chi.URLParam(r, "id")
    user, err := h.svc.GetByID(r.Context(), id) // ← THROUGH SERVICE
}
```

**Root Cause**: The handler struct has both service and repository as fields because someone thought "I might need it." Don't inject repositories into handlers.

**Prevention**: The handler struct should only have service interfaces as fields. If a handler needs direct repository access, the design is wrong.

### Scenario 3: The N+1 Problem Hidden in the Service Layer

**Symptom**: Listing 100 users triggers 101 database queries (1 list + 100 individual order count queries).

```go
// WRONG: N+1 in the service layer
func (s *userService) List(ctx context.Context, filter UserFilter) ([]UserWithOrderCount, error) {
    users, err := s.repo.List(ctx, filter) // 1 query
    if err != nil {
        return nil, err
    }

    var result []UserWithOrderCount
    for _, user := range users {
        count, err := s.orderRepo.CountByUser(ctx, user.ID) // N queries
        if err != nil {
            return nil, err
        }
        result = append(result, UserWithOrderCount{
            User:       user,
            OrderCount: count,
        })
    }
    return result, nil
}

// FIX: Add a repository method that joins in one query
func (r *postgresUserRepo) ListWithOrderCount(ctx context.Context, filter UserFilter) ([]UserWithOrderCount, error) {
    query := `
        SELECT u.*, COALESCE(o.order_count, 0) as order_count
        FROM users u
        LEFT JOIN (
            SELECT user_id, COUNT(*) as order_count
            FROM orders
            GROUP BY user_id
        ) o ON u.id = o.user_id
    `
    // Single query
}
```

### Scenario 4: Accidental Tight Coupling via Concrete Types

**Symptom**: You want to add caching to UserService but can't without modifying every consumer. Every package imports the concrete type.

**Root Cause**: Exporting the concrete struct instead of the interface. This is the Go equivalent of Spring's `@Autowired private UserServiceImpl service` instead of `@Autowired private UserService service`.

```go
// WRONG: Exported concrete type
type UserService struct { ... }  // ← EXPORTED (uppercase)
func NewUserService(...) *UserService { ... }  // ← returns *UserService

// RIGHT: Exported interface, unexported struct
type UserService interface { ... }  // ← EXPORTED interface
type userService struct { ... }     // ← UNEXPORTED struct
func NewUserService(...) UserService { ... }  // ← returns interface
```

## Debugging Techniques

### Tracing Dependency Wiring Issues

```bash
# Find where a type is constructed (the composition root)
rg "NewUserService" --include="*.go"

# Verify interface implementation at compile time
# Add this line to the package where the struct is defined:
var _ UserService = (*userService)(nil)
# This line will fail to compile if userService doesn't implement UserService.
# Put it in the same file as the struct definition.

# Find all implementations of an interface
rg "type \w+Service interface" --include="*.go" -A 10

# Find all consumers of an interface
rg "UserService" --include="*.go" | grep -v "_test.go" | grep -v "interface"
```

### Diagnosing Layer Leakage

```bash
# Check if any service imports net/http (should NOT)
rg "net/http" --include="*.go" internal/service/

# Check if any handler imports database/sql (should NOT)
rg "database/sql\|pgx\|sqlx" --include="*.go" internal/handler/

# Check if any repository imports net/http (should NOT)
rg "net/http" --include="*.go" internal/repository/
```

### Debugging Middleware Ordering

```bash
# Chi middleware executes in insertion order.
# To debug, add a logging middleware first and last:
r.Use(func(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        log.Println("MIDDLEWARE STACK START")
        next.ServeHTTP(w, r)
        log.Println("MIDDLEWARE STACK END")
    })
})

# Then trace which middleware fires when.
# The order in your r.Use() calls IS the execution order.
# Request:  1 → 2 → 3 → handler
# Response: handler → 3 → 2 → 1
```

## Observability Considerations

### Structured Logging Across Layers

```go
// Each layer adds its own context to logs via slog

// Handler layer:
func (h *UserHandler) Create(w http.ResponseWriter, r *http.Request) {
    logger := slog.With("handler", "UserHandler.Create", "request_id", middleware.GetReqID(r.Context()))
    logger.Info("creating user")
    // ...
    if err != nil {
        logger.Error("failed to create user", "error", err)
    }
}

// Service layer:
func (s *userService) Create(ctx context.Context, input CreateUserInput) (*User, error) {
    logger := slog.With("service", "userService.Create", "email", input.Email)
    logger.Debug("validating user input")
    // ...
}

// Repository layer:
func (r *postgresUserRepo) Create(ctx context.Context, user *User) error {
    logger := slog.With("repo", "postgresUserRepo.Create", "user_id", user.ID)
    logger.Debug("inserting user")
    // ...
}

// Result in log aggregator (e.g., Loki, Elasticsearch):
{
  "level": "INFO",
  "handler": "UserHandler.Create",
  "request_id": "abc-123",
  "msg": "creating user"
}
{
  "level": "DEBUG",
  "service": "userService.Create",
  "email": "alice@example.com",
  "msg": "validating user input"
}
{
  "level": "DEBUG",
  "repo": "postgresUserRepo.Create",
  "user_id": "uuid-456",
  "msg": "inserting user"
}

// KEY: The `request_id` ties all logs for one request together.
// The layer name tells you where in the stack the log was emitted.
```

### Metrics by Layer

```
LAYER            METRICS TO COLLECT
─────────────────────────────────────────────────────────────
Handler          request_count, request_duration, request_size,
                 response_size, error_count_by_status_code

Service          operation_count, operation_duration,
                 business_error_count, event_published_count

Repository       query_count, query_duration, query_error_count,
                 connection_pool_wait, connection_pool_idle

Infrastructure   db_connections_active, db_connections_idle,
                 redis_hit_rate, kafka_produce_latency
```

### Distributed Tracing Setup

```go
// Using OpenTelemetry with Chi

import (
    "go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
    "go.opentelemetry.io/otel"
)

func main() {
    // Initialize tracer
    tp := initTracer()

    r := chi.NewRouter()
    // Wrap the entire router with OpenTelemetry
    r.Use(func(next http.Handler) http.Handler {
        return otelhttp.NewHandler(next, "payment-api")
    })

    // In handlers, create spans for each operation
}

// In handler:
func (h *UserHandler) Create(w http.ResponseWriter, r *http.Request) {
    ctx, span := otel.Tracer("user-handler").Start(r.Context(), "UserHandler.Create")
    defer span.End()
    // Use ctx for all downstream calls (service, repository)
    // Tracing context propagates via context.Context
}

// In service:
func (s *userService) Create(ctx context.Context, input CreateUserInput) (*User, error) {
    ctx, span := otel.Tracer("user-service").Start(ctx, "userService.Create")
    defer span.End()
    // ...
}

// In repository:
func (r *postgresUserRepo) Create(ctx context.Context, user *User) error {
    ctx, span := otel.Tracer("user-repo").Start(ctx, "postgresUserRepo.Create")
    defer span.End()
    // ...
}
```

## Performance Implications

### Allocation Analysis by Layer

```
LAYER          ALLOCATIONS PER REQUEST (typical)
───────────────────────────────────────────────────────────
Handler        Request body decode: ~1-3 allocs
               Response encode: ~1-3 allocs

Service        Business logic: ~0-2 allocs (mostly stack)
               Event publishing: ~1-5 allocs

Repository     SQL query + scan: ~5-15 allocs (per entity)
               Connection borrow: ~0 allocs (from pool)

Total per request: ~10-30 allocations
Memory per request: ~2-10KB

COMPARISON (Spring Boot):
Spring Boot with Jackson + Hibernate: ~200-500 allocations per request
Memory per request: ~50-200KB
(Includes proxy objects, reflection, Hibernate entity snapshots, etc.)
```

### Connection Pooling: The Go Way

```go
// database/sql connection pool configuration
// This is critical for layered architecture performance.

db.SetMaxOpenConns(25)      // Maximum concurrent connections
db.SetMaxIdleConns(10)      // Maximum idle connections in pool
db.SetConnMaxLifetime(5 * time.Minute)  // Max connection age
db.SetConnMaxIdleTime(1 * time.Minute)  // Max idle time before close

// TUNING GUIDE:
// MaxOpenConns = (expected_peak_rps * avg_query_duration_seconds) * 1.5
// Example: 1000 RPS * 0.02s avg query * 1.5 buffer = 30 connections
//
// MaxIdleConns = MaxOpenConns * 0.5 (unless memory constrained)
//
// ConnMaxLifetime = less than server-side connection timeout
// (PostgreSQL default: not set, but many cloud providers set 5-10 minutes)

// KEY DIFFERENCES FROM HIKARICP (Spring's connection pool):
// - database/sql is built into Go's standard library. No dependency.
// - Simpler tuning parameters. Fewer knobs, fewer mistakes.
// - No statement caching (use prepared statements explicitly or via pgx).
// - Connection validation is manual (call db.Ping() in health check).
```

## Architecture Implications

### When Layered Architecture Works in Go

```
✅ WORKS WELL FOR:
  - Monolithic applications with <30 domain types
  - Teams of 3-8 engineers
  - CRUD-heavy applications (simple business logic)
  - Projects where the domain model is stable
  - Early-stage startups (fast iteration, refactor later)

KEY SUCCESS INDICATORS:
  - You can answer "where does this code live?" in <5 seconds
  - Feature changes touch 2-3 files (handler, service, repository)
  - New team members understand the structure in their first week
  - Tests don't require complex setup (no Spring context to load)
```

### When Layered Architecture Fails in Go

```
❌ BREAKS DOWN WHEN:
  - Domain types exceed ~30 — handler/, service/, repository/ each
    become "dump" packages with 30+ files
  - Features have fundamentally different structures — not everything
    is CRUD; some features need CQRS, others need event sourcing
  - Multiple teams work in the same codebase — code ownership is
    unclear when teams "vertically" own features but code is
    "horizontally" organized
  - You need to extract a service — layered packages are tightly
    coupled; extracting one domain means rewriting all layers

SYMPTOMS OF FAILURE:
  - Handler package has 50+ files, nobody knows all of them
  - "Where is the email validation logic?" → Nobody knows for sure
  - Adding a field requires changes in handler, service, repository,
    model, and DTO packages (5 files across 5 packages)
  - Cross-feature orchestration ends up in the "wrong" service layer
```

### Migration Path: Layered → Feature-Based

```
Phase 1: Identify feature boundaries
  └→ Group handler+service+repository by domain concept
  └→ Example: user_handler.go + user_service.go + user_repo.go → users/

Phase 2: Create feature packages
  └→ mkdir internal/users
  └→ Move files: user_handler.go → internal/users/handler.go
                  user_service.go → internal/users/service.go
                  user_repo.go    → internal/users/postgres.go
                  user.go         → internal/users/models.go
  └→ Update package names and import paths
  └→ go build ./...

Phase 3: Extract shared code
  └→ Identify code used by multiple features
  └→ Move to internal/common/ or internal/shared/
  └→ Example: auth middleware → internal/common/middleware/auth.go

Phase 4: Enforce boundaries
  └→ No feature imports another feature's service or repository
  └→ Shared code does not import feature code
  └→ Enforce with golangci-lint rules

Phase 5 (optional): Extract to modules
  └→ Add go.mod to features that could become separate services
  └→ Use go.work for local development
  └→ Extract to separate service when business needs demand it
```

## Team Ownership Implications

### Layered Architecture Ownership Anti-Patterns

```
ANTI-PATTERN 1: "Layer Owners"
───────────────────────────────────────────────────────────
Team A owns the handler layer
Team B owns the service layer
Team C owns the repository layer

PROBLEM: Adding a user field requires all three teams to coordinate.
         Every feature change is a cross-team effort.
         This maximizes communication overhead.

ANTI-PATTERN 2: "Shared Ownership of Everything"
───────────────────────────────────────────────────────────
All teams own all packages equally.

PROBLEM: Tragedy of the commons. Nobody takes responsibility.
         Code quality degrades. "Someone else will fix it."
         This is the default for layered architectures.

CORRECT PATTERN: "Feature Teams Own Their Slice"
───────────────────────────────────────────────────────────
# .github/CODEOWNERS
internal/handler/user_*       @team-alpha
internal/service/user_*       @team-alpha
internal/repository/user_*    @team-alpha

PROBLEM: Difficult to maintain with layered structure.
         File naming conventions become critical.
         Easy to accidentally modify another team's files.

BETTER: Feature-based structure (Session 03).
         internal/users/ → @team-alpha (one glob pattern)
```

## Interview Questions

### Q1: "How does Go's layered architecture differ from Spring's in terms of dependency injection?"

**Answer**: Spring uses a DI container that discovers beans via component scanning and injects them via `@Autowired` (field, constructor, or setter injection). Go has no DI container. Dependencies are passed explicitly through constructors (`func NewUserService(repo UserRepository) UserService`). The composition root (`cmd/server/main.go`) manually wires the entire object graph. This is more verbose but eliminates all DI magic—you can trace every dependency by reading `main.go`. There's no "why is this bean null?" runtime surprise. There's no `@Lazy` vs eager initialization confusion. Everything is created at startup in a deterministic order that you control.

### Q2: "What's the idiomatic way to handle cross-cutting concerns (logging, tracing, transactions) in Go's layered architecture?"

**Answer**: Three mechanisms replace Spring AOP:

1. **Middleware** (for HTTP concerns): Chi middleware wraps handlers. Logging, auth, rate limiting, tracing all happen in middleware, not in handlers.

2. **Explicit function calls** (for business logic): Instead of `@Transactional`, you write `tx, _ := db.Begin(); defer tx.Rollback(); ... tx.Commit()`. Instead of `@Cacheable`, you write `if cached, ok := cache.Get(key); ok { return cached }`.

3. **Context.Context** (for request-scoped values): Tracing spans, request IDs, user claims, and deadlines all travel via `context.Context`, which is explicitly passed as the first parameter to every function. No `ThreadLocal`. No `RequestContextHolder`.

The trade-off is verbosity vs. clarity. Go's approach is more verbose but gives you complete control and visibility.

### Q3: "When would you use an unexported struct with an exported interface vs. an exported struct in the service layer?"

**Answer**: Always use unexported struct + exported interface for services and repositories. The interface defines the contract that consumers depend on. The unexported struct is the implementation. This enables: (1) easy mocking in tests (mock the interface, not the struct), (2) multiple implementations (PostgreSQL vs. in-memory for testing), (3) decoration/wrapping (caching layer, audit layer), and (4) preventing direct struct construction (must use constructor, which can enforce invariants). The only exception is simple value types (DTOs, models) where the struct IS the contract and there's no behavior to mock.

### Q4: "How do you handle transaction management that spans multiple repositories in Go?"

**Answer**: In Spring, `@Transactional` automatically propagates transactions. In Go, you have two options:

1. **Transaction as first-class parameter**: Each repository method has a variant that accepts `*sql.Tx` (e.g., `CreateTx(ctx, tx, user)`). The service creates the transaction, calls repository methods with it, and commits/rolls back. This is explicit and testable but verbose.

2. **Unit of Work pattern**: Create a `UnitOfWork` struct that holds the transaction and provides transaction-scoped repositories. `uow := NewUnitOfWork(db); uow.Begin(); uow.Users().Create(user); uow.Orders().Create(order); uow.Commit()`. This is cleaner but adds abstraction.

I prefer option 1 for most projects because it's explicit and doesn't hide the transaction lifecycle. Option 2 becomes valuable when you have many repositories participating in transactions.

### Q5: "How do you test a layered Go service?"

**Answer**: Go enables testing at every layer independently:

**Repository tests**: Integration tests with a real database (testcontainers-go or in-memory). No mocking—test against real PostgreSQL.

**Service tests**: Unit tests with mocked repositories. Create a mock implementing `UserRepository` (manually or with testify/mock). Test business logic in isolation. No database, no HTTP.

**Handler tests**: Use `httptest.NewRecorder()` and `httptest.NewRequest()`. Pass a mocked service. Test HTTP status codes, response bodies, headers. No real HTTP server.

**Integration tests**: Test the whole stack with testcontainers. Spin up the real server, make real HTTP requests, verify database state.

The key advantage over Spring: no `@SpringBootTest` that takes 30 seconds to start. Go tests start instantly. A unit test takes <1ms. An integration test with testcontainers takes 2-5 seconds for the first test (container startup), then <100ms per subsequent test.

### Q6: "How do you prevent layer leakage—e.g., a handler calling the repository directly?"

**Answer**: Two approaches, used together:

1. **Structural prevention**: The handler struct only has service interface fields, never repository fields. `type UserHandler struct { svc UserService }` — there's no way to call the repository because the handler literally doesn't have a reference to it.

2. **Static analysis**: golangci-lint with custom rules or depguard to enforce that `internal/handler/` does not import `internal/repository/`. This catches violations in CI.

Unlike Spring where `@Autowired private UserRepository repo` can be added to any controller, Go's explicit wiring in `main.go` makes it obvious when someone adds a repository to a handler's constructor. It's visible in the diff.

### Q7: "At what scale does layered architecture break in Go, and how do you know you've reached that point?"

**Answer**: The breaking point is not measured in lines of code or number of engineers—it's measured in **cognitive load per feature change**. The signals:

1. **"Where does this code go?" takes >30 seconds to answer.** When the handler/service/repository packages have 30+ files each, nobody can mentally map features to files.

2. **Feature changes consistently touch 5+ files across 3+ packages.** Adding an "email verification" field shouldn't require edits in handler, service, repository, model, DTO, and test packages.

3. **Cross-feature imports create confusion.** When `order/service.go` imports `user/service.go` and `user/service.go` imports `notification/service.go`, you have a spaghetti dependency graph that's hard to reason about.

4. **Code ownership is ambiguous.** When two teams accidentally modify the same service file because they both "own" parts of the user domain.

When you see 2-3 of these signals, it's time to migrate to feature-based architecture (Session 03).

## Hands-On Exercises

### Exercise 1: Build a Minimal Layered Go Service

Create a complete CRUD service for a single entity (e.g., "products") with:
1. Chi router with middleware (RequestID, Logger, Recoverer)
2. Handler layer (struct-based, 4 endpoints: Create, Get, List, Delete)
3. Service layer (interface + unexported struct, basic validation)
4. Repository layer (interface + PostgreSQL implementation)
5. In-memory repository implementation for testing
6. `main.go` that wires everything together
7. Table-driven tests for handler, service, and repository

### Exercise 2: Layer Leakage Audit

Given a "legacy" layered Go project (or written by you with intentional violations):
1. Find all instances where handler imports `database/sql`
2. Find all instances where service imports `net/http`
3. Find all instances where repository has business logic
4. Fix each violation
5. Add compile-time checks to prevent regression

### Exercise 3: From Annotations to Explicit Code

Take a Spring Boot controller written with full annotations:
```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    @Autowired private UserService service;
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Cacheable("users")
    public ResponseEntity<User> get(@PathVariable String id) { ... }
}
```

Rewrite it as a Go/Chi handler with:
- Explicit route registration in `main.go`
- Explicit auth via middleware (not annotations)
- Manual caching (not `@Cacheable`)
- Explicit JSON encoding (not `@ResponseBody`)
- Explicit error mapping (not `@ExceptionHandler`)

Compare line counts and discuss the trade-offs.

### Exercise 4: Multi-Repository Transaction

Implement a service method that:
1. Creates a user
2. Creates an audit log entry
3. Publishes an event

With the constraint that: if the audit log creation fails, the user creation must also roll back. Implement this with explicit transaction management (no ORM).

### Exercise 5: Add Observability to All Layers

Starting from Exercise 1's service, add:
1. Structured logging at each layer (slog with layer-specific attributes)
2. OpenTelemetry spans at handler, service, and repository levels
3. Prometheus metrics (request count, duration, error count) with Chi middleware
4. A `/health` endpoint that checks database connectivity
5. A `/metrics` endpoint exposing Prometheus metrics

## Advanced Challenges

### Challenge 1: Design a Compile-Time Architecture Validator

Create a Go tool (using `go/ast`, `go/parser`, `go/types`) that validates:
- Handlers only import service interfaces (not repositories, not database/sql)
- Services only import repository interfaces (not database/sql, not net/http)
- Repositories only import database/sql or pgx (not service, not handler)
- No package imports a higher layer's package

Run it as part of `go generate` or as a `golangci-lint` plugin.

### Challenge 2: Implement a Pluggable Service Decorator Chain

Design a system where services can be decorated transparently:
```go
// Core service
userSvc := user.NewUserService(repo, nil, events)

// Decorated with caching
cachedSvc := user.NewCachedUserService(userSvc, cache)

// Decorated with auditing
auditedSvc := user.NewAuditedUserService(cachedSvc, auditLog)

// Decorated with rate limiting
rateLimitedSvc := user.NewRateLimitedUserService(auditedSvc, limiter)

// All implement UserService interface
// Handler only knows about UserService interface
```

Implement all decorators as middleware-like wrappers that delegate to the inner service. This is Go's answer to Spring AOP interceptors, but type-safe and explicit.

### Challenge 3: Layer Migration Automation

Build a tool that analyzes a Go layered architecture project and:
1. Identifies feature boundaries from file naming and import patterns
2. Suggests regrouping into feature packages
3. Generates the boilerplate for the migration (new file locations, updated imports)
4. Verifies that the migration doesn't break the build
5. Generates confidence metrics (test coverage change, import graph change)

## Key Insights

1. **Go's layered architecture IS explicit Spring Boot.** Every `@Autowired`, `@Transactional`, `@Cacheable` becomes an explicit function call. The verbosity is the price of clarity.

2. **The interface is the contract; the struct is the implementation.** Export the interface, unexport the struct. This is Go's fundamental abstraction mechanism and replaces Spring's DI container.

3. **The composition root (`main.go`) IS your dependency graph.** If you can't trace a dependency by reading `main.go`, the architecture is too complex for your team's cognitive capacity.

4. **Middleware replaces AOP for cross-cutting concerns.** Chi middleware handles logging, auth, tracing, rate limiting—everything that happens at the HTTP boundary. For deeper cross-cutting (caching, retry), use decorator patterns.

5. **`context.Context` is your request scope.** It carries deadlines, cancellation signals, tracing spans, and user claims. It's passed explicitly to every function that might need them—no ThreadLocal, no RequestContextHolder.

6. **Testing is faster and simpler without a DI container.** No `@SpringBootTest`, no context loading, no `@MockBean`. Just pass mock interfaces to constructors. Tests start in milliseconds.

7. **Transactions are explicit, not declarative.** This is more verbose but gives you complete control. You can never accidentally participate in a transaction you didn't know about (a common Spring gotcha).

8. **Layered architecture scales to ~30 domain types in Go.** Beyond that, the handler/service/repository packages become god-packages that nobody fully understands. Migrate to feature-based before hitting this wall.

9. **The Go compiler, not ArchUnit, enforces your architecture.** Cyclic package imports are compile errors. `internal/` visibility is compiler-enforced. You don't need architecture testing tools—you need good package boundaries.

10. **Every abstraction has a cost in Go.** In Spring, adding a `@Cacheable` annotation is free (one line). In Go, adding caching requires a decorator struct, interface implementation, and wiring. Choose your abstractions carefully—they all cost lines of code.
