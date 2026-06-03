# Session 17: Middleware, Filters, Interceptors & AOP

## 1. Why This Topic Exists

Every non-trivial web application has cross-cutting concerns: logging, authentication, rate limiting, metrics collection, trace propagation, request validation. Spring offers three distinct mechanisms to implement them — Servlet Filters, HandlerInterceptors, and AOP — and the line between them is deliberately blurred. A developer who does not understand the differences will implement authentication in a `@ControllerAdvice`, rate limiting in an `@Around` aspect, and cache headers in a filter, producing a system where cross-cutting logic is scattered across three layers with no clear ownership or execution order guarantees.

The cost of getting this wrong is not academic. When a filter buffers the request body for audit logging before it reaches `@RequestBody` in the controller, the controller sees an empty InputStream. When an interceptor's `preHandle` returns `false`, the handler is never invoked but the filter chain has already committed response headers. When `@Transactional` and `@Cacheable` are stacked in the wrong order on the same method, the transaction commits before the cache is populated — so the next read sees stale data.

**Staff engineer insight**: Understanding the filter→interceptor→AOP chain is what separates engineers who can build a resilient gateway layer from those who copy-paste `OncePerRequestFilter` code from Stack Overflow. The execution order is deterministic but non-obvious. The proxy chain is invisible unless you know how to inspect it. And the interaction between `@Transactional`, `@Cacheable`, `@Async`, and custom `@Aspect` annotations on the same method creates a proxy stack whose behavior depends on the order in which `BeanPostProcessor` instances registered those proxies. If you cannot trace a request through all three layers in your head, you cannot debug why your audit log shows a 200 response for a request that was supposed to be rate-limited at 429.

## 2. Mental Model

The request processing pipeline is a layered onion. Each layer wraps the next, and the request passes through each layer twice — once inbound (before the controller) and once outbound (after the controller).

```
        INBOUND                                        OUTBOUND
        ───────►                                        ───────►

CLIENT ──► Filter₁ ──► Filter₂ ──► DispatcherServlet ──► Interceptor₁ ──► Interceptor₂
                                                                              │
                                                                              ▼
                                                                     AOP Proxy Stack
                                                                   ┌──────────────────┐
                                                                   │ @Cacheable proxy │
                                                                   │ @Async proxy     │
                                                                   │ @Transactional   │
                                                                   │ Custom @Aspect   │
                                                                   │ CONTROLLER       │
                                                                   └──────────────────┘
                                                                              │
                                                                              ▼
        ◄── Filter₁ ◄── Filter₂ ◄── DispatcherServlet ◄── Interceptor₁ ◄── Interceptor₂
        ◄──────────── OUTBOUND ─────────────────────────────────────────────────────────
```

The key insight: **Filters are servlet-container-level**. They operate on raw `ServletRequest`/`ServletResponse` objects and have no knowledge of Spring's handler mappings, controller methods, or AOP proxies. **Interceptors are Spring-MVC-level**. They know which handler method was selected and can inspect `ModelAndView`. **AOP aspects are Spring-bean-level**. They wrap method invocations on proxied beans and know nothing about HTTP semantics.

```
Decision Tree: Which Mechanism Should I Use?

Problem: "I need to implement cross-cutting behavior X"

├── Does X need access to raw HTTP request/response (headers, body, status codes)?
│   └── YES → Filter. (Filters own the ServletInputStream/ServletOutputStream.)
│   
├── Does X need to know which controller method was selected?
│   └── YES → HandlerInterceptor. (Only interceptors can see HandlerMethod.)
│   
├── Does X need to be invoked AFTER the controller returns but BEFORE the view renders?
│   └── YES → HandlerInterceptor.postHandle(). (AOP cannot do this for @ResponseBody.)
│   
├── Does X apply to specific service methods (not just controllers)?
│   └── YES → AOP @Aspect. (Filters and interceptors only know about HTTP requests.)
│   
├── Does X need to stop the request from reaching the controller at all?
│   └── YES → Filter or Interceptor.preHandle() returning false.
│       ├── If before Spring context → Filter
│       └── If after Spring context → Interceptor
│   
├── Does X need to transform method arguments or return values?
│   └── YES → AOP @Around. (Filters/interceptors see raw byte streams, not typed objects.)
│   
└── Does X need transactional semantics (commit/rollback)?
    └── YES → AOP @Transactional. (This is specifically what it was built for.)
```

The execution order (correct for Spring Boot 3.x with default configuration):

```
1. Servlet Filter chain (doFilter)
2. DispatcherServlet.doDispatch()
3. HandlerInterceptor.preHandle() — for each registered interceptor
4. Controller method invocation (through AOP proxy stack)
5. HandlerInterceptor.postHandle() — for each interceptor (REVERSE order of preHandle)
6. After response committed: Filter chain resumes after doFilter()
7. HandlerInterceptor.afterCompletion() — for each interceptor (REVERSE order)
```

## 3. Internal Architecture

### The Servlet Filter Chain: Filter → FilterChain → Target

The filter chain is a servlet-container concept, defined by `javax.servlet.Filter` (Jakarta EE) or `jakarta.servlet.Filter` (Spring Boot 3.x / Servlet 5+). Spring Boot auto-configures its own filter chain wrapper, but the execution model is determined by the servlet container (Tomcat, Jetty, Undertow).

```java
// Source: org.apache.catalina.core.ApplicationFilterChain (Tomcat implementation)
// This is the CONTAINER-level filter chain. Spring's DelegatingFilterProxy and
// FilterChainProxy wrap this for Spring-managed filters.

final class ApplicationFilterChain implements FilterChain {

    // Internal array of filters registered with the servlet container
    private ApplicationFilterConfig[] filters = new ApplicationFilterConfig[0];

    // Current position in the filter chain (incremented with each doFilter call)
    private int pos = 0;

    // Number of filters in the chain
    private int n = 0;

    // The target servlet (usually DispatcherServlet)
    private Servlet servlet = null;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {

        // ── CORE LOGIC: Walk the filter array, one filter at a time ──
        // On each call, pos is incremented. When pos >= n, all filters
        // have been visited → invoke the target servlet.

        if (Globals.IS_SECURITY_ENABLED) {
            // Security-enabled path (rarely used in modern apps)
            internalDoFilter(request, response);
        } else {
            internalDoFilter(request, response);
        }
    }

    private void internalDoFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {

        // ── Get the next filter (or null if we've exhausted the chain) ──
        if (pos < n) {
            // There is a next filter → invoke it
            ApplicationFilterConfig filterConfig = filters[pos++];
            Filter filter = filterConfig.getFilter();

            // The filter's doFilter() will call chain.doFilter() again,
            // which will come back into THIS method with pos incremented.
            // This is classic recursive chain delegation:
            //
            //   Filter₁.doFilter(req, resp, chain) {
            //       // pre-processing
            //       chain.doFilter(req, resp);  // → calls Filter₂
            //       // post-processing
            //   }

            filter.doFilter(request, response, this);

        } else {
            // ── All filters exhausted → invoke the target servlet ──
            // This calls DispatcherServlet.service(request, response)
            servlet.service(request, response);
        }
    }
}
```

**Critical detail**: The `pos` counter is NOT thread-safe. Each request gets its own `ApplicationFilterChain` instance (created per-request by the servlet container), so the single-threaded counter is safe. However, this also means the filter chain is request-scoped — you cannot share state between requests through the chain itself.

**Spring's OncePerRequestFilter**: The problem with raw `Filter` is that a single HTTP request can pass through the filter chain multiple times — once for the original request, and potentially again for `RequestDispatcher.forward()` or `include()` calls within the servlet container. This causes security filters to fire twice, rate limiters to double-count, and MDC context to be set redundantly.

```java
// Source: org.springframework.web.filter.OncePerRequestFilter
// This is Spring's solution to the multiple-invocation problem.

public abstract class OncePerRequestFilter extends GenericFilterBean {

    public static final String ALREADY_FILTERED_SUFFIX = ".FILTERED";

    @Override
    public final void doFilter(ServletRequest request, ServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!(request instanceof HttpServletRequest httpRequest) ||
            !(response instanceof HttpServletResponse httpResponse)) {
            throw new ServletException("OncePerRequestFilter only supports HTTP requests");
        }

        // ── THE KEY CHECK: Has this filter already run for this request? ──
        String alreadyFilteredAttributeName = getAlreadyFilteredAttributeName();
        boolean hasAlreadyFilteredAttribute =
            request.getAttribute(alreadyFilteredAttributeName) != null;

        // Handle skip-after-forward and skip-after-async scenarios
        if (skipDispatch(httpRequest) || shouldNotFilter(httpRequest)) {
            // Proceed without filtering
            filterChain.doFilter(request, response);
            return;
        }

        if (hasAlreadyFilteredAttribute) {
            // Already filtered for this request → SKIP
            if (DispatcherType.ASYNC.equals(request.getDispatcherType())) {
                // For async dispatch: clear the marker and re-filter
                request.setAttribute(alreadyFilteredAttributeName, null);
            } else {
                // Skip entirely
                filterChain.doFilter(request, response);
                return;
            }
        }

        // ── Mark as filtered ──
        request.setAttribute(alreadyFilteredAttributeName, Boolean.TRUE);

        try {
            // ── Delegate to subclass implementation ──
            doFilterInternal(httpRequest, httpResponse, filterChain);
        } finally {
            // ── DO NOT remove the attribute! ──
            // The FILTERED marker persists for the entire request lifecycle
            // to prevent re-invocation on forward/include/error dispatches.
        }
    }

    // The attribute name includes the filter's bean name for uniqueness:
    protected String getAlreadyFilteredAttributeName() {
        String name = getFilterName();  // bean name from GenericFilterBean
        if (name == null) {
            name = getClass().getName();
        }
        return name + ALREADY_FILTERED_SUFFIX;
        // Example: "requestLoggingFilter.FILTERED"
    }

    // Template method — SUBCLASSES override this, NOT doFilter():
    protected abstract void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException;
}
```

**Spring Boot's filter registration**: Filters are NOT auto-discovered via `@Component`. They must be registered explicitly, either via `@Bean` + `FilterRegistrationBean` or via `@WebFilter` + `@ServletComponentScan`. Spring Boot's auto-configuration adds a `DelegatingFilterProxyRegistrationBean` for the `springSecurityFilterChain` but leaves custom filter registration to the developer.

```java
// How Spring Boot registers filters:
// 
// 1. WebMvcAutoConfiguration scans for Filter beans
// 2. For each Filter bean, creates a FilterRegistrationBean
// 3. FilterRegistrationBean is a ServletContextInitializer
// 4. During embedded server startup (refresh step 9), all ServletContextInitializers run
// 5. Each FilterRegistrationBean calls:
//      servletContext.addFilter(filterName, filter)
//            .addMappingForUrlPatterns(dispatcherTypes, isMatchAfter, "/*");

// FilterRegistrationBean controls ordering:
@Bean
public FilterRegistrationBean<RequestLoggingFilter> loggingFilter() {
    FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new RequestLoggingFilter());
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);  // Lower = earlier
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
    return registration;
}
```

### HandlerInterceptor: Spring MVC's Middleware Layer

The `HandlerInterceptor` sits between `DispatcherServlet` and the controller method. It has access to the `HandlerMethod` (the specific controller method that was selected by `RequestMappingHandlerMapping`), but operates AFTER the filter chain and BEFORE the AOP proxy.

```java
// Source: org.springframework.web.servlet.HandlerInterceptor
// Minimal interface — three lifecycle methods:

public interface HandlerInterceptor {

    // Called BEFORE the handler (controller) executes.
    // Return true → proceed to next interceptor then handler
    // Return false → SHORT-CIRCUIT: stop chain, trigger afterCompletion for 
    //                  all interceptors that already had preHandle called
    default boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) throws Exception {
        return true;
    }

    // Called AFTER the handler executes, BEFORE the view is rendered.
    // NOT called if handler threw an exception (unless annotated differently).
    // NOT called if @ResponseBody wrote the response directly (common in REST APIs).
    // modelAndView: may be null if the handler returned void or @ResponseBody
    default void postHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler, @Nullable ModelAndView modelAndView) throws Exception {
    }

    // Called AFTER request completion (response committed).
    // ALWAYS called — even if preHandle returned false, even if exception thrown.
    // Used for cleanup: removing ThreadLocal state, logging, metrics.
    default void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, @Nullable Exception ex) throws Exception {
    }
}
```

**Where interceptors are stored and invoked** — inside `HandlerExecutionChain`:

```java
// Source: org.springframework.web.servlet.HandlerExecutionChain

public class HandlerExecutionChain {

    private final Object handler;  // The controller method (HandlerMethod) or handler bean

    @Nullable
    private HandlerInterceptor[] interceptors;  // Interceptors for this chain

    @Nullable
    private List<HandlerInterceptor> interceptorList;

    private int interceptorIndex = -1;  // Current position (for preHandle walk)

    // Called by DispatcherServlet:
    boolean applyPreHandle(HttpServletRequest request, HttpServletResponse response)
            throws Exception {

        for (int i = 0; i < this.interceptors.length; i++) {
            HandlerInterceptor interceptor = this.interceptors[i];
            if (!interceptor.preHandle(request, response, this.handler)) {
                // preHandle returned false → short-circuit
                // Trigger afterCompletion for interceptors that already ran preHandle
                triggerAfterCompletion(request, response, null);
                return false;
            }
            this.interceptorIndex = i;  // Track how far we got
        }
        return true;
    }

    // Called by DispatcherServlet AFTER handler execution:
    void applyPostHandle(HttpServletRequest request, HttpServletResponse response,
            @Nullable ModelAndView mv) throws Exception {

        for (int i = this.interceptors.length - 1; i >= 0; i--) {
            HandlerInterceptor interceptor = this.interceptors[i];
            interceptor.postHandle(request, response, this.handler, mv);
        }
        // REVERSE ORDER: The last interceptor's postHandle runs FIRST
    }

    // Called by DispatcherServlet in finally block (always executes):
    void triggerAfterCompletion(HttpServletRequest request, HttpServletResponse response,
            @Nullable Exception ex) {

        for (int i = this.interceptorIndex; i >= 0; i--) {
            HandlerInterceptor interceptor = this.interceptors[i];
            try {
                interceptor.afterCompletion(request, response, this.handler, ex);
            } catch (Throwable ex2) {
                logger.error("HandlerInterceptor.afterCompletion threw exception", ex2);
            }
        }
        // Only runs afterCompletion for interceptors WHOSE preHandle WAS CALLED
        // When short-circuit (preHandle returns false): interceptorIndex is the
        //   index of the interceptor BEFORE the one that returned false
        //   → afterCompletion runs for all interceptors that had preHandle run successfully
    }
}
```

