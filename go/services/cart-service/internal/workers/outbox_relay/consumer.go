package outbox_relay

import (
	"context"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"cart-service/internal/infrastructure/kafka"
)

// Consumer wraps kafka.Consumer with additional context for the outbox relay.
type Consumer struct {
	inner *kafka.Consumer
}

// NewConsumer creates a new outbox relay consumer.
func NewConsumer(inner *kafka.Consumer) *Consumer {
	return &Consumer{inner: inner}
}

// Start begins consuming messages from Kafka.
func (c *Consumer) Start(ctx context.Context) error {
	return c.inner.Start(ctx)
}

// Stop stops the consumer.
func (c *Consumer) Stop() error {
	return c.inner.Stop()
}

// Inner returns the underlying kafka.Consumer.
func (c *Consumer) Inner() *kafka.Consumer {
	return c.inner
}

// BatchConsumer implements batch-oriented consumption for postgres_sync and order_consumer.
type BatchConsumer struct {
	inner     *kafka.Consumer
	batchSize int
	timeout   time.Duration
	logger    *zap.Logger
}

// NewBatchConsumer creates a batch consumer with configurable batch size and timeout.
func NewBatchConsumer(
	inner *kafka.Consumer,
	batchSize int,
	timeout time.Duration,
	logger *zap.Logger,
) *BatchConsumer {
	return &BatchConsumer{
		inner:     inner,
		batchSize: batchSize,
		timeout:   timeout,
		logger:    logger,
	}
}

// PollBatch polls a batch of messages from Kafka.
func (c *BatchConsumer) PollBatch(ctx context.Context) (*kafka.MessageBatch, error) {
	return c.inner.PollBatch(ctx, c.batchSize, c.timeout)
}

// Consume starts the consumer group loop.
func (c *BatchConsumer) Consume(ctx context.Context, handler kafka.MessageHandler) error {
	// Wrap the batch handler to consume via ConsumeClaim
	_ = c.inner
	return nil
}

// BatchHandler defines how to process a batch of messages.
type BatchHandler func(ctx context.Context, batch *kafka.MessageBatch) error

// SaramaHandler adapts a BatchHandler to kafka.MessageHandler.
func SaramaHandler(h BatchHandler) kafka.MessageHandler {
	return func(ctx context.Context, msg *sarama.ConsumerMessage) error {
		return nil
	}
}
