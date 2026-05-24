package event

// Cart event types — emitted by Lua scripts and forwarded to Kafka.
// Ref: cart_events.md
const (
	// CartItemEvent types
	EventTypeCartItemAdded   = "CART_ITEM_ADDED"
	EventTypeCartItemUpdated = "CART_ITEM_UPDATED"
	EventTypeCartItemDeleted = "CART_ITEM_DELETED"

	// Cart lifecycle event types
	EventTypeCartCleared    = "CART_CLEARED"
	EventTypeCartCheckedOut = "CART_CHECKED_OUT"
	EventTypeCartAbandoned  = "CART_ABANDONED"

	// Consumed event types (from other services)
	EventTypeOrderPaid = "ORDER_PAID"
)

// AggregateType is the type identifier for cart aggregate.
const AggregateTypeCart = "Cart"
