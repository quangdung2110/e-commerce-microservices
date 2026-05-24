package event

import (
	"encoding/json"
	"testing"
	"time"

	"cart-service/internal/domain/entity"
)

func TestCartEventTypes(t *testing.T) {
	t.Run("event type constants are defined", func(t *testing.T) {
		if EventTypeCartItemAdded != "CART_ITEM_ADDED" {
			t.Errorf("expected CART_ITEM_ADDED, got %s", EventTypeCartItemAdded)
		}
		if EventTypeCartItemUpdated != "CART_ITEM_UPDATED" {
			t.Errorf("expected CART_ITEM_UPDATED, got %s", EventTypeCartItemUpdated)
		}
		if EventTypeCartItemDeleted != "CART_ITEM_DELETED" {
			t.Errorf("expected CART_ITEM_DELETED, got %s", EventTypeCartItemDeleted)
		}
		if EventTypeCartCleared != "CART_CLEARED" {
			t.Errorf("expected CART_CLEARED, got %s", EventTypeCartCleared)
		}
		if EventTypeCartCheckedOut != "CART_CHECKED_OUT" {
			t.Errorf("expected CART_CHECKED_OUT, got %s", EventTypeCartCheckedOut)
		}
		if EventTypeCartAbandoned != "CART_ABANDONED" {
			t.Errorf("expected CART_ABANDONED, got %s", EventTypeCartAbandoned)
		}
		if EventTypeOrderPaid != "ORDER_PAID" {
			t.Errorf("expected ORDER_PAID, got %s", EventTypeOrderPaid)
		}
		if AggregateTypeCart != "Cart" {
			t.Errorf("expected Cart, got %s", AggregateTypeCart)
		}
	})
}

func TestCartItemPayloadRoundTrip(t *testing.T) {
	payload := &CartItemPayload{
		CartID:         "cart-123",
		SkuID:          "sku-456",
		UserID:         "user-789",
		ProductID:      "prod-abc",
		Quantity:       3,
		UnitPriceAtAdd: 19.99,
		ProductName:    "Test Product",
		VariantInfo:    "Size: L",
		ImageURL:       "https://example.com/img.jpg",
		IsAvailable:    true,
		AddedAt:        "1708616400456",
		UpdatedAt:      "1708616400500",
	}

	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal CartItemPayload: %v", err)
	}

	var roundtrip CartItemPayload
	if err := json.Unmarshal(data, &roundtrip); err != nil {
		t.Fatalf("unmarshal CartItemPayload: %v", err)
	}

	if roundtrip.CartID != payload.CartID {
		t.Errorf("CartID mismatch: got %s, want %s", roundtrip.CartID, payload.CartID)
	}
	if roundtrip.SkuID != payload.SkuID {
		t.Errorf("SkuID mismatch: got %s, want %s", roundtrip.SkuID, payload.SkuID)
	}
	if roundtrip.UserID != payload.UserID {
		t.Errorf("UserID mismatch: got %s, want %s", roundtrip.UserID, payload.UserID)
	}
	if roundtrip.ProductID != payload.ProductID {
		t.Errorf("ProductID mismatch: got %s, want %s", roundtrip.ProductID, payload.ProductID)
	}
	if roundtrip.Quantity != payload.Quantity {
		t.Errorf("Quantity mismatch: got %d, want %d", roundtrip.Quantity, payload.Quantity)
	}
	if roundtrip.UnitPriceAtAdd != payload.UnitPriceAtAdd {
		t.Errorf("UnitPriceAtAdd mismatch: got %f, want %f", roundtrip.UnitPriceAtAdd, payload.UnitPriceAtAdd)
	}
	if roundtrip.ProductName != payload.ProductName {
		t.Errorf("ProductName mismatch: got %s, want %s", roundtrip.ProductName, payload.ProductName)
	}
	if roundtrip.VariantInfo != payload.VariantInfo {
		t.Errorf("VariantInfo mismatch: got %s, want %s", roundtrip.VariantInfo, payload.VariantInfo)
	}
	if roundtrip.ImageURL != payload.ImageURL {
		t.Errorf("ImageURL mismatch: got %s, want %s", roundtrip.ImageURL, payload.ImageURL)
	}
	if roundtrip.IsAvailable != payload.IsAvailable {
		t.Errorf("IsAvailable mismatch: got %v, want %v", roundtrip.IsAvailable, payload.IsAvailable)
	}
	if roundtrip.AddedAt != payload.AddedAt {
		t.Errorf("AddedAt mismatch: got %s, want %s", roundtrip.AddedAt, payload.AddedAt)
	}
	if roundtrip.UpdatedAt != payload.UpdatedAt {
		t.Errorf("UpdatedAt mismatch: got %s, want %s", roundtrip.UpdatedAt, payload.UpdatedAt)
	}
}

