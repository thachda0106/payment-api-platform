# Session 25: Source Code Reading Mastery

## 1. Why This Topic Exists

You will spend more time reading code than writing it. At Staff level, you read code to debug production incidents, evaluate architecture decisions made years ago, onboard onto foreign codebases in hours, and understand framework behavior that documentation glosses over. A Staff engineer who cannot read Spring Framework source code is a Staff engineer who cannot answer questions about transaction propagation in nested `@Transactional` calls, cannot explain why a `@Cacheable` method silently returns stale data, and cannot debug a `NoSuchBeanDefinitionException` that the docs call "expected behavior."

Spring Framework is ~1.2 million lines of Java. Spring Boot adds another ~600K. Nobody reads it all. The skill is knowing exactly which 5% of those lines matter for any given problem and reading them in the right order.

**Staff engineer insight**: The difference between a senior engineer and a Staff engineer is not that the Staff engineer knows more facts about Spring. It is that the Staff engineer knows how to acquire knowledge on demand. Reading framework source code is the universal fallback when documentation, Stack Overflow, and ChatGPT all fail.

## 2. Mental Model

```
Source Code Reading Efficacy = f(Goal Clarity, Navigation Speed, Pattern Recognition, Filter Discipline)

NOT Source Code Reading = "Start at line 1 and read sequentially"
```

The mental model has three layers:

| Layer | Question | Technique |
|-------|----------|-----------|
| Goal | What specific behavior do I need to understand? | Write a test that reproduces the behavior first. Now you know exactly what to trace. |
| Navigation | How do I find the relevant code quickly? | Interface → Key Implementation → Main Method → Breakpoints, not file-by-file reading |
| Filter | What do I ignore? | JavaDoc comments, getters/setters, logging statements, exception constructors, internal utility methods |

The discipline is counterintuitive: **reading less code makes you more effective**. A 10,000-line class might have 300 lines that matter for your question. Your job is to find those 300 lines and ignore the other 9,700.

```
AnnotatedSpringApplication.run()
  └─ refresh()
       └─ finishBeanFactoryInitialization()  ← You care about THIS
            └─ preInstantiateSingletons()
                 └─ getBean("myService")
                      └─ doCreateBean()
                           └─ populateBean()     ← And THIS
                           └─ initializeBean()   ← And THIS

NOT AnnotatedSpringApplication.run()
  └─ refresh()
       └─ prepareRefresh()         ← SKIP (standard boilerplate)
       └─ obtainFreshBeanFactory() ← SKIP (you're not refreshing twice)
       └─ prepareBeanFactory()     ← SKIP (standard setup)
       └─ postProcessBeanFactory() ← MAYBE (check if custom post-processing)
       └─ invokeBeanFactoryPostProcessors() ← MAYBE (if you care about @Configuration parsing)
       └─ registerBeanPostProcessors()      ← MAYBE (if you care about post-processors)
       └─ initMessageSource()               ← SKIP (i18n infrastructure)
       └─ initApplicationEventMulticaster()  ← SKIP (event infrastructure)
       └─ onRefresh()                        ← MAYBE (web server starts here)
       └─ registerListeners()                ← SKIP
       └─ finishBeanFactoryInitialization()  ← READ CAREFULLY
```

## 3. Internal Architecture

### Spring Framework Module Map

The Spring Framework is a multi-module Gradle project. Understanding the module boundaries is prerequisite to knowing which JAR to look in.

```
spring-framework/
│
├── spring-core/                    ← Foundation: not optional
│   ├── org.springframework.core    ← Ordered, PriorityOrdered, AttributeAccessor
│   ├── org.springframework.cglib   ← Repackaged CGLIB for proxy generation
│   ├── org.springframework.asm     ← Repackaged ASM for bytecode reading
│   ├── org.springframework.util    ← CollectionUtils, StringUtils, ReflectionUtils, ClassUtils,
│   │                                   SerializationUtils, Assert, ObjectUtils
│   ├── org.springframework.lang    ← @Nullable, @NonNullApi, @NonNullFields
│   └── org.springframework.core.io ← Resource, ResourceLoader, InputStreamSource,
│                                       ClassPathResource, FileSystemResource
│
│   When to read: Always — ClassUtils.isPresent() is the foundation of auto-configuration.
│   Key classes: ClassUtils, ReflectionUtils, Assert, Resource
│
├── spring-beans/                   ← Bean definition and instantiation
│   ├── org.springframework.beans   ← BeanFactory, BeanWrapper, PropertyAccessorFactory
│   ├── org.springframework.beans.factory
│   │   ├── BeanFactory             ← The root interface for bean containers
│   │   ├── ListableBeanFactory     ← Lists beans by type
│   │   ├── HierarchicalBeanFactory ← Parent-child contexts
│   │   ├── AutowireCapableBeanFactory ← Autowiring, initialization callbacks
│   │   ├── ConfigurableBeanFactory ← Singleton registry, scopes
│   │   └── ConfigurableListableBeanFactory ← BeanDefinition capabilities
│   ├── org.springframework.beans.factory.config
│   │   ├── BeanDefinition          ← Recipe for creating a bean
│   │   ├── BeanPostProcessor       ← Pre/post-initialization hooks
│   │   ├── BeanFactoryPostProcessor ← Modify bean definitions before creation
│   │   ├── InstantiationAwareBeanPostProcessor ← Pre/post-instantiation hooks
│   │   └── DestructionAwareBeanPostProcessor ← Cleanup hooks
│   ├── org.springframework.beans.factory.support
│   │   ├── DefaultListableBeanFactory ← THE IoC container implementation
│   │   ├── AbstractBeanDefinition  ← Common bean definition properties
│   │   └── RootBeanDefinition      ← Merged bean definition at runtime
│   └── org.springframework.beans.factory.xml
│
│   When to read: When debugging bean creation, understanding scopes, writing custom
│   BeanPostProcessors, or diagnosing NoSuchBeanDefinitionException.
│   Key classes: DefaultListableBeanFactory, AbstractAutowireCapableBeanFactory,
│                 BeanDefinition, BeanPostProcessor
│
├── spring-context/                 ← ApplicationContext, AOP, JSR-330
│   ├── org.springframework.context
│   │   ├── ApplicationContext      ← The container superinterface
│   │   ├── ConfigurableApplicationContext ← Lifecycle + refresh capability
│   │   ├── ApplicationEvent        ← Base event class
│   │   ├── ApplicationListener     ← Event listener interface
│   │   └── ApplicationEventPublisher ← Event publishing API
│   ├── org.springframework.context.annotation
│   │   ├── ConfigurationClassParser        ← @Configuration class parsing engine
│   │   ├── ConfigurationClassPostProcessor ← BeanFactoryPostProcessor that drives parsing
│   │   ├── ComponentScanAnnotationParser   ← @ComponentScan processing
│   │   ├── ConditionEvaluator              ← @Conditional evaluation
│   │   └── AnnotationConfigApplicationContext ← Standalone annotation-based context
│   ├── org.springframework.context.support
│   │   ├── AbstractApplicationContext       ← THE refresh() template method
│   │   └── GenericApplicationContext        ← Flexible context implementation
│   ├── org.springframework.aop             ← AOP Alliance interfaces + Spring AOP
│   │   ├── Pointcut, Advisor, MethodInterceptor
│   │   ├── ProxyFactory, ProxyFactoryBean
│   │   └── framework/
│   │       ├── ProxyFactoryBean
│   │       ├── JdkDynamicAopProxy
│   │       ├── CglibAopProxy
│   │       └── DefaultAopProxyFactory
│   └── org.springframework.stereotype      ← @Component, @Service, @Repository
│
│   When to read: When understanding @Configuration processing, AOP proxy creation,
│   event publishing, or the application lifecycle.
│   Key classes: AbstractApplicationContext, ConfigurationClassParser,
│                 AnnotationConfigApplicationContext, JdkDynamicAopProxy, CglibAopProxy
│
├── spring-aop/                     ← Standalone AOP (separate from spring-context AOP)
│   ├── org.springframework.aop     ← Standalone AOP without ApplicationContext
│   └── org.aopalliance             ← AOP Alliance interfaces (imported)
│
│   When to read: When using Spring AOP without ApplicationContext, or understanding
│   the AOP Alliance contract. Rarely needed for typical Spring Boot apps — the
│   spring-context AOP integration is more commonly used.
│
├── spring-expression/              ← SpEL (Spring Expression Language)
│   ├── org.springframework.expression
│   │   ├── ExpressionParser        ← Parses SpEL strings into Expression objects
│   │   ├── EvaluationContext       ← Variables, functions, type converters for evaluation
│   │   ├── SpelExpression          ← Compiled expression representation
│   │   └── spel/standard/SpelExpressionParser
│   └── org.springframework.expression.spel
│       ├── support/StandardEvaluationContext
│       ├── standard/SpelExpressionParser
│       └── ast/                    ← Abstract Syntax Tree nodes
│
│   When to read: When debugging @Value("#{...}"), @PreAuthorize, @Cacheable(key="..."),
│   or any SpEL expression that doesn't evaluate as expected.
│   Key classes: SpelExpressionParser, StandardEvaluationContext, InternalSpelExpressionParser
│
├── spring-jdbc/                    ← JDBC abstraction
│   ├── org.springframework.jdbc.core     ← JdbcTemplate, RowMapper, ResultSetExtractor
│   ├── org.springframework.jdbc.support ← SQLExceptionTranslator, SQLStateSQLExceptionTranslator
│   └── org.springframework.jdbc.datasource ← DataSourceUtils, TransactionAwareDataSourceProxy
│
│   When to read: When using JdbcTemplate directly, debugging SQL exception translation,
│   or understanding DataSource transaction synchronization.
│
├── spring-tx/                      ← Transaction management
│   ├── org.springframework.transaction
│   │   ├── PlatformTransactionManager          ← The transaction SPI
│   │   ├── TransactionDefinition               ← Propagation, isolation, timeout, readOnly
│   │   ├── TransactionStatus                    ← Runtime transaction state
│   │   └── TransactionSynchronizationManager   ← Thread-bound transaction resources
│   ├── org.springframework.transaction.annotation
│   │   ├── Transactional                        ← @Transactional annotation
│   │   ├── AnnotationTransactionAttributeSource ← Extracts attributes from @Transactional
│   │   ├── TransactionInterceptor               ← THE AOP interceptor for @Transactional
│   │   └── SpringTransactionAnnotationParser    ← Parses @Transactional into TransactionAttribute
│   ├── org.springframework.transaction.interceptor
│   │   ├── TransactionAspectSupport             ← Base class for TransactionInterceptor
│   │   ├── TransactionAttributeSourcePointcut   ← Where @Transactional applies
│   │   └── DefaultTransactionAttribute          ← Default propagation=REQUIRED, isolation=DEFAULT
│   └── org.springframework.transaction.support
│       ├── AbstractPlatformTransactionManager   ← Template method for TX management
│       ├── DefaultTransactionStatus             ← Standard status implementation
│       └── TransactionSynchronizationUtils      ← Trigger TX synchronization callbacks
│
│   When to read: Debugging @Transactional behavior, nested transactions, rollback rules,
│   transaction synchronization, or multi-datasource transaction management.
│   Key classes: TransactionInterceptor.invoke(), AbstractPlatformTransactionManager,
│                 TransactionAspectSupport, TransactionSynchronizationManager
│
├── spring-web/                     ← Web foundation (shared by Servlet and Reactive)
│   ├── org.springframework.web     ← WebApplicationInitializer, HttpRequestHandler
│   └── org.springframework.web.bind ← @RequestMapping, @RequestParam, @PathVariable
│
│   When to read: Understanding @RequestMapping annotation model, HandlerMethod,
│   or generic web abstractions.
│
├── spring-webmvc/                  ← Spring MVC (Servlet-based)
│   ├── org.springframework.web.servlet
│   │   ├── DispatcherServlet              ← THE front controller
│   │   ├── FrameworkServlet               ← Parent class of DispatcherServlet
│   │   ├── HandlerMapping                 ← Maps request → handler
│   │   ├── HandlerAdapter                 ← Invokes handler
│   │   ├── HandlerInterceptor             ← Pre/post handler hooks
│   │   ├── HandlerExceptionResolver       ← Exception → response mapping
│   │   ├── ViewResolver                   ← View name → View object
│   │   └── ModelAndView                   ← Model + View pair
│   ├── org.springframework.web.servlet.mvc.method.annotation
│   │   ├── RequestMappingHandlerAdapter   ← THE HandlerAdapter for @RequestMapping
│   │   ├── RequestMappingHandlerMapping   ← THE HandlerMapping for @RequestMapping
│   │   ├── ServletInvocableHandlerMethod  ← Invokes controller method
│   │   └── RequestResponseBodyMethodProcessor ← Handles @RequestBody/@ResponseBody
│   └── org.springframework.web.servlet.config.annotation
│       └── WebMvcConfigurer             ← Customization SPI
│
│   When to read: Understanding request dispatch, argument resolution, return value handling,
│   and exception resolution.
│   Key classes: DispatcherServlet.doDispatch(), RequestMappingHandlerAdapter,
│                 ServletInvocableHandlerMethod, HandlerMethodArgumentResolver
│
├── spring-webflux/                 ← WebFlux (Reactive)
│   └── org.springframework.web.reactive
│       └── DispatcherHandler ← The reactive front controller (analogous to DispatcherServlet)
│
│   When to read: When working with WebFlux or debugging reactive endpoints.
│
└── spring-test/                    ← Test support
    └── org.springframework.test.context
        └── TestContext, TestContextManager, SpringBootTestContextBootstrapper
```

