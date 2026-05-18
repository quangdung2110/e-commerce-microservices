package service

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"time"

	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/entity"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/event"
	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/port"
)

// CartService handles cart business logic
type CartService struct {
	cartRepo    port.CartRepository
	productSvc  port.ProductServiceClient
	negCache    port.NegativeCacheManager
	intentIndex port.IntentIndexManager
	keyBuilder  port.KeyBuilder
	db          port.PostgresDB
}

// NewCartService creates a new CartService
func NewCartService(
	cartRepo port.CartRepository,
	productSvc port.ProductServiceClient,
	negCache port.NegativeCacheManager,
	intentIndex port.IntentIndexManager,
	keyBuilder port.KeyBuilder,
	db port.PostgresDB,
) *CartService {
	return &CartService{
		cartRepo:    cartRepo,
		productSvc:  productSvc,
		negCache:    negCache,
		intentIndex: intentIndex,
		keyBuilder:  keyBuilder,
		db:          db,
	}
}

// AddItemResult represents the result of adding an item to cart
type AddItemResult struct {
	Success   bool
	CartID    string
	Item      *entity.CartItemPayload
	StreamID  string
	Error     error
	ErrorCode string
}

// AddItem adds or updates an item in the cart
func (s *CartService) AddItem(ctx context.Context, userID, skuID string, quantity int) (*AddItemResult, error) {
	// 1. Get product info from Product Service
	productInfo, err := s.productSvc.GetProductSku(ctx, skuID)
	if err != nil {
		return &AddItemResult{
			Success:   false,
			Error:     err,
			ErrorCode: "PRODUCT_SERVICE_ERROR",
		}, err
	}

	// 2. Validate product status
	if productInfo.SkuStatus != "ACTIVE" || productInfo.ProductStatus != "ACTIVE" {
		return &AddItemResult{
			Success:   false,
			Error:     fmt.Errorf("product is not available"),
			ErrorCode: "PRODUCT_UNAVAILABLE",
		}, nil
	}

	// 3. Validate inventory
	if productInfo.DisplayQuantity < quantity {
		return &AddItemResult{
			Success:   false,
			Error:     fmt.Errorf("insufficient inventory"),
			ErrorCode: "INSUFFICIENT_INVENTORY",
		}, nil
	}

	// 4. Build cart payload
	cartKey, _ := s.keyBuilder.BuildCartKey(userID)
	outboxKey, _ := s.keyBuilder.BuildOutboxStreamKey(userID)
	eventTimeMs := time.Now().UnixMilli()

	// Determine unit price
	unitPrice := productInfo.OriginalPrice
	if productInfo.PromotionalPrice > 0 {
		unitPrice = productInfo.PromotionalPrice
	}

	// Build item payload
	itemPayload := map[string]interface{}{
		"sku_id":           skuID,
		"product_id":       productInfo.ProductID,
		"quantity":         quantity,
		"unit_price_at_add": unitPrice,
		"product_name":      productInfo.ProductName,
		"variant_info":     productInfo.VariantInfo,
		"image_url":        productInfo.ImageURL,
		"is_available":     productInfo.IsAvailable,
		"added_at":         strconv.FormatInt(eventTimeMs, 10),
		"updated_at":       strconv.FormatInt(eventTimeMs, 10),
	}

	payloadJSON, err := json.Marshal(itemPayload)
	if err != nil {
		return &AddItemResult{
			Success:   false,
			Error:     err,
			ErrorCode: "INTERNAL_ERROR",
		}, err
	}

	// 5. Pre-register intent
	if err := s.intentIndex.PreRegisterIntent(ctx, outboxKey); err != nil {
		return &AddItemResult{
			Success:   false,
			Error:     err,
			ErrorCode: "INTENT_REGISTRATION_ERROR",
		}, err
	}

	// 6. Execute Add/Update Lua script
	resultJSON, streamID, err := s.cartRepo.AddOrUpdateItem(
		ctx,
		userID,
		skuID,
		string(payloadJSON),
		int64(quantity),
		eventTimeMs,
	)

	if err != nil {
		_ = s.intentIndex.ClearIntent(ctx, outboxKey)

		// Map Lua errors to result
		errCode := "INTERNAL_ERROR"
		switch err {
		case port.ErrCartFull:
			errCode = "CART_FULL"
		case port.ErrStepInvalid:
			errCode = "INVALID_STEP"
		case port.ErrQuantityInvalid:
			errCode = "INVALID_QUANTITY"
		case port.ErrCannotDecrementNonExisting:
			errCode = "ITEM_NOT_FOUND"
		}

		return &AddItemResult{
			Success:   false,
			Error:     err,
			ErrorCode: errCode,
		}, nil
	}

	// 7. Invalidate negative cache
	_ = s.negCache.Invalidate(ctx, userID)

	// 8. Publish path
	_ = s.intentIndex.MarkActive(ctx, outboxKey)
	_ = s.intentIndex.ClearIntent(ctx, outboxKey)

	// 9. Parse result
	var resultItem map[string]interface{}
	_ = json.Unmarshal([]byte(resultJSON), &resultItem)

	resultQty := 0
	if q, ok := resultItem["quantity"].(float64); ok {
		resultQty = int(q)
	}

	return &AddItemResult{
		Success:  true,
		CartID:   cartKey,
		StreamID: streamID,
		Item: &entity.CartItemPayload{
			SkuID:          skuID,
			ProductID:      productInfo.ProductID,
			Quantity:       resultQty,
			UnitPriceAtAdd: unitPrice,
			ProductName:    productInfo.ProductName,
			VariantInfo:   productInfo.VariantInfo,
			ImageURL:      productInfo.ImageURL,
			IsAvailable:   productInfo.IsAvailable,
		},
	}, nil
}

