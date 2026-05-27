// LRU Cache: HashMap + Doubly Linked List, all operations O(1)
import java.util.*;

public class Solution<K, V> {
    private final int capacity;
    private final Map<K, Node> map;
    private final Node head, tail; // sentinel nodes

    class Node { K key; V value; Node prev, next; Node(K k, V v) { key = k; value = v; } }

    public Solution(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
        head = new Node(null, null);
        tail = new Node(null, null);
        head.next = tail; tail.prev = head;
    }

    public V get(K key) {
        Node node = map.get(key);
        if (node == null) return null;
        moveToHead(node);
        return node.value;
    }

    public void put(K key, V value) {
        Node node = map.get(key);
        if (node != null) {
            node.value = value;
            moveToHead(node);
        } else {
            Node newNode = new Node(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            if (map.size() > capacity) {
                Node lru = removeTail();
                map.remove(lru.key);
            }
        }
    }

    public int size() { return map.size(); }

    private void addToHead(Node node) {
        node.next = head.next; node.prev = head;
        head.next.prev = node; head.next = node;
    }

    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node node) { removeNode(node); addToHead(node); }

    private Node removeTail() { Node lru = tail.prev; removeNode(lru); return lru; }

    // --- Tests ---
    public static void main(String[] args) {
        Solution<String, Integer> cache = new Solution<>(3);
        cache.put("a", 1); cache.put("b", 2); cache.put("c", 3);
        assert cache.get("a") == 1; // moves a to head
        cache.put("d", 4); // evicts b (LRU)
        assert cache.get("b") == null;
        assert cache.get("a") == 1 && cache.get("c") == 3 && cache.get("d") == 4;
        cache.get("c"); // moves c to head
        cache.put("e", 5); // evicts a
        assert cache.get("a") == null;
        assert cache.get("c") == 3 && cache.get("d") == 4 && cache.get("e") == 5;
        // update existing
        cache.put("d", 99); assert cache.get("d") == 99 && cache.size() == 3;
        System.out.println("All tests passed!");
    }
}
