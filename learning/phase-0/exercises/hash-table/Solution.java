// Hash Table with separate chaining — no java.util.* Map implementations
import java.util.*;

public class Solution<K, V> {
    private Entry<K, V>[] buckets;
    private int size;
    private static final int INITIAL = 16;
    private static final double LOAD_FACTOR = 0.75;

    static class Entry<K, V> {
        K key; V value; Entry<K, V> next;
        Entry(K k, V v, Entry<K, V> n) { key = k; value = v; next = n; }
    }

    @SuppressWarnings("unchecked")
    public Solution() { buckets = (Entry<K, V>[]) new Entry[INITIAL]; size = 0; }

    private int hash(K key) {
        int h = key == null ? 0 : key.hashCode();
        return h ^ (h >>> 16); // spread high bits to low bits (Java HashMap technique)
    }

    private int bucketIndex(K key) { return (buckets.length - 1) & hash(key); }

    public void put(K key, V value) {
        int idx = bucketIndex(key);
        for (Entry<K, V> e = buckets[idx]; e != null; e = e.next) {
            if (eq(key, e.key)) { e.value = value; return; }
        }
        buckets[idx] = new Entry<>(key, value, buckets[idx]); // prepend
        size++;
        if (size > buckets.length * LOAD_FACTOR) resize();
    }

    public V get(K key) {
        for (Entry<K, V> e = buckets[bucketIndex(key)]; e != null; e = e.next)
            if (eq(key, e.key)) return e.value;
        return null;
    }

    public V remove(K key) {
        int idx = bucketIndex(key);
        Entry<K, V> prev = null;
        for (Entry<K, V> e = buckets[idx]; e != null; e = e.next) {
            if (eq(key, e.key)) {
                if (prev == null) buckets[idx] = e.next;
                else prev.next = e.next;
                size--;
                return e.value;
            }
            prev = e;
        }
        return null;
    }

    public boolean containsKey(K key) { return get(key) != null; }
    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public List<K> keys() {
        List<K> result = new ArrayList<>();
        for (Entry<K, V> b : buckets)
            for (Entry<K, V> e = b; e != null; e = e.next) result.add(e.key);
        return result;
    }

    public void clear() { Arrays.fill(buckets, null); size = 0; }

    @SuppressWarnings("unchecked")
    private void resize() {
        Entry<K, V>[] old = buckets;
        buckets = (Entry<K, V>[]) new Entry[old.length * 2];
        size = 0;
        for (Entry<K, V> b : old)
            for (Entry<K, V> e = b; e != null; e = e.next) put(e.key, e.value);
    }

    private boolean eq(K a, K b) { return a == null ? b == null : a.equals(b); }

    // --- Tests ---
    public static void main(String[] args) {
        Solution<String, Integer> m = new Solution<>();
        m.put("one", 1); m.put("two", 2); m.put("three", 3);
        assert m.get("one") == 1 && m.get("two") == 2 && m.get("three") == 3;
        assert m.get("four") == null;
        m.put("one", 10); assert m.get("one") == 10 && m.size() == 3;
        assert m.remove("two") == 2 && m.get("two") == null && m.size() == 2;
        assert m.remove("nonexistent") == null;
        assert m.containsKey("one") && !m.containsKey("two");
        for (int i = 0; i < 1000; i++) m.put("key" + i, i);
        assert m.size() == 1002 && m.get("key500") == 500 && m.get("one") == 10;
        m.clear(); assert m.isEmpty() && m.size() == 0;
        // null key support
        m.put(null, 42); assert m.get(null) == 42; assert m.containsKey(null);
        System.out.println("All tests passed!");
    }
}
