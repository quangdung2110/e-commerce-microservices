package event

// CartCheckedOutPayload is the payload for CART_CHECKED_OUT event.
// Ref: cart_events.md §2.5
// Emitted when ORDER_PAID received and cart becomes empty (HLEN == 0).
type CartCheckedOutPayload struct {
	CartID       string `json:"cart_id"`
	UserID       string `json:"user_id"`
	CheckedOutAt string `json:"checked_out_at"` // Epoch ms as string
}
