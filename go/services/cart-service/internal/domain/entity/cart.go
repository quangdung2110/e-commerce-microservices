package entity

import (
	"encoding/json"
	"time"
)

// Cart represents a shopping cart stored in Redis
type Cart struct {
	CartID      string    `json:"cart_id"`
	UserID      string    `json:"user_id"`
	Status      string    `json:"status"`
	Items       []CartItem `json:"items,omitempty"`
	LastUpdated time.Time `json:"last_updated"`
}

// CartItem represents an item in the shopping cart
type CartItem struct {
	// Stored in Redis Hash Value as JSON
	SkuID          string `json:"sku_id"`
	ProductID      string `json:"product_id"`
	Quantity       int    `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	ProductName    string `json:"product_name"`
	VariantInfo   string `json:"variant_info"`
	ImageURL      string `json:"image_url"`
	IsAvailable   bool   `json:"is_available"`
	AddedAt       string `json:"added_at"` // Epoch milliseconds as string
	UpdatedAt     string `json:"updated_at"` // Epoch milliseconds as string

	// Runtime fields (not stored in Redis, computed at read time)
	CurrentPrice float64 `json:"current_price,omitempty"`
}

// CartItemPayload is used for Add/Update operations
type CartItemPayload struct {
	SkuID          string  `json:"sku_id"`
	ProductID      string  `json:"product_id"`
	Quantity       int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	ProductName    string  `json:"product_name"`
	VariantInfo   string  `json:"variant_info"`
	ImageURL      string  `json:"image_url"`
	IsAvailable   bool    `json:"is_available"`
	AddedAt       string  `json:"added_at"`
	UpdatedAt     string  `json:"updated_at"`
}

// ToJSON converts CartItemPayload to JSON string
func (p *CartItemPayload) ToJSON() (string, error) {
	data, err := json.Marshal(p)
	return string(data), err
}

// CartItemFromJSON creates CartItemPayload from JSON string
func CartItemFromJSON(data string) (*CartItemPayload, error) {
	var payload CartItemPayload
	err := json.Unmarshal([]byte(data), &payload)
	if err != nil {
		return nil, err
	}
	return &payload, nil
}

// CartResponse represents the API response for getting cart
type CartResponse struct {
	CartID          string              `json:"cart_id"`
	Items           []CartItemGroup     `json:"items"`
	IsPriceDegraded bool                `json:"is_price_degraded"`
	TotalItems      int                 `json:"total_items"`
	TotalUniqueSKUs int                `json:"total_unique_skus"`
}

// CartItemGroup groups items by latest_item_at
type CartItemGroup struct {
	LatestItemAt int64        `json:"latest_item_at"`
	Items       []CartItemResponse `json:"items"`
}

// CartItemResponse is the response format for a single cart item
type CartItemResponse struct {
	SkuID        string  `json:"sku_id"`
	ProductName  string  `json:"product_name"`
	VariantInfo string  `json:"variant_info"`
	ImageURL    string  `json:"image_url"`
	Quantity    int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	CurrentPrice float64 `json:"current_price,omitempty"`
	IsAvailable bool    `json:"is_available"`
	AddedAt     string  `json:"added_at"`
	UpdatedAt   string  `json:"updated_at"`
}

// AddItemRequest represents the request to add/update an item in cart
type AddItemRequest struct {
	SkuID    string `json:"sku_id" validate:"required"`
	Quantity int    `json:"quantity" validate:"required,min=1"`
}

// AddItemResponse represents the response after adding an item
type AddItemResponse struct {
	Success bool              `json:"success"`
	CartID  string           `json:"cart_id"`
	Item    *CartItemPayload `json:"item,omitempty"`
	Error   string           `json:"error,omitempty"`
}

// DeleteItemsRequest represents the request to delete items from cart
type DeleteItemsRequest struct {
	CartID string   `json:"cart_id" validate:"required"`
	SkuIDs []string `json:"sku_ids" validate:"required,min=1"`
}

// DeleteItemsResponse represents the response after deleting items
type DeleteItemsResponse struct {
	Success      bool   `json:"success"`
	DeletedCount int64  `json:"deleted_count"`
	Error        string `json:"error,omitempty"`
}

// ProductInfo represents product information from Product Service
type ProductInfo struct {
	SkuID          string  `json:"sku_id"`
	ProductID      string  `json:"product_id"`
	ProductName    string  `json:"product_name"`
	VariantInfo   string  `json:"variant_info"`
	ImageURL      string  `json:"image_url"`
	OriginalPrice float64 `json:"original_price"`
	PromotionalPrice float64 `json:"promotional_price"`
	CurrentPrice  float64 `json:"current_price"`
	DisplayQuantity int    `json:"display_quantity"`
	SkuStatus     string  `json:"sku_status"`
	ProductStatus string  `json:"product_status"`
	IsAvailable   bool    `json:"is_available"`
}

// BatchProductSkusResponse represents the response from GetBatchProductSkus gRPC call
type BatchProductSkusResponse struct {
	Products map[string]*ProductInfo `json:"products"`
	Errors   map[string]string     `json:"errors,omitempty"`
}

// RehydrateItem represents an item to rehydrate from DB to Redis
type RehydrateItem struct {
	SkuID  string `json:"sku_id"`
	Value  string `json:"value"`
}

// RehydrateItems is a collection of RehydrateItem for JSON serialization
type RehydrateItems []RehydrateItem

// MergedCartItem represents an item merged from DB and Product Service
type MergedCartItem struct {
	SkuID          string
	Quantity       int
	ProductName    string
	VariantInfo    string
	ImageURL       string
	UnitPriceAtAdd float64
	IsAvailable    bool
	AddedAt       string
	UpdatedAt      string
}

// CartItemRecord represents a cart item from DB
type CartItemRecord struct {
	ItemID         string  `json:"item_id"`
	CartID         string  `json:"cart_id"`
	ProductID      string  `json:"product_id"`
	SkuID          string  `json:"sku_id"`
	Quantity       int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	ProductSnapshot string  `json:"product_snapshot"`
	RedisStreamID  string  `json:"redis_stream_id"`
	CreatedAt      time.Time `json:"created_at"`
	UpdatedAt      time.Time `json:"updated_at"`
}

// GrpcResult wraps gRPC call results
type GrpcResult struct {
	Success   bool
	Products  map[string]*ProductInfo
	Error     error
}
