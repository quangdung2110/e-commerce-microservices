package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
)

func setupTestRedis(t *testing.T) (*redis.Client, *miniredis.Miniredis) {
	mr, err := miniredis.Run()
	require.NoError(t, err)

	client := redis.NewClient(&redis.Options{
		Addr: mr.Addr(),
	})

	return client, mr
}

func TestCartOperations_AddUpdate(t *testing.T) {
	client, mr := setupTestRedis(t)
	defer mr.Close()
	defer client.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	ops := NewCartOperations(client, keyBuilder, 100, 50, 604800)

	ctx := context.Background()

	// Load scripts
	err := ops.LoadScripts(ctx)
	require.NoError(t, err)

	t.Run("Add new item", func(t *testing.T) {
		userID := "user123"
		skuID := "sku-001"
		quantity := 2
		eventTimeMs := time.Now().UnixMilli()

		payload := &entity.CartItemPayload{
			SkuID:          skuID,
			ProductID:      "prod-001",
			Quantity:       quantity,
			UnitPriceAtAdd: 150000,
			ProductName:    "Test Product",
			VariantInfo:    "Size M",
			ImageURL:       "https://example.com/img.jpg",
			IsAvailable:    true,
			AddedAt:        strconv.FormatInt(eventTimeMs, 10),
			UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
		}

		payloadJSON, err := payload.ToJSON()
		require.NoError(t, err)

		result, _, err := ops.AddOrUpdateItem(ctx, userID, skuID, payloadJSON, int64(quantity), eventTimeMs)
		require.NoError(t, err)
		assert.NotEmpty(t, result)

		// Verify item was added to Redis
		cart, err := ops.GetCart(ctx, userID)
		require.NoError(t, err)
		assert.Contains(t, cart, skuID)
	})

	t.Run("Update existing item", func(t *testing.T) {
		userID := "user123"
		skuID := "sku-002"
		eventTimeMs := time.Now().UnixMilli()

		// First add item
		payload := &entity.CartItemPayload{
			SkuID:          skuID,
			ProductID:      "prod-002",
			Quantity:       1,
			UnitPriceAtAdd: 200000,
			ProductName:    "Test Product 2",
			VariantInfo:    "Size L",
			ImageURL:       "https://example.com/img2.jpg",
			IsAvailable:    true,
			AddedAt:        strconv.FormatInt(eventTimeMs, 10),
			UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
		}

		payloadJSON, err := payload.ToJSON()
		require.NoError(t, err)

		_, _, err = ops.AddOrUpdateItem(ctx, userID, skuID, payloadJSON, 1, eventTimeMs)
		require.NoError(t, err)

		// Now update with +2 quantity
		newTimeMs := eventTimeMs + 1000
		payload.Quantity = 1 // This will be overwritten with accumulated value
		payload.UpdatedAt = strconv.FormatInt(newTimeMs, 10)
		payloadJSON, _ = payload.ToJSON()

		result, _, err := ops.AddOrUpdateItem(ctx, userID, skuID, payloadJSON, 2, newTimeMs)
		require.NoError(t, err)
		assert.NotEmpty(t, result)

		// Verify quantity was accumulated
		cart, err := ops.GetCart(ctx, userID)
		require.NoError(t, err)
		itemJSON, exists := cart[skuID]
		assert.True(t, exists)

		var item entity.CartItemPayload
		err = json.Unmarshal([]byte(itemJSON), &item)
		require.NoError(t, err)
		assert.Equal(t, 3, item.Quantity) // 1 + 2 = 3
	})

	t.Run("Cart Full error", func(t *testing.T) {
		userID := "user_full_test"

		// Add items until cart is full (max 50)
		eventTimeMs := time.Now().UnixMilli()
		for i := 0; i < 50; i++ {
			payload := &entity.CartItemPayload{
				SkuID:          fmt.Sprintf("sku-full-%d", i),
				ProductID:      fmt.Sprintf("prod-%d", i),
				Quantity:       1,
				UnitPriceAtAdd: 100000,
				ProductName:    "Product",
				VariantInfo:    "",
				ImageURL:      "",
				IsAvailable:    true,
				AddedAt:       strconv.FormatInt(eventTimeMs, 10),
				UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
			}
			payloadJSON, _ := payload.ToJSON()
			_, _, err := ops.AddOrUpdateItem(ctx, userID, payload.SkuID, payloadJSON, 1, eventTimeMs)
			require.NoError(t, err)
		}

		// Try to add 51st item - should fail
		payload := &entity.CartItemPayload{
			SkuID:          "sku-overflow",
			ProductID:      "prod-overflow",
			Quantity:       1,
			UnitPriceAtAdd: 100000,
			ProductName:    "Overflow Product",
			VariantInfo:    "",
			ImageURL:      "",
			IsAvailable:    true,
			AddedAt:       strconv.FormatInt(eventTimeMs, 10),
			UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
		}
		payloadJSON, _ := payload.ToJSON()

		_, _, err := ops.AddOrUpdateItem(ctx, userID, "sku-overflow", payloadJSON, 1, eventTimeMs)
		assert.Error(t, err)
		assert.Equal(t, ErrCartFull, err)
	})

	t.Run("Max quantity capped at 100", func(t *testing.T) {
		userID := "user_qty_test"
		skuID := "sku-qty-100"
		eventTimeMs := time.Now().UnixMilli()

		// First add item with quantity 90
		payload := &entity.CartItemPayload{
			SkuID:          skuID,
			ProductID:      "prod-qty",
			Quantity:       90,
			UnitPriceAtAdd: 100000,
			ProductName:    "Qty Test",
			VariantInfo:    "",
			ImageURL:      "",
			IsAvailable:    true,
			AddedAt:       strconv.FormatInt(eventTimeMs, 10),
			UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
		}
		payloadJSON, _ := payload.ToJSON()

		_, _, err := ops.AddOrUpdateItem(ctx, userID, skuID, payloadJSON, 90, eventTimeMs)
		require.NoError(t, err)

		// Now add +20, should cap at 100
		newPayload := &entity.CartItemPayload{
			SkuID:          skuID,
			ProductID:      "prod-qty",
			Quantity:       20, // This will be accumulated
			UnitPriceAtAdd: 100000,
			ProductName:    "Qty Test",
			VariantInfo:    "",
			ImageURL:      "",
			IsAvailable:    true,
			AddedAt:       strconv.FormatInt(eventTimeMs, 10),
			UpdatedAt:      strconv.FormatInt(eventTimeMs+1000, 10),
		}
		newPayloadJSON, _ := newPayload.ToJSON()

		_, _, err = ops.AddOrUpdateItem(ctx, userID, skuID, newPayloadJSON, 20, eventTimeMs+1000)
		require.NoError(t, err)

		// Verify quantity was capped
		cart, err := ops.GetCart(ctx, userID)
		require.NoError(t, err)
		itemJSON := cart[skuID]

		var item entity.CartItemPayload
		json.Unmarshal([]byte(itemJSON), &item)
		assert.Equal(t, 100, item.Quantity) // Capped at 100
	})

	t.Run("Cannot decrement non-existing item", func(t *testing.T) {
		userID := "user_no_decrement"
		skuID := "sku-nonexistent"
		eventTimeMs := time.Now().UnixMilli()

		payload := &entity.CartItemPayload{
			SkuID:          skuID,
			ProductID:      "prod-none",
			Quantity:       1,
			UnitPriceAtAdd: 100000,
			ProductName:    "Nonexistent",
			VariantInfo:    "",
			ImageURL:      "",
			IsAvailable:    true,
			AddedAt:       strconv.FormatInt(eventTimeMs, 10),
			UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
		}
		payloadJSON, _ := payload.ToJSON()

		// Try to decrement non-existing item
		_, _, err := ops.AddOrUpdateItem(ctx, userID, skuID, payloadJSON, -1, eventTimeMs)
		assert.Error(t, err)
		assert.Equal(t, ErrCannotDecrementNonExisting, err)
	})
}

