package redis

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"cart-service/internal/domain/port"
)

// --- Helper functions ---

func setupMiniRedis(t *testing.T) (*miniredis.Miniredis, *redis.Client) {
	t.Helper()
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("failed to start miniredis: %v", err)
	}
	rc := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	return mr, rc
}

func teardownMiniRedis(mr *miniredis.Miniredis, rc *redis.Client) {
	rc.Close()
	mr.Close()
}

func buildItemJSON(skuID string, quantity int, timestamp string) string {
	item := map[string]interface{}{
		"sku_id":         skuID,
		"product_id":     "prod-1",
		"quantity":       quantity,
		"unit_price_at_add": 150000.0,
		"product_name":   "Test Product",
		"is_available":   true,
		"added_at":       timestamp,
		"updated_at":      timestamp,
	}
	b, _ := json.Marshal(item)
	return string(b)
}

// --- AddOrUpdate Tests ---

func TestAddOrUpdateScript_NewItem(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"
	itemJSON := buildItemJSON("sku1", 2, "1708530000")

	result, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON,
		100, 50, 2, 1708530000000, "user1",
	)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var resultItem map[string]interface{}
	json.Unmarshal([]byte(result), &resultItem)

	if resultItem["quantity"].(float64) != 2 {
		t.Errorf("expected quantity 2, got %v", resultItem["quantity"])
	}
	if resultItem["sku_id"] != "sku1" {
		t.Errorf("expected sku_id 'sku1', got %v", resultItem["sku_id"])
	}
}

func TestAddOrUpdateScript_UpdateExistingItem(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Add initial item with quantity 2
	itemJSON1 := buildItemJSON("sku1", 2, "1708530000")
	_, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON1,
		100, 50, 2, 1708530000000, "user1",
	)
	if err != nil {
		t.Fatalf("failed to add initial item: %v", err)
	}

	// Update with additional quantity 3 (step=3)
	itemJSON2 := buildItemJSON("sku1", 5, "1708530001")
	result, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON2,
		100, 50, 3, 1708530001000, "user1",
	)
	if err != nil {
		t.Fatalf("failed to update item: %v", err)
	}

	var resultItem map[string]interface{}
	json.Unmarshal([]byte(result), &resultItem)

	if resultItem["quantity"].(float64) != 5 {
		t.Errorf("expected quantity 5 after accumulation, got %v", resultItem["quantity"])
	}
}

func TestAddOrUpdateScript_CartFull(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Fill cart to max (maxSkus = 2 for test)
	for i := 1; i <= 2; i++ {
		sku := "sku" + string(rune('0'+i))
		itemJSON := buildItemJSON(sku, 1, "1708530000")
		_, err := repo.AddOrUpdateItem(
			context.Background(),
			cartKey, streamShard,
			sku, itemJSON,
			100, 2, 1, 1708530000000, "user1",
		)
		if err != nil {
			t.Fatalf("setup failed: %v", err)
		}
	}

	// Try to add 3rd item — should fail with ErrCartFull
	itemJSON := buildItemJSON("sku3", 1, "1708530000")
	_, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku3", itemJSON,
		100, 2, 1, 1708530000000, "user1",
	)

	if err == nil {
		t.Fatal("expected ErrCartFull, got nil")
	}
	if err != port.ErrCartFull {
		t.Errorf("expected ErrCartFull, got %v", err)
	}
}

func TestAddOrUpdateScript_StepZero(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"
	itemJSON := buildItemJSON("sku1", 0, "1708530000")

	_, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON,
		100, 50, 0, 1708530000000, "user1",
	)

	if err == nil {
		t.Fatal("expected error for step=0, got nil")
	}
}

func TestAddOrUpdateScript_DecrementNonExisting(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"
	itemJSON := buildItemJSON("nonexistent", 1, "1708530000")

	_, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"nonexistent", itemJSON,
		100, 50, -1, 1708530000000, "user1",
	)

	if err == nil {
		t.Fatal("expected error for decrement non-existing, got nil")
	}
}

func TestAddOrUpdateScript_QuantityCapAtMax(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"
	maxQty := 10

	// Add item with quantity 10 (max)
	itemJSON := buildItemJSON("sku1", maxQty, "1708530000")
	_, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON,
		maxQty, 50, maxQty, 1708530000000, "user1",
	)
	if err != nil {
		t.Fatalf("failed to add item: %v", err)
	}

	// Try to add more than max
	itemJSON2 := buildItemJSON("sku1", 5, "1708530001")
	result, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON2,
		maxQty, 50, 5, 1708530001000, "user1",
	)
	if err != nil {
		t.Fatalf("should not error on quantity cap: %v", err)
	}

	var resultItem map[string]interface{}
	json.Unmarshal([]byte(result), &resultItem)

	if resultItem["quantity"].(float64) != float64(maxQty) {
		t.Errorf("quantity should be capped at %d, got %v", maxQty, resultItem["quantity"])
	}
}

