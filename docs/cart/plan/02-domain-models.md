# Cart Service — Module 02: Domain Models

**Document:** 02 | **Module:** Domain Entities & Value Objects  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Không có (Domain layer — pure logic)

---

## 1. Mục tiêu Module

Định nghĩa các Domain Entities và Value Objects hoàn toàn **thuần túy domain**, không phụ thuộc vào bất kỳ infrastructure nào (Redis, PostgreSQL, Kafka). Module này chỉ chứa business logic và data structures.

---

## 2. Domain Entities

### 2.1. Cart Entity (`internal/domain/entity/cart.go`)

```go
package entity

import (
    "errors"
    "time"
)

// CartStatus represents the lifecycle state of a cart.
// Ref: cart_db_schema.md §2.1
type CartStatus string

const (
    CartStatusActive     CartStatus = "ACTIVE"
    CartStatusAbandoned  CartStatus = "ABANDONED"
    CartStatusDeleted    CartStatus = "DELETED"
    CartStatusCheckedOut CartStatus = "CHECKED_OUT"
)

func (s CartStatus) IsValid() bool {
    switch s {
    case CartStatusActive, CartStatusAbandoned,
         CartStatusDeleted, CartStatusCheckedOut:
        return true
    }
    return false
}

// Cart is the aggregate root for cart operations.
// Ref: cart_db_schema.md §2.1
type Cart struct {
    // CartID is the Redis key: "{cart}:{user_id}"
    // Convention: cart_id == Redis Key (immutable contract)
    cartID               string
    userID               string
    status               CartStatus
    lastEventStreamID    string        // Redis Stream ID for out-of-order guard
    abandonedEventPubAt  *time.Time
    createdAt            time.Time
    updatedAt            time.Time
    items                []*CartItem   // Children (not aggregate root children, managed via value)
}

// NewCart creates a new cart for a user.
func NewCart(cartID, userID string) *Cart {
    now := time.Now().UTC()
    return &Cart{
        cartID:            cartID,
        userID:            userID,
        status:            CartStatusActive,
        lastEventStreamID: "",
        createdAt:         now,
        updatedAt:         now,
        items:             []*CartItem{},
    }
}

// RestoreCart creates a cart from cold storage (PostgreSQL).
func RestoreCart(cartID, userID string, status CartStatus, lastStreamID string, createdAt, updatedAt time.Time) *Cart {
    return &Cart{
        cartID:            cartID,
        userID:            userID,
        status:            status,
        lastEventStreamID: lastStreamID,
        createdAt:         createdAt,
        updatedAt:         updatedAt,
        items:             []*CartItem{},
    }
}

// Getters (immutable access)
func (c *Cart) CartID()       string     { return c.cartID }
func (c *Cart) UserID()       string     { return c.userID }
func (c *Cart) Status()       CartStatus { return c.status }
func (c *Cart) LastStreamID() string     { return c.lastEventStreamID }
func (c *Cart) CreatedAt()   time.Time  { return c.createdAt }
func (c *Cart) UpdatedAt()   time.Time  { return c.updatedAt }
func (c *Cart) Items()       []*CartItem { return c.items }

// CanTransitionTo checks if the cart can transition from current status to next status.
// Business rule: carts can be re-activated from DELETED or CHECKED_OUT via new activity.
func (c *Cart) CanTransitionTo(next CartStatus) bool {
    switch c.status {
    case CartStatusActive:
        return next == CartStatusAbandoned
    case CartStatusAbandoned:
        return next == CartStatusActive // re-activation allowed
    case CartStatusDeleted:
        return next == CartStatusActive // re-activation allowed
    case CartStatusCheckedOut:
        return next == CartStatusActive // re-activation allowed
    }
    return false
}

// TransitionTo transitions the cart to a new status if allowed.
func (c *Cart) TransitionTo(next CartStatus, streamID string) error {
    if !next.IsValid() {
        return ErrInvalidCartStatus
    }
    if c.CanTransitionTo(next) {
        c.status = next
        c.lastEventStreamID = streamID
        c.updatedAt = time.Now().UTC()
        return nil
    }
    return ErrInvalidCartTransition
}

// Reactivate sets status to ACTIVE (used when cart receives new activity after DELETED/CHECKED_OUT).
// Business rule: only update if the incoming stream_id is newer.
func (c *Cart) Reactivate(streamID string) {
    if streamIDNewer(c.lastEventStreamID, streamID) {
        c.status = CartStatusActive
        c.lastEventStreamID = streamID
        c.updatedAt = time.Now().UTC()
    }
}

// MarkAbandoned marks the cart as abandoned.
func (c *Cart) MarkAbandoned() {
    c.status = CartStatusAbandoned
    c.updatedAt = time.Now().UTC()
}

// streamIDNewer returns true if candidate is lexicographically greater than current.
// Redis stream IDs are monotonically increasing: "1708616400456-0" > "1708530000123-0"
func streamIDNewer(current, candidate string) bool {
    return candidate > current
}
```

