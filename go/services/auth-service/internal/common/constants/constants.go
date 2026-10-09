// Package constants holds contract-level literals shared by adapters and the
// domain. Values that operators tune live in configuration, not here.
package constants

// Trusted identity headers injected by the API Gateway. They are never accepted
// from a public client (contracts/conventions/http.md).
const (
	HeaderUserID       = "X-User-Id"
	HeaderUserRoles    = "X-User-Roles"
	HeaderTokenJti     = "X-Token-Jti"
	HeaderRequestID    = "X-Request-Id"
	HeaderTraceparent  = "traceparent"
	HeaderRetryAfter   = "Retry-After"
	HeaderCacheControl = "Cache-Control"
	// HeaderServiceIdentity is the attribution header a service caller sends on
	// the internal listener, equal to the caller's ServiceAccount name
	// (docs/mesh/runtime-contract.md section 6).
	HeaderServiceIdentity = "X-Service-Identity"
)

// RefreshCookieName is the only transport for the opaque refresh token.
const RefreshCookieName = "auth_refresh"

// RefreshCookiePath scopes the cookie to the session endpoints.
const RefreshCookiePath = "/api/v1/auth/sessions"

// ContentTypeProblem is the media type of every error response.
const ContentTypeProblem = "application/problem+json"

// NoStore is required on every response that carries a credential.
const NoStore = "no-store"

// OTP purposes bound to a Redis state entry and to the OTP event payload.
const (
	OtpPurposeRegistration = "REGISTRATION_VERIFICATION"
	OtpPurposeEmailChange  = "EMAIL_CHANGE_VERIFICATION"
)

// Notification templates from contracts/http/auth/internal/v1/openapi.yaml.
const (
	TemplateRegistrationVerification = "REGISTRATION_VERIFICATION"
	TemplateEmailChangeVerification  = "EMAIL_CHANGE_VERIFICATION"
	TemplatePasswordReset            = "PASSWORD_RESET"
	TemplateEmailChanged             = "EMAIL_CHANGED"
	TemplatePasswordChanged          = "PASSWORD_CHANGED"
	TemplateAccountLocked            = "ACCOUNT_LOCKED"
	TemplateAccountUnlocked          = "ACCOUNT_UNLOCKED"
)

// HardDeleteRetentionDays is fixed by AUTH-BR-007 and enforced by a CHECK
// constraint in the schema, so it is not configurable.
const HardDeleteRetentionDays = 90

// OtpLength is fixed by the OpenAPI `Otp` schema (six digits).
const OtpLength = 6

// ResetTokenBytes yields a 43-character base64url secret, matching the
// `reset_token` minLength in the public contract.
const ResetTokenBytes = 32

// RefreshTokenBytes is the entropy of an opaque refresh token.
const RefreshTokenBytes = 32
