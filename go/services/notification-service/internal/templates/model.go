package templates

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"regexp"
	"strings"
	"time"
)

// maxEmailItems caps the item table in ORDER_CONFIRMED / ORDER_COMPLETED
// emails. The schema allows 200 items of 255-char names, which alone would
// exceed the 100 KB Gmail clipping limit (12-render.md §5); the email shows
// the first items and points to order_url for the rest.
const maxEmailItems = 20

// Common carries the per-delivery values every template and the layout can
// reference next to the template_data fields (12-render.md §5).
type Common struct {
	DeliveryID     string
	Lane           string
	Timezone       string
	UnsubscribeURL string
	// Brand is the sender display name used in header/footer/subject.
	Brand string
	// Subject is filled after the subject line renders so the HTML layout can
	// reuse it for <title>.
	Subject string
}

func (c *Common) setCommon(v Common) { *c = v }

// model is one typed template_data struct. Every model embeds Common (so
// templates see .DeliveryID next to .OrderCode) and validates the values the
// JSON schema cannot express as "renderable" (zero dates, empty lists).
type model interface {
	setCommon(Common)
	validate() error
}

// ---- Auth (critical) ------------------------------------------------------

// RegistrationVerification: template_data from Auth content API (secret).
type RegistrationVerification struct {
	Common `json:"-"`
	OTP    string `json:"otp"`
}

func (m *RegistrationVerification) validate() error { return checkOTP(m.OTP) }

// EmailChangeVerification: template_data from Auth content API (secret).
type EmailChangeVerification struct {
	Common `json:"-"`
	OTP    string `json:"otp"`
}

func (m *EmailChangeVerification) validate() error { return checkOTP(m.OTP) }

// PasswordReset: template_data from Auth content API (secret).
type PasswordReset struct {
	Common   `json:"-"`
	ResetURL string `json:"reset_url"`
}

func (m *PasswordReset) validate() error { return required("reset_url", m.ResetURL) }

// EmailChanged: built by the consumer from user-email-changed.v1.
type EmailChanged struct {
	Common    `json:"-"`
	Event     string    `json:"event"`
	ChangedAt time.Time `json:"changed_at"`
}

func (m *EmailChanged) validate() error {
	return errors.Join(constEvent("EMAIL_CHANGED", m.Event), nonZero("changed_at", m.ChangedAt))
}

// PasswordChanged: built by the consumer from user-password-changed.v1.
type PasswordChanged struct {
	Common    `json:"-"`
	Event     string    `json:"event"`
	ChangedAt time.Time `json:"changed_at"`
}

func (m *PasswordChanged) validate() error {
	return errors.Join(constEvent("PASSWORD_CHANGED", m.Event), nonZero("changed_at", m.ChangedAt))
}

// AccountLocked: built by the consumer from user-account-locked.v1.
type AccountLocked struct {
	Common      `json:"-"`
	Event       string    `json:"event"`
	LockedAt    time.Time `json:"locked_at"`
	LockedUntil time.Time `json:"locked_until"`
}

func (m *AccountLocked) validate() error {
	return errors.Join(constEvent("ACCOUNT_LOCKED", m.Event), nonZero("locked_at", m.LockedAt), nonZero("locked_until", m.LockedUntil))
}

// AccountUnlocked: built by the consumer from user-unlocked.v1.
type AccountUnlocked struct {
	Common     `json:"-"`
	Event      string    `json:"event"`
	UnlockedAt time.Time `json:"unlocked_at"`
}

func (m *AccountUnlocked) validate() error {
	return errors.Join(constEvent("ACCOUNT_UNLOCKED", m.Event), nonZero("unlocked_at", m.UnlockedAt))
}

// ---- Order (transactional) -----------------------------------------------

// OrderItem is one line of ORDER_CONFIRMED.
type OrderItem struct {
	Name           string  `json:"name"`
	VariantName    *string `json:"variant_name"`
	Quantity       int     `json:"quantity"`
	UnitPriceMinor int64   `json:"unit_price_minor"`
}

