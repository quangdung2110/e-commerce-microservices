package event

// RedisStreamMessage represents the raw structure of a Redis Stream entry.
// This is the format used by Lua scripts for XADD.
// Ref: cart_outbox_pattern.md §2.2
//
// Lua script XADD format:
//   XADD {cart}:events:{shard} * event_type CART_ITEM_ADDED payload '<json>'
type RedisStreamMessage struct {
	EventType string `json:"event_type"` // Field name in stream
	Payload   string `json:"payload"`    // Field name in stream (JSON string)
}

// StreamFieldNames are the actual field names used in Redis Stream entries.
var StreamFieldNames = struct {
	EventType string
	Payload   string
}{
	EventType: "event_type",
	Payload:   "payload",
}
