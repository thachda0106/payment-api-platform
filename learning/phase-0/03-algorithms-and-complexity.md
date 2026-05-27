# Module 03 — Algorithms & Complexity

## 3.1 Why Algorithm Analysis Matters

Two algorithms can solve the same problem. One takes 1 millisecond. The other takes 1 hour. The difference is algorithmic complexity.

When a payment query goes from 50ms to 5 seconds because the data grew 1000×, that's algorithmic complexity. When Redis Sorted Set operations stay fast at 1 billion entries, that's algorithmic complexity. When your idempotency check goes from O(n) linear scan to O(1) hash lookup, that's a data structure choice driven by complexity analysis.

---

## 3.2 Big-O Notation

Big-O describes how an algorithm's runtime or space usage GROWS as the input size grows. It ignores constants and lower-order terms — it's about the SHAPE of the growth curve.

### Common Complexities

| Notation | Name | n=10 | n=100 | n=1,000 | n=1,000,000 |
|----------|------|:----:|:-----:|:-------:|:-----------:|
| O(1) | Constant | 1 | 1 | 1 | 1 |
| O(log n) | Logarithmic | 3 | 7 | 10 | 20 |
| O(n) | Linear | 10 | 100 | 1,000 | 1,000,000 |
| O(n log n) | Linearithmic | 30 | 700 | 10,000 | 20,000,000 |
| O(n²) | Quadratic | 100 | 10,000 | 1,000,000 | 10¹² |
| O(n³) | Cubic | 1,000 | 1,000,000 | 10⁹ | 10¹⁸ |
| O(2ⁿ) | Exponential | 1,024 | 1.27×10³⁰ | ∞ | ∞ |

**The intuition**: O(log n) grows slowly (doubling n adds ~1 step). O(n) grows proportionally. O(n²) grows quadratically (10× data = 100× time). O(2ⁿ) is impractical for n > ~30.

### How to Analyze

1. **Count operations**, not lines of code
2. **Drop constants**: O(3n + 5) = O(n)
3. **Drop lower-order terms**: O(n² + n) = O(n²) (n² dominates for large n)
4. **Nested loops multiply**: O(n) × O(m) = O(n×m)
5. **Sequential operations add**: O(n) + O(n) = O(n) (still linear)

### Analyze These

```java
// Example 1: O(n) — single loop
for (int i = 0; i < n; i++) { process(i); }

// Example 2: O(n²) — nested loop
for (int i = 0; i < n; i++)
    for (int j = 0; j < n; j++)
        process(i, j);

// Example 3: O(n + m) — two independent inputs
for (int i = 0; i < n; i++) process(i);
for (int j = 0; j < m; j++) process(j);

// Example 4: O(log n) — halving each iteration
for (int i = n; i > 0; i /= 2) process(i);

// Example 5: O(n log n) — log n iterations × n work each
for (int i = 0; i < n; i++)     // O(n) outer
    for (int j = 1; j < n; j*=2) // O(log n) inner
        process(i, j);

// Example 6: O(2ⁿ) — exponential (recursive fibonacci)
int fib(int n) {
    if (n <= 1) return n;
    return fib(n-1) + fib(n-2);  // Two recursive calls → 2ⁿ
}
```

### Space Complexity

Same notation, but counts MEMORY usage. An in-place sort (bubble sort) = O(1) space. Merge sort needs O(n) auxiliary array. Recursive solutions use O(depth) stack space.

### Amortized Analysis

Some operations are occasionally expensive but averaged out are cheap. Example: dynamic array insertion. Most inserts are O(1). When the array is full, resize is O(n). Over n insertions starting from empty: n × O(1) + (1+2+4+...+n/2) × O(1) ≈ O(n). Amortized per-insertion: O(1).

---

## 3.3 Sorting Algorithms

### Comparison

| Algorithm | Average | Worst | Space | Stable | In-Place |
|-----------|:-------:|:-----:|:-----:|:------:|:--------:|
| Bubble Sort | O(n²) | O(n²) | O(1) | Yes | Yes |
| Selection Sort | O(n²) | O(n²) | O(1) | No | Yes |
| Insertion Sort | O(n²) | O(n²) | O(1) | Yes | Yes |
| Merge Sort | O(n log n) | O(n log n) | O(n) | Yes | No |
| Quick Sort | O(n log n) | O(n²) | O(log n) | No | Yes |
| Heap Sort | O(n log n) | O(n log n) | O(1) | No | Yes |
| Timsort | O(n log n) | O(n log n) | O(n) | Yes | No |

**Stable**: Equal elements maintain their relative order. Matters for multi-key sorts (sort by date, then by amount within same date).

**In-Place**: Doesn't require extra memory proportional to input size.

### When to Use Which

