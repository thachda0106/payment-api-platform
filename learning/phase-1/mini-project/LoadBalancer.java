import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LoadBalancer {
    record Backend(String host, int port, AtomicInteger activeConnections) {
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
        this.healthy.addAll(this.backends);
    }

    public void start(int port) throws IOException {
        healthChecker.scheduleAtFixedRate(this::healthCheck, 0, 5, TimeUnit.SECONDS);
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("╔══════════════════════════════════════╗");
            System.out.println("║   HTTP Load Balancer v1.0            ║");
            System.out.println("╚══════════════════════════════════════╝");
            System.out.println("Listening on :" + port);
            System.out.println("Algorithm: " + algorithm);
            System.out.println("Backends: " + backends.stream().map(Backend::address).reduce((a, b) -> a + ", " + b).orElse("none"));
            System.out.println();

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            while (true) {
                Socket client = ss.accept();
                pool.submit(() -> handle(client));
            }
        }
    }

    private void handle(Socket client) {
        long t0 = System.nanoTime();
        try (client) {
            Backend backend = selectBackend();
            if (backend == null) { sendError(client, 503, "No healthy backends"); return; }
            backend.activeConnections.incrementAndGet();
            try { forward(client, backend); }
            finally { backend.activeConnections.decrementAndGet(); }
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            if (elapsed > 100) System.out.printf("[SLOW] %d ms → %s%n", elapsed, backend.address());
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
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
            upstream.setSoTimeout(5000); client.setSoTimeout(5000);

            InputStream clientIn = client.getInputStream();
            OutputStream upstreamOut = upstream.getOutputStream();
            byte[] buf = new byte[8192]; int n = clientIn.read(buf);
            if (n > 0) { upstreamOut.write(buf, 0, n); upstreamOut.flush(); }

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
                out.write(("GET /health HTTP/1.1\r\nHost: " + b.host + "\r\n\r\n").getBytes());
                out.flush();
                s.getInputStream().read(); // try to read a byte
                newHealthy.add(b);
            } catch (IOException e) {
                System.err.println("[HEALTH] " + b.address() + " UNHEALTHY");
            }
        }
        healthy.clear(); healthy.addAll(newHealthy);
        System.out.println("[HEALTH] " + healthy.size() + "/" + backends.size() + " healthy");
    }

    public void setAlgorithm(String algo) { this.algorithm = algo; }

    // ─── Demo Mode ──────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        // Start two backend servers
        for (int p : new int[]{9091, 9092}) {
            int port = p;
            Thread t = new Thread(() -> {
                try (ServerSocket ss = new ServerSocket(port)) {
                    System.out.println("Backend :" + port + " started");
                    while (true) {
                        Socket s = ss.accept();
                        new Thread(() -> {
                            try (s) {
                                String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 21\r\n\r\nHello from backend :" + port;
                                s.getOutputStream().write(resp.getBytes());
                            } catch (IOException e) {}
                        }).start();
                    }
                } catch (IOException e) {}
            });
            t.setDaemon(true); t.start();
        }
        Thread.sleep(300);

        // Start load balancer
        LoadBalancer lb = new LoadBalancer("localhost:9091", "localhost:9092");

        // Run in separate thread
        Thread lbThread = new Thread(() -> { try { lb.start(8080); } catch (IOException e) {} });
        lbThread.setDaemon(true); lbThread.start();
        Thread.sleep(200);

        // Test: send 6 requests via raw HTTP
        System.out.println("\n=== Sending test requests ===\n");
        for (int i = 1; i <= 6; i++) {
            try (Socket s = new Socket("localhost", 8080)) {
                OutputStream out = s.getOutputStream();
                out.write(("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes());
                out.flush();
                String resp = new String(s.getInputStream().readAllBytes());
                String body = resp.lines().filter(l -> l.startsWith("Hello")).findFirst().orElse("NO RESPONSE");
                System.out.printf("Request %d → %s%n", i, body);
            }
        }

        // Switch to least-connections and test
        System.out.println("\nSwitching to least-connections...\n");
        lb.setAlgorithm("least-connections");
        for (int i = 7; i <= 10; i++) {
            try (Socket s = new Socket("localhost", 8080)) {
                s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes());
                s.getOutputStream().flush();
                String resp = new String(s.getInputStream().readAllBytes());
                String body = resp.lines().filter(l -> l.startsWith("Hello")).findFirst().orElse("NO RESPONSE");
                System.out.printf("Request %d → %s%n", i, body);
            }
        }

        System.out.println("\nLoad balancer running at http://localhost:8080");
        System.out.println("Test with: for i in {1..20}; do curl -s http://localhost:8080; echo; done");
        System.out.println("Kill a backend: kill <pid-of-backend> and watch health check recover");
    }
}
