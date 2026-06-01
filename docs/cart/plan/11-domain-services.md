# Cart Service — Module 11: Domain Services

**Document:** 11 | **Module:** Domain Services — Business Logic  
**Status:** `[x]` Đã hoàn thành | `[ ]` Chưa hoàn thành  
**Phụ thuộc:** Module 02, 03, 06, 07, 09

---

## 1. Mục tiêu Module

Implement business logic tại Domain Layer — `CartService`. Service này orchestrates các port interfaces để thực hiện các use cases: AddItem, GetCart, BatchDelete. **KHÔNG chứa infrastructure code** — chỉ orchestrates domain + ports.

---

## 2. Chi tiết triển khai

### 2.1. CartService (`internal/domain/service/cart_service.go`)

```go
package service

import (
    "context"
    "encoding/json"
    "fmt"
    "time"

    "cart-service/internal/common/keybuilder"
    "cart-service/internal/domain/entity"
    "cart-service/internal/domain/event"
    "cart-service/internal/domain/port"
)

// CartService handles cart business logic.
// It orchestrates domain entities and infrastructure ports.
// NO infrastructure code — only domain logic + port orchestration.
type CartService struct {
    luaRepo   port.CartLuaRepository
    cache     port.CacheRepository
    cartRepo  port.CartRepository
    product   port.ProductClient
    cfg       CartServiceConfig
}

// CartServiceConfig holds cart-specific configuration.
type CartServiceConfig struct {
    MaxSKUsPerCart     int
    MaxQtyPerItem     int
    RehydrateTTL      int // seconds
    NegativeCacheTTL  int // seconds
}

// NewCartService creates a new cart service.
func NewCartService(
    luaRepo port.CartLuaRepository,
    cache port.CacheRepository,
    cartRepo port.CartRepository,
    product port.ProductClient,
    cfg CartServiceConfig,
) *CartService {
    return &CartService{
        luaRepo:  luaRepo,
        cache:   cache,
        cartRepo: cartRepo,
        product: product,
        cfg:     cfg,
    }
}
```

### 2.2. AddItem Use Case

```go
// AddItem adds or updates an item in the user's cart.
// Ref: cart_workflow.md §2
func (s *CartService) AddItem(ctx context.Context, userID string, req *AddItemRequest) (*AddItemResult, error) {
    // Step 1: Fetch product data from Product Service
    skuInfo, err := s.product.GetProductSku(ctx, req.SkuID)
    if err != nil {
        if err == port.ErrProductNotFound {
            return nil, entity.ErrProductNotFound
        }
        return nil, fmt.Errorf("fetch product: %w", err)
    }

    // Step 2: Validate product availability
    if !skuInfo.IsAvailable {
        return nil, entity.ErrProductUnavailable
    }

    // Step 3: Validate stock
    if skuInfo.AvailableQty < req.Quantity {
        return nil, entity.ErrInsufficientStock
    }

    // Step 4: Build cart item value (for Redis)
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

    // Step 5: Serialize item to JSON
    itemJSON, err := json.Marshal(itemValue)
    if err != nil {
        return nil, fmt.Errorf("marshal item: %w", err)
    }

    // Step 6: Compute Redis keys with Hash Tags
    cartKey := keybuilder.CartKey(userID)
    shardID := keybuilder.ComputeShardID(userID, 4)
    streamShard := keybuilder.StreamShardKey(shardID)

    // Step 7: Execute Lua script (atomic: HSET + XADD)
    resultJSON, err := s.luaRepo.AddOrUpdateItem(
        ctx,
        cartKey, streamShard,
        req.SkuID, string(itemJSON),
        s.cfg.MaxQtyPerItem, s.cfg.MaxSKUsPerCart,
        req.Quantity, // step
        nowMs, userID,
    )
    if err != nil {
        return nil, fmt.Errorf("add item: %w", err)
    }

    // Step 8: Delete negative cache (invalidate stale empty responses)
    if delErr := s.cache.DeleteNegativeCache(ctx, userID); delErr != nil {
        // Log but don't fail — item was added successfully
        _ = delErr
    }

    // Step 9: Parse result and return
    var updatedItem entity.CartItemValue
    json.Unmarshal([]byte(resultJSON), &updatedItem)

    return &AddItemResult{
        SkuID:     req.SkuID,
        Quantity:  updatedItem.Quantity,
        UpdatedAt: updatedItem.UpdatedAt,
    }, nil
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
```

