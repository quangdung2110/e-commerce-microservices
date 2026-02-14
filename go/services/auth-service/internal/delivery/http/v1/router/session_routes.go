package v1_router

import (
	"github.com/gin-gonic/gin"
	v1_handler "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/handler"
	"go.uber.org/zap"

	v1_middleware "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/middleware"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// RegisterSessionRoutes registers session management routes
func RegisterSessionRoutes(
	router *gin.RouterGroup,
	sessionHandler *v1_handler.SessionHandler,
	sessionRepo repository.SessionRepository,
	rateLimitRepo repository.RateLimitRepository,
	blacklistRepo repository.BlacklistRepository,
	tokenGenerator common.TokenGenerator,
	logger *zap.Logger,
) {
	sessionGroup := router.Group("/sessions")

	// All session routes require authentication
	authMiddleware := v1_middleware.AuthMiddleware(logger, tokenGenerator, blacklistRepo)
	activityTracker := v1_middleware.ActivityTrackerMiddleware(logger, sessionRepo, rateLimitRepo)
	rateLimitMiddleware := v1_middleware.RateLimitMiddleware(logger, rateLimitRepo, 100)

	sessionGroup.Use(authMiddleware)
	sessionGroup.Use(activityTracker)
	sessionGroup.Use(rateLimitMiddleware)
	{
		sessionGroup.GET("", sessionHandler.ListSessions)
		sessionGroup.GET("/:session_id", sessionHandler.GetSession)
		sessionGroup.DELETE("/:session_id", sessionHandler.RevokeSession)
		sessionGroup.DELETE("", sessionHandler.RevokeAllSessions)
	}
}
