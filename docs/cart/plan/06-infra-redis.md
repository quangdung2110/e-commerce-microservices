# Cart Service — Module 06: Infrastructure — Redis

**Document:** 06 | **Module:** Infrastructure — Redis Client & Lua Wrappers  
**Status:** `[x]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Module 02 (domain models), Module 03 (domain ports), Module 04 (domain events), Module 05 (Lua scripts)

---

## 1. Mục tiêu Module

Implement các infrastructure adapters cho Redis. Module này implement các interfaces đã định nghĩa trong Module 03 (`CartLuaRepository`, `CacheRepository`, `OutboxWriter`). Đây là module infrastructure đầu tiên được triển khai vì các workers và domain service phụ thuộc vào nó.

---

## 2. Chi tiết triển khai

### 2.1. Connection Setup (`internal/infrastructure/redis/connection.go`)

```go
package redis

import (
    "context"
    "fmt"
    "time"

    "github.com/redis/go-redis/v9"
)

// Config holds Redis connection configuration.
type Config struct {
    Addrs         []string
    Password      string
    DB            int
    PoolSize      int
    MinIdleConns  int
    ReadTimeout   time.Duration
    WriteTimeout  time.Duration
    NumShards     int // 4 for global stream sharding
}

// NewClient creates a Redis client with connection pooling.
func NewClient(ctx context.Context, cfg Config) (*redis.Client, error) {
    if len(cfg.Addrs) == 0 {
        return nil, fmt.Errorf("no Redis addresses provided")
    }

    client := redis.NewClient(&redis.Options{
        Addrs:         cfg.Addrs,
        Password:       cfg.Password,
        DB:            cfg.DB,
        PoolSize:      cfg.PoolSize,
        MinIdleConns:  cfg.MinIdleConns,
        ReadTimeout:   cfg.ReadTimeout,
        WriteTimeout:  cfg.WriteTimeout,
        // Enable pipelining by default
        // Disable auto pipeline in favor of explicit pipelines
    })

    // Verify connectivity
    if err := client.Ping(ctx).Err(); err != nil {
        return nil, fmt.Errorf("Redis ping failed: %w", err)
    }

    return client, nil
}

// NewClusterClient creates a Redis Cluster client.
func NewClusterClient(ctx context.Context, cfg Config) (*redis.ClusterClient, error) {
    client := redis.NewClusterClient(&redis.ClusterOptions{
        Addrs:         cfg.Addrs,
        Password:      cfg.Password,
        PoolSize:      cfg.PoolSize,
        MinIdleConns:  cfg.MinIdleConns,
        ReadTimeout:   cfg.ReadTimeout,
        WriteTimeout:  cfg.WriteTimeout,
    })

    if err := client.Ping(ctx).Err(); err != nil {
        return nil, fmt.Errorf("Redis cluster ping failed: %w", err)
    }

    return client, nil
}

// HealthChecker interface for health checks.
type HealthChecker interface {
    Ping(ctx context.Context) error
}
```

### 2.2. Lua Script Executor (`internal/infrastructure/redis/cart_lua_repo.go`)

Implement `CartLuaRepository` interface (Module 03).

```go
package redis

import (
    "context"
    "encoding/json"
    "fmt"

    "github.com/redis/go-redis/v9"

    "cart-service/internal/domain/port"
)

// CartLuaRepository implements port.CartLuaRepository using go-redis + Lua scripts.
// Ref: cart_caching_schema.md §3
type CartLuaRepository struct {
    client *redis.Client
    addOrUpdateScript  *redis.Script
    batchDeleteScript  *redis.Script
    rehydrateScript    *redis.Script
}

// NewCartLuaRepository creates a new Lua script executor.
func NewCartLuaRepository(client *redis.Client) *CartLuaRepository {
    return &CartLuaRepository{
        client: client,
        addOrUpdateScript:  redis.NewScript(scripts.AddOrUpdateScript),
        batchDeleteScript: redis.NewScript(scripts.BatchDeleteScript),
        rehydrateScript:    redis.NewScript(scripts.RehydrateScript),
    }
}

// Verify interface compliance at compile time.
var _ port.CartLuaRepository = (*CartLuaRepository)(nil)

