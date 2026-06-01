package outbox_relay

import (
	"context"

	"go.uber.org/zap"

	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/kafka"
)

// KafkaProducer wraps kafka.Publisher and implements port.KafkaPublisher.
type KafkaProducer struct {
	inner   *kafka.Publisher
	logger  *zap.Logger
}

// NewKafkaProducer creates a new Kafka producer wrapper.
func NewKafkaProducer(inner *kafka.Publisher, logger *zap.Logger) *KafkaProducer {
	return &KafkaProducer{
		inner:  inner,
		logger: logger,
	}
}

// Publish publishes a cart event message to Kafka.
func (p *KafkaProducer) Publish(ctx context.Context, msg *port.CartEventMessage) error {
	p.logger.Debug("publishing event to Kafka",
		zap.String("event_type", msg.EventType),
		zap.String("aggregate_id", msg.AggregateID),
		zap.String("stream_id", msg.StreamID),
	)
	return p.inner.Publish(ctx, msg)
}

// Close closes the underlying Kafka producer.
func (p *KafkaProducer) Close() error {
	return p.inner.Close()
}

// AsyncKafkaProducer wraps kafka.AsyncPublisher.
type AsyncKafkaProducer struct {
	inner  *kafka.AsyncPublisher
	logger *zap.Logger
}

// NewAsyncKafkaProducer creates a new async Kafka producer wrapper.
func NewAsyncKafkaProducer(inner *kafka.AsyncPublisher, logger *zap.Logger) *AsyncKafkaProducer {
	return &AsyncKafkaProducer{
		inner:  inner,
		logger: logger,
	}
}

// PublishAsync sends a message asynchronously.
func (p *AsyncKafkaProducer) PublishAsync(ctx context.Context, msg *port.CartEventMessage) error {
	p.logger.Debug("publishing async event to Kafka",
		zap.String("event_type", msg.EventType),
		zap.String("aggregate_id", msg.AggregateID),
	)
	return p.inner.PublishAsync(ctx, msg)
}

// Errors returns the error channel for async errors.
func (p *AsyncKafkaProducer) Errors() <-chan error {
	return p.inner.Errors()
}

// Successes returns the success channel.
func (p *AsyncKafkaProducer) Successes() <-chan struct{} {
	return p.inner.Successes()
}

// Close closes the underlying async producer.
func (p *AsyncKafkaProducer) Close() error {
	return p.inner.Close()
}
