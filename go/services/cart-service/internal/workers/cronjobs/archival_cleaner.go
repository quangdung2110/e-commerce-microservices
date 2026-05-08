package cronjobs

import (
	"context"
	"database/sql"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/constants"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
)

// DataArchivalCleaner handles hard deletion of expired cart data
// according to the retention policy:
// - DELETED/CHECKED_OUT: hard delete after 3 months
// - ABANDONED: hard delete after 6 months
type DataArchivalCleaner struct {
	redisClient *redis.Client
	db          *sql.DB
	logger      *zap.Logger
	config      config.ArchivalCleanerConfig
	cartConfig  config.CartConfig

	metrics *ArchivalCleanerMetrics

	stopCh chan struct{}
	wg     sync.WaitGroup
}

// NewDataArchivalCleaner creates a new data archival cleaner
func NewDataArchivalCleaner(
	redisClient *redis.Client,
	db *sql.DB,
	logger *zap.Logger,
	cfg config.ArchivalCleanerConfig,
	cartCfg config.CartConfig,
) *DataArchivalCleaner {
	return &DataArchivalCleaner{
		redisClient: redisClient,
		db:          db,
		logger:      logger,
		config:      cfg,
		cartConfig:  cartCfg,
		metrics:     &ArchivalCleanerMetrics{},
		stopCh:      make(chan struct{}),
	}
}

// Start begins the archival cleaner cronjob
func (c *DataArchivalCleaner) Start(ctx context.Context) error {
	c.logger.Info("Starting Data Archival Cleaner",
		zap.String("schedule", c.config.Schedule),
		zap.Int("batch_size", c.config.BatchSize),
		zap.Int("deleted_retention_days", c.cartConfig.DeletedRetentionDays),
		zap.Int("abandoned_retention_days", c.cartConfig.AbandonedRetentionDays),
	)

	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		c.runCronLoop(ctx)
	}()

	return nil
}

// runCronLoop runs the cronjob on a schedule
func (c *DataArchivalCleaner) runCronLoop(ctx context.Context) {
	// Simple ticker-based execution for weekly schedule
	// In production, use robfig/cron or similar for proper cron scheduling
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			c.logger.Info("Data Archival Cleaner stopped due to context")
			return
		case <-c.stopCh:
			c.logger.Info("Data Archival Cleaner stopped")
			return
		case <-ticker.C:
			c.runCleanerJob(ctx)
		}
	}
}

// Stop stops the archival cleaner
func (c *DataArchivalCleaner) Stop() {
	close(c.stopCh)
	c.wg.Wait()
}

// runCleanerJob executes the main archival cleaning job
func (c *DataArchivalCleaner) runCleanerJob(ctx context.Context) {
	start := time.Now()
	c.logger.Info("Starting data archival cleaning job")

	// Clean DELETED/CHECKED_OUT carts older than retention period
	c.cleanExpiredCartsByStatus(ctx, constants.CartStatusDeleted)
	c.cleanExpiredCartsByStatus(ctx, constants.CartStatusCheckedOut)

	// Clean ABANDONED carts older than abandoned retention period
	c.cleanAbandonedCarts(ctx)

	// Update metrics
	c.metrics.SetLastRunAt(time.Now())
	latencyMs := float64(time.Since(start).Milliseconds())
	c.metrics.UpdateAvgLatency(latencyMs)

	c.logger.Info("Data archival cleaning job completed",
		zap.Float64("latency_ms", latencyMs),
	)
}

// cleanExpiredCartsByStatus deletes carts with given status older than retention period
func (c *DataArchivalCleaner) cleanExpiredCartsByStatus(ctx context.Context, status string) {
	retentionDays := c.cartConfig.DeletedRetentionDays
	if retentionDays == 0 {
		retentionDays = constants.DeletedRetentionDays // Default: 90 days
	}

	batchSize := c.config.BatchSize
	if batchSize == 0 {
		batchSize = 500
	}

	totalDeleted := int64(0)
	totalItemsDeleted := int64(0)

	for {
		// Query carts that are expired
		rows, err := c.db.QueryContext(ctx, `
			SELECT cart_id
			FROM carts
			WHERE status = $1
			  AND updated_at < NOW() - INTERVAL '1 day' * $2
			ORDER BY updated_at ASC
			LIMIT $3
		`, status, retentionDays, batchSize)

		if err != nil {
			c.logger.Error("Failed to query expired carts",
				zap.String("status", status),
				zap.Error(err),
			)
			c.metrics.IncErrors()
			return
		}

		var cartIDs []string
		for rows.Next() {
			var cartID string
			if err := rows.Scan(&cartID); err != nil {
				c.logger.Error("Failed to scan cart row", zap.Error(err))
				continue
			}
			cartIDs = append(cartIDs, cartID)
			c.metrics.IncCartsScanned()
		}
		rows.Close()

		if len(cartIDs) == 0 {
			break
		}

		// Hard delete each cart in a transaction
		deletedCount, itemsDeleted, err := c.hardDeleteCarts(ctx, cartIDs)
		if err != nil {
			c.logger.Error("Failed to hard delete carts",
				zap.String("status", status),
				zap.Error(err),
			)
			c.metrics.IncErrors()
		} else {
			totalDeleted += int64(deletedCount)
			totalItemsDeleted += itemsDeleted
			c.metrics.AddCartsDeleted(int64(deletedCount))
			c.metrics.AddItemsDeleted(itemsDeleted)
		}

		if len(cartIDs) < batchSize {
			break
		}
	}

	if totalDeleted > 0 {
		c.logger.Info("Cleaned expired carts",
			zap.String("status", status),
			zap.Int64("carts_deleted", totalDeleted),
			zap.Int64("items_deleted", totalItemsDeleted),
		)
	}
}