func TestCartOperations_BatchDelete(t *testing.T) {
	client, mr := setupTestRedis(t)
	defer mr.Close()
	defer client.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	ops := NewCartOperations(client, keyBuilder, 100, 50, 604800)

	ctx := context.Background()
	err := ops.LoadScripts(ctx)
	require.NoError(t, err)

	t.Run("Delete existing items", func(t *testing.T) {
		userID := "user_delete_test"
		eventTimeMs := time.Now().UnixMilli()

		// First add items
		skuIDs := []string{"sku-del-1", "sku-del-2", "sku-del-3"}
		for _, skuID := range skuIDs {
			payload := &entity.CartItemPayload{
				SkuID:          skuID,
				ProductID:      "prod-del",
				Quantity:       1,
				UnitPriceAtAdd: 100000,
				ProductName:    "Delete Test",
				VariantInfo:    "",
				ImageURL:      "",
				IsAvailable:    true,
				AddedAt:       strconv.FormatInt(eventTimeMs, 10),
				UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
			}
			payloadJSON, _ := payload.ToJSON()
			_, _, err := ops.AddOrUpdateItem(ctx, userID, skuID, payloadJSON, 1, eventTimeMs)
			require.NoError(t, err)
		}

		// Delete 2 items
		deletedCount, _, err := ops.BatchDeleteItems(ctx, userID, skuIDs[:2], "CART_CLEARED", eventTimeMs+1000)
		require.NoError(t, err)
		assert.Equal(t, int64(2), deletedCount)

		// Verify items were deleted
		cart, err := ops.GetCart(ctx, userID)
		require.NoError(t, err)
		assert.NotContains(t, cart, "sku-del-1")
		assert.NotContains(t, cart, "sku-del-2")
		assert.Contains(t, cart, "sku-del-3")
	})

	t.Run("Empty cart when all items deleted", func(t *testing.T) {
		userID := "user_empty_test"
		eventTimeMs := time.Now().UnixMilli()

		// Add single item
		payload := &entity.CartItemPayload{
			SkuID:          "sku-only",
			ProductID:      "prod-only",
			Quantity:       1,
			UnitPriceAtAdd: 100000,
			ProductName:    "Only Item",
			VariantInfo:    "",
			ImageURL:      "",
			IsAvailable:    true,
			AddedAt:       strconv.FormatInt(eventTimeMs, 10),
			UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
		}
		payloadJSON, _ := payload.ToJSON()
		_, _, err := ops.AddOrUpdateItem(ctx, userID, "sku-only", payloadJSON, 1, eventTimeMs)
		require.NoError(t, err)

		// Delete the only item
		deletedCount, _, err := ops.BatchDeleteItems(ctx, userID, []string{"sku-only"}, "CART_CLEARED", eventTimeMs+1000)
		require.NoError(t, err)
		assert.Equal(t, int64(1), deletedCount)

		// Verify cart is empty (key deleted)
		cart, err := ops.GetCart(ctx, userID)
		require.NoError(t, err)
		assert.Empty(t, cart)
	})
}

