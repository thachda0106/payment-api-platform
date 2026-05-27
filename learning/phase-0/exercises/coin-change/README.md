# Coin Change — Dynamic Programming

Given a set of coin denominations and a target amount, find the MINIMUM number of coins needed to make that amount.

## Problem

VND currency denominations: {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000}

Example: amount = 88,000 VND
- Greedy: 50,000 + 20,000 + 10,000 + 5,000 + 2,000 + 1,000 = 6 coins
- Is greedy always optimal for VND? (Yes — VND denominations are canonical)

But for arbitrary denominations: coins = {1, 3, 4}, amount = 6
- Greedy: 4 + 1 + 1 = 3 coins (NOT optimal)
- Optimal: 3 + 3 = 2 coins

## Requirements

Implement both:

1. **Memoization (top-down DP)**:
```java
public static int minCoins(int[] coins, int amount)
// Returns minimum number of coins, or -1 if impossible
```

2. **Tabulation (bottom-up DP)**:
```java
public static int minCoinsDP(int[] coins, int amount)
// Returns minimum number of coins, or -1 if impossible
```

3. **Coin combination** (which coins?):
```java
public static List<Integer> coinCombination(int[] coins, int amount)
// Returns the list of coin denominations used, or empty list if impossible
```

## DP Recurrence

```
dp[0] = 0  (no coins needed for amount 0)
dp[a] = min(dp[a], dp[a - coin] + 1) for each coin where coin <= a
```

For amount = 11, coins = {1, 5, 6}:
```
dp[0] = 0
dp[1] = min(dp[1], dp[0]+1) = 1   (coin 1)
dp[2] = min(dp[2], dp[1]+1) = 2   (coin 1 + coin 1)
dp[3] = min(dp[3], dp[2]+1) = 3
dp[4] = min(dp[4], dp[3]+1) = 4
dp[5] = min(dp[5], dp[0]+1) = 1   (coin 5)
dp[6] = min(dp[6], dp[0]+1) = 1   (coin 6)
dp[7] = min(dp[7], dp[1]+1 OR dp[6]+1) = 2   (coin 1 + coin 6)
...
dp[11]= min(dp[10]+1, dp[6]+1, dp[5]+1) = min(3, 2, 2) = 2 (coin 5 + coin 6)
```

## Test Cases

```java
int[] coins = {1, 5, 6, 8};
assert minCoins(coins, 0) == 0;
assert minCoins(coins, 1) == 1;   // 1
assert minCoins(coins, 5) == 1;   // 5
assert minCoins(coins, 6) == 1;   // 6
assert minCoins(coins, 11) == 2;  // 5 + 6
assert minCoins(coins, 15) == 3;  // 5 + 5 + 5 or 6 + 8 + 1

// Impossible case
assert minCoins(new int[]{2, 4}, 3) == -1;   // Can't make odd with only evens
assert minCoins(new int[]{5, 10}, 3) == -1;  // All coins > amount

// VND denominations: greedy is optimal
int[] vnd = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000};
assert minCoins(vnd, 88000) == 6;  // 50000 + 20000 + 10000 + 5000 + 2000 + 1000
assert minCoins(vnd, 0) == 0;

// Large amount (memoization vs tabulation performance)
assert minCoins(vnd, 1000000) > 0;  // Should complete in < 100ms
```

## Analysis Questions

1. What is the time complexity of the DP solution? Space complexity?
2. When would memoization (top-down) be faster than tabulation (bottom-up)?
3. Why does the greedy algorithm work for VND but not for arbitrary coin sets?
4. How would you modify the solution to return ALL valid combinations (not just the minimum)?
5. What optimization could you use if the amount is very large (10⁹+) but the number of coin denominations is small?
