// Package userdeleted purges the cart of a deleted account (WF-CART-008).
package userdeleted

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

// EventTypeUserDeleted is the only event type this consumer acts on.
const EventTypeUserDeleted = "com.ecommerce.auth.user-deleted.v1"

// authSource is the CloudEvents source Auth stamps on every event.
const authSource = "/services/auth"

// envelope is the outer message Auth publishes to t.auth.events.
type envelope struct {
	SpecVersion string  `json:"specversion"`
	ID          string  `json:"id"`
	Type        string  `json:"type"`
	Source      string  `json:"source"`
	Data        payload `json:"data"`
}

// payload is the subset of USER_DELETED that Cart needs. Cart derives the cart
// from user_id itself, so the routing rule lives in one place.
type payload struct {
	UserID    string `json:"user_id"`
	DeletedAt string `json:"deleted_at"`
}

// Handler applies USER_DELETED events to carts.
type Handler struct {
	service *service.CartService
	metrics *observability.Metrics
	logger  *zap.Logger
}

// NewHandler builds the USER_DELETED handler.
func NewHandler(cartService *service.CartService, metrics *observability.Metrics, logger *zap.Logger) *Handler {
	return &Handler{service: cartService, metrics: metrics, logger: logger}
}

// Handle processes one Kafka message.
//
// t.auth.events also carries USER_VERIFIED_SUCCESS, which Cart ignores. The
// purge is idempotent (DEL and ZREM on keys that may already be gone), so a
// redelivery is harmless and needs no Redis record. Returning an error leaves
// the offset uncommitted, so a Redis outage replays rather than silently skips
// a privacy deletion.
func (h *Handler) Handle(ctx context.Context, message *sarama.ConsumerMessage) error {
	var event envelope
	if err := json.Unmarshal(message.Value, &event); err != nil {
		// A message Cart cannot parse will never parse. Committing past it is
		// the only way forward; the log carries the offset for investigation.
		h.metrics.UserDeletedSkipped("malformed")
		h.logger.Warn("skipping unparseable auth event",
			zap.Int32("partition", message.Partition),
			zap.Int64("offset", message.Offset),
			zap.Error(err),
		)
		return nil
	}

	if event.Type != EventTypeUserDeleted {
		h.metrics.UserDeletedSkipped("other_event_type")
		return nil
	}

	_, timeErr := time.Parse(time.RFC3339, event.Data.DeletedAt)
	if event.SpecVersion != "1.0" || event.Source != authSource || event.ID == "" ||
		timeErr != nil || uuid.Validate(event.Data.UserID) != nil {
		h.metrics.UserDeletedSkipped("incomplete_payload")
		h.logger.Warn("skipping USER_DELETED with an incomplete payload",
			zap.String("event_id", event.ID),
			zap.Int32("partition", message.Partition),
			zap.Int64("offset", message.Offset),
		)
		return nil
	}

	identity, err := domain.NewIdentity(event.Data.UserID)
	if err != nil {
		h.metrics.UserDeletedSkipped("invalid_user_id")
		h.logger.Warn("skipping USER_DELETED with an unusable user_id", zap.String("event_id", event.ID))
		return nil
	}

	result, err := h.service.PurgeUser(ctx, identity)
	if err != nil {
		h.metrics.UserDeletedError()
		return fmt.Errorf("purge cart for deleted account (event %s): %w", event.ID, err)
	}

	h.metrics.UserDeletedApplied(result.Purged)
	h.logger.Info("handled USER_DELETED",
		zap.String("event_id", event.ID),
		zap.String("cart_id", identity.CartID),
		zap.Bool("purged", result.Purged),
	)
	return nil
}
