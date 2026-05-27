// Dynamic Array (ArrayList) from scratch with auto-resize
public class Solution<E> {
    private Object[] data;
    private int size;
    private static final int DEFAULT_CAPACITY = 10;

    public Solution() { data = new Object[DEFAULT_CAPACITY]; size = 0; }
    public Solution(int initialCapacity) { data = new Object[initialCapacity]; size = 0; }

    public void add(E element) {
        ensureCapacity(size + 1);
        data[size++] = element;
    }

    public void add(int index, E element) {
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        ensureCapacity(size + 1);
        System.arraycopy(data, index, data, index + 1, size - index);
        data[index] = element;
        size++;
    }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        checkIndex(index);
        return (E) data[index];
    }

    public E set(int index, E element) {
        checkIndex(index);
        @SuppressWarnings("unchecked") E old = (E) data[index];
        data[index] = element;
        return old;
    }

    @SuppressWarnings("unchecked")
    public E remove(int index) {
        checkIndex(index);
        E old = (E) data[index];
        int moved = size - index - 1;
        if (moved > 0) System.arraycopy(data, index + 1, data, index, moved);
        data[--size] = null; // prevent memory leak
        return old;
    }

    public boolean remove(Object o) {
        for (int i = 0; i < size; i++) {
            if (o == null ? data[i] == null : o.equals(data[i])) {
                remove(i);
                return true;
            }
        }
        return false;
    }

    public boolean contains(Object o) { return indexOf(o) >= 0; }

    public int indexOf(Object o) {
        for (int i = 0; i < size; i++)
            if (o == null ? data[i] == null : o.equals(data[i])) return i;
        return -1;
    }

    public int size() { return size; }
    public int capacity() { return data.length; }
    public boolean isEmpty() { return size == 0; }

    public void clear() {
        for (int i = 0; i < size; i++) data[i] = null;
        size = 0;
    }

    @SuppressWarnings("unchecked")
    public E[] toArray() {
        Object[] result = new Object[size];
        System.arraycopy(data, 0, result, 0, size);
        return (E[]) result;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > data.length) {
            int newCapacity = data.length + (data.length >> 1); // 1.5x growth
            if (newCapacity < minCapacity) newCapacity = minCapacity;
            Object[] newData = new Object[newCapacity];
            System.arraycopy(data, 0, newData, 0, size);
            data = newData;
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index: " + index + " Size: " + size);
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) { if (i > 0) sb.append(", "); sb.append(data[i]); }
        return sb.append("]").toString();
    }

    // --- Tests ---
    public static void main(String[] args) {
        Solution<Integer> list = new Solution<>();
        assert list.isEmpty() && list.size() == 0;
        for (int i =0;i<20;i++) list.add(i);
        assert list.size()==20 && list.get(0)==0 && list.get(19)==19;
        list.add(5, 999);
        assert list.get(5)==999 && list.size()==21;
        assert list.remove(5)==999 && list.size()==20;
        assert list.set(0, 42)==0 && list.get(0)==42;
        assert list.contains(10) && !list.contains(999);
        assert list.indexOf(10)==10;
        list.clear();
        assert list.isEmpty() && list.size()==0;
        // Test resize (check capacity grows)
        Solution<Integer> small = new Solution<>(2);
        for (int i=0;i<1000;i++) small.add(i);
        assert small.size()==1000 && small.capacity()>=1000;
        for (int i=0;i<1000;i++) assert small.get(i)==i;
        System.out.println("All tests passed!");
    }
}
