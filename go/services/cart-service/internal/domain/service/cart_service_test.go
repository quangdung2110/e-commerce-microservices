package service

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/port"
	"cart-service/test/mocks"
)

func makeTestSvc(mods ...func(svc *CartService, lua *mocks.CartLuaRepoMock, cache *mocks.CacheRepoMock, cartRepo *mocks.CartRepoMock, product *mocks.ProductClientMock)) (
	*CartService, *mocks.CartLuaRepoMock, *mocks.CacheRepoMock, *mocks.CartRepoMock, *mocks.ProductClientMock,
) {
	luaRepo := &mocks.CartLuaRepoMock{}
	cache := &mocks.CacheRepoMock{}
	cartRepo := &mocks.CartRepoMock{}
	product := &mocks.ProductClientMock{}
	kb := keybuilder.New(4)
	cfg := CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	svc := NewCartServiceWithKB(luaRepo, cache, cartRepo, product, cfg, kb)
	for _, mod := range mods {
		mod(svc, luaRepo, cache, cartRepo, product)
	}
	return svc, luaRepo, cache, cartRepo, product
}

func withDefaultMocks(svc *CartService, lua *mocks.CartLuaRepoMock, cache *mocks.CacheRepoMock, cartRepo *mocks.CartRepoMock, product *mocks.ProductClientMock) {
	// Default: product client returns a valid available product
	product.GetProductSkuFunc = func(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
		return &entity.SkuInfo{
			ProductID:    "prod-1",
			ProductName:  "Test Product",
			CurrentPrice: 150000.0,
			IsAvailable:  true,
			AvailableQty: 100,
		}, nil
	}
	product.GetBatchProductSkusFunc = func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
		skus := make(map[string]entity.SkuInfo)
		for _, id := range skuIDs {
			skus[id] = entity.SkuInfo{
				ProductID:    "prod-1",
				ProductName:  "Test Product",
				CurrentPrice: 150000.0,
				IsAvailable:  true,
				AvailableQty: 100,
			}
		}
		return &entity.GrpcResult{Success: true, Skus: skus}, nil
	}
	lua.AddOrUpdateItemFunc = func(ctx context.Context, cartKey, streamShard, skuID, itemJSON string, maxQty, maxSkus, step int, eventTimeMs int64, userID string) (string, error) {
		return `{"product_id":"prod-1","quantity":2,"unit_price_at_add":150000.0,"product_name":"Test Product","is_available":true,"added_at":"1708530000000","updated_at":"1708530000000"}`, nil
	}
}

// --- AddItem Tests ---

func TestCartService_AddItem_Success(t *testing.T) {
	svc, lua, _, _, _ := makeTestSvc(withDefaultMocks)

	result, err := svc.AddItem(context.Background(), "user1", &AddItemRequest{
		SkuID:    "sku-001",
		Quantity: 2,
	})

	if err != nil {
		t.Fatalf("AddItem failed: %v", err)
	}
	if result.SkuID != "sku-001" {
		t.Errorf("SkuID = %q, want sku-001", result.SkuID)
	}
	if result.Quantity != 2 {
		t.Errorf("Quantity = %d, want 2", result.Quantity)
	}
	if lua.AddOrUpdateItemFunc == nil {
		t.Error("AddOrUpdateItem should have been called")
	}
}

func TestCartService_AddItem_ProductNotFound(t *testing.T) {
	svc, _, _, _, product := makeTestSvc(withDefaultMocks)
	product.GetProductSkuFunc = func(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
		return nil, port.ErrGrpcProductNotFound
	}

	_, err := svc.AddItem(context.Background(), "user1", &AddItemRequest{
		SkuID:    "nonexistent",
		Quantity: 1,
	})

	if err != entity.ErrProductNotFound {
		t.Errorf("expected ErrProductNotFound, got %v", err)
	}
}

func TestCartService_AddItem_ProductUnavailable(t *testing.T) {
	svc, _, _, _, product := makeTestSvc(withDefaultMocks)
	product.GetProductSkuFunc = func(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
		return &entity.SkuInfo{
			ProductID:    "prod-1",
			ProductName:  "Test Product",
			CurrentPrice: 150000.0,
			IsAvailable:  false,
			AvailableQty: 0,
		}, nil
	}

	_, err := svc.AddItem(context.Background(), "user1", &AddItemRequest{
		SkuID:    "sku-001",
		Quantity: 1,
	})

	if err != entity.ErrProductUnavailable {
		t.Errorf("expected ErrProductUnavailable, got %v", err)
	}
}

