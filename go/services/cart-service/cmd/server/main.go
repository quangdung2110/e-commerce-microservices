package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"

	"cart-service/internal/api/handler"
	"cart-service/internal/api/router"
	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/service"
	"cart-service/internal/infrastructure/config"
	grpcinfra "cart-service/internal/infrastructure/grpc"
	"cart-service/internal/infrastructure/kafka"
	"cart-service/internal/infrastructure/postgres"
	redisinfra "cart-service/internal/infrastructure/redis"
	"cart-service/internal/workers/cronjobs"
	"cart-service/internal/workers/order_consumer"
	outboxrelay "cart-service/internal/workers/outbox_relay"
	"cart-service/internal/workers/postgres_sync"
)

// Global logger instance.
var logger *zap.Logger

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "server terminated: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	// Step 1: Load configuration
	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		configPath = "configs/application.yaml"
	}

	cfg, err := config.Load(configPath)
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}

	// Step 2: Initialize logger
	logger = initLogger(cfg.Logging.Level, cfg.Logging.Format)
	defer logger.Sync()
	logger.Info("starting cart-service",
		zap.String("env", cfg.App.Env),
		zap.String("app", cfg.App.Name),
	)

	// Step 3: Create root context
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Step 4: Initialize infrastructure
	deps, err := initializeInfrastructure(ctx, cfg)
	if err != nil {
		return fmt.Errorf("initialize infrastructure: %w", err)
	}
	defer shutdownInfrastructure(deps)

	// Step 5: Initialize domain services
	cartService := initializeDomainServices(deps, cfg)

	// Step 6: Initialize workers
	workers, err := initializeWorkers(ctx, deps, cfg, cartService)
	if err != nil {
		return fmt.Errorf("initialize workers: %w", err)
	}

	// Step 7: Initialize API handlers and router
	cartHandler := handler.NewCartHandler(cartService, logger)
	healthHandler := handler.NewHealthHandler(deps.RedisHealth, deps.PGHealth)
	appRouter := router.NewRouter(cartHandler, healthHandler, logger, deps.RedisHealth, deps.PGHealth, cfg)

	// Step 8: Create HTTP server
	server := &http.Server{
		Addr:         fmt.Sprintf("%s:%d", cfg.App.Host, cfg.App.Port),
		Handler:      appRouter.Engine(),
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Step 9: Start HTTP server
	go func() {
		logger.Info("HTTP server starting",
			zap.String("addr", server.Addr),
		)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("HTTP server failed", zap.Error(err))
		}
	}()

	// Step 10: Start all workers
	for name, stopFn := range workers {
		if err := stopFn.Start(ctx); err != nil {
			logger.Error("failed to start worker", zap.String("worker", name), zap.Error(err))
			return fmt.Errorf("start worker %s: %w", name, err)
		}
		logger.Info("worker started", zap.String("worker", name))
	}

	// Step 11: Start cron jobs
	cronScheduler := setupCronJobs(ctx, deps, cfg)
	logger.Info("cron scheduler started")

	// Step 12: Graceful shutdown
	shutdown(server, deps, workers, cronScheduler, cfg)
	return nil
}

// workerControl holds the stop function for each worker.
type workerControl struct {
	Start func(ctx context.Context) error
	Stop  func()
}

