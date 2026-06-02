# Session 13: Spring Boot Bootstrap & Auto-Configuration Internals

## 1. Why This Topic Exists

Every Spring Boot application starts with `SpringApplication.run()`. That single line triggers ~13,000 classes to load, ~2,000 beans to instantiate, and a web server to bind to a port. When it works, it is magic. When it fails — and it will fail in production at 3 AM — the developer who cannot trace from `main()` to `BeanCreationException` is helpless.

Auto-configuration is Spring Boot's signature feature. It is also its most dangerous. The framework makes 300+ decisions about your infrastructure based on what JARs are on the classpath. Those decisions are correct 95% of the time. The other 5% create runtime failures that manifest as `NoSuchBeanDefinitionException` or silent misconfiguration where the wrong `DataSource` bean is used.

**Staff engineer insight**: Understanding auto-configuration internals transforms it from "magic that sometimes breaks" into "a deterministic evaluation engine that you can debug, extend, and override." Without this understanding, you cannot build custom starters, you cannot debug startup failures efficiently, and you cannot reason about what happens when two auto-configuration classes conflict.

## 2. Mental Model

```
SpringApplication.run(MyApp.class, args)
│
├── Phase 1: Bootstrap (ApplicationType deduction, initializers, listeners)
├── Phase 2: Environment Preparation (profiles, property sources)
├── Phase 3: Context Creation (AnnotationConfigServletWebServerApplicationContext)
├── Phase 4: Context Preparation (register startup class, load sources)
├── Phase 5: Context Refresh (12-step AbstractApplicationContext.refresh())
└── Phase 6: After-Refresh (Runners, LivenessState)

AUTO-CONFIGURATION LIVES IN PHASE 5, STEP 6:
  refresh() → invokeBeanFactoryPostProcessors() → ConfigurationClassParser
  → @EnableAutoConfiguration → AutoConfigurationImportSelector
  → spring.factories → filter by @Conditional → register bean definitions
```

The mental model to internalize: **Auto-configuration does NOT create beans. It registers BeanDefinitions.** Bean creation happens later during `finishBeanFactoryInitialization()`. This separation is the source of most confusion. An auto-configuration class "failing" means its `@Conditional` evaluated to false — not that bean creation threw an exception.

```
Auto-configuration = f(classpath JARs, @Conditional evaluations, property values, @Bean method results)

NOT Auto-configuration = "Spring Boot magically knows what I want"
```

## 3. Internal Architecture

### SpringApplication.run() — Every Step Deconstructed

```java
// Source: org.springframework.boot.SpringApplication.java (simplified trace)

public static ConfigurableApplicationContext run(Class<?> primarySource, String... args) {
    return new SpringApplication(primarySource).run(args);
}

public ConfigurableApplicationContext run(String... args) {
    StopWatch stopWatch = new StopWatch();
    stopWatch.start();

    // ── STEP 1: Create BootstrapContext ──
    DefaultBootstrapContext bootstrapContext = createBootstrapContext();

    // ── STEP 2: Set java.awt.headless ──
    configureHeadlessProperty();

    // ── STEP 3: Get SpringApplicationRunListeners ──
    // Loads from spring.factories: EventPublishingRunListener
    SpringApplicationRunListeners listeners = getRunListeners(args);
    listeners.starting(bootstrapContext);  // → fires ApplicationStartingEvent

    try {
        // ── STEP 4: Build ApplicationArguments ──
        ApplicationArguments applicationArguments = new DefaultApplicationArguments(args);

        // ── STEP 5: Prepare Environment ──
        // Loads: systemProperties, systemEnvironment, application.properties,
        //        application-{profile}.properties, command-line args
        ConfigurableEnvironment environment = prepareEnvironment(listeners,
                            bootstrapContext, applicationArguments);
        // Inside prepareEnvironment:
        //   a. Create StandardServletEnvironment (or StandardEnvironment)
        //   b. Configure PropertySources (system, env, random, command-line, config files)
        //   c. Configure Profiles (spring.profiles.active)
        //   d. listeners.environmentPrepared() → fires ApplicationEnvironmentPreparedEvent
        //      → ConfigFileApplicationListener loads application.properties/yml
        //   e. Bind environment to spring.main properties
        //   f. Convert environment for AOT if needed

        // ── STEP 6: Print Banner ──
        Banner printedBanner = printBanner(environment);

        // ── STEP 7: Create ApplicationContext ──
        // Deduces context type from classpath:
        //   spring-web (Servlet)  → AnnotationConfigServletWebServerApplicationContext
        //   spring-webflux        → AnnotationConfigReactiveWebServerApplicationContext
        //   neither               → AnnotationConfigApplicationContext
        ConfigurableApplicationContext context = createApplicationContext();
        context.setApplicationStartup(new FlightRecorderStartup()); // Java 17+

        // ── STEP 8: Prepare Context ──
        //   a. Set environment on context
        //   b. Post-process context (apply ResourceLoader, ConversionService, etc.)
        //   c. Apply Initializers (from spring.factories: ApplicationContextInitializer list)
        //   d. listeners.contextPrepared() → fires ApplicationContextPreparedEvent
        //   e. Print startup info if log startup info is enabled
        //   f. Register primarySource as a singleton bean
        //   g. Load sources into context (bean definitions from @Configuration classes)
        //   h. listeners.contextLoaded() → fires ApplicationPreparedEvent
        prepareContext(bootstrapContext, context, environment, listeners,
                       applicationArguments, printedBanner);

        // ── STEP 9: Refresh Context ──
        // THIS IS THE 12-STEP SEQUENCE (covered in Session 14).
        // Auto-configuration kicks in at step 6 (invokeBeanFactoryPostProcessors)
        refreshContext(context);

        // ── STEP 10: After Refresh (empty hook for subclasses) ──
        afterRefresh(context, applicationArguments);

        // ── STEP 11: Mark startup complete, start liveBeansView ──
        stopWatch.stop();
        if (this.logStartupInfo) {
            new StartupInfoLogger(this.mainApplicationClass)
                .logStarted(getApplicationLog(), stopWatch);
        }
        listeners.started(context, timeTakenToStartup);
        // → fires ApplicationStartedEvent

        // ── STEP 12: Call Runners ──
        // ApplicationRunner and CommandLineRunner beans are executed
        callRunners(context, applicationArguments);
        // → fires ApplicationReadyEvent
        // If any runner fails, fires ApplicationFailedEvent

    } catch (Throwable ex) {
        handleRunFailure(context, ex, listeners);
        // → fires ApplicationFailedEvent with exception
        throw new IllegalStateException(ex);
    }

    try {
        listeners.ready(context, timeTakenToReady);
        // → fires ApplicationReadyEvent (AvailabilityChangeEvent underneath)
    } catch (Throwable ex) {
        handleRunFailure(context, ex, null);
        throw new IllegalStateException(ex);
    }

    return context;
}
```

### ApplicationContext Type Selection Logic

```java
// Inside SpringApplication.createApplicationContext()
protected ConfigurableApplicationContext createApplicationContext() {
    switch (this.webApplicationType) {
        case SERVLET:
            return new AnnotationConfigServletWebServerApplicationContext();
        case REACTIVE:
            return new AnnotationConfigReactiveWebServerApplicationContext();
        case NONE:
            return new AnnotationConfigApplicationContext();
    }
}

// webApplicationType is determined by static helper:
static WebApplicationType deduceFromClasspath() {
    if (ClassUtils.isPresent("org.springframework.web.reactive.DispatcherHandler", null)
            && !ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", null)
            && !ClassUtils.isPresent("org.glassfish.jersey.servlet.ServletContainer", null)) {
        return WebApplicationType.REACTIVE;
    }
    for (String className : SERVLET_INDICATOR_CLASSES) {
        if (!ClassUtils.isPresent(className, null)) {
            return WebApplicationType.NONE;
        }
    }
    return WebApplicationType.SERVLET;
}
```

### The Property Sources Priority Chain

