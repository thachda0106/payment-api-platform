# Session 15: Dependency Injection & IoC Container Internals

## 1. Why This Topic Exists

Dependency injection looks simple: put `@Autowired` on a field, Spring fills it in. This abstraction leaks catastrophically when you encounter `NoSuchBeanDefinitionException` with "expected 1 but found 2," when `@Transactional` silently fails on self-invocation, or when a `@Configuration` class creates three instances of a supposedly-singleton bean.

The IoC container is not a HashMap of beans. It is a triple-caching, recursive-resolution, reflection-driven, proxy-wrapping machine that can create 10,000 interconnected objects in seconds — but only if you understand its rules. When you violate those rules, the error messages are cryptic because the container is 15 layers deep in its own abstraction.

**Staff engineer insight**: Mastering the IoC container internals lets you diagnose any wiring failure in seconds rather than hours. It lets you explain WHY field injection is a testability anti-pattern (not just parroting a best-practice). And it lets you extend the container with custom resolution logic when the defaults fall short.

## 2. Mental Model

```
The DI Container = a function mapping:

    f(BeanDefinition, dependencies, scope) → Object

Where:
  - BeanDefinition: the recipe (class, constructor args, property values, scope, init/destroy)
  - dependencies: resolved by walking the dependency graph recursively via getBean()
  - scope: determines WHEN f is called and HOW results are cached

Internal data structures:
  ┌─────────────────────────────────────────────────────┐
  │ DefaultListableBeanFactory                          │
  │                                                     │
  │  beanDefinitionMap: ConcurrentHashMap<String, BeanDefinition>
  │    ↓ "orderService" → BeanDefinition {              │
  │        beanClass = OrderService.class,               │
  │        scope = "singleton",                          │
  │        autowireMode = AUTOWIRE_CONSTRUCTOR,          │
  │        ...                                           │
  │    }                                                 │
  │                                                     │
  │  singletonObjects: ConcurrentHashMap<String, Object> │
  │    ↓ "orderService" → OrderService@4f3c            │
  │                                                     │
  │  earlySingletonObjects: ConcurrentHashMap<>          │
  │    ↓ (circular dependency early references)          │
  │                                                     │
  │  singletonFactories: HashMap<String, ObjectFactory>  │
  │    ↓ (Level 3 cache, raw bean before population)     │
  │                                                     │
  │  resolvedDependencies: Set<String>                   │
  │    ↓ beans with all dependencies resolved            │
  │                                                     │
  │  dependencyMap: Map<String, Set<String>>             │
  │    ↓ "orderService" → {"dataSource", "orderRepo"}   │
  │                                                     │
  │  dependentBeanMap: Map<String, Set<String>>          │
  │    ↓ "dataSource" → {"orderService", "paymentService"│
  │    ↓ (reverse index: who depends on me)              │
  └─────────────────────────────────────────────────────┘
```

## 3. Internal Architecture

### BeanDefinition → BeanFactory → SingletonObjects

```java
// ── Phase 1: Registration (during refresh step 5) ──
// ConfigurationClassPostProcessor registers BeanDefinitions:

BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
// registry is DefaultListableBeanFactory implementing BeanDefinitionRegistry

// For each @Bean method or @Component class:
GenericBeanDefinition bd = new GenericBeanDefinition();
bd.setBeanClass(OrderService.class);
bd.setScope(BeanDefinition.SCOPE_SINGLETON);
bd.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
bd.setDependsOn("dataSource");
// Constructor argument values:
bd.getConstructorArgumentValues().addGenericArgumentValue(
    new RuntimeBeanReference("orderRepository"));  // reference to another bean
registry.registerBeanDefinition("orderService", bd);

// ── Phase 2: Storage ──
// Inside DefaultListableBeanFactory:
private final Map<String, BeanDefinition> beanDefinitionMap = 
    new ConcurrentHashMap<>(256);

// ── Phase 3: Instantiation (during refresh step 11) ──
// getBean() → createBean() → doCreateBean():
Object bean = constructor.newInstance(resolvedArgs);
// ... populateBean, initializeBean ...
this.singletonObjects.put("orderService", bean);
```

### BeanDefinition Internal Fields

```java
public class GenericBeanDefinition extends AbstractBeanDefinition {
    // From AbstractBeanDefinition:
    private volatile Object beanClass;              // Class<?> or String className
    private String scope = SCOPE_DEFAULT;           // "singleton", "prototype", etc.
    private boolean abstractFlag = false;            // Is this a template definition?
    private Boolean lazyInit;                        // Lazy initialization?
    private int autowireMode = AUTOWIRE_NO;          // AUTOWIRE_BY_NAME, BY_TYPE, CONSTRUCTOR
    private int dependencyCheck = DEPENDENCY_CHECK_NONE;
    private String[] dependsOn;                      // Explicit dependency ordering
    private boolean autowireCandidate = true;         // Eligible for autowiring by other beans?
    private boolean primary = false;                  // @Primary candidate
    private String factoryBeanName;                   // If created by a factory bean
    private String factoryMethodName;                 // If created by a static/@Bean method
    private ConstructorArgumentValues constructorArgumentValues;
    private MutablePropertyValues propertyValues;
    private String initMethodName;                   // Custom init method
    private String destroyMethodName;                // Custom destroy method
    private int role = ROLE_APPLICATION;             // ROLE_APPLICATION, ROLE_INFRASTRUCTURE
    private String description;
    private Resource resource;                       // Originating resource (for error messages)
    
    // From AttributeAccessorSupport:
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    // Metadata about the bean (qualifier annotations, etc.)
}
```

### How @Autowired Resolution Actually Works

```java
// The resolution chain for: @Autowired private OrderRepository repo;

// 1. AutowiredAnnotationBeanPostProcessor detects the field
//    during MergedBeanDefinitionPostProcessor phase
//    and registers it as an InjectionMetadata.InjectedElement

// 2. During populateBean(), the processor iterates all InjectedElements:

public class AutowiredFieldElement extends InjectionMetadata.InjectedElement {
    @Override
    protected void inject(Object bean, String beanName, PropertyValues pvs) 
            throws Throwable {
        Field field = (Field) this.member;
        DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
        
        // 3. The actual resolution:
        Object value = beanFactory.resolveDependency(desc, beanName, 
            autowiredBeanNames, typeConverter);
        
        if (value != null) {
            ReflectionUtils.makeAccessible(field);
            field.set(bean, value);   // Direct field injection via reflection
        }
    }
}

// 4. DefaultListableBeanFactory.resolveDependency():
public Object resolveDependency(DependencyDescriptor descriptor, 
        String requestingBeanName,
        Set<String> autowiredBeanNames, 
        TypeConverter typeConverter) throws BeansException {
    
    // ── Step A: Check @Qualifier ──
    Class<?> type = descriptor.getDependencyType();
    
    // ── Step B: Handle special types ──
    if (type == Optional.class) {
        // @Autowired Optional<OrderRepository>
        return Optional.ofNullable(
            doResolveDependency(descriptor, requestingBeanName, 
                autowiredBeanNames, typeConverter));
    }
    
    if (type == ObjectFactory.class || type == ObjectProvider.class) {
        // @Autowired ObjectProvider<OrderRepository>
        return new DependencyObjectProvider(descriptor, requestingBeanName);
    }
    
    if (type == javax.inject.Provider.class) {
        // JSR-330 Provider
        return new Jsr330Factory().createDependencyProvider(descriptor, requestingBeanName);
    }
    
    // ── Step C: Handle @Value ──
    Object value = getAutowireCandidateResolver()
        .getSuggestedValue(descriptor);
    if (value != null) {
        // Resolves ${property:default} expressions
        return resolveEmbeddedValue(value);
    }
    
    // ── Step D: Handle Collections and Maps ──
    // @Autowired List<OrderRepository>  → finds ALL beans of type
    // @Autowired Map<String, OrderRepository>  → beanName → bean
    if (descriptor.isMultiValued()) {
        return resolveMultipleBeans(descriptor, requestingBeanName, 
            autowiredBeanNames, typeConverter);
    }
    
    // ── Step E: Standard single-bean resolution ──
    return doResolveDependency(descriptor, requestingBeanName, 
        autowiredBeanNames, typeConverter);
}

// 5. doResolveDependency() — the core resolution:
public Object doResolveDependency(DependencyDescriptor descriptor,
        String beanName, Set<String> autowiredBeanNames,
        TypeConverter typeConverter) throws BeansException {
    
    // a. Try exact bean name match first (for @Qualifier)
    Object shortcut = descriptor.resolveShortcut(this);
    if (shortcut != null) return shortcut;
    
    // b. Find matching beans by type
    Class<?> type = descriptor.getDependencyType();
    
    // Get @Qualifier value
    String qualifier = descriptor.getQualifier() != null ?
        ((Qualifier) descriptor.getQualifier()).value() : null;
    
    // c. Find ALL candidates matching the type
    Map<String, Object> matchingBeans = 
        findAutowireCandidates(beanName, type, descriptor);
    
    if (matchingBeans.isEmpty()) {
        if (descriptor.isRequired()) {
            raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
        }
        return null;
    }
    
    // d. If multiple candidates: determine the "primary" or use @Qualifier
    String autowiredBeanName;
    Object instance;
    
    if (matchingBeans.size() > 1) {
        autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
        // ── Resolution order when multiple candidates: ──
        // 1. @Primary bean wins
        // 2. @Priority (javax.annotation.Priority, highest priority wins)
        // 3. Bean name matching the field/setter name (byName fallback)
        if (autowiredBeanName == null) {
            if (descriptor.isRequired()) {
                throw new NoUniqueBeanDefinitionException(type, 
                    matchingBeans.keySet());
            }
            return null;
        }
        instance = matchingBeans.get(autowiredBeanName);
    } else {
        // Single candidate by type
        Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
        autowiredBeanName = entry.getKey();
        instance = entry.getValue();
    }
    
    if (autowiredBeanNames != null) {
        autowiredBeanNames.add(autowiredBeanName);
    }
    
    // e. Return the bean (already fully initialized from getBean())
    if (instance instanceof Class) {
        // Bean not yet created → getBean() will create it
        instance = descriptor.resolveCandidate(autowiredBeanName, type, this);
    }
    
    return instance;
}

// 6. findAutowireCandidates — how the type search works:
protected Map<String, Object> findAutowireCandidates(
        String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {
    
    // a. Get all bean names that match the required type
    String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
        this, requiredType, true, descriptor.isEager());
    
    // b. Filter: isAutowireCandidate() check
    Map<String, Object> result = CollectionUtils.newLinkedHashMap(candidateNames.length);
    for (String candidate : candidateNames) {
        if (!isSelfReference(beanName, candidate) && 
            isAutowireCandidate(candidate, descriptor)) {
            // add bean to candidates
            result.put(candidate, descriptor.resolveCandidate(candidate, requiredType, this));
        }
    }
    
    // c. If no direct matches, check parent context (fallback)
    if (result.isEmpty()) {
        // Check if any bean in parent context matches
        DependencyDescriptor fallbackDescriptor = ...
        // Recurse with fallback
    }
    
    // d. Trim to single candidate or throw
    return result;
}
```

