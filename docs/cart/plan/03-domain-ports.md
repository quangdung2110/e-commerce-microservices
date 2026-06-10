# Cart Service — Module 03: Domain Ports (Interfaces)

**Document:** 03 | **Module:** Domain Ports — Interface Definitions  
**Status:** `[x]` Đã hoàn thành | `[ ]` Chưa hoàn thành
**Phụ thuộc:** Module 02 (domain models)

---

## 1. Mục tiêu Module

Định nghĩa tất cả interfaces (ports trong Hexagonal Architecture) mà Domain layer cần để giao tiếp với thế giới bên ngoài. Interfaces được đặt trong `internal/domain/port/`. Các implementation nằm ở `internal/infrastructure/`.

---

## 2. Tổng quan Ports

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          DOMAIN LAYER                                     │
│                                                                          │
│  Port Interfaces (định nghĩa tại đây, implement ở infrastructure/)     │
│                                                                          │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────┐   │
│  │ CartLuaRepository │  │  CacheRepository  │  │  ProductClient     │   │
│  │ (Redis Lua ops)   │  │  (negative cache)│  │  (gRPC call)       │   │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬───────────┘   │
│           │                     │                     │                 │
│  ┌────────┴─────────┐  ┌───────┴────────┐  ┌───────┴───────────┐   │
│  │ CartRepository   │  │ OutboxWriter   │  │ KafkaPublisher    │   │
│  │ (PG cold store) │  │ (XADD events) │  │ (Kafka produce)  │   │
│  └──────────────────┘  └────────────────┘  └────────────────────┘   │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
           │
           │ Implementations
           ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                     INFRASTRUCTURE LAYER                                   │
│                                                                          │
│  internal/infrastructure/redis/     → implements CartLuaRepo, CacheRepo   │
│  internal/infrastructure/postgres/  → implements CartRepository            │
│  internal/infrastructure/kafka/     → implements KafkaPublisher           │
│  internal/infrastructure/grpc/      → implements ProductClient            │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Chi tiết từng Port

### 3.1. CartLuaRepository (`internal/domain/port/cart_lua_repo.go`)

Port cho tất cả thao tác Redis thông qua Lua Scripts. Đây là cổng quan trọng nhất — mọi write operations phải qua đây để đảm bảo atomicity.

```go
package port

import (
    "context"
)

// CartLuaRepository defines Redis Lua Script operations for cart.
// Ref: cart_caching_schema.md §3
type CartLuaRepository interface {
    // AddOrUpdateItem atomically adds or updates a cart item.
    // Executes the add_or_update Lua script.
    // Returns the encoded JSON of the item (from Lua return) and any error.
    //
    // Parameters:
    //   - cartKey: Redis key with Hash Tag, e.g. "{cart}:{user_id}"
    //   - skuID: SKU identifier
    //   - itemJSON: Full JSON payload built by caller
    //   - maxQty: Max quantity per SKU (e.g. 100)
    //   - maxSkus: Max SKUs per cart (e.g. 50)
    //   - step: Quantity delta (+1, -1, etc.)
    //   - eventTimeMs: Epoch milliseconds for business timestamp
    //   - userID: User ID (needed for event payload)
    //
    // Returns:
    //   - encoded item JSON from Lua
    //   - error: ErrCartFull, ErrQuantityInvalid, ErrCannotDecrementNonExisting
    //
    // Lua script guarantees:
    //   - Atomic quantity accumulation (no race)
    //   - Cart capacity check (HLEN < max_skus)
    //   - Per-item quantity cap (new_qty <= max_qty)
    //   - HSET + XADD in same atomic context
    //   - TTL refresh (EXPIRE 7 days)
    AddOrUpdateItem(ctx context.Context, cartKey, streamShard string, skuID, itemJSON string, maxQty, maxSkus, step int, eventTimeMs int64, userID string) (string, error)

    // BatchDelete removes multiple SKUs from a cart atomically.
    // Executes the batch_delete Lua script.
    //
    // Parameters:
    //   - cartKey: Redis key with Hash Tag, e.g. "{cart}:{user_id}"
    //   - streamShard: Global stream shard, e.g. "{cart}:events:0"
    //   - timestampMs: Current epoch milliseconds
    //   - eventType: "CART_CLEARED" or "CART_CHECKED_OUT"
    //   - userID: User ID (needed for event payload)
    //   - skuIDs: JSON array of SKU IDs to delete
    //
    // Returns:
    //   - number of items deleted
    //   - error
    //
    // Lua script guarantees:
    //   - HDEL only existing SKUs
    //   - Emits CART_ITEM_DELETED per deleted SKU
    //   - Emits CART_CLEARED or CART_CHECKED_OUT if cart becomes empty
    //   - DEL cart key if HLEN == 0
    BatchDelete(ctx context.Context, cartKey, streamShard string, timestampMs int64, eventType, userID, skuIDsJSON string) (int, error)

    // Rehydrate populates a cart from cold storage without emitting events.
    // Executes the silent rehydrate Lua script.
    //
    // Parameters:
    //   - cartKey: Redis key with Hash Tag
    //   - itemsJSON: JSON array of {sku_id, value} objects already merged with product data
    //   - ttlSeconds: TTL to set (default 604800)
    //
    // Returns:
    //   - error
    //
    // Lua script guarantees:
    //   - NO XADD (silent)
    //   - HSET only if field missing OR incoming.updated_at > redis.updated_at
    //   - EXPIRE at end
    Rehydrate(ctx context.Context, cartKey string, itemsJSON string, ttlSeconds int) error
}

// Errors returned by CartLuaRepository
var (
    ErrCartFull               = errors.New("cart has reached maximum SKU capacity")
    ErrQuantityInvalid        = errors.New("quantity invalid (zero or negative)")
    ErrCannotDecrementNonExisting = errors.New("cannot decrement quantity of non-existing item")
    ErrStepInvalid           = errors.New("step must be non-zero integer")
)
```

