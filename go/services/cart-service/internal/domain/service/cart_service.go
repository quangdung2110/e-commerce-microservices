package service

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/port"
)

// CartService handles cart business logic.
// It orchestrates domain entities and infrastructure ports.
// NO infrastructure code — only domain logic + port orchestration.
type CartService struct {
	luaRepo  port.CartLuaRepository
	cache    port.CacheRepository
	cartRepo port.CartRepository
	product  port.ProductClient
	cfg      CartServiceConfig
	kb       *keybuilder.KeyBuilder
}

// CartServiceConfig holds cart-specific configuration.
type CartServiceConfig struct {
	MaxSKUsPerCart   int
	MaxQtyPerItem    int
	RehydrateTTL     int // seconds
	NegativeCacheTTL int // seconds
}

// NewCartService creates a new cart service.
func NewCartService(
	luaRepo port.CartLuaRepository,
	cache port.CacheRepository,
	cartRepo port.CartRepository,
	product port.ProductClient,
	cfg CartServiceConfig,
) *CartService {
	kb := keybuilder.New(4) // numShards=4 matches config
	return &CartService{
		luaRepo:  luaRepo,
		cache:    cache,
		cartRepo: cartRepo,
		product:  product,
		cfg:      cfg,
		kb:       kb,
	}
}

// NewCartServiceWithKB creates a new cart service with an explicit KeyBuilder.
// Use this when a KeyBuilder with a specific shard count is needed (e.g., from ShardManager).
func NewCartServiceWithKB(
	luaRepo port.CartLuaRepository,
	cache port.CacheRepository,
	cartRepo port.CartRepository,
	product port.ProductClient,
	cfg CartServiceConfig,
	kb *keybuilder.KeyBuilder,
) *CartService {
	return &CartService{
		luaRepo:  luaRepo,
		cache:    cache,
		cartRepo: cartRepo,
		product:  product,
		cfg:      cfg,
		kb:       kb,
	}
}

// AddItemRequest is the input for AddItem.
type AddItemRequest struct {
	SkuID    string
	Quantity int
}

// AddItemResult is the output of AddItem.
type AddItemResult struct {
	SkuID     string
	Quantity  int
	UpdatedAt string
}

// AddItem adds or updates an item in the user's cart.
func (s *CartService) AddItem(ctx context.Context, userID string, req *AddItemRequest) (*AddItemResult, error) {
	skuInfo, err := s.product.GetProductSku(ctx, req.SkuID)
	if err != nil {
		if err == port.ErrGrpcProductNotFound {
			return nil, entity.ErrProductNotFound
		}
		return nil, fmt.Errorf("fetch product: %w", err)
	}

	if !skuInfo.IsAvailable {
		return nil, entity.ErrProductUnavailable
	}

	if skuInfo.AvailableQty < int64(req.Quantity) {
		return nil, entity.ErrInsufficientStock
	}

	nowMs := time.Now().UTC().UnixMilli()
	itemValue := entity.CartItemValue{
		ProductID:      skuInfo.ProductID,
		Quantity:       req.Quantity,
		UnitPriceAtAdd: skuInfo.CurrentPrice,
		ProductName:    skuInfo.ProductName,
		VariantInfo:    skuInfo.VariantInfo,
		ImageURL:       skuInfo.ImageURL,
		IsAvailable:    true,
		AddedAt:        fmt.Sprintf("%d", nowMs),
		UpdatedAt:      fmt.Sprintf("%d", nowMs),
	}

	itemJSON, err := json.Marshal(itemValue)
	if err != nil {
		return nil, fmt.Errorf("marshal item: %w", err)
	}

	cartKey := s.kb.CartKey(userID)
	streamShard := s.kb.StreamShardKey(userID)

	resultJSON, err := s.luaRepo.AddOrUpdateItem(
		ctx,
		cartKey, streamShard,
		req.SkuID, string(itemJSON),
		s.cfg.MaxQtyPerItem, s.cfg.MaxSKUsPerCart,
		req.Quantity,
		nowMs, userID,
	)
	if err != nil {
		return nil, fmt.Errorf("add item: %w", err)
	}

	if delErr := s.cache.DeleteNegativeCache(ctx, userID); delErr != nil {
		_ = delErr
	}

	var updatedItem entity.CartItemValue
	json.Unmarshal([]byte(resultJSON), &updatedItem)

	return &AddItemResult{
		SkuID:     req.SkuID,
		Quantity:  updatedItem.Quantity,
		UpdatedAt: updatedItem.UpdatedAt,
	}, nil
}

