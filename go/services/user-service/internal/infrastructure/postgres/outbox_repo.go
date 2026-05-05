package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"user-service/internal/domain/entity"
)

// PostgresOutboxRepository implements port.OutboxRepository
type PostgresOutboxRepository struct {
	db *pgxpool.Pool
}

// NewPostgresOutboxRepository creates a new PostgresOutboxRepository
func NewPostgresOutboxRepository(db *pgxpool.Pool) *PostgresOutboxRepository {
	return &PostgresOutboxRepository{db: db}
}

// Create creates a new outbox event
func (r *PostgresOutboxRepository) Create(ctx context.Context, event *entity.OutboxEvent) error {
	query := `
		INSERT INTO outbox_events (event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)`

	payloadJSON, err := json.Marshal(event.Payload)
	if err != nil {
		return fmt.Errorf("failed to marshal payload: %w", err)
	}

	_, err = r.db.Exec(ctx, query,
		event.EventID,
		event.EventType,
		event.AggregateType,
		event.AggregateID,
		event.PartitionKey,
		payloadJSON,
		event.CreatedAt,
	)

	if err != nil {
		return fmt.Errorf("failed to create outbox event: %w", err)
	}

	return nil
}

// CreateTx creates an outbox event within a transaction
func (r *PostgresOutboxRepository) CreateTx(ctx context.Context, tx pgx.Tx, event *entity.OutboxEvent) error {
	query := `
		INSERT INTO outbox_events (event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)`

	payloadJSON, err := json.Marshal(event.Payload)
	if err != nil {
		return fmt.Errorf("failed to marshal payload: %w", err)
	}

	_, err = tx.Exec(ctx, query,
		event.EventID,
		event.EventType,
		event.AggregateType,
		event.AggregateID,
		event.PartitionKey,
		payloadJSON,
		event.CreatedAt,
	)

	if err != nil {
		return fmt.Errorf("failed to create outbox event in transaction: %w", err)
	}

	return nil
}

// GetPendingEvents retrieves pending events for processing
// This is used by the outbox processor (not Debezium) for custom processing
func (r *PostgresOutboxRepository) GetPendingEvents(ctx context.Context, limit int) ([]*entity.OutboxEvent, error) {
	query := `
		SELECT event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at
		FROM outbox_events
		ORDER BY created_at ASC
		LIMIT $1`

	rows, err := r.db.Query(ctx, query, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to get pending events: %w", err)
	}
	defer rows.Close()

	var events []*entity.OutboxEvent
	for rows.Next() {
		var event entity.OutboxEvent
		var payloadJSON []byte

		err := rows.Scan(
			&event.EventID,
			&event.EventType,
			&event.AggregateType,
			&event.AggregateID,
			&event.PartitionKey,
			&payloadJSON,
			&event.CreatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan outbox event: %w", err)
		}

		event.Payload = payloadJSON
		events = append(events, &event)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows iteration error: %w", err)
	}

	return events, nil
}

// DeleteOldEvents deletes events older than the specified time
func (r *PostgresOutboxRepository) DeleteOldEvents(ctx context.Context, olderThan time.Time) (int64, error) {
	query := `DELETE FROM outbox_events WHERE created_at < $1`

	result, err := r.db.Exec(ctx, query, olderThan)
	if err != nil {
		return 0, fmt.Errorf("failed to delete old events: %w", err)
	}

	return result.RowsAffected(), nil
}

// GetByID retrieves an outbox event by its ID
func (r *PostgresOutboxRepository) GetByID(ctx context.Context, eventID uuid.UUID) (*entity.OutboxEvent, error) {
	query := `
		SELECT event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at
		FROM outbox_events
		WHERE event_id = $1`

	var event entity.OutboxEvent
	var payloadJSON []byte

	err := r.db.QueryRow(ctx, query, eventID).Scan(
		&event.EventID,
		&event.EventType,
		&event.AggregateType,
		&event.AggregateID,
		&event.PartitionKey,
		&payloadJSON,
		&event.CreatedAt,
	)

	if err == pgx.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get outbox event: %w", err)
	}

	event.Payload = payloadJSON
	return &event, nil
}

// GetEventsByAggregateID retrieves events for a specific aggregate
func (r *PostgresOutboxRepository) GetEventsByAggregateID(ctx context.Context, aggregateID uuid.UUID) ([]*entity.OutboxEvent, error) {
	query := `
		SELECT event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at
		FROM outbox_events
		WHERE aggregate_id = $1
		ORDER BY created_at ASC`

	rows, err := r.db.Query(ctx, query, aggregateID)
	if err != nil {
		return nil, fmt.Errorf("failed to get events by aggregate: %w", err)
	}
	defer rows.Close()

	var events []*entity.OutboxEvent
	for rows.Next() {
		var event entity.OutboxEvent
		var payloadJSON []byte

		err := rows.Scan(
			&event.EventID,
			&event.EventType,
			&event.AggregateType,
			&event.AggregateID,
			&event.PartitionKey,
			&payloadJSON,
			&event.CreatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan outbox event: %w", err)
		}

		event.Payload = payloadJSON
		events = append(events, &event)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows iteration error: %w", err)
	}

	return events, nil
}

// GetEventsByType retrieves events by type
func (r *PostgresOutboxRepository) GetEventsByType(ctx context.Context, eventType entity.OutboxEventType, limit int) ([]*entity.OutboxEvent, error) {
	query := `
		SELECT event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at
		FROM outbox_events
		WHERE event_type = $1
		ORDER BY created_at ASC
		LIMIT $2`

	rows, err := r.db.Query(ctx, query, eventType, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to get events by type: %w", err)
	}
	defer rows.Close()

	var events []*entity.OutboxEvent
	for rows.Next() {
		var event entity.OutboxEvent
		var payloadJSON []byte

		err := rows.Scan(
			&event.EventID,
			&event.EventType,
			&event.AggregateType,
			&event.AggregateID,
			&event.PartitionKey,
			&payloadJSON,
			&event.CreatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan outbox event: %w", err)
		}

		event.Payload = payloadJSON
		events = append(events, &event)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows iteration error: %w", err)
	}

	return events, nil
}

// Count returns total number of outbox events
func (r *PostgresOutboxRepository) Count(ctx context.Context) (int64, error) {
	query := `SELECT COUNT(*) FROM outbox_events`

	var count int64
	err := r.db.QueryRow(ctx, query).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("failed to count outbox events: %w", err)
	}

	return count, nil
}

// CountOld returns count of events older than the specified time
func (r *PostgresOutboxRepository) CountOld(ctx context.Context, olderThan time.Time) (int64, error) {
	query := `SELECT COUNT(*) FROM outbox_events WHERE created_at < $1`

	var count int64
	err := r.db.QueryRow(ctx, query, olderThan).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("failed to count old outbox events: %w", err)
	}

	return count, nil
}
