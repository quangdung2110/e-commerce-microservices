// Package port declares the interfaces the domain depends on. Infrastructure
// packages implement them; domain services and workers only see these types.
package port

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// Store is the Postgres unit of work. Every repository method takes a context;
// inside WithinTx the repositories returned by Tx run on the transaction, so
// "INSERT inbox_item + mark DELIVERED + INSERT outbox" is atomic
// (08-decide.md §8, 04-idempotency.md lớp D).
type Store interface {
	// WithinTx runs fn in one transaction; commit on nil, rollback on error.
	WithinTx(ctx context.Context, fn func(ctx context.Context, tx Tx) error) error
	// Repositories outside a transaction (auto-commit per statement).
	Tx
	// Ping is used by readiness.
	Ping(ctx context.Context) error
}

// Tx bundles the repositories bound to one connection or transaction.
type Tx interface {
	Requests() RequestRepository
	Deliveries() DeliveryRepository
	Dedup() DedupRepository
	Preferences() PreferenceRepository
	Suppressions() SuppressionRepository
	Inbox() InboxRepository
	Outbox() OutboxRepository
	ProviderEvents() ProviderEventRepository
	Ops() OpsRepository
	Maintenance() MaintenanceRepository
	Ordering() OrderingRepository
	Tombstones() TombstoneRepository
}

// RequestRepository persists notification_request.
type RequestRepository interface {
	Insert(ctx context.Context, r *entity.Request) error
	// UpdateDecision writes decision, decision_reason, expires_at (effective),
	// template and lane (facts learn them at Decide).
	UpdateDecision(ctx context.Context, r *entity.Request) error
	GetByID(ctx context.Context, id uuid.UUID) (*entity.Request, error)
	// AnonymizeByUser sets user_id, template_data, subject to NULL (11-privacy §4).
	AnonymizeByUser(ctx context.Context, userID uuid.UUID) (int64, error)
}

// DedupRepository is the three dedup ledgers (09-data-model.md §1). Claim*
// return false when the key already existed (ON CONFLICT DO NOTHING → 0 rows).
type DedupRepository interface {
	ClaimIngest(ctx context.Context, source, eventID string) (bool, error)
	ClaimDelivery(ctx context.Context, dedupKey string, channel entity.Channel) (bool, error)
	ClaimProviderEvent(ctx context.Context, provider entity.Provider, providerEventID string) (bool, error)
	PurgeIngest(ctx context.Context, before time.Time, limit int) (int64, error)
	PurgeDelivery(ctx context.Context, before time.Time, limit int) (int64, error)
	PurgeProviderEvent(ctx context.Context, before time.Time, limit int) (int64, error)
}

