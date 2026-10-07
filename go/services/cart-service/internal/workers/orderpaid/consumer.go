// Package orderpaid clears purchased SKUs out of a cart (WF-CART-004).
package orderpaid

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/IBM/sarama"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"cart-service/internal/domain"
	"cart-service/internal/infrastructure/observability"
	"cart-service/internal/service"
)

// EventTypeOrderPaid is the only event type this consumer acts on.
const EventTypeOrderPaid = "com.ecommerce.order.paid.v1"

// envelope is the outer message Order Service publishes to t.order.events.
type envelope struct {
	SpecVersion string  `json:"specversion"`
	ID          string  `json:"id"`
	Type        string  `json:"type"`
	Source      string  `json:"source"`
	Subject     string  `json:"subject"`
	Time        string  `json:"time"`
	ContentType string  `json:"datacontenttype"`
	DataSchema  string  `json:"dataschema"`
	Sequence    int64   `json:"sequence"`
	Data        payload `json:"data"`
}

// payload is the subset of ORDER_PAID that Cart needs.
//
// Cart deliberately does not read a cart_id from the event: it derives the
// cart from user_id itself, so the routing rule lives in one place.
type payload struct {
	OrderID string   `json:"order_id"`
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

	if event.Type != EventTypeOrderPaid {
		// t.order.events carries the PAID and COMPLETED integration facts. Cart
		// only acts on the paid milestone.
		h.metrics.OrderPaidSkipped("other_event_type")
		return nil
	}

	_, timeErr := time.Parse(time.RFC3339, event.Time)
	if event.SpecVersion != "1.0" || uuid.Validate(event.ID) != nil ||
		event.Source != "/services/order" || timeErr != nil ||
		event.ContentType != "application/json" ||
		event.DataSchema != "/contracts/events/order/v1/schemas/order-paid.schema.json" ||
		event.Sequence < 1 ||
		!isCanonicalOrderID(event.Data.OrderID) || event.Subject != "orders/"+event.Data.OrderID ||
		uuid.Validate(event.Data.UserID) != nil || len(event.Data.SkuIDs) == 0 || len(event.Data.SkuIDs) > 200 {
		h.metrics.OrderPaidSkipped("incomplete_payload")
		h.logger.Warn("skipping ORDER_PAID with an incomplete payload",
			zap.String("event_id", event.ID),
			zap.String("order_id", event.Data.OrderID),
			zap.Int64("offset", message.Offset),
		)
		return nil
	}

	identity, err := domain.NewIdentity(event.Data.UserID)
	if err != nil {
		h.metrics.OrderPaidSkipped("invalid_user_id")
		h.logger.Warn("skipping ORDER_PAID with an unusable user_id",
			zap.String("event_id", event.ID),
			zap.String("order_id", event.Data.OrderID),
		)
		return nil
	}

	skuIDs := dedupe(event.Data.SkuIDs)
	result, err := h.service.RemovePurchased(ctx, identity, skuIDs)
	if err != nil {
		h.metrics.OrderPaidError()
		return fmt.Errorf("clear purchased skus for order %s: %w", event.Data.OrderID, err)
	}

	h.metrics.OrderPaidApplied(result.DeletedCount)
	h.logger.Info("cleared purchased skus from cart",
		zap.String("event_id", event.ID),
		zap.String("order_id", event.Data.OrderID),
		zap.String("cart_id", identity.CartID),
		zap.Int("requested", len(skuIDs)),
		zap.Int("deleted", result.DeletedCount),
	)
	return nil
}

func isCanonicalOrderID(value string) bool {
	if value == "" || len(value) > 19 || value[0] == '0' {
		return false
	}
	for _, char := range value {
		if char < '0' || char > '9' {
			return false
		}
	}
	return true
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
