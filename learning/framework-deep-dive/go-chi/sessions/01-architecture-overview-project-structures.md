# Session 1: Architecture Overview & Project Structures for Go Services

## Why This Topic Exists

As a Staff/Principal engineer, you don't just write code—you define the structural boundaries within which teams operate. Project structure is the first architectural decision every team makes, and it determines how easily code can be found, understood, tested, and evolved over years of maintenance. Get this wrong, and you spend years fighting the directory layout.

Go is not Java. There is no `src/main/java/com/company/project/`. There is no package-by-layer convention enforced by a build tool. Go's module system, its opinionated compiler (unused imports are errors), and its flat package namespace create a fundamentally different set of constraints and affordances than Spring Boot engineers are accustomed to.

The lightweight nature of Go means the framework does not impose structure on you. Chi is a router—nothing more. Spring Boot gives you `@Controller`, `@Service`, `@Repository`, `@Configuration`, and a convention that auto-wires everything. Go gives you `net/http`, and Chi gives you a slightly nicer `net/http`. The architectural decisions are yours.

This matters at the Staff/Principal level because:

1. **Cognitive load scales with structure, not with lines of code.** A 100K-line codebase with a clear structure is far easier to work with than a 50K-line codebase with an arbitrary one.
2. **Structure encodes ownership.** Where files live determines who feels responsible for them. The wrong structure creates tragedy-of-the-commons code that nobody owns.
3. **Structure determines change velocity.** When adding a feature requires touching 15 files across 8 packages, the structure is fighting you.
4. **Go's compiler enforces structure discipline.** Cyclic imports are compile errors. Unused imports are compile errors. The compiler becomes your architecture validator.

## Mental Model

Think of Go project structure as answering one question: **"Where do I put this file?"** The answer should be obvious enough that two engineers working independently make the same choice.

### The Core Tension

Every Go project structure is a negotiation between two forces:

```
┌─────────────────────────────────────────────────────────────┐
│  ORGANIZE BY LAYER           vs        ORGANIZE BY FEATURE  │
│  (horizontal slicing)                  (vertical slicing)   │
├─────────────────────────────────────────────────────────────┤
│  handlers/                          users/                  │
│  ├── user_handler.go                ├── handler.go           │
│  ├── order_handler.go               ├── service.go           │
│  ├── payment_handler.go             ├── repository.go        │
│                                    │                        │
│  services/                          orders/                 │
│  ├── user_service.go                ├── handler.go           │
│  ├── order_service.go               ├── service.go           │
│  ├── payment_service.go             ├── repository.go        │
│                                    │                        │
│  Advantages:                        Advantages:              │
│  - Easy to enforce layer rules      - High cohesion          │
│  - Simple for small projects        - Clear ownership        │
│  - Familiar to Spring engineers     - Easy to extract        │
│                                    │                        │
│  Disadvantages:                     Disadvantages:           │
│  - Low cohesion per change          - Duplication risk       │
│  - Hard to find related code        - Harder cross-cutting   │
│  - Does not scale past ~15 types    - Requires discipline    │
└─────────────────────────────────────────────────────────────┘
```

### The Go Compiler as Architecture Enforcer

In Go, these are compile-time errors:
- **Cyclic imports** (`package a imports b imports a`)
- **Unused imports** (the compiler rejects them)
- **Unexported identifiers** used from another package
- **Init cycle** (init functions that circularly depend)

This is profoundly different from Java/Spring. In Spring, `@Autowired` can create circular dependencies that only manifest at runtime. In Go, the compiler stops you from creating a circular package dependency graph. This means your package dependency graph IS your architecture—enforced at build time, not at code review time.

### Three-Tier Structural Model

```
┌──────────────────────────────────────────────────────────────────┐
│  SIZE           │  STRUCTURE           │  WHEN TO USE            │
├──────────────────────────────────────────────────────────────────┤
│  Small          │  Flat packages       │  1-3 engineers, <20     │
│  (solo/startup) │  cmd/ + internal/    │  domain types, rapid    │
│                 │  + domain packages   │  iteration              │
├──────────────────────────────────────────────────────────────────┤
│  Medium         │  Feature packages    │  5-10 engineers, 20-100 │
│  (growing team) │  + shared kernel     │  domain types, multiple │
│                 │  + internal/common   │  features in parallel   │
├──────────────────────────────────────────────────────────────────┤
│  Enterprise     │  go.work monorepo    │  20+ engineers, 100+    │
│  (large team)   │  + multiple modules  │  domain types, multiple │
│                 │  + bounded contexts   │  teams, independent     │
│                 │                      │  deploy cycles          │
└──────────────────────────────────────────────────────────────────┘
```

## Internal Architecture

### How `go build` Resolves Packages

Understanding the compiler's package resolution is essential for understanding why Go project layout works the way it does:

```
$ go build ./cmd/server
│
├── 1. Read go.mod for module path (e.g., "github.com/company/payments")
│
├── 2. Resolve import paths relative to module root
│       import "github.com/company/payments/internal/users"
│       → looks at <module_root>/internal/users/
│
├── 3. For each package, read all .go files (excluding _test.go)
│       → Determine package name (must match directory name or be consistent)
│       → Build export set (capitalized identifiers)
│
├── 4. Build dependency graph
│       → Check for cycles → compile error if found
│       → Topological sort for compilation order
│
├── 5. Compile packages in dependency order
│       → Each package compiles to an .a archive
│       → Main package links into final binary
│
└── 6. Emit single static binary
        → No classpath, no JARs, no WARs
        → One binary per cmd/ entry point
```

### The `internal/` Visibility Rule

This is Go's most important structural primitive and one that Java engineers often miss:

```
myproject/
├── internal/
│   ├── users/
│   │   └── service.go       // package users
│   └── payments/
│       └── processor.go     // package payments
├── pkg/
│   └── client/
│       └── api.go           // package client (public API)
└── cmd/
    └── server/
        └── main.go          // package main

Compilation rules:
✅ cmd/server/main.go can import internal/users and internal/payments
✅ internal/payments can import internal/users
❌ External projects importing "github.com/company/myproject" cannot
   import ANYTHING under internal/
❌ Even sibling Go modules (in go.work) cannot import each other's internal/
✅ pkg/client CAN be imported by external projects
```

