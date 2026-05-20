package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/api/dto/request"
	"github.com/ecommerce/auth-service/internal/api/dto/response"
	"github.com/ecommerce/auth-service/internal/api/dto/svc"
	apperrors "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

type UserHandler struct {
	userService *service.UserService
}

func NewUserHandler(userService *service.UserService) *UserHandler {
	return &UserHandler{
		userService: userService,
	}
}

func (h *UserHandler) Register(c *gin.Context) {
	var req request.RegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		handleServiceError(c, apperrors.NewAppError("VALIDATION_ERROR", err.Error(), http.StatusBadRequest, err))
		return
	}

	ipAddress := c.ClientIP()
	deviceInfo := c.GetHeader("User-Agent")

	user, _, err := h.userService.Register(c.Request.Context(), &svc.RegisterRequest{
		Email:      req.Email,
		Password:   req.Password,
		IPAddress:  ipAddress,
		DeviceInfo: deviceInfo,
	})
	if err != nil {
		handleServiceError(c, err)
		return
	}

	c.JSON(http.StatusCreated, response.RegisterResponse{
		Message:             "Registration successful. Please verify your email.",
		UserID:              user.ID,
		Email:               user.Email.String(),
		Status:              string(user.Status),
		RequireVerification: true,
	})
}

func (h *UserHandler) VerifyEmail(c *gin.Context) {
	var req request.VerifyEmailRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		handleServiceError(c, apperrors.NewAppError("VALIDATION_ERROR", err.Error(), http.StatusBadRequest, err))
		return
	}

	err := h.userService.VerifyEmail(c.Request.Context(), req.Email, req.OTP)
	if err != nil {
		handleServiceError(c, err)
		return
	}

	c.JSON(http.StatusOK, response.MessageResponse{
		Message: "Email verified successfully. You can now log in.",
	})
}

func (h *UserHandler) ResendOTP(c *gin.Context) {
	var req request.ResendOTPRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		handleServiceError(c, apperrors.NewAppError("VALIDATION_ERROR", err.Error(), http.StatusBadRequest, err))
		return
	}

	_, err := h.userService.ResendOTP(c.Request.Context(), req.Email)
	if err != nil {
		handleServiceError(c, err)
		return
	}

	c.JSON(http.StatusOK, response.MessageResponse{
		Message: "Verification code sent to your email.",
	})
}

func handleServiceError(c *gin.Context, err error) {
	if appErr, ok := err.(*apperrors.AppError); ok {
		c.JSON(appErr.Status, gin.H{
			"error": gin.H{
				"code":    appErr.Code,
				"message": appErr.Message,
			},
		})
		return
	}
	c.JSON(http.StatusInternalServerError, gin.H{
		"error": gin.H{
			"code":    "INTERNAL_ERROR",
			"message": "Internal server error",
		},
	})
}