func TestCartItemDeletedPayloadRoundTrip(t *testing.T) {
	payload := &CartItemDeletedPayload{
		CartID:    "cart-123",
		SkuID:     "sku-456",
		UserID:    "user-789",
		DeletedAt: "1708616400555",
	}

	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal CartItemDeletedPayload: %v", err)
	}

	var roundtrip CartItemDeletedPayload
	if err := json.Unmarshal(data, &roundtrip); err != nil {
		t.Fatalf("unmarshal CartItemDeletedPayload: %v", err)
	}

	if roundtrip.CartID != payload.CartID {
		t.Errorf("CartID mismatch: got %s, want %s", roundtrip.CartID, payload.CartID)
	}
	if roundtrip.SkuID != payload.SkuID {
		t.Errorf("SkuID mismatch: got %s, want %s", roundtrip.SkuID, payload.SkuID)
	}
	if roundtrip.UserID != payload.UserID {
		t.Errorf("UserID mismatch: got %s, want %s", roundtrip.UserID, payload.UserID)
	}
	if roundtrip.DeletedAt != payload.DeletedAt {
		t.Errorf("DeletedAt mismatch: got %s, want %s", roundtrip.DeletedAt, payload.DeletedAt)
	}
}

func TestCartClearedPayloadRoundTrip(t *testing.T) {
	payload := &CartClearedPayload{
		CartID:    "cart-123",
		UserID:    "user-789",
		ClearedAt: "1708616400555",
	}

	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal CartClearedPayload: %v", err)
	}

	var roundtrip CartClearedPayload
	if err := json.Unmarshal(data, &roundtrip); err != nil {
		t.Fatalf("unmarshal CartClearedPayload: %v", err)
	}

	if roundtrip.CartID != payload.CartID {
		t.Errorf("CartID mismatch: got %s, want %s", roundtrip.CartID, payload.CartID)
	}
	if roundtrip.UserID != payload.UserID {
		t.Errorf("UserID mismatch: got %s, want %s", roundtrip.UserID, payload.UserID)
	}
	if roundtrip.ClearedAt != payload.ClearedAt {
		t.Errorf("ClearedAt mismatch: got %s, want %s", roundtrip.ClearedAt, payload.ClearedAt)
	}
}

func TestCartCheckedOutPayloadRoundTrip(t *testing.T) {
	payload := &CartCheckedOutPayload{
		CartID:       "cart-123",
		UserID:       "user-789",
		CheckedOutAt: "1708616400555",
	}

	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal CartCheckedOutPayload: %v", err)
	}

	var roundtrip CartCheckedOutPayload
	if err := json.Unmarshal(data, &roundtrip); err != nil {
		t.Fatalf("unmarshal CartCheckedOutPayload: %v", err)
	}

	if roundtrip.CartID != payload.CartID {
		t.Errorf("CartID mismatch: got %s, want %s", roundtrip.CartID, payload.CartID)
	}
	if roundtrip.UserID != payload.UserID {
		t.Errorf("UserID mismatch: got %s, want %s", roundtrip.UserID, payload.UserID)
	}
	if roundtrip.CheckedOutAt != payload.CheckedOutAt {
		t.Errorf("CheckedOutAt mismatch: got %s, want %s", roundtrip.CheckedOutAt, payload.CheckedOutAt)
	}
}