### 2.2. CartItem Entity (`internal/domain/entity/cart_item.go`)

```go
package entity

import (
    "errors"
    "time"
)

// CartItem represents a single product variant in a cart.
// Ref: cart_caching_schema.md §2.2
// Ref: cart_db_schema.md §2.2
type CartItem struct {
    itemID          string    // Format: {cart_id}:{sku_id}
    cartID          string
    productID       string
    skuID           string
    quantity        int
    unitPriceAtAdd  float64   // Snapshot price when user added to cart
    productSnapshot ProductSnapshot
    redisStreamID   string    // Stream ID for idempotency guard
    createdAt       time.Time
    updatedAt       time.Time
}

// ProductSnapshot is a value object for product display data.
// Ref: cart_idempotency.md §2.3
// Stored as JSONB in PostgreSQL; stored as JSON string in Redis Hash.
type ProductSnapshot struct {
    ProductID   string `json:"product_id"`
    ProductName string `json:"product_name"`
    VariantInfo string `json:"variant_info,omitempty"`
    ImageURL    string `json:"image_url,omitempty"`
    IsAvailable bool   `json:"is_available"`
    AddedAt     string `json:"added_at"`    // Epoch milliseconds as string
    UpdatedAt   string `json:"updated_at"`   // Epoch milliseconds as string
}

// NewCartItem creates a new cart item.
func NewCartItem(cartID, productID, skuID string, quantity int, unitPrice float64, snapshot ProductSnapshot) (*CartItem, error) {
    if quantity <= 0 {
        return nil, ErrInvalidQuantity
    }
    if cartID == "" || skuID == "" {
        return nil, ErrMissingRequiredField
    }
    now := time.Now().UTC()
    return &CartItem{
        itemID:          cartID + ":" + skuID, // item_id format: {cart_id}:{sku_id}
        cartID:          cartID,
        productID:       productID,
        skuID:           skuID,
        quantity:        quantity,
        unitPriceAtAdd:  unitPrice,
        productSnapshot: snapshot,
        createdAt:       now,
        updatedAt:       now,
    }, nil
}

// RestoreCartItem creates a cart item from cold storage.
func RestoreCartItem(itemID, cartID, productID, skuID string, quantity int, unitPrice float64, snapshot ProductSnapshot, streamID string, createdAt, updatedAt time.Time) *CartItem {
    return &CartItem{
        itemID:          itemID,
        cartID:          cartID,
        productID:       productID,
        skuID:           skuID,
        quantity:        quantity,
        unitPriceAtAdd:  unitPrice,
        productSnapshot: snapshot,
        redisStreamID:   streamID,
        createdAt:       createdAt,
        updatedAt:       updatedAt,
    }
}

// Getters
func (ci *CartItem) ItemID()          string           { return ci.itemID }
func (ci *CartItem) CartID()          string           { return ci.cartID }
func (ci *CartItem) ProductID()       string           { return ci.productID }
func (ci *CartItem) SkuID()           string           { return ci.skuID }
func (ci *CartItem) Quantity()        int              { return ci.quantity }
func (ci *CartItem) UnitPriceAtAdd() float64          { return ci.unitPriceAtAdd }
func (ci *CartItem) Snapshot()        ProductSnapshot  { return ci.productSnapshot }
func (ci *CartItem) StreamID()       string           { return ci.redisStreamID }
func (ci *CartItem) CreatedAt()      time.Time        { return ci.createdAt }
func (ci *CartItem) UpdatedAt()      time.Time        { return ci.updatedAt }

// UpdateQuantity updates the item quantity with boundary checks.
func (ci *CartItem) UpdateQuantity(newQty int) error {
    if newQty <= 0 {
        return ErrInvalidQuantity
    }
    if newQty > MaxQuantityPerItem {
        return ErrQuantityExceedsLimit
    }
    ci.quantity = newQty
    ci.updatedAt = time.Now().UTC()
    return nil
}

// IncrementQuantity adds delta to the current quantity.
func (ci *CartItem) IncrementQuantity(delta int) error {
    if delta == 0 {
        return nil
    }
    newQty := ci.quantity + delta
    return ci.UpdateQuantity(newQty)
}

// Constants
const (
    MaxQuantityPerItem = 100
    MaxSKUsPerCart     = 50
)
```

