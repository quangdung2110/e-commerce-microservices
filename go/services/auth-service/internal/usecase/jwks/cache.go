package jwks

import (
	"sync"
	"time"

	"github.com/dgraph-io/ristretto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
)

// Cache provides L1 in-memory caching for JWKS using Ristretto
// Ristretto is a high-performance, pure Go cache library
type Cache struct {
	cache  *ristretto.Cache
	ttl    time.Duration
	mu     sync.RWMutex
	loaded time.Time
}

// NewCache creates a new JWKS cache with the specified TTL
func NewCache(ttl time.Duration) (*Cache, error) {
	cache, err := ristretto.NewCache(&ristretto.Config{
		MaxCost:     1 << 20, // 1 MB max cost
		BufferItems: 64,      // number of keys per Get buffer
	})
	if err != nil {
		return nil, err
	}

	return &Cache{
		cache: cache,
		ttl:   ttl,
	}, nil
}

// Get retrieves JWKS from cache
// Returns nil if cache miss
func (c *Cache) Get() (*entity.JWKS, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	val, found := c.cache.Get(domain.RedisKeyPrefixJwksCache)
	if !found {
		return nil, nil // MISS
	}

	// Check if cache is still valid
	if time.Since(c.loaded) > c.ttl {
		return nil, nil // Expired
	}

	jwks, ok := val.(*entity.JWKS)
	if !ok {
		return nil, nil
	}

	return jwks, nil
}

// Set stores JWKS in cache
func (c *Cache) Set(jwks *entity.JWKS) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.cache.Set(domain.RedisKeyPrefixJwksCache, jwks, 1)
	c.loaded = time.Now()
}

// Invalidate removes JWKS from cache
func (c *Cache) Invalidate() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.cache.Del(domain.RedisKeyPrefixJwksCache)
	c.loaded = time.Time{}
}

// Close closes the cache
func (c *Cache) Close() {
	c.cache.Close()
}

// Stats returns cache statistics
func (c *Cache) Stats() *ristretto.Metrics {
	return c.cache.Metrics
}

// IsLoaded returns true if cache has been loaded at least once
func (c *Cache) IsLoaded() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return !c.loaded.IsZero()
}

// TimeUntilExpiry returns the time until cache expiry
// Returns 0 if not loaded
func (c *Cache) TimeUntilExpiry() time.Duration {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if c.loaded.IsZero() {
		return 0
	}

	expiry := c.loaded.Add(c.ttl)
	remaining := time.Until(expiry)
	if remaining < 0 {
		return 0
	}
	return remaining
}
