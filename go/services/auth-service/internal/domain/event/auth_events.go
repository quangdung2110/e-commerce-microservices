package event

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

const (
	EventUserOTPRequested       = "USER_OTP_REQUESTED"
	EventUserVerifiedSuccess   = "USER_VERIFIED_SUCCESS"
	EventUserPasswordChanged   = "USER_PASSWORD_CHANGED"
	EventUserAccountLocked     = "USER_ACCOUNT_LOCKED"
	EventUserUnlocked          = "USER_UNLOCKED"
	EventUserBanned           = "USER_BANNED"
	EventUserDeleted          = "USER_DELETED"
	EventUserEmailChanged      = "USER_EMAIL_CHANGED"
	EventPasswordResetRequested = "PASSWORD_RESET_REQUESTED"
	EventTokenTheftDetected    = "TOKEN_THEFT_DETECTED"
)

const (
	OTPTypeRegister         = "REGISTER_VERIFICATION"
	OTPTypePasswordReset    = "PASSWORD_RESET"
	OTPTypeEmailChange      = "EMAIL_CHANGE"
)

type EventBase struct {
	EventID       string    `json:"event_id"`
	EventType     string    `json:"event_type"`
	AggregateType string    `json:"aggregate_type"`
	AggregateID   string    `json:"aggregate_id"`
	PartitionKey  string    `json:"partition_key"`
	OccurredAt    time.Time `json:"occurred_at"`
}

func NewEventBase(eventType, aggregateType, aggregateID string) EventBase {
	return EventBase{
		EventID:       uuid.New().String(),
		EventType:     eventType,
		AggregateType: aggregateType,
		AggregateID:   aggregateID,
		PartitionKey:  aggregateID,
		OccurredAt:    time.Now().UTC(),
	}
}

type UserOTPRequested struct {
	EventBase
	Payload UserOTPRequestedPayload `json:"payload"`
}

type UserOTPRequestedPayload struct {
	UserID  string `json:"user_id"`
	Email   string `json:"email"`
	OTPType string `json:"otp_type"`
}

func NewUserOTPRequested(userID, email, otpType string) *UserOTPRequested {
	return &UserOTPRequested{
		EventBase: NewEventBase(EventUserOTPRequested, "USER", userID),
		Payload: UserOTPRequestedPayload{
			UserID:  userID,
			Email:   email,
			OTPType: otpType,
		},
	}
}

func (e *UserOTPRequested) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

type UserVerifiedSuccess struct {
	EventBase
	Payload UserVerifiedPayload `json:"payload"`
}

type UserVerifiedPayload struct {
	UserID string   `json:"user_id"`
	Email  string   `json:"email"`
	Roles  []string `json:"roles"`
}

func NewUserVerifiedSuccess(userID, email string, roles []string) *UserVerifiedSuccess {
	return &UserVerifiedSuccess{
		EventBase: NewEventBase(EventUserVerifiedSuccess, "USER", userID),
		Payload: UserVerifiedPayload{
			UserID: userID,
			Email:  email,
			Roles:  roles,
		},
	}
}

func (e *UserVerifiedSuccess) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

type UserPasswordChanged struct {
	EventBase
	Payload UserPasswordChangedPayload `json:"payload"`
}

type UserPasswordChangedPayload struct {
	UserID    string `json:"user_id"`
	Email     string `json:"email"`
	ChangedAt string `json:"changed_at"`
}

func NewUserPasswordChanged(userID, email string) *UserPasswordChanged {
	return &UserPasswordChanged{
		EventBase: NewEventBase(EventUserPasswordChanged, "USER", userID),
		Payload: UserPasswordChangedPayload{
			UserID:    userID,
			Email:     email,
			ChangedAt: time.Now().UTC().Format(time.RFC3339),
		},
	}
}

func (e *UserPasswordChanged) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

type UserAccountLocked struct {
	EventBase
	Payload UserAccountLockedPayload `json:"payload"`
}

type UserAccountLockedPayload struct {
	UserID    string `json:"user_id"`
	Email     string `json:"email"`
	Reason    string `json:"reason"`
	LockedAt  string `json:"locked_at"`
}

func NewUserAccountLocked(userID, email, reason string) *UserAccountLocked {
	return &UserAccountLocked{
		EventBase: NewEventBase(EventUserAccountLocked, "USER", userID),
		Payload: UserAccountLockedPayload{
			UserID:   userID,
			Email:    email,
			Reason:   reason,
			LockedAt: time.Now().UTC().Format(time.RFC3339),
		},
	}
}

func (e *UserAccountLocked) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}
