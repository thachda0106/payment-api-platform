package com.paymentapi.paymentservice.service;

import com.paymentapi.paymentservice.entity.OutboxEvent;
import com.paymentapi.paymentservice.repository.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Polls payment_outbox for unpublished events and publishes to Kafka.
 *
 * The database work (read batch, mark published) is intentionally kept OUTSIDE
 * of the Kafka network calls, so no DB connection/transaction is held open
 * during network I/O. Delivery is at-least-once; consumers dedup via eventId.
 */
@Component
public class OutboxPoller {
    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final String TOPIC = "payment-events";
    private static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPoller(OutboxRepository outboxRepo, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishUnpublished() {
        List<OutboxEvent> events = outboxRepo.findUnpublished(BATCH_SIZE);
        if (events.isEmpty()) return;

        log.debug("Publishing {} outbox events", events.size());
        List<UUID> publishedIds = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.getAggregateId().toString(),  // key = paymentId (ordering)
                    event.getPayload()
                );
                if (event.getTraceId() != null) {
                    record.headers().add("traceId", event.getTraceId().getBytes());
                }
                kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                publishedIds.add(event.getId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
            }
        }

        if (!publishedIds.isEmpty()) {
            outboxRepo.markPublished(publishedIds, Instant.now());
        }
    }
}
