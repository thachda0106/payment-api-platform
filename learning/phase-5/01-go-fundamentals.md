# Module 01 — Go Fundamentals & Type System

## 1.1 Why Go for High-Throughput Services

Go was designed at Google for server software. Its key features for payment services: fast compile times (seconds, not minutes), goroutines (lightweight concurrency, not OS threads), garbage collection with sub-millisecond pauses, static binaries (no runtime dependency), and excellent standard library.

## 1.2 Core Syntax & Zero Values

```go
package main

import "fmt"

func main() {
    // Zero values — every type has a default
    var i int        // 0
    var s string     // "" (empty, not nil)
    var b bool       // false
    var p *int       // nil
    var sl []int     // nil (len=0, cap=0 — but you can still append!)
    var m map[string]int // nil (reading returns zero value, writing panics!)

    // Short variable declaration (inferred type)
    x := 42
    name := "payment"

    // Multiple return values
    balance, ok := checkBalance("U1")
    if !ok { fmt.Println("account not found") }
}
```

## 1.3 Slices vs Arrays

**Arrays**: Fixed size, part of the type. `[3]int` and `[4]int` are DIFFERENT types. Rarely used directly.

**Slices**: Dynamic view into an underlying array. The MOST used data structure in Go.

```go
// Slice header (24 bytes on 64-bit):
// type slice struct {
//     ptr   *Element  // pointer to underlying array
//     len   int       // number of elements
//     cap   int       // capacity of underlying array
// }

s := make([]int, 0, 10)  // len=0, cap=10
s = append(s, 1, 2, 3)    // len=3, cap=10

// Slicing: O(1) — just creates new header pointing to same array
sub := s[1:3]  // [2, 3] — shares memory with s!
sub[0] = 99    // s is now [1, 99, 3] — modified through sub!

// Copy to break sharing
independent := make([]int, len(s))
copy(independent, s)

// Common mistake: append may allocate new array
s2 := append(s, 4, 5, 6, 7, 8, 9, 10, 11)  // cap=10, appending 8 items → new array!
// s and s2 now point to DIFFERENT arrays
```

## 1.4 Maps

```go
// Map is a pointer to a runtime hash table structure
balances := make(map[string]int64)
balances["U1"] = 100000

// Comma-ok idiom: distinguish zero value from missing key
balance, ok := balances["U2"]
if !ok { fmt.Println("U2 not found") }  // ok=false, balance=0

// Delete
delete(balances, "U1")

// Iteration ORDER is RANDOM (intentional — prevents reliance on order)
for account, bal := range balances {
    fmt.Println(account, bal)
}
```

## 1.5 Structs and Methods

```go
type Payment struct {
    ID        string
    Amount    int64
    Currency  string
    Status    string
}

// Value receiver: operates on a COPY. Cannot modify original.
func (p Payment) DisplayAmount() string {
    return fmt.Sprintf("%d %s", p.Amount, p.Currency)
}

// Pointer receiver: can modify the original. PREFERRED for most methods.
func (p *Payment) Complete() {
    p.Status = "COMPLETED"
}

// Constructor (Go doesn't have constructors — use factory function)
func NewPayment(amount int64, currency string) *Payment {
    return &Payment{
        ID:       generateID(),
        Amount:   amount,
        Currency: currency,
        Status:   "PENDING",
    }
}
```

## 1.6 Interfaces — Implicit Satisfaction

```go
// Define interface
type PaymentProcessor interface {
    Process(payment *Payment) error
}

// Implementation — NO "implements" keyword!
type StripeProcessor struct { APIKey string }

func (s *StripeProcessor) Process(p *Payment) error {
    // Call Stripe API
    return nil
}

// Any type with a Process(*Payment) error method satisfies PaymentProcessor
var processor PaymentProcessor = &StripeProcessor{APIKey: "sk_live_..."}
processor.Process(&Payment{Amount: 10000})

// Empty interface: any type (like Object in Java, any in TypeScript)
var anything interface{}
anything = 42
anything = "hello"
anything = &Payment{}

// Type assertion
if p, ok := anything.(*Payment); ok {
    fmt.Println(p.Amount)
}

// Type switch
switch v := anything.(type) {
case int: fmt.Println("integer:", v)
case string: fmt.Println("string:", v)
case *Payment: fmt.Println("payment:", v.Amount)
}
```

## 1.7 Error Handling

Go uses explicit error returns instead of exceptions.

```go
// Sentinel errors
var ErrInsufficientBalance = errors.New("insufficient balance")

func Debit(accountID string, amount int64) error {
    balance, err := getBalance(accountID)
    if err != nil { return fmt.Errorf("debit %s: %w", accountID, err) }

    if balance < amount { return ErrInsufficientBalance }

    // ... perform debit
    return nil
}

// Error wrapping (Go 1.13+)
func processPayment(p *Payment) error {
    if err := Debit(p.UserID, p.Amount); err != nil {
        return fmt.Errorf("process payment %s: %w", p.ID, err)  // %w wraps
    }
    return nil
}

// Unwrapping and checking
err := processPayment(&Payment{Amount: 100000})
if errors.Is(err, ErrInsufficientBalance) {
    // Handle insufficient balance specifically
}
var balanceErr *BalanceError
if errors.As(err, &balanceErr) {
    // Type assertion into custom error type
}
```

## 1.8 Generics (Go 1.18+)

```go
// Generic function
func Min[T constraints.Ordered](a, b T) T {
    if a < b { return a }
    return b
}

x := Min[int](5, 3)     // 3
y := Min[string]("a", "b")  // "a"
z := Min(5.5, 3.3)       // Type inference!

// Generic repository pattern
type Repository[T any, ID comparable] interface {
    FindByID(id ID) (T, error)
    Save(entity T) error
    Delete(id ID) error
}

// Implementation
type PaymentRepo struct { /* ... */ }
func (r *PaymentRepo) FindByID(id string) (*Payment, error) { /* ... */ }
```

## 1.9 Exercises

### Ex 1.1 — Implement a Generic Stack
Implement a generic stack (`Stack[T any]`) with `Push`, `Pop`, `Peek`, `IsEmpty`, `Size`. Handle underflow (return error from Pop).

### Ex 1.2 — Fee Calculator
Given a map of merchant tiers to fee percentages and a list of payments, calculate the fee for each payment. Use interfaces: define a `FeeCalculator` interface with `Calculate(payment) int64`. Implement `TieredFeeCalculator` and `FlatFeeCalculator`.

### Ex 1.3 — Error Handling Chain
Implement a 3-step payment pipeline: validate → authorize → capture. Each step can return a specific error type. Use `errors.Is` and `errors.As` to handle each error type differently.

## 1.10 Self-Assessment

- [ ] Can explain the difference between slices and arrays (and the slice header)
- [ ] Understand why map iteration order is random
- [ ] Know when to use value receivers vs pointer receivers
- [ ] Understand implicit interface satisfaction
- [ ] Can use `errors.Is`, `errors.As`, and `fmt.Errorf("%w")`
- [ ] Can write generic functions using `[T any]` syntax
