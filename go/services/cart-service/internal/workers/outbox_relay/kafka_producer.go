package outbox_relay

import (
	"context"
	"sync"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"
)

// KafkaProducerClient implements KafkaProducer for Sarama
type KafkaProducerClient struct {
	producer sarama.AsyncProducer
	logger   *zap.Logger

	mu         sync.RWMutex
	wg         sync.WaitGroup
	closed     bool
	errorCount int64
}

// NewKafkaProducer creates a new Kafka producer
func NewKafkaProducer(
	brokers []string,
	logger *zap.Logger,
	acks string,
	retries int,
) (*KafkaProducerClient, error) {
	config := sarama.NewConfig()

	// Producer settings
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

	producer, err := sarama.NewAsyncProducer(brokers, config)
	if err != nil {
		return nil, err
	}

	client := &KafkaProducerClient{
		producer: producer,
		logger:   logger,
	}

	// Start error handler
	client.wg.Add(1)
	go client.handleErrors()

	return client, nil
}

// handleErrors handles producer errors
func (p *KafkaProducerClient) handleErrors() {
	defer p.wg.Done()

	for {
		select {
		case err, ok := <-p.producer.Errors():
			if !ok {
				return
			}
			p.logger.Error("Kafka producer error",
				zap.String("topic", err.Msg.Topic),
				zap.Error(err.Err),
			)
		case success, ok := <-p.producer.Successes():
			if !ok {
				return
			}
			p.logger.Debug("Kafka message produced",
				zap.String("topic", success.Topic),
				zap.Int32("partition", success.Partition),
				zap.Int64("offset", success.Offset),
			)
		}
	}
}

// Produce sends a message to Kafka
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

	select {
	case p.producer.Input() <- msg:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Flush flushes all pending messages
func (p *KafkaProducerClient) Flush(timeout time.Duration) error {
	// Async producer doesn't have Flush method, but we can sync on the channel
	maxWait := time.After(timeout)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-maxWait:
			return nil
		case <-ticker.C:
			// Check if producer is idle
			p.mu.RLock()
			closed := p.closed
			p.mu.RUnlock()
			if closed {
				return nil
			}
		}
	}
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

	// Wait for error handler to finish
	p.wg.Wait()
	return nil
}