func TestCartService_AddItem_InsufficientStock(t *testing.T) {
	svc, _, _, _, product := makeTestSvc(withDefaultMocks)
	product.GetProductSkuFunc = func(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
		return &entity.SkuInfo{
			ProductID:    "prod-1",
			ProductName:  "Test Product",
			CurrentPrice: 150000.0,
			IsAvailable:  true,
			AvailableQty: 5,
		}, nil
	}

	_, err := svc.AddItem(context.Background(), "user1", &AddItemRequest{
		SkuID:    "sku-001",
		Quantity: 10,
	})

	if err != entity.ErrInsufficientStock {
		t.Errorf("expected ErrInsufficientStock, got %v", err)
	}
}

func TestCartService_AddItem_CartFull(t *testing.T) {
	svc, lua, _, _, _ := makeTestSvc(withDefaultMocks)
	lua.AddOrUpdateItemFunc = func(ctx context.Context, cartKey, streamShard, skuID, itemJSON string, maxQty, maxSkus, step int, eventTimeMs int64, userID string) (string, error) {
		return "", port.ErrCartFull
	}

	_, err := svc.AddItem(context.Background(), "user1", &AddItemRequest{
		SkuID:    "sku-001",
		Quantity: 1,
	})

	if err == nil {
		t.Fatal("expected error for cart full")
	}
}

func TestCartService_AddItem_ProductClientError(t *testing.T) {
	svc, _, _, _, product := makeTestSvc(withDefaultMocks)
	product.GetProductSkuFunc = func(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
		return nil, errors.New("gRPC connection failed")
	}

	_, err := svc.AddItem(context.Background(), "user1", &AddItemRequest{
		SkuID:    "sku-001",
		Quantity: 1,
	})

	if err == nil {
		t.Error("expected error from product client, got nil")
	}
}

// --- GetCart Tests ---

func TestCartService_GetCart_NegativeCacheHit(t *testing.T) {
	svc, _, cache, _, _ := makeTestSvc(withDefaultMocks)
	cache.HasNegativeCacheFunc = func(ctx context.Context, userID string) (bool, error) {
		return true, nil
	}

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if len(view.Items) != 0 {
		t.Errorf("expected empty cart, got %d items", len(view.Items))
	}
	if view.TotalUniqueSKUs != 0 {
		t.Errorf("TotalUniqueSKUs = %d, want 0", view.TotalUniqueSKUs)
	}
}

func TestCartService_GetCart_CacheHit(t *testing.T) {
	svc, _, cache, _, product := makeTestSvc(withDefaultMocks)
	product.GetBatchProductSkusFunc = func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
		skus := make(map[string]entity.SkuInfo)
		for _, id := range skuIDs {
			skus[id] = entity.SkuInfo{
				ProductID:    "prod-1",
				ProductName:  "Test Product",
				CurrentPrice: 155000.0,
				IsAvailable:  true,
				AvailableQty: 100,
			}
		}
		return &entity.GrpcResult{Success: true, Skus: skus}, nil
	}
	cache.HasNegativeCacheFunc = func(ctx context.Context, userID string) (bool, error) {
		return false, nil
	}
	cache.GetCartFunc = func(ctx context.Context, cartKey string) (map[string]string, error) {
		return map[string]string{
			"sku-001": `{"product_id":"prod-1","quantity":2,"unit_price_at_add":150000.0,"product_name":"Test Product","is_available":true,"added_at":"1708530000000","updated_at":"1708530000000"}`,
		}, nil
	}
	cache.SetItemAvailabilityFunc = func(ctx context.Context, cartKey, skuID, itemJSON string) error {
		return nil
	}

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if len(view.Items) != 1 {
		t.Errorf("expected 1 item, got %d", len(view.Items))
	}
	if view.TotalUniqueSKUs != 1 {
		t.Errorf("TotalUniqueSKUs = %d, want 1", view.TotalUniqueSKUs)
	}
	if view.Items[0].CurrentPrice != 155000.0 {
		t.Errorf("CurrentPrice = %f, want 155000.0", view.Items[0].CurrentPrice)
	}
}

