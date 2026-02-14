package events

import (
	"time"

	"github.com/google/uuid"
)

// BaseEvent is the foundation for all domain events in the system
// Equivalent to Java's com.example.common.event.BaseEvent
type BaseEvent struct {
	EventID     string    `json:"eventId"`
	AggregateID string    `json:"aggregateId"`
	EventType   string    `json:"eventType"`
	OccurredOn  time.Time `json:"occurredOn"`
	Version     int       `json:"version"`
}

// NewBaseEvent creates a new BaseEvent with initialized fields
func NewBaseEvent(aggregateID, eventType string, version int) BaseEvent {
	return BaseEvent{
		EventID:     uuid.New().String(),
		AggregateID: aggregateID,
		EventType:   eventType,
		OccurredOn:  time.Now().UTC(),
		Version:     version,
	}
}

// InitializeEvent sets default values for event fields if not already set
// Equivalent to Java's BaseEvent.initializeEvent()
func (e *BaseEvent) InitializeEvent(aggregateID, eventType string, version int) {
	if e.EventID == "" {
		e.EventID = uuid.New().String()
	}
	if e.AggregateID == "" {
		e.AggregateID = aggregateID
	}
	if e.EventType == "" {
		e.EventType = eventType
	}
	if e.OccurredOn.IsZero() {
		e.OccurredOn = time.Now().UTC()
	}
	if e.Version == 0 {
		e.Version = version
	}
}

// GetEventID returns the unique event identifier
func (e *BaseEvent) GetEventID() string {
	return e.EventID
}

// GetAggregateID returns the aggregate root identifier
func (e *BaseEvent) GetAggregateID() string {
	return e.AggregateID
}

// GetEventType returns the type of the event
func (e *BaseEvent) GetEventType() string {
	return e.EventType
}

// GetOccurredOn returns when the event occurred
func (e *BaseEvent) GetOccurredOn() time.Time {
	return e.OccurredOn
}

// GetVersion returns the event version for schema evolution
func (e *BaseEvent) GetVersion() int {
	return e.Version
}

// DomainEvent interface - marker for all domain events
type DomainEvent interface {
	GetEventID() string
	GetAggregateID() string
	GetEventType() string
	GetOccurredOn() time.Time
	GetVersion() int
}

// Ensure BaseEvent implements DomainEvent
var _ DomainEvent = (*BaseEvent)(nil)
