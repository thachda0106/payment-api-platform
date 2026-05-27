# Phase 1 — Implementation

> This file contains the complete Java implementations for all Phase 1 exercises and the mini-project load balancer.

---

## Exercise Solutions (in order)

### Ex 1.1 — TCP Echo Server

```java
// TCP Echo Server — raw ServerSocket, handles multiple clients with threads
import java.io.*;
import java.net.*;

public class TCPServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("TCP Echo Server listening on port " + port);
            while (true) {
                Socket client = ss.accept();
                new Thread(() -> handle(client)).start();
            }
        }
    }
    static void handle(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("QUIT")) break;
                out.println("ECHO: " + line);
            }
        } catch (IOException e) { /* client disconnected */ }
    }
}
```

### Ex 1.2 — TCP Client (for testing)

```java
import java.io.*;
import java.net.*;

public class TCPClient {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        try (Socket s = new Socket(host, port);
             PrintWriter out = new PrintWriter(s.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            for (String msg : new String[]{"Hello", "Payment=100000", "World", "QUIT"}) {
                out.println(msg);
                System.out.println("SENT: " + msg + " | RECV: " + in.readLine());
            }
        }
    }
}
```

### Ex 1.3 — Context Switch Cost Measurement

```java
// Measures context switch overhead between two threads via token ping-pong
public class ContextSwitch {
    static final int ITERATIONS = 1_000_000;
    static volatile boolean turn = true; // true = thread 1's turn
    static volatile long count = 0;

    public static void main(String[] args) throws Exception {
        // Warm up
        runTest();
        // Measure
        long t0 = System.nanoTime();
        long total = runTest();
        long t1 = System.nanoTime();
        double nsPerSwitch = (t1 - t0) / (double) (total * 2);
        System.out.printf("Context switches: %d (1M ping-pongs each)%n", total * 2);
        System.out.printf("Total time: %.2f ms%n", (t1 - t0) / 1e6);
        System.out.printf("Per context switch: %.0f ns%n", nsPerSwitch);
    }

    static long runTest() throws InterruptedException {
        count = 0;
        Thread t1 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) { while (turn) Thread.onSpinWait(); turn = true; count++; } });
        Thread t2 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) { while (!turn) Thread.onSpinWait(); turn = false; count++; } });
        t1.start(); t2.start(); t1.join(); t2.join();
        return count;
    }
}
```

### Ex 1.4 — Virtual Memory Explorer

```java
// Demonstrates RSS vs VSS, page fault behavior, and /proc/meminfo parsing (Linux only)
import java.io.*;
import java.nio.file.*;

public class VMemExplorer {
    public static void main(String[] args) throws Exception {
        System.out.printf("PID: %d%n", ProcessHandle.current().pid());

        // Read /proc/self/status for memory info (Linux only)
        printMemory("Before allocation");

        // Allocate 1GB virtual memory, touch only boundaries
        int pageSize = 4096;
        int pages = 1024 * 1024 * 1024 / pageSize; // 1GB
        byte[][] chunks = new byte[pages / 1024][];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = new byte[pageSize * 1024]; // 4MB each
            chunks[i][0] = 1; // touch first byte of each chunk only
        }
        printMemory("After sparse allocation (touched ~" + (chunks.length * pageSize / 1024) + " KB)");

        // Touch every page → page fault storm
        for (int i = 0; i < chunks.length; i++)
            for (int j = 0; j < chunks[i].length; j += pageSize)
                chunks[i][j] = 1;
        printMemory("After full page touch (all pages faulted in)");

        System.gc();
        Thread.sleep(100);
        printMemory("After GC");
    }

    static void printMemory(String label) throws Exception {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory(), free = rt.freeMemory(), used = total - free, max = rt.maxMemory();
        System.out.printf("%n=== %s ===%n", label);
        System.out.printf("  JVM Heap: total=%d MB, used=%d MB, max=%d MB%n", total/1024/1024, used/1024/1024, max/1024/1024);

        // Read /proc/self/status (Linux)
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmSize:") || line.startsWith("VmRSS:") || line.startsWith("VmData:") || line.startsWith("VmStk:"))
                    System.out.println("  " + line);
            }
        } catch (IOException e) {
            System.out.println("  (Cannot read /proc — not Linux or permission denied)");
        }
    }
}
```

### Ex 1.5 — I/O Model Comparison

