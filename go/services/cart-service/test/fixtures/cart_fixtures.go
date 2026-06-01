package fixtures

import (
	"encoding/json"
	"fmt"
	"strconv"
	"time"

	"cart-service/internal/domain/entity"
)

// --- Cart Fixtures ---

// NewTestCart creates a cart with ACTIVE status.
func NewTestCart(userID string) *entity.Cart {
	return entity.NewCart("{cart}:"+userID, userID)
}

// NewTestCartWithStatus creates a cart with a specific status.
func NewTestCartWithStatus(userID string, status entity.CartStatus, streamID string) *entity.Cart {
	cart := entity.NewCart("{cart}:"+userID, userID)
	if streamID != "" {
		cart.TransitionTo(status, streamID)
	} else if status != entity.CartStatusActive {
		cart.MarkAbandoned()
	}
	return cart
}

// --- CartItem Fixtures ---

// NewTestCartItem creates a cart item with default values.
func NewTestCartItem(cartID, skuID string) *entity.CartItem {
	return newTestCartItemWithQty(cartID, skuID, 1)
}

// newTestCartItemWithQty creates a cart item with a specific quantity.
func newTestCartItemWithQty(cartID, skuID string, quantity int) *entity.CartItem {
	snap := entity.ProductSnapshot{
		ProductID:   "prod-test",
		ProductName: "Test Product",
		VariantInfo: "Size: M",
		ImageURL:    "https://example.com/test.jpg",
		IsAvailable: true,
		AddedAt:    fmt.Sprintf("%d", time.Now().UnixMilli()),
		UpdatedAt:  fmt.Sprintf("%d", time.Now().UnixMilli()),
	}
	item, _ := entity.NewCartItem(cartID, "prod-test", skuID, quantity, 99999.0, snap)
	return item
}

// NewTestCartItemValue creates a CartItemValue for Redis JSON.
func NewTestCartItemValue(skuID string, quantity int) *entity.CartItemValue {
	nowMs := fmt.Sprintf("%d", time.Now().UnixMilli())
	return &entity.CartItemValue{
		ProductID:      "prod-test",
		Quantity:       quantity,
		UnitPriceAtAdd: 150000.0,
		ProductName:    "Test Product",
		VariantInfo:    "Size: M",
		ImageURL:       "https://example.com/test.jpg",
		IsAvailable:    true,
		AddedAt:        nowMs,
		UpdatedAt:      nowMs,
	}
}

// NewTestCartItemValueJSON creates a JSON string for a cart item value.
func NewTestCartItemValueJSON(skuID string, quantity int) string {
	item := NewTestCartItemValue(skuID, quantity)
	data, _ := json.Marshal(item)
	return string(data)
}

// --- SKU Fixtures ---

// NewTestSkuInfo creates a SkuInfo for testing product client.
func NewTestSkuInfo(skuID string) *entity.SkuInfo {
	return &entity.SkuInfo{
		ProductID:    "prod-test",
		VariantInfo:   "Size: M",
		IsAvailable:  true,
		CurrentPrice: 150000.0,
		ProductName:  "Test Product",
		ImageURL:     "https://example.com/test.jpg",
		AvailableQty: 100,
	}
}

// NewUnavailableSkuInfo creates a SkuInfo for an unavailable product.
func NewUnavailableSkuInfo(skuID string) *entity.SkuInfo {
	return &entity.SkuInfo{
		ProductID:    "prod-test",
		VariantInfo:   "Size: M",
		IsAvailable:  false,
		CurrentPrice: 150000.0,
		ProductName:  "Test Product",
		ImageURL:     "https://example.com/test.jpg",
		AvailableQty: 0,
	}
}

// --- CartItemValue JSON Builders ---

// BuildCartItemValueJSON is a helper that creates the full JSON string
// for a cart item, compatible with the Lua script format.
func BuildCartItemValueJSON(skuID string, quantity int, productID, productName string, price float64) string {
	nowMs := fmt.Sprintf("%d", time.Now().UnixMilli())
	item := entity.CartItemValue{
		ProductID:      productID,
		Quantity:       quantity,
		UnitPriceAtAdd: price,
		ProductName:    productName,
		VariantInfo:    "Size: M",
		ImageURL:       "https://example.com/test.jpg",
		IsAvailable:    true,
		AddedAt:        nowMs,
		UpdatedAt:      nowMs,
	}
	data, _ := json.Marshal(item)
	return string(data)
}

