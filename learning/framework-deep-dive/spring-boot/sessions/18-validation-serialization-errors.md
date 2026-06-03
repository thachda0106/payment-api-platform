# Session 18: Validation, Serialization & Error Handling Architecture

## 1. Why This Topic Exists

Every HTTP request to a Spring Boot application crosses three distinct boundaries: **deserialization** (bytes to objects), **validation** (objects to trusted domain input), and **execution** (trusted input to business logic). When any boundary fails, the error must be serialized back to the client in a predictable format. These three concerns -- serialization, validation, and error handling -- form the *contract boundary* of your API.

Getting this boundary wrong is not a cosmetic issue. It is a security leak, a correctness risk, and a reliability concern:

- **Deserialization failures that expose stack traces** leak internal class names, package structures, and framework versions to attackers. In 2023, a major fintech company leaked its internal service topology through uncaught `InvalidFormatException` stack traces in 400 responses.
- **Validation architecture that validates in the wrong layer** causes either duplicate validation (performance waste) or missing validation (data corruption). A Staff engineer at a healthcare startup discovered that 30% of "validated" records had invalid state because validation only ran on the controller layer, but batch jobs called the service layer directly.
- **Error handling that returns inconsistent formats** breaks client SDKs, makes alerting impossible, and forces every consumer to write defensive parsing logic. When every microservice returns a different error shape, your on-call team cannot build a unified error dashboard.

**Staff engineer insight**: The serialization/validation/error pipeline is a *cross-cutting concern* that determines whether your API is production-grade or prototype-grade. The difference is not in whether you use `@Valid` -- every tutorial covers that. The difference is in understanding how `HttpMessageConverter` selection determines which parser handles a request, how `ConstraintValidatorFactory` loads custom validators (and why they cannot be `@Autowired` without special configuration), and how `ExceptionHandlerExceptionResolver` picks between six possible handler methods when an exception is thrown. These are the internals that separate the Staff engineer who can debug a mysterious 500 from the Senior engineer who adds yet another `@ExceptionHandler` and hopes it works.

Consider this production timeline:

```
T+0ms    POST /orders  Content-Type: application/json  {"amount": "not-a-number"}
T+3ms    Jackson attempts to deserialize "not-a-number" into BigDecimal field "amount"
T+4ms    InvalidFormatException thrown inside MappingJackson2HttpMessageConverter
T+5ms    HttpMessageNotReadableException wraps it
T+7ms    Spring calls processHandlerException() in DispatcherServlet
T+8ms    ExceptionHandlerExceptionResolver scans @ControllerAdvice beans
T+9ms    Finds matching @ExceptionHandler(HttpMessageNotReadableException.class)
T+10ms   Handler converts to ProblemDetail, serializes to JSON
T+12ms   Client receives: {"type":"about:blank","title":"Bad Request","status":400,"detail":"..."}
```

A Staff engineer can trace every microsecond of that timeline. This session builds that understanding.


## 2. Mental Model

The three-phase request processing pipeline:

```
HTTP REQUEST (bytes on the wire)
    |
    v
+------------------------------------------------------------------+
|                   PHASE 1: DESERIALIZE                            |
|                                                                    |
|  Media Type Negotiation:                                          |
|    Content-Type: application/json  ->  MappingJackson2HttpMessageConverter
|    Content-Type: application/xml   ->  MappingJackson2XmlHttpMessageConverter
|    Content-Type: text/plain        ->  StringHttpMessageConverter
|                                                                    |
|  Converter reads InputStream -> Java object                        |
|  Failure -> HttpMessageNotReadableException                        |
+---------------------------+--------------------------------------+
                            |
                            v
+------------------------------------------------------------------+
|                   PHASE 2: VALIDATE                               |
|                                                                    |
|  @Valid / @Validated triggers:                                    |
|    SpringValidatorAdapter -> Hibernate Validator                   |
|      -> ConstraintTree traversal per object graph                  |
|      -> Per-field ConstraintValidator.isValid()                    |
|                                                                    |
|  Failure -> MethodArgumentNotValidException (controller)           |
|         -> ConstraintViolationException (service layer)            |
+---------------------------+--------------------------------------+
                            |
                            v
+------------------------------------------------------------------+
|                   PHASE 3: EXECUTE                                |
|                                                                    |
|  Controller method invoked with validated @RequestBody            |
|  Business logic runs                                              |
|  Return value serialized by same converter chain                  |
|                                                                    |
|  Failure -> Any RuntimeException                                   |
+---------------------------+--------------------------------------+
                            |
                            v
+------------------------------------------------------------------+
|              ERROR HANDLER LAYER (catches failures from ANY phase) |
|                                                                    |
|  ExceptionHandlerExceptionResolver (highest priority):            |
|    +-- @ExceptionHandler in the controller itself                 |
|    +-- @ExceptionHandler in @ControllerAdvice beans               |
|    +-- ResponseStatusExceptionResolver (for @ResponseStatus)      |
|                                                                    |
|  DefaultHandlerExceptionResolver (lowest priority):               |
|    +-- Translates Spring MVC exceptions to HTTP status codes      |
|                                                                    |
|  If nothing catches -> /error endpoint                             |
|    +-- BasicErrorController -> ErrorAttributes -> JSON/HTML         |
+------------------------------------------------------------------+
```

The critical insight: **validation and handling happen at different times, but errors can come from any phase.** The exception handler layer is the universal backstop --- it must handle `HttpMessageNotReadableException` (Phase 1 failure), `MethodArgumentNotValidException` (Phase 2 failure), and any domain exception (Phase 3 failure). A well-architected error handler knows which phase the error came from and responds with the appropriate HTTP status and error detail level.

### The HttpMessageConverter Selection Algorithm

```
For a given request:
1. Does the handler method have a @RequestBody parameter?
   +-- YES -> look for a converter that supports:
       +-- The parameter's declared Java type (canRead(Class, MediaType))
       +-- The request's Content-Type header (application/json, etc.)
       -> Pick the FIRST matching converter in the registered list
       
2. Does the handler method have a return value that is NOT String/View?
   +-- YES -> look for a converter that supports:
       +-- The return value's Java type (canWrite(Class, MediaType))
       +-- The request's Accept header (application/json, application/xml, */*)
       -> Pick the FIRST matching converter in the registered list
       -> If Accept is */* and multiple converters support the type:
           -> Spring Boot picks application/json (configurable via spring.mvc.contentnegotiation)
```

**The order matters.** Spring Boot registers converters in this order:
```
1.  ByteArrayHttpMessageConverter        (application/octet-stream)
2.  StringHttpMessageConverter           (text/plain, */*)
3.  ResourceHttpMessageConverter         (application/octet-stream)
4.  ResourceRegionHttpMessageConverter   (application/octet-stream)
5.  SourceHttpMessageConverter           (application/xml, text/xml)
6.  AllEncompassingFormHttpMessageConverter (application/x-www-form-urlencoded)
7.  MappingJackson2HttpMessageConverter   (application/json, application/*+json)
8.  MappingJackson2XmlHttpMessageConverter (application/xml, text/xml) -- if jackson-dataformat-xml present
9.  Jaxb2RootElementHttpMessageConverter (application/xml) -- if JAXB present
```

If a converter earlier in the list can handle both the type AND the content type, it wins. This is why `StringHttpMessageConverter` is problematic at position 2 --- it accepts `*/*` and can write any `String` return value. If your controller returns a `String` that happens to be JSON, Spring might bypass Jackson entirely and send it as `text/plain`.

## 3. Internal Architecture

### How @Valid Triggers Validation in Controller Methods

```java
// Source: org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor
// Located in: spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/

// This is the HandlerMethodArgumentResolver for @RequestBody parameters.
// It handles BOTH deserialization AND validation in sequence.

public class RequestResponseBodyMethodProcessor extends AbstractMessageConverterMethodProcessor {

    @Override
    public Object resolveArgument(MethodParameter parameter, 
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, 
            WebDataBinderFactory binderFactory) throws Exception {

        // --- STEP 1: Read and deserialize the request body ---
        Object arg = readWithMessageConverters(webRequest, parameter, 
            parameter.getNestedGenericParameterType());
        // Calls AbstractMessageConverterMethodArgumentResolver.readWithMessageConverters()
        // -> Iterates HttpMessageConverter list
        // -> Calls converter.canRead() for each
        // -> First match: converter.read() -> returns Java object

        // --- STEP 2: Get the parameter name for error messages ---
        String name = Conventions.getVariableNameForParameter(parameter);

        // --- STEP 3: Check if validation is needed ---
        // Validation is triggered if:
        //   a. @Valid is present on the parameter (jakarta.validation.Valid)
        //   b. @Validated is present on the parameter (org.springframework.validation.annotation.Validated)
        //   c. The parameter type has method-level validation annotations
        if (parameter.hasParameterAnnotation(Validated.class)) {
            // @Validated: Spring's own annotation, supports validation groups
            binderFactory.createBinder(webRequest, arg, name)
                .validate(getValidationGroups(parameter));
        } else if (parameter.hasParameterAnnotation(Valid.class)) {
            // @Valid: Jakarta Bean Validation annotation, no group support
            // Uses WebDataBinder with SpringValidatorAdapter internally
            binderFactory.createBinder(webRequest, arg, name).validate();
        }

        // --- STEP 4: Check for binding/validation errors ---
        // The binder stored errors in its BindingResult
        // The superclass method validates and checks BindingResult.hasErrors()

        return arg;
    }
}
```

The chain that connects `@Valid` to Hibernate Validator:

```java
// 1. WebDataBinder.validate() is called
//    Source: org.springframework.validation.DataBinder

public void validate() {
    Validator validator = getValidator();
    if (validator != null) {
        validator.validate(getTarget(), getBindingResult());
        // -> Calls SpringValidatorAdapter.validate(Object, Errors)
    }
}

// 2. SpringValidatorAdapter bridges Spring's Validator to Jakarta Bean Validation
//    Source: org.springframework.validation.beanvalidation.SpringValidatorAdapter
//    This class implements BOTH:
//      - org.springframework.validation.Validator (Spring's interface)
//      - jakarta.validation.Validator (Jakarta's interface)

public class SpringValidatorAdapter implements SmartValidator, jakarta.validation.Validator {

    private final jakarta.validation.Validator targetValidator;

    @Override
    public void validate(Object target, Errors errors) {
        Set<ConstraintViolation<Object>> violations = this.targetValidator.validate(target);
        for (ConstraintViolation<Object> violation : violations) {
            String field = violation.getPropertyPath().toString();
            String errorCode = violation.getConstraintDescriptor()
                .getAnnotation().annotationType().getSimpleName();
            Object[] errorArgs = getArgumentsForConstraint(errors.getObjectName(), field, violation);
            errors.rejectValue(field, errorCode, errorArgs, violation.getMessage());
        }
    }
}
```

### Hibernate Validator Internals: ConstraintTree and ValidationOrder

```java
// Source: org.hibernate.validator.internal.engine.ValidatorImpl

public class ValidatorImpl implements jakarta.validation.Validator, ExecutableValidator {

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
        // 1. Build validation order -- determines which validators run in what sequence
        ValidationOrder validationOrder = determineValidationOrder(groups);
        
        // 2. Create a BeanMetaDataManager that can get meta-data for all bean types
        BeanMetaDataManager beanMetaDataManager = ...;
        
        // 3. Validate against the object graph
        return validateInContext(
            validationContext.forValidate(object),
            validationOrder,
            beanMetaDataManager
        );
    }

    // The actual constraint traversal:
    private <T> Set<ConstraintViolation<T>> validateConstraintsForCurrentGroup(
            ValidationContext<T> validationContext,
            BeanMetaDataManager beanMetaDataManager) {
        
        // 1. Get the BeanMetaData for the object type (cached per class)
        BeanMetaData<T> beanMetaData = beanMetaDataManager.getBeanMetaData(
            (Class<T>) validationContext.getRootBeanClass());
        
        // 2. For each constraint in the meta-data:
        for (MetaConstraint<T, ?> constraint : beanMetaData.getMetaConstraints()) {
            // constraint wraps: the annotation, the ConstraintValidator instance, the property path
            
            // 3. Execute the validator
            boolean isValid = validateConstraint(validationContext, constraint);
            
            if (!isValid) {
                // 4. Interpolate the message (using MessageInterpolator)
                String message = interpolateMessage(constraint, validationContext);
                
                // 5. Build ConstraintViolation
                ConstraintViolation<T> violation = buildConstraintViolation(
                    validationContext, constraint, message);
                violations.add(violation);
            }
        }
        
        // 6. Recurse into @Valid-annotated properties (cascaded validation)
        for (Cascadable cascadable : beanMetaData.getCascadedConstraints()) {
            Object value = cascadable.getValue(validationContext.getRootBean());
            if (value != null) {
                violations.addAll(
                    validateInContext(validationContext, value, ...)
                );
            }
        }
        
        return violations;
    }
}
```

The `BeanMetaData` structure is key to understanding performance:

```
BeanMetaData for OrderRequest:
+-- class: OrderRequest
+-- metaConstraints: List<MetaConstraint>
|   +-- MetaConstraint { annotation=@NotNull, validator=NotNullValidator, propertyPath="customerId" }
|   +-- MetaConstraint { annotation=@NotBlank, validator=NotBlankValidator, propertyPath="customerName" }
|   +-- MetaConstraint { annotation=@Positive, validator=PositiveValidator, propertyPath="amount" }
|   +-- MetaConstraint { annotation=@Valid, validator=NULL, propertyPath="shippingAddress" }
|       +-- -> Cascadable (triggers recursive validation of Address class)
+-- cascadedConstraints: List<Cascadable>
|   +-- Cascadable { elementType=Address.class, propertyPath="shippingAddress" }
+-- stored in static final ConcurrentHashMap<Class<?>, BeanMetaData<?>>
    -> Built ONCE per class, reused across all validation calls
    -> The ConstraintValidator instances themselves are cached per constraint type
```

