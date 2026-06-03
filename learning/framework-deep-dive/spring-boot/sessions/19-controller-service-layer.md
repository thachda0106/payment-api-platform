# Session 19: Controller & Service Layer Design

## 1. Why This Topic Exists

The controller and service layers are where most developers spend 80% of their coding time, yet they harbor the most architectural debt. A `@RestController` method with 12 parameters, direct `EntityManager` calls, and a `@Transactional` annotation that rolls back on `NullPointerException` is not a hypothetical — it is inventory of every production codebase that started with a prototype and never stopped.

The problem is that Spring makes it too easy. Add `@RestController`, inject a repository, and you have a working endpoint in 30 seconds. The framework's low ceremony is a trap: it rewards sloppy layering with working software, deferring the cost to the engineer debugging a 400-line controller method at 3 AM when the payment system is down.

**Staff engineer insight**: Controller and service layer design is fundamentally about *boundaries*. The controller knows HTTP. The service knows business rules. When these bleed into each other — when a service returns `ResponseEntity`, when a controller calls `entityManager.persist()`, when a `@Transactional` annotation sits on a method that also sends emails — the system becomes untestable, unmaintainable, and unsafe. The boundary is not a suggestion. It is an engineering invariant that protects correctness under concurrency, observability during incidents, and testability during refactoring.

## 2. Mental Model

```
The Controller-Service Boundary:

  HTTP Request
      |
      v
  +-------------------------------------------------------+
  |  CONTROLLER (HTTP Concern Layer)                       |
  |                                                       |
  |  Responsibilities:                                    |
  |    - Receive and validate HTTP requests               |
  |    - Convert HTTP primitives to Domain DTOs           |
  |    - Invoke the right Application Service             |
  |    - Convert Application Service responses to HTTP   |
  |    - Handle HTTP-level errors (400, 401, 403, 404)   |
  |    - Set HTTP status codes, headers, cookies          |
  |                                                       |
  |  NEVER:                                               |
  |    - Call repositories directly                       |
  |    - Open transactions                                |
  |    - Contain business logic                           |
  |    - Throw business exceptions (wrap them)            |
  +---------------------------+---------------------------+
                              |
                              | DTO / Command objects
                              v
  +-------------------------------------------------------+
  |  APPLICATION SERVICE (Use Case Orchestration)          |
  |                                                       |
  |  Responsibilities:                                    |
  |    - Orchestrate the use case workflow                |
  |    - Open transaction boundaries                      |
  |    - Coordinate domain services, repositories,        |
  |      and external gateways                            |
  |    - Publish domain events after successful commit    |
  |    - Handle cross-cutting: logging, metrics, auth     |
  |                                                       |
  |  NEVER:                                               |
  |    - Know about HTTP                                  |
  |    - Contain domain logic (delegate to domain)        |
  |    - Return ResponseEntity                             |
  +---------------------------+---------------------------+
                              |
                              | Domain objects / IDs
                              v
  +-------------------------------------------------------+
  |  DOMAIN SERVICE (Pure Domain Logic)                    |
  |                                                       |
  |  Responsibilities:                                    |
  |    - Execute domain rules on domain objects           |
  |    - Enforce invariants across multiple entities      |
  |    - Calculate business values                        |
  |    - Stateless operations that don't belong to        |
  |      any single entity                                |
  |                                                       |
  |  NEVER:                                               |
  |    - Open or manage transactions (caller does that)   |
  |    - Call repositories directly (ideally)             |
  |    - Know about infrastructure                        |
  +-------------------------------------------------------+
```

```
Key mental model distinction:

  Application Service  ≠  Domain Service
  
  Application Service = "WHAT happens" (the use case)
       "Place an order" -> validate stock, reserve inventory, charge payment,
                            create order, send confirmation email
  
  Domain Service = "HOW a rule works" (the algorithm)
       "Calculate shipping cost" -> weight * rate + surcharge for express
       "Validate credit limit"  -> total outstanding < creditLimit
```

```
The Thin Controller Pattern:

  GOOD controller (thin):
  ┌─────────────────────────────────────────────┐
  │ @PostMapping("/orders")                      │
  │ public ResponseEntity<?> placeOrder(         │
  │     @Valid @RequestBody PlaceOrderRequest r, │
  │     @CurrentUser User user) {                │
  │                                             │
  │   PlaceOrderCommand cmd = r.toCommand(       │
  │       user.getId());                         │
  │                                             │
  │   OrderResult result = orderService          │
  │       .placeOrder(cmd);                      │
  │                                             │
  │   return ResponseEntity                       │
  │       .created(URI.create("/orders/"         │
  │           + result.orderId()))               │
  │       .body(OrderResponse.from(result));     │
  │ }                                            │
  └─────────────────────────────────────────────┘
  Lines: ~12. HTTP concern only.

  BAD controller (fat):
  ┌─────────────────────────────────────────────┐
  │ @PostMapping("/orders")                      │
  │ @Transactional                               │ <- WRONG layer
  │ public ResponseEntity<?> placeOrder(         │
  │     ...25 parameters...) {                   │ <- TOO MANY
  │                                             │
  │   if (request.getAmount() == null)           │ <- Business validation
  │     return badRequest(...);                  │
  │                                             │
  │   Customer customer = customerRepo           │ <- Repository in controller
  │       .findById(request.getCustomerId());    │
  │                                             │
  │   if (customer.getCreditLimit() < ...)       │ <- Domain logic
  │     return badRequest(...);                  │
  │                                             │
  │   Order order = new Order();                 │ <- Entity construction
  │   order.setStatus("PENDING");               │
  │   orderRepository.save(order);               │
  │                                             │
  │   paymentGateway.charge(...);                │ <- External call in controller
  │   emailService.send(...);                    │
  │   ...200 more lines...                       │
  │ }                                            │
  └─────────────────────────────────────────────┘
  Lines: ~200. Unreadable nightmare.
```

## 3. Internal Architecture

### How @RequestMapping Annotations Become RequestMappingInfo at Startup

```java
// Source: org.springframework.web.servlet.handler.AbstractHandlerMethodMapping
// This is the build-time phase where annotations are parsed into runtime metadata.

public abstract class AbstractHandlerMethodMapping<T> 
        extends AbstractHandlerMapping implements InitializingBean {

    @Override
    public void afterPropertiesSet() {
        initHandlerMethods();  // Entry point: scan all @Controller beans
    }

    protected void initHandlerMethods() {
        for (String beanName : getCandidateBeanNames()) {
            if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
                processCandidateBean(beanName);
            }
        }
        handlerMethodsInitialized(getHandlerMethods());
    }

    protected void processCandidateBean(String beanName) {
        Class<?> beanType = null;
        try {
            beanType = obtainApplicationContext().getType(beanName);
        } catch (Throwable ex) {
            if (logger.isTraceEnabled()) {
                logger.trace("Could not resolve type for bean '" +
                        beanName + "'", ex);
            }
        }
        if (beanType != null && isHandler(beanType)) {
            detectHandlerMethods(beanName);
        }
    }

    protected void detectHandlerMethods(Object handler) {
        Class<?> handlerType = (handler instanceof String beanName
                ? obtainApplicationContext().getType(beanName)
                : handler.getClass());

        if (handlerType != null) {
            Class<?> userType = ClassUtils.getUserClass(handlerType);

            // Introspect ALL methods, calling getMappingForMethod for each
            Map<Method, T> methods = MethodIntrospector.selectMethods(
                    userType,
                    (MethodIntrospector.MetadataLookup<T>) method -> {
                        try {
                            return getMappingForMethod(method, userType);
                        } catch (Throwable ex) {
                            throw new IllegalStateException(
                                    "Invalid mapping on handler class [" +
                                    userType.getName() + "]: " + method, ex);
                        }
                    });

            methods.forEach((method, mapping) -> {
                Method invocableMethod = AopUtils.selectInvocableMethod(
                        method, userType);
                registerHandlerMethod(handler, invocableMethod, mapping);
            });
        }
    }
}
```

### RequestMappingHandlerMapping.getMappingForMethod() -- Annotation to RequestMappingInfo

```java
// Source: org.springframework.web.servlet.mvc.method.annotation
//         .RequestMappingHandlerMapping

@Override
protected RequestMappingInfo getMappingForMethod(
        Method method, Class<?> handlerType) {

    // Step 1: Look for @RequestMapping (or meta-annotations like @GetMapping)
    RequestMappingInfo info = createRequestMappingInfo(
            AnnotatedElementUtils.findMergedAnnotation(
                    method, RequestMapping.class));

    // Step 2: Check for TYPE-LEVEL @RequestMapping (e.g., @RequestMapping("/api/orders"))
    if (info != null) {
        RequestMappingInfo typeInfo = createRequestMappingInfo(
                AnnotatedElementUtils.findMergedAnnotation(
                        handlerType, RequestMapping.class));
        if (typeInfo != null) {
            info = typeInfo.combine(info);
            // Patterns: "/api/orders" + "/{id}" -> "/api/orders/{id}"
            // Methods:  type-level methods ∩ method-level methods
            // Headers:  type-level headers + method-level headers
        }
    }
    return info;
}

private RequestMappingInfo createRequestMappingInfo(
        RequestMapping requestMapping) {

    if (requestMapping == null) return null;

    return RequestMappingInfo
            .paths(resolveEmbeddedValuesInPatterns(requestMapping.path()))
            .methods(requestMapping.method())
            .params(requestMapping.params())
            .headers(requestMapping.headers())
            .consumes(requestMapping.consumes())
            .produces(requestMapping.produces())
            .mappingName(requestMapping.name())
            .options(getBuilderConfiguration())
            .build();
}
```

### RequestMappingInfo -- The Internal Data Structure

