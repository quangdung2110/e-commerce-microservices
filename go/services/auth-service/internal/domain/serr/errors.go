package serr

import (
	"errors"
	"fmt"
	"net/http"
)

// ========================= ERROR TYPES =========================

// ErrorType represents the category of error
type ErrorType string

const (
	ErrorTypeAuthentication ErrorType = "AUTHENTICATION"
	ErrorTypeAuthorization  ErrorType = "AUTHORIZATION"
	ErrorTypeValidation     ErrorType = "VALIDATION"
	ErrorTypeRateLimit      ErrorType = "RATE_LIMIT"
	ErrorTypeResource       ErrorType = "RESOURCE"
	ErrorTypeInternal       ErrorType = "INTERNAL"
	ErrorTypeExternal       ErrorType = "EXTERNAL"
)

// AppError represents application-level errors with HTTP status codes
type AppError struct {
	Type       ErrorType `json:"type"`
	Code       string    `json:"code"`
	Message    string    `json:"message"`
	Details    string    `json:"details,omitempty"`
	HTTPStatus int       `json:"-"`
}

// Error implements error interface
func (e *AppError) Error() string {
	if e.Details != "" {
		return fmt.Sprintf("[%s] %s: %s - %s", e.Type, e.Code, e.Message, e.Details)
	}
	return fmt.Sprintf("[%s] %s: %s", e.Type, e.Code, e.Message)
}

// NewAppError creates a new AppError
func NewAppError(errType ErrorType, code string, message string, httpStatus int) *AppError {
	return &AppError{
		Type:       errType,
		Code:       code,
		Message:    message,
		HTTPStatus: httpStatus,
	}
}

// WithDetails adds details to the error
func (e *AppError) WithDetails(details string) *AppError {
	e.Details = details
	return e
}

// ========================= AUTHENTICATION ERRORS =========================

var (
	// Login/Authentication errors
	ErrInvalidCredentials = NewAppError(
		ErrorTypeAuthentication,
		"INVALID_CREDENTIALS",
		"Invalid email/username or password",
		http.StatusUnauthorized,
	)

	ErrAccountDeactivated = NewAppError(
		ErrorTypeAuthentication,
		"ACCOUNT_DEACTIVATED",
		"This account has been deactivated",
		http.StatusForbidden,
	)

	ErrAccountLocked = NewAppError(
		ErrorTypeAuthentication,
		"ACCOUNT_LOCKED",
		"Account is temporarily locked due to suspicious activity",
		http.StatusForbidden,
	)

	ErrAccountRestricted = NewAppError(
		ErrorTypeAuthentication,
		"ACCOUNT_RESTRICTED",
		"Account has restrictions in place",
		http.StatusForbidden,
	)

	ErrIPBlocked = NewAppError(
		ErrorTypeRateLimit,
		"IP_BLOCKED",
		"Too many failed login attempts from this IP address. Please try again later",
		http.StatusTooManyRequests,
	)

	ErrUserNotFound = NewAppError(
		ErrorTypeResource,
		"USER_NOT_FOUND",
		"User not found",
		http.StatusNotFound,
	)

	// 2FA errors (optional)
	Err2FARequired = NewAppError(
		ErrorTypeAuthentication,
		"2FA_REQUIRED",
		"Two-factor authentication is required",
		http.StatusUnauthorized,
	)

	Err2FACodeInvalid = NewAppError(
		ErrorTypeAuthentication,
		"2FA_CODE_INVALID",
		"Invalid two-factor authentication code",
		http.StatusUnauthorized,
	)

	Err2FACodeExpired = NewAppError(
		ErrorTypeAuthentication,
		"2FA_CODE_EXPIRED",
		"Two-factor authentication code has expired",
		http.StatusUnauthorized,
	)
)

// ========================= TOKEN ERRORS =========================

