package vo

import (
	"regexp"
	"strings"
)

// e164Pattern is the recipient_phone pattern of the OpenAPI contract and of the
// CHECK constraint on "user".user_addresses.recipient_phone.
var e164Pattern = regexp.MustCompile(`^\+[1-9][0-9]{7,14}$`)

// Phone is a recipient phone number in E.164 form. A profile never holds one:
// it belongs to the address that will be delivered to.
type Phone struct {
	value string
}

// NewPhone normalizes and validates a phone number. Spaces, dots and dashes a
// human would type are removed; a leading 00 becomes +.
func NewPhone(raw string) (Phone, bool) {
	normalized := normalizePhone(raw)
	if !e164Pattern.MatchString(normalized) {
		return Phone{}, false
	}
	return Phone{value: normalized}, true
}

// MustPhone is the database read path: the CHECK constraint already guarantees
// the format, so a stored value is taken as-is.
func MustPhone(value string) Phone { return Phone{value: value} }

func (p Phone) String() string { return p.value }

func (p Phone) IsZero() bool { return p.value == "" }

func normalizePhone(raw string) string {
	var sb strings.Builder
	sb.Grow(len(raw))
	for _, r := range strings.TrimSpace(raw) {
		switch r {
		case ' ', '-', '.', '(', ')', '\t':
			continue
		default:
			sb.WriteRune(r)
		}
	}
	normalized := sb.String()
	if strings.HasPrefix(normalized, "00") {
		normalized = "+" + normalized[2:]
	}
	return normalized
}
