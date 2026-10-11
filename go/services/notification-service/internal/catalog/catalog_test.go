package catalog

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/ecommerce/notification-service/internal/domain/entity"
)

const contractsTemplates = "../../../../../contracts/events/notification/v1/templates"

func TestLoad(t *testing.T) {
	c, err := Load()
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if got := len(c.Templates()); got != 15 {
		t.Fatalf("expected 15 templates, got %d", got)
	}
	tpl, ok := c.Get("ORDER_CONFIRMED")
	if !ok {
		t.Fatal("ORDER_CONFIRMED missing")
	}
	if tpl.Lane != entity.LaneTransactional || tpl.MaxAge != 6*time.Hour || tpl.DedupMode != DedupRequired {
		t.Fatalf("unexpected ORDER_CONFIRMED: %+v", tpl)
	}
	if !tpl.AllowsChannel(entity.ChannelInApp) || !tpl.GovernedByFlag("email_enabled") {
		t.Fatalf("unexpected channel/governance: %+v", tpl)
	}
	otp, _ := c.Get("REGISTRATION_VERIFICATION")
	if !otp.MaxAgeFromRequest || !otp.IsSecret() || otp.DedupMode != DedupNone || otp.AllowsChannel(entity.ChannelInApp) {
		t.Fatalf("unexpected REGISTRATION_VERIFICATION: %+v", otp)
	}
	warn, _ := c.Get("EMAIL_CHANGED")
	if warn.IsSecret() || warn.MaxAge != 24*time.Hour || len(warn.GovernedBy) != 0 {
		t.Fatalf("unexpected EMAIL_CHANGED: %+v", warn)
	}
	cart, ok := c.ByFactType("com.ecommerce.cart.abandoned.v1")
	if !ok || cart.ID != "CART_ABANDONED" || cart.Lane != entity.LaneBulk {
		t.Fatalf("fact mapping: %+v %v", cart, ok)
	}
	rev, ok := c.ByReviewMessage("reviewReplyNotificationRequested")
	if !ok || rev.ID != "REVIEW_REPLIED" {
		t.Fatalf("review mapping: %+v %v", rev, ok)
	}
	bulk := c.Lane(entity.LaneBulk)
	if bulk.QuietFrom != "22:00" || bulk.QuietTo != "08:00" || bulk.FrequencyCapPerDay != 2 || bulk.DefaultTimezone != "Asia/Ho_Chi_Minh" {
		t.Fatalf("bulk policy: %+v", bulk)
	}
	crit := c.Lane(entity.LaneCritical)
	if !crit.IgnoresEmailEnabled || !crit.IgnoresSuppression || !crit.IgnoresQuietHours || !crit.IgnoresFrequencyCap {
		t.Fatalf("critical policy: %+v", crit)
	}
}

func TestValidateTemplateData(t *testing.T) {
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	good := []byte(`{"order_id":"1000234","order_code":"ORD-1000234","cancelled_at":"2026-09-20T10:15:02Z","reason":"buyer","refund_expected":false,"order_url":"https://shop.example.com/orders/1000234"}`)
	if err := c.ValidateTemplateData("ORDER_CANCELLED", good); err != nil {
		t.Fatalf("valid data rejected: %v", err)
	}
	withEmail := []byte(`{"order_id":"1000234","order_code":"ORD-1000234","cancelled_at":"2026-09-20T10:15:02Z","reason":"buyer","refund_expected":false,"order_url":"https://shop.example.com/orders/1000234","email":"a@b.c"}`)
	if err := c.ValidateTemplateData("ORDER_CANCELLED", withEmail); err == nil {
		t.Fatal("additionalProperties should reject email")
	}
	checkoutFailed := []byte(`{"order_id":"1000234","order_code":"ORD-1000234","cancelled_at":"2026-09-20T10:15:02Z","reason":"checkout_failed","refund_expected":false,"order_url":"https://shop.example.com/orders/1000234"}`)
	if err := c.ValidateTemplateData("ORDER_CANCELLED", checkoutFailed); err != nil {
		t.Fatalf("checkout_failed rejected: %v", err)
	}
	refund := []byte(`{"order_id":"1000234","order_code":"ORD-1000234","cancelled_at":"2026-09-20T10:15:02Z","reason":"buyer","refund_expected":true,"order_url":"https://shop.example.com/orders/1000234"}`)
	if err := c.ValidateTemplateData("ORDER_CANCELLED", refund); err == nil {
		t.Fatal("refund_expected must be const false")
	}
	retryNoDeadline := []byte(`{"order_id":"1000234","order_code":"ORD-1000234","amount_minor":1,"currency":"VND","attempt":1,"can_retry":true,"order_url":"https://shop.example.com/orders/1000234"}`)
	if err := c.ValidateTemplateData("ORDER_PAYMENT_FAILED", retryNoDeadline); err == nil {
		t.Fatal("can_retry=true without retry_deadline should be rejected")
	}
	if err := c.ValidateTemplateData("CART_ABANDONED", []byte(`{"cart_id":"c1:x","cart_url":"http://localhost:3000/cart"}`)); err != nil {
		t.Fatalf("cart data rejected: %v", err)
	}
	if err := c.ValidateTemplateData("PASSWORD_RESET", []byte(`{"reset_url":"not a url"}`)); err == nil {
		t.Fatal("format uri should be asserted")
	}
}

// The embedded catalog and the five Order schemas must be byte-equal with the
// public contract (12-render.md §4 step 5, D-13).
func TestEmbeddedCopiesMatchContracts(t *testing.T) {
	want, err := os.ReadFile(filepath.Join(contractsTemplates, "catalog.yaml"))
	if err != nil {
		t.Fatalf("contracts not available (fix contractsTemplates): %v", err)
	}
	if !bytes.Equal(want, Raw()) {
		t.Fatal("internal/catalog/catalog.yaml differs from contracts/events/notification/v1/templates/catalog.yaml")
	}
	for _, id := range []string{"ORDER_CONFIRMED", "ORDER_PAYMENT_FAILED", "ORDER_CANCELLED", "ORDER_COMPLETED", "ORDER_REFUNDED"} {
		want, err := os.ReadFile(filepath.Join(contractsTemplates, id+".schema.json"))
		if err != nil {
			t.Fatal(err)
		}
		got, err := RawSchema(id)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(want, got) {
			t.Fatalf("schema %s differs from contracts copy", id)
		}
	}
}

func TestParseISODuration(t *testing.T) {
	cases := map[string]time.Duration{"PT6H": 6 * time.Hour, "PT72H": 72 * time.Hour, "P1D": 24 * time.Hour, "P1DT12H": 36 * time.Hour, "PT30M": 30 * time.Minute}
	for in, want := range cases {
		got, err := ParseISODuration(in)
		if err != nil || got != want {
			t.Errorf("%s: got %v err %v", in, got, err)
		}
	}
	for _, bad := range []string{"", "P", "6h", "PT", "P0D"} {
		if _, err := ParseISODuration(bad); err == nil {
			t.Errorf("%q should fail", bad)
		}
	}
}