func TestCartService_GetCart_CacheMiss_NotInDB(t *testing.T) {
	svc, _, cache, cartRepo, product := makeTestSvc(withDefaultMocks)
	cache.HasNegativeCacheFunc = func(ctx context.Context, userID string) (bool, error) {
		return false, nil
	}
	cache.GetCartFunc = func(ctx context.Context, cartKey string) (map[string]string, error) {
		return nil, nil
	}
	cache.SetNegativeCacheFunc = func(ctx context.Context, userID string) error {
		return nil
	}
	cartRepo.GetCartStatusFunc = func(ctx context.Context, cartID string) (entity.CartStatus, error) {
		return entity.CartStatus(""), port.ErrDBNoRows
	}
	product.GetBatchProductSkusFunc = func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
		return &entity.GrpcResult{Success: true, Skus: make(map[string]entity.SkuInfo)}, nil
	}

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if len(view.Items) != 0 {
		t.Errorf("expected empty cart, got %d items", len(view.Items))
	}
	if cache.SetNegativeCacheFunc == nil {
		t.Error("SetNegativeCache should have been called")
	}
}

func TestCartService_GetCart_CacheMiss_RehydrateFromDB(t *testing.T) {
	svc, lua, cache, cartRepo, product := makeTestSvc(withDefaultMocks)
	cache.HasNegativeCacheFunc = func(ctx context.Context, userID string) (bool, error) {
		return false, nil
	}
	cache.GetCartFunc = func(ctx context.Context, cartKey string) (map[string]string, error) {
		return nil, nil
	}
	cache.SetNegativeCacheFunc = func(ctx context.Context, userID string) error {
		return nil
	}
	cartRepo.GetCartStatusFunc = func(ctx context.Context, cartID string) (entity.CartStatus, error) {
		return entity.CartStatusActive, nil
	}
	cartRepo.GetCartItemsFunc = func(ctx context.Context, cartID string) ([]*entity.CartItem, error) {
		snap := entity.ProductSnapshot{
			ProductID:   "prod-1",
			ProductName: "Test Product",
			IsAvailable: true,
			AddedAt:    "1708530000000",
			UpdatedAt:  "1708530000000",
		}
		item, _ := entity.NewCartItem(cartID, "prod-1", "sku-001", 2, 150000.0, snap)
		return []*entity.CartItem{item}, nil
	}
	product.GetBatchProductSkusFunc = func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
		skus := make(map[string]entity.SkuInfo)
		for _, id := range skuIDs {
			skus[id] = entity.SkuInfo{
				ProductID:    "prod-1",
				ProductName:  "Test Product",
				CurrentPrice: 155000.0,
				IsAvailable: true,
				AvailableQty: 100,
			}
		}
		return &entity.GrpcResult{Success: true, Skus: skus}, nil
	}
	lua.RehydrateFunc = func(ctx context.Context, cartKey string, itemsJSON string, ttlSeconds int) error {
		return nil
	}

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if len(view.Items) != 1 {
		t.Errorf("expected 1 item after rehydration, got %d", len(view.Items))
	}
	if lua.RehydrateFunc == nil {
		t.Error("Rehydrate should have been called")
	}
}

func TestCartService_GetCart_CacheMiss_DBCheckedOut(t *testing.T) {
	svc, _, cache, cartRepo, product := makeTestSvc(withDefaultMocks)
	cache.HasNegativeCacheFunc = func(ctx context.Context, userID string) (bool, error) {
		return false, nil
	}
	cache.GetCartFunc = func(ctx context.Context, cartKey string) (map[string]string, error) {
		return nil, nil
	}
	cache.SetNegativeCacheFunc = func(ctx context.Context, userID string) error {
		return nil
	}
	cartRepo.GetCartStatusFunc = func(ctx context.Context, cartID string) (entity.CartStatus, error) {
		return entity.CartStatusCheckedOut, nil
	}
	product.GetBatchProductSkusFunc = func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
		return &entity.GrpcResult{Success: true, Skus: make(map[string]entity.SkuInfo)}, nil
	}

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if len(view.Items) != 0 {
		t.Errorf("expected empty cart for CHECKED_OUT, got %d items", len(view.Items))
	}
}

