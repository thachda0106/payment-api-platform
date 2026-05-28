# Module 03 — CPython Internals & Production Practices

## 3.1 CPython Execution Model

### Source → Bytecode → Execution

```
source.py → [Parser] → AST → [Compiler] → Bytecode → [ceval loop] → Output
```

```python
import dis
def add(a, b):
    return a + b

dis.dis(add)
#   1           0 RESUME                   0
#   2           2 LOAD_FAST                0 (a)
#               4 LOAD_FAST                1 (b)
#               6 BINARY_OP               0 (+)
#              10 RETURN_VALUE
```

Each bytecode instruction is one iteration of the ceval loop in `Python/ceval.c` (CPython's main interpreter loop, ~3000 lines of C).

### The ceval Loop (Simplified)

```c
// Python/ceval.c (simplified)
for (;;) {
    opcode = NEXTOPARG();           // Fetch next instruction
    if (eval_breaker) {             // Check if GIL should be released
        if (gil_drop_requested) {
            release_gil();
            acquire_gil();
        }
    }
    switch (opcode) {
        case LOAD_FAST:  /* load local var */ break;
        case STORE_FAST: /* store to local var */ break;
        case BINARY_ADD: /* pop 2, add, push result */ break;
        case CALL:       /* function call */ break;
        case RETURN_VALUE: /* return from function */ break;
        // ... ~120 opcodes
    }
}
```

**Key insight**: The interpreter checks `eval_breaker` between EVERY instruction. If another thread wants the GIL, the current thread releases it. This is why Python threads switch GIL ownership at ~5ms intervals — not because of a timer, but because the ceval loop checks between instructions.

### CPython Memory Management

**Reference counting**: Every object has `ob_refcnt`. When it reaches 0, the object is freed immediately.

```python
import sys
a = []          # refcount = 1
b = a           # refcount = 2
del a           # refcount = 1
sys.getrefcount(b)  # Returns 2 (the variable + the function argument)
```

**Cyclic garbage collector**: Reference counting can't handle cycles (A → B → A, both refcount > 0, never freed).

```python
class Node:
    def __init__(self): self.ref = None
a = Node(); b = Node()
a.ref = b; b.ref = a  # Cycle! Both refcount = 1 (from each other)
del a; del b          # Refcount = 1 for each, but unreachable → GC collects them
```

The cyclic GC runs periodically (based on allocation rate). It detects unreachable cycles using a tri-color marking algorithm.

---

## 3.2 Package Management

### pyproject.toml (Modern Standard)

```toml
[project]
name = "fraud-service"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
    "fastapi>=0.111.0",
    "scikit-learn>=1.5.0",
    "numpy>=1.26.0",
]

[project.optional-dependencies]
dev = ["pytest>=8.0", "ruff>=0.4", "mypy>=1.10"]

[tool.ruff]
target-version = "py312"
line-length = 100

[tool.mypy]
python_version = "3.12"
strict = true
```

### Package Managers Compared

| Tool | Speed | Lock File | PEP 621 | Recommendation |
|------|:-----:|:---------:|:-------:|----------------|
| **pip** | Slow | No (requirements.txt) | No | Legacy, simple |
| **pip-tools** | Slow | Yes (pip-compile) | No | Better than raw pip |
| **Poetry** | Moderate | Yes (poetry.lock) | Partial | Most popular |
| **uv** (Rust) | 10-100x faster | Yes (uv.lock) | Yes | Recommended for this project |

```bash
# uv (recommended — fast, reliable)
uv pip install fastapi scikit-learn
uv pip compile requirements.in -o requirements.txt  # Generate lock file
uv pip sync requirements.txt  # Install exactly what's in lock file
```

---

## 3.3 Production Practices

### Structured Logging with structlog

```python
import structlog

logger = structlog.get_logger()

def process_payment(payment_id: str, amount: int):
    logger.info("processing_payment", payment_id=payment_id, amount=amount)
    try:
        result = do_process(payment_id, amount)
        logger.info("payment_succeeded", payment_id=payment_id, result=result)
        return result
    except Exception as e:
        logger.error("payment_failed", payment_id=payment_id, error=str(e), exc_info=True)
        raise
```

### Graceful Shutdown

```python
import signal, asyncio

async def main():
    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()

    def handle_signal(sig, frame):
        print(f"\nReceived signal {sig}, shutting down...")
        stop_event.set()

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    # Start your service
    server = await asyncio.start_server(handler, "0.0.0.0", 8000)

    # Wait for shutdown signal
    await stop_event.wait()

    # Graceful shutdown
    server.close()
    await server.wait_closed()
    print("Server shut down gracefully")
```

---

## 3.4 Exercises

### Ex 3.1 — Bytecode Analysis
Write 3 different implementations of a function (for loop, list comprehension, generator). Use `dis.dis()` to compare bytecode. Explain the differences.

### Ex 3.2 — Package Setup
Create a `pyproject.toml` for a small Python library. Add dependencies, dev dependencies, and tool configuration (ruff, mypy). Install with `uv pip install -e .`. Verify imports work.

### Ex 3.3 — Production Readiness
Take a simple FastAPI app. Add: (a) structlog JSON logging, (b) graceful shutdown with SIGTERM handling, (c) health check endpoint, (d) Prometheus metrics endpoint.

---

## 3.5 Self-Assessment

- [ ] Can read Python bytecode with `dis` and explain what each instruction does
- [ ] Understand how the GIL is implemented in the ceval loop
- [ ] Can explain reference counting and cyclic GC
- [ ] Can set up a modern Python project with pyproject.toml
- [ ] Can implement graceful shutdown with signal handling
