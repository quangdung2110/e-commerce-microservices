package entity

import (
	"errors"
	"time"

	"github.com/google/uuid"

	"user-service/internal/domain/vo"
)

var (
	ErrCannotDeleteLastDefault = errors.New("cannot delete the only default address")
	ErrAddressNotOwned         = errors.New("address does not belong to user")
)

// Address represents a shipping address entity
type Address struct {
	ID            uuid.UUID `json:"id" db:"id"`
	UserID        uuid.UUID `json:"user_id" db:"user_id"`
	IsDefault     bool      `json:"is_default" db:"is_default"`
	RecipientName string    `json:"recipient_name" db:"recipient_name"`
	PhoneNumber   string    `json:"phone_number" db:"phone_number"`
	AddressLine   string    `json:"address_line" db:"address_line"`
	WardID        string    `json:"ward_id" db:"ward_id"`
	DistrictID    string    `json:"district_id" db:"district_id"`
	CityID        string    `json:"city_id" db:"city_id"`
	CreatedAt     time.Time `json:"created_at" db:"created_at"`
	UpdatedAt     time.Time `json:"updated_at" db:"updated_at"`
}

// NewAddress creates a new Address entity
func NewAddress(userID uuid.UUID, recipientName, phone, addressLine, wardID, districtID, cityID string) (*Address, error) {
	phoneVO, err := vo.NewPhoneNumber(phone)
	if err != nil {
		return nil, err
	}

	if recipientName == "" {
		return nil, ErrRecipientNameRequired
	}

	if addressLine == "" {
		return nil, ErrAddressLineRequired
	}

	if wardID == "" || districtID == "" || cityID == "" {
		return nil, ErrLocationRequired
	}

	return &Address{
		ID:            uuid.New(),
		UserID:        userID,
		IsDefault:     false, // Will be set by service logic
		RecipientName: recipientName,
		PhoneNumber:   phoneVO.Value,
		AddressLine:   addressLine,
		WardID:        wardID,
		DistrictID:    districtID,
		CityID:        cityID,
	}, nil
}

// Update updates address fields with validation
func (a *Address) Update(recipientName, phone, addressLine, wardID, districtID, cityID string) error {
	if recipientName != "" {
		a.RecipientName = recipientName
	}

	if phone != "" {
		phoneVO, err := vo.NewPhoneNumber(phone)
		if err != nil {
			return err
		}
		a.PhoneNumber = phoneVO.Value
	}

	if addressLine != "" {
		a.AddressLine = addressLine
	}

	if wardID != "" {
		a.WardID = wardID
	}

	if districtID != "" {
		a.DistrictID = districtID
	}

	if cityID != "" {
		a.CityID = cityID
	}

	return nil
}

// SetAsDefault marks this address as default
func (a *Address) SetAsDefault() {
	a.IsDefault = true
}

// UnsetDefault marks this address as not default
func (a *Address) UnsetDefault() {
	a.IsDefault = false
}

// BelongsTo checks if the address belongs to the specified user
func (a *Address) BelongsTo(userID uuid.UUID) bool {
	return a.UserID == userID
}

// FormattedAddress returns a formatted full address string
func (a *Address) FormattedAddress() string {
	return a.AddressLine
}
