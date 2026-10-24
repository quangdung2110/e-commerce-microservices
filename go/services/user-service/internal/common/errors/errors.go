// Package apperr defines the stable error vocabulary shared by the domain and
// the HTTP adapters. Codes match docs/user/domain.md section 7 and are surfaced
// to clients through the RFC 9457 `code` extension.
package apperr

import (
	"errors"
	"fmt"
	"time"
)

// Stable machine-readable error codes. They MUST match ^[A-Z][A-Z0-9_]{2,63}$.
const (
	CodeValidationFailed              = "VALIDATION_FAILED"
	CodeUnauthorized                  = "UNAUTHORIZED"
	CodeForbidden                     = "FORBIDDEN"
	CodeNotFound                      = "NOT_FOUND"
	CodeMethodNotAllowed              = "METHOD_NOT_ALLOWED"
	CodeProfileNotFound               = "PROFILE_NOT_FOUND"
	CodeAddressNotFound               = "ADDRESS_NOT_FOUND"
	CodeDefaultAddressCannotBeDeleted = "DEFAULT_ADDRESS_CANNOT_BE_DELETED"
	CodeDefaultAddressConflict        = "DEFAULT_ADDRESS_CONFLICT"
	CodeInvalidProfileField           = "INVALID_PROFILE_FIELD"
	CodeInvalidAddress                = "INVALID_ADDRESS"
	CodeInvalidPreference             = "INVALID_PREFERENCE"
	CodeDeletionAlreadyProcessed      = "DELETION_ALREADY_PROCESSED"
	CodeRetentionNotExpired           = "RETENTION_NOT_EXPIRED"
	CodeRateLimited                   = "RATE_LIMITED"
	CodeDependencyUnavailable         = "DEPENDENCY_UNAVAILABLE"
	CodeInternal                      = "INTERNAL_ERROR"
)

// FieldError describes one invalid request field. It never carries the value,
// because every writable field of this service is PII.
type FieldError struct {
	Field   string
	Message string
}

// Error is the single error type crossing the domain boundary.
type Error struct {
	Code string
	// Detail is safe to return to a client: no SQL, hostname, stack or PII.
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

// WithRetryAfter sets the Retry-After hint used for 429 responses.
func (e *Error) WithRetryAfter(d time.Duration) *Error {
	c := e.clone()
	c.RetryAfter = d
	return c
}

// HasFields reports whether any field-level error was collected.
func (e *Error) HasFields() bool { return len(e.Fields) > 0 }

func newError(code, detail string) *Error {
	return &Error{Code: code, Detail: detail}
}

// Sentinels. Handlers map these to HTTP status codes; the domain never knows
// about HTTP.
var (
	ErrValidation   = newError(CodeValidationFailed, "Request payload is invalid.")
	ErrUnauthorized = newError(CodeUnauthorized, "Trusted gateway identity is missing or invalid.")
	ErrForbidden    = newError(CodeForbidden, "The identity is not allowed to perform this operation.")
	ErrNotFound     = newError(CodeNotFound, "The requested resource does not exist.")
	// ErrMethodNotAllowed is produced by the router, not by the domain.
	ErrMethodNotAllowed = newError(CodeMethodNotAllowed, "The method is not allowed for this resource.")
	// ErrProfileNotFound covers a profile that was never bootstrapped and one
	// that the retention worker already purged (USER-BR-006, USER-BR-007).
	ErrProfileNotFound = newError(CodeProfileNotFound, "Profile không tồn tại.")
	// ErrAddressNotFound is returned both for an unknown address and for an
	// address owned by somebody else, so ownership is never disclosed
	// (USER-BR-001).
	ErrAddressNotFound               = newError(CodeAddressNotFound, "Address không tồn tại.")
	ErrDefaultAddressCannotBeDeleted = newError(CodeDefaultAddressCannotBeDeleted, "Hãy đặt address khác làm default trước.")
	ErrDefaultAddressConflict        = newError(CodeDefaultAddressConflict, "Address default của user đang xung đột.")
	ErrInvalidProfileField           = newError(CodeInvalidProfileField, "Một hoặc nhiều field profile không hợp lệ.")
	ErrInvalidAddress                = newError(CodeInvalidAddress, "Address, recipient phone hoặc mã địa giới không hợp lệ.")
	ErrInvalidPreference             = newError(CodeInvalidPreference, "Notification preferences không hợp lệ.")
	// ErrDeletionAlreadyProcessed is a consumer-side no-op, never an HTTP error.
	ErrDeletionAlreadyProcessed = newError(CodeDeletionAlreadyProcessed, "Deletion đã được xử lý.")
	// ErrRetentionNotExpired stops the purge worker from deleting before the
	// deadline Auth published (USER-BR-007).
	ErrRetentionNotExpired = newError(CodeRetentionNotExpired, "Retention window chưa hết hạn.")
	ErrRateLimited         = &Error{Code: CodeRateLimited, Detail: "Too many requests.", Retryable: true}
	// ErrDependencyUnavailable marks an infrastructure failure. Redis failures
	// are handled by falling back to PostgreSQL and are not reported with it.
	ErrDependencyUnavailable = &Error{Code: CodeDependencyUnavailable, Detail: "A required dependency is unavailable.", Retryable: true}
	ErrInternal              = newError(CodeInternal, "Unexpected error.")
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

// Retryable reports whether a consumer or worker should retry the operation
// instead of treating the error as a permanent rejection.
func Retryable(err error) bool {
	appErr := From(err)
	if appErr == nil {
		return false
	}
	switch appErr.Code {
	case CodeDependencyUnavailable, CodeInternal:
		return true
	default:
		return appErr.Retryable
	}
}