```java
// Compares blocking I/O, non-blocking I/O, and epoll (via Selector)
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class IOModelComparison {
    static final int PORT = 9090;
    static final int CONNECTIONS = 1000;

    public static void main(String[] args) throws Exception {
        System.out.println("=== I/O Model Comparison ===\n");

        // 1. Blocking I/O with threads
        long t0 = System.currentTimeMillis();
        testBlockingThreaded();
        System.out.printf("Blocking (thread-per-conn): %d ms%n%n", System.currentTimeMillis() - t0);

        // 2. Non-blocking with Selector (epoll)
        t0 = System.currentTimeMillis();
        testNonBlockingSelector();
        System.out.printf("Non-blocking (epoll): %d ms%n%n", System.currentTimeMillis() - t0);
    }

    static void testBlockingThreaded() throws Exception {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            ss.setSoTimeout(5000);
            Thread serverThread = new Thread(() -> {
                try {
                    for (int i = 0; i < CONNECTIONS; i++) {
                        Socket s = ss.accept();
                        new Thread(() -> { try { s.getInputStream().read(); s.close(); } catch (Exception e) {} }).start();
                    }
                } catch (Exception e) {}
            });
            serverThread.start();

            // Connect from client side
            for (int i = 0; i < CONNECTIONS; i++) {
                try (Socket s = new Socket("localhost", PORT)) { s.getOutputStream().write(42); }
            }
            serverThread.join();
        }
    }

    static void testNonBlockingSelector() throws Exception {
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(PORT + 1));
            ssc.configureBlocking(false);
            Selector sel = Selector.open();
            ssc.register(sel, SelectionKey.OP_ACCEPT);

            // Accept all connections
            int accepted = 0;
            while (accepted < CONNECTIONS) {
                sel.select(100);
                for (Iterator<SelectionKey> it = sel.selectedKeys().iterator(); it.hasNext(); ) {
                    SelectionKey key = it.next(); it.remove();
                    if (key.isAcceptable()) {
                        SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
                        sc.configureBlocking(false);
                        sc.register(sel, SelectionKey.OP_READ);
                        accepted++;
                    }
                }
            }

            // Read one byte from each
            int read = 0;
            while (read < CONNECTIONS) {
                sel.select(100);
                for (Iterator<SelectionKey> it = sel.selectedKeys().iterator(); it.hasNext(); ) {
                    SelectionKey key = it.next(); it.remove();
                    if (key.isReadable()) {
                        ByteBuffer buf = ByteBuffer.allocate(1);
                        ((SocketChannel) key.channel()).read(buf);
                        key.channel().close();
                        read++;
                    }
                }
            }
        }
    }
}
```

### Ex 1.6 — DNS Resolver

```java
// Manual DNS resolution using InetAddress, explores caching and record types
import java.net.*;
import java.util.*;

public class DNSResolver {
    public static void main(String[] args) throws Exception {
        String[] hosts = {"google.com", "stripe.com", "github.com", "amazon.com", "nonexistent.domain.invalid"};

        System.out.printf("%-30s %-15s %-15s %-10s%n", "Host", "IP", "Canonical", "Reachable");
        System.out.println("-".repeat(80));

        for (String host : hosts) {
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                for (InetAddress addr : addrs) {
                    boolean reachable = addr.isReachable(2000);
                    System.out.printf("%-30s %-15s %-15s %-10s%n",
                        host, addr.getHostAddress(), addr.getCanonicalHostName(), reachable ? "YES" : "NO");
                }
            } catch (UnknownHostException e) {
                System.out.printf("%-30s %s%n", host, "NOT FOUND (" + e.getMessage() + ")");
            }
        }

        // Java DNS cache settings
        System.out.println("\nJava DNS Cache:");
        System.out.println("  networkaddress.cache.ttl = " + java.security.Security.getProperty("networkaddress.cache.ttl"));
        System.out.println("  networkaddress.cache.negative.ttl = " + java.security.Security.getProperty("networkaddress.cache.negative.ttl"));
    }
}
```

---

## Mini Project — HTTP Load Balancer

