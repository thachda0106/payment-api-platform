# Session 16: HTTP Layer -- Tomcat & DispatcherServlet Internals

## 1. Why This Topic Exists

Every HTTP request your application serves passes through 7 layers of abstraction before reaching your `@RestController`. When a production incident involves "502 Bad Gateway," "request body consumed twice," or "thread pool exhaustion," the engineer who can trace from the OS socket accept to the controller method return can diagnose the root cause in minutes. The engineer who cannot will restart pods, increase heap, and pray.

The Spring Boot HTTP layer is not a black box that "just works." It is a carefully orchestrated pipeline of thread pools (Tomcat acceptor/poller/worker), adapter layers (Coyote -> Catalina), and Spring's own request processing machinery (DispatcherServlet -> HandlerMapping -> HandlerAdapter -> argument resolver chain). Each layer has its own threading model, its own buffer management, its own error-handling semantics. When these layers misalign -- and they will -- the failure mode is never a clean exception. It is a timeout, a stale connection, a consumed InputStream, or a 406 response that your client did not ask for.

**Staff engineer insight**: Understanding this pipeline transforms "the application is slow" into "the Tomcat worker pool is saturated because keep-alive connections are occupying all 200 threads while downstream latency is at p99=3s." That is the difference between restarting and actually fixing the problem.

## 2. Mental Model

```
The HTTP request pipeline:

  OS TCP Stack
  |  socket.accept()
  v
  Tomcat NIO Endpoint
  |  Acceptor thread -> Poller thread -> Worker thread
  v
  Coyote Adapter (Http11NioProtocol)
  |  Parses raw bytes -> HttpServletRequest/Response
  v
  Catalina Engine -> Host -> Context -> Wrapper
  |  Routes to the correct webapp/servlet
  v
  Filter Chain (ApplicationFilterChain)
  |  CharacterEncodingFilter -> CorsFilter -> SecurityFilter -> ...
  v
  DispatcherServlet
  |  doDispatch(request, response)
  v
  HandlerMapping -> HandlerAdapter -> Controller
  |  Find the method -> resolve args -> invoke -> handle return value
  v
  Response flows back through the same layers in reverse
```

```
Key data structures:

+-------------------------------------------------------------+
| NioEndpoint                                                 |
|  +-- acceptor: Acceptor[]     (accepts new TCP connections) |
|  +-- poller: Poller[]         (monitors NIO events)         |
|  +-- worker pool: Executor    (processes HTTP requests)     |
|  +-- maxConnections: 8192     (concurrent connections)      |
|  +-- acceptCount: 100         (OS backlog for pending conn) |
|  +-- maxThreads: 200          (worker thread pool size)     |
+-------------------------------------------------------------+
         |
         v
+-------------------------------------------------------------+
| DispatcherServlet                                           |
|  +-- handlerMappings: List<HandlerMapping>                  |
|  |   +-- RequestMappingHandlerMapping (@Controller methods) |
|  |   +-- BeanNameUrlHandlerMapping (legacy)                 |
|  |   +-- SimpleUrlHandlerMapping (static resources)         |
|  +-- handlerAdapters: List<HandlerAdapter>                  |
|  |   +-- RequestMappingHandlerAdapter                       |
|  |   +-- HttpRequestHandlerAdapter                          |
|  |   +-- SimpleControllerHandlerAdapter                     |
|  +-- handlerExceptionResolvers: List<HandlerExceptionResolver>
|  |   +-- ExceptionHandlerExceptionResolver                  |
|  +-- viewResolvers: List<ViewResolver>                      |
|  +-- localeResolver: LocaleResolver                         |
+-------------------------------------------------------------+
```

The mental model to internalize: **Tomcat owns the bytes. Spring owns the Java objects.** The boundary between them is `HttpServletRequest` -- a mutable object that carries both raw I/O state (InputStream position) and parsed semantic state (headers, parameters). Violating this boundary (reading the body twice, reading after the response is committed) is the root cause of 80% of HTTP-layer production incidents.

## 3. Internal Architecture

### Embedded Tomcat Startup in Spring Boot

```java
// Source: org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
// Called during refresh phase when ServletWebServerApplicationContext.onRefresh() fires.

public class TomcatServletWebServerFactory extends AbstractServletWebServerFactory
        implements ConfigurableTomcatWebServerFactory {

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        // -- Step 1: Create org.apache.catalina.startup.Tomcat instance --
        Tomcat tomcat = new Tomcat();

        // -- Step 2: Configure base directory (for temp files, work dir, webapps) --
        File baseDir = (this.baseDirectory != null) ? this.baseDirectory
                : createTempDir("tomcat");
        tomcat.setBaseDir(baseDir.getAbsolutePath());

        // -- Step 3: Create Connector -- THE critical component --
        // Default protocol: org.apache.coyote.http11.Http11NioProtocol
        Connector connector = new Connector(this.protocol);
        connector.setThrowOnFailure(true);
        connector.setProperty("port", String.valueOf(getPort()));  // default: 8080

        // -- Step 4: Create the NioEndpoint programmatically --
        tomcat.getService().addConnector(connector);
        customizeConnector(connector);  // Apply user-defined ConnectorCustomizer beans

        // -- Step 5: Configure the Engine, Host, and Context --
        tomcat.getHost().setAutoDeploy(false);

        // -- Step 6: Prepare the Context (web application) --
        prepareContext(tomcat.getHost(), initializers);

        // -- Step 7: Create TomcatWebServer, which calls tomcat.start() --
        return getTomcatWebServer(tomcat);
    }
}

// Source: org.springframework.boot.web.embedded.tomcat.TomcatWebServer
public class TomcatWebServer implements WebServer {

    public TomcatWebServer(Tomcat tomcat, boolean autoStart, Shutdown shutdown) {
        this.tomcat = tomcat;
        this.autoStart = autoStart;
        initialize();  // <-- starts the server
    }

    private void initialize() throws WebServerException {
        this.tomcat.start();
        startDaemonAwaitThread();
    }
}
```

### Tomcat Connector Configuration Deep Dive

```java
// Source: org.apache.coyote.http11.Http11NioProtocol
public class Http11NioProtocol extends AbstractHttp11Protocol<NioChannel> {
    public Http11NioProtocol() {
        super(new NioEndpoint());
    }
}

// Source: org.apache.tomcat.util.net.NioEndpoint
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel, SocketChannel> {

    private int maxConnections = 8192;
    private int acceptorThreadCount = 1;
    private int pollerThreadCount = Math.min(2, Runtime.getRuntime().availableProcessors());

    public void bind() throws Exception {
        serverSock = ServerSocketChannel.open();
        serverSock.socket().bind(addr, getAcceptCount());
        serverSock.configureBlocking(true);  // Acceptor uses blocking I/O
    }

    public void startInternal() throws Exception {
        if (!running) {
            running = true;
            poller = new Poller[getPollerThreadCount()];
            for (int i = 0; i < poller.length; i++) {
                poller[i] = new Poller();
                Thread pollerThread = new Thread(poller[i], getName() + "-ClientPoller-" + i);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }
            startAcceptorThread();
        }
    }
}
```

### The Connector -> Coyote Adapter -> Catalina Pipeline

```
+---------------------------------------------------------------------+
|                    EMBEDDED TOMCAT ARCHITECTURE                      |
|                                                                     |
|  OS TCP Stack                                                       |
|  |  TCP SYN -> SYN-ACK -> ACK (3-way handshake)                    |
|  |  Connection established on port 8080                             |
|  v                                                                  |
|  ServerSocketChannel (Java NIO)                                     |
|  |                                                                  |
|  v                                                                  |
+--+---------------------------------------------------------------+  |
|  | Acceptor Thread (1 thread, BLOCKING accept)                    |  |
|  |                                                                |  |
|  |  while (running) {                                             |  |
|  |      SocketChannel socket = serverSock.accept(); // BLOCKING   |  |
|  |      socket.configureBlocking(false);                          |  |
|  |      getPoller0().register(socket);                            |  |
|  |  }                                                             |  |
+--+---------------------------------------------------------------+  |
|  |                                                                |  |
|  v                                                                |  |
+--+---------------------------------------------------------------+  |
|  | Poller Thread(s) (2 by default, SELECTOR-based)                |  |
|  |                                                                |  |
|  |  while (running) {                                             |  |
|  |      selector.select(timeout);  // Wait for events              |  |
|  |      for (SelectionKey key : selectedKeys) {                   |  |
|  |          if (key.isReadable()) {                               |  |
|  |              processKey(sk, socketWrapper);                    |  |
|  |          }                                                     |  |
|  |      }                                                         |  |
|  |  }                                                             |  |
+--+---------------------------------------------------------------+  |
|  |                                                                |  |
|  v                                                                |  |
+--+---------------------------------------------------------------+  |
|  | Worker Thread Pool (maxThreads = 200)                          |  |
|  |                                                                |  |
|  |  SocketProcessorBase.doRun():                                  |  |
|  |    1. Read bytes from SocketChannel into Http11InputBuffer     |  |
|  |    2. Parse HTTP protocol: method, URI, headers, body          |  |
|  |    3. Create Request/Response objects (Coyote layer)           |  |
|  |    4. Invoke the Adapter (CoyoteAdapter.service())             |  |
+--+---------------------------------------------------------------+  |
|  |                                                                |  |
|  v                                                                |  |
+--+---------------------------------------------------------------+  |
|  | CoyoteAdapter.service(Request coyoteReq, Response coyoteRes)   |  |
|  |                                                                |  |
|  |  // Convert Coyote objects to javax.servlet objects:           |  |
|  |  Request coyoteReq -> HttpServletRequest                      |  |
|  |  Response coyoteRes -> HttpServletResponse`                   |  |
|  |                                                                |  |
|  |  // Route through Catalina pipeline:                           |  |
|  |  connector.getService()       // StandardService               |  |
|  |      .getContainer()          // StandardEngine                |  |
|  |      .getPipeline()           // StandardPipeline              |  |
|  |      .getFirst()              // EngineValve                   |  |
|  |      .invoke(request, response);                               |  |
+--+---------------------------------------------------------------+  |
|  |                                                                |  |
|  v                                                                |  |
|  Catalina Pipeline (Valve chain -- similar to Filter chain):      |  |
|                                                                   |  |
|  StandardEngineValve.invoke()                                     |  |
|    -> StandardHostValve.invoke()     (host = localhost)           |  |
|      -> StandardContextValve.invoke() (context = /)               |  |
|        -> StandardWrapperValve.invoke() (wrapper = DispatcherServlet)|
|          -> ALLOCATE servlet instance (pre-created at startup)    |  |
|          -> ApplicationFilterChain.doFilter(request, response)    |  |
|            -> CharacterEncodingFilter -> CorsFilter -> ...        |  |
|            -> DispatcherServlet.service(request, response)        |  |
+---------------------------------------------------------------------+
```

### DispatcherServlet.doDispatch() -- Full Trace

```java
// Source: org.springframework.web.servlet.DispatcherServlet
// This is THE central method. Every HTTP request flows through here.

public class DispatcherServlet extends FrameworkServlet {

    @Override
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
                // -- STEP 1: Check if multipart request --
                // If Content-Type is multipart/form-data, wrap request
                processedRequest = checkMultipart(request);
                multipartRequestParsed = (processedRequest != request);

                // -- STEP 2: Determine handler for this request --
                // Iterates all registered HandlerMapping beans, calls getHandler()
                mappedHandler = getHandler(processedRequest);
                if (mappedHandler == null) {
                    noHandlerFound(processedRequest, response);
                    return;  // 404
                }

                // -- STEP 3: Determine HandlerAdapter for the handler --
                HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());

                // -- STEP 4: Process last-modified header (GET/HEAD only) --
                String method = request.getMethod();
                boolean isGet = HttpMethod.GET.matches(method);
                if (isGet || HttpMethod.HEAD.matches(method)) {
                    long lastModified = ha.getLastModified(request, mappedHandler.getHandler());
                    if (new ServletWebRequest(request, response)
                            .checkNotModified(lastModified) && isGet) {
                        return;  // 304 Not Modified
                    }
                }

                // -- STEP 5: Execute pre-handle interceptors --
                if (!mappedHandler.applyPreHandle(processedRequest, response)) {
                    return;
                }

                // -- STEP 6: Actually invoke the handler --
                mv = ha.handle(processedRequest, response, mappedHandler.getHandler());

                // -- STEP 7: If async processing, exit early --
                if (asyncManager.isConcurrentHandlingStarted()) {
                    return;
                }

                // -- STEP 8: Apply default view name if needed --
                applyDefaultViewName(processedRequest, mv);

                // -- STEP 9: Execute post-handle interceptors --
                mappedHandler.applyPostHandle(processedRequest, response, mv);

            } catch (Exception ex) {
                dispatchException = ex;
            } catch (Throwable err) {
                dispatchException = new NestedServletException("Handler dispatch failed", err);
            }

            // -- STEP 10: Process dispatch result --
            processDispatchResult(processedRequest, response, mappedHandler, mv,
                    dispatchException);

        } catch (Exception ex) {
            triggerAfterCompletion(processedRequest, response, mappedHandler, ex);
        } catch (Throwable err) {
            triggerAfterCompletion(processedRequest, response, mappedHandler,
                    new NestedServletException("Handler processing failed", err));
        } finally {
            // -- STEP 12: Cleanup --
            if (asyncManager.isConcurrentHandlingStarted()) {
                if (mappedHandler != null) {
                    mappedHandler.applyAfterConcurrentHandlingStarted(processedRequest, response);
                }
            } else {
                if (multipartRequestParsed) {
                    cleanupMultipart(processedRequest);
                }
            }
        }
    }
}
```

### HandlerMapping.getHandler() Resolution Logic

```java
// Source: org.springframework.web.servlet.handler.AbstractHandlerMapping

