# Module 02 — Networking

## 2.1 Why Networking Matters for Payment Platforms

Every payment API call is a network request. Every Kafka message is a TCP packet. Every gRPC call between services is an HTTP/2 stream. Every TLS handshake is a cryptographic negotiation over TCP. When a payment times out, you need to know: was it TCP retransmission? DNS resolution failure? TLS handshake latency? Load balancer health check failure?

---

## 2.2 The OSI Model (Simplified)

```
┌─────────────────────────────────────────────────────────┐
│ 7. Application     │ HTTP, gRPC, Kafka, DNS, SMTP       │ ← Your code
├────────────────────┼────────────────────────────────────┤
│ 4. Transport       │ TCP, UDP                          │ ← Ports, connections
├────────────────────┼────────────────────────────────────┤
│ 3. Network         │ IP, ICMP                          │ ← Addresses, routing
├────────────────────┼────────────────────────────────────┤
│ 2. Data Link       │ Ethernet, Wi-Fi                   │ ← MAC addresses
├────────────────────┼────────────────────────────────────┤
│ 1. Physical        │ Copper, fiber, radio              │ ← Bits on wire
└────────────────────┴────────────────────────────────────┘
```

Real-world: the OSI model is a teaching tool. The actual Internet uses the TCP/IP model: Application → Transport (TCP/UDP) → Internet (IP) → Link.

---

## 2.3 TCP Deep Dive

TCP provides: reliable, ordered, error-checked delivery of a stream of bytes between applications.

### The Three-Way Handshake

```
Client                              Server
  │                                    │
  │──── SYN (seq=x) ──────────────────▶│  State: SYN_SENT → SYN_RCVD
  │                                    │
  │◀─── SYN-ACK (seq=y, ack=x+1) ─────│  State: SYN_RCVD → ESTABLISHED
  │                                    │
  │──── ACK (ack=y+1) ────────────────▶│  State: ESTABLISHED
  │                                    │
  │──── Data can now flow ────────────▶│
```

**Why three-way?** Two-way would allow old duplicate SYNs to create half-open connections. The third message confirms BOTH sides can send and receive. The client proves it received the server's SYN-ACK.

### Data Transfer

```
Client                              Server
  │──── SEQ=100, ACK=300, DATA(100B)─▶│
  │◀─── SEQ=300, ACK=200 ─────────────│  (acknowledges bytes 100-200)
```

**Sequence numbers**: Byte-stream position, NOT packet number. If the client sends 100 bytes at SEQ=100, the next SEQ is 200. The server ACK=200 means "I've received all bytes up to 200."

**Cumulative ACK**: ACK=200 means ALL bytes before 200 have been received. If packets arrive out of order, TCP buffers them until the gap is filled.

### Flow Control (Sliding Window)

The receiver advertises a **window size** (`rwnd` — receiver window): "I can accept up to N more bytes." The sender must not send more than the window allows. This prevents a fast sender from overwhelming a slow receiver.

```
Client (fast)                        Server (slow)
  │──── DATA (window=10000) ────────▶│
  │──── DATA (window=8000)  ────────▶│  (receiver processed 2000 bytes)
  │──── DATA (window=5000)  ────────▶│
  │──── DATA (window=0)     ────────▶│  Buffer full! Sender MUST stop.
  │                                    │  ...server processes data...
  │◀─── ACK (window=5000) ────────────│  Window reopened. Resume sending.
```

**Zero window**: When `rwnd=0`, the sender sends periodic "window probes" (1 byte) to check if the window has opened. Without this, the sender would wait forever (deadlock).

### Congestion Control

Flow control prevents overwhelming the RECEIVER. Congestion control prevents overwhelming the NETWORK.

**Slow Start**: TCP starts with a small congestion window (`cwnd`, initially 1-10 MSS — Maximum Segment Size). For every ACK received, `cwnd` doubles. This is exponential growth until a threshold or loss.

