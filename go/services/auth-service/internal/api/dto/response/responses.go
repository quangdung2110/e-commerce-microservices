// Package response holds the outbound payloads of the HTTP API. A refresh token
// or a token hash never appears in any of these types.
package response

import (
	"time"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// RegistrationAccepted confirms that an account awaits verification.
type RegistrationAccepted struct {
	Status string `json:"status"`
}

// NewRegistrationAccepted builds the registration response.
func NewRegistrationAccepted() RegistrationAccepted {
	return RegistrationAccepted{Status: vo.AccountPendingVerification.String()}
}

// AccessToken is the JSON half of a token pair. The refresh token travels only
// in the auth_refresh cookie.
type AccessToken struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int    `json:"expires_in"`
	SessionID   string `json:"session_id"`
}

// NewAccessToken renders a login or refresh result.
func NewAccessToken(result *service.LoginResult) AccessToken {
	return AccessToken{
		AccessToken: result.Access.Token,
		TokenType:   "Bearer",
		ExpiresIn:   result.Access.ExpiresIn(),
		SessionID:   result.SessionID.String(),
	}
}

// Session is one active session of the caller.
type Session struct {
	ID         string `json:"id"`
	DeviceName string `json:"device_name,omitempty"`
	IPAddress  string `json:"ip_address,omitempty"`
	CreatedAt  string `json:"created_at"`
	LastUsedAt string `json:"last_used_at"`
	ExpiresAt  string `json:"expires_at"`
}

// SessionList is a cursor-paginated page of sessions.
type SessionList struct {
	Sessions   []Session `json:"sessions"`
	NextCursor string    `json:"next_cursor,omitempty"`
}

// NewSessionList renders a page of sessions.
func NewSessionList(sessions []entity.Session, nextCursor string) SessionList {
	items := make([]Session, 0, len(sessions))
	for _, session := range sessions {
		items = append(items, Session{
			ID:         session.ID.String(),
			DeviceName: session.DeviceName,
			IPAddress:  session.IPAddress,
			CreatedAt:  formatTime(session.CreatedAt),
			LastUsedAt: formatTime(session.LastUsedAt),
			ExpiresAt:  formatTime(session.ExpiresAt),
		})
	}
	return SessionList{Sessions: items, NextCursor: nextCursor}
}

// AccountDeletionAccepted confirms an accepted deletion and states when the row
// itself disappears.
type AccountDeletionAccepted struct {
	Status          string `json:"status"`
	HardDeleteAfter string `json:"hard_delete_after"`
}

// NewAccountDeletionAccepted renders a deletion result.
func NewAccountDeletionAccepted(result *service.DeletionResult) AccountDeletionAccepted {
	return AccountDeletionAccepted{
		Status:          result.Status,
		HardDeleteAfter: formatTime(result.HardDeleteAfter),
	}
}

// JWKS is the public key set consumed by gateway-auth.
type JWKS struct {
	Keys []port.JWK `json:"keys"`
}

// NewJWKS renders the published keys.
func NewJWKS(keys []port.JWK) JWKS {
	return JWKS{Keys: keys}
}

// NotificationDeliveryContent is the internal API payload Notification Service
// renders. It is served only over mTLS and never cached.
type NotificationDeliveryContent struct {
	DeliveryID     string            `json:"delivery_id"`
	RecipientEmail string            `json:"recipient_email"`
	Template       string            `json:"template"`
	ExpiresAt      string            `json:"expires_at"`
	TemplateData   map[string]string `json:"template_data"`
}

// NewNotificationDeliveryContent renders the delivery state.
func NewNotificationDeliveryContent(delivery *entity.NotificationDelivery) NotificationDeliveryContent {
	data := delivery.TemplateData
	if data == nil {
		data = map[string]string{}
	}
	return NotificationDeliveryContent{
		DeliveryID:     delivery.DeliveryID.String(),
		RecipientEmail: delivery.RecipientEmail,
		Template:       delivery.Template,
		ExpiresAt:      formatTime(delivery.ExpiresAt),
		TemplateData:   data,
	}
}

// NotificationContact is the internal API payload Notification Service uses to
// address a notice Auth did not originate. It is served only over mTLS and
// never cached. Locale is always serialized, as null while Auth stores none.
type NotificationContact struct {
	UserID        string  `json:"user_id"`
	Email         string  `json:"email"`
	EmailVerified bool    `json:"email_verified"`
	Locale        *string `json:"locale"`
}

// NewNotificationContact renders the resolved contact.
func NewNotificationContact(contact *service.NotificationContact) NotificationContact {
	return NotificationContact{
		UserID:        contact.UserID.String(),
		Email:         contact.Email,
		EmailVerified: contact.EmailVerified,
		Locale:        contact.Locale,
	}
}

// Health is the probe payload. It never lists which dependency failed, because
// the endpoint is reachable from the cluster network.
type Health struct {
	Status string `json:"status"`
}

// formatTime renders an RFC 3339 UTC timestamp, the project-wide format.
func formatTime(t time.Time) string {
	return t.UTC().Format(time.RFC3339)
}
