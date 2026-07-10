package com.paymentapi.financialcore.metrics;

import com.paymentapi.financialcore.repository.LedgerOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes financial-core event-pipeline metrics to Prometheus (via Micrometer):
 *   - ledger_outbox_backlog       (gauge)   unpublished outbox rows
 *   - fraud_events_dlq_total      (counter) messages routed to the DLQ
 */
@Component
public class OutboxMetrics {

    private final Counter dlqCounter;

    public OutboxMetrics(MeterRegistry registry, LedgerOutboxRepository outboxRepo) {
        Gauge.builder("ledger_outbox_backlog", outboxRepo,
                r -> (double) r.countByPublishedAtIsNull())
            .description("Unpublished rows in ledger_outbox")
            .register(registry);

        this.dlqCounter = Counter.builder("fraud_events_dlq_total")
            .description("Messages routed to fraud-events-dlq")
            .register(registry);
    }

    public void incrementDlq() {
        dlqCounter.increment();
    }
}
