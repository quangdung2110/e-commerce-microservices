// Package router maps the OpenAPI contract onto gin routes. Paths, methods and
// status codes follow contracts/http/user/v1/openapi.yaml exactly.
package router

import (
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/api/handler"
	"github.com/ecommerce/user-service/internal/api/httpx"
	"github.com/ecommerce/user-service/internal/api/middleware"
	"github.com/ecommerce/user-service/internal/common/config"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/port"
)

// PublicDeps is everything the public listener needs.
type PublicDeps struct {
	Config      *config.Config
	Logger      *zap.Logger
	Limiter     port.RateLimiter
	Profile     *handler.ProfileHandler
	Address     *handler.AddressHandler
	Preferences *handler.PreferencesHandler
	Health      *handler.HealthHandler
}

// NewPublic builds the public HTTP engine.
//
// Every route below the identity middleware acts on the caller's own data:
// there is no path parameter naming a user, so a client cannot address somebody
// else's profile at all (USER-INV-001).
func NewPublic(deps PublicDeps) *gin.Engine {
	engine := newEngine(deps.Config, deps.Logger)

	limits := deps.Config.Security.RateLimit
	readLimit := middleware.RateLimitByIdentity(deps.Limiter, "user-read", rule(limits.ReadPerIdentity))
	writeLimit := middleware.RateLimitByIdentity(deps.Limiter, "user-write", rule(limits.WritePerIdentity))

	engine.GET("/health/live", deps.Health.Live)
	engine.GET("/health/ready", deps.Health.Ready)
	engine.GET("/health/startup", deps.Health.Startup)

	me := engine.Group("/api/v1/users/me", middleware.GatewayIdentity())

	me.GET("/profile", readLimit, deps.Profile.Get)
	me.PATCH("/profile", writeLimit, deps.Profile.Patch)

	me.GET("/preferences", readLimit, deps.Preferences.Get)
	me.PUT("/preferences", writeLimit, deps.Preferences.Replace)

	me.GET("/addresses", readLimit, deps.Address.List)
	me.POST("/addresses", writeLimit, deps.Address.Create)
	me.PUT("/addresses/:address_id", writeLimit, deps.Address.Replace)
	me.DELETE("/addresses/:address_id", writeLimit, deps.Address.Delete)
	me.PUT("/addresses/:address_id/default", writeLimit, deps.Address.SetDefault)

	return engine
}

// newEngine builds an engine with the shared middleware stack and the problem
// responses gin would otherwise render as plain text.
func newEngine(cfg *config.Config, logger *zap.Logger) *gin.Engine {
	if cfg.App.IsProduction() {
		gin.SetMode(gin.ReleaseMode)
	}

	engine := gin.New()
	engine.RedirectTrailingSlash = false
	// gin trusts every proxy by default, which would let a client forge its own
	// X-Forwarded-For and defeat per-IP rate limiting.
	if err := engine.SetTrustedProxies(cfg.Security.TrustedProxies); err != nil {
		logger.Warn("could not apply the trusted proxy list", zap.Error(err))
	}

	engine.Use(middleware.Correlation(logger), middleware.Recovery(), middleware.AccessLog())

	engine.HandleMethodNotAllowed = true
	engine.NoRoute(func(c *gin.Context) {
		httpx.WriteProblem(c, apperr.ErrNotFound)
	})
	engine.NoMethod(func(c *gin.Context) {
		httpx.WriteProblem(c, apperr.ErrMethodNotAllowed)
	})

	return engine
}

// rule converts a configured limit into the domain type.
func rule(r config.RateLimitRule) port.RateLimitRule {
	return port.RateLimitRule{Limit: r.Limit, Window: r.Window}
}
