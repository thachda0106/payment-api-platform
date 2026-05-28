# Module 03 — Production Node.js

## 3.1 V8 Memory & Performance

### Hidden Classes

V8 creates hidden "maps" describing object shape. Objects with the same properties in the same order share the same hidden class → fast property access (simple offset). Adding properties out of order creates new hidden classes → performance regression.

```typescript
// FAST: same hidden class
const p1 = { amount: 100, currency: "VND" };
const p2 = { amount: 200, currency: "VND" }; // Same hidden class as p1

// SLOW: different initialization order
const p3: any = {}; p3.amount = 300; p3.currency = "USD"; // Different hidden class
// Fix: always initialize in constructor or with object literal
```

### Deoptimization

Triggers: changing property types, adding fields after construction, `delete obj.field`, using `arguments`.

```bash
node --trace-deopt app.js   # See every deoptimization
node --trace-opt app.js     # See when functions are optimized
```

## 3.2 Testing with Vitest

```typescript
import { describe, it, expect, vi, beforeEach } from "vitest";
import { PaymentService } from "./payment.service";

describe("PaymentService", () => {
  const mockFraud = { check: vi.fn() };
  const mockLedger = { write: vi.fn() };

  beforeEach(() => { vi.clearAllMocks(); });

  it("should complete payment when fraud passes", async () => {
    mockFraud.check.mockResolvedValue({ score: 10, decision: "ALLOW" });
    mockLedger.write.mockResolvedValue({ entryId: "je-1" });

    const svc = new PaymentService(mockFraud as any, mockLedger as any);
    const result = await svc.process({ amount: 100000, currency: "VND" });

    expect(result.status).toBe("COMPLETED");
    expect(mockLedger.write).toHaveBeenCalledOnce();
  });
});
```

## 3.3 Debugging & Profiling

```bash
# Debug with Chrome DevTools
node --inspect-brk app.js
# Open chrome://inspect in Chrome

# CPU profiling with clinic.js
clinic doctor -- node app.js      # Overall health
clinic flame -- node app.js       # Flame graph
clinic bubbleprof -- node app.js  # Async operations

# Load testing with autocannon
autocannon -c 100 -d 30 http://localhost:3000/api/payments
```

## 3.4 Production Patterns

### Structured Logging with pino

```typescript
import pino from "pino";
const logger = pino({ level: process.env.LOG_LEVEL || "info" });

logger.info({ paymentId: "P1", amount: 100000 }, "payment_processed");
// Output: {"level":30,"time":...,"pid":...,"paymentId":"P1","amount":100000,"msg":"payment_processed"}
```

### Graceful Shutdown

```typescript
import { createServer } from "http";

const server = createServer(app).listen(3000);

process.on("SIGTERM", () => {
  console.log("SIGTERM received. Closing server...");
  server.close(() => {
    console.log("Server closed. Exiting.");
    process.exit(0);
  });
  // Force exit after 30s
  setTimeout(() => { console.error("Forced exit"); process.exit(1); }, 30000);
});
```

## 3.5 Exercises

### Ex 3.1 — V8 Deoptimization
Write a function that V8 optimizes. Modify it to trigger deoptimization. Use `--trace-deopt` to observe. Fix the deoptimization.

### Ex 3.2 — Memory Leak Detection
Create a memory leak (array growing, closure holding reference). Take heap snapshots at 0s, 30s, 60s. Use Chrome DevTools comparison view to find the leak. Fix it.

### Ex 3.3 — Load Test & Profile
Write a simple HTTP server. Load test with autocannon (100 conn, 30s). Profile with clinic flame. Identify bottleneck. Optimize. Re-test. Document improvement.

---

## 3.6 Self-Assessment

- [ ] Can explain how V8 hidden classes work and how to avoid deoptimization
- [ ] Can write Vitest tests with mocking (vi.fn, mockResolvedValue)
- [ ] Can use `--inspect` to debug and clinic.js to profile
- [ ] Can implement graceful shutdown with SIGTERM handling
- [ ] Can use pino for structured JSON logging