**Congestion Avoidance (AIMD)**: After `cwnd` reaches `ssthresh`, switch to linear growth: `cwnd += MSS * MSS / cwnd` per ACK. On packet loss (triple duplicate ACK or timeout): `ssthresh = cwnd / 2`, `cwnd = 1` (timeout) or `cwnd = ssthresh` (fast recovery).

```
cwnd
 ^
 |     ╱╲
 |    ╱  ╲         ╱╲
 |   ╱    ╲       ╱  ╲       ╱
 |  ╱      ╲     ╱    ╲     ╱
 | ╱        ╲   ╱      ╲   ╱
 |╱          ╲ ╱        ╲ ╱
 └─────────────────────────────▶ time
     loss     loss       loss
```

**Payment relevance**: TCP slow start means a new connection starts SLOW. Connection pooling (HikariCP for PostgreSQL, HTTP connection pools for REST calls) avoids repeatedly paying the slow-start penalty. Kafka batching mitigates it by sending large buffers, reducing the number of round-trips to reach full throughput.

### TIME_WAIT

After closing a connection (FIN → FIN-ACK → ACK), the endpoint that initiated the close enters TIME_WAIT state for 2 × MSL (Maximum Segment Lifetime, typically 120 seconds). During TIME_WAIT, the port pair cannot be reused.

**Why?** Delayed duplicate packets from the old connection could be mistaken for a new connection on the same port pair. TIME_WAIT ensures all old packets have expired before the port pair is reused.

**Problem**: High connection churn (opening/closing thousands of connections) exhausts ephemeral ports. All ports in TIME_WAIT.

**Fixes**: Connection pooling (reuse connections), SO_REUSEADDR (allow rebinding TIME_WAIT ports), reduce TIME_WAIT duration (kernel tuning: `net.ipv4.tcp_tw_reuse=1` for clients). This is why connection pools are essential for high-throughput services.

---

## 2.4 UDP

UDP is connectionless, unreliable, no ordering guarantees. Just fire and forget.

**Use cases**: DNS (fast, small queries — if lost, retry), streaming (video/audio — losing a packet is better than buffering), QUIC (HTTP/3 — built on top of UDP with custom reliability), gaming (low latency > perfect delivery).

**Payment relevance**: DNS for service discovery (should be fast). Monitoring metrics (statsd sends UDP — if a metric is lost, no big deal). Not for payment data — TCP for payments.

---

## 2.5 DNS

### Hierarchy

```
. (root servers)
├── .com
│   ├── stripe.com
│   │   ├── api.stripe.com  → A record → 52.x.x.x
│   │   └── www.stripe.com  → CNAME → stripe.com
│   └── example.com
├── .org
└── .vn
```

### Record Types

| Type | Purpose | Example |
|------|---------|---------|
| **A** | IPv4 address | `api.payment.vn → 203.0.113.1` |
| **AAAA** | IPv6 address | `api.payment.vn → 2001:db8::1` |
| **CNAME** | Canonical name (alias) | `www.payment.vn → payment.vn` |
| **MX** | Mail exchange | `payment.vn → mail.payment.vn (priority 10)` |
| **TXT** | Arbitrary text | SPF/DKIM records, domain verification |
| **NS** | Name server | `payment.vn → ns-1.awsdns.com` |
| **SRV** | Service location | `_http._tcp.payment.vn → port 8080, target api.payment.vn` |

### Resolution

1. Check local cache (browser, OS, `nscd`/`systemd-resolved`)
2. Query recursive resolver (ISP, 8.8.8.8, 1.1.1.1)
3. Recursive resolver queries root servers → .com servers → payment.vn authoritative server
4. Result cached at each level according to TTL

**Payment relevance**: DNS is a critical dependency. If CoreDNS (Kubernetes DNS) is slow or down, service-to-service communication fails. DNS TTL controls how long cached results are used — low TTL (30s) for fast failover, but higher DNS query load; high TTL (300s) for stability but slow failover. Set appropriate TTLs and monitor DNS resolution latency.