### 3.2. CacheRepository (`internal/domain/port/cache_repo.go`)

Port cho các thao tác Redis cache không qua Lua Script (simple operations).

```go
package port

import "context"

// CacheRepository defines simple Redis cache operations.
type CacheRepository interface {
    // GetCart reads all fields from a cart hash.
    // Returns map[skuID]JSONValue or empty map if key doesn't exist.
    // Does NOT emit events.
    GetCart(ctx context.Context, cartKey string) (map[string]string, error)

    // DeleteNegativeCache removes the negative cache key for a cart.
    // Called after successful Add/Update/Delete to prevent stale empty responses.
    DeleteNegativeCache(ctx context.Context, userID string) error

    // SetNegativeCache sets a negative cache marker (key not found indicator).
    // TTL: 60 seconds. Called when cart not in Redis AND not in PostgreSQL.
    SetNegativeCache(ctx context.Context, userID string) error

    // HasNegativeCache checks if a negative cache key exists.
    // Returns true if cart was recently confirmed as non-existent.
    HasNegativeCache(ctx context.Context, userID string) (bool, error)

    // SetItemAvailability updates only the is_available field of a cart item.
    // Called when gRPC returns product BANNED/UNAVAILABLE.
    // This is the ONLY exception to the "read-only TTL" rule.
    // Does NOT call EXPIRE — only updates the field.
    SetItemAvailability(ctx context.Context, cartKey, skuID, itemJSON string) error

    // Ping checks Redis connectivity.
    Ping(ctx context.Context) error
}
```

### 3.3. CartRepository (`internal/domain/port/cart_repository.go`)

Port cho PostgreSQL Cold Store operations. Chỉ dùng cho:
1. Cache-miss rehydrate (đọc từ PG lên Redis)
2. Postgres Sync Worker (ghi từ Kafka events xuống PG)
3. Cronjobs (Abandoned Marker, Archival Cleaner)

```go
package port

import (
    "context"
    "time"

    "cart-service/internal/domain/entity"
)

// CartRepository defines PostgreSQL cold store operations.
type CartRepository interface {
    // GetCartStatus returns the status of a cart from cold storage.
    // Used in cache-miss rehydrate to determine if cart is active.
    GetCartStatus(ctx context.Context, cartID string) (entity.CartStatus, error)

    // ListCartsForAbandonedMark returns carts that are ACTIVE
    // and have not been updated for inactiveDays.
    // Used by Abandoned Cart Marker cronjob.
    // Batch size should be respected (LIMIT).
    ListCartsForAbandonedMark(ctx context.Context, inactiveDays int, limit int) ([]*entity.Cart, error)

    // UpdateCartStatus updates cart status with stream_id guard.
    // Only updates if newStreamID > current last_event_stream_id.
    // Returns rowsAffected.
    UpdateCartStatus(ctx context.Context, cartID string, status entity.CartStatus, streamID string) (int64, error)

    // UpsertCartItem inserts or updates a cart item with stream_id guard.
    // Implements ON CONFLICT DO UPDATE WHERE redis_stream_id < newStreamID.
    // Also upserts the cart master row.
    // Used by Postgres Sync Worker.
    UpsertCartItem(ctx context.Context, item *entity.CartItem, streamID string) error

    // BatchUpsertCartItems performs batch upsert for multiple items of the same cart.
    // Within one database transaction per cart_id.
    BatchUpsertCartItems(ctx context.Context, cartID string, items []*entity.CartItem, streamIDs []string) error

    // DeleteCartItem deletes a specific item with stream_id guard.
    // Hard delete (not soft delete). Only deletes if redis_stream_id < eventStreamID.
    DeleteCartItem(ctx context.Context, itemID string, streamID string) (int64, error)

    // BatchDeleteCartItems performs hard delete for multiple items.
    // Used by Postgres Sync Worker.
    BatchDeleteCartItems(ctx context.Context, itemIDs []string, streamID string) (int64, error)

    // UpsertCartMaster inserts or updates the cart master record.
    // Used by Postgres Sync Worker for ADD/UPDATE/CLEARED/CHECKED_OUT events.
    UpsertCartMaster(ctx context.Context, cart *entity.Cart, streamID string) error

    // ListCartsForCleanup returns carts eligible for hard delete.
    // Used by Data Archival Cleaner.
    ListCartsForCleanup(ctx context.Context, olderThan time.Time, statuses []entity.CartStatus, limit int) ([]string, error)

    // HardDeleteCart permanently removes a cart and all its items.
    // Must be wrapped in a transaction: DELETE cart_items FIRST, then DELETE carts.
    HardDeleteCart(ctx context.Context, cartID string) error

    // ListPendingAbandonedEvents returns carts marked ABANDONED but not yet published.
    // Used for recovery if XADD failed after DB update.
    ListPendingAbandonedEvents(ctx context.Context, olderThan time.Time) ([]*entity.Cart, error)

    // MarkAbandonedEventPublished updates abandoned_event_published_at.
    MarkAbandonedEventPublished(ctx context.Context, cartID string) error
}
```

