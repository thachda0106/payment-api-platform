# Module 02 — Spring Boot & MVC

## 2.1 Auto-Configuration

`@SpringBootApplication` = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`.

**How auto-configuration works**:
1. `@EnableAutoConfiguration` triggers `AutoConfigurationImportSelector`
2. It reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
3. Each auto-config class is evaluated against `@Conditional` annotations
4. If conditions match (e.g., class on classpath, bean not already defined), the configuration is applied

```java
// Example: DataSourceAutoConfiguration
@AutoConfiguration
@ConditionalOnClass({DataSource.class, EmbeddedDatabaseType.class})
@ConditionalOnMissingBean(DataSource.class)
public class DataSourceAutoConfiguration {
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSource dataSource() { return ...; }
}
```

```bash
# See which auto-configurations were applied (and which weren't)
# In application.yml: logging.level.org.springframework.boot.autoconfigure=DEBUG
# Or: --debug flag
```

## 2.2 Request Lifecycle

```
Client Request
    │
    ▼
┌───────────────────┐
│   Servlet Filter   │  OncePerRequestFilter: auth, logging, CORS, tracing
│   Chain            │
└────────┬──────────┘
         ▼
┌───────────────────┐
│  DispatcherServlet │  Front Controller — routes to handler
└────────┬──────────┘
         ▼
┌───────────────────┐
│  HandlerMapping    │  Maps URL → Controller method
│  (e.g., GET /v1   │
│   /payments/{id})  │
└────────┬──────────┘
         ▼
┌───────────────────┐
│  HandlerAdapter    │  Calls the controller method
└────────┬──────────┘
         ▼
┌───────────────────┐
│  HandlerInterceptor│  preHandle() → Controller → postHandle() → afterCompletion()
│  (AOP at HTTP     │
│   level)           │
└────────┬──────────┘
         ▼
┌───────────────────┐
│  Controller.method │  Your code: @GetMapping, @PostMapping
└────────┬──────────┘
         ▼
┌───────────────────┐
│  ReturnValueHandler│  Converts return value → HTTP response
│  (HttpMessageConv. │  (@ResponseBody → Jackson JSON serialization)
│   erter)           │
└────────┬──────────┘
         ▼
    HTTP Response to Client
```

## 2.3 Spring MVC Controllers

```java
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        PaymentResult result = paymentService.process(request, idempotencyKey);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .header("Idempotency-Key", idempotencyKey)
            .body(PaymentResponse.from(result));
    }

    @GetMapping("/{id}")
    public PaymentResponse getPayment(@PathVariable UUID id) {
        return paymentService.findById(id)
            .map(PaymentResponse::from)
            .orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
```

## 2.4 Exception Handling

```java
@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(PaymentNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Payment not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", e.getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
            .toList());
        return ResponseEntity.badRequest().body(problem);
    }
}
```

## 2.5 Validation

```java
public record CreatePaymentRequest(
    @NotNull @Positive
    Long amount,

    @NotBlank @Size(min = 3, max = 3)
    String currency,

    @NotBlank
    String sourceAccountId,

    @NotBlank
    String destinationAccountId,

    @Size(max = 500)
    String description
) {}
```

## 2.6 Exercises

### Ex 2.1 — Request Lifecycle Trace
Add a `Filter`, `HandlerInterceptor`, and `@ControllerAdvice` to a controller. Log every step. Trace a request through the full lifecycle.

### Ex 2.2 — Validation Custom
Create a custom validator `@ValidCurrency` that validates currency codes against a list. Use `ConstraintValidator`. Test with valid and invalid currencies.

---

## 2.7 Self-Assessment

- [ ] Can trace a request from FilterChain → DispatcherServlet → Controller → Response
- [ ] Understand `@RestControllerAdvice` and `@ExceptionHandler` hierarchy
- [ ] Can create custom Bean Validation constraints
- [ ] Know the difference between `Filter`, `Interceptor`, and `@Aspect`
