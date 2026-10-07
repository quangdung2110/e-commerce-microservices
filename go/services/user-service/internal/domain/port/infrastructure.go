package port

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/event"
)

// AddressCache is the cache-aside copy of an address book (ADR-002).
//
// It is never a source of truth and never fails a request: Get reports a miss
// when Redis is unavailable, and Invalidate reports the error so it can be
// alerted on, but the caller has already committed to PostgreSQL by then.
type AddressCache interface {
	// Get returns the cached list. found is false on a miss, on unusable
	// content and when Redis cannot answer.
	Get(ctx context.Context, userID uuid.UUID) (addresses []*entity.Address, found bool)
	// Put stores the list. An empty list gets the shorter TTL, so a user with no
	// addresses cannot be used to hammer PostgreSQL.
	Put(ctx context.Context, userID uuid.UUID, addresses []*entity.Address) error
	// Invalidate drops the key after a committed mutation.
	Invalidate(ctx context.Context, userID uuid.UUID) error
}

// EventPublisher publishes a CloudEvent after the PostgreSQL transaction has
// committed. Delivery is best effort with bounded retry: an exhausted retry is
// the gap the daily reconciliation sweep closes (ADR-004, ADR-007).
type EventPublisher interface {
	Publish(ctx context.Context, envelope *event.Envelope) error
}

// RateLimitRule is one fixed window.
type RateLimitRule struct {
	Limit  int
	Window time.Duration
}

// Enabled reports whether the rule should be applied at all.
func (r RateLimitRule) Enabled() bool { return r.Limit > 0 && r.Window > 0 }

// RateLimitResult reports the outcome of one rate-limit check.
type RateLimitResult struct {
	Allowed    bool
	Remaining  int
	RetryAfter time.Duration
}

// RateLimiter enforces the anti-abuse limits of the public API.
type RateLimiter interface {
	Allow(ctx context.Context, scope, subject string, rule RateLimitRule) (RateLimitResult, error)
}

// Lock is a held distributed lock.
type Lock interface {
	Release(ctx context.Context) error
}

// Locker keeps a scheduled run singleton across replicas, which is what makes
// the reconciliation sweep run once per day service-wide. Acquire returns a nil
// lock when another replica holds it.
type Locker interface {
	Acquire(ctx context.Context, name string, ttl time.Duration) (Lock, error)
}
