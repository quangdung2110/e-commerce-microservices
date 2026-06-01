package entity

import "errors"

var (
	// Cart errors
	ErrCartNotFound          = errors.New("cart not found")
	ErrInvalidCartStatus     = errors.New("invalid cart status")
	ErrInvalidCartTransition = errors.New("invalid cart status transition")

	// CartItem errors
	ErrItemNotFound          = errors.New("cart item not found")
	ErrInvalidQuantity       = errors.New("invalid quantity")
	ErrQuantityExceedsLimit  = errors.New("quantity exceeds maximum limit")
	ErrCartFull              = errors.New("cart has reached maximum SKU capacity")
	ErrMissingRequiredField  = errors.New("missing required field")

	// Business rule errors
	ErrProductUnavailable = errors.New("product is unavailable")
	ErrProductNotFound    = errors.New("product not found")
	ErrInsufficientStock  = errors.New("insufficient stock")

	// Stream ordering errors
	ErrEventOutOfOrder     = errors.New("event arrived out of order")
	ErrEventAlreadyApplied = errors.New("event already applied (duplicate)")

	// Generic
	ErrInternal = errors.New("internal error")
)
