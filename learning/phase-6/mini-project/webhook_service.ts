// Mini Project: Event-Driven Webhook Delivery Service
// Receives events, delivers to configured webhook URLs with retry + rate limiting.

import { createServer, IncomingMessage, ServerResponse } from "http";
import { EventEmitter } from "events";

interface WebhookConfig {
  url: string;
  secret: string; // HMAC signing key
}

interface DeliveryJob {
  eventId: string;
  eventType: string;
  payload: Record<string, unknown>;
  webhook: WebhookConfig;
  attempt: number;
  nextRetryAt: number;
}

class WebhookDeliveryService extends EventEmitter {
  private queue: DeliveryJob[] = [];
  private processing = false;
  private rateLimiter: Map<string, number> = new Map(); // url → last send timestamp
  private maxRetries = 5;
  private baseDelay = 1000; // 1 second
  private rateLimitMs = 100; // 10 req/s per URL = 100ms between

  // Rate limit: max 10 requests/second per URL
  private canSend(url: string): boolean {
    const last = this.rateLimiter.get(url) || 0;
    return Date.now() - last >= this.rateLimitMs;
  }

  // Enqueue a delivery job
  enqueue(eventId: string, eventType: string, payload: Record<string, unknown>, webhook: WebhookConfig): void {
    this.queue.push({ eventId, eventType, payload, webhook, attempt: 0, nextRetryAt: Date.now() });
    this.emit("enqueued", { eventId });
    this.processQueue();
  }

  // Process queue (runs continuously)
  private async processQueue(): Promise<void> {
    if (this.processing) return;
    this.processing = true;

    while (this.queue.length > 0) {
      const job = this.queue.find(j => j.nextRetryAt <= Date.now());
      if (!job) { await sleep(100); continue; }

      if (!this.canSend(job.webhook.url)) { await sleep(50); continue; }

      const delivered = await this.deliver(job);
      if (delivered) {
        this.queue = this.queue.filter(j => j.eventId !== job.eventId);
        this.emit("delivered", { eventId: job.eventId, attempt: job.attempt + 1 });
      } else if (job.attempt >= this.maxRetries) {
        this.queue = this.queue.filter(j => j.eventId !== job.eventId);
        this.emit("failed_permanently", { eventId: job.eventId, attempts: job.attempt });
      } else {
        job.attempt++;
        job.nextRetryAt = Date.now() + this.baseDelay * Math.pow(2, job.attempt);
        this.emit("retry", { eventId: job.eventId, attempt: job.attempt, nextRetryMs: job.nextRetryAt - Date.now() });
      }
    }
    this.processing = false;
  }

  // Attempt delivery to webhook URL with HMAC signature
  private async deliver(job: DeliveryJob): Promise<boolean> {
    this.rateLimiter.set(job.webhook.url, Date.now());
    try {
      const signature = this.hmacSign(job.payload, job.webhook.secret);
      // In production: use fetch() with retry logic
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 5000);

      const response = await fetch(job.webhook.url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Webhook-Signature": signature,
          "X-Event-Type": job.eventType,
          "X-Event-ID": job.eventId,
        },
        body: JSON.stringify(job.payload),
        signal: controller.signal,
      });
      clearTimeout(timeout);
      return response.ok;
    } catch {
      return false; // Network error — will retry
    }
  }

  // Simple HMAC (in production: use crypto.createHmac)
  private hmacSign(payload: Record<string, unknown>, secret: string): string {
    const crypto = require("crypto");
    return crypto.createHmac("sha256", secret).update(JSON.stringify(payload)).digest("hex");
  }

  // Metrics
  metrics() {
    return { queueSize: this.queue.length, processing: this.processing };
  }
}

function sleep(ms: number): Promise<void> { return new Promise(r => setTimeout(r, ms)); }

// ═══════════════════════════════════════════════════════════════════════════
// Demo Server
// ═══════════════════════════════════════════════════════════════════════════
const service = new WebhookDeliveryService();

// Simulated webhook receiver (acts as merchant endpoint)
const receiver = createServer((req: IncomingMessage, res: ServerResponse) => {
  let body = "";
  req.on("data", c => body += c);
  req.on("end", () => {
    const eventType = req.headers["x-event-type"];
    console.log(`[RECEIVER] Got ${eventType}: ${body.slice(0, 50)}...`);
    res.writeHead(200); res.end();
  });
}).listen(9090, () => console.log("Webhook receiver on :9090"));

// Delivery service HTTP API
const server = createServer(async (req: IncomingMessage, res: ServerResponse) => {
  if (req.method === "POST" && req.url === "/webhooks/deliver") {
    let body = "";
    req.on("data", c => body += c);
    req.on("end", () => {
      const { eventId, eventType, payload } = JSON.parse(body);
      service.enqueue(eventId, eventType, payload, { url: "http://localhost:9090/webhook", secret: "secret123" });
      res.writeHead(202); res.end(JSON.stringify({ status: "queued", eventId }));
    });
  } else if (req.url === "/health") {
    res.writeHead(200); res.end(JSON.stringify({ status: "UP", ...service.metrics() }));
  } else {
    res.writeHead(404); res.end();
  }
});

service.on("delivered", (d) => console.log(`  ✓ Delivered ${d.eventId} (attempt ${d.attempt})`));
service.on("retry", (d) => console.log(`  ⟳ Retry ${d.eventId} attempt ${d.attempt}`));
service.on("failed_permanently", (d) => console.log(`  ✗ Failed ${d.eventId} after ${d.attempts} attempts`));

server.listen(3001, () => {
  console.log("Webhook Delivery Service on :3001\n");
  // Send 5 test deliveries
  for (let i = 1; i <= 5; i++) {
    fetch("http://localhost:3001/webhooks/deliver", {
      method: "POST",
      body: JSON.stringify({ eventId: `evt-${i}`, eventType: "payment.completed", payload: { amount: i * 100000 } }),
      headers: { "Content-Type": "application/json" },
    });
  }
});
