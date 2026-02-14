package common

import (
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
)

// UserDTO represents user data from User Service
type UserDTO struct {
	UserID            uuid.UUID                `json:"user_id"`
	ParentID          *uuid.UUID               `json:"parent_id"`
	AccountType       entity.AccountType       `json:"account_type"`
	Role              entity.UserRole          `json:"role"`
	Username          *string                  `json:"username"`
	Email             string                   `json:"email"`
	PhoneNumber       string                   `json:"phone_number"`
	FullName          string                   `json:"full_name"`
	PasswordHash      string                   `json:"-"`
	EmailVerified     bool                     `json:"email_verified"`
	PhoneVerified     bool                     `json:"phone_verified"`
	Active            bool                     `json:"active"`
	RestrictionStatus entity.RestrictionStatus `json:"restriction_status"`
	CreatedAt         time.Time                `json:"created_at"`
}

// CreateUserRequest represents request to create a new user
type CreateUserRequest struct {
	AccountType  entity.AccountType `json:"account_type"`
	Role         entity.UserRole    `json:"role"`
	ParentID     *uuid.UUID         `json:"parent_id,omitempty"`
	Username     *string            `json:"username,omitempty"`
	Email        string             `json:"email,omitempty"`
	PhoneNumber  string             `json:"phone_number,omitempty"`
	FullName     string             `json:"full_name"`
	PasswordHash string             `json:"password_hash"`
}

// RequestContext provides common request metadata
type RequestContext struct {
	RequestID  string              `json:"request_id"`
	DeviceInfo entity.DeviceInfo   `json:"device_info"`
	Timestamp  time.Time           `json:"timestamp"`
	Location   *entity.GeoLocation `json:"location,omitempty"`
}

// Events
type UserLoggedInEvent struct {
	UserID      uuid.UUID           `json:"user_id"`
	SessionID   uuid.UUID           `json:"session_id"`
	DeviceType  string              `json:"device_type"`
	IPAddress   string              `json:"ip_address"`
	GeoLocation *entity.GeoLocation `json:"geo_location,omitempty"`
	Timestamp   time.Time           `json:"timestamp"`
}

type UserLoggedOutEvent struct {
	UserID     uuid.UUID `json:"user_id"`
	SessionID  uuid.UUID `json:"session_id"`
	LogoutType string    `json:"logout_type"` // MANUAL, FORCED, EXPIRED
	Timestamp  time.Time `json:"timestamp"`
}

type UserRegisteredEvent struct {
	UserID         uuid.UUID `json:"user_id"`
	Email          string    `json:"email"`
	PhoneNumber    string    `json:"phone_number"`
	AccountType    string    `json:"account_type"`
	RegistrationIP string    `json:"registration_ip"`
	Timestamp      time.Time `json:"timestamp"`
}

type ManagedAccountCreatedEvent struct {
	UserID    uuid.UUID `json:"user_id"`
	CreatorID uuid.UUID `json:"creator_id"`
	Role      string    `json:"role"`
	Timestamp time.Time `json:"timestamp"`
}

type PasswordChangedEvent struct {
	UserID      *uuid.UUID `json:"user_id"`
	ChangedAt   time.Time  `json:"changed_at"`
	IPAddress   string     `json:"ip_address"`
	TriggeredBy string     `json:"triggered_by"` // SELF, ADMIN, RESET_FLOW
}

type PasswordResetRequestedEvent struct {
	UserID    *uuid.UUID `json:"user_id"`
	Email     string     `json:"email"`
	IPAddress string     `json:"ip_address"`
	Timestamp time.Time  `json:"timestamp"`
}

type SuspiciousActivityEvent struct {
	UserID       *uuid.UUID                                  `json:"user_id,omitempty"`
	ActivityType string                                      `json:"activity_type"` // IMPOSSIBLE_TRAVEL, CREDENTIAL_STUFFING, BRUTE_FORCE, TOKEN_THEFT
	DeviceID     string                                      `json:"device_id"`
	IPAddress    string                                      `json:"ip_address"`
	Timestamp    time.Time                                   `json:"timestamp"`
	Details      *DetailedInformationSuspiciousActivityEvent `json:"details,omitempty"`
}

type DetailedInformationSuspiciousActivityEvent struct {
	PreviousLocation *entity.GeoLocation `json:"previous_location,omitempty"`
	CurrentLocation  *entity.GeoLocation `json:"current_location,omitempty"`
	DistanceKm       float64             `json:"distance_km,omitempty"`
	TimeDifferenceH  float64             `json:"time_difference_hours,omitempty"`
	RequiredSpeedKmH float64             `json:"required_speed_kmh,omitempty"`
	LastActivityAt   time.Time           `json:"last_activity_at,omitempty"`
}