```java
// Source: org.springframework.web.servlet.mvc.method.RequestMappingInfo
// This object represents the COMPLETE request matching criteria.

public final class RequestMappingInfo implements RequestCondition<RequestMappingInfo> {

    private final String name;
    private final PatternsRequestCondition patternsCondition;    // URL patterns
    private final RequestMethodsRequestCondition methodsCondition; // GET, POST, etc.
    private final ParamsRequestCondition paramsCondition;         // ?param=value
    private final HeadersRequestCondition headersCondition;       // X-Header: value
    private final ConsumesRequestCondition consumesCondition;     // Content-Type
    private final ProducesRequestCondition producesCondition;     // Accept
    private final RequestConditionHolder customConditionHolder;   // Custom conditions

    // Example: @PostMapping(value = "/orders", params = "v=2",
    //            headers = "X-Idempotency-Key", consumes = "application/json")
    // Results in:
    //   patternsCondition:    ["/orders"]
    //   methodsCondition:     [POST]
    //   paramsCondition:      [Condition { name="v", value="2" }]
    //   headersCondition:     [Condition { name="X-Idempotency-Key", value=none }]
    //   consumesCondition:    [MediaType("application/json")]
    //   producesCondition:    [] (default, matches anything)

    @Override
    public RequestMappingInfo combine(RequestMappingInfo other) {
        // Combines TYPE-level and METHOD-level RequestMappingInfo
        String name = combineNames(other);
        PatternsRequestCondition patterns =
                this.patternsCondition.combine(other.patternsCondition);
        RequestMethodsRequestCondition methods =
                this.methodsCondition.combine(other.methodsCondition);
        ParamsRequestCondition params =
                this.paramsCondition.combine(other.paramsCondition);
        HeadersRequestCondition headers =
                this.headersCondition.combine(other.headersCondition);
        ConsumesRequestCondition consumes =
                this.consumesCondition.combine(other.consumesCondition);
        ProducesRequestCondition produces =
                this.producesCondition.combine(other.producesCondition);

        return new RequestMappingInfo(name, patterns, methods, params,
                headers, consumes, produces, customConditionHolder);
    }
}
```

### MappingRegistry -- The Runtime Lookup Data Structure

```java
// Source: org.springframework.web.servlet.handler.AbstractHandlerMethodMapping
//         inner class MappingRegistry

class MappingRegistry {
    // Complete registry: RequestMappingInfo -> HandlerMethod (with metadata)
    private final Map<T, MappingRegistration<T>> registry = new HashMap<>();

    // Fast path-based lookup: literal path -> list of RequestMappingInfo
    // "/orders" -> [{GET /orders}, {POST /orders}]
    // "/orders/{id}" -> [{GET /orders/{id}}, {PUT /orders/{id}}, {DELETE /orders/{id}}]
    private final MultiValueMap<String, T> pathLookup = new LinkedMultiValueMap<>();

    // Direct lookup for exact paths WITHOUT variables
    // "/api/health" -> {GET /api/health}
    // (Paths with {variables} are NOT in this map)
    private final Map<String, T> nameLookup = new ConcurrentHashMap<>();

    public void register(T mapping, Object handler, Method method) {
        this.readWriteLock.writeLock().lock();
        try {
            HandlerMethod handlerMethod = createHandlerMethod(handler, method);
            validateMethodMapping(handlerMethod, mapping);

            // Put in main registry
            this.registry.put(mapping,
                    new MappingRegistration<>(mapping, handlerMethod,
                            getDirectPaths(mapping)));

            // Populate pathLookup:
            // @GetMapping("/orders/{id}") -> pathLookup.add("/orders/{id}", mapping)
            for (String path : getDirectPaths(mapping)) {
                this.pathLookup.add(path, mapping);
            }

            // Populate nameLookup ONLY for paths without wildcards/variables
            for (String path : getDirectPaths(mapping)) {
                if (!path.contains("{") && !path.contains("*")
                        && !path.contains("?")) {
                    this.nameLookup.put(path, mapping);
                }
            }
        } finally {
            this.readWriteLock.writeLock().unlock();
        }
    }
}
```

### HandlerMethod Argument Resolution -- Full Chain at Runtime

```java
// Source: org.springframework.web.method.support.InvocableHandlerMethod

@Nullable
public Object invokeForRequest(NativeWebRequest request,
        @Nullable ModelAndViewContainer mavContainer,
        Object... providedArgs) throws Exception {

    Object[] args = getMethodArgumentValues(request, mavContainer, providedArgs);
    if (logger.isTraceEnabled()) {
        logger.trace("Arguments: " + Arrays.toString(args));
    }
    return doInvoke(args);
}

protected Object[] getMethodArgumentValues(NativeWebRequest request,
        @Nullable ModelAndViewContainer mavContainer,
        Object... providedArgs) throws Exception {

    MethodParameter[] parameters = getMethodParameters();
    if (ObjectUtils.isEmpty(parameters)) {
        return EMPTY_ARGS;
    }

    Object[] args = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
        MethodParameter parameter = parameters[i];
        parameter.initParameterNameDiscovery(
                this.parameterNameDiscoverer);

        args[i] = findProvidedArgument(parameter, providedArgs);
        if (args[i] != null) {
            continue;
        }

        // Check each resolver in order
        if (!this.resolvers.supportsParameter(parameter)) {
            throw new IllegalStateException(
                    formatArgumentError(parameter,
                            "No suitable resolver"));
        }
        try {
            args[i] = this.resolvers.resolveArgument(
                    parameter, mavContainer, request,
                    this.dataBinderFactory);
        } catch (Exception ex) {
            if (logger.isDebugEnabled()) {
                String exMsg = ex.getMessage();
                if (exMsg != null && !exMsg.contains(
                        parameter.getExecutable().toGenericString())) {
                    logger.debug(formatArgumentError(parameter,
                            exMsg));
                }
            }
            throw ex;
        }
    }
    return args;
}
```

### HandlerMethodReturnValueHandler Chain

```java
// Source: ServletInvocableHandlerMethod.invokeAndHandle()

public void invokeAndHandle(ServletWebRequest webRequest,
        ModelAndViewContainer mavContainer,
        Object... providedArgs) throws Exception {

    Object returnValue = invokeForRequest(webRequest, mavContainer,
            providedArgs);

    setResponseStatus(webRequest);

    if (returnValue == null) {
        if (isRequestNotModified(webRequest)
                || getResponseStatus() != null
                || mavContainer.isRequestHandled()) {
            mavContainer.setRequestHandled(true);
            return;
        }
    } else if (StringUtils.hasText(getResponseStatusReason())) {
        mavContainer.setRequestHandled(true);
        return;
    }

    mavContainer.setRequestHandled(false);

    try {
        this.returnValueHandlers.handleReturnValue(
                returnValue,
                getReturnValueType(returnValue),
                mavContainer, webRequest);
    } catch (Exception ex) {
        if (logger.isTraceEnabled()) {
            logger.trace(formatErrorForReturnValue(returnValue), ex);
        }
        throw ex;
    }
}
```

### HandlerMethodReturnValueHandler.handleReturnValue() Dispatch

```java
// Source: HandlerMethodReturnValueHandlerComposite

@Override
public void handleReturnValue(@Nullable Object returnValue,
        MethodParameter returnType,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest) throws Exception {

    HandlerMethodReturnValueHandler handler = selectHandler(
            returnValue, returnType);
    if (handler == null) {
        throw new IllegalArgumentException(
                "Unknown return value type: " +
                returnType.getParameterType().getName());
    }
    handler.handleReturnValue(returnValue, returnType,
            mavContainer, webRequest);
}

@Nullable
private HandlerMethodReturnValueHandler selectHandler(
        @Nullable Object value, MethodParameter returnType) {

    boolean isAsyncValue = isAsyncReturnValue(value, returnType);
    for (HandlerMethodReturnValueHandler handler :
            this.returnValueHandlers) {
        if (isAsyncValue && !(handler instanceof
                AsyncHandlerMethodReturnValueHandler)) {
            continue;
        }
        if (handler.supportsReturnType(returnType)) {
            return handler;
        }
    }
    return null;
}
```

### Complete Request-to-Response Trace Through the Handler Layer

```
POST /api/orders  { "customerId": 42, "items": [{"sku": "A1", "qty": 2}] }
Content-Type: application/json

    +-- DispatcherServlet.doDispatch()
        |
        +-- getHandler(request)
        |   +-- RequestMappingHandlerMapping.getHandler()
        |       +-- lookupHandlerMethod("/api/orders", POST)
        |       |   +-- nameLookup: "/api/orders" not found (has /api prefix)
        |       |   +-- pathLookup: "/api/orders" found
        |       |   +-- Check methodsCondition: POST matches
        |       |   +-- Check consumesCondition: matches
        |       |   +-- Return HandlerMethod(OrderController@4f3c, placeOrder())
        |       +-- Return HandlerExecutionChain {
        |               handler: HandlerMethod(placeOrder),
        |               interceptors: [AuthInterceptor, MetricsInterceptor]
        |           }
        |
        +-- getHandlerAdapter(handler)
        |   +-- handler instanceof HandlerMethod -> RequestMappingHandlerAdapter
        |
        +-- mappedHandler.applyPreHandle()
        |   +-- AuthInterceptor.preHandle() -> true
        |   +-- MetricsInterceptor.preHandle() -> true
        |
        +-- ha.handle(request, response, handlerMethod)
        |   +-- invokeHandlerMethod()
        |   |   +-- resolve arguments for: placeOrder(
        |   |   |       @Valid @RequestBody PlaceOrderRequest body,
        |   |   |       @CurrentUser User user)
        |   |   |
        |   |   |       arg[0] = PlaceOrderRequest body:
        |   |   |         -> RequestResponseBodyMethodProcessor.supportsParameter()
        |   |   |         -> detect @RequestBody -> true
        |   |   |         -> readWithMessageConverters():
        |   |   |             Content-Type: application/json
        |   |   |             -> MappingJackson2HttpMessageConverter.read()
        |   |   |             -> ObjectMapper.readValue(inputStream, PlaceOrderRequest.class)
        |   |   |             -> PlaceOrderRequest{customerId=42, items=[...]}
        |   |   |         -> @Valid triggers Bean Validation
        |   |   |             -> Validator.validate(body)
        |   |   |             -> if violations: throw MethodArgumentNotValidException
        |   |   |
        |   |   |       arg[1] = User user:
        |   |   |         -> CurrentUserArgumentResolver.supportsParameter()
        |   |   |         -> has @CurrentUser annotation -> true
        |   |   |         -> resolveArgument():
        |   |   |             Authentication auth = SecurityContextHolder.getContext()
        |   |   |                 .getAuthentication()
        |   |   |             -> return (User) auth.getPrincipal()
        |   |   |
        |   |   +-- doInvoke([body, user]):
        |   |       -> method.invoke(controller, body, user)
        |   |           -> OrderController.placeOrder(body, user)
        |   |               -> PlaceOrderCommand cmd = body.toCommand(user.getId())
        |   |               -> OrderApplicationService.placeOrder(cmd)
        |   |               -> return ResponseEntity.created(uri)
        |   |                   .body(OrderResponse.from(result))
        |   |
        |   +-- handleReturnValue():
        |       -> HttpEntityMethodProcessor.supportsReturnType()
        |           -> ResponseEntity extends HttpEntity -> true
        |       -> HttpEntityMethodProcessor.handleReturnValue():
        |           -> setStatus: 201 Created
        |           -> setHeaders: Location = /orders/12345
        |           -> serialized body via MappingJackson2HttpMessageConverter
        |           -> write to response.getOutputStream()
        |
        +-- mappedHandler.applyPostHandle()
        +-- mappedHandler.triggerAfterCompletion()
```

