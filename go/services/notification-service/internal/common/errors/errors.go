// Package errors defines the stable error vocabulary shared by the domain, the
// HTTP layer (problem+json codes) and the workers (retryable vs permanent).
package errors

import (
	"errors"
	"fmt"
	"time"
)

// Stable problem codes (UPPER_SNAKE_CASE per contracts/shared problem schema).
const (
	CodeValidationFailed      = "VALIDATION_FAILED"
	CodeUnauthorized          = "UNAUTHORIZED"
	CodeForbidden             = "FORBIDDEN"
	CodeNotFound              = "NOT_FOUND"
	CodeMethodNotAllowed      = "METHOD_NOT_ALLOWED"
	CodeStateConflict         = "STATE_CONFLICT"
	CodeIdempotencyConflict   = "IDEMPOTENCY_CONFLICT"
	CodeIdempotencyInProgress = "IDEMPOTENCY_IN_PROGRESS"
	CodeRateLimited           = "RATE_LIMITED"
	CodeDependencyUnavailable = "DEPENDENCY_UNAVAILABLE"
	CodeInternal              = "INTERNAL_ERROR"
)

// Field is one field-level validation message.
type Field struct {
	Field   string
	Message string
}

// Error is the application error carried across layers.
type Error struct {
	Code       string
	Detail     string
	Retryable  bool
	RetryAfter time.Duration
	Fields     []Field
	cause      error
}

func (e *Error) Error() string {
	if e.cause != nil {
		return fmt.Sprintf("%s: %s: %v", e.Code, e.Detail, e.cause)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Detail)
}

func (e *Error) Unwrap() error { return e.cause }

// Is matches by stable code, so errors.Is(err, ErrValidation) holds for a
// clone produced by WithFields/AsRetryable and for any error built with the
// same code.
func (e *Error) Is(target error) bool {
	t, ok := target.(*Error)
	return ok && t.Code == e.Code
}

// New builds an application error.
func New(code, detail string) *Error { return &Error{Code: code, Detail: detail} }

// Wrap attaches a cause.
func Wrap(code, detail string, cause error) *Error {
	return &Error{Code: code, Detail: detail, cause: cause}
}

// WithFields attaches validation fields.
func (e *Error) WithFields(fields ...Field) *Error {
	clone := *e
	clone.Fields = append(clone.Fields, fields...)
	return &clone
}

// AsRetryable marks the error retryable.
func (e *Error) AsRetryable(after time.Duration) *Error {
	clone := *e
	clone.Retryable = true
	clone.RetryAfter = after
	return &clone
}

// From converts any error to an *Error, defaulting to INTERNAL_ERROR.
func From(err error) *Error {
	var appErr *Error
	if errors.As(err, &appErr) {
		return appErr
	}
	return Wrap(CodeInternal, "unexpected error", err)
}

// Sentinels used by ports.
var (
	ErrNotFound      = New(CodeNotFound, "resource not found")
	ErrUnauthorized  = New(CodeUnauthorized, "missing or invalid identity")
	ErrForbidden     = New(CodeForbidden, "identity not allowed")
	ErrStateConflict = New(CodeStateConflict, "current state does not allow this operation")
	ErrValidation    = New(CodeValidationFailed, "invalid request")
)

// Is is a convenience re-export.
func Is(err, target error) bool { return errors.Is(err, target) }

// As is a convenience re-export.
func As(err error, target any) bool { return errors.As(err, target) }

// ---- delivery-path classification --------------------------------------

// Retryable marks a provider/dependency failure worth another attempt
// (5xx, timeout, 429, connection refused).
type Retryable struct {
	Cause      error
	RetryAfter time.Duration
}

func (r *Retryable) Error() string { return "retryable: " + r.Cause.Error() }
func (r *Retryable) Unwrap() error { return r.Cause }

// Permanent marks a failure that will not change on retry (bad address,
// rejected by provider policy, render bug).
type Permanent struct {
	Code  string // reason code recorded in last_error_code
	Cause error
}

func (p *Permanent) Error() string { return "permanent(" + p.Code + "): " + p.Cause.Error() }
func (p *Permanent) Unwrap() error { return p.Cause }

// NewRetryable wraps a cause as retryable.
func NewRetryable(cause error) error { return &Retryable{Cause: cause} }

// NewPermanent wraps a cause as permanent with a reason code.
func NewPermanent(code string, cause error) error { return &Permanent{Code: code, Cause: cause} }

// IsRetryable reports whether err is classified retryable.
func IsRetryable(err error) bool {
	var r *Retryable
	return errors.As(err, &r)
}

// PermanentCode returns the reason code when err is permanent.
func PermanentCode(err error) (string, bool) {
	var p *Permanent
	if errors.As(err, &p) {
		return p.Code, true
	}
	return "", false
}

// Dependency-specific sentinels (wrapped as Retryable/Permanent by adapters).
var (
	ErrNoRecipient      = errors.New("recipient not found")
	ErrContentExpired   = errors.New("auth delivery content expired")
	ErrBreakerOpen      = errors.New("circuit breaker open")
	ErrRateLimitWait    = errors.New("rate limit token not acquired in time")
	ErrCacheMiss        = errors.New("cache miss")
	ErrLanePaused       = errors.New("lane paused")
	ErrInvalidSignature = errors.New("invalid SNS signature")
)
