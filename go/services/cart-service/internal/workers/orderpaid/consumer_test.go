package orderpaid

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/observability"
	"cart-service/internal/service"
)

type recordingStore struct {
	calls []port.RemoveCommand
	err   error
}

func (s *recordingStore) LoadItems(context.Context, domain.Identity) ([]domain.Item, error) {
	return nil, nil
}

func (s *recordingStore) Upsert(context.Context, port.UpsertCommand) (port.UpsertResult, error) {
	return port.UpsertResult{}, nil
}

func (s *recordingStore) Remove(_ context.Context, cmd port.RemoveCommand) (port.RemoveResult, error) {
	s.calls = append(s.calls, cmd)
	if s.err != nil {
		return port.RemoveResult{}, s.err
	}
	return port.RemoveResult{DeletedCount: len(cmd.SkuIDs)}, nil
}

func (s *recordingStore) MarkAbandoned(context.Context, port.MarkAbandonedCommand) (port.MarkAbandonedResult, error) {
	return port.MarkAbandonedResult{}, nil
}

func (s *recordingStore) DueCarts(context.Context, int, time.Time, int) ([]port.DueCart, error) {
	return nil, nil
}

func (s *recordingStore) DropIndexMember(context.Context, int, string) error { return nil }
func (s *recordingStore) Ping(context.Context) error                         { return nil }

type nopCatalog struct{}

func (nopCatalog) GetSku(context.Context, string) (domain.ProductSnapshot, error) {
	return domain.ProductSnapshot{}, nil
}

func (nopCatalog) GetBatchSkus(context.Context, []string) (map[string]domain.ProductSnapshot, error) {
	return nil, nil
}

func (nopCatalog) Ready(context.Context) error { return nil }

func newTestHandler(store *recordingStore) *Handler {
	cartService := service.New(store, nopCatalog{}, service.Config{ProductValidationTTL: time.Minute})
	return NewHandler(cartService, observability.NewMetrics(), zap.NewNop())
}

func message(body string) *sarama.ConsumerMessage {
	return &sarama.ConsumerMessage{Topic: "t.order.events", Value: []byte(body)}
}

// The envelope is the one Order Service documents for t.order.events.
const orderPaidEvent = `{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "ORDER_PAID",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "PAID",
    "sku_ids": ["sku-001", "sku-002"],
    "final_amount": 995000,
    "paid_at": "2026-02-28T10:05:00Z"
  }
}`

func TestHandlerClearsPurchasedSkus(t *testing.T) {
	store := &recordingStore{}

	if err := newTestHandler(store).Handle(context.Background(), message(orderPaidEvent)); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	if len(store.calls) != 1 {
		t.Fatalf("store was called %d times, want 1", len(store.calls))
	}
	cmd := store.calls[0]
	if len(cmd.SkuIDs) != 2 {
		t.Fatalf("sku ids = %v", cmd.SkuIDs)
	}
	if cmd.TouchActivity {
		t.Fatal("order cleanup must not count as user activity")
	}
	if cmd.IdempotencyKey != "" {
		t.Fatalf("idempotency key = %q, want empty", cmd.IdempotencyKey)
	}

	// Cart derives the cart from user_id; it never reads a cart_id from the
	// event, so the routing rule lives in exactly one place.
	expected, err := domain.NewIdentity("550e8400-e29b-41d4-a716-446655440000")
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}
	if cmd.Identity.CartID != expected.CartID || cmd.Identity.Bucket != expected.Bucket {
		t.Fatalf("identity = %+v, want %+v", cmd.Identity, expected)
	}
}

// t.order.events carries the whole order lifecycle. Cart only acts on the
// paid milestone and must leave the rest alone.
func TestHandlerIgnoresOtherOrderEvents(t *testing.T) {
	store := &recordingStore{}
	body := `{"event_type":"ORDER_CANCELLED","payload":{"order_id":1,"user_id":"u","sku_ids":["s"]}}`

	if err := newTestHandler(store).Handle(context.Background(), message(body)); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if len(store.calls) != 0 {
		t.Fatal("cart was modified by a non-ORDER_PAID event")
	}
}

// A message Cart cannot parse will never parse. Committing past it is the only
// way forward; blocking the partition forever is not.
func TestHandlerSkipsUnparseableMessages(t *testing.T) {
	store := &recordingStore{}

	if err := newTestHandler(store).Handle(context.Background(), message("{not json")); err != nil {
		t.Fatalf("an unparseable message must not block the partition: %v", err)
	}
	if len(store.calls) != 0 {
		t.Fatal("cart was modified by an unparseable message")
	}
}

func TestHandlerSkipsIncompletePayloads(t *testing.T) {
	store := &recordingStore{}
	cases := []string{
		`{"event_type":"ORDER_PAID","payload":{"order_id":1,"user_id":"","sku_ids":["s"]}}`,
		`{"event_type":"ORDER_PAID","payload":{"order_id":1,"user_id":"u","sku_ids":[]}}`,
	}

	for _, body := range cases {
		if err := newTestHandler(store).Handle(context.Background(), message(body)); err != nil {
			t.Fatalf("Handle: %v", err)
		}
	}
	if len(store.calls) != 0 {
		t.Fatalf("cart was modified by an incomplete payload: %+v", store.calls)
	}
}

// A Redis outage must leave the offset uncommitted, so the cleanup replays
// rather than being silently skipped.
func TestHandlerReturnsAnErrorWhenTheStoreFails(t *testing.T) {
	store := &recordingStore{err: errors.New("redis is unavailable")}

	err := newTestHandler(store).Handle(context.Background(), message(orderPaidEvent))
	if err == nil {
		t.Fatal("a store failure must surface so the offset is not committed")
	}
}

func TestHandlerDeduplicatesSkuIDs(t *testing.T) {
	store := &recordingStore{}
	body := `{"event_type":"ORDER_PAID","payload":{"order_id":1,"user_id":"u1","sku_ids":["a","a","","b"]}}`

	if err := newTestHandler(store).Handle(context.Background(), message(body)); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	got := store.calls[0].SkuIDs
	if len(got) != 2 || got[0] != "a" || got[1] != "b" {
		t.Fatalf("sku ids = %v, want [a b]", got)
	}
}