func TestCartOperations_SilentRehydrate(t *testing.T) {
	client, mr := setupTestRedis(t)
	defer mr.Close()
	defer client.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	ops := NewCartOperations(client, keyBuilder, 100, 50, 604800)

	ctx := context.Background()
	err := ops.LoadScripts(ctx)
	require.NoError(t, err)

	t.Run("Rehydrate items to empty cart", func(t *testing.T) {
		userID := "user_rehydrate"
		eventTimeMs := time.Now().UnixMilli()

		items := []entity.RehydrateItem{
			{
				SkuID: "sku-rehyd-1",
				Value: mustMarshal(entity.CartItemPayload{
					SkuID:          "sku-rehyd-1",
					ProductID:      "prod-1",
					Quantity:       2,
					UnitPriceAtAdd: 150000,
					ProductName:    "Rehydrated Product 1",
					VariantInfo:    "Size M",
					ImageURL:      "https://example.com/1.jpg",
					IsAvailable:    true,
					AddedAt:       strconv.FormatInt(eventTimeMs, 10),
					UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
				}),
			},
			{
				SkuID: "sku-rehyd-2",
				Value: mustMarshal(entity.CartItemPayload{
					SkuID:          "sku-rehyd-2",
					ProductID:      "prod-2",
					Quantity:       3,
					UnitPriceAtAdd: 200000,
					ProductName:    "Rehydrated Product 2",
					VariantInfo:    "Size L",
					ImageURL:      "https://example.com/2.jpg",
					IsAvailable:    true,
					AddedAt:       strconv.FormatInt(eventTimeMs, 10),
					UpdatedAt:      strconv.FormatInt(eventTimeMs, 10),
				}),
			},
		}

		err := ops.SilentRehydrate(ctx, userID, items)
		require.NoError(t, err)

		// Verify items were rehydrated
		cart, err := ops.GetCart(ctx, userID)
		require.NoError(t, err)
		assert.Len(t, cart, 2)
		assert.Contains(t, cart, "sku-rehyd-1")
		assert.Contains(t, cart, "sku-rehyd-2")
	})

	t.Run("Skip older items during rehydrate", func(t *testing.T) {
		userID := "user_skip_old"
		oldTimeMs := time.Now().Add(-1 * time.Hour).UnixMilli()
		newTimeMs := time.Now().UnixMilli()

		// Add newer item first
		newerPayload := &entity.CartItemPayload{
			SkuID:          "sku-time",
			ProductID:      "prod-time",
			Quantity:       5,
			UnitPriceAtAdd: 100000,
			ProductName:    "Newer Item",
			VariantInfo:    "",
			ImageURL:      "",
			IsAvailable:    true,
			AddedAt:       strconv.FormatInt(newTimeMs, 10),
			UpdatedAt:      strconv.FormatInt(newTimeMs, 10),
		}
		payloadJSON, _ := newerPayload.ToJSON()
		_, _, err := ops.AddOrUpdateItem(ctx, userID, "sku-time", payloadJSON, 5, newTimeMs)
		require.NoError(t, err)

		// Try to rehydrate with older data
		items := []entity.RehydrateItem{
			{
				SkuID: "sku-time",
				Value: mustMarshal(entity.CartItemPayload{
					SkuID:          "sku-time",
					ProductID:      "prod-time",
					Quantity:       2, // Old quantity
					UnitPriceAtAdd: 100000,
					ProductName:    "Older Item",
					VariantInfo:    "",
					ImageURL:      "",
					IsAvailable:    true,
					AddedAt:       strconv.FormatInt(oldTimeMs, 10),
					UpdatedAt:      strconv.FormatInt(oldTimeMs, 10),
				}),
			},
		}

		err = ops.SilentRehydrate(ctx, userID, items)
		require.NoError(t, err)

		// Verify newer item was NOT overwritten
		cart, err := ops.GetCart(ctx, userID)
		require.NoError(t, err)

		var item entity.CartItemPayload
		json.Unmarshal([]byte(cart["sku-time"]), &item)
		assert.Equal(t, 5, item.Quantity) // Should remain 5, not overwritten with 2
	})
}

