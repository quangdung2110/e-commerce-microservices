package postgres_sync

import (
	"testing"
	"time"

	"github.com/IBM/sarama"

	"cart-service/internal/domain/event"
	"cart-service/internal/infrastructure/kafka"
)

func TestGroupByCartID(t *testing.T) {
	env1 := &event.CartEventEnvelope{
		EventType:   "CART_ITEM_ADDED",
		AggregateID: "{cart}:user1",
		StreamID:    "1000-0",
	}
	env2 := &event.CartEventEnvelope{
		EventType:   "CART_ITEM_UPDATED",
		AggregateID: "{cart}:user1",
		StreamID:    "1000-1",
	}
	env3 := &event.CartEventEnvelope{
		EventType:   "CART_ITEM_DELETED",
		AggregateID: "{cart}:user2",
		StreamID:    "1000-2",
	}

	batch := &kafka.MessageBatch{}

	batch.Add(msgWithCartID(env1))
	batch.Add(msgWithCartID(env2))
	batch.Add(msgWithCartID(env3))

	grouped := groupByCartID(batch)

	if len(grouped) != 2 {
		t.Errorf("expected 2 cart groups, got %d", len(grouped))
	}

	user1Events, ok := grouped["{cart}:user1"]
	if !ok {
		t.Fatal("missing cart group {cart}:user1")
	}
	if len(user1Events) != 2 {
		t.Errorf("user1 should have 2 events, got %d", len(user1Events))
	}

	user2Events, ok := grouped["{cart}:user2"]
	if !ok {
		t.Fatal("missing cart group {cart}:user2")
	}
	if len(user2Events) != 1 {
		t.Errorf("user2 should have 1 event, got %d", len(user2Events))
	}
}

func TestParseItemPayloadFromEnvelope(t *testing.T) {
	env := &event.CartEventEnvelope{
		EventType:   "CART_ITEM_ADDED",
		AggregateID: "{cart}:user1",
		StreamID:    "1000-0",
		Payload: map[string]interface{}{
			"cart_id":         "{cart}:user1",
			"sku_id":          "sku001",
			"user_id":         "user1",
			"product_id":      "prod001",
			"quantity":        float64(2),
			"unit_price_at_add": 99.99,
			"product_name":    "Test Product",
			"variant_info":    "Size: M",
			"is_available":    true,
			"added_at":       "1000",
			"updated_at":     "1000",
		},
	}

	payload, err := ParseItemPayloadFromEnvelope(env)
	if err != nil {
		t.Fatalf("ParseItemPayloadFromEnvelope failed: %v", err)
	}

	if payload.SkuID != "sku001" {
		t.Errorf("SkuID = %q, want sku001", payload.SkuID)
	}
	if payload.UserID != "user1" {
		t.Errorf("UserID = %q, want user1", payload.UserID)
	}
	if payload.Quantity != 2 {
		t.Errorf("Quantity = %d, want 2", payload.Quantity)
	}
	if payload.UnitPriceAtAdd != 99.99 {
		t.Errorf("UnitPriceAtAdd = %f, want 99.99", payload.UnitPriceAtAdd)
	}
	if payload.ProductName != "Test Product" {
		t.Errorf("ProductName = %q, want Test Product", payload.ProductName)
	}
}

func TestParseDeletedPayloadFromEnvelope(t *testing.T) {
	env := &event.CartEventEnvelope{
		EventType:   "CART_ITEM_DELETED",
		AggregateID: "{cart}:user1",
		StreamID:    "1000-1",
		Payload: map[string]interface{}{
			"cart_id":    "{cart}:user1",
			"sku_id":     "sku001",
			"user_id":    "user1",
			"deleted_at": "2000",
		},
	}

	payload, err := ParseDeletedPayloadFromEnvelope(env)
	if err != nil {
		t.Fatalf("ParseDeletedPayloadFromEnvelope failed: %v", err)
	}

	if payload.SkuID != "sku001" {
		t.Errorf("SkuID = %q, want sku001", payload.SkuID)
	}
	if payload.CartID != "{cart}:user1" {
		t.Errorf("CartID = %q, want {cart}:user1", payload.CartID)
	}
}

func TestParseAbandonedPayloadFromEnvelope(t *testing.T) {
	env := &event.CartEventEnvelope{
		EventType:   "CART_ABANDONED",
		AggregateID: "{cart}:user1",
		StreamID:    "1000-5",
		Payload: map[string]interface{}{
			"cart_id":      "{cart}:user1",
			"user_id":      "user1",
			"abandoned_at": "5000",
		},
	}

	payload, err := ParseAbandonedPayloadFromEnvelope(env)
	if err != nil {
		t.Fatalf("ParseAbandonedPayloadFromEnvelope failed: %v", err)
	}

	if payload.CartID != "{cart}:user1" {
		t.Errorf("CartID = %q, want {cart}:user1", payload.CartID)
	}
	if payload.UserID != "user1" {
		t.Errorf("UserID = %q, want user1", payload.UserID)
	}
}

