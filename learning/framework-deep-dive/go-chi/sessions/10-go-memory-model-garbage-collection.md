# Session 10: Go Memory Model — Escape Analysis & Garbage Collection

---

## Why This Topic Exists

Memory in Go is not "objects on the heap, primitives on the stack" — that's a Java/JVM mental model that does not apply. Go's compiler decides stack vs. heap allocation through **escape analysis**, and this decision has profound implications for allocation pressure, GC latency, and production performance.

At the Staff/Principal level, you must be able to: (a) predict whether a variable escapes to the heap by reading code, (b) interpret `go test -benchmem` output, (c) tune the GC for a specific workload using `GOGC` and `GOMEMLIMIT`, (d) diagnose a memory leak by reading `pprof` heap profiles, and (e) explain why Go's GC pauses are sub-millisecond while retaining a pause-the-world mark termination phase.

This session covers the entire memory lifecycle: stack allocation, heap allocation via size classes and spans, escape analysis rules, the tri-color concurrent mark-and-sweep collector, the write barrier, GC pacing, and production debugging.

---

## Mental Model

### Stack vs Heap: The Compiler Decides

In Go, you never ask "should I allocate this on the heap?" The compiler decides. Your mental model should be:

> "If a reference to a variable can outlive the stack frame that created it, the variable **escapes** to the heap."

The escape analysis pass runs at compile time and answers one question for each allocation: "Does any pointer to this value survive the function return?" If yes → heap. If no → stack.

### The Allocation Hierarchy

```
┌──────────────────────────────────────────────┐
│                   Your Code                   │
│  var x Foo       x := &Foo{}      make([]int) │
└────────────────┬──────────┬──────────────────┘
                 │          │
        ┌────────▼──┐  ┌────▼───────────┐
        │  Escape    │  │  Always Heap   │
        │  Analysis  │  │  (large,       │
        │ (compiler) │  │   make, new    │
        └──┬─────┬───┘  │   > limits)    │
           │     │      └────┬───────────┘
     ┌─────▼┐  ┌─▼─────┐    │
     │Stack │  │ Heap  │◄───┘
     └──────┘  └───┬───┘
                   │
        ┌──────────▼──────────┐
        │    Memory Allocator  │
        │  ┌─────────────────┐ │
        │  │ mcache (per-P)  │ │  ← Lock-free, goroutine-local
        │  │ mcentral (shared)│ │  ← Per-size-class, mutex
        │  │ mheap (global)  │ │  ← Global lock, multiple arenas
        │  └─────────────────┘ │
        └──────────────────────┘
```

### GC Algorithm at 10,000 Feet

Go uses a **concurrent, tri-color, mark-and-sweep collector** with a write barrier:

1. **Mark Setup (STW)** — Turn on write barrier, start GC workers
2. **Concurrent Mark** — Trace live objects from roots (goroutine stacks, globals, registers), mark them black. The write barrier intercepts pointer writes so no live object is missed.
3. **Mark Termination (STW)** — Drain remaining work, compute next GC heap size
4. **Concurrent Sweep** — Reclaim unmarked (white) objects, return spans to free lists

The write barrier is the key insight: while the GC is concurrently marking live objects, the mutator (your code) is simultaneously writing pointers. Without a write barrier, a black object could point to a white object that the GC already passed, making the white object incorrectly appear as garbage (the "tri-color invariant" violation). The write barrier ensures that when a pointer is written, the pointed-to object is marked grey or black.

---

## Internal Architecture

### Escape Analysis Rules

The compiler (`cmd/compile/internal/escape`) marks allocations as heap when:

#### Rule 1: Returned Pointer

```go
func newFoo() *Foo {
    x := Foo{Name: "hello"}
    return &x  // ESCAPES: pointer returned to caller
}
```

#### Rule 2: Assigned to Interface

```go
func print(v interface{}) { fmt.Println(v) }

func main() {
    x := 42
    print(x) // x ESCAPES: stored in interface{} (heap-allocated backing)
}
```

Why? `interface{}` is a two-word structure (type pointer + data pointer). The data must outlive `main`'s stack frame — the `fmt.Println` call (or any interface user) may hold a reference.

#### Rule 3: Assigned to Slice of Pointers

```go
func collect() []*Item {
    var items []*Item
    for i := 0; i < 100; i++ {
        item := Item{ID: i} // ESCAPES: pointer stored in slice
        items = append(items, &item)
    }
    return items
}
```

#### Rule 4: Closure Captures Variable

```go
func counter() func() int {
    x := 0
    return func() int { // x ESCAPES: captured by closure
        x++
        return x
    }
}
```

#### Rule 5: Large Variables

```go
func process() {
    var buf [1 << 20]byte // > ~64KB? ESCAPES to heap
    // use buf
}
```

Variables larger than a threshold (platform-dependent, typically larger than the stack guard page) are automatically heap-allocated to avoid stack overflow.

#### Rule 6: Indeterminate Index/Length

```go
func process(n int) {
    arr := make([]byte, n) // n not constant → heap
}
```

#### Non-Escape Optimizations

```go
func process() {
    var x Foo
    p := &x       // p does NOT escape if p doesn't leave the stack frame
    p.Name = "hi"
    fmt.Println(x.Name) // compiler may devirtualize and inline
}
```

### Memory Allocator: Size Classes and Spans

Go's allocator is based on TCMalloc (Google's thread-caching malloc). It uses **size classes** — predefined allocation sizes to reduce fragmentation.

**Size classes** (Python representation of the logic):
```python
# runtime/sizeclasses.go
# 67 size classes defined
# Class 0: 0 bytes
# Class 1: 8 bytes
# Class 2: 16 bytes
# Class 3: 24 bytes
# Class 4: 32 bytes
# ...
# Class 66: 32768 bytes (32 KB)

# Each size class maps to a span class which maps to page count
```

When you allocate 17 bytes, you get a 24-byte slot from size class 3. This wastes 7 bytes, but in exchange, the allocator has O(1) allocation and free.

**Spans**: A span is a contiguous set of memory pages (8KB each on most platforms). Each span holds objects of exactly one size class. For small objects (≤ 32KB), multiple objects fit in one span. For large objects (> 32KB), each object gets its own span.

**The three-tier allocation cache**:

```
┌─────────┐
│  mcache │  Per-P, no locks
└────┬────┘
     │  (empty → refill from mcentral)
     ▼
┌──────────┐
│ mcentral │  Per-size-class, lock per class
└────┬─────┘
     │  (empty → get pages from mheap)
     ▼
┌─────────┐
│  mheap  │  Global lock, manages arenas
└─────────┘
     │
     ▼
┌─────────┐
│   OS    │  mmap / VirtualAlloc
└─────────┘
```