### ConstraintValidatorFactory and Custom Validator Discovery

```java
// Source: org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorManager

public class ConstraintValidatorManager {

    // Cache: constraint annotation type -> initialized ConstraintValidator instance
    private final ConcurrentMap<CacheKey, ConstraintValidator<?, ?>> constraintValidatorCache 
        = new ConcurrentHashMap<>();

    public <A extends Annotation, T> ConstraintValidator<A, T> getInitializedValidator(
            Class<T> validatedType,
            ConstraintDescriptorImpl<A> descriptor,
            ConstraintValidatorFactory factory) {
        
        // 1. Create a cache key based on the validator class and validated type
        CacheKey key = new CacheKey(descriptor.getConstraintValidatorClasses(), validatedType);
        
        // 2. Check cache first
        ConstraintValidator<?, ?> cached = constraintValidatorCache.get(key);
        if (cached != null) {
            return (ConstraintValidator<A, T>) cached;
        }
        
        // 3. Create new validator instance via the factory
        ConstraintValidator<A, T> validator = factory.getInstance(
            (Class<ConstraintValidator<A, T>>) descriptor.getConstraintValidatorClasses().get(0));
        
        // 4. Initialize it with the annotation attributes
        validator.initialize(descriptor.getAnnotation());
        // initialize() is called ONCE and isValid() is called MANY times.
        // Heavy setup work (e.g., loading reference data) should go in initialize().
        
        // 5. Cache the initialized instance
        constraintValidatorCache.put(key, validator);
        return validator;
    }
}
```

**Critical production insight**: The default `ConstraintValidatorFactoryImpl` creates validators with `newInstance()`. This means your custom `ConstraintValidator` is NOT a Spring bean -- `@Autowired` fields will be `null`. To inject Spring beans into custom validators, you must configure `LocalValidatorFactoryBean` to use a Spring-aware factory:

```java
@Bean
public LocalValidatorFactoryBean validator(AutowireCapableBeanFactory beanFactory) {
    LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean();
    factoryBean.setConstraintValidatorFactory(
        new SpringConstraintValidatorFactory(beanFactory));
    // SpringConstraintValidatorFactory uses AutowireCapableBeanFactory.createBean()
    // to create validator instances -> @Autowired fields WILL be injected
    return factoryBean;
}
```

### Jackson HttpMessageConverter: The Deserialization Pipeline

```java
// Source: org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter

public abstract class AbstractJackson2HttpMessageConverter 
        extends AbstractGenericHttpMessageConverter<Object> {

    protected ObjectMapper objectMapper;  // Configured by JacksonAutoConfiguration

    @Override
    public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        
        JavaType javaType = getJavaType(type, contextClass);
        return readJavaType(javaType, inputMessage);
    }

    private Object readJavaType(JavaType javaType, HttpInputMessage inputMessage) {
        try {
            InputStream body = inputMessage.getBody();
            // --- The actual Jackson deserialization ---
            // JsonParser -> JsonToken stream -> Deserializer -> Java object
            return this.objectMapper.readValue(body, javaType);
            
        } catch (InvalidFormatException ex) {
            // "not-a-number" for a BigDecimal field, wrong date format, etc.
            throw new HttpMessageNotReadableException(
                "JSON parse error: " + ex.getOriginalMessage(), ex, inputMessage);
        } catch (UnrecognizedPropertyException ex) {
            // spring.jackson.deserialization.fail-on-unknown-properties=true
            throw new HttpMessageNotReadableException(
                "JSON parse error: Unrecognized field \"" + ex.getPropertyName() + "\"", 
                ex, inputMessage);
        } catch (JsonProcessingException ex) {
            // Malformed JSON: missing quotes, invalid syntax, etc.
            throw new HttpMessageNotReadableException(
                "JSON parse error: " + ex.getOriginalMessage(), ex, inputMessage);
        } catch (Exception ex) {
            // Any other failure during deserialization
            throw new HttpMessageNotReadableException(
                "Could not read JSON: " + ex.getMessage(), ex, inputMessage);
        }
    }
}
```

The `MappingJackson2HttpMessageConverter` (concrete class) extends `AbstractJackson2HttpMessageConverter`:

```java
// Source: org.springframework.http.converter.json.MappingJackson2HttpMessageConverter

public class MappingJackson2HttpMessageConverter 
        extends AbstractJackson2HttpMessageConverter {

    public MappingJackson2HttpMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper, 
            MediaType.APPLICATION_JSON,           // application/json
            new MediaType("application", "*+json")); // application/*+json
    }
    
    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        // Checks:
        // 1. Can the ObjectMapper deserialize this class? (not primitive, not Stream, etc.)
        // 2. Does the media type match application/json or application/*+json?
        return canRead(clazz, null, mediaType);
    }
    
    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return canWrite(clazz, null, mediaType);
    }
}
```

### ObjectMapper Auto-Configuration

```java
// Source: org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration

@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class JacksonAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean
    public ObjectMapper jacksonObjectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
        // The builder applies all customization from:
        // 1. spring.jackson.* properties
        // 2. Any Jackson2ObjectMapperBuilderCustomizer beans
        // 3. Any Module beans (Jackson modules) found in the context
    }

    @Bean
    @ConditionalOnMissingBean
    public Jackson2ObjectMapperBuilder jacksonObjectMapperBuilder(
            List<Jackson2ObjectMapperBuilderCustomizer> customizers) {
        
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        // Apply property-based configuration:
        //   spring.jackson.serialization.* -> SerializationFeature
        //   spring.jackson.deserialization.* -> DeserializationFeature
        //   spring.jackson.mapper.* -> MapperFeature
        //   spring.jackson.date-format -> SimpleDateFormat
        //   spring.jackson.time-zone -> TimeZone
        customize(builder);
        
        for (Jackson2ObjectMapperBuilderCustomizer customizer : customizers) {
            customizer.customize(builder);
        }
        
        return builder;
    }

    // Standard Jackson modules auto-registered:
    @Bean
    @ConditionalOnClass(name = "com.fasterxml.jackson.datatype.jdk8.Jdk8Module")
    public Jdk8Module jdk8Module() {
        return new Jdk8Module();  // Optional, OptionalInt, OptionalLong, OptionalDouble
    }

    @Bean
    @ConditionalOnClass(name = "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule")
    public JavaTimeModule javaTimeModule() {
        return new JavaTimeModule();  // java.time types: LocalDate, Instant, ZonedDateTime
    }

    @Bean
    @ConditionalOnClass(name = "com.fasterxml.jackson.module.paramnames.ParameterNamesModule")
    public ParameterNamesModule parameterNamesModule() {
        return new ParameterNamesModule();  // Use parameter names for constructor deserialization
    }
}
```

### HttpMessageConverter Selection Algorithm (Detailed)

```java
// Source: org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodArgumentResolver

protected <T> Object readWithMessageConverters(
        HttpInputMessage inputMessage, 
        MethodParameter parameter,
        Type targetType) throws IOException, HttpMediaTypeNotSupportedException, 
                                 HttpMessageNotReadableException {

    MediaType contentType = inputMessage.getHeaders().getContentType();
    Class<?> contextClass = parameter.getContainingClass();
    Class<T> targetClass = (targetType instanceof Class ? (Class<T>) targetType : null);

    // --- Step 1: Find matching message converters ---
    for (HttpMessageConverter<?> converter : this.messageConverters) {
        if (converter instanceof GenericHttpMessageConverter) {
            GenericHttpMessageConverter<?> genericConverter = 
                (GenericHttpMessageConverter<?>) converter;
            
            if (genericConverter.canRead(targetType, contextClass, contentType)) {
                if (inputMessage instanceof ServletServerHttpRequest) {
                    // Call RequestBodyAdvice chain (before body read)
                    inputMessage = callBeforeBodyRead(inputMessage, parameter, 
                        targetType, genericConverter);
                }
                
                // --- Step 2: Read! ---
                T body = (T) genericConverter.read(targetType, contextClass, inputMessage);
                
                // Call RequestBodyAdvice chain (after body read)
                body = callAfterBodyRead(body, inputMessage, parameter, targetType, genericConverter);
                
                return body;
            }
        }
    }

    // No converter found
    throw new HttpMediaTypeNotSupportedException(contentType, 
        getSupportedMediaTypes(targetClass));
}
```

### Error Handling: DispatcherServlet.processHandlerException()

```java
// Source: org.springframework.web.servlet.DispatcherServlet

protected void processHandlerException(HttpServletRequest request, 
        HttpServletResponse response, Object handler, Exception ex) 
        throws Exception {

    // --- Step 1: Iterate all registered HandlerExceptionResolvers ---
    ModelAndView mav = null;
    for (HandlerExceptionResolver resolver : this.handlerExceptionResolvers) {
        mav = resolver.resolveException(request, response, handler, ex);
        if (mav != null) {
            break;  // First resolver to handle it wins!
        }
    }

    // --- Step 2: If a resolver produced a ModelAndView ---
    if (mav != null) {
        if (mav.isEmpty()) {
            // Handler already committed the response (wrote directly to response)
            request.setAttribute(EXCEPTION_ATTRIBUTE, ex);
            return;
        }
        
        if (!mav.hasView()) {
            mav.setViewName(getDefaultViewName(request));
        }
        
        processDispatchResult(request, response, handler, mav, ex);
    }
}

// The default HandlerExceptionResolvers (in priority order):
// 1. ExceptionHandlerExceptionResolver    -> @ExceptionHandler methods
// 2. ResponseStatusExceptionResolver      -> @ResponseStatus annotations
// 3. DefaultHandlerExceptionResolver      -> Spring's built-in exception -> status mapping
```

### ExceptionHandlerExceptionResolver Resolution Algorithm

```java
// Source: org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver

public class ExceptionHandlerExceptionResolver 
        extends AbstractHandlerMethodExceptionResolver 
        implements ApplicationContextAware, InitializingBean {

    private final Map<Class<?>, Map<Class<? extends Throwable>, 
        ServletInvocableHandlerMethod>> exceptionHandlerCache = 
        new ConcurrentHashMap<>(64);

    @Override
    protected ModelAndView doResolveHandlerMethodException(
            HttpServletRequest request, HttpServletResponse response,
            HandlerMethod handlerMethod, Exception exception) {

        // --- Step 1: Find the best @ExceptionHandler ---
        ServletInvocableHandlerMethod exceptionHandler = 
            getExceptionHandlerMethod(handlerMethod, exception);

        if (exceptionHandler == null) {
            return null;  // No handler found -> try next resolver
        }

        exceptionHandler.setHandlerMethodArgumentResolvers(getArgumentResolvers());
        exceptionHandler.setHandlerMethodReturnValueHandlers(getReturnValueHandlers());

        return exceptionHandler.invokeAndHandle(request, response, handlerMethod, exception);
    }

    protected ServletInvocableHandlerMethod getExceptionHandlerMethod(
            HandlerMethod handlerMethod, Exception exception) {

        Class<?> handlerType = handlerMethod.getBeanType();
        
        // --- Step 1: Check the controller class itself ---
        // @ExceptionHandler methods defined in the controller have HIGHEST priority
        Map<Class<? extends Throwable>, ServletInvocableHandlerMethod> handlers =
            getExceptionHandlerCache(handlerType);
        
        ServletInvocableHandlerMethod result = 
            findBestExceptionHandler(exception, handlers);
        if (result != null) return result;
        
        // --- Step 2: Check @ControllerAdvice beans ---
        for (Map.Entry<ControllerAdviceBean, ExceptionHandlerMethodResolver> entry : 
                this.exceptionHandlerAdviceCache.entrySet()) {
            
            ControllerAdviceBean advice = entry.getKey();
            if (advice.isApplicableToBeanType(handlerType)) {
                handlers = entry.getValue().getHandlerMethods();
                result = findBestExceptionHandler(exception, handlers);
                if (result != null) return result;
            }
        }
        
        return null;
    }

    // How the "best" handler is selected:
    private ServletInvocableHandlerMethod findBestExceptionHandler(
            Throwable exception, 
            Map<Class<? extends Throwable>, ServletInvocableHandlerMethod> handlers) {

        Class<?> exceptionType = exception.getClass();
        ServletInvocableHandlerMethod handler = null;
        int deepestDepth = Integer.MAX_VALUE;
        
        for (Map.Entry<Class<? extends Throwable>, ServletInvocableHandlerMethod> entry : 
                handlers.entrySet()) {
            
            Class<? extends Throwable> mappedException = entry.getKey();
            
            if (mappedException.isAssignableFrom(exceptionType)) {
                int depth = getDepth(exceptionType, mappedException);
                if (depth < deepestDepth) {
                    // Closer match wins!
                    // RuntimeException handler wins over Exception handler for RuntimeException
                    deepestDepth = depth;
                    handler = entry.getValue();
                }
            }
        }
        
        return handler;
    }
    // Example: If exception is HttpMessageNotReadableException
    // A: @ExceptionHandler(Exception.class)              -> depth 3
    // B: @ExceptionHandler(HttpMessageNotReadableException.class) -> depth 1
    // C: @ExceptionHandler(IOException.class)            -> depth 2
    // -> B wins! (closest inheritance match)
}
```

