package postgres

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.DeliveryRepository = (*DeliveryRepository)(nil)

// DeliveryRepository persists notification_delivery and implements the queue
// mechanics of 02-pipeline.md §5: the row is queue, state and retry schedule
// at once. Every method that takes workerID is a compare-and-set on
// `status = 'SENDING' AND locked_by = workerID` (04-idempotency.md lớp C2).
//
// The table is partitioned by created_at and the primary key is
// (id, created_at); every UPDATE therefore joins on both columns so the
// planner prunes to one partition instead of scanning all of them.
type DeliveryRepository struct {
	q     querier
	clock clock.Clock
}

const deliveryColumns = `
	id, request_id, user_id, subject, lane, channel, template, template_version, status,
	attempt, next_attempt_at, lease_until, locked_by, dedup_key, ordering_key, expires_at,
	provider, provider_message_id, possibly_duplicate, last_error_code, last_error, skip_reason,
	source_ref, sent_at, delivered_at, finished_at, created_at, updated_at`

// deliveryColumnsOf prefixes every column with an alias for UPDATE ... RETURNING.
func deliveryColumnsOf(alias string) string {
	cols := strings.Split(deliveryColumns, ",")
	for i, c := range cols {
		cols[i] = alias + "." + strings.TrimSpace(c)
	}
	return strings.Join(cols, ", ")
}

func (r *DeliveryRepository) now() time.Time {
	if r.clock == nil {
		return time.Now().UTC()
	}
	return r.clock.Now()
}

func (r *DeliveryRepository) Insert(ctx context.Context, d *entity.Delivery) error {
	const query = `
		INSERT INTO notification_delivery (` + deliveryColumns + `)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16,
		        $17, $18, $19, $20, $21, $22, $23, $24, $25, $26, $27, $28)`
	_, err := r.q.Exec(ctx, query,
		d.ID, d.RequestID, d.UserID, d.Subject, string(d.Lane), string(d.Channel), d.Template,
		d.TemplateVersion, string(d.Status), d.Attempt, d.NextAttemptAt, d.LeaseUntil, d.LockedBy,
		d.DedupKey, d.OrderingKey, d.ExpiresAt, providerOrNil(d.Provider), d.ProviderMessageID,
		d.PossiblyDuplicate, d.LastErrorCode, d.LastError, d.SkipReason, d.SourceRef,
		d.SentAt, d.DeliveredAt, d.FinishedAt, d.CreatedAt, d.UpdatedAt,
	)
	return mapError(err)
}

func (r *DeliveryRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Delivery, error) {
	return r.one(ctx, `SELECT `+deliveryColumns+` FROM notification_delivery WHERE id = $1`, id)
}

func (r *DeliveryRepository) ListByRequest(ctx context.Context, requestID uuid.UUID) ([]entity.Delivery, error) {
	return r.many(ctx, `SELECT `+deliveryColumns+` FROM notification_delivery WHERE request_id = $1 ORDER BY channel`, requestID)
}

// ListBySubject is the status timeline of an aggregate (10-interfaces.md §2),
// newest first; template narrows it when non-empty.
func (r *DeliveryRepository) ListBySubject(ctx context.Context, subject string, template string, limit int) ([]entity.Delivery, error) {
	if limit <= 0 {
		limit = 50
	}
	return r.many(ctx, `
		SELECT `+deliveryColumns+`
		FROM notification_delivery
		WHERE subject = $1 AND ($2::text = '' OR template = $2)
		ORDER BY created_at DESC, id DESC
		LIMIT $3`, subject, template, limit)
}

// FindByProviderMessage is the Track correlation (03-tracking.md §3); it hits
// idx_delivery_provider_msg, a partial index on provider_message_id IS NOT NULL.
func (r *DeliveryRepository) FindByProviderMessage(ctx context.Context, provider entity.Provider, messageID string) (*entity.Delivery, error) {
	return r.one(ctx, `
		SELECT `+deliveryColumns+`
		FROM notification_delivery
		WHERE provider = $1 AND provider_message_id = $2
		ORDER BY created_at DESC
		LIMIT 1`, string(provider), messageID)
}

