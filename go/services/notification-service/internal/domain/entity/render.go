package entity

import (
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/pii"
)

// Contact is the recipient resolved from Auth at send time. It is cached for
// at most 15 minutes and never persisted (02-pipeline.md §2.3).
type Contact struct {
	UserID        uuid.UUID
	Email         pii.Email
	EmailVerified bool
	Locale        string // "" → default
}

// AuthContent is the response of POST .../notification-deliveries/{id}/content
// for Auth-initiated requests. TemplateData may contain a secret and must
// never be persisted or logged.
type AuthContent struct {
	DeliveryID   uuid.UUID
	Recipient    pii.Email
	Template     string
	ExpiresAt    time.Time
	TemplateData []byte
}

// EmailContent is the rendered email.
type EmailContent struct {
	Subject string
	HTML    string
	Text    string
	Headers map[string]string
}

// InAppContent is the rendered inbox item.
type InAppContent struct {
	Title    string
	Body     string
	DeepLink *string
}

// EmailMessage is what an email adapter sends.
type EmailMessage struct {
	DeliveryID uuid.UUID
	Lane       Lane
	To         pii.Email
	Content    EmailContent
}

// SendResult is the provider acknowledgement.
type SendResult struct {
	Provider  Provider
	MessageID string
}
