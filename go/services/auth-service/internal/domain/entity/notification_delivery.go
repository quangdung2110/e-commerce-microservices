package entity

import (
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/common/constants"
)

// NotificationDelivery is the TTL-bound render state that Notification Service
// resolves over the internal mTLS API. Kafka carries only the delivery_id, so
// no recipient address, OTP or reset URL is ever written to a topic or a log.
type NotificationDelivery struct {
	DeliveryID     uuid.UUID
	AccountID      uuid.UUID
	RecipientEmail string
	Template       string
	ExpiresAt      time.Time
	TemplateData   map[string]string
}

// NewOtpDelivery builds the delivery state for a verification code.
func NewOtpDelivery(accountID uuid.UUID, recipient, template, otp string, expiresAt time.Time) *NotificationDelivery {
	return &NotificationDelivery{
		DeliveryID:     uuid.New(),
		AccountID:      accountID,
		RecipientEmail: recipient,
		Template:       template,
		ExpiresAt:      expiresAt.UTC(),
		TemplateData:   map[string]string{"otp": otp},
	}
}

// NewPasswordResetDelivery builds the delivery state carrying the opaque reset
// URL. The URL is a secret: it never appears in an event or in a log.
func NewPasswordResetDelivery(accountID uuid.UUID, recipient, resetURL string, expiresAt time.Time) *NotificationDelivery {
	return &NotificationDelivery{
		DeliveryID:     uuid.New(),
		AccountID:      accountID,
		RecipientEmail: recipient,
		Template:       constants.TemplatePasswordReset,
		ExpiresAt:      expiresAt.UTC(),
		TemplateData:   map[string]string{"reset_url": resetURL},
	}
}

// NewSecurityNoticeDelivery builds the delivery state of a notice that carries
// no secret, such as EMAIL_CHANGED or ACCOUNT_LOCKED.
func NewSecurityNoticeDelivery(accountID uuid.UUID, recipient, template string, data map[string]string, expiresAt time.Time) *NotificationDelivery {
	if data == nil {
		data = map[string]string{}
	}
	return &NotificationDelivery{
		DeliveryID:     uuid.New(),
		AccountID:      accountID,
		RecipientEmail: recipient,
		Template:       template,
		ExpiresAt:      expiresAt.UTC(),
		TemplateData:   data,
	}
}

// Expired reports whether the delivery state may no longer be resolved.
func (d *NotificationDelivery) Expired(now time.Time) bool {
	return !now.UTC().Before(d.ExpiresAt)
}
