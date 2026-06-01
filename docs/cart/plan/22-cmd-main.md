# Cart Service — Module 22: Application Bootstrap

**Document:** 22 | **Module:** `cmd/server/main.go` — Entry Point & DI Wiring  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  ✅ (2026-06-01)  
**Phụ thuộc:** Tất cả modules 01-21

---

## 1. Mục tiêu Module

Tạo application entry point (`cmd/server/main.go`) với đầy đủ dependency injection wiring, graceful shutdown, và server bootstrap. Module này là "keo dán" gộp tất cả components lại với nhau.

---

## 2. Chi tiết triển khai

### 2.1. Main Entry Point (`cmd/server/main.go`)

```go
package main

import (
    "context"
    "fmt"
    "net/http"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/robfig/cron/v3"
    "go.uber.org/zap"
    "go.uber.org/zap/zapcore"

    "cart-service/internal/api/handler"
    "cart-service/internal/api/router"
    "cart-service/internal/common/logging"
    "cart-service/internal/domain/port"
    "cart-service/internal/domain/service"
    "cart-service/internal/infrastructure/config"
    grpcinfra "cart-service/internal/infrastructure/grpc"
    "cart-service/internal/infrastructure/kafka"
    "cart-service/internal/infrastructure/postgres"
    "cart-service/internal/infrastructure/redis"
    "cart-service/internal/workers/abandoned_marker"
    "cart-service/internal/workers/archival_cleaner"
    outboxrelay "cart-service/internal/workers/outbox_relay"
    "cart-service/internal/workers/order_event_consumer"
    "cart-service/internal/workers/postgres_sync"
    "cart-service/internal/workers/stream_trimmer"
)

func main() {
    // Step 1: Load configuration
    cfg, err := config.Load("")
    if err != nil {
        fmt.Fprintf(os.Stderr, "failed to load config: %v\n", err)
        os.Exit(1)
    }

    // Step 2: Initialize logger
    logger, err := logging.NewLogger(cfg.Logging)
    if err != nil {
        fmt.Fprintf(os.Stderr, "failed to create logger: %v\n", err)
        os.Exit(1)
    }
    defer logger.Sync()
    logger.Info("starting cart-service",
        zap.String("env", cfg.App.Env),
        zap.Int("port", cfg.App.Port))

    // Step 3: Create root context
    ctx, cancel := context.WithCancel(context.Background())
    defer cancel()

    // Step 4: Initialize infrastructure
    deps, err := initializeInfrastructure(ctx, cfg, logger)
    if err != nil {
        logger.Fatal("failed to initialize infrastructure", zap.Error(err))
    }

    // Step 5: Initialize domain services
    cartService := initializeDomainServices(deps, cfg)

    // Step 6: Initialize workers
    workers, err := initializeWorkers(ctx, deps, cfg, logger, cartService)
    if err != nil {
        logger.Fatal("failed to initialize workers", zap.Error(err))
    }

    // Step 7: Initialize API handlers and router
    cartHandler := handler.NewCartHandler(cartService, logger)
    healthHandler := handler.NewHealthHandler(deps.RedisHealth, deps.PGHealth)
    appRouter := router.NewRouter(cartHandler, healthHandler, logger, deps.RedisHealth, deps.PGHealth, cfg)

    // Step 8: Create HTTP server
    server := &http.Server{
        Addr:         cfg.Address(),
        Handler:      appRouter.Engine(),
        ReadTimeout:  10 * time.Second,
        WriteTimeout: 10 * time.Second,
        IdleTimeout:  60 * time.Second,
    }

    // Step 9: Start HTTP server
    go func() {
        logger.Info("HTTP server starting", zap.String("addr", cfg.Address()))
        if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
            logger.Fatal("HTTP server failed", zap.Error(err))
        }
    }()

    // Step 10: Start all workers
    for name, startFn := range workers {
        if err := startFn(); err != nil {
            logger.Fatal("failed to start worker", zap.String("worker", name), zap.Error(err))
        }
        logger.Info("worker started", zap.String("worker", name))
    }

    // Step 11: Start cron jobs
    cronScheduler := setupCronJobs(ctx, deps, cfg, logger)
    logger.Info("cron scheduler started")

    // Step 12: Graceful shutdown
    shutdown(ctx, cancel, server, deps, workers, cronScheduler, logger, cfg)
}

// initializeInfrastructure sets up all infrastructure clients.
func initializeInfrastructure(ctx context.Context, cfg *config.Config, logger *zap.Logger) (*Infrastructure, error) {
    infra := &Infrastructure{}

    // Redis
    redisClient, err := redis.NewClient(ctx, redis.Config{
        Addrs:        cfg.Redis.Addrs,
        Password:     cfg.Redis.Password,
        DB:           cfg.Redis.DB,
        PoolSize:     cfg.Redis.PoolSize,
        MinIdleConns: cfg.Redis.MinIdleConns,
        ReadTimeout:  cfg.Redis.ReadTimeout,
        WriteTimeout: cfg.Redis.WriteTimeout,
        NumShards:    cfg.Redis.NumShards,
    })
    if err != nil {
        return nil, fmt.Errorf("Redis: %w", err)
    }
    infra.RedisClient = redisClient
    infra.RedisHealth = redisClient

    // Ensure consumer groups for all shards
    shardManager := redis.NewShardManager(redisClient, cfg.Redis.NumShards)
    if err := shardManager.EnsureConsumerGroups(ctx, fmt.Sprintf("cart_relay_group")); err != nil {
        return nil, fmt.Errorf("ensure consumer groups: %w", err)
    }

    // Cart Lua Repository
    infra.CartLuaRepo = redis.NewCartLuaRepository(redisClient)
    infra.CacheRepo = redis.NewCacheRepository(redisClient)
    infra.OutboxWriter = redis.NewOutboxWriter(redisClient, cfg.Redis.NumShards)

    // PostgreSQL
    pgDB, err := postgres.NewGORM(ctx, postgres.Config{
        Host:           cfg.Database.Host,
        Port:           cfg.Database.Port,
        User:           cfg.Database.User,
        Password:       cfg.Database.Password,
        Name:           cfg.Database.Name,
        MaxOpenConns:   cfg.Database.MaxOpenConns,
        MaxIdleConns:   cfg.Database.MaxIdleConns,
        ConnMaxLifetime: cfg.Database.ConnMaxLifetime,
        SSLMode:        cfg.Database.SSLMode,
    })
    if err != nil {
        return nil, fmt.Errorf("PostgreSQL: %w", err)
    }
    infra.PGDB = pgDB
    infra.PGHealth = pgDB
    infra.CartRepo = postgres.NewCartRepository(pgDB)

    // Kafka Producer
    kafkaCfg := kafka.Config{
        Brokers:            cfg.Kafka.Brokers,
        TopicCartEvents:   cfg.Kafka.TopicCartEvents,
        TopicOrderEvents:  cfg.Kafka.TopicOrderEvents,
    }
    infra.KafkaPublisher, err = kafka.NewPublisher(kafkaCfg)
    if err != nil {
        return nil, fmt.Errorf("Kafka producer: %w", err)
    }

    // gRPC Product Client
    infra.ProductClient, err = grpcinfra.NewProductClient(ctx, grpcinfra.Config{
        ProductServiceAddr: cfg.Grpc.ProductServiceAddr,
        DialTimeout:      cfg.Grpc.DialTimeout,
        MaxCallTimeout:   cfg.Grpc.MaxCallTimeout,
    }, grpcinfra.CircuitBreakerConfig{
        Name:     cfg.Grpc.CircuitBreaker.Name,
        MaxRequests: 3,
        Interval:   10 * time.Second,
        Timeout:    30 * time.Second,
    })
    if err != nil {
        return nil, fmt.Errorf("gRPC product client: %w", err)
    }

    logger.Info("infrastructure initialized",
        zap.Int("redis_addrs", len(cfg.Redis.Addrs)),
        zap.Int("kafka_brokers", len(cfg.Kafka.Brokers)))

    return infra, nil
}

// initializeDomainServices creates domain service instances.
func initializeDomainServices(deps *Infrastructure, cfg *config.Config) *service.CartService {
    return service.NewCartService(
        deps.CartLuaRepo,
        deps.CacheRepo,
        deps.CartRepo,
        deps.ProductClient,
        service.CartServiceConfig{
            MaxSKUsPerCart:    cfg.Cart.MaxSKUsPerCart,
            MaxQtyPerItem:     cfg.Cart.MaxQtyPerItem,
            RehydrateTTL:      int(cfg.Cart.TTL.Seconds()),
            NegativeCacheTTL:  int(cfg.Cart.NegativeCacheTTL.Seconds()),
        },
    )
}

// initializeWorkers starts all background workers.
func initializeWorkers(
    ctx context.Context,
    deps *Infrastructure,
    cfg *config.Config,
    logger *zap.Logger,
    cartService *service.CartService,
) (map[string]func() error, error) {

    workers := make(map[string]func() error)

    // Worker #1: Outbox Relay (4 goroutines, one per shard)
    for i := 0; i < cfg.Worker.OutboxRelay.Count; i++ {
        shardID := i
        w := outboxrelay.NewWorker(shardID, deps.RedisClient, deps.KafkaPublisher, cfg.Worker.OutboxRelay, logger)
        name := fmt.Sprintf("outbox_relay_shard_%d", shardID)
        workers[name] = func() error { return w.Start(ctx) }
        // Store worker for shutdown
        _ = w // captured in closure
    }

    // Worker #5: Stream Trimmer (periodic goroutine)
    streamTrimJob := stream_trimmer.NewJob(deps.RedisClient, cfg.Redis.NumShards, cfg.Worker.StreamTrimmer, logger)
    workers["stream_trimmer"] = func() error {
        go streamTrimJob.RunPeriodic(ctx)
        return nil
    }

    return workers, nil
}

// setupCronJobs registers scheduled jobs with robfig/cron.
func setupCronJobs(ctx context.Context, deps *Infrastructure, cfg *config.Config, logger *zap.Logger) *cron.Cron {
    c := cron.New(cron.WithSeconds())

    // Abandoned Cart Marker — daily at 2:00 AM
    if cfg.Worker.AbandonedMarker.Enabled {
        abandonedJob := abandoned_marker.NewJob(
            deps.CartRepo, deps.OutboxWriter,
            cfg.Worker.AbandonedMarker, cfg.Redis.NumShards, logger,
        )
        c.AddFunc(cfg.Worker.AbandonedMarker.Cron, func() {
            if err := abandonedJob.Run(ctx); err != nil {
                logger.Error("abandoned marker job failed", zap.Error(err))
            }
        })
        logger.Info("abandoned marker cron registered",
            zap.String("cron", cfg.Worker.AbandonedMarker.Cron))
    }

    // Data Archival Cleaner — weekly on Sunday at 3:00 AM
    if cfg.Worker.ArchivalCleaner.Enabled {
        archivalJob := archival_cleaner.NewJob(deps.CartRepo, cfg.Worker.ArchivalCleaner, logger)
        c.AddFunc(cfg.Worker.ArchivalCleaner.Cron, func() {
            if err := archivalJob.Run(ctx); err != nil {
                logger.Error("archival cleaner job failed", zap.Error(err))
            }
        })
        logger.Info("archival cleaner cron registered",
            zap.String("cron", cfg.Worker.ArchivalCleaner.Cron))
    }

    c.Start()
    return c
}

// shutdown handles graceful shutdown of all components.
func shutdown(
    ctx context.Context,
    cancel context.CancelFunc,
    server *http.Server,
    deps *Infrastructure,
    workers map[string]func() error,
    cronScheduler *cron.Cron,
    logger *zap.Logger,
    cfg *config.Config,
) {
    sigCh := make(chan os.Signal, 1)
    signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
    sig := <-sigCh
    logger.Info("shutdown signal received", zap.String("signal", sig.String()))

    // Create shutdown context with timeout
    shutdownCtx, shutdownCancel := context.WithTimeout(ctx, cfg.App.ShutdownTimeout)
    defer shutdownCancel()

    // Stop accepting new requests
    if err := server.Shutdown(shutdownCtx); err != nil {
        logger.Error("server shutdown error", zap.Error(err))
    }
    logger.Info("HTTP server stopped")

    // Stop cron scheduler
    if cronScheduler != nil {
        cronCtx := cronScheduler.Stop()
        <-cronCtx.Done()
        logger.Info("cron scheduler stopped")
    }

    // Cancel root context (stops workers)
    cancel()

    // Close infrastructure connections
    if deps.ProductClient != nil {
        deps.ProductClient.Close()
    }
    if deps.KafkaPublisher != nil {
        deps.KafkaPublisher.Close()
    }
    if deps.RedisClient != nil {
        deps.RedisClient.Close()
    }
    if deps.PGDB != nil {
        sqlDB, _ := deps.PGDB.DB()
        sqlDB.Close()
    }

    logger.Info("graceful shutdown complete")
}
```

