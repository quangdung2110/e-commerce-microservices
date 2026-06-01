package handler

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
)

// HealthChecker defines the interface for health checks.
type HealthChecker interface {
	Ping(ctx context.Context) error
}

// HealthHandler handles health check endpoints.
type HealthHandler struct {
	redisHealth HealthChecker
	pgHealth    HealthChecker
}

// NewHealthHandler creates a new health handler.
func NewHealthHandler(redisHealth, pgHealth HealthChecker) *HealthHandler {
	return &HealthHandler{
		redisHealth: redisHealth,
		pgHealth:    pgHealth,
	}
}

// HealthResponse represents the health check response.
type HealthResponse struct {
	Status    string            `json:"status"`
	Timestamp string            `json:"timestamp"`
	Checks    map[string]string `json:"checks"`
}

// Liveness handles GET /healthz
func (h *HealthHandler) Liveness(c *gin.Context) {
	c.JSON(http.StatusOK, HealthResponse{
		Status:    "ok",
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Checks:    map[string]string{},
	})
}

// Readiness handles GET /readyz
func (h *HealthHandler) Readiness(c *gin.Context) {
	checks := make(map[string]string)
	allHealthy := true

	if h.redisHealth != nil {
		if err := h.redisHealth.Ping(c.Request.Context()); err != nil {
			checks["redis"] = "unhealthy: " + err.Error()
			allHealthy = false
		} else {
			checks["redis"] = "ok"
		}
	}

	if h.pgHealth != nil {
		if err := h.pgHealth.Ping(c.Request.Context()); err != nil {
			checks["postgres"] = "unhealthy: " + err.Error()
			allHealthy = false
		} else {
			checks["postgres"] = "ok"
		}
	}

	resp := HealthResponse{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Checks:    checks,
	}

	if allHealthy {
		resp.Status = "ok"
		c.JSON(http.StatusOK, resp)
	} else {
		resp.Status = "unhealthy"
		c.JSON(http.StatusServiceUnavailable, resp)
	}
}

// Metrics handles GET /metrics
func (h *HealthHandler) Metrics(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"message": "Metrics endpoint — integrate with prometheus/client_golang",
	})
}
