# Module 01 — Spring Core: IoC, DI, Bean Lifecycle, AOP

## 1.1 The IoC Container

Spring's core is the **ApplicationContext** — an Inversion of Control container. Instead of your code creating objects (`new PaymentService()`), Spring creates them and injects dependencies.

```java
// WITHOUT Spring:
PaymentService svc = new PaymentService(new PaymentRepository(), new FraudService(), new LedgerService());

// WITH Spring:
@Autowired PaymentService svc;  // Spring creates and wires all dependencies
```

### ApplicationContext Hierarchy

```
BeanFactory (interface)
  └── ApplicationContext (interface)
        ├── AnnotationConfigApplicationContext  (Java config)
        ├── ClassPathXmlApplicationContext      (XML config — legacy)
        └── AnnotationConfigServletWebServerApplicationContext  (Spring Boot web)
```

### Bean Lifecycle

```
┌────────────────────────────────────────────────────────────────┐
│                      BEAN LIFECYCLE                             │
│                                                                 │
│  1. Instantiate (constructor called)                            │
│  2. Populate properties (@Autowired, @Value)                   │
│  3. BeanNameAware.setBeanName()                                 │
│  4. BeanFactoryAware.setBeanFactory()                           │
│  5. ApplicationContextAware.setApplicationContext()             │
│  6. @PostConstruct (or InitializingBean.afterPropertiesSet())  │
│  7. Bean is READY for use                                       │
│  8. @PreDestroy (or DisposableBean.destroy())                  │
│  9. Bean is DESTROYED                                           │
└────────────────────────────────────────────────────────────────┘
```

```java
@Component
public class PaymentService implements InitializingBean, DisposableBean {

    @PostConstruct
    public void init() {
        // Called AFTER all dependencies injected. Use for validation, cache warmup.
        log.info("PaymentService initialized with {} repositories", repositoryCount);
    }

    @PreDestroy
    public void cleanup() {
        // Called before bean is destroyed. Use for: close connections, flush buffers.
        log.info("PaymentService shutting down");
    }
}
```

### Bean Scopes

| Scope | Description | Use Case |
|-------|------------|----------|
| `singleton` (default) | One instance per container | Most beans: services, repositories, controllers |
| `prototype` | New instance every time requested | Stateful beans, per-request objects |
| `request` | One instance per HTTP request | Request-scoped data |
| `session` | One instance per HTTP session | User session data |

```java
@Scope("prototype")
@Component
public class PaymentContext { /* Fresh instance per request */ }
```

## 1.2 Dependency Injection

### Constructor Injection (PREFERRED)

```java
@Service
public class PaymentOrchestrator {
    private final FraudService fraudService;
    private final FeeService feeService;
    private final LedgerService ledgerService;

    // Constructor injection — dependencies are explicit, immutable, testable
    public PaymentOrchestrator(FraudService fraud, FeeService fee, LedgerService ledger) {
        this.fraudService = fraud;
        this.feeService = fee;
        this.ledgerService = ledger;
    }
}
```

**Why constructor injection wins**:
- Dependencies are explicit (you see exactly what this class needs)
- Fields can be `final` (immutable)
- No reflection needed for testing (just `new PaymentOrchestrator(mockFraud, mockFee, mockLedger)`)
- Compiler catches missing dependencies

### Field Injection (AVOID)

```java
@Autowired private FraudService fraudService;  // BAD: hidden dependency, not testable without Spring
```

### @Qualifier and @Primary

```java
@Qualifier("stripe") private PaymentGateway gateway;  // Choose specific bean by name
@Primary  // Mark one bean as the default when multiple candidates exist
```

## 1.3 AOP and Proxies

AOP (Aspect-Oriented Programming) allows adding behavior to methods WITHOUT modifying their code. `@Transactional`, `@Cacheable`, `@PreAuthorize` all use AOP.

### How It Works: JDK Dynamic Proxy vs CGLIB

```java
// Target class
public class PaymentService {
    @Transactional
    public void process(Payment p) { /* ... */ }
}

// Spring creates a PROXY that wraps the target:
public class PaymentServiceProxy extends PaymentService {  // CGLIB proxy (subclass)
    private TransactionManager txManager;
    private PaymentService target;  // The actual bean

    public void process(Payment p) {
        TransactionStatus tx = txManager.begin();  // BEFORE
        try {
            target.process(p);                      // CALL REAL METHOD
            txManager.commit(tx);                   // AFTER (success)
        } catch (Exception e) {
            txManager.rollback(tx);                 // AFTER (failure)
            throw e;
        }
    }
}
```

**The proxy trap**: `@Transactional` only works on PUBLIC methods called from OUTSIDE the class. Self-invocation bypasses the proxy!

```java
@Service
public class PaymentService {
    @Transactional
    public void publicMethod() { }  // Works — called through proxy

    public void caller() {
        this.publicMethod();  // BUG! 'this' is the target, not the proxy. @Transactional IGNORED!
    }

    // Fix: inject self (or use @Transactional on caller)
    @Autowired private PaymentService self;
    public void callerFixed() { self.publicMethod(); }  // Works — called through proxy
}
```

### Writing Custom Aspects

```java
@Aspect
@Component
public class PaymentAuditAspect {

    @Around("@annotation(audit)")  // Intercept methods with @Auditable
    public Object audit(ProceedingJoinPoint joinPoint, Auditable audit) throws Throwable {
        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();  // Execute the actual method
            logAudit(joinPoint.getSignature().getName(), "SUCCESS", System.nanoTime() - start);
            return result;
        } catch (Exception e) {
            logAudit(joinPoint.getSignature().getName(), "FAILED: " + e.getMessage(), System.nanoTime() - start);
            throw e;
        }
    }
}
```

## 1.4 Configuration

```java
@Configuration
public class PaymentConfig {

    @Bean
    public PaymentGateway paymentGateway() {
        return new StripeGateway(apiKey, webhookSecret);
    }

    @Bean
    @Profile("dev")  // Only active when spring.profiles.active=dev
    public PaymentGateway mockGateway() {
        return new MockPaymentGateway();
    }
}

// Externalized configuration
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(String apiKey, long defaultTimeoutMs, int maxRetries) {}
```

## 1.5 Exercises

### Ex 1.1 — Bean Lifecycle Lab
Create a bean with `@PostConstruct`, `InitializingBean`, `@PreDestroy`, `DisposableBean`. Log every lifecycle event. Demonstrate the order of execution.

### Ex 1.2 — Proxy Trap
Write a service with `@Transactional` on a private method. Call it from within the same class. Observe that the transaction is NOT created (check via logging). Fix by injecting self.

### Ex 1.3 — Write a Custom Aspect
Write an `@Timed` annotation and an aspect that measures and logs method execution time. Apply to all methods in a package.

## 1.6 Self-Assessment

- [ ] Can explain the Bean lifecycle from instantiation to destruction
- [ ] Understand why constructor injection is preferred over field injection
- [ ] Can explain how `@Transactional` works (AOP proxy wrapping)
- [ ] Can identify the "self-invocation proxy trap"
- [ ] Can write a custom aspect with `@Around` advice
