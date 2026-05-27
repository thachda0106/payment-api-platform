# Module 02 — Data Structures

## 2.1 Why Data Structures Matter

Data structures are not academic exercises. They are the fundamental building blocks of every system you will build. The B-tree in PostgreSQL that indexes `wallet_balances(account_id)` is a data structure. The Redis sorted set that powers your velocity checker is a skiplist. The Kafka partition log is an append-only array. The JVM heap is a complex data structure with multiple generations.

**This module's rule**: Implement every data structure from scratch. No standard library collections. You already know how to use `HashMap` and `ArrayList`. This module teaches you what's INSIDE them.

---

## 2.2 Arrays

### What They Are

A contiguous block of memory storing elements of the same type. Access by index is O(1) — the CPU computes `base_address + index × element_size` in one instruction.

```
Memory: [42][17][93][ 8][55][  ][  ][  ]
          ↑                       ↑
          index 0                  index 7 (capacity=8, size=5)
```

### Properties

| Operation | Complexity | Why |
|-----------|:----------:|-----|
| Access by index | O(1) | Direct memory offset calculation |
| Insert at end | O(1) amortized | Occasional resize (copy to new array) |
| Insert at beginning/middle | O(n) | Shift all subsequent elements |
| Delete at end | O(1) | Just decrement size |
| Delete at beginning/middle | O(n) | Shift all subsequent elements |
| Search (unsorted) | O(n) | Must check every element |
| Search (sorted) | O(log n) | Binary search |

### Dynamic Array (ArrayList / Vector)

When the array is full and you insert, you must:
1. Allocate a NEW array (typically 2× size)
2. Copy all elements from old array to new array
3. Free old array

This is why `ArrayList.add()` is O(1) AMORTIZED — occasional O(n) resizes, but they happen less frequently as the array grows.

### When to Use

- You need random access by index
- You know the size in advance (or mostly append)
- You iterate frequently, rarely insert/delete in the middle

### Payment Platform Examples

- Kafka partition log: append-only array of records, read by offset (index)
- PostgreSQL page: 8KB array of tuples, accessed by item pointer (index)
- Wallet transaction history: array of transactions, displayed in pages

---

## 2.3 Linked Lists

### What They Are

Nodes scattered in memory, connected by pointers. No contiguous memory — each node points to the next (and optionally previous).

```
HEAD → [42 | ●]──→ [17 | ●]──→ [93 | ●]──→ NULL
        data next    data next    data next
```

### Singly vs Doubly Linked

| | Singly | Doubly |
|---|:-----:|:-----:|
| Forward traversal | ✓ | ✓ |
| Backward traversal | ✗ | ✓ |
| Memory per node | 1 pointer | 2 pointers |
| Delete node | Need previous (O(n)) | O(1) with node reference |

### Properties

| Operation | Array | Linked List |
|-----------|:-----:|:-----------:|
| Access by index | O(1) | O(n) |
| Insert at head | O(n) | O(1) |
| Insert at tail | O(1)* | O(1) with tail pointer |
| Delete at head | O(n) | O(1) |
| Memory overhead | None | 1-2 pointers per element |
| Cache locality | Excellent | Terrible |

**Why arrays win in practice**: Despite O(1) insert/delete at head for linked lists, arrays benefit from cache locality. Traversing an array loads 64-byte cache lines with 16 consecutive elements. Traversing a linked list hits a cache miss at every node. For most workloads, `ArrayList` beats `LinkedList` — even for operations that are theoretically O(n) for arrays.

### When to Actually Use Linked Lists

- You need O(1) insert/delete at BOTH ends (deque)
- You need O(1) insertion in the middle with a reference to the node (LRU cache: move node to head)
- Memory is fragmented and you can't allocate a contiguous block
- You're implementing a lock-free data structure (CAS on pointers)

---

## 2.4 Stacks and Queues

### Stack (LIFO — Last In, First Out)

