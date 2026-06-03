# Session 4: Domain-Driven Design: Strategic Patterns

## Why This Topic Exists

Domain-Driven Design (DDD) is not about tactical patterns like Entities, Value Objects, and Repositories. Those are the easy part. The hard part — and the part that matters at the Staff/Principal level — is **strategic design**: bounded contexts, context maps, and the organizational patterns that emerge when multiple teams model overlapping domains.

In Go, strategic DDD patterns map naturally to Go's primitives: bounded contexts map to packages or modules, context maps map to import graphs, and anti-corruption layers map to interface-based facades. The language itself provides the enforcement mechanisms that DDD requires.

Why this matters for Staff/Principal engineers:

1. **Bounded contexts are the scaling limit of any architecture.** If you don't define them explicitly, they emerge implicitly — usually in the form of "why does changing X break Y?"
2. **Context mapping determines team communication overhead.** A Partnership relationship between teams requires different coordination than a Conformist relationship.
3. **Ubiquitous language in code is the most undervalued practice.** When the code uses different terms than the business, bugs happen. Go's type system can encode business language precisely.
4. **The Anti-Corruption Layer is Go's killer app.** Go's interface system makes ACLs natural, testable, and compiler-enforced.

## Mental Model

### What Is Strategic DDD?

Strategic DDD answers one question: **"How do we decompose a large domain into pieces that teams can own independently?"**

```
TACTICAL DDD (within a bounded context):
  Entities, Value Objects, Aggregates, Repositories, Domain Services
  -> "How do I model THIS domain correctly?"

STRATEGIC DDD (across bounded contexts):
  Bounded Contexts, Context Maps, Anti-Corruption Layers
  -> "How do I split the domain so teams can work independently?"

Staff/Principal engineers spend 80% of their DDD time on strategic patterns.
Tactical patterns are implementation details that senior engineers handle.
```

### The Core Strategic Patterns

```
PATTERN                WHAT IT IS                    GO EQUIVALENT
==================================================  ========================
Bounded Context        A boundary within which      A Go package or module
                       a domain model is consistent

Context Map            A diagram showing how        The import graph between
                       contexts relate to each      packages/modules
                       other

Shared Kernel          Two contexts share a         A shared package imported
                       common model subset          by both contexts

Partnership            Two contexts collaborate     Tight integration, shared
                       closely, co-evolving         ownership in CODEOWNERS

Customer-Supplier      One context (Supplier)       Supplier defines interface,
                       provides to another          Customer consumes it
                       (Customer)

Conformist             Customer conforms to         Customer imports Supplier's
                       Supplier's model without     models and adapts
                       translation

Anti-Corruption        Customer translates          ACL package sits between
Layer (ACL)            Supplier's model to its      Customer and Supplier;
                       own domain model             translates types

Open Host Service      Supplier provides a well-    REST/gRPC API with
                       defined API for multiple     versioned contracts
                       consumers

Published Language     Shared schema between        Protobuf, OpenAPI spec,
                       contexts                     shared type definitions

Separate Ways          Two contexts are completely  No import relationship
                       independent                  at all
```

### Mental Model: The Translation Chain

```
OUTSIDE WORLD (unreliable, messy, uncontrolled)
      |
      v
┌──────────────────────────────────────┐
│     ANTI-CORRUPTION LAYER (ACL)      │  Translates external model
│  Responsibility: Protect our domain  │  to internal model
│  from external model leakage         │
└──────────────────────────────────────┘
      |
      v
┌──────────────────────────────────────┐
│       DOMAIN MODEL (pure)            │  Our ubiquitous language
│  No external concerns                │  Pure Go types
│  Business logic only                 │
└──────────────────────────────────────┘
      |
      v
┌──────────────────────────────────────┐
│       INFRASTRUCTURE                 │  Database, message broker,
│  Translates domain to persistence    │  external APIs
└──────────────────────────────────────┘

Each boundary is a translation point.
Each translation is a function: ExternalType -> DomainType -> DBType
Translation is not overhead — it's the point of architecture.
```

## Internal Architecture

### Bounded Contexts as Go Packages

A bounded context in Go is a package (or set of packages) with a well-defined public API and compiler-enforced internal privacy.

```
BOUNDED CONTEXT: "Identity & Access Management"
===========================================================
Package: internal/identity/

Public API (exported):
  type UserService interface {
    Register(ctx, input) (*User, error)
    Authenticate(ctx, credentials) (*Session, error)
    GetByID(ctx, id string) (*User, error)
  }
  type User struct { ... }      // The domain model for THIS context
  var ErrInvalidCredentials     // Domain error

Private (unexported):
  type userService struct { ... }    // Implementation
  type identityRepo interface { ... }  // Persistence abstraction
  type passwordPolicy struct { ... }   // Internal business rule

BOUNDED CONTEXT: "Payments"
===========================================================
Package: internal/payments/

Public API (exported):
  type PaymentService interface {
    ProcessPayment(ctx, input) (*Payment, error)
    Refund(ctx, paymentID string) (*Refund, error)
  }
  type Payment struct { ... }    // Different model than identity.User
  type Money struct { ... }      // Value object

KEY INSIGHT:
  "User" in the Identity context is an entity with credentials, roles, etc.
  "User" in the Payments context is just a PayerID (string).
  Each context has its OWN model of "user" — and they SHOULD be different.
  If they were the same, you wouldn't have two bounded contexts.
```

### Context Map Patterns in Go Code

**Shared Kernel Pattern:**
```
internal/shared/
  money.go      <- type Money struct { Amount int64; Currency string }
  email.go      <- type Email string (with validation)
  tenant.go     <- type TenantID string

Both internal/identity/ and internal/payments/ import internal/shared/.
The shared kernel contains only VALUE OBJECTS (immutable, no behavior).
Changes to shared kernel require agreement from both teams.
```

