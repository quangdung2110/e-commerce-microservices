// Command server runs User Service: the public HTTP API, the internal checkout
// snapshot API, the Auth event consumer and the scheduled workers.
//
// All four live in one binary and are switched on individually by
// configuration, so a deployment can scale the API, the consumer and the
// workers independently while shipping a single image
// (docs/user/architecture.md §5).
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	obs "github.com/ecommerce/observability"
	"log"
	"net/http"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/api/handler"
	"github.com/ecommerce/user-service/internal/api/router"
	"github.com/ecommerce/user-service/internal/common/clock"
	"github.com/ecommerce/user-service/internal/common/config"
	"github.com/ecommerce/user-service/internal/common/keybuilder"
	"github.com/ecommerce/user-service/internal/common/logging"
	"github.com/ecommerce/user-service/internal/domain/port"
	"github.com/ecommerce/user-service/internal/domain/service"
	"github.com/ecommerce/user-service/internal/infrastructure/kafka"
	"github.com/ecommerce/user-service/internal/infrastructure/postgres"
	redisadapter "github.com/ecommerce/user-service/internal/infrastructure/redis"
	"github.com/ecommerce/user-service/internal/workers"
	projectionreconciliation "github.com/ecommerce/user-service/internal/workers/projection_reconciliation"
	retentionpurge "github.com/ecommerce/user-service/internal/workers/retention_purge"
)

func main() {
	configPath := flag.String("config", "", "path to the configuration file")
	flag.Parse()

	if err := run(*configPath); err != nil {
		log.Fatalf("user-service: %v", err)
	}
}

