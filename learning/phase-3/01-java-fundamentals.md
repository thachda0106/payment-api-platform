# Module 01 — Java Fundamentals & OOP

## 1.1 Java 21 — What's New and Why It Matters

Java 21 is an LTS release. Key features for payment platform development:

### Records (Java 16+)

Immutable data carriers. No boilerplate: constructor, getters, equals, hashCode, toString are auto-generated.

```java
// Before (Java 15-):
public class PaymentResult {
    private final String paymentId;
    private final long amount;
    private final String status;
    // 50+ lines of constructor, getters, equals, hashCode, toString
}

// After (Java 16+):
public record PaymentResult(String paymentId, long amount, String status) {}
```

**Payment use**: DTOs, event payloads, API responses. Records are perfect for immutable data transfer.

### Sealed Classes (Java 17+)

Restrict which classes can extend/implement a type. Compiler knows ALL possible subtypes — enables exhaustive pattern matching.

```java
public sealed interface PaymentStatus permits Pending, Completed, Failed, Refunded {}
public record Pending() implements PaymentStatus {}
public record Completed(long completedAt) implements PaymentStatus {}
public record Failed(String reason) implements PaymentStatus {}
public record Refunded(long refundedAt) implements PaymentStatus {}

// Exhaustive switch — compiler verifies all cases covered
String describe(PaymentStatus status) {
    return switch (status) {
        case Pending p -> "Payment is pending";
        case Completed c -> "Completed at " + c.completedAt();
        case Failed f -> "Failed: " + f.reason();
        case Refunded r -> "Refunded at " + r.refundedAt();
    };
}
```

**Payment use**: Payment state machine. Sealed interface `PaymentState` with permitted subtypes for each state. Compiler guarantees every state is handled.

### Pattern Matching for switch (Java 21)

```java
Object obj = getSomeValue();
String result = switch (obj) {
    case null -> "Got null";
    case String s when s.length() > 10 -> "Long string: " + s.substring(0, 10) + "...";
    case String s -> "Short string: " + s;
    case Integer i -> "Integer: " + i;
    case Long l -> "Long: " + l;
    default -> "Unknown type: " + obj.getClass().getName();
};
```

### Virtual Threads (Java 21 — Production Ready)

Lightweight threads managed by the JVM, not the OS. Millions of virtual threads with minimal memory overhead.

```java
// Platform thread: ~1MB stack, OS-managed, expensive
Thread platformThread = new Thread(() -> processPayment(payment));

// Virtual thread: ~2KB stack to start, JVM-managed, cheap
Thread virtualThread = Thread.startVirtualThread(() -> processPayment(payment));

// ExecutorService with virtual threads
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
executor.submit(() -> processPayment(payment));
```

---

## 1.2 Collections Framework

### Choosing the Right Collection

| Need | Use | Why |
|------|-----|-----|
| Fast O(1) lookup by key | `HashMap` | Hash table, O(1) average |
| Ordered iteration | `ArrayList` | Contiguous memory, cache-friendly |
| No duplicates | `HashSet` | Hash table, O(1) add/contains |
| Sorted keys | `TreeMap` | Red-black tree, O(log n) |
| Thread-safe list | `CopyOnWriteArrayList` | Immutable snapshot on write |
| Thread-safe map | `ConcurrentHashMap` | Lock striping, high concurrency |
| FIFO queue | `ArrayDeque` (as Queue) | Circular array, O(1) |
| Priority queue | `PriorityQueue` | Binary heap, O(log n) |
| LRU cache | `LinkedHashMap` (access-order) | HashMap + doubly-linked list |

### HashMap Internals (Interview-Level)

```java
// HashMap uses:
// - Array of Node<K,V> (buckets)
// - Chaining for collision resolution (linked list, converted to Red-Black tree at 8+ nodes)
// - Load factor 0.75 (resize when 75% full)
// - Capacity always power of 2 (hash & (capacity-1) instead of %)
// - Treeify threshold: 8 (switch to tree to prevent O(n) degradation from hash collisions)
// - Untreeify threshold: 6 (switch back to list when tree shrinks)

// Common mistake: using mutable objects as keys
Map<Payment, String> map = new HashMap<>();
Payment p = new Payment(...);
map.put(p, "value");
p.setAmount(999);  // MUTATES KEY! p.hashCode() changes!
map.get(p);  // Returns null! Key can't be found at new hash bucket.
```

### ConcurrentHashMap

