package templates

import (
	"encoding/json"
	"flag"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	texttpl "text/template"
	"unicode/utf8"

	"github.com/google/uuid"
	dto "github.com/prometheus/client_model/go"

	"github.com/ecommerce/notification-service/internal/catalog"
	apperrors "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
	"github.com/ecommerce/notification-service/internal/telemetry"
)

// go test ./internal/templates/ -update regenerates every golden file
// (12-render.md §8); the diff in the PR shows exactly how each email changed.
var update = flag.Bool("update", false, "rewrite golden files")

func loadRegistry(t *testing.T) *Registry {
	t.Helper()
	cat, err := catalog.Load()
	if err != nil {
		t.Fatalf("catalog: %v", err)
	}
	r, err := Load(cat, nil)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	return r
}

// fixtures returns fixture names (without .json) for a template.
func fixtures(t *testing.T, id string) []string {
	t.Helper()
	matches, err := fs.Glob(content, path.Join(fixtureDir, id, "*.json"))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, m := range matches {
		base := path.Base(m)
		if strings.Contains(base, ".golden.") {
			continue
		}
		names = append(names, strings.TrimSuffix(base, ".json"))
	}
	if len(names) == 0 {
		t.Fatalf("%s: no fixtures", id)
	}
	return names
}

func readFixture(t *testing.T, id, name string) json.RawMessage {
	t.Helper()
	data, err := fs.ReadFile(content, path.Join(fixtureDir, id, name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func normalizeEOL(s string) string { return strings.ReplaceAll(s, "\r\n", "\n") }

// checkGolden compares got with testdata/<id>/<name>.golden.<ext>, or writes it with -update.
func checkGolden(t *testing.T, id, name, ext, got string) {
	t.Helper()
	p := filepath.Join(fixtureDir, id, name+".golden."+ext)
	if *update {
		if err := os.WriteFile(p, []byte(got), 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("missing golden %s (run with -update): %v", p, err)
	}
	if normalizeEOL(string(want)) != normalizeEOL(got) {
		t.Errorf("%s differs from rendered output (run with -update to accept):\n--- want\n%s\n--- got\n%s", p, string(want), got)
	}
}

func TestLoad(t *testing.T) {
	r := loadRegistry(t)
	if got := len(r.Templates()); got != 15 {
		t.Fatalf("expected 15 templates, got %d: %v", got, r.Templates())
	}
	if _, err := Load(nil, nil); err == nil {
		t.Fatal("nil catalog must error")
	}
}

func TestGolden(t *testing.T) {
	r := loadRegistry(t)
	for _, def := range r.catalog.Templates() {
		def := def
		t.Run(def.ID, func(t *testing.T) {
			for _, name := range fixtures(t, def.ID) {
				data := readFixture(t, def.ID, name)
				opts := fixtureOptions(def)

				email, err := r.RenderEmail(def.ID, "vi", data, opts)
				if err != nil {
					t.Fatalf("%s: RenderEmail: %v", name, err)
				}
				checkGolden(t, def.ID, name, "subject", email.Subject)
				checkGolden(t, def.ID, name, "html", email.HTML)
				checkGolden(t, def.ID, name, "txt", email.Text)

				if def.AllowsChannel(entity.ChannelInApp) {
					inapp, err := r.RenderInApp(def.ID, "vi", data, opts)
					if err != nil {
						t.Fatalf("%s: RenderInApp: %v", name, err)
					}
					b, _ := json.MarshalIndent(inapp, "", "  ")
					checkGolden(t, def.ID, name, "inapp.json", string(b)+"\n")
				} else if _, err := r.RenderInApp(def.ID, "vi", data, opts); err == nil {
					t.Fatalf("%s: RenderInApp must fail for an email-only template", def.ID)
				}
			}
		})
	}
}

func TestRenderedHTMLInvariants(t *testing.T) {
	r := loadRegistry(t)
	for _, def := range r.catalog.Templates() {
		for _, name := range fixtures(t, def.ID) {
			email, err := r.RenderEmail(def.ID, "vi", readFixture(t, def.ID, name), fixtureOptions(def))
			if err != nil {
				t.Fatalf("%s/%s: %v", def.ID, name, err)
			}
			html := strings.ToLower(email.HTML)
			if strings.Contains(html, "<style") {
				t.Errorf("%s/%s: HTML contains <style>; CSS must be hand-inlined", def.ID, name)
			}
			if len(email.HTML) > maxHTMLBytes {
				t.Errorf("%s/%s: HTML is %d bytes > %d", def.ID, name, len(email.HTML), maxHTMLBytes)
			}
			if utf8.RuneCountInString(email.Subject) > maxSubjectRunes || strings.ContainsAny(email.Subject, "\r\n") {
				t.Errorf("%s/%s: bad subject %q", def.ID, name, email.Subject)
			}
			if !strings.Contains(email.HTML, "<title>") || !strings.HasPrefix(email.HTML, "<!DOCTYPE html>") {
				t.Errorf("%s/%s: HTML is not wrapped in the layout", def.ID, name)
			}
			if !strings.HasSuffix(email.Text, "\n") || strings.Contains(email.Text, "\n\n\n") {
				t.Errorf("%s/%s: text not normalised", def.ID, name)
			}
			if email.Headers["X-Notification-Delivery-Id"] != fixtureOptions(def).DeliveryID.String() {
				t.Errorf("%s/%s: missing delivery id header: %v", def.ID, name, email.Headers)
			}
			_, hasUnsub := email.Headers["List-Unsubscribe"]
			if hasUnsub != (def.Lane == entity.LaneBulk) {
				t.Errorf("%s/%s: List-Unsubscribe presence %v, lane %s", def.ID, name, hasUnsub, def.Lane)
			}
		}
	}
}

var (
	scriptTag = regexp.MustCompile(`(?i)<script`)
	// An on* handler inside an actual element start tag (not escaped text).
	eventAttr = regexp.MustCompile(`(?i)<[a-z][^>]*\son[a-z]+\s*=`)
)

func TestInjectionIsNeutralised(t *testing.T) {
	r := loadRegistry(t)
	def, _ := r.catalog.Get("ORDER_CONFIRMED")
	data := readFixture(t, "ORDER_CONFIRMED", "injection")
	opts := fixtureOptions(def)

	email, err := r.RenderEmail("ORDER_CONFIRMED", "vi", data, opts)
	if err != nil {
		t.Fatal(err)
	}
	if scriptTag.MatchString(email.HTML) {
		t.Errorf("HTML contains <script>:\n%s", email.HTML)
	}
	// The payload may legitimately survive as escaped text; what must never
	// appear is a real tag or attribute built from it.
	if strings.Contains(email.HTML, "<img") || eventAttr.MatchString(email.HTML) || strings.Contains(email.HTML, "<b>1000235</b>") || strings.Contains(email.HTML, "<i>tiền mặt</i>") {
		t.Errorf("HTML contains unescaped template_data:\n%s", email.HTML)
	}
	if strings.Contains(email.HTML, `href="javascript:`) || !strings.Contains(email.HTML, "#ZgotmplZ") {
		t.Errorf("javascript: URL not neutralised:\n%s", email.HTML)
	}
	if !strings.Contains(email.HTML, "&lt;script&gt;") {
		t.Errorf("product name should be escaped as text:\n%s", email.HTML)
	}

	inapp, err := r.RenderInApp("ORDER_CONFIRMED", "vi", data, opts)
	if err != nil {
		t.Fatal(err)
	}
	if inapp.DeepLink != nil {
		t.Errorf("javascript: deep link must be dropped, got %q", *inapp.DeepLink)
	}
}

func TestVersion(t *testing.T) {
	r := loadRegistry(t)
	hexRe := regexp.MustCompile(`^[0-9a-f]{12}$`)
	seen := map[string]string{}
	for _, id := range r.Templates() {
		v := r.Version(id)
		if !hexRe.MatchString(v) {
			t.Errorf("%s: version %q is not 12 hex chars", id, v)
		}
		if other, dup := seen[v]; dup {
			t.Errorf("%s and %s share version %s", id, other, v)
		}
		seen[v] = id
	}
	if r.Version("NOPE") != "" {
		t.Error("unknown template must have empty version")
	}
	// Stable across loads and equal to a direct recomputation.
	again := loadRegistry(t)
	for _, id := range r.Templates() {
		if r.Version(id) != again.Version(id) {
			t.Errorf("%s: version changed between loads", id)
		}
		direct, err := computeVersion(id)
		if err != nil || direct != r.Version(id) {
			t.Errorf("%s: computeVersion %q %v vs %q", id, direct, err, r.Version(id))
		}
	}
}

func TestLocaleFallback(t *testing.T) {
	cat, err := catalog.Load()
	if err != nil {
		t.Fatal(err)
	}
	m := telemetry.New()
	r, err := Load(cat, m)
	if err != nil {
		t.Fatal(err)
	}
	def, _ := cat.Get("REVIEW_REPLIED")
	data := readFixture(t, "REVIEW_REPLIED", "basic")
	opts := fixtureOptions(def)

	vi, err := r.RenderEmail("REVIEW_REPLIED", "vi", data, opts)
	if err != nil {
		t.Fatal(err)
	}
	if got := counterValue(t, m); got != 0 {
		t.Fatalf("vi must not count as fallback, got %v", got)
	}
	if _, err := r.RenderEmail("REVIEW_REPLIED", "", data, opts); err != nil {
		t.Fatal(err)
	}
	if got := counterValue(t, m); got != 0 {
		t.Fatalf("empty locale is the default, not a fallback, got %v", got)
	}
	en, err := r.RenderEmail("REVIEW_REPLIED", "en-US", data, opts)
	if err != nil {
		t.Fatal(err)
	}
	if en.HTML != vi.HTML || en.Subject != vi.Subject {
		t.Fatal("en must fall back to the vi render")
	}
	if _, err := r.RenderInApp("REVIEW_REPLIED", "fr", data, opts); err != nil {
		t.Fatal(err)
	}
	if got := counterValue(t, m); got != 2 {
		t.Fatalf("expected 2 fallbacks, got %v", got)
	}
	for in, want := range map[string]string{"": "vi", "VI": "vi", "en-US": "en", "en_GB": "en", " fr ": "fr"} {
		if got := normalizeLocale(in); got != want {
			t.Errorf("normalizeLocale(%q) = %q want %q", in, got, want)
		}
	}
}

func TestRenderErrorsArePermanent(t *testing.T) {
	r := loadRegistry(t)
	opts := port.RenderOptions{DeliveryID: uuid.New(), Lane: entity.LaneTransactional}
	cases := []struct {
		name     string
		template string
		data     string
	}{
		{"unknown template", "NOPE", `{}`},
		{"unknown field", "REVIEW_REMOVED", `{"review_id":"a","product_id":"b","x":1}`},
		{"missing field", "REVIEW_REMOVED", `{"review_id":"a"}`},
		{"negative money", "ORDER_REFUNDED", `{"order_id":"1","order_code":"c","refund_id":"r","amount_minor":-1,"currency":"VND","refunded_at":"2026-09-20T10:15:02Z","order_url":"https://x"}`},
		{"card number in label", "ORDER_CONFIRMED", `{"order_id":"1","order_code":"c","placed_at":"2026-09-20T10:15:02Z","items":[{"name":"a","quantity":1,"unit_price_minor":1}],"subtotal_minor":1,"discount_minor":0,"shipping_fee_minor":0,"total_minor":1,"currency":"VND","payment_method_label":"4242424242424242","shipping_address":{"recipient_name":"A","ward":"","district":"","city":""},"order_url":"https://x"}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := r.RenderEmail(tc.template, "vi", json.RawMessage(tc.data), opts)
			if err == nil {
				t.Fatal("expected error")
			}
			code, ok := apperrors.PermanentCode(err)
			if !ok || code != entity.ReasonRenderFailed {
				t.Fatalf("expected permanent render_failed, got %v", err)
			}
			if apperrors.IsRetryable(err) {
				t.Fatal("render errors must never be retryable")
			}
		})
	}
	if _, err := r.RenderInApp("PASSWORD_RESET", "vi", json.RawMessage(`{"reset_url":"https://x"}`), opts); err == nil {
		t.Fatal("email-only template must not render in-app")
	} else if code, _ := apperrors.PermanentCode(err); code != entity.ReasonRenderFailed {
		t.Fatalf("got %v", err)
	}
}

func TestHeadersAndOptions(t *testing.T) {
	r := loadRegistry(t)
	id := uuid.MustParse("11111111-2222-4333-8444-555555555555")
	data := readFixture(t, "CART_ABANDONED", "basic")

	bulk, err := r.RenderEmail("CART_ABANDONED", "vi", data, port.RenderOptions{DeliveryID: id, Lane: entity.LaneBulk, UnsubscribeURL: "https://shop.example.com/profile/preferences"})
	if err != nil {
		t.Fatal(err)
	}
	if bulk.Headers["X-Notification-Delivery-Id"] != id.String() {
		t.Errorf("delivery header: %v", bulk.Headers)
	}
	if bulk.Headers["List-Unsubscribe"] != "<https://shop.example.com/profile/preferences>" {
		t.Errorf("List-Unsubscribe: %v", bulk.Headers)
	}
	if !strings.Contains(bulk.HTML, "Ngừng nhận email khuyến mãi") || !strings.Contains(bulk.Text, "https://shop.example.com/profile/preferences") {
		t.Error("unsubscribe footer missing on bulk")
	}

	noURL, err := r.RenderEmail("CART_ABANDONED", "vi", data, port.RenderOptions{DeliveryID: id, Lane: entity.LaneBulk})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := noURL.Headers["List-Unsubscribe"]; ok {
		t.Error("no URL → no List-Unsubscribe header")
	}
	if strings.Contains(noURL.HTML, "Ngừng nhận email khuyến mãi") {
		t.Error("no URL → no unsubscribe footer")
	}

	// A non-bulk lane never gets the header even when a URL is passed.
	tx, err := r.RenderEmail("REVIEW_REPLIED", "vi", readFixture(t, "REVIEW_REPLIED", "basic"), port.RenderOptions{DeliveryID: id, Lane: entity.LaneTransactional, UnsubscribeURL: "https://x"})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := tx.Headers["List-Unsubscribe"]; ok {
		t.Error("transactional must not carry List-Unsubscribe")
	}

	// Timezone from options changes the rendered date; empty falls back to Asia/Ho_Chi_Minh.
	locked := readFixture(t, "ACCOUNT_LOCKED", "basic") // locked_at 2026-08-27T13:10:00Z
	hcm, _ := r.RenderEmail("ACCOUNT_LOCKED", "vi", locked, port.RenderOptions{DeliveryID: id, Lane: entity.LaneCritical})
	tokyo, _ := r.RenderEmail("ACCOUNT_LOCKED", "vi", locked, port.RenderOptions{DeliveryID: id, Lane: entity.LaneCritical, Timezone: "Asia/Tokyo"})
	if !strings.Contains(hcm.Text, "27/08/2026 20:10") || !strings.Contains(tokyo.Text, "27/08/2026 22:10") {
		t.Errorf("timezone handling:\n%s\n%s", hcm.Text, tokyo.Text)
	}
}

func TestInAppSafetyNetTruncates(t *testing.T) {
	r := loadRegistry(t)
	// Bypass the fixture-proved limits by rendering a hand-built bundle whose
	// title/body overflow; RenderInApp must still clamp to 80/200 runes.
	ct := r.templates["REVIEW_REMOVED"]
	long := strings.Repeat("ă", 300)
	b := *ct.locales["vi"]
	b.inapp = &inappBundle{
		title: texttpl.Must(texttpl.New("t").Parse(long)),
		body:  texttpl.Must(texttpl.New("b").Parse(long)),
	}
	title, body, _, err := r.renderInAppRaw(ct, &b, readFixture(t, "REVIEW_REMOVED", "basic"), fixtureOptions(ct.def))
	if err != nil {
		t.Fatal(err)
	}
	if utf8.RuneCountInString(title) != 300 || utf8.RuneCountInString(body) != 300 {
		t.Fatalf("raw render should not truncate: %d %d", utf8.RuneCountInString(title), utf8.RuneCountInString(body))
	}
	if got := truncate(title, maxTitleRunes); utf8.RuneCountInString(got) != maxTitleRunes || !strings.HasSuffix(got, "…") {
		t.Errorf("title clamp: %q", got)
	}
	if got := truncate(body, maxBodyRunes); utf8.RuneCountInString(got) != maxBodyRunes || !strings.HasSuffix(got, "…") {
		t.Errorf("body clamp: %q", got)
	}
}

func TestSubjectSanitised(t *testing.T) {
	if got := sanitizeSubject("  Đơn hàng\r\nORD-1 \t đã   xác nhận \n"); got != "Đơn hàng ORD-1 đã xác nhận" {
		t.Fatalf("got %q", got)
	}
	long := sanitizeSubject(strings.Repeat("a", 500))
	if utf8.RuneCountInString(long) != maxSubjectRunes {
		t.Fatalf("subject not clamped: %d", utf8.RuneCountInString(long))
	}
}

func TestNormalizeText(t *testing.T) {
	got := normalizeText("\n\nA  \r\nB\n\n\n\nC\n\n")
	if got != "A\nB\n\nC\n" {
		t.Fatalf("got %q", got)
	}
}

func TestInAppDeepLinksMatchCatalog(t *testing.T) {
	r := loadRegistry(t)
	want := map[string]string{
		"ORDER_CONFIRMED": "order_url", "ORDER_PAYMENT_FAILED": "order_url", "ORDER_CANCELLED": "order_url",
		"ORDER_COMPLETED": "review_url", "ORDER_REFUNDED": "order_url", "REVIEW_REPLIED": "review_url",
		"CART_ABANDONED": "cart_url",
		"EMAIL_CHANGED":  "", "PASSWORD_CHANGED": "", "ACCOUNT_LOCKED": "", "ACCOUNT_UNLOCKED": "", "REVIEW_REMOVED": "",
	}
	for id, field := range want {
		b := r.templates[id].locales["vi"]
		if b.inapp == nil {
			t.Errorf("%s: no in-app bundle", id)
			continue
		}
		if b.inapp.deepLinkField != field {
			t.Errorf("%s: deep_link_field %q want %q", id, b.inapp.deepLinkField, field)
		}
		inapp, err := r.RenderInApp(id, "vi", readFixture(t, id, "basic"), fixtureOptions(r.templates[id].def))
		if err != nil {
			t.Fatal(err)
		}
		if (inapp.DeepLink != nil) != (field != "") {
			t.Errorf("%s: deep link presence mismatch: %v", id, inapp.DeepLink)
		}
	}
	for _, id := range []string{"REGISTRATION_VERIFICATION", "EMAIL_CHANGE_VERIFICATION", "PASSWORD_RESET"} {
		if r.templates[id].locales["vi"].inapp != nil {
			t.Errorf("%s: email-only template must have no in-app bundle", id)
		}
	}
}

// counterValue reads render_locale_fallback_total{template="REVIEW_REPLIED"}.
func counterValue(t *testing.T, m *telemetry.Metrics) float64 {
	t.Helper()
	var out dto.Metric
	if err := m.RenderLocaleFallback.WithLabelValues("REVIEW_REPLIED").Write(&out); err != nil {
		t.Fatal(err)
	}
	return out.GetCounter().GetValue()
}
