package pg_adapter

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// PostgresAuditRepository implements repository.AuditRepository using PostgreSQL
type PostgresAuditRepository struct {
	pool *pgxpool.Pool
}

// NewPostgresAuditRepository creates a new AuditRepository
func NewPostgresAuditRepository(pool *pgxpool.Pool, logger *zap.Logger) repository.AuditRepository {
	return &PostgresAuditRepository{pool: pool}
}

// Create creates a new audit log entry
func (r *PostgresAuditRepository) Create(ctx context.Context, log *entity.AuditLog) error {
	query := `
		INSERT INTO audit_logs (
			id, actor_id, actor_type, event_type, occurred_at, ip_address,
			device_id, device_info, security_level, payload, log_category,
			request_id, service_name, created_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
	`

	_, err := r.pool.Exec(ctx, query,
		log.ID,
		log.ActorID,
		string(log.ActorType),
		string(log.EventType),
		pgtype.Timestamptz{Time: log.OccurredAt, Valid: true},
		nullString(log.IPAddress),
		nullString(log.DeviceID),
		nullString(log.DeviceInfo),
		string(log.SecurityLevel),
		log.Payload,
		string(log.LogCategory),
		nullString(log.RequestID),
		log.ServiceName,
		pgtype.Timestamptz{Time: log.CreatedAt, Valid: true},
	)
	return err
}

// GetByID retrieves an audit log by ID
func (r *PostgresAuditRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.AuditLog, error) {
	query := `
		SELECT id, actor_id, actor_type, event_type, occurred_at, ip_address,
		       device_id, device_info, security_level, payload, log_category,
		       request_id, service_name, created_at
		FROM audit_logs
		WHERE id = $1
	`

	var log entity.AuditLog
	var actorType, eventType, securityLevel, logCategory string

	err := r.pool.QueryRow(ctx, query, id).Scan(
		&log.ID,
		&log.ActorID,
		&actorType,
		&eventType,
		&log.OccurredAt,
		&log.IPAddress,
		&log.DeviceID,
		&log.DeviceInfo,
		&securityLevel,
		&log.Payload,
		&logCategory,
		&log.RequestID,
		&log.ServiceName,
		&log.CreatedAt,
	)
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}

	log.ActorType = types.ActorType(actorType)
	log.EventType = types.EventType(eventType)
	log.SecurityLevel = types.SecurityLevel(securityLevel)
	log.LogCategory = types.LogCategory(logCategory)

	return &log, nil
}

// GetByActorID retrieves audit logs by actor ID with pagination
func (r *PostgresAuditRepository) GetByActorID(ctx context.Context, actorID string, limit, offset int) ([]*entity.AuditLog, error) {
	query := `
		SELECT id, actor_id, actor_type, event_type, occurred_at, ip_address,
		       device_id, device_info, security_level, payload, log_category,
		       request_id, service_name, created_at
		FROM audit_logs
		WHERE actor_id = $1
		ORDER BY occurred_at DESC
		LIMIT $2 OFFSET $3
	`

	return r.queryAuditLogs(ctx, query, actorID, limit, offset)
}

// GetByEventType retrieves audit logs by event type with pagination
func (r *PostgresAuditRepository) GetByEventType(ctx context.Context, eventType types.EventType, limit, offset int) ([]*entity.AuditLog, error) {
	query := `
		SELECT id, actor_id, actor_type, event_type, occurred_at, ip_address,
		       device_id, device_info, security_level, payload, log_category,
		       request_id, service_name, created_at
		FROM audit_logs
		WHERE event_type = $1
		ORDER BY occurred_at DESC
		LIMIT $2 OFFSET $3
	`

	return r.queryAuditLogs(ctx, query, string(eventType), limit, offset)
}

// GetByTimeRange retrieves audit logs within a time range
func (r *PostgresAuditRepository) GetByTimeRange(ctx context.Context, start, end time.Time, limit, offset int) ([]*entity.AuditLog, error) {
	query := `
		SELECT id, actor_id, actor_type, event_type, occurred_at, ip_address,
		       device_id, device_info, security_level, payload, log_category,
		       request_id, service_name, created_at
		FROM audit_logs
		WHERE occurred_at BETWEEN $1 AND $2
		ORDER BY occurred_at DESC
		LIMIT $3 OFFSET $4
	`

	return r.queryAuditLogs(ctx, query, start, end, limit, offset)
}

