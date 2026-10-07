// Package vo holds the value objects of the User domain. Every enum mirrors a
// CHECK constraint of the "user" schema, so a value the database would refuse
// can never leave the domain.
package vo

import "fmt"

// Gender mirrors the check on "user".user_profiles.gender.
type Gender string

const (
	GenderMale    Gender = "MALE"
	GenderFemale  Gender = "FEMALE"
	GenderOther   Gender = "OTHER"
	GenderUnknown Gender = "UNKNOWN"
)

func (g Gender) String() string { return string(g) }

func (g Gender) Valid() bool {
	switch g {
	case GenderMale, GenderFemale, GenderOther, GenderUnknown:
		return true
	}
	return false
}

// ParseGender converts a request or database value into the enum.
func ParseGender(value string) (Gender, error) {
	gender := Gender(value)
	if !gender.Valid() {
		return "", fmt.Errorf("vo: unknown gender %q", value)
	}
	return gender, nil
}

// Language is the only supported UI language (docs/user/data.md §3).
type Language string

// LanguageVietnamese is the single accepted value of user_preferences.language.
const LanguageVietnamese Language = "vi"

func (l Language) String() string { return string(l) }

func (l Language) Valid() bool { return l == LanguageVietnamese }

// Currency is the only supported settlement currency.
type Currency string

// CurrencyVND is the single accepted value of user_preferences.currency.
const CurrencyVND Currency = "VND"

func (c Currency) String() string { return string(c) }

func (c Currency) Valid() bool { return c == CurrencyVND }

// DeletionReason mirrors the check on "user".user_deletions.reason and the
// enum of the Auth USER_DELETED payload.
type DeletionReason string

const (
	DeletionGDPRRequest   DeletionReason = "GDPR_REQUEST"
	DeletionAdminDeletion DeletionReason = "ADMIN_DELETION"
)

func (d DeletionReason) String() string { return string(d) }

func (d DeletionReason) Valid() bool {
	return d == DeletionGDPRRequest || d == DeletionAdminDeletion
}

// ParseDeletionReason converts an event or database value into the enum.
func ParseDeletionReason(value string) (DeletionReason, error) {
	reason := DeletionReason(value)
	if !reason.Valid() {
		return "", fmt.Errorf("vo: unknown deletion reason %q", value)
	}
	return reason, nil
}