### BasicErrorController and the /error Endpoint

```java
// Source: org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController
// Auto-configured by ErrorMvcAutoConfiguration
// Registered at path: ${server.error.path:/error}

@RequestMapping("${server.error.path:${error.path:/error}}")
public class BasicErrorController extends AbstractErrorController {

    @RequestMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView errorHtml(HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = getStatus(request);
        Map<String, Object> model = Collections.unmodifiableMap(
            getErrorAttributes(request, getErrorAttributeOptions(request, MediaType.TEXT_HTML)));
        
        response.setStatus(status.value());
        ModelAndView modelAndView = resolveErrorView(request, response, status, model);
        // If no custom error view registered, returns the Whitelabel Error Page
        return (modelAndView != null) ? modelAndView : new ModelAndView("error", model);
    }

    @RequestMapping
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        HttpStatus status = getStatus(request);
        Map<String, Object> body = getErrorAttributes(request, 
            getErrorAttributeOptions(request, MediaType.ALL));
        return new ResponseEntity<>(body, status);
    }
}
```

### ErrorAttributes and ErrorProperties

```java
// Source: org.springframework.boot.web.servlet.error.ErrorAttributes
public interface ErrorAttributes {
    Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options);
    Throwable getError(WebRequest webRequest);
}

// DefaultErrorAttributes populates:
//   "timestamp"   -> Instant.now()
//   "status"      -> response status code
//   "error"       -> status reason phrase (e.g., "Bad Request")
//   "exception"   -> exception class name (ONLY if server.error.include-exception=true)
//   "message"     -> exception message (ONLY if server.error.include-message=always)
//   "trace"       -> stack trace (ONLY if server.error.include-stacktrace=always/on-param)
//   "path"        -> request URI

// ErrorProperties:
// Source: org.springframework.boot.autoconfigure.web.ErrorProperties
@ConfigurationProperties(prefix = "server.error")
public class ErrorProperties {
    private String path = "/error";
    private IncludeAttribute includeException = IncludeAttribute.NEVER;
    private IncludeAttribute includeMessage = IncludeAttribute.NEVER;
    private IncludeStacktrace includeStacktrace = IncludeStacktrace.NEVER;
    
    public enum IncludeAttribute { NEVER, ALWAYS, ON_PARAM }
    public enum IncludeStacktrace { NEVER, ALWAYS, ON_PARAM }
}

// CRITICAL PRODUCTION SETTING:
// server.error.include-stacktrace=never
// server.error.include-message=never
// server.error.include-exception=never
// Leaking stack traces exposes internal class names, framework versions, and DB schema.
```

### RFC 9457 ProblemDetail Support in Spring Boot 3.x

```java
// Spring Boot 3.x supports RFC 9457 (previously RFC 7807) Problem Details for HTTP APIs

// If spring.mvc.problemdetails.enabled=true:
// The ErrorMvcAutoConfiguration registers ProblemDetailsErrorController

// To use in @ExceptionHandler:
@ExceptionHandler(MethodArgumentNotValidException.class)
public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "Validation failed");
    problemDetail.setTitle("Validation Error");
    problemDetail.setProperty("errors", 
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
            .toList());
    return problemDetail;
}

// ProblemDetail response format:
// {
//   "type": "about:blank",
//   "title": "Bad Request",
//   "status": 400,
//   "detail": "Invalid request content.",
//   "instance": "/orders",
//   "errors": [{"field": "amount", "message": "must be greater than 0"}]
// }
```

### Full Validation -> Serialization -> Error Pipeline Diagram

```
+------------------------------------------------------------------------------+
|               COMPLETE REQUEST PROCESSING ARCHITECTURE                         |
|                                                                               |
|  HTTP Request                                                                 |
|  POST /orders  Content-Type: application/json                                 |
|  Accept: application/json                                                     |
|  Body: {"amount": -5, "customerId": null}                                     |
|      |                                                                        |
|      v                                                                        |
|  +----------------------------------------------------------------------+    |
|  | DispatcherServlet.service()                                           |    |
|  |   +-- getHandler() -> RequestMappingHandlerMapping -> handler method  |    |
|  |   +-- ha.handle() -> RequestMappingHandlerAdapter.handle()            |    |
|  +----------------------------------------------------------------------+    |
|      |                                                                        |
|      v                                                                        |
|  +----------------------------------------------------------------------+    |
|  | ARGUMENT RESOLUTION                                                   |    |
|  |                                                                        |    |
|  | RequestResponseBodyMethodProcessor.resolveArgument()                   |    |
|  |                                                                        |    |
|  | +----------------------------------------------------------------+    |    |
|  | | STEP 1: readWithMessageConverters()                              |    |    |
|  | |                                                                   |    |    |
|  | | Content-Type: application/json -> MappingJackson2HttpMessageConverter   |    |
|  | | converter.read() -> objectMapper.readValue(inputStream, Type)     |    |    |
|  | |   -> JsonParser -> BeanDeserializer -> CreateOrderRequest        |    |    |
|  | +----------------------------------------------------------------+    |    |
|  |                                                                        |    |
|  | +----------------------------------------------------------------+    |    |
|  | | STEP 2: Validation                                               |    |    |
|  | |                                                                   |    |    |
|  | | @Valid detected -> DataBinder.validate()                          |    |    |
|  | |   -> SpringValidatorAdapter.validate()                            |    |    |
|  | |     -> Hibernate Validator.validate()                             |    |    |
|  | |       -> ConstraintTree traversal per field                       |    |    |
|  | |       -> ConstraintViolations detected                            |    |    |
|  | |     -> Convert to Errors object                                   |    |    |
|  | |   -> BindingResult.hasErrors() -> true -> exception thrown!       |    |    |
|  | +----------------------------------------------------------------+    |    |
|  +----------------------------------------------------------------------+    |
|      |                                                                        |
|      v                                                                        |
|  +----------------------------------------------------------------------+    |
|  | EXCEPTION HANDLING                                                    |    |
|  |                                                                        |    |
|  | MethodArgumentNotValidException thrown                                 |    |
|  | DispatcherServlet.processHandlerException()                            |    |
|  |   +-- [Resolver 1] ExceptionHandlerExceptionResolver                  |    |
|  |   |   +-- Scan controller for @ExceptionHandler -> none found         |    |
|  |   |   +-- Scan @ControllerAdvice beans -> found match!                |    |
|  |   |   +-- Invoke handler -> ResponseEntity<ProblemDetail>             |    |
|  |   +-- [fallback] DefaultHandlerExceptionResolver -> 400               |    |
|  +----------------------------------------------------------------------+    |
|      |                                                                        |
|      v                                                                        |
|  HTTP Response: 400 Bad Request                                               |
|  Content-Type: application/problem+json                                       |
|  {"type":"about:blank","title":"Bad Request","status":400,                     |
|   "detail":"Validation failed","errors":[...]}                                 |
+------------------------------------------------------------------------------+
```

## 4. Runtime Behavior

### Scenario A: POST /orders with Valid JSON

```
Client sends: POST /orders
  Content-Type: application/json
  {"amount": 99.95, "customerId": "cust-123", "items": [{"sku": "A1", "qty": 2}]}

T+0ms   DispatcherServlet receives request
T+1ms   HandlerMapping finds: OrderController.createOrder(@Valid @RequestBody CreateOrderRequest)
T+2ms   RequestResponseBodyMethodProcessor.resolveArgument() begins
T+3ms   Content-Type is application/json -> MappingJackson2HttpMessageConverter selected
T+4ms   ObjectMapper.readValue() parses JSON bytes
T+8ms   CreateOrderRequest object fully deserialized: amount=99.95, customerId="cust-123"
T+9ms   @Valid detected -> WebDataBinder.validate() called
T+10ms  SpringValidatorAdapter.validate() -> Hibernate Validator.validate()
T+11ms  ConstraintTree traversal: @NotNull on customerId OK, @Positive on amount OK
T+12ms  @Valid on items -> recursively validate each OrderItem
T+13ms  @NotNull on sku OK, @Positive on qty OK
T+14ms  No ConstraintViolations -> BindingResult is clean
T+15ms  Controller method invoked: OrderController.createOrder(request)
T+16ms  Service layer processes the order
T+18ms  Return value: OrderResponse{id="ord-456", status="CONFIRMED"}
T+19ms  handleReturnValue() -> Accept: application/json
T+20ms  MappingJackson2HttpMessageConverter serializes OrderResponse to JSON
T+23ms  Response: 201 Created, {"id":"ord-456","status":"CONFIRMED"}
```

### Scenario B: POST /orders with Invalid Field

```
Client sends: POST /orders
  Content-Type: application/json
  {"amount": -5, "customerId": null}

T+0ms   DispatcherServlet receives request
T+3ms   Jackson deserialization COMPLETES successfully
        -> amount=-5, customerId=null (Jackson does NOT validate, only deserializes)
T+5ms   @Valid triggers validation
T+6ms   Hibernate Validator.validate():
        -> @Positive on amount: -5 < 0 -> ConstraintViolation
        -> @NotNull on customerId: null -> ConstraintViolation
T+7ms   SpringValidatorAdapter converts violations to FieldErrors
T+8ms   BindingResult.hasErrors() -> true
T+9ms   MethodArgumentNotValidException thrown
T+10ms  DispatcherServlet catches exception
T+11ms  processHandlerException() called
T+12ms  ExceptionHandlerExceptionResolver scans @ControllerAdvice
T+13ms  Handler invoked -> creates ProblemDetail with field errors
T+14ms  Response: 400 Bad Request, ProblemDetail JSON
```

### Scenario C: POST /orders with Malformed JSON

```
Client sends: POST /orders with truncated JSON body

T+0ms   DispatcherServlet receives request
T+3ms   MappingJackson2HttpMessageConverter selected
T+4ms   ObjectMapper.readValue() called
T+5ms   JsonParser encounters unexpected EOF -> JsonParseException
T+6ms   AbstractJackson2HttpMessageConverter catches -> wraps in HttpMessageNotReadableException
T+9ms   Exception propagates to DispatcherServlet
T+10ms  processHandlerException() called
T+11ms  ExceptionHandlerExceptionResolver finds @ExceptionHandler(HttpMessageNotReadableException.class)
T+13ms  ProblemDetail(400, "Malformed JSON request") returned
T+15ms  Response: 400 Bad Request

NOTE: Without custom @ExceptionHandler:
  -> DefaultHandlerExceptionResolver maps to 400
  -> Falls through to /error endpoint
  -> DefaultErrorAttributes populates response (may include stack trace if misconfigured)
```

### Scenario D: GET /orders/42 with Accept: application/xml

```
Client sends: GET /orders/42, Accept: application/xml

T+0ms   HandlerMapping finds: getOrder(@PathVariable Long id) -> OrderResponse
T+2ms   Controller method returns OrderResponse{id=42, status="CONFIRMED"}
T+4ms   Determine acceptable types: [application/xml] from Accept header
T+6ms   MappingJackson2HttpMessageConverter.canWrite(OrderResponse, xml) -> false
        MappingJackson2XmlHttpMessageConverter.canWrite(OrderResponse, xml) -> true
        (if jackson-dataformat-xml on classpath)
T+7ms   Selected converter writes XML: <OrderResponse><id>42</id>...</OrderResponse>
T+8ms   Response: 200 OK, Content-Type: application/xml

If jackson-dataformat-xml NOT on classpath:
  -> No converter can write XML -> HttpMediaTypeNotAcceptableException
  -> DefaultHandlerExceptionResolver -> 406 Not Acceptable
```

### Scenario E: Unhandled Exception Propagating to /error

```
Controller method throws NullPointerException (no @ExceptionHandler catches it):

T+0ms   NullPointerException thrown from service layer
T+1ms   DispatcherServlet.processHandlerException()
T+2ms   ExceptionHandlerExceptionResolver: no match (no handler for NPE)
T+3ms   ResponseStatusExceptionResolver: no match (@ResponseStatus not on NPE)
T+4ms   DefaultHandlerExceptionResolver: no match (NPE not in its mapping table)
T+5ms   All resolvers returned null -> exception re-thrown
T+6ms   Servlet container catches -> calls sendError(500)
T+7ms   Error page filter redirects to /error endpoint
T+8ms   DispatcherServlet processes GET /error
T+9ms   BasicErrorController.error(request) invoked
T+10ms  getStatus(request) -> 500 (from javax.servlet.error.status_code attribute)
T+11ms  DefaultErrorAttributes builds response body
T+13ms  Response: 500 Internal Server Error
        {"timestamp":"...","status":500,"error":"Internal Server Error","path":"/orders"}
```

## 5. Request Flow Diagrams

### JSON to Object Deserialization Flow

