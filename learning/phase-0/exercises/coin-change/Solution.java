// Coin Change — Dynamic Programming (memoization + tabulation)
import java.util.*;

public class Solution {
    // Memoization (top-down)
    public static int minCoins(int[] coins, int amount) {
        int[] memo = new int[amount + 1];
        Arrays.fill(memo, -2);
        return minCoinsMemo(coins, amount, memo);
    }
    private static int minCoinsMemo(int[] coins, int amount, int[] memo) {
        if (amount == 0) return 0;
        if (amount < 0) return -1;
        if (memo[amount] != -2) return memo[amount];
        int min = Integer.MAX_VALUE;
        for (int c : coins) {
            int sub = minCoinsMemo(coins, amount - c, memo);
            if (sub >= 0) min = Math.min(min, sub + 1);
        }
        return memo[amount] = (min == Integer.MAX_VALUE ? -1 : min);
    }

    // Tabulation (bottom-up)
    public static int minCoinsDP(int[] coins, int amount) {
        int[] dp = new int[amount + 1];
        Arrays.fill(dp, amount + 1);
        dp[0] = 0;
        for (int a = 1; a <= amount; a++)
            for (int c : coins)
                if (c <= a) dp[a] = Math.min(dp[a], dp[a - c] + 1);
        return dp[amount] > amount ? -1 : dp[amount];
    }

    // Which coins? (returns list of denominations)
    public static List<Integer> coinCombination(int[] coins, int amount) {
        int[] dp = new int[amount + 1];
        int[] usedCoin = new int[amount + 1];
        Arrays.fill(dp, amount + 1);
        dp[0] = 0;
        for (int a = 1; a <= amount; a++) {
            for (int c : coins) {
                if (c <= a && dp[a - c] + 1 < dp[a]) {
                    dp[a] = dp[a - c] + 1;
                    usedCoin[a] = c;
                }
            }
        }
        if (dp[amount] > amount) return List.of();
        List<Integer> result = new ArrayList<>();
        for (int a = amount; a > 0; a -= usedCoin[a]) result.add(usedCoin[a]);
        return result;
    }

    public static void main(String[] args) {
        int[] coins = {1, 5, 6, 8};
        assert minCoins(coins, 0) == 0;
        assert minCoins(coins, 1) == 1;
        assert minCoins(coins, 5) == 1;
        assert minCoins(coins, 6) == 1;
        assert minCoins(coins, 11) == 2; // 5+6
        assert minCoins(coins, 15) == 3;

        // Both methods give same result
        assert minCoins(coins, 11) == minCoinsDP(coins, 11);
        assert minCoins(coins, 15) == minCoinsDP(coins, 15);

        // Impossible
        assert minCoins(new int[]{2, 4}, 3) == -1;
        assert minCoins(new int[]{5, 10}, 3) == -1;

        // VND greedy is optimal
        int[] vnd = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000};
        assert minCoinsDP(vnd, 88000) == 6;
        assert minCoinsDP(vnd, 0) == 0;

        // Coin combination
        assert coinCombination(new int[]{1,5,6,8}, 11).stream().mapToInt(i->i).sum() == 11;
        System.out.println("11 VND = " + coinCombination(new int[]{1,5,6,8}, 11));

        // Large amount performance
        long t0 = System.nanoTime();
        int result = minCoinsDP(vnd, 10_000_000);
        System.out.printf("10M VND = %d coins (computed in %.1f ms)%n", result, (System.nanoTime()-t0)/1e6);
        System.out.println("All tests passed!");
    }
}
