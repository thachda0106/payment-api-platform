package com.paymentapi.financialcore.service;

import com.paymentapi.financialcore.entity.Account;
import com.paymentapi.financialcore.entity.Account.AccountType;
import com.paymentapi.financialcore.entity.JournalEntry;
import com.paymentapi.financialcore.repository.AccountRepository;
import com.paymentapi.financialcore.repository.JournalEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

/**
 * Double-entry ledger service. All amounts are in minor currency units (cents).
 *
 * Invariant: SUM(credit) - SUM(debit) = 0 for each ledger_transaction_id.
 *
 * Example for a 10000-cent ($100.00) payment with a 3% fee (300 cents):
 *   Customer Wallet      -10000   (DEBIT)
 *   Merchant Payable     + 9700   (CREDIT)
 *   Platform Fee Revenue +  300   (CREDIT)
 *   Total: -10000 + 9700 + 300 = 0 ✓
 */
@Service
public class LedgerService {
    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final BigDecimal FEE_RATE = new BigDecimal("0.03");

    private final AccountRepository accountRepo;
    private final JournalEntryRepository journalEntryRepo;

    public LedgerService(AccountRepository accountRepo, JournalEntryRepository journalEntryRepo) {
        this.accountRepo = accountRepo;
        this.journalEntryRepo = journalEntryRepo;
    }

    @Transactional
    public Optional<UUID> postPayment(UUID paymentId, String customerId, String merchantId, long amount) {
        if (journalEntryRepo.existsByPaymentId(paymentId)) {
            log.info("Payment {} already posted to ledger — idempotent no-op", paymentId);
            return journalEntryRepo.findFirstByPaymentId(paymentId)
                .map(JournalEntry::getLedgerTransactionId);
        }

        long fee = BigDecimal.valueOf(amount).multiply(FEE_RATE)
            .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long merchantAmount = amount - fee;

        UUID ledgerTxnId = UUID.randomUUID();

        Account customerWallet = getOrCreateAccount(customerId, AccountType.CUSTOMER_WALLET);
        Account merchantPayable = getOrCreateAccount(merchantId, AccountType.MERCHANT_PAYABLE);
        Account platformFee = getOrCreateAccount("PLATFORM", AccountType.PLATFORM_FEE_REVENUE);

        // Double-entry: debit customer, credit merchant, credit platform
        debit(customerWallet, amount, paymentId, ledgerTxnId, "Payment to " + merchantId);
        credit(merchantPayable, merchantAmount, paymentId, ledgerTxnId, "Payment from " + customerId);
        credit(platformFee, fee, paymentId, ledgerTxnId, "Processing fee for payment " + paymentId);

        log.info("Ledger posted: txn={} payment={} amount={} fee={}",
            ledgerTxnId, paymentId, amount, fee);

        return Optional.of(ledgerTxnId);
    }

    private void debit(Account account, long amount, UUID paymentId, UUID ledgerTxnId, String desc) {
        long before = account.getBalance();
        long after = before - amount;
        account.setBalance(after);
        accountRepo.save(account);
        journalEntryRepo.save(entry(account, "DEBIT", amount, before, after, paymentId, ledgerTxnId, desc));
    }

    private void credit(Account account, long amount, UUID paymentId, UUID ledgerTxnId, String desc) {
        long before = account.getBalance();
        long after = before + amount;
        account.setBalance(after);
        accountRepo.save(account);
        journalEntryRepo.save(entry(account, "CREDIT", amount, before, after, paymentId, ledgerTxnId, desc));
    }

    private JournalEntry entry(Account account, String type, long amount, long before, long after,
                               UUID paymentId, UUID ledgerTxnId, String desc) {
        JournalEntry e = new JournalEntry();
        e.setLedgerTransactionId(ledgerTxnId);
        e.setPaymentId(paymentId);
        e.setAccountId(account.getId());
        e.setEntryType(type);
        e.setAmount(amount);
        e.setBalanceBefore(before);
        e.setBalanceAfter(after);
        e.setDescription(desc);
        return e;
    }

    private Account getOrCreateAccount(String externalRef, AccountType type) {
        return accountRepo.findByExternalRefAndAccountType(externalRef, type)
            .orElseGet(() -> {
                Account a = new Account();
                a.setExternalRef(externalRef);
                a.setAccountType(type);
                a.setBalance(0L);
                return accountRepo.save(a);
            });
    }
}