// GetCartResult represents the result of getting a cart
type GetCartResult struct {
	CartID          string
	Items           []entity.CartItemGroup
	IsPriceDegraded bool
	TotalItems      int
	TotalUniqueSKUs int
	Error          error
}

// GetCart retrieves the cart for a user
func (s *CartService) GetCart(ctx context.Context, userID string) (*GetCartResult, error) {
	cartKey, _ := s.keyBuilder.BuildCartKey(userID)

	// 1. Check negative cache
	hasNegCache, _ := s.negCache.HasCachedResult(ctx, userID)
	if hasNegCache {
		return &GetCartResult{
			CartID:          cartKey,
			Items:           []entity.CartItemGroup{},
			TotalItems:      0,
			TotalUniqueSKUs: 0,
		}, nil
	}

	// 2. Try Cache Hit path first
	cartData, err := s.cartRepo.GetCart(ctx, userID)
	if err != nil {
		return &GetCartResult{Error: err}, err
	}

	// 3. Cache Hit: data exists
	if len(cartData) > 0 {
		return s.handleCacheHit(ctx, cartKey, cartData)
	}

	// 4. Cache Miss: need to fetch from DB
	return s.handleCacheMiss(ctx, userID, cartKey)
}

// handleCacheHit handles the cache hit path
func (s *CartService) handleCacheHit(ctx context.Context, cartKey string, cartData map[string]string) (*GetCartResult, error) {
	// Collect SKU IDs
	skuIDs := make([]string, 0, len(cartData))
	for skuID := range cartData {
		skuIDs = append(skuIDs, skuID)
	}

	// Call Product Service
	grpcResult := s.callProductServiceWithCircuitBreaker(ctx, skuIDs)

	// Build response
	return s.buildCartResponse(cartKey, cartData, grpcResult), nil
}

// handleCacheMiss handles the cache miss path
func (s *CartService) handleCacheMiss(ctx context.Context, userID, cartKey string) (*GetCartResult, error) {
	// Check cart status in DB
	status, err := s.db.GetCartStatus(ctx, cartKey)
	if err != nil {
		return &GetCartResult{Error: err}, err
	}

	// If DELETED or CHECKED_OUT, return empty cart
	if status == "DELETED" || status == "CHECKED_OUT" {
		return &GetCartResult{
			CartID:          cartKey,
			Items:           []entity.CartItemGroup{},
			TotalItems:      0,
			TotalUniqueSKUs: 0,
		}, nil
	}

	// Load items from DB
	items, err := s.db.GetCartItems(ctx, cartKey)
	if err != nil {
		return &GetCartResult{Error: err}, err
	}

	// If DB empty, set negative cache and return empty
	if len(items) == 0 {
		_ = s.negCache.SetNotFound(ctx, userID)
		return &GetCartResult{
			CartID:          cartKey,
			Items:           []entity.CartItemGroup{},
			TotalItems:      0,
			TotalUniqueSKUs: 0,
		}, nil
	}

	// Call Product Service
	skuIDs := make([]string, len(items))
	for i, item := range items {
		skuIDs[i] = item.SkuID
	}
	grpcResult := s.callProductServiceWithCircuitBreaker(ctx, skuIDs)

	// Merge data for rehydration
	rehydrateItems, mergedData := s.mergeItemsFromDB(items, grpcResult)

	// Execute Silent Rehydrate
	if len(rehydrateItems) > 0 {
		_ = s.cartRepo.SilentRehydrate(ctx, userID, rehydrateItems)
	}

	// Build response
	return s.buildCartResponseFromMerged(cartKey, mergedData, grpcResult), nil
}

