package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	extredis "github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/constants"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/config"
	cartredis "github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/redis"
	"go.uber.org/zap"
)

// ============ Mock Implementations ============

type MockCartOperations struct {
	AddOrUpdateItemFunc        func(ctx context.Context, userID, skuID string, payloadJSON string, step int64, eventTimeMs int64) (string, string, error)
	BatchDeleteItemsFunc       func(ctx context.Context, userID string, skuIDs []string, clearEventType string, eventTimeMs int64) (int64, []string, error)
	SilentRehydrateFunc        func(ctx context.Context, userID string, items []entity.RehydrateItem) error
	GetCartFunc                func(ctx context.Context, userID string) (map[string]string, error)
	UpdateItemAvailabilityFunc func(ctx context.Context, userID, skuID string, isAvailable bool) error
	LoadScriptsFunc            func(ctx context.Context) error
}

func (m *MockCartOperations) AddOrUpdateItem(ctx context.Context, userID, skuID string, payloadJSON string, step int64, eventTimeMs int64) (string, string, error) {
	if m.AddOrUpdateItemFunc != nil {
		return m.AddOrUpdateItemFunc(ctx, userID, skuID, payloadJSON, step, eventTimeMs)
	}
	return "{}", "", nil
}

func (m *MockCartOperations) BatchDeleteItems(ctx context.Context, userID string, skuIDs []string, clearEventType string, eventTimeMs int64) (int64, []string, error) {
	if m.BatchDeleteItemsFunc != nil {
		return m.BatchDeleteItemsFunc(ctx, userID, skuIDs, clearEventType, eventTimeMs)
	}
	return 0, nil, nil
}

func (m *MockCartOperations) SilentRehydrate(ctx context.Context, userID string, items []entity.RehydrateItem) error {
	if m.SilentRehydrateFunc != nil {
		return m.SilentRehydrateFunc(ctx, userID, items)
	}
	return nil
}

func (m *MockCartOperations) GetCart(ctx context.Context, userID string) (map[string]string, error) {
	if m.GetCartFunc != nil {
		return m.GetCartFunc(ctx, userID)
	}
	return nil, nil
}

func (m *MockCartOperations) UpdateItemAvailability(ctx context.Context, userID, skuID string, isAvailable bool) error {
	if m.UpdateItemAvailabilityFunc != nil {
		return m.UpdateItemAvailabilityFunc(ctx, userID, skuID, isAvailable)
	}
	return nil
}

func (m *MockCartOperations) LoadScripts(ctx context.Context) error {
	if m.LoadScriptsFunc != nil {
		return m.LoadScriptsFunc(ctx)
	}
	return nil
}

type MockIntentIndexManager struct {
	PreRegisterIntentFunc   func(ctx context.Context, streamKey string) error
	MarkActiveFunc          func(ctx context.Context, streamKey string) error
	ClearIntentFunc         func(ctx context.Context, streamKey string) error
	ExecuteWithIntentFunc   func(ctx context.Context, streamKey string, operation func() error) error
}

func (m *MockIntentIndexManager) PreRegisterIntent(ctx context.Context, streamKey string) error {
	if m.PreRegisterIntentFunc != nil {
		return m.PreRegisterIntentFunc(ctx, streamKey)
	}
	return nil
}

func (m *MockIntentIndexManager) MarkActive(ctx context.Context, streamKey string) error {
	if m.MarkActiveFunc != nil {
		return m.MarkActiveFunc(ctx, streamKey)
	}
	return nil
}

func (m *MockIntentIndexManager) ClearIntent(ctx context.Context, streamKey string) error {
	if m.ClearIntentFunc != nil {
		return m.ClearIntentFunc(ctx, streamKey)
	}
	return nil
}

func (m *MockIntentIndexManager) ExecuteWithIntent(ctx context.Context, streamKey string, operation func() error) error {
	if m.ExecuteWithIntentFunc != nil {
		return m.ExecuteWithIntentFunc(ctx, streamKey, operation)
	}
	return operation()
}

type MockNegativeCacheManager struct {
	HasCachedResultFunc func(ctx context.Context, userID string) (bool, error)
	SetNotFoundFunc     func(ctx context.Context, userID string) error
	InvalidateFunc      func(ctx context.Context, userID string) error
}

