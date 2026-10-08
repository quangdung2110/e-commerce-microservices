package service

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
)

// ── fakes ────────────────────────────────────────────────────────────────

type fakeStore struct {
	mu sync.Mutex

	items []domain.Item
	err   error

	upsertCalls  []port.UpsertCommand
	upsertErrors []error
	upsertResult port.UpsertResult

	removeCalls  []port.RemoveCommand
	removeResult port.RemoveResult

	purgeCalls  []domain.Identity
	purgeResult port.PurgeResult
	purgeErr    error
}

func (f *fakeStore) LoadItems(context.Context, domain.Identity) ([]domain.Item, error) {
	return f.items, f.err
}

func (f *fakeStore) Upsert(_ context.Context, cmd port.UpsertCommand) (port.UpsertResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	call := len(f.upsertCalls)
	f.upsertCalls = append(f.upsertCalls, cmd)
	if call < len(f.upsertErrors) && f.upsertErrors[call] != nil {
		return port.UpsertResult{}, f.upsertErrors[call]
	}
	return f.upsertResult, nil
}

func (f *fakeStore) Remove(_ context.Context, cmd port.RemoveCommand) (port.RemoveResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.removeCalls = append(f.removeCalls, cmd)
	return f.removeResult, nil
}

func (f *fakeStore) MarkAbandoned(context.Context, port.MarkAbandonedCommand) (port.MarkAbandonedResult, error) {
	return port.MarkAbandonedResult{}, nil
}

func (f *fakeStore) PurgeCart(_ context.Context, identity domain.Identity) (port.PurgeResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.purgeCalls = append(f.purgeCalls, identity)
	return f.purgeResult, f.purgeErr
}

func (f *fakeStore) DueCarts(context.Context, int, time.Time, int) ([]port.DueCart, error) {
	return nil, nil
}

func (f *fakeStore) DropIndexMember(context.Context, int, string) error { return nil }
func (f *fakeStore) Ping(context.Context) error                         { return nil }

type fakeCatalog struct {
	mu sync.Mutex

	sku      domain.ProductSnapshot
	skuErr   error
	skuCalls int

	batch     map[string]domain.ProductSnapshot
	batchErr  error
	batchCall int
}

func (f *fakeCatalog) GetSku(context.Context, string) (domain.ProductSnapshot, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.skuCalls++
	return f.sku, f.skuErr
}

func (f *fakeCatalog) GetBatchSkus(context.Context, []string) (map[string]domain.ProductSnapshot, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.batchCall++
	return f.batch, f.batchErr
}

func (f *fakeCatalog) Ready(context.Context) error { return nil }

func testIdentity(t *testing.T) domain.Identity {
	t.Helper()

	identity, err := domain.NewIdentity("0198e098-94f4-7f8f-9e16-ae42dfbd93c1")
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}
	return identity
}

func newService(store port.CartStore, catalog port.ProductCatalog) *CartService {
	return New(store, catalog, Config{ProductValidationTTL: 45 * time.Second})
}

// ── AdjustItem ───────────────────────────────────────────────────────────

// A repeated click on a SKU whose validation is still fresh must not reach
// Product at all: that is the whole point of storing the validation window.
func TestAdjustItemSkipsProductWhenTheScriptAccepts(t *testing.T) {
	store := &fakeStore{upsertResult: port.UpsertResult{SkuID: "SKU-A", Quantity: 3}}
	catalog := &fakeCatalog{}

	if _, err := newService(store, catalog).AdjustItem(context.Background(), testIdentity(t), "SKU-A", 1, "idempotency-key-000001"); err != nil {
		t.Fatalf("AdjustItem: %v", err)
	}

	if catalog.skuCalls != 0 {
		t.Fatalf("product was called %d times for a fresh validation", catalog.skuCalls)
	}
	if len(store.upsertCalls) != 1 {
		t.Fatalf("script was called %d times, want 1", len(store.upsertCalls))
	}
	if store.upsertCalls[0].Snapshot != nil {
		t.Fatal("the first attempt carried a snapshot it did not need")
	}
}

