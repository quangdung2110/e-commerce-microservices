package auth

import (
	"context"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// ContactCache is the 15-minute contact cache of 02-pipeline.md §2.3: memory
// or Redis with a TTL, never the Notification database (no long-lived PII
// copy). Get returns errors.ErrCacheMiss when the entry is absent or expired.
// The Redis implementation lives in internal/infrastructure/redis and is
// written against this interface.
type ContactCache interface {
	Get(ctx context.Context, userID uuid.UUID) (*entity.Contact, error)
	Set(ctx context.Context, userID uuid.UUID, contact *entity.Contact, ttl time.Duration) error
	Delete(ctx context.Context, userID uuid.UUID) error
}

// MemoryCache is an in-process ContactCache for single-replica development
// and tests. Entries expire lazily on read.
type MemoryCache struct {
	mu      sync.Mutex
	clk     clock.Clock
	entries map[uuid.UUID]memoryEntry
}

type memoryEntry struct {
	contact   entity.Contact
	expiresAt time.Time
}

// NewMemoryCache builds an empty cache on the given clock (nil → system).
func NewMemoryCache(clk clock.Clock) *MemoryCache {
	if clk == nil {
		clk = clock.System{}
	}
	return &MemoryCache{clk: clk, entries: map[uuid.UUID]memoryEntry{}}
}

func (m *MemoryCache) Get(_ context.Context, userID uuid.UUID) (*entity.Contact, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.entries[userID]
	if !ok {
		return nil, errors.ErrCacheMiss
	}
	if !m.clk.Now().Before(e.expiresAt) {
		delete(m.entries, userID)
		return nil, errors.ErrCacheMiss
	}
	c := e.contact
	return &c, nil
}

func (m *MemoryCache) Set(_ context.Context, userID uuid.UUID, contact *entity.Contact, ttl time.Duration) error {
	if contact == nil || ttl <= 0 {
		return nil
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.entries[userID] = memoryEntry{contact: *contact, expiresAt: m.clk.Now().Add(ttl)}
	return nil
}

func (m *MemoryCache) Delete(_ context.Context, userID uuid.UUID) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.entries, userID)
	return nil
}

// Len reports the number of entries (tests, debugging).
func (m *MemoryCache) Len() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.entries)
}
