package constants

// Cart Service Constants
// These constants define limits, TTLs, and configuration values used across the Cart Service

// TTL values in seconds
const (
	// CartTTL is the time-to-live for cart Redis keys (7 days)
	CartTTL = 604800 // 7 days in seconds

	// NegativeCacheTTL is the TTL for negative cache (60 seconds)
	NegativeCacheTTL = 60

	// IdempotencyKeyTTL is the TTL for order paid idempotency keys (24 hours)
	IdempotencyKeyTTL = 86400

	// IntentPublishTimeout is the timeout for intent publish operations
	IntentPublishTimeout = 30000 // 30 seconds in milliseconds
)

// Limit values
const (
	// MaxQuantityPerItem is the maximum quantity allowed per SKU in cart
	MaxQuantityPerItem = 100

	// MaxUniqueSKUs is the maximum number of unique SKUs allowed in a cart
	MaxUniqueSKUs = 50

	// MaxPayloadSize is the maximum size of a cart item JSON payload in bytes
	MaxPayloadSize = 8192 // 8KB
)

// Cart status values
const (
	CartStatusActive     = "ACTIVE"
	CartStatusAbandoned = "ABANDONED"
	CartStatusDeleted   = "DELETED"
	CartStatusCheckedOut = "CHECKED_OUT"
)

// Event types
const (
	EventTypeCartItemAdded   = "CART_ITEM_ADDED"
	EventTypeCartItemUpdated = "CART_ITEM_UPDATED"
	EventTypeCartItemDeleted = "CART_ITEM_DELETED"
	EventTypeCartCleared     = "CART_CLEARED"
	EventTypeCartCheckedOut  = "CART_CHECKED_OUT"
	EventTypeCartAbandoned   = "CART_ABANDONED"
	EventTypeOrderPaid       = "ORDER_PAID"
)

// Redis key prefixes
const (
	// KeyPrefixCart is the prefix for cart hash keys
	KeyPrefixCart = "cart:user:"

	// KeySuffixOutbox is the suffix for outbox stream keys
	KeySuffixOutbox = ":outbox"

	// KeySuffixNotFound is the suffix for negative cache keys
	KeySuffixNotFound = ":notfound"

	// KeyPrefixIdempotency is the prefix for idempotency keys
	KeyPrefixIdempotency = "cart:idemp:order_paid:"

	// KeyOutboxPublishIntents is the global key for tracking publish intents
	KeyOutboxPublishIntents = "outbox_publish_intents"

	// KeyActiveOutboxStreams is the global key for active outbox streams
	KeyActiveOutboxStreams = "active_outbox_streams"

	// KeyOutboxNotifications is the Pub/Sub channel for outbox notifications
	KeyOutboxNotifications = "outbox_notifications"

	// KeyOutboxDLQ is the dead letter queue stream
	KeyOutboxDLQ = "cart_outbox_dlq"
)

// Kafka configuration
const (
	// KafkaTopicCartEvents is the topic for cart events
	KafkaTopicCartEvents = "t.cart.events"

	// KafkaTopicOrderEvents is the topic for order events
	KafkaTopicOrderEvents = "t.order.events"

	// KafkaConsumerGroupCartDBSync is the consumer group for cart DB sync
	KafkaConsumerGroupCartDBSync = "cart-db-sync"

	// KafkaConsumerGroupOrderConsumer is the consumer group for order consumer
	KafkaConsumerGroupOrderConsumer = "cart-order-consumer"
)

// Service names
const (
	ServiceNameCart    = "cart-service"
	ServiceNameProduct = "product-service"
	ServiceNameOrder   = "order-service"
)

// Header names
const (
	HeaderUserID        = "X-User-Id"
	HeaderCorrelationID = "X-Correlation-Id"
	HeaderRequestID     = "X-Request-Id"
)

// Archival thresholds (in days)
const (
	// AbandonedThresholdDays is the number of days after which an active cart is considered abandoned
	AbandonedThresholdDays = 30

	// DeletedRetentionDays is the number of days to retain DELETED/CHECKED_OUT carts before hard delete
	DeletedRetentionDays = 90

	// AbandonedRetentionDays is the number of days to retain ABANDONED carts before hard delete
	AbandonedRetentionDays = 180
)

// Worker configuration
const (
	// StreamTrimmerInterval is the interval for stream trimmer in minutes
	StreamTrimmerInterval = 5

	// StreamTrimmerBatchSize is the batch size for stream trimmer
	StreamTrimmerBatchSize = 100

	// FallbackWorkerInterval is the interval for ZSET fallback worker in seconds
	FallbackWorkerInterval = 30

	// IntentRecoveryInterval is the interval for intent recovery worker in seconds
	IntentRecoveryInterval = 30

	// PendingTimeoutMs is the timeout for pending messages in milliseconds
	PendingTimeoutMs = 60000

	// MaxRetries is the maximum number of retries before moving to DLQ
	MaxRetries = 5

	// DLQMaxSize is the maximum size of the DLQ
	DLQMaxSize = 10000

	// DLQAutoQuarantineHours is the number of hours before auto-quarantine of DLQ messages
	DLQAutoQuarantineHours = 24
)

// Product service configuration
const (
	// ProductServiceTimeout is the default timeout for gRPC calls to product service
	ProductServiceTimeout = 200 // milliseconds

	// CircuitBreakerMaxFailures is the default max failures before opening circuit
	CircuitBreakerMaxFailures = 5

	// CircuitBreakerFailureTimeout is the default timeout before attempting recovery (seconds)
	CircuitBreakerFailureTimeout = 30

	// CircuitBreakerHalfOpenMaxRequests is the default max requests in half-open state
	CircuitBreakerHalfOpenMaxRequests = 3
)
