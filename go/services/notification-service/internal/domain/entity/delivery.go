package entity

import (
	"time"

	"github.com/google/uuid"
)

// Delivery is one send attempt lifecycle on one channel; the row is queue,
// state and retry schedule at once (02-pipeline.md §5.1, 09-data-model.md §4.2).
type Delivery struct {
	ID              uuid.UUID
	RequestID       uuid.UUID
	UserID          *uuid.UUID // NULL after anonymisation
	Subject         *string
	Source          string
	SourceSequence  *int64
	Lane            Lane
	Channel         Channel
	Template        string
	TemplateVersion *string
	Status          DeliveryStatus
	Attempt         int
	NextAttemptAt   time.Time
	LeaseUntil      *time.Time
	LockedBy        *string
	DedupKey        *string
	// OrderingKey is set for /services/order deliveries only:
	// source:key_version:HMAC(source ‖ NUL ‖ subject) (05-ordering.md §4).
	OrderingKey       *string
	ExpiresAt         time.Time
	Provider          *Provider
	ProviderMessageID *string
	PossiblyDuplicate bool
	LastErrorCode     *string
	LastError         *string
	SkipReason        *string
	SourceRef         *string
	SentAt            *time.Time
	DeliveredAt       *time.Time
	FinishedAt        *time.Time
	CreatedAt         time.Time
	UpdatedAt         time.Time
}

// WasLeaseExpired reports whether the previous attempt ended by reaper, which
// drives the layer-C3 lane policy (04-idempotency.md §3).
func (d *Delivery) WasLeaseExpired() bool {
	return d.LastErrorCode != nil && *d.LastErrorCode == ReasonLeaseExpired
}

// PendingStat is one row of the queue gauges (14-observability.md §2.2).
type PendingStat struct {
	Lane           Lane
	Channel        Channel
	ReadyCount     int64
	ScheduledCount int64
	OldestReadyAge time.Duration
}

// RedriveFilter selects FAILED deliveries for a batch redrive (ops API).
type RedriveFilter struct {
	Lane         Lane
	ReasonCode   string // empty = every non-contract failure
	FailedAfter  *time.Time
	FailedBefore *time.Time
	ExpiresAt    *time.Time
	Limit        int
}
