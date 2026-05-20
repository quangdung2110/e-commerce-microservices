package event

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// Kafka Topic: t.auth.events
// Events that Auth Service Publishes to other services

const (
	// USER Events
	EventUserOTPRequested       = "USER_OTP_REQUEST"         // OTP requested
	EventUserVerifiedSuccess   = "USER_VERIFIED_SUCCESS"   // Email verified
	EventUserDeleted           = "USER_DELETED"            // Account deleted (GDPR)
	EventUserEmailChanged      = "USER_EMAIL_CHANGED"       // Email changed
	EventUserPasswordChanged   = "USER_PASSWORD_CHANGED"    // Password changed
	EventUserAccountLocked     = "USER_ACCOUNT_LOCKED"     // Account locked
	EventUserUnlocked         = "USER_UNLOCKED"           // Account unlocked
	EventUserBannedNotification = "USER_BANNED_NOTIFICATION" // Account banned notification
	EventPasswordResetRequested = "PASSWORD_RESET_REQUESTED" // Password reset requested
	EventTokenTheftDetected   = "TOKEN_THEFT_DETECTED"    // Token theft detected

	// Internal events (not published to Kafka)
	eventLoginSuccess         = "LOGIN_SUCCESS"           // Internal use only
	eventLoginFailed          = "LOGIN_FAILED"            // Internal use only
	eventLogout               = "LOGOUT"                  // Internal use only
	eventForceLogout          = "FORCE_LOGOUT"            // Internal use only
)

const (
	OTPTypeRegister       = "REGISTER_VERIFICATION"
	OTPTypePasswordReset  = "PASSWORD_RESET"
	OTPTypeEmailChange    = "EMAIL_CHANGE"
	OTPTypeLogin2FA       = "LOGIN_2FA"
)

const (
	DeleteReasonGDPRRequest    = "GDPR_REQUEST"
	DeleteReasonAdminDeletion = "ADMIN_DELETION"
)

