// Binary Search Variations
import java.util.*;
public class Solution {
    // Standard binary search
    public static int binarySearch(int[] arr, int target) {
        int l = 0, r = arr.length - 1;
        while (l <= r) { int m = l + (r - l) / 2; if (arr[m] == target) return m; if (arr[m] < target) l = m + 1; else r = m - 1; }
        return -1;
    }
    // First occurrence
    public static int firstOccurrence(int[] arr, int target) {
        int l = 0, r = arr.length - 1, ans = -1;
        while (l <= r) { int m = l + (r - l) / 2; if (arr[m] >= target) { if (arr[m] == target) ans = m; r = m - 1; } else l = m + 1; }
        return ans;
    }
    // Last occurrence
    public static int lastOccurrence(int[] arr, int target) {
        int l = 0, r = arr.length - 1, ans = -1;
        while (l <= r) { int m = l + (r - l) / 2; if (arr[m] <= target) { if (arr[m] == target) ans = m; l = m + 1; } else r = m - 1; }
        return ans;
    }
    // Insertion position (like Arrays.binarySearch but always returns insertion point)
    public static int findInsertPosition(int[] arr, int target) {
        int l = 0, r = arr.length;
        while (l < r) { int m = l + (r - l) / 2; if (arr[m] < target) l = m + 1; else r = m; }
        return l;
    }
    // Search in rotated sorted array: [4,5,6,7,0,1,2]
    public static int findInRotated(int[] arr, int target) {
        int l = 0, r = arr.length - 1;
        while (l <= r) {
            int m = l + (r - l) / 2;
            if (arr[m] == target) return m;
            if (arr[l] <= arr[m]) { // left is sorted
                if (arr[l] <= target && target < arr[m]) r = m - 1; else l = m + 1;
            } else { // right is sorted
                if (arr[m] < target && target <= arr[r]) l = m + 1; else r = m - 1;
            }
        }
        return -1;
    }

    public static void main(String[] args) {
        int[] a = {1, 2, 2, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assert binarySearch(a, 5) >= 0; assert binarySearch(a, 99) == -1;
        assert firstOccurrence(a, 2) == 1; assert lastOccurrence(a, 2) == 3;
        assert findInsertPosition(a, 4) == 5; // 4 already exists at index 5, insert before first occurrence
        assert findInsertPosition(a, 0) == 0; // insert at beginning
        assert findInsertPosition(a, 11) == a.length; // insert at end

        int[] rotated = {4, 5, 6, 7, 0, 1, 2};
        assert findInRotated(rotated, 0) == 4;
        assert findInRotated(rotated, 4) == 0;
        assert findInRotated(rotated, 2) == 6;
        assert findInRotated(rotated, 8) == -1;

        int[] rotated2 = {1}; // single element
        assert findInRotated(rotated2, 1) == 0; assert findInRotated(rotated2, 0) == -1;

        System.out.println("All tests passed!");
    }
}