## 4. Runtime Behavior

### Scenario 1: Valid POST /orders with JSON Body

```
Timeline: POST /api/orders  { "customerId": 42, "items": [{"sku": "A1", "qty": 2}] }

T=0ms   Tomcat worker http-nio-8080-exec-3 picks up request
T=1ms   FilterChain: CharacterEncodingFilter, RequestContextFilter, SecurityFilter
T=2ms   DispatcherServlet.doDispatch()

T=3ms   getHandler() -> RequestMappingHandlerMapping
        -> pathLookup finds "/api/orders"
        -> methodsCondition: POST matches
        -> returns HandlerMethod(OrderController@3f2a.placeOrder(PlaceOrderRequest, User))
        
T=4ms   getHandlerAdapter() -> RequestMappingHandlerAdapter (supports HandlerMethod)
T=5ms   preHandle interceptors: AuthInterceptor checks Bearer token -> valid

T=6ms   ha.handle():
        -> resolve arg[0] @RequestBody PlaceOrderRequest:
           Jackson deserializes JSON -> PlaceOrderRequest{customerId=42, items=[...]}
           @Valid triggers Bean Validation -> all constraints pass
        -> resolve arg[1] @CurrentUser User:
           SecurityContext.getAuthentication().getPrincipal() -> User{id=42}
        -> invoke: controller.placeOrder(body, user)
           -> body.toCommand(userId=42) -> PlaceOrderCommand{customerId=42, items=[...], userId=42}
           -> orderApplicationService.placeOrder(command):
              T=8ms   @Transactional begins (new transaction)
              T=9ms   customer = customerRepo.findByIdOrThrow(42)
              T=10ms  inventoryService.reserve(command.items())  -- domain service
              T=12ms  order = Order.place(command)  -- domain entity factory
              T=13ms  orderRepo.save(order)  -- JPA persist
              T=14ms  paymentGateway.authorize(order.total())  -- external call
              T=15ms  eventPublisher.publish(OrderPlaced(order)) -- after commit
              T=16ms  @Transactional commits
              T=17ms  return PlaceOrderResult{orderId=12345, ...}
        -> controller wraps: ResponseEntity.created(URI("/orders/12345"))
                              .body(OrderResponse{id=12345, status="PLACED"})
        
T=18ms  handleReturnValue():
        -> HttpEntityMethodProcessor handles ResponseEntity
        -> status = 201, Location header set
        -> Jackson serializes OrderResponse -> {"id":12345,"status":"PLACED",...}
        -> bytes written to response

T=20ms  postHandle, afterCompletion interceptors
T=22ms  Response flushed to client: HTTP/1.1 201 Created
```

### Scenario 2: Validation Failure on POST /orders

```
Timeline: POST /api/orders with invalid body { "customerId": null, "items": [] }

T=0-5ms Same as Scenario 1 through handler identification

T=6ms   resolve arg[0] @RequestBody @Valid PlaceOrderRequest body:
        -> Jackson deserializes -> PlaceOrderRequest{customerId=null, items=[]}
        -> @Valid triggers validation:
           -> Validator.validate(body):
           |   customerId: @NotNull -> VIOLATED
           |   items:      @NotEmpty -> VIOLATED
           -> ConstraintViolationException within Spring's DataBinder
        -> throws MethodArgumentNotValidException

T=7ms   Exception propagates from getMethodArgumentValues()

T=8ms   Back in doDispatch():
        catch (Exception ex) { dispatchException = ex; }
        -> processDispatchResult(..., dispatchException=MethodArgumentNotValidException):
        
        -> processHandlerException():
           -> ExceptionHandlerExceptionResolver.resolveException():
              -> Check controller for @ExceptionHandler(MethodArgumentNotValidException.class)
              -> Found: handleValidationErrors(ex)
              -> Invoke: returns ResponseEntity<ErrorResponse>{status=400, body={...}}
              -> ErrorResponse contains field-level errors:
                 {"errors": [
                   {"field":"customerId","message":"must not be null"},
                   {"field":"items","message":"must not be empty"}
                 ]}

T=10ms  response: HTTP/1.1 400 Bad Request
                  Content-Type: application/json
                  Body: {"errors":[...]}
```

## 5. Request Flow Diagrams

### Thin Controller Pattern Flow

```
  HTTP Request
      |
      v
  +------------------+     DTO/Command         +-----------------------+
  |  Controller      |------------------------>| Application Service    |
  |                  |                         |                       |
  | 1. Extract       |                         | 1. Validate business  |
  |    HTTP params    |                        |    rules               |
  | 2. Deserialize   |                         | 2. Open transaction   |
  |    JSON body     |                         | 3. Coordinate:        |
  | 3. Validate      |                         |    - Domain services  |
  |    constraints   |                         |    - Repositories     |
  | 4. Convert to    |                         |    - External APIs    |
  |    command/DTO   |                         | 4. Return result DTO  |
  | 5. Call service  |                         |                       |
  | 6. Map result to |                         |                       |
  |    HTTP response |                         |                       |
  +------------------+                         +-----------^-----------+
                                                           |
                         +---------------------------------+--------+
                         |                                          |
                         v                                          v
              +------------------+                        +------------------+
              | Domain Service   |                        | Repository       |
              |                  |                        |                  |
              | Pure business    |                        | Data access      |
              | logic. No        |                        | only. No         |
              | transaction,     |                        | business logic.  |
              | no HTTP.         |                        |                  |
              +------------------+                        +------------------+
```

### Fat Controller Anti-Pattern Flow (What Not to Do)

```
  HTTP Request
      |
      v
  +------------------------------------------------------------+
  |  FAT CONTROLLER (everything in one place)                   |
  |                                                            |
  |  @PostMapping @Transactional   <-- Transaction in controller|
  |  public ResponseEntity<?> create(                          |
  |      @RequestBody Map<String,Object> raw,  <-- No DTO!     |
  |      HttpServletRequest req, HttpServletResponse res) {    |
  |                                                            |
  |    // Manual JSON parsing                                 |
  |    String name = (String) raw.get("name");                |
  |    if (name == null) {                  <-- Validation    |
  |        res.setStatus(400);                                |
  |        return null;                  <-- Manual 400        |
  |    }                                                      |
  |                                                            |
  |    // Direct repository access                            |
  |    Customer c = repo.findById(1L).orElse(null);   <--     |
  |                                                            |
  |    // Business logic in controller                        |
  |    if (c.getCreditLimit() < amount) {                     |
  |        res.setStatus(402);                                |
  |        return null;                                       |
  |    }                                                      |
  |                                                            |
  |    // Direct entity creation                              |
  |    Order o = new Order();                                 |
  |    o.setCustomer(c);                                      |
  |    o.setItems(items);                                     |
  |    o.setTotal(calculateTotal());   <-- Logic               |
  |    repo.save(o);                                           |
  |                                                            |
  |    // External calls in controller                        |
  |    paymentGateway.charge(...);     <-- Gateway             |
  |    emailService.send(...);         <-- Email               |
  |                                                            |
  |    return ResponseEntity.ok(Map.of("id", o.getId()));     |
  |                                                            |
  |    // 12 responsibilities, 0 separation                    |
  | }                                                         |
  +------------------------------------------------------------+
```

### Complex Workflow Orchestration: Saga Pattern with Application Service