public final HandlerExecutionChain getHandler(HttpServletRequest request) throws Exception {
    Object handler = getHandlerInternal(request);
    if (handler == null) {
        handler = getDefaultHandler();
    }
    if (handler == null) {
        return null;  // 404
    }
    if (handler instanceof String handlerName) {
        handler = obtainApplicationContext().getBean(handlerName);
    }
    HandlerExecutionChain executionChain = getHandlerExecutionChain(handler, request);
    return executionChain;
}

// Source: org.springframework.web.servlet.handler.AbstractHandlerMethodMapping

@Override
protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
    String lookupPath = initLookupPath(request);  // e.g., "/orders/42"
    this.mappingRegistry.acquireReadLock();
    try {
        HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
        return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null);
    } finally {
        this.mappingRegistry.releaseReadLock();
    }
}
```

### RequestMappingHandlerMapping Internals -- How Mappings Are Built

```java
// Source: org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

// -- BUILD-TIME: How @GetMapping("/orders/{id}") becomes a mapping --

@Override
public void afterPropertiesSet() {
    initHandlerMethods();  // Scan ALL beans for @Controller/@RequestMapping
}

protected void initHandlerMethods() {
    for (String beanName : getCandidateBeanNames()) {
        if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
            processCandidateBean(beanName);
        }
    }
    handlerMethodsInitialized(getHandlerMethods());
}

protected void detectHandlerMethods(Object handler) {
    Class<?> handlerType = (handler instanceof String beanName
            ? obtainApplicationContext().getType(beanName) : handler.getClass());

    if (handlerType != null) {
        Class<?> userType = ClassUtils.getUserClass(handlerType);

        Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
                (MethodIntrospector.MetadataLookup<T>) method -> {
                    try {
                        return getMappingForMethod(method, userType);
                    } catch (Throwable ex) {
                        throw new IllegalStateException("Invalid mapping", ex);
                    }
                });

        methods.forEach((method, mapping) -> {
            Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
            registerHandlerMethod(handler, invocableMethod, mapping);
        });
    }
}

// For @GetMapping(value = "/orders/{id}", params = "status", headers = "X-Version=2"):
// RequestMappingInfo {
//     patterns: ["/orders/{id}"]
//     methods: [GET]
//     params: [Condition { name="status", value=none }]
//     headers: [Condition { name="X-Version", value="2" }]
//     consumes: []  (no @Consumes)
//     produces: []  (no @Produces)
// }
```

### Static Mappings vs Runtime Lookup -- The MappingRegistry

```java
class MappingRegistry {
    // All registered mappings: RequestMappingInfo -> HandlerMethod
    private final Map<T, MappingRegistration<T>> registry = new HashMap<>();

    // For fast path-based lookup: path pattern -> RequestMappingInfo list
    private final MultiValueMap<String, T> pathLookup = new LinkedMultiValueMap<>();

    // For unambiguous direct lookup (exact paths, no variables)
    private final Map<String, T> nameLookup = new ConcurrentHashMap<>();

    protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) {
        List<Match> matches = new ArrayList<>();

        // Phase 1: Find matching path patterns
        List<T> directPathMatches = this.pathLookup.get(lookupPath);
        if (directPathMatches != null) {
            addMatchingMappings(directPathMatches, matches, request);
        }

        if (matches.isEmpty()) {
            addMatchingMappings(this.registry.keySet(), matches, request);
        }

        // Phase 2: Sort and pick best match
        if (!matches.isEmpty()) {
            MatchComparator comparator = new MatchComparator(getMappingComparator(request));
            matches.sort(comparator);
            Match bestMatch = matches.get(0);

            // Phase 3: Check for ambiguous matches
            if (matches.size() > 1) {
                Match secondBestMatch = matches.get(1);
                if (comparator.compare(bestMatch, secondBestMatch) == 0) {
                    throw new IllegalStateException(
                            "Ambiguous handler methods mapped for '" + lookupPath + "'");
                }
            }

            request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, bestMatch.getHandlerMethod());
            handleMatch(bestMatch.mapping, lookupPath, request);
            return bestMatch.getHandlerMethod();
        }
        return null;  // No match -> 404
    }
}
```

### HandlerAdapter Chain -- Selecting the Right Adapter

```java
// Source: DispatcherServlet.getHandlerAdapter()

protected HandlerAdapter getHandlerAdapter(Object handler) throws ServletException {
    if (this.handlerAdapters != null) {
        for (HandlerAdapter adapter : this.handlerAdapters) {
            if (adapter.supports(handler)) {
                return adapter;
            }
        }
    }
    throw new ServletException("No adapter for handler [" + handler + "]");
}

// 1. RequestMappingHandlerAdapter: supports HandlerMethod instances
public final boolean supports(Object handler) {
    return (handler instanceof HandlerMethod);
}

// 2. HttpRequestHandlerAdapter: supports HttpRequestHandler
public boolean supports(Object handler) {
    return (handler instanceof HttpRequestHandler);
}

// 3. SimpleControllerHandlerAdapter: supports Controller (pre-Spring 2.5)
public boolean supports(Object handler) {
    return (handler instanceof Controller);
}
```

### HandlerMethodArgumentResolver Chain -- How Arguments Are Resolved

```java
// Source: org.springframework.web.method.support.InvocableHandlerMethod

protected Object invokeForRequest(NativeWebRequest request, ModelAndViewContainer mavContainer,
        Object... providedArgs) throws Exception {

    // Step 1: Resolve all method arguments
    Object[] args = getMethodArgumentValues(request, mavContainer, providedArgs);

    // Step 2: Invoke the controller method via reflection
    return doInvoke(args);
}

private Object[] getMethodArgumentValues(NativeWebRequest request,
        ModelAndViewContainer mavContainer, Object... providedArgs) throws Exception {

    MethodParameter[] parameters = getMethodParameters();
    Object[] args = new Object[parameters.length];

    for (int i = 0; i < parameters.length; i++) {
        MethodParameter parameter = parameters[i];
        parameter.initParameterNameDiscovery(this.parameterNameDiscoverer);
        args[i] = findProvidedArgument(parameter, providedArgs);
        if (args[i] != null) continue;

        if (!this.resolvers.supportsParameter(parameter)) {
            throw new IllegalStateException("No argument resolver for parameter " + i);
        }
        args[i] = this.resolvers.resolveArgument(parameter, mavContainer,
                request, this.dataBinderFactory);
    }
    return args;
}
```

### Argument Resolver Chain -- Default Order and Matching Logic

```java
// DEFAULT RESOLVER ORDER (simplified):

// 1. RequestParamMethodArgumentResolver (useDefaultResolution=false)
//    Handles: @RequestParam annotation
//
// 2. RequestParamMethodArgumentResolver (useDefaultResolution=true)
//    Handles: Map<String,String> as @RequestParam Map
//
// 3. PathVariableMethodArgumentResolver
//    Supports: @PathVariable -> extracts from URI template variables
//
// 4. RequestHeaderMethodArgumentResolver
//    Supports: @RequestHeader -> extracts from HTTP headers
//
// 5. ServletCookieValueMethodArgumentResolver
//    Supports: @CookieValue -> extracts from cookies
//
// 6. ExpressionValueMethodArgumentResolver
//    Supports: @Value -> resolves SpEL expressions
//
// 7. RequestAttributeMethodArgumentResolver
//    Supports: @RequestAttribute -> request attributes
//
// 8. ServletModelAttributeMethodProcessor (annotationNotRequired=false)
//    Supports: @ModelAttribute annotation
//
// 9. ServletModelAttributeMethodProcessor (annotationNotRequired=true)
//    Supports: non-simple types WITHOUT annotation (FALLBACK)
//
// 10. RequestResponseBodyMethodProcessor
//     Supports: @RequestBody AND @ResponseBody annotations
//     Reads body via HttpMessageConverter chain
//
// 11. HttpEntityMethodProcessor
//     Supports: HttpEntity<?> parameter type
//
// Each resolver's supportsParameter() is checked IN ORDER.
// The FIRST one that returns true handles that parameter.

// Example: @RequestBody OrderRequest body
//   -> Resolver 10 (RequestResponseBodyMethodProcessor):
//       @RequestBody annotation? YES -> supports = true -> handles it!

// Example: @PathVariable Long id
//   -> Resolver 3 (PathVariableMethodArgumentResolver):
//       @PathVariable annotation? YES -> supports = true -> handles it!
```

### HandlerMethodReturnValueHandler Chain

```java
// DEFAULT RETURN VALUE HANDLER ORDER:

// 1. ModelAndViewMethodReturnValueHandler
//    Supports: ModelAndView return type (legacy)
//
// 2. ModelMethodProcessor
//    Supports: Model return type
//
// 3. ViewMethodReturnValueHandler
//    Supports: View return type
//
// 4. HttpEntityMethodProcessor (for ResponseEntity, HttpEntity RETURN values)
//    Supports: HttpEntity<T>, ResponseEntity<T>
//    Writes status, headers, and body to the response
//
// 5. RequestResponseBodyMethodProcessor
//    Supports: @ResponseBody annotation on method
//    Serializes return value via HttpMessageConverter (e.g., Jackson -> JSON)
//
// 6. ViewNameMethodReturnValueHandler
//    Supports: void or String as view name (legacy MVC)
//
// 7. MapMethodProcessor
//    Supports: Map return type
//
// 8. StreamingResponseBodyReturnValueHandler
//    Supports: StreamingResponseBody for async streaming
//
// 9. DeferredResultMethodReturnValueHandler
//    Supports: DeferredResult<T>, ListenableFuture, CompletionStage
//
// 10. CallableMethodReturnValueHandler
//     Supports: Callable<T> -> async request processing

// Example: @RestController method returns ResponseEntity<Order>:
//   -> Checks 1: ModelAndView? No
//   -> Checks 2-3: No
//   -> Checks 4 (HttpEntityMethodProcessor): HttpEntity subtype? YES
//       -> Extract status code, headers, body
//       -> Use HttpMessageConverter to serialize body to JSON
//       -> Write to HttpServletResponse.getOutputStream()
```

### Exception Resolution -- @ExceptionHandler and @ControllerAdvice Internals

```java
// Source: org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver

// -- In processDispatchResult() --

private void processDispatchResult(HttpServletRequest request, HttpServletResponse response,
        HandlerExecutionChain mappedHandler, ModelAndView mv, Exception exception)
        throws Exception {

    if (exception != null) {
        if (exception instanceof ModelAndViewDefiningException mavDefEx) {
            mv = mavDefEx.getModelAndView();
        } else {
            // ITERATE all HandlerExceptionResolver beans:
            //   1. ExceptionHandlerExceptionResolver (@ExceptionHandler methods)
            //   2. ResponseStatusExceptionResolver (@ResponseStatus)
            //   3. DefaultHandlerExceptionResolver (standard Spring exceptions)
            Object handler = (mappedHandler != null ? mappedHandler.getHandler() : null);
            mv = processHandlerException(request, response, handler, exception);
        }
    }
}

// -- ExceptionHandlerExceptionResolver.doResolveHandlerMethodException() --

@Override
protected ModelAndView doResolveHandlerMethodException(HttpServletRequest request,
        HttpServletResponse response, HandlerMethod handlerMethod, Exception exception) {

    // Step 1: Find the best @ExceptionHandler method
    //   1a. Check the CONTROLLER that threw (local @ExceptionHandler)
    //   1b. Check all @ControllerAdvice beans (global @ExceptionHandler)
    ServletInvocableHandlerMethod exceptionHandlerMethod =
            getExceptionHandlerMethod(handlerMethod, exception);

    if (exceptionHandlerMethod == null) {
        return null;  // Pass to next resolver
    }

    // Step 2: Build argument resolvers for this invocation
    // @ExceptionHandler params: Exception, HttpServletRequest, Model, etc.
    ArrayList<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
    resolvers.add(new SessionAttributeMethodArgumentResolver());
    resolvers.add(new RequestAttributeMethodArgumentResolver());

    // Step 3: Build return value handlers
    // @ExceptionHandler returns: ResponseEntity, @ResponseBody, String, ModelAndView
    ArrayList<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();
    handlers.add(new ModelAndViewMethodReturnValueHandler());
    handlers.add(new HttpEntityMethodProcessor(...));
    handlers.add(new RequestResponseBodyMethodProcessor(...));

    // Step 4: Invoke the @ExceptionHandler method
    exceptionHandlerMethod.setHandlerMethodArgumentResolvers(resolvers);
    exceptionHandlerMethod.setHandlerMethodReturnValueHandlers(handlers);
    return exceptionHandlerMethod.invokeAndHandle(webRequest, mavContainer,
            handlerMethod, exception);
}

