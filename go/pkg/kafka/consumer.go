package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/confluentinc/confluent-kafka-go/v2/kafka"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/pkg/events"
)

// EventHandler processes received events
type EventHandler func(event events.DomainEvent) error

// EventConsumer interface for consuming domain events
// Equivalent to Java's com.example.common.consumer.EventConsumer
type EventConsumer interface {
	Start(ctx context.Context) error
	Stop() error
}

// KafkaEventConsumer consumes events from Kafka topics
// Equivalent to Java's com.example.common.consumer.KafkaEventConsumer
type KafkaEventConsumer struct {
	consumer        *kafka.Consumer
	topics          []string
	groupID         string
	deadLetterTopic string
	handler         EventHandler
	publisher       *KafkaEventPublisher
	logger          *zap.Logger

	// Idempotency tracking (in-memory, replace with Redis in production)
	processedEvents  map[string]bool
	processedMutex   sync.RWMutex
	maxProcessedSize int
}

// ConsumerConfig holds configuration for Kafka consumer
type ConsumerConfig struct {
	Brokers         string
	Topics          []string
	GroupID         string
	DeadLetterTopic string
	Handler         EventHandler
	Logger          *zap.Logger
}

// NewKafkaEventConsumer creates a new Kafka event consumer
func NewKafkaEventConsumer(config ConsumerConfig) (*KafkaEventConsumer, error) {
	if config.Logger == nil {
		config.Logger, _ = zap.NewProduction()
	}

	kafkaConfig := &kafka.ConfigMap{
		"bootstrap.servers":     config.Brokers,
		"group.id":              config.GroupID,
		"auto.offset.reset":     "earliest",
		"enable.auto.commit":    false, // Manual commit for better control
		"session.timeout.ms":    6000,
		"heartbeat.interval.ms": 2000,
		"max.poll.interval.ms":  300000,
		"enable.partition.eof":  false,
	}

	consumer, err := kafka.NewConsumer(kafkaConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create consumer: %w", err)
	}

	// Create publisher for dead letter queue
	var publisher *KafkaEventPublisher
	if config.DeadLetterTopic != "" {
		publisher, err = NewKafkaEventPublisher(config.Brokers, config.DeadLetterTopic, config.Logger)
		if err != nil {
			consumer.Close()
			return nil, fmt.Errorf("failed to create DLQ publisher: %w", err)
		}
	}

	return &KafkaEventConsumer{
		consumer:         consumer,
		topics:           config.Topics,
		groupID:          config.GroupID,
		deadLetterTopic:  config.DeadLetterTopic,
		handler:          config.Handler,
		publisher:        publisher,
		logger:           config.Logger,
		processedEvents:  make(map[string]bool),
		maxProcessedSize: 10000,
	}, nil
}

// Start begins consuming events from Kafka
func (c *KafkaEventConsumer) Start(ctx context.Context) error {
	err := c.consumer.SubscribeTopics(c.topics, nil)
	if err != nil {
		return fmt.Errorf("failed to subscribe to topics: %w", err)
	}

	c.logger.Info("Started consuming events",
		zap.Strings("topics", c.topics),
		zap.String("groupId", c.groupID))

	for {
		select {
		case <-ctx.Done():
			c.logger.Info("Context cancelled, stopping consumer")
			return ctx.Err()
		default:
			msg, err := c.consumer.ReadMessage(100 * time.Millisecond)
			if err != nil {
				if err.(kafka.Error).Code() == kafka.ErrTimedOut {
					continue
				}
				c.logger.Error("Error reading message", zap.Error(err))
				continue
			}

			c.handleMessage(msg)
		}
	}
}

