# Production Troubleshooting Runbook for Spring Boot Applications

## Decision Tree Flowcharts

### "High CPU" — Diagnostic Decision Tree

```
                    ┌──────────────────┐
                    │   HIGH CPU        │
                    │   Alert Fired      │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Identify Process  │
                    │ top -H -p <pid>  │
                    └────────┬─────────┘
                             │
             ┌───────────────┼───────────────┐
             ▼               ▼               ▼
    ┌────────────┐   ┌────────────┐   ┌────────────┐
    │ GC Threads │   │ App Threads│   │ JIT Threads│
    │ > 20% CPU? │   │ > 80% CPU?│   │ (C1/C2     │
    └─────┬──────┘   └─────┬──────┘   │ Compiler)  │
          │                │          └─────┬──────┘
          ▼                ▼                ▼
    ┌────────────┐  ┌────────────┐   ┌────────────┐
    │ High GC    │  │ App Thread │   │ Code Cache │
    │ Overhead   │  │ Hot Loop   │   │ Full / JIT │
    │            │  │            │   │ Recompiling│
    │ Check:     │  │ Check:     │   │ Check:     │
    │ - Heap size│  │ - jstack   │   │ - jinfo    │
    │ - GC logs  │  │ - Profiler │   │ - XX:+Print│
    │ - Is it    │  │ - Thread   │   │   Compilat.│
    │   young GC?│  │   dump     │   │ - CodeCache│
    │ - Is it    │  │ - Find     │   │   usage    │
    │   full GC? │  │   hotspot  │   │   (jstat)  │
    └─────┬──────┘  └─────┬──────┘   └─────┬──────┘
          │                │                │
    ┌─────▼──────┐  ┌─────▼──────┐   ┌─────▼──────┐
    │ Young GC:  │  │ CPU-bound  │   │ - Increase │
    │ - Increase │  │ business   │   │   Reserved- │
    │   heap     │  │ logic      │   │   CodeCache│
    │ - Tune     │  │ - Algorithm│   │   Size     │
    │   Eden size│  │   review   │   │ - Use -Xint│
    │            │  │ - SQL join │   │   to verify│
    │ Full GC:   │  │   in app   │   │ - Check for│
    │ - Memory   │  │ - Regex    │   │   deopt.   │
    │   leak     │  │   backtrack│   └────────────┘
    │ - Heap dump│  │ - Serializ.│
    │ - Meta-    │  │   overhead │
    │   space    │  └────────────┘
    └────────────┘
```

### "High Memory" — Diagnostic Decision Tree

```
                    ┌──────────────────┐
                    │  HIGH MEMORY      │
                    │  Alert Fired       │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Is it HEAP or    │
                    │ OFF-HEAP?        │
                    │ jcmd <pid>       │
                    │ VM.native_memory │
                    │ summary          │
                    └────────┬─────────┘
                             │
             ┌───────────────┼───────────────┐
             ▼               ▼               ▼
    ┌────────────┐   ┌────────────┐   ┌────────────┐
    │ Java Heap  │   │ Metaspace  │   │ Thread     │
    │ > expected │   │ > 256MB?   │   │ Stacks     │
    └─────┬──────┘   └─────┬──────┘   └─────┬──────┘
          │                │                │
          ▼                ▼                ▼
    ┌────────────┐   ┌────────────┐   ┌────────────┐
    │ Heap Dump  │   │ Classloader│   │ Too Many   │
    │ (jmap)     │   │ Leak       │   │ Threads     │
    │            │   │ - Many     │   │ - 1000s of  │
    │ Common:    │   │   versions │   │   Virtual   │
    │ - Large    │   │   of same  │   │   Threads   │
    │   caches  │   │   class    │   │ - Thread per │
    │ - List     │   │ - Dynamic │   │   request    │
    │   growth  │   │   proxies │   │ - Unbounded  │
    │ - Session  │   │   unbounded│   │   pool       │
    │   buildup │   │ - Groovy/ │   │ - Check with │
    │ - JSON     │   │   scripts │   │   jstack     │
    │   payload │   │ - Many    │   │ - Each thread│
    │   buffering│   │   lambdas│   │   = 1MB stack│
    │ - File     │   │ - Check  │   │   (default)  │
    │   upload   │   │   with   │   - 1000 threads│
    └─────┬──────┘   │   jcmd GC│   │   = 1GB      │
          │          │   .class │   └─────┬──────┘
          │          │   _histo │         │
          │          └──────────┘         ▼
          │                        ┌────────────┐
          ▼                        │ Direct     │
    ┌────────────┐                 │ Buffers    │
    │ Analyze    │                 │ - NIO      │
    │ with       │                 │   ByteBuff.│
    │ Eclipse MAT│                 │ - Netty    │
    │            │                 │   pooled   │
    │ See "Heap  │                 │ - Too many │
    │ Dump       │                 │   conn.    │
    │ Analysis"  │                 │ - Check:   │
    │ section    │                 │   jcmd     │
    └────────────┘                 │   VM.native│
                                   │   _memory  │
                                   │   details  │
                                   └────────────┘
```

### "Slow Responses" — Diagnostic Decision Tree

```
                    ┌──────────────────┐
                    │  SLOW RESPONSES   │
                    │  P99 > threshold  │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Which layer?     │
                    │ Check: Span       │
                    │ attributes in     │
                    │ tracing system    │
                    └────────┬─────────┘
                             │
     ┌───────────┬───────────┼───────────┬───────────┐
     ▼           ▼           ▼           ▼           ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│  DB     │ │Downstream││ Thread  │ │   GC    │ │Network  │
│  > 50ms ││ Service  ││  Pool   │ │ Pauses  ││/I/O     │
│  avg?   ││ > 100ms? ││Exhausted│ │ > 100ms?││ > 10ms? │
└────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘
     │           │           │           │           │
     ▼           ▼           ▼           ▼           ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│Check:   │ │Check:   │ │Check:   │ │Check:   │ │Check:   │
│- EXPLAIN│ │- Circuit│ │- tomcat │ │- GC logs│ │- netstat│
│  ANALYZE│ │  Breaker│ │  threads│ │- jstat  │ │- ss -s  │
│- Missing│ │  OPEN?  │ │  busy vs│ │  -gcutil│ │- ping   │
│  indexes│ │- Timeout│ │  max    │ │- GC     │ │  latency│
│- SeqScan│ │  too    │ │- hikari │ │  pause  │ │- DNS    │
│  on large│ │  short?│ │  active │ │  history│ │  lookup │
│  table  │ │- Retry  │ │  vs max │ │- GC     │ │  slow?  │
│- N+1    │ │  storm │ │- queue  │ │  algo   │ │- Packet │
│  queries│ │- Connec-│ │  depth  │ │  (G1 vs │ │  loss   │
│- Lock   │ │  tion   │ │- pending│ │  ZGC vs │ │- Sat'd  │
│  content│ │  pool   │ │  acquire│ │  Serial)│ │  NIC    │
│  ion    │ │  exhaus.│ │         │ │         │ │         │
└─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘
```

### "Connection Errors" — Diagnostic Decision Tree

