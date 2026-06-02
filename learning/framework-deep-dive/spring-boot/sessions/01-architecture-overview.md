# Session 01: Architecture Overview & Project Structures

## 1. Why This Topic Exists

Framework APIs rot. Architecture patterns compound. A developer who knows 50 `@Annotation`s but cannot explain why `orders/` should not directly import `payments/` will design systems that fail under team scale. This session establishes the **mental model for architecture as a first-class engineering discipline**, not an afterthought.

The Spring Boot ecosystem teaches you to put `@Service` and `@Repository` on classes. It does NOT teach you where those classes should live, who owns them, or what happens when 15 engineers modify them simultaneously.

**Staff engineer insight**: Architecture decisions made in month 1 determine whether your team ships in month 12 or spends month 12 fighting circular dependencies.

## 2. Mental Model

```
Architecture = f(Team Size, Domain Complexity, Growth Rate, Conway's Law)

NOT Architecture = f("Clean Architecture book said so")
```

Every architecture decision is a **trade-off between today's velocity and tomorrow's maintainability**. The art of Staff engineering is knowing which trade-off to make given the specific context.

### The Architecture Compass

| Dimension | Question |
|-----------|----------|
| Team Size | How many people modify this codebase simultaneously? |
| Domain Complexity | How many independent business concepts exist? |
| Change Rate | How fast do requirements evolve? |
| Scale | What throughput and data volume? |
| Organization | How are teams structured? (Conway's Law) |

## 3. Internal Architecture

### Small Project Structure (1-3 engineers, MVP)

```
src/main/java/com/example/
├── controller/
│   ├── UserController.java
│   ├── OrderController.java
│   └── PaymentController.java
├── service/
│   ├── UserService.java
│   ├── OrderService.java
│   └── PaymentService.java
├── repository/
│   ├── UserRepository.java
│   ├── OrderRepository.java
│   └── PaymentRepository.java
├── model/
│   ├── User.java
│   ├── Order.java
│   └── Payment.java
├── dto/
│   ├── CreateUserRequest.java
│   └── OrderResponse.java
├── config/
│   └── SecurityConfig.java
└── PaymentApplication.java
```

**Why it works**: Zero cognitive overhead. Every engineer knows exactly where code lives. Packages mirror Spring stereotypes directly.

**Scaling limits**: At ~5 engineers, OrderService calls PaymentService which calls UserService. At ~10, `service/` has 40 classes. Merge conflicts in `OrderService.java` become daily occurrences. The package structure provides **zero enforcement of dependency rules**.

**Refactoring trigger**: When you say "I'm not sure which service should own this logic" more than once per sprint.

### Medium Project Structure (5-15 engineers, multiple domains)

```
src/main/java/com/example/
├── common/
│   ├── exception/
│   ├── util/
│   └── config/
├── user/
│   ├── controller/
│   │   └── UserController.java
│   ├── service/
│   │   └── UserService.java
│   ├── repository/
│   │   └── UserRepository.java
│   ├── domain/
│   │   ├── User.java
│   │   └── UserCreatedEvent.java
│   └── dto/
│       ├── CreateUserRequest.java
│       └── UserResponse.java
├── order/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── domain/
│   └── dto/
├── payment/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── domain/
│   └── dto/
├── shared/
│   ├── audit/
│   ├── id/
│   └── events/
└── infrastructure/
    ├── persistence/
    ├── messaging/
    └── security/
```

**Ownership**: Each `feature/` package owned by a team or sub-team. `shared/` and `common/` require cross-team review for changes.

**Dependency management**: Feature packages must not depend on each other directly. Communication via `shared/` interfaces or domain events through `infrastructure/messaging/`.

### Enterprise Project Structure (20+ engineers, multiple teams)

```
com.example/
├── platform/
│   ├── platform-api/                    ← Shared API contracts
│   │   └── src/main/java/com/example/platform/api/
│   │       ├── events/                  ← Integration event schemas
│   │       ├── dto/                     ← Shared DTOs
│   │       └── error/                   ← Error codes
│   ├── platform-infrastructure/         ← Shared infra
│   │   └── src/main/java/...
│   │       ├── persistence/
│   │       ├── messaging/
│   │       ├── observability/
│   │       └── security/
│   └── platform-test/                   ← Test fixtures
│
├── identity/                            ← Bounded Context: Identity
│   ├── identity-api/                    ← Public API contracts
│   ├── identity-core/                   ← Domain logic
│   ├── identity-infrastructure/         ← Persistence adapters
│   └── identity-web/                    ← REST/GraphQL adapters
│
├── ordering/                            ← Bounded Context: Ordering
│   ├── ordering-api/
│   ├── ordering-core/
│   ├── ordering-infrastructure/
│   └── ordering-web/
│
├── billing/                             ← Bounded Context: Billing
│   ├── billing-api/
│   ├── billing-core/
│   ├── billing-infrastructure/
│   └── billing-web/
│
├── catalog/                             ← Bounded Context: Catalog
│   ├── catalog-api/
│   ├── catalog-core/
│   ├── catalog-infrastructure/
│   └── catalog-web/
│
├── fulfillment/                         ← Bounded Context: Fulfillment
│   ├── fulfillment-api/
│   ├── fulfillment-core/
│   ├── fulfillment-infrastructure/
│   └── fulfillment-web/
│
├── notification/                        ← Bounded Context: Notification
│   ├── notification-api/
│   ├── notification-core/
│   ├── notification-infrastructure/
│   └── notification-web/
│
└── build.gradle                         ← Multi-module build
```

Each bounded context is a Gradle module with:
- `*-api`: Public contracts (interfaces, events, DTOs) — can be depended on by other modules
- `*-core`: Domain logic, entities, services — depends only on `*-api`
- `*-infrastructure`: Persistence, external APIs, messaging adapters
- `*-web`: REST controllers, configuration, Spring Boot wiring

**Governance**: No bounded context can depend on another context's `*-core` or `*-infrastructure`. Contexts communicate through `*-api` contracts and domain/integration events. This is enforced by Gradle module dependencies, NOT by convention.

**Team autonomy**: Team A (Identity) can change their persistence from JPA to jOOQ without Team B (Ordering) knowing, because Ordering only depends on `identity-api`.

## 4. Runtime Behavior

Architecture choices have runtime implications:

| Structure | Build Time | Test Time | Deploy Time | Merge Conflicts |
|-----------|-----------|-----------|-------------|-----------------|
| Small (by-layer) | Fast | Fast | 1 deploy | High at 5+ devs |
| Medium (by-feature) | Fast | Fast | 1 deploy | Low |
| Enterprise (multi-module) | Slow (Gradle) | Moderate (incremental) | Independent per module | Very low |

## 5. Request Flow Diagrams

### Small Project: Single Deploy

```
Request → Controller → Service → Repository → DB
                  ↓
            (in-process, synchronous)
```

### Enterprise: Multi-Module (Still Monolith)

```
Request → identity-web/ → identity-core/ → identity-infrastructure/
                                                 ↓ (domain event)
                                        ordering-core/ ← ordering-infrastructure/
                                                 ↓
                                        ordering-web/ (if sync API needed)
```

Even in a monolithic deployment, the module boundaries enforce the same dependency discipline that microservices would enforce through network boundaries — but without the network cost.

## 6. Lifecycle Diagrams

### Architecture Lifecycle

```
Week 1:    src/main/java/com/example/  (everything)
Month 3:   controller/ service/ repository/ model/
Month 6:   feature-packages (user/, order/, payment/)
Year 1:    Gradle modules with api/core/infra/web
Year 2:    Bounded contexts with domain events
Year 3+:   Selective extraction to microservices
```

The key insight: **You don't skip stages. You pass through them.** The question is how long you spend at each stage.

## 7. Source Code Reading Guide

For this session, explore real-world open-source Spring Boot projects:

1. **Spring PetClinic** (simple): `https://github.com/spring-projects/spring-petclinic`
   - Classic layered architecture, NOT feature-based
   - Note: even Spring's reference app is layered, confirming it's appropriate for small projects

2. **eShopOnContainers** (complex): `https://github.com/dotnet-architecture/eShopOnContainers`
   - Though .NET, the architecture patterns are universal
   - Study the bounded context separation

3. **AxonBank** (DDD/CQRS): `https://github.com/AxonIQ/axon-bank`
   - Study how bounded contexts are separated

## 8. Production Failure Scenarios

### Symptom: Cannot deploy a small change without full regression test

**Root cause**: Monolithic deployable with tight coupling. A change to `user/Address.java` required deploying `order/`, `payment/`, `notification/` because everything touched `User`.

**Detection**: Deployment frequency drops. Lead time for changes increases.

**Resolution**: Extract shared types to `*-api` modules. Interfaces, not implementations.

### Symptom: Merge conflicts on `OrderService.java` daily

**Root cause**: Service classes too large, multiple teams modifying same file.

**Detection**: `git log --follow OrderService.java` shows 3+ distinct authors per week.

**Resolution**: Split `OrderService` into `OrderCreationService`, `OrderFulfillmentService`, `OrderCancellationService`. Better: move to vertical slices.

## 9. Debugging Techniques

Use ArchUnit to enforce your intended architecture:

```java
@Test
void domainShouldNotDependOnInfrastructure() {
    classes().that().resideInAPackage("..domain..")
        .should().onlyDependOnClassesThat()
        .resideInAnyPackage("..domain..", "java..", "..shared..")
        .check(classes);
}

@Test
void orderingShouldNotDependOnBilling() {
    noClasses().that().resideInAPackage("..ordering..")
        .should().dependOnClassesThat()
        .resideInAPackage("..billing..")
        .check(classes);
}
```

This is architecture as code — tests fail when someone introduces a forbidden dependency.

## 10. Observability Considerations

In a multi-module setup, trace context must propagate across module boundaries:

```java
// In identity-core
@EventListener
public void onUserRegistered(UserRegisteredEvent event) {
    MDC.put("traceId", event.getTraceId());  // Propagate trace context
    try {
        // process
    } finally {
        MDC.clear();
    }
}
```

Without this, you cannot trace a business transaction across modules.

## 11. Performance Implications

Multi-module Gradle builds are slower than single-module Maven builds. Trade-off: build time vs team autonomy. Mitigation:
- Gradle Build Cache (remote)
- Gradle Enterprise (Build Scan)
- Only build changed modules and dependents

## 12. Architecture Implications

**Conway's Law**: "Organizations design systems that mirror their communication structure."

If you have 3 teams but a single `service/` package, you will have merge conflicts. Structure your code to mirror your team structure. If the code structure and team structure disagree, one of them is wrong.

## 13. Team Ownership Implications

| Structure | Team Boundary | Communication Cost |
|-----------|---------------|-------------------|
| By-layer | None (shared ownership) | High (everyone in every layer) |
| By-feature | Per-package | Medium (shared kernel) |
| By-module | Per Gradle module | Low (explicit APIs) |
| By-service | Per deployable | Lowest (network boundary) |

## 14. Interview Questions

1. "You join a 3-engineer startup with a single Spring Boot app. The codebase has 50 classes in `service/`. What do you do first?"
   - **Answer**: Nothing. At 3 engineers, layered architecture is correct. The problem is when you grow to 8 engineers with the same structure.

2. "How would you structure a Spring Boot project for 5 teams of 8 engineers each?"
   - **Answer**: Multi-module Gradle project. Each team owns a bounded context module. Shared kernel for cross-cutting concerns. Compile-time dependency enforcement between modules. Domain events for cross-context communication.

3. "What are the signs that your project structure is wrong?"
   - **Answer**: Merge conflicts in core classes, circular dependencies, can't deploy one feature without deploying another, "where does this code go?" uncertainty, tests take too long because everything is coupled.

## 15. Hands-On Exercises

1. **Analyze a real codebase**: Count classes per package. Map dependencies between packages using `jdeps` or ArchUnit. Identify dependency violations.

2. **Restructure a sample app**: Take the Spring PetClinic layered structure and reorganize it by feature. Compare the dependency graphs.

3. **Design from scratch**: Given requirements for an e-commerce platform (users, products, orders, payments, shipping), design the module structure for a 20-engineer team. Document which modules can depend on which others.

## 16. Advanced Challenges

1. **Implement ArchUnit tests for a real project**: Write 10 architectural rules that capture your intended dependency structure. Run them in CI.

2. **Build a dependency graph visualizer**: Use `jdeps` or ASM to generate a DOT file showing all inter-package dependencies. Color-code violations.

3. **Design a monolith-to-microservices extraction plan**: Given a monolith with `user/`, `order/`, `payment/` feature packages, write the sequence of refactoring steps to extract `payment/` as a standalone service without downtime.
