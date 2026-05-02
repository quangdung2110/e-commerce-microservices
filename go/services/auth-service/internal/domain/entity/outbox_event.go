package entity

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// OutboxEvent represents an event in the transactional outbox
type OutboxEvent struct {
	ID            uuid.UUID              `json:"event_id"`
	EventType     string                 `json:"event_type"`
	AggregateType string                 `json:"aggregate_type"`
	AggregateID   uuid.UUID              `json:"aggregate_id"`
	PartitionKey  uuid.UUID              `json:"partition_key"`
	Payload       map[string]interface{}  `json:"payload"`
	Metadata      map[string]interface{} `json:"metadata,omitempty"`
	Status        types.OutboxEventStatus `json:"status"`
	ErrorMessage  string                 `json:"error_message,omitempty"`
	CreatedAt     time.Time              `json:"created_at"`
	PublishedAt   *time.Time             `json:"published_at,omitempty"`
	RetryCount    int                    `json:"retry_count"`
	LastRetryAt   *time.Time             `json:"last_retry_at,omitempty"`
}

// NewOutboxEvent creates a new outbox event
func NewOutboxEvent(
	eventType string,
	aggregateType string,
	aggregateID uuid.UUID,
	payload map[string]interface{},
	opts ...OutboxEventOption,
) *OutboxEvent {
	event := &OutboxEvent{
		ID:            uuid.New(),
		EventType:     eventType,
		AggregateType: aggregateType,
		AggregateID:   aggregateID,
		PartitionKey:  aggregateID, // Default partition key to aggregate ID
		Payload:       payload,
		Metadata:      make(map[string]interface{}),
		Status:        types.OutboxEventStatusPending,
		CreatedAt:     time.Now().UTC(),
		RetryCount:    0,
	}

	// Add common metadata
	event.Metadata["service"] = "auth-service"
	event.Metadata["version"] = "1.0"

	for _, opt := range opts {
		opt(event)
	}

	return event
}

// OutboxEventOption is a functional option for OutboxEvent
type OutboxEventOption func(*OutboxEvent)

// WithPartitionKey sets a custom partition key
func WithPartitionKey(key uuid.UUID) OutboxEventOption {
	return func(event *OutboxEvent) {
		event.PartitionKey = key
	}
}

// WithMetadata adds metadata to the event
func WithMetadata(key string, value interface{}) OutboxEventOption {
	return func(event *OutboxEvent) {
		if event.Metadata == nil {
			event.Metadata = make(map[string]interface{})
		}
		event.Metadata[key] = value
	}
}

// WithCorrelationID adds correlation ID to metadata
func WithCorrelationID(correlationID string) OutboxEventOption {
	return WithMetadata("correlation_id", correlationID)
}

// WithCausationID adds causation ID to metadata
func WithCausationID(causationID string) OutboxEventOption {
	return WithMetadata("causation_id", causationID)
}

// ToJSON returns the payload as JSON bytes
func (e *OutboxEvent) ToJSON() ([]byte, error) {
	return json.Marshal(e.Payload)
}

// GetPayloadString returns the payload as a formatted JSON string
func (e *OutboxEvent) GetPayloadString() string {
	data, err := json.Marshal(e.Payload)
	if err != nil {
		return "{}"
	}
	return string(data)
}

// MarkAsPublished marks the event as published
func (e *OutboxEvent) MarkAsPublished() {
	e.Status = types.OutboxEventStatusPublished
	now := time.Now().UTC()
	e.PublishedAt = &now
}

// MarkAsFailed marks the event as failed
func (e *OutboxEvent) MarkAsFailed(errMsg string) {
	e.Status = types.OutboxEventStatusFailed
	e.ErrorMessage = errMsg
	e.RetryCount++
	now := time.Now().UTC()
	e.LastRetryAt = &now
}

// IsPending returns true if the event is pending
func (e *OutboxEvent) IsPending() bool {
	return e.Status == types.OutboxEventStatusPending
}

// IsPublished returns true if the event is published
func (e *OutboxEvent) IsPublished() bool {
	return e.Status == types.OutboxEventStatusPublished
}

// IsFailed returns true if the event has failed
func (e *OutboxEvent) IsFailed() bool {
	return e.Status == types.OutboxEventStatusFailed
}

// ShouldRetry returns true if the event should be retried
func (e *OutboxEvent) ShouldRetry(maxRetries int) bool {
	return e.RetryCount < maxRetries
}

// CloudEvent represents a CloudEvents-compatible event structure
type CloudEvent struct {
	SpecVersion string                 `json:"specversion"`
	Type       string                 `json:"type"`
	Source     string                 `json:"source"`
	ID         string                 `json:"id"`
	Time       string                 `json:"time"`
	Data       map[string]interface{} `json:"data,omitempty"`
	Extensions  map[string]interface{} `json:"extensions,omitempty"`
}

// ToCloudEvent converts OutboxEvent to CloudEvents format
func (e *OutboxEvent) ToCloudEvent() *CloudEvent {
	return &CloudEvent{
		SpecVersion: "1.0",
		Type:        e.EventType,
		Source:      "auth-service",
		ID:          e.ID.String(),
		Time:        e.CreatedAt.Format(time.RFC3339),
		Data:        e.Payload,
		Extensions:  e.Metadata,
	}
}
