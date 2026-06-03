package com.paymentapi.paymentservice.dto;

import java.math.BigDecimal;

public record CreatePaymentRequest(
    @jakarta.validation.constraints.NotNull
    BigDecimal amount,

    @jakarta.validation.constraints.NotBlank
    String currency,

    @jakarta.validation.constraints.NotBlank
    String merchantId,

    @jakarta.validation.constraints.NotBlank
    String customerId
) {}