### Spring Boot Module Map

```
spring-boot/
├── spring-boot/                                       ← Core Boot: SpringApplication, Banner, env
│   └── org.springframework.boot
│       ├── SpringApplication.java                     ★ THE BIBLE: ~1400 lines
│       ├── SpringApplicationBuilder.java              ★ Fluent builder API
│       ├── ApplicationRunner.java
│       ├── CommandLineRunner.java
│       ├── ExitCodeGenerator.java
│       ├── web/
│       │   └── WebApplicationType.java                ← SERVLET / REACTIVE / NONE
│       └── env/
│           └── EnvironmentPostProcessor.java
│
├── spring-boot-autoconfigure/                         ← Auto-configuration classes
│   └── org.springframework.boot.autoconfigure
│       ├── AutoConfigurationImportSelector.java       ★ THE ENGINE: ~300 lines
│       ├── AutoConfigurationImportFilter.java         ★ Early class-based filtering
│       ├── AutoConfigurationPackages.java
│       ├── condition/                                 ← All @Conditional* annotations
│       │   ├── OnClassCondition.java
│       │   ├── OnBeanCondition.java
│       │   ├── OnPropertyCondition.java
│       │   ├── OnWebApplicationCondition.java
│       │   └── ConditionEvaluationReport.java
│       ├── web/servlet/
│       │   ├── WebMvcAutoConfiguration.java
│       │   ├── DispatcherServletAutoConfiguration.java
│       │   └── error/ErrorMvcAutoConfiguration.java
│       ├── jdbc/DataSourceAutoConfiguration.java
│       ├── orm/jpa/HibernateJpaAutoConfiguration.java
│       ├── transaction/TransactionAutoConfiguration.java
│       └── ... (~80 more)
│
├── spring-boot-starters/                              ← Starter POMs (no Java code)
│   ├── spring-boot-starter-web
│   ├── spring-boot-starter-data-jpa
│   ├── spring-boot-starter-security
│   └── ... (~50 starters)
│
├── spring-boot-actuator/                              ← Production endpoints
│   └── org.springframework.boot.actuate
│       ├── health/HealthEndpoint.java
│       ├── metrics/
│       ├── env/EnvironmentEndpoint.java
│       └── beans/BeansEndpoint.java
│
├── spring-boot-actuator-autoconfigure/                ← Actuator auto-configuration
├── spring-boot-devtools/                              ← Dev tools (restart, livereload)
├── spring-boot-test/                                  ← @SpringBootTest, test utilities
├── spring-boot-test-autoconfigure/                    ← Test slice auto-config
│   └── org.springframework.boot.test.autoconfigure
│       ├── @WebMvcTest ← Scans only @Controller
│       ├── @DataJpaTest ← Scans only @Repository
│       ├── @JsonTest    ← Scans only Jackson
│       └── @RestClientTest ← Scans only RestTemplate/WebClient
├── spring-boot-loader/                                ← Executable JAR/WAR launcher
│   └── org.springframework.boot.loader
│       ├── JarLauncher.java        ← Executable JAR entry point
│       ├── WarLauncher.java
│       └── LaunchedURLClassLoader.java
└── spring-boot-docker-compose/                        ← Docker Compose integration
```

### Key Classes Ranked by Importance

#### Tier 1: Must Know (Every Staff Engineer Should Have Read These)

| # | Class | Key Method | Lines | Why It Matters |
|---|-------|-----------|-------|----------------|
| 1 | `SpringApplication` | `run(String... args)` | ~1400 | Every Boot app starts here. Understanding this = understanding the entire lifecycle. |
| 2 | `AbstractApplicationContext` | `refresh()` | ~600 | The 12-step template method. Spring's heartbeat. |
| 3 | `DefaultListableBeanFactory` | `preInstantiateSingletons()` | ~1500 | Where all your beans come from. |
| 4 | `DispatcherServlet` | `doDispatch(HttpServletRequest, HttpServletResponse)` | ~100 | Every HTTP request passes through this method. |
| 5 | `TransactionInterceptor` | `invoke(MethodInvocation)` | ~50 (delegates to parent) | Every @Transactional method passes through this. |
| 6 | `AutoConfigurationImportSelector` | `getAutoConfigurationEntry(AnnotationMetadata)` | ~80 | Determines which auto-configurations activate. |

#### Tier 2: Should Know (Read These When Debugging Specific Problems)

| # | Class | Key Method | Why It Matters |
|---|-------|-----------|----------------|
| 7 | `AbstractAutoProxyCreator` | `wrapIfNecessary(Object, String, Object)` | AOP proxy creation — every `@Transactional`, `@Cacheable`, `@Async` bean |
| 8 | `ConfigurationClassParser` | `doProcessConfigurationClass(ConfigurationClass, SourceClass)` | How `@Configuration` classes are parsed recursively |
| 9 | `ConfigurationClassPostProcessor` | `processConfigBeanDefinitions(BeanDefinitionRegistry)` | The BFPP that triggers all @Configuration parsing |
| 10 | `AbstractAutowireCapableBeanFactory` | `doCreateBean(String, RootBeanDefinition, Object[])` | The actual bean instantiation + population + initialization |
| 11 | `AbstractPlatformTransactionManager` | `getTransaction(TransactionDefinition)` | Transaction begin/join logic |
| 12 | `TransactionSynchronizationManager` | `initSynchronization()` / `clearSynchronization()` | Thread-local transaction state |

#### Tier 3: Read When Curious