**Customer-Supplier Pattern:**
```
Supplier: internal/identity/ (provides user data)
Customer: internal/orders/   (consumes user data)

// internal/identity/service.go
type UserService interface {
    GetByID(ctx context.Context, id string) (*User, error)  // Supplier defines
}

// internal/orders/service.go
import "github.com/company/app/internal/identity"

type orderService struct {
    userSvc identity.UserService  // Customer consumes
}

The Supplier (identity) defines the interface.
The Customer (orders) conforms to it.
Supplier has more power — Customer can request changes but Supplier decides.
```

**Conformist Pattern:**
```
Supplier: internal/identity/ (defines the user model)
Conformist: internal/analytics/ (uses the identity model as-is)

// internal/analytics/user_events.go
import "github.com/company/app/internal/identity"

func (s *analyticsService) TrackUserActivity(ctx context.Context, user identity.User, action string) error {
    // Uses identity.User directly. No translation.
    // Conformist accepts the Supplier's model.
    // Simpler but tightly coupled. If identity.User changes,
    // analytics must change too.
}
```

**Anti-Corruption Layer (ACL) Pattern:**
```
External System: Stripe (payment processor)
Our System: internal/payments/ (our domain)

// internal/payments/acl/stripe.go
package acl  // Separate package from the domain

// StripeTranslator translates between Stripe's model and our domain model.
// It is the ONLY place in the codebase that knows about Stripe's types.
type StripeTranslator struct {
    client *stripe.Client
}

// ProcessPayment translates our domain request to Stripe's API,
// and Stripe's response back to our domain.
func (t *StripeTranslator) ProcessPayment(ctx context.Context, payment *payments.Payment) (*payments.PaymentResult, error) {
    // 1. Translate domain -> Stripe
    params := &stripe.PaymentIntentParams{
        Amount:   stripe.Int64(payment.Amount.Amount),
        Currency: stripe.String(string(payment.Amount.Currency)),
    }

    // 2. Call Stripe
    pi, err := t.client.PaymentIntents.New(params)
    if err != nil {
        return nil, t.translateError(err)  // Translate Stripe errors -> domain errors
    }

    // 3. Translate Stripe response -> domain
    return &payments.PaymentResult{
        ExternalID: pi.ID,
        Status:     t.translateStatus(pi.Status),
    }, nil
}

func (t *StripeTranslator) translateStatus(stripeStatus stripe.PaymentIntentStatus) payments.PaymentStatus {
    switch stripeStatus {
    case stripe.PaymentIntentStatusSucceeded:
        return payments.StatusCompleted
    case stripe.PaymentIntentStatusRequiresAction:
        return payments.StatusPendingAction
    default:
        return payments.StatusFailed
    }
}

func (t *StripeTranslator) translateError(err error) error {
    var stripeErr *stripe.Error
    if errors.As(err, &stripeErr) {
        switch stripeErr.Code {
        case stripe.ErrorCodeCardDeclined:
            return payments.ErrCardDeclined
        case stripe.ErrorCodeExpiredCard:
            return payments.ErrExpiredCard
        }
    }
    return payments.ErrPaymentFailed
}
```

**Open Host Service Pattern:**
```
// internal/payments/api/  <- Open Host Service (public API)
// Provides well-defined REST/gRPC API for multiple consumers.

// internal/payments/api/handler.go
type PaymentAPI struct {
    svc payments.PaymentService
}

func (api *PaymentAPI) Routes() func(r chi.Router) {
    return func(r chi.Router) {
        r.Post("/payments", api.ProcessPayment)
        r.Get("/payments/{id}", api.GetPayment)
        r.Post("/payments/{id}/refund", api.Refund)
    }
}

// Versioned API:
func (api *PaymentAPI) Routes() func(r chi.Router) {
    return func(r chi.Router) {
        r.Route("/v1", func(r chi.Router) {
            r.Post("/payments", api.ProcessPaymentV1)
        })
        r.Route("/v2", func(r chi.Router) {
            r.Post("/payments", api.ProcessPaymentV2)
        })
    }
}

// gRPC Open Host Service:
// internal/payments/api/grpc/payments.proto
service PaymentService {
    rpc ProcessPayment(ProcessPaymentRequest) returns (ProcessPaymentResponse);
    rpc GetPayment(GetPaymentRequest) returns (GetPaymentResponse);
}
```

### Event Storming Output to Go Code Structure

Event storming is a workshop technique to discover bounded contexts. Here's how the output maps to Go:

```
EVENT STORMING OUTPUT:
===========================================================
Domain Events (orange stickies):
  "User Registered"
  "Order Placed"
  "Payment Authorized"
  "Payment Captured"
  "Order Shipped"
  "Invoice Generated"

Commands (blue stickies):
  "Register User"
  "Place Order"
  "Authorize Payment"
  "Capture Payment"
  "Ship Order"

Aggregates (yellow stickies):
  User, Order, Payment, Shipment, Invoice

Bounded Contexts (discovered from clustering):
  Identity Context: UserRegistered, RegisterUser -> User aggregate
  Commerce Context: OrderPlaced, PlaceOrder -> Order aggregate
  Payments Context: PaymentAuthorized, PaymentCaptured -> Payment aggregate
  Fulfillment Context: OrderShipped, ShipOrder -> Shipment aggregate
  Billing Context: InvoiceGenerated -> Invoice aggregate

MAPPED TO GO CODE:
===========================================================
internal/
  identity/
    events.go:
      type UserRegistered struct { UserID string; Email string; Timestamp time.Time }
    handler.go:
      func (h *Handler) Register(w, r) { ... }  // Handles RegisterUser command
    models.go:
      type User struct { ... }  // User aggregate root

  commerce/
    events.go:
      type OrderPlaced struct { ... }
    handler.go:
      func (h *Handler) PlaceOrder(w, r) { ... }
    models.go:
      type Order struct { ... }

  payments/
    events.go:
      type PaymentAuthorized struct { ... }
      type PaymentCaptured struct { ... }
    handler.go: handles AuthorizePayment, CapturePayment commands
    models.go: Payment aggregate

  fulfillment/
    events.go:
      type OrderShipped struct { ... }
    handler.go: handles ShipOrder command
    models.go: Shipment aggregate

  billing/
    events.go:
      type InvoiceGenerated struct { ... }
    models.go: Invoice aggregate
```

