package postgres_sync

import (
	"encoding/json"
	"fmt"
	"time"

	"cart-service/internal/domain/event"
)

// ParseItemPayloadFromEnvelope parses CartItemPayload from a CartEventEnvelope.
func ParseItemPayloadFromEnvelope(env *event.CartEventEnvelope) (*CartItemPayload, error) {
	payloadBytes, err := json.Marshal(env.Payload)
	if err != nil {
		return nil, fmt.Errorf("marshal payload: %w", err)
	}

	var payload CartItemPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		return nil, fmt.Errorf("unmarshal item payload: %w", err)
	}

	return &payload, nil
}

// ParseDeletedPayloadFromEnvelope parses CartItemDeletedPayload from a CartEventEnvelope.
func ParseDeletedPayloadFromEnvelope(env *event.CartEventEnvelope) (*CartItemDeletedPayload, error) {
	payloadBytes, err := json.Marshal(env.Payload)
	if err != nil {
		return nil, fmt.Errorf("marshal payload: %w", err)
	}

	var payload CartItemDeletedPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		return nil, fmt.Errorf("unmarshal deleted payload: %w", err)
	}

	return &payload, nil
}

// ParseClearedPayloadFromEnvelope parses cleared payload (as CartItemPayload) from a CartEventEnvelope.
func ParseClearedPayloadFromEnvelope(env *event.CartEventEnvelope) (*CartItemPayload, error) {
	return ParseItemPayloadFromEnvelope(env)
}

// ParseCheckedOutPayloadFromEnvelope parses checked_out payload (as CartItemPayload) from a CartEventEnvelope.
func ParseCheckedOutPayloadFromEnvelope(env *event.CartEventEnvelope) (*CartItemPayload, error) {
	return ParseItemPayloadFromEnvelope(env)
}

// ParseAbandonedPayloadFromEnvelope parses CartAbandonedPayload from a CartEventEnvelope.
func ParseAbandonedPayloadFromEnvelope(env *event.CartEventEnvelope) (*CartAbandonedPayload, error) {
	payloadBytes, err := json.Marshal(env.Payload)
	if err != nil {
		return nil, fmt.Errorf("marshal payload: %w", err)
	}

	var payload CartAbandonedPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		return nil, fmt.Errorf("unmarshal abandoned payload: %w", err)
	}

	return &payload, nil
}

// CartItemPayload represents an item event payload for PostgreSQL persistence.
type CartItemPayload struct {
	CartID        string    `json:"cart_id"`
	SkuID         string    `json:"sku_id"`
	UserID        string    `json:"user_id"`
	ProductID     string    `json:"product_id"`
	Quantity      int       `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	ProductName   string    `json:"product_name"`
	VariantInfo   string    `json:"variant_info,omitempty"`
	ImageURL      string    `json:"image_url,omitempty"`
	IsAvailable   bool      `json:"is_available"`
	AddedAt       string    `json:"added_at"`
	UpdatedAt     string    `json:"updated_at"`
}

// CartItemDeletedPayload represents a delete event payload.
type CartItemDeletedPayload struct {
	CartID    string `json:"cart_id"`
	SkuID     string `json:"sku_id"`
	UserID    string `json:"user_id"`
	DeletedAt string `json:"deleted_at"`
}

// CartAbandonedPayload represents an abandoned cart event payload.
type CartAbandonedPayload struct {
	CartID      string `json:"cart_id"`
	UserID      string `json:"user_id"`
	AbandonedAt string `json:"abandoned_at"`
}

// ParsedEvent represents a parsed event with its type and payload.
type ParsedEvent struct {
	EventType string
	CartID    string
	StreamID  string
	Timestamp time.Time
	Payload   interface{}
}
