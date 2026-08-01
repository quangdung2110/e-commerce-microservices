package integration

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/port"
	"cart-service/internal/domain/service"
	redisinfra "cart-service/internal/infrastructure/redis"
	"cart-service/test/mocks"
)

// TestMain sets up the test environment.
func TestMain(m *testing.M) {
	os.Exit(m.Run())
}

// newRedisClient creates a go-redis client connected to miniredis.
func newRedisClient(t *testing.T, mr *miniredis.Miniredis) *redis.Client {
	t.Helper()
	rc := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	return rc
}

// --- Test Cart Add Item ---

func TestAddItem_Integration(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("failed to start miniredis: %v", err)
	}
	defer mr.Close()

	rc := newRedisClient(t, mr)
	defer rc.Close()

	sm := redisinfra.NewShardManager(4)
	luaRepo := redisinfra.NewCartLuaRepo(redisinfra.NewClientFromRaw(rc), sm)
	kb := keybuilder.New(4)

	product := &mocks.ProductClientMock{
		GetProductSkuFunc: func(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
			return &entity.SkuInfo{
				ProductID:    "prod-1",
				ProductName:  "Integration Test Product",
				CurrentPrice: 150000.0,
				IsAvailable:  true,
				AvailableQty: 100,
			}, nil
		},
	}

	cache := &mocks.CacheRepoMock{
		DeleteNegativeCacheFunc: func(ctx context.Context, userID string) error {
			return nil
		},
	}

	cfg := service.CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	svc := service.NewCartServiceWithKB(luaRepo, cache, &mocks.CartRepoMock{}, product, cfg, kb)

	result, err := svc.AddItem(context.Background(), "user1", &service.AddItemRequest{
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

	// Verify item was written to Redis
	cartKey := kb.CartKey("user1")
	val, err := rc.HGet(context.Background(), cartKey, "sku-001").Result()
	if err != nil {
		t.Fatalf("HGet failed: %v", err)
	}
	if val == "" {
		t.Error("item not found in Redis")
	}

	// Verify stream event was written
	streamKey := kb.StreamShardKey("user1")
	streamLen, _ := rc.XLen(context.Background(), streamKey).Result()
	if streamLen == 0 {
		t.Error("event not found in stream")
	}
}

// --- Test Cart Get Cart (Cache Hit) ---

func TestGetCart_CacheHit_Integration(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("failed to start miniredis: %v", err)
	}
	defer mr.Close()

	rc := newRedisClient(t, mr)
	defer rc.Close()

	sm := redisinfra.NewShardManager(4)
	luaRepo := redisinfra.NewCartLuaRepo(redisinfra.NewClientFromRaw(rc), sm)
	kb := keybuilder.New(4)

	// Pre-populate Redis with items
	cartKey := kb.CartKey("user1")
	nowMs := fmt.Sprintf("%d", time.Now().UnixMilli())
	item1 := map[string]interface{}{
		"product_id":        "prod-001",
		"quantity":          2,
		"unit_price_at_add": 100000.0,
		"product_name":      "Product 1",
		"is_available":      true,
		"added_at":         nowMs,
		"updated_at":       nowMs,
	}
	item2 := map[string]interface{}{
		"product_id":        "prod-002",
		"quantity":          1,
		"unit_price_at_add": 200000.0,
		"product_name":      "Product 2",
		"is_available":      true,
		"added_at":         nowMs,
		"updated_at":       nowMs,
	}
	item1JSON, _ := json.Marshal(item1)
	item2JSON, _ := json.Marshal(item2)
	rc.HSet(context.Background(), cartKey, "sku-001", string(item1JSON))
	rc.HSet(context.Background(), cartKey, "sku-002", string(item2JSON))

	cache := &mocks.CacheRepoMock{
		HasNegativeCacheFunc: func(ctx context.Context, userID string) (bool, error) {
			return false, nil
		},
		GetCartFunc: func(ctx context.Context, cartKey string) (map[string]string, error) {
			return rc.HGetAll(ctx, cartKey).Result()
		},
		SetItemAvailabilityFunc: func(ctx context.Context, cartKey, skuID, itemJSON string) error {
			return nil
		},
	}

	product := &mocks.ProductClientMock{
		GetBatchProductSkusFunc: func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
			skus := make(map[string]entity.SkuInfo)
			for _, id := range skuIDs {
				skus[id] = entity.SkuInfo{
					ProductID:    "prod-" + id,
					ProductName:  "Product " + id,
					CurrentPrice: 105000.0,
					IsAvailable:  true,
					AvailableQty: 50,
				}
			}
			return &entity.GrpcResult{Success: true, Skus: skus}, nil
		},
	}

	cfg := service.CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	svc := service.NewCartServiceWithKB(luaRepo, cache, &mocks.CartRepoMock{}, product, cfg, kb)

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if len(view.Items) != 2 {
		t.Errorf("expected 2 items, got %d", len(view.Items))
	}
	if view.TotalUniqueSKUs != 2 {
		t.Errorf("TotalUniqueSKUs = %d, want 2", view.TotalUniqueSKUs)
	}
}

