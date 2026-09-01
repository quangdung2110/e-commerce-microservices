// Package orderpaid clears purchased SKUs out of a cart (WF-CART-004).
package orderpaid

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/infrastructure/observability"
	"cart-service/internal/service"
)

// EventTypeOrderPaid is the only event type this consumer acts on.
const EventTypeOrderPaid = "ORDER_PAID"

// envelope is the outer message Order Service publishes to t.order.events.
type envelope struct {
	EventID   string  `json:"event_id"`
	EventType string  `json:"event_type"`
	Payload   payload `json:"payload"`
}

// payload is the subset of ORDER_PAID that Cart needs.
//
// Cart deliberately does not read a cart_id from the event: it derives the
// cart from user_id itself, so the routing rule lives in one place.
type payload struct {
	OrderID int64    `json:"order_id"`
	UserID  string   `json:"user_id"`
	SkuIDs  []string `json:"sku_ids"`
}

// Handler applies ORDER_PAID events to carts.
type Handler struct {
	service *service.CartService
	metrics *observability.Metrics
	logger  *zap.Logger
}

// NewHandler builds the ORDER_PAID handler.
func NewHandler(cartService *service.CartService, metrics *observability.Metrics, logger *zap.Logger) *Handler {
	return &Handler{service: cartService, metrics: metrics, logger: logger}
}

// Handle processes one Kafka message.
//
// Redelivery is safe without an idempotency record: HDEL per SKU is a no-op
// the second time, the path emits no Cart event, and it does not extend the
// cart's activity. Returning an error leaves the offset uncommitted, so a
// Redis outage replays rather than silently skips the cleanup.
func (h *Handler) Handle(ctx context.Context, message *sarama.ConsumerMessage) error {
	var event envelope
	if err := json.Unmarshal(message.Value, &event); err != nil {
		// A message Cart cannot parse will never parse. Committing past it is
		// the only way forward; the log carries the offset for investigation.
		h.metrics.OrderPaidSkipped("malformed")
		h.logger.Warn("skipping unparseable order event",
			zap.Int32("partition", message.Partition),
			zap.Int64("offset", message.Offset),
			zap.Error(err),
		)
		return nil
	}

	if event.EventType != EventTypeOrderPaid {
		// t.order.events carries the whole order lifecycle; Cart only acts on
		// the paid milestone.
		h.metrics.OrderPaidSkipped("other_event_type")
		return nil
	}

	if event.Payload.UserID == "" || len(event.Payload.SkuIDs) == 0 {
		h.metrics.OrderPaidSkipped("incomplete_payload")
		h.logger.Warn("skipping ORDER_PAID with an incomplete payload",
			zap.String("event_id", event.EventID),
			zap.Int64("order_id", event.Payload.OrderID),
			zap.Int64("offset", message.Offset),
		)
		return nil
	}

	identity, err := domain.NewIdentity(event.Payload.UserID)
	if err != nil {
		h.metrics.OrderPaidSkipped("invalid_user_id")
		h.logger.Warn("skipping ORDER_PAID with an unusable user_id",
			zap.String("event_id", event.EventID),
			zap.Int64("order_id", event.Payload.OrderID),
		)
		return nil
	}

	skuIDs := dedupe(event.Payload.SkuIDs)
	result, err := h.service.RemovePurchased(ctx, identity, skuIDs)
	if err != nil {
		h.metrics.OrderPaidError()
		return fmt.Errorf("clear purchased skus for order %d: %w", event.Payload.OrderID, err)
	}

	h.metrics.OrderPaidApplied(result.DeletedCount)
	h.logger.Info("cleared purchased skus from cart",
		zap.String("event_id", event.EventID),
		zap.Int64("order_id", event.Payload.OrderID),
		zap.String("cart_id", identity.CartID),
		zap.Int("requested", len(skuIDs)),
		zap.Int("deleted", result.DeletedCount),
	)
	return nil
}

// dedupe removes repeated SKU ids so one HDEL is issued per SKU.
func dedupe(skuIDs []string) []string {
	seen := make(map[string]struct{}, len(skuIDs))
	unique := make([]string, 0, len(skuIDs))
	for _, skuID := range skuIDs {
		if skuID == "" {
			continue
		}
		if _, ok := seen[skuID]; ok {
			continue
		}
		seen[skuID] = struct{}{}
		unique = append(unique, skuID)
	}
	return unique
}