```
BYTES ON THE WIRE
{"amount":99.95,"items":[{"sku":"A1","qty":2}]}
                    |
                    v
+--------------------------------------------------------------------+
| HttpInputMessage.getBody() -> InputStream                            |
+---------------------------+----------------------------------------+
                            |
                            v
+--------------------------------------------------------------------+
| ObjectMapper.readValue(InputStream, CreateOrderRequest.class)      |
|                                                                    |
| Step 1: JsonFactory.createParser(inputStream)                      |
|   -> ReaderBasedJsonParser (UTF-8 stream decoder)                   |
|   -> Tokenizes the JSON input                                       |
|     Tokens: START_OBJECT, FIELD_NAME("amount"), VALUE_NUMBER_FLOAT  |
|              FIELD_NAME("items"), START_ARRAY, START_OBJECT,        |
|              FIELD_NAME("sku"), VALUE_STRING("A1"),                 |
|              FIELD_NAME("qty"), VALUE_NUMBER_INT(2),                |
|              END_OBJECT, END_ARRAY, END_OBJECT                      |
|                                                                    |
| Step 2: BeanDeserializer.deserialize(parser, context)              |
|   +-- Expect START_OBJECT token                                    |
|   +-- Loop: nextFieldName() -> "amount"                            |
|   |   +-- BigDecimalDeserializer.deserialize() -> 99.95            |
|   |   +-- field.set(bean, 99.95) via reflection                    |
|   +-- nextFieldName() -> "items"                                   |
|   |   +-- CollectionDeserializer.deserialize()                     |
|   |       +-- For each element: BeanDeserializer for OrderItem     |
|   |           +-- "sku" -> "A1", "qty" -> 2                        |
|   |       -> List<OrderItem> with 1 item                           |
|   +-- Expect END_OBJECT                                            |
|   +-- Return: CreateOrderRequest@4f3c                              |
|                                                                    |
|     WARNING: If any token doesn't match expected type:              |
|        -> InvalidFormatException or UnrecognizedPropertyException  |
+--------------------------------------------------------------------+
```

### Validation Flow (Detailed)

```
CreateOrderRequest object (from deserialization)
    |
    v
+--------------------------------------------------------------------+
| RequestResponseBodyMethodProcessor                                  |
|   Parameter has @Valid -> validateIfApplicable()                    |
+---------------------------+----------------------------------------+
                            |
                            v
+--------------------------------------------------------------------+
| DataBinderFactory.createBinder(request, object, "createOrderRequest")|
|   -> WebDataBinder extends DataBinder                               |
+---------------------------+----------------------------------------+
                            |
                            v
+--------------------------------------------------------------------+
| DataBinder.validate()                                               |
|                                                                    |
|   validator = SpringValidatorAdapter (if jakarta.validation present)|
|                                                                    |
|   validator.validate(target, errors)                                 |
+---------------------------+----------------------------------------+
                            |
                            v
+--------------------------------------------------------------------+
| SpringValidatorAdapter.validate(Object target, Errors errors)       |
|                                                                    |
|   Set<ConstraintViolation> violations =                              |
|       this.targetValidator.validate(target);                         |
|   // targetValidator is Hibernate Validator's ValidatorImpl         |
|                                                                    |
|   FOR EACH violation:                                               |
|       String field = violation.getPropertyPath().toString();       |
|       String code = violation.getConstraintDescriptor()            |
|           .getAnnotation().annotationType().getSimpleName();       |
|       errors.rejectValue(field, code, args, violation.getMessage());|
+---------------------------+----------------------------------------+
                            |
                            v
+--------------------------------------------------------------------+
| Hibernate Validator's ValidatorImpl.validate(target, groups...)     |
|                                                                    |
|   1. ValidationOrder order = validationOrderGenerator               |
|        .getValidationOrder(groups);                                 |
|                                                                    |
|   2. BeanMetaData<CreateOrderRequest> metaData =                    |
|        beanMetaDataManager.getBeanMetaData(CreateOrderRequest.class)|
|      // Cached per class from first validation call                 |
|      // Contains list of MetaConstraint objects                     |
|                                                                    |
|   3. FOR EACH MetaConstraint:                                       |
|        ConstraintValidator validator =                               |
|            constraintValidatorManager.getInitializedValidator(...)  |
|        // Retrieved from cache if already initialized                |
|        // validator.initialize() called ONCE at cache creation      |
|                                                                    |
|        boolean valid = validator.isValid(value, context);           |
|        // @NotNull: NotNullValidator.isValid(null) -> false        |
|        // @Positive: PositiveValidator.isValid(-5) -> false        |
|                                                                    |
|        IF NOT valid:                                                |
|            String msg = messageInterpolator.interpolate(...)        |
|            violations.add(new ConstraintViolationImpl<>(...));      |
|                                                                    |
|   4. FOR EACH Cascadable (from @Valid annotations):                 |
|        Object nestedValue = cascadable.getValue(target);            |
|        violations.addAll(validate(nestedValue));  // RECURSE!       |
|        // OrderItem.sku (@NotBlank) -> ConstraintViolation?        |
|                                                                    |
|   RETURN Set<ConstraintViolation>                                   |
+--------------------------------------------------------------------+
```

### Exception Handling Flow

```
Any exception thrown from controller/handler
    |
    v
+--------------------------------------------------------------------+
| DispatcherServlet.doDispatch()                                      |
|   try { ha.handle(request, response, handler); }                    |
|   catch (Exception ex) { processHandlerException(...); }           |
+---------------------------+----------------------------------------+
                            |
                            v
+--------------------------------------------------------------------+
| DispatcherServlet.processHandlerException()                         |
|                                                                    |
| FOR EACH HandlerExceptionResolver resolver (in registered order):  |
|   ModelAndView mav = resolver.resolveException(req, resp, hnd, ex) |
|   IF mav != null: FIRST MATCH WINS, processDispatchResult()        |
|                                                                    |
| REGISTERED RESOLVERS (Spring Boot default order):                   |
| +------------------------------------------------------------+    |
| | 1. ExceptionHandlerExceptionResolver                       |    |
| |    Priority: HIGHEST                                       |    |
| |    Strategy: Scan for @ExceptionHandler methods            |    |
| |    Search order:                                           |    |
| |      a. Controller class itself (local handlers)           |    |
| |      b. @ControllerAdvice beans (global handlers)          |    |
| |    Match algorithm: closest Exception inheritance match    |    |
| +------------------------------------------------------------+    |
| +------------------------------------------------------------+    |
| | 2. ResponseStatusExceptionResolver                        |    |
| |    Priority: MEDIUM                                        |    |
| |    Strategy: Check @ResponseStatus on exception           |    |
| +------------------------------------------------------------+    |
| +------------------------------------------------------------+    |
| | 3. DefaultHandlerExceptionResolver                        |    |
| |    Priority: LOWEST                                        |    |
| |    Strategy: Hardcoded mapping of exceptions to status     |    |
| |      HttpMessageNotReadableException -> 400                |    |
| |      MethodArgumentNotValidException -> 400               |    |
| |      HttpMediaTypeNotSupportedException -> 415             |    |
| |      HttpMediaTypeNotAcceptableException -> 406            |    |
| +------------------------------------------------------------+    |
|                                                                    |
| IF ALL resolvers return null:                                       |
|   -> Exception propagates to servlet container                      |
|   -> Container calls response.sendError(statusCode)                |
|   -> Error page mechanism redirects to /error endpoint             |
|   -> BasicErrorController handles the error request                |
+--------------------------------------------------------------------+
```

### Error Page Flow

```
Unhandled exception escapes all HandlerExceptionResolvers
    |
    v
+--------------------------------------------------------------------+
| Servlet Container (Tomcat/Jetty/Undertow)                           |
|                                                                    |
| response.sendError(500) called                                     |
|   -> Sets javax.servlet.error.* request attributes:                 |
|     javax.servlet.error.status_code = 500                          |
|     javax.servlet.error.exception = the Throwable                  |
|     javax.servlet.error.message = exception.getMessage()           |
|     javax.servlet.error.request_uri = /orders                      |
|                                                                    |
| Tomcat's ErrorPageSupport:                                          |
|   -> FORWARD to the configured error page location: /error         |
+---------------------------+----------------------------------------+
                            |
                            v
+--------------------------------------------------------------------+
| New request (FORWARD): GET /error                                   |
|   -> DispatcherServlet processes this as a new request              |
|   -> HandlerMapping finds BasicErrorController (mapped to /error)  |
+---------------------------+----------------------------------------+
                            |
                            v
+--------------------------------------------------------------------+
| BasicErrorController.error(request)                                 |
|                                                                    |
| 1. HttpStatus status = getStatus(request)                          |
|    -> Reads from javax.servlet.error.status_code attribute         |
|                                                                    |
| 2. Map<String, Object> body = getErrorAttributes(request, options) |
|    -> DefaultErrorAttributes populates:                            |
|      "timestamp", "status", "error", "path"                        |
|      "exception" (if server.error.include-exception=true)         |
|      "message" (if server.error.include-message=always)            |
|      "trace" (if server.error.include-stacktrace=always/on-param)  |
|                                                                    |
| 3. If Accept: text/html -> Whitelabel Error Page                   |
| 4. If Accept: application/json -> JSON response                    |
| 5. If spring.mvc.problemdetails.enabled=true:                      |
|    -> ProblemDetailsErrorController handles instead                |
|    -> Content-Type: application/problem+json                       |
+--------------------------------------------------------------------+
```

## 6. Lifecycle Diagrams

### ValidatorFactory and MessageInterpolator Initialization

```
Application startup (refresh() step 11: finishBeanFactoryInitialization)
    |
    v
+--------------------------------------------------------------------+
| LocalValidatorFactoryBean (Spring's Validator + Jakarta Validator)  |
|                                                                    |
| Auto-configured by ValidationAutoConfiguration:                    |
|                                                                    |
| 1. Constructor called (no-arg or custom provider)                  |
|    -> Uses Validation.buildDefaultValidatorFactory()                |
|                                                                    |
| 2. afterPropertiesSet() called (InitializingBean)                  |
|    -> Sets up MessageInterpolator:                                  |
|        If MessageSource bean exists:                                |
|          -> Uses LocaleContextHolder to get current locale          |
|          -> Resolves from Spring's MessageSource                    |
|          -> Falls back to ValidationMessages.properties             |
|        Otherwise:                                                   |
|          -> Uses default ResourceBundleMessageInterpolator          |
|    -> Creates jakarta.validation.Validator instance                |
|      (Hibernate Validator's ValidatorImpl)                          |
|                                                                    |
| 3. Validator instance caches:                                      |
|    +-- BeanMetaData per class (lazy, on first validation)          |
|    +-- ConstraintValidator instances (lazy, on first use)          |
|    +-- MessageInterpolator (eager, one per validator)              |
|                                                                    |
| 4. LocalValidatorFactoryBean exposes BOTH:                         |
|    +-- SpringValidatorAdapter (for DataBinder integration)         |
|    +-- jakarta.validation.Validator (for direct programmatic use)  |
+--------------------------------------------------------------------+
```

### Jackson ObjectMapper Bean Creation

```
Application startup -> refresh() step 11 -> preInstantiateSingletons()
    |
    v
+--------------------------------------------------------------------+
| JacksonAutoConfiguration                                           |
|                                                                    |
| @ConditionalOnClass(ObjectMapper.class) -> match OK                |
|                                                                    |
| 1. jacksonObjectMapperBuilder bean created:                        |
|    +--------------------------------------------------------+     |
|    | Jackson2ObjectMapperBuilder created                    |     |
|    |   +-- Apply spring.jackson.* properties:               |     |
|    |   |   serialization.indent-output=true                 |     |
|    |   |   deserialization.fail-on-unknown-properties=true  |     |
|    |   |   default-property-inclusion=non_null              |     |
|    |   |   date-format=yyyy-MM-dd                           |     |
|    |   |   time-zone=UTC                                    |     |
|    |   |                                                   |     |
|    |   +-- Apply Jackson2ObjectMapperBuilderCustomizer beans|     |
|    |   +-- Detect and configure Jackson Modules:            |     |
|    |   |   JavaTimeModule (java.time.* support)            |     |
|    |   |   Jdk8Module (Optional support)                   |     |
|    |   |   ParameterNamesModule (constructor detection)    |     |
|    |   |   JsonComponentModule (@JsonComponent beans)      |     |
|    +--------------------------------------------------------+     |
|                                                                    |
| 2. jacksonObjectMapper bean created:                                |
|    +--------------------------------------------------------+     |
|    | ObjectMapper created via Jackson2ObjectMapperBuilder  |     |
|    |   +-- _serializationConfig (SerializationFeature flags) |     |
|    |   +-- _deserializationConfig (DeserializationFeature)  |     |
|    |   +-- _registeredModuleTypes (list of active Modules)  |     |
|    |   +-- _rootDeserializers (ConcurrentHashMap, lazy)    |     |
|    |   |                                                   |     |
|    |   @Primary bean -> used by ALL Jackson converters     |     |
|    |   @ConditionalOnMissingBean -> user can override      |     |
|    +--------------------------------------------------------+     |
+--------------------------------------------------------------------+
```

### HttpMessageConverter Registration Order

```
Application startup -> WebMvcAutoConfiguration -> configureMessageConverters()
    |
    v
+--------------------------------------------------------------------+
| HttpMessageConvertersAutoConfiguration                              |
|                                                                    |
| Converts are registered in this order:                             |
|                                                                    |
| 1. ByteArrayHttpMessageConverter                                    |
| 2. StringHttpMessageConverter                                       |
| 3. ResourceHttpMessageConverter                                     |
| 4. ResourceRegionHttpMessageConverter                               |
| 5. AllEncompassingFormHttpMessageConverter                          |
| 6. MappingJackson2HttpMessageConverter (from jacksonObjectMapper)   |
|    -> Reads: application/json, application/*+json                  |
|    -> Writes: application/json, application/*+json                 |
| 7. Additional converters (if any)                                   |
|                                                                    |
| USER CUSTOMIZATION:                                                |
|   @Configuration                                                    |
|   public class WebConfig implements WebMvcConfigurer {             |
|       @Override                                                     |
|       public void configureMessageConverters(                       |
|               List<HttpMessageConverter<?>> converters) {          |
|           // converters already has defaults (1-6 above)           |
|           converters.add(0, new MyCustomConverter());              |
|           // Add at index 0 -> HIGHEST priority                     |
|       }                                                             |
|   }                                                                 |
+--------------------------------------------------------------------+
```

