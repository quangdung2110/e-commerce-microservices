package outbox_relay

import (
	"context"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce-platform/go/services/cart-service/internal/common/keybuilder"
	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure/config"
)

// DLQHandler processes messages in the dead letter queue
type DLQHandler struct {
	redisClient *redis.Client
	logger      *zap.Logger
	config      config.DLQHandlerConfig

	stopCh chan struct{}
	wg     sync.WaitGroup
}

// NewDLQHandler creates a new DLQ handler
func NewDLQHandler(
	redisClient *redis.Client,
	logger *zap.Logger,
	cfg config.DLQHandlerConfig,
) *DLQHandler {
	return &DLQHandler{
		redisClient: redisClient,
		logger:      logger,
		config:      cfg,
		stopCh:      make(chan struct{}),
	}
}

// Start begins the DLQ handler loop
func (h *DLQHandler) Start(ctx context.Context) error {
	interval := config.ParseDuration(h.config.Interval)
	if interval == 0 {
		interval = 5 * time.Minute
	}

	h.logger.Info("Starting DLQ Handler",
		zap.Duration("interval", interval),
		zap.Int("max_size", h.config.MaxDLQSize),
	)

	h.wg.Add(1)
	go func() {
		defer h.wg.Done()
		h.runLoop(ctx, interval)
	}()

	return nil
}

// runLoop runs the main handler loop
func (h *DLQHandler) runLoop(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			h.logger.Info("DLQ Handler stopped due to context")
			return
		case <-h.stopCh:
			h.logger.Info("DLQ Handler stopped")
			return
		case <-ticker.C:
			h.processDLQ(ctx)
		}
	}
}

// Stop stops the DLQ handler
func (h *DLQHandler) Stop() {
	close(h.stopCh)
	h.wg.Wait()
}

// processDLQ processes messages in the DLQ
func (h *DLQHandler) processDLQ(ctx context.Context) {
	// Check DLQ length
	dlqLen, err := h.redisClient.XLen(ctx, keybuilder.OutboxDLQKey).Result()
	if err != nil && err != redis.Nil {
		h.logger.Error("Failed to get DLQ length", zap.Error(err))
		return
	}

	if dlqLen > 0 {
		h.logger.Info("DLQ has messages",
			zap.Int64("count", dlqLen),
		)
	}

	// Check if DLQ exceeds max size
	if dlqLen > int64(h.config.MaxDLQSize) {
		h.logger.Warn("DLQ exceeds max size, trimming",
			zap.Int64("current_size", dlqLen),
			zap.Int("max_size", h.config.MaxDLQSize),
		)
		// Trim DLQ to max size using XADD with MAXLEN
		// We can't directly trim, so we add an empty message to trigger approximate trimming
		// For exact trimming, use a Lua script or external tool
		h.trimDLQ(ctx)
	}

	// Auto-quarantine old messages
	h.autoQuarantine(ctx)
}

// trimDLQ trims the DLQ to max size
func (h *DLQHandler) trimDLQ(ctx context.Context) {
	// Use XTRIM with MINID approach - keep messages from a certain ID onwards
	// For simplicity, we'll delete oldest messages one by one
	messages, err := h.redisClient.XRange(ctx, keybuilder.OutboxDLQKey, "-", "+").Result()
	if err != nil {
		h.logger.Error("Failed to read DLQ for trimming", zap.Error(err))
		return
	}

	// Calculate how many to delete
	toDelete := len(messages) - h.config.MaxDLQSize
	if toDelete <= 0 {
		return
	}

	// Delete oldest messages
	for i := 0; i < toDelete && i < len(messages); i++ {
		h.redisClient.XDel(ctx, keybuilder.OutboxDLQKey, messages[i].ID)
	}

	h.logger.Info("DLQ trimmed",
		zap.Int("deleted", toDelete),
		zap.Int64("remaining", int64(len(messages)-toDelete)),
	)
}

// autoQuarantine removes messages older than the auto-quarantine threshold
func (h *DLQHandler) autoQuarantine(ctx context.Context) {
	if h.config.AutoQuarantineHours == 0 {
		return
	}

	quarantineThreshold := time.Now().Add(-time.Duration(h.config.AutoQuarantineHours) * time.Hour)

	// Read DLQ messages to check their age
	messages, err := h.redisClient.XRange(ctx, keybuilder.OutboxDLQKey, "-", "+").Result()
	if err != nil {
		if err != redis.Nil {
			h.logger.Error("Failed to read DLQ", zap.Error(err))
		}
		return
	}

	var toDelete []string
	for _, msg := range messages {
		// Check if message has a failed_at timestamp
		failedAtStr, ok := msg.Values["failed_at"].(string)
		if !ok {
			continue
		}

		failedAt, err := time.Parse(time.RFC3339, failedAtStr)
		if err != nil {
			continue
		}

		if failedAt.Before(quarantineThreshold) {
			toDelete = append(toDelete, msg.ID)
		}
	}

	if len(toDelete) > 0 {
		h.logger.Info("Auto-quarantining old DLQ messages",
			zap.Int("count", len(toDelete)),
			zap.Duration("threshold", quarantineThreshold.Sub(time.Now())),
		)

		// Delete the messages
		for _, id := range toDelete {
			h.redisClient.XDel(ctx, keybuilder.OutboxDLQKey, id)
		}

		// Log audit message
		h.logger.Info("Auto-quarantine completed",
			zap.Int("deleted", len(toDelete)),
		)
	}
}

// ReplayMessage replays a specific message from DLQ
func (h *DLQHandler) ReplayMessage(ctx context.Context, messageID string) error {
	// Get the message from DLQ
	messages, err := h.redisClient.XRange(ctx, keybuilder.OutboxDLQKey, messageID, messageID).Result()
	if err != nil {
		return err
	}

	if len(messages) == 0 {
		return nil // Message not found
	}

	msg := messages[0]
	dataStr, ok := msg.Values["data"].(string)
	if !ok {
		return nil
	}

	h.logger.Info("Replaying DLQ message",
		zap.String("message_id", messageID),
		zap.String("data", dataStr),
	)

	// Delete from DLQ
	h.redisClient.XDel(ctx, keybuilder.OutboxDLQKey, messageID)

	// In a real implementation, this would re-process the message
	// For now, we just log it as replay was requested

	return nil
}

// GetDLQLength returns the current DLQ length
func (h *DLQHandler) GetDLQLength(ctx context.Context) (int64, error) {
	return h.redisClient.XLen(ctx, keybuilder.OutboxDLQKey).Result()
}