func TestNegativeCache(t *testing.T) {
	client, mr := setupTestRedis(t)
	defer mr.Close()
	defer client.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	negCache := NewNegativeCacheManager(client, keyBuilder, 60)

	ctx := context.Background()

	t.Run("Cache miss returns false", func(t *testing.T) {
		hasCache, err := negCache.HasCachedResult(ctx, "user123")
		require.NoError(t, err)
		assert.False(t, hasCache)
	})

	t.Run("Set and check not found cache", func(t *testing.T) {
		userID := "user_notfound"

		// Set not found cache
		err := negCache.SetNotFound(ctx, userID)
		require.NoError(t, err)

		// Check cache exists
		hasCache, err := negCache.HasCachedResult(ctx, userID)
		require.NoError(t, err)
		assert.True(t, hasCache)
	})

	t.Run("Invalidate clears cache", func(t *testing.T) {
		userID := "user_invalidate"

		// Set cache
		err := negCache.SetNotFound(ctx, userID)
		require.NoError(t, err)

		// Invalidate
		err = negCache.Invalidate(ctx, userID)
		require.NoError(t, err)

		// Check cache is gone
		hasCache, err := negCache.HasCachedResult(ctx, userID)
		require.NoError(t, err)
		assert.False(t, hasCache)
	})
}

func TestIdempotencyManager(t *testing.T) {
	client, mr := setupTestRedis(t)
	defer mr.Close()
	defer client.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	idempotency := NewIdempotencyManager(client, keyBuilder)

	ctx := context.Background()

	t.Run("First call acquires lock", func(t *testing.T) {
		orderID := "order_123"

		acquired, err := idempotency.CheckAndLock(ctx, orderID)
		require.NoError(t, err)
		assert.True(t, acquired)
	})

	t.Run("Second call fails to acquire lock", func(t *testing.T) {
		orderID := "order_456"

		// First acquires
		acquired1, err := idempotency.CheckAndLock(ctx, orderID)
		require.NoError(t, err)
		assert.True(t, acquired1)

		// Second fails
		acquired2, err := idempotency.CheckAndLock(ctx, orderID)
		require.NoError(t, err)
		assert.False(t, acquired2)
	})

	t.Run("MarkDone clears lock", func(t *testing.T) {
		orderID := "order_done"

		// Acquire lock
		acquired, err := idempotency.CheckAndLock(ctx, orderID)
		require.NoError(t, err)
		assert.True(t, acquired)

		// Mark done
		err = idempotency.MarkDone(ctx, orderID)
		require.NoError(t, err)

		// Can acquire again
		acquired, err = idempotency.CheckAndLock(ctx, orderID)
		require.NoError(t, err)
		assert.True(t, acquired)
	})

	t.Run("IsProcessed returns true after done", func(t *testing.T) {
		orderID := "order_processed"

		// Mark done
		err := idempotency.MarkDone(ctx, orderID)
		require.NoError(t, err)

		// Check is processed
		processed, err := idempotency.IsProcessed(ctx, orderID)
		require.NoError(t, err)
		assert.True(t, processed)
	})
}

