package outbox_relay

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/port"
)

// PubSubOutboxWriter implements port.OutboxWriter using Redis Pub/Sub + Streams.
// It writes events to a Redis Stream shard and optionally publishes via Pub/Sub.
type PubSubOutboxWriter struct {
	redisClient redis.UniversalClient
	kb          *keybuilder.KeyBuilder
	logger      *zap.Logger
}

// NewPubSubOutboxWriter creates a new Pub/Sub outbox writer.
func NewPubSubOutboxWriter(
	redisClient redis.UniversalClient,
	kb *keybuilder.KeyBuilder,
	logger *zap.Logger,
) *PubSubOutboxWriter {
	return &PubSubOutboxWriter{
		redisClient: redisClient,
		kb:          kb,
		logger:      logger,
	}
}

var _ port.OutboxWriter = (*PubSubOutboxWriter)(nil)

// WriteEvent writes an event to a Redis Stream shard.
// It is used by the abandoned_marker job to publish CART_ABANDONED events.
func (w *PubSubOutboxWriter) WriteEvent(
	ctx context.Context,
	shardID int,
	userID string,
	eventType string,
	payloadJSON string,
) (streamID string, err error) {
	streamKey := w.kb.StreamShard(uint32(shardID))

	result, err := w.redisClient.XAdd(ctx, &redis.XAddArgs{
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

	w.logger.Debug("event written to stream",
		zap.String("stream_key", streamKey),
		zap.String("stream_id", result),
		zap.String("event_type", eventType),
		zap.String("user_id", userID),
	)

	return result, nil
}

// Ping checks Redis connectivity.
func (w *PubSubOutboxWriter) Ping(ctx context.Context) error {
	return w.redisClient.Ping(ctx).Err()
}

// WriteEventDirect writes an event directly to the stream (no Pub/Sub).
func (w *PubSubOutboxWriter) WriteEventDirect(
	ctx context.Context,
	streamKey string,
	eventType string,
	payloadJSON string,
) (string, error) {
	result, err := w.redisClient.XAdd(ctx, &redis.XAddArgs{
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

// PublishNotification publishes a notification via Pub/Sub channel.
func (w *PubSubOutboxWriter) PublishNotification(ctx context.Context, channel string, eventType string, payload map[string]interface{}) error {
	payloadJSON, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal notification payload: %w", err)
	}

	return w.redisClient.Publish(ctx, channel, string(payloadJSON)).Err()
}

// OutboxWriterWithPubSub wraps an OutboxWriter with Pub/Sub notifications.
type OutboxWriterWithPubSub struct {
	inner   port.OutboxWriter
	pubsub  *PubSubOutboxWriter
	logger  *zap.Logger
}

// NewOutboxWriterWithPubSub creates an outbox writer with Pub/Sub support.
func NewOutboxWriterWithPubSub(
	inner port.OutboxWriter,
	pubsub *PubSubOutboxWriter,
	logger *zap.Logger,
) *OutboxWriterWithPubSub {
	return &OutboxWriterWithPubSub{
		inner:  inner,
		pubsub: pubsub,
		logger: logger,
	}
}

// WriteEvent delegates to the inner writer.
func (w *OutboxWriterWithPubSub) WriteEvent(
	ctx context.Context,
	shardID int,
	userID string,
	eventType string,
	payloadJSON string,
) (string, error) {
	return w.inner.WriteEvent(ctx, shardID, userID, eventType, payloadJSON)
}

// Ping delegates to the inner writer.
func (w *OutboxWriterWithPubSub) Ping(ctx context.Context) error {
	return w.inner.Ping(ctx)
}