| # | Class | What To Study |
|---|-------|--------------|
| 13 | `RequestMappingHandlerAdapter` | How `@RequestBody`, `@ResponseBody`, argument resolvers work |
| 14 | `ServletInvocableHandlerMethod` | How controller method parameters are resolved and invoked |
| 15 | `CglibAopProxy` / `JdkDynamicAopProxy` | How proxies are generated (class enhancement vs interface proxy) |
| 16 | `AnnotationConfigServletWebServerApplicationContext` | How Servlet web context bootstraps |
| 17 | `ConditionEvaluator` | How `@Conditional` is actually evaluated |
| 18 | `ConfigFileApplicationListener` | How `application.properties`/YML is loaded |
| 19 | `DataSourceAutoConfiguration` | Canonical auto-configuration example |
| 20 | `HibernateJpaAutoConfiguration` | How JPA/Hibernate integrates |

## 4. Runtime Behavior

### The Critical Method: SpringApplication.run()

```
SpringApplication.run(MyApp.class, args)
│
├── [0] new SpringApplication(primarySources)
│       ├── deduceWebApplicationType() → SERVLET/REACTIVE/NONE
│       ├── getSpringFactoriesInstances(ApplicationContextInitializer.class) → initializers
│       └── getSpringFactoriesInstances(ApplicationListener.class) → listeners
│
├── [1] createBootstrapContext()
│
├── [2] configureHeadlessProperty()
│
├── [3] getRunListeners(args) → load EventPublishingRunListener
│
├── [4] listeners.starting(bootstrapContext)
│       └── fires: ApplicationStartingEvent
│
├── [5] prepareEnvironment(listeners, bootstrapContext, args)
│       ├── getOrCreateEnvironment() → StandardServletEnvironment
│       ├── configureEnvironment()
│       ├── configurePropertySources() → systemProperties + systemEnvironment
│       ├── configureProfiles() → spring.profiles.active
│       ├── listeners.environmentPrepared()
│       │     └── ConfigFileApplicationListener loads application.properties/yml
│       └── bindToSpringApplication(environment)
│
├── [6] printBanner(environment)
│
├── [7] createApplicationContext()
│       └── switch WebApplicationType → Appropriate AnnotationConfig*ApplicationContext
│
├── [8] prepareContext(context, ...)
│       ├── applyInitializers()
│       ├── register primarySource
│       ├── load(primarySources) → BeanDefinitions from @Configuration classes
│       └── listeners.contextLoaded()
│
├── [9] refreshContext(context)
│       └── AbstractApplicationContext.refresh() ← THE 12 STEPS (see below)
│
├── [10] afterRefresh() ← empty hook
│
├── [11] listeners.started(context)
│       └── fires: ApplicationStartedEvent
│
├── [12] callRunners(context, args)
│       ├── ApplicationRunner beans
│       └── CommandLineRunner beans
│
└── [13] listeners.ready(context)
        └── fires: ApplicationReadyEvent
```

### The Critical Method: AbstractApplicationContext.refresh()

```java
// org.springframework.context.support.AbstractApplicationContext
// THE template method — 12 sequential steps, non-overridable order

@Override
public void refresh() throws BeansException, IllegalStateException {
    synchronized (this.startupShutdownMonitor) {
        StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");

        // STEP 1: Prepare this context for refreshing
        // - Sets start date, active flag
        // - Initializes property sources
        // - Validates required properties (environment.getRequiredProperties())
        // - Creates early application listeners
        prepareRefresh();

        // STEP 2: Tell subclass to refresh the internal bean factory
        // - Creates new DefaultListableBeanFactory
        // - Loads BeanDefinitions (from XML, annotations, scanning)
        // - Returns the fresh ConfigurableListableBeanFactory
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

        // STEP 3: Prepare the bean factory for use
        // - Sets ClassLoader, SpEL support
        // - Registers standard singletons: environment, systemProperties, systemEnvironment
        // - Registers default beans: BeanFactory, ResourceLoader, ApplicationEventPublisher
        // - Configures bean post-processor detection
        // - Registers environment beans
        prepareBeanFactory(beanFactory);

        try {
            // STEP 4: Allows post-processing of bean factory in context subclasses
            // - Registers special ServletContext beans for web contexts
            // - Registers request/session/web-application scopes
            postProcessBeanFactory(beanFactory);

            StartupStep beanPostProcess = this.applicationStartup.start("spring.context.beans.post-process");

            // STEP 5: Invoke factory processors registered as beans
            //   THIS IS WHERE AUTO-CONFIGURATION TRIGGERS:
            //   1. Invoke BeanDefinitionRegistryPostProcessor beans
            //   2. ConfigurationClassPostProcessor parses @Configuration classes
            //   3. This invokes DeferredImportSelectors (AutoConfigurationImportSelector)
            //   4. Auto-configuration @Bean methods are registered as BeanDefinitions
            invokeBeanFactoryPostProcessors(beanFactory);

            // STEP 6: Register bean post-processors
            //   Registers beans that intercept bean creation:
            //   - AutowiredAnnotationBeanPostProcessor (@Autowired, @Value)
            //   - CommonAnnotationBeanPostProcessor (@PostConstruct, @PreDestroy, @Resource)
            //   - PersistenceAnnotationBeanPostProcessor (@PersistenceContext, @PersistenceUnit)
            //   - AbstractAutoProxyCreator subclasses (AOP, @Transactional, @Cacheable, @Async)
            //   - EventListenerMethodProcessor (@EventListener)
            registerBeanPostProcessors(beanFactory);

            // STEP 7: Initialize MessageSource for i18n
            initMessageSource(beanFactory);

            // STEP 8: Initialize event multicaster
            initApplicationEventMulticaster();

            // STEP 9: Initialize special beans in context subclasses
            //   For web contexts: creates embedded web server (Tomcat/Jetty/Undertow)
            //   For reactive contexts: creates reactive web server
            onRefresh();

            // STEP 10: Register event listeners
            //   Finds beans implementing ApplicationListener and registers them
            registerListeners();

            // STEP 11: Instantiate all remaining (non-lazy-init) singletons
            //   THIS IS WHERE YOUR BEANS ARE CREATED:
            //   1. Iterate all BeanDefinition names
            //   2. If not lazy, not abstract, singleton scope:
            //   3. getBean(name) → doCreateBean() → createBeanInstance() → populateBean() → initializeBean()
            //   4. SmartInitializingSingleton.afterSingletonsInstantiated() callbacks
            //   5. Bean is cached in singletonObjects (ConcurrentHashMap)
            finishBeanFactoryInitialization(beanFactory);

            // STEP 12: Finish refresh
            //   - Clears context-level resource caches
            //   - Initializes lifecycle processor
            //   - Publishes ContextRefreshedEvent
            finishRefresh();
        } catch (BeansException ex) {
            // Destroy already-created singletons
            destroyBeans();
            // Reset 'active' flag
            cancelRefresh(ex);
            throw ex;
        }
    }
}
```

### Profile-Based Conditional Activation

```
At startup, the auto-configuration evaluation sequence is:

1. Boot determines active profiles (spring.profiles.active, spring.profiles.default)
2. Environment contains PropertySources in priority order
3. For each auto-configuration class:
   a. @Profile("web") → is "web" an active profile?
      - YES → continue evaluation
      - NO → skip class entirely (even before @ConditionalOnClass)
   b. @ConditionalOnClass(...) → are those classes on classpath?
   c. @ConditionalOnWebApplication(type=SERVLET) → is this a servlet app?
   d. @ConditionalOnProperty("my.feature.enabled", matchIfMissing=true) → property check
   e. @ConditionalOnBean(DataSource.class) → is there a DataSource bean already?
   f. If all pass → register BeanDefinitions from @Bean methods
```

## 5. Request Flow Diagrams

### Full HTTP Request Processing