### @Qualifier and @Primary Internals

```java
// @Qualifier resolution:
// When @Autowired @Qualifier("paymentRepo") is used:

// 1. The qualifier value is extracted during MergedBeanDefinitionPostProcessor phase
// 2. During doResolveDependency(), it queries:
descriptor.getQualifier(); // Returns @Qualifier annotation

// 3. Candidates are filtered:
// For each candidate "beanName":
//    a. Check if bean definition has matching qualifier
//    b. Check by-type OR by-name
//    c. Check @Qualifier annotation on the BEAN DEFINITION, not just the type

// @Primary resolution:
// If multiple candidates and NO explicit @Qualifier:
determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor desc) {
    // 1. Try @Primary
    for (Map.Entry<String, Object> entry : candidates.entrySet()) {
        if (isPrimary(entry.getKey(), entry.getValue())) {
            if (primaryCandidate != null) {
                // Multiple @Primary beans → ambiguous, fail
                throw new NoUniqueBeanDefinitionException(...);
            }
            primaryCandidate = entry.getKey();
        }
    }
    if (primaryCandidate != null) return primaryCandidate;
    
    // 2. Try @Priority
    for (Map.Entry<String, Object> entry : candidates.entrySet()) {
        Integer priority = getPriority(entry.getValue());
        if (priority != null) {
            // Bean with highest priority wins
        }
    }
    
    // 3. Try fallback to bean name matching field name
    // e.g., @Autowired OrderRepository paymentRepo → bean named "paymentRepo"
    String fallbackName = descriptor.getDependencyName();
    if (candidates.containsKey(fallbackName)) {
        return fallbackName;
    }
}
```

### Constructor Injection vs Field Injection vs Setter Injection

```java
// ── CONSTRUCTOR INJECTION (Preferred by Spring Team) ──

@Service
public class OrderService {
    private final OrderRepository repo;       // final: immutability guarantee
    private final PaymentGateway gateway;     // final: explicit required dependencies
    
    public OrderService(OrderRepository repo, PaymentGateway gateway) {
        this.repo = repo;
        this.gateway = gateway;
        // Bean is fully usable after construction
        // No @Autowired needed for single-constructor scenario
    }
}

// Resolution: ConstructorResolver.autowireConstructor()
// 1. Determine constructor to use:
//    a. @Autowired on constructor → that one
//    b. Single constructor → that one (no @Autowired needed!)
//    c. Multiple constructors, no @Autowired → default (no-arg) constructor
//    d. Multiple constructors, one @Autowired → that one
//    e. Multiple @Autowired constructors → error
// 2. For each parameter:
//    a. Create DependencyDescriptor
//    b. resolveDependency() → triggers getBean() for each dependency
//    c. If any dependency cannot be resolved → fail fast at startup
// 3. newInstance(resolvedArgs...)

// Pro: Immutability (final fields), explicit required deps, fail-fast, testable
// Con: Long constructor signatures if too many deps (>5 = design smell)


// ── FIELD INJECTION ──

@Service
public class OrderService {
    @Autowired private OrderRepository repo;      // Mutable, no final
    @Autowired private PaymentGateway gateway;    // Hidden dependency
}

// Resolution: AutowiredAnnotationBeanPostProcessor.AutowiredFieldElement.inject()
// 1. Field found during MergedBeanDefinitionPostProcessor phase
// 2. During populateBean(): resolveDependency(), then field.set(bean, value)
// 3. ReflectionUtils.makeAccessible(field) — breaks encapsulation

// Pro: Concise, no boilerplate
// Con: Hidden dependencies (can't tell from constructor what the class needs),
//      No immutability, hard to test without Spring, encourages too many deps,
//      Null until population phase (can't use in constructor logic)


// ── SETTER INJECTION ──

@Service
public class OrderService {
    private OrderRepository repo;
    private PaymentGateway gateway;
    
    @Autowired
    public void setRepo(OrderRepository repo) { this.repo = repo; }
    
    @Autowired(required = false)
    public void setGateway(PaymentGateway gateway) { this.gateway = gateway; }
}

// Resolution: AutowiredAnnotationBeanPostProcessor.AutowiredMethodElement.inject()
// 1. Method found during MergedBeanDefinitionPostProcessor phase
// 2. During populateBean(): resolve each parameter, then method.invoke(bean, args)
// 3. Optional dependencies via required=false

// Pro: Optional dependencies, reconfigurable after construction, good for legacy refactoring
// Con: Mutable, can be in incomplete state between construction and injection,
//      Setter can be called multiple times if misused


// ── TRADE-OFF SUMMARY ──
// 
// Constructor injection: USE for required dependencies.
//   → Spring 4.3+: @Autowired on single constructor is optional.
//   → It enforces the "fully constructed object" invariant.
//   → Unit testing: just pass mocks to constructor. No Spring needed.
//
// Setter injection: USE for OPTIONAL dependencies.
//   → @Autowired(required = false)
//   → Also useful when dependency is only needed for SOME configurations.
//
// Field injection: AVOID in production code.
//   → Use ONLY in tests (@SpringBootTest) where class is not manually instantiated.
//   → The lack of final/immutability is a real problem in multi-threaded environments.
//   → Makes unit testing impossible without reflection or Spring test runner.
```

### Internal Dependency Graph Data Structures

```java
// Inside DefaultListableBeanFactory:

// Forward dependency map: which beans does bean A depend on?
// "orderService" → {"orderRepository", "paymentGateway"}
private final Map<String, Set<String>> dependenciesForBeanMap = 
    new ConcurrentHashMap<>(64);

// Reverse dependency map: which beans depend on bean A?
// "dataSource" → {"orderService", "paymentService", "cacheInitializer"}
private final Map<String, Set<String>> dependentBeanMap = 
    new ConcurrentHashMap<>(64);

// This is updated during bean creation:
void registerDependentBean(String beanName, String dependentBeanName) {
    // When "orderService" declares dependency on "dataSource":
    //   dependenciesForBeanMap["orderService"].add("dataSource")
    //   dependentBeanMap["dataSource"].add("orderService")
    
    Set<String> dependencies = this.dependenciesForBeanMap.computeIfAbsent(
        beanName, k -> new LinkedHashSet<>(8));
    dependencies.add(dependentBeanName);
    
    Set<String> dependents = this.dependentBeanMap.computeIfAbsent(
        dependentBeanName, k -> new LinkedHashSet<>(8));
    dependents.add(beanName);
}

// Used by:
// getDependenciesForBean("orderService") → {"dataSource", "orderRepository"}
// getDependentBeans("dataSource") → {"orderService", "paymentService"}
// Used in: isDependent() checks for circular dependency detection
```