// GetCart retrieves the user's cart with current prices from Product Service.
func (s *CartService) GetCart(ctx context.Context, userID string) (*entity.CartView, error) {
	hasNeg, err := s.cache.HasNegativeCache(ctx, userID)
	if err == nil && hasNeg {
		return &entity.CartView{
			CartID:          s.kb.CartKey(userID),
			Items:           []entity.ItemView{},
			IsPriceDegraded: false,
			TotalItems:      0,
			TotalUniqueSKUs: 0,
		}, nil
	}

	cartKey := s.kb.CartKey(userID)
	hashData, err := s.cache.GetCart(ctx, cartKey)
	if err != nil {
		return nil, fmt.Errorf("fetch cart: %w", err)
	}

	if len(hashData) > 0 {
		return s.buildCartViewFromCache(ctx, userID, cartKey, hashData)
	}

	return s.rehydrateAndBuildCart(ctx, userID)
}

// buildCartViewFromCache constructs cart view from Redis data.
func (s *CartService) buildCartViewFromCache(
	ctx context.Context, userID, cartKey string, hashData map[string]string,
) (*entity.CartView, error) {
	skuIDs := make([]string, 0, len(hashData))
	itemValues := make(map[string]*entity.CartItemValue, len(hashData))

	for skuID, jsonStr := range hashData {
		skuIDs = append(skuIDs, skuID)
		var item entity.CartItemValue
		if err := json.Unmarshal([]byte(jsonStr), &item); err != nil {
			return nil, fmt.Errorf("parse item %s: %w", skuID, err)
		}
		itemValues[skuID] = &item
	}

	grpcResult, err := s.product.GetBatchProductSkus(ctx, skuIDs)
	if err != nil {
		grpcResult = &entity.GrpcResult{Success: false, Error: err.Error()}
	}

	items := s.buildItemViews(itemValues, grpcResult)

	if grpcResult.Success {
		for skuID, skuInfo := range grpcResult.Skus {
			if !skuInfo.IsAvailable && itemValues[skuID] != nil {
				item := itemValues[skuID]
				item.IsAvailable = false
				updatedJSON, _ := json.Marshal(item)
				_ = s.cache.SetItemAvailability(ctx, cartKey, skuID, string(updatedJSON))
			}
		}
	}

	totalQty := 0
	for _, item := range items {
		totalQty += item.Quantity
	}

	return &entity.CartView{
		CartID:          cartKey,
		Items:           items,
		IsPriceDegraded: !grpcResult.Success,
		TotalItems:      totalQty,
		TotalUniqueSKUs: len(items),
	}, nil
}

