// Package constants holds header names, media types and Redis key helpers
// shared across layers.
package constants

const (
	HeaderUserID          = "X-User-Id"
	HeaderRequestID       = "X-Request-Id"
	HeaderServiceIdentity = "X-Service-Identity"
	HeaderIdempotencyKey  = "Idempotency-Key"
	HeaderCacheControl    = "Cache-Control"
	HeaderRetryAfter      = "Retry-After"
	HeaderDeliveryID      = "X-Notification-Delivery-Id"

	NoStore            = "no-store"
	ContentTypeJSON    = "application/json"
	ContentTypeProblem = "application/problem+json"

	// CloudEvents constants for outbound events (contracts/events/notification/v1).
	EventSource          = "/services/notification"
	EventDataContentType = "application/json"
	KafkaContentType     = "application/cloudevents+json"

	// Ops control keys stored in ops_control.
	ControlLanePrefix = "lane:" // lane:<lane> → {"email":"active|paused","in_app":"active|paused",...}
	ControlReplayMode = "replay_mode"

	// Postgres NOTIFY channel prefix used to wake critical deliver workers.
	NotifyChannelPrefix = "delivery_ready_"
)

// Redis key layout (09-data-model.md §2). All keys are namespaced by the
// configured prefix.
const (
	RedisKeyContact  = "contact:%s"        // contact:{user_id} → JSON contact, TTL 15m
	RedisKeyFreqCap  = "freq:%s:%s"        // freq:{user_id}:{YYYY-MM-DD}
	RedisKeyUnread   = "inbox:unread:%s"   // inbox:unread:{user_id}
	RedisKeyBucket   = "bucket:%s:%s"      // bucket:{lane}:{channel} token bucket
	RedisKeyDaily    = "daily:%s:%s"       // daily:{provider}:{YYYY-MM-DD}
	RedisKeyRetryWin = "retrywin:%s:%s:%d" // retrywin:{lane}:{kind}:{minute}
)
