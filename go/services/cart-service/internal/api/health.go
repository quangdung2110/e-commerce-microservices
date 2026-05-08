package api

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"go.uber.org/zap"
)

// HealthHandler handles health check endpoints
type HealthHandler struct {
	deps *Dependencies
}

// NewHealthHandler creates a new HealthHandler
func NewHealthHandler(deps *Dependencies) *HealthHandler {
	return &HealthHandler{deps: deps}
}

// HealthStatus represents the health status response
type HealthStatus struct {
	Status string            `json:"status"`
	Checks map[string]Check `json:"checks"`
}

// Check represents a single health check
type Check struct {
	Status  string `json:"status"`
	Message string `json:"message,omitempty"`
}

// Live handles GET /health/live - liveness probe
func (h *HealthHandler) Live(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status":"ok"}`))
}

// Ready handles GET /health/ready - readiness probe
func (h *HealthHandler) Ready(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	status := HealthStatus{
		Status: "ok",
		Checks: make(map[string]Check),
	}

	// Check Redis
	if err := h.checkRedis(ctx); err != nil {
		status.Status = "degraded"
		status.Checks["redis"] = Check{
			Status:  "unhealthy",
			Message: err.Error(),
		}
	} else {
		status.Checks["redis"] = Check{Status: "healthy"}
	}

	// Check PostgreSQL (if configured)
	if h.deps.DB != nil {
		if err := h.checkPostgres(ctx); err != nil {
			status.Status = "degraded"
			status.Checks["postgres"] = Check{
				Status:  "unhealthy",
				Message: err.Error(),
			}
		} else {
			status.Checks["postgres"] = Check{Status: "healthy"}
		}
	} else {
		status.Checks["postgres"] = Check{Status: "not_configured"}
	}

	// Check Kafka (placeholder - would need actual Kafka client)
	status.Checks["kafka"] = Check{Status: "not_implemented"}

	httpStatus := http.StatusOK
	if status.Status != "ok" {
		httpStatus = http.StatusServiceUnavailable
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(httpStatus)
	if err := json.NewEncoder(w).Encode(status); err != nil {
		h.deps.Logger.Error("Failed to encode health response", zap.Error(err))
	}
}

func (h *HealthHandler) checkRedis(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()

	if err := h.deps.RedisClient.Ping(ctx).Err(); err != nil {
		return err
	}
	return nil
}

func (h *HealthHandler) checkPostgres(ctx context.Context) error {
	// Placeholder - would need actual PostgreSQL connection check
	// For now, return nil since DB is not yet fully implemented
	return nil
}
