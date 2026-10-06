package router

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/api/handler"
	"cart-service/internal/api/media"
	"cart-service/internal/api/middleware"
	"cart-service/internal/domain"
	"cart-service/internal/domain/port"
	"cart-service/internal/service"
)

const (
	testUserID    = "0198e098-94f4-7f8f-9e16-ae42dfbd93c1"
	testIdempoKey = "idempotency-key-000001"
	itemsPath     = "/api/v1/carts/mine/items"
	cartPath      = "/api/v1/carts/mine"

	testCDNBaseURL     = "https://cdn.example.test/media"
	testPlaceholderURL = "https://cdn.example.test/media/placeholder/product.svg"
)

var testMediaURLs = media.NewURLs(testCDNBaseURL, testPlaceholderURL)

type stubStore struct {
	items        []domain.Item
	loadErr      error
	upsertResult port.UpsertResult
	upsertErr    error
	removeResult port.RemoveResult
	removeErr    error
}

func (s *stubStore) LoadItems(context.Context, domain.Identity) ([]domain.Item, error) {
	return s.items, s.loadErr
}

func (s *stubStore) Upsert(context.Context, port.UpsertCommand) (port.UpsertResult, error) {
	return s.upsertResult, s.upsertErr
}

func (s *stubStore) Remove(context.Context, port.RemoveCommand) (port.RemoveResult, error) {
	return s.removeResult, s.removeErr
}

func (s *stubStore) MarkAbandoned(context.Context, port.MarkAbandonedCommand) (port.MarkAbandonedResult, error) {
	return port.MarkAbandonedResult{}, nil
}

func (s *stubStore) DueCarts(context.Context, int, time.Time, int) ([]port.DueCart, error) {
	return nil, nil
}

func (s *stubStore) DropIndexMember(context.Context, int, string) error { return nil }
func (s *stubStore) Ping(context.Context) error                         { return nil }

type stubCatalog struct {
	batch    map[string]domain.ProductSnapshot
	batchErr error
	sku      domain.ProductSnapshot
	skuErr   error
}

func (s *stubCatalog) GetSku(context.Context, string) (domain.ProductSnapshot, error) {
	return s.sku, s.skuErr
}

func (s *stubCatalog) GetBatchSkus(context.Context, []string) (map[string]domain.ProductSnapshot, error) {
	return s.batch, s.batchErr
}

func (s *stubCatalog) Ready(context.Context) error { return nil }

func newTestEngine(store port.CartStore, catalog port.ProductCatalog) http.Handler {
	cartService := service.New(store, catalog, service.Config{ProductValidationTTL: time.Minute})
	return New(Config{
		Cart:   handler.NewCart(cartService, testMediaURLs, zap.NewNop()),
		Health: handler.NewHealth(map[string]handler.Probe{}, time.Second),
		Logger: zap.NewNop(),
	})
}

func do(t *testing.T, engine http.Handler, method, path, body string, headers map[string]string) *httptest.ResponseRecorder {
	t.Helper()

	var reader *bytes.Reader
	if body == "" {
		reader = bytes.NewReader(nil)
	} else {
		reader = bytes.NewReader([]byte(body))
	}
	req := httptest.NewRequest(method, path, reader)
	req.Header.Set("Content-Type", "application/json")
	for key, value := range headers {
		req.Header.Set(key, value)
	}

	recorder := httptest.NewRecorder()
	engine.ServeHTTP(recorder, req)
	return recorder
}

func authedWrite() map[string]string {
	return map[string]string{
		middleware.HeaderUserID:         testUserID,
		middleware.HeaderIdempotencyKey: testIdempoKey,
	}
}

func decode[T any](t *testing.T, recorder *httptest.ResponseRecorder) T {
	t.Helper()

	var value T
	if err := json.Unmarshal(recorder.Body.Bytes(), &value); err != nil {
		t.Fatalf("decode response %s: %v", recorder.Body.String(), err)
	}
	return value
}

// ── identity ─────────────────────────────────────────────────────────────

// Cart is behind a Gateway that strips client-supplied identity headers. With
// no trusted identity there is nothing to serve.
func TestRoutesRequireTrustedIdentity(t *testing.T) {
	engine := newTestEngine(&stubStore{}, &stubCatalog{})

	cases := []struct {
		method string
		path   string
		body   string
	}{
		{http.MethodGet, cartPath, ""},
		{http.MethodPost, itemsPath, `{"sku_id":"SKU-A","delta":1}`},
		{http.MethodDelete, itemsPath, `{"sku_ids":["SKU-A"]}`},
	}

	for _, tc := range cases {
		recorder := do(t, engine, tc.method, tc.path, tc.body, map[string]string{
			middleware.HeaderIdempotencyKey: testIdempoKey,
		})
		if recorder.Code != http.StatusUnauthorized {
			t.Fatalf("%s %s = %d, want 401", tc.method, tc.path, recorder.Code)
		}

		problem := decode[map[string]any](t, recorder)
		if problem["code"] != string(domain.CodeUnauthenticated) {
			t.Fatalf("code = %v, want UNAUTHENTICATED", problem["code"])
		}
	}
}

