package main

import (
	"context"
	"database/sql"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
	"strconv"

	goredis "github.com/redis/go-redis/v9"
	_ "github.com/lib/pq"
	"go.uber.org/zap"

	"github.com/ecommerce-platform/go/services/cart-service/internal/api/handler"
	"github.com/ecommerce-platform/go/services/cart-service/internal/api/router"
	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure"
	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure/config"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/entity"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/port"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/service"
	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure/kafka"
	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure/redis"
	"github.com/ecommerce-platform/go/services/cart-service/internal/workers/cronjobs"
	"github.com/ecommerce-platform/go/services/cart-service/internal/workers/order_consumer"
	"github.com/ecommerce-platform/go/services/cart-service/internal/workers/outbox_relay"
	"github.com/ecommerce-platform/go/services/cart-service/internal/workers/postgres_sync"
)

func main() {
	logger, err := zap.NewProduction()
	if err != nil {
		fmt.Printf("Failed to initialize logger: %v\n", err)
		os.Exit(1)
	}
	defer logger.Sync()

	cfg, err := config.LoadDefault()
	if err != nil {
		logger.Fatal("Failed to load configuration", zap.Error(err))
	}

	logger.Info("Starting cart-service",
		zap.String("env", cfg.App.Env),
		zap.Int("port", cfg.Server.Port),
	)

	redisOpts := &goredis.Options{
		Addr:         cfg.Redis.Addresses[0],
		Password:     cfg.Redis.Password,
		DB:           cfg.Redis.DB,
		PoolSize:     cfg.Redis.PoolSize,
		DialTimeout:  config.ParseDuration(cfg.Redis.DialTimeout),
		ReadTimeout:  config.ParseDuration(cfg.Redis.ReadTimeout),
		WriteTimeout: config.ParseDuration(cfg.Redis.WriteTimeout),
	}

	redisClient := goredis.NewClient(redisOpts)
	defer redisClient.Close()

	ctx := context.Background()
	if err := redisClient.Ping(ctx).Err(); err != nil {
		logger.Fatal("Failed to connect to Redis", zap.Error(err))
	}
	logger.Info("Connected to Redis", zap.String("addr", cfg.Redis.Addresses[0]))

	var db *sql.DB
	if cfg.Postgres.DSN != "" {
		db, err = sql.Open("postgres", cfg.Postgres.DSN)
		if err != nil {
			logger.Fatal("Failed to open database connection", zap.Error(err))
		}

		db.SetMaxOpenConns(cfg.Postgres.MaxOpenConns)
		db.SetMaxIdleConns(cfg.Postgres.MaxIdleConns)
		if cfg.Postgres.ConnMaxLifetime != "" {
			lifetime, _ := time.ParseDuration(cfg.Postgres.ConnMaxLifetime)
			db.SetConnMaxLifetime(lifetime)
		}

		if err := db.Ping(); err != nil {
			logger.Warn("Failed to connect to database, workers requiring DB will be disabled", zap.Error(err))
			db = nil
		} else {
			logger.Info("Connected to PostgreSQL")
		}
	}

	kafkaProducer, err := kafka.NewKafkaProducer(
		cfg.Kafka.Brokers,
		logger,
		fmt.Sprintf("%d", cfg.Kafka.Producer.Acks),
		cfg.Kafka.Producer.Retries,
	)
	if err != nil {
		logger.Fatal("Failed to create Kafka producer", zap.Error(err))
	}
	defer kafkaProducer.Close()

	keyBuilder := redis.NewCartKeyBuilder()
	ttlSeconds, _ := strconv.Atoi(cfg.Cart.TTL)
	cartRepo := redis.NewCartRepository(
		redisClient,
		keyBuilder,
		cfg.Cart.MaxQuantityPerItem,
		cfg.Cart.MaxUniqueSKUs,
		ttlSeconds,
	)
	if err := cartRepo.LoadScripts(ctx); err != nil {
		logger.Fatal("Failed to load Lua scripts", zap.Error(err))
	}
	logger.Info("Lua scripts loaded successfully")

	intentIndex := redis.NewIntentIndexManager(redisClient, keyBuilder)
	negCache := redis.NewNegativeCacheManager(redisClient, keyBuilder, cfg.Cart.NegativeCacheTTL)
	_ = redis.NewIdempotencyManager(redisClient, keyBuilder)

	productClient := newProductClient(cfg, logger)

	var postgresDB port.PostgresDB
	if db != nil {
		postgresDB = newPostgresDB(db, logger)
	}

	cartService := service.NewCartService(
		cartRepo,
		productClient,
		negCache,
		intentIndex,
		keyBuilder,
		postgresDB,
	)

	cartHandler := handler.NewCartHandler(cartService, logger)
	healthHandler := handler.NewHealthHandler(redisClient, db, logger)

	httpRouter := router.NewRouter(cartHandler, healthHandler, logger)

	server := &http.Server{
		Addr:    fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port),
		Handler: httpRouter.Handler(),
	}

	go func() {
		logger.Info("HTTP server starting", zap.String("addr", server.Addr))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Failed to start HTTP server", zap.Error(err))
		}
	}()

	workerCtx, workerCancel := context.WithCancel(context.Background())

	outboxWorker := outbox_relay.NewWorker(redisClient, kafkaProducer, logger, cfg)
	if err := outboxWorker.Start(workerCtx); err != nil {
		logger.Fatal("Failed to start outbox relay worker", zap.Error(err))
	}
	logger.Info("Outbox relay worker started")

	streamTrimmer := cronjobs.NewStreamTrimmer(redisClient, logger, cfg.Workers.StreamTrimmer)
	if err := streamTrimmer.Start(workerCtx); err != nil {
		logger.Fatal("Failed to start stream trimmer", zap.Error(err))
	}
	logger.Info("Stream trimmer started")

	dlqHandler := outbox_relay.NewDLQHandler(redisClient, logger, cfg.Workers.DLQHandler)
	if err := dlqHandler.Start(workerCtx); err != nil {
		logger.Fatal("Failed to start DLQ handler", zap.Error(err))
	}
	logger.Info("DLQ handler started")

	if db != nil {
		abandonedMarker := cronjobs.NewAbandonedCartMarker(
			redisClient,
			db,
			logger,
			cfg.Workers.AbandonedMarker,
			cfg.Cart,
		)
		if err := abandonedMarker.Start(workerCtx); err != nil {
			logger.Fatal("Failed to start abandoned cart marker", zap.Error(err))
		}
		logger.Info("Abandoned cart marker started")
	}

	if db != nil {
		archivalCleaner := cronjobs.NewDataArchivalCleaner(
			redisClient,
			db,
			logger,
			cfg.Workers.ArchivalCleaner,
			cfg.Cart,
		)
		if err := archivalCleaner.Start(workerCtx); err != nil {
			logger.Fatal("Failed to start archival cleaner", zap.Error(err))
		}
		logger.Info("Archival cleaner started")
	}

	orderConsumer := order_consumer.NewOrderEventConsumer(redisClient, logger, cfg)
	if err := orderConsumer.Start(workerCtx); err != nil {
		logger.Fatal("Failed to start order event consumer", zap.Error(err))
	}
	logger.Info("Order event consumer started")

	var postgresSyncWorker *postgres_sync.Worker
	if db != nil {
		var err error
		postgresSyncWorker, err = postgres_sync.NewWorker(cfg, logger, redisClient)
		if err != nil {
			logger.Fatal("Failed to create postgres sync worker", zap.Error(err))
		}
		if err := postgresSyncWorker.Start(workerCtx); err != nil {
			logger.Fatal("Failed to start postgres sync worker", zap.Error(err))
		}
		logger.Info("Postgres sync worker started")
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh

	logger.Info("Shutting down...")
	workerCancel()

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	outboxWorker.Stop()
	streamTrimmer.Stop()
	dlqHandler.Stop()
	orderConsumer.Stop()
	if postgresSyncWorker != nil {
		postgresSyncWorker.Stop()
	}

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("Server shutdown error", zap.Error(err))
	}

	if err := productClient.Close(); err != nil {
		logger.Error("Failed to close Product Service client", zap.Error(err))
	}

	if db != nil {
		db.Close()
	}

	logger.Info("Cart service shutdown complete")
}

