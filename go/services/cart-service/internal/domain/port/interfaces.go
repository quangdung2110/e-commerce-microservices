package port

import (
	"context"
	"time"

	goredis "github.com/redis/go-redis/v9"

	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/entity"
)

// Redis key constants for Intent Index protocol
// These MUST match the constants in cart_repository.go for consistency
const (
	// Intent Index keys
	KeyOutboxPublishIntents = "outbox:intents"     // ZSET for tracking publish intents
	KeyActiveOutboxStreams  = "outbox:active"      // ZSET for active streams
	KeyOutboxNotifications  = "outbox:notifications" // Pub/Sub channel for notifications
	KeyOutboxDLQ            = "outbox:dlq"         // Dead letter queue stream

	// Cart-specific key suffixes
	KeySuffixOutbox      = ":outbox"
	KeySuffixNotFound    = ":notfound"
	KeyPrefixIdempotency = "idem:"

	// Cart key prefix with Hash Tag
	KeyPrefixCart = "cart:user:"
)

// RedisClient defines the Redis operations needed by cart service
type RedisClient interface {
	// EVAL executes a Lua script
	Eval(ctx context.Context, script string, keys []string, args ...interface{}) *goredis.Cmd

	// EVALSHA executes a cached Lua script
	EvalSha(ctx context.Context, sha1 string, keys []string, args ...interface{}) *goredis.Cmd

	// SCRIPTLOAD loads a Lua script into the script cache
	ScriptLoad(ctx context.Context, script string) *goredis.StringCmd

	// HGetAll returns all fields and values of the hash stored at key
	HGetAll(ctx context.Context, key string) *goredis.MapStringStringCmd

	// HGet returns the value of field in the hash stored at key
	HGet(ctx context.Context, key, field string) *goredis.StringCmd

	// HSet sets field in the hash stored at key to value
	HSet(ctx context.Context, key string, values ...interface{}) *goredis.IntCmd

	// HDel removes field(s) from the hash stored at key
	HDel(ctx context.Context, key string, fields ...string) *goredis.IntCmd

	// HLen returns the number of fields in the hash stored at key
	HLen(ctx context.Context, key string) *goredis.IntCmd

	// HExists returns whether field exists in the hash stored at key
	HExists(ctx context.Context, key, field string) *goredis.BoolCmd

	// Exec executes queued commands
	Exec(ctx context.Context, pipe *goredis.Pipeline) ([]goredis.Cmder, error)

	// Pipeline returns a pipeline
	Pipeline() *goredis.Pipeline

	// DEL removes the specified keys
	Del(ctx context.Context, keys ...string) *goredis.IntCmd

	// EXPIRE sets a TTL on key
	Expire(ctx context.Context, key string, expiration time.Duration) *goredis.BoolCmd

	// GET returns the value of key
	Get(ctx context.Context, key string) *goredis.StringCmd

	// SET sets key to value with optional expiration
	Set(ctx context.Context, key string, value interface{}, expiration time.Duration) *goredis.StatusCmd

	// SETNX sets key to value only if key does not exist
	SetNX(ctx context.Context, key string, value interface{}, expiration time.Duration) *goredis.BoolCmd

	// EXISTS checks if a key exists
	Exists(ctx context.Context, keys ...string) *goredis.IntCmd

	// XADD appends a new entry to the stream
	XAdd(ctx context.Context, args *goredis.XAddArgs) *goredis.StringCmd

	// XREADGROUP reads messages from stream groups
	XReadGroup(ctx context.Context, args *goredis.XReadGroupArgs) *goredis.XStreamSliceCmd

	// XACK acknowledges a message
	XAck(ctx context.Context, stream, group string, ids ...string) *goredis.IntCmd

	// XLEN returns the number of entries in the stream
	XLen(ctx context.Context, stream string) *goredis.IntCmd

	// XTRIM trims the stream to a specific size
	XTrim(ctx context.Context, key string, start string) *goredis.IntCmd

	// XPENDING returns pending messages info
	XPending(ctx context.Context, stream, group string) *goredis.XPendingCmd

	// XAutoClaim changes (or acquires) ownership of messages
	XAutoClaim(ctx context.Context, args *goredis.XAutoClaimArgs) *goredis.XAutoClaimCmd

	// XPendingExt returns extended pending information
	XPendingExt(ctx context.Context, args *goredis.XPendingExtArgs) *goredis.XPendingExtCmd

	// XInfoGroups returns stream group information
	XInfoGroups(ctx context.Context, key string) *goredis.XInfoGroupsCmd

	// XGROUPCREATE creates a consumer group
	XGroupCreate(ctx context.Context, stream, group, start string) *goredis.StatusCmd

	// XGROUP_CREATECONSUMER creates a consumer in a group
	XGroupCreateConsumer(ctx context.Context, stream, group, consumer string) *goredis.IntCmd

	// ZADD adds member(s) to sorted set
	ZAdd(ctx context.Context, key string, members ...goredis.Z) *goredis.IntCmd

	// ZCARD returns the number of members in the sorted set
	ZCard(ctx context.Context, key string) *goredis.IntCmd

	// ZRANGE returns a range of members in sorted set
	ZRange(ctx context.Context, key string, start, stop int64) *goredis.StringSliceCmd

	// ZRANGEBYSCORE returns members in sorted set by score
	ZRangeByScore(ctx context.Context, key string, opt *goredis.ZRangeBy) *goredis.StringSliceCmd

	// ZSCAN iterates over sorted set
	ZScan(ctx context.Context, key string, cursor uint64, match string, count int64) *goredis.ScanCmd

	// ZREM removes member(s) from sorted set
	ZRem(ctx context.Context, key string, members ...interface{}) *goredis.IntCmd

	// PUBLISH publishes a message to a channel
	Publish(ctx context.Context, channel string, message interface{}) *goredis.IntCmd

	// SUBSCRIBE subscribes to channels
	Subscribe(ctx context.Context, channels ...string) *goredis.PubSub

	// PING pings the server
	Ping(ctx context.Context) *goredis.StatusCmd

	// ScriptExists checks if a script exists
	ScriptExists(ctx context.Context, hashes ...string) *goredis.BoolSliceCmd
}

