package v1_router

import (
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	v1_handler "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/handler"
	v1_middleware "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/middleware"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// RegisterAuthRoutes registers authentication-related routes
func RegisterAuthRoutes(
	router *gin.RouterGroup,
	authHandler *v1_handler.AuthenticationHandler,
	rateLimitRepo repository.RateLimitRepository,
	sessionRepo repository.SessionRepository,
	blacklistRepo repository.BlacklistRepository,
	tokenGenerator common.TokenGenerator,
	logger *zap.Logger,
) {
	authGroup := router.Group("/auth")

	// Rate limiting middleware for auth endpoints (100 requests per minute)
	authGroup.Use(v1_middleware.RateLimitMiddleware(logger, rateLimitRepo, 100))

	// Public auth routes (no authentication required)
	{
		authGroup.POST("/login", authHandler.Login)
		authGroup.POST("/refresh", authHandler.RefreshToken)
	}

	// Protected auth routes (require authentication)
	authMiddleware := v1_middleware.AuthMiddleware(logger, tokenGenerator, blacklistRepo)
	activityTracker := v1_middleware.ActivityTrackerMiddleware(logger, sessionRepo, rateLimitRepo)

	protected := authGroup.Group("")
	protected.Use(authMiddleware)
	protected.Use(activityTracker)
	{
		protected.POST("/logout", authHandler.Logout)
		protected.POST("/logout-all", authHandler.LogoutAll)
	}
}
