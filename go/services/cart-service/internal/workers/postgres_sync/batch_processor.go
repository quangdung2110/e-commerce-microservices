package postgres_sync

import (
	"context"
	"fmt"
	"sync"

	"go.uber.org/zap"
	"gorm.io/gorm"

)

// BatchProcessor processes events in batches grouped by cart_id
type BatchProcessor struct {
	db     *gorm.DB
	logger *zap.Logger

	// Metrics
	mu                    sync.Mutex
	eventsProcessed       int64
	eventsFailed         int64
	cartsUpserted       int64
	itemsUpserted       int64
	itemsDeleted        int64
}

// NewBatchProcessor creates a new batch processor
func NewBatchProcessor(db *gorm.DB, logger *zap.Logger) *BatchProcessor {
	return &BatchProcessor{
		db:     db,
		logger: logger,
	}
}

// ProcessedResult holds the result of processing a batch of events
type ProcessedResult struct {
	CartID     string
	Success    bool
	Error      error
	ItemsUpserted int64
	ItemsDeleted  int64
}

// ProcessEvents processes a batch of events grouped by cart_id
// Each cart_id is processed in its own transaction
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

			result := p.processCartEvents(ctx, cartID, events)
			results = append(results, result)
		}(cartID, cartEvts)
	}

	wg.Wait()
	return results
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
				return fmt.Errorf("failed to batch upsert cart items: %w", err)
			}
			result.ItemsUpserted = int64(len(itemsToUpsert))
		}

		// Batch delete cart items
		if len(itemIDsToDelete) > 0 {
			// Get the latest stream_id for guard
			if err := HardDeleteCartItems(tx, itemIDsToDelete, latestStreamID); err != nil {
				return fmt.Errorf("failed to hard delete cart items: %w", err)
			}
			result.ItemsDeleted = int64(len(itemIDsToDelete))
		}

		// Upsert cart to ACTIVE if there were add/update events
		if len(itemsToUpsert) > 0 && userID != "" {
			if err := UpsertCartActive(tx, cartID, userID, latestStreamID); err != nil {
				return fmt.Errorf("failed to upsert cart active: %w", err)
			}
		}

		return nil
	})

	if err != nil {
		result.Success = false
		result.Error = err
		p.logger.Error("Failed to process cart events",
			zap.String("cart_id", cartID),
			zap.Int("event_count", len(events)),
			zap.Error(err),
		)
	} else {
		result.Success = true
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
		EventsProcessed:  p.eventsProcessed,
		EventsFailed:     p.eventsFailed,
		CartsUpserted:   p.cartsUpserted,
		ItemsUpserted:    p.itemsUpserted,
		ItemsDeleted:     p.itemsDeleted,
	}
}

// BatchProcessorMetrics holds metrics for the batch processor
type BatchProcessorMetrics struct {
	EventsProcessed int64
	EventsFailed   int64
	CartsUpserted int64
	ItemsUpserted int64
	ItemsDeleted  int64
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