func newProductClient(cfg *config.Config, logger *zap.Logger) port.ProductServiceClient {
	// First create the base client
	var baseClient port.ProductServiceClient
	client, err := infrastructure.NewProductServiceClient(cfg.ProductService, logger)
	if err != nil {
		logger.Warn("Failed to connect to Product Service, running in mock mode",
			zap.String("address", cfg.ProductService.Address),
			zap.Error(err),
		)
		// Return a mock client that returns mock data
		baseClient = &mockProductClient{logger: logger}
	} else {
		baseClient = client
	}

	// Wrap with Circuit Breaker
	timeoutMs := 200
	if cfg.ProductService.Timeout != "" {
		timeoutMs = config.ParseDuration(cfg.ProductService.Timeout).Milliseconds()
	}
	cbConfig := service.CircuitBreakerConfig("product-service", int(timeoutMs))
	return service.NewProductServiceClientWithCB(baseClient, cbConfig, logger)
}

// mockProductClient provides mock data when Product Service is unavailable
type mockProductClient struct {
	logger *zap.Logger
}

func (m *mockProductClient) GetProductSku(ctx context.Context, skuID string) (*entity.ProductInfo, error) {
	m.logger.Debug("Mock GetProductSku", zap.String("sku_id", skuID))
	return &entity.ProductInfo{
		SkuID:            skuID,
		ProductID:        "mock-product-id",
		ProductName:      "Mock Product",
		SkuStatus:        "ACTIVE",
		ProductStatus:    "ACTIVE",
		OriginalPrice:    100000,
		PromotionalPrice: 90000,
		DisplayQuantity:  100,
		ImageURL:        "https://cdn.example.com/mock.jpg",
		VariantInfo:     "Default Variant",
		IsAvailable:     true,
	}, nil
}

func (m *mockProductClient) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.BatchProductSkusResponse, error) {
	m.logger.Debug("Mock GetBatchProductSkus", zap.Int("count", len(skuIDs)))
	products := make(map[string]*entity.ProductInfo)
	for _, skuID := range skuIDs {
		products[skuID] = &entity.ProductInfo{
			SkuID:            skuID,
			ProductID:        "mock-product-id",
			ProductName:      "Mock Product",
			OriginalPrice:    100000,
			PromotionalPrice: 90000,
			CurrentPrice:     90000,
			DisplayQuantity:  100,
			ImageURL:         "https://cdn.example.com/mock.jpg",
			VariantInfo:      "Default Variant",
			IsAvailable:      true,
			SkuStatus:       "ACTIVE",
			ProductStatus:   "ACTIVE",
		}
	}
	return &entity.BatchProductSkusResponse{Products: products}, nil
}

func (m *mockProductClient) Close() error {
	return nil
}

func newPostgresDB(db *sql.DB, logger *zap.Logger) port.PostgresDB {
	return infrastructure.NewPostgresDB(db)
}
