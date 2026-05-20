package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"
	"strings"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/config"
	eventPkg "github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

const (
	TopicAuthEvents  = "auth.events"
	TopicAdminEvents = "admin.events"
)

type Producer struct {
	producer    sarama.SyncProducer
	topicPrefix string
	logger      *zap.Logger
}

func NewProducer(cfg config.KafkaConfig, logger *zap.Logger) (*Producer, error) {
	saramaConfig := sarama.NewConfig()
	saramaConfig.Producer.Return.Successes = true
	saramaConfig.Producer.RequiredAcks = sarama.WaitForAll
	saramaConfig.Producer.Retry.Max = 3
	saramaConfig.Producer.Return.Errors = true
	saramaConfig.Producer.Partitioner = sarama.NewHashPartitioner

	producer, err := sarama.NewSyncProducer(cfg.Brokers, saramaConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create Kafka producer: %w", err)
	}

	return &Producer{
		producer:    producer,
		topicPrefix: cfg.TopicPrefix,
		logger:      logger.With(zap.String("component", "kafka_producer")),
	}, nil
}

func (p *Producer) SendMessage(topic string, key string, value []byte) error {
	fullTopic := p.topicPrefix + "." + topic
	msg := &sarama.ProducerMessage{
		Topic: fullTopic,
		Key:   sarama.StringEncoder(key),
		Value: sarama.ByteEncoder(value),
	}

	partition, offset, err := p.producer.SendMessage(msg)
	if err != nil {
		return fmt.Errorf("failed to send message to %s: %w", fullTopic, err)
	}

	p.logger.Debug("Message sent",
		zap.String("topic", fullTopic),
		zap.Int32("partition", partition),
		zap.Int64("offset", offset),
	)

	return nil
}

func (p *Producer) Publish(ctx context.Context, event interface{}) error {
	data, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal event: %w", err)
	}

	partitionKey := p.getPartitionKey(event)

	topic := TopicAuthEvents

	return p.SendMessage(topic, partitionKey, data)
}

func (p *Producer) PublishAuthEvent(ctx context.Context, event interface{}) error {
	data, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal auth event: %w", err)
	}

	partitionKey := p.getPartitionKey(event)
	return p.SendMessage(TopicAuthEvents, partitionKey, data)
}

func (p *Producer) PublishAdminEvent(ctx context.Context, key string, event interface{}) error {
	data, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal admin event: %w", err)
	}

	return p.SendMessage(TopicAdminEvents, key, data)
}

func (p *Producer) PublishWithHeaders(ctx context.Context, topic string, key string, value []byte, headers map[string]string) error {
	fullTopic := p.topicPrefix + "." + topic

	var saramaHeaders []sarama.RecordHeader
	for k, v := range headers {
		saramaHeaders = append(saramaHeaders, sarama.RecordHeader{
			Key:   []byte(k),
			Value: []byte(v),
		})
	}

	msg := &sarama.ProducerMessage{
		Topic:   fullTopic,
		Key:     sarama.StringEncoder(key),
		Value:   sarama.ByteEncoder(value),
		Headers: saramaHeaders,
	}

	partition, offset, err := p.producer.SendMessage(msg)
	if err != nil {
		return fmt.Errorf("failed to send message to %s: %w", fullTopic, err)
	}

	p.logger.Debug("Message with headers sent",
		zap.String("topic", fullTopic),
		zap.Int32("partition", partition),
		zap.Int64("offset", offset),
	)

	return nil
}

func (p *Producer) Close() error {
	return p.producer.Close()
}

func (p *Producer) getEventType(event interface{}) string {
	t := reflect.TypeOf(event)
	if t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	return strings.ToUpper(t.Name())
}

func (p *Producer) getPartitionKey(event interface{}) string {
	switch e := event.(type) {
	case *eventPkg.UserOTPRequested:
		return e.Payload.UserID
	case *eventPkg.UserVerifiedSuccess:
		return e.Payload.UserID
	case *eventPkg.UserPasswordChanged:
		return e.Payload.UserID
	case *eventPkg.UserAccountLocked:
		return e.Payload.UserID
	case *eventPkg.UserUnlocked:
		return e.Payload.UserID
	case *eventPkg.UserBannedNotification:
		return e.Payload.UserID
	case *eventPkg.PasswordResetRequested:
		return e.Payload.UserID
	case *eventPkg.TokenTheftDetected:
		return e.Payload.UserID
	case *eventPkg.UserDeleted:
		return e.Payload.UserID
	case *eventPkg.UserEmailChanged:
		return e.Payload.UserID
	// Internal events
	case *eventPkg.LoginSuccess:
		return e.Payload.UserID
	case *eventPkg.LoginFailed:
		return e.Payload.UserID
	case *eventPkg.LogoutEvent:
		return e.Payload.UserID
	case *eventPkg.ForceLogoutEvent:
		return e.Payload.UserID
	default:
		return ""
	}
}

var _ port.EventPublisher = (*Producer)(nil)
