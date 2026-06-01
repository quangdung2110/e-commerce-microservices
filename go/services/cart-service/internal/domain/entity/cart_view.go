package entity

// CartView is the runtime view of a cart for API response.
type CartView struct {
	CartID          string     `json:"cart_id"`
	Items           []ItemView `json:"items"`
	IsPriceDegraded bool       `json:"is_price_degraded"`
	TotalItems      int        `json:"total_items"`
	TotalUniqueSKUs int        `json:"total_unique_skus"`
}

// ItemView is the runtime view of a cart item.
type ItemView struct {
	SkuID          string  `json:"sku_id"`
	ProductID      string  `json:"product_id"`
	ProductName    string  `json:"product_name"`
	VariantInfo    string  `json:"variant_info,omitempty"`
	ImageURL       string  `json:"image_url,omitempty"`
	Quantity       int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	CurrentPrice   float64 `json:"current_price"`
	IsAvailable    bool    `json:"is_available"`
	AddedAt        string  `json:"added_at"`
	UpdatedAt      string  `json:"updated_at"`
}

// GrpcResult holds the result of a gRPC product fetch.
type GrpcResult struct {
	Success bool
	Skus    map[string]SkuInfo
	Error   string
}

// SkuInfo holds product info from gRPC Product Service.
type SkuInfo struct {
	ProductID     string  `json:"product_id"`
	VariantInfo   string  `json:"variant_info"`
	IsAvailable   bool    `json:"is_available"`
	CurrentPrice  float64 `json:"current_price"`
	ProductName   string  `json:"product_name"`
	ImageURL      string  `json:"image_url"`
	AvailableQty  int64   `json:"available_qty"`
}
