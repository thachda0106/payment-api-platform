# Session 20: Repository, Transactions & Persistence Internals

## 1. Why This Topic Exists

Most Spring Boot applications are databases with an HTTP frontend. The repository and transaction layers are where correctness is guaranteed or lost. A missing `@Transactional` causes half-written rows. A `REQUIRES_NEW` in the wrong place silently drops data. An N+1 query at 10K requests/minute melts the database. A connection leak from a forgotten `@Transactional` on a streaming endpoint exhausts the pool in 5 minutes under load.

Spring Data JPA and `@Transactional` make persistence "just work" — until they don't. The abstraction is so effective that developers can build production systems without understanding the proxy that generates their queries, the connection pool that manages their database connections, or the transaction manager that coordinates their commits. When the abstraction breaks, the error messages — "No EntityManager with actual transaction available," "Transaction marked as rollback-only," "Connection is not available, request timed out after 30000ms" — are opaque to anyone who hasn't read the source code.

**Staff engineer insight**: Persistence is the hardest layer to get right because the abstractions span three subsystems (Spring proxy, JPA/Hibernate, JDBC/database) that each have their own lifecycle, their own thread model, and their own failure modes. Understanding how these layers compose — how a `JpaRepository` method call triggers a proxy that gets a connection from HikariCP that starts a transaction through JpaTransactionManager that binds to the Hibernate Session that synchronizes with the JDBC Connection — is the difference between debugging a deadlock in 5 minutes and 5 hours.

## 2. Mental Model

```
The Persistence Stack:

  @Service (Application Layer)
      | calls orderRepo.findById(42L)
      v
  +---------------------------------------------------------------+
  | JdkDynamicAopProxy / CGLIB Proxy (Spring Data)                |
  |                                                               |
  |  Intercept method call -> QueryExecutorMethodInterceptor      |
  |  Determine query strategy: PartTree vs @Query vs named query  |
  |  Invoke actual query execution                                |
  +-----------------------------------+---------------------------+
                                      |
                                      v
  +---------------------------------------------------------------+
  | JpaTransactionManager (Spring)                                 |
  |                                                               |
  |  If @Transactional: bind EntityManager to current thread       |
  |  Get JDBC Connection from HikariCP                            |
  |  Begin database transaction                                   |
  |  On success: flush, commit                                    |
  |  On rollback: mark rollback-only                              |
  +-----------------------------------+---------------------------+
                                      |
                                      v
  +---------------------------------------------------------------+
  | Hibernate Session / EntityManager                             |
  |                                                               |
  |  PersistenceContext (Level 1 cache)                           |
  |  Dirty checking: detect entity state changes                  |
  |  Flush: synchronize PersistenceContext -> JDBC statements     |
  |  Query translation: HQL/JPQL -> SQL                           |
  +-----------------------------------+---------------------------+
                                      |
                                      v
  +---------------------------------------------------------------+
  | HikariCP (Connection Pool)                                     |
  |                                                               |
  |  Pool of JDBC Connections (default 10)                        |
  |  Borrow: get connection from pool (with timeout)              |
  |  Return: release back to pool after transaction commit        |
  |  Leak detection: connections borrowed too long                |
  +-----------------------------------+---------------------------+
                                      |
                                      v
  +---------------------------------------------------------------+
  | JDBC Driver -> Database (PostgreSQL / MySQL / Oracle)         |
  +---------------------------------------------------------------+
```

```
Key data structures and thread binding:

  TransactionSynchronizationManager (ThreadLocal):
  ┌─────────────────────────────────────────────────────────────┐
  │ resources: Map<Object, Object>                              │
  │   -> DataSource key -> ConnectionHolder (JDBC connection)   │
  │   -> EntityManagerFactory key -> EntityManagerHolder         │
  │                                                             │
  │ synchronizations: Set<TransactionSynchronization>           │
  │   -> afterCommit callbacks, afterRollback callbacks          │
  │   -> @TransactionalEventListener hooks                      │
  │                                                             │
  │ currentTransactionName: String                              │
  │   -> "com.example.OrderService.placeOrder"                   │
  │                                                             │
  │ currentTransactionReadOnly: Boolean                          │
  │   -> from @Transactional(readOnly=true)                      │
  │                                                             │
  │ currentTransactionIsolationLevel: Integer                    │
  │   -> from @Transactional(isolation=READ_COMMITTED)           │
  │                                                             │
  │ actualTransactionActive: Boolean                             │
  │   -> true when a real database transaction is active         │
  └─────────────────────────────────────────────────────────────┘
```

## 3. Internal Architecture

### How JpaRepository Generates Queries at Runtime

```java
// When you write: public interface OrderRepository extends JpaRepository<Order, Long> {}
// Spring Data creates a PROXY that intercepts every method call.

// Source: org.springframework.data.jpa.repository.support.JpaRepositoryFactory

public class JpaRepositoryFactory extends RepositoryFactorySupport {
    
    @Override
    protected Object getTargetRepository(RepositoryInformation information) {
        JpaEntityInformation<?, ?> entityInformation = 
                getEntityInformation(information.getDomainType());
        return getTargetRepositoryViaReflection(information, 
                entityInformation, entityManager);
    }
    
    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        // The base implementation class:
        return SimpleJpaRepository.class;
        // SimpleJpaRepository provides the CRUD implementations:
        //   findAll(), findById(), save(), delete(), etc.
    }
}

// Source: org.springframework.data.repository.core.support.RepositoryFactorySupport

public <T> T getRepository(Class<T> repositoryInterface, 
        RepositoryFragments fragments) {
    
    // Step 1: Create the proxy
    RepositoryProxyFactory proxyFactory = new RepositoryProxyFactory();
    
    // Step 2: Add the default method interceptor (for CRUD methods)
    proxyFactory.addAdvice(new DefaultMethodInterceptor());
    
    // Step 3: Add the query executor interceptor (for custom query methods)
    proxyFactory.addAdvice(new QueryExecutorMethodInterceptor(
            queryMethods, customImplementations, target));
    
    // Step 4: Add custom implementation fragments
    for (RepositoryFragment<?> fragment : fragments) {
        proxyFactory.addAdvice(fragment.getImplementation());
    }
    
    return proxyFactory.getProxy(repositoryInterface);
}

// Source: org.springframework.data.jpa.repository.query.JpaQueryLookupStrategy

// How a method name like "findByCustomerIdAndStatusOrderByCreatedAtDesc"
// becomes a JPA query:

class PartTreeJpaQuery extends AbstractJpaQuery {
    
    public PartTreeJpaQuery(JpaQueryMethod method, EntityManager em) {
        // Parses: findByCustomerIdAndStatusOrderByCreatedAtDesc
        PartTree tree = new PartTree(
                method.getName(),   // "findByCustomerIdAndStatusOrderByCreatedAtDesc"
                method.getEntityInformation().getJavaType());
        
        // PartTree produces a tree structure:
        //   Subject: findBy
        //   Predicate: 
        //     And(
        //       Property("customerId"),
        //       Property("status")
        //     )
        //   OrderBy:
        //     Desc(Property("createdAt"))
        
        // This tree is then translated to:
        //   JPQL: SELECT o FROM Order o 
        //         WHERE o.customerId = :customerId 
        //         AND o.status = :status 
        //         ORDER BY o.createdAt DESC
    }
}

// Source: org.springframework.data.repository.query.parser.PartTree

public class PartTree implements Iterable<OrPart> {
    
    private final Subject subject;      // "find", "count", "delete", "exists"
    private final List<OrPart> nodes;    // Predicates connected by OR
    private final OrderBySource orderBy; // ORDER BY clause
    
    public PartTree(String source, Class<?> domainClass) {
        // 1. Extract subject: "find" | "count" | "delete" | "exists"
        // 2. Extract predicate: strip subject, split by "And"/"Or"
        // 3. Parse each predicate part: PropertyName, Operator, IgnoreCase
        //    "findByCustomerNameIgnoreCaseAndAgeGreaterThan"
        //    -> Property("customerName"), IgnoreCase(true)
        //    -> And
        //    -> Property("age"), GreaterThan
        // 4. Extract OrderBy: "findByStatusOrderByCreatedAtDesc" 
        //    -> OrderBy(Desc("createdAt"))
    }
}
```

### Query Methods: How Spring Processes @Query, Named Queries, Specifications

```java
// --- @Query (JPQL) ---
// @Query("SELECT o FROM Order o WHERE o.customer.id = :customerId AND o.status = :status")
// List<Order> findOrders(@Param("customerId") Long customerId, @Param("status") String status);

// Processing chain:
// 1. QueryExecutorMethodInterceptor detects @Query annotation on method
// 2. Creates DeclaredQuery:
//    -> Parses JPQL, detects named parameters (:customerId, :status)
//    -> Validates against entity metadata (does "customer.id" exist?)
//    -> Detects query type: SELECT, UPDATE, DELETE
// 3. At runtime: binds parameters, creates TypedQuery, executes

// Source: org.springframework.data.jpa.repository.query.SimpleJpaQuery

class SimpleJpaQuery extends AbstractStringBasedJpaQuery {
    
    @Override
    protected Query createJpaQuery(String queryString, 
            Sort sort, LockModeType lockMode) {
        // queryString = "SELECT o FROM Order o WHERE ..."
        
        // Detect if it's a native query:
        if (this.queryMethod.isNativeQuery()) {
            // Pass through as native SQL
            return entityManager.createNativeQuery(queryString);
        }
        
        if (this.queryMethod.isModifyingQuery()) {
            // UPDATE/DELETE queries use executeUpdate(), not getResultList()
            return entityManager.createQuery(queryString);
        }
        
        // Standard JPQL SELECT query
        return entityManager.createQuery(queryString);
    }
}

// --- @Query (Native SQL) ---
// @Query(value = "SELECT * FROM orders WHERE created_at > :since", 
//        nativeQuery = true)
// List<Order> findRecentOrders(@Param("since") LocalDateTime since);

// Processing: Same as JPQL but uses createNativeQuery() instead of createQuery()

// --- Specifications (JPA Criteria API) ---
// orderRepo.findAll(hasStatus("ACTIVE").and(createdAfter(lastWeek)));

// Source: org.springframework.data.jpa.repository.support.SimpleJpaRepository

public List<T> findAll(@Nullable Specification<T> spec, Sort sort) {
    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
    CriteriaQuery<T> query = builder.createQuery(getDomainClass());
    Root<T> root = query.from(getDomainClass());
    
    if (spec != null) {
        Predicate predicate = spec.toPredicate(root, query, builder);
        query.where(predicate);
    }
    
    if (sort.isSorted()) {
        query.orderBy(QueryUtils.toOrders(sort, root, builder));
    }
    
    return entityManager.createQuery(query).getResultList();
}

// Specification composition:
public class OrderSpecifications {
    public static Specification<Order> hasStatus(String status) {
        return (root, query, cb) -> 
                cb.equal(root.get("status"), status);
    }
    
    public static Specification<Order> createdAfter(LocalDateTime since) {
        return (root, query, cb) -> 
                cb.greaterThan(root.get("createdAt"), since);
    }
    
    public static Specification<Order> customerNameContains(String name) {
        return (root, query, cb) -> {
            Join<Order, Customer> customer = root.join("customer");
            return cb.like(customer.get("name"), "%" + name + "%");
        };
    }
}
```

### Transaction Management Deep Dive: PlatformTransactionManager Hierarchy

