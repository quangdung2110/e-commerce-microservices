package router

import (
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"cart-service/internal/api/handler"
	"cart-service/internal/api/middleware"
	"cart-service/internal/infrastructure/config"
)

// Router holds all handlers and the Gin engine.
type Router struct {
	engine        *gin.Engine
	cartHandler   *handler.CartHandler
	healthHandler *handler.HealthHandler
	logger        *zap.Logger
}

// NewRouter creates a new router with all routes configured.
func NewRouter(
	cartHandler *handler.CartHandler,
	healthHandler *handler.HealthHandler,
	logger *zap.Logger,
	redisHealth, pgHealth handler.HealthChecker,
	cfg *config.AppConfig,
) *Router {
	if cfg.App.Env == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	engine := gin.New()

	mwCfg := middleware.MiddlewareConfig{
		RateLimit: middleware.RateLimitConfig{
			Enabled:       cfg.App.Env != "development",
			MaxReqsPerMin: 1000,
		},
	}
	engine.Use(middleware.Stack(logger, mwCfg)...)

	r := &Router{
		engine:        engine,
		cartHandler:   cartHandler,
		healthHandler: healthHandler,
		logger:        logger,
	}

	r.setupRoutes()

	return r
}

// setupRoutes configures all API routes.
func (r *Router) setupRoutes() {
	r.engine.GET("/healthz", r.healthHandler.Liveness)
	r.engine.GET("/readyz", r.healthHandler.Readiness)
	r.engine.GET("/metrics", r.healthHandler.Metrics)

	v1 := r.engine.Group("/api/v1")
	{
		carts := v1.Group("/carts")
		carts.Use(middleware.Auth())
		{
			carts.GET("/mine", r.cartHandler.GetCart)
			carts.POST("/items", r.cartHandler.AddItem)
			carts.DELETE("/items", r.cartHandler.BatchDelete)
		}
	}
}

// Engine returns the Gin engine.
func (r *Router) Engine() *gin.Engine {
	return r.engine
}
