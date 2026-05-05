package handler

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"user-service/internal/api/dto/request"
	"user-service/internal/api/dto/response"
	"user-service/internal/api/middleware"
	"user-service/internal/domain/service"
)

const (
	// CacheTTLSeconds is the cache TTL for address list responses
	CacheTTLSeconds = 43200 // 12 hours
)

// AddressHandler handles address-related HTTP requests
type AddressHandler struct {
	addressService *service.AddressService
	logger        *zap.Logger
}

// NewAddressHandler creates a new address handler
func NewAddressHandler(addressService *service.AddressService, logger *zap.Logger) *AddressHandler {
	return &AddressHandler{
		addressService: addressService,
		logger:        logger,
	}
}

// ListAddresses handles GET /api/v1/users/me/addresses
func (h *AddressHandler) ListAddresses(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	addresses, err := h.addressService.GetByUserID(c.Request.Context(), userID)
	if err != nil {
		h.logger.Error("failed to get addresses", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while fetching addresses",
		))
		return
	}

	// Convert to response DTOs
	addressResponses := make([]*response.AddressResponse, len(addresses))
	for i, addr := range addresses {
		addressResponses[i] = response.AddressResponseFromEntity(addr)
	}

	c.JSON(http.StatusOK, response.AddressListResponse{
		Addresses:       addressResponses,
		Total:           len(addressResponses),
		Cached:          true,
		CacheTTLSeconds: CacheTTLSeconds,
	})
}

// CreateAddress handles POST /api/v1/users/me/addresses
func (h *AddressHandler) CreateAddress(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	var req request.CreateAddressRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
			"Dữ liệu không hợp lệ",
			[]response.ErrorDetail{{Field: "body", Message: err.Error()}},
		))
		return
	}

	// Validate request fields
	if err := service.ValidateAddressFields(
		req.RecipientName,
		req.PhoneNumber,
		req.AddressLine,
		req.WardID,
		req.DistrictID,
		req.CityID,
	); err != nil {
		c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
			"Dữ liệu không hợp lệ",
			[]response.ErrorDetail{{Field: "request", Message: err.Error()}},
		))
		return
	}

	address, err := h.addressService.Create(
		c.Request.Context(),
		userID,
		req.RecipientName,
		req.PhoneNumber,
		req.AddressLine,
		req.WardID,
		req.DistrictID,
		req.CityID,
	)

	if err != nil {
		h.logger.Error("failed to create address", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while creating address",
		))
		return
	}

	c.JSON(http.StatusCreated, response.AddressResponseFromEntity(address))
}

// UpdateAddress handles PUT /api/v1/users/me/addresses/:id
func (h *AddressHandler) UpdateAddress(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	addressID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
			"ID địa chỉ không hợp lệ",
			[]response.ErrorDetail{{Field: "id", Message: "UUID không hợp lệ"}},
		))
		return
	}

	var req request.UpdateAddressRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
			"Dữ liệu không hợp lệ",
			[]response.ErrorDetail{{Field: "body", Message: err.Error()}},
		))
		return
	}

	address, err := h.addressService.Update(
		c.Request.Context(),
		userID,
		addressID,
		req.RecipientName,
		req.PhoneNumber,
		req.AddressLine,
		req.WardID,
		req.DistrictID,
		req.CityID,
	)

	if err != nil {
		if errors.Is(err, service.ErrAddressNotFound) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodeAddressNotFound,
				"Địa chỉ không tồn tại hoặc không thuộc về bạn",
			))
			return
		}
		if errors.Is(err, service.ErrAddressNotOwned) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodeAddressNotOwned,
				"Địa chỉ không tồn tại hoặc không thuộc về bạn",
			))
			return
		}
		h.logger.Error("failed to update address", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while updating address",
		))
		return
	}

	c.JSON(http.StatusOK, response.AddressResponseFromEntity(address))
}

// DeleteAddress handles DELETE /api/v1/users/me/addresses/:id
func (h *AddressHandler) DeleteAddress(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	addressID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
			"ID địa chỉ không hợp lệ",
			[]response.ErrorDetail{{Field: "id", Message: "UUID không hợp lệ"}},
		))
		return
	}

	err = h.addressService.Delete(c.Request.Context(), userID, addressID)
	if err != nil {
		if errors.Is(err, service.ErrAddressNotFound) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodeAddressNotFound,
				"Địa chỉ không tồn tại hoặc không thuộc về bạn",
			))
			return
		}
		if errors.Is(err, service.ErrAddressNotOwned) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodeAddressNotOwned,
				"Địa chỉ không tồn tại hoặc không thuộc về bạn",
			))
			return
		}
		if errors.Is(err, service.ErrCannotDeleteOnlyDefault) {
			c.JSON(http.StatusConflict, response.NewErrorResponse(
				response.ErrCodeCannotDeleteOnlyAddress,
				"Không thể xóa địa chỉ mặc định duy nhất. Vui lòng thêm địa chỉ khác trước.",
			))
			return
		}
		h.logger.Error("failed to delete address", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while deleting address",
		))
		return
	}

	c.Status(http.StatusNoContent)
}

// SetDefaultAddress handles POST /api/v1/users/me/addresses/:id/default
func (h *AddressHandler) SetDefaultAddress(c *gin.Context) {
	userID, ok := middleware.GetUserID(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, response.NewErrorResponse(
			response.ErrCodeUnauthorized,
			"User not authenticated",
		))
		return
	}

	addressID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, response.NewValidationErrorResponse(
			"ID địa chỉ không hợp lệ",
			[]response.ErrorDetail{{Field: "id", Message: "UUID không hợp lệ"}},
		))
		return
	}

	address, err := h.addressService.SetDefault(c.Request.Context(), userID, addressID)
	if err != nil {
		if errors.Is(err, service.ErrAddressNotFound) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodeAddressNotFound,
				"Địa chỉ không tồn tại hoặc không thuộc về bạn",
			))
			return
		}
		if errors.Is(err, service.ErrAddressNotOwned) {
			c.JSON(http.StatusNotFound, response.NewErrorResponse(
				response.ErrCodeAddressNotOwned,
				"Địa chỉ không tồn tại hoặc không thuộc về bạn",
			))
			return
		}
		h.logger.Error("failed to set default address", zap.Error(err))
		c.JSON(http.StatusInternalServerError, response.NewErrorResponse(
			response.ErrCodeInternalError,
			"An error occurred while setting default address",
		))
		return
	}

	c.JSON(http.StatusOK, response.AddressResponseFromEntity(address))
}
