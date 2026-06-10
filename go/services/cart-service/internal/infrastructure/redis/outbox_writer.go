package redis

import (
	"context"
	"fmt"

	"github.com/redis/go-redis/v9"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/port"
)

// OutboxWriter implements port.OutboxWriter by writing events to Redis Streams.
// Used by Abandoned Cart Marker cronjob to publish CART_ABANDONED events.
type OutboxWriter struct {
	client *Client
	kb     *keybuilder.KeyBuilder
}

// NewOutboxWriter creates a new outbox writer.
func NewOutboxWriter(client *Client, kb *keybuilder.KeyBuilder) *OutboxWriter {
	return &OutboxWriter{
		client: client,
		kb:     kb,
	}
}

// Compile-time interface assertion
var _ port.OutboxWriter = (*OutboxWriter)(nil)

// WriteEvent writes a cart event to the appropriate global stream shard.
// Shard is calculated from user_id: shard_id = crc32(userID) % numShards.
func (w *OutboxWriter) WriteEvent(
	ctx context.Context,
	shardID int,
	userID string,
	eventType string,
	payloadJSON string,
) (streamID string, err error) {
	streamKey := w.kb.StreamShard(uint32(shardID))

	result, err := w.client.Raw().XAdd(ctx, &redis.XAddArgs{
		Stream: streamKey,
		MaxLen: 100000,
		Approx: true,
		Values: map[string]interface{}{
			"event_type": eventType,
			"payload":    payloadJSON,
		},
	}).Result()

	if err != nil {
		return "", fmt.Errorf("XADD %s: %w", streamKey, err)
	}

	return result, nil
}

// Ping checks Redis connectivity.
func (w *OutboxWriter) Ping(ctx context.Context) error {
	return w.client.Ping(ctx)
}
