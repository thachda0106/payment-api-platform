"""Mini Project — Notification API (NestJS-style, simplified)
Run: npx tsx notification_api.ts
Test: curl -X POST http://localhost:3000/notifications -H "Content-Type: application/json" -d '{"userId":"U1","channel":"push","title":"Payment","body":"100K VND sent"}'
"""
import { createServer, IncomingMessage, ServerResponse } from "http";

interface SendDto { userId: string; channel: "push" | "email" | "sms"; title: string; body: string }
interface Notification { id: string; userId: string; channel: string; title: string; body: string; status: string; createdAt: string }

// Simulated notification store
const store: Map<string, Notification> = new Map();

// ─── Services ──────────────────────────────────────────────────────────────
class PushService { async send(n: Notification) { console.log(`  [PUSH] To ${n.userId}: ${n.title} — ${n.body}`); return true; } }
class EmailService { async send(n: Notification) { console.log(`  [EMAIL] To ${n.userId}: ${n.title} — ${n.body}`); return true; } }
class SmsService { async send(n: Notification) { console.log(`  [SMS] To ${n.userId}: ${n.title}`); return true; } }

class NotificationService {
  constructor(private push: PushService, private email: EmailService, private sms: SmsService) {}

  async send(dto: SendDto): Promise<Notification> {
    const n: Notification = { id: crypto.randomUUID(), ...dto, status: "QUEUED", createdAt: new Date().toISOString() };
    store.set(n.id, n);
    switch (dto.channel) {
      case "push": await this.push.send(n); break;
      case "email": await this.email.send(n); break;
      case "sms": await this.sms.send(n); break;
    }
    n.status = "SENT";
    return n;
  }

  getStatus(id: string): Notification | undefined { return store.get(id); }
}

// ─── Simple Router ─────────────────────────────────────────────────────────
const svc = new NotificationService(new PushService(), new EmailService(), new SmsService());

async function handleRequest(req: IncomingMessage, res: ServerResponse) {
  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "UP", service: "notification-api" }));
  } else if (req.method === "POST" && req.url === "/notifications") {
    let body = ""; req.on("data", c => body += c);
    req.on("end", async () => {
      try {
        const dto: SendDto = JSON.parse(body);
        const result = await svc.send(dto);
        res.writeHead(201, { "Content-Type": "application/json" });
        res.end(JSON.stringify(result));
      } catch (e: any) { res.writeHead(400); res.end(JSON.stringify({ error: e.message })); }
    });
  } else if (req.method === "GET" && req.url?.startsWith("/notifications/")) {
    const id = req.url.split("/")[2];
    const n = svc.getStatus(id);
    if (n) { res.writeHead(200, { "Content-Type": "application/json" }); res.end(JSON.stringify(n)); }
    else { res.writeHead(404); res.end(JSON.stringify({ error: "Not found" })); }
  } else { res.writeHead(404); res.end(); }
}

const server = createServer(handleRequest).listen(3000, () => console.log("Notification API on :3000"));

process.on("SIGTERM", () => { console.log("Shutting down..."); server.close(() => process.exit(0)); });
