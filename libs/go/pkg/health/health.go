// Package health provides Kubernetes-style probe handlers (liveness, readiness, startup)
// with a TTL-cached dependency registry.
package health

import (
	"context"
	"log/slog"
	"sync"
	"time"
)

// Checker is implemented by dependency health checks (database, kafka, redis, etc.).
type Checker interface {
	Name() string
	Check(ctx context.Context) CheckResult
}

// CheckResult is the outcome of a single dependency check.
type CheckResult struct {
	Status     DependencyStatus `json:"status"`
	LatencyMs  float64          `json:"latencyMs"`
	LastChecked time.Time       `json:"lastChecked"`
}

// DependencyStatus represents the health of a single dependency.
type DependencyStatus string

const (
	StatusOK     DependencyStatus = "ok"
	StatusDown   DependencyStatus = "down"
	StatusUnused DependencyStatus = "unused"
)

// IsHealthy returns true if the dependency is OK or unused.
func (s DependencyStatus) IsHealthy() bool {
	return s == StatusOK || s == StatusUnused
}

// Registry holds cached dependency check results with a TTL.
// Thread-safe; readiness probes read from cache (no live I/O).
type Registry struct {
	mu     sync.RWMutex
	checks map[string]*cachedCheck
	ttl    time.Duration
}

// NewRegistry creates a registry with the given TTL.
func NewRegistry(ttl time.Duration) *Registry {
	return &Registry{
		checks: make(map[string]*cachedCheck),
		ttl:    ttl,
	}
}

// Register adds a new dependency check.
func (r *Registry) Register(checker Checker) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.checks[checker.Name()] = &cachedCheck{checker: checker}
}

// Statuses returns the current status of all registered checks.
// Results are cached; fresh checks only performed when TTL expires.
func (r *Registry) Statuses(ctx context.Context) map[string]CheckResult {
	r.mu.RLock()
	defer r.mu.RUnlock()

	results := make(map[string]CheckResult, len(r.checks))
	for _, c := range r.checks {
		results[c.checker.Name()] = c.getStatus(ctx, r.ttl)
	}
	return results
}

// AllHealthy returns true if every registered check is healthy.
func (r *Registry) AllHealthy(ctx context.Context) bool {
	for _, result := range r.Statuses(ctx) {
		if !result.Status.IsHealthy() {
			return false
		}
	}
	return true
}

// cachedCheck wraps a Checker with cached result + timestamp.
type cachedCheck struct {
	checker     Checker
	lastResult  *CheckResult
	lastChecked time.Time
	mu          sync.Mutex
}

func (c *cachedCheck) getStatus(ctx context.Context, ttl time.Duration) CheckResult {
	c.mu.Lock()
	defer c.mu.Unlock()

	// Return cached result if still fresh
	if c.lastResult != nil && time.Since(c.lastChecked) < ttl {
		return *c.lastResult
	}

	// Perform fresh check
	start := time.Now()
	result := c.checker.Check(ctx)
	result.LatencyMs = float64(time.Since(start).Microseconds()) / 1000.0
	result.LastChecked = time.Now()

	c.lastResult = &result
	c.lastChecked = result.LastChecked
	return result
}

// SimpleChecker wraps a function as a Checker.
type SimpleChecker struct {
	name    string
	checkFn func(ctx context.Context) (bool, error)
}

func (s SimpleChecker) Name() string { return s.name }

func (s SimpleChecker) Check(ctx context.Context) CheckResult {
	ok, err := s.checkFn(ctx)
	if err != nil {
		slog.Warn("health check failed", "check", s.name, "error", err)
		return CheckResult{Status: StatusDown}
	}
	if ok {
		return CheckResult{Status: StatusOK}
	}
	return CheckResult{Status: StatusDown}
}

// NewSimpleChecker creates a checker from a function.
func NewSimpleChecker(name string, fn func(ctx context.Context) (bool, error)) Checker {
	return SimpleChecker{name: name, checkFn: fn}
}
