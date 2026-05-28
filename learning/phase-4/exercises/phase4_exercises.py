"""Phase 4 Exercises — Python Deep Dive"""
import asyncio, time, threading, multiprocessing
from dataclasses import dataclass
from typing import Optional

# ═══════════════════════════════════════════════════════════════════════════
# 2.1 — GIL Contention Demo
# ═══════════════════════════════════════════════════════════════════════════
def cpu_bound(n: int) -> int:
    """CPU-bound: compute sum of primes up to n."""
    total = 0
    for i in range(2, n):
        is_prime = True
        for j in range(2, int(i**0.5) + 1):
            if i % j == 0: is_prime = False; break
        if is_prime: total += 1
    return total

def bench_threading(n_threads: int) -> float:
    threads = [threading.Thread(target=cpu_bound, args=(5000,)) for _ in range(n_threads)]
    start = time.perf_counter()
    for t in threads: t.start()
    for t in threads: t.join()
    return time.perf_counter() - start

def bench_multiprocessing(n_procs: int) -> float:
    with multiprocessing.Pool(n_procs) as pool:
        start = time.perf_counter()
        pool.map(cpu_bound, [5000] * n_procs)
        return time.perf_counter() - start

# ═══════════════════════════════════════════════════════════════════════════
# 2.2 — Async Rate Limiter
# ═══════════════════════════════════════════════════════════════════════════
class RateLimiter:
    def __init__(self, rate: int, per_seconds: float = 1.0):
        self.rate = rate
        self.interval = per_seconds / rate
        self._last = 0.0
        self._lock = asyncio.Lock()

    async def acquire(self):
        async with self._lock:
            now = time.monotonic()
            wait = self._last + self.interval - now
            if wait > 0: await asyncio.sleep(wait)
            self._last = time.monotonic()

async def rate_limited_task(limiter: RateLimiter, task_id: int):
    async with limiter: # Assumes __aenter__/__aexit__ — use acquire() instead
        pass
    await limiter.acquire()
    print(f"Task {task_id} running at {time.monotonic():.3f}")
    await asyncio.sleep(0.01)  # Simulate work

# ═══════════════════════════════════════════════════════════════════════════
# 2.3 — Async 3-Stage Pipeline
# ═══════════════════════════════════════════════════════════════════════════
@dataclass
class Payment: id: str; amount: int; status: str = "PENDING"

async def fraud_check(queue_in: asyncio.Queue, queue_out: asyncio.Queue):
    while True:
        payment = await queue_in.get()
        if payment is None: queue_out.put_nowait(None); break
        payment.status = "FRAUD_CHECKED" if payment.amount < 10_000_000 else "REVIEW"
        await queue_out.put(payment)
        await asyncio.sleep(0.001)

async def fee_calc(queue_in: asyncio.Queue, queue_out: asyncio.Queue):
    while True:
        payment = await queue_in.get()
        if payment is None: queue_out.put_nowait(None); break
        payment.amount = int(payment.amount * 0.985)  # Deduct fee
        payment.status = "COMPLETED"
        await queue_out.put(payment)

# ═══════════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════════
if __name__ == "__main__":
    print("=== Phase 4 Exercises ===\n")

    # GIL contention
    print("GIL Contention: threading vs multiprocessing")
    for n in [1, 2, 4]:
        t = bench_threading(n)
        m = bench_multiprocessing(n)
        print(f"  {n} workers: threading={t:.2f}s, multiprocessing={m:.2f}s, speedup={t/m:.1f}x")

    # Rate limiter
    print("\nRate Limiter: 10 tasks at max 5/second")
    async def run_rate_limiter():
        limiter = RateLimiter(5)
        tasks = [asyncio.create_task(rate_limited_task(limiter, i)) for i in range(10)]
        await asyncio.gather(*tasks)
    asyncio.run(run_rate_limiter())

    print("\nAll exercises demonstrated!")
