package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/payment-api/settlement-service/internal/config"
)

func TestLiveness(t *testing.T) {
	cfg := &config.Config{ServiceName: "settlement-service"}
	h := NewHealth(cfg)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()

	h.Liveness(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}

	var body map[string]interface{}
	if err := json.NewDecoder(w.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if body["status"] != "UP" {
		t.Errorf("expected status UP, got %v", body["status"])
	}
	if body["service"] != "settlement-service" {
		t.Errorf("expected service settlement-service, got %v", body["service"])
	}
}

func TestReadiness(t *testing.T) {
	cfg := &config.Config{ServiceName: "settlement-service"}
	h := NewHealth(cfg)

	req := httptest.NewRequest(http.MethodGet, "/ready", nil)
	w := httptest.NewRecorder()

	h.Readiness(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}

	var body map[string]string
	if err := json.NewDecoder(w.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if body["status"] != "READY" {
		t.Errorf("expected status READY, got %v", body["status"])
	}
}
