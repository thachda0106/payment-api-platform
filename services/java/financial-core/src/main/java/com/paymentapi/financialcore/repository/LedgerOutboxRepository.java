package com.paymentapi.financialcore.repository;

import com.paymentapi.financialcore.entity.LedgerOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerOutboxRepository extends JpaRepository<LedgerOutboxEvent, UUID> {
    @Query(value = """
        SELECT * FROM ledger_outbox
        WHERE published_at IS NULL
        ORDER BY created_at, id
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
    """, nativeQuery = true)
    List<LedgerOutboxEvent> findUnpublished(@Param("limit") int limit);

    long countByPublishedAtIsNull();

    @Modifying
    @Transactional
    @Query("UPDATE LedgerOutboxEvent e SET e.publishedAt = :now WHERE e.id IN :ids")
    void markPublished(@Param("ids") List<UUID> ids, @Param("now") Instant now);
}
