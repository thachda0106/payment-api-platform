# Module 01 — Operating Systems

## 1.1 Why Operating Systems Matter for Payment Platforms

Every service in the payment platform runs on an operating system (Linux, in containers). Docker containers ARE Linux processes with isolated namespaces. Kubernetes pod scheduling IS process scheduling at cluster scale. PostgreSQL writes data to disk via `fsync` — an OS system call that guarantees durability. Node.js handles 10,000 concurrent connections via `epoll` — a Linux kernel I/O multiplexing mechanism.

When a payment times out because `fsync` took 30ms instead of 1ms during a checkpoint storm, that's an OS-level problem. When Kafka producer throughput drops because TCP buffers are too small, that's an OS tuning problem. Understanding the OS is understanding the foundation under every line of code.

---

## 1.2 Process Model

### What is a Process?

A process is a program in execution. It consists of:
- **Executable code** (text segment)
- **Data** (global variables, static data)
- **Heap** (dynamically allocated memory)
- **Stack** (function call frames, local variables)
- **Process Control Block (PCB)**: Metadata the OS maintains — PID, state, registers, memory maps, file descriptors, priority, owner

```
┌──────────────────────────┐
│    Process Address Space  │
├──────────────────────────┤
│  Stack        (grows ↓)  │ ← Local variables, function frames
│       ↓                  │
│                          │
│       ↑                  │
│  Heap         (grows ↑)  │ ← malloc/new
├──────────────────────────┤
│  BSS          (zeroed)   │ ← Uninitialized static variables
├──────────────────────────┤
│  Data         (init)     │ ← Initialized static variables
├──────────────────────────┤
│  Text         (code)     │ ← Executable instructions (read-only)
└──────────────────────────┘
```

### Process States

```
         ┌──────────┐
         │   NEW    │  Process created (fork)
         └────┬─────┘
              ▼
         ┌──────────┐
    ┌───▶│  READY   │  Waiting for CPU (in run queue)
    │    └────┬─────┘
    │         ▼
    │    ┌──────────┐
    │    │ RUNNING  │  Executing on CPU
    │    └────┬─────┘
    │         │
    │    ┌────┼──────────────┐
    │    ▼    ▼              ▼
    │ ┌──────┐ ┌────────┐ ┌──────────┐
    │ │BLOCKED│ │ZOMBIE │ │TERMINATED│
    │ └──┬───┘ └────────┘ └──────────┘
    │    │ (I/O complete)
    └────┘
```

- **NEW**: Process created, PCB initialized
- **READY**: Waiting in the run queue for CPU
- **RUNNING**: Actually executing on a CPU core
- **BLOCKED**: Waiting for I/O, lock, signal — not in run queue. Moved back to READY when I/O completes
- **ZOMBIE**: Process exited but parent hasn't called `wait()`. PCB still exists. Accumulating zombies exhausts PID space
- **TERMINATED**: Process has been cleaned up

### fork() and exec()

```c
pid_t pid = fork();
if (pid == 0) {
    // CHILD: pid is 0
    execve("/usr/bin/java", args, env);  // Replace child's memory with new program
    // never returns if execve succeeds
} else if (pid > 0) {
    // PARENT: pid is the child's PID
    int status;
    waitpid(pid, &status, 0);  // Wait for child to finish (prevents zombie)
} else {
    // ERROR: fork failed
}
```

**fork() semantics**: The child gets a COPY of the parent's address space (via copy-on-write — pages are shared until one process modifies them, then copied). File descriptors are shared (both parent and child see the same open files).

**Why fork+exec and not spawn?** Historical: Unix was designed this way. Modern systems use `posix_spawn()` or `clone()` which are more efficient. Docker uses `clone()` with namespace flags for container creation.

### Signals

Signals are asynchronous notifications sent to a process:
| Signal | Number | Default Action | Meaning |
|--------|:------:|---------------|---------|
| SIGINT | 2 | Terminate | Ctrl+C |
| SIGKILL | 9 | Terminate (cannot be caught) | Force kill |
| SIGTERM | 15 | Terminate | Graceful shutdown request |
| SIGSTOP | 19 | Stop (cannot be caught) | Pause process |
| SIGCHLD | 17 | Ignore | Child process state changed |
| SIGSEGV | 11 | Core dump + terminate | Segmentation fault (invalid memory access) |

