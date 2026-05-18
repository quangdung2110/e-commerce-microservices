package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"
)

// KafkaProducer implements port.KafkaProducer
type KafkaProducer struct {
	producer sarama.AsyncProducer
	logger  *zap.Logger
}

// NewKafkaProducer creates a new Kafka producer
func NewKafkaProducer(brokers []string, logger *zap.Logger, acks string, retries int) (*KafkaProducer, error) {
	config := sarama.NewConfig()

	config.Producer.Return.Successes = true
	config.Producer.Return.Errors = true

	switch acks {
	case "all", "-1":
		config.Producer.RequiredAcks = sarama.WaitForAll
	case "1":
		config.Producer.RequiredAcks = sarama.WaitForLocal
	case "0":
		config.Producer.RequiredAcks = sarama.NoResponse
	default:
		config.Producer.RequiredAcks = sarama.WaitForAll
	}

	config.Producer.Retry.Max = retries

	producer, err := sarama.NewAsyncProducer(brokers, config)
	if err != nil {
		return nil, err
	}

	return &KafkaProducer{
		producer: producer,
		logger:  logger,
	}, nil
}

// Produce sends a message to Kafka
func (p *KafkaProducer) Produce(ctx context.Context, topic string, key []byte, value []byte, headers map[string]string) error {
	saramaHeaders := make([]sarama.RecordHeader, 0, len(headers))
	for k, v := range headers {
		saramaHeaders = append(saramaHeaders, sarama.RecordHeader{
			Key:   []byte(k),
			Value: []byte(v),
		})
	}

	msg := &sarama.ProducerMessage{
		Topic:   topic,
		Key:     sarama.ByteEncoder(key),
		Value:   sarama.ByteEncoder(value),
		Headers: saramaHeaders,
	}

	select {
	case p.producer.Input() <- msg:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Flush flushes all pending messages
func (p *KafkaProducer) Flush(timeout time.Duration) error {
	maxWait := time.After(timeout)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-maxWait:
			return nil
		case <-ticker.C:
		}
	}
}

// Close closes the producer
func (p *KafkaProducer) Close() error {
	return p.producer.Close()
}

// KafkaConsumer implements port.KafkaConsumer for consuming cart events
type KafkaConsumer struct {
	client  sarama.ConsumerGroup
	topic   string
	groupID string
	logger  *zap.Logger
}

// ConsumedMessage represents a consumed Kafka message
type ConsumedMessage struct {
	Topic     string
	Partition int32
	Offset    int64
	Key       []byte
	Value     []byte
	Headers   map[string]string
}

// ConsumerHandlers defines the handlers for consuming messages
type ConsumerHandlers interface {
	HandleBatch(ctx context.Context, messages []*ConsumedMessage) error
}

// NewKafkaConsumer creates a new Kafka consumer
func NewKafkaConsumer(brokers []string, topic, groupID string, logger *zap.Logger) (*KafkaConsumer, error) {
	saramaConfig := sarama.NewConfig()

	saramaConfig.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{
		sarama.NewBalanceStrategyRoundRobin(),
	}
	saramaConfig.Consumer.Group.Session.Timeout = 30 * time.Second
	saramaConfig.Consumer.Group.Heartbeat.Interval = 3 * time.Second
	saramaConfig.Consumer.Offsets.Initial = sarama.OffsetNewest
	saramaConfig.Consumer.Offsets.AutoCommit.Enable = false

	client, err := sarama.NewConsumerGroup(brokers, groupID, saramaConfig)
	if err != nil {
		return nil, err
	}

	return &KafkaConsumer{
		client:  client,
		topic:   topic,
		groupID: groupID,
		logger:  logger,
	}, nil
}

// Close closes the consumer
func (c *KafkaConsumer) Close() error {
	return c.client.Close()
}

// CartEvent represents a parsed cart event from Kafka
type CartEvent struct {
	EventID          string                 `json:"event_id"`
	EventType        string                 `json:"event_type"`
	AggregateType    string                 `json:"aggregate_type"`
	AggregateID      string                 `json:"aggregate_id"`
	CorrelationID    string                 `json:"correlation_id,omitempty"`
	Timestamp        string                 `json:"timestamp"`
	Source           string                 `json:"source"`
	StreamID         string                 `json:"stream_id,omitempty"`
	Payload          map[string]interface{} `json:"payload"`
}

// ParseCartEvent parses a cart event from Kafka message
func ParseCartEvent(data []byte) (*CartEvent, error) {
	var event CartEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, fmt.Errorf("failed to unmarshal cart event: %w", err)
	}
	return &event, nil
}

// GetPayloadField extracts a field from the event payload
func (e *CartEvent) GetPayloadField(key string) string {
	if e.Payload == nil {
		return ""
	}
	if v, ok := e.Payload[key].(string); ok {
		return v
	}
	return ""
}

// GetPayloadStringSlice extracts a string slice field from the event payload
func (e *CartEvent) GetPayloadStringSlice(key string) []string {
	if e.Payload == nil {
		return nil
	}
	if v, ok := e.Payload[key].([]interface{}); ok {
		result := make([]string, 0, len(v))
		for _, item := range v {
			if s, ok := item.(string); ok {
				result = append(result, s)
			}
		}
		return result
	}
	return nil
}

// IsValidCartID validates the cart ID format
func IsValidCartID(cartID string) bool {
	return strings.HasPrefix(cartID, "{cart:user:") && strings.HasSuffix(cartID, "}")
}
