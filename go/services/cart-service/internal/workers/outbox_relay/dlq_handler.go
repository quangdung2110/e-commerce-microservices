package outbox_relay

import (
	"context"
	"fmt"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"cart-service/internal/domain/port"
)

// DLQConfig holds configuration for the Dead Letter Queue handler.
type DLQConfig struct {
	Topic     string
	Retries   int
	Backoff   time.Duration
	MaxBackoff time.Duration
}

// DLQHandler handles messages that failed to be processed after max retries.
type DLQHandler struct {
	producer  port.KafkaPublisher
	topic     string
	retries   int
	backoff   time.Duration
	maxBackoff time.Duration
	logger    *zap.Logger
}

// NewDLQHandler creates a new DLQ handler.
func NewDLQHandler(
	producer port.KafkaPublisher,
	topic string,
	retries int,
	backoff time.Duration,
	logger *zap.Logger,
) *DLQHandler {
	return &DLQHandler{
		producer:   producer,
		topic:      topic,
		retries:    retries,
		backoff:    backoff,
		maxBackoff: backoff * 4,
		logger:     logger,
	}
}

// HandleFailedMessage handles a message that has exhausted all retries.
// It publishes the message to the DLQ topic for later inspection.
func (h *DLQHandler) HandleFailedMessage(ctx context.Context, msg *sarama.ConsumerMessage, err error) error {
	h.logger.Error("message sent to DLQ after exhausting retries",
		zap.String("topic", h.topic),
		zap.Int64("offset", msg.Offset),
		zap.Int32("partition", msg.Partition),
		zap.Error(err),
	)

	dlqMsg := &port.CartEventMessage{
		EventID:    fmt.Sprintf("dlq-%d-%d", msg.Offset, msg.Partition),
		EventType:  "DLQ",
		Source:     "cart-service-outbox-relay",
		Timestamp:  time.Now().UTC().Format(time.RFC3339),
		StreamID:   fmt.Sprintf("%d-%d", msg.Offset, msg.Partition),
		Payload: map[string]interface{}{
			"original_topic":     msg.Topic,
			"original_partition": msg.Partition,
			"original_offset":    msg.Offset,
			"error":             err.Error(),
			"value":             string(msg.Value),
			"headers":            headersToMap(msg.Headers),
		},
	}

	if pubErr := h.producer.Publish(ctx, dlqMsg); pubErr != nil {
		h.logger.Error("failed to publish to DLQ",
			zap.Error(pubErr),
			zap.Int64("offset", msg.Offset),
		)
		return pubErr
	}

	return nil
}

// HandleFailedRedisMessage handles a Redis message that failed processing.
func (h *DLQHandler) HandleFailedRedisMessage(
	ctx context.Context,
	streamID string,
	eventType string,
	payload []byte,
	err error,
) error {
	h.logger.Warn("Redis message sent to DLQ",
		zap.String("stream_id", streamID),
		zap.String("event_type", eventType),
		zap.Error(err),
	)

	return nil
}

func headersToMap(headers []*sarama.RecordHeader) map[string]string {
	result := make(map[string]string, len(headers))
	for _, h := range headers {
		if h != nil {
			result[string(h.Key)] = string(h.Value)
		}
	}
	return result
}
