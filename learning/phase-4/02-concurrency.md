# Module 02 — Concurrency: GIL, Threading, Multiprocessing, asyncio

## 2.1 The GIL (Global Interpreter Lock)

### What It Is

The GIL is a `pthread_mutex` that prevents multiple threads from executing Python bytecode simultaneously. Only ONE thread can hold the GIL at a time.

### Why It Exists

CPython's memory management uses reference counting. Every object has a `refcount` field. When you `a = b`, `b.refcount += 1`. Without the GIL, two threads simultaneously incrementing `refcount` on the same object could cause a data race: both read `refcount=5`, both write `refcount=6` (should be `7`). The GIL makes reference counting thread-safe without adding locks to every object.

### When the GIL is Released

- **I/O operations**: `file.read()`, `socket.recv()`, `time.sleep()` — thread releases GIL, another thread acquires it
- **C extensions**: Libraries like NumPy release the GIL during heavy computation (they manage their own locking)
- **Preemptive switch**: Every ~5ms (`sys.getswitchinterval()`), the interpreter checks if another thread wants the GIL and may release it

### The Consequence

CPU-bound multi-threaded Python code does NOT scale with cores. 4 threads on a 4-core machine = still ~1 core of throughput. The GIL ensures only one runs at a time.

```python
import threading, time, multiprocessing

def cpu_bound_work():
    total = 0
    for _ in range(50_000_000):
        total += 1
    return total

# Threading — no speedup (GIL-bound)
def bench_threads():
    threads = [threading.Thread(target=cpu_bound_work) for _ in range(4)]
    start = time.perf_counter()
    for t in threads: t.start()
    for t in threads: t.join()
    return time.perf_counter() - start

# Multiprocessing — near-linear speedup (separate GIL per process)
def bench_processes():
    with multiprocessing.Pool(4) as pool:
        start = time.perf_counter()
        pool.map(cpu_bound_work, [None]*4)
        return time.perf_counter() - start
```

---

## 2.2 Threading — For I/O-Bound Work

```python
import threading
import requests  # synchronous HTTP library

def fetch_payment(payment_id):
    response = requests.get(f"https://api.payment.vn/payments/{payment_id}")
    return response.json()

# Sequential: 10 payments × 100ms each = 1000ms
results = [fetch_payment(f"P{i}") for i in range(10)]

# Threaded: 10 payments concurrently ≈ 100ms
threads = []
for i in range(10):
    t = threading.Thread(target=lambda: results.append(fetch_payment(f"P{i}")))
    threads.append(t); t.start()
for t in threads: t.join()
```

**Thread safety with lock**:
```python
lock = threading.Lock()
shared_balance = 100000

def debit(amount):
    global shared_balance
    with lock:  # Same as lock.acquire() + try/finally lock.release()
        if shared_balance >= amount:
            shared_balance -= amount
            return True
    return False
```

---

## 2.3 Multiprocessing — For CPU-Bound Work

Each process has its OWN Python interpreter + GIL. CPU-bound work scales linearly with cores.

```python
from concurrent.futures import ProcessPoolExecutor
import numpy as np

def score_transaction(features):
    """CPU-bound: ML model scoring"""
    return model.predict_proba([features])[0][1]  # Fraud probability

transactions = [...]  # 1,000,000 transactions

with ProcessPoolExecutor(max_workers=4) as executor:
    scores = list(executor.map(score_transaction, transactions))
# 4 processes → ~4x speedup
```

**Communication between processes**: Data is PICKLED (serialized) when sent between processes. This adds overhead. Use `multiprocessing.Queue` or `multiprocessing.Pipe` for streaming data, or shared memory with `multiprocessing.shared_memory`.

---

## 2.4 asyncio — Cooperative Concurrency

### The Event Loop

```
┌────────────────────────────────────────────────────┐
│                  EVENT LOOP                         │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐              │
│  │Task 1│ │Task 2│ │Task 3│ │Task 4│              │
│  │(wait │ │(run) │ │(wait │ │(run) │              │
│  │ I/O) │ │      │ │ I/O) │ │      │              │
│  └──────┘ └──────┘ └──────┘ └──────┘              │
│     Task 1 waits for I/O → loop runs Task 2        │
│     Task 2 awaits → loop runs Task 3               │
│     Task 3 I/O completes → loop resumes Task 1     │
└────────────────────────────────────────────────────┘
```

### Coroutines and Tasks

