package response

import "github.com/ecommerce-platform/go/services/cart-service/internal/domain/service"

// AddItemResponse represents the response after adding an item
type AddItemResponse struct {
	Success bool    `json:"success"`
	CartID  string `json:"cart_id"`
	Item    *AddItemDTO `json:"item,omitempty"`
	Error   string `json:"error,omitempty"`
}

// AddItemDTO represents a simplified item in add response
type AddItemDTO struct {
	SkuID          string  `json:"sku_id"`
	Quantity       int     `json:"quantity"`
	ProductName    string  `json:"product_name"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
}

// NewAddItemResponse creates a new AddItemResponse
func NewAddItemResponse(result *service.AddItemResult) *AddItemResponse {
	resp := &AddItemResponse{
		Success: result.Success,
		CartID: result.CartID,
	}

	if result.Item != nil {
		resp.Item = &AddItemDTO{
			SkuID:          result.Item.SkuID,
			Quantity:       result.Item.Quantity,
			ProductName:    result.Item.ProductName,
			UnitPriceAtAdd: result.Item.UnitPriceAtAdd,
		}
	}

	if result.Error != nil {
		resp.Error = result.Error.Error()
	}

	return resp
}
