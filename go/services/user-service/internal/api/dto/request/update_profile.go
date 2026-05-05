package request

// UpdateProfileRequest represents the request body for updating a profile
type UpdateProfileRequest struct {
	FirstName   string  `json:"first_name" binding:"max=100"`
	LastName    string  `json:"last_name" binding:"max=100"`
	PhoneNumber string  `json:"phone_number" binding:"omitempty,e164_phone"`
	DateOfBirth string  `json:"date_of_birth" binding:"omitempty,datetime=2006-01-02"`
	Gender      string  `json:"gender" binding:"omitempty,oneof=MALE FEMALE OTHER UNKNOWN"`
}