func run(configPath string) error {
	cfg, err := config.Load(configPath)
	if err != nil {
		return err
	}

	logger, err := logging.New(cfg.Logging.Level, cfg.Logging.Format, cfg.App.Name)
	if err != nil {
		return fmt.Errorf("build logger: %w", err)
	}
	defer func() { _ = logger.Sync() }()
	shutdownTelemetry, err := obs.Init("user-service")
	if err != nil {
		return err
	}
	defer shutdownTelemetry()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	logger.Info("starting user-service",
		zap.String("env", cfg.App.Env),
		zap.Bool("api", cfg.App.APIEnabled),
		zap.Bool("consumer", cfg.Kafka.ConsumerEnabled),
	)

	// Infrastructure.
	pool, err := postgres.NewPool(ctx, cfg.Database)
	if err != nil {
		return err
	}
	defer pool.Close()

	redisClient, err := redisadapter.NewClient(ctx, cfg.Redis)
	if err != nil {
		return err
	}
	defer func() { _ = redisClient.Close() }()

	systemClock := clock.System{}
	keys := keybuilder.New(cfg.Redis.CachePrefix, cfg.Redis.StatePrefix)

	// Repositories and adapters.
	profiles := postgres.NewProfileRepository(pool)
	addresses := postgres.NewAddressRepository(pool)
	preferences := postgres.NewPreferencesRepository(pool)
	deletions := postgres.NewDeletionRepository(pool)
	projections := postgres.NewProjectionRepository(pool)
	txManager := postgres.NewTxManager(pool)

	addressCache := redisadapter.NewAddressCache(
		redisClient, keys, cfg.Cache.AddressTTL, cfg.Cache.AddressEmptyTTL, logger)
	limiter := redisadapter.NewRateLimiter(redisClient, keys)
	locker := redisadapter.NewLocker(redisClient, keys)

	publisher, closePublisher, err := newPublisher(cfg.Kafka, logger)
	if err != nil {
		return err
	}
	defer closePublisher()

	// Domain services.
	emitter := service.NewEventEmitter(publisher, logger)
	profileService := service.NewProfileService(profiles, txManager, emitter, systemClock)
	preferencesService := service.NewPreferencesService(preferences, txManager, emitter, systemClock)
	addressService := service.NewAddressService(addresses, profiles, addressCache, txManager, systemClock, logger)
	lifecycleService := service.NewLifecycleService(
		profiles, addresses, preferences, deletions, addressCache, txManager, systemClock, logger)
	reconciliationService := service.NewReconciliationService(projections, emitter, logger)

	// HTTP layer.
	healthHandler := handler.NewHealthHandler(2 * time.Second)
	healthHandler.AddProbe("postgres", pool.Ping)
	// Redis only accelerates the address list, so its failure degrades the
	// service instead of taking it out of rotation.
	healthHandler.AddOptionalProbe("redis", func(probeCtx context.Context) error {
		return redisClient.Ping(probeCtx).Err()
	})

	var (
		publicServer   *http.Server
		internalServer *http.Server
	)
	if cfg.App.APIEnabled {
		publicEngine := router.NewPublic(router.PublicDeps{
			Config:      cfg,
			Logger:      logger,
			Limiter:     limiter,
			Profile:     handler.NewProfileHandler(profileService),
			Address:     handler.NewAddressHandler(addressService),
			Preferences: handler.NewPreferencesHandler(preferencesService),
			Health:      healthHandler,
		})
		publicServer = &http.Server{
			Addr:         fmt.Sprintf("%s:%d", cfg.App.Host, cfg.App.Port),
			Handler:      publicEngine,
			ReadTimeout:  cfg.Server.ReadTimeout,
			WriteTimeout: cfg.Server.WriteTimeout,
			IdleTimeout:  cfg.Server.IdleTimeout,
		}
	}

	if cfg.Internal.Enabled {
		internalEngine := router.NewInternal(router.InternalDeps{
			Config:   cfg,
			Logger:   logger,
			Limiter:  limiter,
			Checkout: handler.NewCheckoutHandler(addressService),
			Health:   healthHandler,
		})
		internalServer = &http.Server{
			Addr:         fmt.Sprintf("%s:%d", cfg.Internal.Host, cfg.Internal.Port),
			Handler:      internalEngine,
			ReadTimeout:  cfg.Server.ReadTimeout,
			WriteTimeout: cfg.Server.WriteTimeout,
			IdleTimeout:  cfg.Server.IdleTimeout,
		}
	}

	// Auth event consumer.
	var (
		consumer   *kafka.Consumer
		consumerWG sync.WaitGroup
	)
	if cfg.Kafka.ConsumerEnabled {
		consumer, err = kafka.NewConsumer(cfg.Kafka, lifecycleService, preferencesService, publisher, logger)
		if err != nil {
			return err
		}
	}

	// Workers.
	runner := workers.NewRunner(locker, cfg.Workers.LockTTL, logger)
	runner.Register(retentionpurge.New(lifecycleService, cfg.Workers.RetentionPurge))
	runner.Register(projectionreconciliation.New(reconciliationService, cfg.Workers.ProjectionReconciliation))
	runner.Start(ctx)

	serverErrors := make(chan error, 2)

	if publicServer != nil {
		go func() {
			logger.Info("public API listening", zap.String("addr", publicServer.Addr))
			if err := publicServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				serverErrors <- fmt.Errorf("public server: %w", err)
			}
		}()
	}

	if internalServer != nil {
		go func() {
			logger.Info("internal API listening",
				zap.String("addr", internalServer.Addr),
				zap.Strings("allowed_callers", cfg.Internal.AllowedCallers),
			)
			if err := internalServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				serverErrors <- fmt.Errorf("internal server: %w", err)
			}
		}()
	}

	if consumer != nil {
		consumerWG.Add(1)
		go func() {
			defer consumerWG.Done()
			logger.Info("event consumer starting",
				zap.String("auth_topic", cfg.Kafka.AuthTopic),
				zap.String("notification_topic", cfg.Kafka.NotificationTopic),
				zap.String("group", cfg.Kafka.ConsumerGroup),
			)
			if err := consumer.Run(ctx); err != nil {
				serverErrors <- fmt.Errorf("event consumer: %w", err)
			}
		}()
	}

	var runErr error
	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	case runErr = <-serverErrors:
		logger.Error("component stopped unexpectedly", zap.Error(runErr))
		stop()
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
	defer cancel()

	if publicServer != nil {
		if err := publicServer.Shutdown(shutdownCtx); err != nil {
			logger.Error("public server shutdown failed", zap.Error(err))
		}
	}
	if internalServer != nil {
		if err := internalServer.Shutdown(shutdownCtx); err != nil {
			logger.Error("internal server shutdown failed", zap.Error(err))
		}
	}
	if consumer != nil {
		// Leaving the group before the process exits hands the partitions over
		// without waiting for the session timeout.
		if err := consumer.Close(); err != nil {
			logger.Error("event consumer shutdown failed", zap.Error(err))
		}
		consumerWG.Wait()
	}

	runner.Wait()
	logger.Info("user-service stopped")
	return runErr
}

// eventPublisher is what the wiring needs from the Kafka adapter: publishing
// the two user events and parking what the consumer cannot process.
type eventPublisher interface {
	port.EventPublisher
	kafka.DeadLetterSink
}

// newPublisher returns the Kafka publisher, or a logging stand-in when the
// broker is disabled for a local run.
func newPublisher(cfg config.KafkaConfig, logger *zap.Logger) (eventPublisher, func(), error) {
	if !cfg.Enabled {
		logger.Warn("kafka is disabled, user events will not be published")
		return kafka.NewLoggingPublisher(logger), func() {}, nil
	}

	publisher, err := kafka.NewPublisher(cfg, logger)
	if err != nil {
		return nil, nil, err
	}
	return publisher, func() {
		if err := publisher.Close(); err != nil {
			logger.Error("could not close the Kafka producer", zap.Error(err))
		}
	}, nil
}