func (m *MockNegativeCacheManager) HasCachedResult(ctx context.Context, userID string) (bool, error) {
	if m.HasCachedResultFunc != nil {
		return m.HasCachedResultFunc(ctx, userID)
	}
	return false, nil
}

func (m *MockNegativeCacheManager) SetNotFound(ctx context.Context, userID string) error {
	if m.SetNotFoundFunc != nil {
		return m.SetNotFoundFunc(ctx, userID)
	}
	return nil
}

func (m *MockNegativeCacheManager) Invalidate(ctx context.Context, userID string) error {
	if m.InvalidateFunc != nil {
		return m.InvalidateFunc(ctx, userID)
	}
	return nil
}

type MockProductClient struct {
	GetProductSkuFunc       func(ctx context.Context, skuID string) (*ProductInfo, error)
	GetBatchProductSkusFunc func(ctx context.Context, skuIDs []string) (*BatchProductSkusResponse, error)
	CloseFunc               func() error
}

func (m *MockProductClient) GetProductSku(ctx context.Context, skuID string) (*ProductInfo, error) {
	if m.GetProductSkuFunc != nil {
		return m.GetProductSkuFunc(ctx, skuID)
	}
	return &ProductInfo{
		SkuID:            skuID,
		ProductID:        "test-product-id",
		ProductName:      "Test Product",
		OriginalPrice:    100000,
		PromotionalPrice: 90000,
		CurrentPrice:     90000,
		DisplayQuantity:  100,
		SkuStatus:        "ACTIVE",
		ProductStatus:    "ACTIVE",
		IsAvailable:      true,
	}, nil
}

func (m *MockProductClient) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*BatchProductSkusResponse, error) {
	if m.GetBatchProductSkusFunc != nil {
		return m.GetBatchProductSkusFunc(ctx, skuIDs)
	}
	products := make(map[string]*ProductInfo)
	for _, skuID := range skuIDs {
		products[skuID] = &ProductInfo{
			SkuID:            skuID,
			ProductID:        "test-product-id",
			ProductName:      "Test Product",
			OriginalPrice:    100000,
			PromotionalPrice: 90000,
			CurrentPrice:     90000,
			DisplayQuantity:  100,
			SkuStatus:        "ACTIVE",
			ProductStatus:    "ACTIVE",
			IsAvailable:      true,
		}
	}
	return &BatchProductSkusResponse{Products: products}, nil
}

func (m *MockProductClient) Close() error {
	if m.CloseFunc != nil {
		return m.CloseFunc()
	}
	return nil
}

type MockDBClient struct {
	GetCartStatusFunc func(ctx context.Context, cartID string) (string, error)
	GetCartItemsFunc  func(ctx context.Context, cartID string) ([]CartItemRecord, error)
	CloseFunc         func() error
}

func (m *MockDBClient) GetCartStatus(ctx context.Context, cartID string) (string, error) {
	if m.GetCartStatusFunc != nil {
		return m.GetCartStatusFunc(ctx, cartID)
	}
	return "ACTIVE", nil
}

func (m *MockDBClient) GetCartItems(ctx context.Context, cartID string) ([]CartItemRecord, error) {
	if m.GetCartItemsFunc != nil {
		return m.GetCartItemsFunc(ctx, cartID)
	}
	return nil, nil
}

func (m *MockDBClient) Close() error {
	if m.CloseFunc != nil {
		return m.CloseFunc()
	}
	return nil
}

// ============ Test Helpers ============

func createTestDependencies() *Dependencies {
	return &Dependencies{
		CartOps:       &MockCartOperations{},
		IntentIndex:   &MockIntentIndexManager{},
		NegativeCache: &MockNegativeCacheManager{},
		ProductClient: &MockProductClient{},
		DB:            &MockDBClient{},
		Config:        &config.Config{},
		Logger:        zap.NewNop(),
	}
}

func createTestHandler() *CartHandler {
	return NewCartHandler(createTestDependencies())
}

func parseResponseBody(t *testing.T, w *httptest.ResponseRecorder) map[string]interface{} {
	var result map[string]interface{}
	err := json.Unmarshal(w.Body.Bytes(), &result)
	require.NoError(t, err)
	return result
}

// ============ AddItem Tests ============