// ── GET ──────────────────────────────────────────────────────────────────

func TestGetEmptyCart(t *testing.T) {
	engine := newTestEngine(&stubStore{}, &stubCatalog{})

	recorder := do(t, engine, http.MethodGet, cartPath, "", map[string]string{
		middleware.HeaderUserID: testUserID,
	})
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	// Cart contents are personal data and must never be shared-cached.
	if got := recorder.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", got)
	}

	body := decode[map[string]any](t, recorder)
	if body["catalog_freshness"] != "CURRENT" {
		t.Fatalf("catalog_freshness = %v", body["catalog_freshness"])
	}
	if body["total_unique_skus"] != float64(0) || body["total_units"] != float64(0) {
		t.Fatalf("totals = %v / %v", body["total_unique_skus"], body["total_units"])
	}
	// The contract requires items to be present, so it must serialise as []
	// rather than null.
	if items, ok := body["items"].([]any); !ok || len(items) != 0 {
		t.Fatalf("items = %v, want an empty array", body["items"])
	}
}

func TestGetCartRendersTheContractShape(t *testing.T) {
	store := &stubStore{items: []domain.Item{{
		SkuID: "SKU-A",
		StoredItem: domain.StoredItem{
			ProductID:         "PRODUCT-A",
			ProductName:       "Ao thun do",
			Quantity:          2,
			UnitPriceAtAdd:    domain.Money{AmountMinor: 125000, Currency: "VND"},
			VariantAttributes: domain.VariantAttributes{"color": "red"},
			IsAvailable:       true,
			AddedAt:           domain.Millis(1780000000000),
			UpdatedAt:         domain.Millis(1780000000000),
		},
	}}}
	catalog := &stubCatalog{batch: map[string]domain.ProductSnapshot{
		"SKU-A": {SkuID: "SKU-A", Price: domain.Money{AmountMinor: 130000, Currency: "VND"}, IsAvailable: true},
	}}

	recorder := do(t, newTestEngine(store, catalog), http.MethodGet, cartPath, "", map[string]string{
		middleware.HeaderUserID: testUserID,
	})
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}

	body := decode[map[string]any](t, recorder)
	item := body["items"].([]any)[0].(map[string]any)

	for _, required := range []string{
		"sku_id", "product_id", "product_name", "quantity",
		"unit_price_at_add", "current_price", "is_available", "added_at", "updated_at",
	} {
		if _, ok := item[required]; !ok {
			t.Fatalf("item is missing the required field %q", required)
		}
	}
	if item["added_at"] != "2026-05-28T20:26:40Z" {
		t.Fatalf("added_at = %v, want an RFC 3339 timestamp", item["added_at"])
	}
	if body["total_units"] != float64(2) {
		t.Fatalf("total_units = %v, want 2", body["total_units"])
	}
}

// Product supplies a storage key, never a URL. The browser must receive an
// absolute CDN URL (the contract declares format: uri), the placeholder when
// the SKU has no image, and a legacy absolute URL unchanged.
func TestGetCartRendersImageStorageKeysAsCDNURLs(t *testing.T) {
	line := func(skuID, imageKey string) domain.Item {
		return domain.Item{SkuID: skuID, StoredItem: domain.StoredItem{
			ProductID:      "PRODUCT-" + skuID,
			ProductName:    "Ao thun",
			Quantity:       1,
			UnitPriceAtAdd: domain.Money{AmountMinor: 100000, Currency: "VND"},
			ImageKey:       imageKey,
			IsAvailable:    true,
			AddedAt:        domain.Millis(1780000000000),
			UpdatedAt:      domain.Millis(1780000000000),
		}}
	}
	store := &stubStore{items: []domain.Item{
		line("SKU-KEY", "products/ao thun/hero.jpg"),
		line("SKU-NONE", ""),
		line("SKU-LEGACY", "https://example.test/products/tshirt-red.png"),
	}}
	// Product is down: the stored snapshot alone must still render valid URLs.
	catalog := &stubCatalog{batchErr: domain.NewRetryableError(domain.CodeProductUnavailable, "timeout")}

	recorder := do(t, newTestEngine(store, catalog), http.MethodGet, cartPath, "", map[string]string{
		middleware.HeaderUserID: testUserID,
	})
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}

	want := map[string]string{
		"SKU-KEY":    testCDNBaseURL + "/products/ao%20thun/hero.jpg",
		"SKU-NONE":   testPlaceholderURL,
		"SKU-LEGACY": "https://example.test/products/tshirt-red.png",
	}
	body := decode[map[string]any](t, recorder)
	for _, raw := range body["items"].([]any) {
		item := raw.(map[string]any)
		sku := item["sku_id"].(string)
		if item["image_url"] != want[sku] {
			t.Fatalf("%s image_url = %v, want %s", sku, item["image_url"], want[sku])
		}
	}
}

