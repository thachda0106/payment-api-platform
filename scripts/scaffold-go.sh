#!/bin/bash
# scaffold-go.sh — Generate new Go Chi service
# Usage: bash scripts/scaffold-go.sh <service-name>

set -euo pipefail
NAME="${1:-}"
[ -z "$NAME" ] && { echo "Usage: scaffold-go.sh <service-name>"; exit 1; }

SERVICE_DIR="services/go/$NAME"
mkdir -p "$SERVICE_DIR/cmd/server" "$SERVICE_DIR/test" "$SERVICE_DIR/docs/adr"

# go.mod
cat > "$SERVICE_DIR/go.mod" <<EOF
module github.com/payment-api/$NAME
go 1.22
require (
    github.com/go-chi/chi/v5 v5.1.0
    github.com/payment-api/platform-libs v0.0.0
)
replace github.com/payment-api/platform-libs => ../../libs/go
EOF

# main.go
cat > "$SERVICE_DIR/cmd/server/main.go" <<'GOEOF'
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"log/slog"

	"github.com/payment-api/platform-libs/pkg/config"
	"github.com/payment-api/platform-libs/pkg/health"
	"github.com/payment-api/platform-libs/pkg/telemetry"
)

func main() {
	cfg := config.Load()

	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(logger)

	ctx := context.Background()
	tp, err := telemetry.Setup(ctx, cfg.Otel.ExporterEndpoint, cfg.Otel.ServiceName, cfg.Otel.ServiceVersion, "local")
	if err != nil { log.Fatalf("OTel setup failed: %v", err) }
	defer func() {
		shutdownCtx, _ := context.WithTimeout(context.Background(), 10*time.Second)
		tp.Shutdown(shutdownCtx)
	}()

	registry := health.NewRegistry(5 * time.Second)

	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(telemetry.HTTPMiddleware())
	r.Use(middleware.Recoverer)
	r.Use(middleware.Timeout(30 * time.Second))

	startTime := time.Now()
	r.Get("/liveness", health.MakeLivenessHandler(cfg.Otel.ServiceName, cfg.Otel.ServiceVersion, startTime))
	r.Get("/readiness", health.MakeReadinessHandler(cfg.Otel.ServiceName, cfg.Otel.ServiceVersion, startTime, registry))
	r.Get("/startup", health.MakeStartupHandler(cfg.Otel.ServiceName, cfg.Otel.ServiceVersion, startTime, registry))
	r.Get("/health", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/liveness", 301) })
	r.Get("/ready", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/readiness", 301) })

	srv := &http.Server{Addr: cfg.Server.Addr(), Handler: r}

	go func() {
		ch := make(chan os.Signal, 1)
		signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
		<-ch
		shutdownCtx, _ := context.WithTimeout(context.Background(), 30*time.Second)
		srv.Shutdown(shutdownCtx)
	}()

	slog.Info("Starting", "port", cfg.Server.Port)
	if err := srv.ListenAndServe(); err != http.ErrServerClosed { log.Fatal(err) }
}
GOEOF

# ADR
cat > "$SERVICE_DIR/docs/adr/ADR-0001-${NAME}-architecture.md" <<EOF
# ADR-0001: Architecture — $NAME
## Status: Accepted
## Decision
- Language: Go 1.22, Framework: Chi
- Tracing: OTel SDK → gRPC to otel-collector → Jaeger
- Health: /liveness, /readiness, /startup (cached, TTL 5s)
## Consequences
- All probe endpoints from platform-libs
- Config validated at startup
EOF

echo "✅ Go service scaffolded: $SERVICE_DIR"
echo "   cd $SERVICE_DIR && go run ./cmd/server"