- **mcache**: Each P has its own cache. Allocating a small object is: check mcache for available slot → if slot available, take it (atomic increment of free pointer) → done. No locks, no contention. This is ~10-20 CPU instructions.
- **mcentral**: If mcache is empty, refill from mcentral. mcentral maintains partially-full and fully-empty spans for its size class. The P must acquire a lock for its mcentral (but different P's allocating different size classes don't contend).
- **mheap**: If mcentral has no spans, mheap allocates more pages from the OS (via `mmap` on Linux).

### Tri-Color Marking

The concurrent mark phase classifies every heap object:

- **White**: Not yet visited. Initially, all objects are white. At the end, white objects are garbage.
- **Grey**: Visited, but its outgoing pointers haven't been scanned yet. The grey set is the work queue.
- **Black**: Visited and scanned. All outgoing pointers known. No black object can point to a white object (enforced by write barrier).

```
Initial state:        After GC roots scanned:     After full traversal:
┌────┐                ┌────┐                       ┌────┐
│ W  │                │ B  │ (root → black)        │ B  │
└──┬─┘                └──┬─┘                       └──┬─┘
   │ pointer             │ pointer                    │ pointer
   ▼                     ▼                            ▼
┌────┐                ┌────┐                       ┌────┐
│ W  │                │ G  │ (grey: found,         │ B  │ (black: scanned)
└────┘                └──┬─┘  not yet scanned)      └─┬──┘
                         │                            │
                         ▼                            ▼
                    ┌────┐                        ┌────┐
                    │ W  │                        │ B  │
                    └────┘                        └────┘

Remaining white objects = garbage → swept
```

### The Write Barrier

The problem: while the GC is scanning, the mutator (your code) can write a pointer from a black object to a white object. This would make the white object reachable but it would be swept as garbage.

The write barrier prevents this. When the GC is active and a pointer write happens:

```go
// Conceptual write barrier (actual implementation is lower-level)
func writePointer(slot *unsafe.Pointer, ptr unsafe.Pointer) {
    if writeBarrier.enabled {
        // If we're writing to a black object's slot,
        // shade (mark grey) the object being pointed to
        shade(ptr)
    }
    *slot = ptr
}
```

The actual implementation uses Dijkstra-style write barrier: shade the new value. Any pointer stored into a heap object during marking triggers shading of the pointed-to object, ensuring the invariant holds.

There are also special barriers for stack writes (cheaper, since stacks are scanned at mark termination), and for global variables.

### GC Phases in Detail

```
   ┌──────────┐
   │  Sweep   │ ← concurrent (can overlap with next cycle)
   └────┬─────┘
        │  GC triggered (heap ≥ GOGC% over live heap from last cycle)
        ▼
   ┌──────────────┐
   │ Mark Setup   │ STW #1: enable write barrier, reset GC state
   │ (STW)        │ Duration: ~10-100 μs
   └──────┬───────┘
          │
          ▼
   ┌──────────────┐
   │ Mark (Roots) │ STW: scan goroutine stacks, globals, registers
   │ (STW)        │ Duration: proportional to # of goroutines + globals
   └──────┬───────┘
          │
          ▼
   ┌──────────────┐
   │ Concurrent   │ Goroutines run normally. GC workers (25% of P's)
   │ Mark         │ traverse heap. Write barrier intercepts pointer writes.
   │              │ Duration: proportional to live heap size
   └──────┬───────┘
          │
          ▼
   ┌──────────────┐
   │ Mark Term.   │ STW #2: drain remaining work queue, check invariant
   │ (STW)        │ Duration: ~10-100 μs (typically < 1ms)
   └──────┬───────┘
          │
          ▼
   ┌──────────────┐
   │ Concurrent   │ Sweep proceeds lazily — spans are swept when needed
   │ Sweep        │ for allocation, interleaved with mutator execution
   └──────────────┘
```

**Two STW pauses per cycle**: Mark Setup (~10-100μs) and Mark Termination (~10-1000μs). Total STW is typically < 1ms for most workloads. The "Roots" scan (goroutine stacks) is where most variability comes from — 100,000 goroutines with large stacks can push this to multiple milliseconds.

### GC Pacing

The GC controller (`runtime/mgcpacer.go`) decides:
1. **When to start the next GC cycle**: when heap size exceeds `GOGC`% of the live heap from the previous cycle.
2. **How fast to mark**: adjust the fraction of CPU dedicated to GC marking (25% by default) so marking finishes before the heap fills up.

**GOGC math**:
```
GC trigger = live_heap_after_last_GC * (1 + GOGC/100)

With GOGC=100 (default):
  If live heap is 100MB, next GC triggers at 200MB

With GOGC=200:
  GC triggers at 100MB * 3 = 300MB (less frequent GC, more memory)

With GOGC=50:
  GC triggers at 100MB * 1.5 = 150MB (more frequent GC, less memory)

With GOGC=off:
  GC never triggers — heap grows until OOM
```

**GOMEMLIMIT** (Go 1.19+): Sets a soft memory limit. When the total heap approaches the limit, GC runs more aggressively regardless of GOGC. This prevents OOM in containers with fixed memory budgets.

```bash
GOMEMLIMIT=256MiB ./app    # Never exceed 256MiB heap
GOMEMLIMIT=512MiB GOGC=50 ./app  # Conservative GC with hard cap
```

### Memory Allocation Walkthrough

```go
x := &User{Name: "Alice", Age: 30}
```

1. `User` struct is ~24 bytes (string header 16 bytes + int 8 bytes)
2. Escape analysis: `x` is returned? Assigned to interface? No — but `&User{}` always escapes (taking address of composite literal with `&` is a heap allocation).
3. Size class: 24 bytes → class 3 (24-byte slots)
4. Allocation:
   a. Check current P's mcache for class 3 free slot
   b. If available: take it, increment free pointer, return pointer (10-20 instructions)
   c. If mcache empty: acquire mcentral[3] lock, grab a filled span, refill mcache
   d. If mcentral empty: acquire mheap lock, allocate pages, carve into 24-byte slots

That's it. No free list traversal, no buddy allocator, no best-fit search. The cost is fragmentation (7 bytes wasted in this case), but the allocation speed is effectively free for the common case.

---

## Runtime Behavior

### GC Write Barrier Overhead

When the write barrier is active (during concurrent mark), every pointer write in Go code compiles to extra instructions. This is typically 5-10% throughput reduction during marking. This is the primary cost of Go's GC — not the STW pauses, but the steady-state write barrier overhead.

### Object Finalization

