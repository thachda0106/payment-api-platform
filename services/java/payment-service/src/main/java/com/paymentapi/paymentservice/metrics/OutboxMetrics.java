package com.paymentapi.paymentservice.metrics;

import com.paymentapi.paymentservice.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes payment_outbox_backlog (unpublished outbox rows) to Prometheus via Micrometer.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxRepository outboxRepo) {
        Gauge.builder("payment_outbox_backlog", outboxRepo,
                r -> (double) r.countByPublishedAtIsNull())
            .description("Unpublished rows in payment_outbox")
            .register(registry);
    }
}
