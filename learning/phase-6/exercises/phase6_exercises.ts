// Phase 6 Exercises — TypeScript + Node.js

// ═══════════ 1.1 — Payment State Machine ═══════════
type PaymentState =
  | { status: "PENDING" }
  | { status: "AUTHORIZED"; authorizedAt: Date }
  | { status: "COMPLETED"; completedAt: Date; journalId: string }
  | { status: "FAILED"; reason: string; failedAt: Date };

type PaymentEvent = { type: "AUTHORIZE" } | { type: "COMPLETE"; journalId: string } | { type: "FAIL"; reason: string };

function transition(state: PaymentState, event: PaymentEvent): PaymentState {
  switch (state.status) {
    case "PENDING":
      if (event.type === "AUTHORIZE") return { status: "AUTHORIZED", authorizedAt: new Date() };
      if (event.type === "FAIL") return { status: "FAILED", reason: event.reason, failedAt: new Date() };
      break;
    case "AUTHORIZED":
      if (event.type === "COMPLETE") return { status: "COMPLETED", completedAt: new Date(), journalId: event.journalId };
      if (event.type === "FAIL") return { status: "FAILED", reason: event.reason, failedAt: new Date() };
      break;
    default:
      throw new Error(`Invalid transition from ${state.status} with ${event.type}`);
  }
  return state; // No valid transition
}

// ═══════════ 1.2 — Typed Event Bus ═══════════
type EventMap = {
  PaymentCompleted: { paymentId: string; amount: number };
  PaymentFailed: { paymentId: string; reason: string };
  RefundProcessed: { refundId: string; originalPaymentId: string };
};

class TypedEventBus<T extends Record<string, any>> {
  private handlers = new Map<string, Set<Function>>();

  on<K extends keyof T>(event: K, handler: (payload: T[K]) => void): void {
    const key = event as string;
    if (!this.handlers.has(key)) this.handlers.set(key, new Set());
    this.handlers.get(key)!.add(handler);
  }

  emit<K extends keyof T>(event: K, payload: T[K]): void {
    this.handlers.get(event as string)?.forEach(h => h(payload));
  }
}

// ═══════════ 2.1 — Event Loop Order ═══════════
function demoEventLoop() {
  setTimeout(() => console.log("1. setTimeout(0)"), 0);
  setImmediate(() => console.log("2. setImmediate"));
  Promise.resolve().then(() => console.log("3. Promise.then"));
  process.nextTick(() => console.log("4. nextTick"));
  queueMicrotask(() => console.log("5. queueMicrotask"));
  console.log("6. synchronous");
}

// ═══════════ 2.2 — Stream Processing ═══════════
import { createReadStream, createWriteStream } from "fs";
import { Transform, pipeline } from "stream";
import { promisify } from "util";
const pipe = promisify(pipeline);

async function processLargeCSV(input: string, output: string) {
  let total = 0; let count = 0;
  const transform = new Transform({
    transform(chunk: Buffer, _encoding, callback) {
      const lines = chunk.toString().split("\n").filter(l => l);
      for (const line of lines) {
        const parts = line.split(",");
        if (parts[2]) { total += parseInt(parts[2]); count++; }
      }
      callback(null, chunk);
    }
  });
  await pipe(createReadStream(input), transform, createWriteStream(output));
  console.log(`Processed ${count} lines, total=${total}`);
}

// ═══════════ MAIN ═══════════
console.log("=== Phase 6 Exercises ===\n");

const bus = new TypedEventBus<EventMap>();
bus.on("PaymentCompleted", (p) => console.log(`Payment ${p.paymentId} completed: ${p.amount}`));
bus.emit("PaymentCompleted", { paymentId: "P1", amount: 100000 });

const state: PaymentState = { status: "PENDING" };
const next = transition(state, { type: "AUTHORIZE" });
console.log(`State: ${state.status} → ${next.status}`);

demoEventLoop();