func TestCartAbandonedPayloadRoundTrip(t *testing.T) {
	payload := &CartAbandonedPayload{
		CartID:      "cart-123",
		UserID:      "user-789",
		AbandonedAt: "1708616400555",
	}

	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal CartAbandonedPayload: %v", err)
	}

	var roundtrip CartAbandonedPayload
	if err := json.Unmarshal(data, &roundtrip); err != nil {
		t.Fatalf("unmarshal CartAbandonedPayload: %v", err)
	}

	if roundtrip.CartID != payload.CartID {
		t.Errorf("CartID mismatch: got %s, want %s", roundtrip.CartID, payload.CartID)
	}
	if roundtrip.UserID != payload.UserID {
		t.Errorf("UserID mismatch: got %s, want %s", roundtrip.UserID, payload.UserID)
	}
	if roundtrip.AbandonedAt != payload.AbandonedAt {
		t.Errorf("AbandonedAt mismatch: got %s, want %s", roundtrip.AbandonedAt, payload.AbandonedAt)
	}
}

func TestOrderPaidPayloadRoundTrip(t *testing.T) {
	payload := &OrderPaidPayload{
		OrderID:             99999,
		UserID:              "user-789",
		CartID:              "cart-123",
		Status:              "PAID",
		SkuIDs:              []string{"sku-1", "sku-2", "sku-3"},
		CheckoutSubmittedAt: "1708616400000",
		PaidAt:              "1708616400555",
	}

	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal OrderPaidPayload: %v", err)
	}

	var roundtrip OrderPaidPayload
	if err := json.Unmarshal(data, &roundtrip); err != nil {
		t.Fatalf("unmarshal OrderPaidPayload: %v", err)
	}

	if roundtrip.OrderID != payload.OrderID {
		t.Errorf("OrderID mismatch: got %d, want %d", roundtrip.OrderID, payload.OrderID)
	}
	if roundtrip.UserID != payload.UserID {
		t.Errorf("UserID mismatch: got %s, want %s", roundtrip.UserID, payload.UserID)
	}
	if roundtrip.CartID != payload.CartID {
		t.Errorf("CartID mismatch: got %s, want %s", roundtrip.CartID, payload.CartID)
	}
	if roundtrip.Status != payload.Status {
		t.Errorf("Status mismatch: got %s, want %s", roundtrip.Status, payload.Status)
	}
	if len(roundtrip.SkuIDs) != len(payload.SkuIDs) {
		t.Errorf("SkuIDs length mismatch: got %d, want %d", len(roundtrip.SkuIDs), len(payload.SkuIDs))
	}
	for i, sku := range payload.SkuIDs {
		if roundtrip.SkuIDs[i] != sku {
			t.Errorf("SkuIDs[%d] mismatch: got %s, want %s", i, roundtrip.SkuIDs[i], sku)
		}
	}
	if roundtrip.CheckoutSubmittedAt != payload.CheckoutSubmittedAt {
		t.Errorf("CheckoutSubmittedAt mismatch: got %s, want %s", roundtrip.CheckoutSubmittedAt, payload.CheckoutSubmittedAt)
	}
	if roundtrip.PaidAt != payload.PaidAt {
		t.Errorf("PaidAt mismatch: got %s, want %s", roundtrip.PaidAt, payload.PaidAt)
	}
}

