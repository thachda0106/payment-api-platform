# Session 14: Application Context & Bean Lifecycle

## 1. Why This Topic Exists

Every object in a Spring application is a bean. Every bean has a lifecycle. The `ApplicationContext` orchestrates that lifecycle across thousands of beans. When `BeanCurrentlyInCreationException` appears in production logs, or when `@PostConstruct` fires twice, or when `@Autowired` injects a stale reference — the root cause is always an incomplete understanding of the bean lifecycle.

The `ApplicationContext` is not a simple map. It is a hierarchical, event-driven container with 12 distinct refresh phases, 20+ post-processor hooks, and a carefully choreographed dance between `BeanFactoryPostProcessor` (which modifies bean definitions) and `BeanPostProcessor` (which modifies bean instances).

**Staff engineer insight**: The difference between a Senior engineer who can use Spring and a Staff engineer who can debug it is knowing the `refresh()` sequence. When you understand the 12 steps, you can explain any startup failure, any bean wiring issue, and any lifecycle anomaly.

## 2. Mental Model

```
ApplicationContext IS:
  ┌─────────────────────────────────────────────────────┐
  │ A registry of BeanDefinitions (what to create)      │
  │ A factory of Beans (created instances)              │
  │ A container of lifecycle hooks (post-processors)     │
  │ An event publisher (ApplicationEvents)              │
  │ A resource loader (classpath, file, URL)            │
  │ A message source (i18n)                             │
  │ A hierarchical parent-child context tree             │
  └─────────────────────────────────────────────────────┘

ApplicationContext is NOT:
  ├── A simple HashMap<String, Object>
  ├── Thread-safe by default (bean creation is NOT thread-safe)
  └── A graph database (it does not resolve circular deps by algorithm alone)
```

The container's core data structure:

```
BeanDefinitionRegistry (Map<String, BeanDefinition>)
  │
  ├── beanName → BeanDefinition
  │   
  │   BeanDefinition {
  │       Class<?> beanClass;
  │       String scope;                    // singleton, prototype
  │       boolean lazyInit;
  │       String[] dependsOn;
  │       boolean primary;
  │       ConstructorArgumentValues;
  │       MutablePropertyValues;
  │       String initMethodName;
  │       String destroyMethodName;
  │       // ... many more fields
  │   }
  │
  └── SingletonObjects (Map<String, Object>)
      └── beanName → fully-initialized singleton instance
      
The lifecycle traverses from BeanDefinition (left) to SingletonObjects (right)
through createBean() → populateBean() → initializeBean()
```

## 3. Internal Architecture

### ApplicationContext Hierarchy

```
BeanFactory (interface)
  ├── getBean(String)
  ├── containsBean(String)
  ├── isSingleton(String)
  ├── getType(String)
  └── getAliases(String)
      │
      ▼
HierarchicalBeanFactory (interface)
  ├── getParentBeanFactory()
  └── containsLocalBean(String)         // checks only THIS factory
      │
      ▼
ListableBeanFactory (interface)
  ├── getBeanDefinitionNames()
  ├── getBeansOfType(Class)
  └── findAnnotationOnBean(String, Class)
      │
      ▼
ApplicationContext (interface)
  ├── extends all above interfaces
  ├── getEnvironment()
  ├── publishEvent(Object)
  └── getApplicationName()
      │
      ▼
ConfigurableApplicationContext (interface)
  ├── refresh()
  ├── close()
  ├── addBeanFactoryPostProcessor()
  └── addApplicationListener()
      │
      ▼
AbstractApplicationContext (abstract class)
  ├── implements refresh() — THE 12-STEP SEQUENCE
  ├── template method pattern
  └── Most Spring Boot context types extend this
      │
      ▼
AbstractRefreshableApplicationContext
  └── Allows multiple refresh() calls (not common in Spring Boot)
      │
      ▼
GenericApplicationContext
  └── Single refresh(), registers bean definitions directly
      │
      ▼
AnnotationConfigApplicationContext          (standalone, no web)
      │
      ▼
GenericWebApplicationContext               (web-aware)
      │
      ▼
AnnotationConfigServletWebServerApplicationContext   (Spring Boot Servlet)
      │
      ▼
AnnotationConfigReactiveWebServerApplicationContext  (Spring Boot Reactive)
```

The key implementation class: `DefaultListableBeanFactory`. Despite its name suggesting "just a BeanFactory", it is the de facto implementation for everything — it implements `BeanDefinitionRegistry`, `ListableBeanFactory`, `ConfigurableBeanFactory`, and manages the singleton and bean definition caches. Every `ApplicationContext` holds a `DefaultListableBeanFactory` internally.

### The 12-Step refresh() Sequence (AbstractApplicationContext)