```go
type Resource struct {
    fd int
}

func NewResource() *Resource {
    r := &Resource{fd: openFile()}
    runtime.SetFinalizer(r, func(r *Resource) {
        r.close()
    })
    return r
}
```

A finalizer runs when the GC determines the object is unreachable. However:
- Finalizers run on a dedicated goroutine, serially
- There is no guarantee when (or if) they run
- A finalizer can "resurrect" an object by storing its pointer somewhere
- Use `defer` for deterministic cleanup; finalizers are a last resort

### Heap Growth Under Allocation Pressure

When allocation rate exceeds sweep rate, the heap grows. The GC controller tracks this and increases GC frequency. Eventually, if allocation rate is extremely high (gigabytes/second), the GC can't keep up — this is called "GC thrashing":

```
GC thrashing pattern:
  Heap size: 100MB → 200MB → GC → 102MB live
             → 202MB → 404MB → GC → 103MB live
             → 206MB → 412MB → GC → 104MB live
             → 208MB → 416MB → GC → 105MB live
```

Each GC cycle reclaims very little because allocation rate is near-infinite compared to GC throughput. Fix: reduce allocation rate, increase GOGC, or use `GOMEMLIMIT`.

---

## Request Flow Diagrams

### Allocation Flow

```
   [Go code: var x Foo]
             │
             ▼
    ┌─────────────────────┐
    │ Compile time:        │
    │ Escape analysis      │
    │ → stack or heap?     │
    └──┬──────────────┬────┘
       │ Stack        │ Heap
       ▼              ▼
┌───────────┐  ┌─────────────────────┐
│ SP -= size │  │ newobject(typ)       │
│ zero mem   │  │ → mallocgc()        │
│ (frame     │  └──────────┬──────────┘
│  alloc)    │             │
└───────────┘    ┌────────▼──────────┐
                 │ Size class lookup  │
                 │ (runtime/msize.go)│
                 └────────┬──────────┘
                          │
              ┌───────────▼───────────┐
              │ Is size > 32KB?       │
              │  YES → allocate span  │
              │  directly from mheap  │
              │  NO → use size class  │
              └───────────┬───────────┘
                          │
              ┌───────────▼───────────┐
              │ mcache.alloc(size)    │
              │  Avail? → take slot   │
              │  Empty? → refill()    │
              └───────────┬───────────┘
                          │
              ┌───────────▼───────────┐
              │ mcentral.cacheSpan()  │
              │  Avail? → take span   │
              │  Empty? → grow()      │
              └───────────┬───────────┘
                          │
              ┌───────────▼───────────┐
              │ mheap.alloc(npages)   │
              │  → mmap OS pages      │
              │  → carve into spans   │
              └───────────────────────┘
```

### GC Cycle Flow

```
  [Heap reaches GC trigger threshold]
                │
                ▼
       ┌────────────────┐
       │ gcStart()       │  goroutine that triggered GC
       │  → STW #1       │
       │  → Enable WB    │
       │  → Start work   │
       └───────┬────────┘
               │
       ┌───────▼────────┐
       │ Concurrent Mark │  GC workers pick up root set
       │                 │  Mark objects from stacks
       │                 │  Mark objects from globals
       │                 │  Traverse heap graph
       │                 │  Write barrier shades new refs
       └───────┬────────┘
               │
       ┌───────▼────────┐
       │ gcMarkDone()    │  No more grey objects
       │  → STW #2       │
       │  → Scan stacks  │
       │  → Drain work   │
       │  → Disable WB   │
       │  → Compute next │
       │    GC trigger   │
       └───────┬────────┘
               │
       ┌───────▼────────┐
       │ Concurrent Sweep│  Lazy — spans swept on demand
       │  → Reclaim white│  Background goroutine sweeps
       │    objects       │  unswept spans when idle
       │  → Return pages  │
       │    to OS (optional)
       └────────────────┘
```

---

## Lifecycle Diagrams

### Object Lifecycle

```
   Allocation ──▶ Live (white) ──▶ Live (grey) ──▶ Live (black) ──▶ Dead (white)
                     │                   │                  │              │
                     │  Reachable        │  Marked,         │  Survived    │  Unreachable
                     │  from roots       │  not scanned     │  GC          │  → swept
                     │                   │                  │              │
                     └─── Write barrier shades if stored ───┘              │
                            into black object                              │
                                                                           ▼
                                                                    ┌────────────┐
                                                                    │ Span returned│
                                                                    │ to mcentral  │
                                                                    │ or freed     │
                                                                    └────────────┘
```

### Heap Arena Lifecycle

```
   [OS] ──mmap──▶ [Heap Arena 64MB]
                         │
                         ▼
                  ┌──────────────┐
                  │ Pages carved  │
                  │ into spans    │
                  └──────┬───────┘
                         │
               ┌─────────▼─────────┐
               │ Span allocated     │
               │ to size class N    │
               └─────────┬─────────┘
                         │
              ┌──────────▼──────────┐
              │ Objects allocated    │
              │ and freed in span    │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │ All objects freed    │
              │ Span returned to     │
              │ mheap (may be        │
              │ munmap'd to OS)      │
              └─────────────────────┘
```

---

## Source Code Reading Guide

### Files to Read (in order)

#### 1. `runtime/malloc.go` — Allocation (Start Here)
Key functions and sections:
- `mallocgc(size, typ, needzero)` — the main allocation function (~300 lines). This is what you must read.
- Size class lookup table: `class_to_size[]`, `class_to_allocnpages[]`
- `newobject()` — wrapper around `mallocgc` for single-pointer allocations
- Goroutine-local allocation cache: `mcache` operations
- **What to ignore**: The `cgoInCrash` code, profiling/tracing instrumentation, debug hooks
- **Time investment**: 45 minutes

Key code to study:

```go
func mallocgc(size uintptr, typ *_type, needzero bool) unsafe.Pointer {
    // 1. Check for size 0 — return fixed address
    // 2. Check if GC assist is required (this goroutine must help GC)
    // 3. Get size class (or handle large object path directly)
    // 4. Allocate from mcache
    // 5. If pointer type, check write barrier
    // 6. Zero memory if needed
    // 7. Return pointer
}
```

#### 2. `runtime/mgc.go` — GC Lifecycle
- `gcStart()` — triggers a GC cycle
- `gcBgMarkWorker()` — the goroutine that does concurrent marking
- `gcMarkDone()` — transitions from mark to mark termination
- `gcMarkTermination()` — STW phase
- GC trigger calculation
- **What to ignore**: Pacing internals (`mgcpacer.go`), sweep-specific functions
- **Time investment**: 1 hour

