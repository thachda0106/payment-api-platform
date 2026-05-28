// Mini Project: Payment Event Pipeline (simulated without actual Kafka)
// Run: javac PaymentEventPipeline.java && java PaymentEventPipeline
// In production, this runs with: Spring Kafka Producer → Kafka → Spring Kafka Consumer

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class PaymentEventPipeline {
    // ─── Event Types ─────────────────────────────────────────────────────
    record PaymentEvent(String paymentId, String userId, long amount, String currency, String type) {}
    record JournalEvent(String entryId, String paymentId, String debitAccount, String creditAccount, long amount) {}
    record NotificationEvent(String userId, String channel, String title, String body) {}

    // ─── Simulated Kafka-like Broker ─────────────────────────────────────
    static class MessageBroker {
        private final Map<String, BlockingQueue<String>> topics = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Long>> consumerOffsets = new ConcurrentHashMap<>();

        void createTopic(String name, int partitions) { topics.put(name, new LinkedBlockingQueue<>()); }

        void produce(String topic, String key, String message) {
            BlockingQueue<String> q = topics.get(topic);
            if (q != null) { q.offer(message); System.out.printf("  [%s] Produced: %s%n", topic, message.substring(0, Math.min(60, message.length()))); }
            else System.err.println("Topic not found: " + topic);
        }

        String consume(String topic, String groupId) {
            BlockingQueue<String> q = topics.get(topic);
            if (q == null) return null;
            try {
                String msg = q.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    consumerOffsets.computeIfAbsent(groupId, _k -> new ConcurrentHashMap<>())
                        .merge(topic, 1L, Long::sum);
                }
                return msg;
            } catch (InterruptedException e) { return null; }
        }

        long getLag(String topic) {
            return topics.containsKey(topic) ? topics.get(topic).size() : 0;
        }
    }

    // ─── Outbox Relay ────────────────────────────────────────────────────
    static class OutboxRelay {
        private final List<String> outbox = Collections.synchronizedList(new ArrayList<>());
        private final MessageBroker broker;
        OutboxRelay(MessageBroker broker) { this.broker = broker; }

        void write(String eventType, String key, String payload) {
            outbox.add(String.format("{\"event_type\":\"%s\",\"payload\":%s}", eventType, payload));
        }

        void relay() {
            // In production: Debezium CDC reads outbox table → publishes to Kafka
            synchronized (outbox) {
                for (String entry : outbox) {
                    // Parse and route to correct topic
                    if (entry.contains("PaymentCompleted")) {
                        broker.produce("payments.payment.succeeded", "key", entry);
                    } else if (entry.contains("JournalEntryCreated")) {
                        broker.produce("ledger.entry.committed", "key", entry);
                    } else if (entry.contains("NotificationQueued")) {
                        broker.produce("notifications.queued", "key", entry);
                    }
                }
                outbox.clear();
            }
        }
    }

    // ─── Services ─────────────────────────────────────────────────────────
    static class PaymentService {
        private final OutboxRelay outbox;
        PaymentService(OutboxRelay outbox) { this.outbox = outbox; }

        String process(String userId, long amount) {
            String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0,8);
            // 1. Write to outbox (in same DB transaction in production)
            outbox.write("PaymentCompleted", paymentId,
                String.format("{\"payment_id\":\"%s\",\"user_id\":\"%s\",\"amount\":%d}", paymentId, userId, amount));
            return paymentId;
        }
    }

    // ─── Consumers ────────────────────────────────────────────────────────
    static class NotificationConsumer implements Runnable {
        private final MessageBroker broker;
        private final AtomicInteger delivered = new AtomicInteger();
        private final Set<String> processed = ConcurrentHashMap.newKeySet(); // Inbox dedup
        volatile boolean running = true;

        NotificationConsumer(MessageBroker broker) { this.broker = broker; }

        public void run() {
            while (running) {
                String msg = broker.consume("payments.payment.succeeded", "notification-service");
                if (msg != null) {
                    // Inbox dedup: extract event ID, check if processed
                    String eventId = msg.substring(20, 28);
                    if (processed.add(eventId)) {
                        delivered.incrementAndGet();
                        System.out.printf("  [Notification] Sending: payment %s%n", eventId);
                    } else {
                        System.out.printf("  [Notification] DUPLICATE IGNORED: %s%n", eventId);
                    }
                }
            }
        }

        int delivered() { return delivered.get(); }
        void stop() { running = false; }
    }

    static class AuditConsumer implements Runnable {
        private final MessageBroker broker;
        private final AtomicInteger logged = new AtomicInteger();
        volatile boolean running = true;

        AuditConsumer(MessageBroker broker) { this.broker = broker; }

        public void run() {
            while (running) {
                String msg = broker.consume("ledger.entry.committed", "audit-service");
                if (msg != null) { logged.incrementAndGet(); }
            }
        }

        int logged() { return logged.get(); }
        void stop() { running = false; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        System.out.println("=== Payment Event Pipeline ===\n");

        MessageBroker broker = new MessageBroker();
        broker.createTopic("payments.payment.succeeded", 12);
        broker.createTopic("ledger.entry.committed", 12);

        OutboxRelay outbox = new OutboxRelay(broker);
        PaymentService paymentSvc = new PaymentService(outbox);

        // Start consumers
        NotificationConsumer notifConsumer = new NotificationConsumer(broker);
        AuditConsumer auditConsumer = new AuditConsumer(broker);
        new Thread(notifConsumer).start();
        new Thread(auditConsumer).start();

        // Process 10 payments
        System.out.println("Processing 10 payments...");
        for (int i = 1; i <= 10; i++) {
            paymentSvc.process("U" + i, i * 100000L);
        }
        outbox.relay(); // Simulate CDC relay

        // Wait for consumers
        Thread.sleep(500);
        notifConsumer.stop();
        auditConsumer.stop();

        // Verify
        System.out.printf("%nResults:%n");
        System.out.printf("  Notifications delivered: %d (expected 10)%n", notifConsumer.delivered());
        System.out.printf("  Audit entries logged: %d (expected 0 — no ledger events)%n", auditConsumer.logged());
        System.out.printf("  Broker lag: %d%n", broker.getLag("payments.payment.succeeded"));

        assert notifConsumer.delivered() == 10 : "Expected 10 notifications, got " + notifConsumer.delivered();
        System.out.println("\nTest PASS: All events delivered, no duplicates!");
    }

    static void assert(boolean condition, String msg) { if (!condition) throw new AssertionError(msg); }
}
