package httpx

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
)

// maxRequestBody caps the JSON body. Every request of this API is a small object.
const maxRequestBody = 64 << 10

// DecodeJSON parses the body strictly: an unknown field is rejected, matching
// additionalProperties:false in the OpenAPI schemas.
func DecodeJSON(c *gin.Context, dst any) error {
	if c.Request.Body == nil {
		return apperr.ErrValidation.WithDetail("A JSON body is required.")
	}

	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxRequestBody)
	decoder := json.NewDecoder(c.Request.Body)
	decoder.DisallowUnknownFields()

	if err := decoder.Decode(dst); err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			return apperr.ErrValidation.WithDetail("The request body is too large.")
		}
		return apperr.ErrValidation.WithDetail("The request body is not valid JSON for this endpoint.")
	}
	// A second JSON value in the same body is a malformed request, not a second
	// command.
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return apperr.ErrValidation.WithDetail("The request body must contain exactly one JSON object.")
	}
	return nil
}

// PathUUID reads a UUID path parameter.
func PathUUID(c *gin.Context, name string) (uuid.UUID, error) {
	value, err := uuid.Parse(c.Param(name))
	if err != nil {
		return uuid.Nil, apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   name,
			Message: "must be a UUID",
		})
	}
	return value, nil
}

// QueryLimit reads the page size, applying the contract bounds.
func QueryLimit(c *gin.Context, fallback, max int) (int, error) {
	raw := c.Query("limit")
	if raw == "" {
		return fallback, nil
	}

	limit, err := strconv.Atoi(raw)
	if err != nil || limit < 1 || limit > max {
		return 0, apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "limit",
			Message: "must be an integer between 1 and 100",
		})
	}
	return limit, nil
}

// WriteJSON renders a success body with the no-store header required on every
// response that carries a credential.
func WriteJSON(c *gin.Context, status int, body any) {
	c.Header(constants.HeaderCacheControl, constants.NoStore)
	c.JSON(status, body)
}

// NoContent renders an empty success response.
func NoContent(c *gin.Context) {
	c.Header(constants.HeaderCacheControl, constants.NoStore)
	c.Status(http.StatusNoContent)
}

// Accepted renders a 202 response.
func Accepted(c *gin.Context, body any) {
	if body == nil {
		c.Header(constants.HeaderCacheControl, constants.NoStore)
		c.Status(http.StatusAccepted)
		return
	}
	WriteJSON(c, http.StatusAccepted, body)
}
