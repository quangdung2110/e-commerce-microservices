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

var _ port.OpsRepository = (*OpsRepository)(nil)

// OpsRepository stores the control switches, the Idempotency-Key records and
// the audit trail of 16-operations.md §2.
type OpsRepository struct {
	q querier
}

func (r *OpsRepository) GetControl(ctx context.Context, key string) ([]byte, error) {
	var value []byte
	err := r.q.QueryRow(ctx, `SELECT value FROM ops_control WHERE key = $1`, key).Scan(&value)
	if err != nil {
		return nil, mapError(err)
	}
	return value, nil
}

func (r *OpsRepository) SetControl(ctx context.Context, key string, value []byte, updatedBy string, now time.Time) error {
	_, err := r.q.Exec(ctx, `
		INSERT INTO ops_control (key, value, updated_at, updated_by)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (key) DO UPDATE
		SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by`,
		key, value, now, nullString(updatedBy))
	return mapError(err)
}

func (r *OpsRepository) ListControls(ctx context.Context, prefix string) (map[string][]byte, error) {
	rows, err := r.q.Query(ctx, `SELECT key, value FROM ops_control WHERE left(key, length($1::text)) = $1 ORDER BY key`, prefix)
	if err != nil {
		return nil, mapError(err)
	}
	out := map[string][]byte{}
	_, err = collect(rows, func(rows pgx.Rows) (struct{}, error) {
		var (
			key   string
			value []byte
		)
		if err := rows.Scan(&key, &value); err != nil {
			return struct{}{}, err
		}
		out[key] = value
		return struct{}{}, nil
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}

// BeginIdempotent claims the key; ON CONFLICT DO NOTHING makes the unique
// constraint the arbiter between two concurrent calls. When the key already
// existed the stored record is returned so the caller can compare hashes
// (409 IDEMPOTENCY_CONFLICT) or replay the response.
func (r *OpsRepository) BeginIdempotent(ctx context.Context, key, requestHash string, now time.Time) (*entity.IdempotencyRecord, error) {
	tag, err := r.q.Exec(ctx, `
		INSERT INTO ops_idempotency (key, request_hash, created_at)
		VALUES ($1, $2, $3)
		ON CONFLICT (key) DO NOTHING`, key, requestHash, now)
	if err != nil {
		return nil, mapError(err)
	}
	if tag.RowsAffected() == 1 {
		return nil, nil
	}
	var (
		rec      entity.IdempotencyRecord
		status   *int
		response []byte
	)
	err = r.q.QueryRow(ctx, `SELECT key, request_hash, status, response, created_at FROM ops_idempotency WHERE key = $1`, key).
		Scan(&rec.Key, &rec.RequestHash, &status, &response, &rec.CreatedAt)
	if err != nil {
		return nil, mapError(err)
	}
	if status != nil {
		rec.Status = *status
	}
	if response != nil {
		rec.Response = json.RawMessage(response)
	}
	return &rec, nil
}

func (r *OpsRepository) CompleteIdempotent(ctx context.Context, key string, status int, response []byte) error {
	_, err := r.q.Exec(ctx, `UPDATE ops_idempotency SET status = $2, response = $3 WHERE key = $1`,
		key, status, rawJSONOrNil(response))
	return mapError(err)
}

func (r *OpsRepository) PurgeIdempotent(ctx context.Context, before time.Time) (int64, error) {
	tag, err := r.q.Exec(ctx, `DELETE FROM ops_idempotency WHERE created_at < $1`, before)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

func (r *OpsRepository) InsertAudit(ctx context.Context, e *entity.AuditEntry) error {
	id := e.ID
	if id == uuid.Nil {
		id = uuid.New()
		e.ID = id
	}
	params := e.Params
	if len(params) == 0 {
		params = json.RawMessage(`{}`)
	}
	var createdAt *time.Time
	if !e.CreatedAt.IsZero() {
		createdAt = &e.CreatedAt
	}
	_, err := r.q.Exec(ctx, `
		INSERT INTO ops_audit (id, principal, operation, params, result, created_at)
		VALUES ($1, $2, $3, $4, $5, COALESCE($6::timestamptz, now()))`,
		id, e.Principal, e.Operation, []byte(params), e.Result, createdAt)
	return mapError(err)
}
