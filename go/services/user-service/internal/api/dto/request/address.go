package request

// CreateAddressRequest represents the request body for creating an address
type CreateAddressRequest struct {
	RecipientName string `json:"recipient_name" binding:"required,max=200"`
	PhoneNumber   string `json:"phone_number" binding:"required,e164_phone"`
	AddressLine   string `json:"address_line" binding:"required,max=500"`
	WardID        string `json:"ward_id" binding:"required,max=20"`
	DistrictID    string `json:"district_id" binding:"required,max=20"`
	CityID        string `json:"city_id" binding:"required,max=20"`
}

// UpdateAddressRequest represents the request body for updating an address
type UpdateAddressRequest struct {
	RecipientName string `json:"recipient_name" binding:"omitempty,max=200"`
	PhoneNumber   string `json:"phone_number" binding:"omitempty,e164_phone"`
	AddressLine   string `json:"address_line" binding:"omitempty,max=500"`
	WardID        string `json:"ward_id" binding:"omitempty,max=20"`
	DistrictID    string `json:"district_id" binding:"omitempty,max=20"`
	CityID        string `json:"city_id" binding:"omitempty,max=20"`
}
