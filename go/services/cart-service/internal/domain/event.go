package domain

import (
	"encoding/json"
	"fmt"
)

// EventTypeCartAbandoned is the only event Cart publishes today
// (contracts/events/cart/v1/asyncapi.yaml).
const EventTypeCartAbandoned = "CART_ABANDONED"

// CloudEvent type and source constants pinned by the event contract.
const (
	CloudEventType   = "com.ecommerce.cart.abandoned.v1"
	CloudEventSource = "/services/cart"
	CloudEventSpec   = "1.0"

	// cloudEventIDPrefix namespaces the deterministic event id so that a
	// future namespace never reuses an id from this one.
	cloudEventIDPrefix = "cart-outbox:" + Namespace
)

// OutboxRecord is one entry of a bucket outbox stream, as written by
// mark_abandoned.lua.
type OutboxRecord struct {
	// StreamID is the Redis entry id. It only orders within its own stream.
	StreamID string
	// Bucket is the outbox bucket the entry came from.
	Bucket int
	// EventType is the record's event type field. Only CART_ABANDONED exists.
	EventType string
	// Payload is the decoded abandonment payload.
	Payload AbandonedPayload
}

// AbandonedPayload is the payload field of an outbox record.
//
// It carries millisecond precision on the wire because the Lua script has no
// RFC 3339 formatter; the relay converts it when it seals the CloudEvent.
type AbandonedPayload struct {
	CartID           string `json:"cart_id"`
	UserID           string `json:"user_id"`
	AbandonmentCycle int64  `json:"abandonment_cycle"`
	OccurredAt       Millis `json:"occurred_at_ms"`
}

// CloudEvent is the CloudEvents 1.0 JSON envelope published to Kafka
// (contracts/shared/json-schema/v1/cloud-event.schema.json).
type CloudEvent struct {
	SpecVersion     string        `json:"specversion"`
	ID              string        `json:"id"`
	Type            string        `json:"type"`
	Source          string        `json:"source"`
	Subject         string        `json:"subject"`
	Time            string        `json:"time"`
	DataContentType string        `json:"datacontenttype"`
	DataSchema      string        `json:"dataschema"`
	Data            AbandonedData `json:"data"`
}

// AbandonedData is the `data` member of a CART_ABANDONED CloudEvent
// (contracts/events/cart/v1/schemas/cart-abandoned.schema.json).
//
// It carries internal identifiers only: no email, phone, address, item list,
// payment or device information.
type AbandonedData struct {
	CartID           string `json:"cart_id"`
	UserID           string `json:"user_id"`
	AbandonmentCycle int64  `json:"abandonment_cycle"`
	OccurredAt       string `json:"occurred_at"`
}

// CloudEventID derives the deterministic event id for one outbox entry.
//
// The id is a pure function of the outbox key and the stream id, so every
// retry of the same entry publishes the same id and Notification can
// deduplicate an at-least-once delivery.
func CloudEventID(outboxKey, streamID string) string {
	return fmt.Sprintf("%s:%s:%s", cloudEventIDPrefix, outboxKey, streamID)
}

// ToCloudEvent seals an outbox record into the published envelope.
func (r OutboxRecord) ToCloudEvent(outboxKey, dataSchema string) CloudEvent {
	occurredAt := r.Payload.OccurredAt.RFC3339()
	return CloudEvent{
		SpecVersion:     CloudEventSpec,
		ID:              CloudEventID(outboxKey, r.StreamID),
		Type:            CloudEventType,
		Source:          CloudEventSource,
		Subject:         "carts/" + r.Payload.CartID,
		Time:            occurredAt,
		DataContentType: "application/json",
		DataSchema:      dataSchema,
		Data: AbandonedData{
			CartID:           r.Payload.CartID,
			UserID:           r.Payload.UserID,
			AbandonmentCycle: r.Payload.AbandonmentCycle,
			OccurredAt:       occurredAt,
		},
	}
}

// DecodeAbandonedPayload parses the payload field of an outbox entry.
func DecodeAbandonedPayload(raw string) (AbandonedPayload, error) {
	var payload AbandonedPayload
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		return AbandonedPayload{}, fmt.Errorf("decode abandoned payload: %w", err)
	}
	if payload.CartID == "" || payload.UserID == "" {
		return AbandonedPayload{}, fmt.Errorf("abandoned payload is missing cart_id or user_id")
	}
	if payload.AbandonmentCycle < 1 {
		return AbandonedPayload{}, fmt.Errorf("abandonment_cycle must be at least 1, got %d", payload.AbandonmentCycle)
	}
	return payload, nil
}
