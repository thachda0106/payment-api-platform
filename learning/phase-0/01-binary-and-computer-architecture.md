# Module 01 — Binary & Computer Architecture

## 1.1 Why This Module Matters

Every line of code you write executes on a CPU. When your Java microservice handles 10,000 payment requests per second, the JVM is translating bytecode into machine instructions that execute on physical hardware. Understanding what happens at that level transforms you from someone who writes code into someone who understands WHY code behaves the way it does.

**Payment platform relevance**:
- JVM GC pause time causing a payment timeout? That's heap fragmentation at the cache-line level.
- Redis sorted set for velocity checking? That's a skiplist — a probabilistic data structure you'll implement in Module 02.
- PostgreSQL B-tree index lookup? That's O(log n) — you'll analyze this complexity in Module 03.
- Kafka producer batching throughput? That's bounded by L3 cache size and memory bandwidth.

---

## 1.2 Binary, Bits, Bytes, and Hexadecimal

### Why Computers Use Binary

Transistors have two states: ON (1) and OFF (0). Everything in a computer — numbers, text, images, code — is ultimately represented as binary. Understanding this isn't academic; it's practical. When you see `0xFF` in a hex dump, that's `11111111` in binary and `255` in decimal. When you debug a network packet, you're reading hex.

### Bits and Bytes

A **bit** is a single binary digit (0 or 1).
A **byte** is 8 bits (can represent 256 values: 0-255).

```
1 byte  = 8 bits   = 2^8  = 256 values
1 KB    = 1024 bytes (2^10)
1 MB    = 1024 KB    (2^20)
1 GB    = 1024 MB    (2^30)
```

**Why 1024 not 1000?** Because computers use powers of 2. Memory is addressed in binary, so boundaries align to powers of 2. (Storage manufacturers use 1000 because it makes drives look bigger — this is why your "1TB" drive shows as ~931GB in your OS.)

### Binary to Decimal Conversion

```
Binary:  1    0    1    1    0    1    0    0
        128   64   32   16    8    4    2    1
        = 128 + 0 + 32 + 16 + 0 + 4 + 0 + 0 = 180

Decimal 180 = Binary 10110100
```

### Hexadecimal

Hex uses 16 digits: 0-9, A-F. Each hex digit represents exactly 4 bits (a nibble).

```
Binary:  1011 0100
Hex:       B    4   → 0xB4

0x1F = 31 decimal
0xFF = 255 decimal (maximum 8-bit value)
0x00 = 0 decimal
```

**Why hex matters**: Reading raw bytes in binary is unwieldy. `1011010011100010` vs `0xB4E2`. Hex is compact and maps directly to binary. Every network packet analyzer, hex editor, and memory dump uses hex. You will read hex when debugging Kafka wire protocol, TLS handshakes, and PostgreSQL page structures.

### Two's Complement (Negative Numbers)

How do you represent -5 in binary? Two's complement is the standard:

