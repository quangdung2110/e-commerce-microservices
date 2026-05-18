package postgres_sync

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	"github.com/ecommerce-platform/go/services/cart-service/internal/common/constants"
)

// CartEvent represents a cart event from Kafka
type CartEvent struct {
	EventID            string                 `json:"event_id"`
	EventType         string                 `json:"event_type"`
	AggregateType     string                 `json:"aggregate_type"`
	AggregateID       string                 `json:"aggregate_id"`
	CorrelationID     string                 `json:"correlation_id,omitempty"`
	Timestamp         string                 `json:"timestamp"`
	Source            string                 `json:"source"`
	StreamID          string                 `json:"stream_id"`
	StreamTimestampMs string                 `json:"stream_timestamp_ms"`
	EventSequence     string                 `json:"event_sequence"`
	Payload           map[string]interface{} `json:"payload"`
}

// ParseCartEvent parses a JSON message into CartEvent
func ParseCartEvent(data []byte) (*CartEvent, error) {
	var event CartEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, fmt.Errorf("failed to parse cart event: %w", err)
	}
	return &event, nil
}

// GetCartID returns the cart_id from the event
func (e *CartEvent) GetCartID() string {
	if e.AggregateID != "" {
		return e.AggregateID
	}
	if cartID, ok := e.Payload["cart_id"].(string); ok {
		return cartID
	}
	return ""
}

// GetUserID returns the user_id from the event payload
func (e *CartEvent) GetUserID() string {
	if userID, ok := e.Payload["user_id"].(string); ok {
		return userID
	}
	// Handle numeric user_id (from ORDER_PAID)
	if userIDFloat, ok := e.Payload["user_id"].(float64); ok {
		return strconv.FormatFloat(userIDFloat, 'f', 0, 64)
	}
	if userIDInt, ok := e.Payload["user_id"].(int64); ok {
		return strconv.FormatInt(userIDInt, 10)
	}
	if userIDInt, ok := e.Payload["user_id"].(int); ok {
		return strconv.Itoa(userIDInt)
	}
	return ""
}

// GetStreamID returns the stream_id for idempotency guard
func (e *CartEvent) GetStreamID() string {
	return e.StreamID
}

// GetEventType returns the event type
func (e *CartEvent) GetEventType() string {
	return e.EventType
}

