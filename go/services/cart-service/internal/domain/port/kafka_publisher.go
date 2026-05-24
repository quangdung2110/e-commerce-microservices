package port

import "context"

// CartEventMessage represents a cart event message to be published to Kafka.
type CartEventMessage struct {
	EventID           string                 `json:"event_id"`
	EventType         string                 `json:"event_type"`
	AggregateType    string                 `json:"aggregate_type"`
	AggregateID      string                 `json:"aggregate_id"`
	CorrelationID    string                 `json:"correlation_id"`
	Timestamp        string                 `json:"timestamp"`          // ISO 8601 UTC
	Source           string                 `json:"source"`            // "cart-service"
	StreamID         string                 `json:"stream_id"`         // Redis Stream ID
	StreamTimestampMs string               `json:"stream_timestamp_ms"`
	EventSequence    string                 `json:"event_sequence"`
	Payload          map[string]interface{} `json:"payload"`
}

// KafkaPublisher defines the interface for publishing cart events to Kafka.
type KafkaPublisher interface {
	// Publish publishes a single cart event to the Kafka topic.
	// Partition key: cart_id (ensures events for same cart go to same partition).
	// Uses acks=all for durability.
	Publish(ctx context.Context, msg *CartEventMessage) error

	// Close closes the Kafka producer.
	Close() error
}