#### 3. `runtime/mgcsweep.go` — Sweeping
- `sweepone()` — sweep one span
- `sweepClass()` — sweep all spans of a size class
- Lazy sweeping: how allocation triggers sweeping of spans it needs
- **Time investment**: 20 minutes

#### 4. `runtime/mcentral.go` — Central Allocation Cache
- `cacheSpan()` — refill mcache from mcentral
- `uncacheSpan()` — return span to mcentral
- Lock management
- **Time investment**: 15 minutes

#### 5. `runtime/mheap.go` — Global Heap
- `alloc(npages, spanclass)` — allocate span from heap
- `free()` — return span to heap
- Arena management: how arenas are mapped and unmapped
- **What to ignore**: Scavenging (returning memory to OS)
- **Time investment**: 20 minutes

#### 6. `runtime/mbarrier.go` — Write Barrier
- `writebarrierptr()` — the actual write barrier
- `bulkBarrierPreWrite()` — bulk barrier for slice copy, typedmemmove
- Different barrier modes: Dijkstra vs. Yuasa
- **Time investment**: 20 minutes

#### 7. `cmd/compile/internal/escape/escape.go` — Escape Analysis (Optional)
- Read if you want to understand the exact escape rules
- Very dense, math-heavy; not required for practical use
- **Time investment**: Skip unless you're implementing a compiler pass

### What NOT to Read
- `runtime/mstats.go` — just the API for `runtime.ReadMemStats()`
- `runtime/msize.go` — generated table of size classes (data, not logic)
- `runtime/mgcwork.go` — GC work buffer internals (queue implementation detail)
- `runtime/mgcscavenge.go` — memory scavenging (returning to OS)

### Complementary Reading
- Go 1.5 GC design document: https://go.dev/s/go15gcpacing
- "Getting to Go: The Journey of Go's Garbage Collector" — Rick Hudson's GopherCon talk

---

## Production Failure Scenarios

### Scenario 1: GC Thrashing Under High Allocation Rate

**Symptom**: Service responds quickly for a few seconds, then latency spikes to 500ms+, then drops. Pattern repeats every few seconds, matching GC cycles. CPU usage is 100%, but throughput is low.

**Root cause**: Allocation rate exceeds what the GC can process. Each GC cycle reclaims very little, heap grows uncontrollably, GC runs more frequently.

**Diagnosis**:
```bash
GODEBUG=gctrace=1 ./app
```

Output shows:
```
gc 1 @0.008s 0%: 0.022+0.17+0.003 ms clock, 0.18+0/0.15/0.050+0.026 ms cpu, 4->4->0 MB, 5 MB goal, 8 P
gc 2 @0.010s 0%: 0.011+0.19+0.002 ms clock, 0.091+0/0.17/0.049+0.022 ms cpu, 4->4->0 MB, 5 MB goal, 8 P
gc 3 @0.013s 0%: 0.013+3.2+0.003 ms clock, ...
```
GC cycles every 2-3ms — that's thrashing.

**Fix**:
1. Reduce allocation rate: use `sync.Pool`, avoid `fmt.Sprintf` in hot paths, pre-size slices
2. Increase GOGC: `GOGC=200` (trades memory for less GC)
3. Set GOMEMLIMIT: `GOMEMLIMIT=512MiB` (soft cap)

### Scenario 2: Memory Leak — Retained Slice

```go
type Cache struct {
    items []*Item
}

func (c *Cache) Load() {
    c.items = c.items[:0] // slice length zeroed... BUT backing array remains!
    for _, id := range fetchIDs() {
        c.items = append(c.items, fetchItem(id))
    }
}
```

The backing array of `c.items` never shrinks. If it grew to 100M elements during peak, it stays 100M even when only 10 elements are needed.

**Fix**: Use `nil` assignment to release the backing array:

```go
func (c *Cache) Clear() {
    c.items = nil // allows GC to collect the backing array
}
```

Or use a ring buffer with fixed capacity.

### Scenario 3: Interface Boxing Allocation Storm

```go
func (s *Service) Process(items []Entity) {
    for _, item := range items {
        s.log.Debug("processing", "id", item.ID) // item.ID (int) boxed to interface{}
    }
}
```

Every log parameter that's not already an `interface{}` is boxed: the compiler allocates a backing value on the heap and constructs an `interface{}` header. In a hot loop, this is millions of allocations per second.

**Fix for structured logging** (e.g., `slog`):
```go
s.log.Debug("processing", slog.Int("id", item.ID)) // avoids boxing in slog
```

### Scenario 4: Large Object Allocation Fragmentation

```go
func processImages(images []image.Image) {
    for _, img := range images {
        buf := make([]byte, img.Width*img.Height*4) // ~8MB for 4K image
        // process buf
    }
}
```

Each 8MB allocation is > 32KB, so it goes directly to mheap as a large object span. If these allocations are interleaved with small allocations that keep their spans alive, the heap becomes fragmented — large free chunks are interleaved with small live ones.

**Fix**: Pool large buffers: `sync.Pool{New: func() interface{} { return make([]byte, 8<<20) }}`

### Scenario 5: GC Assist CPU Starvation

When a goroutine allocates heavily during a GC cycle, it's required to "assist" the GC by doing marking work proportional to its allocation. If a goroutine allocates 100MB during a GC cycle, it must do marking work that would have covered 100MB of allocation.

This means a single goroutine can experience hundreds of milliseconds of GC assist time, appearing as latency spikes.

**Fix**: Reduce per-request allocation. Move allocations to initialization or use pooling.

---

## Debugging Techniques

### 1. GODEBUG=gctrace=1

```
GODEBUG=gctrace=1 ./app
```

Output format:
```
gc 123 @45.678s 2%: 0.022+1.7+0.003 ms clock, 0.18+1.2/1.5/0.050+0.026 ms cpu, 450->451->260 MB, 520 MB goal, 8 P
```

Field breakdown:
| Field | Example | Meaning |
|-------|---------|---------|
| `gc 123` | 123 | GC cycle number |
| `@45.678s` | 45.678 | Seconds since program start |
| `2%` | 2% | CPU spent in GC since program start |
| `0.022+1.7+0.003 ms` | 0.022 | STW Mark Setup (ms) |
| | 1.7 | Concurrent Mark (ms) |
| | 0.003 | STW Mark Termination (ms) |
| `450->451->260 MB` | 450 | Heap before GC |
| | 451 | Heap after GC (typically more — GC found live objects) |
| | 260 | Live heap (actually reachable) |
| `520 MB goal` | 520 | Next GC trigger heap size |
| `8 P` | 8 | GOMAXPROCS |

