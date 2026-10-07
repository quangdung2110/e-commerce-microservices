package handler

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/dto/response"
	"github.com/ecommerce/auth-service/internal/common/logging"
)

// probe is one readiness check.
type probe struct {
	name  string
	check func(context.Context) error
}

// HealthHandler serves the liveness, readiness and startup probes.
//
// Readiness covers the dependencies without which authentication cannot be done
// safely: the credential store and the security state store. A failing probe
// reports which check failed to the log, never to the response body.
type HealthHandler struct {
	probes  []probe
	timeout time.Duration
}

// NewHealthHandler wires the probes.
func NewHealthHandler(timeout time.Duration) *HealthHandler {
	return &HealthHandler{timeout: timeout}
}

// AddProbe registers one readiness dependency.
func (h *HealthHandler) AddProbe(name string, check func(context.Context) error) {
	h.probes = append(h.probes, probe{name: name, check: check})
}

// Live handles GET /health/live: the process is running.
func (h *HealthHandler) Live(c *gin.Context) {
	c.JSON(http.StatusOK, response.Health{Status: "UP"})
}

// Ready handles GET /health/ready: the dependencies answer.
func (h *HealthHandler) Ready(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), h.timeout)
	defer cancel()

	for _, p := range h.probes {
		if err := p.check(ctx); err != nil {
			logging.FromContext(c.Request.Context()).Warn("readiness probe failed",
				zap.String("dependency", p.name),
				zap.Error(err),
			)
			c.JSON(http.StatusServiceUnavailable, response.Health{Status: "DOWN"})
			return
		}
	}
	c.JSON(http.StatusOK, response.Health{Status: "UP"})
}

// Startup handles GET /health/startup and runs the same checks as readiness.
func (h *HealthHandler) Startup(c *gin.Context) {
	h.Ready(c)
}
