package handler

import (
	"context"
	"net/http"
	"sort"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api/dto/response"
	"github.com/ecommerce/notification-service/internal/common/logging"
)

// ReadinessCheck is one dependency probe, keyed by name (postgres, redis,
// kafka, templates, sqs — 14-observability.md §8). The set of checks depends
// on the role the process runs, so main wires them.
type ReadinessCheck func(ctx context.Context) error

// HealthHandler serves /livez and /readyz.
type HealthHandler struct {
	checks  map[string]ReadinessCheck
	timeout time.Duration
}

// NewHealthHandler wires the probes; timeout bounds the whole readiness call.
func NewHealthHandler(checks map[string]ReadinessCheck, timeout time.Duration) *HealthHandler {
	if checks == nil {
		checks = map[string]ReadinessCheck{}
	}
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	return &HealthHandler{checks: checks, timeout: timeout}
}

// Live handles GET /livez: the process is running, nothing else.
func (h *HealthHandler) Live(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, response.Health{Status: "UP"})
}

// Ready handles GET /readyz: 200 when every check passes, otherwise 503
// listing the failing names so the on-call engineer sees which dependency is
// down without reading logs.
func (h *HealthHandler) Ready(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), h.timeout)
	defer cancel()

	var failed []string
	for name, check := range h.checks {
		if err := check(ctx); err != nil {
			logging.FromContext(c.Request.Context()).Warn("readiness check failed",
				zap.String("dependency", name), zap.Error(err))
			failed = append(failed, name)
		}
	}
	c.Header("Cache-Control", "no-store")
	if len(failed) > 0 {
		sort.Strings(failed)
		c.JSON(http.StatusServiceUnavailable, response.Health{Status: "DOWN", Failed: failed})
		return
	}
	c.JSON(http.StatusOK, response.Health{Status: "UP"})
}
