package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"cart-service/internal/api/dto/request"
	"cart-service/internal/api/dto/response"
	"cart-service/internal/api/middleware"
	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/service"
)

// CartHandler handles cart HTTP endpoints.
type CartHandler struct {
	cartService *service.CartService
	logger      *zap.Logger
}

// NewCartHandler creates a new cart handler.
func NewCartHandler(cartService *service.CartService, logger *zap.Logger) *CartHandler {
	return &CartHandler{
		cartService: cartService,
		logger:      logger,
	}
}

// AddItem handles POST /api/v1/carts/items
func (h *CartHandler) AddItem(c *gin.Context) {
	ctx := c.Request.Context()
	requestID := middleware.GetRequestID(c)
	userID := middleware.GetUserID(c)

	var req request.AddItemRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("invalid request body",
			zap.String("request_id", requestID),
			zap.String("user_id", userID),
			zap.Error(err),
		)
		h.abortWithError(c, entity.ErrInvalidQuantity, requestID)
		return
	}

	if err := req.Validate(); err != nil {
		h.logger.Warn("validation failed",
			zap.String("request_id", requestID),
			zap.String("user_id", userID),
			zap.Error(err),
		)
		h.abortWithError(c, entity.ErrInvalidQuantity, requestID)
		return
	}

	result, err := h.cartService.AddItem(ctx, userID, &service.AddItemRequest{
		SkuID:    req.SkuID,
		Quantity: req.Quantity,
	})
	if err != nil {
		h.logger.Error("AddItem failed",
			zap.String("request_id", requestID),
			zap.String("user_id", userID),
			zap.String("sku_id", req.SkuID),
			zap.Error(err),
		)
		h.abortWithError(c, err, requestID)
		return
	}

	c.JSON(http.StatusOK, request.AddItemResponse{
		Success:   true,
		SkuID:     result.SkuID,
		Quantity:  result.Quantity,
		UpdatedAt: result.UpdatedAt,
		Message:   "Thêm vào giỏ hàng thành công",
	})
}

// GetCart handles GET /api/v1/carts/mine
func (h *CartHandler) GetCart(c *gin.Context) {
	ctx := c.Request.Context()
	requestID := middleware.GetRequestID(c)
	userID := middleware.GetUserID(c)

	cartView, err := h.cartService.GetCart(ctx, userID)
	if err != nil {
		h.logger.Error("GetCart failed",
			zap.String("request_id", requestID),
			zap.String("user_id", userID),
			zap.Error(err),
		)
		h.abortWithError(c, err, requestID)
		return
	}

	cartResp := response.FromCartView(cartView)
	c.JSON(http.StatusOK, cartResp)
}

// BatchDelete handles DELETE /api/v1/carts/items
func (h *CartHandler) BatchDelete(c *gin.Context) {
	ctx := c.Request.Context()
	requestID := middleware.GetRequestID(c)
	userID := middleware.GetUserID(c)

	var req request.BatchDeleteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("invalid request body",
			zap.String("request_id", requestID),
			zap.Error(err),
		)
		h.abortWithError(c, entity.ErrInvalidQuantity, requestID)
		return
	}

	if err := req.Validate(); err != nil {
		h.logger.Warn("validation failed",
			zap.String("request_id", requestID),
			zap.Error(err),
		)
		h.abortWithError(c, entity.ErrInvalidQuantity, requestID)
		return
	}

	count, err := h.cartService.BatchDelete(ctx, userID, req.SkuIDs, "CART_CLEARED")
	if err != nil {
		h.logger.Error("BatchDelete failed",
			zap.String("request_id", requestID),
			zap.String("user_id", userID),
			zap.Error(err),
		)
		h.abortWithError(c, err, requestID)
		return
	}

	c.JSON(http.StatusOK, response.BatchDeleteResponse{
		Success:      true,
		DeletedCount: count,
		Message:      "Xóa sản phẩm thành công",
	})
}

// abortWithError converts domain errors to HTTP responses.
func (h *CartHandler) abortWithError(c *gin.Context, err error, requestID string) {
	errResp := response.FromDomainError(err, requestID)
	statusCode := response.HTTPStatusCode(errResp.Code)
	c.AbortWithStatusJSON(statusCode, errResp)
}
