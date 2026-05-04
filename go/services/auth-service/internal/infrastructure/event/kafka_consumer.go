package event

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/confluentinc/confluent-kafka-go/v2/kafka"
	"go.uber.org/zap"
)

// EventHandler processes received Kafka events
type EventHandler func(ctx context.Context, event *KafkaEvent) error

// KafkaConsumer consumes events from Kafka topics
type KafkaConsumer struct {
	client         *kafka.Consumer
	groupID        string
	topic          string
	handlers       map[string]EventHandler
	logger         *zap.Logger
	ready          chan struct{}
	stopCh         chan struct{}
	wg             sync.WaitGroup
	mu             sync.RWMutex
}

// KafkaEvent represents a Kafka message event
type KafkaEvent struct {
	EventID       string          `json:"event_id"`
	EventType     string          `json:"event_type"`
	AggregateType string          `json:"aggregate_type"`
	AggregateID   string          `json:"aggregate_id"`
	Payload       json.RawMessage `json:"payload"`
	OccurredAt    time.Time       `json:"occurred_at"`
	Source        string          `json:"source,omitempty"`
}

// NewKafkaConsumer creates a new Kafka consumer
func NewKafkaConsumer(brokers []string, groupID string, topic string, logger *zap.Logger) (*KafkaConsumer, error) {
	brokerStr := fmt.Sprintf("%v", brokers)
	if len(brokers) == 0 {
		return nil, fmt.Errorf("no brokers provided")
	}
	if len(brokers) == 1 {
		brokerStr = brokers[0]
	}

	config := &kafka.ConfigMap{
		"bootstrap.servers":  brokerStr,
		"group.id":           groupID,
		"auto.offset.reset":  "earliest",
		"enable.auto.commit": false, // Manual commit for better control
		"session.timeout.ms": 6000,
		"heartbeat.interval.ms": 2000,
		"max.poll.interval.ms":  300000,
	}

	client, err := kafka.NewConsumer(config)
	if err != nil {
		return nil, fmt.Errorf("failed to create kafka consumer: %w", err)
	}

	return &KafkaConsumer{
		client:   client,
		groupID:  groupID,
		topic:    topic,
		handlers: make(map[string]EventHandler),
		logger:   logger,
		ready:    make(chan struct{}),
		stopCh:   make(chan struct{}),
	}, nil
}

// RegisterHandlers registers event handlers for different event types
func (c *KafkaConsumer) RegisterHandlers(handlers map[string]EventHandler) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.handlers = handlers
}

// Start begins consuming events from Kafka
func (c *KafkaConsumer) Start(ctx context.Context) error {
	c.logger.Info("Starting Kafka consumer",
		zap.String("topic", c.topic),
		zap.String("group_id", c.groupID))

	// Subscribe to topic
	if err := c.client.Subscribe(c.topic, nil); err != nil {
		return fmt.Errorf("failed to subscribe to topic %s: %w", c.topic, err)
	}

	c.wg.Add(1)
	go c.consumeLoop(ctx)

	// Wait for consumer to be ready
	select {
	case <-c.ready:
		c.logger.Info("Kafka consumer is ready")
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(30 * time.Second):
		return fmt.Errorf("timeout waiting for consumer to be ready")
	}

	return nil
}

// consumeLoop is the main consumer loop
func (c *KafkaConsumer) consumeLoop(ctx context.Context) {
	defer c.wg.Done()

	c.logger.Info("Consumer loop started", zap.String("topic", c.topic))
	close(c.ready) // Signal that consumer is ready

	for {
		select {
		case <-c.stopCh:
			c.logger.Info("Consumer loop stopping")
			return
		case <-ctx.Done():
			c.logger.Info("Consumer loop context cancelled")
			return
		default:
			c.consumeMessage(ctx)
		}
	}
}

