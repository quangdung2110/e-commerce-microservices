package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/infrastructure/config"
	redisinfra "cart-service/internal/infrastructure/redis"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

// Global logger instance. Initialized in initLogger().
var logger *zap.Logger

func main() {
	if err := run(); err != nil {
		log.Fatalf("server terminated: %v", err)
	}
}

func run() error {
	// ---------------------------------------------------------------------------
	// 1. Load configuration
	// ---------------------------------------------------------------------------
	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		configPath = "configs/application.yaml"
	}

	cfg, err := config.Load(configPath)
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}

	// ---------------------------------------------------------------------------
	// 2. Initialize logger
	// ---------------------------------------------------------------------------
	logger = initLogger(cfg.Logging.Level, cfg.Logging.Format)
	defer logger.Sync()

	logger.Info("starting cart-service",
		zap.String("env", cfg.App.Env),
		zap.String("app", cfg.App.Name),
	)

	// ---------------------------------------------------------------------------
	// 3. Create context with cancellation for graceful shutdown
	// ---------------------------------------------------------------------------
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// ---------------------------------------------------------------------------
	// MODULE 06: Initialize Redis infrastructure
	// ---------------------------------------------------------------------------
	// 3a. Build Redis configuration from app config
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

	// 3b. Create Redis client
	redisClient, err := redisinfra.NewClient(redisCfg)
	if err != nil {
		return fmt.Errorf("create redis client: %w", err)
	}
	defer func() {
		if err := redisClient.Close(); err != nil {
			logger.Warn("failed to close redis client", zap.Error(err))
		}
	}()

	// 3c. Ping Redis to verify connectivity
	pingCtx, pingCancel := context.WithTimeout(ctx, 5*time.Second)
	defer pingCancel()
	if err := redisClient.Ping(pingCtx); err != nil {
		return fmt.Errorf("redis ping failed: %w", err)
	}
	logger.Info("redis connection established",
		zap.Strings("addrs", cfg.Redis.Addrs),
		zap.Int("pool_size", cfg.Redis.PoolSize),
		zap.Int("num_shards", cfg.Redis.NumShards),
	)

	// 3d. Create ShardManager for stream sharding
	shardManager := redisinfra.NewShardManager(cfg.Redis.NumShards)
	logger.Info("redis shard manager initialized",
		zap.Uint32("num_shards", shardManager.NumShards()),
	)

	// 3e. Create KeyBuilder for Redis key construction
	keyBuilder := keybuilder.New(uint32(cfg.Redis.NumShards))

	// 3f. Create CartLuaRepo — the primary write adapter
	cartLuaRepo := redisinfra.NewCartLuaRepo(redisClient, shardManager)

	// 3g. Create CacheRepo — simple read/write operations
	cacheRepo := redisinfra.NewCacheRepo(redisClient, keyBuilder, cfg.Cart.NegativeCacheTTL)

	logger.Info("module 06 (Redis infrastructure) initialized",
		zap.Int("cart_ttl_seconds", cfg.Cart.TTL),
		zap.Int("max_skus_per_cart", cfg.Cart.MaxSKUsPerCart),
		zap.Int("max_qty_per_item", cfg.Cart.MaxQtyPerItem),
		zap.Int("negative_cache_ttl_seconds", cfg.Cart.NegativeCacheTTL),
		zap.Int("rehydrate_ttl_seconds", cfg.Cart.RehydrateTTL),
	)

	// Suppress unused-variable warnings — these are used by Module 11 (CartService domain)
	_, _ = cartLuaRepo, cacheRepo

	// ---------------------------------------------------------------------------
	// Modules 07+ (future): PostgreSQL, Kafka, gRPC, Domain Service, API, Workers
	// ---------------------------------------------------------------------------
	// TODO(module 07): Initialize PostgreSQL connection + CartRepository adapter
	// TODO(module 08): Initialize Kafka producer + publisher adapter
	// TODO(module 09): Initialize gRPC product client + circuit breaker
	// TODO(module 11): Initialize CartService domain service
	// TODO(module 12-14): Initialize API layer (router, handlers, middleware)
	// TODO(module 15-21): Initialize background workers
	// TODO(module 22): Start HTTP server (replacing this dev health endpoint)

	// Development health check server on a separate port
	go func() {
		mux := http.NewServeMux()
		mux.HandleFunc("/healthz", healthHandler(redisClient))
		addr := fmt.Sprintf("%s:%d", "0.0.0.0", cfg.App.Port+1)
		logger.Info("health check server starting", zap.String("addr", addr))
		if err := http.ListenAndServe(addr, mux); err != nil && err != http.ErrServerClosed {
			logger.Error("health check server failed", zap.Error(err))
		}
	}()

	// ---------------------------------------------------------------------------
	// 4. Graceful shutdown handling
	// ---------------------------------------------------------------------------
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-quit:
		logger.Info("received shutdown signal", zap.String("signal", sig.String()))
		cancel()
	case <-ctx.Done():
	}

	// Shutdown timeout
	shutdownTimeout := 30 * time.Second
	if cfg.App.ShutdownTimeout != "" {
		if d, err := time.ParseDuration(cfg.App.ShutdownTimeout); err == nil {
			shutdownTimeout = d
		}
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer shutdownCancel()

	// TODO(module 22): Gracefully stop HTTP server
	// TODO(module 15-21): Gracefully stop all workers

	<-shutdownCtx.Done()
	if shutdownCtx.Err() == context.DeadlineExceeded {
		logger.Warn("graceful shutdown timed out", zap.Duration("timeout", shutdownTimeout))
	}

	logger.Info("cart-service stopped")
	return nil
}

// initLogger creates a zap logger based on configuration.
func initLogger(level, format string) *zap.Logger {
	var zapLevel zapcore.Level
	if err := zapLevel.UnmarshalText([]byte(level)); err != nil {
		zapLevel = zapcore.InfoLevel
	}

	var config zap.Config
	if format == "json" {
		config = zap.NewProductionConfig()
	} else {
		config = zap.NewDevelopmentConfig()
	}
	config.Level = zap.NewAtomicLevelAt(zapLevel)
	config.EncoderConfig.TimeKey = "timestamp"
	config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder

	logger, err := config.Build()
	if err != nil {
		log.Fatalf("failed to build logger: %v", err)
	}
	return logger
}

// healthHandler exposes a /healthz endpoint for liveness probes.
func healthHandler(client *redisinfra.Client) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		if err := client.Ping(ctx); err != nil {
			http.Error(w, `{"status":"unhealthy","redis":"down"}`, http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"status":"healthy","redis":"up"}`))
	}
}
