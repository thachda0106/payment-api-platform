// Dijkstra's Shortest Path
import java.util.*;

public class Solution {
    record Edge(int to, int weight) {}
    private final Map<Integer, List<Edge>> adj = new HashMap<>();

    public void addEdge(int u, int v, int w) { adj.computeIfAbsent(u, k->new ArrayList<>()).add(new Edge(v, w)); }

    public record Result(Map<Integer, Integer> distances, Map<Integer, Integer> predecessors) {}

    public Result dijkstra(int source) {
        Map<Integer, Integer> dist = new HashMap<>();
        Map<Integer, Integer> prev = new HashMap<>();
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));

        for (int v : adj.keySet()) dist.put(v, Integer.MAX_VALUE);
        dist.put(source, 0);
        pq.add(new int[]{source, 0});

        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int u = cur[0], d = cur[1];
            if (d > dist.get(u)) continue; // stale entry
            for (Edge e : adj.getOrDefault(u, List.of())) {
                int newDist = d + e.weight;
                if (newDist < dist.getOrDefault(e.to, Integer.MAX_VALUE)) {
                    dist.put(e.to, newDist);
                    prev.put(e.to, u);
                    pq.add(new int[]{e.to, newDist});
                }
            }
        }
        return new Result(dist, prev);
    }

    public List<Integer> shortestPath(int source, int target) {
        Result r = dijkstra(source);
        if (!r.distances.containsKey(target) || r.distances.get(target) == Integer.MAX_VALUE)
            return List.of();
        List<Integer> path = new ArrayList<>();
        for (int v = target; v != source; v = r.predecessors.get(v)) path.add(v);
        path.add(source);
        Collections.reverse(path);
        return path;
    }

    // --- Tests ---
    public static void main(String[] args) {
        Solution g = new Solution();
        // Graph: A=0, B=1, C=2, D=3
        g.addEdge(0, 1, 4); g.addEdge(0, 2, 1);
        g.addEdge(2, 1, 2); g.addEdge(1, 3, 1);
        g.addEdge(2, 3, 5);

        var r = g.dijkstra(0);
        assert r.distances.get(0) == 0;
        assert r.distances.get(1) == 3; // 0→2→1 = 1+2=3
        assert r.distances.get(2) == 1; // 0→2 = 1
        assert r.distances.get(3) == 4; // 0→2→1→3 = 1+2+1=4

        assert g.shortestPath(0, 3).equals(List.of(0, 2, 1, 3));
        assert g.shortestPath(0, 0).equals(List.of(0));

        // FX conversion: find cheapest path VND→USD
        Solution fx = new Solution();
        fx.addEdge(0,1,5); fx.addEdge(0,2,10); fx.addEdge(1,3,20); fx.addEdge(2,3,5);
        assert fx.dijkstra(0).distances.get(3) == 15; // 0→2→3 = 10+5
        System.out.println("Cheapest path cost: " + fx.dijkstra(0).distances.get(3));
        System.out.println("All tests passed!");
    }
}
