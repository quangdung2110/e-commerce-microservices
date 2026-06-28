package handler

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"user-service/internal/api/dto/request"
	"user-service/internal/api/dto/response"
	"user-service/internal/api/middleware"
	"user-service/internal/domain/service"
	"user-service/internal/infrastructure/kafka"
)

// PreferencesHandler handles preferences-related HTTP requests
type PreferencesHandler struct {
	preferencesService *service.PreferencesService
	producer           *kafka.UserEventProducer
	logger             *zap.Logger
}

// NewPreferencesHandler creates a new preferences handler
func NewPreferencesHandler(preferencesService *service.PreferencesService, producer *kafka.UserEventProducer, logger *zap.Logger) *PreferencesHandler {
	return &PreferencesHandler{
		preferencesService: preferencesService,
		producer:           producer,
		logger:             logger,
	}
}

// GetPreferences handles GET /api/v1/users/me/preferences
func (h *PreferencesHandler) GetPreferences(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	prefs, err := h.preferencesService.GetPreferences(c.Request.Context(), userID)
	if err != nil {
		if errors.Is(err, service.ErrPreferencesNotFound) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodePreferencesNotFound,
				"Cài đặt người dùng không tồn tại",
			))
			return
		}
		h.logger.Error("failed to get preferences", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while fetching preferences",
		))
		return
	}

	c.JSON(http.StatusOK, response.PreferencesResponseFromEntity(prefs))
}

// UpdatePreferences handles PUT /api/v1/users/me/preferences
func (h *PreferencesHandler) UpdatePreferences(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	var req request.UpdatePreferencesRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
			"Dữ liệu không hợp lệ",
			[]response.ErrorDetail{{Field: "body", Message: err.Error()}},
		))
		return
	}

	ctx := c.Request.Context()

	prefs, err := h.preferencesService.UpdatePreferences(
		ctx,
		userID,
		req.Language,
		req.Currency,
		req.EmailEnabled,
		req.InAppEnabled,
		req.MarketingEnabled,
	)

	if err != nil {
		if errors.Is(err, service.ErrPreferencesNotFound) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodePreferencesNotFound,
				"Cài đặt người dùng không tồn tại",
			))
			return
		}
		h.logger.Error("failed to update preferences", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while updating preferences",
		))
		return
	}

	// Publish preferences updated event to Kafka (best-effort async)
	if err := h.producer.PublishUserPreferencesUpdated(ctx, kafka.PreferencesUpdatedPayload{
		ID:               prefs.UserID,
		Language:         prefs.Language,
		Currency:         prefs.Currency,
		EmailEnabled:     prefs.EmailEnabled,
		InAppEnabled:     prefs.InAppEnabled,
		MarketingEnabled: prefs.MarketingEnabled,
	}); err != nil {
		h.logger.Error("failed to publish preferences updated event", zap.Error(err))
	}

	c.JSON(http.StatusOK, response.PreferencesResponseFromEntity(prefs))
}
