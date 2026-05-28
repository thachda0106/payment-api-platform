# Module 01 — TypeScript Fundamentals & Advanced Types

## 1.1 Why TypeScript for Payment Services

JavaScript is dynamically typed. At Stripe/PayPal scale, dynamic typing means: "property doesn't exist" errors in production, API contract violations, and refactoring fear. TypeScript adds a structural type system that catches errors at compile time without changing runtime behavior (it compiles to JavaScript, types are erased).

## 1.2 Core Type System

```typescript
// Basic annotations
let amount: number = 100000;
let currency: string = "VND";
let completed: boolean = true;

// Arrays and tuples
let payments: Payment[] = [];
let pair: [string, number] = ["VND", 100000];

// Union types
type PaymentStatus = "PENDING" | "COMPLETED" | "FAILED" | "REFUNDED";

// Intersection types — combine multiple types
type Auditable = { createdAt: Date; createdBy: string };
type Payment = { id: string; amount: number } & Auditable;

// Interfaces — preferred for object shapes (extendable)
interface PaymentEvent {
  paymentId: string;
  amount: number;       // Stored in cents/smallest unit
  currency: string;
  status: PaymentStatus;
}
```

## 1.3 Generics

```typescript
// Generic repository
interface Repository<T, ID> {
  findById(id: ID): Promise<T | null>;
  save(entity: T): Promise<T>;
  delete(id: ID): Promise<void>;
}

class PaymentRepository implements Repository<Payment, string> {
  async findById(id: string): Promise<Payment | null> { /* ... */ }
  async save(entity: Payment): Promise<Payment> { /* ... */ }
  async delete(id: string): Promise<void> { /* ... */ }
}

// Generic constraints
function processPayment<T extends { amount: number }>(payment: T): T { return payment; }

// Conditional types
type IsString<T> = T extends string ? true : false;
type A = IsString<"hello">; // true
type B = IsString<42>;      // false
```

## 1.4 Discriminated Unions — The Pattern for Payment States

```typescript
type PaymentState =
  | { status: "PENDING" }
  | { status: "AUTHORIZED"; authorizedAt: Date }
  | { status: "COMPLETED"; completedAt: Date; journalEntryId: string }
  | { status: "FAILED"; reason: string; failedAt: Date }
  | { status: "REFUNDED"; refundedAt: Date; refundId: string };

function describe(state: PaymentState): string {
  switch (state.status) {
    case "PENDING":    return "Awaiting processing";
    case "AUTHORIZED": return `Authorized at ${state.authorizedAt}`;  // TypeScript knows shape!
    case "COMPLETED":  return `Completed: ${state.journalEntryId}`;   // Access journalEntryId
    case "FAILED":      return `Failed: ${state.reason}`;              // Access reason
    case "REFUNDED":    return `Refunded: ${state.refundId}`;
  }
}
```

## 1.5 Mapped Types & Utility Types

```typescript
// Partial — all properties optional
type PaymentUpdate = Partial<Payment>;

// Pick — select specific properties
type PaymentSummary = Pick<Payment, "id" | "amount" | "status">;

// Omit — exclude specific properties
type PaymentWithoutAudit = Omit<Payment, "createdAt" | "createdBy">;

// Record — key-value map
type SettlementReport = Record<string, { total: number; count: number }>;

// Readonly — immutable
type ImmutablePayment = Readonly<Payment>;

// Custom mapped type: make all properties nullable
type Nullable<T> = { [K in keyof T]: T[K] | null };

// Template literal types
type EventTopic = `payments.${PaymentStatus}`;
// "payments.PENDING" | "payments.COMPLETED" | "payments.FAILED" | "payments.REFUNDED"

type APIEndpoint = `/v1/${string}`;
type PaymentEndpoint = `/v1/payments/${string}`;
```

## 1.6 Type Guards & Narrowing

```typescript
// typeof guard
function formatAmount(amount: number | string): string {
  if (typeof amount === "number") return amount.toLocaleString();
  return amount; // TypeScript knows it's string here
}

// instanceof guard
class FraudCheckError extends Error { constructor(msg: string) { super(msg); } }
class LedgerError extends Error { constructor(msg: string) { super(msg); } }

function handleError(err: Error) {
  if (err instanceof FraudCheckError) return { decision: "REVIEW", reason: err.message };
  if (err instanceof LedgerError)    return { decision: "FAILED", reason: err.message };
  throw err; // Unknown error type
}

// Custom type guard
function isPayment(obj: unknown): obj is Payment {
  return typeof obj === "object" && obj !== null && "id" in obj && "amount" in obj;
}

// Assertion function (Node.js assert style)
function assert(condition: unknown, msg?: string): asserts condition {
  if (!condition) throw new Error(msg ?? "assertion failed");
}
```

## 1.7 Exercises

### Ex 1.1 — Payment State Machine
Define the payment state machine using discriminated unions. Implement `transition(state, event)` returning the new state. TypeScript must verify ALL state transitions are valid.

### Ex 1.2 — Generic Event Bus
Implement a typed event bus: `EventBus<T extends Record<string, any>>` where `T` maps event names to payload types. `emit("PaymentCompleted", payload)` must be type-checked.

### Ex 1.3 — Type-Safe API Client
Define API endpoints as a type map. Create a `fetchAPI<K extends keyof Endpoints>(endpoint: K, body: Endpoints[K]["request"]): Promise<Endpoints[K]["response"]>`. Full type inference.

---

## 1.8 Self-Assessment

- [ ] Can use discriminated unions to model payment states with exhaustive switch
- [ ] Can write generic functions with constraints
- [ ] Understand mapped types (Pick, Omit, Partial, Record) and when to use them
- [ ] Can write type guards and assertion functions
- [ ] Understand structural typing vs nominal typing
- [ ] Know `unknown` vs `any` — `unknown` is safe (must narrow before use)