### ExceptionHandlerExceptionResolver Bean Lifecycle

```
Application startup -> refresh() step 11
    |
    v
+--------------------------------------------------------------------+
| ExceptionHandlerExceptionResolver                                   |
| (created by WebMvcConfigurationSupport)                             |
|                                                                    |
| 1. Constructor: no-arg, sets default order                         |
|    implements ApplicationContextAware, InitializingBean             |
|                                                                    |
| 2. setApplicationContext() called (ApplicationContextAware)        |
|                                                                    |
| 3. afterPropertiesSet() called (InitializingBean):                  |
|    initExceptionHandlerAdviceCache()                                |
|                                                                    |
|    Scans ApplicationContext for @ControllerAdvice beans:           |
|      FOR EACH bean:                                                |
|        +-- Is it annotated with @ControllerAdvice?                 |
|        +-- Parse @ExceptionHandler methods in the bean             |
|        +-- Parse @InitBinder methods in the bean                   |
|        +-- Parse @ModelAttribute methods in the bean               |
|        +-- Cache: ControllerAdviceBean -> ExceptionHandlerMethodResolver|
|                                                                    |
|    The cache is built ONCE at startup.                             |
|    New @ControllerAdvice beans added later are NOT detected.       |
|                                                                    |
| 4. Sets up argument resolvers for @ExceptionHandler parameters     |
|      (Exception, HttpServletRequest, WebRequest, etc.)             |
|                                                                    |
| 5. Bean ready -> registered in DispatcherServlet's handlerExceptionResolvers |
+--------------------------------------------------------------------+
```

### @ControllerAdvice Bean Detection and Registration

```
ConfigurationClassPostProcessor (refresh step 5)
    |
    v
+--------------------------------------------------------------------+
| @ControllerAdvice bean detection flow                               |
|                                                                    |
| 1. @ComponentScan discovers class with @ControllerAdvice           |
|    -> Registered as BeanDefinition                                  |
|                                                                    |
| 2. Bean is instantiated in refresh() step 11                        |
|    -> Regular singleton lifecycle                                    |
|                                                                    |
| 3. ExceptionHandlerExceptionResolver.afterPropertiesSet()          |
|    -> Scans for beans annotated with @ControllerAdvice              |
|    -> Creates ControllerAdviceBean wrapper:                          |
|        +-- Bean type, name                                          |
|        +-- @ControllerAdvice attributes:                            |
|        |   +-- basePackages (limit which controllers it applies to) |
|        |   +-- assignableTypes                                      |
|        |   +-- annotations                                          |
|        +-- Parsed @ExceptionHandler method mappings                |
|                                                                    |
| 4. Cached in exceptionHandlerAdviceCache:                           |
|    Map<ControllerAdviceBean, ExceptionHandlerMethodResolver>        |
|                                                                    |
| AT RUNTIME (per exception):                                         |
|   -> isApplicableToBeanType(controllerType) checks:                 |
|       - basePackages: is controller in this package?               |
|       - assignableTypes: is controller assignable?                 |
|       - annotations: is controller annotated?                      |
|     -> Only applicable advices are searched for matching handlers   |
+--------------------------------------------------------------------+
```


## 7. Source Code Reading Guide

### Core Spring Web MVC Classes

```
1. RequestResponseBodyMethodProcessor.java
   spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/
   -> resolveArgument() -- deserialization + validation in one method
   -> readWithMessageConverters() call, then validateIfApplicable() call
   -> Look for: "throw new MethodArgumentNotValidException" -- the validation failure path

2. AbstractMessageConverterMethodArgumentResolver.java
   spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/
   -> readWithMessageConverters() -- the converter selection loop
   -> How Content-Type header determines which converter handles the request
   -> writeWithMessageConverters() -- response serialization with Accept header negotiation

3. SpringValidatorAdapter.java
   spring-context/src/main/java/org/springframework/validation/beanvalidation/
   -> validate(Object target, Errors errors) -- the bridge from Jakarta to Spring
   -> How ConstraintViolations are converted to FieldErrors
   -> processConstraintViolations() -- the actual conversion logic

4. DataBinder.java
   spring-context/src/main/java/org/springframework/validation/
   -> validate() -- triggers SpringValidatorAdapter
   -> getBindingResult() -- returns the Errors object populated by validation

5. MethodValidationPostProcessor.java
   spring-context/src/main/java/org/springframework/validation/beanvalidation/
   -> How @Validated on the CLASS level triggers method-level validation
   -> AOP-based: creates a proxy that validates method parameters and return values
```

### Jackson Integration

```
6. AbstractJackson2HttpMessageConverter.java
   spring-web/src/main/java/org/springframework/http/converter/json/
   -> read() -- the deserialization entry point
   -> readJavaType() -- calls objectMapper.readValue()
   -> Exception handling: InvalidFormatException -> HttpMessageNotReadableException

7. MappingJackson2HttpMessageConverter.java
   spring-web/src/main/java/org/springframework/http/converter/json/
   -> Constructor sets supported MediaTypes: application/json, application/*+json
   -> canRead()/canWrite() -- type and media type compatibility checks

8. JacksonAutoConfiguration.java
   spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/jackson/
   -> jacksonObjectMapper() -- the @Primary ObjectMapper bean
   -> jacksonObjectMapperBuilder() -- applies spring.jackson.* properties
   -> Module beans: Jdk8Module, JavaTimeModule, ParameterNamesModule

9. Jackson2ObjectMapperBuilder.java
   spring-boot/src/main/java/org/springframework/boot/autoconfigure/jackson/
   -> build() -- assembles the final ObjectMapper
   -> customize() -- applies property-based configuration
```

### Hibernate Validator Internals

```
10. ValidatorImpl.java
    hibernate-validator/src/main/java/org/hibernate/validator/internal/engine/
    -> validate() -- entry point for all validation calls
    -> validateInContext() -- manages validation context propagation
    -> validateConstraintsForCurrentGroup() -- iterates MetaConstraints

11. ConstraintTree.java
    hibernate-validator/src/main/java/org/hibernate/validator/internal/engine/constraintvalidation/
    -> validateConstraints() -- executes constraint validators in order
    -> How @ReportAsSingleViolation affects violation collection

12. ConstraintValidatorManager.java
    hibernate-validator/src/main/java/org/hibernate/validator/internal/engine/constraintvalidation/
    -> getInitializedValidator() -- the per-validator cache
    -> How ConstraintValidatorFactory.createInstance() is called
    -> initialize() called once, isValid() called many times

13. BeanMetaDataManager.java
    hibernate-validator/src/main/java/org/hibernate/validator/internal/metadata/core/
    -> getBeanMetaData(Class) -- builds and caches BeanMetaData per class
    -> How annotation scanning builds the MetaConstraint list
```

### Error Handling Infrastructure

```
14. DispatcherServlet.java
    spring-webmvc/src/main/java/org/springframework/web/servlet/
    -> processHandlerException() -- the error dispatch central
    -> initHandlerExceptionResolvers() -- how resolvers are registered

15. ExceptionHandlerExceptionResolver.java
    spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/
    -> doResolveHandlerMethodException() -- finds and invokes @ExceptionHandler
    -> getExceptionHandlerMethod() -- controller-local then @ControllerAdvice search
    -> initExceptionHandlerAdviceCache() -- startup-time @ControllerAdvice scanning

16. BasicErrorController.java
    spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/web/servlet/error/
    -> error() -- JSON error response
    -> errorHtml() -- HTML error page (Whitelabel)
    -> getErrorAttributes() -- populates the error response map

17. DefaultErrorAttributes.java
    spring-boot/src/main/java/org/springframework/boot/web/servlet/error/
    -> getErrorAttributes() -- builds the standard error response map
    -> How server.error.* properties control attribute inclusion

18. ErrorProperties.java
    spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/web/
    -> @ConfigurationProperties(prefix = "server.error")

19. ErrorMvcAutoConfiguration.java
    spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/web/servlet/error/
    -> basicErrorController() -- registers the /error endpoint
    -> ProblemDetailsErrorController (Spring Boot 3.x)
```

### Problem Details (RFC 9457)

```
20. ProblemDetail.java
    spring-web/src/main/java/org/springframework/http/
    -> The standard ProblemDetail class for RFC 9457 responses
    -> Fields: type, title, status, detail, instance, properties (Map)

21. ErrorResponse.java
    spring-web/src/main/java/org/springframework/web/
    -> Builder interface for building error responses with ProblemDetail
```

## 8. Production Failure Scenarios

### Scenario 1: Stack Traces in Error Responses Leaking Internal Architecture

**Symptom**: A client reports seeing stack traces in 400 error responses. The traces reveal internal package names, class names, and the framework version.

**Root cause**: `server.error.include-stacktrace=always` (or `on-param` and the client appended `?trace=true`). The `DefaultErrorAttributes` includes the full stack trace, including Spring framework internals, in the error response body.

**Attack surface**: An attacker can enumerate which packages exist, which libraries are in use, and JVM/Spring Boot versions from stack trace line numbers matching known release artifacts.

**Diagnosis**:
```bash
curl -s http://api.example.com/orders -X POST \
  -H "Content-Type: application/json" -d 'bad json' | jq '.trace'
```

**Resolution**:
```properties
# ALWAYS set in production:
server.error.include-stacktrace=never
server.error.include-exception=never
server.error.include-message=never
server.error.include-binding-errors=never

# Use RFC 9457 Problem Details for structured but safe error responses:
spring.mvc.problemdetails.enabled=true
```
And implement a custom `@ExceptionHandler` that returns a sanitized `ProblemDetail` -- never the raw exception.

### Scenario 2: Validation Bottleneck on Large Payloads

**Symptom**: An endpoint that accepts a JSON array shows 100x latency increase when the array grows beyond 1000 items. Memory usage spikes proportionally.

**Root cause**: Each element in the array undergoes full Hibernate Validator traversal. With `@Valid` on each element of a `List<OrderItem>`, a 1000-item list triggers 1000 recursive validation calls. Each call looks up `BeanMetaData` (cached ~1us), iterates `MetaConstraint` entries (~5-10 per class), and calls each `ConstraintValidator.isValid()` (~1-5us each). With nested `@Valid`, recursion continues.

With 10 constraints per `OrderItem` and 1000 items: 10,000 `isValid()` calls. At 2us each: 20ms per request. At 10,000 items: 200ms. At 100,000 items: 2 seconds of CPU burn.

**Diagnosis**:
```java
// Enable timing on Hibernate Validator:
logging.level.org.hibernate.validator=TRACE
```

**Resolution**:
1. **Pagination**: Don't accept 100,000 items in one request. Paginate to 100-1000 max.
2. **Batch validation offload**: For massive payloads, validate asynchronously.
3. **Group-based validation**: Use `@Validated(OnCreate.class)` vs `@Validated(OnUpdate.class)` to validate only relevant constraints.
4. **Fail-fast ordering**: Order `@GroupSequence` so cheap validations run before expensive ones.

### Scenario 3: Jackson Deserialization Failure Due to Unknown Properties

**Symptom**: After adding a new JSON field to requests (e.g., `"priority": "HIGH"`), existing clients break with 400 errors.

**Root cause**: `spring.jackson.deserialization.fail-on-unknown-properties=true` (the Spring Boot default). When JSON contains a field not in the Java class, Jackson throws `UnrecognizedPropertyException`.

**Why Spring Boot defaults to `true`**: It catches typos. If a client sends `"ammount": 100` instead of `"amount": 100`, failing loudly prevents silent data loss.

**Diagnosis**:
```java
@Autowired private ObjectMapper mapper;
boolean failOnUnknown = mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
```

**Resolution options**:
```properties
# Option A: Disable globally
spring.jackson.deserialization.fail-on-unknown-properties=false

# Option B: Disable per-class only
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateOrderRequest { ... }

# Option C: Keep enabled, but use API versioning for field additions
```

**Staff-level guidance**: Keep `fail-on-unknown-properties=true` in production. If you need to add optional fields, release a new API version.

### Scenario 4: @ExceptionHandler Not Catching Expected Exception

**Symptom**: An `@ExceptionHandler(MethodArgumentNotValidException.class)` method exists but validation errors still return 500 responses.

**Root cause**: The @ExceptionHandler is in the wrong place, or a closer-matching handler intercepts first, or the exception type doesn't match.

**Common causes**:
1. **Handler in wrong @ControllerAdvice scope** (basePackages filter excludes the failing controller)
2. **Narrower handler wins**: Controller-local `@ExceptionHandler(Exception.class)` catches everything first
3. **Wrong exception class**: Validation on `@RequestParam` throws `ConstraintViolationException`, not `MethodArgumentNotValidException`

**Diagnosis**:
```
logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver=TRACE
```

**Resolution**: Define handlers from MOST specific to LEAST specific. Place broad handlers at lower priority using `@Order`.

### Scenario 5: Content Negotiation Returning Wrong Format

**Symptom**: A Spring Boot API endpoint that returned JSON for months suddenly returns XML to a specific client.

