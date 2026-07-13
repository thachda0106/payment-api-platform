package com.paymentapi.financialcore.consumer;

import com.paymentapi.financialcore.metrics.OutboxMetrics;
import com.paymentapi.financialcore.repository.InboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Retries FAILED inbox rows with exponential backoff (≤5 attempts). On exhaustion the
 * event is routed to the DLQ and left FAILED. Decouples retry from Kafka consumption
 * so a transient failure never stalls the partition (Phase-9 P2, CI-4).
 */
@Component
public class InboxRetryScheduler {
    private static final Logger log = LoggerFactory.getLogger(InboxRetryScheduler.class);
    private static final String GROUP = "financial-core";
    private static final String DLQ_TOPIC = "payments.dlq";
    private static final int MAX_RETRIES = 5;
    private static final int BATCH = 50;

    private final InboxRepository inboxRepo;
    private final LedgerPostingService postingService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxMetrics metrics;

    public InboxRetryScheduler(InboxRepository inboxRepo, LedgerPostingService postingService,
                               KafkaTemplate<String, String> kafkaTemplate, OutboxMetrics metrics) {
        this.inboxRepo = inboxRepo;
        this.postingService = postingService;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 5000)
    public void retryFailed() {
        List<String> due = inboxRepo.findRetryable(GROUP, MAX_RETRIES, BATCH);
        for (String eventId : due) {
            String payload = inboxRepo.payloadOf(eventId, GROUP);
            try {
                postingService.process(payload);  // marks COMPLETED on success
            } catch (Exception e) {
                inboxRepo.incrementRetry(eventId, GROUP, e.getMessage());
                log.warn("Retry failed for event {}: {}", eventId, e.getMessage());
            }
        }
        // Route exhausted rows (retry_count >= MAX) to the DLQ.
        for (String eventId : inboxRepo.findExhausted(GROUP, MAX_RETRIES, BATCH)) {
            String payload = inboxRepo.payloadOf(eventId, GROUP);
            kafkaTemplate.send(DLQ_TOPIC, payload);
            inboxRepo.markDlqRouted(eventId, GROUP);
            metrics.incrementDlq();
            log.error("Event {} exhausted retries — routed to {}", eventId, DLQ_TOPIC);
        }
    }
}