// CartItemEventPayload represents the payload for CART_ITEM_ADDED/UPDATED events
type CartItemEventPayload struct {
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

// ParseCartItemPayload parses the payload for CART_ITEM_ADDED/UPDATED events
func (e *CartEvent) ParseCartItemPayload() (*CartItemEventPayload, error) {
	if e.Payload == nil {
		return nil, fmt.Errorf("payload is nil")
	}

	// Handle both string and number for numeric fields
	var quantity int
	switch q := e.Payload["quantity"].(type) {
	case float64:
		quantity = int(q)
	case int:
		quantity = q
	case int64:
		quantity = int(q)
	case string:
		quantity, _ = strconv.Atoi(q)
	}

	var unitPrice float64
	switch p := e.Payload["unit_price_at_add"].(type) {
	case float64:
		unitPrice = p
	case int:
		unitPrice = float64(p)
	case int64:
		unitPrice = float64(p)
	case string:
		unitPrice, _ = strconv.ParseFloat(p, 64)
	}

	var isAvailable bool
	switch a := e.Payload["is_available"].(type) {
	case bool:
		isAvailable = a
	case string:
		isAvailable = a == "true" || a == "1"
	}

	return &CartItemEventPayload{
		CartID:        getString(e.Payload, "cart_id"),
		SkuID:         getString(e.Payload, "sku_id"),
		UserID:        getString(e.Payload, "user_id"),
		ProductID:     getString(e.Payload, "product_id"),
		Quantity:      quantity,
		UnitPriceAtAdd: unitPrice,
		ProductName:   getString(e.Payload, "product_name"),
		VariantInfo:  getString(e.Payload, "variant_info"),
		ImageURL:     getString(e.Payload, "image_url"),
		IsAvailable:  isAvailable,
		AddedAt:      getString(e.Payload, "added_at"),
		UpdatedAt:    getString(e.Payload, "updated_at"),
	}, nil
}

// BuildProductSnapshot builds the product_snapshot JSONB from payload
func (p *CartItemEventPayload) BuildProductSnapshot() (string, error) {
	snapshot := map[string]interface{}{
		"product_name": p.ProductName,
		"variant_info": p.VariantInfo,
		"image_url":   p.ImageURL,
		"is_available": p.IsAvailable,
		"added_at":    p.AddedAt,
		"updated_at":  p.UpdatedAt,
	}

	data, err := json.Marshal(snapshot)
	if err != nil {
		return "", fmt.Errorf("failed to marshal product snapshot: %w", err)
	}
	return string(data), nil
}

// ToCartItem converts the payload to a CartItem model
func (p *CartItemEventPayload) ToCartItem(streamID string) (*CartItem, error) {
	snapshot, err := p.BuildProductSnapshot()
	if err != nil {
		return nil, err
	}

	return &CartItem{
		ItemID:          BuildItemID(p.CartID, p.SkuID),
		CartID:          p.CartID,
		ProductID:       p.ProductID,
		SkuID:          p.SkuID,
		Quantity:        p.Quantity,
		UnitPriceAtAdd:  p.UnitPriceAtAdd,
		ProductSnapshot: snapshot,
		RedisStreamID:   &streamID,
	}, nil
}

// CartItemDeletedPayload represents the payload for CART_ITEM_DELETED event
type CartItemDeletedPayload struct {
	CartID    string `json:"cart_id"`
	SkuID     string `json:"sku_id"`
	UserID    string `json:"user_id"`
	DeletedAt string `json:"deleted_at"`
}

// ParseCartItemDeletedPayload parses the payload for CART_ITEM_DELETED events
func (e *CartEvent) ParseCartItemDeletedPayload() (*CartItemDeletedPayload, error) {
	if e.Payload == nil {
		return nil, fmt.Errorf("payload is nil")
	}

	return &CartItemDeletedPayload{
		CartID:    getString(e.Payload, "cart_id"),
		SkuID:     getString(e.Payload, "sku_id"),
		UserID:    getString(e.Payload, "user_id"),
		DeletedAt: getString(e.Payload, "deleted_at"),
	}, nil
}

// GetItemID returns the item_id for CART_ITEM_DELETED
func (p *CartItemDeletedPayload) GetItemID() string {
	return BuildItemID(p.CartID, p.SkuID)
}

// CartClearedPayload represents the payload for CART_CLEARED event
type CartClearedPayload struct {
	CartID    string `json:"cart_id"`
	UserID    string `json:"user_id"`
	ClearedAt string `json:"cleared_at"`
}

// ParseCartClearedPayload parses the payload for CART_CLEARED events
func (e *CartEvent) ParseCartClearedPayload() (*CartClearedPayload, error) {
	if e.Payload == nil {
		return nil, fmt.Errorf("payload is nil")
	}

	return &CartClearedPayload{
		CartID:    getString(e.Payload, "cart_id"),
		UserID:    getString(e.Payload, "user_id"),
		ClearedAt: getString(e.Payload, "cleared_at"),
	}, nil
}

// CartCheckedOutPayload represents the payload for CART_CHECKED_OUT event
type CartCheckedOutPayload struct {
	CartID       string `json:"cart_id"`
	UserID       string `json:"user_id"`
	CheckedOutAt string `json:"checked_out_at"`
}

// ParseCartCheckedOutPayload parses the payload for CART_CHECKED_OUT events
func (e *CartEvent) ParseCartCheckedOutPayload() (*CartCheckedOutPayload, error) {
	if e.Payload == nil {
		return nil, fmt.Errorf("payload is nil")
	}

	return &CartCheckedOutPayload{
		CartID:       getString(e.Payload, "cart_id"),
		UserID:       getString(e.Payload, "user_id"),
		CheckedOutAt: getString(e.Payload, "checked_out_at"),
	}, nil
}

// CartAbandonedPayload represents the payload for CART_ABANDONED event
type CartAbandonedPayload struct {
	CartID      string `json:"cart_id"`
	UserID      string `json:"user_id"`
	AbandonedAt string `json:"abandoned_at"`
}

// ParseCartAbandonedPayload parses the payload for CART_ABANDONED events
func (e *CartEvent) ParseCartAbandonedPayload() (*CartAbandonedPayload, error) {
	if e.Payload == nil {
		return nil, fmt.Errorf("payload is nil")
	}

	return &CartAbandonedPayload{
		CartID:      getString(e.Payload, "cart_id"),
		UserID:      getString(e.Payload, "user_id"),
		AbandonedAt: getString(e.Payload, "abandoned_at"),
	}, nil
}

// getString safely extracts a string from a map
func getString(m map[string]interface{}, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

// IsAddOrUpdateEvent checks if event is CART_ITEM_ADDED or CART_ITEM_UPDATED
func (e *CartEvent) IsAddOrUpdateEvent() bool {
	return e.EventType == constants.EventTypeCartItemAdded ||
		e.EventType == constants.EventTypeCartItemUpdated
}

// IsDeleteEvent checks if event is CART_ITEM_DELETED
func (e *CartEvent) IsDeleteEvent() bool {
	return e.EventType == constants.EventTypeCartItemDeleted
}

// IsCartClearedEvent checks if event is CART_CLEARED
func (e *CartEvent) IsCartClearedEvent() bool {
	return e.EventType == constants.EventTypeCartCleared
}

// IsCartCheckedOutEvent checks if event is CART_CHECKED_OUT
func (e *CartEvent) IsCartCheckedOutEvent() bool {
	return e.EventType == constants.EventTypeCartCheckedOut
}

// IsCartAbandonedEvent checks if event is CART_ABANDONED
func (e *CartEvent) IsCartAbandonedEvent() bool {
	return e.EventType == constants.EventTypeCartAbandoned
}

// NormalizeUserID normalizes user_id to string format
func NormalizeUserID(userID interface{}) string {
	switch v := userID.(type) {
	case string:
		return v
	case float64:
		return strconv.FormatFloat(v, 'f', 0, 64)
	case int:
		return strconv.Itoa(v)
	case int64:
		return strconv.FormatInt(v, 10)
	default:
		return fmt.Sprintf("%v", v)
	}
}

// ValidateCartID validates cart_id format
func ValidateCartID(cartID string) bool {
	return strings.HasPrefix(cartID, "{cart:user:") && strings.HasSuffix(cartID, "}")
}
