package mocks

import (
	"context"
	"time"

	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/port"
)

// --- CartLuaRepository ---

// CartLuaRepoMock is a mock implementation of port.CartLuaRepository.
type CartLuaRepoMock struct {
	AddOrUpdateItemFunc func(ctx context.Context, cartKey, streamShard, skuID, itemJSON string, maxQty, maxSkus, step int, eventTimeMs int64, userID string) (string, error)
	BatchDeleteFunc     func(ctx context.Context, cartKey, streamShard string, timestampMs int64, eventType, userID, skuIDsJSON string) (int, error)
	RehydrateFunc       func(ctx context.Context, cartKey string, itemsJSON string, ttlSeconds int) error
}

func (m *CartLuaRepoMock) AddOrUpdateItem(ctx context.Context, cartKey, streamShard, skuID, itemJSON string, maxQty, maxSkus, step int, eventTimeMs int64, userID string) (string, error) {
	if m.AddOrUpdateItemFunc != nil {
		return m.AddOrUpdateItemFunc(ctx, cartKey, streamShard, skuID, itemJSON, maxQty, maxSkus, step, eventTimeMs, userID)
	}
	return "", nil
}

func (m *CartLuaRepoMock) BatchDelete(ctx context.Context, cartKey, streamShard string, timestampMs int64, eventType, userID, skuIDsJSON string) (int, error) {
	if m.BatchDeleteFunc != nil {
		return m.BatchDeleteFunc(ctx, cartKey, streamShard, timestampMs, eventType, userID, skuIDsJSON)
	}
	return 0, nil
}

func (m *CartLuaRepoMock) Rehydrate(ctx context.Context, cartKey string, itemsJSON string, ttlSeconds int) error {
	if m.RehydrateFunc != nil {
		return m.RehydrateFunc(ctx, cartKey, itemsJSON, ttlSeconds)
	}
	return nil
}

// --- CacheRepository ---

// CacheRepoMock is a mock implementation of port.CacheRepository.
type CacheRepoMock struct {
	GetCartFunc             func(ctx context.Context, cartKey string) (map[string]string, error)
	DeleteNegativeCacheFunc func(ctx context.Context, userID string) error
	SetNegativeCacheFunc    func(ctx context.Context, userID string) error
	HasNegativeCacheFunc    func(ctx context.Context, userID string) (bool, error)
	SetItemAvailabilityFunc func(ctx context.Context, cartKey, skuID, itemJSON string) error
	PingFunc                func(ctx context.Context) error
}

func (m *CacheRepoMock) GetCart(ctx context.Context, cartKey string) (map[string]string, error) {
	if m.GetCartFunc != nil {
		return m.GetCartFunc(ctx, cartKey)
	}
	return nil, nil
}

func (m *CacheRepoMock) DeleteNegativeCache(ctx context.Context, userID string) error {
	if m.DeleteNegativeCacheFunc != nil {
		return m.DeleteNegativeCacheFunc(ctx, userID)
	}
	return nil
}

func (m *CacheRepoMock) SetNegativeCache(ctx context.Context, userID string) error {
	if m.SetNegativeCacheFunc != nil {
		return m.SetNegativeCacheFunc(ctx, userID)
	}
	return nil
}

func (m *CacheRepoMock) HasNegativeCache(ctx context.Context, userID string) (bool, error) {
	if m.HasNegativeCacheFunc != nil {
		return m.HasNegativeCacheFunc(ctx, userID)
	}
	return false, nil
}

func (m *CacheRepoMock) SetItemAvailability(ctx context.Context, cartKey, skuID, itemJSON string) error {
	if m.SetItemAvailabilityFunc != nil {
		return m.SetItemAvailabilityFunc(ctx, cartKey, skuID, itemJSON)
	}
	return nil
}

func (m *CacheRepoMock) Ping(ctx context.Context) error {
	if m.PingFunc != nil {
		return m.PingFunc(ctx)
	}
	return nil
}

// --- CartRepository ---

