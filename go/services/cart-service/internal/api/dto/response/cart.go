package response

import (
	"cart-service/internal/domain/entity"
)

// CartResponse represents the GET /api/v1/carts/mine response.
type CartResponse struct {
	Items           []ItemGroupResponse `json:"items"`
	IsPriceDegraded bool                `json:"is_price_degraded"`
	TotalItems      int                 `json:"total_items"`
	TotalUniqueSKUs int                 `json:"total_unique_skus"`
	Message         string              `json:"message,omitempty"`
}

// ItemGroupResponse groups items by platform/store (if needed).
type ItemGroupResponse struct {
	Items []ItemResponse `json:"items"`
}

// ItemResponse represents a single cart item in the response.
type ItemResponse struct {
	SkuID          string  `json:"sku_id"`
	ProductID      string  `json:"product_id"`
	ProductName    string  `json:"product_name"`
	VariantInfo    string  `json:"variant_info,omitempty"`
	ImageURL       string  `json:"image_url,omitempty"`
	Quantity       int     `json:"quantity"`
	UnitPriceAtAdd float64 `json:"unit_price_at_add"`
	CurrentPrice   float64 `json:"current_price"`
	IsAvailable    bool    `json:"is_available"`
	AddedAt        string  `json:"added_at"`
	UpdatedAt      string  `json:"updated_at"`
}

// FromCartView converts domain CartView to response DTO.
func FromCartView(view *entity.CartView) *CartResponse {
	items := make([]ItemResponse, len(view.Items))
	for i, item := range view.Items {
		items[i] = ItemResponse{
			SkuID:          item.SkuID,
			ProductID:      item.ProductID,
			ProductName:    item.ProductName,
			VariantInfo:    item.VariantInfo,
			ImageURL:       item.ImageURL,
			Quantity:       item.Quantity,
			UnitPriceAtAdd: item.UnitPriceAtAdd,
			CurrentPrice:   item.CurrentPrice,
			IsAvailable:    item.IsAvailable,
			AddedAt:        item.AddedAt,
			UpdatedAt:      item.UpdatedAt,
		}
	}

	resp := &CartResponse{
		Items:           []ItemGroupResponse{{Items: items}},
		IsPriceDegraded: view.IsPriceDegraded,
		TotalItems:      view.TotalItems,
		TotalUniqueSKUs: view.TotalUniqueSKUs,
	}

	if view.IsPriceDegraded {
		resp.Message = "Hệ thống đang quá tải, giá sản phẩm có thể chưa được cập nhật mới nhất."
	}

	return resp
}

// NewEmptyCartResponse returns an empty cart response.
func NewEmptyCartResponse() *CartResponse {
	return &CartResponse{
		Items:           []ItemGroupResponse{},
		IsPriceDegraded: false,
		TotalItems:      0,
		TotalUniqueSKUs: 0,
	}
}