```
Highest priority (wins on conflict):
  1. Devtools global settings ($HOME/.spring-boot-devtools.properties)
  2. @TestPropertySource annotations on tests
  3. @SpringBootTest#properties attribute
  4. Command line arguments (--server.port=8081)
  5. SPRING_APPLICATION_JSON properties (inline JSON in env var)
  6. ServletConfig init parameters
  7. ServletContext init parameters
  8. JNDI attributes from java:comp/env
  9. Java System properties (System.getProperties())
 10. OS environment variables (SPRING_PROFILES_ACTIVE, SERVER_PORT, etc.)
 11. RandomValuePropertySource (random.*)
 12. Profile-specific application-{profile}.properties OUTSIDE jar
 13. Profile-specific application-{profile}.properties INSIDE jar
 14. Application properties OUTSIDE jar (config/application.properties)
 15. Application properties INSIDE jar (classpath:application.properties)
 16. @PropertySource on @Configuration classes
 17. Default properties (SpringApplication.setDefaultProperties)
Lowest priority
```

### Auto-Configuration Mechanism

```java
// The chain: @SpringBootApplication → @EnableAutoConfiguration → AutoConfigurationImportSelector

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootConfiguration     // → @Configuration
@EnableAutoConfiguration     // THE KEY ANNOTATION
@ComponentScan(
    excludeFilters = {
        @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
    })
public @interface SpringBootApplication {
    // ...
}

// @EnableAutoConfiguration triggers AutoConfigurationImportSelector
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@AutoConfigurationPackage       // registers the package of @SpringBootApplication class
@Import(AutoConfigurationImportSelector.class)  // THE MECHANISM
public @interface EnableAutoConfiguration {
    // ...
}

// AutoConfigurationImportSelector implements DeferredImportSelector
// → process() called during ConfigurationClassParser phase, NOT during first parse pass
public class AutoConfigurationImportSelector implements DeferredImportSelector, ... {

    // Core logic: load candidate configurations, filter by conditions
    @Override
    public String[] selectImports(AnnotationMetadata annotationMetadata) {
        if (!isEnabled(annotationMetadata)) {
            return NO_IMPORTS;
        }
        // 1. Load all auto-configuration class names
        AutoConfigurationEntry entry = getAutoConfigurationEntry(annotationMetadata);
        // 2. Return class names to be imported (registered as bean definitions)
        return StringUtils.toStringArray(entry.getConfigurations());
    }

    protected AutoConfigurationEntry getAutoConfigurationEntry(
            AnnotationMetadata annotationMetadata) {
        // 1. Load all candidate configurations from META-INF/spring/org.springframework.boot
        //    .autoconfigure.AutoConfiguration.imports (Spring Boot 3.x)
        //    OR spring.factories key org.springframework.boot.autoconfigure.EnableAutoConfiguration
        List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);

        // 2. Remove duplicates
        configurations = removeDuplicates(configurations);

        // 3. Apply exclusions (spring.autoconfigure.exclude + @EnableAutoConfiguration(exclude=))
        Set<String> exclusions = getExclusions(annotationMetadata, attributes);
        configurations.removeAll(exclusions);

        // 4. Filter by @Conditional evaluation
        configurations = new AutoConfigurationImportFilter(configurations)
            .filter(this::filter)
            .stream()
            .toList();

        // 5. Fire AutoConfigurationImportEvent (for debugging/auditing)
        fireAutoConfigurationImportEvents(configurations, exclusions);
        return new AutoConfigurationEntry(configurations, exclusions);
    }
}
```

### Where Auto-Configurations Are Loaded From

In Spring Boot 3.x, auto-configuration registrations moved from `spring.factories` to a dedicated file:

```
# Spring Boot 2.x (legacy):
META-INF/spring.factories
  org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
    org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration,\
    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
    ...

# Spring Boot 3.x (current):
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
  org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
  org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
  ...
```

The import file is loaded by `ImportCandidates.load(AutoConfiguration.class, classLoader)`, which scans all JARs on the classpath for this file and aggregates the results. Multiple JARs can contribute auto-configuration entries — this is how custom starters integrate.

### @Conditional Family — How Evaluation Works

```java
// The @Conditional annotation and its Spring Boot specializations:

@Conditional(MyCondition.class)                    // Generic: implement Condition.matches()
@ConditionalOnClass(name = "com.mysql.cj.jdbc.Driver")   // Class present on classpath?
@ConditionalOnMissingClass("com.mysql.cj.jdbc.Driver")   // Class absent?
@ConditionalOnBean(DataSource.class)               // Bean exists in context?
@ConditionalOnMissingBean(DataSource.class)        // Bean NOT in context?
@ConditionalOnProperty("myapp.feature.enabled")    // Property value?
@ConditionalOnResource(resources = "classpath:myconfig.xml")  // Resource exists?
@ConditionalOnWebApplication                         // Servlet vs Reactive vs None
@ConditionalOnExpression("${myapp.enabled:true}")    // SpEL expression
@ConditionalOnJava(range = Range.EQUAL_OR_NEWER, value = JavaVersion.SEVENTEEN) // Java version
@ConditionalOnCloudPlatform(CloudPlatform.KUBERNETES)  // Cloud environment
@ConditionalOnWarDeployment                           // WAR vs JAR deployment
@ConditionalOnJndi                                    // JNDI available?

// Condition evaluation logic (simplified):
public class OnClassCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context,
                                            AnnotatedTypeMetadata metadata) {
        String[] classNames = getAttributes(metadata, ConditionalOnClass.class);
        for (String className : classNames) {
            if (!ClassUtils.isPresent(className, context.getClassLoader())) {
                return ConditionOutcome.noMatch("Required class '" + className + "' not found");
            }
        }
        return ConditionOutcome.match();
    }
}
```

Each `@Conditional*` annotation has a corresponding `Condition` implementation registered via `@Conditional(SomeCondition.class)`. During auto-configuration evaluation, Spring iterates all candidate configuration classes, checks their class-level and method-level `@Conditional*` annotations against the current `ConditionContext`, and produces a `ConditionOutcome` (match or no-match, with a message).

**Evaluation order matters**: `@ConditionalOnClass` is evaluated BEFORE `@ConditionalOnBean` because class presence is cheaper to check than bean presence. The `AutoConfigurationImportFilter` applies class-based filtering early (before loading the class bytecode) using ASM to read annotations without loading the class itself.

### Canonical Auto-Configuration Classes and What They Do

| Auto-Configuration Class | Activates When | Beans Created |
|--------------------------|----------------|---------------|
| `DispatcherServletAutoConfiguration` | Spring MVC on classpath | `DispatcherServlet`, `/` and `/*` mappings |
| `EmbeddedWebServerFactoryCustomizerAutoConfiguration` | Servlet web app | `TomcatServletWebServerFactory`, `JettyServletWebServerFactory`, or `UndertowServletWebServerFactory` |
| `HttpMessageConvertersAutoConfiguration` | Spring MVC | `HttpMessageConverters` (Jackson, String, ByteArray) |
| `JacksonAutoConfiguration` | Jackson on classpath | `ObjectMapper`, `Jackson2ObjectMapperBuilder` |
| `DataSourceAutoConfiguration` | `DataSource` class, no existing `DataSource` bean | `DataSource` (HikariCP by default), `DataSourceProperties` binding |
| `DataSourceTransactionManagerAutoConfiguration` | Existing `DataSource` bean | `PlatformTransactionManager` |
| `HibernateJpaAutoConfiguration` | `DataSource` bean, Hibernate on classpath | `EntityManagerFactory`, `JpaTransactionManager` |
| `JpaRepositoriesAutoConfiguration` | `EntityManagerFactory` bean, Spring Data JPA | Enables `@EnableJpaRepositories` |
| `TransactionAutoConfiguration` | `PlatformTransactionManager` bean | `TransactionTemplate`, `@Transactional` support |
| `TaskExecutionAutoConfiguration` | No custom `TaskExecutor` bean | `ThreadPoolTaskExecutor` (8 core threads) |
| `TaskSchedulingAutoConfiguration` | `@EnableScheduling` | `ThreadPoolTaskScheduler` |
| `CacheAutoConfiguration` | No `CacheManager` bean | `ConcurrentMapCacheManager` (or Redis, Caffeine, etc. if detected) |
| `SecurityAutoConfiguration` | Spring Security on classpath | Default security filter chain, `UserDetailsService` with generated password |
| `RabbitAutoConfiguration` | RabbitMQ client on classpath | `ConnectionFactory`, `RabbitTemplate`, `AmqpAdmin` |
| `KafkaAutoConfiguration` | Kafka client on classpath | `KafkaTemplate`, `ConsumerFactory`, `ProducerFactory` |
| `RedisAutoConfiguration` | Redis client on classpath | `RedisConnectionFactory`, `RedisTemplate`, `StringRedisTemplate` |
| `MongoAutoConfiguration` | MongoDB driver on classpath | `MongoClient`, `MongoTemplate` |
| `FlywayAutoConfiguration` | Flyway on classpath + `DataSource` bean | Flyway migration runner |
| `LiquibaseAutoConfiguration` | Liquibase on classpath + `DataSource` bean | Liquibase migration runner |
| `Actuator auto-configs` (via `*AutoConfiguration` classes in `spring-boot-actuator-autoconfigure`) | Actuator on classpath | Health, metrics, info, env endpoints |

