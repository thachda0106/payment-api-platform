// Settlement Service
// =================
// Supporting domain service for End-of-Day merchant settlement batch processing.
// Consumes payment events, aggregates merchant balances, and generates settlement files.
//
// Uses platform-libs for telemetry, health probes, and config.

package main

import (
	"context"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"github.com/payment-api/platform-libs/pkg/config"
	"github.com/payment-api/platform-libs/pkg/health"
	"github.com/payment-api/platform-libs/pkg/telemetry"
)

func main() {
	// 1. Load config (fails fast on missing required values)
	cfg := config.Load()

	// 2. Structured JSON logging
	logLevel := slogLevel(cfg.Logging.Level)
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: logLevel}))
	slog.SetDefault(logger)

	// 3. OTel tracing
	ctx := context.Background()
	tp, err := telemetry.Setup(ctx, cfg.Otel.ExporterEndpoint, cfg.Otel.ServiceName, cfg.Otel.ServiceVersion, "local")
	if err != nil {
		log.Fatalf("Failed to initialize OTel: %v", err)
	}
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := tp.Shutdown(shutdownCtx); err != nil {
			slog.Error("OTel shutdown error", "error", err)
		}
	}()

	// 4. Health probe registry (with TTL cache)
	registry := health.NewRegistry(5 * time.Second)
	// Register dependency checks if available
	if cfg.Database != nil {
		// Database check will be registered when DB pool is created (Phase 7)
		slog.Info("Database configured, health check will be registered when pool is created",
			"url", cfg.Database.URL)
	}
	if cfg.Kafka != nil {
		// Kafka check will be registered when consumer/producer is created (Phase 7)
		slog.Info("Kafka configured, health check will be registered when client is created",
			"brokers", cfg.Kafka.BootstrapServers)
	}

	// 5. HTTP router
	r := chi.NewRouter()
	r.Use(middleware.RequestID)           // generates requestId if missing
	r.Use(telemetry.HTTPMiddleware())     // OTel span per request
	r.Use(middleware.RealIP)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.Timeout(30 * time.Second))

	// Probe endpoints
	startTime := time.Now()
	r.Get("/liveness", health.MakeLivenessHandler(cfg.Otel.ServiceName, cfg.Otel.ServiceVersion, startTime))
	r.Get("/readiness", health.MakeReadinessHandler(cfg.Otel.ServiceName, cfg.Otel.ServiceVersion, startTime, registry))
	r.Get("/startup", health.MakeStartupHandler(cfg.Otel.ServiceName, cfg.Otel.ServiceVersion, startTime, registry))

	// Backward compat redirects
	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/liveness", http.StatusMovedPermanently)
	})
	r.Get("/ready", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/readiness", http.StatusMovedPermanently)
	})

	// 6. HTTP Server
	srv := &http.Server{
		Addr:         cfg.Server.Addr(),
		Handler:      r,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Graceful shutdown
	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
		<-sigCh

		slog.Info("shutting down server gracefully...")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		if err := srv.Shutdown(shutdownCtx); err != nil {
			slog.Error("server forced to shutdown", "error", err)
		}
		if tpErr := tp.Shutdown(shutdownCtx); tpErr != nil {
			slog.Error("tracer shutdown error", "error", tpErr)
		}
	}()

	slog.Info("Settlement Service starting",
		"port", cfg.Server.Port,
		"otel_endpoint", cfg.Otel.ExporterEndpoint,
	)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		slog.Error("server failed", "error", err)
		os.Exit(1)
	}

	slog.Info("server stopped")
}

func slogLevel(level string) slog.Level {
	switch level {
	case "debug": return slog.LevelDebug
	case "warn":  return slog.LevelWarn
	case "error": return slog.LevelError
	default:      return slog.LevelInfo
	}
}