### How @Configuration and @Bean Work Internally (CGLIB Proxying)

```java
// When you write:
@Configuration
public class AppConfig {
    @Bean
    public DataSource dataSource() {
        return new HikariDataSource();
    }
    
    @Bean
    public OrderRepository orderRepository() {
        return new OrderRepository(dataSource());  // ← Method call!
    }
}

// Spring does NOT execute this code as written.
// Instead, ConfigurationClassPostProcessor detects @Configuration:
// 1. It creates a CGLIB proxy of AppConfig
// 2. The proxy intercepts all @Bean method calls
// 3. When orderRepository() calls dataSource(), the proxy intercepts:
//    "Is the DataSource singleton already created? → return cached instance"
//    "Not yet created? → call super.dataSource() → cache → return"

// The CGLIB proxy (generated bytecode, reconstructed logic):
public class AppConfig$$SpringCGLIB$$0 extends AppConfig 
        implements SpringProxy, Advised, Factory {
    
    private final BeanFactory beanFactory;
    
    @Override
    public DataSource dataSource() {
        // Intercepted by BeanMethodInterceptor:
        // 1. Check singletonObjects cache
        Object cached = beanFactory.getSingleton("dataSource");
        if (cached != null) {
            return (DataSource) cached;
        }
        // 2. Call the real method
        DataSource ds = super.dataSource();
        // 3. Register as singleton
        return ds;
    }
    
    @Override
    public OrderRepository orderRepository() {
        Object cached = beanFactory.getSingleton("orderRepository");
        if (cached != null) {
            return (OrderRepository) cached;
        }
        OrderRepository repo = super.orderRepository();
        // ... super.orderRepository() internally calls this.dataSource()
        // ... which is intercepted and returns the singleton
        return repo;
    }
}
```

**proxyBeanMethods = false** (Spring Boot 3.x recommends this for performance):
```java
@Configuration(proxyBeanMethods = false)  // No CGLIB proxy!
public class AppConfig {
    @Bean
    public DataSource dataSource() {
        return new HikariDataSource();
    }
    
    @Bean
    public OrderRepository orderRepository(DataSource ds) {  // ← Parameter injection!
        return new OrderRepository(ds);
    }
    // Without CGLIB proxy, each call to dataSource() creates a NEW instance.
    // Parameter injection ensures the Spring-managed singleton is used.
}
```

### @ComponentScan Internals

```java
// When you write @ComponentScan("com.myapp"):

// 1. ConfigurationClassParser encounters @ComponentScan
// 2. It creates a ClassPathBeanDefinitionScanner:

public class ClassPathBeanDefinitionScanner extends ClassPathScanningCandidateComponentProvider {
    private BeanDefinitionRegistry registry;
    
    public int scan(String... basePackages) {
        int beanCount = 0;
        for (String basePackage : basePackages) {
            Set<BeanDefinition> candidates = findCandidateComponents(basePackage);
            for (BeanDefinition candidate : candidates) {
                // Check @Scope, @Lazy, @Primary, @DependsOn annotations
                // Register in the BeanDefinitionRegistry
                String beanName = this.beanNameGenerator.generateBeanName(candidate, registry);
                if (candidate instanceof AnnotatedBeanDefinition) {
                    AnnotationConfigUtils.processCommonDefinitionAnnotations(
                        (AnnotatedBeanDefinition) candidate);
                }
                if (checkCandidate(beanName, candidate)) {
                    BeanDefinitionHolder definitionHolder = 
                        new BeanDefinitionHolder(candidate, beanName);
                    definitionHolder = applyScopedProxyMode(definitionHolder, registry);
                    BeanDefinitionReaderUtils.registerBeanDefinition(
                        definitionHolder, registry);
                    beanCount++;
                }
            }
        }
        return beanCount;
    }
}

// 3. findCandidateComponents() — the classpath scanning (expensive!):
private Set<BeanDefinition> scanCandidateComponents(String basePackage) {
    Set<BeanDefinition> candidates = new LinkedHashSet<>();
    
    // a. Convert package name to path: "com.myapp" → "com/myapp"
    String packageSearchPath = "classpath*:" + 
        resolveBasePackage(basePackage) + '/' + 
        this.resourcePattern;  // default: "**/*.class"
    
    // b. Find all .class files under this path
    Resource[] resources = getResourcePatternResolver()
        .getResources(packageSearchPath);
    
    // c. For each .class file:
    for (Resource resource : resources) {
        // Read the class metadata using ASM (avoids loading the class)
        MetadataReader metadataReader = getMetadataReaderFactory()
            .getMetadataReader(resource);
        
        // d. Check if the class is a candidate:
        //    - Has @Component (or stereotype) annotation?
        //    - Is not an interface?
        //    - Is not abstract?
        //    - Is not excluded by excludeFilters?
        if (isCandidateComponent(metadataReader)) {
            ScannedGenericBeanDefinition sbd = new ScannedGenericBeanDefinition(metadataReader);
            sbd.setSource(resource);
            
            if (isCandidateComponent(sbd)) {
                candidates.add(sbd);
            }
        }
    }
    
    return candidates;
}

// COST OF @ComponentScan:
// - Scans ALL .class files in the base package
// - Each file requires ASM parsing of annotation metadata
// - Full scan of 10,000 classes: ~100-300ms on SSD
// - Narrow base packages minimize scan time
// - @SpringBootApplication scanBasePackages = {"com.myapp"} 
//   → scans EVERYTHING under that package
```

### @Profile and @Conditional Internal Processing

```java
// @Profile is just @Conditional with ProfileCondition:

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ProfileCondition.class)  // ← It's just syntactic sugar for @Conditional!
public @interface Profile {
    String[] value();
}

// ProfileCondition:
class ProfileCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        MultiValueMap<String, Object> attrs = metadata.getAllAnnotationAttributes(
            Profile.class.getName());
        if (attrs != null) {
            for (Object value : attrs.get("value")) {
                if (context.getEnvironment().acceptsProfiles(
                        Profiles.of((String[]) value))) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}

// HOW IT'S EVALUATED:
// 1. During ConfigurationClassParser processing
// 2. When encountering @Profile("production") on a @Configuration class:
//    a. Create ConditionEvaluator
//    b. shouldSkip(metadata, ConfigurationPhase.PARSE_CONFIGURATION)
//       → Check @Conditional(ProfileCondition.class)
//       → ProfileCondition.matches() queries Environment.getActiveProfiles()
//       → If "production" not in active profiles → class is SKIPPED entirely
// 3. When @Profile is on a @Bean method (ConfigurationPhase.REGISTER_BEAN):
//    a. Same evaluation, but at bean-registration time
//    b. If profile doesn't match → @Bean method is not registered

// The critical insight: @Profile at the class level is evaluated at PARSE_CONFIGURATION
// phase — if it doesn't match, the ENTIRE @Configuration class and all its @Bean methods
// are skipped, saving parsing time. @Profile at method level skips only that @Bean method.
```

### AOP Proxy Creation Mechanism