func TestAddItem_MissingUserID(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"sku-001","quantity":1}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)

	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "X-User-Id header is required")
}

func TestAddItem_EmptySkuID(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"","quantity":1}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "sku_id is required")
}

func TestAddItem_ZeroQuantity(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"sku-001","quantity":0}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "quantity must be positive")
}

func TestAddItem_NegativeQuantity(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"sku-001","quantity":-1}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestAddItem_InvalidJSON(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{invalid json}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestAddItem_ProductInactive(t *testing.T) {
	deps := createTestDependencies()
	deps.ProductClient = &MockProductClient{
		GetProductSkuFunc: func(ctx context.Context, skuID string) (*ProductInfo, error) {
			return &ProductInfo{
				SkuID:         skuID,
				SkuStatus:     "INACTIVE",
				ProductStatus: "ACTIVE",
			}, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"sku-001","quantity":1}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "not available")
}

func TestAddItem_ProductNoInventory(t *testing.T) {
	deps := createTestDependencies()
	deps.ProductClient = &MockProductClient{
		GetProductSkuFunc: func(ctx context.Context, skuID string) (*ProductInfo, error) {
			return &ProductInfo{
				SkuID:           skuID,
				SkuStatus:       "ACTIVE",
				ProductStatus:   "ACTIVE",
				DisplayQuantity: 0,
			}, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"sku-001","quantity":1}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "Insufficient inventory")
}

func TestAddItem_Success(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		AddOrUpdateItemFunc: func(ctx context.Context, userID, skuID string, payloadJSON string, step int64, eventTimeMs int64) (string, string, error) {
			itemJSON := `{"sku_id":"sku-001","quantity":1,"product_name":"Test Product","unit_price_at_add":90000}`
			return itemJSON, "1708530000123-0", nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"sku-001","quantity":1}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp AddItemResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.True(t, resp.Success)
	assert.Equal(t, "{cart:user:user-123}", resp.CartID)
	assert.Equal(t, "sku-001", resp.Item.SkuID)
}

func TestAddItem_CartFull(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		AddOrUpdateItemFunc: func(ctx context.Context, userID, skuID string, payloadJSON string, step int64, eventTimeMs int64) (string, string, error) {
			return "", "", ErrCartFull
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"sku-001","quantity":1}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusUnprocessableEntity, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "Cart is full")
}

