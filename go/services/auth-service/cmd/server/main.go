package server

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/pkg/cache"
	authConfig "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/config"
	v1_handler "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/handler"
	v1_middleware "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/middleware"
	v1_router "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1/router"
)

func main() {
	// Initialize logger
	logger, err := zap.NewProduction()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to initialize logger: %v\n", err)
		os.Exit(1)
	}
	defer logger.Sync()

	logger.Info("Starting auth-service...")

	// Load configuration
	cfg, err := authConfig.LoadAuthConfig()
	if err != nil {
		logger.Fatal("Failed to load configuration", zap.Error(err))
	}

	logger.Info("Configuration loaded",
		zap.Int("server.port", cfg.Server.Port),
		zap.String("redis.host", cfg.Redis.Host),
		zap.String("database.host", cfg.Database.Host))

	// Initialize Redis cache
	cacheManager, err := cache.NewRedisCacheManager(cache.RedisConfig{
		Host:   cfg.Redis.Host,
		Port:   cfg.Redis.Port,
		DB:     cfg.Redis.DB,
		Logger: logger,
	})
	if err != nil {
		logger.Fatal("Failed to initialize cache manager", zap.Error(err))
	}
	defer cacheManager.Close()

	// Check Redis connection
	if err := cacheManager.Ping(context.Background()); err != nil {
		logger.Fatal("Failed to connect to Redis", zap.Error(err))
	}
	logger.Info("Redis connection established")

	// Initialize dependencies (repositories, services, usecases)
	deps, err := InitializeDependencies(cfg, cacheManager.Client(), logger)
	if err != nil {
		logger.Fatal("Failed to initialize dependencies", zap.Error(err))
	}
	logger.Info("Dependencies initialized successfully")

	// Initialize Gin router
	if cfg.Server.Mode == "production" {
		gin.SetMode(gin.ReleaseMode)
	} else {
		gin.SetMode(gin.DebugMode)
	}

	router := gin.New()

	// Global middleware
	router.Use(gin.Recovery())
	router.Use(v1_middleware.LoggerMiddleware(logger))
	router.Use(v1_middleware.CORSMiddleware())
	router.Use(v1_middleware.SecurityHeadersMiddleware())

	// Health check endpoint
	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":    "healthy",
			"service":   "auth-service",
			"timestamp": time.Now().UTC(),
		})
	})

	// Readiness endpoint
	router.GET("/ready", func(c *gin.Context) {
		// Check dependencies
		if err := cacheManager.Ping(context.Background()); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{
				"status": "unhealthy",
				"error":  "redis connection failed",
			})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"status": "ready",
		})
	})

	// Initialize handlers
	registrationHandler := v1_handler.NewRegistrationHandler(
		deps.RegistrationUsecase,
		deps.ManagedAccountUsecase,
		logger,
	)

	authHandler := v1_handler.NewAuthenticationHandler(
		deps.LoginUsecase,
		deps.LogoutUsecase,
		deps.RefreshTokenUsecase,
		logger,
	)

	passwordHandler := v1_handler.NewPasswordHandler(
		deps.PasswordResetUsecase,
		deps.PasswordChangeUsecase,
		logger,
	)

	sessionHandler := v1_handler.NewSessionHandler(
		deps.SessionManagerUsecase,
		logger,
	)

	// Module 4: Audit and JWKS handlers
	auditHandler := v1_handler.NewAuditHandler(
		deps.AuditService,
		logger,
	)

	var jwksHandler *v1_handler.JwksHandler
	if deps.JwksService != nil {
		jwksHandler = v1_handler.NewJwksHandler(
			deps.JwksService,
			logger,
		)
	}

	// Module 4: Register JWKS routes at root level (/.well-known/jwks.json)
	// This must be registered before the API group
	if jwksHandler != nil {
		v1_router.RegisterJwksRoutes(router, jwksHandler)
	}

	// API routes
	v1Group := router.Group("/api/v1")
	{
		// Register routes using route modules
		v1_router.RegisterAuthRoutes(
			v1Group,
			authHandler,
			deps.RateLimitRepo,
			deps.SessionRepo,
			deps.BlacklistRepo,
			deps.TokenGenerator,
			logger,
		)

		v1_router.RegisterPasswordRoutes(
			v1Group,
			passwordHandler,
			deps.RateLimitRepo,
			deps.SessionRepo,
			deps.BlacklistRepo,
			deps.TokenGenerator,
			logger,
		)

		v1_router.RegisterSessionRoutes(
			v1Group,
			sessionHandler,
			deps.SessionRepo,
			deps.RateLimitRepo,
			deps.BlacklistRepo,
			deps.TokenGenerator,
			logger,
		)

		v1_router.RegisterRegistrationRoutes(
			v1Group,
			registrationHandler,
			deps.RateLimitRepo,
			logger,
		)

		// Module 4: Register Audit routes
		v1_router.RegisterAuditRoutes(
			v1Group,
			auditHandler,
			logger,
		)

		// Module 4: Register JWKS routes (API v1)
		if jwksHandler != nil {
			v1_router.RegisterJwksAPIRoutes(
				v1Group,
				jwksHandler,
			)
		}
	}

	// Create HTTP server
	serverAddr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	srv := &http.Server{
		Addr:           serverAddr,
		Handler:        router,
		ReadTimeout:    15 * time.Second,
		WriteTimeout:   15 * time.Second,
		IdleTimeout:    60 * time.Second,
		MaxHeaderBytes: 1 << 20, // 1 MB
	}

	// Start server in goroutine
	go func() {
		logger.Info("Server started", zap.String("address", serverAddr))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Failed to start server", zap.Error(err))
		}
	}()

	// Wait for interrupt signal to gracefully shut down the server
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")

	// Graceful shutdown with 30 second timeout
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatal("Server forced to shutdown", zap.Error(err))
	}

	logger.Info("Server exited")
}