**Root cause**: The client's `Accept: application/xml` header plus `jackson-dataformat-xml` on classpath causes Spring to serialize to XML. More subtly: `Accept: */*` from certain HTTP libraries resolves to `StringHttpMessageConverter` for `String` return types, bypassing Jackson.

**Diagnosis**:
```java
logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor=DEBUG
```

**Resolution**:
```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.defaultContentType(MediaType.APPLICATION_JSON);
    }
}
```

### Scenario 6: Missing NoArgsConstructor Causing Jackson Deserialization Failure

**Symptom**: `InvalidDefinitionException: Cannot construct instance of OrderRequest (no Creators, like default constructor, exist)`.

**Root cause**: DTO class has a single all-args constructor but no no-args constructor, and Jackson cannot determine JSON-field-to-constructor-param mapping.

**Fix options**:
```java
// Option 1: @JsonCreator + @JsonProperty
public CreateOrderRequest(@JsonProperty("amount") BigDecimal amount) { ... }

// Option 2: Enable -parameters compiler flag (ParameterNamesModule auto-detects)

// Option 3: Use Java record (Spring Boot 3.x, Jackson 2.12+):
public record CreateOrderRequest(@NotNull @Positive BigDecimal amount) {}
```

### Scenario 7: Custom Validator with @Autowired Dependency Being Null

**Symptom**: A custom `ConstraintValidator` throws `NullPointerException` in production because an `@Autowired` dependency is `null`.

**Root cause**: The default `ConstraintValidatorFactoryImpl` creates validators with `Class.newInstance()`. These instances are NOT Spring-managed. `@Autowired` fields are `null`.

```java
// BROKEN:
public class ValidOrderStatusValidator implements ConstraintValidator<ValidOrderStatus, String> {
    @Autowired private OrderRepository orderRepository;  // ALWAYS null!
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return orderRepository.existsByStatus(value);  // NPE!
    }
}

// FIX:
@Bean
public LocalValidatorFactoryBean validator(AutowireCapableBeanFactory beanFactory) {
    LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean();
    factoryBean.setConstraintValidatorFactory(
        new SpringConstraintValidatorFactory(beanFactory));
    return factoryBean;
}
```

## 9. Debugging Techniques

### How to Debug HttpMessageConverter Selection

```java
// Technique 1: Enable DEBUG logging
logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor=DEBUG
// Output: "Read [application/json;charset=UTF-8] to [com.example.CreateOrderRequest]"

// Technique 2: Inspect via a Filter
@Component
public class ConverterAuditFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) {
        HttpServletRequest request = (HttpServletRequest) req;
        log.info("Request: {} {} Content-Type: {} Accept: {}",
            request.getMethod(), request.getRequestURI(),
            request.getContentType(), request.getHeader("Accept"));
        chain.doFilter(req, resp);
    }
}

// Technique 3: Breakpoint in AbstractMessageConverterMethodArgumentResolver.readWithMessageConverters()
// Step through converter list iteration

// Technique 4: List converters at startup
@Component
public class ConverterInspector implements ApplicationListener<ApplicationReadyEvent> {
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        RequestMappingHandlerAdapter adapter = 
            event.getApplicationContext().getBean(RequestMappingHandlerAdapter.class);
        List<HttpMessageConverter<?>> converters = adapter.getMessageConverters();
        for (int i = 0; i < converters.size(); i++) {
            HttpMessageConverter<?> c = converters.get(i);
            System.out.printf("[%d] %s -> supports: %s%n", 
                i, c.getClass().getSimpleName(),
                c instanceof AbstractHttpMessageConverter<?> ac 
                    ? ac.getSupportedMediaTypes() : "unknown");
        }
    }
}
```

### How to Inspect Jackson ObjectMapper Configuration at Runtime

```java
@Component
public class ObjectMapperInspector {
    @Autowired private ObjectMapper objectMapper;
    
    @EventListener(ApplicationReadyEvent.class)
    public void inspect() {
        SerializationConfig serConfig = objectMapper.getSerializationConfig();
        DeserializationConfig deserConfig = objectMapper.getDeserializationConfig();
        
        System.out.println("FAIL_ON_UNKNOWN_PROPERTIES: " + 
            deserConfig.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        System.out.println("INDENT_OUTPUT: " + 
            serConfig.isEnabled(SerializationFeature.INDENT_OUTPUT));
        System.out.println("Registered modules:");
        objectMapper.getRegisteredModuleIds().forEach(id -> System.out.println("  " + id));
    }
}

// Use JacksonTester in tests:
@JsonTest
class OrderRequestJsonTest {
    @Autowired private JacksonTester<CreateOrderRequest> json;
    @Test
    void testDeserialization() throws IOException {
        CreateOrderRequest req = json.parseObject("{\"amount\": 99.95}");
    }
}
```

### Debugging Validation Failures

```bash
# Enable TRACE on Hibernate Validator:
logging.level.org.hibernate.validator.internal.engine=TRACE
logging.level.org.hibernate.validator.internal.metadata=TRACE
logging.level.org.springframework.validation.beanvalidation=TRACE

# Shows per-field constraint evaluation:
# "Validating com.example.CreateOrderRequest"
# "  Validating @NotNull on field 'customerId'"
# "  Value: null, isValid: false"
# "  Interpolated message: must not be null"

# Debug ConstraintValidatorFactory:
logging.level.org.hibernate.validator.internal.engine.constraintvalidation=TRACE
# Shows: "Creating ConstraintValidator instance using factory XYZ"
```

### How to Trace Exception Handler Selection

```bash
logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver=TRACE
# Shows: "Resolved exception handler for MethodArgumentNotValidException: ..."
```

```java
// Inspect the resolver's cache at runtime:
@RestController
public class DebugController {
    @Autowired private ExceptionHandlerExceptionResolver resolver;
    
    @GetMapping("/debug/exception-handlers")
    public Map<String, Object> listHandlers() throws Exception {
        Field cacheField = ExceptionHandlerExceptionResolver.class
            .getDeclaredField("exceptionHandlerAdviceCache");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(resolver);
        Map<String, Object> result = new LinkedHashMap<>();
        cache.forEach((advice, handlers) -> result.put(advice.toString(), handlers.toString()));
        return result;
    }
}
```

### Using MockMvc to Test Validation and Error Handling

```java
@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerValidationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void shouldReturn400OnValidationFailure() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": -5, \"customerId\": null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[?(@.field == 'amount')].message")
                .value("must be greater than 0"))
            .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message")
                .value("must not be null"))
            .andReturn();
        
        Exception resolvedException = result.getResolvedException();
        System.out.println("Resolved: " + resolvedException);
    }

    @Test
    void shouldReturn400OnMalformedJson() throws Exception {
        mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{bad json"))
            .andExpect(status().isBadRequest());
    }
}
```

### Debugging Jackson Deserialization Step-Through

```java
class JacksonDeserializationTests {
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void shouldDetectExactDeserializationFailure() {
        String json = "{\"amount\": \"not-a-number\"}";
        assertThrows(InvalidFormatException.class, () -> mapper.readValue(json, CreateOrderRequest.class));
        
        // Use DeserializationProblemHandler for detailed diagnostics:
        mapper.setHandler(new DeserializationProblemHandler() {
            @Override
            public Object handleWeirdStringValue(DeserializationContext ctxt,
                    Class<?> targetType, String valueToConvert, String failureMsg) {
                System.out.printf("Failed to convert '%s' to %s: %s%n",
                    valueToConvert, targetType.getSimpleName(), failureMsg);
                return super.handleWeirdStringValue(ctxt, targetType, valueToConvert, failureMsg);
            }
        });
    }
}
```

## 10. Observability Considerations

### Key Metrics to Track

```
| Metric | Type | What It Tells You |
|--------|------|-------------------|
| validation.failure.count | Counter (by endpoint, field) | Which endpoints/fields fail most? |
| deserialization.error.count | Counter (by endpoint) | Are clients sending malformed JSON? |
| exception.handler.hit.count | Counter (by exception_type) | Which exception types are most common? |
| http.server.requests (status) | Already by Actuator | 400/422 rate, 500 rate |
```

### Micrometer-Based Custom Metrics

```java
@Component
public class ValidationMetricsAspect {
    private final MeterRegistry registry;

    public ValidationMetricsAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, 
            HttpServletRequest request) {
        registry.counter("validation.failure").increment();
        ex.getBindingResult().getFieldErrors().forEach(fe -> 
            registry.counter("validation.failure.per.field",
                "endpoint", request.getRequestURI(),
                "field", fe.getField(),
                "code", fe.getCode()
            ).increment());
        return buildProblemDetail(ex);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleDeserialization(HttpMessageNotReadableException ex) {
        registry.counter("deserialization.error").increment();
        return buildProblemDetail(ex);
    }
}
```

### Logging: What to Log (and What NOT to Log)

```
DO LOG:
  - The endpoint path (e.g., /orders)
  - The HTTP method (POST)
  - The validation error codes (NotNull, Positive, Size)
  - The fields that failed
  - The timestamp and trace ID

DO NOT LOG (PII/GDPR/Security concern):
  - The full request body (may contain PII, credit card info)
  - The values that failed validation (may contain PII)
  - Stack traces (expose internal architecture)
  - Headers that may contain auth tokens
```

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ProblemDetail handleValidation(MethodArgumentNotValidException ex,
        HttpServletRequest request) {
    // SAFE logging:
    log.warn("Validation failed on {} {}: fields={} codes={} traceId={}",
        request.getMethod(), request.getRequestURI(),
        ex.getBindingResult().getFieldErrors().stream().map(FieldError::getField).toList(),
        ex.getBindingResult().getFieldErrors().stream().map(FieldError::getCode).toList(),
        MDC.get("traceId"));
    // NEVER: log the full request body
    return buildProblemDetail(ex);
}
```

### Tracing: Adding Validation Results to Spans

```java
@Component
public class ValidationTracingFilter extends OncePerRequestFilter {
    private final Tracer tracer;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag("http.content_type", 
                Objects.toString(request.getContentType(), "unknown"));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (currentSpan != null && request.getAttribute("validation.errors") != null) {
                @SuppressWarnings("unchecked")
                List<String> errors = (List<String>) request.getAttribute("validation.errors");
                currentSpan.tag("validation.errors", String.join(",", errors));
                currentSpan.tag("error", "true");
            }
        }
    }
}
```

### Alerting: When to Alert on Error Spikes

```
Alert: validation_failure_rate > 5% of requests (5-min window)
  -> Client SDK bug, breaking API change, or attack probe

Alert: deserialization_error_rate > 1% of requests
  -> Clients sending malformed JSON, new client integration failure

Alert: 500_error_rate > 0.1%
  -> Bug in validation handling itself (@ExceptionHandler is broken)

Health Check: Validation is stateless -> NO direct health indicator needed
  - Monitor the validation ERROR RATE, not a boolean health status
  - Do NOT create a /health/validation endpoint (validators are stateless)
```

## 11. Performance Implications

### Jackson Deserialization Cost for Large Payloads

```
Payload size vs deserialization time (ObjectMapper default config):

| Payload | Objects    | Deser Time | Throughput |
|---------|------------|-----------|------------|
| 1 KB    | ~5         | ~50 us    | 20,000/s  |
| 10 KB   | ~50        | ~200 us   | 5,000/s   |
| 100 KB  | ~500       | ~2 ms     | 500/s     |
| 1 MB    | ~5,000     | ~25 ms    | 40/s      |
| 10 MB   | ~50,000    | ~350 ms   | 3/s       |

Key findings:
- Deserialization is O(n) in payload size
- Java records: 15-20% faster than POJOs (MethodHandle invocation vs reflection)
- Streaming (JsonParser manually): 3x faster for simple structures
- Use Jackson Afterburner module for bytecode-generated serializers (30-40% speedup)
- Avoid deeply nested object trees (each level adds deserializer context overhead)
```

### Hibernate Validator Performance with Complex Object Graphs

```java
// For a single object with 20 constraints: ~40us per validation call
// For nested graph: Order(10) -> 5 OrderItems(5 each) -> each has Address(5)
//   Total: 10 + 25 + 25 = 60 validations -> ~120us for one order
// For 1000 orders: ~120ms total (CPU-bound, single-threaded)

// OPTIMIZATION: Group validation
@Validated(OnCreate.class)  // Only validates Default + OnCreate groups
// vs @Validated (ALL groups) -- group validation reduces validator count

// Custom ConstraintValidator optimization:
// Move expensive work from isValid() to initialize():
public class ExternalServiceValidator implements ConstraintValidator<ValidCustomer, String> {
    private String allowedPattern;
    
    @Override
    public void initialize(ValidCustomer annotation) {
        // HEAVY WORK -- called ONCE
        this.allowedPattern = annotation.pattern();
    }
    
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // LIGHT WORK -- called for EVERY validation
        return value != null && value.matches(allowedPattern);
        // NEVER call database/remote service from isValid()!
        // Use a pre-loaded cache or pass data via ConstraintValidatorContext.
    }
}
```

### ObjectMapper Configuration Performance

```java
// CRITICAL: ObjectMapper is thread-safe! Share one instance across the application.

// FAIL_ON_UNKNOWN_PROPERTIES: negligible (set membership test on property names)
// FAIL_ON_NULL_FOR_PRIMITIVES: negligible (null check per primitive field)
// ACCEPT_FLOAT_AS_INT: 5-10% overhead for numeric fields (attempts coercion)
// INDENT_OUTPUT: 5-15% larger response size, ~10% slower serialization
// USE_BIG_DECIMAL_FOR_FLOATS: BigDecimal arithmetic is 5-10x slower than double