### /etc/hosts and /etc/resolv.conf

- `/etc/hosts`: Static hostname→IP mappings. Checked BEFORE DNS.
- `/etc/resolv.conf`: DNS server configuration (`nameserver 1.1.1.1`, `search payment.internal`).
- In containers: Kubernetes manages these via CoreDNS and pod configuration.

---

## 2.6 HTTP/1.1, HTTP/2, HTTP/3

### HTTP/1.1

- Text-based protocol
- **Persistent connections** (Connection: keep-alive) — reuse TCP connection for multiple requests
- **Pipelining**: Send multiple requests without waiting for responses (rarely used — head-of-line blocking)
- **Head-of-line blocking**: If the first request in a pipeline takes 5 seconds, subsequent responses are blocked even if they're ready
- One active request per connection at a time (without pipelining)
- Workaround: open 6 parallel connections (browser limit)

### HTTP/2

- Binary protocol (not text)
- **Multiplexing**: Multiple concurrent streams over a single TCP connection. No head-of-line blocking at the application level
- **Header compression** (HPACK): Compress repetitive headers (cookies, User-Agent)
- **Server push**: Server can send resources before client requests them
- **Stream prioritization**: Client can indicate which streams are more important
- BUT: TCP-level head-of-line blocking still exists — if a TCP packet is lost, ALL streams stall until retransmission

**gRPC uses HTTP/2**: gRPC is built on HTTP/2 for multiplexing, binary framing, and header compression. Every gRPC call = one HTTP/2 stream.

### HTTP/3 (QUIC)

- Built on UDP, not TCP
- **Eliminates TCP head-of-line blocking**: Each stream is independent — a lost packet only affects its own stream
- **0-RTT connection establishment**: For previously connected clients, data can be sent immediately (no handshake wait)
- **Connection migration**: Connection survives network changes (WiFi → cellular) because it's identified by a connection ID, not IP+port
- **Built-in TLS 1.3**: Always encrypted, no separate TLS negotiation

---

## 2.7 TLS 1.3 Handshake

```
Client                                    Server
  │                                          │
  │── ClientHello ─────────────────────────▶│
  │   (supported cipher suites, key share)   │
  │                                          │
  │◀── ServerHello ──────────────────────────│
  │   (chosen cipher, certificate,           │
  │    key share, Finished)                  │
  │                                          │
  │── Finished ────────────────────────────▶│
  │                                          │
  │── Encrypted Application Data ──────────▶│  ← 1-RTT (one round trip)
  │◀── Encrypted Application Data ──────────│
```

**1-RTT**: In TLS 1.3, the second flight already includes the server's Finished message AND application data can be sent immediately. This is faster than TLS 1.2's 2-RTT handshake.

**0-RTT**: For resumed connections, the client can send application data in the FIRST flight (ClientHello). This is called "0-RTT" or "early data." BUT: 0-RTT data is vulnerable to replay attacks. Only use for idempotent requests (GET, not POST).

**Certificate chain**:
```
Root CA (pre-installed in OS/browser)
  └── Intermediate CA (cross-signed by Root)
       └── Server Certificate (api.payment.vn)
```
The server sends its certificate + intermediate certificates. The client validates the chain up to a trusted Root CA.

**mTLS (Mutual TLS)**: Both client AND server present certificates. Used for service-to-service communication. Istio service mesh uses mTLS — every pod has a certificate, every inter-pod call is authenticated and encrypted.

**Payment relevance**: TLS 1.3 for all external APIs (PCI DSS requirement 4). mTLS for internal service communication. Certificate expiry monitoring (cert-manager auto-renews). HSTS header to enforce HTTPS.

---

## 2.8 Load Balancing

### L4 (TCP) Load Balancing

Operates at the transport layer. Distributes TCP connections to backends. Doesn't inspect HTTP — just forwards bytes. Fast. Used by: AWS NLB, HAProxy (TCP mode), Kubernetes Service (kube-proxy).

