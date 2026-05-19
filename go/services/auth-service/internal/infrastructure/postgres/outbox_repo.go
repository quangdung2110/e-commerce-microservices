package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ecommerce/auth-service/internal/domain/entity"
)

type OutboxRepository struct {
	pool *pgxpool.Pool
}

func NewOutboxRepository(pool *pgxpool.Pool) *OutboxRepository {
	return &OutboxRepository{pool: pool}
}

func (r *OutboxRepository) Create(ctx context.Context, event *entity.OutboxEvent) error {
	sql := `
		INSERT INTO outbox_events (event_id, event_type, aggregate_type, aggregate_id,
		                           partition_key, payload, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
	`

	_, err := r.pool.Exec(ctx, sql,
		event.EventID,
		event.EventType,
		event.AggregateType,
		event.AggregateID,
		event.PartitionKey,
		event.Payload,
		event.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to create outbox event: %w", err)
	}

	return nil
}

func (r *OutboxRepository) GetPending(ctx context.Context, limit int) ([]*entity.OutboxEvent, error) {
	sql := `
		SELECT event_id, event_type, aggregate_type, aggregate_id,
		       partition_key, payload, created_at
		FROM outbox_events
		ORDER BY created_at ASC
		LIMIT $1
	`

	rows, err := r.pool.Query(ctx, sql, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to get pending outbox events: %w", err)
	}
	defer rows.Close()

	var events []*entity.OutboxEvent
	for rows.Next() {
		var event entity.OutboxEvent
		err := rows.Scan(
			&event.EventID,
			&event.EventType,
			&event.AggregateType,
			&event.AggregateID,
			&event.PartitionKey,
			&event.Payload,
			&event.CreatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan outbox event: %w", err)
		}
		events = append(events, &event)
	}

	if events == nil {
		events = []*entity.OutboxEvent{}
	}

	return events, nil
}

func (r *OutboxRepository) Delete(ctx context.Context, eventID string) error {
	sql := `DELETE FROM outbox_events WHERE event_id = $1`

	result, err := r.pool.Exec(ctx, sql, eventID)
	if err != nil {
		return fmt.Errorf("failed to delete outbox event: %w", err)
	}

	if result.RowsAffected() == 0 {
		return ErrNotFound
	}

	return nil
}

func (r *OutboxRepository) DeleteOld(ctx context.Context, olderThan time.Time) (int64, error) {
	sql := `DELETE FROM outbox_events WHERE created_at < $1`

	result, err := r.pool.Exec(ctx, sql, olderThan)
	if err != nil {
		return 0, fmt.Errorf("failed to delete old outbox events: %w", err)
	}

	return result.RowsAffected(), nil
}

var _ = errors.Is
