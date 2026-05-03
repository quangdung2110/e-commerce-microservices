package event

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// OutboxPublisher is responsible for inserting events into the outbox table
// within the same transaction as the business logic. This ensures atomicity:
// if the business transaction fails, the event is not inserted.
type OutboxPublisher struct {
	pool   *pgxpool.Pool
	logger *zap.Logger
}

// NewOutboxPublisher creates a new OutboxPublisher
func NewOutboxPublisher(pool *pgxpool.Pool, logger *zap.Logger) *OutboxPublisher {
	return &OutboxPublisher{
		pool:   pool,
		logger: logger,
	}
}

// PublishEvent inserts an event into the outbox table within the same transaction.
// This method MUST be called with an active transaction (tx).
// If tx is nil, it returns an error since outbox events must be transactional.
func (p *OutboxPublisher) PublishEvent(ctx context.Context, tx pgx.Tx, event *entity.OutboxEvent) error {
	if tx == nil {
		return errors.New("outbox publisher requires an active transaction")
	}

	payloadJSON, err := json.Marshal(event.Payload)
	if err != nil {
		return fmt.Errorf("failed to marshal event payload: %w", err)
	}

	metadataJSON, err := json.Marshal(event.Metadata)
	if err != nil {
		return fmt.Errorf("failed to marshal event metadata: %w", err)
	}

	query := `
		INSERT INTO outbox_events (
			event_id, event_type, aggregate_type, aggregate_id,
			partition_key, payload, metadata, status, error_message,
			created_at, published_at, retry_count, last_retry_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
	`

	_, err = tx.Exec(ctx, query,
		event.ID,
		event.EventType,
		event.AggregateType,
		event.AggregateID,
		event.PartitionKey,
		payloadJSON,
		metadataJSON,
		types.OutboxEventStatusPending,
		event.ErrorMessage,
		event.CreatedAt,
		event.PublishedAt,
		event.RetryCount,
		event.LastRetryAt,
	)

	if err != nil {
		p.logger.Error("Failed to insert outbox event",
			zap.String("event_type", event.EventType),
			zap.String("aggregate_id", event.AggregateID.String()),
			zap.Error(err))
		return fmt.Errorf("failed to insert outbox event: %w", err)
	}

	p.logger.Debug("Outbox event inserted",
		zap.String("event_id", event.ID.String()),
		zap.String("event_type", event.EventType))

	return nil
}

// PublishEventWithoutTx inserts an event into the outbox table without transaction.
// Use this only when there's no business transaction involved.
func (p *OutboxPublisher) PublishEventWithoutTX(ctx context.Context, event *entity.OutboxEvent) error {
	payloadJSON, err := json.Marshal(event.Payload)
	if err != nil {
		return fmt.Errorf("failed to marshal event payload: %w", err)
	}

	metadataJSON, err := json.Marshal(event.Metadata)
	if err != nil {
		return fmt.Errorf("failed to marshal event metadata: %w", err)
	}

	query := `
		INSERT INTO outbox_events (
			event_id, event_type, aggregate_type, aggregate_id,
			partition_key, payload, metadata, status, error_message,
			created_at, published_at, retry_count, last_retry_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
	`

	_, err = p.pool.Exec(ctx, query,
		event.ID,
		event.EventType,
		event.AggregateType,
		event.AggregateID,
		event.PartitionKey,
		payloadJSON,
		metadataJSON,
		types.OutboxEventStatusPending,
		event.ErrorMessage,
		event.CreatedAt,
		event.PublishedAt,
		event.RetryCount,
		event.LastRetryAt,
	)

	if err != nil {
		p.logger.Error("Failed to insert outbox event (no tx)",
			zap.String("event_type", event.EventType),
			zap.String("aggregate_id", event.AggregateID.String()),
			zap.Error(err))
		return fmt.Errorf("failed to insert outbox event: %w", err)
	}

	return nil
}

// PublishEventAsync publishes event without transaction, used for background processes
func (p *OutboxPublisher) PublishEventAsync(ctx context.Context, event *entity.OutboxEvent) {
	go func() {
		if err := p.PublishEventWithoutTX(context.Background(), event); err != nil {
			p.logger.Error("Failed to publish async outbox event",
				zap.String("event_id", event.ID.String()),
				zap.Error(err))
		}
	}()
}

// BeginTx starts a new transaction for outbox operations
func (p *OutboxPublisher) BeginTx(ctx context.Context) (pgx.Tx, error) {
	return p.pool.Begin(ctx)
}

// OutboxTransaction wraps operations that need to be transactional
type OutboxTransaction struct {
	tx     pgx.Tx
	logger *zap.Logger
}

// WithTransaction executes a function within a transaction context
func (p *OutboxPublisher) WithTransaction(ctx context.Context, fn func(tx pgx.Tx) error) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}

	defer func() {
		if r := recover(); r != nil {
			_ = tx.Rollback(ctx)
			panic(r)
		}
	}()

	if err := fn(tx); err != nil {
		if rbErr := tx.Rollback(ctx); rbErr != nil {
			p.logger.Error("Failed to rollback transaction",
				zap.Error(rbErr))
		}
		return err
	}

	return tx.Commit(ctx)
}

// NewOutboxEvent creates a new outbox event with the given parameters
func NewOutboxEvent(eventType, aggregateType string, aggregateID uuid.UUID, payload map[string]interface{}) *entity.OutboxEvent {
	return &entity.OutboxEvent{
		ID:            uuid.New(),
		EventType:     eventType,
		AggregateType: aggregateType,
		AggregateID:   aggregateID,
		PartitionKey:  aggregateID,
		Payload:       payload,
		Metadata:      make(map[string]interface{}),
		Status:        types.OutboxEventStatusPending,
	}
}