```
                    ┌──────────────────┐
                    │  CONNECTION       │
                    │  ERRORS           │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Error type?      │
                    │ Check logs       │
                    └────────┬─────────┘
                             │
     ┌───────────┬───────────┼───────────┬───────────┐
     ▼           ▼           ▼           ▼           ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│"Too Many│ │"Connect │ │"Peer    │ │"Address │ │"SSL/    │
│Open     │ │Timeout" │ │Reset"   │ │Already  │ │TLS      │
│Files"   │ │         │ │         │ │in Use"  │ │Handshake│
└────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘
     │           │           │           │           │
     ▼           ▼           ▼           ▼           ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│File Descr│ │Network  │ │Remote   │ │Ephemeral│ │Cert     │
│Limit     │ │Unreach. │ │Service  │ │Ports    │ │Expired  │
│- ulimit  │ │Is target│ │Restarted│ │Exhausted│ │Check    │
│  -n      │ │ host up?│ │- Deploy │ │- netstat│ │cert     │
│- lsof    │ │ping     │ │  during │ │  -an |  │ │validity │
│  -p <pid>│ │telnet   │ │  request│ │  wc -l  │ │Check    │
│  | wc -l │ │<host>   │ │- LB    │ │- TIME_  │ │trust    │
│- Leaking │ │<port>   │ │  health │ │  WAIT   │ │chain    │
│  sockets │ │Firewall │ │  check  │ │  sockets│ │Cipher   │
│  (netstat│ │ rules?  │ │  removed│ │  piling │ │mismatch │
│  CLOSE_  │ │Check    │ │  before │ │  up     │ │Check    │
│  WAIT)   │ │route    │ │  conn   │ │- tcp_tw │ │openssl  │
│- Client  │ │tracert  │ │  drain  │ │  _reuse │ │s_client │
│  not     │ │         │ │- Grace- │ │  kernel │ │         │
│  closing │ │         │ │  ful    │ │  param  │ │         │
│  conns   │ │         │ │  shutdn│ │- Reduce │ │         │
└─────────┘ └─────────┘ │  missing│ │  conn   │ └─────────┘
                         └─────────┘ │  churn  │
                                     └─────────┘
```

### "OOM Kill" — Diagnostic Decision Tree

```
                    ┌──────────────────┐
                    │  OOM KILL /       │
                    │  Heap Dump        │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ OOM Type?        │
                    │ -Xlog:gc* output │
                    └────────┬─────────┘
                             │
     ┌───────────────────────┼───────────────────────┐
     ▼                       ▼                       ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────────┐
│ Java heap   │     │ GC overhead │     │ Metaspace       │
│ space       │     │ limit       │     │ (Compressed     │
│             │     │ exceeded    │     │  class space)   │
│ Heap full,  │     │ GC spent    │     │                 │
│ can't       │     │ > 98% of    │     │ Too many        │
│ allocate    │     │ time but    │     │ classes loaded  │
│ more        │     │ reclaimed   │     │ (dynamic        │
│             │     │ < 2% of     │     │ proxies, script │
│             │     │ heap        │     │ engines)        │
└──────┬──────┘     └──────┬──────┘     └────────┬────────┘
       │                   │                     │
       ▼                   ▼                     ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────────┐
│ Get heap    │     │ Heap is too │     │ jcmd <pid>      │
│ dump:       │     │ small for   │     │ VM.class_       │
│ - Automatic │     │ workload    │     │ hierarchy       │
│   -XX:+Heap │     │ - Increase  │     │                 │
│   DumpOnOut │     │   -Xmx      │     │ Check number of │
│   OfMemory  │     │ - Reduce    │     │ loaded classes  │
│   Error     │     │   live set  │     │ (jstat -class)  │
│ - Manual:   │     │ - Check     │     │                 │
│   jcmd <pid>│     │   for       │     │ Common cause:   │
│   GC.heap_  │     │   memory    │     │ - Groovy/Groovy │
│   dump      │     │   leak      │     │   Shell scripts │
│             │     │   BEFORE    │     │ - Dynamic       │
│ Then        │     │   increas-  │     │   proxy         │
│ analyze     │     │   ing heap  │     │   generation in │
│ in Eclipse  │     │             │     │   loop          │
│ MAT (see    │     │             │     │ - Many lambda   │
│ section     │     │             │     │   classes       │
│ below)      │     │             │     │ - ClassLoader   │
└─────────────┘     └─────────────┘     │   leak (re-     │
                                        │   deploy cycle) │
                                        └─────────────────┘
```

### "Startup Failure" — Diagnostic Decision Tree

```
                    ┌──────────────────┐
                    │  STARTUP FAILURE  │
                    │  Application     │
                    │  WON'T START     │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────────────────────────┐
                    │ Check exit code / last log lines:     │
                    │ journalctl -u myapp -n 100            │
                    │ docker logs myapp --tail 100          │
                    │ tail -100 /var/log/myapp/spring.log   │
                    └────────┬─────────────────────────────┘
                             │
     ┌───────────┬───────────┼───────────┬───────────┐
     ▼           ▼           ▼           ▼           ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│Bean     │ │ Port    │ │ Config  │ │ Health  │ │External │
│Creation │ │Binding  │ │Missing  │ │Check    │ │Dependency
│Failure  │ │Failed   │ │/Wrong   │ │Failed   │ │Unavail  │
└────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘
     │           │           │           │           │
     ▼           ▼           ▼           ▼           ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│Check:   │ │Check:   │ │Check:   │ │Check:   │ │Check:   │
│- Actuator│ │netstat  │ │Actuator │ │/actuator│ │ping DB  │
│  /condi-│ │-tulpn   │ │/config- │ │/health  │ │host      │
│  tions   │ │grep    │ │props    │ │endpoint │ │telnet    │
│  endpoint│ │<port>  │ │         │ │shows    │ │<host>    │
│  (enable │ │         │ │Look for:│ │which    │ │<port>    │
│  via     │ │Another  │ │- DB URL │ │component│ │          │
│  proper- │ │process  │ │  wrong  │ │is DOWN  │ │Is Kafka,│
│  ty if   │ │on that  │ │- Creds  │ │         │ │Redis,   │
│  startup │ │port?    │ │  missing│ │Look for │ │Consul   │
│  fails)  │ │lsof -i │ │- Profile│ │cascading│ │available│
│          │ │:<port>  │ │  not set│ │failures │ │?         │
│- Check   │ │         │ │- Encrypt│ │         │ │          │
│  @Condi- │ │         │ │  key    │ │         │ │          │
│  tional- │ │         │ │  missing│ │         │ │          │
│  OnBean, │ │         │ │         │ │         │ │          │
│  @Condi- │ │         │ │         │ │         │ │          │
│  tional  │ │         │ │         │ │         │ │          │
│  annota- │ │         │ │         │ │         │ │          │
│  tions   │ │         │ │         │ │         │ │          │
└─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘
```

---

## Diagnostic Command Cheat Sheet

### Java Process Commands

#### `jps` — List Java Processes
```bash
# List all Java processes with PID and main class
jps -l

# List with full arguments (shows JVM options)
jps -lv

# List with package path
jps -lm
```

#### `jstack` — Thread Dump
```bash
# Take a thread dump (most commonly used form)
jstack <pid>

# Take a thread dump to file
jstack <pid> > threaddump_$(date +%Y%m%d_%H%M%S).txt

# Include additional lock information
jstack -l <pid>

# Detect deadlocks only (fast check)
jstack -l <pid> | grep -A 10 "deadlock"

# Take multiple thread dumps for comparison (every 5s, 6 times = 30s)
for i in {1..6}; do
    jstack <pid> > threaddump_$i.txt
    sleep 5
done

# Filter for threads in BLOCKED state
jstack <pid> | grep -A 5 "BLOCKED"

# Filter for threads in RUNNABLE state (may indicate CPU-bound work)
jstack <pid> | grep -A 3 "RUNNABLE"

# Count threads by state
jstack <pid> | grep "java.lang.Thread.State" | sort | uniq -c | sort -rn
```

#### `jmap` — Memory Map / Heap Dump
```bash
# Take a heap dump (histogram first to see what's in heap)
jmap -histo:live <pid> | head -30

# Take a full heap dump (NOTE: may pause JVM, use jcmd for production)
jmap -dump:live,format=b,file=/tmp/heapdump_$(date +%Y%m%d_%H%M%S).hprof <pid>

# Safer heap dump via jcmd (preferred for production)
jcmd <pid> GC.heap_dump /tmp/heapdump_$(date +%Y%m%d_%H%M%S).hprof

# Memory allocation histogram for all objects
jmap -histo <pid> | head -50

# Histogram with only live objects (triggers full GC)
jmap -histo:live <pid> | head -50

# Show finalizer queue (objects waiting for finalization)
jmap -finalizerinfo <pid>

# Show class loader statistics
jmap -clstats <pid>
```

