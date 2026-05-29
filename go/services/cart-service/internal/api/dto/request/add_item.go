package request

import "github.com/go-playground/validator/v10"

// AddItemRequest represents POST /api/v1/carts/items
type AddItemRequest struct {
	SkuID    string `json:"sku_id" validate:"required,max=64"`
	Quantity int    `json:"quantity" validate:"required,min=1,max=100"`
}

// Validate validates the request.
func (r *AddItemRequest) Validate() error {
	validate := validator.New()
	return validate.Struct(r)
}

// AddItemResponse represents the response for add item.
type AddItemResponse struct {
	Success   bool   `json:"success"`
	SkuID     string `json:"sku_id"`
	Quantity  int    `json:"quantity"`
	UpdatedAt string `json:"updated_at"`
	Message   string `json:"message,omitempty"`
}