// -- How getExceptionHandlerMethod() finds the right handler --

// 1. Check the controller class: find @ExceptionHandler methods
//    whose exception types match (isAssignableFrom)
//    If multiple match, pick the most specific.

// 2. If no local handler, check @ControllerAdvice beans (cached at startup):
public class ExceptionHandlerExceptionResolver
        implements ApplicationContextAware, InitializingBean {

    private final Map<ControllerAdviceBean, ExceptionHandlerMethodResolver>
            exceptionHandlerAdviceCache = new LinkedHashMap<>();

    @Override
    public void afterPropertiesSet() {
        for (ControllerAdviceBean adviceBean : ControllerAdviceBean
                .findAnnotatedBeans(getApplicationContext())) {
            Class<?> beanType = adviceBean.getBeanType();
            if (beanType != null) {
                ExceptionHandlerMethodResolver resolver =
                        new ExceptionHandlerMethodResolver(beanType);
                if (resolver.hasExceptionMappings()) {
                    this.exceptionHandlerAdviceCache.put(adviceBean, resolver);
                }
            }
        }
    }
}
```

### The Full Pipeline -- ASCII Diagram

```
                    +----------------------------------------------------------+
                    |          COMPLETE REQUEST PIPELINE                       |
                    |          Socket -> Controller -> Socket                  |
                    +----------------------------------------------------------+

  +-----------------------------------------------------------------------------+
  | LAYER 0: OS & JVM                                                           |
  |                                                                             |
  |  Kernel TCP stack receives SYN on port 8080                                 |
  |    -> Completes 3-way handshake                                              |
  |    -> Places connection in accept queue (backlog = acceptCount = 100)        |
  |  ServerSocketChannel.accept() returns SocketChannel                         |
  +-------------------------------------+---------------------------------------+
                                        |
  +-------------------------------------v---------------------------------------+
  | LAYER 1: Tomcat NioEndpoint -- Acceptor                                     |
  |                                                                             |
  |  Acceptor.accept():                                                         |
  |    SocketChannel socket = serverSock.accept();    // BLOCKING call          |
  |    socket.configureBlocking(false);               // Switch to NIO         |
  |    NioChannel channel = new NioChannel(socket);                             |
  |    poller.register(channel);                      // Hand to Poller         |
  |                                                                             |
  |  Thread: http-nio-8080-Acceptor (daemon, 1 thread)                          |
  +-------------------------------------+---------------------------------------+
                                        |
  +-------------------------------------v---------------------------------------+
  | LAYER 2: Tomcat NioEndpoint -- Poller                                       |
  |                                                                             |
  |  Poller.run():                                                              |
  |    selector.select(1000);                          // Wait for I/O events   |
  |    for (SelectionKey key : selector.selectedKeys()):                        |
  |      if (key.isReadable()):                                                 |
  |        NioSocketWrapper wrapper = (NioSocketWrapper) key.attachment();      |
  |        processKey(sk, wrapper);                    // Dispatch to worker    |
  |                                                                             |
  |  Thread: http-nio-8080-ClientPoller-0 (daemon, 2 threads by default)       |
  +-------------------------------------+---------------------------------------+
                                        |
  +-------------------------------------v---------------------------------------+
  | LAYER 3: Tomcat Worker Thread -- SocketProcessor                            |
  |                                                                             |
  |  SocketProcessorBase.doRun():                                               |
  |    state = handler.process(wrapper, SocketEvent.OPEN_READ);                 |
  |    // -> Http11Processor.service():                                         |
  |    //   1. Parse request line: GET /orders/42?status=active HTTP/1.1        |
  |    //   2. Parse headers: Host, Accept, Content-Type, etc.                  |
  |    //   3. Parse body (if POST/PUT) -- lazy, on-demand                      |
  |    //   4. Create Request (coyote) and Response (coyote)                    |
  |    //   5. adapter.service(coyoteReq, coyoteRes)                            |
  |                                                                             |
  |  Thread: http-nio-8080-exec-1 (from pool, maxThreads=200)                   |
  +-------------------------------------+---------------------------------------+
                                        |
  +-------------------------------------v---------------------------------------+
  | LAYER 4: CoyoteAdapter -> Catalina Pipeline                                 |
  |                                                                             |
  |  CoyoteAdapter.service(Request coyoteReq, Response coyoteRes):              |
  |    request = new RequestFacade(coyoteReq);                                  |
  |    response = new ResponseFacade(coyoteRes);                                |
  |                                                                             |
  |    connector.getService().getMapper().map(serverName, coyoteReq)            |
  |    // -> Finds matching Host -> Context -> Wrapper                          |
  |                                                                             |
  |    StandardWrapperValve.invoke():                                           |
  |      servlet = wrapper.allocate();  // Get or create the servlet           |
  |      filterChain = ApplicationFilterFactory.createFilterChain(...)          |
  |      filterChain.doFilter(request, response);                               |
  +-------------------------------------+---------------------------------------+
                                        |
  +-------------------------------------v---------------------------------------+
  | LAYER 5: Filter Chain                                                       |
  |                                                                             |
  |  ApplicationFilterChain.doFilter(request, response):                        |
  |    filter[0] -> CharacterEncodingFilter  (sets request/response encoding)    |
  |    filter[1] -> CorsFilter                (handles CORS preflight/headers)   |
  |    filter[2] -> OncePerRequestFilter      (ensures single execution)         |
  |    filter[3] -> SecurityFilterChain       (Spring Security, if present)      |
  |    filter[4] -> RequestContextFilter      (exposes request to current thread)|
  |    filter[5] -> FormContentFilter         (parses form data)                 |
  |    filter[6] -> HiddenHttpMethodFilter    (PUT/DELETE via _method param)     |
  |    filter[7] -> ... any custom filters ...                                   |
  |    filter[N] -> DispatcherServlet.service()  <-- The final "filter"          |
  +-------------------------------------+---------------------------------------+
                                        |
  +-------------------------------------v---------------------------------------+
  | LAYER 6: DispatcherServlet -> doDispatch()                                  |
  |                                                                             |
  |  checkMultipart(request)                     // Parse multipart if needed   |
  |  mappedHandler = getHandler(request)         // HandlerMapping chain        |
  |    -> RequestMappingHandlerMapping.getHandler()                              |
  |      -> lookupHandlerMethod("/orders/42", request)                           |
  |        -> "orders/{id}" matches, method=GET matches                          |
  |        -> extract path variable: id = "42"                                   |
  |      -> return HandlerMethod(OrderController@4f3c, getOrder(Long))           |
  |  ha = getHandlerAdapter(mappedHandler)       // Find RequestMappingHandlerAdapter
  |  mappedHandler.applyPreHandle()              // Pre-interceptors            |
  |                                                                             |
  |  mv = ha.handle(request, response, handlerMethod)                           |
  |    // -> RequestMappingHandlerAdapter.handleInternal()                      |
  |    //    -> invokeHandlerMethod()                                            |
  |    //       -> resolve arguments:                                            |
  |    //           @PathVariable Long id -> PathVariableMethodArgumentResolver  |
  |    //           @RequestParam String status -> RequestParamMethodArgumentResolver
  |    //       -> invoke: controller.getOrder(42L, "active")                    |
  |    //       -> handle return:                                                |
  |    //           ResponseEntity<Order> -> HttpEntityMethodProcessor           |
  |    //             -> status=200, Content-Type: application/json              |
  |    //             -> body via MappingJackson2HttpMessageConverter            |
  |    //             -> write to response.getOutputStream()                     |
  |                                                                             |
  |  mappedHandler.applyPostHandle()             // Post-interceptors           |
  |  mappedHandler.triggerAfterCompletion()      // After-completion hooks      |
  +-----------------------------------------------------------------------------+
```

## 4. Runtime Behavior

### Scenario 1: GET /orders/42?status=active

```
Timeline and thread assignment:

T=0ms   TCP connection established on port 8080
T=1ms   Acceptor thread accepts SocketChannel, registers with Poller[0]
T=2ms   Poller[0] detects readable event, dispatches to worker thread pool
T=3ms   Worker thread http-nio-8080-exec-7 picks up the task

  exec-7:
    +-- Parse bytes from input buffer (Http11InputBuffer):
    |   "GET /orders/42?status=active HTTP/1.1\r\n
    |    Host: localhost:8080\r\n
    |    Accept: application/json\r\n\r\n"
    |   -> method = "GET", uri = "/orders/42?status=active", protocol = "HTTP/1.1"
    |   -> headers = {Host: localhost:8080, Accept: application/json}
    |
    +-- CoyoteAdapter wraps as RequestFacade/ResponseFacade
    +-- CoyoteAdapter.service():
    |   +-- Catalina valves: Engine -> Host -> Context
    |   +-- StandardWrapperValve: allocates DispatcherServlet instance
    |   +-- ApplicationFilterChain.doFilter()
    |       +-- CharacterEncodingFilter.doFilter()
    |       +-- FormContentFilter.doFilter()
    |       +-- RequestContextFilter.doFilter()  // sets RequestAttributes for ThreadLocal
    |       +-- DispatcherServlet.doDispatch()

T=5ms   DispatcherServlet.doDispatch():

    getHandler(request):
      -> RequestMappingHandlerMapping.getHandler()
        -> lookupHandlerMethod("/orders/42", GET)
          -> pathLookup: no direct "/orders/42" match
          -> iterate all patterns: "/orders/{id}" matches via AntPathMatcher
          -> method=GET checks out -> Match found
          -> extract URI template variables: {id: "42"}
          -> stored in request attribute: HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE

      -> returns HandlerExecutionChain {
          handler: HandlerMethod(OrderController@5a07, getOrder(Long, String)),
          interceptors: [LoggingInterceptor, MetricsInterceptor]
        }

    getHandlerAdapter(handler):
      -> handler instanceof HandlerMethod -> RequestMappingHandlerAdapter

    mappedHandler.applyPreHandle():
      -> LoggingInterceptor.preHandle()  -> true
      -> MetricsInterceptor.preHandle()  -> true

T=6ms   ha.handle(request, response, handlerMethod):

    invokeHandlerMethod():
      -> RequestMappingHandlerAdapter.invokeHandlerMethod(...)

      -> getMethodArgumentValues(request):
          param[0] = Long id
            -> PathVariableMethodArgumentResolver.supportsParameter() -> true
            -> resolveArgument():
                value = URI_TEMPLATE_VARIABLES.get("id") -> "42"
                typeConverter.convert("42", Long.class) -> 42L

          param[1] = String status
            -> RequestParamMethodArgumentResolver.supportsParameter() -> true
            -> resolveArgument():
                String value = request.getParameter("status") -> "active"

      -> doInvoke([42L, "active"]):
          OrderController.getOrder(42L, "active")
            -> OrderService.findById(42L)
            -> OrderRepository.findById(42L) -> Optional[Order{id=42, ...}]
            -> returns ResponseEntity.ok(order)

T=8ms   handleReturnValue():
      -> HttpEntityMethodProcessor.supportsReturnType() -> true (ResponseEntity)
      -> handleReturnValue():
          ResponseEntity<Order> entity = ResponseEntity.ok(order)
          -> status = 200 OK, headers = {}, body = Order{...}

          -> ContentNegotiationManager.resolveMediaTypes():
              Accept: "application/json" -> produces = [application/json]
          -> selectConverter(Order.class, application/json):
              -> MappingJackson2HttpMessageConverter canWrite() -> true
          -> converter.write(order, application/json, httpOutputMessage):
              -> ObjectMapper.writeValue(response.getOutputStream(), order)
              -> "{\"id\":42,\"status\":\"active\",\"items\":[...]}"

T=10ms  response headers set:
          Content-Type: application/json
          Content-Length: 156
          Status: 200 OK

    mappedHandler.applyPostHandle():
      -> LoggingInterceptor.postHandle()  (request completed successfully)
      -> MetricsInterceptor.postHandle()  (record latency: 5ms)

    triggerAfterCompletion():
      -> LoggingInterceptor.afterCompletion()
      -> MetricsInterceptor.afterCompletion()  (record full processing: 7ms)

T=11ms  response bytes flushed to SocketChannel
T=12ms  client receives HTTP response
T=13ms  Connection: keep-alive -- socket remains open for next request
```

### Scenario 2: POST /orders with JSON Body -- Exception Flow

```
Timeline for: POST /orders with invalid JSON -> @ExceptionHandler catches:

T=0ms   POST /orders, Content-Type: application/json, Body: {"amount": -5}

T=5ms   ha.handle(request, response, handlerMethod):

    resolve argument: @RequestBody OrderRequest body
      -> RequestResponseBodyMethodProcessor.supportsParameter() -> true
      -> readWithMessageConverters():
          Content-Type: application/json -> MappingJackson2HttpMessageConverter
          -> objectMapper.readValue(inputStream, OrderRequest.class)
          -> Creates OrderRequest{amount: -5, ...}

    doInvoke([OrderRequest{amount=-5}]):
      OrderController.createOrder(OrderRequest{amount=-5})
        -> OrderService.validate(orderRequest)
          -> if (amount < 0) throw new InvalidAmountException("Amount must be positive")

