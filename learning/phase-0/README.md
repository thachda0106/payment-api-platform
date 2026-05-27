# Phase 0 — Computer Science Essentials

> **Duration**: 2-3 weeks (full-time) | **Prerequisites**: Basic programming in any language
>
> **Goal**: Reason about programs at the hardware level. Understand how data structures are stored in memory, how CPUs execute instructions, how caches affect performance, and how to analyze algorithm efficiency.
>
> **Why this matters for the payment platform**: Every performance optimization — JVM GC tuning, PostgreSQL index selection, Redis data structure choice, Kafka producer batching — traces back to concepts in this phase. You cannot optimize what you don't understand.

## Learning Objectives

After completing Phase 0, you will be able to:

1. Explain how a CPU executes instructions (fetch-decode-execute cycle, pipelining, branch prediction)
2. Understand the memory hierarchy (registers → L1/L2/L3 cache → RAM → disk) and why it matters
3. Implement core data structures from scratch (hash table, binary tree, heap, graph)
4. Analyze algorithm complexity using Big-O notation
5. Choose the right data structure for a given problem
6. Explain why a cache miss costs 100x more than a cache hit
7. Understand binary representation (two's complement, IEEE 754 floating point)

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-2 | Module 01 | Binary, bits, bytes, hex, two's complement, IEEE 754 | 6h |
| 3-4 | Module 01 | CPU architecture, instruction cycle, pipelining, branch prediction | 6h |
| 5-6 | Module 01 | Memory hierarchy, cache lines, cache coherence (MESI), TLB, virtual memory intro | 6h |
| 7-9 | Module 02 | Arrays, linked lists, stacks, queues, hash tables | 8h |
| 10-12 | Module 02 | Binary trees, BST, balanced trees, heaps, graphs | 8h |
| 13-15 | Module 03 | Big-O analysis, sorting algorithms, searching, recursion | 8h |
| 16-18 | Module 03 | Dynamic programming, memoization, graph algorithms | 8h |
| 19-21 | Mini Project | In-Memory Database Engine | 10h |

## Prerequisites Check

Before starting, verify you can:
- [ ] Write a function in any language that takes parameters and returns a value
- [ ] Use loops (for, while) and conditionals (if/else)
- [ ] Create and use arrays/lists
- [ ] Understand basic recursion (fibonacci, factorial)
- [ ] Use a text editor or IDE

If any of these are unfamiliar, spend 1-2 days on a basic programming tutorial first.

## How to Use This Phase

1. **Read the module** — understand the theory
2. **Do the exercises** — write code, don't just read
3. **Build from scratch** — implement data structures yourself (don't use standard library for exercises)
4. **Verify understanding** — explain concepts out loud as if teaching someone
5. **Complete the mini project** — integrates everything from this phase

## Resources

- **Primary textbook**: "Computer Systems: A Programmer's Perspective" (Bryant & O'Hallaron), Chapters 1-6
- **Algorithms textbook**: "Introduction to Algorithms" (CLRS), Parts I-III
- **Online course**: MIT 6.006 Introduction to Algorithms (OCW, free)
- **Visualization**: visualgo.net — interactive algorithm visualizations
- **Practice**: LeetCode Easy/Medium problems (50+ recommended)

## Connection to Next Phase

Phase 1 (Operating Systems & Networking) builds directly on:
- CPU scheduling → process/thread scheduling
- Memory hierarchy → virtual memory, page tables
- Cache coherence → concurrent programming primitives
- Binary representation → network byte order, protocol encoding
