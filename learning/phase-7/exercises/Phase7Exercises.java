// Phase 7 Exercises — Spring Boot Deep Dive (runnable without Spring framework)
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// ═══════════════════════════════════════════════════════════════════════
// 5.2 — Mini IoC Container
// ═══════════════════════════════════════════════════════════════════════
@Retention(RetentionPolicy.RUNTIME) @interface Autowired {}
@Retention(RetentionPolicy.RUNTIME) @interface Component {}
@Retention(RetentionPolicy.RUNTIME) @interface PostConstruct {}
@Retention(RetentionPolicy.RUNTIME) @interface PreDestroy {}

class MiniApplicationContext {
    private final Map<Class<?>, Object> beans = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) { return (T) beans.get(type); }
    public void registerBean(Class<?> type, Object instance) { beans.put(type, instance); }

    public void autowire() {
        for (var entry : beans.entrySet()) {
            Object bean = entry.getValue();
            for (Field field : bean.getClass().getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    try {
                        Object dep = beans.get(field.getType());
                        if (dep == null) throw new RuntimeException("No bean of type " + field.getType());
                        field.setAccessible(true); field.set(bean, dep);
                    } catch (IllegalAccessException e) { throw new RuntimeException(e); }
                }
            }
        }
        // Call @PostConstruct methods
        for (Object bean : beans.values()) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                if (m.isAnnotationPresent(PostConstruct.class)) {
                    try { m.setAccessible(true); m.invoke(bean); } catch (Exception e) { throw new RuntimeException(e); }
                }
            }
        }
    }

    public void close() {
        for (Object bean : beans.values()) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                if (m.isAnnotationPresent(PreDestroy.class)) {
                    try { m.setAccessible(true); m.invoke(bean); } catch (Exception e) { throw new RuntimeException(e); }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 5.3 — Mini @Transactional via JDK Dynamic Proxy
// ═══════════════════════════════════════════════════════════════════════
@Retention(RetentionPolicy.RUNTIME) @interface Transactional {
    Class<? extends Throwable>[] rollbackFor() default {};
}

interface PaymentProcessor { void processPayment(long amount); }

class PaymentProcessorImpl implements PaymentProcessor {
    public void processPayment(long amount) {
        System.out.println("  Processing payment: " + amount);
        if (amount > 100000) throw new RuntimeException("Insufficient balance");
    }
}

class TransactionProxy implements InvocationHandler {
    private final Object target;
    TransactionProxy(Object target) { this.target = target; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Transactional tx = method.getAnnotation(Transactional.class);
        if (tx == null) return method.invoke(target, args);

        System.out.println(">>> BEGIN TX");
        try {
            Object result = method.invoke(target, args);
            System.out.println(">>> COMMIT TX");
            return result;
        } catch (Exception e) {
            System.out.println(">>> ROLLBACK TX: " + e.getMessage());
            throw e;
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Demo Beans
// ═══════════════════════════════════════════════════════════════════════
@Component
class PaymentRepository {
    private boolean initialized = false;
    @PostConstruct void init() { initialized = true; System.out.println("  [PostConstruct] PaymentRepository ready"); }
    @PreDestroy void cleanup() { System.out.println("  [PreDestroy] PaymentRepository closed"); }
    boolean isInitialized() { return initialized; }
}

@Component
class PaymentService {
    @Autowired private PaymentRepository repository;
    private final AtomicInteger count = new AtomicInteger();

    void process(long amount) {
        count.incrementAndGet();
        System.out.println("  PaymentService.process(" + amount + ") — repo ready: " + repository.isInitialized());
    }
    int count() { return count.get(); }
}

// ═══════════════════════════════════════════════════════════════════════
// MAIN
// ═══════════════════════════════════════════════════════════════════════
public class Phase7Exercises {
    public static void main(String[] args) {
        System.out.println("=== Phase 7 Exercises ===\n");

        // Test Mini IoC
        System.out.println("--- Mini IoC Container ---");
        MiniApplicationContext ctx = new MiniApplicationContext();
        ctx.registerBean(PaymentRepository.class, new PaymentRepository());
        ctx.registerBean(PaymentService.class, new PaymentService());
        ctx.autowire();

        PaymentService svc = ctx.getBean(PaymentService.class);
        svc.process(50000);
        svc.process(100000);
        System.out.println("  Processed: " + svc.count() + " payments");
        ctx.close();

        // Test Mini @Transactional Proxy
        System.out.println("\n--- Mini @Transactional Proxy ---");
        PaymentProcessor target = new PaymentProcessorImpl();
        PaymentProcessor proxy = (PaymentProcessor) Proxy.newProxyInstance(
            target.getClass().getClassLoader(), new Class[]{PaymentProcessor.class},
            new TransactionProxy(target)
        );

        try { proxy.processPayment(50000); } catch (Exception e) { /* expected */ }
        try { proxy.processPayment(200000); } catch (Exception e) { /* expected rollback */ }

        System.out.println("\nAll exercises demonstrated!");
    }
}