### Ubiquitous Language in Go Type Names

The ubiquitous language is NOT just documentation. It IS the code. Go type names should match business terms exactly.

```
BUSINESS TERM        GO TYPE NAME           WHY
===================================         =========================
"Payer"              type Payer struct      NOT "User" — that's the
                      { ID PayerID }        Identity context's term

"Premium Payer"      type PremiumPayer      NOT "UserWithSubscription"
                      struct { ... }        Match the business term

"Payment Intent"     type PaymentIntent     Matches Stripe's term;
                      struct { ... }        used in ACL translation

"Order Dispatched"   OrderDispatched event  The business says "dispatch"
                                            NOT "ship" (even if shipping
                                            is the noun used elsewhere)

"Authorize Payment"  AuthorizePayment(ctx,  The business says "authorize"
                      input) error          NOT "validate" or "approve"

RULE: If you say one word in a meeting and a different word in the code,
you have a bug waiting to happen. The junior engineer hears "authorize" in
standup, searches for "authorize" in the code, finds nothing, and
implements it again from scratch.
```

## Runtime Behavior

### ACL at Runtime

```
EXTERNAL PAYMENT PROVIDER (Stripe) INTERACTION:
===========================================================

1. Handler receives POST /payments request:
   POST /api/v1/payments
   Body: {"amount": 2999, "currency": "USD", "source": "tok_visa"}

2. Handler decodes to our DTO:
   var input payments.ProcessPaymentInput
   json.Decode(&input)  // Our domain language: ProcessPaymentInput

3. Handler calls service:
   result, err := h.svc.ProcessPayment(ctx, input)

4. Service builds domain Payment:
   payment := &payments.Payment{
       ID:     payments.PaymentID(uuid.New().String()),
       Amount: payments.Money{Amount: 2999, Currency: "USD"},
       Source: input.Source,
       Status: payments.StatusPending,
   }

5. Service calls ACL (not Stripe directly!):
   result, err := h.acl.ProcessPayment(ctx, payment)

6. ACL translates domain -> external:
   params := &stripe.PaymentIntentParams{
       Amount:   stripe.Int64(2999),      // Domain cents -> Stripe cents
       Currency: stripe.String("usd"),    // Domain "USD" -> Stripe "usd"
       PaymentMethod: stripe.String("tok_visa"),
   }

7. ACL calls Stripe:
   pi, err := h.stripeClient.PaymentIntents.New(params)

8. ACL translates external -> domain:
   return &payments.PaymentResult{
       ExternalID: pi.ID,                // Stripe ID -> domain ExternalID
       Status:     payments.StatusCompleted,  // Stripe status -> domain status
   }, nil

9. Service updates domain Payment:
   payment.ExternalID = result.ExternalID
   payment.Status = result.Status

10. Service persists (via repository):
    h.repo.Update(ctx, payment)

11. Service publishes event:
    h.events.Publish(ctx, payments.PaymentCompleted{...})

12. Handler encodes response (domain -> DTO):
    json.Encode(w, payments.PaymentResponse{...})

KEY OBSERVATION:
  The domain model NEVER references Stripe types.
  Stripe types NEVER leak into the domain model.
  The ACL is the ONLY coupling point.
  If Stripe's API changes, only the ACL changes.
  If our domain model changes, only the ACL changes.
  The ACL IS the seam.
```

### Context Map at Runtime

```
REQUEST FLOW ACROSS BOUNDED CONTEXTS:
"Customer places an order and pays"
===========================================================

1. POST /orders      [Commerce Context]
   -> CommerceHandler.PlaceOrder()
   -> CommerceService.PlaceOrder()
      -> IdentityService.GetByID(userID)    // [Identity Context, sync]
         -> Returns Identity.User (Payer details)
      -> InventoryService.Reserve(items)     // [Inventory Context, sync]
         -> Returns ReservationConfirmation
      -> OrderRepository.Create(order)
      -> Publish: OrderPlaced{...}           // [Integration Event]

2. OrderPlaced received by Payments Context
   -> PaymentSubscriber.HandleOrderPlaced()
   -> PaymentService.ProcessOrderPayment()
      -> ACL: StripeTranslator.ProcessPayment() // [ACL, external]
      -> PaymentRepository.Update(payment)
      -> Publish: PaymentAuthorized{...}

3. PaymentAuthorized received by Commerce Context
   -> OrderSubscriber.HandlePaymentAuthorized()
   -> OrderService.ConfirmOrder()
      -> OrderRepository.Update(order)         // Status: Confirmed
      -> Publish: OrderConfirmed{...}

4. OrderConfirmed received by Fulfillment Context
   -> FulfillmentSubscriber.HandleOrderConfirmed()
   -> FulfillmentService.CreateShipment()
   -> Publish: OrderShipped{...}

Each bounded context operates INDEPENDENTLY.
They communicate via EVENTS (async) and SERVICE CALLS (sync).
No context directly accesses another context's database.
No context imports another context's repository.
```

## Request Flow Diagrams

### Bounded Context Communication: Synchronous vs. Asynchronous

