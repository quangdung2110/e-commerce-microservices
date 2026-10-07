package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/dto/response"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// DeliveryHandler serves the internal notification-delivery API.
//
// The response may contain PII and a secret, so it is served only on the
// internal listener, only to a verified service identity, and always with
// Cache-Control: no-store. Resolving is idempotent inside the TTL and never
// consumes the OTP or reset token it returns.
type DeliveryHandler struct {
	deliveries *service.DeliveryService
}

// NewDeliveryHandler wires the internal delivery endpoint.
func NewDeliveryHandler(deliveries *service.DeliveryService) *DeliveryHandler {
	return &DeliveryHandler{deliveries: deliveries}
}

// Resolve handles
// POST /internal/v1/auth/notification-deliveries/{delivery_id}/content.
func (h *DeliveryHandler) Resolve(c *gin.Context) {
	deliveryID, err := httpx.PathUUID(c, "delivery_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	delivery, err := h.deliveries.Resolve(c.Request.Context(), deliveryID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewNotificationDeliveryContent(delivery))
}