```java
// Source: org.springframework.transaction.PlatformTransactionManager

public interface PlatformTransactionManager {
    TransactionStatus getTransaction(TransactionDefinition definition) 
            throws TransactionException;
    void commit(TransactionStatus status) throws TransactionException;
    void rollback(TransactionStatus status) throws TransactionException;
}

// Hierarchy:
// PlatformTransactionManager (interface)
//   └-- AbstractPlatformTransactionManager (template method)
//       └-- DataSourceTransactionManager (plain JDBC)
//       └-- JtaTransactionManager (JTA, distributed TX)
//       └-- JpaTransactionManager (JPA with Hibernate)

// Source: org.springframework.transaction.support.AbstractPlatformTransactionManager

public final TransactionStatus getTransaction(
        @Nullable TransactionDefinition definition) throws TransactionException {
    
    // Step 1: Check if a transaction already exists
    Object transaction = doGetTransaction();
    // For JpaTransactionManager: gets EntityManagerHolder from ThreadLocal
    
    if (isExistingTransaction(transaction)) {
        // Transaction already active -- handle propagation level
        return handleExistingTransaction(definition, transaction, debugTx);
    }
    
    // Step 2: Validate timeout settings
    if (definition.getTimeout() < TransactionDefinition.TIMEOUT_DEFAULT) {
        throw new InvalidTimeoutException("Invalid transaction timeout", 
                definition.getTimeout());
    }
    
    // Step 3: Validate propagation level
    if (definition.getPropagationBehavior() == 
            TransactionDefinition.PROPAGATION_MANDATORY) {
        throw new IllegalTransactionStateException(
                "No existing transaction found for " +
                "transaction marked with propagation 'mandatory'");
    }
    
    // Step 4: Start a new transaction
    if (definition.getPropagationBehavior() == 
            TransactionDefinition.PROPAGATION_REQUIRED ||
        definition.getPropagationBehavior() == 
            TransactionDefinition.PROPAGATION_REQUIRES_NEW ||
        definition.getPropagationBehavior() == 
            TransactionDefinition.PROPAGATION_NESTED) {
        
        SuspendedResourcesHolder suspendedResources = suspend(null);
        
        try {
            DefaultTransactionStatus status = newTransactionStatus(
                    definition, transaction, true, 
                    newSynchronization, debugTx, suspendedResources);
            doBegin(transaction, definition);  // <-- Gets connection, starts DB TX
            prepareSynchronization(status, definition);
            return status;
        } catch (RuntimeException | Error ex) {
            resume(null, suspendedResources);
            throw ex;
        }
    }
    
    // Step 5: PROPAGATION_NOT_SUPPORTED, NEVER, SUPPORTS
    // Create "empty" transaction -- no actual database transaction
    boolean newSynchronization = (getTransactionSynchronization() 
            == SYNCHRONIZATION_ALWAYS);
    return prepareTransactionStatus(definition, null, true, 
            newSynchronization, debugTx, null);
}

// Source: org.springframework.orm.jpa.JpaTransactionManager.doBegin()

@Override
protected void doBegin(Object transaction, TransactionDefinition definition) {
    JpaTransactionObject txObject = (JpaTransactionObject) transaction;
    
    // Step 1: Get or create EntityManager
    EntityManager em = createEntityManagerForTransaction();
    
    // Step 2: Bind EntityManager to current thread
    // ThreadLocal: TransactionSynchronizationManager.bindResource(
    //     entityManagerFactory, new EntityManagerHolder(em));
    
    // Step 3: Set Hibernate session properties from transaction definition
    if (definition.isReadOnly()) {
        em.setProperty("org.hibernate.readOnly", true);
        // Hibernate: session.setDefaultReadOnly(true);
        //           -> disables dirty checking
    }
    
    if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
        // Set isolation level on the JDBC connection
        int isolation = definition.getIsolationLevel();
        // e.g., Connection.TRANSACTION_READ_COMMITTED
        em.setProperty("hibernate.connection.isolation", isolation);
    }
    
    // Step 4: Get JDBC connection from HikariCP via EntityManager
    Connection connection = em.unwrap(Session.class)
            .connection();  // Gets from HikariCP pool
    
    // Step 5: Begin the actual database transaction
    // connection.setAutoCommit(false);
}

// Source: org.springframework.orm.jpa.JpaTransactionManager.doCommit()

@Override
protected void doCommit(DefaultTransactionStatus status) {
    JpaTransactionObject txObject = (JpaTransactionObject) status.getTransaction();
    
    if (status.isDebug()) {
        logger.debug("Committing JPA transaction on EntityManager [" +
                txObject.getEntityManagerHolder().getEntityManager() + "]");
    }
    
    try {
        EntityTransaction tx = txObject.getEntityManagerHolder()
                .getEntityManager().getTransaction();
        tx.commit();
        // Hibernate: session.flush() then connection.commit()
        //   -> flush: synchronize PersistenceContext -> SQL INSERT/UPDATE/DELETE
        //   -> commit: JDBC connection.commit()
    } catch (RuntimeException ex) {
        // If flush during commit fails, Hibernate may have already 
        // marked the transaction for rollback
        throw ex;
    }
}
```

### @Transactional Propagation Levels -- Deep Dive with Real Scenarios

```java
// --- PROPAGATION_REQUIRED (default) ---
// Joins existing transaction if one exists, creates new if none exists.

@Service
public class OrderService {
    
    @Transactional  // (propagation = REQUIRED)
    public void placeOrder(PlaceOrderCommand cmd) {
        // TX1 starts HERE (no existing tx)
        orderRepo.save(order);  // In TX1
        
        // This joins TX1 (existing tx found)
        inventoryService.reserve(cmd.items());  // In TX1, same connection
        
        paymentService.authorize(cmd.payment());  // In TX1
        
        // If payment fails: TX1 rolls back entirely (order + inventory + payment)
    }
}

@Service
public class InventoryService {
    
    @Transactional  // Joins existing transaction (TX1)
    public void reserve(List<LineItem> items) {
        // Decrements stock. If this fails, TX1 rolls back -> order rolls back too.
        // This is CORRECT: we want atomicity.
    }
}

// --- PROPAGATION_REQUIRES_NEW ---
// SUSPENDS current transaction, creates a NEW independent transaction.
// Used when a sub-operation must commit/rollback independently.

@Service
public class OrderService {
    
    @Transactional  // TX1 (REQUIRED)
    public void placeOrder(PlaceOrderCommand cmd) {
        orderRepo.save(order);  // In TX1
        
        // This RUNS IN ITS OWN TRANSACTION (TX2), TX1 is SUSPENDED
        auditService.recordOrderCreated(order.getId());
        // TX2 commits independently. Even if TX1 rolls back, audit log persists.
        
        // This ALSO runs in its own TX3
        notificationService.sendOrderConfirmation(order.getId());
        // If notification fails, order is NOT rolled back. Correct for notifications.
    }
}

@Service
public class AuditService {
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOrderCreated(Long orderId) {
        // Runs in TX2. Commits independently of TX1.
        // If order placement (TX1) rolls back, the audit log still records the attempt.
        auditRepo.save(new AuditLog("ORDER_CREATED", orderId));
    }
    
    // WARNING: REQUIRES_NEW means database connections are HELD for both transactions.
    // If the pool has 10 connections and 5 are suspended, only 5 are available.
}

// --- PROPAGATION_NESTED ---
// Creates a SAVEPOINT within the current transaction.
// Rollback to savepoint on sub-method failure without rolling back entire TX.
// Only works with JDBC savepoints (PostgreSQL, Oracle, MySQL InnoDB).

@Service
public class OrderService {
    
    @Transactional  // TX1
    public void processBatch(List<Order> orders) {
        int successCount = 0;
        for (Order order : orders) {
            try {
                processSingleOrder(order);  // Uses NESTED
                successCount++;
            } catch (Exception e) {
                // This order fails, savepoint rolls back, but TX1 continues
                // Only this one order is rolled back, not the entire batch
                log.error("Failed to process order {}", order.getId(), e);
            }
        }
        // TX1 commits with all successful orders
    }
}

@Service
public class BatchOrderProcessor {
    
    @Transactional(propagation = Propagation.NESTED)
    public void processSingleOrder(Order order) {
        // Creates a savepoint: SAVEPOINT spring_tx_1
        // On success: RELEASE SAVEPOINT spring_tx_1
        // On failure: ROLLBACK TO SAVEPOINT spring_tx_1 (not the entire TX)
        orderRepo.save(order);
        // ... business logic ...
    }
}

// --- PROPAGATION_MANDATORY ---
// MUST join an existing transaction. Throws exception if no tx exists.

@Service
public class PaymentService {
    
    @Transactional(propagation = Propagation.MANDATORY)
    public void authorize(PaymentMethod method) {
        // ASSERTS: caller MUST have opened a transaction.
        // If called outside a @Transactional method -> 
        //   IllegalTransactionStateException at runtime
    }
}

// --- PROPAGATION_NEVER ---
// MUST NOT run in a transaction. Throws exception if tx exists.
// Useful for operations that should be non-transactional by design.

// --- PROPAGATION_NOT_SUPPORTED ---
// Suspends current transaction if one exists, runs without transaction.
// Useful for operations that should NOT participate in the current TX 
// (e.g., sending an email, calling an external API that might take 30 seconds).

// --- PROPAGATION_SUPPORTS ---
// Runs in current tx if one exists, non-transactional otherwise.
// Rarely used. Better to be explicit with REQUIRED or NOT_SUPPORTED.
```

### Transaction Isolation Levels and Database Mapping

```java
// Spring isolation levels map to JDBC isolation levels:

// @Transactional(isolation = Isolation.DEFAULT)  -> Database default
// @Transactional(isolation = Isolation.READ_UNCOMMITTED) -> Connection.TRANSACTION_READ_UNCOMMITTED
// @Transactional(isolation = Isolation.READ_COMMITTED)   -> Connection.TRANSACTION_READ_COMMITTED
// @Transactional(isolation = Isolation.REPEATABLE_READ)  -> Connection.TRANSACTION_REPEATABLE_READ
// @Transactional(isolation = Isolation.SERIALIZABLE)     -> Connection.TRANSACTION_SERIALIZABLE

// Database behavior:
// ┌──────────────────┬──────────────┬──────────────┬──────────────┬──────────────┐
// │                  │ Dirty Read   │ Non-repr. Rd │ Phantom Read │ Serial. Anom │
// ├──────────────────┼──────────────┼──────────────┼──────────────┼──────────────┤
// │ READ_UNCOMMITTED │ Yes          │ Yes          │ Yes          │ Yes          │
// │ READ_COMMITTED   │ No           │ Yes          │ Yes          │ Yes          │
// │ REPEATABLE_READ  │ No           │ No           │ Yes          │ Yes          │
// │ SERIALIZABLE     │ No           │ No           │ No           │ No           │
// └──────────────────┴──────────────┴──────────────┴──────────────┴──────────────┘

// PostgreSQL default: READ_COMMITTED
// MySQL default:      REPEATABLE_READ
// Oracle default:     READ_COMMITTED

// REAL-WORLD ISOLATION SCENARIO:

@Service
public class SeatReservationService {
    
    // @Transactional(isolation = Isolation.READ_COMMITTED)
    // Problem: Two concurrent requests read seat as "available", both reserve it.
    // Timeline:
    //   T1: TX1 reads seat A1 -> available (READ_COMMITTED: snapshot of committed data)
    //   T2: TX2 reads seat A1 -> available (READ_COMMITTED: snapshot of committed data)
    //   T3: TX1 UPDATE seat A1 SET status='RESERVED' -> succeeds
    //   T4: TX2 UPDATE seat A1 SET status='RESERVED' -> succeeds (overwrites TX1!)
    //   T5: TX1 commits. TX2 commits. DOUBLE BOOKING.
    
    // Solution 1: @Version (optimistic locking) -- better
    // Solution 2: SELECT ... FOR UPDATE (pessimistic locking) at SERVICE layer
    // Solution 3: @Transactional(isolation = Isolation.SERIALIZABLE)
    //   -> Database serializes transactions. TX2 would fail with serialization error.
    //   -> Overhead: higher contention, more retries.
    
    // Best practice for booking/reservation:
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResult reserve(Long seatId, Long customerId) {
        // Use OPTIMISTIC LOCKING, not higher isolation:
        // Seat entity has @Version field
        Seat seat = seatRepo.findById(seatId).orElseThrow();
        seat.reserve(customerId);  // seat.setStatus(RESERVED);
        seatRepo.save(seat);
        // @Version check: UPDATE seat SET status='RESERVED', version=version+1 
        //                 WHERE id=? AND version=?
        // If version doesn't match -> OptimisticLockException -> retry
        return ReservationResult.from(seat);
    }
}
```

