// Command server runs the Cart Service: the HTTP API, the com.ecommerce.order.paid.v1 and
// com.ecommerce.auth.user-deleted.v1 consumers and the three background loops (marker, relay,
// trimmer).
//
// At the load this service is sized for, one deployment runs all of them. When
// it needs to scale, bucket ownership is split across deployments; a stream
// must never have two active relay readers.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
	"golang.org/x/sync/errgroup"

	"cart-service/internal/api/handler"
	"cart-service/internal/api/media"
	"cart-service/internal/api/middleware"
	"cart-service/internal/api/router"
	"cart-service/internal/infrastructure/cartredis"
	"cart-service/internal/infrastructure/config"
	"cart-service/internal/infrastructure/kafka"
	"cart-service/internal/infrastructure/observability"
	"cart-service/internal/infrastructure/product"
	"cart-service/internal/service"
	"cart-service/internal/workers/marker"
	"cart-service/internal/workers/orderpaid"
	"cart-service/internal/workers/relay"
	"cart-service/internal/workers/trimmer"
	"cart-service/internal/workers/userdeleted"
)

func main() {
	configPath := flag.String("config", "configs/application.yaml", "path to the configuration file")
	flag.Parse()

	if err := run(*configPath); err != nil {
		fmt.Fprintf(os.Stderr, "cart-service failed: %v\n", err)
		os.Exit(1)
	}
}

