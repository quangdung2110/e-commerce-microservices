package request

// AddItemRequest represents the request to add/update an item
type AddItemRequest struct {
	SkuID    string `json:"sku_id"`
	Quantity int    `json:"quantity"`
}

// Validate validates the request
func (r *AddItemRequest) Validate() error {
	if r.SkuID == "" {
		return ErrSkuIDRequired
	}
	if r.Quantity <= 0 {
		return ErrQuantityPositive
	}
	return nil
}
