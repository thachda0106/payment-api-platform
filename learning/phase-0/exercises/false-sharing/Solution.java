// Demonstrates false sharing: two threads incrementing counters on same vs different cache lines
// Run: java --add-opens java.base/jdk.internal.vm.annotation=ALL-UNNAMED Solution.java (Java 17+)
// Or simpler: just run with -XX:-RestrictContended on Java 8/11

public class Solution {
    // These two counters are on the same cache line (64 bytes) → false sharing
    static class SharedLine {
        volatile long counterA = 0;
        volatile long counterB = 0;
    }

    // These counters are padded to separate cache lines
    static class PaddedLine {
        volatile long counterA = 0;
        long p1, p2, p3, p4, p5, p6, p7;  // 7 × 8 = 56 bytes padding + 8 bytes counter = 64 bytes
        volatile long counterB = 0;
        long q1, q2, q3, q4, q5, q6, q7;
    }

    static final int ITERATIONS = 100_000_000;

    public static void main(String[] args) throws Exception {
        System.out.println("Measuring false sharing effect...");
        System.out.println("Iterations per thread: " + ITERATIONS);

        // Test shared cache line
        SharedLine shared = new SharedLine();
        long tShared = test(shared);
        System.out.printf("Shared cache line:  %6d ms%n", tShared);

        // Test padded cache line
        PaddedLine padded = new PaddedLine();
        long tPadded = test(padded);
        System.out.printf("Padded cache lines:  %6d ms%n", tPadded);

        if (tShared > 0)
            System.out.printf("Speedup with padding: %.1fx%n", (double) tShared / Math.max(1, tPadded));
    }

    static long test(Object obj) throws InterruptedException {
        Thread t1, t2;
        if (obj instanceof SharedLine) {
            SharedLine s = (SharedLine) obj;
            t1 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) s.counterA++; });
            t2 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) s.counterB++; });
        } else {
            PaddedLine p = (PaddedLine) obj;
            t1 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) p.counterA++; });
            t2 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) p.counterB++; });
        }

        long start = System.currentTimeMillis();
        t1.start(); t2.start();
        t1.join(); t2.join();
        return System.currentTimeMillis() - start;
    }
}
