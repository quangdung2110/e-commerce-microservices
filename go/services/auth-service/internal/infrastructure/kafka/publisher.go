// Package kafka publishes the identity and notification events of Auth.
//
// ADR-005 accepts a direct producer instead of a transactional outbox: the
// message is sent after the PostgreSQL transaction committed, retried a bounded
// number of times with the same CloudEvent id, and — if every attempt fails —
// reported for operational reconciliation. Consumers deduplicate on that id.
package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.EventPublisher = (*Publisher)(nil)

// contentType marks the payload as a structured CloudEvent.
const contentType = "application/cloudevents+json"

// Publisher sends CloudEvents to Kafka with bounded retry.
type Publisher struct {
	producer    sarama.SyncProducer
	topics      map[event.Channel]string
	maxAttempts int
	backoff     time.Duration
	timeout     time.Duration
	logger      *zap.Logger
}

// NewPublisher opens a synchronous producer. Acks are required from every
// in-sync replica and the producer is idempotent, so a broker-side retry cannot
// duplicate a message inside one send.
func NewPublisher(cfg config.KafkaConfig, logger *zap.Logger) (*Publisher, error) {
	saramaCfg := sarama.NewConfig()
	saramaCfg.Version = sarama.V3_5_0_0
	saramaCfg.ClientID = "auth-service"
	saramaCfg.Producer.RequiredAcks = sarama.WaitForAll
	saramaCfg.Producer.Return.Successes = true
	saramaCfg.Producer.Idempotent = true
	saramaCfg.Producer.Retry.Max = 3
	saramaCfg.Producer.Partitioner = sarama.NewHashPartitioner
	saramaCfg.Net.MaxOpenRequests = 1
	if cfg.PublishTimeout > 0 {
		saramaCfg.Producer.Timeout = cfg.PublishTimeout
	}

	producer, err := sarama.NewSyncProducer(cfg.Brokers, saramaCfg)
	if err != nil {
		return nil, fmt.Errorf("kafka: create producer: %w", err)
	}

	maxAttempts := cfg.PublishMaxAttempts
	if maxAttempts < 1 {
		maxAttempts = 1
	}

	return &Publisher{
		producer: producer,
		topics: map[event.Channel]string{
			event.ChannelIdentity:     cfg.IdentityTopic,
			event.ChannelNotification: cfg.NotificationTopic,
		},
		maxAttempts: maxAttempts,
		backoff:     cfg.PublishBackoff,
		timeout:     cfg.PublishTimeout,
		logger:      logger,
	}, nil
}

// Publish sends one event. The partition key is the user id, which is what makes
// per-account ordering the only ordering the contract promises.
func (p *Publisher) Publish(ctx context.Context, envelope *event.Envelope) error {
	topic, ok := p.topics[envelope.Channel]
	if !ok || topic == "" {
		return apperr.ErrInternal.WithDetail("Unknown event channel.")
	}

	payload, err := json.Marshal(envelope)
	if err != nil {
		return apperr.ErrInternal.WithCause(err)
	}

	message := &sarama.ProducerMessage{
		Topic: topic,
		Key:   sarama.StringEncoder(envelope.Key),
		Value: sarama.ByteEncoder(payload),
		Headers: []sarama.RecordHeader{
			{Key: []byte("content-type"), Value: []byte(contentType)},
			{Key: []byte("ce_id"), Value: []byte(envelope.ID)},
			{Key: []byte("ce_type"), Value: []byte(envelope.Type)},
		},
	}
	if envelope.TraceParent != "" {
		message.Headers = append(message.Headers, sarama.RecordHeader{
			Key: []byte("traceparent"), Value: []byte(envelope.TraceParent),
		})
	}

	var lastErr error
	for attempt := 1; attempt <= p.maxAttempts; attempt++ {
		// The same CloudEvent id is reused on every attempt so a consumer that
		// receives two copies can deduplicate them.
		partition, offset, err := p.producer.SendMessage(message)
		if err == nil {
			p.logger.Debug("published an identity event",
				zap.String("event_id", envelope.ID),
				zap.String("event_type", envelope.Type),
				zap.String("topic", topic),
				zap.Int32("partition", partition),
				zap.Int64("offset", offset),
			)
			return nil
		}

		lastErr = err
		p.logger.Warn("could not publish an identity event, retrying",
			zap.String("event_id", envelope.ID),
			zap.String("event_type", envelope.Type),
			zap.Int("attempt", attempt),
			zap.Error(err),
		)

		if attempt == p.maxAttempts {
			break
		}
		if err := sleep(ctx, p.backoff*time.Duration(attempt)); err != nil {
			lastErr = err
			break
		}
	}

	// Retries are exhausted. The domain change is already committed, so this is
	// the reconciliation gap ADR-005 accepts and alerting must cover.
	p.logger.Error("gave up publishing an identity event",
		zap.String("event_id", envelope.ID),
		zap.String("event_type", envelope.Type),
		zap.String("topic", topic),
		zap.Int("attempts", p.maxAttempts),
		zap.Error(lastErr),
	)
	return apperr.ErrDependencyUnavailable.WithCause(lastErr)
}

// Close shuts the producer down.
func (p *Publisher) Close() error {
	return p.producer.Close()
}

func sleep(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return nil
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

var _ port.EventPublisher = (*LoggingPublisher)(nil)

// LoggingPublisher replaces the broker in a local run where Kafka is disabled.
// It records what would have been sent so a developer still sees the flow.
type LoggingPublisher struct {
	logger *zap.Logger
}

// NewLoggingPublisher wires the no-broker publisher.
func NewLoggingPublisher(logger *zap.Logger) *LoggingPublisher {
	return &LoggingPublisher{logger: logger}
}

// Publish logs the envelope metadata. The payload is not logged: it would put
// identifiers of a security flow into the log stream for no operational gain.
func (p *LoggingPublisher) Publish(_ context.Context, envelope *event.Envelope) error {
	p.logger.Info("kafka is disabled, skipping an identity event",
		zap.String("event_id", envelope.ID),
		zap.String("event_type", envelope.Type),
		zap.String("channel", string(envelope.Channel)),
	)
	return nil
}