```java
// AOP proxies are created in the AbstractAutoProxyCreator.wrapIfNecessary()
// called during BeanPostProcessor.postProcessAfterInitialization()

// Decision: JDK Dynamic Proxy OR CGLIB Proxy?

protected Object createProxy(Class<?> beanClass, String beanName,
        Object[] specificInterceptors, TargetSource targetSource) {
    
    ProxyFactory proxyFactory = new ProxyFactory();
    proxyFactory.copyFrom(this);
    
    // KEY DECISION:
    if (proxyFactory.isProxyTargetClass()) {
        // Option 1: Force CGLIB (spring.aop.proxy-target-class=true in Spring Boot)
        proxyFactory.setProxyTargetClass(true);
    } else {
        // Option 2: Evaluate if JDK proxy is possible
        evaluateProxyInterfaces(beanClass, proxyFactory);
    }
    
    Advisor[] advisors = buildAdvisors(beanName, specificInterceptors);
    proxyFactory.addAdvisors(advisors);
    proxyFactory.setTargetSource(targetSource);
    
    return proxyFactory.getProxy(getProxyClassLoader());
}

// The evaluation logic:
private void evaluateProxyInterfaces(Class<?> beanClass, ProxyFactory proxyFactory) {
    Class<?>[] targetInterfaces = ClassUtils.getAllInterfacesForClass(beanClass, 
        getProxyClassLoader());
    
    boolean hasReasonableProxyInterface = false;
    for (Class<?> ifc : targetInterfaces) {
        if (!isConfigurationCallbackInterface(ifc) &&   // Not Aware, InitializingBean, etc.
            !isInternalLanguageInterface(ifc) &&         // Not GroovyObject, etc.
            ifc.getMethods().length > 0) {               // Has methods to advise
            hasReasonableProxyInterface = true;
            break;
        }
    }
    
    if (hasReasonableProxyInterface) {
        // JDK DYNAMIC PROXY: bean has an interface → use it
        for (Class<?> ifc : targetInterfaces) {
            proxyFactory.addInterface(ifc);
        }
    } else {
        // CGLIB PROXY: bean has no interfaces → subclass the bean
        proxyFactory.setProxyTargetClass(true);
    }
}

// JDK DYNAMIC PROXY (java.lang.reflect.Proxy):
// ──────────────────────────────────────────
// Proxy.newProxyInstance(classLoader, interfaces, invocationHandler)
//
// @Service
// public class OrderService implements IOrderService { ... }
//
// Proxy created: $Proxy42 implements IOrderService {
//     @Override
//     public Order createOrder(CreateOrderRequest req) {
//         // invocationHandler.invoke(this, method, args)
//         //   → Get Advisor chain for this method
//         //   → Create MethodInvocation
//         //   → proceed() through interceptors
//         //     → TransactionInterceptor.invoke()
//         //       → target.createOrder(req)   // actual business logic
//         //       → commit/rollback
//     }
// }
//
// PRO: Standard Java, no extra dependencies, interface-based design
// CON: Requires interface, can only proxy interface methods
//      Cannot inject proxy where concrete class is expected (ClassCastException)


// CGLIB PROXY (net.sf.cglib.proxy.Enhancer):
// ──────────────────────────────────────────
// @Service  // No interface
// public class OrderService { ... }
//
// Proxy created: OrderService$$SpringCGLIB$$0 extends OrderService {
//     private final MethodInterceptor interceptor;
//     
//     @Override
//     public Order createOrder(CreateOrderRequest req) {
//         // interceptor.intercept(this, method, args, methodProxy)
//         //   → Get Advisor chain
//         //   → proceed() through interceptors
//         //     → TransactionInterceptor.invoke()
//         //       → methodProxy.invokeSuper(this, args)  // bypasses proxy, calls real method
//         //       → commit/rollback
//     }
// }
//
// PRO: Works with any class (no interface required), proxies all public methods
// CON: Requires CGLIB library, generated subclass is harder to debug,
//      Cannot proxy final classes or final methods,
//      Constructor called twice (once for target, once for proxy superclass),
//      Creates additional runtime class → Metaspace pressure


// SPRING BOOT DEFAULT: CGLIB (spring.aop.proxy-target-class=true)
// ────────────────────────────────────────────────────────────────
// Spring Boot sets proxyTargetClass=true by default because:
//   1. Most service beans don't implement interfaces
//   2. Simpler mental model: "everything is proxied the same way"
//   3. Avoids ClassCastException from injecting proxy where concrete class expected
```

### Self-Invocation Problem: Why @Transactional Fails on Self-Calls

```java
// ── THE PROBLEM ──

@Service
public class OrderService {
    
    @Transactional
    public void placeOrder(Order order) {
        // This is called through the proxy: proxy.placeOrder()
        // TransactionInterceptor opens a transaction
        orderRepository.save(order);
        // self-invocation: THIS bypasses the proxy!
        this.updateInventory(order);  // ← PROXY NOT INVOLVED
        // TransactionInterceptor commits
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateInventory(Order order) {
        // Expected: NEW transaction
        // Reality: Runs in SAME transaction as placeOrder()
        // Because: this.updateInventory() → direct method call on the TARGET object
        //          NOT on the proxy → AOP advice NOT applied
        inventoryRepository.decrement(order.getItems());
    }
}

// ── WHY IT FAILS ──
// The proxy structure:
//
// Caller → [AOP PROXY] → [TransactionInterceptor] → [ACTUAL TARGET]
//              ↑                                          │
//              │     this.updateInventory() ──────────────┘
//              │     (BYPASSES the proxy!)
//              
// When a method is called externally, Spring routes through the proxy.
// When a method calls itself (this.method()), it calls the actual
// object reference, bypassing the proxy entirely.
// 
// The proxy intercepts EXTERNAL calls only.
// Internal calls go directly to the target object, not the proxy.

// ── SOLUTIONS ──

// Solution 1: Self-injection (least preferred but works)
@Service
public class OrderService {
    @Autowired
    private OrderService self;  // Inject the PROXY into itself
    
    @Transactional
    public void placeOrder(Order order) {
        orderRepository.save(order);
        self.updateInventory(order);  // ← Calls through the PROXY
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateInventory(Order order) {
        inventoryRepository.decrement(order.getItems());
    }
}

// Solution 2: Extract to separate service (preferred)
@Service
public class InventoryService {  // ← Separate bean, gets its own proxy
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateInventory(Order order) {
        inventoryRepository.decrement(order.getItems());
    }
}

@Service
public class OrderService {
    private final InventoryService inventoryService;
    
    @Transactional
    public void placeOrder(Order order) {
        orderRepository.save(order);
        inventoryService.updateInventory(order);  // ← Goes through InventoryService's proxy
    }
}

// Solution 3: AopContext.currentProxy() (requires exposeProxy=true)
@EnableAspectJAutoProxy(exposeProxy = true)
public class AppConfig { }

@Service
public class OrderService {
    @Transactional
    public void placeOrder(Order order) {
        orderRepository.save(order);
        ((OrderService) AopContext.currentProxy()).updateInventory(order);
    }
}

// Solution 4: @Transactional on the outer method only (if propagation is not the issue)
// If updateInventory() doesn't need REQUIRES_NEW semantics, just move it inline.
```

## 4. Runtime Behavior

### The Dependency Resolution Walk

```
Scenario: @Autowired OrderService with dependencies {OrderRepository, PaymentGateway}

1. getBean("orderService") called
   └── NOT in singletonObjects cache → createBean()

2. ConstructorResolver.autowireConstructor()
   ├── Find constructor: OrderService(OrderRepository, PaymentGateway)
   ├── Resolve parameter 0 (OrderRepository):
   │   └── resolveDependency(descriptor, "orderService")
   │       └── findAutowireCandidates()
   │           └── find beans of type OrderRepository
   │               → "orderRepository" (from @Repository or @Bean)
   │           └── isAutowireCandidate("orderRepository") → yes
   │       └── getBean("orderRepository")  ← Recursive!
   │           └── (same process for orderRepository)
   │               └── instantiate, populate, initialize
   │               └── addSingleton("orderRepository", bean)
   │       └── return orderRepository bean
   │   
   ├── Resolve parameter 1 (PaymentGateway):
   │   └── resolveDependency(descriptor, "orderService")
   │       └── findAutowireCandidates()
   │           └── find beans of type PaymentGateway
   │               → "paymentGateway" (from @Service)
   │       └── getBean("paymentGateway")  ← Recursive!
   │           └── (same process)
   │       └── return paymentGateway bean
   │   
   └── Constructor.newInstance(orderRepo, paymentGateway)
       → ORDER OF BEAN CREATION: orderRepository → paymentGateway → orderService
         (dependencies created first, then dependents)

3. populateBean("orderService")
   └── No @Autowired fields (using constructor injection) → skip

4. initializeBean("orderService")
   └── @PostConstruct, InitializingBean, AOP proxy wrapping

5. addSingleton("orderService", bean)
   └── Bean ready
```

### What Happens When NoUniqueBeanDefinitionException Occurs

```
Exception: NoUniqueBeanDefinitionException: No qualifying bean of type 
  'com.example.PaymentGateway' available: expected single matching bean 
  but found 2: stripePaymentGateway, paypalPaymentGateway

This means:
1. @Autowired PaymentGateway gateway;
2. findAutowireCandidates() found TWO beans matching PaymentGateway type
3. Neither has @Primary
4. Field name "gateway" does NOT match either "stripePaymentGateway" or "paypalPaymentGateway"
5. → Fallback resolution failed → throw exception

Internal trace:
DefaultListableBeanFactory.resolveDependency()
  → doResolveDependency()
    → findAutowireCandidates() → {"stripePaymentGateway"=StripeGW@abc, "paypalPaymentGateway"=PayPalGW@def}
    → matchingBeans.size() = 2
    → determineAutowireCandidate(matchingBeans, descriptor)
      → isPrimary("stripePaymentGateway") → false
      → isPrimary("paypalPaymentGateway") → false
      → getPriority("stripePaymentGateway") → null
      → getPriority("paypalPaymentGateway") → null
      → descriptor.getDependencyName() → "gateway"
      → candidates.containsKey("gateway") → false
      → All resolution strategies exhausted → throw NoUniqueBeanDefinitionException
```

