// Graph with BFS, DFS, shortest path (unweighted), and connectivity
import java.util.*;

public class Solution {
    private final Map<Integer, List<Integer>> adj;

    public Solution() { adj = new HashMap<>(); }

    public void addVertex(int v) { adj.putIfAbsent(v, new ArrayList<>()); }
    public void addEdge(int u, int v) { addVertex(u); addVertex(v); adj.get(u).add(v); adj.get(v).add(u); }

    public List<Integer> bfs(int start) {
        List<Integer> result = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Queue<Integer> q = new LinkedList<>();
        q.add(start); visited.add(start);
        while (!q.isEmpty()) {
            int v = q.poll(); result.add(v);
            for (int n : adj.getOrDefault(v, List.of()))
                if (visited.add(n)) q.add(n);
        }
        return result;
    }

    public List<Integer> dfs(int start) {
        List<Integer> result = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        dfsRecursive(start, visited, result);
        return result;
    }
    private void dfsRecursive(int v, Set<Integer> visited, List<Integer> result) {
        visited.add(v); result.add(v);
        for (int n : adj.getOrDefault(v, List.of()))
            if (!visited.contains(n)) dfsRecursive(n, visited, result);
    }

    public boolean hasPath(int start, int end) {
        Set<Integer> visited = new HashSet<>();
        Queue<Integer> q = new LinkedList<>();
        q.add(start); visited.add(start);
        while (!q.isEmpty()) {
            int v = q.poll();
            if (v == end) return true;
            for (int n : adj.getOrDefault(v, List.of()))
                if (visited.add(n)) q.add(n);
        }
        return false;
    }

    public List<Integer> shortestPath(int start, int end) {
        Map<Integer, Integer> parent = new HashMap<>();
        Queue<Integer> q = new LinkedList<>();
        Set<Integer> visited = new HashSet<>();
        q.add(start); visited.add(start); parent.put(start, -1);

        while (!q.isEmpty()) {
            int v = q.poll();
            if (v == end) return reconstructPath(start, end, parent);
            for (int n : adj.getOrDefault(v, List.of())) {
                if (visited.add(n)) { parent.put(n, v); q.add(n); }
            }
        }
        return List.of(); // no path
    }

    private List<Integer> reconstructPath(int start, int end, Map<Integer, Integer> parent) {
        List<Integer> path = new ArrayList<>();
        for (int v = end; v != start; v = parent.get(v)) path.add(v);
        path.add(start);
        Collections.reverse(path);
        return path;
    }

    public int connectedComponents() {
        Set<Integer> visited = new HashSet<>(); int count = 0;
        for (int v : adj.keySet())
            if (visited.add(v)) { count++; bfsFrom(v, visited); }
        return count;
    }
    private void bfsFrom(int start, Set<Integer> visited) {
        Queue<Integer> q = new LinkedList<>(); q.add(start);
        while (!q.isEmpty())
            for (int n : adj.getOrDefault(q.poll(), List.of()))
                if (visited.add(n)) q.add(n);
    }

    // --- Tests ---
    public static void main(String[] args) {
        Solution g = new Solution();
        g.addEdge(1, 2); g.addEdge(1, 3); g.addEdge(2, 4); g.addEdge(3, 4); g.addEdge(4, 5);
        // BFS: level order
        assert g.bfs(1).equals(List.of(1, 2, 3, 4, 5));
        // DFS: depth-first
        assert g.dfs(1).get(0) == 1; // starts at 1
        // Path
        assert g.hasPath(1, 5) && !g.hasPath(1, 99);
        assert g.shortestPath(1, 5).equals(List.of(1, 2, 4, 5));
        assert g.shortestPath(1, 1).equals(List.of(1));
        // Connected components
        g.addEdge(6, 7); // disconnected component
        assert g.connectedComponents() == 2;
        System.out.println("All tests passed!");
    }
}