var (
	ErrTokenInvalid = NewAppError(
		ErrorTypeAuthentication,
		"TOKEN_INVALID",
		"Invalid or malformed token",
		http.StatusUnauthorized,
	)

	ErrTokenExpired = NewAppError(
		ErrorTypeAuthentication,
		"TOKEN_EXPIRED",
		"Token has expired",
		http.StatusUnauthorized,
	)

	ErrTokenRevoked = NewAppError(
		ErrorTypeAuthentication,
		"TOKEN_REVOKED",
		"Token has been revoked",
		http.StatusUnauthorized,
	)

	ErrTokenNotFound = NewAppError(
		ErrorTypeResource,
		"TOKEN_NOT_FOUND",
		"Token not found",
		http.StatusNotFound,
	)

	ErrRefreshTokenInvalid = NewAppError(
		ErrorTypeAuthentication,
		"REFRESH_TOKEN_INVALID",
		"Invalid refresh token",
		http.StatusUnauthorized,
	)

	ErrRefreshTokenExpired = NewAppError(
		ErrorTypeAuthentication,
		"REFRESH_TOKEN_EXPIRED",
		"Refresh token has expired",
		http.StatusUnauthorized,
	)

	ErrRefreshTokenRevoked = NewAppError(
		ErrorTypeAuthentication,
		"REFRESH_TOKEN_REVOKED",
		"Refresh token has been revoked",
		http.StatusUnauthorized,
	)

	ErrRefreshTokenTheft = NewAppError(
		ErrorTypeAuthentication,
		"REFRESH_TOKEN_THEFT",
		"Refresh token theft detected. All sessions have been revoked for security",
		http.StatusUnauthorized,
	)
)

// ========================= SESSION ERRORS =========================

var (
	ErrSessionNotFound = NewAppError(
		ErrorTypeResource,
		"SESSION_NOT_FOUND",
		"Session not found",
		http.StatusNotFound,
	)

	ErrSessionExpired = NewAppError(
		ErrorTypeAuthentication,
		"SESSION_EXPIRED",
		"Session has expired",
		http.StatusUnauthorized,
	)

	ErrSessionInvalid = NewAppError(
		ErrorTypeAuthentication,
		"SESSION_INVALID",
		"Invalid session",
		http.StatusUnauthorized,
	)

	ErrSessionRevoked = NewAppError(
		ErrorTypeAuthentication,
		"SESSION_REVOKED",
		"Session has been revoked",
		http.StatusUnauthorized,
	)

	ErrMaxSessionsReached = NewAppError(
		ErrorTypeValidation,
		"MAX_SESSIONS_REACHED",
		"Maximum number of active sessions reached",
		http.StatusForbidden,
	)

	ErrUnauthorizedSessionAccess = NewAppError(
		ErrorTypeAuthorization,
		"UNAUTHORIZED_SESSION_ACCESS",
		"You are not authorized to access this session",
		http.StatusForbidden,
	)
)

// ========================= REGISTRATION ERRORS =========================

var (
	ErrEmailAlreadyExists = NewAppError(
		ErrorTypeValidation,
		"EMAIL_ALREADY_EXISTS",
		"This email address is already registered",
		http.StatusConflict,
	)

	ErrPhoneAlreadyExists = NewAppError(
		ErrorTypeValidation,
		"PHONE_ALREADY_EXISTS",
		"This phone number is already registered",
		http.StatusConflict,
	)

	ErrUsernameAlreadyExists = NewAppError(
		ErrorTypeValidation,
		"USERNAME_ALREADY_EXISTS",
		"This username is already taken",
		http.StatusConflict,
	)

	ErrEmailNotVerified = NewAppError(
		ErrorTypeAuthentication,
		"EMAIL_NOT_VERIFIED",
		"Email address has not been verified",
		http.StatusForbidden,
	)

	ErrPhoneNotVerified = NewAppError(
		ErrorTypeAuthentication,
		"PHONE_NOT_VERIFIED",
		"Phone number has not been verified",
		http.StatusForbidden,
	)
)

// ========================= OTP ERRORS =========================