```
SYNCHRONOUS (Request-Response):
===========================================================

Commerce Context                Identity Context
     │                                │
     │──GetByID(userID)──────────────>│
     │                                │── DB query
     │<─────User{...}─────────────────│
     │                                │

When: Commerce needs user data NOW to continue processing.
How: Commerce imports identity.UserService interface.
Risk: If Identity is slow, Commerce is slow (coupling via latency).
Mitigation: Circuit breaker, timeout, fallback to cached user data.

ASYNCHRONOUS (Event-Driven):
===========================================================

Commerce Context                Identity Context
     │                                │
     │  Publish: OrderPlaced          │
     │──> Message Broker ────────────>│
     │                                │── HandleOrderPlaced
     │                                │   (user order history update)
     │                                │
     │  (Commerce continues,          │
     │   doesn't wait for Identity)   │

When: Commerce doesn't need an immediate response from Identity.
How: Commerce publishes OrderPlaced event. Identity subscribes.
Risk: Eventual consistency. Identity might process event after Commerce responds.
Mitigation: Idempotent event handlers, outbox pattern, DLQ.

WHEN TO USE WHICH:
  Sync: Reads that affect the current operation.
  Async: Side effects, notifications, cross-context state updates.
  Rule: Can the caller continue without the response?
    YES -> Async (events).
    NO  -> Sync (service interface).
```

### ACL Request Flow with Multiple External Providers

```
                ┌───────────────────────────┐
                │     Payments Domain       │
                │  (our ubiquitous language) │
                └───────────┬───────────────┘
                            │
                ┌───────────▼───────────────┐
                │   PaymentGateway interface│
                │   ProcessPayment(ctx, p)  │
                └───────────┬───────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
        ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ Stripe ACL   │   │ Adyen ACL    │   │ PayPal ACL   │
│ (translator) │   │ (translator) │   │ (translator) │
└──────┬───────┘   └──────┬───────┘   └──────┬───────┘
       │                  │                  │
       ▼                  ▼                  ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ Stripe API   │   │ Adyen API    │   │ PayPal API   │
└──────────────┘   └──────────────┘   └──────────────┘

Each ACL implements the SAME PaymentGateway interface.
The domain knows ONLY about PaymentGateway.
Adding a new payment provider = adding a new ACL implementation.
No domain code changes. The interface is the seam.
```

## Lifecycle Diagrams

### Bounded Context Evolution

```
PHASE 1: MONOLITH (one context)
===========================================================
internal/
  app.go         <- Everything is one context
  users.go
  orders.go
  payments.go

"Everything is one domain model."
"All teams share the same ubiquitous language."
Works for small teams (1-5 engineers).

PHASE 2: EMERGING CONTEXTS (contexts discovered)
===========================================================
internal/
  identity/      <- First bounded context emerges
    users.go, auth.go, sessions.go
  orders.go      <- Still in the monolith (not yet separate)
  payments.go    <- Still in the monolith

"Identity is clearly a separate concern."
"Orders and payments are still entangled."
Typically discovered through refactoring or an event storming session.

PHASE 3: MULTIPLE CONTEXTS (all contexts separated)
===========================================================
internal/
  identity/
  commerce/      <- Orders, cart, checkout
  payments/
  fulfillment/
  billing/

"Each context has its own ubiquitous language."
"Contexts communicate via interfaces and events."
"Teams align to contexts."

PHASE 4: DISTRIBUTED CONTEXTS (contexts as services)
===========================================================
services/
  identity-service/
    go.mod, cmd/, internal/
  commerce-service/
    go.mod, cmd/, internal/
  payment-service/
    go.mod, cmd/, internal/

"Each context is a separate deployable."
"go.work ties them together for local dev."
"Contexts communicate via gRPC and events."
```

### ACL Lifecycle

```
PHASE 1: INITIAL INTEGRATION (direct call)
===========================================================
PaymentService calls Stripe API directly.
Stripe types (stripe.PaymentIntent) leak into domain types.
"Quick and dirty" — acceptable for MVP, technical debt for production.

PHASE 2: SIMPLE TRANSLATION (bare minimum ACL)
===========================================================
type stripeAdapter struct { client *stripe.Client }
func (a *stripeAdapter) ProcessPayment(ctx, p) (Result, error) {
    // translate p -> stripe params, call, translate response
}
Still in the same package. Better, but Stripe types still importable.

PHASE 3: FULL ACL (separate package)
===========================================================
internal/payments/acl/
  stripe.go       <- Stripe translation logic
  adyen.go        <- Adyen translation logic
  gateway.go      <- PaymentGateway interface

Stripe types NEVER imported by payments/ core domain.
ACL IS the boundary. Adding a new provider is trivial.
This is production-grade DDD.

PHASE 4: ACL EVOLUTION (provider migration)
===========================================================
Old provider: Braintree ACL
New provider: Stripe ACL

Migrate by feature flag:
  if useStripe {
      acl = NewStripeACL(client)
  } else {
      acl = NewBraintreeACL(client)
  }

Gradually shift traffic to Stripe.
When Braintree is at 0% traffic, remove the Braintree ACL.
ZERO domain code changes during migration. Just ACL swap.
```

## Source Code Reading Guide

### Real-World Go/DDD Examples

