// Package event builds the CloudEvents 1.0 messages defined by
// contracts/events/auth/v1/asyncapi.yaml. Payloads carry identifiers only: no
// email, password, OTP, reset token or refresh token ever reaches a topic.
package event

import (
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Channel selects the Kafka topic a message belongs to. The physical topic name
// is resolved by the publisher from configuration.
type Channel string

const (
	// ChannelIdentity is t.auth.events, consumed by User Service.
	ChannelIdentity Channel = "identity"
	// ChannelNotification is t.auth.notification.events, consumed only by
	// Notification Service.
	ChannelNotification Channel = "notification"
)

// CloudEvents envelope constants required by the shared schema.
const (
	specVersion     = "1.0"
	source          = "/services/auth"
	dataContentType = "application/json"
)

// Event types, versioned on the wire.
const (
	TypeUserVerifiedSuccess    = "com.ecommerce.auth.user-verified-success.v1"
	TypeUserDeleted            = "com.ecommerce.auth.user-deleted.v1"
	TypeUserOtpRequested       = "com.ecommerce.auth.user-otp-requested.v1"
	TypePasswordResetRequested = "com.ecommerce.auth.password-reset-requested.v1"
	TypeUserEmailChanged       = "com.ecommerce.auth.user-email-changed.v1"
	TypeUserPasswordChanged    = "com.ecommerce.auth.user-password-changed.v1"
	TypeUserAccountLocked      = "com.ecommerce.auth.user-account-locked.v1"
	TypeUserUnlocked           = "com.ecommerce.auth.user-unlocked.v1"
)

const schemaBase = "/contracts/events/auth/v1/schemas/"

// Envelope is the structured CloudEvents JSON message. Channel and Key are
// routing metadata and are not serialized.
type Envelope struct {
	SpecVersion     string `json:"specversion"`
	ID              string `json:"id"`
	Type            string `json:"type"`
	Source          string `json:"source"`
	Subject         string `json:"subject"`
	Time            string `json:"time"`
	DataContentType string `json:"datacontenttype"`
	DataSchema      string `json:"dataschema"`
	CorrelationID   string `json:"correlationid,omitempty"`
	TraceParent     string `json:"traceparent,omitempty"`
	Data            any    `json:"data"`

	Channel Channel `json:"-"`
	Key     string  `json:"-"`
}

// Metadata carries the correlation identifiers of the originating request.
type Metadata struct {
	CorrelationID string
	TraceParent   string
}

// FormatTime renders an RFC 3339 UTC timestamp as required by the conventions.
func FormatTime(t time.Time) string {
	return t.UTC().Format(time.RFC3339)
}

func newEnvelope(channel Channel, eventType, schemaFile string, accountID uuid.UUID, occurredAt time.Time, meta Metadata, data any) *Envelope {
	correlationID := meta.CorrelationID
	if correlationID == "" {
		// The schema constrains correlationid to a UUID, so fall back to the
		// account rather than propagating an arbitrary edge request id.
		correlationID = accountID.String()
	}
	return &Envelope{
		SpecVersion:     specVersion,
		ID:              uuid.NewString(),
		Type:            eventType,
		Source:          source,
		Subject:         fmt.Sprintf("accounts/%s", accountID),
		Time:            FormatTime(occurredAt),
		DataContentType: dataContentType,
		DataSchema:      schemaBase + schemaFile,
		CorrelationID:   correlationID,
		TraceParent:     meta.TraceParent,
		Data:            data,
		Channel:         channel,
		Key:             accountID.String(),
	}
}

// UserVerifiedSuccessData is the payload of USER_VERIFIED_SUCCESS.
type UserVerifiedSuccessData struct {
	UserID     string   `json:"user_id"`
	Roles      []string `json:"roles"`
	VerifiedAt string   `json:"verified_at"`
}

// NewUserVerifiedSuccess reports a completed account verification to User Service.
func NewUserVerifiedSuccess(accountID uuid.UUID, roles []string, verifiedAt time.Time, meta Metadata) *Envelope {
	return newEnvelope(ChannelIdentity, TypeUserVerifiedSuccess, "user-verified-success.schema.json",
		accountID, verifiedAt, meta, UserVerifiedSuccessData{
			UserID:     accountID.String(),
			Roles:      roles,
			VerifiedAt: FormatTime(verifiedAt),
		})
}

// UserDeletedData is the payload of USER_DELETED.
type UserDeletedData struct {
	UserID          string `json:"user_id"`
	DeletedAt       string `json:"deleted_at"`
	Reason          string `json:"reason"`
	HardDeleteAfter string `json:"hard_delete_after"`
}

// NewUserDeleted starts the downstream deletion flow.
func NewUserDeleted(accountID uuid.UUID, deletedAt, hardDeleteAfter time.Time, reason string, meta Metadata) *Envelope {
	return newEnvelope(ChannelIdentity, TypeUserDeleted, "user-deleted.schema.json",
		accountID, deletedAt, meta, UserDeletedData{
			UserID:          accountID.String(),
			DeletedAt:       FormatTime(deletedAt),
			Reason:          reason,
			HardDeleteAfter: FormatTime(hardDeleteAfter),
		})
}

// UserOtpRequestedData is the payload of USER_OTP_REQUESTED.
type UserOtpRequestedData struct {
	DeliveryID  string `json:"delivery_id"`
	UserID      string `json:"user_id"`
	Purpose     string `json:"purpose"`
	RequestedAt string `json:"requested_at"`
	ExpiresAt   string `json:"expires_at"`
}

// NewUserOtpRequested asks Notification to send a verification code. The code
// itself is resolved over the internal API.
func NewUserOtpRequested(accountID, deliveryID uuid.UUID, purpose string, requestedAt, expiresAt time.Time, meta Metadata) *Envelope {
	return newEnvelope(ChannelNotification, TypeUserOtpRequested, "user-otp-requested.schema.json",
		accountID, requestedAt, meta, UserOtpRequestedData{
			DeliveryID:  deliveryID.String(),
			UserID:      accountID.String(),
			Purpose:     purpose,
			RequestedAt: FormatTime(requestedAt),
			ExpiresAt:   FormatTime(expiresAt),
		})
}

// PasswordResetRequestedData is the payload of PASSWORD_RESET_REQUESTED.
type PasswordResetRequestedData struct {
	DeliveryID  string `json:"delivery_id"`
	UserID      string `json:"user_id"`
	RequestedAt string `json:"requested_at"`
	ExpiresAt   string `json:"expires_at"`
}

// NewPasswordResetRequested asks Notification to send the opaque reset link.
func NewPasswordResetRequested(accountID, deliveryID uuid.UUID, requestedAt, expiresAt time.Time, meta Metadata) *Envelope {
	return newEnvelope(ChannelNotification, TypePasswordResetRequested, "password-reset-requested.schema.json",
		accountID, requestedAt, meta, PasswordResetRequestedData{
			DeliveryID:  deliveryID.String(),
			UserID:      accountID.String(),
			RequestedAt: FormatTime(requestedAt),
			ExpiresAt:   FormatTime(expiresAt),
		})
}

// UserEmailChangedData is the payload of USER_EMAIL_CHANGED.
type UserEmailChangedData struct {
	DeliveryID string `json:"delivery_id"`
	UserID     string `json:"user_id"`
	ChangedAt  string `json:"changed_at"`
}

// NewUserEmailChanged warns the previous address that the identity changed.
func NewUserEmailChanged(accountID, deliveryID uuid.UUID, changedAt time.Time, meta Metadata) *Envelope {
	return newEnvelope(ChannelNotification, TypeUserEmailChanged, "user-email-changed.schema.json",
		accountID, changedAt, meta, UserEmailChangedData{
			DeliveryID: deliveryID.String(),
			UserID:     accountID.String(),
			ChangedAt:  FormatTime(changedAt),
		})
}

// UserPasswordChangedData is the payload of USER_PASSWORD_CHANGED.
type UserPasswordChangedData struct {
	DeliveryID string `json:"delivery_id"`
	UserID     string `json:"user_id"`
	ChangedAt  string `json:"changed_at"`
}

// NewUserPasswordChanged notifies the account owner of a credential change.
func NewUserPasswordChanged(accountID, deliveryID uuid.UUID, changedAt time.Time, meta Metadata) *Envelope {
	return newEnvelope(ChannelNotification, TypeUserPasswordChanged, "user-password-changed.schema.json",
		accountID, changedAt, meta, UserPasswordChangedData{
			DeliveryID: deliveryID.String(),
			UserID:     accountID.String(),
			ChangedAt:  FormatTime(changedAt),
		})
}

// UserAccountLockedData is the payload of USER_ACCOUNT_LOCKED.
type UserAccountLockedData struct {
	DeliveryID  string `json:"delivery_id"`
	UserID      string `json:"user_id"`
	LockedAt    string `json:"locked_at"`
	LockedUntil string `json:"locked_until"`
}

// NewUserAccountLocked reports that the failed-login threshold was reached.
func NewUserAccountLocked(accountID, deliveryID uuid.UUID, lockedAt, lockedUntil time.Time, meta Metadata) *Envelope {
	return newEnvelope(ChannelNotification, TypeUserAccountLocked, "user-account-locked.schema.json",
		accountID, lockedAt, meta, UserAccountLockedData{
			DeliveryID:  deliveryID.String(),
			UserID:      accountID.String(),
			LockedAt:    FormatTime(lockedAt),
			LockedUntil: FormatTime(lockedUntil),
		})
}

// UserUnlockedData is the payload of USER_UNLOCKED.
type UserUnlockedData struct {
	DeliveryID string `json:"delivery_id"`
	UserID     string `json:"user_id"`
	UnlockedAt string `json:"unlocked_at"`
}

// NewUserUnlocked reports that the worker returned a locked account to ACTIVE.
func NewUserUnlocked(accountID, deliveryID uuid.UUID, unlockedAt time.Time, meta Metadata) *Envelope {
	return newEnvelope(ChannelNotification, TypeUserUnlocked, "user-unlocked.schema.json",
		accountID, unlockedAt, meta, UserUnlockedData{
			DeliveryID: deliveryID.String(),
			UserID:     accountID.String(),
			UnlockedAt: FormatTime(unlockedAt),
		})
}