## 5. Request Flow Diagrams

### @Autowired Resolution Decision Tree

```
@Autowired PaymentGateway gateway;
│
├── 1. Is it a special type?
│   ├── Optional<T>        → wrap result in Optional
│   ├── ObjectFactory<T>   → return lazy accessor
│   ├── ObjectProvider<T>  → return lazy accessor with stream
│   ├── Provider<T>        → JSR-330 lazy inject
│   └── List<T> / Map<K,T> → find ALL matching beans
│       └── SKIP single-candidate logic, return all
│
├── 2. Is it @Value?
│   └── YES → resolve property expression → return
│
├── 3. Resolve by TYPE (findAutowireCandidates)
│   ├── Find all bean names assignable to PaymentGateway
│   ├── Filter: isAutowireCandidate(beanName)
│   │   └── Not self-referencing
│   │   └── Bean definition has autowireCandidate=true
│   │   └── Matches @Qualifier if present
│   └── Result: N candidates
│
├── 4. If N == 0:
│   ├── required=true  → throw NoSuchBeanDefinitionException
│   └── required=false → inject null
│
├── 5. If N == 1:
│   └── Return that bean (via getBean()) ✓
│
└── 6. If N > 1 (AMIBGUOUS — resolve!):
    ├── Step A: Check @Primary
    │   ├── One @Primary → return it ✓
    │   └── Multiple @Primary → throw NoUniqueBeanDefinitionException
    │
    ├── Step B: Check @Priority (javax.annotation.Priority)
    │   └── Highest priority wins ✓
    │
    └── Step C: By-name fallback
        ├── Field name "gateway" matches bean name? → return it ✓
        └── No match → throw NoUniqueBeanDefinitionException ✗
            ("expected 1 but found 2: beanA, beanB")
```

### @Configuration CGLIB Proxy Call Flow

```
public class AppConfig$$SpringCGLIB$$0 extends AppConfig {
    
    @Override
    public OrderRepository orderRepository() {
        // INTERCEPTED by BeanMethodInterceptor
        
        // 1. Before calling the real method:
        //    Check if "orderRepository" bean is already in creation
        //    (circular reference check)
        
        // 2. Resolve parameters: this.dataSource()
        //    ↓ This call is ALSO intercepted!
        
        return super.orderRepository(); // calls the real method
        
        // 3. After real method returns:
        //    Register the returned bean in BeanFactory if it's a new singleton
        //    If bean with same name exists, check compatibility
    }
    
    @Override
    public DataSource dataSource() {
        // INTERCEPTED by BeanMethodInterceptor
        
        // Check: is "dataSource" already in singletonObjects?
        Object cached = this.$$beanFactory.getSingleton("dataSource");
        if (cached != null) {
            return (DataSource) cached; // ← Returns existing singleton, no new call!
        }
        
        // Not cached → call real method
        DataSource ds = super.dataSource();
        // Register in BeanFactory
        return ds;
    }
}
```

## 6. Lifecycle Diagrams

### BeanDefinition Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│              BeanDefinition LIFECYCLE                       │
│                                                             │
│  1. REGISTRATION (refresh step 5)                           │
│     ├── @Configuration scanned → @Bean methods → BD created │
│     ├── @ComponentScan → stereotype classes → BD created    │
│     ├── Auto-configuration @Bean → BD created               │
│     └── Stored in beanDefinitionMap: "name" → BeanDefinition│
│                                                             │
│  2. MERGING (refresh step 11, before createBean)            │
│     ├── getMergedLocalBeanDefinition(beanName)              │
│     ├── If has parent definition → merge with parent        │
│     └── Apply post-processors: merge @Autowired annotations │
│                                                             │
│  3. PRE-INSTANTIATION PROCESSING (preInstantiateSingletons) │
│     ├── Freeze configuration                               │
│     ├── For each non-lazy singleton:                        │
│     │   ├── getBean(beanName)                               │
│     │   └── (triggers creation if not in cache)             │
│     └── After all: SmartInitializingSingleton callbacks     │
│                                                             │
│  4. INSTANTIATION (createBean → doCreateBean)               │
│     ├── createBeanInstance()                               │
│     ├── populateBean()                                     │
│     └── initializeBean()                                   │
│                                                             │
│  5. POST-INITIALIZATION                                     │
│     ├── BeanPostProcessor.afterInitialization               │
│     └── AOP proxy wraps target bean                         │
│                                                             │
│  6. SINGLETON CACHED (bean → singletonObjects)              │
│                                                             │
│  7. DESTRUCTION (context.close → destroyBeans)              │
│     ├── For each singleton: @PreDestroy, DisposableBean     │
│     └── BeanDefinition remains in map (not removed)         │
│         (context is closed, won't be used again)            │
└─────────────────────────────────────────────────────────────┘
```

### AOP Proxy Lifecycle in the Bean Lifecycle

```
createBean("orderService")
  │
  ├── createBeanInstance()
  │   └── Constructor called → OrderService@4f3c (target object)
  │
  ├── populateBean()
  │   └── @Autowired fields injected ON THE TARGET
  │
  ├── EARLY EXPOSURE (for circular deps):
  │   └── addSingletonFactory("orderService", 
  │         () -> getEarlyBeanReference("orderService", mbd, target)
  │              → wraps target in AOP proxy EARLY)
  │       └── This ensures other beans get the proxy, not the raw target
  │
  ├── initializeBean()
  │   ├── @PostConstruct called ON THE TARGET
  │   ├── afterPropertiesSet() ON THE TARGET
  │   └── BeanPostProcessor.postProcessAfterInitialization()
  │       └── AbstractAutoProxyCreator.wrapIfNecessary(target, beanName)
  │           ├── Check: does this bean have advisors (e.g., @Transactional)?
  │           ├── YES → Create proxy:
  │           │   ├── JDK proxy (if target implements interface)
  │           │   │   └── $Proxy42 implements IOrderService { 
  │           │   │         invocationHandler → target 
  │           │   │       }
  │           │   └── CGLIB proxy (if no interface)
  │           │       └── OrderService$$SpringCGLIB$$0 extends OrderService {
  │           │             callback → target
  │           │           }
  │           └── NO → Return target as-is
  │
  └── addSingleton("orderService", proxy)
      └── The PROXY is stored in singletonObjects, NOT the target
      └── Every getBean("orderService") returns the PROXY
      └── The target is referenced inside the proxy's handler
```

## 7. Source Code Reading Guide

### Critical Files

```
1. DefaultListableBeanFactory.java (~1500 lines)
   spring-beans/.../beans/factory/support/DefaultListableBeanFactory.java
   → findAutowireCandidates(), doResolveDependency(), resolveDependency()
   → The DI resolution engine — this IS the container

2. AutowiredAnnotationBeanPostProcessor.java
   spring-beans/.../beans/factory/annotation/AutowiredAnnotationBeanPostProcessor.java
   → postProcessProperties(), AutowiredFieldElement.inject(), AutowiredMethodElement.inject()

3. ConstructorResolver.java (~900 lines)
   spring-beans/.../beans/factory/support/ConstructorResolver.java
   → autowireConstructor() — how constructor injection works

4. DefaultSingletonBeanRegistry.java (~400 lines)
   spring-beans/.../beans/factory/support/DefaultSingletonBeanRegistry.java
   → getSingleton(), three-level cache, circular dependency logic

5. ConfigurationClassPostProcessor.java (~500 lines)
   spring-context/.../context/annotation/ConfigurationClassPostProcessor.java
   → postProcessBeanDefinitionRegistry() — enhances @Configuration with CGLIB
   → enhanceConfigurationClasses() — the actual CGLIB proxying

6. ConfigurationClassEnhancer.java
   spring-context/.../context/annotation/ConfigurationClassEnhancer.java
   → BeanMethodInterceptor — intercepts @Bean method calls on CGLIB proxies

7. ClassPathBeanDefinitionScanner.java
   spring-context/.../context/annotation/ClassPathBeanDefinitionScanner.java
   → scan(), findCandidateComponents() — classpath scanning internals

8. AbstractAutoProxyCreator.java
   spring-aop/.../aop/framework/autoproxy/AbstractAutoProxyCreator.java
   → wrapIfNecessary(), getAdvicesAndAdvisorsForBean()
   → How AOP proxies are created during postProcessAfterInitialization

9. AnnotationAwareAspectJAutoProxyCreator.java
   spring-aop/.../aop/aspectj/annotation/AnnotationAwareAspectJAutoProxyCreator.java
   → Extends AbstractAutoProxyCreator, processes @Aspect annotations

10. TransactionInterceptor.java
    spring-tx/.../transaction/interceptor/TransactionInterceptor.java
    → invoke() — how @Transactional advice actually works (AOP MethodInterceptor)

11. ApplicationContextAwareProcessor.java
    spring-context/.../context/support/ApplicationContextAwareProcessor.java
    → How ApplicationContext is injected into aware beans (NOT via @Autowired)
```

## 8. Production Failure Scenarios

### Scenario 1: @Transactional Silently Not Working

**Symptom**: Database changes are not rolled back when an exception is thrown. No errors in logs.

**Root cause**: Self-invocation (described in Section 3). The `@Transactional` method is called from another method in the same class via `this.method()`, bypassing the proxy.

**Diagnosis**:
```java
// Add this to detect proxy bypass:
@Service
public class OrderService {
    @PostConstruct
    public void checkProxy() {
        boolean isProxy = AopUtils.isAopProxy(this);
        log.info("OrderService is proxy? {}", isProxy);
        // If false → this is the RAW target, self-calls won't get advice
    }
    
    @Transactional
    public void placeOrder(Order order) {
        // Check if we're in a transaction:
        boolean inTx = TransactionSynchronizationManager.isActualTransactionActive();
        log.info("placeOrder: transaction active? {}", inTx);
        // If false → @Transactional is being bypassed
        
        this.updateInventory(order); // ← Bypass!
    }
    
    @Transactional
    public void updateInventory(Order order) {
        boolean inTx = TransactionSynchronizationManager.isActualTransactionActive();
        log.info("updateInventory: transaction active? {}", inTx);
        // If false, but placeOrder had true → self-invocation confirmed
    }
}
```

**Resolution**: Extract `updateInventory` to a separate bean, use `self` injection, or use `AopContext.currentProxy()`.

### Scenario 2: NoUniqueBeanDefinitionException After Adding a New Implementation

**Symptom**: Application was working. Added `new StripePaymentGateway implements PaymentGateway`. Now all `@Autowired PaymentGateway` injections fail with `expected 1 but found 2`.

**Root cause**: Multiple beans of same type with no `@Primary` or `@Qualifier` disambiguation.

**Diagnosis**: Use Actuator beans endpoint to enumerate all beans of the conflicting type.

**Resolution**:
```java
// Option A: Mark one as @Primary (if it's the default)
@Primary
@Service
public class StripePaymentGateway implements PaymentGateway { }

// Option B: Use @Qualifier on injection site
@Autowired @Qualifier("stripePaymentGateway")
private PaymentGateway gateway;

// Option C: Use named bean in @Bean method
@Bean("stripePaymentGateway")
public PaymentGateway stripePaymentGateway() { ... }

// Option D: Use collection injection if you need ALL implementations
@Autowired
private List<PaymentGateway> gateways;  // Injects all PaymentGateway beans
```

### Scenario 3: Slow Startup — Excessive Classpath Scanning

**Symptom**: Application takes 60+ seconds to start. Most time spent in `ClassPathBeanDefinitionScanner`.

**Root cause**: `@ComponentScan` with a broad base package (e.g., `com.myapp`) in a project with 10,000+ classes. Every class file is opened and parsed with ASM for `@Component` annotations.

**Diagnosis**: Use Flight Recorder or `BufferingApplicationStartup` to see time spent in scan.

**Resolution**:
```java
// Narrow your scan base packages:
@SpringBootApplication(scanBasePackages = {"com.myapp.core", "com.myapp.web"})
// NOT scanBasePackages = {"com"} or even {"com.myapp"}

// Or exclude heavy packages:
@ComponentScan(
    basePackages = "com.myapp",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.myapp\\.generated\\..*"
    )
)