// SINGLE ObjectMapper instance: REQUIRED
//   -> ObjectMapper caches serializers/deserializers per class in ConcurrentHashMap
//   -> Creating new ObjectMapper per request -> cache miss -> rebuild -> 1000x slower
```

### HttpMessageConverter Chain Traversal Cost

```
For each request:
1. Iterate all converters (8-12 default)
2. Each: canRead(targetType, mediaType) -- assignability + string comparison
3. Total traversal: ~1us (negligible compared to deserialization time)
4. Selected converter.read(): depends on payload (dominates performance)

With 50+ custom converters: ~6us per request (still negligible)
OPTIMIZATION: register your custom converter at position 0
-> converters.add(0, myFastConverter);
```

### ProblemDetail Serialization Overhead

```java
// Plain Map.of("message", "error") -> 1.2 us per serialization
// ProblemDetail with 5 fields -> 1.8 us per serialization
// -> 50% overhead but only 0.6us absolute difference per request

// RECOMMENDATION: Use ProblemDetail. Overhead is negligible.
// The standardization benefit far outweighs the 0.6us cost.
```


## 12. Architecture Implications

### Where Validation Belongs: Controller Layer vs Service Layer vs Both

```
+-------------------------------------------------------------------+
|                  WHERE TO PLACE VALIDATION                          |
+--------------+--------------------+-------------------------------+
|  Layer       | What to Validate   | Why                           |
+--------------+--------------------+-------------------------------+
|  Controller  | DTO field formats,  | Input contracts -- "does the  |
|  (@Valid)    | nullability,        | data look right?" Fail fast,  |
|              | ranges, sizes       | return 400.                   |
+--------------+--------------------+-------------------------------+
|  Service     | Business rules,     | Domain invariants -- "does    |
|  (@Validated  | cross-field logic, | the data make sense?" Can't   |
|   on class)  | state validation    | check from DTO alone.         |
+--------------+--------------------+-------------------------------+
|  Domain      | Aggregate           | Business core -- must NEVER   |
|  Entity      | invariants,         | be bypassed. Entity cannot    |
|              | lifecycle state     | be in an invalid state.       |
+--------------+--------------------+-------------------------------+

ANTI-PATTERN: Validating ONLY at the controller layer
  -> Batch jobs that call service directly bypass validation
  -> Internal API calls skip controller validation
  
ANTI-PATTERN: Validating ONLY at the service layer  
  -> Invalid data reaches the service, wastes resources
  -> 500 errors for bad input instead of 400 errors
  
CORRECT: Defense in depth
  Controller: structural validation (format, nullability, range)
  Service: business validation (cross-field, state-aware rules)
  Domain: final invariant enforcement (aggregate consistency)
```

### API Versioning Impact on Error Response Format

```
Versioning strategy affects error handling:

URL-BASED VERSIONING (/v1/orders, /v2/orders):
  -> Different versions can have different error formats
  -> @ExceptionHandler in version-specific controllers
  -> v1: {"error": "..."}, v2: ProblemDetail

HEADER-BASED VERSIONING (Accept: application/vnd.myapp.v2+json):
  -> Content negotiation drives error format
  -> Custom HttpMessageConverter per version

CONTENT-TYPE-BASED (Accept: application/problem+json vs application/json):
  -> Already built into Spring Boot 3.x ProblemDetail support
  -> spring.mvc.problemdetails.enabled=true enables negotiation
```

```java
@ControllerAdvice
public class VersionedErrorHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/v1/")) {
            // Legacy format for v1
            Map<String, Object> legacy = Map.of(
                "error", "Validation failed",
                "fields", ex.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getField).toList());
            return ResponseEntity.badRequest().body(legacy);
        }
        // RFC 9457 for v2+
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors());
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
```

### RFC 9457 Adoption Strategy for Existing APIs

```
MIGRATION PATH:

Phase 1 (Non-breaking): Accept-based negotiation
  spring.mvc.problemdetails.enabled=true
  -> Existing clients: Accept: application/json -> legacy format
  -> New clients: Accept: application/problem+json -> RFC 9457

Phase 2 (Dual-format): Both formats available
  -> Monitor problem+json adoption
  -> Update internal client SDKs

Phase 3 (Sunset legacy): 6-12 months later  
  -> Announce deprecation of legacy format
  -> After deadline: 406 Not Acceptable for non-problem+json errors
```

### JSON Schema Generation from Java Validation Annotations

```java
// Spring Boot doesn't auto-generate JSON Schema from @NotNull, @Positive, etc.
// But you can build a tool:

public class JsonSchemaGenerator {
    public JsonNode generateSchema(Class<?> dtoClass) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        
        for (Field field : dtoClass.getDeclaredFields()) {
            ObjectNode fieldSchema = properties.putObject(field.getName());
            mapJavaType(field, fieldSchema);
            
            for (Annotation annotation : field.getAnnotations()) {
                if (annotation instanceof NotNull || annotation instanceof NotBlank)
                    required.add(field.getName());
                if (annotation instanceof Size size) {
                    fieldSchema.put("minLength", size.min());
                    fieldSchema.put("maxLength", size.max());
                }
                if (annotation instanceof Positive || annotation instanceof PositiveOrZero)
                    fieldSchema.put("minimum", annotation instanceof PositiveOrZero ? 0 : 1);
                if (annotation instanceof Min min) fieldSchema.put("minimum", min.value());
                if (annotation instanceof Max max) fieldSchema.put("maximum", max.value());
                if (annotation instanceof Email) fieldSchema.put("format", "email");
                if (annotation instanceof Pattern pattern) fieldSchema.put("pattern", pattern.regexp());
            }
        }
        return schema;
    }

    private void mapJavaType(Field field, ObjectNode fieldSchema) {
        Class<?> type = field.getType();
        if (type == String.class) fieldSchema.put("type", "string");
        else if (type == Integer.class || type == int.class) fieldSchema.put("type", "integer");
        else if (type == BigDecimal.class || type == Double.class) fieldSchema.put("type", "number");
        else if (type == Boolean.class || type == boolean.class) fieldSchema.put("type", "boolean");
        else if (type.isArray() || Collection.class.isAssignableFrom(type)) fieldSchema.put("type", "array");
        else fieldSchema.put("type", "object");
    }
}
```

## 13. Team Ownership Implications

### Shared Error Response Contract Across Microservices

```
When multiple teams own different microservices:

CENTRALIZED ERROR CONTRACT:
+------------------------------------------------------------+
|  error-contract-library (shared Maven/Gradle artifact)     |
|                                                            |
|  Contains:                                                 |
|    +-- ErrorCode enum (standardized error codes)           |
|    |   VALIDATION_ERROR, DESERIALIZATION_ERROR,            |
|    |   BUSINESS_RULE_VIOLATION, DEPENDENCY_FAILURE         |
|    |                                                      |
|    +-- ErrorResponse class (standardized response body)    |
|    |   { "errorCode": "VALIDATION_ERROR",                  |
|    |     "message": "...", "details": [...],               |
|    |     "traceId": "...", "timestamp": "..." }            |
|    |                                                      |
|    +-- ProblemDetailCustomizer (applies standard fields)   |
|        Adds: traceId, errorCode to ProblemDetail           |
+------------------------------------------------------------+

Team responsibilities:
  Platform team: Owns the error-contract-library
    -> Defines error codes, publishes new versions
    -> Provides shared @ControllerAdvice base class
    
  Service teams: Consume the library
    -> Add service-specific error codes
    -> Extend base @ControllerAdvice for custom exceptions
    -> Must NOT define ad-hoc error response formats
```

### Validation Annotation Library Shared Across Teams

```
SHARED VALIDATION ANNOTATIONS:

@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Constraint(validatedBy = ValidCurrencyValidator.class)
public @interface ValidCurrency {
    String message() default "Invalid currency code";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

// In shared library, NOT in each service.
// Platform team maintains the validators. Service teams consume them.

// Anti-pattern: Copying the same @ValidCurrency annotation to 5 services
//   -> When a new currency is added, 5 PRs must be opened and coordinated
```

### Exception Handler Conventions and Shared @ControllerAdvice

```java
// In shared library (error-contract-library):
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BaseGlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setProperty("errorCode", ErrorCode.VALIDATION_ERROR.name());
        pd.setProperty("fields", ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "code", fe.getCode(),
                "message", fe.getDefaultMessage())).toList());
        pd.setProperty("traceId", MDC.get("traceId"));
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleDeserialization(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Malformed request body");
        pd.setProperty("errorCode", ErrorCode.DESERIALIZATION_ERROR.name());
        pd.setProperty("traceId", MDC.get("traceId"));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal Server Error");
        pd.setProperty("errorCode", ErrorCode.INTERNAL_ERROR.name());
        pd.setProperty("traceId", MDC.get("traceId"));
        // DELIBERATELY exclude exception details in production!
        return pd;
    }
}

// In each service:
@ControllerAdvice
public class OrderServiceExceptionHandler extends BaseGlobalExceptionHandler {
    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Order Not Found");
        pd.setProperty("errorCode", ErrorCode.RESOURCE_NOT_FOUND.name());
        return pd;
    }
}
```

### Error Code Taxonomy Management

```
ERROR CODE TAXONOMY STRUCTURE:

CATEGORY:VALIDATION
  VALIDATION_ERROR          -> Generic validation failure
  VALIDATION_FIELD_ERROR    -> Specific field failed validation

CATEGORY:AUTHORIZATION
  UNAUTHORIZED             -> Missing/invalid credentials
  FORBIDDEN                -> Insufficient permissions

CATEGORY:RESOURCE
  RESOURCE_NOT_FOUND       -> Entity doesn't exist
  RESOURCE_CONFLICT        -> Entity already exists

CATEGORY:RATE
  RATE_LIMITED             -> Request exceeded rate limit
  QUOTA_EXCEEDED           -> Account quota exceeded

CATEGORY:DEPENDENCY
  DEPENDENCY_TIMEOUT       -> External service timed out
  DEPENDENCY_UNAVAILABLE   -> External service is down

CATEGORY:INTERNAL
  INTERNAL_ERROR           -> Unexpected server error
  DATABASE_ERROR           -> Database operation failed

GOVERNANCE:
  -> New error codes require PR to error-contract-library
  -> Error codes are NEVER deleted (only deprecated)
  -> Each error code maps to exactly one HTTP status code
  -> Platform team reviews all new error codes weekly
