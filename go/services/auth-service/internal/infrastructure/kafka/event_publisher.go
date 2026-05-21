package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"
	"strings"

	"go.uber.org/zap"

	eventPkg "github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

type EventPublisher struct {
	producer    *Producer
	topicPrefix string
	logger      *zap.Logger
}

func NewEventPublisher(producer *Producer, topicPrefix string, logger *zap.Logger) *EventPublisher {
	return &EventPublisher{
		producer:    producer,
		topicPrefix: topicPrefix,
		logger:      logger.With(zap.String("component", "event_publisher")),
	}
}

func (p *EventPublisher) Publish(ctx context.Context, event interface{}) error {
	data, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal event: %w", err)
	}

	eventType := p.getEventTypeName(event)
	partitionKey := p.getPartitionKey(event)

	p.logger.Debug("Publishing event",
		zap.String("event_type", eventType),
		zap.String("partition_key", partitionKey),
		zap.String("topic", p.topicPrefix+".auth.events"),
	)

	if err := p.producer.SendMessage("auth.events", partitionKey, data); err != nil {
		return fmt.Errorf("failed to publish event %s: %w", eventType, err)
	}

	p.logger.Info("Event published successfully",
		zap.String("event_type", eventType),
		zap.String("topic", p.topicPrefix+".auth.events"),
	)

	return nil
}

func (p *EventPublisher) PublishAuthEvent(ctx context.Context, event interface{}) error {
	return p.Publish(ctx, event)
}

func (p *EventPublisher) PublishAdminEvent(ctx context.Context, key string, event interface{}) error {
	data, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal admin event: %w", err)
	}

	p.logger.Debug("Publishing admin event",
		zap.String("key", key),
		zap.String("topic", p.topicPrefix+".admin.events"),
	)

	if err := p.producer.SendMessage("admin.events", key, data); err != nil {
		return fmt.Errorf("failed to publish admin event: %w", err)
	}

	return nil
}

func (p *EventPublisher) getEventTypeName(evt interface{}) string {
	t := reflect.TypeOf(evt)
	if t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	return strings.ToUpper(t.Name())
}

func (p *EventPublisher) getPartitionKey(evt interface{}) string {
	switch e := evt.(type) {
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

var _ port.EventPublisher = (*EventPublisher)(nil)