const (
	UnlockTypeAuto   = "AUTO"
	UnlockTypeAdmin  = "ADMIN"
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

// 1.1 USER_OTP_REQUESTED
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

// 1.2 PASSWORD_RESET_REQUESTED
type PasswordResetRequested struct {
	EventBase
	Payload PasswordResetRequestedPayload `json:"payload"`
}

type PasswordResetRequestedPayload struct {
	UserID  string `json:"user_id"`
	Email   string `json:"email"`
	OTPType string `json:"otp_type"`
}

func NewPasswordResetRequested(userID, email string) *PasswordResetRequested {
	return &PasswordResetRequested{
		EventBase: NewEventBase(EventPasswordResetRequested, "USER", userID),
		Payload: PasswordResetRequestedPayload{
			UserID:  userID,
			Email:   email,
			OTPType: OTPTypePasswordReset,
		},
	}
}

func (e *PasswordResetRequested) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// 1.3 USER_VERIFIED_SUCCESS
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

// 1.4 USER_DELETED
type UserDeleted struct {
	EventBase
	Payload UserDeletedPayload `json:"payload"`
}

type UserDeletedPayload struct {
	UserID                     string `json:"user_id"`
	DeletedAt                  string `json:"deleted_at"`
	Reason                     string `json:"reason"`
	RetentionWindowExpiresAt   string `json:"retention_window_expires_at"`
}

func NewUserDeleted(userID, reason string, retentionDays int) *UserDeleted {
	now := time.Now().UTC()
	retentionWindow := now.AddDate(0, 0, retentionDays)
	return &UserDeleted{
		EventBase: NewEventBase(EventUserDeleted, "USER", userID),
		Payload: UserDeletedPayload{
			UserID:                   userID,
			DeletedAt:                now.Format(time.RFC3339),
			Reason:                   reason,
			RetentionWindowExpiresAt: retentionWindow.Format(time.RFC3339),
		},
	}
}

func (e *UserDeleted) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// 1.5 TOKEN_THEFT_DETECTED
type TokenTheftDetected struct {
	EventBase
	Payload TokenTheftPayload `json:"payload"`
}

type TokenTheftPayload struct {
	UserID              string `json:"user_id"`
	TheftRefreshTokenID string `json:"theft_refresh_token_id"`
	ActionTaken        string `json:"action_taken"`
}

const ActionAllTokensRevoked = "ALL_TOKENS_REVOKED"

func NewTokenTheftDetected(userID, tokenID string) *TokenTheftDetected {
	return &TokenTheftDetected{
		EventBase: NewEventBase(EventTokenTheftDetected, "USER", userID),
		Payload: TokenTheftPayload{
			UserID:              userID,
			TheftRefreshTokenID: tokenID,
			ActionTaken:         ActionAllTokensRevoked,
		},
	}
}

func (e *TokenTheftDetected) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// 1.6 USER_EMAIL_CHANGED
type UserEmailChanged struct {
	EventBase
	Payload UserEmailChangedPayload `json:"payload"`
}

type UserEmailChangedPayload struct {
	UserID   string `json:"user_id"`
	OldEmail string `json:"old_email"`
	NewEmail string `json:"new_email"`
}

func NewUserEmailChanged(userID, oldEmail, newEmail string) *UserEmailChanged {
	return &UserEmailChanged{
		EventBase: NewEventBase(EventUserEmailChanged, "USER", userID),
		Payload: UserEmailChangedPayload{
			UserID:   userID,
			OldEmail: oldEmail,
			NewEmail: newEmail,
		},
	}
}

func (e *UserEmailChanged) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// 1.7 USER_PASSWORD_CHANGED
type UserPasswordChanged struct {
	EventBase
	Payload UserPasswordChangedPayload `json:"payload"`
}

type UserPasswordChangedPayload struct {
	UserID    string `json:"user_id"`
	Email     string `json:"email"`
	DeviceInfo string `json:"device_info"`
	ChangedAt string `json:"changed_at"`
}

func NewUserPasswordChanged(userID, email, deviceInfo string) *UserPasswordChanged {
	return &UserPasswordChanged{
		EventBase: NewEventBase(EventUserPasswordChanged, "USER", userID),
		Payload: UserPasswordChangedPayload{
			UserID:    userID,
			Email:     email,
			DeviceInfo: deviceInfo,
			ChangedAt: time.Now().UTC().Format(time.RFC3339),
		},
	}
}

func (e *UserPasswordChanged) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// 1.8 USER_ACCOUNT_LOCKED
type UserAccountLocked struct {
	EventBase
	Payload UserAccountLockedPayload `json:"payload"`
}

type UserAccountLockedPayload struct {
	UserID     string `json:"user_id"`
	Email      string `json:"email"`
	Reason     string `json:"reason"`
	LockedUntil string `json:"locked_until"`
}

func NewUserAccountLocked(userID, email, reason string, lockedUntil time.Time) *UserAccountLocked {
	return &UserAccountLocked{
		EventBase: NewEventBase(EventUserAccountLocked, "USER", userID),
		Payload: UserAccountLockedPayload{
			UserID:     userID,
			Email:      email,
			Reason:     reason,
			LockedUntil: lockedUntil.Format(time.RFC3339),
		},
	}
}

func (e *UserAccountLocked) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// 1.9 USER_UNLOCKED
type UserUnlocked struct {
	EventBase
	Payload UserUnlockedPayload `json:"payload"`
}

type UserUnlockedPayload struct {
	UserID     string `json:"user_id"`
	Email      string `json:"email"`
	UnlockType string `json:"unlock_type"`
	UnlockedBy string `json:"unlocked_by"`
}

func NewUserUnlocked(userID, email, unlockType, unlockedBy string) *UserUnlocked {
	return &UserUnlocked{
		EventBase: NewEventBase(EventUserUnlocked, "USER", userID),
		Payload: UserUnlockedPayload{
			UserID:     userID,
			Email:      email,
			UnlockType: unlockType,
			UnlockedBy: unlockedBy,
		},
	}
}

func (e *UserUnlocked) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// 1.11 USER_BANNED_NOTIFICATION
type UserBannedNotification struct {
	EventBase
	Payload UserBannedPayload `json:"payload"`
}

type UserBannedPayload struct {
	UserID   string `json:"user_id"`
	Email    string `json:"email"`
	Reason   string `json:"reason"`
	BannedBy string `json:"banned_by"`
	BannedAt string `json:"banned_at"`
}

func NewUserBannedNotification(userID, email, reason, bannedBy string) *UserBannedNotification {
	return &UserBannedNotification{
		EventBase: NewEventBase(EventUserBannedNotification, "USER", userID),
		Payload: UserBannedPayload{
			UserID:   userID,
			Email:    email,
			Reason:   reason,
			BannedBy: bannedBy,
			BannedAt: time.Now().UTC().Format(time.RFC3339),
		},
	}
}

func (e *UserBannedNotification) ToJSON() ([]byte, error) {
	return json.Marshal(e)
}

// ============================================
// INTERNAL EVENTS (Not published to Kafka)
// ============================================

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
		EventBase: NewEventBase(eventLoginSuccess, "AUTH", userID),
		Payload: LoginSuccessPayload{
			UserID:    userID,
			Email:     email,
			IPAddress: ipAddress,
			DeviceID:  deviceID,
			UserAgent: deviceInfo,
		},
	}
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
		EventBase: NewEventBase(eventLoginFailed, "AUTH", userID),
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
		EventBase: NewEventBase(eventLogout, "AUTH", userID),
		Payload: LogoutPayload{
			UserID: userID,
		},
	}
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
		EventBase: NewEventBase(eventForceLogout, "AUTH", userID),
		Payload: ForceLogoutPayload{
			UserID: userID,
			Reason: reason,
		},
	}
}
