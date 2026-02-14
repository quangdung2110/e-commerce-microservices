package kafka

import (
	"encoding/json"
	"fmt"

	"github.com/confluentinc/confluent-kafka-go/v2/kafka"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/pkg/events"
)

// EventPublisher interface for publishing domain events
// Equivalent to Java's com.example.common.publisher.EventPublisher
type EventPublisher interface {
	Publish(event events.DomainEvent, routingKey string) error
	Close() error
}

// KafkaEventPublisher publishes events to Kafka topics
// Equivalent to Java's com.example.common.publisher.KafkaEventPublisher
type KafkaEventPublisher struct {
	producer *kafka.Producer
	topic    string
	logger   *zap.Logger
}

// NewKafkaEventPublisher creates a new Kafka event publisher
func NewKafkaEventPublisher(brokers string, topic string, logger *zap.Logger) (*KafkaEventPublisher, error) {
	if logger == nil {
		logger, _ = zap.NewProduction()
	}

	config := &kafka.ConfigMap{
		"bootstrap.servers":                     brokers,
		"client.id":                             "event-publisher",
		"acks":                                  "all",
		"enable.idempotence":                    true,
		"max.in.flight.requests.per.connection": 5,
		"compression.type":                      "snappy",
		"linger.ms":                             10,
		"batch.size":                            16384,
	}

	producer, err := kafka.NewProducer(config)
	if err != nil {
		return nil, fmt.Errorf("failed to create producer: %w", err)
	}

	// Start delivery report handler goroutine
	go func() {
		for e := range producer.Events() {
			switch ev := e.(type) {
			case *kafka.Message:
				if ev.TopicPartition.Error != nil {
					logger.Error("Failed to deliver message",
						zap.String("topic", *ev.TopicPartition.Topic),
						zap.Error(ev.TopicPartition.Error))
				} else {
					logger.Info("Message delivered successfully",
						zap.String("topic", *ev.TopicPartition.Topic),
						zap.Int32("partition", ev.TopicPartition.Partition),
						zap.Int64("offset", int64(ev.TopicPartition.Offset)))
				}
			}
		}
	}()

	return &KafkaEventPublisher{
		producer: producer,
		topic:    topic,
		logger:   logger,
	}, nil
}

// Publish publishes an event to the Kafka topic
func (p *KafkaEventPublisher) Publish(event events.DomainEvent, routingKey string) error {
	// Serialize event to JSON
	eventJSON, err := json.Marshal(event)
	if err != nil {
		p.logger.Error("Failed to serialize event",
			zap.String("eventId", event.GetEventID()),
			zap.String("eventType", event.GetEventType()),
			zap.Error(err))
		return fmt.Errorf("failed to serialize event: %w", err)
	}

	// Use aggregateID as key for partition assignment (ensures ordering per aggregate)
	key := event.GetAggregateID()
	if routingKey != "" {
		key = routingKey
	}

	// Produce message
	err = p.producer.Produce(&kafka.Message{
		TopicPartition: kafka.TopicPartition{
			Topic:     &p.topic,
			Partition: kafka.PartitionAny,
		},
		Key:   []byte(key),
		Value: eventJSON,
		Headers: []kafka.Header{
			{Key: "eventType", Value: []byte(event.GetEventType())},
			{Key: "eventId", Value: []byte(event.GetEventID())},
		},
	}, nil)

	if err != nil {
		p.logger.Error("Failed to produce event",
			zap.String("eventId", event.GetEventID()),
			zap.String("eventType", event.GetEventType()),
			zap.String("topic", p.topic),
			zap.Error(err))
		return fmt.Errorf("failed to produce event: %w", err)
	}

	p.logger.Info("Event published",
		zap.String("eventId", event.GetEventID()),
		zap.String("eventType", event.GetEventType()),
		zap.String("aggregateId", event.GetAggregateID()),
		zap.String("topic", p.topic))

	return nil
}

// Flush ensures all messages are delivered
func (p *KafkaEventPublisher) Flush(timeoutMs int) int {
	return p.producer.Flush(timeoutMs)
}

// Close closes the producer and releases resources
func (p *KafkaEventPublisher) Close() error {
	// Wait for all messages to be delivered
	remaining := p.producer.Flush(10000) // 10 seconds timeout
	if remaining > 0 {
		p.logger.Warn("Not all messages were delivered before closing",
			zap.Int("remaining", remaining))
	}

	p.producer.Close()
	p.logger.Info("Kafka event publisher closed")
	return nil
}