```
Client (browser/curl/Postman)
  │
  ▼  HTTP request: GET /api/users/42
  │
  ▼
Tomcat/NIO Connector (Thread from pool)
  │
  ▼
Servlet Filter Chain
  ├── OncePerRequestFilter (Spring Security filter chain)
  ├── CharacterEncodingFilter
  ├── CorsFilter
  ├── RequestContextFilter (exposes request to current thread)
  └── ... (custom filters)
  │
  ▼
DispatcherServlet.doDispatch(request, response)    ← ★ KEY METHOD ★
  │
  ├── [1] Determine Handler
  │   checkMultipart(request) → if multipart, wrap
  │   getHandler(request):
  │     ├── Iterate HandlerMappings
  │     │   ├── RequestMappingHandlerMapping ("/api/users/{id}")
  │     │   ├── SimpleUrlHandlerMapping (static resources)
  │     │   └── BeanNameUrlHandlerMapping
  │     └── Return: HandlerExecutionChain
  │         ├── Handler: HandlerMethod(UserController.getUser(Long))
  │         └── Interceptors: [OpenEntityManagerInViewInterceptor, ...]
  │
  ├── [2] Determine HandlerAdapter
  │   getHandlerAdapter(handler):
  │     ├── RequestMappingHandlerAdapter   ← for @RequestMapping methods
  │     ├── HttpRequestHandlerAdapter      ← for HttpRequestHandler
  │     └── SimpleControllerHandlerAdapter ← for Controller interface
  │   Return: RequestMappingHandlerAdapter
  │
  ├── [3] Apply Pre-Interceptors
  │   if (!mappedHandler.applyPreHandle(request, response)) {
  │       return; // Interceptor decided to handle the response itself
  │   }
  │
  ├── [4] Invoke Handler (via HandlerAdapter)
  │   ha.handle(request, response, handlerMethod):
  │     ├── abstract:
  │     │     ├── Resolve method arguments:
  │     │     │   @PathVariable → PathVariableMethodArgumentResolver
  │     │     │   @RequestParam → RequestParamMethodArgumentResolver
  │     │     │   @RequestBody → RequestResponseBodyMethodProcessor
  │     │     │     └── HttpMessageConverter.read() → Jackson ObjectMapper
  │     │     │   @RequestHeader → RequestHeaderMethodArgumentResolver
  │     │     │   HttpServletRequest → ServletRequestMethodArgumentResolver
  │     │     │   Model, BindingResult, Principal, HttpMethod, etc.
  │     │     │
  │     │     ├── Invoke method:
  │     │     │   userController.getUser(42L)
  │     │     │     → UserService.getUserById(42L)
  │     │     │       → UserRepository.findById(42L)  ← @Transactional wraps this
  │     │     │         → EntityManager.find(User.class, 42)
  │     │     │           → JDBC Connection → SELECT * FROM users WHERE id = 42
  │     │     │     → return UserDto(id=42, name="Alice")
  │     │     │
  │     │     └── Handle return value:
  │     │         @ResponseBody → RequestResponseBodyMethodProcessor
  │     │           └── HttpMessageConverter.write() → Jackson ObjectMapper
  │     │               → {"id": 42, "name": "Alice"}
  │     │
  │     └── Return: ModelAndView (null for @ResponseBody)
  │
  ├── [5] Apply Post-Interceptors
  │   mappedHandler.applyPostHandle(request, response, null);
  │
  ├── [6] Process Dispatch Result
  │   processDispatchResult(request, response, mappedHandler, mv, exception):
  │     ├── If exception != null:
  │     │   processHandlerException(request, response, handler, exception):
  │     │     ├── Iterate HandlerExceptionResolvers:
  │     │     │   ├── ExceptionHandlerExceptionResolver (@ExceptionHandler)
  │     │     │   ├── ResponseStatusExceptionResolver (@ResponseStatus)
  │     │     │   └── DefaultHandlerExceptionResolver (standard Spring exceptions)
  │     │     └── Return error ModelAndView or @ResponseBody error
  │     ├── If ModelAndView != null:
  │     │   render(mv, request, response)
  │     │     ├── Resolve View (ViewResolver)
  │     │     └── Render View
  │     └── Otherwise: response already committed (JSON written to OutputStream)
  │
  └── [7] Trigger After-Completion (always, even on exception)
      mappedHandler.triggerAfterCompletion(request, response, exception);
      → Interceptors' afterCompletion() called
      → OpenEntityManagerInViewInterceptor closes EntityManager
```

### @Transactional Method Invocation

```
controller.getUser(id)
  │  (call goes through CGLIB proxy, not directly to service)
  ▼
UserService$$SpringCGLIB$$0  (Proxy)
  │
  ▼
TransactionInterceptor.invoke(MethodInvocation)       ← ★ KEY METHOD ★
  │
  ├── [1] Get TransactionAttribute from method metadata
  │   TransactionAttributeSource.getTransactionAttribute(method, targetClass):
  │     └── SpringTransactionAnnotationParser.parseTransactionAnnotation()
  │         └── @Transactional(propagation=REQUIRED, isolation=DEFAULT,
  │                             timeout=-1, readOnly=false,
  │                             rollbackFor={}, noRollbackFor={})
  │
  ├── [2] Determine TransactionManager
  │   TransactionAspectSupport.determineTransactionManager(txAttr):
  │     └── if @Transactional("inventoryTransactionManager"):
  │         return inventoryTransactionManager
  │         else: return default PlatformTransactionManager (DataSourceTM)
  │
  ├── [3] Get Transaction (begin or join)
  │   PlatformTransactionManager.getTransaction(txAttr):
  │     └── AbstractPlatformTransactionManager.getTransaction():
  │         ├── If existing transaction in TransactionSynchronizationManager:
  │         │   ├── PROPAGATION_REQUIRED: join existing (do nothing)
  │         │   ├── PROPAGATION_REQUIRES_NEW: suspend existing, start new
  │         │   ├── PROPAGATION_NESTED: create savepoint
  │         │   ├── PROPAGATION_MANDATORY: throw if no existing TX
  │         │   ├── PROPAGATION_SUPPORTS: join if exists, non-TX if not
  │         │   └── PROPAGATION_NEVER: throw if existing TX exists
  │         └── If no existing transaction:
  │             ├── PROPAGATION_REQUIRED, REQUIRES_NEW, NESTED: begin new TX
  │             ├── PROPAGATION_MANDATORY: throw IllegalTransactionStateException
  │             └── PROPAGATION_SUPPORTS, NOT_SUPPORTED, NEVER: non-TX execution
  │                     ├── PROPAGATION_NOT_SUPPORTED: suspend any existing
  │                     └── PROPAGATION_NEVER: throw if existing TX exists
  │         → doBegin(): DataSourceTransactionManager.doBegin()
  │             ├── Get JDBC Connection (from HikariCP pool)
  │             ├── connection.setAutoCommit(false)
  │             ├── Set isolation level if configured
  │             └── Bind connection to TransactionSynchronizationManager (ThreadLocal)
  │
  ├── [4] Invoke Target Method
  │   try {
  │       Object retVal = invocation.proceed();  // ← actual service method runs
  │       // getUserById(42L) executes here
  │       // All SQL runs within this transaction
  │   }
  │
  ├── [5] Commit or Rollback
  │   TransactionAspectSupport.commitTransactionAfterReturning(txInfo):
  │     └── PlatformTransactionManager.commit(txStatus):
  │         ├── If rollbackOnly flag set (from @Transactional or manual):
  │         │   doRollback(txStatus):
  │         │     └── connection.rollback()
  │         └── Else:
  │             doCommit(txStatus):
  │               └── connection.commit()
  │   catch (Throwable ex) {
  │       TransactionAspectSupport.completeTransactionAfterThrowing(txInfo, ex):
  │         └── For each rollback rule in TransactionAttribute:
  │             ├── If exception matches rollbackFor:
  │             │   doRollback(txStatus) → connection.rollback()
  │             └── If exception doesn't match:
  │                 ├── RuntimeException or Error? → doRollback (default behavior)
  │                 └── Checked exception? → doCommit (NOT rolled back by default!)
  │   }
  │
  └── [6] Cleanup
      TransactionAspectSupport.cleanupTransactionInfo(txInfo):
        └── if suspended transaction existed: resume it
        └── TransactionSynchronizationManager.clear()
            ├── Remove connection from ThreadLocal
            ├── Remove transaction name, readOnly, isolation level
            └── connection.close() → returns to HikariCP pool
```

## 6. Lifecycle Diagrams

### Bean Lifecycle — Complete

```
┌─────────────────────────────────────────────────────────────────────────┐
│  BeanDefinition loaded (from @Component, @Bean, XML, auto-config)       │
│  ↓                                                                      │
│  [1] BeanFactoryPostProcessor (e.g., ConfigurationClassPostProcessor)   │
│      - Can modify BeanDefinition (property overrides, scope changes)    │
│  ↓                                                                      │
│  [2] Instantiation                                                      │
│      - Constructor resolved (by type, by name, @Qualifier, primary)     │
│      - DefaultListableBeanFactory.createBeanInstance()                  │
│      - CGLIB subclass if @Configuration's proxyBeanMethods=true        │
│  ↓                                                                      │
│  [3] MergedBeanDefinition Post-Processing                               │
│      - AutowiredAnnotationBeanPostProcessor: scan for @Autowired, @Value│
│      - CommonAnnotationBeanPostProcessor: scan for @PostConstruct,      │
│        @PreDestroy, @Resource                                           │
│      - PersistenceAnnotationBeanPostProcessor: scan for @PersistenceUnit│
│  ↓                                                                      │
│  [4] Property Population (Dependency Injection)                         │
│      - InstantiationAwareBeanPostProcessor.postProcessProperties()      │
│      - AutowiredAnnotationBeanPostProcessor: inject @Autowired fields   │
│      - AutowiredAnnotationBeanPostProcessor: inject @Value fields       │
│      - CommonAnnotationBeanPostProcessor: inject @Resource fields       │
│      - Apply all PropertyValues from BeanDefinition                    │
│  ↓                                                                      │
│  [5] Aware Interface Callbacks                                          │
│      - BeanNameAware.setBeanName(name)                                  │
│      - BeanClassLoaderAware.setBeanClassLoader(classLoader)             │
│      - BeanFactoryAware.setBeanFactory(beanFactory)                     │
│      - EnvironmentAware.setEnvironment(environment)                     │
│      - ApplicationContextAware.setApplicationContext(applicationContext)│
│  ↓                                                                      │
│  [6] BeanPostProcessor.postProcessBeforeInitialization()                │
│      - CommonAnnotationBeanPostProcessor: @PostConstruct invocation     │
│      - ApplicationContextAwareProcessor: more aware callbacks           │
│      - InitDestroyAnnotationBeanPostProcessor: @PostConstruct           │
│  ↓                                                                      │
│  [7] Initialization                                                     │
│      - InitializingBean.afterPropertiesSet()                            │
│      - Custom init-method (from @Bean(initMethod="..."))                │
│  ↓                                                                      │
│  [8] BeanPostProcessor.postProcessAfterInitialization()                 │
│      - AbstractAutoProxyCreator.wrapIfNecessary():                      │
│        IF bean has methods annotated with @Transactional / @Cacheable / │
│        @Async / @Retryable OR matches AOP pointcut expression:          │
│          → Create CGLIB or JDK dynamic proxy wrapping the bean          │
│          → Replace original bean in singleton cache with proxy          │
│  ↓                                                                      │
│  [9] Bean Ready                                                         │
│      - Stored in DefaultSingletonBeanRegistry.singletonObjects          │
│        (ConcurrentHashMap<String, Object>)                              │
│  ↓                                                                      │
│  ... Application runs ...                                               │
│  ↓                                                                      │
│  [10] Destruction (on ApplicationContext.close())                       │
│      - DestructionAwareBeanPostProcessor.postProcessBeforeDestruction() │
│      - DisposableBean.destroy()                                         │
│      - Custom destroy-method (from @Bean(destroyMethod="..."))          │
│      - @PreDestroy method invocation                                    │
└─────────────────────────────────────────────────────────────────────────┘
```

