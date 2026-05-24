package event

// CartClearedPayload is the payload for CART_CLEARED event.
// Ref: cart_events.md §2.4
// Emitted when user manually clears all items and cart becomes empty (HLEN == 0).
type CartClearedPayload struct {
	CartID    string `json:"cart_id"`
	UserID    string `json:"user_id"`
	ClearedAt string `json:"cleared_at"` // Epoch ms as string
}
