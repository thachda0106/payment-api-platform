import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class IOModelComparison {
    static final int BASE_PORT = 9090;
    static final int CONNECTIONS = 1000;

    public static void main(String[] args) throws Exception {
        System.out.println("=== I/O Model Comparison ===\n");
        long t0 = System.currentTimeMillis();
        testBlockingThreaded();
        System.out.printf("Blocking (thread-per-conn): %d ms%n", System.currentTimeMillis() - t0);
        t0 = System.currentTimeMillis();
        testNonBlockingSelector();
        System.out.printf("Non-blocking (epoll/Selector): %d ms%n", System.currentTimeMillis() - t0);
    }

    static void testBlockingThreaded() throws Exception {
        int port = BASE_PORT;
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setSoTimeout(5000);
            Thread server = new Thread(() -> {
                try { for (int i = 0; i < CONNECTIONS; i++) { Socket s = ss.accept(); new Thread(() -> { try { s.getInputStream().read(); s.close(); } catch (Exception e) {} }).start(); } } catch (Exception e) {}
            }); server.start();
            for (int i = 0; i < CONNECTIONS; i++) { try (Socket s = new Socket("localhost", port)) { s.getOutputStream().write(42); } }
            server.join();
        }
    }

    static void testNonBlockingSelector() throws Exception {
        int port = BASE_PORT + 1;
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(port)); ssc.configureBlocking(false);
            Selector sel = Selector.open(); ssc.register(sel, SelectionKey.OP_ACCEPT);
            int accepted = 0;
            while (accepted < CONNECTIONS) {
                sel.select(100);
                for (Iterator<SelectionKey> it = sel.selectedKeys().iterator(); it.hasNext();) {
                    SelectionKey key = it.next(); it.remove();
                    if (key.isAcceptable()) { SocketChannel sc = ((ServerSocketChannel) key.channel()).accept(); sc.configureBlocking(false); sc.register(sel, SelectionKey.OP_READ); accepted++; }
                }
            }
            int read = 0;
            while (read < CONNECTIONS) {
                sel.select(100);
                for (Iterator<SelectionKey> it = sel.selectedKeys().iterator(); it.hasNext();) {
                    SelectionKey key = it.next(); it.remove();
                    if (key.isReadable()) { ByteBuffer buf = ByteBuffer.allocate(1); ((SocketChannel) key.channel()).read(buf); key.channel().close(); read++; }
                }
            }
        }
    }
}
