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
import java.util.UUID;

/**
 * Double-entry ledger service.
 * Each payment generates 3 journal entries linked by ledger_transaction_id.
 *
 * Invariant: SUM(credit) - SUM(debit) = 0 for each ledger_transaction_id.
 *
 * Example for $100 payment with 3% fee:
 *   Customer Wallet      -$100.00   (DEBIT)
 *   Merchant Payable     +$97.00    (CREDIT)
 *   Platform Fee Revenue +$3.00     (CREDIT)
 *   Total: -100 + 97 + 3 = 0 ✓
 */
@Service
public class LedgerService {
    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final BigDecimal FEE_RATE = new BigDecimal("0.03");
    private static final int SCALE = 4;

    private final AccountRepository accountRepo;
    private final JournalEntryRepository journalEntryRepo;

    public LedgerService(AccountRepository accountRepo, JournalEntryRepository journalEntryRepo) {
        this.accountRepo = accountRepo;
        this.journalEntryRepo = journalEntryRepo;
    }

    @Transactional
    public UUID postPayment(UUID paymentId, String customerId, String merchantId, BigDecimal amount) {
        BigDecimal fee = amount.multiply(FEE_RATE).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal merchantAmount = amount.subtract(fee);

        UUID ledgerTxnId = UUID.randomUUID();

        // Get or create accounts
        Account customerWallet = getOrCreateAccount(customerId, AccountType.CUSTOMER_WALLET);
        Account merchantPayable = getOrCreateAccount(merchantId, AccountType.MERCHANT_PAYABLE);
        Account platformFee = getOrCreateAccount("PLATFORM", AccountType.PLATFORM_FEE_REVENUE);

        // Double-entry: debit customer, credit merchant, credit platform
        // ─────────────────────────────────────────────────────────────
        debit(customerWallet, amount, paymentId, ledgerTxnId,
            "Payment to " + merchantId);
        credit(merchantPayable, merchantAmount, paymentId, ledgerTxnId,
            "Payment from " + customerId);
        credit(platformFee, fee, paymentId, ledgerTxnId,
            "Processing fee for payment " + paymentId);

        log.info("Ledger posted: txn={} payment={} amount={} fee={}",
            ledgerTxnId, paymentId, amount, fee);

        return ledgerTxnId;
    }

    private void debit(Account account, BigDecimal amount, UUID paymentId,
                       UUID ledgerTxnId, String desc) {
        BigDecimal before = account.getBalance();
        BigDecimal after = before.subtract(amount);

        account.setBalance(after);
        accountRepo.save(account);

        JournalEntry entry = new JournalEntry();
        entry.setLedgerTransactionId(ledgerTxnId);
        entry.setPaymentId(paymentId);
        entry.setAccountId(account.getId());
        entry.setEntryType("DEBIT");
        entry.setAmount(amount);
        entry.setBalanceBefore(before);
        entry.setBalanceAfter(after);
        entry.setDescription(desc);
        journalEntryRepo.save(entry);
    }

    private void credit(Account account, BigDecimal amount, UUID paymentId,
                        UUID ledgerTxnId, String desc) {
        BigDecimal before = account.getBalance();
        BigDecimal after = before.add(amount);

        account.setBalance(after);
        accountRepo.save(account);

        JournalEntry entry = new JournalEntry();
        entry.setLedgerTransactionId(ledgerTxnId);
        entry.setPaymentId(paymentId);
        entry.setAccountId(account.getId());
        entry.setEntryType("CREDIT");
        entry.setAmount(amount);
        entry.setBalanceBefore(before);
        entry.setBalanceAfter(after);
        entry.setDescription(desc);
        journalEntryRepo.save(entry);
    }

    private Account getOrCreateAccount(String externalRef, AccountType type) {
        return accountRepo.findByExternalRefAndAccountType(externalRef, type)
            .orElseGet(() -> {
                Account a = new Account();
                a.setExternalRef(externalRef);
                a.setAccountType(type);
                a.setBalance(BigDecimal.ZERO);
                return accountRepo.save(a);
            });
    }
}
