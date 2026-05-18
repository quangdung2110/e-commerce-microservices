package request

// DeleteItemsRequest represents the request to delete items
type DeleteItemsRequest struct {
	CartID string   `json:"cart_id"`
	SkuIDs []string `json:"sku_ids"`
}

// Validate validates the request
func (r *DeleteItemsRequest) Validate() error {
	if r.CartID == "" {
		return ErrCartIDRequired
	}
	if len(r.SkuIDs) == 0 {
		return ErrSkuIDsRequired
	}
	return nil
}