The `internal/` directory is enforced by the Go compiler, not by convention. Any package under a directory named `internal` can only be imported by code within the tree rooted at the parent of `internal`. This is Go's answer to Java's `package-private` visibility but enforced by the compiler across package boundaries.

### `cmd/` vs `pkg/` vs `internal/` — The Definitive Guide

```
┌──────────────────────────────────────────────────────────────────────┐
│  DIRECTORY   │  PURPOSE                    │  IMPORTABLE BY          │
├──────────────────────────────────────────────────────────────────────┤
│  cmd/        │  Entry points (main)        │  Nothing (it's main)    │
│              │  One binary per subdir      │  Do NOT put logic here  │
│              │  Wire dependencies here     │                         │
├──────────────────────────────────────────────────────────────────────┤
│  internal/   │  Private application code   │  Only this module       │
│              │  NEVER import from outside  │  Compiler-enforced      │
│              │  Can have sub-internal/     │  private visibility     │
├──────────────────────────────────────────────────────────────────────┤
│  pkg/        │  Public library code        │  Any module             │
│              │  Deliberately designed API  │  Treat as public API    │
│              │  Semantic versioning        │  Breaking changes hurt  │
├──────────────────────────────────────────────────────────────────────┤
│  api/        │  API definitions            │  Internal or external   │
│              │  .proto, OpenAPI specs      │  depending on intent    │
│              │  Generated client/server    │                         │
├──────────────────────────────────────────────────────────────────────┤
│  configs/    │  Configuration files        │  Not Go code            │
│              │  YAML, JSON, TOML           │  Embedded via //go:embed│
│              │  Per-environment configs    │                         │
├──────────────────────────────────────────────────────────────────────┤
│  scripts/    │  Build, deploy, migration   │  Not Go code            │
│              │  Makefile, shell scripts    │  CI/CD helpers          │
├──────────────────────────────────────────────────────────────────────┤
│  test/       │  Integration/E2E tests      │  _test.go suffix allows │
│              │  Test fixtures, testdata/   │  access to internal/    │
├──────────────────────────────────────────────────────────────────────┤
│  docs/       │  Design docs, ADRs          │  Not Go code            │
└──────────────────────────────────────────────────────────────────────┘
```

## Runtime Behavior

Unlike Spring Boot where `@SpringBootApplication` triggers component scanning, auto-configuration, and a cascade of framework initialization, a Go/Chi application starts from `main()` and does exactly what you tell it to.

### Application Startup: Go/Chi vs Spring Boot

```
SPRING BOOT STARTUP (implicit):
─────────────────────────────────
@SpringBootApplication  ← annotation triggers:
  1. @ComponentScan → scans ALL packages for @Component
  2. @EnableAutoConfiguration → reads spring.factories, auto-configures
  3. @Configuration → process @Bean methods
  4. Embedded Tomcat starts
  5. DispatcherServlet registered
  6. Request mapping table built from @RequestMapping annotations
  7. Application context fully initialized
  8. Application ready

Time: 2-10 seconds depending on classpath scanning

GO/CHI STARTUP (explicit):
─────────────────────────────────
func main() {
  1. cfg := config.Load()          // read config (10-50ms)
  2. db := postgres.Connect(cfg)   // connect to DB (50-200ms)
  3. repo := users.NewRepo(db)     // explicit constructor
  4. svc := users.NewService(repo) // explicit constructor
  5. h := users.NewHandler(svc)    // explicit constructor
  6. r := chi.NewRouter()          // create router
  7. r.Get("/users/{id}", h.Get)   // explicit route registration
  8. http.ListenAndServe(":8080", r)  // start server
}
// Total: 100-300ms. No scanning. No reflection. No surprises.
```

This explicitness is the defining characteristic of Go services. There is no magic. There is no "why is this bean being created?" There is no "which profile is active?" The startup sequence is a linear chain of function calls visible in a single file: `cmd/server/main.go`.

### Request Handling: net/http vs Servlet

```
Spring Boot (Servlet model):
────────────────────────────
Request → Filter Chain → DispatcherServlet → HandlerMapping
→ HandlerAdapter → Controller.method() → ViewResolver/MessageConverter
→ Response
(10+ layers of abstraction, each with its own lifecycle)

Go/Chi (net/http model):
────────────────────────────
Request → Middleware Chain → Router → Handler.ServeHTTP(w, r)
(3-4 layers, all explicit, all in your code)

Chi adds:
- Route parameters: chi.URLParam(r, "id")
- Middleware: r.Use(middleware.Logger)
- Route groups: r.Route("/api", func(r chi.Router) { ... })
- No reflection, no annotations, no bytecode manipulation
```

### The Binary: Self-Contained, No Runtime Dependencies

```
$ go build ./cmd/server
$ ls -lh server
-rwxr-xr-x  1 user  group  12M Jun  3 10:00 server

$ ldd server
        not a dynamic executable

$ ./server
2026/06/03 10:00:01 server starting on :8080

The binary contains:
- Your code
- All Go standard library code
- All third-party dependency code
- The Go runtime (scheduler, GC, memory allocator)
- NO JDK, NO Tomcat, NO classpath, NO JAR files
```

## Request Flow Diagrams

### Simple Request Through Chi Router

