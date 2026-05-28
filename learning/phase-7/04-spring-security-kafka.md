# Module 04 — Spring Security & Spring Kafka

## 4.1 Spring Security Architecture

```
Client Request
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    SecurityFilterChain                           │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │SecurityContext│  │  Auth Filter │  │  Authorization       │  │
│  │ Persistence   │  │  (JWT/OAuth) │  │  Filter              │  │
│  │ Filter        │  │              │  │  (RBAC check)         │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
  Controller (@PreAuthorize check)
```

## 4.2 JWT Authentication

```java
// 1. JWT Filter — validates token on every request
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(req, res); return;
        }
        String token = header.substring(7);
        try {
            JWTVerifier verifier = JWT.require(Algorithm.RSA256(publicKey, null)).build();
            DecodedJWT jwt = verifier.verify(token);
            String userId = jwt.getSubject();
            List<String> scopes = jwt.getClaim("scope").asList(String.class);

            // Create Authentication object
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null,
                    scopes.stream().map(SimpleGrantedAuthority::new).toList());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JWTVerificationException e) {
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(req, res);
    }
}

// 2. Security configuration
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // Enables @PreAuthorize
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)  // API — no CSRF needed
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/v1/payments/**").hasAuthority("SCOPE_write:payments")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}

// 3. Method-level security
@PreAuthorize("hasRole('ADMIN')")
public void freezeAccount(String accountId) { ... }

@PreAuthorize("hasAuthority('SCOPE_write:payments')")
public PaymentResult createPayment(PaymentRequest req) { ... }

// 4. OAuth2 Resource Server (simpler — no custom filter needed)
@Bean
public SecurityFilterChain oauth2Chain(HttpSecurity http) {
    return http
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .build();
}
```

## 4.3 Spring Kafka

### Producer

```java
@Service
public class PaymentEventPublisher {
    private final KafkaTemplate<String, PaymentEvent> kafka;

    public PaymentEventPublisher(KafkaTemplate<String, PaymentEvent> kafka) {
        this.kafka = kafka;
    }

    public void publish(PaymentEvent event) {
        kafka.send("payments.payment.succeeded", event.paymentId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) log.error("Failed to publish {}", event.paymentId(), ex);
                else log.info("Published to {}-{} @ offset {}",
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            });
    }
}
```

### Consumer

```java
@Component
public class PaymentEventConsumer {

    @KafkaListener(topics = "payments.payment.succeeded", groupId = "notification-service")
    public void handlePaymentSucceeded(PaymentEvent event) {
        log.info("Received PaymentSucceeded: {}", event.paymentId());
        notificationService.sendConfirmation(event);
    }
}
```

### Error Handling

```java
@Bean
public CommonErrorHandler errorHandler() {
    DefaultErrorHandler handler = new DefaultErrorHandler(
        new DeadLetterPublishingRecoverer(kafkaTemplate),
        new FixedBackOff(1000L, 3L)  // 1s backoff, 3 retries
    );
    handler.addNotRetryableExceptions(DeserializationException.class); // Never retry deser errors
    return handler;
}
```

### Kafka Transaction Sync with DB

```java
@Transactional  // DB transaction
@Transactional("kafkaTransactionManager")  // Kafka transaction
public void processPayment(Payment payment) {
    paymentRepo.save(payment);                    // DB write
    kafka.send("payments.payment.succeeded", ...); // Kafka write
    // Both committed atomically? NO! Two separate transactions!
    // Solution: Outbox pattern (write to outbox_events table in DB TX, CDC reads and publishes)
}
```

## 4.4 Exercises

### Ex 4.1 — JWT Filter
Build a JWT authentication filter. Generate a JWT with `JJWT` library. Test: valid JWT → 200, expired JWT → 401, no JWT → 401, wrong scope → 403.

### Ex 4.2 — RBAC
Define 3 roles: USER (read own payments), MERCHANT (read own settlements), ADMIN (all). Use `@PreAuthorize` to enforce. Test each role's access.

### Ex 4.3 — Kafka Consumer
Write a Spring Kafka consumer with error handling (DeadLetterPublishingRecoverer). Inject a deserialization error. Verify the message is routed to the DLT.

---

## 4.5 Self-Assessment

- [ ] Can configure a SecurityFilterChain with JWT + OAuth2 Resource Server
- [ ] Understand `@PreAuthorize` (method security) vs `authorizeHttpRequests` (URL security)
- [ ] Can configure Kafka producer with acks=all and idempotence
- [ ] Know why `@Transactional` on DB and Kafka does NOT give atomicity (dual-write problem)
- [ ] Can implement error handling with DeadLetterPublishingRecoverer
