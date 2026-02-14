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
