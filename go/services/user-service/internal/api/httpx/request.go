package httpx

import (
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/user-service/internal/common/constants"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
)

// maxRequestBody caps the JSON body. Every request of this API is a small
// object; an address is the largest of them.
const maxRequestBody = 32 << 10

// DecodeJSON parses the body strictly: an unknown field is rejected, matching
// additionalProperties:false in the OpenAPI schemas.
func DecodeJSON(c *gin.Context, dst any) error {
	return decode(c, dst, "application/json")
}

// DecodeMergePatch parses a JSON Merge Patch body. RFC 7396 gives `null` the
// meaning "remove this member", so the target type has to record which members
// were present at all.
func DecodeMergePatch(c *gin.Context, dst any) error {
	return decode(c, dst, constants.ContentTypeMergePatch)
}

func decode(c *gin.Context, dst any, wantContentType string) error {
	if err := ensureContentType(c, wantContentType); err != nil {
		return err
	}
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
		var appErr *apperr.Error
		if errors.As(err, &appErr) {
			// A field decoder rejected the value with a domain error of its own.
			return appErr
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

// ensureContentType refuses a body sent under the wrong media type. The profile
// patch in particular has different semantics from a plain JSON replace, so the
// two must not be interchangeable.
func ensureContentType(c *gin.Context, want string) error {
	header := strings.TrimSpace(c.GetHeader("Content-Type"))
	if header == "" {
		return apperr.ErrValidation.WithDetail("Content-Type " + want + " is required.")
	}

	mediaType, _, err := mime.ParseMediaType(header)
	if err != nil || !strings.EqualFold(mediaType, want) {
		return apperr.ErrValidation.WithDetail("Content-Type must be " + want + ".")
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

// WriteJSON renders a success body. Every response of this service carries
// profile, address or preference data, so none of it may be cached by a browser
// or a proxy.
func WriteJSON(c *gin.Context, status int, body any) {
	c.Header(constants.HeaderCacheControl, constants.PrivateNoStore)
	c.JSON(status, body)
}

// WriteCreated renders a 201 with the Location of the new resource.
func WriteCreated(c *gin.Context, location string, body any) {
	c.Header(constants.HeaderLocation, location)
	WriteJSON(c, http.StatusCreated, body)
}

// NoContent renders an empty success response.
func NoContent(c *gin.Context) {
	c.Header(constants.HeaderCacheControl, constants.PrivateNoStore)
	c.Status(http.StatusNoContent)
}
