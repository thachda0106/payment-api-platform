# Phase 4 — Python Deep Dive

> **Duration**: 2-3 weeks (full-time) | **Prerequisites**: Phase 2 (DB Fundamentals)
>
> **Goal**: Build async Python services, understand the GIL and work around it, leverage Python's ML/data ecosystem for fraud detection, and write production-grade Python with type hints, tests, and profiling.
>
> **Why Python for the payment platform**: The Fraud & Risk service uses Python because it has the richest ML/AI ecosystem (scikit-learn, XGBoost, PyTorch), the best data analysis tools (pandas, NumPy), and FastAPI's async performance is sufficient for the fraud check path (< 50ms budget). Rapid model iteration without recompilation is critical for fraud rule updates.

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-2 | Module 01 | Syntax, comprehensions, decorators, closures, context managers | 8h |
| 3-4 | Module 01 | OOP (dunder methods, properties, MRO), iterators, generators, type hints | 8h |
| 5-7 | Module 02 | GIL deep dive, threading vs multiprocessing | 8h |
| 8-10 | Module 02 | asyncio (event loop, coroutines, tasks, queues, synchronization) | 8h |
| 11-12 | Module 03 | CPython internals (bytecode, ceval, memory), package management | 8h |
| 13-14 | Module 04 | NumPy, Pandas, pytest, debugging & profiling | 8h |
| 15-18 | Mini Project | Async Fraud Check Service | 12h |

## Setup

```bash
python3 --version  # Should be 3.12+
pip install numpy pandas pytest pytest-asyncio httpx ruff mypy
```

## Resources

- **Book**: "Fluent Python" (Ramalho)
- **Book**: "Python Concurrency with asyncio" (Fowler)
- **Book**: "High Performance Python" (Gorelick & Ozsvald)
- **Blog**: "Python behind the scenes" series