// Or register beans explicitly:
@SpringBootApplication(scanBasePackages = {})
// + @Import({ServiceConfig.class, RepositoryConfig.class})
```

### Scenario 4: Prototype Bean Injected Into Singleton — Always Same Reference

**Symptom**: Prototype-scoped bean is always the same instance. `@Scope("prototype")` doesn't work.

**Root cause**: Prototype bean is injected into a singleton via regular `@Autowired`. The dependency is resolved ONCE (when the singleton is created) and never refreshed.

```java
@Scope("prototype")
@Component
public class ShoppingCart { ... }

@Service
public class ShoppingService {
    @Autowired
    private ShoppingCart cart;  // ← Injected ONCE, never changes!
}

// Resolution:
@Service
public class ShoppingService {
    @Autowired
    private ObjectFactory<ShoppingCart> cartFactory;  // Get fresh instance when needed
    
    public void addItem(Item item) {
        ShoppingCart cart = cartFactory.getObject();  // NEW instance each time
        cart.add(item);
    }
}
```

## 9. Debugging Techniques

### Tracing Autowiring Failures

```bash
# Enable TRACE logging for autowiring:
logging.level.org.springframework.beans.factory.support.DefaultListableBeanFactory=TRACE
logging.level.org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor=TRACE

# Or programmatically:
@EventListener(ContextRefreshedEvent.class)
public void checkAutowiring(ConfigurableApplicationContext ctx) {
    DefaultListableBeanFactory bf = 
        (DefaultListableBeanFactory) ctx.getBeanFactory();
    
    // List all beans that are NOT autowire candidates:
    for (String name : bf.getBeanDefinitionNames()) {
        BeanDefinition bd = bf.getBeanDefinition(name);
        if (!bd.isAutowireCandidate()) {
            System.out.println("NOT autowire candidate: " + name);
        }
    }
}
```

### Visualizing the Dependency Graph

```java
@Component
public class BeanDependencyVisualizer implements ApplicationListener<ContextRefreshedEvent> {
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        DefaultListableBeanFactory bf = 
            (DefaultListableBeanFactory) event.getApplicationContext()
                .getAutowireCapableBeanFactory();
        
        StringBuilder dot = new StringBuilder("digraph Beans {\n");
        
        for (String beanName : bf.getBeanDefinitionNames()) {
            dot.append("  \"").append(beanName).append("\";\n");
            
            for (String dep : bf.getDependenciesForBean(beanName)) {
                dot.append("  \"").append(beanName)
                   .append("\" -> \"").append(dep).append("\";\n");
            }
        }
        
        dot.append("}\n");
        
