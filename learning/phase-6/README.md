# Phase 6 — TypeScript + Node.js Deep Dive

> **Duration**: 2-3 weeks (full-time) | **Prerequisites**: Phase 2 (DB Fundamentals)
>
> **Goal**: Build type-safe Node.js services, understand the event loop and V8 internals, and write code that never blocks the event loop.
>
> **Why Node.js for the payment platform**: Notification Service (push/email/SMS), Transaction Read Model (CQRS), and Fee Engine use Node.js because: async I/O model is ideal for event-driven Kafka consumers, rich ecosystem for push notifications (FCM/APNs) and email, Fastify performance (~45K RPS) for read-heavy BFF layers, TypeScript provides type safety across consumer contracts.

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-3 | Module 01 | TypeScript: types, generics, discriminated unions, mapped types | 10h |
| 4-6 | Module 02 | Node.js runtime: V8, libuv, event loop (6 phases), microtasks | 10h |
| 7-8 | Module 02 | Streams (Readable/Writable/Transform/backpressure), concurrency | 8h |
| 9-10 | Module 03 | Memory management, hidden classes, deoptimization, performance | 8h |
| 11-12 | Module 03 | Testing (Vitest), debugging (--inspect), production (pino, PM2) | 8h |
| 13-16 | Mini Project | Event-Driven Webhook Delivery Service | 12h |

## Setup

```bash
node --version  # Should be 22+
npm install -g typescript tsx vitest
```
