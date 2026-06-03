package com.paymentapi.financialcore.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id @GeneratedValue
    private UUID id;

    @Column(unique = true, nullable = false, length = 64)
    private String externalRef;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;  // CACHED PROJECTION

    @Version
    private Long version = 0L;

    private Instant createdAt = Instant.now();

    public enum AccountType {
        CUSTOMER_WALLET, MERCHANT_PAYABLE, PLATFORM_FEE_REVENUE, SETTLEMENT_ACCOUNT
    }

    // ─── Getters/Setters ───
    public UUID getId() { return id; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String v) { this.externalRef = v; }
    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType v) { this.accountType = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal v) { this.balance = v; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
