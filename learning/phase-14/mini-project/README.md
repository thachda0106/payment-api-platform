# Mini Project — Secure Authentication Service

## Goal

Build a JWT authentication service with: token issuance, validation, key rotation via JWKS endpoint, RBAC enforcement, and token revocation — the security foundation of the payment platform.

## Run

```bash
javac AuthService.java && java AuthService
```

## Acceptance Criteria

1. Issue JWT → validate returns user ID and scopes
2. RBAC: `hasScope(token, "write:payments")` checks correctly
3. Key rotation: new tokens use new key. Old tokens valid during grace period (5 min). Old tokens INVALID after key removed.
4. Token revocation: revoked tokens fail validation
5. JWKS endpoint returns keys with kid identifiers
