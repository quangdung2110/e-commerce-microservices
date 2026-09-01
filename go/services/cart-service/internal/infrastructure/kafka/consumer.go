package kafka

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"
)

// ConsumerConfig configures the ORDER_PAID consumer group.
type ConsumerConfig struct {
	Brokers []string
	Topic   string
	GroupID string
	// InitialOffset is "oldest" or "newest".
	InitialOffset  string
	TLSEnabled     bool
	SessionTimeout time.Duration
}

// MessageHandler processes one Kafka message.
//
// Returning an error leaves the offset uncommitted, so the message is
// redelivered. Handlers must therefore be idempotent.
type MessageHandler func(ctx context.Context, message *sarama.ConsumerMessage) error

// Consumer runs a sarama consumer group against a single topic.
type Consumer struct {
	group   sarama.ConsumerGroup
	cfg     ConsumerConfig
	handler MessageHandler
	logger  *zap.Logger
}

// NewConsumer builds a consumer group.
func NewConsumer(cfg ConsumerConfig, handler MessageHandler, logger *zap.Logger) (*Consumer, error) {
	saramaCfg := sarama.NewConfig()
	saramaCfg.Consumer.Return.Errors = true
	saramaCfg.Consumer.Offsets.Initial = initialOffset(cfg.InitialOffset)
	saramaCfg.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{
		sarama.NewBalanceStrategyRoundRobin(),
	}
	if cfg.SessionTimeout > 0 {
		saramaCfg.Consumer.Group.Session.Timeout = cfg.SessionTimeout
	}
	saramaCfg.Net.TLS.Enable = cfg.TLSEnabled

	group, err := sarama.NewConsumerGroup(cfg.Brokers, cfg.GroupID, saramaCfg)
	if err != nil {
		return nil, fmt.Errorf("create consumer group %s: %w", cfg.GroupID, err)
	}

	return &Consumer{group: group, cfg: cfg, handler: handler, logger: logger}, nil
}

// Run consumes until the context is cancelled. Rebalances return from
// group.Consume, so the loop re-enters until the caller stops it.
func (c *Consumer) Run(ctx context.Context) error {
	handler := &groupHandler{handle: c.handler, logger: c.logger}

	go func() {
		for err := range c.group.Errors() {
			c.logger.Warn("kafka consumer group error", zap.Error(err))
		}
	}()

	for {
		if err := c.group.Consume(ctx, []string{c.cfg.Topic}, handler); err != nil {
			if errors.Is(err, sarama.ErrClosedConsumerGroup) || ctx.Err() != nil {
				return nil
			}
			c.logger.Warn("kafka consume returned an error, retrying",
				zap.String("topic", c.cfg.Topic),
				zap.Error(err),
			)
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(time.Second):
			}
			continue
		}
		if ctx.Err() != nil {
			return nil
		}
	}
}

// Close shuts the consumer group down.
func (c *Consumer) Close() error {
	return c.group.Close()
}

type groupHandler struct {
	handle MessageHandler
	logger *zap.Logger
}

func (h *groupHandler) Setup(sarama.ConsumerGroupSession) error   { return nil }
func (h *groupHandler) Cleanup(sarama.ConsumerGroupSession) error { return nil }

// ConsumeClaim marks an offset only after the handler succeeded. A handler
// failure stops the claim so the partition is reprocessed from the last good
// offset rather than skipping past a message Cart never applied.
func (h *groupHandler) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for {
		select {
		case <-session.Context().Done():
			return nil
		case message, ok := <-claim.Messages():
			if !ok {
				return nil
			}
			if err := h.handle(session.Context(), message); err != nil {
				h.logger.Error("failed to handle kafka message, offset not committed",
					zap.String("topic", message.Topic),
					zap.Int32("partition", message.Partition),
					zap.Int64("offset", message.Offset),
					zap.Error(err),
				)
				return err
			}
			session.MarkMessage(message, "")
		}
	}
}

func initialOffset(setting string) int64 {
	if setting == "newest" {
		return sarama.OffsetNewest
	}
	return sarama.OffsetOldest
}