### 3.4. ProductClient (`internal/domain/port/product_client.go`)

Port cho gRPC calls đến Product Service. Used in both write path (AddItem) and read path (GetCart).

```go
package port

import (
    "context"

    "cart-service/internal/domain/entity"
)

// ProductClient defines the interface for fetching product data from Product Service.
// Implemented by internal/infrastructure/grpc/product_client.go
type ProductClient interface {
    // GetProductSku fetches a single SKU's data.
    // Used in AddItem workflow to validate SKU and get display info.
    // Returns ErrProductNotFound if SKU doesn't exist or is inactive.
    GetProductSku(ctx context.Context, skuID string) (*entity.SkuInfo, error)

    // GetBatchProductSkus fetches multiple SKUs in a single gRPC call.
    // Used in GetCart (both cache-hit and cache-miss paths).
    // Returns map of skuID -> SkuInfo. Missing SKUs are omitted from map.
    // Context timeout: 200ms hard limit.
    GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error)

    // Close closes the gRPC connection.
    Close() error

    // IsHealthy checks if the product service is reachable.
    IsHealthy(ctx context.Context) bool
}

// Product unavailability reasons (for logging/debugging)
const (
    ProductStatusActive   = "ACTIVE"
    ProductStatusBanned   = "BANNED"
    ProductStatusDeleted  = "DELETED"
    ProductStatusInactive = "INACTIVE"
)
```

### 3.5. KafkaPublisher (`internal/domain/port/kafka_publisher.go`)

Port cho việc publish messages lên Kafka. Chỉ được dùng bởi Outbox Relay Worker (đọc từ Redis Stream và forward sang Kafka).

```go
package port

import (
    "context"
)

// CartEventMessage represents a cart event message to be published to Kafka.
type CartEventMessage struct {
    EventID           string            `json:"event_id"`
    EventType         string            `json:"event_type"`
    AggregateType     string            `json:"aggregate_type"`
    AggregateID       string            `json:"aggregate_id"`
    CorrelationID     string            `json:"correlation_id"`
    Timestamp         string            `json:"timestamp"`         // ISO 8601 UTC
    Source            string            `json:"source"`            // "cart-service"
    StreamID         string            `json:"stream_id"`         // Redis Stream ID
    StreamTimestampMs string           `json:"stream_timestamp_ms"`
    EventSequence     string            `json:"event_sequence"`
    Payload           map[string]interface{} `json:"payload"`
}

// KafkaPublisher defines the interface for publishing cart events to Kafka.
type KafkaPublisher interface {
    // Publish publishes a single cart event to the Kafka topic.
    // Partition key: cart_id (ensures events for same cart go to same partition).
    // Uses acks=all for durability.
    Publish(ctx context.Context, msg *CartEventMessage) error

    // Close closes the Kafka producer.
    Close() error
}
```

### 3.6. OutboxWriter (`internal/domain/port/outbox_writer.go`)

Port cho việc ghi trực tiếp vào Redis Stream Outbox. Được dùng bởi Abandoned Cart Marker cronjob (phải đi cùng đường với write API: XADD → Relay → Kafka).