// AddOrUpdateItem implements port.CartLuaRepository.AddOrUpdateItem.
// Ref: cart_caching_schema.md §3.1
func (r *CartLuaRepository) AddOrUpdateItem(
    ctx context.Context,
    cartKey, streamShard, skuID, itemJSON string,
    maxQty, maxSkus, step int,
    eventTimeMs int64,
    userID string,
) (string, error) {

    result, err := r.addOrUpdateScript.Run(ctx, r.client,
        []string{cartKey, streamShard}, // KEYS[1], KEYS[2]
        skuID,          // ARGV[1]
        itemJSON,       // ARGV[2]
        maxQty,         // ARGV[3]
        maxSkus,        // ARGV[4]
        step,           // ARGV[5]
        eventTimeMs,    // ARGV[6]
        userID,         // ARGV[7]
    ).Text()

    if err != nil {
        return "", fmt.Errorf("Lua AddOrUpdate error: %w", err)
    }

    // Check for Lua-returned error
    var errCheck struct {
        Err string `json:"err"`
    }
    if err := json.Unmarshal([]byte(result), &errCheck); err == nil && errCheck.Err != "" {
        switch errCheck.Err {
        case "Cart Full":
            return "", port.ErrCartFull
        case "Step Invalid":
            return "", port.ErrStepInvalid
        case "Quantity Invalid":
            return "", port.ErrQuantityInvalid
        case "Cannot decrement non-existing item":
            return "", port.ErrCannotDecrementNonExisting
        default:
            return "", fmt.Errorf("Lua error: %s", errCheck.Err)
        }
    }

    return result, nil
}

// BatchDelete implements port.CartLuaRepository.BatchDelete.
// Ref: cart_caching_schema.md §3.3
func (r *CartLuaRepository) BatchDelete(
    ctx context.Context,
    cartKey, streamShard string,
    timestampMs int64,
    eventType, userID, skuIDsJSON string,
) (int, error) {

    result, err := r.batchDeleteScript.Run(ctx, r.client,
        []string{cartKey, streamShard},
        timestampMs,      // ARGV[1]
        eventType,       // ARGV[2]
        userID,          // ARGV[3]
        skuIDsJSON,      // ARGV[4]
    ).Text()

    if err != nil {
        return 0, fmt.Errorf("Lua BatchDelete error: %w", err)
    }

    // Check for Lua-returned error
    var errCheck struct {
        Err string `json:"err"`
    }
    if err := json.Unmarshal([]byte(result), &errCheck); err == nil && errCheck.Err != "" {
        return 0, fmt.Errorf("Lua error: %s", errCheck.Err)
    }

    // Parse deleted count from result
    var count int
    if err := json.Unmarshal([]byte(result), &count); err != nil {
        // Lua returned the JSON directly
        return 0, nil
    }

    return count, nil
}

// Rehydrate implements port.CartLuaRepository.Rehydrate.
// Ref: cart_caching_schema.md §3.6
func (r *CartLuaRepository) Rehydrate(
    ctx context.Context,
    cartKey, itemsJSON string,
    ttlSeconds int,
) error {

    _, err := r.rehydrateScript.Run(ctx, r.client,
        []string{cartKey},
        itemsJSON,
        ttlSeconds,
    ).Int()

    if err != nil {
        return fmt.Errorf("Lua Rehydrate error: %w", err)
    }

    return nil
}
```

### 2.3. Cache Repository (`internal/infrastructure/redis/cache_repo.go`)

Implement `CacheRepository` interface (Module 03).

```go
package redis

import (
    "context"
    "encoding/json"
    "fmt"
    "time"

    "github.com/redis/go-redis/v9"

    "cart-service/internal/common/keybuilder"
)

// CacheRepository implements port.CacheRepository using go-redis.
type CacheRepository struct {
    client         *redis.Client
    negativeCacheTTL time.Duration // 60 seconds
}

// NewCacheRepository creates a new cache repository.
func NewCacheRepository(client *redis.Client) *CacheRepository {
    return &CacheRepository{
        client:         client,
        negativeCacheTTL: 60 * time.Second,
    }
}

// Verify interface compliance.
var _ port.CacheRepository = (*CacheRepository)(nil)

// GetCart implements port.CacheRepository.GetCart.
// Returns map[skuID]JSONValue from Redis hash.
func (r *CacheRepository) GetCart(ctx context.Context, cartKey string) (map[string]string, error) {
    result, err := r.client.HGetAll(ctx, cartKey).Result()
    if err != nil {
        return nil, fmt.Errorf("HGETALL error: %w", err)
    }
    return result, nil
}

// DeleteNegativeCache implements port.CacheRepository.DeleteNegativeCache.
func (r *CacheRepository) DeleteNegativeCache(ctx context.Context, userID string) error {
    key := keybuilder.NegativeCacheKey(userID)
    return r.client.Del(ctx, key).Err()
}

