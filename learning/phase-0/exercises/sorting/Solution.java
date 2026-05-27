// Merge Sort + Quick Sort implementations with benchmark
import java.util.*;

public class Solution {
    // --- Merge Sort ---
    public static void mergeSort(int[] arr) { mergeSort(arr, 0, arr.length - 1, new int[arr.length]); }
    private static void mergeSort(int[] arr, int l, int r, int[] aux) {
        if (l >= r) return;
        int m = l + (r - l) / 2;
        mergeSort(arr, l, m, aux); mergeSort(arr, m + 1, r, aux);
        merge(arr, l, m, r, aux);
    }
    private static void merge(int[] arr, int l, int m, int r, int[] aux) {
        System.arraycopy(arr, l, aux, l, r - l + 1);
        int i = l, j = m + 1, k = l;
        while (i <= m && j <= r) arr[k++] = aux[i] <= aux[j] ? aux[i++] : aux[j++];
        while (i <= m) arr[k++] = aux[i++];
        while (j <= r) arr[k++] = aux[j++];
    }

    // --- Quick Sort (median-of-three pivot) ---
    public static void quickSort(int[] arr) { quickSort(arr, 0, arr.length - 1); }
    private static void quickSort(int[] arr, int low, int high) {
        if (low >= high) return;
        int pivot = partition(arr, low, high);
        quickSort(arr, low, pivot - 1);
        quickSort(arr, pivot + 1, high);
    }
    private static int partition(int[] arr, int low, int high) {
        int mid = low + (high - low) / 2;
        // median-of-three: sort low, mid, high
        if (arr[mid] < arr[low]) swap(arr, low, mid);
        if (arr[high] < arr[low]) swap(arr, low, high);
        if (arr[high] < arr[mid]) swap(arr, mid, high);
        swap(arr, mid, high - 1); // pivot at high-1
        int pivot = arr[high - 1];
        int i = low, j = high - 1;
        while (true) {
            while (arr[++i] < pivot);
            while (arr[--j] > pivot);
            if (i >= j) break;
            swap(arr, i, j);
        }
        swap(arr, i, high - 1);
        return i;
    }
    private static void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }

    // --- Helpers ---
    static int[] random(int n) { int[] a = new int[n]; for (int i=0;i<n;i++) a[i]=(int)(Math.random()*n); return a; }
    static int[] sorted(int n) { int[] a=new int[n]; for (int i=0;i<n;i++) a[i]=i; return a; }
    static int[] reversed(int n) { int[] a=new int[n]; for (int i=0;i<n;i++) a[i]=n-i; return a; }
    static int[] fewUnique(int n) { int[] a=new int[n]; for (int i=0;i<n;i++) a[i]=(int)(Math.random()*10); return a; }
    static boolean isSorted(int[] a) { for (int i=1;i<a.length;i++) if (a[i]<a[i-1]) return false; return true; }

    // --- Benchmark ---
    public static void main(String[] args) {
        System.out.printf("%-8s %-12s %-12s %-12s%n", "n", "MergeSort", "QuickSort", "Timsort");
        System.out.println("-------------------------------------------------");

        int[] sizes = {10000, 100000, 1000000};
        for (int n : sizes) {
            int[] base = random(n);
            int[] a1 = base.clone(), a2 = base.clone(), a3 = base.clone();

            long t0 = System.nanoTime(); mergeSort(a1); long tMerge = System.nanoTime()-t0;
            t0 = System.nanoTime(); quickSort(a2); long tQuick = System.nanoTime()-t0;
            t0 = System.nanoTime(); Arrays.sort(a3); long tTim = System.nanoTime()-t0;

            System.out.printf("%,-8d %8.1f ms %8.1f ms %8.1f ms  (all sorted: %s)%n",
                n, tMerge/1e6, tQuick/1e6, tTim/1e6,
                isSorted(a1)&&isSorted(a2)&&isSorted(a3)?"OK":"FAIL");
        }

        // Worst case for naive quick sort: sorted input with first-element pivot
        // Our median-of-three handles this
        System.out.println("\nSorted input (worst-case test for naive quick sort):");
        int n = 100000;
        int[] sorted1 = sorted(n), sorted2 = sorted(n);
        long t0 = System.nanoTime(); mergeSort(sorted1);
        System.out.printf("  MergeSort: %6.1f ms%n", (System.nanoTime()-t0)/1e6);
        t0 = System.nanoTime(); quickSort(sorted2);
        System.out.printf("  QuickSort: %6.1f ms (median-of-three prevents O(n²))%n", (System.nanoTime()-t0)/1e6);
    }
}
