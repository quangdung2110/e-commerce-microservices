package event

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"go.uber.org/zap"

	pkgKafka "github.com/yourusername/spring-event-sourcing/go/pkg/kafka"
	common "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// KafkaEventPublisher publishes domain events to Kafka using the shared kafka package
type KafkaEventPublisher struct {
	publisher *pkgKafka.KafkaEventPublisher
	logger    *zap.Logger
}

// NewKafkaEventPublisher creates new Kafka event publisher
func NewKafkaEventPublisher(brokers []string, topic string, logger *zap.Logger) (common.EventPublisher, error) {
	// Convert brokers slice to comma-separated string
	brokersStr := strings.Join(brokers, ",")

	publisher, err := pkgKafka.NewKafkaEventPublisher(brokersStr, topic, logger)
	if err != nil {
		return nil, fmt.Errorf("failed to create kafka publisher: %w", err)
	}

	return &KafkaEventPublisher{
		publisher: publisher,
		logger:    logger,
	}, nil
}

// Close closes the Kafka publisher
func (p *KafkaEventPublisher) Close() error {
	return p.publisher.Close()
}

// domainEventWrapper wraps auth service events to implement pkg/events.DomainEvent interface
type domainEventWrapper struct {
	EventID     string      `json:"eventId"`
	EventType   string      `json:"eventType"`
	AggregateID string      `json:"aggregateId"`
	OccurredOn  time.Time   `json:"occurredOn"`
	Version     int         `json:"version"`
	Data        interface{} `json:"data"`
}

func (e *domainEventWrapper) GetEventID() string       { return e.EventID }
func (e *domainEventWrapper) GetEventType() string     { return e.EventType }
func (e *domainEventWrapper) GetAggregateID() string   { return e.AggregateID }
func (e *domainEventWrapper) GetOccurredOn() time.Time { return e.OccurredOn }
func (e *domainEventWrapper) GetVersion() int          { return e.Version }

func (e *domainEventWrapper) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		EventID     string      `json:"eventId"`
		EventType   string      `json:"eventType"`
		AggregateID string      `json:"aggregateId"`
		OccurredOn  time.Time   `json:"occurredOn"`
		Version     int         `json:"version"`
		Data        interface{} `json:"data"`
	}{
		EventID:     e.EventID,
		EventType:   e.EventType,
		AggregateID: e.AggregateID,
		OccurredOn:  e.OccurredOn,
		Version:     e.Version,
		Data:        e.Data,
	})
}

// publishEvent is a helper to publish any event
func (p *KafkaEventPublisher) publishEvent(ctx context.Context, event interface{}, eventType string, aggregateID string) error {
	// Create wrapper event that implements DomainEvent interface
	wrapper := &domainEventWrapper{
		EventID:     fmt.Sprintf("%s-%d", eventType, time.Now().UnixNano()),
		EventType:   eventType,
		AggregateID: aggregateID,
		OccurredOn:  time.Now().UTC(),
		Version:     1,
		Data:        event,
	}

	// Publish using the shared kafka publisher
	if err := p.publisher.Publish(wrapper, aggregateID); err != nil {
		p.logger.Error("Failed to publish event",
			zap.String("event_type", eventType),
			zap.String("aggregate_id", aggregateID),
			zap.Error(err))
		return err
	}

	p.logger.Info("Event published successfully",
		zap.String("event_type", eventType),
		zap.String("aggregate_id", aggregateID))

	return nil
}

// PublishUserRegistered publishes UserRegisteredEvent
func (p *KafkaEventPublisher) PublishUserRegistered(ctx context.Context, event *common.UserRegisteredEvent) error {
	return p.publishEvent(ctx, event, "UserRegistered", event.UserID.String())
}

// PublishManagedAccountCreated publishes ManagedAccountCreatedEvent
func (p *KafkaEventPublisher) PublishManagedAccountCreated(ctx context.Context, event *common.ManagedAccountCreatedEvent) error {
	return p.publishEvent(ctx, event, "ManagedAccountCreated", event.UserID.String())
}

// PublishUserLoggedIn publishes UserLoggedInEvent
func (p *KafkaEventPublisher) PublishUserLoggedIn(ctx context.Context, event *common.UserLoggedInEvent) error {
	return p.publishEvent(ctx, event, "UserLoggedIn", event.UserID.String())
}

// PublishUserLoggedOut publishes UserLoggedOutEvent
func (p *KafkaEventPublisher) PublishUserLoggedOut(ctx context.Context, event *common.UserLoggedOutEvent) error {
	return p.publishEvent(ctx, event, "UserLoggedOut", event.UserID.String())
}

// PublishPasswordChanged publishes PasswordChangedEvent
func (p *KafkaEventPublisher) PublishPasswordChanged(ctx context.Context, event *common.PasswordChangedEvent) error {
	var userID string
	if event.UserID != nil {
		userID = event.UserID.String()
	}
	return p.publishEvent(ctx, event, "PasswordChanged", userID)
}

// PublishPasswordResetRequested publishes PasswordResetRequestedEvent
func (p *KafkaEventPublisher) PublishPasswordResetRequested(ctx context.Context, event *common.PasswordResetRequestedEvent) error {
	var userID string
	if event.UserID != nil {
		userID = event.UserID.String()
	}
	return p.publishEvent(ctx, event, "PasswordResetRequested", userID)
}

// PublishSuspiciousActivity publishes SuspiciousActivityDetectedEvent
func (p *KafkaEventPublisher) PublishSuspiciousActivity(ctx context.Context, event *common.SuspiciousActivityEvent) error {
	var userID string
	if event.UserID != nil {
		userID = event.UserID.String()
	}
	// Fallback if UserID is nil - use DeviceID as fallback
	if userID == "" {
		userID = event.DeviceID
	}
	return p.publishEvent(ctx, event, "SuspiciousActivityDetected", userID)
}