// SetNegativeCache implements port.CacheRepository.SetNegativeCache.
func (r *CacheRepository) SetNegativeCache(ctx context.Context, userID string) error {
    key := keybuilder.NegativeCacheKey(userID)
    return r.client.Set(ctx, key, "1", r.negativeCacheTTL).Err()
}

// HasNegativeCache implements port.CacheRepository.HasNegativeCache.
func (r *CacheRepository) HasNegativeCache(ctx context.Context, userID string) (bool, error) {
    key := keybuilder.NegativeCacheKey(userID)
    exists, err := r.client.Exists(ctx, key).Result()
    return exists > 0, err
}

// SetItemAvailability implements port.CacheRepository.SetItemAvailability.
// This is the ONLY exception to the "read-only TTL" rule — updates is_available=false.
func (r *CacheRepository) SetItemAvailability(ctx context.Context, cartKey, skuID, itemJSON string) error {
    // Validate itemJSON is valid JSON
    var item map[string]interface{}
    if err := json.Unmarshal([]byte(itemJSON), &item); err != nil {
        return fmt.Errorf("invalid JSON: %w", err)
    }

    // Only update if is_available is false (the only allowed write-back case)
    if avail, ok := item["is_available"].(bool); ok && !avail {
        return r.client.HSet(ctx, cartKey, skuID, itemJSON).Err()
    }

    return nil // No-op if is_available is true
}

// Ping implements port.CacheRepository.Ping.
func (r *CacheRepository) Ping(ctx context.Context) error {
    return r.client.Ping(ctx).Err()
}
```

### 2.4. Outbox Writer (`internal/infrastructure/redis/outbox_writer.go`)

Implement `OutboxWriter` interface (Module 03). Used by Abandoned Cart Marker cronjob.

```go
package redis

import (
    "context"
    "fmt"

    "github.com/redis/go-redis/v9"
)

// OutboxWriter implements port.OutboxWriter for writing directly to Redis Stream.
// Used by Abandoned Cart Marker cronjob.
type OutboxWriter struct {
    client   *redis.Client
    numShards int // Default: 4
}

// NewOutboxWriter creates a new outbox writer.
func NewOutboxWriter(client *redis.Client, numShards int) *OutboxWriter {
    if numShards <= 0 {
        numShards = 4
    }
    return &OutboxWriter{
        client:   client,
        numShards: numShards,
    }
}

var _ port.OutboxWriter = (*OutboxWriter)(nil)

// WriteEvent writes a cart event to the appropriate global stream shard.
// Shard is calculated: shard_id = crc32(userID) % numShards
func (w *OutboxWriter) WriteEvent(
    ctx context.Context,
    shardID int,
    userID string,
    eventType string,
    payloadJSON string,
) (streamID string, err error) {

    if shardID < 0 || shardID >= w.numShards {
        return "", fmt.Errorf("invalid shard ID: %d", shardID)
    }

    streamKey := fmt.Sprintf("{cart}:events:%d", shardID)

    // XADD with auto-generated ID (*)
    // Fields: event_type, payload
    id, err := w.client.XAdd(ctx, &redis.XAddArgs{
        Stream: streamKey,
        ID:     "*", // Auto-generate ID
        Values: map[string]interface{}{
            "event_type": eventType,
            "payload":    payloadJSON,
        },
    }).Result()

    if err != nil {
        return "", fmt.Errorf("XADD failed: %w", err)
    }

    return id, nil
}

// Ping implements port.OutboxWriter.Ping.
func (w *OutboxWriter) Ping(ctx context.Context) error {
    return w.client.Ping(ctx).Err()
}
```

### 2.5. Key Builder (`internal/common/keybuilder/keybuilder.go`)

Utility for constructing Redis keys with proper Hash Tags.

```go
package keybuilder

import (
    "fmt"
    "hash/crc32"
)

const (
    KeyPrefix     = "{cart}"
    StreamPrefix  = "{cart}:events"
    NotFoundSuffix = ":notfound"
)

// CartKey returns the Redis hash key for a user's cart.
// Format: {cart}:{user_id}
// Contains Hash Tag {cart} for CROSSSLOT compatibility with stream shards.
func CartKey(userID string) string {
    return fmt.Sprintf("{cart}:%s", userID)
}

// StreamShardKey returns the global stream shard key.
// Format: {cart}:events:{shard_id}
func StreamShardKey(shardID int) string {
    return fmt.Sprintf("{cart}:events:%d", shardID)
}

