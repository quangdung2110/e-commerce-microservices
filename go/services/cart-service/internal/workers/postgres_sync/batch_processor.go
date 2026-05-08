package postgres_sync

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

const (
	// DefaultMaxRetries is the default number of retries for failed transactions
	DefaultMaxRetries = 3
	// DefaultRetryDelay is the default delay between retries
	DefaultRetryDelay = 100 * time.Millisecond
	// DLQMaxSize is the maximum size of the Postgres Sync DLQ
	DLQMaxSize = 10000
	// PostgresSyncDLQKey is the Redis key for the Postgres Sync DLQ
	PostgresSyncDLQKey = "postgres_sync_dlq"
)

// DLQPusher defines the interface for pushing failed transactions to DLQ
type DLQPusher interface {
	PushToDLQ(ctx context.Context, cartID string, events []*CartEvent, reason string, attempts int) error
	GetDLQLength(ctx context.Context) (int64, error)
}

// RetryConfig holds configuration for retry behavior
type RetryConfig struct {
	MaxRetries int
	Delay      time.Duration
}

// DefaultRetryConfig returns the default retry configuration
func DefaultRetryConfig() RetryConfig {
	return RetryConfig{
		MaxRetries: DefaultMaxRetries,
		Delay:      DefaultRetryDelay,
	}
}

// BatchProcessor processes events in batches grouped by cart_id
type BatchProcessor struct {
	db     *gorm.DB
	logger *zap.Logger

	// Redis client for DLQ
	redisClient *redis.Client

	// Retry configuration
	retryConfig RetryConfig

	// DLQ pusher
	dlqPusher DLQPusher

	// Metrics
	mu                sync.Mutex
	eventsProcessed   int64
	eventsFailed     int64
	eventsRetried    int64
	cartsUpserted    int64
	itemsUpserted    int64
	itemsDeleted     int64
	messagesPushedToDLQ int64
}

// NewBatchProcessor creates a new batch processor
func NewBatchProcessor(db *gorm.DB, logger *zap.Logger) *BatchProcessor {
	return &BatchProcessor{
		db:          db,
		logger:      logger,
		retryConfig: DefaultRetryConfig(),
	}
}

// NewBatchProcessorWithRetry creates a new batch processor with custom retry config
func NewBatchProcessorWithRetry(db *gorm.DB, logger *zap.Logger, retryConfig RetryConfig) *BatchProcessor {
	return &BatchProcessor{
		db:          db,
		logger:      logger,
		retryConfig: retryConfig,
	}
}

// NewBatchProcessorWithRedis creates a new batch processor with Redis client for DLQ
func NewBatchProcessorWithRedis(db *gorm.DB, redisClient *redis.Client, logger *zap.Logger) *BatchProcessor {
	p := &BatchProcessor{
		db:          db,
		redisClient: redisClient,
		logger:      logger,
		retryConfig: DefaultRetryConfig(),
	}
	// Create the DLQ pusher
	p.dlqPusher = NewDLQPusher(redisClient, logger)
	return p
}

// ProcessedResult holds the result of processing a batch of events
type ProcessedResult struct {
	CartID       string
	Success      bool
	Error        error
	ItemsUpserted int64
	ItemsDeleted  int64
	RetryAttempt int
}

// ProcessEvents processes a batch of events grouped by cart_id
// Each cart_id is processed in its own transaction with retry logic
func (p *BatchProcessor) ProcessEvents(ctx context.Context, events []*CartEvent) []ProcessedResult {
	if len(events) == 0 {
		return nil
	}

	// Group events by cart_id
	cartEvents := make(map[string][]*CartEvent)
	for _, event := range events {
		cartID := event.GetCartID()
		if cartID == "" {
			p.logger.Warn("Event without cart_id, skipping",
				zap.String("event_type", event.EventType),
				zap.String("stream_id", event.StreamID),
			)
			continue
		}
		cartEvents[cartID] = append(cartEvents[cartID], event)
	}

	// Process each cart_id in parallel
	results := make([]ProcessedResult, 0, len(cartEvents))
	var wg sync.WaitGroup

	for cartID, cartEvts := range cartEvents {
		wg.Add(1)
		go func(cartID string, events []*CartEvent) {
			defer wg.Done()

			result := p.processCartEventsWithRetry(ctx, cartID, events)
			results = append(results, result)
		}(cartID, cartEvts)
	}

	wg.Wait()
	return results
}