```java
// HTTP Load Balancer — supports round-robin and least-connections algorithms
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LoadBalancer {
    static record Backend(String host, int port, AtomicInteger activeConnections) {
        String address() { return host + ":" + port; }
    }

    private final List<Backend> backends;
    private final AtomicInteger roundRobinIdx = new AtomicInteger(0);
    private final ScheduledExecutorService healthChecker = Executors.newSingleThreadScheduledExecutor();
    private final List<Backend> healthy = new CopyOnWriteArrayList<>();
    private volatile String algorithm = "round-robin";

    public LoadBalancer(String... backends) {
        this.backends = new CopyOnWriteArrayList<>();
        for (String b : backends) {
            String[] parts = b.split(":");
            this.backends.add(new Backend(parts[0], Integer.parseInt(parts[1]), new AtomicInteger(0)));
        }
        healthy.addAll(this.backends);
    }

    public void start(int port) throws IOException {
        healthChecker.scheduleAtFixedRate(this::healthCheck, 0, 5, TimeUnit.SECONDS);

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Load Balancer listening on :" + port);
            System.out.println("Backends: " + backends.stream().map(Backend::address).reduce((a, b) -> a + ", " + b).orElse("none"));
            System.out.println("Algorithm: " + algorithm);
            System.out.println();

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            while (true) {
                Socket client = ss.accept();
                pool.submit(() -> handle(client));
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            Backend backend = selectBackend();
            if (backend == null) {
                sendError(client, 503, "No healthy backends available");
                return;
            }
            backend.activeConnections.incrementAndGet();
            try {
                forward(client, backend);
            } finally {
                backend.activeConnections.decrementAndGet();
            }
        } catch (Exception e) {
            System.err.println("Error handling client: " + e.getMessage());
        }
    }

    private Backend selectBackend() {
        List<Backend> current = healthy;
        if (current.isEmpty()) return null;
        return switch (algorithm) {
            case "least-connections" -> current.stream().min(Comparator.comparingInt(b -> b.activeConnections.get())).orElse(current.get(0));
            default -> current.get(roundRobinIdx.getAndUpdate(i -> (i + 1) % current.size()));
        };
    }

    private void forward(Socket client, Backend backend) throws IOException {
        try (Socket upstream = new Socket(backend.host, backend.port)) {
            upstream.setSoTimeout(5000);
            // Read HTTP request from client and forward
            InputStream clientIn = client.getInputStream();
            OutputStream upstreamOut = upstream.getOutputStream();

            byte[] buf = new byte[8192];
            int n = clientIn.read(buf);
            if (n > 0) {
                upstreamOut.write(buf, 0, n);
                upstreamOut.flush();
            }

            // Read response from backend and forward to client
            InputStream upstreamIn = upstream.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            while ((n = upstreamIn.read(buf)) > 0) { clientOut.write(buf, 0, n); clientOut.flush(); }
        }
    }

    private void sendError(Socket client, int status, String message) {
        try {
            String body = "{\"error\":\"" + message + "\"}";
            String resp = "HTTP/1.1 " + status + " Service Unavailable\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
            client.getOutputStream().write(resp.getBytes());
        } catch (IOException ignored) {}
    }

    private void healthCheck() {
        List<Backend> newHealthy = new ArrayList<>();
        for (Backend b : backends) {
            try (Socket s = new Socket(b.host, b.port)) {
                s.setSoTimeout(2000);
                OutputStream out = s.getOutputStream();
                out.write("GET /health HTTP/1.1\r\nHost: " + b.host + "\r\n\r\n".getBytes());
                out.flush();
                InputStream in = s.getInputStream();
                newHealthy.add(b);
            } catch (IOException e) {
                System.err.println("Backend " + b.address() + " is UNHEALTHY");
            }
        }
        healthy.clear();
        healthy.addAll(newHealthy);
        System.out.println("Health check: " + healthy.size() + "/" + backends.size() + " healthy");
    }

    public void setAlgorithm(String algo) { this.algorithm = algo; }

    // ─── Demo ──────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        // Start a simple backend server on a random port
        int backendPort = 9091;
        Thread backend = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(backendPort);
                System.out.println("Backend running on :" + backendPort);
                while (true) {
                    Socket s = ss.accept();
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 29\r\n\r\nHello from backend :" + backendPort;
                    s.getOutputStream().write(resp.getBytes());
                    s.close();
                }
            } catch (IOException e) {}
        });
        backend.setDaemon(true);
        backend.start();
        Thread.sleep(200); // let backend start

        // Start load balancer
        LoadBalancer lb = new LoadBalancer("localhost:" + backendPort);
        new Thread(() -> {
            try { lb.start(8080); } catch (IOException e) {}
        }).start();
        Thread.sleep(200);

        // Test: send requests via HTTP client
        System.out.println("Sending test requests...\n");
        for (int i = 0; i < 5; i++) {
            try (Socket s = new Socket("localhost", 8080)) {
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes());
                String resp = new String(s.getInputStream().readAllBytes());
                System.out.printf("Request %d: %s%n", i + 1, resp.lines().filter(l -> l.startsWith("Hello")).findFirst().orElse("NO RESPONSE"));
            }
        }
        System.out.println("\nLoad balancer is running. Connect with: curl http://localhost:8080");
    }
}
```

---

### Compilation & Run Instructions

```bash
# Compile all exercises
javac TCPServer.java TCPClient.java ContextSwitch.java VMemExplorer.java IOModelComparison.java DNSResolver.java

# Or compile individually:
javac TCPServer.java && java TCPServer 8080

# Load Balancer (mini project)
javac LoadBalancer.java && java LoadBalancer

# Test the load balancer
curl http://localhost:8080
```
