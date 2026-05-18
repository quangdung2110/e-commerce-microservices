package order_consumer

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"strconv"
	"sync"
	"time"

	"github.com/IBM/sarama"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce-platform/go/services/cart-service/internal/common/constants"
	"github.com/ecommerce-platform/go/services/cart-service/internal/common/keybuilder"
	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure/config"
)

// OrderEventConsumer consumes ORDER_PAID events from Order Service
// and triggers cart cleanup via Redis Lua scripts.
type OrderEventConsumer struct {
	kafkaBrokers []string
	consumerGroup string
	topic         string

	redisClient *redis.Client
	logger      *zap.Logger
	config      config.DLQHandlerConfig

	keyBuilder *keybuilder.CartKeyBuilder
	metrics    *OrderConsumerMetrics

	stopCh chan struct{}
	wg     sync.WaitGroup
}

// OrderPaidEvent represents the ORDER_PAID event from Order Service
type OrderPaidEvent struct {
	EventID   string           `json:"event_id"`
	EventType string          `json:"event_type"`
	Payload   OrderPaidPayload `json:"payload"`
}

// OrderPaidPayload represents the payload of ORDER_PAID event
type OrderPaidPayload struct {
	OrderID    int64    `json:"order_id"`
	UserID    int64    `json:"user_id"`
	CartID    string   `json:"cart_id"`
	Status    string   `json:"status"`
	SkuIDs    []string `json:"sku_ids"`
	PaidAt    string   `json:"paid_at"`
}

// NewOrderEventConsumer creates a new order event consumer
func NewOrderEventConsumer(
	redisClient *redis.Client,
	logger *zap.Logger,
	cfg *config.Config,
) *OrderEventConsumer {
	return &OrderEventConsumer{
		kafkaBrokers: cfg.Kafka.Brokers,
		consumerGroup: cfg.Kafka.OrderConsumerGroup,
		topic:        cfg.Kafka.OrderTopic,
		redisClient:  redisClient,
		logger:       logger,
		config:       cfg.Workers.DLQHandler,
		keyBuilder:   keybuilder.NewCartKeyBuilder(),
		metrics:      &OrderConsumerMetrics{},
		stopCh:      make(chan struct{}),
	}
}

// Start begins consuming ORDER_PAID events
func (c *OrderEventConsumer) Start(ctx context.Context) error {
	c.logger.Info("Starting Order Event Consumer",
		zap.Strings("brokers", c.kafkaBrokers),
		zap.String("topic", c.topic),
		zap.String("consumer_group", c.consumerGroup),
	)

	// Create Kafka consumer
	saramaConfig := sarama.NewConfig()
	saramaConfig.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{sarama.NewBalanceStrategyRoundRobin()}
	saramaConfig.Consumer.Offsets.Initial = sarama.OffsetNewest

	// Start consumer group
	consumerGroup, err := sarama.NewConsumerGroup(c.kafkaBrokers, c.consumerGroup, saramaConfig)
	if err != nil {
		return fmt.Errorf("failed to create consumer group: %w", err)
	}

	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		c.runConsumerGroup(ctx, consumerGroup)
	}()

	return nil
}

// runConsumerGroup runs the Kafka consumer group
func (c *OrderEventConsumer) runConsumerGroup(ctx context.Context, consumerGroup sarama.ConsumerGroup) {
	handler := &consumerGroupHandler{
		consumer: c,
		ready:    make(chan bool),
	}

	for {
		if err := consumerGroup.Consume(ctx, []string{c.topic}, handler); err != nil {
			c.logger.Error("Error from consumer", zap.Error(err))
		}

		if ctx.Err() != nil {
			return
		}

		handler.ready = make(chan bool)
	}
}

// consumerGroupHandler implements sarama.ConsumerGroupHandler
type consumerGroupHandler struct {
	consumer *OrderEventConsumer
	ready    chan bool
}

