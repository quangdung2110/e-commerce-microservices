package v1_router

import (
	"github.com/gin-gonic/gin"
	v1_handler "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/handler"
	"go.uber.org/zap"

	v1_middleware "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/middleware"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// RegisterPasswordRoutes registers password-related routes
func RegisterPasswordRoutes(
	router *gin.RouterGroup,
	passwordHandler *v1_handler.PasswordHandler,
	rateLimitRepo repository.RateLimitRepository,
	sessionRepo repository.SessionRepository,
	blacklistRepo repository.BlacklistRepository,
	tokenGenerator common.TokenGenerator,
	logger *zap.Logger,
) {
	passwordGroup := router.Group("/password")

	// Rate limiting middleware for password endpoints
	passwordGroup.Use(v1_middleware.RateLimitMiddleware(logger, rateLimitRepo, 100))

	// Public password routes (no authentication required)
	{
		passwordGroup.POST("/forgot", passwordHandler.ForgotPassword)
		passwordGroup.POST("/validate-token", passwordHandler.ValidateResetToken)
		passwordGroup.POST("/reset", passwordHandler.ResetPassword)
	}

	// Protected password routes (require authentication)
	authMiddleware := v1_middleware.AuthMiddleware(logger, tokenGenerator, blacklistRepo)
	activityTracker := v1_middleware.ActivityTrackerMiddleware(logger, sessionRepo, rateLimitRepo)

	protected := passwordGroup.Group("")
	protected.Use(authMiddleware)
	protected.Use(activityTracker)
	{
		protected.POST("/change", passwordHandler.ChangePassword)
	}
}
