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

// GetID returns the address ID
func (a *Address) GetID() uuid.UUID {
	return a.ID
}

// GetUserID returns the user ID
func (a *Address) GetUserID() uuid.UUID {
	return a.UserID
}

// IsDefault returns whether this is the default address
func (a *Address) IsDefaultAddress() bool {
	return a.IsDefault
}

// GetRecipientName returns the recipient name
func (a *Address) GetRecipientName() string {
	return a.RecipientName
}

// GetPhoneNumber returns the phone number
func (a *Address) GetPhoneNumber() string {
	return a.PhoneNumber
}

// GetAddressLine returns the address line
func (a *Address) GetAddressLine() string {
	return a.AddressLine
}

// GetWardID returns the ward ID
func (a *Address) GetWardID() string {
	return a.WardID
}

// GetDistrictID returns the district ID
func (a *Address) GetDistrictID() string {
	return a.DistrictID
}

// GetCityID returns the city ID
func (a *Address) GetCityID() string {
	return a.CityID
}

// GetCreatedAt returns the created timestamp
func (a *Address) GetCreatedAt() time.Time {
	return a.CreatedAt
}

// GetUpdatedAt returns the updated timestamp
func (a *Address) GetUpdatedAt() time.Time {
	return a.UpdatedAt
}