T=7ms   Exception propagates up from doInvoke()
    invokeAndHandle() catches it at InvocableHandlerMethod level
    -> wraps as InvocationTargetException
    -> unwrapped -> InvalidAmountException

T=8ms   Back in DispatcherServlet.doDispatch():
    catch (Exception ex) { dispatchException = ex; }
    -> processDispatchResult(request, response, mappedHandler, mv, dispatchException):

    processHandlerException(request, response, handler, InvalidAmountException):
      -> ITERATE HandlerExceptionResolver chain:

        [1] ExceptionHandlerExceptionResolver.resolveException():
            -> getExceptionHandlerMethod(handlerMethod, InvalidAmountException):
                -> Check OrderController for @ExceptionHandler(InvalidAmountException.class)
                -> Found: handleInvalidAmount(InvalidAmountException ex)
            -> Invoke: handleInvalidAmount(InvalidAmountException)
                -> Returns ResponseEntity<ErrorResponse>().status(400).body(errorBody)
            -> HttpEntityMethodProcessor handles the ResponseEntity
            -> Writes 400 Bad Request with JSON error body

T=10ms  response:
          Status: 400 Bad Request
          Content-Type: application/json
          Body: {"error":"INVALID_AMOUNT","message":"Amount must be positive"}
```


## 5. Request Flow Diagrams

### Full HTTP Request Path Through All Layers

```
+-------+     +---------+     +----------+     +----------+     +---------------+     +----------+
|Client |     |  Linux  |     |  Tomcat  |     | Catalina |     | Dispatcher    |     |Controller|
|(HTTP) |     |  TCP/IP |     |  NIO     |     | Pipeline |     | Servlet       |     |  Method  |
+---+---+     +----+----+     +----+-----+     +----+-----+     +------+--------+     +----+-----+
    |              |               |                |                |                   |
    | TCP SYN      |               |                |                |                   |
    |------------->|               |                |                |                   |
    | TCP SYN-ACK  |               |                |                |                   |
    |<-------------|               |                |                |                   |
    | TCP ACK      |               |                |                |                   |
    |------------->|               |                |                |                   |
    |              |  established  |                |                |                   |
    |              |-------------->|                |                |                   |
    |              |  socket.accept|                |                |                   |
    | GET /orders  |               |                |                |                   |
    |------------->|               |                |                |                   |
    |              |  data ready   |                |                |                   |
    |              |-------------->|                |                |                   |
    |              |               | poller.dispatch|                |                   |
    |              |               |--------------->|                |                   |
    |              |               |                | CoyoteAdapter  |                   |
    |              |               |                | .service()     |                   |
    |              |               |                |--------------->|                   |
    |              |               |                |                | FilterChain       |
    |              |               |                |                | filters           |
    |              |               |                |                | doDispatch()      |
    |              |               |                |                | getHandler()      |
    |              |               |                |                | getHandlerAdapter |
    |              |               |                |                | ha.handle()       |
    |              |               |                |                |---------->|       |
    |              |               |                |                | resolve   |       |
    |              |               |                |                | arguments |       |
    |              |               |                |                | invoke()  |       |
    |              |               |                |                |---------->|       |
    |              |               |                |                |  return   |       |
    |              |               |                |                |<----------|       |
    |              |               |                |                | handle    |       |
    |              |               |                |                | return    |       |
    |              |               |                |                |postHandle()       |
    |              |               |                |                |afterCompletion()  |
    |              |               |                |                | flush response    |
    |              |<--------------|<---------------|<---------------|                   |
    | HTTP 200 OK  |               |                |                |                   |
    |<-------------|               |                |                |                   |
```

### Exception Flow With @ExceptionHandler

```
  Controller       DispatcherServlet     ExceptionHandlerExcResolver    @ExceptionHandler
      |                  |                          |                         |
      | invoke()         |                          |                         |
      |------------------>                          |                         |
      | throw            |                          |                         |
      | InvalidAmount    |                          |                         |
      |------------------>                          |                         |
      |                  |                          |                         |
      |                  | catch(Exception)         |                         |
      |                  | in doDispatch()          |                         |
      |                  |                          |                         |
      |                  | processDispatchResult()  |                         |
      |                  |                          |                         |
      |                  | processHandlerException()|                         |
      |                  |------------------------->|                         |
      |                  |                          |                         |
      |                  |                          | getExceptionHandlerMethod|
      |                  |                          |  Check controller       |
      |                  |                          |  Check @ControllerAdvice|
      |                  |                          |----Found--->            |
      |                  |                          |                         |
      |                  |                          | invokeAndHandle()       |
      |                  |                          |------------------------>|
      |                  |                          |  resolve args           |
      |                  |                          |  invoke handler         |
      |                  |                          |------------------------>|
      |                  |                          |  return ResponseEntity  |
      |                  |                          |<------------------------|
      |                  |                          |  handleReturnValue()    |
      |                  |                          |  -> write 400 JSON body |
      |                  |  ModelAndView            |                         |
      |                  |<-------------------------|                         |
      |                  | render() / write body    |                         |
      |                  | afterCompletion()        |                         |
```

### Async Request Handling Path

```
  Worker Thread        WebAsyncManager       Async Task Thread       Response Thread
       |                      |                       |                     |
       | doDispatch()         |                       |                     |
       |--------------------->|                       |                     |
       | ha.handle() returns  |                       |                     |
       | Callable<Order>      |                       |                     |
       |                      | startAsyncProcessing()|                     |
       |                      |---------------------->|                     |
       | return (worker       |                       | process Callable    |
       | thread RELEASED      |                       | in separate thread  |
       | back to pool)        |                       |                     |
       |                      |                       | result = callable   |
       |                      |                       | .call()             |
       |                      |                       |                     |
       |                      |                       | setResult(result)   |
       |                      |<----------------------|                     |
       |                      | dispatch()            |                     |
       |                      |-------------------------------------------->|
       |                      |                       |   handle return     |
       |                      |                       |   value (serialize) |
       |                      |                       |   flush response    |
```

## 6. Lifecycle Diagrams

### Tomcat Server Startup Lifecycle Within Spring Boot

```
  SpringApplication.run()
       |
       v
  AbstractApplicationContext.refresh()
       |
       +-- ... steps 1-8 ...
       |
       +-- onRefresh()  <-- Step 9
       |   |
       |   +-- ServletWebServerApplicationContext.onRefresh()
       |       |
       |       +-- createWebServer()
       |           |
       |           +-- TomcatServletWebServerFactory.getWebServer()
       |               |
       |               +-- 1. new Tomcat()
       |               +-- 2. new Connector(protocol)
       |               |      +-- Creates NioEndpoint internally
       |               +-- 3. tomcat.getService().addConnector()
       |               +-- 4. configureEngine(tomcat.getEngine())
       |               +-- 5. prepareContext(host, initializers)
       |               |      +-- TomcatEmbeddedContext with classloader,
       |               |          docBase (temp dir), and servlet registrations
       |               |
       |               +-- 6. new TomcatWebServer(tomcat, autoStart=true)
       |                   |
       |                   +-- initialize()
       |                       |
       |                       +-- tomcat.start()
       |                           |
       |                           +-- Server.start()
       |                           +-- Service.start()
       |                           |   +-- Engine.start()
       |                           |   +-- Connector.start()
       |                           |       +-- NioEndpoint.startInternal()
       |                           |           +-- bind(): ServerSocketChannel.bind(port)
       |                           |           +-- Start Poller threads (ClientPoller-0,1)
       |                           |           +-- Start Acceptor thread
       |                           |               +-- serverSock.accept() -> NOW LISTENING
       |                           +-- startDaemonAwaitThread()
       |
       +-- ... steps 10-12 ...
       |
       +-- finishRefresh() -> ContextRefreshedEvent -> READY for traffic