```
  Application Service: PlaceOrderSaga
  (Orchestration-based saga)

  POST /orders (Controller)
      |
      v
  +----------------------------------------------------------+
  | PlaceOrderSaga.placeOrder(PlaceOrderCommand cmd)          |
  |                                                            |
  |  @Transactional                                            |
  |  public PlaceOrderResult placeOrder(PlaceOrderCommand cmd) {|
  |                                                            |
  |    // STEP 1: Validate and reserve                         |
  |    InventoryReservation reservation =                      |
  |        inventoryService.reserve(cmd.items());              |
  |                                   |                        |
  |    // STEP 2: Authorize payment    |                       |
  |    PaymentAuthorization payment =  |                       |
  |        paymentService.authorize(   | (may fail ->          |
  |            cmd.customerId(),       |  release              |
  |            cmd.total());           |  reservation)         |
  |                                   |                        |
  |    // STEP 3: Create order         |                       |
  |    Order order = Order.place(     |                       |
  |        cmd, reservation, payment); |                       |
  |    orderRepo.save(order);          v                       |
  |                                                            |
  |    // STEP 4: Emit events                                 |
  |    eventPublisher.publish(new OrderPlaced(order));         |
  |    // (processed after commit via @TransactionalEventListener)
  |                                                            |
  |    // STEP 5: Return result                               |
  |    return PlaceOrderResult.from(order);                    |
  |  }                                                         |
  |                                                            |
  |  // Compensation (if payment fails):                       |
  |  @TransactionalEventListener(phase=AFTER_COMMIT)           |
  |  public void onOrderPlaced(OrderPlaced event) {            |
  |    // Async: send confirmation email, update analytics,    |
  |    // notify fulfillment system                            |
  |  }                                                         |
  |                                                            |
  |  @TransactionalEventListener(phase=AFTER_ROLLBACK)         |
  |  public void onPaymentFailed(PaymentFailed event) {        |
  |    // Compensate: release inventory reservation            |
  |    inventoryService.releaseReservation(event.reservationId());|
  |  }                                                         |
  +----------------------------------------------------------+
```

## 6. Lifecycle Diagrams

### Controller Bean Lifecycle

```
  +------------------------------------------------------------------+
  |                 @RestController BEAN LIFECYCLE                    |
  +------------------------------------------------------------------+

  1. BEAN DEFINITION PHASE (refresh step 5)
     +-- Component scanning: @RestController detected by ClassPathBeanDefinitionScanner
     +-- BeanDefinition registered:
     |   beanClass = OrderController.class
     |   scope = SCOPE_SINGLETON (default)
     |   role = ROLE_APPLICATION
     +-- AbstractBeanDefinition stored in beanDefinitionMap

  2. HANDLER MAPPING REGISTRATION PHASE (refresh step 11)
     +-- RequestMappingHandlerMapping.afterPropertiesSet()
     +-- initHandlerMethods():
     |   +-- Iterate all bean definitions
     |   +-- isHandler(beanType): checks for @Controller or @RequestMapping
     |   |       -> OrderController has @RestController -> true
     |   +-- detectHandlerMethods("orderController"):
     |       +-- Introspect OrderController methods
     |       +-- For placeOrder(): getMappingForMethod(method, OrderController.class)
     |       |   +-- Find @PostMapping on method -> RequestMappingInfo{...}
     |       |   +-- Find @RequestMapping("/api/orders") on class -> combine()
     |       |   +-- Result: POST /api/orders
     |       +-- registerHandlerMethod("orderController", method, mapping):
     |           +-- MappingRegistry.register():
     |               +-- registry.put(mapping, new MappingRegistration<>(...))
     |               +-- pathLookup.add("/api/orders", mapping)
     |               +-- (no nameLookup, path contains no variables but will still work)
     |       +-- Repeat for getOrder(), updateOrder(), deleteOrder()
     |
     +-- All @RequestMapping methods registered before ANY request arrives

  3. BEAN INSTANTIATION PHASE (refresh step 11, after registrations)
     +-- getBean("orderController")
     +-- createBean():
     |   +-- resolve constructor dependencies:
     |       +-- OrderApplicationService (autowired)
     |       +-- CurrentUserArgumentResolver may also inject here
     |   +-- instantiate: new OrderController(orderApplicationService)
     |   +-- populateBean(): @Autowired fields set
     |   +-- initializeBean():
     |       +-- @PostConstruct methods called
     |       +-- BeanPostProcessor.postProcessAfterInitialization():
     |           +-- (no AOP proxy needed for @RestController -- no transactional methods)
     |   +-- addSingleton("orderController", bean)
     |
     +-- Bean is now a singleton in singletonObjects cache

  4. RUNTIME (per-request):
     +-- HandlerMethod holds reference to bean INSTANCE (no lookup needed)
     +-- Each request calls the SAME singleton instance
     +-- Controller is stateless (should have no mutable state)
     +-- Thread-safety: Controller fields (injected services) are immutable post-construction

  5. SHUTDOWN:
     +-- destroyBeans() -> @PreDestroy methods called
     +-- MappingRegistry cleared
     +-- Controller bean removed from singletonObjects
```

### Application Service Lifecycle with @Transactional Proxying

```
  +------------------------------------------------------------------+
  |         @Transactional SERVICE BEAN LIFECYCLE                     |
  +------------------------------------------------------------------+

  1. BEAN DEFINITION (refresh step 5)
     +-- Component scan: @Service detected
     +-- BeanDefinition: OrderApplicationService.class, SCOPE_SINGLETON

  2. BEAN INSTANTIATION (refresh step 11)
     +-- createBean("orderApplicationService"):
     |   +-- Constructor: new OrderApplicationService(repo, invSvc, paySvc)
     |   +-- populateBean(): autowire remaining fields
     |
     |   +-- initializeBean():
     |       +-- BeanPostProcessor chain:
     |           +-- InfrastructureAdvisorAutoProxyCreator
     |               (wraps beans that match @Transactional pointcuts)
     |           +-- Checks: does any Advisor match?
     |           +-- BeanFactoryTransactionAttributeSourceAdvisor:
     |               +-- Checks @Transactional on ORDER APPLICATION SERVICE methods
     |               +-- placeOrder() has @Transactional -> MATCH
     |               +-- getOrder() has @Transactional(readOnly=true) -> MATCH
     |           +-- Creates CGLIB proxy (for class-based proxy):
     |               +-- Proxy class: OrderApplicationService$$SpringCGLIB$$0
     |               +-- Extends OrderApplicationService
     |               +-- Overrides placeOrder() with TransactionInterceptor call
     |
     |   +-- singletonObjects.put("orderApplicationService", PROXY, not raw bean)
     |
     +-- IMPORTANT: The "orderApplicationService" bean in the context is the PROXY,
     |   not the raw instance. Any bean that injects "orderApplicationService"
     |   gets the proxy.
     |
     +-- TRANSACTION PROXY BEHAVIOR:
         +-- Every call goes through TransactionInterceptor.invoke()
         +-- Before: PlatformTransactionManager.getTransaction()
         +-- After success: PlatformTransactionManager.commit()
         +-- After exception: PlatformTransactionManager.rollback()

  3. RUNTIME (@Transactional method invocation):
     TransactionInterceptor.invoke(MethodInvocation):
       -> TransactionAttribute txAttr = getTransactionAttribute(method)
          // Reads @Transactional annotation: propagation, isolation, readOnly, etc.
       -> PlatformTransactionManager tm = determineTransactionManager()
       -> TransactionInfo txInfo = tm.getTransaction(txAttr)
          // Creates or joins transaction based on propagation level
       -> try {
            result = invocation.proceed()  // <-- actual method call
            tm.commit(txInfo.getTransactionStatus())
          } catch (Throwable ex) {
            if (txAttr.rollbackOn(ex))
              tm.rollback(txInfo.getTransactionStatus())
            else
              tm.commit(txInfo.getTransactionStatus())
            throw ex
          }
```

## 7. Source Code Reading Guide

### Critical Files to Read (In Order)

```
1. org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
   spring-webmvc/.../RequestMappingHandlerMapping.java (~400 lines)
   -> getMappingForMethod() -- annotation to metadata conversion
   -> isHandler() -- determines if a bean is a controller
   -> createRequestMappingInfo() -- builds RequestMappingInfo from an annotation

2. org.springframework.web.servlet.mvc.method.RequestMappingInfo
   spring-webmvc/.../method/RequestMappingInfo.java (~800 lines)
   -> Builder pattern for creating RequestMappingInfo
   -> combine() -- merges type-level and method-level mappings
   -> getMatchingCondition() -- runtime request matching

3. org.springframework.web.servlet.handler.AbstractHandlerMethodMapping
   spring-webmvc/.../handler/AbstractHandlerMethodMapping.java (~600 lines)
   -> afterPropertiesSet() -> initHandlerMethods() -- startup scanning
   -> lookupHandlerMethod() -- runtime lookup
   -> MappingRegistry inner class -- the data structures

4. org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
   spring-webmvc/.../RequestMappingHandlerAdapter.java (~1200 lines)
   -> handleInternal() -> invokeHandlerMethod()
   -> getDefaultArgumentResolvers() -- builds the arg resolver chain
   -> getDefaultReturnValueHandlers() -- builds the return value chain

5. org.springframework.web.method.support.InvocableHandlerMethod
   spring-web/.../method/support/InvocableHandlerMethod.java (~400 lines)
   -> invokeForRequest() -- entry point for controller invocation
   -> getMethodArgumentValues() -- resolves all arguments
   -> doInvoke() -- reflection invocation

6. org.springframework.web.method.support.HandlerMethodArgumentResolverComposite
   spring-web/.../method/support/HandlerMethodArgumentResolverComposite.java (~120 lines)
   -> resolveArgument() -- iterates resolver chain
   -> supportsParameter() -- finds matching resolver

7. org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite
   spring-web/.../method/support/HandlerMethodReturnValueHandlerComposite.java (~120 lines)
   -> handleReturnValue() -- dispatches to correct handler
   -> selectHandler() -- finds matching handler by return type

8. org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor
   spring-webmvc/.../RequestResponseBodyMethodProcessor.java (~200 lines)
   -> supportsParameter() -- @RequestBody detection
   -> resolveArgument() -- reads body via HttpMessageConverter
   -> supportsReturnType() -- @ResponseBody detection

9. org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor
   spring-webmvc/.../HttpEntityMethodProcessor.java (~300 lines)
   -> supportsReturnType() -- ResponseEntity detection
   -> handleReturnValue() -- sets status, headers, serializes body

10. org.springframework.transaction.interceptor.TransactionInterceptor
    spring-tx/.../interceptor/TransactionInterceptor.java (~200 lines)
    -> invoke() -- the AOP advice that wraps @Transactional methods
    -> invokeWithinTransaction() -- the core transaction logic

11. org.springframework.transaction.interceptor.TransactionAspectSupport
    spring-tx/.../TransactionAspectSupport.java (~500 lines)
    -> currentTransactionStatus() -- ThreadLocal transaction status
    -> invokeWithinTransaction() -- getTransaction/commit/rollback flow
```

