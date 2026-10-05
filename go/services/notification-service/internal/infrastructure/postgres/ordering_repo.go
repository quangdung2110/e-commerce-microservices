package postgres

import (
	"context"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.OrderingRepository = (*OrderingRepository)(nil)

// OrderingRepository implements the Order ordering state of 05-ordering.md §4
// and 09-data-model.md §3–§4: notification_subject_sequence (consumer) and
// notification_ordering_lease (deliver worker, reaper).
type OrderingRepository struct {
	q querier
}

func (r *OrderingRepository) now() time.Time { return time.Now().UTC() }

// Advisory-lock namespaces keep these locks apart from each other and from
// any other user of pg_advisory_xact_lock in the same database.
const (
	subjectLockNamespace = int64(0x4e545351) << 32 // "NTSQ": Order subject ledger
	userLockNamespace    = int64(0x4e545553) << 32 // "NTUS": deleted-user tombstone
)

// LockSubject takes one transaction-scoped advisory lock per key version,
// sorted, so two consumers of the same subject — even while one of them
// already writes a newer key version — serialise instead of racing on the
// first insert (a missing row cannot be locked FOR UPDATE).
func (r *OrderingRepository) LockSubject(ctx context.Context, source string, keys []pii.KeyedHash) error {
	return advisoryLock(ctx, r.q, subjectLockNamespace, source, keys)
}

// advisoryLock locks every key under the namespace in ascending order (a
// fixed order cannot deadlock against another holder of the same keys).
func advisoryLock(ctx context.Context, q querier, namespace int64, salt string, keys []pii.KeyedHash) error {
	ids := make([]int64, 0, len(keys))
	for _, k := range keys {
		ids = append(ids, advisoryID(namespace, salt, k))
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	for _, id := range ids {
		if _, err := q.Exec(ctx, `SELECT pg_advisory_xact_lock($1)`, id); err != nil {
			return mapError(err)
		}
	}
	return nil
}

// advisoryID folds the key into 32 bits under the namespace; a collision only
// serialises two unrelated subjects for one transaction.
func advisoryID(namespace int64, salt string, k pii.KeyedHash) int64 {
	raw, err := hex.DecodeString(k.Hash)
	if err != nil || len(raw) < 4 {
		raw = []byte(k.Hash + salt + "0000")
	}
	return namespace | int64(binary.BigEndian.Uint32(raw[:4]))
}

const subjectSequenceColumns = `source, subject_key_version, subject_key_hash, highest_sequence, highest_event_id, highest_payload_sha256, updated_at`

func (r *OrderingRepository) SubjectSequences(ctx context.Context, source string, keys []pii.KeyedHash) ([]entity.SubjectSequence, error) {
	if len(keys) == 0 {
		return nil, nil
	}
	versions := make([]int16, 0, len(keys))
	hashes := make([]string, 0, len(keys))
	for _, k := range keys {
		versions = append(versions, k.Version)
		hashes = append(hashes, k.Hash)
	}
	const query = `
		SELECT ` + subjectSequenceColumns + `
		FROM   notification_subject_sequence s
		JOIN   unnest($2::smallint[], $3::text[]) AS k(version, hash)
		       ON s.subject_key_version = k.version AND s.subject_key_hash = k.hash
		WHERE  s.source = $1
		ORDER  BY s.subject_key_version DESC`
	rows, err := r.q.Query(ctx, query, source, versions, hashes)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, func(rows pgx.Rows) (entity.SubjectSequence, error) {
		var s entity.SubjectSequence
		err := rows.Scan(&s.Source, &s.KeyVersion, &s.KeyHash, &s.HighestSequence, &s.HighestEventID, &s.HighestPayloadSHA256, &s.UpdatedAt)
		return s, err
	})
}

func (r *OrderingRepository) SaveSubjectSequence(ctx context.Context, s *entity.SubjectSequence) error {
	const query = `
		INSERT INTO notification_subject_sequence (` + subjectSequenceColumns + `)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (source, subject_key_version, subject_key_hash) DO UPDATE
		SET highest_sequence       = EXCLUDED.highest_sequence,
		    highest_event_id       = EXCLUDED.highest_event_id,
		    highest_payload_sha256 = EXCLUDED.highest_payload_sha256,
		    updated_at             = EXCLUDED.updated_at`
	updated := s.UpdatedAt
	if updated.IsZero() {
		updated = r.now()
	}
	_, err := r.q.Exec(ctx, query, s.Source, s.KeyVersion, s.KeyHash, s.HighestSequence, s.HighestEventID, s.HighestPayloadSHA256, updated)
	return mapError(err)
}

// AcquireLease: insert, take over an expired lease, or re-enter our own.
// RETURNING yields a row only when the insert or the guarded update ran.
func (r *OrderingRepository) AcquireLease(ctx context.Context, orderingKey string, deliveryID uuid.UUID, workerID string, lease time.Duration) (bool, error) {
	now := r.now()
	const query = `
		INSERT INTO notification_ordering_lease AS l (ordering_key, delivery_id, lease_until, locked_by)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (ordering_key) DO UPDATE
		SET    delivery_id = EXCLUDED.delivery_id,
		       lease_until = EXCLUDED.lease_until,
		       locked_by   = EXCLUDED.locked_by
		WHERE  l.lease_until < $5
		   OR  (l.delivery_id = EXCLUDED.delivery_id AND l.locked_by = EXCLUDED.locked_by)
		RETURNING l.delivery_id`
	var got uuid.UUID
	err := r.q.QueryRow(ctx, query, orderingKey, deliveryID, now.Add(lease), workerID, now).Scan(&got)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, mapError(err)
	}
	return got == deliveryID, nil
}