#### `jcmd` — Diagnostic Commands (Preferred for Production)
```bash
# List all available diagnostic commands for this JVM
jcmd <pid> help

# Thread dump (same as jstack)
jcmd <pid> Thread.print

# Heap histogram without full GC
jcmd <pid> GC.class_histogram | head -50

# Trigger GC
jcmd <pid> GC.run

# GC statistics
jcmd <pid> GC.heap_info

# Native memory usage summary (requires -XX:NativeMemoryTracking=summary at startup)
jcmd <pid> VM.native_memory summary

# Detailed native memory breakdown
jcmd <pid> VM.native_memory summary.diff

# Check baseline and diff for native memory leak detection
jcmd <pid> VM.native_memory baseline
# ... wait some time ...
jcmd <pid> VM.native_memory summary.diff

# Metaspace size and usage
jcmd <pid> VM.metaspace

# Class hierarchy (useful for classloader leak diagnosis)
jcmd <pid> VM.class_hierarchy

# Check JVM flags (active and default)
jcmd <pid> VM.flags

# Check all JVM system properties
jcmd <pid> VM.system_properties

# Show JVM uptime
jcmd <pid> VM.uptime

# Force full GC (use with caution in production)
jcmd <pid> GC.run_finalization

# Print all loaded classes
jcmd <pid> VM.classloaders
```

#### `jstat` — JVM Statistics Monitoring
```bash
# GC statistics every 1 second, 10 iterations
jstat -gc <pid> 1000 10

# Meaning of columns:
# S0C/S1C: Survivor space 0/1 capacity (KB)
# S0U/S1U: Survivor space 0/1 utilization (KB)
# EC: Eden capacity (KB)
# EU: Eden utilization (KB)
# OC: Old generation capacity (KB)
# OU: Old generation utilization (KB)
# MC: Metaspace capacity (KB)
# MU: Metaspace utilization (KB)
# YGC: Young GC count
# YGCT: Young GC time (seconds)
# FGC: Full GC count
# FGCT: Full GC time (seconds)
# GCT: Total GC time (seconds)

# GC utilization (percentages) — most frequently used
jstat -gcutil <pid> 1000 10

# Additional columns: S0, S1, E, O, M (utilization %), CCS (compressed class space %), YGC, YGCT, FGC, FGCT, GCT

# Class loading statistics
jstat -class <pid> 1000 10

# Compiler statistics (JIT)
jstat -compiler <pid> 1000 10

# For G1GC: shows heap region details
jstat -gccapacity <pid> 1000 10

# GC cause for last collection
jstat -gccause <pid> 1000 10
```

#### `jinfo` — JVM Configuration
```bash
# Show all JVM flags (set and default)
jinfo <pid>

# Show specific flag value
jinfo -flag MaxHeapSize <pid>
jinfo -flag UseG1GC <pid>
jinfo -flag ConcGCThreads <pid>

# Show system properties
jinfo -sysprops <pid>

# Change a manageable flag at runtime (only some flags)
jinfo -flag +PrintGCDetails <pid>
```

### Profiling Commands

#### async-profiler (Recommended for Production CPU Profiling)
```bash
# Profile CPU for 30 seconds (flame graph)
./profiler.sh -d 30 -f /tmp/flamegraph.html <pid>

# Profile allocations for 30 seconds
./profiler.sh -d 30 -e alloc -f /tmp/alloc.html <pid>

# Profile lock contention
./profiler.sh -d 30 -e lock -f /tmp/lock.html <pid>

# Profile specific event
./profiler.sh -d 30 -e cpu -f /tmp/cpu.html <pid>

# Profile with JFR output format (can be opened in JMC)
./profiler.sh -d 30 -o jfr -f /tmp/profile.jfr <pid>

# Profile wall-clock time (includes sleeping/blocked)
./profiler.sh -d 30 -e wall -f /tmp/wall.html <pid>

# Profile with specific interval
./profiler.sh -d 30 -e cpu -i 1ms -f /tmp/cpu.html <pid>
```

### Linux/Unix System Commands

```bash
# --- Process Monitoring ---

# CPU and memory usage per process (top in batch mode)
top -b -n 1 -p <pid>

# Thread-level CPU usage (H flag shows threads)
top -H -p <pid>

# Alternative: htop with tree view (more interactive)
htop -p <pid>

# Process memory breakdown (RSS, VSZ, shared, etc.)
ps -o pid,vsz,rss,pmem,pcpu,comm -p <pid>

# --- Memory ---

# Memory usage summary
free -h

# Detailed memory info
vmstat -s

# Memory usage per process
ps aux --sort=-%mem | head -20

# --- I/O ---

# I/O statistics per device, every 2 seconds
iostat -x 2

# I/O statistics for a specific device
iostat -x -p sda 2

# Per-process I/O statistics
iotop -o -b -n 1

# --- Network ---

# Active connections and listening ports
netstat -tulpn

# TCP connection state summary
netstat -an | awk '/^tcp/ {print $6}' | sort | uniq -c | sort -rn

# Count connections per remote address (check for connection leaks)
netstat -an | grep ESTABLISHED | awk '{print $5}' | sort | uniq -c | sort -rn

# Socket statistics (modern replacement for netstat)
ss -tulpn            # Listening
ss -tan              # All TCP sockets
ss -tan state time-wait | wc -l  # Count TIME_WAIT sockets

# Open file descriptors for a process
lsof -p <pid> | wc -l
lsof -p <pid> | grep "REG" | wc -l    # Regular files
lsof -p <pid> | grep "sock" | wc -l   # Sockets
lsof -p <pid> | grep "IPv" | wc -l    # IP sockets

# Top file descriptor consumers
lsof -p <pid> | awk '{print $5}' | sort | uniq -c | sort -rn | head -10

# List sockets in CLOSE_WAIT (potential leak)
lsof -i -P -n | grep CLOSE_WAIT

# --- File Descriptors ---

# Per-process file descriptor limit
cat /proc/<pid>/limits | grep "open files"

# Current count of open file descriptors
ls /proc/<pid>/fd | wc -l

# System-wide limits
ulimit -n

# Kernel limits
sysctl fs.file-max
sysctl fs.file-nr   # (allocated, unused, max)

# --- CPU ---

# CPU information
lscpu

# CPU usage per core
mpstat -P ALL 2

# Load average and CPU usage
uptime

# --- Disk ---

# Disk usage
df -h

# Inode usage (can cause "no space left on device" even with free disk)
df -i

# Find large files
find / -type f -size +100M -exec ls -lh {} \; 2>/dev/null
```

### Spring Boot Actuator Endpoints

```bash
# Ensure management endpoints are accessible
# application.yml:
#   management.endpoints.web.exposure.include: health,info,metrics,prometheus,threaddump,heapdump,conditions,configprops,env,loggers

# --- Health ---
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/db         # Database health
curl http://localhost:8080/actuator/health/redis       # Redis health
curl http://localhost:8080/actuator/health/liveness    # Kubernetes liveness
curl http://localhost:8080/actuator/health/readiness   # Kubernetes readiness

# --- Metrics ---
curl http://localhost:8080/actuator/metrics            # List all metrics
curl http://localhost:8080/actuator/metrics/jvm.memory.used
curl http://localhost:8080/actuator/metrics/jvm.gc.pause
curl http://localhost:8080/actuator/metrics/http.server.requests
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
curl http://localhost:8080/actuator/metrics/tomcat.threads.busy
curl http://localhost:8080/actuator/metrics/jvm.threads.live

# --- Thread Dump ---
curl http://localhost:8080/actuator/threaddump

# --- Heap Dump ---
curl http://localhost:8080/actuator/heapdump -o heapdump.hprof

# --- Conditions Evaluation Report ---
# Shows which @Conditional annotations matched/not-matched
curl http://localhost:8080/actuator/conditions

# --- Configuration Properties ---
curl http://localhost:8080/actuator/configprops

# --- Environment ---
curl http://localhost:8080/actuator/env

# --- Loggers (view and change at runtime) ---
curl http://localhost:8080/actuator/loggers
curl http://localhost:8080/actuator/loggers/com.example
# Change log level:
curl -X POST http://localhost:8080/actuator/loggers/com.example \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'

# --- Mappings (all request mappings) ---
curl http://localhost:8080/actuator/mappings

# --- Scheduled Tasks ---
curl http://localhost:8080/actuator/scheduledtasks

# --- Caches ---
curl http://localhost:8080/actuator/caches
curl http://localhost:8080/actuator/caches/orders   # Specific cache
# Evict cache at runtime:
curl -X DELETE http://localhost:8080/actuator/caches/orders
```