**Uber's Go Monorepo:**
Uber uses a monorepo with Go services organized by domain. Key patterns:
- Each domain team owns a top-level directory with its own go.mod
- Shared code in `go.uber.org` libraries
- Bounded contexts aligned with organizational structure
- Heavy use of FX (Uber's DI framework) for explicit dependency injection
- Context propagation via `context.Context` with tracing and baggage

Reading path for Uber's approach:
1. Understand the monorepo layout (go.work at root)
2. Study FX for dependency injection (Uber's answer to explicit wiring at scale)
3. Observe how context boundaries are enforced (separate go.mod per domain)
4. Note the use of protobuf for published language between contexts

**Monzo's Banking Platform:**
Monzo built a Go-based banking platform using DDD principles:
- Over 2000 microservices (mostly Go)
- Each service = one bounded context
- gRPC + protobuf as published language
- Event sourcing for the ledger context (every transaction is an event)
- Strong emphasis on ACL for third-party integrations (card processors, Faster Payments)

Reading path for Monzo's approach:
1. Study their event sourcing implementation (banking ledger is an event stream)
2. Observe how they handle the intersection of DDD + financial compliance
3. Note the use of protobuf for all inter-service communication
4. Understand their "active-active" architecture (runs everything in two DCs)

**HashiCorp's Go Architecture:**
HashiCorp products (Terraform, Vault, Consul) use Go idioms:
- Plugin-based architecture for extensibility (Terraform providers)
- Explicit dependency injection without frameworks
- Internal packages for bounded contexts
- Interfaces at plugin boundaries (the Go plugin interface IS the context boundary)
- Strong use of command pattern for CLI operations

Reading path for HashiCorp's approach:
1. Study Terraform's plugin protocol (gRPC + protobuf as published language)
2. Observe Vault's auth method and secret engine plugin interfaces
3. Note how `internal/` packages enforce context boundaries
4. Understand the command pattern used in all HashiCorp CLIs

**For our codebase, read in this order:**
1. Event storming output or domain model documentation (if exists)
2. `internal/` directory structure — each top-level directory is a suspected bounded context
3. For each context: `models.go` first (ubiquitous language), then `service.go` (public API)
4. Cross-context communication: events (look for `Publisher`/`Subscriber` interfaces)
5. ACL implementations: look for packages with external service names (stripe, sendgrid, etc.)
6. `cmd/server/main.go` — how are contexts wired? Are there circular dependencies?

## Production Failure Scenarios

### Scenario 1: External Model Leakage

**Symptom**: Stripe's `PaymentIntent` type is used directly in the domain service layer. When Stripe upgrades their API and changes the type, 50 files need updating.

**Root Cause**: No anti-corruption layer. The team called the Stripe API directly from the service layer.

**Fix**: Create an ACL package (`internal/payments/acl/`) that translates between Stripe types and domain types. The domain layer never imports Stripe's Go SDK. Only the ACL imports it. When Stripe changes, only the ACL changes.

### Scenario 2: Bounded Context Collision

**Symptom**: The Identity context's `User` type has 25 fields. The Payments context only needs 3 of them. But Payments imports Identity's `User` type directly. When Identity adds an `AvatarURL` field, Payments breaks because the database mapping assumes 3 columns.

**Root Cause**: Payments uses Identity's domain model instead of its own. This is a Conformist relationship where it shouldn't be.

**Fix**: Payments defines its own `Payer` type with only the fields it needs:
```go
// internal/payments/models.go
type Payer struct {
    ID    PayerID
    Email string
    Name  string
}
```
The ACL translates between `identity.User` and `payments.Payer`. Changes to Identity's `User` type don't affect Payments at all.

### Scenario 3: Missing Bounded Context

**Symptom**: The `Order` type in `internal/orders/` has fields for payment status, shipping address, invoice details, and user profile. It's 50 fields. Ten engineers touch it daily. Merge conflicts are constant.

**Root Cause**: No bounded context decomposition. Orders, Payments, Fulfillment, and Billing are all one context. The `Order` type is the aggregate for everything.

**Fix**: Split into four bounded contexts:
- `commerce/` — Order (core commerce fields only)
- `payments/` — Payment (payment-specific fields)
- `fulfillment/` — Shipment (shipping fields)
- `billing/` — Invoice (invoice fields)

Each context has its own aggregate. They communicate via events. The `Order` type shrinks from 50 fields to 10.

### Scenario 4: ACL Bypass

**Symptom**: A new feature adds PayPal integration. The engineer copies the Stripe integration code (which calls Stripe directly from the service) and creates a new service method that calls PayPal directly. Now there are TWO external providers called directly from the domain layer.

**Root Cause**: No `PaymentGateway` interface. Each provider integration is ad-hoc.

**Fix**: Define a `PaymentGateway` interface. Each provider implements it via an ACL. The service layer depends on the interface, not on any specific provider. Adding a new provider = adding a new ACL implementation. No service code changes.

## Debugging Techniques

### Diagnosing Context Boundary Violations

```bash
# Check if any context imports another context's internal types
# (Go compiler prevents importing unexported types, but check for
# patterns suggesting tight coupling)

# Find all cross-context imports
rg "internal/(identity|commerce|payments|fulfillment)" internal/ \
    --include="*.go" | grep -v "_test.go"

# For each line, ask: Is this importing MODELS or SERVICE INTERFACES?
# If importing anything else (repository, internal helpers), it's a violation.

# Find imported types that should be in ACLs
rg "stripe\.|paypal\.|sendgrid\." internal/ --include="*.go" \
    | grep -v "acl/" | grep -v "_test.go"
# Any result here is an ACL bypass — external types in domain code.
```

### Diagnosing Ubiquitous Language Inconsistency

```bash
# Find different names for the same concept
# Example: "user" vs "customer" vs "payer" vs "account"
rg "type (User|Customer|Payer|Account)\b" --include="*.go" -l

# Are these all the same thing in different contexts, or different things?
# If same thing, pick ONE name per bounded context and be consistent.
# If different things, the naming is correct — document it in the context map.

# Find business operations named differently than the code
# Look for comments that say "this does X" but the function name says Y
rg "// (authorize|validate|approve|process|dispatch|ship|send)" --include="*.go"
```

### Visualizing the Context Map

```bash
# Generate a context dependency graph
echo "digraph contexts {" > /tmp/contexts.dot
for ctx in identity commerce payments fulfillment billing; do
    for dep in $(rg "internal/$ctx" "internal/" --include="*.go" -l | \
        grep -v "internal/$ctx" | \
        sed 's|internal/||;s|/.*||' | sort -u); do
        echo "  $dep -> $ctx" >> /tmp/contexts.dot
    done
done
echo "}" >> /tmp/contexts.dot
dot -Tpng /tmp/contexts.dot -o /tmp/contexts.png
# Open /tmp/contexts.png to see the visual context map.

# EXPECTED PATTERN: The graph should be mostly a DAG with
# clear dependencies. If it's a near-complete graph (every
# context imports every other context), your bounded contexts
# are wrong.
```

## Observability Considerations

### Context-Level Tracing

```
SPAN ATTRIBUTES BY LAYER:
===========================================================
Every span should carry:
  bounded_context: "identity" | "commerce" | "payments"
  domain_operation: "RegisterUser" | "PlaceOrder" | "ProcessPayment"

This enables queries like:
  "Show me all traces that pass through the Payments context"
  "Alert when any operation in the Payments context exceeds 500ms"
  "What contexts does a typical PlaceOrder request touch?"

In Go with OpenTelemetry:
  ctx, span := tracer.Start(ctx, "payments.ProcessPayment",
      trace.WithAttributes(
          attribute.String("bounded_context", "payments"),
          attribute.String("domain_operation", "ProcessPayment"),
      ),
  )
```

### Event-Driven Context Communication Observability

```
FOR EACH EVENT PUBLISHED, TRACK:
===========================================================
Event type: OrderPlaced, PaymentAuthorized, OrderShipped
Producer context: commerce, payments, fulfillment
Consumer context(s): payments, commerce, fulfillment
Publish latency: time to acknowledge by broker
Process latency: time from publish to consumer processing
Success/failure rate: how often do consumers successfully process?

DASHBOARD: "Cross-Context Event Flow"
  Shows real-time flow of events between contexts.
  Highlights bottlenecks: "PaymentAuthorized events are taking 30s
  to be processed by Commerce context — investigate."

METRICS:
  domain_events_published_total{context="commerce", event="OrderPlaced"}
  domain_events_consumed_total{context="payments", event="OrderPlaced"}
  domain_events_processing_duration_seconds{context="payments", event="OrderPlaced"}
```

### ACL Health Monitoring

```
FOR EACH EXTERNAL INTEGRATION:
===========================================================
Provider: Stripe, Adyen, PayPal, SendGrid, Twilio
ACL package: payments/acl/stripe.go, notifications/acl/sendgrid.go

Metrics:
  - acl_call_total{provider="stripe", operation="process_payment"}
  - acl_call_duration_seconds{provider="stripe", operation="process_payment"}
  - acl_call_errors_total{provider="stripe", operation="process_payment"}
  - acl_translation_errors_total{provider="stripe"}
    (errors in translating between domain and external types)

Alerting:
  - ACL call error rate > 5%: provider might be down
  - ACL translation error rate > 0%: provider changed their API,
    our translation is broken
  - ACL call duration p99 > 1000ms: provider is slow, enable circuit breaker

Circuit Breaker per ACL:
  Each ACL should have a circuit breaker (e.g., gobreaker library).
  If Stripe is returning errors, OPEN the circuit for 30 seconds.
  Fast-fail instead of timing out. Return ErrPaymentUnavailable.
  Domain code doesn't know about circuit breakers — the ACL handles it.
```

## Performance Implications

### ACL Translation Overhead

```
TRANSLATION COST:
===========================================================
Domain type -> Stripe type -> Stripe API call -> Stripe type -> Domain type

Translation overhead: <1ms (just struct mapping, no network)
Network call to provider: 50-500ms (99% of total time)
Total: effectively the network call time.

The translation cost is NEGLIGIBLE compared to the network call.
Don't optimize the translation. Optimize the network:
  - Set appropriate timeouts (not too long, not too short)
  - Use connection pooling (HTTP keep-alive)
  - Enable circuit breakers
  - Consider async patterns for non-blocking operations

The ACL abstraction cost (~0.1ms) is worth the architectural benefit.
```

### Event Processing Latency

```
EVENT PROCESSING IN A MODULAR MONOLITH:
===========================================================
In-process event bus (Go channel or simple dispatcher):
  Publish -> Deliver -> Process: ~1-10ms
  (no network, no serialization overhead)

Message broker (Kafka, NATS, RabbitMQ):
  Publish -> Broker -> Deliver -> Process: 10-200ms
  (network + serialization + broker overhead)

TRADE-OFF:
  In-process: Faster, simpler, but all contexts must be in the same binary.
  Message broker: Slower, more infrastructure, but contexts can be
  separate services eventually.

RECOMMENDATION for modular monolith:
  Use an abstraction: EventPublisher/EventSubscriber interfaces.
  Development/production: pluggable backend (in-process or Kafka).
  Migration path: start with in-process, switch to Kafka when extracting services.
```

## Architecture Implications

### Bounded Contexts and Database Strategy

```
OPTION 1: SHARED DATABASE (monolith)
===========================================================
One PostgreSQL database, all contexts share it.
Tables: identity.users, commerce.orders, payments.payments, etc.
Each context owns its own schema/tables.

Advantages:
  - Simple, single connection pool, easy transactions
  - Cross-context queries work (JOINs across contexts)
  - Easy to maintain referential integrity

Disadvantages:
  - Schemas are coupled (one migration can break another context)
  - Single point of failure
  - Hard to scale independently

Go implementation:
  One *sql.DB -> passed to all contexts.
  Each context creates its own repository with same *sql.DB.

OPTION 2: DATABASE PER CONTEXT (modular monolith / microservices)
===========================================================
Each context has its own PostgreSQL database.
No cross-context JOINs. No shared schemas.

Advantages:
  - Independent scaling
  - Independent schema evolution
  - Clear ownership

Disadvantages:
  - No cross-context transactions (need sagas)
  - Data duplication (user name in both identity and commerce)
  - More infrastructure to manage

Go implementation:
  Each module (go.mod) connects to its own database.
  Context communicates data via service interfaces or events.
```

### Organizational Implications (Team per Bounded Context)

```
ORGANIZATIONAL MAPPING:
===========================================================
Bounded Context     Team                Size    Skills
─────────────────────────────────────────────────────────
Identity            Identity & Auth     5       Auth, security, GDPR
Commerce            Commerce Platform   7       E-commerce, checkout UX
Payments            Payment Processing  5       PCI-DSS, fintech
Fulfillment         Logistics           4       Warehouse, shipping
Billing             Finance Systems     4       Invoicing, accounting
Platform            Platform & Infra    4       CI/CD, observability

CODEOWNERS:
  internal/identity/    @team-identity
  internal/commerce/    @team-commerce
  internal/payments/    @team-payments
  internal/fulfillment/ @team-logistics
  internal/billing/     @team-finance
  internal/common/      @team-platform

CROSS-TEAM COORDINATION PATTERNS:

Partnership (Identity - Commerce):
  - These teams work closely together.
  - Commerce needs user data; Identity needs to know Commerce's needs.
  - Weekly sync, shared Slack channel, joint PR reviews for contract changes.

Customer-Supplier (Commerce -> Payments):
  - Commerce defines what it needs from Payments.
  - Payments provides the PaymentService interface.
  - Interface changes require Commerce approval.
  - Payments doesn't need to understand Commerce's internals.

Conformist (Analytics -> All):
  - Analytics consumes events from ALL other contexts.
  - Analytics adapts to each context's event schema.
  - Analytics has no power to request changes to source contexts.
  - This is intentional: analytics shouldn't slow down product teams.

Separate Ways (Billing - Fulfillment):
  - These contexts do not interact directly.
  - Billing handles invoicing. Fulfillment handles shipping.
  - No dependencies, no coordination needed.
  - Each team operates independently.
```

## Interview Questions

### Q1: "How would you map a bounded context to a Go package structure?"

**Answer**: A bounded context maps to a top-level package under `internal/` (e.g., `internal/identity/`) with a clearly defined public API (exported interfaces and types) and private implementation (unexported structs). The package boundary IS the bounded context boundary in Go. Key decision points: (1) Use `internal/` to prevent other modules from importing your context, (2) Export only service interfaces and domain types — everything else is unexported, (3) If the context grows large, use sub-packages (`internal/identity/auth/`, `internal/identity/profiles/`) but keep the context as one coherent unit. The module boundary (`go.mod`) becomes the bounded context boundary when you need to enforce that other modules cannot import this context's internals at all.

### Q2: "How do you implement an Anti-Corruption Layer in Go?"

**Answer**: An ACL in Go is a separate package that translates between external types and domain types. It sits at the boundary between your domain and an external system. Key implementation details: (1) The ACL package is the ONLY place that imports the external system's SDK, (2) The ACL implements a domain-defined interface (e.g., `PaymentGateway`) so the domain never knows which provider is being used, (3) The ACL translates in both directions: domain types to external API parameters, external API responses to domain types, (4) The ACL translates external errors to domain errors (e.g., Stripe `CardDeclined` to domain `ErrCardDeclined`), (5) The ACL handles cross-cutting concerns like circuit breaking, retries, and logging — the domain code stays clean. This is Go's interface system at its best: the domain defines the interface, the ACL implements it, and the two are entirely decoupled.

### Q3: "When would you use a Shared Kernel vs. a Customer-Supplier relationship between two bounded contexts?"

**Answer**: Shared Kernel: when two contexts genuinely share a model that changes at the same cadence for both. Example: Money value object. Both Payments and Billing need it, and it changes infrequently (or when it changes, both contexts must update simultaneously). The cost is tight coupling — any change to the shared kernel requires coordination. Customer-Supplier: when one context (Supplier) owns the model and another (Customer) consumes it. Example: Identity owns User. Commerce needs user data. Identity defines `UserService` interface; Commerce consumes it. The Supplier can evolve independently as long as it maintains the interface contract. The Customer can request changes but the Supplier decides. I default to Customer-Supplier and only use Shared Kernel for truly universal value objects that are stable across all contexts.

### Q4: "How does Go's type system support the ubiquitous language?"

**Answer**: Go's type system supports ubiquitous language through three mechanisms. First, named types: `type PayerID string` instead of just `string` — the business term "Payer" is directly in the type name, and the compiler prevents accidentally using a UserID where a PayerID is expected. Second, unexported fields enforce encapsulation: the domain type controls what can be mutated, preventing ad-hoc field assignments that skip business rules. Third, interface-based service contracts use business verbs: `AuthorizePayment(ctx, input)` not `ProcessPayment` or `DoPayment` — the verb matches what the business says. The key insight is that Go forces you to name things explicitly (no `@Column(name="...")` hiding the mapping), so the ubiquitous language is visible in every type definition, method name, and variable declaration.

### Q5: "What's the difference between an ACL and an Open Host Service in Go?"

**Answer**: An ACL is on the CONSUMER side — it protects our domain from an external system's model. It translates external types to our domain types. An Open Host Service is on the PROVIDER side — we provide a well-defined API for multiple consumers. It defines a published language (REST/gRPC schema) that consumers use. In Go: an ACL is `internal/payments/acl/stripe.go` (translating Stripe's model to ours). An Open Host Service is `internal/payments/api/` (providing v1 and v2 REST endpoints, with versioned gRPC services). Both involve translation, but ACL translates EXTERNAL to INTERNAL (we're consuming), while OHS translates INTERNAL to EXTERNAL (we're providing). They're complementary: a microservice typically has ACLs for its upstream dependencies and an OHS for its downstream consumers.

### Q6: "How do you handle data that spans bounded contexts in a modular monolith?"

**Answer**: Each bounded context OWNS its data. No other context writes to another context's tables. There are three patterns for reading data across contexts: (1) Service interface: Context A calls Context B's service to get data synchronously. Best for data needed to complete the current operation. (2) Events with local cache: Context A subscribes to Context B's events and maintains a read-only local projection of the data it needs. Best for reference data that changes infrequently (user name, product price). (3) API composition at the edge: The API gateway or frontend calls multiple contexts and composes the response. Best for read-only views that aggregate data from multiple contexts. The anti-pattern is direct cross-context database queries — these couple schemas and make independent deployment impossible.

### Q7: "How do real Go-based companies like Monzo or HashiCorp apply DDD?"

**Answer**: Monzo uses Go for almost all its 2000+ microservices. Each service is a bounded context with its own event store (event sourcing). Services communicate via gRPC with protobuf as the published language. ACLs are used extensively for integration with legacy banking systems (Faster Payments, BACS, CHAPS) where the external model is decades old and incompatible with Monzo's domain model. HashiCorp uses Go's plugin interface as bounded context boundaries: Terraform providers are ACLs that translate between Terraform's resource model and cloud provider APIs (AWS, GCP, Azure). Vault's auth methods and secret engines are bounded contexts that communicate through well-defined Go interfaces. Both companies use `internal/` packages extensively to enforce context boundaries at compile time. Neither uses a heavy DDD framework — they use Go's native type system and interfaces as their DDD toolkit.

## Hands-On Exercises

### Exercise 1: Discover Bounded Contexts from Code

Given a monolith with the following "god types" (types with 30+ fields), identify bounded contexts:
1. Classify each field into a business capability
2. Group related fields into candidate bounded contexts
3. Identify which fields span contexts (should they be split or duplicated?)
4. Draw the context map (which contexts depend on which?)
5. Identify the ubiquitous language in each context

### Exercise 2: Implement an Anti-Corruption Layer

Given a third-party API client (could be Stripe, SendGrid, or a mock), implement:
1. A domain interface (`EmailSender` or `PaymentGateway`)
2. An ACL that implements the interface and translates types
3. A service that depends on the interface, not the ACL
4. Tests: mock the interface for service tests, integration test the ACL
5. A circuit breaker in the ACL (using gobreaker or similar)

### Exercise 3: Context Map Implementation

Given 3 bounded contexts (Identity, Commerce, Payments):
1. Implement Partnership between Identity and Commerce
2. Implement Customer-Supplier between Commerce and Payments
3. Implement a Shared Kernel (Money value object used by all three)
4. Implement events for cross-context communication
5. Enforce boundaries with golangci-lint depguard rules

### Exercise 4: Ubiquitous Language Audit

Audit an existing codebase (or a provided one) for ubiquitous language violations:
1. List all domain types and their field names
2. Interview a domain expert (or read the spec) for the actual business terms
3. Identify mismatches (code says "UserStatus", business says "AccountState")
4. Rename types to match the business language
5. Verify that the renamed code still compiles and passes tests

### Exercise 5: Event Storming to Go Code

Run a mini event storming session for a simple domain (e.g., "Library Book Lending"):
1. Identify domain events, commands, and aggregates
2. Cluster into bounded contexts
3. Map each bounded context to a Go package structure
4. Write the skeleton code: models, service interfaces, event types
5. Implement one end-to-end flow across contexts

## Advanced Challenges

### Challenge 1: Multi-Provider ACL with Dynamic Provider Selection

Design and implement an ACL system that:
1. Supports multiple payment providers (Stripe, Adyen, PayPal)
2. Routes payments to providers based on rules (currency, amount, region)
3. Handles provider fallback (if Stripe fails, try Adyen)
4. Maintains provider-specific state (API keys, rate limits)
5. Supports A/B testing between providers
6. Exposes provider health metrics and circuit breaker states

### Challenge 2: Bounded Context Migration Architecture

Design a migration strategy for splitting a monolith bounded context into two. Example: The "Commerce" context currently handles both orders and products. Split into "Orders" and "Catalog" contexts. Design:
1. Schema migration plan (who owns which tables?)
2. Code extraction plan (which files move where?)
3. API versioning (when do you change from in-process to gRPC?)
4. Data synchronization (how does Catalog data get to Orders?)
5. Rollback plan for each phase
6. Success metrics (when is the migration "done"?)

### Challenge 3: Context Map as Code

Design a system where the context map is maintained as code (not a diagram) and automatically validated. The system should:
1. Define contexts, their types, and their relationships in YAML or Go
2. Generate CODEOWNERS from the context map
3. Generate golangci-lint depguard rules from the context map
4. Generate a visual context map (Graphviz/mermaid) from the code
5. Validate in CI that actual imports match the declared context relationships
6. Alert when a new cross-context import is added without a corresponding map update

## Key Insights

1. **Bounded contexts are not microservices.** A bounded context is a domain modeling boundary. A microservice is a deployment boundary. They often align but don't have to. A single deployable can contain multiple bounded contexts.

2. **The ACL is Go's superpower.** Go's interfaces make anti-corruption layers natural, testable, and compiler-enforced. No framework needed. No dynamic proxy. Just interfaces and structs.

3. **Ubiquitous language IS the code.** Every type name, method name, and variable name should match business terminology exactly. If you need a glossary to translate between business and code, you've already lost.

4. **Context maps ARE the import graph in Go.** The Go compiler prevents circular context dependencies automatically. Your context map is literally the package dependency graph. Use it.

5. **Shared Kernel is the most dangerous pattern.** Every type in a shared kernel couples every context that uses it. Share only value objects. Share only what's truly universal and stable.

6. **Events are the safest inter-context communication.** Synchronous service calls couple contexts in time (caller must wait) and space (caller must know the interface). Events decouple both. Prefer events for cross-context side effects.

7. **Organizational structure mirrors context structure.** If your teams don't align with your bounded contexts, either restructure the code or restructure the teams. Conway's Law is not optional — it's a law.

8. **Go's `internal/` is the bounded context boundary enforcer.** The compiler prevents external code from importing anything under `internal/`. A bounded context as an `internal/` package is compile-time protected from external contamination.

9. **Strategic DDD is about organizational scaling, not code elegance.** The patterns exist to let teams work independently. If you have one team of 5 engineers, you probably don't need bounded contexts yet.

10. **The real work of DDD is not modeling — it's negotiation.** Deciding where to draw context boundaries is a business decision, not a technical one. The code patterns are easy. The conversations with stakeholders about what "user" means in each context are the hard part.
