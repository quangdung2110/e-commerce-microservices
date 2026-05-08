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

	// Initialize dependencies (placeholder for Module 2-6)
	_, err = InitializeDependencies(cfg, redisClient, logger)
	if err != nil {
		logger.Fatal("Failed to initialize dependencies", zap.Error(err))
	}

	// Start HTTP server
	server := &http.Server{
		Addr:    fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port),
		Handler: nil, // Will be set when implementing M3
	}

	// Graceful shutdown
	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
		<-sigCh

		logger.Info("Shutting down...")

		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		if err := server.Shutdown(ctx); err != nil {
			logger.Error("Server shutdown error", zap.Error(err))
		}
	}()

	logger.Info("Cart service started successfully",
		zap.String("address", server.Addr),
	)
}

// InitializeDependencies initializes all dependencies
// This is a placeholder for Module 2-6 implementation
func InitializeDependencies(cfg *config.Config, redisClient *redis.Client, logger *zap.Logger) (interface{}, error) {
	// TODO: Initialize all dependencies
	// - Module 2: Redis Lua Core
	// - Module 3: REST API Layer
	// - Module 4: Outbox Relay Worker
	// - Module 5: Postgres Sync Worker
	// - Module 6: Background Workers

	return struct{}{}, nil
}
