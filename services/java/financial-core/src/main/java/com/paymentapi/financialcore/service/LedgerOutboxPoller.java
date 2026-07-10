package com.paymentapi.financialcore.service;

import com.paymentapi.financialcore.entity.LedgerOutboxEvent;
import com.paymentapi.financialcore.repository.LedgerOutboxRepository;
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

@Component
public class LedgerOutboxPoller {
    private static final Logger log = LoggerFactory.getLogger(LedgerOutboxPoller.class);
    private static final String TOPIC = "ledger-events";
    private static final int BATCH_SIZE = 100;

    private final LedgerOutboxRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public LedgerOutboxPoller(LedgerOutboxRepository outboxRepo, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishUnpublished() {
        List<LedgerOutboxEvent> events = outboxRepo.findUnpublished(BATCH_SIZE);
        if (events.isEmpty()) return;

        log.debug("Publishing {} ledger outbox events", events.size());
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
                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

                event.setPublishedAt(Instant.now());
                outboxRepo.save(event);
            } catch (Exception e) {
                log.error("Failed to publish ledger outbox event {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}
