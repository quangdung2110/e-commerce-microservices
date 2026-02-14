package v1_handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	v1 "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/password"
)

// PasswordHandler handles password-related HTTP requests
type PasswordHandler struct {
	resetUsecase  *password.ResetUsecase
	changeUsecase *password.ChangeUsecase
	logger        *zap.Logger
}

// NewPasswordHandler creates new password handler
func NewPasswordHandler(
	resetUsecase *password.ResetUsecase,
	changeUsecase *password.ChangeUsecase,
	logger *zap.Logger,
) *PasswordHandler {
	return &PasswordHandler{
		resetUsecase:  resetUsecase,
		changeUsecase: changeUsecase,
		logger:        logger,
	}
}

// ForgotPassword initiates password reset flow
// @Summary Initiate password reset
// @Description Send password reset email to user
// @Tags password
// @Accept json
// @Produce json
// @Param request body dto.ForgotPasswordRequest true "Forgot password request"
// @Success 200 {object} v1.SuccessResponse
// @Failure 400 {object} v1.ErrorResponse
// @Failure 429 {object} v1.ErrorResponse
// @Router /api/v1/password/forgot [post]
func (h *PasswordHandler) ForgotPassword(c *gin.Context) {
	var req dto.ForgotPasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request body", err)
		return
	}

	// Get request context
	reqCtx := extractRequestContext(c)

	// Execute forgot password
	if _, err := h.resetUsecase.ForgotPassword(c.Request.Context(), &req, reqCtx); err != nil {
		h.handlePasswordError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, gin.H{
		"message": "If the email exists, a password reset link has been sent",
	})
}

// ValidateResetToken validates password reset token
// @Summary Validate reset token
// @Description Check if password reset token is valid
// @Tags password
// @Accept json
// @Produce json
// @Param request body dto.ValidateResetTokenRequest true "Validate token request"
// @Success 200 {object} v1.SuccessResponse
// @Failure 400 {object} v1.ErrorResponse
// @Failure 404 {object} v1.ErrorResponse
// @Router /api/v1/password/validate-token [post]
func (h *PasswordHandler) ValidateResetToken(c *gin.Context) {
	var req dto.ValidateResetTokenRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request body", err)
		return
	}

	// Execute validation
	resp, err := h.resetUsecase.ValidateResetToken(c.Request.Context(), &req)
	if err != nil {
		h.handlePasswordError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// ResetPassword completes password reset
// @Summary Reset password
// @Description Reset password using reset token
// @Tags password
// @Accept json
// @Produce json
// @Param request body dto.ResetPasswordRequest true "Reset password request"
// @Success 200 {object} v1.SuccessResponse
// @Failure 400 {object} v1.ErrorResponse
// @Failure 404 {object} v1.ErrorResponse
// @Router /api/v1/password/reset [post]
func (h *PasswordHandler) ResetPassword(c *gin.Context) {
	var req dto.ResetPasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request body", err)
		return
	}

	// Get request context
	reqCtx := extractRequestContext(c)

	// Execute password reset
	resp, err := h.resetUsecase.ResetPassword(c.Request.Context(), &req, reqCtx)
	if err != nil {
		h.handlePasswordError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, resp)
}

// ChangePassword changes user password (authenticated)
// @Summary Change password
// @Description Change password for authenticated user
// @Tags password
// @Accept json
// @Produce json
// @Security BearerAuth
// @Param request body dto.ChangePasswordRequest true "Change password request"
// @Success 200 {object} v1.SuccessResponse
// @Failure 400 {object} v1.ErrorResponse
// @Failure 401 {object} v1.ErrorResponse
// @Failure 429 {object} v1.ErrorResponse
// @Router /api/v1/password/change [post]
func (h *PasswordHandler) ChangePassword(c *gin.Context) {
	var req dto.ChangePasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		v1.SendErrorResponse(c, http.StatusBadRequest, "INVALID_REQUEST", "Invalid request body", err)
		return
	}

	// Get user ID from context (set by auth middleware)
	userID, exists := c.Get("user_id")
	if !exists {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found", nil)
		return
	}

	// Get current session ID from context
	var currentSessionID *uuid.UUID
	if sessionID, exists := c.Get("session_id"); exists {
		sid := sessionID.(uuid.UUID)
		currentSessionID = &sid
	}

	// Get request context
	reqCtx := extractRequestContext(c)

	// Execute password change
	if _, err := h.changeUsecase.ChangePassword(c.Request.Context(), &req, userID.(uuid.UUID), currentSessionID, reqCtx); err != nil {
		h.handlePasswordError(c, err)
		return
	}

	v1.SendSuccessResponse(c, http.StatusOK, gin.H{
		"message": "Password changed successfully",
	})
}

// handlePasswordError handles password-related errors
func (h *PasswordHandler) handlePasswordError(c *gin.Context, err error) {
	h.logger.Error("Password error", zap.Error(err))

	// Check if it's an AppError
	if appErr := serr.GetAppError(err); appErr != nil {
		v1.SendErrorResponse(c, appErr.HTTPStatus, appErr.Code, appErr.Message, err)
		return
	}

	// Default to internal server error
	v1.SendErrorResponse(c, http.StatusInternalServerError, "INTERNAL_ERROR", "An error occurred", err)
}
