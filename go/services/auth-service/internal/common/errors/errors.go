package errors

import (
	"errors"
	"net/http"
)

var (
	ErrInternal        = errors.New("internal server error")
	ErrNotFound        = errors.New("resource not found")
	ErrUnauthorized    = errors.New("unauthorized")
	ErrForbidden       = errors.New("forbidden")
	ErrBadRequest      = errors.New("bad request")
	ErrConflict        = errors.New("conflict")
	ErrTooManyRequests = errors.New("too many requests")
)

type AppError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	Status  int    `json:"-"`
	Cause   error  `json:"-"`
}

func (e *AppError) Error() string {
	if e.Cause != nil {
		return e.Cause.Error()
	}
	return e.Message
}

func (e *AppError) Unwrap() error {
	return e.Cause
}

func NewAppError(code, message string, status int, cause error) *AppError {
	return &AppError{
		Code:    code,
		Message: message,
		Status:  status,
		Cause:   cause,
	}
}

func ErrInvalidCredentials() *AppError {
	return NewAppError("INVALID_CREDENTIALS", "Invalid email or password", http.StatusUnauthorized, nil)
}

func ErrUserNotFound() *AppError {
	return NewAppError("USER_NOT_FOUND", "User not found", http.StatusNotFound, ErrNotFound)
}

func ErrUserAlreadyExists() *AppError {
	return NewAppError("USER_EXISTS", "User already exists", http.StatusConflict, ErrConflict)
}

func ErrAccountLocked() *AppError {
	return NewAppError("ACCOUNT_LOCKED", "Account is temporarily locked", http.StatusForbidden, nil)
}

func ErrAccountBanned() *AppError {
	return NewAppError("ACCOUNT_BANNED", "Account has been banned", http.StatusForbidden, nil)
}

func ErrEmailNotVerified() *AppError {
	return NewAppError("EMAIL_NOT_VERIFIED", "Please verify your email first", http.StatusForbidden, nil)
}

func ErrInvalidToken() *AppError {
	return NewAppError("INVALID_TOKEN", "Invalid or expired token", http.StatusUnauthorized, ErrUnauthorized)
}

func ErrTokenTheft() *AppError {
	return NewAppError("TOKEN_THEFT", "Session terminated due to security concern", http.StatusForbidden, nil)
}

func ErrRateLimitExceeded() *AppError {
	return NewAppError("RATE_LIMIT_EXCEEDED", "Too many requests", http.StatusTooManyRequests, ErrTooManyRequests)
}

func ErrInvalidOTP() *AppError {
	return NewAppError("INVALID_OTP", "Invalid or expired OTP code", http.StatusBadRequest, ErrBadRequest)
}

func ErrPasswordMismatch() *AppError {
	return NewAppError("PASSWORD_MISMATCH", "Passwords do not match", http.StatusBadRequest, ErrBadRequest)
}

func ErrWeakPassword() *AppError {
	return NewAppError("WEAK_PASSWORD", "Password does not meet requirements", http.StatusBadRequest, ErrBadRequest)
}

func ErrSessionNotFound() *AppError {
	return NewAppError("SESSION_NOT_FOUND", "Session not found", http.StatusNotFound, ErrNotFound)
}

func ErrMaxSessionsReached() *AppError {
	return NewAppError("MAX_SESSIONS", "Maximum number of sessions reached", http.StatusConflict, ErrConflict)
}

func IsNotFoundError(err error) bool {
	return errors.Is(err, ErrNotFound)
}
