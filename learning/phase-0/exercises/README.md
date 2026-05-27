# Phase 0 — Exercises

All exercises should be implemented from scratch. Do not use standard library collections or built-in sort/search functions for the core implementation.

## Exercise Index

### Module 01 — Binary & Computer Architecture

| # | Exercise | File | Difficulty |
|---|----------|------|:----------:|
| 1.1 | Binary/Hex Converter | `binary-converter/` | Easy |
| 1.2 | Floating Point Accuracy Demo | `floating-point/` | Easy |
| 1.3 | Cache Effect Demo (Matrix Multiply) | `cache-demo/` | Medium |
| 1.4 | Endianness Detector | `endianness/` | Easy |
| 1.5 | False Sharing Demo | `false-sharing/` | Hard |
| 1.6 | Pipeline Visualization | `pipeline-viz/` | Medium |

### Module 02 — Data Structures

| # | Exercise | File | Difficulty |
|---|----------|------|:----------:|
| 2.1 | Dynamic Array | `dynamic-array/` | Medium |
| 2.2 | Hash Table | `hash-table/` | Medium |
| 2.3 | LRU Cache | `lru-cache/` | Medium |
| 2.4 | Binary Search Tree | `binary-tree/` | Medium |
| 2.5 | Min-Heap | `min-heap/` | Medium |
| 2.6 | Graph Search (BFS/DFS) | `graph-search/` | Medium |
| 2.7 | Payment Dependency Graph | `dependency-graph/` | Hard |

### Module 03 — Algorithms

| # | Exercise | File | Difficulty |
|---|----------|------|:----------:|
| 3.1 | Complexity Analysis | `complexity-analysis/` | Easy |
| 3.2 | Sorting Algorithms | `sorting/` | Medium |
| 3.3 | Binary Search Variations | `binary-search/` | Medium |
| 3.4 | DP: Coin Change | `coin-change/` | Medium |
| 3.5 | DP: Knapsack (0/1) | `knapsack/` | Medium |
| 3.6 | Graph: Dijkstra | `dijkstra/` | Hard |
| 3.7 | Topological Sort | `topological-sort/` | Hard |

## Exercise Template

Each exercise directory should contain:
```
exercise-name/
├── README.md        # Problem description, expected behavior, test cases
├── solution/        # Your implementation
│   └── Solution.xxx # (language of your choice)
└── tests/           # Test cases (optional, but recommended)
```

## Rules

1. **Implement from scratch** — no standard library collections for the core data structure
2. **Write tests** — verify correctness before claiming completion
3. **Measure performance** — for cache demo, false sharing, and sorting exercises, include timing
4. **Explain** — add comments explaining WHY your implementation works

## Language

Choose ONE language for all exercises (recommended: Java, Python, Go, or TypeScript — one of the platform languages). Using the same language you'll use in later phases builds fluency.