**Payment relevance**: Kubernetes sends SIGTERM to pods for graceful shutdown. The pod has `terminationGracePeriodSeconds` (default 30s) to clean up. If it doesn't exit, Kubernetes sends SIGKILL. Your services must handle SIGTERM: stop accepting new requests, finish in-flight requests, close database connections, flush logs.

---

## 1.3 Threads and Concurrency

### Process vs Thread

| | Process | Thread |
|---|:------:|:-----:|
| Memory | Separate address space | Shared address space within process |
| Creation cost | High (fork + new page tables) | Low (new stack + registers) |
| Context switch cost | High (TLB flush, cache invalidation) | Lower (same address space) |
| Communication | IPC (pipes, sockets, shared memory) | Shared memory (need synchronization) |
| Failure isolation | One crash doesn't affect others | One crash kills all threads |

### Kernel Threads vs User Threads

- **Kernel threads (1:1)**: Each user thread maps to a kernel thread. OS schedules kernel threads. Used by: Linux (pthreads), Java platform threads.
- **User threads (N:1)**: N user threads map to 1 kernel thread. Library schedules user threads. OS sees one thread. If any thread blocks, all block. Used by: old Java "green threads".
- **Hybrid (M:N)**: M user threads map to N kernel threads. Used by: Go goroutines (GMP scheduler), Java Virtual Threads (Project Loom).

### Context Switching Cost

When the OS switches from one thread to another:
1. Save current thread's registers, PC, stack pointer → PCB
2. Load next thread's registers, PC, stack pointer from its PCB
3. Flush TLB (if switching processes, not threads in same process)
4. Cache will gradually warm up with new thread's data

**Typical costs** (on a 3GHz CPU):
- Same-process thread switch: ~1-2 µs
- Cross-process switch: ~3-5 µs (TLB flush adds cost)

**Payment relevance**: 1,000 context switches per second = 2ms CPU time. 100,000 context switches = 200ms. At 10,000 RPS with a thread-per-request model, context switch overhead dominates. This is why virtual threads and goroutines matter — they reduce context switches by multiplexing many logical tasks onto fewer OS threads.

---

## 1.4 CPU Scheduling

### Completely Fair Scheduler (CFS — Linux default)

CFS assigns each process a proportion of CPU time based on its **nice** value (priority). Lower nice = higher priority = more CPU time.

**vruntime (virtual runtime)**: Each process accumulates vruntime proportional to how much CPU time it has consumed, weighted by priority. CFS always picks the process with the SMALLEST vruntime (the one that has had the least CPU time). This ensures fairness over time — every process gets its proportional share.

**CFS data structure**: Red-black tree ordered by vruntime. O(log n) for insertion and finding the minimum.

**Preemption**: If a process runs for more than its target latency (`sched_latency_ns`, typically 6ms), it's preempted and put back in the tree. If a higher-priority process is woken up (I/O completed), it may preempt the current process if its vruntime is low enough.

### Priority Inversion

What happens when a high-priority task waits for a lock held by a low-priority task that's been preempted by a medium-priority task? The high-priority task is stuck waiting for the low-priority task, which can't run because the medium-priority task keeps running.

**Fix**: Priority inheritance — the low-priority task temporarily inherits the high-priority task's priority until it releases the lock. Used by Linux kernel's rt-mutex.

**Payment relevance**: Database connection pools, thread pools, and lock contention in financial services can exhibit priority inversion. A critical payment processing thread might be blocked because a non-critical reporting thread holds a lock and keeps getting CPU time. Mitigation: bounded thread pools, timeouts, and careful lock scope.

---

## 1.5 Virtual Memory

### The Illusion

Every process thinks it has the ENTIRE address space to itself (0 to 2^64-1 on x86-64). The OS and hardware create this illusion through virtual memory: virtual addresses are translated to physical addresses on every memory access.

### Page Tables

Memory is divided into fixed-size **pages** (typically 4KB on Linux). A page table maps virtual pages → physical page frames (or "not present" → page fault).

```
Virtual Address (64-bit):
┌──────────┬───────────┬───────────┬──────────────┐
│  unused  │  PML4 idx │  PDP idx  │  PD idx │ PT idx │ offset │
│  16 bits │   9 bits  │  9 bits   │ 9 bits │ 9 bits │ 12 bits│
└──────────┴───────────┴───────────┴────────┴────────┴────────┘
                                             4-level page table (x86-64)
```

