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

// SessionHandler serves login, refresh and the session endpoints.
type SessionHandler struct {
	auth     *service.AuthenticationService
	sessions *service.SessionService
	cookie   httpx.CookieSettings
}

// NewSessionHandler wires the session endpoints.
func NewSessionHandler(auth *service.AuthenticationService, sessions *service.SessionService, cookie httpx.CookieSettings) *SessionHandler {
	return &SessionHandler{auth: auth, sessions: sessions, cookie: cookie}
}

// Login handles POST /api/v1/auth/sessions.
func (h *SessionHandler) Login(c *gin.Context) {
	var payload request.Login
	if err := httpx.DecodeJSON(c, &payload); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	result, err := h.auth.Login(c.Request.Context(), service.LoginCommand{
		Email:      payload.Email,
		Password:   payload.Password,
		DeviceName: payload.DeviceName,
		UserAgent:  c.Request.UserAgent(),
		IPAddress:  c.ClientIP(),
	})
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.SetRefreshCookie(c, h.cookie, result.RefreshToken, result.RefreshExpiresAt)
	httpx.WriteJSON(c, http.StatusOK, response.NewAccessToken(result))
}

// Refresh handles POST /api/v1/auth/sessions/refresh. A failed rotation clears
// the cookie so the browser stops presenting a token that can never work again.
func (h *SessionHandler) Refresh(c *gin.Context) {
	result, err := h.sessions.Refresh(c.Request.Context(), service.RefreshCommand{
		RefreshToken: httpx.RefreshCookie(c, h.cookie),
		UserAgent:    c.Request.UserAgent(),
		IPAddress:    c.ClientIP(),
	})
	if err != nil {
		if apperr.From(err).Code == apperr.CodeRefreshTokenInvalid {
			httpx.ClearRefreshCookie(c, h.cookie)
		}
		httpx.WriteProblem(c, err)
		return
	}

	httpx.SetRefreshCookie(c, h.cookie, result.RefreshToken, result.RefreshExpiresAt)
	httpx.WriteJSON(c, http.StatusOK, response.NewAccessToken(result))
}

// ListSessions handles GET /api/v1/auth/sessions.
func (h *SessionHandler) ListSessions(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	limit, err := httpx.QueryLimit(c, service.DefaultSessionPageSize, service.MaxSessionPageSize)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	sessions, nextCursor, err := h.sessions.ListSessions(c.Request.Context(), identity.AccountID, c.Query("cursor"), limit)
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.WriteJSON(c, http.StatusOK, response.NewSessionList(sessions, nextCursor))
}

// RevokeSession handles DELETE /api/v1/auth/sessions/{session_id}.
func (h *SessionHandler) RevokeSession(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	sessionID, err := httpx.PathUUID(c, "session_id")
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	result, err := h.sessions.RevokeSession(c.Request.Context(), service.RevokeSessionCommand{
		AccountID:        identity.AccountID,
		SessionID:        sessionID,
		TokenJti:         identity.TokenJti,
		RefreshToken:     httpx.RefreshCookie(c, h.cookie),
		CurrentSessionID: identity.SessionID,
	})
	if err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	if result.CurrentSessionRevoked {
		httpx.ClearRefreshCookie(c, h.cookie)
	}
	c.Status(http.StatusNoContent)
}

// RevokeAllSessions handles DELETE /api/v1/auth/sessions.
func (h *SessionHandler) RevokeAllSessions(c *gin.Context) {
	identity, ok := middleware.IdentityFrom(c)
	if !ok {
		httpx.WriteProblem(c, apperr.ErrUnauthorized)
		return
	}

	if err := h.sessions.RevokeAllSessions(c.Request.Context(), identity.AccountID); err != nil {
		httpx.WriteProblem(c, err)
		return
	}

	httpx.ClearRefreshCookie(c, h.cookie)
	c.Status(http.StatusNoContent)
}
