package domain

import (
	"bytes"
	"encoding/json"
	"strconv"
	"time"
)

// Business limits (docs/cart/domain.md §4).
const (
	// MaxQuantityPerItem caps a single SKU line. CART-BR-001.
	MaxQuantityPerItem = 100
	// MaxSKUsPerCart caps the number of distinct SKUs. CART-BR-002.
	MaxSKUsPerCart = 50
	// MaxDeleteBatch caps one DELETE request.
	MaxDeleteBatch = 100
	// MaxBatchSkuLookup is the provider-side cap on GetBatchSkus.
	MaxBatchSkuLookup = 100
)

// Status is the cart lifecycle state stored in the meta hash.
type Status string

const (
	StatusActive    Status = "ACTIVE"
	StatusAbandoned Status = "ABANDONED"
)

// Money is an amount in the currency's smallest unit. It never uses floating
// point (contracts/shared/json-schema/v1/money.schema.json).
type Money struct {
	AmountMinor int64  `json:"amount_minor"`
	Currency    string `json:"currency"`
}

// Millis is a Unix millisecond timestamp that is stored as a JSON string.
//
// The Lua scripts round-trip item JSON through cjson, which re-encodes every
// number through a %.14g format. Keeping timestamps as strings makes that
// round-trip lossless.
type Millis int64

// MarshalJSON writes the timestamp as a decimal string.
func (m Millis) MarshalJSON() ([]byte, error) {
	return []byte(`"` + strconv.FormatInt(int64(m), 10) + `"`), nil
}

// UnmarshalJSON accepts either a JSON string or a JSON number, so that a value
// written by an older encoder still loads.
func (m *Millis) UnmarshalJSON(data []byte) error {
	data = bytes.TrimSpace(data)
	if bytes.Equal(data, []byte("null")) {
		*m = 0
		return nil
	}
	if len(data) >= 2 && data[0] == '"' {
		var s string
		if err := json.Unmarshal(data, &s); err != nil {
			return err
		}
		if s == "" {
			*m = 0
			return nil
		}
		parsed, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return err
		}
		*m = Millis(parsed)
		return nil
	}
	var n int64
	if err := json.Unmarshal(data, &n); err != nil {
		return err
	}
	*m = Millis(n)
	return nil
}

// Time converts to a time.Time in UTC.
func (m Millis) Time() time.Time {
	return time.UnixMilli(int64(m)).UTC()
}

// RFC3339 renders the timestamp in the format the HTTP and event contracts use.
func (m Millis) RFC3339() string {
	return m.Time().Format(time.RFC3339)
}

// MillisOf converts a time.Time to Millis.
func MillisOf(t time.Time) Millis {
	return Millis(t.UnixMilli())
}

// VariantAttributes is a display-only attribute map.
//
// Redis cjson encodes an empty Lua table as an empty array, so an item that
// round-trips through a Lua script can come back with `"variant_attributes": []`.
// The custom decoder tolerates that; the encoder omits the field when empty so
// the situation is not created in the first place.
type VariantAttributes map[string]string

// UnmarshalJSON decodes an object, and treats an empty array as an empty map.
func (v *VariantAttributes) UnmarshalJSON(data []byte) error {
	data = bytes.TrimSpace(data)
	if bytes.Equal(data, []byte("null")) || bytes.Equal(data, []byte("[]")) {
		*v = nil
		return nil
	}
	var m map[string]string
	if err := json.Unmarshal(data, &m); err != nil {
		return err
	}
	*v = m
	return nil
}

// StoredItem is the item JSON stored as one field of the cart items hash
// (docs/cart/data.md §3.1).
//
// The field names and types are shared with the Lua scripts. Two of them are
// immutable once written: UnitPriceAtAdd and AddedAt (CART-BR-005). The
// scripts preserve both when a refreshed product snapshot replaces the rest.
type StoredItem struct {
	ProductID         string            `json:"product_id"`
	Quantity          int               `json:"quantity"`
	UnitPriceAtAdd    Money             `json:"unit_price_at_add"`
	ProductName       string            `json:"product_name"`
	VariantAttributes VariantAttributes `json:"variant_attributes,omitempty"`
	ImageURL          string            `json:"image_url,omitempty"`

	// IsAvailable is catalog saleability (Product and SKU are ACTIVE) at the
	// time of validation. It is not an Inventory or reservation result.
	IsAvailable bool `json:"is_available"`

	// CatalogValidatedAt and CatalogValidationExpiresAt bound how long the
	// snapshot may be reused for a positive delta without calling Product again
	// (CART-BR-004).
	CatalogValidatedAt         Millis `json:"catalog_validated_at_ms"`
	CatalogValidationExpiresAt Millis `json:"catalog_validation_expires_at_ms"`

	AddedAt   Millis `json:"added_at"`
	UpdatedAt Millis `json:"updated_at"`
}

// Item is one SKU line as read out of Redis, with the hash field name attached.
type Item struct {
	SkuID string
	StoredItem
}

// CatalogFreshness reports whether a read was enriched with live Product data.
type CatalogFreshness string

const (
	// FreshnessCurrent means Product answered and the response carries live
	// prices and availability.
	FreshnessCurrent CatalogFreshness = "CURRENT"
	// FreshnessDegraded means Product timed out or the breaker was open, so
	// the response falls back to the stored snapshot.
	FreshnessDegraded CatalogFreshness = "DEGRADED"
)

// ViewItem is one cart line enriched for a read response.
type ViewItem struct {
	Item
	// CurrentPrice is nil when Product could not supply a live price.
	CurrentPrice *Money
	// Available is the live availability when freshness is CURRENT, and the
	// stored snapshot value when it is DEGRADED.
	Available bool
}

// CartView is the result of reading a cart.
type CartView struct {
	Items     []ViewItem
	Freshness CatalogFreshness
}

// TotalUnits sums the quantity across all lines.
func (c CartView) TotalUnits() int {
	total := 0
	for _, item := range c.Items {
		total += item.Quantity
	}
	return total
}

// ProductSnapshot is what Product reported for one SKU at validation time.
type ProductSnapshot struct {
	SkuID             string
	ProductID         string
	ProductName       string
	VariantAttributes map[string]string
	ImageURL          string
	Price             Money
	IsAvailable       bool
}

// ToStoredItem renders a snapshot as the item JSON the upsert script expects.
//
// Quantity and AddedAt are deliberately left at their zero values: the script
// owns both. For an existing SKU it also restores the original
// UnitPriceAtAdd, so the value set here only survives for a brand-new line.
func (s ProductSnapshot) ToStoredItem(validatedAt, expiresAt Millis) StoredItem {
	return StoredItem{
		ProductID:                  s.ProductID,
		UnitPriceAtAdd:             s.Price,
		ProductName:                s.ProductName,
		VariantAttributes:          VariantAttributes(s.VariantAttributes),
		ImageURL:                   s.ImageURL,
		IsAvailable:                s.IsAvailable,
		CatalogValidatedAt:         validatedAt,
		CatalogValidationExpiresAt: expiresAt,
	}
}
