package entity

import (
	"encoding/json"
	"time"
)

// ProviderEventStatus is the Track queue state (03-tracking.md §3).
type ProviderEventStatus string

const (
	ProviderEventPending   ProviderEventStatus = "PENDING"
	ProviderEventProcessed ProviderEventStatus = "PROCESSED"
	ProviderEventUnmatched ProviderEventStatus = "UNMATCHED"
	ProviderEventIgnored   ProviderEventStatus = "IGNORED"
)

// ProviderEventType is the normalised SES event type.
type ProviderEventType string

const (
	ProviderEventSend          ProviderEventType = "send"
	ProviderEventDelivery      ProviderEventType = "delivery"
	ProviderEventBounce        ProviderEventType = "bounce"
	ProviderEventComplaint     ProviderEventType = "complaint"
	ProviderEventReject        ProviderEventType = "reject"
	ProviderEventDeliveryDelay ProviderEventType = "delivery_delay"
	ProviderEventOther         ProviderEventType = "other"
)

// ProviderEvent is one raw provider notification, append-only, with every
// email address already replaced by its sha256 before INSERT.
type ProviderEvent struct {
	ID                int64
	Provider          Provider
	ProviderEventID   string
	ProviderMessageID *string
	EventType         ProviderEventType
	OccurredAt        *time.Time
	Raw               json.RawMessage
	Status            ProviderEventStatus
	Attempt           int
	NextAttemptAt     time.Time
	ReceivedAt        time.Time
}

// BounceType distinguishes a hard (permanent) from a soft (transient) bounce.
type BounceType string

const (
	BouncePermanent BounceType = "permanent"
	BounceTransient BounceType = "transient"
)
