package response

import (
	"time"

	"github.com/google/uuid"
)

// AddressResponse represents a single address in responses
type AddressResponse struct {
	ID            uuid.UUID `json:"id"`
	IsDefault     bool      `json:"is_default"`
	RecipientName string    `json:"recipient_name"`
	PhoneNumber   string    `json:"phone_number"`
	AddressLine   string    `json:"address_line"`
	WardID        string    `json:"ward_id"`
	DistrictID    string    `json:"district_id"`
	CityID        string    `json:"city_id"`
	CreatedAt     time.Time `json:"created_at"`
	UpdatedAt     time.Time `json:"updated_at"`
}

// AddressListResponse represents the response for listing addresses
type AddressListResponse struct {
	Addresses       []*AddressResponse `json:"addresses"`
	Total           int               `json:"total"`
	Cached          bool              `json:"cached"`
	CacheTTLSeconds int               `json:"cache_ttl_seconds,omitempty"`
}

// AddressResponseFromEntity converts an address entity to a response
func AddressResponseFromEntity(a interface {
	GetID() uuid.UUID
	GetUserID() uuid.UUID
	IsDefaultAddress() bool
	GetRecipientName() string
	GetPhoneNumber() string
	GetAddressLine() string
	GetWardID() string
	GetDistrictID() string
	GetCityID() string
	GetCreatedAt() time.Time
	GetUpdatedAt() time.Time
}) *AddressResponse {
	return &AddressResponse{
		ID:            a.GetID(),
		IsDefault:     a.IsDefaultAddress(),
		RecipientName: a.GetRecipientName(),
		PhoneNumber:   a.GetPhoneNumber(),
		AddressLine:   a.GetAddressLine(),
		WardID:        a.GetWardID(),
		DistrictID:    a.GetDistrictID(),
		CityID:        a.GetCityID(),
		CreatedAt:     a.GetCreatedAt(),
		UpdatedAt:     a.GetUpdatedAt(),
	}
}