1. Write the positive number: `0000 0101` (5)
2. Flip all bits (one's complement): `1111 1010`
3. Add 1: `1111 1011` (-5)

**Why this works**: `5 + (-5) = 0` should work in binary addition:
```
  0000 0101  (5)
+ 1111 1011  (-5)
────────────
  0000 0000  (0, with carry overflow — discarded)
```

**Range for 8-bit signed integer**: -128 to 127. For 32-bit: -2,147,483,648 to 2,147,483,647. For 64-bit: roughly ±9.2 × 10^18.

**Payment relevance**: A wallet balance stored as a 32-bit signed integer can hold up to ~2.1 billion VND. That sounds like a lot — until your platform grows and you hit integer overflow. Use `BIGINT` (64-bit) or `NUMERIC` for money. PostgreSQL `BIGINT` as cent values (smallest currency unit) can hold up to ~92 quadrillion units — safe for any payment platform.

### IEEE 754 Floating Point

Floating point is how computers represent real numbers (3.14, -0.001, 1.5 × 10^9).

**Structure** (32-bit single precision):
```
Sign  Exponent (8 bits)   Mantissa/Fraction (23 bits)
 1 bit
```

The value is: `(-1)^sign × 2^(exponent - 127) × 1.fraction`

**The problem**: Floating point is APPROXIMATE. Not all decimal numbers can be represented exactly in binary:
```
0.1 + 0.2 = 0.30000000000000004  (not 0.3!)
```

**Payment rule**: NEVER use FLOAT or DOUBLE for money. Use `DECIMAL/NUMERIC` in databases, `BigDecimal` in Java, `decimal.Decimal` in Python, `int64` (cents) in Go, `BigInt` or integer cents in Node.js. Money must be EXACT.

### Endianness

Multi-byte values can be stored two ways:

| Value | Address | Big-Endian | Little-Endian |
|-------|---------|-----------|--------------|
| 0x12345678 | 0x00 | 0x12 | 0x78 |
| | 0x01 | 0x34 | 0x56 |
| | 0x02 | 0x56 | 0x34 |
| | 0x03 | 0x78 | 0x12 |

- **Big-Endian**: Most significant byte first. Network byte order. Used by: TCP/IP, Java JVM.
- **Little-Endian**: Least significant byte first. Used by: x86 CPUs, ARM (configurable).

Java abstracts this (`ByteBuffer.order()`). C requires explicit handling (`htonl()`, `ntohl()`). Go's `encoding/binary` handles it. Node.js `Buffer` uses LE by default but supports BE.

**Payment relevance**: When parsing ISO 8583 messages (card payment protocol) or SWIFT messages (bank transfers), you must handle endianness correctly. A reversed 4-byte amount field could turn 100,000 VND into billions.

---

## 1.3 CPU Architecture

### The Von Neumann Architecture

```
┌─────────────────────────────────────────────────────────┐
│                        CPU                               │
│  ┌──────────┐   ┌──────────┐   ┌──────────────┐        │
│  │  Control │   │   ALU    │   │  Registers   │        │
│  │   Unit   │──▶│(Arithmetic│◀──│  (R1, R2...) │        │
│  │          │   │Logic Unit)│   │  PC, SP, IR  │        │
│  └────┬─────┘   └──────────┘   └──────┬───────┘        │
│       │                               │                 │
│       ▼                               ▼                 │
│  ┌──────────────────────────────────────────────┐      │
│  │              System Bus (Address + Data)       │      │
│  └──────────────────────┬───────────────────────┘      │
└─────────────────────────┼──────────────────────────────┘
                          │
┌─────────────────────────▼──────────────────────────────┐
│                    MEMORY (RAM)                          │
│  Address 0x0000: [instruction]                          │
│  Address 0x0004: [instruction]                          │
│  Address 0x0008: [data]                                  │
│  ...                                                     │
└─────────────────────────────────────────────────────────┘
```

**Key components**:
- **ALU** (Arithmetic Logic Unit): Performs math (add, subtract, AND, OR, shift)
- **Registers**: Ultra-fast storage inside the CPU. 16-32 general-purpose registers (x86-64 has 16). Operations on registers are 1 cycle.
- **PC** (Program Counter): Points to the NEXT instruction to execute
- **IR** (Instruction Register): Holds the CURRENT instruction being executed
- **SP** (Stack Pointer): Points to the top of the stack in memory

### The Instruction Cycle (Fetch-Decode-Execute)

Every instruction goes through this cycle:

```
┌─────────┐     ┌─────────┐     ┌──────────┐     ┌──────────┐
│  FETCH  │────▶│ DECODE  │────▶│ EXECUTE  │────▶│  WRITE   │
│         │     │         │     │          │     │  BACK    │
│Load inst│     │Determine│     │Perform   │     │Store     │
│from PC  │     │operation│     │operation │     │result    │
└─────────┘     └─────────┘     └──────────┘     └──────────┘
     ▲                                                      │
     └──────────────────────────────────────────────────────┘
                         PC += instruction size
```

**Example**: `ADD R1, R2, R3` (R1 = R2 + R3)
1. **FETCH**: Load `ADD R1, R2, R3` from memory address in PC
2. **DECODE**: Determine it's an ADD, operands are R2 and R3, destination R1
3. **EXECUTE**: ALU computes R2 + R3
4. **WRITE BACK**: Store ALU result into R1
5. PC += 4 (next instruction)

### Pipelining

Modern CPUs don't execute one instruction at a time. They overlap execution:

```
Clock Cycle:  1     2     3     4     5     6     7     8
Instruction1: FETCH DECODE EXEC  WRITE
Instruction2:       FETCH DECODE EXEC  WRITE
Instruction3:             FETCH DECODE EXEC  WRITE
Instruction4:                   FETCH DECODE EXEC  WRITE
```

With 4-stage pipeline: 4 instructions complete every 4 cycles (1 IPC — Instruction Per Cycle), but it takes 7 cycles total. Without pipelining, 4 instructions take 16 cycles. Pipeline = 2.3x throughput improvement.

**Pipeline hazards**:
- **Data hazard**: Instruction 2 needs the result of Instruction 1 (still in pipeline). Fix: forwarding/bypassing (send result directly to next instruction) or stall (insert NOP).
- **Control hazard**: Branch instruction changes PC. Pipeline has already fetched wrong instructions. Fix: branch prediction.

### Branch Prediction

Modern CPUs guess which way a branch will go:

```
if (balance < amount) {   // BRANCH: take if balance < amount
    return INSUFFICIENT;   // RARE path (most users have sufficient balance)
} else {
    processPayment();       // COMMON path
}
```

The CPU predicts: "probably don't take the branch" (based on history). It speculatively executes `processPayment()`. If prediction was correct: no pipeline stall. If wrong: flush pipeline, restart from correct path (~10-20 cycle penalty).

**Dynamic branch prediction**: CPUs track branch history. "This branch was taken the last 3 times → predict taken." Modern predictors (TAGE, Perceptron) achieve >95% accuracy.

**Payment relevance**: In a tight loop processing payments, a mispredicted branch on a rare error path (INSUFFICIENT_BALANCE) costs ~15 cycles. On a 3GHz CPU, that's 5 nanoseconds. Not worth optimizing. But in a hot loop executing millions of times — consider making the common path the predicted path (use `__builtin_expect` in C/C++, or trust that the CPU's predictor will learn).