// Claim is the pick-up statement of 02-pipeline.md §5.2 verbatim: SELECT ...
// FOR UPDATE SKIP LOCKED chooses, UPDATE ... RETURNING marks — one atomic
// statement, no window between "I see this row" and "I hold this row".
// attempt is incremented here; a row that is claimed but not really tried
// must go back through Release so the increment is undone.
func (r *DeliveryRepository) Claim(ctx context.Context, lane entity.Lane, channel entity.Channel, workerID string, batch int, lease time.Duration) ([]entity.Delivery, error) {
	if batch <= 0 {
		return nil, nil
	}
	now := r.now()
	query := `
		WITH picked AS (
			SELECT id, created_at
			FROM   notification_delivery
			WHERE  lane = $1
			  AND  channel = $2
			  AND  status IN ('PENDING', 'RETRY')
			  AND  next_attempt_at <= $3
			ORDER  BY next_attempt_at
			LIMIT  $4
			FOR UPDATE SKIP LOCKED
		)
		UPDATE notification_delivery d
		SET    status      = 'SENDING',
		       lease_until = $5,
		       locked_by   = $6,
		       attempt     = d.attempt + 1,
		       updated_at  = $3
		FROM   picked
		WHERE  d.id = picked.id AND d.created_at = picked.created_at
		RETURNING ` + deliveryColumnsOf("d")
	return r.many(ctx, query, string(lane), string(channel), now, batch, now.Add(lease), workerID)
}

// ExtendLease is the heartbeat (06-backpressure.md §6): only rows this worker
// still holds are touched, so a row already reaped and re-claimed elsewhere is
// left alone.
func (r *DeliveryRepository) ExtendLease(ctx context.Context, ids []uuid.UUID, workerID string, lease time.Duration) error {
	if len(ids) == 0 {
		return nil
	}
	now := r.now()
	const query = `
		UPDATE notification_delivery
		SET    lease_until = $3, updated_at = $4
		WHERE  id = ANY($1) AND status = 'SENDING' AND locked_by = $2`
	_, err := r.q.Exec(ctx, query, ids, workerID, now.Add(lease), now)
	return mapError(err)
}

// Release puts a claimed row back without counting the attempt (breaker
// open, no rate-limit token, lane paused).
func (r *DeliveryRepository) Release(ctx context.Context, id uuid.UUID, workerID string, nextAttemptAt time.Time, errorCode string) (bool, error) {
	const query = `
		UPDATE notification_delivery
		SET    status          = 'RETRY',
		       attempt         = GREATEST(attempt - 1, 0),
		       next_attempt_at = $3,
		       last_error_code = $4,
		       lease_until     = NULL,
		       locked_by       = NULL,
		       updated_at      = $5
		WHERE  id = $1 AND status = 'SENDING' AND locked_by = $2`
	return r.cas(ctx, query, id, workerID, nextAttemptAt, nullString(errorCode), r.now())
}

func (r *DeliveryRepository) MarkSent(ctx context.Context, id uuid.UUID, workerID string, provider entity.Provider, providerMessageID, templateVersion string, sentAt time.Time, possiblyDuplicate bool) (bool, error) {
	const query = `
		UPDATE notification_delivery
		SET    status              = 'SENT',
		       provider            = $3,
		       provider_message_id = $4,
		       template_version    = $5,
		       sent_at             = $6,
		       possibly_duplicate  = $7,
		       lease_until         = NULL,
		       locked_by           = NULL,
		       updated_at          = $8
		WHERE  id = $1 AND status = 'SENDING' AND locked_by = $2`
	return r.cas(ctx, query, id, workerID, string(provider), nullString(providerMessageID),
		nullString(templateVersion), sentAt, possiblyDuplicate, r.now())
}

