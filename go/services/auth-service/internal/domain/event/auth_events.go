package event

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

const (
	EventUserOTPRequested         = "USER_OTP_REQUESTED"
	EventUserVerifiedSuccess     = "USER_VERIFIED_SUCCESS"
	EventUserPasswordChanged     = "USER_PASSWORD_CHANGED"
	EventUserAccountLocked       = "USER_ACCOUNT_LOCKED"
	EventUserUnlocked            = "USER_UNLOCKED"
	EventUserBanned              = "USER_BANNED"
	EventUserDeleted             = "USER_DELETED"
	EventUserEmailChanged        = "USER_EMAIL_CHANGED"
	EventPasswordResetRequested  = "PASSWORD_RESET_REQUESTED"
	EventTokenTheftDetected      = "TOKEN_THEFT_DETECTED"
	EventLoginSuccess            = "LOGIN_SUCCESS"
	EventLoginFailed             = "LOGIN_FAILED"
	EventLogout                  = "LOGOUT"
	EventForceLogout             = "FORCE_LOGOUT"
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

type LoginSuccess struct {
	EventBase
	Payload LoginSuccessPayload `json:"payload"`
}

type LoginSuccessPayload struct {
	UserID    string `json:"user_id"`
	Email     string `json:"email"`
	IPAddress string `json:"ip_address"`
	DeviceID  string `json:"device_id"`
	UserAgent string `json:"user_agent"`
}

func NewLoginSuccess(userID, email, ipAddress, deviceID, deviceInfo string) *LoginSuccess {
	return &LoginSuccess{
		EventBase: NewEventBase(EventLoginSuccess, "AUTH", userID),
		Payload: LoginSuccessPayload{
			UserID:    userID,
			Email:     email,
			IPAddress: ipAddress,
			DeviceID:  deviceID,
			UserAgent: deviceInfo,
		},
	}
}

func (e *LoginSuccess) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

type LoginFailed struct {
	EventBase
	Payload LoginFailedPayload `json:"payload"`
}

type LoginFailedPayload struct {
	UserID    string `json:"user_id,omitempty"`
	Email     string `json:"email"`
	IPAddress string `json:"ip_address"`
	DeviceID  string `json:"device_id"`
	UserAgent string `json:"user_agent"`
	Reason    string `json:"reason"`
}

func NewLoginFailed(userID, email, ipAddress, deviceID, deviceInfo string) *LoginFailed {
	return &LoginFailed{
		EventBase: NewEventBase(EventLoginFailed, "AUTH", userID),
		Payload: LoginFailedPayload{
			UserID:    userID,
			Email:     email,
			IPAddress: ipAddress,
			DeviceID:  deviceID,
			UserAgent: deviceInfo,
			Reason:    "INVALID_CREDENTIALS",
		},
	}
}

func (e *LoginFailed) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

type LogoutEvent struct {
	EventBase
	Payload LogoutPayload `json:"payload"`
}

type LogoutPayload struct {
	UserID    string `json:"user_id"`
	IPAddress string `json:"ip_address"`
	DeviceID  string `json:"device_id"`
}

func NewLogoutEvent(userID string) *LogoutEvent {
	return &LogoutEvent{
		EventBase: NewEventBase(EventLogout, "AUTH", userID),
		Payload: LogoutPayload{
			UserID: userID,
		},
	}
}

func (e *LogoutEvent) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

type ForceLogoutEvent struct {
	EventBase
	Payload ForceLogoutPayload `json:"payload"`
}

type ForceLogoutPayload struct {
	UserID    string `json:"user_id"`
	Reason    string `json:"reason"`
	Initiator string `json:"initiator"`
}

func NewForceLogoutEvent(userID, reason string) *ForceLogoutEvent {
	return &ForceLogoutEvent{
		EventBase: NewEventBase(EventForceLogout, "AUTH", userID),
		Payload: ForceLogoutPayload{
			UserID: userID,
			Reason: reason,
		},
	}
}

func (e *ForceLogoutEvent) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

type UserUnlocked struct {
	EventBase
	Payload UserUnlockedPayload `json:"payload"`
}

type UserUnlockedPayload struct {
	UserID    string `json:"user_id"`
	Email     string `json:"email"`
	UnlockedAt string `json:"unlocked_at"`
	Reason    string `json:"reason"`
}

func NewUserUnlocked(userID, email, reason string) *UserUnlocked {
	return &UserUnlocked{
		EventBase: NewEventBase(EventUserUnlocked, "USER", userID),
		Payload: UserUnlockedPayload{
			UserID:     userID,
			Email:      email,
			UnlockedAt: time.Now().UTC().Format(time.RFC3339),
			Reason:     reason,
		},
	}
}

func (e *UserUnlocked) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

type UserBanned struct {
	EventBase
	Payload UserBannedPayload `json:"payload"`
}

type UserBannedPayload struct {
	UserID    string `json:"user_id"`
	Email     string `json:"email"`
	Reason    string `json:"reason"`
	BannedAt  string `json:"banned_at"`
}

func NewUserBanned(userID, email, reason string) *UserBanned {
	return &UserBanned{
		EventBase: NewEventBase(EventUserBanned, "USER", userID),
		Payload: UserBannedPayload{
			UserID:   userID,
			Email:    email,
			Reason:   reason,
			BannedAt: time.Now().UTC().Format(time.RFC3339),
		},
	}
}

func (e *UserBanned) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// TokenTheftDetected event
type TokenTheftDetected struct {
	EventBase
	Payload TokenTheftPayload `json:"payload"`
}

type TokenTheftPayload struct {
	UserID               string `json:"user_id"`
	TheftRefreshTokenID  string `json:"theft_refresh_token_id"`
	ActionTaken          string `json:"action_taken"`
}

func NewTokenTheftDetected(userID, tokenID, action string) *TokenTheftDetected {
	return &TokenTheftDetected{
		EventBase: NewEventBase(EventTokenTheftDetected, "AUTH", userID),
		Payload: TokenTheftPayload{
			UserID:              userID,
			TheftRefreshTokenID: tokenID,
			ActionTaken:         action,
		},
	}
}

func (e *TokenTheftDetected) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}
