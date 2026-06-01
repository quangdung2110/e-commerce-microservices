package redis

import (
	"context"
	"encoding/json"
	"strconv"
	"testing"
)

// --- Rehydrate Tests ---

func TestRehydrate_NewCart(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"

	items := map[string]string{
		"sku1": `{"product_id":"prod-1","quantity":2,"updated_at":"1708530000"}`,
		"sku2": `{"product_id":"prod-2","quantity":3,"updated_at":"1708530001"}`,
	}
	itemsJSON := buildRehydrateJSON(items)

	err := repo.Rehydrate(context.Background(), cartKey, itemsJSON, 604800)
	if err != nil {
		t.Fatalf("Rehydrate failed: %v", err)
	}

	// Verify items were written
	val1, err := rc.HGet(context.Background(), cartKey, "sku1").Result()
	if err != nil {
		t.Fatalf("HGet sku1 failed: %v", err)
	}
	var item1 map[string]interface{}
	json.Unmarshal([]byte(val1), &item1)
	if item1["product_id"] != "prod-1" {
		t.Errorf("sku1 product_id = %v, want prod-1", item1["product_id"])
	}
	if item1["quantity"].(float64) != 2 {
		t.Errorf("sku1 quantity = %v, want 2", item1["quantity"])
	}
}

func TestRehydrate_TimestampGuard(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"

	// Pre-populate Redis with newer data
	newerItem := `{"product_id":"prod-1","quantity":5,"updated_at":"1708616400"}`
	rc.HSet(context.Background(), cartKey, "sku1", newerItem)

	// Try to rehydrate with older data — should NOT overwrite
	items := map[string]string{
		"sku1": `{"product_id":"prod-1","quantity":3,"updated_at":"1708530000"}`,
	}
	itemsJSON := buildRehydrateJSON(items)
	err := repo.Rehydrate(context.Background(), cartKey, itemsJSON, 604800)
	if err != nil {
		t.Fatalf("Rehydrate failed: %v", err)
	}

	val, _ := rc.HGet(context.Background(), cartKey, "sku1").Result()
	var item map[string]interface{}
	json.Unmarshal([]byte(val), &item)

	if item["quantity"].(float64) != 5 {
		t.Errorf("expected newer quantity 5, got %v (should not overwrite with older)", item["quantity"])
	}
}

func TestRehydrate_OverwritesOlderData(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"

	// Pre-populate Redis with older data
	olderItem := `{"product_id":"prod-1","quantity":3,"updated_at":"1708530000"}`
	rc.HSet(context.Background(), cartKey, "sku1", olderItem)

	// Rehydrate with newer data
	items := map[string]string{
		"sku1": `{"product_id":"prod-1","quantity":5,"updated_at":"1708616400"}`,
	}
	itemsJSON := buildRehydrateJSON(items)
	err := repo.Rehydrate(context.Background(), cartKey, itemsJSON, 604800)
	if err != nil {
		t.Fatalf("Rehydrate failed: %v", err)
	}

	val, _ := rc.HGet(context.Background(), cartKey, "sku1").Result()
	var item map[string]interface{}
	json.Unmarshal([]byte(val), &item)

	if item["quantity"].(float64) != 5 {
		t.Errorf("expected newer quantity 5, got %v", item["quantity"])
	}
}

func TestRehydrate_EmptyJSON(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"

	err := repo.Rehydrate(context.Background(), cartKey, "", 604800)
	if err != nil {
		t.Fatalf("Rehydrate with empty string should not error: %v", err)
	}

	err = repo.Rehydrate(context.Background(), cartKey, "[]", 604800)
	if err != nil {
		t.Fatalf("Rehydrate with empty array should not error: %v", err)
	}
}

func TestRehydrate_InvalidJSON(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"

	err := repo.Rehydrate(context.Background(), cartKey, "not valid json", 604800)
	if err != nil {
		t.Fatalf("Rehydrate should not error on invalid JSON (silent failure): %v", err)
	}
}