**Translation process**:
1. CPU looks up virtual address in **TLB** (Translation Lookaside Buffer — a cache for page table entries)
2. TLB hit: physical address found (fast, ~1 cycle)
3. TLB miss: walk the 4-level page table in memory (slow, ~100-500 cycles)
4. Page not present: **page fault** → OS loads page from disk (or allocates new page), updates page table, retries instruction

### Page Faults

Three types:
- **Minor (soft) fault**: Page is in memory but not in this process's page table (e.g., shared library already loaded by another process). Just update page table.
- **Major (hard) fault**: Page must be read from disk. Very expensive (disk I/O time).
- **Invalid fault**: Access to unmapped memory → SIGSEGV (crash).

### mmap

`mmap()` maps a file (or anonymous memory) into a process's virtual address space. Reads and writes to the mapped region go to the file (or anonymous memory). This is how:
- Shared libraries are loaded (mmap the .so file)
- Large files are accessed (mmap instead of read)
- Memory is allocated (malloc uses mmap for large allocations, brk for small ones)
- Zero-copy file transfers (mmap file → write to socket via sendfile)

**Payment relevance**: PostgreSQL can use `mmap` for large datasets (though it prefers shared_buffers). Kafka uses mmap for its log segments (zero-copy from disk to network). Java's `MappedByteBuffer` is a wrapper around mmap.

### Huge Pages

Normally, pages are 4KB. For large memory regions (PostgreSQL `shared_buffers` = 32GB), that's 8 million page table entries. The TLB has ~1000 entries → 99.99% of accesses are TLB misses.

**Huge pages**: 2MB pages (or 1GB on newer CPUs). 32GB shared_buffers with 2MB pages = 16,384 pages. TLB still has ~1000 entries, but each covers 2000x more memory → higher hit rate.

PostgreSQL: `huge_pages = on` (requires `vm.nr_hugepages` sysctl and `Shared memory` in postgresql.conf).

---

## 1.6 File Systems

### Inodes

An **inode** (index node) is a data structure that stores metadata about a file:
- File type (regular, directory, symlink, etc.)
- Permissions (rwx for owner/group/other)
- Owner, group
- Size
- Timestamps (atime, mtime, ctime)
- Pointers to data blocks (direct, indirect, double-indirect, triple-indirect)

```
Inode structure (simplified):
┌──────────────────────┐
│  Metadata             │
├──────────────────────┤
│  12 direct pointers   │ → Point directly to data blocks (12 × 4KB = 48KB)
├──────────────────────┤
│  1 indirect pointer   │ → Points to a block of more pointers (4KB / 4 bytes = 1024 pointers × 4KB = 4MB)
├──────────────────────┤
│  1 double-indirect    │ → Points to indirect → more pointers (1024² × 4KB = 4GB)
├──────────────────────┤
│  1 triple-indirect    │ → (1024³ × 4KB = 4TB)
└──────────────────────┘
```

### Journaling

Journaling file systems (ext4, XFS) record changes in a journal before applying them to the main file system. On crash, replay the journal to get to a consistent state.

**Write-ahead logging**: The same principle as PostgreSQL WAL. Record what you're about to do, do it, mark it done. On crash, redo incomplete operations or undo partial operations.

**fsync / fdatasync / O_SYNC / O_DIRECT**:
- `fsync(fd)`: Flush file data AND metadata to disk. Blocks until complete. Critical for durability.
- `fdatasync(fd)`: Flush file data only (no metadata unless size changed). Faster than fsync.
- `O_SYNC`: Every write is implicitly `fsync`'d. Slow — use sparingly.
- `O_DIRECT`: Bypass OS page cache. Application manages its own caching. Used by: PostgreSQL (configurable), Kafka (for log segments).

**Payment relevance**: PostgreSQL `fsync = on` is CRITICAL. Turning it off gives 100x write speed but on crash → corrupted database. PostgreSQL uses `O_DIRECT` for WAL writes (avoids double-caching in OS page cache and shared_buffers). Kafka uses `O_DIRECT` for log segment writes (Kafka manages its own caching via the page cache, but direct I/O prevents double buffering).

