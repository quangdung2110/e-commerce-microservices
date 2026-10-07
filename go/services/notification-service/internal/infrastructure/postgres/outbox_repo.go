package postgres

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.OutboxRepository = (*OutboxRepository)(nil)

// OutboxRepository is the Debezium-compatible outbox (09-data-model.md §8).
// Insert is always called inside the transaction that changes the delivery
// state so the CloudEvents id is committed exactly once (04-idempotency.md
// lớp D).
type OutboxRepository struct {
	q querier
}

const outboxColumns = `id, aggregatetype, aggregateid, type, payload, created_at, published_at`

func (r *OutboxRepository) Insert(ctx context.Context, ev *entity.OutboxEvent) error {
	const query = `
		INSERT INTO outbox (` + outboxColumns + `)
		VALUES ($1, $2, $3, $4, $5, COALESCE($6::timestamptz, now()), $7)`
	var createdAt *time.Time
	if !ev.CreatedAt.IsZero() {
		createdAt = &ev.CreatedAt
	}
	_, err := r.q.Exec(ctx, query, ev.ID, ev.AggregateType, ev.AggregateID, ev.Type,
		[]byte(ev.Payload), createdAt, ev.PublishedAt)
	return mapError(err)
}

// FetchUnpublished locks rows for the poller (outbox.publisher = poller). It
// is meant to run inside WithinTx: the locks are what keep two poller
// replicas from publishing the same row.
func (r *OutboxRepository) FetchUnpublished(ctx context.Context, limit int) ([]entity.OutboxEvent, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.q.Query(ctx, `
		SELECT `+outboxColumns+`
		FROM   outbox
		WHERE  published_at IS NULL
		ORDER  BY created_at
		LIMIT  $1
		FOR UPDATE SKIP LOCKED`, limit)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, scanOutbox)
}

func (r *OutboxRepository) MarkPublished(ctx context.Context, ids []uuid.UUID, at time.Time) error {
	if len(ids) == 0 {
		return nil
	}
	_, err := r.q.Exec(ctx, `UPDATE outbox SET published_at = $2 WHERE id = ANY($1) AND published_at IS NULL`, ids, at)
	return mapError(err)
}

func (r *OutboxRepository) PurgePublished(ctx context.Context, before time.Time, limit int) (int64, error) {
	if limit <= 0 {
		limit = 10000
	}
	tag, err := r.q.Exec(ctx, `
		DELETE FROM outbox WHERE id IN (
			SELECT id FROM outbox WHERE published_at IS NOT NULL AND published_at < $1 LIMIT $2)`, before, limit)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// Stats feeds outbox_unpublished_count / outbox_oldest_age_seconds.
func (r *OutboxRepository) Stats(ctx context.Context, now time.Time) (int64, time.Duration, error) {
	var (
		count  int64
		oldest *time.Time
	)
	err := r.q.QueryRow(ctx, `SELECT count(*), min(created_at) FROM outbox WHERE published_at IS NULL`).Scan(&count, &oldest)
	if err != nil {
		return 0, 0, mapError(err)
	}
	if oldest == nil {
		return count, 0, nil
	}
	return count, now.Sub(*oldest), nil
}

func scanOutbox(rows pgx.Rows) (entity.OutboxEvent, error) {
	var (
		ev      entity.OutboxEvent
		payload []byte
	)
	err := rows.Scan(&ev.ID, &ev.AggregateType, &ev.AggregateID, &ev.Type, &payload, &ev.CreatedAt, &ev.PublishedAt)
	if err != nil {
		return ev, err
	}
	ev.Payload = json.RawMessage(payload)
	return ev, nil
}