```

## 14. Interview Questions

### Question 1: "You POST invalid JSON to a @RestController. Trace exactly what happens from the IOException in Jackson to the 400 response the client receives. Which Spring components are involved and in what order?"

**Staff-level answer**: The request enters through Tomcat's `CoyoteAdapter`, which delegates to the Servlet container, and ultimately reaches `DispatcherServlet.doDispatch()`. The `DispatcherServlet` has already determined the handler: `OrderController.createOrder(@Valid @RequestBody CreateOrderRequest)`, via `RequestMappingHandlerMapping`.

When `HandlerAdapter.handle()` is called, Spring invokes the `HandlerMethodArgumentResolver` chain. For the `@RequestBody` parameter, `RequestResponseBodyMethodProcessor` takes over. It calls `readWithMessageConverters()`, which iterates the registered `HttpMessageConverter` list. The `Content-Type: application/json` header causes `MappingJackson2HttpMessageConverter.canRead(CreateOrderRequest.class, application/json)` to return `true`. Spring calls `converter.read()` which goes to `AbstractJackson2HttpMessageConverter.read()` and then `ObjectMapper.readValue()`.

Inside Jackson, the `ReaderBasedJsonParser` tokenizes the JSON input stream. If it encounters malformed bytes -- an unexpected token, a missing closing brace, an unquoted string -- it throws a `JsonParseException`. The `AbstractJackson2HttpMessageConverter` catches this, wraps it in an `HttpMessageNotReadableException("JSON parse error: ...")`, and rethrows. This exception propagates up through `readWithMessageConverters()` and escapes the argument resolver.

When the exception reaches `DispatcherServlet.doDispatch()`, the try/catch block calls `processHandlerException(request, response, handler, ex)`. This method iterates three `HandlerExceptionResolver` implementations in order:

First, `ExceptionHandlerExceptionResolver.doResolveHandlerMethodException()` searches for `@ExceptionHandler` methods. The search has two phases: (a) it looks in the controller class for a handler matching `HttpMessageNotReadableException`. If found, that handler runs immediately -- controller-local handlers have highest precedence. (b) If not found, it iterates all `@ControllerAdvice` beans, checking `isApplicableToBeanType(handlerType)` for each, and then scanning their `@ExceptionHandler` methods. The matching algorithm uses inheritance distance -- the exception type closest to `HttpMessageNotReadableException` in the class hierarchy wins. So `@ExceptionHandler(HttpMessageNotReadableException.class)` beats `@ExceptionHandler(IOException.class)` which beats `@ExceptionHandler(Exception.class)`.

If `ExceptionHandlerExceptionResolver` finds a handler, it invokes it via `InvocableHandlerMethod`. The handler method's return value goes through `HandlerMethodReturnValueHandler` -- if it returns a `ProblemDetail` or `ResponseEntity`, Spring calls `AbstractMessageConverterMethodProcessor.handleReturnValue()`, which serializes it to JSON using the same `MappingJackson2HttpMessageConverter`. The response is committed with status 400 and `Content-Type: application/json` (or `application/problem+json` if RFC 9457 is enabled).

The exact sequence of components is: `DispatcherServlet` -> `RequestMappingHandlerAdapter` -> `RequestResponseBodyMethodProcessor` -> `MappingJackson2HttpMessageConverter` -> `ObjectMapper.readValue()` (exception here) -> propagate back -> `DispatcherServlet.processHandlerException()` -> `ExceptionHandlerExceptionResolver` -> `@ExceptionHandler` method -> `RequestResponseBodyMethodProcessor` -> `MappingJackson2HttpMessageConverter` -> HTTP 400 response.

If NO `@ExceptionHandler` matches, `ResponseStatusExceptionResolver` is checked next. `HttpMessageNotReadableException` doesn't have `@ResponseStatus`, so this returns null. Finally, `DefaultHandlerExceptionResolver` handles it -- it has a hardcoded mapping: `HttpMessageNotReadableException -> 400 Bad Request`. It calls `response.sendError(400)`, which triggers the error page mechanism that redirects to the `/error` endpoint. At that point, `BasicErrorController` processes the error request, `DefaultErrorAttributes` builds the error response body (which may include the stack trace if `server.error.include-stacktrace` is misconfigured), and the response is sent.

### Question 2: "How does @Valid trigger validation in a Spring controller? What's the difference between @Valid and @Validated, and when does validation happen relative to deserialization?"

**Staff-level answer**: `@Valid` is a Jakarta Bean Validation annotation (`jakarta.validation.Valid`). `@Validated` is a Spring annotation (`org.springframework.validation.annotation.Validated`). The key differences:

1. **Origin and group support**: `@Valid` is part of Jakarta Bean Validation (JSR-380) and does NOT support validation groups. `@Validated` is Spring's extension that wraps Jakarta validation and adds group support. With `@Validated(OnCreate.class)`, only constraints belonging to the `OnCreate` group are validated.

2. **Where they can be applied**: `@Valid` can be placed on method parameters, fields, and type arguments (e.g., `List<@Valid OrderItem>`). `@Validated` can be placed on method parameters AND at the class level. When placed at the class level on a Spring bean, `@Validated` triggers method-level validation via `MethodValidationPostProcessor`, which creates an AOP proxy that intercepts method calls and validates both method parameters and return values against their constraint annotations.

3. **Validation timing relative to deserialization**: Validation always happens AFTER deserialization in the controller layer. The `RequestResponseBodyMethodProcessor.resolveArgument()` method first calls `readWithMessageConverters()` to deserialize the request body into a Java object, and only then calls `validateIfApplicable()` to trigger validation. This is a critical ordering -- you cannot validate bytes; you can only validate objects. If deserialization fails (malformed JSON), validation never runs because there is no object to validate. If deserialization succeeds but produces an invalid object (e.g., `amount=-5`), validation catches it.

4. **The chain from @Valid to ConstraintValidator**: When `@Valid` is detected on a `@RequestBody` parameter, `DataBinderFactory.createBinder()` creates a `WebDataBinder`. `DataBinder.validate()` calls `SpringValidatorAdapter.validate()`, which delegates to Hibernate Validator's `ValidatorImpl.validate()`. This builds a `ValidationOrder` from the requested groups, retrieves `BeanMetaData` for the target class (cached, built once per class), and iterates all `MetaConstraint` entries. For each constraint, it retrieves the cached `ConstraintValidator` instance (created via `ConstraintValidatorFactory`, initialized once with the annotation attributes), and calls `isValid()`. Violations are collected, interpolated via `MessageInterpolator`, and converted to Spring `FieldError` objects stored in `BindingResult`. If `BindingResult.hasErrors()` is true, `MethodArgumentNotValidException` is thrown.

5. **@Validated on the class level**: This triggers a completely different mechanism. `MethodValidationPostProcessor` is a `BeanPostProcessor` that wraps the bean in an AOP proxy. Every method call on the proxy is intercepted by `MethodValidationInterceptor`, which uses Hibernate Validator's `ExecutableValidator.validateParameters()` and `validateReturnValue()` to validate method arguments and return values. Failures throw `ConstraintViolationException` (not `MethodArgumentNotValidException`), which is the key diagnostic differentiator when debugging why your handler isn't catching validation errors.

### Question 3: "How does @ExceptionHandler resolution work? If you have handlers in the controller, in a @ControllerAdvice, and the default /error endpoint, explain the precedence order and how Spring picks the right one."

**Staff-level answer**: The resolution system is a layered cascade with three distinct resolvers in fixed priority order, and within the primary resolver, a two-phase search with inheritance-distance matching.

**Resolver-level precedence** (in `DispatcherServlet.processHandlerException()`):

1. `ExceptionHandlerExceptionResolver` -- handles `@ExceptionHandler` methods. This is checked FIRST. If it returns a non-null `ModelAndView`, no other resolver is consulted.

2. `ResponseStatusExceptionResolver` -- checks if the exception class has `@ResponseStatus`. If so, it applies the HTTP status and uses the reason field as the body. This is checked SECOND, only if resolver 1 returned null.

3. `DefaultHandlerExceptionResolver` -- has a hardcoded mapping of 20+ Spring MVC exceptions to HTTP status codes. This is checked THIRD.

If all three resolvers return null, the exception propagates to the servlet container, which calls `response.sendError(500)`, triggering the `/error` endpoint redirect.

**Within ExceptionHandlerExceptionResolver**, the search has two phases with implicit precedence rules:

**Phase 1 -- Controller-local handlers**: The resolver first searches the controller class where the exception was thrown for `@ExceptionHandler` methods. These have HIGHEST precedence. A controller-local `@ExceptionHandler(Exception.class)` catches everything and prevents any `@ControllerAdvice` handler from ever running for that controller.

**Phase 2 -- @ControllerAdvice handlers**: If no controller-local handler matches, the resolver iterates all `@ControllerAdvice` beans. For each advice, it checks `isApplicableToBeanType()` -- only advices that target the controller's package, type, or annotations are considered. Within applicable advices, `@ExceptionHandler` methods are searched.

**Within each phase**, the matching algorithm selects the handler whose declared exception type is CLOSEST in the inheritance hierarchy to the thrown exception. For example, if `HttpMessageNotReadableException` is thrown:

- `@ExceptionHandler(HttpMessageNotReadableException.class)` -- depth 1 (best match)
- `@ExceptionHandler(IOException.class)` -- depth 2 (HttpMessageNotReadableException inherits IOException)
- `@ExceptionHandler(Exception.class)` -- depth 4 (distant ancestor)

The handler with the smallest depth wins. If two handlers have the same depth (e.g., two advices both declaring `HttpMessageNotReadableException`), Spring picks the one from the advice with the lowest `@Order` value. If neither has `@Order`, the behavior is non-deterministic (depends on bean registration order).

**The /error endpoint**: This is the last resort. When ALL resolvers return null, the exception reaches the servlet container. In Tomcat, the `ErrorPageSupportValve` intercepts `response.sendError(statusCode)` and FORWARDs the request to the configured error page (`/error` by default). This becomes a NEW request processed by `BasicErrorController`, which reads error details from `javax.servlet.error.*` request attributes set by the container. `DefaultErrorAttributes` populates the response body with timestamp, status, error, and path. Conditional fields (exception class, message, stack trace) are included only if `server.error.include-*` properties permit it.

**The critical debugging insight**: When a `@ControllerAdvice` handler isn't firing, check three things: (1) Does the controller have a broader `@ExceptionHandler` that's intercepting first? (2) Does the `@ControllerAdvice` annotation's `basePackages`/`assignableTypes`/`annotations` filter exclude the failing controller? (3) Is the exception type correct? `@Valid` on `@RequestBody` throws `MethodArgumentNotValidException`. `@Validated` on the class throws `ConstraintViolationException`. `@Valid` on `@RequestParam`/`@PathVariable` throws `HandlerMethodValidationException` (Spring 6.1+) or `ConstraintViolationException` (earlier versions).

## 15. Hands-On Exercises

1. **Write a custom ConstraintValidator for a domain-specific validation rule**: Create a `@ValidOrderStatus` annotation and its `ConstraintValidator`. The validator should check that the order status is one of: PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED. Configure it with `@Constraint(validatedBy = ValidOrderStatusValidator.class)`. Write unit tests using `ValidatorFactory.buildDefaultValidatorFactory()` (no Spring context needed). Then wire it into a Spring Boot controller and test with MockMvc.

2. **Configure a global @ControllerAdvice with proper RFC 9457 ProblemDetail responses**: Create a `@RestControllerAdvice` that handles `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `ConstraintViolationException`, and a catch-all `Exception`. Each handler should return `ProblemDetail` with appropriate status codes, titles, and field-level error details for validation errors. Enable `spring.mvc.problemdetails.enabled=true` and verify the `Content-Type: application/problem+json` header. Test with MockMvc for all four exception types.

3. **Add unit tests for JSON deserialization edge cases using JacksonTester**: Use `@JsonTest` and `JacksonTester` to test deserialization of: (a) a malformed JSON string, (b) JSON with unknown properties (verify fail-on-unknown behavior), (c) JSON with wrong types for fields (e.g., string where number expected), (d) nullable vs non-null fields. Write assertions against `InvalidFormatException`, `UnrecognizedPropertyException`, and successful deserialization.

4. **Implement group-based validation (Create vs Update validation groups)**: Define two marker interfaces: `OnCreate` and `OnUpdate`. Annotate fields in a DTO with different groups: `@NotNull(groups = OnCreate.class)` on a generated ID field that should be null for create but required for update. Use `@Validated(OnCreate.class)` on the create endpoint and `@Validated(OnUpdate.class)` on the update endpoint. Verify with MockMvc that the same DTO validates differently depending on which endpoint is called.

5. **Debug exception handler resolution by creating conflicting handlers**: Create two `@ControllerAdvice` classes with different `@Order` values, both handling `MethodArgumentNotValidException`. Return different response bodies from each. Submit an invalid request and observe which handler wins. Then add a controller-local `@ExceptionHandler(Exception.class)` and observe that it intercepts ALL exceptions before the advices are reached. Enable TRACE logging on `ExceptionHandlerExceptionResolver` and observe the resolution log messages.

## 16. Advanced Challenges

1. **Build a JSON Schema generator that produces JSON Schema from Jakarta Bean Validation annotations**: Create a `JsonSchemaGenerator` class that takes a `Class<?>` and produces a JSON Schema document (Draft 2020-12). Support: `@NotNull` (adds to `required` array), `@NotBlank` (adds `minLength: 1` + required), `@Size` (adds `minLength`/`maxLength`), `@Positive`/`@PositiveOrZero` (adds `minimum`), `@Min`/`@Max` (adds `minimum`/`maximum`), `@Email` (adds `format: "email"`), `@Pattern` (adds `pattern`), `@Valid` on nested objects (recursively generates nested schema), `List<@Valid T>` (generates array schema with items). Expose the schema at `GET /schemas/{className}`. Use this to auto-generate OpenAPI documentation.

2. **Implement a dynamic validation framework where rules are loaded from a database at runtime**: Build a `DynamicConstraintValidator` that implements `ConstraintValidator<DynamicValidation, Object>`. Define a `@DynamicValidation` annotation with a `ruleGroup` attribute. On `initialize()`, the validator queries a database table `validation_rules` for rules matching the `ruleGroup`. Each rule row has: `field_name`, `rule_type` (NOT_NULL, REGEX, RANGE, CUSTOM_SPEL), `rule_value`, `error_message`. On `isValid()`, the validator evaluates each rule against the corresponding field value. Implement rule caching with a TTL. Handle the case where the database is unavailable (fall back to cached rules or pass validation). Test with a dynamic rule change that takes effect within 60 seconds.

3. **Create an "Error Propagation Contract Test" tool**: Given a controller class and its DTO classes, automatically generate client-side error handling code (TypeScript/JavaScript) that correctly handles every possible error response. The tool should: (a) introspect `@ExceptionHandler` methods to determine error response shapes, (b) introspect `@Valid` annotations on DTOs to determine which fields can fail validation, (c) generate a TypeScript enum of error codes, (d) generate typed error response interfaces, (e) generate an error handler function that switches on error types. Run as a Gradle/Maven plugin that produces `errors.ts` during build. Verify by compiling the generated TypeScript with `tsc --noEmit`.

4. **Build a custom HttpMessageConverter that supports a binary protocol (MessagePack) alongside JSON**: Implement `MessagePackHttpMessageConverter` extending `AbstractHttpMessageConverter<Object>`. Support `Content-Type: application/x-msgpack`. In `readInternal()`, use Jackson's MessagePack module (`jackson-dataformat-msgpack`) to deserialize binary data. In `writeInternal()`, serialize to MessagePack binary format. Register the converter via `WebMvcConfigurer.configureMessageConverters()`. Ensure it coexists with JSON: JSON endpoints should still work, and clients requesting `Accept: application/x-msgpack` get MessagePack responses. Benchmark serialization speed and response size vs JSON for a 1000-item collection.

5. **Implement a "Validation Performance Profiler" that measures ConstraintValidator execution time per field**: Create a `ProfilingConstraintValidatorFactory` that wraps the real `ConstraintValidatorFactory` and instruments every `isValid()` call with a `Timer`. Use Micrometer to record per-constraint timing histograms. Build a dashboard endpoint `GET /actuator/validation-profile` that returns: top-10 slowest validators by average execution time, total validation time per endpoint, and per-field breakdown. Use this in production for 24 hours to identify which validators should be optimized (can be sped up) or removed (not providing value relative to their cost). Handle the performance impact of the profiling itself (should be < 1% overhead).
