package router

import (
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api/handler"
	"github.com/ecommerce/notification-service/internal/api/middleware"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// InternalDeps is everything the cluster-internal listener needs.
type InternalDeps struct {
	Config  *config.Config
	Logger  *zap.Logger
	Clock   clock.Clock
	Metrics *telemetry.Metrics
	// OpsRepo backs idempotency records and the audit trail.
	OpsRepo port.OpsRepository
	Status  *handler.StatusHandler
	Ops     *handler.OpsHandler
	Health  *handler.HealthHandler
}

// NewInternal builds the internal engine (port 8081): health, metrics, the
// status API for support tools and the ops API for SRE. It is never
// published through the gateway. The mesh decides who may reach it (mTLS and
// AuthorizationPolicy per principal); the application only checks the
// X-Service-Identity header against the allowlist of each group as defence in
// depth (11-privacy.md §5, decisions.md D-21).
func NewInternal(deps InternalDeps) *gin.Engine {
	engine := newEngine(deps.Config, deps.Logger, nil)
	if deps.Clock == nil {
		deps.Clock = clock.System{}
	}

	engine.GET("/livez", deps.Health.Live)
	engine.GET("/readyz", deps.Health.Ready)

	metricsPath := deps.Config.Telemetry.MetricsPath
	if metricsPath == "" {
		metricsPath = "/metrics"
	}
	if deps.Metrics != nil {
		engine.GET(metricsPath, gin.WrapH(promhttp.HandlerFor(deps.Metrics.Registry, promhttp.HandlerOpts{})))
	}

	api := engine.Group("/internal/v1/notifications")

	status := api.Group("", middleware.ServiceIdentity(deps.Config.Internal.StatusPrincipals))
	status.GET("/requests/:request_id", middleware.Operation("getNotificationRequest"), deps.Status.GetRequest)
	status.GET("/deliveries", middleware.Operation("listDeliveriesBySubject"), deps.Status.ListDeliveries)

	ops := api.Group("/ops", middleware.ServiceIdentity(deps.Config.Internal.OpsPrincipals))
	audit := middleware.Audit(deps.OpsRepo, deps.Clock, deps.Metrics, deps.Logger)
	idempotent := middleware.Idempotency(deps.OpsRepo, deps.Clock)

	// Reads are audited but need no Idempotency-Key.
	ops.GET("/lanes/:lane", middleware.Operation("getLaneState"), audit, deps.Ops.GetLaneState)
	// Every switch: audit wraps idempotency so a replayed call is audited too.
	ops.PUT("/lanes/:lane", middleware.Operation("setLaneState"), audit, idempotent, deps.Ops.SetLaneState)
	ops.POST("/deliveries/:delivery_id/redrive", middleware.Operation("redriveDelivery"), audit, idempotent, deps.Ops.RedriveDelivery)
	ops.POST("/dlq/redrive", middleware.Operation("redriveDlq"), audit, idempotent, deps.Ops.RedriveDLQ)
	ops.POST("/suppressions", middleware.Operation("addManualSuppression"), audit, idempotent, deps.Ops.AddSuppression)
	// DELETE carries no body and is idempotent by nature; the contract
	// declares no Idempotency-Key for it.
	ops.DELETE("/suppressions/:suppression_id", middleware.Operation("removeSuppression"), audit, deps.Ops.RemoveSuppression)
	ops.PUT("/replay-mode", middleware.Operation("setReplayMode"), audit, idempotent, deps.Ops.SetReplayMode)

	return engine
}
