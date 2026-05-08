package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/api"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
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

	// Initialize DB client (placeholder)
	// In production, this would be a real PostgreSQL client
	// deps.DB = NewDBClient(cfg.Postgres, logger)

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

	// Graceful shutdown
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh

	logger.Info("Shutting down...")

	// Create shutdown context
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Shutdown HTTP server
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("Server shutdown error", zap.Error(err))
	}

	// Close Product Service client
	if err := productClient.Close(); err != nil {
		logger.Error("Failed to close Product Service client", zap.Error(err))
	}

	logger.Info("Cart service shutdown complete")
}
