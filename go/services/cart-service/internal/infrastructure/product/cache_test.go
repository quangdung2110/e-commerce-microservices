package product

import (
	"testing"
	"time"

	"cart-service/internal/domain"
)

func snapshotFor(skuID string) domain.ProductSnapshot {
	return domain.ProductSnapshot{SkuID: skuID, IsAvailable: true}
}

func TestCacheReturnsAStoredSnapshot(t *testing.T) {
	cache := newSnapshotCache(4, time.Minute)
	cache.put("SKU-A", snapshotFor("SKU-A"))

	got, ok := cache.get("SKU-A")
	if !ok || got.SkuID != "SKU-A" {
		t.Fatalf("get = %+v, %v", got, ok)
	}
}

// The cache is short-lived on purpose: it collapses a burst of clicks, it does
// not stand in for Redis.
func TestCacheExpiresEntries(t *testing.T) {
	cache := newSnapshotCache(4, 30*time.Second)
	now := time.Unix(1780000000, 0)
	cache.now = func() time.Time { return now }

	cache.put("SKU-A", snapshotFor("SKU-A"))
	now = now.Add(31 * time.Second)

	if _, ok := cache.get("SKU-A"); ok {
		t.Fatal("an expired entry was served")
	}
}

func TestCacheEvictsTheLeastRecentlyUsedEntry(t *testing.T) {
	cache := newSnapshotCache(2, time.Minute)

	cache.put("SKU-A", snapshotFor("SKU-A"))
	cache.put("SKU-B", snapshotFor("SKU-B"))
	// Touching A makes B the least recently used entry.
	cache.get("SKU-A")
	cache.put("SKU-C", snapshotFor("SKU-C"))

	if _, ok := cache.get("SKU-B"); ok {
		t.Fatal("the least recently used entry survived eviction")
	}
	if _, ok := cache.get("SKU-A"); !ok {
		t.Fatal("a recently used entry was evicted")
	}
	if _, ok := cache.get("SKU-C"); !ok {
		t.Fatal("the newest entry is missing")
	}
}

// A bounded cache must stay bounded no matter how many distinct SKUs arrive.
func TestCacheStaysWithinCapacity(t *testing.T) {
	cache := newSnapshotCache(8, time.Minute)

	for i := 0; i < 1000; i++ {
		sku := "SKU-" + time.Duration(i).String()
		cache.put(sku, snapshotFor(sku))
	}

	if len(cache.entries) > 8 {
		t.Fatalf("cache holds %d entries, capacity is 8", len(cache.entries))
	}
	if cache.order.Len() != len(cache.entries) {
		t.Fatalf("index and order list disagree: %d vs %d", len(cache.entries), cache.order.Len())
	}
}

func TestCacheIsDisabledWhenTTLIsZero(t *testing.T) {
	cache := newSnapshotCache(4, 0)
	cache.put("SKU-A", snapshotFor("SKU-A"))

	if _, ok := cache.get("SKU-A"); ok {
		t.Fatal("a cache configured with no ttl served an entry")
	}
}