func TestAddOrUpdateScript_RaceCondition(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Simulate 10 concurrent "add 1" requests
	done := make(chan bool, 10)
	for i := 0; i < 10; i++ {
		go func() {
			itemJSON := buildItemJSON("sku1", 1, "1708530000")
			repo.AddOrUpdateItem(
				context.Background(),
				cartKey, streamShard,
				"sku1", itemJSON,
				100, 50, 1, 1708530000000, "user1",
			)
			done <- true
		}()
	}

	for i := 0; i < 10; i++ {
		<-done
	}

	// Final quantity should be exactly 10 (not less due to race)
	val, err := rc.HGet(context.Background(), cartKey, "sku1").Result()
	if err != nil {
		t.Fatalf("HGET failed: %v", err)
	}

	var item map[string]interface{}
	json.Unmarshal([]byte(val), &item)
	if item["quantity"].(float64) != 10 {
		t.Errorf("expected quantity 10 after race, got %v", item["quantity"])
	}
}

func TestAddOrUpdateScript_AddsToStream(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"
	itemJSON := buildItemJSON("sku1", 2, "1708530000")

	_, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON,
		100, 50, 2, 1708530000000, "user1",
	)
	if err != nil {
		t.Fatalf("AddOrUpdateItem failed: %v", err)
	}

	// Verify event was published to Redis Stream
	streamLen, err := rc.XLen(context.Background(), streamShard).Result()
	if err != nil {
		t.Fatalf("XLen failed: %v", err)
	}
	if streamLen != 1 {
		t.Errorf("expected 1 event in stream, got %d", streamLen)
	}
}

func TestAddOrUpdateScript_AddedEventType(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"
	itemJSON := buildItemJSON("sku1", 2, "1708530000")

	_, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON,
		100, 50, 2, 1708530000000, "user1",
	)
	if err != nil {
		t.Fatalf("AddOrUpdateItem failed: %v", err)
	}

	streams, err := rc.XRange(context.Background(), streamShard, "-", "+").Result()
	if err != nil {
		t.Fatalf("XRange failed: %v", err)
	}
	if len(streams) != 1 {
		t.Fatalf("expected 1 stream entry, got %d", len(streams))
	}

	eventType := streams[0].Values["event_type"].(string)
	if eventType != "CART_ITEM_ADDED" {
		t.Errorf("expected CART_ITEM_ADDED, got %s", eventType)
	}
}

func TestAddOrUpdateScript_UpdatedEventType(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Add initial item
	itemJSON1 := buildItemJSON("sku1", 2, "1708530000")
	_, _ = repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON1,
		100, 50, 2, 1708530000000, "user1",
	)

	// Update item
	itemJSON2 := buildItemJSON("sku1", 5, "1708530001")
	_, _ = repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON2,
		100, 50, 3, 1708530001000, "user1",
	)

	streams, err := rc.XRange(context.Background(), streamShard, "-", "+").Result()
	if err != nil {
		t.Fatalf("XRange failed: %v", err)
	}
	if len(streams) != 2 {
		t.Fatalf("expected 2 stream entries, got %d", len(streams))
	}

	secondEventType := streams[1].Values["event_type"].(string)
	if secondEventType != "CART_ITEM_UPDATED" {
		t.Errorf("expected CART_ITEM_UPDATED for second event, got %s", secondEventType)
	}
}

func TestAddOrUpdateScript_TTLSet(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"
	itemJSON := buildItemJSON("sku1", 2, "1708530000")

	_, _ = repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON,
		100, 50, 2, 1708530000000, "user1",
	)

	ttl, err := rc.TTL(context.Background(), cartKey).Result()
	if err != nil {
		t.Fatalf("TTL failed: %v", err)
	}
	if ttl <= 0 {
		t.Error("TTL should be set (> 0)")
	}
}

func TestAddOrUpdateScript_CanUpdateExistingSkuWhenCartFull(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Fill cart
	for i := 1; i <= 2; i++ {
		sku := "sku" + string(rune('0'+i))
		itemJSON := buildItemJSON(sku, 1, "1708530000")
		_, _ = repo.AddOrUpdateItem(
			context.Background(),
			cartKey, streamShard,
			sku, itemJSON,
			100, 2, 1, 1708530000000, "user1",
		)
	}

	// Updating existing SKU should succeed even when at capacity
	itemJSON := buildItemJSON("sku1", 5, "1708530001")
	result, err := repo.AddOrUpdateItem(
		context.Background(),
		cartKey, streamShard,
		"sku1", itemJSON,
		100, 2, 4, 1708530001000, "user1",
	)
	if err != nil {
		t.Errorf("updating existing SKU should succeed: %v", err)
	}

	var resultItem map[string]interface{}
	json.Unmarshal([]byte(result), &resultItem)
	if resultItem["quantity"].(float64) != 5 {
		t.Errorf("expected quantity 5, got %v", resultItem["quantity"])
	}
}