// Variant returns the variant name or "" when absent/null.
func (i OrderItem) Variant() string {
	if i.VariantName == nil {
		return ""
	}
	return *i.VariantName
}

// LineTotalMinor is quantity × unit price.
func (i OrderItem) LineTotalMinor() int64 { return int64(i.Quantity) * i.UnitPriceMinor }

// ShippingAddress is the reduced address allowed in template_data
// (07-templates.md §2: recipient_name, ward, district, city only).
type ShippingAddress struct {
	RecipientName string `json:"recipient_name"`
	Ward          string `json:"ward"`
	District      string `json:"district"`
	City          string `json:"city"`
}

// Line joins the non-empty parts: "Phường 7, Quận 3, TP. Hồ Chí Minh".
func (a ShippingAddress) Line() string {
	parts := make([]string, 0, 3)
	for _, p := range []string{a.Ward, a.District, a.City} {
		if p = strings.TrimSpace(p); p != "" {
			parts = append(parts, p)
		}
	}
	return strings.Join(parts, ", ")
}

// OrderConfirmed: schemas/ORDER_CONFIRMED.schema.json.
type OrderConfirmed struct {
	Common             `json:"-"`
	OrderID            string          `json:"order_id"`
	OrderCode          string          `json:"order_code"`
	PlacedAt           time.Time       `json:"placed_at"`
	Items              []OrderItem     `json:"items"`
	SubtotalMinor      int64           `json:"subtotal_minor"`
	DiscountMinor      int64           `json:"discount_minor"`
	ShippingFeeMinor   int64           `json:"shipping_fee_minor"`
	TotalMinor         int64           `json:"total_minor"`
	Currency           string          `json:"currency"`
	PaymentMethodLabel string          `json:"payment_method_label"`
	ShippingAddress    ShippingAddress `json:"shipping_address"`
	OrderURL           string          `json:"order_url"`
}

func (m *OrderConfirmed) validate() error {
	errs := []error{
		required("order_id", m.OrderID), required("order_code", m.OrderCode),
		nonZero("placed_at", m.PlacedAt), required("currency", m.Currency),
		required("payment_method_label", m.PaymentMethodLabel), required("order_url", m.OrderURL),
		required("shipping_address.recipient_name", m.ShippingAddress.RecipientName),
		nonNegative("subtotal_minor", m.SubtotalMinor), nonNegative("discount_minor", m.DiscountMinor),
		nonNegative("shipping_fee_minor", m.ShippingFeeMinor), nonNegative("total_minor", m.TotalMinor),
	}
	if len(m.Items) == 0 {
		errs = append(errs, errors.New("items: must not be empty"))
	}
	for i, it := range m.Items {
		errs = append(errs, required(fmt.Sprintf("items[%d].name", i), it.Name), positive(fmt.Sprintf("items[%d].quantity", i), int64(it.Quantity)), nonNegative(fmt.Sprintf("items[%d].unit_price_minor", i), it.UnitPriceMinor))
	}
	return errors.Join(errs...)
}

// ShownItems are the items the email lists (maxEmailItems).
func (m *OrderConfirmed) ShownItems() []OrderItem {
	if len(m.Items) <= maxEmailItems {
		return m.Items
	}
	return m.Items[:maxEmailItems]
}

// HiddenItems is how many items the email omits.
func (m *OrderConfirmed) HiddenItems() int { return max(len(m.Items)-maxEmailItems, 0) }

// ItemCount is the total quantity across lines.
func (m *OrderConfirmed) ItemCount() int {
	n := 0
	for _, it := range m.Items {
		n += it.Quantity
	}
	return n
}

