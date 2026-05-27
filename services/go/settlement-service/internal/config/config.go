// Package config provides application configuration from environment variables.
package config

import (
	"log/slog"
	"os"
)

// Config holds all application configuration.
type Config struct {
	ServiceName   string
	Environment   string
	ServerPort    string
	DatabaseURL   string
	KafkaBrokers  string
	OtelEndpoint  string
	LogLevel      slog.Level
}

// Load reads configuration from environment variables with sensible defaults.
func Load() *Config {
	return &Config{
		ServiceName:   getEnv("OTEL_SERVICE_NAME", "settlement-service"),
		Environment:   getEnv("ENVIRONMENT", "development"),
		ServerPort:    getEnv("SERVER_PORT", "8088"),
		DatabaseURL:   getEnv("DATABASE_URL", "postgresql://payment:payment@localhost:5432/settlement_db?sslmode=disable"),
		KafkaBrokers:  getEnv("KAFKA_BROKERS", "localhost:9092"),
		OtelEndpoint:  getEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "localhost:4317"),
		LogLevel:      parseLogLevel(getEnv("LOG_LEVEL", "info")),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func parseLogLevel(level string) slog.Level {
	switch level {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