// PRODUCT_SNAPSHOT_REQUIRED is an internal signal. The caller must never see
// it: Cart refreshes Product and retries.
func TestAdjustItemRefreshesProductAndRetries(t *testing.T) {
	store := &fakeStore{
		upsertErrors: []error{domain.NewError(domain.CodeProductSnapshotRequired, "stale")},
		upsertResult: port.UpsertResult{SkuID: "SKU-A", Quantity: 1, Created: true},
	}
	catalog := &fakeCatalog{sku: domain.ProductSnapshot{
		SkuID:       "SKU-A",
		ProductID:   "PRODUCT-A",
		ProductName: "A",
		Price:       domain.Money{AmountMinor: 1000, Currency: "VND"},
		IsAvailable: true,
	}}

	result, err := newService(store, catalog).AdjustItem(context.Background(), testIdentity(t), "SKU-A", 1, "idempotency-key-000001")
	if err != nil {
		t.Fatalf("AdjustItem: %v", err)
	}
	if !result.Created {
		t.Fatalf("result = %+v, want created=true", result)
	}

	if catalog.skuCalls != 1 {
		t.Fatalf("product was called %d times, want 1", catalog.skuCalls)
	}
	if len(store.upsertCalls) != 2 {
		t.Fatalf("script was called %d times, want 2", len(store.upsertCalls))
	}

	retry := store.upsertCalls[1]
	if retry.Snapshot == nil {
		t.Fatal("the retry carried no snapshot")
	}
	if retry.Snapshot.CatalogValidationExpiresAt <= retry.Snapshot.CatalogValidatedAt {
		t.Fatal("the snapshot validation window is not in the future")
	}
	// Both attempts must share a fingerprint, or the retry would look like a
	// different payload under the same idempotency key.
	if retry.Fingerprint != store.upsertCalls[0].Fingerprint {
		t.Fatal("the retry changed the request fingerprint")
	}
}

// A Product failure on a write rejects the write. Guessing that an unverified
// SKU is on sale is worse than an error.
func TestAdjustItemFailsWhenProductIsUnavailable(t *testing.T) {
	store := &fakeStore{upsertErrors: []error{domain.NewError(domain.CodeProductSnapshotRequired, "new sku")}}
	catalog := &fakeCatalog{skuErr: domain.NewRetryableError(domain.CodeProductUnavailable, "breaker open")}

	_, err := newService(store, catalog).AdjustItem(context.Background(), testIdentity(t), "SKU-A", 1, "idempotency-key-000001")

	var domainErr *domain.Error
	if !errors.As(err, &domainErr) || domainErr.Code != domain.CodeProductUnavailable {
		t.Fatalf("err = %v, want PRODUCT_UNAVAILABLE", err)
	}
	if len(store.upsertCalls) != 1 {
		t.Fatalf("the script ran %d times; a rejected write must not retry", len(store.upsertCalls))
	}
}

// Any other domain error is the caller's answer, and must not trigger a
// Product lookup.
func TestAdjustItemDoesNotRefreshOnOtherDomainErrors(t *testing.T) {
	store := &fakeStore{upsertErrors: []error{domain.NewError(domain.CodeCartFull, "full")}}
	catalog := &fakeCatalog{}

	_, err := newService(store, catalog).AdjustItem(context.Background(), testIdentity(t), "SKU-A", 1, "idempotency-key-000001")

	var domainErr *domain.Error
	if !errors.As(err, &domainErr) || domainErr.Code != domain.CodeCartFull {
		t.Fatalf("err = %v, want CART_FULL", err)
	}
	if catalog.skuCalls != 0 {
		t.Fatalf("product was called %d times for a CART_FULL result", catalog.skuCalls)
	}
}

// ── GetCart ──────────────────────────────────────────────────────────────

func TestGetCartReturnsAnEmptyCartOnAMiss(t *testing.T) {
	catalog := &fakeCatalog{}

	view, err := newService(&fakeStore{}, catalog).GetCart(context.Background(), testIdentity(t))
	if err != nil {
		t.Fatalf("GetCart: %v", err)
	}
	if len(view.Items) != 0 {
		t.Fatalf("got %d items for a missing cart", len(view.Items))
	}
	if view.Freshness != domain.FreshnessCurrent {
		t.Fatalf("freshness = %s, want CURRENT", view.Freshness)
	}
	if catalog.batchCall != 0 {
		t.Fatal("product was called for an empty cart")
	}
}