### spring-boot-autoconfigure Module Internal Structure

```
spring-boot-autoconfigure/
└── src/main/java/org/springframework/boot/autoconfigure/
    ├── AutoConfiguration.java               ← @AutoConfiguration (replaces @Configuration for auto-config)
    ├── AutoConfigurationImportSelector.java  ← Reads imports files, filters by @Conditional
    ├── AutoConfigurationImportFilter.java    ← Early filtering by @ConditionalOnClass
    ├── AutoConfigurationPackages.java       ← Tracks base packages
    ├── SpringBootApplication.java           ← Not here (in spring-boot module)
    │
    ├── condition/                           ← All @Conditional* implementations
    │   ├── ConditionOutcome.java
    │   ├── SpringBootCondition.java         ← Base class for all conditions
    │   ├── ConditionalOnClass.java
    │   ├── ConditionalOnBean.java
    │   ├── ConditionalOnMissingBean.java
    │   ├── ConditionalOnProperty.java
    │   ├── ConditionalOnWebApplication.java
    │   ├── ConditionalOnResource.java
    │   ├── ConditionalOnExpression.java
    │   ├── ConditionalOnJava.java
    │   ├── ConditionalOnCloudPlatform.java
    │   ├── ConditionalOnSingleCandidate.java
    │   ├── ConditionalOnJndi.java
    │   ├── ConditionalOnWarDeployment.java
    │   ├── OnClassCondition.java
    │   ├── OnBeanCondition.java
    │   ├── OnPropertyCondition.java
    │   ├── OnWebApplicationCondition.java
    │   └── SearchStrategy.java              ← ANCESTORS vs CURRENT vs PARENTS
    │
    ├── web/                                 ← Web infrastructure auto-configs
    │   ├── servlet/
    │   │   ├── WebMvcAutoConfiguration.java
    │   │   ├── DispatcherServletAutoConfiguration.java
    │   │   ├── HttpMessageConvertersAutoConfiguration.java
    │   │   ├── ServletWebServerFactoryAutoConfiguration.java
    │   │   └── error/ErrorMvcAutoConfiguration.java
    │   ├── reactive/
    │   │   ├── WebFluxAutoConfiguration.java
    │   │   └── ReactiveWebServerFactoryAutoConfiguration.java
    │   └── client/
    │       └── RestTemplateAutoConfiguration.java
    │
    ├── jdbc/                                ← DataSource & JDBC
    │   ├── DataSourceAutoConfiguration.java
    │   ├── DataSourceTransactionManagerAutoConfiguration.java
    │   ├── JdbcTemplateAutoConfiguration.java
    │   └── JndiDataSourceAutoConfiguration.java
    │
    ├── orm/jpa/                             ← JPA/Hibernate
    │   ├── HibernateJpaAutoConfiguration.java
    │   └── JpaRepositoriesAutoConfiguration.java
    │
    ├── transaction/                         ← Transaction management
    │   └── TransactionAutoConfiguration.java
    │
    ├── task/                                ← Async execution & scheduling
    │   ├── TaskExecutionAutoConfiguration.java
    │   └── TaskSchedulingAutoConfiguration.java
    │
    ├── cache/                               ← Caching abstraction
    │   ├── CacheAutoConfiguration.java
    │   └── (Redis, Caffeine, Hazelcast, etc. variants)
    │
    ├── security/                            ← Spring Security integration
    │   ├── SecurityAutoConfiguration.java
    │   └── servlet/SecurityFilterAutoConfiguration.java
    │
    ├── messaging/                           ← RabbitMQ, Kafka, JMS
    │   ├── rabbit/RabbitAutoConfiguration.java
    │   ├── kafka/KafkaAutoConfiguration.java
    │   ├── jms/JmsAutoConfiguration.java
    │
    ├── data/                                ← Spring Data integrations
    │   ├── redis/RedisAutoConfiguration.java
    │   ├── mongo/MongoAutoConfiguration.java
    │   ├── elasticsearch/ElasticsearchAutoConfiguration.java
    │   └── cassandra/CassandraAutoConfiguration.java
    │
    ├── flyway/FlywayAutoConfiguration.java
    ├── liquibase/LiquibaseAutoConfiguration.java
    ├── quartz/QuartzAutoConfiguration.java
    ├── mail/MailSenderAutoConfiguration.java
    ├── validation/ValidationAutoConfiguration.java
    ├── jackson/JacksonAutoConfiguration.java
    ├── gson/GsonAutoConfiguration.java
    ├── info/ProjectInfoAutoConfiguration.java
    ├── sql/init/SqlInitializationAutoConfiguration.java
    ├── r2dbc/R2dbcAutoConfiguration.java
    ├── graphql/GraphQlAutoConfiguration.java
    ├── batch/BatchAutoConfiguration.java
    ├── webservices/WebServicesAutoConfiguration.java
    ├── thymeleaf/ThymeleafAutoConfiguration.java
    ├── freemarker/FreeMarkerAutoConfiguration.java
    └── sendgrid/SendGridAutoConfiguration.java
```

## 4. Runtime Behavior

### Startup Timing Breakdown (Real Production Measurement)

```
Measured on a medium Spring Boot 3.2 app, Dell Precision, 32GB RAM, NVMe SSD:
(Your numbers will vary; relative proportions matter more than absolute values.)

[0.0-0.3s]    JVM Bootstrap, class loading begin
[0.3-1.2s]    SpringApplication constructors, listeners setup
[1.2-2.0s]    Environment preparation (property files, profile resolution)
[2.0-2.3s]    ApplicationContext creation
[2.3-3.0s]    Context preparation (initializers, primary source registration)
[3.0-3.5s]    refresh() phase 1-5: BeanFactory preparation
[3.5-5.5s]    refresh() phase 6: invokeBeanFactoryPostProcessors
               ↓ Auto-configuration evaluation happens here ↓
               ├── 3.5-3.8s: Load 200+ auto-config class names from imports files
               ├── 3.8-4.0s: Apply exclusions, deduplicate
               ├── 4.0-4.5s: OnClassCondition filtering (ASM-based, no class loading)
               └── 4.5-5.5s: @Configuration class parsing, @Bean registration
[5.5-8.0s]    refresh() phase 7-11: Bean instantiation
               ├── 5.5-6.0s: BeanPostProcessor registration
               ├── 6.0-7.5s: Singleton bean instantiation (non-lazy)
               │   └── Hibernate EntityManagerFactory: 1.2s (metadata scanning)
               │   └── Tomcat web server: 0.3s
               │   └── Connection pools: 0.1s each
               └── 7.5-8.0s: finishRefresh() — start web server
[8.0-8.2s]    afterRefresh() hook (empty for most apps)
[8.2-8.5s]    ApplicationRunners invoked
[8.5-8.7s]    ApplicationReadyEvent fired, ready for traffic

Total: ~8.7s startup time (cold JVM, no CDS)
```

### What Happens on Refresh When Auto-Configuration Fails Silently

```java
// Example: HibernateJpaAutoConfiguration when no DataSource bean exists

@AutoConfiguration
@ConditionalOnClass({ LocalContainerEntityManagerFactoryBean.class, EntityManager.class })
@ConditionalOnBean(DataSource.class)   // ← This evaluates to FALSE if no DataSource bean
@EnableConfigurationProperties(JpaProperties.class)
public class HibernateJpaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(...) {
        // This bean is NEVER created because the CLASS-LEVEL @ConditionalOnBean
        // evaluated to false. The class is entirely skipped.
    }
}
```

**Silent failure consequence**: If `DataSourceAutoConfiguration` failed its conditions (e.g., no driver on classpath, or `spring.datasource.url` not set), then `HibernateJpaAutoConfiguration` also silently fails because it requires a `DataSource` bean. The application starts fine — just without JPA. No error, no warning (unless debugging is enabled). A developer expecting JPA to "just work" is now debugging `NoSuchBeanDefinitionException: No qualifying bean of type 'EntityManager'`.

