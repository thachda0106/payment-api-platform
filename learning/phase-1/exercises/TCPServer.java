import java.io.*;
import java.net.*;

public class TCPServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("TCP Echo Server listening on port " + port);
            while (true) {
                Socket client = ss.accept();
                new Thread(() -> handle(client)).start();
            }
        }
    }
    static void handle(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("QUIT")) break;
                out.println("ECHO: " + line);
            }
        } catch (IOException e) { /* client disconnected */ }
    }
}