Red flags:
- Concurrent mark > 10ms for every GC (high live heap)
- STW mark termination > 1ms (check goroutine count)
- Heap after GC ≈ heap before GC (nothing being reclaimed — possible leak)
- Live heap > goal (GC can't keep up — thrashing)
- `%` approaching 25% or more (GC dominates CPU)

### 2. Memory Profiling with pprof

**Heap profile** (what's currently allocated):
```bash
curl -o heap.prof http://localhost:6060/debug/pprof/heap
go tool pprof -top heap.prof
go tool pprof -list=<function> heap.prof
# In interactive mode:
#   top20 — show top allocations
#   list <function> — show annotated source
#   web — open graph visualization
```

**Allocation profile** (what's been allocated, good for hotspots):
```bash
curl -o allocs.prof http://localhost:6060/debug/pprof/allocs
go tool pprof -top allocs.prof
```

**Key pprof flags**:
```
-inuse_space    : Show objects currently allocated (default for heap)
-alloc_space    : Show total allocated (since start)
-inuse_objects  : Count of objects currently allocated
-alloc_objects  : Count of total allocated objects
```

**Interpreting heap profiles**:
- `-inuse_space`: The "steady state" memory. What's currently allocated. Use for memory leak detection.
- `-alloc_space`: The "churn" view. Where allocation happens. Use for allocation reduction.

**Differencing heap profiles**:
```bash
# Take baseline
curl -o base.prof http://localhost:6060/debug/pprof/heap

# Wait 5 minutes under load

# Take current
curl -o current.prof http://localhost:6060/debug/pprof/heap

# Diff
go tool pprof -base base.prof current.prof
# Shows what changed between the two snapshots
```

### 3. Escape Analysis Output

```bash
go build -gcflags="-m -m" ./... 2>&1 | grep "escapes"
```

Double `-m` provides more detail:
```
./main.go:5:6: can inline newFoo
./main.go:6:9: &Foo{...} escapes to heap
./main.go:8:13: ... argument does not escape
```

### 4. Benchmark with Memory

```bash
go test -bench=. -benchmem ./...
```

Output:
```
BenchmarkProcess-8    50000    25000 ns/op    1024 B/op    8 allocs/op
```

- `B/op`: bytes allocated per operation
- `allocs/op`: number of allocations per operation

Zero-allocation hot paths are a target for high-throughput services.

### 5. Finding Memory Leaks

```go
import "runtime"

// Burn-in: force GC to clean up temporary allocations
runtime.GC()

var m1, m2 runtime.MemStats
runtime.ReadMemStats(&m1)

// Run workload for N seconds
time.Sleep(60 * time.Second)

runtime.GC() // force collection
runtime.ReadMemStats(&m2)

fmt.Printf("HeapInuse: %d → %d (Δ = %d)\n", m1.HeapInuse, m2.HeapInuse, m2.HeapInuse-m1.HeapInuse)
fmt.Printf("HeapObjects: %d → %d (Δ = %d)\n", m1.HeapObjects, m2.HeapObjects, m2.HeapObjects-m1.HeapObjects)
```

If `HeapInuse` or `HeapObjects` grows monotonically under steady load, you have a leak.

### 6. GC Tuning Profiling

```bash
GODEBUG=gctrace=1,gcpacertrace=1 ./app
```

`gcpacertrace=1` adds pacing information including assist time per goroutine.

---

## Observability Considerations

### Metrics You Must Track

| Metric | Source | What It Tells You |
|--------|--------|-------------------|
| `go_memstats_heap_alloc_bytes` | `runtime.ReadMemStats()` | Current heap allocation |
| `go_memstats_heap_inuse_bytes` | `runtime.ReadMemStats()` | Heap in use (includes fragmentation) |
| `go_memstats_heap_objects` | `runtime.ReadMemStats()` | Live objects count |
| `go_gc_duration_seconds` (quantile) | Prometheus Go collector | GC pause distribution |
| `go_memstats_gc_cpu_fraction` | `runtime.ReadMemStats()` | % CPU in GC |
| `go_memstats_alloc_bytes_total` | `runtime.ReadMemStats()` | Total allocated (rate = allocation rate) |
| `go_memstats_mallocs_total - go_memstats_frees_total` | `runtime.ReadMemStats()` | Live allocation count |

### Dashboard Alerts

- **GC pause P99 > 10ms**: Check goroutine count; may need to reduce live heap or goroutine stacks
- **allocation rate > 100 MB/s sustained**: Check for allocation in hot paths
- **HeapInuse growing > 5% / hour**: Possible memory leak
- **gc_cpu_fraction > 0.15**: GC takes >15% CPU; reduce allocation or increase GOGC
- **go_memstats_heap_alloc / GOMEMLIMIT > 0.85**: Approaching memory limit; GC about to throttle

### Logging GC Events

```go
import "runtime"

func logGCStats() {
    var stats debug.GCStats
    debug.ReadGCStats(&stats)
    // stats.Pause — slice of STW pause durations
    // stats.PauseEnd — timestamps
    // stats.NumGC — number of GC cycles
}
```

---

## Performance Implications

### Allocation Is the Bottleneck, Not CPU

For most Go services, the primary performance bottleneck is **allocation rate**, not CPU cycles. Each allocation:
1. Takes CPU (even if fast)
2. Creates GC work (marking, sweeping)
3. Increases GC frequency
4. Causes write barrier overhead during marking

A single `fmt.Sprintf("user_%d", id)` in a hot path can dominate CPU through cascading GC effects, even though the allocation itself is microseconds.

### sync.Pool: When It Helps

```go
var bufferPool = sync.Pool{
    New: func() interface{} { return make([]byte, 4096) },
}

func process(data []byte) {
    buf := bufferPool.Get().([]byte)
    defer bufferPool.Put(buf[:cap(buf)]) // reset and return

    copy(buf, data)
    // process buf
}
```

`sync.Pool` eliminates allocations for frequently-used temporary objects. The pool is per-P, lock-free on Get/Put (most of the time), and the GC clears the pool periodically.

**Only use `sync.Pool` when**:
- The object is allocated frequently (hot path)
- The object has a bounded lifetime per use
- The overhead of GC for these objects is measurable

### Pre-Sizing Slices and Maps

```go
// BAD: 10+ allocations as slice grows
var items []Item
for i := 0; i < 10000; i++ {
    items = append(items, fetch(i))
}

// GOOD: 1 allocation
items := make([]Item, 0, 10000)
for i := 0; i < 10000; i++ {
    items = append(items, fetch(i))
}
```

The magic number for map pre-sizing:
```go
// With 1000 elements and default load factor (6.5), you need ~1000/6.5 ≈ 154 buckets
// But the map will grow; pre-size eliminates rehash allocations
m := make(map[string]int, 1000) // good estimate: expected elements
```

### Value vs Pointer Receivers

```go
// Stack-allocated (if T is small and T doesn't escape)
func (t T) Method() { ... }

// T escapes to heap
func (t *T) Method() { ... }

// Mixed: T may escape
func (t T) Method() { ... }
func (t *T) OtherMethod() { ... } // now T.Method() causes escape
```

Rule of thumb: if any method has a pointer receiver, all methods should. But this causes all values to escape when passed to the method. For small, short-lived types used in hot paths, value receivers are faster.

### Stack Growth Impact

Goroutine stacks start at 2KB and grow by copying. Stack growth is triggered when the stack check at function entry detects the stack is nearly full. The `morestack()` routine copies the entire stack to a new (larger) allocation and updates all pointers into the stack.

Stack growth is:
- Fast for small stacks (microseconds)
- Proportional to stack size for large stacks (milliseconds for 1MB+ stacks)
- Can happen during a hot loop if deep call chains cause repeated growth

Avoid deep recursion in goroutines you spawn frequently. Each deep call chain can trigger multiple stack growths.

---

## Architecture Implications

### Design Principle: Avoid Allocation in Hot Paths

Before writing a function that will be called millions of times per second:
1. Count allocations: can this be done with stack-only variables?
2. Pre-allocate: can the caller provide the buffer?
3. Pool: can `sync.Pool` recycle objects?

```go
// BAD: allocates on every call
func FormatID(userID int) string {
    return fmt.Sprintf("user_%d", userID)
}

// BETTER: caller provides buffer
func AppendID(dst []byte, userID int) []byte {
    dst = append(dst, "user_"...)
    return strconv.AppendInt(dst, int64(userID), 10)
}

// BEST: use strings.Builder with pre-allocated capacity
var b strings.Builder
b.Grow(32)
// ...
```

### Design Principle: GC-Friendly Data Structures

- **Slices of values > slices of pointers**: A `[]Item` keeps all data contiguous, which is cache-friendly and requires only one GC root. A `[]*Item` scatters data across the heap, requiring the GC to scan each pointer individually.
- **Maps with integer keys > maps with string keys**: Integer map keys don't need pointer tracking; string keys require scanning.
- **Flat structs > deep pointer graphs**: The GC must traverse every pointer. Deeply nested pointer structures multiply GC work.

```go
// GC-friendly: single allocation, contiguous
type Users struct {
    items []User
}

// GC-unfriendly: N+1 allocations, pointer scan per user
type Users struct {
    items []*User
}
```

### Design Principle: GC as a Design Constraint

The Go GC doesn't just "happen in the background." Its design shapes the language and ecosystem:
- Go has no finalizer guarantees — `defer` is the correct tool
- Go has no weak references — the GC tracks everything
- Go has no generational GC — all objects participate in every cycle
- Go's fast allocation path (stack, then mcache) enables a style of programming where short-lived allocations are cheap

### when to tune GC

| Workload | Recommendation |
|----------|----------------|
| Latency-critical (< 1ms P99) | GOGC=25-50, small heap |
| Throughput-critical (batch) | GOGC=200-500, large heap |
| Memory-constrained (containers) | GOMEMLIMIT=256MiB, GOGC=100 |
| Offline/batch processing | GOGC=off, restart when done |
| Real-time (< 100μs P99) | Go may not be the right choice |

---

## Team Ownership Implications

### Who Owns GC Tuning

The **service team** owns GC configuration for their service. The platform team provides:
- Default GC settings (GOGC, GOMEMLIMIT) in base container images
- Monitoring dashboards for GC metrics
- Documentation and runbooks for GC incident response

The service team adjusts settings based on their workload's memory vs. latency tradeoffs.

### Code Review Checklist for Memory

1. **Allocation in hot paths**: Every allocation in a request handler is N× amplification where N = QPS.
2. **Escape analysis**: Does the variable escape? Does it need to? Can it be stack-allocated?
3. **Pre-sizing**: Are slices/maps created with expected capacity?
4. **sync.Pool misuse**: Are pooled objects properly reset? Are they too large? Are they pooled unnecessarily?
5. **Finalizers**: Does this code use `runtime.SetFinalizer`? Why isn't it using `defer`?
6. **Leak potential**: Does a goroutine hold a reference to a large object? Does a global map grow without bound?

### On-Call: GC Incident Runbook

1. Check `go_memstats_heap_alloc_bytes` — is it growing?
2. Check allocation rate — has it spiked?
3. Check GC pause P99 — is it exceeding SLO?
4. Check goroutine count — high goroutine count → high stack scan time
5. Capture heap profile: `curl localhost:6060/debug/pprof/heap > heap.prof`
6. Check `GODEBUG=gctrace=1` output for thrashing pattern
7. If thrashing: increase GOGC, set GOMEMLIMIT, or reduce allocation
8. If leak: diff heap profiles, identify retaining reference

---

## Interview Questions

### Q1: How does Go decide whether to allocate on the stack or heap?

**Answer**: The Go compiler performs escape analysis at compile time. A variable is heap-allocated if a reference to it can outlive the stack frame — meaning a pointer to it is returned, stored in a heap-allocated structure (interface, slice of pointers, global), or captured by a closure. Variables larger than a threshold are also heap-allocated. The decision is made entirely at compile time; there is no runtime stack/heap selection.

Use `go build -gcflags="-m"` to see escape analysis decisions. The variable "escapes to heap" if the compiler determines it must outlive its declaring function.

### Q2: Explain Go's tri-color mark-and-sweep GC algorithm. What is the write barrier and why is it necessary?

**Answer**: Go uses a concurrent tri-color mark-and-sweep collector. All heap objects start white. GC roots (stacks, globals, registers) are marked grey. The GC iterates: pick a grey object, scan its outgoing pointers, mark referenced objects grey, then mark the scanned object black. When no grey objects remain, all white objects are garbage and can be swept.

The write barrier is necessary because the mutator runs concurrently with the mark phase. If the mutator writes a pointer from a black object (already scanned) to a white object, the GC would never mark that white object, and it would be incorrectly collected as garbage. The write barrier intercepts pointer writes during marking and shades the target object grey, ensuring the tri-color invariant holds.

### Q3: What is GOGC? How does GOMEMLIMIT differ?

**Answer**: `GOGC` (default 100) controls GC frequency. The next GC triggers when the heap reaches `live_heap * (1 + GOGC/100)`. Higher GOGC means less frequent GC but more memory usage. `GOGC=off` disables GC entirely.

`GOMEMLIMIT` (Go 1.19+) sets a soft memory ceiling. When total heap approaches this limit, GC runs more aggressively regardless of GOGC. This prevents OOM in containerized environments with fixed memory budgets. Unlike GOGC (which is relative to live heap), GOMEMLIMIT is an absolute cap.

### Q4: Your service experiences P99 latency spikes of 500ms every 30 seconds. GC pause shows < 2ms. What's happening?

**Answer**: GC STW pauses are < 2ms, so the latency is not from STW. Likely **GC assist**: the goroutine allocates heavily (e.g., serializing a large response) during a GC cycle. The runtime forces this goroutine to do marking work proportional to its allocation — this GC assist time appears as latency on the allocating goroutine, not in the STW pause metric.

Diagnose with `GODEBUG=gctrace=1,gcpacertrace=1` to see assist times. Fix by reducing per-request allocation or pre-computing serialized responses.

### Q5: What's the difference between `-inuse_space` and `-alloc_space` in pprof heap profiles?

**Answer**: `-inuse_space` shows memory currently allocated (live objects). Use for memory leak detection and steady-state memory analysis. `-alloc_space` shows total memory allocated over the program's lifetime (includes freed allocations). Use for finding allocation hotspots — functions that allocate a lot even if they don't hold the memory.

A function that allocates 1GB total but only holds 1MB live has a high `alloc_space` but low `inuse_space`. For reducing GC pressure, you focus on `alloc_space`; for reducing memory footprint, you focus on `inuse_space`.

### Q6: How does Go's memory allocator work? Walk through an allocation of 20 bytes.

**Answer**: 20 bytes falls into size class 3 (24-byte slots).
1. The allocating goroutine's P consults its `mcache` (per-P, lock-free).
2. If the mcache has an available 24-byte slot in the appropriate span, it returns the slot immediately (~10-20 CPU instructions). The free pointer is advanced.
3. If the mcache is empty for that size class, it acquires `mcentral[3].lock` and requests a partially-full or empty span. This span's free list is used to refill the mcache.
4. If mcentral has no spans, it acquires `mheap.lock` and allocates more pages from the OS (via `mmap`), carves them into 24-byte slots in a new span, and returns the span.
5. For objects > 32KB, the path goes directly to mheap (one span = one object), bypassing the size class system.

### Q7: When does a variable NOT escape in Go? Give examples.

**Answer**: A variable does NOT escape when the compiler can prove no reference to it survives the function. Examples:
1. A local variable whose address is never taken
2. A local variable whose address is taken but only used within the function (pointer is not stored beyond the stack frame)
3. Function arguments passed by value (not through interface{})
4. Loop variables that don't outlive the loop iteration
5. A struct passed to a function that is inlined (compiler can eliminate the heap allocation)
6. `sync.Pool` objects that are Put back (the compiler can sometimes stack-allocate them)

Verify with `go build -gcflags="-m"` — look for "does not escape" messages.

### Q8: What is the difference between Go's GC and JVM's G1/ZGC?

**Answer**: 
- **Go GC**: Non-generational, concurrent mark-sweep, no compaction. All objects participate in every GC cycle. No old/new generation split. STW pauses are sub-millisecond for typical workloads. Heap fragmentation can occur over time (no compaction), mitigated by size classes.
- **JVM G1**: Generational (young/old generations). Young GC is fast (copying); old GC is concurrent mark + mixed evacuations. Compacts heap. More complex tuning (many flags). Pause times are tunable.
- **JVM ZGC**: Fully concurrent (including compaction) with colored pointers. Ultra-low pause times (< 1ms, even for multi-terabyte heaps). Requires 64-bit pointers.
- **Go's simplicity advantage**: Single tuning knob (GOGC + GOMEMLIMIT). No generation sizes, survivor ratios, or promotion thresholds to tune.

### Q9: What happens when a goroutine's stack grows at runtime?

**Answer**: Each function prologue checks `SP < g.stackguard0`. If the stack is nearly full, the goroutine calls `morestack()` which:
1. Computes new stack size (typically 2× current, up to max 1GB)
2. Allocates a new stack
3. Copies all active frames from old stack to new stack
4. Adjusts all pointers stored in the stack frames (frame pointer chain enables this)
5. Updates `g.stack` to point to the new stack
6. Returns to the function that triggered the check, now on the new stack

The old stack is freed (it's not referenced by any goroutine). If the goroutine's stack usage drops significantly, the GC may copy the stack to a smaller one during mark termination. The minimum stack size after shrinking is 2KB.

### Q10: How does `sync.Pool` work internally? Why does the GC clear it?

**Answer**: `sync.Pool` maintains a per-P local pool (lock-free) and a shared pool (locked). `Get()` first checks the local P's private cache (single slot), then the local P's shared chain, then steals from another P's shared chain, and finally calls `New()` if configured.

The GC periodically clears all pools because `sync.Pool` has no notion of object lifetime — pooled objects could accumulate indefinitely, referencing and keeping alive large object graphs. The GC clears pools as a safety valve. In Go 1.13+, pool clearing changed: instead of clearing all pools every GC, old pools are cleared after a two-GC-cycle delay (victim cache mechanism), giving objects a longer lifespan in the pool.

---

## Hands-On Exercises

### Exercise 1: Observe Escape Analysis

```go
package main

type User struct {
    Name string
    Age  int
}

func escapeReturn() *User {
    u := User{Name: "Alice", Age: 30}
    return &u // does this escape?
}

func noEscape() User {
    u := User{Name: "Bob", Age: 25}
    return u // does this escape?
}

func escapeInterface(v interface{}) {}

func main() {
    u := User{Name: "Charlie", Age: 35}
    escapeInterface(u) // does this escape?
}
```

Run:
```bash
go build -gcflags="-m" main.go 2>&1 | grep "escapes"
```

Expected output:
```
./main.go:9:9: &u escapes to heap
./main.go:13:9: u does not escape
./main.go:21:20: u escapes to heap
```

### Exercise 2: Interpret GC Trace

Run the following program with GC tracing:

```go
package main

import (
    "fmt"
    "os"
    "runtime"
    "time"
)

func main() {
    os.Setenv("GODEBUG", "gctrace=1")

    // Phase 1: allocate a lot
    fmt.Println("=== Phase 1: Heavy allocation ===")
    var keep [][]byte
    for i := 0; i < 100; i++ {
        data := make([]byte, 1<<20) // 1MB each
        for j := range data {
            data[j] = byte(j % 256)
        }
        keep = append(keep, data)
        time.Sleep(10 * time.Millisecond)
    }

    // Force GC
    runtime.GC()
    time.Sleep(time.Second)

    // Phase 2: drop references
    fmt.Println("=== Phase 2: Drop references ===")
    keep = nil
    runtime.GC()
    time.Sleep(time.Second)

    // Phase 3: allocate again
    fmt.Println("=== Phase 3: Allocate again ===")
    for i := 0; i < 100; i++ {
        data := make([]byte, 1<<20)
        for j := range data {
            data[j] = byte(j % 256)
        }
        keep = append(keep, data)
        time.Sleep(10 * time.Millisecond)
    }

    runtime.GC()
    fmt.Println("Done")
}
```

Observe:
- GC triggers when heap reaches 2× live heap (GOGC=100)
- After `keep = nil`, the live heap drops dramatically
- GC sweeps the freed 100MB
- Phase 3 reuses the heap space (no new OS allocation)

### Exercise 3: Eliminate Allocations in a Hot Path

Start with this function (~5 allocs/op):

```go
func FormatUsers(users []User) []string {
    var result []string
    for _, u := range users {
        line := fmt.Sprintf("User %s (ID: %d) - %s", u.Name, u.ID, u.Role)
        result = append(result, line)
    }
    return result
}
```

Benchmark it:
```bash
go test -bench=. -benchmem
```

Then refactor to zero allocations:
```go
func FormatUsersAppend(dst []string, users []User) []string {
    dst = dst[:0] // reuse slice
    for _, u := range users {
        var b strings.Builder
        b.Grow(64)
        b.WriteString("User ")
        b.WriteString(u.Name)
        b.WriteString(" (ID: ")
        b.WriteString(strconv.FormatInt(u.ID, 10))
        b.WriteString(") - ")
        b.WriteString(u.Role)
        dst = append(dst, b.String())
    }
    return dst
}
```

Compare the benchmarks. Write a pooled version using `sync.Pool` for the `strings.Builder`.

### Exercise 4: Use pprof to Find a Memory Leak

```go
package main

import (
    "net/http"
    _ "net/http/pprof"
    "time"
)

var globalMap = make(map[int][]byte)

func leakyHandler(w http.ResponseWriter, r *http.Request) {
    id := int(time.Now().UnixNano())
    data := make([]byte, 1024*1024) // 1MB
    globalMap[id] = data // LEAK: never deleted
    w.Write([]byte("ok"))
}

func main() {
    go func() {
        http.ListenAndServe(":6060", nil)
    }()

    // Burn-in: generate load
    for i := 0; i < 100; i++ {
        leakyHandler(nil, nil)
    }

    select {} // block forever
}
```

1. Run the program
2. `go tool pprof http://localhost:6060/debug/pprof/heap`
3. In pprof: `top20` — `globalMap` should dominate
4. In pprof: `list leakyHandler` — see the allocation
5. Fix: add map cleanup, expiry, or use a bounded cache (e.g., `groupcache/lru`)

### Exercise 5: Experiment with GOGC Settings

Benchmark the same workload with different GC settings:

```bash
GOGC=25 go test -bench=. -benchtime=30s -benchmem
GOGC=100 go test -bench=. -benchtime=30s -benchmem
GOGC=200 go test -bench=. -benchtime=30s -benchmem
GOGC=off go test -bench=. -benchtime=30s -benchmem
```

Record: ops/sec, B/op, allocs/op, and peak memory usage (use `runtime.MemStats.HeapAlloc` in the benchmark). Plot the trade-off: throughput vs. memory.

---

## Advanced Challenges

### Challenge 1: Write a Custom Allocator

Implement a slab allocator for a specific fixed-size struct:

```go
type SlabAllocator struct {
    // slabs []byte
    // freeList *Node
    // ...
}

func NewSlabAllocator(structSize, slabSize int) *SlabAllocator
func (a *SlabAllocator) Alloc() unsafe.Pointer
func (a *SlabAllocator) Free(ptr unsafe.Pointer)
```

Benchmark against the standard allocator (`&Item{}`) for allocation and GC pause time. Use `go tool trace` to compare GC overhead.

### Challenge 2: Diagnose and Fix a Production Memory Leak

(This requires a real codebase.)
1. Run a production-like workload for 10 minutes
2. Collect heap profiles at t=0, t=5min, t=10min
3. Use `go tool pprof -base t=0.prof t=10min.prof` to identify growing allocations
4. Trace the retaining reference: what holds a pointer to the leaked object?
5. Write a fix and verify with a follow-up profile

### Challenge 3: Implement a Simplified Tri-Color GC

Implement a tri-color mark-and-sweep GC simulator:

```go
type Object struct {
    ID        int
    Mark      Color // White, Grey, Black
    Pointers  []*Object
}

func MarkSweep(roots []*Object, heap []*Object) []*Object {
    // 1. Shade roots → grey
    // 2. While grey set not empty:
    //    a. Pick grey object
    //    b. Shade all its pointers → grey
    //    c. Mark object → black
    // 3. Sweep: collect white objects
}
```

Add a write barrier: simulate concurrent mutation that writes a pointer from a black object to a white object. The write barrier should shade the white object to prevent it from being collected.

---

## Key Insights

1. **Go decides stack vs heap, not you.** Escape analysis is the compiler pass that makes this decision. Learn the rules; use `-gcflags="-m"` to verify.

2. **The fast allocation path is lock-free.** mcache (per-P) provides lock-free allocation for the common case. This is why Go programs can allocate at rates of gigabytes per second.

3. **Go's GC has no generations.** Every GC cycle processes the entire heap. This simplifies tuning but means allocation reduction is the primary optimization lever.

4. **GC pauses are not your biggest GC problem.** The STW pauses are sub-millisecond for most workloads. The bigger cost is: (a) write barrier overhead during concurrent mark, and (b) GC assist time on heavy allocators.

5. **GOGC and GOMEMLIMIT are sufficient for >95% of workloads.** If you think you need more tuning knobs, you probably have an allocation problem, not a GC tuning problem.

6. **Allocation reduction gives compound benefits.** Every allocation eliminated reduces CPU (allocation path), reduces GC frequency, reduces write barrier overhead, and reduces sweep work. A single allocation elimination can cascade into measurable throughput improvement.

7. **Pre-sizing slices and maps eliminates cascading allocations.** A single `make([]T, 0, expectedSize)` can eliminate 10+ reallocation and copy operations.

8. **Read `runtime/malloc.go`'s `mallocgc` function.** ~300 lines. Once you've read it, you'll understand why Go allocations feel free for the common case.

9. **Escape analysis is conservative.** If the compiler is not 100% certain a variable doesn't escape, it puts it on the heap. This means some variables that "should" be on the stack end up on the heap. Profile to find them.

10. **Go memory management is designed for server workloads.** Short-lived allocations are cheap, GC pauses are bounded, and the allocator is optimized for concurrent allocation. This is not a coincidence — Go was built for network servers.

