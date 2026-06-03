# Session 09: Go Runtime — GMP Scheduler & Goroutine Internals

---

## Why This Topic Exists

Every Go program runs atop a runtime scheduler that manages goroutines, OS threads, and logical processors. At the Staff/Principal level, understanding the GMP scheduler is not optional — it is the foundation for diagnosing production latency, preventing goroutine leaks, sizing connection pools, and reasoning about concurrency correctness under load.

A Staff engineer who cannot explain what happens after `go f()` has no authority to approve a PR that spawns 50,000 goroutines in a hot path. A Principal who cannot read `GODEBUG=schedtrace=1000` output cannot diagnose why 100 CPUs sit idle while request latency spikes.

This session builds from first principles: the scheduler data structures, the work-stealing algorithm, the role of `sysmon()`, the mechanics of preemption, and the netpoll integration that makes Go's network servers so fast. By the end, you will be able to trace a goroutine from creation to termination through every scheduler state transition.

---

## Mental Model

### The GMP Trinity

Think of the Go scheduler as a **restaurant kitchen**:

| Symbol | Concept | Kitchen Analogy |
|--------|---------|-----------------|
| **G** | Goroutine | A recipe ticket (task to execute) |
| **M** | Machine (OS thread) | A cook (the physical executor) |
| **P** | Processor (logical CPU) | A workstation (cook + stove + tools) |

A cook (M) cannot work without being assigned to a workstation (P). A workstation (P) must have a cook (M) to operate. A recipe ticket (G) goes to a workstation's local queue, and the cook at that workstation executes it.

