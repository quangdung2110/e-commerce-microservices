package entity

import (
	"time"

	"github.com/google/uuid"
)

// UserDeletion represents a GDPR deletion request stored for 2-phase hard delete
// This is the Event Store for tracking account deletions with retention windows
type UserDeletion struct {
	ID                        uuid.UUID  `json:"id" db:"id"`
	UserID                    uuid.UUID  `json:"user_id" db:"user_id"`
	DeletedAt                 time.Time `json:"deleted_at" db:"deleted_at"`
	RetentionWindowExpiresAt  time.Time `json:"retention_window_expires_at" db:"retention_window_expires_at"`
	Reason                    string    `json:"reason" db:"reason"` // "GDPR_REQUEST" or "ADMIN_DELETION"
}

// NewUserDeletion creates a new UserDeletion entity
func NewUserDeletion(userID uuid.UUID, retentionWindowExpiresAt time.Time, reason string) *UserDeletion {
	return &UserDeletion{
		ID:                       uuid.New(),
		UserID:                   userID,
		DeletedAt:                time.Now(),
		RetentionWindowExpiresAt: retentionWindowExpiresAt,
		Reason:                   reason,
	}
}

// IsRetentionExpired checks if the retention window has expired
func (d *UserDeletion) IsRetentionExpired() bool {
	return time.Now().After(d.RetentionWindowExpiresAt)
}

// DaysUntilHardDelete returns the number of days until hard delete is allowed
func (d *UserDeletion) DaysUntilHardDelete() int {
	remaining := d.RetentionWindowExpiresAt.Sub(time.Now())
	if remaining <= 0 {
		return 0
	}
	return int(remaining.Hours() / 24)
}
