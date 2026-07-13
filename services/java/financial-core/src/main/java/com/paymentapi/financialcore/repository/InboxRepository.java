package com.paymentapi.financialcore.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Inbox pattern store (Phase-9 P2). Dedup + retry state for consumed events.
 * `claim` inserts a PENDING row (offset is committed by the listener regardless);
 * business processing + `markCompleted` happen in one transaction; failures are
 * retried by InboxRetryScheduler with exponential backoff, then routed to the DLQ.
 */
@Repository
public class InboxRepository {
    public enum Claim { PROCEED, SKIP }

    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Insert PENDING with payload (or observe existing). Returns SKIP only if already COMPLETED. */
    public Claim claim(String eventId, String group, String payloadJson) {
        jdbc.update(
            """
            INSERT INTO consumer_inbox (event_id, consumer_group, status, payload)
            VALUES (?::uuid, ?, 'PENDING', ?::jsonb)
            ON CONFLICT (event_id, consumer_group) DO NOTHING
            """,
            eventId, group, payloadJson);
        String status = jdbc.queryForObject(
            "SELECT status FROM consumer_inbox WHERE event_id = ?::uuid AND consumer_group = ?",
            String.class, eventId, group);
        return "COMPLETED".equals(status) ? Claim.SKIP : Claim.PROCEED;
    }

    public String payloadOf(String eventId, String group) {
        return jdbc.queryForObject(
            "SELECT payload::text FROM consumer_inbox WHERE event_id=?::uuid AND consumer_group=?",
            String.class, eventId, group);
    }

    public void markCompleted(String eventId, String group) {
        jdbc.update(
            "UPDATE consumer_inbox SET status='COMPLETED', updated_at=now() WHERE event_id=?::uuid AND consumer_group=?",
            eventId, group);
    }

    public void markFailed(String eventId, String group, String error) {
        jdbc.update(
            "UPDATE consumer_inbox SET status='FAILED', last_error=?, updated_at=now() WHERE event_id=?::uuid AND consumer_group=?",
            truncate(error), eventId, group);
    }

    public void incrementRetry(String eventId, String group, String error) {
        jdbc.update(
            "UPDATE consumer_inbox SET retry_count=retry_count+1, last_error=?, updated_at=now() WHERE event_id=?::uuid AND consumer_group=?",
            truncate(error), eventId, group);
    }

    /** FAILED rows eligible for retry: retry_count < max and backoff elapsed (2^retry seconds). */
    public List<String> findRetryable(String group, int maxRetries, int limit) {
        return jdbc.queryForList(
            """
            SELECT event_id::text FROM consumer_inbox
            WHERE consumer_group = ? AND status = 'FAILED' AND retry_count < ?
              AND updated_at < now() - (power(2, retry_count) * interval '1 second')
            ORDER BY updated_at
            LIMIT ?
            """,
            String.class, group, maxRetries, limit);
    }

    /** FAILED rows that exhausted retries and must be routed to the DLQ. */
    public List<String> findExhausted(String group, int maxRetries, int limit) {
        return jdbc.queryForList(
            """
            SELECT event_id::text FROM consumer_inbox
            WHERE consumer_group = ? AND status = 'FAILED' AND retry_count >= ?
            ORDER BY updated_at
            LIMIT ?
            """,
            String.class, group, maxRetries, limit);
    }

    /** Terminal state after DLQ routing (won't be retried or re-routed). */
    public void markDlqRouted(String eventId, String group) {
        jdbc.update(
            "UPDATE consumer_inbox SET status='DLQ', updated_at=now() WHERE event_id=?::uuid AND consumer_group=?",
            eventId, group);
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 1000 ? s.substring(0, 1000) : s);
    }
}
