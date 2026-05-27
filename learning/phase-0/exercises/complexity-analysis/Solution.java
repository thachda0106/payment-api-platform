// Complexity Analysis: Determine time and space complexity for each snippet
public class Solution {
    public static void main(String[] args) {
        System.out.println("=== Complexity Analysis ===\n");

        // A: O(log n) — i doubles each iteration
        System.out.println("A: for (int i = 0; i < n; i *= 2)");
        System.out.println("   Time: O(log₂ n) — i doubles each iteration, ~log₂(n) steps");
        System.out.println("   Space: O(1)\n");

        // B: O(n²/2) = O(n²) — triangular loop
        System.out.println("B: for i=0..n-1: for j=i..n-1: process(i,j)");
        System.out.println("   Iterations: n + (n-1) + (n-2) + ... + 1 = n(n+1)/2");
        System.out.println("   Time: O(n²)");
        System.out.println("   Space: O(1)\n");

        // C: O(n) — each call spawns 2 calls at n/2 → recurrence T(n)=2T(n/2)+O(1)=O(n)
        System.out.println("C: void recurse(int n) { if (n<=0) return; recurse(n/2); recurse(n/2); }");
        System.out.println("   Recurrence: T(n) = 2T(n/2) + O(1)");
        System.out.println("   Total nodes in recursion tree = 2^(log₂ n + 1) - 1 ≈ 2n");
        System.out.println("   Time: O(n)");
        System.out.println("   Space: O(log n) — recursion depth\n");

        // D: O(2ⁿ) — Fibonacci naive
        System.out.println("D: int fib(int n) { if (n<=1) return n; return fib(n-1)+fib(n-2); }");
        System.out.println("   Each call spawns 2 subcalls → ~2ⁿ calls total");
        System.out.println("   Time: O(2ⁿ)");
        System.out.println("   Space: O(n) — max recursion depth\n");

        // E: O(n log n) — outer loop O(n), inner loop O(log n)
        System.out.println("E: for i=0..n-1: for j=1; j<n; j*=2: process(i,j)");
        System.out.println("   Outer: O(n), Inner: O(log n)");
        System.out.println("   Time: O(n log n)");
        System.out.println("   Space: O(1)\n");

        // F: O(n) amortized — dynamic array insert
        System.out.println("F: for i=0..n-1: arrayList.add(i)");
        System.out.println("   Most inserts: O(1). Occasional resize: O(n)");
        System.out.println("   Amortized time: O(1) per insertion, O(n) total");
        System.out.println("   Space: O(n)\n");

        // Verify with empirical test
        System.out.println("=== Empirical Verification (n scaling) ===");
        for (int n : new int[]{10_000, 20_000, 40_000, 80_000}) {
            long t0 = System.nanoTime();
            int sum = 0;
            for (int i=0;i<n;i*=2) sum++; // O(log n)
            long logN = System.nanoTime()-t0;

            t0 = System.nanoTime();
            int[] arr = new int[n];
            for (int i=0;i<n;i++) arr[i]=i; // O(n)
            long linear = System.nanoTime()-t0;

            System.out.printf("n=%,d: O(log n)=%,dns | O(n)=%,dns | ratio=%.1fx%n",
                n, logN, linear, (double)linear/Math.max(1, logN));
        }
    }
}
