// Package port declares the interfaces the application service depends on.
// Every implementation lives under internal/infrastructure.
package port

import (
	"context"
	"time"

	"cart-service/internal/domain"
)

// UpsertCommand is one delta applied to one SKU.
type UpsertCommand struct {
	Identity domain.Identity
	SkuID    string
	// Delta is a non-zero relative change. The contract never accepts an
	// absolute quantity, because Redis has to serialise concurrent clicks.
	Delta int
	// Snapshot is a freshly validated product snapshot, or nil to let the
	// script reuse the snapshot already stored with the item.
	Snapshot *domain.StoredItem
	// Fingerprint is the canonical hash of the request, used to detect an
	// idempotency key replayed with a different payload.
	Fingerprint string
	// IdempotencyKey is the client-supplied key. Always set on the HTTP path.
	IdempotencyKey string
}

// UpsertResult is the logical outcome of an upsert, as returned by the script
// and as replayed from an idempotency record.
type UpsertResult struct {
	SkuID     string        `json:"sku_id"`
	Quantity  int           `json:"quantity"`
	UpdatedAt domain.Millis `json:"updated_at"`
	Created   bool          `json:"created"`
}

// RemoveCommand deletes a batch of SKUs from one cart.
type RemoveCommand struct {
	Identity domain.Identity
	SkuIDs   []string
	// TouchActivity extends the cart lifetime and pushes the abandonment
	// deadline. A user delete sets it; an ORDER_PAID cleanup does not
	// (CART-BR-007, WF-CART-004).
	TouchActivity bool
	// Fingerprint and IdempotencyKey are only set on the HTTP path. ORDER_PAID
	// needs no record: HDEL per SKU is already idempotent.
	Fingerprint    string
	IdempotencyKey string
}

// RemoveResult is the logical outcome of a batch delete.
type RemoveResult struct {
	DeletedCount int `json:"deleted_count"`
}

// MarkAbandonedCommand transitions one cart to ABANDONED and writes its outbox
// entry in the same Lua invocation.
type MarkAbandonedCommand struct {
	Identity domain.Identity
	// ExpectedDeadline is the ZSET score the marker read. The script rejects
	// the transition if the score moved, which is how a user write that raced
	// the marker wins.
	ExpectedDeadline int64
}

// MarkAbandonedResult reports what the script did.
type MarkAbandonedResult struct {
	Marked           bool   `json:"marked"`
	Reason           string `json:"reason"`
	StreamID         string `json:"stream_id"`
	AbandonmentCycle int64  `json:"abandonment_cycle"`
}

// DueCart is one overdue entry of a bucket's abandonment index.
type DueCart struct {
	UserToken string
	Deadline  int64
}

// CartStore is the Redis source of truth for cart state.
//
// Every mutating method is a single Lua invocation, which is the only
// transaction boundary Cart has.
type CartStore interface {
	// LoadItems reads every SKU line of a cart. A missing hash yields an empty
	// slice and no error: Cart never rehydrates from a cold store.
	LoadItems(ctx context.Context, id domain.Identity) ([]domain.Item, error)

	// Upsert applies a delta to one SKU.
	Upsert(ctx context.Context, cmd UpsertCommand) (UpsertResult, error)

	// Remove deletes a batch of SKUs.
	Remove(ctx context.Context, cmd RemoveCommand) (RemoveResult, error)

	// MarkAbandoned transitions one cart and emits its outbox entry.
	MarkAbandoned(ctx context.Context, cmd MarkAbandonedCommand) (MarkAbandonedResult, error)

	// DueCarts lists overdue members of one bucket's abandonment index.
	DueCarts(ctx context.Context, bucket int, now time.Time, limit int) ([]DueCart, error)

	// DropIndexMember removes a stale member from a bucket's abandonment index.
	DropIndexMember(ctx context.Context, bucket int, userToken string) error

	// Ping reports whether Redis is reachable.
	Ping(ctx context.Context) error
}

// OutboxStore is the Redis Streams side of the outbox, used by the relay and
// the trimmer loops.
type OutboxStore interface {
	// StreamExists reports whether a bucket's outbox stream has been created.
	// Streams are created lazily by the first XADD.
	StreamExists(ctx context.Context, bucket int) (bool, error)

	// EnsureGroup creates the relay consumer group for a bucket if it is absent.
	EnsureGroup(ctx context.Context, bucket int) error

	// Claim takes over entries that a previous relay read but never acked.
	Claim(ctx context.Context, bucket, count int, minIdle time.Duration) ([]domain.OutboxRecord, error)

	// Read fetches undelivered entries for the relay consumer group.
	Read(ctx context.Context, bucket, count int) ([]domain.OutboxRecord, error)

	// Ack marks an entry delivered. It is only called after Kafka has
	// acknowledged the produce.
	Ack(ctx context.Context, bucket int, streamID string) error

	// TrimWatermark reports the oldest entry id the trimmer must keep, and
	// whether trimming is safe for this bucket at all.
	TrimWatermark(ctx context.Context, bucket int) (minID string, safe bool, err error)

	// Trim removes entries strictly older than minID.
	Trim(ctx context.Context, bucket int, minID string) (int64, error)

	// OutboxKey returns the Redis key of a bucket's outbox stream. The relay
	// needs it verbatim to build the deterministic CloudEvent id.
	OutboxKey(bucket int) string
}

// ProductCatalog is the Product Service read capability Cart pulls from.
//
// Cart never subscribes to Product changes and never builds a reverse index
// from SKU to cart (ADR-005).
type ProductCatalog interface {
	// GetSku validates one SKU for a write. A failure rejects the write.
	GetSku(ctx context.Context, skuID string) (domain.ProductSnapshot, error)

	// GetBatchSkus enriches a cart read. A failure degrades the response
	// rather than failing it, so the caller receives the error and decides.
	GetBatchSkus(ctx context.Context, skuIDs []string) (map[string]domain.ProductSnapshot, error)

	// Ready reports whether the client is usable for a readiness probe.
	Ready(ctx context.Context) error
}

// EventPublisher produces sealed CloudEvents to Kafka.
type EventPublisher interface {
	// Publish sends one event to the cart topic, keyed by cart id. It returns
	// only after the broker has acknowledged the write.
	Publish(ctx context.Context, event domain.CloudEvent, headers map[string]string) error

	// PublishDeadLetter routes an entry that can never succeed to the DLQ.
	PublishDeadLetter(ctx context.Context, key string, payload []byte, headers map[string]string) error

	// Ready reports whether the producer has a usable broker connection.
	Ready(ctx context.Context) error

	// Close flushes and shuts the producer down.
	Close() error
}