// initializeInfrastructure sets up all infrastructure clients.
func initializeInfrastructure(ctx context.Context, cfg *config.AppConfig) (*Infrastructure, error) {
	infra := &Infrastructure{}

	// --- Redis ---
	redisCfg := redisinfra.RedisConfig{
		Addrs:        cfg.Redis.Addrs,
		Password:     cfg.Redis.Password,
		DB:           cfg.Redis.DB,
		PoolSize:     cfg.Redis.PoolSize,
		MinIdleConns: cfg.Redis.MinIdleConns,
		ReadTimeout:  cfg.Redis.ReadTimeout,
		WriteTimeout: cfg.Redis.WriteTimeout,
		NumShards:    cfg.Redis.NumShards,
	}

	redisClient, err := redisinfra.NewClient(redisCfg)
	if err != nil {
		return nil, fmt.Errorf("redis client: %w", err)
	}
	infra.RedisClient = redisClient
	infra.RedisHealth = redisClient

	// Ping to verify connectivity
	pingCtx, pingCancel := context.WithTimeout(ctx, 5*time.Second)
	defer pingCancel()
	if err := redisClient.Ping(pingCtx); err != nil {
		return nil, fmt.Errorf("redis ping: %w", err)
	}

	// ShardManager for stream sharding
	shardManager := redisinfra.NewShardManager(cfg.Redis.NumShards)

	// KeyBuilder
	kb := keybuilder.New(uint32(cfg.Redis.NumShards))

	// Ensure consumer groups for all shards (required by outbox_relay workers)
	if err := shardManager.EnsureConsumerGroups(ctx, redisClient, "cart_relay_group"); err != nil {
		return nil, fmt.Errorf("ensure consumer groups: %w", err)
	}

	// Cart Lua Repository
	infra.CartLuaRepo = redisinfra.NewCartLuaRepo(redisClient, shardManager)

	// Cache Repository
	infra.CacheRepo = redisinfra.NewCacheRepo(redisClient, kb, cfg.Cart.NegativeCacheTTL)

	// Outbox Writer
	infra.OutboxWriter = redisinfra.NewOutboxWriter(redisClient, kb)

	logger.Info("redis infrastructure initialized",
		zap.Strings("addrs", cfg.Redis.Addrs),
		zap.Int("num_shards", cfg.Redis.NumShards),
	)

	// --- PostgreSQL ---
	pgCfg := postgres.Config{
		Host:            cfg.Database.Host,
		Port:           cfg.Database.Port,
		User:           cfg.Database.User,
		Password:       cfg.Database.Password,
		Name:           cfg.Database.Name,
		MaxOpenConns:   cfg.Database.MaxOpenConns,
		MaxIdleConns:   cfg.Database.MaxIdleConns,
		ConnMaxLifetime: parseDuration(cfg.Database.ConnMaxLifetime),
		SSLMode:        "disable",
	}

	pgDB, err := postgres.NewGORM(ctx, pgCfg)
	if err != nil {
		return nil, fmt.Errorf("postgres: %w", err)
	}
	infra.PGDB = pgDB
	infra.PGHealth = postgres.NewHealthChecker(pgDB)
	infra.CartRepo = postgres.NewCartRepository(pgDB)

	logger.Info("postgres infrastructure initialized",
		zap.String("host", cfg.Database.Host),
		zap.Int("port", cfg.Database.Port),
	)

	// --- Kafka ---
	kafkaCfg := kafka.Config{
		Brokers:           cfg.Kafka.Brokers,
		TopicCartEvents:  cfg.Kafka.TopicCartEvents,
		TopicOrderEvents: cfg.Kafka.TopicOrderEvents,
		Producer: kafka.ProducerConfig{
			RequiredAcks: 1,
			Timeout:      10 * time.Second,
			RetryMax:     3,
		},
	}

	infra.KafkaPublisher, err = kafka.NewPublisher(kafkaCfg)
	if err != nil {
		return nil, fmt.Errorf("kafka publisher: %w", err)
	}

	logger.Info("kafka infrastructure initialized",
		zap.Strings("brokers", cfg.Kafka.Brokers),
	)

	// --- gRPC ---
	grpcCfg := config.GRPCSection{
		ProductServiceAddr: cfg.GRPC.ProductServiceAddr,
		DialTimeout:        cfg.GRPC.DialTimeout,
		MaxCallTimeout:     cfg.GRPC.MaxCallTimeout,
		CircuitBreaker:     cfg.GRPC.CircuitBreaker,
	}

	infra.ProductClient, err = grpcinfra.NewProductClient(grpcCfg)
	if err != nil {
		return nil, fmt.Errorf("gRPC product client: %w", err)
	}

	logger.Info("gRPC infrastructure initialized",
		zap.String("addr", cfg.GRPC.ProductServiceAddr),
	)

	return infra, nil
}

