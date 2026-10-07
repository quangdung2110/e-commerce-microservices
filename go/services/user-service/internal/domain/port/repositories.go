// Package port declares the outbound interfaces of the domain. Adapters in
// internal/infrastructure implement them; the domain never imports an adapter.
package port

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/user-service/internal/domain/entity"
)

// ProfileRepository owns "user".user_profiles. A missing profile is reported as
// apperr.ErrProfileNotFound, both for a profile that was never bootstrapped and
// for one the retention worker purged.
type ProfileRepository interface {
	// Insert writes the bootstrap profile and reports whether the row was
	// created. A duplicate delivery of USER_VERIFIED_SUCCESS finds the row
	// already there and must not overwrite what the owner has edited
	// (USER-BR-005).
	Insert(ctx context.Context, profile *entity.Profile) (bool, error)
	GetByID(ctx context.Context, userID uuid.UUID) (*entity.Profile, error)
	// GetForUpdate reads the row and locks it for the rest of the transaction,
	// so a profile update and an incoming deletion serialize instead of
	// resurrecting PII (WF-USER-002).
	GetForUpdate(ctx context.Context, userID uuid.UUID) (*entity.Profile, error)
	Update(ctx context.Context, profile *entity.Profile) error
	// HardDelete removes the row permanently. Addresses and preferences cascade,
	// which is only a safety net: the deletion consumer already removed them.
	HardDelete(ctx context.Context, userID uuid.UUID) (bool, error)
}

// AddressRepository owns "user".user_addresses.
type AddressRepository interface {
	// LockUser serializes every mutation of one address book. The partial
	// unique index is the last guard; this lock is what keeps two concurrent
	// requests from both trying to create a default (docs/user/data.md §5).
	LockUser(ctx context.Context, userID uuid.UUID) error
	// ListByUser returns the address book ordered default first, then newest
	// first, which is the order checkout expects.
	ListByUser(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error)
	// GetOwned looks an address up by (address_id, user_id) and returns
	// apperr.ErrAddressNotFound when either does not match (USER-BR-001).
	GetOwned(ctx context.Context, userID, addressID uuid.UUID) (*entity.Address, error)
	CountByUser(ctx context.Context, userID uuid.UUID) (int, error)
	Insert(ctx context.Context, address *entity.Address) error
	Update(ctx context.Context, address *entity.Address) error
	Delete(ctx context.Context, userID, addressID uuid.UUID) error
	// ClearDefault removes the default flag from every address of the user
	// except the given one, which is how set-default stays atomic.
	ClearDefault(ctx context.Context, userID, exceptAddressID uuid.UUID) error
	// SetDefault marks one address as the default of its owner and returns the
	// `updated_at` the schema trigger stored.
	SetDefault(ctx context.Context, userID, addressID uuid.UUID) (time.Time, error)
	// DeleteAllForUser hard-deletes the address book of an anonymized account.
	DeleteAllForUser(ctx context.Context, userID uuid.UUID) (int64, error)
}

// PreferencesRepository owns "user".user_preferences.
type PreferencesRepository interface {
	// Insert writes the defaults and reports whether the row was created, so a
	// replayed bootstrap does not reset an opt-out the owner made.
	Insert(ctx context.Context, preferences *entity.Preferences) (bool, error)
	GetByUser(ctx context.Context, userID uuid.UUID) (*entity.Preferences, error)
	Update(ctx context.Context, preferences *entity.Preferences) error
	DeleteForUser(ctx context.Context, userID uuid.UUID) (bool, error)
}

// DeletionRepository owns "user".user_deletions, the retention record.
type DeletionRepository interface {
	// Insert creates the record and reports whether it was new. A duplicate
	// USER_DELETED finds the row and must not extend the deadline
	// (USER-BR-006).
	Insert(ctx context.Context, deletion *entity.Deletion) (bool, error)
	ExistsForUser(ctx context.Context, userID uuid.UUID) (bool, error)
	// ListDue returns the retention records whose deadline has passed, oldest
	// deadline first.
	ListDue(ctx context.Context, now time.Time, limit int) ([]*entity.Deletion, error)
	// ClaimDue locks one due record with FOR UPDATE SKIP LOCKED and returns nil
	// when another replica holds it or the deadline has not passed. It must be
	// called inside a transaction (WF-USER-007).
	ClaimDue(ctx context.Context, deletionID uuid.UUID, now time.Time) (*entity.Deletion, error)
	Delete(ctx context.Context, deletionID uuid.UUID) error
	// CountOverdueBefore reports the backlog used by the compliance alert.
	CountOverdueBefore(ctx context.Context, cutoff time.Time) (int64, error)
}

// ProjectionSnapshot is the current state of one active user, as read by the
// reconciliation sweep. Preferences is nil when the user has no preferences row.
type ProjectionSnapshot struct {
	UserID           uuid.UUID
	SenderName       string
	ProfileUpdatedAt time.Time
	Preferences      *PreferencesSnapshot
}

// PreferencesSnapshot is the replicated part of a preferences row.
type PreferencesSnapshot struct {
	EmailEnabled     bool
	InAppEnabled     bool
	MarketingEnabled bool
	UpdatedAt        time.Time
}

// ProjectionRepository serves the daily reconciliation sweep of ADR-007. It
// reads current state only: there is no outbox, no checkpoint table and no
// event-id deduplication store in this schema.
type ProjectionRepository interface {
	// ListActive returns one batch of users ordered by id, starting after the
	// given id. Users with a retention record are excluded, because deletion is
	// terminal and must never be undone by a corrective snapshot.
	ListActive(ctx context.Context, afterUserID uuid.UUID, limit int) ([]ProjectionSnapshot, error)
}

// TxManager runs a unit of work inside one PostgreSQL transaction. The
// transaction travels in the context, so repositories join it transparently.
// Redis and Kafka never take part in it (docs/user/README.md §3).
type TxManager interface {
	WithinTx(ctx context.Context, fn func(ctx context.Context) error) error
}