func (h *consumerGroupHandler) Setup(sarama.ConsumerGroupSession) error {
	close(h.ready)
	return nil
}

func (h *consumerGroupHandler) Cleanup(sarama.ConsumerGroupSession) error {
	return nil
}

func (h *consumerGroupHandler) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for {
		select {
		case message, ok := <-claim.Messages():
			if !ok {
				return nil
			}
			h.consumer.processMessage(session.Context(), message)
			session.MarkMessage(message, "")

		case <-session.Context().Done():
			return nil
		}
	}
}

// processMessage processes a single ORDER_PAID message
func (c *OrderEventConsumer) processMessage(ctx context.Context, message *sarama.ConsumerMessage) {
	c.metrics.IncMessagesReceived()

	// Parse event
	var event OrderPaidEvent
	if err := json.Unmarshal(message.Value, &event); err != nil {
		c.logger.Error("Failed to unmarshal ORDER_PAID event",
			zap.Error(err),
			zap.Int64("offset", message.Offset),
		)
		c.metrics.IncErrors()
		return
	}

	// Only process ORDER_PAID events
	if event.EventType != constants.EventTypeOrderPaid {
		c.metrics.IncMessagesSkipped()
		return
	}

	// Validate cart_id
	cartID := event.Payload.CartID
	if cartID == "" {
		// Try to reconstruct from user_id
		if event.Payload.UserID != 0 {
			cartID = fmt.Sprintf("{cart:user:%d}", event.Payload.UserID)
		} else {
			c.logger.Error("Missing both cart_id and user_id in ORDER_PAID event",
				zap.Int64("order_id", event.Payload.OrderID),
				zap.Int64("offset", message.Offset),
			)
			c.metrics.IncErrors()
			c.metrics.IncDLQMessages()
			return
		}
	}

	// Validate cart_id format
	cartIDPattern := regexp.MustCompile(`^\{cart:user:[^{}]+\}$`)
	if !cartIDPattern.MatchString(cartID) {
		c.logger.Error("Invalid cart_id format",
			zap.String("cart_id", cartID),
			zap.Int64("order_id", event.Payload.OrderID),
		)
		c.metrics.IncErrors()
		c.metrics.IncDLQMessages()
		return
	}

	// Validate sku_ids
	if len(event.Payload.SkuIDs) == 0 {
		c.logger.Warn("Empty sku_ids in ORDER_PAID event",
			zap.String("cart_id", cartID),
			zap.Int64("order_id", event.Payload.OrderID),
		)
		c.metrics.IncMessagesSkipped()
		return
	}

	// Extract user_id from cart_id
	userID, err := c.keyBuilder.ExtractUserIDFromCartID(cartID)
	if err != nil {
		c.logger.Error("Failed to extract user_id from cart_id",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
		c.metrics.IncErrors()
		return
	}

	// Idempotency check
	orderIDStr := strconv.FormatInt(event.Payload.OrderID, 10)
	if err := c.checkAndSetIdempotency(ctx, orderIDStr); err != nil {
		// Already processed
		if err == ErrAlreadyProcessed || err == ErrAlreadyProcessing {
			c.metrics.IncMessagesSkipped()
			return
		}
		c.logger.Error("Failed to check idempotency",
			zap.Int64("order_id", event.Payload.OrderID),
			zap.Error(err),
		)
		c.metrics.IncErrors()
		return
	}

	// Process the order - call Lua Batch Delete
	if err := c.processOrder(ctx, cartID, userID, event.Payload.SkuIDs, orderIDStr); err != nil {
		c.logger.Error("Failed to process order",
			zap.String("cart_id", cartID),
			zap.Int64("order_id", event.Payload.OrderID),
			zap.Error(err),
		)
		c.metrics.IncErrors()
		// Clear intent if exists
		outboxKey := cartID + constants.KeySuffixOutbox
		_ = c.clearIntent(ctx, outboxKey)
		return
	}

	// Mark idempotency as done
	if err := c.markIdempotencyDone(ctx, orderIDStr); err != nil {
		c.logger.Warn("Failed to mark idempotency as done",
			zap.Int64("order_id", event.Payload.OrderID),
			zap.Error(err),
		)
	}

	c.metrics.IncMessagesProcessed()
	c.logger.Info("Processed ORDER_PAID event",
		zap.String("cart_id", cartID),
		zap.Int64("order_id", event.Payload.OrderID),
		zap.Int("sku_count", len(event.Payload.SkuIDs)),
	)
}

// checkAndSetIdempotency checks if order was already processed and sets lock
func (c *OrderEventConsumer) checkAndSetIdempotency(ctx context.Context, orderID string) error {
	processingKey := c.keyBuilder.BuildIdempotencyKey(orderID)
	doneKey := c.keyBuilder.BuildIdempotencyDoneKey(orderID)

	// Check if already done
	result, err := c.redisClient.Get(ctx, doneKey).Result()
	if err == nil && result == "DONE" {
		return ErrAlreadyProcessed
	}

	// Try to set processing lock (NX = only if not exists)
	set, err := c.redisClient.SetNX(ctx, processingKey, "PROCESSING", 5*time.Minute).Result()
	if err != nil {
		return fmt.Errorf("failed to set idempotency lock: %w", err)
	}

	if !set {
		return ErrAlreadyProcessing
	}

	return nil
}

// markIdempotencyDone marks the idempotency key as done
func (c *OrderEventConsumer) markIdempotencyDone(ctx context.Context, orderID string) error {
	processingKey := c.keyBuilder.BuildIdempotencyKey(orderID)
	doneKey := c.keyBuilder.BuildIdempotencyDoneKey(orderID)

	// Delete processing key
	c.redisClient.Del(ctx, processingKey)

	// Set done key
	_, err := c.redisClient.Set(ctx, doneKey, "DONE", 24*time.Hour).Result()
	return err
}

// processOrder processes the order and clears cart items
func (c *OrderEventConsumer) processOrder(ctx context.Context, cartID, userID string, skuIDs []string, orderID string) error {
	now := time.Now()
	nowMs := now.UnixMilli()

	outboxKey := cartID + constants.KeySuffixOutbox

	// Step 1: Pre-register intent
	if err := c.preRegisterIntent(ctx, outboxKey); err != nil {
		c.logger.Warn("Failed to pre-register intent",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
	}

	// Step 2: Marshal SKU IDs to JSON
	skuIDsJSON, err := json.Marshal(skuIDs)
	if err != nil {
		return fmt.Errorf("failed to marshal SKU IDs: %w", err)
	}

	// Step 3: Call Lua Batch Delete script
	// Note: We use EVAL here since we need to pass the batch delete arguments
	result, err := c.redisClient.Eval(ctx, getBatchDeleteScript(), []string{cartID, outboxKey},
		nowMs,
		constants.EventTypeCartCheckedOut,
		userID,
		string(skuIDsJSON),
	).Result()

	if err != nil {
		return fmt.Errorf("failed to execute batch delete: %w", err)
	}

	// Step 4: Mark active and notify
	if err := c.markActive(ctx, outboxKey); err != nil {
		c.logger.Warn("Failed to mark active",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
	}

	// Step 5: Clear intent
	if err := c.clearIntent(ctx, outboxKey); err != nil {
		c.logger.Warn("Failed to clear intent",
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
	}

	// Check if cart was cleared
	deletedCount, _ := result.(int64)
	if deletedCount > 0 {
		c.metrics.IncCartsCleared()
	}

	return nil
}

// preRegisterIntent registers an intent before Lua execution
func (c *OrderEventConsumer) preRegisterIntent(ctx context.Context, streamKey string) error {
	score := float64(time.Now().UnixMilli())
	_, err := c.redisClient.ZAdd(ctx, keybuilder.OutboxPublishIntentsKey, redis.Z{
		Score:  score,
		Member: streamKey,
	}).Result()
	return err
}

// markActive marks stream as active and notifies workers
func (c *OrderEventConsumer) markActive(ctx context.Context, streamKey string) error {
	score := float64(time.Now().UnixMilli())

	// Add to active outbox streams
	_, err := c.redisClient.ZAdd(ctx, keybuilder.ActiveOutboxStreamsKey, redis.Z{
		Score:  score,
		Member: streamKey,
	}).Result()
	if err != nil {
		return err
	}

	// Notify workers via Pub/Sub
	_, err = c.redisClient.Publish(ctx, keybuilder.OutboxNotificationsChannel, streamKey).Result()
	return err
}

// clearIntent removes the intent after successful publish
func (c *OrderEventConsumer) clearIntent(ctx context.Context, streamKey string) error {
	_, err := c.redisClient.ZRem(ctx, keybuilder.OutboxPublishIntentsKey, streamKey).Result()
	return err
}

// getBatchDeleteScript returns the Lua batch delete script
func getBatchDeleteScript() string {
	return `
-- Lua Script: Batch Delete Cart Items for Checkout
--
-- KEYS[1]: cart_key - Cart hash key
-- KEYS[2]: outbox_stream - Outbox stream key
--
-- ARGV[1]: current_timestamp_ms
-- ARGV[2]: clear_event_type - Should be CART_CHECKED_OUT
-- ARGV[3]: user_id
-- ARGV[4]: sku_ids_json - JSON array of SKU IDs to delete
--
-- Returns: deleted_count

local current_time_str = tostring(ARGV[1])
local clear_event_type = ARGV[2]
local user_id = ARGV[3]
local sku_ids = cjson.decode(ARGV[4])

local deleted_count = 0
local skus_to_delete = {}

local deleted_event_obj = {
    sku_id = "",
    cart_id = "",
    user_id = user_id,
    deleted_at = current_time_str
}
deleted_event_obj.cart_id = KEYS[1]

-- 1. Scan through sku_ids array and collect existing SKUs to delete
for i = 1, #sku_ids do
    local sku_id = sku_ids[i]
    local current_item = redis.call('HGET', KEYS[1], sku_id)

    if current_item then
        table.insert(skus_to_delete, sku_id)
        deleted_count = deleted_count + 1

        deleted_event_obj.sku_id = sku_id
        local deleted_payload = cjson.encode(deleted_event_obj)
        redis.call('XADD', KEYS[2], '*', 'event_type', 'CART_ITEM_DELETED', 'payload', deleted_payload)
    end
end

-- 2. Batch HDEL for items to remove completely
if deleted_count > 0 then
    redis.call('HDEL', KEYS[1], unpack(skus_to_delete))

    -- Cleanup Key if cart becomes empty
    local remaining_items = redis.call('HLEN', KEYS[1])
    if remaining_items == 0 then
        redis.call('DEL', KEYS[1])
        local clear_obj = {cart_id = KEYS[1], user_id = user_id}
        clear_obj.checked_out_at = current_time_str
        redis.call('XADD', KEYS[2], '*', 'event_type', clear_event_type, 'payload', cjson.encode(clear_obj))
    end
end

return deleted_count
`
}

// Stop stops the consumer
func (c *OrderEventConsumer) Stop() {
	c.logger.Info("Stopping Order Event Consumer")
	close(c.stopCh)
	c.wg.Wait()
}

// GetMetrics returns the consumer metrics
func (c *OrderEventConsumer) GetMetrics() OrderConsumerSnapshot {
	return c.metrics.Snapshot()
}

// Custom errors
var (
	ErrAlreadyProcessed = fmt.Errorf("order already processed")
	ErrAlreadyProcessing = fmt.Errorf("order already being processed")
)