func TestGetCartEnrichesWithLiveProductData(t *testing.T) {
	store := &fakeStore{items: []domain.Item{{
		SkuID: "SKU-A",
		StoredItem: domain.StoredItem{
			Quantity:       2,
			UnitPriceAtAdd: domain.Money{AmountMinor: 100, Currency: "VND"},
			IsAvailable:    true,
		},
	}}}
	catalog := &fakeCatalog{batch: map[string]domain.ProductSnapshot{
		"SKU-A": {
			SkuID:       "SKU-A",
			Price:       domain.Money{AmountMinor: 150, Currency: "VND"},
			IsAvailable: true,
		},
	}}

	view, err := newService(store, catalog).GetCart(context.Background(), testIdentity(t))
	if err != nil {
		t.Fatalf("GetCart: %v", err)
	}
	if view.Freshness != domain.FreshnessCurrent {
		t.Fatalf("freshness = %s, want CURRENT", view.Freshness)
	}

	line := view.Items[0]
	if line.CurrentPrice == nil || line.CurrentPrice.AmountMinor != 150 {
		t.Fatalf("current_price = %+v, want 150", line.CurrentPrice)
	}
	// The price at add time is frozen and must survive enrichment untouched.
	if line.UnitPriceAtAdd.AmountMinor != 100 {
		t.Fatalf("unit_price_at_add = %d, want 100", line.UnitPriceAtAdd.AmountMinor)
	}
	if view.TotalUnits() != 2 {
		t.Fatalf("total units = %d, want 2", view.TotalUnits())
	}
}

// A Product outage degrades the read but must not fail it. The cart page does
// not die with Product Service.
func TestGetCartDegradesWhenProductFails(t *testing.T) {
	store := &fakeStore{items: []domain.Item{{
		SkuID: "SKU-A",
		StoredItem: domain.StoredItem{
			Quantity:       2,
			UnitPriceAtAdd: domain.Money{AmountMinor: 100, Currency: "VND"},
			IsAvailable:    true,
		},
	}}}
	catalog := &fakeCatalog{batchErr: domain.NewRetryableError(domain.CodeProductUnavailable, "timeout")}

	view, err := newService(store, catalog).GetCart(context.Background(), testIdentity(t))
	if err != nil {
		t.Fatalf("a product failure must not fail the read: %v", err)
	}
	if view.Freshness != domain.FreshnessDegraded {
		t.Fatalf("freshness = %s, want DEGRADED", view.Freshness)
	}
	// A stale price shown as if it were live is worse than no price at all.
	if view.Items[0].CurrentPrice != nil {
		t.Fatalf("current_price = %+v, want nil while degraded", view.Items[0].CurrentPrice)
	}
	if !view.Items[0].Available {
		t.Fatal("availability should fall back to the stored snapshot")
	}
}

// A SKU Product no longer knows must render as unavailable with no price,
// rather than disappearing or looking degraded.
func TestGetCartMarksSkusProductNoLongerSells(t *testing.T) {
	store := &fakeStore{items: []domain.Item{{
		SkuID:      "SKU-GONE",
		StoredItem: domain.StoredItem{Quantity: 1, IsAvailable: true},
	}}}
	catalog := &fakeCatalog{batch: map[string]domain.ProductSnapshot{
		"SKU-GONE": {SkuID: "SKU-GONE", IsAvailable: false},
	}}

	view, err := newService(store, catalog).GetCart(context.Background(), testIdentity(t))
	if err != nil {
		t.Fatalf("GetCart: %v", err)
	}
	if view.Freshness != domain.FreshnessCurrent {
		t.Fatalf("freshness = %s, want CURRENT", view.Freshness)
	}
	if view.Items[0].Available {
		t.Fatal("a sku product no longer sells is still marked available")
	}
	if view.Items[0].CurrentPrice != nil {
		t.Fatal("an unavailable sku carries a price")
	}
}

func TestGetCartSortsItemsDeterministically(t *testing.T) {
	store := &fakeStore{items: []domain.Item{
		{SkuID: "SKU-C"}, {SkuID: "SKU-A"}, {SkuID: "SKU-B"},
	}}
	catalog := &fakeCatalog{batch: map[string]domain.ProductSnapshot{}}

	view, err := newService(store, catalog).GetCart(context.Background(), testIdentity(t))
	if err != nil {
		t.Fatalf("GetCart: %v", err)
	}
	for i, want := range []string{"SKU-A", "SKU-B", "SKU-C"} {
		if view.Items[i].SkuID != want {
			t.Fatalf("item %d = %s, want %s", i, view.Items[i].SkuID, want)
		}
	}
}

// ── deletes ──────────────────────────────────────────────────────────────

func TestDeleteItemsTouchesActivity(t *testing.T) {
	store := &fakeStore{removeResult: port.RemoveResult{DeletedCount: 1}}

	if _, err := newService(store, &fakeCatalog{}).DeleteItems(
		context.Background(), testIdentity(t), []string{"SKU-A"}, "idempotency-key-000001",
	); err != nil {
		t.Fatalf("DeleteItems: %v", err)
	}

	cmd := store.removeCalls[0]
	if !cmd.TouchActivity {
		t.Fatal("a user delete must count as activity")
	}
	if cmd.IdempotencyKey != "idempotency-key-000001" {
		t.Fatalf("idempotency key = %q", cmd.IdempotencyKey)
	}
}

