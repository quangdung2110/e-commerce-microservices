package handler

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/dto/response"
	apperrors "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

const refreshTokenCookieName = "refresh_token"

type SessionHandler struct {
	sessionService *service.SessionService
	logger         *zap.Logger
}

func NewSessionHandler(sessionService *service.SessionService, logger *zap.Logger) *SessionHandler {
	return &SessionHandler{
		sessionService: sessionService,
		logger:         logger.With(zap.String("handler", "SessionHandler")),
	}
}

// ListSessions handles GET /api/v1/auth/sessions
func (h *SessionHandler) ListSessions(c *gin.Context) {
	userID, exists := c.Get("user_id")
	if !exists {
		respondError(c, apperrors.ErrUnauthorized)
		return
	}

	// Get current refresh token hash (from cookie)
	currentToken := getRefreshTokenFromCookie(c)
	currentTokenHash := ""
	if currentToken != "" {
		currentTokenHash = hashToken(currentToken)
	}

	sessions, err := h.sessionService.ListSessions(c.Request.Context(), userID.(string), currentTokenHash)
	if err != nil {
		h.logger.Error("Failed to list sessions",
			zap.String("user_id", userID.(string)),
			zap.Error(err),
		)
		respondError(c, apperrors.ErrInternal)
		return
	}

	// Format response
	sessionResponses := make([]*response.SessionResponse, 0, len(sessions))
	for _, s := range sessions {
		sessionResponses = append(sessionResponses, &response.SessionResponse{
			ID:         s.ID,
			DeviceID:   s.DeviceID,
			DeviceInfo: formatDeviceInfo(s.DeviceInfo),
			IPAddress:  s.IPAddress,
			CreatedAt:  s.CreatedAt,
			ExpiresAt:  s.ExpiresAt,
			IsCurrent:  s.IsCurrent,
		})
	}

	c.JSON(http.StatusOK, response.SessionsResponse{
		Sessions: sessionResponses,
		Total:    len(sessionResponses),
	})
}

// RevokeSession handles DELETE /api/v1/auth/sessions/:session_id
func (h *SessionHandler) RevokeSession(c *gin.Context) {
	userID, exists := c.Get("user_id")
	if !exists {
		respondError(c, apperrors.ErrUnauthorized)
		return
	}

	sessionID := c.Param("session_id")
	if sessionID == "" {
		respondError(c, apperrors.NewAppError("BAD_REQUEST", "Session ID is required", http.StatusBadRequest, nil))
		return
	}

	// Get current refresh token hash (from cookie)
	currentToken := getRefreshTokenFromCookie(c)
	currentTokenHash := ""
	if currentToken != "" {
		currentTokenHash = hashToken(currentToken)
	}

	// Check if it's the current session
	session, err := h.sessionService.GetSessionByID(c.Request.Context(), userID.(string), sessionID)
	if err != nil {
		h.logger.Error("Session not found",
			zap.String("user_id", userID.(string)),
			zap.String("session_id", sessionID),
			zap.Error(err),
		)
		respondError(c, apperrors.ErrSessionNotFound())
		return
	}

	if session.IsCurrent {
		respondError(c, apperrors.NewAppError("BAD_REQUEST", "Cannot revoke current session: use logout instead", http.StatusBadRequest, nil))
		return
	}

	err = h.sessionService.RevokeSession(c.Request.Context(), userID.(string), sessionID, currentTokenHash)
	if err != nil {
		h.logger.Error("Failed to revoke session",
			zap.String("user_id", userID.(string)),
			zap.String("session_id", sessionID),
			zap.Error(err),
		)
		respondError(c, apperrors.ErrInternal)
		return
	}

	c.JSON(http.StatusOK, response.MessageResponse{
		Message: "Session revoked successfully",
	})
}

// RevokeAllSessions handles DELETE /api/v1/auth/sessions
func (h *SessionHandler) RevokeAllSessions(c *gin.Context) {
	userID, exists := c.Get("user_id")
	if !exists {
		respondError(c, apperrors.ErrUnauthorized)
		return
	}

	// Get current refresh token hash (from cookie)
	currentToken := getRefreshTokenFromCookie(c)
	currentTokenHash := ""
	if currentToken != "" {
		currentTokenHash = hashToken(currentToken)
	}

	err := h.sessionService.RevokeAllSessions(c.Request.Context(), userID.(string), currentTokenHash)
	if err != nil {
		h.logger.Error("Failed to revoke all sessions",
			zap.String("user_id", userID.(string)),
			zap.Error(err),
		)
		respondError(c, apperrors.ErrInternal)
		return
	}

	c.JSON(http.StatusOK, response.MessageResponse{
		Message: "All other sessions revoked successfully",
	})
}

// GetSession handles GET /api/v1/auth/sessions/:session_id
func (h *SessionHandler) GetSession(c *gin.Context) {
	userID, exists := c.Get("user_id")
	if !exists {
		respondError(c, apperrors.ErrUnauthorized)
		return
	}

	sessionID := c.Param("session_id")
	if sessionID == "" {
		respondError(c, apperrors.NewAppError("BAD_REQUEST", "Session ID is required", http.StatusBadRequest, nil))
		return
	}

	session, err := h.sessionService.GetSessionByID(c.Request.Context(), userID.(string), sessionID)
	if err != nil {
		h.logger.Error("Session not found",
			zap.String("user_id", userID.(string)),
			zap.String("session_id", sessionID),
			zap.Error(err),
		)
		respondError(c, apperrors.ErrSessionNotFound())
		return
	}

	c.JSON(http.StatusOK, &response.SessionResponse{
		ID:         session.ID,
		DeviceID:   session.DeviceID,
		DeviceInfo: formatDeviceInfo(session.DeviceInfo),
		IPAddress:  session.IPAddress,
		CreatedAt:  session.CreatedAt,
		ExpiresAt:  session.ExpiresAt,
		IsCurrent:  session.IsCurrent,
	})
}

// Helper functions

func getRefreshTokenFromCookie(c *gin.Context) string {
	token, _ := c.Cookie(refreshTokenCookieName)
	return token
}

func formatDeviceInfo(info string) string {
	if info == "" {
		return "Unknown Device"
	}
	return info
}

func hashToken(token string) string {
	hash := sha256.Sum256([]byte(token))
	return hex.EncodeToString(hash[:])
}

func respondError(c *gin.Context, err error) {
	if appErr, ok := err.(*apperrors.AppError); ok {
		c.JSON(appErr.Status, gin.H{
			"code":    appErr.Code,
			"message": appErr.Message,
		})
		return
	}
	c.JSON(http.StatusInternalServerError, gin.H{
		"code":    "INTERNAL_ERROR",
		"message": "An internal error occurred",
	})
}