### What Happens When Auto-Configuration Fails Loudly

```java
// Example: DataSourceAutoConfiguration when spring.datasource.url is set
// but the driver class is missing from the classpath

@AutoConfiguration
@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceAutoConfiguration {
    // This class MATCHES (DataSource is on classpath, embedded DB also possible)
    // But when Spring tries to CREATE the DataSource bean:
    @Bean
    @ConditionalOnMissingBean
    public DataSource dataSource(DataSourceProperties properties) {
        // During bean instantiation (NOT during auto-config evaluation):
        // If driver class missing → CannotLoadDriverClassException
        // If URL is wrong → Connection refused exception
        // If credentials wrong → Authentication failure at connection test
    }
}
```

**Loud failure**: `DataSourceAutoConfiguration` matched its conditions, so it was registered as a bean definition. When `finishBeanFactoryInitialization()` tries to instantiate the `dataSource` bean, the `DataSourceProperties` binding succeeds, but `DataSourceBuilder` fails to load the driver class or connect to the database. The result is a startup-crashing exception with a stack trace that begins deep in `AbstractApplicationContext.refresh()`.

The critical distinction: **Condition failures are silent by design (the class was never meant to activate). Bean creation failures are loud by design (the class DID activate but couldn't deliver).**

## 5. Request Flow Diagrams

### Startup Flow: From main() to ApplicationReady

```
main()
  │
  ▼
SpringApplication.run(MyApp.class, args)
  │
  ├─[1] NEW SpringApplication(primarySource)
  │      ├── deduce WebApplicationType (Servlet/Reactive/None)
  │      ├── setInitializers (from spring.factories)
  │      └── setListeners (from spring.factories)
  │
  ├─[2] createBootstrapContext()
  │
  ├─[3] getRunListeners(args)
  │      └── load: EventPublishingRunListener
  │
  ├─[4] listeners.starting(bootstrapContext)
  │      └── fires: ApplicationStartingEvent
  │
  ├─[5] prepareEnvironment(listeners, bootstrapContext, args)
  │      ├── createEnvironment() → StandardServletEnvironment
  │      ├── configureEnvironment() → conversionService, commandLineArgs
  │      ├── configurePropertySources() → system + env + random
  │      ├── configureProfiles() → spring.profiles.active
  │      ├── listeners.environmentPrepared()
  │      │   └── ConfigFileApplicationListener reads application.properties/.yml
  │      │       ├── classpath:/application.properties
  │      │       ├── classpath:/application-{profile}.properties
  │      │       ├── optional:configtree:/ (K8s ConfigMap secrets)
  │      │       └── optional:classpath:/config/
  │      ├── bindToSpringApplication(environment)
  │      └── if AOT: convertEnvironment(environment)
  │
  ├─[6] printBanner(environment)
  │
  ├─[7] createApplicationContext()
  │      └── switch(webApplicationType):
  │          SERVLET: AnnotationConfigServletWebServerApplicationContext
  │          REACTIVE: AnnotationConfigReactiveWebServerApplicationContext
  │          NONE: AnnotationConfigApplicationContext
  │
  ├─[8] prepareContext(context, environment, ...)
  │      ├── context.setEnvironment(environment)
  │      ├── postProcessApplicationContext()
  │      ├── applyInitializers()
  │      ├── listeners.contextPrepared() → ApplicationContextPreparedEvent
  │      ├── register primarySource bean
  │      └── load() primarySources into BeanDefinitionRegistry
  │
  ├─[9] refreshContext(context) ← THE 12-STEP REFRESH
  │      │
  │      ├── prepareRefresh()
  │      ├── obtainFreshBeanFactory()
  │      ├── prepareBeanFactory()
  │      ├── postProcessBeanFactory()
  │      │
  │      ├── ╔═══════════════════════════════════════════════╗
  │      │   ║  [5] invokeBeanFactoryPostProcessors()        ║
  │      │   ║                                               ║
  │      │   ║  ├── ConfigurationClassPostProcessor           ║
  │      │   ║  │   └── parse @Configuration classes         ║
  │      │   ║  │       ├── @ComponentScan processing         ║
  │      │   ║  │       ├── @Import processing                ║
  │      │   ║  │       ├── @Bean method registration         ║
  │      │   ║  │       └── DeferredImportSelector processing ║
  │      │   ║  │           └── AutoConfigurationImportSelector║
  │      │   ║  │               ├── load *.imports files      ║
  │      │   ║  │               ├── apply exclusions          ║
  │      │   ║  │               ├── OnClassCondition filter   ║
  │      │   ║  │               ├── Evaluate @Conditional*     ║
  │      │   ║  │               └── Register matching @Bean   ║
  │      │   ║  │                   method definitions        ║
  │      │   ╚═══════════════════════════════════════════════╝
  │      │
  │      ├── registerBeanPostProcessors()
  │      ├── initMessageSource()
  │      ├── initApplicationEventMulticaster()
  │      ├── onRefresh() → createWebServer() (Tomcat starts)
  │      ├── registerListeners()
  │      └── finishBeanFactoryInitialization()
  │           └── preInstantiateSingletons() → all non-lazy singletons
  │
  ├─[10] afterRefresh() (hook for subclasses)
  │
  ├─[11] listeners.started(context) → ApplicationStartedEvent
  │
  ├─[12] callRunners(context, args)
  │       ├── ApplicationRunner beans
  │       └── CommandLineRunner beans
  │
  └─[13] listeners.ready(context) → ApplicationReadyEvent
           └── AvailabilityChangeEvent(ReadinessState.ACCEPTING_TRAFFIC)
```

### Auto-Configuration Decision Flow Per Class

```
For EACH auto-config candidate class (e.g., DataSourceAutoConfiguration):

  1. Is it in the exclusions list?
     ├── YES → SKIP (go to next candidate)
     └── NO → continue
     
  2. OnClassCondition: Are required classes present?
     ├── @ConditionalOnClass(DataSource.class)
     │   ├── NOT PRESENT → SKIP (with message: "DataSource not on classpath")
     │   └── PRESENT → continue
     └── @ConditionalOnMissingClass("some.optional.Library")
         ├── PRESENT → SKIP
         └── NOT PRESENT → continue
         
  3. WebApplicationType condition (if annotated)
     ├── @ConditionalOnWebApplication(type=SERVLET) but app is REACTIVE → SKIP
     └── matches → continue
     
  4. Property condition (if annotated)
     ├── @ConditionalOnProperty("spring.datasource.enabled", matchIfMissing=true)
     │   ├── Property exists AND value is "false" → SKIP
     │   └── Property missing or "true" → continue
     
  5. Bean conditions (evaluated LATER, during actual @Configuration parsing)
     ├── @ConditionalOnBean(DataSource.class) at class level
     │   └── Evaluated during ConfigurationClassParser phase
     ├── @ConditionalOnMissingBean(DataSource.class) at @Bean method level
     │   └── Evaluated when the @Bean method is being considered
     
  6. All conditions pass → Register BeanDefinitions from @Bean methods
     └── These become candidates for instantiation in later refresh step
```

## 6. Lifecycle Diagrams

### SpringApplication Lifecycle Events (In Order)

```
├── ApplicationStartingEvent
│   └── Fired: After listeners registered, before anything else
│   └── Context: Bootstrap context only, no ApplicationContext yet
│   └── Usage: Early initialization (logging systems, external config)
│
├── ApplicationEnvironmentPreparedEvent
│   └── Fired: After environment prepared, before context created
│   └── Context: Environment is available (profiles, properties)
│   └── Usage: Modify environment, add property sources
│
├── ApplicationContextInitializedEvent
│   └── Fired: After context created and prepared, before refresh
│   └── Context: ApplicationContext exists but not refreshed
│   └── Usage: Modify context before bean definitions loaded
│
├── ApplicationPreparedEvent
│   └── Fired: After refresh starts, bean definitions loaded, before instantiation
│   └── Context: ApplicationContext with BeanDefinitions but no beans yet
│   └── Usage: Last chance to modify bean definitions
│
├── ContextRefreshedEvent       ← Fired during refresh()
│   └── Fired: At end of AbstractApplicationContext.refresh()
│   └── Context: All beans instantiated, web server running
│
├── WebServerInitializedEvent
│   └── Fired: After embedded web server is initialized
│   └── Context: Web server running, bound to port
│   └── Usage: Programmatic servlet/filter registration
│
├── ApplicationStartedEvent
│   └── Fired: After context refreshed, before runners
│   └── Context: Fully operational, before runners execute
│   └── Usage: Signal readiness to external systems
│
├── AvailabilityChangeEvent(LivenessState.CORRECT)
│   └── Fired: Application is alive
│
├── ApplicationReadyEvent
│   └── Fired: After runners complete (or fail if no runners)
│   └── Context: Ready to serve traffic
│   └── Usage: Most common integration point
│
└── AvailabilityChangeEvent(ReadinessState.ACCEPTING_TRAFFIC)
    └── Fired: Application is ready for requests
    └── Consumed by: Kubernetes readiness probe, Actuator health
```

### Auto-Configuration Class Lifecycle

```
┌──────────────────────────────────────────────────────────────┐
│  1. CLASS WRITTEN (by Spring Boot team or you)               │
│     @AutoConfiguration                                       │
│     @ConditionalOnClass(SomeClass.class)                     │
│     public class MyAutoConfiguration { ... }                 │
│                                                              │
│  2. REGISTERED (in *.imports file)                           │
│     META-INF/spring/org.springframework.boot.                │
│       autoconfigure.AutoConfiguration.imports                │
│     → Class name is added to candidate list                  │
│                                                              │
│  3. SCANNED (at startup)                                     │
│     AutoConfigurationImportSelector reads ALL imports files  │
│     from ALL JARs on classpath into a single list            │
│                                                              │
│  4. FILTERED (early: OnClassCondition)                       │
│     ASM reads @ConditionalOnClass from bytecode              │
│     without loading the class                                │
│     → Class matched (required classes present) or SKIPPED    │
│                                                              │
│  5. PARSED (ConfigurationClassParser)                        │
│     Class loaded, @Configuration scanning applied:           │
│     ├── @Bean methods registered as BeanDefinitions          │
│     ├── Method-level @Conditional* evaluated                 │
│     ├── @Import on methods processed                        │
│     └── Nested @Configuration classes processed             │
│                                                              │
│  6. BEAN DEFINITIONS REGISTERED                              │
│     BeanDefinition stored in BeanDefinitionRegistry          │
│     Each @Bean → one BeanDefinition                         │
│                                                              │
│  7. BEAN INSTANTIATED (later, during preInstantiateSingletons)│
│     BeanFactory calls getBean() → createBean() → doCreateBean()│
│     ├── Constructor resolved                                │
│     ├── Dependencies injected                                │
│     ├── @PostConstruct called                               │
│     └── Bean ready, stored in singletonObjects cache         │
│                                                              │
│  8. BEAN DESTROYED (at shutdown)                             │
│     ApplicationContext.close() → destroyBeans()              │
│     └── @PreDestroy called → DisposableBean.destroy()        │
└──────────────────────────────────────────────────────────────┘
```

## 7. Source Code Reading Guide

### Critical Files to Read (In Order)

```
1. SpringApplication.java
   spring-boot/spring-boot/src/main/java/org/springframework/boot/SpringApplication.java
   → Read: constructor, run(), prepareEnvironment(), prepareContext(), refreshContext()
   → This is your entry point. Understand every step before moving on.

2. AutoConfigurationImportSelector.java
   spring-boot/spring-boot-autoconfigure/src/main/java/org/springframework/boot/
   autoconfigure/AutoConfigurationImportSelector.java
   → Read: selectImports(), getAutoConfigurationEntry(), getCandidateConfigurations()
   → This is the heart of auto-configuration.

3. ImportCandidates.java
   spring-framework/spring-core/src/main/java/org/springframework/core/io/support/
   ImportCandidates.java
   → How *.imports files are loaded across all JARs

4. ConfigurationClassParser.java
   spring-framework/spring-context/src/main/java/org/springframework/context/
   annotation/ConfigurationClassParser.java
   → Read: parse(), processConfigurationClass(), doProcessConfigurationClass()
   → How @Configuration classes are parsed and BeanDefinitions are registered

5. ConditionEvaluator.java
   spring-framework/spring-context/src/main/java/org/springframework/context/
   annotation/ConditionEvaluator.java
   → Read: shouldSkip()
   → How @Conditional annotations are evaluated

6. AutoConfigurationReport.java (Spring Boot 2.x) / ConditionEvaluationReport.java
   spring-boot/spring-boot/src/main/java/org/springframework/boot/autoconfigure/
   condition/ConditionEvaluationReport.java
   → How condition evaluation results are captured for --debug output

7. OnClassCondition.java
   spring-boot/spring-boot-autoconfigure/src/main/java/org/springframework/boot/
   autoconfigure/condition/OnClassCondition.java
   → Read: getMatchOutcome()
   → How class presence is checked efficiently using ClassUtils.isPresent() and ASM

8. WebServerFactoryCustomizerBeanPostProcessor.java
   → How embedded server customization is applied

9. AnnotationConfigServletWebServerApplicationContext.java
   → The context type for Servlet-based apps; extends a deep hierarchy

10. ServletWebServerApplicationContext.java
    → Read: onRefresh(), createWebServer()
    → Where the embedded web server actually starts
```

### Source Code Breadcrumb Trace

```
SpringApplication.run(MyApp.class, args)      // Line ~1300 in SpringApplication.java
  → this.run(args)
    → stopWatch.start()
    → bootstrapContext = createBootstrapContext()    // Line ~300
    → listeners = getRunListeners(args)
    → listeners.starting(bootstrapContext)
    → arguments = new DefaultApplicationArguments(args)
    → environment = prepareEnvironment(listeners, bootstrapContext, arguments) // Line ~350
        → getOrCreateEnvironment()
        → configureEnvironment()
        → configurePropertySources()
        → configureProfiles()
        → listeners.environmentPrepared(environment)
            → ConfigFileApplicationListener.onApplicationEvent()  // Loads application.properties
    → printBanner(environment)
    → context = createApplicationContext()            // Line ~400
    → prepareContext(bootstrapContext, context, environment, ...)
    → refreshContext(context)
        → AbstractApplicationContext.refresh()        // 12-step sequence
    → afterRefresh(context, arguments)
    → listeners.started(context, timeTaken)
    → callRunners(context, arguments)
    → listeners.ready(context, timeTakenToReady)
```

## 8. Production Failure Scenarios

### Scenario 1: Application Fails to Start After Upgrading JAR Dependency

**Symptom**: App starts fine on 3.1.x, fails on 3.2.x upgrade with `BeanCreationException`.

**Root cause**: The upgraded library removed a class that an auto-configuration's `@ConditionalOnClass` references. The auto-configuration now silently deactivates (or worse, a different auto-configuration activates because a `@ConditionalOnMissingBean` condition changed).

**Diagnosis**:
```bash
java -jar myapp.jar --debug 2>&1 | grep "did not match"
```

Look for auto-configuration classes that matched before the upgrade but no longer match. The `@ConditionalOnMissingBean` scenarios are harder — a bean that was previously provided by auto-config A is now missing because auto-config A was deactivated.

**Resolution**: Add `spring.autoconfigure.exclude` to disable the alternative auto-config, or explicitly define the missing bean in an `@Configuration` class.

### Scenario 2: Wrong DataSource Used in Production

**Symptom**: Application connects to H2 in-memory database in production instead of the configured PostgreSQL.

**Root cause**: `DataSourceAutoConfiguration` has multiple `@Configuration` inner classes: `EmbeddedDatabaseConfiguration` (activates when no explicit DataSource and embedded DB driver is present) and `PooledDataSourceConfiguration` (activates when spring.datasource.url is set). If `spring.datasource.url` is missing from production config (because it's set via environment variable that is empty in the deployed container), the embedded path activates.

**Diagnosis**:
```bash
# Check which DataSource bean was created
curl http://localhost:8080/actuator/beans | jq '.contexts.application.beans[] | select(.type=="javax.sql.DataSource")'

# Or from startup logs with debug:
java -jar myapp.jar --debug 2>&1 | grep "DataSourceAutoConfiguration"
```

**Resolution**: Add a validation bean that checks for production profile and refuses to start if H2 is active:
```java
@Bean
@Profile("production")
public CommandLineRunner validateProductionConfig(DataSource dataSource) {
    return args -> {
        String url = dataSource.getConnection().getMetaData().getURL();
        if (url.contains("h2")) {
            throw new IllegalStateException("H2 database in production profile!");
        }
    };
}
```

### Scenario 3: Slow Startup Due to Auto-Configuration Class Loading

**Symptom**: Fresh startup takes 30+ seconds.

**Root cause**: 200+ auto-configuration classes each trigger class loading during `@ConditionalOnClass` evaluation. Many of those classes have transitive dependencies that pull in more classes. OnClassCondition uses ASM to avoid loading the auto-config class itself, but it cannot avoid loading the classes referenced in `@ConditionalOnClass(value = SomeDbDriver.class)`.

**Diagnosis**: Enable startup profiling:
```java
// Add to application.properties:
spring.application.startup.log-step=true

// Or programmatically:
new SpringApplicationBuilder(MyApp.class)
    .applicationStartup(new BufferingApplicationStartup(10000))
    .run(args);

// Access: /actuator/startup (requires spring-boot-starter-actuator)
```

**Resolution**: Use `spring.autoconfigure.exclude` to disable auto-configurations you don't need. Exclude entire categories:
```properties
spring.autoconfigure.exclude=\
  org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,\
  org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,\
  org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration,\
  org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration,\
  org.springframework.boot.autoconfigure.webservices.WebServicesAutoConfiguration
```

### Scenario 4: Custom Starter Keeps Activating When It Shouldn't

**Symptom**: Custom auto-configuration's beans always appear, even in test profiles.

**Root cause**: `@ConditionalOnMissingBean` with wrong search strategy. Default is `SearchStrategy.ALL`, which searches parent contexts too. In tests, the parent context might have a bean that the child doesn't, or vice versa.

**Diagnosis**: Add `--debug` to test run and inspect condition evaluation report. Pay attention to which bean (in which context) matched or didn't match.

**Resolution**: Set explicit search strategy: `@ConditionalOnMissingBean(value = MyService.class, search = SearchStrategy.CURRENT)` to only check the current context.

## 9. Debugging Techniques

### The --debug Flag: Reading Condition Evaluation Report

```bash
java -jar myapp.jar --debug
```

Output is structured as:

```
============================
CONDITIONS EVALUATION REPORT
============================

Positive matches:
-----------------

   DataSourceAutoConfiguration matched:
      - @ConditionalOnClass found required class 'javax.sql.DataSource'
      - @ConditionalOnProperty (spring.datasource.enabled) matched

   DataSourceAutoConfiguration.PooledDataSourceConfiguration matched:
      - @ConditionalOnProperty (spring.datasource.type) did not find property 'type'
      - @ConditionalOnClass found required class 'com.zaxxer.hikari.HikariDataSource'

Negative matches:
-----------------

   HibernateJpaAutoConfiguration:
      Did not match:
         - @ConditionalOnBean (types: javax.sql.DataSource;
            SearchStrategy: all) did not find any beans of type javax.sql.DataSource

   FlywayAutoConfiguration:
      Did not match:
         - @ConditionalOnClass found required class 'org.flywaydb.core.Flyway'
         - @ConditionalOnProperty (spring.flyway.enabled) matched (OnPropertyCondition)
         - @ConditionalOnBean (types: javax.sql.DataSource;
            SearchStrategy: all) did not find any beans of type javax.sql.DataSource

Unconditional classes:
----------------------

   org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration

Exclusions:
-----------

   org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration
```

**How to interpret**:
- **Positive matches**: These configurations will create beans. Verify each one is expected.
- **Negative matches with "required class not found"**: Expected if you didn't include the dependency.
- **Negative matches with "did not find any beans"**: This is the most common surprise. It means a prerequisite auto-configuration did not activate. Check WHY the prerequisite failed.
- **Unconditional classes**: Always loaded. Usually framework infrastructure.
- **Exclusions**: Explicitly excluded via properties or annotations.

### Programmatic Condition Evaluation

```java
// In a test or ApplicationRunner:
@Component
public class AutoConfigurationDebugger {
    @Autowired
    private ApplicationContext context;

    @EventListener(ApplicationReadyEvent.class)
    public void debugAutoConfig() {
        ConfigurableApplicationContext ctx = (ConfigurableApplicationContext) context;
        ConditionEvaluationReport report = ConditionEvaluationReport.get(
            ctx.getBeanFactory());

        System.out.println("=== POSITIVE MATCHES ===");
        report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
            if (!source.startsWith("unconditional") && outcomes.isFullMatch()) {
                System.out.println(" + " + source);
            }
        });

        System.out.println("\n=== NEGATIVE MATCHES ===");
        report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
            if (!outcomes.isFullMatch()) {
                System.out.println(" - " + source);
                outcomes.forEach(outcome -> {
                    if (!outcome.isMatch()) {
                        System.out.println("     Reason: " + outcome.getMessage());
                    }
                });
            }
        });
    }
}
```

### Debugging Startup Failures

```bash
# 1. Get a detailed stack trace
java -jar myapp.jar --debug 2>&1 | tee startup.log

# 2. Use verbose class loading to find missing classes
java -verbose:class -jar myapp.jar 2>&1 | grep "not found"

# 3. Trace specific auto-configuration
java -jar myapp.jar --debug 2>&1 | grep -A5 "DataSourceAutoConfiguration"

# 4. Check which JARs provide auto-configuration imports
jar tf myapp.jar | grep "AutoConfiguration.imports"
# And for each dependency:
find ~/.gradle/caches -name "*.jar" -exec sh -c \
  'jar tf "$1" 2>/dev/null | grep -q "AutoConfiguration.imports" && echo "$1"' _ {} \;

# 5. Check what's on the classpath
java -jar myapp.jar --debug 2>&1 | head -100
# The debug output starts with classpath listing

# 6. Isolate auto-configuration for testing
@SpringBootTest(classes = {DataSourceAutoConfiguration.class})
class DataSourceAutoConfigurationTest {
    @Autowired
    private DataSource dataSource;
    
    @Test
    void shouldCreateDataSource() {
        assertThat(dataSource).isNotNull();
    }
}
```

## 10. Observability Considerations

### Startup Observability with Micrometer

```java
// Enable startup time measurements:
@Configuration
public class StartupMetricsConfig {
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> startupMetrics(
            ApplicationStartup applicationStartup) {
        return registry -> {
            // ApplicationStartup is available via ApplicationContext
            // On Spring Boot 3.x with Java Flight Recorder:
        };
    }
}

// Add to application.properties:
management.metrics.export.prometheus.enabled=true
management.endpoints.web.exposure.include=health,metrics,startup
```

### Key Startup Metrics to Monitor

```
| Metric | What It Tells You | Alerting Threshold |
|--------|-------------------|-------------------|
| application.started.time | Time until ApplicationStartedEvent | > 30s in prod |
| application.ready.time | Time until ApplicationReadyEvent (includes runners) | > 60s in prod |
| spring.beans.definitions | Total number of BeanDefinitions registered | Measure trend |
| spring.beans.singletons | Total number of singleton beans instantiated | Measure trend |
| spring.context.refresh.time | Time spent in refresh() | > 20s warning |
| jvm.classes.loaded | Total classes loaded (proxy for complexity) | Measure trend |
| jvm.memory.used (post-startup) | Memory after startup | > 70% of -Xmx warning |
```

### Startup Time Budget

For a production application:
```
Target: < 60s for startup (Kubernetes readiness probe window)
Breakdown budget:
  └── SpringApplication.run(): < 50s
       ├── Environment preparation: < 5s
       ├── Bean definition loading: < 10s
       ├── Bean instantiation: < 25s
       │   ├── Hibernate scan: < 10s
       │   └── Connection pool init: < 2s
       └── Web server start: < 3s
  └── Application runners: < 10s

If any phase exceeds budget:
  → Hibernate scan: @EntityScan with packages, not root package
  → Bean instantiation: lazy-initialization (trade-off with first-request latency)
  → Environment: reduce property source count, avoid remote config servers
```

## 11. Performance Implications

### Startup Performance Anti-Patterns

1. **Full classpath scan for @Entity classes**: By default, `@EnableJpaRepositories` scans from the `@SpringBootApplication` package downward. Large projects with many non-entity classes waste time scanning. Fix: `@EntityScan(basePackages = "com.myapp.domain")`.

2. **Too many @Configuration classes being parsed**: Each `@Configuration` class requires `ConfigurationClassParser` to process it. Prefer `@AutoConfiguration` over `@Configuration` when building shared libraries (auto-configs are deferred, loaded on demand).

3. **Eager bean initialization that touches external systems**: If bean `@PostConstruct` calls an external API, startup blocks until that API responds (or times out). Fix: Use `ApplicationRunner` with async execution or lazy-init-beans.

4. **Metaspace pressure from excessive class generation**: Hibernate's bytecode enhancement, Spring's CGLIB proxies, and Lombok annotation processors all generate classes that consume Metaspace. Monitor `jvm.memory.used` for Metaspace separately from heap.

### Lazy Initialization Performance Tradeoff

```properties
spring.main.lazy-initialization=true
```

With lazy initialization:
- Startup time: ~70% reduction (from 8.7s to ~2.5s in the earlier example)
- First request latency: adds 2-3 seconds (as beans are created on first access)
- Cold-path detection: triggers `LazyInitializationException` outside transaction boundaries
- Restart during development: much faster with DevTools

**Production recommendation**: Keep `lazy-initialization=false` (default). Use AOT processing (Spring Boot 3.x native compilation) for startup time reduction instead. Lazy initialization is a development optimization, not a production one.

### Spring Boot 3.x AOT Processing

```bash
# Generate AOT source code (build time):
mvn spring-boot:process-aot

# The AOT engine:
# 1. Evaluates @Conditional at BUILD TIME
# 2. Generates source files that register beans directly (bypassing condition evaluation)
# 3. Generates reflection hints for GraalVM native image
# 4. Reduces startup time by 50-70% after compilation

# Generated AOT sources look like:
// build/generated/aotSources/.../MyApp__ApplicationContextInitializer.java
public class MyApp__ApplicationContextInitializer {
    public static void registerBeans(BeanDefinitionRegistry registry) {
        // Directly registers beans that passed @Conditional at build time
        // No runtime condition evaluation needed
        GenericBeanDefinition bd = new GenericBeanDefinition();
        bd.setBeanClass(DataSource.class);
        bd.setInstanceSupplier(DataSourceAutoConfiguration::dataSource);
        registry.registerBeanDefinition("dataSource", bd);
    }
}
```

**AOT limitations**:
- `@ConditionalOnExpression` with runtime variables — cannot be pre-computed
- Dynamic profiles — must be known at build time or handled with fallback
- `@ConditionalOnBean` based on beans defined in other libraries — resolved at build time; if classpath changes after build, this becomes stale

## 12. Architecture Implications

### Starter Design Patterns

A well-designed Spring Boot starter has three components:

```
my-starter/
├── my-starter/                    ← The autoconfigure module
│   └── src/main/java/com/example/starter/
│       ├── MyServiceAutoConfiguration.java
│       ├── MyServiceProperties.java    (bound from spring.myservice.*)
│       └── MyService.java             (the actual bean implementation)
│   └── src/main/resources/
│       ├── META-INF/spring/
│       │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│       └── application.properties     (default properties)
│
├── my-starter-core/               ← The library logic (NO Spring dependency)
│   └── src/main/java/com/example/starter/core/
│       ├── MyLibClient.java
│       └── MyLibConfig.java
│
└── samples/                       ← Sample usage
    └── my-starter-sample/
```

**Key principles**:
1. **Separate autoconfigure from core logic**: The autoconfigure module depends on core and Spring Boot. Core has NO Spring dependency. This means core can be tested independently and used without Spring Boot.
2. **Use `@AutoConfiguration` not `@Configuration`**: `@AutoConfiguration` is ordered AFTER user `@Configuration` classes, so user can override default beans.
3. **Properties class with `@ConfigurationProperties`**: Never hardcode configuration. Bind to a `@ConfigurationProperties` class with `prefix = "spring.myservice"`.
4. **`@ConditionalOnMissingBean` on EVERY @Bean method**: Allow the user to override any bean by defining their own.
5. **Register in imports file, not spring.factories**: Spring Boot 3.x convention.

### Custom Auto-Configuration Example

```java
// ── my-starter-core/.../ApiClient.java ──
// Pure Java, no Spring dependency
public class ApiClient {
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;

    public ApiClient(ApiClientConfig config) {
        this.baseUrl = config.baseUrl();
        this.timeout = config.timeout();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();
    }

    public String fetchData(String path) { /* ... */ }
}

public record ApiClientConfig(String baseUrl, Duration timeout, int maxRetries) {}

// ── my-starter/.../ApiProperties.java ──
@ConfigurationProperties(prefix = "myapi")
public class ApiProperties {
    private String baseUrl = "https://api.default.com";
    private Duration timeout = Duration.ofSeconds(5);
    private int maxRetries = 3;
    // getters and setters
}

// ── my-starter/.../ApiAutoConfiguration.java ──
@AutoConfiguration
@EnableConfigurationProperties(ApiProperties.class)
@ConditionalOnClass(ApiClient.class)
public class ApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApiClient apiClient(ApiProperties properties) {
        ApiClientConfig config = new ApiClientConfig(
            properties.getBaseUrl(),
            properties.getTimeout(),
            properties.getMaxRetries()
        );
        return new ApiClient(config);
    }
}

// ── my-starter/.../META-INF/spring/...AutoConfiguration.imports ──
com.example.starter.ApiAutoConfiguration
```

### When Auto-Configuration Is the Wrong Tool

Do NOT use auto-configuration when:
- The bean depends on runtime state that cannot be expressed in a `@Conditional` (e.g., "create this bean if database query X returns Y results")
- The bean must exist unconditionally in every application using your library — use `@Configuration` instead
- You need guaranteed ordering: auto-configuration classes are ordered but there are no ordering guarantees between two auto-configurations in different starters
- The decision is business logic, not infrastructure wiring — keep business logic in your domain layer

## 13. Team Ownership Implications

### Who Owns Auto-Configuration Debugging?

| Symptom | Owned By | Because |
|---------|----------|---------|
| Missing auto-config due to missing JAR | Platform/DevOps team | Dependency management |
| Wrong DataSource selected | Application team | Configuration ownership |
| Auto-config condition failure after framework upgrade | Platform team | Framework version management |
| Custom starter not activating | The team that BUILT the starter | Starter maintenance |
| Slow startup due to too many auto-configs | Application team | They know which auto-configs are needed |
| Production crash from bean creation in auto-config | Application team (owner of config/properties causing the crash) | The bean class often fails due to external dependency (DB, cache) misconfiguration |

### Team Design for Starter Ownership

When multiple teams build their own starters:
1. Each starter has a CODEOWNERS entry
2. Each starter's compatibility matrix (which Spring Boot versions it supports) is documented
3. Breaking changes in starters follow semver
4. Starters are published to an internal artifact repository with CI/CD pipelines that test against multiple Spring Boot versions
5. A "starter catalog" exists with documentation, usage examples, and a dashboard showing which services use which starter

## 14. Interview Questions

### Question 1: "Walk me through exactly what happens from `SpringApplication.run()` to the application being ready to serve HTTP requests."

**Staff-level answer**: I start by going to `SpringApplication.java`. The `run()` method executes a 13-step sequence. First, it creates a `BootstrapContext` and sets up `SpringApplicationRunListeners`. Then it fires `ApplicationStartingEvent`, prepares the `Environment` — which loads `application.properties`, resolves profiles, and activates property sources in a specific priority order. Next it creates the `ApplicationContext` — the type depends on whether Servlet or Reactive is on the classpath. Then `prepareContext` applies initializers, registers the primary source as a bean definition, and fires `ApplicationPreparedEvent`.

The critical phase is `refreshContext()`, which delegates to `AbstractApplicationContext.refresh()`. This 12-step sequence includes `invokeBeanFactoryPostProcessors()`, where `ConfigurationClassPostProcessor` parses `@Configuration` classes and `@ComponentScan`, and the `DeferredImportSelector` — our `AutoConfigurationImportSelector` — loads all auto-configuration class names from `META-INF/spring/*.imports` files across all JARs, filters them by `@ConditionalOnClass` using ASM-based parsing (to avoid loading the class), applies exclusions, and registers matching `@Bean` methods as `BeanDefinition` instances. No beans are created yet — only definitions.

Next, `finishBeanFactoryInitialization()` pre-instantiates all non-lazy singleton beans. This is where `DataSource`, `EntityManagerFactory`, and the embedded web server beans are actually created. `onRefresh()` in `ServletWebServerApplicationContext` starts the embedded Tomcat/Jetty server, binding it to the configured port. After refresh, `afterRefresh()` provides an extension point. `ApplicationStartedEvent` fires, then `CommandLineRunner` and `ApplicationRunner` beans execute. Finally, `ApplicationReadyEvent` fires, and internally Spring publishes `AvailabilityChangeEvent(ReadinessState.ACCEPTING_TRAFFIC)`, which the Actuator health endpoint translates into the readiness probe response.

The entire auto-configuration mechanism is deterministic: given the same classpath and same properties, the same beans will be created every time. The `--debug` flag proves this by showing the exact condition evaluation for each auto-configuration class.

### Question 2: "You've been asked to build a custom Spring Boot starter for an internal service client at your company. Walk me through the design."

**Staff-level answer**: I structure it as a three-module project: the core library module, the autoconfigure module, and samples. The **core module** has zero Spring dependencies — it's plain Java. This is critical because it can be unit-tested without the Spring container, used in non-Spring applications, and won't break if Spring Boot's API changes.

Inside the autoconfigure module, I create three artifacts. First, a `@ConfigurationProperties` class with `prefix = "myclient"` that maps all configuration parameters with sensible defaults — base URL, timeouts, retry counts, thread pool sizes. Defaults are essential; the `@ConditionalOnMissingBean` at every `@Bean` method means the user can override any single bean without losing the rest. The class is annotated `@AutoConfiguration` (not `@Configuration` — this is a Spring Boot 3.x change that ensures auto-config classes load after user configuration) and registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

I use `@ConditionalOnClass` on the class to ensure the core library JAR is present, and `@ConditionalOnMissingBean` on each `@Bean` method so users can override individual beans. I expose a `HealthIndicator` bean for the service client so it integrates with Actuator's health endpoint automatically.

For the build, the autoconfigure module has an optional dependency on the core module. The user's project includes the starter, which transitively brings in both. This way, if for some reason the user can't or doesn't want auto-configuration, they can depend on the core module directly and wire beans manually. The starter BOM manages version alignment internally.

Finally, I write integration tests using `@SpringBootTest` against an ApplicationContext loaded with only the auto-configuration classes. I use WireMock to simulate the external API, test the fallback behavior when the property is set to an unreachable URL, and verify the health indicator reports DOWN status correctly. I also include `spring-boot-configuration-processor` as an optional dependency with `<optional>true</optional>` to generate `spring-configuration-metadata.json` for IDE autocompletion.

### Question 3: "You have an application that sometimes uses H2 in production because the database URL property is missing from the deployment configuration. How would you prevent this at the architecture level?"

**Staff-level answer**: This is a defense-in-depth problem. The root cause is that `DataSourceAutoConfiguration` has a fallback path (`EmbeddedDatabaseConfiguration`) that activates when no explicit `DataSource` bean exists and an embedded driver is on the classpath. On paper, this is great for development. In practice, embedded databases in production are catastrophic.

The fix has four layers. **Layer 1 — Explicit exclusion**: In production profiles, define a bean that fails-fast if the wrong database is detected:
```java
@Configuration
@Profile("production")
public class ProductionGuardConfig {
    @Bean
    public CommandLineRunner validateDatabaseProduction(DataSource ds) {
        return args -> {
            String url = ds.getConnection().getMetaData().getURL();
            if (url.contains("h2") || url.contains("hsqldb")) {
                throw new StartupFailureException("Embedded database in production!");
            }
        };
    }
}
```
This runs as an `ApplicationRunner` (highest priority, order=0) to fail before any traffic is served.

**Layer 2 — Remove the dependency footprint**: Use Gradle/Maven scoping to exclude H2 from production deployments. Create a `devImplementation` configuration that includes H2, and keep `runtimeOnly` free of H2. This way, even if `spring.datasource.url` is missing, `DataSourceAutoConfiguration` cannot fall back to the embedded path because the H2 driver class is absent. The application will fail with a clear error (`BeanCreationException: Failed to instantiate DataSource`) instead of silently connecting to an in-memory database.

**Layer 3 — Property validation at startup**: Use `@Validated` with `@ConfigurationProperties` and `@NotEmpty` on `spring.datasource.url` for production profiles:
```java
@Validated
@ConfigurationProperties(prefix = "spring.datasource")
@Profile("production")
public record ProductionDataSourceProperties(
    @NotEmpty String url,
    @NotEmpty String username,
    @NotEmpty String password
) {}
```

**Layer 4 — Runtime health check**: The Actuator health endpoint with a custom `HealthIndicator` that queries `select 1` against the database and tags the result with the database product name. Alert if the tag `db=H2` appears in the production metrics pipeline.

This defense-in-depth approach means: if Layer 4 fires, you have a problem. If Layer 1 triggers, the application refuses to start. If Layer 2 and Layer 3 work correctly, Layer 1 never triggers because H2 was never on the classpath. The key principle is enabling safe defaults — fail loud, fail early, fail before customers are impacted.

## 15. Hands-On Exercises

1. **Run a Spring Boot application with `--debug` and analyze the conditions report**: Start a basic Spring Boot web app. Read every line of the conditions evaluation report. For each negative match, explain why it didn't match. For each positive match, explain what beans it will create. Find at least one auto-configuration you didn't know was activating.

2. **Build a custom Spring Boot starter from scratch**: Create a three-module Gradle project (core, autoconfigure, sample). The starter should configure an HTTP client for a weather API. Include `@ConfigurationProperties` with IDE metadata, `@ConditionalOnClass`, `@ConditionalOnMissingBean` on every bean, and a `HealthIndicator`. Test with `@SpringBootTest(classes = {WeatherAutoConfiguration.class})`.

3. **Debug a startup failure**: Intentionally break an auto-configuration by providing wrong properties. Remove the database driver JAR but keep `spring.datasource.url`. Observe the error. Now add the JAR back but set a wrong password. Observe the different error. Understand the distinction between condition failure (silent) and bean creation failure (loud).

4. **Optimize application startup time**: Start with a full Spring Boot app with JPA. Profile startup time. Exclude unnecessary auto-configurations. Measure the difference. Use `BufferingApplicationStartup`. Identify the top-5 slowest beans to initialize.

5. **Trace the AutoConfigurationImportSelector code path**: Set a breakpoint in `AutoConfigurationImportSelector.getAutoConfigurationEntry()`. Step through the loading of auto-configuration imports, the filtering by OnClassCondition, and the registration of bean definitions. Observe how class-level conditions are evaluated before method-level conditions.

## 16. Advanced Challenges

1. **Implement a `@ConditionalOnPropertyCombination` annotation**: Create a custom `@Conditional` that only matches when a specific combination of property values is present. For example: `@ConditionalOnPropertyCombination({"db.type=postgresql", "cache.type=redis"})`. The condition should support SpEL expressions. Test it with `ApplicationContextRunner`.

2. **Build a startup-time optimizer that identifies unnecessary auto-configurations**: Write a tool that parses the `--debug` output (or programmatically reads `ConditionEvaluationReport`), cross-references the negative-matches list with the classpath, and generates an optimal `spring.autoconfigure.exclude` list. It should distinguish between "this auto-config didn't match because you don't use MongoDB" (safe to exclude) vs "this didn't match because its prerequisite bean is missing, but fixing the prerequisite would make it match" (don't exclude).

3. **Migrate a custom Spring Boot 2.x starter (using `spring.factories`) to Spring Boot 3.x**: Handle the migration of `@Configuration` to `@AutoConfiguration`, `spring.factories` to `AutoConfiguration.imports`, and `javax.*` to `jakarta.*` imports. Update any `@ConditionalOnClass` references. Add AOT processing support with `RuntimeHintsRegistrar`.

4. **Implement a "dual-mode" starter that works as both Spring Boot auto-configuration and as a plain Java library**: The core module is pure Java. The autoconfigure module provides Spring Boot integration. Write a test that verifies the core module works without Spring (pure POJO construction). Write another test that verifies the autoconfigure module wires everything automatically. Then write a third test that verifies the user can depend on core alone without pulling in Spring Boot transitively.

5. **Build a condition evaluation debugger UI**: Write a Spring Boot application that, upon receiving a GET `/actuator/conditions`, returns an HTML page that visualizes auto-configuration evaluation as a decision tree. For each auto-configuration class, show: (a) class-level conditions and their outcomes, (b) method-level conditions per @Bean, (c) dependency chain (which auto-configurations must succeed for this one to match), and (d) which properties influenced each condition. Use the `ConditionEvaluationReport` API to collect data at runtime.
