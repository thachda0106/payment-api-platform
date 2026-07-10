/**
 * Kafka consumer + transactional outbox for notification-service.
 *
 * Consumes LedgerEntryCreated events from `ledger-events`, records the notification,
 * and writes a `notification_outbox` row — all in a SINGLE DB transaction
 * (atomic idempotency + no dual-write). A poller drains `notification_outbox` to
 * the `notification-events` topic.
 */

import { Kafka, Producer, logLevel } from 'kafkajs';
import { Pool } from 'pg';
import { randomUUID } from 'crypto';

const SOURCE_TOPIC = 'ledger-events';
const OUTBOX_TOPIC = 'notification-events';
const DLQ_TOPIC = 'ledger-events-dlq';
const CONSUMER_GROUP = 'notification-service';
const POLL_INTERVAL_MS = 1000;
const POLL_BATCH_SIZE = 100;

interface LedgerEvent {
  eventId: string;
  type: string;
  paymentId: string;
  ledgerTransactionId: string;
  customerId: string;
  amount: string;
  currency?: string;
  timestamp: string;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export class EventValidationError extends Error {}


export function validateLedgerEvent(e: Partial<LedgerEvent>): asserts e is LedgerEvent {
  if (!e.eventId) throw new EventValidationError('missing eventId');
  if (!e.paymentId || !UUID_RE.test(e.paymentId)) throw new EventValidationError('paymentId not a UUID');
  if (e.amount === undefined || Number(e.amount) <= 0 || Number.isNaN(Number(e.amount))) {
    throw new EventValidationError(`amount must be > 0: ${e.amount}`);
  }
  if (!e.customerId) throw new EventValidationError('missing customerId');
}

export class NotificationConsumer {
  private consumer;
  private dlqProducer: Producer;
  private db: Pool;

  constructor(kafka: Kafka, db: Pool) {
    this.consumer = kafka.consumer({ groupId: CONSUMER_GROUP });
    this.dlqProducer = kafka.producer();
    this.db = db;
  }

  async start(): Promise<void> {
    await this.consumer.connect();
    await this.dlqProducer.connect();
    await this.consumer.subscribe({ topic: SOURCE_TOPIC, fromBeginning: true });

    await this.consumer.run({
      eachMessage: async ({ message }) => {
        let event: LedgerEvent;
        try {
          const parsed = JSON.parse(message.value!.toString());
          validateLedgerEvent(parsed);
          event = parsed;
        } catch (err) {
          console.error('Invalid ledger event → DLQ:', err);
          await this.sendToDlq(message.value!.toString(), String(err));
          return;
        }
        await this.handle(event);
      },
    });
  }

  private async sendToDlq(rawMessage: string, reason: string): Promise<void> {
    try {
      await this.dlqProducer.send({
        topic: DLQ_TOPIC,
        messages: [{
          value: JSON.stringify({
            error: reason,
            consumer: CONSUMER_GROUP,
            timestamp: new Date().toISOString(),
            original: rawMessage,
          }),
        }],
      });
    } catch (e) {
      console.error('Failed to send to DLQ:', e);
    }
  }

  private async handle(event: LedgerEvent): Promise<void> {
    const client = await this.db.connect();
    try {
      await client.query('BEGIN');

      // Atomic idempotency — dedup mark shares the transaction with the writes.
      const dedup = await client.query(
        `INSERT INTO processed_events (event_id, consumer_group, processed_at)
         VALUES ($1, $2, now())
         ON CONFLICT (event_id, consumer_group) DO NOTHING
         RETURNING event_id`,
        [event.eventId, CONSUMER_GROUP]
      );
      if (dedup.rowCount === 0) {
        await client.query('COMMIT');
        console.log(`Duplicate event ${event.eventId} — skipping`);
        return;
      }

      // Send receipt (mock — real implementation uses nodemailer).
      const email = `${event.customerId}@example.com`;
      console.log(`Sending receipt to ${email} for payment ${event.paymentId} amount ${event.amount}`);

      await client.query(
        `INSERT INTO notifications (id, payment_id, recipient_email, template, status, sent_at)
         VALUES ($1, $2, $3, $4, 'SENT', now())`,
        [randomUUID(), event.paymentId, email, 'payment_receipt']
      );

      const outEventId = randomUUID();
      const payload = {
        v: 1,
        eventId: outEventId,
        type: 'NotificationSent',
        paymentId: event.paymentId,
        recipientEmail: email,
        amount: event.amount,
        currency: event.currency ?? null,
        timestamp: new Date().toISOString(),
      };
      await client.query(
        `INSERT INTO notification_outbox (event_id, aggregate_id, event_type, payload)
         VALUES ($1, $2, $3, $4::jsonb)`,
        [outEventId, event.paymentId, 'NotificationSent', JSON.stringify(payload)]
      );

      await client.query('COMMIT');
      console.log(`Notification recorded for payment ${event.paymentId}`);
    } catch (err) {
      await client.query('ROLLBACK');
      throw err; // transient — kafkajs redelivers; dedup makes reprocessing safe
    } finally {
      client.release();
    }
  }

  async shutdown(): Promise<void> {
    await this.consumer.disconnect();
    await this.dlqProducer.disconnect();
  }
}

export class NotificationOutboxPoller {
  private db: Pool;
  private producer: Producer;
  private timer: NodeJS.Timeout | null = null;
  private draining = false;

  constructor(db: Pool, producer: Producer) {
    this.db = db;
    this.producer = producer;
  }

  async start(): Promise<void> {
    await this.producer.connect();
    this.timer = setInterval(() => void this.drain(), POLL_INTERVAL_MS);
  }

  async backlog(): Promise<number> {
    const res = await this.db.query('SELECT count(*)::int AS n FROM notification_outbox WHERE published_at IS NULL');
    return res.rows[0].n;
  }

  private async drain(): Promise<void> {
    if (this.draining) return;
    this.draining = true;
    const client = await this.db.connect();
    try {
      await client.query('BEGIN');
      const rows = await client.query(
        `SELECT id, aggregate_id, payload
         FROM notification_outbox
         WHERE published_at IS NULL
         ORDER BY created_at, id
         FOR UPDATE SKIP LOCKED
         LIMIT $1`,
        [POLL_BATCH_SIZE]
      );
      for (const row of rows.rows) {
        await this.producer.send({
          topic: OUTBOX_TOPIC,
          messages: [{ key: String(row.aggregate_id), value: JSON.stringify(row.payload) }],
        });
        await client.query('UPDATE notification_outbox SET published_at = now() WHERE id = $1', [row.id]);
      }
      await client.query('COMMIT');
      if (rows.rowCount) console.log(`Published ${rows.rowCount} notification-events`);
    } catch (err) {
      await client.query('ROLLBACK');
      console.error('notification outbox drain failed:', err);
    } finally {
      client.release();
      this.draining = false;
    }
  }

  async shutdown(): Promise<void> {
    if (this.timer) clearInterval(this.timer);
    await this.producer.disconnect();
  }
}

export { Kafka, logLevel };
