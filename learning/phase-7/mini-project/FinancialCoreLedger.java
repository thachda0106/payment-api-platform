// Mini Project: Financial Core Ledger Service (runnable without Spring)
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// ═══════════════════════════════════════════════════════════════════════
// Domain Model
// ═══════════════════════════════════════════════════════════════════════
record JournalEntry(UUID id, String referenceType, UUID referenceId, String description,
                    String idempotencyKey, Instant createdAt, String createdBy) {}
record JournalLine(UUID id, UUID entryId, String accountId, String entryType, long amount, String currency) {}

// ═══════════════════════════════════════════════════════════════════════
// Repository (in-memory — in real project these are JPA repositories)
// ═══════════════════════════════════════════════════════════════════════
class InMemoryRepository<T> {
    protected final Map<UUID, T> store = new ConcurrentHashMap<>();
    public void save(UUID id, T entity) { store.put(id, entity); }
    public Optional<T> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
    public List<T> findAll() { return List.copyOf(store.values()); }
}

// ═══════════════════════════════════════════════════════════════════════
// Ledger Service (the core — @Transactional behavior simulated)
// ═══════════════════════════════════════════════════════════════════════
class LedgerService {
    private final InMemoryRepository<JournalEntry> entryRepo = new InMemoryRepository<>();
    private final InMemoryRepository<JournalLine> lineRepo = new InMemoryRepository<>();
    private final Map<String, Long> walletBalances = new ConcurrentHashMap<>();
    private final Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();
    private final AtomicInteger txCount = new AtomicInteger();

    record CreateEntryCmd(String referenceType, UUID referenceId, String description,
                          String idempotencyKey, String debitAccount, String creditAccount,
                          long amount, String currency) {}

    record EntryResult(UUID entryId, String status, String message) {}

    // @Transactional(isolation = SERIALIZABLE) — simulated
    public EntryResult createJournalEntry(CreateEntryCmd cmd) {
        // Idempotency check — UNIQUE constraint
        if (!idempotencyKeys.add(cmd.idempotencyKey)) {
            var existing = entryRepo.store.values().stream()
                .filter(e -> e.idempotencyKey().equals(cmd.idempotencyKey)).findFirst();
            return existing.map(e -> new EntryResult(e.id(), "DUPLICATE", "Idempotent replay"))
                .orElse(new EntryResult(null, "ERROR", "Idempotency key conflict"));
        }

        // Balance check — SELECT FOR UPDATE
        long debitBalance = walletBalances.getOrDefault(cmd.debitAccount, 0L);
        if (debitBalance < cmd.amount) {
            idempotencyKeys.remove(cmd.idempotencyKey); // Rollback
            return new EntryResult(null, "FAILED", "Insufficient balance: " + debitBalance);
        }

        // BEGIN TX (simulated)
        int txId = txCount.incrementAndGet();
        try {
            // 1. Create journal entry
            UUID entryId = UUID.randomUUID();
            JournalEntry entry = new JournalEntry(entryId, cmd.referenceType, cmd.referenceId,
                cmd.description, cmd.idempotencyKey, Instant.now(), "system");
            entryRepo.save(entryId, entry);

            // 2. Create journal lines
            JournalLine debit = new JournalLine(UUID.randomUUID(), entryId, cmd.debitAccount, "DEBIT", cmd.amount, cmd.currency);
            JournalLine credit = new JournalLine(UUID.randomUUID(), entryId, cmd.creditAccount, "CREDIT", cmd.amount, cmd.currency);
            lineRepo.save(debit.id(), debit);
            lineRepo.save(credit.id(), credit);

            // 3. Update wallet balances (in same transaction)
            walletBalances.merge(cmd.debitAccount, -cmd.amount, Long::sum);
            walletBalances.merge(cmd.creditAccount, cmd.amount, Long::sum);

            // COMMIT TX
            System.out.printf("  [TX-%d] COMMIT: %s DEBIT %s %.0f %s → CREDIT %s%n",
                txId, entryId.toString().substring(0,8), cmd.debitAccount, (double)cmd.amount, cmd.currency, cmd.creditAccount);
            return new EntryResult(entryId, "COMPLETED", "Journal entry created");
        } catch (Exception e) {
            // ROLLBACK TX — remove any partial inserts
            entryRepo.store.values().removeIf(e -> e.idempotencyKey().equals(cmd.idempotencyKey));
            lineRepo.store.values().removeIf(l -> entryRepo.findById(l.entryId()).isEmpty());
            idempotencyKeys.remove(cmd.idempotencyKey);
            System.out.printf("  [TX-%d] ROLLBACK: %s%n", txId, e.getMessage());
            return new EntryResult(null, "FAILED", e.getMessage());
        }
    }

