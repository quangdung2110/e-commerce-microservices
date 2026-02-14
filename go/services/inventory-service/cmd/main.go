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

	"github.com/yourusername/spring-event-sourcing/go/pkg/cache"
	"github.com/yourusername/spring-event-sourcing/go/pkg/config"
	"github.com/yourusername/spring-event-sourcing/go/pkg/eventstore"
	"github.com/yourusername/spring-event-sourcing/go/pkg/kafka"
)

func main() {
	// Initialize logger
	logger, err := zap.NewProduction()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to initialize logger: %v\n", err)
		os.Exit(1)
	}
	defer logger.Sync()

	logger.Info("Starting inventory-service...")

	// Load configuration
	cfg, err := config.LoadConfig("inventory-service")
	if err != nil {
		logger.Fatal("Failed to load configuration", zap.Error(err))
	}

	logger.Info("Configuration loaded",
		zap.Int("server.port", cfg.Server.Port),
		zap.Strings("kafka.brokers", cfg.Kafka.Brokers),
		zap.String("redis.host", cfg.Redis.Host),
		zap.String("database.host", cfg.Database.Host))

	// Initialize Redis cache
	cacheManager, err := cache.NewRedisCacheManager(cache.RedisConfig{
		Host:   cfg.Redis.Host,
		Port:   cfg.Redis.Port,
		DB:     cfg.Redis.DB,
		Logger: logger,
	})
	if err != nil {
		logger.Fatal("Failed to initialize cache manager", zap.Error(err))
	}
	defer cacheManager.Close()

	// Initialize Event Store
	eventStore, err := eventstore.NewPostgreSQLEventStore(eventstore.EventStoreConfig{
		Host:     cfg.Database.Host,
		Port:     cfg.Database.Port,
		Database: cfg.Database.Name,
		User:     cfg.Database.User,
		Password: cfg.Database.Password,
		SSLMode:  cfg.Database.SSLMode,
		Logger:   logger,
	})
	if err != nil {
		logger.Fatal("Failed to initialize event store", zap.Error(err))
	}
	defer eventStore.Close()

	// Initialize Kafka publisher
	publisher, err := kafka.NewKafkaEventPublisher(
		cfg.Kafka.Brokers[0],
		"inventory-events",
		logger,
	)
	if err != nil {
		logger.Fatal("Failed to initialize Kafka publisher", zap.Error(err))
	}
	defer publisher.Close()

	// Initialize Kafka consumer
	// TODO: Implement event handler for order events
	// consumer, err := kafka.NewKafkaEventConsumer(kafka.ConsumerConfig{
	// 	Brokers:         cfg.Kafka.Brokers,
	// 	Topics:          []string{"order-events"},
	// 	GroupID:         "inventory-service-group",
	// 	DeadLetterTopic: "order-events-dlt",
	// 	Handler:         eventHandler,
	// 	Logger:          logger,
	// })
	// if err != nil {
	// 	logger.Fatal("Failed to initialize Kafka consumer", zap.Error(err))
	// }
	// defer consumer.Stop()

	// Start Kafka consumer in background
	// ctx, cancel := context.WithCancel(context.Background())
	// defer cancel()
	// go func() {
	// 	if err := consumer.Start(ctx); err != nil {
	// 		logger.Error("Kafka consumer error", zap.Error(err))
	// 	}
	// }()

	// Initialize HTTP server
	mux := http.NewServeMux()

	// Health check endpoint
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"UP"}`))
	})

	// Readiness check endpoint
	mux.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"READY"}`))
	})

	// TODO: Add inventory API endpoints
	// mux.HandleFunc("/api/inventory/reserve", reserveStockHandler)
	// mux.HandleFunc("/api/inventory/release", releaseStockHandler)
	// mux.HandleFunc("/api/inventory/check", checkStockHandler)

	server := &http.Server{
		Addr:         fmt.Sprintf(":%d", cfg.Server.Port),
		Handler:      mux,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start HTTP server in background
	go func() {
		logger.Info("HTTP server listening", zap.Int("port", cfg.Server.Port))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("HTTP server error", zap.Error(err))
		}
	}()

	// Wait for interrupt signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	<-sigChan

	logger.Info("Shutting down inventory-service...")

	// Graceful shutdown
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("HTTP server shutdown error", zap.Error(err))
	}

	logger.Info("inventory-service stopped")
}
