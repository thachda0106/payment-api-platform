package com.paymentapi.financialcore.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {
    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Returns true if event was successfully marked (first time). */
    public boolean markAsProcessed(String eventId, String consumerGroup) {
        int rows = jdbc.update(
            """INSERT INTO processed_events (event_id, consumer_group, processed_at)
               VALUES (?, ?, now())
               ON CONFLICT (event_id, consumer_group) DO NOTHING""",
            eventId, consumerGroup
        );
        return rows > 0;  // true = first time, false = duplicate
    }
}
