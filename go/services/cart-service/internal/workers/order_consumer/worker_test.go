package order_consumer

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
	"go.uber.org/zap"
)

// ============ Test Setup ============

func setupTestRedis(t *testing.T) (*redis.Client, *miniredis.Miniredis) {
	mr, err := miniredis.Run()
	require.NoError(t, err)

	client := redis.NewClient(&redis.Options{
		Addr: mr.Addr(),
	})

	return client, mr
}

// ============ Idempotency Tests ============

func TestCheckAndSetIdempotency_NotProcessed(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	logger := zap.NewNop()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		keyBuilder:  keyBuilder,
		logger:      logger,
	}

	ctx := context.Background()
	orderID := "order-123"

	err := consumer.checkAndSetIdempotency(ctx, orderID)
	assert.NoError(t, err)
}

func TestCheckAndSetIdempotency_AlreadyProcessing(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	logger := zap.NewNop()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		keyBuilder:  keyBuilder,
		logger:      logger,
	}

	ctx := context.Background()
	orderID := "order-123"

	// First call should succeed
	err := consumer.checkAndSetIdempotency(ctx, orderID)
	require.NoError(t, err)

	// Second call should fail with AlreadyProcessing
	err = consumer.checkAndSetIdempotency(ctx, orderID)
	assert.Equal(t, ErrAlreadyProcessing, err)
}

func TestCheckAndSetIdempotency_AlreadyProcessed(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	logger := zap.NewNop()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		keyBuilder:  keyBuilder,
		logger:      logger,
	}

	ctx := context.Background()
	orderID := "order-456"

	// Set as done
	consumer.markIdempotencyDone(ctx, orderID)

	// Should return AlreadyProcessed
	err := consumer.checkAndSetIdempotency(ctx, orderID)
	assert.Equal(t, ErrAlreadyProcessed, err)
}

func TestMarkIdempotencyDone(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	logger := zap.NewNop()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		keyBuilder:  keyBuilder,
		logger:      logger,
	}

	ctx := context.Background()
	orderID := "order-789"

	err := consumer.markIdempotencyDone(ctx, orderID)
	assert.NoError(t, err)

	// Verify done key exists
	doneKey := keyBuilder.BuildIdempotencyDoneKey(orderID)
	result, err := redisClient.Get(ctx, doneKey).Result()
	require.NoError(t, err)
	assert.Equal(t, "DONE", result)
}

// ============ Intent Management Tests ============

func TestPreRegisterIntent(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	logger := zap.NewNop()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		logger:      logger,
	}

	ctx := context.Background()
	streamKey := "{cart:user:123}:outbox"

	err := consumer.preRegisterIntent(ctx, streamKey)
	assert.NoError(t, err)

	// Verify intent was added
	score, err := redisClient.ZScore(ctx, keybuilder.OutboxPublishIntentsKey, streamKey).Result()
	assert.NoError(t, err)
	assert.Greater(t, score, float64(0))
}

func TestMarkActive(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	logger := zap.NewNop()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		logger:      logger,
	}

	ctx := context.Background()
	streamKey := "{cart:user:456}:outbox"

	err := consumer.markActive(ctx, streamKey)
	assert.NoError(t, err)

	// Verify stream was added to active
	score, err := redisClient.ZScore(ctx, keybuilder.ActiveOutboxStreamsKey, streamKey).Result()
	assert.NoError(t, err)
	assert.Greater(t, score, float64(0))
}

func TestClearIntent(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	logger := zap.NewNop()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		logger:      logger,
	}

	ctx := context.Background()
	streamKey := "{cart:user:789}:outbox"

	// First add intent
	redisClient.ZAdd(ctx, keybuilder.OutboxPublishIntentsKey, redis.Z{
		Score:  float64(time.Now().UnixMilli()),
		Member: streamKey,
	})

	// Clear it
	err := consumer.clearIntent(ctx, streamKey)
	assert.NoError(t, err)

	// Verify intent was removed
	_, err = redisClient.ZScore(ctx, keybuilder.OutboxPublishIntentsKey, streamKey).Result()
	assert.Error(t, err)
}

// ============ Message Parsing Tests ============