## 7. Source Code Reading Guide

### Philosophy: How to Read 1M+ Line Codebases

**Rule 1: You never read all of it.** Spring Framework is 1.2M lines. You read at most 0.5% (~6,000 lines) deeply. The rest you navigate, search, or ignore.

**Rule 2: Start from the behavior, not the code.** Write a minimal reproduction test that exercises exactly the behavior you want to understand. This gives you a concrete stack trace to follow.

**Rule 3: Follow the contract, not the implementation.** Spring is built on interfaces. The interface method's JavaDoc often contains the contract that all 15 implementations obey. Read the interface first, then one implementation.

**Rule 4: The breadcrumb trail method.** Trace from public API entry point (e.g., `TransactionInterceptor.invoke()`) inward, one method call at a time. At each level, ask: "Does this method do the thing I care about, or is it infrastructure?" If infrastructure, skip it. If behavior logic, read it.

**Rule 5: The breakpoint trace method.** For complex flows, setting 3-5 strategic breakpoints and stepping through a single request teaches you more than 10 hours of static reading. The IDE shows you the actual runtime values — you see which branches are taken, what objects look like at each stage.

**Rule 6: Read tests as executable documentation.** Spring's test suite is arguably better documented than its production code. Tests show you exactly what inputs produce what outputs. Find the test class for the subsystem you're studying (e.g., `TransactionInterceptorTests`) and read the test method names.

### Navigation Technique

```
1. Find the interface
   └── Use IDE: Ctrl+N (IntelliJ) → "PlatformTransactionManager"
       └── Read interface JavaDoc and method signatures first

2. Find the key implementation
   └── Use IDE: Ctrl+Alt+B (IntelliJ) → "Go to Implementation(s)"
       └── For Spring Boot web apps, this is almost always the "AnnotationConfig*" variant
       └── For transactions on JDBC: DataSourceTransactionManager

3. Trace the main method
   └── Open the implementation
   └── Find the key method from the interface (e.g., getTransaction())
   └── Set breakpoint on first meaningful line
   └── Ctrl+Alt+F7 (Find Usages) to see who calls it

4. Step through
   └── Run the app/tests in debug mode
   └── Trigger the behavior (send HTTP request, call a service, etc.)
   └── When breakpoint hits, step into (F7) calls that look relevant
   └── Step over (F8) utility/boilerplate calls
   └── Inspect variables (Alt+F8) at each step

5. Document
   └── After each trace, write down the call sequence you discovered
   └── Include class names and method names
   └── This becomes your personal reference for next time
```

### Breakpoint Strategy by Subsystem

| Subsystem | Key Breakpoint Locations | What You'll See |
|-----------|------------------------|-----------------|
| Bean Creation | `DefaultListableBeanFactory.preInstantiateSingletons()` line: `getBean(beanName)` | Which beans are created, in what order, any circular dependency exceptions |
| | `AbstractAutowireCapableBeanFactory.doCreateBean()` line: `instanceWrapper = createBeanInstance(...)` | Constructor resolution, factory method invocation |
| | `AbstractAutowireCapableBeanFactory.populateBean()` | Autowired field injection, property values being set |
| | `AbstractAutowireCapableBeanFactory.initializeBean()` | @PostConstruct, afterPropertiesSet, AOP proxy wrapping |
| Auto-Config | `AutoConfigurationImportSelector.getAutoConfigurationEntry()` | Full list of candidates before/after filtering |
| | `OnClassCondition.getMatchOutcome()` | Which classes matched/missed by class presence |
| | `ConfigurationClassParser.doProcessConfigurationClass()` | Recursive @Configuration class parsing |
| | `ConfigurationClassPostProcessor.processConfigBeanDefinitions()` | All @Configuration classes being parsed |
| HTTP Dispatch | `DispatcherServlet.doDispatch()` line: `HandlerExecutionChain mappedHandler = getHandler(...)` | Which handler matched the request URL |
| | `DispatcherServlet.doDispatch()` line: `mv = ha.handle(...)` | Arguments being resolved, controller being called |
| | `RequestMappingHandlerAdapter.invokeHandlerMethod()` | Full argument resolution and method invocation |
| | `AbstractHandlerMethodAdapter.handle()` | The actual handler method call |
| Transactions | `TransactionInterceptor.invoke()` | Transaction attribute, method being intercepted |
| | `AbstractPlatformTransactionManager.getTransaction()` | Propagation logic, existing transaction check |
| | `DataSourceTransactionManager.doBegin()` | Connection acquisition, autoCommit=false |
| | `DataSourceTransactionManager.doCommit()` / `doRollback()` | Actual commit/rollback |
| | `TransactionAspectSupport.completeTransactionAfterThrowing()` | Rollback rule evaluation |
| AOP Proxying | `AbstractAutoProxyCreator.wrapIfNecessary()` | Whether a bean gets proxied |
| | `AbstractAutoProxyCreator.getAdvicesAndAdvisorsForBean()` | Which advisors match this bean |
| | `DefaultAopProxyFactory.createAopProxy()` | JDK vs CGLIB proxy decision |
| | `CglibAopProxy.getProxy()` | CGLIB subclass generation |
| Starter Context | `SpringApplication.run()` | Entire bootstrap flow |
| | `AbstractApplicationContext.refresh()` line: `invokeBeanFactoryPostProcessors(beanFactory)` | Configuration processing triggers |
| | `AbstractApplicationContext.refresh()` line: `finishBeanFactoryInitialization(beanFactory)` | Bean instantiation begins |

### Scenario-Based Quick Lookup

```
"I want to understand how @Transactional works"
  → 1. TransactionInterceptor.invoke()
  → 2. TransactionAspectSupport.invokeWithinTransaction()
  → 3. AbstractPlatformTransactionManager.getTransaction()
  → 4. DataSourceTransactionManager.doBegin()
  → 5. Read: TransactionInterceptorTests (tests show propagation scenarios)

"I want to understand why my bean isn't being created"
  → 1. DefaultListableBeanFactory.preInstantiateSingletons()
  → 2. Look for your bean name in the iteration
  → 3. AbstractBeanFactory.getBean() → doGetBean()
  → 4. If not found: check BeanDefinition in mergedBeanDefinitions map
  → 5. If found but creation fails: check doCreateBean() stack trace

"I want to understand how @Autowired works"
  → 1. AutowiredAnnotationBeanPostProcessor.postProcessProperties()
  → 2. InjectionMetadata.inject()
  → 3. AutowiredFieldElement.inject()
  → 4. DefaultListableBeanFactory.resolveDependency()
  → 5. DefaultListableBeanFactory.doResolveDependency()

"I want to understand how auto-configuration decides what to load"
  → 1. AutoConfigurationImportSelector.getAutoConfigurationEntry()
  → 2. AutoConfigurationImportSelector.getCandidateConfigurations()
  → 3. AutoConfigurationImportFilter.match() → OnClassCondition
  → 4. ConfigurationClassParser.parse() for each matched class
  → 5. ConditionEvaluator.shouldSkip()

"I want to understand how a request maps to a controller method"
  → 1. DispatcherServlet.doDispatch() → getHandler(request)
  → 2. AbstractHandlerMapping.getHandler()
  → 3. AbstractHandlerMethodMapping.lookupHandlerMethod()
  → 4. RequestMappingInfoHandlerMapping.handleMatch()
  → 5. Check HandlerMethod's method: this IS your controller method

"I want to understand how @Profile works"
  → 1. ConfigurationClassParser.doProcessConfigurationClass()
  → 2. ConditionEvaluator.shouldSkip() for @Profile
  → 3. ProfileCondition.matches()
  → 4. environment.acceptsProfiles()
```

## 8. Production Failure Scenarios

### Scenario 1: @Transactional silently doesn't roll back on checked exception

**Symptom**: Customer payment succeeded but order status is stuck in "PENDING" despite the order service throwing a checked exception.

**Root cause**: `@Transactional` rolls back automatically only on `RuntimeException` and `Error`. The developer threw a checked `OrderProcessingException` but did not set `@Transactional(rollbackFor = OrderProcessingException.class)`. The transaction committed with partial state.

**How reading the source helps**: Reading `TransactionAspectSupport.completeTransactionAfterThrowing()` and `DefaultTransactionAttribute.rollbackOn(Throwable)` reveals that `RuntimeException` and `Error` are the only defaults. The `rollbackFor` attribute adds additional exception types to the rollback rule.

**Key source locations**: `TransactionAspectSupport.java:~660`, `DefaultTransactionAttribute.java:~50`, `RuleBasedTransactionAttribute.java:~150`

### Scenario 2: @Cacheable returns stale data after DB update in another method

**Symptom**: Method A updates a user's email. Method B reads the user by ID and returns the old email.

**Root cause**: `@Cacheable` uses method-level granularity by default. Method A updates the database but doesn't invalidate the cache key that Method B uses. The `UserService.getUser(id)` cache has no idea that `UserService.updateUser(user)` changed the underlying data.

**How reading the source helps**: Reading `CacheInterceptor.invoke()` shows that `@Cacheable` checks the cache BEFORE calling the method. `@CachePut` updates the cache AFTER calling the method. `@CacheEvict` removes from cache AFTER calling the method. They are completely independent — there is no automatic cache invalidation across methods.

