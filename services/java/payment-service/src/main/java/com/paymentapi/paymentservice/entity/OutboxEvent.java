package com.paymentapi.paymentservice.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Debezium CDC outbox row (Phase-9). One row = one CloudEvents envelope to publish.
 * `id` is the CloudEvents id / consumer dedup key; `partitionKey` is the Kafka key
 * (paymentId) for ordering; `eventTopic` names the destination topic (EventRouter).
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_topic", nullable = false)
    private String eventTopic;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // ─── Getters/Setters ───
    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String v) { this.aggregateType = v; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String v) { this.aggregateId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getEventTopic() { return eventTopic; }
    public void setEventTopic(String v) { this.eventTopic = v; }
    public String getPayload() { return payload; }
    public void setPayload(String v) { this.payload = v; }
    public String getPartitionKey() { return partitionKey; }
    public void setPartitionKey(String v) { this.partitionKey = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
