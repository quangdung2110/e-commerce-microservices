package request

import "github.com/go-playground/validator/v10"

// BatchDeleteRequest represents DELETE /api/v1/carts/items
type BatchDeleteRequest struct {
	SkuIDs []string `json:"sku_ids" validate:"required,min=1,max=100,dive,required,max=64"`
}

// Validate validates the request.
func (r *BatchDeleteRequest) Validate() error {
	validate := validator.New()
	return validate.Struct(r)
}
