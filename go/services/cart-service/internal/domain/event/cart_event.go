package event

import (
	"encoding/json"
	"fmt"
	"time"
)

// CartEvent represents an event in the cart event system
type CartEvent struct {
	EventID          string                 `json:"event_id"`
	EventType        string                 `json:"event_type"`
	AggregateType    string                 `json:"aggregate_type"`
	AggregateID      string                 `json:"aggregate_id"`
	CorrelationID    string                 `json:"correlation_id,omitempty"`
	Timestamp        string                 `json:"timestamp"`
	Source           string                 `json:"source"`
	StreamID         string                 `json:"stream_id,omitempty"`
	StreamTimestampMs string               `json:"stream_timestamp_ms,omitempty"`
	EventSequence    string                 `json:"event_sequence,omitempty"`
	Payload          map[string]interface{} `json:"payload"`
}

// NewCartEvent creates a new cart event
func NewCartEvent(eventType, aggregateID string, payload map[string]interface{}) *CartEvent {
	return &CartEvent{
		EventType:     eventType,
		AggregateType: "Cart",
		AggregateID:   aggregateID,
		Timestamp:     time.Now().UTC().Format(time.RFC3339),
		Source:        "cart-service",
		Payload:       payload,
	}
}

// ToJSON converts CartEvent to JSON bytes
func (e *CartEvent) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// CartItemAddedPayload represents the payload for CART_ITEM_ADDED event
type CartItemAddedPayload struct {
	CartID        string  `json:"cart_id"`
	SkuID         string  `json:"sku_id"`
	UserID        string  `json:"user_id"`
	ProductID     string  `json:"product_id"`
	Quantity      int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	ProductName   string  `json:"product_name"`
	VariantInfo  string  `json:"variant_info"`
	ImageURL     string  `json:"image_url"`
	IsAvailable  bool    `json:"is_available"`
	AddedAt      string  `json:"added_at"`
	UpdatedAt    string  `json:"updated_at"`
}

// ToMap converts CartItemAddedPayload to map
func (p *CartItemAddedPayload) ToMap() map[string]interface{} {
	return map[string]interface{}{
		"cart_id":         p.CartID,
		"sku_id":          p.SkuID,
		"user_id":         p.UserID,
		"product_id":      p.ProductID,
		"quantity":        p.Quantity,
		"unit_price_at_add": p.UnitPriceAtAdd,
		"product_name":    p.ProductName,
		"variant_info":    p.VariantInfo,
		"image_url":       p.ImageURL,
		"is_available":    p.IsAvailable,
		"added_at":        p.AddedAt,
		"updated_at":      p.UpdatedAt,
	}
}

// CartItemUpdatedPayload represents the payload for CART_ITEM_UPDATED event
// Same structure as CartItemAddedPayload
type CartItemUpdatedPayload = CartItemAddedPayload

// CartItemDeletedPayload represents the payload for CART_ITEM_DELETED event
type CartItemDeletedPayload struct {
	CartID     string `json:"cart_id"`
	SkuID      string `json:"sku_id"`
	UserID     string `json:"user_id"`
	DeletedAt  string `json:"deleted_at"`
}

// ToMap converts CartItemDeletedPayload to map
func (p *CartItemDeletedPayload) ToMap() map[string]interface{} {
	return map[string]interface{}{
		"cart_id":    p.CartID,
		"sku_id":     p.SkuID,
		"user_id":    p.UserID,
		"deleted_at": p.DeletedAt,
	}
}

// CartClearedPayload represents the payload for CART_CLEARED event
type CartClearedPayload struct {
	CartID    string `json:"cart_id"`
	UserID    string `json:"user_id"`
	ClearedAt string `json:"cleared_at"`
}

// ToMap converts CartClearedPayload to map
func (p *CartClearedPayload) ToMap() map[string]interface{} {
	return map[string]interface{}{
		"cart_id":    p.CartID,
		"user_id":    p.UserID,
		"cleared_at": p.ClearedAt,
	}
}

// CartCheckedOutPayload represents the payload for CART_CHECKED_OUT event
type CartCheckedOutPayload struct {
	CartID        string `json:"cart_id"`
	UserID        string `json:"user_id"`
	CheckedOutAt  string `json:"checked_out_at"`
}

// ToMap converts CartCheckedOutPayload to map
func (p *CartCheckedOutPayload) ToMap() map[string]interface{} {
	return map[string]interface{}{
		"cart_id":        p.CartID,
		"user_id":        p.UserID,
		"checked_out_at": p.CheckedOutAt,
	}
}

// CartAbandonedPayload represents the payload for CART_ABANDONED event
type CartAbandonedPayload struct {
	CartID      string `json:"cart_id"`
	UserID      string `json:"user_id"`
	AbandonedAt string `json:"abandoned_at"`
}

// ToMap converts CartAbandonedPayload to map
func (p *CartAbandonedPayload) ToMap() map[string]interface{} {
	return map[string]interface{}{
		"cart_id":      p.CartID,
		"user_id":      p.UserID,
		"abandoned_at": p.AbandonedAt,
	}
}

// OrderPaidPayload represents the payload for ORDER_PAID event (consumed from Order Service)
type OrderPaidPayload struct {
	OrderID             int64    `json:"order_id"`
	UserID              int64    `json:"user_id"`
	CartID              string   `json:"cart_id"`
	Status              string   `json:"status"`
	SkuIDs              []string `json:"sku_ids"`
	CheckoutSubmittedAt string   `json:"checkout_submitted_at"`
	PaidAt              string   `json:"paid_at"`
}

// ToMap converts OrderPaidPayload to map
func (p *OrderPaidPayload) ToMap() map[string]interface{} {
	return map[string]interface{}{
		"order_id":               p.OrderID,
		"user_id":                p.UserID,
		"cart_id":                p.CartID,
		"status":                 p.Status,
		"sku_ids":                p.SkuIDs,
		"checkout_submitted_at":  p.CheckoutSubmittedAt,
		"paid_at":                p.PaidAt,
	}
}

// EventBuilder helps build cart events
type EventBuilder struct {
	serviceName string
}

// NewEventBuilder creates a new event builder
func NewEventBuilder(serviceName string) *EventBuilder {
	return &EventBuilder{
		serviceName: serviceName,
	}
}

// Build creates a new cart event
func (b *EventBuilder) Build(eventType, aggregateID string, payload map[string]interface{}) *CartEvent {
	return &CartEvent{
		EventType:     eventType,
		AggregateType: "Cart",
		AggregateID:   aggregateID,
		Timestamp:     time.Now().UTC().Format(time.RFC3339),
		Source:        b.serviceName,
		Payload:       payload,
	}
}

// GenerateDeterministicEventID generates a deterministic event ID from message ID and cart ID
func GenerateDeterministicEventID(messageID, cartID string) string {
	combined := messageID + ":" + cartID
	hash := 0
	for _, c := range combined {
		hash = 31*hash + int(c)
	}
	return fmt.Sprintf("%s-%d", messageID, hash)
}

// Event types constants (for backward compatibility)
const (
	CartCleared    = "CART_CLEARED"
	CartCheckedOut = "CART_CHECKED_OUT"
	CartAbandoned  = "CART_ABANDONED"
	CartItemAdded  = "CART_ITEM_ADDED"
	CartItemUpdated = "CART_ITEM_UPDATED"
	CartItemDeleted = "CART_ITEM_DELETED"
	OrderPaid      = "ORDER_PAID"
)
