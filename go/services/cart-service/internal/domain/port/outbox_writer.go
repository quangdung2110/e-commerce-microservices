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