// handleMessage processes a single Kafka message
func (c *KafkaEventConsumer) handleMessage(msg *kafka.Message) {
	c.logger.Info("Received event",
		zap.String("topic", *msg.TopicPartition.Topic),
		zap.Int32("partition", msg.TopicPartition.Partition),
		zap.Int64("offset", int64(msg.TopicPartition.Offset)))

	// Deserialize base event to check eventId
	var baseEvent events.BaseEvent
	if err := json.Unmarshal(msg.Value, &baseEvent); err != nil {
		c.logger.Error("Failed to deserialize event", zap.Error(err))
		c.sendToDeadLetterQueue(msg, err)
		c.commitMessage(msg)
		return
	}

	// Idempotency check
	if c.isAlreadyProcessed(baseEvent.EventID) {
		c.logger.Warn("Event already processed (idempotency)",
			zap.String("eventId", baseEvent.EventID),
			zap.String("eventType", baseEvent.EventType))
		c.commitMessage(msg)
		return
	}

	// Process event
	if err := c.handler(&baseEvent); err != nil {
		c.logger.Error("Failed to process event",
			zap.String("eventId", baseEvent.EventID),
			zap.String("eventType", baseEvent.EventType),
			zap.Error(err))
		c.sendToDeadLetterQueue(msg, err)
		c.commitMessage(msg)
		return
	}

	// Mark as processed
	c.markAsProcessed(baseEvent.EventID)

	// Commit offset
	c.commitMessage(msg)

	c.logger.Info("Event processed successfully",
		zap.String("eventId", baseEvent.EventID),
		zap.String("eventType", baseEvent.EventType),
		zap.String("aggregateId", baseEvent.AggregateID))
}

// isAlreadyProcessed checks if event was already processed (idempotency)
func (c *KafkaEventConsumer) isAlreadyProcessed(eventID string) bool {
	c.processedMutex.RLock()
	defer c.processedMutex.RUnlock()
	return c.processedEvents[eventID]
}

// markAsProcessed marks an event as processed
func (c *KafkaEventConsumer) markAsProcessed(eventID string) {
	c.processedMutex.Lock()
	defer c.processedMutex.Unlock()

	// Simple eviction strategy when max size reached
	if len(c.processedEvents) >= c.maxProcessedSize {
		c.logger.Warn("Processed events cache full, clearing",
			zap.Int("size", len(c.processedEvents)))
		c.processedEvents = make(map[string]bool)
	}

	c.processedEvents[eventID] = true
}

// sendToDeadLetterQueue sends failed events to DLQ
func (c *KafkaEventConsumer) sendToDeadLetterQueue(msg *kafka.Message, processingError error) {
	if c.publisher == nil || c.deadLetterTopic == "" {
		c.logger.Warn("No dead letter queue configured, event lost")
		return
	}

	var baseEvent events.BaseEvent
	if err := json.Unmarshal(msg.Value, &baseEvent); err != nil {
		c.logger.Error("Failed to deserialize event for DLQ", zap.Error(err))
		return
	}

	c.logger.Error("Sending event to Dead Letter Queue",
		zap.String("eventId", baseEvent.EventID),
		zap.String("topic", c.deadLetterTopic),
		zap.Error(processingError))

	if err := c.publisher.Publish(&baseEvent, baseEvent.AggregateID); err != nil {
		c.logger.Error("Failed to send event to DLQ",
			zap.String("eventId", baseEvent.EventID),
			zap.Error(err))
	}
}

// commitMessage commits the message offset
func (c *KafkaEventConsumer) commitMessage(msg *kafka.Message) {
	if _, err := c.consumer.CommitMessage(msg); err != nil {
		c.logger.Error("Failed to commit message",
			zap.String("topic", *msg.TopicPartition.Topic),
			zap.Int32("partition", msg.TopicPartition.Partition),
			zap.Int64("offset", int64(msg.TopicPartition.Offset)),
			zap.Error(err))
	}
}

// Stop stops the consumer and releases resources
func (c *KafkaEventConsumer) Stop() error {
	c.logger.Info("Stopping Kafka event consumer")

	if c.publisher != nil {
		if err := c.publisher.Close(); err != nil {
			c.logger.Error("Failed to close DLQ publisher", zap.Error(err))
		}
	}

	if err := c.consumer.Close(); err != nil {
		c.logger.Error("Failed to close consumer", zap.Error(err))
		return err
	}

	c.logger.Info("Kafka event consumer stopped")
	return nil
}
