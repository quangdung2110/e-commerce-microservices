package request

import "errors"

var (
	ErrSkuIDRequired      = errors.New("sku_id is required")
	ErrQuantityPositive  = errors.New("quantity must be positive")
	ErrCartIDRequired    = errors.New("cart_id is required")
	ErrSkuIDsRequired    = errors.New("sku_ids is required and must not be empty")
)
