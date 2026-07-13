# ============================================================================
# Dockerfile.go — Go multi-stage build (context: repo root)
# ============================================================================
# Usage:
#   docker build -f docker/Dockerfile.go --build-arg SERVICE_PATH=services/go/settlement-service -t payment-api/settlement-service:latest .
#   docker compose build settlement-service  (handled by docker-compose.yml)
#
# The repo layout is preserved inside the image so the module's
# `replace github.com/payment-api/platform-libs => ../../../libs/go` resolves.
# ============================================================================

# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM golang:1.25-alpine AS builder
ARG SERVICE_PATH
RUN apk add --no-cache git ca-certificates tzdata
WORKDIR /repo

# Shared platform lib (for the `replace` directive)
COPY libs/go /repo/libs/go

# Service module (path preserved so the replace directive resolves)
COPY ${SERVICE_PATH} /repo/${SERVICE_PATH}
WORKDIR /repo/${SERVICE_PATH}
RUN go mod download
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-s -w" -trimpath -o /server ./cmd/server

# ─── Stage 2: Runtime (scratch) ─────────────────────────────────────────────
FROM scratch
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=builder /server /server

USER 65534:65534
EXPOSE 8088

ENTRYPOINT ["/server"]
