package port

import "errors"

// Cart operation errors
var (
	ErrCartFull                   = errors.New("cart is full")
	ErrStepInvalid                = errors.New("step must be non-zero")
	ErrQuantityInvalid            = errors.New("quantity would be invalid")
	ErrCannotDecrementNonExisting = errors.New("cannot decrement non-existing item")
	ErrCartNotFound              = errors.New("cart not found")
	ErrItemNotFound              = errors.New("item not found in cart")
)

// Idempotency status constants
const (
	IdempotencyStatusProcessing = "PROCESSING"
	IdempotencyStatusDone       = "DONE"
)
