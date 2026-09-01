// Package request holds the inbound HTTP payloads defined by
// contracts/http/cart/v1/openapi.yaml.
package request

import (
	"fmt"

	"cart-service/internal/domain"
)

// FieldError names one invalid request field.
type FieldError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

// AdjustItem is the POST /api/v1/carts/mine/items body.
//
// The contract carries a relative delta rather than an absolute quantity. Two
// clicks that arrive out of order therefore both apply, instead of the older
// one overwriting the newer total.
type AdjustItem struct {
	SkuID string `json:"sku_id"`
	Delta *int   `json:"delta"`
}

// Validate enforces the syntactic constraints of AdjustItemRequest.
func (r AdjustItem) Validate() []FieldError {
	var errs []FieldError

	if r.SkuID == "" {
		errs = append(errs, FieldError{Field: "sku_id", Message: "sku_id is required"})
	} else if len(r.SkuID) > 64 {
		errs = append(errs, FieldError{Field: "sku_id", Message: "sku_id must be at most 64 characters"})
	}

	switch {
	case r.Delta == nil:
		errs = append(errs, FieldError{Field: "delta", Message: "delta is required"})
	case *r.Delta == 0:
		errs = append(errs, FieldError{Field: "delta", Message: "delta must not be zero"})
	case *r.Delta < -domain.MaxQuantityPerItem || *r.Delta > domain.MaxQuantityPerItem:
		errs = append(errs, FieldError{
			Field:   "delta",
			Message: fmt.Sprintf("delta must be between -%d and %d", domain.MaxQuantityPerItem, domain.MaxQuantityPerItem),
		})
	}

	return errs
}

// DeleteItems is the DELETE /api/v1/carts/mine/items body.
type DeleteItems struct {
	SkuIDs []string `json:"sku_ids"`
}

// Validate enforces the syntactic constraints of DeleteItemsRequest,
// including the uniqueness the contract requires.
func (r DeleteItems) Validate() []FieldError {
	var errs []FieldError

	switch {
	case len(r.SkuIDs) == 0:
		errs = append(errs, FieldError{Field: "sku_ids", Message: "sku_ids must contain at least one entry"})
	case len(r.SkuIDs) > domain.MaxDeleteBatch:
		errs = append(errs, FieldError{
			Field:   "sku_ids",
			Message: fmt.Sprintf("sku_ids must contain at most %d entries", domain.MaxDeleteBatch),
		})
	}

	seen := make(map[string]struct{}, len(r.SkuIDs))
	for _, skuID := range r.SkuIDs {
		if skuID == "" || len(skuID) > 64 {
			errs = append(errs, FieldError{Field: "sku_ids", Message: "each sku_id must be 1 to 64 characters"})
			break
		}
		if _, duplicate := seen[skuID]; duplicate {
			errs = append(errs, FieldError{Field: "sku_ids", Message: "sku_ids must not contain duplicates"})
			break
		}
		seen[skuID] = struct{}{}
	}

	return errs
}