// processCartEventsWithRetry processes events with retry logic
func (p *BatchProcessor) processCartEventsWithRetry(ctx context.Context, cartID string, events []*CartEvent) ProcessedResult {
	result := ProcessedResult{CartID: cartID}

	for attempt := 0; attempt <= p.retryConfig.MaxRetries; attempt++ {
		result.RetryAttempt = attempt

		// Process the events
		processResult := p.processCartEvents(ctx, cartID, events)

		if processResult.Success {
			result.Success = true
			result.ItemsUpserted = processResult.ItemsUpserted
			result.ItemsDeleted = processResult.ItemsDeleted
			return result
		}

		// Check if error is retryable
		if attempt < p.retryConfig.MaxRetries && IsRetryable(processResult.Error) {
			p.logger.Warn("Retrying failed cart transaction",
				zap.String("cart_id", cartID),
				zap.Int("attempt", attempt+1),
				zap.Int("max_retries", p.retryConfig.MaxRetries),
				zap.Error(processResult.Error),
			)

			// Increment retry counter
			p.mu.Lock()
			p.eventsRetried++
			p.mu.Unlock()

			// Wait before retry with exponential backoff
			delay := p.retryConfig.Delay * time.Duration(1<<uint(attempt))
			select {
			case <-ctx.Done():
				result.Success = false
				result.Error = ctx.Err()
				return result
			case <-time.After(delay):
				// Continue to next retry
			}
			continue
		}

		// Non-retryable error or max retries exceeded
		result.Success = false
		result.Error = processResult.Error

		// Update metrics
		p.mu.Lock()
		p.eventsFailed++
		p.mu.Unlock()

		// Move to DLQ if max retries exceeded
		if attempt >= p.retryConfig.MaxRetries {
			p.logger.Error("Max retries exceeded for cart transaction, moving to DLQ",
				zap.String("cart_id", cartID),
				zap.Int("attempts", attempt+1),
				zap.Error(processResult.Error),
			)

			// Push to DLQ
			if p.dlqPusher != nil {
				reason := "max_retries_exceeded"
				if processResult.Error != nil {
					reason = processResult.Error.Error()
				}
				if err := p.dlqPusher.PushToDLQ(ctx, cartID, events, reason, attempt+1); err != nil {
					p.logger.Error("Failed to push to DLQ",
						zap.String("cart_id", cartID),
						zap.Error(err),
					)
				} else {
					p.mu.Lock()
					p.messagesPushedToDLQ++
					p.mu.Unlock()
					p.logger.Info("Successfully pushed to DLQ",
						zap.String("cart_id", cartID),
						zap.Int("event_count", len(events)),
					)
				}
			}
		}

		return result
	}

	return result
}

