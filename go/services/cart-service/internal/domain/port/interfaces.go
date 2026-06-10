package port

import (
	"context"
	"time"

	"cart-service/internal/domain/entity"
)

// Placeholder types for compile-time checks.
// Each placeholder struct must implement its corresponding interface
// so that this file compiles independently of the infrastructure layer.
//
// Real implementations are filled in during Module 06-10 (infrastructure layer).
// These placeholders exist so the port package itself can be validated.

var (
	// CartLuaRepository — implemented by internal/infrastructure/redis/cart_lua_repo.go
	_ CartLuaRepository = (*CartLuaRepositoryImpl)(nil)

	// CacheRepository — implemented by internal/infrastructure/redis/cache_repo.go
	_ CacheRepository = (*CacheRepositoryImpl)(nil)

	// CartRepository — implemented by internal/infrastructure/postgres/cart_repo.go
	_ CartRepository = (*CartRepositoryImpl)(nil)

	// ProductClient — implemented by internal/infrastructure/grpc/product_client.go
	_ ProductClient = (*ProductClientImpl)(nil)

	// KafkaPublisher — implemented by internal/infrastructure/kafka/publisher.go
	_ KafkaPublisher = (*KafkaPublisherImpl)(nil)

	// OutboxWriter — implemented by internal/infrastructure/redis/outbox_writer.go
	_ OutboxWriter = (*OutboxWriterImpl)(nil)
)

// CartLuaRepositoryImpl is a placeholder implementing CartLuaRepository.
type CartLuaRepositoryImpl struct{}

func (*CartLuaRepositoryImpl) AddOrUpdateItem(ctx context.Context, cartKey, streamShard, skuID, itemJSON string, maxQty, maxSkus, step int, eventTimeMs int64, userID string) (string, error) {
	return "", nil
}
func (*CartLuaRepositoryImpl) BatchDelete(ctx context.Context, cartKey, streamShard string, timestampMs int64, eventType, userID, skuIDsJSON string) (int, error) {
	return 0, nil
}
func (*CartLuaRepositoryImpl) Rehydrate(ctx context.Context, cartKey string, itemsJSON string, ttlSeconds int) error {
	return nil
}

// CacheRepositoryImpl is a placeholder implementing CacheRepository.
type CacheRepositoryImpl struct{}

func (*CacheRepositoryImpl) GetCart(ctx context.Context, cartKey string) (map[string]string, error) {
	return nil, nil
}
func (*CacheRepositoryImpl) DeleteNegativeCache(ctx context.Context, userID string) error {
	return nil
}
func (*CacheRepositoryImpl) SetNegativeCache(ctx context.Context, userID string) error {
	return nil
}
func (*CacheRepositoryImpl) HasNegativeCache(ctx context.Context, userID string) (bool, error) {
	return false, nil
}
func (*CacheRepositoryImpl) SetItemAvailability(ctx context.Context, cartKey, skuID, itemJSON string) error {
	return nil
}
func (*CacheRepositoryImpl) Ping(ctx context.Context) error {
	return nil
}

// CartRepositoryImpl is a placeholder implementing CartRepository.
type CartRepositoryImpl struct{}

func (*CartRepositoryImpl) GetCartStatus(ctx context.Context, cartID string) (entity.CartStatus, error) {
	return "", nil
}
func (*CartRepositoryImpl) ListCartsForAbandonedMark(ctx context.Context, inactiveDays, limit int) ([]*entity.Cart, error) {
	return nil, nil
}
func (*CartRepositoryImpl) UpdateCartStatus(ctx context.Context, cartID string, status entity.CartStatus, streamID string) (int64, error) {
	return 0, nil
}
func (*CartRepositoryImpl) UpsertCartItem(ctx context.Context, item *entity.CartItem, streamID string) error {
	return nil
}
func (*CartRepositoryImpl) BatchUpsertCartItems(ctx context.Context, cartID string, items []*entity.CartItem, streamIDs []string) error {
	return nil
}
func (*CartRepositoryImpl) DeleteCartItem(ctx context.Context, itemID, streamID string) (int64, error) {
	return 0, nil
}
func (*CartRepositoryImpl) BatchDeleteCartItems(ctx context.Context, itemIDs []string, streamID string) (int64, error) {
	return 0, nil
}
func (*CartRepositoryImpl) UpsertCartMaster(ctx context.Context, cart *entity.Cart, streamID string) error {
	return nil
}
func (*CartRepositoryImpl) ListCartsForCleanup(ctx context.Context, olderThan time.Time, statuses []entity.CartStatus, limit int) ([]string, error) {
	return nil, nil
}
func (*CartRepositoryImpl) HardDeleteCart(ctx context.Context, cartID string) error {
	return nil
}
func (*CartRepositoryImpl) ListPendingAbandonedEvents(ctx context.Context, olderThan time.Time) ([]*entity.Cart, error) {
	return nil, nil
}
func (*CartRepositoryImpl) MarkAbandonedEventPublished(ctx context.Context, cartID string) error {
	return nil
}
func (*CartRepositoryImpl) GetCartItems(ctx context.Context, cartID string) ([]*entity.CartItem, error) {
	return nil, nil
}
func (*CartRepositoryImpl) UpsertCartItemWithPayload(ctx context.Context, cartID string, payload map[string]interface{}, streamID string) error {
	return nil
}
func (*CartRepositoryImpl) UpsertCartMasterWithStatus(ctx context.Context, userID string, status entity.CartStatus, streamID string, updatedAt time.Time) error {
	return nil
}

// ProductClientImpl is a placeholder implementing ProductClient.
type ProductClientImpl struct{}

func (*ProductClientImpl) GetProductSku(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
	return nil, nil
}
func (*ProductClientImpl) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
	return nil, nil
}
func (*ProductClientImpl) Close() error {
	return nil
}
func (*ProductClientImpl) IsHealthy(ctx context.Context) bool {
	return false
}

// KafkaPublisherImpl is a placeholder implementing KafkaPublisher.
type KafkaPublisherImpl struct{}

func (*KafkaPublisherImpl) Publish(ctx context.Context, msg *CartEventMessage) error {
	return nil
}
func (*KafkaPublisherImpl) Close() error {
	return nil
}

// OutboxWriterImpl is a placeholder implementing OutboxWriter.
type OutboxWriterImpl struct{}

func (*OutboxWriterImpl) WriteEvent(ctx context.Context, shardID int, userID, eventType, payloadJSON string) (string, error) {
	return "", nil
}
func (*OutboxWriterImpl) Ping(ctx context.Context) error {
	return nil
}