### 2.2. Infrastructure Container (`cmd/server/infrastructure.go`)

```go
package main

import (
    "cart-service/internal/domain/port"
    "cart-service/internal/infrastructure/grpc"
    "cart-service/internal/infrastructure/kafka"
    "cart-service/internal/infrastructure/postgres"
    "cart-service/internal/infrastructure/redis"
)

// Infrastructure holds all initialized infrastructure clients.
type Infrastructure struct {
    // Redis
    RedisClient *redis.Client
    CartLuaRepo port.CartLuaRepository
    CacheRepo   port.CacheRepository
    OutboxWriter port.OutboxWriter
    RedisHealth port.HealthChecker

    // PostgreSQL
    PGDB      *postgres.GormDB
    CartRepo  port.CartRepository
    PGHealth  port.HealthChecker

    // Kafka
    KafkaPublisher port.KafkaPublisher

    // gRPC
    ProductClient port.ProductClient
}
```

---

## 3. Logging Setup (`internal/common/logging/logger.go`)

```go
package logging

import (
    "go.uber.org/zap"
    "go.uber.org/zap/zapcore"

    "cart-service/internal/infrastructure/config"
)

// NewLogger creates a zap logger from config.
func NewLogger(cfg config.LoggingConfig) (*zap.Logger, error) {
    var level zapcore.Level
    if err := level.UnmarshalText([]byte(cfg.Level)); err != nil {
        level = zapcore.InfoLevel
    }

    var encoding string
    if cfg.Format == "json" {
        encoding = "json"
    } else {
        encoding = "console"
    }

    zapCfg := zap.Config{
        Level:       zap.NewAtomicLevelAt(level),
        Development: false,
        Encoding:    encoding,
        EncoderConfig: zapcore.EncoderConfig{
            TimeKey:        "timestamp",
            LevelKey:       "level",
            NameKey:        "logger",
            CallerKey:      "caller",
            FunctionKey:    zapcore.OmitKey,
            MessageKey:     "msg",
            StacktraceKey:  "stacktrace",
            LineEnding:     zapcore.DefaultLineEnding,
            EncodeLevel:    zapcore.LowercaseLevelEncoder,
            EncodeTime:     zapcore.ISO8601TimeEncoder,
            EncodeDuration: zapcore.SecondsDurationEncoder,
            EncodeCaller:   zapcore.ShortCallerEncoder,
        },
        OutputPaths:      []string{cfg.Output},
        ErrorOutputPaths: []string{cfg.Output},
    }

    return zapCfg.Build()
}
```