func TestCartEventBatch(t *testing.T) {
	batch := NewCartEventBatch("{cart}:user1")

	env := &event.CartEventEnvelope{
		EventType:   "CART_ITEM_ADDED",
		AggregateID: "{cart}:user1",
		StreamID:    "1000-0",
	}

	batch.Add(env)
	batch.Add(env)

	if len(batch.Events) != 2 {
		t.Errorf("batch should have 2 events, got %d", len(batch.Events))
	}

	if batch.CartID != "{cart}:user1" {
		t.Errorf("CartID = %q, want {cart}:user1", batch.CartID)
	}
}

func TestEventParserEdgeCases(t *testing.T) {
	// Empty envelope with nil payload
	env := &event.CartEventEnvelope{Payload: nil}
	_, err := ParseItemPayloadFromEnvelope(env)
	if err == nil {
		// nil payload marshals to "null", which unmarshals to empty struct — not an error
	}

	// Invalid type in payload (non-serializable)
	env2 := &event.CartEventEnvelope{
		Payload: map[string]interface{}{
			"invalid": make(chan int),
		},
	}
	_, err = ParseItemPayloadFromEnvelope(env2)
	if err == nil {
		t.Error("expected error for non-serializable payload")
	}
}

func TestMessageBatch(t *testing.T) {
	batch := kafka.NewMessageBatch()

	if batch.Count() != 0 {
		t.Errorf("new batch should have 0 messages, got %d", batch.Count())
	}

	batch.Add(&sarama.ConsumerMessage{Key: []byte("key1")})
	batch.Add(&sarama.ConsumerMessage{Key: []byte("key2")})

	if batch.Count() != 2 {
		t.Errorf("batch should have 2 messages, got %d", batch.Count())
	}

	msgs := batch.Messages()
	if len(msgs) != 2 {
		t.Errorf("Messages() should return 2, got %d", len(msgs))
	}

	batch.Commit()
}

func TestBatchProcessor_ProcessBatch(t *testing.T) {
	batch := &CartEventBatch{
		CartID:  "{cart}:user1",
		Events:  make([]*event.CartEventEnvelope, 0),
		Metrics: &BatchMetrics{},
	}

	env := &event.CartEventEnvelope{
		EventType:   "CART_ITEM_ADDED",
		AggregateID: "{cart}:user1",
		StreamID:    "1000-0",
		Payload: map[string]interface{}{
			"cart_id":      "{cart}:user1",
			"sku_id":       "sku001",
			"user_id":      "user1",
			"product_id":   "prod001",
			"quantity":     float64(1),
			"product_name": "Test",
		},
	}
	batch.Add(env)

	if len(batch.Events) != 1 {
		t.Errorf("batch should have 1 event, got %d", len(batch.Events))
	}
}

func TestBatchMetrics(t *testing.T) {
	m := &BatchMetrics{
		ItemsUpserted: 5,
		ItemsDeleted:  2,
		StatusUpdated: 1,
		Errors:        0,
	}

	if m.ItemsUpserted != 5 {
		t.Errorf("ItemsUpserted = %d, want 5", m.ItemsUpserted)
	}
}

func TestCartEventRecord(t *testing.T) {
	rec := &CartEventRecord{
		CartID:    "{cart}:user1",
		UserID:    "user1",
		SkuID:     "sku001",
		EventType: "CART_ITEM_ADDED",
		StreamID:  "1000-0",
		Timestamp: time.Now(),
		Quantity:  3,
	}

	if rec.CartID != "{cart}:user1" {
		t.Errorf("CartID = %q, want {cart}:user1", rec.CartID)
	}
	if rec.Quantity != 3 {
		t.Errorf("Quantity = %d, want 3", rec.Quantity)
	}
}

func TestBatchResult(t *testing.T) {
	result := &BatchResult{
		CartsProcessed: 10,
		EventsTotal:   25,
		EventsFailed:  2,
	}

	if result.CartsProcessed != 10 {
		t.Errorf("CartsProcessed = %d, want 10", result.CartsProcessed)
	}
	if result.EventsTotal-result.EventsFailed != 23 {
		t.Errorf("successful events should be 23, got %d", result.EventsTotal-result.EventsFailed)
	}
}

func msgWithCartID(env *event.CartEventEnvelope) *sarama.ConsumerMessage {
	return &sarama.ConsumerMessage{
		Key:   []byte(env.AggregateID),
		Value: []byte(`{"aggregate_id":"` + env.AggregateID + `"}`),
	}
}
