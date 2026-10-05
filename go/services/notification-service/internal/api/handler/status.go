package handler

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/api/dto/response"
	"github.com/ecommerce/notification-service/internal/api/httpx"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/service/status"
)

// StatusService is what the status handler needs from the domain.
type StatusService interface {
	GetRequest(ctx context.Context, id uuid.UUID) (*status.Timeline, error)
	ListBySubject(ctx context.Context, subject, template string) ([]entity.Delivery, error)
}

// StatusHandler serves the support endpoints of the internal API.
type StatusHandler struct {
	svc StatusService
}

// NewStatusHandler wires the handler.
func NewStatusHandler(svc StatusService) *StatusHandler { return &StatusHandler{svc: svc} }

// GetRequest handles GET /internal/v1/notifications/requests/{request_id}.
func (h *StatusHandler) GetRequest(c *gin.Context) {
	id, err := httpx.PathID(c, "request_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	timeline, err := h.svc.GetRequest(c.Request.Context(), id)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.FromRequest(timeline.Request, timeline.Deliveries))
}

// ListDeliveries handles GET /internal/v1/notifications/deliveries?subject&template.
func (h *StatusHandler) ListDeliveries(c *gin.Context) {
	deliveries, err := h.svc.ListBySubject(c.Request.Context(), c.Query("subject"), c.Query("template"))
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}
	httpx.WriteJSON(c, http.StatusOK, response.DeliveryList{Deliveries: response.FromDeliveries(deliveries)})
}
