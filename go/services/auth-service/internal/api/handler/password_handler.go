package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/dto/request"
	"github.com/ecommerce/auth-service/internal/api/dto/response"
	apperrors "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

type PasswordHandler struct {
	passwordService *service.PasswordService
	logger          *zap.Logger
}

func NewPasswordHandler(passwordService *service.PasswordService, logger *zap.Logger) *PasswordHandler {
	return &PasswordHandler{
		passwordService: passwordService,
		logger:          logger.With(zap.String("handler", "PasswordHandler")),
	}
}

// ChangePassword handles POST /api/v1/auth/change-password
func (h *PasswordHandler) ChangePassword(c *gin.Context) {
	userID, exists := c.Get("user_id")
	if !exists {
		respondError(c, apperrors.ErrUnauthorized)
		return
	}

	var req request.ChangePasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("Invalid request body", zap.Error(err))
		respondError(c, apperrors.NewAppError("BAD_REQUEST", err.Error(), http.StatusBadRequest, nil))
		return
	}

	err := h.passwordService.ChangePassword(c.Request.Context(), &service.PasswordChangeRequest{
		UserID:          userID.(string),
		CurrentPassword: req.CurrentPassword,
		NewPassword:     req.NewPassword,
		IPAddress:       c.ClientIP(),
		DeviceInfo:      c.GetHeader("User-Agent"),
	})
	if err != nil {
		h.logger.Error("Password change failed",
			zap.String("user_id", userID.(string)),
			zap.Error(err),
		)

		// Check for specific errors
		if err.Error() == "invalid current password" {
			respondError(c, apperrors.NewAppError("INVALID_PASSWORD", "Current password is incorrect", http.StatusBadRequest, nil))
			return
		}
		if err.Error()[:12] == "weak password" {
			respondError(c, apperrors.NewAppError("WEAK_PASSWORD", err.Error()[13:], http.StatusBadRequest, nil))
			return
		}

		respondError(c, apperrors.ErrInternal)
		return
	}

	c.JSON(http.StatusOK, response.MessageResponse{
		Message: "Password changed successfully. All other sessions have been logged out.",
	})
}

// ForgotPassword handles POST /api/v1/auth/public/forgot-password
func (h *PasswordHandler) ForgotPassword(c *gin.Context) {
	var req request.ForgotPasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("Invalid request body", zap.Error(err))
		respondError(c, apperrors.NewAppError("BAD_REQUEST", err.Error(), http.StatusBadRequest, nil))
		return
	}

	err := h.passwordService.RequestPasswordReset(c.Request.Context(), &service.ForgotPasswordRequest{
		Email:     req.Email,
		IPAddress: c.ClientIP(),
	})
	if err != nil {
		h.logger.Error("Password reset request failed",
			zap.String("email", maskEmailForLog(req.Email)),
			zap.Error(err),
		)
		respondError(c, apperrors.ErrInternal)
		return
	}

	// Always return success to prevent email enumeration
	c.JSON(http.StatusOK, response.ForgotPasswordResponse{
		Message: "If an account with that email exists, a password reset link has been sent.",
	})
}

// ResetPassword handles POST /api/v1/auth/public/reset-password
func (h *PasswordHandler) ResetPassword(c *gin.Context) {
	var req request.ResetPasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("Invalid request body", zap.Error(err))
		respondError(c, apperrors.NewAppError("BAD_REQUEST", err.Error(), http.StatusBadRequest, nil))
		return
	}

	err := h.passwordService.ResetPassword(c.Request.Context(), &service.PasswordResetRequest{
		ResetToken:  req.Token,
		NewPassword: req.NewPassword,
		IPAddress:   c.ClientIP(),
	})
	if err != nil {
		h.logger.Error("Password reset failed",
			zap.String("ip", c.ClientIP()),
			zap.Error(err),
		)

		if err.Error() == "invalid or expired reset token" {
			respondError(c, apperrors.NewAppError("INVALID_TOKEN", "Reset token has expired or is invalid", http.StatusBadRequest, nil))
			return
		}
		if err.Error()[:12] == "weak password" {
			respondError(c, apperrors.NewAppError("WEAK_PASSWORD", err.Error()[13:], http.StatusBadRequest, nil))
			return
		}

		respondError(c, apperrors.ErrInternal)
		return
	}

	c.JSON(http.StatusOK, response.MessageResponse{
		Message: "Password has been reset successfully. You can now log in with your new password.",
	})
}