---

## 3. Value Objects

### 3.1. CartItemValue (`internal/domain/entity/cart_item_value.go`)

Value object representing the JSON structure stored in Redis Hash Value.
Ref: `cart_caching_schema.md` §2.2

```go
package entity

// CartItemValue represents the JSON value stored in Redis Hash.
// This is NOT the CartItem entity — it's the serialized form for Redis.
type CartItemValue struct {
    ProductID      string  `json:"product_id"`
    Quantity       int     `json:"quantity"`
    UnitPriceAtAdd float64 `json:"unit_price_at_add"`
    ProductName    string  `json:"product_name"`
    VariantInfo    string  `json:"variant_info,omitempty"`
    ImageURL       string  `json:"image_url,omitempty"`
    IsAvailable    bool    `json:"is_available"`
    AddedAt        string  `json:"added_at"`     // Epoch ms as string
    UpdatedAt      string  `json:"updated_at"`   // Epoch ms as string
}

// ToSnapshot converts to ProductSnapshot for storage.
func (civ *CartItemValue) ToSnapshot() ProductSnapshot {
    return ProductSnapshot{
        ProductID:   civ.ProductID,
        ProductName: civ.ProductName,
        VariantInfo: civ.VariantInfo,
        ImageURL:    civ.ImageURL,
        IsAvailable: civ.IsAvailable,
        AddedAt:     civ.AddedAt,
        UpdatedAt:   civ.UpdatedAt,
    }
}
```

---

## 4. Domain Errors (`internal/domain/entity/errors.go`)

```go
package entity

import "errors"

var (
    // Cart errors
    ErrCartNotFound        = errors.New("cart not found")
    ErrInvalidCartStatus   = errors.New("invalid cart status")
    ErrInvalidCartTransition = errors.New("invalid cart status transition")

    // CartItem errors
    ErrItemNotFound        = errors.New("cart item not found")
    ErrInvalidQuantity     = errors.New("invalid quantity")
    ErrQuantityExceedsLimit = errors.New("quantity exceeds maximum limit")
    ErrCartFull           = errors.New("cart has reached maximum SKU capacity")
    ErrMissingRequiredField = errors.New("missing required field")

    // Business rule errors
    ErrProductUnavailable  = errors.New("product is unavailable")
    ErrProductNotFound     = errors.New("product not found")
    ErrInsufficientStock   = errors.New("insufficient stock")

    // Stream ordering errors
    ErrEventOutOfOrder     = errors.New("event arrived out of order")
    ErrEventAlreadyApplied = errors.New("event already applied (duplicate)")

    // Generic
    ErrInternal            = errors.New("internal error")
)
```

