# Hash Table from Scratch

Implement a hash table with separate chaining from scratch. No `HashMap`, `Hashtable`, `Dictionary`, or any standard library map.

## Requirements

Implement a generic hash table `MyHashMap<K, V>` with:

| Method | Description | Complexity |
|--------|-------------|:----------:|
| `put(K key, V value)` | Insert or update key-value pair | O(1) avg |
| `get(K key)` | Retrieve value by key, null if not found | O(1) avg |
| `remove(K key)` | Remove key-value pair | O(1) avg |
| `containsKey(K key)` | Check if key exists | O(1) avg |
| `size()` | Number of entries | O(1) |
| `isEmpty()` | True if empty | O(1) |
| `keys()` | All keys (any order) | O(n) |
| `clear()` | Remove all entries | O(n) |

## Implementation Requirements

1. **Hash function**: Implement your own hash function. For string keys, use polynomial rolling hash: `hash = s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]`. For integer keys, use `key % capacity` or multiplicative hashing.

2. **Collision resolution**: Separate chaining (linked list at each bucket).

3. **Load factor**: Initial capacity = 16. When `size / capacity > 0.75`, resize to `capacity * 2` and rehash all entries.

4. **Generic**: Support any key type that implements `hashCode()` and `equals()` (Java). In other languages, support at least String and Integer keys.

## Starter Code (Java)

```java
public class MyHashMap<K, V> {
    private static class Entry<K, V> {
        K key;
        V value;
        Entry<K, V> next;
        
        Entry(K key, V value, Entry<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
    
    private Entry<K, V>[] buckets;
    private int size;
    private static final int INITIAL_CAPACITY = 16;
    private static final double LOAD_FACTOR = 0.75;
    
    @SuppressWarnings("unchecked")
    public MyHashMap() {
        buckets = (Entry<K, V>[]) new Entry[INITIAL_CAPACITY];
        size = 0;
    }
    
    private int hash(K key) {
        // Your hash function here
        // Use key.hashCode() for generic type, but understand what hashCode() does
        throw new UnsupportedOperationException("Implement me");
    }
    
    private int getBucketIndex(K key) {
        // hash(key) % capacity, but handle negative hashCode values
        throw new UnsupportedOperationException("Implement me");
    }
    
    public void put(K key, V value) {
        // 1. Compute bucket index
        // 2. Check if key already exists in chain (update value)
        // 3. If not, prepend to chain (or append — your choice)
        // 4. Increment size
        // 5. If load factor exceeded, resize
        throw new UnsupportedOperationException("Implement me");
    }
    
    public V get(K key) {
        // 1. Compute bucket index
        // 2. Traverse chain looking for key
        // 3. Return value or null
        throw new UnsupportedOperationException("Implement me");
    }
    
    public V remove(K key) {
        // 1. Compute bucket index
        // 2. Find entry in chain (keep reference to previous for removal)
        // 3. Remove from chain (update previous.next, or head)
        // 4. Decrement size, return removed value
        throw new UnsupportedOperationException("Implement me");
    }
    
    public boolean containsKey(K key) {
        return get(key) != null;
    }
    
    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    
    public List<K> keys() {
        List<K> result = new ArrayList<>();
        for (Entry<K, V> bucket : buckets) {
            for (Entry<K, V> e = bucket; e != null; e = e.next) {
                result.add(e.key);
            }
        }
        return result;
    }
    
    public void clear() {
        Arrays.fill(buckets, null);
        size = 0;
    }
    
    @SuppressWarnings("unchecked")
    private void resize() {
        Entry<K, V>[] oldBuckets = buckets;
        buckets = (Entry<K, V>[]) new Entry[oldBuckets.length * 2];
        size = 0;
        for (Entry<K, V> bucket : oldBuckets) {
            for (Entry<K, V> e = bucket; e != null; e = e.next) {
                put(e.key, e.value);  // Re-insert into new table
            }
        }
    }
}
```

## Test Cases

```java
MyHashMap<String, Integer> map = new MyHashMap<>();

// Test put and get
map.put("one", 1);
map.put("two", 2);
map.put("three", 3);
assert map.get("one") == 1;
assert map.get("two") == 2;
assert map.get("three") == 3;
assert map.get("four") == null;

// Test update
map.put("one", 10);
assert map.get("one") == 10;
assert map.size() == 3;  // Size shouldn't change

// Test remove
assert map.remove("two") == 2;
assert map.get("two") == null;
assert map.size() == 2;
assert map.remove("nonexistent") == null;

// Test containsKey
assert map.containsKey("one");
assert !map.containsKey("two");

// Test resize (load factor)
for (int i = 0; i < 1000; i++) {
    map.put("key" + i, i);
}
assert map.size() == 1002;  // 1000 new + 2 remaining
// Verify a few entries survived
assert map.get("one") == 10;
assert map.get("key500") == 500;

// Test clear
map.clear();
assert map.isEmpty();
assert map.size() == 0;
```

## Analysis Questions

1. What is the time complexity of `put` when the hash table is almost full? How does resizing affect amortized complexity?
2. Why prepend to chain rather than append? (Hint: temporal locality)
3. What happens if `hashCode()` returns the same value for all keys? (Hint: degenerate to linked list)
4. Why is the initial capacity a power of 2? (Hint: `hash % capacity` can be optimized to `hash & (capacity - 1)` when capacity is power of 2)
5. How does Java's `HashMap` handle the case where `hashCode()` is poorly distributed? (Hint: it applies a supplemental hash function to spread bits)