### HikariCP Internals: Connection Pool Lifecycle

```java
// HikariCP Architecture:

// +------------------------------------------------------------------+
// |                        HikariPool                                 |
// |                                                                  |
// |  ConcurrentBag<PoolEntry>  -- thread-safe pool of connections    |
// |                                                                  |
// |  +-- HouseKeeper thread: runs every 30s                          |
// |  |   +-- Evicts idle connections beyond idleTimeout               |
// |  |   +-- Closes connections that exceed maxLifetime              |
// |  |   +-- Fills pool to minimumIdle                              |
// |  |                                                              |
// |  +-- connectionTimeout: 30000ms (max wait for a connection)      |
// |  +-- idleTimeout: 600000ms (max idle time before eviction)       |
// |  +-- maxLifetime: 1800000ms (max connection lifetime)            |
// |  +-- maximumPoolSize: 10 (default)                              |
// |  +-- minimumIdle: 10 (default, same as max)                     |
// |  +-- leakDetectionThreshold: 0 (disabled by default)            |
// |  +-- validationTimeout: 5000ms (connection test timeout)        |
// +------------------------------------------------------------------+

// Connection BORROW flow:
// 1. HikariPool.getConnection() called
// 2. Check ConcurrentBag for idle connection
//    -> If found: validate connection (test query or JDBC4 isValid)
//    -> If valid: return it
//    -> If invalid: evict, try again
// 3. If no idle connection and pool < maximumPoolSize:
//    -> Create new connection (in background thread, non-blocking)
//    -> Add to ConcurrentBag
// 4. If pool at max and no idle:
//    -> Thread WAITS (park) with timeout = connectionTimeout (30s default)
//    -> On timeout: throw SQLException("Connection is not available...")
//    -> On connection returned: thread unparked, gets connection

// Connection RETURN flow:
// 1. Connection.close() (actually returns to pool -- proxy intercepts)
// 2. ConcurrentBag.requite(poolEntry) -- adds back to idle list
// 3. If threads are WAITING for a connection: signal one to wake up

// Connection LEAK detection:
// When leakDetectionThreshold > 0:
//   1. On borrow: record startTime
//   2. On return: clear startTime
//   3. Scheduler checks: if (now - startTime) > threshold
//      -> LOG WARNING with stack trace of borrow location:
//      "Connection leak detection triggered for connection X, 
//       stack trace follows: java.lang.Exception
//         at com.zaxxer.hikari.pool.ProxyConnection.close(...)
//         at com.example.OrderService.findOrders(...)"
```

### Hibernate/JPA Dirty Checking -- How It Works

```java
// Hibernate dirty checking is the mechanism that detects changes to
// managed entities and generates UPDATE statements at flush time.

// HOW IT WORKS:
// 1. When an entity is loaded (findById, query), Hibernate takes a SNAPSHOT
//    of the entity's state. The entity is in "managed" state.
// 2. When flush() is called (explicitly, or before query, or before commit),
//    Hibernate compares the current state of each managed entity with its
//    snapshot (stored in the PersistenceContext).
// 3. For each entity where current state != snapshot: generates UPDATE SQL.

// Source: org.hibernate.engine.internal.StatefulPersistenceContext

// The entity snapshot is stored in:
// Map<EntityKey, Object[]> entitySnapshotsByKey;
// Where EntityKey = {entityName, identifier}
// And Object[] = the loaded property values (deep-copied)

// Example:
@Transactional
public void updateOrderStatus(Long orderId, OrderStatus newStatus) {
    // 1. Load entity: entity becomes MANAGED, snapshot taken
    Order order = orderRepo.findById(orderId).orElseThrow();
    //    state: {id=42, status=PENDING, amount=100}
    //    snapshot: [{id=42}, {id=42, status=PENDING, amount=100}]
    
    // 2. Modify entity
    order.setStatus(newStatus);
    //    current state:  {id=42, status=SHIPPED, amount=100}
    //    snapshot:       {id=42, status=PENDING, amount=100}
    //    -> DIFFERENCE detected at flush time
    
    // 3. Flush (automatic before commit):
    //    -> Compare all managed entities current state vs snapshot
    //    -> Order current vs snapshot: status differs
    //    -> Generate: UPDATE orders SET status='SHIPPED' WHERE id=42
    
    // 4. Commit: JDBC commit
}

// Dirty checking is EXPENSIVE. For read-only operations, disable it:
@Transactional(readOnly = true)
public List<Order> findOrders() {
    return orderRepo.findAll();
    // Hibernate knows this is read-only:
    //   - No snapshots taken for loaded entities
    //   - No dirty checking at flush time
    //   - No AUTO flush before queries
}

// FLUSH MODES:
// FlushModeType.AUTO (default):
//   - Flush before every query (to ensure query sees current state)
//   - Flush before commit
// FlushModeType.COMMIT:
//   - Flush only before commit
//   - Potential stale reads within the same transaction
// FlushModeType.MANUAL:
//   - Flush only when explicitly called
//   - Use with extreme caution
```

### N+1 Query Problem: Detection and Fixes

```java
// THE N+1 PROBLEM:
// One query fetches parent entities, then N queries fetch children.

// SCENARIO: Find all orders and display their items
@Entity
public class Order {
    @Id private Long id;
    
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();
}

// BROKEN CODE:
@Transactional(readOnly = true)
public List<OrderResponse> findAllOrders() {
    List<Order> orders = orderRepo.findAll();
    // SQL: SELECT * FROM orders           -- 1 query
    // Returns: 50 orders
    
    return orders.stream()
            .map(order -> OrderResponse.from(order))
            .toList();
    // OrderResponse.from() calls order.getItems().size()
    // For each of 50 orders: SELECT * FROM order_items WHERE order_id = ?
    // SQL: 50 additional queries (total: 1 + 50 = 51 queries)
}

// DETECTION METHODS:
// 1. application.properties:
//    spring.jpa.show-sql=true
//    logging.level.org.hibernate.SQL=DEBUG
//    logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE

// 2. datasource-proxy (more powerful):
//    Add dependency: com.github.gavlyukovskiy:datasource-proxy-spring-boot-starter
//    logging.level.net.ttddyy.dsproxy.listener=DEBUG
//    Shows: query count, execution time, parameters

// FIX 1: JOIN FETCH (explicit join with eager loading)
@Query("SELECT DISTINCT o FROM Order o " +
       "JOIN FETCH o.items " +
       "WHERE o.customer.id = :customerId")
List<Order> findByCustomerIdWithItems(@Param("customerId") Long customerId);
// SQL: SELECT o.*, i.* FROM orders o 
//      JOIN order_items i ON o.id = i.order_id 
//      WHERE o.customer_id = ?
// 1 query instead of 1 + N

// FIX 2: @EntityGraph (declarative fetch strategy)
@Entity
@NamedEntityGraph(
    name = "Order.items",
    attributeNodes = @NamedAttributeNode("items")
)
public class Order { ... }

public interface OrderRepository extends JpaRepository<Order, Long> {
    
    @EntityGraph("Order.items")
    List<Order> findByCustomerId(Long customerId);
    // Loads customer + items in one query with JOIN FETCH under the hood
}

// FIX 3: @BatchSize (batch lazy loading)
@Entity
public class Order {
    @OneToMany(mappedBy = "order")
    @BatchSize(size = 100)  // Load items in batches of 100
    private List<OrderItem> items;
}
// Instead of 50 queries for 50 orders:
// SQL: SELECT * FROM order_items WHERE order_id IN (?,?,?,?,? ... up to 100)

// FIX 4: DTO Projection (avoids entity graph entirely)
// Constructor expression in JPQL:
@Query("SELECT new com.example.OrderSummaryDto(" +
       "o.id, o.status, o.totalAmount, o.createdAt) " +
       "FROM Order o WHERE o.customer.id = :customerId")
List<OrderSummaryDto> findOrderSummaries(@Param("customerId") Long customerId);
// Only selects needed columns, no entity graph traversal, no lazy loading.
```

### Read-Only Transaction Optimization

```java
// @Transactional(readOnly = true) provides two levels of optimization:

// LEVEL 1: Hibernate (application level)
// - Entities loaded in read-only session have DEFAULT_READ_ONLY = true
// - No snapshots taken for loaded entities (no dirty checking)
// - AUTO flush mode: no flush before queries
// - Automatic dirty checking skipped for ALL entities in the transaction
// - ~10-15% CPU reduction for read-heavy operations

// LEVEL 2: Database (JDBC connection level)
// - Connection.setReadOnly(true) hint sent to JDBC driver
// - PostgreSQL: enables read-only transaction mode
//   -> Prevents accidental writes: "cannot execute INSERT in a read-only transaction"
//   -> Can skip acquiring certain row-level locks
// - MySQL: sets transaction_read_only to ON
//   -> Reduces undo log growth for InnoDB (no rollback segments needed)
// - Oracle: can enable read-only transaction (read-consistent snapshot)

// VERIFYING READ-ONLY OPTIMIZATION:

@Transactional(readOnly = true)
public OrderResult findOrder(Long id) {
    Order order = orderRepo.findById(id).orElseThrow();
    
    // Verify read-only mode:
    Session session = entityManager.unwrap(Session.class);
    System.out.println("Read-only: " + session.isDefaultReadOnly());
    // Output: Read-only: true
    
    // Attempting a write:
    order.setStatus(OrderStatus.CANCELLED);
    // At flush time: Hibernate DETECTS entity is dirty in a read-only session
    // -> No UPDATE SQL generated (silently ignored if flush happens)
    // -> OR: Hibernate may throw if configured to be strict
    
    return OrderResult.from(order);
}

// ENABLING STRICT READ-ONLY (fails fast on writes in read-only TX):
// spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true
// Custom: set hibernate.connection.provider_disables_autocommit=true
```

### Connection Leaks: How They Happen, Symptoms, Detection

```
  +------------------------------------------------------------------+
  |                     CONNECTION LEAK SCENARIO                      |
  |                                                                  |
  |  @Transactional                                                  |
  |  public void generateReport(Long requestId) {                    |
  |      Report report = reportRepo.findById(requestId).orElseThrow();|
  |      // HikariCP: connection BORROWED from pool                   |
  |                                                                  |
  |      byte[] pdf = generatePdf(report);  // Takes 30 seconds!     |
  |      // Connection is HELD for 30 seconds, doing NO database work |
  |                                                                  |
  |      emailService.sendPdf(pdf);  // Takes another 5 seconds      |
  |      // Connection still held                                    |
  |                                                                  |
  |  }  // Connection finally returned to pool after 35 seconds       |
  |                                                                  |
  |  SYMPTOMS:                                                       |
  |  1. Intermittent: "Connection is not available, request timed    |
  |     out after 30000ms"                                           |
  |  2. Occurs under load: when report generation and regular        |
  |     traffic coincide                                             |
  |  3. Thread dump: multiple threads WAITING in HikariCP            |
  |     HikariPool.getConnection()                                   |
  |                                                                  |
  |  DETECTION:                                                      |
  |  1. Enable leak detection:                                        |
  |     spring.datasource.hikari.leak-detection-threshold=10000      |
  |     -> Logs stack trace of any connection borrowed >10s          |
  |                                                                  |
  |  2. Monitor HikariCP metrics:                                    |
  |     hikaricp.connections.active  (micrometer gauge)              |
  |     hikaricp.connections.idle                                           |
  |     hikaricp.connections.pending (waiting for connection)        |
  |     hikaricp.connections.timeout (counter of timeouts)           |
  |                                                                  |
  |  FIX:                                                            |
  |  @Transactional                                                  |
  |  public ReportData loadReportData(Long requestId) {              |
  |      return reportRepo.findReportData(requestId);                |
  |      // Connection RETURNED here (transaction ends)              |
  |  }                                                               |
  |                                                                  |
  |  public byte[] generateReportPdf(Long requestId) {               |
  |      // Non-transactional. No connection held.                   |
  |      ReportData data = loadReportData(requestId);  // Separate TX |
  |      return pdfGenerator.generate(data);  // 30 secs, no DB conn |
  |  }                                                               |
  +------------------------------------------------------------------+
```

