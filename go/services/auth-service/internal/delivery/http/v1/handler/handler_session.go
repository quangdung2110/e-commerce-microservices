package v1_handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	v1 "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/session"
)

// SessionHandler handles session management HTTP requests
type SessionHandler struct {
	sessionManager *session.ManagerUsecase
	logger         *zap.Logger
}

// NewSessionHandler creates new session handler
func NewSessionHandler(
	sessionManager *session.ManagerUsecase,
	logger *zap.Logger,
) *SessionHandler {
	return &SessionHandler{
		sessionManager: sessionManager,
		logger:         logger,
	}
}

// ListSessions lists all active sessions for current user
// @Summary List user sessions
// @Description Get all active sessions for authenticated user
// @Tags sessions
// @Accept json
// @Produce json
// @Security BearerAuth
// @Success 200 {object} dto.ListSessionsResponse
// @Failure 401 {object} v1.ErrorResponse
// @Router /api/v1/sessions [get]
func (h *SessionHandler) ListSessions(c *gin.Context) {
	// Get user ID from context (set by auth middleware)
	userID, exists := c.Get("user_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found", nil)
		return
	}

	// Get current session ID
	var currentSessionID *uuid.UUID
	if sessionID, exists := c.Get("session_id"); exists {
		sid := sessionID.(uuid.UUID)
		currentSessionID = &sid
	}

	// Execute list sessions
	resp, err := h.sessionManager.ListSessions(c.Request.Context(), userID.(uuid.UUID), currentSessionID)
	if err != nil {
		h.handleSessionError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// GetSession gets details of a specific session
// @Summary Get session details
// @Description Get detailed information about a specific session
// @Tags sessions
// @Accept json
// @Produce json
// @Security BearerAuth
// @Param session_id path string true "Session ID"
// @Success 200 {object} dto.SessionInfo
// @Failure 401 {object} v1.ErrorResponse
// @Failure 404 {object} v1.ErrorResponse
// @Router /api/v1/sessions/{session_id} [get]
func (h *SessionHandler) GetSession(c *gin.Context) {
	// Get user ID from context
	userID, exists := c.Get("user_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found", nil)
		return
	}

	// Get session ID from path
	sessionIDStr := c.Param("session_id")
	sessionID, err := uuid.Parse(sessionIDStr)
	if err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_SESSION_ID", "Invalid session ID format", err)
		return
	}

	// Execute get session
	resp, err := h.sessionManager.GetSessionInfo(c.Request.Context(), userID.(uuid.UUID), sessionID)
	if err != nil {
		h.handleSessionError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// RevokeSession revokes a specific session
// @Summary Revoke session
// @Description Revoke a specific session (logout from one device)
// @Tags sessions
// @Accept json
// @Produce json
// @Security BearerAuth
// @Param session_id path string true "Session ID"
// @Success 200 {object} v1.SuccessResponse
// @Failure 401 {object} v1.ErrorResponse
// @Failure 404 {object} v1.ErrorResponse
// @Router /api/v1/sessions/{session_id} [delete]
func (h *SessionHandler) RevokeSession(c *gin.Context) {
	// Get user ID from context
	userID, exists := c.Get("user_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found", nil)
		return
	}

	// Get session ID from path
	sessionIDStr := c.Param("session_id")
	sessionID, err := uuid.Parse(sessionIDStr)
	if err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_SESSION_ID", "Invalid session ID format", err)
		return
	}

	// Execute revoke session
	if err := h.sessionManager.RevokeSession(c.Request.Context(), userID.(uuid.UUID), sessionID); err != nil {
		h.handleSessionError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, gin.H{
		"message": "Session revoked successfully",
	})
}

// RevokeAllSessions revokes all sessions except current one
// @Summary Revoke all sessions
// @Description Revoke all sessions except the current one
// @Tags sessions
// @Accept json
// @Produce json
// @Security BearerAuth
// @Success 200 {object} v1.SuccessResponse
// @Failure 401 {object} v1.ErrorResponse
// @Router /api/v1/sessions [delete]
func (h *SessionHandler) RevokeAllSessions(c *gin.Context) {
	// Get user ID from context
	userID, exists := c.Get("user_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found", nil)
		return
	}

	// Get current session ID
	currentSessionID, exists := c.Get("session_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "Session ID not found", nil)
		return
	}

	// Execute revoke all sessions
	revokedCount, err := h.sessionManager.RevokeAllSessions(c.Request.Context(), userID.(uuid.UUID), currentSessionID.(uuid.UUID))
	if err != nil {
		h.handleSessionError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, gin.H{
		"message":       "All other sessions revoked successfully",
		"revoked_count": revokedCount,
	})
}

// handleSessionError handles session-related errors
func (h *SessionHandler) handleSessionError(c *gin.Context, err error) {
	h.logger.Error("Session error", zap.Error(err))

	// Check if it's an AppError
	if appErr := serr.GetAppError(err); appErr != nil {
		v1.SendErrorResponse(c, appErr.HTTPStatus, appErr.Code, appErr.Message, err)
		return
	}

	// Default to internal server error
	v1.SendErrorResponse(c, http.StatusInternalServerError, "INTERNAL_ERROR", "An error occurred", err)
}
