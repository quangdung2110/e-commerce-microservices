package templates

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestBuildModelStrictness(t *testing.T) {
	cases := []struct {
		name     string
		template string
		data     string
		wantErr  string // substring; "" = success
	}{
		{"unknown template", "NOPE", `{}`, "unknown template"},
		{"empty data", "REGISTRATION_VERIFICATION", ``, "empty template_data"},
		{"not an object", "REGISTRATION_VERIFICATION", `"482913"`, "decode"},
		{"unknown field rejected", "REGISTRATION_VERIFICATION", `{"otp":"482913","extra":1}`, "unknown field"},
		{"missing required otp", "REGISTRATION_VERIFICATION", `{}`, "otp: must be 6 digits"},
		{"otp wrong shape", "REGISTRATION_VERIFICATION", `{"otp":"12345"}`, "otp"},
		{"trailing data", "REGISTRATION_VERIFICATION", `{"otp":"482913"} {}`, "trailing data"},
		{"common fields are not template_data", "PASSWORD_RESET", `{"reset_url":"https://x","DeliveryID":"a"}`, "unknown field"},
		{"type mismatch", "ORDER_CONFIRMED", `{"order_id":"1","order_code":"c","placed_at":"2026-09-20T10:15:02Z","items":[],"subtotal_minor":"12"}`, "decode"},
		{"bad date", "EMAIL_CHANGED", `{"event":"EMAIL_CHANGED","changed_at":"yesterday"}`, "decode"},
		{"wrong event const", "EMAIL_CHANGED", `{"event":"PASSWORD_CHANGED","changed_at":"2026-09-20T10:15:02Z"}`, "event: want"},
		{"missing date", "ACCOUNT_LOCKED", `{"event":"ACCOUNT_LOCKED","locked_at":"2026-09-20T10:15:02Z"}`, "locked_until: required"},
		{"empty items", "ORDER_COMPLETED", `{"order_id":"1","order_code":"c","completed_at":"2026-09-20T10:15:02Z","items":[],"review_url":"https://x"}`, "items: must not be empty"},
		{"zero quantity", "ORDER_COMPLETED", `{"order_id":"1","order_code":"c","completed_at":"2026-09-20T10:15:02Z","items":[{"name":"a","quantity":0}],"review_url":"https://x"}`, "quantity"},
		{"negative amount", "ORDER_REFUNDED", `{"order_id":"1","order_code":"c","refund_id":"r","amount_minor":-5,"currency":"VND","refunded_at":"2026-09-20T10:15:02Z","order_url":"https://x"}`, "amount_minor"},
		{"unknown cancel reason", "ORDER_CANCELLED", `{"order_id":"1","order_code":"c","cancelled_at":"2026-09-20T10:15:02Z","reason":"fraud","refund_expected":false,"order_url":"https://x"}`, "reason"},
		{"unknown item field", "ORDER_CONFIRMED", `{"order_id":"1","order_code":"c","placed_at":"2026-09-20T10:15:02Z","items":[{"name":"a","quantity":1,"unit_price_minor":1,"sku":"x"}],"subtotal_minor":1,"discount_minor":0,"shipping_fee_minor":0,"total_minor":1,"currency":"VND","payment_method_label":"COD","shipping_address":{"recipient_name":"A","ward":"","district":"","city":""},"order_url":"https://x"}`, "unknown field"},
		{"valid minimal order", "ORDER_CONFIRMED", `{"order_id":"1","order_code":"c","placed_at":"2026-09-20T10:15:02Z","items":[{"name":"a","quantity":1,"unit_price_minor":1}],"subtotal_minor":1,"discount_minor":0,"shipping_fee_minor":0,"total_minor":1,"currency":"VND","payment_method_label":"COD","shipping_address":{"recipient_name":"A","ward":"","district":"","city":""},"order_url":"https://x"}`, ""},
		{"valid cart without cycle", "CART_ABANDONED", `{"cart_id":"c","cart_url":"https://x"}`, ""},
		{"valid payment failed without deadline", "ORDER_PAYMENT_FAILED", `{"order_id":"1","order_code":"c","amount_minor":1,"currency":"VND","attempt":1,"can_retry":false,"order_url":"https://x"}`, ""},
		{"retryable payment failed needs deadline", "ORDER_PAYMENT_FAILED", `{"order_id":"1","order_code":"c","amount_minor":1,"currency":"VND","attempt":1,"can_retry":true,"order_url":"https://x"}`, "retry_deadline: required"},
		{"cancel never expects refund", "ORDER_CANCELLED", `{"order_id":"1","order_code":"c","cancelled_at":"2026-09-20T10:15:02Z","reason":"buyer","refund_expected":true,"order_url":"https://x"}`, "refund_expected: must be false"},
		{"valid checkout failed cancel", "ORDER_CANCELLED", `{"order_id":"1","order_code":"c","cancelled_at":"2026-09-20T10:15:02Z","reason":"checkout_failed","refund_expected":false,"order_url":"https://x"}`, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			m, err := buildModel(tc.template, json.RawMessage(tc.data))
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if m == nil {
					t.Fatal("nil model")
				}
				return
			}
			if err == nil {
				t.Fatalf("expected error containing %q, got nil", tc.wantErr)
			}
			if !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("error %q does not contain %q", err.Error(), tc.wantErr)
			}
		})
	}
}

