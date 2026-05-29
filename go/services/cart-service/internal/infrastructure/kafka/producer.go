package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/IBM/sarama"

	"cart-service/internal/domain/port"
)

// Publisher implements port.KafkaPublisher using Sarama sync producer.
type Publisher struct {
	producer sarama.SyncProducer
	topic    string
}

// NewPublisher creates a new Kafka publisher.
func NewPublisher(cfg Config) (*Publisher, error) {
	config, err := NewSaramaConfig(cfg, true)
	if err != nil {
		return nil, err
	}

	producer, err := sarama.NewSyncProducer(cfg.Brokers, config)
	if err != nil {
		return nil, fmt.Errorf("create Kafka producer: %w", err)
	}

	return &Publisher{
		producer: producer,
		topic:    cfg.TopicCartEvents,
	}, nil
}

var _ port.KafkaPublisher = (*Publisher)(nil)

// Publish publishes a cart event to the Kafka topic.
func (p *Publisher) Publish(ctx context.Context, msg *port.CartEventMessage) error {
	jsonBytes, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal event: %w", err)
	}

	producerMsg := &sarama.ProducerMessage{
		Topic: p.topic,
		Key:   sarama.StringEncoder(msg.AggregateID),
		Value: sarama.ByteEncoder(jsonBytes),
		Headers: []sarama.RecordHeader{
			{Key: []byte("event_type"), Value: []byte(msg.EventType)},
			{Key: []byte("stream_id"), Value: []byte(msg.StreamID)},
		},
		Timestamp: time.Now(),
	}

	partition, offset, err := p.producer.SendMessage(producerMsg)
	if err != nil {
		return fmt.Errorf("send message: %w", err)
	}

	_ = partition
	_ = offset

	return nil
}

// Close closes the Kafka producer.
func (p *Publisher) Close() error {
	return p.producer.Close()
}

// AsyncPublisher is an async version for high-throughput scenarios.
type AsyncPublisher struct {
	producer     sarama.AsyncProducer
	topic       string
	errChan     chan error
	successChan chan struct{}
	wg          sync.WaitGroup
}

// NewAsyncPublisher creates an async Kafka publisher.
func NewAsyncPublisher(cfg Config) (*AsyncPublisher, error) {
	config, err := NewSaramaConfig(cfg, true)
	if err != nil {
		return nil, err
	}

	config.Producer.Return.Successes = true
	config.Producer.Return.Errors = true

	producer, err := sarama.NewAsyncProducer(cfg.Brokers, config)
	if err != nil {
		return nil, fmt.Errorf("create async producer: %w", err)
	}

	ap := &AsyncPublisher{
		producer:     producer,
		topic:       cfg.TopicCartEvents,
		errChan:     make(chan error, 100),
		successChan: make(chan struct{}, 100),
	}

	ap.wg.Add(1)
	go func() {
		defer ap.wg.Done()
		for err := range producer.Errors() {
			select {
			case ap.errChan <- err:
			default:
			}
		}
	}()

	ap.wg.Add(1)
	go func() {
		defer ap.wg.Done()
		for range producer.Successes() {
			select {
			case ap.successChan <- struct{}{}:
			default:
			}
		}
	}()

	return ap, nil
}

// PublishAsync sends a message asynchronously.
func (p *AsyncPublisher) PublishAsync(ctx context.Context, msg *port.CartEventMessage) error {
	jsonBytes, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal event: %w", err)
	}

	producerMsg := &sarama.ProducerMessage{
		Topic: p.topic,
		Key:   sarama.StringEncoder(msg.AggregateID),
		Value: sarama.ByteEncoder(jsonBytes),
		Headers: []sarama.RecordHeader{
			{Key: []byte("event_type"), Value: []byte(msg.EventType)},
			{Key: []byte("stream_id"), Value: []byte(msg.StreamID)},
		},
	}

	select {
	case p.producer.Input() <- producerMsg:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Errors returns the error channel for async errors.
func (p *AsyncPublisher) Errors() <-chan error {
	return p.errChan
}

// Successes returns the success channel for async acknowledgements.
func (p *AsyncPublisher) Successes() <-chan struct{} {
	return p.successChan
}

// Close gracefully shuts down the async producer.
func (p *AsyncPublisher) Close() error {
	if err := p.producer.Close(); err != nil {
		return err
	}
	p.wg.Wait()
	close(p.errChan)
	close(p.successChan)
	return nil
}
