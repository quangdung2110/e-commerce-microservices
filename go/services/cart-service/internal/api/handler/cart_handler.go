package handler

import (
	"encoding/json"
	"net/http"

	"go.uber.org/zap"

	"github.com/ecommerce-platform/go/services/cart-service/internal/api/dto/request"
	"github.com/ecommerce-platform/go/services/cart-service/internal/api/dto/response"
	"github.com/ecommerce-platform/go/services/cart-service/internal/api/middleware"
	"github.com/ecommerce-platform/go/services/cart-service/internal/common/constants"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/service"
)

// CartHandler handles cart-related HTTP requests
type CartHandler struct {
	cartService *service.CartService
	logger     *zap.Logger
}

// NewCartHandler creates a new CartHandler
func NewCartHandler(cartService *service.CartService, logger *zap.Logger) *CartHandler {
	return &CartHandler{
		cartService: cartService,
		logger:     logger,
	}
}

// AddItem handles POST /api/v1/carts/items
func (h *CartHandler) AddItem(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Extract user_id from header
	userID := r.Header.Get(constants.HeaderUserID)
	if userID == "" {
		middleware.Error(w, r, http.StatusBadRequest, "X-User-Id header is required")
		return
	}

	// Parse request
	var req request.AddItemRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		middleware.Error(w, r, http.StatusBadRequest, "Invalid request body")
		return
	}

	// Validate request
	if err := req.Validate(); err != nil {
		middleware.Error(w, r, http.StatusBadRequest, err.Error())
		return
	}

	// Execute business logic
	result, err := h.cartService.AddItem(ctx, userID, req.SkuID, req.Quantity)
	if err != nil {
		h.logger.Error("Failed to add item to cart",
			zap.String("user_id", userID),
			zap.String("sku_id", req.SkuID),
			zap.Error(err),
		)
		middleware.Error(w, r, http.StatusInternalServerError, "Failed to add item to cart")
		return
	}

	// Handle business errors
	if !result.Success {
		statusCode := http.StatusBadRequest
		switch result.ErrorCode {
		case "PRODUCT_UNAVAILABLE":
			statusCode = http.StatusBadRequest
		case "INSUFFICIENT_INVENTORY":
			statusCode = http.StatusBadRequest
		case "CART_FULL":
			statusCode = http.StatusUnprocessableEntity
		}
		middleware.Error(w, r, statusCode, result.Error.Error())
		return
	}

	// Build response
	resp := response.NewAddItemResponse(result)

	h.logger.Info("Item added to cart",
		zap.String("cart_id", result.CartID),
		zap.String("sku_id", req.SkuID),
		zap.Int("quantity", result.Item.Quantity),
		zap.String("stream_id", result.StreamID),
	)

	middleware.JSON(w, r, http.StatusOK, resp)
}

// GetCart handles GET /api/v1/carts/mine
func (h *CartHandler) GetCart(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Extract user_id from header
	userID := r.Header.Get(constants.HeaderUserID)
	if userID == "" {
		middleware.Error(w, r, http.StatusBadRequest, "X-User-Id header is required")
		return
	}

	// Execute business logic
	result, err := h.cartService.GetCart(ctx, userID)
	if err != nil {
		h.logger.Error("Failed to get cart",
			zap.String("user_id", userID),
			zap.Error(err),
		)
		middleware.Error(w, r, http.StatusInternalServerError, "Failed to get cart")
		return
	}

	// Build response
	resp := response.NewGetCartResponse(result)

	h.logger.Info("Cart retrieved",
		zap.String("cart_id", result.CartID),
		zap.Int("item_count", result.TotalUniqueSKUs),
		zap.Bool("is_price_degraded", result.IsPriceDegraded),
	)

	middleware.JSON(w, r, http.StatusOK, resp)
}

// DeleteItems handles DELETE /api/v1/carts/items
func (h *CartHandler) DeleteItems(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Extract user_id from header
	userID := r.Header.Get(constants.HeaderUserID)
	if userID == "" {
		middleware.Error(w, r, http.StatusBadRequest, "X-User-Id header is required")
		return
	}

	// Parse request
	var req request.DeleteItemsRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		middleware.Error(w, r, http.StatusBadRequest, "Invalid request body")
		return
	}

	// Validate request
	if err := req.Validate(); err != nil {
		middleware.Error(w, r, http.StatusBadRequest, err.Error())
		return
	}

	// Execute business logic
	result, err := h.cartService.DeleteItems(ctx, userID, req.CartID, req.SkuIDs)
	if err != nil {
		h.logger.Error("Failed to delete items from cart",
			zap.String("cart_id", req.CartID),
			zap.Error(err),
		)
		middleware.Error(w, r, http.StatusInternalServerError, "Failed to delete items")
		return
	}

	// Handle business errors
	if !result.Success {
		middleware.Error(w, r, http.StatusBadRequest, result.Error.Error())
		return
	}

	// Build response
	resp := response.NewDeleteItemsResponse(result)

	h.logger.Info("Items deleted from cart",
		zap.String("cart_id", req.CartID),
		zap.Int64("deleted_count", result.DeletedCount),
		zap.Strings("sku_ids", req.SkuIDs),
	)

	middleware.JSON(w, r, http.StatusOK, resp)
}
