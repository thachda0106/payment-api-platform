package com.paymentapi.financialcore.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * financial-core DLQ metric. Outbox backlog is now observed via Debezium connector
 * lag metrics (Phase-9), not an application gauge.
 */
@Component
public class OutboxMetrics {

    private final Counter dlqCounter;

    public OutboxMetrics(MeterRegistry registry) {
        this.dlqCounter = Counter.builder("payments_dlq_total")
            .description("Messages routed to payments.dlq")
            .register(registry);
    }

    public void incrementDlq() {
        dlqCounter.increment();
    }
}
