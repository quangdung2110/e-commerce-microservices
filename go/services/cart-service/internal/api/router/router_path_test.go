package router

import (
	"net/http"
	"strings"
	"testing"

	"cart-service/internal/api/middleware"
	"cart-service/internal/domain"
)

// Gin attaches Identity and ServiceIdentity to route groups, so the guard that runs is always the one of
// the route that matched, and gin matches on the decoded path (no UseRawPath). These tests pin that for
// the spellings that bypassed the Java filters (Inventory I3, audit X5): a percent-encoded prefix keeps
// the guard, a ';param' never reaches a handler, and a cart with items is never served without a
// credential.

func leakyStore() *stubStore {
	return &stubStore{items: []domain.Item{
		{SkuID: "SKU-LEAK", StoredItem: domain.StoredItem{ProductID: "PRODUCT-LEAK", Quantity: 1, ProductName: "LEAK",
			UnitPriceAtAdd: domain.Money{AmountMinor: 1, Currency: "VND"}, IsAvailable: true}},
	}}
}

func TestInternalRouteKeepsItsGuardForEncodedAndParameterisedPaths(t *testing.T) {
	engine := newSnapshotEngine(leakyStore())
	id := testUserID

	// An encoded spelling decodes to the real route, so the guard must still apply: 401 / 403, never 200.
	for _, path := range []string{
		"/%69nternal/v1/carts/" + id + "/checkout-snapshot",
		"/internal/v1/carts/" + id + "/checkout%2Dsnapshot",
		"/internal/v1/%63arts/" + id + "/checkout-snapshot",
	} {
		if got := do(t, engine, http.MethodGet, path, "", nil).Code; got != http.StatusUnauthorized {
			t.Errorf("anonymous %s = %d, want 401", path, got)
		}
		other := do(t, engine, http.MethodGet, path, "", map[string]string{middleware.HeaderServiceIdentity: "order-service"})
		if other.Code != http.StatusForbidden {
			t.Errorf("order-service %s = %d, want 403", path, other.Code)
		}
		allowed := do(t, engine, http.MethodGet, path, "", checkoutCaller())
		if allowed.Code != http.StatusOK {
			t.Errorf("checkout-service %s = %d, want 200", path, allowed.Code)
		}
	}

	// A ';param' (Go keeps it in URL.Path) matches no route: 404, and nothing from the cart in the body.
	for _, path := range []string{
		"/internal/v1;x=1/carts/" + id + "/checkout-snapshot",
		"/internal/v1/carts;x=1/" + id + "/checkout-snapshot",
		"/internal/v1/carts/" + id + "/checkout-snapshot;x=1",
		"//internal/v1/carts/" + id + "/checkout-snapshot",
	} {
		for _, headers := range []map[string]string{nil, checkoutCaller()} {
			recorder := do(t, engine, http.MethodGet, path, "", headers)
			if recorder.Code == http.StatusOK || strings.Contains(recorder.Body.String(), "SKU-LEAK") {
				t.Errorf("%s (headers %v) = %d %s, want a refusal", path, headers, recorder.Code, recorder.Body.String())
			}
		}
	}
}

func TestPublicRouteKeepsItsGuardForEncodedAndParameterisedPaths(t *testing.T) {
	engine := newTestEngine(leakyStore(), &stubCatalog{})

	for _, path := range []string{
		"/%61pi/v1/carts/mine",
		"/api/v1/carts/%6dine",
		"/api/v1/carts/mine/",
	} {
		if recorder := do(t, engine, http.MethodGet, path, "", nil); recorder.Code == http.StatusOK ||
			strings.Contains(recorder.Body.String(), "SKU-LEAK") {
			t.Errorf("anonymous %s = %d %s, want a refusal", path, recorder.Code, recorder.Body.String())
		}
	}
	for _, path := range []string{"/%61pi/v1/carts/mine", "/api/v1/carts/%6dine"} {
		if got := do(t, engine, http.MethodGet, path, "", nil).Code; got != http.StatusUnauthorized {
			t.Errorf("anonymous %s = %d, want 401 (same guard as the canonical route)", path, got)
		}
	}
	for _, path := range []string{"/api/v1;x=1/carts/mine", "/api/v1/carts/mine;x=1"} {
		recorder := do(t, engine, http.MethodGet, path, "", map[string]string{middleware.HeaderUserID: testUserID})
		if recorder.Code != http.StatusNotFound || strings.Contains(recorder.Body.String(), "SKU-LEAK") {
			t.Errorf("%s = %d %s, want 404", path, recorder.Code, recorder.Body.String())
		}
	}
}