### 2.3. GetCart Use Case

```go
// GetCart retrieves the user's cart with current prices from Product Service.
// Ref: cart_workflow.md §3
func (s *CartService) GetCart(ctx context.Context, userID string) (*entity.CartView, error) {
    // Step 1: Check negative cache (fast path)
    hasNeg, err := s.cache.HasNegativeCache(ctx, userID)
    if err == nil && hasNeg {
        return &entity.CartView{
            CartID:          keybuilder.CartKey(userID),
            Items:           []entity.ItemView{},
            IsPriceDegraded: false,
            TotalItems:       0,
            TotalUniqueSKUs: 0,
        }, nil
    }

    // Step 2: Fetch from Redis
    cartKey := keybuilder.CartKey(userID)
    hashData, err := s.cache.GetCart(ctx, cartKey)
    if err != nil {
        return nil, fmt.Errorf("fetch cart: %w", err)
    }

    // Step 3: Cache Hit — data found in Redis
    if len(hashData) > 0 {
        return s.buildCartViewFromCache(ctx, userID, cartKey, hashData)
    }

    // Step 4: Cache Miss — try cold storage
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

    // Step: Pull current prices from Product Service
    grpcResult, err := s.product.GetBatchProductSkus(ctx, skuIDs)
    if err != nil {
        grpcResult = &entity.GrpcResult{Success: false, Error: err.Error()}
    }

    // Step: Build response and handle is_available=false write-back
    items := s.buildItemViews(itemValues, grpcResult)

    // Write back is_available=false to Redis (the ONLY exception to read-only TTL)
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
        TotalItems:       totalQty,
        TotalUniqueSKUs: len(items),
    }, nil
}

// rehydrateAndBuildCart handles cache miss by loading from cold storage.
func (s *CartService) rehydrateAndBuildCart(ctx context.Context, userID string) (*entity.CartView, error) {
    cartKey := keybuilder.CartKey(userID)

    // Step 1: Check if cart exists in PostgreSQL
    status, err := s.cartRepo.GetCartStatus(ctx, cartKey)
    if err == port.ErrCartNotFound || (err == nil && status == entity.CartStatusDeleted) ||
        (err == nil && status == entity.CartStatusCheckedOut) {
        // Set negative cache: user has no active cart
        _ = s.cache.SetNegativeCache(ctx, userID)
        return &entity.CartView{
            CartID: cartKey, Items: []entity.ItemView{},
            IsPriceDegraded: false, TotalItems: 0, TotalUniqueSKUs: 0,
        }, nil
    }
    if err != nil {
        return nil, fmt.Errorf("check cart status: %w", err)
    }

    // Step 2: Load items from PostgreSQL
    items, err := s.loadColdItems(ctx, cartKey)
    if err != nil {
        return nil, fmt.Errorf("load cold items: %w", err)
    }
    if len(items) == 0 {
        _ = s.cache.SetNegativeCache(ctx, userID)
        return &entity.CartView{
            CartID: cartKey, Items: []entity.ItemView{},
            IsPriceDegraded: false, TotalItems: 0, TotalUniqueSKUs: 0,
        }, nil
    }

    // Step 3: Fetch product data from gRPC
    skuIDs := make([]string, len(items))
    for i, item := range items {
        skuIDs[i] = item.SkuID
    }
    grpcResult, err := s.product.GetBatchProductSkus(ctx, skuIDs)
    if err != nil {
        grpcResult = &entity.GrpcResult{Success: false, Error: err.Error()}
    }

    // Step 4: Merge data and prepare for rehydrate
    mergedItems := s.mergeProductData(items, grpcResult)

    // Step 5: Rehydrate to Redis (silent Lua script, no XADD)
    rehydrateItems := make([]map[string]string, len(mergedItems))
    for i, item := range mergedItems {
        itemJSON, _ := json.Marshal(item)
        rehydrateItems[i] = map[string]string{
            "sku_id": item.SkuID,
            "value":  string(itemJSON),
        }
    }
    rehydrateJSON, _ := json.Marshal(rehydrateItems)
    if reErr := s.luaRepo.Rehydrate(ctx, cartKey, string(rehydrateJSON), s.cfg.RehydrateTTL); reErr != nil {
        // Log but don't fail — we still have data to return
        _ = reErr
    }

    // Step 6: Build response
    itemValues := make(map[string]*entity.CartItemValue, len(mergedItems))
    for _, item := range mergedItems {
        itemValues[item.SkuID] = &entity.CartItemValue{
            ProductID:      item.ProductID,
            Quantity:       item.Quantity,
            UnitPriceAtAdd: item.UnitPriceAtAdd,
            ProductName:    item.ProductName,
            VariantInfo:    item.VariantInfo,
            ImageURL:       item.ImageURL,
            IsAvailable:    item.IsAvailable,
            AddedAt:        item.AddedAt,
            UpdatedAt:      item.UpdatedAt,
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
        TotalItems:       totalQty,
        TotalUniqueSKUs: len(viewItems),
    }, nil
}

// loadColdItems loads cart items from PostgreSQL.
func (s *CartService) loadColdItems(ctx context.Context, cartID string) ([]*ColdCartItem, error) {
    // This would call the postgres repository to get cart_items
    // For now, returns empty — implemented in Module 07
    return nil, nil
}

// ColdCartItem represents a cart item from cold storage.
type ColdCartItem struct {
    SkuID          string
    ProductID       string
    Quantity        int
    UnitPriceAtAdd float64
    ProductName    string
    VariantInfo    string
    ImageURL       string
    IsAvailable    bool
    AddedAt        string
    UpdatedAt      string
}

// mergeProductData merges product data from gRPC with cold storage items.
func (s *CartService) mergeProductData(items []*ColdCartItem, grpcResult *entity.GrpcResult) []*ColdCartItem {
    for _, item := range items {
        if grpcResult.Success {
            if skuInfo, ok := grpcResult.Skus[item.SkuID]; ok {
                // gRPC returned data
                if !skuInfo.IsAvailable {
                    item.IsAvailable = false // Force false if BANNED
                }
                // If available, keep DB snapshot (DB may be stale but not necessarily unavailable)
            }
        }
        // If grpcResult.Success is false: keep DB snapshot, no merge
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
            SkuID:         skuID,
            ProductID:       item.ProductID,
            Quantity:       item.Quantity,
            UnitPriceAtAdd: item.UnitPriceAtAdd,
            ProductName:    item.ProductName,
            VariantInfo:    item.VariantInfo,
            ImageURL:       item.ImageURL,
            IsAvailable:    item.IsAvailable,
            AddedAt:        item.AddedAt,
            UpdatedAt:      item.UpdatedAt,
            CurrentPrice:   item.UnitPriceAtAdd, // fallback
        }

        if grpcResult.Success {
            if skuInfo, ok := grpcResult.Skus[skuID]; ok {
                view.CurrentPrice = skuInfo.CurrentPrice
                // is_available is already merged in the caller
            }
        }

        views = append(views, view)
    }
    return views
}
```