```
PUSH 42     POP → 55
PUSH 17     ┌────┐
PUSH 93     │ 55 │ ← top
PUSH  8     │  8 │
PUSH 55     │ 93 │
            │ 17 │
            │ 42 │
            └────┘
```

**Operations**: `push(item)` — O(1), `pop()` — O(1), `peek()` — O(1)

**Implemented with**: Array (with top index) or linked list (push/pop at head)

**Uses**: Function call stack, expression evaluation (`3 + 4 × 2` → postfix), undo/redo, bracket matching, depth-first search.

**Payment example**: Saga orchestration state stack. Each saga step pushes state; on failure, pop and execute compensation in reverse order.

### Queue (FIFO — First In, First Out)

```
ENQUEUE 42                            ENQUEUE 17 (new tail)
┌───┬───┬───┬───┬───┐                ┌───┬───┬───┬───┬───┐
│42 │   │   │   │   │                │42 │17 │   │   │   │
└───┴───┴───┴───┴───┘                └───┴───┴───┴───┴───┘
 ↑head    ↑tail                       ↑head         ↑tail

DEQUEUE → 42 (remove from head)
┌───┬───┬───┬───┬───┐
│   │17 │   │   │   │
└───┴───┴───┴───┴───┘
      ↑head    ↑tail
```

**Operations**: `enqueue(item)` — O(1), `dequeue()` — O(1), `peek()` — O(1)

**Implemented with**: Array (circular buffer) or linked list (head and tail pointers). Circular buffer: head and tail indices wrap around. When `(tail + 1) % capacity == head`, the queue is full.

**Uses**: BFS (breadth-first search), task scheduling, print spooler, message queues, Kafka consumer batch processing.

**Payment example**: Outbox relay poller. Read unprocessed events from `outbox_events` into a queue. Dequeue one by one, publish to Kafka, mark as processed.

### Deque (Double-Ended Queue)

Insert/delete at BOTH ends: O(1).

**Uses**: Sliding window algorithms (velocity check: maintain deque of transaction timestamps), undo/redo with depth limit.

---

## 2.5 Hash Tables

### What They Are

A key-value store with O(1) average lookup by key. The magic: hash the key to get an array index, store the value at that index.

### How It Works

```
PUT("user_U1", balance=100000)
  1. hash("user_U1") = 8237492
  2. index = 8237492 % capacity = 4
  3. table[4] = {key: "user_U1", value: 100000}

GET("user_U1")
  1. hash("user_U1") = 8237492
  2. index = 8237492 % capacity = 4
  3. return table[4].value  → 100000
```

### Collision Resolution

What if two keys hash to the same index?

**Chaining** (Java HashMap, Go map):
```
table[4] → [{key:"U1", value:100000}] → [{key:"U5", value:50000}] → NULL
```
Each bucket is a linked list. On collision, append to the list. Lookup: hash → index → traverse list → compare keys.

