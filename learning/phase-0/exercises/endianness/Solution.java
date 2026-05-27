// Detects CPU endianness by checking byte order of an int
public class Solution {
    public static void main(String[] args) {
        int value = 0x12345678;
        // Read first byte of the integer by casting to byte array via NIO
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4);
        bb.putInt(value);
        byte firstByte = bb.get(0);
        if (firstByte == 0x12)
            System.out.println("BIG-ENDIAN (network byte order)");
        else if (firstByte == 0x78)
            System.out.println("LITTLE-ENDIAN (x86/ARM default)");
        else
            System.out.println("UNKNOWN: first byte = 0x" + Integer.toHexString(firstByte & 0xFF));

        // Alternative: use Unsafe (demonstrates direct memory access)
        System.out.println("\nVerification with direct memory layout:");
        bb.position(0);
        System.out.printf("  Byte 0: 0x%02X%n", bb.get() & 0xFF);
        System.out.printf("  Byte 1: 0x%02X%n", bb.get() & 0xFF);
        System.out.printf("  Byte 2: 0x%02X%n", bb.get() & 0xFF);
        System.out.printf("  Byte 3: 0x%02X%n", bb.get() & 0xFF);

        // Check via library
        String order = java.nio.ByteOrder.nativeOrder().toString();
        System.out.println("\njava.nio.ByteOrder.nativeOrder() = " + order);
    }
}