// ComputeShardID computes the shard ID for a user.
// shard_id = crc32(userID) % numShards
func ComputeShardID(userID string, numShards int) int {
    if numShards <= 0 {
        numShards = 4
    }
    return int(crc32.ChecksumIEEE([]byte(userID))) % numShards
}

// StreamShardForUser returns the stream shard key for a user.
func StreamShardForUser(userID string, numShards int) string {
    shardID := ComputeShardID(userID, numShards)
    return StreamShardKey(shardID)
}

// NegativeCacheKey returns the negative cache key for a user.
// Format: {cart}:{user_id}:notfound
func NegativeCacheKey(userID string) string {
    return fmt.Sprintf("{cart}:%s:notfound", userID)
}

// IdempotencyKey returns the idempotency key for order paid events.
// Format: cart:idemp:order_paid:{order_id}
func IdempotencyKey(orderID int64) string {
    return fmt.Sprintf("cart:idemp:order_paid:%d", orderID)
}
```

---

## 3. Shard Management

### 3.1. Shard Manager

```go
// ShardManager handles Redis stream shard operations.
type ShardManager struct {
    client    *redis.Client
    numShards int
}

// NewShardManager creates a shard manager.
func NewShardManager(client *redis.Client, numShards int) *ShardManager {
    return &ShardManager{client: client, numShards: numShards}
}

// EnsureConsumerGroups creates consumer groups for all shards if they don't exist.
// Called at startup by main.go.
func (m *ShardManager) EnsureConsumerGroups(ctx context.Context, groupName string) error {
    for i := 0; i < m.numShards; i++ {
        streamKey := keybuilder.StreamShardKey(i)
        // MKSTREAM creates the stream if it doesn't exist
        err := m.client.XGroupCreateMkStream(ctx, streamKey, groupName, "0").Err()
        if err != nil {
            // Ignore "BUSYGROUP" error (group already exists)
            if err.Error() != "BUSYGROUP Consumer Group name already exists" {
                return fmt.Errorf("XGROUP CREATE failed for shard %d: %w", i, err)
            }
        }
    }
    return nil
}

// StreamKeys returns all stream shard keys.
func (m *ShardManager) StreamKeys() []string {
    keys := make([]string, m.numShards)
    for i := 0; i < m.numShards; i++ {
        keys[i] = keybuilder.StreamShardKey(i)
    }
    return keys
}
```

---

## 4. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `connection.go` | `internal/infrastructure/redis/connection.go` | Redis client setup |
| `cart_lua_repo.go` | `internal/infrastructure/redis/cart_lua_repo.go` | Lua script executor impl |
| `cache_repo.go` | `internal/infrastructure/redis/cache_repo.go` | Cache ops impl |
| `outbox_writer.go` | `internal/infrastructure/redis/outbox_writer.go` | Redis Stream writer impl |
| `keybuilder.go` | `internal/common/keybuilder/keybuilder.go` | Redis key construction |
| `scripts/scripts.go` | `internal/infrastructure/redis/scripts/scripts.go` | Lua embed |

---

## 5. Checklist triển khai Module 06

```markdown
## 06 - Infrastructure Redis
- [x] Tạo `internal/infrastructure/redis/connection.go` với client setup
- [x] Tạo `internal/infrastructure/redis/scripts/scripts.go` với embed directives
- [x] Tạo `internal/infrastructure/redis/cart_lua_repo.go` với đầy đủ Lua invocations
- [x] Tạo `internal/infrastructure/redis/cache_repo.go` với all cache operations
- [x] Tạo `internal/infrastructure/redis/outbox_writer.go` cho cronjob XADD
- [x] Tạo `internal/common/keybuilder/keybuilder.go` với key construction utilities
- [x] Tạo `internal/infrastructure/redis/shard_manager.go` với consumer group setup
- [x] Viết unit tests: mock Redis client, verify Lua invocation parameters
- [x] Viết unit tests: shard ID computation
- [x] Viết unit tests: negative cache TTL behavior
- [x] Verify go build ./internal/infrastructure/redis compile thành công
- [x] Verify go build ./internal/common/keybuilder compile thành công
- [x] Update cmd/server/main.go với module 06 wiring
- [x] Update internal/infrastructure/config/loader.go với đầy đủ config structs
```

---

*Tiếp theo: [07-infra-postgres.md](07-infra-postgres.md) — PostgreSQL connection, repositories, migrations.*
