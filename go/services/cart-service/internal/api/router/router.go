// Package router wires the HTTP surface described by
// contracts/http/cart/v1/openapi.yaml.
package router

import (
	obs "github.com/ecommerce/observability"
	obsgin "github.com/ecommerce/observability/gin"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"go.uber.org/zap"

	"cart-service/internal/api/dto/response"
	"cart-service/internal/api/handler"
	"cart-service/internal/api/middleware"
	"cart-service/internal/domain"
)

var httpMetrics = obs.NewHTTPMetrics()

// Config holds the router's dependencies.
type Config struct {
	Cart      *handler.Cart
	Health    *handler.Health
	RateLimit middleware.RateLimitConfig
	// InternalCallers are the service identities allowed on /internal/v1 (the mesh enforces the
	// principal; X-Service-Identity is checked as defence in depth).
	InternalCallers []string
	Registry        prometheus.Gatherer
	Logger          *zap.Logger
	// Debug switches Gin out of release mode.
	Debug bool
}

// New builds the HTTP engine.
func New(cfg Config) *gin.Engine {
	if !cfg.Debug {
		gin.SetMode(gin.ReleaseMode)
	}

	engine := gin.New()
	engine.Use(
		obsgin.Middleware(httpMetrics),
		middleware.RequestID(),
		middleware.Recovery(cfg.Logger),
		middleware.Logging(cfg.Logger),
	)
	engine.NoRoute(notFound)

	engine.GET("/health/live", cfg.Health.Live)
	engine.GET("/health/ready", cfg.Health.Ready)
	if cfg.Registry != nil {
		engine.GET("/metrics", gin.WrapH(httpMetrics.Handler(cfg.Registry)))
	}

	// Every cart route requires a trusted identity, and every write also
	// requires an idempotency key.
	carts := engine.Group("/api/v1/carts/mine")
	carts.Use(middleware.Identity(), middleware.RateLimit(cfg.RateLimit))
	{
		carts.GET("", cfg.Cart.Get)

		items := carts.Group("/items")
		items.Use(middleware.RequireIdempotencyKey())
		{
			items.POST("", cfg.Cart.AdjustItem)
			items.DELETE("", cfg.Cart.DeleteItems)
		}
	}

	// Internal read for Checkout. The user comes from the path, never from X-User-Id, so this group
	// must stay off the public gateway and behind the service identity check.
	internal := engine.Group("/internal/v1/carts")
	internal.Use(middleware.ServiceIdentity(cfg.InternalCallers), middleware.CallerDeadline())
	internal.GET("/:user_id/checkout-snapshot", cfg.Cart.CheckoutSnapshot)

	return engine
}

func notFound(c *gin.Context) {
	middleware.AbortWithProblem(c, response.NewProblem(
		http.StatusNotFound,
		domain.Code("NOT_FOUND"),
		"no route matches this path",
		c.Request.URL.Path,
		middleware.GetRequestID(c),
	))
}
