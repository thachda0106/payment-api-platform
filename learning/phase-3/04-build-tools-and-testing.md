# Module 04 — Build Tools, Testing & Profiling

## 4.1 Maven

### POM Structure

```xml
<project>
    <groupId>com.paymentapi</groupId>
    <artifactId>payment-service</artifactId>
    <version>1.0.0</version>

    <properties>
        <java.version>21</java.version>
        <spring-boot.version>3.3.0</spring-boot.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### Maven Lifecycle

```
validate → compile → test → package → verify → install → deploy
```

Each phase runs ALL preceding phases. `mvn package` runs: validate, compile, test (runs tests!), package.

### Dependency Scopes

| Scope | Available at | Example |
|-------|-------------|---------|
| `compile` (default) | Compile + Runtime + Test | Spring Web, Jackson |
| `runtime` | Runtime + Test | JDBC driver |
| `provided` | Compile + Test | Servlet API (provided by container) |
| `test` | Test only | JUnit, Mockito |

### Multi-Module Projects

```
payment-platform/
├── pom.xml (parent: <packaging>pom</packaging>)
├── payment-common/      (shared library)
├── payment-service/     (depends on payment-common)
├── financial-core/      (depends on payment-common)
└── fraud-service/       (depends on payment-common)
```

---

## 4.2 Testing

### JUnit 5

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentServiceTest {
    private PaymentService service;

    @BeforeEach
    void setUp() { service = new PaymentService(new InMemoryPaymentRepo()); }

    @Test
    @DisplayName("Should create payment with valid amount")
    void createPayment_ValidAmount_ReturnsCompleted() {
        PaymentResult result = service.process(new PaymentRequest(100000L, "VND"));
        assertEquals(PaymentStatus.COMPLETED, result.status());
        assertTrue(result.paymentId() != null);
    }

    @Test
    @DisplayName("Should reject payment with zero amount")
    void createPayment_ZeroAmount_ThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> service.process(new PaymentRequest(0, "VND")));
    }

    @ParameterizedTest
    @ValueSource(longs = {10000, 50000, 100000, 500000})
    void createPayment_VariousAmounts_Success(long amount) {
        PaymentResult result = service.process(new PaymentRequest(amount, "VND"));
        assertEquals(PaymentStatus.COMPLETED, result.status());
    }
}
```

### Mockito

```java
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentOrchestratorTest {
    @Mock FraudService fraudService;
    @Mock LedgerService ledgerService;
    @InjectMocks PaymentOrchestrator orchestrator;

    @Test
    void processPayment_FraudPasses_LedgerCalled() {
        when(fraudService.check(any())).thenReturn(new FraudResult(10, "ALLOW"));
        when(ledgerService.createEntry(any(), anyLong())).thenReturn(new JournalEntry(...));

        PaymentResult result = orchestrator.process(new PaymentRequest(100000L, "VND"));

        verify(fraudService).check(any());
        verify(ledgerService).createEntry(any(), eq(100000L));
        assertEquals(PaymentStatus.COMPLETED, result.status());
    }

    @Test
    void processPayment_FraudBlocks_LedgerNotCalled() {
        when(fraudService.check(any())).thenReturn(new FraudResult(90, "BLOCK"));

        PaymentResult result = orchestrator.process(new PaymentRequest(100000L, "VND"));

        verify(ledgerService, never()).createEntry(any(), anyLong());
        assertEquals(PaymentStatus.DECLINED, result.status());
    }

    @Test
    void processPayment_LedgerFails_CompensationCalled() {
        when(fraudService.check(any())).thenReturn(new FraudResult(10, "ALLOW"));
        when(ledgerService.createEntry(any(), anyLong())).thenThrow(new RuntimeException("DB down"));

        assertThrows(PaymentFailedException.class,
            () -> orchestrator.process(new PaymentRequest(100000L, "VND")));
        // Verify compensation logic was triggered
        verify(ledgerService).reverseEntry(any());
    }
}
```

### Testcontainers

Integration tests with REAL PostgreSQL (in Docker), not H2 in-memory database.

```java
@Testcontainers
@SpringBootTest
class PaymentRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired PaymentRepository repository;

    @Test
    void saveAndFindPayment() {
        Payment payment = new Payment(UUID.randomUUID(), "U1", "M1", 100000L, "VND", PENDING);
        repository.save(payment);
        Optional<Payment> found = repository.findById(payment.id());
        assertTrue(found.isPresent());
        assertEquals(100000L, found.get().amount());
    }
}
```

---

## 4.3 Debugging & Profiling Tools

### jstack — Thread Dumps

```bash
jstack <pid> > threaddump.txt

# Look for:
# - "BLOCKED" — waiting for a monitor (synchronized)
# - "WAITING" — Object.wait(), Thread.join()
# - "TIMED_WAITING" — Thread.sleep(), wait(timeout)
# - "RUNNABLE" — actively executing or waiting for I/O
# - Deadlock detection: jstack prints "Found one Java-level deadlock:"
```

### JFR (Java Flight Recorder) — Low-Overhead Profiling

```bash
# Start recording (via command line)
java -XX:StartFlightRecording=duration=60s,filename=profile.jfr MyApp

# Start recording (via jcmd — attach to running JVM)
jcmd <pid> JFR.start name=myprofile duration=60s filename=profile.jfr

# Analyze with JDK Mission Control (jmc) or async-profiler converter
```

**JFR captures**: CPU usage per method, allocation rate per class, lock contention, I/O events, GC events, exceptions.

### async-profiler — CPU + Allocation Sampling

```bash
# CPU profiling (Linux/macOS)
./profiler.sh -d 30 -f cpu.html <pid>

# Allocation profiling
./profiler.sh -e alloc -d 30 -f alloc.html <pid>

# Lock profiling
./profiler.sh -e lock -d 30 -f lock.html <pid>
```

---

## 4.4 Exercises

### Ex 4.1 — Maven Multi-Module
Create a multi-module Maven project: `payment-common` (shared DTOs), `payment-service` (depends on common), `fraud-service` (depends on common). Verify: `mvn clean package` builds all modules.

### Ex 4.2 — Unit Testing with Mockito
Write unit tests for the `PaymentOrchestrator` using Mockito. Test: (a) fraud passes → payment processed, (b) fraud blocks → payment declined, (c) ledger fails → compensation triggered, (d) idempotency key duplicate → cached response returned. Achieve 100% branch coverage.

### Ex 4.3 — Integration Test with Testcontainers
Write an integration test for `PaymentRepository` using Testcontainers + PostgreSQL. Test: save, findById, findByStatus, update, optimistic locking (version conflict).

### Ex 4.4 — JFR Profiling
Run a payment simulation (10,000 payments in 60 seconds). Capture JFR recording. Analyze: top CPU consumers, top memory allocators, lock contention hot spots.

---

## 4.5 Self-Assessment

- [ ] Can create a multi-module Maven project with correct dependency scopes
- [ ] Can write JUnit 5 tests with `@ParameterizedTest`, `assertThrows`, `@BeforeEach`
- [ ] Can mock dependencies with Mockito (`when`, `verify`, `never`, `eq`, `any`)
- [ ] Can write integration tests with Testcontainers (real PostgreSQL)
- [ ] Can generate and read a thread dump with jstack
- [ ] Can profile CPU and memory with JFR or async-profiler