var (
	ErrOTPInvalid = NewAppError(
		ErrorTypeValidation,
		"OTP_INVALID",
		"Invalid OTP code",
		http.StatusBadRequest,
	)

	ErrOTPExpired = NewAppError(
		ErrorTypeValidation,
		"OTP_EXPIRED",
		"OTP code has expired",
		http.StatusBadRequest,
	)

	ErrOTPMaxAttemptsReached = NewAppError(
		ErrorTypeValidation,
		"OTP_MAX_ATTEMPTS_REACHED",
		"Maximum OTP verification attempts exceeded",
		http.StatusTooManyRequests,
	)

	ErrOTPNotFound = NewAppError(
		ErrorTypeResource,
		"OTP_NOT_FOUND",
		"OTP code not found or already verified",
		http.StatusNotFound,
	)

	ErrOTPAlreadySent = NewAppError(
		ErrorTypeRateLimit,
		"OTP_ALREADY_SENT",
		"OTP was sent recently. Please wait before requesting a new one",
		http.StatusTooManyRequests,
	)

	ErrOTPRateLimitExceeded = NewAppError(
		ErrorTypeRateLimit,
		"OTP_RATE_LIMIT_EXCEEDED",
		"Too many OTP requests. Please try again later",
		http.StatusTooManyRequests,
	)

	ErrOTPPurposeMismatch = NewAppError(
		ErrorTypeValidation,
		"OTP_PURPOSE_MISMATCH",
		"OTP was generated for a different purpose",
		http.StatusBadRequest,
	)

	ErrOTPNotVerified = NewAppError(
		ErrorTypeValidation,
		"OTP_NOT_VERIFIED",
		"OTP has not been verified yet",
		http.StatusBadRequest,
	)
)

// ========================= PASSWORD ERRORS =========================

var (
	ErrPasswordTooWeak = NewAppError(
		ErrorTypeValidation,
		"PASSWORD_TOO_WEAK",
		"Password does not meet security requirements",
		http.StatusBadRequest,
	)

	ErrPasswordSameAsOld = NewAppError(
		ErrorTypeValidation,
		"PASSWORD_SAME_AS_OLD",
		"New password cannot be the same as the old password",
		http.StatusBadRequest,
	)

	ErrPasswordIncorrect = NewAppError(
		ErrorTypeAuthentication,
		"PASSWORD_INCORRECT",
		"Current password is incorrect",
		http.StatusUnauthorized,
	)

	ErrPasswordResetTokenInvalid = NewAppError(
		ErrorTypeValidation,
		"PASSWORD_RESET_TOKEN_INVALID",
		"Invalid password reset token",
		http.StatusBadRequest,
	)

	ErrPasswordResetTokenExpired = NewAppError(
		ErrorTypeValidation,
		"PASSWORD_RESET_TOKEN_EXPIRED",
		"Password reset token has expired",
		http.StatusBadRequest,
	)

	ErrPasswordResetTokenAlreadyUsed = NewAppError(
		ErrorTypeValidation,
		"PASSWORD_RESET_TOKEN_ALREADY_USED",
		"Password reset token has already been used",
		http.StatusBadRequest,
	)
)

// ========================= VALIDATION ERRORS =========================

var (
	ErrInvalidInput = NewAppError(
		ErrorTypeValidation,
		"INVALID_INPUT",
		"Invalid input provided",
		http.StatusBadRequest,
	)

	ErrInvalidEmail = NewAppError(
		ErrorTypeValidation,
		"INVALID_EMAIL",
		"Invalid email address format",
		http.StatusBadRequest,
	)

	ErrInvalidPhone = NewAppError(
		ErrorTypeValidation,
		"INVALID_PHONE",
		"Invalid phone number format",
		http.StatusBadRequest,
	)

	ErrInvalidUsername = NewAppError(
		ErrorTypeValidation,
		"INVALID_USERNAME",
		"Invalid username format",
		http.StatusBadRequest,
	)

	ErrInvalidOTPChannel = NewAppError(
		ErrorTypeValidation,
		"INVALID_OTP_CHANNEL",
		"Invalid OTP delivery channel",
		http.StatusBadRequest,
	)

	ErrInvalidOTPPurpose = NewAppError(
		ErrorTypeValidation,
		"INVALID_OTP_PURPOSE",
		"Invalid OTP purpose",
		http.StatusBadRequest,
	)

	ErrMissingRequiredField = NewAppError(
		ErrorTypeValidation,
		"MISSING_REQUIRED_FIELD",
		"Required field is missing",
		http.StatusBadRequest,
	)

	ErrInvalidUUID = NewAppError(
		ErrorTypeValidation,
		"INVALID_UUID",
		"Invalid UUID format",
		http.StatusBadRequest,
	)
)

