// Binary Search Tree with insert, search, delete, traversal
import java.util.*;

public class Solution {
    static class Node {
        int val; Node left, right;
        Node(int v) { val = v; }
    }
    private Node root;

    public void insert(int val) { root = insert(root, val); }
    private Node insert(Node node, int val) {
        if (node == null) return new Node(val);
        if (val < node.val) node.left = insert(node.left, val);
        else if (val > node.val) node.right = insert(node.right, val);
        return node;
    }

    public boolean search(int val) { return search(root, val); }
    private boolean search(Node node, int val) {
        if (node == null) return false;
        if (val == node.val) return true;
        return val < node.val ? search(node.left, val) : search(node.right, val);
    }

    public void delete(int val) { root = delete(root, val); }
    private Node delete(Node node, int val) {
        if (node == null) return null;
        if (val < node.val) node.left = delete(node.left, val);
        else if (val > node.val) node.right = delete(node.right, val);
        else {
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;
            Node min = findMin(node.right);
            node.val = min.val;
            node.right = delete(node.right, min.val);
        }
        return node;
    }

    private Node findMin(Node node) { while (node.left != null) node = node.left; return node; }
    private Node findMax(Node node) { while (node.right != null) node = node.right; return node; }

    public List<Integer> inorder() { List<Integer> r = new ArrayList<>(); inorder(root, r); return r; }
    private void inorder(Node n, List<Integer> r) { if (n != null) { inorder(n.left, r); r.add(n.val); inorder(n.right, r); } }

    public List<Integer> preorder() { List<Integer> r = new ArrayList<>(); preorder(root, r); return r; }
    private void preorder(Node n, List<Integer> r) { if (n != null) { r.add(n.val); preorder(n.left, r); preorder(n.right, r); } }

    public List<Integer> postorder() { List<Integer> r = new ArrayList<>(); postorder(root, r); return r; }
    private void postorder(Node n, List<Integer> r) { if (n != null) { postorder(n.left, r); postorder(n.right, r); r.add(n.val); } }

    public List<Integer> levelOrder() {
        List<Integer> r = new ArrayList<>();
        if (root == null) return r;
        Queue<Node> q = new LinkedList<>(); q.add(root);
        while (!q.isEmpty()) {
            Node n = q.poll(); r.add(n.val);
            if (n.left != null) q.add(n.left);
            if (n.right != null) q.add(n.right);
        }
        return r;
    }

    public int height() { return height(root); }
    private int height(Node n) { return n == null ? -1 : 1 + Math.max(height(n.left), height(n.right)); }

    public boolean isBalanced() { return isBalanced(root) != -1; }
    private int isBalanced(Node n) {
        if (n == null) return 0;
        int lh = isBalanced(n.left); if (lh == -1) return -1;
        int rh = isBalanced(n.right); if (rh == -1) return -1;
        if (Math.abs(lh - rh) > 1) return -1;
        return 1 + Math.max(lh, rh);
    }

    public Integer findMinVal() { return root == null ? null : findMin(root).val; }
    public Integer findMaxVal() { return root == null ? null : findMax(root).val; }

    // --- Tests ---
    public static void main(String[] args) {
        Solution bst = new Solution();
        int[] vals = {50, 30, 70, 20, 40, 60, 80};
        for (int v : vals) bst.insert(v);
        assert bst.search(50) && bst.search(20) && bst.search(80) && !bst.search(99);
        assert bst.inorder().equals(List.of(20, 30, 40, 50, 60, 70, 80));
        assert bst.preorder().equals(List.of(50, 30, 20, 40, 70, 60, 80));
        assert bst.postorder().equals(List.of(20, 40, 30, 60, 80, 70, 50));
        assert bst.levelOrder().equals(List.of(50, 30, 70, 20, 40, 60, 80));
        assert bst.height() == 2 && bst.isBalanced();
        assert bst.findMinVal()==20 && bst.findMaxVal()==80;
        bst.delete(20); assert !bst.search(20) && bst.search(30);
        bst.delete(50); assert !bst.search(50) && bst.search(70);
        System.out.println("All tests passed!");
    }
}