// current_price is a required member that may be null, so it has to be
// emitted rather than omitted while degraded.
func TestGetCartEmitsNullCurrentPriceWhenDegraded(t *testing.T) {
	store := &stubStore{items: []domain.Item{{
		SkuID:      "SKU-A",
		StoredItem: domain.StoredItem{Quantity: 1, IsAvailable: true},
	}}}
	catalog := &stubCatalog{batchErr: domain.NewRetryableError(domain.CodeProductUnavailable, "timeout")}

	recorder := do(t, newTestEngine(store, catalog), http.MethodGet, cartPath, "", map[string]string{
		middleware.HeaderUserID: testUserID,
	})
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 while degraded", recorder.Code)
	}

	body := decode[map[string]any](t, recorder)
	if body["catalog_freshness"] != "DEGRADED" {
		t.Fatalf("catalog_freshness = %v, want DEGRADED", body["catalog_freshness"])
	}

	item := body["items"].([]any)[0].(map[string]any)
	value, present := item["current_price"]
	if !present {
		t.Fatal("current_price was omitted; the contract requires the member")
	}
	if value != nil {
		t.Fatalf("current_price = %v, want null", value)
	}
}

func TestGetCartReturns503WhenRedisIsDown(t *testing.T) {
	store := &stubStore{loadErr: domain.NewRetryableError(domain.CodeCartStoreUnavailable, "redis is unavailable")}

	recorder := do(t, newTestEngine(store, &stubCatalog{}), http.MethodGet, cartPath, "", map[string]string{
		middleware.HeaderUserID: testUserID,
	})
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", recorder.Code)
	}
	if got := recorder.Header().Get("Content-Type"); got != "application/problem+json" {
		t.Fatalf("Content-Type = %q, want application/problem+json", got)
	}

	problem := decode[map[string]any](t, recorder)
	for _, required := range []string{"type", "title", "status", "detail", "instance", "code"} {
		if _, ok := problem[required]; !ok {
			t.Fatalf("problem document is missing %q", required)
		}
	}
	if problem["retryable"] != true {
		t.Fatalf("retryable = %v, want true", problem["retryable"])
	}
}

// ── POST /items ──────────────────────────────────────────────────────────

func TestAdjustItemReturns201ForANewSku(t *testing.T) {
	store := &stubStore{upsertResult: port.UpsertResult{
		SkuID: "SKU-A", Quantity: 1, Created: true, UpdatedAt: domain.Millis(1780000000000),
	}}

	recorder := do(t, newTestEngine(store, &stubCatalog{}), http.MethodPost, itemsPath,
		`{"sku_id":"SKU-A","delta":1}`, authedWrite())
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", recorder.Code)
	}

	body := decode[map[string]any](t, recorder)
	if body["created"] != true || body["quantity"] != float64(1) {
		t.Fatalf("body = %v", body)
	}
	if body["updated_at"] != "2026-05-28T20:26:40Z" {
		t.Fatalf("updated_at = %v, want an RFC 3339 timestamp", body["updated_at"])
	}
}

func TestAdjustItemReturns200ForAnExistingSku(t *testing.T) {
	store := &stubStore{upsertResult: port.UpsertResult{SkuID: "SKU-A", Quantity: 4}}

	recorder := do(t, newTestEngine(store, &stubCatalog{}), http.MethodPost, itemsPath,
		`{"sku_id":"SKU-A","delta":3}`, authedWrite())
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
}

func TestAdjustItemRequiresAnIdempotencyKey(t *testing.T) {
	engine := newTestEngine(&stubStore{}, &stubCatalog{})

	cases := map[string]string{
		"missing":  "",
		"tooshort": "short",
	}
	for name, key := range cases {
		headers := map[string]string{middleware.HeaderUserID: testUserID}
		if key != "" {
			headers[middleware.HeaderIdempotencyKey] = key
		}

		recorder := do(t, engine, http.MethodPost, itemsPath, `{"sku_id":"SKU-A","delta":1}`, headers)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s idempotency key: status = %d, want 400", name, recorder.Code)
		}
	}
}

