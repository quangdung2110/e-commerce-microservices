// Package apperr defines the stable error vocabulary shared by the domain and
// the HTTP adapters. Codes match the error catalog in docs/auth/interfaces.md
// section 6 and are surfaced to clients through the RFC 9457 `code` extension.
package apperr

import (
	"errors"
	"fmt"
	"time"
)

// Stable machine-readable error codes. They MUST match ^[A-Z][A-Z0-9_]{2,63}$.
const (
	CodeValidationFailed       = "VALIDATION_FAILED"
	CodeUnauthorized           = "UNAUTHORIZED"
	CodeForbidden              = "FORBIDDEN"
	CodeInvalidCredentials     = "INVALID_CREDENTIALS"
	CodeAccountNotVerified     = "ACCOUNT_NOT_VERIFIED"
	CodeAccountLocked          = "ACCOUNT_LOCKED"
	CodeAccountNotActive       = "ACCOUNT_NOT_ACTIVE"
	CodeAccountDeleted         = "ACCOUNT_DELETED"
	CodeAccountNotFound        = "ACCOUNT_NOT_FOUND"
	CodeNotFound               = "NOT_FOUND"
	CodeMethodNotAllowed       = "METHOD_NOT_ALLOWED"
	CodeEmailAlreadyRegistered = "EMAIL_ALREADY_REGISTERED"
	CodeRefreshTokenInvalid    = "REFRESH_TOKEN_INVALID"
	CodeSessionNotFound        = "SESSION_NOT_FOUND"
	CodeOtpInvalid             = "OTP_INVALID"
	CodeResetTokenInvalid      = "RESET_TOKEN_INVALID"
	CodePasswordPolicy         = "PASSWORD_POLICY_VIOLATION"
	CodeRateLimited            = "RATE_LIMITED"
	CodeDeliveryNotFound       = "DELIVERY_NOT_FOUND"
	CodeDeliveryExpired        = "DELIVERY_EXPIRED"
	CodeSigningKeyMissing      = "SIGNING_KEY_MISSING"
	CodeDependencyUnavailable  = "DEPENDENCY_UNAVAILABLE"
	CodeConcurrentUpdate       = "CONCURRENT_UPDATE"
	CodeInternal               = "INTERNAL_ERROR"
)

// FieldError describes one invalid request field. It never carries the value.
type FieldError struct {
	Field   string
	Message string
}

// Error is the single error type crossing the domain boundary.
type Error struct {
	Code string
	// Detail is safe to return to a client: no SQL, hostname, stack or secret.
	Detail     string
	Retryable  bool
	RetryAfter time.Duration
	Fields     []FieldError
	cause      error
}

func (e *Error) Error() string {
	if e.cause != nil {
		return fmt.Sprintf("%s: %s: %v", e.Code, e.Detail, e.cause)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Detail)
}

func (e *Error) Unwrap() error { return e.cause }

// Is compares by stable code so callers can use errors.Is with the sentinels
// below even after WithCause/WithFields produced a copy.
func (e *Error) Is(target error) bool {
	var t *Error
	if !errors.As(target, &t) {
		return false
	}
	return t.Code == e.Code
}

func (e *Error) clone() *Error {
	c := *e
	return &c
}

// WithCause attaches an internal cause that is logged but never serialized.
func (e *Error) WithCause(err error) *Error {
	c := e.clone()
	c.cause = err
	return c
}

// WithDetail replaces the client-safe detail message.
func (e *Error) WithDetail(detail string) *Error {
	c := e.clone()
	c.Detail = detail
	return c
}

// WithFields attaches field-level validation errors.
func (e *Error) WithFields(fields ...FieldError) *Error {
	c := e.clone()
	c.Fields = append(append([]FieldError(nil), c.Fields...), fields...)
	return c
}

// WithRetryAfter sets the Retry-After hint used for 423 and 429 responses.
func (e *Error) WithRetryAfter(d time.Duration) *Error {
	c := e.clone()
	c.RetryAfter = d
	return c
}

func newError(code, detail string) *Error {
	return &Error{Code: code, Detail: detail}
}

// Sentinels. Handlers map these to HTTP status codes; the domain never knows
// about HTTP.
var (
	ErrValidation             = newError(CodeValidationFailed, "Request payload is invalid.")
	ErrUnauthorized           = newError(CodeUnauthorized, "Trusted gateway identity is missing or invalid.")
	ErrForbidden              = newError(CodeForbidden, "The identity is not allowed to perform this operation.")
	ErrInvalidCredentials     = newError(CodeInvalidCredentials, "Email or password is invalid.")
	ErrAccountNotVerified     = newError(CodeAccountNotVerified, "The account email has not been verified.")
	ErrAccountLocked          = newError(CodeAccountLocked, "The account is temporarily locked.")
	ErrAccountNotActive       = newError(CodeAccountNotActive, "The account is not in a state that allows this operation.")
	ErrAccountDeleted         = newError(CodeAccountDeleted, "The account has been deleted.")
	ErrAccountNotFound        = newError(CodeAccountNotFound, "The account does not exist.")
	ErrNotFound               = newError(CodeNotFound, "The requested resource does not exist.")
	ErrMethodNotAllowed       = newError(CodeMethodNotAllowed, "The method is not allowed for this resource.")
	ErrEmailAlreadyRegistered = newError(CodeEmailAlreadyRegistered, "The email address is already registered.")
	ErrRefreshTokenInvalid    = newError(CodeRefreshTokenInvalid, "The refresh token is invalid, expired or revoked.")
	ErrSessionNotFound        = newError(CodeSessionNotFound, "The session does not exist or does not belong to this account.")
	ErrOtpInvalid             = newError(CodeOtpInvalid, "The verification code is invalid, expired or already used.")
	ErrResetTokenInvalid      = newError(CodeResetTokenInvalid, "The reset token is invalid, expired or already used.")
	ErrPasswordPolicy         = newError(CodePasswordPolicy, "The new password does not meet the password policy.")
	ErrRateLimited            = &Error{Code: CodeRateLimited, Detail: "Too many requests.", Retryable: true}
	ErrDeliveryNotFound       = newError(CodeDeliveryNotFound, "The delivery does not exist.")
	ErrDeliveryExpired        = newError(CodeDeliveryExpired, "The delivery state has expired.")
	// ErrSigningKeyMissing means no ACTIVE signing key exists yet. The key
	// lifecycle worker creates one; token issuance fails closed until it does.
	ErrSigningKeyMissing = newError(CodeSigningKeyMissing, "No signing key is available.")
	// ErrDependencyUnavailable is the fail-closed error used when Redis or another
	// security-critical dependency cannot be reached (ADR-004).
	ErrDependencyUnavailable = &Error{Code: CodeDependencyUnavailable, Detail: "A required dependency is unavailable.", Retryable: true}
	// ErrConcurrentUpdate is returned by the account repository when the row
	// changed since it was loaded. Services reload and re-apply their change; the
	// error only reaches a client when every attempt lost the race.
	ErrConcurrentUpdate = &Error{Code: CodeConcurrentUpdate, Detail: "The account was changed by another request. Retry.", Retryable: true}
	ErrInternal         = newError(CodeInternal, "Unexpected error.")
)

// From normalizes any error into *Error so adapters always have a code.
func From(err error) *Error {
	if err == nil {
		return nil
	}
	var appErr *Error
	if errors.As(err, &appErr) {
		return appErr
	}
	return ErrInternal.WithCause(err)
}
