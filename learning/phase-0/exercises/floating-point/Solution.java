// Demonstrates floating-point inaccuracy. Run: javac Solution.java && java Solution
public class Solution {
    public static void main(String[] args) {
        // 0.1 + 0.2 != 0.3
        double a = 0.1 + 0.2;
        System.out.printf("0.1 + 0.2 = %.20f%n", a);
        System.out.printf("0.1 + 0.2 == 0.3? %b%n", a == 0.3);
        System.out.printf("Difference: %.20f%n%n", Math.abs(a - 0.3));

        // Accumulation error
        double sum = 0;
        for (int i = 0; i < 10; i++) sum += 0.1;
        System.out.printf("10 × 0.1 = %.20f (expected 1.0)%n", sum);
        System.out.printf("Error after 10 iterations: %.20f%n%n", Math.abs(sum - 1.0));

        // Large accumulation
        sum = 0;
        for (int i = 0; i < 1000000; i++) sum += 0.1;
        System.out.printf("1M × 0.1 = %.6f (expected 100000.0)%n", sum);
        System.out.printf("Error: %.6f%n%n", Math.abs(sum - 100000.0));

        // Correct approach: BigDecimal / integer cents
        long cents = 0;
        for (int i = 0; i < 1000000; i++) cents += 10;  // 10 cents = 0.1
        System.out.printf("Using cents: %d cents = %.2f VND (exact)%n", cents, cents / 100.0);

        // BigDecimal approach
        java.math.BigDecimal bd = java.math.BigDecimal.ZERO;
        java.math.BigDecimal inc = new java.math.BigDecimal("0.1");
        for (int i = 0; i < 10; i++) bd = bd.add(inc);
        System.out.printf("Using BigDecimal: %s (exact)%n", bd);
    }
}
