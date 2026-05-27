public class ContextSwitch {
    static final int ITERATIONS = 1_000_000;
    static volatile boolean turn = true;
    static volatile long count = 0;

    public static void main(String[] args) throws Exception {
        runTest(); // warm up
        long t0 = System.nanoTime();
        long total = runTest();
        long t1 = System.nanoTime();
        double nsPerSwitch = (t1 - t0) / (double) (total * 2);
        System.out.printf("Context switches: %d (1M ping-pongs each)%n", total * 2);
        System.out.printf("Total time: %.2f ms%n", (t1 - t0) / 1e6);
        System.out.printf("Per context switch: %.0f ns (~%.0f CPU cycles at 3GHz)%n", nsPerSwitch, nsPerSwitch * 3);
    }

    static long runTest() throws InterruptedException {
        count = 0; turn = true;
        Thread t1 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) { while (turn) Thread.onSpinWait(); turn = true; count++; } });
        Thread t2 = new Thread(() -> { for (int i = 0; i < ITERATIONS; i++) { while (!turn) Thread.onSpinWait(); turn = false; count++; } });
        t1.start(); t2.start(); t1.join(); t2.join();
        return count;
    }
}
