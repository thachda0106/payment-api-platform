# Binary/Hex Converter

Implement conversion between decimal, binary, and hexadecimal WITHOUT using built-in conversion functions (no `Integer.toBinaryString()`, `parseInt(x, 2)`, etc.).

## Requirements

Implement the following functions:

1. `decimalToBinary(int n)` → String (e.g., 180 → "10110100")
2. `binaryToDecimal(String binary)` → int (e.g., "10110100" → 180)
3. `decimalToHex(int n)` → String (e.g., 180 → "B4")
4. `hexToDecimal(String hex)` → int (e.g., "B4" → 180)
5. `binaryToHex(String binary)` → String (e.g., "10110100" → "B4")
6. `hexToBinary(String hex)` → String (e.g., "B4" → "10110100")

## Rules

- No `Integer.toBinaryString()`, `Integer.toHexString()`, `Integer.parseInt(x, radix)`, or similar
- The algorithm must be your own: repeated division for decimal→binary, positional arithmetic for binary→decimal
- Handle negative numbers using two's complement (8-bit for simplicity)
- Support leading zeros in binary (e.g., "00001111" → 15)

## Test Cases

```java
// decimalToBinary
assert decimalToBinary(0).equals("0");
assert decimalToBinary(1).equals("1");
assert decimalToBinary(2).equals("10");
assert decimalToBinary(10).equals("1010");
assert decimalToBinary(180).equals("10110100");
assert decimalToBinary(255).equals("11111111");

// binaryToDecimal
assert binaryToDecimal("0") == 0;
assert binaryToDecimal("1") == 1;
assert binaryToDecimal("10") == 2;
assert binaryToDecimal("1010") == 10;
assert binaryToDecimal("10110100") == 180;
assert binaryToDecimal("11111111") == 255;

// decimalToHex
assert decimalToHex(0).equals("0");
assert decimalToHex(10).equals("A");
assert decimalToHex(15).equals("F");
assert decimalToHex(16).equals("10");
assert decimalToHex(180).equals("B4");
assert decimalToHex(255).equals("FF");

// hexToDecimal
assert hexToDecimal("0") == 0;
assert hexToDecimal("A") == 10;
assert hexToDecimal("F") == 15;
assert hexToDecimal("10") == 16;
assert hexToDecimal("B4") == 180;
assert hexToDecimal("FF") == 255;

// Negative numbers (8-bit two's complement)
assert decimalToBinary(-1).equals("11111111");    // -1 in 8-bit
assert decimalToBinary(-128).equals("10000000");  // minimum 8-bit signed
assert binaryToDecimal("11111111") == -1;
assert binaryToDecimal("10000000") == -128;
```

## Starter Code (Java)

```java
public class BinaryConverter {
    
    public static String decimalToBinary(int n) {
        // Handle negative: convert to two's complement 8-bit
        // Handle zero
        // Build binary string by repeated division by 2
        throw new UnsupportedOperationException("Implement me");
    }
    
    public static int binaryToDecimal(String binary) {
        // Handle two's complement: check MSB (first char is '1')
        // For each bit, multiply by positional value (2^position)
        throw new UnsupportedOperationException("Implement me");
    }
    
    public static String decimalToHex(int n) {
        // Repeated division by 16
        // Map remainder 0-15 to '0'-'9', 'A'-'F'
        throw new UnsupportedOperationException("Implement me");
    }
    
    public static int hexToDecimal(String hex) {
        // For each hex digit, multiply by 16^position
        // Map '0'-'9' → 0-9, 'A'-'F' → 10-15 (case insensitive)
        throw new UnsupportedOperationException("Implement me");
    }
    
    public static String binaryToHex(String binary) {
        // Group into nibbles (4 bits), convert each to hex
        // Alternative: binary → decimal → hex
        throw new UnsupportedOperationException("Implement me");
    }
    
    public static String hexToBinary(String hex) {
        // Each hex digit → 4 bits
        throw new UnsupportedOperationException("Implement me");
    }
}
```

## Hints

- `decimalToBinary`: while n > 0: prepend (n % 2) to result, n = n / 2
- `binaryToDecimal`: for char c in binary: result = result * 2 + (c - '0')
- For negative numbers: if MSB is 1, compute -(256 - unsignedValue)
- Hex conversion uses the same pattern but with base 16
- Map `0-9 → 0-9`, `A/a → 10`, `F/f → 15`
