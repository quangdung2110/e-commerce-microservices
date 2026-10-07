// Package response holds the wire shapes of the outgoing payloads defined by
// contracts/http/user/v1/openapi.yaml.
package response

import (
	"time"

	"github.com/ecommerce/user-service/internal/domain/entity"
)

// dateLayout is the `format: date` representation of date_of_birth.
const dateLayout = "2006-01-02"

// Profile is the body of the profile endpoints. `display_name` is derived from
// the two name fields and is read-only.
type Profile struct {
	ID          string  `json:"id"`
	FirstName   *string `json:"first_name"`
	LastName    *string `json:"last_name"`
	DisplayName string  `json:"display_name"`
	DateOfBirth *string `json:"date_of_birth"`
	Gender      string  `json:"gender"`
	CreatedAt   string  `json:"created_at"`
	UpdatedAt   string  `json:"updated_at"`
}

// NewProfile renders one profile.
func NewProfile(profile *entity.Profile) Profile {
	body := Profile{
		ID:          profile.ID.String(),
		FirstName:   profile.FirstName,
		LastName:    profile.LastName,
		DisplayName: profile.DisplayName(),
		Gender:      profile.Gender.String(),
		CreatedAt:   formatTime(profile.CreatedAt),
		UpdatedAt:   formatTime(profile.UpdatedAt),
	}
	if profile.DateOfBirth != nil {
		day := profile.DateOfBirth.UTC().Format(dateLayout)
		body.DateOfBirth = &day
	}
	return body
}

// Preferences is the body of the preference endpoints.
type Preferences struct {
	Language         string `json:"language"`
	Currency         string `json:"currency"`
	EmailEnabled     bool   `json:"email_enabled"`
	InAppEnabled     bool   `json:"in_app_enabled"`
	MarketingEnabled bool   `json:"marketing_enabled"`
	UpdatedAt        string `json:"updated_at"`
}

// NewPreferences renders one preferences row.
func NewPreferences(preferences *entity.Preferences) Preferences {
	return Preferences{
		Language:         preferences.Language.String(),
		Currency:         preferences.Currency.String(),
		EmailEnabled:     preferences.EmailEnabled,
		InAppEnabled:     preferences.InAppEnabled,
		MarketingEnabled: preferences.MarketingEnabled,
		UpdatedAt:        formatTime(preferences.UpdatedAt),
	}
}

// Address is one entry of the address book. The owner id is not part of the
// body: every route already resolves it from the trusted identity.
type Address struct {
	ID             string `json:"id"`
	RecipientName  string `json:"recipient_name"`
	RecipientPhone string `json:"recipient_phone"`
	AddressLine    string `json:"address_line"`
	ProvinceCode   string `json:"province_code"`
	DistrictCode   string `json:"district_code"`
	WardCode       string `json:"ward_code"`
	IsDefault      bool   `json:"is_default"`
	CreatedAt      string `json:"created_at"`
	UpdatedAt      string `json:"updated_at"`
}

// NewAddress renders one address.
func NewAddress(address *entity.Address) Address {
	return Address{
		ID:             address.ID.String(),
		RecipientName:  address.RecipientName,
		RecipientPhone: address.RecipientPhone.String(),
		AddressLine:    address.AddressLine,
		ProvinceCode:   address.ProvinceCode,
		DistrictCode:   address.DistrictCode,
		WardCode:       address.WardCode,
		IsDefault:      address.IsDefault,
		CreatedAt:      formatTime(address.CreatedAt),
		UpdatedAt:      formatTime(address.UpdatedAt),
	}
}

// AddressList is the body of GET /addresses. The contract has no pagination
// here, which is why the service caps how many addresses one profile may hold.
type AddressList struct {
	Addresses []Address `json:"addresses"`
}

// NewAddressList renders the address book.
func NewAddressList(addresses []*entity.Address) AddressList {
	body := AddressList{Addresses: make([]Address, 0, len(addresses))}
	for _, address := range addresses {
		body.Addresses = append(body.Addresses, NewAddress(address))
	}
	return body
}

// CheckoutAddressSnapshot is what Checkout Service persists into its session and
// later into the order. It is deliberately a copy: the order must not change
// when the owner edits or deletes the address afterwards (USER-INV-004).
type CheckoutAddressSnapshot struct {
	AddressID      string `json:"address_id"`
	RecipientName  string `json:"recipient_name"`
	RecipientPhone string `json:"recipient_phone"`
	AddressLine    string `json:"address_line"`
	ProvinceCode   string `json:"province_code"`
	DistrictCode   string `json:"district_code"`
	WardCode       string `json:"ward_code"`
}

// NewCheckoutAddressSnapshot renders the internal snapshot.
func NewCheckoutAddressSnapshot(address *entity.Address) CheckoutAddressSnapshot {
	return CheckoutAddressSnapshot{
		AddressID:      address.ID.String(),
		RecipientName:  address.RecipientName,
		RecipientPhone: address.RecipientPhone.String(),
		AddressLine:    address.AddressLine,
		ProvinceCode:   address.ProvinceCode,
		DistrictCode:   address.DistrictCode,
		WardCode:       address.WardCode,
	}
}

// Health is the body of the probe endpoints.
type Health struct {
	Status string `json:"status"`
	// Degraded names the optional dependencies that are currently failing. The
	// service stays ready without them: a cache outage must not take the API
	// out of rotation (docs/user/operations.md §1).
	Degraded []string `json:"degraded,omitempty"`
}

// formatTime renders a response timestamp. Whole-second RFC 3339 is enough
// here: unlike the event payloads, no consumer orders anything by these values.
func formatTime(t time.Time) string {
	return t.UTC().Format(time.RFC3339)
}