### JVM Flags for Diagnostics in Production

Add these to `JAVA_TOOL_OPTIONS` or `-XX:` flags. They have negligible performance impact:

```bash
# Enable GC logging (Java 9+ unified JVM logging)
-Xlog:gc*=info:file=/var/log/myapp/gc.log:time,uptime,level,tags:filecount=10,filesize=50M

# Enable heap dump on OOM
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/myapp/heapdumps

# Exit JVM on OOM (so orchestrator restarts it)
-XX:+ExitOnOutOfMemoryError
# Or crash on OOM (creates core dump for analysis)
-XX:+CrashOnOutOfMemoryError

# Native memory tracking (use only when diagnosing native memory leaks, adds 5-10% overhead)
-XX:NativeMemoryTracking=summary

# Print date stamps in GC log
-XX:+PrintGCDateStamps

# Include detailed GC info
-XX:+PrintGCDetails

# Record the class that caused GC (useful for huge object allocations)
-XX:+PrintClassHistogramBeforeFullGC
-XX:+PrintClassHistogramAfterFullGC

# Set heap dump path
-XX:HeapDumpPath=/opt/dumps/

# Timeout for generating heap dump (prevent hanging on OOM)
-XX:HeapDumpSegmentSize=1G
```

---

## Log Patterns: What to Grep For

### High CPU / Slow Responses
```bash
# OutOfMemoryError warnings (often precede OOM)
grep -i "outofmemory\|java.lang.OutOfMemoryError\|java heap space\|GC overhead" /var/log/myapp/spring.log

# GC pauses (from GC logs)
grep -E "Pause.*[0-9]{3,}ms|[0-9]{4,}\.[0-9]+ms" /var/log/myapp/gc.log

# Full GC events
grep -i "Full GC\|Pause Full" /var/log/myapp/gc.log

# Thread pool exhaustion (Tomcat, HikariCP)
grep -i "RejectedExecutionException\|exhausted\|no available threads\|pool is full\|pool exhausted" /var/log/myapp/spring.log

# Slow database queries (> 1 second). Requires slow query logging enabled or JDBC logging.
grep -E "[0-9]{4,}ms|Execution.*[0-9]{4,}\.0ms" /var/log/myapp/spring.log

# Circuit breaker open (Resilience4j / Hystrix)
grep -i "CircuitBreaker.*OPEN\|circuit.*open\|short-circuit\|fallback" /var/log/myapp/spring.log

# Deadlock detection
grep -i "deadlock\|dead lock\|Found 1 deadlock\|Found a total of [1-9]" /var/log/myapp/spring.log

# Connection timeouts
grep -i "connect timed out\|ConnectTimeoutException\|Connection refused\|No route to host\|SocketTimeoutException\|Read timed out" /var/log/myapp/spring.log

# Connection pool exhaustion
grep -i "Connection is not available\|request timed out after\|HikariPool.*Timeout\|CannotAcquireResourceException\|PoolExhaustedException" /var/log/myapp/spring.log
```

### Memory Issues
```bash
# Memory warnings
grep -i "MemoryMXBean\|low memory\|high memory\|heap usage\|used heap" /var/log/myapp/spring.log

# Large allocations
grep -i "large allocation\|huge allocation\|very large\|allocated [0-9]+ MB" /var/log/myapp/spring.log

# Metaspace issues
grep -i "Metaspace\|Compressed class space\|class metadata" /var/log/myapp/spring.log

# Direct buffer issues
grep -i "direct buffer\|DirectByteBuffer\|OutOfDirectMemoryError\|direct buffer memory" /var/log/myapp/spring.log
```

### Startup Failures
```bash
# Bean creation failures
grep -i "BeanCreationException\|UnsatisfiedDependencyException\|NoSuchBeanDefinitionException\|No qualifying bean" /var/log/myapp/spring.log

# Circular dependencies
grep -i "Circular depends-on\|circular reference\|BeanCurrentlyInCreationException\|Is there an unresolvable circular" /var/log/myapp/spring.log

# Port already in use
grep -i "Port.*already.*in use\|Address already in use\|bind.*failed.*port\|PortInUseException\|Embedded servlet container failed to start" /var/log/myapp/spring.log

# Configuration errors
grep -i "Could not resolve placeholder\|Invalid configuration\|Cannot load driver class\|Failed to configure" /var/log/myapp/spring.log

# Flyway/Liquibase migration failures
grep -i "FlywayException\|Migration.*failed\|checksum mismatch\|LiquibaseException\|ChangeSet.*failed" /var/log/myapp/spring.log

# External dependency connection failures (during startup)
grep -i "Connection refused\|Failed to connect\|Unable to connect\|Cannot get connection\|Failed to obtain JDBC Connection" /var/log/myapp/spring.log
```

### Application Errors
```bash
# 5xx errors from access logs
grep -E " [5][0-9]{2} " /var/log/myapp/access.log

# Stack traces
grep -B 2 -A 20 "Exception\|Error" /var/log/myapp/spring.log | head -200

# ConcurrentModificationException (state corruption)
grep -i "ConcurrentModificationException\|ConcurrentModification" /var/log/myapp/spring.log

# OptimisticLockException (concurrent updates)
grep -i "OptimisticLockException\|StaleObjectStateException\|StaleStateException\|Row was updated" /var/log/myapp/spring.log

# DataIntegrityViolationException
grep -i "DataIntegrityViolationException\|ConstraintViolationException\|violates.*constraint\|duplicate key" /var/log/myapp/spring.log

# Timeout on external calls
grep -i "timeout\|timed out\|Interrupt\|TransactionTimedOutException" /var/log/myapp/spring.log

# Rate limiting
grep -i "rate limit\|throttle\|too many requests\|429\|Bucket4j\|RateLimiter" /var/log/myapp/spring.log

# JSON parsing errors (usually bad client input)
grep -i "JsonParseException\|JsonMappingException\|HttpMessageNotReadableException\|MalformedJson" /var/log/myapp/spring.log
```

---

## Metrics Dashboard: What to Check First

### Critical Metrics — First 60 Seconds of Investigation

| Metric | Check With | Healthy Range | Concerning | Critical |
|--------|-----------|---------------|------------|----------|
| Request rate | `http_server_requests_seconds_count` | Within expected range | > 2x normal | > 5x normal or 0 |
| Error rate (5xx) | `http_server_requests_seconds_count{status=~"5.."}` | < 0.1% | > 1% | > 5% |
| P99 latency | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))` | < 500ms | > 1s | > 5s |
| JVM Heap Used % | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}` | < 70% | 70-90% | > 90% |
| GC pause time | `rate(jvm_gc_pause_seconds_sum[5m])` | < 50ms/s | 50-200ms/s | > 200ms/s |
| Thread count | `jvm_threads_live_threads` | 50-500 | 500-2000 | > 2000 |
| CPU usage | `system_cpu_usage` | < 60% | 60-85% | > 85% |
| Hikari active conns | `hikaricp_connections_active` | < 50% of max | 50-80% | > 80% |
| Hikari pending conns | `hikaricp_connections_pending` | 0 | 1-5 | > 5 |
| Tomcat busy threads | `tomcat_threads_busy_threads` | < 50% of max | 50-80% | > 80% |
| File descriptors open | `process_files_open_files` | < 50% of max | 50-80% | > 80% |
| DB query duration | `jdbc_connections_max` equivalent timer | < 50ms avg | > 100ms | > 500ms |
| Circuit breaker state | `resilience4j_circuitbreaker_state` | 0 (CLOSED) | 1 (OPEN) | 1 for > 30s |
| Cache hit rate | `cache_gets_total{result="hit"} / cache_gets_total` | > 90% | 70-90% | < 70% |

### Quick Diagnostic Dashboard Queries (PromQL)

