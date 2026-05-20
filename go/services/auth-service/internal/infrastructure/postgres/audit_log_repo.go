package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

type AuditLogRepository struct {
	pool *pgxpool.Pool
}

func NewAuditLogRepository(pool *pgxpool.Pool) *AuditLogRepository {
	return &AuditLogRepository{pool: pool}
}

func (r *AuditLogRepository) Create(ctx context.Context, log *entity.AuditLog) error {
	sql := `
		INSERT INTO audit_logs (id, actor_id, actor_type, event_type, occurred_at,
		                       ip_address, device_id, device_info, security_level, payload, log_category)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
	`

	_, err := r.pool.Exec(ctx, sql,
		log.ID,
		log.ActorID,
		log.ActorType,
		log.EventType,
		log.OccurredAt,
		log.IPAddress,
		log.DeviceID,
		log.DeviceInfo,
		log.SecurityLevel,
		log.Payload,
		log.LogCategory,
	)
	if err != nil {
		return fmt.Errorf("failed to create audit log: %w", err)
	}

	return nil
}

func (r *AuditLogRepository) GetByActorID(ctx context.Context, actorID string, limit, offset int) ([]*entity.AuditLog, error) {
	sql := `
		SELECT id, actor_id, actor_type, event_type, occurred_at,
		       ip_address, device_id, device_info, security_level, payload, log_category
		FROM audit_logs
		WHERE actor_id = $1
		ORDER BY occurred_at DESC
		LIMIT $2 OFFSET $3
	`

	rows, err := r.pool.Query(ctx, sql, actorID, limit, offset)
	if err != nil {
		return nil, fmt.Errorf("failed to get audit logs by actor: %w", err)
	}
	defer rows.Close()

	return r.scanAuditLogs(rows)
}

func (r *AuditLogRepository) GetByEventType(ctx context.Context, eventType string, limit, offset int) ([]*entity.AuditLog, error) {
	sql := `
		SELECT id, actor_id, actor_type, event_type, occurred_at,
		       ip_address, device_id, device_info, security_level, payload, log_category
		FROM audit_logs
		WHERE event_type = $1
		ORDER BY occurred_at DESC
		LIMIT $2 OFFSET $3
	`

	rows, err := r.pool.Query(ctx, sql, eventType, limit, offset)
	if err != nil {
		return nil, fmt.Errorf("failed to get audit logs by event type: %w", err)
	}
	defer rows.Close()

	return r.scanAuditLogs(rows)
}

func (r *AuditLogRepository) GetByTimeRange(ctx context.Context, from, to time.Time, limit, offset int) ([]*entity.AuditLog, error) {
	sql := `
		SELECT id, actor_id, actor_type, event_type, occurred_at,
		       ip_address, device_id, device_info, security_level, payload, log_category
		FROM audit_logs
		WHERE occurred_at >= $1 AND occurred_at <= $2
		ORDER BY occurred_at DESC
		LIMIT $3 OFFSET $4
	`

	rows, err := r.pool.Query(ctx, sql, from, to, limit, offset)
	if err != nil {
		return nil, fmt.Errorf("failed to get audit logs by time range: %w", err)
	}
	defer rows.Close()

	return r.scanAuditLogs(rows)
}

func (r *AuditLogRepository) GetLoginHistory(ctx context.Context, userID string, limit int) ([]*entity.AuditLog, error) {
	sql := `
		SELECT id, actor_id, actor_type, event_type, occurred_at,
		       ip_address, device_id, device_info, security_level, payload, log_category
		FROM audit_logs
		WHERE actor_id = $1 AND event_type = 'USER_LOGGED_IN'
		ORDER BY occurred_at DESC
		LIMIT $2
	`

	rows, err := r.pool.Query(ctx, sql, userID, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to get login history: %w", err)
	}
	defer rows.Close()

	return r.scanAuditLogs(rows)
}

