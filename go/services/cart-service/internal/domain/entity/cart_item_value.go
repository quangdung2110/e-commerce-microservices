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
	AddedAt        string  `json:"added_at"`
	UpdatedAt      string  `json:"updated_at"`
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
