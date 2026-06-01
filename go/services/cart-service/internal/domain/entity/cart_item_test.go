package entity

import (
	"testing"
	"time"
)

func TestNewCartItem(t *testing.T) {
	snapshot := ProductSnapshot{
		ProductID:   "prod-1",
		ProductName: "Test Product",
		VariantInfo: "Size: L",
		ImageURL:    "https://example.com/img.jpg",
		IsAvailable: true,
		AddedAt:     "1708530000000",
		UpdatedAt:   "1708530000000",
	}

	item, err := NewCartItem("{cart}:user1", "prod-1", "sku-1", 3, 99.99, snapshot)
	if err != nil {
		t.Fatalf("NewCartItem() unexpected error: %v", err)
	}

	if item.CartID() != "{cart}:user1" {
		t.Errorf("CartID = %q, want {cart}:user1", item.CartID())
	}
	if item.ProductID() != "prod-1" {
		t.Errorf("ProductID = %q, want prod-1", item.ProductID())
	}
	if item.SkuID() != "sku-1" {
		t.Errorf("SkuID = %q, want sku-1", item.SkuID())
	}
	if item.Quantity() != 3 {
		t.Errorf("Quantity = %d, want 3", item.Quantity())
	}
	if item.UnitPriceAtAdd() != 99.99 {
		t.Errorf("UnitPriceAtAdd = %f, want 99.99", item.UnitPriceAtAdd())
	}
	if item.ItemID() != "{cart}:user1:sku-1" {
		t.Errorf("ItemID = %q, want {cart}:user1:sku-1", item.ItemID())
	}
}

func TestNewCartItem_InvalidQuantity(t *testing.T) {
	snapshot := ProductSnapshot{ProductName: "Test"}

	_, err := NewCartItem("{cart}:user1", "prod-1", "sku-1", 0, 99.99, snapshot)
	if err != ErrInvalidQuantity {
		t.Errorf("expected ErrInvalidQuantity, got %v", err)
	}

	_, err = NewCartItem("{cart}:user1", "prod-1", "sku-1", -1, 99.99, snapshot)
	if err != ErrInvalidQuantity {
		t.Errorf("expected ErrInvalidQuantity, got %v", err)
	}
}

func TestNewCartItem_MissingRequiredField(t *testing.T) {
	snapshot := ProductSnapshot{ProductName: "Test"}

	_, err := NewCartItem("", "prod-1", "sku-1", 1, 99.99, snapshot)
	if err != ErrMissingRequiredField {
		t.Errorf("expected ErrMissingRequiredField, got %v", err)
	}

	_, err = NewCartItem("{cart}:user1", "prod-1", "", 1, 99.99, snapshot)
	if err != ErrMissingRequiredField {
		t.Errorf("expected ErrMissingRequiredField, got %v", err)
	}
}

func TestCartItem_UpdateQuantity(t *testing.T) {
	snapshot := ProductSnapshot{ProductName: "Test"}
	item, _ := NewCartItem("{cart}:user1", "prod-1", "sku-1", 5, 100.0, snapshot)

	err := item.UpdateQuantity(10)
	if err != nil {
		t.Fatalf("UpdateQuantity(10) unexpected error: %v", err)
	}
	if item.Quantity() != 10 {
		t.Errorf("Quantity = %d, want 10", item.Quantity())
	}

	err = item.UpdateQuantity(0)
	if err != ErrInvalidQuantity {
		t.Errorf("expected ErrInvalidQuantity for 0, got %v", err)
	}

	err = item.UpdateQuantity(-5)
	if err != ErrInvalidQuantity {
		t.Errorf("expected ErrInvalidQuantity for -5, got %v", err)
	}

	err = item.UpdateQuantity(MaxQuantityPerItem + 1)
	if err != ErrQuantityExceedsLimit {
		t.Errorf("expected ErrQuantityExceedsLimit, got %v", err)
	}

	err = item.UpdateQuantity(MaxQuantityPerItem)
	if err != nil {
		t.Errorf("UpdateQuantity(MaxQuantityPerItem) should succeed, got %v", err)
	}
}

func TestCartItem_IncrementQuantity(t *testing.T) {
	snapshot := ProductSnapshot{ProductName: "Test"}
	item, _ := NewCartItem("{cart}:user1", "prod-1", "sku-1", 5, 100.0, snapshot)

	err := item.IncrementQuantity(3)
	if err != nil {
		t.Fatalf("IncrementQuantity(3) unexpected error: %v", err)
	}
	if item.Quantity() != 8 {
		t.Errorf("Quantity = %d, want 8", item.Quantity())
	}

	err = item.IncrementQuantity(0)
	if err != nil {
		t.Fatalf("IncrementQuantity(0) should not error, got %v", err)
	}
	if item.Quantity() != 8 {
		t.Errorf("Quantity = %d, want 8 (unchanged)", item.Quantity())
	}
}

