package product

import (
	"container/list"
	"sync"
	"time"

	"cart-service/internal/domain"
)

// snapshotCache is a bounded, per-pod LRU of recent Product lookups.
//
// It exists so that a burst of quantity clicks on the same SKU does not turn
// into a burst of gRPC calls. It is an optimisation only: Redis holds the
// snapshot that actually matters, and a cold cache changes latency, never
// correctness (docs/cart/data.md §5).
type snapshotCache struct {
	mu       sync.Mutex
	capacity int
	ttl      time.Duration
	now      func() time.Time

	entries map[string]*list.Element
	order   *list.List
}

type cacheEntry struct {
	skuID     string
	snapshot  domain.ProductSnapshot
	expiresAt time.Time
}

func newSnapshotCache(capacity int, ttl time.Duration) *snapshotCache {
	if capacity <= 0 {
		capacity = 1024
	}
	return &snapshotCache{
		capacity: capacity,
		ttl:      ttl,
		now:      time.Now,
		entries:  make(map[string]*list.Element, capacity),
		order:    list.New(),
	}
}

// get returns a cached snapshot that has not expired.
func (c *snapshotCache) get(skuID string) (domain.ProductSnapshot, bool) {
	if c.ttl <= 0 {
		return domain.ProductSnapshot{}, false
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	element, ok := c.entries[skuID]
	if !ok {
		return domain.ProductSnapshot{}, false
	}

	entry := element.Value.(*cacheEntry)
	if c.now().After(entry.expiresAt) {
		c.order.Remove(element)
		delete(c.entries, skuID)
		return domain.ProductSnapshot{}, false
	}

	c.order.MoveToFront(element)
	return entry.snapshot, true
}

// put stores a snapshot, evicting the least recently used entry when full.
func (c *snapshotCache) put(skuID string, snapshot domain.ProductSnapshot) {
	if c.ttl <= 0 {
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if element, ok := c.entries[skuID]; ok {
		entry := element.Value.(*cacheEntry)
		entry.snapshot = snapshot
		entry.expiresAt = c.now().Add(c.ttl)
		c.order.MoveToFront(element)
		return
	}

	element := c.order.PushFront(&cacheEntry{
		skuID:     skuID,
		snapshot:  snapshot,
		expiresAt: c.now().Add(c.ttl),
	})
	c.entries[skuID] = element

	for c.order.Len() > c.capacity {
		oldest := c.order.Back()
		if oldest == nil {
			break
		}
		c.order.Remove(oldest)
		delete(c.entries, oldest.Value.(*cacheEntry).skuID)
	}
}
