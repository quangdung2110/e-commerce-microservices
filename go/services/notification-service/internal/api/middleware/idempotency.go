package middleware

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/api/httpx"
	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/common/constants"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

const (
	minIdempotencyKeyLength = 8
	maxIdempotencyKeyLength = 128
)

// Idempotency implements the ops contract: every mutating ops call carries an
// Idempotency-Key kept 24 h; same key + same request replays the stored
// result, same key + different request is 409 IDEMPOTENCY_CONFLICT and a key
// whose first call has not finished is 409 IDEMPOTENCY_IN_PROGRESS
// (internal openapi.yaml, 16-operations.md §2).
//
// The request hash covers method, path and body, so the same key sent to a
// different endpoint is a conflict too. The stored response is replayed with
// its original status and JSON body; problem responses are stored as well so
// a retried bad request answers the same way.
func Idempotency(ops port.OpsRepository, clk clock.Clock) gin.HandlerFunc {
	return func(c *gin.Context) {
		key := strings.TrimSpace(c.GetHeader(constants.HeaderIdempotencyKey))
		if len(key) < minIdempotencyKeyLength || len(key) > maxIdempotencyKeyLength {
			httpx.WriteProblem(c, apperr.New(apperr.CodeValidationFailed, "Idempotency-Key header is required.").
				WithFields(apperr.Field{Field: "Idempotency-Key", Message: "must be 8 to 128 characters"}))
			return
		}

		body, err := httpx.ReadBody(c)
		if err != nil {
			httpx.WriteProblem(c, err)
			return
		}
		hash := RequestHash(c.Request.Method, c.Request.URL.RequestURI(), body)

		ctx := c.Request.Context()
		existing, err := ops.BeginIdempotent(ctx, key, hash, clk.Now())
		if err != nil {
			httpx.WriteProblem(c, apperr.Wrap(apperr.CodeDependencyUnavailable, "idempotency store unavailable", err).AsRetryable(0))
			return
		}
		if existing != nil {
			switch {
			case existing.RequestHash != hash:
				httpx.WriteProblem(c, apperr.New(apperr.CodeIdempotencyConflict,
					"This Idempotency-Key was already used with a different request."))
			case existing.Status == 0:
				httpx.WriteProblem(c, apperr.New(apperr.CodeIdempotencyInProgress,
					"A request with this Idempotency-Key is still being processed."))
			default:
				replay(c, existing.Status, existing.Response)
			}
			return
		}

		writer := &captureWriter{ResponseWriter: c.Writer}
		c.Writer = writer
		c.Next()

		status := writer.Status()
		if status == 0 {
			status = http.StatusOK
		}
		if err := ops.CompleteIdempotent(ctx, key, status, writer.body.Bytes()); err != nil {
			// The response already went out; the key stays "in progress" until
			// purge. Log it so an operator understands the later 409.
			logging.FromContext(ctx).Error("idempotency record not completed", zap.Error(err))
		}
	}
}

// RequestHash is sha256(method + "\n" + path + "\n" + body), hex encoded.
func RequestHash(method, path string, body []byte) string {
	h := sha256.New()
	h.Write([]byte(method))
	h.Write([]byte{'\n'})
	h.Write([]byte(path))
	h.Write([]byte{'\n'})
	h.Write(body)
	return hex.EncodeToString(h.Sum(nil))
}

func replay(c *gin.Context, status int, body []byte) {
	c.Header(constants.HeaderCacheControl, constants.NoStore)
	c.Abort()
	if len(body) == 0 {
		c.Status(status)
		c.Writer.WriteHeaderNow()
		return
	}
	contentType := constants.ContentTypeJSON
	if status >= 400 {
		contentType = constants.ContentTypeProblem
	}
	c.Data(status, contentType, body)
}

// captureWriter tees the response body so it can be stored for replay.
type captureWriter struct {
	gin.ResponseWriter
	body bytes.Buffer
}

func (w *captureWriter) Write(b []byte) (int, error) {
	w.body.Write(b)
	return w.ResponseWriter.Write(b)
}

func (w *captureWriter) WriteString(s string) (int, error) {
	w.body.WriteString(s)
	return w.ResponseWriter.WriteString(s)
}