### Write-Behind / Outbox Pattern with Spring

```java
// The OUTBOX PATTERN ensures reliable event publication:
// Instead of: (1) write to DB, (2) publish event to message queue
// (which can fail between steps 1 and 2, losing the event)
// 
// DO: Write to DB + write to outbox table IN SAME TRANSACTION.
//     A separate process reads the outbox and publishes events.

// STEP 1: Outbox table entity
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    private UUID id;
    
    @Column(nullable = false)
    private String aggregateType;  // "Order"
    
    @Column(nullable = false)
    private String aggregateId;    // "42"
    
    @Column(nullable = false)
    private String eventType;      // "OrderPlaced"
    
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;        // JSON serialized event
    
    @Column(nullable = false)
    private Instant createdAt;
    
    @Column(nullable = false)
    private Instant publishedAt;   // null until published
}

// STEP 2: Write event to outbox in the same transaction as the domain change
@Service
public class OrderApplicationService {
    private final OrderRepository orderRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;
    
    @Transactional
    public PlaceOrderResult placeOrder(PlaceOrderCommand cmd) {
        Order order = Order.place(cmd);
        orderRepo.save(order);
        
        // Write event to outbox in SAME TRANSACTION
        OrderPlaced event = new OrderPlaced(order.getId(), 
                order.getTotal(), order.getCustomerId(), Instant.now());
        
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateType("Order");
        outboxEvent.setAggregateId(order.getId().toString());
        outboxEvent.setEventType("OrderPlaced");
        outboxEvent.setPayload(objectMapper.writeValueAsString(event));
        outboxEvent.setCreatedAt(Instant.now());
        
        outboxRepo.save(outboxEvent);
        // Both order and outbox event committed atomically
        
        return PlaceOrderResult.from(order);
    }
}

// STEP 3: Outbox poller (reads unpublished events and publishes to message broker)
@Component
public class OutboxEventPublisher {
    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    @Scheduled(fixedDelay = 100)  // Poll every 100ms
    @Transactional
    public void publishOutboxEvents() {
        List<OutboxEvent> events = outboxRepo
                .findTop100ByPublishedAtIsNullOrderByCreatedAt();
        
        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(
                        event.getAggregateType().toLowerCase() + "-events",
                        event.getAggregateId(),
                        event.getPayload()
                ).get(5, TimeUnit.SECONDS);
                
                event.setPublishedAt(Instant.now());
                outboxRepo.save(event);
                // If Kafka send succeeds, mark as published.
                // If the save fails (unlikely), the event will be republished
                // (at-least-once delivery). Consumer must be idempotent.
                
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}", event.getId(), e);
                break;  // Stop processing, retry on next poll
            }
        }
    }
}
```

## 4. Runtime Behavior

### Complete Transaction Lifecycle: @Transactional Method Invocation

```
Timeline for: orderService.placeOrder(cmd) with @Transactional

T=0ms   Caller (controller) calls placeOrder(cmd)
        -> The call hits the CGLIB PROXY, not the real method
        -> TransactionInterceptor.invoke(MethodInvocation)

T=1ms   TransactionInterceptor.invokeWithinTransaction():
        
        Step A: Determine TransactionAttribute
        -> Read @Transactional annotation:
           propagation=REQUIRED, isolation=DEFAULT, readOnly=false, 
           timeout=-1, rollbackFor={}, noRollbackFor={}
        
        Step B: PlatformTransactionManager.getTransaction(txAttr)
        -> AbstractPlatformTransactionManager.getTransaction()
        -> doGetTransaction():
           -> Check ThreadLocal: TransactionSynchronizationManager
              .getResource(entityManagerFactory)
           -> null (no existing transaction)
        -> isExistingTransaction(txObject) -> false
        
        Step C: Start new transaction
        -> doBegin(txObject, definition):
           -> JpaTransactionManager.doBegin():
              +-- createEntityManagerForTransaction()
              |   +-- entityManagerFactory.createEntityManager()
              |   +-- Set JPA properties from @Transactional:
              |       readOnly=false -> no optimization
              |       isolation=DEFAULT -> use database default
              +-- EntityManagerHolder holder = new EntityManagerHolder(em)
              +-- TransactionSynchronizationManager.bindResource(
              |       emf, holder)  -- ThreadLocal binding
              +-- em.unwrap(Session.class).connection()
              |   -> HikariCP.getConnection()
              |       -> HikariPool.getConnection()
              |           -> ConcurrentBag.borrow(idleTimeout, TimeUnit)
              |           -> Returns PoolEntry with JDBC Connection
              |   -> connection.setAutoCommit(false)
              |   -> Actual database transaction BEGINS
              +-- Prepare TransactionSynchronization (afterCommit hooks)

T=5ms   [Transaction ACTIVE, EntityManager bound to thread]

T=6ms   invocation.proceed() -> actual placeOrder() method:
        -> orderRepo.save(order) -- uses SAME EntityManager (ThreadLocal)
           +-- entityManager.persist(order)
           +-- Hibernate: assigns ID, entity becomes MANAGED
           +-- snapshot taken for dirty checking
        -> inventoryService.reserve(cmd.items())
           +-- @Transactional: existing TX found -> JOINS (propagation=REQUIRED)
           +-- Uses same EntityManager, same Connection
           +-- inventoryRepo.decrementStock(sku, qty)
               +-- entityManager.createQuery("UPDATE Inventory SET ...")
               +-- FlushMode.AUTO: flush before this query
                   +-- SessionImpl.flush():
                       +-- Check dirty entities: Order (new, no changes),
                           Inventory (new, no changes)
                       +-- No UPDATEs needed, just persist new entities

T=10ms  After placeOrder() returns successfully:

T=11ms  -> commitTransactionAfterReturning(txInfo):
        -> txInfo.getTransactionManager().commit(txInfo.getTransactionStatus())
        -> JpaTransactionManager.doCommit(status):
           +-- JpaTransactionObject.getEntityManagerHolder()
               .getEntityManager().getTransaction().commit()
           +-- Hibernate EntityTransaction.commit():
               +-- SessionImpl.flush():
               |   +-- Iterate all MANAGED entities in PersistenceContext
               |   +-- Compare current state vs snapshot
               |   +-- Order: found changes -> UPDATE orders SET ... WHERE id=42
               |   +-- Inventory: found changes -> UPDATE inventory SET ... WHERE sku=?
               |   +-- Execute all pending INSERT/UPDATE/DELETE statements
               |   +-- JDBC batch or individual statements
               +-- Connection.commit(): database transaction commits
               +-- PersistenceContext cleared

T=15ms  -> triggerAfterCompletion(status=COMMITTED):
        -> TransactionSynchronization.afterCompletion(STATUS_COMMITTED)
        -> TransactionSynchronization.afterCommit() callbacks
            +-- @TransactionalEventListener(phase=AFTER_COMMIT) fired
            +-- ApplicationEventPublisher publishes OrderPlaced event
        -> TransactionSynchronizationManager.unbindResource(emf)
        -> EntityManager.close()
        -> HikariCP: Connection.close() -> actually returns to pool
           +-- ConcurrentBag.requite(poolEntry)
           +-- PoolEntry marked as idle

T=18ms  Transaction COMPLETE. Proxy returns result to caller.
```

### Transaction Rollback Flow

```
Timeline for: placeOrder() throws InvalidOrderException

T=0-10ms Same as commit flow through doBegin and business logic...

T=11ms  placeOrder() throws new InvalidOrderException("...")
        -> Exception propagates up to TransactionInterceptor

T=12ms  TransactionInterceptor: catch (Throwable ex) {
        -> completeTransactionAfterThrowing(txInfo, ex):
        -> txAttr.rollbackOn(ex):
           -> InvalidOrderException extends RuntimeException -> true
           -> (If checked exception: check rollbackFor list)
           -> (If RuntimeException but in noRollbackFor: return false)

T=13ms  -> txInfo.getTransactionManager().rollback(txInfo.getTransactionStatus())
        -> AbstractPlatformTransactionManager.rollback(status):
           -> doRollback(status):
           -> JpaTransactionManager.doRollback(status):
              +-- entityManager.getTransaction().rollback()
              +-- Hibernate: marks PersistenceContext for discard
              +-- Connection.rollback(): database discards all changes
              +-- PersistenceContext cleared (all entities detached)

T=15ms  -> triggerAfterCompletion(status=ROLLED_BACK):
        -> TransactionSynchronization.afterCompletion(STATUS_ROLLED_BACK)
        -> TransactionSynchronization.afterRollback() callbacks
            +-- @TransactionalEventListener(phase=AFTER_ROLLBACK) fired
        -> TransactionSynchronizationManager.unbindResource(emf)
        -> EntityManager.close()
        -> HikariCP: Connection returned to pool

T=17ms  -> Exception re-thrown to caller (controller's @ExceptionHandler)
```

## 5. Request Flow Diagrams

### Query Execution Flow: From Service Method to SQL

```
  orderRepo.findByCustomerIdAndStatus(customerId, "ACTIVE")
      |
      v
  +-----------------------------+
  | JDK Dynamic Proxy           |
  | orderRepo (JpaRepository)   |
  +-------------+---------------+
                |
                v
  +-----------------------------+
  | QueryExecutorMethodInterceptor |
  |                               |
  | 1. Lookup query method        |
  |    -> PartTreeJpaQuery        |
  | 2. Parse method name          |
  |    -> PartTree: findBy,       |
  |       CustomerId, And, Status |
  +-------------+---------------+
                |
                v
  +-----------------------------+
  | JpaQueryExecution            |
  |                               |
  | 1. Create JPA Query:          |
  |    SELECT o FROM Order o     |
  |    WHERE o.customer.id = ?1  |
  |    AND o.status = ?2         |
  | 2. Set parameters:            |
  |    setParameter(1, customerId)|
  |    setParameter(2, "ACTIVE") |
  +-------------+---------------+
                |
                v
  +-----------------------------+
  | EntityManager                |
  |                               |
  | 1. Get Hibernate Session      |
  | 2. Create TypedQuery          |
  | 3. Translate JPQL -> SQL      |
  |    SELECT o.id, o.status, ... |
  |    FROM orders o             |
  |    WHERE o.customer_id = ?   |
  |    AND o.status = ?          |
  +-------------+---------------+
                |
                v
  +-----------------------------+
  | JDBC PreparedStatement       |
  |                               |
  | 1. Get connection from pool  |
  | 2. Prepare statement          |
  | 3. Bind parameters            |
  | 4. Execute query              |
  | 5. Process ResultSet          |
  |    -> Build Order entities   |
  |    -> Cache in Session       |
  |       (PersistenceContext)   |
  +-------------+---------------+
                |
                v
  +-----------------------------+
  | Return List<Order> to caller |
  +-----------------------------+
```

### Transaction Propagation Flow: REQUIRED vs REQUIRES_NEW vs NESTED