```java
// Source: org.springframework.context.support.AbstractApplicationContext.java
// This is THE most important method in the entire Spring Framework. Memorize it.

@Override
public void refresh() throws BeansException, IllegalStateException {
    synchronized (this.startupShutdownMonitor) {
        StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");

        // ── STEP 1: Prepare this context for refreshing ──
        prepareRefresh();
        //   ├── Set closed=false, active=true
        //   ├── Initialize PropertySources in the environment
        //   └── Validate required properties (if any marked as required)

        // ── STEP 2: Tell the subclass to refresh the internal bean factory ──
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();
        //   ├── If existing bean factory: destroy beans, close it
        //   ├── Create new DefaultListableBeanFactory
        //   └── Load bean definitions (in XML-based apps, not annotation-based)
        //   In GenericApplicationContext: this is a no-op; bean factory was created in ctor

        // ── STEP 3: Prepare the bean factory for use in this context ──
        prepareBeanFactory(beanFactory);
        //   ├── Set ClassLoader (context class loader)
        //   ├── Set ExpressionLanguage parser (SpEL)
        //   ├── Add PropertyEditorRegistrar (for converting text → typed values)
        //   ├── Add ApplicationContextAwareProcessor (injects ApplicationContext)
        //   ├── Ignore dependency interfaces (ResourceLoaderAware, ApplicationEventPublisherAware,
        //   │   etc. — these are injected by the container, not by @Autowired resolution)
        //   └── Register default environment beans (environment, systemProperties, systemEnvironment)

        // ── STEP 4: Post-process the bean factory (before bean definitions) ──
        postProcessBeanFactory(beanFactory);
        //   ├── Called AFTER standard initialization, BEFORE bean post-processors
        //   ├── Template method: subclasses override to register special beans
        //   └── In ServletWebServerApplicationContext:
        //       → Registers WebApplicationContextServletContextAwareProcessor

        // ── STEP 5: Invoke BeanFactoryPostProcessors ──
        invokeBeanFactoryPostProcessors(beanFactory);
        //   ├── BeanFactoryPostProcessor: modifies bean DEFINITIONS before beans are created
        //   ├── BeanDefinitionRegistryPostProcessor: can register NEW bean definitions
        //   ├── Execution order:
        //   │   a. BeanDefinitionRegistryPostProcessors that implement PriorityOrdered
        //   │   b. BeanDefinitionRegistryPostProcessors that implement Ordered
        //   │   c. All remaining BeanDefinitionRegistryPostProcessors
        //   │   d. BeanFactoryPostProcessors that implement PriorityOrdered
        //   │   e. BeanFactoryPostProcessors that implement Ordered
        //   │   f. All remaining BeanFactoryPostProcessors
        //   └── Key actor: ConfigurationClassPostProcessor (a BeanDefinitionRegistryPostProcessor)
        //       → Parses @Configuration, @ComponentScan, @Import, @Bean
        //       → AutoConfigurationImportSelector fires here (as a DeferredImportSelector)

        // ── STEP 6: Register BeanPostProcessors ──
        registerBeanPostProcessors(beanFactory);
        //   ├── BeanPostProcessor: intercepts bean INSTANTIATION (before/after init)
        //   ├── Registered but NOT yet applied (beans aren't created yet)
        //   ├── Includes: AutowiredAnnotationBeanPostProcessor (@Autowired)
        //   │             CommonAnnotationBeanPostProcessor (@PostConstruct, @PreDestroy)
        //   │             PersistenceAnnotationBeanPostProcessor (@PersistenceContext)
        //   │             RequiredAnnotationBeanPostProcessor (deprecated, @Required)
        //   └── Ordered by PriorityOrdered → Ordered → un-ordered

        // ── STEP 7: Initialize message source ──
        initMessageSource();
        //   └── Creates MessageSource bean if not defined by user (i18n support)

        // ── STEP 8: Initialize event multicaster ──
        initApplicationEventMulticaster();
        //   └── Creates SimpleApplicationEventMulticaster (synchronous by default)
        //       → Events are published to listeners on the calling thread

        // ── STEP 9: Initialize other special beans (template method) ──
        onRefresh();
        //   ├── Subclass hook
        //   ├── ServletWebServerApplicationContext: creates and starts embedded web server
        //   │   → new Tomcat() → configure engine, host, context → tomcat.start()
        //   └── ReactiveWebServerApplicationContext: creates and starts reactive web server

        // ── STEP 10: Register listeners ──
        registerListeners();
        //   ├── Register ApplicationListener beans with the multicaster
        //   └── Publish early application events (queued during refresh)

        // ── STEP 11: Instantiate all remaining (non-lazy-init) singletons ──
        finishBeanFactoryInitialization(beanFactory);
        //   ├── Freeze configuration (no more BeanDefinition changes allowed)
        //   └── beanFactory.preInstantiateSingletons()
        //       ├── For each singleton bean (non-lazy, non-abstract):
        //       │   ├── getBean(beanName)
        //       │   │   ├── If bean exists in singleton cache → return
        //       │   │   ├── If bean is being created → circular dependency check
        //       │   │   ├── getBeanDefinition(beanName)
        //       │   │   ├── resolve dependencies (dependsOn, constructor args)
        //       │   │   ├── createBean(beanName, mbd, args)
        //       │   │   │   ├── resolveBeforeInstantiation() → InstantiationAwareBeanPostProcessor
        //       │   │   │   │   If any BPP returns non-null, skip constructor (proxy shortcut)
        //       │   │   │   ├── doCreateBean(beanName, mbd, args)
        //       │   │   │   │   ├── createBeanInstance() → instantiate via constructor
        //       │   │   │   │   ├── applyMergedBeanDefinitionPostProcessors()
        //       │   │   │   │   ├── populateBean() → inject @Autowired fields/setters
        //       │   │   │   │   ├── initializeBean()
        //       │   │   │   │   │   ├── invokeAwareMethods() (BeanNameAware, BeanFactoryAware, etc.)
        //       │   │   │   │   │   ├── applyBeanPostProcessorsBeforeInitialization()
        //       │   │   │   │   │   │   → @PostConstruct calls happen here
        //       │   │   │   │   │   ├── invokeInitMethods()
        //       │   │   │   │   │   │   → InitializingBean.afterPropertiesSet()
        //       │   │   │   │   │   │   → custom init-method
        //       │   │   │   │   │   └── applyBeanPostProcessorsAfterInitialization()
        //       │   │   │   │   │       → AOP proxy creation happens here
        //       │   │   │   │   └── registerDisposableBeanIfNecessary()
        //       │   │   │   └── addSingleton(beanName, singletonObject)
        //       │   │   └── Return fully-initialized bean
        //       │   └── After all singletons: SmartInitializingSingleton.afterSingletonsInstantiated()
        //       └── Bean is now in singletonObjects map, ready for use

        // ── STEP 12: Finish refresh ──
        finishRefresh();
        //   ├── Clear resource caches
        //   ├── Initialize LifecycleProcessor for this context
        //   │   → DefaultLifecycleProcessor: calls start() on SmartLifecycle beans
        //   ├── Publish ContextRefreshedEvent
        //   └── Register with LiveBeansView MBean (if spring.liveBeansView.mbeanDomain set)
    }
}
```

### The 12 Steps Visualized

```
┌───────────────────────────────────────────────────────────────┐
│                    refresh() 12-STEP ORCHESTRATION             │
├───────┬───────────────────────────────────────────────────────┤
│ STEP  │  ACTION                    │  PRIMARY ACTORS           │
├───────┼────────────────────────────┼───────────────────────────┤
│  01   │ prepareRefresh()           │ PropertySources,          │
│       │                            │ required props validation │
│  02   │ obtainFreshBeanFactory()   │ DefaultListableBeanFactory│
│  03   │ prepareBeanFactory()       │ ClassLoader, SpEL,        │
│       │                            │ ApplicationContextAware   │
│  04   │ postProcessBeanFactory()   │ Web server context hooks  │
│  05   │ invokeBeanFactoryP.P.()    │ ConfigurationClassP.P.,   │
│       │                            │ AutoConfigImportSelector  │
│  06   │ registerBeanPostProcessors │ AutowiredAnnotationBPP,   │
│       │                            │ CommonAnnotationBPP       │
│  07   │ initMessageSource()        │ i18n message resolution   │
│  08   │ initAppEventMulticaster()  │ Event publication infra   │
│  09   │ onRefresh()                │ Embedded web server start │
│  10   │ registerListeners()        │ ApplicationListener beans │
│  11   │ finishBeanFactoryInit()    │ ★ ALL SINGLETON BEANS ★   │
│       │                            │ (createBean → populate →  │
│       │                            │  initialize → proxy)      │
│  12   │ finishRefresh()            │ LifecycleProcessor,       │
│       │                            │ ContextRefreshedEvent     │
└───────┴────────────────────────────┴───────────────────────────┘
```

**Critical ordering insight**: Steps 1-4 are pre-work (environment, factory setup). Step 5 is where bean DEFINITIONS are processed — this is the ConfigurationClassPostProcessor phase. Step 6 registers the post-processors that will intercept bean creation. Steps 7-10 set up infrastructure. Step 11 is where beans are actually created. The fact that BeanPostProcessors are registered in step 6 BEFORE beans are created in step 11 is why dependency injection and AOP work — the processors are already in place when bean instantiation begins.

This also means: a `BeanFactoryPostProcessor` defined as a `@Bean` method in a `@Configuration` class will NOT be processed as a BFPP until that `@Configuration` class itself is processed in step 5. To have a BFPP fire earlier, it must be registered programmatically (e.g., in a `SpringApplication` initializer), not as a `@Bean`.

## 4. Runtime Behavior

### Bean Lifecycle: The Full Sequence

