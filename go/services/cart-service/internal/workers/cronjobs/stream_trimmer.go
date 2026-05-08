package cronjobs

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
)

// StreamTrimmer trims acknowledged messages from Redis Streams to free memory
// Runs every 5 minutes and processes active outbox streams.
type StreamTrimmer struct {
	redisClient *redis.Client
	logger      *zap.Logger
	config      config.StreamTrimmerConfig

	metrics *StreamTrimmerMetrics

	stopCh chan struct{}
	wg     sync.WaitGroup
}

// NewStreamTrimmer creates a new stream trimmer
func NewStreamTrimmer(
	redisClient *redis.Client,
	logger *zap.Logger,
	cfg config.StreamTrimmerConfig,
) *StreamTrimmer {
	return &StreamTrimmer{
		redisClient: redisClient,
		logger:      logger,
		config:      cfg,
		metrics:     &StreamTrimmerMetrics{},
		stopCh:     make(chan struct{}),
	}
}

// Start begins the stream trimmer goroutine
func (t *StreamTrimmer) Start(ctx context.Context) error {
	interval := config.ParseDuration(t.config.Interval)
	if interval == 0 {
		interval = 5 * time.Minute
	}

	t.logger.Info("Starting Stream Trimmer",
		zap.Duration("interval", interval),
		zap.Int("batch_size", t.config.BatchSize),
	)

	t.wg.Add(1)
	go func() {
		defer t.wg.Done()
		t.runLoop(ctx, interval)
	}()

	return nil
}

// runLoop runs the trimmer loop
func (t *StreamTrimmer) runLoop(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			t.logger.Info("Stream Trimmer stopped due to context")
			return
		case <-t.stopCh:
			t.logger.Info("Stream Trimmer stopped")
			return
		case <-ticker.C:
			t.runTrimmerJob(ctx)
		}
	}
}

// Stop stops the stream trimmer
func (t *StreamTrimmer) Stop() {
	close(t.stopCh)
	t.wg.Wait()
}

// runTrimmerJob executes the main trimming job
func (t *StreamTrimmer) runTrimmerJob(ctx context.Context) {
	start := time.Now()
	t.logger.Debug("Starting stream trimming job")

	batchSize := t.config.BatchSize
	if batchSize == 0 {
		batchSize = 100
	}

	// Get all active outbox streams using ZSCAN
	streams, err := t.scanActiveStreams(ctx, batchSize)
	if err != nil {
		t.logger.Error("Failed to scan active streams", zap.Error(err))
		t.metrics.IncErrors()
		return
	}

	if len(streams) == 0 {
		t.logger.Debug("No active streams to trim")
		return
	}

	t.metrics.AddStreamsScanned(int64(len(streams)))

	// Process each stream
	for _, streamKey := range streams {
		if err := t.processStream(ctx, streamKey); err != nil {
			t.logger.Error("Failed to process stream",
				zap.String("stream_key", streamKey),
				zap.Error(err),
			)
			t.metrics.IncErrors()
		}
	}

	// Update metrics
	t.metrics.SetLastRunAt(time.Now())
	latencyMs := float64(time.Since(start).Milliseconds())
	t.metrics.UpdateAvgLatency(latencyMs)

	t.logger.Debug("Stream trimming job completed",
		zap.Int("streams_processed", len(streams)),
		zap.Float64("latency_ms", latencyMs),
	)
}

// scanActiveStreams scans active outbox streams using ZSCAN
func (t *StreamTrimmer) scanActiveStreams(ctx context.Context, batchSize int) ([]string, error) {
	var streams []string
	var cursor uint64

	for {
		keys, nextCursor, err := t.redisClient.ZScan(ctx, keybuilder.ActiveOutboxStreamsKey, cursor, "*", int64(batchSize)).Result()
		if err != nil {
			return nil, fmt.Errorf("failed to ZSCAN: %w", err)
		}

		// Keys are the members (stream keys)
		streams = append(streams, keys...)

		cursor = nextCursor
		if cursor == 0 {
			break
		}
	}

	return streams, nil
}

// processStream processes a single stream
func (t *StreamTrimmer) processStream(ctx context.Context, streamKey string) error {
	// Pipeline 1: Get XLEN and XINFO GROUPS
	xlen, err := t.redisClient.XLen(ctx, streamKey).Result()
	if err != nil && err != redis.Nil {
		return fmt.Errorf("failed to get XLEN: %w", err)
	}

	// Get group info
	groups, err := t.redisClient.XInfoGroups(ctx, streamKey).Result()
	if err != nil && err != redis.Nil {
		return fmt.Errorf("failed to get XINFO GROUPS: %w", err)
	}

	// Case A: XLEN == 0 (empty stream)
	if xlen == 0 {
		if len(groups) == 0 {
			// Stream is empty and has no groups - safe to delete
			if err := t.deleteEmptyStream(ctx, streamKey); err != nil {
				return err
			}
			t.metrics.IncStreamsDeleted()
			return nil
		}
		// Has groups but no messages - skip (pending messages might exist)
		return nil
	}

	// Case C: XLEN > 0 but NO group
	if len(groups) == 0 {
		// Skip this stream - it's being created by XADD but XGROUP CREATE hasn't happened
		t.metrics.IncStreamsSkippedNoGroup()
		t.logger.Debug("Skipping stream with no group",
			zap.String("stream_key", streamKey),
		)
		return nil
	}

	// Case B: XLEN > 0 and HAS group - safe to trim
	return t.trimStream(ctx, streamKey, groups)
}

