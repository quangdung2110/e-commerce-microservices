package kafka

import (
	"context"
	"fmt"
	"sync"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
)

type ConsumerHandler interface {
	HandleMessage(ctx context.Context, msg *sarama.ConsumerMessage) error
}

type Consumer struct {
	consumerGroup sarama.ConsumerGroup
	topics        []string
	handler       ConsumerHandler
	logger        *zap.Logger
	ready         chan bool
	wg            sync.WaitGroup
	cancelFn      context.CancelFunc
}

type consumerGroupHandler struct {
	handler ConsumerHandler
	logger  *zap.Logger
}

func NewConsumer(cfg config.KafkaConfig, topics []string, handler ConsumerHandler, logger *zap.Logger) (*Consumer, error) {
	saramaConfig := sarama.NewConfig()
	saramaConfig.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{
		sarama.NewBalanceStrategyRoundRobin(),
	}
	saramaConfig.Consumer.Offsets.Initial = sarama.OffsetNewest
	saramaConfig.Consumer.Return.Errors = true

	consumerGroup, err := sarama.NewConsumerGroup(cfg.Brokers, cfg.ConsumerGroup, saramaConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create consumer group: %w", err)
	}

	return &Consumer{
		consumerGroup: consumerGroup,
		topics:        topics,
		handler:       handler,
		logger:        logger.With(zap.String("component", "kafka_consumer")),
		ready:         make(chan bool),
	}, nil
}

func (c *Consumer) Start(ctx context.Context) error {
	ctx, cancel := context.WithCancel(ctx)
	c.cancelFn = cancel

	handler := &consumerGroupHandler{
		handler: c.handler,
		logger:  c.logger,
	}

	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		for {
			if err := c.consumerGroup.Consume(ctx, c.topics, handler); err != nil {
				c.logger.Error("Error from consumer", zap.Error(err))
			}

			if ctx.Err() != nil {
				c.logger.Info("Consumer context cancelled, stopping")
				return
			}

			c.ready = make(chan bool)
		}
	}()

	<-c.ready
	c.logger.Info("Kafka consumer group is ready",
		zap.Strings("topics", c.topics),
		zap.String("consumer_group", "auth-service-consumer"),
	)

	return nil
}

func (c *Consumer) Close() error {
	if c.cancelFn != nil {
		c.cancelFn()
	}
	c.wg.Wait()
	return c.consumerGroup.Close()
}

// MarkOffset is deprecated - offset marking is now handled automatically by the session
// This method is kept for API compatibility but does nothing
func (c *Consumer) MarkOffset(msg *sarama.ConsumerMessage) {
	// No-op: In newer Sarama versions, offsets are marked automatically via session.MarkMessage
}

// MarkOffsetWithMetadata is deprecated - offset marking is now handled automatically by the session
// This method is kept for API compatibility but does nothing
func (c *Consumer) MarkOffsetWithMetadata(msg *sarama.ConsumerMessage, metadata string) {
	// No-op: In newer Sarama versions, offsets are marked automatically via session.MarkMessage
}

func (h *consumerGroupHandler) Setup(session sarama.ConsumerGroupSession) error {
	return nil
}

func (h *consumerGroupHandler) Cleanup(session sarama.ConsumerGroupSession) error {
	return nil
}

func (h *consumerGroupHandler) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for {
		select {
		case message, ok := <-claim.Messages():
			if !ok {
				h.logger.Debug("Message channel closed")
				return nil
			}

			h.logger.Debug("Processing message",
				zap.String("topic", message.Topic),
				zap.Int64("partition", int64(message.Partition)),
				zap.Int64("offset", message.Offset),
			)

			if err := h.handler.HandleMessage(session.Context(), message); err != nil {
				h.logger.Error("Failed to handle message",
					zap.Error(err),
					zap.String("topic", message.Topic),
					zap.Int64("offset", message.Offset),
				)
			}

			session.MarkMessage(message, "")
			session.Commit()

		case <-session.Context().Done():
			h.logger.Debug("Session context cancelled")
			return nil
		}
	}
}
