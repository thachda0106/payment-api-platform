# Module 01 — Python Fundamentals & Type System

## 1.1 Why Python for Fraud Detection

Python dominates ML/Data Science. The fraud service needs: fast model iteration (no recompilation), rich ML libraries (scikit-learn, XGBoost), data analysis (pandas for velocity checks), and async I/O (FastAPI + asyncio for concurrent fraud checks).

## 1.2 Core Syntax

### Comprehensions — Python's Superpower

```python
# List comprehension
squares = [x**2 for x in range(10)]  # [0, 1, 4, 9, 16, 25, 36, 49, 64, 81]

# With filter
even = [x for x in range(20) if x % 2 == 0]

# Dict comprehension
user_balances = {f"U{i}": i * 10000 for i in range(1, 6)}
# {'U1': 10000, 'U2': 20000, 'U3': 30000, 'U4': 40000, 'U5': 50000}

# Set comprehension
unique_currencies = {txn["currency"] for txn in transactions}

# Generator expression (lazy — doesn't create list in memory!)
total = sum(txn["amount"] for txn in transactions if txn["status"] == "COMPLETED")
```

### Decorators

Functions that wrap other functions, modifying their behavior.

```python
import time
from functools import wraps

def timing(func):
    """Decorator: measure execution time"""
    @wraps(func)
    def wrapper(*args, **kwargs):
        start = time.perf_counter()
        result = func(*args, **kwargs)
        elapsed = time.perf_counter() - start
        print(f"{func.__name__} took {elapsed*1000:.2f}ms")
        return result
    return wrapper

@timing
def fraud_check(payment):
    # ... expensive ML model scoring
    return 0.15  # fraud score

# Parametrized decorator:
def retry(max_attempts=3, delay=0.1):
    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            for attempt in range(max_attempts):
                try: return func(*args, **kwargs)
                except Exception as e:
                    if attempt == max_attempts - 1: raise
                    time.sleep(delay * (2 ** attempt))  # Exponential backoff
        return wrapper
    return decorator

@retry(max_attempts=5, delay=0.5)
def call_bank_api(payload): ...
```

### Context Managers

`with` statement for resource management.

```python
# Built-in context managers
with open("data.csv") as f:  # Auto-closes file
    content = f.read()

# Custom context manager (class-based)
class Transaction:
    def __init__(self, db): self.db = db
    def __enter__(self): self.db.begin(); return self
    def __exit__(self, exc_type, exc_val, exc_tb):
        if exc_type is None: self.db.commit()
        else: self.db.rollback()
        return False  # Don't suppress exceptions

with Transaction(db) as txn:
    db.execute("INSERT INTO payments ...")
    db.execute("INSERT INTO journal_lines ...")
    # Auto-commits on success, auto-rollbacks on exception

# Custom context manager (generator-based — simpler!)
from contextlib import contextmanager

@contextmanager
def transaction(db):
    db.begin()
    try: yield
    except: db.rollback(); raise
    else: db.commit()

with transaction(db):
    db.execute("...")
```

---

## 1.3 Object-Oriented Python

### Dunder Methods (Double Underscore)

```python
class Money:
    def __init__(self, amount: int, currency: str):
        self.amount = amount
        self.currency = currency

    def __repr__(self): return f"Money({self.amount}, '{self.currency}')"
    def __str__(self): return f"{self.amount/1:,.0f} {self.currency}"
    def __eq__(self, other): return self.amount == other.amount and self.currency == other.currency
    def __hash__(self): return hash((self.amount, self.currency))
    def __add__(self, other):
        if self.currency != other.currency: raise ValueError("Currency mismatch")
        return Money(self.amount + other.amount, self.currency)
    def __sub__(self, other):
        if self.currency != other.currency: raise ValueError("Currency mismatch")
        return Money(self.amount - other.amount, self.currency)
    def __bool__(self): return self.amount > 0  # Truthiness

m1 = Money(100000, "VND")
m2 = Money(50000, "VND")
print(m1 + m2)  # 150,000 VND
print(m1)       # 100,000 VND
if m1: print("Has money")  # __bool__ called
```

### Properties and Descriptors

