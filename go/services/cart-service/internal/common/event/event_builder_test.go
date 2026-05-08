package event

import (
	"testing"
	"time"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/constants"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/entity"
)

func TestEventBuilder_BuildCartEvent(t *testing.T) {
	builder := NewEventBuilder("cart-service")

	tests := []struct {
		name          string
		eventType    string
		cartID       string
		payload      map[string]interface{}
		streamID     string
		correlationID string
		wantSource   string
	}{
		{
			name:       "event without stream ID",
			eventType:  "CART_ITEM_ADDED",
			cartID:     "{cart:user:12345}",
			payload:    map[string]interface{}{"sku_id": "sku-1001", "quantity": 2},
			streamID:   "",
			wantSource: "cart-service",
		},
		{
			name:       "event with stream ID",
			eventType:  "CART_ITEM_UPDATED",
			cartID:     "{cart:user:12345}",
			payload:    map[string]interface{}{"sku_id": "sku-1001", "quantity": 5},
			streamID:   "1708616400456-0",
			wantSource: "cart-service",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			event := builder.BuildCartEvent(tt.eventType, tt.cartID, tt.payload, tt.streamID, tt.correlationID)

			if event.EventType != tt.eventType {
				t.Errorf("EventType = %v, want %v", event.EventType, tt.eventType)
			}
			if event.AggregateType != "Cart" {
				t.Errorf("AggregateType = %v, want Cart", event.AggregateType)
			}
			if event.AggregateID != tt.cartID {
				t.Errorf("AggregateID = %v, want %v", event.AggregateID, tt.cartID)
			}
			if event.Source != tt.wantSource {
				t.Errorf("Source = %v, want %v", event.Source, tt.wantSource)
			}
			if event.Timestamp == "" {
				t.Error("Timestamp should not be empty")
			}
			if tt.streamID != "" {
				if event.StreamID != tt.streamID {
					t.Errorf("StreamID = %v, want %v", event.StreamID, tt.streamID)
				}
				if event.StreamTimestampMs != "1708616400456" {
					t.Errorf("StreamTimestampMs = %v, want 1708616400456", event.StreamTimestampMs)
				}
				if event.EventSequence != "0" {
					t.Errorf("EventSequence = %v, want 0", event.EventSequence)
				}
				if event.EventID == "" {
					t.Error("EventID should be generated from stream ID")
				}
			}
		})
	}
}

func TestEventBuilder_BuildCartItemAddedEvent(t *testing.T) {
	builder := NewEventBuilder("cart-service")

	payload := &entity.CartItemAddedPayload{
		CartID:    "{cart:user:12345}",
		SkuID:     "sku-1001",
		UserID:    "12345",
		ProductID: "prod-uuid",
		Quantity:  2,
		ProductName: "Áo Thun Nam",
		IsAvailable: true,
		AddedAt:   "1708530000123",
		UpdatedAt: "1708616400456",
	}

	event := builder.BuildCartItemAddedEvent(
		"{cart:user:12345}",
		payload,
		"1708616400456-0",
		"req-123",
	)

	if event.EventType != constants.EventTypeCartItemAdded {
		t.Errorf("EventType = %v, want %v", event.EventType, constants.EventTypeCartItemAdded)
	}
	if event.CorrelationID != "req-123" {
		t.Errorf("CorrelationID = %v, want req-123", event.CorrelationID)
	}
	if event.Payload["sku_id"] != "sku-1001" {
		t.Errorf("Payload sku_id = %v, want sku-1001", event.Payload["sku_id"])
	}
}

func TestEventBuilder_BuildCartItemDeletedEvent(t *testing.T) {
	builder := NewEventBuilder("cart-service")

	payload := &entity.CartItemDeletedPayload{
		CartID:    "{cart:user:12345}",
		SkuID:     "sku-1001",
		UserID:    "12345",
		DeletedAt: "1708616400899",
	}

	event := builder.BuildCartItemDeletedEvent(
		"{cart:user:12345}",
		payload,
		"1708616400899-0",
		"",
	)

	if event.EventType != constants.EventTypeCartItemDeleted {
		t.Errorf("EventType = %v, want %v", event.EventType, constants.EventTypeCartItemDeleted)
	}
	if event.Payload["sku_id"] != "sku-1001" {
		t.Errorf("Payload sku_id = %v, want sku-1001", event.Payload["sku_id"])
	}
}

