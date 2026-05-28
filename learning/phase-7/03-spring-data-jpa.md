# Module 03 — Spring Data JPA & Transactions

## 3.1 Repository Pattern

```java
@Entity
@Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false) private String sourceAccountId;
    @Column(nullable = false) private String destinationAccountId;
    @Column(nullable = false) private Long amount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) private PaymentStatus status;
    @Version private Long version;
    @Column(updatable = false) private Instant createdAt;
}

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // Derived query method
    List<Payment> findBySourceAccountIdAndStatus(String accountId, PaymentStatus status);

    // JPQL with named parameters
    @Query("SELECT p FROM Payment p WHERE p.createdAt >= :since AND p.status = :status")
    List<Payment> findCompletedSince(@Param("since") Instant since, @Param("status") PaymentStatus status);

    // Native query
    @Query(value = "SELECT SUM(amount) FROM payments WHERE merchant_id = :merchantId AND created_at >= :since",
           nativeQuery = true)
    Optional<Long> totalAmountSince(@Param("merchantId") UUID merchantId, @Param("since") Instant since);

    // Locking
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
}
```

## 3.2 Entity Relationships & N+1 Problem

```java
@Entity
public class JournalEntry {
    @Id private UUID id;

    @OneToMany(mappedBy = "entry", fetch = FetchType.LAZY) // LAZY = don't load unless accessed
    private List<JournalLine> lines;
}

// N+1 PROBLEM: 1 query for entries + N queries for lines (one per entry)
List<JournalEntry> entries = repo.findAll();  // 1 query for entries
for (JournalEntry e : entries) {
    e.getLines().size();  // N additional queries for lines! N+1 = 1001 queries!
}

// FIX 1: @EntityGraph — eager-load specified associations
@EntityGraph(attributePaths = {"lines"})
@Query("SELECT e FROM JournalEntry e WHERE e.createdAt >= :since")
List<JournalEntry> findWithLinesSince(@Param("since") Instant since);

// FIX 2: JOIN FETCH in JPQL
@Query("SELECT e FROM JournalEntry e JOIN FETCH e.lines WHERE e.createdAt >= :since")
List<JournalEntry> findWithLinesSinceJPQL(@Param("since") Instant since);
```

## 3.3 Pessimistic vs Optimistic Locking

### Pessimistic Locking (SELECT FOR UPDATE)

```java
@Transactional
public void debitWallet(String accountId, long amount) {
    WalletBalance balance = walletRepo.findByIdForUpdate(accountId);  // SELECT ... FOR UPDATE
    if (balance.getAvailable() < amount) throw new InsufficientBalanceException();
    balance.setAvailable(balance.getAvailable() - amount);
    walletRepo.save(balance);
    // Lock released on transaction commit
}
```

### Optimistic Locking (@Version)

```java
@Entity
public class WalletBalance {
    @Version private Long version;  // Incremented on every update
}

@Transactional
public PaymentResult processPayment(PaymentRequest req) {
    WalletBalance balance = walletRepo.findById(req.sourceAccountId());
    if (balance.getAvailable() < req.amount()) throw new InsufficientBalanceException();
    balance.setAvailable(balance.getAvailable() - req.amount());
    // walletRepo.save(balance) → UPDATE ... WHERE version = ? — if version changed, throws OptimisticLockException
    return createPayment(req);
}
```

## 3.4 @Transactional Deep Dive

### Propagation

```java
@Transactional(propagation = Propagation.REQUIRED)        // DEFAULT: join existing, or create new
@Transactional(propagation = Propagation.REQUIRES_NEW)    // ALWAYS create new (suspend existing)
@Transactional(propagation = Propagation.MANDATORY)       // MUST have existing transaction (else error)
@Transactional(propagation = Propagation.NESTED)          // Nested transaction (savepoint — JDBC only)
```

**REQUIRES_NEW trap**: Creates a NEW physical transaction. The outer transaction's locks are ignored by the inner. This can cause "lock not held" surprises and deadlocks.

### Isolation

```java
@Transactional(isolation = Isolation.READ_COMMITTED)     // Default in PostgreSQL
@Transactional(isolation = Isolation.REPEATABLE_READ)    // Snapshot at first query
@Transactional(isolation = Isolation.SERIALIZABLE)       // Strongest — for ledger writes
```

### Rollback Rules

```java
@Transactional(rollbackFor = Exception.class)  // Rollback on ANY exception (not just RuntimeException)
@Transactional(noRollbackFor = {ValidationException.class})  // Don't rollback on validation errors
```

**Default**: Only rolls back on RuntimeException and Error. Checked exceptions do NOT trigger rollback (surprising!).

### Ledger Transaction Pattern

```java
@Service
public class LedgerService {

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public JournalEntry createJournalEntry(CreateEntryCommand cmd) {
        // 1. Insert journal entry header
        JournalEntry entry = journalEntryRepo.save(cmd.toEntity());

        // 2. Insert journal lines
        List<JournalLine> lines = cmd.toLines(entry.getId());
        journalLineRepo.saveAll(lines);

        // 3. Update wallet balances (pessimistic lock already acquired via SELECT FOR UPDATE)
        walletBalanceRepo.debit(cmd.debitAccountId(), cmd.amount());
        walletBalanceRepo.credit(cmd.creditAccountId(), cmd.amount());

        // 4. Write outbox event in SAME transaction
        OutboxEvent event = new OutboxEvent("JournalEntryCreated", entry.toPayload());
        outboxRepo.save(event);

        return entry;
    }
    // If any step fails → entire transaction rolls back → no partial state
}
```

## 3.5 Exercises

### Ex 3.1 — N+1 Detection
Write a query that triggers N+1. Use `@EntityGraph` or `JOIN FETCH` to fix. Enable SQL logging (`spring.jpa.show-sql=true`) to count queries before and after.

### Ex 3.2 — Transaction Experiment
Write a service method with `REQUIRES_NEW`. Call it from another transaction. Observe: inner commits even if outer rolls back. Document the behavior.

### Ex 3.3 — Optimistic Lock Retry
Implement a retry loop for `OptimisticLockException`. Use `@Retryable` (Spring Retry) or manual loop. Test with concurrent updates.

---

## 3.6 Self-Assessment

- [ ] Can explain the N+1 problem and fix it with EntityGraph/JOIN FETCH
- [ ] Understand the difference between pessimistic (row lock) and optimistic (@Version) locking
- [ ] Know when to use SERIALIZABLE vs REPEATABLE READ isolation
- [ ] Can explain REQUIRES_NEW propagation and its dangers
- [ ] Know that @Transactional only rolls back on RuntimeException by default
- [ ] Can design a ledger transaction that is atomic across multiple tables
