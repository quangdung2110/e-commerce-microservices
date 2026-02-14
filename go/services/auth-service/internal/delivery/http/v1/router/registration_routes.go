package v1_router

import (
	"github.com/gin-gonic/gin"
	v1_handler "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/handler"
	"go.uber.org/zap"

	v1_middleware "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/middleware"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

// RegisterRegistrationRoutes registers user registration routes
func RegisterRegistrationRoutes(
	router *gin.RouterGroup,
	registrationHandler *v1_handler.RegistrationHandler,
	rateLimitRepo repository.RateLimitRepository,
	logger *zap.Logger,
) {
	// Registration routes (Amazon-style 5-step flow)
	registerGroup := router.Group("/register")

	// Rate limiting middleware for registration endpoints
	registerGroup.Use(v1_middleware.RateLimitMiddleware(logger, rateLimitRepo, 100))
	{
		registerGroup.POST("/start", registrationHandler.StartRegistration)
		registerGroup.POST("/verify-email", registrationHandler.VerifyEmailOTP)
		registerGroup.POST("/add-phone", registrationHandler.AddPhoneNumber)
		registerGroup.POST("/verify-phone", registrationHandler.VerifyPhoneOTP)
		registerGroup.POST("/complete", registrationHandler.CompleteRegistration)
	}

	// Admin routes (managed account creation)
	adminGroup := router.Group("/admin")

	// TODO: Add admin authentication middleware when ready
	// adminGroup.Use(middleware.NewAdminAuthMiddleware(...))

	adminGroup.Use(v1_middleware.RateLimitMiddleware(logger, rateLimitRepo, 100))
	{
		adminGroup.POST("/managed-accounts", registrationHandler.CreateManagedAccount)
	}
}