```python
import asyncio

async def fetch_payment(payment_id: str):
    """Coroutine — must be awaited"""
    await asyncio.sleep(0.1)  # Simulate I/O (releases control to event loop)
    return {"id": payment_id, "status": "COMPLETED"}

async def main():
    # Sequential: 10 × 0.1s = 1.0s
    # Concurrent: all start together → ~0.1s

    # gather — run concurrently, return all results
    results = await asyncio.gather(
        fetch_payment("P1"), fetch_payment("P2"), fetch_payment("P3"),
    )

    # Create tasks dynamically
    tasks = [asyncio.create_task(fetch_payment(f"P{i}")) for i in range(10)]
    results = await asyncio.gather(*tasks)

    # Wait for first result (others continue)
    done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)

asyncio.run(main())
```

### Queues — Producer-Consumer

```python
queue = asyncio.Queue(maxsize=100)

async def producer():
    for payment_id in range(1000):
        await queue.put(payment_id)
        await asyncio.sleep(0.001)  # 1000 msg/s rate

async def consumer(name):
    while True:
        payment_id = await queue.get()
        await process(payment_id)
        queue.task_done()

async def main():
    producers = [asyncio.create_task(producer())]
    consumers = [asyncio.create_task(consumer(f"worker-{i}")) for i in range(5)]
    await asyncio.gather(*producers)
    await queue.join()  # Wait until all items processed
    for c in consumers: c.cancel()
```

### Synchronization Primitives

```python
# Semaphore — limit concurrent operations
sem = asyncio.Semaphore(10)  # Max 10 concurrent fraud checks

async def fraud_check_with_limit(payment):
    async with sem:  # Acquire semaphore, release on exit
        return await fraud_service.check(payment)

# Lock — mutual exclusion
lock = asyncio.Lock()

async def update_balance(account, amount):
    async with lock:
        balance = await db.get(account)
        await db.set(account, balance + amount)

# Event — signal between tasks
fraud_model_ready = asyncio.Event()

async def wait_for_model():
    await fraud_model_ready.wait()  # Block until set
    print("Model ready, starting checks")

async def train_model():
    await train()  # Takes minutes
    fraud_model_ready.set()  # Signal all waiters
```

### asyncio.gather vs asyncio.wait vs asyncio.as_completed

```python
# gather: run all, return all results (order preserved)
results = await asyncio.gather(task1(), task2(), task3())

# gather with exception handling
results = await asyncio.gather(*tasks, return_exceptions=True)
for r in results:
    if isinstance(r, Exception): ...  # Handle individual failures

# wait: more control (FIRST_COMPLETED, FIRST_EXCEPTION, ALL_COMPLETED)
done, pending = await asyncio.wait(tasks, timeout=5.0)

# as_completed: iterate as each completes (order NOT preserved)
for coro in asyncio.as_completed(tasks):
    result = await coro  # Get result of whatever finishes next
    process(result)
```

---

## 2.5 Choosing the Right Concurrency Model

| Workload | Use | Why |
|----------|-----|-----|
| I/O-bound (API calls, DB queries, file I/O) | `asyncio` | Lightweight coroutines, no thread overhead |
| I/O-bound + legacy sync libraries | `threading` | `requests` library is sync — use threads |
| CPU-bound (ML scoring, data processing) | `multiprocessing` | Bypasses GIL, scales with cores |
| Mixed I/O + CPU | `asyncio` + `loop.run_in_executor` | I/O in event loop, CPU in thread/process pool |

---

## 2.6 Exercises

### Ex 2.1 — GIL Contention
Write a CPU-bound function (compute primes to N). Run with 1/2/4/8 threads. Measure throughput. Rewrite with `multiprocessing.Pool`. Compare speedup.

### Ex 2.2 — asyncio Rate Limiter
Implement an async rate limiter using `asyncio.Semaphore` and `asyncio.Queue`. Allow max 10 requests/second. Test with 100 concurrent tasks trying to make API calls.

### Ex 2.3 — Async Pipeline
Build a 3-stage pipeline: `producer → queue → consumer → queue → processor`. Each stage runs as an independent asyncio task. Measure throughput vs sequential processing.

---

## 2.7 Self-Assessment

- [ ] Can explain exactly when the GIL is held and when it's released
- [ ] Can choose between threading, multiprocessing, and asyncio for any workload
- [ ] Can use `asyncio.gather`, `asyncio.wait`, `asyncio.Queue`, and `asyncio.Semaphore`
- [ ] Understand the difference between `asyncio.sleep(0)` and `time.sleep(0)`
- [ ] Can write an async context manager (`async with`)
- [ ] Know when `multiprocessing.Queue` is needed vs `asyncio.Queue`