```
  Propagation REQUIRED (default):
  
  outerMethod() [@Transactional]
      |
      +-- TX1 begins
      +-- save(A)
      +-- innerMethod() [@Transactional]  <- joins TX1 (not new)
      |       +-- save(B)                  <- in TX1
      |       +-- if inner fails: TX1 DOOMED (rollback only)
      +-- save(C)                          <- also in TX1 (rolled back too)
      +-- TX1 commits (or rolls back if inner failed)


  Propagation REQUIRES_NEW:
  
  outerMethod() [@Transactional]
      |
      +-- TX1 begins
      +-- save(A)                     <- in TX1
      +-- innerMethod() [REQUIRES_NEW]
      |       |
      |       +-- TX1 SUSPENDED
      |       +-- TX2 BEGINS (new connection from pool)
      |       +-- save(B)             <- in TX2
      |       +-- TX2 COMMITS independently
      |       +-- TX1 RESUMED
      +-- save(C)                     <- in TX1
      +-- TX1 commits
      |   Result: A, B, C all saved. If inner fails, A and C are saved.
      |   WARNING: Two connections held simultaneously -> pool size must accommodate.


  Propagation NESTED:
  
  outerMethod() [@Transactional]
      |
      +-- TX1 begins
      +-- SAVEPOINT SP1
      +-- save(A)                     <- in TX1
      +-- innerMethod() [NESTED]
      |       |
      |       +-- SAVEPOINT SP2 (nested within TX1)
      |       +-- save(B)             <- in same TX1, SP2 boundary
      |       +-- if fails: ROLLBACK TO SP2 (not entire TX1)
      |       +-- if succeeds: RELEASE SP2 (or auto-release on commit)
      +-- save(C)                     <- in TX1, after SP2
      +-- TX1 commits
      |   Result: A and C saved, B rolled back. Single connection.
      |   Only works with databases supporting savepoints.
```

## 6. Lifecycle Diagrams

### HikariCP Connection Pool Lifecycle

```
  +------------------------------------------------------------------+
  |                    HIKARICP CONNECTION LIFECYCLE                  |
  +------------------------------------------------------------------+

  STATE: NOT_EXISTS
  |
  | Pool needs a connection
  v
  STATE: CREATING
  +-- newConnection() called on thread pool
  +-- Driver.connect(url, props) -> java.sql.Connection
  +-- PoolEntry created wrapping the connection
  |
  v
  STATE: IDLE (in ConcurrentBag, available for borrowing)
  +-- connection is open, ready for use
  +-- lastAccessTime tracked for idle timeout
  +-- PoolEntry.state = NOT_IN_USE
  |
  | Thread calls HikariDataSource.getConnection()
  v
  STATE: IN_USE (borrowed by application)
  +-- PoolEntry.state = IN_USE
  +-- borrowStartTime recorded for leak detection
  +-- lastAccessTime updated
  +-- Connection proxy wraps real connection
  |   (intercepts close() to return to pool instead)
  |
  | Application calls connection.close()
  v
  STATE: IDLE (returned to pool)
  +-- PoolEntry.state = NOT_IN_USE
  +-- Connection reset: autoCommit=true, readOnly=false, 
  |   isolation=default, catalog/schema reset
  +-- If connection fails validation: -> EVICTED
  +-- If idleTimeout exceeded by HouseKeeper: -> EVICTED
  |
  v
  STATE: EVICTED (connection removed from pool and closed)
  +-- connection.close() -- JDBC close, releases database-side resources
  +-- If pool size < minimumIdle: -> CREATING (fill pool)
  |
  v
  STATE: CLOSED
  +-- JDBC connection closed, database resources freed
```

### EntityManager / PersistenceContext Lifecycle

```
  +------------------------------------------------------------------+
  |              ENTITYMANAGER / PERSISTENCE CONTEXT                  |
  +------------------------------------------------------------------+

  ENTITY STATES:

  NEW / TRANSIENT:
  +-- Entity created with 'new' keyword
  +-- No database row, no ID, not tracked
  +-- Order order = new Order(); order.setStatus(PENDING);
  +-- em.persist(order) -> transitions to MANAGED

  MANAGED:
  +-- Entity has database identity, tracked by PersistenceContext
  +-- Dirty checking active: changes generate UPDATE at flush
  +-- Session caches entity by ID (Level 1 cache)
  +-- state transitions:
  |   +-- loaded by find()/query: NEW -> MANAGED
  |   +-- persisted: TRANSIENT -> MANAGED
  |   +-- merged: DETACHED -> MANAGED (returns NEW managed instance)
  +-- flush(): dirty-check -> generate SQL
  +-- commit(): flush + JDBC commit

  DETACHED:
  +-- Entity has ID but is not tracked
  +-- em.detach(entity): MANAGED -> DETACHED
  +-- em.clear(): all MANAGED -> DETACHED
  +-- em.close(): all MANAGED -> DETACHED
  +-- session.evict(entity): MANAGED -> DETACHED
  +-- LazyInitializationException if accessing unloaded associations
  +-- em.merge(entity): DETACHED -> MANAGED (copy returned)

  REMOVED:
  +-- em.remove(entity): MANAGED -> REMOVED
  +-- On flush: DELETE SQL generated
  +-- On commit: row deleted from database

  PersistenceContext = Level 1 Cache:
  ┌──────────────────────────────────────────────────────────────┐
  │ Map<EntityKey, Object> entitiesByKey;                         │
  │   -> {EntityKey("Order", 42) -> Order@4f3c}                   │
  │                                                              │
  │ Map<EntityKey, Object[]> entitySnapshotsByKey; (for dirty check)
  │   -> {EntityKey("Order", 42) -> [42L, PENDING, 100.00]}     │
  │                                                              │
  │ Map<String, List<EntityKey>> entitiesByEntityName;            │
  │   -> {"Order" -> [EntityKey(42), EntityKey(43)]}             │
  │                                                              │
  │ ActionQueue (pending INSERT/UPDATE/DELETE):                   │
  │   -> [InsertAction(Order@4f3c), UpdateAction(Item@5a1b)]    │
  └──────────────────────────────────────────────────────────────┘
```

### JpaRepository Proxy Lifecycle

```
  +------------------------------------------------------------------+
  |              JPA REPOSITORY PROXY LIFECYCLE                      |
  +------------------------------------------------------------------+

  1. BEAN DEFINITION PHASE (refresh step 5)
     +-- @EnableJpaRepositories triggers JpaRepositoriesRegistrar
     +-- Scans base packages for interfaces extending JpaRepository
     +-- For each found interface: creates JpaRepositoryFactoryBean BD
     +-- BeanDefinition for "orderRepository" registered

  2. BEAN INSTANTIATION PHASE (refresh step 11)
     +-- getBean("orderRepository")
     +-- JpaRepositoryFactoryBean.getObject():
     |   +-- Creates JpaRepositoryFactory
     |   +-- factory.getRepository(OrderRepository.class):
     |       |
     |       +-- Step 1: Determine repository base class
     |       |   -> SimpleJpaRepository (CRUD implementations)
     |       |
     |       +-- Step 2: Build query lookup strategy
     |       |   -> CreateQueryLookupStrategy:
     |       |       QUERY_CREATE: parse method names -> PartTree
     |       |       USE_DECLARED_QUERY: find @Query annotations
     |       |       QUERY_CREATE_IF_NOT_FOUND: try @Query, fallback to PartTree
     |       |
     |       +-- Step 3: Collect custom implementations
     |       |   -> Look for OrderRepositoryImpl bean (custom fragment)
     |       |
     |       +-- Step 4: Create JDK Dynamic Proxy
     |       |   -> Proxy.newProxyInstance(
     |       |       classLoader,
     |       |       [OrderRepository.class, JpaRepository.class, ...],
     |       |       invocationHandler)
     |       |   -> InvocationHandler: routes method calls to:
     |       |       +-- SimpleJpaRepository (CRUD: save, findById, ...)
     |       |       +-- QueryExecutorMethodInterceptor (custom queries)
     |       |       +-- Custom implementation fragment
     |       |
     |       +-- Return the proxy instance
     |
     +-- addSingleton("orderRepository", proxy)

  3. RUNTIME
     +-- Every orderRepo.findById(42L) goes through the proxy
     +-- InvocationHandler routes to SimpleJpaRepository.findById()
     +-- SimpleJpaRepository uses injected EntityManager (ThreadLocal-bound)
     +-- The EntityManager is the SAME one bound by @Transactional
     +-- NO additional database lookup for the proxy (it's a singleton)
```

## 7. Source Code Reading Guide

### Critical Files to Read (In Order)

```
1. org.springframework.data.jpa.repository.support.SimpleJpaRepository
   spring-data-jpa/.../support/SimpleJpaRepository.java (~700 lines)
   -> Implementation of all CRUD methods (save, findById, findAll, delete)
   -> Uses injected EntityManager (JPA standard)
   -> Key insight: save() calls entityManager.persist() or entityManager.merge()
      based on whether the entity is new (ID null or primitive zero)

2. org.springframework.data.jpa.repository.support.JpaRepositoryFactory
   spring-data-jpa/.../support/JpaRepositoryFactory.java (~400 lines)
   -> getRepository() -- creates the proxy for your repository interface
   -> getTargetRepository() -- returns SimpleJpaRepository
   -> getQueryLookupStrategy() -- determines how queries are resolved

3. org.springframework.data.repository.core.support.RepositoryFactorySupport
   spring-data-commons/.../support/RepositoryFactorySupport.java (~1200 lines)
   -> getRepository() -- the top-level proxy creation logic
   -> QueryExecutorMethodInterceptor inner class -- routes custom queries

4. org.springframework.data.jpa.repository.query.JpaQueryLookupStrategy
   spring-data-jpa/.../query/JpaQueryLookupStrategy.java (~300 lines)
   -> resolveQuery() -- determines query type for a method
   -> Creates: PartTreeJpaQuery | SimpleJpaQuery | NativeJpaQuery

5. org.springframework.data.repository.query.parser.PartTree
   spring-data-commons/.../parser/PartTree.java (~400 lines)
   -> Parses method names into query predicates
   -> Supports: findBy, readBy, queryBy, countBy, existsBy, deleteBy
   -> Predicates: And, Or, Between, LessThan, GreaterThan, Like, In, etc.

6. org.springframework.data.jpa.repository.query.PartTreeJpaQuery
   spring-data-jpa/.../query/PartTreeJpaQuery.java (~200 lines)
   -> Translates PartTree into JPA Criteria or JPQL

7. org.springframework.transaction.support.AbstractPlatformTransactionManager
   spring-tx/.../AbstractPlatformTransactionManager.java (~900 lines)
   -> getTransaction() -- handles propagation logic
   -> commit(), rollback() -- the commit/rollback flow
   -> handleExistingTransaction() -- REQUIRED, REQUIRES_NEW, NESTED logic

8. org.springframework.orm.jpa.JpaTransactionManager
   spring-orm/.../JpaTransactionManager.java (~1000 lines)
   -> doBegin() -- creates EntityManager, gets JDBC connection, begins TX
   -> doCommit() -- flushes, commits
   -> doRollback() -- rolls back, clears session

9. org.springframework.transaction.interceptor.TransactionInterceptor
   spring-tx/.../interceptor/TransactionInterceptor.java (~200 lines)
   -> invoke() -- the AOP advice entry point

10. org.springframework.transaction.interceptor.TransactionAspectSupport
    spring-tx/.../TransactionAspectSupport.java (~500 lines)
    -> invokeWithinTransaction() -- the core interception logic
    -> currentTransactionStatus() -- ThreadLocal access

11. com.zaxxer.hikari.pool.HikariPool
    HikariCP/.../pool/HikariPool.java (~900 lines)
    -> getConnection() -- borrow from pool
    -> recycle() -- return to pool
    -> HouseKeeper -- eviction thread
    -> fillPool() -- connection creation

12. com.zaxxer.hikari.pool.PoolEntry
    HikariCP/.../pool/PoolEntry.java (~400 lines)
    -> Wraps a JDBC Connection with pool metadata
    -> Tracks borrow time, last access time, state (IN_USE/NOT_IN_USE/reserved)

13. com.zaxxer.hikari.util.ConcurrentBag
    HikariCP/.../util/ConcurrentBag.java (~400 lines)
    -> The core lock-free data structure (using ThreadLocal for fast path)
    -> borrow() -- get available entry or create new
    -> requite() -- return entry to pool

14. org.hibernate.engine.internal.StatefulPersistenceContext
    hibernate-core/.../internal/StatefulPersistenceContext.java (~700 lines)
    -> The L1 cache implementation
    -> dirty-check: finds dirty entities by comparing to snapshots
    -> entity snapshots management

15. org.hibernate.internal.SessionImpl
    hibernate-core/.../internal/SessionImpl.java (~1800 lines)
    -> flush() -- the dirty-check + SQL generation orchestration
    -> autoFlushIfRequired() -- called before queries
    -> getEntitySnapshot() -- for dirty checking
```

