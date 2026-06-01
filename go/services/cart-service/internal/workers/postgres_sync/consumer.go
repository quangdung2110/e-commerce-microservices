package postgres_sync

import (
	"context"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"cart-service/internal/domain/event"
	"cart-service/internal/infrastructure/kafka"
)

// Consumer wraps kafka.Consumer for the postgres sync worker.
type Consumer struct {
	inner  *kafka.Consumer
	logger *zap.Logger
}

// NewConsumer creates a new consumer for the postgres sync worker.
func NewConsumer(inner *kafka.Consumer, logger *zap.Logger) *Consumer {
	return &Consumer{
		inner:  inner,
		logger: logger,
	}
}

// Start starts consuming messages.
func (c *Consumer) Start(ctx context.Context) error {
	return c.inner.Start(ctx)
}

// Stop stops the consumer.
func (c *Consumer) Stop() error {
	return c.inner.Stop()
}

// PollBatch polls a batch of messages.
func (c *Consumer) PollBatch(ctx context.Context, batchSize int, timeout time.Duration) (*kafka.MessageBatch, error) {
	return c.inner.PollBatch(ctx, batchSize, timeout)
}

// MessageHandler implements kafka.MessageHandler for sync consumption.
type MessageHandler struct {
	worker *Worker
}

// NewMessageHandler creates a handler that delegates to the worker.
func NewMessageHandler(worker *Worker) *MessageHandler {
	return &MessageHandler{worker: worker}
}

// Handle processes a single Kafka message.
func (h *MessageHandler) Handle(ctx context.Context, msg *sarama.ConsumerMessage) error {
	env, err := kafka.ParseCartEvent(msg)
	if err != nil {
		return err
	}
	return h.worker.processEvent(ctx, env)
}

// ParseMessage parses a Kafka message into a CartEventEnvelope.
func ParseMessage(msg *sarama.ConsumerMessage) (*event.CartEventEnvelope, error) {
	return kafka.ParseCartEvent(msg)
}