```
                    CLIENT
                      │
                      ▼
              ┌───────────────┐
              │  net/http      │  ← Accepts TCP connection
              │  Server        │  ← Creates goroutine per request
              └───────┬───────┘
                      │
                      ▼
              ┌───────────────┐
              │  Middleware    │  ← RequestID, Logger, Recoverer
              │  Stack         │  ← Each wraps the next handler
              │  (in order)    │
              └───────┬───────┘
                      │
                      ▼
              ┌───────────────┐
              │  chi.Router    │  ← Pattern matching on method+path
              │  (radix tree)  │  ← /users/{id} → handler
              └───────┬───────┘
                      │
                      ▼
              ┌───────────────┐
              │  Handler       │  ← Extract params, validate input
              │  ServeHTTP()   │
              └───────┬───────┘
                      │
                      ▼
              ┌───────────────┐
              │  Service       │  ← Business logic, orchestration
              │  (interface)   │
              └───────┬───────┘
                      │
                      ▼
              ┌───────────────┐
              │  Repository    │  ← Database query, external API call
              │  (interface)   │
              └───────┬───────┘
                      │
                      ▼
              ┌───────────────┐
              │  Database      │  ← PostgreSQL, Redis, etc.
              └───────────────┘
```

### Request Lifecycle Timeline (Goroutine Model)

```
Time ──────────────────────────────────────────────────────────────►

Goroutine 1: [Accept] [Parse] [Middleware] [Route] [Handle] [DB Query] [Respond]
Goroutine 2:          [Accept] [Parse] [Middleware] [Route] [Handle] [DB Query] [Respond]
Goroutine 3:                    [Accept] [Parse] [Middleware] [Route] [Handle] [DB Query]

Key difference from thread-per-request:
- Goroutines are ~2KB initial stack (threads are ~1MB)
- 10,000 concurrent requests = ~20MB of goroutine memory
- 10,000 concurrent requests in Java/Spring = ~10GB of thread memory (before pooling)
- Goroutines are multiplexed onto OS threads (GOMAXPROCS)
- No thread pool exhaustion to configure
```

## Lifecycle Diagrams

### Application Lifecycle (Go/Chi vs Spring Boot)

```
GO/CHI APPLICATION LIFECYCLE:
─────────────────────────────────────────────────────────────────
                   ┌──────────┐
                   │  main()  │
                   └────┬─────┘
                        │
            ┌───────────▼───────────┐
            │  config.Load()        │  ← Read env vars, config files
            └───────────┬───────────┘
                        │
            ┌───────────▼───────────┐
            │  Initialize Deps      │  ← DB pool, Redis, Kafka, etc.
            │  (manual wiring)      │  ← Each dependency is explicit
            └───────────┬───────────┘
                        │
            ┌───────────▼───────────┐
            │  Build Handler Tree   │  ← Wire handlers to router
            │  Register Routes      │  ← Explicit route registration
            └───────────┬───────────┘
                        │
            ┌───────────▼───────────┐
            │  http.ListenAndServe  │  ← Start accepting connections
            │  (blocking call)      │
            └───────────┬───────────┘
                        │
              ┌─────────▼─────────┐
              │  Running          │◄──── SIGTERM/SIGINT
              │  Accepting reqs   │
              └─────────┬─────────┘
                        │
            ┌───────────▼───────────┐
            │  Graceful Shutdown    │  ← Stop accepting new reqs
            │  Drain in-flight      │  ← Wait for active requests
            │  Close DB pools       │  ← Clean up resources
            └───────────┬───────────┘
                        │
                    ┌───▼───┐
                    │ Exit  │
                    └───────┘

SPRING BOOT LIFECYCLE (for contrast):
─────────────────────────────────────────────────────────────────
  main() → SpringApplication.run()
    → Environment prepared
    → Banner printed
    → ApplicationContext created
    → @Conditional evaluations
    → Auto-configuration applied
    → Component scanning
    → Bean instantiation
    → Bean post-processing
    → Embedded server started
    → ApplicationRunner/CommandLineRunner
    → Application ready
    [MANY implicit steps, much harder to debug]
```

### Module/Workspace Lifecycle

```
SINGLE MODULE (small/medium projects):
─────────────────────────────────────
go.mod:
  module github.com/company/myapp
  go 1.22

  require (
      github.com/go-chi/chi/v5 v5.0.12
      github.com/jackc/pgx/v5 v5.5.0
  )

All packages share one go.mod.
One version of each dependency.
One go.sum lockfile.
Simple, sufficient for most teams.

MULTI-MODULE WORKSPACE (enterprise):
─────────────────────────────────────
go.work:
  go 1.22

  use (
      ./services/users
      ./services/orders
      ./services/payments
      ./shared/kit
  )

services/users/go.mod:
  module github.com/company/services/users
  // independent versions, independent CI

services/orders/go.mod:
  module github.com/company/services/orders
  // can upgrade dependencies independently

shared/kit/go.mod:
  module github.com/company/shared/kit
  // shared types, utilities, no business logic

Each module has its own dependency graph.
go.work ties them together for local development.
In CI, each module builds independently.
Can deploy independently when each is a separate service.
```

## Source Code Reading Guide

### Small Project Structure (Solo/Startup, 1-3 Engineers)

**When to use**: You're building an MVP, a solo project, or a service with fewer than 20 domain types. Speed of iteration matters more than architectural purity.

```
myapp/
├── cmd/
│   └── server/
│       └── main.go              ← Entry point, wire everything here
├── internal/
│   ├── handler/                 ← HTTP handlers (thin, delegate to service)
│   │   ├── users.go
│   │   ├── orders.go
│   │   └── payments.go
│   ├── service/                 ← Business logic
│   │   ├── users.go
│   │   ├── orders.go
│   │   └── payments.go
│   ├── postgres/                ← Database access
│   │   ├── users.go
│   │   ├── orders.go
│   │   └── payments.go
│   └── model/                   ← Domain types, shared across layers
│       ├── user.go
│       ├── order.go
│       └── payment.go
├── go.mod
├── go.sum
└── README.md
```

**Reading order for this structure:**
1. `go.mod` — understand the module path and dependencies
2. `cmd/server/main.go` — see how everything is wired together
3. `internal/model/` — understand the domain types
4. `internal/handler/` — see the HTTP surface area
5. `internal/service/` — understand the business logic
6. `internal/postgres/` — see how data is persisted

**What to ignore**: Don't worry about package boundaries—they're intentionally loose. Don't worry about the lack of interfaces—they'll be added when needed for testing.