```promql
# Is the application alive?
up{job="my-service"}

# What's the current error rate?
rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m])

# How fast is error budget burning?
# (error_rate_sli - error_rate_target) / error_budget_remaining

# Is GC causing latency?
rate(jvm_gc_pause_seconds_sum[5m]) / rate(jvm_gc_pause_seconds_count[5m])

# Memory leak? (heap growing over time)
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# Thread leak?
delta(jvm_threads_live_threads[1h])

# Connection pool saturation?
hikaricp_connections_active / hikaricp_connections_max

# DB query latency
rate(jdbc_connections_seconds_sum[5m]) / rate(jdbc_connections_seconds_count[5m])

# Cache efficiency
sum(rate(cache_gets_total{result="hit"}[5m])) / sum(rate(cache_gets_total[5m]))

# JIT code cache utilization
jvm_memory_used_bytes{area="nonheap",id="CodeCache"} / jvm_memory_max_bytes{area="nonheap",id="CodeCache"}

# HTTP 429 (rate limited) rate
rate(http_server_requests_seconds_count{status="429"}[5m])

# Garbage collector overhead
rate(jvm_gc_pause_seconds_sum[5m])
```

---

## Heap Dump Analysis: Eclipse MAT Step-by-Step

### Common Heap Leak Patterns and Analysis

#### Pattern 1: Session/Request-Scoped Bean Caching

**Symptoms**: Heap grows monotonically. Full GCs become more frequent and longer. Eventually OOM.

**In Eclipse MAT**:
1. Open the heap dump (`File → Open Heap Dump`).
2. Run **Leak Suspects Report** (automatic on open). Look at the "Biggest Objects" pie chart.
3. Click on the biggest suspect. Review the **Accumulated Objects** — these are objects reachable only through the suspect.
4. Check the **Class Histogram** view. Sort by "Retained Heap". Look for:
   - Large numbers of `HttpSession` objects (session buildup)
   - `DefaultListableBeanFactory` with many singleton beans keeping references
   - `ThreadLocal` with large retained sizes (potential thread-local leak from thread pools)
5. Right-click a suspicious class → **List objects → with outgoing references**. Browse to find what's holding the reference chain.
6. **Path to GC Roots**: Right-click suspect object → `Path to GC Roots → exclude weak/soft/phantom references`. This shows what's preventing GC.

**Common Fixes**:
- Session timeout misconfiguration → verify `server.servlet.session.timeout` and Spring Session config.
- Request-scoped beans injected into singletons → use `ObjectFactory` or `@Lookup` instead of direct injection.
- `ThreadLocal` not cleaned up in thread pool → use `try { ... } finally { threadLocal.remove(); }`.

#### Pattern 2: Large Collection Growth (Unbounded Cache/Map)

**Symptoms**: Heap grows slowly over days. GC cycles gradually lengthen.

**In Eclipse MAT**:
1. Open **Histogram**. Sort by "Retained Heap".
2. Look for: `HashMap$Node`, `LinkedHashMap$Entry`, `ConcurrentHashMap$Node` with high instance count (> 100K).
3. Check `ArrayList` and `LinkedList` — large arrays with huge elementData.
4. Right-click → `List objects → with outgoing references`. Check what's inside these collections.
5. Identify the collection owner: `Path to GC Roots → exclude weak references`.

**Common Fixes**:
- `@Cacheable` without eviction policy → add `expireAfterWrite` or `maximumSize` in Caffeine config.
- `Map` used as in-memory cache without size limit → use Caffeine or Guava Cache with eviction.
- Event listeners accumulating events without processing → verify event listener is actually consuming.
- WebSocket sessions accumulating messages for disconnected clients → add heartbeat and timeout.

#### Pattern 3: ClassLoader Leak (Redeployment)

**Symptoms**: After multiple redeployments, Metaspace grows unboundedly. Eventually `OutOfMemoryError: Metaspace`.

**In Eclipse MAT**:
1. Open **Histogram**. Search for classes with the same name but different classloaders.
2. Query: `SELECT * FROM java.lang.Class` — look for duplicate class names loaded by different `ClassLoader` instances.
3. Check for `GroovyClassLoader`, `ScriptEngine`, `CGLIB` proxy classes accumulating.
4. Right-click a class → `Path to GC Roots → exclude weak references`. Check if a `ClassLoader` is still referenced by a thread or static field.

**Common Fixes**:
- Spring devtools redeployment creating new ClassLoaders that aren't cleaned up → fixed in newer versions, or disable devtools in production.
- Groovy scripts loaded dynamically without clearing the script cache → call `GroovyClassLoader.clearCache()`.
- CGLIB proxies from `@Configuration` classes not being replaced on refresh → fixed in Spring 5.2+.
- JDBC drivers registered but never deregistered → manually deregister in `@PreDestroy`.

#### Pattern 4: Thread Pileup (Unbounded Thread Creation)

**Symptoms**: Thread count grows. Each thread consumes ~1MB stack space + TLS overhead. Can cause OOM.

**In Eclipse MAT**:
1. Look for `java.lang.Thread` objects with high count in histogram.
2. Check the **Thread Overview** (separate view or query). Look for threads with the same name pattern.
3. Threads in `WAITING` or `TIMED_WAITING` state that never terminate.

**Common Fixes**:
- `Executors.newCachedThreadPool()` used instead of bounded pool → switch to `newFixedThreadPool` or `ThreadPoolExecutor` with bounded queue.
- Async methods (`@Async`) without thread pool configuration → configure `ThreadPoolTaskExecutor` with max pool size.
- Virtual threads on Java 21+ solve this for I/O-bound work.

#### Pattern 5: ByteBuffer/Direct Memory Leak

**Symptoms**: Native memory grows (visible in `jcmd VM.native_memory` or OS memory metrics) but heap is fine.

**In Eclipse MAT** (direct buffers are on heap but reference native memory):
1. Look for `java.nio.DirectByteBuffer` objects in the histogram.
2. Check the `Cleaner` thread — if buffers aren't being cleaned, the `Cleaner` reference queue may be backed up.
3. If using Netty, look for `PooledByteBufAllocator` and `PoolArena` objects.

**Common Fixes**:
- Direct buffers not being released explicitly → call `((DirectBuffer) buffer).cleaner().clean()` in finally block (or use try-with-resources if the buffer API supports it).
- Netty leak detection → enable `-Dio.netty.leakDetection.level=PARANOID`.
- Increase `-XX:MaxDirectMemorySize` temporarily while fixing the leak.

---

## Thread Dump Analysis

### Identifying Blocked Threads

```bash
# Take a thread dump
jstack <pid> > td.txt

# Find BLOCKED threads
grep -n "BLOCKED" td.txt

# A typical blocked thread looks like:
# "http-nio-8080-exec-27" #47 daemon prio=5 os_prio=0 tid=0x00007f8c0c001000 nid=0x6a3b waiting for monitor entry [0x00007f8be5afe000]
#    java.lang.Thread.State: BLOCKED (on object monitor)
#    at com.example.service.InventoryService.reserveInventory(InventoryService.java:42)
#    - waiting to lock <0x00000006c2e6d8a0> (a java.lang.Object)
#    - locked <0x00000006c2e6d8a0> (a java.lang.Object)  <-- THIS IS THE LOCK HOLDER

# Find the lock holder (copy the lock address from above)
grep -B 2 "locked <0x00000006c2e6d8a0>" td.txt
```

### Finding Lock Owners

The "locked" thread will have the same lock address with "locked" annotation. Look for:
```
"http-nio-8080-exec-12" #32 daemon prio=5 ... nid=0x6a2f runnable
   java.lang.Thread.State: RUNNABLE
   at com.example.service.InventoryService.reserveInventory(InventoryService.java:42)
   - locked <0x00000006c2e6d8a0> (a java.lang.Object)
```

This tells you: thread exec-12 holds the lock that exec-27 is waiting for. Check what exec-12 is doing — is it stuck in a long database query? Is it in an infinite loop? Is it waiting for another lock (deadlock)?

### Detecting Deadlocks

```bash
# Method 1: jstack auto-detection
jstack -l <pid> | grep -A 20 "Found one Java-level deadlock"

# Method 2: Graph analysis with jstack output
# Look for circular dependencies in "waiting to lock" relationships

# Method 3: Thread dump from actuator
curl http://localhost:8080/actuator/threaddump | jq '.threads[] | select(.threadState == "BLOCKED")'
```

