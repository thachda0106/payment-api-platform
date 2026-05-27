public class Solution {
    public static String decimalToBinary(int n) {
        if (n == 0) return "0";
        boolean negative = n < 0;
        int val = negative ? (256 + n) : n; // 8-bit two's complement
        StringBuilder sb = new StringBuilder();
        while (val > 0) { sb.insert(0, val % 2); val /= 2; }
        if (negative && sb.length() < 8)
            while (sb.length() < 8) sb.insert(0, '0');
        return sb.toString();
    }

    public static int binaryToDecimal(String binary) {
        int n = binary.length();
        if (n <= 8 && binary.charAt(0) == '1' && n == 8) {
            int unsigned = 0;
            for (int i = 0; i < n; i++) unsigned = unsigned * 2 + (binary.charAt(i) - '0');
            return unsigned - 256;
        }
        int result = 0;
        for (int i = 0; i < n; i++) result = result * 2 + (binary.charAt(i) - '0');
        return result;
    }

    public static String decimalToHex(int n) {
        if (n == 0) return "0";
        boolean negative = n < 0;
        long val = negative ? (256L + n) : n;
        StringBuilder sb = new StringBuilder();
        while (val > 0) { int rem = (int)(val % 16); sb.insert(0, hexChar(rem)); val /= 16; }
        return sb.toString();
    }

    public static int hexToDecimal(String hex) {
        int result = 0;
        for (int i = 0; i < hex.length(); i++)
            result = result * 16 + hexValue(hex.charAt(i));
        if (hex.length() == 2 && hexValue(hex.charAt(0)) >= 8) return result - 256;
        return result;
    }

    public static String binaryToHex(String binary) { return decimalToHex(binaryToDecimal(binary)); }
    public static String hexToBinary(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            int val = hexValue(hex.charAt(i));
            sb.append((val & 8) > 0 ? '1' : '0');
            sb.append((val & 4) > 0 ? '1' : '0');
            sb.append((val & 2) > 0 ? '1' : '0');
            sb.append((val & 1) > 0 ? '1' : '0');
        }
        while (sb.length() > 1 && sb.charAt(0) == '0') sb.deleteCharAt(0);
        return sb.toString();
    }

    private static char hexChar(int n) { return (char)(n < 10 ? '0' + n : 'A' + n - 10); }
    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        throw new IllegalArgumentException("Invalid hex: " + c);
    }

    public static void main(String[] args) {
        assertEq("0", decimalToBinary(0)); assertEq("1", decimalToBinary(1));
        assertEq("10", decimalToBinary(2)); assertEq("1010", decimalToBinary(10));
        assertEq("10110100", decimalToBinary(180)); assertEq("11111111", decimalToBinary(255));
        assertEq("11111111", decimalToBinary(-1)); assertEq("10000000", decimalToBinary(-128));
        assert 0 == binaryToDecimal("0"); assert 1 == binaryToDecimal("1");
        assert 10 == binaryToDecimal("1010"); assert 180 == binaryToDecimal("10110100");
        assert -1 == binaryToDecimal("11111111"); assert -128 == binaryToDecimal("10000000");
        assertEq("0", decimalToHex(0)); assertEq("A", decimalToHex(10));
        assertEq("F", decimalToHex(15)); assertEq("10", decimalToHex(16));
        assertEq("B4", decimalToHex(180)); assertEq("FF", decimalToHex(255));
        assert 0 == hexToDecimal("0"); assert 10 == hexToDecimal("A");
        assert 15 == hexToDecimal("F"); assert 180 == hexToDecimal("B4");
        assertEq("10110100", binaryToHex("10110100")); assertEq("B4", binaryToHex("10110100"));
        assertEq("10110100", hexToBinary("B4")); assertEq("11111111", hexToBinary("FF"));
        System.out.println("All tests passed!");
    }
    static void assertEq(String a, String b) { if (!a.equals(b)) throw new AssertionError(a + " != " + b); }
}
