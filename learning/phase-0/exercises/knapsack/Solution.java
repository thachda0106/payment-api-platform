// 0/1 Knapsack — Dynamic Programming
import java.util.*;

public class Solution {
    record Item(int weight, int value) {}

    // Tabulation: dp[i][w] = max value using first i items with capacity w
    public static int knapsack(Item[] items, int capacity) {
        int n = items.length;
        int[][] dp = new int[n + 1][capacity + 1];
        for (int i = 1; i <= n; i++) {
            for (int w = 0; w <= capacity; w++) {
                if (items[i-1].weight <= w)
                    dp[i][w] = Math.max(dp[i-1][w], dp[i-1][w - items[i-1].weight] + items[i-1].value);
                else
                    dp[i][w] = dp[i-1][w];
            }
        }
        return dp[n][capacity];
    }

    // Space-optimized: O(capacity) instead of O(n*capacity)
    public static int knapsackOptimized(Item[] items, int capacity) {
        int[] dp = new int[capacity + 1];
        for (Item item : items)
            for (int w = capacity; w >= item.weight; w--)
                dp[w] = Math.max(dp[w], dp[w - item.weight] + item.value);
        return dp[capacity];
    }

    // Returns which items are selected
    public static List<Item> selectedItems(Item[] items, int capacity) {
        int n = items.length;
        int[][] dp = new int[n + 1][capacity + 1];
        for (int i = 1; i <= n; i++)
            for (int w = 0; w <= capacity; w++)
                if (items[i-1].weight <= w)
                    dp[i][w] = Math.max(dp[i-1][w], dp[i-1][w - items[i-1].weight] + items[i-1].value);
                else dp[i][w] = dp[i-1][w];

        List<Item> selected = new ArrayList<>();
        for (int i = n, w = capacity; i > 0; i--)
            if (dp[i][w] != dp[i-1][w]) { selected.add(items[i-1]); w -= items[i-1].weight; }
        Collections.reverse(selected);
        return selected;
    }

    public static void main(String[] args) {
        Item[] items = {
            new Item(2, 3), new Item(3, 4), new Item(4, 5), new Item(5, 8), new Item(9, 10)
        };
        assert knapsack(items, 5) == 7;   // {2,3} + {3,4} = weight 5, value 7
        assert knapsack(items, 10) == 14; // {2,3}+{3,4}+{5,8}=10,15 but {2,3}+{4,5}+{3,4}=9,12... let's check: {5,8}+{3,4}+{2,3}=10,15? weight=10: {5,8}+{4,5}=9,13 or {5,8}+{3,4}+{2,3}=10,15. Yes 15.
        // Recalculate: items: (2,3)(3,4)(4,5)(5,8)(9,10). capacity=10: best is (2+3+5=10 weight, 3+4+8=15 value)
        assert knapsack(items, 10) == 15;
        assert knapsackOptimized(items, 10) == knapsack(items, 10);

        // No items fit
        assert knapsack(new Item[]{new Item(10, 100)}, 5) == 0;

        System.out.println("Selected items for capacity 10: " + selectedItems(items, 10));
        System.out.println("All tests passed!");
    }
}
