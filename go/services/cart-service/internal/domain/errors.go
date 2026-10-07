package domain

import "errors"

// Code is a stable, machine-readable domain error code. It is surfaced in the
// `code` member of an RFC 9457 problem document.
type Code string

// Domain error codes (docs/cart/domain.md §8). Every code here is a business
// rule outcome, not an infrastructure failure.
const (
	CodeCartFull                Code = "CART_FULL"
	CodeProductSnapshotRequired Code = "PRODUCT_SNAPSHOT_REQUIRED"
	CodeDeltaInvalid            Code = "DELTA_INVALID"
	CodeQuantityInvalid         Code = "QUANTITY_INVALID"
	CodeItemNotFound            Code = "ITEM_NOT_FOUND"
	CodeProductNotAvailable     Code = "PRODUCT_NOT_AVAILABLE"
	CodeIdempotencyConflict     Code = "IDEMPOTENCY_CONFLICT"
	CodeUnauthenticated         Code = "UNAUTHENTICATED"
	CodeForbidden               Code = "FORBIDDEN"
	CodeValidationFailed        Code = "VALIDATION_FAILED"
	CodeRateLimited             Code = "RATE_LIMITED"
	CodeCartStoreUnavailable    Code = "CART_STORE_UNAVAILABLE"
	CodeProductUnavailable      Code = "PRODUCT_UNAVAILABLE"
	CodeInternal                Code = "INTERNAL_ERROR"
)

// Error is a domain error carrying a stable code and a human-readable detail.
type Error struct {
	Code   Code
	Detail string
	// Field is set when the error is attributable to one request field.
	Field string
	// Retryable marks errors the caller may retry unchanged.
	Retryable bool
}

func (e *Error) Error() string {
	return string(e.Code) + ": " + e.Detail
}

// NewError builds a domain error.
func NewError(code Code, detail string) *Error {
	return &Error{Code: code, Detail: detail}
}

// NewFieldError builds a domain error attributed to one request field.
func NewFieldError(code Code, field, detail string) *Error {
	return &Error{Code: code, Detail: detail, Field: field}
}

// NewRetryableError builds a domain error the caller may retry unchanged.
func NewRetryableError(code Code, detail string) *Error {
	return &Error{Code: code, Detail: detail, Retryable: true}
}

// AsError extracts a *Error from an error chain.
func AsError(err error) (*Error, bool) {
	var domainErr *Error
	if errors.As(err, &domainErr) {
		return domainErr, true
	}
	return nil, false
}

// ErrUnauthenticated is returned when no trusted identity reached the service.
var ErrUnauthenticated = NewError(CodeUnauthenticated, "missing trusted identity")

// scriptErrorCodes maps a Lua script error string onto a domain error.
// The scripts return bare codes via `return {err = '...'}`, and go-redis
// surfaces them as the error message.
var scriptErrorCodes = map[string]Code{
	"CART_FULL":                 CodeCartFull,
	"PRODUCT_SNAPSHOT_REQUIRED": CodeProductSnapshotRequired,
	"DELTA_INVALID":             CodeDeltaInvalid,
	"QUANTITY_INVALID":          CodeQuantityInvalid,
	"ITEM_NOT_FOUND":            CodeItemNotFound,
	"PRODUCT_NOT_AVAILABLE":     CodeProductNotAvailable,
	"IDEMPOTENCY_CONFLICT":      CodeIdempotencyConflict,
}

// ScriptError maps a raw Lua error string onto a domain error, reporting
// whether the string was a recognised domain code. Anything unrecognised is
// an infrastructure fault and must not be presented as a business outcome.
func ScriptError(raw string) (*Error, bool) {
	code, ok := scriptErrorCodes[raw]
	if !ok {
		return nil, false
	}
	return NewError(code, scriptErrorDetail(code)), true
}

func scriptErrorDetail(code Code) string {
	switch code {
	case CodeCartFull:
		return "cart already holds the maximum number of distinct SKUs"
	case CodeProductSnapshotRequired:
		return "a fresh product snapshot is required for this SKU"
	case CodeDeltaInvalid:
		return "delta must be a non-zero integer"
	case CodeQuantityInvalid:
		return "the resulting quantity would not be positive; delete the SKU instead"
	case CodeItemNotFound:
		return "the SKU is not in the cart"
	case CodeProductNotAvailable:
		return "the SKU is not available for sale"
	case CodeIdempotencyConflict:
		return "the idempotency key was already used with a different payload"
	default:
		return string(code)
	}
}