// --- Test Cart Batch Delete ---

func TestBatchDelete_Integration(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("failed to start miniredis: %v", err)
	}
	defer mr.Close()

	rc := newRedisClient(t, mr)
	defer rc.Close()

	sm := redisinfra.NewShardManager(4)
	luaRepo := redisinfra.NewCartLuaRepo(redisinfra.NewClientFromRaw(rc), sm)
	kb := keybuilder.New(4)

	cartKey := kb.CartKey("user1")
	nowMs := fmt.Sprintf("%d", time.Now().UnixMilli())
	for _, sku := range []string{"sku-001", "sku-002", "sku-003"} {
		item := map[string]interface{}{
			"product_id":        "prod-1",
			"quantity":          1,
			"unit_price_at_add": 100000.0,
			"product_name":      "Test Product",
			"is_available":      true,
			"added_at":         nowMs,
			"updated_at":       nowMs,
		}
		itemJSON, _ := json.Marshal(item)
		rc.HSet(context.Background(), cartKey, sku, string(itemJSON))
	}

	cache := &mocks.CacheRepoMock{
		DeleteNegativeCacheFunc: func(ctx context.Context, userID string) error {
			return nil
		},
	}

	cfg := service.CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	svc := service.NewCartServiceWithKB(luaRepo, cache, &mocks.CartRepoMock{}, &mocks.ProductClientMock{}, cfg, kb)

	count, err := svc.BatchDelete(context.Background(), "user1", []string{"sku-001", "sku-002"}, "CART_CLEARED")
	if err != nil {
		t.Fatalf("BatchDelete failed: %v", err)
	}
	if count != 2 {
		t.Errorf("expected 2 deleted, got %d", count)
	}

	// Verify remaining item
	val, err := rc.HGet(context.Background(), cartKey, "sku-003").Result()
	if err != nil {
		t.Fatalf("HGet failed: %v", err)
	}
	if val == "" {
		t.Error("sku-003 should still exist")
	}

	// Verify deleted items are gone
	exists, _ := rc.HExists(context.Background(), cartKey, "sku-001").Result()
	if exists {
		t.Error("sku-001 should have been deleted")
	}
}

// --- Test Cart Rehydration ---