### Superscalar Execution

Modern CPUs can execute MULTIPLE instructions per cycle (superscalar). They have multiple execution units:

```
┌────────────────────────────────────────────────────┐
│                CPU Core                             │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────┐ │
│  │  ALU 0  │ │  ALU 1  │ │  ALU 2  │ │  Load/   │ │
│  │(integer)│ │(integer)│ │  (FPU)  │ │  Store   │ │
│  └─────────┘ └─────────┘ └─────────┘ └──────────┘ │
└────────────────────────────────────────────────────┘
```

The CPU analyzes the instruction stream (out-of-order execution), finds independent instructions, and executes them in parallel. Instructions must be independent (no data dependencies). The CPU's reorder buffer (ROB) ensures results are committed in program order.

**Payment relevance**: Code that appears sequential in source may execute in parallel on the CPU. This is why benchmark results can be surprising — and why micro-optimizations without measurement are usually wrong.

---

## 1.4 Memory Hierarchy

### Why Hierarchy Exists

Fast memory is expensive and small. Slow memory is cheap and large. The hierarchy balances cost and performance:

```
┌────────────────────────────────────────────────────────────┐
│  REGISTERS   │   ~1 cycle   │   ~1 KB     │  CPU internal  │ Fastest
├──────────────┼──────────────┼─────────────┼────────────────┤
│  L1 CACHE    │   ~4 cycles  │   32-64 KB  │  Per core      │
├──────────────┼──────────────┼─────────────┼────────────────┤
│  L2 CACHE    │   ~12 cycles │   256-512 KB│  Per core      │
├──────────────┼──────────────┼─────────────┼────────────────┤
│  L3 CACHE    │   ~40 cycles │   8-32 MB   │  Shared        │
├──────────────┼──────────────┼─────────────┼────────────────┤
│  RAM         │   ~100 cycles│   16-256 GB │  Main memory   │
├──────────────┼──────────────┼─────────────┼────────────────┤
│  SSD         │ ~100,000 cyc │   256GB-4TB │  Persistent    │
├──────────────┼──────────────┼─────────────┼────────────────┤
│  HDD/NETWORK │ ~1,000,000+  │   TB+       │  Remote/slow    │ Slowest
└──────────────┴──────────────┴─────────────┴────────────────┘
```

**The crucial insight**: Accessing RAM is ~100x slower than accessing L1 cache. Accessing disk is ~100,000x slower. This gap is why caching is everywhere — from CPU caches to Redis to CDN edge caches.

### Cache Lines

Data moves between cache and RAM in fixed-size blocks called **cache lines** (typically 64 bytes on x86). When you access a single byte, the CPU loads the entire 64-byte cache line containing that byte.

**Why this matters**: Sequential array access is FAST because each cache line load brings 64 bytes (16 integers) at once. Random access across a large structure is SLOW because each access likely needs a different cache line (cache miss).

**Payment example**: Iterating over `journal_lines` ordered by `line_id` (sequential) will be fast. Iterating by `account_id` on an unordered column will trigger cache misses on every access. This is why index ordering matters — a B-tree stores keys in order, so traversing the B-tree is sequential disk access.

### Cache Coherence (MESI Protocol)

In multi-core CPUs, each core has its own L1/L2 cache. What if Core 1 writes to address X, and Core 2 has X in its cache? The MESI protocol ensures consistency:

| State | Meaning |
|-------|---------|
| **M**odified | This cache has the ONLY valid copy. Dirty (different from RAM). |
| **E**xclusive | This cache has the ONLY valid copy. Clean (same as RAM). |
| **S**hared | Multiple caches have copies. All clean. |
| **I**nvalid | This cache line is stale. Must reload from another cache or RAM. |