// initializeDomainServices creates domain service instances.
func initializeDomainServices(deps *Infrastructure, cfg *config.AppConfig) *service.CartService {
	return service.NewCartServiceWithKB(
		deps.CartLuaRepo,
		deps.CacheRepo,
		deps.CartRepo,
		deps.ProductClient,
		service.CartServiceConfig{
			MaxSKUsPerCart:   cfg.Cart.MaxSKUsPerCart,
			MaxQtyPerItem:    cfg.Cart.MaxQtyPerItem,
			RehydrateTTL:     cfg.Cart.RehydrateTTL,
			NegativeCacheTTL: cfg.Cart.NegativeCacheTTL,
		},
		keybuilder.New(uint32(cfg.Redis.NumShards)),
	)
}

// initializeWorkers starts all background workers.
func initializeWorkers(
	ctx context.Context,
	deps *Infrastructure,
	cfg *config.AppConfig,
	cartService *service.CartService,
) (map[string]*workerControl, error) {

	workers := make(map[string]*workerControl)
	kb := keybuilder.New(uint32(cfg.Redis.NumShards))

	// Worker #1: Outbox Relay Pool
	outboxPool := outboxrelay.NewWorkerPool(
		deps.RedisClient.Raw(),
		deps.KafkaPublisher,
		cfg.Worker.OutboxRelay,
		cfg.Redis.NumShards,
		logger,
	)
	workers["outbox_relay"] = &workerControl{
		Start: func(ctx context.Context) error {
			outboxPool.Start(ctx)
			return nil
		},
		Stop: outboxPool.Stop,
	}

	// Worker #2: Postgres Sync
	if cfg.Worker.PostgresSync.Enabled {
		kafkaConsumerCfg := kafka.Config{
			Brokers:           cfg.Kafka.Brokers,
			TopicCartEvents:  cfg.Kafka.TopicCartEvents,
			Consumer: kafka.ConsumerConfig{
				GroupID:         cfg.Kafka.ConsumerGroupCartSync,
				SessionTimeout:   30 * time.Second,
				HeartbeatInterval: 3 * time.Second,
				RebalanceTimeout: 30 * time.Second,
				AutoOffsetReset: "earliest",
				EnableAutoCommit: true,
			},
		}
		consumer, err := kafka.NewConsumer(kafkaConsumerCfg, []string{cfg.Kafka.TopicCartEvents}, nil)
		if err != nil {
			return nil, fmt.Errorf("postgres sync consumer: %w", err)
		}
		pgSyncWorker := postgres_sync.NewWorker(consumer, deps.CartRepo, cfg.Worker.PostgresSync, logger)
		workers["postgres_sync"] = &workerControl{
			Start: pgSyncWorker.Start,
			Stop:  pgSyncWorker.Stop,
		}
	}

	// Worker #6: Order Event Consumer
	orderKafkaCfg := kafka.Config{
		Brokers: cfg.Kafka.Brokers,
		Consumer: kafka.ConsumerConfig{
			GroupID:         cfg.Kafka.ConsumerGroupOrder,
			SessionTimeout:   30 * time.Second,
			HeartbeatInterval: 3 * time.Second,
			RebalanceTimeout: 30 * time.Second,
			AutoOffsetReset: "earliest",
			EnableAutoCommit: true,
		},
	}
	orderConsumer, err := kafka.NewConsumer(orderKafkaCfg, []string{cfg.Kafka.TopicOrderEvents}, nil)
	if err != nil {
		return nil, fmt.Errorf("order consumer: %w", err)
	}
	orderWorker := order_consumer.NewWorker(
		orderConsumer,
		deps.RedisClient.Raw(),
		deps.CartLuaRepo,
		deps.CacheRepo,
		kb,
		cfg.Redis.NumShards,
		logger,
	)
	workers["order_consumer"] = &workerControl{
		Start: orderWorker.Start,
		Stop:  orderWorker.Stop,
	}

	logger.Info("all workers initialized",
		zap.Int("count", len(workers)),
	)

	return workers, nil
}