func TestGetCart_CacheMiss_Rehydrate_Integration(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("failed to start miniredis: %v", err)
	}
	defer mr.Close()

	rc := newRedisClient(t, mr)
	defer rc.Close()

	sm := redisinfra.NewShardManager(4)
	luaRepo := redisinfra.NewCartLuaRepo(redisinfra.NewClientFromRaw(rc), sm)
	kb := keybuilder.New(4)

	cache := &mocks.CacheRepoMock{
		HasNegativeCacheFunc: func(ctx context.Context, userID string) (bool, error) {
			return false, nil
		},
		GetCartFunc: func(ctx context.Context, cartKey string) (map[string]string, error) {
			return nil, nil // cache miss
		},
		SetNegativeCacheFunc: func(ctx context.Context, userID string) error {
			return nil
		},
	}

	cartRepo := &mocks.CartRepoMock{
		GetCartStatusFunc: func(ctx context.Context, cartID string) (entity.CartStatus, error) {
			return entity.CartStatusActive, nil
		},
		GetCartItemsFunc: func(ctx context.Context, cartID string) ([]*entity.CartItem, error) {
			snap := entity.ProductSnapshot{
				ProductID:   "prod-rehyd",
				ProductName: "Rehydrated Product",
				IsAvailable: true,
				AddedAt:    "1708530000000",
				UpdatedAt:  "1708530000000",
			}
			item, _ := entity.NewCartItem(cartID, "prod-rehyd", "sku-rehyd", 3, 200000.0, snap)
			return []*entity.CartItem{item}, nil
		},
	}

	product := &mocks.ProductClientMock{
		GetBatchProductSkusFunc: func(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
			skus := make(map[string]entity.SkuInfo)
			for _, id := range skuIDs {
				skus[id] = entity.SkuInfo{
					ProductID:    "prod-rehyd",
					ProductName:  "Rehydrated Product",
					CurrentPrice: 205000.0,
					IsAvailable: true,
					AvailableQty: 10,
				}
			}
			return &entity.GrpcResult{Success: true, Skus: skus}, nil
		},
	}

	cfg := service.CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	svc := service.NewCartServiceWithKB(luaRepo, cache, cartRepo, product, cfg, kb)

	view, err := svc.GetCart(context.Background(), "user1")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if len(view.Items) != 1 {
		t.Errorf("expected 1 item after rehydration, got %d", len(view.Items))
	}
	if view.Items[0].SkuID != "sku-rehyd" {
		t.Errorf("SkuID = %q, want sku-rehyd", view.Items[0].SkuID)
	}

	// Verify item was written back to Redis
	cartKey := kb.CartKey("user1")
	val, err := rc.HGet(context.Background(), cartKey, "sku-rehyd").Result()
	if err != nil {
		t.Fatalf("rehydrated item should be in Redis: %v", err)
	}
	if val == "" {
		t.Error("rehydrated item should be in Redis")
	}
}

// --- Test Negative Cache ---

func TestGetCart_NegativeCache_Integration(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("failed to start miniredis: %v", err)
	}
	defer mr.Close()

	rc := newRedisClient(t, mr)
	defer rc.Close()

	sm := redisinfra.NewShardManager(4)
	luaRepo := redisinfra.NewCartLuaRepo(redisinfra.NewClientFromRaw(rc), sm)
	kb := keybuilder.New(4)

	cache := &mocks.CacheRepoMock{
		HasNegativeCacheFunc: func(ctx context.Context, userID string) (bool, error) {
			return true, nil // negative cache hit
		},
	}

	cfg := service.CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	svc := service.NewCartServiceWithKB(luaRepo, cache, &mocks.CartRepoMock{}, &mocks.ProductClientMock{}, cfg, kb)

	view, err := svc.GetCart(context.Background(), "nonexistent-user")
	if err != nil {
		t.Fatalf("GetCart with negative cache failed: %v", err)
	}
	if len(view.Items) != 0 {
		t.Errorf("expected empty cart, got %d items", len(view.Items))
	}
	if view.TotalUniqueSKUs != 0 {
		t.Errorf("TotalUniqueSKUs = %d, want 0", view.TotalUniqueSKUs)
	}
}

// --- Test Concurrent Add Item ---