// GetByActorAndTimeRange retrieves audit logs by actor within time range
func (r *PostgresAuditRepository) GetByActorAndTimeRange(ctx context.Context, actorID string, start, end time.Time, limit, offset int) ([]*entity.AuditLog, error) {
	query := `
		SELECT id, actor_id, actor_type, event_type, occurred_at, ip_address,
		       device_id, device_info, security_level, payload, log_category,
		       request_id, service_name, created_at
		FROM audit_logs
		WHERE actor_id = $1 AND occurred_at BETWEEN $2 AND $3
		ORDER BY occurred_at DESC
		LIMIT $4 OFFSET $5
	`

	return r.queryAuditLogs(ctx, query, actorID, start, end, limit, offset)
}

// GetBySecurityLevel retrieves audit logs by security level
func (r *PostgresAuditRepository) GetBySecurityLevel(ctx context.Context, level types.SecurityLevel, limit, offset int) ([]*entity.AuditLog, error) {
	query := `
		SELECT id, actor_id, actor_type, event_type, occurred_at, ip_address,
		       device_id, device_info, security_level, payload, log_category,
		       request_id, service_name, created_at
		FROM audit_logs
		WHERE security_level = $1
		ORDER BY occurred_at DESC
		LIMIT $2 OFFSET $3
	`

	return r.queryAuditLogs(ctx, query, string(level), limit, offset)
}

// GetByCategory retrieves audit logs by category
func (r *PostgresAuditRepository) GetByCategory(ctx context.Context, category types.LogCategory, limit, offset int) ([]*entity.AuditLog, error) {
	query := `
		SELECT id, actor_id, actor_type, event_type, occurred_at, ip_address,
		       device_id, device_info, security_level, payload, log_category,
		       request_id, service_name, created_at
		FROM audit_logs
		WHERE log_category = $1
		ORDER BY occurred_at DESC
		LIMIT $2 OFFSET $3
	`

	return r.queryAuditLogs(ctx, query, string(category), limit, offset)
}

// Count returns total count of audit logs
func (r *PostgresAuditRepository) Count(ctx context.Context) (int64, error) {
	var count int64
	err := r.pool.QueryRow(ctx, "SELECT COUNT(*) FROM audit_logs").Scan(&count)
	return count, err
}

// CountByActorID returns count of audit logs for an actor
func (r *PostgresAuditRepository) CountByActorID(ctx context.Context, actorID string) (int64, error) {
	var count int64
	err := r.pool.QueryRow(ctx, "SELECT COUNT(*) FROM audit_logs WHERE actor_id = $1", actorID).Scan(&count)
	return count, err
}

// DeleteOldLogs deletes audit logs older than the specified time
func (r *PostgresAuditRepository) DeleteOldLogs(ctx context.Context, olderThan time.Time) (int64, error) {
	result, err := r.pool.Exec(ctx, "DELETE FROM audit_logs WHERE occurred_at < $1", olderThan)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected(), nil
}

// queryAuditLogs is a helper function to query audit logs
func (r *PostgresAuditRepository) queryAuditLogs(ctx context.Context, query string, args ...interface{}) ([]*entity.AuditLog, error) {
	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var logs []*entity.AuditLog
	for rows.Next() {
		var log entity.AuditLog
		var actorType, eventType, securityLevel, logCategory string

		err := rows.Scan(
			&log.ID,
			&log.ActorID,
			&actorType,
			&eventType,
			&log.OccurredAt,
			&log.IPAddress,
			&log.DeviceID,
			&log.DeviceInfo,
			&securityLevel,
			&log.Payload,
			&logCategory,
			&log.RequestID,
			&log.ServiceName,
			&log.CreatedAt,
		)
		if err != nil {
			return nil, err
		}

		log.ActorType = types.ActorType(actorType)
		log.EventType = types.EventType(eventType)
		log.SecurityLevel = types.SecurityLevel(securityLevel)
		log.LogCategory = types.LogCategory(logCategory)

		logs = append(logs, &log)
	}

	return logs, rows.Err()
}

// nullString returns a pointer to string or nil
func nullString(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
