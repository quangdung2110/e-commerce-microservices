package outbox_relay

import (
	"context"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
)

// StreamMessage represents a message from Redis Stream
type StreamMessage struct {
	StreamKey  string
	MessageID  string
	EventType  string
	Payload    string
	Timestamp  string
}

// Worker manages the outbox relay components
type Worker struct {
	redisClient     *redis.Client
	kafkaProducer   KafkaProducer
	logger          *zap.Logger
	config          config.OutboxRelayConfig

	keyBuilder      *keybuilder.CartKeyBuilder

	// Channels
	streamChannel   chan string
	stopChannel    chan struct{}
	wg              sync.WaitGroup

	// Components
	pubsub          *PubSubSubscriber
	fallback        *ZSETFallbackWorker
	intentRecovery  *IntentRecoveryWorker
	consumer        *StreamConsumer

	// Metrics
	metrics         *Metrics
}

// NewWorker creates a new outbox relay worker
func NewWorker(
	redisClient *redis.Client,
	kafkaProducer KafkaProducer,
	logger *zap.Logger,
	cfg *config.Config,
) *Worker {
	w := &Worker{
		redisClient:   redisClient,
		kafkaProducer: kafkaProducer,
		logger:        logger,
		config:        cfg.Workers.OutboxRelay,
		keyBuilder:    keybuilder.NewCartKeyBuilder(),
		streamChannel: make(chan string, 1000),
		stopChannel:   make(chan struct{}),
		metrics:       NewMetrics(),
	}

	// Initialize components
	w.pubsub = NewPubSubSubscriber(redisClient, w.streamChannel, w.stopChannel, logger)
	w.fallback = NewZSETFallbackWorker(redisClient, w.streamChannel, w.stopChannel, logger, cfg.Workers.OutboxRelay)
	w.intentRecovery = NewIntentRecoveryWorker(redisClient, w.streamChannel, w.stopChannel, logger, cfg.Workers.OutboxRelay)

	pollTimeout := config.ParseDuration(cfg.Workers.OutboxRelay.PollTimeout)
	w.consumer = NewStreamConsumer(
		redisClient,
		kafkaProducer,
		w.logger,
		w.keyBuilder,
		w.metrics,
		cfg.Kafka.Topic,
		pollTimeout,
		cfg.Workers.OutboxRelay,
	)

	return w
}

// Start starts all components of the outbox relay worker
func (w *Worker) Start(ctx context.Context) error {
	w.logger.Info("Starting outbox relay worker",
		zap.Int("consumer_count", w.config.ConsumerCount),
		zap.Int("batch_size", w.config.BatchSize),
	)

	// Start Pub/Sub subscriber
	w.wg.Add(1)
	go func() {
		defer w.wg.Done()
		if err := w.pubsub.Start(ctx); err != nil {
			w.logger.Error("Pub/Sub subscriber error", zap.Error(err))
		}
	}()

	// Start ZSET Fallback worker (30s interval)
	w.wg.Add(1)
	go func() {
		defer w.wg.Done()
		w.fallback.Start(ctx)
	}()

	// Start Intent Recovery worker (30s interval)
	w.wg.Add(1)
	go func() {
		defer w.wg.Done()
		w.intentRecovery.Start(ctx)
	}()

	// Start Stream Consumers (multiple goroutines)
	for i := 0; i < w.config.ConsumerCount; i++ {
		w.wg.Add(1)
		go func(consumerID int) {
			defer w.wg.Done()
			w.logger.Info("Starting stream consumer",
				zap.Int("consumer_id", consumerID),
			)
			w.consumer.Start(ctx, w.streamChannel, consumerID)
		}(i)
	}

	w.logger.Info("Outbox relay worker started successfully")
	return nil
}

// Stop gracefully stops all components
func (w *Worker) Stop() {
	w.logger.Info("Stopping outbox relay worker...")

	// Close stop channel to signal all goroutines to stop
	close(w.stopChannel)

	// Wait for all goroutines with timeout
	done := make(chan struct{})
	go func() {
		w.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		w.logger.Info("Outbox relay worker stopped gracefully")
	case <-time.After(30 * time.Second):
		w.logger.Warn("Outbox relay worker stop timed out")
	}
}

// GetMetrics returns the worker metrics
func (w *Worker) GetMetrics() *Metrics {
	return w.metrics
}

// KafkaProducer interface for Kafka producer operations
type KafkaProducer interface {
	Produce(ctx context.Context, topic string, key []byte, value []byte, headers map[string]string) error
	Flush(timeout time.Duration) error
	Close() error
}
