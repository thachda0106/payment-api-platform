# Sorting Algorithms — Merge Sort & Quick Sort

Implement merge sort and quick sort from scratch. Compare their performance on different input types.

## Requirements

### Merge Sort
```java
public static void mergeSort(int[] arr)
public static void mergeSort(int[] arr, int left, int right)
private static void merge(int[] arr, int left, int mid, int right)
```

- Stable sort (preserves relative order of equal elements)
- O(n log n) time, O(n) space (auxiliary array)
- Recursive divide-and-conquer

### Quick Sort
```java
public static void quickSort(int[] arr)
public static void quickSort(int[] arr, int low, int high)
private static int partition(int[] arr, int low, int high)
```

- In-place, O(log n) stack space
- O(n log n) average, O(n²) worst case
- Use median-of-three pivot: pick arr[low], arr[mid], arr[high], use median as pivot
- Not stable (equal elements may change relative order)

### Benchmark Runner
```java
public static void benchmark() {
    int[] sizes = {1000, 10000, 100000, 1000000};
    String[] types = {"random", "sorted", "reverse", "few_unique"};
    
    for (int n : sizes) {
        for (String type : types) {
            int[] arr = generateArray(n, type);
            // Clone array for each sort
            // Time mergeSort
            // Time quickSort
            // Time Arrays.sort() (Timsort) for comparison
            // Print results
        }
    }
}
```

### Expected Results

- On random arrays: Quick sort slightly faster than merge sort (less memory allocation)
- On sorted arrays: Naive quick sort (first-element pivot) → O(n²). Median-of-three → O(n log n)
- On reverse arrays: Same pattern as sorted
- On few_unique: Both perform well, but Timsort excels (it detects runs)
- Timsort (Arrays.sort) beats both for nearly all inputs

## Analysis Questions

1. Why does quick sort become O(n²) on sorted input with naive pivot selection?
2. How does median-of-three prevent the worst case?
3. Why is merge sort stable but quick sort is not?
4. When would you choose merge sort over quick sort despite the extra memory?
5. How does introsort (used by C++ std::sort) combine quick sort and heap sort to guarantee O(n log n) worst-case while keeping quick sort's speed?