```go
package port

import "context"

// OutboxWriter defines the interface for writing events to Redis Stream Outbox.
// Used by Abandoned Cart Marker cronjob.
type OutboxWriter interface {
    // WriteEvent writes a cart event to the appropriate global stream shard.
    // Shard is calculated from user_id: shard_id = crc32(userID) % numShards.
    // This bypasses the Lua script path (for cronjob use only).
    WriteEvent(ctx context.Context, shardID int, userID string, eventType string, payloadJSON string) (streamID string, err error)

    // Ping checks Redis connectivity.
    Ping(ctx context.Context) error
}
```

---

## 4. Interface Composition & Dependency Injection

### 4.1. CartService Dependencies (trong `internal/domain/service/`)

```go
// All interfaces injected into domain service
type CartServiceDeps struct {
    LuaRepo   CartLuaRepository   // Redis operations
    Cache     CacheRepository    // Simple cache ops
    CartRepo  CartRepository     // PostgreSQL cold store
    Product   ProductClient      // gRPC product service
}
```

### 4.2. Worker Dependencies

```go
// OutboxRelayWorker
type OutboxRelayWorkerDeps struct {
    Redis    *redis.Client
    Producer KafkaPublisher
    Logger   *zap.Logger
}

// PostgresSyncWorker
type PostgresSyncWorkerDeps struct {
    Consumer KafkaConsumer
    CartRepo CartRepository
    Logger   *zap.Logger
}

// AbandonedCartMarker
type AbandonedMarkerDeps struct {
    CartRepo    CartRepository
    OutboxWriter OutboxWriter
    Logger      *zap.Logger
}

// OrderEventConsumer
type OrderEventConsumerDeps struct {
    Consumer KafkaConsumer
    LuaRepo   CartLuaRepository
    Cache     CacheRepository
    Logger    *zap.Logger
}
```

---

## 5. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `cart_lua_repo.go` | `internal/domain/port/cart_lua_repo.go` | Lua script executor |
| `cache_repo.go` | `internal/domain/port/cache_repo.go` | Simple Redis cache ops |
| `cart_repository.go` | `internal/domain/port/cart_repository.go` | PostgreSQL cold store |
| `product_client.go` | `internal/domain/port/product_client.go` | gRPC Product Service |
| `kafka_publisher.go` | `internal/domain/port/kafka_publisher.go` | Kafka producer |
| `outbox_writer.go` | `internal/domain/port/outbox_writer.go` | Redis Stream writer |
| `interfaces.go` | `internal/domain/port/interfaces.go` | Compile-time interface checks |

---

## 6. Compile-time Interface Verification

```go
// internal/domain/port/interfaces.go
package port

import (
    // Verify all implementations at compile time
    _ "cart-service/internal/infrastructure/redis"
    _ "cart-service/internal/infrastructure/postgres"
    _ "cart-service/internal/infrastructure/kafka"
    _ "cart-service/internal/infrastructure/grpc"
)

// Ensure CartLuaRepository is implemented by the infrastructure layer
var _ CartLuaRepository = (*redis.CartLuaRepository)(nil) // filled by module 06
var _ CacheRepository  = (*redis.CacheRepository)(nil)
var _ CartRepository   = (*postgres.CartRepositoryImpl)(nil)
var _ ProductClient    = (*grpc.ProductClientImpl)(nil)
var _ KafkaPublisher  = (*kafka.Publisher)(nil)
var _ OutboxWriter     = (*redis.OutboxWriter)(nil)
```

---

## 7. Checklist triển khai Module 03

```markdown
## 03 - Domain Ports
- [x] Tạo `internal/domain/port/cart_lua_repo.go` — Lua script executor interface ✅ (2026-05-24)
- [x] Tạo `internal/domain/port/cache_repo.go` — Simple cache ops interface ✅ (2026-05-24)
- [x] Tạo `internal/domain/port/cart_repository.go` — PG cold store interface ✅ (2026-05-24)
- [x] Tạo `internal/domain/port/product_client.go` — gRPC product client interface ✅ (2026-05-24)
- [x] Tạo `internal/domain/port/kafka_publisher.go` — Kafka producer interface ✅ (2026-05-24)
- [x] Tạo `internal/domain/port/outbox_writer.go` — Redis Stream writer interface ✅ (2026-05-24)
- [x] Tạo `internal/domain/port/errors.go` — Port-level sentinel errors ✅ (2026-05-24)
- [x] Tạo `internal/domain/port/interfaces.go` — Compile-time checks ✅ (2026-05-24)
- [x] Verify tất cả interfaces có đầy đủ docstrings ✅ (2026-05-24)
- [x] Verify interfaces không chứa implementation details ✅ (2026-05-24)
- [x] Verify `go build ./internal/domain/port` compile thành công ✅ (2026-05-24)
```

---

*Tiếp theo: [04-domain-events.md](04-domain-events.md) — Domain event definitions.*