```

### DispatcherServlet Initialization (initStrategies)

```
  DispatcherServlet.init() (called at startup if load-on-startup is set)
       |
       +-- initServletBean()
       |   |
       |   +-- initStrategies(ApplicationContext context)
       |       |
       |       +-- initMultipartResolver(context)
       |       |   +-- Look for MultipartResolver bean (StandardServletMultipartResolver)
       |       |
       |       +-- initLocaleResolver(context)
       |       |   +-- Look for LocaleResolver bean (AcceptHeaderLocaleResolver default)
       |       |
       |       +-- initThemeResolver(context)
       |       |   +-- Look for ThemeResolver bean (FixedThemeResolver default)
       |       |
       |       +-- initHandlerMappings(context)
       |       |   |
       |       |   +-- Find all HandlerMapping beans (ordered)
       |       |   |   +-- RequestMappingHandlerMapping (priority 0)
       |       |   |   +-- BeanNameUrlHandlerMapping (priority 2)
       |       |   |   +-- SimpleUrlHandlerMapping for /**, /favicon.ico (priority MAX)
       |       |   |
       |       |   +-- Trigger afterPropertiesSet() on each -> builds mapping registry
       |       |       |
       |       |       +-- RequestMappingHandlerMapping.initHandlerMethods():
       |       |           +-- Scan all beans for @Controller/@RequestMapping
       |       |           +-- For each controller method: extract RequestMappingInfo
       |       |           +-- Register in MappingRegistry (pathLookup, registry, nameLookup)
       |       |
       |       +-- initHandlerAdapters(context)
       |       |   +-- Find all HandlerAdapter beans
       |       |       +-- RequestMappingHandlerAdapter
       |       |       +-- HttpRequestHandlerAdapter
       |       |       +-- SimpleControllerHandlerAdapter
       |       |
       |       +-- initHandlerExceptionResolvers(context)
       |       |   +-- Find all HandlerExceptionResolver beans
       |       |       +-- ExceptionHandlerExceptionResolver
       |       |       |   +-- Scans @ControllerAdvice beans, caches @ExceptionHandler methods
       |       |       +-- ResponseStatusExceptionResolver
       |       |       +-- DefaultHandlerExceptionResolver
       |       |
       |       +-- initRequestToViewNameTranslator(context)
       |       +-- initViewResolvers(context)
       |       +-- initFlashMapManager(context)
       |
       +-- DispatcherServlet ready to process requests
```

### RequestMappingHandlerMapping Bean Lifecycle and Registration

```
  +----------------------------------------------------------------------+
  |             HandlerMapping REGISTRATION AND INITIALIZATION            |
  +----------------------------------------------------------------------+

  1. BEAN DEFINITION PHASE (refresh step 5)
     +-- WebMvcAutoConfiguration registers RequestMappingHandlerMapping BD
     +-- Stored in beanDefinitionMap

  2. BEAN INSTANTIATION PHASE (refresh step 11)
     +-- getBean("requestMappingHandlerMapping")
     +-- Constructor called -> creates empty RequestMappingHandlerMapping
     +-- populateBean(): @Autowired ApplicationContext injected
     +-- initializeBean():
     |   +-- InitializingBean.afterPropertiesSet():
     |   |   +-- super.afterPropertiesSet() -> AbstractHandlerMethodMapping:
     |   |       +-- initHandlerMethods()
     |   |           |
     |   |           +-- Get all bean names from ApplicationContext
     |   |           +-- For each bean: isHandler(beanType)?
     |   |           |   +-- Check for @Controller or @RequestMapping
     |   |           |
     |   |           +-- For each handler bean:
     |   |               +-- detectHandlerMethods(beanName):
     |   |                   +-- Introspect class methods
     |   |                   +-- For each method:
     |   |                   |   +-- getMappingForMethod(method, beanType):
     |   |                   |       +-- Find @RequestMapping (or meta: @GetMapping etc.)
     |   |                   |       +-- Create RequestMappingInfo
     |   |                   +-- registerHandlerMethod(beanName, method, mapping):
     |   |                       +-- MappingRegistry.register(mapping, handler, method):
     |   |                           +-- registry.put(mapping, new Registration(...))
     |   |                           +-- pathLookup.add("/orders/{id}", mapping)
     |   |                           +-- nameLookup (only for exact paths, no variables)
     |   |           +-- handlerMethodsInitialized(getHandlerMethods())
     |   |
     |   +-- BeanPostProcessor.postProcessAfterInitialization()
     |       +-- (no AOP proxy needed for HandlerMapping)
     |
     +-- addSingleton("requestMappingHandlerMapping", bean)
         +-- Bean ready, MappingRegistry fully populated

  3. SERVLET INIT PHASE
     +-- DispatcherServlet.initStrategies()
         +-- initHandlerMappings(context)
             +-- getBeansOfType(HandlerMapping.class)
                 +-- Finds "requestMappingHandlerMapping" from ApplicationContext
                 +-- Reuses the fully-initialized bean (NOT a new instance)
```

### Request Lifecycle From Accept to Response Flush

```
  +--------------------------------------------------------------------------+
  |                    REQUEST LIFECYCLE STATE MACHINE                        |
  +--------------------------------------------------------------------------+

  STATE: NEW
  +-- TCP connection accepted by Acceptor
  +-- SocketChannel registered with Poller
  +-- Socket state: NIO Channel, non-blocking

  v Poller detects OP_READ

  STATE: PROCESSING
  +-- Worker thread assigned from pool
  +-- Thread name: http-nio-8080-exec-N
  +-- SocketProcessorBase.doRun():
  |   +-- Http11Processor reads HTTP request line + headers
  |   +-- Request + Response objects created
  |   +-- Catalina pipeline invoked
  |   +-- FilterChain -> DispatcherServlet
  +-- ThreadLocal state:
      +-- RequestContextHolder request attributes
      +-- TransactionSynchronizationManager (if @Transactional)
      +-- SecurityContextHolder (if Spring Security)

  v After controller returns

  STATE: WRITING RESPONSE
  +-- Response committed (headers written to SocketChannel)
  +-- Response body written (JSON bytes flushed)
  +-- Content-Length or Transfer-Encoding: chunked set

  v Response fully written

  STATE: AFTER_COMPLETION (keep-alive)
  +-- if (Connection: keep-alive):
  |   +-- SocketChannel returned to Poller for monitoring
  |   +-- Worker thread returned to pool
  |   +-- NioChannel recycled for next request
  |   +-- ThreadLocal state cleaned up
  +-- if (Connection: close): SocketChannel.close()

  STATE: KEEP-ALIVE WAIT
  +-- Poller monitors socket for next OP_READ event
  +-- Data within keepAliveTimeout -> STATE: PROCESSING
  +-- keepAliveTimeout expires -> SocketChannel.close()

  STATE: CLOSED
  +-- SocketChannel closed
  +-- NioChannel returned to pool
  +-- Connection removed from Poller selector
```

## 7. Source Code Reading Guide

### Critical Files to Read (In Order)

```
1. org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
   spring-boot/.../web/embedded/tomcat/TomcatServletWebServerFactory.java (~600 lines)
   -> getWebServer() -- how Spring Boot creates and configures embedded Tomcat
   -> Look for: customizeConnector(), prepareContext(), configureSsl()
   -> Key insight: Tomcat is created programmatically -- no server.xml

2. org.springframework.boot.web.embedded.tomcat.TomcatWebServer
   spring-boot/.../web/embedded/tomcat/TomcatWebServer.java (~300 lines)
   -> initialize() -- calls tomcat.start(), starts daemon await thread
   -> Key insight: In embedded mode, the JVM process IS the server; no shutdown port

3. org.apache.catalina.startup.Tomcat
   tomcat-embed-core/.../startup/Tomcat.java (~800 lines)
   -> start() -- the entry point for embedded Tomcat startup
   -> How Service, Engine, Host, Context are created programmatically

4. org.apache.coyote.http11.Http11NioProtocol
   tomcat-embed-core/.../coyote/http11/Http11NioProtocol.java (~200 lines)
   -> Constructor creates NioEndpoint
   -> Endpoint configuration: maxConnections, maxThreads, connectionTimeout, etc.

5. org.apache.tomcat.util.net.NioEndpoint
   tomcat-embed-core/.../util/net/NioEndpoint.java (~1300 lines)
   -> bind() -- creates ServerSocketChannel, binds to port
   -> startInternal() -- starts Acceptor and Poller threads
   -> Acceptor inner class -- accept loop
   -> Poller inner class -- selector loop, event processing
   -> Key insight: This is the heart of Tomcat NIO. Every connection flows through here.

6. org.apache.catalina.connector.CoyoteAdapter
   tomcat-embed-core/.../catalina/connector/CoyoteAdapter.java (~800 lines)
   -> service(Request, Response) -- converts Coyote objects to Servlet API objects
   -> Calls connector.getService().getMapper().map() to route request
   -> Invokes Catalina valve pipeline: Engine -> Host -> Context -> Wrapper

7. org.apache.catalina.core.StandardWrapperValve
   tomcat-embed-core/.../catalina/core/StandardWrapperValve.java (~300 lines)
   -> invoke() -- allocates servlet instance, creates filter chain
   -> Calls ApplicationFilterChain.doFilter()

8. org.apache.catalina.core.ApplicationFilterChain
   tomcat-embed-core/.../catalina/core/ApplicationFilterChain.java (~200 lines)
   -> doFilter() -- iterates through filters, then calls servlet.service()
   -> Key insight: The filter chain uses an array + index, NOT a linked list

9. org.springframework.web.servlet.DispatcherServlet
   spring-webmvc/.../web/servlet/DispatcherServlet.java (~1200 lines)
   -> doDispatch() -- THE method. Every request touches this.
   -> getHandler(), getHandlerAdapter(), processDispatchResult()
   -> initStrategies() -- where all strategy beans are loaded

10. org.springframework.web.servlet.handler.AbstractHandlerMethodMapping
    spring-webmvc/.../servlet/handler/AbstractHandlerMethodMapping.java (~600 lines)
    -> initHandlerMethods() -- startup scan of all @Controller beans
    -> lookupHandlerMethod(lookupPath, request) -- runtime lookup
    -> MappingRegistry inner class -- the data structure

11. org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
    spring-webmvc/.../servlet/mvc/method/annotation/RequestMappingHandlerMapping.java (~400 lines)
    -> getMappingForMethod() -- creates RequestMappingInfo from annotations
    -> isHandler() -- checks for @Controller/@RequestMapping
    -> handleMatch() -- extracts URI template variables

12. org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
    spring-webmvc/.../servlet/mvc/method/annotation/RequestMappingHandlerAdapter.java (~1200 lines)
    -> handleInternal() -> invokeHandlerMethod()
    -> getDefaultArgumentResolvers() -- the argument resolver chain
    -> getDefaultReturnValueHandlers() -- the return value handler chain

13. org.springframework.web.method.support.InvocableHandlerMethod
    spring-web/.../method/support/InvocableHandlerMethod.java (~400 lines)
    -> invokeForRequest() -- resolves args, invokes method, handles return
    -> getMethodArgumentValues() -- iterates argument resolver chain
    -> doInvoke() -- reflection invocation

14. org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver
    spring-webmvc/.../servlet/mvc/method/annotation/ExceptionHandlerExceptionResolver.java (~500 lines)
    -> doResolveHandlerMethodException() -- the core exception resolution logic
    -> getExceptionHandlerMethod() -- finds @ExceptionHandler in controller and @ControllerAdvice
    -> afterPropertiesSet() -- caches @ControllerAdvice exception handlers at startup

15. org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor
    spring-webmvc/.../servlet/mvc/method/annotation/RequestResponseBodyMethodProcessor.java (~200 lines)
    -> supportsParameter() -- checks for @RequestBody
    -> resolveArgument() -- reads body via HttpMessageConverter
    -> supportsReturnType() -- checks for @ResponseBody
    -> handleReturnValue() -- writes body via HttpMessageConverter

16. org.springframework.web.servlet.mvc.method.annotation.PathVariableMethodArgumentResolver
    spring-webmvc/.../servlet/mvc/method/annotation/PathVariableMethodArgumentResolver.java (~150 lines)
    -> supportsParameter() -- checks for @PathVariable
    -> resolveArgument() -- extracts from URI template variables

17. org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor
    spring-webmvc/.../servlet/mvc/method/annotation/HttpEntityMethodProcessor.java (~300 lines)
    -> supportsParameter() -- HttpEntity parameter
    -> supportsReturnType() -- HttpEntity/ResponseEntity return type
    -> handleReturnValue() -- sets status, headers, serializes body

18. org.springframework.web.accept.ContentNegotiationManager
    spring-web/.../web/accept/ContentNegotiationManager.java (~200 lines)
    -> resolveMediaTypes() -- determines the response Content-Type
    -> ContentNegotiationStrategy chain: parameter -> Accept header -> fixed default

19. org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport
    spring-webmvc/.../servlet/config/annotation/WebMvcConfigurationSupport.java (~800 lines)
    -> requestMappingHandlerMapping() -- @Bean creating the HandlerMapping
    -> requestMappingHandlerAdapter() -- @Bean creating the HandlerAdapter
    -> Key insight: This is where Spring Boot picks up Web MVC configuration

20. org.apache.coyote.http11.Http11Processor
    tomcat-embed-core/.../coyote/http11/Http11Processor.java (~1200 lines)
    -> service() -- the HTTP/1.1 protocol handler
    -> prepareRequest() -- parses HTTP request into Coyote Request object
    -> Key insight: This is where raw bytes become an HTTP request
```

## 8. Production Failure Scenarios

### Scenario 1: "Stream closed" / "getInputStream() has already been called"

**Symptom**: `IllegalStateException: getInputStream() has already been called for this request` or `Stream closed` when reading the request body. Happens intermittently in production.

**Root cause**: The `HttpServletRequest.getInputStream()` can only be called ONCE. Multiple components try to read the body: a filter reads it for logging, then the `@RequestBody` argument resolver tries to read it again.

```java
// -- The Problem --
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, ...) {
        String body = new String(request.getInputStream().readAllBytes()); // <-- CONSUMED
        log.info("Request body: {}", body);
        filterChain.doFilter(request, response);
        // Controller @RequestBody now gets STREAM CLOSED error
    }
}

// -- Fix: ContentCachingRequestWrapper --
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BodyCachingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, ...) {
        ContentCachingRequestWrapper wrappedRequest =
                new ContentCachingRequestWrapper(request);
        filterChain.doFilter(wrappedRequest, response);
        byte[] body = wrappedRequest.getContentAsByteArray();
        log.info("Request body: {}", new String(body, StandardCharsets.UTF_8));
    }
}

// -- Fix for pre-read caching (read ONCE, cache forever) --
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = request.getInputStream().readAllBytes();
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(this.cachedBody);
    }
}
```

**Production note**: `ContentCachingRequestWrapper` has a size limit (default ~2KB). Large bodies are truncated. For request logging at scale, sample only a percentage and use async logging.

### Scenario 2: Content Negotiation Returning XML Instead of JSON

**Symptom**: API returns XML (`application/xml`) instead of expected JSON. Client breaks.

**Root cause**: If `jackson-dataformat-xml` is on the classpath, Spring Boot auto-configures `MappingJackson2XmlHttpMessageConverter`. When `Accept: */*` or no Accept header is sent, the first compatible converter might be XML.

```java
// Fix 1: Restrict produces on controller
@RestController
@RequestMapping(value = "/api/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController { ... }

// Fix 2: Configure default content type
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.defaultContentType(MediaType.APPLICATION_JSON);
    }
}

// Fix 3: Remove XML converter
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(c -> c instanceof MappingJackson2XmlHttpMessageConverter);
    }
}
```

### Scenario 3: Missing @RequestBody Causing Null Pointer

**Symptom**: Controller method parameter is always `null` despite client sending valid JSON. No error thrown.

**Root cause**: Missing `@RequestBody` annotation. A complex type parameter without `@RequestBody` is treated as `@ModelAttribute` -- Spring binds from query params/form data, not the body.

```java
// BROKEN -- body ignored, parameter is null
@PostMapping("/orders")
public ResponseEntity<Order> create(OrderRequest request) {  // No @RequestBody
    // request is always null or empty
}

// FIXED
@PostMapping("/orders")
public ResponseEntity<Order> create(@RequestBody OrderRequest request) { }
```

**Architectural prevention**: Add an ArchUnit rule requiring `@RequestBody` on POST/PUT/PATCH handler parameters that are complex types.

### Scenario 4: MaxUploadSizeExceededException Not Caught

**Symptom**: Large file uploads cause 500 Internal Server Error. `@ExceptionHandler(MaxUploadSizeExceededException.class)` does not catch it.

**Root cause**: `MaxUploadSizeExceededException` is thrown by `MultipartResolver` BEFORE `DispatcherServlet` calls `doDispatch()` -- during `checkMultipart()`. No `@ExceptionHandler` is active yet.

```java
// Fix: Handle in a Filter (BEFORE DispatcherServlet)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MultipartExceptionHandlerFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (MaxUploadSizeExceededException ex) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value()); // 413
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"FILE_TOO_LARGE\","
                    + "\"maxSize\":" + ex.getMaxUploadSize() + "}");
        }
    }
}
```

### Scenario 5: Missing Accept Header Causing 406 Not Acceptable

**Symptom**: Client receives `406 Not Acceptable`. Works in Postman but fails from a specific client.

**Root cause**: The client's `Accept` header does not include a type Spring can produce, OR content negotiation uses parameter strategy (`?format=json`) and ignores the Accept header entirely.

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer
            .favorParameter(false)           // Don't use ?format= parameter
            .ignoreAcceptHeader(false)       // DO respect Accept header
            .defaultContentType(MediaType.APPLICATION_JSON);
    }
}
```

### Scenario 6: Tomcat Thread Pool Exhaustion Under Load

**Symptom**: Application accepts new connections but requests pile up and time out. Thread dump shows 200 threads all in `socketRead()` or waiting on downstream services.

**Root cause**: All 200 worker threads occupied with slow requests. Acceptor still accepts connections, but they queue in Poller waiting for a worker. Eventually OS accept queue fills (`acceptCount`) and new TCP connections are refused.