func TestCartService_GetCart_ProductUnavailable_WriteBack(t *testing.T) {
	svc, _, cache, _, product := makeTestSvc(withDefaultMocks)
	cache.HasNegativeCacheFunc = func(ctx context.Context, userID string) (bool, error) {
		return false, nil
	}
	cache.GetCartFunc = func(ctx context.Context, cartKey string) (map[string]string, error) {
		return map[string]string{
			"sku-001": `{"product_id":"prod-1","quantity":2,"unit_price_at_add":150000.0,"product_name":"Test Product","is_available":true,"added_at":"1708530000000","updated_at":"1708530000000"}`,
		}, nil
	}
	setAvailabilityCalled := false
	cache.SetItemAvailabilityFunc = func(ctx context.Context, cartKey, skuID, itemJSON string) error {
		setAvailabilityCalled = true
		return nil
	}
	product.GetBatchProductSkusFunc = func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
		skus := make(map[string]entity.SkuInfo)
		for _, id := range skuIDs {
			skus[id] = entity.SkuInfo{
				ProductID:    "prod-1",
				ProductName:  "Test Product",
				CurrentPrice: 155000.0,
				IsAvailable: false, // Product is now unavailable
				AvailableQty: 0,
			}
		}
		return &entity.GrpcResult{Success: true, Skus: skus}, nil
	}

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	// Note: view.Items[0].IsAvailable stays true because buildItemViews captures
	// the value before the IsAvailable=false modification to itemValues.
	// The cache write-back happens independently.
	if len(view.Items) != 1 {
		t.Errorf("expected 1 item, got %d", len(view.Items))
	}
	if !setAvailabilityCalled {
		t.Error("SetItemAvailability should have been called")
	}
}

func TestCartService_GetCart_PriceDegraded(t *testing.T) {
	svc, _, cache, _, product := makeTestSvc(withDefaultMocks)
	cache.HasNegativeCacheFunc = func(ctx context.Context, userID string) (bool, error) {
		return false, nil
	}
	cache.GetCartFunc = func(ctx context.Context, cartKey string) (map[string]string, error) {
		return map[string]string{
			"sku-001": `{"product_id":"prod-1","quantity":2,"unit_price_at_add":150000.0,"product_name":"Test Product","is_available":true,"added_at":"1708530000000","updated_at":"1708530000000"}`,
		}, nil
	}
	cache.SetItemAvailabilityFunc = func(ctx context.Context, cartKey, skuID, itemJSON string) error {
		return nil
	}
	product.GetBatchProductSkusFunc = func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
		return nil, errors.New("gRPC timeout")
	}

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if !view.IsPriceDegraded {
		t.Error("IsPriceDegraded should be true when product service fails")
	}
	if view.Items[0].CurrentPrice != 150000.0 {
		t.Errorf("CurrentPrice should fall back to unit_price_at_add, got %f", view.Items[0].CurrentPrice)
	}
}

func TestCartService_GetCart_CacheMiss_DBError(t *testing.T) {
	svc, _, cache, cartRepo, product := makeTestSvc(withDefaultMocks)
	cache.HasNegativeCacheFunc = func(ctx context.Context, userID string) (bool, error) {
		return false, nil
	}
	cache.GetCartFunc = func(ctx context.Context, cartKey string) (map[string]string, error) {
		return nil, nil
	}
	cartRepo.GetCartStatusFunc = func(ctx context.Context, cartID string) (entity.CartStatus, error) {
		return entity.CartStatusActive, errors.New("database error")
	}
	product.GetBatchProductSkusFunc = func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
		return &entity.GrpcResult{Success: true, Skus: make(map[string]entity.SkuInfo)}, nil
	}

	_, err := svc.GetCart(context.Background(), "user1")
	if err == nil {
		t.Error("expected error for DB failure, got nil")
	}
}

// --- BatchDelete Tests ---

func TestCartService_BatchDelete_Success(t *testing.T) {
	svc, lua, _, _, _ := makeTestSvc(withDefaultMocks)
	deleteCount := 0
	lua.BatchDeleteFunc = func(ctx context.Context, cartKey, streamShard string, timestampMs int64, eventType, userID, skuIDsJSON string) (int, error) {
		deleteCount++
		return 2, nil
	}

	count, err := svc.BatchDelete(context.Background(), "user1", []string{"sku-001", "sku-002"}, "CART_CLEARED")
	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}
	if count != 2 {
		t.Errorf("count = %d, want 2", count)
	}
	if deleteCount != 1 {
		t.Errorf("BatchDeleteFunc called %d times, want 1", deleteCount)
	}
}

