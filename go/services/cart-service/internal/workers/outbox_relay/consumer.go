package outbox_relay

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce-platform/go/services/cart-service/internal/common/constants"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/event"
	"github.com/ecommerce-platform/go/services/cart-service/internal/common/keybuilder"
	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure/config"
)

// StreamConsumer consumes messages from Redis Streams and produces to Kafka
type StreamConsumer struct {
	redisClient   *redis.Client
	kafkaProducer KafkaProducer
	logger        *zap.Logger
	keyBuilder    *keybuilder.CartKeyBuilder
	metrics       *Metrics
	topic         string
	pollTimeout   time.Duration
	config        config.OutboxRelayConfig

	eventBuilder *event.EventBuilder
}

// NewStreamConsumer creates a new stream consumer
func NewStreamConsumer(
	redisClient *redis.Client,
	kafkaProducer KafkaProducer,
	logger *zap.Logger,
	keyBuilder *keybuilder.CartKeyBuilder,
	metrics *Metrics,
	topic string,
	pollTimeout time.Duration,
	cfg config.OutboxRelayConfig,
) *StreamConsumer {
	return &StreamConsumer{
		redisClient:   redisClient,
		kafkaProducer: kafkaProducer,
		logger:        logger,
		keyBuilder:    keyBuilder,
		metrics:       metrics,
		topic:         topic,
		pollTimeout:   pollTimeout,
		config:        cfg,
		eventBuilder:  event.NewEventBuilder(constants.ServiceNameCart),
	}
}

// consumerID represents this consumer's unique ID
var consumerIDCounter int64
var consumerIDMu sync.Mutex

func getNextConsumerID() string {
	consumerIDMu.Lock()
	defer consumerIDMu.Unlock()
	consumerIDCounter++
	return fmt.Sprintf("consumer-%d", consumerIDCounter)
}

// Start begins consuming streams from the channel
func (c *StreamConsumer) Start(ctx context.Context, streamCh chan string, workerID int) {
	consumerName := getNextConsumerID()
	c.logger.Info("Stream consumer started",
		zap.String("consumer", consumerName),
		zap.Int("worker_id", workerID),
	)

	for {
		select {
		case <-ctx.Done():
			c.logger.Info("Stream consumer stopped due to context",
				zap.String("consumer", consumerName),
			)
			return
		case streamKey, ok := <-streamCh:
			if !ok {
				return
			}
			c.consumeStream(ctx, streamKey, consumerName)
		}
	}
}

// consumeStream reads messages from a single stream
func (c *StreamConsumer) consumeStream(ctx context.Context, streamKey, consumerName string) {
	// Ensure consumer group exists
	if err := c.ensureConsumerGroup(ctx, streamKey); err != nil {
		c.logger.Error("Failed to ensure consumer group",
			zap.String("stream", streamKey),
			zap.Error(err),
		)
		return
	}

	// Read messages with XREADGROUP
	streams, err := c.redisClient.XReadGroup(ctx, &redis.XReadGroupArgs{
		Group:    "relay_group",
		Consumer: consumerName,
		Streams:  []string{streamKey, ">"},
		Count:    int64(c.config.BatchSize),
		Block:    c.pollTimeout,
	}).Result()

	if err != nil {
		if err != redis.Nil {
			c.logger.Error("XREADGROUP error",
				zap.String("stream", streamKey),
				zap.Error(err),
			)
		}
		return
	}

	for _, stream := range streams {
		c.processMessages(ctx, stream.Messages, stream.Stream, consumerName)
	}
}

// ensureConsumerGroup creates the consumer group if it doesn't exist
func (c *StreamConsumer) ensureConsumerGroup(ctx context.Context, streamKey string) error {
	// Try to create the group, ignore error if it already exists
	err := c.redisClient.XGroupCreate(ctx, streamKey, "relay_group", "0").Err()
	if err != nil && !strings.Contains(err.Error(), "BUSYGROUP") {
		return err
	}
	return nil
}

