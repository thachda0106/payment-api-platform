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
import java.util.Map;
import java.util.UUID;

/**
 * Transactional writer: persists the payment and its outbox event atomically.
 * Kept separate from {@link PaymentService} so the idempotency retry (catch +
 * re-read) runs OUTSIDE this transaction — a unique-key violation dooms the
 * current transaction, so the cached read must happen in a fresh one.
 */
@Component
public class PaymentWriter {
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
        // Force the INSERT (and thus the unique-key check) to happen now, inside
        // this transaction, so a duplicate surfaces as DataIntegrityViolationException.
        payment = paymentRepo.saveAndFlush(payment);

        OutboxEvent event = new OutboxEvent();
        UUID eventId = UUID.randomUUID();
        event.setEventId(eventId);
        event.setAggregateId(payment.getId());
        event.setEventType("PaymentCreated");
        event.setPayload(toPayload(eventId, payment));
        event.setTraceId(traceId);
        outboxRepo.save(event);

        return payment;
    }

    private String toPayload(UUID eventId, Payment p) {
        try {
            return mapper.writeValueAsString(Map.of(
                "v", 1,
                "eventId", eventId.toString(),
                "type", "PaymentCreated",
                "paymentId", p.getId().toString(),
                "amount", p.getAmount(),
                "currency", p.getCurrency(),
                "merchantId", p.getMerchantId(),
                "customerId", p.getCustomerId(),
                "timestamp", Instant.now().toString()
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }
}