**Key source locations**: `CacheInterceptor.java`, `CacheAspectSupport.execute()`, `CacheOperationExpressionEvaluator.java`

### Scenario 3: Self-invocation bypasses @Transactional proxy

**Symptom**: `saveOrder()` calls `this.processPayment()` internally. `processPayment()` has `@Transactional(propagation = REQUIRES_NEW)` but runs in the SAME transaction as `saveOrder()`.

**Root cause**: Spring AOP wraps the bean in a proxy. When external code calls `orderService.saveOrder()`, it goes through the proxy → transaction interceptor → actual method. But `this.processPayment()` calls the actual method directly on `this`, bypassing the proxy entirely. The proxy never sees the call.

**How reading the source helps**: Reading `AbstractAutoProxyCreator.wrapIfNecessary()` confirms that the proxy wraps the ORIGINAL bean — all calls from outside go through the proxy, but `this` references within the bean bypass it. Reading `CglibAopProxy.DynamicAdvisedInterceptor.intercept()` shows that CGLIB intercepts only external calls.

**Key source locations**: `CglibAopProxy.java:~600` (DynamicAdvisedInterceptor), `JdkDynamicAopProxy.java:~200` (invoke method)

### Scenario 4: Circular dependency between @Configuration classes

**Symptom**: `BeanCurrentlyInCreationException` during startup with a message about a circular reference.

**Root cause**: `ConfigA.@Bean method` depends on `ConfigB.@Bean method` which depends on `ConfigA.@Bean method`. Spring's CGLIB proxy for `@Configuration(proxyBeanMethods=true)` cannot resolve circular dependencies between @Configuration classes (it CAN resolve circular dependencies between regular beans, via an early reference proxy).

**How reading the source helps**: Reading `ConfigurationClassEnhancer.enhance()` shows that each `@Configuration` class is subclassed via CGLIB. Inter-bean method calls are intercepted to return the singleton from the context. But if bean A needs bean B and bean B needs bean A, and both are defined in different @Configuration classes, the CGLIB proxy for config A has not yet been created when config B's @Bean method tries to call config A's @Bean method.

**Key source locations**: `ConfigurationClassEnhancer.java`, `ConfigurationClassPostProcessor.enhanceConfigurationClasses()`

## 9. Debugging Techniques

### Technique 1: Conditional Breakpoints with Logging

Don't just set a breakpoint that stops every time. Use a conditional breakpoint that logs and continues:

```java
// In IntelliJ, set a breakpoint on:
// DefaultListableBeanFactory.preInstantiateSingletons()
// Right-click → More → "Log evaluated expression" → Enter:
"Creating bean: " + beanName

// Now every bean creation is logged without stopping execution.
// Look at the console afterward to see the full creation order.
```

### Technique 2: Exception Breakpoints

Set an exception breakpoint for `BeanCreationException`:

```
IntelliJ: Run → View Breakpoints → Java Exception Breakpoints → Add:
  - org.springframework.beans.factory.BeanCreationException
  - Check: "Caught exceptions"
  
When any bean fails to create, the debugger stops at the EXACT line throwing the exception,
before Spring wraps it in 10 layers of error handling.
```

### Technique 3: Evaluate Expression (Alt+F8)

When stopped at a breakpoint, you can evaluate arbitrary expressions:

```java
// At breakpoint in DefaultListableBeanFactory.preInstantiateSingletons():
beanFactory.getBeanDefinitionCount()          // How many bean definitions exist
beanFactory.getBean("myService")              // Force creation of a specific bean
beanFactory.getBeanDefinition("myService")    // Inspect the BeanDefinition
Arrays.toString(beanFactory.getBeanDefinitionNames())  // All bean names
beanFactory.getSingleton("myService")         // Is it already created?

// At breakpoint in AutoConfigurationImportSelector.getAutoConfigurationEntry():
configurations.size()         // How many candidates loaded
configurations.contains("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
```

### Technique 4: Trace Current Context

```java
// At any breakpoint inside Spring code, evaluate:
((ConfigurableApplicationContext) applicationContext).getBeanFactory()
((DefaultListableBeanFactory) beanFactory).getBeanDefinitionNames()
```

### Technique 5: Step Filters

Configure step filters to avoid stepping into utility methods:

```
IntelliJ: Settings → Build → Debugger → Stepping → Do not step into classes:
  - java.*
  - javax.*
  - org.springframework.util.*
  - org.springframework.cglib.*
  - sun.*
  - com.sun.*
```

### Technique 6: Drop Frame

If you stepped too far, "Drop Frame" (IntelliJ) pops the current stack frame and rewinds execution to before the method call. This only works if the method didn't have side effects — use with caution, but it's extremely useful in Spring's read-heavy bean creation code.

### Technique 7: Reading Tests as Executable Documentation

Spring's own tests are the best documentation. Here's how to find and use them:

```
1. Find the test for the class you're studying:
   TransactionInterceptor → TransactionInterceptorTests
   DefaultListableBeanFactory → DefaultListableBeanFactoryTests
   AutoConfigurationImportSelector → AutoConfigurationImportSelectorTests
   
2. Look for tests that match your scenario:
   Test method names like: 
     - "rollbackOnCheckedException" 
     - "requiresNewPropagationSuspendsExisting"
     - "circularReferenceWithFactoryBean"
   
3. Set breakpoints in the test, run it in debug mode:
   - The test sets up EXACTLY the scenario you care about
   - Step through both the test setup AND the framework code
   - Much faster than setting up your own reproduction
```

## 10. Observability Considerations

### What to Instrument When Reading Source Code

When tracing through Spring source, these are the key metrics to collect:

```java
// Startup observability — available since Spring Boot 2.4
// Add to application.properties:
spring.application.startup.log-step=true

// Or programmatically:
SpringApplication app = new SpringApplication(MyApp.class);
app.setApplicationStartup(new BufferingApplicationStartup(10000));
app.run(args);

// Access: GET /actuator/startup
// Returns: JSON tree of every startup step with duration
{
  "spring.boot.application.running": { "duration": 8700.0, "tags": [] },
  "spring.context.refresh": { "duration": 5600.0 }
  "spring.context.beans.post-process": { "duration": 2200.0 },
  "spring.beans.instantiate": { "duration": 3500.0,
    "tags": [{ "key": "bean", "value": "entityManagerFactory" }] }
}
```

### Reading the Source of Observability Features

When debugging observability issues in Spring, the key source locations are:

| Issue | Source Location |
|-------|----------------|
| Metrics not appearing | `micrometer-core/.../MeterRegistry`, `spring-boot-actuator-autoconfigure/.../MetricsAutoConfiguration` |
| Trace context not propagating | `spring-cloud-sleuth/.../TraceFilter` (Sleuth) or `micrometer-tracing/.../ObservationHandler` (Micrometer Tracing) |
| Health check shows DOWN | `spring-boot-actuator/.../HealthEndpoint`, individual `HealthIndicator` implementations |
| Logging configuration not applied | `spring-boot/.../logging/LoggingApplicationListener` |
| Thread pool metrics missing | `micrometer-core/.../ExecutorServiceMetrics` |

## 11. Performance Implications

### Source Code Reading Affects Production Performance Awareness

Many performance issues are visible in the source code if you know where to look:

1. **Eager initialization**: `finishBeanFactoryInitialization()` instantiates ALL non-lazy singleton beans. If you have 500 beans and some are slow to construct (Hibernate SessionFactory, connection pools), you pay that cost at startup, not on first use.

2. **Proxy overhead**: Every `@Transactional`, `@Cacheable`, `@Async`, and `@Retryable` method call goes through CGLIB or JDK proxy. A single `@Transactional` method call involves: proxy invocation → TransactionInterceptor → TransactionAspectSupport → PlatformTransactionManager → actual method → commit/rollback → cleanup. That's ~6 additional stack frames per call.

3. **Reflection overhead**: Controller method invocation uses `Method.invoke()`. Argument resolution uses reflection to find the right `HandlerMethodArgumentResolver`. Bean instantiation uses `Constructor.newInstance()`. All of these are slower than direct Java calls, but acceptable for the level of abstraction provided.

4. **Thread-local access**: `TransactionSynchronizationManager` uses `ThreadLocal` for transaction state. `RequestContextHolder` uses `ThreadLocal` for request context. These are fast but have implications for async code — thread locals don't survive thread switches.

5. **Auto-configuration scanning cost**: Each auto-configuration candidate is evaluated by checking class presence (`Class.forName` equivalent), reading annotations, evaluating SpEL expressions. 200 candidates × several condition checks = non-trivial startup cost.

## 12. Architecture Implications

### What Source Code Reading Reveals About Architecture

Reading Spring source reveals that the framework's architecture is remarkably consistent:

**Pattern 1: Template Method everywhere.** `AbstractApplicationContext.refresh()`, `AbstractPlatformTransactionManager.getTransaction()`, `AbstractAutowireCapableBeanFactory.createBean()`. The skeleton algorithm is defined in the abstract class, and subclasses override specific steps.

**Pattern 2: Chain of Responsibility for processing.** HandlerMappings in DispatcherServlet, HandlerExceptionResolvers, HandlerMethodArgumentResolvers — each chain iterates through registered resolvers until one claims responsibility.

**Pattern 3: Proxy-based interception.** AOP, transactions, caching, async, retry — all implemented as BeanPostProcessors that wrap the original bean in a proxy that intercepts method calls.

**Pattern 4: Interface-first design.** BeanFactory interface hierarchy (BeanFactory → HierarchicalBeanFactory → ConfigurableBeanFactory → ConfigurableListableBeanFactory), ApplicationContext interface hierarchy, Resource hierarchy — Spring almost never depends on concrete classes internally.

