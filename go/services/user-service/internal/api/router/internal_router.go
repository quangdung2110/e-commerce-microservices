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
// identity, so what separates it from an address-book oracle is the mesh
// AuthorizationPolicy that only lets the checkout-service principal reach it
// (docs/mesh/runtime-contract.md §5.2). The X-Service-Identity allowlist below
// is defence in depth, not the enforcement point (docs/user/interfaces.md §2).
func NewInternal(deps InternalDeps) *gin.Engine {
	engine := newEngine(deps.Config, deps.Logger)

	engine.GET("/health/live", deps.Health.Live)

	group := engine.Group("/internal/v1/users")
	group.Use(middleware.ServiceIdentity(deps.Config.Internal.AllowedCallers))
	group.Use(middleware.RateLimitByServiceUser(deps.Limiter, "internal-checkout", "user_id",
		rule(deps.Config.Security.RateLimit.InternalPerUser)))

	group.GET("/:user_id/addresses/:address_id/checkout-snapshot", deps.Checkout.Snapshot)

	return engine
}