func TestAddItem_Concurrent_Integration(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("failed to start miniredis: %v", err)
	}
	defer mr.Close()

	rc := newRedisClient(t, mr)
	defer rc.Close()

	sm := redisinfra.NewShardManager(4)
	luaRepo := redisinfra.NewCartLuaRepo(redisinfra.NewClientFromRaw(rc), sm)
	kb := keybuilder.New(4)

	cache := &mocks.CacheRepoMock{
		DeleteNegativeCacheFunc: func(ctx context.Context, userID string) error {
			return nil
		},
	}

	product := &mocks.ProductClientMock{
		GetProductSkuFunc: func(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
			return &entity.SkuInfo{
				ProductID:    "prod-1",
				ProductName:  "Test Product",
				CurrentPrice: 150000.0,
				IsAvailable:  true,
				AvailableQty: 1000,
			}, nil
		},
	}

	cfg := service.CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	svc := service.NewCartServiceWithKB(luaRepo, cache, &mocks.CartRepoMock{}, product, cfg, kb)

	// Simulate 10 concurrent adds of the same item
	done := make(chan error, 10)
	for i := 0; i < 10; i++ {
		go func(idx int) {
			_, err := svc.AddItem(context.Background(), "concurrent-user", &service.AddItemRequest{
				SkuID:    "sku-concurrent",
				Quantity: 1,
			})
			done <- err
		}(i)
	}

	for i := 0; i < 10; i++ {
		err := <-done
		if err != nil {
			t.Errorf("concurrent add %d failed: %v", i, err)
		}
	}

	// Verify final quantity is exactly 10
	cartKey := kb.CartKey("concurrent-user")
	val, err := rc.HGet(context.Background(), cartKey, "sku-concurrent").Result()
	if err != nil {
		t.Fatalf("HGet failed: %v", err)
	}

	var item map[string]interface{}
	json.Unmarshal([]byte(val), &item)
	if int(item["quantity"].(float64)) != 10 {
		t.Errorf("expected quantity 10 after concurrent adds, got %v", item["quantity"])
	}
}

// --- Test Empty Cart Response ---

func TestGetCart_EmptyCart_Integration(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatalf("failed to start miniredis: %v", err)
	}
	defer mr.Close()

	rc := newRedisClient(t, mr)
	defer rc.Close()

	sm := redisinfra.NewShardManager(4)
	luaRepo := redisinfra.NewCartLuaRepo(redisinfra.NewClientFromRaw(rc), sm)
	kb := keybuilder.New(4)

	cache := &mocks.CacheRepoMock{
		HasNegativeCacheFunc: func(ctx context.Context, userID string) (bool, error) {
			return false, nil
		},
		GetCartFunc: func(ctx context.Context, cartKey string) (map[string]string, error) {
			return map[string]string{}, nil // empty
		},
	}

	cartRepo := &mocks.CartRepoMock{
		GetCartStatusFunc: func(ctx context.Context, cartID string) (entity.CartStatus, error) {
			return entity.CartStatus(""), port.ErrDBNoRows
		},
	}

	cfg := service.CartServiceConfig{
		MaxSKUsPerCart:   50,
		MaxQtyPerItem:    100,
		RehydrateTTL:     604800,
		NegativeCacheTTL: 60,
	}
	svc := service.NewCartServiceWithKB(luaRepo, cache, cartRepo, &mocks.ProductClientMock{}, cfg, kb)

	view, err := svc.GetCart(context.Background(), "empty-user")
	if err != nil {
		t.Fatalf("GetCart failed: %v", err)
	}
	if len(view.Items) != 0 {
		t.Errorf("expected 0 items, got %d", len(view.Items))
	}
}

// --- Test Cart Status Transitions ---

func TestCartStatus_Transitions_Integration(t *testing.T) {
	cart := entity.NewCart("{cart}:user1", "user1")

	// ACTIVE -> ABANDONED
	err := cart.TransitionTo(entity.CartStatusAbandoned, "1000-0")
	if err != nil {
		t.Fatalf("TransitionTo ABANDONED failed: %v", err)
	}
	if cart.Status() != entity.CartStatusAbandoned {
		t.Errorf("Status = %v, want ABANDONED", cart.Status())
	}

	// ABANDONED -> ACTIVE
	err = cart.TransitionTo(entity.CartStatusActive, "1001-0")
	if err != nil {
		t.Fatalf("TransitionTo ACTIVE failed: %v", err)
	}
	if cart.Status() != entity.CartStatusActive {
		t.Errorf("Status = %v, want ACTIVE", cart.Status())
	}
}