**Open Addressing** (Python dict, .NET Dictionary):
```
Probe sequence: index, index+1, index+2, ... (linear probing)
              or index+1², index+2², ... (quadratic probing)
              or hash(key, i) for i=0,1,2,... (double hashing)
```
On collision, find the next empty slot. No linked lists — everything in the array. Better cache locality. Deletion requires tombstone markers (can't just null the slot, would break probe chain).

### Load Factor and Rehashing

`load_factor = size / capacity`. When load factor exceeds threshold (default 0.75 for Java HashMap), the table is resized (typically 2×) and ALL entries are rehashed. This is O(n) but amortized to O(1).

**Payment relevance**: The JVM HashMap storing your idempotency response cache resizes under load. If you don't specify initial capacity for a map that you KNOW will have 10,000 entries, it resizes ~12 times during population. Set `new HashMap<>(16000)` (capacity for 10,000 entries at 0.75 load factor) to avoid resizing.

### Hash Function Requirements

1. **Deterministic**: Same key → same hash
2. **Uniform distribution**: Hashes spread evenly across the range
3. **Fast**: Hash computation is on the hot path
4. **Avalanche effect**: Changing 1 bit in the key changes ~50% of hash bits

Java's `String.hashCode()`: `s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]`. Why 31? It's prime, multiplication can be optimized to shift-and-subtract (`31*x = (x << 5) - x`), and it's been used since Java 1.0.

### When to Use

- Key-value lookups (O(1) average)
- Deduplication (check if element exists)
- Counting/frequency (histogram)
- Caching (memoization)
- Symbol tables (compilers, interpreters)

### Payment Platform Examples

- **Idempotency key store**: HashMap<idempotencyKey, Response>. Check if key exists → return cached response.
- **Session cache**: HashMap<sessionId, UserSession>. O(1) session lookup.
- **Fee table**: HashMap<merchantTier, FeeSchedule>. O(1) fee calculation.
- **Connection pool**: HashMap<connectionId, Connection>. Track active connections.

---

## 2.6 Binary Trees

### Binary Search Tree (BST)

A tree where: left subtree < node < right subtree.

```
        50
       /  \
     30    70
    /  \   /  \
   20  40 60  80
```

**Operations**: Search, Insert, Delete — all O(h) where h = height.

**The problem**: Worst case h = n (degenerate tree — all nodes on one side). This happens when inserting sorted data: 20, 30, 40, 50, 60, 70, 80 → effectively a linked list.

**Solution**: Balanced trees.

### Balanced Trees

**AVL Tree**: After every insert/delete, check balance factor `(height(left) - height(right))`. If |balance| > 1, perform rotation (LL, RR, LR, RL). Guarantees h = O(log n). But rotations are expensive for write-heavy workloads.

**Red-Black Tree**: A looser balance constraint. Root is black. Red nodes cannot have red children. Every path from root to leaf has the same number of black nodes. Guarantees h ≤ 2×log(n+1). Fewer rotations than AVL → better for write-heavy workloads. Used by: Java TreeMap, C++ std::map, Linux CFS scheduler.

**B-Tree**: Generalization for DISK-based storage. Each node stores MANY keys (not just 2). A node is one disk page (8KB for PostgreSQL). Degree `t` means each node has t-1 to 2t-1 keys, t to 2t children. Height is `O(log_t n)` — much shorter than binary tree. Fewer disk seeks = faster queries. Used by: PostgreSQL indexes, filesystems (NTFS, ext4, Btrfs).

**PostgreSQL B-tree structure**:
```
Page (8KB):
┌────────────────────────────────────────────────────┐
│ PageHeader │ ItemId[] │ ...free space... │ Tuples  │
│ (24 bytes) │(4 bytes  │                  │ (keys + │
│            │  each)   │                  │  TIDs)  │
└────────────────────────────────────────────────────┘
```
Each page is a B-tree node. Lookup: traverse root → internal page → leaf page. Each hop is one 8KB read from disk (or from shared_buffers if cached). A 4-level B-tree can index billions of rows.

---

## 2.7 Heaps (Priority Queues)

A heap is a complete binary tree where:
- **Max-heap**: parent ≥ children (root = maximum)
- **Min-heap**: parent ≤ children (root = minimum)

```
Min-heap:         Insert 15:         Extract 25:
     10               10                   15
    /  \             /  \                 /  \
   25  30     →     15  30      →      25   30
  /                /  \                /
 50               50  25             50
```

**Operations**: `insert`: O(log n) — bubble up. `extract-min`: O(log n) — swap with last, bubble down. `peek`: O(1).

**Implemented with**: Array. For node at index i: parent = (i-1)/2, left child = 2i+1, right child = 2i+2.

**Uses**: Priority scheduling (highest-priority payment first), Dijkstra's algorithm, heap sort, top-K queries.

**Payment example**: Settlement batch processing. Payments prioritized by amount (largest first → fastest bank settlement). Use max-heap to always process the largest payment next.

---

## 2.8 Graphs

### Representation

```
    A ── B
    │    │
    C ── D
```

**Adjacency List** (preferred for sparse graphs):
```
A → [B, C]
B → [A, D]
C → [A, D]
D → [B, C]
```

**Adjacency Matrix** (preferred for dense graphs):
```
    A B C D
A   0 1 1 0
B   1 0 0 1
C   1 0 0 1
D   0 1 1 0
```

### Key Algorithms

| Algorithm | What It Does | Complexity | Use Case |
|-----------|-------------|:----------:|----------|
| BFS | Level-order traversal, shortest path (unweighted) | O(V+E) | Find shortest payment route |
| DFS | Depth-first traversal, cycle detection | O(V+E) | Detect circular dependencies |
| Dijkstra | Shortest path (weighted, non-negative) | O((V+E)log V) | Find cheapest FX conversion path |
| Bellman-Ford | Shortest path (weighted, negative edges OK) | O(V×E) | Detect arbitrage in FX rates |
| Topological Sort | Linear ordering respecting dependencies | O(V+E) | Order payment processing steps |
| Union-Find | Connected components, cycle detection | O(α(V)) | Detect fraud rings (connected accounts) |
| Kruskal/Prim | Minimum Spanning Tree | O(E log E) | Optimize network topology |

### Payment Platform Examples

- **Fraud ring detection**: Build a graph of accounts that transact with each other. Connected components = potential fraud rings.
- **FX arbitrage detection**: Currencies as nodes, exchange rates as edges. Bellman-Ford to find negative cycles = arbitrage opportunity.
- **Payment dependency graph**: Topological sort of payment processing steps (validate → fraud check → fee calc → ledger write → notify).
- **Service dependency graph**: Which services call which. Used for blast radius analysis and circuit breaker configuration.

---

## 2.9 Exercises

### Exercise 2.1 — Dynamic Array
Implement a dynamic array from scratch with: `add(element)`, `get(index)`, `set(index, element)`, `remove(index)`, `size()`, `capacity()`. Auto-resize when full (2×). Handle resizing correctly (copy elements, don't leak old array).

### Exercise 2.2 — Hash Table
Implement a hash table from scratch using chaining. Support: `put(key, value)`, `get(key)`, `remove(key)`, `containsKey(key)`, `size()`. Implement your own hash function. Handle collisions. Auto-resize when load factor > 0.75.

### Exercise 2.3 — LRU Cache
Implement an LRU (Least Recently Used) cache using a hash table + doubly linked list:
- `get(key)`: Return value, move node to head of list (most recently used)
- `put(key, value)`: Insert/update, move to head. If capacity exceeded, evict tail (least recently used)
- All operations O(1).

### Exercise 2.4 — BST with Traversals
Implement a Binary Search Tree with: `insert`, `search`, `delete`, `inorder`, `preorder`, `postorder`, `levelOrder` (BFS). Implement `height()`, `isBalanced()`, `findMin()`, `findMax()`.

### Exercise 2.5 — Min-Heap
Implement a min-heap using an array. Support: `insert`, `extractMin`, `peek`, `size`, `heapify` (build heap from unsorted array in O(n)).

### Exercise 2.6 — Graph Search
Implement a graph (adjacency list). Implement: `addEdge(u, v)`, `bfs(start)`, `dfs(start)`, `hasPath(start, end)`, `shortestPath(start, end)` (unweighted, using BFS).

### Exercise 2.7 — Payment Dependency Graph
Given a list of payment processing steps with dependencies ("fraud check must complete before fee calc"), use topological sort to find a valid processing order. Detect if a circular dependency exists.

## 2.10 Self-Assessment

- [ ] Can implement a dynamic array with resize from scratch in under 20 minutes
- [ ] Can implement a hash table with chaining from scratch in under 30 minutes
- [ ] Can explain the difference between AVL and Red-Black trees
- [ ] Can explain why B-trees are used for database indexes (not binary trees)
- [ ] Can implement BFS and DFS on a graph without reference
- [ ] Can choose the right data structure for: LRU cache, priority queue, symbol table, graph traversal
- [ ] Understand the trade-off between array cache locality and linked list insertion speed
