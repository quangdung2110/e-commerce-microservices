package errors

import "errors"

// Common errors
var (
	ErrInvalidRequest = errors.New("invalid request")
	ErrNotFound      = errors.New("not found")
	ErrInternal      = errors.New("internal server error")
)
