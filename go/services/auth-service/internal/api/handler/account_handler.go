package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/dto/request"
	"github.com/ecommerce/auth-service/internal/api/dto/response"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/api/middleware"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// AccountHandler serves the email-change and account-deletion endpoints.
type AccountHandler struct {
	emailChanges *service.EmailChangeService
	deletions    *service.AccountDeletionService
	cookie       httpx.CookieSettings
}

// NewAccountHandler wires the account endpoints.
func NewAccountHandler(emailChanges *service.EmailChangeService, deletions *service.AccountDeletionService, cookie httpx.CookieSettings) *AccountHandler {
	return &AccountHandler{emailChanges: emailChanges, deletions: deletions, cookie: cookie}
}

// RequestEmailChange handles POST /api/v1/auth/email-change-requests.
func (h *AccountHandler) RequestEmailChange(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	var payload request.EmailChange
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	err := h.emailChanges.Request(c.Request.Context(), service.RequestEmailChangeCommand{
		AccountID:       identity.AccountID,
		CurrentPassword: payload.CurrentPassword,
		NewEmail:        payload.NewEmail,
	})
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.Accepted(c, nil)
}

// ConfirmEmailChange handles POST /api/v1/auth/email-changes/confirmations. The
// identity moves and every session is revoked, so the cookie is cleared.
func (h *AccountHandler) ConfirmEmailChange(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	var payload request.EmailChangeConfirmation
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	err := h.emailChanges.Confirm(c.Request.Context(), service.ConfirmEmailChangeCommand{
		AccountID: identity.AccountID,
		NewEmail:  payload.NewEmail,
		OTP:       payload.OTP,
	})
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.ClearRefreshCookie(c, h.cookie)
	c.Status(http.StatusNoContent)
}

// RequestOwnDeletion handles POST /api/v1/auth/accounts/me/deletion-requests.
func (h *AccountHandler) RequestOwnDeletion(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	var payload request.CurrentPassword
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	result, err := h.deletions.DeleteOwn(c.Request.Context(), identity.AccountID, payload.CurrentPassword)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.ClearRefreshCookie(c, h.cookie)
	httpx.WriteJSON(c, http.StatusAccepted, response.NewAccountDeletionAccepted(result))
}

// RequestAdminDeletion handles
// POST /api/v1/auth/admin/accounts/{account_id}/deletion-requests. The ADMIN
// role is enforced by middleware before this handler runs.
func (h *AccountHandler) RequestAdminDeletion(c *gin.Context) {
	accountID, err := httpx.PathUUID(c, "account_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	result, err := h.deletions.DeleteByAdmin(c.Request.Context(), accountID)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusAccepted, response.NewAccountDeletionAccepted(result))
}