// CartRepoMock is a mock implementation of port.CartRepository.
type CartRepoMock struct {
	GetCartStatusFunc                func(ctx context.Context, cartID string) (entity.CartStatus, error)
	ListCartsForAbandonedMarkFunc   func(ctx context.Context, inactiveDays, limit int) ([]*entity.Cart, error)
	UpdateCartStatusFunc             func(ctx context.Context, cartID string, status entity.CartStatus, streamID string) (int64, error)
	UpsertCartItemFunc               func(ctx context.Context, item *entity.CartItem, streamID string) error
	BatchUpsertCartItemsFunc         func(ctx context.Context, cartID string, items []*entity.CartItem, streamIDs []string) error
	DeleteCartItemFunc               func(ctx context.Context, itemID, streamID string) (int64, error)
	BatchDeleteCartItemsFunc         func(ctx context.Context, itemIDs []string, streamID string) (int64, error)
	UpsertCartMasterFunc             func(ctx context.Context, cart *entity.Cart, streamID string) error
	ListCartsForCleanupFunc          func(ctx context.Context, olderThan time.Time, statuses []entity.CartStatus, limit int) ([]string, error)
	HardDeleteCartFunc               func(ctx context.Context, cartID string) error
	ListPendingAbandonedEventsFunc   func(ctx context.Context, olderThan time.Time) ([]*entity.Cart, error)
	MarkAbandonedEventPublishedFunc  func(ctx context.Context, cartID string) error
	GetCartItemsFunc                 func(ctx context.Context, cartID string) ([]*entity.CartItem, error)
	UpsertCartItemWithPayloadFunc    func(ctx context.Context, cartID string, payload map[string]interface{}, streamID string) error
	UpsertCartMasterWithStatusFunc   func(ctx context.Context, userID string, status entity.CartStatus, streamID string, updatedAt time.Time) error
}

func (m *CartRepoMock) GetCartStatus(ctx context.Context, cartID string) (entity.CartStatus, error) {
	if m.GetCartStatusFunc != nil {
		return m.GetCartStatusFunc(ctx, cartID)
	}
	return entity.CartStatusActive, nil
}

func (m *CartRepoMock) ListCartsForAbandonedMark(ctx context.Context, inactiveDays int, limit int) ([]*entity.Cart, error) {
	if m.ListCartsForAbandonedMarkFunc != nil {
		return m.ListCartsForAbandonedMarkFunc(ctx, inactiveDays, limit)
	}
	return nil, nil
}

func (m *CartRepoMock) UpdateCartStatus(ctx context.Context, cartID string, status entity.CartStatus, streamID string) (int64, error) {
	if m.UpdateCartStatusFunc != nil {
		return m.UpdateCartStatusFunc(ctx, cartID, status, streamID)
	}
	return 0, nil
}

func (m *CartRepoMock) UpsertCartItem(ctx context.Context, item *entity.CartItem, streamID string) error {
	if m.UpsertCartItemFunc != nil {
		return m.UpsertCartItemFunc(ctx, item, streamID)
	}
	return nil
}

func (m *CartRepoMock) BatchUpsertCartItems(ctx context.Context, cartID string, items []*entity.CartItem, streamIDs []string) error {
	if m.BatchUpsertCartItemsFunc != nil {
		return m.BatchUpsertCartItemsFunc(ctx, cartID, items, streamIDs)
	}
	return nil
}

func (m *CartRepoMock) DeleteCartItem(ctx context.Context, itemID, streamID string) (int64, error) {
	if m.DeleteCartItemFunc != nil {
		return m.DeleteCartItemFunc(ctx, itemID, streamID)
	}
	return 0, nil
}

func (m *CartRepoMock) BatchDeleteCartItems(ctx context.Context, itemIDs []string, streamID string) (int64, error) {
	if m.BatchDeleteCartItemsFunc != nil {
		return m.BatchDeleteCartItemsFunc(ctx, itemIDs, streamID)
	}
	return 0, nil
}

func (m *CartRepoMock) UpsertCartMaster(ctx context.Context, cart *entity.Cart, streamID string) error {
	if m.UpsertCartMasterFunc != nil {
		return m.UpsertCartMasterFunc(ctx, cart, streamID)
	}
	return nil
}

func (m *CartRepoMock) ListCartsForCleanup(ctx context.Context, olderThan time.Time, statuses []entity.CartStatus, limit int) ([]string, error) {
	if m.ListCartsForCleanupFunc != nil {
		return m.ListCartsForCleanupFunc(ctx, olderThan, statuses, limit)
	}
	return nil, nil
}

func (m *CartRepoMock) HardDeleteCart(ctx context.Context, cartID string) error {
	if m.HardDeleteCartFunc != nil {
		return m.HardDeleteCartFunc(ctx, cartID)
	}
	return nil
}

