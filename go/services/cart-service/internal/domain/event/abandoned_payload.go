package event

// CartAbandonedPayload is the payload for CART_ABANDONED event.
// Ref: cart_events.md §2.6
// Emitted by Abandoned Cart Marker cronjob.
type CartAbandonedPayload struct {
	CartID      string `json:"cart_id"`
	UserID      string `json:"user_id"`
	AbandonedAt string `json:"abandoned_at"` // Epoch ms as string
}
