package handler

import (
	"context"
	"net/http"
	"time"
)

// ReadinessCheck reports whether a dependency can currently serve verification.
type ReadinessCheck func(ctx context.Context) bool

// HealthHandler serves liveness and readiness. Readiness requires Redis and at
// least one usable JWKS key: without either, every protected request would
// fail closed, so the instance should not receive traffic.
type HealthHandler struct {
	checks  []ReadinessCheck
	timeout time.Duration
}

func NewHealthHandler(timeout time.Duration, checks ...ReadinessCheck) *HealthHandler {
	return &HealthHandler{checks: checks, timeout: timeout}
}

func (h *HealthHandler) Live(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"UP"}`))
}

func (h *HealthHandler) Ready(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), h.timeout)
	defer cancel()
	for _, check := range h.checks {
		if !check(ctx) {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"status":"DOWN"}`))
			return
		}
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"UP"}`))
}
