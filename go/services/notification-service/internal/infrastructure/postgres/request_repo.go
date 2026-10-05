package postgres

import (
	"context"
	"encoding/json"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.RequestRepository = (*RequestRepository)(nil)

// RequestRepository persists notification_request (09-data-model.md §3.2).
type RequestRepository struct {
	q querier
}

const requestColumns = `
	id, source, event_id, event_type, event_time, subject, input_kind, template, lane,
	user_id, dedup_key, expires_at, template_data, source_ref, correlation_id, traceparent,
	decision, decision_reason, received_at`

func (r *RequestRepository) Insert(ctx context.Context, req *entity.Request) error {
	const query = `
		INSERT INTO notification_request (` + requestColumns + `)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19)`
	_, err := r.q.Exec(ctx, query,
		req.ID, req.Source, req.EventID, req.EventType, req.EventTime, req.Subject,
		string(req.InputKind), req.Template, laneOrNil(req.Lane),
		req.UserID, req.DedupKey, req.ExpiresAt, rawJSONOrNil(req.TemplateData), req.SourceRef,
		req.CorrelationID, req.TraceParent, string(req.Decision), req.DecisionReason, req.ReceivedAt,
	)
	return mapError(err)
}

// UpdateDecision writes what Decide learnt: decision, reason, the effective
// expiry, and — for facts — template and lane.
func (r *RequestRepository) UpdateDecision(ctx context.Context, req *entity.Request) error {
	const query = `
		UPDATE notification_request
		SET decision = $2, decision_reason = $3, expires_at = $4, template = $5, lane = $6, user_id = $7
		WHERE id = $1 AND received_at = $8`
	tag, err := r.q.Exec(ctx, query, req.ID, string(req.Decision), req.DecisionReason, req.ExpiresAt,
		req.Template, laneOrNil(req.Lane), req.UserID, req.ReceivedAt)
	if err != nil {
		return mapError(err)
	}
	if tag.RowsAffected() == 0 {
		return mapError(pgx.ErrNoRows)
	}
	return nil
}

func (r *RequestRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Request, error) {
	const query = `SELECT ` + requestColumns + ` FROM notification_request WHERE id = $1`
	rows, err := r.q.Query(ctx, query, id)
	if err != nil {
		return nil, mapError(err)
	}
	list, err := collect(rows, scanRequest)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, mapError(pgx.ErrNoRows)
	}
	return &list[0], nil
}

// AnonymizeByUser keeps decision and timestamps for metrics/audit and drops
// what identifies the person (11-privacy.md §4, 09 §10).
func (r *RequestRepository) AnonymizeByUser(ctx context.Context, userID uuid.UUID) (int64, error) {
	const query = `
		UPDATE notification_request
		SET user_id = NULL, template_data = NULL, subject = NULL
		WHERE user_id = $1`
	tag, err := r.q.Exec(ctx, query, userID)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

func scanRequest(rows pgx.Rows) (entity.Request, error) {
	var (
		req      entity.Request
		kind     string
		lane     *string
		decision string
		data     []byte
	)
	err := rows.Scan(
		&req.ID, &req.Source, &req.EventID, &req.EventType, &req.EventTime, &req.Subject, &kind,
		&req.Template, &lane, &req.UserID, &req.DedupKey, &req.ExpiresAt, &data, &req.SourceRef,
		&req.CorrelationID, &req.TraceParent, &decision, &req.DecisionReason, &req.ReceivedAt,
	)
	if err != nil {
		return req, err
	}
	req.InputKind = entity.InputKind(kind)
	req.Decision = entity.Decision(decision)
	if lane != nil {
		l := entity.Lane(*lane)
		req.Lane = &l
	}
	if data != nil {
		req.TemplateData = json.RawMessage(data)
	}
	return req, nil
}

func laneOrNil(l *entity.Lane) *string {
	if l == nil {
		return nil
	}
	s := string(*l)
	return &s
}

// rawJSONOrNil maps a nil RawMessage to SQL NULL; pgx would otherwise send an
// empty string that jsonb rejects.
func rawJSONOrNil(raw json.RawMessage) any {
	if len(raw) == 0 {
		return nil
	}
	return []byte(raw)
}
