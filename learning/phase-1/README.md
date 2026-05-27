# Phase 1 — Operating Systems & Networking

> **Duration**: 3-4 weeks (full-time) | **Prerequisites**: Phase 0 (Computer Science Essentials)
>
> **Goal**: Understand what happens when a process runs, how virtual memory works, why context switching is expensive, how TCP packets travel from your app to a remote server and back, and how TLS secures the connection.
>
> **Why this matters for the payment platform**: Docker containers are processes with isolated namespaces. Kubernetes pod scheduling mirrors OS scheduling concepts. Kafka producer batching depends on TCP buffer sizing. PostgreSQL `shared_buffers` tuning requires virtual memory understanding. Node.js event loop IS epoll. Every gRPC call between services IS HTTP/2. TLS 1.3 IS how your payment API is secured. You cannot operate a production system without understanding the OS and network underneath.

## Learning Objectives

After completing Phase 1, you will be able to:
1. Explain the process lifecycle (fork, exec, signals, zombie processes, wait)
2. Understand virtual memory (page tables, TLB, page faults, mmap, copy-on-write)
3. Choose the right I/O model (blocking, non-blocking, epoll, AIO) for a given workload
4. Trace a TCP connection from SYN to FIN with Wireshark
5. Explain TCP flow control (sliding window) and congestion control (slow start, AIMD)
6. Debug DNS resolution issues and understand record types
7. Explain HTTP/1.1 vs HTTP/2 vs HTTP/3 differences
8. Read a TLS 1.3 handshake from a packet capture
9. Understand load balancing algorithms (round-robin, least-connections, consistent hashing)

## Study Plan

| Day | Module | Topics | Hours |
|-----|--------|--------|:-----:|
| 1-3 | Module 01 | Process model, threads, context switching, signals | 8h |
| 4-6 | Module 01 | Virtual memory, page tables, TLB, mmap, page faults | 8h |
| 7-8 | Module 01 | File systems, inodes, journaling, fsync/O_DIRECT | 6h |
| 9-10 | Module 01 | I/O models: blocking, non-blocking, epoll, AIO | 6h |
| 11-13 | Module 02 | TCP/IP deep dive, flow control, congestion control | 8h |
| 14-15 | Module 02 | DNS, HTTP/1.1/2/3, TLS 1.3 handshake | 8h |
| 16-17 | Module 02 | Load balancing, gRPC, WebSocket | 6h |
| 18-21 | Exercises + Mini Project | Build TCP server, epoll server, HTTP load balancer | 12h |

## Prerequisites Check

Before starting, verify you understand from Phase 0:
- [ ] Stack vs heap memory allocation
- [ ] Cache hierarchy (L1/L2/L3/TLB)
- [ ] Concurrency fundamentals (race conditions, mutex, semaphore)
- [ ] Big-O complexity analysis
- [ ] Basic graph algorithms (BFS, shortest path)

## How to Use This Phase

1. **Read the module** — understand the theory
2. **Use Wireshark** — capture real network traffic, don't just read about TCP
3. **Use `strace`** — trace system calls to see what the OS actually does
4. **Use `/proc`** — explore process state, memory maps, file descriptors
5. **Write code** — implement a TCP server, an epoll event loop, a load balancer

## Connection to Next Phase

Phase 2 (Database Fundamentals) builds on:
- File systems → PostgreSQL storage (pages, WAL, fsync guarantees)
- Virtual memory → PostgreSQL `shared_buffers`, `mmap` for large datasets
- I/O models → PostgreSQL uses epoll/kqueue for connection handling
- TCP → Kafka producer batching, consumer polling
- DNS → Service discovery in Kubernetes (CoreDNS)
