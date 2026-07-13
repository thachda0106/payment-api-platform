package com.paymentapi.financialcore.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "journal_entries")
public class JournalEntry {
    @Id @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID ledgerTransactionId;

    @Column(nullable = false)
    private UUID paymentId;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 10)
    private String entryType;  // DEBIT or CREDIT

    @Column(nullable = false)
    private long amount;         // minor currency units (cents)

    @Column(nullable = false)
    private long balanceBefore;

    @Column(nullable = false)
    private long balanceAfter;

    @Column(length = 255)
    private String description;

    private Instant createdAt = Instant.now();

    // ─── Getters/Setters ───
    public UUID getId() { return id; }
    public UUID getLedgerTransactionId() { return ledgerTransactionId; }
    public void setLedgerTransactionId(UUID v) { this.ledgerTransactionId = v; }
    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID v) { this.paymentId = v; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { this.accountId = v; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String v) { this.entryType = v; }
    public long getAmount() { return amount; }
    public void setAmount(long v) { this.amount = v; }
    public long getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(long v) { this.balanceBefore = v; }
    public long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(long v) { this.balanceAfter = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Instant getCreatedAt() { return createdAt; }
}