**Integration point in DispatcherServlet**:

```java
// Source: org.springframework.web.servlet.DispatcherServlet.doDispatch()
// This is THE central dispatch method. Every HTTP request flows through here.

protected void doDispatch(HttpServletRequest request, HttpServletResponse response)
        throws Exception {

    HttpServletRequest processedRequest = request;
    HandlerExecutionChain mappedHandler = null;
    boolean multipartRequestParsed = false;
    WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);

    try {
        ModelAndView mv = null;
        Exception dispatchException = null;

        try {
            // 1. Check if multipart → parse
            processedRequest = checkMultipart(request);
            multipartRequestParsed = (processedRequest != request);

            // 2. Determine handler (RequestMappingHandlerMapping resolves @RequestMapping)
            mappedHandler = getHandler(processedRequest);
            if (mappedHandler == null) {
                noHandlerFound(processedRequest, response);
                return;
            }

            // 3. Determine handler adapter (RequestMappingHandlerAdapter for @Controller)
            HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());

            // ── 4. PRE-HANDLE: Walk the interceptor chain ──
            //    If any interceptor's preHandle returns false, stop here
            if (!mappedHandler.applyPreHandle(processedRequest, response)) {
                return;  // ← Short-circuit: handler never invoked
            }

            // ── 5. INVOKE HANDLER (through AOP proxy) ──
            //    This is where @Transactional, @Cacheable, @Aspect advice fire
            mv = ha.handle(processedRequest, response, mappedHandler.getHandler());

            // 6. Handle async (if controller returned DeferredResult, Callable, etc.)
            if (asyncManager.isConcurrentHandlingStarted()) {
                return;  // Async → postHandle and afterCompletion handled later
            }

            // 7. Apply default view name (if mv is null and no view set)
            applyDefaultViewName(processedRequest, mv);

            // ── 8. POST-HANDLE: Walk interceptors in REVERSE order ──
            mappedHandler.applyPostHandle(processedRequest, response, mv);

        } catch (Exception ex) {
            dispatchException = ex;
        } catch (Throwable err) {
            dispatchException = new NestedServletException("Handler dispatch failed", err);
        }

        // 9. Process dispatch result (render view, handle exception via HandlerExceptionResolver)
        processDispatchResult(processedRequest, response, mappedHandler, mv,
                (Exception) dispatchException);

    } catch (Exception ex) {
        // ── 10. AFTER-COMPLETION: Always runs, even on exceptions ──
        triggerAfterCompletion(processedRequest, response, mappedHandler, ex);
    } catch (Throwable err) {
        triggerAfterCompletion(processedRequest, response, mappedHandler,
                new NestedServletException("Handler processing failed", err));
    } finally {
        // 11. Clean up multipart resources, restore request attributes
        if (asyncManager.isConcurrentHandlingStarted()) {
            // For async: afterCompletion is called later in async dispatch
            if (mappedHandler != null) {
                mappedHandler.applyAfterConcurrentHandlingStarted(
                        processedRequest, response);
            }
        } else {
            if (multipartRequestParsed) {
                cleanupMultipart(processedRequest);
            }
        }
    }
}

// Helper: triggers afterCompletion in finally block
private void triggerAfterCompletion(HttpServletRequest request,
        HttpServletResponse response,
        @Nullable HandlerExecutionChain mappedHandler, Exception ex) throws Exception {
    if (mappedHandler != null) {
        mappedHandler.triggerAfterCompletion(request, response, ex);
    }
    throw ex;  // Re-throw after cleanup
}
```

### AOP: @Aspect and the Proxy Architecture

AOP in Spring is implemented via dynamic proxies (JDK or CGLIB). Every AOP-annotated bean is wrapped in a chain of `MethodInterceptor` objects. `@Aspect` is a declarative way to define pointcuts and advice — Spring's `AnnotationAwareAspectJAutoProxyCreator` converts `@Aspect` beans into `Advisor` objects that are applied to matching beans.

```java
// Source: org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator
// This is the BeanPostProcessor that creates AOP proxies.
// Called during postProcessAfterInitialization() in the bean lifecycle.

@Override
public Object postProcessAfterInitialization(@Nullable Object bean, String beanName) {
    if (bean != null) {
        Object cacheKey = getCacheKey(bean.getClass(), beanName);
        if (this.earlyProxyReferences.remove(cacheKey) != bean) {
            return wrapIfNecessary(bean, beanName, cacheKey);
        }
    }
    return bean;
}

protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
    // 1. Already wrapped? Skip
    if (StringUtils.hasLength(beanName) && this.targetSourcedBeans.contains(beanName)) {
        return bean;
    }

    // 2. Should this bean be advised?
    if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
        return bean;
    }

    // 3. Is the bean infrastructure (Advice, Pointcut, Advisor)? Skip
    if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName)) {
        this.advisedBeans.put(cacheKey, Boolean.FALSE);
        return bean;
    }

    // ── 4. Find all advisors (PointcutAdvisor, IntroductionAdvisor) ──
    Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(
            bean.getClass(), beanName, null);

    if (specificInterceptors != DO_NOT_PROXY) {
        // ── 5. Create the proxy ──
        this.advisedBeans.put(cacheKey, Boolean.TRUE);
        Object proxy = createProxy(bean.getClass(), beanName,
                specificInterceptors, new SingletonTargetSource(bean));
        this.proxyTypes.put(cacheKey, proxy.getClass());
        return proxy;  // ← Returns the PROXY, not the original bean
    }

    this.advisedBeans.put(cacheKey, Boolean.FALSE);
    return bean;
}
```

**How @Around, @Before, @After, @AfterReturning, @AfterThrowing map to Spring's advice types**:

```
┌──────────────────────┬──────────────────────────────────┬──────────────────────┐
│ @Aspect Annotation   │ Spring Advice Interface          │ When Executed         │
├──────────────────────┼──────────────────────────────────┼──────────────────────┤
│ @Before              │ MethodBeforeAdvice               │ Before target method  │
│ @After               │ AspectJAfterAdvice (finally)     │ After method (always) │
│ @AfterReturning      │ AfterReturningAdvice             │ After successful ret  │
│ @AfterThrowing       │ AspectJAfterThrowingAdvice       │ After exception       │
│ @Around              │ AspectJAroundAdvice (wraps)      │ Full method wrap      │
└──────────────────────┴──────────────────────────────────┴──────────────────────┘
```

Each advice type is converted into a Spring `AspectJMethodBeforeAdvice`, `AspectJAfterAdvice`, etc. These are then wrapped in a `MethodInterceptor` chain via `ReflectiveMethodInvocation`:

```java
// Source: org.springframework.aop.framework.ReflectiveMethodInvocation

public class ReflectiveMethodInvocation implements ProxyMethodInvocation, Cloneable {

    protected final Object proxy;           // The proxy object
    protected final Object target;          // The actual target bean
    protected final Method method;          // The intercepted method
    protected Object[] arguments;
    private int currentInterceptorIndex = -1;  // Current position in chain
    protected final List<?> interceptorsAndDynamicMethodMatchers;

    @Override
    @Nullable
    public Object proceed() throws Throwable {
        // When currentInterceptorIndex reaches the end of the chain,
        // invoke the actual target method
        if (this.currentInterceptorIndex == this.interceptorsAndDynamicMethodMatchers.size() - 1) {
            return invokeJoinpoint();  // → target.method(args)
        }

        // Get the next interceptor in the chain
        Object interceptorOrInterceptionAdvice =
            this.interceptorsAndDynamicMethodMatchers.get(++this.currentInterceptorIndex);

        // If it's a MethodInterceptor, invoke it directly
        if (interceptorOrInterceptionAdvice instanceof MethodInterceptor methodInterceptor) {
            return methodInterceptor.invoke(this);  // Chain delegation
        }
        // DynamicMethodMatcherPointcutAdvisor (conditional matching)
        else {
            // Evaluate if this advice applies to this specific invocation
            MethodMatcher methodMatcher = ...;
            if (methodMatcher.matches(this.method, targetClass, this.arguments)) {
                return ((MethodInterceptor) ...).invoke(this);
            }
            // No match → skip to next interceptor
            return proceed();
        }
    }

    protected Object invokeJoinpoint() throws Throwable {
        return AopUtils.invokeJoinpointUsingReflection(this.target, this.method, this.arguments);
    }
}
```

**The AOP advice invocation chain (simplified)**:

```
Method Call on Proxy
  │
  ▼
ReflectiveMethodInvocation.proceed()  [index = -1]
  │
  ├── Interceptor 0: @Around advice
  │   └── proceed() → [index = 0]
  │
  ├── Interceptor 1: @Before advice
  │   └── Run advice → proceed() → [index = 1]
  │
  ├── Interceptor 2: @AfterReturning/@AfterThrowing advice (registered together)
  │   └── proceed() → [index = 2]
  │
  ├── Interceptor 3: @After advice (finally-block semantics)
  │   └── proceed() → [index = 3]
  │
  └── invokeJoinpoint() → target.method()  [index = 4, end of chain]
```

### @Transactional Proxy Internals

```java
// Source: org.springframework.transaction.interceptor.TransactionInterceptor
// This is a MethodInterceptor — it wraps the target method in a transaction.

public class TransactionInterceptor extends TransactionAspectSupport
        implements MethodInterceptor, Serializable {

    @Override
    @Nullable
    public Object invoke(MethodInvocation invocation) throws Throwable {
        // The actual target class (behind the proxy):
        Class<?> targetClass = (invocation.getThis() != null ?
                AopUtils.getTargetClass(invocation.getThis()) : null);

        // ── Invoke the transactional logic ──
        return invokeWithinTransaction(invocation.getMethod(), targetClass,
                new CoroutinesInvocationCallback() {
                    @Override
                    @Nullable
                    public Object proceedWithInvocation() throws Throwable {
                        return invocation.proceed();  // → next interceptor or target
                    }
                    // ... coroutine support methods ...
                });
    }

    // The core transactional logic:
    protected Object invokeWithinTransaction(Method method, @Nullable Class<?> targetClass,
            InvocationCallback invocation) throws Throwable {

        // 1. Get TransactionAttribute (@Transactional settings)
        TransactionAttributeSource tas = getTransactionAttributeSource();
        TransactionAttribute txAttr = (tas != null ?
                tas.getTransactionAttribute(method, targetClass) : null);

        // 2. Get the TransactionManager
        TransactionManager tm = determineTransactionManager(txAttr);

        // 3. If reactive transaction manager, handle differently (WebFlux)
        if (tm instanceof ReactiveTransactionManager) { /* ... */ }

        // 4. Get the PlatformTransactionManager
        PlatformTransactionManager ptm = asPlatformTransactionManager(tm);

        // 5. Build the joinpoint identification (method + target class)
        final String joinpointIdentification = methodIdentification(method, targetClass, txAttr);

        // ── 6. Declarative transaction (standard @Transactional) ──
        TransactionInfo txInfo = createTransactionIfNecessary(ptm, txAttr,
                joinpointIdentification);

        Object retVal;
        try {
            // ── 7. Proceed with the actual business logic ──
            retVal = invocation.proceedWithInvocation();
            //    ↑ This calls the next interceptor in the chain (or the target method)
            //      The next interceptor could be @Cacheable, @Async, custom @Aspect
        } catch (Throwable ex) {
            // ── 8. Exception → rollback (if rollback rules match) ──
            completeTransactionAfterThrowing(txInfo, ex);
            throw ex;
        } finally {
            cleanupTransactionInfo(txInfo);
        }

        // 9. No exception → potentially still rollback (if marked for rollback)
        if (retVal != null && vavrPresent && VavrDelegate.isVavrTrySuccess(retVal)) {
            // Vavr Try monad support
            txObject.setRollbackOnly();
        }

        // ── 10. Commit transaction ──
        commitTransactionAfterReturning(txInfo);
        return retVal;
    }
}
```

### @Async Proxy Internals

```java
// Source: org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor
// This BPP creates proxies for @Async methods.

// The actual advice is AsyncExecutionInterceptor:
// Source: org.springframework.aop.interceptor.AsyncExecutionInterceptor

public class AsyncExecutionInterceptor extends AsyncExecutionAspectSupport
        implements MethodInterceptor, Ordered {

    @Override
    @Nullable
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        // Get the target class and method
        Class<?> targetClass = (invocation.getThis() != null ?
                AopUtils.getTargetClass(invocation.getThis()) : null);
        Method method = invocation.getMethod();

        // ── Submit to async executor ──
        // Returns: Future, CompletableFuture, ListenableFuture, or null (void)
        return doSubmit(invocation, getExecutorQualifier(method),
                determineAsyncExecutor(method));
    }

    protected Object doSubmit(MethodInvocation invocation, AsyncTaskExecutor executor,
            @Nullable Method method) throws Throwable {

        // Determine return type
        Class<?> returnType = (method != null ? method.getReturnType() :
                invocation.getMethod().getReturnType());

        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            // CompletableFuture: use supplyAsync
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return invocation.proceed();  // ← Runs in worker thread
                } catch (Throwable ex) {
                    throw new CompletionException(ex);
                }
            }, executor);
        }
        else if (ListenableFuture.class.isAssignableFrom(returnType)) {
            // ListenableFuture: use ListenableFutureTask
            return ((AsyncListenableTaskExecutor) executor).submitListenable(() -> {
                try {
                    return invocation.proceed();  // ← Runs in worker thread
                } catch (Throwable ex) {
                    throw new CompletionException(ex);
                }
            });
        }
        else if (Future.class.isAssignableFrom(returnType)) {
            // Future: use standard executor submission
            return executor.submit(() -> {
                try {
                    return invocation.proceed();  // ← Runs in worker thread
                } catch (Throwable ex) {
                    throw new CompletionException(ex);
                }
            });
        }
        else {
            // void return → fire and forget
            executor.submit(() -> {
                try {
                    invocation.proceed();  // ← Runs in worker thread
                } catch (Throwable ex) {
                    throw new CompletionException(ex);
                }
            });
            return null;  // Return immediately to caller
        }
    }
}
```

### @Cacheable Proxy Internals

```java
// Source: org.springframework.cache.interceptor.CacheInterceptor
// This is a MethodInterceptor — wraps the target method in caching logic.

public class CacheInterceptor extends CacheAspectSupport
        implements MethodInterceptor, Serializable {

    @Override
    @Nullable
    public Object invoke(final MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // ── Wrap the invocation in a CacheOperationInvoker ──
        CacheOperationInvoker aopAllianceInvoker = () -> {
            try {
                return invocation.proceed();  // → next interceptor or target
            } catch (Throwable ex) {
                throw new CacheOperationInvoker.ThrowableWrapper(ex);
            }
        };

        Object target = invocation.getThis();
        try {
            // ── Execute cache operations (@Cacheable, @CachePut, @CacheEvict) ──
            return execute(aopAllianceInvoker, target, method, invocation.getArguments());
            // Inside execute():
            //   1. Check @Cacheable: compute key → lookup cache
            //      a. Cache hit → return cached value (skip method invocation)
            //      b. Cache miss → invoke method → store in cache → return result
            //   2. Check @CachePut: invoke method → store in cache → return result
            //   3. Check @CacheEvict: invoke method → evict from cache → return result
        } catch (CacheOperationInvoker.ThrowableWrapper th) {
            throw th.getOriginal();
        }
    }
}
```