```
                     ┌──────────────────────────────────┐
                     │  1. BeanDefinition REGISTERED     │
                     │  (ConfigurationClassPostProcessor  │
                     │   or XML or programmatic)          │
                     └───────────────┬──────────────────┘
                                     │
                                     ▼
                     ┌──────────────────────────────────┐
                     │  2. Class LOADED                  │
                     │  (if not already loaded)           │
                     └───────────────┬──────────────────┘
                                     │
                                     ▼
                     ┌──────────────────────────────────┐
                     │  3. BeanFactoryPostProcessors      │
                     │  can MODIFY BeanDefinition here    │
                     │  (e.g., PropertySourcesPlaceholder │
                     │   Configurer resolves ${...})      │
                     └───────────────┬──────────────────┘
                                     │
                                     ▼
              ┌──────────────────────────────────────────┐
              │  4. INSTANTIATION (createBeanInstance)    │
              │  ┌────────────────────────────────────┐ │
              │  │ a. Constructor resolved             │ │
              │  │ b. Constructor.invoke(args)         │ │
              │  │ c. Object exists (unpopulated)      │ │
              │  └────────────────────────────────────┘ │
              └───────────────┬──────────────────────────┘
                              │
                              ▼
              ┌──────────────────────────────────────────┐
              │  5. PROPERTY POPULATION (populateBean)    │
              │  ┌────────────────────────────────────┐ │
              │  │ a. InstantiationAwareBeanPostProcessors│
              │  │    .postProcessAfterInstantiation() │ │
              │  │ b. @Autowired fields injected       │ │
              │  │ c. @Autowired setters invoked       │ │
              │  │ d. @Value fields resolved           │ │
              │  │    (AutowiredAnnotationBeanPostProcessor)│
              │  └────────────────────────────────────┘ │
              └───────────────┬──────────────────────────┘
                              │
                              ▼
              ┌──────────────────────────────────────────┐
              │  6. INITIALIZATION (initializeBean)       │
              │  ┌────────────────────────────────────┐ │
              │  │ a. invokeAwareMethods()              │ │
              │  │    BeanNameAware.setBeanName()       │ │
              │  │    BeanClassLoaderAware              │ │
              │  │    BeanFactoryAware                  │ │
              │  │    ApplicationContextAware            │ │
              │  │    EnvironmentAware                   │ │
              │  │    ResourceLoaderAware                │ │
              │  │    MessageSourceAware                │ │
              │  │    ApplicationEventPublisherAware     │ │
              │  │                                      │ │
              │  │ b. BeanPostProcessors BEFORE init    │ │
              │  │    ├── @PostConstruct method called   │ │
              │  │    │   (CommonAnnotationBeanPostProcessor)│
              │  │    └── Other custom BPPs              │ │
              │  │                                      │ │
              │  │ c. InitializingBean.afterPropertiesSet()│
              │  │                                      │ │
              │  │ d. Custom init-method                 │ │
              │  │    (specified in @Bean(initMethod=))  │ │
              │  │                                      │ │
              │  │ e. BeanPostProcessors AFTER init     │ │
              │  │    ├── AOP proxy creation             │ │
              │  │    │   (AbstractAutoProxyCreator)     │ │
              │  │    │   → JDK dynamic proxy OR          │ │
              │  │    │   → CGLIB subclass proxy         │ │
              │  │    └── Other wrapping BPPs            │ │
              │  └────────────────────────────────────┘ │
              └───────────────┬──────────────────────────┘
                              │
                              ▼
              ┌──────────────────────────────────────────┐
              │  7. BEAN READY                            │
              │  ├── Stored in singletonObjects cache      │
              │  └── Available for getBean() / injection   │
              └───────────────┬──────────────────────────┘
                              │
                    (application runs...)
                              │
                              ▼
              ┌──────────────────────────────────────────┐
              │  8. DESTRUCTION (on context close)         │
              │  ├── @PreDestroy method called              │
              │  ├── DisposableBean.destroy()              │
              │  ├── Custom destroy-method                  │
              │  └── Bean removed from singletonObjects     │
              └──────────────────────────────────────────┘
```

### How @Autowired Works (The InjectableResolution)

```java
// The chain:
AutowiredAnnotationBeanPostProcessor
  └── AutowiredFieldElement.inject()           // For field injection
  └── AutowiredMethodElement.inject()          // For setter injection

// For field @Autowired:
protected void inject(Object bean, String beanName, PropertyValues pvs) {
    Field field = (Field) this.member;
    Object value;
    DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
    
    // 1. Resolve the dependency
    value = beanFactory.resolveDependency(desc, beanName);
    //   ├── Check @Qualifier annotations
    //   ├── Check @Primary beans
    //   ├── By-type matching → If multiple candidates, by-name fallback
    //   ├── For collections: find ALL beans of type
    //   ├── For Optional: wrap result in Optional
    //   └── For Map: key=beanName, value=bean
    
    // 2. Inject via reflection
    if (value != null) {
        ReflectionUtils.makeAccessible(field);
        field.set(bean, value);
    }
}
```

### How @PostConstruct Works

```java
// CommonAnnotationBeanPostProcessor extends InitDestroyAnnotationBeanPostProcessor
// In the constructor:
public CommonAnnotationBeanPostProcessor() {
    setInitAnnotationType(PostConstruct.class);    // JSR-250 / Jakarta
    setDestroyAnnotationType(PreDestroy.class);
}

// During initializeBean():
@Override
public Object postProcessBeforeInitialization(Object bean, String beanName) {
    LifecycleMetadata metadata = findLifecycleMetadata(bean.getClass());
    metadata.invokeInitMethods(bean, beanName);
    //   → ReflectionUtils.invokeMethod(PostConstruct-annotated method, bean)
    return bean;
}
```

### Bean Scopes and Their Implementations

```java
// ── SINGLETON (default) ──
// Storage: DefaultSingletonBeanRegistry.singletonObjects (ConcurrentHashMap)
// Lifecycle: Created at refresh() step 11, destroyed at context.close()
// Thread safety: THE BEAN MUST BE THREAD-SAFE
// Implementation: single instance in map, always returns cached instance
Scope: singleton
Storage: Map<String, Object> singletonObjects  (ConcurrentHashMap, 256 initial capacity)
Access: singletonObjects.get(beanName)
        // If exists → return immediately
        // If not → createBean() → addSingleton()
        // If currently being created → circular dependency detection

// ── PROTOTYPE ──
// Storage: NONE (no cache — each request creates a new bean)
// Lifecycle: Created on every getBean(), NOT destroyed by container (YOU must clean up)
// Thread safety: Each thread gets its own instance
// Implementation: createBean() every time, no caching, no destruction callback
Scope: prototype
Storage: No cache (created fresh each time)
Access: getBean() → createBean() → return (no addSingleton call)
WARNING: The container does NOT manage destruction for prototype beans.
         @PreDestroy is NEVER called on prototype beans.
         You must implement your own cleanup mechanism.

// ── REQUEST (web-aware only) ──
// Storage: RequestAttributes (attributes of HTTP request via RequestContextHolder)
// Lifecycle: Created on first access in request, destroyed at request end
// Implementation: RequestScope extends AbstractRequestAttributesScope
Scope: request
Storage: RequestContextHolder.currentRequestAttributes().getAttribute(beanName, SCOPE_REQUEST)
         // ThreadLocal-based (accesses the current HTTP request attributes)
Access: get() → if null → createBean() → scope.get(beanName, ObjectFactory) 
        → setAttribute(name, bean, SCOPE_REQUEST) 
        → RequestContextListener or RequestContextFilter must be registered
Destruction: ServletRequestListener.requestDestroyed() → scope.getConversationId() 
            → destroyBean()

// ── SESSION (web-aware only) ──
// Storage: HttpSession
// Lifecycle: Created on first access in session, destroyed at session expiration
// Implementation: SessionScope, similar to RequestScope but scoped to HttpSession
Scope: session
Storage: HttpSession attributes
Access: RequestContextHolder.currentRequestAttributes()
        .getAttribute(beanName, SCOPE_SESSION)
        → If null → createBean() → session.setAttribute(name, bean)
Requirements: The bean proxy must implement Serializable if session replication is used.
              ScopedProxyMode.TARGET_CLASS is typical (CGLIB proxy).

// ── APPLICATION (web-aware) ──
// Storage: ServletContext
// Lifecycle: Created once per ServletContext (shares context with web application)
// Uncommon: Used for data that should live as long as the servlet container
```

### The Default Singleton Cache (ConcurrentHashMap)

```java
// Inside DefaultSingletonBeanRegistry:
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry 
        implements SingletonBeanRegistry {

    // Primary cache: fully initialized singletons
    private final Map<String, Object> singletonObjects = 
        new ConcurrentHashMap<>(256);

    // Secondary cache: early references (for circular dependency resolution)
    private final Map<String, Object> earlySingletonObjects = 
        new ConcurrentHashMap<>(16);

    // Tertiary cache: singleton factories (ObjectFactory for early exposure)
    private final Map<String, ObjectFactory<?>> singletonFactories = 
        new HashMap<>(16);

    // Beans currently being created (used for circular detection)
    private final Set<String> singletonsCurrentlyInCreation = 
        Collections.newSetFromMap(new ConcurrentHashMap<>(16));

    // The three-level cache lookup:
    protected Object getSingleton(String beanName, boolean allowEarlyReference) {
        Object singletonObject = this.singletonObjects.get(beanName);     // Cache 1
        if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
            singletonObject = this.earlySingletonObjects.get(beanName);   // Cache 2
            if (singletonObject == null && allowEarlyReference) {
                synchronized (this.singletonObjects) {
                    singletonObject = this.singletonObjects.get(beanName);
                    if (singletonObject == null) {
                        singletonObject = this.earlySingletonObjects.get(beanName);
                        if (singletonObject == null) {
                            ObjectFactory<?> singletonFactory = 
                                this.singletonFactories.get(beanName);    // Cache 3
                            if (singletonFactory != null) {
                                singletonObject = singletonFactory.getObject();
                                this.earlySingletonObjects.put(beanName, singletonObject);
                                this.singletonFactories.remove(beanName);
                            }
                        }
                    }
                }
            }
        }
        return singletonObject;
    }
}
```

