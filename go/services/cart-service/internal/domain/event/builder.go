package event

import (
	"encoding/json"
	"fmt"
	"time"

	"cart-service/internal/domain/entity"
)

// BuildItemPayload creates CartItemPayload from a CartItemValue.
func BuildItemPayload(cartID, userID, skuID string, item *entity.CartItemValue) *CartItemPayload {
	nowMs := fmt.Sprintf("%d", time.Now().UTC().UnixMilli())
	return &CartItemPayload{
		CartID:         cartID,
		SkuID:          skuID,
		UserID:         userID,
		ProductID:      item.ProductID,
		Quantity:       item.Quantity,
		UnitPriceAtAdd: item.UnitPriceAtAdd,
		ProductName:    item.ProductName,
		VariantInfo:    item.VariantInfo,
		ImageURL:       item.ImageURL,
		IsAvailable:    item.IsAvailable,
		AddedAt:        item.AddedAt,
		UpdatedAt:      nowMs,
	}
}

// PayloadToJSON serializes an event payload to JSON string.
func PayloadToJSON(v interface{}) (string, error) {
	data, err := json.Marshal(v)
	if err != nil {
		return "", fmt.Errorf("marshal payload: %w", err)
	}
	return string(data), nil
}

// ParseItemPayload deserializes CartItemPayload from a JSON string.
func ParseItemPayload(jsonStr string) (*CartItemPayload, error) {
	var p CartItemPayload
	if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
		return nil, fmt.Errorf("unmarshal item payload: %w", err)
	}
	return &p, nil
}

// ParseDeletedPayload deserializes CartItemDeletedPayload from a JSON string.
func ParseDeletedPayload(jsonStr string) (*CartItemDeletedPayload, error) {
	var p CartItemDeletedPayload
	if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
		return nil, fmt.Errorf("unmarshal deleted payload: %w", err)
	}
	return &p, nil
}

// ParseOrderPaidPayload deserializes OrderPaidPayload from a JSON string.
func ParseOrderPaidPayload(jsonStr string) (*OrderPaidPayload, error) {
	var p OrderPaidPayload
	if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
		return nil, fmt.Errorf("unmarshal order paid payload: %w", err)
	}
	return &p, nil
}
