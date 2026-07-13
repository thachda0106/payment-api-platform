package com.paymentapi.paymentservice.dto;

import com.paymentapi.paymentservice.entity.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID paymentId,
    String status,
    long amount,   // minor currency units (cents)
    String currency,
    String merchantId,
    String customerId,
    Instant createdAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
            p.getId(), p.getStatus(), p.getAmount(),
            p.getCurrency(), p.getMerchantId(), p.getCustomerId(),
            p.getCreatedAt()
        );
    }
}