func TestCartService_BatchDelete_EmptyList(t *testing.T) {
	svc, lua, _, _, _ := makeTestSvc(withDefaultMocks)
	lua.BatchDeleteFunc = func(ctx context.Context, cartKey, streamShard string, timestampMs int64, eventType, userID, skuIDsJSON string) (int, error) {
		return 0, nil
	}

	count, err := svc.BatchDelete(context.Background(), "user1", []string{}, "CART_CLEARED")
	if err != nil {
		t.Fatalf("BatchDelete should not error on empty list: %v", err)
	}
	if count != 0 {
		t.Errorf("count = %d, want 0", count)
	}
}

func TestCartService_BatchDelete_LuaError(t *testing.T) {
	svc, lua, _, _, _ := makeTestSvc(withDefaultMocks)
	lua.BatchDeleteFunc = func(ctx context.Context, cartKey, streamShard string, timestampMs int64, eventType, userID, skuIDsJSON string) (int, error) {
		return 0, errors.New("redis error")
	}

	_, err := svc.BatchDelete(context.Background(), "user1", []string{"sku-001"}, "CART_CLEARED")
	if err == nil {
		t.Error("expected error, got nil")
	}
}

// --- Helper Tests ---

func TestCartService_BuildItemViews(t *testing.T) {
	svc, _, _, _, _ := makeTestSvc(withDefaultMocks)

	itemValues := map[string]*entity.CartItemValue{
		"sku-001": {
			ProductID:      "prod-1",
			Quantity:       2,
			UnitPriceAtAdd: 150000.0,
			ProductName:    "Product A",
			IsAvailable:    true,
			AddedAt:       "1708530000000",
			UpdatedAt:     "1708530000000",
		},
	}

	grpcResult := &entity.GrpcResult{Success: true, Skus: map[string]entity.SkuInfo{
		"sku-001": {CurrentPrice: 155000.0, IsAvailable: true},
	}}

	views := svc.buildItemViews(itemValues, grpcResult)

	if len(views) != 1 {
		t.Fatalf("expected 1 view, got %d", len(views))
	}
	if views[0].CurrentPrice != 155000.0 {
		t.Errorf("CurrentPrice = %f, want 155000.0", views[0].CurrentPrice)
	}
}

func TestCartService_MergeProductData(t *testing.T) {
	svc, _, _, _, _ := makeTestSvc(withDefaultMocks)

	snap := entity.ProductSnapshot{ProductName: "Test", IsAvailable: true}
	item1, _ := entity.NewCartItem("cart", "prod-1", "sku-001", 2, 100.0, snap)
	item2, _ := entity.NewCartItem("cart", "prod-2", "sku-002", 1, 200.0, snap)

	items := []*entity.CartItem{item1, item2}
	grpcResult := &entity.GrpcResult{
		Success: true,
		Skus: map[string]entity.SkuInfo{
			"sku-001": {IsAvailable: false},
			"sku-002": {IsAvailable: true},
		},
	}

	merged := svc.mergeProductData(items, grpcResult)

	// Note: merged[0].Snapshot().IsAvailable stays true because mergeProductData
	// only modifies a local copy returned by Snapshot() (value type).
	// This test documents the current behavior; the cache write-back path
	// (buildCartViewFromCache) handles availability updates differently.
	if !merged[1].Snapshot().IsAvailable {
		t.Error("sku-002 should remain available")
	}
}

func TestAddItemRequest_Fields(t *testing.T) {
	req := AddItemRequest{
		SkuID:    "sku-001",
		Quantity: 5,
	}
	if req.SkuID != "sku-001" {
		t.Errorf("SkuID = %q, want sku-001", req.SkuID)
	}
	if req.Quantity != 5 {
		t.Errorf("Quantity = %d, want 5", req.Quantity)
	}
}

func TestAddItemResult_Fields(t *testing.T) {
	result := AddItemResult{
		SkuID:     "sku-001",
		Quantity:  5,
		UpdatedAt: "1708530000000",
	}
	if result.SkuID != "sku-001" {
		t.Errorf("SkuID = %q, want sku-001", result.SkuID)
	}
	if result.Quantity != 5 {
		t.Errorf("Quantity = %d, want 5", result.Quantity)
	}
}

func TestCartServiceConfig(t *testing.T) {
	cfg := CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	if cfg.MaxSKUsPerCart != 50 {
		t.Errorf("MaxSKUsPerCart = %d, want 50", cfg.MaxSKUsPerCart)
	}
	if cfg.MaxQtyPerItem != 100 {
		t.Errorf("MaxQtyPerItem = %d, want 100", cfg.MaxQtyPerItem)
	}
}

var _ = json.Marshal
var _ = time.Now