```
+-----------------------------------------------------------------+
|                    THREAD POOL EXHAUSTION                        |
|                                                                 |
|  maxThreads = 200, occupied = 200, queue = 100+ pending         |
|                                                                 |
|  Workers: ALL 200 threads stuck in:                              |
|    +-- http-nio-8080-exec-1:  waiting for DB query (60s)        |
|    +-- http-nio-8080-exec-2:  waiting for downstream API (30s)  |
|    +-- ...                                                      |
|    +-- http-nio-8080-exec-200: waiting for file upload          |
|                                                                 |
|  New clients:                                                   |
|    +-- TCP handshake succeeds (Acceptor working)                |
|    +-- HTTP request sent                                        |
|    +-- No worker to process -> timeout                          |
|    +-- Eventually: OS accept queue full -> connection refused   |
+-----------------------------------------------------------------+
```

**Mitigation strategies**:
```java
// 1. Add timeouts to all downstream calls (circuit breaker pattern)
@Bean
public RestTemplate restTemplate() {
    return new RestTemplateBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(5))
        .build();
}

// 2. Move long-running operations to async processing
@GetMapping("/report")
public DeferredResult<Report> generateReport() {
    DeferredResult<Report> result = new DeferredResult<>(60000L);
    taskExecutor.submit(() -> {
        Report report = reportService.generate();  // Runs on separate pool
        result.setResult(report);
    });
    return result;  // Returns immediately, releases worker thread
}

// 3. Server-level tuning
// server.tomcat.threads.max=500
// server.tomcat.connection-timeout=30s
// server.tomcat.keep-alive-timeout=5s
// server.tomcat.max-connections=1000
```

## 9. Debugging Techniques

### Tracing a Single Request Through DispatcherServlet

```java
// Breakpoint locations (in execution order):

// 1. DispatcherServlet.doDispatch() -- line ~1040
//    Condition: request.getRequestURI().contains("/orders")
//
// 2. AbstractHandlerMethodMapping.lookupHandlerMethod() -- line ~300
//    Condition: lookupPath.equals("/orders/42")
//
// 3. RequestMappingHandlerAdapter.invokeHandlerMethod() -- line ~870
//
// 4. InvocableHandlerMethod.getMethodArgumentValues() -- line ~200
//    Watch: which resolver handles each arg
//
// 5. RequestResponseBodyMethodProcessor.readWithMessageConverters() -- line ~120
//
// 6. Your controller method -- any breakpoint
//
// 7. HttpEntityMethodProcessor.handleReturnValue() -- line ~150
//
// 8. ExceptionHandlerExceptionResolver.doResolveHandlerMethodException()
//    Watch: exception, matched handler method

// -- Enable Trace Logging --
// application.properties:
// logging.level.org.springframework.web.servlet.DispatcherServlet=TRACE
// logging.level.org.springframework.web.servlet.handler.AbstractHandlerMethodMapping=TRACE
// logging.level.org.springframework.web.servlet.mvc.method.annotation=TRACE
```

### Inspecting HandlerMapping Registrations at Runtime

```java
@Component
public class MappingInspector {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @EventListener(ContextRefreshedEvent.class)
    public void inspectMappings() {
        // Method 1: Use the public API
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            System.out.printf("  %s %s -> %s.%s(%d args)%n",
                    mapping.getMethodsCondition(),
                    mapping.getDirectPaths(),
                    method.getBeanType().getSimpleName(),
                    method.getMethod().getName(),
                    method.getMethodParameters().length);
        });

        // Method 2: Access MappingRegistry via reflection (for pathLookup)
        try {
            Field registryField = AbstractHandlerMethodMapping.class
                    .getDeclaredField("mappingRegistry");
            registryField.setAccessible(true);
            Object registry = registryField.get(handlerMapping);

            Field pathLookupField = registry.getClass()
                    .getDeclaredField("pathLookup");
            pathLookupField.setAccessible(true);
            MultiValueMap<String, ?> pathLookup =
                    (MultiValueMap<String, ?>) pathLookupField.get(registry);

            System.out.println("Total path patterns: " + pathLookup.size());
            pathLookup.forEach((path, mappings) -> {
                System.out.printf("  %s -> %d mappings%n", path, mappings.size());
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### Debugging Handler Method Argument Resolution

```java
// Custom argument resolver with debugging:
public class DebuggingArgumentResolver implements HandlerMethodArgumentResolver {

    private final PathVariableMethodArgumentResolver delegate =
            new PathVariableMethodArgumentResolver();

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        boolean supports = delegate.supportsParameter(parameter);
        if (supports) {
            System.out.printf("[ARG-RESOLVER] @PathVariable %s %s -> supported%n",
                    parameter.getParameterType().getSimpleName(),
                    parameter.getParameterName());
        }
        return supports;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) throws Exception {

        long start = System.nanoTime();
        Object value = delegate.resolveArgument(parameter, mavContainer,
                webRequest, binderFactory);
        long duration = System.nanoTime() - start;

        System.out.printf("[ARG-RESOLVER] Resolved %s = %s (%d us)%n",
                parameter.getParameterName(), value, duration / 1000);
        return value;
    }
}

// Register in WebMvcConfigurer:
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(0, new DebuggingArgumentResolver());
    }
}
```

### Enabling Tomcat Access Logging

```java
// Method 1: Application Properties
// server.tomcat.accesslog.enabled=true
// server.tomcat.accesslog.directory=/var/log/myapp
// server.tomcat.accesslog.pattern=%h %l %u %t "%r" %s %b %D "%{X-Trace-Id}i" %I
//   %D = processing time in ms, %I = current thread name

// Method 2: Programmatic Configuration
@Bean
public TomcatServletWebServerFactory tomcatFactory() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    factory.addContextCustomizers(context -> {
        AccessLogValve valve = new AccessLogValve();
        valve.setDirectory("/var/log/myapp");
        valve.setPattern("%h %l %u %t \"%r\" %s %b %D \"%{X-Trace-Id}i\"");
        valve.setSuffix(".log");
        valve.setRotatable(true);
        context.getPipeline().addValve(valve);
    });
    return factory;
}
```

### Using B3 Trace Propagation Through the HTTP Layer

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Extract or generate trace ID
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        String spanId = UUID.randomUUID().toString();

        // Set in MDC for logging
        MDC.put("traceId", traceId);
        MDC.put("spanId", spanId);

        // Echo back for downstream propagation
        response.setHeader("X-Trace-Id", traceId);
        response.setHeader("X-Span-Id", spanId);

        request.setAttribute("traceId", traceId);
        request.setAttribute("spanId", spanId);

        // Access log pattern: %h %l %u %t "%r" %s %b %D "%{X-Trace-Id}i"
        // This correlates every access log entry with your trace

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }
}
```

## 10. Observability Considerations

### Key Metrics

```java
// Tomcat Metrics (auto-configured by spring-boot-actuator):
//   tomcat.threads.config.max       (gauge -- configured max)
//   tomcat.threads.current          (gauge -- total threads in pool)
//   tomcat.threads.busy             (gauge -- threads actively processing)
//   tomcat.connections.current      (gauge -- open connections)
//   tomcat.connections.keepalive.current (gauge)
//   tomcat.global.received          (counter -- bytes received)
//   tomcat.global.sent              (counter -- bytes sent)
//   tomcat.global.error             (counter -- error count)

// Custom Request Metrics:
@Component
public class HttpRequestMetrics implements HandlerInterceptor {

    private final MeterRegistry registry;
    private final Timer requestTimer;

    public HttpRequestMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requestTimer = Timer.builder("http.request.duration")
                .description("HTTP request duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .sla(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofMillis(1000))
                .register(registry);
    }

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
        if (startTime == null) return;

        long duration = System.nanoTime() - startTime;
        requestTimer.record(duration, TimeUnit.NANOSECONDS);

        // Tagged metrics for drill-down:
        Timer.builder("http.request.duration")
                .tag("method", request.getMethod())
                .tag("uri", getUriTemplate(request))
                .tag("status", String.valueOf(response.getStatus()))
                .tag("outcome", ex == null ? "SUCCESS" : "ERROR")
                .register(registry)
                .record(duration, TimeUnit.NANOSECONDS);
    }

    private String getUriTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}
```

### Tomcat Thread Pool Health Alerts

```
+----------------------------------------------------------------------+
|                     TOMCAT THREAD POOL HEALTH                         |
|                                                                      |
|  ALERTING RULES:                                                     |
|                                                                      |
|  1. tomcat.threads.busy / tomcat.threads.config.max > 0.8           |
|     -> Warning: Thread pool is 80% saturated                          |
|     -> Action: Investigate slow downstream services, add timeouts    |
|                                                                      |
|  2. tomcat.threads.busy == tomcat.threads.config.max                |
|     -> Critical: Thread pool fully exhausted                          |
|     -> Action: Circuit breaker should open, consider scaling up      |
|                                                                      |
|  3. tomcat.connections.current > 5000                                |
|     -> Warning: Large number of connections (check keep-alive)       |
|     -> Action: Reduce keep-alive-timeout, check for connection leaks |
|                                                                      |
|  DASHBOARD GAUGES:                                                   |
|  +--------------------------------------------------------------+   |
|  | [################________________] Busy: 160/200 (80%)       |   |
|  | [##################______________] Total: 180/200 (90%)     |   |
|  | [######___________________________] Connections: 320/8192   |   |
|  +--------------------------------------------------------------+   |
+----------------------------------------------------------------------+
```

### Logging: What To Log in a Filter vs Interceptor vs Controller

```
  +-----------------------------------------------------------------+
  |                      LOGGING STRATEGY                            |
  |                                                                 |
  |  FILTER (earliest, pre-DispatcherServlet):                       |
  |    +-- Request arrival with trace ID, method, URI, client IP    |
  |    +-- Request body (sampled, truncated) if needed for audit    |
  |    +-- Security authentication events                           |
  |    +-- NEVER: business logic, processing outcomes               |
  |                                                                 |
  |  HANDLER INTERCEPTOR (handler identified):                       |
  |    +-- Handler: controller + method name                        |
  |    +-- Authorization decision                                   |
  |    +-- Timing: preHandle -> postHandle duration                 |
  |    +-- NEVER: exception details (use afterCompletion)           |
  |                                                                 |
  |  CONTROLLER / SERVICE:                                           |
  |    +-- Business event: "order placed, id=42"                    |
  |    +-- Downstream call results: "inventory reserved"             |
  |    +-- NEVER: full request/response bodies (PII risk)            |
  |                                                                 |
  |  EXCEPTION HANDLER:                                              |
  |    +-- Full exception with stack trace                          |
  |    +-- Request context at time of error                         |
  |    +-- Correlation ID for support                               |
  |    +-- NEVER: expose stack traces to the client                 |
  +-----------------------------------------------------------------+
```

## 11. Performance Implications

### Tomcat Connector Tuning

```java
// server.tomcat.max-connections = 8192
// Purpose: Maximum connections Tomcat will accept and process
// Tuning: Too high -> OOM risk from socket buffers. Too low -> rejected connections.
// Rule of thumb: Set based on expected concurrent users + 20% headroom.

// server.tomcat.accept-count = 100
// Purpose: OS-level backlog for connections awaiting accept()
// Tuning: Higher values smooth traffic spikes but consume kernel memory.

// server.tomcat.threads.max = 200
// Purpose: Maximum worker threads
// Tuning: For I/O-bound workloads: related to (concurrent requests * avg latency / avg processing time)
//         With virtual threads (Java 21+), thread pool size is much less significant.

// server.tomcat.connection-timeout = 60s
// Purpose: Time to wait for the next HTTP request on an established connection
// Tuning: 5-10s for public APIs, 30-60s for internal service-to-service.

// Programmatic configuration:
@Bean
public TomcatServletWebServerFactory tomcatFactory() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    factory.addConnectorCustomizers(connector -> {
        connector.setProperty("maxConnections", "8192");
        connector.setProperty("acceptCount", "200");
        connector.setProperty("connectionTimeout", "10000");
        connector.setProperty("maxThreads", "200");
        connector.setProperty("minSpareThreads", "25");
        connector.setProperty("maxKeepAliveRequests", "100");
    });
    return factory;
}
```

### Impact of @ResponseBody Serialization on Response Time

```
  +--------------------------------------------------------------+
  |          RESPONSE SERIALIZATION OVERHEAD ANALYSIS             |
  |                                                              |
  |  Scenario: GET /orders/42 returns Order with 20 nested items |
  |                                                              |
  |  Controller method call:           ~0.5ms                    |
  |  HandlerMethodReturnValueHandler:  ~0.05ms (dispatch)        |
  |  HttpEntityMethodProcessor:                                   |
  |    +-- Content negotiation:        ~0.1ms                    |
  |    +-- Select converter:           ~0.05ms                   |
  |    +-- Jackson serialization:      ~1.5ms (for 5KB JSON)    |
  |  Response write to socket:         ~0.2ms                    |
  |  TOTAL overhead:                   ~1.9ms                    |
  |                                                              |
  |  Optimization strategies:                                    |
  |  * Use @JsonView to exclude unnecessary fields              |
  |  * Pre-serialize to byte[] for hot responses (caching)      |
  |  * Consider protocol buffers for high-throughput services   |
  +--------------------------------------------------------------+
```