func TestBuildModelDecodesTimesAndOptionals(t *testing.T) {
	m, err := buildModel("ORDER_PAYMENT_FAILED", json.RawMessage(`{"order_id":"1","order_code":"c","amount_minor":1,"currency":"VND","attempt":2,"can_retry":true,"retry_deadline":"2026-09-20T16:15:02+07:00","order_url":"https://x"}`))
	if err != nil {
		t.Fatal(err)
	}
	pf := m.(*OrderPaymentFailed)
	if !pf.HasRetryDeadline() || !pf.Deadline().Equal(time.Date(2026, 9, 20, 9, 15, 2, 0, time.UTC)) {
		t.Fatalf("retry_deadline not parsed: %+v", pf.RetryDeadline)
	}
	oc, err := buildModel("ORDER_CONFIRMED", json.RawMessage(`{"order_id":"1","order_code":"c","placed_at":"2026-09-20T10:15:02Z","items":[{"name":"a","variant_name":null,"quantity":2,"unit_price_minor":10},{"name":"b","variant_name":"Đỏ","quantity":1,"unit_price_minor":5}],"subtotal_minor":25,"discount_minor":0,"shipping_fee_minor":0,"total_minor":25,"currency":"VND","payment_method_label":"COD","shipping_address":{"recipient_name":"A","ward":"P1","district":"","city":"HCM"},"order_url":"https://x"}`))
	if err != nil {
		t.Fatal(err)
	}
	o := oc.(*OrderConfirmed)
	if o.Items[0].Variant() != "" || o.Items[1].Variant() != "Đỏ" || o.Items[0].LineTotalMinor() != 20 || o.ItemCount() != 3 {
		t.Fatalf("item helpers: %+v", o.Items)
	}
	if o.ShippingAddress.Line() != "P1, HCM" {
		t.Fatalf("address line: %q", o.ShippingAddress.Line())
	}
	if o.HiddenItems() != 0 || len(o.ShownItems()) != 2 {
		t.Fatalf("shown/hidden: %d %d", len(o.ShownItems()), o.HiddenItems())
	}
}

func TestShownItemsCap(t *testing.T) {
	o := &OrderConfirmed{Items: make([]OrderItem, maxEmailItems+5)}
	if len(o.ShownItems()) != maxEmailItems || o.HiddenItems() != 5 {
		t.Fatalf("cap: shown %d hidden %d", len(o.ShownItems()), o.HiddenItems())
	}
	c := &OrderCompleted{Items: make([]CompletedItem, 3)}
	if len(c.ShownItems()) != 3 || c.HiddenItems() != 0 {
		t.Fatalf("no cap needed: shown %d hidden %d", len(c.ShownItems()), c.HiddenItems())
	}
}

func TestDeepLink(t *testing.T) {
	data := json.RawMessage(`{"order_url":"https://shop/x","bad":"javascript:alert(1)","num":5}`)
	if l, err := deepLink(data, ""); err != nil || l != nil {
		t.Fatalf("empty field: %v %v", l, err)
	}
	if l, err := deepLink(data, "order_url"); err != nil || l == nil || *l != "https://shop/x" {
		t.Fatalf("order_url: %v %v", l, err)
	}
	if l, err := deepLink(data, "bad"); err != nil || l != nil {
		t.Fatalf("javascript: link must be neutralised to nil: %v %v", l, err)
	}
	if l, err := deepLink(data, "missing"); err != nil || l != nil {
		t.Fatalf("missing field: %v %v", l, err)
	}
	if _, err := deepLink(data, "num"); err == nil {
		t.Fatal("non-string field must error")
	}
}

func TestHasJSONField(t *testing.T) {
	m, _ := newModel("ORDER_CONFIRMED")
	if !hasJSONField(m, "order_url") || hasJSONField(m, "DeliveryID") || hasJSONField(m, "nope") {
		t.Fatal("hasJSONField mismatch")
	}
}

func TestEveryCatalogTemplateHasAModel(t *testing.T) {
	for _, id := range []string{
		"REGISTRATION_VERIFICATION", "EMAIL_CHANGE_VERIFICATION", "PASSWORD_RESET", "EMAIL_CHANGED", "PASSWORD_CHANGED",
		"ACCOUNT_LOCKED", "ACCOUNT_UNLOCKED", "ORDER_CONFIRMED", "ORDER_PAYMENT_FAILED", "ORDER_CANCELLED",
		"ORDER_COMPLETED", "ORDER_REFUNDED", "REVIEW_REPLIED", "REVIEW_REMOVED", "CART_ABANDONED",
	} {
		if _, ok := newModel(id); !ok {
			t.Errorf("no model for %s", id)
		}
	}
}