// processCartEvents processes all events for a single cart in a transaction
func (p *BatchProcessor) processCartEvents(ctx context.Context, cartID string, events []*CartEvent) ProcessedResult {
	result := ProcessedResult{CartID: cartID}

	err := p.db.Transaction(func(tx *gorm.DB) error {
		// Collect items to upsert and delete
		var itemsToUpsert []*CartItem
		var itemIDsToDelete []string
		var latestStreamID string
		var userID string

		for _, event := range events {
			// Track latest stream_id for status update
			if latestStreamID == "" || compareStreamID(event.StreamID, latestStreamID) > 0 {
				latestStreamID = event.StreamID
			}

			// Extract user_id from first event
			if userID == "" {
				userID = event.GetUserID()
			}

			switch {
			case event.IsAddOrUpdateEvent():
				item, err := p.processAddOrUpdateEvent(event)
				if err != nil {
					p.logger.Warn("Failed to process add/update event",
						zap.String("cart_id", cartID),
						zap.String("event_type", event.EventType),
						zap.Error(err),
					)
					continue
				}
				if item != nil {
					itemsToUpsert = append(itemsToUpsert, item)
				}

			case event.IsDeleteEvent():
				itemID := event.GetCartID() + ":" + event.Payload["sku_id"].(string)
				itemIDsToDelete = append(itemIDsToDelete, itemID)

			case event.IsCartClearedEvent():
				// CART_CLEARED just updates status to DELETED
				// Items are deleted via CART_ITEM_DELETED events
				if err := UpdateCartStatus(tx, cartID, string(CartStatusDeleted), event.StreamID); err != nil {
					p.logger.Warn("Failed to update cart status to DELETED",
						zap.String("cart_id", cartID),
						zap.Error(err),
					)
					// Don't fail the transaction for status update failure
				}

			case event.IsCartCheckedOutEvent():
				if err := UpdateCartStatus(tx, cartID, string(CartStatusCheckedOut), event.StreamID); err != nil {
					p.logger.Warn("Failed to update cart status to CHECKED_OUT",
						zap.String("cart_id", cartID),
						zap.Error(err),
					)
				}

			case event.IsCartAbandonedEvent():
				// CART_ABANDONED updates status to ABANDONED
				if err := UpdateCartStatus(tx, cartID, string(CartStatusAbandoned), event.StreamID); err != nil {
					p.logger.Warn("Failed to update cart status to ABANDONED",
						zap.String("cart_id", cartID),
						zap.Error(err),
					)
				}
			}
		}

		// Batch upsert cart items
		if len(itemsToUpsert) > 0 {
			if err := BatchUpsertCartItems(tx, itemsToUpsert); err != nil {
				return &RetryableError{Err: fmt.Errorf("failed to batch upsert cart items: %w", err)}
			}
			result.ItemsUpserted = int64(len(itemsToUpsert))
		}

		// Batch delete cart items
		if len(itemIDsToDelete) > 0 {
			// Get the latest stream_id for guard
			if err := HardDeleteCartItems(tx, itemIDsToDelete, latestStreamID); err != nil {
				return &RetryableError{Err: fmt.Errorf("failed to hard delete cart items: %w", err)}
			}
			result.ItemsDeleted = int64(len(itemIDsToDelete))
		}

		// Upsert cart to ACTIVE if there were add/update events
		if len(itemsToUpsert) > 0 && userID != "" {
			if err := UpsertCartActive(tx, cartID, userID, latestStreamID); err != nil {
				return &RetryableError{Err: fmt.Errorf("failed to upsert cart active: %w", err)}
			}
		}

		return nil
	})

	if err != nil {
		result.Success = false
		result.Error = err

		// Don't count as failed if it will be retried
		if !IsRetryable(err) {
			p.mu.Lock()
			p.eventsFailed++
			p.mu.Unlock()
		}

		p.logger.Error("Failed to process cart events",
			zap.String("cart_id", cartID),
			zap.Int("event_count", len(events)),
			zap.Error(err),
		)
	} else {
		result.Success = true
		p.mu.Lock()
		p.eventsProcessed++
		p.mu.Unlock()
		p.logger.Debug("Successfully processed cart events",
			zap.String("cart_id", cartID),
			zap.Int("event_count", len(events)),
		)
	}

	return result
}

// processAddOrUpdateEvent processes CART_ITEM_ADDED or CART_ITEM_UPDATED event
func (p *BatchProcessor) processAddOrUpdateEvent(event *CartEvent) (*CartItem, error) {
	payload, err := event.ParseCartItemPayload()
	if err != nil {
		return nil, fmt.Errorf("failed to parse payload: %w", err)
	}

	return payload.ToCartItem(event.StreamID)
}

// GetMetrics returns the current metrics
func (p *BatchProcessor) GetMetrics() BatchProcessorMetrics {
	p.mu.Lock()
	defer p.mu.Unlock()

	return BatchProcessorMetrics{
		EventsProcessed: p.eventsProcessed,
		EventsFailed:    p.eventsFailed,
		EventsRetried:   p.eventsRetried,
		CartsUpserted:   p.cartsUpserted,
		ItemsUpserted:   p.itemsUpserted,
		ItemsDeleted:    p.itemsDeleted,
	}
}