### The Proxy Chain Architecture: How Multiple Proxies Stack

When a single bean has multiple AOP annotations (`@Transactional`, `@Cacheable`, `@Async`, custom `@Aspect`), the proxies are NOT merged into a single proxy. Instead, **each `BeanPostProcessor` creates a SEPARATE proxy wrapping the previous one**. The final bean stored in `singletonObjects` is the outermost proxy of the chain.

```
The proxy wrapping sequence (order determined by BeanPostProcessor registration order):

@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)  // wraps SECOND
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE)                    // wraps FIRST (innermost)

Step 1: Bean created → OrderService@4f3c (raw target)
Step 2: AbstractAutoProxyCreator (for @Transactional) 
        → wraps OrderService@4f3c in TransactionProxy
        → TransactionProxy.target = OrderService@4f3c
Step 3: AbstractAutoProxyCreator (for @Cacheable)
        → wraps TransactionProxy in CacheProxy
        → CacheProxy.target = TransactionProxy

Final bean in singletonObjects:
  CacheProxy (CGLIB$$0)
    └── target → TransactionProxy (CGLIB$$1)
        └── target → OrderService@4f3c (raw)

Request flow through the proxy chain:
  caller → CacheProxy.invoke()
              → CacheInterceptor:
                  check cache → MISS
                  → target.invoke()  [TransactionProxy]
                      → TransactionInterceptor:
                          begin tx
                          → target.invoke()  [OrderService@4f3c]
                              → OrderService.placeOrder()
                          ← result
                          ← commit tx
              ← store in cache
              ← return result
```

**The ordering problem**: If `@Cacheable` wraps `@Transactional` (outer proxy), the cache is checked BEFORE the transaction begins. If the cache is populated BEFORE the transaction commits, and the transaction rolls back, the cache contains data that was never committed. This is the canonical "Cache then Transaction" bug.

The fix: `@EnableCaching(order = 1)` and `@EnableTransactionManagement(order = 0)` to reverse the proxy order, so the transaction wraps the cache.

### WebMvcConfigurer and Interceptor Registration

```java
// Source: org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequestLoggingInterceptor())
                .order(Ordered.HIGHEST_PRECEDENCE)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health", "/api/metrics");

        registry.addInterceptor(new RateLimitInterceptor())
                .order(Ordered.HIGHEST_PRECEDENCE + 10)
                .addPathPatterns("/api/**");

        registry.addInterceptor(new TenantContextInterceptor())
                .order(Ordered.HIGHEST_PRECEDENCE + 20)
                .addPathPatterns("/**");
    }
}

// How interceptors are registered (internal):
// WebMvcConfigurationSupport (or DelegatingWebMvcConfiguration) calls:
//   getInterceptors(registry) → calls user's addInterceptors()
// The InterceptorRegistry accumulates InterceptorRegistration objects
// These are converted to MappedInterceptor beans during configuration
// MappedInterceptor wraps the HandlerInterceptor with URL pattern matching:
//
// public class MappedInterceptor implements HandlerInterceptor {
//     private final List<String> includePatterns;
//     private final List<String> excludePatterns;
//     private final HandlerInterceptor interceptor;
//
//     @Override
//     public boolean preHandle(request, response, handler) {
//         // Check if path matches include patterns and NOT exclude patterns
//         if (matches(request)) {
//             return interceptor.preHandle(request, response, handler);
//         }
//         return true;  // Doesn't match → skip this interceptor
//     }
// }
```

## 4. Runtime Behavior

### Complete Request Processing Trace

```
Request: GET /api/orders/12345

[1] Tomcat accepts TCP connection
    ├── CoyoteAdapter.service(request, response)
    └── Creates ApplicationFilterChain(pos=0, n=7)

[2] ApplicationFilterChain.internalDoFilter() [pos=0]
    ├── Filter 0: CharacterEncodingFilter.doFilter()
    │   ├── request.setCharacterEncoding("UTF-8")
    │   ├── chain.doFilter(req, resp) → [pos=1]
    │   └── (post-processing: nothing)
    │
    ├── Filter 1: CorsFilter.doFilter()
    │   ├── Check Origin header → set CORS response headers
    │   ├── chain.doFilter(req, resp) → [pos=2]
    │   └── (post-processing: nothing)
    │
    ├── Filter 2: RequestLoggingFilter (OncePerRequestFilter)
    │   ├── Check "requestLoggingFilter.FILTERED" attribute → null → MARK
    │   ├── doFilterInternal():
    │   │   ├── MDC.put("requestId", UUID.randomUUID())
    │   │   ├── log.info("→ GET /api/orders/12345")
    │   │   ├── chain.doFilter(req, resp) → [pos=3]
    │   │   ├── log.info("← 200 OK (45ms)")
    │   │   └── MDC.clear()
    │   └── (post-processing: nothing)
    │
    ├── Filter 3: DelegatingFilterProxy → springSecurityFilterChain (dozens of security filters)
    │   ├── SecurityContextPersistenceFilter: load SecurityContext
    │   ├── UsernamePasswordAuthenticationFilter: authenticate
    │   ├── FilterSecurityInterceptor: authorize
    │   ├── chain.doFilter(req, resp) → [pos=4]
    │   └── ExceptionTranslationFilter: convert auth exceptions to 401/403
    │
    ├── Filter 4: FormContentFilter (parses form data for PUT/PATCH/DELETE)
    │   └── chain.doFilter(req, resp) → [pos=5]
    │
    ├── Filter 5: HiddenHttpMethodFilter (_method parameter → POST→PUT/PATCH/DELETE)
    │   └── chain.doFilter(req, resp) → [pos=6]
    │
    └── [pos=6] → All filters exhausted → servlet.service()

[3] DispatcherServlet.service() → doDispatch() ──────────────────────────

    ├── getHandler(request):
    │   └── RequestMappingHandlerMapping.getHandler()
    │       → Match "/api/orders/{id}" → OrderController.getOrder(Long id)
    │       → Wraps in HandlerExecutionChain {
    │             handler = HandlerMethod(OrderController.getOrder)
    │             interceptors = [RequestLoggingInterceptor, RateLimitInterceptor]
    │             interceptorIndex = -1
    │          }

    ├── getHandlerAdapter(handler):
    │   └── RequestMappingHandlerAdapter (supports @RequestMapping methods)

    ├── mappedHandler.applyPreHandle(request, response):
    │   ├── Interceptor[0]: RequestLoggingInterceptor.preHandle()
    │   │   ├── MDC.put("handlerMethod", "OrderController.getOrder")
    │   │   └── return true  → interceptorIndex = 0
    │   │
    │   └── Interceptor[1]: RateLimitInterceptor.preHandle()
    │       ├── Check rate limit for client IP
    │       ├── Under limit → return true  → interceptorIndex = 1
    │       └── Over limit → response.sendError(429); return false
    │           → triggerAfterCompletion() for Interceptor[0] and [1]
    │           → doDispatch returns early (handler never called)

    ├── ha.handle(request, response, handler):
    │   │  // This calls through the AOP proxy chain to the controller:
    │   │
    │   ├── CacheProxy(CGLIB$$0).getOrder(12345)
    │   │   └── CacheInterceptor.invoke(invocation):
    │   │       ├── Compute cache key: "orders::12345"
    │   │       ├── cacheManager.getCache("orders").get("orders::12345")
    │   │       ├── CACHE MISS → invocation.proceed()
    │   │       │
    │   │       ├── TransactionProxy(CGLIB$$1).getOrder(12345)
    │   │       │   └── TransactionInterceptor.invoke(invocation):
    │   │       │       ├── PlatformTransactionManager.getTransaction()
    │   │       │       │   ├── DataSourceTransactionManager.doBegin()
    │   │       │       │   │   ├── connection = DataSource.getConnection()
    │   │       │       │   │   ├── connection.setAutoCommit(false)
    │   │       │       │   │   └── TransactionSynchronizationManager.bindResource()
    │   │       │       │   └── return TransactionStatus (new transaction)
    │   │       │       │
    │   │       │       ├── invocation.proceed():
    │   │       │       │   └── OrderService.getOrder(12345)  ← TARGET METHOD
    │   │       │       │       ├── orderRepository.findById(12345)
    │   │       │       │       │   └── JPA → SQL: SELECT * FROM orders WHERE id=12345
    │   │       │       │       └── return Order{id=12345, status=SHIPPED}
    │   │       │       │
    │   │       │       ├── commitTransactionAfterReturning(txInfo):
    │   │       │       │   ├── connection.commit()
    │   │       │       │   └── TransactionSynchronizationManager.unbindResource()
    │   │       │       └── return Order{id=12345, status=SHIPPED}
    │   │       │
    │   │       ├── cache.put("orders::12345", order)  // store in cache AFTER commit
    │   │       └── return order
    │   │
    │   └── Back in RequestMappingHandlerAdapter:
    │       └── If @ResponseBody: HttpMessageConverter.write(order, response)
    │           → Jackson ObjectMapper serializes order to JSON
    │           → writes to response.getOutputStream()

    ├── mappedHandler.applyPostHandle(request, response, null):
    │   ├── Interceptor[1]: RateLimitInterceptor.postHandle()
    │   │   └── (called only for non-@ResponseBody, so may be SKIPPED)
    │   │       NOTE: postHandle is NOT called for @ResponseBody because
    │   │       the response body is written before postHandle is reached.
    │   │       For REST APIs, use afterCompletion instead.
    │   │
    │   └── Interceptor[0]: RequestLoggingInterceptor.postHandle()
    │       └── (same caveat)

    └── mappedHandler.triggerAfterCompletion(request, response, null):
        ├── Interceptor[1]: RateLimitInterceptor.afterCompletion()
        │   └── Clean up rate limit counters
        └── Interceptor[0]: RequestLoggingInterceptor.afterCompletion()
            └── MDC.clear()  // final cleanup

[4] Filter chain resumes (post-doFilter):
    ├── Filter 2: RequestLoggingFilter → MDC.clear (already done)
    ├── Filter 1: CorsFilter → nothing
    └── Filter 0: CharacterEncodingFilter → nothing

[5] Response committed → TCP connection closed (or kept alive)
```

### What Happens When preHandle Returns false

When an interceptor's `preHandle` returns `false`, the dispatcher stops processing immediately:

```
mappedHandler.applyPreHandle():
  Interceptor[0].preHandle() → true   (interceptorIndex = 0)
  Interceptor[1].preHandle() → false  (returns false → SHORT CIRCUIT)
  
  → triggerAfterCompletion(request, response, null):
      Interceptor[1].afterCompletion()  ← Called even though preHandle was false!
      Interceptor[0].afterCompletion()

  → return false

Back in doDispatch():
  if (!mappedHandler.applyPreHandle(...)) {
      return;  // ← Handler NEVER called. Filters continue post-doFilter.
  }

CRITICAL: The HTTP response code is WHATEVER was set by the interceptor
that returned false. If it didn't set a status code, the response defaults
to 200 OK with an empty body.

Best practice: The interceptor that returns false MUST set the response
status and body BEFORE returning false:
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
    return false;
```

### What Happens When afterCompletion Is Called After an Exception

Even when an exception propagates all the way up, `triggerAfterCompletion` is called in a `finally` block:

```
doDispatch():
  try {
      mappedHandler.applyPreHandle() → true
      ha.handle() → throws RuntimeException!
      
      // mappedHandler.applyPostHandle() NEVER CALLED
      // The exception propagates up

  } catch (Exception ex) {
      // processDispatchResult → HandlerExceptionResolver chain
      // If @ExceptionHandler catches it → handled, response set
  }

  finally {
      // ── ALWAYS EXECUTED ──
      mappedHandler.triggerAfterCompletion(request, response, ex);
      // The 'ex' parameter is the ORIGINAL exception (or null if no exception)
      // This allows afterCompletion to differentiate success from failure
  }
```

## 5. Request Flow Diagrams

