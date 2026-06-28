package handler

import (
	"errors"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"user-service/internal/api/dto/request"
	"user-service/internal/api/dto/response"
	"user-service/internal/api/middleware"
	"user-service/internal/domain/service"
	"user-service/internal/infrastructure/kafka"
)

// ProfileHandler handles profile-related HTTP requests
type ProfileHandler struct {
	profileService *service.ProfileService
	producer       *kafka.UserEventProducer
	logger         *zap.Logger
}

// NewProfileHandler creates a new profile handler
func NewProfileHandler(profileService *service.ProfileService, producer *kafka.UserEventProducer, logger *zap.Logger) *ProfileHandler {
	return &ProfileHandler{
		profileService: profileService,
		producer:       producer,
		logger:         logger,
	}
}

// GetProfile handles GET /api/v1/users/me/profile
func (h *ProfileHandler) GetProfile(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	profile, err := h.profileService.GetProfile(c.Request.Context(), userID)
	if err != nil {
		if errors.Is(err, service.ErrProfileNotFound) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodeProfileNotFound,
				"Hồ sơ người dùng không tồn tại",
			))
			return
		}
		h.logger.Error("failed to get profile", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while fetching profile",
		))
		return
	}

	c.JSON(http.StatusOK, response.ToProfileResponse(profile))
}

// UpdateProfile handles PUT /api/v1/users/me/profile
func (h *ProfileHandler) UpdateProfile(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	var req request.UpdateProfileRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
			"Dữ liệu không hợp lệ",
			[]response.ErrorDetail{{Field: "body", Message: err.Error()}},
		))
		return
	}

	var dob *time.Time
	if req.DateOfBirth != "" {
		parsed, err := time.Parse("2006-01-02", req.DateOfBirth)
		if err != nil {
			c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
				"Ngày sinh không đúng định dạng",
				[]response.ErrorDetail{{Field: "date_of_birth", Message: "Định dạng: YYYY-MM-DD"}},
			))
			return
		}
		dob = &parsed
	}

	ctx := c.Request.Context()

	profile, err := h.profileService.UpdateProfile(
		ctx,
		userID,
		req.FirstName,
		req.LastName,
		req.PhoneNumber,
		dob,
		req.Gender,
	)

	if err != nil {
		if errors.Is(err, service.ErrProfileNotFound) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodeProfileNotFound,
				"Hồ sơ người dùng không tồn tại",
			))
			return
		}
		if errors.Is(err, service.ErrPhoneNumberExists) {
			c.JSON(http.StatusConflict, response.NewErrorResponse(
				response.ErrCodePhoneNumberExists,
				"Số điện thoại đã được sử dụng bởi người dùng khác",
			))
			return
		}
		h.logger.Error("failed to update profile", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while updating profile",
		))
		return
	}

	// Publish profile updated event to Kafka (best-effort async)
	if err := h.producer.PublishUserProfileUpdated(ctx, kafka.ProfileUpdatedPayload{
		ID:          profile.ID,
		FirstName:   profile.FirstName,
		LastName:    profile.LastName,
		PhoneNumber: profile.PhoneNumber,
		DateOfBirth: profile.DateOfBirth,
		Gender:      string(profile.Gender),
	}); err != nil {
		h.logger.Error("failed to publish profile updated event", zap.Error(err))
	}

	c.JSON(http.StatusOK, response.ToProfileResponse(profile))
}