func TestRehydrate_SetsTTL(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"

	items := map[string]string{
		"sku1": `{"product_id":"prod-1","quantity":2,"updated_at":"1708530000"}`,
	}
	itemsJSON := buildRehydrateJSON(items)
	err := repo.Rehydrate(context.Background(), cartKey, itemsJSON, 604800)
	if err != nil {
		t.Fatalf("Rehydrate failed: %v", err)
	}

	ttl, err := rc.TTL(context.Background(), cartKey).Result()
	if err != nil {
		t.Fatalf("TTL failed: %v", err)
	}
	if ttl <= 0 {
		t.Error("TTL should be set (> 0)")
	}
}

func TestRehydrate_DefaultTTL(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"

	items := map[string]string{
		"sku1": `{"product_id":"prod-1","quantity":2,"updated_at":"1708530000"}`,
	}
	itemsJSON := buildRehydrateJSON(items)
	err := repo.Rehydrate(context.Background(), cartKey, itemsJSON, 0)
	if err != nil {
		t.Fatalf("Rehydrate with 0 TTL should use default: %v", err)
	}

	ttl, _ := rc.TTL(context.Background(), cartKey).Result()
	if ttl <= 0 {
		t.Error("TTL should be set to default 604800")
	}
}

func TestRehydrate_NoXADD(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"
	streamShard := "{cart}:events:0"

	items := map[string]string{
		"sku1": `{"product_id":"prod-1","quantity":2,"updated_at":"1708530000"}`,
	}
	itemsJSON := buildRehydrateJSON(items)
	err := repo.Rehydrate(context.Background(), cartKey, itemsJSON, 604800)
	if err != nil {
		t.Fatalf("Rehydrate failed: %v", err)
	}

	// Verify no events were written to stream
	streamLen, _ := rc.XLen(context.Background(), streamShard).Result()
	if streamLen != 0 {
		t.Errorf("Rehydrate should NOT write to stream, but found %d events", streamLen)
	}
}

func TestRehydrate_PartialNewData(t *testing.T) {
	mr, rc := setupMiniRedis(t)
	defer teardownMiniRedis(mr, rc)

	repo := NewCartLuaRepo(&Client{rdb: rc}, NewShardManager(4))
	cartKey := "{cart}:user1"

	// Pre-populate with newer sku1, older sku2
	newerItem := `{"product_id":"prod-1","quantity":5,"updated_at":"1708616400"}`
	rc.HSet(context.Background(), cartKey, "sku1", newerItem)

	// Rehydrate both sku1 (older) and sku2 (new)
	items := map[string]string{
		"sku1": `{"product_id":"prod-1","quantity":3,"updated_at":"1708530000"}`,
		"sku2": `{"product_id":"prod-2","quantity":1,"updated_at":"1708616400"}`,
	}
	itemsJSON := buildRehydrateJSON(items)
	err := repo.Rehydrate(context.Background(), cartKey, itemsJSON, 604800)
	if err != nil {
		t.Fatalf("Rehydrate failed: %v", err)
	}

	// sku1 should keep newer data
	val1, _ := rc.HGet(context.Background(), cartKey, "sku1").Result()
	var item1 map[string]interface{}
	json.Unmarshal([]byte(val1), &item1)
	if item1["quantity"].(float64) != 5 {
		t.Errorf("sku1 should keep quantity 5, got %v", item1["quantity"])
	}

	// sku2 should be written
	val2, err := rc.HGet(context.Background(), cartKey, "sku2").Result()
	if err != nil {
		t.Fatalf("sku2 should exist: %v", err)
	}
	var item2 map[string]interface{}
	json.Unmarshal([]byte(val2), &item2)
	if item2["product_id"] != "prod-2" {
		t.Errorf("sku2 product_id = %v, want prod-2", item2["product_id"])
	}
}

// buildRehydrateJSON constructs the JSON array format expected by the rehydrate Lua script.
func buildRehydrateJSON(items map[string]string) string {
	if len(items) == 0 {
		return "[]"
	}
	var b []byte
	b = append(b, '[')
	first := true
	for skuID, value := range items {
		if !first {
			b = append(b, ',')
		}
		first = false
		b = append(b, `{"sku_id":`...)
		b = append(b, strconv.AppendQuote(nil, skuID)...)
		b = append(b, `,"value":`...)
		b = append(b, strconv.AppendQuote(nil, value)...)
		b = append(b, `}`...)
	}
	b = append(b, ']')
	return string(b)
}