## 8. Production Failure Scenarios

### Scenario 1: "Transaction marked as rollback-only" / UnexpectedRollbackException

**Symptom**: `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`. The outer transaction commits successfully (no exception), but the database changes are gone.

**Root cause**: An inner `@Transactional` method with `REQUIRED` propagation caught a `RuntimeException`, preventing it from propagating to the outer method. But the exception already marked the transaction as rollback-only. The outer method thinks everything succeeded, but the entire transaction rolls back on commit.

```java
// BROKEN:
@Service
public class OrderService {
    @Transactional  // TX1
    public void placeOrder(Order order) {
        orderRepo.save(order);  // INSERT into TX1
        
        try {
            paymentService.charge(order.getPayment());  // Joins TX1
        } catch (PaymentFailedException e) {
            // Caught the exception! But TX1 is now marked rollback-only
            order.setStatus(OrderStatus.PAYMENT_FAILED);
        }
        
        // This line executes. No exception. Everything looks fine.
        orderRepo.save(order);
        // But on commit: UnexpectedRollbackException!
        // TX1 was marked rollback-only by the PaymentFailedException
    }
}

// FIX 1: Let the exception propagate
// FIX 2: Use REQUIRES_NEW for the risky sub-method:
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void charge(PaymentMethod payment) {
    // Own transaction. If it fails, TX2 rolls back but TX1 continues.
}

// FIX 3: Check transaction status before proceeding:
TransactionStatus status = TransactionAspectSupport.currentTransactionStatus();
if (status.isRollbackOnly()) {
    // Don't proceed with business logic, throw or handle
}
```

### Scenario 2: Connection Pool Exhaustion Under Load

**Symptom**: Application returns 5xx errors with "Connection is not available, request timed out after 30000ms" under moderate load. Thread dump shows all HikariCP connections in use.

**Root cause analysis checklist**:
1. Is `maximumPoolSize` too small? Default is 10. For a service handling 100 concurrent requests, you need more.
2. Are connections held too long? Check for non-database work inside `@Transactional` methods.
3. Are connections leaking? Check `hikaricp.connections.active` gauge — does it keep climbing?
4. Are there deadlocked transactions holding connections?

```java
// POOL TUNING:
// spring.datasource.hikari.maximum-pool-size=20
// spring.datasource.hikari.minimum-idle=10
// spring.datasource.hikari.connection-timeout=5000  // Fail fast, don't wait 30s
// spring.datasource.hikari.idle-timeout=300000
// spring.datasource.hikari.max-lifetime=600000
// spring.datasource.hikari.leak-detection-threshold=10000  // Detect leaks >10s

// POOL SIZING FORMULA:
// pool_size = Tn * (Cm - 1) + 1
// Where:
//   Tn = max number of threads (Tomcat maxThreads or virtual thread count)
//   Cm = max number of simultaneous connections held by a single thread
// 
// For standard @Transactional with REQUIRED: Cm = 1
//   -> pool_size should be >= Tn
// For REQUIRES_NEW (holds 2 connections in outer+inner): Cm = 2
//   -> pool_size should be >= Tn * 2
// Default HikariCP pool size (10) is too small for most production apps.

// MONITORING QUERY:
// SELECT count(*), state FROM pg_stat_activity GROUP BY state;
// Shows how many connections are idle, active, idle in transaction, etc.
```

### Scenario 3: N+1 Query in Production Causes DB CPU Spike

**Symptom**: Database CPU hits 100% when a specific list endpoint is called. The endpoint returns normally for a few records, but times out for larger result sets.

```java
// Root cause: Lazy loading in a loop
@Transactional(readOnly = true)
public List<OrderResponse> findAllOrders() {
    List<Order> orders = orderRepo.findAll();          // 1 query: SELECT * FROM orders
    
    return orders.stream()
            .map(order -> {
                String customerName = order.getCustomer().getName(); // +1 query
                int itemCount = order.getItems().size();              // +1 query
                Money total = order.getTotal();                       // 0 queries (loaded)
                return new OrderResponse(order.getId(), customerName, 
                        itemCount, total);
            })
            .toList();
    // 10 orders -> 21 queries
    // 100 orders -> 201 queries
    // 1000 orders -> 2001 queries (database meltdown)
}

// FIX: Single query with JOIN FETCH
@Query("SELECT o FROM Order o " +
       "JOIN FETCH o.customer " +
       "LEFT JOIN FETCH o.items")
List<Order> findAllWithCustomerAndItems();
// 1 query: SELECT o.*, c.*, i.* FROM orders o
//          JOIN customers c ON o.customer_id = c.id
//          LEFT JOIN order_items i ON o.id = i.order_id
// Warning: Cartesian product with multiple joins can return many rows.
// Solution: use @BatchSize, multiple queries, or DTO projection.
```

### Scenario 4: Deadlock in Pessimistic Locking

**Symptom**: Two transactions deadlock, one is killed by the database (deadlock detector), the survivor continues but the killed one gets an error.

```java
// How deadlocks happen with @Lock(LockModeType.PESSIMISTIC_WRITE):

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}

// Transaction 1:                          Transaction 2:
// orderRepo.findByIdForUpdate(1L);  // locks row 1
//                                          orderRepo.findByIdForUpdate(2L);  // locks row 2
// orderRepo.findByIdForUpdate(2L);  // waits for T2 to release row 2
//                                          orderRepo.findByIdForUpdate(1L);  // waits for T1 to release row 1
// DEADLOCK! Database detects and kills one transaction.

// PREVENTION:
// 1. Always lock in the same order (e.g., sort IDs before locking)
// 2. Use optimistic locking (@Version) instead of pessimistic
// 3. Use lock timeout: @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
// 4. Keep pessimistic lock duration minimal
```

## 9. Debugging Techniques

### Enabling SQL Logging for Query Analysis

```properties
# application.properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.properties.hibernate.use_sql_comments=true
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
logging.level.org.hibernate.type.descriptor.sql.BasicExtractor=TRACE
logging.level.org.hibernate.stat=DEBUG
```

### Using datasource-proxy for Query Counting

```java
// Add dependency: com.github.gavlyukovskiy:datasource-proxy-spring-boot-starter
// Logs every query with execution time:
// Name:MyDS, Time:15, Success:True, Type:Prepared, Batch:False, 
// QuerySize:1, BatchSize:0
// Query:["select o1_0.id, o1_0.status from orders o1_0 where o1_0.id=?"]
// Params:[(42)]

// Custom datasource-proxy listener to count queries per request:
@Component
public class QueryCountListener implements QueryExecutionListener {
    private final ThreadLocal<Integer> queryCount = ThreadLocal.withInitial(() -> 0);
    
    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        queryCount.set(queryCount.get() + 1);
    }
    
    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        if (queryCount.get() > 50) {
            log.warn("HIGH QUERY COUNT: {} queries in request {}",
                    queryCount.get(), MDC.get("traceId"));
        }
    }
    
    public int getAndReset() {
        int count = queryCount.get();
        queryCount.remove();
        return count;
    }
}
```

### Debugging Transaction Boundaries

```java
// Use TransactionSynchronizationManager to log transaction state:

@Aspect
@Component
public class TransactionDebugAspect {
    
    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object logTransaction(ProceedingJoinPoint pjp) throws Throwable {
        boolean active = TransactionSynchronizationManager
                .isActualTransactionActive();
        String name = TransactionSynchronizationManager
                .getCurrentTransactionName();
        boolean readOnly = TransactionSynchronizationManager
                .isCurrentTransactionReadOnly();
        Integer isolation = TransactionSynchronizationManager
                .getCurrentTransactionIsolationLevel();
        
        System.out.printf("[TX] %s | active=%s | readOnly=%s | isolation=%s%n",
                name, active, readOnly, isolation);
        
        Object result = pjp.proceed();
        
        System.out.printf("[TX] %s | completed%n", name);
        return result;
    }
}
```

### Identifying Connection Leaks

```java
// Enable HikariCP leak detection:
// spring.datasource.hikari.leak-detection-threshold=5000  (5 seconds)

// Output when leak detected:
// [HikariPool-1 housekeeper] WARN  com.zaxxer.hikari.pool.ProxyLeakTask
//   Connection leak detection triggered for conn 1625737265 on thread 
//   http-nio-8080-exec-5, stack trace follows:
//   java.lang.Exception: Apparent connection leak detected
//       at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:185)
//       at com.example.OrderService.generateReport(OrderService.java:42)
//       ... (full stack trace of the borrow point)

// Manual leak detection check:
@EventListener(ContextRefreshedEvent.class)
public void checkPoolStats() {
    HikariDataSource ds = (HikariDataSource) dataSource;
    HikariPoolMXBean poolBean = ds.getHikariPoolMXBean();
    
    log.info("Pool stats: active={}, idle={}, waiting={}, total={}",
            poolBean.getActiveConnections(),
            poolBean.getIdleConnections(),
            poolBean.getThreadsAwaitingConnection(),
            poolBean.getTotalConnections());
}
```

## 10. Observability Considerations

### HikariCP Micrometer Metrics (Auto-Configured)

```
  Key HikariCP metrics surfaced by Spring Boot Actuator + Micrometer:
  
  hikaricp.connections         (gauge, tags: pool=HikariPool-1)
    -> state=active: connections currently borrowed
    -> state=idle: connections available in pool
    -> state=pending: threads waiting for a connection
  
  hikaricp.connections.max     (gauge) -> configured maximumPoolSize
  hikaricp.connections.min     (gauge) -> configured minimumIdle
  
  hikaricp.connections.usage   (gauge, tags: pool, state=active/idle)
  hikaricp.connections.creation (counter) -> connections created
  hikaricp.connections.timeout  (counter) -> connections timed out waiting
  hikaricp.connections.acquire  (timer) -> time to acquire a connection

  ALERTING RULES:
  +------------------------------------------------------------------+
  | * hikaricp_connections_active / hikaricp_connections_max > 0.8  |
  |   -> Warning: Pool is 80% utilized                               |
  |                                                                  |
  | * hikaricp_connections_pending > 0 for > 5 minutes              |
  |   -> Critical: Threads waiting for connections                   |
  |                                                                  |
  | * hikaricp_connections_timeout_total > 0 in past 5m             |
  |   -> Critical: Connection timeouts occurring                    |
  |                                                                  |
  | * hikaricp_connections_active == maximumPoolSize for > 2 min    |
  |   -> Warning: Pool fully exhausted, possible leak or overload    |
  +------------------------------------------------------------------+
```

### Custom Transaction Metrics

```java
@Component
public class TransactionMetricsCollector {
    private final MeterRegistry registry;
    
    public TransactionMetricsCollector(MeterRegistry registry) {
        this.registry = registry;
        // Register a global TransactionSynchronization listener
    }
    
    public <T> T instrumentTransaction(String txName, Supplier<T> operation) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = operation.get();
            sample.stop(registry.timer("transaction.duration",
                    "name", txName, "outcome", "SUCCESS"));
            registry.counter("transaction.count",
                    "name", txName, "outcome", "SUCCESS").increment();
            return result;
        } catch (Exception e) {
            sample.stop(registry.timer("transaction.duration",
                    "name", txName, "outcome", "ROLLBACK",
                    "exception", e.getClass().getSimpleName()));
            registry.counter("transaction.count",
                    "name", txName, "outcome", "ROLLBACK",
                    "exception", e.getClass().getSimpleName()).increment();
            throw e;
        }
    }
}
```

### Query Performance Monitoring

