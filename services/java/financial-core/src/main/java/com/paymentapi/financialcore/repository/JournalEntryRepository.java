package com.paymentapi.financialcore.repository;

import com.paymentapi.financialcore.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    boolean existsByPaymentId(UUID paymentId);
    Optional<JournalEntry> findFirstByPaymentId(UUID paymentId);
}