// callProductServiceWithCircuitBreaker calls Product Service with Circuit Breaker
func (s *CartService) callProductServiceWithCircuitBreaker(ctx context.Context, skuIDs []string) *entity.GrpcResult {
	if len(skuIDs) == 0 {
		return &entity.GrpcResult{Success: true, Products: make(map[string]*entity.ProductInfo)}
	}

	productResp, err := s.productSvc.GetBatchProductSkus(ctx, skuIDs)
	if err != nil {
		return &entity.GrpcResult{Success: false, Error: err}
	}

	return &entity.GrpcResult{
		Success:  true,
		Products: productResp.Products,
	}
}

// mergeItemsFromDB merges DB items with Product Service data
func (s *CartService) mergeItemsFromDB(items []*port.CartItemRecord, grpcResult *entity.GrpcResult) ([]entity.RehydrateItem, map[string]*entity.MergedCartItem) {
	rehydrateItems := make([]entity.RehydrateItem, 0, len(items))
	mergedData := make(map[string]*entity.MergedCartItem)

	for _, item := range items {
		var snapshot map[string]interface{}
		if err := json.Unmarshal([]byte(item.ProductSnapshot), &snapshot); err != nil {
			continue
		}

		// Inject unit_price_at_add
		snapshot["unit_price_at_add"] = item.UnitPriceAtAdd

		// Merge is_available from Product Service
		if grpcResult.Success && grpcResult.Products != nil {
			if product, ok := grpcResult.Products[item.SkuID]; ok {
				if !product.IsAvailable {
					snapshot["is_available"] = false
				}
			}
		}

		// Marshal back to JSON for rehydration
		mergedJSON, _ := json.Marshal(snapshot)

		rehydrateItems = append(rehydrateItems, entity.RehydrateItem{
			SkuID: item.SkuID,
			Value: string(mergedJSON),
		})

		mergedData[item.SkuID] = &entity.MergedCartItem{
			SkuID:          item.SkuID,
			Quantity:       item.Quantity,
			ProductName:    getStringFromMap(snapshot, "product_name"),
			VariantInfo:    getStringFromMap(snapshot, "variant_info"),
			ImageURL:       getStringFromMap(snapshot, "image_url"),
			UnitPriceAtAdd: item.UnitPriceAtAdd,
			IsAvailable:    getBoolFromMap(snapshot, "is_available"),
			AddedAt:        getStringFromMap(snapshot, "added_at"),
			UpdatedAt:      getStringFromMap(snapshot, "updated_at"),
		}
	}

	return rehydrateItems, mergedData
}

// buildCartResponse builds response from Redis data
func (s *CartService) buildCartResponse(cartKey string, cartData map[string]string, grpcResult *entity.GrpcResult) *GetCartResult {
	response := &GetCartResult{
		CartID:          cartKey,
		Items:           []entity.CartItemGroup{},
		IsPriceDegraded: !grpcResult.Success,
	}

	itemGroups := make(map[string]*entity.CartItemGroup)
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
					// Update Redis with is_available=false
					_ = s.cartRepo.UpdateItemAvailability(context.Background(), "", skuID, false)
				}
			}
		}

		updatedAt := getStringFromMap(item, "updated_at")
		updatedAtMs := parseTimestamp(updatedAt)

		itemResp := entity.CartItemResponse{
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

		// Use product_id as group key
		platformID := getStringFromMap(item, "product_id")
		if platformID == "" {
			platformID = "default"
		}

		if _, ok := itemGroups[platformID]; !ok {
			itemGroups[platformID] = &entity.CartItemGroup{
				Items: []entity.CartItemResponse{},
			}
		}
		itemGroups[platformID].Items = append(itemGroups[platformID].Items, itemResp)

		if updatedAtMs > maxUpdatedAt {
			maxUpdatedAt = updatedAtMs
		}
		totalItems += itemResp.Quantity
	}

	for _, group := range itemGroups {
		group.LatestItemAt = maxUpdatedAt
		response.Items = append(response.Items, *group)
	}

	sortItemsByAddedAt(&response.Items)
	response.TotalItems = totalItems
	response.TotalUniqueSKUs = len(cartData)

	return response
}

