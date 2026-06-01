package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/IBM/sarama"

	"cart-service/internal/domain/event"
)

// MessageHandler is the function signature for processing Kafka messages.
type MessageHandler func(ctx context.Context, msg *sarama.ConsumerMessage) error

// Consumer represents a Kafka consumer group.
type Consumer struct {
	groupID      string
	topics       []string
	config       *sarama.Config
	brokers      []string
	ready        chan bool
	handler      MessageHandler
	inner        sarama.ConsumerGroup
	wg           sync.WaitGroup
	claimsMu      sync.RWMutex
	activeClaims  map[string]sarama.ConsumerGroupClaim
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
		groupID:      cfg.Consumer.GroupID,
		topics:       topics,
		config:       config,
		brokers:      cfg.Brokers,
		ready:        make(chan bool),
		handler:      handler,
		inner:        inner,
		activeClaims: make(map[string]sarama.ConsumerGroupClaim),
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
func (c *Consumer) Setup(session sarama.ConsumerGroupSession) error {
	// Clear stale claims
	c.claimsMu.Lock()
	c.activeClaims = make(map[string]sarama.ConsumerGroupClaim)
	for _, topic := range c.topics {
		c.activeClaims[topic] = nil
	}
	c.claimsMu.Unlock()
	close(c.ready)
	return nil
}

// Cleanup is run at the end of a session.
func (c *Consumer) Cleanup(sarama.ConsumerGroupSession) error {
	c.claimsMu.Lock()
	c.activeClaims = make(map[string]sarama.ConsumerGroupClaim)
	c.ready = make(chan bool)
	c.claimsMu.Unlock()
	return nil
}

// ConsumeClaim processes messages from a partition.
func (c *Consumer) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	topic := claim.Topic()

	c.claimsMu.Lock()
	c.activeClaims[topic] = claim
	c.claimsMu.Unlock()

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

// MessageBatch holds a batch of consumed messages ready for processing.
type MessageBatch struct {
	messages []*sarama.ConsumerMessage
	claims   map[string]sarama.ConsumerGroupClaim
	mu       sync.Mutex
}

// NewMessageBatch creates a new empty message batch.
func NewMessageBatch() *MessageBatch {
	return &MessageBatch{
		messages: make([]*sarama.ConsumerMessage, 0),
		claims:   make(map[string]sarama.ConsumerGroupClaim),
	}
}

// Messages returns all messages in the batch.
func (b *MessageBatch) Messages() []*sarama.ConsumerMessage {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.messages
}

// Add adds a message to the batch.
func (b *MessageBatch) Add(msg *sarama.ConsumerMessage) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.messages = append(b.messages, msg)
}

// Count returns the number of messages in the batch.
func (b *MessageBatch) Count() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.messages)
}

// Commit marks all messages in the batch as committed.
func (b *MessageBatch) Commit() {
	// Messages are marked committed via session.MarkMessage in ConsumeClaim.
	// This method is a no-op for the simple consumer; batch.commit()
	// is a placeholder for future explicit offset management.
}

// PollBatch collects messages from all active claims until batchSize is reached or timeout expires.
func (c *Consumer) PollBatch(ctx context.Context, batchSize int, timeout time.Duration) (*MessageBatch, error) {
	batch := NewMessageBatch()
	deadline := time.Now().Add(timeout)

	claims := make(map[string]sarama.ConsumerGroupClaim)
	claimMsgs := make(map[string]<-chan *sarama.ConsumerMessage)
	var claimsMu sync.Mutex

claimLoop:
	for batch.Count() < batchSize && time.Now().Before(deadline) {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			break claimLoop
		}

		select {
		case <-ctx.Done():
			return batch, ctx.Err()
		default:
		}

		c.claimsMu.Lock()
		for topic, claim := range c.activeClaims {
			claimsMu.Lock()
			if _, ok := claims[topic]; !ok {
				claims[topic] = claim
				claimMsgs[topic] = claim.Messages()
			}
			claimsMu.Unlock()
		}
		c.claimsMu.Unlock()

		claimsMu.Lock()
		for topic, msgCh := range claimMsgs {
			select {
			case msg, ok := <-msgCh:
				if !ok {
					delete(claimMsgs, topic)
					claimsMu.Unlock()
					continue claimLoop
				}
				batch.Add(msg)
				claimsMu.Unlock()
				if batch.Count() >= batchSize {
					break claimLoop
				}
			case <-time.After(remaining):
				claimsMu.Unlock()
				break claimLoop
			case <-ctx.Done():
				claimsMu.Unlock()
				return batch, ctx.Err()
			}
		}
		if len(claimMsgs) == 0 {
			claimsMu.Unlock()
			break claimLoop
		}
		claimsMu.Unlock()
	}

	_ = claims
	return batch, nil
}
