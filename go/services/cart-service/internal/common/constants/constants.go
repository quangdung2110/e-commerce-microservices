package constants

// Cart Service Constants

// TTL values in seconds
const (
	CartTTL           = 604800 // 7 days
	NegativeCacheTTL   = 60
	IdempotencyKeyTTL = 86400
)

// Limit values
const (
	MaxQuantityPerItem = 100
	MaxUniqueSKUs     = 50
	MaxPayloadSize    = 8192
)

// Service identifiers
const (
	ServiceNameCart = "cart-service"
)

// Cart status values
const (
	CartStatusActive     = "ACTIVE"
	CartStatusAbandoned  = "ABANDONED"
	CartStatusDeleted    = "DELETED"
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

// Redis key prefixes and suffixes
// NOTE: These match port/interfaces.go for consistency
const (
	KeyPrefixIdempotency    = "idem:"
	KeyOutboxPublishIntents = "outbox:intents"
	KeyActiveOutboxStreams = "outbox:active"
	KeyOutboxNotifications  = "outbox:notifications"
	KeyOutboxDLQ            = "outbox:dlq"
	KeySuffixOutbox         = ":outbox"
	KeySuffixNotFound       = ":notfound"
)

// Kafka configuration
const (
	KafkaTopicCartEvents           = "t.cart.events"
	KafkaTopicOrderEvents         = "t.order.events"
	KafkaConsumerGroupCartDBSync  = "cart-db-sync"
	KafkaConsumerGroupOrderConsumer = "cart-order-consumer"
)

// Header names
const (
	HeaderUserID        = "X-User-Id"
	HeaderCorrelationID = "X-Correlation-Id"
	HeaderRequestID     = "X-Request-Id"
)

// Worker thresholds
const (
	AbandonedThresholdDays     = 30
	DeletedRetentionDays       = 90
	AbandonedRetentionDays     = 180
	StreamTrimmerInterval     = 5
	FallbackWorkerInterval    = 30
	IntentRecoveryInterval    = 30
	PendingTimeoutMs          = 60000
	MaxRetries                = 5
	DLQMaxSize                = 10000
	DLQAutoQuarantineHours    = 24
)

// Product service
const (
	ProductServiceTimeout             = 200
	CircuitBreakerMaxFailures        = 5
	CircuitBreakerFailureTimeout     = 30
	CircuitBreakerHalfOpenMaxRequests = 3
)