// BatchProcessorMetrics holds metrics for the batch processor
type BatchProcessorMetrics struct {
	EventsProcessed int64
	EventsFailed   int64
	EventsRetried  int64
	CartsUpserted  int64
	ItemsUpserted  int64
	ItemsDeleted   int64
}

// compareStreamID compares two Redis stream IDs
// Returns: 1 if a > b, 0 if a == b, -1 if a < b
func compareStreamID(a, b string) int {
	if a == b {
		return 0
	}

	// Stream ID format: "timestamp-sequence"
	// Compare timestamp first, then sequence
	aParts := splitStreamID(a)
	bParts := splitStreamID(b)

	if aParts.timestamp > bParts.timestamp {
		return 1
	}
	if aParts.timestamp < bParts.timestamp {
		return -1
	}

	if aParts.sequence > bParts.sequence {
		return 1
	}
	return -1
}

type streamIDParts struct {
	timestamp int64
	sequence  int64
}

func splitStreamID(id string) streamIDParts {
	var ts, seq int64
	fmt.Sscanf(id, "%d-%d", &ts, &seq)
	return streamIDParts{timestamp: ts, sequence: seq}
}

// RetryableError wraps an error with retry information
type RetryableError struct {
	Err error
}

// Error implements error interface
func (e *RetryableError) Error() string {
	return e.Err.Error()
}

// Unwrap returns the wrapped error
func (e *RetryableError) Unwrap() error {
	return e.Err
}

// IsRetryable checks if an error is retryable
func IsRetryable(err error) bool {
	if err == nil {
		return false
	}
	_, ok := err.(*RetryableError)
	return ok
}

// NewRetryableError creates a new retryable error
func NewRetryableError(err error) *RetryableError {
	return &RetryableError{Err: err}
}

// DLQPusherImpl implements DLQPusher using Redis
type DLQPusherImpl struct {
	redisClient *redis.Client
	logger     *zap.Logger
}

// NewDLQPusher creates a new DLQ pusher
func NewDLQPusher(redisClient *redis.Client, logger *zap.Logger) DLQPusher {
	return &DLQPusherImpl{
		redisClient: redisClient,
		logger:     logger,
	}
}

// PushToDLQ pushes failed cart events to the DLQ stream
func (p *DLQPusherImpl) PushToDLQ(ctx context.Context, cartID string, events []*CartEvent, reason string, attempts int) error {
	if len(events) == 0 {
		return nil
	}

	// Serialize events to JSON
	eventsData, err := json.Marshal(events)
	if err != nil {
		return fmt.Errorf("failed to marshal events: %w", err)
	}

	// Build DLQ entry
	dlqEntry := map[string]interface{}{
		"cart_id":        cartID,
		"events":         string(eventsData),
		"event_count":    len(events),
		"reason":         reason,
		"retry_attempts":  attempts,
		"failed_at":      time.Now().UTC().Format(time.RFC3339),
	}

	entryJSON, err := json.Marshal(dlqEntry)
	if err != nil {
		return fmt.Errorf("failed to marshal DLQ entry: %w", err)
	}

	// Push to Redis Stream DLQ with MAXLEN cap
	_, err = p.redisClient.XAdd(ctx, &redis.XAddArgs{
		Stream: PostgresSyncDLQKey,
		MaxLen: DLQMaxSize,
		Approx: true,
		Values: map[string]interface{}{
			"data": string(entryJSON),
		},
	}).Result()

	if err != nil {
		return fmt.Errorf("failed to push to DLQ: %w", err)
	}

	p.logger.Info("Pushed events to DLQ",
		zap.String("cart_id", cartID),
		zap.Int("event_count", len(events)),
		zap.String("reason", reason),
		zap.Int("attempts", attempts),
	)

	return nil
}

// GetDLQLength returns the current DLQ length
func (p *DLQPusherImpl) GetDLQLength(ctx context.Context) (int64, error) {
	return p.redisClient.XLen(ctx, PostgresSyncDLQKey).Result()
}
