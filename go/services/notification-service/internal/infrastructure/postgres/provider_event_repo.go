package postgres

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.ProviderEventRepository = (*ProviderEventRepository)(nil)

// ProviderEventRepository is the Track queue (03-tracking.md §3): the SQS
// poller appends, the track worker claims and finishes. Rows are (id,
// received_at) because the table is partitioned on received_at.
type ProviderEventRepository struct {
	q querier
}

const providerEventColumns = `id, provider, provider_event_id, provider_message_id, event_type, occurred_at, raw, status, attempt, next_attempt_at, received_at`

// Insert appends one event; the identity id and received_at come back into
// the entity so the caller can reference the row (evidence_event_id).
func (r *ProviderEventRepository) Insert(ctx context.Context, ev *entity.ProviderEvent) error {
	status := ev.Status
	if status == "" {
		status = entity.ProviderEventPending
	}
	var nextAttempt, receivedAt *time.Time
	if !ev.NextAttemptAt.IsZero() {
		nextAttempt = &ev.NextAttemptAt
	}
	if !ev.ReceivedAt.IsZero() {
		receivedAt = &ev.ReceivedAt
	}
	const query = `
		INSERT INTO provider_event (provider, provider_event_id, provider_message_id, event_type, occurred_at, raw,
		                            status, attempt, next_attempt_at, received_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8,
		        COALESCE($9::timestamptz, COALESCE($10::timestamptz, now())),
		        COALESCE($10::timestamptz, now()))
		RETURNING id, next_attempt_at, received_at`
	err := r.q.QueryRow(ctx, query, string(ev.Provider), ev.ProviderEventID, ev.ProviderMessageID,
		string(ev.EventType), ev.OccurredAt, rawJSONOrNil(ev.Raw), string(status), ev.Attempt, nextAttempt, receivedAt).
		Scan(&ev.ID, &ev.NextAttemptAt, &ev.ReceivedAt)
	if err != nil {
		return mapError(err)
	}
	ev.Status = status
	return nil
}

// Claim picks ready rows with FOR UPDATE SKIP LOCKED; the caller keeps the
// transaction open until SetStatus/Reschedule, so two track workers never
// process the same event.
func (r *ProviderEventRepository) Claim(ctx context.Context, batch int, now time.Time) ([]entity.ProviderEvent, error) {
	if batch <= 0 {
		return nil, nil
	}
	rows, err := r.q.Query(ctx, `
		SELECT `+providerEventColumns+`
		FROM   provider_event
		WHERE  status IN ('PENDING', 'UNMATCHED') AND next_attempt_at <= $1
		ORDER  BY next_attempt_at
		LIMIT  $2
		FOR UPDATE SKIP LOCKED`, now, batch)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, scanProviderEvent)
}

func (r *ProviderEventRepository) SetStatus(ctx context.Context, id int64, receivedAt time.Time, status entity.ProviderEventStatus) error {
	tag, err := r.q.Exec(ctx, `UPDATE provider_event SET status = $3 WHERE id = $1 AND received_at = $2`, id, receivedAt, string(status))
	if err != nil {
		return mapError(err)
	}
	if tag.RowsAffected() == 0 {
		return mapError(pgx.ErrNoRows)
	}
	return nil
}

// Reschedule marks UNMATCHED and pushes the next try (03-tracking.md §5.1:
// the delivery row may not have its provider_message_id committed yet).
func (r *ProviderEventRepository) Reschedule(ctx context.Context, id int64, receivedAt time.Time, attempt int, nextAttemptAt time.Time) error {
	tag, err := r.q.Exec(ctx, `
		UPDATE provider_event
		SET    status = 'UNMATCHED', attempt = $3, next_attempt_at = $4
		WHERE  id = $1 AND received_at = $2`, id, receivedAt, attempt, nextAttemptAt)
	if err != nil {
		return mapError(err)
	}
	if tag.RowsAffected() == 0 {
		return mapError(pgx.ErrNoRows)
	}
	return nil
}

func (r *ProviderEventRepository) ListByProviderMessage(ctx context.Context, providerMessageID string) ([]entity.ProviderEvent, error) {
	rows, err := r.q.Query(ctx, `
		SELECT `+providerEventColumns+`
		FROM   provider_event
		WHERE  provider_message_id = $1
		ORDER  BY received_at, id`, providerMessageID)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, scanProviderEvent)
}

// OldestUnmatchedAge feeds the "unmatched > N" alert.
func (r *ProviderEventRepository) OldestUnmatchedAge(ctx context.Context, now time.Time) (time.Duration, error) {
	var oldest *time.Time
	err := r.q.QueryRow(ctx, `SELECT min(received_at) FROM provider_event WHERE status = 'UNMATCHED'`).Scan(&oldest)
	if err != nil {
		return 0, mapError(err)
	}
	if oldest == nil {
		return 0, nil
	}
	return now.Sub(*oldest), nil
}

func scanProviderEvent(rows pgx.Rows) (entity.ProviderEvent, error) {
	var (
		ev                          entity.ProviderEvent
		provider, eventType, status string
		raw                         []byte
	)
	err := rows.Scan(&ev.ID, &provider, &ev.ProviderEventID, &ev.ProviderMessageID, &eventType, &ev.OccurredAt,
		&raw, &status, &ev.Attempt, &ev.NextAttemptAt, &ev.ReceivedAt)
	if err != nil {
		return ev, err
	}
	ev.Provider = entity.Provider(provider)
	ev.EventType = entity.ProviderEventType(eventType)
	ev.Status = entity.ProviderEventStatus(status)
	ev.Raw = json.RawMessage(raw)
	return ev, nil
}
