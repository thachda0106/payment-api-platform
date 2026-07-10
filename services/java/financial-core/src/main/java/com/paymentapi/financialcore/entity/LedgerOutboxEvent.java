package com.paymentapi.financialcore.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox event for financial-core.
 * eventId: unique per event — consumer dedup key in processed_events.
 * aggregateId: paymentId — Kafka message key for partition ordering.
 */
@Entity
@Table(name = "ledger_outbox")
public class LedgerOutboxEvent {
    @Id @GeneratedValue
    private UUID id;

    @Column(unique = true, nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(length = 64)
    private String traceId;

    private Instant createdAt = Instant.now();
    private Instant publishedAt;

    @PrePersist
    void generateEventId() {
        if (this.eventId == null) this.eventId = UUID.randomUUID();
    }

    // ─── Getters/Setters ───
    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID v) { this.eventId = v; }
    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID v) { this.aggregateId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getPayload() { return payload; }
    public void setPayload(String v) { this.payload = v; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }
}
