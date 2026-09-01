package response

import (
	"net/http"

	"cart-service/internal/api/dto/request"
	"cart-service/internal/domain"
)

// ProblemContentType is the media type every error response uses.
const ProblemContentType = "application/problem+json"

// problemTypeBase namespaces the machine-readable problem type URIs.
const problemTypeBase = "https://contracts.ecommerce.local/problems/cart/"

// Problem is an RFC 9457 problem document with this project's extensions
// (contracts/shared/json-schema/v1/problem.schema.json).
type Problem struct {
	Type     string `json:"type"`
	Title    string `json:"title"`
	Status   int    `json:"status"`
	Detail   string `json:"detail"`
	Instance string `json:"instance"`
	Code     string `json:"code"`

	TraceID   string               `json:"trace_id,omitempty"`
	Retryable bool                 `json:"retryable,omitempty"`
	Errors    []request.FieldError `json:"errors,omitempty"`
}

// NewProblem builds a problem document.
func NewProblem(status int, code domain.Code, detail, instance, traceID string) Problem {
	return Problem{
		Type:     problemTypeBase + string(code),
		Title:    http.StatusText(status),
		Status:   status,
		Detail:   detail,
		Instance: instance,
		Code:     string(code),
		TraceID:  traceID,
	}
}

// NewValidationProblem builds the 400 returned for a syntactically invalid
// request, listing every offending field.
func NewValidationProblem(instance, traceID string, fieldErrors []request.FieldError) Problem {
	problem := NewProblem(http.StatusBadRequest, domain.CodeValidationFailed,
		"the request body or headers did not satisfy the contract", instance, traceID)
	problem.Errors = fieldErrors
	return problem
}

// StatusFor maps a domain error code onto the HTTP status the OpenAPI
// contract defines for it.
func StatusFor(code domain.Code) int {
	switch code {
	case domain.CodeValidationFailed, domain.CodeDeltaInvalid:
		return http.StatusBadRequest
	case domain.CodeUnauthenticated:
		return http.StatusUnauthorized
	case domain.CodeIdempotencyConflict:
		return http.StatusConflict
	case domain.CodeCartFull,
		domain.CodeProductNotAvailable,
		domain.CodeQuantityInvalid,
		domain.CodeItemNotFound,
		domain.CodeProductSnapshotRequired:
		// Every one of these is a cart or catalog rule that the request as
		// written cannot satisfy, which is what 422 means in this contract.
		return http.StatusUnprocessableEntity
	case domain.CodeRateLimited:
		return http.StatusTooManyRequests
	case domain.CodeCartStoreUnavailable, domain.CodeProductUnavailable:
		return http.StatusServiceUnavailable
	default:
		return http.StatusInternalServerError
	}
}

// FromDomainError renders a domain error as a problem document.
func FromDomainError(err *domain.Error, instance, traceID string) Problem {
	status := StatusFor(err.Code)
	problem := NewProblem(status, err.Code, err.Detail, instance, traceID)
	problem.Retryable = err.Retryable
	if err.Field != "" {
		problem.Errors = []request.FieldError{{Field: err.Field, Message: err.Detail}}
	}
	return problem
}
