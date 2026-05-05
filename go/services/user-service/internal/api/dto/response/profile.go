package response

import (
	"time"

	"github.com/google/uuid"
)

// ProfileResponse represents the response body for profile endpoints
type ProfileResponse struct {
	ID          uuid.UUID  `json:"id"`
	FirstName  string     `json:"first_name"`
	LastName   string     `json:"last_name"`
	PhoneNumber *string   `json:"phone_number,omitempty"`
	DateOfBirth *string   `json:"date_of_birth,omitempty"`
	Gender     string     `json:"gender"`
	CreatedAt  time.Time  `json:"created_at"`
	UpdatedAt  time.Time  `json:"updated_at"`
}

// ToProfileResponse converts a profile entity to a response
func ToProfileResponse(p interface {
	GetID() uuid.UUID
	GetFirstName() string
	GetLastName() string
	GetPhoneNumber() *string
	GetDateOfBirth() *string
	GetGender() string
	GetCreatedAt() time.Time
	GetUpdatedAt() time.Time
}) *ProfileResponse {
	return &ProfileResponse{
		ID:          p.GetID(),
		FirstName:   p.GetFirstName(),
		LastName:    p.GetLastName(),
		PhoneNumber: p.GetPhoneNumber(),
		DateOfBirth: p.GetDateOfBirth(),
		Gender:      p.GetGender(),
		CreatedAt:   p.GetCreatedAt(),
		UpdatedAt:   p.GetUpdatedAt(),
	}
}