// ========================= RATE LIMIT ERRORS =========================

var (
	ErrTooManyRequests = NewAppError(
		ErrorTypeRateLimit,
		"TOO_MANY_REQUESTS",
		"Too many requests. Please try again later",
		http.StatusTooManyRequests,
	)

	ErrRateLimitExceeded = NewAppError(
		ErrorTypeRateLimit,
		"RATE_LIMIT_EXCEEDED",
		"Rate limit exceeded",
		http.StatusTooManyRequests,
	)

	ErrLoginAttemptsExceeded = NewAppError(
		ErrorTypeRateLimit,
		"LOGIN_ATTEMPTS_EXCEEDED",
		"Too many login attempts. Please try again later",
		http.StatusTooManyRequests,
	)

	ErrPasswordResetAttemptsExceeded = NewAppError(
		ErrorTypeRateLimit,
		"PASSWORD_RESET_ATTEMPTS_EXCEEDED",
		"Too many password reset attempts. Please try again later",
		http.StatusTooManyRequests,
	)
)

// ========================= AUTHORIZATION ERRORS =========================

var (
	ErrUnauthorized = NewAppError(
		ErrorTypeAuthorization,
		"UNAUTHORIZED",
		"You are not authorized to perform this action",
		http.StatusUnauthorized,
	)

	ErrForbidden = NewAppError(
		ErrorTypeAuthorization,
		"FORBIDDEN",
		"Access to this resource is forbidden",
		http.StatusForbidden,
	)

	ErrInsufficientPermissions = NewAppError(
		ErrorTypeAuthorization,
		"INSUFFICIENT_PERMISSIONS",
		"You do not have sufficient permissions",
		http.StatusForbidden,
	)
)

// ========================= INTERNAL/EXTERNAL ERRORS =========================

var (
	ErrInternal = NewAppError(
		ErrorTypeInternal,
		"INTERNAL_ERROR",
		"An internal server error occurred",
		http.StatusInternalServerError,
	)

	ErrDatabaseError = NewAppError(
		ErrorTypeInternal,
		"DATABASE_ERROR",
		"Database operation failed",
		http.StatusInternalServerError,
	)

	ErrCacheError = NewAppError(
		ErrorTypeInternal,
		"CACHE_ERROR",
		"Cache operation failed",
		http.StatusInternalServerError,
	)

	ErrExternalServiceError = NewAppError(
		ErrorTypeExternal,
		"EXTERNAL_SERVICE_ERROR",
		"External service is unavailable",
		http.StatusServiceUnavailable,
	)

	ErrNotificationServiceError = NewAppError(
		ErrorTypeExternal,
		"NOTIFICATION_SERVICE_ERROR",
		"Failed to send notification",
		http.StatusServiceUnavailable,
	)

	ErrUserServiceError = NewAppError(
		ErrorTypeExternal,
		"USER_SERVICE_ERROR",
		"User service is unavailable",
		http.StatusServiceUnavailable,
	)

	ErrEventPublishError = NewAppError(
		ErrorTypeInternal,
		"EVENT_PUBLISH_ERROR",
		"Failed to publish event",
		http.StatusInternalServerError,
	)
)

// ========================= HELPER FUNCTIONS =========================

// IsAppError checks if an error is an AppError
func IsAppError(err error) bool {
	var appErr *AppError
	return errors.As(err, &appErr)
}

// GetAppError extracts AppError from error chain
func GetAppError(err error) *AppError {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr
	}
	return nil
}

// WrapError wraps an error with additional context
func WrapError(err error, message string) error {
	if err == nil {
		return nil
	}
	if appErr := GetAppError(err); appErr != nil {
		return appErr.WithDetails(message)
	}
	return fmt.Errorf("%s: %w", message, err)
}
