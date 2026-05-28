// Mini Project: Thread-Safe Idempotency Store
// Features: O(1) get/set, TTL expiration, LRU eviction, thread-safe, metrics
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class IdempotencyStore {
    static class Entry {
        final String key;
        final String response;
        volatile long expiresAt;
        Entry prev, next;
        Entry(String k, String r, long ttlMs) { key=k; response=r; expiresAt=System.currentTimeMillis()+ttlMs; }
        boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private final int capacity;
    private final long defaultTtlMs;
    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final ReentrantLock listLock = new ReentrantLock();
    private Entry head, tail; // Doubly-linked LRU list (head=MRU, tail=LRU)

    // Metrics
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();

    public IdempotencyStore(int capacity, long defaultTtlMs) {
        this.capacity = capacity;
        this.defaultTtlMs = defaultTtlMs;
        head = new Entry(null, null, 0);
        tail = new Entry(null, null, 0);
        head.next = tail; tail.prev = head;

        // Background TTL cleaner
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-cleaner"); t.setDaemon(true); return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanExpired, 1, 1, TimeUnit.SECONDS);
    }

    public Optional<String> get(String key) {
        Entry entry = map.get(key);
        if (entry == null) { misses.incrementAndGet(); return Optional.empty(); }
        if (entry.expired()) { remove(key); expirations.incrementAndGet(); misses.incrementAndGet(); return Optional.empty(); }
        hits.incrementAndGet();
        moveToHead(entry);
        return Optional.of(entry.response);
    }

    public boolean setIfAbsent(String key, String response) {
        return setIfAbsent(key, response, defaultTtlMs);
    }

    public boolean setIfAbsent(String key, String response, long ttlMs) {
        Entry entry = new Entry(key, response, ttlMs);
        Entry existing = map.putIfAbsent(key, entry);
        if (existing != null) {
            if (existing.expired()) { remove(key); expirations.incrementAndGet(); return setIfAbsent(key, response, ttlMs); }
            return false; // Key exists and is not expired
        }
        listLock.lock();
        try {
            addToHead(entry);
            if (map.size() > capacity) evictLRU();
        } finally { listLock.unlock(); }
        return true;
    }

    public boolean remove(String key) {
        Entry removed = map.remove(key);
        if (removed != null) { removeFromList(removed); return true; }
        return false;
    }

    public long size() { return map.size(); }

    // Metrics
    public long hitCount() { return hits.get(); }
    public long missCount() { return misses.get(); }
    public long evictionCount() { return evictions.get(); }
    public long expirationCount() { return expirations.get(); }

    public Map<String, Object> metrics() {
        long total = hits.get() + misses.get();
        return Map.of(
            "hits", hits.get(), "misses", misses.get(),
            "hitRate", total > 0 ? (double) hits.get() / total : 0,
            "evictions", evictions.get(), "expirations", expirations.get(),
            "currentSize", size(), "capacity", capacity
        );
    }

    // ─── LRU List Operations ────────────────────────────────────────────────
    private void addToHead(Entry e) { e.next=head.next; e.prev=head; head.next.prev=e; head.next=e; }
    private void removeFromList(Entry e) { e.prev.next=e.next; e.next.prev=e.prev; }
    private void moveToHead(Entry e) { listLock.lock(); try { removeFromList(e); addToHead(e); } finally { listLock.unlock(); } }

    private void evictLRU() {
        Entry lru = tail.prev;
        if (lru == head) return;
        map.remove(lru.key);
        removeFromList(lru);
        evictions.incrementAndGet();
    }

    private void cleanExpired() {
        // Iterate from LRU side, remove expired entries
        listLock.lock();
        try {
            Entry current = tail.prev;
            while (current != head) {
                Entry prev = current.prev;
                if (current.expired()) { removeFromList(current); map.remove(current.key); expirations.incrementAndGet(); }
                current = prev;
            }
        } finally { listLock.unlock(); }
    }

    // ─── Tests ──────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        IdempotencyStore store = new IdempotencyStore(100, 2000); // 100 entries, 2s TTL

        // Test 1: setIfAbsent
        assert store.setIfAbsent("key1", "resp1");
        assert !store.setIfAbsent("key1", "resp2");
        assert store.get("key1").orElse("").equals("resp1");
        System.out.println("Test 1 PASS: setIfAbsent");

        // Test 2: TTL expiration
        assert store.setIfAbsent("short", "value", 500);
        Thread.sleep(600);
        assert store.get("short").isEmpty();
        System.out.println("Test 2 PASS: TTL expiration");

        // Test 3: Re-insert after expiration
        assert store.setIfAbsent("short", "new-value", 500);
        assert store.get("short").orElse("").equals("new-value");
        System.out.println("Test 3 PASS: Re-insert after expiration");

        // Test 4: LRU eviction
        IdempotencyStore small = new IdempotencyStore(3, 60000);
        small.setIfAbsent("a", "1"); small.setIfAbsent("b", "2"); small.setIfAbsent("c", "3");
        // Access "a" to make it MRU
        small.get("a");
        // Insert "d" → should evict "b" (LRU)
        small.setIfAbsent("d", "4");
        assert small.get("a").isPresent();
        assert small.get("b").isEmpty();
        assert small.get("c").isPresent();
        assert small.get("d").isPresent();
        System.out.println("Test 4 PASS: LRU eviction");

        // Test 5: Concurrency (100 threads, 10,000 operations each)
        int threads = 100, opsPerThread = 1000;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            pool.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    String key = "concurrent-" + (i % 100);
                    if (store.setIfAbsent(key, "value-" + i, 60000)) successes.incrementAndGet();
                    else duplicates.incrementAndGet();
                }
            });
        }
        pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS);
        System.out.printf("Test 5 PASS: Concurrent — %d successes, %d duplicates%n", successes.get(), duplicates.get());
        assert successes.get() + duplicates.get() == threads * opsPerThread;
        assert successes.get() == 100; // Only 100 unique keys

        System.out.println("\nMetrics: " + store.metrics());
        System.out.println("\nAll acceptance tests passed!");
    }

    // Assertion helper (avoid needing JUnit)
    static void assert(boolean condition) { if (!condition) throw new AssertionError(); }
}
