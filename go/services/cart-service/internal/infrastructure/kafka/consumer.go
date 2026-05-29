package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/IBM/sarama"

	"cart-service/internal/domain/event"
)

// MessageHandler is the function signature for processing Kafka messages.
type MessageHandler func(ctx context.Context, msg *sarama.ConsumerMessage) error

// Consumer represents a Kafka consumer group.
type Consumer struct {
	groupID string
	topics  []string
	config  *sarama.Config
	brokers []string
	ready   chan bool
	handler MessageHandler
	inner   sarama.ConsumerGroup
	wg      sync.WaitGroup
}

// NewConsumer creates a new Kafka consumer group.
func NewConsumer(cfg Config, topics []string, handler MessageHandler) (*Consumer, error) {
	config, err := NewSaramaConfig(cfg, false)
	if err != nil {
		return nil, err
	}

	inner, err := sarama.NewConsumerGroup(cfg.Brokers, cfg.Consumer.GroupID, config)
	if err != nil {
		return nil, fmt.Errorf("create consumer group: %w", err)
	}

	return &Consumer{
		groupID: cfg.Consumer.GroupID,
		topics:  topics,
		config:  config,
		brokers: cfg.Brokers,
		ready:   make(chan bool),
		handler: handler,
		inner:   inner,
	}, nil
}

// Start begins consuming messages.
func (c *Consumer) Start(ctx context.Context) error {
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		for {
			select {
			case <-ctx.Done():
				return
			default:
				if err := c.inner.Consume(ctx, c.topics, c); err != nil {
					_ = err
				}
			}
		}
	}()

	<-c.ready
	return nil
}

// Stop gracefully stops the consumer.
func (c *Consumer) Stop() error {
	return c.inner.Close()
}

// Setup is run at the beginning of a new session, before ConsumeClaim.
func (c *Consumer) Setup(sarama.ConsumerGroupSession) error {
	close(c.ready)
	return nil
}

// Cleanup is run at the end of a session.
func (c *Consumer) Cleanup(sarama.ConsumerGroupSession) error {
	c.ready = make(chan bool)
	return nil
}

// ConsumeClaim processes messages from a partition.
func (c *Consumer) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for {
		select {
		case message, ok := <-claim.Messages():
			if !ok {
				return nil
			}

			if err := c.handler(session.Context(), message); err != nil {
				_ = err
			}

			session.MarkMessage(message, "")

		case <-session.Context().Done():
			return nil
		}
	}
}

// ParseCartEvent deserializes a Kafka message to CartEventEnvelope.
func ParseCartEvent(msg *sarama.ConsumerMessage) (*event.CartEventEnvelope, error) {
	var e event.CartEventEnvelope
	if err := json.Unmarshal(msg.Value, &e); err != nil {
		return nil, fmt.Errorf("unmarshal event: %w", err)
	}
	return &e, nil
}

// ParseOrderPaidEvent deserializes an ORDER_PAID event.
func ParseOrderPaidEvent(msg *sarama.ConsumerMessage) (*event.OrderPaidPayload, error) {
	var wrapper struct {
		Payload event.OrderPaidPayload `json:"payload"`
	}
	if err := json.Unmarshal(msg.Value, &wrapper); err != nil {
		return nil, fmt.Errorf("unmarshal ORDER_PAID: %w", err)
	}
	return &wrapper.Payload, nil
}
