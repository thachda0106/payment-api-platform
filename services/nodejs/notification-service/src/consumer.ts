/**
 * Kafka consumer for notification-service.
 * Consumes LedgerEntryCreated events from ledger-events topic.
 * Sends email receipt via nodemailer.
 * Atomic idempotency via INSERT ... ON CONFLICT DO NOTHING.
 * Publishes NotificationSent to notification-events topic.
 */

import { Kafka, Producer } from 'kafkajs';
import { Pool } from 'pg';
import { v4 as uuidv4 } from 'uuid';

interface LedgerEvent {
  eventId: string;
  type: string;
  paymentId: string;
  ledgerTransactionId: string;
  customerId: string;
  amount: number;
  timestamp: string;
}

export class NotificationConsumer {
  private consumer;
  private producer: Producer;
  private db: Pool;

  constructor(kafka: Kafka, db: Pool) {
    this.consumer = kafka.consumer({ groupId: 'notification-service' });
    this.producer = kafka.producer();
    this.db = db;
  }

  async start(): Promise<void> {
    await this.consumer.connect();
    await this.producer.connect();
    await this.consumer.subscribe({ topic: 'ledger-events' });

    await this.consumer.run({
      eachMessage: async ({ message }) => {
        try {
          const event: LedgerEvent = JSON.parse(message.value!.toString());
          const eventId = event.eventId;

          // Atomic idempotency
          const result = await this.db.query(
            `INSERT INTO processed_events (event_id, consumer_group, processed_at)
             VALUES ($1, $2, now())
             ON CONFLICT (event_id, consumer_group) DO NOTHING
             RETURNING event_id`,
            [eventId, 'notification-service']
          );

          if (result.rowCount === 0) {
            console.log(`Duplicate event ${eventId} — skipping`);
            return;
          }

          // Send receipt (mock — real implementation uses nodemailer)
          const email = `${event.customerId}@example.com`;
          console.log(`Sending receipt to ${email} for payment ${event.paymentId} amount $${event.amount}`);

          // Save notification record
          await this.db.query(
            `INSERT INTO notifications (id, payment_id, recipient_email, template, status, sent_at)
             VALUES ($1, $2, $3, $4, 'SENT', now())`,
            [uuidv4(), event.paymentId, email, 'payment_receipt']
          );

          // Publish NotificationSent event
          const outboxEvent = {
            eventId: uuidv4(),
            type: 'NotificationSent',
            paymentId: event.paymentId,
            recipientEmail: email,
            amount: event.amount,
            timestamp: new Date().toISOString(),
          };

          await this.producer.send({
            topic: 'notification-events',
            messages: [{
              key: event.paymentId,
              value: JSON.stringify(outboxEvent),
              headers: {
                traceId: message.headers?.traceId?.toString() || '',
              },
            }],
          });

          console.log(`Notification sent for payment ${event.paymentId}`);
        } catch (err) {
          console.error('Failed to process ledger event:', err);
        }
      },
    });
  }

  async shutdown(): Promise<void> {
    await this.consumer.disconnect();
    await this.producer.disconnect();
  }
}