**Team ownership map:**
```
cmd/server/                 → Whoever is on call (single team)
internal/handler/           → Same team
internal/service/           → Same team
internal/postgres/          → Same team
internal/model/             → Same team
```

**When this structure breaks:**
- `handler/` grows past ~15 files — too much to scan
- `service/` has cross-domain orchestration — coupling increases
- Developers can't find code without searching — cognitive load too high
- Feature work touches files across all packages — cohesion is low

### Medium Project Structure (5-10 Engineers)

**When to use**: You have multiple features being developed in parallel. Domain concepts have stabilized. The team needs clear ownership boundaries.

```
myapp/
├── cmd/
│   └── server/
│       └── main.go              ← Wire everything here (single entry point)
├── internal/
│   ├── users/                   ← Feature package: everything about users
│   │   ├── handler.go           ← HTTP handlers for /users/*
│   │   ├── service.go           ← Business logic
│   │   ├── postgres.go          ← PostgreSQL repository
│   │   ├── models.go            ← Domain types
│   │   ├── dto.go               ← Request/response DTOs
│   │   ├── errors.go            ← Domain-specific errors
│   │   └── handler_test.go
│   ├── orders/                  ← Feature package: everything about orders
│   │   ├── handler.go
│   │   ├── service.go
│   │   ├── postgres.go
│   │   ├── models.go
│   │   ├── dto.go
│   │   └── handler_test.go
│   ├── payments/                ← Feature package
│   │   ├── handler.go
│   │   ├── service.go
│   │   ├── postgres.go
│   │   ├── models.go
│   │   ├── dto.go
│   │   └── handler_test.go
│   ├── common/                  ← Shared across features
│   │   ├── middleware/          ← Authentication, logging, CORS
│   │   ├── auth/                ← JWT, sessions, permissions
│   │   ├── httputil/            ← Response helpers, error rendering
│   │   └── validate/            ← Input validation
│   └── postgres/                ← Database connection pool
│       └── pool.go
├── api/                         ← API specifications
│   └── openapi.yaml
├── configs/                     ← Configuration files
│   ├── config.yaml
│   └── config.production.yaml
├── scripts/                     ← Migration scripts, deployment
│   ├── migrate.sh
│   └── deploy.sh
├── go.mod
├── go.sum
└── Makefile
```

**Reading order for this structure:**
1. `go.mod` — module path, major dependencies
2. `cmd/server/main.go` — wiring, middleware stack, route registration
3. `internal/common/` — shared infrastructure
4. Pick ONE feature (e.g., `internal/users/`) and trace it:
   - `handler.go` → `service.go` → `postgres.go` → `models.go`
5. `configs/` — configuration structure

**What to ignore**: Inter-feature dependencies — understand them when you need to. Internal implementation details of other features — trust the feature boundary.

**Team ownership map:**
```
internal/users/             → Team Alpha (User & Auth)
internal/orders/            → Team Beta (Commerce)
internal/payments/          → Team Gamma (Payments)
internal/common/            → Platform team (or shared ownership)
internal/postgres/          → Platform team (infrastructure)
cmd/server/main.go          → Platform team (with approval from all)
configs/                    → Platform/DevOps
```

**Cross-team collaboration rules:**
- Feature packages can import `internal/common/` — no approval needed
- Feature packages can import sibling feature's **models** (not services/repos) — with approval
- Feature packages MUST NOT import sibling feature's service or repository packages
- `cmd/server/main.go` changes require 2 approvals (platform + affected team)

### Enterprise Project Structure (20+ Engineers)

**When to use**: Multiple teams owning different bounded contexts. Independent deploy cycles. Need to version APIs. Different scaling characteristics per domain. This is the most important structure—it's where Staff/Principal engineers spend most of their time.

```
payments-platform/
├── go.work                      ← Ties modules together for local dev
├── services/
│   ├── user-service/
│   │   ├── cmd/
│   │   │   └── server/
│   │   │       └── main.go
│   │   ├── internal/
│   │   │   ├── users/           ← Feature packages within the service
│   │   │   │   ├── handler.go
│   │   │   │   ├── service.go
│   │   │   │   ├── postgres.go
│   │   │   │   └── models.go
│   │   │   └── auth/
│   │   │       ├── handler.go
│   │   │       ├── service.go
│   │   │       └── models.go
│   │   ├── go.mod               ← github.com/company/user-service
│   │   └── go.sum
│   ├── order-service/
│   │   ├── cmd/server/main.go
│   │   ├── internal/
│   │   │   ├── orders/
│   │   │   └── fulfillment/
│   │   ├── go.mod               ← github.com/company/order-service
│   │   └── go.sum
│   └── payment-service/
│       ├── cmd/server/main.go
│       ├── internal/
│       │   ├── payments/
│       │   ├── ledger/
│       │   └── settlement/
│       ├── go.mod               ← github.com/company/payment-service
│       └── go.sum
├── shared/                      ← Cross-cutting shared code
│   ├── kit/                     ← Framework-level utilities
│   │   ├── middleware/
│   │   ├── telemetry/
│   │   ├── httputil/
│   │   └── go.mod               ← github.com/company/shared-kit v1.2.0
│   ├── contracts/               ← Inter-service API contracts
│   │   ├── user-api/
│   │   │   ├── openapi.yaml
│   │   │   └── gen/             ← Generated Go client
│   │   └── go.mod
│   └── protos/                  ← gRPC protobuf definitions
│       ├── users/v1/
│       │   └── users.proto
│       ├── orders/v1/
│       │   └── orders.proto
│       └── go.mod
├── deployments/                 ← Kubernetes manifests, Helm charts
├── infrastructure/              ← Terraform, Pulumi
├── docs/
│   └── adr/                     ← Architecture Decision Records
└── Makefile
```

**Reading order for this structure:**
1. `go.work` — understand which modules exist and how they relate
2. `docs/adr/` — understand WHY the system is structured this way
3. Pick ONE service and read its `go.mod` and `cmd/server/main.go`
4. `shared/kit/` — understand shared infrastructure
5. `shared/contracts/` — understand inter-service contracts
6. One service's `internal/` — understand domain logic

