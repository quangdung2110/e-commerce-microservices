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

	"github.com/redis/go-redis/v9"
	_ "github.com/lib/pq"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/api"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/workers/cronjobs"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/workers/order_consumer"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/workers/outbox_relay"
)

func main() {
	// Initialize logger
	logger, err := zap.NewProduction()
	if err != nil {
		fmt.Printf("Failed to initialize logger: %v\n", err)
		os.Exit(1)
	}
	defer logger.Sync()

	// Load configuration
	cfg, err := config.LoadDefault()
	if err != nil {
		logger.Fatal("Failed to load configuration", zap.Error(err))
	}

	logger.Info("Starting cart-service",
		zap.String("env", cfg.App.Env),
		zap.Int("port", cfg.Server.Port),
	)

	// Initialize Redis client
	redisOpts := &redis.Options{
		Addr:         cfg.Redis.Addresses[0],
		Password:     cfg.Redis.Password,
		DB:           cfg.Redis.DB,
		PoolSize:     cfg.Redis.PoolSize,
		DialTimeout:  config.ParseDuration(cfg.Redis.DialTimeout),
		ReadTimeout:  config.ParseDuration(cfg.Redis.ReadTimeout),
		WriteTimeout: config.ParseDuration(cfg.Redis.WriteTimeout),
	}

	redisClient := redis.NewClient(redisOpts)
	defer redisClient.Close()

	// Test Redis connection
	ctx := context.Background()
	if err := redisClient.Ping(ctx).Err(); err != nil {
		logger.Fatal("Failed to connect to Redis", zap.Error(err))
	}
	logger.Info("Connected to Redis", zap.String("addr", cfg.Redis.Addresses[0]))

	// Initialize database connection
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

		// Test DB connection
		if err := db.Ping(); err != nil {
			logger.Warn("Failed to connect to database, workers requiring DB will be disabled", zap.Error(err))
			db = nil
		} else {
			logger.Info("Connected to PostgreSQL")
		}
	}

	// Initialize Kafka producer
	kafkaProducer, err := outbox_relay.NewKafkaProducer(
		cfg.Kafka.Brokers,
		logger,
		cfg.Kafka.Producer.Acks,
		cfg.Kafka.Producer.Retries,
	)
	if err != nil {
		logger.Fatal("Failed to create Kafka producer", zap.Error(err))
	}
	defer kafkaProducer.Close()

	// Initialize API dependencies
	deps, err := api.Initialize(cfg, redisClient, logger)
	if err != nil {
		logger.Fatal("Failed to initialize API dependencies", zap.Error(err))
	}

	// Initialize Product Service client
	productClient := api.NewProductClient(
		api.ProductClientConfig{
			Address:        cfg.ProductService.Address,
			Timeout:        config.ParseDuration(cfg.ProductService.Timeout),
			MaxFailures:    cfg.ProductService.CircuitBreaker.MaxFailures,
			FailureTimeout: config.ParseDuration(cfg.ProductService.CircuitBreaker.FailureTimeout),
			HalfOpenMax:   cfg.ProductService.CircuitBreaker.HalfOpenMaxRequests,
		},
		logger,
	)
	deps.ProductClient = productClient

	// Create router
	router := api.NewRouter(deps, logger)

	// Start HTTP server
	server := &http.Server{
		Addr:    fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port),
		Handler: router.Handler(),
	}

	// Start server in goroutine
	go func() {
		logger.Info("HTTP server starting",
			zap.String("addr", server.Addr),
		)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Failed to start HTTP server", zap.Error(err))
		}
	}()

	// Start workers
	workerCtx, workerCancel := context.WithCancel(context.Background())

	// 1. Start Outbox Relay Worker
	outboxWorker := outbox_relay.NewWorker(redisClient, kafkaProducer, logger, cfg)
	if err := outboxWorker.Start(workerCtx); err != nil {
		logger.Fatal("Failed to start outbox relay worker", zap.Error(err))
	}
	logger.Info("Outbox relay worker started")

	// 2. Start Stream Trimmer
	streamTrimmer := cronjobs.NewStreamTrimmer(redisClient, logger, cfg.Workers.StreamTrimmer)
	if err := streamTrimmer.Start(workerCtx); err != nil {
		logger.Fatal("Failed to start stream trimmer", zap.Error(err))
	}
	logger.Info("Stream trimmer started")

	// 3. Start DLQ Handler
	dlqHandler := outbox_relay.NewDLQHandler(redisClient, logger, cfg.Workers.DLQHandler)
	if err := dlqHandler.Start(workerCtx); err != nil {
		logger.Fatal("Failed to start DLQ handler", zap.Error(err))
	}
	logger.Info("DLQ handler started")

	// 4. Start Abandoned Cart Marker (requires DB)
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

	// 5. Start Data Archival Cleaner (requires DB)
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

	// 6. Start Order Event Consumer
	orderConsumer := order_consumer.NewOrderEventConsumer(redisClient, logger, cfg)
	if err := orderConsumer.Start(workerCtx); err != nil {
		logger.Fatal("Failed to start order event consumer", zap.Error(err))
	}
	logger.Info("Order event consumer started")

	// Graceful shutdown
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh

	logger.Info("Shutting down...")

	// Cancel worker context
	workerCancel()

	// Create shutdown context
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Stop workers
	outboxWorker.Stop()
	streamTrimmer.Stop()
	dlqHandler.Stop()
	orderConsumer.Stop()

	// Shutdown HTTP server
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("Server shutdown error", zap.Error(err))
	}

	// Close Product Service client
	if err := productClient.Close(); err != nil {
		logger.Error("Failed to close Product Service client", zap.Error(err))
	}

	// Close database
	if db != nil {
		db.Close()
	}

	logger.Info("Cart service shutdown complete")
}
