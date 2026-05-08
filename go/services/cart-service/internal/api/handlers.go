package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/constants"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
)

// CartHandler handles cart-related HTTP requests
type CartHandler struct {
	deps *Dependencies
}

// NewCartHandler creates a new CartHandler
func NewCartHandler(deps *Dependencies) *CartHandler {
	return &CartHandler{deps: deps}
}

// AddItemRequest represents the request to add/update an item
type AddItemRequest struct {
	SkuID    string `json:"sku_id"`
	Quantity int    `json:"quantity"`
}

// AddItemResponse represents the response after adding an item
type AddItemResponse struct {
	Success bool          `json:"success"`
	CartID  string       `json:"cart_id"`
	Item    *CartItemDTO `json:"item,omitempty"`
	Error   string       `json:"error,omitempty"`
}

// CartItemDTO represents an item in cart responses
type CartItemDTO struct {
	SkuID          string  `json:"sku_id"`
	Quantity       int     `json:"quantity"`
	ProductName    string  `json:"product_name"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
}

// AddItem handles POST /api/v1/carts/items
func (h *CartHandler) AddItem(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	logger := h.deps.Logger

	// 1. Extract user_id from header
	userID := r.Header.Get(constants.HeaderUserID)
	if userID == "" {
		h.writeError(w, http.StatusBadRequest, "X-User-Id header is required")
		return
	}

	// Parse and validate request
	var req AddItemRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}

	if req.SkuID == "" {
		h.writeError(w, http.StatusBadRequest, "sku_id is required")
		return
	}

	if req.Quantity <= 0 {
		h.writeError(w, http.StatusBadRequest, "quantity must be positive")
		return
	}

	// 2. Call Product Service gRPC to get product info
	productInfo, err := h.deps.ProductClient.GetProductSku(ctx, req.SkuID)
	if err != nil {
		logger.Warn("Failed to get product info",
			zap.String("sku_id", req.SkuID),
			zap.Error(err),
		)
		h.writeError(w, http.StatusBadGateway, "Failed to get product information")
		return
	}

	// 3. Validate product status
	if productInfo.SkuStatus != "ACTIVE" || productInfo.ProductStatus != "ACTIVE" {
		h.writeError(w, http.StatusBadRequest, "Product is not available")
		return
	}

	// 4. Validate inventory
	if productInfo.DisplayQuantity < req.Quantity {
		h.writeError(w, http.StatusBadRequest, "Insufficient inventory")
		return
	}

	// 5. Build cart payload
	cartKey := fmt.Sprintf("{cart:user:%s}", userID)
	eventTimeMs := time.Now().UnixMilli()
	currentTimeMsStr := strconv.FormatInt(eventTimeMs, 10)

	// Determine unit price
	unitPrice := productInfo.OriginalPrice
	if productInfo.PromotionalPrice > 0 {
		unitPrice = productInfo.PromotionalPrice
	}

	// Build variant info string
	variantInfo := ""
	if productInfo.VariantInfo != "" {
		variantInfo = productInfo.VariantInfo
	}

	// Build item payload
	itemPayload := map[string]interface{}{
		"sku_id":           req.SkuID,
		"product_id":       productInfo.ProductID,
		"quantity":         req.Quantity,
		"unit_price_at_add": unitPrice,
		"product_name":      productInfo.ProductName,
		"variant_info":     variantInfo,
		"image_url":        productInfo.ImageURL,
		"is_available":     productInfo.IsAvailable,
		"added_at":         currentTimeMsStr,
		"updated_at":       currentTimeMsStr,
	}

	payloadJSON, err := json.Marshal(itemPayload)
	if err != nil {
		logger.Error("Failed to marshal item payload", zap.Error(err))
		h.writeError(w, http.StatusInternalServerError, "Failed to process item")
		return
	}

	// 6. Get outbox stream key for intent registration
	outboxKey := cartKey + constants.KeySuffixOutbox

	// 7. Pre-register intent
	if err := h.deps.IntentIndex.PreRegisterIntent(ctx, outboxKey); err != nil {
		logger.Error("Failed to pre-register intent", zap.Error(err))
		h.writeError(w, http.StatusInternalServerError, "Failed to register intent")
		return
	}

	// 8. Execute Add/Update Lua script
	resultJSON, streamID, err := h.deps.CartOps.AddOrUpdateItem(
		ctx,
		userID,
		req.SkuID,
		string(payloadJSON),
		int64(req.Quantity),
		eventTimeMs,
	)

	// Check for Lua errors
	if err != nil {
		// Clear intent on failure
		_ = h.deps.IntentIndex.ClearIntent(ctx, outboxKey)

		switch err {
		case ErrCartFull:
			h.writeError(w, http.StatusUnprocessableEntity, "Cart is full (maximum 50 unique SKUs)")
			return
		case ErrStepInvalid:
			h.writeError(w, http.StatusBadRequest, "Invalid quantity step")
			return
		case ErrQuantityInvalid:
			h.writeError(w, http.StatusBadRequest, "Quantity would be invalid")
			return
		case ErrCannotDecrementNonExisting:
			h.writeError(w, http.StatusBadRequest, "Cannot decrement non-existing item")
			return
		default:
			logger.Error("Add/Update failed", zap.Error(err))
			h.writeError(w, http.StatusInternalServerError, "Failed to add item to cart")
			return
		}
	}

	// 9. Invalidate negative cache
	if err := h.deps.NegativeCache.Invalidate(ctx, userID); err != nil {
		logger.Warn("Failed to invalidate negative cache", zap.Error(err))
	}

	// 10. Mark active and notify (publish path)
	if err := h.deps.IntentIndex.MarkActive(ctx, outboxKey); err != nil {
		logger.Error("Failed to mark active", zap.Error(err))
		// Continue - intent will be recovered by recovery worker
	}

	// 11. Clear intent
	if err := h.deps.IntentIndex.ClearIntent(ctx, outboxKey); err != nil {
		logger.Warn("Failed to clear intent", zap.Error(err))
		// Intent will be cleaned up by recovery worker
	}

	// 12. Parse result and build response
	var resultItem map[string]interface{}
	if err := json.Unmarshal([]byte(resultJSON), &resultItem); err != nil {
		logger.Warn("Failed to parse result JSON", zap.Error(err))
	}

	// Build response
	quantity := 0
	if q, ok := resultItem["quantity"].(float64); ok {
		quantity = int(q)
	}

	response := AddItemResponse{
		Success: true,
		CartID:  cartKey,
		Item: &CartItemDTO{
			SkuID:          req.SkuID,
			Quantity:       quantity,
			ProductName:    productInfo.ProductName,
			UnitPriceAtAdd: unitPrice,
		},
	}

	logger.Info("Item added to cart",
		zap.String("cart_id", cartKey),
		zap.String("sku_id", req.SkuID),
		zap.Int("quantity", quantity),
		zap.String("stream_id", streamID),
	)

	h.writeJSON(w, http.StatusOK, response)
}

// GetCart handles GET /api/v1/carts/mine
func (h *CartHandler) GetCart(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	logger := h.deps.Logger

	// 1. Extract user_id from header
	userID := r.Header.Get(constants.HeaderUserID)
	if userID == "" {
		h.writeError(w, http.StatusBadRequest, "X-User-Id header is required")
		return
	}

	cartKey := fmt.Sprintf("{cart:user:%s}", userID)

	// 2. Check negative cache
	hasNegCache, err := h.deps.NegativeCache.HasCachedResult(ctx, userID)
	if err != nil {
		logger.Warn("Failed to check negative cache", zap.Error(err))
	}
	if hasNegCache {
		logger.Debug("Negative cache hit", zap.String("user_id", userID))
		h.writeJSON(w, http.StatusOK, EmptyCartResponse(cartKey))
		return
	}

	// 3. Try Cache Hit path first
	cartData, err := h.deps.CartOps.GetCart(ctx, userID)
	if err != nil {
		logger.Error("Failed to get cart from Redis", zap.Error(err))
		h.writeError(w, http.StatusInternalServerError, "Failed to get cart")
		return
	}

	// 4. Cache Hit: data exists
	if len(cartData) > 0 {
		h.handleCacheHit(ctx, w, r, userID, cartKey, cartData)
		return
	}

	// 5. Cache Miss: need to fetch from DB
	logger.Debug("Cache miss, falling back to DB", zap.String("user_id", userID))
	h.handleCacheMiss(ctx, w, r, userID, cartKey)
}

// handleCacheHit handles the cache hit path
func (h *CartHandler) handleCacheHit(ctx context.Context, w http.ResponseWriter, r *http.Request, userID, cartKey string, cartData map[string]string) {
	logger := h.deps.Logger

	// Collect SKU IDs
	skuIDs := make([]string, 0, len(cartData))
	for skuID := range cartData {
		skuIDs = append(skuIDs, skuID)
	}

	// Call Product Service with Circuit Breaker
	grpcResult := h.callProductServiceWithCircuitBreaker(ctx, skuIDs)

	// Build response
	response := h.buildCartResponse(cartData, grpcResult)

	// Write response
	h.writeJSON(w, http.StatusOK, response)
	logger.Info("Cart retrieved (cache hit)",
		zap.String("cart_id", cartKey),
		zap.Int("item_count", len(cartData)),
	)
}

// handleCacheMiss handles the cache miss path
func (h *CartHandler) handleCacheMiss(ctx context.Context, w http.ResponseWriter, r *http.Request, userID, cartKey string) {
	logger := h.deps.Logger

	// Check cart status in DB
	status, err := h.deps.DB.GetCartStatus(ctx, cartKey)
	if err != nil {
		logger.Error("Failed to get cart status from DB", zap.Error(err))
		h.writeError(w, http.StatusInternalServerError, "Failed to get cart")
		return
	}

	// If DELETED or CHECKED_OUT, return empty cart
	if status == constants.CartStatusDeleted || status == constants.CartStatusCheckedOut {
		logger.Debug("Cart status is terminal", zap.String("cart_id", cartKey), zap.String("status", status))
		h.writeJSON(w, http.StatusOK, EmptyCartResponse(cartKey))
		return
	}

	// Load items from DB
	items, err := h.deps.DB.GetCartItems(ctx, cartKey)
	if err != nil {
		logger.Error("Failed to get cart items from DB", zap.Error(err))
		h.writeError(w, http.StatusInternalServerError, "Failed to get cart")
		return
	}

	// If DB empty, set negative cache and return empty
	if len(items) == 0 {
		if err := h.deps.NegativeCache.SetNotFound(ctx, userID); err != nil {
			logger.Warn("Failed to set negative cache", zap.Error(err))
		}
		h.writeJSON(w, http.StatusOK, EmptyCartResponse(cartKey))
		return
	}

	// Call Product Service with Circuit Breaker
	skuIDs := make([]string, len(items))
	for i, item := range items {
		skuIDs[i] = item.SkuID
	}
	grpcResult := h.callProductServiceWithCircuitBreaker(ctx, skuIDs)

	// Merge data for rehydration
	rehydrateItems, mergedData := h.mergeItemsFromDB(items, grpcResult)

	// Execute Silent Rehydrate
	if len(rehydrateItems) > 0 {
		if err := h.deps.CartOps.SilentRehydrate(ctx, userID, rehydrateItems); err != nil {
			logger.Warn("Silent rehydrate failed", zap.Error(err))
			// Continue - we can still return the merged data
		}
	}

	// Build response from merged data
	response := h.buildCartResponseFromMerged(mergedData, grpcResult)

	logger.Info("Cart retrieved (cache miss with rehydration)",
		zap.String("cart_id", cartKey),
		zap.Int("item_count", len(items)),
		zap.Bool("price_degraded", response.IsPriceDegraded),
	)

	h.writeJSON(w, http.StatusOK, response)
}

// callProductServiceWithCircuitBreaker calls Product Service with Circuit Breaker
func (h *CartHandler) callProductServiceWithCircuitBreaker(ctx context.Context, skuIDs []string) *GrpcResult {
	logger := h.deps.Logger

	if len(skuIDs) == 0 {
		return &GrpcResult{Success: true, Products: make(map[string]*ProductInfo)}
	}

	// Create context with timeout
	timeoutStr := h.deps.Config.ProductService.Timeout
	timeout := config.ParseDuration(timeoutStr)
	if timeout == 0 {
		timeout = 200 * time.Millisecond
	}

	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	// Call Product Service
	productResp, err := h.deps.ProductClient.GetBatchProductSkus(ctx, skuIDs)
	if err != nil {
		logger.Warn("Product Service call failed",
			zap.Strings("sku_ids", skuIDs),
			zap.Error(err),
		)
		return &GrpcResult{Success: false, Error: err}
	}

	return &GrpcResult{
		Success:   true,
		Products: productResp.Products,
	}
}

// mergeItemsFromDB merges DB items with Product Service data
func (h *CartHandler) mergeItemsFromDB(items []CartItemRecord, grpcResult *GrpcResult) ([]entity.RehydrateItem, map[string]*MergedCartItem) {
	rehydrateItems := make([]entity.RehydrateItem, 0, len(items))
	mergedData := make(map[string]*MergedCartItem)

	for _, item := range items {
		// Parse product snapshot
		var snapshot map[string]interface{}
		if err := json.Unmarshal([]byte(item.ProductSnapshot), &snapshot); err != nil {
			continue
		}

		// Inject unit_price_at_add
		snapshot["unit_price_at_add"] = item.UnitPriceAtAdd

		// Merge is_available from Product Service if available
		if grpcResult.Success && grpcResult.Products != nil {
			if product, ok := grpcResult.Products[item.SkuID]; ok {
				// If product is unavailable (banned/deleted), update
				if !product.IsAvailable {
					snapshot["is_available"] = false
				}
				// Keep DB value if product is available (DB might be stale but not unavailable)
			}
		}

		// Marshal back to JSON for rehydration
		mergedJSON, _ := json.Marshal(snapshot)

		rehydrateItems = append(rehydrateItems, entity.RehydrateItem{
			SkuID: item.SkuID,
			Value: string(mergedJSON),
		})

		// Create merged data for response
		merged := &MergedCartItem{
			SkuID:          item.SkuID,
			Quantity:       item.Quantity,
			ProductName:    getStringFromMap(snapshot, "product_name"),
			VariantInfo:    getStringFromMap(snapshot, "variant_info"),
			ImageURL:       getStringFromMap(snapshot, "image_url"),
			UnitPriceAtAdd: item.UnitPriceAtAdd,
			IsAvailable:    getBoolFromMap(snapshot, "is_available"),
			AddedAt:        getStringFromMap(snapshot, "added_at"),
			UpdatedAt:       getStringFromMap(snapshot, "updated_at"),
		}
		mergedData[item.SkuID] = merged
	}

	return rehydrateItems, mergedData
}

// buildCartResponse builds response from Redis data
func (h *CartHandler) buildCartResponse(cartData map[string]string, grpcResult *GrpcResult) *GetCartResponse {
	response := &GetCartResponse{
		Items:            []CartItemGroup{},
		IsPriceDegraded: !grpcResult.Success,
	}

	// Parse items and group by platform
	itemGroups := make(map[string]*CartItemGroup)
	totalItems := 0
	var maxUpdatedAt int64 = 0

	for skuID, jsonStr := range cartData {
		var item map[string]interface{}
		if err := json.Unmarshal([]byte(jsonStr), &item); err != nil {
			continue
		}

		// Merge with product service data
		currentPrice := getFloat64FromMap(item, "unit_price_at_add")
		isAvailable := getBoolFromMap(item, "is_available")

		if grpcResult.Success && grpcResult.Products != nil {
			if product, ok := grpcResult.Products[skuID]; ok {
				currentPrice = product.CurrentPrice
				if !product.IsAvailable {
					isAvailable = false
					// Update Redis with is_available=false (exception to read-only rule)
					_ = h.deps.CartOps.UpdateItemAvailability(context.Background(), "", skuID, false)
				}
			}
		}

		updatedAt := getStringFromMap(item, "updated_at")
		updatedAtMs := parseTimestamp(updatedAt)

		itemResp := CartItemResponse{
			SkuID:        skuID,
			ProductName:  getStringFromMap(item, "product_name"),
			VariantInfo:  getStringFromMap(item, "variant_info"),
			ImageURL:     getStringFromMap(item, "image_url"),
			Quantity:     getIntFromMap(item, "quantity"),
			UnitPriceAtAdd: getFloat64FromMap(item, "unit_price_at_add"),
			CurrentPrice: currentPrice,
			IsAvailable: isAvailable,
			AddedAt:     getStringFromMap(item, "added_at"),
			UpdatedAt:   updatedAt,
		}

		// Use product_id as group key (default to "default" if not present)
		platformID := getStringFromMap(item, "product_id")
		if platformID == "" {
			platformID = "default"
		}

		if _, ok := itemGroups[platformID]; !ok {
			itemGroups[platformID] = &CartItemGroup{
				Items: []CartItemResponse{},
			}
		}
		itemGroups[platformID].Items = append(itemGroups[platformID].Items, itemResp)

		if updatedAtMs > maxUpdatedAt {
			maxUpdatedAt = updatedAtMs
		}
		totalItems += itemResp.Quantity
	}

	// Set latest_item_at for each group
	for _, group := range itemGroups {
		group.LatestItemAt = maxUpdatedAt
		response.Items = append(response.Items, *group)
	}

	// Sort items within each group by added_at DESC
	for i := range response.Items {
		sortItemsByAddedAt(&response.Items[i].Items)
	}

	// Sort groups by latest_item_at DESC (already done by using single maxUpdatedAt)

	response.TotalItems = totalItems
	response.TotalUniqueSKUs = len(cartData)

	return response
}

// buildCartResponseFromMerged builds response from merged DB data
func (h *CartHandler) buildCartResponseFromMerged(mergedData map[string]*MergedCartItem, grpcResult *GrpcResult) *GetCartResponse {
	response := &GetCartResponse{
		Items:            []CartItemGroup{},
		IsPriceDegraded: !grpcResult.Success,
	}

	totalItems := 0
	maxUpdatedAt := int64(0)

	// Group items (using default group since we don't have platform_id)
	group := &CartItemGroup{
		Items: []CartItemResponse{},
	}

	for _, item := range mergedData {
		currentPrice := item.UnitPriceAtAdd
		isAvailable := item.IsAvailable

		if grpcResult.Success && grpcResult.Products != nil {
			if product, ok := grpcResult.Products[item.SkuID]; ok {
				currentPrice = product.CurrentPrice
				if !product.IsAvailable {
					isAvailable = false
				}
			}
		}

		updatedAtMs := parseTimestamp(item.UpdatedAt)
		if updatedAtMs > maxUpdatedAt {
			maxUpdatedAt = updatedAtMs
		}

		itemResp := CartItemResponse{
			SkuID:          item.SkuID,
			ProductName:    item.ProductName,
			VariantInfo:    item.VariantInfo,
			ImageURL:       item.ImageURL,
			Quantity:       item.Quantity,
			UnitPriceAtAdd: item.UnitPriceAtAdd,
			CurrentPrice:   currentPrice,
			IsAvailable:    isAvailable,
			AddedAt:        item.AddedAt,
			UpdatedAt:       item.UpdatedAt,
		}

		group.Items = append(group.Items, itemResp)
		totalItems += item.Quantity
	}

	group.LatestItemAt = maxUpdatedAt
	sortItemsByAddedAt(&group.Items)
	response.Items = append(response.Items, *group)

	response.TotalItems = totalItems
	response.TotalUniqueSKUs = len(mergedData)

	return response
}

// MergedCartItem represents an item merged from DB and Product Service
type MergedCartItem struct {
	SkuID          string
	Quantity       int
	ProductName    string
	VariantInfo    string
	ImageURL       string
	UnitPriceAtAdd float64
	IsAvailable    bool
	AddedAt        string
	UpdatedAt      string
}

// GetCartResponse represents the response for getting cart
type GetCartResponse struct {
	CartID          string           `json:"cart_id"`
	Items           []CartItemGroup `json:"items"`
	IsPriceDegraded bool             `json:"is_price_degraded"`
	TotalItems      int              `json:"total_items"`
	TotalUniqueSKUs int             `json:"total_unique_skus"`
}

// CartItemGroup groups items by latest_item_at
type CartItemGroup struct {
	LatestItemAt int64              `json:"latest_item_at"`
	Items       []CartItemResponse `json:"items"`
}

// CartItemResponse is the response format for a single cart item
type CartItemResponse struct {
	SkuID          string  `json:"sku_id"`
	ProductName    string  `json:"product_name"`
	VariantInfo   string  `json:"variant_info"`
	ImageURL      string  `json:"image_url"`
	Quantity      int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	CurrentPrice float64 `json:"current_price,omitempty"`
	IsAvailable  bool    `json:"is_available"`
	AddedAt      string  `json:"added_at"`
	UpdatedAt    string  `json:"updated_at"`
}

// DeleteItemsRequest represents the request to delete items
type DeleteItemsRequest struct {
	CartID string   `json:"cart_id"`
	SkuIDs []string `json:"sku_ids"`
}

// DeleteItemsResponse represents the response after deleting items
type DeleteItemsResponse struct {
	Success      bool  `json:"success"`
	DeletedCount int64 `json:"deleted_count"`
	Error        string `json:"error,omitempty"`
}

// DeleteItems handles DELETE /api/v1/carts/items
func (h *CartHandler) DeleteItems(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	logger := h.deps.Logger

	// 1. Extract user_id from header
	userID := r.Header.Get(constants.HeaderUserID)
	if userID == "" {
		h.writeError(w, http.StatusBadRequest, "X-User-Id header is required")
		return
	}

	// Parse and validate request
	var req DeleteItemsRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.writeError(w, http.StatusBadRequest, "Invalid request body")
		return
	}

	if req.CartID == "" {
		h.writeError(w, http.StatusBadRequest, "cart_id is required")
		return
	}

	if len(req.SkuIDs) == 0 {
		h.writeError(w, http.StatusBadRequest, "sku_ids is required and must not be empty")
		return
	}

	// Validate cart_id format
	keyBuilder := NewCartKeyBuilder()
	if !keyBuilder.IsValidCartKey(req.CartID) {
		h.writeError(w, http.StatusBadRequest, "Invalid cart_id format")
		return
	}

	// Extract user_id from cart_id and verify it matches
	extractedUserID, err := keyBuilder.ExtractUserIDFromCartID(req.CartID)
	if err != nil {
		h.writeError(w, http.StatusBadRequest, "Invalid cart_id format")
		return
	}

	if extractedUserID != userID {
		h.writeError(w, http.StatusForbidden, "Cart does not belong to user")
		return
	}

	// Get outbox stream key
	outboxKey := req.CartID + constants.KeySuffixOutbox
	eventTimeMs := time.Now().UnixMilli()

	// Pre-register intent
	if err := h.deps.IntentIndex.PreRegisterIntent(ctx, outboxKey); err != nil {
		logger.Error("Failed to pre-register intent", zap.Error(err))
		h.writeError(w, http.StatusInternalServerError, "Failed to register intent")
		return
	}

	// Execute Batch Delete Lua script
	deletedCount, _, err := h.deps.CartOps.BatchDeleteItems(
		ctx,
		userID,
		req.SkuIDs,
		constants.EventTypeCartCleared, // Default to CART_CLEARED for user-initiated delete
		eventTimeMs,
	)

	if err != nil {
		// Clear intent on failure
		_ = h.deps.IntentIndex.ClearIntent(ctx, outboxKey)
		logger.Error("Batch delete failed", zap.Error(err))
		h.writeError(w, http.StatusInternalServerError, "Failed to delete items")
		return
	}

	// Invalidate negative cache
	if err := h.deps.NegativeCache.Invalidate(ctx, userID); err != nil {
		logger.Warn("Failed to invalidate negative cache", zap.Error(err))
	}

	// Mark active and notify
	if err := h.deps.IntentIndex.MarkActive(ctx, outboxKey); err != nil {
		logger.Error("Failed to mark active", zap.Error(err))
	}

	// Clear intent
	if err := h.deps.IntentIndex.ClearIntent(ctx, outboxKey); err != nil {
		logger.Warn("Failed to clear intent", zap.Error(err))
	}

	logger.Info("Items deleted from cart",
		zap.String("cart_id", req.CartID),
		zap.Int64("deleted_count", deletedCount),
		zap.Strings("sku_ids", req.SkuIDs),
	)

	h.writeJSON(w, http.StatusOK, DeleteItemsResponse{
		Success:      true,
		DeletedCount: deletedCount,
	})
}

// EmptyCartResponse returns an empty cart response
func EmptyCartResponse(cartID string) *GetCartResponse {
	return &GetCartResponse{
		CartID:          cartID,
		Items:           []CartItemGroup{},
		IsPriceDegraded: false,
		TotalItems:      0,
		TotalUniqueSKUs: 0,
	}
}

// writeJSON writes JSON response
func (h *CartHandler) writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(data); err != nil {
		h.deps.Logger.Error("Failed to encode JSON response", zap.Error(err))
	}
}

// writeError writes error response
func (h *CartHandler) writeError(w http.ResponseWriter, status int, message string) {
	h.writeJSON(w, status, map[string]string{"error": message})
}

// Helper functions for parsing map data
func getStringFromMap(m map[string]interface{}, key string) string {
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

func getBoolFromMap(m map[string]interface{}, key string) bool {
	if v, ok := m[key].(bool); ok {
		return v
	}
	return false
}

func getFloat64FromMap(m map[string]interface{}, key string) float64 {
	if v, ok := m[key].(float64); ok {
		return v
	}
	if v, ok := m[key].(int); ok {
		return float64(v)
	}
	return 0
}

func getIntFromMap(m map[string]interface{}, key string) int {
	if v, ok := m[key].(float64); ok {
		return int(v)
	}
	if v, ok := m[key].(int); ok {
		return v
	}
	return 0
}

func parseTimestamp(ts string) int64 {
	if ts == "" {
		return 0
	}
	v, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return 0
	}
	return v
}

func sortItemsByAddedAt(items *[]CartItemResponse) {
	// Simple bubble sort for small lists
	arr := *items
	for i := 0; i < len(arr)-1; i++ {
		for j := 0; j < len(arr)-i-1; j++ {
			t1 := parseTimestamp(arr[j].AddedAt)
			t2 := parseTimestamp(arr[j+1].AddedAt)
			if t1 < t2 {
				arr[j], arr[j+1] = arr[j+1], arr[j]
			}
		}
	}
}

// Cart operation errors (from redis package)
var (
	ErrCartFull                   = fmt.Errorf("cart is full")
	ErrStepInvalid                = fmt.Errorf("step must be non-zero")
	ErrQuantityInvalid            = fmt.Errorf("quantity would be invalid")
	ErrCannotDecrementNonExisting = fmt.Errorf("cannot decrement non-existing item")
)
