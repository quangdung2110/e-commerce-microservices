package pg_adapter

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// PostgresOutboxRepository implements repository.OutboxRepository using PostgreSQL
type PostgresOutboxRepository struct {
	pool *pgxpool.Pool
}

// NewPostgresOutboxRepository creates a new OutboxRepository
func NewPostgresOutboxRepository(pool *pgxpool.Pool) repository.OutboxRepository {
	return &PostgresOutboxRepository{pool: pool}
}

// Create creates a new outbox event
func (r *PostgresOutboxRepository) Create(ctx context.Context, event *entity.OutboxEvent) error {
	query := `
		INSERT INTO outbox_events (
			event_id, event_type, aggregate_type, aggregate_id,
			partition_key, payload, metadata, status, error_message,
			created_at, published_at, retry_count, last_retry_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
	`

	payload, err := json.Marshal(event.Payload)
	if err != nil {
		return err
	}

	metadata, err := json.Marshal(event.Metadata)
	if err != nil {
		return err
	}

	_, err = r.pool.Exec(ctx, query,
		event.ID,
		event.EventType,
		event.AggregateType,
		event.AggregateID,
		event.PartitionKey,
		payload,
		metadata,
		event.Status,
		event.ErrorMessage,
		event.CreatedAt,
		event.PublishedAt,
		event.RetryCount,
		event.LastRetryAt,
	)
	return err
}

// GetByID retrieves an outbox event by ID
func (r *PostgresOutboxRepository) GetByID(ctx context.Context, eventID uuid.UUID) (*entity.OutboxEvent, error) {
	query := `
		SELECT event_id, event_type, aggregate_type, aggregate_id,
			   partition_key, payload, metadata, status, error_message,
			   created_at, published_at, retry_count, last_retry_at
		FROM outbox_events
		WHERE event_id = $1
	`

	var event entity.OutboxEvent
	var payload, metadata []byte

	err := r.pool.QueryRow(ctx, query, eventID).Scan(
		&event.ID,
		&event.EventType,
		&event.AggregateType,
		&event.AggregateID,
		&event.PartitionKey,
		&payload,
		&metadata,
		&event.Status,
		&event.ErrorMessage,
		&event.CreatedAt,
		&event.PublishedAt,
		&event.RetryCount,
		&event.LastRetryAt,
	)
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}

	if err := json.Unmarshal(payload, &event.Payload); err != nil {
		return nil, err
	}
	if err := json.Unmarshal(metadata, &event.Metadata); err != nil {
		return nil, err
	}

	return &event, nil
}

// GetPending retrieves pending events for processing
func (r *PostgresOutboxRepository) GetPending(ctx context.Context, limit int) ([]*entity.OutboxEvent, error) {
	query := `
		SELECT event_id, event_type, aggregate_type, aggregate_id,
			   partition_key, payload, metadata, status, error_message,
			   created_at, published_at, retry_count, last_retry_at
		FROM outbox_events
		WHERE status = $1
		ORDER BY created_at ASC
		LIMIT $2
	`

	rows, err := r.pool.Query(ctx, query, types.OutboxEventStatusPending, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	return r.scanEvents(rows)
}

// MarkAsPublished marks an event as published
func (r *PostgresOutboxRepository) MarkAsPublished(ctx context.Context, eventID uuid.UUID) error {
	query := `
		UPDATE outbox_events
		SET status = $2, published_at = NOW()
		WHERE event_id = $1
	`

	_, err := r.pool.Exec(ctx, query, eventID, types.OutboxEventStatusPublished)
	return err
}

// MarkAsFailed marks an event as failed
func (r *PostgresOutboxRepository) MarkAsFailed(ctx context.Context, eventID uuid.UUID, errorMsg string) error {
	query := `
		UPDATE outbox_events
		SET status = $2, error_message = $3, retry_count = retry_count + 1, last_retry_at = NOW()
		WHERE event_id = $1
	`

	_, err := r.pool.Exec(ctx, query, eventID, types.OutboxEventStatusFailed, errorMsg)
	return err
}

// DeleteOldEvents deletes events older than the specified time
func (r *PostgresOutboxRepository) DeleteOldEvents(ctx context.Context, olderThan time.Time) (int64, error) {
	result, err := r.pool.Exec(ctx, `
		DELETE FROM outbox_events
		WHERE created_at < $1 AND status != 'PENDING'
	`, olderThan)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected(), nil
}

// Delete deletes an event
func (r *PostgresOutboxRepository) Delete(ctx context.Context, eventID uuid.UUID) error {
	_, err := r.pool.Exec(ctx, "DELETE FROM outbox_events WHERE event_id = $1", eventID)
	return err
}

// scanEvents scans rows into outbox event slice
func (r *PostgresOutboxRepository) scanEvents(rows pgx.Rows) ([]*entity.OutboxEvent, error) {
	var events []*entity.OutboxEvent
	for rows.Next() {
		var event entity.OutboxEvent
		var payload, metadata []byte

		err := rows.Scan(
			&event.ID,
			&event.EventType,
			&event.AggregateType,
			&event.AggregateID,
			&event.PartitionKey,
			&payload,
			&metadata,
			&event.Status,
			&event.ErrorMessage,
			&event.CreatedAt,
			&event.PublishedAt,
			&event.RetryCount,
			&event.LastRetryAt,
		)
		if err != nil {
			return nil, err
		}

		if err := json.Unmarshal(payload, &event.Payload); err != nil {
			return nil, err
		}
		if err := json.Unmarshal(metadata, &event.Metadata); err != nil {
			return nil, err
		}

		events = append(events, &event)
	}
	return events, rows.Err()
}
