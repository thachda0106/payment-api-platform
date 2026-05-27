// Package handler provides HTTP handlers for the settlement service.
package handler

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/payment-api/settlement-service/internal/config"
)

// Health provides liveness and readiness endpoints.
type Health struct {
	cfg     *config.Config
	started time.Time
}

// NewHealth creates a new Health handler.
func NewHealth(cfg *config.Config) *Health {
	return &Health{
		cfg:     cfg,
		started: time.Now(),
	}
}

// Liveness returns 200 OK if the service is alive.
func (h *Health) Liveness(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "UP",
		"service": h.cfg.ServiceName,
		"version": "0.1.0",
		"uptime":  time.Since(h.started).String(),
	})
}

// Readiness returns 200 OK if the service is ready to accept traffic.
func (h *Health) Readiness(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{
		"status":  "READY",
		"service": h.cfg.ServiceName,
	})
}
