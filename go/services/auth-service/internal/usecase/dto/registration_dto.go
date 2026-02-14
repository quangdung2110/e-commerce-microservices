package dto

import (
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// ========================= PERSONAL REGISTRATION FLOW DTOs =========================

// Step 1: Start Registration with Email
type StartRegistrationRequest struct {
	Email string `json:"email" validate:"required,email"`
}

type StartRegistrationResponse struct {
	SessionID        string `json:"session_id"`
	Email            string `json:"email"`
	MaskedEmail      string `json:"masked_email"`
	OTPRequestID     string `json:"otp_request_id"`
	OTPExpiresIn     int    `json:"otp_expires_in"` // seconds
	NextStep         int    `json:"next_step"`
	NextStepEndpoint string `json:"next_step_endpoint"`
}

// Step 2: Verify Email OTP
type VerifyEmailOTPRequest struct {
	SessionID string `json:"session_id" validate:"required,uuid"`
	OTPCode   string `json:"otp_code" validate:"required,len=6,numeric"`
}

type VerifyEmailOTPResponse struct {
	SessionID        string `json:"session_id"`
	EmailVerified    bool   `json:"email_verified"`
	NextStep         int    `json:"next_step"`
	NextStepEndpoint string `json:"next_step_endpoint"`
}

// Step 3: Add Phone Number
type AddPhoneNumberRequest struct {
	SessionID   string `json:"session_id" validate:"required,uuid"`
	PhoneNumber string `json:"phone_number" validate:"required,e164"`
}

type AddPhoneNumberResponse struct {
	SessionID        string `json:"session_id"`
	PhoneNumber      string `json:"phone_number"`
	MaskedPhone      string `json:"masked_phone"`
	OTPRequestID     string `json:"otp_request_id"`
	OTPExpiresIn     int    `json:"otp_expires_in"`
	NextStep         int    `json:"next_step"`
	NextStepEndpoint string `json:"next_step_endpoint"`
}

// Step 4: Verify Phone OTP
type VerifyPhoneOTPRequest struct {
	SessionID string `json:"session_id" validate:"required,uuid"`
	OTPCode   string `json:"otp_code" validate:"required,len=6,numeric"`
}

type VerifyPhoneOTPResponse struct {
	SessionID        string `json:"session_id"`
	PhoneVerified    bool   `json:"phone_verified"`
	NextStep         int    `json:"next_step"`
	NextStepEndpoint string `json:"next_step_endpoint"`
}

// Step 5: Complete Registration
type CompleteRegistrationRequest struct {
	SessionID    string `json:"session_id" validate:"required,uuid"`
	Password     string `json:"password" validate:"required,min=8"`
	FullName     string `json:"full_name" validate:"required,min=2,max=100"`
	AgreeToTerms bool   `json:"agree_to_terms" validate:"required,eq=true"`
}

type CompleteRegistrationResponse struct {
	Success      bool            `json:"success"`
	AccessToken  string          `json:"access_token"`
	RefreshToken string          `json:"refresh_token"`
	TokenType    string          `json:"token_type"`
	User         *common.UserDTO `json:"user"` // Use common.UserDTO in actual implementation
}

// ========================= MANAGED ACCOUNT REGISTRATION DTOs =========================

type CreateManagedAccountRequest struct {
	Username string          `json:"username" validate:"required,min=3,max=50,alphanum"`
	FullName string          `json:"full_name" validate:"required,min=2,max=100"`
	Role     entity.UserRole `json:"role" validate:"required"` // Manager or staff for seller and supporter for admin
}

type CreateManagedAccountResponse struct {
	Success           bool            `json:"success"`
	Username          string          `json:"username"`
	Role              entity.UserRole `json:"role"`
	TemporaryPassword string          `json:"temporary_password,omitempty"`
	Message           string          `json:"message"`
}