func TestIntentIndexManager(t *testing.T) {
	client, mr := setupTestRedis(t)
	defer mr.Close()
	defer client.Close()

	keyBuilder := keybuilder.NewCartKeyBuilder()
	intentIdx := NewIntentIndexManager(client, keyBuilder)

	ctx := context.Background()

	t.Run("PreRegisterIntent and ClearIntent", func(t *testing.T) {
		streamKey := "{cart:user:intent_test}:outbox"

		// Pre-register
		err := intentIdx.PreRegisterIntent(ctx, streamKey)
		require.NoError(t, err)

		// Get active streams
		streams, err := intentIdx.GetActiveStreams(ctx)
		require.NoError(t, err)
		assert.Contains(t, streams, streamKey)

		// Clear
		err = intentIdx.ClearIntent(ctx, streamKey)
		require.NoError(t, err)

		// Verify cleared
		streams, err = intentIdx.GetActiveStreams(ctx)
		require.NoError(t, err)
		assert.NotContains(t, streams, streamKey)
	})

	t.Run("MarkActive notifies and adds to active", func(t *testing.T) {
		streamKey := "{cart:user:active_test}:outbox"

		// Pre-register first
		err := intentIdx.PreRegisterIntent(ctx, streamKey)
		require.NoError(t, err)

		// Mark active
		err = intentIdx.MarkActive(ctx, streamKey)
		require.NoError(t, err)

		// Verify in active streams
		streams, err := intentIdx.GetActiveOutboxStreams(ctx)
		require.NoError(t, err)
		assert.Contains(t, streams, streamKey)
	})

	t.Run("GetStaleIntents", func(t *testing.T) {
		streamKey := "{cart:user:stale_test}:outbox"

		// Pre-register
		err := intentIdx.PreRegisterIntent(ctx, streamKey)
		require.NoError(t, err)

		// Wait a bit
		time.Sleep(100 * time.Millisecond)

		// Get stale intents (older than 50ms)
		stale, err := intentIdx.GetStaleIntents(ctx, 50*time.Millisecond)
		require.NoError(t, err)
		assert.Contains(t, stale, streamKey)
	})
}

func TestKeyBuilder(t *testing.T) {
	kb := keybuilder.NewCartKeyBuilder()

	t.Run("BuildCartKey with Hash Tag", func(t *testing.T) {
		key, err := kb.BuildCartKey("12345")
		require.NoError(t, err)
		assert.Equal(t, "{cart:user:12345}", key)
		assert.True(t, kb.IsValidCartKey(key))
	})

	t.Run("BuildOutboxStreamKey", func(t *testing.T) {
		key, err := kb.BuildOutboxStreamKey("12345")
		require.NoError(t, err)
		assert.Equal(t, "{cart:user:12345}:outbox", key)
	})

	t.Run("ExtractUserIDFromCartID", func(t *testing.T) {
		userID, err := kb.ExtractUserIDFromCartID("{cart:user:12345}")
		require.NoError(t, err)
		assert.Equal(t, "12345", userID)
	})

	t.Run("BuildIdempotencyKey", func(t *testing.T) {
		key := kb.BuildIdempotencyKey("order-123")
		assert.Contains(t, key, "order-123")
		assert.Contains(t, key, "PROCESSING")
	})

	t.Run("BuildIdempotencyDoneKey", func(t *testing.T) {
		key := kb.BuildIdempotencyDoneKey("order-456")
		assert.Contains(t, key, "order-456")
		assert.Contains(t, key, "DONE")
	})
}

// Helper function
func mustMarshal(v interface{}) string {
	data, _ := json.Marshal(v)
	return string(data)
}
