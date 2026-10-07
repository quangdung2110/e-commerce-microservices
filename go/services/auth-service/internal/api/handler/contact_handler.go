package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/dto/response"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// ContactHandler serves the internal notification-contact API.
//
// The response carries the recipient address, so it is served only on the
// internal listener, only to a verified service identity, and always with
// Cache-Control: no-store. The address is never logged: the access log records
// the route and the status only, and a 404 problem carries no identity.
type ContactHandler struct {
	contacts *service.ContactService
}

// NewContactHandler wires the internal contact endpoint.
func NewContactHandler(contacts *service.ContactService) *ContactHandler {
	return &ContactHandler{contacts: contacts}
}

// Resolve handles
// GET /internal/v1/auth/users/{user_id}/notification-contact.
func (h *ContactHandler) Resolve(c *gin.Context) {
	userID, err := httpx.PathUUID(c, "user_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	contact, err := h.contacts.Resolve(c.Request.Context(), userID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewNotificationContact(contact))
}