// buildCartResponseFromMerged builds response from merged DB data
func (s *CartService) buildCartResponseFromMerged(cartKey string, mergedData map[string]*entity.MergedCartItem, grpcResult *entity.GrpcResult) *GetCartResult {
	response := &GetCartResult{
		CartID:          cartKey,
		Items:           []entity.CartItemGroup{},
		IsPriceDegraded: !grpcResult.Success,
	}

	totalItems := 0
	maxUpdatedAt := int64(0)

	group := &entity.CartItemGroup{
		Items: []entity.CartItemResponse{},
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

		itemResp := entity.CartItemResponse{
			SkuID:          item.SkuID,
			ProductName:    item.ProductName,
			VariantInfo:    item.VariantInfo,
			ImageURL:       item.ImageURL,
			Quantity:       item.Quantity,
			UnitPriceAtAdd: item.UnitPriceAtAdd,
			CurrentPrice:   currentPrice,
			IsAvailable:    isAvailable,
			AddedAt:       item.AddedAt,
			UpdatedAt:      item.UpdatedAt,
		}

		group.Items = append(group.Items, itemResp)
		totalItems += item.Quantity
	}

	group.LatestItemAt = maxUpdatedAt
	sortItemsByAddedAt(&[]entity.CartItemGroup{*group})
	response.Items = append(response.Items, *group)

	response.TotalItems = totalItems
	response.TotalUniqueSKUs = len(mergedData)

	return response
}

// DeleteItemsResult represents the result of deleting items from cart
type DeleteItemsResult struct {
	Success      bool
	DeletedCount int64
	Error       error
}

// DeleteItems deletes items from the cart
func (s *CartService) DeleteItems(ctx context.Context, userID, cartID string, skuIDs []string) (*DeleteItemsResult, error) {
	// Validate cart_id
	if !s.keyBuilder.IsValidCartKey(cartID) {
		return &DeleteItemsResult{
			Success: false,
			Error:  fmt.Errorf("invalid cart_id format"),
		}, nil
	}

	// Extract user_id from cart_id and verify
	extractedUserID, err := s.keyBuilder.ExtractUserIDFromCartID(cartID)
	if err != nil {
		return &DeleteItemsResult{
			Success: false,
			Error:  fmt.Errorf("invalid cart_id format"),
		}, nil
	}

	if extractedUserID != userID {
		return &DeleteItemsResult{
			Success: false,
			Error:  fmt.Errorf("cart does not belong to user"),
		}, nil
	}

	outboxKey := cartID + ":outbox"
	eventTimeMs := time.Now().UnixMilli()

	// Pre-register intent
	if err := s.intentIndex.PreRegisterIntent(ctx, outboxKey); err != nil {
		return &DeleteItemsResult{
			Success: false,
			Error:  err,
		}, err
	}

	// Execute Batch Delete Lua script
	deletedCount, _, err := s.cartRepo.BatchDeleteItems(
		ctx,
		userID,
		skuIDs,
		event.CartCleared, // CART_CLEARED for user-initiated delete
		eventTimeMs,
	)

	if err != nil {
		_ = s.intentIndex.ClearIntent(ctx, outboxKey)
		return &DeleteItemsResult{
			Success: false,
			Error:  err,
		}, err
	}

	// Invalidate negative cache
	_ = s.negCache.Invalidate(ctx, userID)

	// Publish path
	_ = s.intentIndex.MarkActive(ctx, outboxKey)
	_ = s.intentIndex.ClearIntent(ctx, outboxKey)

	return &DeleteItemsResult{
		Success:      true,
		DeletedCount: deletedCount,
	}, nil
}

// Helper functions
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

func sortItemsByAddedAt(items *[]entity.CartItemGroup) {
	for i := range *items {
		arr := (*items)[i].Items
		for j := 0; j < len(arr)-1; j++ {
			for k := 0; k < len(arr)-j-1; k++ {
				t1 := parseTimestamp(arr[k].AddedAt)
				t2 := parseTimestamp(arr[k+1].AddedAt)
				if t1 < t2 {
					arr[k], arr[k+1] = arr[k+1], arr[k]
				}
			}
		}
	}
}
