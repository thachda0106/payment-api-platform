package health

import (
	"encoding/json"
	"net/http"
	"time"
)

// ProbeResponse is the standardized response for all probe endpoints.
type ProbeResponse struct {
	Status    string                 `json:"status"`
	Service   string                 `json:"service"`
	Version   string                 `json:"version"`
	Timestamp time.Time              `json:"timestamp"`
	Uptime    float64                `json:"uptime"`
	Checks    map[string]CheckResult `json:"checks,omitempty"`
}

// MakeLivenessHandler returns an http.HandlerFunc for GET /liveness.
// Always returns 200 — no I/O, no dependency checks.
func MakeLivenessHandler(serviceName, version string, startTime time.Time) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		resp := ProbeResponse{
			Status:    "ok",
			Service:   serviceName,
			Version:   version,
			Timestamp: time.Now().UTC(),
			Uptime:    time.Since(startTime).Seconds(),
		}
		writeJSON(w, http.StatusOK, resp)
	}
}

// MakeReadinessHandler returns an http.HandlerFunc for GET /readiness.
// Returns 200 if all deps are healthy, 503 otherwise.
// Uses cached registry (TTL) to avoid live I/O on every probe.
func MakeReadinessHandler(serviceName, version string, startTime time.Time, registry *Registry) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		statuses := registry.Statuses(r.Context())
		allHealthy := true
		for _, s := range statuses {
			if !s.Status.IsHealthy() {
				allHealthy = false
				break
			}
		}

		status := "ok"
		httpStatus := http.StatusOK
		if !allHealthy {
			status = "not_ready"
			httpStatus = http.StatusServiceUnavailable
		}

		resp := ProbeResponse{
			Status:    status,
			Service:   serviceName,
			Version:   version,
			Timestamp: time.Now().UTC(),
			Uptime:    time.Since(startTime).Seconds(),
			Checks:    statuses,
		}
		writeJSON(w, httpStatus, resp)
	}
}

// MakeStartupHandler returns an http.HandlerFunc for GET /startup.
// Returns 503 until all deps are healthy at least once, then 200 permanently.
func MakeStartupHandler(serviceName, version string, startTime time.Time, registry *Registry) http.HandlerFunc {
	started := false
	return func(w http.ResponseWriter, r *http.Request) {
		statuses := registry.Statuses(r.Context())

		allHealthy := true
		for _, s := range statuses {
			if !s.Status.IsHealthy() {
				allHealthy = false
				break
			}
		}
		if allHealthy {
			started = true
		}

		status := "ok"
		httpStatus := http.StatusOK
		if !started {
			status = "not_ready"
			httpStatus = http.StatusServiceUnavailable
		}

		resp := ProbeResponse{
			Status:    status,
			Service:   serviceName,
			Version:   version,
			Timestamp: time.Now().UTC(),
			Uptime:    time.Since(startTime).Seconds(),
			Checks:    statuses,
		}
		writeJSON(w, httpStatus, resp)
	}
}

func writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}
