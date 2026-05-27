# Cache Effect Demonstration (Matrix Multiplication)

Demonstrate the dramatic performance difference between cache-friendly (row-major) and cache-unfriendly (column-major) memory access patterns.

## Background

A modern CPU loads data from RAM in 64-byte cache lines. When you access `array[i]`, the CPU loads `array[i]` AND the next 15 integers (assuming 4-byte ints) into L1 cache. If your NEXT access is `array[i+1]`, it's a cache hit (fast: ~4 cycles). If your next access is far away, it's a cache miss (slow: ~100 cycles).

## Requirements

Implement two versions of square matrix multiplication (C = A × B):

1. **Row-major access (cache-friendly)**:
```java
for (int i = 0; i < n; i++)
    for (int j = 0; j < n; j++)
        for (int k = 0; k < n; k++)
            C[i][j] += A[i][k] * B[k][j];
```

2. **Column-major access (cache-unfriendly)**:
```java
for (int i = 0; i < n; i++)
    for (int j = 0; j < n; j++)
        for (int k = 0; k < n; k++)
            C[j][i] += A[k][i] * B[j][k];  // Note: swapped indices
```

## Measurement

- Run with matrix sizes: 128, 256, 512, 1024, 2048
- Measure elapsed time for each version at each size
- Compute the speedup: `time(column_major) / time(row_major)`
- Create a table of results
- Explain WHY the speedup increases with matrix size

## Expected Results

For n = 1024 on a modern CPU, row-major should be **10-20x faster** than column-major.

## Starter Code (Java)

```java
public class CacheDemo {
    public static void main(String[] args) {
        int[] sizes = {128, 256, 512, 1024, 2048};
        
        for (int n : sizes) {
            double[][] A = randomMatrix(n);
            double[][] B = randomMatrix(n);
            double[][] C1 = new double[n][n];
            double[][] C2 = new double[n][n];
            
            // Warm-up (JIT compilation)
            rowMajor(A, B, new double[n][n]);
            colMajor(A, B, new double[n][n]);
            
            // Measure row-major
            long start = System.nanoTime();
            rowMajor(A, B, C1);
            long rowTime = System.nanoTime() - start;
            
            // Measure column-major
            start = System.nanoTime();
            colMajor(A, B, C2);
            long colTime = System.nanoTime() - start;
            
            // Verify results match
            boolean correct = matricesEqual(C1, C2, n);
            
            System.out.printf("n=%4d | row: %8.2f ms | col: %8.2f ms | speedup: %.1fx | %s%n",
                n, rowTime/1e6, colTime/1e6, (double)colTime/rowTime,
                correct ? "OK" : "MISMATCH!");
        }
    }
    
    static void rowMajor(double[][] A, double[][] B, double[][] C) {
        int n = A.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++)
                    C[i][j] += A[i][k] * B[k][j];
    }
    
    static void colMajor(double[][] A, double[][] B, double[][] C) {
        int n = A.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                for (int k = 0; k < n; k++)
                    C[j][i] += A[k][i] * B[j][k];
    }
    
    static double[][] randomMatrix(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                m[i][j] = Math.random();
        return m;
    }
    
    static boolean matricesEqual(double[][] A, double[][] B, int n) {
        double epsilon = 0.001;  // Floating point tolerance
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (Math.abs(A[i][j] - B[i][j]) > epsilon)
                    return false;
        return true;
    }
}
```

## Analysis Questions

1. Why is row-major faster? Draw a diagram showing cache line loads for each access pattern.
2. For n = 128, the speedup might be only 2-3x. Why is it smaller for smaller matrices? (Hint: L1/L2 cache sizes)
3. What would happen if we transposed matrix B before multiplication? Would that help column-major access?
4. How does this relate to database index access patterns (sequential scan vs. random I/O)?