This three-level cache is NOT an optimization. It's the **circular dependency resolution mechanism**. Level 3 holds an `ObjectFactory` that can provide a raw (unpopulated, uninitialized) bean reference. When circular dependency is detected, the raw reference is placed in Level 2 for injection, then fully initialized later.

## 5. Request Flow Diagrams

### A getBean() Call Trace

```
SomeClient.getBean("orderService")
  │
  ▼
AbstractBeanFactory.getBean("orderService")
  │
  ▼
AbstractBeanFactory.doGetBean("orderService", ...)
  │
  ├──[1] Get beanName (resolve alias)
  │
  ├──[2] Try singletonObjects cache
  │      └── FOUND → return bean (short circuit, ~1μs)
  │
  ├──[3] NOT FOUND — check if currently being created
  │      ├── YES → check earlySingletonObjects (circular dependency)
  │      │   └── FOUND → return early reference
  │      └── NO → continue
  │
  ├──[4] Get BeanDefinition from registry
  │      └── beanDefinitionRegistry.getBeanDefinition("orderService")
  │
  ├──[5] Check dependsOn
  │      └── For each dep: getBean(dep) (recursive, ensures deps created first)
  │
  ├──[6] Check scope: singleton, prototype, request, session
  │
  ├──[7] FOR SINGLETON: synchronized block on singletonObjects
  │      ├── Double-check cache (another thread may have created it)
  │      ├── addSingletonFactory(beanName, () -> getEarlyBeanReference(name, mbd, bean))
  │      │   → This is the Level 3 cache entry → enables circular dependency resolution
  │      ├── markBeanAsCurrentlyInCreation(beanName)
  │      ├── createBean(beanName, mbd, args)
  │      │   ├── resolveBeforeInstantiation(beanName, mbd)
  │      │   │   → InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation()
  │      │   │   → If returns non-null, skip entire creation chain (custom proxy)
  │      │   │
  │      │   ├── doCreateBean(beanName, mbd, args)
  │      │   │   ├── createBeanInstance(beanName, mbd, args)
  │      │   │   │   ├── Resolve constructor (autowiring or explicit)
  │      │   │   │   │   → Determine which constructor to use
  │      │   │   │   │   → Resolve constructor argument values
  │      │   │   │   │   → InstantiationStrategy.instantiate()
  │      │   │   │   │   → Constructor.newInstance(args) or CGLIB Objenesis
  │      │   │   │   └── BeanWrapperImpl wraps the instance
  │      │   │   │
  │      │   │   ├── applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName)
  │      │   │   │   → @Autowired, @Value, @PostConstruct annotation detection
  │      │   │   │
  │      │   │   ├── EARLY EXPOSURE: addSingletonFactory() 
  │      │   │   │   if (earlySingletonExposure) → Level 3 cache
  │      │   │   │
  │      │   │   ├── populateBean(beanName, mbd, bw)
  │      │   │   │   ├── InstantiationAwareBeanPostProcessors.postProcessAfterInstantiation()
  │      │   │   │   ├── InstantiationAwareBeanPostProcessors.postProcessProperties()
  │      │   │   │   │   → AutowiredAnnotationBeanPostProcessor: injects @Autowired
  │      │   │   │   └── applyPropertyValues(beanName, mbd, bw, pvs)
  │      │   │   │       → Setter-based injection from XML/annotation
  │      │   │   │
  │      │   │   ├── initializeBean(beanName, exposedObject, mbd)
  │      │   │   │   ├── invokeAwareMethods()
  │      │   │   │   ├── applyBeanPostProcessorsBeforeInitialization()
  │      │   │   │   │   → @PostConstruct fires here
  │      │   │   │   ├── invokeInitMethods()
  │      │   │   │   │   → InitializingBean.afterPropertiesSet()
  │      │   │   │   │   → custom init-method
  │      │   │   │   └── applyBeanPostProcessorsAfterInitialization()
  │      │   │   │       → AOP proxy wraps the bean here
  │      │   │   │
  │      │   │   └── registerDisposableBeanIfNecessary()
  │      │   │
  │      │   └── RETURN bean (or proxy)
  │      │
  │      ├── removeSingletonCurrentlyInCreation(beanName)
  │      └── addSingleton(beanName, singletonObject)  → Level 1 cache
  │
  └── RETURN the bean
```

### The Instantiation Path: How Does Spring Call Your Constructor?

```java
// Inside ConstructorResolver.autowireConstructor():
public BeanWrapper autowireConstructor(
        String beanName, RootBeanDefinition mbd,
        Constructor<?>[] chosenCtors, Object[] explicitArgs) {

    // 1. Find all constructors on the bean class
    Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
    //   → AutowiredAnnotationBeanPostProcessor.determineCandidateConstructors()
    //   → Returns @Autowired-annotated constructor, or if only one constructor, that one
    //   → If no @Autowired and multiple constructors: picks the default (no-arg)

    // 2. For the chosen constructor, resolve each parameter:
    for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
        // Create a MethodParameter descriptor for each constructor argument
        MethodParameter param = MethodParameter.forExecutable(ctor, paramIndex);
        
        // Resolve the argument value:
        Object argValue = beanFactory.resolveDependency(
            new DependencyDescriptor(param, true), beanName,
            autowiredBeanNames, typeConverter);
        // This calls getBean() for each constructor dependency!
        // This is where circular dependency starts getting interesting.
    }

    // 3. Instantiate:
    BeanWrapper bw = new BeanWrapperImpl(ctor.newInstance(resolvedArgs));
    return bw;
}
```

## 6. Lifecycle Diagrams

### Bean Lifecycle with All Extension Points

