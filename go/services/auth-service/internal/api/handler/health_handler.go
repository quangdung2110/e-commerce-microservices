package handler

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
)

type HealthHandler struct {
	dbPingFn func(ctx context.Context) error
	redisPingFn func(ctx context.Context) error
}

type HealthResponse struct {
	Status    string            `json:"status"`
	Timestamp time.Time        `json:"timestamp"`
	Checks    map[string]string `json:"checks,omitempty"`
}

func NewHealthHandler(
	dbPingFn func(ctx context.Context) error,
	redisPingFn func(ctx context.Context) error,
) *HealthHandler {
	return &HealthHandler{
		dbPingFn:    dbPingFn,
		redisPingFn: redisPingFn,
	}
}

func (h *HealthHandler) Health(c *gin.Context) {
	c.JSON(http.StatusOK, HealthResponse{
		Status:    "ok",
		Timestamp: time.Now().UTC(),
	})
}

func (h *HealthHandler) Ready(c *gin.Context) {
	checks := make(map[string]string)
	allHealthy := true

	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()

	if h.dbPingFn != nil {
		if err := h.dbPingFn(ctx); err != nil {
			checks["database"] = "unhealthy: " + err.Error()
			allHealthy = false
		} else {
			checks["database"] = "healthy"
		}
	}

	if h.redisPingFn != nil {
		if err := h.redisPingFn(ctx); err != nil {
			checks["redis"] = "unhealthy: " + err.Error()
			allHealthy = false
		} else {
			checks["redis"] = "healthy"
		}
	}

	status := "ok"
	statusCode := http.StatusOK
	if !allHealthy {
		status = "degraded"
		statusCode = http.StatusServiceUnavailable
	}

	c.JSON(statusCode, HealthResponse{
		Status:    status,
		Timestamp: time.Now().UTC(),
		Checks:    checks,
	})
}

func (h *HealthHandler) Live(c *gin.Context) {
	c.JSON(http.StatusOK, HealthResponse{
		Status:    "ok",
		Timestamp: time.Now().UTC(),
	})
}
