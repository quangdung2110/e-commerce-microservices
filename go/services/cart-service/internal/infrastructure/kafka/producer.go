// Package kafka carries CART_ABANDONED out to Notification and brings
// com.ecommerce.order.paid.v1 in from Order and com.ecommerce.auth.user-deleted.v1 in from Auth.
package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/IBM/sarama"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
)

// ProducerConfig configures the CART_ABANDONED producer.
type ProducerConfig struct {
	Brokers []string
	// Topic is the cart event topic. Only CART_ABANDONED is published to it.
	Topic string
	// DeadLetterTopic receives entries that can never succeed, such as an
	// outbox record whose payload does not parse.
	DeadLetterTopic string
	// RequiredAcks is "all", "local" or "none". Anything but "all" trades away
	// the durability the outbox exists to provide.
	RequiredAcks string
	Timeout      time.Duration
	TLSEnabled   bool
	// SASLUsername and SASLPassword are the service's SCRAM principal (mesh
	// ADR-003); empty keeps PLAINTEXT.
	SASLUsername string
	SASLPassword string
}

// Producer publishes sealed CloudEvents.
type Producer struct {
	producer sarama.SyncProducer
	cfg      ProducerConfig
}

// NewProducer builds a synchronous producer.
//
// The relay must know whether Kafka accepted an entry before it acks the
// Redis stream, so an asynchronous producer is not an option here.
func NewProducer(cfg ProducerConfig) (*Producer, error) {
	saramaCfg := sarama.NewConfig()
	saramaCfg.Producer.Return.Successes = true
	saramaCfg.Producer.Return.Errors = true
	saramaCfg.Producer.RequiredAcks = requiredAcks(cfg.RequiredAcks)
	saramaCfg.Producer.Idempotent = saramaCfg.Producer.RequiredAcks == sarama.WaitForAll
	if saramaCfg.Producer.Idempotent {
		saramaCfg.Net.MaxOpenRequests = 1
	}
	saramaCfg.Producer.Partitioner = sarama.NewHashPartitioner
	saramaCfg.Producer.Timeout = cfg.Timeout
	saramaCfg.Net.TLS.Enable = cfg.TLSEnabled
	applySASL(saramaCfg, cfg.SASLUsername, cfg.SASLPassword)

	producer, err := sarama.NewSyncProducer(cfg.Brokers, saramaCfg)
	if err != nil {
		return nil, fmt.Errorf("create kafka producer: %w", err)
	}
	return &Producer{producer: producer, cfg: cfg}, nil
}

var _ port.EventPublisher = (*Producer)(nil)

// Publish sends one CloudEvent, keyed by cart id so that every event for one
// cart lands on the same partition and stays ordered.
func (p *Producer) Publish(ctx context.Context, event domain.CloudEvent, headers map[string]string) error {
	payload, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("encode cloud event %s: %w", event.ID, err)
	}

	message := &sarama.ProducerMessage{
		Topic:   p.cfg.Topic,
		Key:     sarama.StringEncoder(event.Data.CartID),
		Value:   sarama.ByteEncoder(payload),
		Headers: recordHeaders(headers),
	}

	if _, _, err := p.producer.SendMessage(message); err != nil {
		return fmt.Errorf("publish cloud event %s to %s: %w", event.ID, p.cfg.Topic, err)
	}
	return nil
}

// PublishDeadLetter routes an entry that can never succeed to the DLQ.
func (p *Producer) PublishDeadLetter(ctx context.Context, key string, payload []byte, headers map[string]string) error {
	if p.cfg.DeadLetterTopic == "" {
		return fmt.Errorf("no dead letter topic is configured")
	}

	message := &sarama.ProducerMessage{
		Topic:   p.cfg.DeadLetterTopic,
		Key:     sarama.StringEncoder(key),
		Value:   sarama.ByteEncoder(payload),
		Headers: recordHeaders(headers),
	}

	if _, _, err := p.producer.SendMessage(message); err != nil {
		return fmt.Errorf("publish to dead letter topic %s: %w", p.cfg.DeadLetterTopic, err)
	}
	return nil
}

// Ready reports whether the producer has a usable broker connection.
func (p *Producer) Ready(ctx context.Context) error {
	clientCfg := sarama.NewConfig()
	clientCfg.Net.TLS.Enable = p.cfg.TLSEnabled
	applySASL(clientCfg, p.cfg.SASLUsername, p.cfg.SASLPassword)
	client, err := sarama.NewClient(p.cfg.Brokers, clientCfg)
	if err != nil {
		return fmt.Errorf("kafka is unreachable: %w", err)
	}
	defer client.Close()

	if len(client.Brokers()) == 0 {
		return fmt.Errorf("kafka reports no available brokers")
	}
	return nil
}

// Close flushes and shuts the producer down.
func (p *Producer) Close() error {
	return p.producer.Close()
}

func recordHeaders(headers map[string]string) []sarama.RecordHeader {
	if len(headers) == 0 {
		return nil
	}
	out := make([]sarama.RecordHeader, 0, len(headers))
	for key, value := range headers {
		out = append(out, sarama.RecordHeader{
			Key:   []byte(key),
			Value: []byte(value),
		})
	}
	return out
}

func requiredAcks(setting string) sarama.RequiredAcks {
	switch setting {
	case "none":
		return sarama.NoResponse
	case "local":
		return sarama.WaitForLocal
	default:
		return sarama.WaitForAll
	}
}