// cleanAbandonedCarts deletes ABANDONED carts older than abandoned retention period
func (c *DataArchivalCleaner) cleanAbandonedCarts(ctx context.Context) {
	retentionDays := c.cartConfig.AbandonedRetentionDays
	if retentionDays == 0 {
		retentionDays = constants.AbandonedRetentionDays // Default: 180 days
	}

	batchSize := c.config.BatchSize
	if batchSize == 0 {
		batchSize = 500
	}

	totalDeleted := int64(0)
	totalItemsDeleted := int64(0)

	for {
		// Query ABANDONED carts that are expired
		rows, err := c.db.QueryContext(ctx, `
			SELECT cart_id
			FROM carts
			WHERE status = $1
			  AND updated_at < NOW() - INTERVAL '1 day' * $2
			ORDER BY updated_at ASC
			LIMIT $3
		`, constants.CartStatusAbandoned, retentionDays, batchSize)

		if err != nil {
			c.logger.Error("Failed to query abandoned carts",
				zap.Error(err),
			)
			c.metrics.IncErrors()
			return
		}

		var cartIDs []string
		for rows.Next() {
			var cartID string
			if err := rows.Scan(&cartID); err != nil {
				c.logger.Error("Failed to scan cart row", zap.Error(err))
				continue
			}
			cartIDs = append(cartIDs, cartID)
			c.metrics.IncCartsScanned()
		}
		rows.Close()

		if len(cartIDs) == 0 {
			break
		}

		// Hard delete each cart in a transaction
		deletedCount, itemsDeleted, err := c.hardDeleteCarts(ctx, cartIDs)
		if err != nil {
			c.logger.Error("Failed to hard delete abandoned carts",
				zap.Error(err),
			)
			c.metrics.IncErrors()
		} else {
			totalDeleted += int64(deletedCount)
			totalItemsDeleted += itemsDeleted
			c.metrics.AddCartsDeleted(int64(deletedCount))
			c.metrics.AddItemsDeleted(itemsDeleted)
		}

		if len(cartIDs) < batchSize {
			break
		}
	}

	if totalDeleted > 0 {
		c.logger.Info("Cleaned expired abandoned carts",
			zap.Int64("carts_deleted", totalDeleted),
			zap.Int64("items_deleted", totalItemsDeleted),
		)
	}
}

// hardDeleteCarts deletes cart_items first, then carts in a transaction
func (c *DataArchivalCleaner) hardDeleteCarts(ctx context.Context, cartIDs []string) (int, int64, error) {
	if len(cartIDs) == 0 {
		return 0, 0, nil
	}

	// Start transaction
	tx, err := c.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, 0, err
	}
	defer func() {
		if err != nil {
			tx.Rollback()
		}
	}()

	// Step 1: Delete cart_items (no CASCADE DELETE - must delete manually)
	deleteItemsSQL := `DELETE FROM cart_items WHERE cart_id = ANY($1)`
	result, err := tx.ExecContext(ctx, deleteItemsSQL, cartIDs)
	if err != nil {
		return 0, 0, err
	}

	itemsDeleted, _ := result.RowsAffected()

	// Step 2: Delete carts
	deleteCartsSQL := `DELETE FROM carts WHERE cart_id = ANY($1)`
	result, err = tx.ExecContext(ctx, deleteCartsSQL, cartIDs)
	if err != nil {
		return 0, 0, err
	}

	cartsDeleted, _ := result.RowsAffected()

	// Commit transaction
	if err = tx.Commit(); err != nil {
		return 0, 0, err
	}

	// Clean up Redis keys for deleted carts
	c.cleanupRedisKeys(ctx, cartIDs)

	return int(cartsDeleted), itemsDeleted, nil
}

// cleanupRedisKeys removes Redis keys for deleted carts
func (c *DataArchivalCleaner) cleanupRedisKeys(ctx context.Context, cartIDs []string) {
	var keysToDelete []string
	for _, cartID := range cartIDs {
		keysToDelete = append(keysToDelete, cartID, cartID+constants.KeySuffixOutbox, cartID+constants.KeySuffixNotFound)
	}

	// Delete all keys at once
	if len(keysToDelete) > 0 {
		if err := c.redisClient.Del(ctx, keysToDelete...).Err(); err != nil {
			c.logger.Warn("Failed to cleanup Redis keys",
				zap.Int("keys_count", len(keysToDelete)),
				zap.Error(err),
			)
		} else {
			c.logger.Debug("Cleaned up Redis keys",
				zap.Int("carts_count", len(cartIDs)),
			)
		}
	}
}

// GetMetrics returns the cleaner metrics
func (c *DataArchivalCleaner) GetMetrics() ArchivalCleanerSnapshot {
	return c.metrics.Snapshot()
}