### Normal Flow: Filter → DispatcherServlet → Interceptor → AOP → Controller

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      NORMAL REQUEST FLOW (200 OK)                         │
│                                                                          │
│  CLIENT                                                                  │
│    │                                                                     │
│    │  GET /api/orders/12345                                              │
│    ▼                                                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                    APPLICATION FILTER CHAIN                        │   │
│  │                                                                    │   │
│  │  Filter₁.doFilter()    Filter₂.doFilter()    Filter₃.doFilter()    │   │
│  │  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐      │   │
│  │  │ pre-process  │     │ pre-process  │     │ pre-process  │      │   │
│  │  │              │     │              │     │              │      │   │
│  │  │ chain.doFltr │────►│ chain.doFltr │────►│ chain.doFltr │──┐   │   │
│  │  │              │     │              │     │              │  │   │   │
│  │  │ post-process │◄────│ post-process │◄────│ post-process │  │   │   │
│  │  └──────────────┘     └──────────────┘     └──────────────┘  │   │   │
│  └───────────────────────────────────────────────────────────────┼───┘   │
│                                                                  │       │
│                                                                  ▼       │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                   DispatcherServlet.doDispatch()                   │   │
│  │                                                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │  HandlerExecutionChain.applyPreHandle()                      │ │   │
│  │  │                                                              │ │   │
│  │  │  Interceptor₁.preHandle() → true                             │ │   │
│  │  │  Interceptor₂.preHandle() → true                             │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  │                              │                                    │   │
│  │                              ▼                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │  ha.handle(request, response, handler)                       │ │   │
│  │  │  ┌──────────────────────────────────────────────────────┐   │ │   │
│  │  │  │               AOP PROXY STACK                         │   │ │   │
│  │  │  │                                                       │   │ │   │
│  │  │  │  @Cacheable proxy                                     │   │ │   │
│  │  │  │    ├── Check cache → MISS                             │   │ │   │
│  │  │  │    └── proceeed() ↓                                   │   │ │   │
│  │  │  │                                                       │   │ │   │
│  │  │  │  @Transactional proxy                                 │   │ │   │
│  │  │  │    ├── begin tx                                       │   │ │   │
│  │  │  │    └── proceeed() ↓                                   │   │ │   │
│  │  │  │                                                       │   │ │   │
│  │  │  │  Controller.getOrder(Long id)                         │   │ │   │
│  │  │  │    └── return Order → ↑                               │   │ │   │
│  │  │  │                                                       │   │ │   │
│  │  │  │  @Transactional proxy                                 │   │ │   │
│  │  │  │    └── commit tx ↑                                    │   │ │   │
│  │  │  │                                                       │   │ │   │
│  │  │  │  @Cacheable proxy                                     │   │ │   │
│  │  │  │    └── put in cache ↑ → return Order                  │   │ │   │
│  │  │  └──────────────────────────────────────────────────────┘   │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  │                              │                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │  HandlerExecutionChain.applyPostHandle() (REVERSE ORDER)     │ │   │
│  │  │    Interceptor₂.postHandle()                                 │ │   │
│  │  │    Interceptor₁.postHandle()                                 │ │   │
│  │  │  (May be skipped for @ResponseBody — response already written)│ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  │                                                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │  HandlerExecutionChain.triggerAfterCompletion() (REVERSE)    │ │   │
│  │  │    Interceptor₂.afterCompletion(ex=null)                     │ │   │
│  │  │    Interceptor₁.afterCompletion(ex=null)                     │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  FILTER CHAIN RESUMES (post-doFilter callbacks in reverse order)         │
│    Filter₃ → Filter₂ → Filter₁                                           │
│                                                                         │
│  RESPONSE COMMITTED (200 OK, body = JSON)                               │
└──────────────────────────────────────────────────────────────────────────┘
```

### Exception Flow Through the Layers

```
┌──────────────────────────────────────────────────────────────────────────┐
│                   EXCEPTION FLOW (RuntimeException)                        │
│                                                                          │
│  Filter₁.doFilter() starts                                               │
│  Filter₂.doFilter() starts                                                │
│  Filter₃.doFilter() starts                                                │
│    └── chain.doFilter() → DispatcherServlet                              │
│                                                                          │
│  DispatcherServlet.doDispatch():                                         │
│    applyPreHandle() → all true                                           │
│    ha.handle():                                                           │
│      @Cacheable proxy: check cache → MISS → proceed()                    │
│      @Transactional proxy: begin tx → proceed()                          │
│      Controller.getOrder(): → throws RuntimeException!                   │
│                                                                          │
│      @Transactional proxy:                                               │
│        ← catch exception                                                 │
│        ← check rollback rules (RuntimeException → ROLLBACK)              │
│        ← connection.rollback()                                           │
│        ← RE-THROW exception                                              │
│                                                                          │
│      @Cacheable proxy:                                                   │
│        ← catch exception                                                 │
│        ← does NOT cache the result                                       │
│        ← RE-THROW exception                                              │
│                                                                          │
│    Back in DispatcherServlet:                                            │
│      applyPostHandle() → NOT CALLED (exception occurred before)          │
│                                                                          │
│    processDispatchResult():                                               │
│      → Iterate HandlerExceptionResolvers:                                │
│        → ExceptionHandlerExceptionResolver:                              │
│          → Find @ExceptionHandler(RuntimeException.class)                │
│          → ControllerAdvice.handleRuntimeException()                     │
│          → Returns ErrorResponse{500, "Internal error"}                  │
│          → ModelAndView with error response                              │
│        → Serialize to JSON via HttpMessageConverter                      │
│        → response.setStatus(500)                                         │
│        → Write JSON body to response                                     │
│                                                                          │
│    triggerAfterCompletion(request, response, originalException):          │
│      Interceptor₂.afterCompletion(ex=RuntimeException)                   │
│      Interceptor₁.afterCompletion(ex=RuntimeException)                   │
│                                                                          │
│  Filter chain resumes (post-doFilter):                                   │
│    Filter₃: sees 500 response (no error, just post-processing)           │
│    Filter₂: sees 500 response                                            │
│    Filter₁: sees 500 response                                            │
│                                                                          │
│  RESPONSE COMMITTED (500 Internal Server Error)                           │
└──────────────────────────────────────────────────────────────────────────┘
```

### @Async Flow Showing Thread Handoff

```
┌──────────────────────────────────────────────────────────────────────────┐
│                   @Async FLOW WITH THREAD HANDOFF                          │
│                                                                          │
│  HTTP Thread (tomcat-http-1):                                            │
│                                                                          │
│    Filter chain → DispatcherServlet → interceptors →                     │
│                                                                          │
│    ┌─────────────────────────────────────────────────────┐              │
│    │  @Async PROXY (AsyncExecutionInterceptor)            │              │
│    │                                                      │              │
│    │  invoke(invocation):                                 │              │
│    │    ├── Get async executor (ThreadPoolTaskExecutor)   │              │
│    │    ├── READ ThreadLocal state:                       │              │
│    │    │   ├── SecurityContext (current user)            │              │
│    │    │   ├── RequestAttributes (current request)       │              │
│    │    │   ├── MDC context (traceId, userId)             │              │
│    │    │   └── TransactionSynchronizationManager         │              │
│    │    │       (current transaction — SUSPENDED)         │              │
│    │    │                                                │              │
│    │    ├── Submit to executor:                           │              │
│    │    │   executor.submit(() -> {                       │              │
│    │    │       // ⚠ NEW THREAD: task-scheduler-3        │              │
│    │    │       // ThreadLocal state is GONE!             │              │
│    │    │       // SecurityContext → null                 │              │
│    │    │       // MDC → empty                            │              │
│    │    │       // Transaction → none                     │              │
│    │    │       invocation.proceed() → target.method()    │              │
│    │    │   })                                            │              │
│    │    │                                                │              │
│    │    └── Return immediately:                           │              │
│    │        ├── void → null (fire and forget)             │              │
│    │        ├── Future<T> → Future object (check later)   │              │
│    │        └── CompletableFuture<T> → future object      │              │
│    └─────────────────────────────────────────────────────┘              │
│                                                                          │
│  HTTP Thread continues:                                                   │
│    Interceptors postHandle/afterCompletion → MDC.clear()                 │
│    Filter chain post-processing → return response                        │
│    Response sent to client (202 Accepted or 200 OK)                      │
│                                                                          │
│  ─────────── THREAD BOUNDARY ───────────                                 │
│                                                                          │
│  Worker Thread (task-scheduler-3):                                       │
│                                                                          │
│    ThreadLocal state: EMPTY                                              │
│      ├── SecurityContextHolder.getContext() → ANONYMOUS                  │
│      ├── MDC.get("traceId") → null                                       │
│      ├── RequestContextHolder.getRequestAttributes() → null              │
│      └── TransactionSynchronizationManager.getCurrentTransaction() → null│
│                                                                          │
│    invocation.proceed():                                                 │
│      ├── If method has @Transactional: NONE → OK, new transaction        │
│      ├── If method logs: MDC is empty → no trace IDs in logs ⚠          │
│      ├── If method reads SecurityContext → ANONYMOUS (not caller!) ⚠    │
│      └── Method executes → result/future completes                      │
│                                                                          │
│  FIX: Propagate context to worker thread:                                │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  TaskDecorator decorator = task -> {                            │    │
│  │      // Capture context from HTTP thread                         │    │
│  │      Map<String, String> mdcContext = MDC.getCopyOfContextMap(); │    │
│  │      SecurityContext secCtx = SecurityContextHolder.getContext();│    │
│  │      RequestAttributes reqAttrs =                                │    │
│  │          RequestContextHolder.getRequestAttributes();            │    │
│  │                                                                  │    │
│  │      return () -> {                                              │    │
│  │          // Restore context in worker thread                     │    │
│  │          MDC.setContextMap(mdcContext);                           │    │
│  │          SecurityContextHolder.setContext(secCtx);                │    │
│  │          RequestContextHolder.setRequestAttributes(reqAttrs);    │    │
│  │          try {                                                    │    │
│  │              task.run();                                         │    │
│  │          } finally {                                             │    │
│  │              MDC.clear();                                        │    │
│  │              SecurityContextHolder.clearContext();               │    │
│  │              RequestContextHolder.resetRequestAttributes();      │    │
│  │          }                                                       │    │
│  │      };                                                          │    │
│  │  };                                                              │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

## 6. Lifecycle Diagrams

### Filter Lifecycle in the Servlet Container

```
┌──────────────────────────────────────────────────────────────────────┐
│                    FILTER LIFECYCLE                                    │
│                                                                      │
│  1. CONFIGURATION (application startup)                              │
│     ├── FilterRegistrationBean registers filter with ServletContext  │
│     ├── FilterConfig created (init params, filter name)              │
│     └── Added to StandardContext.filterDefs                          │
│                                                                      │
│  2. INITIALIZATION (first filter chain creation or eager init)       │
│     ┌────────────────────────────────────────────────────────────┐  │
│     │  Filter.init(FilterConfig)                                 │  │
│     │    → GenericFilterBean.afterPropertiesSet()                │  │
│     │    → OncePerRequestFilter: (no special init)               │  │
│     │    → Security filter chain: builds internal filter list    │  │
│     │    → Custom filters: open resources, init caches           │  │
│     └────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  3. PER-REQUEST (for every HTTP request)                             │
│     ├── ApplicationFilterChain created (per-request)                 │
│     ├── Filters invoked in order (pos counter)                       │
│     └── After chain completion: filter objects REUSED next request   │
│                                                                      │
│  4. DESTRUCTION (application shutdown)                               │
│     ┌────────────────────────────────────────────────────────────┐  │
│     │  Filter.destroy()                                          │  │
│     │    → Close resources, release caches                       │  │
│     │    → GenericFilterBean calls destroy() on super            │  │
│     └────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  IMPORTANT: Servlet spec guarantees single init()/destroy() per      │
│  filter instance, but doFilter() may be called CONCURRENTLY from     │
│  multiple threads. Filters MUST be thread-safe for the per-request   │
│  phase.                                                               │
└──────────────────────────────────────────────────────────────────────┘
```

### Interceptor Registration Lifecycle

```
┌──────────────────────────────────────────────────────────────────────┐
│                INTERCEPTOR LIFECYCLE (via WebMvcConfigurer)            │
│                                                                      │
│  Refresh Step 5 (invokeBeanFactoryPostProcessors):                    │
│    ├── @Configuration class WebConfig parsed                          │
│    ├── WebMvcConfigurationSupport detected @Bean methods              │
│    └── requestMappingHandlerMapping bean definition registered        │
│                                                                      │
│  Refresh Step 11 (finishBeanFactoryInitialization):                   │
│    ├── WebMvcConfigurationSupport.getInterceptors() called            │
│    │   ├── DelegatingWebMvcConfiguration extends this                 │
│    │   │   └── Injects all WebMvcConfigurer beans                     │
│    │   └── For each WebMvcConfigurer:                                 │
│    │       └── configurer.addInterceptors(registry)                   │
│    │           └── User code adds interceptors with URL patterns      │
│    │           └── InterceptorRegistration objects accumulated        │
│    │                                                                  │
│    ├── InterceptorRegistration → MappedInterceptor beans              │
│    │   └── Each mapped interceptor includes:                          │
│    │       ├── HandlerInterceptor instance                            │
│    │       ├── includePatterns (URL patterns)                         │
│    │       └── excludePatterns                                        │
│    │                                                                  │
│    └── requestMappingHandlerMapping.setInterceptors(interceptors)     │
│        └── Stored for use during getHandler()                         │
│                                                                      │
│  During request processing:                                            │
│    ├── getHandler(request) → HandlerExecutionChain(handler)          │
│    ├── For each mapped interceptor:                                   │
│    │   └── if (matches request path) → add to chain                   │
│    └── Applied during doDispatch() → preHandle/postHandle/afterComp   │
│                                                                      │
│  DESTRUCTION: No explicit destruction lifecycle for interceptors.     │
│  They follow the singleton bean lifecycle (destroyed with context).   │
└──────────────────────────────────────────────────────────────────────┘
```

### AOP Proxy Creation Lifecycle

```
┌──────────────────────────────────────────────────────────────────────┐
│              AOP PROXY CREATION LIFECYCLE (BeanPostProcessor)          │
│                                                                      │
│  Refresh Step 6 (registerBeanPostProcessors):                         │
│    ├── AnnotationAwareAspectJAutoProxyCreator registered              │
│    │   (for @Aspect annotations)                                     │
│    ├── InfrastructureAdvisorAutoProxyCreator registered               │
│    │   (for @Transactional, @Cacheable, @Async)                      │
│    └── Order determined by PriorityOrdered / Ordered interfaces      │
│                                                                      │
│  Refresh Step 11 (bean instantiation):                                │
│    │                                                                  │
│    ├── createBean("orderService")                                    │
│    │   ├── createBeanInstance() → OrderService@4f3c (raw object)     │
│    │   ├── populateBean() → inject dependencies                       │
│    │   └── initializeBean() → BeanPostProcessors fire                │
│    │                                                                  │
│    │       ┌──────────────────────────────────────────────────────┐  │
│    │       │  BeanPostProcessor 1: @Transactional creator          │  │
│    │       │    └── wrapIfNecessary(OrderService@4f3c)             │  │
│    │       │        ├── getAdvicesAndAdvisorsForBean() → 1 advisor │  │
│    │       │        └── createProxy() → TransactionProxy@5a1c      │  │
│    │       │            TransactionProxy.target = OrderService@4f3c│  │
│    │       │            (CGLIB subclass of OrderService)           │  │
│    │       └──────────────────────────────────────────────────────┘  │
│    │                                                                  │
│    │       ┌──────────────────────────────────────────────────────┐  │
│    │       │  BeanPostProcessor 2: @Async creator                  │  │
│    │       │    └── wrapIfNecessary(TransactionProxy@5a1c)         │  │
│    │       │        ├── getAdvicesAndAdvisorsForBean() → 1 advisor │  │
│    │       │        └── createProxy() → AsyncProxy@7b2d            │  │
│    │       │            AsyncProxy.target = TransactionProxy@5a1c  │  │
│    │       │            (CGLIB subclass)                            │  │
│    │       └──────────────────────────────────────────────────────┘  │
│    │                                                                  │
│    │       ┌──────────────────────────────────────────────────────┐  │
│    │       │  BeanPostProcessor 3: @Cacheable creator              │  │
│    │       │    └── wrapIfNecessary(AsyncProxy@7b2d)               │  │
│    │       │        ├── getAdvicesAndAdvisorsForBean() → 1 advisor │  │
│    │       │        └── createProxy() → CacheProxy@9c3e            │  │
│    │       │            CacheProxy.target = AsyncProxy@7b2d        │  │
│    │       └──────────────────────────────────────────────────────┘  │
│    │                                                                  │
│    └── addSingleton("orderService", CacheProxy@9c3e)                 │
│        └── The OUTERMOST proxy is what all other beans receive       │
│                                                                      │
│  CRITICAL ORDERING RULE:                                              │
│    @EnableTransactionManagement(order = LOWEST_PRECEDENCE)           │
│    @EnableCaching(order = LOWEST_PRECEDENCE)                         │
│    DEFAULT: Cache wraps Transaction → CACHE FIRST, THEN TX           │
│    OVERRIDE: @EnableCaching(order = 1) + @EnableTxMgmt(order = 0)   │
│              → Transaction wraps Cache → TX FIRST, THEN CACHE        │
└──────────────────────────────────────────────────────────────────────┘
```

