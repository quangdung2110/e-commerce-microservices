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

	"user-service/internal/api/handler"
	"user-service/internal/api/router"
	"user-service/internal/api/validator"
	"user-service/internal/domain/port"
	"user-service/internal/domain/service"
	kafkainfra "user-service/internal/infrastructure/kafka"
	kafkahandler "user-service/internal/infrastructure/kafka/handler"
	"user-service/internal/infrastructure/postgres"
	redisinfra "user-service/internal/infrastructure/redis"
	kafkaconsumer "user-service/internal/workers/kafka_consumer"
)

func main() {
	// Register custom validators
	validator.RegisterCustomValidators()

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
			NewUserDeletionRepository,
			NewAddressCache,

			// Services
			NewProfileService,
			NewAddressService,
			NewPreferencesService,

			// HTTP Handlers
			NewProfileHandler,
			NewAddressHandler,
			NewPreferencesHandler,
			NewHealthHandler,

			// Router Config
			NewRouterConfig,
			GinEngine,

			// Kafka Infrastructure
			NewKafkaConfig,
			NewUserEventProducer,
			NewKafkaUserVerifiedHandler,
			NewKafkaUserDeletedHandler,
			fx.Annotate(
				NewUserVerifiedConsumer,
				fx.ResultTags(`name:"user_verified_consumer"`),
			),
			fx.Annotate(
				NewUserDeletedConsumer,
				fx.ResultTags(`name:"user_deleted_consumer"`),
			),

			NewHTTPServer,
		),
		fx.Invoke(RegisterHooks),
		fx.Invoke(
			fx.Annotate(
				RegisterKafkaConsumers,
				fx.ParamTags(
					``, // lifecycle
					`name:"user_verified_consumer"`,
					`name:"user_deleted_consumer"`,
					``, // producer
					``, // logger
				),
			),
		),
		fx.Invoke(func(*http.Server) {}),
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
	Port      string
	JWTSecret string
	DB        DBConfig
	Redis     RedisConfig
	RateLimit RateLimitConfig
	Kafka     KafkaConfig
}

// KafkaConfig holds Kafka configuration
type KafkaConfig struct {
	Brokers        []string
	AuthTopic      string
	UserTopic      string
	ConsumerGroup  string
	OffsetReset    string
	SessionTimeout time.Duration
}