// consumeMessage reads and processes a single message
func (c *KafkaConsumer) consumeMessage(ctx context.Context) {
	msg, err := c.client.ReadMessage(5 * time.Second)
	if err != nil {
		// Check if it's a timeout (no messages available)
		if kafkaErr, ok := err.(kafka.Error); ok && kafkaErr.Code() == kafka.ErrTimedOut {
			return
		}
		c.logger.Error("Error reading message", zap.Error(err))
		return
	}

	c.logger.Debug("Received message",
		zap.String("topic", *msg.TopicPartition.Topic),
		zap.Int32("partition", msg.TopicPartition.Partition),
		zap.Int64("offset", int64(msg.TopicPartition.Offset)))

	// Parse the event
	var event KafkaEvent
	if err := json.Unmarshal(msg.Value, &event); err != nil {
		c.logger.Error("Failed to unmarshal Kafka event",
			zap.Error(err),
			zap.ByteString("raw", msg.Value))
		// Commit the message to avoid reprocessing invalid messages
		c.commitMessage(msg)
		return
	}

	// Find and execute handler
	handler := c.getHandler(event.EventType)
	if handler == nil {
		c.logger.Debug("No handler for event type", zap.String("event_type", event.EventType))
		c.commitMessage(msg)
		return
	}

	// Process the event with timeout
	processCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	if err := handler(processCtx, &event); err != nil {
		c.logger.Error("Handler failed for event",
			zap.Error(err),
			zap.String("event_type", event.EventType),
			zap.String("aggregate_id", event.AggregateID))

		// Retry with exponential backoff
		c.retryWithBackoff(ctx, &event, handler)
	}

	// Commit the message after processing
	c.commitMessage(msg)
}

// getHandler returns the handler for an event type
func (c *KafkaConsumer) getHandler(eventType string) EventHandler {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.handlers[eventType]
}

// commitMessage commits the Kafka message offset
func (c *KafkaConsumer) commitMessage(msg *kafka.Message) {
	if _, err := c.client.CommitMessage(msg); err != nil {
		c.logger.Error("Failed to commit message",
			zap.Error(err),
			zap.Int64("offset", int64(msg.TopicPartition.Offset)))
	}
}

// retryWithBackoff retries a failed event with exponential backoff
func (c *KafkaConsumer) retryWithBackoff(ctx context.Context, event *KafkaEvent, handler EventHandler) {
	maxRetries := 3
	baseDelay := 1 * time.Second

	for attempt := 0; attempt < maxRetries; attempt++ {
		delay := baseDelay * time.Duration(1<<attempt) // Exponential backoff: 1s, 2s, 4s
		c.logger.Info("Retrying failed event",
			zap.String("event_type", event.EventType),
			zap.String("aggregate_id", event.AggregateID),
			zap.Int("attempt", attempt+1),
			zap.Duration("delay", delay))

		select {
		case <-ctx.Done():
			c.logger.Warn("Retry cancelled due to context", zap.String("event_id", event.EventID))
			return
		case <-time.After(delay):
			processCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
			if err := handler(processCtx, event); err != nil {
				c.logger.Error("Retry failed",
					zap.Error(err),
					zap.String("event_type", event.EventType),
					zap.Int("attempt", attempt+1))
			} else {
				c.logger.Info("Retry succeeded",
					zap.String("event_type", event.EventType),
					zap.String("aggregate_id", event.AggregateID))
				cancel()
				return
			}
			cancel()
		}
	}

	c.logger.Error("All retries exhausted for event",
		zap.String("event_type", event.EventType),
		zap.String("aggregate_id", event.AggregateID))
}

// Stop stops the Kafka consumer
func (c *KafkaConsumer) Stop() error {
	c.logger.Info("Stopping Kafka consumer")

	// Signal consumers to stop
	close(c.stopCh)

	// Wait for consumers to finish
	c.wg.Wait()

	// Close the consumer
	if err := c.client.Close(); err != nil {
		return fmt.Errorf("failed to close kafka consumer: %w", err)
	}

	c.logger.Info("Kafka consumer stopped")
	return nil
}

// GetTopic returns the topic the consumer is subscribed to
func (c *KafkaConsumer) GetTopic() string {
	return c.topic
}

// GetGroupID returns the consumer group ID
func (c *KafkaConsumer) GetGroupID() string {
	return c.groupID
}

// AdminForceLogoutPayload represents the payload for admin force logout event
type AdminForceLogoutPayload struct {
	UserID  string `json:"user_id"`
	Reason  string `json:"reason"`
	ActorID string `json:"actor_id"`
}

// UserBannedPayload represents the payload for user banned event
type UserBannedPayload struct {
	UserID  string `json:"user_id"`
	Reason  string `json:"reason"`
	ActorID string `json:"actor_id"`
}

// Event type constants for Kafka consumer
const (
	EventTypeAdminForceLogout     = "ADMIN_FORCE_LOGOUT"
	EventTypeUserBanned           = "USER_BANNED"
	EventTypeUserUnbanned         = "USER_UNBANNED"
	EventTypeUserBannedNotification = "USER_BANNED_NOTIFICATION"
)

// ValidAdminEventTypes returns all valid admin event types
func ValidAdminEventTypes() []string {
	return []string{
		EventTypeAdminForceLogout,
		EventTypeUserBanned,
		EventTypeUserUnbanned,
		EventTypeUserBannedNotification,
	}
}