func TestCartEventEnvelopeRoundTrip(t *testing.T) {
	envelope := &CartEventEnvelope{
		EventID:            "evt-abc123",
		EventType:          EventTypeCartItemAdded,
		AggregateType:      AggregateTypeCart,
		AggregateID:        "cart-123",
		CorrelationID:      "corr-xyz",
		Timestamp:          time.Now().UTC().Format(time.RFC3339),
		Source:             "cart-service",
		StreamID:           "1708616400456-0",
		StreamTimestampMs:  "1708616400456",
		EventSequence:      "0",
		Payload: map[string]interface{}{
			"cart_id":  "cart-123",
			"sku_id":   "sku-456",
			"quantity": 3,
		},
	}

	data, err := json.Marshal(envelope)
	if err != nil {
		t.Fatalf("marshal CartEventEnvelope: %v", err)
	}

	var roundtrip CartEventEnvelope
	if err := json.Unmarshal(data, &roundtrip); err != nil {
		t.Fatalf("unmarshal CartEventEnvelope: %v", err)
	}

	if roundtrip.EventID != envelope.EventID {
		t.Errorf("EventID mismatch: got %s, want %s", roundtrip.EventID, envelope.EventID)
	}
	if roundtrip.EventType != envelope.EventType {
		t.Errorf("EventType mismatch: got %s, want %s", roundtrip.EventType, envelope.EventType)
	}
	if roundtrip.AggregateType != envelope.AggregateType {
		t.Errorf("AggregateType mismatch: got %s, want %s", roundtrip.AggregateType, envelope.AggregateType)
	}
	if roundtrip.AggregateID != envelope.AggregateID {
		t.Errorf("AggregateID mismatch: got %s, want %s", roundtrip.AggregateID, envelope.AggregateID)
	}
	if roundtrip.CorrelationID != envelope.CorrelationID {
		t.Errorf("CorrelationID mismatch: got %s, want %s", roundtrip.CorrelationID, envelope.CorrelationID)
	}
	if roundtrip.Source != envelope.Source {
		t.Errorf("Source mismatch: got %s, want %s", roundtrip.Source, envelope.Source)
	}
	if roundtrip.StreamID != envelope.StreamID {
		t.Errorf("StreamID mismatch: got %s, want %s", roundtrip.StreamID, envelope.StreamID)
	}
	if roundtrip.StreamTimestampMs != envelope.StreamTimestampMs {
		t.Errorf("StreamTimestampMs mismatch: got %s, want %s", roundtrip.StreamTimestampMs, envelope.StreamTimestampMs)
	}
	if roundtrip.EventSequence != envelope.EventSequence {
		t.Errorf("EventSequence mismatch: got %s, want %s", roundtrip.EventSequence, envelope.EventSequence)
	}
	if roundtrip.Payload["cart_id"] != envelope.Payload["cart_id"] {
		t.Errorf("Payload[cart_id] mismatch")
	}
}

func TestRedisStreamMessageFormat(t *testing.T) {
	t.Run("stream field names are correct", func(t *testing.T) {
		if StreamFieldNames.EventType != "event_type" {
			t.Errorf("expected event_type, got %s", StreamFieldNames.EventType)
		}
		if StreamFieldNames.Payload != "payload" {
			t.Errorf("expected payload, got %s", StreamFieldNames.Payload)
		}
	})
}

func TestParseStreamID(t *testing.T) {
	t.Run("parses standard stream ID", func(t *testing.T) {
		ts, seq := ParseStreamID("1708616400456-0")
		if ts != "1708616400456" {
			t.Errorf("expected timestamp 1708616400456, got %s", ts)
		}
		if seq != "0" {
			t.Errorf("expected sequence 0, got %s", seq)
		}
	})

	t.Run("parses stream ID with non-zero sequence", func(t *testing.T) {
		ts, seq := ParseStreamID("1708616400456-5")
		if ts != "1708616400456" {
			t.Errorf("expected timestamp 1708616400456, got %s", ts)
		}
		if seq != "5" {
			t.Errorf("expected sequence 5, got %s", seq)
		}
	})

	t.Run("handles large sequence number", func(t *testing.T) {
		ts, seq := ParseStreamID("1712345678901-9999999")
		if ts != "1712345678901" {
			t.Errorf("expected timestamp 1712345678901, got %s", ts)
		}
		if seq != "9999999" {
			t.Errorf("expected sequence 9999999, got %s", seq)
		}
	})

	t.Run("no separator returns full string as timestamp", func(t *testing.T) {
		ts, seq := ParseStreamID("1708616400456")
		if ts != "1708616400456" {
			t.Errorf("expected timestamp 1708616400456, got %s", ts)
		}
		if seq != "0" {
			t.Errorf("expected sequence 0 (default), got %s", seq)
		}
	})

	t.Run("empty string returns empty and 0", func(t *testing.T) {
		ts, seq := ParseStreamID("")
		if ts != "" {
			t.Errorf("expected empty timestamp, got %s", ts)
		}
		if seq != "0" {
			t.Errorf("expected sequence 0 (default), got %s", seq)
		}
	})
}

