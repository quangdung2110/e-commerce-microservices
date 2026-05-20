package router

import (
	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/middleware"
)

type Router struct {
	engine        *gin.Engine
	healthHandler *handler.HealthHandler
	userHandler   *handler.UserHandler
	authHandler   *handler.AuthHandler

	sessionHandler  *handler.SessionHandler
	passwordHandler *handler.PasswordHandler
	auditHandler    *handler.AuditHandler
	jwksHandler     *handler.JWKSHandler

	authMiddleware        *middleware.AuthMiddleware
	rateLimiterMiddleware *middleware.RateLimiterMiddleware
}

func NewRouter(
	healthHandler *handler.HealthHandler,
	userHandler *handler.UserHandler,
	authHandler *handler.AuthHandler,
	sessionHandler *handler.SessionHandler,
	passwordHandler *handler.PasswordHandler,
	auditHandler *handler.AuditHandler,
	jwksHandler *handler.JWKSHandler,
	authMiddleware *middleware.AuthMiddleware,
	rateLimiterMiddleware *middleware.RateLimiterMiddleware,
) *Router {
	engine := gin.New()

	engine.Use(gin.Recovery())
	engine.Use(middleware.Logger())
	engine.Use(middleware.Tracing())

	return &Router{
		engine:                engine,
		healthHandler:        healthHandler,
		userHandler:          userHandler,
		authHandler:          authHandler,
		sessionHandler:       sessionHandler,
		passwordHandler:     passwordHandler,
		auditHandler:        auditHandler,
		jwksHandler:         jwksHandler,
		authMiddleware:       authMiddleware,
		rateLimiterMiddleware: rateLimiterMiddleware,
	}
}

func (r *Router) SetupRoutes() {
	r.engine.GET("/health", r.healthHandler.Health)
	r.engine.GET("/ready", r.healthHandler.Ready)
	r.engine.GET("/live", r.healthHandler.Live)

	v1 := r.engine.Group("/api/v1/auth")
	{
		public := v1.Group("/public")
		{
			public.POST("/register", r.userHandler.Register)
			public.POST("/verify-email", r.userHandler.VerifyEmail)
			public.POST("/resend-otp", r.userHandler.ResendOTP)

			public.POST("/login",
				r.rateLimiterMiddleware.LoginRateLimit(),
				r.authHandler.Login,
			)

			public.POST("/forgot-password", r.passwordHandler.ForgotPassword)
			public.POST("/reset-password", r.passwordHandler.ResetPassword)
		}

		// JWKS endpoint - public, no auth required
		v1.GET("/.well-known/jwks.json", r.jwksHandler.GetJWKS)

		protected := v1.Group("")
		protected.Use(r.authMiddleware.Auth())
		{
			protected.POST("/logout", r.authHandler.Logout)
			protected.POST("/refresh", r.authHandler.Refresh)
			protected.POST("/change-password", r.passwordHandler.ChangePassword)

			protected.GET("/sessions", r.sessionHandler.ListSessions)
			protected.GET("/sessions/:session_id", r.sessionHandler.GetSession)
			protected.DELETE("/sessions/:session_id", r.sessionHandler.RevokeSession)
			protected.DELETE("/sessions", r.sessionHandler.RevokeAllSessions)

			protected.GET("/audit/my-login-history", r.auditHandler.GetMyLoginHistory)
		}

		admin := v1.Group("/admin")
		admin.Use(r.authMiddleware.Auth(), r.authMiddleware.RequireRole("ADMIN"))
		{
			admin.GET("/audit/history-logs", r.auditHandler.GetAuditLogs)
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