// setupCronJobs registers scheduled jobs with robfig/cron.
func setupCronJobs(ctx context.Context, deps *Infrastructure, cfg *config.AppConfig) *cronjobs.Scheduler {
	scheduler := cronjobs.NewScheduler(logger)

	// Abandoned Cart Marker — daily at 2:00 AM
	if cfg.Worker.AbandonedMarker.Enabled {
		abandonedJob := cronjobs.NewAbandonedMarker(
			deps.CartRepo,
			deps.OutboxWriter,
			cfg.Worker.AbandonedMarker,
			cfg.Redis.NumShards,
			logger,
		)
		if err := scheduler.Add("abandoned_marker", cfg.Worker.AbandonedMarker.Cron, abandonedJob); err != nil {
			logger.Warn("failed to register abandoned_marker cron", zap.Error(err))
		} else {
			logger.Info("abandoned marker cron registered",
				zap.String("cron", cfg.Worker.AbandonedMarker.Cron))
		}
	}

	// Data Archival Cleaner — weekly on Sunday at 3:00 AM
	if cfg.Worker.ArchivalCleaner.Enabled {
		archivalJob := cronjobs.NewArchivalCleaner(
			deps.CartRepo,
			cfg.Worker.ArchivalCleaner,
			logger,
		)
		if err := scheduler.Add("archival_cleaner", cfg.Worker.ArchivalCleaner.Cron, archivalJob); err != nil {
			logger.Warn("failed to register archival_cleaner cron", zap.Error(err))
		} else {
			logger.Info("archival cleaner cron registered",
				zap.String("cron", cfg.Worker.ArchivalCleaner.Cron))
		}
	}

	scheduler.Start()
	return scheduler
}

// shutdown handles graceful shutdown of all components.
func shutdown(
	server *http.Server,
	deps *Infrastructure,
	workers map[string]*workerControl,
	cronScheduler *cronjobs.Scheduler,
	cfg *config.AppConfig,
) {
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	sig := <-sigCh
	logger.Info("shutdown signal received", zap.String("signal", sig.String()))

	// Parse shutdown timeout
	shutdownTimeout := 30 * time.Second
	if cfg.App.ShutdownTimeout != "" {
		if d, err := time.ParseDuration(cfg.App.ShutdownTimeout); err == nil {
			shutdownTimeout = d
		}
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer shutdownCancel()

	// Stop accepting new HTTP requests
	logger.Info("shutting down HTTP server...")
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("HTTP server shutdown error", zap.Error(err))
	} else {
		logger.Info("HTTP server stopped")
	}

	// Stop cron scheduler
	if cronScheduler != nil {
		cronScheduler.Stop()
		logger.Info("cron scheduler stopped")
	}

	// Stop all workers
	for name, wc := range workers {
		logger.Info("stopping worker", zap.String("worker", name))
		wc.Stop()
		logger.Info("worker stopped", zap.String("worker", name))
	}

	logger.Info("graceful shutdown complete")
}

// shutdownInfrastructure closes all infrastructure connections.
func shutdownInfrastructure(deps *Infrastructure) {
	if deps.ProductClient != nil {
		_ = deps.ProductClient.Close()
	}
	if deps.KafkaPublisher != nil {
		_ = deps.KafkaPublisher.Close()
	}
	if deps.RedisClient != nil {
		_ = deps.RedisClient.Close()
	}
	if deps.PGDB != nil {
		if sqlDB, err := deps.PGDB.DB(); err == nil {
			_ = sqlDB.Close()
		}
	}
}

// initLogger creates a zap logger based on configuration.
func initLogger(level, format string) *zap.Logger {
	var zapLevel zapcore.Level
	if err := zapLevel.UnmarshalText([]byte(level)); err != nil {
		zapLevel = zapcore.InfoLevel
	}

	var zapCfg zap.Config
	if format == "json" {
		zapCfg = zap.NewProductionConfig()
	} else {
		zapCfg = zap.NewDevelopmentConfig()
	}
	zapCfg.Level = zap.NewAtomicLevelAt(zapLevel)
	zapCfg.EncoderConfig.TimeKey = "timestamp"
	zapCfg.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

	l, err := zapCfg.Build()
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to build logger: %v\n", err)
		os.Exit(1)
	}
	return l
}

// parseDuration parses a duration string.
func parseDuration(s string) time.Duration {
	if s == "" {
		return 30 * time.Minute
	}
	d, err := time.ParseDuration(s)
	if err != nil {
		return 30 * time.Minute
	}
	return d
}
