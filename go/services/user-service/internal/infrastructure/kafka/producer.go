package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/segmentio/kafka-go"
	"go.uber.org/zap"
)

const (
	userEventsTopic = "t.user.events"
)

type UserEventType string

const (
	EventUserProfileUpdated      UserEventType = "USER_PROFILE_UPDATED"
	EventUserPreferencesUpdated UserEventType = "USER_PREFERENCES_UPDATED"
	EventAccountDeleted         UserEventType = "ACCOUNT_DELETED"
)

type UserEventPayload struct {
	EventID       uuid.UUID       `json:"event_id"`
	AggregateType string          `json:"aggregate_type"`
	AggregateID   uuid.UUID       `json:"aggregate_id"`
	PartitionKey  uuid.UUID       `json:"partition_key"`
	EventType     UserEventType   `json:"event_type"`
	OccurredAt    string          `json:"occurred_at"`
	Payload       json.RawMessage `json:"payload"`
}

type ProfileUpdatedPayload struct {
	ID          uuid.UUID  `json:"id"`
	FirstName   string     `json:"first_name"`
	LastName    string     `json:"last_name"`
	PhoneNumber *string    `json:"phone_number,omitempty"`
	DateOfBirth *time.Time `json:"date_of_birth,omitempty"`
	Gender      string     `json:"gender"`
}

type PreferencesUpdatedPayload struct {
	ID               uuid.UUID `json:"id"`
	Language         string    `json:"language"`
	Currency         string    `json:"currency"`
	EmailEnabled     bool      `json:"email_enabled"`
	InAppEnabled     bool      `json:"in_app_enabled"`
	MarketingEnabled bool      `json:"marketing_enabled"`
}

type AccountDeletedPayload struct {
	UserID                   uuid.UUID `json:"user_id"`
	DeletedAt                string    `json:"deleted_at"`
	Reason                   string    `json:"reason"`
	RetentionWindowExpiresAt string    `json:"retention_window_expires_at"`
}

type UserEventProducer struct {
	writer *kafka.Writer
	logger *zap.Logger
}

func NewUserEventProducer(cfg *Config, logger *zap.Logger) *UserEventProducer {
	w := &kafka.Writer{
		Addr:                   kafka.TCP(cfg.Brokers...),
		Topic:                  userEventsTopic,
		Balancer:               &kafka.Hash{},
		WriteTimeout:           10 * time.Second,
		ReadTimeout:            10 * time.Second,
		RequiredAcks:           kafka.RequireOne,
		AllowAutoTopicCreation: true,
	}
	return &UserEventProducer{writer: w, logger: logger}
}

func (p *UserEventProducer) Close() error {
	return p.writer.Close()
}

func (p *UserEventProducer) publish(ctx context.Context, eventType UserEventType, aggregateID uuid.UUID, payload interface{}) error {
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("failed to marshal payload: %w", err)
	}

	event := UserEventPayload{
		EventID:       uuid.New(),
		AggregateType: "USER",
		AggregateID:   aggregateID,
		PartitionKey:  aggregateID,
		EventType:     eventType,
		OccurredAt:    time.Now().Format(time.RFC3339),
		Payload:       payloadBytes,
	}

	value, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal event: %w", err)
	}

	msg := kafka.Message{
		Key:   []byte(aggregateID.String()),
		Value: value,
	}

	if err := p.writer.WriteMessages(ctx, msg); err != nil {
		return fmt.Errorf("failed to write message to Kafka: %w", err)
	}

	p.logger.Info("Published user event to Kafka",
		zap.String("event_type", string(eventType)),
		zap.String("aggregate_id", aggregateID.String()),
		zap.String("event_id", event.EventID.String()),
		zap.String("topic", userEventsTopic),
	)

	return nil
}

func (p *UserEventProducer) PublishUserProfileUpdated(ctx context.Context, payload ProfileUpdatedPayload) error {
	return p.publish(ctx, EventUserProfileUpdated, payload.ID, payload)
}

func (p *UserEventProducer) PublishUserPreferencesUpdated(ctx context.Context, payload PreferencesUpdatedPayload) error {
	return p.publish(ctx, EventUserPreferencesUpdated, payload.ID, payload)
}

func (p *UserEventProducer) PublishAccountDeleted(ctx context.Context, payload AccountDeletedPayload) error {
	return p.publish(ctx, EventAccountDeleted, payload.UserID, payload)
}
