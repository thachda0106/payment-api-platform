# Module 02 — Node.js Runtime & Streams

## 2.1 Node.js Architecture

```
┌─────────────────────────────────────────────────────┐
│                 JavaScript / TypeScript               │  ← Your code
├─────────────────────────────────────────────────────┤
│                   Node.js Core API                    │
│  http / fs / crypto / stream / buffer / net / dgram  │
├───────────────────────┬─────────────────────────────┤
│          V8           │           libuv              │
│   (JavaScript engine)  │   (Async I/O, event loop)    │
│                        │                              │
│  Ignition (interpreter)│   Thread Pool (default: 4)   │
│  TurboFan (compiler)   │   epoll / kqueue / IOCP      │
│  Orinoco (GC)          │   DNS, File I/O, Crypto      │
└────────────────────────┴─────────────────────────────┘
```

**V8**: Compiles JS to native code. Ignition (interpreter) for quick startup, TurboFan (optimizing compiler) for hot code.

**libuv**: Cross-platform async I/O library. Provides: event loop, thread pool (for blocking operations: file I/O, DNS, crypto), TCP/UDP sockets, child processes.

## 2.2 Event Loop — The Six Phases

```
   ┌───────────────────────────┐
   │           timers           │  setTimeout(fn, 0), setInterval(fn, 1000)
   └─────────────┬─────────────┘
                 ▼
   ┌───────────────────────────┐
   │     pending callbacks      │  Deferred I/O callbacks (TCP errors)
   └─────────────┬─────────────┘
                 ▼
   ┌───────────────────────────┐
   │       idle, prepare        │  Internal use only
   └─────────────┬─────────────┘
                 ▼
   ┌───────────────────────────┐
   │           poll             │  I/O polling (epoll_wait). Where most time is spent.
   │  Retrieve new I/O events.  │  If queue empty: block until timer expires or I/O ready.
   └─────────────┬─────────────┘
                 ▼
   ┌───────────────────────────┐
   │           check            │  setImmediate(fn) callbacks
   └─────────────┬─────────────┘
                 ▼
   ┌───────────────────────────┐
   │      close callbacks       │  socket.on("close", fn), cleanup
   └─────────────┬─────────────┘
                 │
                 └──→ back to timers
```

**Microtasks** (process.nextTick and Promise callbacks) run BETWEEN phases:
- After each phase completes, the microtask queue is drained before moving to the next phase.
- `process.nextTick()` runs BEFORE Promise microtasks (higher priority).

```typescript
// Event loop order demonstration
setTimeout(() => console.log("1. setTimeout"), 0);
setImmediate(() => console.log("2. setImmediate"));
Promise.resolve().then(() => console.log("3. Promise.then"));
process.nextTick(() => console.log("4. nextTick"));
console.log("5. synchronous");

// Output:
// 5. synchronous       (sync — always first)
// 4. nextTick          (nextTick before Promise)
// 3. Promise.then      (microtask)
// 1. setTimeout        (timers phase)
// 2. setImmediate      (check phase)
```

**Event loop blockage**: A CPU-intensive synchronous operation blocks ALL phases. No timers fire, no I/O processed, no microtasks run. The thread is stuck.

```typescript
// BLOCKING: 500ms of CPU work blocks everything
function blockEventLoop() {
  const start = Date.now();
  while (Date.now() - start < 500) { /* busy wait */ }
}

// NON-BLOCKING: Offload to Worker thread
import { Worker } from "worker_threads";
function nonBlockingCPU() {
  return new Promise((resolve) => {
    const worker = new Worker("./cpu-task.js");
    worker.on("message", resolve);
  });
}
```

## 2.3 Streams

Streams process data in chunks without loading everything into memory.

```typescript
import { createReadStream, createWriteStream } from "fs";
import { Transform, pipeline } from "stream";
import { promisify } from "util";

const pipelineAsync = promisify(pipeline);

// Readable: emits "data" events
const readable = createReadStream("large_payments.csv", { highWaterMark: 64 * 1024 }); // 64KB chunks
let totalAmount = 0;
readable.on("data", (chunk: Buffer) => {
  totalAmount += chunk.toString().split("\n").length;
});
readable.on("end", () => console.log(`Total lines: ${totalAmount}`));

// Transform: modify data in-flight
const feeTransform = new Transform({
  transform(chunk: Buffer, encoding, callback) {
    const lines = chunk.toString().split("\n");
    const processed = lines.map(line => {
      const parts = line.split(",");
      if (parts.length >= 3) {
        const amount = parseInt(parts[2]);
        parts[2] = String(amount - Math.floor(amount * 0.015)); // Deduct 1.5% fee
      }
      return parts.join(",");
    }).join("\n");
    callback(null, processed);
  }
});

// Writable: write to destination
const writable = createWriteStream("processed_payments.csv");

// Pipeline: connect readable → transform → writable with error handling
await pipelineAsync(readable, feeTransform, writable);
```

**Backpressure**: If the writable is slower than the readable, the readable pauses (via `pause()`) until the writable drains (emits `"drain"` event). This prevents buffer overflow.

## 2.4 Concurrency

```typescript
// async/await — syntactic sugar over Promises
async function processPayment(id: string): Promise<PaymentResult> {
  const fraud = await fraudService.check(id);     // I/O — yields to event loop
  const fee = await feeService.calculate(id);     // I/O — yields to event loop
  return await ledgerService.write(id, fee);       // I/O — yields to event loop
}

// Promise.all — run concurrently
const [fraud, fee] = await Promise.all([
  fraudService.check(id),
  feeService.calculate(id),
]);

// Worker threads — CPU-bound work offloaded to separate thread
import { Worker, isMainThread, parentPort } from "worker_threads";

if (isMainThread) {
  const worker = new Worker(__filename);
  worker.postMessage({ payments: [/* ... */] });
  worker.on("message", (result) => console.log("Fraud scores:", result));
} else {
  parentPort?.on("message", (data) => {
    const scores = data.payments.map(/* CPU-intensive scoring */);
    parentPort?.postMessage(scores);
  });
}

// Cluster — multi-process (one per CPU core)
import cluster from "cluster";
import { availableParallelism } from "os";

if (cluster.isPrimary) {
  for (let i = 0; i < availableParallelism(); i++) cluster.fork();
} else {
  // Worker process — start HTTP server
  app.listen(3000);
}
```

## 2.5 Exercises

### Ex 2.1 — Event Loop Visualization
Write a program that schedules tasks in every event loop phase + microtask queue. Predict the output order. Run and verify.

### Ex 2.2 — Stream Processing
Read a 1GB CSV using streams. Transform each line (deduct fees, filter COMPLETED). Write to output. Memory must stay < 50MB.

### Ex 2.3 — Worker Pool
Create a worker pool for CPU-bound fraud scoring. Queue 1000 scoring requests. Process them in parallel across 4 worker threads. Measure throughput vs single-threaded.

---

## 2.6 Self-Assessment

- [ ] Can explain all 6 event loop phases and when each runs
- [ ] Know the difference between `setImmediate()`, `setTimeout(fn,0)`, and `process.nextTick()`
- [ ] Can implement a stream pipeline with correct error handling and backpressure
- [ ] Understand when to use Worker threads (CPU-bound) vs async I/O
- [ ] Can identify event-loop-blocking code by inspection
