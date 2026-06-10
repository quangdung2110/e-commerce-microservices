package event

// CartItemDeletedPayload is the payload for CART_ITEM_DELETED event.
// Ref: cart_events.md §2.3
// Note: Does NOT contain product_id (only cart_id, sku_id, user_id needed for deletion).
type CartItemDeletedPayload struct {
	CartID    string `json:"cart_id"`
	SkuID     string `json:"sku_id"`
	UserID    string `json:"user_id"`
	DeletedAt string `json:"deleted_at"` // Epoch ms as string
}
