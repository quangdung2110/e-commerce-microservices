package router

import (
	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/middleware"
)

type Router struct {
	engine        *gin.Engine
	healthHandler *handler.HealthHandler
}

func NewRouter(healthHandler *handler.HealthHandler) *Router {
	engine := gin.New()

	engine.Use(gin.Recovery())
	engine.Use(middleware.Logger())
	engine.Use(middleware.Tracing())

	return &Router{
		engine:        engine,
		healthHandler: healthHandler,
	}
}

func (r *Router) SetupRoutes() {
	r.engine.GET("/health", r.healthHandler.Health)
	r.engine.GET("/ready", r.healthHandler.Ready)
	r.engine.GET("/live", r.healthHandler.Live)

	v1 := r.engine.Group("/api/v1/auth")
	{
		// Public routes (no auth required)
		public := v1.Group("/public")
		{
			public.POST("/register", r.notImplementedHandler("POST /register"))
			public.POST("/login", r.notImplementedHandler("POST /login"))
			public.POST("/forgot-password", r.notImplementedHandler("POST /forgot-password"))
			public.POST("/reset-password", r.notImplementedHandler("POST /reset-password"))
			public.POST("/verify-email", r.notImplementedHandler("POST /verify-email"))
			public.POST("/resend-otp", r.notImplementedHandler("POST /resend-otp"))
		}

		// JWKS endpoint (no auth, but may require API key from gateway)
		v1.GET("/.well-known/jwks.json", r.notImplementedHandler("GET /.well-known/jwks.json"))

		// Protected routes (require auth)
		protected := v1.Group("")
		protected.Use(middleware.Auth())
		{
			protected.POST("/logout", r.notImplementedHandler("POST /logout"))
			protected.POST("/refresh", r.notImplementedHandler("POST /refresh"))
			protected.POST("/change-password", r.notImplementedHandler("POST /change-password"))

			// Session management
			protected.GET("/sessions", r.notImplementedHandler("GET /sessions"))
			protected.DELETE("/sessions/:session_id", r.notImplementedHandler("DELETE /sessions/:session_id"))

			// User audit
			protected.GET("/audit/my-login-history", r.notImplementedHandler("GET /audit/my-login-history"))
		}

		// Admin routes (require admin role)
		admin := v1.Group("/admin")
		admin.Use(middleware.Auth(), middleware.RequireRole("ADMIN"))
		{
			admin.GET("/audit/history-logs", r.notImplementedHandler("GET /admin/audit/history-logs"))
			admin.POST("/users/:user_id/ban", r.notImplementedHandler("POST /admin/users/:user_id/ban"))
			admin.POST("/users/:user_id/unban", r.notImplementedHandler("POST /admin/users/:user_id/unban"))
			admin.POST("/users/:user_id/unlock", r.notImplementedHandler("POST /admin/users/:user_id/unlock"))
		}
	}
}

func (r *Router) notImplementedHandler(endpoint string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.JSON(501, gin.H{
			"code":    "NOT_IMPLEMENTED",
			"message": endpoint + " is not implemented yet",
		})
	}
}

func (r *Router) Engine() *gin.Engine {
	return r.engine
}
