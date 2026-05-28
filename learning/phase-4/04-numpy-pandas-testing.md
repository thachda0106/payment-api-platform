# Module 04 — NumPy, Pandas, Testing & Profiling

## 4.1 NumPy — Fast Numerical Computing

NumPy arrays are stored in contiguous C arrays (not Python lists of PyObject pointers). Operations are vectorized (applied to whole array at once in C).

```python
import numpy as np

# Python list (slow): each element is a PyObject, each operation is interpreted
sum(x * y for x, y in zip(a, b))  # ~100ms for 1M elements

# NumPy array (fast): C-level loop over contiguous memory
(np.array(a) * np.array(b)).sum()  # ~2ms for 1M elements — 50x faster

# Reshape, slice, broadcast — all O(1) operations (just change view metadata)
arr = np.arange(12).reshape(3, 4)  # [[0..3],[4..7],[8..11]]
arr[:, 1]  # [1, 5, 9]  — second column as 1D view (no copy!)
arr[arr > 5]  # [6, 7, 8, 9, 10, 11]  — boolean indexing

# Universal functions (ufuncs) — element-wise operations in C
np.sqrt(arr)  # Apply sqrt to every element — no Python loop
```

## 4.2 Pandas — Data Analysis

### Core Structures

```python
import pandas as pd

# DataFrame: 2D labeled table
df = pd.DataFrame({
    "transaction_id": range(1000),
    "user_id": [f"U{i%50}" for i in range(1000)],
    "amount": np.random.randint(1000, 10_000_000, 1000),
    "status": np.random.choice(["COMPLETED", "FAILED"], 1000, p=[0.9, 0.1]),
    "created_at": pd.date_range("2026-01-01", periods=1000, freq="5min")
})

# Basic analysis
print(df.head())
print(df.describe())  # count, mean, std, min, 25%, 50%, 75%, max

# Filtering
completed = df[df["status"] == "COMPLETED"]
large = df[(df["amount"] > 1_000_000) & (df["status"] == "COMPLETED")]

# Group by + aggregate
daily = df.groupby(df["created_at"].dt.date).agg(
    total_amount=("amount", "sum"),
    txn_count=("transaction_id", "count"),
    avg_amount=("amount", "mean")
)

# Pivot table
pivot = df.pivot_table(
    values="amount", index="user_id", columns="status",
    aggfunc="sum", fill_value=0
)
```

### Fraud Analysis with Pandas

```python
# Velocity check: users with > 5 transactions in 10 minutes
df["time_bucket"] = df["created_at"].dt.floor("10min")
velocity = df.groupby(["user_id", "time_bucket"]).size()
suspicious = velocity[velocity > 5]

# Amount outlier detection (z-score > 3)
df["z_score"] = (df["amount"] - df["amount"].mean()) / df["amount"].std()
outliers = df[df["z_score"].abs() > 3]

# New device check: users transacting from a new device for the first time
first_seen = df.groupby("user_id")["created_at"].min()
df["is_first_txn"] = df["created_at"] == df["user_id"].map(first_seen)
new_device_txns = df[df["is_first_txn"]]
```

---

## 4.3 Testing with pytest

```python
# conftest.py — shared fixtures
import pytest
from httpx import AsyncClient
from main import app

@pytest.fixture
async def client():
    async with AsyncClient(app=app, base_url="http://test") as ac:
        yield ac

# test_fraud.py
import pytest

@pytest.mark.asyncio
async def test_fraud_check_allow(client):
    response = await client.post("/fraud/check", json={
        "user_id": "U1", "amount": 100000, "merchant_id": "M1"
    })
    assert response.status_code == 200
    data = response.json()
    assert 0 <= data["score"] <= 100
    assert data["decision"] in ("ALLOW", "REVIEW", "BLOCK")

@pytest.mark.asyncio
async def test_fraud_check_missing_field(client):
    response = await client.post("/fraud/check", json={"user_id": "U1"})
    assert response.status_code == 422  # Validation error

@pytest.mark.parametrize("amount,expected", [
    (1000, "ALLOW"), (10_000_000, "REVIEW"), (100_000_000, "BLOCK")
])
async def test_fraud_by_amount(client, amount, expected):
    response = await client.post("/fraud/check", json={
        "user_id": "U1", "amount": amount
    })
    assert response.json()["decision"] == expected
```

---

## 4.4 Profiling

```bash
# cProfile — built-in, function-level
python -m cProfile -o output.prof fraud_check.py

# line_profiler — line-by-line (install: pip install line_profiler)
@profile  # Decorator — shows time per line
def fraud_check(payment):
    # ... expensive logic

# memory_profiler — memory usage per line
@profile
def load_model():
    model = joblib.load("fraud_model.pkl")  # Shows memory increase here

# py-spy — sampling profiler (no code changes, attach to running process)
py-spy top --pid <PID>        # Live top-like view
py-spy record -o profile.svg --pid <PID>  # Flame graph
```

---

## 4.5 Exercises

### Ex 4.1 — Pandas Fraud Analysis
Given a CSV of 100K transactions (timestamp, user_id, amount, merchant_id, device_id, status): (a) find users with > 10 txns in 5 minutes (velocity check), (b) find amount outliers (z-score > 3), (c) find users with > 3 different devices, (d) compute fraud score as weighted sum of all checks.

### Ex 4.2 — pytest Parametrize
Write parametrized tests for a fraud check endpoint: test 10 different amount/currency/user combinations and verify correct decision.

### Ex 4.3 — Profile and Optimize
Profile a slow Python function with cProfile. Identify the hot path. Rewrite the hot path using NumPy vectorization. Re-profile. Document the speedup.

---

## 4.6 Self-Assessment

- [ ] Can use NumPy broadcasting and ufuncs to avoid Python loops
- [ ] Can group, filter, aggregate, and pivot with Pandas
- [ ] Can write pytest fixtures and parametrized tests
- [ ] Can profile with cProfile and line_profiler to find bottlenecks
- [ ] Understand when to vectorize (NumPy) vs loop (Python)