// processMessages processes a batch of messages from a stream
func (c *StreamConsumer) processMessages(ctx context.Context, messages []redis.XMessage, streamKey, consumerName string) {
	if len(messages) == 0 {
		return
	}

	c.logger.Debug("Processing messages",
		zap.String("stream", streamKey),
		zap.Int("count", len(messages)),
	)

	// Parse cart_id from stream key
	cartID := c.extractCartID(streamKey)
	if cartID == "" {
		c.logger.Error("Failed to parse cart_id from stream key",
			zap.String("stream", streamKey),
		)
		return
	}

	// Track messages to ACK
	messageIDs := make([]string, 0, len(messages))
	retryCount := make(map[string]int)

	for _, msg := range messages {
		eventType, _ := msg.Values["event_type"].(string)
		payload, _ := msg.Values["payload"].(string)

		if eventType == "" || payload == "" {
			c.logger.Warn("Invalid message format",
				zap.String("stream", streamKey),
				zap.String("msg_id", msg.ID),
			)
			continue
		}

		// Check retry count
		count := retryCount[msg.ID]
		if count >= c.config.MaxRetries {
			// Move to DLQ
			c.moveToDLQ(ctx, streamKey, msg.ID, eventType, payload, "max_retries_exceeded")
			messageIDs = append(messageIDs, msg.ID)
			c.metrics.IncDLQCount()
			continue
		}

		// Produce to Kafka
		err := c.produceToKafka(ctx, streamKey, msg.ID, cartID, eventType, payload)
		if err != nil {
			c.logger.Error("Failed to produce to Kafka",
				zap.String("stream", streamKey),
				zap.String("msg_id", msg.ID),
				zap.Error(err),
			)
			retryCount[msg.ID] = count + 1
			continue
		}

		// Success - track for ACK
		messageIDs = append(messageIDs, msg.ID)
		c.metrics.IncMessagesProduced()
	}

	// ACK all processed messages
	if len(messageIDs) > 0 {
		if err := c.redisClient.XAck(ctx, streamKey, "relay_group", messageIDs...).Err(); err != nil {
			c.logger.Error("Failed to ACK messages",
				zap.String("stream", streamKey),
				zap.Error(err),
			)
		} else {
			c.logger.Debug("ACKed messages",
				zap.String("stream", streamKey),
				zap.Int("count", len(messageIDs)),
			)
		}
	}
}

// extractCartID extracts cart_id from stream key
func (c *StreamConsumer) extractCartID(streamKey string) string {
	// Stream key format: {cart:user:{user_id}}:outbox
	// Cart ID format: {cart:user:{user_id}}
	return strings.TrimSuffix(streamKey, constants.KeySuffixOutbox)
}

// produceToKafka sends a message to Kafka with proper headers
func (c *StreamConsumer) produceToKafka(
	ctx context.Context,
	streamKey string,
	messageID string,
	cartID string,
	eventType string,
	payload string,
) error {
	// Parse payload
	var payloadMap map[string]interface{}
	if err := json.Unmarshal([]byte(payload), &payloadMap); err != nil {
		return fmt.Errorf("failed to parse payload: %w", err)
	}

	// Extract correlation_id from payload if present
	correlationID, _ := payloadMap["correlation_id"].(string)

	// Build the full cart event with headers
	// The event_id is deterministic from stream_id + cart_id
	eventID := event.GenerateDeterministicEventID(messageID, cartID)

	// Parse stream_id components
	parts := strings.SplitN(messageID, "-", 2)
	streamTimestampMs := parts[0]
	eventSequence := "0"
	if len(parts) == 2 {
		eventSequence = parts[1]
	}

	// Build headers
	headers := map[string]string{
		"event_id":             eventID,
		"event_type":           eventType,
		"aggregate_type":       "Cart",
		"aggregate_id":         cartID,
		"correlation_id":       correlationID,
		"timestamp":            time.Now().UTC().Format(time.RFC3339),
		"source":              constants.ServiceNameCart,
		"stream_id":           messageID,
		"stream_timestamp_ms":  streamTimestampMs,
		"event_sequence":       eventSequence,
	}

	// Build the full Kafka message
	kafkaMessage := map[string]interface{}{
		"event_id":             eventID,
		"event_type":           eventType,
		"aggregate_type":       "Cart",
		"aggregate_id":         cartID,
		"correlation_id":       correlationID,
		"timestamp":            time.Now().UTC().Format(time.RFC3339),
		"source":              constants.ServiceNameCart,
		"stream_id":           messageID,
		"stream_timestamp_ms":  streamTimestampMs,
		"event_sequence":       eventSequence,
		"payload":              payloadMap,
	}

	messageBytes, err := json.Marshal(kafkaMessage)
	if err != nil {
		return fmt.Errorf("failed to marshal message: %w", err)
	}

	// Produce to Kafka with cart_id as partition key
	if err := c.kafkaProducer.Produce(ctx, c.topic, []byte(cartID), messageBytes, headers); err != nil {
		return fmt.Errorf("failed to produce: %w", err)
	}

	c.logger.Debug("Produced message to Kafka",
		zap.String("topic", c.topic),
		zap.String("event_type", eventType),
		zap.String("stream_id", messageID),
		zap.String("cart_id", cartID),
	)

	return nil
}