## 7. Source Code Reading Guide

### Critical Files — Read In This Order

```
1. ApplicationFilterChain.java (Tomcat)
   org.apache.catalina.core.ApplicationFilterChain
   → internalDoFilter(), the pos counter, how the chain delegates
   → This is the foundation. Understand before Spring's wrappers.

2. OncePerRequestFilter.java (Spring)
   org.springframework.web.filter.OncePerRequestFilter
   → doFilter(), getAlreadyFilteredAttributeName(), skipDispatch()
   → How Spring prevents duplicate filter execution

3. DispatcherServlet.java (Spring MVC)
   org.springframework.web.servlet.DispatcherServlet
   → doDispatch() — THE central dispatch method, ~250 lines
   → Every interceptor and handler invocation flows through here

4. HandlerExecutionChain.java (Spring MVC)
   org.springframework.web.servlet.HandlerExecutionChain
   → applyPreHandle(), applyPostHandle(), triggerAfterCompletion()
   → The interceptor walk with the interceptorIndex counter

5. HandlerInterceptor.java (Spring MVC)
   org.springframework.web.servlet.HandlerInterceptor
   → The three lifecycle methods: preHandle, postHandle, afterCompletion
   → Default methods showing the contract

6. AbstractAutoProxyCreator.java (Spring AOP)
   org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator
   → postProcessAfterInitialization(), wrapIfNecessary()
   → How AOP proxies are created as BeanPostProcessors

7. ReflectiveMethodInvocation.java (Spring AOP)
   org.springframework.aop.framework.ReflectiveMethodInvocation
   → proceed(), the interceptor chain walk, invokeJoinpoint()
   → How AOP advice is chained together

8. TransactionInterceptor.java (Spring TX)
   org.springframework.transaction.interceptor.TransactionInterceptor
   → invoke(), invokeWithinTransaction()
   → How @Transactional wraps method calls in transactions

9. CacheInterceptor.java (Spring Cache)
   org.springframework.cache.interceptor.CacheInterceptor
   → invoke(), execute()
   → How @Cacheable/@CachePut/@CacheEvict intercepts method calls

10. AsyncExecutionInterceptor.java (Spring Async)
    org.springframework.aop.interceptor.AsyncExecutionInterceptor
    → invoke(), doSubmit()
    → How @Async submits method calls to thread pools

11. AnnotationAwareAspectJAutoProxyCreator.java (Spring AOP)
    org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator
    → findCandidateAdvisors(), buildAspectJAdvisors()
    → How @Aspect classes are converted to Advisor chains

12. ProxyFactory.java (Spring AOP)
    org.springframework.aop.framework.ProxyFactory
    → getProxy(), createAopProxy()
    → Decision point: JDK proxy vs CGLIB proxy

13. JdkDynamicAopProxy.java (Spring AOP)
    org.springframework.aop.framework.JdkDynamicAopProxy
    → invoke() — the JDK proxy InvocationHandler
    → How the proxy dispatches to the advisor chain

14. CglibAopProxy.java (Spring AOP)
    org.springframework.aop.framework.CglibAopProxy
    → getProxy(), DynamicAdvisedInterceptor.intercept()
    → How CGLIB proxies handle method interception

15. WebMvcConfigurationSupport.java (Spring MVC)
    org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport
    → getInterceptors(), requestMappingHandlerMapping()
    → How WebMvcConfigurer addInterceptors feeds into the handler mapping

16. InterceptorRegistry.java (Spring MVC)
    org.springframework.web.servlet.config.annotation.InterceptorRegistry
    → addInterceptor(), getInterceptors()
    → The registration API and how MappedInterceptors are created

17. FilterRegistrationBean.java (Spring Boot)
    org.springframework.boot.web.servlet.FilterRegistrationBean
    → configure(), getFilter()
    → How Filter beans become ServletContext filter registrations
```

### Source Code Breadcrumb Trace

```
Request received by Tomcat:
  StandardHostValve.invoke() → StandardContextValve.invoke()
  → StandardWrapperValve.invoke() → ApplicationFilterChain.internalDoFilter()
  
ApplicationFilterChain.internalDoFilter():
  → Filter[0].doFilter(req, resp, chain)
  → Filter[1].doFilter(req, resp, chain)
  → ... → Filter[N].doFilter(req, resp, chain)
  → servlet.service(req, resp)  // HttpServlet.service()
  → DispatcherServlet.doDispatch(request, response)

DispatcherServlet.doDispatch():
  → getHandler(request) → HandlerMapping.getHandler() 
    → AbstractHandlerMapping.getHandler()
      → getHandlerExecutionChain(handler, request)
        → chain.addInterceptors(adaptedInterceptors)
  → getHandlerAdapter(handler)
  → mappedHandler.applyPreHandle(request, response)
    → for each interceptor: interceptor.preHandle(request, response, handler)
  → ha.handle(request, response, handler)
    → RequestMappingHandlerAdapter.handleInternal()
      → invokeHandlerMethod(request, response, handlerMethod)
        → ServletInvocableHandlerMethod.invokeAndHandle()
          → InvocableHandlerMethod.invokeForRequest()
            → Proxied controller method invoked through AOP proxy chain:
              Proxy.invoke() → Advisor chain → Target method
  → mappedHandler.applyPostHandle(request, response, mv)
    → for each interceptor (REVERSE): interceptor.postHandle(...)
  → processDispatchResult(request, response, mappedHandler, mv, exception)
  → triggerAfterCompletion(request, response, mappedHandler, exception)
    → for each interceptor (REVERSE): interceptor.afterCompletion(...)
```

## 8. Production Failure Scenarios

### Scenario 1: @Transactional Silently Failing on Self-Invocation

**Symptom**: Database writes are not rolled back when an unchecked exception is thrown. Application logs show no errors, but data is inconsistent.

**Root cause**: The `@Transactional` method is called from another method in the SAME class via `this.method()`. The call bypasses the AOP proxy entirely — it is a direct method invocation on the raw target object. `TransactionInterceptor` is never invoked, so no transaction is created.

```
// THE BUG:
@Service
public class OrderService {
    public void submitOrder(Order order) {
        // Called by controller → goes through PROXY → transaction starts
        this.createOrder(order);  // ← DIRECT CALL, bypasses proxy!
    }

    @Transactional
    public void createOrder(Order order) {
        orderRepository.save(order);  // NO TRANSACTION!
        // Exception here → changes are PERMANENT
    }
}
```

**Diagnosis**:
```java
// Add to the service to detect proxy bypass:
@PostConstruct
public void logProxy() {
    log.info("Service class: {}", this.getClass().getName());
    // If output is "OrderService" (not "OrderService$$SpringCGLIB$$0"):
    //   → You have the RAW target, self-calls won't be advised
    // If output is "OrderService$$SpringCGLIB$$0":
    //   → You have the proxy, but THIS reference inside a method
    //     still points to the raw target
}

// At runtime, check transaction state:
@Transactional
public void createOrder(Order order) {
    boolean inTx = TransactionSynchronizationManager.isActualTransactionActive();
    log.info("Transaction active? {}", inTx);
    // If false → proxy bypass confirmed
}
```

**Resolution**: Extract the transactional method to a separate service bean, use self-injection (`@Autowired private OrderService self`), or use `AopContext.currentProxy()` with `@EnableAspectJAutoProxy(exposeProxy = true)`.

### Scenario 2: @Async Losing SecurityContext and MDC

**Symptom**: Audit logs from async methods show "ANONYMOUS" user and no trace IDs. Authentication fails inside the async method. Thread-local context from the HTTP request is lost.

**Root cause**: When `@Async` hands off execution to a thread pool, the new worker thread has EMPTY `ThreadLocal` state. `SecurityContextHolder` uses `ThreadLocal` by default (mode `MODE_THREADLOCAL`). `MDC` uses `ThreadLocal`. When the async proxy submits the task to the executor, the HTTP thread's context is left behind.

```
HTTP Thread:                          Task Thread:
  SecurityContext: user@example.com     SecurityContext: null → ANONYMOUS
  MDC: {traceId: abc123}               MDC: {}
  RequestAttributes: {...}              RequestAttributes: null
                                        ↓
                                        controllerMethod() called
                                        → securityContext is null
                                        → audit log: "unknown user"
```

**Diagnosis**:
```java
@Async
public CompletableFuture<Void> processAsync() {
    log.info("SecurityContext: {}", SecurityContextHolder.getContext()
        .getAuthentication());
    log.info("MDC traceId: {}", MDC.get("traceId"));
    // Both will be null/empty
}
```

**Resolution**:
```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    @Override
    @Bean
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        
        // ── Propagate SecurityContext ──
        SecurityContextHolder.setStrategyName(
            SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
        
        // ── Propagate MDC and RequestAttributes ──
        executor.setTaskDecorator(task -> () -> {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            SecurityContext ctx = SecurityContextHolder.getContext();
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            try {
                if (mdc != null) MDC.setContextMap(mdc);
                if (ctx != null) SecurityContextHolder.setContext(ctx);
                if (attrs != null) 
                    RequestContextHolder.setRequestAttributes(attrs);
                task.run();
            } finally {
                MDC.clear();
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        });
        
        executor.initialize();
        return executor;
    }
}
```

### Scenario 3: Multiple AOP Proxies in Wrong Order

**Symptom**: Method with both `@Transactional` and `@Cacheable` sometimes returns stale data after a failed transaction. Cache is populated BEFORE the database transaction commits — if the transaction rolls back, the cache still has the value.

**Root cause**: By default, `@EnableCaching` and `@EnableTransactionManagement` have the same order (`Ordered.LOWEST_PRECEDENCE`). Spring resolves the tie by registration order, which typically puts `@Cacheable` as the OUTER proxy (created first → becomes inner) and `@Transactional` as the INNER proxy (created second → wraps inner). With `CacheProxy` outside, the cache is checked/populated before/after the transaction. A cache-miss triggers the method, which runs inside a transaction, but the cache `put` happens BEFORE the transaction proxy commits.

```
Order: CacheProxy → TransactionProxy → target
Cache miss → method runs → result cached → transaction commits
If transaction rolls back AFTER cache.put: cache is DIRTY (uncommitted data)
```

**Fix 1 — Reverse the proxy order manually**:
```java
@Configuration
@EnableCaching(order = 1)  // Higher order = processed SECOND = wraps inner proxy
@EnableTransactionManagement(order = 0)  // Lower order = processed FIRST = inner proxy
public class AppConfig { }

// Result: TransactionProxy → CacheProxy → target
// Cache is read AFTER transaction begins and written BEFORE transaction commits
```

**Fix 2 — Use programmatic cache management**:
```java
@Service
@Transactional
public class OrderService {
    private final CacheManager cacheManager;
    
    public Order getOrder(Long id) {
        Cache cache = cacheManager.getCache("orders");
        Order cached = cache.get(id, Order.class);
        if (cached != null) return cached;
        
        Order order = orderRepository.findById(id).orElse(null);
        cache.put(id, order);
        return order;
    }
    // Transaction is active for entire method → cache is populated consistently
}
```

### Scenario 4: Filter Buffering Request Body Before Controller Can Read It

**Symptom**: A filter that logs the request body causes the controller to see an empty body. `@RequestBody` fails with `HttpMessageNotReadableException: Required request body is missing`.

**Root cause**: `HttpServletRequest.getReader()` and `getInputStream()` can be called ONCE. After the first call consumes the stream, subsequent calls see an empty stream. A filter that reads the body for logging MUST buffer it and make the buffered copy available to downstream consumers.

```java
// THE BUG:
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        // Read the body for logging:
        String body = request.getReader().lines()
            .collect(Collectors.joining("\n"));
        log.info("Request body: {}", body);
        // BUG: The InputStream is now exhausted!
        // The controller will get an empty body.
        
        chain.doFilter(request, response);
    }
}
```

**Resolution**: Use `ContentCachingRequestWrapper` (wraps the original request, caches the body in a byte array, and provides a new `getInputStream()` for each call):

```java
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        // WRAP the request to cache the body:
        ContentCachingRequestWrapper wrappedRequest = 
            new ContentCachingRequestWrapper(request);
        
        chain.doFilter(wrappedRequest, response);  // Pass the WRAPPED request
        
        // AFTER the chain completes, the body has been cached:
        byte[] body = wrappedRequest.getContentAsByteArray();
        log.info("Request body: {}", new String(body, StandardCharsets.UTF_8));
    }
}

// IMPORTANT: ContentCachingRequestWrapper caches the body only AFTER
// it's been read. So you read it AFTER chain.doFilter(), not before.
// If you need to read the body BEFORE the chain, use a two-pass approach
// with a custom wrapper or read the body, cache it, then create a new
// request with the cached body via RequestBodyReaderWrapper.
```

### Scenario 5: postHandle Not Called for @ResponseBody Controllers

**Symptom**: An interceptor's `postHandle` logic (e.g., adding response headers, logging response time) never executes for REST API endpoints. But `preHandle` and `afterCompletion` do execute.

**Root cause**: `postHandle` is called AFTER the handler returns but BEFORE the view renders. For `@ResponseBody` methods, the response body is written directly to the `HttpServletResponse.getOutputStream()` DURING handler execution (inside `HttpMessageConverter.write()`). By the time `applyPostHandle` is called, the response is already committed. Spring skips `postHandle` in certain circumstances to avoid `IllegalStateException` from modifying a committed response.

```
For @ResponseBody endpoints:
  HandlerAdapter.handle() → writes JSON directly to response output stream
  → Response committed at this point
  → postHandle is a NO-OP (response already flushed)
  
For view-based endpoints (JSP, Thymeleaf):
  HandlerAdapter.handle() → returns ModelAndView
  → postHandle fires → can modify ModelAndView
  → ViewResolver resolves view → renders HTML
  → afterCompletion fires
```

**Resolution**: Use `afterCompletion` for any logic that needs to run after the response. For timing, capture the start time in `preHandle` (store in request attribute), compute elapsed time in `afterCompletion`.

### Scenario 6: ThreadLocal Leak in @Async Thread Pool

**Symptom**: After processing `@Async` tasks, the thread pool threads retain references to large request objects. Over time, heap memory grows unbounded, eventually causing `OutOfMemoryError`.

