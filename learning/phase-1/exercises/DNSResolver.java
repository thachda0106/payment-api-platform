import java.net.*;

public class DNSResolver {
    public static void main(String[] args) throws Exception {
        String[] hosts = {"google.com", "stripe.com", "github.com", "amazon.com", "nonexistent.domain.invalid"};
        System.out.printf("%-30s %-18s %-40s %-10s%n", "Host", "IP", "Canonical", "Reachable");
        System.out.println("-".repeat(100));
        for (String host : hosts) {
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                for (InetAddress addr : addrs) {
                    boolean reachable = addr.isReachable(2000);
                    System.out.printf("%-30s %-18s %-40s %-10s%n",
                        host, addr.getHostAddress(), truncate(addr.getCanonicalHostName(), 38), reachable ? "YES" : "NO");
                }
            } catch (UnknownHostException e) {
                System.out.printf("%-30s %s%n", host, "NOT FOUND");
            }
        }
        System.out.println("\nJava DNS Cache Settings:");
        System.out.println("  networkaddress.cache.ttl = " + java.security.Security.getProperty("networkaddress.cache.ttl"));
        System.out.println("  networkaddress.cache.negative.ttl = " + java.security.Security.getProperty("networkaddress.cache.negative.ttl"));
    }
    static String truncate(String s, int len) { return s.length() <= len ? s : s.substring(0, len - 3) + "..."; }
}
