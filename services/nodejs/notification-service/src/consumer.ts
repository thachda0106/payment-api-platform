/**
 * Kafka consumer (Avro) + inbox pattern for notification-service (Phase-9 P2).
 *
 * Consumes `ledger.entry.committed` (Avro/CloudEvents), records a notification, and
 * writes an `email.queued` CloudEvents envelope to the CDC `outbox`. Inbox pattern:
 *   1. decode + normalize → claim inbox (PENDING); kafkajs commits offset on resolve
 *   2. process (notification insert + outbox + mark COMPLETED) in one transaction
 *   3. on failure mark FAILED; InboxRetryScheduler retries with backoff → DLQ
 */

import { Kafka, Producer, logLevel } from 'kafkajs';
import { SchemaRegistry } from '@kafkajs/confluent-schema-registry';
import { Pool, PoolClient } from 'pg';
import { randomUUID } from 'crypto';

interface LogFn {
  (msg: string, ...args: any[]): void;
  (obj: object, msg: string, ...args: any[]): void;
}
interface LoggerLike { info: LogFn; error: LogFn; }

function createNoopLogger(): LoggerLike {
  const noop = () => {};
  return { info: noop as any, error: noop as any };
}

const SOURCE_TOPIC = 'ledger.entry.committed';
const EVENT_TYPE = 'email.queued';
const EVENT_TOPIC = 'notifications.email.queued';
const DLQ_TOPIC = 'ledger.dlq';
const CONSUMER_GROUP = 'notification-service';
const MAX_RETRIES = 5;
const RETRY_INTERVAL_MS = 5000;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

interface Normalized {
  eventId: string;
  paymentId: string;
  customerId: string;
  amountMinor: number | null;
  currency: string | null;
}

export class EventValidationError extends Error {}

export function normalizeLedgerEvent(event: any): Normalized {
  const data = event?.data ?? {};
  const n: Normalized = {
    eventId: event?.id,
    paymentId: data.payment_id,
    customerId: data.customer_id,
    amountMinor: data.amount ?? null,
    currency: data.currency ?? null,
  };
  if (!n.eventId) throw new EventValidationError('missing id');
  if (!n.paymentId || !UUID_RE.test(n.paymentId)) throw new EventValidationError('payment_id not a UUID');
  if (!n.customerId) throw new EventValidationError('missing customer_id');
  return n;
}

export class NotificationProcessor {
  constructor(private db: Pool, private log: LoggerLike) {}

  /** notifications insert + outbox (email.queued) + mark inbox COMPLETED in one tx. */
  async process(n: Normalized): Promise<void> {
    const client: PoolClient = await this.db.connect();
    try {
      await client.query('BEGIN');
      const email = `${n.customerId}@example.com`;
      await client.query(
        `INSERT INTO notifications (id, payment_id, recipient_email, template, status, sent_at)
         VALUES ($1, $2, $3, $4, 'SENT', now())`,
        [randomUUID(), n.paymentId, email, 'payment_receipt']
      );
      const outId = randomUUID();
      const envelope = {
        id: outId, type: EVENT_TYPE, time: new Date().toISOString(),
        data: {
          payment_id: n.paymentId, recipient_email: email, template: 'payment_receipt',
          amount: n.amountMinor, currency: n.currency,
        },
        trigger: { service: 'notification-service', instance: '', request_id: '', idempotency_key: '' },
      };
      await client.query(
        `INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, event_topic, payload, partition_key)
         VALUES ($1, 'payment', $2, $3, $4, $5::jsonb, $2)`,
        [outId, n.paymentId, EVENT_TYPE, EVENT_TOPIC, JSON.stringify(envelope)]
      );
      await client.query(
        `UPDATE consumer_inbox SET status='COMPLETED', updated_at=now()
         WHERE event_id=$1::uuid AND consumer_group=$2`,
        [n.eventId, CONSUMER_GROUP]
      );
      await client.query('COMMIT');
      this.log.info('Notification recorded for payment %s', n.paymentId);
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    } finally {
      client.release();
    }
  }
}

export class NotificationConsumer {
  private consumer;
  private dlqProducer: Producer;
  private registry: SchemaRegistry;
  private processor: NotificationProcessor;
  private log: LoggerLike;

  constructor(kafka: Kafka, private db: Pool, registryUrl: string, log: LoggerLike = createNoopLogger()) {
    this.consumer = kafka.consumer({ groupId: CONSUMER_GROUP });
    this.dlqProducer = kafka.producer();
    this.log = log;
    const srUser = process.env.SR_BASIC_AUTH_USER;
    this.registry = new SchemaRegistry({
      host: registryUrl,
      ...(srUser ? { auth: { username: srUser, password: process.env.SR_BASIC_AUTH_PASS ?? "" } } : {}),
    });
    this.processor = new NotificationProcessor(db, log);
  }

