package cronjobs

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/constants"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
)

// AbandonedCartMarker marks carts that have been inactive for a specified period
// as ABANDONED and publishes CART_ABANDONED events to Redis Streams.
type AbandonedCartMarker struct {
	redisClient *redis.Client
	db          *sql.DB
	logger      *zap.Logger
	config      config.AbandonedMarkerConfig
	cartConfig  config.CartConfig

	keyBuilder *keybuilder.CartKeyBuilder
	metrics    *AbandonedMarkerMetrics

	stopCh chan struct{}
	wg     sync.WaitGroup
}

// NewAbandonedCartMarker creates a new abandoned cart marker
func NewAbandonedCartMarker(
	redisClient *redis.Client,
	db *sql.DB,
	logger *zap.Logger,
	cfg config.AbandonedMarkerConfig,
	cartCfg config.CartConfig,
) *AbandonedCartMarker {
	return &AbandonedCartMarker{
		redisClient: redisClient,
		db:          db,
		logger:      logger,
		config:      cfg,
		cartConfig:  cartCfg,
		keyBuilder:  keybuilder.NewCartKeyBuilder(),
		metrics:     &AbandonedMarkerMetrics{},
		stopCh:      make(chan struct{}),
	}
}

// Start begins the abandoned cart marker cronjob
func (m *AbandonedCartMarker) Start(ctx context.Context) error {
	m.logger.Info("Starting Abandoned Cart Marker",
		zap.String("schedule", m.config.Schedule),
		zap.Int("batch_size", m.config.BatchSize),
		zap.Int("threshold_days", m.cartConfig.AbandonedThresholdDays),
	)

	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		m.runCronLoop(ctx)
	}()

	return nil
}

// runCronLoop runs the cronjob on a schedule
func (m *AbandonedCartMarker) runCronLoop(ctx context.Context) {
	// Simple ticker-based execution for now
	// In production, use robfig/cron or similar for proper cron scheduling
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			m.logger.Info("Abandoned Cart Marker stopped due to context")
			return
		case <-m.stopCh:
			m.logger.Info("Abandoned Cart Marker stopped")
			return
		case <-ticker.C:
			m.runMarkerJob(ctx)
			// Also run reconciliation
			m.runReconciliation(ctx)
		}
	}
}

// Stop stops the abandoned cart marker
func (m *AbandonedCartMarker) Stop() {
	close(m.stopCh)
	m.wg.Wait()
}

// runMarkerJob executes the main abandoned cart marking job
func (m *AbandonedCartMarker) runMarkerJob(ctx context.Context) {
	start := time.Now()
	m.logger.Info("Starting abandoned cart marking job")

	thresholdDays := m.cartConfig.AbandonedThresholdDays
	if thresholdDays == 0 {
		thresholdDays = constants.AbandonedThresholdDays
	}

	batchSize := m.config.BatchSize
	if batchSize == 0 {
		batchSize = 1000
	}

	totalMarked := int64(0)
	totalErrors := int64(0)
	totalScanned := int64(0)

	for {
		// Query carts that are ACTIVE and not updated for threshold days
		rows, err := m.db.QueryContext(ctx, `
			SELECT cart_id, user_id
			FROM carts
			WHERE status = $1
			  AND updated_at < NOW() - INTERVAL '1 day' * $2
			ORDER BY updated_at ASC
			LIMIT $3
		`, constants.CartStatusActive, thresholdDays, batchSize)

		if err != nil {
			m.logger.Error("Failed to query abandoned carts", zap.Error(err))
			m.metrics.IncErrors()
			return
		}

		var carts []struct {
			CartID string
			UserID string
		}

		for rows.Next() {
			var c struct {
				CartID string
				UserID string
			}
			if err := rows.Scan(&c.CartID, &c.UserID); err != nil {
				m.logger.Error("Failed to scan cart row", zap.Error(err))
				continue
			}
			carts = append(carts, c)
			totalScanned++
		}
		rows.Close()

		if len(carts) == 0 {
			break
		}

		// Process each cart
		for _, cart := range carts {
			if err := m.markCartAbandoned(ctx, cart.CartID, cart.UserID); err != nil {
				m.logger.Error("Failed to mark cart abandoned",
					zap.String("cart_id", cart.CartID),
					zap.Error(err),
				)
				totalErrors++
				m.metrics.IncErrors()
			} else {
				totalMarked++
				m.metrics.IncCartsMarkedAbandoned()
				m.metrics.IncEventsPublished()
			}
		}

		// If we got less than batch size, we're done
		if len(carts) < batchSize {
			break
		}
	}

	// Update metrics
	m.metrics.AddCartsScanned(totalScanned)
	m.metrics.SetLastRunAt(time.Now())
	latencyMs := float64(time.Since(start).Milliseconds())
	m.metrics.UpdateAvgLatency(latencyMs)

	m.logger.Info("Abandoned cart marking job completed",
		zap.Int64("carts_scanned", totalScanned),
		zap.Int64("carts_marked", totalMarked),
		zap.Int64("errors", totalErrors),
		zap.Float64("latency_ms", latencyMs),
	)
}