Key invariants:
- **GOMAXPROCS** = number of P's (default: `runtime.NumCPU()`)
- **M count** ≥ P count (there are always spare M's to handle blocking syscalls)
- **G count** = all live goroutines (can be millions)
- An M must hold a P to execute Go code
- An M can release its P when blocking in a syscall, allowing another M to take that P

### The Two Queues

```
                    ┌──────────────────┐
                    │  Global Run Queue │  ← Slow path, lock-protected
                    └────────┬─────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   ┌────▼─────┐        ┌────▼─────┐        ┌────▼─────┐
   │  P[0]    │        │  P[1]    │        │  P[2]    │
   │          │        │          │        │          │
   │ Local RQ │        │ Local RQ │        │ Local RQ │
   │ [G1,G2]  │        │ [G3]     │        │ [G4,G5]  │
   └──────────┘        └──────────┘        └──────────┘
```

Every P has a **local run queue** (lock-free, capacity 256). The **global run queue** is a shared queue protected by a mutex. The scheduler prefers the local queue; the global queue is the fallback.

---

## Internal Architecture

### Core Data Structures

#### The `g` Struct (`runtime/runtime2.go`)

```go
type g struct {
    stack       stack          // stack bounds (lo, hi)
    stackguard0 uintptr        // compare for stack overflow check
    _panic      *_panic        // innermost panic
    _defer      *_defer        // innermost defer
    m           *m             // current M
    sched       gobuf          // saved registers for context switch
    atomicstatus uint32        // goroutine status
    goid        int64          // goroutine ID
    waitreason  waitReason     // if blocked, why
    // ... many more fields
}
```

The `gobuf` (go buffer) stores the register state when a goroutine yields:

```go
type gobuf struct {
    sp   uintptr   // stack pointer
    pc   uintptr   // program counter
    g    guintptr  // goroutine pointer
    ctxt unsafe.Pointer // closure context
    ret  uintptr   // return value for syscall
    lr   uintptr   // link register
    bp   uintptr   // base pointer
}
```

When a goroutine is descheduled, all its live registers spill into `gobuf`. On reschedule, they are restored. This is a userspace context switch — no kernel involvement, no full register save/restore of the OS thread.

#### The `m` Struct

```go
type m struct {
    g0          *g             // goroutine with scheduling stack
    curg        *g             // currently running goroutine
    p           puintptr       // attached P (nil when blocked in syscall)
    nextp       puintptr       // pending P to attach
    oldp        puintptr       // P released during syscall
    mOS                          // OS-specific data (thread handle, signal mask)
    locks       int32          // lock count for preemption tracking
    // ...
}
```

Critical detail: each M has a **g0 goroutine** — the scheduling goroutine. This g0 runs with a fixed stack and handles scheduling logic. When `schedule()` runs, it's executing on the M's g0, not on any user goroutine. This avoids the risk of stack overflow during scheduling.

#### The `p` Struct

```go
type p struct {
    m           muintptr       // back-link to attached M (nil if idle)
    runq        [256]guintptr  // lock-free local run queue (circular buffer)
    runnext     guintptr       // single next goroutine (bypasses runq)
    runqhead    uint32         // head index for runq
    runqtail    uint32         // tail index for runq
    gFree       struct {       // free goroutine list (recycling)
        n       int32
        gList   gList
    }
    sudogcache  []*sudog       // cached sudog structures
    mcache      *mcache        // per-P memory cache
    // ...
}
```

The `runnext` field deserves special attention. It's a single-slot cache that bypasses the run queue. When `runnext` is set, the next goroutine to run comes from there — not the runq. This enables goroutine affinity: a goroutine that unblocks (e.g., from a channel receive) can be placed directly into `runnext` so it resumes immediately on the same P, keeping CPU caches warm.

### Global Scheduler State

```go
type schedt struct {
    lock        mutex
    midle       muintptr       // idle M list
    nmidle      int32          // count of idle M's
    pidle       puintptr       // idle P list
    npidle      int32          // count of idle P's
    runq        gQueue         // global run queue
    runqsize    int32          // size of global run queue
    // GC state, defer pool, etc.
}
```

The global `sched` variable holds all idle M's and P's, plus the global run queue.

---

## Runtime Behavior

### Goroutine Lifecycle States

```
                     go f()
                        │
                        ▼
     ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
     │  _Gidle  │──▶│_Grunnable│──▶│_Grunning│──▶│ _Gdead   │
     └──────────┘   └────┬─────┘   └────┬─────┘   └──────────┘
                         │              │
                         │              ▼
                         │         ┌──────────┐
                         │         │_Gsyscall │  (blocked in syscall)
                         │         └────┬─────┘
                         │              │
                         │              ▼
                         │         ┌──────────┐
                         └─────────│_Grunnable│  (wakes up)
                                   └──────────┘
                                          ▲
                                          │
              ┌──────────┐   ┌──────────┐  │
              │_Gwaiting │──▶│_Grunnable│──┘
              └──────────┘   └──────────┘
              (channel recv, mutex, select, time.Sleep, I/O, GC)
```

Status values (from `runtime/runtime2.go`):
- `_Gidle` (0): just allocated, not yet initialized
- `_Grunnable` (1): on a run queue, ready to execute
- `_Grunning` (2): executing on an M
- `_Gsyscall` (3): executing a system call
- `_Gwaiting` (4): blocked (channel, mutex, network I/O, timer, GC)
- `_Gdead` (6): terminated, available for reuse
- `_Gcopystack` (8): stack is being copied (GC or growth)

Transition rules:
- Only `_Grunnable` goroutines are on run queues
- Only one goroutine per P can be `_Grunning`
- A goroutine in `_Gwaiting` is stored in the wait structure (e.g., channel's `recvq`, timer heap)
- When a goroutine becomes `_Grunnable`, it's placed on the current P's `runnext` if possible

### The `schedule()` Function Walkthrough

This is the heart of the scheduler (`runtime/proc.go`). Every M calls `schedule()` in a loop — it never returns:

```
schedule() {
    1. Check if GC is waiting to stop the world (gcwaiting)
       → If yes, call gcstopm(), block until GC done

    2. Check if there's a goroutine waiting in runnext
       → If yes, dispatch it immediately

    3. Try to find a runnable goroutine:
       a. Check local runq (lock-free dequeue)
       b. Check global runq (with sched.lock, take up to 1/len(allp) goroutines)
       c. Try netpoll — check for network I/O completions
       d. Try work stealing from other P's
       e. If still nothing, enter the idle loop

    4. If we found a goroutine, execute it:
       execute(gp, inheritTime=false)

    5. If not found, the M goes idle:
       a. Try to spin briefly (check for GC work)
       b. Put P on idle list
       c. Block M on a futex/semaphore
}
```

### Work Stealing Algorithm

Work stealing is the reason Go achieves near-linear CPU utilization. When a P's local queue is empty, it tries to steal from another P:

```
findrunnable() {
    // Phase 1: check local
    if runnext != nil { return runnext }
    if runq is not empty { return dequeue from runq }

    // Phase 2: check global (every 61st invocation to ensure fairness)
    if sched.runqsize > 0 {
        n := min(len(globq), GOMAXPROCS)
        n = max(n, len(globq) / GOMAXPROCS + 1)
        steal n goroutines from global runq
    }

    // Phase 3: check netpoll
    list := netpoll(0) // non-blocking poll
    if list != nil { inject list into global runq; return one from it }

    // Phase 4: steal from random P
    for i := 0; i < 4; i++ {
        stealP := allp[rand() % len(allp)]
        if steal from stealP.runq (take half) { return one }
    }

    // Phase 5: steal from random P's runnext
    for i := 0; i < 4; i++ {
        stealP := allp[rand() % len(allp)]
        if steal from stealP.runnext { return it }
    }

    // Phase 6: poll network with timeout
    list := netpoll(some_timeout)
    if list != nil { return one }

    // Phase 7: give up, M goes idle
    stopm()
}
```

The steal operation takes **half** the victim's run queue (rounded up), not just one goroutine. This amortizes the steal cost and prevents pathological ping-pong.

The random victim selection prevents all idle P's from hammering the same busy P.

### Preemption

Go's preemption story has two eras:

**Cooperative Preemption (Go ≤ 1.13):**
The compiler inserts stack-check instructions in function prologues. When a goroutine's `stackguard0` is set to `stackPreempt` (by sysmon), the next function call triggers a stack check, discovers preemption is requested, and calls `morestack()` which calls `goschedImpl()`. This means goroutines that never call functions (tight loops with no function calls) could starve the scheduler indefinitely.

**Signal-Based Preemption (Go 1.14+):**
The runtime sends `SIGURG` to an M that has been running the same goroutine too long. The signal handler sets `g.preempt = true` and the goroutine yields. This is **asynchronous preemption** — no function call needed. The signal is masked while the goroutine is in the runtime itself, atomic operations, or GC critical sections.

Preemption is NOT triggered by:
- Running goroutine time < 10ms
- Goroutine is in the runtime (e.g., in `mallocgc`, `channel send/recv`)
- Goroutine is in a `cgo` call
- Goroutine holds locks

### sysmon() — The System Monitor

`sysmon()` is a special M that runs without a P (it doesn't need one — it never executes Go code). It runs in an infinite loop, sleeping between iterations. Its responsibilities:

1. **Netpoll** — checks for network completions every few ms
2. **Preemption** — if a P has been running the same G for > 10ms, flag it for preemption
3. **Handoff** — if an M is blocking in a syscall and its P has accumulated pending goroutines, hand off the P to another M
4. **Timer processing** — move expired timers to the ready queue
5. **GC triggering** — if the heap has grown beyond the next GC threshold, trigger GC
6. **Scavenging** — return unused memory to the OS
7. **Retaking P from syscall** — if an M has been in syscall for > 20μs, its P can be taken

### netpoll

netpoll is Go's answer to the C10K problem. Instead of one OS thread per network connection (which doesn't scale), Go uses one goroutine per connection. The netpoll mechanism uses epoll (Linux), kqueue (macOS/BSD), or IOCP (Windows) to wait for events across thousands of file descriptors without blocking an OS thread per socket.

When a goroutine calls `conn.Read()`:
1. The fd is registered with epoll (if not already)
2. If data is not immediately available, the goroutine parks itself on the fd's wait list
3. The M that was running the goroutine goes back to `schedule()` to find other work
4. When data arrives, the netpoll (called by sysmon or an idle P) finds the goroutine and marks it runnable

This means a server with 100,000 idle connections uses ~100,000 goroutines (which is cheap) but only a handful of OS threads. The same design in a thread-per-connection model would require 100,000 OS threads, each consuming ~1MB+ of stack, crashing the kernel.

---

## Request Flow Diagrams

### What Happens When `go f()` Executes

```
 [caller goroutine]
        │
        │  go f()
        ▼
┌─────────────────────────────────────┐
│ Compiler translates to:             │
│   newproc(funcPC(f), closure_ptr)   │
│   → calls newproc1()                │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 1. Acquire goroutine from free list │
│    (gFree list on current P)        │
│    or allocate new g & stack (2KB)  │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 2. Initialize g:                    │
│    - Set goid (atomic inc)          │
│    - Set stack bounds               │
│    - Set sched.pc = funcPC(f)       │
│    - Set sched.sp = stack top       │
│    - g.atomicstatus = _Grunnable    │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 3. Enqueue goroutine:               │
│    Try runnext first (fast path)    │
│    If runnext full → local runq     │
│    If local runq full → global runq │
│    (plus half of local runq moved   │
│     to global to make room)         │
└──────────────┬──────────────────────┘
               │
               ▼
        [return to caller]
        [new goroutine will run when scheduled]
```

### Context Switch: Goroutine Yield

```
   [Running goroutine G1]
           │
           │  channel <- val  (receiver not ready)
           ▼
┌──────────────────────────────────────┐
│ 1. G1 blocked on channel:            │
│    gopark() → mcall(park_m)          │
│    This switches to M's g0 stack     │
│    Saves G1's registers to gobuf     │
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│ 2. On g0 stack, call schedule():     │
│    - G1 is now _Gwaiting             │
│    - G1 is on channel's sendq        │
│    - findrunnable() looks for next G │
└──────────────┬───────────────────────┘
               │
               ▼
┌──────────────────────────────────────┐
│ 3. Found G2 in local runq:           │
│    execute(G2, false)                │
│    gogo(&G2.sched)                   │
│    - Restore G2's registers          │
│    - Jump to G2's saved PC           │
│    - G2 is now _Grunning             │
└──────────────────────────────────────┘
```

### Channel Unblock Flow

```
  [G1 sends on ch]
       │
       │  (found waiting receiver G2)
       ▼
┌──────────────────────────────────────┐
│ 1. Direct handoff (no scheduler):    │
│    - Copy value directly to G2       │
│    - Set G2 to _Grunnable            │
│    - Put G2 on current P's runnext   │
│    - G1 continues running            │
└──────────────────────────────────────┘
```

The direct handoff optimization means that if a receiver is already waiting, the sender atomically copies the value and wakes the receiver — no scheduler involvement, no queues.

---

## Lifecycle Diagrams

### Goroutine Lifecycle: From Birth to Death

```
                     Time ──────────────────────────────────────────▶

┌─────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌─────┐
│Idle │──▶│Runnable  │──▶│Running   │──▶│Waiting   │──▶│Runnable  │──▶│Dead │
│     │   │(on runq) │   │(on M/P)  │   │(channel) │   │(on runq) │   │     │
└─────┘   └──────────┘   └────┬─────┘   └──────────┘   └──────────┘   └─────┘
                              │                              ▲
                              │                              │
                              │  time.Sleep, I/O            │
                              ▼                              │
                         ┌──────────┐   timer fires ────────┘
                         │Waiting   │
                         │(timer)   │
                         └──────────┘
```

### P State Machine

```
                    ┌──────────┐
          ┌────────▶  _Pidle  │◀────────┐
          │         └────┬─────┘         │
          │              │               │
          │  acquirep()  │  releasep()   │
          │              ▼               │
          │         ┌──────────┐         │
          │         │_Prunning │         │
          │         └────┬─────┘         │
          │              │               │
          │              │  exitsyscall  │
          │              ▼               │
          │         ┌──────────┐ ────────┘
          │         │_Psyscall │
          │         └──────────┘
          │
          │  (expelled by sysmon —
          │   P is stolen while M
          │   blocked in syscall)
          └─────────────────────┘
```

### M State Machine (Simplified)

```
  [new M created]
       │
       ▼
  ┌──────────┐    acquirep()    ┌──────────┐
  │ spinning │ ───────────────▶ │ running  │
  │ (no P)   │                  │ (has P)  │
  └──────────┘                  └────┬─────┘
       ▲                             │
       │                   entersyscall()
       │                       │
       │    handoffp()          ▼
       │              ┌──────────────┐
       └──────────────│  blocking    │─── exitsyscall() ──▶ try to reacquire P or park
                      │  (no P, in   │
                      │   syscall)   │
                      └──────────────┘
       ▲
       │
  ┌──────────┐       stopm()        ┌──────────┐
  │ sleeping │◀────────────────────│  idle    │
  │ (no P)   │   (no work found)   │ (has P   │
  └──────────┘                      │  but no G)│
       │                            └──────────┘
       │  startm()
       └──────────────────────────────
```

---

## Source Code Reading Guide

### Files to Read (in order)

#### 1. `runtime/runtime2.go` — Data Structures (Read First)
- Lines that define `struct g`, `struct m`, `struct p`, `gobuf`
- `waitReason` constants — understand why goroutines block
- `status` constants (`_Gidle`, `_Grunnable`, etc.)
- **What to ignore**: `sudog`, `trace`, `schedt` fields unrelated to scheduling
- **Time investment**: 20 minutes

#### 2. `runtime/proc.go` — The Scheduler (Core File)
Most important functions, ordered by importance:

| Function | Lines (approx) | What It Does |
|----------|---------------|--------------|
| `schedule()` | ~20 | One round of scheduling — never returns |
| `findrunnable()` | ~200 | The work-stealing loop |
| `execute()` | ~80 | Bind G to M and P, then `gogo` |
| `newproc1()` | ~140 | Goroutine creation |
| `goexit0()` | ~40 | Goroutine teardown |
| `stopm()` | ~30 | Park an M when no work exists |
| `startm()` | ~50 | Wake/spawn an M for a P |
| `handoffp()` | ~40 | Transfer a P when M blocks in syscall |
| `wakep()` | ~30 | Wake an M when new work appears |
| `globrunqget()` | ~40 | Fetch from global run queue |
| `runqsteal()` | ~50 | Steal from another P's runq |
| `sysmon()` | ~200 | The system monitor goroutine |
| `retake()` | ~60 | sysmon's preemption logic |

- **What to ignore**: GC-related code, cgo entry/exit, `panic`/`recover` paths, tracing code
- **Time investment**: 2-3 hours (don't try to understand every line; focus on the functions above)

#### 3. `runtime/proc.go` — netpoll integration
- `netpoll(blocking)` function lookup (defined in platform-specific files)
- Where `schedule()` calls `netpoll()`
- How sysmon calls `netpoll()`
- **What to ignore**: Platform-specific netpoll implementations (epoll/kqueue)

#### 4. `runtime/stack.go` — Stack Management
- `newstack()` — stack growth
- `stackfree()` — stack shrinkage
- `copystack()` — GC stack copy
- **What to ignore**: Debug printing functions

#### 5. `runtime/os_linux.go` (or your platform) — OS Thread Management
- `newosproc()` — OS thread creation (calls clone())
- `futexsleep()` / `futexwake()` — M parking

### What NOT to Read
- Don't read `runtime/trace.go` unless you're implementing tracing
- Don't read platform-specific assembly files (`rt0_linux_amd64.s`) — you don't need them
- Don't read the cgo code in `cgocall.go` — separate topic
- Don't read GC code in `mgc.go` or `mgcmark.go` — that's Session 10

---

## Production Failure Scenarios

### Scenario 1: Goroutine Leak — Channel Never Closed

```go
// BUG: ch is never closed, goroutine blocks forever
func fetchAll(ids []int) []Result {
    ch := make(chan Result)
    for _, id := range ids {
        go func(id int) {
            result, err := db.Query(id)
            ch <- result // blocks forever if nobody reads
        }(id)
    }
    // reader goroutine exits early on error
    for i := 0; i < 5; i++ {
        r := <-ch
        if r.Error != nil {
            return nil // BUG: remaining goroutines leak
        }
    }
}
```

**Production symptoms**: Goroutine count grows monotonically. `pprof/goroutine` shows hundreds of goroutines blocked on `chansend`. Memory grows (each goroutine holds its stack plus heap references). Eventually OOM.

**Fix**: Use `context.Context` with cancellation, use `errgroup`, or buffer the channel to `len(ids)`.

### Scenario 2: Too Many Goroutines Blocking on I/O

```go
// BUG: 10K goroutines each blocking on a SQL query
for _, user := range users {
    go func(u User) {
        rows, _ := db.Query("SELECT * FROM orders WHERE user_id = ?", u.ID)
        process(rows)
    }(user)
}
```

**Production symptoms**: 10K goroutines in `_Gsyscall` or `_Gwaiting` (waiting for network). M count spikes to GOMAXPROCS + blocking count. OS thread count can hit thousands — Linux kernel struggles. P's keep getting handed off, creating new M's.

**Fix**: Limit concurrency with a semaphore channel or worker pool:

```go
sem := make(chan struct{}, 100) // max 100 concurrent
for _, user := range users {
    sem <- struct{}{}
    go func(u User) {
        defer func() { <-sem }()
        // query
    }(user)
}
```

### Scenario 3: GOMAXPROCS Misconfiguration

Setting `GOMAXPROCS=1` on a 64-core machine: only one P exists. All goroutines compete for one P. Work stealing is impossible. Throughput is ~1/64 of potential.

Setting `GOMAXPROCS=256` on a 16-core machine: 256 P's, but only 16 CPUs. Excessive context switching, cache thrashing. The scheduler steals constantly. Net negative.

**Default is correct for nearly all workloads.** Only change `GOMAXPROCS` if you have benchmarking data proving it helps.

### Scenario 4: Timer Goroutines Accumulating

```go
// BUG: creates a timer per request, doesn't stop it
func handler(w http.ResponseWriter, r *http.Request) {
    timer := time.AfterFunc(30*time.Second, func() {
        // never runs if request completes in time
        db.CancelQuery(queryID)
    })
    // ... process request, return
    // BUG: timer not stopped, goroutine and timer leak
}
```

**Fix**: Always `timer.Stop()` in defer. Use `time.NewTimer()` + `Stop()` instead of `time.AfterFunc`.

### Scenario 5: Deadlock in init()

```
fatal error: all goroutines are asleep - deadlock!

goroutine 1 [chan send]:
main.init()
    /app/main.go:8 +0x45
```

The main goroutine (which runs `init()` and `main()`) blocks. If no other goroutines can proceed, the runtime detects deadlock. Common causes: channel operations in `init()` with no receiver, mutex deadlock in `init()`.

---

## Debugging Techniques

### 1. GODEBUG=schedtrace

```
GODEBUG=schedtrace=1000 ./app
```

Output format:
```
SCHED 1000ms: gomaxprocs=8 idleprocs=4 threads=12 spinning=0 idlethreads=0 runqueue=0 [1 2 0 0 0 3 0 1]
```

| Field | Meaning |
|-------|---------|
| `gomaxprocs` | Number of P's (GOMAXPROCS) |
| `idleprocs` | P's with nothing to run |
| `threads` | Total M's (OS threads) |
| `spinning` | M's spinning waiting for work |
| `idlethreads` | M's parked (sleeping) |
| `runqueue` | Goroutines in global queue |
| `[0 0 0 0 0 0 0 0]` | Per-P local run queue lengths |

**Diagnosis rules**:
- High `idleprocs` — not enough parallelism; check for serial bottleneck
- High `runqueue` — goroutines are being pushed to global (local queues overflowing)
- `threads` >> `gomaxprocs` — many goroutines blocking in syscalls; check I/O
- Per-P queues all empty but `runqueue` high — work stealing not happening (shouldn't occur)

### 2. Goroutine Dumps

Send `SIGQUIT` (Ctrl+\ on terminal, `kill -QUIT <pid>`) to a running Go process:

```
goroutine 1 [running]:
main.main()
    /app/main.go:15

goroutine 35 [chan receive]:
main.worker(0xc00001e0c0)
    /app/worker.go:20 +0x8c

goroutine 36 [IO wait]:
internal/poll.runtime_pollWait(0x7f..., 0x72)
    /usr/local/go/src/runtime/netpoll.go:343
net.(*netFD).Read(0xc0001c8000, ...)
    ...
```

Count goroutines by state:
```
grep "\[" goroutine_dump.txt | sort | uniq -c | sort -rn
```

Common states to watch:
- `[chan receive]` / `[chan send]` — blocked on channel
- `[IO wait]` — blocked on network I/O
- `[select]` — blocked in select statement
- `[sync.Mutex.Lock]` — blocked on mutex
- `[sleep]` — `time.Sleep`
- `[syscall]` — blocking system call

### 3. `pprof` Goroutine Profile

```go
// Endpoint: /debug/pprof/goroutine?debug=1
// or: /debug/pprof/goroutine?debug=2 (full stack traces)

// In code:
import _ "net/http/pprof"
go http.ListenAndServe(":6060", nil)

// CLI:
go tool pprof http://localhost:6060/debug/pprof/goroutine
```

### 4. go tool trace

```
curl -o trace.out http://localhost:6060/debug/pprof/trace?seconds=5
go tool trace trace.out
```

The trace viewer shows:
- Goroutines as horizontal bars, colored by state
- Procs as lanes showing what each P is doing
- Syscalls, network poll, GC
- Goroutine creation (parent → child)
- Channel operations (send → recv)

### 5. Detecting Goroutine Leaks

```go
import "runtime"

func getGoroutineCount() int {
    return runtime.NumGoroutine()
}

// Before sending traffic:
initial := runtime.NumGoroutine()

// After steady-state, wait 30s:
// (GC must clean up dead goroutines)
time.Sleep(30 * time.Second)
final := runtime.NumGoroutine()

if final - initial > threshold {
    log.Printf("WARNING: goroutine leak detected: %d → %d", initial, final)
}
```

Better approach: Use `github.com/uber-go/goleak` in tests:

```go
func TestHandler(t *testing.T) {
    defer goleak.VerifyNone(t)
    // test code
}
```

---

## Observability Considerations

### Metrics You Must Track

| Metric | Why | Instrumentation |
|--------|-----|----------------|
| `go_goroutines` | Goroutine count — rising trend = leak | `runtime.NumGoroutine()` |
| `go_threads` | OS thread count — should be stable | `runtime.ThreadCreateProfile()` |
| `go_gc_duration_seconds` | GC pauses | `debug.ReadGCStats()` |
| `go_memstats_alloc_bytes` | Heap allocation rate | `runtime.ReadMemStats()` |

### Dashboards

- Goroutine count over time (line chart) — alarm if monotonically increasing
- Stack of goroutine states (stacked area) — sudden shift to `chan receive` = channel bottleneck
- Thread count vs GOMAXPROCS — alarm if ratio > 3×
- Per-instance goroutine count — uneven distribution suggests work routing bug

### When to Alert

- `go_goroutines` > 100,000 per instance (tune per workload)
- `go_threads` > 500 (tune per workload; default max is 10,000)
- `go_goroutines` growing > 10% per minute for > 10 minutes
- GOMAXPROCS idleprocs > 50% for sustained period

---

## Performance Implications

### Goroutine Cost

| Resource | Goroutine | OS Thread |
|----------|-----------|-----------|
| Initial stack | 2 KB | ~1 MB (default on Linux) |
| Creation time | ~2-3 μs | ~10-20 μs |
| Context switch | ~0.2-0.5 μs (userspace) | ~1-5 μs (kernel) |
| Memory on block | Stack only (shrinks) | Full stack remains |
| Max count | Millions (practical) | Thousands (practical) |

At 100,000 goroutines:
- Stack memory: ~200 MB (if all stacks at minimum 2KB; real-world is higher)
- Scheduler overhead: negligible (< 1% CPU)
- Context switches: invisible to OS; all userspace

### Benchmark: Goroutine Throughput

```go
func BenchmarkGoroutineCreation(b *testing.B) {
    for i := 0; i < b.N; i++ {
        go func() {}()
    }
}
```

Typical result: ~50-80 ns per goroutine creation on modern hardware. Compare to `std::thread` at ~10,000 ns — goroutines are 100-200× faster to create.

### Throughput Impact of Run Queue Overflow

When a P's local run queue (capacity 256) overflows, the runtime moves half the local queue to the global queue. This involves acquiring `sched.lock`, a global mutex. Under extreme load (many short-lived goroutines being created, e.g., recursive parallel divide-and-conquer), this lock becomes a bottleneck.

**Mitigation**: Use bounded parallelism patterns:

```go
// Instead of unbounded goroutine spawn:
for _, item := range items {
    go process(item) // may spawn millions
}

// Use worker pool:
sem := make(chan struct{}, runtime.GOMAXPROCS(0))
for _, item := range items {
    sem <- struct{}{}
    go func(it Item) {
        defer func() { <-sem }()
        process(it)
    }(item)
}
```

---

## Architecture Implications

### Design Principle: Don't Manage Threads

Go's scheduler handles OS thread management. Your code should never create OS threads directly. Express parallelism as goroutines, let the scheduler map them to threads.

**Corollary**: Never use `runtime.LockOSThread()` unless absolutely necessary (e.g., Cocoa GUI, some OpenGL bindings). It pins the goroutine to an OS thread, defeating the scheduler's ability to optimize.

### Design Principle: Concurrency Over Parallelism

The scheduler makes even single-GOMAXPROCS concurrent patterns efficient. A program with 10,000 goroutines on 1 CPU correctly multiplexes them with zero explicit thread management. This means correct concurrent code is cheap — don't avoid goroutines out of fear.

### When to Use sync.Pool

Goroutines that allocate heavily benefit from `sync.Pool`. Each goroutine can create per-goroutine garbage, but `sync.Pool` allows recycling objects across goroutines, reducing GC pressure. This is only valuable in hot paths — don't prematurely pool.

### Connection Pool Sizing

Given N goroutines needing database connections, the optimal pool size is NOT N. It's typically:

```
pool_size = min(db_max_connections, num_cores, goroutine_concurrency_limit)
```

The scheduler multiplexes N goroutines over `GOMAXPROCS` threads. Only `GOMAXPROCS` goroutines can be executing simultaneously on CPU. If all goroutines are waiting on database I/O, they're parked (not consuming CPU). So the pool needs only enough connections to keep `GOMAXPROCS` goroutines busy doing actual work, plus enough to overlap with network latency.

---

## Team Ownership Implications

### Who Owns Performance

The team that writes the most goroutines owns the scheduler behavior. If your service spawns goroutines in a hot HTTP handler, your team owns the resulting scheduler contention. The platform team provides monitoring and defaults (GOMAXPROCS, GC tuning) but cannot fix a goroutine leak in application code.

### Code Review Checklist for Goroutine Usage

Every PR review that spawns goroutines must verify:
1. **Lifetime**: Is this goroutine bounded? Will it exit? When?
2. **Cancellation**: Does it respect a context? Can it be canceled?
3. **Panic isolation**: Does a panic in this goroutine crash the entire program? (Yes, unless recovered.)
4. **Concurrency limit**: Is there a bound on how many of these goroutines can exist?
5. **Backpressure**: If this goroutine produces work faster than it's consumed, what happens?

### On-Call Knowledge Requirements

Every on-call engineer must be able to:
1. Run `curl localhost:6060/debug/pprof/goroutine?debug=2` and interpret the output
2. Count goroutines by state using `grep` on the goroutine dump
3. Identify a goroutine leak from a monotonically increasing `go_goroutines` metric
4. Send `SIGQUIT` to a running process and not panic when it doesn't crash (it just dumps goroutines)
5. Use `go tool trace` to identify scheduler contention

---

## Interview Questions

### Q1: What happens when you call `go f()`? Trace the entire path.

**Answer**: `go f()` is compiled to a call to `runtime.newproc()` which calls `newproc1()`. This function:
1. Acquires a goroutine struct from the P's free list (or allocates a new one)
2. Initializes the goroutine's stack (2KB initially), sets `sched.pc = funcPC(f)`, `sched.sp = stack top`
3. Sets `atomicstatus = _Grunnable`
4. Tries to place it on current P's `runnext` field (fast path)
5. If `runnext` is occupied, appends to local runq (capacity 256)
6. If local runq is full, moves half to global runq, places new G in local
7. The goroutine will run whenever the scheduler picks it up — no immediate execution

### Q2: How does work stealing work? What data structure is used for local run queues?

**Answer**: Each P has a lock-free, bounded (256-slot) circular buffer as its local run queue. When `findrunnable()` can't find work locally:
1. Checks global run queue (with lock)
2. Calls `netpoll(0)` for ready network goroutines
3. Picks a random P and steals half its run queue using `runqsteal()`
4. Tries up to 4 times, then tries `runnext` of random P's
5. Falls back to `netpoll()` with timeout
6. Parks the M via `stopm()`

The steal is lock-free at the victim end (only the victim P writes to its own head), and uses CAS at the thief end. This avoids contention on the global lock.

### Q3: Why does Go use signal-based preemption? What problem did it solve?

**Answer**: Before Go 1.14, preemption was cooperative — a goroutine only yielded at function prologues (stack check). Tight loops with no function calls (e.g., `for i := 0; i < n; i++ {}`) would never yield. This caused:
- GC STW (stop-the-world) to hang waiting for the goroutine to reach a safe point
- Other goroutines on the same P to starve
- Debugging tools (to attach profiler) to fail

Signal-based preemption (Go 1.14+) sends `SIGURG` to the OS thread. The signal handler sets a flag, and the goroutine yields at the next safe point (not during runtime code, atomic ops, or GC). This guarantees max preemption delay of ~10ms.

### Q4: A service has `GOMAXPROCS=8`. It spawns 10,000 goroutines, each making a blocking database call. How many OS threads exist? Why?

**Answer**: The M count will be at least 8 (for the P's running goroutines doing CPU work) plus the number of goroutines currently blocking in syscalls. When a goroutine blocks in a syscall (e.g., `read()` from a socket), the M enters `_Gsyscall` state, and `sysmon()` will eventually hand off the P to another M. This creates a new M for each concurrent blocking syscall beyond GOMAXPROCS.

If all 10,000 goroutines issue blocking DB calls simultaneously, the OS thread count could approach 10,000 (though Go caps M count at 10,000 by default; increase with `debug.SetMaxThreads()`). In practice, a connection pool limits concurrency and prevents this.

### Q5: What is the difference between channel blocking and syscall blocking from the scheduler's perspective?

**Answer**: **Channel blocking**: The goroutine enters `_Gwaiting` state. The M remains attached to its P. The scheduler is called immediately (`schedule()`), and the M picks up another goroutine from the run queue. The P is NOT handed off — the M is still running Go code on g0.

**Syscall blocking**: The goroutine enters `_Gsyscall` state. The M is stuck in the kernel (not running Go code). The P is detached from the M (`handoffp()`) so another M can pick it up. If no idle M exists, a new M is created. After the syscall returns, the M tries to reacquire a P; if none is available, the goroutine goes to the global run queue and the M parks.

### Q6: What does `runnext` do and why is it important?

**Answer**: `runnext` is a single-slot field on each P. When a goroutine is woken (e.g., channel receive ready), it's placed in `runnext` instead of the run queue. The scheduler always checks `runnext` first.

This provides goroutine affinity: a goroutine that just unblocked runs next on the same P, keeping L1/L2 cache warm. It also enables channel direct handoff — sender wakes receiver directly into `runnext`, avoiding all queues.

### Q7: How does netpoll prevent blocking an OS thread per network connection?

**Answer**: When a goroutine calls `conn.Read()` and no data is available:
1. The fd is registered with epoll/kqueue (only once, if not already)
2. The goroutine parks (`_Gwaiting`) on the fd's wait descriptor
3. The M returns to `schedule()` and picks up another goroutine — the OS thread is NOT blocked on the socket read

When data arrives:
1. `sysmon()` or an idle P calls `netpoll()` which calls `epoll_wait()` (or equivalent)
2. `netpoll()` returns the list of goroutines waiting on ready fd's
3. These goroutines are injected into the run queues and will run when scheduled

One OS thread (the one calling `netpoll()`) handles all network readiness checking. This is how Go servers handle 100K+ connections with ~GOMAXPROCS OS threads.

### Q8: A goroutine dump shows 500 goroutines in `[chan receive]` state. Is this a problem?

**Answer**: Not necessarily. If these are worker goroutines in a pool waiting for work, that's by design. The problem is if the count is **growing** over time (leak), or if the count doesn't match the expected worker pool size.

Questions to ask:
- Is the count stable under steady load?
- Does it match the expected number of workers?
- Is there a corresponding goroutine that's supposed to send on that channel?
- If the channel is never closed, will these goroutines ever exit?

### Q9: What happens when all goroutines are blocked at the same time?

**Answer**: The runtime detects this and panics with `fatal error: all goroutines are asleep - deadlock!`. This is checked in `checkdead()` in `runtime/proc.go`. The check runs after each scheduling round when there are zero idle P's, zero spinning M's, and zero runnable goroutines.

If even one goroutine is runnable (e.g., blocked on a timer that will fire), the program does NOT deadlock — the runtime waits for it.

### Q10: How do you debug a goroutine leak in production?

**Answer**:
1. Confirm the leak: monitor `go_goroutines` metric — is it monotonically increasing?
2. Capture goroutine dump: `curl http://localhost:6060/debug/pprof/goroutine?debug=2 > goroutines.txt`
3. Take two dumps 5 minutes apart under steady load
4. Compare: goroutines present in both dumps that are NOT part of the normal pool are leaks
5. Group by state: `grep "\[" goroutines.txt | sort | uniq -c | sort -rn`
6. Group by function: `grep "created by" goroutines.txt | sort | uniq -c | sort -rn`
7. The `created by` line tells you exactly which line of code spawned the leaking goroutine
8. For non-critical services, use `runtime.SetFinalizer` trick to log when leaked goroutines are GC'd

---

## Hands-On Exercises

### Exercise 1: Observe the Scheduler in Action

```go
package main

import (
    "fmt"
    "os"
    "runtime"
    "runtime/trace"
    "sync"
    "time"
)

func main() {
    // Start tracing
    f, _ := os.Create("trace.out")
    defer f.Close()
    trace.Start(f)
    defer trace.Stop()

    var wg sync.WaitGroup

    // Spawn 100 goroutines that do mixed CPU/IO work
    for i := 0; i < 100; i++ {
        wg.Add(1)
        go func(id int) {
            defer wg.Done()
            // CPU work
            for j := 0; j < 100000; j++ {
                _ = j * j
            }
            // Simulate I/O
            time.Sleep(time.Duration(id%5) * time.Millisecond)
            // CPU work
            for j := 0; j < 100000; j++ {
                _ = j * j
            }
        }(i)
    }

    wg.Wait()
    fmt.Println("Done. Run: go tool trace trace.out")
    fmt.Println("GOMAXPROCS:", runtime.GOMAXPROCS(0))
}
```

Run with:
```
go run main.go
go tool trace trace.out
```

Explore:
- View by "Goroutines" — see the lifecycle of each goroutine
- View by "Proc" — see which P executes which goroutine
- Check "Scheduler latency profile" and "Goroutine analysis"

### Exercise 2: Goroutine Leak Detection

```go
package main

import (
    "fmt"
    "net/http"
    _ "net/http/pprof"
    "runtime"
    "time"
)

func leakyHandler(w http.ResponseWriter, r *http.Request) {
    ch := make(chan int)
    go func() {
        ch <- 42 // blocks forever: nobody reads ch
    }()
    // Forgot to receive from ch
    w.Write([]byte("ok"))
}

func main() {
    go func() {
        for {
            fmt.Printf("Goroutines: %d\n", runtime.NumGoroutine())
            time.Sleep(2 * time.Second)
        }
    }()

    http.HandleFunc("/leak", leakyHandler)
    http.ListenAndServe(":8080", nil)
}
```

1. Start the server
2. `curl localhost:8080/leak` repeatedly
3. Watch the goroutine count grow
4. Hit `/debug/pprof/goroutine?debug=2` and identify the leak
5. Fix the handler (use buffered channel or read from `ch`)

### Exercise 3: Measure Work Stealing Efficiency

```go
package main

import (
    "fmt"
    "os"
    "runtime"
    "sync"
    "time"
)

func main() {
    // Force initial imbalance: pin work to P0
    // Then spawn more work that must be stolen
    os.Setenv("GODEBUG", "schedtrace=1000")

    runtime.GOMAXPROCS(4)

    for {
        var wg sync.WaitGroup
        start := time.Now()

        // Spawn 1000 goroutines doing varying work
        for i := 0; i < 1000; i++ {
            wg.Add(1)
            go func(n int) {
                defer wg.Done()
                // Variable work — some long, some short
                for j := 0; j < n*1000; j++ {
                    _ = j * j
                }
            }(i % 100)
        }

        wg.Wait()
        elapsed := time.Since(start)
        fmt.Printf("Time: %v\n", elapsed)
        time.Sleep(time.Second)
    }
}
```

Observe the `schedtrace` output: are the per-P run queue lengths balanced? Is the global queue being used?

### Exercise 4: Channel Handoff Benchmark

```go
package main

import (
    "fmt"
    "sync"
    "time"
)

func main() {
    // Channel of int
    ch := make(chan int)
    var wg sync.WaitGroup

    // Ping-pong: two goroutines passing a token back and forth
    start := time.Now()
    const iterations = 10_000_000

    wg.Add(2)
    go func() {
        defer wg.Done()
        for i := 0; i < iterations; i++ {
            ch <- i
            <-ch
        }
    }()

    go func() {
        defer wg.Done()
        for i := 0; i < iterations; i++ {
            <-ch
            ch <- i
        }
    }()

    wg.Wait()
    elapsed := time.Since(start)
    fmt.Printf("Time per round-trip: %v\n", elapsed/iterations/2)
}
```

This measures the direct handoff path — each send matches a waiting receive, so the value is copied directly without scheduler involvement. Compare with a buffered channel.

### Exercise 5: Reproduce a Deadlock

```go
package main

func main() {
    ch := make(chan int)
    ch <- 1 // blocks forever: no receiver, and this is the main goroutine
    <-ch
}
```

Run it. Observe: `fatal error: all goroutines are asleep - deadlock!`

Now add a goroutine:
```go
package main

import "time"

func main() {
    ch := make(chan int)
    go func() {
        time.Sleep(time.Second)
        ch <- 1
    }()
    <-ch // blocks, but runtime knows a goroutine exists that will send
}
```

No deadlock — the runtime detects the timer goroutine is alive and waits.

---

## Advanced Challenges

### Challenge 1: Implement a Work-Stealing Scheduler

Implement a simplified work-stealing scheduler in Go:
- Define `Task` (like a goroutine) with a function to execute
- Define `Worker` (like M + P) with a local deque and a goroutine
- Implement `Steal()` that takes from another worker's deque
- Implement `Submit()` that adds a task
- Measure throughput and work distribution

```go
// Starter code
type Task struct {
    F    func()
    Done chan struct{}
}

type Worker struct {
    ID    int
    Deque *Deque // your lock-free deque
}

func NewScheduler(numWorkers int) *Scheduler { /* ... */ }
func (s *Scheduler) Submit(f func()) { /* ... */ }
func (s *Scheduler) Shutdown() { /* ... */ }
```

### Challenge 2: Detect and Profile a Real Goroutine Leak

Take the service you work on daily:
1. Run a load test at 2× normal traffic for 10 minutes
2. Capture goroutine profiles at t=0, t=5min, t=10min
3. Identify: is the goroutine count stable? If not, which goroutines are accumulating?
4. Use `go tool pprof -base` to diff profiles
5. Trace the `created by` line to the source
6. Write a fix and verify the goroutine count stabilizes

### Challenge 3: Build a Goroutine Leak Detector

Write a lightweight goroutine leak detector that:
1. Instruments `go` statements (or uses a runtime hook) to track goroutine creation
2. Periodically dumps goroutine state
3. Identifies goroutines that have been in `_Gwaiting` for >N seconds
4. Alerts with the goroutine ID, state, and stack trace
5. Optionally integrates with `leaktest` for testing

---

## Key Insights

1. **Goroutines are NOT threads.** They are userspace, cooperatively-multiplexed (with signal-based preemption as safety net), lightweight tasks. Thinking of them as threads leads to wrong architecture decisions.

2. **The scheduler is work-stealing, not work-sharing.** Idle P's steal from busy ones. This means you don't need to manually balance work — the runtime handles it.

3. **Blocking in syscalls is expensive.** Each blocking syscall can create an OS thread. Use `net.DialTimeout`, connection pools, and non-blocking APIs.

4. **Channel operations are the cheapest synchronization.** Direct handoff avoids the scheduler entirely. Use channels for signaling between goroutines.

5. **Read `runtime/proc.go`.** It's ~5000 lines. You don't need to understand every line, but reading `schedule()`, `findrunnable()`, and `newproc1()` will change how you write Go.

6. **GOMAXPROCS should almost never be changed.** The default is correct. Only change it with benchmark data proving improvement.

7. **Goroutine leaks are production-critical bugs.** A single unclosed channel in a handler can take down a service in hours. Test for leaks with `uber-go/goleak`.

8. **The runtime is observable.** `GODEBUG=schedtrace`, `SIGQUIT` goroutine dumps, `pprof`, and `go tool trace` give you complete visibility. There is no excuse for guessing about scheduler behavior.

9. **Context switches are invisible to the OS.** `top` / `htop` showing 100% CPU with 8 threads on a 64-core machine is normal for Go — those 8 threads are just the P's, and the scheduler multiplexes goroutines within them.

10. **The scheduler is >10 years old and heavily optimized.** Don't try to be clever. The runtime authors have already solved the problems you're thinking about. Use goroutines, channels, contexts, and `sync` primitives as intended.

