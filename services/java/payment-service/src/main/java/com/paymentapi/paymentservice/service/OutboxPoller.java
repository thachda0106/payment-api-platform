package com.paymentapi.paymentservice.service;

import com.paymentapi.paymentservice.entity.OutboxEvent;
import com.paymentapi.paymentservice.repository.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls payment_outbox for unpublished events and publishes to Kafka.
 * Uses SELECT ... FOR UPDATE SKIP LOCKED for concurrent safety.
 *
 * Phase 7: synchronous .get() for simplicity.
 * Phase 8: convert to async batch with CompletableFuture.whenComplete().
 */
@Component
public class OutboxPoller {
    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final String TOPIC = "payment-events";
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPoller(OutboxRepository outboxRepo, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishUnpublished() {
        List<OutboxEvent> events = outboxRepo.findUnpublished(BATCH_SIZE);
        if (events.isEmpty()) return;

        log.debug("Publishing {} outbox events", events.size());
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
                // Phase 7: synchronous. Phase 8: switch to async with whenComplete()
                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

                event.setPublishedAt(Instant.now());
                outboxRepo.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}