  async start(): Promise<void> {
    await this.consumer.connect();
    await this.dlqProducer.connect();
    await this.consumer.subscribe({ topic: SOURCE_TOPIC, fromBeginning: true });
    await this.consumer.run({
      eachMessage: async ({ message }) => {
        let n: Normalized;
        try {
          const decoded = await this.registry.decode(message.value!);
          n = normalizeLedgerEvent(decoded);
        } catch (err) {
          this.log.error('Invalid ledger event → DLQ: %s', err);
          await this.sendToDlq(message.value, String(err));
          return; // offset commits (kafkajs) — poison skipped
        }
        const status = await this.claim(n);
        if (status === 'COMPLETED') return;
        try {
          await this.processor.process(n);
        } catch (err) {
          this.log.error({eventId:n.eventId}, 'Processing failed (will retry)');
          await this.markFailed(n.eventId, String(err));
        }
      },
    });
  }

  private async claim(n: Normalized): Promise<string> {
    await this.db.query(
      `INSERT INTO consumer_inbox (event_id, consumer_group, status, payload)
       VALUES ($1::uuid, $2, 'PENDING', $3::jsonb)
       ON CONFLICT (event_id, consumer_group) DO NOTHING`,
      [n.eventId, CONSUMER_GROUP, JSON.stringify(n)]
    );
    const r = await this.db.query(
      `SELECT status FROM consumer_inbox WHERE event_id=$1::uuid AND consumer_group=$2`,
      [n.eventId, CONSUMER_GROUP]
    );
    return r.rows[0].status;
  }

  private async markFailed(eventId: string, error: string): Promise<void> {
    await this.db.query(
      `UPDATE consumer_inbox SET status='FAILED', last_error=$1, updated_at=now()
       WHERE event_id=$2::uuid AND consumer_group=$3`,
      [error.slice(0, 1000), eventId, CONSUMER_GROUP]
    );
  }

  private async sendToDlq(raw: Buffer | null, reason: string): Promise<void> {
    try {
      await this.dlqProducer.send({
        topic: DLQ_TOPIC,
        messages: [{ value: JSON.stringify({
          error: reason, consumer: CONSUMER_GROUP, timestamp: new Date().toISOString(),
          original_hex: raw ? raw.toString('hex') : null,
        }) }],
      });
    } catch (e) {
      this.log.error('Failed to send to DLQ: %s', e);
    }
  }

  async shutdown(): Promise<void> {
    await this.consumer.disconnect();
    await this.dlqProducer.disconnect();
  }
}

export class InboxRetryScheduler {
  private timer: NodeJS.Timeout | null = null;
  private producer: Producer;
  private processor: NotificationProcessor;
  private log: LoggerLike;

  constructor(kafka: Kafka, private db: Pool, log: LoggerLike = createNoopLogger()) {
    this.producer = kafka.producer();
    this.processor = new NotificationProcessor(db, log);
    this.log = log;
  }

  async start(): Promise<void> {
    await this.producer.connect();
    this.timer = setInterval(() => void this.tick(), RETRY_INTERVAL_MS);
  }

  private async tick(): Promise<void> {
    const due = await this.db.query(
      `SELECT event_id::text AS id, payload::text AS payload FROM consumer_inbox
       WHERE consumer_group=$1 AND status='FAILED' AND retry_count < $2
         AND updated_at < now() - (power(2, retry_count) * interval '1 second')
       ORDER BY updated_at LIMIT 50`,
      [CONSUMER_GROUP, MAX_RETRIES]
    );
    for (const row of due.rows) {
      try {
        await this.processor.process(JSON.parse(row.payload));
      } catch (err) {
        await this.db.query(
          `UPDATE consumer_inbox SET retry_count=retry_count+1, last_error=$1, updated_at=now()
           WHERE event_id=$2::uuid AND consumer_group=$3`,
          [String(err).slice(0, 1000), row.id, CONSUMER_GROUP]
        );
      }
    }
    const exhausted = await this.db.query(
      `SELECT event_id::text AS id, payload::text AS payload FROM consumer_inbox
       WHERE consumer_group=$1 AND status='FAILED' AND retry_count >= $2 ORDER BY updated_at LIMIT 50`,
      [CONSUMER_GROUP, MAX_RETRIES]
    );
    for (const row of exhausted.rows) {
      await this.producer.send({ topic: DLQ_TOPIC, messages: [{ value: row.payload }] });
      await this.db.query(
        `UPDATE consumer_inbox SET status='DLQ', updated_at=now() WHERE event_id=$1::uuid AND consumer_group=$2`,
        [row.id, CONSUMER_GROUP]
      );
      this.log.error({eventId: row.id}, 'Event exhausted retries — routed to %s', DLQ_TOPIC);
    }
  }

  async shutdown(): Promise<void> {
    if (this.timer) clearInterval(this.timer);
    await this.producer.disconnect();
  }
}

export { Kafka, logLevel };