**What to ignore**: Other services' internal details. Contract files for services you're not integrating with. Deployment configs unless doing DevOps work.

**Team ownership map (Conway's Law applied):**
```
services/user-service/          → Identity & Access Team (5 engineers)
  Owned by: Team Identity
  SLA: 99.9%, p99 < 50ms
  On-call: Identity rotation

services/order-service/         → Commerce Team (6 engineers)
  Owned by: Team Commerce
  SLA: 99.99%, p99 < 100ms
  On-call: Commerce rotation

services/payment-service/       → Payments Team (6 engineers)
  Owned by: Team Payments
  SLA: 99.999%, p99 < 200ms
  On-call: Payments rotation

shared/kit/                     → Platform Team (4 engineers)
  Owned by: Team Platform
  Versioned releases (semver)
  All service teams are consumers
  Changes require RFC + migration guide

shared/contracts/               → Platform + Service Teams (shared)
  Each contract owned by its producer service
  Consumer teams can request changes via PR
  Breaking changes require major version bump

infrastructure/                 → Platform/DevOps (3 engineers)
  CI/CD, Kubernetes, Terraform
```

### When to Split Packages

```
Decision Framework: Should I create a new package?

ASK THESE QUESTIONS IN ORDER:

1. Is the code consumed by a different team?
   → YES: Create a new package. Ownership boundary.
   → NO: Continue.

2. Does the code have a different release cadence?
   → YES: Create a new module (separate go.mod). Deployment boundary.
   → NO: Continue.

3. Are the types/concepts independent enough to be understood in isolation?
   → YES: Consider a new package. Cognitive boundary.
   → NO: Continue.

4. Does the package have > 10 files or > 2000 lines?
   → YES: Split the package. Navigability boundary.
   → NO: Continue.

5. Is there a natural domain boundary (bounded context)?
   → YES: Create a new package. Domain boundary.
   → NO: Keep in current package.

ANTI-PATTERNS TO AVOID:
- "One struct per file" → unnecessary splitting
- "One function per file" → Go convention is related functions together
- "Package per layer" → leads to low cohesion at project scale
- "types.go, interfaces.go, errors.go" → split by domain, not by kind
```

### When to Create Modules

```
When to add a new go.mod (new module):

✅ INDEPENDENT RELEASE CYCLE
   "The payments team deploys twice a week. The user team deploys monthly.
   They should be separate modules so payments can upgrade dependencies
   without waiting for users."

✅ INDEPENDENT VERSIONING
   "The shared kit library is at v2.3.1. It has a stable API.
   Services depend on specific versions. This requires a separate module."

✅ DIFFERENT TEAMS, DIFFERENT DEPENDENCIES
   "The order service needs Kafka, the user service doesn't.
   Separate modules avoid carrying unused dependencies."

✅ EXTRACTABLE MICROSERVICE CANDIDATE
   "The payment service might become a separate deployable. A separate
   module now makes the eventual extraction trivial."

❌ PREMATURE MODULARIZATION
   "We have 2 developers and 5 packages. Do NOT create modules yet.
   The overhead of go.work and cross-module coordination exceeds the benefit."

❌ EVERY PACKAGE IS A MODULE
   "No. A Go module is a versioning boundary, not a namespace boundary.
   Multiple packages can (and should) coexist in one module."
```

### Go Workspaces (go.work) for Multi-Module Development

```
WHY go.work EXISTS:
─────────────────────────────────────────────────────────────
Problem: You have 3 separate modules in a monorepo:
  services/users/go.mod   → module github.com/company/services/users
  services/orders/go.mod  → module github.com/company/services/orders
  shared/kit/go.mod       → module github.com/company/shared/kit

Without go.work:
  - orders importing kit requires a `replace` directive in go.mod
    replace github.com/company/shared/kit => ../../shared/kit
  - This is a development hack. It should NEVER be committed.
  - Every developer must manually add replace directives.

With go.work:
  go.work at repo root:
    go 1.22
    use (
        ./services/users
        ./services/orders
        ./shared/kit
    )
  - The Go toolchain automatically resolves local module paths
  - go.work is committed (it's the source of truth for workspace layout)
  - go.work.sum is committed (locks workspace dependency versions)
  - CI uses go.work for building; production deploys from go.mod versions

RULES OF go.work:
─────────────────────────────────────────────────────────────
1. go.work is for DEVELOPMENT only.
   In production, each service deploys using its go.mod versions.
2. go.work.sum MUST be committed.
   It locks dependency versions across the workspace.
3. go.work should be at the repo root.
   It's the entry point for IDEs and tooling.
4. Each module in `use` must have its own go.mod.
   go.work does not replace go.mod — it augments it.
5. module paths must match.
   If go.mod says "github.com/company/services/users", the import path
   must be "github.com/company/services/users/internal/...".

TYPICAL WORKFLOW:
─────────────────────────────────────────────────────────────
# Developer starts working:
$ cd services/users
$ go run ./cmd/server          # go.work auto-resolves local modules

# CI builds:
$ go work sync                 # sync workspace dependencies
$ cd services/users
$ go build ./cmd/server         # builds with workspace resolution
$ go test ./...                 # tests with workspace resolution

# Production deploy:
$ cd services/users
$ go build ./cmd/server         # build with go.mod versions (no workspace)
$ docker build -t users-service .
```

## Production Failure Scenarios

### Scenario 1: The "Where Is This File?" Anti-Pattern

**Symptom**: Developers spend 15+ minutes finding the right file for a bug fix. On-call engineers open the wrong file 3 times before finding the right one.

**Root Cause**: Package-by-layer structure in a large project. The `handler/` package has 47 files. The `service/` package has 52 files. Finding `UpdateUserEmail` means searching across both packages with no structural hints.

**Example of the problem:**
```
internal/
├── handler/      ← 47 files
├── service/      ← 52 files
├── repository/   ← 38 files
└── model/        ← 41 files

To change "user email update":
1. Find handler: grep "email" handler/ → 12 results, guess which one
2. Find service: grep "email" service/ → 8 results, guess which one
3. Find repository: grep "email" repository/ → 5 results
4. Find model: grep "email" model/ → 3 results
Time: ~15 minutes. Cognitive overhead: high.
```

**Fix**: Restructure to feature packages.
```
internal/
├── users/        ← handler.go, service.go, postgres.go, models.go
│   Open users/ → all user code is visible at a glance.
│   Change "user.email": edit users/models.go, users/service.go.
│   Time: ~2 minutes. Cognitive overhead: low.
```

### Scenario 2: Circular Import Hell

**Symptom**: `go build` fails with "import cycle not allowed". You stare at the graph trying to find the cycle.

**Root Cause**: Packages importing each other, often through intermediary packages. This is Go's compiler enforcing that your package dependency graph is a DAG.

**Example:**
```
package orders imports users    (to get user info for orders)
package users imports orders    (to get order history for users)
→ CYCLE: orders → users → orders

Fix options:
1. Extract shared types to a separate package:
   users/types/  ← importable by both
   orders/types/ ← importable by both
2. Use interfaces to break the dependency:
   orders defines UserProvider interface
   users implements it (but doesn't import orders)
3. Introduce a mediator package that both depend on
4. Reconsider the design: should orders and users really know about each other?
```

### Scenario 3: The Monolith That Cannot Split

**Symptom**: The team wants to extract a service but every package imports every other package. Migration is impossible without a full rewrite.

**Root Cause**: No module boundaries. Everything is one `go.mod`. The `internal/` directory creates a false sense of boundaries because everything under `internal/` can import everything else under `internal/`.

**Prevention**: Use `go.work` + separate modules BEFORE you need to split. The module boundary becomes the eventual service boundary. Even if you deploy as a monolith today, the module boundary gives you the option to split.

## Debugging Techniques

### Diagnosing Import Cycles

```bash
# Find all import cycles in your package graph
go vet ./...

# Visualize the dependency graph
go mod graph | grep "^github.com/company/" | sort

# Use goimports to find unused/broken imports
goimports -l -w ./...

# Find which packages import a specific package
rg "github.com/company/myapp/internal/users" --include="*.go"
```

### Diagnosing Structural Problems

```bash
# Count files per package (identify god-packages)
find . -name "*.go" -not -name "*_test.go" | \
  awk -F/ '{print $(NF-1)}' | sort | uniq -c | sort -rn

# Find packages with excessive imports (high coupling)
for pkg in $(find . -name "*.go" -not -name "*_test.go" -exec dirname {} \; | sort -u); do
  count=$(grep -rh "^import" "$pkg" | grep -v "// import" | wc -l)
  echo "$count $pkg"
done | sort -rn | head -20

# Find packages imported by most other packages (high fan-in, acceptable)
rg "github.com/company/myapp/internal/([^/]+)" --include="*.go" -o | \
  sort | uniq -c | sort -rn
```

## Observability Considerations

### Structure-Level Observability

The project structure itself should be observable. You should be able to answer these questions without reading code:

1. **What are the bounded contexts?** → Each top-level directory under `internal/` or each module in `go.work`
2. **Who owns each context?** → CODEOWNERS file maps directories to teams
3. **What are the inter-context dependencies?** → `go mod graph` for module-level; import analysis for package-level
4. **What is the change frequency per context?** → git log per directory
5. **What is the bug density per context?** → git log --grep="fix" per directory

```
# Generate a CODEOWNERS file from directory ownership
cat > .github/CODEOWNERS << 'EOF'
# Identity & Access Team
/services/user-service/      @team-identity
/shared/contracts/user-api/  @team-identity

# Commerce Team
/services/order-service/     @team-commerce
/shared/contracts/order-api/  @team-commerce

# Payments Team
/services/payment-service/   @team-payments
/shared/contracts/payment-api/ @team-payments

# Platform Team
/shared/kit/                 @team-platform
/infrastructure/             @team-platform
/deployments/                @team-platform

# Shared ownership
/go.work                     @team-platform @team-identity @team-commerce @team-payments
EOF
```

## Performance Implications

### Compile-Time Performance

```
FACTORS AFFECTING COMPILE TIME:
─────────────────────────────────────────────────────────────
1. Number of packages → more packages = more compilation units
   (but Go compiles packages in parallel)

2. Package dependency depth → deeper graph = less parallelism
   (Go compiles packages in dependency order; fan-out enables parallelism)

3. CGO usage → CGo disables cross-compilation and slows builds
   (pure Go compiles ~10x faster)

4. Code generation → generated code adds to compilation time
   (but generated code is typically straightforward and compiles fast)

OPTIMIZING FOR COMPILE TIME:
- Flat dependency graph (many leaf packages, few deep chains)
- Minimal CGo
- Generated code in separate packages (so it's compiled once)
- Use go.work to scope compilation to relevant modules

TYPICAL NUMBERS (M1 MacBook Pro):
- 10 packages, 5K lines:    ~1 second
- 50 packages, 50K lines:   ~5 seconds
- 200 packages, 200K lines: ~20 seconds
- 500 packages, 500K lines: ~60 seconds (with parallelism)
- Compare: equivalent Java/Spring project → 2-5 minutes
```

### Binary Size

```
BINARY SIZE FACTORS:
─────────────────────────────────────────────────────────────
1. Import graph size → every imported package contributes code
2. Reflection usage → prevents dead code elimination
3. net/http → the standard library HTTP package is ~2MB compiled
4. Database drivers → pgx is ~3MB, sqlite is ~2MB

TYPICAL SIZES:
- Minimal Chi service (no DB):    ~8MB
- Chi + pgx + JWT + validation:   ~15MB
- Full monolith (all features):   ~30-50MB

COMPARISON:
- Equivalent Spring Boot JAR:         ~50-80MB (plus JDK)
- Single Go binary:                  ~15-50MB (no runtime needed)
- Docker image (Go, distroless):     ~20MB
- Docker image (Java, adoptopenjdk): ~300MB+
```

## Architecture Implications

### Structure Encodes Communication Patterns

Conway's Law states that organizations design systems that mirror their communication structure. In Go projects, this is encoded in the directory structure:

```
ORGANIZATION       →      SYSTEM STRUCTURE
─────────────────────────────────────────────────────────────
Single team       →      Single module, feature packages
                         internal/users/
                         internal/orders/

Multiple teams    →      Multiple modules, bounded contexts
                         services/user-service/go.mod
                         services/order-service/go.mod

Platform + Apps   →      shared/kit/ as shared infrastructure
                         Each app as separate service module

Outsourced team   →      Clearly defined API contract
                         shared/contracts/ with versioned APIs
                         ACL (anti-corruption layer) in consuming service
```

### The Module as a Deployment Boundary

```
A Go module (go.mod) IS NOT just a dependency management tool.
It IS a deployment boundary waiting to happen.

Every module you create today that shares a go.work:
- CAN be deployed as one binary (monolith) today
- CAN be deployed as separate binaries (services) tomorrow
- WITHOUT changing any import paths
- WITHOUT restructuring internal packages

The module path becomes the service name:
  github.com/company/services/users   → user-service
  github.com/company/services/orders  → order-service

The cmd/ becomes the entry point:
  services/users/cmd/server/main.go   → user-service binary
  services/orders/cmd/server/main.go  → order-service binary
```

## Team Ownership Implications

### Go-Specific Ownership Patterns

```
PATTERN 1: Package-Level Ownership
─────────────────────────────────────────────────────────────
Each feature package under internal/ is owned by exactly one team.
internal/users/   → Team Alpha
internal/orders/  → Team Beta

Advantages:
- Clear accountability for every file
- No confusion about who to ask for code review
- CODEOWNERS is a simple 1:1 mapping

Disadvantages:
- Cross-cutting changes require multi-team coordination
- Shared packages (internal/common/) have ambiguous ownership

PATTERN 2: CODEOWNERS + Module Ownership
─────────────────────────────────────────────────────────────
For enterprise (go.work) structures:

# .github/CODEOWNERS
services/user-service/          @team-identity
services/order-service/         @team-commerce
services/payment-service/       @team-payments
shared/kit/                     @team-platform        # CODEOWNERS review required
shared/contracts/user-api/      @team-identity        # Producer owns contract
shared/contracts/order-api/     @team-commerce
shared/contracts/payment-api/   @team-payments

PATTERN 3: The "Internal Import" Rule
─────────────────────────────────────────────────────────────
Teams own their feature packages. Cross-feature imports require:

Level 1: Import models/types only → Auto-approved (if models package exists)
Level 2: Import service interface → PR review by owning team
Level 3: Import service implementation → FORBIDDEN
Level 4: Import repository → FORBIDDEN

Enforced by golangci-lint rules (not by compiler).
```

## Interview Questions

### Q1: "When would you recommend a flat package structure vs. a feature-based structure for a Go service?"

**Answer**: Flat (package-by-layer) for projects with fewer than ~20 domain types and 1-3 engineers, where iteration speed matters more than long-term maintainability. The key indicator for switching to feature-based is when developers consistently can't find code without grep. Feature-based scales because it organizes code by what the business cares about, not by technical layer. The threshold isn't lines of code—it's cognitive load on the team. I've seen 50K-line projects thrive with flat structure because the domain was simple, and 10K-line projects struggle because the domain concepts were deeply nested.

### Q2: "How does Go's package system enforce architecture better than Spring's?"

**Answer**: Three compiler-enforced rules make the difference. First, cyclic imports are compile errors—you cannot create circular dependencies between packages. Spring allows `@Autowired` circular references that only fail at runtime. Second, `internal/` visibility is enforced by the compiler, not by code review. Third, unused imports are errors, so every dependency is intentional and visible. The practical effect is that the Go compiler validates your dependency graph on every build. You don't need ArchUnit because the compiler IS ArchUnit.

### Q3: "What's the relationship between modules, packages, and workspaces in a monorepo?"

**Answer**: A Go module (`go.mod`) is a versioning and dependency boundary. A package is a compilation unit (a directory of `.go` files). A workspace (`go.work`) ties multiple modules together for local development. In a monorepo, I use modules as eventual service boundaries: each potential service gets its own `go.mod`. During development, `go.work` makes local module resolution seamless. In production, each module builds independently from its `go.mod` versions. This gives us the monorepo's code sharing benefits during development and independent deployability for production.

### Q4: "When would you put code in `pkg/` vs `internal/`?"

**Answer**: `internal/` is for code that should never be imported by external consumers. It's compiler-enforced privacy. `pkg/` is for deliberately public library code with a stable API that external projects depend on. The rule: start everything in `internal/`. Only move to `pkg/` when there's a demonstrated need for external consumption AND you're willing to maintain backward compatibility. Most `pkg/` directories I've seen are premature—they expose internals that nobody outside the organization will ever import.

### Q5: "How do you handle a shared kernel across feature packages without creating a god-package?"

**Answer**: A shared kernel should contain ONLY what is universally agreed upon across all contexts: base value objects (Money, Email, TenantID), shared error types, and maybe common middleware. The test: if removing the shared kernel would require changes in more than two feature packages, it doesn't belong there—it belongs in a specific feature package. I enforce three rules: (1) the shared kernel has no dependencies on feature packages, (2) shared kernel types are immutable value objects, not mutable entities, (3) shared kernel changes require approval from all consuming teams. If a type is only consumed by 2 of 5 teams, it moves out of the shared kernel.

### Q6: "You join a company with a Go monolith that has 200 packages under `internal/`. How do you think about restructuring?"

**Answer**: I don't restructure. Restructuring a 200-package monolith is a recipe for a year-long migration that delivers zero business value and breaks everything. Instead, I use the "strangler fig" pattern: (1) identify bounded contexts within the existing structure, (2) define new module boundaries for these contexts, (3) new features are built in the new modules, (4) existing code is gradually extracted as it's touched for other reasons. The key insight is that the import graph tells you where the real boundaries are. Run `go mod graph` and look for clusters of highly connected packages with sparse connections to the rest of the graph—those are your natural module boundaries.

### Q7: "What's wrong with putting business logic in `cmd/server/main.go`?"

**Answer**: `main.go` is an entry point and composition root—it should contain ONLY wiring logic: creating dependencies, injecting them, and starting the server. Business logic in `main.go` is untestable (it's package `main`, which Go testing conventions can't easily import), unreusable (you can't import `main` from another package), and creates a god-function that's impossible to understand. The `main()` function should read like a table of contents, not like a novel. If your `main()` is longer than 100 lines, you're doing it wrong.

### Q8: "How do you version internal packages that are shared across multiple services?"

**Answer**: Don't. `internal/` packages are not versioned—they are consumed at HEAD by the services within the same repository. If a package needs versioning, it moves to a separate module with its own `go.mod` and semantic version tags. This is the key difference: `internal/` means "this module's private code," not "versioned shared library." Shared code that needs versioning lives in `shared/kit/`, `pkg/`, or a separate repository. The Go toolchain enforces this: you cannot import another module's `internal/` packages.

## Hands-On Exercises

### Exercise 1: Structure Diagnosis

Take an existing Go project (or create a small one with 10+ packages). Answer these questions:
1. Can you find the handler for `POST /users` in under 30 seconds without grep?
2. How many packages does a typical feature change touch?
3. Is there a circular dependency anywhere? (Run `go vet ./...`)
4. Can you explain the package dependency graph to a new team member in 5 minutes?

**Expected outcome**: If any answer is "no" or ">5", the structure needs attention.

### Exercise 2: Restructure a Layered Project

Take a package-by-layer structured project (handler/, service/, repository/) and restructure it to feature-based structure (users/, orders/, payments/).
1. Create feature directories
2. Move files to their feature directory
3. Update import paths
4. Verify `go build ./...` succeeds
5. Run all tests
6. Compare: how many files changed per "add a field to user" type of change?

### Exercise 3: Create a Multi-Module Workspace

Create a mini-ecosystem with:
1. `shared/kit/` — a module with common middleware and error types
2. `services/users/` — a module importing shared/kit
3. `services/orders/` — a module importing shared/kit
4. `go.work` tying them together
5. Verify local development works with `go.work`
6. Verify independent builds work (each service builds from its go.mod)

### Exercise 4: Ownership Map

For your current project (or a hypothetical one):
1. Draw the directory tree
2. Map each directory to the team that owns it
3. Identify directories with ambiguous ownership
4. Write a CODEOWNERS file
5. Identify cross-team dependencies and their communication overhead

### Exercise 5: Import Analysis

Write a script that:
1. Lists all packages in the project
2. Counts how many packages import each package (fan-in)
3. Counts how many packages each package imports (fan-out)
4. Identifies packages with high fan-out (potential god-packages)
5. Suggests structural improvements based on the numbers

## Advanced Challenges

### Challenge 1: Design a Go Workspace Strategy for a 50-Engineer Organization

Design the module layout, `go.work` structure, shared code strategy, versioning policy, and team ownership model for a payments platform with 10 bounded contexts, 50 engineers across 8 teams, and regulatory requirements requiring PCI-DSS compliance for the payment context. Consider:
- How to isolate PCI-scoped code
- How to share non-PCI code efficiently
- How to version contracts between contexts
- How CI/CD pipelines differ per module
- How to handle shared database schemas vs. per-service databases

### Challenge 2: Migration Architecture

Design a migration strategy from a single-module, package-by-layer monolith (200 packages, 30 engineers, 5 years old) to a multi-module workspace with bounded contexts. The system must continue to serve production traffic throughout the migration. Define:
- Phase 1: Analysis (what to measure, what to look for)
- Phase 2: Strangulation (what to extract first, what last)
- Phase 3: Module creation (go.mod creation, go.work setup)
- Phase 4: Service extraction (from module to deployable service)
- Rollback strategy for each phase
- Success metrics for each phase
- Communication plan for engineering org

### Challenge 3: Structure Linting

Design (and optionally implement) a `golangci-lint` plugin or standalone tool that enforces your project's structural rules:
- No feature package imports another feature package's service/repository
- Shared kernel does not import feature packages
- `cmd/` packages only import, never export
- `internal/` structure follows a defined convention
- Import depth does not exceed a threshold
- Package naming follows convention

## Key Insights

1. **Go's compiler is your architecture validator.** Cyclic imports, unused imports, and `internal/` visibility are compiler-enforced. You don't need ArchUnit—you need the Go compiler.

2. **Structure is not about aesthetics—it's about ownership, cognitive load, and change velocity.** The right structure makes the right thing easy and the wrong thing hard.

3. **Package-by-layer works for 1-3 engineers and ~20 domain types. Beyond that, you need feature-based.** The threshold is not lines of code—it's the number of things developers need to keep in their head to make a change.

4. **`internal/` is the most underused Go feature.** It gives you compiler-enforced encapsulation across package boundaries. Use it. Start everything in `internal/` and export to `pkg/` only when necessary.

5. **Go modules are deployment boundaries waiting to happen.** Create module boundaries at domain seams, even if you deploy as a monolith. The module is your option value for future service extraction.

6. **Explicit wiring in `main.go` is not boilerplate—it's documentation.** The 50 lines of dependency construction in `main.go` tell you exactly how the system works. Spring's 500 annotations scattered across 50 files do not.

7. **The import graph IS the architecture.** In Go, you can read the `import` statements to understand the system's dependency structure. In Spring, you need to understand the DI container's resolution rules.

8. **Team ownership should be visible in the directory tree.** A new engineer should be able to look at the directory tree and understand who owns what. If they can't, the structure is wrong or the ownership is unclear.

9. **Shared code is a liability, not an asset.** Every line in `internal/common/` or `shared/kit/` creates a coupling point between teams. Share only what is universally agreed upon and stable.

10. **Start simple, add complexity only when needed.** A flat project with 10 packages that everyone understands is better than a feature-based structure with 50 packages that nobody fully understands. The structure should match the team's cognitive capacity.