    // Verify double-entry (SUM(DEBIT) == SUM(CREDIT))
    public boolean verifyDoubleEntry(UUID entryId) {
        long debitSum = lineRepo.findAll().stream()
            .filter(l -> l.entryId().equals(entryId) && l.entryType().equals("DEBIT"))
            .mapToLong(JournalLine::amount).sum();
        long creditSum = lineRepo.findAll().stream()
            .filter(l -> l.entryId().equals(entryId) && l.entryType().equals("CREDIT"))
            .mapToLong(JournalLine::amount).sum();
        return debitSum == creditSum;
    }

    long balance(String accountId) { return walletBalances.getOrDefault(accountId, 0L); }
    void deposit(String accountId, long amount) { walletBalances.put(accountId, amount); }
}

// ═══════════════════════════════════════════════════════════════════════
// Demo
// ═══════════════════════════════════════════════════════════════════════
public class FinancialCoreLedger {
    public static void main(String[] args) {
        System.out.println("=== Financial Core Ledger Service ===\n");
        LedgerService ledger = new LedgerService();

        // Seed balances
        ledger.deposit("liability:user_wallet:U1", 500000);
        ledger.deposit("liability:user_wallet:U2", 300000);

        // Test 1: Successful payment
        System.out.println("Test 1: Payment U1 → U2 (100,000 VND)");
        var r1 = ledger.createJournalEntry(new LedgerService.CreateEntryCmd(
            "PAYMENT", UUID.randomUUID(), "P2P Transfer", "idem-001",
            "liability:user_wallet:U1", "liability:user_wallet:U2", 100000, "VND"));
        System.out.printf("  Result: %s — %s%n", r1.status(), r1.message());
        assert r1.status().equals("COMPLETED");
        assert ledger.balance("liability:user_wallet:U1") == 400000;
        assert ledger.balance("liability:user_wallet:U2") == 400000;
        System.out.println("  PASS\n");

        // Test 2: Idempotency — duplicate request returns original result
        System.out.println("Test 2: Idempotent replay (same idempotency key)");
        var r2 = ledger.createJournalEntry(new LedgerService.CreateEntryCmd(
            "PAYMENT", UUID.randomUUID(), "P2P Transfer", "idem-001",
            "liability:user_wallet:U1", "liability:user_wallet:U2", 100000, "VND"));
        assert r2.status().equals("DUPLICATE");
        assert ledger.balance("liability:user_wallet:U1") == 400000; // Unchanged
        System.out.printf("  Result: %s — %s%n", r2.status(), r2.message());
        System.out.println("  PASS\n");

        // Test 3: Insufficient balance
        System.out.println("Test 3: Insufficient balance");
        var r3 = ledger.createJournalEntry(new LedgerService.CreateEntryCmd(
            "PAYMENT", UUID.randomUUID(), "P2P Transfer", "idem-003",
            "liability:user_wallet:U1", "liability:user_wallet:U2", 1000000, "VND"));
        assert r3.status().equals("FAILED");
        assert ledger.balance("liability:user_wallet:U1") == 400000; // Unchanged
        System.out.printf("  Result: %s — %s%n", r3.status(), r3.message());
        System.out.println("  PASS\n");

        // Test 4: Double-entry verification
        System.out.println("Test 4: Double-entry verification");
        assert ledger.verifyDoubleEntry(r1.entryId());
        System.out.println("  PASS — DEBIT == CREDIT\n");

        System.out.println("All acceptance tests passed!");
    }

    static void assert(boolean condition) { if (!condition) throw new AssertionError(); }
}
