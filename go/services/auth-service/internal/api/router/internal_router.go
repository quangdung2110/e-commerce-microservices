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
// reset templates, the secret itself.
func NewInternal(deps InternalDeps) *gin.Engine {
	engine := newEngine(deps.Config, deps.Logger)

	engine.GET("/health/live", deps.Health.Live)

	group := engine.Group("/internal/v1/auth")
	if deps.Config.Internal.MutualTLSEnabled() {
		group.Use(middleware.ServiceIdentity(deps.Config.Internal.AllowedClientCN))
	} else {
		// Without certificates the listener can only be protected by the network
		// itself, which is acceptable for local development and refused by the
		// configuration validator in production.
		deps.Logger.Warn("the internal delivery API is running without mTLS")
	}

	group.Use(middleware.RateLimit(deps.Limiter, "internal-delivery:ip",
		rule(deps.Config.Security.RateLimit.InternalPerClient)))

	group.POST("/notification-deliveries/:delivery_id/content", deps.Delivery.Resolve)
	group.GET("/users/:user_id/notification-contact", deps.Contact.Resolve)

	return engine
}
