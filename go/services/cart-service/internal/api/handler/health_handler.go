package handler

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
)

// Probe reports whether one dependency is usable.
type Probe func(ctx context.Context) error

// Health serves the liveness and readiness probes.
//
// Readiness receives only dependencies that must be available to serve HTTP
// traffic. Product and Kafka have separate operational health signals: GET
// degrades when Product is unavailable and Kafka only affects the relay.
type Health struct {
	probes  map[string]Probe
	timeout time.Duration
}

// NewHealth builds the health handler. Probe names appear verbatim in the
// readiness body, so keep them stable.
func NewHealth(probes map[string]Probe, timeout time.Duration) *Health {
	if timeout <= 0 {
		timeout = 2 * time.Second
	}
	return &Health{probes: probes, timeout: timeout}
}

// Live handles GET /health/live. It confirms the process is running and
// nothing else, so a dependency outage never restarts a healthy pod.
func (h *Health) Live(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

// Ready handles GET /health/ready. It reports 503 when a dependency required
// to serve HTTP traffic is down, so traffic is withheld only when Cart cannot
// safely answer its request path.
func (h *Health) Ready(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), h.timeout)
	defer cancel()

	checks := make(map[string]string, len(h.probes))
	ready := true
	for name, probe := range h.probes {
		if err := probe(ctx); err != nil {
			checks[name] = "down"
			ready = false
			continue
		}
		checks[name] = "up"
	}

	status := http.StatusOK
	state := "ready"
	if !ready {
		status = http.StatusServiceUnavailable
		state = "not_ready"
	}
	c.JSON(status, gin.H{"status": state, "checks": checks})
}
