package com.paymentapi.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentapi.paymentservice.dto.CreatePaymentRequest;
import com.paymentapi.paymentservice.entity.OutboxEvent;
import com.paymentapi.paymentservice.entity.Payment;
import com.paymentapi.paymentservice.repository.OutboxRepository;
import com.paymentapi.paymentservice.repository.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional writer: persists the payment and a CDC outbox row atomically.
 * The outbox row carries a CloudEvents envelope (JSON) that a Debezium connector
 * publishes to `payments.payment.created` as Avro.
 */
@Component
public class PaymentWriter {
    private static final String AGGREGATE_TYPE = "payment";
    private static final String EVENT_TYPE = "payment.created";
    private static final String EVENT_TOPIC = "payments.payment.created";

    private final PaymentRepository paymentRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper mapper;

    public PaymentWriter(PaymentRepository paymentRepo, OutboxRepository outboxRepo, ObjectMapper mapper) {
        this.paymentRepo = paymentRepo;
        this.outboxRepo = outboxRepo;
        this.mapper = mapper;
    }

    @Transactional
    public Payment persist(CreatePaymentRequest req, String idempotencyKey, String traceId) {
        Payment payment = new Payment();
        payment.setIdempotencyKey(idempotencyKey);
        payment.setAmount(req.amount());
        payment.setCurrency(req.currency());
        payment.setMerchantId(req.merchantId());
        payment.setCustomerId(req.customerId());
        payment.setStatus("CREATED");
        payment = paymentRepo.saveAndFlush(payment);

        outboxRepo.save(buildOutbox(payment, traceId));
        return payment;
    }

    private OutboxEvent buildOutbox(Payment p, String traceId) {
        UUID eventId = UUID.randomUUID();
        String paymentId = p.getId().toString();

        OutboxEvent event = new OutboxEvent();
        event.setId(eventId);
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(paymentId);
        event.setEventType(EVENT_TYPE);
        event.setEventTopic(EVENT_TOPIC);
        event.setPartitionKey(paymentId);
        event.setPayload(cloudEvent(eventId, paymentId, p, traceId));
        return event;
    }

    /** CloudEvents envelope: { id, type, time, data{...}, trigger{...} }. Amount in minor units. */
    private String cloudEvent(UUID eventId, String paymentId, Payment p, String traceId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment_id", paymentId);
        data.put("customer_id", p.getCustomerId());
        data.put("merchant_id", p.getMerchantId());
        data.put("amount", p.getAmount());   // already minor units
        data.put("currency", p.getCurrency());

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("service", "payment-service");
        trigger.put("instance", "");
        trigger.put("request_id", traceId == null ? "" : traceId);
        trigger.put("idempotency_key", p.getIdempotencyKey());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId.toString());
        envelope.put("type", EVENT_TYPE);
        envelope.put("time", Instant.now().toString());
        envelope.put("data", data);
        envelope.put("trigger", trigger);
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize CloudEvents payload", e);
        }
    }
}