```python
class Payment:
    def __init__(self, amount: int):
        self._amount = amount

    @property
    def amount(self): return self._amount

    @amount.setter
    def amount(self, value):
        if value <= 0: raise ValueError("Amount must be positive")
        self._amount = value

    @property
    def amount_display(self) -> str:
        """Computed property — always up to date"""
        return f"{self._amount/1:,.0f} VND"

p = Payment(100000)
print(p.amount_display)  # 100,000 VND
p.amount = 200000         # Uses setter — validates
# p.amount = -100         # Raises ValueError
```

### MRO (Method Resolution Order)

Python uses C3 linearization for multiple inheritance resolution.

```python
class A: def method(self): return "A"
class B(A): def method(self): return "B"
class C(A): def method(self): return "C"
class D(B, C): pass  # Multiple inheritance
print(D().method())   # "B" — B comes before C in MRO
print(D.__mro__)      # D → B → C → A → object
```

---

## 1.4 Iterators and Generators

### Iterator Protocol

```python
class PaymentBatch:
    def __init__(self, payments): self.payments = payments; self.pos = 0
    def __iter__(self): return self
    def __next__(self):
        if self.pos >= len(self.payments): raise StopIteration
        result = self.payments[self.pos]; self.pos += 1
        return result

# Generator function (much simpler!)
def payment_batch(payments, batch_size):
    for i in range(0, len(payments), batch_size):
        yield payments[i:i+batch_size]  # Yields one batch at a time

for batch in payment_batch(large_list, 1000):
    process_batch(batch)  # Only 1000 items in memory at a time
```

### Generator Expressions

```python
# Lazy pipeline — no intermediate lists created
fraudulent = (
    txn for txn in transactions
    if txn.amount > 50_000_000  # Large transaction
    and txn.user.country != txn.merchant.country  # Cross-border
)
print(next(fraudulent))  # Produces values one at a time
```

---

## 1.5 Type Hints

```python
from typing import Protocol, TypedDict, Literal, Optional

# Function type hints
def calculate_fee(amount: int, tier: Literal["BASIC", "PREMIUM", "ENTERPRISE"]) -> float: ...

# TypedDict — typed dictionary for JSON-like data
class PaymentEvent(TypedDict):
    payment_id: str
    user_id: str
    amount: int
    currency: str
    status: Literal["COMPLETED", "FAILED", "PENDING"]

# Protocol — structural subtyping (like Go interfaces)
class PaymentProcessor(Protocol):
    def process(self, payment: PaymentEvent) -> bool: ...

class StripeProcessor:
    def process(self, payment: PaymentEvent) -> bool: return True

def handle_payment(processor: PaymentProcessor, payment: PaymentEvent) -> bool:
    return processor.process(payment)

handle_payment(StripeProcessor(), {"payment_id": "...", ...})  # Works! Structural match.

# Type narrowing with isinstance
def process(value: int | str) -> str:
    if isinstance(value, int): return f"Amount: {value:,}"
    else: return value.upper()  # mypy knows value is str here
```

---

## 1.6 Exercises

### Ex 1.1 — Money Class
Implement a `Money` class with: (a) validation (amount >= 0), (b) arithmetic (+, -, *, /), (c) comparison (==, <, >), (d) currency conversion with an exchange rate dict.

### Ex 1.2 — Retry Decorator
Write a `@retry` decorator that retries a function with exponential backoff, jitter, and max attempts. Apply it to a flaky API call function. Test with a function that fails N times before succeeding.

### Ex 1.3 — Generator Pipeline
Create a generator pipeline that: reads transactions from a CSV (yield one at a time) → filters COMPLETED → converts amount to int → yields. Memory must stay constant regardless of file size.

---

## 1.7 Self-Assessment

- [ ] Can write comprehensions (list, dict, set, generator) fluently
- [ ] Can create decorators with and without parameters
- [ ] Can implement `__init__`, `__repr__`, `__eq__`, `__hash__`, `__add__` correctly
- [ ] Understand when to use `yield` (generator) vs `return` (list)
- [ ] Can write type hints for functions, classes, and use Protocol/TypedDict
- [ ] Understand Python's MRO for multiple inheritance
