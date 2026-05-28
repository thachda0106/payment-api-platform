# Module 05 — Testing, Production & Build Mini Spring

## 5.1 Testing Strategies

### Slice Tests (Fast, Focused)

```java
@WebMvcTest(PaymentController.class)  // Only loads web layer
class PaymentControllerTest {
    @Autowired MockMvc mvc;
    @MockBean PaymentService service;

    @Test void createPayment_Valid_Returns201() throws Exception {
        when(service.process(any(), any())).thenReturn(new PaymentResult(...));
        mvc.perform(post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100000,\"currency\":\"VND\"}"))
            .andExpect(status().isCreated());
    }
}

@DataJpaTest  // Only loads JPA layer, uses in-memory DB by default
class PaymentRepositoryTest {
    @Autowired TestEntityManager em;
    @Autowired PaymentRepository repo;
}
```

### Integration Tests with Testcontainers

```java
@SpringBootTest
@Testcontainers
class PaymentIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
    }

    @Autowired PaymentService service;

    @Test void endToEndPaymentFlow() {
        PaymentResult r = service.process(new PaymentRequest(100000L, "VND"));
        assertThat(r.status()).isEqualTo(COMPLETED);
        assertThat(r.journalEntryId()).isNotNull();
    }
}
```

## 5.2 Production

### Actuator Endpoints

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true  # /actuator/health/liveness, /actuator/health/readiness
```

### Graceful Shutdown

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

### Docker Optimization

```dockerfile
# Use layered JAR for efficient Docker caching
RUN java -Djarmode=tools -jar app.jar extract --destination extracted
# Copy layers in order of change frequency: dependencies → app code
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/application/ ./
```

## 5.3 Build a Mini Spring

Implement from scratch:

### Mini IoC Container

```java
public class MiniApplicationContext {
    private final Map<Class<?>, Object> beans = new HashMap<>();
    private final Map<Class<?>, Object> beanDefinitions = new HashMap<>();

    public <T> void registerBean(Class<T> type, T instance) {
        beans.put(type, instance);
    }

    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) {
        return (T) beans.get(type);
    }

    // Constructor injection: find dependencies, create instances, wire them
    public void autowire() {
        for (var entry : beans.entrySet()) {
            Object bean = entry.getValue();
            for (Field field : bean.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    Object dependency = beans.get(field.getType());
                    field.setAccessible(true);
                    field.set(bean, dependency);
                }
            }
        }
    }
}
```

### Mini @Transactional via Proxy

```java
public class TransactionProxy implements InvocationHandler {
    private final Object target;
    public TransactionProxy(Object target) { this.target = target; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.isAnnotationPresent(Transactional.class)) {
            System.out.println(">>> BEGIN TX");
            try {
                Object result = method.invoke(target, args);
                System.out.println(">>> COMMIT TX");
                return result;
            } catch (Exception e) {
                System.out.println(">>> ROLLBACK TX");
                throw e;
            }
        }
        return method.invoke(target, args);
    }
}

// Usage:
PaymentService target = new PaymentService();
PaymentService proxy = (PaymentService) Proxy.newProxyInstance(
    target.getClass().getClassLoader(),
    target.getClass().getInterfaces(),
    new TransactionProxy(target)
);
proxy.process(payment);  // Transaction created!
```

## 5.4 Exercises

### Ex 5.1 — Slice Testing
Write `@WebMvcTest`, `@DataJpaTest`, and `@SpringBootTest` for the same service. Measure: startup time, test execution time. Understand which to use when.

### Ex 5.2 — Build Mini IoC
Implement: Bean registration, constructor injection, `@PostConstruct` lifecycle, `@PreDestroy` lifecycle. Test with a real dependency graph (Service → Repository → DataSource).

### Ex 5.3 — Build Mini @Transactional
Implement a JDK dynamic proxy that wraps method calls in BEGIN/COMMIT/ROLLBACK. Support `rollbackFor`. Test with a method that throws a checked exception (should NOT rollback) and a runtime exception (should rollback).

---

## 5.5 Self-Assessment

- [ ] Can write slice tests (@WebMvcTest, @DataJpaTest) and integration tests (@SpringBootTest)
- [ ] Can configure Actuator health, metrics, and readiness probes
- [ ] Understand Docker layered JAR optimization
- [ ] Can implement a mini IoC container with constructor injection
- [ ] Can implement a mini @Transactional using JDK dynamic proxies
