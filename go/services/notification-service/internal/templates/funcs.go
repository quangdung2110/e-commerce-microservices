package templates

import (
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata" // 12-render.md §6: date() converts to the user's zone; the production image may lack /usr/share/zoneinfo.
	"unicode/utf8"
)

// defaultTimezone is the fallback zone when the delivery carries none or an
// unknown one (07-templates.md §4: User has not published timezone → Asia/Ho_Chi_Minh).
const defaultTimezone = "Asia/Ho_Chi_Minh"

// dateLayout is the Vietnamese day-first layout, e.g. 20/09/2026 17:15.
const dateLayout = "02/01/2006 15:04"

// funcMap is the function set every template (subject, html, text, in-app)
// is parsed with. None of them do I/O, read env or call out (12-render.md §6).
func funcMap() map[string]any {
	return map[string]any{
		"money":       money,
		"date":        date,
		"plural":      plural,
		"truncate":    truncate,
		"mask":        mask,
		"reasonLabel": reasonLabel,
		"dict":        dict,
	}
}

// money formats a minor-unit amount. VND has no fraction and uses "." as the
// thousands separator (vi locale): 428000 → "428.000 ₫". Any other currency
// is rendered generically with two decimals and the ISO code: 12345 USD →
// "123,45 USD". Negative amounts are a producer bug (schemas say minimum 0)
// and fail the render (12-render.md §9: FAILED render_failed, not retried).
func money(minor int64, currency string) (string, error) {
	if minor < 0 {
		return "", fmt.Errorf("money: negative amount %d %s", minor, currency)
	}
	cur := strings.ToUpper(strings.TrimSpace(currency))
	switch cur {
	case "VND":
		return groupThousands(strconv.FormatInt(minor, 10)) + " ₫", nil
	case "":
		return "", errors.New("money: empty currency")
	default:
		whole := groupThousands(strconv.FormatInt(minor/100, 10))
		return fmt.Sprintf("%s,%02d %s", whole, minor%100, cur), nil
	}
}

// groupThousands inserts "." every three digits from the right: "1234567" → "1.234.567".
func groupThousands(digits string) string {
	n := len(digits)
	if n <= 3 {
		return digits
	}
	var b strings.Builder
	b.Grow(n + n/3)
	first := n % 3
	if first > 0 {
		b.WriteString(digits[:first])
	}
	for i := first; i < n; i += 3 {
		if b.Len() > 0 {
			b.WriteByte('.')
		}
		b.WriteString(digits[i : i+3])
	}
	return b.String()
}

// date renders t in the IANA zone tz as dd/MM/yyyy HH:mm. An empty or unknown
// zone falls back to Asia/Ho_Chi_Minh; a zero time is a producer bug and fails
// the render (12-render.md §9).
func date(t time.Time, tz string) (string, error) {
	if t.IsZero() {
		return "", errors.New("date: zero time")
	}
	return t.In(location(tz)).Format(dateLayout), nil
}

// location resolves tz, falling back to the default zone and finally to a
// fixed +07:00 so rendering stays deterministic even without tzdata.
func location(tz string) *time.Location {
	if tz = strings.TrimSpace(tz); tz != "" {
		if loc, err := time.LoadLocation(tz); err == nil {
			return loc
		}
	}
	if loc, err := time.LoadLocation(defaultTimezone); err == nil {
		return loc
	}
	return time.FixedZone(defaultTimezone, 7*3600)
}

// plural returns "<n> <word>". Vietnamese nouns do not inflect so the
// optional many-form is only used by other locales: plural 2 "item" "items".
func plural(n any, one string, many ...string) (string, error) {
	count, err := toInt64(n)
	if err != nil {
		return "", fmt.Errorf("plural: %w", err)
	}
	word := one
	if count != 1 && len(many) > 0 && many[0] != "" {
		word = many[0]
	}
	return strconv.FormatInt(count, 10) + " " + word, nil
}

func toInt64(v any) (int64, error) {
	switch x := v.(type) {
	case int:
		return int64(x), nil
	case int8:
		return int64(x), nil
	case int16:
		return int64(x), nil
	case int32:
		return int64(x), nil
	case int64:
		return x, nil
	case uint:
		return int64(x), nil
	case uint8:
		return int64(x), nil
	case uint16:
		return int64(x), nil
	case uint32:
		return int64(x), nil
	case uint64:
		return int64(x), nil
	default:
		return 0, fmt.Errorf("not an integer: %T", v)
	}
}

// truncate cuts s to at most n runes; when it cuts, the last kept rune is
// replaced by "…" so the result never exceeds n runes. Used by in-app copy to
// keep title ≤ 80 and body ≤ 200 with 255-char product names.
func truncate(s string, n int) string {
	if n <= 0 {
		return ""
	}
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	if n == 1 {
		return "…"
	}
	runes := []rune(s)
	kept := strings.TrimRight(string(runes[:n-1]), " \t\r\n")
	return kept + "…"
}

// cardLike matches 13–19 consecutive digits (PAN length range) once
// separators are stripped.
var cardLike = regexp.MustCompile(`[0-9]{13,19}`)

// cardSeparators are characters people put between PAN groups.
var cardSeparators = strings.NewReplacer(" ", "", "-", "", ".", "", " ", "")

// mask is the payment_method_label guard (12-render.md §6): Order already
// masks the label ("Thẻ Visa •••• 4242"); if a full card number ever slips
// through, the render fails instead of emailing it.
func mask(s string) (string, error) {
	if cardLike.MatchString(cardSeparators.Replace(s)) {
		return "", errors.New("mask: value looks like a card number")
	}
	return s, nil
}

// cancelReasons maps the ORDER_CANCELLED reason enum to user-facing
// Vietnamese wording (07-templates.md §5.2).
var cancelReasons = map[string]string{
	"buyer":           "Bạn đã yêu cầu huỷ đơn hàng",
	"payment_timeout": "Đơn hàng chưa được thanh toán trong thời hạn cho phép",
	"admin":           "Cửa hàng đã huỷ đơn hàng",
	"out_of_stock":    "Sản phẩm trong đơn đã hết hàng",
	"checkout_failed": "Đơn hàng không thể hoàn tất do sự cố trong quá trình đặt hàng",
}

// reasonLabel renders an ORDER_CANCELLED reason. An unknown value is a
// contract violation (schema enum) and fails the render.
func reasonLabel(reason string) (string, error) {
	if label, ok := cancelReasons[reason]; ok {
		return label, nil
	}
	return "", fmt.Errorf("reasonLabel: unknown reason %q", reason)
}

// dict builds a map from key/value pairs so a layout block can be called
// with named arguments: {{template "button" (dict "URL" .OrderURL "Label" "Xem đơn")}}.
func dict(kv ...any) (map[string]any, error) {
	if len(kv)%2 != 0 {
		return nil, errors.New("dict: odd number of arguments")
	}
	m := make(map[string]any, len(kv)/2)
	for i := 0; i < len(kv); i += 2 {
		k, ok := kv[i].(string)
		if !ok {
			return nil, fmt.Errorf("dict: key %d is %T, want string", i/2, kv[i])
		}
		m[k] = kv[i+1]
	}
	return m, nil
}
