// Package httpx adapts the domain vocabulary to HTTP: RFC 9457 problem
// responses and strict request decoding.
package httpx

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/constants"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/common/logging"
	"github.com/ecommerce/user-service/internal/common/reqctx"
)

// problemTypeBase namespaces the problem type URIs, matching the examples in
// contracts/http/user/v1/openapi.yaml.
const problemTypeBase = "https://errors.ecommerce.local/"

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
// responses declared in contracts/http/user/v1/openapi.yaml.
var statusByCode = map[string]int{
	apperr.CodeValidationFailed:              http.StatusBadRequest,
	apperr.CodeUnauthorized:                  http.StatusUnauthorized,
	apperr.CodeForbidden:                     http.StatusForbidden,
	apperr.CodeProfileNotFound:               http.StatusNotFound,
	apperr.CodeAddressNotFound:               http.StatusNotFound,
	apperr.CodeNotFound:                      http.StatusNotFound,
	apperr.CodeMethodNotAllowed:              http.StatusMethodNotAllowed,
	apperr.CodeDefaultAddressCannotBeDeleted: http.StatusConflict,
	apperr.CodeDefaultAddressConflict:        http.StatusConflict,
	apperr.CodeInvalidProfileField:           http.StatusUnprocessableEntity,
	apperr.CodeInvalidAddress:                http.StatusUnprocessableEntity,
	apperr.CodeInvalidPreference:             http.StatusUnprocessableEntity,
	apperr.CodeRateLimited:                   http.StatusTooManyRequests,
	// A dependency failure is reported as an internal error because the public
	// contract of these endpoints does not define 503.
	apperr.CodeDependencyUnavailable: http.StatusInternalServerError,
	apperr.CodeInternal:              http.StatusInternalServerError,
}

// titleByCode gives each problem a short human title.
var titleByCode = map[string]string{
	apperr.CodeValidationFailed:              "Invalid request",
	apperr.CodeUnauthorized:                  "Unauthorized",
	apperr.CodeForbidden:                     "Forbidden",
	apperr.CodeProfileNotFound:               "Profile không tồn tại",
	apperr.CodeAddressNotFound:               "Address không tồn tại",
	apperr.CodeNotFound:                      "Not found",
	apperr.CodeMethodNotAllowed:              "Method not allowed",
	apperr.CodeDefaultAddressCannotBeDeleted: "Không thể xóa address default",
	apperr.CodeDefaultAddressConflict:        "Default address conflict",
	apperr.CodeInvalidProfileField:           "Profile field không hợp lệ",
	apperr.CodeInvalidAddress:                "Address không hợp lệ",
	apperr.CodeInvalidPreference:             "Preferences không hợp lệ",
	apperr.CodeRateLimited:                   "Too many requests",
	apperr.CodeDependencyUnavailable:         "Unexpected error",
	apperr.CodeInternal:                      "Unexpected error",
}

// WriteProblem renders any error as application/problem+json. The internal
// cause is logged, never serialized: a client sees a stable code and a safe
// message.
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

	c.Header(constants.HeaderCacheControl, constants.PrivateNoStore)
	c.Abort()
	c.Data(status, constants.ContentTypeProblem, payload)
}

func titleOf(code string) string {
	if title, ok := titleByCode[code]; ok {
		return title
	}
	return "Unexpected error"
}