```java
// Hibernate statistics (enable for analysis, disable in production):
// spring.jpa.properties.hibernate.generate_statistics=true
// log:
// Hibernate: 
//     Transactions: 142
//     Sessions: 142
//     Session close count: 142
//     Flushes: 128
//     Connections obtained: 142
//     Statements prepared: 284
//     Statements closed: 284
//     Second Level Cache puts: 0
//     Second Level Cache hits: 0
//     Second Level Cache misses: 0
//     Entities loaded: 1420
//     Entities updated: 54
//     Entities inserted: 23
//     Entities deleted: 0
//     Entities fetched (minimize this): 0
//     Collections loaded: 0
//     Collections updated: 0
//     Collections removed: 0
//     Collections recreated: 0
//     Collections fetched (minimize this): 0
//     Queries executed to database: 85
//     Query cache puts: 0
//     Query cache hits: 0
//     Query cache misses: 0
//     Max query time: 45ms
```

## 11. Performance Implications

### Transaction Duration vs Database Lock Duration

```
  +------------------------------------------------------------------+
  |           TRANSACTION DURATION = DATABASE LOCK DURATION           |
  |                                                                  |
  |  Every millisecond inside a transaction with write intent:       |
  |    * Row-level locks held on modified rows (PostgreSQL, MySQL)   |
  |    * Index locks held on modified indexes                        |
  |    * Gap locks may be held depending on isolation level          |
  |    * Connection is borrowed from pool (others cannot use it)     |
  |                                                                  |
  |  RULE: Transaction time = business logic time.                   |
  |        Minimize business logic time within transactions.         |
  |                                                                  |
  |  DO inside @Transactional:                                       |
  |    * Database reads and writes                                   |
  |    * Domain logic (pure computation on loaded data)              |
  |    * Calling other @Transactional methods (joins same TX)        |
  |                                                                  |
  |  DO NOT inside @Transactional:                                   |
  |    * HTTP calls to external services (can take seconds)          |
  |    * File I/O                                                     |
  |    * Sending emails                                               |
  |    * Long-running computations                                   |
  |    * Waiting on message queue responses                          |
  |                                                                  |
  |  Pattern: Load data in TX, close TX, process, open new TX, save: |
  |                                                                  |
  |  @Transactional                                                   |
  |  public OrderData loadOrder(Long id) {                            |
  |      return orderRepo.findOrderData(id);  // TX commits here     |
  |  }                                                                |
  |                                                                  |
  |  public Report generateReport(OrderData data) {                  |
  |      // No transaction. Can take seconds.                        |
  |      return pdfGenerator.generate(data);                          |
  |  }                                                                |
  |                                                                  |
  |  @Transactional                                                   |
  |  public void saveReport(Long orderId, Report report) {            |
  |      reportRepo.save(new ReportEntity(orderId, report));         |
  |  }                                                                |
  +------------------------------------------------------------------+
```

### HikariCP vs Other Connection Pools

```
  +----------+-----------+-----------+-----------+-----------+
  | Pool     | Perf      | Memory    | Features  | Best For  |
  +----------+-----------+-----------+-----------+-----------+
  | HikariCP | Optimized | Very low  | Leak det.,| Default   |
  |          | bytecode  | footprint | metrics,  | choice.   |
  |          | level     |           | fast path | Spring    |
  |          |           |           |           | Boot def. |
  +----------+-----------+-----------+-----------+-----------+
  | Tomcat   | Moderate  | Medium    | JMX,      | Legacy    |
  | Pool     |           |           | intercept.| apps      |
  +----------+-----------+-----------+-----------+-----------+
  | DBCP2    | Slower    | Higher    | Most      | Older     |
  |          |           |           | features  | Spring    |
  +----------+-----------+-----------+-----------+-----------+
  | Vibur    | Good      | Low       | SQL       | Specific  |
  | DBCP     |           |           | logging   | needs     |
  +----------+-----------+-----------+-----------+-----------+

  HikariCP optimizations:
  * Bytecode-level optimization (Javassist/CGLIB proxy generation
    is replaced with hand-crafted bytecode for ProxyConnection)
  * ConcurrentBag: lock-free data structure for connection storage
    (uses ThreadLocal for fast path, CAS for contention)
  * Connection validation uses JDBC4 isValid() (fast) instead of test query
```

## 12. Architecture Implications

### Separate Read and Write DataSources (CQRS at Persistence Layer)

```java
@Configuration
public class DataSourceConfiguration {
    
    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.write")
    public DataSource writeDataSource() {
        return DataSourceBuilder.create().build();
    }
    
    @Bean
    @ConfigurationProperties("spring.datasource.read")
    public DataSource readDataSource() {
        return DataSourceBuilder.create().build();
    }
    
    @Bean
    public DataSource routingDataSource() {
        ReadWriteRoutingDataSource router = new ReadWriteRoutingDataSource();
        router.setDefaultTargetDataSource(writeDataSource());
        
        Map<Object, Object> targets = new HashMap<>();
        targets.put("WRITE", writeDataSource());
        targets.put("READ", readDataSource());
        router.setTargetDataSources(targets);
        
        return router;
    }
}

// RoutingDataSource that decides at transaction level:
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        boolean readOnly = TransactionSynchronizationManager
                .isCurrentTransactionReadOnly();
        return readOnly ? "READ" : "WRITE";
        // @Transactional(readOnly=true) -> routes to replica
        // @Transactional -> routes to primary
    }
}
```

### When to Use JPA vs jOOQ vs MyBatis vs JDBC Template

```
  +----------+----------+----------+----------+-----------+
  | Tool     | Best For | Query    | Type     | Migration |
  |          |          | Power    | Safety   | Cost      |
  +----------+----------+----------+----------+-----------+
  | JPA/     | CRUD,    | Good     | Compile- | Low       |
  | Hibernate| simple   | (JPQL,   | time     |           |
  |          | queries  | Criteria)| (metamod)|           |
  +----------+----------+----------+----------+-----------+
  | jOOQ     | Complex  | Excellent| Compile- | Medium    |
  |          | queries, | (type-   | time     |           |
  |          | reporting| safe SQL)| (codegen)|           |
  +----------+----------+----------+----------+-----------+
  | MyBatis  | Complex  | Good     | None     | Low       |
  |          | queries, | (XML/    | (string  |           |
  |          | existing | annot.) | SQL)     |           |
  |          | schemas  |          |          |           |
  +----------+----------+----------+----------+-----------+
  | JDBC     | Simple,  | Low      | None     | None      |
  | Template | high-perf| (manual) | (string  |           |
  |          |          |          | SQL)     |           |
  +----------+----------+----------+----------+-----------+

  Decision: Start with JPA. Introduce jOOQ when you need:
    * Complex reporting queries (multi-table joins, window functions)
    * Database-specific features (PostgreSQL JSONB, full-text search)
    * Batch operations with bulk INSERT/UPDATE/MERGE
  Don't add jOOQ prematurely. JPA handles 80% of queries well.
```

## 13. Team Ownership Implications

```
  +------------------------------------------------------------------+
  |                     OWNERSHIP MATRIX                               |
  |                                                                  |
  |  Platform Team Owns:                                             |
  |  +-- HikariCP pool configuration (max size, timeouts, metrics)   |
  |  +-- Transaction manager configuration                            |
  |  +-- Datasource proxy (datasource-proxy for query logging)       |
  |  +-- Entity scanning configuration and naming strategy           |
  |  +-- Hibernate properties (dialect, ddl-auto, batch size)        |
  |  +-- @Transactional default conventions (readOnly, rollbackFor)  |
  |  +-- Outbox pattern library (shared outbox publisher)            |
  |  +-- ArchUnit rules: no @Transactional on controllers           |
  |                                                                  |
  |  Service Team Owns:                                              |
  |  +-- Entity definitions (@Entity, relationships, fetch types)   |
  |  +-- Repository interfaces (custom queries, Specifications)     |
  |  +-- @Transactional boundaries on service methods               |
  |  +-- Propagation level choices for specific use cases           |
  |  +-- Custom query methods (@Query, named queries)               |
  |  +-- N+1 detection and optimization for their endpoints         |
  |                                                                  |
  |  DBA/Data Team Owns:                                             |
  |  +-- Database schema (Liquibase/Flyway migrations)              |
  |  +-- Index strategy (review JPA-generated queries, add indexes) |
  |  +-- Slow query log analysis and optimization                   |
  |  +-- Connection pool sizing recommendations                     |
  |  +-- Deadlock analysis and prevention                            |
  +------------------------------------------------------------------+
```

## 14. Interview Questions

### Question 1: "Explain Spring's transaction propagation levels. When would you use REQUIRES_NEW vs NESTED? What are the dangers of REQUIRES_NEW?"

**Staff-level answer**: Spring's seven propagation levels define how a transactional method relates to an existing transaction. The most important are REQUIRED (default, joins or creates), REQUIRES_NEW (suspends current, creates independent), and NESTED (creates a savepoint within current).

REQUIRES_NEW is appropriate when a sub-operation must commit independently regardless of the outer transaction outcome. Classic examples: audit logging (you always want to record that an operation was *attempted*, even if it failed), outbox event publishing (the event must survive a business transaction rollback so it can trigger compensation), and idempotency key tracking (you must persist that a key was used even if the business operation fails, to prevent replay). The mechanism: `AbstractPlatformTransactionManager` calls `suspend()` which unbinds the current transaction's resources from ThreadLocal and stores them in a `SuspendedResourcesHolder`, then creates a completely new transaction with a new database connection. After the inner method returns, the suspended transaction is resumed by re-binding its resources.

NESTED creates a JDBC savepoint within the current transaction. It's appropriate when you want to roll back a sub-operation without losing the outer transaction's work — but critically, it only works with a single physical database transaction. Use cases: batch processing where individual items can fail (try each item in a nested transaction, rollback failed items, batch continues), and multi-step operations where intermediate failures should be compensated but not by rolling back everything.

The dangers of REQUIRES_NEW: First, it requires an additional database connection. If the outer transaction holds one connection and the inner REQUIRES_NEW acquires another, you now have two connections in use per thread. With 10 pool connections and 6 concurrent threads using REQUIRES_NEW, you can have 12 connections needed but only 10 available — deadlock. Pool sizing must account for (max concurrency × max nested REQUIRES_NEW depth). Second, REQUIRES_NEW creates independent transactions that can see inconsistent data. If TX1 inserts a row then TX2 (REQUIRES_NEW) tries to read it before TX1 commits, TX2 won't see it (assuming READ_COMMITTED). Third, suspend/resume has overhead — ThreadLocal manipulation, resource rebinding — that adds latency. Fourth, if the outer transaction rolls back after the inner committed, you have a partial-commit situation that must be handled at the business level (compensating transactions).

The critical insight: REQUIRES_NEW is a tool for deliberate transaction decoupling, not a band-aid for transaction management problems. If you're using it to "fix" rollback-only issues, you're papering over a design problem.

### Question 2: "Walk through what happens from the moment orderRepo.findById(42L) is called to the moment the Order entity is returned. Cover the Spring Data proxy, query resolution, Hibernate session, connection pool, and SQL execution."

**Staff-level answer**: The `orderRepo` reference is not an `OrderRepositoryImpl` — it's a JDK dynamic proxy created by `JpaRepositoryFactory.getRepository()`. When `findById(42L)` is called, the proxy's `InvocationHandler` intercepts the call and routes it based on the method. Since `findById` is a standard CRUD method declared by `JpaRepository`, the handler dispatches it directly to the `SimpleJpaRepository` target implementation — no query lookup strategy is needed.

Inside `SimpleJpaRepository.findById(42L)`, the call is `entityManager.find(Order.class, 42L)`. The `EntityManager` is not a global singleton — it's the `SharedEntityManagerInvocationHandler` proxy that delegates to the *current* transactional `EntityManager` obtained from `TransactionSynchronizationManager.getResource(entityManagerFactory)`. If there's an active transaction, this returns the `EntityManagerHolder` bound to the current thread. If there's no transaction, it creates a new short-lived `EntityManager` just for this operation (or reuses one via `EntityManagerFactoryUtils`).

