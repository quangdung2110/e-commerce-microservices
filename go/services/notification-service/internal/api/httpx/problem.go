// Package httpx adapts the domain vocabulary to HTTP: RFC 9457 problem
// responses, strict request decoding and the no-store success writers every
// inbox/status/ops response needs (10-interfaces.md §5–6).
package httpx

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/constants"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/common/reqctx"
)

// problemTypeBase namespaces the problem type URIs of this service.
const problemTypeBase = "https://contracts.ecommerce.local/problems/"

// FieldProblem is one field-level validation error.
type FieldProblem struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

// Problem is the RFC 9457 body shared by every service
// (contracts/shared/json-schema/v1/problem.schema.json): type, title, status,
// detail, instance and code are all required.
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
// responses declared in contracts/http/notification/{v1,internal/v1}/openapi.yaml.
var statusByCode = map[string]int{
	apperr.CodeValidationFailed:      http.StatusBadRequest,
	apperr.CodeUnauthorized:          http.StatusUnauthorized,
	apperr.CodeForbidden:             http.StatusForbidden,
	apperr.CodeNotFound:              http.StatusNotFound,
	apperr.CodeMethodNotAllowed:      http.StatusMethodNotAllowed,
	apperr.CodeStateConflict:         http.StatusConflict,
	apperr.CodeIdempotencyConflict:   http.StatusConflict,
	apperr.CodeIdempotencyInProgress: http.StatusConflict,
	apperr.CodeRateLimited:           http.StatusTooManyRequests,
	apperr.CodeDependencyUnavailable: http.StatusServiceUnavailable,
	apperr.CodeInternal:              http.StatusInternalServerError,
}

var titleByCode = map[string]string{
	apperr.CodeValidationFailed:      "Invalid request",
	apperr.CodeUnauthorized:          "Unauthorized",
	apperr.CodeForbidden:             "Forbidden",
	apperr.CodeNotFound:              "Not found",
	apperr.CodeMethodNotAllowed:      "Method not allowed",
	apperr.CodeStateConflict:         "State conflict",
	apperr.CodeIdempotencyConflict:   "Idempotency key conflict",
	apperr.CodeIdempotencyInProgress: "Request in progress",
	apperr.CodeRateLimited:           "Too many requests",
	apperr.CodeDependencyUnavailable: "Dependency unavailable",
	apperr.CodeInternal:              "Unexpected error",
}

// StatusOf returns the HTTP status a code maps to (500 for unknown codes).
func StatusOf(code string) int {
	if status, ok := statusByCode[code]; ok {
		return status
	}
	return http.StatusInternalServerError
}

// WriteProblem renders any error as application/problem+json and aborts the
// chain. The internal cause is logged, never serialized: a client sees a
// stable code and a safe message. The detail of an unexpected error is
// replaced too, so a driver message can never reach a caller.
func WriteProblem(c *gin.Context, err error) {
	appErr := apperr.From(err)
	status := StatusOf(appErr.Code)

	detail := appErr.Detail
	if status >= http.StatusInternalServerError {
		logging.FromContext(c.Request.Context()).Error("request failed",
			zap.String("code", appErr.Code),
			zap.String("route", c.FullPath()),
			zap.Error(err),
		)
		if appErr.Code == apperr.CodeInternal {
			detail = "An unexpected error occurred."
		}
	}
	if detail == "" {
		detail = titleOf(appErr.Code)
	}

	if seconds := retryAfterSeconds(status, appErr.Retryable, appErr.RetryAfter); seconds > 0 {
		c.Header(constants.HeaderRetryAfter, strconv.Itoa(seconds))
	}

	problem := Problem{
		Type:      problemTypeBase + strings.ToLower(strings.ReplaceAll(appErr.Code, "_", "-")),
		Title:     titleOf(appErr.Code),
		Status:    status,
		Detail:    detail,
		Instance:  c.Request.URL.Path,
		Code:      appErr.Code,
		TraceID:   traceID(c),
		Retryable: appErr.Retryable,
	}
	for _, field := range appErr.Fields {
		problem.Errors = append(problem.Errors, FieldProblem{Field: field.Field, Message: field.Message})
	}

	// Marshalled by hand so the response keeps the problem+json media type;
	// gin's JSON helper would set application/json.
	payload, marshalErr := json.Marshal(problem)
	if marshalErr != nil {
		c.AbortWithStatus(http.StatusInternalServerError)
		return
	}

	c.Header(constants.HeaderCacheControl, constants.NoStore)
	c.Abort()
	c.Data(status, constants.ContentTypeProblem, payload)
}

// traceID returns the request id when it satisfies the schema length
// (16..64); a shorter caller-supplied id is omitted rather than rejected.
func traceID(c *gin.Context) string {
	id := reqctx.RequestID(c.Request.Context())
	if len(id) < 16 || len(id) > 64 {
		return ""
	}
	return id
}

func titleOf(code string) string {
	if title, ok := titleByCode[code]; ok {
		return title
	}
	return "Unexpected error"
}

// WriteJSON renders a success body. Every payload of this service is personal
// or operational data, so nothing may be cached (contract header NoStore).
func WriteJSON(c *gin.Context, status int, body any) {
	c.Header(constants.HeaderCacheControl, constants.NoStore)
	c.JSON(status, body)
}

// NoContent renders an empty success response.
func NoContent(c *gin.Context) {
	c.Header(constants.HeaderCacheControl, constants.NoStore)
	c.Status(http.StatusNoContent)
}
