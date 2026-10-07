package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/user-service/internal/api/dto/request"
	"github.com/ecommerce/user-service/internal/api/dto/response"
	"github.com/ecommerce/user-service/internal/api/httpx"
	"github.com/ecommerce/user-service/internal/api/middleware"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/service"
)

// PreferencesHandler serves the preference endpoints.
type PreferencesHandler struct {
	preferences *service.PreferencesService
}

// NewPreferencesHandler wires the preference endpoints.
func NewPreferencesHandler(preferences *service.PreferencesService) *PreferencesHandler {
	return &PreferencesHandler{preferences: preferences}
}

// Get handles GET /api/v1/users/me/preferences.
func (h *PreferencesHandler) Get(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	preferences, err := h.preferences.Get(c.Request.Context(), identity.UserID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewPreferences(preferences))
}

// Replace handles PUT /api/v1/users/me/preferences. Only the three notification
// flags are writable; a missing profile is a 404 rather than a silent bootstrap
// of an account that may have been deleted (WF-USER-005).
func (h *PreferencesHandler) Replace(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	var payload request.NotificationPreferencesUpdate
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	changes, err := payload.ToChanges()
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	preferences, err := h.preferences.Replace(c.Request.Context(), identity.UserID, changes)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewPreferences(preferences))
}
