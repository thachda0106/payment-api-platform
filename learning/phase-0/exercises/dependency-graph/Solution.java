// Payment Dependency Graph: Topological sort of payment processing steps
// Detects circular dependencies
import java.util.*;

public class Solution {
    static class Step { String name; List<String> dependencies; Step(String n) { name = n; dependencies = new ArrayList<>(); } }

    public static List<String> topologicalSort(List<Step> steps) {
        Map<String, List<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (Step s : steps) { graph.putIfAbsent(s.name, new ArrayList<>()); inDegree.putIfAbsent(s.name, 0); }
        for (Step s : steps) {
            for (String dep : s.dependencies) {
                graph.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.name);
                inDegree.merge(s.name, 1, Integer::sum);
            }
        }

        Queue<String> q = new LinkedList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet())
            if (e.getValue() == 0) q.add(e.getKey());

        List<String> result = new ArrayList<>();
        while (!q.isEmpty()) {
            String step = q.poll(); result.add(step);
            for (String next : graph.getOrDefault(step, List.of())) {
                int newDegree = inDegree.get(next) - 1;
                inDegree.put(next, newDegree);
                if (newDegree == 0) q.add(next);
            }
        }

        if (result.size() != steps.size())
            throw new IllegalStateException("CIRCULAR DEPENDENCY DETECTED! Remaining: " +
                inDegree.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList());

        return result;
    }

    // --- Tests ---
    public static void main(String[] args) {
        // Real payment processing dependencies
        List<Step> steps = List.of(
            new Step("INITIATED") {{ }},
            new Step("VALIDATE_AMOUNT") {{ dependencies.add("INITIATED"); }},
            new Step("FRAUD_CHECK") {{ dependencies.add("VALIDATE_AMOUNT"); }},
            new Step("FEE_CALCULATION") {{ dependencies.add("VALIDATE_AMOUNT"); }},
            new Step("LEDGER_WRITE") {{ dependencies.add("FRAUD_CHECK"); dependencies.add("FEE_CALCULATION"); }},
            new Step("NOTIFICATION") {{ dependencies.add("LEDGER_WRITE"); }}
        );

        List<String> order = topologicalSort(steps);
        System.out.println("Payment processing order: " + order);

        // Verify ordering constraints
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < order.size(); i++) pos.put(order.get(i), i);
        assert pos.get("INITIATED") < pos.get("VALIDATE_AMOUNT");
        assert pos.get("VALIDATE_AMOUNT") < pos.get("FRAUD_CHECK");
        assert pos.get("VALIDATE_AMOUNT") < pos.get("FEE_CALCULATION");
        assert pos.get("FRAUD_CHECK") < pos.get("LEDGER_WRITE");
        assert pos.get("FEE_CALCULATION") < pos.get("LEDGER_WRITE");
        assert pos.get("LEDGER_WRITE") < pos.get("NOTIFICATION");
        System.out.println("All ordering constraints satisfied!");

        // Circular dependency test
        List<Step> circular = List.of(
            new Step("A") {{ dependencies.add("C"); }},
            new Step("B") {{ dependencies.add("A"); }},
            new Step("C") {{ dependencies.add("B"); }}
        );
        try { topologicalSort(circular); System.out.println("FAIL: Should have detected cycle"); }
        catch (IllegalStateException e) { System.out.println("Cycle correctly detected: " + e.getMessage()); }
    }
}