- **Small arrays (<50)**: Insertion sort (low overhead)
- **General purpose**: Timsort (Java, Python default — optimized for real-world data with partial ordering)
- **Memory constrained**: Heap sort (in-place, O(n log n) worst-case)
- **Stable required**: Merge sort or Timsort
- **External sorting** (data doesn't fit in memory): Merge sort (process chunks, merge results)

### Why Quick Sort Can Be O(n²)

Quick sort picks a pivot, partitions around it, recursively sorts sub-arrays. If the pivot is always the smallest/largest element (sorted or reverse-sorted input with naive pivot selection), one partition is empty, the other is n-1 elements → O(n²). Mitigation: median-of-three pivot, random pivot, or use introspective sort (switch to heap sort if recursion depth exceeds log n).

**Payment relevance**: NEVER sort by `OFFSET` in SQL queries. It degrades to O(n²) behavior. Use keyset pagination (cursor-based).

---

## 3.4 Searching

### Binary Search

**Precondition**: Array must be SORTED.

**Algorithm**: Compare target with middle element. If target < middle, search left half. If target > middle, search right half. Repeat.

**Complexity**: O(log n). For n = 1,000,000: ~20 comparisons. For n = 1,000,000,000: ~30 comparisons.

**Implementation**:
```java
int binarySearch(int[] arr, int target) {
    int left = 0, right = arr.length - 1;
    while (left <= right) {
        int mid = left + (right - left) / 2;  // Avoid overflow: (left+right)/2 can overflow
        if (arr[mid] == target) return mid;
        if (arr[mid] < target) left = mid + 1;
        else right = mid - 1;
    }
    return -1;
}
```

**Critical bug**: `mid = (left + right) / 2` can overflow for large arrays (left + right > Integer.MAX_VALUE). Use `left + (right - left) / 2`. This bug existed in Java's Arrays.binarySearch for 9 years.

### Hash-Based Search

O(1) average, but: requires precomputed hash, extra space, no ordering, no range queries.

**Payment decision tree**:
- Need O(1) point lookup by key? → Hash table (idempotency check)
- Need ordered traversal or range queries? → B-tree index (ledger entries by date range)
- Need full-text search? → Inverted index (OpenSearch transaction search)
- Need ordered set with range + ranking? → Skiplist (Redis Sorted Set velocity window)

---

## 3.5 Recursion

### What It Is

A function that calls itself. Every recursive solution has:
1. **Base case**: When to stop recursing
2. **Recursive case**: When to call itself with a smaller input
3. **Progress toward base case**: Input must shrink each call

### Factorial

```java
int factorial(int n) {
    if (n <= 1) return 1;           // Base case
    return n * factorial(n - 1);    // Recursive case: n gets smaller each call
}
```

Call stack for `factorial(4)`:
```
factorial(4) → 4 * factorial(3)
                 factorial(3) → 3 * factorial(2)
                                  factorial(2) → 2 * factorial(1)
                                                   factorial(1) → 1 (base case!)
                                                  return 1
                                 return 2 (2 * 1)
                return 6 (3 * 2)
return 24 (4 * 6)
```

### When NOT to Use Recursion

- Deep recursion exceeding stack limit (StackOverflowError)
- Repeated subproblems without memoization (exponential blowup — see fibonacci)
- Tail recursion that your language doesn't optimize (Java doesn't optimize tail recursion; Go doesn't; Python doesn't by default)

### Tail Recursion

A recursive call is in "tail position" if it's the LAST thing the function does. Tail-recursive functions can be optimized by the compiler to use O(1) stack space (reuse the same stack frame).

```java
// NOT tail recursive (multiplies after recursive call returns)
int factorial(int n) { return (n <= 1) ? 1 : n * factorial(n - 1); }

// Tail recursive (result accumulated in parameter)
int factorialTail(int n, int acc) {
    if (n <= 1) return acc;
    return factorialTail(n - 1, acc * n);
}
```

---

## 3.6 Dynamic Programming

### What It Is

Solving problems by breaking them into overlapping subproblems, solving each subproblem ONCE, and storing the result (memoization or tabulation). Avoids exponential recomputation.

### Fibonacci: The Example

**Naive recursive (O(2ⁿ))**:
```java
int fib(int n) { return (n <= 1) ? n : fib(n-1) + fib(n-2); }
// fib(5) calls fib(3) twice, fib(2) three times, fib(1) five times...
// fib(50) would take years.
```

**Memoization (top-down, O(n))**:
```java
Map<Integer, Long> memo = new HashMap<>();
long fib(int n) {
    if (n <= 1) return n;
    if (memo.containsKey(n)) return memo.get(n);
    long result = fib(n-1) + fib(n-2);
    memo.put(n, result);
    return result;
}
```

**Tabulation (bottom-up, O(n), O(1) space)**:
```java
long fib(int n) {
    if (n <= 1) return n;
    long prev2 = 0, prev1 = 1, current = 0;
    for (int i = 2; i <= n; i++) {
        current = prev2 + prev1;
        prev2 = prev1;
        prev1 = current;
    }
    return current;
}
```

### Classic DP Problems

| Problem | Why DP | Complexity |
|---------|--------|:----------:|
| Fibonacci | Overlapping subproblems (fib(3) called many times) | O(n) |
| Knapsack (0/1) | Optimal choice with weight constraint | O(n×W) |
| Longest Common Subsequence | Compare two sequences | O(m×n) |
| Edit Distance (Levenshtein) | Min operations to transform string A → B | O(m×n) |
| Coin Change | Minimum coins to make amount | O(n×amount) |
| Matrix Chain Multiplication | Optimal parenthesization | O(n³) |