```
 ┌────────────────────────────────────────────────────────────────────┐
 │                    BEAN LIFECYCLE — ALL HOOKS                       │
 │                                                                    │
 │  BeanDefinition REGISTERED                                         │
 │      │                                                             │
 │      ├── [Hook] BeanFactoryPostProcessor.postProcessBeanFactory()   │
 │      │          → Can modify bean definitions before creation       │
 │      │          → PropertySourcesPlaceholderConfigurer resolves ${} │
 │      │                                                             │
 │      ▼                                                             │
 │  InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation │
 │      │   → Return non-null to SHORT-CIRCUIT (custom proxy)          │
 │      │   → Return null to proceed normally                          │
 │      │                                                             │
 │      ▼                                                             │
 │  CONSTRUCTOR CALLED (object exists, no fields set)                  │
 │      │                                                             │
 │      ├── [Hook] MergedBeanDefinitionPostProcessor.postProcessMerged │
 │      │          BeanDefinition                                      │
 │      │          → Detects @Autowired, @Value, @PostConstruct, etc. │
 │      │                                                             │
 │      ▼                                                             │
 │  InstantiationAwareBeanPostProcessor.postProcessAfterInstantiation   │
 │      │   → Return true to proceed with property population           │
 │      │   → Return false to skip (rarely done)                       │
 │      │                                                             │
 │      ▼                                                             │
 │  PROPERTY POPULATION                                               │
 │      │   → @Autowired fields are injected                          │
 │      │   → @Autowired setter methods are invoked                   │
 │      │   → @Value fields are resolved                               │
 │      │                                                             │
 │      ├── [Hook] InstantiationAwareBeanPostProcessor.postProcessProperties│
 │      │          → Modifies property values before they're set       │
 │      │                                                             │
 │      ▼                                                             │
 │  AWARE METHODS                                                     │
 │      ├── BeanNameAware.setBeanName(String)                         │
 │      ├── BeanClassLoaderAware.setBeanClassLoader(ClassLoader)      │
 │      ├── BeanFactoryAware.setBeanFactory(BeanFactory)               │
 │      ├── EnvironmentAware.setEnvironment(Environment)               │
 │      ├── EmbeddedValueResolverAware.setEmbeddedValueResolver(...)  │
 │      ├── ResourceLoaderAware.setResourceLoader(ResourceLoader)     │
 │      ├── ApplicationEventPublisherAware.setApplicationEventPublisher│
 │      ├── MessageSourceAware.setMessageSource(MessageSource)        │
 │      └── ApplicationContextAware.setApplicationContext(...)         │
 │                                                                    │
 │      ▼                                                             │
 │  BeanPostProcessor.postProcessBeforeInitialization                  │
 │      ├── @PostConstruct (@InitDestroyAnnotationBeanPostProcessor)  │
 │      ├── ApplicationContextAwareProcessor (sets context refs)       │
 │      └── Custom BeanPostProcessors                                 │
 │                                                                    │
 │      ▼                                                             │
 │  InitializingBean.afterPropertiesSet()                              │
 │                                                                    │
 │      ▼                                                             │
 │  Custom init-method (from @Bean(initMethod = "init"))              │
 │                                                                    │
 │      ▼                                                             │
 │  BeanPostProcessor.postProcessAfterInitialization                   │
 │      ├── AbstractAutoProxyCreator.wrapIfNecessary() — AOP PROXY     │
 │      └── Custom wrapping BeanPostProcessors                        │
 │                                                                    │
 │      ▼                                                             │
 │  BEAN READY (in singletonObjects)                                   │
 │      │                                                             │
 │      ├── [Hook] SmartInitializingSingleton.afterSingletonsInstantiated│
 │      │          → Called AFTER ALL singletons are initialized      │
 │      │          → Good for cache warmup, validation                │
 │      │                                                             │
 │      ▼                                                             │
 │  (Application runs...)                                             │
 │      │                                                             │
 │      ▼                                                             │
 │  DESTRUCTION (context.close())                                     │
 │      ├── DestructionAwareBeanPostProcessor.postProcessBeforeDestruction│
 │      ├── @PreDestroy method                                         │
 │      ├── DisposableBean.destroy()                                   │
 │      └── Custom destroy-method                                      │
 │                                                                    │
 │      ▼                                                             │
 │  BEAN DESTROYED (removed from singletonObjects)                     │
 └────────────────────────────────────────────────────────────────────┘
```

### Context Lifecycle with Parent-Child Hierarchy

```
┌─────────────────────────────────────────────────────────────────────┐
│                     CONTEXT HIERARCHY LIFECYCLE                      │
│                                                                     │
│  ROOT ApplicationContext (parent)                                    │
│  ├── created first                                                  │
│  ├── holds shared beans (DataSource, TransactionManager, CacheManager)│
│  └── Beans visible to all children via getParentBeanFactory()        │
│       │                                                             │
│       ├── CHILD ApplicationContext (e.g., DispatcherServlet context) │
│       │   ├── created after parent                                  │
│       │   ├── holds web-layer beans (Controllers, ViewResolvers)    │
│       │   ├── Can access parent beans                               │
│       │   └── Parent CANNOT access child beans                       │
│       │                                                             │
│       ├── CHILD ApplicationContext (another DispatcherServlet)       │
│       │   └── created after parent                                  │
│       │                                                             │
│  DESTRUCTION ORDER: Children first, then parent                      │
│  └── @PreDestroy: closes children, then parent                       │
│                                                                     │
│  In Spring Boot (typical single-context setup):                      │
│  └── Only ONE context: AnnotationConfigServletWebServerApplicationContext│
│      └── No parent/child split needed (Spring Boot's simplification) │
└─────────────────────────────────────────────────────────────────────┘
```

### Bean Destruction Order

```
context.close()
  │
  ├── publish ContextClosedEvent
  │
  ├── lifecycleProcessor.onClose()
  │   └── DefaultLifecycleProcessor:
  │       └── SmartLifecycle beans.stop() in REVERSE order 
  │           (last started = first stopped)
  │
  └── destroyBeans()
      └── For each singleton bean (in REVERSE creation order):
          ├── DisposableBeanAdapter.destroy()
          │   ├── DestructionAwareBeanPostProcessor.postProcessBeforeDestruction()
          │   ├── @PreDestroy method
          │   ├── DisposableBean.destroy()
          │   └── Custom destroy-method
          └── Remove from singletonObjects cache

CRITICAL: Prototype beans are NEVER destroyed by the container.
          The container registers NO disposable callback for prototypes.
          If a prototype bean holds a connection or file handle,
          you MUST close it manually. This is the #1 cause of 
          resource leaks in Spring applications.
```

## 7. Source Code Reading Guide

### Critical Files (Read In This Order)

```
1. AbstractApplicationContext.java
   spring-framework/spring-context/src/main/java/org/springframework/context/
   support/AbstractApplicationContext.java
   → THE refresh() method — read every line of this 300-line method
   → Understand why each step exists and what it enables

2. DefaultListableBeanFactory.java
   spring-framework/spring-beans/src/main/java/org/springframework/beans/
   factory/support/DefaultListableBeanFactory.java
   → The workhorse — implements both BeanDefinitionRegistry and ListableBeanFactory
   → preInstantiateSingletons(), getBean(), doGetBean(), createBean()

3. AbstractAutowireCapableBeanFactory.java
   spring-framework/spring-beans/src/main/java/org/springframework/beans/
   factory/support/AbstractAutowireCapableBeanFactory.java
   → createBean(), doCreateBean(), populateBean(), initializeBean()
   → The bean creation pipeline

4. DefaultSingletonBeanRegistry.java
   spring-framework/spring-beans/src/main/java/org/springframework/beans/
   factory/support/DefaultSingletonBeanRegistry.java
   → Three-level cache, getSingleton(), addSingleton(), circular dependency support

5. AutowiredAnnotationBeanPostProcessor.java
   spring-framework/spring-beans/src/main/java/org/springframework/beans/
   factory/annotation/AutowiredAnnotationBeanPostProcessor.java
   → How @Autowired fields and methods are resolved via reflection
   → AutowiredFieldElement.inject(), AutowiredMethodElement.inject()

6. CommonAnnotationBeanPostProcessor.java
   spring-framework/spring-context/src/main/java/org/springframework/context/
   annotation/CommonAnnotationBeanPostProcessor.java
   → @PostConstruct and @PreDestroy support
   → Extends InitDestroyAnnotationBeanPostProcessor

7. ConfigurationClassPostProcessor.java
   spring-framework/spring-context/src/main/java/org/springframework/context/
   annotation/ConfigurationClassPostProcessor.java
   → The BFPP that parses @Configuration classes
   → Enhances @Configuration classes with CGLIB

8. ConstructorResolver.java
   spring-framework/spring-beans/src/main/java/org/springframework/beans/
   factory/support/ConstructorResolver.java
   → Constructor argument resolution, autowireConstructor()

9. AbstractAutoProxyCreator.java
   spring-framework/spring-aop/src/main/java/org/springframework/aop/
   framework/autoproxy/AbstractAutoProxyCreator.java
   → Where AOP proxies are created (called in postProcessAfterInitialization)

10. ServletWebServerApplicationContext.java
    spring-boot/spring-boot/src/main/java/org/springframework/boot/
    web/servlet/context/ServletWebServerApplicationContext.java
    → onRefresh() — where the embedded web server starts
    → finishRefresh() — where the servlet container lifecycle completes
```

## 8. Production Failure Scenarios

### Scenario 1: BeanCurrentlyInCreationException

**Symptom**:
```
org.springframework.beans.factory.BeanCurrentlyInCreationException:
Error creating bean with name 'orderService': Requested bean is currently in creation:
Is there an unresolvable circular reference?
```

