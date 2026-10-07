package entity

import (
	"time"

	"github.com/google/uuid"

	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

// Deletion is the durable retention record of an anonymized account. Its mere
// existence means "this profile is anonymized and waiting to be purged", which
// is why it needs no status column (docs/user/data.md §2). It deliberately has
// no foreign key: it outlives the profile row the worker deletes.
type Deletion struct {
	ID              uuid.UUID
	UserID          uuid.UUID
	DeletedAt       time.Time
	HardDeleteAfter time.Time
	Reason          vo.DeletionReason
	CreatedAt       time.Time
}

// NewDeletion records a USER_DELETED event. The deadline is the one Auth
// published: User never recomputes it (ADR-005, USER-BR-007).
func NewDeletion(userID uuid.UUID, deletedAt, hardDeleteAfter time.Time, reason vo.DeletionReason, now time.Time) (*Deletion, error) {
	if !reason.Valid() {
		return nil, apperr.ErrValidation.WithDetail("Unsupported deletion reason.")
	}
	if !hardDeleteAfter.After(deletedAt) {
		return nil, apperr.ErrValidation.WithDetail("hard_delete_after must be later than deleted_at.")
	}

	return &Deletion{
		ID:              uuid.New(),
		UserID:          userID,
		DeletedAt:       deletedAt.UTC(),
		HardDeleteAfter: hardDeleteAfter.UTC(),
		Reason:          reason,
		CreatedAt:       now.UTC(),
	}, nil
}

// IsDue reports whether the retention window has elapsed.
func (d *Deletion) IsDue(now time.Time) bool {
	return !d.HardDeleteAfter.After(now.UTC())
}

// EnsureDue fails closed when a worker reaches a record before its deadline.
func (d *Deletion) EnsureDue(now time.Time) error {
	if !d.IsDue(now) {
		return apperr.ErrRetentionNotExpired
	}
	return nil
}
