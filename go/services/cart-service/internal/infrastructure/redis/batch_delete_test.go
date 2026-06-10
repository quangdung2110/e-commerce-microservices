package redis

import (
	"context"
	"encoding/json"
	"testing"
)

// --- BatchDelete Tests ---

func TestBatchDelete_RemovesSkus(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Pre-populate cart
	for _, sku := range []string{"sku-001", "sku-002", "sku-003"} {
		itemJSON := buildItemJSON(sku, 1, "1708530000")
		_, _ = repo.AddOrUpdateItem(context.Background(),
			cartKey, streamShard,
			sku, itemJSON, 100, 50, 1, 1708530000000, "user1")
	}

	skuIDsJSON := `["sku-001","sku-002"]`
	count, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CLEARED",
		"user1", skuIDsJSON,
	)

	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}
	if count != 2 {
		t.Errorf("expected 2 deleted, got %d", count)
	}

	// Verify remaining item
	val, err := rc.HGet(context.Background(), cartKey, "sku-003").Result()
	if err != nil {
		t.Fatalf("HGet failed: %v", err)
	}
	if val == "" {
		t.Error("sku-003 should still exist")
	}

	// Verify deleted items are gone
	exists, _ := rc.HExists(context.Background(), cartKey, "sku-001").Result()
	if exists {
		t.Error("sku-001 should have been deleted")
	}
}

func TestBatchDelete_CartEmptied(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Add single item
	itemJSON := buildItemJSON("sku-001", 1, "1708530000")
	_, _ = repo.AddOrUpdateItem(context.Background(),
		cartKey, streamShard,
		"sku-001", itemJSON, 100, 50, 1, 1708530000000, "user1")

	// Delete the only item
	skuIDsJSON := `["sku-001"]`
	count, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CLEARED",
		"user1", skuIDsJSON,
	)

	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}
	if count != 1 {
		t.Errorf("expected 1 deleted, got %d", count)
	}

	// Cart key should be deleted
	exists, _ := rc.Exists(context.Background(), cartKey).Result()
	if exists != 0 {
		t.Error("cart key should be deleted when empty")
	}
}

func TestBatchDelete_EmitsDeletedEvents(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Pre-populate
	for _, sku := range []string{"sku-001", "sku-002"} {
		itemJSON := buildItemJSON(sku, 1, "1708530000")
		_, _ = repo.AddOrUpdateItem(context.Background(),
			cartKey, streamShard,
			sku, itemJSON, 100, 50, 1, 1708530000000, "user1")
	}

	skuIDsJSON := `["sku-001","sku-002"]`
	_, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CLEARED",
		"user1", skuIDsJSON,
	)
	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}

	streams, _ := rc.XRange(context.Background(), streamShard, "-", "+").Result()

	// Should have 2 CART_ITEM_DELETED + 1 CART_CLEARED = 3 events (plus 2 CART_ITEM_ADDED from setup)
	if len(streams) < 3 {
		t.Errorf("expected at least 3 events in stream, got %d", len(streams))
	}

	// Check for CART_CLEARED event
	hasCleared := false
	for _, s := range streams {
		if s.Values["event_type"] == "CART_CLEARED" {
			hasCleared = true
			break
		}
	}
	if !hasCleared {
		t.Error("expected CART_CLEARED event")
	}
}

func TestBatchDelete_EmitsCheckedOutEvent(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	itemJSON := buildItemJSON("sku-001", 1, "1708530000")
	_, _ = repo.AddOrUpdateItem(context.Background(),
		cartKey, streamShard,
		"sku-001", itemJSON, 100, 50, 1, 1708530000000, "user1")

	skuIDsJSON := `["sku-001"]`
	_, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CHECKED_OUT",
		"user1", skuIDsJSON,
	)
	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}

	streams, _ := rc.XRange(context.Background(), streamShard, "-", "+").Result()
	hasCheckedOut := false
	for _, s := range streams {
		if s.Values["event_type"] == "CART_CHECKED_OUT" {
			hasCheckedOut = true
			break
		}
	}
	if !hasCheckedOut {
		t.Error("expected CART_CHECKED_OUT event")
	}
}

