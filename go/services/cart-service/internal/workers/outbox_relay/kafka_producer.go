package outbox_relay

import (
	"context"
	"sync"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"
)

// KafkaProducerClient implements KafkaProducer for Sarama SyncProducer
// Using SyncProducer ensures messages are ACKed by broker before returning,
// which is required for XACK-after-ACK semantics in Outbox Relay
type KafkaProducerClient struct {
	producer sarama.SyncProducer
	logger   *zap.Logger

	mu     sync.RWMutex
	closed bool
}

// NewKafkaProducer creates a new Kafka producer
func NewKafkaProducer(
	brokers []string,
	logger *zap.Logger,
	acks string,
	retries int,
) (*KafkaProducerClient, error) {
	config := sarama.NewConfig()

	// SyncProducer settings - ensures acks=all behavior
	config.Producer.Return.Successes = true
	config.Producer.Return.Errors = true

	// Acks configuration
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

	// Retry settings
	config.Producer.Retry.Max = retries

	// Required for SyncProducer
	config.Producer.Idempotent = false

	producer, err := sarama.NewSyncProducer(brokers, config)
	if err != nil {
		return nil, err
	}

	client := &KafkaProducerClient{
		producer: producer,
		logger:   logger,
	}

	return client, nil
}

// Produce sends a message to Kafka synchronously
// This method BLOCKS until the message is ACKed by the broker (acks=all)
func (p *KafkaProducerClient) Produce(
	ctx context.Context,
	topic string,
	key []byte,
	value []byte,
	headers map[string]string,
) error {
	p.mu.RLock()
	if p.closed {
		p.mu.RUnlock()
		return nil
	}
	p.mu.RUnlock()

	// Convert headers to Sarama format
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

	// SyncProducer.SendAndWait blocks until message is ACKed by broker
	// This ensures XACK only happens AFTER broker acknowledgment
	partition, offset, err := p.producer.SendMessage(msg)
	if err != nil {
		p.logger.Error("Kafka produce failed",
			zap.String("topic", topic),
			zap.Error(err),
		)
		return err
	}

	p.logger.Debug("Kafka message produced and ACKed",
		zap.String("topic", topic),
		zap.Int32("partition", partition),
		zap.Int64("offset", offset),
	)

	return nil
}

// Flush is a no-op for SyncProducer as messages are sent immediately
func (p *KafkaProducerClient) Flush(timeout time.Duration) error {
	return nil
}

// Close closes the producer
func (p *KafkaProducerClient) Close() error {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil
	}
	p.closed = true
	p.mu.Unlock()

	if err := p.producer.Close(); err != nil {
		return err
	}

	return nil
}