// OrderPaymentFailed: schemas/ORDER_PAYMENT_FAILED.schema.json.
type OrderPaymentFailed struct {
	Common        `json:"-"`
	OrderID       string     `json:"order_id"`
	OrderCode     string     `json:"order_code"`
	AmountMinor   int64      `json:"amount_minor"`
	Currency      string     `json:"currency"`
	Attempt       int        `json:"attempt"`
	CanRetry      bool       `json:"can_retry"`
	RetryDeadline *time.Time `json:"retry_deadline"`
	OrderURL      string     `json:"order_url"`
}

func (m *OrderPaymentFailed) validate() error {
	errs := []error{
		required("order_id", m.OrderID), required("order_code", m.OrderCode),
		nonNegative("amount_minor", m.AmountMinor), required("currency", m.Currency),
		positive("attempt", int64(m.Attempt)), required("order_url", m.OrderURL),
	}
	if m.RetryDeadline != nil {
		errs = append(errs, nonZero("retry_deadline", *m.RetryDeadline))
	} else if m.CanRetry {
		// Schema: can_retry=true requires retry_deadline.
		errs = append(errs, errors.New("retry_deadline: required when can_retry is true"))
	}
	return errors.Join(errs...)
}

// HasRetryDeadline reports whether a deadline can be shown.
func (m *OrderPaymentFailed) HasRetryDeadline() bool { return m.RetryDeadline != nil }

// Deadline is the retry deadline value (only call when HasRetryDeadline).
func (m *OrderPaymentFailed) Deadline() time.Time {
	if m.RetryDeadline == nil {
		return time.Time{}
	}
	return *m.RetryDeadline
}

// OrderCancelled: schemas/ORDER_CANCELLED.schema.json. Only pre-capture
// orders are cancelled (paid orders go through ORDER_REFUNDED), so
// refund_expected is always false.
type OrderCancelled struct {
	Common         `json:"-"`
	OrderID        string    `json:"order_id"`
	OrderCode      string    `json:"order_code"`
	CancelledAt    time.Time `json:"cancelled_at"`
	Reason         string    `json:"reason"`
	RefundExpected bool      `json:"refund_expected"`
	OrderURL       string    `json:"order_url"`
}

func (m *OrderCancelled) validate() error {
	errs := []error{
		required("order_id", m.OrderID), required("order_code", m.OrderCode),
		nonZero("cancelled_at", m.CancelledAt), required("order_url", m.OrderURL),
	}
	if _, ok := cancelReasons[m.Reason]; !ok {
		errs = append(errs, fmt.Errorf("reason: unknown value %q", m.Reason))
	}
	if m.RefundExpected {
		errs = append(errs, errors.New("refund_expected: must be false"))
	}
	return errors.Join(errs...)
}

// CompletedItem is one line of ORDER_COMPLETED (no price).
type CompletedItem struct {
	Name        string  `json:"name"`
	VariantName *string `json:"variant_name"`
	Quantity    int     `json:"quantity"`
}

// Variant returns the variant name or "" when absent/null.
func (i CompletedItem) Variant() string {
	if i.VariantName == nil {
		return ""
	}
	return *i.VariantName
}

// OrderCompleted: schemas/ORDER_COMPLETED.schema.json.
type OrderCompleted struct {
	Common      `json:"-"`
	OrderID     string          `json:"order_id"`
	OrderCode   string          `json:"order_code"`
	CompletedAt time.Time       `json:"completed_at"`
	Items       []CompletedItem `json:"items"`
	ReviewURL   string          `json:"review_url"`
}

func (m *OrderCompleted) validate() error {
	errs := []error{
		required("order_id", m.OrderID), required("order_code", m.OrderCode),
		nonZero("completed_at", m.CompletedAt), required("review_url", m.ReviewURL),
	}
	if len(m.Items) == 0 {
		errs = append(errs, errors.New("items: must not be empty"))
	}
	for i, it := range m.Items {
		errs = append(errs, required(fmt.Sprintf("items[%d].name", i), it.Name), positive(fmt.Sprintf("items[%d].quantity", i), int64(it.Quantity)))
	}
	return errors.Join(errs...)
}