The Hibernate `SessionImpl.find()` first checks the PersistenceContext (Level 1 cache) for an entity with key `{Order, 42}`. If found, it returns the cached instance — no database query. If not found, it creates a `LoadEvent` and dispatches through Hibernate's event listener chain to `DefaultLoadEventListener.load()`, which creates a `SELECT ... FROM orders WHERE id = ?` query. The query goes through Hibernate's query plan cache (for compiled SQL) and then to `JdbcResourceRegistry` to get a JDBC `Connection`.

The connection is obtained from `HikariPool.getConnection()`. HikariCP first attempts a fast-path borrow from its `ConcurrentBag` using a ThreadLocal hash set to find locally cached idle connections. If found and valid (JDBC4 `isValid()` within 5 seconds), it returns immediately — this is the common fast path (~1 microsecond). If no local connection, it scans the shared pool with CAS operations. If the pool is at maximum and no idle connection exists, the calling thread is parked (`LockSupport.parkNanos`) with the configured `connectionTimeout`. When a connection is returned by another thread, the parked thread is unparked.

Once a JDBC `Connection` is obtained, Hibernate creates a `PreparedStatement`, binds the parameter (42L), executes the query, and iterates the `ResultSet`. For each row, Hibernate constructs an `Order` entity (using the no-arg constructor and direct field access via reflection, or if a proxy is needed, via `JavassistLazyInitializer`). The entity is stored in the PersistenceContext keyed by `EntityKey("Order", 42L)`, and a snapshot (deep copy of all property values) is taken for dirty checking. The entity is returned to `SimpleJpaRepository.findById()`, which wraps it in `Optional.of(order)` and returns it to the caller.

The entire chain is: application code → proxy (routing) → SimpleJpaRepository → EntityManager proxy (ThreadLocal lookup) → Hibernate Session → L1 cache check → query plan cache → HikariCP (fast path or park) → JDBC → database → ResultSet → entity construction → L1 cache storage → snapshot → return. Each layer adds overhead, but the cumulative overhead is typically 1-5ms for a simple primary key lookup.

### Question 3: "Explain the N+1 query problem. How does it happen, how do you detect it in production, and what are all the available fixes with their trade-offs?"

**Staff-level answer**: The N+1 query problem occurs when an ORM executes 1 query to fetch parent entities, then N additional queries to fetch their lazy-loaded associations, resulting in N+1 total round trips to the database. It happens because Hibernate's default fetch strategy for `@OneToMany` and `@ManyToOne` is `FetchType.LAZY`, meaning associated entities are loaded on first access — and if you access them in a loop, each access triggers a separate query.

Detection in production requires query-level observability. Enable `spring.jpa.show-sql=true` and `logging.level.org.hibernate.SQL=DEBUG` in lower environments, but in production, use datasource-proxy which provides a non-intrusive query counter per HTTP request. A spike from 3 queries/request to 300 queries/request when result count increases from 10 to 100 is the N+1 signature. Database-level detection: `pg_stat_statements` in PostgreSQL or `performance_schema` in MySQL reveals high counts of identical parameterized queries (e.g., `SELECT * FROM order_items WHERE order_id = $1` executed 1000 times in a short window).

The fixes and their trade-offs:

JOIN FETCH (`JOIN FETCH o.items` in JPQL): Pros: single query, simple, explicit. Cons: Cartesian product with multiple joins (100 orders × 10 items = 1000 rows returned and materialized). Cannot use multiple `JOIN FETCH` on parallel collections (Hibernate throws `MultipleBagFetchException`). For multiple collections, use `Set` instead of `List` or use multiple queries.

@EntityGraph: Pros: declarative, reusable, composes with existing queries. Cons: same Cartesian product issue as JOIN FETCH. Limited to static definitions (or programmatic `EntityGraph` creation).

@BatchSize: Pros: reduces N+1 to N/batchSize+1 queries. Does not cause Cartesian products. Cons: still executes multiple queries (just fewer). Batch size must be tuned — too large creates giant IN clauses, too small doesn't reduce query count enough.

DTO Projection: Pros: single query selecting only needed columns. No entity graph traversal, no lazy loading. Best performance. Cons: loses entity lifecycle (no dirty checking, no cascading). Different code path from entity-based logic. More boilerplate if using constructor expressions.

Subselect fetching (`@Fetch(FetchMode.SUBSELECT)`): Pros: one additional query for all children using a sub-select (`WHERE order_id IN (SELECT id FROM orders WHERE ...)`). Cons: Hibernate-specific. Only works with a single parent query context. Subselect may be less efficient than batch fetch on indexed columns.

The Staff-level recommendation: Start with `JOIN FETCH` or `@EntityGraph` for single-association eager loading. When Cartesian products become a problem (multiple collections, large join results), switch to `@BatchSize` with batch fetching. When query shape optimization is critical (high-throughput read endpoints), use DTO projections with explicit SQL or jOOQ. Always code review loops that access lazy-loaded associations — if you see `entity.getCollection().size()` inside a `for` loop without an `@EntityGraph` on the enclosing query method, it's an N+1 waiting to happen.

## 15. Hands-On Exercises

1. **Create a repository with method naming queries, @Query, Specifications, and native SQL**:
   Define an `OrderRepository` with: (a) `findByCustomerIdAndStatus(Long customerId, OrderStatus status)` (method naming), (b) `@Query("SELECT o FROM Order o WHERE o.total.amount > :minAmount")` (JPQL), (c) `@Query(value = "SELECT * FROM orders WHERE EXTRACT(YEAR FROM created_at) = :year", nativeQuery = true)` (native SQL), (d) a `Specification<Order>` builder that composes `hasStatus`, `createdBetween`, and `totalGreaterThan` dynamically. Write integration tests verifying all four approaches produce correct SQL and return correct results.

2. **Trace transaction propagation behavior with unit tests**:
   Create two services: `OuterService.outerMethod()` (@Transactional) and `InnerService.innerMethod()` (configurable propagation). Write tests with different propagation levels (REQUIRED, REQUIRES_NEW, NESTED, MANDATORY, NEVER) and assertions that verify: (a) whether inner method joins or gets its own transaction, (b) whether inner method failure rolls back the outer method, (c) whether the outer method commit succeeds after inner failure. Use `TransactionSynchronizationManager.isActualTransactionActive()` and `TransactionAspectSupport.currentTransactionStatus()` to inspect transaction state.

3. **Reproduce and fix an N+1 query**:
   Create an `Order` entity with lazy-loaded `List<OrderItem>` (OneToMany) and `Customer` (ManyToOne). Write a service method that loads 50 orders and iterates them to build a response containing customer name and item count. Use datasource-proxy or `spring.jpa.show-sql` to observe the N+1 pattern (1 + 50 + 50 = 101 queries). Fix it with: (a) JOIN FETCH, (b) @EntityGraph, (c) @BatchSize on both associations. Benchmark all three fixes and compare query counts and execution times.

4. **Configure and test HikariCP connection pool behavior under load**:
   Set `maximumPoolSize=5`, `connectionTimeout=2000`, and `leakDetectionThreshold=3000`. Write a load test (using `CountDownLatch` and threads) that simulates 20 concurrent requests, each holding a connection for a random 100-500ms. Observe connection timeouts when pool is exhausted. Enable Micrometer metrics and create a Grafana dashboard showing active/idle/pending connections over time. Add a simulated connection leak (never close the connection) and observe the leak detection log.

5. **Implement the outbox pattern for reliable event publishing**:
   Create an `OutboxEvent` entity and `OutboxEventRepository`. In your `OrderApplicationService.placeOrder()`, write both the order and an outbox event in the same transaction. Implement a `@Scheduled` poller that reads unpublished outbox events (oldest 100 at a time), publishes them to a `BlockingQueue` (simulating a message broker), and marks them published. Handle: duplicate publication (consumer idempotency), poller concurrency (multiple instances), and failure mid-batch (at-least-once semantics). Write an integration test verifying that if the database transaction commits but the broker is down, events are re-published on next poll.

6. **Implement a read/write routing datasource**:
   Configure two HikariCP datasources: primary (write) and replica (read). Write a `ReadWriteRoutingDataSource` extending `AbstractRoutingDataSource` that routes based on `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`. Annotate read service methods with `@Transactional(readOnly=true)` and write methods with `@Transactional`. Verify via datasource-proxy that reads hit the replica datasource and writes hit the primary. Handle the case where read operations need up-to-date data (use `@Transactional` without `readOnly` to force routing to primary).

## 16. Advanced Challenges

1. **Build a custom Spring Data repository implementation that uses jOOQ behind the scenes**:
   Create a `JooqOrderRepository` that implements a custom `OrderRepositoryCustom` interface. Use jOOQ code generation to produce type-safe query DSL classes from your database schema. Implement: (a) a complex reporting query with multi-table joins, window functions, and CTEs that would be impractical in JPQL, (b) a batch INSERT using jOOQ's batch API that processes 1000 orders in a single call, (c) transparent integration so callers use the same `OrderRepository` interface but get jOOQ execution for custom methods. Benchmark against the equivalent JPA implementation and document the performance difference.

2. **Implement a transaction manager that coordinates between JPA and a NoSQL database**:
   Build a custom `PlatformTransactionManager` that coordinates a JPA transaction with a MongoDB or Redis transaction for a dual-write scenario (e.g., order saved in PostgreSQL + cached in Redis). Use the transaction synchronization pattern: register a `TransactionSynchronization` that, on `beforeCommit`, executes a Redis MULTI/EXEC block, and on `afterCommit`, performs any post-commit cleanup. Handle: JPA commit succeeds but Redis commit fails (compensate by rolling back Redis manually), Redis commit succeeds but JPA commit fails (the JPA rollback is automatic, Redis must be compensated). Implement a two-phase commit simulation that tries Redis first, then JPA; if JPA fails, issue a Redis UNWATCH/DISCARD.

3. **Create a "Query Anomaly Detector" that identifies problematic repository usage at build time**:
   Build an annotation processor or ArchUnit test suite that scans the codebase for: (a) repository methods without `@EntityGraph` or `JOIN FETCH` that return entities with lazy-loaded collections accessed by callers, (b) `@Transactional` methods that call external HTTP clients, (c) `REQUIRES_NEW` methods called in loops (potential connection exhaustion), (d) `@Query` annotations with string concatenation (SQL injection risk), (e) methods that mix `@Modifying` with SELECT-style return types. Generate a report and optionally fail the build if severity thresholds are exceeded.

4. **Implement a "Transaction Trace Recorder" that records every SQL statement within a transaction**:
   Build a `TransactionSynchronization` that, when registered during `beforeCommit`, collects all SQL statements executed in the current transaction using datasource-proxy's listener API. Serialize the trace to JSON and store it in a `transaction_traces` table (in a separate transaction/datasource). Add trace metadata: transaction name, duration, number of statements, number of rows affected, whether it committed or rolled back. Build a query endpoint that retrieves traces for a given time window and visualizes slow or high-statement transactions. Handle high volume: sample only transactions exceeding a duration or statement count threshold.

5. **Design a multi-tenant persistence layer with per-tenant connection pool isolation**:
   Implement multi-tenancy at the connection pool level: each tenant gets its own HikariCP pool (separate `DataSource`). Build a `TenantRoutingDataSource` that resolves the tenant from a `TenantContext` (ThreadLocal, set by a Filter). At startup, read tenant database credentials from a secrets manager. Support: dynamic tenant addition (new pool created on the fly), tenant removal (pool drained and closed gracefully), per-tenant pool metrics (separate Micrometer tags), and per-tenant connection limits (prevent one noisy tenant from starving others). Handle Spring's singleton bean model: repositories and services are shared across tenants, but the DataSource routes to the correct tenant pool at connection acquisition time.
