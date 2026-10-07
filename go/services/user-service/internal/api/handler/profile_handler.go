// Package handler adapts HTTP requests to the use cases. A handler resolves the
// caller, decodes the body and renders the result; it holds no business rule.
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

// ProfileHandler serves the profile endpoints.
type ProfileHandler struct {
	profiles *service.ProfileService
}

// NewProfileHandler wires the profile endpoints.
func NewProfileHandler(profiles *service.ProfileService) *ProfileHandler {
	return &ProfileHandler{profiles: profiles}
}

// Get handles GET /api/v1/users/me/profile.
func (h *ProfileHandler) Get(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	profile, err := h.profiles.Get(c.Request.Context(), identity.UserID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewProfile(profile))
}

// Patch handles PATCH /api/v1/users/me/profile with JSON Merge Patch semantics:
// an absent member keeps its value, an explicit null clears it.
func (h *ProfileHandler) Patch(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	var payload request.ProfilePatch
	if err := httpx.DecodeMergePatch(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	changes, err := payload.ToChanges()
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	profile, err := h.profiles.Update(c.Request.Context(), identity.UserID, changes)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewProfile(profile))
}