### How to Navigate the Source in Your IDE

```
Start here:                                    Key method:
  DispatcherServlet.doDispatch()               -> getHandler(), ha.handle()
    -> AbstractHandlerMethodMapping.lookupHandlerMethod()  -> pathLookup + sort
    -> RequestMappingHandlerAdapter.handleInternal()       -> invokeHandlerMethod()
      -> ServletInvocableHandlerMethod.invokeAndHandle()   -> resolve + invoke + handle return
        -> InvocableHandlerMethod.getMethodArgumentValues() -> resolver chain
        -> HandlerMethodReturnValueHandlerComposite        -> return handler chain
        
Transaction side:
  TransactionInterceptor.invoke()
    -> TransactionAspectSupport.invokeWithinTransaction()
      -> PlatformTransactionManager.getTransaction()
      -> proceed()  (actual method call)
      -> PlatformTransactionManager.commit() / rollback()
```

## 8. Production Failure Scenarios

### Scenario 1: @Transactional on Controller Causes LazyInitializationException

**Symptom**: `LazyInitializationException: could not initialize proxy - no Session` when accessing entity relationships after the controller method returns. Happens inconsistently on some response types.

**Root cause**: `@Transactional` on the controller means the transaction commits BEFORE the return value handler serializes the response body. By the time Jackson iterates the entity graph, the Hibernate Session is closed.

```java
// BROKEN -- @Transactional on controller:
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    
    @GetMapping("/{id}")
    @Transactional  // <-- WRONG! Transaction commits before Jackson serializes
    public Order getOrder(@PathVariable Long id) {
        return orderRepo.findById(id).orElseThrow();
        // Returned Order still attached. But transaction commit happens HERE,
        // BEFORE Jackson iterates order.getItems() (lazy collection).
        // Result: LazyInitializationException during serialization
    }
}

// FIXED -- Transaction on service, DTO in controller:
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    
    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        // Service method is @Transactional(readOnly=true)
        // Transaction is still active when we build the DTO below
        OrderResult result = orderService.findById(id);
        return OrderResponse.from(result);  // All data is materialized
    }
}

@Service
public class OrderApplicationService {
    
    @Transactional(readOnly = true)
    public OrderResult findById(Long id) {
        Order order = orderRepo.findById(id).orElseThrow();
        // Force-load lazy associations within the transaction:
        order.getItems().size();  // Triggers lazy load
        return OrderResult.from(order);  // Materialize to DTO
    }
}
```

**Architectural prevention**: Add an ArchUnit rule: `noClasses().that().areAnnotatedWith(RestController.class).should().beAnnotatedWith(Transactional.class)`.

### Scenario 2: Service Layer Returns HttpEntity/ResponseEntity

**Symptom**: Unit tests for the service layer require mocking `HttpServletResponse`. Business logic is tangled with HTTP status codes. Service cannot be reused for non-HTTP entry points (message queue, scheduled job).

**Root cause**: The service layer is returning Spring MVC types. This couples business logic to the HTTP delivery mechanism.

```java
// BROKEN -- Service knows about HTTP:
@Service
public class OrderService {
    public ResponseEntity<Order> createOrder(OrderRequest request) {
        // Service now decides HTTP status. Cannot be used from a message listener.
        if (request.getAmount() < 0) {
            return ResponseEntity.badRequest().build();  // HTTP concern!
        }
        // ...
        return ResponseEntity.created(uri).body(order);
    }
}

// FIXED -- Service returns domain result, controller maps to HTTP:
@Service
public class OrderApplicationService {
    public PlaceOrderResult placeOrder(PlaceOrderCommand cmd) {
        // Pure business logic. No HTTP in sight.
        if (cmd.amount().isNegative()) {
            throw new InvalidOrderException("Amount must be positive");
        }
        Order order = Order.place(cmd);
        orderRepo.save(order);
        return PlaceOrderResult.from(order);
    }
}

@RestController
public class OrderController {
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody PlaceOrderRequest request) {
        try {
            PlaceOrderResult result = orderService.placeOrder(request.toCommand());
            return ResponseEntity.created(URI.create("/orders/" + result.id()))
                    .body(OrderResponse.from(result));
        } catch (InvalidOrderException e) {
            // Controller maps business exception to HTTP status
            // OR: use @ExceptionHandler
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
```

### Scenario 3: Fat Controller With 15+ Dependencies

**Symptom**: Controller constructor takes 15 parameters. Class is 800 lines. Unit test is impossible to set up. Every change risks breaking unrelated endpoints.

**Root cause**: The controller is acting as a "God Object" — it contains validation, transaction management, domain logic, external API calls, and response formatting.

```java
// Fix: Extract to focused application services
// Before: OrderController has 800 lines, 15 dependencies
// After:  OrderController delegates to:
//   OrderPlacementService, OrderQueryService, OrderCancellationService

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderPlacementService placementService;
    private final OrderQueryService queryService;
    private final OrderCancellationService cancellationService;

    @PostMapping
    public ResponseEntity<OrderResponse> place(
            @Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.created(...)
                .body(OrderResponse.from(
                    placementService.place(request.toCommand())));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return OrderResponse.from(queryService.findById(id));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id,
            @Valid @RequestBody CancelOrderRequest request) {
        return OrderResponse.from(
            cancellationService.cancel(id, request.toCommand()));
    }
}
```

### Scenario 4: @Transactional with Checked Exception That Does Not Roll Back

**Symptom**: A checked exception is thrown, the catch block logs it and returns an error response, but the database changes from the same transaction are committed — half the operation persists, half doesn't.

**Root cause**: By default, `@Transactional` only rolls back on `RuntimeException` and `Error`. Checked exceptions commit by default.

```java
// BROKEN -- Checked exception commits:
@Transactional
public void transferMoney(Long from, Long to, Money amount) 
        throws InsufficientFundsException {  // CHECKED exception
    accountRepo.debit(from, amount);
    Account toAccount = accountRepo.findById(to).orElseThrow();
    if (toAccount.isFrozen()) {
        throw new InsufficientFundsException("Account frozen");  // COMMITS the debit!
    }
    accountRepo.credit(to, amount);
}

// FIXED -- Specify rollbackFor:
@Transactional(rollbackFor = Exception.class)  // ALL exceptions roll back
public void transferMoney(Long from, Long to, Money amount) {
    accountRepo.debit(from, amount);
    Account toAccount = accountRepo.findById(to).orElseThrow();
    if (toAccount.isFrozen()) {
        throw new InsufficientFundsException("Account frozen");
        // Now correctly rolls back the debit
    }
    accountRepo.credit(to, amount);
}

// BETTER: Use unchecked exceptions for business failures:
public class InsufficientFundsException extends RuntimeException {
    // Now @Transactional rolls back by default without rollbackFor
}
```

### Scenario 5: Large Request Body Causing OOM

**Symptom**: `OutOfMemoryError: Java heap space` when a client sends a multi-megabyte JSON body. The entire body is read into a `byte[]` and then deserialized into Java objects.

```java
// Fix 1: Limit request body size
// application.properties:
// spring.servlet.multipart.max-file-size=1MB
// spring.servlet.multipart.max-request-size=1MB

// Fix 2: Server-level limit
@Bean
public TomcatServletWebServerFactory tomcatFactory() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    factory.addConnectorCustomizers(connector -> {
        connector.setMaxPostSize(1048576);  // 1MB
    });
    return factory;
}

// Fix 3: Controller-level validation
@PostMapping("/orders")
public ResponseEntity<?> createOrder(
        @Valid @RequestBody @SizeLimit(max = "1MB") PlaceOrderRequest body) {
    // Custom @SizeLimit validated in a HandlerMethodArgumentResolver
}

// Fix 4: Stream processing for large payloads
@PostMapping("/orders/bulk")
public ResponseEntity<?> bulkImport(InputStream body) {
    // Process as a stream, never materialize the full body in memory
    bulkOrderService.processStream(body);
}
```

## 9. Debugging Techniques

### Tracing Handler Method Resolution

```java
// Enable DEBUG/TRACE logging:
// logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping=TRACE
// logging.level.org.springframework.web.servlet.DispatcherServlet=DEBUG

// Output:
// Mapped "{[/api/orders],methods=[POST]}" onto 
//   public org.springframework.http.ResponseEntity<?> 
//   com.example.OrderController.placeOrder(PlaceOrderRequest,User)
// Mapped "{[/api/orders/{id}],methods=[GET]}" onto 
//   public com.example.OrderResponse com.example.OrderController.getOrder(Long)

// Custom inspector bean to verify all mappings at startup:
@Component
public class MappingInspector {
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @EventListener(ApplicationReadyEvent.class)
    public void inspectMappings() {
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            System.out.printf("%-7s %-30s -> %s.%s(%d params)%n",
                    info.getMethodsCondition(),
                    info.getPatternsCondition(),
                    method.getBeanType().getSimpleName(),
                    method.getMethod().getName(),
                    method.getMethodParameters().length);
        });
    }
}
```

### Debugging Argument Resolution Failures

```java
// When you get: "No suitable resolver for argument [0] [type=...]"
// Check which resolvers were consulted:

// 1. Set a breakpoint in InvocableHandlerMethod.getMethodArgumentValues()
// 2. Step through to see which resolver handles each parameter
// 3. Inspect this.resolvers (the resolver list) to see the order

// Common cause: Missing @RequestBody on a complex type parameter
//   Wrong:  public void handle(OrderRequest request)
//   Right:  public void handle(@RequestBody OrderRequest request)

// Common cause: Missing @PathVariable on a method parameter with different name
//   Wrong:  @GetMapping("/orders/{id}") public void get(Long orderId)
//   Right:  @GetMapping("/orders/{id}") public void get(@PathVariable Long id)
//   Also:   @GetMapping("/orders/{orderId}") public void get(@PathVariable Long orderId)
```

