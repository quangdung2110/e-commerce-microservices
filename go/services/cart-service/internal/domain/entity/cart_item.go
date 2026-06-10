package entity

import (
	"errors"
	"time"
)

// CartItem represents a single product variant in a cart.
type CartItem struct {
	itemID          string
	cartID          string
	productID       string
	skuID           string
	quantity        int
	unitPriceAtAdd  float64
	productSnapshot ProductSnapshot
	redisStreamID   string
	createdAt       time.Time
	updatedAt       time.Time
}

// ProductSnapshot is a value object for product display data.
// Stored as JSONB in PostgreSQL; stored as JSON string in Redis Hash.
type ProductSnapshot struct {
	ProductID   string `json:"product_id"`
	ProductName string `json:"product_name"`
	VariantInfo string `json:"variant_info,omitempty"`
	ImageURL    string `json:"image_url,omitempty"`
	IsAvailable bool   `json:"is_available"`
	AddedAt     string `json:"added_at"`
	UpdatedAt   string `json:"updated_at"`
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
		itemID:          cartID + ":" + skuID,
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
func (ci *CartItem) ItemID()         string          { return ci.itemID }
func (ci *CartItem) CartID()         string          { return ci.cartID }
func (ci *CartItem) ProductID()      string          { return ci.productID }
func (ci *CartItem) SkuID()          string          { return ci.skuID }
func (ci *CartItem) Quantity()       int             { return ci.quantity }
func (ci *CartItem) UnitPriceAtAdd() float64         { return ci.unitPriceAtAdd }
func (ci *CartItem) Snapshot()       ProductSnapshot { return ci.productSnapshot }
func (ci *CartItem) StreamID()       string          { return ci.redisStreamID }
func (ci *CartItem) CreatedAt()     time.Time       { return ci.createdAt }
func (ci *CartItem) UpdatedAt()     time.Time       { return ci.updatedAt }

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

// Ensure errors are used to avoid compiler warnings
var (
	_ = errors.New
	_ = time.Now
)