Unlike `HashMap`, `ConcurrentHashMap`:
- Does NOT lock the entire map
- Uses lock striping (multiple segments, each with its own lock) — in Java 8+, uses CAS + synchronized on bucket head
- `putIfAbsent`, `computeIfAbsent`, `compute`, `merge` — atomic compound operations
- Iterators are weakly consistent (don't throw ConcurrentModificationException)
- Does NOT allow null keys or values (unlike HashMap)

---

## 1.3 Generics

### Type Erasure

Java generics are COMPILE-TIME only. At runtime, type parameters are erased.

```java
List<String> strings = new ArrayList<>();
List<Integer> integers = new ArrayList<>();
// At runtime: BOTH are just List (raw type)
// strings.getClass() == integers.getClass() → true

// This is why you CANNOT do:
// if (obj instanceof List<String>)  // Compile error
// T t = new T();  // Compile error (type not known at runtime)

// Bounded type parameters
public <T extends Comparable<T>> T max(List<T> list) { ... }

// Wildcards
void processPayments(List<? extends Payment> payments) { ... }  // Producer (read)
void addRefund(List<? super Refund> refunds) { ... }             // Consumer (write)
// PECS: Producer Extends, Consumer Super
```

---

## 1.4 Streams & Lambdas

### Stream Pipeline

```java
// Find top 5 users by total completed payment amount
List<UserSummary> topSpenders = payments.stream()
    .filter(p -> p.status() == PaymentStatus.COMPLETED)
    .collect(Collectors.groupingBy(Payment::userId,
        Collectors.summingLong(Payment::amount)))
    .entrySet().stream()
    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
    .limit(5)
    .map(e -> new UserSummary(e.getKey(), e.getValue()))
    .toList();

// Stream operations:
// - Intermediate (lazy): filter, map, flatMap, distinct, sorted, peek, limit, skip
// - Terminal (eager): forEach, collect, reduce, count, anyMatch, findFirst, toList

// Common mistake: modifying source during stream → undefined behavior
// Common mistake: using parallel() without measuring → overhead > benefit for small datasets
// Common mistake: side effects in peek() or map() → not guaranteed to execute
```

---

## 1.5 Exception Handling

```java
// Checked exceptions: MUST be caught or declared. Used for recoverable errors.
try {
    Files.readString(Path.of("settlement.csv"));
} catch (IOException e) {
    // Retry logic or user notification
}

// Unchecked exceptions: Runtime exceptions. Programming errors.
// NullPointerException, IllegalArgumentException, IllegalStateException

// try-with-resources (AutoCloseable — automatically closed)
try (Connection conn = dataSource.getConnection();
     PreparedStatement stmt = conn.prepareStatement(sql)) {
    // Resources auto-closed in reverse order
}

// NEVER do this:
catch (Exception e) {
    e.printStackTrace();  // Swallows exception, no recovery
}
// ALWAYS: log + rethrow, or handle gracefully with fallback
```

---

## 1.6 Java I/O and NIO

### Traditional I/O (java.io) — Blocking

```java
// Reading a file line by line
try (BufferedReader reader = new BufferedReader(new FileReader("data.csv"))) {
    String line;
    while ((line = reader.readLine()) != null) {
        process(line);
    }
}
```

### NIO (java.nio) — Non-blocking, Buffers, Channels

```java
// Memory-mapped file (zero-copy)
try (FileChannel channel = FileChannel.open(Path.of("large_file.dat"), StandardOpenOption.READ)) {
    MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    // Access file contents directly from memory — no read() syscalls
}

// Selector — I/O multiplexing (like epoll)
Selector selector = Selector.open();
ServerSocketChannel serverChannel = ServerSocketChannel.open();
serverChannel.configureBlocking(false);
serverChannel.register(selector, SelectionKey.OP_ACCEPT);
// ... event loop with selector.select()
```

**Payment relevance**: Kafka uses MappedByteBuffer for zero-copy log segment reads. Netty (used by Spring WebFlux, gRPC) uses NIO Selector for high-throughput network I/O.

---

## 1.7 Exercises

### Ex 1.1 — Sealed Payment States
Define the payment state machine using sealed interfaces/classes. Implement `processPayment()` that pattern-matches on state and returns the next state.

### Ex 1.2 — Stream Processing
Given a list of 100,000 Payment records, use Streams to: (a) filter COMPLETED payments, (b) group by merchant, (c) calculate total amount per merchant, (d) sort by total descending, (e) return top 10. Compare performance with non-stream implementation.

### Ex 1.3 — Generic Repository
Implement a generic `Repository<T, ID>` interface with `findById`, `save`, `delete`, `findAll`. Implement `InMemoryPaymentRepository`. Ensure type safety at compile time.

### Ex 1.4 — I/O Processing
Read a 1GB CSV file of payment transactions line by line using buffered I/O. Compute running statistics (count, sum, avg). Memory usage must stay under 50MB.

---

## 1.8 Self-Assessment

- [ ] Can explain how records differ from classes and when to use each
- [ ] Can implement a sealed interface hierarchy with exhaustive pattern matching
- [ ] Can choose the right collection for: LRU cache, thread-safe counter, sorted unique set, blocking queue
- [ ] Understand PECS (Producer Extends, Consumer Super) for generic wildcards
- [ ] Can write a stream pipeline with filter, map, collect, and groupBy
- [ ] Understand checked vs unchecked exceptions and when to use each
- [ ] Know the difference between java.io and java.nio approaches