func (m *CartRepoMock) ListPendingAbandonedEvents(ctx context.Context, olderThan time.Time) ([]*entity.Cart, error) {
	if m.ListPendingAbandonedEventsFunc != nil {
		return m.ListPendingAbandonedEventsFunc(ctx, olderThan)
	}
	return nil, nil
}

func (m *CartRepoMock) MarkAbandonedEventPublished(ctx context.Context, cartID string) error {
	if m.MarkAbandonedEventPublishedFunc != nil {
		return m.MarkAbandonedEventPublishedFunc(ctx, cartID)
	}
	return nil
}

func (m *CartRepoMock) GetCartItems(ctx context.Context, cartID string) ([]*entity.CartItem, error) {
	if m.GetCartItemsFunc != nil {
		return m.GetCartItemsFunc(ctx, cartID)
	}
	return nil, nil
}

func (m *CartRepoMock) UpsertCartItemWithPayload(ctx context.Context, cartID string, payload map[string]interface{}, streamID string) error {
	if m.UpsertCartItemWithPayloadFunc != nil {
		return m.UpsertCartItemWithPayloadFunc(ctx, cartID, payload, streamID)
	}
	return nil
}

func (m *CartRepoMock) UpsertCartMasterWithStatus(ctx context.Context, userID string, status entity.CartStatus, streamID string, updatedAt time.Time) error {
	if m.UpsertCartMasterWithStatusFunc != nil {
		return m.UpsertCartMasterWithStatusFunc(ctx, userID, status, streamID, updatedAt)
	}
	return nil
}

// --- ProductClient ---

// ProductClientMock is a mock implementation of port.ProductClient.
type ProductClientMock struct {
	GetProductSkuFunc      func(ctx context.Context, skuID string) (*entity.SkuInfo, error)
	GetBatchProductSkusFunc func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error)
	CloseFunc              func() error
	IsHealthyFunc          func(ctx context.Context) bool
}

func (m *ProductClientMock) GetProductSku(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
	if m.GetProductSkuFunc != nil {
		return m.GetProductSkuFunc(ctx, skuID)
	}
	return &entity.SkuInfo{
		ProductID:    "prod-1",
		ProductName:  "Test Product",
		CurrentPrice: 150000.0,
		IsAvailable:  true,
		AvailableQty: 100,
	}, nil
}

func (m *ProductClientMock) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
	if m.GetBatchProductSkusFunc != nil {
		return m.GetBatchProductSkusFunc(ctx, skuIDs)
	}
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

func (m *ProductClientMock) Close() error {
	if m.CloseFunc != nil {
		return m.CloseFunc()
	}
	return nil
}

func (m *ProductClientMock) IsHealthy(ctx context.Context) bool {
	if m.IsHealthyFunc != nil {
		return m.IsHealthyFunc(ctx)
	}
	return true
}

// --- KafkaPublisher ---

// KafkaPublisherMock is a mock implementation of port.KafkaPublisher.
type KafkaPublisherMock struct {
	PublishFunc func(ctx context.Context, msg *port.CartEventMessage) error
	CloseFunc   func() error
}

func (m *KafkaPublisherMock) Publish(ctx context.Context, msg *port.CartEventMessage) error {
	if m.PublishFunc != nil {
		return m.PublishFunc(ctx, msg)
	}
	return nil
}

func (m *KafkaPublisherMock) Close() error {
	if m.CloseFunc != nil {
		return m.CloseFunc()
	}
	return nil
}

// --- OutboxWriter ---

// OutboxWriterMock is a mock implementation of port.OutboxWriter.
type OutboxWriterMock struct {
	WriteEventFunc func(ctx context.Context, shardID int, userID string, eventType string, payloadJSON string) (string, error)
	PingFunc       func(ctx context.Context) error
}

func (m *OutboxWriterMock) WriteEvent(ctx context.Context, shardID int, userID string, eventType string, payloadJSON string) (string, error) {
	if m.WriteEventFunc != nil {
		return m.WriteEventFunc(ctx, shardID, userID, eventType, payloadJSON)
	}
	return "1000-0", nil
}

func (m *OutboxWriterMock) Ping(ctx context.Context) error {
	if m.PingFunc != nil {
		return m.PingFunc(ctx)
	}
	return nil
}

// --- HealthChecker ---

// HealthCheckerMock is a mock implementation of port.HealthChecker.
type HealthCheckerMock struct {
	PingFunc func(ctx context.Context) error
}

func (m *HealthCheckerMock) Ping(ctx context.Context) error {
	if m.PingFunc != nil {
		return m.PingFunc(ctx)
	}
	return nil
}
