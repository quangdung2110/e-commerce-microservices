package router

import (
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"user-service/internal/api/handler"
	"user-service/internal/api/middleware"
)

// Config holds router configuration
type Config struct {
	JWTSecret   string
	RateLimit   int           // requests per window
	Window      time.Duration // time window
	Capacity    int           // max tokens per window
}

// Setup configures and returns the Gin router
func Setup(
	logger *zap.Logger,
	profileHandler *handler.ProfileHandler,
	addressHandler *handler.AddressHandler,
	preferencesHandler *handler.PreferencesHandler,
	healthHandler *handler.HealthHandler,
	config *Config,
) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()

	// Global middleware
	router.Use(middleware.RecoveryMiddleware(logger))
	router.Use(middleware.LoggerMiddleware(logger))

	// Rate limiter for public endpoints
	rateLimiter := middleware.NewRateLimiter(config.RateLimit, config.Window, config.Capacity)
	router.Use(middleware.RateLimitMiddleware(rateLimiter))

	// Health check endpoints (no auth required)
	router.GET("/health", healthHandler.Health)
	router.GET("/ready", healthHandler.Ready)

	// API v1 routes (auth required)
	v1 := router.Group("/api/v1")
	v1.Use(middleware.AuthMiddleware(config.JWTSecret))
	{
		// User profile routes
		users := v1.Group("/users/me")
		{
			// Profile endpoints
			users.GET("/profile", profileHandler.GetProfile)
			users.PUT("/profile", profileHandler.UpdateProfile)

			// Address endpoints
			users.GET("/addresses", addressHandler.ListAddresses)
			users.POST("/addresses", addressHandler.CreateAddress)
			users.PUT("/addresses/:id", addressHandler.UpdateAddress)
			users.DELETE("/addresses/:id", addressHandler.DeleteAddress)
			users.POST("/addresses/:id/default", addressHandler.SetDefaultAddress)

			// Preferences endpoints
			users.GET("/preferences", preferencesHandler.GetPreferences)
			users.PUT("/preferences", preferencesHandler.UpdatePreferences)
		}
	}

	return router
}
