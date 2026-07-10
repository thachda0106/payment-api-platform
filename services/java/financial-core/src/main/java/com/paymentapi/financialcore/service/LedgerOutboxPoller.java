package com.paymentapi.financialcore.service;

import com.paymentapi.financialcore.entity.LedgerOutboxEvent;
import com.paymentapi.financialcore.repository.LedgerOutboxRepository;
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
 * Drains ledger_outbox to the ledger-events topic.
 *
 * DB work (read batch, mark published) is kept OUTSIDE the Kafka network calls,
 * so no DB transaction is held open during network I/O. Delivery is
 * at-least-once; consumers dedup via eventId.
 */
@Component
public class LedgerOutboxPoller {
    private static final Logger log = LoggerFactory.getLogger(LedgerOutboxPoller.class);
    private static final String TOPIC = "ledger-events";
    private static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final LedgerOutboxRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public LedgerOutboxPoller(LedgerOutboxRepository outboxRepo, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishUnpublished() {
        List<LedgerOutboxEvent> events = outboxRepo.findUnpublished(BATCH_SIZE);
        if (events.isEmpty()) return;

        log.debug("Publishing {} ledger outbox events", events.size());
        List<UUID> publishedIds = new ArrayList<>(events.size());
        for (LedgerOutboxEvent event : events) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    event.getAggregateId().toString(),
                    event.getPayload()
                );
                if (event.getTraceId() != null) {
                    record.headers().add("traceId", event.getTraceId().getBytes());
                }
                kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                publishedIds.add(event.getId());
            } catch (Exception e) {
                log.error("Failed to publish ledger outbox event {}: {}", event.getId(), e.getMessage());
            }
        }

        if (!publishedIds.isEmpty()) {
            outboxRepo.markPublished(publishedIds, Instant.now());
        }
    }
}