// deleteEmptyStream atomically deletes an empty stream using Lua script
func (t *StreamTrimmer) deleteEmptyStream(ctx context.Context, streamKey string) error {
	// Use Lua script for atomic check-and-delete
	script := `
		local stream_key = KEYS[1]
		local active_streams_key = KEYS[2]
		
		local stream_len = redis.call('XLEN', stream_key)
		if stream_len == 0 then
			redis.call('DEL', stream_key)
			redis.call('ZREM', active_streams_key, stream_key)
			return 1
		end
		return 0
	`

	result, err := t.redisClient.Eval(ctx, script, []string{streamKey, keybuilder.ActiveOutboxStreamsKey}).Result()
	if err != nil {
		return fmt.Errorf("failed to execute delete script: %w", err)
	}

	if result.(int64) == 1 {
		t.logger.Debug("Deleted empty stream",
			zap.String("stream_key", streamKey),
		)
	}

	return nil
}

// trimStream trims acknowledged messages from a stream
func (t *StreamTrimmer) trimStream(ctx context.Context, streamKey string, groups []redis.XInfoGroup) error {
	// Find the lowest safe ID across all groups
	lowestSafeID := t.findLowestSafeID(ctx, streamKey, groups)
	if lowestSafeID == "" {
		// No safe ID found - all messages are pending
		t.logger.Debug("No safe ID found, stream has pending messages",
			zap.String("stream_key", streamKey),
		)
		return nil
	}

	// Trim the stream to the lowest safe ID (MINID trims messages with IDs less than the threshold)
	trimmed, err := t.redisClient.XTrimMinIDApprox(ctx, streamKey, lowestSafeID, 0).Result()
	if err != nil {
		return fmt.Errorf("failed to XTRIM: %w", err)
	}

	if trimmed > 0 {
		t.metrics.IncStreamsTrimmed()
		t.metrics.AddMessagesRemoved(trimmed)
		t.logger.Debug("Trimmed stream",
			zap.String("stream_key", streamKey),
			zap.Int64("messages_removed", trimmed),
			zap.String("lowest_safe_id", lowestSafeID),
		)
	}

	// Check if stream is now empty and can be deleted
	newXLen, err := t.redisClient.XLen(ctx, streamKey).Result()
	if err == nil && newXLen == 0 {
		// Stream is now empty, delete it
		if err := t.deleteEmptyStream(ctx, streamKey); err != nil {
			t.logger.Warn("Failed to delete empty stream after trim",
				zap.String("stream_key", streamKey),
				zap.Error(err),
			)
		} else {
			t.metrics.IncStreamsDeleted()
		}
	}

	return nil
}

// findLowestSafeID finds the lowest safe ID across all consumer groups
func (t *StreamTrimmer) findLowestSafeID(ctx context.Context, streamKey string, groups []redis.XInfoGroup) string {
	var lowestID string

	for _, group := range groups {
		// Check if group has pending messages
		if group.Pending > 0 {
			// Use XPENDING to find the oldest pending message
			pending, err := t.redisClient.XPending(ctx, streamKey, group.Name).Result()
			if err != nil && err != redis.Nil {
				t.logger.Warn("Failed to get XPENDING",
					zap.String("stream_key", streamKey),
					zap.String("group", group.Name),
					zap.Error(err),
				)
				continue
			}

			// Get the first pending message ID from the XPending struct
			if pending.Count > 0 {
				// Use XREADGROUP with XPENDING to get the oldest pending message
				// For simplicity, use the oldest ID from pending details
				entries, err := t.redisClient.XPendingExt(ctx, &redis.XPendingExtArgs{
					Stream: streamKey,
					Group:  group.Name,
					Start:  "-",
					End:    "+",
					Count:  1,
				}).Result()
				if err == nil && len(entries) > 0 {
					firstPendingID := entries[0].ID
					if lowestID == "" || firstPendingID < lowestID {
						lowestID = firstPendingID
					}
				}
			}
		} else {
			// No pending messages, use last-delivered-id
			if group.LastDeliveredID != "" {
				if lowestID == "" || group.LastDeliveredID < lowestID {
					lowestID = group.LastDeliveredID
				}
			}
		}
	}

	return lowestID
}

// GetMetrics returns the trimmer metrics
func (t *StreamTrimmer) GetMetrics() StreamTrimmerSnapshot {
	return t.metrics.Snapshot()
}
