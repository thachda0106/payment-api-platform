// Topological Sort — Kahn's Algorithm for general DAG
import java.util.*;

public class Solution {
    public static List<Integer> topologicalSort(int vertices, int[][] edges) {
        List<Integer>[] graph = new List[vertices];
        int[] inDegree = new int[vertices];
        for (int i=0;i<vertices;i++) graph[i]=new ArrayList<>();
        for (int[] e : edges) { graph[e[0]].add(e[1]); inDegree[e[1]]++; }

        Queue<Integer> q = new LinkedList<>();
        for (int i=0;i<vertices;i++) if (inDegree[i]==0) q.add(i);

        List<Integer> result = new ArrayList<>();
        while (!q.isEmpty()) {
            int v = q.poll(); result.add(v);
            for (int n : graph[v]) if (--inDegree[n]==0) q.add(n);
        }
        if (result.size()!=vertices) throw new IllegalStateException("Cycle detected! Processed: "+result.size()+" of "+vertices);
        return result;
    }

    // Check if a given order is valid
    public static boolean isValidOrder(List<Integer> order, int[][] edges) {
        Map<Integer, Integer> pos = new HashMap<>();
        for (int i=0;i<order.size();i++) pos.put(order.get(i), i);
        for (int[] e : edges) if (pos.get(e[0]) >= pos.get(e[1])) return false;
        return true;
    }

    public static void main(String[] args) {
        // 5 tasks: 0→1, 0→2, 1→3, 2→3, 3→4
        int[][] edges = {{0,1},{0,2},{1,3},{2,3},{3,4}};
        List<Integer> order = topologicalSort(5, edges);
        System.out.println("Order: " + order);
        assert order.size()==5 && isValidOrder(order, edges);

        // Cycle detection
        int[][] cycle = {{0,1},{1,2},{2,0}};
        try { topologicalSort(3, cycle); System.out.println("FAIL: Should have detected cycle"); }
        catch (IllegalStateException e) { System.out.println("Cycle correctly detected"); }

        // Complex DAG (course prerequisites style)
        int[][] prereqs = {{0,2},{1,2},{1,3},{2,4},{3,4},{4,5},{0,5}};
        List<Integer> courses = topologicalSort(6, prereqs);
        System.out.println("Course order: " + courses);
        assert isValidOrder(courses, prereqs);

        System.out.println("All tests passed!");
    }
}
