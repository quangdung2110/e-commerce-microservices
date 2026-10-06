// Package response holds the outbound HTTP payloads defined by
// contracts/http/cart/v1/openapi.yaml.
package response

import (
	"cart-service/internal/api/media"
	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
)

// Money mirrors contracts/shared/json-schema/v1/money.schema.json.
type Money struct {
	AmountMinor int64  `json:"amount_minor"`
	Currency    string `json:"currency"`
}

// CartItem is one enriched cart line.
type CartItem struct {
	SkuID             string            `json:"sku_id"`
	ProductID         string            `json:"product_id"`
	ProductName       string            `json:"product_name"`
	VariantAttributes map[string]string `json:"variant_attributes,omitempty"`
	// ImageURL is always an absolute CDN URL, or the placeholder when the SKU
	// has no image. A storage key never leaves the service.
	ImageURL string `json:"image_url"`
	Quantity int    `json:"quantity"`
	// UnitPriceAtAdd is frozen at the moment the SKU entered the cart.
	UnitPriceAtAdd Money `json:"unit_price_at_add"`
	// CurrentPrice is null when Product could not supply a live price. It is
	// never persisted, only rendered.
	CurrentPrice *Money `json:"current_price"`
	IsAvailable  bool   `json:"is_available"`
	AddedAt      string `json:"added_at"`
	UpdatedAt    string `json:"updated_at"`
}

// Cart is the GET /api/v1/carts/mine body.
type Cart struct {
	Items            []CartItem `json:"items"`
	TotalUniqueSkus  int        `json:"total_unique_skus"`
	TotalUnits       int        `json:"total_units"`
	CatalogFreshness string     `json:"catalog_freshness"`
}

// CartMutationResult is the adjust-item response body.
type CartMutationResult struct {
	SkuID     string `json:"sku_id"`
	Quantity  int    `json:"quantity"`
	Created   bool   `json:"created"`
	UpdatedAt string `json:"updated_at"`
}

// DeleteItemsResult is the delete-items response body.
type DeleteItemsResult struct {
	DeletedCount int `json:"deleted_count"`
}

// NewCart maps a domain view onto the wire contract.
func NewCart(view domain.CartView, urls media.URLs) Cart {
	items := make([]CartItem, 0, len(view.Items))
	for _, line := range view.Items {
		items = append(items, newCartItem(line, urls))
	}
	return Cart{
		Items:            items,
		TotalUniqueSkus:  len(items),
		TotalUnits:       view.TotalUnits(),
		CatalogFreshness: string(view.Freshness),
	}
}

func newCartItem(line domain.ViewItem, urls media.URLs) CartItem {
	item := CartItem{
		SkuID:             line.SkuID,
		ProductID:         line.ProductID,
		ProductName:       line.ProductName,
		VariantAttributes: line.VariantAttributes,
		ImageURL:          urls.Image(line.ImageKey),
		Quantity:          line.Quantity,
		UnitPriceAtAdd:    newMoney(line.UnitPriceAtAdd),
		IsAvailable:       line.Available,
		AddedAt:           line.AddedAt.RFC3339(),
		UpdatedAt:         line.UpdatedAt.RFC3339(),
	}
	if line.CurrentPrice != nil {
		price := newMoney(*line.CurrentPrice)
		item.CurrentPrice = &price
	}
	return item
}

func newMoney(money domain.Money) Money {
	return Money{AmountMinor: money.AmountMinor, Currency: money.Currency}
}

// NewCartMutationResult maps an upsert outcome onto the wire contract.
func NewCartMutationResult(result port.UpsertResult) CartMutationResult {
	return CartMutationResult{
		SkuID:     result.SkuID,
		Quantity:  result.Quantity,
		Created:   result.Created,
		UpdatedAt: result.UpdatedAt.RFC3339(),
	}
}

// NewDeleteItemsResult maps a remove outcome onto the wire contract.
func NewDeleteItemsResult(result port.RemoveResult) DeleteItemsResult {
	return DeleteItemsResult{DeletedCount: result.DeletedCount}
}