Deadlock pattern in thread dump:
```
Thread-1: waiting to lock <A> (held by Thread-2) → waiting to lock <B> (held by Thread-1)
Thread-2: waiting to lock <B> (held by Thread-1) → waiting to lock <A> (held by Thread-2)
```

### Common Thread Pool Exhaustion Patterns

```bash
# All Tomcat threads are busy → look for many "http-nio-8080-exec-N" threads all in RUNNABLE or WAITING
grep "http-nio" td.txt | grep "State:" | sort | uniq -c

# All HikariCP connections are busy → look for "HikariPool" threads WAITING for connections
grep "HikariPool" td.txt
```

---

## 20+ Specific Spring Boot Error Messages

### Error 1: "Unable to start web server; nested exception is org.springframework.boot.web.server.WebServerException: Unable to start embedded Tomcat"

**Root cause**: Port binding failure. Another process is already using the configured port.

**Diagnostic steps**:
1. `lsof -i :<port>` or `netstat -tulpn | grep <port>` to find the process on the port.
2. Check if a previous instance of your application did not fully terminate (zombie process).
3. Check `management.server.port` — if it's the same as `server.port` without explicit configuration, both Tomcat and the management server may conflict.

**Fix**: Kill the other process or change `server.port` in `application.yml`.

---

### Error 2: "APPLICATION FAILED TO START: Parameter X of constructor in Y required a bean of type Z that could not be found"

**Root cause**: Missing bean definition. Most common after refactoring or when a `@ComponentScan` base package is wrong.

**Diagnostic steps**:
1. Check the `@SpringBootApplication` class package — component scanning starts from this package downward.
2. Use `/actuator/beans` to list all registered beans. Search for the missing bean.
3. Check if the bean is excluded by a `@ConditionalOnMissingBean`, `@ConditionalOnProperty`, or `@Profile`.
4. Check `/actuator/conditions` for the specific auto-configuration.

**Fix**: Move the class to a scanned package, add `@ComponentScan`, or remove conflicting `@Conditional` annotations.

---

### Error 3: "No qualifying bean of type 'javax.sql.DataSource' available"

**Root cause**: Spring Boot auto-configures a DataSource by default, but (a) no database driver is on the classpath, (b) no connection URL is configured, or (c) you added spring-data-jpa but didn't intend to use a database.

**Diagnostic steps**:
1. Check if `spring.datasource.url` is set.
2. Check if the driver dependency is present (e.g., `postgresql`, `mysql-connector-java`, `h2`).
3. If you don't want a DataSource: exclude `DataSourceAutoConfiguration`: `@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)`.

**Fix**: Add the driver dependency and configure `spring.datasource.url`, or exclude auto-configuration if no DB is needed.

---

### Error 4: "java.lang.IllegalStateException: Failed to load ApplicationContext ... Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'entityManagerFactory'"

**Root cause**: JPA/Hibernate entity scanning, mapping, or database connection issues.

**Diagnostic steps**:
1. Check the chain of `Caused by:` exceptions. Common sub-causes:
   - `PersistenceException: [PersistenceUnit: default] Unable to build Hibernate SessionFactory` → check entity mappings.
   - `Table "XXX" not found` → Flyway/Liquibase migrations missing or schema auto-generation disabled.
   - `Unable to determine dialect` → driver not found on classpath.
   - `Could not open connection` → database credentials or URL wrong.

**Fix**: Address the specific `Caused by` exception. Most commonly: run Flyway migrations, add `spring.jpa.hibernate.ddl-auto=validate`, or fix the database URL.

---

### Error 5: "org.springframework.beans.factory.BeanCurrentlyInCreationException: Error creating bean with name 'X': Requested bean is currently in creation: Is there an unresolvable circular reference?"

**Root cause**: Circular dependency in constructor injection. Bean A needs Bean B which needs Bean A.

**Diagnostic steps**:
1. Check the stack trace for the circular chain.
2. Use `/actuator/beans` and trace the dependency graph.

**Fix**: Break the cycle using one of:
- `@Lazy` annotation on one of the constructor parameters.
- Introduce a third bean to hold the shared logic.
- Use setter injection for one of the dependencies.
- Use `ObjectProvider<B>` or `Provider<B>` for lazy resolution.

---

### Error 6: "Cannot load driver class: com.mysql.cj.jdbc.Driver"

**Root cause**: Database driver JAR not on classpath. Spring Boot auto-configures JDBC but the driver is missing.

**Fix**: Add the dependency:
```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>
```
Or for PostgreSQL:
```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

---

### Error 7: "org.hibernate.LazyInitializationException: could not initialize proxy [com.example.Entity#1] - no Session"

**Root cause**: Accessing a lazily-loaded JPA relationship outside of a transaction context (most commonly in a `@Transactional(readOnly = true)` method, or after the transaction has committed, such as in a controller returning a JSON response).

**Diagnostic steps**:
1. Check if the calling method is `@Transactional`.
2. Check if an `OpenEntityManagerInViewFilter` (or `spring.jpa.open-in-view`) is enabled (it is by default in Spring Boot — but it's an anti-pattern).
3. Check if entity is being serialized (Jackson) outside transaction → serializer traverses relationships → LazyInitializationException.

**Fix**: Options in order of preference:
- Use a DTO/projection and fetch only needed data in the transaction.
- Use `JOIN FETCH` in the JPQL query to eagerly load the relationship.
- Use `@EntityGraph` for specific use cases.
- Enable `spring.jpa.open-in-view=true` as a temporary fix (not recommended for production).

---

### Error 8: "java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms"

**Root cause**: Connection pool exhausted. All connections are in use and the wait queue is full.

**Diagnostic steps**:
1. Check Hikari metrics: `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`.
2. Check if `hikaricp_connections_active = hikaricp_connections_max` — pool is maxed out.
3. Check for long-running queries holding connections → query `pg_stat_activity` in PostgreSQL or `SHOW PROCESSLIST` in MySQL.
4. Check for connection leaks — connections acquired but never returned (leak detection threshold: `spring.datasource.hikari.leak-detection-threshold=10000`).

**Fix**:
- Increase pool size: `spring.datasource.hikari.maximum-pool-size=20` (but ensure database `max_connections` can handle it).
- Find and fix slow queries (long transactions holding connections).
- Add connection timeout: `spring.datasource.hikari.connection-timeout=5000`.
- Fix connection leak: ensure connections are properly closed in finally blocks.

---

### Error 9: "org.springframework.transaction.CannotCreateTransactionException: Could not open JPA EntityManager for transaction"

**Root cause**: Transaction cannot be started. Usually database connectivity issue.

**Sub-causes**:
- Database is down or unreachable.
- Network partition.
- Database credentials changed.
- Database connection limit reached.

**Diagnostic steps**:
1. Can you connect from the application host? `psql -h <host> -U <user> -d <db>` or `mysql -h <host> -u <user> -p`.
2. Check database logs for rejected connections.
3. Check `max_connections` in PostgreSQL: `SELECT count(*) FROM pg_stat_activity;` vs `SHOW max_connections;`.

**Fix**: Restore database connectivity, increase `max_connections`, or start rejecting/queuing application requests gracefully.

---

### Error 10: "org.springframework.dao.DataIntegrityViolationException: could not execute statement; SQL [n/a]; constraint [xxx]"

**Root cause**: Database constraint violation. Common sub-types:
- `unique constraint violation` → duplicate key.
- `foreign key constraint violation` → referencing a non-existent parent.
- `not-null constraint violation` → inserting null into a required column.
- `check constraint violation` → value fails check constraint.

**Diagnostic steps**:
1. Read the constraint name in the exception message.
2. For unique: identify the duplicate value. Search for it in the database.
3. For foreign key: verify the referenced entity exists.
4. Add `spring.jpa.show-sql=true` during development to see the exact SQL.

**Fix**: Handle the duplicate case (idempotency), ensure parent entities exist before creating children, or fix the null column issue.

---

### Error 11: "org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'X': Unsatisfied dependency expressed through field 'Y'"

**Root cause**: Field injection failing. The field (marked `@Autowired`) has no matching bean.

**Diagnostic steps**:
1. Prefer constructor injection (the error messages are better).
2. Check if there are multiple beans of the same type → qualify with `@Qualifier` or `@Primary`.
3. Check if the bean's `@Profile`, `@ConditionalOnProperty` prevents it from being created.
4. Enable debug logging: `logging.level.org.springframework.beans=DEBUG`.

**Fix**: Provide the missing bean, add `@Qualifier`, or use constructor injection.

---

### Error 12: "Consider defining a bean of type 'X' in your configuration"

**Root cause**: Spring cannot find an auto-configured bean, and you haven't defined one manually.

**Common scenarios**:
- `RestTemplate` is not auto-configured anymore (Spring Boot 2.x+). Use `RestTemplateBuilder`.
- `JdbcTemplate` without Spring JDBC on classpath.
- `KafkaTemplate` without Spring Kafka configuration.
- Custom beans not scanned because component scan doesn't cover their package.

**Fix**: Either define the bean manually, add the missing starter dependency, or fix the component scan.

---

### Error 13: "org.springframework.kafka.KafkaException: Send failed; nested exception is org.apache.kafka.common.errors.TimeoutException"

**Root cause**: Kafka broker unreachable or request timed out.

**Diagnostic steps**:
1. Verify broker connectivity: `telnet <kafka-broker> 9092` or `nc -zv <kafka-broker> 9092`.
2. Check Kafka logs for errors.
3. Check `request.timeout.ms` and `delivery.timeout.ms` producer configs.
4. Check broker load — if under-provisioned, requests time out.

**Fix**: Increase timeouts (`spring.kafka.producer.properties[request.timeout.ms]=30000`), fix broker connectivity, or scale Kafka cluster.

---

### Error 14: "org.springframework.web.client.ResourceAccessException: I/O error on POST request for 'http://...': Connection refused"

**Root cause**: Downstream service is not running or not listening on the expected port.

**Diagnostic steps**:
1. Verify the downstream service is running: `curl http://<host>:<port>/actuator/health`.
2. Check Kubernetes service definition — has the selector changed?
3. Check DNS resolution: `nslookup <host>` from the application container.
4. Check network policies (Kubernetes) or security groups (AWS) — are they allowing traffic?