### 2.4. BatchDelete Use Case

```go
// BatchDelete removes multiple SKUs from a cart.
// Ref: cart_workflow.md §4
func (s *CartService) BatchDelete(ctx context.Context, userID string, skuIDs []string, eventType string) (int, error) {
    cartKey := keybuilder.CartKey(userID)
    shardID := keybuilder.ComputeShardID(userID, 4)
    streamShard := keybuilder.StreamShardKey(shardID)

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

    // Delete negative cache
    if delErr := s.cache.DeleteNegativeCache(ctx, userID); delErr != nil {
        _ = delErr
    }

    return count, nil
}
```

---

## 3. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `cart_service.go` | `internal/domain/service/cart_service.go` | CartService with all use cases |

---

## 4. Checklist triển khai Module 11

```markdown
## 11 - Domain Services
- [ ] Tạo `internal/domain/service/cart_service.go` với CartService struct
- [ ] Implement AddItem use case với full workflow
- [ ] Implement GetCart use case với cache hit/miss branching
- [ ] Implement BatchDelete use case
- [ ] Implement rehydrate pipeline (cache miss → PG → gRPC → Lua → response)
- [ ] Viết unit tests: AddItem with mock product client
- [ ] Viết unit tests: GetCart cache hit path
- [ ] Viết unit tests: GetCart cache miss path (mock cartRepo)
- [ ] Viết unit tests: BatchDelete
- [ ] Viết unit tests: is_available=false write-back logic
- [ ] Verify `go build ./internal/domain/service` compile thành công
```

---

*Tiếp theo: [12-api-dto.md](12-api-dto.md) — DTOs cho HTTP request/response.*