**Root cause**: A `ThreadLocal` set during async method execution is never cleared. Common culprits: database connection ThreadLocals (from ORMs), security contexts that aren't cleared, or custom request-scoped ThreadLocals. Unlike HTTP request threads (where the container typically cleans up), thread pool threads are reused indefinitely.

**Resolution**: Always clean up ThreadLocals in a finally block. Use `TaskDecorator` to wrap every async task with cleanup logic. Consider using `InheritableThreadLocal` carefully (it copies parent values on thread creation, but doesn't clear them after use).

## 9. Debugging Techniques

### Identifying AOP Proxy Class Names in Stack Traces

When an exception is thrown from a proxied bean, the stack trace reveals the proxy chain:

```
// STACK TRACE SHOWING PROXY CHAIN:
Caused by: java.lang.RuntimeException: Order not found
    at com.example.OrderService.findOrder(OrderService.java:45)
    at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
    at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint()
    at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed()
    at org.springframework.transaction.interceptor.TransactionInterceptor$1.proceedWithInvocation()
    at org.springframework.transaction.interceptor.TransactionInterceptor.invoke()
    at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed()
    at org.springframework.cache.interceptor.CacheInterceptor.invoke()
    at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed()
    at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept()
    at com.example.OrderService$$SpringCGLIB$$0.findOrder(<generated>)
    at com.example.OrderController.getOrder(OrderController.java:30)
```

**How to read it**: The call to `OrderController.getOrder()` went to `OrderService$$SpringCGLIB$$0` (CGLIB proxy). Inside, the proxy invoked `CacheInterceptor` first (outermost advice), then `TransactionInterceptor` (inner advice), then the actual `OrderService.findOrder()` method. The order of `ReflectiveMethodInvocation.proceed()` calls in the stack trace is the EXACT order of advice execution.

### Using Advised.getAdvisors() to Inspect Proxy Chain

```java
// In a @PostConstruct or debug endpoint:
@Component
public class ProxyInspector {
    @Autowired
    private ApplicationContext context;

    @EventListener(ApplicationReadyEvent.class)
    public void inspectAllProxies() {
        for (String beanName : context.getBeanDefinitionNames()) {
            Object bean = context.getBean(beanName);
            if (AopUtils.isAopProxy(bean)) {
                // ── Inspect the proxy ──
                log.info("=== AOP Proxy: {} ===", beanName);
                log.info("  Class: {}", bean.getClass().getName());
                // Output example: "OrderService$$SpringCGLIB$$0"
                
                // ── Cast to Advised (all Spring AOP proxies implement this) ──
                Advised advised = (Advised) bean;
                
                // ── Get the target class ──
                log.info("  Target: {}", advised.getTargetSource().getTargetClass());
                // Output: "class com.example.OrderService"
                
                // ── List all advisors/interceptors ──
                for (Advisor advisor : advised.getAdvisors()) {
                    Advice advice = advisor.getAdvice();
                    log.info("  Advisor: {} → Advice: {}",
                        advisor.getClass().getSimpleName(),
                        advice.getClass().getName());
                    // Output examples:
                    //   "Advisor: BeanFactoryCacheOperationSourceAdvisor → 
                    //     Advice: org.springframework.cache.interceptor.CacheInterceptor"
                    //   "Advisor: BeanFactoryTransactionAttributeSourceAdvisor → 
                    //     Advice: org.springframework.transaction.interceptor.TransactionInterceptor"
                }
                
                // ── Is it JDK dynamic proxy or CGLIB? ──
                log.info("  JDK proxy: {}", AopUtils.isJdkDynamicProxy(bean));
                log.info("  CGLIB proxy: {}", AopUtils.isCglibProxy(bean));
            }
        }
    }
}
```

### Setting Breakpoints for Debugging

```
Breakpoint Locations (in order of usefulness for debugging):

1. DispatcherServlet.doDispatch() — line ~1037
   → See: which handler was selected, interceptors in chain
   
2. HandlerExecutionChain.applyPreHandle() — line ~250
   → See: each interceptor's preHandle return value
   
3. TransactionInterceptor.invokeWithinTransaction() — line ~200
   → See: transaction attribute, propagation, rollback rules
   
4. AbstractAutoProxyCreator.wrapIfNecessary() — line ~120
   → See: which beans are being proxied and why
   
5. OncePerRequestFilter.doFilter() — line ~80
   → See: filter name, FILTERED attribute state
   
6. ApplicationFilterChain.internalDoFilter() — line ~200
   → See: pos counter, which filter is next

Conditional breakpoints:
  TransactionInterceptor.invokeWithinTransaction():
    Condition: invocation.getMethod().getName().equals("problemMethod")
  DispatcherServlet.doDispatch():
    Condition: request.getRequestURI().contains("/api/problem-path")
```

### Verifying Filter Order at Runtime

```java
// Programmatically inspect filter registrations:
@EventListener(ApplicationReadyEvent.class)
public void logFilterOrder(@Autowired ApplicationContext ctx) {
    ServletContext servletContext = 
        ((WebApplicationContext) ctx).getServletContext();
    
    // Get filter registrations (not ordered by default):
    Map<String, ? extends FilterRegistration> registrations = 
        servletContext.getFilterRegistrations();
    
    log.info("=== Registered Filters ===");
    registrations.forEach((name, reg) -> {
        log.info("  {} → class={}, urlPatterns={}, dispatcherTypes={}",
            name,
            reg.getClassName(),
            reg.getUrlPatternMappings(),
            reg.getDispatcherTypes());
    });
    
    // For Spring Security's internal filter chain:
    // The springSecurityFilterChain bean is a FilterChainProxy
    // containing multiple internal filters. To inspect those:
    FilterChainProxy securityChain = ctx.getBean(
        "springSecurityFilterChain", FilterChainProxy.class);
    securityChain.getFilterChains().forEach(chain -> {
        log.info("Security filter chain: {}", chain.getRequestMatcher());
        chain.getFilters().forEach(f -> 
            log.info("  → {}", f.getClass().getSimpleName()));
    });
}
```

### Tracing Transaction State

```java
// At any point in your code:
public void debugTransactionState() {
    // Is there an actual database transaction active?
    boolean active = TransactionSynchronizationManager
        .isActualTransactionActive();
    
    // What's the current transaction name?
    String txName = TransactionSynchronizationManager
        .getCurrentTransactionName();
    
    // Is the current transaction read-only?
    boolean readOnly = TransactionSynchronizationManager
        .isCurrentTransactionReadOnly();
    
    // What isolation level?
    Integer isolation = TransactionSynchronizationManager
        .getCurrentTransactionIsolationLevel();
    
    // What resources are bound (DataSource, EntityManager, etc.)?
    Map<Object, Object> resources = TransactionSynchronizationManager
        .getResourceMap();
    // This shows ALL resources bound to the current transaction
    // Key = DataSource/EntityManager, Value = Connection/Session
    
    // Are there any registered synchronizations?
    List<TransactionSynchronization> syncs = TransactionSynchronizationManager
        .getSynchronizations();
    // Shows @TransactionalEventListener registrations, etc.
    
    log.info("Transaction: active={}, name={}, readOnly={}, isolation={}",
        active, txName, readOnly, isolation);
}
```

## 10. Observability Considerations

### Metrics: Execution Time at Each Layer

```java
// Filter-level timing:
@Component
public class MetricsFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        Timer.Sample sample = Timer.start(registry);
        try {
            chain.doFilter(request, response);
        } finally {
            sample.stop(Timer.builder("http.server.requests")
                .tag("uri", request.getRequestURI())
                .tag("method", request.getMethod())
                .tag("status", String.valueOf(response.getStatus()))
                .register(registry));
        }
    }
}

// Interceptor-level timing (more granular — per-handler-method):
@Component
public class MetricsInterceptor implements HandlerInterceptor {
    private final MeterRegistry registry;
    
    @Override
    public boolean preHandle(HttpServletRequest request,
            HttpServletResponse response, Object handler) {
        request.setAttribute("startTime", System.nanoTime());
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request,
            HttpServletResponse response, Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute("startTime");
        long duration = System.nanoTime() - startTime;
        
        String handlerName = (handler instanceof HandlerMethod hm) ?
            hm.getMethod().getName() : handler.toString();
        
        Timer.builder("spring.controller.execution")
            .tag("handler", handlerName)
            .tag("outcome", ex == null ? "SUCCESS" : "ERROR")
            .register(registry)
            .record(duration, TimeUnit.NANOSECONDS);
    }
}

// AOP-level timing (most granular — per-method):
@Aspect
@Component
public class MethodTimingAspect {
    private final MeterRegistry registry;
    
    @Around("@annotation(Monitored)")
    public Object measureExecution(ProceedingJoinPoint pjp) throws Throwable {
        Timer.Sample sample = Timer.start(registry);
        try {
            return pjp.proceed();
        } finally {
            sample.stop(Timer.builder("service.method.execution")
                .tag("class", pjp.getTarget().getClass().getSimpleName())
                .tag("method", pjp.getSignature().getName())
                .register(registry));
        }
    }
}
```

### Tracing: Span Creation at Each Layer

```
Trace context propagation through the layers:

┌──────────────────────────────────────────────────────────────────┐
│ FILTER layer: Creates ROOT HTTP span                              │
│                                                                  │
│  RequestLoggingFilter:                                            │
│    Span httpSpan = tracer.spanBuilder("HTTP GET /api/orders/{id}")│
│        .setSpanKind(SpanKind.SERVER)                              │
│        .startSpan();                                              │
│    try (Scope scope = httpSpan.makeCurrent()) {                  │
│        MDC.put("traceId", httpSpan.getSpanContext().getTraceId());│
│        chain.doFilter(wrappedRequest, wrappedResponse);          │
│        httpSpan.setStatus(response.getStatus());                  │
│    } finally {                                                    │
│        httpSpan.end();                                            │
│    }                                                              │
│                                                                  │
│ INTERCEPTOR layer: Can add attributes to the current span         │
│                                                                  │
│ AOP layer (@Transactional): Creates DB INTERACTION span           │
│    Span dbSpan = tracer.spanBuilder("SELECT orders")              │
│        .addLink(currentSpan.getSpanContext())                    │
│        .setSpanKind(SpanKind.CLIENT)                             │
│        .startSpan();                                              │
│    try {                                                          │
│        // Execute SQL                                              │
│    } finally {                                                    │
│        dbSpan.end();                                              │
│    }                                                              │
│                                                                  │
│ @Async: Creates a NEW root span (different thread!)              │
│    Need to propagate trace context explicitly via                 │
│    Context.propagate() or TraceContext.wrap(task)                 │
└──────────────────────────────────────────────────────────────────┘
```

### MDC Context Propagation

```java
// The complete MDC lifecycle across layers:

// FILTER: Generate requestId, populate MDC
public class TraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();  // Clean up for THIS request on THIS thread
        }
    }
}

// INTERCEPTOR: Add handler-specific context
public class TracingInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request,
            HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod hm) {
            MDC.put("handler", hm.getMethod().toGenericString());
        }
        return true;
    }
}

// AOP: Add business-specific context
@Aspect
@Component
public class TracingAspect {
    @Before("@annotation(com.example.Traced)")
    public void addTraceContext(JoinPoint jp) {
        MDC.put("method", jp.getSignature().toShortString());
    }
}

// @Async: MUST explicitly propagate MDC (as shown in Scenario 2)
```

## 11. Performance Implications

### AOP Proxy Overhead

```
CGLIB Proxy Overhead (per method call):
  ├── Proxy method dispatch:         ~0.5-1.0 μs (method lookup + reflection)
  ├── Advisor chain walk:            ~0.1 μs per advisor (memory access)
  ├── TransactionInterceptor:        ~3-5 μs (begin/commit, without SQL)
  ├── CacheInterceptor:              ~0.5-1.0 μs (cache hit, without serialization)
  └── TOTAL for @Transactional:     ~4-6 μs per method invocation

JDK Dynamic Proxy Overhead (per method call):
  ├── Proxy method dispatch:         ~0.3-0.8 μs (slightly faster than CGLIB)
  ├── Advisor chain walk:            ~0.1 μs per advisor
  ├── TOTAL:                        ~10-20% less overhead than CGLIB

In a service handling 10,000 req/sec:
  ├── @Transactional on every method: ~40-60 ms/sec CPU overhead
  ├── @Cacheable on every method:    ~5-10 ms/sec CPU overhead
  └── Combined:                     negligible for business logic (0.005% of 1 second)

WHEN IT MATTERS:
  ├── Ultra-low latency (<100μs) → AOP overhead is significant
  │   → Consider programmatic transaction management
  ├── 100K+ ops/sec per instance → benchmark with JMH
  └── For typical REST APIs (1-100 ms response time): negligible
```

### Filter Chain Depth and Latency

```
Each filter adds ~0.1-0.5 μs for the filter.doFilter() call overhead:
  ├── CharacterEncodingFilter:       ~0.1 μs (attribute check only)
  ├── CorsFilter:                    ~0.5 μs (header processing)
  ├── Spring Security chain:         ~50-200 μs (20+ internal filters)
  │   └── Each security filter:      ~2-5 μs per filter
  ├── Custom logging filter:         ~1-5 μs (depends on log level and content)
  └── Total 10-filter chain:        ~60-210 μs

Optimization:
  ├── Use OncePerRequestFilter to prevent duplicate execution
  ├── Move heavy filters (image processing, WAF) to API Gateway
  ├── Use async logging in filters (log to ring buffer, not blocking IO)
  └── Security: use stateless JWT (no session lookup per request)
```

### @Transactional Overhead and readOnly Optimization

```
@Transactional overhead breakdown:
  ├── getTransactionAttribute():     ~1-2 μs (lookup from metadata cache)
  ├── determineTransactionManager(): ~0.5 μs (cached after first lookup)
  ├── getTransaction():              
  │   ├── Existing transaction:      ~0.5 μs (ThreadLocal lookup)
  │   └── New transaction:           ~100-500 μs (connection acquisition from pool)
  ├── commitTransaction():           ~1-5 μs (if no dirty checking)
  └── rollbackTransaction():         ~1-5 μs

@Transactional(readOnly = true) optimization:
  ├── Hibernate: disables dirty checking → ~10-30% faster for read operations
  ├── JDBC: sets connection.setReadOnly(true) → database can optimize
  │   └── PostgreSQL: skips WAL writes when readOnly
  │   └── MySQL: may use read replicas (if configured)
  ├── Spring: no flush before query → reduces ORM overhead
  └── RECOMMENDATION: Always use readOnly=true for GET operations
```

### @Cacheable Key Computation Cost

```
Key computation cost:
  ├── Simple key (single primitive arg):  ~0.1 μs
  ├── Composite key (multiple args):      ~0.5-1 μs (SimpleKey generation)
  ├── SpEL expression key:               ~1-3 μs (expression parsing + evaluation)
  ├── Custom KeyGenerator:               varies (your code)
  └── Cache lookup (ConcurrentHashMap):  ~0.1-0.3 μs (in-memory)

Cache miss scenario with @Transactional:
  ├── Key computation:                 ~1 μs
  ├── Cache lookup (miss):             ~0.2 μs
  ├── Transaction begin:               ~200 μs (connection from pool)
  ├── Method execution:                varies (business logic + SQL)
  ├── Transaction commit:              ~2 μs
  ├── Cache put:                       ~0.3 μs
  └── Total overhead:                  ~205 μs (all in transaction begin)

Cache hit scenario:
  ├── Key computation:                 ~1 μs
  ├── Cache lookup (hit):              ~0.2 μs
  └── RESPONSE RETURNED:               ~1.2 μs total!
```

### Impact of Proxy Layers on Startup and Metaspace

```
CGLIB proxy classes consume Metaspace:
  ├── Each CGLIB proxy class:         ~5-15 KB of Metaspace
  ├── Each proxy INSTANCE:            ~200 bytes of heap
  ├── Application with 200 services:
  │   ├── 200 @Transactional → 200 CGLIB classes → ~2 MB Metaspace
  │   ├── 50 @Cacheable → 50 CGLIB classes → ~500 KB Metaspace
  │   ├── 10 @Async → 10 CGLIB classes → ~100 KB Metaspace
  │   └── 30 @Configuration → 30 CGLIB classes → ~300 KB Metaspace
  │     (but proxyBeanMethods=false eliminates @Configuration proxies)
  └── Total: ~3 MB Metaspace for proxy classes (negligible for most apps)

Startup time impact:
  ├── CGLIB class generation:         ~0.5-2 ms per class (bytecode generation)
  ├── 200 services × 2 ms:           ~400 ms at startup
  ├── Mitigation: Spring AOT (native compilation) eliminates runtime proxies
  └── JDK proxies: no class generation, but limited to interfaces
```

## 12. Architecture Implications

### When to Promote from Interceptor to AOP to Filter

```
DECISION FRAMEWORK — PROMOTION RULES:

START: Controller needs cross-cutting behavior.
  │
  ├── Use HandlerInterceptor when:
  │   ├── Only applies to HTTP controller methods
  │   ├── Needs access to raw HttpServletRequest/Response
  │   ├── Needs to short-circuit (preHandle returns false)
  │   └── Needs to run afterCompletion (always, even on exception)
  │
  ├── PROMOTE to AOP @Aspect when:
  │   ├── Same behavior needed for NON-HTTP entry points
  │   │   (message listeners, scheduled tasks, gRPC services)
  │   ├── Needs to modify method arguments or return values
  │   ├── Needs method-level granularity (not just URL patterns)
  │   └── Multiple teams need the aspect on their own beans
  │
  └── PROMOTE to Servlet Filter when:
      ├── Must execute BEFORE Spring context is available
      │   (authentication gate, WAF, DoS protection)
      ├── Must handle ALL requests including static resources
      │   (which bypass DispatcherServlet)
      ├── Must modify the request/response at the byte stream level
      │   (compression, encryption, body transformation)
      └── Must integrate with non-Spring filters
          (container-managed security, third-party servlet filters)
```

### Security: Why Authentication Belongs in Filters, Authorization in AOP

```
┌──────────────────────────────────────────────────────────────────┐
│ AUTHENTICATION → FILTER                                           │
│                                                                  │
│ Why: Authentication must happen BEFORE the request reaches       │
│ Spring's handler mapping. The filter can:                        │
│   a. Extract the token from the Authorization header             │
│   b. Validate the token against an identity provider             │
│   c. Set the SecurityContext BEFORE any Spring code runs          │
│   d. Short-circuit with 401 BEFORE any controller is called      │
│                                                                  │
│ Spring Security does exactly this: its FilterChainProxy is a     │
│ Filter registered BEFORE all Spring bootstrap filters.           │
│                                                                  │
│ AUTHORIZATION → AOP (@PreAuthorize or custom @Aspect)            │
│                                                                  │
│ Why: Authorization is method-level. It depends on:               │
│   a. Which CONTROLLER METHOD is being invoked                    │
│   b. What PARAMETERS were provided (e.g., @PreAuthorize          │
│      "#userId == authentication.principal.id")                   │
│   c. The SecurityContext (already set by the auth filter)        │
│                                                                  │
│ Authorization CANNOT be done in a filter because filters don't   │
│ know which handler method will be invoked. They only know the    │
│ URL. An interceptor COULD do authorization (it knows the         │
│ handler), but AOP is preferred because:                          │
│   a. @PreAuthorize/@PostAuthorize are declarative and concise    │
│   b. Authorization applies to service methods too (not just      │
│      controllers — e.g., "only admins can delete orders")       │
│   c. Method security integrates with method parameter resolution │
└──────────────────────────────────────────────────────────────────┘
```

### How the Proxy Model Shapes Service Design

Every AOP annotation (`@Transactional`, `@Cacheable`, `@Async`, `@Retryable`) imposes a constraint: **only public methods are proxied**. If you mark a non-public method with `@Transactional`, no transaction will be created. If you call an AOP-annotated method from within the same class, no proxy is involved.

This shapes service design in three ways:

1. **Service granularity is forced by AOP requirements**: If method A and method B both need transactions but different propagation levels, they CANNOT be in the same class (unless you use self-injection, which is a code smell). They must be in separate service beans, each with its own proxy.

2. **DTO-to-entity mapping cannot be AOP-advised**: Mapper classes typically use `private` or `default-visibility` methods. These cannot be advised. Mapping logic that needs caching or transactional semantics must be refactored into public methods on separate beans.

3. **The "thin controller, thick service" pattern is reinforced**: Controllers are rarely AOP-advised (they're stateless and pass data through). The AOP surface area is the service layer. This naturally pushes business logic into services.

### The "AOP is Invisible Abstraction" Problem

AOP is "invisible" — you cannot see it in the code. When you read:

```java
@Service
public class OrderService {
    public Order getOrder(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }
}
```

You cannot know whether this method has a transaction, is cached, is async, is retried, or is rate-limited. The annotations are on the declaration, but the BEHAVIOR is in the proxy. This creates several problems:

1. **Debugging confusion**: An engineer reading the service code sees a 3-line method. Why is the stack trace 30 lines deep showing `TransactionInterceptor` and `CacheInterceptor`?

2. **Refactoring risk**: Moving a method from one class to another can silently change its AOP behavior (because the proxy only wraps the original class).

3. **Test fragility**: Testing a method annotated with `@Cacheable` requires `@SpringBootTest` (for the proxy to exist). A unit test with `new OrderService()` will NOT have caching.

**Staff engineer mitigation**: Document the AOP proxy chain for each service in the team wiki. Use `Advised.getAdvisors()` at startup to log the full proxy chain. Make AOP visible through observability (trace every advisor invocation). And train the team: "When you see `$$SpringCGLIB$$0` in a stack trace, find the `@Enable` annotation that created it."

## 13. Team Ownership Implications

### Who Owns What Layer?

```
┌──────────────────────────────┬─────────────────────────────────────────┐
│ LAYER                        │ OWNER                                   │
├──────────────────────────────┼─────────────────────────────────────────┤
│ Servlet Filters              │ PLATFORM TEAM                           │
│ (security, CORS, tracing,    │ These are infrastructure concerns.      │
│  logging, request ID)        │ Teams must NOT add their own filters    │
│                              │ without platform review.                │
├──────────────────────────────┼─────────────────────────────────────────┤
│ Common Interceptors          │ PLATFORM TEAM                           │
│ (rate limiting, tenant       │ Provided as a common library.           │
│  context extraction,         │ Teams compose them via config.          │
│  feature flag checks)        │                                         │
├──────────────────────────────┼─────────────────────────────────────────┤
│ Service-Specific Interceptors│ FEATURE TEAM                            │
│ (domain-specific validation, │ Owned by the team that owns the         │
│  custom metrics for their    │ controller. Reviewed by platform.       │
│  domain)                     │                                         │
├──────────────────────────────┼─────────────────────────────────────────┤
│ AOP Aspects (@Transactional, │ INFRASTRUCTURE (annotations)            │
│  @Cacheable, @Async)         │ FEATURE TEAM (usage on their services) │
│                              │ Platform provides the aspects.          │
│                              │ Teams decide where to apply them.       │
├──────────────────────────────┼─────────────────────────────────────────┤
│ Custom @Aspect annotations   │ FEATURE TEAM (domain-specific)          │
│ (@FeatureFlag, @Audited,     │ Platform team reviews for redundancy:  │
│  @DistributedLock)           │ "Does an existing aspect do this?"      │
└──────────────────────────────┴─────────────────────────────────────────┘
```

### Establishing Filter Ordering Conventions

```
Standard filter order convention for the platform:

Order 0-99:    SECURITY (authentication, authorization, CSRF)
Order 100-199: REQUEST TRANSFORMATION (encoding, body wrapping, CORS)
Order 200-299: CONTEXT PROPAGATION (trace ID, tenant context, MDC)
Order 300-399: OBSERVABILITY (request logging, metrics, audit)
Order 400-499: BUSINESS (feature flags, A/B testing, multi-tenancy routing)
Order 500-599: RESILIENCY (rate limiting, circuit breaking, bulkheading)

Concrete example:
  @Order(Ordered.HIGHEST_PRECEDENCE + 10)   → SecurityContextFilter
  @Order(Ordered.HIGHEST_PRECEDENCE + 110)  → RequestBodyCachingFilter
  @Order(Ordered.HIGHEST_PRECEDENCE + 210)  → TraceContextFilter
  @Order(Ordered.HIGHEST_PRECEDENCE + 310)  → AccessLogFilter
  @Order(Ordered.HIGHEST_PRECEDENCE + 410)  → FeatureFlagFilter
  @Order(Ordered.HIGHEST_PRECEDENCE + 510)  → RateLimitFilter

These conventions MUST be documented in the team's engineering wiki.
New filters MUST be placed in the correct numeric band.
```

### Code Review Checklist for AOP Usage

```
Before approving a PR that adds AOP annotations:

☐ Is @Transactional applied to a PUBLIC method?
   → Private/protected/package-private methods are NOT proxied.

☐ Is @Transactional on a method that is called from within the same class?
   → Self-invocation bypasses the proxy. Extract to separate bean.

☐ Does the method have MULTIPLE AOP annotations?
   → (@Transactional + @Cacheable + @Async)
   → Verify the proxy order. Is it correct for the use case?
   → Is the cache populated before or after the transaction commits?

☐ Is @Async used with VOID return type?
   → Exceptions in void async methods are SILENTLY swallowed.
   → Use CompletableFuture<Void> or configure an AsyncUncaughtExceptionHandler.

☐ Is context propagation handled for @Async?
   → MDC, SecurityContext, RequestAttributes must be explicitly propagated.
   → Verify TaskDecorator is configured on the async executor.

☐ Does @Cacheable have an appropriate eviction strategy?
   → How does stale data get cleared? @CacheEvict? TTL? Manual invalidation?
   → Is the cache key deterministic and collision-resistant?

☐ Is @Retryable applied to an IDEMPOTENT operation?
   → Retrying a non-idempotent operation (payment, email, SMS) causes duplicates.

☐ Are transaction boundaries appropriate?
   → @Transactional on a GET method? (Use readOnly=true)
   → @Transactional on a method that calls 5 external APIs? (Too wide)
   → @Transactional(propagation = REQUIRES_NEW) — is it really needed?

☐ Are AOP-related exceptions properly handled?
   → Does the @ExceptionHandler cover TransactionTimedOutException?
   → Does the @ExceptionHandler cover CacheOperationException?
```

### Testing Strategies for AOP-Affected Code

```
LAYER TESTING STRATEGY:

1. UNIT TESTING (no Spring context):
   → Test the SERVICE IMPLEMENTATION directly
   → new OrderService(mockRepo)
   → AOP annotations are NOT active — test business logic in isolation
   → Assert: returned values, exceptions, repository calls

2. INTEGRATION TESTING (slice test with Spring context):
   → @DataJpaTest / @JdbcTest for @Transactional testing
   → @WebMvcTest + @Import(WebConfig.class) for interceptors
   → Use @TestConfiguration to add test-specific filters/interceptors

3. AOP-SPECIFIC INTEGRATION TESTING:
   → @SpringBootTest for full proxy stack
   → Test that @Transactional actually rolls back:
   
   @Test
   void transactionalShouldRollbackOnRuntimeException() {
       assertThrows(RuntimeException.class, () -> 
           orderService.createOrder(invalidOrder));
       
       // Verify changes were rolled back
       List<Order> orders = orderRepository.findAll();
       assertTrue(orders.isEmpty());
   }

   → Test that @Cacheable actually caches:
   @Test
   void cacheableShouldReturnFromCacheOnSecondCall() {
       Order first = orderService.getOrder(1L);
       Order second = orderService.getOrder(1L);
       assertSame(first, second);  // Same object instance (from cache)
   }

4. FILTER TESTING:
   @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
   void filterShouldSetRequestIdHeader() {
       ResponseEntity<String> response = restTemplate.getForEntity(
           "/api/orders", String.class);
       assertTrue(response.getHeaders().containsKey("X-Request-ID"));
   }

5. @Async TESTING:
   → Use Awaitility for async assertions:
   @Test
   void asyncMethodShouldCompleteEventually() {
       CompletableFuture<Void> future = asyncService.processAsync();
       await().atMost(5, SECONDS)
           .until(future::isDone);
   }
```

## 14. Interview Questions

### Question 1: "Explain the difference between a Servlet Filter, a HandlerInterceptor, and an @Aspect. Give a concrete decision framework for when to use each, with examples."

**Staff-level answer**: These three mechanisms operate at progressively higher levels of abstraction. The decision between them is based on what data you need access to and when you need to intervene.

**Servlet Filter** operates at the container level, before Spring's `DispatcherServlet` even sees the request. It works with raw `ServletRequest`/`ServletResponse` objects. Its lifecycle is managed by the servlet container, not Spring. You use a filter when you need to: (a) intercept the request before any Spring code runs — this is critical for authentication gates and DoS protection; (b) operate on the raw byte stream — for request/response body transformation, compression, or encryption; (c) handle requests that don't go through Spring at all (static resources served by the container). Spring Security's entire filter chain (`springSecurityFilterChain`) is a single filter registered with the container. Example: a WAF filter that inspects request bodies for SQL injection patterns before they reach any application code.

**HandlerInterceptor** operates at Spring MVC level. It knows which `HandlerMethod` (controller method) was selected by `RequestMappingHandlerMapping`. It can see `HttpServletRequest`/`Response` but also has access to the handler object. Its three lifecycle hooks (`preHandle`, `postHandle`, `afterCompletion`) map to specific dispatch phases. You use an interceptor when you need to: (a) make decisions based on which CONTROLLER method will handle the request (URL-based filtering isn't enough); (b) short-circuit the handler chain (returning `false` from `preHandle` stops the handler from being called); (c) execute cleanup logic that ALWAYS runs regardless of success or failure (`afterCompletion` is in a finally block). Example: a rate limiter that applies different limits to different controller methods based on method-level annotations.

**@Aspect (AOP)** operates at the method invocation level, on proxied Spring beans. It has NO knowledge of HTTP semantics. It sees method arguments and return values as Java objects. You use AOP when you need to: (a) apply behavior to non-HTTP entry points (message listeners, scheduled tasks, internal service calls); (b) transform method arguments or return values programmatically; (c) implement declarative semantics like `@Transactional` where the advice controls the flow around the method. Example: a `@DistributedLock` annotation that acquires a Redis lock before the method executes and releases it after.

**Decision framework**: Start with the question "Does this behavior apply to raw HTTP requests or to method invocations on beans?" If HTTP requests only → Filter or Interceptor. If bean method invocations → AOP. Then: "Do I need to see a byte stream or a typed method argument?" Byte stream → Filter. Typed argument → AOP. Finally: "Do I need to know which controller method was selected?" Yes → Interceptor. No → Filter.

Concrete example — **Rate Limiting**: If you rate-limit by IP address globally, use a Filter (you only need the client IP, no handler info needed). If you rate-limit differently per controller method (e.g., 100 req/s for GET /api/orders vs 10 req/s for POST /api/orders), use an Interceptor (you need to identify the handler method). If you rate-limit internal service-to-service calls that don't go through HTTP, use AOP.

### Question 2: "You have a service with both @Transactional and @Cacheable. Explain exactly what the proxy chain looks like, the order of execution, and what problems can arise if the order is wrong."

**Staff-level answer**: When a single bean has both `@Transactional` and `@Cacheable`, Spring creates TWO SEPARATE CGLIB proxies, nested inside each other. These are NOT merged into a single proxy with two interceptors — each `AbstractAutoProxyCreator` BeanPostProcessor creates its own proxy wrapping the previous result.

The default order, unless explicitly changed, puts `@Cacheable` as the OUTER proxy and `@Transactional` as the INNER proxy:

```
CacheProxy(OrderService$$CGLIB$$1)
  └── target = TransactionProxy(OrderService$$CGLIB$$0)
      └── target = OrderService (raw instance)
```

**Execution order on a cache miss (read operation)**:

1. Caller invokes `orderService.getOrder(123)` — hits CacheProxy
2. CacheProxy → `CacheInterceptor.invoke()`:
   - Compute cache key: `orders::123`
   - `cacheManager.get("orders").get("orders::123")` → MISS
   - Call `invocation.proceed()` → hands off to TransactionProxy
3. TransactionProxy → `TransactionInterceptor.invoke()`:
   - `getTransaction()` → begin new transaction
   - `invocation.proceed()` → hands off to actual OrderService
4. `OrderService.getOrder(123)` executes SQL, returns Order
5. TransactionProxy resumes: `commitTransactionAfterReturning()` → commit
6. CacheProxy resumes: `cache.put("orders::123", order)` → store in cache
7. Return result to caller

**Execution order on a cache HIT**:

1. Caller invokes `orderService.getOrder(123)` → hits CacheProxy
2. CacheProxy → `CacheInterceptor.invoke()`:
   - `cacheManager.get("orders").get("orders::123")` → HIT
   - Return cached value DIRECTLY
3. **TransactionProxy is NEVER invoked** — no transaction is created
4. Return cached result to caller

This is DESIRABLE for read operations: we avoid starting a transaction entirely when the data is cached. The transaction is only created on a cache miss, when we actually need to read from the database.

**Problems when the order is WRONG** — if `@Transactional` is the OUTER proxy (wraps CacheProxy):

```
TransactionProxy
  └── target = CacheProxy
      └── target = OrderService
```

On a cache miss:
1. TransactionProxy begins transaction
2. CacheProxy: cache miss → invoke OrderService
3. CacheProxy: `cache.put(key, result)` — puts data into cache WHILE transaction is still open
4. TransactionProxy: commits transaction

THE DANGER: If the transaction ROLLS BACK after step 3 (e.g., due to a database constraint violation on a related entity written AFTER the cache was populated), the cache now contains data that was NEVER committed to the database. The next cache hit returns phantom data.

**The fix**: Explicitly order the proxies so `@Transactional` wraps `@Cacheable` ONLY if ALL methods in the cacheable service are read-only (no writes). If the service does writes, you must ensure the cache is populated AFTER the transaction commits. This can be done by:
1. Setting `@EnableCaching(order = 0)` and `@EnableTransactionManagement(order = 1)` to put TransactionProxy outside CacheProxy, coupled with a `TransactionSynchronization.afterCommit()` callback to populate the cache.
2. Using `@CachePut` and `@CacheEvict` inside `@Transactional` methods, where the cache operation executes as part of the transactional boundary.
3. Moving caching logic to a separate service called from the transactional service, where the cache service is called AFTER the transactional method returns (ensuring the transaction has committed).

### Question 3: "Walk me through how @Async actually works. What happens to the caller's thread, what gets lost in the async thread, and how do you fix context propagation?"

**Staff-level answer**: `@Async` relies on AOP proxy generation, specifically `AsyncAnnotationBeanPostProcessor` (or `@EnableAsync` which imports it). When the bean is post-processed, methods annotated with `@Async` are wrapped by `AsyncExecutionInterceptor`.

When the caller invokes an `@Async` method:

1. The call hits the CGLIB proxy (`OrderService$$SpringCGLIB$$0`)
2. The proxy dispatches to `AsyncExecutionInterceptor.invoke(invocation)`
3. The interceptor calls `determineAsyncExecutor(method)` to get the `AsyncTaskExecutor` — this is either the bean named `taskExecutor`, a `TaskExecutor` bean, or the `SimpleAsyncTaskExecutor` (which creates a NEW THREAD for every call — a dangerous default in production)

4. The interceptor submits the invocation to the executor:
   - `CompletableFuture` return type → `CompletableFuture.supplyAsync(() -> invocation.proceed(), executor)`
   - `Future` return type → `executor.submit(() -> invocation.proceed())`
   - `void` return type → `executor.submit(() -> invocation.proceed())` (fire-and-forget)

5. The CALLER thread returns IMMEDIATELY with either: `null` (void method), a `Future` that will complete later, or a `CompletableFuture` that will complete later. The caller thread continues executing the rest of the HTTP request — interceptor `afterCompletion`, filter chain post-processing, and sends the response to the client.

6. The ASYNC worker thread gets a task from the executor's queue and calls `invocation.proceed()`. This executes the actual service method. After the method returns, the thread returns to the pool.

**What gets LOST** in the async thread — anything stored in `ThreadLocal` on the caller thread:

- `SecurityContext` (Spring Security): `SecurityContextHolder` uses `ThreadLocal` by default. The async thread sees an empty security context → authentication is ANONYMOUS. Any `@PreAuthorize` checks on service methods called from the async thread will fail.
- `MDC` (Logging context): trace IDs, request IDs, user IDs — all gone. Log statements from the async thread have empty MDC → logs are not correlated with the original request.
- `RequestAttributes`: `RequestContextHolder.getRequestAttributes()` returns `null`. Any code that reads request attributes (IP address, session, headers) in the async thread fails.
- `TransactionSynchronizationManager` resources: If the caller was in a transaction, the async thread has NO transaction. It might start its own transaction (if the async method has its own `@Transactional`), but it's NOT part of the caller's transaction.

**How to fix context propagation**:

The cleanest solution is a `TaskDecorator` on the `ThreadPoolTaskExecutor`:

```java
@Bean
public TaskDecorator contextPropagatingDecorator() {
    return task -> {
        // CAPTURE context from the CALLER thread (at submit time)
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        SecurityContext securityContext = SecurityContextHolder.getContext();
        RequestAttributes requestAttributes = 
            RequestContextHolder.getRequestAttributes();
        
        return () -> {
            // RESTORE context in the WORKER thread
            try {
                if (mdcContext != null) MDC.setContextMap(mdcContext);
                if (securityContext != null) 
                    SecurityContextHolder.setContext(securityContext);
                if (requestAttributes != null) 
                    RequestContextHolder.setRequestAttributes(requestAttributes, true);
                task.run();
            } finally {
                // CLEANUP — prevent ThreadLocal leaks
                MDC.clear();
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        };
    };
}

@Bean
public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(100);
    executor.setTaskDecorator(contextPropagatingDecorator());
    executor.initialize();
    return executor;
}
```

Alternative approaches: Use `InheritableThreadLocal` for `SecurityContextHolder` (sets `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` — works for `Thread` creation, but NOT for thread pools where threads are reused). Use `DelegatingSecurityContextAsyncTaskExecutor` (Spring Security's built-in wrapper). Use Micrometer's `ContextSnapshot` API for trace context.

**The Staff Engineer also considers**: Is `@Async` the right pattern in the first place? In modern microservice architectures, putting work on a local thread pool creates a single point of failure — if that instance dies, the queued work is lost. The alternative is to publish a domain event to a message broker (Kafka, RabbitMQ) and process it asynchronously on any available consumer. This provides durability, replayability, and horizontal scaling that `@Async` cannot match. `@Async` is appropriate for fire-and-forget operations where loss is acceptable (sending a non-critical notification) — NOT for business-critical workflow steps.

## 15. Hands-On Exercises

1. **Implement a Request ID Filter**: Create a `OncePerRequestFilter` that generates a UUID for every request, stores it in MDC, adds it as `X-Request-ID` response header, and logs the request method, URI, response status, and elapsed time. Verify that the request ID appears in all log statements for the request's lifecycle, including from services called by the controller. Test: send 10 concurrent requests via `curl` and verify each has a unique ID in logs.

2. **Build a Rate-Limiting HandlerInterceptor**: Implement a sliding window rate limiter using `ConcurrentHashMap<String, Deque<Long>>` where the key is the client IP. For each request, evict timestamps older than the window (e.g., 60 seconds), count remaining entries, and reject with HTTP 429 if over the limit. Register the interceptor via `WebMvcConfigurer.addInterceptors()`. Test: send requests in a loop and verify that the 101st request in a 100-per-minute window is rejected.

3. **Build an AOP @Timed Aspect**: Create a custom `@Timed` annotation and an `@Aspect` class that wraps annotated methods with a `StopWatch`. Log the method name, arguments (via `joinPoint.getArgs()`), execution time, and result. Handle both successful execution and exceptions (log the exception type in the aspect's `@AfterThrowing`). Apply to controller methods and observe that the aspect fires AFTER interceptors but BEFORE the controller method body.

4. **Debug the AOP Proxy Chain**: Create a service with ALL THREE annotations: `@Transactional`, `@Cacheable`, and a custom `@Aspect`. Write a `@PostConstruct` method that uses `Advised.getAdvisors()` to log the full proxy chain: each advisor's order, type, and the annotation that triggered it. Then add a `@RestController` endpoint that returns this information as JSON. Observe the proxy class name in the response (`OrderService$$SpringCGLIB$$2`).

5. **Implement Context Propagation for @Async**: Configure a `ThreadPoolTaskExecutor` with a `TaskDecorator` that propagates MDC, SecurityContext, and RequestAttributes. Create a service with `@Async public CompletableFuture<String> processAsync(String input)`. In the async method, log the trace ID, user name, and request URI from context. Verify that these values match the calling thread's context. Then create a second test WITHOUT the TaskDecorator and observe the values are null.

## 16. Advanced Challenges

1. **Build a "Proxy Chain Visualizer"**: Create a `BeanPostProcessor` or `ApplicationListener` that, at startup, inspects every bean in the context. For each AOP proxy, recursively unwraps the chain: get the target via `Advised.getTargetSource().getTarget()`, check if the target is itself an `Advised` proxy, and continue until reaching the raw target. Render the full proxy chain for each bean as an ASCII tree showing: the proxy class name, the advisors/interceptors at each level, and the annoatation that triggered each proxy. Export as HTML with collapsible trees for each bean.

2. **Implement a Distributed Rate Limiter Using Filter + Redis**: Instead of a ConcurrentHashMap-based rate limiter, build a production-grade rate limiter that uses Redis sorted sets. The Filter extracts the client identifier (API key, user ID, IP), computes the Redis key (`ratelimit:{clientId}`), and atomically adds the current timestamp + counts entries in the window using a Lua script (to make the operation atomic). Handle Redis connection failures gracefully — fail open (allow the request) or fail closed (reject) based on configuration. Add a `/actuator/ratelimits` endpoint showing current rate limit status for all clients.

3. **Build a Custom `@FeatureFlag` Annotation Using AOP**: Design an annotation `@FeatureFlag(key, defaultValue, fallbackMethod)` that toggles method behavior based on a feature flag service (could be backed by database, LaunchDarkly, or a properties file). The aspect intercepts the annotated method, checks the flag status, and either executes the method or delegates to a fallback method. Handle edge cases: what if the flag service is unavailable, what if the fallback method has different parameters, what if the flag changes at runtime. Make it work with `@Cacheable` — the feature flag decision should be cached independently of the method result.

4. **Create a "Circuit Breaker" AOP Annotation for Service-to-Service Calls**: Implement `@CircuitBreaker(failureThreshold=5, openTimeoutSeconds=30, fallbackMethod="defaultResponse")`. The aspect tracks failures via a `ConcurrentHashMap<String, AtomicInteger>` keyed by method signature. When failures exceed the threshold, the circuit OPENS — all subsequent calls for `openTimeoutSeconds` are short-circuited to the fallback method. After the timeout, the circuit goes to HALF_OPEN — one trial call is allowed through. If it succeeds, the circuit CLOSES. If it fails, the circuit re-OPENS. Add a `/actuator/circuitbreakers` endpoint showing state, failure counts, and time until half-open for each breaker.

5. **Implement a "Request Replay System" Using Filter + AsyncContext**: Build a system for shadow traffic testing. A Filter wraps the `HttpServletRequest` in a custom wrapper that copies the request body. On a configurable percentage of requests (e.g., 1%), it uses `AsyncContext` to asynchronously replay the request to a "shadow" endpoint (a canary instance or a different API version). The shadow request runs entirely independently — its response is logged but never sent to the client. The original request continues normally. Track: shadow latency vs production latency, shadow error rate vs production error rate, and any divergences in response body (structural diff). Export this data to a metrics system for canary analysis.