func TestNewCartEventEnvelope(t *testing.T) {
	payload := map[string]interface{}{
		"cart_id": "cart-123",
		"sku_id":  "sku-456",
	}

	envelope := NewCartEventEnvelope(
		EventTypeCartItemAdded,
		"cart-123",
		"corr-xyz",
		"1708616400456-0",
		"1708616400456",
		"0",
		payload,
	)

	if envelope.EventType != EventTypeCartItemAdded {
		t.Errorf("EventType mismatch: got %s, want %s", envelope.EventType, EventTypeCartItemAdded)
	}
	if envelope.AggregateID != "cart-123" {
		t.Errorf("AggregateID mismatch: got %s, want cart-123", envelope.AggregateID)
	}
	if envelope.CorrelationID != "corr-xyz" {
		t.Errorf("CorrelationID mismatch: got %s, want corr-xyz", envelope.CorrelationID)
	}
	if envelope.AggregateType != AggregateTypeCart {
		t.Errorf("AggregateType mismatch: got %s, want Cart", envelope.AggregateType)
	}
	if envelope.Source != "cart-service" {
		t.Errorf("Source mismatch: got %s, want cart-service", envelope.Source)
	}
	if envelope.StreamID != "1708616400456-0" {
		t.Errorf("StreamID mismatch: got %s, want 1708616400456-0", envelope.StreamID)
	}
	if envelope.StreamTimestampMs != "1708616400456" {
		t.Errorf("StreamTimestampMs mismatch: got %s, want 1708616400456", envelope.StreamTimestampMs)
	}
	if envelope.EventSequence != "0" {
		t.Errorf("EventSequence mismatch: got %s, want 0", envelope.EventSequence)
	}
	if envelope.EventID != "" {
		t.Errorf("EventID should be empty (generated by Relay Worker), got %s", envelope.EventID)
	}
	_, err := time.Parse(time.RFC3339, envelope.Timestamp)
	if err != nil {
		t.Errorf("Timestamp should be valid RFC3339: %v", err)
	}
}

func TestPayloadToJSON(t *testing.T) {
	t.Run("serializes CartItemPayload", func(t *testing.T) {
		payload := &CartItemPayload{
			CartID:         "cart-123",
			SkuID:          "sku-456",
			UserID:         "user-789",
			ProductID:      "prod-abc",
			Quantity:       3,
			UnitPriceAtAdd: 19.99,
			ProductName:    "Test Product",
			IsAvailable:    true,
		}

		jsonStr, err := PayloadToJSON(payload)
		if err != nil {
			t.Fatalf("PayloadToJSON failed: %v", err)
		}

		// Verify it's valid JSON and roundtrips
		var roundtrip CartItemPayload
		if err := json.Unmarshal([]byte(jsonStr), &roundtrip); err != nil {
			t.Fatalf("roundtrip failed: %v", err)
		}
		if roundtrip.CartID != payload.CartID {
			t.Errorf("CartID mismatch after roundtrip")
		}
	})

	t.Run("handles nil interface", func(t *testing.T) {
		jsonStr, err := PayloadToJSON(nil)
		if err != nil {
			t.Fatalf("PayloadToJSON(nil) should not error, got: %v", err)
		}
		if jsonStr != "null" {
			t.Errorf("expected 'null', got %s", jsonStr)
		}
	})
}