**Root cause**: Constructor injection with a circular dependency. Spring resolves circular dependencies for setter/field injection through the three-level cache mechanism (early bean reference), but constructor injection requires the fully-constructed bean during construction itself — which is impossible in a cycle.

**Why constructor injection fails but field injection works**:
```
A ──constructor──→ B
B ──constructor──→ A

1. createBean(A): Constructor called → needs B
2. createBean(B): Constructor called → needs A
   → A is still being created, not in cache yet
   → A's ObjectFactory not yet registered (happens after constructor call)
   → FAIL: cannot resolve A during B's construction

vs.

A ──field @Autowired──→ B
B ──field @Autowired──→ A

1. createBean(A): Constructor(no-args), ObjectFactory registered in Level 3
2. populateBean(A): needs B → createBean(B)
3. createBean(B): Constructor(no-args), ObjectFactory registered in Level 3
4. populateBean(B): needs A → getSingleton("A") → Level 3 factory provides raw A
   → Inject raw A reference into B
5. B initialized, B's AOP proxy applied
6. A continues: injects fully-initialized B (or B's proxy)
7. A initialized, A's AOP proxy applied
   → SUCCESS
```

**Resolution**:
1. **Preferred**: Refactor to break the cycle (extract a third class C that both A and B depend on)
2. **Acceptable**: Switch constructor injection to field/setter injection (with `@Lazy` on one side, or an `ObjectProvider`/`Provider`)
3. **Last resort**: `@Lazy` on one constructor parameter

### Scenario 2: Startup Hangs — Bean Never Finishes Creating

**Symptom**: Application starts but never becomes ready. Thread dump shows threads BLOCKED in `getBean()` or `invokeInitMethods()`.

