package com.paymentapi.financialcore.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentapi.financialcore.metrics.OutboxMetrics;
import com.paymentapi.financialcore.repository.InboxRepository;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Consumes `payments.payment.succeeded` (Avro/CloudEvents) and posts the ledger via
 * the inbox pattern (Phase-9 P2):
 *   1. normalize the event → JSON, claim the inbox (PENDING); offset commits on return
 *   2. process (post ledger + outbox + mark COMPLETED) in one transaction
 *   3. on failure, mark FAILED — InboxRetryScheduler retries with backoff → DLQ
 */
@Component
public class PaymentSucceededConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentSucceededConsumer.class);
    private static final String GROUP = "financial-core";
    private static final String DLQ_TOPIC = "payments.dlq";
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final LedgerPostingService postingService;
    private final InboxRepository inboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxMetrics metrics;
    private final ObjectMapper mapper;

    public PaymentSucceededConsumer(LedgerPostingService postingService, InboxRepository inboxRepo,
                                    KafkaTemplate<String, String> kafkaTemplate,
                                    OutboxMetrics metrics, ObjectMapper mapper) {
        this.postingService = postingService;
        this.inboxRepo = inboxRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "payments.payment.succeeded", groupId = GROUP)
    public void consume(GenericRecord root) {
        String normalized;
        String eventId;
        try {
            Map<String, Object> n = normalize(root);
            eventId = (String) n.get("eventId");
            normalized = mapper.writeValueAsString(n);
        } catch (Exception e) {
            log.error("Invalid payment.succeeded event → DLQ: {}", e.getMessage());
            sendToDlq(String.valueOf(root), e.getMessage());
            return;
        }

        if (inboxRepo.claim(eventId, GROUP, normalized) == InboxRepository.Claim.SKIP) {
            log.debug("Duplicate event {} — already completed", eventId);
            return;
        }

        try {
            postingService.process(normalized);
        } catch (Exception e) {
            log.error("Ledger posting failed for event {} (will retry): {}", eventId, e.getMessage());
            inboxRepo.markFailed(eventId, GROUP, e.getMessage());
        }
    }

    /** Flatten the Avro/CloudEvents record into the normalized inbox payload. */
    private Map<String, Object> normalize(GenericRecord root) {
        GenericRecord data = (GenericRecord) root.get("data");
        String eventId = str(root.get("id"));
        String paymentId = str(data.get("payment_id"));
        String customerId = str(data.get("customer_id"));
        String merchantId = str(data.get("merchant_id"));
        long amountMinor = ((Number) data.get("amount")).longValue();
        String currency = str(data.get("currency"));

        if (isBlank(eventId)) throw new IllegalArgumentException("missing id");
        if (isBlank(paymentId)) throw new IllegalArgumentException("missing payment_id");
        java.util.UUID.fromString(paymentId);
        if (isBlank(customerId)) throw new IllegalArgumentException("missing customer_id");
        if (isBlank(merchantId)) throw new IllegalArgumentException("missing merchant_id");
        if (currency == null || !CURRENCY.matcher(currency).matches())
            throw new IllegalArgumentException("currency not ISO 4217");
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be > 0");

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("eventId", eventId);
        n.put("paymentId", paymentId);
        n.put("customerId", customerId);
        n.put("merchantId", merchantId);
        n.put("amountMinor", amountMinor);
        n.put("currency", currency);
        return n;
    }

    private void sendToDlq(String raw, String reason) {
        try {
            kafkaTemplate.send(DLQ_TOPIC, mapper.writeValueAsString(Map.of(
                "error", reason, "consumer", GROUP, "original", raw)));
            metrics.incrementDlq();
        } catch (Exception e) {
            log.error("Failed to send to DLQ: {}", e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