// DeliveryRepository persists notification_delivery and implements the queue
// mechanics of 02-pipeline.md §5. Every Mark* method that takes workerID is a
// compare-and-set on `status = 'SENDING' AND locked_by = workerID` and returns
// false when the row was already taken over (layer C2).
type DeliveryRepository interface {
	Insert(ctx context.Context, d *entity.Delivery) error
	GetByID(ctx context.Context, id uuid.UUID) (*entity.Delivery, error)
	ListByRequest(ctx context.Context, requestID uuid.UUID) ([]entity.Delivery, error)
	ListBySubject(ctx context.Context, subject string, template string, limit int) ([]entity.Delivery, error)
	FindByProviderMessage(ctx context.Context, provider entity.Provider, messageID string) (*entity.Delivery, error)

	// Claim atomically picks up to batch ready rows (PENDING/RETRY,
	// next_attempt_at <= now) for lane×channel, marks them SENDING with a lease
	// and attempt+1, and returns them (FOR UPDATE SKIP LOCKED + RETURNING).
	Claim(ctx context.Context, lane entity.Lane, channel entity.Channel, workerID string, batch int, lease time.Duration) ([]entity.Delivery, error)
	// ExtendLease is the heartbeat for rows this worker still holds.
	ExtendLease(ctx context.Context, ids []uuid.UUID, workerID string, lease time.Duration) error
	// Release puts a claimed row back without counting the attempt (breaker
	// open, no rate-limit token, lane paused): status RETRY, attempt-1,
	// next_attempt_at, last_error_code.
	Release(ctx context.Context, id uuid.UUID, workerID string, nextAttemptAt time.Time, errorCode string) (bool, error)
	MarkSent(ctx context.Context, id uuid.UUID, workerID string, provider entity.Provider, providerMessageID, templateVersion string, sentAt time.Time, possiblyDuplicate bool) (bool, error)
	MarkRetry(ctx context.Context, id uuid.UUID, workerID string, nextAttemptAt time.Time, errorCode, errorMsg string) (bool, error)
	// MarkFinal moves a SENDING row held by workerID to a terminal status
	// (FAILED with last_error_code, EXPIRED, SKIPPED with skip_reason) and sets
	// finished_at.
	MarkFinal(ctx context.Context, id uuid.UUID, workerID string, status entity.DeliveryStatus, reasonCode, detail string, at time.Time) (bool, error)
	// MarkDeliveredInApp finishes an in_app row: SENT+DELIVERED in one step,
	// provider inbox, sent_at = delivered_at = at.
	MarkDeliveredInApp(ctx context.Context, id uuid.UUID, workerID string, templateVersion string, at time.Time) (bool, error)
	// ReapExpiredLeases moves SENDING rows past lease_until back to RETRY with
	// last_error_code = lease_expired and returns how many.
	ReapExpiredLeases(ctx context.Context, now time.Time) (int64, error)

	// TransitionTracked applies a provider-reported status by rank
	// (03-tracking.md §5.1): SENT(1) < DELIVERED(2) < BOUNCED(3). It updates
	// only when the new rank is >= the current one (equal rank refreshes
	// timestamps only) and returns whether the status changed. It also applies
	// from FAILED(lease_expired)/RETRY/SENDING so a late webhook can recover a
	// row (04-idempotency §4 step 7).
	TransitionTracked(ctx context.Context, id uuid.UUID, to entity.DeliveryStatus, at time.Time, note string) (changed bool, err error)

	// Redrive moves FAILED/EXPIRED → PENDING with attempt 0 (ops API); returns
	// ErrStateConflict for other statuses and ErrValidation when the row is
	// past expires_at and no new expiry is given.
	Redrive(ctx context.Context, id uuid.UUID, newExpiresAt *time.Time, now time.Time) (*entity.Delivery, error)
	// CountRedrivable counts FAILED rows matching the filter (dlq redrive 202).
	CountRedrivable(ctx context.Context, f entity.RedriveFilter) (int64, error)
	// RedriveBatch redrives up to f.Limit FAILED rows matching the filter; never
	// includes producer-contract reason codes.
	RedriveBatch(ctx context.Context, f entity.RedriveFilter, now time.Time) (int64, error)

	// SkipPendingByUser marks PENDING/RETRY rows of a user SKIPPED (user_deleted).
	SkipPendingByUser(ctx context.Context, userID uuid.UUID, reason string, now time.Time) (int64, error)
	AnonymizeByUser(ctx context.Context, userID uuid.UUID) (int64, error)

	// PendingStats feeds the queue gauges.
	PendingStats(ctx context.Context, now time.Time) ([]entity.PendingStat, error)
}

// PreferenceRepository is the replicated preference table.
type PreferenceRepository interface {
	// Get returns errors.ErrNotFound when no row exists.
	Get(ctx context.Context, userID uuid.UUID) (*entity.Preference, error)
	// Upsert applies an event with compare-and-set on version; returns false
	// when a newer version was already stored.
	Upsert(ctx context.Context, p *entity.Preference) (bool, error)
	// DisableMarketing sets marketing_enabled=false (complaint), inserting a
	// default row when none exists. Version is bumped to the given value only
	// if greater.
	DisableMarketing(ctx context.Context, userID uuid.UUID, version int64, now time.Time) error
	Delete(ctx context.Context, userID uuid.UUID) error
}