### Visualizing Transaction Boundaries

```java
// Use TransactionSynchronizationManager to log transaction boundaries:

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class TransactionLoggingInterceptor implements MethodInterceptor {
    
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        TransactionStatus status = TransactionAspectSupport.currentTransactionStatus();
        
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        boolean isNew = status != null && status.isNewTransaction();
        
        if (isNew) {
            System.out.printf(">>> BEGIN TX: %s | isolation=%s | readOnly=%s%n",
                    txName,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        } else if (status != null) {
            System.out.printf("    JOIN TX: %s (propagation)%n", txName);
        }
        
        try {
            Object result = invocation.proceed();
            if (isNew) {
                System.out.printf("<<< COMMIT TX: %s%n", txName);
            }
            return result;
        } catch (Exception e) {
            if (isNew) {
                System.out.printf("<<< ROLLBACK TX: %s%n", txName);
            }
            throw e;
        }
    }
}
```

## 10. Observability Considerations

### Key Metrics for Controller and Service Layers

```java
// 1. Endpoint-level latency with percentiles:
@Component
public class EndpointMetricsInterceptor implements HandlerInterceptor {
    private final MeterRegistry registry;
    
    @Override
    public boolean preHandle(HttpServletRequest request,
            HttpServletResponse response, Object handler) {
        request.setAttribute("startNanos", System.nanoTime());
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request,
            HttpServletResponse response, Object handler, Exception ex) {
        Long start = (Long) request.getAttribute("startNanos");
        if (start == null) return;
        
        long duration = System.nanoTime() - start;
        String uri = getUriPattern(request);
        String method = request.getMethod();
        String outcome = ex != null ? "ERROR" : 
                String.valueOf(response.getStatus() / 100) + "XX";
        
        Timer.builder("http.server.requests")
                .tag("uri", uri)
                .tag("method", method)
                .tag("outcome", outcome)
                .tag("status", String.valueOf(response.getStatus()))
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration, TimeUnit.NANOSECONDS);
    }
    
    private String getUriPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : "UNKNOWN";
    }
}

// 2. Service-layer method timing (use Micrometer @Timed):
@Service
public class OrderApplicationService {
    
    @Timed(value = "service.order.place", 
           percentiles = {0.5, 0.95, 0.99},
           histogram = true)
    @Transactional
    public PlaceOrderResult placeOrder(PlaceOrderCommand cmd) {
        // ... business logic
    }
}
```

### Consistent Error Response Format Monitoring

```java
// All error responses should have a consistent structure:
// {
//   "error": {
//     "code": "ORDER_NOT_FOUND",
//     "message": "Order with id 99999 was not found",
//     "timestamp": "2025-01-15T10:30:00Z",
//     "traceId": "abc-123-def",
//     "details": [...]  // Optional: field-level errors
//   }
// }

// Monitor error response consistency:
@ControllerAdvice
public class GlobalExceptionHandler {
    
    private final MeterRegistry meterRegistry;
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handle(Exception ex,
            HttpServletRequest request) {
        // Record that an unhandled exception leaked to the global handler
        meterRegistry.counter("http.error.unhandled",
                "exception", ex.getClass().getSimpleName(),
                "uri", request.getRequestURI())
                .increment();
        
        ErrorResponse error = new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                Instant.now(),
                MDC.get("traceId"));
        return ResponseEntity.status(500).body(error);
    }
}
```

### Transaction Metrics

```java
// Monitor transaction behavior via Spring's TransactionSynchronization:
@Component
public class TransactionMetrics {
    private final MeterRegistry registry;
    
    public void registerTransactionMetrics() {
        TransactionSynchronizationManager
                .registerSynchronization(new TransactionSynchronization() {
            private long startTime;
            
            @Override
            public void beforeCommit(boolean readOnly) {
                startTime = System.nanoTime();
            }
            
            @Override
            public void afterCommit() {
                if (!TransactionSynchronizationManager
                        .isCurrentTransactionReadOnly()) {
                    registry.timer("transaction.commit.duration")
                            .record(System.nanoTime() - startTime,
                                    TimeUnit.NANOSECONDS);
                }
            }
            
            @Override
            public void afterCompletion(int status) {
                registry.counter("transaction.status",
                        "status", status == STATUS_COMMITTED 
                                ? "COMMITTED" : "ROLLED_BACK")
                        .increment();
            }
        });
    }
}
```

## 11. Performance Implications

### HandlerMapping Lookup Performance at Scale

```
  +------------------------------------------------------------------+
  |              HANDLER MAPPING LOOKUP COST ANALYSIS                 |
  |                                                                  |
  |  Pattern count | Lookup strategy           | Typical time        |
  |  --------------+---------------------------+-------------------- |
  |  <50           | Default pathLookup + sort | <0.1ms             |
  |  500           | Default pathLookup + sort | ~0.5ms             |
  |  5000          | Default pathLookup + sort | ~3-5ms (concerning)|
  |  10000+        | Custom HandlerMapping      | needed             |
  |                                                                  |
  |  Optimization strategies:                                        |
  |  * Use exact paths for hot endpoints (no variables)              |
  |  * Custom HandlerMapping for high-cardinality lookups            |
  |  * Pre-compute the mapping if it's dynamic                       |
  +------------------------------------------------------------------+

// Custom high-performance HandlerMapping for exact path lookup:
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FastPathHandlerMapping implements HandlerMapping {
    private final Map<String, HandlerExecutionChain> fastPaths 
            = new ConcurrentHashMap<>();
    
    @PostConstruct
    void init() {
        fastPaths.put("/api/health", new HandlerExecutionChain(
                (HttpRequestHandler) (req, res) -> {
                    res.setStatus(200);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"status\":\"UP\"}");
                }));
    }
    
    @Override
    public HandlerExecutionChain getHandler(HttpServletRequest request) {
        return fastPaths.get(request.getRequestURI());
        // O(1) lookup for hot paths. Falls through to 
        // RequestMappingHandlerMapping if null.
    }
}
```

### Jackson Serialization Overhead

```
  +------------------------------------------------------------------+
  |         @ResponseBody SERIALIZATION COST BREAKDOWN                |
  |                                                                  |
  |  Payload: Order with 20 line items (5KB JSON)                     |
  |                                                                  |
  |  Controller method call:          ~0.3ms                          |
  |  Return value handler dispatch:    ~0.05ms                        |
  |  Content negotiation:             ~0.1ms                          |
  |  Jackson serialization:           ~1.2ms                          |
  |  Response write to socket:        ~0.2ms                          |
  |  TOTAL overhead:                  ~1.55ms                         |
  |                                                                  |
  |  Optimization:                                                   |
  |  * @JsonView to exclude unused fields -> 30-50% less time        |
  |  * @JsonInclude(NON_NULL) to skip nulls                          |
  |  * Pre-serialize hot responses -> response caching               |
  |  * Use @JsonUnwrapped sparingly (allocates intermediate maps)    |
  |  * For collections: use arrays not unwrapped List<Object>        |
  +------------------------------------------------------------------+
```

### @Transactional(readOnly=true) Performance Impact

```
  +------------------------------------------------------------------+
  |            READ-ONLY TRANSACTION PERFORMANCE BENEFITS             |
  |                                                                  |
  |  Hibernate:                                                      |
  |    * No dirty checking (doesn't snapshot entity state)           |
  |    * No automatic flush before queries                          |
  |    * ~10-15% throughput improvement on read-heavy endpoints     |
  |                                                                  |
  |  Database (PostgreSQL/MySQL):                                    |
  |    * PostgreSQL: can skip acquiring row-level locks              |
  |    * MySQL: reduces undo log overhead                           |
  |    * Connection: shared across read operations without conflict |
  |                                                                  |
  |  RULE: Every @GetMapping should flow through a                   |
  |        @Transactional(readOnly=true) service method.             |
  +------------------------------------------------------------------+
```

## 12. Architecture Implications

### When to Use CQRS-Style Command/Query Separation at the Service Layer

```
  +------------------------------------------------------------------+
  |            COMMAND vs QUERY SERVICE SEPARATION                     |
  |                                                                  |
  |  Simple CRUD (keep together):                                    |
  |    OrderService { create(), findById(), findAll(), delete() }    |
  |    Use when: single model, simple queries, low read volume       |
  |                                                                  |
  |  CQRS at service layer (separate):                                |
  |    OrderCommandService { place(), cancel(), update() }           |
  |    OrderQueryService { findById(), findByStatus(), search() }    |
  |    Use when:                                                     |
  |      * Read and write models differ significantly               |
  |      * Read volume >> write volume (separate optimization)       |
  |      * Different consistency requirements (commands = strong,    |
  |        queries = eventual)                                       |
  |      * Different storage (commands -> normalized,               |
  |        queries -> materialized views)                            |
  |                                                                  |
  |  Benefits:                                                       |
  |    * Commands can be @Transactional, queries @Transactional(      |
  |      readOnly=true)                                              |
  |    * Independent caching strategies                              |
  |    * Independent scaling and monitoring                          |
  +------------------------------------------------------------------+
```

### How the Controller Layer Constrains Your Domain Model

```
  +------------------------------------------------------------------+
  |         CONTROLLER -> DOMAIN MODEL IMPEDANCE MISMATCH             |
  |                                                                  |
  |  PROBLEM: Controllers shape how you design domain objects.       |
  |  If every controller returns a JPA entity directly, you:          |
  |    * Can't evolve the domain model without breaking the API      |
  |    * Leak database structure to clients (columns, relationships) |
  |    * Risk N+1 queries from Jackson serialization                 |
  |    * Can't add computed fields or aggregate multiple entities     |
  |                                                                  |
  |  SOLUTION: Response DTOs as an API contract.                      |
  |                                                                  |
  |  API Layer:        OrderResponse { id, status, items[], total }  |
  |  Service Layer:    OrderResult { id, status, total }  (internal) |
  |  Domain Layer:     Order { id, status, items, shippingAddress }  |
  |  Persistence:      OrderEntity { id, status, ... } (JPA mapped) |
  |                                                                  |
  |  Each layer owns its own model. Controllers NEVER return entities.|
  +------------------------------------------------------------------+
```