### Handler Mapping Lookup Cost at Scale

```java
// OBSERVATION: Handler lookup is O(n) in registered patterns.
// With 500 methods and 50 unique URL patterns, worst case is 50 path comparisons.
// Fast enough for typical applications (<1ms for 500 patterns).
// Becomes a concern at 10,000+ patterns.

// MITIGATION: Custom HandlerMapping for hot paths (O(1))
@Component
public class FastPathHandlerMapping implements HandlerMapping {
    private final Map<String, Object> fastPaths = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        fastPaths.put("/api/health", (HttpRequestHandler) (req, res) -> {
            res.setStatus(200);
            res.getWriter().write("OK");
        });
    }

    @Override
    public HandlerExecutionChain getHandler(HttpServletRequest request) {
        Object handler = fastPaths.get(request.getRequestURI());
        return handler != null ? new HandlerExecutionChain(handler) : null;
    }
}
```

### Synchronous vs Async Request Processing Trade-offs

```
  +------------------------------------------------------------------+
  |                SYNC vs ASYNC REQUEST PROCESSING                   |
  |                                                                  |
  |  SYNCHRONOUS (default):                                          |
  |    * Worker thread occupied from start to finish                 |
  |    * Simple, predictable, debuggable                             |
  |    * Thread-per-request: maxThreads limits concurrency           |
  |    * If downstream takes 500ms, thread is BLOCKED 500ms          |
  |    * 200 threads x 500ms blocking = 400 req/s max                |
  |    * Best for: CPU-bound or fast I/O (<50ms) workloads           |
  |                                                                  |
  |  ASYNCHRONOUS (DeferredResult, Callable):                        |
  |    * Worker thread released while waiting                        |
  |    * 200 threads x unlimited pending = much higher throughput    |
  |    * BUT: complexity cost in error handling, timeouts, MDC       |
  |    * Best for: I/O-heavy workloads with slow downstream calls    |
  |                                                                  |
  |  THE REAL TRADE-OFF:                                             |
  |    Async does NOT make individual requests faster.                |
  |    Async allows more CONCURRENT requests with fewer threads.     |
  |    If downstream is the bottleneck, async won't help.            |
  |    If threads are the bottleneck, async can 10x throughput.     |
  +------------------------------------------------------------------+
```

## 12. Architecture Implications

### When to Use WebFlux Instead of Spring MVC

```
  +-----------------------------------------------------------------+
  |              Spring MVC (Servlet) vs WebFlux (Reactive)          |
  |                                                                 |
  |  CHOOSE SPRING MVC WHEN:                                        |
  |    +-- Using blocking JDBC/JPA (Hibernate)                      |
  |    +-- Legacy libraries expect Servlet API                      |
  |    +-- Team is experienced with servlet model                   |
  |    +-- Workload is CPU-bound or fast I/O (<50ms)                |
  |    +-- Simple CRUD services, moderate throughput (<5K req/s)    |
  |                                                                 |
  |  CHOOSE WEBFLUX WHEN:                                           |
  |    +-- Using R2DBC, Reactive MongoDB, Reactive Redis            |
  |    +-- High-throughput, low-latency I/O (>5K req/s)             |
  |    +-- Need true non-blocking end-to-end                        |
  |    +-- Gateway/API proxy with many concurrent connections       |
  |    +-- Streaming data (SSE, WebSocket at scale)                 |
  |                                                                 |
  |  THE PITFALL: MIXING MODELS                                     |
  |    +-- Blocking JDBC in a WebFlux app = WORSE than MVC          |
  |    |   (blocks event loop thread, degrading ALL requests)       |
  |    +-- WebClient in MVC = OK (uses its own thread pool)         |
  |    +-- Rule: Never block the event loop                         |
  +-----------------------------------------------------------------+
```

### How the HTTP Layer Constrains Your Threading Model

```
  +-----------------------------------------------------------------+
  |             THREADING CONSTRAINTS IN THE HTTP LAYER              |
  |                                                                 |
  |  1. ThreadLocal must be cleaned up                              |
  |     +-- RequestContextHolder reset on every request completion  |
  |     +-- TransactionSynchronizationManager clear                 |
  |     +-- SecurityContextHolder clear                             |
  |     +-- Failure to clean = memory leak + cross-request pollution|
  |                                                                 |
  |  2. Blocking the worker thread affects ALL requests             |
  |     +-- Thread blocked on slow DB query can't serve others      |
  |     +-- Solution: async processing or separate thread pools     |
  |     +-- With Virtual Threads (Java 21+): blocking is cheap      |
  |                                                                 |
  |  3. Context propagation across async boundaries                 |
  |     +-- @Async methods lose ThreadLocal context                 |
  |     +-- CompletableFuture does NOT auto-propagate MDC            |
  |     +-- Solution: ContextSnapshotFactory (Micrometer)           |
  |                                                                 |
  |  4. Response must be committed before thread returns            |
  |     +-- Holding response objects across requests = data leak    |
  +-----------------------------------------------------------------+
```

### Servlet Container Choice (Tomcat vs Jetty vs Undertow)

```
  +----------+--------------+--------------+----------------------+
  | Feature  | TOMCAT       | JETTY        | UNDERTOW              |
  +----------+--------------+--------------+----------------------+
  | NIO      | NIO (pooled) | NIO (pooled) | XNIO Worker            |
  | Model    | Acceptor +   | Selector +   | I/O + Worker threads  |
  |          | Poller +     | QueuedThread | (persistent per core) |
  |          | Worker pool  | Pool         |                       |
  +----------+--------------+--------------+----------------------+
  | Default  | 200          | 200          | I/O = cores           |
  | Threads  |              |              | Worker = cores x 8    |
  +----------+--------------+--------------+----------------------+
  | HTTP/2   | Native       | Native       | Native                |
  +----------+--------------+--------------+----------------------+
  | Memory   | Medium       | Medium       | Lower (fewer buffers) |
  +----------+--------------+--------------+----------------------+
  | Best For | General use  | Embedded     | High-throughput       |
  |          | (Spring def) | w/WebSocket  | low-latency          |
  +----------+--------------+--------------+----------------------+

  Spring Boot defaults to Tomcat: most tested, best integration, broadest support.
  For most teams, the container is NOT the bottleneck.
```

### API Versioning Strategies and Handler Mapping

```java
// Strategy 1: URI Path Versioning (/v1/orders, /v2/orders)
// HandlerMapping sees COMPLETELY SEPARATE paths. Simple and explicit.
@RestController
@RequestMapping("/api/v1/orders")
public class OrderControllerV1 { }

@RestController
@RequestMapping("/api/v2/orders")
public class OrderControllerV2 { }

// Strategy 2: Custom Header Versioning (X-API-Version: 2)
// Uses @RequestMapping headers condition:
@GetMapping(value = "/orders", headers = "X-API-Version=2")
public List<OrderV2> getOrdersV2() { }

@GetMapping(value = "/orders", headers = "X-API-Version=1")
public List<OrderV1> getOrdersV1() { }

// Strategy 3: Custom RequestCondition for Accept header versioning
public class ApiVersionCondition implements RequestCondition<ApiVersionCondition> {
    private final int version;

    @Override
    public ApiVersionCondition getMatchingCondition(HttpServletRequest request) {
        String acceptHeader = request.getHeader("Accept");
        int requestVersion = extractVersion(acceptHeader);
        if (requestVersion >= this.version) return this;
        return null;
    }
}

// Multiple @RequestMapping methods with SAME path but DIFFERENT
// headers/params/produces conditions use RequestMappingInfo.compareTo().
// The most specific match wins. Ambiguous matches cause IllegalStateException at startup.
```

## 13. Team Ownership Implications

### Who Owns Tomcat Configuration

```
  +-----------------------------------------------------------------+
  |                    OWNERSHIP MATRIX                              |
  |                                                                 |
  |  Platform Team Owns:                                            |
  |  +-- Base server.tomcat.* defaults (threads, connections)       |
  |  +-- Common access log format with trace ID                     |
  |  +-- Standard filter chain ordering (security, tracing, logging) |
  |  +-- Error handling pattern (ErrorController, error attributes)  |
  |  +-- Content negotiation defaults (JSON by default)             |
  |  +-- CORS defaults                                              |
  |  +-- Graceful shutdown configuration                            |
  |  +-- Health check endpoints and probe configuration             |
  |                                                                 |
  |  Service Team Owns:                                             |
  |  +-- Service-specific Tomcat tuning (if different from defaults) |
  |  +-- Service-specific interceptors and filters                  |
  |  +-- Custom argument resolvers and return value handlers        |
  |  +-- Exception handler methods for business exceptions          |
  |  +-- API versioning strategy                                    |
  |                                                                 |
  |  DevOps/SRE Owns:                                               |
  |  +-- Kubernetes probe endpoints and timing                      |
  |  +-- Ingress/load balancer configuration                        |
  |  +-- Connection draining during deployments                     |
  |  +-- Resource limits (memory, CPU) that constrain thread pools   |
  +-----------------------------------------------------------------+
```

### How to Document HTTP Layer Conventions

```
  1. FILTER ORDERING: Document every filter in the chain, its purpose,
     and its expected behavior using @Order or Ordered interface.

  2. EXCEPTION HANDLING CATALOG: Document every @ExceptionHandler:
     which exceptions it catches, HTTP status, and error body format.

  3. CONTENT NEGOTIATION: Document supported media types, how the
     default is determined, and how clients should request alternatives.

  4. CUSTOM RESOLVERS: Document any custom HandlerMethodArgumentResolver
     or HandlerMethodReturnValueHandler, their position in the chain,
     and which annotations trigger them.

  5. TOMCAT TUNING: Document any non-default server.tomcat.* properties,
     the rationale for the change, and the expected impact.
```

### What Every Developer Must Know About Request/Response Lifecycle

```
  1. HttpServletRequest.getInputStream() can be called ONCE.
     Reading the body in a filter makes it unavailable in the controller.

  2. HttpServletResponse.getOutputStream() can be obtained ONCE.
     Once committed, headers cannot be changed.

  3. @RequestBody and @ResponseBody are NOT magic.
     They're processed by RequestResponseBodyMethodProcessor, which
     uses Jackson (or another HttpMessageConverter) to map bytes to objects.

  4. Filters run BEFORE HandlerInterceptor. Filters operate at the
     Servlet API level. Interceptors operate at the Spring MVC level.

  5. @ExceptionHandler only catches exceptions thrown during
     HandlerAdapter.handle(). Exceptions thrown by Filters are
     NOT caught by @ExceptionHandler.

  6. The Tomcat worker thread is shared. Blocking it blocks all
     requests waiting for a worker. Use async processing or
     separate thread pools for long-running operations.

  7. ThreadLocal state (RequestContextHolder, MDC, SecurityContext)
     must be cleaned up after each request to prevent cross-request
     data leakage.
```

## 14. Interview Questions

### Question 1: "Walk me through what happens from the moment a TCP packet arrives at port 8080 to the moment a @RestController method returns a ResponseEntity."

**Staff-level answer**: The journey begins at the OS kernel level. When a TCP SYN packet arrives at port 8080, the kernel completes the 3-way handshake and places the established connection in the accept queue, which has a backlog of `acceptCount` (default 100). The Tomcat `Acceptor` thread -- a single daemon thread named `http-nio-8080-Acceptor` -- calls `ServerSocketChannel.accept()`, which is a blocking call that returns a `SocketChannel` for each new connection. The Acceptor immediately configures the channel for non-blocking I/O, wraps it in a `NioChannel`, and registers it with one of the `Poller` threads (typically 2, named `http-nio-8080-ClientPoller-0` and `-1`).

The Poller thread uses a `Selector` to monitor hundreds of channels simultaneously. When the client sends HTTP bytes, the Poller detects the `OP_READ` event and hands the `NioSocketWrapper` off to the worker thread pool (`http-nio-8080-exec-N`, default 200 threads). The worker's `SocketProcessor` delegates to `Http11Processor.service()`, which reads raw bytes into an `Http11InputBuffer` and parses the HTTP request line (`GET /orders/42?status=active HTTP/1.1`), headers, and body (lazily, on demand). The parsed data populates Coyote-level `Request` and `Response` objects.

At this point, `CoyoteAdapter.service()` converts these Coyote objects into Servlet API wrappers (`RequestFacade` and `ResponseFacade`) and invokes the Catalina valve pipeline: `StandardEngineValve` -> `StandardHostValve` -> `StandardContextValve` -> `StandardWrapperValve`. The Wrapper valve is the critical handoff: it allocates (or reuses) the `DispatcherServlet` instance, creates an `ApplicationFilterChain` from the ordered filter registrations, and calls `filterChain.doFilter()`. Each filter executes in order -- `CharacterEncodingFilter`, `CorsFilter`, `SecurityFilterChain`, `RequestContextFilter` (which sets up ThreadLocal request attributes) -- until the chain reaches the final element: `DispatcherServlet.service()`.