**Fix**: Start the downstream service, fix DNS, or update network policies.

---

### Error 15: "io.github.resilience4j.circuitbreaker.CallNotPermittedException: CircuitBreaker 'X' is OPEN and does not permit further calls"

**Root cause**: Circuit breaker tripped. Too many failures to downstream service.

**Diagnostic steps**:
1. Check why the downstream is failing — look for its errors.
2. Check circuit breaker metrics: `resilience4j_circuitbreaker_state`, `resilience4j_circuitbreaker_failure_rate`.
3. Check if the `failureRateThreshold` and `waitDurationInOpenState` are set correctly.
4. Is a retry mechanism retrying too aggressively, filling the circuit breaker?

**Fix**: Fix the downstream issue. As a temporary measure, increase thresholds or manually close the circuit breaker via actuator (if configured).

---

### Error 16: "java.lang.OutOfMemoryError: Java heap space"

**Root cause**: JVM heap exhausted. No more memory can be allocated on heap.

**Diagnostic steps**:
1. Check if `-XX:+HeapDumpOnOutOfMemoryError` was set. Analyze the heap dump.
2. Check heap size: `-Xmx`. Is it too small for the workload?
3. Check for memory leak (growing over time) vs. insufficient heap (transient spike).
4. See "Heap Dump Analysis" section above for detailed analysis.

**Fix**: Increase `-Xmx` (temporary), fix memory leak (permanent).

---

### Error 17: "java.lang.OutOfMemoryError: Metaspace"

**Root cause**: Metaspace (class metadata) exhausted. Too many classes loaded.

**Diagnostic steps**:
1. Check Metaspace usage: `jstat -gc <pid>`, look at MU/MC columns.
2. Check class count: `jstat -class <pid>`.
3. Common causes: Groovy scripting engine loading classes per evaluation, excessive CGLIB proxies, dynamic language class generation, or a ClassLoader leak on redeploy.

**Fix**: Increase `-XX:MaxMetaspaceSize` (temporary). Fix classloader leak or dynamic class generation (permanent).

---

### Error 18: "java.lang.IllegalStateException: The request object has been recycled and is no longer associated with this facade"

**Root cause**: Holding a reference to an HTTP request object after the request has completed (e.g., in an async thread).

**Diagnostic steps**:
1. Check for `HttpServletRequest` passed to an `@Async` method.
2. Check for references to `@RequestScope` beans in singleton-scoped beans.
3. Check for request attributes accessed in `CompletableFuture` chains.

**Fix**: Extract needed data from the request before passing to async code. Use `RequestContextFilter` with `setThreadContextInheritable(true)` (careful with thread pools).

---

### Error 19: "org.springframework.web.HttpMediaTypeNotAcceptableException: Could not find acceptable representation"

**Root cause**: Client sent an `Accept` header that the server cannot satisfy, or the content negotiation strategy is wrong.

**Diagnostic steps**:
1. Check the `Accept` header in the request.
2. Check what content types your controller produces (`@RequestMapping(produces = ...)`).
3. Check `spring.mvc.contentnegotiation` settings.
4. If returning an error from an exception handler, ensure the handler also produces an acceptable content type.

**Fix**: Ensure the controller accepts the client's content type, or configure content negotiation.

---

### Error 20: "io.lettuce.core.RedisConnectionException: Unable to connect to [host:6379]"

**Root cause**: Redis is unreachable from the application.

**Diagnostic steps**:
1. Verify Redis connectivity: `redis-cli -h <host> -p 6379 PING`.
2. Check Redis `maxclients` limit: `CONFIG GET maxclients`.
3. Check Redis logs for connection rejection.
4. Check Lettuce client configuration: `spring.redis.timeout`, `spring.redis.lettuce.pool.max-active`.
5. Sentinel/cluster mode: check if topology is correct.

**Fix**: Fix network connectivity, increase `maxclients`, or configure cluster topology correctly.

---

### Error 21: "org.apache.kafka.clients.consumer.CommitFailedException: Commit cannot be completed since the group has already rebalanced"

**Root cause**: Kafka consumer group rebalance during message processing. Consumer took too long to process a batch (exceeding `max.poll.interval.ms`).

**Diagnostic steps**:
1. Check consumer log for "Revoking previously assigned partitions" — rebalancing is happening.
2. Check `max.poll.interval.ms` (default: 300000 = 5 min) and `max.poll.records` (default: 500).
3. Check message processing time — if it's near or over `max.poll.interval.ms`, commits will fail.

**Fix**:
- Increase `max.poll.interval.ms` (e.g., 600000 = 10 min).
- Decrease `max.poll.records` to process smaller batches faster.
- Offload heavy processing to a separate thread pool (pause consumer, process, resume).
- Ensure processing is idempotent (since messages may be re-delivered after rebalance).

---

### Error 22: "org.springframework.web.client.HttpServerErrorException$ServiceUnavailable: 503 Service Unavailable"

**Root cause**: The upstream service is returning 503 (or the load balancer/ingress is).

**Diagnostic steps**:
1. Check the upstream service health: `/actuator/health` on the upstream.
2. Check if the upstream is under load (thread pool exhaustion).
3. Check if there's an ingress/load balancer between that might return 503 (e.g., Kubernetes Ingress or AWS ALB when no healthy targets).
4. Check Kubernetes pod status: `kubectl get pods` for the upstream. `CrashLoopBackOff`? `OOMKilled`?

**Fix**: Scale the upstream service, fix its health check, or increase circuit breaker thresholds.

---

## Emergency Procedures

### Graceful Degradation (Circuit Breaker)

When a downstream dependency is failing, the application should degrade gracefully rather than cascading failures.

**Pre-conditions**: Resilience4j circuit breakers configured on all external calls.