---

## 1.7 I/O Models

### Blocking I/O

```c
char buf[1024];
int n = read(fd, buf, sizeof(buf));  // Thread blocks until data is available
```

The thread makes a system call. The OS puts the thread to sleep (BLOCKED state). When data is available, the OS wakes the thread. Meanwhile, the thread does nothing. This is the simplest model: one thread per connection.

**Problem**: 10,000 concurrent connections = 10,000 threads. Thread creation, context switching, and stack memory overhead become prohibitive.

### Non-Blocking I/O

```c
fcntl(fd, F_SETFL, O_NONBLOCK);
int n = read(fd, buf, sizeof(buf));
if (n == -1 && errno == EAGAIN) {
    // No data available right now, try again later
}
```

The `read()` returns immediately. If no data, it returns EAGAIN/EWOULDBLOCK. The application must poll (check repeatedly) — busy waiting wastes CPU.

### I/O Multiplexing — select / poll

```c
fd_set readfds;
FD_ZERO(&readfds);
FD_SET(fd1, &readfds); FD_SET(fd2, &readfds);
select(max_fd + 1, &readfds, NULL, NULL, NULL);
if (FD_ISSET(fd1, &readfds)) read(fd1, ...);
if (FD_ISSET(fd2, &readfds)) read(fd2, ...);
```

One thread monitors MULTIPLE file descriptors. When any become ready, `select` returns. The thread then reads from ready descriptors.

**Problems with select**: (1) Max 1024 file descriptors (FD_SETSIZE), (2) O(n) scan of all FDs to find which are ready, (3) must rebuild fd_set before each call.

**poll**: Similar to select but uses an array of structs → no FD_SETSIZE limit. Still O(n) scan.

### I/O Multiplexing — epoll (Linux)

```c
int epfd = epoll_create(1);
struct epoll_event ev;
ev.events = EPOLLIN; ev.data.fd = fd1; epoll_ctl(epfd, EPOLL_CTL_ADD, fd1, &ev);
ev.data.fd = fd2; epoll_ctl(epfd, EPOLL_CTL_ADD, fd2, &ev);

struct epoll_event events[64];
int nfds = epoll_wait(epfd, events, 64, -1);
for (int i = 0; i < nfds; i++) {
    if (events[i].events & EPOLLIN) read(events[i].data.fd, ...);
}
```

**Why epoll is better**: (1) O(1) for adding/removing FDs (maintains interest list in kernel), (2) Returns ONLY ready FDs (no O(n) scan), (3) Edge-triggered mode (notify only on state change, not every time data is available).

**What uses epoll**: Nginx, Node.js (via libuv), Redis, Java NIO Selector on Linux, Kafka network layer. This is THE I/O multiplexing mechanism for high-concurrency servers.

---

## 1.8 Key Concepts Summary

| Concept | What It Is | Payment Relevance |
|---------|-----------|-------------------|
| fork + exec | Create new process | Docker container = fork + clone + exec |
| SIGTERM handling | Graceful shutdown signal | K8s sends SIGTERM before killing pod |
| Context switch | Switch between threads | Thread-per-request overhead at scale |
| CFS | Linux fair scheduler | Process priority for critical services |
| Priority inversion | High-prio task waits for low-prio | Database lock contention scenarios |
| Page table / TLB | Virtual→physical translation | Huge pages for PostgreSQL shared_buffers |
| fsync / O_DIRECT | Durability guarantees | PostgreSQL WAL durability, Kafka log writes |
| epoll | O(1) I/O multiplexing | Node.js, Redis, Nginx, Java NIO Selector |
| mmap | Memory-map file | Zero-copy file access, Kafka log segments |

---

## 1.9 Self-Assessment

- [ ] Can explain what happens when `fork()` is called — including copy-on-write
- [ ] Can list all process states and explain transitions
- [ ] Understand why context switching between threads is cheaper than between processes
- [ ] Can explain how CFS achieves fairness using vruntime
- [ ] Can walk through a 4-level page table translation
- [ ] Understand the difference between minor and major page faults
- [ ] Know when to use `fsync`, `fdatasync`, `O_SYNC`, and `O_DIRECT`
- [ ] Can explain why `epoll` is O(1) while `select` is O(n)
- [ ] Understand why huge pages matter for database performance
