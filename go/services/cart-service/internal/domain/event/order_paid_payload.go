package event

// OrderPaidPayload is the payload for ORDER_PAID event consumed from Kafka.
// Ref: cart_events.md §3.1
// Fields used by Cart Service: order_id, user_id, cart_id, sku_ids.
type OrderPaidPayload struct {
	OrderID              int64    `json:"order_id"`
	UserID               string   `json:"user_id"`
	CartID               string   `json:"cart_id"`
	Status               string   `json:"status"`
	SkuIDs               []string `json:"sku_ids"`
	CheckoutSubmittedAt  string   `json:"checkout_submitted_at"`
	PaidAt               string   `json:"paid_at"`
}