**Root cause**: A `@PostConstruct` or `afterPropertiesSet()` method makes a blocking call (e.g., database connection, HTTP call to a service that isn't up yet) with no timeout.

```java
@Component
public class CacheWarmer {
    @PostConstruct
    public void warmCache() {
        // This blocks the startup thread for 60 seconds waiting for Redis
        redisTemplate.opsForValue().get("large-cache-key"); // No timeout configured!
    }
}
```

**Diagnosis**:
```bash
jstack <pid> | grep -A 10 "main" | grep "at com.myapp.CacheWarmer.warmCache"
# Or via Actuator thread dump: /actuator/threaddump
```

**Resolution**: Move blocking initialization out of `@PostConstruct` into an `ApplicationRunner` or `SmartInitializingSingleton` that runs after all beans are ready. Use async execution with a timeout.

### Scenario 3: @PostConstruct Fires Twice

**Symptom**: Database initialization runs twice, double-inserting data.

**Root cause**: The bean is defined in two `@Configuration` classes, producing two bean instances. OR the `@Bean` method is called directly (not proxied by CGLIB), creating a new instance each time it's called, each getting its own lifecycle callbacks.

```java
@Configuration
public class AppConfig {
    @Bean
    public DatabaseInitializer databaseInitializer() {
        return new DatabaseInitializer();  // ← @PostConstruct fires once here
    }

    @Bean
    public SomeService someService() {
        return new SomeService(databaseInitializer());  
        // ← Calling @Bean method directly = NEW instance = @PostConstruct fires AGAIN
        // Fix: use parameter injection: someService(DatabaseInitializer init)
    }
}
```

**Resolution**: ALWAYS use parameter injection in `@Bean` methods. The CGLIB proxy ensures the existing singleton is returned, not a new instance.

### Scenario 4: Production Server Refuses Traffic for 3 Minutes After Startup

**Symptom**: Kubernetes readiness probe fails for 180 seconds after pod start.

**Root cause**: Spring Boot creates ALL non-lazy singleton beans before accepting traffic. If you have 200+ beans and Hibernate entity scanning over 500 entity classes, startup can take 2-3 minutes. The web server starts at step 9 (`onRefresh()`), but `ApplicationReadyEvent` doesn't fire until step 12 completes and runners execute.

**Diagnosis**: Check when `ApplicationReadyEvent` fires in logs vs when the first request arrives.

**Resolution**:
1. Enable `spring.main.lazy-initialization=true` (trade-off: first request latency)
2. Exclude unnecessary auto-configurations
3. Narrow Hibernate entity scanning with `@EntityScan(basePackages = "com.myapp.domain")`
4. Use Spring Boot 3.x AOT processing for 50-70% startup time reduction
5. Register readiness probe to check the `/actuator/health/readiness` endpoint

## 9. Debugging Techniques

### Tracing Bean Creation

```java
// Register a bean post-processor that logs every bean creation:
@Component
public class BeanCreationDebugger implements BeanPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(BeanCreationDebugger.class);

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        log.info("Bean '{}' of type {} about to be initialized", 
            beanName, bean.getClass().getSimpleName());
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        log.info("Bean '{}' initialization complete", beanName);
        return bean;
    }
}
```

### Finding Which Beans Are Not Lazy

```java
@Component
public class NonLazyBeanReporter implements ApplicationListener<ContextRefreshedEvent> {
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        ConfigurableListableBeanFactory bf = 
            (ConfigurableListableBeanFactory) event.getApplicationContext()
                .getAutowireCapableBeanFactory();
        
        for (String name : bf.getBeanDefinitionNames()) {
            BeanDefinition bd = bf.getBeanDefinition(name);
            if (bd.isSingleton() && !bd.isLazyInit() && !bd.isAbstract()) {
                System.out.printf("Non-lazy singleton: %s (%s)%n", 
                    name, bd.getBeanClassName());
            }
        }
    }
}
```

### Diagnosing BeanCurrentlyInCreationException

```bash
# 1. Get the full stack trace
java -jar myapp.jar 2>&1 | grep -A 50 "BeanCurrentlyInCreationException"

# 2. Identify the cycle
# Look for the "chain" in the exception message:
# "Requested bean is currently in creation: [orderService -> paymentService -> orderService]"
# This tells you: orderService tries to create paymentService, which tries to create orderService

# 3. Enable TRACE logging for bean creation
logging.level.org.springframework.beans.factory=TRACE

# 4. Use a breakpoint in AbstractAutowireCapableBeanFactory.createBean()
# Step through to see the exact dependency chain
```

### Startup Time Analysis

```java
// Add to application.properties:
spring.application.startup.log-step=true

// Or programmatically on Spring Boot 3.x:
public static void main(String[] args) {
    new SpringApplicationBuilder(MyApplication.class)
        .applicationStartup(new BufferingApplicationStartup(10000))
        .run(args);
}

// Then call: GET /actuator/startup
// Returns JSON with timing for each step:
// {
//   "spring.beans.instantiate": [
//     { "beanName": "entityManagerFactory", "duration": "PT1.234S" },
//     { "beanName": "dataSource", "duration": "PT0.089S" },
//     ...
//   ]
// }
```

## 10. Observability Considerations

### Key Context Metrics

```
spring.application.started.time      → Time from start to ApplicationStartedEvent
spring.application.ready.time        → Time from start to ApplicationReadyEvent
spring.context.refresh.time           → Time spent in refresh()
spring.beans.definitions              → Total BeanDefinitions registered
spring.beans.singletons               → Total singleton beans instantiated
spring.beans.prototype-created        → Prototype beans created (grows over time)
```

### Bean Lifecycle Monitoring

```java
@Component
public class BeanLifecycleMetrics {
    private final MeterRegistry registry;
    private final Counter beanInitCounter;
    private final Counter beanDestroyCounter;

    public BeanLifecycleMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.beanInitCounter = Counter.builder("spring.beans.initialized")
            .description("Number of beans initialized")
            .register(registry);
        this.beanDestroyCounter = Counter.builder("spring.beans.destroyed")
            .description("Number of beans destroyed")
            .register(registry);
    }

    @EventListener
    public void onContextRefreshed(ContextRefreshedEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        int count = ctx.getBeanDefinitionCount();
        Gauge.builder("spring.beans.definition.count", () -> count)
            .register(registry);
    }
}
```

### Context State Health

The Actuator health endpoint exposes context state:
```
/actuator/health
{
    "status": "UP",
    "components": {
        "livenessState": { "status": "UP" },
        "readinessState": { "status": "UP" }
    }
}
```

These states correspond to:
- `LivenessState` = "is the application alive?" (JVM is running, context has not been closed)
- `ReadinessState` = "can the application serve traffic?" (context is refreshed, runners completed)

## 11. Performance Implications

### Bean Creation: The Hidden Startup Cost

```
The cost of creating 1000 singleton beans:

Without @PostConstruct cost:
  └── Instantiation + @Autowired + proxy creation: ~5-10ms per bean
      1000 beans × 7.5ms avg = 7.5 seconds

With expensive @PostConstruct work:
  ├── Database connection tests: 50ms each
  ├── Cache population: 100ms-2s
  ├── External API health checks: 200ms-5s
  └── ObjectMapper configuration: 20ms
  
  Worst case with 20 expensive beans: 10s (bean creation) + 30s (init work) = 40s startup
```

**Optimization strategies**:

1. **Lazy initialization**: `spring.main.lazy-initialization=true`
   - Startup: 70% faster
   - First request: 2-3 seconds slower (all lazy beans created)
   - Cold path: triggers `LazyInitializationException` outside transactions
   - Best for: Development, CI/CD pipelines

2. **Bulk warm-up**: Instead of `@PostConstruct`, use `SmartInitializingSingleton`:
   ```java
   @Component
   public class BulkWarmup implements SmartInitializingSingleton {
       @Override
       public void afterSingletonsInstantiated() {
           // This runs AFTER ALL beans are ready
           // You know the entire context is intact before warming anything
           CompletableFuture.runAsync(this::warmCache);
       }
   }
   ```

3. **AOT compilation** (Spring Boot 3.x): Pre-computes bean definitions and condition evaluations at build time, eliminating classpath scanning and condition evaluation at startup.

### Singleton Memory Footprint

```
A typical medium Spring Boot app:

Spring framework overhead:                  50-80 MB
  ├── DefaultListableBeanFactory:           10 MB (bean definitions, caches)
  ├── CGLIB proxies:                        20 MB (generated classes)
  ├── Spring AOP advisor chains:             5 MB
  └── ApplicationContext infrastructure:    15 MB (environment, message source, etc.)

Application beans:                          50-200 MB
  ├── 100-500 beans × ~1KB each:           0.1-0.5 MB (the beans themselves)
  ├── Hibernate metadata + SessionFactory: 30-150 MB
  ├── Connection pools (HikariCP):          2-10 MB
  ├── Caches (Caffeine default):            10-100 MB
  └── Configurations (properties, @Value):  5-20 MB

Total Spring overhead ≈ 50-80 MB regardless of application size.
This is the "Spring tax" — the fixed cost of the container.
```

## 12. Architecture Implications

### Singleton Scope and Thread Safety

Every singleton bean is shared across all threads in the JVM. This means:

```java
// THIS IS BROKEN — non-thread-safe singleton:
@Service
public class CounterService {
    private int counter = 0;  // Shared mutable state on a SINGLETON
    
    public int increment() {
        return ++counter;  // Race condition: two threads can read the same value
    }
}

// CORRECT — use request-scoped bean for per-request state:
@Service
@RequestScope
public class CounterService {
    private int counter = 0;  // Each HTTP request gets its own instance
    
    public int increment() { return ++counter; }
}

// ALSO CORRECT — use thread-safe types on singletons:
@Service
public class CounterService {
    private final AtomicInteger counter = new AtomicInteger(0);
    
    public int increment() { return counter.incrementAndGet(); }
}
```

### Context Hierarchy Design Decision

A common Spring Boot anti-pattern is creating a parent-child context hierarchy for "modularity":

```java
// Anti-pattern: Manual parent/child context hierarchy
public static void main(String[] args) {
    AnnotationConfigApplicationContext parent = 
        new AnnotationConfigApplicationContext(InfrastructureConfig.class);
    
    AnnotationConfigApplicationContext child = 
        new AnnotationConfigApplicationContext();
    child.setParent(parent);
    child.register(WebConfig.class);
    child.refresh();
}
```

Spring Boot intentionally flattens this hierarchy into a single context. The parent-child split was a legacy from pre-Spring Boot days (root context for services + child context for DispatcherServlet). Spring Boot's approach is simpler, faster, and eliminates the common "my controller can't find a bean that I can see in the parent context" confusion.

**When a hierarchy IS appropriate**: When you need to run two Spring applications in the same JVM that share some beans. For example, a batch-processing module and a web module sharing the same DataSource but having different lifecycle boundaries.

## 13. Team Ownership Implications

### Bean Management Across Teams

| Scenario | Responsibility |
|----------|---------------|
| A bean fails to create in production | The team that OWNS the bean (indicated by its package) |
| A bean was removed, breaking another team's app | The removing team (they must communicate breaking changes) |
| An auto-configuration bean conflicts with a manually defined bean | The application team (they must exclude or override) |
| A library upgrade changes bean lifecycle behavior | Platform team (they own dependency management) |
| Too many beans causing slow startup | Architecture/platform team (governs which auto-configs are enabled) |

### Bean Dependency Governance

Establish conventions for:
1. **Bean naming**: Every `@Component` gets an explicit name; `@Bean` methods use descriptive names
2. **Bean visibility**: Custom components should not depend on auto-configured infrastructure beans by name (use by-type injection)
3. **Lifecycle hooks**: `@PostConstruct` must be idempotent (will it break if called twice after a refresh?)
4. **Destroy hooks**: `@PreDestroy` must handle partial failure (not all beans may have been created)

## 14. Interview Questions

### Question 1: "Walk me through the 12 steps of `AbstractApplicationContext.refresh()`. Why does Spring execute them in this specific order?"

**Staff-level answer**: The 12-step sequence is not arbitrary — it is a carefully ordered dependency chain. Step 1 (`prepareRefresh`) sets up the environment context because all subsequent steps need property placeholder resolution. Step 2 (`obtainFreshBeanFactory`) creates the `DefaultListableBeanFactory` — this must happen before anything uses it. Step 3 (`prepareBeanFactory`) registers the context's ClassLoader and SpEL support, and crucially, adds ignore-dependency interfaces. These interfaces (`BeanFactoryAware`, `ResourceLoaderAware`, etc.) must be registered as "ignored for autowiring" before autowiring begins, or `@Autowired` would try to inject the `BeanFactory` by type and crash.

Steps 4 and 5 are where the real work begins. Step 4 (`postProcessBeanFactory`) allows subclasses to register special beans before normal processing. Step 5 (`invokeBeanFactoryPostProcessors`) processes `BeanFactoryPostProcessor` and `BeanDefinitionRegistryPostProcessor` beans — this is where `ConfigurationClassPostProcessor` scans `@Configuration` classes and registers `@Bean` method definitions. The ordering within step 5 matters: `BeanDefinitionRegistryPostProcessor` implementations (which register NEW bean definitions) must run before `BeanFactoryPostProcessor` implementations (which modify EXISTING definitions), because you need to know all definitions exist before you modify them.

Step 6 (`registerBeanPostProcessors`) must happen AFTER step 5 (now all bean definitions are registered that might declare new BPPs) but BEFORE step 11 (bean instantiation). If BPPs were registered after bean creation, `@Autowired` and `@PostConstruct` would not work. Step 9 (`onRefresh`) intentionally starts the web server BEFORE beans are instantiated because some beans might depend on the web server being available (e.g., a bean that registers servlets programmatically).

The most critical ordering constraint: **step 11 (`finishBeanFactoryInitialization`) must be the penultimate step.** All infrastructure (post-processors, event multicaster, message source, lifecycle processor) must be in place before any user bean is created. This is why Spring Boot's auto-configuration mechanism — which fires in step 5 — has already registered all its bean definitions before a single bean is actually instantiated in step 11.

Step 12 (`finishRefresh`) fires `ContextRefreshedEvent`, which tells the world "all beans are created, the context is ready." Any `ApplicationListener<ContextRefreshedEvent>` can now safely access any bean.

### Question 2: "How does Spring resolve circular dependencies? What are the limitations?"

**Staff-level answer**: Spring resolves circular dependencies for singleton beans through its three-level cache in `DefaultSingletonBeanRegistry`. The key mechanism is *early bean reference exposure*.

When `doCreateBean()` starts, after the constructor call but BEFORE property population, Spring registers the bean instance in the Level 3 cache (`singletonFactories`) as an `ObjectFactory`. This factory can produce either the raw bean or an early AOP proxy reference. When another bean (B) tries to `getBean("A")` while A is still being created, `getSingleton("A", true)` finds the factory in Level 3, creates the early reference, moves it to Level 2 (`earlySingletonObjects`), and returns it to B. B receives a partially-created A (constructor called, no fields set, no initialization). B finishes its own creation, then A continues where it left off: populating properties (including injecting the now-fully-created B) and initializing.

This ONLY works for singleton scope. Prototype beans have no cache and no early reference mechanism — a circular dependency in prototype scope always throws `BeanCurrentlyInCreationException`.

The critical limitation is **constructor injection**. Constructor injection requires the fully-resolved dependency AT CONSTRUCTION TIME. The three-level cache mechanism exposes the bean AFTER the constructor call. So when A's constructor needs B, and B's constructor needs A, neither has been exposed to the cache yet. This is why constructor injection circular dependencies always fail.

Another subtlety: if A is an AOP proxy target, the early reference might be the RAW bean, not the proxy. Spring handles this through `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference()`, which wraps the raw bean in the proxy before putting it in the cache. But this means the B receives a proxy-to-A while A itself is still being initialized. If B calls a method on A's proxy during initialization, and that method is `@Transactional` or `@Cacheable`, the advice won't fire because A hasn't been fully post-processed yet. This is why circular dependencies with AOP are dangerous even when they "work."

**Bottom line**: Circular dependencies indicate a design problem. Spring supports them for backward compatibility, but a Staff engineer's reflex should be to refactor the cycle away, not to rely on the framework's escape hatches.

### Question 3: "Explain the difference between a BeanFactoryPostProcessor and a BeanPostProcessor. When would you use each? Why are they invoked at different points in the refresh cycle?"

**Staff-level answer**: The difference is fundamental and the names hint at it precisely: `BeanFactoryPostProcessor` operates on the *factory* (bean definitions, before beans exist), and `BeanPostProcessor` operates on *bean instances* (after they are created but during their lifecycle).

**BeanFactoryPostProcessor** fires in refresh step 5, before ANY bean is created. It receives the `ConfigurableListableBeanFactory` and can inspect or modify `BeanDefinition` objects. The canonical example is `PropertySourcesPlaceholderConfigurer`, which scans all `BeanDefinition` objects for `${...}` placeholders and resolves them against the `Environment`. Without this BFPP, `@Value("${server.port}")` would inject the literal string `${server.port}`. Another example: `ConfigurationClassPostProcessor` is itself a BFPP (specifically a `BeanDefinitionRegistryPostProcessor`) that parses `@Configuration` classes and registers their `@Bean` method definitions — without it, `@Configuration` and `@Bean` wouldn't work.

**BeanPostProcessor** fires during bean instantiation in step 11, and operates on individual bean instances. It provides two hooks: `postProcessBeforeInitialization` (before `@PostConstruct`) and `postProcessAfterInitialization` (after `@PostConstruct`, where AOP proxies are created). `AutowiredAnnotationBeanPostProcessor` injects `@Autowired` fields during `postProcessProperties` (a more specific hook). `CommonAnnotationBeanPostProcessor` calls `@PostConstruct` methods in `postProcessBeforeInitialization`.

The ordering in the refresh cycle is enforced: BFPPs MUST run before beans are created (you need to know what beans to create), and BPPs MUST be registered before bean creation but execute DURING bean creation. If you tried to run a BFPP after bean creation, it would be too late to modify definitions. If you tried to run a BPP before bean definitions were registered, it would have nothing to process.

**When to use each**:
- Use **BFPP** when you need to modify how beans are DEFINED: change scopes, add property values, register additional bean definitions, or modify constructor arguments before any bean is instantiated.
- Use **BPP** when you need to modify or wrap bean INSTANCES: add proxy behavior, customize initialization, or inject dependencies that can't be expressed through `@Autowired` (e.g., from a non-Spring registry).

A Staff Engineer also knows: if you declare a BFPP as a `@Bean` inside a `@Configuration` class, don't make it depend on other `@Bean` methods. All BFPPs must be instantiated early, and their dependencies might not be available yet. Always use `static @Bean` methods for BFPPs and BPPs.

## 15. Hands-On Exercises

1. **Set a breakpoint in `DefaultListableBeanFactory.preInstantiateSingletons()` and trace the creation of 5 beans**: Observe the stack trace for each bean. Notice which `BeanPostProcessor` runs for each. Pay attention to the order of `applyBeanPostProcessorsBeforeInitialization` and `after`.

2. **Create a circular dependency intentionally**: Write two services that depend on each other via (a) field injection and (b) constructor injection. Observe the difference in behavior. Add `@Lazy` to one side of the constructor injection and see it resolve. Then remove `@Lazy` and refactor by extracting a third class.

3. **Implement a custom `BeanPostProcessor` that times bean initialization**: Record the time each bean spends in `postProcessBeforeInitialization` and `postProcessAfterInitialization`. Aggregate results and print the slowest 10 beans at the end of startup.

4. **Profile bean memory usage**: Create 1000 beans with various scopes (singleton, prototype). Use `jmap -histo <pid>` to compare heap usage of singleton vs prototype. Observe that prototype beans are not tracked by the container and not GC'd until unreachable from application code.

5. **Trace the `refresh()` sequence with logging**: Add `@EventListener` handlers for `ContextRefreshedEvent`, `ContextStartedEvent`, `ContextStoppedEvent`, `ContextClosedEvent`. Observe the order they fire. Add a BeanPostProcessor that logs every bean name before and after initialization. Cross-reference with `--debug` output.

## 16. Advanced Challenges

1. **Implement a custom `BeanFactoryPostProcessor` that replaces `${}` placeholders from a remote configuration service**: The BFPP should query a REST API for configuration values and resolve placeholders in all BeanDefinitions before beans are created. Handle the case where the remote service is unavailable — the application should still start with defaults but log a warning. Test with `ApplicationContextRunner`.

2. **Build a "Bean Dependency Graph" visualizer**: Use `DefaultListableBeanFactory.getDependenciesForBean()` and `getDependentBeans()` to construct a directed graph of all bean dependencies. Export as DOT format for Graphviz. Identify cycles, orphan beans (no dependents but not a controller), and "god beans" (>50 dependents). Run against a real application and identify architectural problems from the graph.

3. **Implement a "Startup Gate" that prevents the application from accepting traffic until all cache warmers, connection pool validators, and health checks pass**: Use `SmartLifecycle` with ordered phases. The `SmartLifecycle` bean runs in onRefresh phase. If any check fails, the application should start (so Kubernetes can see it) but report DOWN readiness state so traffic is not routed to it. This requires overriding the default `AvailabilityChangeEvent` behavior.

4. **Build a "Bean Hot Reload" prototype**: Implement a mechanism that, given a class file change, reloads a single bean without restarting the entire application context. This requires: destroying the old bean (calling `@PreDestroy`, removing from singletonObjects), re-parsing the bean definition, creating a new instance, and injecting it into all dependent beans. Handle the challenge that dependent beans hold references to the OLD bean instance.

5. **Analyze the performance impact of `@Configuration` CGLIB proxying**: Create two versions of the same configuration — one with `@Configuration(proxyBeanMethods = true)` (default) and one with `proxyBeanMethods = false`. In each, define three `@Bean` methods where B depends on A, and C depends on both A and B. For the proxy=true version, verify that all three beans call the factory method, and A and B are only created once (singleton). For proxy=false, verify that A is created three times. Measure the startup time difference and memory difference with 100 such configurations. Write a recommendation for when to use each mode.
