import java.io.*;
import java.net.*;

public class TCPClient {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        try (Socket s = new Socket(host, port);
             PrintWriter out = new PrintWriter(s.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
            for (String msg : new String[]{"Hello", "Payment=100000", "World", "QUIT"}) {
                out.println(msg);
                System.out.println("SENT: " + msg + " | RECV: " + in.readLine());
            }
        }
    }
}