func TestRestoreCartItem(t *testing.T) {
	snapshot := ProductSnapshot{
		ProductID:   "prod-1",
		ProductName: "Restored Product",
		IsAvailable: true,
	}

	item := RestoreCartItem(
		"item-id-1", "{cart}:user1", "prod-1", "sku-1",
		2, 50.0, snapshot, "1000-0",
		timeNow(), timeNow(),
	)

	if item.ItemID() != "item-id-1" {
		t.Errorf("ItemID = %q, want item-id-1", item.ItemID())
	}
	if item.StreamID() != "1000-0" {
		t.Errorf("StreamID = %q, want 1000-0", item.StreamID())
	}
	if item.Quantity() != 2 {
		t.Errorf("Quantity = %d, want 2", item.Quantity())
	}
}

func TestCartItem_Snapshot(t *testing.T) {
	snapshot := ProductSnapshot{
		ProductID:   "prod-1",
		ProductName: "Snapshot Test",
		VariantInfo: "Color: Red",
		ImageURL:    "https://example.com/red.jpg",
		IsAvailable: false,
		AddedAt:     "1708530000000",
		UpdatedAt:   "1708530001000",
	}

	item, _ := NewCartItem("{cart}:user1", "prod-1", "sku-1", 1, 100.0, snapshot)
	snap := item.Snapshot()

	if snap.ProductName != "Snapshot Test" {
		t.Errorf("ProductName = %q, want Snapshot Test", snap.ProductName)
	}
	if snap.IsAvailable != false {
		t.Error("IsAvailable should be false")
	}
}

func TestCartItemValue_ToSnapshot(t *testing.T) {
	civ := CartItemValue{
		ProductID:      "prod-abc",
		Quantity:       3,
		UnitPriceAtAdd: 25.99,
		ProductName:    "Converted Product",
		VariantInfo:    "Size: XL",
		ImageURL:       "https://example.com/xl.jpg",
		IsAvailable:    true,
		AddedAt:        "1708530000000",
		UpdatedAt:      "1708530002000",
	}

	snap := civ.ToSnapshot()

	if snap.ProductID != "prod-abc" {
		t.Errorf("ProductID = %q, want prod-abc", snap.ProductID)
	}
	if snap.ProductName != "Converted Product" {
		t.Errorf("ProductName = %q, want Converted Product", snap.ProductName)
	}
	if snap.VariantInfo != "Size: XL" {
		t.Errorf("VariantInfo = %q, want Size: XL", snap.VariantInfo)
	}
	if snap.ImageURL != "https://example.com/xl.jpg" {
		t.Errorf("ImageURL = %q, want https://example.com/xl.jpg", snap.ImageURL)
	}
	if !snap.IsAvailable {
		t.Error("IsAvailable should be true")
	}
	if snap.AddedAt != "1708530000000" {
		t.Errorf("AddedAt = %q, want 1708530000000", snap.AddedAt)
	}
	if snap.UpdatedAt != "1708530002000" {
		t.Errorf("UpdatedAt = %q, want 1708530002000", snap.UpdatedAt)
	}
}

func TestCartItemValue_QuantityBoundary(t *testing.T) {
	civ := CartItemValue{
		ProductID:      "prod-1",
		Quantity:       1,
		UnitPriceAtAdd: 10.0,
		ProductName:    "Boundary Test",
		IsAvailable:    true,
		AddedAt:       "1708530000000",
		UpdatedAt:     "1708530000000",
	}

	if civ.Quantity != 1 {
		t.Errorf("Quantity = %d, want 1", civ.Quantity)
	}

	civ.Quantity = MaxQuantityPerItem
	if civ.Quantity != MaxQuantityPerItem {
		t.Errorf("Quantity = %d, want MaxQuantityPerItem", civ.Quantity)
	}
}

func TestProductSnapshot_Defaults(t *testing.T) {
	snap := ProductSnapshot{}
	if snap.ProductID != "" {
		t.Errorf("ProductID default = %q, want empty", snap.ProductID)
	}
	if snap.IsAvailable != false {
		t.Error("IsAvailable default should be false")
	}
}

func timeNow() time.Time {
	return time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC)
}