// rehydrateAndBuildCart handles cache miss by loading from cold storage.
func (s *CartService) rehydrateAndBuildCart(ctx context.Context, userID string) (*entity.CartView, error) {
	cartKey := s.kb.CartKey(userID)

	status, err := s.cartRepo.GetCartStatus(ctx, cartKey)
	if err == port.ErrDBNoRows || (err == nil && status == entity.CartStatusDeleted) ||
		(err == nil && status == entity.CartStatusCheckedOut) {
		_ = s.cache.SetNegativeCache(ctx, userID)
		return &entity.CartView{
			CartID: cartKey, Items: []entity.ItemView{},
			IsPriceDegraded: false, TotalItems: 0, TotalUniqueSKUs: 0,
		}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("check cart status: %w", err)
	}

	pgItems, err := s.cartRepo.GetCartItems(ctx, cartKey)
	if err != nil {
		return nil, fmt.Errorf("load cold items: %w", err)
	}
	if len(pgItems) == 0 {
		_ = s.cache.SetNegativeCache(ctx, userID)
		return &entity.CartView{
			CartID: cartKey, Items: []entity.ItemView{},
			IsPriceDegraded: false, TotalItems: 0, TotalUniqueSKUs: 0,
		}, nil
	}

	skuIDs := make([]string, len(pgItems))
	for i, item := range pgItems {
		skuIDs[i] = item.SkuID()
	}

	grpcResult, err := s.product.GetBatchProductSkus(ctx, skuIDs)
	if err != nil {
		grpcResult = &entity.GrpcResult{Success: false, Error: err.Error()}
	}

	mergedItems := s.mergeProductData(pgItems, grpcResult)

	rehydrateItems := make(map[string]string)
	for _, item := range mergedItems {
		snap := item.Snapshot()
		itemJSON, _ := json.Marshal(entity.CartItemValue{
			ProductID:      item.ProductID(),
			Quantity:       item.Quantity(),
			UnitPriceAtAdd: item.UnitPriceAtAdd(),
			ProductName:    snap.ProductName,
			VariantInfo:    snap.VariantInfo,
			ImageURL:       snap.ImageURL,
			IsAvailable:    snap.IsAvailable,
			AddedAt:        snap.AddedAt,
			UpdatedAt:      snap.UpdatedAt,
		})
		rehydrateItems[item.SkuID()] = string(itemJSON)
	}
	rehydrateJSON := keybuilder.RehydrateItemsJSON(rehydrateItems)
	if reErr := s.luaRepo.Rehydrate(ctx, cartKey, rehydrateJSON, s.cfg.RehydrateTTL); reErr != nil {
		_ = reErr
	}

	itemValues := make(map[string]*entity.CartItemValue, len(mergedItems))
	for _, item := range mergedItems {
		snap := item.Snapshot()
		itemValues[item.SkuID()] = &entity.CartItemValue{
			ProductID:      item.ProductID(),
			Quantity:       item.Quantity(),
			UnitPriceAtAdd: item.UnitPriceAtAdd(),
			ProductName:    snap.ProductName,
			VariantInfo:    snap.VariantInfo,
			ImageURL:       snap.ImageURL,
			IsAvailable:    snap.IsAvailable,
			AddedAt:        snap.AddedAt,
			UpdatedAt:      snap.UpdatedAt,
		}
	}

	viewItems := s.buildItemViews(itemValues, grpcResult)
	totalQty := 0
	for _, item := range viewItems {
		totalQty += item.Quantity
	}

	return &entity.CartView{
		CartID:          cartKey,
		Items:           viewItems,
		IsPriceDegraded: !grpcResult.Success,
		TotalItems:      totalQty,
		TotalUniqueSKUs: len(viewItems),
	}, nil
}

// mergeProductData merges product data from gRPC with cold storage items.
func (s *CartService) mergeProductData(items []*entity.CartItem, grpcResult *entity.GrpcResult) []*entity.CartItem {
	if grpcResult.Success {
		for _, item := range items {
			if skuInfo, ok := grpcResult.Skus[item.SkuID()]; ok {
				if !skuInfo.IsAvailable {
					snap := item.Snapshot()
					snap.IsAvailable = false
				}
			}
		}
	}
	return items
}

// buildItemViews constructs ItemView slice from item values and grpc result.
func (s *CartService) buildItemViews(
	itemValues map[string]*entity.CartItemValue,
	grpcResult *entity.GrpcResult,
) []entity.ItemView {
	views := make([]entity.ItemView, 0, len(itemValues))
	for skuID, item := range itemValues {
		view := entity.ItemView{
			SkuID:          skuID,
			ProductID:      item.ProductID,
			Quantity:       item.Quantity,
			UnitPriceAtAdd: item.UnitPriceAtAdd,
			ProductName:    item.ProductName,
			VariantInfo:    item.VariantInfo,
			ImageURL:       item.ImageURL,
			IsAvailable:    item.IsAvailable,
			AddedAt:        item.AddedAt,
			UpdatedAt:      item.UpdatedAt,
			CurrentPrice:   item.UnitPriceAtAdd,
		}

		if grpcResult.Success {
			if skuInfo, ok := grpcResult.Skus[skuID]; ok {
				view.CurrentPrice = skuInfo.CurrentPrice
			}
		}

		views = append(views, view)
	}
	return views
}

// BatchDelete removes multiple SKUs from a cart.
func (s *CartService) BatchDelete(ctx context.Context, userID string, skuIDs []string, eventType string) (int, error) {
	cartKey := s.kb.CartKey(userID)
	streamShard := s.kb.StreamShardKey(userID)

	skuIDsJSON, err := json.Marshal(skuIDs)
	if err != nil {
		return 0, fmt.Errorf("marshal sku IDs: %w", err)
	}

	timestampMs := time.Now().UTC().UnixMilli()

	count, err := s.luaRepo.BatchDelete(
		ctx,
		cartKey, streamShard,
		timestampMs, eventType, userID, string(skuIDsJSON),
	)
	if err != nil {
		return 0, fmt.Errorf("batch delete: %w", err)
	}

	if delErr := s.cache.DeleteNegativeCache(ctx, userID); delErr != nil {
		_ = delErr
	}

	return count, nil
}