func (r *AuditLogRepository) GetByFilters(ctx context.Context, filters port.AuditLogFilters, limit, offset int) ([]*entity.AuditLog, int, error) {
	var conditions []string
	var args []interface{}
	argIndex := 1

	if filters.ActorID != "" {
		conditions = append(conditions, fmt.Sprintf("actor_id = $%d", argIndex))
		args = append(args, filters.ActorID)
		argIndex++
	}
	if filters.ActorType != "" {
		conditions = append(conditions, fmt.Sprintf("actor_type = $%d", argIndex))
		args = append(args, filters.ActorType)
		argIndex++
	}
	if filters.EventType != "" {
		conditions = append(conditions, fmt.Sprintf("event_type = $%d", argIndex))
		args = append(args, filters.EventType)
		argIndex++
	}
	if filters.SecurityLevel != "" {
		conditions = append(conditions, fmt.Sprintf("security_level = $%d", argIndex))
		args = append(args, filters.SecurityLevel)
		argIndex++
	}
	if filters.LogCategory != "" {
		conditions = append(conditions, fmt.Sprintf("log_category = $%d", argIndex))
		args = append(args, filters.LogCategory)
		argIndex++
	}
	if !filters.From.IsZero() {
		conditions = append(conditions, fmt.Sprintf("occurred_at >= $%d", argIndex))
		args = append(args, filters.From)
		argIndex++
	}
	if !filters.To.IsZero() {
		conditions = append(conditions, fmt.Sprintf("occurred_at <= $%d", argIndex))
		args = append(args, filters.To)
		argIndex++
	}

	whereClause := ""
	if len(conditions) > 0 {
		whereClause = "WHERE " + strings.Join(conditions, " AND ")
	}

	countSQL := fmt.Sprintf("SELECT COUNT(*) FROM audit_logs %s", whereClause)
	var total int
	err := r.pool.QueryRow(ctx, countSQL, args...).Scan(&total)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to count audit logs: %w", err)
	}

	dataSQL := fmt.Sprintf(`
		SELECT id, actor_id, actor_type, event_type, occurred_at,
		       ip_address, device_id, device_info, security_level, payload, log_category
		FROM audit_logs
		%s
		ORDER BY occurred_at DESC
		LIMIT $%d OFFSET $%d
	`, whereClause, argIndex, argIndex+1)

	args = append(args, limit, offset)

	rows, err := r.pool.Query(ctx, dataSQL, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to get audit logs by filters: %w", err)
	}
	defer rows.Close()

	logs, err := r.scanAuditLogs(rows)
	if err != nil {
		return nil, 0, err
	}

	return logs, total, nil
}

func (r *AuditLogRepository) DeleteOlderThan(ctx context.Context, olderThan time.Time) (int64, error) {
	sql := `DELETE FROM audit_logs WHERE log_category = 'USER' AND occurred_at < $1`

	result, err := r.pool.Exec(ctx, sql, olderThan)
	if err != nil {
		return 0, fmt.Errorf("failed to delete old audit logs: %w", err)
	}

	return result.RowsAffected(), nil
}

func (r *AuditLogRepository) scanAuditLogs(rows interface {
	Next() bool
	Scan(dest ...interface{}) error
}) ([]*entity.AuditLog, error) {
	var logs []*entity.AuditLog

	pgxRows, ok := rows.(interface {
		Next() bool
		Scan(dest ...interface{}) error
	})
	if !ok {
		return nil, fmt.Errorf("invalid rows type")
	}

	for pgxRows.Next() {
		var log entity.AuditLog
		var payload []byte

		err := pgxRows.Scan(
			&log.ID,
			&log.ActorID,
			&log.ActorType,
			&log.EventType,
			&log.OccurredAt,
			&log.IPAddress,
			&log.DeviceID,
			&log.DeviceInfo,
			&log.SecurityLevel,
			&payload,
			&log.LogCategory,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan audit log: %w", err)
		}

		if payload != nil {
			log.Payload = json.RawMessage(payload)
		}

		logs = append(logs, &log)
	}

	if logs == nil {
		logs = []*entity.AuditLog{}
	}

	return logs, nil
}

var _ = errors.Is