func TestEventBuilder_BuildKafkaHeaders(t *testing.T) {
	builder := NewEventBuilder("cart-service")

	event := &entity.CartEvent{
		EventID:             "018e7b4a-1234-7def-8abc-123456789abc",
		EventType:           "CART_ITEM_ADDED",
		AggregateType:       "Cart",
		AggregateID:         "{cart:user:12345}",
		CorrelationID:       "req-123",
		Timestamp:           "2026-02-28T10:00:00Z",
		Source:              "cart-service",
		StreamID:            "1708616400456-0",
		StreamTimestampMs:    "1708616400456",
		EventSequence:       "0",
	}

	headers := builder.BuildKafkaHeaders(event)

	if headers.EventID != event.EventID {
		t.Errorf("EventID = %v, want %v", headers.EventID, event.EventID)
	}
	if headers.EventType != event.EventType {
		t.Errorf("EventType = %v, want %v", headers.EventType, event.EventType)
	}
	if headers.AggregateID != event.AggregateID {
		t.Errorf("AggregateID = %v, want %v", headers.AggregateID, event.AggregateID)
	}
	if headers.StreamID != event.StreamID {
		t.Errorf("StreamID = %v, want %v", headers.StreamID, event.StreamID)
	}
}

func TestKafkaHeaders_ToMap(t *testing.T) {
	headers := &KafkaHeaders{
		EventID:           "018e7b4a-1234-7def-8abc-123456789abc",
		EventType:         "CART_ITEM_ADDED",
		AggregateType:     "Cart",
		AggregateID:       "{cart:user:12345}",
		CorrelationID:     "req-123",
		Timestamp:         "2026-02-28T10:00:00Z",
		Source:            "cart-service",
		StreamID:          "1708616400456-0",
		StreamTimestampMs: "1708616400456",
		EventSequence:     "0",
	}

	m := headers.ToMap()

	expectedKeys := []string{
		"event_id", "event_type", "aggregate_type", "aggregate_id",
		"correlation_id", "timestamp", "source", "stream_id",
		"stream_timestamp_ms", "event_sequence",
	}

	for _, key := range expectedKeys {
		if _, ok := m[key]; !ok {
			t.Errorf("ToMap() missing key %q", key)
		}
	}
}

func TestGenerateDeterministicEventID(t *testing.T) {
	// Same inputs should produce same output
	id1 := generateDeterministicEventID("1708616400456-0", "{cart:user:12345}")
	id2 := generateDeterministicEventID("1708616400456-0", "{cart:user:12345}")

	if id1 != id2 {
		t.Errorf("Deterministic ID should be same for same inputs: %v != %v", id1, id2)
	}

	// Different inputs should produce different output
	id3 := generateDeterministicEventID("1708616400457-0", "{cart:user:12345}")
	if id1 == id3 {
		t.Errorf("Different inputs should produce different IDs: %v == %v", id1, id3)
	}
}

func TestParseStreamID(t *testing.T) {
	tests := []struct {
		name        string
		streamID    string
		wantTS      string
		wantSeq     string
	}{
		{
			name:     "standard stream ID",
			streamID: "1708616400456-0",
			wantTS:   "1708616400456",
			wantSeq:  "0",
		},
		{
			name:     "stream ID with sequence",
			streamID: "1708616400456-123",
			wantTS:   "1708616400456",
			wantSeq:  "123",
		},
		{
			name:     "empty stream ID",
			streamID: "",
			wantTS:   "0",
			wantSeq:  "0",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ts, seq := parseStreamID(tt.streamID)
			if ts != tt.wantTS {
				t.Errorf("parseStreamID() timestamp = %v, want %v", ts, tt.wantTS)
			}
			if seq != tt.wantSeq {
				t.Errorf("parseStreamID() sequence = %v, want %v", seq, tt.wantSeq)
			}
		})
	}
}

func TestStreamIDFromTimestamp(t *testing.T) {
	ts := time.UnixMilli(1708616400456)
	streamID := StreamIDFromTimestamp(ts)

	expected := "1708616400456-0"
	if streamID != expected {
		t.Errorf("StreamIDFromTimestamp() = %v, want %v", streamID, expected)
	}
}

func TestParseStreamID_Full(t *testing.T) {
	ts, seq, err := ParseStreamID("1708616400456-123")
	if err != nil {
		t.Fatalf("ParseStreamID() error = %v", err)
	}

	if ts != 1708616400456 {
		t.Errorf("timestamp = %v, want 1708616400456", ts)
	}
	if seq != 123 {
		t.Errorf("sequence = %v, want 123", seq)
	}
}

func TestParseStreamID_Invalid(t *testing.T) {
	_, _, err := ParseStreamID("invalid")
	if err == nil {
		t.Error("ParseStreamID() expected error for invalid input")
	}
}

func TestSHA256Hash(t *testing.T) {
	input := "test-input"
	hash := SHA256Hash(input)

	if len(hash) != 64 {
		t.Errorf("SHA256Hash() length = %v, want 64", len(hash))
	}

	// Same input should produce same hash
	hash2 := SHA256Hash(input)
	if hash != hash2 {
		t.Errorf("SHA256Hash() should be deterministic: %v != %v", hash, hash2)
	}
}
