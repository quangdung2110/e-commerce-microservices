package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/dto/request"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/api/middleware"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// PasswordHandler serves the password change and reset endpoints.
type PasswordHandler struct {
	passwords *service.PasswordService
	cookie    httpx.CookieSettings
}

// NewPasswordHandler wires the password endpoints.
func NewPasswordHandler(passwords *service.PasswordService, cookie httpx.CookieSettings) *PasswordHandler {
	return &PasswordHandler{passwords: passwords, cookie: cookie}
}

// ChangePassword handles PUT /api/v1/auth/password. Every session is revoked, so
// the cookie is cleared with the response.
func (h *PasswordHandler) ChangePassword(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	var payload request.ChangePassword
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	err := h.passwords.ChangePassword(c.Request.Context(), service.ChangePasswordCommand{
		AccountID:       identity.AccountID,
		CurrentPassword: payload.CurrentPassword,
		NewPassword:     payload.NewPassword,
		ConfirmPassword: payload.ConfirmPassword,
	})
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.ClearRefreshCookie(c, h.cookie)
	c.Status(http.StatusNoContent)
}

// RequestReset handles POST /api/v1/auth/password-reset-requests. It answers the
// same way whether or not the address has an account.
func (h *PasswordHandler) RequestReset(c *gin.Context) {
	var payload request.Email
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	if err := h.passwords.RequestReset(c.Request.Context(), payload.Email); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.Accepted(c, nil)
}

// CompleteReset handles POST /api/v1/auth/password-resets.
func (h *PasswordHandler) CompleteReset(c *gin.Context) {
	var payload request.CompletePasswordReset
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	err := h.passwords.CompleteReset(c.Request.Context(), service.CompleteResetCommand{
		ResetToken:      payload.ResetToken,
		NewPassword:     payload.NewPassword,
		ConfirmPassword: payload.ConfirmPassword,
	})
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.ClearRefreshCookie(c, h.cookie)
	c.Status(http.StatusNoContent)
}