// ShownItems are the items the email lists (maxEmailItems).
func (m *OrderCompleted) ShownItems() []CompletedItem {
	if len(m.Items) <= maxEmailItems {
		return m.Items
	}
	return m.Items[:maxEmailItems]
}

// HiddenItems is how many items the email omits.
func (m *OrderCompleted) HiddenItems() int { return max(len(m.Items)-maxEmailItems, 0) }

// FirstItemName is used by the in-app body (truncated by the template).
func (m *OrderCompleted) FirstItemName() string {
	if len(m.Items) == 0 {
		return ""
	}
	return m.Items[0].Name
}

// OrderRefunded: schemas/ORDER_REFUNDED.schema.json.
type OrderRefunded struct {
	Common      `json:"-"`
	OrderID     string    `json:"order_id"`
	OrderCode   string    `json:"order_code"`
	RefundID    string    `json:"refund_id"`
	AmountMinor int64     `json:"amount_minor"`
	Currency    string    `json:"currency"`
	RefundedAt  time.Time `json:"refunded_at"`
	OrderURL    string    `json:"order_url"`
}

func (m *OrderRefunded) validate() error {
	return errors.Join(
		required("order_id", m.OrderID), required("order_code", m.OrderCode),
		required("refund_id", m.RefundID), nonNegative("amount_minor", m.AmountMinor),
		required("currency", m.Currency), nonZero("refunded_at", m.RefundedAt),
		required("order_url", m.OrderURL),
	)
}

// ---- Review (transactional) ----------------------------------------------

// ReviewReplied: schemas/REVIEW_REPLIED.schema.json.
type ReviewReplied struct {
	Common    `json:"-"`
	ReviewID  string `json:"review_id"`
	ProductID string `json:"product_id"`
	ReviewURL string `json:"review_url"`
}

func (m *ReviewReplied) validate() error {
	return errors.Join(required("review_id", m.ReviewID), required("product_id", m.ProductID), required("review_url", m.ReviewURL))
}

// ReviewRemoved: schemas/REVIEW_REMOVED.schema.json.
type ReviewRemoved struct {
	Common    `json:"-"`
	ReviewID  string `json:"review_id"`
	ProductID string `json:"product_id"`
}

func (m *ReviewRemoved) validate() error {
	return errors.Join(required("review_id", m.ReviewID), required("product_id", m.ProductID))
}

// ---- Cart (bulk) ----------------------------------------------------------

// CartAbandoned: schemas/CART_ABANDONED.schema.json.
type CartAbandoned struct {
	Common           `json:"-"`
	CartID           string `json:"cart_id"`
	AbandonmentCycle int    `json:"abandonment_cycle"`
	CartURL          string `json:"cart_url"`
}

func (m *CartAbandoned) validate() error {
	return errors.Join(required("cart_id", m.CartID), required("cart_url", m.CartURL), nonNegative("abandonment_cycle", int64(m.AbandonmentCycle)))
}

// ---- construction ---------------------------------------------------------

// newModel returns the empty model for a template id.
func newModel(templateID string) (model, bool) {
	switch templateID {
	case "REGISTRATION_VERIFICATION":
		return &RegistrationVerification{}, true
	case "EMAIL_CHANGE_VERIFICATION":
		return &EmailChangeVerification{}, true
	case "PASSWORD_RESET":
		return &PasswordReset{}, true
	case "EMAIL_CHANGED":
		return &EmailChanged{}, true
	case "PASSWORD_CHANGED":
		return &PasswordChanged{}, true
	case "ACCOUNT_LOCKED":
		return &AccountLocked{}, true
	case "ACCOUNT_UNLOCKED":
		return &AccountUnlocked{}, true
	case "ORDER_CONFIRMED":
		return &OrderConfirmed{}, true
	case "ORDER_PAYMENT_FAILED":
		return &OrderPaymentFailed{}, true
	case "ORDER_CANCELLED":
		return &OrderCancelled{}, true
	case "ORDER_COMPLETED":
		return &OrderCompleted{}, true
	case "ORDER_REFUNDED":
		return &OrderRefunded{}, true
	case "REVIEW_REPLIED":
		return &ReviewReplied{}, true
	case "REVIEW_REMOVED":
		return &ReviewRemoved{}, true
	case "CART_ABANDONED":
		return &CartAbandoned{}, true
	}
	return nil, false
}

