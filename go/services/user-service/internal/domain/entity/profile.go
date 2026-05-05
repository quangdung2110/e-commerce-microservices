package entity

import (
	"time"

	"github.com/google/uuid"

	"user-service/internal/domain/vo"
)

// Profile represents the user profile entity
// This is the core domain entity that stores customer demographic information
type Profile struct {
	ID          uuid.UUID  `json:"id" db:"id"`
	FirstName   string     `json:"first_name" db:"first_name"`
	LastName    string     `json:"last_name" db:"last_name"`
	PhoneNumber *string    `json:"phone_number,omitempty" db:"phone_number"`
	DateOfBirth *time.Time `json:"date_of_birth,omitempty" db:"date_of_birth"`
	Gender      vo.Gender  `json:"gender" db:"gender"`
	CreatedAt   time.Time  `json:"created_at" db:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at" db:"updated_at"`
}

// NewProfile creates a new Profile entity with default values
func NewProfile(id uuid.UUID) *Profile {
	return &Profile{
		ID:     id,
		Gender: vo.GenderUnknown,
	}
}

// FullName returns the full name of the user
func (p *Profile) FullName() string {
	if p.FirstName == "" && p.LastName == "" {
		return ""
	}
	return p.FirstName + " " + p.LastName
}

// SetName sets the first and last name with validation
func (p *Profile) SetName(firstName, lastName string) error {
	nameVO, err := vo.NewName(firstName, lastName)
	if err != nil {
		return err
	}
	p.FirstName = nameVO.FirstName
	p.LastName = nameVO.LastName
	return nil
}

// SetPhoneNumber sets the phone number with validation
func (p *Profile) SetPhoneNumber(phone string) error {
	if phone == "" {
		p.PhoneNumber = nil
		return nil
	}
	phoneVO, err := vo.NewPhoneNumber(phone)
	if err != nil {
		return err
	}
	p.PhoneNumber = &phoneVO.Value
	return nil
}

// SetDateOfBirth sets the date of birth
func (p *Profile) SetDateOfBirth(dob *time.Time) error {
	if dob == nil {
		p.DateOfBirth = nil
		return nil
	}
	if dob.After(time.Now()) {
		return ErrDateOfBirthInFuture
	}
	p.DateOfBirth = dob
	return nil
}

// SetGender sets the gender
func (p *Profile) SetGender(gender vo.Gender) error {
	if !gender.IsValid() {
		return ErrInvalidGender
	}
	p.Gender = gender
	return nil
}
