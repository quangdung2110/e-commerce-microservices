package interfaces

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/entity"
)

// RedisClient defines the Redis operations needed by cart service
type RedisClient interface {
	// EVAL executes a Lua script
	Eval(ctx context.Context, script string, keys []string, args ...interface{}) *redis.Cmd

	// EVALSHA executes a cached Lua script
	EvalSha(ctx context.Context, sha1 string, keys []string, args ...interface{}) *redis.Cmd

	// SCRIPTLOAD loads a Lua script into the script cache
	ScriptLoad(ctx context.Context, script string) *redis.StringCmd

	// HGetAll returns all fields and values of the hash stored at key
	HGetAll(ctx context.Context, key string) *redis.MapStringStringCmd

	// HGet returns the value of field in the hash stored at key
	HGet(ctx context.Context, key, field string) *redis.StringCmd

	// HSet sets field in the hash stored at key to value
	HSet(ctx context.Context, key string, values ...interface{}) *redis.IntCmd

	// HDel removes field(s) from the hash stored at key
	HDel(ctx context.Context, key string, fields ...string) *redis.IntCmd

	// HLen returns the number of fields in the hash stored at key
	HLen(ctx context.Context, key string) *redis.IntCmd

	// HExists returns whether field exists in the hash stored at key
	HExists(ctx context.Context, key, field string) *redis.BoolCmd

	// Exec executes queued commands
	Exec(ctx context.Context, pipe *redis.Pipeline) ([]redis.Cmder, error)

	// Pipeline returns a pipeline
	Pipeline() *redis.Pipeline

	// DEL removes the specified keys
	Del(ctx context.Context, keys ...string) *redis.IntCmd

	// EXPIRE sets a TTL on key
	Expire(ctx context.Context, key string, expiration time.Duration) *redis.BoolCmd

	// GET returns the value of key
	Get(ctx context.Context, key string) *redis.StringCmd

	// SET sets key to value with optional expiration
	Set(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.StatusCmd

	// SETNX sets key to value only if key does not exist
	SetNX(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.BoolCmd

	// XADD appends a new entry to the stream
	XAdd(ctx context.Context, args *redis.XAddArgs) *redis.StringCmd

	// XREADGROUP reads messages from stream groups
	XReadGroup(ctx context.Context, args *redis.XReadGroupArgs) *redis.XStreamSliceCmd

	// XACK acknowledges a message
	XAck(ctx context.Context, stream, group string, ids ...string) *redis.IntCmd

	// XLEN returns the number of entries in the stream
	XLen(ctx context.Context, stream string) *redis.IntCmd

	// XTRIM trims the stream to a specific size
	XTrim(ctx context.Context, key string, start string) *redis.IntCmd

	// XPENDING returns pending messages info
	XPending(ctx context.Context, stream, group string) *redis.XPendingCmd

	// XAUTOCLAIM changes (or acquires) ownership of messages
	XAutoClaim(ctx context.Context, stream, group, consumer, min, start string, count int64) *redis.XAutoClaimCmd

	// XINFOGROUPS returns stream group information
	XInfoGroups(ctx context.Context, key string) *redis.XInfoGroupsCmd

	// XGROUPCREATE creates a consumer group
	XGroupCreate(ctx context.Context, stream, group, start string) *redis.StatusCmd

	// XGROUP_CREATECONSUMER creates a consumer in a group
	XGroupCreateConsumer(ctx context.Context, stream, group, consumer string) *redis.IntCmd

	// ZADD adds member(s) to sorted set
	ZAdd(ctx context.Context, key string, members ...redis.Z) *redis.IntCmd

	// ZCARD returns the number of members in the sorted set
	ZCard(ctx context.Context, key string) *redis.IntCmd

	// ZRANGE returns a range of members in sorted set
	ZRange(ctx context.Context, key string, start, stop int64) *redis.StringSliceCmd

	// ZRANGEBYSCORE returns members in sorted set by score
	ZRangeByScore(ctx context.Context, key string, opt *redis.ZRangeBy) *redis.StringSliceCmd

	// ZSCAN iterates over sorted set
	ZScan(ctx context.Context, key string, cursor uint64, match string, count int64) *redis.ScanCmd

	// ZREM removes member(s) from sorted set
	ZRem(ctx context.Context, key string, members ...interface{}) *redis.IntCmd

	// PUBLISH publishes a message to a channel
	Publish(ctx context.Context, channel string, message interface{}) *redis.IntCmd

	// SUBSCRIBE subscribes to channels
	Subscribe(ctx context.Context, channels ...string) *redis.PubSub

	// PING pings the server
	Ping(ctx context.Context) *redis.StatusCmd
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

// ProductServiceClient defines the Product Service gRPC client operations
type ProductServiceClient interface {
	// GetProductSku retrieves a single product SKU
	GetProductSku(ctx context.Context, skuID string) (*entity.ProductInfo, error)

	// GetBatchProductSkus retrieves multiple product SKUs in batch
	GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.BatchProductSkusResponse, error)

	// Close closes the gRPC connection
	Close() error
}

// PostgresDB defines the PostgreSQL operations needed by cart service
type PostgresDB interface {
	// CreateCart creates a new cart record
	CreateCart(ctx context.Context, cart *CartRecord) error

	// UpdateCart updates a cart record
	UpdateCart(ctx context.Context, cart *CartRecord) error

	// GetCart retrieves a cart by ID
	GetCart(ctx context.Context, cartID string) (*CartRecord, error)

	// DeleteCart deletes a cart by ID
	DeleteCart(ctx context.Context, cartID string) error

	// CreateCartItem creates a new cart item
	CreateCartItem(ctx context.Context, item *CartItemRecord) error

	// UpsertCartItem upserts a cart item with stream_id guard
	UpsertCartItem(ctx context.Context, item *CartItemRecord) error

	// DeleteCartItem deletes a cart item
	DeleteCartItem(ctx context.Context, itemID string, streamID string) error

	// GetCartItems retrieves all items for a cart
	GetCartItems(ctx context.Context, cartID string) ([]*CartItemRecord, error)

	// BatchUpsertCartItems upserts multiple cart items
	BatchUpsertCartItems(ctx context.Context, items []*CartItemRecord) error

	// BatchDeleteCartItems deletes multiple cart items
	BatchDeleteCartItems(ctx context.Context, itemIDs []string) error

	// DeleteCartItemsByCartID deletes all items for a cart
	DeleteCartItemsByCartID(ctx context.Context, cartID string) error

	// GetAbandonedCarts retrieves carts that are abandoned
	GetAbandonedCarts(ctx context.Context, thresholdDays int, limit int) ([]*CartRecord, error)

	// GetExpiredCarts retrieves carts that should be archived
	GetExpiredCarts(ctx context.Context, status string, thresholdDays int, limit int) ([]*CartRecord, error)
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
	ItemID        string    `db:"item_id"`
	CartID        string    `db:"cart_id"`
	ProductID     string    `db:"product_id"`
	SkuID         string    `db:"sku_id"`
	Quantity      int       `db:"quantity"`
	UnitPriceAtAdd float64  `db:"unit_price_at_add"`
	ProductSnapshot string   `db:"product_snapshot"`
	RedisStreamID string    `db:"redis_stream_id"`
	CreatedAt     time.Time `db:"created_at"`
	UpdatedAt     time.Time `db:"updated_at"`
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
