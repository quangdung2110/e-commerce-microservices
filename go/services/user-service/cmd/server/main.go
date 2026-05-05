package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"go.uber.org/fx"
	"go.uber.org/zap"

	"user-service/internal/domain/port"
	"user-service/internal/domain/service"
	"user-service/internal/infrastructure/postgres"
	redisinfra "user-service/internal/infrastructure/redis"
)

func main() {
	app := fx.New(
		fx.Provide(
			// Infrastructure
			NewLogger,
			NewConfig,
			NewPostgresPool,
			NewRedisClient,
			NewPostgresDB,

			// Repositories (implementations)
			NewProfileRepository,
			NewAddressRepository,
			NewPreferencesRepository,
			NewOutboxRepository,
			NewAddressCache,

			// Services
			NewProfileService,
			NewAddressService,
			NewPreferencesService,

			// HTTP Layer
			NewGinEngine,
			fx.Annotate(
				NewHTTPServer,
				fx.ParamTags(`group:"server"`),
			),
		),
		fx.Invoke(RegisterHooks),
	)

	ctx := context.Background()
	if err := app.Start(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to start application: %v\n", err)
		os.Exit(1)
	}

	// Wait for interrupt signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	fmt.Println("\nShutting down server...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := app.Stop(shutdownCtx); err != nil {
		fmt.Fprintf(os.Stderr, "Server forced to shutdown: %v\n", err)
	}
}

// Logger setup
func NewLogger() (*zap.Logger, error) {
	return zap.NewProduction()
}

// Config holds application configuration
type Config struct {
	Port string
	DB   DBConfig
	Redis RedisConfig
}

// DBConfig holds database configuration
type DBConfig struct {
	Host            string
	Port            string
	User            string
	Password        string
	Database        string
	SSLMode         string
	MaxConns        int32
	MinConns        int32
	MaxConnLifetime string
}

// RedisConfig holds Redis configuration
type RedisConfig struct {
	Host     string
	Port     string
	Password string
	DB       int
}

func NewConfig() *Config {
	return &Config{
		Port: getEnv("APP_PORT", "8080"),
		DB: DBConfig{
			Host:            getEnv("DB_HOST", "localhost"),
			Port:            getEnv("DB_PORT", "5432"),
			User:            getEnv("DB_USER", "postgres"),
			Password:        getEnv("DB_PASSWORD", "postgres"),
			Database:        getEnv("DB_NAME", "user_service"),
			SSLMode:         getEnv("DB_SSLMODE", "disable"),
			MaxConns:        25,
			MinConns:        5,
			MaxConnLifetime: "5m",
		},
		Redis: RedisConfig{
			Host:     getEnv("REDIS_HOST", "localhost"),
			Port:     getEnv("REDIS_PORT", "6379"),
			Password: getEnv("REDIS_PASSWORD", ""),
			DB:       0,
		},
	}
}

func NewPostgresPool(logger *zap.Logger, cfg *Config) (*pgxpool.Pool, error) {
	return postgres.NewPostgresPool(logger, postgres.DBConfig{
		Host:            cfg.DB.Host,
		Port:            cfg.DB.Port,
		User:            cfg.DB.User,
		Password:        cfg.DB.Password,
		Database:        cfg.DB.Database,
		SSLMode:         cfg.DB.SSLMode,
		MaxConns:        cfg.DB.MaxConns,
		MinConns:        cfg.DB.MinConns,
		MaxConnLifetime: cfg.DB.MaxConnLifetime,
	})
}

func NewRedisClient(logger *zap.Logger, cfg *Config) (*redis.Client, error) {
	client := redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%s", cfg.Redis.Host, cfg.Redis.Port),
		Password: cfg.Redis.Password,
		DB:       cfg.Redis.DB,
	})

	// Verify connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to Redis: %w", err)
	}

	logger.Info("Redis client created",
		zap.String("host", cfg.Redis.Host),
		zap.String("port", cfg.Redis.Port),
	)

	return client, nil
}

