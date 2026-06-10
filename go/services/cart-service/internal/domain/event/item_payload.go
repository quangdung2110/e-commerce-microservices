package event

// CartItemPayload is the payload for CART_ITEM_ADDED and CART_ITEM_UPDATED events.
// Ref: cart_events.md §2.1, §2.2
// Populated by Lua Script (ARGV) + Backend (item data)
type CartItemPayload struct {
	// These 3 fields are added by Lua script before XADD
	CartID string `json:"cart_id"`
	SkuID  string `json:"sku_id"`
	UserID string `json:"user_id"`

	// These fields come from the item JSON built by the backend
	ProductID      string  `json:"product_id"`
	Quantity       int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	ProductName    string  `json:"product_name"`
	VariantInfo    string  `json:"variant_info,omitempty"`
	ImageURL       string  `json:"image_url,omitempty"`
	IsAvailable    bool    `json:"is_available"`
	AddedAt        string  `json:"added_at"`    // Epoch ms as string
	UpdatedAt      string  `json:"updated_at"`  // Epoch ms as string
}
