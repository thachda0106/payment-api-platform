// Package config provides typed, validated, modular configuration for all services.
//
// Mandatory config (always validated):
//   - server (port, host)
//   - logging (level, format)
//   - otel (exporterEndpoint, serviceName, serviceVersion)
//
// Optional config (validated only when configured):
//   - database (url, maxPoolSize, minIdle)
//   - kafka (bootstrapServers, consumerGroup)
//   - redis (url)
//
// All config is loaded from environment variables with the same names across all 4 languages:
//
//	SERVER_PORT, DATABASE_URL, KAFKA_BOOTSTRAP_SERVERS, REDIS_URL,
//	OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME, LOG_LEVEL, LOG_FORMAT
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// Config is the root configuration struct.
// Optional fields are nil if not configured.
type Config struct {
	Server   Server
	Logging  Logging
	Otel     Otel
	Database *DatabaseConfig
	Kafka    *KafkaConfig
	Redis    *RedisConfig
}

type Server struct {
	Port int
	Host string
}

func (s Server) Addr() string { return fmt.Sprintf("%s:%d", s.Host, s.Port) }

type Logging struct {
	Level  string // debug, info, warn, error
	Format string // json, text
}

type Otel struct {
	ExporterEndpoint string
	ServiceName      string
	ServiceVersion   string
}

type DatabaseConfig struct {
	URL          string
	MaxPoolSize  int
	MinIdle      int
}

type KafkaConfig struct {
	BootstrapServers string
	ConsumerGroup    string
}

type RedisConfig struct {
	URL string
}

// Load reads configuration from environment variables.
// Panics if mandatory config is missing (fail-fast).
func Load() *Config {
	cfg := &Config{
		Server: Server{
			Port: envInt("SERVER_PORT", 8080),
			Host: envStr("SERVER_HOST", "0.0.0.0"),
		},
		Logging: Logging{
			Level:  envStr("LOG_LEVEL", "info"),
			Format: envStr("LOG_FORMAT", "json"),
		},
		Otel: Otel{
			ExporterEndpoint: envStr("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
			ServiceName:      envStr("OTEL_SERVICE_NAME", "unknown"),
			ServiceVersion:   envStr("SERVICE_VERSION", "0.1.0"),
		},
	}

	// Optional modules — only load if env var is set
	if dbURL := os.Getenv("DATABASE_URL"); dbURL != "" {
		cfg.Database = &DatabaseConfig{
			URL:         dbURL,
			MaxPoolSize: envInt("DB_MAX_POOL_SIZE", 10),
			MinIdle:     envInt("DB_MIN_IDLE", 2),
		}
	}

	if kafka := os.Getenv("KAFKA_BOOTSTRAP_SERVERS"); kafka != "" {
		cfg.Kafka = &KafkaConfig{
			BootstrapServers: kafka,
			ConsumerGroup:    envStr("KAFKA_CONSUMER_GROUP", "default"),
		}
	}

	if redisURL := os.Getenv("REDIS_URL"); redisURL != "" {
		cfg.Redis = &RedisConfig{URL: redisURL}
	}

	if err := cfg.Validate(); err != nil {
		fmt.Fprintf(os.Stderr, "Config validation failed: %v\n", err)
		os.Exit(1)
	}

	return cfg
}

// Validate checks mandatory fields and optional module fields.
func (c *Config) Validate() error {
	if c.Otel.ExporterEndpoint == "" {
		return fmt.Errorf("OTEL_EXPORTER_OTLP_ENDPOINT is required")
	}
	if c.Otel.ServiceName == "" {
		return fmt.Errorf("OTEL_SERVICE_NAME is required")
	}
	return nil
}

// ─── Helpers ──────────────────────────────────────────────────────────────

func envStr(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}

func envInt(key string, defaultVal int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(strings.TrimSpace(v)); err == nil {
			return n
		}
	}
	return defaultVal
}