// buildModel decodes template_data strictly into the template's typed model
// (12-render.md §5: typed model, never a raw map). Unknown fields, type
// mismatches, trailing data and unrenderable values are errors.
func buildModel(templateID string, data json.RawMessage) (model, error) {
	m, ok := newModel(templateID)
	if !ok {
		return nil, fmt.Errorf("unknown template %q", templateID)
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return nil, fmt.Errorf("template %s: empty template_data", templateID)
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(m); err != nil {
		return nil, fmt.Errorf("template %s: decode template_data: %w", templateID, err)
	}
	if dec.More() {
		return nil, fmt.Errorf("template %s: trailing data after template_data object", templateID)
	}
	if err := m.validate(); err != nil {
		return nil, fmt.Errorf("template %s: invalid template_data: %w", templateID, err)
	}
	return m, nil
}

// hasJSONField reports whether the model exposes a top-level template_data
// field with the given JSON name (used to validate inapp deep_link_field).
func hasJSONField(m model, name string) bool {
	t := reflect.TypeOf(m)
	for t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	for i := 0; i < t.NumField(); i++ {
		tag := t.Field(i).Tag.Get("json")
		if tag == "" || tag == "-" {
			continue
		}
		if strings.Split(tag, ",")[0] == name {
			return true
		}
	}
	return false
}

// deepLink extracts the string field named by inapp.yaml deep_link_field
// from the raw template_data (12-render.md §5: taken from data, never
// assembled). Only http(s) links are kept: a producer sending
// "javascript:..." would otherwise reach the frontend inbox verbatim, so it
// is neutralised to nil the same way html/template neutralises hrefs.
func deepLink(data json.RawMessage, field string) (*string, error) {
	if field == "" {
		return nil, nil
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("deep_link: template_data is not an object: %w", err)
	}
	v, ok := raw[field]
	if !ok {
		return nil, nil
	}
	var s string
	if err := json.Unmarshal(v, &s); err != nil {
		return nil, fmt.Errorf("deep_link: field %s is not a string: %w", field, err)
	}
	s = strings.TrimSpace(s)
	if !safeLink(s) {
		return nil, nil
	}
	return &s, nil
}

// safeLink reports whether s is an absolute http(s) URL.
func safeLink(s string) bool {
	lower := strings.ToLower(s)
	return strings.HasPrefix(lower, "https://") || strings.HasPrefix(lower, "http://")
}

// ---- validation helpers ---------------------------------------------------

var otpPattern = regexp.MustCompile(`^[0-9]{6}$`)

func checkOTP(otp string) error {
	if !otpPattern.MatchString(otp) {
		return errors.New("otp: must be 6 digits")
	}
	return nil
}

func required(field, v string) error {
	if strings.TrimSpace(v) == "" {
		return fmt.Errorf("%s: required", field)
	}
	return nil
}

func nonZero(field string, t time.Time) error {
	if t.IsZero() {
		return fmt.Errorf("%s: required", field)
	}
	return nil
}

func nonNegative(field string, v int64) error {
	if v < 0 {
		return fmt.Errorf("%s: must be >= 0", field)
	}
	return nil
}

func positive(field string, v int64) error {
	if v < 1 {
		return fmt.Errorf("%s: must be >= 1", field)
	}
	return nil
}

func constEvent(want, got string) error {
	if got != want {
		return fmt.Errorf("event: want %q, got %q", want, got)
	}
	return nil
}