// The privacy purge goes to the store as one command for the derived identity
// and never consults Product.
func TestPurgeUserDelegatesToTheStoreWithoutCallingProduct(t *testing.T) {
	store := &fakeStore{purgeResult: port.PurgeResult{Purged: true}}
	catalog := &fakeCatalog{}
	identity := testIdentity(t)

	result, err := newService(store, catalog).PurgeUser(context.Background(), identity)
	if err != nil {
		t.Fatalf("PurgeUser: %v", err)
	}

	if !result.Purged {
		t.Fatal("the store result was not returned")
	}
	if len(store.purgeCalls) != 1 || store.purgeCalls[0].CartID != identity.CartID {
		t.Fatalf("purge calls = %+v", store.purgeCalls)
	}
	if catalog.skuCalls != 0 || catalog.batchCall != 0 {
		t.Fatal("purging a cart must not call Product")
	}
}

func TestRemovePurchasedDoesNotTouchActivityOrUseAnIdempotencyKey(t *testing.T) {
	store := &fakeStore{removeResult: port.RemoveResult{DeletedCount: 1}}

	if _, err := newService(store, &fakeCatalog{}).RemovePurchased(
		context.Background(), testIdentity(t), []string{"SKU-A"},
	); err != nil {
		t.Fatalf("RemovePurchased: %v", err)
	}

	cmd := store.removeCalls[0]
	if cmd.TouchActivity {
		t.Fatal("paying for items must not count as browsing activity")
	}
	// An empty key is what makes the store pass three KEYS instead of four,
	// which is what keeps the invocation inside one cluster slot.
	if cmd.IdempotencyKey != "" {
		t.Fatalf("idempotency key = %q, want empty", cmd.IdempotencyKey)
	}
}

// ── fingerprints ─────────────────────────────────────────────────────────

func TestFingerprintDistinguishesPayloads(t *testing.T) {
	identity := testIdentity(t)
	other, err := domain.NewIdentity("a-different-user")
	if err != nil {
		t.Fatalf("NewIdentity: %v", err)
	}

	base := adjustFingerprint(identity, "SKU-A", 1)
	cases := map[string]string{
		"same input":      adjustFingerprint(identity, "SKU-A", 1),
		"different delta": adjustFingerprint(identity, "SKU-A", 2),
		"different sku":   adjustFingerprint(identity, "SKU-B", 1),
		"different user":  adjustFingerprint(other, "SKU-A", 1),
	}
	if cases["same input"] != base {
		t.Fatal("the fingerprint is not stable for identical input")
	}
	for name, got := range cases {
		if name == "same input" {
			continue
		}
		if got == base {
			t.Fatalf("%s produced the same fingerprint", name)
		}
	}
}

// The length-prefixed encoding is what stops two different requests from
// hashing to the same bytes by shifting a delimiter across fields.
func TestFingerprintIsNotAmbiguousAcrossFieldBoundaries(t *testing.T) {
	identity := testIdentity(t)
	if adjustFingerprint(identity, "SKU-A:1", 1) == adjustFingerprint(identity, "SKU-A", 11) {
		t.Fatal("field boundaries are ambiguous in the fingerprint encoding")
	}
}

// Deleting the same set of SKUs in a different order is the same request, so a
// retry must replay rather than conflict.
func TestDeleteFingerprintIsOrderIndependent(t *testing.T) {
	identity := testIdentity(t)

	first := deleteFingerprint(identity, []string{"SKU-A", "SKU-B", "SKU-C"})
	second := deleteFingerprint(identity, []string{"SKU-C", "SKU-A", "SKU-B"})
	if first != second {
		t.Fatal("sku order changed the delete fingerprint")
	}
	if deleteFingerprint(identity, []string{"SKU-A"}) == first {
		t.Fatal("a different sku set produced the same fingerprint")
	}
}

// The caller's slice must not be reordered underneath them.
func TestDeleteFingerprintDoesNotMutateItsInput(t *testing.T) {
	skuIDs := []string{"SKU-C", "SKU-A", "SKU-B"}
	deleteFingerprint(testIdentity(t), skuIDs)

	if skuIDs[0] != "SKU-C" {
		t.Fatalf("the input slice was reordered: %v", skuIDs)
	}
}