// SuppressionRepository is the do-not-mail ledger.
type SuppressionRepository interface {
	Insert(ctx context.Context, s *entity.Suppression) error
	GetByID(ctx context.Context, id uuid.UUID) (*entity.Suppression, error)
	// ActiveByUser returns non-superseded rows for the user (caller checks Until).
	ActiveByUser(ctx context.Context, userID uuid.UUID) ([]entity.Suppression, error)
	ActiveByHash(ctx context.Context, emailHash string) ([]entity.Suppression, error)
	MarkSuperseded(ctx context.Context, id uuid.UUID, at time.Time) error
	Delete(ctx context.Context, id uuid.UUID) error
	DetachUser(ctx context.Context, userID uuid.UUID) (int64, error)
}

// InboxRepository is the in-app read model.
type InboxRepository interface {
	Insert(ctx context.Context, item *entity.InboxItem) error
	Get(ctx context.Context, userID, id uuid.UUID) (*entity.InboxItem, error)
	List(ctx context.Context, userID uuid.UUID, cursor *entity.InboxCursor, limit int, unreadOnly bool) (*entity.InboxPage, error)
	// SetRead returns the item after update and whether the unread state changed.
	SetRead(ctx context.Context, userID, id uuid.UUID, read bool, now time.Time) (*entity.InboxItem, bool, error)
	// Archive returns whether the item was unread before archiving (for the
	// unread counter) and false,nil when already archived.
	Archive(ctx context.Context, userID, id uuid.UUID, now time.Time) (wasUnread bool, changed bool, err error)
	MarkAllRead(ctx context.Context, userID uuid.UUID, now time.Time) (int64, error)
	CountUnread(ctx context.Context, userID uuid.UUID) (int64, error)
	DeleteByUser(ctx context.Context, userID uuid.UUID) (int64, error)
}

// OutboxRepository is the Debezium-compatible outbox.
type OutboxRepository interface {
	Insert(ctx context.Context, ev *entity.OutboxEvent) error
	// FetchUnpublished locks (SKIP LOCKED) up to limit rows for the poller.
	FetchUnpublished(ctx context.Context, limit int) ([]entity.OutboxEvent, error)
	MarkPublished(ctx context.Context, ids []uuid.UUID, at time.Time) error
	PurgePublished(ctx context.Context, before time.Time, limit int) (int64, error)
	// Stats returns unpublished count and oldest unpublished age (poller mode).
	Stats(ctx context.Context, now time.Time) (count int64, oldest time.Duration, err error)
}

// ProviderEventRepository is the Track queue (03-tracking.md §3).
type ProviderEventRepository interface {
	Insert(ctx context.Context, ev *entity.ProviderEvent) error
	// Claim picks PENDING/UNMATCHED rows ready now (FOR UPDATE SKIP LOCKED);
	// the caller must hold the transaction until it finishes them.
	Claim(ctx context.Context, batch int, now time.Time) ([]entity.ProviderEvent, error)
	SetStatus(ctx context.Context, id int64, receivedAt time.Time, status entity.ProviderEventStatus) error
	Reschedule(ctx context.Context, id int64, receivedAt time.Time, attempt int, nextAttemptAt time.Time) error
	ListByProviderMessage(ctx context.Context, providerMessageID string) ([]entity.ProviderEvent, error)
	OldestUnmatchedAge(ctx context.Context, now time.Time) (time.Duration, error)
}

// OpsRepository stores control switches, idempotency records and audit.
type OpsRepository interface {
	GetControl(ctx context.Context, key string) ([]byte, error) // ErrNotFound when absent
	SetControl(ctx context.Context, key string, value []byte, updatedBy string, now time.Time) error
	ListControls(ctx context.Context, prefix string) (map[string][]byte, error)

	// BeginIdempotent records (key, hash) and returns the existing record when
	// the key was seen before (caller compares hashes → 409).
	BeginIdempotent(ctx context.Context, key, requestHash string, now time.Time) (existing *entity.IdempotencyRecord, err error)
	CompleteIdempotent(ctx context.Context, key string, status int, response []byte) error
	PurgeIdempotent(ctx context.Context, before time.Time) (int64, error)

	InsertAudit(ctx context.Context, e *entity.AuditEntry) error
}

