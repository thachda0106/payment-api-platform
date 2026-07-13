package com.paymentapi.paymentservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(
    @NotNull(message = "amount is required")
    @Positive(message = "amount must be greater than 0")
    Long amount,   // minor currency units (e.g. cents)

    @NotNull(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
    String currency,

    @NotNull(message = "merchantId is required")
    @Size(min = 1, max = 64, message = "merchantId must be 1-64 characters")
    String merchantId,

    @NotNull(message = "customerId is required")
    @Size(min = 1, max = 64, message = "customerId must be 1-64 characters")
    String customerId
) {}
