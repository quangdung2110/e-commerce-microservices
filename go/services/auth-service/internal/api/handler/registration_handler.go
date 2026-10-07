// Package handler adapts HTTP requests to the domain use cases. A handler
// parses, delegates and renders; every business rule lives in the domain.
package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/dto/request"
	"github.com/ecommerce/auth-service/internal/api/dto/response"
	"github.com/ecommerce/auth-service/internal/api/httpx"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

// RegistrationHandler serves the registration and email-verification endpoints.
type RegistrationHandler struct {
	registrations *service.RegistrationService
}

// NewRegistrationHandler wires the registration endpoints.
func NewRegistrationHandler(registrations *service.RegistrationService) *RegistrationHandler {
	return &RegistrationHandler{registrations: registrations}
}

// Register handles POST /api/v1/auth/registrations.
func (h *RegistrationHandler) Register(c *gin.Context) {
	var payload request.Registration
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	err := h.registrations.Register(c.Request.Context(), service.RegisterCommand{
		Email:           payload.Email,
		Password:        payload.Password,
		ConfirmPassword: payload.ConfirmPassword,
	})
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.Accepted(c, response.NewRegistrationAccepted())
}

// RequestVerification handles POST /api/v1/auth/email-verification-requests.
// The response is identical whether or not the address has an account.
func (h *RegistrationHandler) RequestVerification(c *gin.Context) {
	var payload request.Email
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	if err := h.registrations.RequestVerification(c.Request.Context(), payload.Email); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.Accepted(c, nil)
}

// VerifyEmail handles POST /api/v1/auth/email-verifications.
func (h *RegistrationHandler) VerifyEmail(c *gin.Context) {
	var payload request.EmailVerification
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	if err := h.registrations.VerifyEmail(c.Request.Context(), payload.Email, payload.OTP); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	c.Status(http.StatusNoContent)
}
