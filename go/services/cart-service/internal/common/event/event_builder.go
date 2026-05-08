package event

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/constants"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/entity"
)

// EventBuilder builds Kafka messages with proper headers
type EventBuilder struct {
	source string
}

// NewEventBuilder creates a new EventBuilder
func NewEventBuilder(source string) *EventBuilder {
	return &EventBuilder{
		source: source,
	}
}

// BuildCartEvent builds a complete cart event with all required headers
func (b *EventBuilder) BuildCartEvent(
	eventType string,
	cartID string,
	payload map[string]interface{},
	streamID string,
	correlationID string,
) *entity.CartEvent {
	event := entity.NewCartEvent(eventType, cartID, payload)
	event.Source = b.source

	if streamID != "" {
		event.StreamID = streamID
		event.StreamTimestampMs, event.EventSequence = parseStreamID(streamID)
	}

	if correlationID != "" {
		event.CorrelationID = correlationID
	}

	// Generate deterministic event_id from stream_id + cart_id
	if streamID != "" {
		event.EventID = generateDeterministicEventID(streamID, cartID)
	} else {
		event.EventID = uuid.New().String()
	}

	return event
}

// BuildCartItemAddedEvent builds a CART_ITEM_ADDED event
func (b *EventBuilder) BuildCartItemAddedEvent(
	cartID string,
	payload *entity.CartItemAddedPayload,
	streamID string,
	correlationID string,
) *entity.CartEvent {
	return b.BuildCartEvent(constants.EventTypeCartItemAdded, cartID, payload.ToMap(), streamID, correlationID)
}

// BuildCartItemUpdatedEvent builds a CART_ITEM_UPDATED event
func (b *EventBuilder) BuildCartItemUpdatedEvent(
	cartID string,
	payload *entity.CartItemUpdatedPayload,
	streamID string,
	correlationID string,
) *entity.CartEvent {
	return b.BuildCartEvent(constants.EventTypeCartItemUpdated, cartID, payload.ToMap(), streamID, correlationID)
}

// BuildCartItemDeletedEvent builds a CART_ITEM_DELETED event
func (b *EventBuilder) BuildCartItemDeletedEvent(
	cartID string,
	payload *entity.CartItemDeletedPayload,
	streamID string,
	correlationID string,
) *entity.CartEvent {
	return b.BuildCartEvent(constants.EventTypeCartItemDeleted, cartID, payload.ToMap(), streamID, correlationID)
}

// BuildCartClearedEvent builds a CART_CLEARED event
func (b *EventBuilder) BuildCartClearedEvent(
	cartID string,
	payload *entity.CartClearedPayload,
	streamID string,
	correlationID string,
) *entity.CartEvent {
	return b.BuildCartEvent(constants.EventTypeCartCleared, cartID, payload.ToMap(), streamID, correlationID)
}

// BuildCartCheckedOutEvent builds a CART_CHECKED_OUT event
func (b *EventBuilder) BuildCartCheckedOutEvent(
	cartID string,
	payload *entity.CartCheckedOutPayload,
	streamID string,
	correlationID string,
) *entity.CartEvent {
	return b.BuildCartEvent(constants.EventTypeCartCheckedOut, cartID, payload.ToMap(), streamID, correlationID)
}

// BuildCartAbandonedEvent builds a CART_ABANDONED event
func (b *EventBuilder) BuildCartAbandonedEvent(
	cartID string,
	payload *entity.CartAbandonedPayload,
	streamID string,
	correlationID string,
) *entity.CartEvent {
	return b.BuildCartEvent(constants.EventTypeCartAbandoned, cartID, payload.ToMap(), streamID, correlationID)
}

// KafkaHeaders represents Kafka message headers
type KafkaHeaders struct {
	EventID          string
	EventType        string
	AggregateType    string
	AggregateID      string
	CorrelationID    string
	Timestamp        string
	Source           string
	StreamID         string
	StreamTimestampMs string
	EventSequence    string
}

// ToMap converts KafkaHeaders to a map
func (h *KafkaHeaders) ToMap() map[string]string {
	return map[string]string{
		"event_id":            h.EventID,
		"event_type":         h.EventType,
		"aggregate_type":      h.AggregateType,
		"aggregate_id":        h.AggregateID,
		"correlation_id":      h.CorrelationID,
		"timestamp":           h.Timestamp,
		"source":              h.Source,
		"stream_id":           h.StreamID,
		"stream_timestamp_ms": h.StreamTimestampMs,
		"event_sequence":      h.EventSequence,
	}
}

// BuildKafkaHeaders builds headers from a cart event
func (b *EventBuilder) BuildKafkaHeaders(event *entity.CartEvent) *KafkaHeaders {
	return &KafkaHeaders{
		EventID:           event.EventID,
		EventType:        event.EventType,
		AggregateType:    event.AggregateType,
		AggregateID:      event.AggregateID,
		CorrelationID:    event.CorrelationID,
		Timestamp:        event.Timestamp,
		Source:           event.Source,
		StreamID:         event.StreamID,
		StreamTimestampMs: event.StreamTimestampMs,
		EventSequence:    event.EventSequence,
	}
}

// parseStreamID parses a Redis Stream ID (e.g., "1708616400456-0") into timestamp and sequence
func parseStreamID(streamID string) (timestampMs, sequence string) {
	parts := strings.SplitN(streamID, "-", 2)
	if len(parts) == 2 {
		return parts[0], parts[1]
	}
	return "0", "0"
}

// generateDeterministicEventID generates a deterministic UUIDv5 from stream_id + cart_id
func generateDeterministicEventID(streamID, cartID string) string {
	// Create a deterministic namespace UUID
	namespace := uuid.MustParse("018e7b4a-1234-7def-8abc-123456789abc")

	// Combine stream_id and cart_id for the name
	name := fmt.Sprintf("%s:%s", streamID, cartID)

	// Generate UUIDv5
	return uuid.NewSHA1(namespace, []byte(name)).String()
}

// GenerateDeterministicEventID is exported for testing
func GenerateDeterministicEventID(streamID, cartID string) string {
	return generateDeterministicEventID(streamID, cartID)
}

// StreamIDFromTimestamp generates a stream ID from a timestamp
func StreamIDFromTimestamp(t time.Time) string {
	return fmt.Sprintf("%d-0", t.UnixMilli())
}

// ParseStreamID parses a Redis Stream ID
func ParseStreamID(streamID string) (timestampMs int64, sequence int64, err error) {
	parts := strings.SplitN(streamID, "-", 2)
	if len(parts) != 2 {
		return 0, 0, fmt.Errorf("invalid stream ID format: %s", streamID)
	}

	var ts, seq int64
	if _, err := fmt.Sscanf(parts[0], "%d", &ts); err != nil {
		return 0, 0, fmt.Errorf("invalid timestamp in stream ID: %s", streamID)
	}

	if _, err := fmt.Sscanf(parts[1], "%d", &seq); err != nil {
		return 0, 0, fmt.Errorf("invalid sequence in stream ID: %s", streamID)
	}

	return ts, seq, nil
}

// SHA256Hash generates a SHA256 hash of the input string
func SHA256Hash(input string) string {
	h := sha256.Sum256([]byte(input))
	return hex.EncodeToString(h[:])
}
