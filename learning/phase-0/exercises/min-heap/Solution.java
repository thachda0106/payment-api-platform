// Min-Heap implemented using array (complete binary tree)
import java.util.*;

public class Solution {
    private int[] heap;
    private int size;
    private static final int DEFAULT = 16;

    public Solution() { heap = new int[DEFAULT]; size = 0; }
    public Solution(int capacity) { heap = new int[capacity]; size = 0; }

    public void insert(int val) {
        if (size == heap.length) resize();
        heap[size] = val;
        bubbleUp(size);
        size++;
    }

    public int extractMin() {
        if (size == 0) throw new NoSuchElementException("Heap empty");
        int min = heap[0];
        heap[0] = heap[size - 1];
        size--;
        bubbleDown(0);
        return min;
    }

    public int peek() {
        if (size == 0) throw new NoSuchElementException("Heap empty");
        return heap[0];
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    // Build heap from unsorted array in O(n) — Floyd's algorithm
    public void heapify(int[] arr) {
        heap = Arrays.copyOf(arr, arr.length);
        size = arr.length;
        for (int i = parent(size - 1); i >= 0; i--) bubbleDown(i);
    }

    public int[] toSortedArray() {
        int[] result = new int[size];
        int originalSize = size;
        int[] backup = Arrays.copyOf(heap, size);
        for (int i = 0; i < originalSize; i++) result[i] = extractMin();
        heap = backup; size = originalSize;
        return result;
    }

    private void bubbleUp(int idx) {
        while (idx > 0 && heap[idx] < heap[parent(idx)]) {
            swap(idx, parent(idx));
            idx = parent(idx);
        }
    }

    private void bubbleDown(int idx) {
        while (true) {
            int left = leftChild(idx), right = rightChild(idx), smallest = idx;
            if (left < size && heap[left] < heap[smallest]) smallest = left;
            if (right < size && heap[right] < heap[smallest]) smallest = right;
            if (smallest == idx) break;
            swap(idx, smallest);
            idx = smallest;
        }
    }

    private int parent(int i) { return (i - 1) / 2; }
    private int leftChild(int i) { return 2 * i + 1; }
    private int rightChild(int i) { return 2 * i + 2; }
    private void swap(int i, int j) { int t = heap[i]; heap[i] = heap[j]; heap[j] = t; }
    private void resize() { heap = Arrays.copyOf(heap, heap.length * 2); }

    // --- Tests ---
    public static void main(String[] args) {
        Solution h = new Solution();
        assert h.isEmpty() && h.size()==0;
        h.insert(5); h.insert(3); h.insert(8); h.insert(1); h.insert(9);
        assert h.peek() == 1 && h.size()==5;
        assert h.extractMin()==1 && h.extractMin()==3 && h.extractMin()==5;
        assert h.extractMin()==8 && h.extractMin()==9 && h.isEmpty();

        // heapify test
        Solution h2 = new Solution();
        h2.heapify(new int[]{5, 3, 8, 1, 9, 2, 7, 4, 6});
        int[] sorted = h2.toSortedArray();
        for (int i = 0; i < sorted.length - 1; i++) assert sorted[i] <= sorted[i+1];
        for (int i = 1; i <= 9; i++) assert sorted[i-1] == i;
        System.out.println("All tests passed!");
    }
}