// MaintenanceRepository runs partition and stats jobs (09-data-model.md §9).
type MaintenanceRepository interface {
	// EnsurePartitions creates monthly partitions for the current month and
	// `ahead` months after it on every partitioned table.
	EnsurePartitions(ctx context.Context, now time.Time, ahead int) error
	// FutureMonths returns, per table, how many whole future months have a
	// partition (metric partition_future_months).
	FutureMonths(ctx context.Context, now time.Time) (map[string]int, error)
	// DropExpiredPartitions drops partitions entirely older than the retention
	// per table; for notification_delivery it refuses (and reports) when the
	// partition still holds PENDING/RETRY/SENDING rows.
	DropExpiredPartitions(ctx context.Context, now time.Time, retentionDays map[string]int) (dropped []string, refused []string, err error)
	// DeadTupleRatio reads pg_stat_user_tables for the hot table.
	DeadTupleRatio(ctx context.Context, table string) (float64, error)
	// Notify wakes deliver workers of a lane (LISTEN/NOTIFY); no-op when unsupported.
	Notify(ctx context.Context, lane entity.Lane) error
}

// OrderingRepository is the Order ordering state (05-ordering.md §4,
// 09-data-model.md §3–§4): the highest-sequence ledger written by the
// consumer and the per-subject provider-call lease used by deliver workers.
type OrderingRepository interface {
	// LockSubject serialises consumers of one subject inside the current
	// transaction (advisory xact locks on every key version, in a fixed order,
	// so a rotation in progress cannot split the lock).
	LockSubject(ctx context.Context, source string, keys []pii.KeyedHash) error
	// SubjectSequences returns the ledger rows of the subject under every given
	// key version (dual-read); empty when the subject was never seen.
	SubjectSequences(ctx context.Context, source string, keys []pii.KeyedHash) ([]entity.SubjectSequence, error)
	// SaveSubjectSequence upserts one ledger row (the consumer writes the
	// active key version, folding older versions in).
	SaveSubjectSequence(ctx context.Context, s *entity.SubjectSequence) error

	// AcquireLease takes the ordering lease for deliveryID held by workerID:
	// inserts it, takes over an expired one, or re-enters one this worker
	// already holds for the same delivery. false = another delivery holds it.
	AcquireLease(ctx context.Context, orderingKey string, deliveryID uuid.UUID, workerID string, lease time.Duration) (bool, error)
	// ExtendLease is the ordering heartbeat; false = ownership lost.
	ExtendLease(ctx context.Context, orderingKey string, deliveryID uuid.UUID, workerID string, lease time.Duration) (bool, error)
	// HoldsLease checks ownership and locks the lease row FOR SHARE until the
	// transaction ends (in_app commits only while still owning the lease).
	HoldsLease(ctx context.Context, orderingKey string, deliveryID uuid.UUID, workerID string) (bool, error)
	// ReleaseLease deletes the lease only if (key, delivery, worker) still own it.
	ReleaseLease(ctx context.Context, orderingKey string, deliveryID uuid.UUID, workerID string) (bool, error)
	// ReapExpiredLeases compare-and-deletes leases past lease_until.
	ReapExpiredLeases(ctx context.Context, now time.Time) (int64, error)
	// ExpiredLeaseAges reports, per source, the oldest lease past lease_until
	// that has not been reclaimed yet.
	ExpiredLeaseAges(ctx context.Context, now time.Time) ([]entity.OrderingLeaseAge, error)
}

// TombstoneRepository is deleted_user_tombstone (09-data-model.md §10).
type TombstoneRepository interface {
	// Lock serialises user.deleted and preferences-updated of one user inside
	// the current transaction, so a preference row cannot be re-created
	// between the tombstone insert and the preference delete.
	Lock(ctx context.Context, keys []pii.KeyedHash) error
	// Insert records HMAC(user_id) under every given key version (idempotent).
	Insert(ctx context.Context, keys []pii.KeyedHash, deletedAt time.Time) error
	// Exists reports whether any of the given key versions is tombstoned.
	Exists(ctx context.Context, keys []pii.KeyedHash) (bool, error)
}
