package handler

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"user-service/internal/api/dto/request"
	"user-service/internal/api/dto/response"
	"user-service/internal/api/middleware"
	"user-service/internal/domain/service"
)

// PreferencesHandler handles preferences-related HTTP requests
type PreferencesHandler struct {
	preferencesService *service.PreferencesService
	db                 *pgxpool.Pool
	logger             *zap.Logger
}

// NewPreferencesHandler creates a new preferences handler
func NewPreferencesHandler(preferencesService *service.PreferencesService, db *pgxpool.Pool, logger *zap.Logger) *PreferencesHandler {
	return &PreferencesHandler{
		preferencesService: preferencesService,
		db:                 db,
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
// Uses transactional outbox pattern to ensure preferences update and outbox event are committed atomically
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

	// Begin transaction for atomic preferences update + outbox event
	ctx := c.Request.Context()
	tx, err := h.db.Begin(ctx)
	if err != nil {
		h.logger.Error("failed to begin transaction", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while updating preferences",
		))
		return
	}
	defer tx.Rollback(ctx)

	prefs, err := h.preferencesService.UpdatePreferencesWithOutbox(
		ctx,
		tx,
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

	// Commit transaction
	if err := tx.Commit(ctx); err != nil {
		h.logger.Error("failed to commit transaction", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while updating preferences",
		))
		return
	}

	c.JSON(http.StatusOK, response.PreferencesResponseFromEntity(prefs))
}