func (r *DeliveryRepository) MarkRetry(ctx context.Context, id uuid.UUID, workerID string, nextAttemptAt time.Time, errorCode, errorMsg string) (bool, error) {
	const query = `
		UPDATE notification_delivery
		SET    status          = 'RETRY',
		       next_attempt_at = $3,
		       last_error_code = $4,
		       last_error      = $5,
		       lease_until     = NULL,
		       locked_by       = NULL,
		       updated_at      = $6
		WHERE  id = $1 AND status = 'SENDING' AND locked_by = $2`
	return r.cas(ctx, query, id, workerID, nextAttemptAt, nullString(errorCode), nullString(errorMsg), r.now())
}

// MarkFinal moves a SENDING row held by workerID to a terminal status.
// FAILED/EXPIRED record reasonCode in last_error_code (it becomes reason_code
// of notification.failed.v1); SKIPPED records it in skip_reason.
func (r *DeliveryRepository) MarkFinal(ctx context.Context, id uuid.UUID, workerID string, status entity.DeliveryStatus, reasonCode, detail string, at time.Time) (bool, error) {
	var errCode, errMsg, skip *string
	switch status {
	case entity.StatusFailed, entity.StatusExpired:
		errCode, errMsg = nullString(reasonCode), nullString(detail)
	case entity.StatusSkipped:
		skip, errMsg = nullString(reasonCode), nullString(detail)
	default:
		return false, apperr.Wrap(apperr.CodeValidationFailed, fmt.Sprintf("MarkFinal: %s is not a terminal status", status), nil)
	}
	const query = `
		UPDATE notification_delivery
		SET    status          = $3,
		       last_error_code = COALESCE($4, last_error_code),
		       last_error      = COALESCE($5, last_error),
		       skip_reason     = COALESCE($6, skip_reason),
		       finished_at     = $7,
		       lease_until     = NULL,
		       locked_by       = NULL,
		       updated_at      = $8
		WHERE  id = $1 AND status = 'SENDING' AND locked_by = $2`
	return r.cas(ctx, query, id, workerID, string(status), errCode, errMsg, skip, at, r.now())
}

// MarkDeliveredInApp finishes an in_app row in one step: writing the inbox
// row is a local transaction, so there is no SENT/DELIVERED gap to track.
func (r *DeliveryRepository) MarkDeliveredInApp(ctx context.Context, id uuid.UUID, workerID string, templateVersion string, at time.Time) (bool, error) {
	const query = `
		UPDATE notification_delivery
		SET    status           = 'DELIVERED',
		       provider         = 'inbox',
		       template_version = $3,
		       sent_at          = $4,
		       delivered_at     = $4,
		       finished_at      = $4,
		       lease_until      = NULL,
		       locked_by        = NULL,
		       updated_at       = $5
		WHERE  id = $1 AND status = 'SENDING' AND locked_by = $2`
	return r.cas(ctx, query, id, workerID, nullString(templateVersion), at, r.now())
}