        try {
            Files.writeString(Paths.get("bean-graph.dot"), dot.toString());
            // Render with: dot -Tpng bean-graph.dot -o bean-graph.png
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

### Checking if a Bean is a Proxy

```java
// At any point in your code:
Object bean = context.getBean("orderService");

System.out.println("Is CGLIB proxy? " + AopUtils.isCglibProxy(bean));
System.out.println("Is JDK proxy? " + AopUtils.isJdkDynamicProxy(bean));
System.out.println("Is AOP proxy? " + AopUtils.isAopProxy(bean));

// Get the actual target behind the proxy:
if (AopUtils.isAopProxy(bean)) {
    Object target = ((Advised) bean).getTargetSource().getTarget();
    System.out.println("Target class: " + target.getClass());
    
    // List all advisors (interceptors) on this proxy:
    for (Advisor advisor : ((Advised) bean).getAdvisors()) {
        System.out.println("Advisor: " + advisor);
    }
}
```

### Finding Which BeanPostProcessors Affect a Bean

```java
@Component
public class BppInspector {
    @Autowired
    private ListableBeanFactory beanFactory;
    
    @EventListener(ContextRefreshedEvent.class)
    public void inspectPostProcessors() {
        Map<String, BeanPostProcessor> bpps = 
            beanFactory.getBeansOfType(BeanPostProcessor.class);
        
        for (Map.Entry<String, BeanPostProcessor> entry : bpps.entrySet()) {
            System.out.printf("BPP: %s → %s%n", 
                entry.getKey(), entry.getValue().getClass().getName());
        }
        
        // Expected output includes:
        // BPP: org.springframework...AutowiredAnnotationBeanPostProcessor
        // BPP: org.springframework...CommonAnnotationBeanPostProcessor
        // BPP: org.springframework...PersistenceAnnotationBeanPostProcessor
        // BPP: org.springframework...ApplicationContextAwareProcessor
        // BPP: org.springframework...AbstractAutoProxyCreator#0  (one or more)
    }
}
```

## 10. Observability Considerations

### Monitoring the IoC Container

```java
@Component
public class ContainerMetrics {
    private final ApplicationContext ctx;
    private final MeterRegistry registry;
    
    public ContainerMetrics(ApplicationContext ctx, MeterRegistry registry) {
        this.ctx = ctx;
        this.registry = registry;
        
        // Gauge: total singleton count
        Gauge.builder("spring.beans.singleton.count", ctx::getBeanDefinitionCount)
            .description("Total number of singleton beans")
            .register(registry);
        
        // Gauge: prototype bean creation rate (requires custom counter)
        Counter.builder("spring.beans.prototype.created")
            .description("Prototype beans created since startup")
            .register(registry);
    }
}
```

### Key Questions to Answer from Observability Data

```
1. How many beans are created at startup? (static count)
2. How many are created lazily at runtime? (growing count)
3. How many prototype-scoped beans are created per second? (rate)
4. What is the average time to create a bean? (histogram)
5. Which beans have the most dependents? (graph density metric)
6. Are there beans with zero dependents that are NOT controllers? (dead code)
7. Are there cycles in the dependency graph? (structural anomaly)
```

## 11. Performance Implications

### Startup Performance: The @ComponentScan Tax

```
Scanning 10,000 .class files with ASM:
  ├── File system listing:       ~20ms (SSD, 10K files)
  ├── ASM parsing per file:      ~0.1ms (class header + annotations only)
  ├── Total scan time:           ~1 second for 10K classes
  └── Additional: class loading for non-ASM-filtered items

Mitigation:
  1. Narrow scan base packages: scanBasePackages = {"com.myapp.services"}
  2. Use explicit @Configuration + @Bean instead of @ComponentScan
  3. Use @ComponentScan.Filter to exclude packages without stereotypes
  4. Spring Boot 3.2+ AOT: scan happens at BUILD TIME, not runtime
```

### Memory: The CGLIB Proxy Tax

```
CGLIB proxies consume Metaspace:
  ├── Each @Configuration class → 1 CGLIB proxy class (~5-10KB in Metaspace)
  ├── Each @Transactional service → 1 CGLIB proxy class (~5-10KB)
  ├── Each @Cacheable service → 1 CGLIB proxy class
  ├── 200 services + 30 configs = ~230 CGLIB classes = ~1.5-2.3 MB Metaspace
  └── Each proxy instance: ~200 bytes (handler references)

Optimization:
  1. Use @Configuration(proxyBeanMethods = false) for @Configuration classes
     that use parameter injection instead of method calls
  2. Use interfaces for services → enables JDK proxies (shared Proxy class)
  3. Avoid @Transactional on methods that don't need it
```

### Resolution Caching Performance

```java
// Spring caches type resolution results:

// First getBeansOfType(PaymentGateway.class):
//   → Iterate all bean definitions (O(n))
//   → Check type compatibility for each (O(1) per bean)
//   → Cache result in allBeanNamesByType (ConcurrentHashMap)

// Second getBeansOfType(PaymentGateway.class):
//   → Return from cache (O(1))
//   → Invalidated when new beans are registered

// This cache is critical for @Autowired List<PaymentGateway> injection,
// which would otherwise be O(n*m) where n=beans, m=injection points.
```

## 12. Architecture Implications

### Dependency Injection Pattern Decisions

```
┌─────────────────────────────────────────────────────────────────┐
│  DECISION: Constructor vs Field vs Setter Injection             │
├──────────────┬───────────────────┬──────────────────────────────┤
│  Constructor │ REQUIRED deps     │ Final fields, immutability,   │
│              │                   │ fail-fast, testable, explicit  │
│              │                   │ dep contract                   │
├──────────────┼───────────────────┼──────────────────────────────┤
│  Setter      │ OPTIONAL deps     │ Mutability allowed, explicit   │
│              │                   │ but not compiler-enforced      │
├──────────────┼───────────────────┼──────────────────────────────┤
│  Field       │ NEVER in prod     │ Test code only, hidden deps,  │
│              │                   │ not unit-testable, tight       │
│              │                   │ coupling to Spring             │
└──────────────┴───────────────────┴──────────────────────────────┘

Architecture Rule (enforce with ArchUnit):
  No @Autowired on non-final fields in production source packages.
  → Exceptions: test packages, @SpringBootApplication main class
```

### When NOT to Use DI

```java
// Anti-pattern: DI for everything
@Component
public class StringUtils {  // Stateless utility → no state, no dependencies
    public static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
// This should be a static utility class, not a Spring bean.
// Creating a bean for every utility class bloats the container
// and makes startup slower for no benefit.

// Anti-pattern: DI for configuration constants
@Component
public class AppConstants {
    public static final int MAX_RETRIES = 3;
    public static final String APP_NAME = "payment-api";
}
// Use @ConfigurationProperties or a constants interface instead.
```

### AOP Usage in Architecture

Every `@Transactional`, `@Cacheable`, `@Retryable`, and `@Async` adds a proxy layer. In a complex application, a single bean might be wrapped in 3-4 proxy layers:

```
Request → CacheProxy → AsyncProxy → TransactionProxy → ActualService
```

Each proxy layer adds ~0.5μs to each method call. For a service with 100K calls/second, that's ~150μs overhead per call — negligible. The real cost is in debugging complexity and the self-invocation trap. Staff engineers balance AOP usage against the cognitive load it imposes on the team.

## 13. Team Ownership Implications

### Who Owns the DI Configuration?

| Configuration Type | Ownership |
|--------------------|-----------|
| Component scan base packages | Platform team (standardized across services) |
| `@Primary` annotation | Team owning the DEFAULT implementation |
| `@Qualifier` naming convention | Platform team (define naming standard) |
| `@Profile("production")` | DevOps/platform team |
| `@ConditionalOnProperty` flags | Feature team (they own the feature flag) |
| Custom `@Conditional` annotations | Platform team (shared infrastructure) |
| `@ConfigurationProperties` classes | Feature team (they own the config schema) |

### Anti-Pattern: "Inject Everything" Culture

Teams that inject every class as a Spring bean face:
1. **Slow startup**: 500+ beans, many of which are stateless utilities that could be static
2. **Debugging difficulty**: No way to know which beans are actually wired (adds cognitive load)
3. **Circular dependency risk**: More beans = higher chance of accidental cycles
4. **Test slowdown**: `@SpringBootTest` must create the entire context for a single service test

**Guideline**: A class should be a Spring bean only if it:
- Has mutable state (dependencies, configurations)
- Needs lifecycle management (`@PostConstruct`, `@PreDestroy`)
- Requires AOP advice (`@Transactional`, `@Cacheable`)
- Is a pluggable strategy (interface + multiple implementations)

## 14. Interview Questions

### Question 1: "Explain the difference between @Autowired, @Inject (JSR-330), and @Resource (JSR-250). How does Spring resolve each? When would you choose one over another?"

**Staff-level answer**: `@Autowired` is Spring's own annotation. It's resolved by `AutowiredAnnotationBeanPostProcessor` and supports `required=false`, injection into collections, `Map<String, T>`, `Optional<T>`, and `ObjectProvider<T>`. Resolution is by-type first, with `@Qualifier` for disambiguation, and by-name as a fallback when multiple candidates exist.

`@Inject` is the JSR-330 (javax.inject/jakarta.inject) standard. Spring supports it via a separate `CommonAnnotationBeanPostProcessor` path. The key differences: `@Inject` has no `required` attribute (use `Optional<T>` instead), and it uses `@Named` instead of `@Qualifier` for disambiguation. Under the hood, Spring registers `AutowiredAnnotationBeanPostProcessor` with both `@Autowired` and `@Value` annotations, and delegates `@Inject` to a JSR-330 factory adapter that ultimately calls the same `resolveDependency()` method. The resolution logic is identical — only the annotation discovery differs.

`@Resource` is JSR-250. It follows a completely different resolution strategy: **by-name first, then by-type**. If you write `@Resource(name = "myDataSource")`, Spring looks for a bean literally named `myDataSource`. If no name is specified, it uses the field name as the bean name. Only if the by-name lookup fails does it fall back to by-type resolution. This is the opposite of `@Autowired`'s by-type-first approach.

**When to use each**: Use `@Autowired` for new Spring Boot applications — it's the most feature-rich and best supported. Use `@Inject` only if you're writing a library that must work across multiple DI frameworks (Guice, CDI, Dagger). Use `@Resource` when you specifically want by-name semantics — for example, when you have multiple `DataSource` beans and you know exactly which one you want by its bean name. Avoid mixing `@Autowired` and `@Resource` in the same class; the resolution order is undefined when both are present, and your code becomes harder to reason about.

The Staff Engineer take: The annotation choice is less important than establishing a consistent convention across your codebase. The real engineering decision is whether a dependency should be constructor-injected (required, immutable) or setter-injected (optional, mutable). The annotation is just the delivery mechanism.

### Question 2: "You have a bean that is sometimes not created because the profile isn't active. But you can't change the profile management. How would you make a bean that conditionally creates itself based on both profile AND a runtime property, where one takes precedence over the other?"

**Staff-level answer**: This is a case for a custom `@Conditional` annotation and a bean defined with an `ObjectProvider` or a programmatic registration in a `BeanDefinitionRegistryPostProcessor`.

The cleanest approach for a reusable pattern: Define a custom `@Conditional` annotation that combines multiple conditions with an OR/logical-precedence semantic:

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(CompositeCondition.class)
public @interface ConditionalOnProfileOrProperty {
    String profile();           // If this profile is active, match
    String property();          // Property name
    String havingValue() default "true";
    boolean matchIfMissing() default false;
}

class CompositeCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext ctx, 
            AnnotatedTypeMetadata metadata) {
        String profile = (String) metadata.getAnnotationAttributes(
            ConditionalOnProfileOrProperty.class.getName()).get("profile");
        
        // First precedence: profile match
        if (ctx.getEnvironment().acceptsProfiles(Profiles.of(profile))) {
            return ConditionOutcome.match("Profile '" + profile + "' is active");
        }
        
        // Second precedence: property match
        String prop = (String) metadata.getAnnotationAttributes(
            ConditionalOnProfileOrProperty.class.getName()).get("property");
        String value = ctx.getEnvironment().getProperty(prop);
        if (value != null && value.equals("true")) {
            return ConditionOutcome.match("Property '" + prop + "' is true");
        }
        
        return ConditionOutcome.noMatch(
            "Neither profile '" + profile + "' active nor property '" + prop + "' set");
    }
}
```

For a one-off scenario, `@ConditionalOnExpression` with SpEL is simpler:
```java
@Bean
@ConditionalOnExpression("'${active.profiles}'.contains('production') or ${myapp.feature.enabled:false}")
public MyBean myBean() { ... }
```

But `@ConditionalOnExpression` has a pitfall: SpEL expressions are evaluated late in the parsing phase, so if the expression depends on beans that haven't been created yet, it fails silently. Custom `Condition` implementations have full access to the `ConditionContext` and `Environment` at evaluation time, making them more flexible and debuggable.

The Staff Engineer also considers: If the condition is truly dynamic (based on runtime state that changes after startup), a `@Conditional` won't work — conditions are evaluated once at startup. In that case, use an `ObjectProvider<MyBean>` and resolve at call time, or register the bean programmatically in response to application events.

### Question 3: "Explain how CGLIB proxying of @Configuration classes works. What happens if you call a @Bean method directly instead of going through the proxy? Why does Spring recommend `proxyBeanMethods = false` since Spring Boot 3.x?"

**Staff-level answer**: `ConfigurationClassPostProcessor` detects `@Configuration` classes and enhances them with CGLIB. The enhancement creates a subclass (`AppConfig$$SpringCGLIB$$0 extends AppConfig`) that intercepts every `@Bean` method call. When method `orderRepository()` calls `dataSource()` internally, the CGLIB interceptor checks the `BeanFactory` singleton cache. If a `DataSource` singleton already exists, it returns the cached instance. If not, it calls the real `super.dataSource()` — but crucially, it does NOT register the result as a Spring bean (that's the container's job when the `@Bean` method is invoked by the framework directly). The interceptor only intercepts internal chained calls, not the initial framework invocation.

If you call a `@Bean` method directly (without the proxy, which happens with `proxyBeanMethods = false` or when the class is not CGLIB-enhanced), each call creates a NEW instance. Three calls to `dataSource()` from three different `@Bean` methods produce three different `HikariDataSource` instances, only one of which is the Spring-managed singleton. The other two are orphan objects — they exist on the heap but are not managed by the container and will never be closed. This is a resource leak and a correctness bug.

Spring recommends `proxyBeanMethods = false` since Boot 3.x for performance. The CGLIB proxy adds: (1) Metaspace consumption for the generated class, (2) startup time for CGLIB bytecode generation, (3) complexity (the proxy makes `this` refer to the proxy, breaking `instanceof` checks and `getClass()` calls), and (4) restrictions (the class and `@Bean` methods must be non-final, non-private).

The modern alternative is **Lite mode** (`proxyBeanMethods = false`): instead of calling `@Bean` methods directly, you inject dependencies as parameters. The Spring container resolves each parameter from its singleton cache automatically. This is more explicit, faster, and doesn't require CGLIB. The only case where `proxyBeanMethods = true` is needed is when you CANNOT express the dependency via parameter injection — for example, when a `@Bean` method conditionally calls another `@Bean` method based on runtime state, or in deeply nested configuration hierarchies where parameter passing becomes unmanageable.

**Rule of thumb for Staff Engineers**: Default to `proxyBeanMethods = false`. Use parameter injection. Reserve `proxyBeanMethods = true` for cases where it's demonstrably necessary, and document WHY in a comment. The performance and clarity benefits of lite mode make it the right default.

## 15. Hands-On Exercises

1. **Build a custom `@Autowired` replacement using Java reflection**: Create a `MyInject` annotation and a `BeanPostProcessor` that finds fields annotated with `@MyInject`, resolves the bean by type from the `ApplicationContext`, and injects it via reflection. Compare the code to `AutowiredAnnotationBeanPostProcessor`.

2. **Create a multi-module project with intentional dependency conflicts**: Module A defines `@Service public class PaymentGateway`. Module B defines `@Service @Primary public class StripeGateway implements PaymentGateway`. Module C defines `@Service public class PayPalGateway implements PaymentGateway`. In the main app, `@Autowired PaymentGateway` should pick StripeGateway. Verify with assertions. Then remove `@Primary` and add `@Qualifier` to specific injection points.

3. **Trace constructor injection resolution**: Set a breakpoint in `ConstructorResolver.autowireConstructor()`. Create a bean with 5 `@Autowired` constructor parameters. Step through the resolution of each parameter. Observe how each `resolveDependency()` call triggers a `getBean()` call, which may recursively trigger more `getBean()` calls.

4. **Reproduce the self-invocation problem**: Write a service with `@Transactional` on two methods where one calls the other. Verify with `TransactionSynchronizationManager.isActualTransactionActive()` that the inner method is NOT in a new transaction. Then fix it with self-injection. Then refactor to a separate service and verify it works correctly.

5. **Build a bean dependency graph for a real Spring Boot app**: Use `DefaultListableBeanFactory.getDependenciesForBean()` and `getDependentBeans()` to build adjacency lists. Export as DOT format. Render with Graphviz. Identify: (a) beans with no incoming edges that aren't controllers — dead code candidates, (b) beans with >20 incoming edges — "god" beans that couple too many components, (c) cycles in the graph.

## 16. Advanced Challenges

1. **Implement a custom `BeanDefinitionRegistryPostProcessor` that generates Repository beans from interfaces at runtime**: Instead of relying on Spring Data's repository scanning, read a YAML config that maps interface names to SQL template files. For each entry, use CGLIB or JDK `Proxy` to create runtime implementations of the interface, and register them as `BeanDefinition` instances. Verify that `@Autowired MyRepository repo` works without any `@Repository` annotation.

2. **Build an "AOP Stack Visualizer"**: Write a tool that inspects any `@Service` bean and prints the full AOP proxy stack. For example: `CGLIB$Proxy → AsyncExecutionInterceptor → CacheInterceptor → TransactionInterceptor → OrderService(impl)`. For each layer, show: which annotation triggered it, the interceptor class, and the order in the advice chain. Use `Advised.getAdvisors()` to introspect the proxy. Render the stack as an ASCII or HTML diagram.

3. **Create a "Dependency Injection Linter" using ArchUnit or a custom annotation processor**: Write rules that:
   - Disallow `@Autowired` on fields in non-test code
   - Require `@Primary` on exactly one bean when multiple implementations of an interface exist
   - Require `@Qualifier` on every `@Autowired` field whose type has multiple implementations of the same interface
   - Flag beans injected into 20+ other beans as "potential architectural bottlenecks"
   - Identify `@Lazy` usage on constructor injections (design smell — why is a required dependency lazy?)
   - Verify that every `@Configuration(proxyBeanMethods = true)` class uses parameter injection (if all `@Bean` methods use parameters, the CGLIB proxy is unnecessary)

4. **Build a "DI Performance Benchmark"**: Compare startup time and memory for three bean-wiring strategies: (a) All beans via `@ComponentScan` + `@Autowired`, (b) All beans via explicit `@Configuration` + `@Bean` with parameter injection, (c) All beans via programmatic `BeanDefinition` registration in a `BeanDefinitionRegistryPostProcessor`. Use JMH for accurate measurement. Account for Metaspace, heap, and class loading overhead. Write a report with recommendations for projects at different scales.

5. **Implement a "Scoped Proxy for Multi-Tenancy"**: Build a custom `Scope` implementation that resolves beans differently based on a tenant context (e.g., a `TenantContextHolder` ThreadLocal). The scope should:
   - Create tenant-specific bean instances lazily
   - Cache per-tenant instances
   - Evict per-tenant instances when a tenant is deprovisioned
   - Work correctly with `@Autowired` in both singleton and tenant-scoped beans
   - Handle the case where a singleton depends on a tenant-scoped bean (using `ScopedProxyMode.TARGET_CLASS`)
   - Include a `@PreDestroy` mechanism that notifies tenant-scoped beans when the tenant context is destroyed