func TestAddItem_InvalidStep(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		AddOrUpdateItemFunc: func(ctx context.Context, userID, skuID string, payloadJSON string, step int64, eventTimeMs int64) (string, string, error) {
			return "", "", ErrStepInvalid
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(`{"sku_id":"sku-001","quantity":1}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.AddItem(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "Invalid quantity step")
}

func TestAddItem_CannotDecrementNonExisting(t *testing.T) {
	// Note: This test is informational - the handler validates quantity > 0 before calling CartOps
	// So ErrCannotDecrementNonExisting is only reachable through direct CartOps mocking
	// This test verifies the error constant exists
	assert.Equal(t, "cannot decrement non-existing item", ErrCannotDecrementNonExisting.Error())
}

// ============ GetCart Tests ============

func TestGetCart_MissingUserID(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	w := httptest.NewRecorder()

	handler.GetCart(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestGetCart_NegativeCacheHit(t *testing.T) {
	deps := createTestDependencies()
	deps.NegativeCache = &MockNegativeCacheManager{
		HasCachedResultFunc: func(ctx context.Context, userID string) (bool, error) {
			return true, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.GetCart(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp GetCartResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, "{cart:user:user-123}", resp.CartID)
	assert.Equal(t, 0, resp.TotalItems)
	assert.Equal(t, 0, resp.TotalUniqueSKUs)
}

func TestGetCart_CacheHit(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		GetCartFunc: func(ctx context.Context, userID string) (map[string]string, error) {
			return map[string]string{
				"sku-001": `{"sku_id":"sku-001","quantity":2,"product_name":"Test Product","unit_price_at_add":90000}`,
			}, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.GetCart(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp GetCartResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, 2, resp.TotalItems)
	assert.Equal(t, 1, resp.TotalUniqueSKUs)
}

func TestGetCart_CacheMissWithDBData(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		GetCartFunc: func(ctx context.Context, userID string) (map[string]string, error) {
			return map[string]string{}, nil
		},
		SilentRehydrateFunc: func(ctx context.Context, userID string, items []entity.RehydrateItem) error {
			return nil
		},
	}
	deps.NegativeCache = &MockNegativeCacheManager{
		HasCachedResultFunc: func(ctx context.Context, userID string) (bool, error) {
			return false, nil
		},
	}
	deps.DB = &MockDBClient{
		GetCartStatusFunc: func(ctx context.Context, cartID string) (string, error) {
			return "ACTIVE", nil
		},
		GetCartItemsFunc: func(ctx context.Context, cartID string) ([]CartItemRecord, error) {
			return []CartItemRecord{
				{
					SkuID:           "sku-001",
					Quantity:        2,
					UnitPriceAtAdd:  90000,
					ProductSnapshot: `{"product_name":"Test Product"}`,
				},
			}, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.GetCart(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp GetCartResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, 2, resp.TotalItems)
	assert.Equal(t, 1, resp.TotalUniqueSKUs)
	assert.False(t, resp.IsPriceDegraded)
}

func TestGetCart_CacheMissTerminalStatus(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		GetCartFunc: func(ctx context.Context, userID string) (map[string]string, error) {
			return map[string]string{}, nil
		},
	}
	deps.NegativeCache = &MockNegativeCacheManager{
		HasCachedResultFunc: func(ctx context.Context, userID string) (bool, error) {
			return false, nil
		},
	}
	deps.DB = &MockDBClient{
		GetCartStatusFunc: func(ctx context.Context, cartID string) (string, error) {
			return constants.CartStatusCheckedOut, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.GetCart(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp GetCartResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, 0, resp.TotalItems)
	assert.Equal(t, 0, resp.TotalUniqueSKUs)
}

func TestGetCart_CacheMissEmptyDB(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		GetCartFunc: func(ctx context.Context, userID string) (map[string]string, error) {
			return map[string]string{}, nil
		},
	}
	deps.NegativeCache = &MockNegativeCacheManager{
		HasCachedResultFunc: func(ctx context.Context, userID string) (bool, error) {
			return false, nil
		},
		SetNotFoundFunc: func(ctx context.Context, userID string) error {
			return nil
		},
	}
	deps.DB = &MockDBClient{
		GetCartStatusFunc: func(ctx context.Context, cartID string) (string, error) {
			return "ACTIVE", nil
		},
		GetCartItemsFunc: func(ctx context.Context, cartID string) ([]CartItemRecord, error) {
			return []CartItemRecord{}, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.GetCart(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp GetCartResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, 0, resp.TotalItems)
	assert.Equal(t, 0, resp.TotalUniqueSKUs)
}

func TestGetCart_PriceDegraded(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		GetCartFunc: func(ctx context.Context, userID string) (map[string]string, error) {
			return map[string]string{
				"sku-001": `{"sku_id":"sku-001","quantity":1,"product_name":"Test Product","unit_price_at_add":90000}`,
			}, nil
		},
	}
	deps.ProductClient = &MockProductClient{
		GetBatchProductSkusFunc: func(ctx context.Context, skuIDs []string) (*BatchProductSkusResponse, error) {
			return nil, assert.AnError
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.GetCart(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp GetCartResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.True(t, resp.IsPriceDegraded)
}

// ============ DeleteItems Tests ============

func TestDeleteItems_MissingUserID(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(`{"cart_id":"{cart:user:user-123}","sku_ids":["sku-001"]}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.DeleteItems(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestDeleteItems_MissingCartID(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(`{"cart_id":"","sku_ids":["sku-001"]}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.DeleteItems(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "cart_id is required")
}

func TestDeleteItems_EmptySkuIDs(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(`{"cart_id":"{cart:user:user-123}","sku_ids":[]}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.DeleteItems(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "sku_ids is required")
}

func TestDeleteItems_InvalidCartIDFormat(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(`{"cart_id":"invalid-cart-id","sku_ids":["sku-001"]}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.DeleteItems(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Contains(t, resp["error"], "Invalid cart_id format")
}

func TestDeleteItems_UserMismatch(t *testing.T) {
	handler := createTestHandler()

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(`{"cart_id":"{cart:user:user-123}","sku_ids":["sku-001"]}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-456")
	w := httptest.NewRecorder()

	handler.DeleteItems(w, req)

	assert.Equal(t, http.StatusForbidden, w.Code)
}

func TestDeleteItems_Success(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		BatchDeleteItemsFunc: func(ctx context.Context, userID string, skuIDs []string, clearEventType string, eventTimeMs int64) (int64, []string, error) {
			return int64(len(skuIDs)), nil, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(`{"cart_id":"{cart:user:user-123}","sku_ids":["sku-001","sku-002"]}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.DeleteItems(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp DeleteItemsResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.True(t, resp.Success)
	assert.Equal(t, int64(2), resp.DeletedCount)
}

func TestDeleteItems_InternalError(t *testing.T) {
	deps := createTestDependencies()
	deps.CartOps = &MockCartOperations{
		BatchDeleteItemsFunc: func(ctx context.Context, userID string, skuIDs []string, clearEventType string, eventTimeMs int64) (int64, []string, error) {
			return 0, nil, assert.AnError
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(`{"cart_id":"{cart:user:user-123}","sku_ids":["sku-001"]}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "user-123")
	w := httptest.NewRecorder()

	handler.DeleteItems(w, req)

	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

// ============ Helper Function Tests ============

func TestGetStringFromMap(t *testing.T) {
	tests := []struct {
		name     string
		m        map[string]interface{}
		key      string
		expected string
	}{
		{
			name:     "string value",
			m:        map[string]interface{}{"key": "value"},
			key:      "key",
			expected: "value",
		},
		{
			name:     "non-string value",
			m:        map[string]interface{}{"key": 123},
			key:      "key",
			expected: "",
		},
		{
			name:     "missing key",
			m:        map[string]interface{}{},
			key:      "key",
			expected: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := getStringFromMap(tt.m, tt.key)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestGetBoolFromMap(t *testing.T) {
	tests := []struct {
		name     string
		m        map[string]interface{}
		key      string
		expected bool
	}{
		{
			name:     "true value",
			m:        map[string]interface{}{"key": true},
			key:      "key",
			expected: true,
		},
		{
			name:     "false value",
			m:        map[string]interface{}{"key": false},
			key:      "key",
			expected: false,
		},
		{
			name:     "non-bool value",
			m:        map[string]interface{}{"key": "true"},
			key:      "key",
			expected: false,
		},
		{
			name:     "missing key",
			m:        map[string]interface{}{},
			key:      "key",
			expected: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := getBoolFromMap(tt.m, tt.key)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestGetFloat64FromMap(t *testing.T) {
	tests := []struct {
		name     string
		m        map[string]interface{}
		key      string
		expected float64
	}{
		{
			name:     "float64 value",
			m:        map[string]interface{}{"key": float64(123.45)},
			key:      "key",
			expected: 123.45,
		},
		{
			name:     "int value",
			m:        map[string]interface{}{"key": 123},
			key:      "key",
			expected: 123.0,
		},
		{
			name:     "non-numeric value",
			m:        map[string]interface{}{"key": "123"},
			key:      "key",
			expected: 0,
		},
		{
			name:     "missing key",
			m:        map[string]interface{}{},
			key:      "key",
			expected: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := getFloat64FromMap(tt.m, tt.key)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestGetIntFromMap(t *testing.T) {
	tests := []struct {
		name     string
		m        map[string]interface{}
		key      string
		expected int
	}{
		{
			name:     "float64 value",
			m:        map[string]interface{}{"key": float64(123.0)},
			key:      "key",
			expected: 123,
		},
		{
			name:     "int value",
			m:        map[string]interface{}{"key": 123},
			key:      "key",
			expected: 123,
		},
		{
			name:     "non-numeric value",
			m:        map[string]interface{}{"key": "123"},
			key:      "key",
			expected: 0,
		},
		{
			name:     "missing key",
			m:        map[string]interface{}{},
			key:      "key",
			expected: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := getIntFromMap(tt.m, tt.key)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestParseTimestamp(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected int64
	}{
		{
			name:     "valid timestamp",
			input:    "1708530000123",
			expected: 1708530000123,
		},
		{
			name:     "empty string",
			input:    "",
			expected: 0,
		},
		{
			name:     "invalid string",
			input:    "not-a-number",
			expected: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := parseTimestamp(tt.input)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestEmptyCartResponse(t *testing.T) {
	cartID := "{cart:user:123}"
	resp := EmptyCartResponse(cartID)

	assert.Equal(t, cartID, resp.CartID)
	assert.Equal(t, 0, resp.TotalItems)
	assert.Equal(t, 0, resp.TotalUniqueSKUs)
	assert.False(t, resp.IsPriceDegraded)
	assert.Empty(t, resp.Items)
}

func TestWriteError(t *testing.T) {
	handler := createTestHandler()

	w := httptest.NewRecorder()
	handler.writeError(w, http.StatusBadRequest, "test error")

	assert.Equal(t, http.StatusBadRequest, w.Code)
	assert.Contains(t, w.Header().Get("Content-Type"), "application/json")

	var resp map[string]string
	json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Equal(t, "test error", resp["error"])
}

func TestWriteJSON(t *testing.T) {
	handler := createTestHandler()

	w := httptest.NewRecorder()
	handler.writeJSON(w, http.StatusOK, map[string]string{"key": "value"})

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Contains(t, w.Header().Get("Content-Type"), "application/json")
}

// ============ Sort Items Tests ============

func TestSortItemsByAddedAt(t *testing.T) {
	now := time.Now().UnixMilli()
	items := []CartItemResponse{
		{SkuID: "sku-1", AddedAt: strconv.FormatInt(now-1000, 10)},
		{SkuID: "sku-2", AddedAt: strconv.FormatInt(now-2000, 10)},
		{SkuID: "sku-3", AddedAt: strconv.FormatInt(now, 10)},
	}

	sortItemsByAddedAt(&items)

	assert.Equal(t, "sku-3", items[0].SkuID)
	assert.Equal(t, "sku-1", items[1].SkuID)
	assert.Equal(t, "sku-2", items[2].SkuID)
}

func TestSortItemsByAddedAt_Empty(t *testing.T) {
	items := []CartItemResponse{}
	sortItemsByAddedAt(&items)
	assert.Empty(t, items)
}

func TestSortItemsByAddedAt_SingleItem(t *testing.T) {
	items := []CartItemResponse{
		{SkuID: "sku-1", AddedAt: "1708530000123"},
	}
	sortItemsByAddedAt(&items)
	assert.Equal(t, "sku-1", items[0].SkuID)
}

// ============ Integration Tests with miniredis ============
// Note: These tests verify the API handlers work correctly with mocked dependencies.
// Full Redis integration requires a real Redis instance.

type testIntegrationEnv struct {
	redisServer *miniredis.Miniredis
	redisClient *extredis.Client
	cartOps     cartredis.CartOperations
	handler     *CartHandler
}

func setupIntegrationEnv(t *testing.T) *testIntegrationEnv {
	mr, err := miniredis.Run()
	require.NoError(t, err)

	client := extredis.NewClient(&extredis.Options{
		Addr: mr.Addr(),
	})

	keyBuilder := keybuilder.NewCartKeyBuilder()
	cartOps := cartredis.NewCartOperations(client, keyBuilder, 100, 50, 86400)

	cfg := &config.Config{}
	deps := &Dependencies{
		CartOps:      cartOps,
		IntentIndex:  &MockIntentIndexManager{},
		NegativeCache: &MockNegativeCacheManager{},
		ProductClient: &MockProductClient{},
		DB:           &MockDBClient{},
		Config:       cfg,
		Logger:       zap.NewNop(),
	}
	handler := NewCartHandler(deps)

	return &testIntegrationEnv{
		redisServer: mr,
		redisClient: client,
		cartOps:     cartOps,
		handler:     handler,
	}
}

func (e *testIntegrationEnv) teardown() {
	e.redisClient.Close()
	e.redisServer.Close()
}

// TestIntegration_AddItemValidation verifies the handler validates input correctly
func TestIntegration_AddItemValidation(t *testing.T) {
	env := setupIntegrationEnv(t)
	defer env.teardown()

	// Test missing user ID
	reqBody := `{"sku_id":"test-sku","quantity":1}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	env.handler.AddItem(w, req)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

// TestIntegration_AddItemSuccess verifies successful add item response
func TestIntegration_AddItemSuccess(t *testing.T) {
	env := setupIntegrationEnv(t)
	defer env.teardown()

	reqBody := `{"sku_id":"test-sku-001","quantity":3}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/carts/items", strings.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Id", "integration-user-001")
	w := httptest.NewRecorder()

	env.handler.AddItem(w, req)

	// Should succeed
	assert.Equal(t, http.StatusOK, w.Code)

	var addResp AddItemResponse
	err := json.Unmarshal(w.Body.Bytes(), &addResp)
	require.NoError(t, err)
	assert.True(t, addResp.Success)
	assert.Equal(t, "test-sku-001", addResp.Item.SkuID)
}

// TestIntegration_GetCartEmpty verifies empty cart response
func TestIntegration_GetCartEmpty(t *testing.T) {
	env := setupIntegrationEnv(t)
	defer env.teardown()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	req.Header.Set("X-User-Id", "new-user")
	w := httptest.NewRecorder()

	env.handler.GetCart(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp GetCartResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, 0, resp.TotalItems)
}

// TestIntegration_GetCartNegativeCache verifies negative cache handling
func TestIntegration_GetCartNegativeCache(t *testing.T) {
	env := setupIntegrationEnv(t)
	defer env.teardown()

	deps := createTestDependencies()
	deps.NegativeCache = &MockNegativeCacheManager{
		HasCachedResultFunc: func(ctx context.Context, userID string) (bool, error) {
			return true, nil
		},
	}
	handler := NewCartHandler(deps)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/carts/mine", nil)
	req.Header.Set("X-User-Id", "cached-user")
	w := httptest.NewRecorder()

	handler.GetCart(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp GetCartResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, 0, resp.TotalItems)
}

// TestIntegration_DeleteItemsValidation verifies delete validation
func TestIntegration_DeleteItemsValidation(t *testing.T) {
	env := setupIntegrationEnv(t)
	defer env.teardown()

	// Test missing user ID
	deleteBody := `{"cart_id":"{cart:user:user1}","sku_ids":["sku-001"]}`
	deleteReq := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(deleteBody))
	deleteReq.Header.Set("Content-Type", "application/json")
	deleteW := httptest.NewRecorder()

	env.handler.DeleteItems(deleteW, deleteReq)
	assert.Equal(t, http.StatusBadRequest, deleteW.Code)

	// Test missing cart ID
	deleteReq2 := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(`{"cart_id":"","sku_ids":["sku-001"]}`))
	deleteReq2.Header.Set("Content-Type", "application/json")
	deleteReq2.Header.Set("X-User-Id", "user1")
	deleteW2 := httptest.NewRecorder()

	env.handler.DeleteItems(deleteW2, deleteReq2)
	assert.Equal(t, http.StatusBadRequest, deleteW2.Code)
}

// TestIntegration_DeleteItemsSuccess verifies successful delete
func TestIntegration_DeleteItemsSuccess(t *testing.T) {
	env := setupIntegrationEnv(t)
	defer env.teardown()

	deleteBody := `{"cart_id":"{cart:user:user123}","sku_ids":["sku-001","sku-002"]}`
	deleteReq := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(deleteBody))
	deleteReq.Header.Set("Content-Type", "application/json")
	deleteReq.Header.Set("X-User-Id", "user123")
	deleteW := httptest.NewRecorder()

	env.handler.DeleteItems(deleteW, deleteReq)

	assert.Equal(t, http.StatusOK, deleteW.Code)

	var resp DeleteItemsResponse
	err := json.Unmarshal(deleteW.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.True(t, resp.Success)
}

// TestIntegration_UserMismatch verifies user ID mismatch is rejected
func TestIntegration_UserMismatch(t *testing.T) {
	env := setupIntegrationEnv(t)
	defer env.teardown()

	deleteBody := `{"cart_id":"{cart:user:user1}","sku_ids":["sku-001"]}`
	deleteReq := httptest.NewRequest(http.MethodDelete, "/api/v1/carts/items", strings.NewReader(deleteBody))
	deleteReq.Header.Set("Content-Type", "application/json")
	deleteReq.Header.Set("X-User-Id", "different-user") // Different from cart user
	deleteW := httptest.NewRecorder()

	env.handler.DeleteItems(deleteW, deleteReq)
	assert.Equal(t, http.StatusForbidden, deleteW.Code)
}