// moveToDLQ moves a failed message to the dead letter queue
func (c *StreamConsumer) moveToDLQ(
	ctx context.Context,
	streamKey string,
	messageID string,
	eventType string,
	payload string,
	reason string,
) {
	dlqMessage := map[string]interface{}{
		"original_stream": streamKey,
		"original_id":     messageID,
		"event_type":      eventType,
		"payload":         payload,
		"error_reason":    reason,
		"failed_at":       time.Now().UTC().Format(time.RFC3339),
	}

	messageBytes, err := json.Marshal(dlqMessage)
	if err != nil {
		c.logger.Error("Failed to marshal DLQ message",
			zap.String("stream", streamKey),
			zap.String("msg_id", messageID),
			zap.Error(err),
		)
		return
	}

	// Add to DLQ stream
	_, err = c.redisClient.XAdd(ctx, &redis.XAddArgs{
		Stream: keybuilder.OutboxDLQKey,
		MaxLen: 10000, // Cap DLQ size
		Approx: true,
		Values: map[string]interface{}{
			"data": string(messageBytes),
		},
	}).Result()

	if err != nil {
		c.logger.Error("Failed to add to DLQ",
			zap.String("stream", streamKey),
			zap.String("msg_id", messageID),
			zap.Error(err),
		)
	} else {
		c.logger.Warn("Message moved to DLQ",
			zap.String("stream", streamKey),
			zap.String("msg_id", messageID),
			zap.String("reason", reason),
		)
	}
}

// ProcessPendingMessages handles pending messages that have been waiting too long
func (c *StreamConsumer) ProcessPendingMessages(ctx context.Context, streamKey, consumerName string) error {
	// Use XAUTOCLAIM to get pending messages older than configured timeout
	pendingTimeout := time.Duration(c.config.PendingTimeoutMs) * time.Millisecond

	// XAutoClaim with correct API using XAutoClaimArgs
	// Returns: messages, nextStart, err
	result, nextStart, err := c.redisClient.XAutoClaim(
		ctx,
		&redis.XAutoClaimArgs{
			Stream:   streamKey,
			Group:    "relay_group",
			Consumer: consumerName,
			MinIdle:  pendingTimeout,
			Start:    "0-0",
			Count:    100,
		},
	).Result()

	if err != nil {
		if err != redis.Nil {
			return fmt.Errorf("XAUTOCLAIM error: %w", err)
		}
		return nil
	}

	// Log next start for debugging
	c.logger.Debug("XAutoClaim completed",
		zap.String("stream", streamKey),
		zap.Int("messages_claimed", len(result)),
		zap.String("next_start", nextStart),
	)

	if len(result) > 0 {
		c.logger.Info("Claimed pending messages",
			zap.String("stream", streamKey),
			zap.Int("count", len(result)),
		)
		c.processMessages(ctx, result, streamKey, consumerName)
	}

	return nil
}

// Helper to convert interface{} to string
func toString(v interface{}) string {
	switch val := v.(type) {
	case string:
		return val
	case int:
		return strconv.Itoa(val)
	case int64:
		return strconv.FormatInt(val, 10)
	default:
		return fmt.Sprintf("%v", val)
	}
}
