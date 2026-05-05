package response

// ErrorDetail represents a single validation error
type ErrorDetail struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

// ErrorResponse represents an error response
type ErrorResponse struct {
	Error ErrorBody `json:"error"`
}

// ErrorBody contains error details
type ErrorBody struct {
	Code    string        `json:"code"`
	Message string       `json:"message"`
	Details []ErrorDetail `json:"details,omitempty"`
}

// NewErrorResponse creates a new error response
func NewErrorResponse(code, message string) ErrorResponse {
	return ErrorResponse{
		Error: ErrorBody{
			Code:    code,
			Message: message,
		},
	}
}

// NewValidationErrorResponse creates a validation error response with details
func NewValidationErrorResponse(message string, details []ErrorDetail) ErrorResponse {
	return ErrorResponse{
		Error: ErrorBody{
			Code:    "VALIDATION_ERROR",
			Message: message,
			Details: details,
		},
	}
}

// Error codes
const (
	ErrCodeProfileNotFound         = "PROFILE_NOT_FOUND"
	ErrCodeAddressNotFound         = "ADDRESS_NOT_FOUND"
	ErrCodePreferencesNotFound     = "PREFERENCES_NOT_FOUND"
	ErrCodeValidationError         = "VALIDATION_ERROR"
	ErrCodePhoneNumberExists       = "PHONE_NUMBER_EXISTS"
	ErrCodeAddressNotOwned          = "ADDRESS_NOT_FOUND"
	ErrCodeCannotDeleteOnlyAddress  = "CANNOT_DELETE_ONLY_DEFAULT_ADDRESS"
	ErrCodeInternalError           = "INTERNAL_ERROR"
	ErrCodeUnauthorized             = "UNAUTHORIZED"
)
