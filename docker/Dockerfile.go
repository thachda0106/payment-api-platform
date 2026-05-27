# ============================================================================
# Dockerfile.go — Go multi-stage build (scratch/minimal)
# ============================================================================
# Build:  docker build -f docker/Dockerfile.go -t payment-api/{svc}:latest services/go/{svc}
# Run:    docker run -p 8080:8080 payment-api/{svc}:latest
# ============================================================================

# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM golang:1.22-alpine AS builder

# Install build dependencies
RUN apk add --no-cache git ca-certificates tzdata

WORKDIR /app

# Copy module files first for dependency caching
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build static binary with optimizations
# -ldflags="-s -w": strip debug info, reduce binary size
# -trimpath: remove filesystem paths from binary
# CGO_ENABLED=0: static linking, no libc dependency
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-s -w" -trimpath \
    -o /app/server \
    ./cmd/server

# ─── Stage 2: Runtime (distroless static) ──────────────────────────────────
# Using scratch for minimal attack surface. Only ca-certificates for TLS.
FROM scratch

# Copy certificates for HTTPS outbound calls
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /usr/share/zoneinfo /usr/share/zoneinfo

# Copy the static binary
COPY --from=builder /app/server /server

# Run as non-root (numeric user)
USER 65534:65534

EXPOSE 8080

# No HEALTHCHECK in scratch (no shell). Use Kubernetes liveness/readiness probes instead.
# For local dev, use `docker run --health-cmd` or rely on orchestrator probes.

ENTRYPOINT ["/server"]
