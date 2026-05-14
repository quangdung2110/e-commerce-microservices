package event

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// AuthEventType defines the types of authentication events consumed from Kafka
type AuthEventType string

const (
	AuthEventUserVerifiedSuccess AuthEventType = "USER_VERIFIED_SUCCESS"
	AuthEventUserDeleted        AuthEventType = "USER_DELETED"
)

// AuthEvent represents an authentication event consumed from Kafka
type AuthEvent struct {
	EventID       uuid.UUID       `json:"event_id"`
	AggregateType string          `json:"aggregate_type"`
	AggregateID   uuid.UUID       `json:"aggregate_id"`
	PartitionKey  uuid.UUID       `json:"partition_key"`
	EventType     AuthEventType   `json:"event_type"`
	OccurredAt    string          `json:"occurred_at"`
	Payload       AuthPayload     `json:"payload"`
}

// ParseAuthEvent parses an auth event from JSON bytes
func ParseAuthEvent(data []byte) (*AuthEvent, error) {
	var event AuthEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, err
	}
	return &event, nil
}

// AuthPayload contains the data for authentication events
type AuthPayload struct {
	UserID                      uuid.UUID `json:"user_id"`
	Roles                       []string `json:"roles,omitempty"`
	Reason                      string   `json:"reason,omitempty"`
	RetentionWindowExpiresAt    time.Time `json:"retention_window_expires_at,omitempty"`
}

// IsUserVerifiedSuccess checks if the event is a user verified success event
func (e *AuthEvent) IsUserVerifiedSuccess() bool {
	return e.EventType == AuthEventUserVerifiedSuccess
}

// IsUserDeleted checks if the event is a user deleted event
func (e *AuthEvent) IsUserDeleted() bool {
	return e.EventType == AuthEventUserDeleted
}

// OccurredAtTime parses the occurred_at timestamp
func (e *AuthEvent) OccurredAtTime() (time.Time, error) {
	return time.Parse(time.RFC3339, e.OccurredAt)
}
