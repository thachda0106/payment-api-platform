package com.paymentapi.financialcore.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentapi.financialcore.repository.ProcessedEventRepository;
import com.paymentapi.financialcore.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes PaymentApproved events from fraud-events topic.
 * Posts double-entry ledger entries and publishes LedgerPosted event.
 */
@Component
public class FraudEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(FraudEventConsumer.class);

    private final LedgerService ledgerService;
    private final ProcessedEventRepository processedEventRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public FraudEventConsumer(LedgerService ledgerService,
                               ProcessedEventRepository processedEventRepo,
                               KafkaTemplate<String, String> kafkaTemplate) {
        this.ledgerService = ledgerService;
        this.processedEventRepo = processedEventRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "fraud-events", groupId = "financial-core")
    public void consume(String message) {
        try {
            JsonNode event = mapper.readTree(message);
            String eventId = event.get("eventId").asText();
            String decision = event.get("decision").asText();

            // Atomic idempotency
            if (!processedEventRepo.markAsProcessed(eventId, "financial-core")) {
                log.debug("Duplicate event {} — skipping", eventId);
                return;
            }

            if (!"APPROVED".equals(decision)) {
                log.info("Payment {} rejected by fraud — no ledger entry", event.get("paymentId").asText());
                return;
            }

            // Post to ledger
            UUID paymentId = UUID.fromString(event.get("paymentId").asText());
            BigDecimal amount = new BigDecimal(event.get("amount").asText());
            String customerId = "customer-" + paymentId.toString().substring(0, 8);
            String merchantId = "merchant-" + paymentId.toString().substring(0, 8);

            UUID ledgerTxnId = ledgerService.postPayment(paymentId, customerId, merchantId, amount);

            // Publish LedgerPosted event
            String outboxEvent = mapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "type", "LedgerEntryCreated",
                "paymentId", paymentId.toString(),
                "ledgerTransactionId", ledgerTxnId.toString(),
                "customerId", customerId,
                "merchantId", merchantId,
                "amount", amount,
                "timestamp", Instant.now().toString()
            ));

            kafkaTemplate.send("ledger-events", paymentId.toString(), outboxEvent);
            log.info("Ledger posted for payment {} — txn {}", paymentId, ledgerTxnId);

        } catch (Exception e) {
            log.error("Failed to process fraud event: {}", e.getMessage(), e);
        }
    }
}