**False sharing**: Two cores access DIFFERENT variables that happen to be on the SAME cache line. They're not actually sharing data, but the cache coherence protocol treats it as sharing. Each write invalidates the other core's cache line → constant cache misses → performance tanks.

```
// False sharing example: counter_A and counter_B on the same cache line
volatile long counter_A;  // Core 1 increments this
volatile long counter_B;  // Core 2 increments this
// These are independent, but every write invalidates the OTHER core's cache line!
```

**Fix**: Pad variables to cache line boundaries (Java: `@Contended`, Go: pad struct fields, C: `__attribute__((aligned(64)))`).

### TLB (Translation Lookaside Buffer)

Virtual memory translates virtual addresses (used by programs) to physical addresses (used by RAM). The TLB is a cache for these translations.

- TLB hit: ~1 cycle (translation cached)
- TLB miss: ~30-100 cycles (must walk page tables in memory)
- Page size: 4KB (standard) or 2MB/1GB (huge pages)

**Payment relevance**: PostgreSQL uses huge pages (`huge_pages = on`) for shared_buffers to reduce TLB misses. A 32GB shared_buffers with 4KB pages = 8 million pages. TLB has ~1000 entries. Without huge pages, 99.99% of accesses are TLB misses.

---

## 1.5 Key Concepts Summary

| Concept | What It Is | Why It Matters |
|---------|-----------|---------------|
| Two's complement | How negative numbers are stored | Integer overflow in balance fields |
| IEEE 754 | How real numbers are stored | NEVER use for money (approximate) |
| Endianness | Byte ordering | Network protocols, binary parsing |
| Cache line | 64-byte block loaded from RAM | Sequential access is 100x faster than random |
| MESI | Cache coherence protocol | False sharing kills multi-core performance |
| Pipelining | Overlapping instruction execution | Branch misprediction = 15-cycle penalty |
| TLB | Virtual→physical address cache | Huge pages reduce misses for large DB buffers |
| Von Neumann | CPU + shared memory architecture | The model behind every computer you program |

---

## 1.6 Hands-On Exercises

### Exercise 1.1 — Binary Converter
Write a function that converts between decimal and binary/hex. No built-in conversion functions.
- Input: `decimalToBinary(180)` → Output: `"10110100"`
- Input: `binaryToDecimal("10110100")` → Output: `180`
- Input: `decimalToHex(180)` → Output: `"B4"`
- Input: `hexToDecimal("B4")` → Output: `180`

### Exercise 1.2 — Floating Point Accuracy
Write a program that demonstrates floating point inaccuracy:
- Compute `0.1 + 0.2` and print the result to 20 decimal places
- Compute `0.1 + 0.2 == 0.3` — what does it return? Why?
- Add 0.1 ten times. Sum should be 1.0. What is it actually?
- (Java) Use `BigDecimal`. (Python) Use `Decimal`. (Go) Use integer cents. (JS) Use `toFixed(2)` for display only.

### Exercise 1.3 — Cache Effect Demonstration
Create two versions of matrix multiplication:
- **Version A**: Row-major access (`matrix[i][j]`)
- **Version B**: Column-major access (`matrix[j][i]`)
- Measure execution time for 1000×1000 matrices
- Explain why one is 10-50x faster (cache line locality)

### Exercise 1.4 — Endianness Detector
Write a program that detects whether your CPU is big-endian or little-endian:
- Store a 32-bit value `0x12345678` in memory
- Read the first byte
- If first byte is `0x12` → big-endian. If `0x78` → little-endian.

### Exercise 1.5 — False Sharing Demo
Write a multi-threaded program with two counters on the same cache line vs. padded to 64 bytes. Measure throughput difference. (Java: use `@jdk.internal.vm.annotation.Contended`. C/Go: use aligned struct fields.)

### Exercise 1.6 — Pipeline Visualization
Given this instruction sequence, trace execution through a 4-stage pipeline. Identify hazards. Calculate total cycles with and without forwarding.
```
1: LOAD  R1, [A]    // R1 = memory[A]
2: ADD   R2, R1, 5  // R2 = R1 + 5   (depends on instruction 1!)
3: STORE [B], R2     // memory[B] = R2 (depends on instruction 2!)
4: LOAD  R3, [C]    // R3 = memory[C] (independent)
```

## 1.7 Self-Assessment

- [ ] Can convert any 8-bit binary number to decimal and hex in my head
- [ ] Can explain why `0.1 + 0.2 != 0.3` in floating point
- [ ] Understand the fetch-decode-execute cycle for a simple instruction
- [ ] Can explain why sequential array access is 100x faster than random access
- [ ] Know what happens during a cache miss
- [ ] Understand why `BigDecimal` exists and when to use it
- [ ] Can detect the endianness of a machine with a simple program