// PostgresDB wraps the pool for dependency injection
type PostgresDB struct {
	Pool *pgxpool.Pool
}

func NewPostgresDB(pool *pgxpool.Pool) *PostgresDB {
	return &PostgresDB{Pool: pool}
}

// Repository constructors - using real implementations from infrastructure package

func NewProfileRepository(db *PostgresDB) port.ProfileRepository {
	return postgres.NewPostgresProfileRepository(db.Pool)
}

func NewAddressRepository(db *PostgresDB) port.AddressRepository {
	return postgres.NewPostgresAddressRepository(db.Pool)
}

func NewPreferencesRepository(db *PostgresDB) port.PreferencesRepository {
	return postgres.NewPostgresPreferencesRepository(db.Pool)
}

func NewOutboxRepository(db *PostgresDB) port.OutboxRepository {
	return postgres.NewPostgresOutboxRepository(db.Pool)
}

func NewAddressCache(client *redis.Client) port.AddressCache {
	return redisinfra.NewRedisAddressCache(client)
}

// Service constructors

func NewProfileService(
	repo port.ProfileRepository,
	db *PostgresDB,
	logger *zap.Logger,
) *service.ProfileService {
	return service.NewProfileService(repo, db.Pool, logger)
}

func NewAddressService(
	repo port.AddressRepository,
	cache port.AddressCache,
	db *PostgresDB,
	logger *zap.Logger,
) *service.AddressService {
	return service.NewAddressService(repo, cache, db.Pool, logger)
}

func NewPreferencesService(
	prefsRepo port.PreferencesRepository,
	profileRepo port.ProfileRepository,
	outboxRepo port.OutboxRepository,
	db *PostgresDB,
	logger *zap.Logger,
) *service.PreferencesService {
	return service.NewPreferencesService(prefsRepo, profileRepo, outboxRepo, db.Pool, logger)
}

// HTTP Layer constructors

func NewGinEngine(
	profileService *service.ProfileService,
	addressService *service.AddressService,
	preferencesService *service.PreferencesService,
	logger *zap.Logger,
) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(requestLogger(logger))

	// Health check endpoints
	router.GET("/health", healthCheck)
	router.GET("/ready", readinessCheck)

	// TODO: Add API routes when Module C is implemented
	// api.RegisterRoutes(router, profileService, addressService, preferencesService)

	return router
}

func NewHTTPServer(lc fx.Lifecycle, router *gin.Engine, config *Config, logger *zap.Logger) *http.Server {
	addr := fmt.Sprintf(":%s", config.Port)
	server := &http.Server{
		Addr:         addr,
		Handler:      router,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	lc.Append(fx.Hook{
		OnStart: func(ctx context.Context) error {
			go func() {
				logger.Info("Starting HTTP server", zap.String("addr", addr))
				if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
					logger.Error("HTTP server error", zap.Error(err))
				}
			}()
			return nil
		},
		OnStop: func(ctx context.Context) error {
			return server.Shutdown(ctx)
		},
	})

	return server
}

func RegisterHooks(lifecycle fx.Lifecycle, logger *zap.Logger) {
	lifecycle.Append(fx.Hook{
		OnStart: func(ctx context.Context) error {
			logger.Info("Application started")
			return nil
		},
		OnStop: func(ctx context.Context) error {
			logger.Info("Application stopped")
			return nil
		},
	})
}

// Utility functions

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func requestLogger(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path

		c.Next()

		logger.Info("request",
			zap.String("method", c.Request.Method),
			zap.String("path", path),
			zap.Int("status", c.Writer.Status()),
			zap.Duration("latency", time.Since(start)),
		)
	}
}

func healthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "healthy",
		"timestamp": time.Now().Format(time.RFC3339),
	})
}

func readinessCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "ready",
		"timestamp": time.Now().Format(time.RFC3339),
	})
}
