package httpx

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/ecommerce/notification-service/internal/common/constants"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
)

// MaxBodyBytes caps every JSON body. The largest request of these APIs is a
// DLQ redrive filter; 64 KB leaves a wide margin without letting a caller
// hold a connection open with a large upload.
const MaxBodyBytes = 64 << 10

// bodyKey is where the raw body is cached in the gin context so the
// idempotency and audit middlewares and the handler can all read it once.
const bodyKey = "httpx.body"

// ReadBody returns the raw request body (at most MaxBodyBytes), reading it
// once and caching it for the rest of the chain. A too-large body is a
// validation error, not a transport failure.
func ReadBody(c *gin.Context) ([]byte, error) {
	if cached, ok := c.Get(bodyKey); ok {
		if raw, ok := cached.([]byte); ok {
			return raw, nil
		}
	}
	if c.Request.Body == nil {
		c.Set(bodyKey, []byte{})
		return []byte{}, nil
	}
	limited := http.MaxBytesReader(c.Writer, c.Request.Body, MaxBodyBytes)
	raw, err := io.ReadAll(limited)
	if err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			return nil, apperr.New(apperr.CodeValidationFailed, "The request body is too large.")
		}
		return nil, apperr.New(apperr.CodeValidationFailed, "The request body could not be read.")
	}
	c.Set(bodyKey, raw)
	// Restore a readable body for anything downstream that bypasses ReadBody.
	c.Request.Body = io.NopCloser(bytes.NewReader(raw))
	return raw, nil
}

// DecodeJSON parses the body strictly: an unknown field is rejected, matching
// additionalProperties:false in the OpenAPI schemas, and exactly one JSON
// value is accepted.
func DecodeJSON(c *gin.Context, dst any) error {
	if err := ensureJSONContentType(c); err != nil {
		return err
	}
	raw, err := ReadBody(c)
	if err != nil {
		return err
	}
	if len(bytes.TrimSpace(raw)) == 0 {
		return apperr.New(apperr.CodeValidationFailed, "A JSON body is required.")
	}
	return decodeStrict(raw, dst)
}

// DecodeOptionalJSON is DecodeJSON for endpoints whose body is optional
// (redrive): an absent or empty body leaves dst untouched and returns false.
func DecodeOptionalJSON(c *gin.Context, dst any) (bool, error) {
	raw, err := ReadBody(c)
	if err != nil {
		return false, err
	}
	if len(bytes.TrimSpace(raw)) == 0 {
		return false, nil
	}
	if err := ensureJSONContentType(c); err != nil {
		return false, err
	}
	return true, decodeStrict(raw, dst)
}

func decodeStrict(raw []byte, dst any) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(dst); err != nil {
		var appErr *apperr.Error
		if errors.As(err, &appErr) {
			return appErr
		}
		return apperr.New(apperr.CodeValidationFailed, "The request body is not valid JSON for this endpoint.")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return apperr.New(apperr.CodeValidationFailed, "The request body must contain exactly one JSON object.")
	}
	return nil
}

func ensureJSONContentType(c *gin.Context) error {
	header := strings.TrimSpace(c.GetHeader("Content-Type"))
	if header == "" {
		return apperr.New(apperr.CodeValidationFailed, "Content-Type application/json is required.")
	}
	mediaType, _, err := mime.ParseMediaType(header)
	if err != nil || !strings.EqualFold(mediaType, constants.ContentTypeJSON) {
		return apperr.New(apperr.CodeValidationFailed, "Content-Type must be application/json.")
	}
	return nil
}

// PathUUID reads a UUID path parameter; a malformed value is a 400 with the
// parameter named.
func PathUUID(c *gin.Context, name string) (uuid.UUID, error) {
	value, err := uuid.Parse(strings.TrimSpace(c.Param(name)))
	if err != nil {
		return uuid.Nil, apperr.New(apperr.CodeValidationFailed, "invalid path parameter").
			WithFields(apperr.Field{Field: name, Message: "must be a UUID"})
	}
	return value, nil
}

// PathID reads a UUID path parameter naming a resource and answers 404 when
// it is malformed: such a resource cannot exist, and the inbox/status/ops
// contracts declare no 400 for their id-addressed routes (11-privacy.md §5:
// never reveal existence, so 404 both for "not yours" and "not an id").
func PathID(c *gin.Context, name string) (uuid.UUID, error) {
	value, err := uuid.Parse(strings.TrimSpace(c.Param(name)))
	if err != nil {
		return uuid.Nil, apperr.ErrNotFound
	}
	return value, nil
}
