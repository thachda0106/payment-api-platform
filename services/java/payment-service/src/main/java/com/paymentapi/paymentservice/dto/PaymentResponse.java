package com.paymentapi.paymentservice.dto;

import com.paymentapi.paymentservice.entity.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID paymentId,
    String status,
    BigDecimal amount,
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
