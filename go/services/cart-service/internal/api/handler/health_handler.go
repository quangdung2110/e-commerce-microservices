package handler

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"time"

	goredis "github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

// HealthHandler handles health check endpoints
type HealthHandler struct {
	redisClient *goredis.Client
	db          *sql.DB
	logger      *zap.Logger
}

// NewHealthHandler creates a new HealthHandler
func NewHealthHandler(
	redisClient *goredis.Client,
	db *sql.DB,
	logger *zap.Logger,
) *HealthHandler {
	return &HealthHandler{
		redisClient: redisClient,
		db:          db,
		logger:      logger,
	}
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
	if h.redisClient != nil {
		if err := h.checkRedis(ctx); err != nil {
			status.Status = "degraded"
			status.Checks["redis"] = Check{
				Status:  "unhealthy",
				Message: err.Error(),
			}
		} else {
			status.Checks["redis"] = Check{Status: "healthy"}
		}
	} else {
		status.Checks["redis"] = Check{Status: "not_configured"}
	}

	// Check PostgreSQL
	if h.db != nil {
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

	httpStatus := http.StatusOK
	if status.Status != "ok" {
		httpStatus = http.StatusServiceUnavailable
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(httpStatus)
	if err := json.NewEncoder(w).Encode(status); err != nil {
		h.logger.Error("Failed to encode health response", zap.Error(err))
	}
}

func (h *HealthHandler) checkRedis(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	return h.redisClient.Ping(ctx).Err()
}

func (h *HealthHandler) checkPostgres(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	return h.db.PingContext(ctx)
}
