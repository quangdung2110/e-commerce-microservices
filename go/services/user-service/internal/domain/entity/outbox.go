package entity

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// OutboxEventType defines the types of events in the outbox
type OutboxEventType string

const (
	EventTypeUserProfileUpdated   OutboxEventType = "USER_PROFILE_UPDATED"
	EventTypeUserPreferencesUpdated OutboxEventType = "USER_PREFERENCES_UPDATED"
	EventTypeUserProfileDeleted   OutboxEventType = "USER_PROFILE_DELETED"
)

// AggregateType defines the type of aggregate
type AggregateType string

const (
	AggregateUser AggregateType = "USER"
)

// OutboxEvent represents a transactional outbox event for CDC
type OutboxEvent struct {
	EventID       uuid.UUID       `json:"event_id" db:"event_id"`
	EventType     OutboxEventType `json:"event_type" db:"event_type"`
	AggregateType AggregateType   `json:"aggregate_type" db:"aggregate_type"`
	AggregateID   uuid.UUID       `json:"aggregate_id" db:"aggregate_id"`
	PartitionKey  uuid.UUID       `json:"partition_key" db:"partition_key"`
	Payload       json.RawMessage `json:"payload" db:"payload"`
	CreatedAt     time.Time       `json:"created_at" db:"created_at"`
}

// NewOutboxEvent creates a new outbox event
func NewOutboxEvent(eventType OutboxEventType, aggregateID uuid.UUID, payload interface{}) (*OutboxEvent, error) {
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}

	return &OutboxEvent{
		EventID:       uuid.New(),
		EventType:    eventType,
		AggregateType: AggregateUser,
		AggregateID:   aggregateID,
		PartitionKey:  aggregateID,
		Payload:       payloadBytes,
		CreatedAt:     time.Now(),
	}, nil
}

// NewUserProfileUpdatedEvent creates an outbox event for profile updates
func NewUserProfileUpdatedEvent(profile *Profile) (*OutboxEvent, error) {
	payload := ProfileUpdatedPayload{
		ID:          profile.ID,
		FirstName:   profile.FirstName,
		LastName:    profile.LastName,
		PhoneNumber: profile.PhoneNumber,
		DateOfBirth: profile.DateOfBirth,
		Gender:      string(profile.Gender),
	}
	return NewOutboxEvent(EventTypeUserProfileUpdated, profile.ID, payload)
}

// NewUserPreferencesUpdatedEvent creates an outbox event for preferences updates
func NewUserPreferencesUpdatedEvent(prefs *Preferences) (*OutboxEvent, error) {
	payload := PreferencesUpdatedPayload{
		ID:               prefs.UserID,
		Language:         prefs.Language,
		Currency:         prefs.Currency,
		EmailEnabled:     prefs.EmailEnabled,
		InAppEnabled:     prefs.InAppEnabled,
		MarketingEnabled: prefs.MarketingEnabled,
	}
	return NewOutboxEvent(EventTypeUserPreferencesUpdated, prefs.UserID, payload)
}

// NewUserProfileDeletedEvent creates an outbox event for profile deletion
func NewUserProfileDeletedEvent(userID uuid.UUID) (*OutboxEvent, error) {
	payload := ProfileDeletedPayload{
		ID:        userID,
		DeletedAt: time.Now(),
	}
	return NewOutboxEvent(EventTypeUserProfileDeleted, userID, payload)
}

// ProfileUpdatedPayload is the payload for profile update events
type ProfileUpdatedPayload struct {
	ID          uuid.UUID  `json:"id"`
	FirstName   string     `json:"first_name"`
	LastName    string     `json:"last_name"`
	PhoneNumber *string    `json:"phone_number,omitempty"`
	DateOfBirth *time.Time `json:"date_of_birth,omitempty"`
	Gender      string     `json:"gender"`
}

// PreferencesUpdatedPayload is the payload for preferences update events
type PreferencesUpdatedPayload struct {
	ID               uuid.UUID `json:"id"`
	Language         string    `json:"language"`
	Currency         string    `json:"currency"`
	EmailEnabled     bool      `json:"email_enabled"`
	InAppEnabled     bool      `json:"in_app_enabled"`
	MarketingEnabled bool      `json:"marketing_enabled"`
}

// ProfileDeletedPayload is the payload for profile deletion events
type ProfileDeletedPayload struct {
	ID        uuid.UUID `json:"id"`
	DeletedAt time.Time `json:"deleted_at"`
}
