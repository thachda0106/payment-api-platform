// Mini Project: Secure Authentication Service (simulated JWT issuer + verifier)
// Run: javac AuthService.java && java AuthService

import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;

public class AuthService {
    // ═══════════════════════════════════════════════════════════════════════
    // Simplified JWT (no library — demonstrates structure)
    // ═══════════════════════════════════════════════════════════════════════
    record JWT(String header, String payload, String signature) {
        String encoded() { return header + "." + payload + "." + signature; }
    }

    static class JWKS {
        record Key(String kid, String publicKeyPEM) {}
        private final Map<String, Key> keys = new ConcurrentHashMap<>();

        void addKey(String kid, String publicKeyPEM) { keys.put(kid, new Key(kid, publicKeyPEM)); }
        void removeKey(String kid) { keys.remove(kid); }
        Key getKey(String kid) { return keys.get(kid); }
        Collection<Key> allKeys() { return keys.values(); }
    }

    private final JWKS jwks = new JWKS();
    private String activeKeyId = "key-1";
    private final Map<String, String> revokedTokens = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════════
    // Token Issuance
    // ═══════════════════════════════════════════════════════════════════════
    String issueToken(String userId, List<String> scopes) {
        long now = System.currentTimeMillis() / 1000;
        String header = b64("{\"alg\":\"SIMULATED\",\"kid\":\"" + activeKeyId + "\",\"typ\":\"JWT\"}");
        String payload = b64(String.format(
            "{\"sub\":\"%s\",\"scope\":\"%s\",\"iat\":%d,\"exp\":%d,\"iss\":\"auth.payment.vn\"}",
            userId, String.join(" ", scopes), now, now + 300));

        // In production: RS256 signature = RSA.sign(SHA256(header.payload), privateKey)
        String signature = b64("simulated-signature-" + activeKeyId);

        return header + "." + payload + "." + signature;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Token Validation
    // ═══════════════════════════════════════════════════════════════════════
    record ValidationResult(boolean valid, String userId, List<String> scopes, String error) {}

    ValidationResult validateToken(String tokenString) {
        String[] parts = tokenString.split("\\.");
        if (parts.length != 3) return new ValidationResult(false, null, null, "Invalid token format");

        // Decode payload
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
        Map<String, Object> claims = parseJSON(payloadJson);

        // Check expiration
        long exp = ((Number) claims.getOrDefault("exp", 0)).longValue();
        long now = System.currentTimeMillis() / 1000;
        if (exp < now) return new ValidationResult(false, null, null, "Token expired");

        // Check issuer
        if (!"auth.payment.vn".equals(claims.get("iss")))
            return new ValidationResult(false, null, null, "Invalid issuer");

        // Check revocation (simulated stored in map)
        if (revokedTokens.containsKey(tokenString.substring(0, 20)))
            return new ValidationResult(false, null, null, "Token revoked");

        // Decode header to get kid
        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
        String kid = (String) parseJSON(headerJson).get("kid");

        // Check key exists in JWKS
        JWKS.Key key = jwks.getKey(kid);
        if (key == null) return new ValidationResult(false, null, null, "Unknown key: " + kid);

        // In production: verify RSA signature using public key
        // if (!RSA.verify(parts[0] + "." + parts[1], decode(parts[2]), publicKey))
        //     return new ValidationResult(false, null, null, "Invalid signature");

        String userId = (String) claims.get("sub");
        String scopeStr = (String) claims.get("scope");
        List<String> scopes = scopeStr != null ? List.of(scopeStr.split(" ")) : List.of();

        return new ValidationResult(true, userId, scopes, null);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Key Rotation
    // ═══════════════════════════════════════════════════════════════════════
    void rotateKey() {
        String oldKeyId = activeKeyId;
        activeKeyId = "key-" + (Integer.parseInt(activeKeyId.substring(4)) + 1);
        jwks.addKey(activeKeyId, "PUBLIC-KEY-PEM-" + activeKeyId);
        System.out.println("  Key rotated: " + oldKeyId + " → " + activeKeyId);

        // Schedule old key removal after 5 seconds (simulating 5-minute grace period)
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            jwks.removeKey(oldKeyId);
            System.out.println("  Old key " + oldKeyId + " removed from JWKS");
        }, 5, TimeUnit.SECONDS);
    }

    void revokeToken(String token) { revokedTokens.put(token.substring(0, 20), "revoked"); }

    // ═══════════════════════════════════════════════════════════════════════
    // RBAC Enforcement
    // ═══════════════════════════════════════════════════════════════════════
    boolean hasScope(ValidationResult validation, String requiredScope) {
        return validation.valid() && validation.scopes().contains(requiredScope);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Demo
    // ═══════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        System.out.println("=== Secure Authentication Service ===\n");
        AuthService auth = new AuthService();

        // Initialize JWKS with key-1
        auth.jwks.addKey("key-1", "PUBLIC-KEY-PEM-key-1");

        // Test 1: Issue and validate token
        System.out.println("Test 1: Issue & Validate");
        String token = auth.issueToken("U1", List.of("read:wallets", "write:payments"));
        var result = auth.validateToken(token);
        assert result.valid() : "Token should be valid";
        assert "U1".equals(result.userId());
        assert result.scopes().contains("write:payments");
        System.out.println("  Token valid for " + result.userId() + " with scopes: " + result.scopes());
        System.out.println("  PASS\n");

        // Test 2: RBAC enforcement
        System.out.println("Test 2: RBAC");
        assert auth.hasScope(result, "write:payments") : "Should have write:payments";
        assert !auth.hasScope(result, "admin:freeze") : "Should NOT have admin scope";
        System.out.println("  PASS\n");

        // Test 3: Key rotation
        System.out.println("Test 3: Key Rotation");
        auth.rotateKey();
        String token2 = auth.issueToken("U2", List.of("read:wallets"));
        var result2 = auth.validateToken(token2);
        assert result2.valid() : "Token with new key should be valid";
        // Old token still valid (old key still in JWKS)
        assert auth.validateToken(token).valid() : "Old token should still be valid during grace period";
        System.out.println("  Old token valid during grace period");
        Thread.sleep(6000); // Wait for old key removal
        var oldResult = auth.validateToken(token);
        assert !oldResult.valid() : "Old token should be invalid after key removed";
        System.out.println("  Old token INVALID after key removal");
        System.out.println("  PASS\n");

        // Test 4: Token revocation
        System.out.println("Test 4: Revocation");
        auth.revokeToken(token2);
        assert !auth.validateToken(token2).valid() : "Revoked token should be invalid";
        System.out.println("  PASS\n");

        System.out.println("All tests passed!");
    }

    // Helpers
    static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes()); }
    static Map<String, Object> parseJSON(String json) {
        Map<String, Object> m = new HashMap<>();
        json = json.replaceAll("[{}\"]", "").trim();
        for (String pair : json.split(",")) {
            String[] kv = pair.trim().split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                try { m.put(key, Long.parseLong(val)); } catch (NumberFormatException e) { m.put(key, val); }
            }
        }
        return m;
    }
    static void assert(boolean condition, String msg) { if (!condition) throw new AssertionError(msg); }
}
