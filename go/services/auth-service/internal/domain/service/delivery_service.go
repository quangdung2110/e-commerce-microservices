package service

import (
	"context"

	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// DeliveryService serves the internal notification-delivery API.
type DeliveryService struct {
	deliveries port.DeliveryStore
}

// NewDeliveryService wires the internal delivery lookup.
func NewDeliveryService(deliveries port.DeliveryStore) *DeliveryService {
	return &DeliveryService{deliveries: deliveries}
}

// Resolve returns the content Notification Service must render. The lookup is
// idempotent inside the TTL and never consumes the OTP or the reset token, so a
// retry of the same delivery_id returns the same content.
func (s *DeliveryService) Resolve(ctx context.Context, deliveryID uuid.UUID) (*entity.NotificationDelivery, error) {
	return s.deliveries.Get(ctx, deliveryID)
}