### Service Layer as Transaction Boundary: Why It Matters

```
  +------------------------------------------------------------------+
  |                  TRANSACTION BOUNDARY DESIGN                      |
  |                                                                  |
  |  CORRECT: Transaction at Application Service                     |
  |                                                                  |
  |  Controller (no @Transactional)                                  |
  |    -> Service.placeOrder(cmd)  // Transaction starts HERE        |
  |       -> validate(cmd)                                           |
  |       -> order = Order.place(cmd)  // Domain logic               |
  |       -> orderRepo.save(order)     // Persistence                |
  |       -> inventoryService.reserve(cmd.items())  // In same TX    |
  |       -> eventPublisher.publish(new OrderPlaced(order))           |
  |    <- // Transaction commits HERE (all or nothing)               |
  |    <- Controller maps result to HTTP response                     |
  |                                                                  |
  |  WRONG: Transaction at Controller                                |
  |                                                                  |
  |  Controller.placeOrder()  // Transaction starts                  |
  |    -> validate request body  // NOT business logic in TX         |
  |    -> Service.placeOrder(cmd)  // Still in same TX               |
  |       -> ... all the business logic ...                          |
  |    -> Map result to JSON    // STILL IN TX! Jackson serialization |
  |    <- Transaction commits HERE (way too late)                    |
  |                                                                  |
  |  WHY IT MATTERS:                                                 |
  |  * Transaction holds database connection. Controller processing  |
  |    (JSON serialization, response building) should NOT hold it.   |
  |  * Transaction time = lock time. Minimize it.                    |
  |  * Controller-level transaction makes it impossible to call      |
  |    the service from a batch job without HTTP overhead.           |
  +------------------------------------------------------------------+
```

## 13. Team Ownership Implications

### Who Owns What in Controller/Service Architecture

```
  +------------------------------------------------------------------+
  |                     OWNERSHIP MATRIX                               |
  |                                                                  |
  |  Platform Team Owns:                                             |
  |  +-- Base exception handlers (@ControllerAdvice)                 |
  |  +-- Standard error response format                              |
  |  +-- Request/response logging filter                             |
  |  +-- Authentication and authorization filters/interceptors       |
  |  +-- Rate limiting filter                                        |
  |  +-- Request ID / tracing filter                                 |
  |  +-- Custom argument resolvers (@CurrentUser, @RequestId)        |
  |  +-- Content negotiation defaults                                |
  |  +-- Validation error response format                            |
  |  +-- CORS configuration                                          |
  |                                                                  |
  |  Service Team Owns:                                              |
  |  +-- Controller classes and their @RequestMapping definitions    |
  |  +-- Request/Response DTOs for each endpoint                     |
  |  +-- Application Service implementations                         |
  |  +-- Domain Service implementations                              |
  |  +-- Business exception classes and their @ExceptionHandler       |
  |  +-- Transaction boundaries (which service methods are           |
  |      @Transactional and at what propagation level)               |
  |  +-- Service-to-service orchestration (sagas, process managers)  |
  |                                                                  |
  |  Architecture/Lead Owns:                                         |
  |  +-- Architectural rules (ArchUnit tests enforcing thin          |
  |      controllers, service-layer-only transactions, DTO usage)    |
  |  +-- Code review checklist for controller/service separation     |
  |  +-- Decision framework: Application vs Domain Service split     |
  +------------------------------------------------------------------+
```

### Architecture Rules via ArchUnit

```java
// Enforce controller-service separation at build time:
@Test
public void controllers_should_not_access_repositories_directly() {
    noClasses()
        .that().areAnnotatedWith(RestController.class)
        .should().dependOnClassesThat()
        .areAnnotatedWith(Repository.class)
        .check(classes);
}

@Test
public void controllers_should_not_be_transactional() {
    noClasses()
        .that().areAnnotatedWith(RestController.class)
        .should().beAnnotatedWith(Transactional.class)
        .check(classes);
}

@Test
public void services_should_not_depend_on_spring_http() {
    noClasses()
        .that().resideInAPackage("..service..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.http..",
            "org.springframework.web..",
            "jakarta.servlet..")
        .check(classes);
}

@Test
public void services_with_public_methods_should_be_transactional() {
    classes()
        .that().resideInAPackage("..service..")
        .and().areAnnotatedWith(Service.class)
        .should(containTransactionalPublicMethods())
        .check(classes);
}
```

## 14. Interview Questions

### Question 1: "Explain why @Transactional should be placed on the service layer, not the controller. What are the concrete consequences of putting it on the controller?"

**Staff-level answer**: The `@Transactional` annotation defines a transaction boundary. Its placement determines WHEN the database transaction begins and ends relative to the application's processing. Placing it on the controller layer creates four concrete problems.

First, transaction duration is inflated. A transaction on the controller encompasses HTTP deserialization, request validation, service logic, and response serialization. Jackson serialization can be surprisingly expensive — for large object graphs with lazy-loaded collections, serialization can iterate hundreds of related objects. During this entire period, the database connection is held, and in many databases, locks are held. This directly increases contention on database rows and can cause deadlocks under concurrent load that would not occur if transactions were shorter.

Second, the controller layer introduces a coupling problem. A service method annotated with `@Transactional` at the controller level cannot be safely called from a non-HTTP entry point (a scheduled job, a message queue listener, a CLI command) without also ensuring that caller opens a transaction. The service becomes dependent on its caller for transactional integrity, violating the principle that a service should guarantee its own consistency.

Third, there is a semantic mismatch with HTTP error handling. In a well-designed system, the controller catches business exceptions and maps them to HTTP status codes. If `@Transactional` is on the controller, the business exception is thrown WITHIN the transaction, which causes a rollback. This seems correct, but consider: the controller's `@ExceptionHandler` now runs OUTSIDE the transaction (the transaction was already rolled back). If the exception handler needs to read data to construct an error response, it operates in a new, implicit transaction or no transaction at all. Any lazy-loaded associations accessed in the error handler will throw `LazyInitializationException`.

Fourth, it prevents transaction composition. Application services often need to call multiple sub-services within a single transaction (e.g., `placeOrder` calls `reserveInventory` and `authorizePayment`). With `@Transactional` at the controller, the entire request is one transaction — there is no way to have the payment authorization run in its own transaction with `REQUIRES_NEW` so that a payment failure doesn't roll back the entire order creation. Transaction propagation levels become useless when the outermost boundary is the HTTP layer.

The correct pattern: `@Transactional` at the application service method that represents the use case boundary. The controller extracts HTTP data, calls the service, and maps the result. The service owns the transaction and guarantees atomicity of the business operation it represents.

### Question 2: "What is the difference between an Application Service and a Domain Service? Provide clear criteria for when to use each."

**Staff-level answer**: The distinction is about *what* they coordinate versus *how* they compute. An Application Service orchestrates a use case; a Domain Service encapsulates pure domain logic that doesn't naturally belong to any single entity.

An **Application Service** answers the question "What should happen when the user places an order?" It sequences the steps: validate the customer, check inventory, calculate pricing, authorize payment, create the order record, and send a confirmation. It coordinates multiple domain objects, repositories, domain services, and external gateways. It has no domain logic of its own — it delegates every decision to domain objects or domain services. Its signature typically accepts a command object or DTO, returns a result object or DTO, and is annotated with `@Transactional`. It is named after the use case: `PlaceOrderService`, `CancelSubscriptionService`, `ApproveLoanService`.

A **Domain Service** answers the question "How do we calculate shipping cost for an international order with weight and dimensional weight?" It encapsulates algorithms, calculations, and rules that span multiple entities or are stateless operations. For example, `PricingService.calculateTotal(List<LineItem> items, CustomerTier tier)` applies discount rules, tax calculations, and roundings. This logic belongs neither to `Order` (which shouldn't know about tax tables) nor to `LineItem` (which shouldn't know about customer tiers). Domain services are stateless (no instance variables beyond injected dependencies), operate on domain objects and value objects, and never open transactions themselves — the calling Application Service manages the transaction. They are named after the domain concept they compute: `PricingService`, `ShippingCalculator`, `CreditLimitValidator`.

The decision framework:
- If the logic assigns or changes state of a SINGLE entity: put it on the entity (e.g., `Order.cancel()`)
- If the logic spans MULTIPLE entities or external systems: put it in an Application Service (e.g., `OrderPlacementService.place()`)
- If the logic is a stateless calculation or rule that operates on domain objects but doesn't orchestrate: put it in a Domain Service (e.g., `FraudScoringService.score(order, customer)`)
- If the logic requires transactional consistency: it's an Application Service boundary
- If the logic is reusable across multiple use cases: consider a Domain Service

A common mistake is putting everything in a single `OrderService` that mixes orchestration (calling payment gateway, sending emails) with domain logic (calculating discounts, validating business rules). This creates a 1000-line class that's hard to test and impossible to reuse. The split: `OrderPlacementService` (application, orchestrates), `OrderPricingService` (domain, calculates), `OrderValidator` (domain, validates).

### Question 3: "How does RequestMappingHandlerMapping build its mapping registry at startup, and how does it resolve the correct handler at runtime when two patterns can match the same URL? What happens with ambiguous mappings?"

**Staff-level answer**: `RequestMappingHandlerMapping` builds its mapping registry during the `InitializingBean.afterPropertiesSet()` phase of the ApplicationContext refresh lifecycle — well before any HTTP requests arrive. The process is:

At startup, `initHandlerMethods()` iterates every bean in the ApplicationContext, calling `isHandler(beanType)` which checks for the presence of `@Controller` or `@RequestMapping` at the class level. For each controller bean, `detectHandlerMethods()` uses `MethodIntrospector.selectMethods()` to introspect every method, calling `getMappingForMethod()` on each. This method reads `@RequestMapping` (and its composed annotations `@GetMapping`, `@PostMapping`, etc.) from both the type level and method level. For `@RestController @RequestMapping("/api/orders")` with `@GetMapping("/{id}")`, it reads type-level `{patterns:["/api/orders"]}` and method-level `{patterns:["/{id}"], methods:[GET]}`, then `combine()` merges them into a single `RequestMappingInfo` with `patterns:["/api/orders/{id}"], methods:[GET]`.

Each `RequestMappingInfo` is registered in the `MappingRegistry` via three data structures:
1. `registry`: A `Map<RequestMappingInfo, HandlerMethod>` — the canonical mapping.
2. `pathLookup`: A `MultiValueMap<String, RequestMappingInfo>` — keyed by the *literal pattern string* (e.g., `/api/orders/{id}`), allowing O(1) lookup by pattern.
3. `nameLookup`: A `Map<String, RequestMappingInfo>` — only for exact paths without wildcards or variables (e.g., `/api/health`).

At runtime, when `DispatcherServlet.getHandler()` fires, `lookupHandlerMethod(lookupPath, request)` executes a three-phase matching algorithm:
1. Check `nameLookup` for an exact match (O(1), only for simple paths).
2. If not found, retrieve all `RequestMappingInfo` entries from `pathLookup` and iterate them, using `AntPathMatcher` to test whether each pattern matches the incoming path. For each matching pattern, further conditions (HTTP method, params, headers, consumes, produces) are checked via `getMatchingCondition()`.
3. Collect all matches and sort by specificity using `RequestMappingInfo.compareTo()`. Specificity is determined by: number of path segments, explicit vs. wildcard matches, number of path variables, number of media type conditions, and presence of params/headers conditions. The most specific match wins.

Ambiguous mappings occur when two `RequestMappingInfo` objects have *identical* specificity scores for the same request. For example, `@GetMapping("/orders/{id}")` and `@GetMapping("/orders/{orderId}")` are ambiguous because both have a single variable segment, no additional conditions, and the same pattern specificity. Spring detects this during the sort: if `comparator.compare(bestMatch, secondBestMatch) == 0`, it throws `IllegalStateException("Ambiguous handler methods mapped...")`. This exception is thrown at *startup time* — during `initHandlerMethods()` — not at runtime, because `validateMethodMapping()` is called during registration. However, certain ambiguities between controllers in different packages or with different conditions may only be detected at runtime.

Importantly, identical paths with different HTTP methods (e.g., `@GetMapping("/orders")` and `@PostMapping("/orders")`) are NOT ambiguous because the `methodsCondition` disambiguates them during `getMatchingCondition()`. Similarly, `produces` conditions (e.g., `produces = APPLICATION_JSON_VALUE` vs `produces = APPLICATION_XML_VALUE`) disambiguate paths with otherwise identical patterns.

## 15. Hands-On Exercises

1. **Refactor a fat controller into thin controller + application service + domain service**:
   Take a 200-line controller method that mixes validation, business logic, repository calls, and external API calls. Extract into: (a) a thin controller (~10 lines) that converts HTTP to DTOs, calls a service, and converts the result to HTTP; (b) an application service that orchestrates the use case and owns the transaction; (c) domain services for any stateless business calculations. Verify the refactoring by running the existing integration tests — they should pass without modification.

2. **Implement a custom HandlerMethodArgumentResolver for request-scoped data**:
   Create a `@RequestTrace` annotation and a resolver that injects a `RequestTrace` object containing `traceId`, `userId`, and `requestTimestamp` into controller methods. Extract `traceId` from the `X-Trace-Id` request header or generate a new UUID. Extract `userId` from `SecurityContextHolder`. Register the resolver at highest priority via `WebMvcConfigurer.addArgumentResolvers()`. Write a test that verifies the resolver works and another that verifies it returns a meaningful error when no auth context is present.

3. **Build a rate-limiting filter and apply it via a custom @RateLimit annotation**:
   Create a `@RateLimit(maxRequests = 100, windowSeconds = 60)` annotation. Implement a `HandlerInterceptor` that reads this annotation from the handler method, uses a `ConcurrentHashMap<String, TokenBucket>` keyed by client identifier (IP or user ID), and rejects requests that exceed the limit with HTTP 429. Expose rate limit metrics via Micrometer (remaining tokens gauge, rejected counter). Test with concurrent requests using `CountDownLatch`.

4. **Set up ArchUnit rules that enforce controller-service separation**:
   Write ArchUnit tests that enforce: (a) controllers must not directly access repositories, (b) controllers must not have `@Transactional`, (c) service classes must not import Spring HTTP types, (d) every `@RestController` method that accepts a complex type parameter must have `@Valid` or `@Validated`, (e) public service methods should be `@Transactional`. Run these tests as part of the build and fix any violations found.

5. **Trace a single request through the full controller resolution stack with breakpoints**:
   Set breakpoints at: `DispatcherServlet.doDispatch()`, `AbstractHandlerMethodMapping.lookupHandlerMethod()`, `RequestMappingHandlerAdapter.invokeHandlerMethod()`, `InvocableHandlerMethod.getMethodArgumentValues()`, `HttpEntityMethodProcessor.handleReturnValue()`. Send a POST request with a JSON body. At each breakpoint, inspect: the thread name, the resolved handler, the argument values (before and after resolution), the return value, and the response data. Document the full data flow.

6. **Write a comprehensive @ControllerAdvice for consistent error handling**:
   Build a `@ControllerAdvice` that handles: `MethodArgumentNotValidException` (400 with field errors), `BindException` (400), `HttpMessageNotReadableException` (400 with parse error details), `AccessDeniedException` (403), `NoResourceFoundException` (404), `HttpRequestMethodNotSupportedException` (405), `MethodArgumentTypeMismatchException` (400), your business exceptions (mapped to appropriate 4xx/5xx codes), and a catch-all `Exception` handler (500). Each handler must return a consistent `ErrorResponse` JSON structure with `code`, `message`, `timestamp`, and `traceId`. Log the full exception for 5xx errors; log only a summary for 4xx errors. Expose a metric counter for each error type.

## 16. Advanced Challenges

1. **Build a dynamic controller registration system that loads controllers from a database at runtime**:
   Implement a system where business analysts can define new API endpoints via a database table (`dynamic_endpoints`: `path`, `http_method`, `handler_class`, `handler_method`, `request_type`, `response_type`). At startup, read these definitions and use reflection + `HandlerMethod` construction to register them in `RequestMappingHandlerMapping`'s `MappingRegistry` (via reflection, since the `register` methods on `MappingRegistry` are private). Support hot reload: listen for database changes (via a polling scheduler or CDC) and dynamically add/remove mappings from the registry without restarting. Handle: race conditions during registration, validation of the dynamic endpoints, security (who can call what), and observability (metrics per dynamic endpoint).

2. **Implement a saga orchestration framework using Application Services as steps**:
   Build a `SagaOrchestrator` that accepts a list of `SagaStep` definitions (each wrapping an Application Service call + a compensation action). The orchestrator executes steps sequentially, and on failure of any step, executes all previous steps' compensations in reverse order. Each step can run in its own transaction (`REQUIRES_NEW`). Support: parallel step execution where possible, timeout per step, retry with backoff, idempotency (each step receives a saga ID to deduplicate), and saga state persistence (store current step + progress in the database so the saga can resume after a crash). Test with a real multi-service orchestration (Order -> Payment -> Inventory -> Shipment) and verify that any failure triggers complete compensation.

3. **Design and implement a process manager for a long-running workflow**:
   Unlike a saga (which compensates on failure), a process manager maintains state across multiple transactions and waits for external events. Build one for an order fulfillment workflow: `DRAFT -> PAYMENT_PENDING -> PAYMENT_CONFIRMED -> INVENTORY_RESERVED -> SHIPPED -> DELIVERED`. Each state transition is triggered by an event (payment confirmed, inventory reserved, tracking number assigned). The process manager persists current state in a database, listens for events via `@TransactionalEventListener` or a message queue, and transitions state by invoking the appropriate Application Service. Handle: duplicate events (idempotency via event ID), out-of-order events (buffer and reorder), timeout-based transitions (if payment is not confirmed within 30 minutes, transition to `CANCELLED`), and observability (expose current state of every process instance).

4. **Create a Controller Complexity Analyzer that statically grades controller quality**:
   Build a Maven/Gradle plugin or a compile-time annotation processor that analyzes `@RestController` classes and assigns a quality score (A-F). Metrics: (a) number of constructor parameters (more is worse, unless they're all services), (b) average lines per method, (c) presence of `@Transactional` on controller methods (automatic F), (d) use of raw `Map<String,Object>` as request/response types, (e) ratio of framework annotations to business logic lines, (f) number of repository/entity references in controller code, (g) whether responses are DTOs or entities. Generate an HTML report with scores per controller and suggestions for improvement. Integrate with CI to fail builds when a new controller drops below a threshold.

5. **Build a request shadowing/replay system at the controller layer**:
   Implement a `Filter` that, for a configurable percentage of requests (e.g., 5%), creates a copy of the incoming request and replays it against a "shadow" instance of the application (different port, different database). The filter must: (a) copy the full request body (handling large bodies via streaming or truncation), (b) replay the request asynchronously without blocking the production response, (c) compare the production response with the shadow response (JSON diff, status code match), (d) log diffs for analysis, (e) include the same trace ID in both requests for correlation. Handle: shadow service being unavailable (timeout, circuit breaker), potentially mutated request state (ensure the copy is deep), and performance overhead (measure and limit the max shadow rate).