---

## 5. Các Struct hỗ trợ cho Domain

### 5.1. CartView (`internal/domain/entity/cart_view.go`)

Runtime view for API response — includes `current_price` from gRPC pull.

```go
package entity

// CartView is the runtime view of a cart for API response.
// It enriches CartItemValue with current_price from Product Service.
type CartView struct {
    CartID           string         `json:"cart_id"`
    Items            []ItemView    `json:"items"`
    IsPriceDegraded  bool           `json:"is_price_degraded"`
    TotalItems       int            `json:"total_items"`
    TotalUniqueSKUs  int            `json:"total_unique_skus"`
}

// ItemView is the runtime view of a cart item.
type ItemView struct {
    SkuID            string  `json:"sku_id"`
    ProductID        string  `json:"product_id"`
    ProductName      string  `json:"product_name"`
    VariantInfo      string  `json:"variant_info,omitempty"`
    ImageURL         string  `json:"image_url,omitempty"`
    Quantity         int     `json:"quantity"`
    UnitPriceAtAdd   float64 `json:"unit_price_at_add"`
    CurrentPrice     float64 `json:"current_price"`
    IsAvailable      bool    `json:"is_available"`
    AddedAt          string  `json:"added_at"`
    UpdatedAt        string  `json:"updated_at"`
}

// GrpcResult holds the result of a gRPC product fetch.
type GrpcResult struct {
    Success bool
    Skus    map[string]SkuInfo // skuID -> SkuInfo
    Error   string
}

// SkuInfo holds product info from gRPC Product Service.
type SkuInfo struct {
    IsAvailable bool
    CurrentPrice float64
    ProductName  string
    ImageURL     string
}
```

---

## 6. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `cart.go` | `internal/domain/entity/cart.go` | Cart aggregate root |
| `cart_item.go` | `internal/domain/entity/cart_item.go` | CartItem entity + ProductSnapshot VO |
| `cart_item_value.go` | `internal/domain/entity/cart_item_value.go` | Redis Hash value representation |
| `cart_view.go` | `internal/domain/entity/cart_view.go` | Runtime API response view |
| `errors.go` | `internal/domain/entity/errors.go` | Domain errors |

---

## 7. Checklist triển khai Module 02

```markdown
## 02 - Domain Models
- [x] Tạo `internal/domain/entity/cart.go` với Cart entity + CartStatus enum
- [x] Tạo `internal/domain/entity/cart_item.go` với CartItem entity + ProductSnapshot
- [x] Tạo `internal/domain/entity/cart_item_value.go` với CartItemValue value object
- [x] Tạo `internal/domain/entity/cart_view.go` với CartView + ItemView + GrpcResult
- [x] Tạo `internal/domain/entity/errors.go` với tất cả domain errors
- [ ] Viết unit tests cho Cart entity: status transitions, stream ID comparison
- [ ] Viết unit tests cho CartItem entity: quantity validation, boundaries
- [x] Verify domain entities không import bất kỳ infrastructure nào
- [x] Verify `go build ./internal/domain/entity` compile thành công
```

---

## 8. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Cart là aggregate root | CartStatus quản lý vòng đời; items được quản lý qua value object |
| `item_id = cart_id + ":" + sku_id` | Dùng format này để nhất quán với PostgreSQL PK và Redis Hash field |
| `current_price` KHÔNG trong entity | Đây là runtime field từ Product Service, không phải stored data |
| `stream_id` là string comparison | Redis stream IDs tăng lexicographically nên string comparison đủ |
| Timestamps là string epoch ms | Tránh precision loss khi serialize qua JSON (cjson on Redis) |

---

*Tiếp theo: [03-domain-ports.md](03-domain-ports.md) — Interface definitions (contracts).*
