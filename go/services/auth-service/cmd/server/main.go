package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/gin-gonic/gin"
	"github.com/tylerb/graceful"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"

	"github.com/ecommerce/auth-service/internal/api/handler"
	"github.com/ecommerce/auth-service/internal/api/middleware"
	"github.com/ecommerce/auth-service/internal/api/router"
	"github.com/ecommerce/auth-service/internal/common/config"
	infracommon "github.com/ecommerce/auth-service/internal/infrastructure/common"
	"github.com/ecommerce/auth-service/internal/infrastructure/kafka"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/workers/key_retirement"
	"github.com/ecommerce/auth-service/internal/workers/key_rotation"
)

func main() {
	configPath := flag.String("config", "", "Path to config file")
	flag.Parse()

	logger, err := initLogger()
	if err != nil {
		log.Fatalf("Failed to initialize logger: %v", err)
	}
	defer logger.Sync()

	cfg, err := config.Load(*configPath)
	if err != nil {
		logger.Fatal("Failed to load configuration", zap.Error(err))
	}

	if cfg.App.Env == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	logger.Info("Starting auth-service",
		zap.String("env", cfg.App.Env),
		zap.Int("port", cfg.App.Port),
	)

	db, err := postgres.NewConnection(cfg.Database)
	if err != nil {
		logger.Fatal("Failed to connect to database", zap.Error(err))
	}
	defer db.Close()

	redisClient, err := redis.NewClient(cfg.Redis)
	if err != nil {
		logger.Fatal("Failed to connect to Redis", zap.Error(err))
	}
	defer redisClient.Close()

	var eventPublisher port.EventPublisher
	kafkaProducer, err := kafka.NewProducer(cfg.Kafka)
	if err != nil {
		logger.Warn("Failed to create Kafka producer, running without Kafka", zap.Error(err))
		eventPublisher = &port.NoOpEventPublisher{}
	} else {
		defer kafkaProducer.Close()
		eventPublisher = kafkaProducer
	}

	// Initialize repositories
	userRepo := postgres.NewUserRepository(db.Pool)
	refreshTokenRepo := postgres.NewRefreshTokenRepository(db.Pool)
	auditLogRepo := postgres.NewAuditLogRepository(db.Pool)
	jwtKeyRepo := postgres.NewJWTKeyRepository(db.Pool)

	// Initialize Redis caches
	tokenCache := redis.NewCacheRepository(redisClient)
	failedLoginCache := redis.NewFailedLoginCounter(redisClient)
	rateLimiter := redis.NewRateLimit(redisClient)
	gracePeriodCache := redis.NewGracePeriod(redisClient)
	resetTokenCache := redis.NewResetTokenCache(redisClient)
	otpCache := redis.NewOTPCache(redisClient)

	// Initialize password hasher
	passwordHasher := infracommon.NewArgon2Hasher(infracommon.Argon2Config{
		Memory:      65536,
		Iterations:  3,
		Parallelism: 4,
		SaltLength:  16,
		KeyLength:   32,
	})

	// Initialize services
	jwtService := service.NewJWTService(jwtKeyRepo, gracePeriodCache, tokenCache, logger)
	auditService := service.NewAuditService(auditLogRepo, logger)
	otpService := service.NewOTPService(otpCache)

	// JWKS and Key services
	keyService := service.NewKeyService(jwtKeyRepo, logger)
	jwksService := service.NewJWKSService(jwtKeyRepo, logger)

	// Brute force service (optional)
	bruteForceService := service.NewBruteForceService(
		userRepo,
		failedLoginCache,
		auditService,
		eventPublisher,
		logger,
	)
	_ = bruteForceService

	// Auth service
	authService := service.NewAuthService(
		userRepo,
		refreshTokenRepo,
		tokenCache,
		passwordHasher,
		failedLoginCache,
		eventPublisher,
		auditService,
		jwtService,
		logger,
	)

	// Refresh service
	refreshService := service.NewRefreshService(
		userRepo,
		refreshTokenRepo,
		tokenCache,
		gracePeriodCache,
		jwtService,
		eventPublisher,
		auditService,
		logger,
	)

	// User service
	userService := service.NewUserService(
		userRepo,
		otpService,
		passwordHasher,
		eventPublisher,
	)

	// Session service
	sessionService := service.NewSessionService(
		refreshTokenRepo,
		tokenCache,
		auditService,
		logger,
	)

	// Password service
	passwordService := service.NewPasswordService(
		userRepo,
		refreshTokenRepo,
		tokenCache,
		passwordHasher,
		resetTokenCache,
		eventPublisher,
		auditService,
		logger,
	)

	// Set dependencies
	jwtService.SetEventPublisher(eventPublisher)
	jwtService.SetAuditService(auditService)

	// Initialize middleware
	authMiddleware := middleware.NewAuthMiddleware(jwtService, tokenCache, logger)
	rateLimiterMiddleware := middleware.NewRateLimiterMiddleware(rateLimiter, logger)

	// Initialize handlers
	healthHandler := handler.NewHealthHandler(
		db.Ping,
		func(ctx context.Context) error {
			return redisClient.Ping(ctx).Err()
		},
	)

	userHandler := handler.NewUserHandler(userService)
	authHandler := handler.NewAuthHandler(authService, refreshService, jwtService, logger)
	sessionHandler := handler.NewSessionHandler(sessionService, logger)
	passwordHandler := handler.NewPasswordHandler(passwordService, logger)
	auditHandler := handler.NewAuditHandler(auditService, logger)
	jwksHandler := handler.NewJWKSHandler(jwksService, logger)

	// Initialize workers
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Key rotation worker
	keyRotationWorker := keyrotation.NewKeyRotationWorker(
		keyService,
		jwksService,
		redisClient,
		cfg.Keys,
		logger,
	)

	// Key retirement worker
	keyRetirementWorker := keyretirement.NewKeyRetirementWorker(
		keyService,
		jwksService,
		redisClient,
		cfg.Keys,
		logger,
	)

	// Start workers
	if err := keyRotationWorker.Start(ctx); err != nil {
		logger.Error("Failed to start key rotation worker", zap.Error(err))
	}
	defer keyRotationWorker.Stop()

	if err := keyRetirementWorker.Start(ctx); err != nil {
		logger.Error("Failed to start key retirement worker", zap.Error(err))
	}
	defer keyRetirementWorker.Stop()

	// Initialize router
	r := router.NewRouter(
		healthHandler,
		userHandler,
		authHandler,
		sessionHandler,
		passwordHandler,
		auditHandler,
		jwksHandler,
		authMiddleware,
		rateLimiterMiddleware,
	)
	r.SetupRoutes()

	server := &graceful.Server{
		Server: &http.Server{
			Addr:         fmt.Sprintf("%s:%d", cfg.App.Host, cfg.App.Port),
			Handler:      r.Engine(),
			ReadTimeout:  cfg.Server.ReadTimeout,
			WriteTimeout: cfg.Server.WriteTimeout,
			IdleTimeout:  cfg.Server.IdleTimeout,
		},
		Timeout: cfg.Server.ShutdownTimeout,
	}

	go func() {
		logger.Info("Starting HTTP server",
			zap.String("addr", server.Addr),
		)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Failed to start server", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
	defer shutdownCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("Server forced to shutdown", zap.Error(err))
	}

	logger.Info("Server exited properly")
}

func initLogger() (*zap.Logger, error) {
	cfg := zap.NewProductionConfig()
	cfg.EncoderConfig.TimeKey = "timestamp"
	cfg.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	return cfg.Build()
}
