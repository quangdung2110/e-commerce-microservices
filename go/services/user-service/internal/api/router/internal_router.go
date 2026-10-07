package router

import (
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/api/handler"
	"github.com/ecommerce/user-service/internal/api/middleware"
	"github.com/ecommerce/user-service/internal/common/config"
	"github.com/ecommerce/user-service/internal/domain/port"
)

// InternalDeps is everything the cluster-internal listener needs.
type InternalDeps struct {
	Config   *config.Config
	Logger   *zap.Logger
	Limiter  port.RateLimiter
	Checkout *handler.CheckoutHandler
	Health   *handler.HealthHandler
}

// NewInternal builds the internal HTTP engine that serves the checkout address
// snapshot.
//
// This engine is bound to its own port and must never be published through the
// gateway: it takes a user id from the caller instead of from a verified
// identity, so the only thing separating it from an address-book oracle is the
// mTLS peer check (docs/user/interfaces.md §2).
func NewInternal(deps InternalDeps) *gin.Engine {
	engine := newEngine(deps.Config, deps.Logger)

	engine.GET("/health/live", deps.Health.Live)

	group := engine.Group("/internal/v1/users")
	if deps.Config.Internal.MutualTLSEnabled() {
		group.Use(middleware.ServiceIdentity(deps.Config.Internal.AllowedClientCN))
	} else {
		// Without certificates the listener can only be protected by the
		// network itself, which is acceptable for local development and refused
		// by the configuration validator in production.
		deps.Logger.Warn("the internal checkout API is running without mTLS")
	}

	group.Use(middleware.RateLimit(deps.Limiter, "internal-checkout:ip",
		rule(deps.Config.Security.RateLimit.InternalPerClient)))

	group.GET("/:user_id/addresses/:address_id/checkout-snapshot", deps.Checkout.Snapshot)

	return engine
}