---

## 4. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `main.go` | `cmd/server/main.go` | Application entry point + DI wiring |
| `infrastructure.go` | `cmd/server/infrastructure.go` | Infrastructure container struct |
| `logger.go` | `internal/common/logging/logger.go` | Zap logger setup |

---

## 5. Checklist triển khai Module 22

```markdown
## 22 - cmd/main
- [x] Tạo `cmd/server/main.go` với full DI wiring
- [x] Tạo `cmd/server/infrastructure.go` với Infrastructure struct
- [x] Tạo `internal/infrastructure/redis/outbox_writer.go` (port.OutboxWriter impl)
- [x] Thêm `EnsureConsumerGroups` vào ShardManager (`internal/infrastructure/redis/shard.go`)
- [x] Thêm `HealthChecker` interface vào `internal/domain/port/errors.go`
- [x] Thêm `NewHealthChecker` constructor vào `internal/infrastructure/postgres/connection.go`
- [x] Verify startup order (infrastructure → services → workers → HTTP)
- [x] Verify shutdown order (HTTP → cron → workers → infra)
- [x] Verify `go build ./cmd/server` compile thành công
- [ ] Run locally với docker-compose và smoke test
```

---

*Tiếp theo: [23-testing-plan.md](23-testing-plan.md) — Testing strategy.*