// CartRepository defines operations for cart data access
type CartRepository interface {
	// AddOrUpdateItem executes Lua script for atomic add/update
	// Returns the updated item JSON and stream_id
	AddOrUpdateItem(ctx context.Context, userID, skuID string, payloadJSON string, step int64, eventTimeMs int64) (string, string, error)

	// BatchDeleteItems executes Lua script for batch delete
	// Returns deleted count and stream_ids
	BatchDeleteItems(ctx context.Context, userID string, skuIDs []string, clearEventType string, eventTimeMs int64) (int64, []string, error)

	// SilentRehydrate executes Lua script for cache-miss recovery
	// Does NOT emit events
	SilentRehydrate(ctx context.Context, userID string, items []entity.RehydrateItem) error

	// GetCart retrieves all items from cart
	GetCart(ctx context.Context, userID string) (map[string]string, error)

	// UpdateItemAvailability updates only the is_available field
	// Exception: Only used when gRPC returns is_available=false (no EXPIRE, no XADD)
	UpdateItemAvailability(ctx context.Context, userID, skuID string, isAvailable bool) error

	// LoadScripts loads all Lua scripts into Redis
	LoadScripts(ctx context.Context) error
}

// IntentIndexManager manages the Intent Index protocol for Outbox Pattern
type IntentIndexManager interface {
	// PreRegisterIntent registers a publish intent before Lua execution
	PreRegisterIntent(ctx context.Context, streamKey string) error

	// MarkActive marks stream as active and notifies workers
	MarkActive(ctx context.Context, streamKey string) error

	// ClearIntent removes the intent after successful publish
	ClearIntent(ctx context.Context, streamKey string) error

	// ExecuteWithIntent executes an operation with full intent index protocol
	ExecuteWithIntent(ctx context.Context, streamKey string, operation func() error) error

	// GetStaleIntents returns intents that haven't been processed
	GetStaleIntents(ctx context.Context, olderThan time.Duration) ([]string, error)

	// GetActiveOutboxStreams returns all active outbox streams from the ZSET
	GetActiveOutboxStreams(ctx context.Context) ([]string, error)
}

// NegativeCacheManager manages negative caching for cart reads
type NegativeCacheManager interface {
	// HasCachedResult checks if we have a cached "not found" result
	HasCachedResult(ctx context.Context, userID string) (bool, error)

	// SetNotFound caches the "not found" result with TTL
	SetNotFound(ctx context.Context, userID string) error

	// Invalidate removes the negative cache (called after successful write)
	Invalidate(ctx context.Context, userID string) error
}

// IdempotencyManager manages idempotency keys for ORDER_PAID events
type IdempotencyManager interface {
	// CheckAndLock attempts to acquire an idempotency lock
	// Returns true if lock acquired (not processed), false if already processed
	CheckAndLock(ctx context.Context, orderID string) (bool, error)

	// MarkDone marks the order as successfully processed
	MarkDone(ctx context.Context, orderID string) error

	// IsProcessed checks if order was already processed
	IsProcessed(ctx context.Context, orderID string) (bool, error)

	// GetStatus returns the current status of an order idempotency key
	GetStatus(ctx context.Context, orderID string) (string, error)
}

// KeyBuilder defines Redis key building operations
type KeyBuilder interface {
	// BuildCartKey builds a cart hash key with Hash Tag validation
	BuildCartKey(userID string) (string, error)

	// BuildOutboxStreamKey builds an outbox stream key for a cart
	BuildOutboxStreamKey(userID string) (string, error)

	// BuildNegativeCacheKey builds a negative cache key
	BuildNegativeCacheKey(userID string) (string, error)

	// ExtractUserIDFromCartID extracts user_id from cart key
	ExtractUserIDFromCartID(cartID string) (string, error)

	// IsValidCartKey validates cart key format
	IsValidCartKey(cartID string) bool

	// ValidateKeyPair validates that two keys have the same Hash Tag
	ValidateKeyPair(key1, key2 string) error
}

