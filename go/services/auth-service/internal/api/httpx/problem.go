// Package httpx adapts the domain vocabulary to HTTP: RFC 9457 problem
// responses, strict request decoding and the refresh cookie.
package httpx

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/logging"
	"github.com/ecommerce/auth-service/internal/common/reqctx"
)

// problemTypeBase namespaces the problem type URIs of this service.
const problemTypeBase = "https://contracts.ecommerce.local/problems/"

// FieldProblem is one field-level validation error.
type FieldProblem struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

// Problem is the RFC 9457 body shared by every service. All of type, title,
// status, detail, instance and code are required by the shared schema.
type Problem struct {
	Type      string         `json:"type"`
	Title     string         `json:"title"`
	Status    int            `json:"status"`
	Detail    string         `json:"detail"`
	Instance  string         `json:"instance"`
	Code      string         `json:"code"`
	TraceID   string         `json:"trace_id,omitempty"`
	Retryable bool           `json:"retryable"`
	Errors    []FieldProblem `json:"errors,omitempty"`
}

// statusByCode maps the stable error codes to HTTP status codes, following the
// responses declared in contracts/http/auth/v1/openapi.yaml.
var statusByCode = map[string]int{
	apperr.CodeValidationFailed:       http.StatusBadRequest,
	apperr.CodeUnauthorized:           http.StatusUnauthorized,
	apperr.CodeInvalidCredentials:     http.StatusUnauthorized,
	apperr.CodeRefreshTokenInvalid:    http.StatusUnauthorized,
	apperr.CodeForbidden:              http.StatusForbidden,
	apperr.CodeAccountNotVerified:     http.StatusForbidden,
	apperr.CodeAccountNotFound:        http.StatusNotFound,
	apperr.CodeSessionNotFound:        http.StatusNotFound,
	apperr.CodeNotFound:               http.StatusNotFound,
	apperr.CodeMethodNotAllowed:       http.StatusMethodNotAllowed,
	apperr.CodeDeliveryNotFound:       http.StatusNotFound,
	apperr.CodeEmailAlreadyRegistered: http.StatusConflict,
	apperr.CodeAccountNotActive:       http.StatusConflict,
	apperr.CodeAccountDeleted:         http.StatusConflict,
	apperr.CodeDeliveryExpired:        http.StatusGone,
	apperr.CodeOtpInvalid:             http.StatusUnprocessableEntity,
	apperr.CodeResetTokenInvalid:      http.StatusUnprocessableEntity,
	apperr.CodePasswordPolicy:         http.StatusUnprocessableEntity,
	apperr.CodeAccountLocked:          http.StatusLocked,
	apperr.CodeRateLimited:            http.StatusTooManyRequests,
	// A dependency failure is reported as an internal error because the public
	// contract of these endpoints does not define 503.
	apperr.CodeSigningKeyMissing:     http.StatusInternalServerError,
	apperr.CodeDependencyUnavailable: http.StatusInternalServerError,
	// Four lost races in a row; declared as 500 because the public contract of
	// the affected endpoints defines no 409 for it.
	apperr.CodeConcurrentUpdate: http.StatusInternalServerError,
	apperr.CodeInternal:         http.StatusInternalServerError,
}

// titleByCode gives each problem a short human title.
var titleByCode = map[string]string{
	apperr.CodeValidationFailed:       "Invalid request",
	apperr.CodeUnauthorized:           "Unauthorized",
	apperr.CodeInvalidCredentials:     "Authentication failed",
	apperr.CodeRefreshTokenInvalid:    "Refresh failed",
	apperr.CodeForbidden:              "Forbidden",
	apperr.CodeAccountNotVerified:     "Account not verified",
	apperr.CodeAccountNotFound:        "Account not found",
	apperr.CodeSessionNotFound:        "Session not found",
	apperr.CodeNotFound:               "Not found",
	apperr.CodeMethodNotAllowed:       "Method not allowed",
	apperr.CodeDeliveryNotFound:       "Delivery not found",
	apperr.CodeEmailAlreadyRegistered: "Email already registered",
	apperr.CodeAccountNotActive:       "Account state conflict",
	apperr.CodeAccountDeleted:         "Account deleted",
	apperr.CodeDeliveryExpired:        "Delivery expired",
	apperr.CodeOtpInvalid:             "Verification failed",
	apperr.CodeResetTokenInvalid:      "Reset token invalid",
	apperr.CodePasswordPolicy:         "Password policy violation",
	apperr.CodeAccountLocked:          "Account locked",
	apperr.CodeRateLimited:            "Too many requests",
	apperr.CodeSigningKeyMissing:      "Unexpected error",
	apperr.CodeDependencyUnavailable:  "Unexpected error",
	apperr.CodeConcurrentUpdate:       "Unexpected error",
	apperr.CodeInternal:               "Unexpected error",
}

// WriteProblem renders any error as application/problem+json. The internal cause
// is logged, never serialized: a client sees a stable code and a safe message.
func WriteProblem(c *gin.Context, err error) {
	appErr := apperr.From(err)
	status, ok := statusByCode[appErr.Code]
	if !ok {
		status = http.StatusInternalServerError
	}

	if status >= http.StatusInternalServerError {
		logging.FromContext(c.Request.Context()).Error("request failed",
			zap.String("code", appErr.Code),
			zap.String("path", c.FullPath()),
			zap.Error(err),
		)
	}

	if seconds := retryAfterSeconds(status, appErr.Retryable, appErr.RetryAfter); seconds > 0 {
		c.Header(constants.HeaderRetryAfter, strconv.Itoa(seconds))
	}

	problem := Problem{
		Type:      problemTypeBase + strings.ToLower(strings.ReplaceAll(appErr.Code, "_", "-")),
		Title:     titleOf(appErr.Code),
		Status:    status,
		Detail:    appErr.Detail,
		Instance:  c.Request.URL.Path,
		Code:      appErr.Code,
		TraceID:   reqctx.RequestID(c.Request.Context()),
		Retryable: appErr.Retryable,
	}
	for _, field := range appErr.Fields {
		problem.Errors = append(problem.Errors, FieldProblem{Field: field.Field, Message: field.Message})
	}

	// The body is marshalled here so the response keeps the problem+json media
	// type; gin's JSON helper would overwrite it with application/json.
	payload, marshalErr := json.Marshal(problem)
	if marshalErr != nil {
		c.AbortWithStatus(http.StatusInternalServerError)
		return
	}

	c.Header(constants.HeaderCacheControl, constants.NoStore)
	c.Abort()
	c.Data(status, constants.ContentTypeProblem, payload)
}

func titleOf(code string) string {
	if title, ok := titleByCode[code]; ok {
		return title
	}
	return "Unexpected error"
}