func (r *OrderingRepository) ExtendLease(ctx context.Context, orderingKey string, deliveryID uuid.UUID, workerID string, lease time.Duration) (bool, error) {
	now := r.now()
	const query = `
		UPDATE notification_ordering_lease
		SET    lease_until = $4
		WHERE  ordering_key = $1 AND delivery_id = $2 AND locked_by = $3 AND lease_until >= $5`
	tag, err := r.q.Exec(ctx, query, orderingKey, deliveryID, workerID, now.Add(lease), now)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

func (r *OrderingRepository) HoldsLease(ctx context.Context, orderingKey string, deliveryID uuid.UUID, workerID string) (bool, error) {
	const query = `
		SELECT 1 FROM notification_ordering_lease
		WHERE  ordering_key = $1 AND delivery_id = $2 AND locked_by = $3 AND lease_until >= $4
		FOR SHARE`
	var one int
	err := r.q.QueryRow(ctx, query, orderingKey, deliveryID, workerID, r.now()).Scan(&one)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, mapError(err)
	}
	return true, nil
}

func (r *OrderingRepository) ReleaseLease(ctx context.Context, orderingKey string, deliveryID uuid.UUID, workerID string) (bool, error) {
	tag, err := r.q.Exec(ctx, `DELETE FROM notification_ordering_lease WHERE ordering_key = $1 AND delivery_id = $2 AND locked_by = $3`,
		orderingKey, deliveryID, workerID)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

// ReapExpiredLeases deletes leases past lease_until. The row is re-checked
// under its lock by DELETE, so a lease renewed or taken over meanwhile (same
// key, new lease_until or new delivery_id) is left alone (16-operations R-15).
func (r *OrderingRepository) ReapExpiredLeases(ctx context.Context, now time.Time) (int64, error) {
	const query = `
		DELETE FROM notification_ordering_lease l
		USING  (SELECT ordering_key, delivery_id FROM notification_ordering_lease WHERE lease_until < $1) e
		WHERE  l.ordering_key = e.ordering_key AND l.delivery_id = e.delivery_id AND l.lease_until < $1`
	tag, err := r.q.Exec(ctx, query, now)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// ExpiredLeaseAges groups by the source prefix of the ordering key
// (source:version:hash; the source has no ':').
func (r *OrderingRepository) ExpiredLeaseAges(ctx context.Context, now time.Time) ([]entity.OrderingLeaseAge, error) {
	const query = `
		SELECT split_part(ordering_key, ':', 1), EXTRACT(EPOCH FROM ($1::timestamptz - min(lease_until)))::float8
		FROM   notification_ordering_lease
		WHERE  lease_until < $1::timestamptz
		GROUP  BY 1`
	rows, err := r.q.Query(ctx, query, now)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, func(rows pgx.Rows) (entity.OrderingLeaseAge, error) {
		var (
			a       entity.OrderingLeaseAge
			seconds float64
		)
		err := rows.Scan(&a.Source, &seconds)
		a.Age = time.Duration(seconds * float64(time.Second))
		return a, err
	})
}

var _ port.TombstoneRepository = (*TombstoneRepository)(nil)

// TombstoneRepository is deleted_user_tombstone (09-data-model.md §10).
type TombstoneRepository struct {
	q querier
}

// Lock serialises user.deleted against preferences-updated for one user
// inside the current transaction (advisory locks on every key version).
func (r *TombstoneRepository) Lock(ctx context.Context, keys []pii.KeyedHash) error {
	return advisoryLock(ctx, r.q, userLockNamespace, "user", keys)
}

func (r *TombstoneRepository) Insert(ctx context.Context, keys []pii.KeyedHash, deletedAt time.Time) error {
	for _, k := range keys {
		_, err := r.q.Exec(ctx, `
			INSERT INTO deleted_user_tombstone (user_key_version, user_key_hash, deleted_at)
			VALUES ($1, $2, $3)
			ON CONFLICT (user_key_version, user_key_hash) DO NOTHING`, k.Version, k.Hash, deletedAt)
		if err != nil {
			return mapError(err)
		}
	}
	return nil
}

func (r *TombstoneRepository) Exists(ctx context.Context, keys []pii.KeyedHash) (bool, error) {
	if len(keys) == 0 {
		return false, nil
	}
	versions := make([]int16, 0, len(keys))
	hashes := make([]string, 0, len(keys))
	for _, k := range keys {
		versions = append(versions, k.Version)
		hashes = append(hashes, k.Hash)
	}
	var exists bool
	err := r.q.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM deleted_user_tombstone t
			JOIN   unnest($1::smallint[], $2::text[]) AS k(version, hash)
			       ON t.user_key_version = k.version AND t.user_key_hash = k.hash)`, versions, hashes).Scan(&exists)
	if err != nil {
		return false, mapError(err)
	}
	return exists, nil
}