// CartRecord represents a cart record in the database
type CartRecord struct {
	CartID                    string     `db:"cart_id"`
	UserID                    string     `db:"user_id"`
	Status                    string     `db:"status"`
	LastEventStreamID         string     `db:"last_event_stream_id"`
	AbandonedEventPublishedAt *time.Time `db:"abandoned_event_published_at"`
	CreatedAt                 time.Time  `db:"created_at"`
	UpdatedAt                 time.Time  `db:"updated_at"`
}

// CartItemRecord represents a cart item record in the database
type CartItemRecord struct {
	ItemID         string    `db:"item_id"`
	CartID         string    `db:"cart_id"`
	ProductID      string    `db:"product_id"`
	SkuID          string    `db:"sku_id"`
	Quantity       int       `db:"quantity"`
	UnitPriceAtAdd float64   `db:"unit_price_at_add"`
	ProductSnapshot string    `db:"product_snapshot"`
	RedisStreamID  string    `db:"redis_stream_id"`
	CreatedAt      time.Time `db:"created_at"`
	UpdatedAt      time.Time `db:"updated_at"`
}

// PostgresDB defines the PostgreSQL operations needed by cart service
type PostgresDB interface {
	// GetCart retrieves a cart by ID
	GetCart(ctx context.Context, cartID string) (*CartRecord, error)

	// GetCartStatus retrieves the status of a cart
	GetCartStatus(ctx context.Context, cartID string) (string, error)

	// GetCartItems retrieves all items for a cart
	GetCartItems(ctx context.Context, cartID string) ([]*CartItemRecord, error)

	// UpsertCartItems upserts multiple cart items with stream_id guard
	UpsertCartItems(ctx context.Context, items []*CartItemRecord) error

	// DeleteCartItems deletes multiple cart items
	DeleteCartItems(ctx context.Context, itemIDs []string) error

	// DeleteCartItemsByCartID deletes all items for a cart
	DeleteCartItemsByCartID(ctx context.Context, cartID string) error

	// UpdateCartStatus updates the status of a cart with stream_id guard
	UpdateCartStatus(ctx context.Context, cartID string, status string, streamID string) error

	// CreateCart creates a new cart record
	CreateCart(ctx context.Context, cart *CartRecord) error

	// GetAbandonedCarts retrieves carts that are abandoned
	GetAbandonedCarts(ctx context.Context, thresholdDays int, limit int) ([]*CartRecord, error)

	// GetExpiredCarts retrieves carts that should be archived
	GetExpiredCarts(ctx context.Context, status string, thresholdDays int, limit int) ([]*CartRecord, error)

	// DeleteCart deletes a cart and its items
	DeleteCart(ctx context.Context, cartID string) error
}

// ProductServiceClient defines the Product Service gRPC client operations
type ProductServiceClient interface {
	// GetProductSku retrieves a single product SKU
	GetProductSku(ctx context.Context, skuID string) (*entity.ProductInfo, error)

	// GetBatchProductSkus retrieves multiple product SKUs in batch
	GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.BatchProductSkusResponse, error)

	// Close closes the gRPC connection
	Close() error
}

// KafkaProducer defines the Kafka producer operations needed by cart service
type KafkaProducer interface {
	// Produce sends a message to Kafka
	Produce(ctx context.Context, topic string, key []byte, value []byte, headers map[string]string) error

	// Flush flushes all pending messages
	Flush(timeout time.Duration) error

	// Close closes the producer
	Close() error
}

// KafkaConsumer defines the Kafka consumer operations
type KafkaConsumer interface {
	// Subscribe subscribes to a topic
	Subscribe(topic string) error

	// Poll polls for messages
	Poll(timeout time.Duration) ([]KafkaMessage, error)

	// Commit commits offsets
	Commit(offsets map[string]int64) error

	// Close closes the consumer
	Close() error
}

// KafkaMessage represents a Kafka message
type KafkaMessage struct {
	Topic     string
	Partition int32
	Offset    int64
	Key       []byte
	Value     []byte
	Headers   map[string]string
	Timestamp time.Time
}

// Logger defines the logging operations needed by cart service
type Logger interface {
	// Info logs an info message
	Info(msg string, fields ...interface{})

	// Debug logs a debug message
	Debug(msg string, fields ...interface{})

	// Warn logs a warning message
	Warn(msg string, fields ...interface{})

	// Error logs an error message
	Error(msg string, fields ...interface{})

	// Fatal logs a fatal message and exits
	Fatal(msg string, fields ...interface{})
}
