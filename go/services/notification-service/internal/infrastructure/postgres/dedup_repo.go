package postgres

import (
	"context"
	"time"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.DedupRepository = (*DedupRepository)(nil)

// DedupRepository is the three dedup ledgers (09-data-model.md §1). Each
// Claim* is `INSERT ... ON CONFLICT DO NOTHING`: the unique constraint is the
// arbiter, never a SELECT-then-INSERT (04-idempotency.md lớp A).
type DedupRepository struct {
	q querier
}

func (r *DedupRepository) ClaimIngest(ctx context.Context, source, eventID string) (bool, error) {
	const query = `INSERT INTO ingest_dedup (source, event_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`
	tag, err := r.q.Exec(ctx, query, source, eventID)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

func (r *DedupRepository) ClaimDelivery(ctx context.Context, dedupKey string, channel entity.Channel) (bool, error) {
	const query = `INSERT INTO delivery_dedup (dedup_key, channel) VALUES ($1, $2) ON CONFLICT DO NOTHING`
	tag, err := r.q.Exec(ctx, query, dedupKey, string(channel))
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

func (r *DedupRepository) ClaimProviderEvent(ctx context.Context, provider entity.Provider, providerEventID string) (bool, error) {
	const query = `INSERT INTO provider_event_dedup (provider, provider_event_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`
	tag, err := r.q.Exec(ctx, query, string(provider), providerEventID)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

// Purge* delete in bounded batches (09 §9: "DELETE batch hàng ngày") so a
// backlog never turns into one giant transaction. The ctid subquery is the
// standard way to LIMIT a DELETE in Postgres.

func (r *DedupRepository) PurgeIngest(ctx context.Context, before time.Time, limit int) (int64, error) {
	return r.purge(ctx, `
		DELETE FROM ingest_dedup WHERE ctid IN (
			SELECT ctid FROM ingest_dedup WHERE received_at < $1 LIMIT $2)`, before, limit)
}

func (r *DedupRepository) PurgeDelivery(ctx context.Context, before time.Time, limit int) (int64, error) {
	return r.purge(ctx, `
		DELETE FROM delivery_dedup WHERE ctid IN (
			SELECT ctid FROM delivery_dedup WHERE created_at < $1 LIMIT $2)`, before, limit)
}

func (r *DedupRepository) PurgeProviderEvent(ctx context.Context, before time.Time, limit int) (int64, error) {
	return r.purge(ctx, `
		DELETE FROM provider_event_dedup WHERE ctid IN (
			SELECT ctid FROM provider_event_dedup WHERE received_at < $1 LIMIT $2)`, before, limit)
}

func (r *DedupRepository) purge(ctx context.Context, query string, before time.Time, limit int) (int64, error) {
	if limit <= 0 {
		limit = 10000
	}
	tag, err := r.q.Exec(ctx, query, before, limit)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}
