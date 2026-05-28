# Module 01 — Cryptography, TLS, OAuth2 & JWT

## 1.1 Cryptography for Payment Systems

| Algorithm | Use Case | Key Size |
|-----------|----------|:--------:|
| **AES-256-GCM** | Encrypt PII at rest (DB columns, backups) | 256-bit |
| **RSA-2048 / ECDSA P-256** | JWT signing, cert private keys | 2048/256-bit |
| **SHA-256** | Ledger hash chain, idempotency dedup | 256-bit output |
| **HMAC-SHA256** | API request signing, webhook signatures | 256-bit key |
| **HKDF** | Key derivation from master key | N/A |

### Envelope Encryption

```
Master Key (KMS) → encrypts → Data Key
Data Key → encrypts → Actual Data (PII)
Encrypted Data Key stored alongside Encrypted Data
```

**Why**: Rotate the Data Key without re-encrypting all data. Master Key never leaves KMS.

## 1.2 TLS 1.3

### Handshake (1-RTT)

```
Client                                    Server
  │── ClientHello ──────────────────────▶│
  │   (supported ciphers, key share)      │
  │◀── ServerHello ──────────────────────│
  │   (chosen cipher, cert, key share)    │
  │── Finished ─────────────────────────▶│
  │── Application Data (encrypted) ─────▶│ ← 1 round trip!
```

**vs TLS 1.2 (2-RTT)**: TLS 1.3 eliminates one round trip. For payment APIs, this saves ~50-100ms on every new connection.

### mTLS (Mutual TLS)

Both client AND server present certificates. Used for service-to-service communication. Istio service mesh provides this automatically — every pod gets a certificate, every inter-pod call is authenticated and encrypted.

## 1.3 OAuth2 & OIDC

### Authorization Code Flow + PKCE

```
User → Merchant App → /authorize → Auth Server → Login → Redirect with code
     → App exchanges code + code_verifier → /token → Access + Refresh tokens
```

**PKCE** (Proof Key for Code Exchange): Prevents authorization code interception. App creates `code_verifier` (random string) → sends SHA256 hash to Auth Server → later proves possession of original string.

### Token Types

| Token | Lifetime | Use |
|-------|:--------:|-----|
| **Access Token** | 5-15 minutes | Bearer token for API calls |
| **Refresh Token** | 30-90 days | Get new access tokens without re-login |
| **ID Token** (OIDC) | Same as access | User identity (JWT with user claims) |

## 1.4 JWT Deep Dive

```
eyJhbGciOiJSUzI1NiIsImtpZCI6ImtleS0xIn0.eyJzdWIiOiJ1MSIsInNjb3BlIjoicmVhZDp3YWxsZXRzIHdyaXRlOnBheW1lbnRzIiwiZXhwIjoxNzE2ODAwMDAwfQ.SIG
│─────── HEADER ────────│─────────────────── PAYLOAD ───────────────────────│── SIG ──│
```

### Validation Checklist

```java
JWTVerifier verifier = JWT.require(Algorithm.RSA256(publicKey, null))
    .withIssuer("auth.payment.vn")
    .withAudience("payment-service")
    .acceptLeeway(5)  // 5 second clock skew tolerance
    .build();

DecodedJWT jwt = verifier.verify(token);
// Checks: signature, exp, nbf, iss, aud
// If any check fails → JWTVerificationException
```

### Key Rotation

1. Generate new key pair (key-2). Add public key to JWKS endpoint: `{"keys": [{"kid": "key-1", ...}, {"kid": "key-2", ...}]}`
2. New JWTs signed with key-2 (include `kid: "key-2"` in header)
3. Old key-1 kept for `max_age` (5 minutes) to validate in-flight JWTs
4. After max_age, remove key-1 from JWKS → all key-1 JWTs become invalid
5. Result: zero-downtime key rotation. Stolen key expires in 5 minutes.

### JWKS Endpoint

```json
{
  "keys": [
    {
      "kid": "key-1",
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "n": "base64url-encoded-modulus",
      "e": "AQAB"
    }
  ]
}
```

## 1.5 Exercises

### Ex 1.1 — JWT Implementation
Generate an RS256-signed JWT containing: sub=userId, scope=write:payments, exp=5min. Verify with public key. Test: expired token, wrong signature, missing scope.

### Ex 1.2 — Key Rotation
Implement JWKS endpoint with 2 keys. Sign JWTs with key-1. Rotate to key-2. Verify that in-flight key-1 JWTs still validate for 5 minutes after rotation.

### Ex 1.3 — TLS Inspection
Capture a TLS 1.3 handshake with Wireshark. Identify: ClientHello, ServerHello, certificate chain. Decrypt with SSLKEYLOGFILE. Read the encrypted application data.

## 1.6 Self-Assessment

- [ ] Can explain envelope encryption and why KMS exists
- [ ] Understand TLS 1.3 1-RTT handshake vs TLS 1.2 2-RTT
- [ ] Can implement OAuth2 Authorization Code + PKCE flow
- [ ] Know every field in a JWT and how to validate each
- [ ] Can implement zero-downtime key rotation with JWKS endpoint
