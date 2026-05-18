package response

import "github.com/ecommerce-platform/go/services/cart-service/internal/domain/service"

// GetCartResponse represents the response for getting cart
type GetCartResponse struct {
	CartID          string          `json:"cart_id"`
	Items           []ItemGroupDTO  `json:"items"`
	IsPriceDegraded bool            `json:"is_price_degraded"`
	TotalItems     int             `json:"total_items"`
	TotalUniqueSKUs int           `json:"total_unique_skus"`
}

// ItemGroupDTO groups items by latest_item_at
type ItemGroupDTO struct {
	LatestItemAt int64        `json:"latest_item_at"`
	Items       []CartItemDTO `json:"items"`
}

// CartItemDTO represents a cart item in responses
type CartItemDTO struct {
	SkuID          string  `json:"sku_id"`
	ProductName    string  `json:"product_name"`
	VariantInfo    string  `json:"variant_info"`
	ImageURL       string  `json:"image_url"`
	Quantity       int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	CurrentPrice   float64 `json:"current_price,omitempty"`
	IsAvailable    bool    `json:"is_available"`
	AddedAt        string  `json:"added_at"`
	UpdatedAt      string  `json:"updated_at"`
}

// NewGetCartResponse creates a new GetCartResponse
func NewGetCartResponse(result *service.GetCartResult) *GetCartResponse {
	resp := &GetCartResponse{
		CartID:          result.CartID,
		Items:           []ItemGroupDTO{},
		IsPriceDegraded: result.IsPriceDegraded,
		TotalItems:      result.TotalItems,
		TotalUniqueSKUs: result.TotalUniqueSKUs,
	}

	for _, group := range result.Items {
		items := make([]CartItemDTO, len(group.Items))
		for i, item := range group.Items {
			items[i] = CartItemDTO{
				SkuID:          item.SkuID,
				ProductName:    item.ProductName,
				VariantInfo:    item.VariantInfo,
				ImageURL:       item.ImageURL,
				Quantity:       item.Quantity,
				UnitPriceAtAdd:  item.UnitPriceAtAdd,
				CurrentPrice:  item.CurrentPrice,
				IsAvailable:   item.IsAvailable,
				AddedAt:      item.AddedAt,
				UpdatedAt:    item.UpdatedAt,
			}
		}
		resp.Items = append(resp.Items, ItemGroupDTO{
			LatestItemAt: group.LatestItemAt,
			Items:       items,
		})
	}

	return resp
}