### Payment Platform Example — Optimal Fee Route

Given multiple fee structures (flat, percentage, tiered), find the cheapest fee for a given payment amount. This is a DP problem if fee structures can be combined (e.g., cashback promotion + standard fee + interchange).

---

## 3.7 Key Graph Algorithms

### Dijkstra's Algorithm (Shortest Path)

Finds the shortest path from a source node to ALL other nodes in a weighted graph with non-negative edges.

**Algorithm**:
1. Initialize distances: source=0, all others=∞.
2. Use a priority queue (min-heap) ordered by distance.
3. While queue not empty: extract node with minimum distance. For each neighbor: if `distance[current] + edge_weight < distance[neighbor]`, update and push to queue.
4. Result: `distance[target]` = shortest path length.

**Complexity**: O((V + E) log V) with binary heap.

**Payment application**: Find the cheapest FX conversion path. Currencies = nodes, exchange rates = edges. Dijkstra finds the path that maximizes the final amount (minimizes cost).

### Topological Sort

Orders nodes such that for every edge u→v, u comes before v. Only works on DAGs (Directed Acyclic Graphs).

**Algorithm (Kahn's algorithm)**:
1. Compute in-degree (number of incoming edges) for each node.
2. Queue all nodes with in-degree = 0.
3. While queue not empty: dequeue node, add to result. For each neighbor: decrement in-degree. If in-degree becomes 0, enqueue.
4. If result.length < V: there's a CYCLE → topological sort impossible.

**Complexity**: O(V + E).

**Payment application**: Order payment processing steps. The dependency graph "fraud check before fee calc, fee calc before ledger write" must be a DAG. Topological sort gives a valid execution order.

---

## 3.8 Algorithm Design Techniques

| Technique | How It Works | Example |
|-----------|-------------|---------|
| **Divide & Conquer** | Break into subproblems, solve recursively, combine | Merge sort, Quick sort, Binary search |
| **Greedy** | Make locally optimal choice at each step | Dijkstra, Prim's MST, Huffman coding |
| **Dynamic Programming** | Cache overlapping subproblem results | Fibonacci, Knapsack, LCS |
| **Backtracking** | Explore all possibilities, prune impossible paths | N-Queens, Sudoku, subset sum |
| **Sliding Window** | Maintain window over array, update incrementally | Max sum subarray of size K, longest substring without repeating chars |
| **Two Pointers** | Two indices moving through array | Sorted array pair sum, palindrome check, merge sorted arrays |
| **Binary Search on Answer** | Search over possible answer values, not array indices | Find minimum capacity to ship packages in D days |

---

## 3.9 Exercises

### Exercise 3.1 — Complexity Analysis
For each code snippet, determine time and space complexity. Justify your answer.
```java
// A: O(?)
for (int i = 0; i < n; i *= 2) process(i);
// B: O(?)
for (int i = 0; i < n; i++)
    for (int j = i; j < n; j++)
        process(i, j);
// C: O(?)
void recurse(int n) { if (n <= 0) return; recurse(n/2); recurse(n/2); }
```

### Exercise 3.2 — Implement Sorting
Implement merge sort and quick sort from scratch. Test with: random array, sorted array, reverse-sorted array, array with duplicates. Measure and compare performance.

### Exercise 3.3 — Binary Search Variations
Implement:
- `firstOccurrence(arr, target)`: Index of FIRST occurrence of target in sorted array
- `lastOccurrence(arr, target)`: Index of LAST occurrence
- `findInsertPosition(arr, target)`: Index where target would be inserted to maintain order (like `Arrays.binarySearch` but always returns insertion point)
- `findInRotatedArray(arr, target)`: Binary search in a rotated sorted array (e.g., [4,5,6,7,0,1,2])

### Exercise 3.4 — DP: Coin Change
Given coin denominations [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000] (VND) and an amount, find the MINIMUM number of coins needed. Implement both memoization (top-down) and tabulation (bottom-up).

### Exercise 3.5 — DP: Knapsack
Given items with weight and value, and a maximum weight capacity, find the maximum value you can carry. (0/1 Knapsack — you can take each item at most once.)

### Exercise 3.6 — Graph: Dijkstra
Implement Dijkstra's algorithm. Given a graph representing payment routing (nodes = accounts, edges = transfer costs), find the cheapest path from source to destination.

### Exercise 3.7 — Topological Sort
Implement Kahn's algorithm for topological sort. Given a list of payment processing steps and their dependencies, output a valid processing order. Detect and report cycles.

## 3.10 Self-Assessment

- [ ] Can analyze any algorithm's time and space complexity
- [ ] Can implement merge sort, quick sort, and binary search from memory
- [ ] Can explain the difference between memoization and tabulation
- [ ] Can solve a dynamic programming problem without hints
- [ ] Can implement Dijkstra and topological sort on a graph
- [ ] Can identify greedy algorithms vs. DP problems
- [ ] Understand why O(n²) is unacceptable for n > 10,000
- [ ] Can spot when a problem requires DP by identifying overlapping subproblems