Inside `DispatcherServlet.doDispatch()`, the framework iterates all registered `HandlerMapping` beans. The `RequestMappingHandlerMapping` extracts the lookup path `/orders/42`, consults its `MappingRegistry` (built at startup by scanning `@Controller` beans), finds that the pattern `/orders/{id}` matches, verifies the HTTP method matches GET, and returns a `HandlerExecutionChain` containing an `OrderController.getOrder()` `HandlerMethod` and any registered `HandlerInterceptor` beans. The `RequestMappingHandlerAdapter` is selected because it `supports(HandlerMethod)`. Pre-handle interceptors fire, then the adapter invokes the handler method: it iterates the `HandlerMethodArgumentResolver` chain to resolve each parameter (e.g., `PathVariableMethodArgumentResolver` extracts `id=42` from URI template variables, `RequestParamMethodArgumentResolver` extracts `status=active` from query parameters), calls `java.lang.reflect.Method.invoke()` on the controller method, and then processes the return value through the `HandlerMethodReturnValueHandler` chain (e.g., `HttpEntityMethodProcessor` handles `ResponseEntity`, setting the HTTP status, headers, and serializing the body via Jackson's `MappingJackson2HttpMessageConverter`).

Post-handle interceptors run, the response bytes are flushed to the `SocketChannel`, and if the `Connection: keep-alive` header was sent, the channel is returned to the Poller for reuse. The worker thread is returned to the pool, and `RequestContextHolder.resetRequestAttributes()` cleans up ThreadLocal state.

### Question 2: "How does Spring determine which controller method to invoke for a request to /api/orders/42? Explain HandlerMapping, how mappings are registered, and what happens with ambiguous mappings."

**Staff-level answer**: Spring determines the controller method through a two-phase process: build-time registration and runtime lookup. At build time, during ApplicationContext refresh, the `RequestMappingHandlerMapping` bean's `afterPropertiesSet()` method triggers `initHandlerMethods()`. This iterates every bean in the context, checks `isHandler(beanType)` for `@Controller` or `@RequestMapping` annotations, and for each matching bean, calls `detectHandlerMethods()`. This method introspects every method in the class, extracts annotation metadata (creating a `RequestMappingInfo` containing the URL pattern, HTTP methods, params, headers, consumes, and produces conditions), and registers each mapping in the internal `MappingRegistry`.

The `MappingRegistry` uses three data structures: `registry` (a `Map<RequestMappingInfo, HandlerMethod>` for the complete mapping), `pathLookup` (a `MultiValueMap<String, RequestMappingInfo>` for O(1) lookup by literal path), and `nameLookup` (a `Map<String, RequestMappingInfo>` for exact paths without variables). For `/orders/{id}`, it goes into `pathLookup` under the key `/orders/{id}` (the literal pattern, not a resolved path). For `/api/health` (an exact path with no variables), it goes into both `nameLookup` and `pathLookup`.

At runtime, in `doDispatch()`, `getHandler()` calls `AbstractHandlerMethodMapping.lookupHandlerMethod()`. This first checks `nameLookup` for an exact match on the lookup path -- O(1). If not found, it iterates all registered `RequestMappingInfo` objects, using `AntPathMatcher` to test whether the pattern matches the request path. For each matching pattern, it further checks method, params, headers, consumes, and produces conditions. All matches are collected and sorted by specificity using `RequestMappingInfo.compareTo()` -- the "best match" is the one with the fewest wildcards, most specific URL pattern, and most restrictive conditions.

Ambiguous mappings occur when two `RequestMappingInfo` objects have identical specificity for the same request. For example, `@GetMapping("/orders/{id}")` and `@GetMapping("/orders/{orderId}")` are ambiguous because both match the same path with the same specificity. Spring detects this during the sort: if the top two matches have the same comparator value, it throws `IllegalStateException("Ambiguous handler methods mapped for HTTP path '/orders/42'")`. This is a startup-time error, not a runtime surprise. Similarly, identical paths with different HTTP methods (e.g., `@GetMapping` and `@PostMapping` on `/orders`) are NOT ambiguous because the method condition disambiguates them.

The key data flow: after finding the best match, `handleMatch()` extracts URI template variables (e.g., `{id: "42"}`) and stores them as request attributes under `HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE`, where `PathVariableMethodArgumentResolver` later retrieves them. The returned `HandlerMethod` wraps both the controller bean reference AND the `java.lang.reflect.Method` -- the framework does not need to re-lookup the bean on each request; it holds the instance reference from when the bean was created.

### Question 3: "Explain the HandlerMethodArgumentResolver and HandlerMethodReturnValueHandler chains. How would you add custom argument resolution for a @CurrentUser annotation?"

**Staff-level answer**: These two chains are the core extensibility mechanism of Spring MVC's handler method processing. They follow the Chain of Responsibility pattern: a list of resolvers/handlers is iterated, and the first one that `supports()` a given parameter or return type handles it. The chains are ordered -- the default resolvers registered by `RequestMappingHandlerAdapter.getDefaultArgumentResolvers()` are added first, then any custom resolvers from the ApplicationContext are appended. Since the `supports()` check iterates from index 0, custom resolvers added later have lower priority unless explicitly placed earlier via `WebMvcConfigurer.addArgumentResolvers()`.

The `HandlerMethodArgumentResolver` chain resolves each parameter of a `@RequestMapping` method. For `handle(@RequestBody OrderRequest body, @PathVariable Long id, HttpServletRequest request)`:
- `RequestResponseBodyMethodProcessor.supportsParameter()` checks for `@RequestBody` -> true, handles body
- `PathVariableMethodArgumentResolver.supportsParameter()` checks for `@PathVariable` -> true, handles id
- `ServletRequestMethodArgumentResolver.supportsParameter()` checks for `ServletRequest` type -> true, injects request

The `HandlerMethodReturnValueHandler` chain processes the method's return value. For a `@RestController` returning `ResponseEntity<Order>`:
- `HttpEntityMethodProcessor.supportsReturnType()` checks for `HttpEntity` subtype -> true, sets status/headers and serializes body via `HttpMessageConverter`

For a custom `@CurrentUser` annotation, I would create a resolver that extracts user information from the security context and injects it into controller method parameters:

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser { }

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        // Extract from SecurityContext (or JWT, session, etc.)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Not authenticated");
        }
        // If the method parameter type is UserDetails, cast and return
        if (parameter.getParameterType().isAssignableFrom(auth.getPrincipal().getClass())) {
            return auth.getPrincipal();
        }
        // Or resolve from a UserService by the principal name
        return userService.findByUsername(auth.getName());
    }
}

// Register:
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(0, new CurrentUserArgumentResolver());  // High priority
    }
}
```

**Staff-level nuance**: The critical design decision is where the resolver gets its data from. If it reads from `SecurityContextHolder`, it works synchronously but breaks with `@Async` methods (the context is not propagated). If it reads from `HttpServletRequest.getUserPrincipal()`, it works with the Servlet container's security integration. If it reads from a custom header or JWT token, the resolver must also handle the error case (no token, expired token, invalid token) and throw appropriate exceptions that map to HTTP status codes -- or return `null` and let the controller handle the optional user case. The resolver should NEVER make blocking I/O calls (like a database lookup) without explicit awareness that it's running on the Tomcat worker thread; if the user lookup requires a database call, consider caching or using a separate, non-blocking thread.

## 15. Hands-On Exercises

1. **Write a custom HandlerInterceptor that measures and logs request duration**:
   Create a `HandlerInterceptor` that records `System.nanoTime()` in `preHandle()`, computes the duration in `afterCompletion()`, and logs it with the handler method name, HTTP status, and outcome. Add it via `WebMvcConfigurer.addInterceptors()`. Verify it works by observing the log output for fast and slow requests. Add a counter for requests exceeding 1 second and expose it as a Micrometer metric.

2. **Write a custom HandlerMethodArgumentResolver for a @CurrentUser annotation**:
   Create a `@CurrentUser` annotation and a resolver that extracts the authenticated user from `SecurityContextHolder`. Register it via `WebMvcConfigurer.addArgumentResolvers()`. Write a test controller method that uses `@CurrentUser User user` as a parameter. Test with a mock `SecurityContext`. Verify the resolver is called before the default resolvers by placing it at index 0.

3. **Configure Tomcat with specific connector settings and verify via JMX**:
   Set `server.tomcat.threads.max=50`, `server.tomcat.accept-count=10`, and `server.tomcat.max-connections=100`. Use JConsole or `jcmd` to connect to the running JVM and inspect the Tomcat thread pool MBean (`Catalina:type=ThreadPool,name="http-nio-8080"`). Observe `currentThreadCount`, `currentThreadsBusy`, and `maxThreads`. Run a load test with 200 concurrent connections and observe how the thread pool behaves when saturated.

4. **Debug a request through the full DispatcherServlet stack**:
   Set breakpoints in `DispatcherServlet.doDispatch()`, `AbstractHandlerMethodMapping.lookupHandlerMethod()`, `RequestMappingHandlerAdapter.invokeHandlerMethod()`, `InvocableHandlerMethod.getMethodArgumentValues()`, and `HttpEntityMethodProcessor.handleReturnValue()`. Step through a POST request with a JSON body. At each breakpoint, inspect the thread name (`http-nio-8080-exec-N`), the request URI, the resolved handler, the resolved arguments, and the return value. Note how many layers the request passes through before reaching your code.

5. **Write a ContentNegotiationStrategy that serves different formats based on a custom header**:
   Implement `ContentNegotiationStrategy` that checks for a custom `X-Response-Format` header. Register it via `WebMvcConfigurer.configureContentNegotiation()`. Create a controller that returns an object (without specifying `produces`). Test with `X-Response-Format: xml` and `X-Response-Format: json`. Observe how the strategy overrides the Accept header when the custom header is present, and falls back to Accept header-based negotiation when it's absent.

## 16. Advanced Challenges

1. **Build a custom HandlerMapping that maps requests based on a database table**:
   Create a `HandlerMapping` implementation that, at startup, queries a `route_mappings` table (`path_pattern`, `controller_bean`, `method_name`, `http_method`). For each row, use reflection to instantiate a `HandlerMethod`. At runtime, match incoming requests against the pattern column. Handle dynamic reloading: listen for database changes (via a scheduled poll or CDC event) and rebuild the mapping registry without restarting the application. Write tests that verify: (a) a request is correctly routed, (b) adding a new route in the database makes it available within the polling interval, (c) a removed route returns 404.

2. **Implement a request replay/dev-shadowing system using Filter + AsyncContext**:
   Build a `Filter` that, for a configurable percentage of production traffic (e.g., 1%), forks the request to a "shadow" service. The filter must: (a) copy the request body into a `CachedBodyHttpServletRequest`, (b) start an async task using `AsyncContext` to replay the request against the shadow service, (c) continue the original request processing normally without waiting for the shadow response, (d) log any differences between production and shadow responses. Handle: large request bodies (streaming), timeouts, and the case where the shadow service is unavailable.

3. **Build a custom embedded container (replace Tomcat with a Netty-based one using Reactor Netty)**:
   Implement the `WebServer` and `ServletWebServerFactory` interfaces from Spring Boot. Use Reactor Netty's `HttpServer` to create a server that can host Servlet-based applications. Implement the bridge between Netty's reactive HTTP model and the blocking Servlet API (this is the hard part -- you need to adapt the reactive `HttpServerRequest/Response` to `HttpServletRequest/Response`, which means blocking the Netty event loop for each request on a separate thread pool). Compare startup time, memory usage, and throughput with embedded Tomcat in a benchmark.

4. **Create a "Controller Method Complexity Analyzer" that instruments HandlerMethod**:
   Using a `HandlerInterceptor` or a `BeanPostProcessor` that wraps `HandlerMethod` objects, build a tool that measures: (a) the number of parameters each controller method has, (b) the types of arguments (body, path var, query param, header), (c) the return type category (void, ResponseEntity, plain object), (d) the presence of validation annotations (`@Valid`, `@Validated`), (e) whether the method is annotated with `@Transactional` (which should be on the service layer, not the controller). Generate a report that flags design smells: controllers with >5 parameters, controllers calling `@Transactional` methods directly, methods with no validation on `@RequestBody` parameters, and methods returning `ResponseEntity` with manually-set status codes that could be replaced by exception handling.

5. **Implement per-tenant Tomcat connector isolation for multi-tenant SaaS**:
   For a multi-tenant SaaS application, create a system where each tenant gets its own Tomcat `Connector` on a different port. The platform team maps tenant subdomains to ports at the load balancer level. Each connector has its own thread pool with tenant-specific limits. Implement: (a) dynamic connector creation/removal when tenants are provisioned/deprovisioned (using `TomcatServletWebServerFactory.addAdditionalTomcatConnectors()`), (b) per-connector metrics (separate thread pool gauges), (c) graceful shutdown of a single tenant's connector without affecting others, (d) a `Filter` that validates the tenant from the request context and rejects requests to the wrong connector. Handle the complexity of Spring beans being shared across all connectors -- the controller and service layers are singletons, so isolation must happen at the connector and filter level.