func TestBuilderFunctions(t *testing.T) {
	t.Run("BuildItemPayload from CartItemValue", func(t *testing.T) {
		item := &entity.CartItemValue{
			ProductID:      "prod-abc",
			Quantity:       5,
			UnitPriceAtAdd: 29.99,
			ProductName:    "Awesome Widget",
			VariantInfo:    "Color: Red",
			ImageURL:       "https://example.com/widget.jpg",
			IsAvailable:    true,
			AddedAt:        "1708616400456",
		}

		payload := BuildItemPayload("cart-123", "user-789", "sku-456", item)

		if payload.CartID != "cart-123" {
			t.Errorf("CartID mismatch: got %s", payload.CartID)
		}
		if payload.UserID != "user-789" {
			t.Errorf("UserID mismatch: got %s", payload.UserID)
		}
		if payload.SkuID != "sku-456" {
			t.Errorf("SkuID mismatch: got %s", payload.SkuID)
		}
		if payload.ProductID != "prod-abc" {
			t.Errorf("ProductID mismatch: got %s", payload.ProductID)
		}
		if payload.Quantity != 5 {
			t.Errorf("Quantity mismatch: got %d", payload.Quantity)
		}
		if payload.UnitPriceAtAdd != 29.99 {
			t.Errorf("UnitPriceAtAdd mismatch: got %f", payload.UnitPriceAtAdd)
		}
		if payload.ProductName != "Awesome Widget" {
			t.Errorf("ProductName mismatch: got %s", payload.ProductName)
		}
		if payload.VariantInfo != "Color: Red" {
			t.Errorf("VariantInfo mismatch: got %s", payload.VariantInfo)
		}
		if payload.ImageURL != "https://example.com/widget.jpg" {
			t.Errorf("ImageURL mismatch: got %s", payload.ImageURL)
		}
		if !payload.IsAvailable {
			t.Error("IsAvailable should be true")
		}
		if payload.AddedAt != "1708616400456" {
			t.Errorf("AddedAt mismatch: got %s", payload.AddedAt)
		}
		if payload.UpdatedAt == "" {
			t.Error("UpdatedAt should be set to current timestamp")
		}
	})
}

func TestParsePayloads(t *testing.T) {
	t.Run("ParseItemPayload from valid JSON", func(t *testing.T) {
		jsonStr := `{"cart_id":"cart-123","sku_id":"sku-456","user_id":"user-789","product_id":"prod-abc","quantity":3,"unit_price_at_add":19.99,"product_name":"Test","is_available":true,"added_at":"1708616400456","updated_at":"1708616400500"}`
		payload, err := ParseItemPayload(jsonStr)
		if err != nil {
			t.Fatalf("ParseItemPayload failed: %v", err)
		}
		if payload.CartID != "cart-123" {
			t.Errorf("CartID mismatch: got %s", payload.CartID)
		}
		if payload.Quantity != 3 {
			t.Errorf("Quantity mismatch: got %d", payload.Quantity)
		}
	})

	t.Run("ParseItemPayload from invalid JSON", func(t *testing.T) {
		_, err := ParseItemPayload("{invalid json}")
		if err == nil {
			t.Error("expected error for invalid JSON")
		}
	})

	t.Run("ParseDeletedPayload from valid JSON", func(t *testing.T) {
		jsonStr := `{"cart_id":"cart-123","sku_id":"sku-456","user_id":"user-789","deleted_at":"1708616400555"}`
		payload, err := ParseDeletedPayload(jsonStr)
		if err != nil {
			t.Fatalf("ParseDeletedPayload failed: %v", err)
		}
		if payload.SkuID != "sku-456" {
			t.Errorf("SkuID mismatch: got %s", payload.SkuID)
		}
		if payload.DeletedAt != "1708616400555" {
			t.Errorf("DeletedAt mismatch: got %s", payload.DeletedAt)
		}
	})

	t.Run("ParseDeletedPayload from invalid JSON", func(t *testing.T) {
		_, err := ParseDeletedPayload("not json")
		if err == nil {
			t.Error("expected error for invalid JSON")
		}
	})

	t.Run("ParseOrderPaidPayload from valid JSON", func(t *testing.T) {
		jsonStr := `{"order_id":99999,"user_id":"user-789","cart_id":"cart-123","status":"PAID","sku_ids":["sku-1","sku-2"],"checkout_submitted_at":"1708616400000","paid_at":"1708616400555"}`
		payload, err := ParseOrderPaidPayload(jsonStr)
		if err != nil {
			t.Fatalf("ParseOrderPaidPayload failed: %v", err)
		}
		if payload.OrderID != 99999 {
			t.Errorf("OrderID mismatch: got %d", payload.OrderID)
		}
		if len(payload.SkuIDs) != 2 {
			t.Errorf("SkuIDs length mismatch: got %d", len(payload.SkuIDs))
		}
	})

	t.Run("ParseOrderPaidPayload from invalid JSON", func(t *testing.T) {
		_, err := ParseOrderPaidPayload("broken")
		if err == nil {
			t.Error("expected error for invalid JSON")
		}
	})
}
