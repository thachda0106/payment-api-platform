// Cache Effect: Row-major vs Column-major matrix multiplication
public class Solution {
    public static void main(String[] args) {
        int[] sizes = {128, 256, 512, 1024, 2048};
        System.out.printf("%-6s %12s %12s %10s %6s%n", "n", "row-major(ms)", "col-major(ms)", "speedup", "check");
        System.out.println("----------------------------------------------------------");
        for (int n : sizes) {
            double[][] A = random(n), B = random(n), C1 = new double[n][n], C2 = new double[n][n];
            // warmup
            rowMajor(A, B, new double[n][n]);
            colMajor(A, B, new double[n][n]);
            // measure
            long t0 = System.nanoTime();
            rowMajor(A, B, C1);
            long tRow = System.nanoTime() - t0;

            t0 = System.nanoTime();
            colMajor(A, B, C2);
            long tCol = System.nanoTime() - t0;

            boolean ok = true;
            for (int i = 0; i < n && ok; i++)
                for (int j = 0; j < n && ok; j++)
                    if (Math.abs(C1[i][j] - C2[i][j]) > 0.001) ok = false;

            System.out.printf("%-6d %10.2f %10.2f %8.1fx %6s%n",
                n, tRow / 1e6, tCol / 1e6, (double) tCol / Math.max(1, tRow), ok ? "OK" : "FAIL");
        }
    }

    static void rowMajor(double[][] A, double[][] B, double[][] C) {
        int n = A.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (int k = 0; k < n; k++) sum += A[i][k] * B[k][j];
                C[i][j] = sum;
            }
    }

    static void colMajor(double[][] A, double[][] B, double[][] C) {
        int n = A.length;
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                double sum = 0;
                for (int k = 0; k < n; k++) sum += A[i][k] * B[k][j];
                C[i][j] = sum;
            }
    }

    static double[][] random(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) m[i][j] = Math.random();
        return m;
    }
}
