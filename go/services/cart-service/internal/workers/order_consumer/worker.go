package order_consumer

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/IBM/sarama"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/event"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/kafka"
)

// Worker consumes ORDER_PAID events from Kafka and clears the corresponding cart in Redis.
type Worker struct {
	consumer    *kafka.Consumer
	redisClient redis.UniversalClient
	luaRepo     port.CartLuaRepository
	cacheRepo   port.CacheRepository
	kb          *keybuilder.KeyBuilder
	numShards   int
	logger      *zap.Logger
	metrics     *Metrics
	wg          sync.WaitGroup
	stopCh      chan struct{}
	batchSize   int
	timeout     time.Duration
}

// NewWorker creates a new order consumer worker.
func NewWorker(
	consumer *kafka.Consumer,
	redisClient redis.UniversalClient,
	luaRepo port.CartLuaRepository,
	cacheRepo port.CacheRepository,
	kb *keybuilder.KeyBuilder,
	numShards int,
	logger *zap.Logger,
) *Worker {
	return &Worker{
		consumer:    consumer,
		redisClient: redisClient,
		luaRepo:     luaRepo,
		cacheRepo:   cacheRepo,
		kb:          kb,
		numShards:   numShards,
		logger:      logger,
		metrics:     NewMetrics(logger),
		stopCh:      make(chan struct{}),
		batchSize:   50,
		timeout:     5 * time.Second,
	}
}

// Start begins consuming ORDER_PAID events.
func (w *Worker) Start(ctx context.Context) error {
	w.wg.Add(1)
	go w.run(ctx)
	w.logger.Info("order consumer worker started",
		zap.Int("batch_size", w.batchSize),
	)
	return nil
}

// Stop gracefully stops the worker.
func (w *Worker) Stop() {
	close(w.stopCh)
	w.wg.Wait()
	w.logger.Info("order consumer worker stopped")
}

func (w *Worker) run(ctx context.Context) {
	defer w.wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		case <-w.stopCh:
			return
		default:
			w.consume(ctx)
		}
	}
}

func (w *Worker) consume(ctx context.Context) {
	batch, err := w.consumer.PollBatch(ctx, w.batchSize, w.timeout)
	if err != nil {
		if err == context.DeadlineExceeded || err == context.Canceled {
			return
		}
		w.logger.Warn("poll error", zap.Error(err))
		return
	}

	if batch.Count() == 0 {
		return
	}

	for _, msg := range batch.Messages() {
		w.processMessage(ctx, msg)
	}

	batch.Commit()
}

func (w *Worker) processMessage(ctx context.Context, msg *sarama.ConsumerMessage) {
	var wrapper OrderPaidWrapper
	if err := json.Unmarshal(msg.Value, &wrapper); err != nil {
		w.logger.Warn("unmarshal failed",
			zap.Int64("offset", msg.Offset),
			zap.Error(err),
		)
		return
	}

	if wrapper.EventType != event.EventTypeOrderPaid {
		return
	}

	payload := wrapper.Payload

	if !isValidCartID(payload.CartID) {
		w.logger.Warn("invalid cart_id",
			zap.String("cart_id", payload.CartID),
			zap.Int64("order_id", payload.OrderID),
		)
		return
	}

	if w.isOrderProcessed(ctx, payload.OrderID) {
		w.logger.Debug("order already processed",
			zap.Int64("order_id", payload.OrderID),
		)
		return
	}

	if err := w.acquireIdempotencyLock(ctx, payload.OrderID); err != nil {
		w.logger.Warn("failed to acquire lock",
			zap.Int64("order_id", payload.OrderID),
			zap.Error(err),
		)
		return
	}

	userID := extractUserIDFromCartID(payload.CartID)
	shardID := int(w.kb.StreamShardByUserID(userID))
	streamShard := w.kb.StreamShard(uint32(shardID))

	skuIDsJSON, _ := json.Marshal(payload.SkuIDs)
	timestampMs := time.Now().UTC().UnixMilli()

	_, deleteErr := w.luaRepo.BatchDelete(
		ctx,
		payload.CartID, streamShard,
		timestampMs, event.EventTypeCartCheckedOut,
		userID, string(skuIDsJSON),
	)
	if deleteErr != nil {
		w.logger.Error("batch delete failed",
			zap.Int64("order_id", payload.OrderID),
			zap.String("cart_id", payload.CartID),
			zap.Error(deleteErr),
		)
		w.metrics.RecordErrors()
		return
	}

	w.markOrderProcessed(ctx, payload.OrderID)
	w.cacheRepo.DeleteNegativeCache(ctx, userID)

	w.metrics.RecordSuccess()
	w.logger.Info("order processed, cart cleared",
		zap.Int64("order_id", payload.OrderID),
		zap.String("cart_id", payload.CartID),
		zap.Int("sku_count", len(payload.SkuIDs)),
	)
}

func (w *Worker) isOrderProcessed(ctx context.Context, orderID int64) bool {
	key := keybuilder.IdempotencyKey(orderID)
	val, err := w.redisClient.Get(ctx, key).Result()
	return err == nil && val == "DONE"
}

func (w *Worker) acquireIdempotencyLock(ctx context.Context, orderID int64) error {
	key := keybuilder.IdempotencyKey(orderID)
	return w.redisClient.SetNX(ctx, key, "PROCESSING", 5*time.Minute).Err()
}

func (w *Worker) markOrderProcessed(ctx context.Context, orderID int64) {
	key := keybuilder.IdempotencyKey(orderID)
	_ = w.redisClient.SetEx(ctx, key, "DONE", 24*time.Hour)
}

// Metrics returns the worker's metrics.
func (w *Worker) Metrics() *Metrics {
	return w.metrics
}

// OrderPaidWrapper wraps the ORDER_PAID event for unmarshaling.
type OrderPaidWrapper struct {
	EventType string               `json:"event_type"`
	Payload   event.OrderPaidPayload `json:"payload"`
}

// isValidCartID validates the cart ID format.
func isValidCartID(cartID string) bool {
	return len(cartID) > 7 && cartID[:6] == "{cart}"
}

// extractUserIDFromCartID extracts the user ID from a cart ID.
// Cart ID format: {cart}:{user_id}
// Returns everything after the first colon.
func extractUserIDFromCartID(cartID string) string {
	for i := 0; i < len(cartID); i++ {
		if cartID[i] == ':' {
			return cartID[i+1:]
		}
	}
	return ""
}

// Consumer returns the underlying Kafka consumer.
func (w *Worker) Consumer() *kafka.Consumer {
	return w.consumer
}
