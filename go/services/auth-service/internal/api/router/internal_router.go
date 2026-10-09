package router

import (
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/middleware"
	"github.com/ecommerce/auth-service/internal/common/config"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// InternalDeps is everything the cluster-internal listener needs.
type InternalDeps struct {
	Config   *config.Config
	Logger   *zap.Logger
	Limiter  port.RateLimiter
	Delivery *handler.DeliveryHandler
	Contact  *handler.ContactHandler
	Health   *handler.HealthHandler
}

// NewInternal builds the internal HTTP engine that serves the notification
// delivery and notification contact APIs.
//
// This engine is bound to its own port and must never be published through the
// gateway: its responses carry the recipient address and, for verification and
// reset templates, the secret itself. What separates it from a recipient oracle
// is the mesh AuthorizationPolicy that only lets the notification-service
// principal reach it (docs/mesh/runtime-contract.md section 5.2). The
// X-Service-Identity allowlist below is defence in depth, not the enforcement
// point (docs/auth/interfaces.md section 3).
func NewInternal(deps InternalDeps) *gin.Engine {
	engine := newEngine(deps.Config, deps.Logger)

	engine.GET("/health/live", deps.Health.Live)

	limit := rule(deps.Config.Security.RateLimit.InternalPerResource)

	group := engine.Group("/internal/v1/auth")
	group.Use(middleware.ServiceIdentity(deps.Config.Internal.AllowedCallers))

	group.POST("/notification-deliveries/:delivery_id/content",
		middleware.RateLimitByServiceResource(deps.Limiter, "internal-delivery", "delivery_id", limit),
		deps.Delivery.Resolve)
	group.GET("/users/:user_id/notification-contact",
		middleware.RateLimitByServiceResource(deps.Limiter, "internal-contact", "user_id", limit),
		deps.Contact.Resolve)

	return engine
}
