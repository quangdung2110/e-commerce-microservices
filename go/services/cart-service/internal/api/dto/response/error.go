package response

import (
	"errors"
	"net/http"
	"time"

	"cart-service/internal/domain/entity"
)

// ErrorCode represents API error codes.
type ErrorCode string

const (
	ErrInvalidRequest     ErrorCode = "INVALID_REQUEST"
	ErrProductNotFound   ErrorCode = "PRODUCT_NOT_FOUND"
	ErrProductUnavailable ErrorCode = "PRODUCT_UNAVAILABLE"
	ErrInsufficientStock  ErrorCode = "INSUFFICIENT_STOCK"
	ErrCartFull          ErrorCode = "CART_FULL"
	ErrCartNotFound      ErrorCode = "CART_NOT_FOUND"
	ErrInternal          ErrorCode = "INTERNAL_ERROR"
	ErrUnauthorized      ErrorCode = "UNAUTHORIZED"
	ErrRateLimited       ErrorCode = "RATE_LIMITED"
)

// ErrorResponse represents a standardized error response.
type ErrorResponse struct {
	Code      ErrorCode `json:"code"`
	Message   string    `json:"message"`
	Details   string    `json:"details,omitempty"`
	RequestID string    `json:"request_id,omitempty"`
	Timestamp string    `json:"timestamp"`
}

// NewErrorResponse creates a new error response.
func NewErrorResponse(code ErrorCode, message string, requestID string) *ErrorResponse {
	return &ErrorResponse{
		Code:      code,
		Message:   message,
		RequestID: requestID,
		Timestamp: time.Now().UTC().Format(time.RFC3339),
	}
}

// FromDomainError converts domain errors to API error responses.
func FromDomainError(err error, requestID string) *ErrorResponse {
	switch {
	case errors.Is(err, entity.ErrProductNotFound):
		return NewErrorResponse(ErrProductNotFound, "Sản phẩm không tồn tại", requestID)
	case errors.Is(err, entity.ErrProductUnavailable):
		return NewErrorResponse(ErrProductUnavailable, "Sản phẩm không còn khả dụng", requestID)
	case errors.Is(err, entity.ErrInsufficientStock):
		return NewErrorResponse(ErrInsufficientStock, "Số lượng tồn kho không đủ", requestID)
	case errors.Is(err, entity.ErrCartFull):
		return NewErrorResponse(ErrCartFull, "Giỏ hàng đã đầy", requestID)
	case errors.Is(err, entity.ErrCartNotFound):
		return NewErrorResponse(ErrCartNotFound, "Giỏ hàng không tồn tại", requestID)
	case errors.Is(err, entity.ErrInvalidQuantity),
		errors.Is(err, entity.ErrQuantityExceedsLimit),
		errors.Is(err, entity.ErrMissingRequiredField):
		return NewErrorResponse(ErrInvalidRequest, "Yêu cầu không hợp lệ", requestID)
	default:
		return NewErrorResponse(ErrInternal, "Lỗi hệ thống nội bộ", requestID)
	}
}

// HTTPStatusCode returns the HTTP status code for an error code.
func HTTPStatusCode(code ErrorCode) int {
	switch code {
	case ErrUnauthorized, ErrRateLimited:
		return http.StatusUnauthorized
	case ErrProductNotFound, ErrCartNotFound:
		return http.StatusNotFound
	case ErrInvalidRequest, ErrCartFull:
		return http.StatusBadRequest
	case ErrProductUnavailable, ErrInsufficientStock:
		return http.StatusUnprocessableEntity
	default:
		return http.StatusInternalServerError
	}
}