func run(configPath string) error {
	cfg, err := config.Load(configPath)
	if err != nil {
		return err
	}

	logger, err := observability.NewLogger(observability.LogConfig{
		Level:  cfg.Logging.Level,
		Format: cfg.Logging.Format,
		Output: cfg.Logging.Output,
	})
	if err != nil {
		return err
	}
	defer func() { _ = logger.Sync() }()

	metrics := observability.NewMetrics()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// ── Redis: the cart source of truth ──────────────────────────────────
	redisClient := cartredis.NewClient(cartredis.ClientConfig{
		Addrs:        cfg.Redis.Addrs,
		Password:     cfg.Redis.Password,
		DB:           cfg.Redis.DB,
		PoolSize:     cfg.Redis.PoolSize,
		MinIdleConns: cfg.Redis.MinIdleConns,
		DialTimeout:  cfg.Redis.DialTimeout,
		ReadTimeout:  cfg.Redis.ReadTimeout,
		WriteTimeout: cfg.Redis.WriteTimeout,
		TLSEnabled:   cfg.Redis.TLSEnabled,
	})
	defer func() { _ = redisClient.Close() }()

	store := cartredis.NewStore(redisClient, cartredis.Options{
		AbandonedAfter: cfg.Cart.AbandonedAfter,
		ActiveTTL:      cfg.Cart.ActiveTTL,
		AbandonedTTL:   cfg.Cart.AbandonedTTL,
		IdempotencyTTL: cfg.Cart.IdempotencyTTL,
	})

	// The consumer name identifies this process inside the relay group, so
	// XAUTOCLAIM can tell its own pending entries from a dead predecessor's.
	outbox := cartredis.NewOutbox(redisClient, relayConsumerName())

	// ── Product Service: pull model, hard deadline, breaker ──────────────
	catalog, err := product.NewClient(ctx, product.Config{
		Address:             cfg.Product.Address,
		DialTimeout:         cfg.Product.DialTimeout,
		GetSkuTimeout:       cfg.Product.GetSkuTimeout,
		GetBatchSkusTimeout: cfg.Product.GetBatchSkusTimeout,
		CacheSize:           cfg.Product.CacheSize,
		CacheTTL:            cfg.Product.CacheTTL,
		Breaker: product.BreakerConfig{
			FailureThreshold: cfg.Product.Breaker.FailureThreshold,
			HalfOpenMaxCalls: cfg.Product.Breaker.HalfOpenMaxCalls,
			OpenTimeout:      cfg.Product.Breaker.OpenTimeout,
		},
	})
	if err != nil {
		return err
	}
	catalog.Observer = metrics
	defer func() { _ = catalog.Close() }()

	// ── Kafka: CART_ABANDONED out; order.paid and user-deleted in ────────
	publisher, err := kafka.NewProducer(kafka.ProducerConfig{
		Brokers:         cfg.Kafka.Brokers,
		Topic:           cfg.Kafka.TopicCartEvents,
		DeadLetterTopic: cfg.Kafka.TopicCartEventsDLQ,
		RequiredAcks:    cfg.Kafka.RequiredAcks,
		Timeout:         cfg.Kafka.ProduceTimeout,
		TLSEnabled:      cfg.Kafka.TLSEnabled,
	})
	if err != nil {
		return err
	}
	defer func() { _ = publisher.Close() }()

	cartService := service.New(store, catalog, service.Config{
		ProductValidationTTL: cfg.Cart.ProductValidationTTL,
	})

	// ── HTTP surface ─────────────────────────────────────────────────────
	engine := router.New(router.Config{
		Cart: handler.NewCart(cartService, media.NewURLs(cfg.Media.CDNBaseURL, cfg.Media.PlaceholderURL), logger),
		Health: handler.NewHealth(map[string]handler.Probe{
			// Redis is the only dependency required to serve the read path.
			// Product failures degrade GET and Kafka failures only delay the
			// abandoned-event relay, so neither may withdraw healthy Cart pods
			// from traffic.
			"redis": store.Ping,
		}, cfg.App.ReadinessTimeout),
		RateLimit: middleware.RateLimitConfig{
			RequestsPerSecond: cfg.Cart.RateLimit.RequestsPerSecond,
			Burst:             cfg.Cart.RateLimit.Burst,
			IdleTimeout:       cfg.Cart.RateLimit.IdleTimeout,
		},
		InternalCallers: cfg.Cart.InternalCallers,
		Registry:        metrics.Registry(),
		Logger:          logger,
		Debug:           cfg.App.Env == "development",
	})

	server := &http.Server{
		Addr:              net.JoinHostPort(cfg.App.Host, strconv.Itoa(cfg.App.Port)),
		Handler:           engine,
		ReadHeaderTimeout: 10 * time.Second,
	}

	group, groupCtx := errgroup.WithContext(ctx)

	group.Go(func() error {
		logger.Info("cart http api listening",
			zap.String("addr", server.Addr),
			zap.String("env", cfg.App.Env),
		)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			return fmt.Errorf("http server: %w", err)
		}
		return nil
	})

	// ── Background loops ─────────────────────────────────────────────────
	if cfg.Worker.AbandonedMarker.Enabled {
		worker := marker.New(store, marker.Config{
			Interval:         cfg.Worker.AbandonedMarker.Interval,
			BatchSize:        cfg.Worker.AbandonedMarker.BatchSize,
			OperationTimeout: cfg.Worker.AbandonedMarker.OperationTimeout,
		}, metrics, logger)
		group.Go(func() error { return worker.Run(groupCtx) })
	}

	if cfg.Worker.OutboxRelay.Enabled {
		worker := relay.New(outbox, publisher, relay.Config{
			IdleInterval:       cfg.Worker.OutboxRelay.IdleInterval,
			BatchSize:          cfg.Worker.OutboxRelay.BatchSize,
			ClaimMinIdle:       cfg.Worker.OutboxRelay.ClaimMinIdle,
			MaxPublishAttempts: cfg.Worker.OutboxRelay.MaxPublishAttempts,
			PublishBackoff:     cfg.Worker.OutboxRelay.PublishBackoff,
			OperationTimeout:   cfg.Worker.OutboxRelay.OperationTimeout,
			DataSchema:         cfg.Kafka.EventDataSchema,
		}, metrics, logger)
		group.Go(func() error { return worker.Run(groupCtx) })
	}

	if cfg.Worker.StreamTrimmer.Enabled {
		worker := trimmer.New(outbox, trimmer.Config{
			Interval:         cfg.Worker.StreamTrimmer.Interval,
			OperationTimeout: cfg.Worker.StreamTrimmer.OperationTimeout,
		}, metrics, logger)
		group.Go(func() error { return worker.Run(groupCtx) })
	}

	if cfg.Worker.OrderConsumer.Enabled {
		orderHandler := orderpaid.NewHandler(cartService, metrics, logger)
		consumer, err := kafka.NewConsumer(kafka.ConsumerConfig{
			Brokers:        cfg.Kafka.Brokers,
			Topic:          cfg.Kafka.TopicOrderEvents,
			GroupID:        cfg.Kafka.ConsumerGroupOrder,
			InitialOffset:  cfg.Kafka.InitialOffset,
			TLSEnabled:     cfg.Kafka.TLSEnabled,
			SessionTimeout: cfg.Kafka.SessionTimeout,
		}, orderHandler.Handle, logger)
		if err != nil {
			return err
		}
		defer func() { _ = consumer.Close() }()

		group.Go(func() error {
			logger.Info("order paid consumer started",
				zap.String("topic", cfg.Kafka.TopicOrderEvents),
				zap.String("group", cfg.Kafka.ConsumerGroupOrder),
			)
			return consumer.Run(groupCtx)
		})
	}

	if cfg.Worker.UserDeletedConsumer.Enabled {
		deletedHandler := userdeleted.NewHandler(cartService, metrics, logger)
		consumer, err := kafka.NewConsumer(kafka.ConsumerConfig{
			Brokers:        cfg.Kafka.Brokers,
			Topic:          cfg.Kafka.TopicAuthEvents,
			GroupID:        cfg.Kafka.ConsumerGroupAuth,
			InitialOffset:  cfg.Kafka.InitialOffset,
			TLSEnabled:     cfg.Kafka.TLSEnabled,
			SessionTimeout: cfg.Kafka.SessionTimeout,
		}, deletedHandler.Handle, logger)
		if err != nil {
			return err
		}
		defer func() { _ = consumer.Close() }()

		group.Go(func() error {
			logger.Info("user deleted consumer started",
				zap.String("topic", cfg.Kafka.TopicAuthEvents),
				zap.String("group", cfg.Kafka.ConsumerGroupAuth),
			)
			return consumer.Run(groupCtx)
		})
	}

	// ── Graceful shutdown ────────────────────────────────────────────────
	group.Go(func() error {
		<-groupCtx.Done()
		logger.Info("shutdown signal received, draining http server")

		shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.App.ShutdownTimeout)
		defer cancel()
		return server.Shutdown(shutdownCtx)
	})

	if err := group.Wait(); err != nil {
		return err
	}
	logger.Info("cart-service stopped cleanly")
	return nil
}

// relayConsumerName builds a per-process consumer name for the relay group.
// The hostname makes it readable in XINFO output; the suffix keeps two
// restarts on the same host from sharing a pending list.
func relayConsumerName() string {
	host, err := os.Hostname()
	if err != nil || host == "" {
		host = "cart"
	}
	return fmt.Sprintf("%s-%s", host, uuid.NewString()[:8])
}