// ReapExpiredLeases is the reaper of 02-pipeline.md §5.4. The row keeps
// attempt as incremented by Claim and carries lease_expired so the next
// claimant applies the layer-C3 lane policy (04-idempotency.md §3).
func (r *DeliveryRepository) ReapExpiredLeases(ctx context.Context, now time.Time) (int64, error) {
	const query = `
		UPDATE notification_delivery
		SET    status          = 'RETRY',
		       next_attempt_at = $1,
		       last_error_code = 'lease_expired',
		       last_error      = 'lease expired (worker ' || COALESCE(locked_by, '?') || ')',
		       lease_until     = NULL,
		       locked_by       = NULL,
		       updated_at      = $1
		WHERE  status = 'SENDING' AND lease_until < $1`
	tag, err := r.q.Exec(ctx, query, now)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// TransitionTracked applies a provider-reported status by rank
// (03-tracking.md §5.1): SENT(1) < DELIVERED(2) < BOUNCED(3); every other
// status ranks 0, so a late webhook recovers a FAILED(lease_expired)/RETRY/
// SENDING row (04-idempotency.md §4 step 7). Equal rank refreshes the
// timestamp only; lower rank is ignored. SKIPPED/EXPIRED rows were never
// handed to a provider, so a webhook for them is noise: never applied.
func (r *DeliveryRepository) TransitionTracked(ctx context.Context, id uuid.UUID, to entity.DeliveryStatus, at time.Time, note string) (bool, error) {
	newRank := to.TrackingRank()
	if newRank == 0 {
		return false, apperr.Wrap(apperr.CodeValidationFailed, fmt.Sprintf("TransitionTracked: %s is not a tracked status", to), nil)
	}
	const query = `
		WITH cur AS (
			SELECT id, created_at, status,
			       CASE status WHEN 'SENT' THEN 1 WHEN 'DELIVERED' THEN 2 WHEN 'BOUNCED' THEN 3 ELSE 0 END AS rank
			FROM   notification_delivery
			WHERE  id = $1 AND status NOT IN ('SKIPPED', 'EXPIRED')
			FOR UPDATE
		)
		UPDATE notification_delivery d
		SET    status       = CASE WHEN $2 > cur.rank THEN $3 ELSE d.status END,
		       sent_at      = CASE WHEN $3 = 'SENT' THEN $4
		                           WHEN $2 > cur.rank THEN COALESCE(d.sent_at, $4)
		                           ELSE d.sent_at END,
		       delivered_at = CASE WHEN $3 = 'DELIVERED' THEN $4 ELSE d.delivered_at END,
		       finished_at  = CASE WHEN $3 IN ('DELIVERED', 'BOUNCED') THEN $4 ELSE d.finished_at END,
		       last_error   = CASE WHEN $2 > cur.rank AND $5 <> '' THEN concat_ws('; ', d.last_error, $5)
		                           ELSE d.last_error END,
		       lease_until  = CASE WHEN $2 > cur.rank THEN NULL ELSE d.lease_until END,
		       locked_by    = CASE WHEN $2 > cur.rank THEN NULL ELSE d.locked_by END,
		       updated_at   = $6
		FROM   cur
		WHERE  d.id = cur.id AND d.created_at = cur.created_at AND $2 >= cur.rank
		RETURNING cur.status, d.status`
	var before, after string
	err := r.q.QueryRow(ctx, query, id, newRank, string(to), at, note, r.now()).Scan(&before, &after)
	if err == nil {
		return before != after, nil
	}
	if err != pgx.ErrNoRows {
		return false, mapError(err)
	}
	// 0 rows: absent, terminal-by-us (SKIPPED/EXPIRED) or a lower rank.
	if _, getErr := r.GetByID(ctx, id); getErr != nil {
		return false, getErr
	}
	return false, nil
}

// Redrive moves FAILED/EXPIRED → PENDING with attempt 0 (ops API). last_error*
// is kept on purpose: the timeline must still show why it failed the first
// time.
func (r *DeliveryRepository) Redrive(ctx context.Context, id uuid.UUID, newExpiresAt *time.Time, now time.Time) (*entity.Delivery, error) {
	query := `
		UPDATE notification_delivery
		SET    status          = 'PENDING',
		       attempt         = 0,
		       next_attempt_at = $2,
		       finished_at     = NULL,
		       lease_until     = NULL,
		       locked_by       = NULL,
		       expires_at      = COALESCE($3, expires_at),
		       updated_at      = $2
		WHERE  id = $1
		  AND  status IN ('FAILED', 'EXPIRED')
		  AND  ($3::timestamptz IS NOT NULL OR expires_at > $2)
		RETURNING ` + deliveryColumns
	d, err := r.one(ctx, query, id, now, newExpiresAt)
	if err == nil {
		return d, nil
	}
	if !apperr.Is(err, apperr.ErrNotFound) {
		return nil, err
	}
	// Nothing matched: find out which precondition failed.
	cur, err := r.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if cur.Status != entity.StatusFailed && cur.Status != entity.StatusExpired {
		return nil, apperr.ErrStateConflict
	}
	return nil, apperr.ErrValidation.WithFields(apperr.Field{Field: "expires_at", Message: "delivery is past expires_at; provide a new expires_at"})
}

// redriveWhere builds the FAILED-row filter shared by CountRedrivable and
// RedriveBatch. Producer-contract reasons are always excluded: the payload
// itself is wrong, resending changes nothing (R-04).
func redriveWhere(f entity.RedriveFilter, now time.Time) (string, []any) {
	contract := make([]string, 0, len(entity.ProducerContractReasons))
	for code := range entity.ProducerContractReasons {
		contract = append(contract, code)
	}
	args := []any{string(f.Lane), contract}
	clauses := []string{
		"status = 'FAILED'",
		"lane = $1",
		"(last_error_code IS NULL OR last_error_code <> ALL($2::text[]))",
	}
	add := func(clause string, v any) {
		args = append(args, v)
		clauses = append(clauses, fmt.Sprintf(clause, len(args)))
	}
	if f.ReasonCode != "" {
		add("last_error_code = $%d", f.ReasonCode)
	}
	if f.FailedAfter != nil {
		add("finished_at >= $%d", *f.FailedAfter)
	}
	if f.FailedBefore != nil {
		add("finished_at < $%d", *f.FailedBefore)
	}
	if f.ExpiresAt == nil {
		// No override: rows already past their expiry would only be claimed
		// and marked EXPIRED again.
		add("expires_at > $%d", now)
	}
	return strings.Join(clauses, " AND "), args
}

func (r *DeliveryRepository) CountRedrivable(ctx context.Context, f entity.RedriveFilter) (int64, error) {
	where, args := redriveWhere(f, r.now())
	var n int64
	err := r.q.QueryRow(ctx, `SELECT count(*) FROM notification_delivery WHERE `+where, args...).Scan(&n)
	if err != nil {
		return 0, mapError(err)
	}
	return n, nil
}

func (r *DeliveryRepository) RedriveBatch(ctx context.Context, f entity.RedriveFilter, now time.Time) (int64, error) {
	limit := f.Limit
	if limit <= 0 {
		limit = 1000
	}
	where, args := redriveWhere(f, now)
	args = append(args, limit, now, f.ExpiresAt)
	query := fmt.Sprintf(`
		WITH picked AS (
			SELECT id, created_at
			FROM   notification_delivery
			WHERE  %s
			ORDER  BY finished_at
			LIMIT  $%d
			FOR UPDATE SKIP LOCKED
		)
		UPDATE notification_delivery d
		SET    status          = 'PENDING',
		       attempt         = 0,
		       next_attempt_at = $%d,
		       finished_at     = NULL,
		       lease_until     = NULL,
		       locked_by       = NULL,
		       expires_at      = COALESCE($%d::timestamptz, d.expires_at),
		       updated_at      = $%d
		FROM   picked
		WHERE  d.id = picked.id AND d.created_at = picked.created_at`,
		where, len(args)-2, len(args)-1, len(args), len(args)-1)
	tag, err := r.q.Exec(ctx, query, args...)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// SkipPendingByUser is the user.deleted rule of 09-data-model.md §10.
func (r *DeliveryRepository) SkipPendingByUser(ctx context.Context, userID uuid.UUID, reason string, now time.Time) (int64, error) {
	const query = `
		UPDATE notification_delivery
		SET    status      = 'SKIPPED',
		       skip_reason = $2,
		       finished_at = $3,
		       lease_until = NULL,
		       locked_by   = NULL,
		       updated_at  = $3
		WHERE  user_id = $1 AND status IN ('PENDING', 'RETRY')`
	tag, err := r.q.Exec(ctx, query, userID, reason, now)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

func (r *DeliveryRepository) AnonymizeByUser(ctx context.Context, userID uuid.UUID) (int64, error) {
	tag, err := r.q.Exec(ctx, `UPDATE notification_delivery SET user_id = NULL, subject = NULL WHERE user_id = $1`, userID)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// PendingStats feeds the queue gauges (14-observability.md §2.2): one grouped
// query over idx_delivery_ready, zero-filled for every lane × channel so a
// gauge never goes stale when a queue drains.
func (r *DeliveryRepository) PendingStats(ctx context.Context, now time.Time) ([]entity.PendingStat, error) {
	const query = `
		SELECT lane, channel,
		       count(*) FILTER (WHERE next_attempt_at <= $1),
		       count(*) FILTER (WHERE next_attempt_at >  $1),
		       min(next_attempt_at) FILTER (WHERE next_attempt_at <= $1)
		FROM   notification_delivery
		WHERE  status IN ('PENDING', 'RETRY')
		GROUP  BY lane, channel`
	rows, err := r.q.Query(ctx, query, now)
	if err != nil {
		return nil, mapError(err)
	}
	type key struct {
		lane    entity.Lane
		channel entity.Channel
	}
	found := map[key]entity.PendingStat{}
	_, err = collect(rows, func(rows pgx.Rows) (struct{}, error) {
		var (
			lane, channel string
			ready, sched  int64
			oldest        *time.Time
		)
		if err := rows.Scan(&lane, &channel, &ready, &sched, &oldest); err != nil {
			return struct{}{}, err
		}
		st := entity.PendingStat{Lane: entity.Lane(lane), Channel: entity.Channel(channel), ReadyCount: ready, ScheduledCount: sched}
		if oldest != nil {
			st.OldestReadyAge = now.Sub(*oldest)
		}
		found[key{st.Lane, st.Channel}] = st
		return struct{}{}, nil
	})
	if err != nil {
		return nil, err
	}
	out := make([]entity.PendingStat, 0, len(entity.AllLanes)*len(entity.AllChannels))
	for _, lane := range entity.AllLanes {
		for _, ch := range entity.AllChannels {
			if st, ok := found[key{lane, ch}]; ok {
				out = append(out, st)
				continue
			}
			out = append(out, entity.PendingStat{Lane: lane, Channel: ch})
		}
	}
	return out, nil
}

// ---- helpers ----------------------------------------------------------------

// cas runs a compare-and-set UPDATE and reports whether the row was ours.
func (r *DeliveryRepository) cas(ctx context.Context, query string, args ...any) (bool, error) {
	tag, err := r.q.Exec(ctx, query, args...)
	if err != nil {
		return false, mapError(err)
	}
	return tag.RowsAffected() == 1, nil
}

func (r *DeliveryRepository) one(ctx context.Context, query string, args ...any) (*entity.Delivery, error) {
	list, err := r.many(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, mapError(pgx.ErrNoRows)
	}
	return &list[0], nil
}

func (r *DeliveryRepository) many(ctx context.Context, query string, args ...any) ([]entity.Delivery, error) {
	rows, err := r.q.Query(ctx, query, args...)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, scanDelivery)
}

func scanDelivery(rows pgx.Rows) (entity.Delivery, error) {
	var (
		d                     entity.Delivery
		lane, channel, status string
		provider              *string
	)
	err := rows.Scan(
		&d.ID, &d.RequestID, &d.UserID, &d.Subject, &lane, &channel, &d.Template, &d.TemplateVersion, &status,
		&d.Attempt, &d.NextAttemptAt, &d.LeaseUntil, &d.LockedBy, &d.DedupKey, &d.OrderingKey, &d.ExpiresAt,
		&provider, &d.ProviderMessageID, &d.PossiblyDuplicate, &d.LastErrorCode, &d.LastError, &d.SkipReason,
		&d.SourceRef, &d.SentAt, &d.DeliveredAt, &d.FinishedAt, &d.CreatedAt, &d.UpdatedAt,
	)
	if err != nil {
		return d, err
	}
	d.Lane = entity.Lane(lane)
	d.Channel = entity.Channel(channel)
	d.Status = entity.DeliveryStatus(status)
	if provider != nil {
		p := entity.Provider(*provider)
		d.Provider = &p
	}
	return d, nil
}

func providerOrNil(p *entity.Provider) *string {
	if p == nil {
		return nil
	}
	s := string(*p)
	return &s
}