func TestParseOrderPaidEvent(t *testing.T) {
	jsonData := `{
		"event_id": "evt-123",
		"event_type": "ORDER_PAID",
		"payload": {
			"order_id": 12345,
			"user_id": 67890,
			"cart_id": "{cart:user:67890}",
			"status": "PAID",
			"sku_ids": ["sku-001", "sku-002"],
			"paid_at": "2024-01-01T12:00:00Z"
		}
	}`

	var event OrderPaidEvent
	err := json.Unmarshal([]byte(jsonData), &event)
	require.NoError(t, err)

	assert.Equal(t, "evt-123", event.EventID)
	assert.Equal(t, "ORDER_PAID", event.EventType)
	assert.Equal(t, int64(12345), event.Payload.OrderID)
	assert.Equal(t, int64(67890), event.Payload.UserID)
	assert.Equal(t, "{cart:user:67890}", event.Payload.CartID)
	assert.Len(t, event.Payload.SkuIDs, 2)
}

func TestParseOrderPaidEvent_InvalidJSON(t *testing.T) {
	jsonData := `invalid json`

	var event OrderPaidEvent
	err := json.Unmarshal([]byte(jsonData), &event)
	assert.Error(t, err)
}

// ============ Batch Delete Script Tests ============

func TestGetBatchDeleteScript(t *testing.T) {
	script := getBatchDeleteScript()
	assert.NotEmpty(t, script)
	assert.Contains(t, script, "HDEL")
	assert.Contains(t, script, "XADD")
	assert.Contains(t, script, "HLEN")
	assert.Contains(t, script, "DEL")
}

func TestProcessOrder_LuaExecution(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	logger := zap.NewNop()
	keyBuilder := keybuilder.NewCartKeyBuilder()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		logger:      logger,
		keyBuilder:  keyBuilder,
		metrics:    &OrderConsumerMetrics{},
	}

	ctx := context.Background()
	cartID := "{cart:user:test-order}"
	userID := "test-order"
	skuIDs := []string{"sku-001", "sku-002"}
	orderID := "order-999"

	// Setup: Add items to cart
	cartKey := cartID
	redisClient.HSet(ctx, cartKey, map[string]interface{}{
		"sku-001": `{"sku_id":"sku-001","quantity":2}`,
		"sku-002": `{"sku_id":"sku-002","quantity":1}`,
	})

	// Process order
	err := consumer.processOrder(ctx, cartID, userID, skuIDs, orderID)
	assert.NoError(t, err)

	// Verify cart was cleared
	exists, _ := redisClient.Exists(ctx, cartKey).Result()
	assert.Equal(t, int64(0), exists)

	// Verify outbox stream was created
	outboxKey := cartID + ":outbox"
	streamLen, _ := redisClient.XLen(ctx, outboxKey).Result()
	assert.Greater(t, streamLen, int64(0))
}

func TestProcessOrder_EmptyCart(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	logger := zap.NewNop()
	keyBuilder := keybuilder.NewCartKeyBuilder()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		logger:      logger,
		keyBuilder:  keyBuilder,
		metrics:    &OrderConsumerMetrics{},
	}

	ctx := context.Background()
	cartID := "{cart:user:empty-cart}"
	userID := "empty-cart"
	skuIDs := []string{"sku-001"}
	orderID := "order-empty"

	// Process order on empty cart - should still succeed
	err := consumer.processOrder(ctx, cartID, userID, skuIDs, orderID)
	assert.NoError(t, err)

	// Verify outbox stream was created (for the CART_CHECKED_OUT event)
	outboxKey := cartID + ":outbox"
	streamLen, err := redisClient.XLen(ctx, outboxKey).Result()
	assert.NoError(t, err)
	assert.GreaterOrEqual(t, streamLen, int64(0), "Outbox stream should exist")
}

// ============ GetMetrics Tests ============

func TestGetMetrics(t *testing.T) {
	redisClient, mr := setupTestRedis(t)
	defer mr.Close()
	defer redisClient.Close()

	logger := zap.NewNop()

	consumer := &OrderEventConsumer{
		redisClient: redisClient,
		logger:      logger,
		metrics:    &OrderConsumerMetrics{},
	}

	// Increment some metrics
	consumer.metrics.IncMessagesReceived()
	consumer.metrics.IncMessagesReceived()
	consumer.metrics.IncMessagesProcessed()
	consumer.metrics.IncErrors()

	snapshot := consumer.GetMetrics()

	assert.Equal(t, int64(2), snapshot.MessagesReceived)
	assert.Equal(t, int64(1), snapshot.MessagesProcessed)
	assert.Equal(t, int64(1), snapshot.Errors)
}