// delta is a relative change and zero is meaningless, so the contract forbids
// it outright.
func TestAdjustItemRejectsInvalidBodies(t *testing.T) {
	engine := newTestEngine(&stubStore{}, &stubCatalog{})

	cases := map[string]string{
		"zero delta":      `{"sku_id":"SKU-A","delta":0}`,
		"missing delta":   `{"sku_id":"SKU-A"}`,
		"missing sku":     `{"delta":1}`,
		"delta too big":   `{"sku_id":"SKU-A","delta":101}`,
		"delta too small": `{"sku_id":"SKU-A","delta":-101}`,
		"not json":        `{`,
	}

	for name, body := range cases {
		recorder := do(t, engine, http.MethodPost, itemsPath, body, authedWrite())
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", name, recorder.Code)
		}
		if got := recorder.Header().Get("Content-Type"); got != "application/problem+json" {
			t.Fatalf("%s: Content-Type = %q", name, got)
		}
	}
}

func TestAdjustItemMapsDomainErrorsOntoContractStatuses(t *testing.T) {
	cases := map[domain.Code]int{
		domain.CodeCartFull:             http.StatusUnprocessableEntity,
		domain.CodeProductNotAvailable:  http.StatusUnprocessableEntity,
		domain.CodeQuantityInvalid:      http.StatusUnprocessableEntity,
		domain.CodeIdempotencyConflict:  http.StatusConflict,
		domain.CodeCartStoreUnavailable: http.StatusServiceUnavailable,
		domain.CodeProductUnavailable:   http.StatusServiceUnavailable,
	}

	for code, wantStatus := range cases {
		store := &stubStore{upsertErr: domain.NewError(code, "test")}
		recorder := do(t, newTestEngine(store, &stubCatalog{}), http.MethodPost, itemsPath,
			`{"sku_id":"SKU-A","delta":1}`, authedWrite())

		if recorder.Code != wantStatus {
			t.Fatalf("%s: status = %d, want %d", code, recorder.Code, wantStatus)
		}
		problem := decode[map[string]any](t, recorder)
		if problem["code"] != string(code) {
			t.Fatalf("%s: problem code = %v", code, problem["code"])
		}
	}
}

// ── DELETE /items ────────────────────────────────────────────────────────

func TestDeleteItems(t *testing.T) {
	store := &stubStore{removeResult: port.RemoveResult{DeletedCount: 2}}

	recorder := do(t, newTestEngine(store, &stubCatalog{}), http.MethodDelete, itemsPath,
		`{"sku_ids":["SKU-A","SKU-B"]}`, authedWrite())
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}

	body := decode[map[string]any](t, recorder)
	if body["deleted_count"] != float64(2) {
		t.Fatalf("deleted_count = %v, want 2", body["deleted_count"])
	}
}

func TestDeleteItemsRejectsInvalidBodies(t *testing.T) {
	engine := newTestEngine(&stubStore{}, &stubCatalog{})

	longList := `{"sku_ids":[`
	for i := 0; i < 101; i++ {
		if i > 0 {
			longList += ","
		}
		longList += `"SKU-` + string(rune('A'+i%26)) + string(rune('0'+i%10)) + `"`
	}
	longList += `]}`

	cases := map[string]string{
		"empty list":   `{"sku_ids":[]}`,
		"duplicates":   `{"sku_ids":["SKU-A","SKU-A"]}`,
		"over the cap": longList,
	}

	for name, body := range cases {
		recorder := do(t, engine, http.MethodDelete, itemsPath, body, authedWrite())
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", name, recorder.Code)
		}
	}
}

// ── health ───────────────────────────────────────────────────────────────

func TestHealthEndpoints(t *testing.T) {
	engine := newTestEngine(&stubStore{}, &stubCatalog{})

	// Liveness confirms the process only, so a dependency outage never
	// restarts a healthy pod.
	if recorder := do(t, engine, http.MethodGet, "/health/live", "", nil); recorder.Code != http.StatusOK {
		t.Fatalf("/health/live = %d, want 200", recorder.Code)
	}
	if recorder := do(t, engine, http.MethodGet, "/health/ready", "", nil); recorder.Code != http.StatusOK {
		t.Fatalf("/health/ready = %d, want 200", recorder.Code)
	}
}

func TestReadinessFailsWhenADependencyIsDown(t *testing.T) {
	cartService := service.New(&stubStore{}, &stubCatalog{}, service.Config{})
	engine := New(Config{
		Cart: handler.NewCart(cartService, testMediaURLs, zap.NewNop()),
		Health: handler.NewHealth(map[string]handler.Probe{
			"redis": func(context.Context) error { return domain.NewError(domain.CodeCartStoreUnavailable, "down") },
		}, time.Second),
		Logger: zap.NewNop(),
	})

	recorder := do(t, engine, http.MethodGet, "/health/ready", "", nil)
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("/health/ready = %d, want 503", recorder.Code)
	}
}
