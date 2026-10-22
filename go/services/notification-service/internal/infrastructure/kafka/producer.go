// Package kafka holds the sarama consumer-group runner every source topic
// shares and the sync producer used for dead letters and (poller mode) outbox
// publishing. Style follows go/services/auth-service and user-service.
package kafka

import (
	"context"
	"fmt"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/config"
	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var (
	_ port.DeadLetterPublisher = (*Producer)(nil)
	_ port.EventPublisher      = (*Producer)(nil)
)

// Header names shared with the other services' consumers.
const (
	HeaderContentType = "content-type"
	HeaderCEID        = "ce_id"
	HeaderCEType      = "ce_type"
	HeaderTraceParent = "traceparent"
	HeaderDLQReason   = "x-dlq-reason"
	HeaderDLQTopic    = "x-dlq-source-topic"
	// HeaderDLQGroup names the Notification consumer group that parked the
	// message, so a redrive can tell it apart from other consumers sharing the
	// same DLQ topic (decisions.md D-22).
	HeaderDLQGroup = "x-dlq-consumer-group"
)

// Producer is a synchronous, idempotent producer (acks=all).
type Producer struct {
	producer sarama.SyncProducer
	logger   *zap.Logger
}

// NewProducer opens the producer against cfg.Brokers.
func NewProducer(cfg config.KafkaConfig, logger *zap.Logger) (*Producer, error) {
	saramaCfg, err := baseConfig(cfg)
	if err != nil {
		return nil, err
	}
	saramaCfg.Producer.RequiredAcks = sarama.WaitForAll
	saramaCfg.Producer.Return.Successes = true
	saramaCfg.Producer.Idempotent = true
	saramaCfg.Producer.Retry.Max = 3
	saramaCfg.Producer.Partitioner = sarama.NewHashPartitioner
	saramaCfg.Producer.Timeout = 10 * time.Second
	saramaCfg.Net.MaxOpenRequests = 1

	p, err := sarama.NewSyncProducer(cfg.Brokers, saramaCfg)
	if err != nil {
		return nil, fmt.Errorf("kafka: create producer: %w", err)
	}
	return NewProducerWith(p, logger), nil
}

// NewProducerWith wraps an existing sync producer (tests, shared clients).
func NewProducerWith(p sarama.SyncProducer, logger *zap.Logger) *Producer {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Producer{producer: p, logger: logger}
}

// PublishRaw sends an already-serialised CloudEvent (notifctl publish-example,
// integration tests) with the standard headers.
func (p *Producer) PublishRaw(_ context.Context, topic, key, ceID, ceType string, value []byte) error {
	msg := &sarama.ProducerMessage{
		Topic: topic,
		Key:   sarama.StringEncoder(key),
		Value: sarama.ByteEncoder(value),
		Headers: []sarama.RecordHeader{
			{Key: []byte(HeaderContentType), Value: []byte(constants.KafkaContentType)},
			{Key: []byte(HeaderCEID), Value: []byte(ceID)},
			{Key: []byte(HeaderCEType), Value: []byte(ceType)},
		},
	}
	if _, _, err := p.producer.SendMessage(msg); err != nil {
		return fmt.Errorf("kafka: publish %s: %w", topic, err)
	}
	return nil
}

// PublishDeadLetter parks the raw message on the DLQ topic with its original
// headers plus x-dlq-reason, so an operator can inspect and redrive it
// (16-operations.md). The payload is never logged.
func (p *Producer) PublishDeadLetter(_ context.Context, topic string, key, value []byte, headers map[string]string, reason string) error {
	if topic == "" {
		return fmt.Errorf("kafka: dead-letter topic not configured")
	}
	msg := &sarama.ProducerMessage{
		Topic: topic,
		Value: sarama.ByteEncoder(value),
	}
	if len(key) > 0 {
		msg.Key = sarama.ByteEncoder(key)
	}
	for k, v := range headers {
		if k == HeaderDLQReason {
			continue
		}
		msg.Headers = append(msg.Headers, sarama.RecordHeader{Key: []byte(k), Value: []byte(v)})
	}
	msg.Headers = append(msg.Headers, sarama.RecordHeader{Key: []byte(HeaderDLQReason), Value: []byte(reason)})

	partition, offset, err := p.producer.SendMessage(msg)
	if err != nil {
		return fmt.Errorf("kafka: publish dead letter to %s: %w", topic, err)
	}
	p.logger.Warn("message parked on dead-letter topic",
		zap.String("topic", topic), zap.String("reason", reason),
		zap.Int32("partition", partition), zap.Int64("offset", offset))
	return nil
}

// Publish sends one outbox row as a structured CloudEvent. The key is the
// aggregate id (user_id) so per-user ordering holds downstream; the payload is
// the envelope already serialised at INSERT time (04-idempotency lớp D).
func (p *Producer) Publish(_ context.Context, topic string, key string, ev *entity.OutboxEvent) error {
	if topic == "" {
		return fmt.Errorf("kafka: outcome topic not configured")
	}
	if key == "" {
		key = ev.AggregateID
	}
	msg := &sarama.ProducerMessage{
		Topic: topic,
		Key:   sarama.StringEncoder(key),
		Value: sarama.ByteEncoder(ev.Payload),
		Headers: []sarama.RecordHeader{
			{Key: []byte(HeaderContentType), Value: []byte(constants.KafkaContentType)},
			{Key: []byte(HeaderCEID), Value: []byte(ev.ID.String())},
			{Key: []byte(HeaderCEType), Value: []byte(ev.Type)},
		},
	}
	partition, offset, err := p.producer.SendMessage(msg)
	if err != nil {
		return fmt.Errorf("kafka: publish %s: %w", ev.Type, err)
	}
	p.logger.Debug("published outcome event",
		zap.String("event_id", ev.ID.String()), zap.String("event_type", ev.Type),
		zap.String("topic", topic), zap.Int32("partition", partition), zap.Int64("offset", offset))
	return nil
}

// Close shuts the producer down.
func (p *Producer) Close() error { return p.producer.Close() }

// baseConfig builds the sarama config shared by producer and consumers.
func baseConfig(cfg config.KafkaConfig) (*sarama.Config, error) {
	saramaCfg := sarama.NewConfig()
	saramaCfg.Version = sarama.V3_5_0_0
	if cfg.Version != "" {
		v, err := sarama.ParseKafkaVersion(cfg.Version)
		if err != nil {
			return nil, fmt.Errorf("kafka: parse version %q: %w", cfg.Version, err)
		}
		saramaCfg.Version = v
	}
	saramaCfg.ClientID = cfg.ClientID
	if saramaCfg.ClientID == "" {
		saramaCfg.ClientID = "notification-service"
	}
	applySASL(saramaCfg, cfg.SASLUsername, cfg.SASLPassword)
	return saramaCfg, nil
}
