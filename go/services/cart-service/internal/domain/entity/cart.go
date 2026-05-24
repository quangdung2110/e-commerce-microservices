package entity

import (
	"time"
)

// CartStatus represents the lifecycle state of a cart.
type CartStatus string

const (
	CartStatusActive     CartStatus = "ACTIVE"
	CartStatusAbandoned  CartStatus = "ABANDONED"
	CartStatusDeleted    CartStatus = "DELETED"
	CartStatusCheckedOut CartStatus = "CHECKED_OUT"
)

func (s CartStatus) IsValid() bool {
	switch s {
	case CartStatusActive, CartStatusAbandoned,
		CartStatusDeleted, CartStatusCheckedOut:
		return true
	}
	return false
}

// Cart is the aggregate root for cart operations.
type Cart struct {
	cartID              string
	userID              string
	status              CartStatus
	lastEventStreamID   string
	abandonedEventPubAt *time.Time
	createdAt           time.Time
	updatedAt           time.Time
	items               []*CartItem
}

// NewCart creates a new cart for a user.
func NewCart(cartID, userID string) *Cart {
	now := time.Now().UTC()
	return &Cart{
		cartID:            cartID,
		userID:            userID,
		status:            CartStatusActive,
		lastEventStreamID: "",
		createdAt:         now,
		updatedAt:         now,
		items:             []*CartItem{},
	}
}

// RestoreCart creates a cart from cold storage (PostgreSQL).
func RestoreCart(cartID, userID string, status CartStatus, lastStreamID string, createdAt, updatedAt time.Time) *Cart {
	return &Cart{
		cartID:            cartID,
		userID:            userID,
		status:            status,
		lastEventStreamID: lastStreamID,
		createdAt:         createdAt,
		updatedAt:         updatedAt,
		items:             []*CartItem{},
	}
}

// Getters (immutable access)
func (c *Cart) CartID()        string     { return c.cartID }
func (c *Cart) UserID()        string     { return c.userID }
func (c *Cart) Status()        CartStatus { return c.status }
func (c *Cart) LastStreamID()  string     { return c.lastEventStreamID }
func (c *Cart) CreatedAt()    time.Time  { return c.createdAt }
func (c *Cart) UpdatedAt()    time.Time  { return c.updatedAt }
func (c *Cart) Items()        []*CartItem { return c.items }

// CanTransitionTo checks if the cart can transition from current status to next status.
func (c *Cart) CanTransitionTo(next CartStatus) bool {
	switch c.status {
	case CartStatusActive:
		return next == CartStatusAbandoned
	case CartStatusAbandoned:
		return next == CartStatusActive
	case CartStatusDeleted:
		return next == CartStatusActive
	case CartStatusCheckedOut:
		return next == CartStatusActive
	}
	return false
}

// TransitionTo transitions the cart to a new status if allowed.
func (c *Cart) TransitionTo(next CartStatus, streamID string) error {
	if !next.IsValid() {
		return ErrInvalidCartStatus
	}
	if c.CanTransitionTo(next) {
		c.status = next
		c.lastEventStreamID = streamID
		c.updatedAt = time.Now().UTC()
		return nil
	}
	return ErrInvalidCartTransition
}

// Reactivate sets status to ACTIVE when cart receives new activity after DELETED/CHECKED_OUT.
func (c *Cart) Reactivate(streamID string) {
	if streamIDNewer(c.lastEventStreamID, streamID) {
		c.status = CartStatusActive
		c.lastEventStreamID = streamID
		c.updatedAt = time.Now().UTC()
	}
}

// MarkAbandoned marks the cart as abandoned.
func (c *Cart) MarkAbandoned() {
	c.status = CartStatusAbandoned
	c.updatedAt = time.Now().UTC()
}

// streamIDNewer returns true if candidate is lexicographically greater than current.
// Redis stream IDs are monotonically increasing: "1708616400456-0" > "1708530000123-0"
func streamIDNewer(current, candidate string) bool {
	return candidate > current
}