func TestBatchDelete_InvalidJSON(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	_, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CLEARED",
		"user1", "not valid json",
	)

	if err == nil {
		t.Error("expected error for invalid JSON")
	}
}

func TestBatchDelete_EmptyArray(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	count, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CLEARED",
		"user1", "[]",
	)

	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}
	if count != 0 {
		t.Errorf("expected 0 deleted, got %d", count)
	}
}

func TestBatchDelete_NonExistentSku(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Add one item
	itemJSON := buildItemJSON("sku-001", 1, "1708530000")
	_, _ = repo.AddOrUpdateItem(context.Background(),
		cartKey, streamShard,
		"sku-001", itemJSON, 100, 50, 1, 1708530000000, "user1")

	// Try to delete non-existent SKU
	skuIDsJSON := `["nonexistent-sku"]`
	count, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CLEARED",
		"user1", skuIDsJSON,
	)

	if err != nil {
		t.Fatalf("BatchDelete should not error for non-existent SKU: %v", err)
	}
	if count != 0 {
		t.Errorf("expected 0 deleted (non-existent SKU), got %d", count)
	}
}

func TestBatchDelete_PartialDelete(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	// Add items
	for _, sku := range []string{"sku-001", "sku-002"} {
		itemJSON := buildItemJSON(sku, 1, "1708530000")
		_, _ = repo.AddOrUpdateItem(context.Background(),
			cartKey, streamShard,
			sku, itemJSON, 100, 50, 1, 1708530000000, "user1")
	}

	// Delete one existing, one non-existent
	skuIDsJSON := `["sku-001","nonexistent"]`
	count, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CLEARED",
		"user1", skuIDsJSON,
	)

	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}
	if count != 1 {
		t.Errorf("expected 1 deleted (only existing SKU), got %d", count)
	}

	// sku-002 should remain
	val, err := rc.HGet(context.Background(), cartKey, "sku-002").Result()
	if err != nil {
		t.Fatalf("HGet failed: %v", err)
	}
	if val == "" {
		t.Error("sku-002 should still exist")
	}
}

func TestBatchDelete_EventPayloadFormat(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	itemJSON := buildItemJSON("sku-001", 1, "1708530000")
	_, _ = repo.AddOrUpdateItem(context.Background(),
		cartKey, streamShard,
		"sku-001", itemJSON, 100, 50, 1, 1708530000000, "user1")

	skuIDsJSON := `["sku-001"]`
	_, err := repo.BatchDelete(
		context.Background(),
		cartKey, streamShard,
		1708616400000, "CART_CLEARED",
		"user1", skuIDsJSON,
	)
	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}

	streams, _ := rc.XRange(context.Background(), streamShard, "-", "+").Result()
	if len(streams) < 2 {
		t.Fatalf("expected at least 2 events, got %d", len(streams))
	}

	// Find the CART_ITEM_DELETED event (not the CART_ITEM_ADDED from setup)
	var deletedEvent map[string]interface{}
	for _, s := range streams {
		if s.Values["event_type"] == "CART_ITEM_DELETED" {
			deletedEvent = s.Values
			break
		}
	}
	if deletedEvent == nil {
		t.Fatal("expected CART_ITEM_DELETED event not found")
	}

	payloadStr, ok := deletedEvent["payload"].(string)
	if !ok {
		t.Fatal("payload should be string")
	}

	var payload map[string]interface{}
	json.Unmarshal([]byte(payloadStr), &payload)
	if payload["sku_id"] != "sku-001" {
		t.Errorf("payload sku_id = %v, want sku-001", payload["sku_id"])
	}
	if payload["cart_id"] != cartKey {
		t.Errorf("payload cart_id = %v, want %s", payload["cart_id"], cartKey)
	}
}
