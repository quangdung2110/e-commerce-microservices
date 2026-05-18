package kafka_consumer

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
	"go.uber.org/zap"
)

// ConsumerConfig holds Kafka consumer configuration
type ConsumerConfig struct {
	Brokers       []string
	GroupID       string
	Topic         string
	OffsetReset   string
	MaxWaitTime   time.Duration
	MinBytes      int
	MaxBytes      int
}

// MessageHandler is the interface for handling Kafka messages
type MessageHandler interface {
	Handle(ctx context.Context, msg kafka.Message) error
}

// Consumer represents a Kafka consumer
type Consumer struct {
	reader  *kafka.Reader
	handler MessageHandler
	logger  *zap.Logger
	running bool
	mu      sync.Mutex
	stopCh  chan struct{}
}

// NewConsumer creates a new Kafka consumer
func NewConsumer(cfg ConsumerConfig, handler MessageHandler, logger *zap.Logger) *Consumer {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:                cfg.Brokers,
		GroupID:                cfg.GroupID,
		Topic:                  cfg.Topic,
		MinBytes:              cfg.MinBytes,
		MaxBytes:              cfg.MaxBytes,
		MaxWait:               cfg.MaxWaitTime,
		StartOffset:            getStartOffset(cfg.OffsetReset),
		CommitInterval:         0, // Manual commit
		RetentionTime:          7 * 24 * time.Hour,
		MaxAttempts:            3,
	})

	return &Consumer{
		reader:  reader,
		handler: handler,
		logger:  logger,
		stopCh:  make(chan struct{}),
	}
}

// getStartOffset converts offset reset string to kafka.StartOffset
func getStartOffset(offsetReset string) int64 {
	switch offsetReset {
	case "earliest":
		return kafka.FirstOffset
	case "latest":
		return kafka.LastOffset
	default:
		return kafka.FirstOffset
	}
}

// Start begins consuming messages
func (c *Consumer) Start(ctx context.Context) error {
	c.mu.Lock()
	if c.running {
		c.mu.Unlock()
		return fmt.Errorf("consumer is already running")
	}
	c.running = true
	c.mu.Unlock()

	c.logger.Info("Starting Kafka consumer",
		zap.String("topic", c.reader.Config().Topic),
		zap.String("group_id", c.reader.Config().GroupID),
	)

	go c.consume(ctx)
	return nil
}

// consume is the main consumption loop
func (c *Consumer) consume(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			c.logger.Info("Context cancelled, stopping consumer")
			return
		case <-c.stopCh:
			c.logger.Info("Stop signal received, stopping consumer")
			return
		default:
			msg, err := c.reader.FetchMessage(ctx)
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				c.logger.Error("Failed to fetch message", zap.Error(err))
				continue
			}

			if err := c.processMessage(ctx, msg); err != nil {
				c.logger.Error("Failed to process message",
					zap.Error(err),
					zap.String("topic", msg.Topic),
					zap.Int64("offset", msg.Offset),
					zap.Int("partition", msg.Partition),
				)
				// Continue processing other messages
			}

			// Commit the message
			if err := c.reader.CommitMessages(ctx, msg); err != nil {
				c.logger.Error("Failed to commit message", zap.Error(err))
			}
		}
	}
}

// processMessage handles a single message with error handling
func (c *Consumer) processMessage(ctx context.Context, msg kafka.Message) error {
	c.logger.Debug("Processing message",
		zap.String("topic", msg.Topic),
		zap.Int64("offset", msg.Offset),
		zap.Int("partition", msg.Partition),
		zap.Int("size", len(msg.Value)),
	)

	return c.handler.Handle(ctx, msg)
}

// Stop stops the consumer
func (c *Consumer) Stop() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.running {
		return nil
	}

	close(c.stopCh)
	c.running = false

	if err := c.reader.Close(); err != nil {
		return fmt.Errorf("failed to close reader: %w", err)
	}

	c.logger.Info("Kafka consumer stopped")
	return nil
}

// Stats returns consumer statistics
func (c *Consumer) Stats() kafka.ReaderStats {
	return c.reader.Stats()
}