// markCartAbandoned marks a single cart as abandoned
func (m *AbandonedCartMarker) markCartAbandoned(ctx context.Context, cartID, userID string) error {
	now := time.Now()
	nowMs := now.UnixMilli()
	nowStr := fmt.Sprintf("%d", nowMs)

	// Step 1: Update DB - set status to ABANDONED, abandoned_event_published_at = NULL
	_, err := m.db.ExecContext(ctx, `
		UPDATE carts
		SET status = $1,
		    abandoned_event_published_at = NULL,
		    updated_at = NOW()
		WHERE cart_id = $2 AND status = $3
	`, constants.CartStatusAbandoned, cartID, constants.CartStatusActive)

	if err != nil {
		return fmt.Errorf("failed to update cart status: %w", err)
	}

	// Extract user_id from cart_id if not provided
	if userID == "" {
		extractedUserID, err := m.keyBuilder.ExtractUserIDFromCartID(cartID)
		if err != nil {
			return fmt.Errorf("failed to extract user_id: %w", err)
		}
		userID = extractedUserID
	}

	// Build outbox stream key
	outboxKey := cartID + constants.KeySuffixOutbox

	// Step 2: Pre-register intent
	if err := m.preRegisterIntent(ctx, outboxKey); err != nil {
		m.logger.Warn("Failed to pre-register intent, continuing anyway",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
	}

	// Step 3: XADD CART_ABANDONED event to Redis Stream
	eventPayload := map[string]interface{}{
		"cart_id":       cartID,
		"user_id":       userID,
		"abandoned_at": nowStr,
	}

	payloadJSON, err := json.Marshal(eventPayload)
	if err != nil {
		return fmt.Errorf("failed to marshal event payload: %w", err)
	}

	_, err = m.redisClient.XAdd(ctx, &redis.XAddArgs{
		Stream: outboxKey,
		MaxLen: 0, // No MAXLEN - let Stream Trimmer handle memory
		Approx: true,
		Values: map[string]interface{}{
			"event_type": constants.EventTypeCartAbandoned,
			"payload":    string(payloadJSON),
		},
	}).Result()

	if err != nil {
		// Clear intent on failure
		_ = m.clearIntent(ctx, outboxKey)
		return fmt.Errorf("failed to XADD event: %w", err)
	}

	// Step 4: Mark active and notify
	if err := m.markActive(ctx, outboxKey); err != nil {
		m.logger.Warn("Failed to mark active, continuing",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
	}

	// Step 5: Update DB - set abandoned_event_published_at
	_, err = m.db.ExecContext(ctx, `
		UPDATE carts
		SET abandoned_event_published_at = NOW()
		WHERE cart_id = $1
	`, cartID)

	if err != nil {
		m.logger.Warn("Failed to update abandoned_event_published_at",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
		// Don't return error - the event was published
	}

	// Step 6: Clear intent
	if err := m.clearIntent(ctx, outboxKey); err != nil {
		m.logger.Warn("Failed to clear intent",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
	}

	m.logger.Debug("Cart marked as abandoned",
		zap.String("cart_id", cartID),
		zap.String("user_id", userID),
		zap.String("abandoned_at", nowStr),
	)

	return nil
}

// runReconciliation finds and re-publishes events for carts where
// abandoned_event_published_at is NULL (crash recovery)
func (m *AbandonedCartMarker) runReconciliation(ctx context.Context) {
	start := time.Now()
	m.logger.Info("Starting abandoned cart reconciliation")

	batchSize := m.config.BatchSize
	if batchSize == 0 {
		batchSize = 1000
	}

	totalReconciled := int64(0)

	for {
		// Find carts with status=ABANDONED but abandoned_event_published_at=NULL
		rows, err := m.db.QueryContext(ctx, `
			SELECT cart_id, user_id
			FROM carts
			WHERE status = $1
			  AND abandoned_event_published_at IS NULL
			  AND updated_at >= NOW() - INTERVAL '24 hours'
			ORDER BY updated_at ASC
			LIMIT $2
		`, constants.CartStatusAbandoned, batchSize)

		if err != nil {
			m.logger.Error("Failed to query carts for reconciliation", zap.Error(err))
			return
		}

		var carts []struct {
			CartID string
			UserID string
		}

		for rows.Next() {
			var c struct {
				CartID string
				UserID string
			}
			if err := rows.Scan(&c.CartID, &c.UserID); err != nil {
				m.logger.Error("Failed to scan cart row", zap.Error(err))
				continue
			}
			carts = append(carts, c)
		}
		rows.Close()

		if len(carts) == 0 {
			break
		}

		// Re-publish events for each cart
		for _, cart := range carts {
			if err := m.republishAbandonedEvent(ctx, cart.CartID, cart.UserID); err != nil {
				m.logger.Error("Failed to republish abandoned event",
					zap.String("cart_id", cart.CartID),
					zap.Error(err),
				)
				m.metrics.IncErrors()
			} else {
				totalReconciled++
				m.metrics.IncCartsReconciled()
			}
		}

		if len(carts) < batchSize {
			break
		}
	}

	latencyMs := float64(time.Since(start).Milliseconds())
	if totalReconciled > 0 {
		m.logger.Info("Abandoned cart reconciliation completed",
			zap.Int64("carts_reconciled", totalReconciled),
			zap.Float64("latency_ms", latencyMs),
		)
	}
}

// republishAbandonedEvent re-publishes the CART_ABANDONED event
func (m *AbandonedCartMarker) republishAbandonedEvent(ctx context.Context, cartID, userID string) error {
	now := time.Now()
	nowMs := now.UnixMilli()
	nowStr := fmt.Sprintf("%d", nowMs)

	// Extract user_id from cart_id if not provided
	if userID == "" {
		extractedUserID, err := m.keyBuilder.ExtractUserIDFromCartID(cartID)
		if err != nil {
			return fmt.Errorf("failed to extract user_id: %w", err)
		}
		userID = extractedUserID
	}

	// Build outbox stream key
	outboxKey := cartID + constants.KeySuffixOutbox

	// Pre-register intent
	if err := m.preRegisterIntent(ctx, outboxKey); err != nil {
		m.logger.Warn("Failed to pre-register intent for reconciliation",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
	}

	// XADD CART_ABANDONED event
	eventPayload := map[string]interface{}{
		"cart_id":       cartID,
		"user_id":       userID,
		"abandoned_at": nowStr,
	}

	payloadJSON, err := json.Marshal(eventPayload)
	if err != nil {
		return fmt.Errorf("failed to marshal event payload: %w", err)
	}

	_, err = m.redisClient.XAdd(ctx, &redis.XAddArgs{
		Stream: outboxKey,
		MaxLen: 0,
		Approx: true,
		Values: map[string]interface{}{
			"event_type": constants.EventTypeCartAbandoned,
			"payload":    string(payloadJSON),
		},
	}).Result()

	if err != nil {
		_ = m.clearIntent(ctx, outboxKey)
		return fmt.Errorf("failed to XADD event: %w", err)
	}

	// Mark active and notify
	if err := m.markActive(ctx, outboxKey); err != nil {
		m.logger.Warn("Failed to mark active", zap.String("cart_id", cartID), zap.Error(err))
	}

	// Update abandoned_event_published_at
	_, err = m.db.ExecContext(ctx, `
		UPDATE carts
		SET abandoned_event_published_at = NOW()
		WHERE cart_id = $1
	`, cartID)

	if err != nil {
		m.logger.Warn("Failed to update abandoned_event_published_at",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
	}

	// Clear intent
	if err := m.clearIntent(ctx, outboxKey); err != nil {
		m.logger.Warn("Failed to clear intent", zap.String("cart_id", cartID), zap.Error(err))
	}

	m.logger.Info("Reconciled abandoned cart event",
		zap.String("cart_id", cartID),
	)

	return nil
}

// preRegisterIntent registers an intent before XADD
func (m *AbandonedCartMarker) preRegisterIntent(ctx context.Context, streamKey string) error {
	score := float64(time.Now().UnixMilli())
	_, err := m.redisClient.ZAdd(ctx, keybuilder.OutboxPublishIntentsKey, redis.Z{
		Score:  score,
		Member: streamKey,
	}).Result()
	return err
}

// markActive marks the stream as active and notifies workers
func (m *AbandonedCartMarker) markActive(ctx context.Context, streamKey string) error {
	score := float64(time.Now().UnixMilli())

	// Add to active outbox streams
	_, err := m.redisClient.ZAdd(ctx, keybuilder.ActiveOutboxStreamsKey, redis.Z{
		Score:  score,
		Member: streamKey,
	}).Result()
	if err != nil {
		return err
	}

	// Notify workers via Pub/Sub
	_, err = m.redisClient.Publish(ctx, keybuilder.OutboxNotificationsChannel, streamKey).Result()
	return err
}

// clearIntent removes the intent after successful publish
func (m *AbandonedCartMarker) clearIntent(ctx context.Context, streamKey string) error {
	_, err := m.redisClient.ZRem(ctx, keybuilder.OutboxPublishIntentsKey, streamKey).Result()
	return err
}

// GetMetrics returns the worker metrics
func (m *AbandonedCartMarker) GetMetrics() AbandonedMarkerSnapshot {
	return m.metrics.Snapshot()
}