```java
@Service
public class OrderService {
    private final PaymentClient paymentClient;
    private final CircuitBreaker circuitBreaker;

    public OrderService(PaymentClient paymentClient, CircuitBreakerRegistry registry) {
        this.paymentClient = paymentClient;
        this.circuitBreaker = registry.circuitBreaker("paymentService");
    }

    public Order createOrder(CreateOrderRequest req) {
        PaymentResponse payment;
        try {
            payment = circuitBreaker.executeSupplier(
                () -> paymentClient.processPayment(req.getPayment())
            );
        } catch (CallNotPermittedException e) {
            // Circuit breaker open — fall back to graceful degradation
            payment = PaymentResponse.queued(req.getPayment().getAmount()); // Queue for later
            meterRegistry.counter("orders.payment.deferred").increment();
        }
        // ... continue with order creation
    }
}
```

**Emergency manual override**: If the circuit breaker is preventing recovery (e.g., it opened transiently and won't close fast enough):
```bash
# Transition to half-open manually (if configured)
curl -X POST http://localhost:8080/actuator/circuitbreakers/paymentService \
  -H "Content-Type: application/json" \
  -d '{"transition":"HALF_OPEN"}'
```

### Traffic Shedding

When the service is overloaded, shed excess traffic gracefully rather than failing all requests.

**Tomcat configuration**:
```yaml
server:
  tomcat:
    threads:
      max: 200
      min-spare: 20
    max-connections: 10000
    accept-count: 100
```

When `accept-count` is reached, Tomcat refuses new connections → load balancer should route to other instances.

**Application-level load shedding**:
```java
@Component
public class LoadSheddingFilter implements Filter {
    private final MeterRegistry registry;
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final int maxActiveRequests = 200;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        int current = activeRequests.incrementAndGet();
        try {
            if (current > maxActiveRequests) {
                ((HttpServletResponse) res).setStatus(503);
                req.getServletContext().setAttribute("load_shed", true);
                registry.counter("http.requests.shed").increment();
                return;
            }
            chain.doFilter(req, res);
        } finally {
            activeRequests.decrementAndGet();
        }
    }
}
```

### Restart Strategies

**Rolling Restart** (zero-downtime, orchestrated by Kubernetes or deployment tool):
1. Add a new instance to the load balancer.
2. Wait for it to pass health checks.
3. Remove an old instance from the load balancer.
4. Send `SIGTERM` to old instance.
5. Application must handle graceful shutdown:
   ```yaml
   server:
     shutdown: graceful
   spring:
     lifecycle:
       timeout-per-shutdown-phase: 30s
   ```
6. Repeat until all instances are replaced.

**Full Restart** (downtime required, for emergencies when rolling restart is not possible):
1. Notify stakeholders: "Service X will be unavailable for approximately 90 seconds."
2. Stop all instances.
3. Verify database is still accessible (connections freed).
4. Start instances one at a time.
5. Verify each instance passes health checks before starting the next.
6. Run smoke tests before declaring recovery.

**When to use each**:
- Rolling restart: configuration changes, memory leak building up (buy time while investigating), new deployment.
- Full restart: database connection pool corruption, file descriptor exhaustion (only full restart can reclaim some kernel resources), deadlock in shared resource.

### Database Failover

For PostgreSQL with streaming replication:

1. **Detection**: Health check fails with `SQLTransientConnectionException` for > 30 seconds.
2. **Verify**: `SELECT pg_is_in_recovery();` on the replica — if false, it's now writable.
3. **Failover procedure**:
   ```bash
   # On the replica (new primary):
   pg_ctl promote -D /var/lib/postgresql/data

   # Verify promotion:
   psql -c "SELECT pg_is_in_recovery();"  # Should return 'f' (false)
   ```
4. **Update application**:
   - Update `spring.datasource.url` to point to the new primary.
   - Or use a connection string with multiple hosts: `jdbc:postgresql://primary-host,replica-host/db?targetServerType=primary`
   - Restart or refresh DataSource if runtime refresh is supported.
5. **Verify**: Run smoke tests, check data integrity.
6. **Post-failover**: Rebuild the old primary as a new replica (pg_basebackup). Update replication slots.

---

## Post-Incident Template

```markdown
# Incident Postmortem: [INCIDENT-XXX]

## Summary
[One sentence description of what happened and the customer impact.]

## Timeline (All times in UTC)
| Time | Event |
|------|-------|
| HH:MM | [First alert / detection] |
| HH:MM | [On-call engineer acknowledged] |
| HH:MM | [Incident declared, incident commander assigned] |
| HH:MM | [Initial diagnosis] |
| HH:MM | [Mitigation deployed] |
| HH:MM | [Service recovered] |
| HH:MM | [Monitoring confirms recovery] |

## Impact
- **Duration**: [X minutes]
- **Users affected**: [X% of users / all users in region Y / etc.]
- **Business impact**: [Revenue lost, orders delayed, SLA violation, etc.]
- **Services affected**: [List of services]

## Root Cause
[What specifically caused the incident? Be precise.]

## Contributing Factors
- [Missing alert]
- [Configuration error]
- [Insufficient testing]
- [Operational procedure gap]
- [Dependency failure]

## Detection
- **How was it detected?** [Monitoring alert / customer report / internal report]
- **Time to detect**: [X minutes]
- **Could it have been detected faster?** [Yes/No, explanation]

## Resolution
[Steps taken to resolve the incident.]

## Action Items
| # | Action | Owner | Priority | Due Date | Status |
|---|--------|-------|----------|----------|--------|
| 1 | [Preventive action] | @handle | P0/P1/P2 | YYYY-MM-DD | Open |
| 2 | [Monitoring improvement] | @handle | P1 | YYYY-MM-DD | Open |
| 3 | [Automation to prevent recurrence] | @handle | P1 | YYYY-MM-DD | Open |

## Prevention Measures
[What systemic changes will prevent this class of incident?]

## Lessons Learned
[What new knowledge was gained? What should change in our practices?]

## Attachments
- [Link to monitoring dashboard during incident]
- [Link to relevant logs]
- [Link to heap/thread dumps if applicable]
```

---

## On-Call Best Practices

### Communication During Incidents
- **First 5 minutes**: Acknowledge the alert. Post in the incident channel: "Acknowledging alert #X. Investigating. Will update in 15 minutes."
- **Every 15-30 minutes**: Post a status update even if there's no change: "Still investigating. Error rate is stable at 5%. Root cause not yet identified."
- **When you have a hypothesis**: "I suspect [X] is causing [Y]. I'm testing this by [action]. This may cause [expected impact]. Should I proceed?"
- **When engaging others**: "@username, we have a Sev1 incident. Can you join? Context: [2 sentences]."
- **Using clear language**: Say "Service is down for customers in us-east-1" not "We have a partial outage manifesting as elevated 5xx rates."

### When to Escalate
- **Immediately**: If customer impact is high (users can't complete core transactions) AND you haven't identified the cause within 15 minutes.
- **After 30 minutes**: If you've identified the cause but can't fix it alone (need DB admin, network engineer, etc.).
- **After 60 minutes**: If the incident is still ongoing. Escalate to senior leadership if customer impact is ongoing.
- **When in doubt**: Escalate. It's better to pull someone in unnecessarily than to delay a fix.

### Blameless Postmortems
- Focus on "what" and "how" not "who."
- Ask "How did this happen?" not "Who caused this?"
- Every action taken during the incident was the best decision with the information available at the time. Do not second-guess decisions retrospectively.
- Counterfactuals are useful for learning: "If we had alert X, the detection time would have been 2 minutes instead of 20."
- Counterfactuals are NOT useful for blame: "If Alice had tested her change better, this wouldn't have happened."
- The postmortem should produce actionable items, not scapegoats.
- Postmortems should be shared widely. Organizational learning is the goal.

### On-Call Hygiene
- **Handoff**: Always hand off to the next on-call engineer with a summary of open issues, ongoing investigations, and any known fragile states.
- **Alert fatigue**: If an alert fires more than once per shift without requiring action, tune it or remove it. Every false alarm reduces trust in the alerting system.
- **Runbooks**: After resolving an incident, update the runbook. If no runbook exists, write one. Future you (or your teammate) will thank you.
- **Sleep**: If you've been awake for 2+ hours on a night incident, escalate for relief. Tired engineers make bad decisions.
- **Post-on-call**: Document everything you learned. Share with the team. Improve the system so the next person has an easier time.
