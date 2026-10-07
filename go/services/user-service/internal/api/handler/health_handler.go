package handler

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/api/dto/response"
	"github.com/ecommerce/user-service/internal/common/logging"
)

// probe is one dependency check.
type probe struct {
	name  string
	check func(context.Context) error
	// required marks a dependency the API cannot serve without. An optional one
	// is reported as degraded instead, because a cache outage must not take a
	// healthy API out of rotation (docs/user/operations.md §1).
	required bool
}

// HealthHandler serves the liveness, readiness and startup probes.
type HealthHandler struct {
	probes  []probe
	timeout time.Duration
}

// NewHealthHandler wires the probes.
func NewHealthHandler(timeout time.Duration) *HealthHandler {
	return &HealthHandler{timeout: timeout}
}

// AddProbe registers a dependency the service cannot work without.
func (h *HealthHandler) AddProbe(name string, check func(context.Context) error) {
	h.probes = append(h.probes, probe{name: name, check: check, required: true})
}

// AddOptionalProbe registers a dependency whose failure only degrades the
// service.
func (h *HealthHandler) AddOptionalProbe(name string, check func(context.Context) error) {
	h.probes = append(h.probes, probe{name: name, check: check})
}

// Live handles GET /health/live: the process is running.
func (h *HealthHandler) Live(c *gin.Context) {
	c.JSON(http.StatusOK, response.Health{Status: "UP"})
}

// Ready handles GET /health/ready. It fails only on a required dependency; a
// failing optional one is listed as degraded.
func (h *HealthHandler) Ready(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), h.timeout)
	defer cancel()

	var degraded []string
	for _, p := range h.probes {
		if err := p.check(ctx); err != nil {
			logging.FromContext(c.Request.Context()).Warn("readiness probe failed",
				zap.String("dependency", p.name),
				zap.Bool("required", p.required),
				zap.Error(err),
			)
			if p.required {
				c.JSON(http.StatusServiceUnavailable, response.Health{Status: "DOWN"})
				return
			}
			degraded = append(degraded, p.name)
		}
	}
	c.JSON(http.StatusOK, response.Health{Status: "UP", Degraded: degraded})
}

// Startup handles GET /health/startup and runs the same checks as readiness.
func (h *HealthHandler) Startup(c *gin.Context) {
	h.Ready(c)
}