**Pattern 5: Event-driven decoupling.** Spring's internal components communicate via ApplicationEvents, not direct method calls. The event multicaster decouples publishers from subscribers.

### Design Patterns in Spring

| Pattern | Where in Spring | Concrete Example |
|---------|----------------|------------------|
| **Factory** | BeanFactory, ApplicationContext | `DefaultListableBeanFactory.getBean("myService")` — creates and returns beans by name |
| **Proxy** | AOP subsystem | `CglibAopProxy`, `JdkDynamicAopProxy` — wrap beans to intercept method calls |
| **Template Method** | Abstract* classes | `AbstractApplicationContext.refresh()` — 12-step skeleton, subclasses override specific steps |
| **Strategy** | TransactionManager, ViewResolver, CacheManager | `PlatformTransactionManager` implementations — JTA, JDBC, Hibernate, each different strategy |
| **Observer** | ApplicationListener, event system | `@EventListener`, `ApplicationEventPublisher.publishEvent()` |
| **Chain of Responsibility** | HandlerMapping, HandlerInterceptor | `DispatcherServlet` iterates HandlerMappings; each interceptor in chain can short-circuit |
| **Decorator** | BeanPostProcessor, TransactionalProxy | AOP proxy wraps the original bean, adding behavior without changing the bean's code |
| **Singleton** | DefaultSingletonBeanRegistry | `singletonObjects` ConcurrentHashMap — each bean name maps to exactly one instance |
| **Adapter** | HandlerAdapter | `RequestMappingHandlerAdapter` adapts `HandlerMethod` to `Controller` interface |
| **Front Controller** | DispatcherServlet | Single entry point for all HTTP requests, dispatches to specific handlers |

## 13. Team Ownership Implications

### Who Should Read Spring Source

Not everyone needs to read Spring source. Here is the breakdown:

| Role | What to Read | When |
|------|-------------|------|
| Junior Engineer | Nothing (rely on docs/Stack Overflow) | Only if specifically assigned a deep Spring task |
| Mid-Level Engineer | `SpringApplication.run()`, `@Transactional` basics, `DispatcherServlet.doDispatch()` overview | When debugging production issues involving these subsystems |
| Senior Engineer | Transaction flow, AOP proxy creation, `refresh()` 12 steps, auto-config condition evaluation | When building custom starters, custom AOP aspects, or debugging complex transaction scenarios |
| Staff Engineer | Everything in Tier 1 and Tier 2 of the key classes list + ability to navigate to any subsystem | When establishing architecture standards, reviewing critical code, or making framework extension decisions |

### How to Share Source Code Knowledge

1. **Internal wiki pages** for each subsystem, with the call chain documented. Example: "How @Transactional works in this project" — 2-page document with the exact classes and methods, plus production-specific examples.

2. **Lunch-and-learn sessions** where one engineer traces through a Spring subsystem and teaches the rest. 30 minutes, one subsystem per session. Over 6 months, the entire team reaches a baseline.

3. **Pair debugging**: When a Spring-related production issue occurs, pair a junior engineer with a senior engineer. The senior navigates the Spring source while explaining the trace. This is the highest-retention learning method.

4. **Architecture Decision Records (ADRs)** that reference specific Spring source code when making framework usage decisions. Example: "ADR-012: Use REQUIRES_NEW Propagation — we chose this because TransactionAspectSupport (line 558) does X."

## 14. Interview Questions

### Question 1: "You're debugging a production issue where a @Transactional method called from another @Transactional method in the SAME class doesn't roll back when it should. Walk me through exactly what happens at the bytecode/proxy level."

**Staff-Level Answer**:

The core issue is that Spring AOP is proxy-based, not bytecode-weaving-based. Here's the exact mechanism:

When `AbstractAutoProxyCreator.wrapIfNecessary()` returns true for a bean (because it has `@Transactional` methods), `AbstractAutoProxyCreator.postProcessAfterInitialization()` creates either a CGLIB proxy (for classes) or a JDK dynamic proxy (for interfaces). The original bean instance is stored as the proxy's target.

For CGLIB: `CglibAopProxy.createProxyClassAndInstance()` generates a new class `MyService$$SpringCGLIB$$0` that extends `MyService`. Every public method is overridden to call `DynamicAdvisedInterceptor.intercept()`. This interceptor looks up the appropriate advice chain (which includes `TransactionInterceptor`) and calls it. The advice chain then calls `methodProxy.invoke(target, args)` to delegate to the actual target object.

When external code calls `myService.saveOrder()`:
1. The call goes to the CGLIB proxy's `saveOrder()` method
2. `DynamicAdvisedInterceptor.intercept()` retrieves the advice chain
3. `TransactionInterceptor.invoke()` begins the transaction
4. `methodProxy.invoke(target, args)` calls the REAL `MyService.saveOrder()` on the target
5. Inside `saveOrder()`, `this.processPayment()` calls `this` (the REAL object, not the proxy)
6. The `processPayment()` on `this` is the plain Java method — it has no proxy wrapping, no transaction interceptor
7. It runs inside the same transaction as `saveOrder()`, regardless of `propagation = REQUIRES_NEW`

The fix is either:
- Inject the bean into itself: `@Autowired private MyService self;` then call `self.processPayment()`
- Move `processPayment()` to a separate bean
- Use AspectJ compile-time weaving (which modifies bytecode, so `this` calls are also intercepted)

The architectural implication: Spring AOP is a proxy-based solution. This means it intercepts calls from object A to object B, but not calls from object B to object B. This is a fundamental limitation, not a bug. Compile-time weaving (AspectJ) solves it but adds complexity and tooling requirements.

---

### Question 2: "Explain the 12 steps of AbstractApplicationContext.refresh() and what would break if you skipped each one."

**Staff-Level Answer**:

The `refresh()` method in `AbstractApplicationContext` is a template method implementing the entire application context lifecycle. Skipping any step would cause specific, predictable failures:

1. **prepareRefresh()** — Sets the `active` flag, validates required properties, initializes PropertySources. Skip: The context appears inactive, `environment.getRequiredProperty()` doesn't validate, early ApplicationListeners are not available.

2. **obtainFreshBeanFactory()** — Creates a new `DefaultListableBeanFactory`, loads all BeanDefinitions. Skip: No IoC container exists. Every subsequent step gets NPE trying to access `beanFactory`.

3. **prepareBeanFactory(beanFactory)** — Sets ClassLoader, registers default singletons (environment, systemProperties), configures post-processor detection. Skip: `@Autowired` on `ApplicationContext` fails, SpEL expressions fail, standard singletons are missing from the context.

4. **postProcessBeanFactory(beanFactory)** — Extension point for context subclasses. Web context registers request/session scopes. Skip: `RequestScoped` and `SessionScoped` beans fail to create. The web context can't handle scoped proxy beans.

5. **invokeBeanFactoryPostProcessors(beanFactory)** — Runs `BeanFactoryPostProcessors` (modify bean definitions) and `BeanDefinitionRegistryPostProcessors` (register additional bean definitions). This is where `ConfigurationClassPostProcessor` runs, which parses `@Configuration` classes and triggers auto-configuration. Skip: None of your `@Configuration` classes are parsed. No `@Bean` methods are registered. No auto-configuration activates. You get an empty context with only the default beans.

6. **registerBeanPostProcessors(beanFactory)** — Registers `BeanPostProcessor` beans. Skip: `@Autowired` injection fails (no `AutowiredAnnotationBeanPostProcessor`), `@PostConstruct` fails (no `CommonAnnotationBeanPostProcessor`), AOP proxying fails (no `AbstractAutoProxyCreator`), `@Transactional` fails, `@Cacheable` fails.

7. **initMessageSource(beanFactory)** — Initializes i18n message source. Skip: `MessageSource.getMessage()` returns default messages or throws. Not a hard failure for most apps, but i18n is broken.

8. **initApplicationEventMulticaster()** — Initializes the event multicaster for `ApplicationEvent` publishing. Skip: `@EventListener` methods never fire. `ApplicationEventPublisher.publishEvent()` does nothing. Startup/ready events don't fire.

9. **onRefresh()** — Template method for context-specific initialization. In web contexts, this creates the embedded web server and starts it on the configured port. Skip: No web server starts. The app doesn't listen on any port. HTTP requests fail with connection refused.

10. **registerListeners()** — Finds `ApplicationListener` beans and registers them with the multicaster. Skip: Listeners registered as beans don't receive early events (starting, environment prepared). They might still receive later events if registered elsewhere.

11. **finishBeanFactoryInitialization(beanFactory)** — Instantiates all non-lazy singleton beans. This is where your services, controllers, repositories are actually created. Skip: The context is full of BeanDefinitions but no actual beans. `context.getBean()` throws `BeanCreationException` for everything.

12. **finishRefresh()** — Clears caches, initializes lifecycle processor, publishes `ContextRefreshedEvent`. Skip: Lifecycle beans (start/stop) aren't managed, caches aren't cleared (memory leak risk), `ContextRefreshedEvent` doesn't fire (components waiting for this event never initialize).

The architectural insight: This template method is the fundamental guarantee of Spring's consistency. Every ApplicationContext implementation follows these 12 steps. Custom extensions should override specific steps (like `onRefresh()`) rather than reinventing the sequence. Understanding this method means you can debug ANY Spring application, regardless of custom configurations.

---

### Question 3: "How would you contribute a new feature to Spring Framework? Walk me through the complete process from idea to merged PR."

**Staff-Level Answer**:

The contribution process involves several stages, each with specific requirements:

**Stage 1: Pre-Proposal Validation**
- Before any code: search the issue tracker (`github.com/spring-projects/spring-framework/issues`) for existing requests
- Check if the feature aligns with Spring's philosophy (non-invasive, abstraction-first, pluggable)
- Spring rarely accepts features that can be implemented as a third-party library
- Ask: "Does this need to be IN Spring, or can it sit ON TOP of Spring?" If it can be a library, make it a library

**Stage 2: Issue or Discussion**
- Create a GitHub issue with the `type: enhancement` label
- Template includes: motivation, proposed solution, alternatives considered, impact on existing code
- Expect discussion: Spring maintainers are active but selective. A typical enhancement issue gets 5-20 comments over 2-4 weeks
- The maintainers may suggest: "This should be a separate library" or "This belongs in Spring Boot, not Spring Framework"

**Stage 3: Contributor License Agreement (CLA)**
- Sign the Spring CLA at `https://cla.pivotal.io/sign/spring`
- This is required BEFORE any code review. Individual or corporate CLA
- Without a signed CLA, PRs will be automatically rejected

**Stage 4: Code Conventions**
- Spring has strict code style (enforced via Checkstyle and IDE formatter configs in the repo)
- No wildcard imports
- No `@author` tags in JavaDoc
- JavaDoc on all public and protected methods
- Line length: 120 characters
- Tabs: uses spaces (4 for Java, 2 for XML)
- Commit messages: one-line summary (50 chars), blank line, detailed description (wrap at 72)
- Commits reference the GitHub issue: `Closes gh-12345`

**Stage 5: Testing Requirements**
- Tests ARE the contract. Features without tests are rejected
- Test class naming: `FeatureNameTests` (not `TestFeatureName`)
- Use JUnit 5, spring-test module
- Tests must pass on Java 17+ and 21+
- Tests must NOT depend on external services (no real databases, no network calls)
- Use `@MockBean`, `@TestConfiguration`, or embedded alternatives (H2, Embedded Kafka, etc.)
- Performance-sensitive features need JMH benchmarks

**Stage 6: PR Process**
- Fork the repo, create a feature branch from `main`
- One PR = one feature. No mega-PRs
- PR description must include: what, why, how tested, backward compatibility impact
- CI runs: build with all JDK versions, Checkstyle, API compatibility check (Japicmp), integration tests
- Expect 2-3 rounds of review from maintainers
- Review feedback is direct and technical — don't take it personally
- Once approved, a maintainer merges. Contributors don't merge their own PRs

**Stage 7: Backporting and Documentation**
- Maintainers decide if the feature is backported to maintenance branches (e.g., 6.1.x)
- Reference documentation must be updated in the `src/docs/asciidoc/` directory
- Wiki or reference documentation update PR is typically separate from the code PR

**Real-world timeline**: A well-prepared minor feature PR might take 2-4 weeks from PR to merge. A significant feature (e.g., the `@HttpExchange` annotations in 6.0) involved an RFC process that took 6+ months of discussion before any code was written.

The architectural lesson: Spring's contribution process mirrors its design philosophy. The requirement for backward compatibility, abstraction-first design, and comprehensive testing exists because Spring is used by millions of applications. A seemingly simple change to `AbstractApplicationContext.refresh()` could break thousands of production systems. The process is slow by design — deliberate, not bureaucratic.

## 15. Hands-On Exercises

### Exercise 1: Trace a Single Request End-to-End

**Goal**: Trace one HTTP request from `doDispatch()` to the database and back.

**Setup**:
```java
@RestController
public class UserController {
    @GetMapping("/users/{id}")
    public UserResponse getUser(@PathVariable Long id) {
        return userService.getUser(id);
    }
}

@Service
public class UserService {
    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new UserNotFoundException(id));
        return new UserResponse(user.getId(), user.getName());
    }
}
```

**Steps**:
1. Set breakpoints at: `DispatcherServlet.doDispatch()` line ~985 (getHandler), line ~1045 (handle), line ~1070 (processDispatchResult)
2. Set breakpoint at: `TransactionInterceptor.invoke()`
3. Set breakpoint at: `DataSourceTransactionManager.doBegin()`
4. Send `GET http://localhost:8080/users/1`
5. Step through each breakpoint, documenting the call stack at each
6. Answer: Which HandlerMapping matched? Which HandlerAdapter was selected? Did the transaction commit or rollback? What was the response content type?

### Exercise 2: Trace Bean Creation for a @Service

**Goal**: Understand exactly when and how your service bean is created.

**Steps**:
1. Set breakpoint at: `DefaultListableBeanFactory.preInstantiateSingletons()` — first line of loop
2. Set breakpoint at: `AbstractAutowireCapableBeanFactory.doCreateBean()` — line where `createBeanInstance` is called
3. Set breakpoint at: `AbstractAutowireCapableBeanFactory.populateBean()` — before setting properties
4. Set breakpoint at: `AbstractAutowireCapableBeanFactory.initializeBean()` — before init callbacks
5. Start the application in debug mode
6. When breakpoints hit for YOUR service bean (watch `beanName` variable):
   a. Step into `createBeanInstance()` — which constructor was used? Was it CGLIB-enhanced (@Configuration)?
   b. In `populateBean()`, navigate to the `postProcessProperties()` calls — which fields are being autowired?
   c. In `initializeBean()`, step into `applyBeanPostProcessorsAfterInitialization()` — is `wrapIfNecessary()` called? Is the bean proxied?
7. After the bean is created, evaluate `beanFactory.getSingleton("userService")` — examine the object's class name (ends with `$$SpringCGLIB$$0` if proxied)

### Exercise 3: Simulate an Auto-Configuration Conflict

**Goal**: Understand how auto-configuration decisions are made and how to debug them.

**Steps**:
1. Create a Spring Boot app with `spring-boot-starter-web` and `spring-boot-starter-data-mongodb`
2. Add `--debug` flag: `java -jar myapp.jar --debug`
3. Find "CONDITIONS EVALUATION REPORT" in output
4. Find `MongoAutoConfiguration` — is it in positive or negative matches?
5. Add `spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration` to `application.properties`
6. Re-run with `--debug` — find MongoDB auto-config in "Exclusions" section
7. Now implement a custom auto-configuration: create `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with your own `@AutoConfiguration` class
8. Set `@ConditionalOnProperty("myapp.mongo.enabled")` on your class
9. Run with different property values and observe the condition report

### Exercise 4: Read TransactionInterceptor.source and Answer Specific Questions

**Goal**: Develop source-reading skills by answering concrete questions.

**Questions to answer** (read the source, don't guess):
1. If a `@Transactional` method is called from outside the bean, what are the exact 5 method calls that happen between the proxy and the actual method execution? List class and method names.
2. When `@Transactional(timeout = 30)` is set, at what exact line does the timeout take effect? What happens when it expires?
3. What happens when a `@Transactional(propagation = REQUIRES_NEW)` method is called inside an existing transaction? Trace the exact code path — which `if` branch is taken in `AbstractPlatformTransactionManager.getTransaction()`?
4. If a `@Transactional` method catches a `RuntimeException` and doesn't rethrow it, does the transaction commit or rollback? Trace exactly where the decision is made.
5. What determines whether a CGLIB or JDK dynamic proxy is created for a `@Transactional` bean? Read `DefaultAopProxyFactory.createAopProxy()`.

## 16. Advanced Challenges

### Challenge 1: Build a Custom BeanPostProcessor That Logs Bean Creation Order

Write a `BeanPostProcessor` that logs every bean's name, class, and creation timestamp during `postProcessAfterInitialization()`. Collect the data into a sorted list and expose it via an Actuator endpoint. Compare the creation order with `--debug` output and explain any discrepancies.

### Challenge 2: Implement a Custom Auto-Configuration With Full Condition Support

Create a library JAR with its own auto-configuration:
- `@AutoConfiguration` class with `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`
- Register it via `META-INF/spring/...AutoConfiguration.imports`
- Ship it as a starter JAR (convention: `mycompany-spring-boot-starter` with dependency on `mycompany-spring-boot-autoconfigure`)
- Write tests that verify: auto-config activates correctly, auto-config doesn't activate when class is missing, auto-config doesn't activate when property is false, user's custom bean overrides the auto-configured bean

### Challenge 3: Trace and Document a Complete Subsystem

Choose one Spring subsystem (e.g., `@Async` execution, `@Scheduled` task scheduling, `@EventListener` event handling) and produce a complete internal documentation:

1. Entry point class and method
2. Full call chain (every class and method in sequence)
3. All configuration options and where they are read from
4. All exception paths and what happens on failure
5. Threading model (which thread does what)
6. Integration points with other subsystems
7. A diagram showing the flow

### Challenge 4: Write an ArchUnit Test That Validates Your Project's Usage of Spring APIs

Write ArchUnit rules that enforce:
1. No `ApplicationContext.getBean()` calls in production code (only in `@Configuration` classes)
2. All `@Transactional` annotations specify `rollbackFor`
3. No field injection (`@Autowired` on fields) — only constructor injection
4. All controller methods have `@PreAuthorize` or equivalent security annotation
5. No circular dependencies between packages

### Challenge 5: Contribute a Documentation PR to Spring Framework

Find a class in Spring Framework where the JavaDoc is incomplete or unclear. Write a PR that improves it:
1. Fork spring-projects/spring-framework
2. Find a public class/method with insufficient or outdated JavaDoc
3. Write comprehensive JavaDoc explaining: purpose, thread safety, expected usage, constraints
4. Submit the PR following Spring's contribution guidelines
5. This is the lowest-barrier way to become a Spring contributor — documentation PRs are reviewed more quickly and build relationships with maintainers
