package v1

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
)

// Response represents a standard API response
type Response struct {
	Success   bool        `json:"success"`
	Data      interface{} `json:"data,omitempty"`
	Error     *ErrorInfo  `json:"error,omitempty"`
	Timestamp string      `json:"timestamp"`
}

// ErrorInfo represents error details
type ErrorInfo struct {
	Type    string `json:"type"`
	Code    string `json:"code"`
	Message string `json:"message"`
	Details string `json:"details,omitempty"`
}

// SuccessResponse sends a successful response
func SuccessResponse(c *gin.Context, statusCode int, data interface{}) {
	c.JSON(statusCode, Response{
		Success:   true,
		Data:      data,
		Timestamp: getCurrentTimestamp(),
	})
}

// ErrorResponse sends an error response
func ErrorResponse(c *gin.Context, err error) {
	// Check if it's an AppError
	if appErr := serr.GetAppError(err); appErr != nil {
		c.JSON(appErr.HTTPStatus, Response{
			Success: false,
			Error: &ErrorInfo{
				Type:    string(appErr.Type),
				Code:    appErr.Code,
				Message: appErr.Message,
				Details: appErr.Details,
			},
			Timestamp: getCurrentTimestamp(),
		})
		return
	}

	// Default internal error
	c.JSON(http.StatusInternalServerError, Response{
		Success: false,
		Error: &ErrorInfo{
			Type:    string(serr.ErrorTypeInternal),
			Code:    "INTERNAL_ERROR",
			Message: "An internal server error occurred",
			Details: err.Error(),
		},
		Timestamp: getCurrentTimestamp(),
	})
}

// ValidationErrorResponse sends a validation error response
func ValidationErrorResponse(c *gin.Context, validationErrors map[string]string) {
	c.JSON(http.StatusBadRequest, gin.H{
		"success": false,
		"error": gin.H{
			"type":    string(serr.ErrorTypeValidation),
			"code":    "VALIDATION_ERROR",
			"message": "Validation failed",
			"fields":  validationErrors,
		},
		"timestamp": getCurrentTimestamp(),
	})
}

// getCurrentTimestamp returns current timestamp in RFC3339 format
func getCurrentTimestamp() string {
	return time.Now().UTC().Format(time.RFC3339)
}

// SendSuccessResponse sends a success response
func SendSuccessResponse(c *gin.Context, statusCode int, data interface{}) {
	respondSuccessWithMessage(c, statusCode, "Success", data)
}

// SendErrorResponse sends an error response with custom code and message
func SendErrorResponse(c *gin.Context, statusCode int, errCode string, message string, err error) {
	c.JSON(statusCode, Response{
		Success: false,
		Error: &ErrorInfo{
			Type:    string(serr.ErrorTypeValidation), // Defaulting to validation or generic
			Code:    errCode,
			Message: message,
			Details: func() string {
				if err != nil {
					return err.Error()
				}
				return ""
			}(),
		},
		Timestamp: getCurrentTimestamp(),
	})
}

// Aliases for backward compatibility
var (
	respondSuccess = respondSuccessWithMessage
	respondError   = ErrorResponse
)

// respondSuccessWithMessage sends a success response with a message
func respondSuccessWithMessage(c *gin.Context, statusCode int, message string, data interface{}) {
	c.JSON(statusCode, Response{
		Success:   true,
		Data:      data,
		Timestamp: getCurrentTimestamp(),
	})
}

// PaginatedResponse represents a paginated API response
type PaginatedResponse struct {
	Success   bool        `json:"success"`
	Data      interface{} `json:"data"`
	Pagination Pagination `json:"pagination"`
	Timestamp string      `json:"timestamp"`
}

// Pagination represents pagination metadata
type Pagination struct {
	Page       int   `json:"page"`
	Limit      int   `json:"limit"`
	Total      int64 `json:"total"`
	TotalPages int   `json:"total_pages"`
}

// Paginated sends a paginated response
func Paginated(c *gin.Context, data interface{}, total int64, limit, offset int) {
	page := 0
	if limit > 0 {
		page = offset / limit
	}

	totalPages := 0
	if total > 0 && limit > 0 {
		totalPages = int((total + int64(limit) - 1) / int64(limit))
	}

	c.JSON(http.StatusOK, PaginatedResponse{
		Success:   true,
		Data:      data,
		Pagination: Pagination{
			Page:       page,
			Limit:      limit,
			Total:      total,
			TotalPages: totalPages,
		},
		Timestamp: getCurrentTimestamp(),
	})
}

// Success sends a success response with data
func Success(c *gin.Context, statusCode int, data interface{}) {
	c.JSON(statusCode, Response{
		Success:   true,
		Data:      data,
		Timestamp: getCurrentTimestamp(),
	})
}

// Error sends an error response
func Error(c *gin.Context, statusCode int, code, message string, err error) {
	details := ""
	if err != nil {
		details = err.Error()
	}

	c.JSON(statusCode, Response{
		Success: false,
		Error: &ErrorInfo{
			Type:    string(serr.ErrorTypeInternal),
			Code:    code,
			Message: message,
			Details: details,
		},
		Timestamp: getCurrentTimestamp(),
	})
}

// Unauthorized sends an unauthorized error response
func Unauthorized(c *gin.Context) {
	c.JSON(http.StatusUnauthorized, Response{
		Success: false,
		Error: &ErrorInfo{
			Type:    string(serr.ErrorTypeAuthentication),
			Code:    "UNAUTHORIZED",
			Message: "Authentication required",
		},
		Timestamp: getCurrentTimestamp(),
	})
}

// Forbidden sends a forbidden error response
func Forbidden(c *gin.Context) {
	c.JSON(http.StatusForbidden, Response{
		Success: false,
		Error: &ErrorInfo{
			Type:    string(serr.ErrorTypeAuthorization),
			Code:    "FORBIDDEN",
			Message: "Access denied",
		},
		Timestamp: getCurrentTimestamp(),
	})
}