**Algorithms**:
- **Round-robin**: Cycle through backends sequentially
- **Least connections**: Backend with fewest active TCP connections
- **IP hash**: Hash client IP → always routes same client to same backend (session affinity)
- **Consistent hashing**: Minimize redistribution when backends are added/removed (used by Kafka partition assignment, distributed caches)

### L7 (HTTP) Load Balancing

Operates at the application layer. Inspects HTTP headers, cookies, paths. Can route based on URL (`/payments/` → payment service, `/wallets/` → wallet service). Can terminate TLS. Used by: Nginx, HAProxy, Envoy, AWS ALB, Kong.

### Health Checks

The load balancer periodically checks: "Is the backend alive?"
- **TCP check**: Can we open a TCP connection? (crude — server process alive, but application dead)
- **HTTP check**: GET /health returns 200? (better — application is responsive)
- **Custom check**: Does the /health endpoint also verify DB connectivity? (best — returns 503 if DB down)

**Payment relevance**: Kubernetes liveness/readiness probes ARE health checks. `livenessProbe`: is the process hung? If fails → restart pod. `readinessProbe`: is the application ready for traffic? If fails → remove from service endpoints (load balancer stops routing).

---

## 2.9 gRPC

gRPC is Google's RPC framework using Protocol Buffers (protobuf) and HTTP/2.

**Why gRPC for inter-service communication**:
- **Binary and compact**: Protobuf is much smaller than JSON → less bandwidth, faster serialization
- **Strongly typed**: `.proto` files define the contract → generated code in Java/Python/Go/Node.js
- **Streaming**: Unary (1 request → 1 response), Server streaming, Client streaming, Bidirectional streaming
- **Deadlines**: Every gRPC call has a deadline → no hanging requests
- **Cancellation**: Cancel in-progress calls (propagated across service chain via context)

```protobuf
service PaymentService {
  rpc CreatePayment(CreatePaymentRequest) returns (PaymentResponse);
  rpc ListPayments(ListPaymentsRequest) returns (stream Payment);  // server streaming
}
```

**Payment relevance**: gRPC for high-throughput internal service calls. REST for external APIs (merchants, mobile SDKs). gRPC-gateway can translate REST ↔ gRPC if needed.

---

## 2.10 Key Concepts Summary

| Concept | What It Is | Payment Relevance |
|---------|-----------|-------------------|
| TCP handshake | SYN → SYN-ACK → ACK | Connection establishment latency (why connection pools matter) |
| Sliding window | Receiver flow control | TCP buffer tuning for Kafka throughput |
| Slow start | Congestion control ramp-up | Connection pooling avoids slow start penalty |
| TIME_WAIT | Post-close state (2×MSL) | Port exhaustion under high connection churn |
| DNS TTL | Cache duration | Service discovery staleness vs DNS query load |
| HTTP/2 multiplexing | Multiple streams per connection | gRPC uses HTTP/2 for inter-service calls |
| TLS 1.3 1-RTT | Faster handshake | Reduced latency for API calls |
| mTLS | Mutual certificate verification | Istio service mesh secures all inter-pod traffic |
| epoll | Efficient I/O multiplexing | Foundation of Node.js, Nginx, Java NIO |

---

## 2.11 Self-Assessment

- [ ] Can walk through the TCP three-way handshake and explain why three messages are needed
- [ ] Understand the difference between flow control and congestion control
- [ ] Can explain why TIME_WAIT exists and how to handle port exhaustion
- [ ] Can trace a DNS resolution from root servers to authoritative server
- [ ] Can list the key differences between HTTP/1.1, HTTP/2, and HTTP/3
- [ ] Can read a TLS 1.3 handshake from Wireshark
- [ ] Understand the difference between L4 and L7 load balancing
- [ ] Know when to use gRPC vs REST for inter-service communication
- [ ] Can explain why DNS is a critical dependency and how to monitor it