// BuildRehydrateJSON is a helper that builds the JSON array format
// expected by the rehydrate Lua script from a map of SKU ID -> JSON value.
func BuildRehydrateJSON(items map[string]string) string {
	if len(items) == 0 {
		return "[]"
	}
	var b []byte
	b = append(b, '[')
	first := true
	for skuID, value := range items {
		if !first {
			b = append(b, ',')
		}
		first = false
		b = append(b, `{"sku_id":`...)
		b = append(b, strconv.AppendQuote(nil, skuID)...)
		b = append(b, `,"value":`...)
		b = append(b, strconv.AppendQuote(nil, value)...)
		b = append(b, `}`...)
	}
	b = append(b, ']')
	return string(b)
}

// BuildSkuIDsJSON builds a JSON array of SKU IDs for batch operations.
func BuildSkuIDsJSON(skuIDs []string) string {
	if len(skuIDs) == 0 {
		return "[]"
	}
	var b []byte
	b = append(b, '[')
	for i, sku := range skuIDs {
		if i > 0 {
			b = append(b, ',')
		}
		b = append(b, strconv.AppendQuote(nil, sku)...)
	}
	b = append(b, ']')
	return string(b)
}

// --- Batch Item Fixtures ---

// NewTestCartWithItems creates a cart with multiple items.
func NewTestCartWithItems(userID string, skuIDs []string, quantities []int) *entity.Cart {
	cart := NewTestCart(userID)
	for i, skuID := range skuIDs {
		qty := 1
		if i < len(quantities) {
			qty = quantities[i]
		}
		item := newTestCartItemWithQty(cart.CartID(), skuID, qty)
		cart.Items()
		_ = item // Items are managed through cart, not stored in cart
	}
	return cart
}

// --- GrpcResult Fixtures ---

// NewTestGrpcResult creates a GrpcResult with test SKU data.
func NewTestGrpcResult(skuIDs []string) *entity.GrpcResult {
	skus := make(map[string]entity.SkuInfo)
	for _, id := range skuIDs {
		skus[id] = *NewTestSkuInfo(id)
	}
	return &entity.GrpcResult{
		Success: true,
		Skus:    skus,
	}
}

// NewFailedGrpcResult creates a GrpcResult indicating failure.
func NewFailedGrpcResult(err string) *entity.GrpcResult {
	return &entity.GrpcResult{
		Success: false,
		Error:   err,
		Skus:    make(map[string]entity.SkuInfo),
	}
}

// --- CartView Fixtures ---

// NewTestCartView creates a CartView with test data.
func NewTestCartView(userID string, itemCount int) *entity.CartView {
	items := make([]entity.ItemView, itemCount)
	for i := 0; i < itemCount; i++ {
		items[i] = entity.ItemView{
			SkuID:          fmt.Sprintf("sku-%03d", i+1),
			ProductID:      "prod-test",
			ProductName:    fmt.Sprintf("Test Product %d", i+1),
			VariantInfo:    "Size: M",
			ImageURL:       fmt.Sprintf("https://example.com/img-%d.jpg", i+1),
			Quantity:       i + 1,
			UnitPriceAtAdd: 150000.0,
			CurrentPrice:   155000.0,
			IsAvailable:    true,
			AddedAt:       fmt.Sprintf("%d", time.Now().UnixMilli()),
			UpdatedAt:     fmt.Sprintf("%d", time.Now().UnixMilli()),
		}
	}

	totalQty := 0
	for _, item := range items {
		totalQty += item.Quantity
	}

	return &entity.CartView{
		CartID:          "{cart}:" + userID,
		Items:           items,
		IsPriceDegraded: false,
		TotalItems:      totalQty,
		TotalUniqueSKUs: len(items),
	}
}

// NewEmptyCartView creates an empty CartView.
func NewEmptyCartView(userID string) *entity.CartView {
	return &entity.CartView{
		CartID:          "{cart}:" + userID,
		Items:           []entity.ItemView{},
		IsPriceDegraded: false,
		TotalItems:      0,
		TotalUniqueSKUs: 0,
	}
}

// --- Stream ID Fixtures ---

// StreamID returns a deterministic stream ID for testing.
func StreamID(timestamp int64, sequence int) string {
	return fmt.Sprintf("%d-%d", timestamp, sequence)
}

// OldStreamID returns an older stream ID for comparison testing.
func OldStreamID() string {
	return "1708530000123-0"
}

// NewStreamID returns a newer stream ID for comparison testing.
func NewStreamID() string {
	return "1708616400456-0"
}
