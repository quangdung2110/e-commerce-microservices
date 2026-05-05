package event

import (
	"time"

	"github.com/google/uuid"

	"user-service/internal/domain/entity"
)

// DomainEventType defines the types of domain events
type DomainEventType string

const (
	DomainEventProfileCreated       DomainEventType = "ProfileCreated"
	DomainEventProfileUpdated       DomainEventType = "ProfileUpdated"
	DomainEventProfileDeleted       DomainEventType = "ProfileDeleted"
	DomainEventAddressCreated       DomainEventType = "AddressCreated"
	DomainEventAddressUpdated       DomainEventType = "AddressUpdated"
	DomainEventAddressDeleted       DomainEventType = "AddressDeleted"
	DomainEventAddressSetDefault    DomainEventType = "AddressSetDefault"
	DomainEventPreferencesCreated   DomainEventType = "PreferencesCreated"
	DomainEventPreferencesUpdated   DomainEventType = "PreferencesUpdated"
)

// DomainEvent is the base interface for all domain events
type DomainEvent interface {
	EventType() DomainEventType
	OccurredAt() time.Time
	AggregateID() uuid.UUID
}

// BaseEvent provides common fields for all domain events
type BaseEvent struct {
	eventType    DomainEventType
	occurredAt   time.Time
	aggregateID  uuid.UUID
}

// NewBaseEvent creates a new base event
func NewBaseEvent(eventType DomainEventType, aggregateID uuid.UUID) BaseEvent {
	return BaseEvent{
		eventType:   eventType,
		occurredAt:  time.Now(),
		aggregateID: aggregateID,
	}
}

func (e BaseEvent) EventType() DomainEventType {
	return e.eventType
}

func (e BaseEvent) OccurredAt() time.Time {
	return e.occurredAt
}

func (e BaseEvent) AggregateID() uuid.UUID {
	return e.aggregateID
}

// ProfileCreatedEvent is raised when a new profile is created
type ProfileCreatedEvent struct {
	BaseEvent
	Profile *entity.Profile
}

// NewProfileCreatedEvent creates a new profile created event
func NewProfileCreatedEvent(profile *entity.Profile) *ProfileCreatedEvent {
	return &ProfileCreatedEvent{
		BaseEvent: NewBaseEvent(DomainEventProfileCreated, profile.ID),
		Profile:   profile,
	}
}

// ProfileUpdatedEvent is raised when a profile is updated
type ProfileUpdatedEvent struct {
	BaseEvent
	Profile   *entity.Profile
	ChangedFields []string
}

// NewProfileUpdatedEvent creates a new profile updated event
func NewProfileUpdatedEvent(profile *entity.Profile, changedFields []string) *ProfileUpdatedEvent {
	return &ProfileUpdatedEvent{
		BaseEvent:     NewBaseEvent(DomainEventProfileUpdated, profile.ID),
		Profile:       profile,
		ChangedFields: changedFields,
	}
}

// ProfileDeletedEvent is raised when a profile is deleted
type ProfileDeletedEvent struct {
	BaseEvent
	UserID uuid.UUID
}

// NewProfileDeletedEvent creates a new profile deleted event
func NewProfileDeletedEvent(userID uuid.UUID) *ProfileDeletedEvent {
	return &ProfileDeletedEvent{
		BaseEvent: NewBaseEvent(DomainEventProfileDeleted, userID),
		UserID:    userID,
	}
}

// AddressCreatedEvent is raised when a new address is created
type AddressCreatedEvent struct {
	BaseEvent
	Address   *entity.Address
	IsDefault bool
}

// NewAddressCreatedEvent creates a new address created event
func NewAddressCreatedEvent(address *entity.Address) *AddressCreatedEvent {
	return &AddressCreatedEvent{
		BaseEvent: NewBaseEvent(DomainEventAddressCreated, address.UserID),
		Address:   address,
		IsDefault: address.IsDefault,
	}
}

// AddressUpdatedEvent is raised when an address is updated
type AddressUpdatedEvent struct {
	BaseEvent
	Address   *entity.Address
	ChangedFields []string
}

// NewAddressUpdatedEvent creates a new address updated event
func NewAddressUpdatedEvent(address *entity.Address, changedFields []string) *AddressUpdatedEvent {
	return &AddressUpdatedEvent{
		BaseEvent:     NewBaseEvent(DomainEventAddressUpdated, address.UserID),
		Address:       address,
		ChangedFields: changedFields,
	}
}

// AddressDeletedEvent is raised when an address is deleted
type AddressDeletedEvent struct {
	BaseEvent
	AddressID uuid.UUID
	WasDefault bool
}

// NewAddressDeletedEvent creates a new address deleted event
func NewAddressDeletedEvent(userID, addressID uuid.UUID, wasDefault bool) *AddressDeletedEvent {
	return &AddressDeletedEvent{
		BaseEvent:  NewBaseEvent(DomainEventAddressDeleted, userID),
		AddressID:  addressID,
		WasDefault: wasDefault,
	}
}

// AddressSetDefaultEvent is raised when an address is set as default
type AddressSetDefaultEvent struct {
	BaseEvent
	AddressID       uuid.UUID
	PreviousDefault *uuid.UUID
}

// NewAddressSetDefaultEvent creates a new address set default event
func NewAddressSetDefaultEvent(userID, addressID uuid.UUID, previousDefault *uuid.UUID) *AddressSetDefaultEvent {
	return &AddressSetDefaultEvent{
		BaseEvent:       NewBaseEvent(DomainEventAddressSetDefault, userID),
		AddressID:       addressID,
		PreviousDefault: previousDefault,
	}
}

// PreferencesCreatedEvent is raised when default preferences are created
type PreferencesCreatedEvent struct {
	BaseEvent
	Preferences *entity.Preferences
}

// NewPreferencesCreatedEvent creates a new preferences created event
func NewPreferencesCreatedEvent(prefs *entity.Preferences) *PreferencesCreatedEvent {
	return &PreferencesCreatedEvent{
		BaseEvent:    NewBaseEvent(DomainEventPreferencesCreated, prefs.UserID),
		Preferences:  prefs,
	}
}

// PreferencesUpdatedEvent is raised when preferences are updated
type PreferencesUpdatedEvent struct {
	BaseEvent
	Preferences   *entity.Preferences
	ChangedFields []string
}

// NewPreferencesUpdatedEvent creates a new preferences updated event
func NewPreferencesUpdatedEvent(prefs *entity.Preferences, changedFields []string) *PreferencesUpdatedEvent {
	return &PreferencesUpdatedEvent{
		BaseEvent:     NewBaseEvent(DomainEventPreferencesUpdated, prefs.UserID),
		Preferences:   prefs,
		ChangedFields: changedFields,
	}
}
