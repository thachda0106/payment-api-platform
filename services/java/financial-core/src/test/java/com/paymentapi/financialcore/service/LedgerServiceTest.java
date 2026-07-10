package com.paymentapi.financialcore.service;

import com.paymentapi.financialcore.entity.Account;
import com.paymentapi.financialcore.entity.JournalEntry;
import com.paymentapi.financialcore.repository.AccountRepository;
import com.paymentapi.financialcore.repository.JournalEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock AccountRepository accountRepo;
    @Mock JournalEntryRepository journalEntryRepo;

    private LedgerService service;

    @BeforeEach
    void setUp() {
        service = new LedgerService(accountRepo, journalEntryRepo);
    }

    private Account account(String id, Account.AccountType type) {
        Account a = new Account();
        a.setExternalRef(id);
        a.setAccountType(type);
        a.setBalance(BigDecimal.ZERO);
        return a;
    }

    @Test
    void idempotentWhenPaymentAlreadyPosted() {
        UUID paymentId = UUID.randomUUID();
        when(journalEntryRepo.existsByPaymentId(paymentId)).thenReturn(true);

        Optional<UUID> result = service.postPayment(
            paymentId, "c1", "m1", new BigDecimal("100.00"));

        assertThat(result).isEmpty();
        verify(journalEntryRepo, never()).save(any());
    }

    @Test
    void postsNewLedgerTransaction() {
        UUID paymentId = UUID.randomUUID();
        when(journalEntryRepo.existsByPaymentId(paymentId)).thenReturn(false);

        Account customer = account("c1", Account.AccountType.CUSTOMER_WALLET);
        Account merchant = account("m1", Account.AccountType.MERCHANT_PAYABLE);
        Account platform = account("PLATFORM", Account.AccountType.PLATFORM_FEE_REVENUE);

        when(accountRepo.findByExternalRefAndAccountType("c1", Account.AccountType.CUSTOMER_WALLET))
            .thenReturn(Optional.of(customer));
        when(accountRepo.findByExternalRefAndAccountType("m1", Account.AccountType.MERCHANT_PAYABLE))
            .thenReturn(Optional.of(merchant));
        when(accountRepo.findByExternalRefAndAccountType("PLATFORM", Account.AccountType.PLATFORM_FEE_REVENUE))
            .thenReturn(Optional.of(platform));

        Optional<UUID> result = service.postPayment(
            paymentId, "c1", "m1", new BigDecimal("100.00"));

        assertThat(result).isPresent();
        verify(accountRepo, times(3)).save(any(Account.class));
        verify(journalEntryRepo, times(3)).save(any(JournalEntry.class));
    }
}