// RateLimitConfig holds rate limiting configuration
type RateLimitConfig struct {
	Enabled  bool
	Rate     int
	Window   time.Duration
	Capacity int
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
		Port:      getEnv("APP_PORT", "8080"),
		JWTSecret: getEnv("JWT_SECRET", "your-secret-key-change-in-production"),
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
		RateLimit: RateLimitConfig{
			Enabled:  getEnv("RATE_LIMIT_ENABLED", "true") == "true",
			Rate:     100,
			Window:   1 * time.Minute,
			Capacity: 100,
		},
		Kafka: KafkaConfig{
			Brokers:       []string{getEnv("KAFKA_BROKERS", "localhost:9092")},
			AuthTopic:     getEnv("KAFKA_AUTH_TOPIC", "t.auth.events"),
			UserTopic:     getEnv("KAFKA_USER_TOPIC", "t.user.events"),
			ConsumerGroup: getEnv("KAFKA_CONSUMER_GROUP", "user-service"),
			OffsetReset:   getEnv("KAFKA_OFFSET_RESET", "earliest"),
			SessionTimeout: 30 * time.Second,
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

func NewUserDeletionRepository(db *PostgresDB) port.UserDeletionRepository {
	return postgres.NewPostgresUserDeletionRepository(db.Pool)
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
	logger *zap.Logger,
) *service.PreferencesService {
	return service.NewPreferencesService(prefsRepo, profileRepo, logger)
}

// Handler constructors

func NewProfileHandler(profileService *service.ProfileService, producer *kafkainfra.UserEventProducer, logger *zap.Logger) *handler.ProfileHandler {
	return handler.NewProfileHandler(profileService, producer, logger)
}

func NewAddressHandler(addressService *service.AddressService, logger *zap.Logger) *handler.AddressHandler {
	return handler.NewAddressHandler(addressService, logger)
}

func NewPreferencesHandler(preferencesService *service.PreferencesService, producer *kafkainfra.UserEventProducer, logger *zap.Logger) *handler.PreferencesHandler {
	return handler.NewPreferencesHandler(preferencesService, producer, logger)
}

func NewHealthHandler(db *PostgresDB, redisClient *redis.Client) *handler.HealthHandler {
	return handler.NewHealthHandler(db.Pool, redisClient)
}

// Router configuration
func NewRouterConfig(cfg *Config) *router.Config {
	return &router.Config{
		JWTSecret: cfg.JWTSecret,
		RateLimit: cfg.RateLimit.Rate,
		Window:    cfg.RateLimit.Window,
		Capacity:  cfg.RateLimit.Capacity,
	}
}

// Kafka configuration
func NewKafkaConfig(cfg *Config) *kafkainfra.Config {
	return &kafkainfra.Config{
		Brokers:           cfg.Kafka.Brokers,
		AuthEventsTopic:   cfg.Kafka.AuthTopic,
		UserEventsTopic:   cfg.Kafka.UserTopic,
		ConsumerGroup:     cfg.Kafka.ConsumerGroup,
		OffsetReset:       cfg.Kafka.OffsetReset,
		SessionTimeout:    cfg.Kafka.SessionTimeout,
		HeartbeatInterval: 3 * time.Second,
		MaxWaitTime:       10 * time.Second,
		MinBytes:          1,
		MaxBytes:          10485760, // 10MB
	}
}

func NewUserEventProducer(kafkaCfg *kafkainfra.Config, logger *zap.Logger) *kafkainfra.UserEventProducer {
	return kafkainfra.NewUserEventProducer(kafkaCfg, logger)
}

// Kafka Handler constructors

func NewKafkaUserVerifiedHandler(
	profileRepo port.ProfileRepository,
	prefsRepo port.PreferencesRepository,
	addressRepo port.AddressRepository,
	db *PostgresDB,
	logger *zap.Logger,
) *kafkahandler.UserVerifiedHandler {
	return kafkahandler.NewUserVerifiedHandler(
		profileRepo,
		prefsRepo,
		addressRepo,
		db.Pool,
		logger,
	)
}

func NewKafkaUserDeletedHandler(
	profileRepo port.ProfileRepository,
	prefsRepo port.PreferencesRepository,
	addressRepo port.AddressRepository,
	deletionRepo port.UserDeletionRepository,
	producer *kafkainfra.UserEventProducer,
	cache port.AddressCache,
	db *PostgresDB,
	logger *zap.Logger,
) *kafkahandler.UserDeletedHandler {
	return kafkahandler.NewUserDeletedHandler(
		profileRepo,
		prefsRepo,
		addressRepo,
		deletionRepo,
		producer,
		cache,
		db.Pool,
		logger,
	)
}

// Kafka Consumer constructors

func NewUserVerifiedConsumer(
	kafkaCfg *kafkainfra.Config,
	userVerifiedHandler *kafkahandler.UserVerifiedHandler,
	logger *zap.Logger,
) *kafkaconsumer.Consumer {
	return kafkaconsumer.NewConsumer(
		kafkaconsumer.ConsumerConfig{
			Brokers:     kafkaCfg.Brokers,
			GroupID:     kafkaCfg.ConsumerGroup + "-profile-creator",
			Topic:       kafkaCfg.AuthEventsTopic,
			OffsetReset: kafkaCfg.OffsetReset,
			MaxWaitTime: kafkaCfg.MaxWaitTime,
			MinBytes:    kafkaCfg.MinBytes,
			MaxBytes:    kafkaCfg.MaxBytes,
		},
		userVerifiedHandler,
		logger,
	)
}

func NewUserDeletedConsumer(
	kafkaCfg *kafkainfra.Config,
	userDeletedHandler *kafkahandler.UserDeletedHandler,
	logger *zap.Logger,
) *kafkaconsumer.Consumer {
	return kafkaconsumer.NewConsumer(
		kafkaconsumer.ConsumerConfig{
			Brokers:     kafkaCfg.Brokers,
			GroupID:     kafkaCfg.ConsumerGroup + "-account-deletion",
			Topic:       kafkaCfg.AuthEventsTopic,
			OffsetReset: kafkaCfg.OffsetReset,
			MaxWaitTime: kafkaCfg.MaxWaitTime,
			MinBytes:    kafkaCfg.MinBytes,
			MaxBytes:    kafkaCfg.MaxBytes,
		},
		userDeletedHandler,
		logger,
	)
}

// GinEngine creates the Gin router with all routes configured
func GinEngine(
	logger *zap.Logger,
	profileHandler *handler.ProfileHandler,
	addressHandler *handler.AddressHandler,
	preferencesHandler *handler.PreferencesHandler,
	healthHandler *handler.HealthHandler,
	routerConfig *router.Config,
) *gin.Engine {
	return router.Setup(
		logger,
		profileHandler,
		addressHandler,
		preferencesHandler,
		healthHandler,
		routerConfig,
	)
}

// HTTP Layer constructors

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

// Kafka lifecycle hooks

func RegisterKafkaConsumers(
	lifecycle fx.Lifecycle,
	userVerifiedConsumer *kafkaconsumer.Consumer,
	userDeletedConsumer *kafkaconsumer.Consumer,
	producer *kafkainfra.UserEventProducer,
	logger *zap.Logger,
) {
	lifecycle.Append(fx.Hook{
		OnStart: func(ctx context.Context) error {
			// Start user verified consumer
			if err := userVerifiedConsumer.Start(ctx); err != nil {
				return fmt.Errorf("failed to start user verified consumer: %w", err)
			}
			logger.Info("User verified Kafka consumer started")

			// Start user deleted consumer
			if err := userDeletedConsumer.Start(ctx); err != nil {
				return fmt.Errorf("failed to start user deleted consumer: %w", err)
			}
			logger.Info("User deleted Kafka consumer started")

			return nil
		},
		OnStop: func(ctx context.Context) error {
			logger.Info("Stopping Kafka consumers...")

			// Stop user deleted consumer first
			if err := userDeletedConsumer.Stop(); err != nil {
				logger.Error("Failed to stop user deleted consumer", zap.Error(err))
			}

			// Stop user verified consumer
			if err := userVerifiedConsumer.Stop(); err != nil {
				logger.Error("Failed to stop user verified consumer", zap.Error(err))
			}

			// Close Kafka producer
			if err := producer.Close(); err != nil {
				logger.Error("Failed to close Kafka producer", zap.Error(err))
			}

			logger.Info("Kafka consumers and producer stopped")
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
